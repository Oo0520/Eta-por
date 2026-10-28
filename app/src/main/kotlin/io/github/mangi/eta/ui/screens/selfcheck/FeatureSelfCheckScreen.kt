package io.github.mangi.eta.ui.screens.selfcheck

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mangi.eta.core.CrashRecorder
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ChunkedTextDao
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.TextStoreMaintenance
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STATE_OK = "✔ 正常"
private const val STATE_MISSING = "✘ 待处理"
private const val STATE_UNKNOWN = "● 未实测"
private const val STATE_PLATFORM = "● 系统不提供"

/** 文本库体量单独一路加载，用这个名字在列表里替换占位项。 */
private const val TEXT_STORE_NAME = "文本库体量"

/** su 常见落点：只用来在「未实测」时显示位置，不再当授权依据。 */
private val SU_PATHS = listOf(
    "/system/bin/su",
    "/system/xbin/su",
    "/sbin/su",
    "/debug_ramdisk/su",
    "/data/adb/ksu/bin/su",
)

/** 单项自检结果：权限类项目带一个可跳转的 uri，方便直接去修。 */
private data class SelfCheckItem(
    val name: String,
    val state: String,
    val detail: String,
    val actionUri: String? = null,
)

/** 未实测项的名字，摘要里直接点名；不点名用户会以为是 Root（它其实只在首次进入时未实测）。 */
private fun SelfCheckReport.unknownSummary(): String =
    (permissions + storage).filter { it.state == STATE_UNKNOWN }.joinToString("、") { it.name }

private data class SelfCheckReport(
    val permissions: List<SelfCheckItem>,
    val storage: List<SelfCheckItem>,
) {
    val total: Int get() = permissions.size + storage.size
    val okCount: Int get() = (permissions + storage).count { it.state == STATE_OK }
    /** 「未实测」不算故障，但要单独计数，避免摘要说「全部就绪」。 */
    val unknownCount: Int get() = (permissions + storage).count { it.state == STATE_UNKNOWN }
    /** 平台不提供读数的项：测过了，但系统接口拿不到结果，点多少次都不会变。 */
    val platformCount: Int get() = (permissions + storage).count { it.state == STATE_PLATFORM }
    val problemCount: Int get() = (permissions + storage).count { it.state == STATE_MISSING }
}

/** 设置 → 关于 → 功能自检：只查两类会静默失效的东西——跑起来要的授权、会悄悄坏掉的本机数据能力。 */
@Composable
internal fun FeatureSelfCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var report by remember { mutableStateOf<SelfCheckReport?>(null) }
    var textStoreItem by remember { mutableStateOf<SelfCheckItem?>(null) }
    var round by remember { mutableStateOf(0) }
    // 两组各自可折叠；进页面就展开，避免看起来像「什么都没检测」。
    var showPermissions by remember { mutableStateOf(true) }
    var showStorage by remember { mutableStateOf(true) }
    // 首次进页面不跑 su（会弹授权框）；只有用户点「重新检测」才实测。
    LaunchedEffect(round) {
        report = withContext(Dispatchers.IO) { runSelfCheck(context, probeRoot = round > 0) }
    }
    // 文本库体量是 agent_text_chunks 全表 GROUP BY，慢，单独一路补，别拖住权限组。
    LaunchedEffect(round) {
        textStoreItem = withContext(Dispatchers.IO) { probeTextStore(context) }
    }

    MiuixScaffoldPage(title = "功能自检", onBack = onBack) {
        item(key = "selfcheck-head") {
            val current = report
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "重新检测",
                    summary = when {
                        current == null -> "正在检测…"
                        current.problemCount == 0 && current.unknownCount == 0 && current.platformCount == 0 ->
                            "共 ${current.total} 项，运行条件全部就绪"
                        current.problemCount == 0 && current.unknownCount == 0 ->
                            "共 ${current.total} 项，运行条件就绪（${current.platformCount} 项系统不提供读数）"
                        current.problemCount == 0 ->
                            "共 ${current.total} 项，" + current.unknownSummary() + " 未实测（点「重新检测」重试）"
                        else ->
                            "共 ${current.total} 项，有 ${current.problemCount} 项待处理" +
                                (current.unknownSummary().takeIf { it.isNotEmpty() }?.let { "、" + it + " 未实测" } ?: "") +
                                "（点条目可跳转处理）"
                    },
                    onClick = { round += 1 },
                )
            }
        }

        item(key = "selfcheck-permissions-head") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "① 权限与环境",
                    summary = if (showPermissions) "点一下收起" else "已收起，点一下展开（${report?.permissions?.size ?: 0} 项）",
                    onClick = { showPermissions = !showPermissions },
                )
            }
        }

        if (showPermissions) item(key = "selfcheck-permissions") {
            EtaPreferenceGroup {
                val items = report?.permissions.orEmpty()
                if (items.isEmpty()) {
                    EtaPreference(title = "读取中", summary = "…")
                } else {
                    items.forEachIndexed { index, item ->
                        if (index > 0) EtaPreferenceDivider()
                        if (item.actionUri != null) {
                            EtaArrowPreference(
                                title = "${item.state}  ${item.name}",
                                summary = item.detail,
                                onClick = { openUri(context, item.actionUri) },
                            )
                        } else {
                            EtaPreference(title = "${item.state}  ${item.name}", summary = item.detail)
                        }
                    }
                }
            }
        }

        item(key = "selfcheck-storage-head") {
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = "② 数据与存储",
                    summary = if (showStorage) "点一下收起" else "已收起，点一下展开（${report?.storage?.size ?: 0} 项）",
                    onClick = { showStorage = !showStorage },
                )
            }
        }

        if (showStorage) item(key = "selfcheck-storage") {
            EtaPreferenceGroup {
                val items = report?.storage.orEmpty().map { item ->
                    if (item.name == TEXT_STORE_NAME) textStoreItem ?: item else item
                }
                if (items.isEmpty()) {
                    EtaPreference(title = "读取中", summary = "…")
                } else {
                    items.forEachIndexed { index, item ->
                        if (index > 0) EtaPreferenceDivider()
                        EtaPreference(title = "${item.state}  ${item.name}", summary = item.detail)
                    }
                }
            }
        }

        item(key = "selfcheck-textstore-trim") {
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = if (busy) "正在瘦身…" else "文本库瘦身",
                    summary = "把超上限的历史字段按同一条规则保尾截断并重写引用头；只动文本分块，不碰会话、消息与设置",
                    onClick = {
                        if (!busy) {
                            busy = true
                            scope.launch {
                                val done = withContext(Dispatchers.IO) {
                                    runCatching { TextStoreMaintenance.trimOversized(EtaDatabase.get(context)) }.getOrNull()
                                }
                                busy = false
                                android.widget.Toast.makeText(
                                    context,
                                    done?.let { "瘦身完成：${it.trimmedFields} 个字段 · 省 ${it.savedChars} 字符" } ?: "瘦身失败",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                round += 1
                            }
                        }
                    },
                )
            }
        }

    }
}

/** 跳去修权限。package: 必须走 ACTION_APPLICATION_DETAILS_SETTINGS——实测 AOSP 没有 Activity 接 ACTION_VIEW package:。 */
private fun openUri(context: Context, uri: String) {
    val intent = when {
        uri.startsWith("package:") ->
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse(uri))
        uri == "accessibility:" -> Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
        uri == "battery:" -> Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        uri == "alarm:" ->
            Intent(
                AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:${context.packageName}"),
            )
        uri == "listener:" -> Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        else -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        android.widget.Toast.makeText(
            context,
            "打不开设置页：${it.message}",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}

/** 文本库体量：agent_text_chunks 全表 GROUP BY + 排序，单独跑，不让权限组陪着等。 */
private suspend fun probeTextStore(context: Context): SelfCheckItem {
    val textStore = runCatching {
        val database = EtaDatabase.get(context)
        TextStoreMaintenance.inspect(database)
    }.getOrNull()
    val state = if (textStore == null || textStore.oversizedFields == 0) STATE_OK else STATE_MISSING
    val detail = if (textStore == null) {
        "读不到分块表"
    } else {
        val cap = ChunkedTextDao.MAX_FIELD_CHARS / 10000
        val window = ChunkedTextDao.MAX_RESTORE_CHARS / 10000
        val top = textStore.topFields.firstOrNull()
            ?.let { " · 最大 ${it.field} ${it.charCount / 10000} 万字符" }
            .orEmpty()
        "分块 ${textStore.scannedChars / 10000} 万字符 / ${textStore.scannedFields} 个字段 · " +
            "单字段上限 ${cap} 万 · 单次读入窗口 ${window} 万 · 越线 ${textStore.oversizedFields} 个$top"
    }
    return SelfCheckItem(name = TEXT_STORE_NAME, state = state, detail = detail)
}

/** 实测 Root：su 路径存在只是必要条件，真正判据是 su -c id -u 能不能返回 0。 */
private fun probeRootState(): Pair<String, String> {
    val path = SU_PATHS.firstOrNull { File(it).exists() }
        ?: return STATE_MISSING to "未发现 su：虚拟屏、终端与特权功能会受限"
    return runCatching {
        val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        val finished = process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching STATE_MISSING to "su 存在（$path），但 su -c id -u 4 秒内没返回：可能弹了授权框没点，或已被拒绝"
        }
        val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (text == "0") {
            STATE_OK to "su -c id -u 返回 0（$path），虚拟屏、终端与特权功能可用"
        } else {
            STATE_MISSING to "su 存在（$path）但没拿到 uid 0（输出「${text.ifBlank { "空" }}」），多半是被拒绝授权"
        }
    }.getOrElse { STATE_UNKNOWN to "su 探测失败：${it.message}" }
}

private fun bootSummary(stats: io.github.mangi.eta.core.BootReceipt.Stats): String =
    io.github.mangi.eta.core.BootReceipt.describe(stats) +
        if (stats.receivers > 0) "（${stats.receivers} 个开机接收器响应）" else ""

private suspend fun runSelfCheck(context: Context, probeRoot: Boolean): SelfCheckReport {
    val pkg = context.packageName
    val resolver = context.contentResolver
    val overlayOk = AndroidSettings.canDrawOverlays(context)
    val notifyOk = context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
        PackageManager.PERMISSION_GRANTED
    // 共存包 io.github.mangi.eta.beta 的包名含主包名，字符串 contains 会互相误报，改组件级比对。
    fun grantedIn(raw: String): Boolean = raw.split(':').any {
        android.content.ComponentName.unflattenFromString(it.trim())?.packageName == pkg
    }
    val listeners = AndroidSettings.Secure.getString(
        resolver,
        "enabled_notification_listeners",
    ).orEmpty()
    // 优先问系统官方接口：部分 ROM 不刷新 enabled_notification_listeners，只看字符串会误报"没给权限"。
    val listenerOk = grantedIn(listeners) || runCatching {
        context.getSystemService(android.app.NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(
                android.content.ComponentName(pkg, "$pkg.agent.device.AgentNotificationHistoryService"),
            ) == true
    }.getOrDefault(false)
    val a11y = AndroidSettings.Secure.getString(
        resolver,
        "enabled_accessibility_services",
    ).orEmpty()
    val a11yOk = grantedIn(a11y)
    // Root 只在「重新检测」时实测（会走 su，可能弹系统授权框）；首次进页面只报未实测。
    val rootState: Pair<String, String> = if (probeRoot) probeRootState() else {
        val path = SU_PATHS.firstOrNull { File(it).exists() }
        STATE_UNKNOWN to "未实测 su${if (path != null) "（存在 $path）" else "（没找到 su 文件）"}：点「重新检测」会执行 su -c id -u，可能弹授权框"
    }
    // 自动任务/巡检依赖的四项系统授权。
    val powerManager = context.getSystemService(android.os.PowerManager::class.java)
    val batteryOk = runCatching {
        powerManager?.isIgnoringBatteryOptimizations(pkg) == true
    }.getOrDefault(false)
    // 自启动能不能真跑，看的是三件事：接收器没被禁用、BOOT_COMPLETED 真的收到了、重启后闹钟排回来了。
    // ColorOS 的「自启动」白名单 App 读不到属已知盲区，只能靠后两条实证。
    val bootReceivers = listOf(
        "$pkg.agent.automation.AutoBackupReceiver",
        "$pkg.core.DiagnosticsPatrolReceiver",
        "$pkg.core.DiagnosticsAutoExportReceiver",
    )
    val disabledReceivers = bootReceivers.filter { name ->
        runCatching {
            context.packageManager.getComponentEnabledSetting(
                android.content.ComponentName(pkg, name),
            ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(false)
    }
    val bootStats = runCatching { io.github.mangi.eta.core.BootReceipt.load(context) }.getOrNull()
    val bootVerified = bootStats != null &&
        bootStats.lastAt > 0L &&
        bootStats.lastAt >= bootStats.installAt &&
        bootStats.alarmRescheduled
    val appOps = context.getSystemService(android.app.AppOpsManager::class.java)
    fun opMode(vararg names: String): Int {
        val manager = appOps ?: return android.app.AppOpsManager.MODE_DEFAULT
        for (name in names) {
            val mode = runCatching {
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    manager.unsafeCheckOpNoThrow(name, android.os.Process.myUid(), pkg)
                } else {
                    manager.checkOpNoThrow(name, android.os.Process.myUid(), pkg)
                }
            }.getOrNull() ?: continue
            if (mode != android.app.AppOpsManager.MODE_DEFAULT) return mode
        }
        return android.app.AppOpsManager.MODE_DEFAULT
    }
    // 实测：ColorOS 上这个 op 名是 android:background_start_activity；
    // android:start_activity_from_background 会报 Unknown operation，所以只留实测可用的那个。
    val popupMode = opMode("android:background_start_activity")
    val exactAlarmOk = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        runCatching {
            context.getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true
        }.getOrDefault(false)
    } else {
        true
    }

    val permissions = listOf(
        SelfCheckItem(
            name = "悬浮窗权限",
            state = if (overlayOk) STATE_OK else STATE_MISSING,
            detail = if (overlayOk) {
                "虚拟屏小窗能正常显示"
            } else {
                "缺这个：虚拟屏小窗不会出现。去 应用详情 → 显示在其他应用上层"
            },
            actionUri = if (overlayOk) null else "package:$pkg",
        ),
        SelfCheckItem(
            name = "通知权限",
            state = if (notifyOk) STATE_OK else STATE_MISSING,
            detail = if (notifyOk) "可以发运行状态与完成提醒" else "缺这个：任务完成提醒发不出来",
            actionUri = if (notifyOk) null else "package:$pkg",
        ),
        SelfCheckItem(
            name = "通知使用权",
            state = if (listenerOk) STATE_OK else STATE_MISSING,
            detail = if (listenerOk) {
                "通知历史可用（通知触发任务、近 7 天记录）"
            } else {
                "未开：通知触发类任务与通知历史会缺失"
            },
            actionUri = if (listenerOk) null else "listener:",
        ),
        SelfCheckItem(
            name = "无障碍服务",
            state = if (a11yOk) STATE_OK else STATE_MISSING,
            detail = if (a11yOk) {
                "界面操作（点击/输入/观察）与虚拟屏控制可用"
            } else {
                "未开：所有界面自动化都会失败（不是 Eta 的 bug，是系统没授权）"
            },
            actionUri = if (a11yOk) null else "accessibility:",
        ),
        SelfCheckItem(
            name = "Root 权限",
            state = rootState.first,
            detail = rootState.second,
        ),
        SelfCheckItem(
            name = "电池优化白名单",
            state = if (batteryOk) STATE_OK else STATE_MISSING,
            detail = if (batteryOk) {
                "已不受电池优化限制：后台定时与巡检不会被推迟"
            } else {
                "未加入白名单：锁屏久了自动备份、巡检与自动任务会被推迟"
            },
            actionUri = if (batteryOk) null else "battery:",
        ),
        SelfCheckItem(
            name = "自启动",
            state = when {
                disabledReceivers.isNotEmpty() -> STATE_MISSING
                bootVerified -> STATE_OK
                else -> STATE_UNKNOWN
            },
            detail = when {
                disabledReceivers.isNotEmpty() ->
                    "这几个接收器被禁用：${disabledReceivers.joinToString("、") { it.substringAfterLast('.') }}；重启后自动任务、备份、巡检都不会续排"
                bootVerified -> "启动接收器已实证：" + bootSummary(bootStats)
                bootStats == null ->
                    "接收器已声明，但还没有开机实证：重启一次 Eta 收到 BOOT_COMPLETED、并把闹钟排回来后这里才会变绿"
                else -> "接收器已声明，还没有开机实证：" + bootSummary(bootStats) +
                    "；ColorOS 的「自启动」白名单 App 读不到，需要到应用详情 → 耗电管理里确认允许自启动"
            },
            actionUri = "package:$pkg",
        ),
        SelfCheckItem(
            name = "后台弹出界面",
            state = when (popupMode) {
                android.app.AppOpsManager.MODE_ALLOWED -> STATE_OK
                android.app.AppOpsManager.MODE_IGNORED,
                android.app.AppOpsManager.MODE_ERRORED -> STATE_MISSING
                else -> STATE_PLATFORM
            },
            detail = when {
                popupMode == android.app.AppOpsManager.MODE_ALLOWED ->
                    "允许从后台拉起界面：虚拟屏小窗能自己出来"
                popupMode == android.app.AppOpsManager.MODE_IGNORED ||
                    popupMode == android.app.AppOpsManager.MODE_ERRORED ->
                    "被禁止从后台拉起界面：虚拟屏小窗可能不出现（ColorOS 叫「后台弹出界面」）"
                else -> "系统没给这个权限项的可读状态（ColorOS 私有项）；到应用详情 → 权限管理里确认「后台弹出界面」"
            },
            actionUri = "package:$pkg",
        ),
        SelfCheckItem(
            name = "精确闹钟",
            state = if (exactAlarmOk) STATE_OK else STATE_MISSING,
            detail = if (exactAlarmOk) {
                "可以排精确闹钟：短周期自动备份与巡检能准点到点"
            } else {
                "没有精确闹钟授权：定时会退化成「大约到点」，短周期（10~60 分钟档）明显偏晚"
            },
            actionUri = if (exactAlarmOk) null else "alarm:",
        ),
    )

    val storage = mutableListOf<SelfCheckItem>()
    val shareDir = File("/storage/emulated/0/ai共享文件夹")
    val shareOk = shareDir.isDirectory && shareDir.canWrite()
    storage += SelfCheckItem(
        name = "共享目录",
        state = if (shareOk) STATE_OK else STATE_MISSING,
        detail = if (shareOk) "/storage/emulated/0/ai共享文件夹 可读写" else "共享目录不存在或不可写",
    )

    val database = runCatching { EtaDatabase.get(context) }.getOrNull()
    val dbVersion = runCatching { database?.openHelper?.readableDatabase?.version }.getOrNull()
    val conversations = runCatching { database?.conversationDao()?.conversationCount() }.getOrNull()
    val messages = runCatching { database?.conversationDao()?.messageCount() }.getOrNull()
    val usageRecords = runCatching { database?.tokenUsageDao()?.recordCount() }.getOrNull()
    storage += SelfCheckItem(
        name = "数据库",
        state = if (dbVersion != null) STATE_OK else STATE_MISSING,
        detail = if (dbVersion != null) {
            val expect = io.github.mangi.eta.data.db.ETA_DB_SCHEMA_VERSION
            ("结构版本 v$dbVersion" + if (dbVersion != expect) "（期望 v$expect，需迁移）" else "") +
                " · 会话 ${conversations ?: 0} · 消息 ${messages ?: 0} · 用量 ${usageRecords ?: 0} 条"
        } else {
            "打不开本地数据库"
        },
    )
    val crashCount = runCatching { CrashRecorder.records(context).size }.getOrDefault(0)
    storage += SelfCheckItem(
        name = "崩溃留证",
        state = STATE_OK,
        detail = if (crashCount == 0) "暂无崩溃记录（正常）" else "已留 ${crashCount} 份，设置 → 关于可查看导出",
    )
    // 文本库体量是全表 GROUP BY（这库 129 MB），单独一路加载，这里只挂占位。
    storage += SelfCheckItem(name = TEXT_STORE_NAME, state = STATE_UNKNOWN, detail = "读取中…")
    val settings = runCatching { SettingsDataStore.settings() }.getOrNull()
    val backupDetail = if (settings == null) {
        "读不到设置"
    } else if (!settings.autoBackupEnabled) {
        "未开启（设置 → 备份与恢复 里可开）"
    } else {
        val ts = settings.autoBackupLastAt
        val last = if (ts > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
        } else {
            "尚未执行"
        }
        run {
            val minutes = io.github.mangi.eta.data.model.AutoBackupInterval.effectiveMinutes(
                settings.autoBackupIntervalMinutes,
                settings.autoBackupIntervalHours,
            )
            val period = if (minutes % 60 == 0) "每 ${minutes / 60} 小时" else "每 $minutes 分钟"
            val keep = io.github.mangi.eta.data.model.AutoBackupKeepCount.normalize(
                settings.autoBackupKeepCount,
            )
            // 把这两轮新做的备份能力并进同一条：最近一个全量包多大、数据库到底进没进包。
            val packInfo = runCatching {
                io.github.mangi.eta.data.repository.AutoBackupRunner
                    .fullBackups(settings.autoBackupDirectory)
                    .firstOrNull()
                    ?.let { file ->
                        val mb = String.format(java.util.Locale.US, "%.1f", file.length() / 1048576.0)
                        val hasDb = java.util.zip.ZipFile(file).use { zip ->
                            zip.getEntry("private/databases/eta.db") != null
                        }
                        " · 最新全量包 $mb MB · 数据库${if (hasDb) "已含" else "缺失"}"
                    }
            }.getOrNull().orEmpty()
            "已开启 · $period · 保留 $keep 份 · 上次 $last$packInfo"
        }
    }
    storage += SelfCheckItem(
        name = "定时自动备份",
        state = if (settings?.autoBackupEnabled == true) STATE_OK else STATE_MISSING,
        detail = backupDetail,
    )

    // 诊断包每周自动导出：清单里注册了接收器才可能跑；顺便报出自动目录里已有几份。
    val autoExportReady = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getReceiverInfo(
            android.content.ComponentName(
                context,
                "io.github.mangi.eta.core.DiagnosticsAutoExportReceiver",
            ),
            0,
        )
    }.isSuccess
    val autoCount = runCatching {
        File("/storage/emulated/0/ai共享文件夹/诊断包备份/自动")
            .listFiles()?.count { it.name.endsWith(".zip") } ?: 0
    }.getOrDefault(0)
    storage += SelfCheckItem(
        name = "诊断包自动导出",
        state = if (autoExportReady) STATE_OK else STATE_MISSING,
        detail = if (autoExportReady) {
            "接收器已注册 · 每周一次 · 落到 诊断包备份/自动（只留最近 4 份）· 该目录现有 $autoCount 份"
        } else {
            "接收器未注册，自动导出不会跑"
        },
    )

    return SelfCheckReport(permissions = permissions, storage = storage)
}
