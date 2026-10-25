package io.github.mangi.eta.agent.device

import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 内存面板的数据源：进程 RSS 排行、「Eta 自己起的后台任务」，以及构建进程与一键清理。
 *
 * 数据全部走 root 读 `/proc`，原因见 [snapshot] 的注释；结束操作只针对自己的后代进程与
 * 构建/编译类进程，不碰系统进程，也不需要猜包名。
 */
internal object MemoryProcessProbe {
    data class Proc(
        val pid: Int,
        val ppid: Int,
        val rssBytes: Long,
        /** 展示用的短名：命令行首段取 basename（`java`、`com.foo:push`、`[kworker/0:1]`）。 */
        val name: String,
        /** 完整命令行，只用于识别构建进程（Gradle/Kotlin 的特征串在参数里，不在首段）。 */
        val cmdline: String = "",
    )

    private val executor by lazy {
        BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = { RootAccess.isGranted })
    }

    private val lineSplit = Regex("\\s+")

    /** 进面板的最小 RSS：8 MB（2048 页）。低于它的进程对清理没意义，但会让遍历变慢。 */
    private const val MIN_RSS_PAGES = 2048

    /**
     * 全量快照。
     *
     * 两条硬约束（都是踩过的坑）：
     * 1. 不用 `ps -o RSS`：本机 toybox 的 ps 只认小写列名，且对绝大多数进程把 RSS 报成 0，
     *    排行会几乎为空，看起来就像「释放没效果」；
     * 2. 不遍历全部进程的 stat/awk：1000+ 进程逐个 fork，面板打开要好几秒。
     *
     * 做法：先只读 `/proc/<pid>/statm` 第 2 字段（常驻页数）筛出 >= 8 MB 的候选，
     * 再只对候选读 cmdline 与 ppid。实测 188 个候选、约 1 秒。
     *
     * 输出每行：`pid ppid rssKb 命令行`。
     */
    fun snapshot(): List<Proc> {
        val script = listOf(
            "for p in /proc/[0-9]*; do",
            "  pid=\${p#/proc/}",
            "  case \"\$pid\" in ''|*[!0-9]*) continue;; esac",
            "  [ -r \"\$p/statm\" ] || continue",
            "  read -r _sz res _rest < \"\$p/statm\" 2>/dev/null || continue",
            "  [ \"\${res:-0}\" -gt $MIN_RSS_PAGES ] 2>/dev/null || continue",
            "  ppid=\$(awk '{print \$4}' \"\$p/stat\" 2>/dev/null)",
            "  cmd=\$(tr '\\0' ' ' < \"\$p/cmdline\" 2>/dev/null)",
            "  [ -z \"\$cmd\" ] && cmd=\"[\$(cat \"\$p/comm\" 2>/dev/null)]\"",
            "  echo \"\$pid \${ppid:-0} \$((res * 4)) \$cmd\"",
            "done",
            "exit 0",
        ).joinToString("\n")
        val result = executor.execute(script, timeoutMillis = 30_000L, maxOutputBytes = 1024 * 1024)
        if (!result.ok) return emptyList()
        return result.stdout.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@mapNotNull null
            val parts = line.split(lineSplit, limit = 4)
            if (parts.size < 3) return@mapNotNull null
            val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
            val ppid = parts[1].toIntOrNull() ?: 0
            val rssKb = parts[2].toLongOrNull() ?: return@mapNotNull null
            val cmdline = parts.getOrNull(3)?.trim()?.take(400).orEmpty()
            Proc(
                pid = pid,
                ppid = ppid,
                rssBytes = rssKb * 1024L,
                name = shortName(cmdline, pid),
                cmdline = cmdline,
            )
        }.toList()
    }

    /** 命令行首段取 basename；kernel 线程的 `[...]` 原样保留。 */
    private fun shortName(cmdline: String, pid: Int): String {
        val first = cmdline.trim().substringBefore(' ').trim()
        if (first.isEmpty()) return "[$pid]"
        if (first.startsWith("[")) return first.take(40)
        return first.substringAfterLast('/').ifEmpty { first }.take(40)
    }

    /**
     * 构建 / 编译类进程：编译 Eta 时真正把内存吃满的就是这些。
     *
     * 它们以 root 身份跑在 chroot 里，普通「按应用释放」根本看不到、也停不掉，
     * 所以单独成组给一个出口。
     */
    private val BUILD_PROCESS_HINTS = listOf(
        "GradleDaemon", "KotlinCompileDaemon", "kotlin-daemon", "K2JVMCompiler",
        "aapt2", "gradle-wrapper", "ksp", "R8", "d8",
    )

    fun isBuildProcess(proc: Proc): Boolean {
        val haystack = if (proc.cmdline.isNotEmpty()) proc.cmdline else proc.name
        return BUILD_PROCESS_HINTS.any { hint -> haystack.contains(hint, ignoreCase = true) }
    }

    /** 构建进程按占用降序；面板里单独分组展示并允许一键结束。 */
    fun buildProcesses(procs: List<Proc>): List<Proc> =
        procs.filter(::isBuildProcess).sortedByDescending(Proc::rssBytes)

    /** 一行的占用：同名多进程（:remote 之类）合并到同一个包名上。 */
    data class AppUsage(
        val packageName: String,
        val rssBytes: Long,
    )

    /**
     * 按应用聚合的内存排行。进程名 `com.foo:remote` 归到 `com.foo`；
     * `system_server`、`ps`、`[kthreadd]` 这类不是包名，原样保留，界面上查不到应用信息就只读展示。
     */
    fun appUsage(procs: List<Proc>, limit: Int): List<AppUsage> =
        procs.asSequence()
            .filter { it.pid > 0 && it.rssBytes > 0 && it.name.isNotBlank() }
            .groupBy { proc -> proc.name.substringBefore(':').trim() }
            .map { (name, group) -> AppUsage(name, group.sumOf(Proc::rssBytes)) }
            .sortedByDescending(AppUsage::rssBytes)
            .take(limit)
            .toList()

    /**
     * 释放一个用户应用：`am force-stop` 让系统把它整个停掉。
     * 只接受形如包名的参数，调用方保证不是系统应用、也不是 Eta 自己。
     */
    fun release(packageName: String): Boolean {
        if (!PACKAGE_NAME.matches(packageName)) return false
        val result = executor.execute(
            "am force-stop $packageName",
            timeoutMillis = 8_000L,
            maxOutputBytes = 16 * 1024,
        )
        return result.ok
    }

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.]*")

    /** /proc/meminfo 的 MemAvailable（KB）。口径与面板概览一致，用来算清理前后差值。 */
    fun memAvailableKb(): Long = runCatching {
        java.io.File("/proc/meminfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }
                ?.filter(Char::isDigit)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    data class QuickCleanResult(
        val rootOk: Boolean,
        val killedDaemons: Int,
        val availBeforeKb: Long,
        val availAfterKb: Long,
    ) {
        val freedKb: Long get() = (availAfterKb - availBeforeKb).coerceAtLeast(0L)
    }

    /**
     * 一键清理：结束构建/编译守护进程 + 回收页缓存。
     *
     * 只认命令行特征，不按进程名乱杀；不碰前台应用、系统进程、Eta 自己。
     * 成功判据是「脚本跑到了最后一行」而不是整体退出码 —— `drop_caches` 在部分内核
     * 不可写会把退出码带成非 0，之前因此误报「需要 Root 授权」。
     */
    fun quickClean(): QuickCleanResult {
        val before = memAvailableKb()
        val script = listOf(
            "n=0",
            "for p in /proc/[0-9]*; do",
            "  pid=\${p#/proc/}",
            "  case \"\$pid\" in ''|*[!0-9]*) continue;; esac",
            "  [ -r \"\$p/cmdline\" ] || continue",
            "  c=\$(tr '\\0' ' ' < \"\$p/cmdline\" 2>/dev/null)",
            "  [ -z \"\$c\" ] && continue",
            "  case \"\$c\" in",
            "    *GradleDaemon*|*KotlinCompileDaemon*|*kotlin-daemon*|*K2JVMCompiler*|*aapt2*)",
            "      kill \"\$pid\" 2>/dev/null && n=\$((n + 1));;",
            "  esac",
            "done",
            "sync",
            "echo 3 > /proc/sys/vm/drop_caches 2>/dev/null",
            "echo \"killed=\$n\"",
            "exit 0",
        ).joinToString("\n")
        val result = executor.execute(script, timeoutMillis = 30_000L, maxOutputBytes = 64 * 1024)
        val killed = Regex("killed=(\\d+)").find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
        runCatching { Thread.sleep(400L) }
        return QuickCleanResult(
            rootOk = killed != null,
            killedDaemons = killed ?: 0,
            availBeforeKb = before,
            availAfterKb = memAvailableKb(),
        )
    }

    /**
     * 当前进程的全部后代。Eta 起的终端会话、构建进程都是它的子进程，
     * 顺着 ppid 链找比按名字猜安全得多。
     */
    fun descendants(procs: List<Proc>, rootPid: Int): List<Proc> {
        val childrenByParent = procs.groupBy(Proc::ppid)
        val collected = mutableListOf<Proc>()
        val queue = ArrayDeque<Proc>()
        childrenByParent[rootPid]?.let(queue::addAll)
        val seen = mutableSetOf<Int>()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.pid == rootPid || !seen.add(current.pid)) continue
            collected += current
            childrenByParent[current.pid]?.let(queue::addAll)
        }
        return collected.sortedByDescending(Proc::rssBytes)
    }

    /** 结束指定进程；调用方保证这些 pid 都是自己的后代。返回是否发出成功。 */
    fun terminate(pids: List<Int>): Boolean {
        val targets = pids.filter { it > 1 }
        if (targets.isEmpty()) return false
        val result = executor.execute(
            "kill -TERM " + targets.joinToString(" "),
            timeoutMillis = 5_000L,
            maxOutputBytes = 16 * 1024,
        )
        return result.ok
    }
}
