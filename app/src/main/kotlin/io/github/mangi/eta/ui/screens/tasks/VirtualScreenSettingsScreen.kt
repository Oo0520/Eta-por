package io.github.mangi.eta.ui.screens.tasks

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.MainScreenFallbackApproval
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 灵屏（GUI Agent）设置。
 *
 * 只留用户真正需要决定的两件事：要不要用、要不要常驻一个小窗观察。
 * 其余（应用冲突处理、熄屏、空闲回收、帧率）都是内部策略，给用户开关只会增加选择负担，
 * 也容易让人误以为「关了就不安全」。这些改成固定默认：
 * - 应用已在别处运行 → 先询问，由用户决定是否重启；
 * - 熄屏执行 → 关闭（需要时由会话显式申请）；
 * - 回退主屏 → 关闭（保持隔离）；
 * - 空闲回收 5 分钟；出帧 3fps（人接管走直通，不受帧率限制）。
 */
@Composable
internal fun VirtualScreenSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    // 开关的即时反馈：DataStore 回流有延迟，先本地记住点击结果，避免看着像「点不动」。
    var keepScreenOnLocal by remember(settings.virtualScreenKeepScreenOn) {
        mutableStateOf(settings.virtualScreenKeepScreenOn)
    }
    // 最近一次导出结果：路径直接展示出来，并允许一键打开。
    var lastExport by remember { mutableStateOf<String?>(null) }

    MiuixScaffoldPage(title = stringResource(R.string.virtual_screen_title), onBack = onBack) {
        item {
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_enable),
                    summary = stringResource(R.string.virtual_screen_enable_summary),
                    checked = settings.virtualScreenEnabled,
                    onCheckedChange = { value ->
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenEnabled = value) }
                            if (!value) {
                                VirtualScreenSession.revokePermission()
                                MainScreenFallbackApproval.cancelAll()
                            }
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.lingscreen_float_enable),
                    summary = stringResource(R.string.lingscreen_float_summary),
                    checked = settings.virtualScreenFloatWindowEnabled,
                    enabled = settings.virtualScreenEnabled,
                    onCheckedChange = { value ->
                        if (value && !android.provider.Settings.canDrawOverlays(context)) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + context.packageName),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenFloatWindowEnabled = value) }
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.lingscreen_keep_screen_on),
                    summary = stringResource(R.string.lingscreen_keep_screen_on_summary),
                    checked = keepScreenOnLocal,
                    onCheckedChange = { value ->
                        keepScreenOnLocal = value
                        io.github.mangi.eta.agent.display.VscrDiag.line("不息屏 开关=" + value)
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenKeepScreenOn = value) }
                            // 无条件同步：关掉时必须能真的释放，不能因为会话状态判断漏掉。
                            io.github.mangi.eta.agent.display.VirtualScreenWakeLock.sync(
                                context,
                                value && VirtualScreenSession.isActive(),
                            )
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.lingscreen_edge_collapse),
                    summary = stringResource(R.string.lingscreen_edge_collapse_summary),
                    checked = settings.virtualScreenFloatEdgeCollapseEnabled,
                    enabled = settings.virtualScreenFloatWindowEnabled,
                    onCheckedChange = { value ->
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenFloatEdgeCollapseEnabled = value) }
                        }
                    },
                )
            }
        }
        item {
            Text(
                text = "导出目录：/sdcard/ai共享文件夹/灵屏日志/（固定副本 lingscreen-latest.log）",
                modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 4.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        lastExport?.let { path ->
            item {
                Text(
                    text = "最近导出：$path",
                    modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            item {
                EtaTextButton(
                    text = "打开日志文件",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 4.dp),
                    onClick = {
                        runCatching {
                            val file = java.io.File(path)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                context.packageName + ".fileprovider",
                                file,
                            )
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "text/plain")
                                    .addFlags(
                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    )
                            )
                        }
                    },
                )
            }
        }
        item {
            EtaTextButton(
                text = stringResource(R.string.lingscreen_export_log),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 8.dp),
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val message = runCatching {
                            val src = java.io.File(context.getExternalFilesDir(null), "vscr.log")
                            if (!src.exists()) error("找不到日志文件")
                            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                                .format(java.util.Date())
                            // 导出到共享产物目录：文件管理器、Eta 工作区、其他 App 都能直接看到。
                            val dir = java.io.File("/sdcard/ai共享文件夹/灵屏日志")
                            if (!dir.exists()) dir.mkdirs()
                            val dst = java.io.File(dir, "lingscreen-log-$stamp.log")
                            val latest = java.io.File(dir, "lingscreen-latest.log")
                            src.copyTo(dst, overwrite = true)
                            src.copyTo(latest, overwrite = true)
                            lastExport = dst.absolutePath
                            "已导出 ${dst.name}（${src.length() / 1024} KB）"
                        }.getOrElse { "导出失败：" + (it.message ?: it.javaClass.simpleName) }
                        io.github.mangi.eta.agent.display.VscrDiag.line("导出灵屏日志 " + message)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
        }
        item {
            Text(
                text = stringResource(R.string.lingscreen_usage_title),
                modifier = Modifier.padding(
                    start = EtaPreferenceDefaults.SidePadding,
                    end = EtaPreferenceDefaults.SidePadding,
                    top = 14.dp,
                    bottom = 2.dp,
                ),
                style = MiuixTheme.textStyles.body1,
            )
        }
        item {
            Text(
                text = stringResource(R.string.lingscreen_usage_body),
                modifier = Modifier.padding(
                    start = EtaPreferenceDefaults.SidePadding,
                    end = EtaPreferenceDefaults.SidePadding,
                    bottom = 18.dp,
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
