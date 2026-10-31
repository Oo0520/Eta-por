package io.github.mangi.eta.ui.screens.prompts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.tool.AgentToolNaming
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 编辑页里的"改前 → 改后"卡片：只给结论，不把全文铺开。 */
@Composable
internal fun EditDiffCard(before: EffectivePreview, after: EffectivePreview) {
    val diff = PromptEditDiff.of(before, after)
    EtaPreferenceGroup(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(
                    R.string.prompt_edit_diff_summary,
                    diff.systemAfter - diff.systemBefore,
                    diff.systemCharsAfter - diff.systemCharsBefore,
                    diff.toolsAdded.size,
                    diff.toolsRemoved.size,
                ),
                style = MiuixTheme.textStyles.body2,
                color = EtaPreferenceColors.Blue,
            )
            if (!diff.hasContentChanges) {
                Text(
                    text = stringResource(R.string.prompt_edit_diff_same),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            diff.changedSegments.forEach { change ->
                Text(
                    text = stringResource(
                        R.string.prompt_edit_diff_segment,
                        segmentLabel(change.kind),
                        change.beforeChars,
                        change.afterChars,
                    ),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (diff.toolsAdded.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.prompt_edit_diff_tools_added, diff.toolsAdded.size),
                    style = MiuixTheme.textStyles.body2,
                    color = EtaPreferenceColors.Green,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = diff.toolsAdded.take(8).joinToString("、") { AgentToolNaming.face(it).label },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (diff.toolsRemoved.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.prompt_edit_diff_tools_removed, diff.toolsRemoved.size),
                    style = MiuixTheme.textStyles.body2,
                    color = EtaPreferenceColors.Orange,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = diff.toolsRemoved.take(8).joinToString("、") { AgentToolNaming.face(it).label },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
