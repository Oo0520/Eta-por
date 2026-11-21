package io.github.mangi.eta.ui.screens.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.DiagnosticsPatrol
import io.github.mangi.eta.core.DiagnosticsPatrolAlarm
import io.github.mangi.eta.core.DiagnosticsPatrolInterval
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.DiagnosticsSettings
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/** 巡检周期档位（分钟）：1 小时 / 6 小时 / 12 小时 / 1 天 / 7 天。 */
private val PatrolIntervalOptions = listOf(60, 360, 720, 1440, 10080)

/**
 * 极致自动排查设置页：开关、周期、立即巡检、上次结论。
 *
 * 巡检本身没有任何常驻进程，只排一次闹钟，跑完再排下一次；关掉开关立刻取消。
 */
@Composable
internal fun DiagnosticsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(DiagnosticsSettings()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        settings = runCatching { SettingsDataStore.diagnosticsSettings() }.getOrDefault(DiagnosticsSettings())
    }

    fun persist(next: DiagnosticsSettings) {
        settings = next
        scope.launch {
            runCatching { SettingsDataStore.setDiagnosticsSettings(next) }
            runCatching { DiagnosticsPatrolAlarm.refresh(context) }
        }
    }

    val runningText = stringResource(R.string.diag_patrol_running)
    fun runNow() {
        if (busy) return
        busy = true
        scope.launch {
            status = runningText
            val result = runCatching { DiagnosticsPatrol.runOnce(context, reason = "手动巡检") }
            status = result.fold(
                onSuccess = { it.oneLine() },
                onFailure = { "巡检失败：${it.javaClass.simpleName}" },
            )
            settings = runCatching { SettingsDataStore.diagnosticsSettings() }
                .getOrDefault(settings)
            busy = false
        }
    }

    val lastLine = when {
        status != null -> status!!
        settings.lastAt <= 0L -> stringResource(R.string.diag_patrol_never)
        else -> "上次巡检 · ${settings.lastSummary.ifBlank { settings.lastLevel.ifBlank { "-" } }}"
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.settings_diagnostics_title),
                onBack = onBack,
            ) {
                CouiPreferenceGroup(title = stringResource(R.string.diag_patrol_enable)) {
                    item {
                        CouiSwitchPreference(
                            title = stringResource(R.string.diag_patrol_enable),
                            summary = stringResource(R.string.diag_patrol_enable_summary),
                            checked = settings.enabled,
                            onCheckedChange = { persist(settings.copy(enabled = it)) },
                        )
                    }
                }
                CouiPreferenceGroup(title = stringResource(R.string.diag_patrol_interval_title)) {
                    PatrolIntervalOptions.forEach { minutes ->
                        item {
                            CouiPreference(
                                title = DiagnosticsPatrolInterval.label(minutes),
                                value = if (DiagnosticsPatrolInterval.normalize(settings.intervalMinutes) == minutes) {
                                    stringResource(R.string.diag_patrol_interval_current)
                                } else {
                                    null
                                },
                                onClick = { persist(settings.copy(intervalMinutes = minutes)) },
                            )
                        }
                    }
                }
                CouiPreferenceGroup {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.diag_patrol_run_now),
                            summary = lastLine,
                            onClick = { runNow() },
                        )
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.settings_diagnostics_title),
                onBack = onBack,
            ) {
                item {
                    GroupCard {
                        SwitchPreference(
                            title = stringResource(R.string.diag_patrol_enable),
                            summary = stringResource(R.string.diag_patrol_enable_summary),
                            checked = settings.enabled,
                            onCheckedChange = { persist(settings.copy(enabled = it)) },
                        )
                    }
                }
                item { SmallTitle(stringResource(R.string.diag_patrol_interval_title)) }
                item {
                    GroupCard {
                        PatrolIntervalOptions.forEach { minutes ->
                            ArrowPreference(
                                title = DiagnosticsPatrolInterval.label(minutes),
                                summary = if (DiagnosticsPatrolInterval.normalize(settings.intervalMinutes) == minutes) {
                                    stringResource(R.string.diag_patrol_interval_current)
                                } else {
                                    null
                                },
                                onClick = { persist(settings.copy(intervalMinutes = minutes)) },
                            )
                        }
                    }
                }
                item { SmallTitle(stringResource(R.string.diag_patrol_run_now)) }
                item {
                    GroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.diag_patrol_run_now),
                            summary = lastLine,
                            onClick = { runNow() },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Card { content() }
    }
}
