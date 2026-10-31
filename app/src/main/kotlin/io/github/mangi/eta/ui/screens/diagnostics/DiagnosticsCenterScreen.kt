package io.github.mangi.eta.ui.screens.diagnostics

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import io.github.mangi.eta.core.CrashRecorder
import io.github.mangi.eta.core.DiagnosticsBundle
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置 → 关于 → 诊断与日志：把「导出诊断包」和「崩溃记录」合成一个入口。
 *
 * 两件事性质相关（都是出事之后的现场），原来在关于页各占一行、还各自带箭头指向不存在
 * 的下一级；合并后动作就地执行：生成诊断包、逐份分享崩溃记录、清空留证，都不离开本页。
 */
@Composable
internal fun DiagnosticsCenterScreen(onBack: () -> Unit) {
    // 启动系统分享面板必须用 Activity 上下文（或至少带 NEW_TASK）：
    // applicationContext 直接 startActivity 会抛 AndroidRuntimeException，失败被吞就是「闪一下」。
    val host = LocalContext.current
    val context = host.applicationContext
    val scope = rememberCoroutineScope()
    var crashes by remember { mutableStateOf<List<File>>(emptyList()) }
    var bundleCount by remember { mutableStateOf(0) }
    var autoCount by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf(false) }
    var payload by remember { mutableStateOf<ByteArray?>(null) }
    var sharingName by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }

    // 目录不可写（没给所有文件访问权限等）时退回系统文件选择器。
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val bytes = payload
        payload = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(bytes)
                    } ?: error("诊断包输出流不可用")
                }
            }.isSuccess
            Toast.makeText(
                context,
                if (saved) "诊断包已保存" else "诊断包保存失败",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(round) {
        withContext(Dispatchers.IO) {
            crashes = CrashRecorder.records(context)
            bundleCount = countZips(File(DiagnosticsBundle.DEFAULT_DIR))
            autoCount = countZips(File(DiagnosticsBundle.DEFAULT_DIR, "自动"))
        }
    }

    val exportBundle: () -> Unit = {
        if (!pending) {
            pending = true
            scope.launch {
                val bytes = runCatching {
                    withContext(Dispatchers.IO) { DiagnosticsBundle.collect(context) }
                }.getOrNull()
                pending = false
                if (bytes == null) {
                    Toast.makeText(context, "诊断包生成失败", Toast.LENGTH_SHORT).show()
                } else {
                    val name = DiagnosticsBundle.defaultFileName()
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = File(DiagnosticsBundle.DEFAULT_DIR).apply { mkdirs() }
                            File(dir, name).apply { writeBytes(bytes) }
                        }.getOrNull()
                    }
                    if (saved != null) {
                        lastSaved = "${saved.name} · ${saved.length() / 1024} KB"
                        round += 1
                        Toast.makeText(context, "诊断包已存到：${saved.absolutePath}", Toast.LENGTH_LONG).show()
                    } else {
                        payload = bytes
                        saver.launch(name)
                    }
                }
            }
        }
    }

    val share: (File) -> Unit = { file ->
        if (sharingName == null) {
            sharingName = file.name
            scope.launch {
                val uri = runCatching {
                    withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
                        val target = File(dir, file.name)
                        target.writeBytes(file.readBytes())
                        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
                    }
                }.getOrNull()
                sharingName = null
                if (uri == null) {
                    Toast.makeText(context, "崩溃记录导出失败", Toast.LENGTH_SHORT).show()
                } else {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(send, "分享崩溃记录")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { host.startActivity(chooser) }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "打不开分享面板：" + (error.message ?: error.javaClass.simpleName),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    MiuixScaffoldPage(title = "诊断与日志", onBack = onBack) {
        item(key = "diagnostics-head") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = "出事之后的现场都在这",
                    summary = "诊断包带走整份上下文，崩溃记录一份一份留着；两者都只读采集，不改设置",
                )
            }
        }

        item(key = "diagnostics-bundle") {
            EtaPreferenceGroupTitle("诊断包")
            EtaPreferenceGroup {
                EtaPreference(
                    title = if (pending) "正在收集…" else "生成诊断包",
                    summary = lastSaved?.let { "上次生成：$it" }
                        ?: "状态快照 + 崩溃现场 + 自留日志 + 行为核查 + 最近 logcat",
                    enabled = !pending,
                    endActions = {
                        EtaTextButton(
                            text = if (pending) "收集中" else "生成",
                            enabled = !pending,
                            onClick = exportBundle,
                        )
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaPreference(
                    title = "已存诊断包",
                    summary = "手动 $bundleCount 份 · 自动 $autoCount 份（每周一次，只留最近 4 份）",
                )
            }
        }

        item(key = "diagnostics-crash") {
            EtaPreferenceGroupTitle("崩溃记录（${crashes.size} / 最多 ${CrashRecorder.MAX_KEPT} 份）")
            EtaPreferenceGroup {
                if (crashes.isEmpty()) {
                    EtaPreference(
                        title = "暂无崩溃记录",
                        summary = "崩溃时会自动留证到 files/crash，含调用栈与日志尾部",
                    )
                } else {
                    crashes.forEachIndexed { index, file ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = true)
                        EtaPreference(
                            title = file.name,
                            summary = crashSummary(file),
                            enabled = sharingName == null,
                            endActions = {
                                EtaTextButton(
                                    text = "分享",
                                    enabled = sharingName == null,
                                    onClick = { share(file) },
                                )
                            },
                        )
                    }
                    EtaPreferenceDivider(hasLeading = true)
                    EtaPreference(
                        title = "清空崩溃记录",
                        summary = "只删留证文件，不动诊断包、不影响运行",
                        endActions = {
                            EtaTextButton(
                                text = "清空",
                                onClick = { confirmClear = true },
                            )
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        EtaWindowDialog(
            show = true,
            title = "清空崩溃记录？",
            summary = "会删掉全部 ${crashes.size} 份留证，删了没法恢复。",
            onDismissRequest = { confirmClear = false },
        ) {
            MiuixDialogActions(
                confirmText = "清空",
                destructive = true,
                onCancel = { confirmClear = false },
                onConfirm = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { CrashRecorder.clear(context) }
                        round += 1
                        Toast.makeText(context, "已清空崩溃记录", Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

private fun countZips(dir: File): Int = dir
    .listFiles { file -> file.isFile && file.name.startsWith("Eta-诊断包-") && file.name.endsWith(".zip") }
    ?.size ?: 0

private fun crashSummary(file: File): String {
    val kb = (file.length() + 1023) / 1024
    val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
    return "$time · $kb KB"
}
