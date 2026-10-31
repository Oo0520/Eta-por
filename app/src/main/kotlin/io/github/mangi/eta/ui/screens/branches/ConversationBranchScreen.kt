package io.github.mangi.eta.ui.screens.branches

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.formatMessageTimestamp
import io.github.mangi.eta.ui.model.BranchNodeUi
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val NodeWidth = 176.dp
private val NodeHeight = 68.dp
private val ColumnGap = 64.dp
private val RowGap = 16.dp

/** 画布上的一个节点：深度决定横坐标，先序序号决定纵坐标。 */
private data class PlacedNode(
    val node: BranchNodeUi,
    val depth: Int,
    val row: Int,
)

/**
 * 分支图：把一棵会话树画出来。
 *
 * - 纵向按 [BranchNodeUi.createdAt] 的顺序排（先序 DFS），分支会话因此落在父会话下方。
 * - 横向按深度分列，父→子用贝塞尔曲线连，分叉点画一个小圆点。
 * - 双指缩放、单指平移、双击回到「适配屏幕」。
 */
@Composable
internal fun ConversationBranchScreen(
    nodes: List<BranchNodeUi>,
    currentConversationId: String?,
    loading: Boolean,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        // 顶栏由 AppShell 提供（路由标为紧凑标题），这里只放画布与空态提示。
        val branchCount = nodes.count { it.isBranch }
        if (nodes.isEmpty() && !loading) {
            EmptyBranchHint()
        } else {
            if (branchCount > 0) {
                Text(
                    text = "$branchCount 分支 · ${nodes.size} 会话 · " + stringResource(R.string.branch_graph_hint),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            BranchCanvas(
                nodes = nodes,
                currentConversationId = currentConversationId,
                onOpenConversation = onOpenConversation,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun EmptyBranchHint() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.branch_graph_empty),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }
}

@Composable
private fun BranchCanvas(
    nodes: List<BranchNodeUi>,
    currentConversationId: String?,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val placed = remember(nodes) { placeNodes(nodes) }
    val nodeWidthPx = with(density) { NodeWidth.toPx() }
    val nodeHeightPx = with(density) { NodeHeight.toPx() }
    val columnGapPx = with(density) { ColumnGap.toPx() }
    val rowGapPx = with(density) { RowGap.toPx() }

    val contentWidthPx = (placed.maxOfOrNull { it.depth }?.plus(1) ?: 1) * (nodeWidthPx + columnGapPx)
    val contentHeightPx = (placed.size) * (nodeHeightPx + rowGapPx)
    val contentWidth = with(density) { contentWidthPx.toDp() }
    val contentHeight = with(density) { contentHeightPx.toDp() }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var fitted by remember { mutableStateOf(false) }

    fun fit() {
        if (viewport.width <= 0 || contentWidthPx <= 0f) return
        val target = min(1f, (viewport.width - 48f) / contentWidthPx).coerceIn(0.4f, 1f)
        scale = target
        offsetX = 24f
        offsetY = 16f
    }

    LaunchedEffect(viewport, contentWidthPx, contentHeightPx) {
        if (!fitted && viewport.width > 0) {
            fit()
            fitted = true
        }
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewport = it }
            .clip(RoundedCornerShape(0.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(0.4f, 2f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { fit() })
            },
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                }
                .size(contentWidth, contentHeight),
        ) {
            val lineColor = MiuixTheme.colorScheme.outline
            val dotColor = MiuixTheme.colorScheme.primary
            val byId = remember(placed) { placed.associateBy { it.node.id } }
            Canvas(modifier = Modifier.fillMaxSize()) {
                placed.forEach { child ->
                    val parentId = child.node.parentConversationId ?: return@forEach
                    val parent = byId[parentId] ?: return@forEach
                    val startX = parent.depth * (nodeWidthPx + columnGapPx) + nodeWidthPx
                    val startY = parent.row * (nodeHeightPx + rowGapPx) + nodeHeightPx / 2f
                    val endX = child.depth * (nodeWidthPx + columnGapPx)
                    val endY = child.row * (nodeHeightPx + rowGapPx) + nodeHeightPx / 2f
                    val handle = max(24f, (endX - startX) / 2f)
                    val path = Path().apply {
                        moveTo(startX, startY)
                        cubicTo(startX + handle, startY, endX - handle, endY, endX, endY)
                    }
                    drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(color = dotColor, radius = 3.dp.toPx(), center = Offset(endX, endY))
                }
            }
            placed.forEach { placedNode ->
                val nodeModifier = Modifier
                    .offset {
                        IntOffset(
                            x = (placedNode.depth * (nodeWidthPx + columnGapPx)).roundToInt(),
                            y = (placedNode.row * (nodeHeightPx + rowGapPx)).roundToInt(),
                        )
                    }
                    .size(NodeWidth, NodeHeight)
                BranchNodeCard(
                    node = placedNode.node,
                    isCurrent = placedNode.node.id == currentConversationId,
                    onClick = { onOpenConversation(placedNode.node.id) },
                    modifier = nodeModifier,
                )
            }
        }
    }
}

@Composable
private fun BranchNodeCard(
    node: BranchNodeUi,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (isCurrent) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .border(if (isCurrent) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = node.title,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                formatMessageTimestamp(node.createdAt)?.let { append(it) }
                if (node.messageCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${node.messageCount} 条")
                }
            },
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (node.isBranch) {
            Text(
                text = stringResource(R.string.branch_forked_from) +
                    (node.forkPreview.takeIf(String::isNotBlank)?.let { "：$it" } ?: ""),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 先序布局：每棵树从根开始 DFS，行号等于访问顺序，列号等于深度。
 * 父子关系来自 `parent_conversation_id`；父不在集合里的节点当根处理，避免整棵树消失。
 */
private fun placeNodes(nodes: List<BranchNodeUi>): List<PlacedNode> {
    if (nodes.isEmpty()) return emptyList()
    val ids = nodes.mapTo(HashSet()) { it.id }
    val children = nodes
        .filter { it.parentConversationId != null && it.parentConversationId in ids }
        .groupBy { it.parentConversationId.orEmpty() }
        .mapValues { (_, value) -> value.sortedBy { it.createdAt } }
    val roots = nodes.filter { it.parentConversationId == null || it.parentConversationId !in ids }
        .sortedBy { it.createdAt }
    val result = ArrayList<PlacedNode>(nodes.size)
    val visited = HashSet<String>()

    fun visit(node: BranchNodeUi, depth: Int) {
        if (!visited.add(node.id)) return
        result += PlacedNode(node = node, depth = depth, row = result.size)
        children[node.id].orEmpty().forEach { child -> visit(child, depth + 1) }
    }

    roots.forEach { visit(it, 0) }
    // 被循环引用或父节点缺失到的孤立节点也要画出来，不能让它们凭空消失。
    nodes.sortedBy { it.createdAt }.forEach { if (it.id !in visited) visit(it, 0) }
    return result
}
