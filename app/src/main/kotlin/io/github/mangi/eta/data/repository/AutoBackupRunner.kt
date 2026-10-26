package io.github.mangi.eta.data.repository

import android.content.Context
import android.os.Environment
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.AutoBackupInterval
import io.github.mangi.eta.data.model.AutoBackupKeepCount
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 自动备份的结果，供界面提示与日志使用。 */
internal sealed interface AutoBackupResult {
    data class Done(val fileName: String, val conversationCount: Int, val kept: Int) : AutoBackupResult
    data class Skipped(val reason: String) : AutoBackupResult
    data class Failed(val message: String) : AutoBackupResult
}

/**
 * 定期自动备份：把 Eta 数据导出到共享目录的 `Eta备份/` 下，并按保留份数删掉最旧的。
 *
 * 复用「数据备份」页同一份导出实现（[EtaBackupRepository.export]），产物与手动导出完全一致，
 * 需要还原时直接走「数据备份 → 导入」即可。
 *
 * 省电策略：先比较一份轻量指纹（会话数 / 消息数 / 用量记录数），内容没变就只推进检查时间、
 * 不写文件，避免一天一次的白写盘。到点唤醒由 [io.github.mangi.eta.agent.automation.AutoBackupAlarm] 负责。
 *
 * 可靠性约定（这三条是这一版补的）：
 * 1. 全流程串行：闹钟、应用启动、回前台、手动「立即备份一次」都走同一把锁，不会两个协程各写一份；
 * 2. 先写 `.part` 再改名：导出中途被杀只会留下临时文件并当场清掉，不会出现半截 `.eta-backup.json`
 *    占着保留名额、或被当成「最近一次备份」；
 * 3. 失败落状态：失败原因与时间写进设置，界面能直接显示「为什么没备份成」，成功时清空。
 */
internal object AutoBackupRunner {

    const val DIR_NAME = "Eta备份"
    private const val FILE_PREFIX = "Eta-自动备份-"
    private const val FILE_SUFFIX = ".eta-backup.json"
    /** 全量包沿用旧名字前缀，方便和以前的备份放在一起看。 */
    private const val FULL_PREFIX = "Eta-全量备份-"
    private const val FULL_SUFFIX = EtaFullBackupArchive.FULL_SUFFIX
    private const val TEMP_SUFFIX = ".part"

    /** 同一时刻只允许一次备份在跑。 */
    private val mutex = Mutex()

    /** 默认备份目录（忽略自定义配置）。 */
    fun defaultDirectory(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    /**
     * 备份目录：自定义路径优先——绝对路径直接用，相对路径按内部存储根解析；为空时用默认目录。
     * 默认参数取 [SettingsDataStore.cachedAutoBackupDirectory]，供界面等非挂起调用使用。
     */
    fun directory(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): File {
        val trimmed = customDirectory.trim()
        if (trimmed.isEmpty()) return defaultDirectory()
        return if (trimmed.startsWith("/")) File(trimmed)
        else File(Environment.getExternalStorageDirectory(), trimmed)
    }

    /** 已存在的自动备份文件，最新在前。 */
    fun backups(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): List<File> = directory(customDirectory)
        .listFiles { file: File ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX)
        }
        ?.sortedByDescending { it.name }
        .orEmpty()

    /** 已存在的全量包（.eta-fullbackup.zip），最新在前。 */
    fun fullBackups(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): List<File> =
        directory(customDirectory)
            .listFiles { file: File -> file.isFile && EtaFullBackupArchive.isFullBackup(file.name) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    /** JSON 备份 + 全量包，按文件名倒序（两套命名的时间戳格式一致，可以直接混排）。 */
    fun allBackups(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): List<File> =
        (backups(customDirectory) + fullBackups(customDirectory)).sortedByDescending { it.name }

    /**
     * 目录里真实存在的最新一份备份。
     *
     * 界面显示「最近备份」时必须用它，而不是直接用设置里记的文件名：
     * 文件可能被用户清理掉、或换过备份目录，光看记录会显示一个根本不存在的文件。
     */
    fun latestBackup(customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory()): File? =
        allBackups(customDirectory).firstOrNull()

    /** 上次记录的文件名如果确实还在磁盘上就返回它，否则回落到目录里最新的一份。 */
    fun resolveLastBackup(
        recordedName: String,
        customDirectory: String = SettingsDataStore.cachedAutoBackupDirectory(),
    ): File? {
        val all = allBackups(customDirectory)
        if (recordedName.isNotBlank()) {
            all.firstOrNull { it.name == recordedName }?.let { return it }
        }
        return all.firstOrNull()
    }

    /** 目录里残留的 `.part`（上次导出被杀留下的），下次跑之前顺手清掉。 */
    private fun staleTempFiles(customDirectory: String): List<File> = directory(customDirectory)
        .listFiles { file: File ->
            file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(TEMP_SUFFIX)
        }
        .orEmpty()
        .toList()

    /**
     * 到点且内容有变化才执行；[force] 为 true 时忽略开关、周期与指纹（手动「立即备份一次」）。
     * 任何失败都只记录日志与状态，不抛出，避免影响启动或闹钟回调。
     */
    suspend fun maybeRun(context: Context, force: Boolean = false): AutoBackupResult =
        withContext(Dispatchers.IO) {
            mutex.withLock { runLocked(context, force) }
        }

    private suspend fun runLocked(context: Context, force: Boolean): AutoBackupResult {
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
            ?: return AutoBackupResult.Skipped("设置不可读")
        if (!force && !settings.autoBackupEnabled) {
            return AutoBackupResult.Skipped("未开启自动备份")
        }
        val intervalMinutes = AutoBackupInterval.effectiveMinutes(
            settings.autoBackupIntervalMinutes,
            settings.autoBackupIntervalHours,
        )
        val intervalMs = intervalMinutes * 60_000L
        val now = System.currentTimeMillis()
        if (!force && settings.autoBackupLastAt > 0L && now - settings.autoBackupLastAt < intervalMs) {
            return AutoBackupResult.Skipped("未到备份周期")
        }
        val fingerprint = fingerprint(context)
        // 短周期（≤ 60 分钟）时每次都写一份，保证「每 10 分钟一份」不会被内容指纹挡掉。
        val alwaysWrite = intervalMinutes <= AutoBackupInterval.ALWAYS_WRITE_MAX_MINUTES
        if (!force && !alwaysWrite && fingerprint.isNotEmpty() && fingerprint == settings.autoBackupFingerprint) {
            runCatching {
                SettingsDataStore.setAutoBackupState(
                    lastAt = now,
                    fingerprint = fingerprint,
                    fileName = settings.autoBackupLastFileName,
                    error = settings.autoBackupLastError,
                    errorAt = settings.autoBackupLastErrorAt,
                )
            }
            return AutoBackupResult.Skipped("数据没有变化")
        }
        return runNow(
            context = context,
            keep = AutoBackupKeepCount.normalize(settings.autoBackupKeepCount),
            fingerprint = fingerprint,
            customDirectory = settings.autoBackupDirectory,
            format = settings.autoBackupFormat,
            now = now,
        )
    }

    /** 轻量指纹：只读三个计数，不碰数据库文件本身。取不到时返回空串（空串不做跳过判断）。 */
    private suspend fun fingerprint(context: Context): String = runCatching {
        val database = EtaDatabase.get(context)
        val conversations = database.conversationDao().conversationCount()
        val messages = database.conversationDao().messageCount()
        val usage = database.tokenUsageDao().recordCount()
        "c=$conversations;m=$messages;u=$usage"
    }.getOrDefault("")

    private suspend fun runNow(
        context: Context,
        keep: Int,
        fingerprint: String,
        customDirectory: String,
        format: String,
        now: Long,
    ): AutoBackupResult {
        val dir = directory(customDirectory)
        if (!dir.exists() && !dir.mkdirs()) {
            return fail(now, "无法创建备份目录 " + dir.absolutePath)
        }
        if (!dir.isDirectory || !dir.canWrite()) {
            return fail(now, "备份目录不可写 " + dir.absolutePath)
        }
        // 上一次导出被系统杀掉留下的临时文件，先清掉，免得越积越多。
        staleTempFiles(customDirectory).forEach { it.delete() }

        val full = io.github.mangi.eta.data.model.AutoBackupFormat.isFull(format)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val target = if (full) File(dir, "$FULL_PREFIX$stamp$FULL_SUFFIX")
        else File(dir, "$FILE_PREFIX$stamp$FILE_SUFFIX")
        val temp = File(dir, target.name + TEMP_SUFFIX)
        return try {
            val conversationCount = temp.outputStream().use { stream ->
                if (full) EtaFullBackupArchive.export(context, stream).conversationCount
                else EtaBackupRepository.export(context, stream).conversationCount
            }
            if (!commit(temp, target)) {
                temp.delete()
                return fail(now, "备份文件落盘失败 " + target.absolutePath)
            }
            val kept = prune(keep, customDirectory, full)
            runCatching {
                SettingsDataStore.setAutoBackupState(
                    lastAt = System.currentTimeMillis(),
                    fingerprint = fingerprint,
                    fileName = target.name,
                )
            }
            AutoBackupResult.Done(target.name, conversationCount, kept)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            temp.delete()
            AndroidAgentLogger.warn("Auto backup failed: type=${error.safeLogType()}")
            fail(
                now,
                error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName,
            )
        }
    }

    /**
     * 把临时文件改名成正式备份文件。
     *
     * 同目录 rename 在共享存储上是原子的；个别机型/ROM 的 FUSE 实现会拒绝跨 UID 的改名
     * （例如应用重装后 UID 变了，目录里的旧文件属主还是上一个 UID），这时退化成直接复制一份，
     * 保证备份本身不因为改名失败而丢掉。
     */
    private fun commit(temp: File, target: File): Boolean {
        if (target.exists()) target.delete()
        if (temp.renameTo(target)) return true
        return runCatching {
            temp.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 8) }
            }
            temp.delete()
            target.length() > 0L
        }.getOrDefault(false)
    }

    private suspend fun fail(now: Long, message: String): AutoBackupResult {
        // 失败也要推进「上次检查时间」，否则每次打开应用都会立刻重试同一个失败，
        // 既刷日志又反复占用 IO；下一个周期再试，手动「立即备份一次」不受影响。
        val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
        runCatching {
            SettingsDataStore.setAutoBackupState(
                lastAt = now,
                fingerprint = settings?.autoBackupFingerprint.orEmpty(),
                fileName = settings?.autoBackupLastFileName.orEmpty(),
                error = message,
                errorAt = now,
            )
        }
        return AutoBackupResult.Failed(message)
    }

    private fun prune(keep: Int, customDirectory: String, full: Boolean): Int {
        val pool = if (full) fullBackups(customDirectory) else backups(customDirectory)
        pool.drop(keep.coerceAtLeast(1)).forEach { stale ->
            if (!stale.delete()) {
                AndroidAgentLogger.warn("Auto backup prune skipped: ~" + stale.length() + "B")
            }
        }
        return (if (full) fullBackups(customDirectory) else backups(customDirectory)).size
    }
}
