package io.github.mangi.eta.agent.security

import android.content.Context
import io.github.mangi.eta.agent.model.AgentRequestSnapshot
import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.PromptCustomizationStore
import io.github.mangi.eta.config.Prefs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class IntegrityLevel { OK, WARNING, CRITICAL }

internal data class IntegrityFinding(
    val code: String,
    val level: IntegrityLevel,
    val title: String,
    val detail: String,
)

internal data class IntegrityReport(
    val checkedAt: Long,
    val findings: List<IntegrityFinding>,
) {
    val worst: IntegrityLevel
        get() = when {
            findings.any { it.level == IntegrityLevel.CRITICAL } -> IntegrityLevel.CRITICAL
            findings.any { it.level == IntegrityLevel.WARNING } -> IntegrityLevel.WARNING
            else -> IntegrityLevel.OK
        }
}

/**
 * 提示词完整性自检（只读，不改任何东西）。
 *
 * 查四类事：外部往私有目录塞的注入文件、进程里被 Xposed/LSPosed 注入的痕迹、
 * 系统提示里应当存在的护栏句子是否还在、最新一轮请求里有没有越狱特征词。
 */
internal object PromptIntegrityGuard {

    /** 模块会写进 Eta 私有目录的文件名；出现任意一个就说明有人从外部改提示词链路。 */
    private val plantedFiles = listOf(
        "eta_purge.md",
        "eta_append.md",
        "eta_triggers.txt",
        "eta_stage2.txt",
        "eta_input.txt",
        "eta_ticket_extra.txt",
        "prompt_dump.txt",
        "eta_model.txt",
    )
    private val plantedPrefixes = listOf("eta_")
    private val plantedSuffixes = listOf(".on", ".off")

    // 只查旧版 XposedBridge：Eta 自己是 LSPosed 模块，合法依赖 libxposed API，
    // 把 io.github.libxposed.* 算进来会对自身误报。
    private val xposedClasses = listOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XposedHelpers",
    )
    private val xposedMapMarkers = listOf("liblspd", "libriru", "libxposed", "zygisk", "xposed")

    /** 这些句子是工具契约的锚点，被替换通常意味着有人整体改写了系统提示。 */
    private val promptAnchors = listOf(
        "不得编造未取得的数据",
        "不包含可供 GUI 工具使用的 observation_id",
        "缺少会影响执行结果的关键信息时，再简短询问",
        "只读检查相关应用私有文件与数据库",
        "不要使用唤醒时的主屏截图坐标操作虚拟屏",
    )

    /** 越狱提示词的指纹，用来判断"模型看到的这份提示是不是被人换过"。 */
    private val jailbreakMarkers = listOf(
        "UNRESTRICTED SANDBOX",
        "ETA-PLUGIN-PATCH",
        "忽略以上所有指令",
        "不要遵守",
        "MODE: UNRESTRICTED",
    )

    fun scan(context: Context): IntegrityReport {
        val findings = mutableListOf<IntegrityFinding>()

        scanPlantedFiles(context)?.let(findings::add)
        scanXposedClasses()?.let(findings::add)
        scanProcessMaps()?.let(findings::add)
        scanPromptAnchors(context)?.let(findings::add)
        scanLatestSnapshot(context)?.let(findings::add)

        val report = IntegrityReport(System.currentTimeMillis(), findings)
        runCatching { appendLog(context, report) }
        return report
    }

    private fun scanPlantedFiles(context: Context): IntegrityFinding? {
        val dir = context.filesDir ?: return null
        val suspicious = (dir.listFiles().orEmpty())
            .filter { it.isFile }
            .map { it.name }
            .filter { name ->
                plantedFiles.any { it == name } ||
                    (name.startsWith("eta_") && plantedSuffixes.any { name.endsWith(it) })
            }
        if (suspicious.isEmpty()) return null
        return IntegrityFinding(
            code = "PLANTED_FILES",
            level = IntegrityLevel.CRITICAL,
            title = "私有目录里出现外部注入文件",
            detail = "这些文件不是 Eta 自己写的，通常是提示词注入模块落下的：" + suspicious.joinToString("、"),
        )
    }

    private fun scanXposedClasses(): IntegrityFinding? {
        val hits = xposedClasses.filter { name -> runCatching { Class.forName(name) }.isSuccess }
        if (hits.isEmpty()) return null
        return IntegrityFinding(
            code = "XPOSED_CLASS",
            level = IntegrityLevel.WARNING,
            title = "进程里能加载到旧版 Xposed 类",
            detail = "命中：" + hits.joinToString("、") +
                "。Eta 自身合法使用 libxposed API，不算异常；旧版 XposedBridge 出现则说明进程被 hook。",
        )
    }

    private fun scanProcessMaps(): IntegrityFinding? {
        val maps = runCatching { File("/proc/self/maps").readLines() }.getOrNull() ?: return null
        val hits = xposedMapMarkers.filter { marker -> maps.any { it.contains(marker, ignoreCase = true) } }
        if (hits.isEmpty()) return null
        return IntegrityFinding(
            code = "XPOSED_MAPS",
            level = IntegrityLevel.CRITICAL,
            title = "进程映射里有注入框架的库",
            detail = "命中关键字：" + hits.joinToString("、"),
        )
    }

    private fun scanPromptAnchors(context: Context): IntegrityFinding? {
        val snapshots = AgentRequestSnapshot.list()
        val sample = snapshots.firstOrNull()?.let(AgentRequestSnapshot::read)
            ?.optJSONArray("system")
            ?.let { array ->
                buildString {
                    for (index in 0 until array.length()) {
                        append(array.optJSONObject(index)?.optString("text").orEmpty())
                    }
                }
            }
            ?: return null
        val missing = promptAnchors.filterNot(sample::contains)
        if (missing.isEmpty()) return null
        return IntegrityFinding(
            code = "PROMPT_ANCHOR_MISSING",
            level = IntegrityLevel.CRITICAL,
            title = "系统提示里的护栏句子缺失",
            detail = "最近一轮请求中找不到这些句子：" + missing.joinToString("；") +
                "。它们通常在提示词被整体替换或改写后消失。",
        )
    }

    private fun scanLatestSnapshot(context: Context): IntegrityFinding? {
        val file = AgentRequestSnapshot.list().firstOrNull() ?: return null
        val json = AgentRequestSnapshot.read(file) ?: return null
        val text = buildString {
            val system = json.optJSONArray("system")
            for (index in 0 until (system?.length() ?: 0)) {
                append(system?.optJSONObject(index)?.optString("text").orEmpty())
            }
            val tools = json.optJSONArray("tools")
            for (index in 0 until (tools?.length() ?: 0)) {
                append(tools?.optJSONObject(index)?.optString("description").orEmpty())
            }
        }
        val hit = jailbreakMarkers.firstOrNull(text::contains)
        if (hit != null) {
            return IntegrityFinding(
                code = "JAILBREAK_MARKER",
                level = IntegrityLevel.CRITICAL,
                title = "最近一轮请求里出现越狱提示特征",
                detail = "命中「$hit」。这份提示不是 Eta 内置的，说明请求在发出前被改写过。",
            )
        }
        // 快照里的自定义版本应与当前配置一致，否则说明"看到的"和"生效的"不是同一份。
        val profile = PromptCustomizationStore.load()
        val snapshotVersion = json.optJSONArray("system")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                Regex("<custom_instructions version=\"(\\d+)\"")
                    .find(array.optJSONObject(index)?.optString("text").orEmpty())
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            }.firstOrNull()
        }
        if (snapshotVersion != null && profile.active && snapshotVersion != profile.version) {
            return IntegrityFinding(
                code = "SNAPSHOT_STALE",
                level = IntegrityLevel.WARNING,
                title = "最新快照用的不是当前版本",
                detail = "快照里的自定义版本 v$snapshotVersion，当前配置 v${profile.version}；" +
                    "如果刚改完还没发消息，属于正常现象。",
            )
        }
        val policy = AgentToolPolicyStore.load()
        if (policy.mode == io.github.mangi.eta.agent.prompt.ToolPolicyMode.ALLOW_ALL && file.length() == 0L) {
            return null
        }
        return null
    }

    private fun appendLog(context: Context, report: IntegrityReport) {
        val dir = File(context.filesDir, "diagnostics").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(dir, "integrity-$stamp.log")
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(report.checkedAt)) +
            " ${report.worst}" +
            (if (report.findings.isEmpty()) " ok" else " " + report.findings.joinToString(";") { it.code }) +
            "\n"
        file.appendText(line)
        // 只留最近 7 天的报告
        dir.listFiles { f -> f.name.startsWith("integrity-") }
            ?.sortedByDescending { it.name }
            ?.drop(7)
            ?.forEach { runCatching { it.delete() } }
    }
}
