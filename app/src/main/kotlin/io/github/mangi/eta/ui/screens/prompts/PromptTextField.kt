package io.github.mangi.eta.ui.screens.prompts

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 带粘贴/清空/字数统计的文本框。
 *
 * 长文本经输入法粘贴到 miuix TextField 时会静默失败（实测粘贴后取值为空），
 * 所以额外给一个直接读系统剪贴板的入口，绕开 IME。**不设上限**：写多少存多少，
 * 只在超过 6000 字时把字数提示变成橙色。
 */
@Composable
internal fun PromptTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = false,
) {
    val context = LocalContext.current
    // 长文本默认折叠：编辑器里摊开两千字会把页面拉成一条，滚都滚不动。
    val collapsible = !singleLine && value.length > COLLAPSE_CHARS
    var expanded by remember { mutableStateOf(!collapsible) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (collapsible && !expanded) {
            EtaPreferenceGroup(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body2,
                    )
                    Text(
                        text = value.replace('\n', ' ').take(COLLAPSED_PREVIEW_CHARS) + "…",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = stringResource(R.string.prompt_field_collapsed, value.length),
                        style = MiuixTheme.textStyles.footnote2,
                        color = EtaPreferenceColors.Orange,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            TextField(
                value = value,
                onValueChange = onValueChange,
                label = label,
                modifier = Modifier.fillMaxWidth().heightIn(max = MAX_EDITOR_HEIGHT),
                singleLine = singleLine,
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                text = stringResource(
                    R.string.prompt_field_stats,
                    value.length,
                    estimateTokens(value),
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = if (value.length > PROMPT_WARN_CHARS) {
                    io.github.mangi.eta.ui.components.EtaPreferenceColors.Orange
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.prompt_field_paste),
                style = MiuixTheme.textStyles.footnote2,
                color = io.github.mangi.eta.ui.components.EtaPreferenceColors.Blue,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .clickableText {
                        val pasted = readClipboard(context)
                        if (pasted.isEmpty()) {
                            toast(context, context.getString(R.string.prompt_field_paste_empty))
                        } else {
                            onValueChange(pasted)
                            toast(context, context.getString(R.string.prompt_field_pasted, pasted.length))
                        }
                    },
            )
            if (!singleLine && value.length > COLLAPSE_CHARS) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.prompt_field_collapse else R.string.prompt_field_expand,
                    ),
                    style = MiuixTheme.textStyles.footnote2,
                    color = EtaPreferenceColors.Blue,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .clickableText { expanded = !expanded },
                )
            }
            Text(
                text = stringResource(R.string.prompt_field_clear),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.clickableText { onValueChange("") },
            )
        }
    }
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

private fun readClipboard(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = manager.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}

/** 粗估 token：汉字按 1 个算，其余按 4 字符 1 个算。只用于提示量级。 */
internal fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    for (char in text) {
        if (char.code > 0x2E80) cjk++
    }
    val rest = text.length - cjk
    return cjk + (rest + 3) / 4
}

internal const val PROMPT_WARN_CHARS = 6000

private const val COLLAPSE_CHARS = 600
private const val COLLAPSED_PREVIEW_CHARS = 160
private val MAX_EDITOR_HEIGHT = 320.dp
