package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一项能力检测结果：能干什么 + 现在是否真的可用。 */
internal data class LinuxCapabilityItem(
    val key: String,
    val title: String,
    val purpose: String,
    val ready: Boolean,
)

/**
 * 能力检测分两层：
 * 1. 首选在实际 Linux 环境里跑一次 `command -v`——判据与真正执行命令时的 PATH 完全一致，
 *    符号链接、包装脚本、发行版自带工具都能正确识别；
 * 2. 环境不可用或探测失败时回退到静态判定，且只判真实文件与目录。
 *
 * 静态回退必须避开符号链接：rootfs 由 root 解包，其中链接的 SELinux 标签不带 App 的
 * MLS 类别，App 进程对它们连 lstat 都会被拒（EACCES），`java.io.File.exists()` 恒为 false。
 * 同理，`/usr/local/bin/node -> /opt/eta/node/...` 这种绝对链接在 chroot 内有效，
 * 在 App 进程里会按宿主根路径解析，也必然不存在。
 */
internal object LinuxCapabilityProbe {
    private data class Capability(
        val key: String,
        val title: String,
        val purpose: String,
        val commands: List<String>,
        /** true 表示列表里的命令必须全部存在；false 表示任一存在即可。 */
        val requireAll: Boolean = false,
    )

    private const val OUTPUT_PREFIX = "eta-cap:"
    private const val PROBE_TIMEOUT_SECONDS = 20L

    private val capabilities = listOf(
        Capability(
            key = "shell",
            title = "基础命令",
            purpose = "运行脚本、文件与管道操作",
            commands = listOf("sh", "dash", "bash", "busybox"),
        ),
        Capability(
            key = "text",
            title = "文本处理",
            purpose = "grep / sed / awk 检索与改写文本",
            commands = listOf("grep", "sed"),
            requireAll = true,
        ),
        Capability(
            key = "archive",
            title = "压缩归档",
            purpose = "tar / zip / unzip 打包与解包",
            commands = listOf("tar"),
        ),
        Capability(
            key = "python",
            title = "Python 3",
            purpose = "写脚本、做数据处理",
            commands = listOf("python3"),
        ),
        Capability(
            key = "uv",
            title = "uv",
            purpose = "Python 依赖与工具管理",
            commands = listOf("uv"),
        ),
        Capability(
            key = "node",
            title = "Node.js / npm",
            purpose = "JS 工具链与 npm 包",
            commands = listOf("node"),
        ),
        Capability(
            key = "ssh",
            title = "SSH",
            purpose = "连接服务器、传文件",
            commands = listOf("ssh", "scp"),
        ),
        Capability(
            key = "kimi",
            title = "Kimi Code",
            purpose = "命令行 AI 编程助手",
            commands = listOf("kimi"),
        ),
        Capability(
            key = "java",
            title = "Java 运行时",
            purpose = "jadx / smali 等需要 JVM",
            commands = listOf("java"),
        ),
        Capability(
            key = "apk",
            title = "APK 分析",
            purpose = "jadx 反编译、apktool 解包、smali / baksmali",
            commands = listOf("jadx", "apktool"),
            requireAll = true,
        ),
        Capability(
            key = "net",
            title = "网络下载",
            purpose = "curl / wget / git 获取资源",
            commands = listOf("curl", "wget", "git"),
        ),
    )

    suspend fun probe(context: Context, distribution: LinuxDistribution): List<LinuxCapabilityItem> {
        val rootfs = LinuxEnvironmentPaths.rootfsDir(context, distribution)
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath)) {
            return staticProbe(rootfs)
        }
        val observed = probeInBackend(distribution, rootfs) ?: return staticProbe(rootfs)
        return capabilities.map { capability ->
            LinuxCapabilityItem(
                key = capability.key,
                title = capability.title,
                purpose = capability.purpose,
                ready = observed[capability.key] == true,
            )
        }
    }

    /** 在目标 Linux 环境里批量执行 `command -v`；整体失败返回 null，由调用方回退静态判定。 */
    private suspend fun probeInBackend(
        distribution: LinuxDistribution,
        rootfs: File,
    ): Map<String, Boolean>? {
        val script = buildString {
            appendLine("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH")
            capabilities.forEach { capability ->
                appendLine(if (capability.requireAll) "eta_ok=1" else "eta_ok=0")
                capability.commands.forEach { command ->
                    appendLine(
                        if (capability.requireAll) {
                            "command -v $command >/dev/null 2>&1 || eta_ok=0"
                        } else {
                            "command -v $command >/dev/null 2>&1 && eta_ok=1"
                        },
                    )
                }
                appendLine("printf '$OUTPUT_PREFIX${capability.key}=%s\\n' \"\$eta_ok\"")
            }
            appendLine("exit 0")
        }
        val result = withContext(Dispatchers.IO) {
            InstallerShellRunner.run(
                command = script,
                timeoutSeconds = PROBE_TIMEOUT_SECONDS,
                environment = distribution.terminalEnvironment,
                linuxRootfsPath = rootfs.absolutePath,
            )
        }
        if (result.exitCode != 0) return null
        val observed = result.output.lineSequence()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (!trimmed.startsWith(OUTPUT_PREFIX)) return@mapNotNull null
                val body = trimmed.removePrefix(OUTPUT_PREFIX)
                val key = body.substringBefore('=', "")
                if (key.isEmpty()) return@mapNotNull null
                key to (body.substringAfter('=', "").trim() == "1")
            }
            .toMap()
        return observed.takeIf { it.size == capabilities.size }
    }

    /**
     * 静态回退：只看真实文件与目录。
     * 不要在这里判 `/bin/sh`、`/usr/local/bin/node` 这类符号链接，App 进程 stat 不到。
     */
    private fun staticProbe(rootfs: File): List<LinuxCapabilityItem> {
        fun file(vararg relative: String): Boolean = relative.any { File(rootfs, it).isFile }
        fun dir(vararg relative: String): Boolean = relative.any { File(rootfs, it).isDirectory }

        val ready = mapOf(
            // Debian 13 为 usrmerge：/bin 与 /bin/sh 都是链接，判真实 shell 二进制。
            "shell" to file("usr/bin/dash", "usr/bin/bash", "usr/bin/busybox", "bin/busybox"),
            "text" to (file("usr/bin/grep", "bin/grep") && file("usr/bin/sed", "bin/sed")),
            "archive" to file("usr/bin/tar", "bin/tar"),
            // uv 装的 Python 全在 opt/eta/python 下，usr/local/bin/python3 只是链接。
            "python" to (dir("opt/eta/python") || file("usr/bin/python3.13", "usr/bin/python3.14")),
            "uv" to dir("opt/eta/uv"),
            // Node 归档落在 opt/eta/node/<version>，usr/local/bin/node 只是链接。
            "node" to (dir("opt/eta/node") || file("usr/bin/node")),
            "ssh" to file("usr/bin/ssh", "bin/ssh", "usr/bin/scp", "bin/scp"),
            "kimi" to file("usr/local/lib/node_modules/@moonshot-ai/kimi-code/dist/main.mjs"),
            "java" to (
                file("opt/eta/apk-analysis/current/bin/java") ||
                    dir("usr/lib/jvm")
                ),
            "apk" to (
                file("opt/eta/apk-analysis/current/jadx/bin/jadx") &&
                    file("opt/eta/apk-analysis/current/bin/apktool")
                ),
            "net" to file("usr/bin/curl", "bin/curl", "usr/bin/wget", "bin/wget", "usr/bin/git", "bin/git"),
        )
        return capabilities.map { capability ->
            LinuxCapabilityItem(
                key = capability.key,
                title = capability.title,
                purpose = capability.purpose,
                ready = ready[capability.key] == true,
            )
        }
    }
}
