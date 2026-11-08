package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import java.text.NumberFormat
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 上下文面板：头部（已用百分比 + `~已用 / 窗口`）+ 分段条 + 三段构成明细。
 *
 * 分段条把「已用」这段长度按构成比例切开，剩下的长度留成轨道色；
 * 三段都是请求侧折算出来的量级，所以明细行统一带 `~` 前缀，避免被当成服务端实测值。
 */
@Composable
internal fun ContextUsagePanelContent(
    usage: AgentContextUsageUi,
    canCompact: Boolean,
    onCompact: () -> Unit,
    onDismiss: () -> Unit,
) {
    val progress = usage.progress
    val legend = contextLegend(usage)
    val window = usage.contextWindow?.takeIf { it > 0 }
    val used = usage.contextTokens?.takeIf { it >= 0 }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.context_panel_label),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = usage.usedPercent?.let { "$it%" } ?: "--",
                style = MiuixTheme.textStyles.subtitle,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            if (used != null && window != null) {
                Text(
                    text = "~${compactTokens(used)} / ${compactTokens(window)}",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (progress == null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (window == null) {
                    stringResource(R.string.context_no_model_limit)
                } else {
                    stringResource(R.string.context_no_previous_usage)
                },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            Spacer(Modifier.height(14.dp))
            CompositionBar(
                usedFraction = progress,
                segments = legend.map { part -> part.color to part.tokens.toFloat() },
                trackColor = MiuixTheme.colorScheme.secondaryContainer,
                fallbackColor = contextPressureColor(progress),
            )
        }
        if (legend.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            legend.forEachIndexed { index, part ->
                if (index > 0) Spacer(Modifier.height(12.dp))
                LegendRow(
                    color = part.color,
                    label = part.label,
                    value = "~${compactTokens(part.tokens)}",
                )
            }
        }
        if (canCompact) {
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(R.string.context_compact_action),
                onClick = {
                    onCompact()
                    onDismiss()
                },
                minWidth = 0.dp,
                minHeight = 34.dp,
                cornerRadius = 17.dp,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

/**
 * Token 用量面板：一次响应的用量明细（缓存命中 / 未缓存输入 / 缓存读取 / 输出）。
 *
 * 数字全部来自供应商返回的用量，不做估算；缓存命中率按「缓存读取 / (未缓存输入 + 缓存读取)」算，
 * 两种口径（prompt_tokens 含缓存、input_tokens 不含缓存）都能得到同一个比例。
 */
@Composable
internal fun TokenUsagePanelContent(usage: TokenUsageUi?) {
    val cacheHitLabel = stringResource(R.string.token_panel_cache_hit)
    val uncachedLabel = stringResource(R.string.token_panel_uncached_input)
    val cacheReadLabel = stringResource(R.string.token_panel_cache_read)
    val outputLabel = stringResource(R.string.token_panel_output)
    val modelDurationLabel = stringResource(R.string.token_panel_model_duration)
    val toolDurationLabel = stringResource(R.string.token_panel_tool_duration)
    val ttftLabel = stringResource(R.string.token_panel_ttft)
    val tpsLabel = stringResource(R.string.token_panel_tps)
    val rows = ArrayList<Pair<String, String>>(8)
    usage?.cacheHitRate?.let { rate ->
        rows += cacheHitLabel to "${(rate * 100f + 0.5f).toInt()}%"
    }
    usage?.uncachedInputTokens?.let { rows += uncachedLabel to "${countTokens(it)} tok" }
    usage?.cacheReadTokens?.let { rows += cacheReadLabel to "${countTokens(it)} tok" }
    usage?.outputTokens?.let { rows += outputLabel to "${countTokens(it)} tok" }
    usage?.requestDurationMs?.let { rows += modelDurationLabel to formatDuration(it) }
    usage?.toolDurationMs?.let { rows += toolDurationLabel to formatDuration(it) }
    usage?.firstTokenAverageMs?.let { rows += ttftLabel to formatDuration(it) }
    usage?.generationTokensPerSecond?.let { rows += tpsLabel to "${roundToInt(it)} tok/s" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = MiuixIcons.Layers,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.token_panel_title),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            usage?.totalBilledTokens?.let { total ->
                Text(
                    text = "${countTokens(total)} tok",
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.16f)),
        )
        if (rows.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.token_panel_empty),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            rows.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = value,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 输入框里的 Token 用量面板容器：与 MCP / 技能 / 内存面板共用同一套弹出动效。 */
@Composable
internal fun TokenUsageSheet(
    show: Boolean,
    usage: TokenUsageUi?,
    onDismiss: () -> Unit,
) {
    CornerScaleDialog(
        show = show,
        onDismiss = onDismiss,
        wrapContent = true,
        alignStart = true,
    ) { _ ->
        TokenUsagePanelContent(usage = usage)
    }
}

/** 上下文面板容器：与 Token 用量面板同层、同宽度、同方向（贴输入框左缘展开）。 */
@Composable
internal fun ContextUsageSheet(
    show: Boolean,
    usage: AgentContextUsageUi,
    canCompact: Boolean,
    onCompact: () -> Unit,
    onDismiss: () -> Unit,
) {
    CornerScaleDialog(
        show = show,
        onDismiss = onDismiss,
        wrapContent = true,
        alignStart = true,
    ) { requestClose ->
        ContextUsagePanelContent(
            usage = usage,
            canCompact = canCompact,
            onCompact = onCompact,
            onDismiss = requestClose,
        )
    }
}

private data class ContextLegendPart(
    val color: Color,
    val label: String,
    val tokens: Int,
)

@Composable
private fun contextLegend(usage: AgentContextUsageUi): List<ContextLegendPart> {
    val parts = ArrayList<ContextLegendPart>(3)
    usage.systemPromptTokens?.let {
        parts += ContextLegendPart(
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.55f),
            label = stringResource(R.string.context_panel_system_prompt),
            tokens = it,
        )
    }
    usage.toolDefinitionTokens?.let {
        parts += ContextLegendPart(
            color = ToolDefinitionColor,
            label = stringResource(R.string.context_panel_tool_definitions),
            tokens = it,
        )
    }
    usage.conversationTokens?.let {
        parts += ContextLegendPart(
            color = ConversationColor,
            label = stringResource(R.string.context_panel_conversation),
            tokens = it,
        )
    }
    return parts
}

/** 与上下文环同一套压力色：接近上限时整条也跟着变黄/变红。 */
@Composable
private fun contextPressureColor(progress: Float): Color = when {
    progress >= 0.95f -> StatusError
    progress >= 0.80f -> StatusWarning
    else -> MiuixTheme.colorScheme.primary
}

/**
 * 分段条：先铺轨道，再按「各构成占比 × 已用比例」画三段。
 *
 * 整体用 Path 裁一次圆角，段与段之间保持直角——两端是圆的，内部边界不会因为各自加圆角而露缝。
 */
@Composable
private fun CompositionBar(
    usedFraction: Float,
    segments: List<Pair<Color, Float>>,
    trackColor: Color,
    fallbackColor: Color,
) {
    val total = segments.sumOf { (_, weight) -> weight.toDouble() }.toFloat()
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        val radius = size.height / 2f
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    cornerRadius = CornerRadius(radius, radius),
                ),
            )
        }
        clipPath(path) {
            drawRect(trackColor, size = size)
            val usedWidth = size.width * usedFraction.coerceIn(0f, 1f)
            if (usedWidth <= 0f) return@clipPath
            if (total <= 0f) {
                drawRect(fallbackColor, size = Size(usedWidth, size.height))
                return@clipPath
            }
            var x = 0f
            segments.forEach { (color, weight) ->
                val width = usedWidth * (weight / total)
                if (width > 0f) {
                    drawRect(color, topLeft = Offset(x, 0f), size = Size(width, size.height))
                    x += width
                }
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 构成条上工具定义与对话消息的固定色：与系统提示词的灰形成三档区分。 */
private val ToolDefinitionColor = Color(0xFF9B7BF0)
private val ConversationColor = Color(0xFF5B8DEF)

/** 头部两侧的紧凑写法：3.6K / 102K / 1M；小于一千按整数。 */
internal fun compactTokens(value: Int): String {
    val absolute = kotlin.math.abs(value.toLong())
    return when {
        absolute < 1_000L -> value.toString()
        absolute < 100_000L -> trimZero(value / 1_000.0) + "K"
        absolute < 1_000_000L -> ((absolute + 500L) / 1_000L).toString() + "K"
        else -> trimZero(value / 1_000_000.0) + "M"
    }
}

private fun trimZero(value: Double): String {
    val text = String.format(Locale.US, "%.1f", value)
    return if (text.endsWith(".0")) text.dropLast(2) else text
}

/** 明细里的完整数字：千分位分组，不缩写。 */
private fun countTokens(value: Int): String =
    NumberFormat.getIntegerInstance().format(value)

/** 时长：不足一秒给毫秒（避免出现「0.0 秒」），一分钟内给一位小数秒，超过按「X分Y秒」。 */
private fun formatDuration(millis: Long): String {
    val safe = millis.coerceAtLeast(0)
    if (safe < 1_000) {
        return "${safe} ms"
    }
    if (safe < 60_000) {
        return String.format(Locale.US, "%.1f 秒", safe / 1000.0)
    }
    val minutes = safe / 60_000
    val seconds = (safe % 60_000) / 1000
    return "${minutes}分${seconds}秒"
}

private fun roundToInt(value: Double): Long = kotlin.math.round(value).toLong()
