package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.prompt.PromptCustomization
import io.github.mangi.eta.agent.prompt.PromptCustomizationMode
import io.github.mangi.eta.agent.prompt.PromptCustomizationStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 提示词工坊：自定义身份 / 风格 / 追加指令。
 *
 * 生效方式：
 * - 追加（APPEND）：内置身份不动，自定义内容作为额外 system 消息追加；
 * - 替换（REPLACE）：自定义内容前置，并声明优先级高于内置身份描述。
 *
 * 角色会话不生效（人物设定优先），这一条在 AgentPromptBuilder 里判定。
 */
@Composable
internal fun PromptStudioScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(PromptCustomizationStore.load()) }
    var status by remember { mutableStateOf<String?>(null) }

    val savedText = stringResource(R.string.prompt_studio_saved)
    val emptyText = stringResource(R.string.prompt_studio_empty)

    fun save() {
        val next = draft.edit(
            enabled = draft.enabled,
            mode = draft.mode,
            baseSystemPrompt = draft.baseSystemPrompt,
            personaName = draft.personaName,
            personaStyle = draft.personaStyle,
            appendInstructions = draft.appendInstructions,
        )
        scope.launch {
            val written = PromptCustomizationStore.save(next)
            draft = next
            status = if (written) savedText else emptyText
        }
    }

    val statusLine = status ?: ("v${draft.version}" + if (draft.active) "" else "")

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.prompt_studio_title),
                onBack = onBack,
            ) {
                CouiPreferenceGroup(title = stringResource(R.string.prompt_studio_title)) {
                    item {
                        CouiSwitchPreference(
                            title = stringResource(R.string.prompt_studio_enabled),
                            summary = stringResource(R.string.prompt_studio_enabled_summary),
                            checked = draft.enabled,
                            onCheckedChange = { draft = draft.copy(enabled = it) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.prompt_studio_mode_append),
                            value = if (draft.mode == PromptCustomizationMode.APPEND) "✓" else null,
                            onClick = { draft = draft.copy(mode = PromptCustomizationMode.APPEND) },
                        )
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.prompt_studio_mode_replace),
                            value = if (draft.mode == PromptCustomizationMode.REPLACE) "✓" else null,
                            onClick = { draft = draft.copy(mode = PromptCustomizationMode.REPLACE) },
                        )
                    }
                }
                CouiPreferenceGroup(title = stringResource(R.string.prompt_studio_content)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.prompt_studio_base),
                                value = draft.baseSystemPrompt,
                                singleLine = false,
                                onChange = { draft = draft.copy(baseSystemPrompt = it) },
                            )
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.prompt_studio_persona_name),
                                value = draft.personaName,
                                onChange = { draft = draft.copy(personaName = it) },
                            )
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.prompt_studio_persona_style),
                                value = draft.personaStyle,
                                singleLine = false,
                                onChange = { draft = draft.copy(personaStyle = it) },
                            )
                        }
                    }
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.prompt_studio_append),
                                value = draft.appendInstructions,
                                singleLine = false,
                                onChange = { draft = draft.copy(appendInstructions = it) },
                            )
                        }
                    }
                }
                CouiPreferenceGroup {
                    item {
                        CouiPreference(
                            title = stringResource(R.string.prompt_studio_save),
                            summary = statusLine,
                            onClick = { save() },
                        )
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.prompt_studio_title),
                onBack = onBack,
            ) {
                item {
                    GroupCard {
                        SwitchPreference(
                            title = stringResource(R.string.prompt_studio_enabled),
                            summary = stringResource(R.string.prompt_studio_enabled_summary),
                            checked = draft.enabled,
                            onCheckedChange = { draft = draft.copy(enabled = it) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.prompt_studio_mode_append),
                            summary = if (draft.mode == PromptCustomizationMode.APPEND) "✓" else null,
                            onClick = { draft = draft.copy(mode = PromptCustomizationMode.APPEND) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.prompt_studio_mode_replace),
                            summary = if (draft.mode == PromptCustomizationMode.REPLACE) "✓" else null,
                            onClick = { draft = draft.copy(mode = PromptCustomizationMode.REPLACE) },
                        )
                    }
                }
                item { SmallTitle(stringResource(R.string.prompt_studio_content)) }
                item {
                    GroupCard {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.prompt_studio_base),
                                value = draft.baseSystemPrompt,
                                singleLine = false,
                                onChange = { draft = draft.copy(baseSystemPrompt = it) },
                            )
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.prompt_studio_persona_name),
                                value = draft.personaName,
                                onChange = { draft = draft.copy(personaName = it) },
                            )
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.prompt_studio_persona_style),
                                value = draft.personaStyle,
                                singleLine = false,
                                onChange = { draft = draft.copy(personaStyle = it) },
                            )
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.prompt_studio_append),
                                value = draft.appendInstructions,
                                singleLine = false,
                                onChange = { draft = draft.copy(appendInstructions = it) },
                            )
                        }
                    }
                }
                item {
                    GroupCard {
                        ArrowPreference(
                            title = stringResource(R.string.prompt_studio_save),
                            summary = statusLine,
                            onClick = { save() },
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
