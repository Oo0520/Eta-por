package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.Settings
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 面板内提示条：占一行、把内容往下推，不浮在界面上。
 *
 * 为什么不浮：侧栏与工作区都是整屏面板，浮层会挡住正要看的东西；
 * 而且浮层要算绝对坐标，面板本身是被位移出来的，坐标很容易错。
 *
 * 关闭即记 seen（写 `seenTourPacks`），之后永久不再出现。
 */
@Composable
internal fun PaneHintBar(
    id: String,
    text: String,
    modifier: Modifier = Modifier,
) {
    val settingsState = produceState<Settings?>(initialValue = null) {
        SettingsDataStore.settingsFlow().collect { value = it }
    }
    // 设置还没读出来时按「已看过」处理，避免已关过的提示闪一下。
    val seen = settingsState.value?.seenTourPacks?.containsKey(id) ?: true
    if (seen) return

    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch {
            SettingsDataStore.updateSettings { current ->
                current.copy(seenTourPacks = current.seenTourPacks + (id to 1))
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Lightbulb,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = dismiss) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "知道了",
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
