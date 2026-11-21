package io.github.mangi.eta.ui.screens.conversations

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ConversationCategoryState
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.SettingsTextField
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiPreferenceGroup
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 会话分类管理：新建 / 重命名 / 删除。
 *
 * 分类与会话归属存在 DataStore 的一个 JSON 域里（[ConversationCategoryState]），
 * 不建 Room 表，避免和上游 EtaDatabase 的版本迁移撞车。
 * 删除分类只解散归属，不删除会话。
 */
@Composable
internal fun ConversationCategoriesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(ConversationCategoryState.EMPTY) }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        state = runCatching { SettingsDataStore.conversationCategories() }
            .getOrDefault(ConversationCategoryState.EMPTY)
    }

    fun persist(next: ConversationCategoryState) {
        state = next
        scope.launch { runCatching { SettingsDataStore.setConversationCategories(next) } }
    }

    val emptyText = stringResource(R.string.conversation_categories_empty)

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.conversation_categories_title),
                onBack = onBack,
            ) {
                CouiPreferenceGroup(title = stringResource(R.string.conversation_categories_new)) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.conversation_categories_name),
                                value = draft,
                                onChange = { draft = it },
                            )
                        }
                    }
                    item {
                        CouiPreference(
                            title = stringResource(R.string.conversation_categories_add),
                            summary = status,
                            onClick = {
                                if (draft.isBlank()) {
                                    status = emptyText
                                } else {
                                    persist(state.add(draft))
                                    draft = ""
                                    status = null
                                }
                            },
                        )
                    }
                }
                CouiPreferenceGroup(title = stringResource(R.string.conversation_categories_list)) {
                    if (state.categories.isEmpty()) {
                        item { CouiPreference(title = emptyText, onClick = null) }
                    } else {
                        state.categories.forEach { category ->
                            item {
                                CouiPreference(
                                    title = category.name,
                                    value = "${state.countOf(category.id)} 个会话",
                                    onClick = { persist(state.remove(category.id)) },
                                )
                            }
                        }
                    }
                }
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.conversation_categories_title),
                onBack = onBack,
            ) {
                item { SmallTitle(stringResource(R.string.conversation_categories_new)) }
                item {
                    GroupCard {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SettingsTextField(
                                label = stringResource(R.string.conversation_categories_name),
                                value = draft,
                                onChange = { draft = it },
                            )
                        }
                        ArrowPreference(
                            title = stringResource(R.string.conversation_categories_add),
                            summary = status,
                            onClick = {
                                if (draft.isBlank()) {
                                    status = emptyText
                                } else {
                                    persist(state.add(draft))
                                    draft = ""
                                    status = null
                                }
                            },
                        )
                    }
                }
                item { SmallTitle(stringResource(R.string.conversation_categories_list)) }
                item {
                    GroupCard {
                        if (state.categories.isEmpty()) {
                            ArrowPreference(title = emptyText, onClick = null)
                        } else {
                            state.categories.forEach { category ->
                                ArrowPreference(
                                    title = category.name,
                                    summary = "${state.countOf(category.id)} 个会话",
                                    onClick = { persist(state.remove(category.id)) },
                                )
                            }
                        }
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
