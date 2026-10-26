package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 全量备份包（`.eta-fullbackup.zip`）的导出结果。 */
internal data class EtaFullBackupSummary(
    val fileName: String = "",
    val conversationCount: Int = 0,
    val privateFiles: Int = 0,
    val privateBytes: Long = 0L,
    val totalBytes: Long = 0L,
)

/**
 * 全量备份包：一个 zip，里面同时放
 *
 * - `eta-backup.json`：与手动导出完全一致的逻辑备份（会话、消息、记忆、角色卡、provider）；
 * - `private/`：应用私有目录里真正落盘的东西（databases / shared_prefs / files），
 *   用来兜住「JSON 表达不了」的部分（数据库原文件、DataStore 设置、草稿、技能、快照等）；
 * - `manifest.json`：包元信息（时间、文件数、字节数）。
 *
 * 刻意不打包 `files/terminal`、`files/terminal-user`：那是 Linux 终端与 chroot 的系统盘，
 * 动辄几个 GB，塞进备份里既慢又占地方，还原也用不上。需要连终端系统一起搬的话，
 * 可以单独用文件管理器打包。
 */
internal object EtaFullBackupArchive {

    const val FULL_SUFFIX = ".eta-fullbackup.zip"
    private const val JSON_ENTRY = "eta-backup.json"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val PRIVATE_DIR = "private"

    /** 顶层只收这三类，其余（app_*、code_cache、cache 等）不进包。 */
    private val includeTop = listOf("databases", "shared_prefs", "files")

    /** 相对 dataDir 需要跳过的目录。 */
    private val excludedRelative = setOf(
        "files/terminal",
        "files/terminal-user",
        "files/restore-staging",
    )

    /** 还原暂存区：解压到这里，下次进程启动最早期再覆盖到真实目录。 */
    fun stagingDirectory(context: Context): File = File(context.filesDir, "restore-staging")

    fun isFullBackup(name: String): Boolean = name.endsWith(FULL_SUFFIX, ignoreCase = true)

    private fun excluded(name: String): Boolean = excludedRelative.any { name == it || name.startsWith("$it/") }

    /**
     * 导出全量包。
     *
     * [output] 由调用方负责关闭；这里只往里写 zip 条目。
     */
    suspend fun export(
        context: Context,
        output: OutputStream,
        onPhase: (String) -> Unit = {},
    ): EtaFullBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val dataDir = appContext.dataDir
        var fileCount = 0
        var byteCount = 0L
        var conversations = 0
        val zip = ZipOutputStream(output.buffered(), Charsets.UTF_8)
        try {
            onPhase("full_json")
            zip.putNextEntry(ZipEntry(JSON_ENTRY).apply { time = System.currentTimeMillis() })
            val summary = EtaBackupRepository.export(appContext, zip)
            conversations = summary.conversationCount
            zip.closeEntry()

            onPhase("full_private")
            includeTop.forEach { top ->
                val root = File(dataDir, top)
                if (!root.isDirectory) return@forEach
                val files = root.walkTopDown()
                    .onEnter { dir -> !excluded(dir.relativeTo(dataDir).invariantSeparatorsPath) }
                    .filter { it.isFile }
                for (file in files) {
                    currentCoroutineContext().ensureActive()
                    val relative = file.relativeTo(dataDir).invariantSeparatorsPath
                    if (excluded(relative)) continue
                    try {
                        zip.putNextEntry(ZipEntry("$PRIVATE_DIR/$relative").apply { time = file.lastModified() })
                        file.inputStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                zip.write(buffer, 0, count)
                                byteCount += count
                            }
                        }
                        zip.closeEntry()
                        fileCount++
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        AndroidAgentLogger.warn("Full backup skip file: type=${error.safeLogType()}")
                        runCatching { zip.closeEntry() }
                    }
                }
            }

            onPhase("full_manifest")
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY).apply { time = System.currentTimeMillis() })
            zip.write(
                manifestJson(
                    exportedAt = System.currentTimeMillis(),
                    conversationCount = conversations,
                    privateFiles = fileCount,
                    privateBytes = byteCount,
                    appVersion = appVersionName(appContext),
                ).toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            runCatching { zip.closeEntry() }
            throw error
        } finally {
            runCatching { zip.finish() }
        }
        EtaFullBackupSummary(
            conversationCount = conversations,
            privateFiles = fileCount,
            privateBytes = byteCount,
            totalBytes = byteCount,
        )
    }

    /**
     * 把全量包解到暂存区，等下次启动最早期整体覆盖。
     *
     * 只解 `private/` 下的内容；`eta-backup.json` 由调用方另外读出交给
     * [EtaBackupRepository.import] 走正常的逻辑还原。
     */
    suspend fun stage(
        context: Context,
        input: InputStream,
        onPhase: (String) -> Unit = {},
    ): EtaFullBackupSummary = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val staging = stagingDirectory(appContext)
        deleteRecursively(staging)
        val privateRoot = File(staging, PRIVATE_DIR)
        privateRoot.mkdirs()
        var fileCount = 0
        var byteCount = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                if (!name.startsWith("$PRIVATE_DIR/")) {
                    zip.closeEntry()
                    continue
                }
                val relative = name.removePrefix("$PRIVATE_DIR/")
                if (relative.isBlank() || relative.contains("..")) {
                    zip.closeEntry()
                    continue
                }
                if (excluded(relative)) {
                    zip.closeEntry()
                    continue
                }
                onPhase("restore_" + relative.substringBefore('/'))
                val target = File(privateRoot, relative)
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        byteCount += count
                    }
                }
                zip.closeEntry()
                fileCount++
            }
        }
        EtaFullBackupSummary(privateFiles = fileCount, privateBytes = byteCount, totalBytes = byteCount)
    }

    /** 有没有等待应用的还原。 */
    fun hasPendingRestore(context: Context): Boolean = File(stagingDirectory(context), PRIVATE_DIR).isDirectory

    /**
     * 进程启动最早期调用：把暂存区里的私有目录内容覆盖回 dataDir。
     *
     * 必须在 Room / DataStore 打开前执行；databases 里的 `-wal` / `-shm` 先删掉，
     * 避免旧库原文件配上新日志导致 SQLite 校验失败。
     */
    fun applyPendingRestore(context: Context): Boolean {
        val appContext = context.applicationContext
        val staging = stagingDirectory(appContext)
        val privateRoot = File(staging, PRIVATE_DIR)
        if (!privateRoot.isDirectory) return false
        val dataDir = appContext.dataDir
        var restored = 0
        return runCatching {
            privateRoot.walkTopDown().filter { it.isFile }.forEach { source ->
                val relative = source.relativeTo(privateRoot).invariantSeparatorsPath
                if (relative.isBlank() || relative.contains("..")) return@forEach
                val target = File(dataDir, relative)
                target.parentFile?.mkdirs()
                if (relative.startsWith("databases/")) {
                    File(target.path + "-wal").delete()
                    File(target.path + "-shm").delete()
                    File(target.path + "-journal").delete()
                }
                source.copyTo(target, overwrite = true)
                restored++
            }
            deleteRecursively(staging)
            AndroidAgentLogger.info("Full backup restore applied: files=$restored")
            true
        }.onFailure {
            AndroidAgentLogger.warn("Full backup restore failed: type=${it.safeLogType()}")
        }.getOrDefault(false)
    }

    /** 从全量包里只取出 `eta-backup.json`，交给现有导入链路。 */
    suspend fun extractDocument(context: Context, input: InputStream): File = withContext(Dispatchers.IO) {
        val target = File(File(context.cacheDir, "fullbackup"), "eta-backup.json")
        target.parentFile?.mkdirs()
        var found = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == JSON_ENTRY) {
                    target.outputStream().use { out -> zip.copyTo(out) }
                    found = true
                    zip.closeEntry()
                    break
                }
                zip.closeEntry()
            }
        }
        require(found) { "全量包里没有 eta-backup.json" }
        target
    }

    private fun manifestJson(
        exportedAt: Long,
        conversationCount: Int,
        privateFiles: Int,
        privateBytes: Long,
        appVersion: String,
    ): String = buildString {
        append('{')
        append("\"format\":\"eta-fullbackup\",")
        append("\"schemaVersion\":1,")
        append("\"exportedAt\":").append(exportedAt).append(',')
        append("\"appVersion\":\"").append(appVersion.replace("\"", "")).append("\",")
        append("\"conversationCount\":").append(conversationCount).append(',')
        append("\"privateFiles\":").append(privateFiles).append(',')
        append("\"privateBytes\":").append(privateBytes)
        append('}')
    }

    private fun appVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        runCatching { file.walkBottomUp().forEach { it.delete() } }
    }
}
