package io.github.mangi.eta.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowRightAlt
import androidx.compose.material.icons.rounded.WrapText
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Report
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 绘制一份已解析的 [MarkdownDocument]。
 *
 * 每个块以源码偏移作为组合 key；未变化的块是同一个实例，组合期直接跳过。传入
 * [reveal] 时文本块接入逐字显现：排版仍然一次完成，帧间推进只让绘制失效。
 */
@Composable
internal fun MarkdownContent(
    document: MarkdownDocument,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    reveal: SmoothTextRevealCoordinator? = null,
    /** 朗读高亮的源码偏移区间；与文本块相交的部分加底色，其余不变。 */
    highlight: IntRange? = null,
    /** 任务列表勾选回写（源码偏移）；null 表示只读。 */
    onTaskToggle: ((Int) -> Unit)? = null,
    /** 代码块是否按容器宽度折行（预览页要「自适应」，聊天里保持不折行 + 横向滚动）。 */
    codeWrap: Boolean = false,
) {
    val scope = remember(style, reveal, highlight, onTaskToggle, codeWrap) {
        MarkdownRenderScope(style, reveal, highlight, onTaskToggle, codeWrap)
    }
    MarkdownBlockColumn(blocks = document.blocks, scope = scope, compact = false, modifier = modifier)
}

@Immutable
private class MarkdownRenderScope(
    val style: MarkdownStyle,
    val reveal: SmoothTextRevealCoordinator?,
    val highlight: IntRange? = null,
    val onTaskToggle: ((Int) -> Unit)? = null,
    val codeWrap: Boolean = false,
) {
    fun muted() = MarkdownRenderScope(style.muted(), reveal, highlight, onTaskToggle, codeWrap)
}

/** 嵌套列表的层级只影响 marker 字形，用 CompositionLocal 传递，块组件不必逐层转发。 */
private val LocalListDepth = compositionLocalOf { 0 }

@Composable
private fun MarkdownBlockColumn(
    blocks: List<MarkdownBlock>,
    scope: MarkdownRenderScope,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            key(block.offset) {
                val previous = blocks.getOrNull(index - 1)
                val gap = if (compact) {
                    markdownCompactGap(previous, scope.style.tone)
                } else {
                    markdownBlockGap(previous, block, scope.style.tone)
                }
                if (gap.value > 0f) Spacer(Modifier.height(with(density) { gap.toDp() }))
                MarkdownBlockView(block, scope)
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MarkdownBlock, scope: MarkdownRenderScope) {
    when (block) {
        is MarkdownParagraph -> MarkdownText(block, scope.style.body, scope)
        is MarkdownHeading -> MarkdownText(
            block = block,
            style = scope.style.heading(block.level),
            scope = scope,
            modifier = Modifier.semantics { heading() },
        )
        is MarkdownCode -> MarkdownCodeBlock(block, scope)
        is MarkdownQuote -> MarkdownQuoteBlock(block, scope)
        is MarkdownAlert -> MarkdownAlertBlock(block, scope)
        is MarkdownList -> MarkdownListBlock(block, scope)
        is MarkdownTable -> MarkdownTableBlock(block, scope)
        is MarkdownTableCell -> MarkdownText(block, scope.style.table, scope)
        is MarkdownRule -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .height(0.5.dp)
                .background(scope.style.dividerColor),
        )
    }
}

/**
 * 把朗读高亮的源码区间映射到块内文本：块起始偏移已知，行内标记会让块内偏移略有偏差，
 * 这里只做收敛裁剪，算不出有效区间就不高亮，不猜位置。
 */
private fun MarkdownTextBlock.speechHighlighted(highlight: IntRange?, color: Color): AnnotatedString {
    if (highlight == null || text.isEmpty()) return text
    val start = (highlight.first - offset).coerceIn(0, text.length)
    val end = (highlight.last + 1 - offset).coerceIn(start, text.length)
    if (start >= end) return text
    return AnnotatedString.Builder(text)
        .apply { addStyle(SpanStyle(background = color), start, end) }
        .toAnnotatedString()
}

@Composable
private fun MarkdownText(
    block: MarkdownTextBlock,
    style: TextStyle,
    scope: MarkdownRenderScope,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
) {
    val reveal = scope.reveal
    val highlighted = scope.highlight?.let { block.speechHighlighted(it, MiuixTheme.colorScheme.primary.copy(alpha = 0.18f)) }
    val text = highlighted ?: block.text
    // 行内代码：从 span 里筛出等宽字体区间，交给 drawBehind 画圆角块。
    // 文字仍是同一个 Text，所以选中、复制、搜索命中都不受影响。
    val codeRanges = remember(text) {
        text.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
            .mapNotNull { span ->
                // 收紧到非空白字符：解析层在片段首尾留了空格，直接框进去会多出一截，
                // 跨行时表现就是上一行末尾有残留。
                var start = span.start
                var end = span.end - 1
                while (start <= end && text.text[start].isWhitespace()) start++
                while (end >= start && text.text[end].isWhitespace()) end--
                if (start > end) null else start..end
            }
    }
    // 字色偏安静：用 onSurfaceVariantSummary，比正文淡一点，但仍然清晰。
    val quietColor = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val linkColor = MiuixTheme.colorScheme.primary
    // 可点击的本地路径：只认行内代码里以 / 开头、且文件真实存在的写法。
    val pathRanges = remember(text, codeRanges) {
        codeRanges.mapNotNull { range ->
            val raw = text.text.substring(range.first, range.last + 1)
            MarkdownPathLinks.resolve(raw)?.let { range to it }
        }
    }
    val context = LocalContext.current
    val renderText = remember(text, codeRanges, quietColor, linkColor, pathRanges) {
        if (codeRanges.isEmpty()) text else AnnotatedString.Builder(text).apply {
            codeRanges.forEach { addStyle(SpanStyle(color = quietColor), it.first, it.last + 1) }
            // 能打开的文件路径：主色 + 下划线，和普通行内代码区分开。
            pathRanges.forEach { (range, _) ->
                addStyle(
                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    range.first,
                    range.last + 1,
                )
            }
        }.toAnnotatedString()
    }
    var textLayout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val pathTapModifier = if (pathRanges.isEmpty()) {
        Modifier
    } else {
        Modifier.pointerInput(pathRanges) {
            detectTapGestures { position ->
                val layout = textLayout ?: return@detectTapGestures
                val offset = layout.getOffsetForPosition(position)
                pathRanges.firstOrNull { (range, _) -> offset in range }?.let { (_, path) ->
                    MarkdownPathLinks.open(context, path)
                }
            }
        }
    }
    // 用 onSurface 的一档半透明，比 codeBackground 更实，浅色主题下才看得出边界。
    val pillColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val pillModifier = modifier.drawBehind {
        val layout = textLayout ?: return@drawBehind
        if (codeRanges.isEmpty()) return@drawBehind
        val pad = 3.dp.toPx()
        val radius = 5.dp.toPx()
        val length = layout.layoutInput.text.length
        // 字号 → 像素：药丸高度按字号固定，不随字符形状变化（避免 g/p 拉低、d/l 拉高）。
        val fontSizePx = with(this) { layout.layoutInput.style.fontSize.toPx() }
        val padH = 3.dp.toPx()
        val padV = 1.5.dp.toPx()
        codeRanges.forEach { range ->
            val last = (length - 1).coerceAtLeast(0)
            val firstChar = range.first.coerceIn(0, last)
            val lastChar = range.last.coerceIn(firstChar, last)
            val firstLine = layout.getLineForOffset(firstChar)
            val lastLine = layout.getLineForOffset(lastChar)
            for (line in firstLine..lastLine) {
                val lineStart = layout.getLineStart(line)
                val lineEnd = layout.getLineEnd(line, visibleEnd = true)
                val from = maxOf(if (line == firstLine) firstChar else lineStart, lineStart)
                val to = minOf(if (line == lastLine) lastChar else (lineEnd - 1), lineEnd - 1)
                if (to < from) continue
                val left = layout.getHorizontalPosition(from, true)
                val right = layout.getBoundingBox(to).right
                // 基线为锚：所有药丸等高、与文字垂直居中，上下留白一致。
                val baseline = layout.getLineBaseline(line)
                val top = baseline - fontSizePx * 0.86f - padV
                val bottom = baseline + fontSizePx * 0.29f + padV
                drawRoundRect(
                    color = pillColor,
                    topLeft = androidx.compose.ui.geometry.Offset(left - padH, top),
                    size = androidx.compose.ui.geometry.Size((right - left) + padH * 2, bottom - top),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                )
            }
        }
    }
    // 空单元格没有可显现的文字，也不占用显现协调器的 key。
    if (reveal == null || text.isEmpty()) {
        Text(
            text = renderText,
            style = style,
            softWrap = softWrap,
            modifier = pillModifier.then(pathTapModifier),
            onTextLayout = { layout -> textLayout = layout },
        )
        return
    }
    val revealState = rememberSmoothTextRevealState(block.revealKey, reveal)
    // 显现裁剪按字素边界逐帧变化，关闭像素对齐可避免裁剪边缘抖动。
    val animatedStyle = remember(style) { style.copy(textMotion = TextMotion.Animated) }
    Text(
        text = renderText,
        style = animatedStyle,
        softWrap = softWrap,
        modifier = pillModifier.smoothTextReveal(revealState).then(pathTapModifier),
        onTextLayout = { layout ->
            textLayout = layout
            revealState.onTextLayout(renderText.text, layout)
        },
    )
}

// ── 代码块 ──────────────────────────────────────────────────────────────

@Composable
private fun MarkdownCodeBlock(block: MarkdownCode, scope: MarkdownRenderScope) {
    val style = scope.style
    // 块级公式走 KaTeX 离线渲染（assets 内置，不联网）。
    if (block.isMath) {
        MathBlockView(tex = block.text.text, modifier = Modifier.padding(vertical = 4.dp))
        return
    }
    // 带文件路径的块（```markdown file=/abs/path）走文件卡片：可预览、可编辑、保存覆盖原文件。
    val fileSpec = remember(block.language, block.text.text) {
        markdownFileSpec(block.language, block.text.text)
    }
    if (fileSpec != null) {
        MarkdownFileCard(path = fileSpec.path, content = fileSpec.content)
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = style.codeBackground, cornerRadius = 12.dp),
    ) {
        // 折行开关：默认沿用调用方设置（预览页折行、聊天不折行），可逐块切换。
        var wrap by remember(block.text.text) { mutableStateOf(scope.codeWrap) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language?.substringBefore(' ') ?: stringResource(R.string.markdown_code_block),
                style = MiuixTheme.textStyles.footnote2,
                color = style.secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { wrap = !wrap },
                minWidth = 30.dp,
                minHeight = 30.dp,
            ) {
                Icon(
                    imageVector = if (wrap) Icons.Rounded.WrapText else Icons.AutoMirrored.Rounded.ArrowRightAlt,
                    contentDescription = stringResource(
                        if (wrap) R.string.markdown_code_nowrap else R.string.markdown_code_wrap,
                    ),
                    modifier = Modifier.size(16.dp),
                    tint = if (wrap) MiuixTheme.colorScheme.primary else style.secondaryColor,
                )
            }
            CopyButton(text = block.text.text, tint = style.secondaryColor)
        }
        // 默认不折行 + 横向滚动（保留缩进对齐）；开关打开后按容器宽度折行铺满。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
        ) {
            MarkdownText(block, style.code, scope, softWrap = wrap)
        }
    }
}

@Composable
private fun CopyButton(text: String, tint: Color) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    IconButton(
        onClick = {
            @Suppress("DEPRECATION")
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
        minWidth = 32.dp,
        minHeight = 32.dp,
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = stringResource(if (copied) R.string.copy_copied else R.string.copy_code),
            modifier = Modifier.size(14.dp),
            tint = if (copied) MiuixTheme.colorScheme.primary else tint,
        )
    }
}

// ── 引用与提示 ──────────────────────────────────────────────────────────

@Composable
private fun MarkdownQuoteBlock(block: MarkdownQuote, scope: MarkdownRenderScope) {
    val barColor = scope.style.dividerColor
    val mutedScope = remember(scope) { scope.muted() }
    MarkdownBlockColumn(
        blocks = block.blocks,
        scope = mutedScope,
        compact = false,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val width = 3.dp.toPx()
                drawLine(
                    color = barColor,
                    start = Offset(width / 2, width / 2),
                    end = Offset(width / 2, size.height - width / 2),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
            .padding(start = 14.dp),
    )
}

@Composable
private fun MarkdownAlertBlock(block: MarkdownAlert, scope: MarkdownRenderScope) {
    val accent = block.kind.accentColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = accent.copy(alpha = 0.08f), cornerRadius = 12.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = block.kind.icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accent,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(block.kind.label),
                style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        }
        if (block.blocks.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            MarkdownBlockColumn(blocks = block.blocks, scope = scope, compact = false)
        }
    }
}

private val MarkdownAlertKind.icon: ImageVector
    get() = when (this) {
        MarkdownAlertKind.Note -> Icons.Rounded.Info
        MarkdownAlertKind.Tip -> Icons.Rounded.Lightbulb
        MarkdownAlertKind.Important -> Icons.Rounded.Feedback
        MarkdownAlertKind.Warning -> Icons.Rounded.Warning
        MarkdownAlertKind.Caution -> Icons.Rounded.Report
    }

private val MarkdownAlertKind.label: Int
    get() = when (this) {
        MarkdownAlertKind.Note -> R.string.markdown_alert_note
        MarkdownAlertKind.Tip -> R.string.markdown_alert_tip
        MarkdownAlertKind.Important -> R.string.markdown_alert_important
        MarkdownAlertKind.Warning -> R.string.markdown_alert_warning
        MarkdownAlertKind.Caution -> R.string.markdown_alert_caution
    }

@Composable
private fun MarkdownAlertKind.accentColor(): Color = when (this) {
    MarkdownAlertKind.Note -> MiuixTheme.colorScheme.primary
    MarkdownAlertKind.Tip -> StatusSuccess
    MarkdownAlertKind.Important -> AlertImportantColor
    MarkdownAlertKind.Warning -> StatusWarning
    MarkdownAlertKind.Caution -> MiuixTheme.colorScheme.error
}

private val AlertImportantColor = Color(0xFF8B5CF6)

// ── 列表 ────────────────────────────────────────────────────────────────

@Composable
private fun MarkdownListBlock(block: MarkdownList, scope: MarkdownRenderScope) {
    val depth = LocalListDepth.current
    val answer = scope.style.tone == MarkdownTone.Answer
    val itemGap = when {
        block.loose -> if (answer) 10.dp else 8.dp
        else -> if (answer) 4.dp else 2.dp
    }
    // 序号按本列表最长的数字预留宽度，各项正文左边缘对齐。
    val markerWidth = if (block.ordered) {
        val digits = (block.items.lastOrNull()?.number ?: 1).toString().length
        (digits * 9 + 8).dp
    } else {
        14.dp
    }
    Column(Modifier.fillMaxWidth()) {
        block.items.forEachIndexed { index, item ->
            key(item.offset) {
                if (index > 0) Spacer(Modifier.height(itemGap))
                // 任务项：整行垂直居中（勾选框与文字中线对齐）；普通项仍走基线对齐。
                val task = item.checked != null
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = if (task) Alignment.CenterVertically else Alignment.Top,
                ) {
                    ListMarker(item = item, ordered = block.ordered, depth = depth, width = markerWidth, scope = scope)
                    CompositionLocalProvider(LocalListDepth provides depth + 1) {
                        MarkdownBlockColumn(
                            blocks = item.blocks,
                            scope = scope,
                            compact = !block.loose,
                            modifier = if (task) {
                                Modifier.weight(1f)
                            } else {
                                Modifier
                                    .weight(1f)
                                    .alignByBaseline()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ListMarker(
    item: MarkdownListItem,
    ordered: Boolean,
    depth: Int,
    width: Dp,
    scope: MarkdownRenderScope,
) {
    val style = scope.style
    val reveal = scope.reveal
    // 在 graphicsLayer 内读取显现进度：块开始显现只让 marker 图层更新，不触发重组。
    val visibility = Modifier.graphicsLayer {
        alpha = if (listMarkerVisible(reveal != null, item.markerRevealKey, reveal?.started?.value)) 1f else 0f
    }
    val markerModifier = visibility
        .padding(end = 8.dp)
        .widthIn(min = width)
    when {
        item.checked != null -> {
            val toggle = scope.onTaskToggle
            Box(
                markerModifier
                    .height(26.dp)
                    .then(if (toggle != null) Modifier.clickable { toggle(item.offset) } else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (item.checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (item.checked) MiuixTheme.colorScheme.primary else style.markerColor,
                )
            }
        }
        ordered -> Text(
            text = "${item.number}.",
            style = style.body,
            color = style.markerColor,
            textAlign = TextAlign.End,
            modifier = markerModifier.alignByBaseline(),
        )
        else -> Text(
            text = BULLETS[depth % BULLETS.size],
            style = style.body,
            color = style.markerColor,
            textAlign = TextAlign.Center,
            modifier = markerModifier.alignByBaseline(),
        )
    }
}

/**
 * 流式正文里 marker 与该项第一段文字同时出现；没有文字的空项在显现期间保持隐藏，
 * 避免先冒出孤立的圆点。
 */
internal fun listMarkerVisible(
    revealActive: Boolean,
    markerKey: RevealBlockKey?,
    started: Set<RevealBlockKey>?,
): Boolean = !revealActive || (markerKey != null && started?.contains(markerKey) == true)

private val BULLETS = listOf("•", "◦", "▪")

// ── 表格 ────────────────────────────────────────────────────────────────

/**
 * 列宽按内容自适应：每列取最宽单元格的自然宽度并限制在合理区间；总宽不足时按比例
 * 撑满可用宽度，超出时整体横向滚动。网格线与表头底色在绘制阶段按测量结果画出。
 */
@Composable
private fun MarkdownTableBlock(block: MarkdownTable, scope: MarkdownRenderScope) {
    val style = scope.style
    val bodyStyles = remember(style, block.alignments) {
        block.alignments.map { align -> style.table.copy(textAlign = align.textAlign) }
    }
    val headerStyles = remember(bodyStyles) {
        bodyStyles.map { it.copy(fontWeight = FontWeight.SemiBold) }
    }
    var grid by remember { mutableStateOf(TableGrid.Empty) }
    val lineColor = style.dividerColor
    val headerColor = style.tableHeaderBackground
    val columns = block.header.size
    val rows = remember(block) { listOf(block.header) + block.rows }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = constraints.maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
        ) {
            Layout(
                modifier = Modifier
                    .squircleBorder(width = 0.5.dp, color = lineColor, cornerRadius = 12.dp)
                    .squircleClip(cornerRadius = 12.dp)
                    .drawBehind {
                        val current = grid
                        val headerHeight = current.rowHeights.firstOrNull() ?: return@drawBehind
                        drawRect(headerColor, size = Size(size.width, headerHeight.toFloat()))
                        val stroke = 0.5.dp.toPx()
                        var y = 0f
                        current.rowHeights.dropLast(1).forEach { height ->
                            y += height
                            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), stroke)
                        }
                        var x = 0f
                        current.columnWidths.dropLast(1).forEach { width ->
                            x += width
                            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), stroke)
                        }
                    },
                content = {
                    rows.forEachIndexed { rowIndex, row ->
                        row.forEachIndexed { column, cell ->
                            MarkdownText(
                                block = cell,
                                style = if (rowIndex == 0) headerStyles[column] else bodyStyles[column],
                                scope = scope,
                                // 单元格以固定宽度测量，不需要 fill；fill 会干扰列宽的固有尺寸查询。
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                },
            ) { measurables, _ ->
                val rowCount = measurables.size / columns
                val minWidth = TableMinColumnWidth.roundToPx()
                val maxWidth = TableMaxColumnWidth.roundToPx()
                val widths = IntArray(columns) { column ->
                    (0 until rowCount).maxOf { row ->
                        measurables[row * columns + column].maxIntrinsicWidth(Constraints.Infinity)
                    }.coerceIn(minWidth, maxWidth)
                }
                val natural = widths.sum()
                if (natural in 1 until availableWidth) {
                    val extra = availableWidth - natural
                    var assigned = 0
                    for (column in 0 until columns) {
                        val share = if (column == columns - 1) {
                            extra - assigned
                        } else {
                            (extra.toLong() * widths[column] / natural).toInt()
                        }
                        widths[column] += share
                        assigned += share
                    }
                }
                val placeables = measurables.mapIndexed { index, measurable ->
                    measurable.measure(Constraints.fixedWidth(widths[index % columns]))
                }
                val heights = IntArray(rowCount) { row ->
                    (0 until columns).maxOf { column -> placeables[row * columns + column].height }
                }
                val next = TableGrid(widths.toList(), heights.toList())
                if (next != grid) grid = next
                layout(widths.sum(), heights.sum()) {
                    var y = 0
                    for (row in 0 until rowCount) {
                        var x = 0
                        for (column in 0 until columns) {
                            placeables[row * columns + column].place(x, y)
                            x += widths[column]
                        }
                        y += heights[row]
                    }
                }
            }
        }
    }
}

/** 测量结果供绘制阶段画网格；只在绘制中读取，写入不会引起重组。 */
@Immutable
private data class TableGrid(val columnWidths: List<Int>, val rowHeights: List<Int>) {
    companion object {
        val Empty = TableGrid(emptyList(), emptyList())
    }
}

private val MarkdownColumnAlign.textAlign: TextAlign
    get() = when (this) {
        MarkdownColumnAlign.Start -> TextAlign.Start
        MarkdownColumnAlign.Center -> TextAlign.Center
        MarkdownColumnAlign.End -> TextAlign.End
    }

private val TableMinColumnWidth = 72.dp
private val TableMaxColumnWidth = 260.dp
