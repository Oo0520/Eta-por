package io.github.mangi.eta.ui.markdown

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import io.github.mangi.eta.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 带文件路径的代码块（info string 写成 `markdown file=/abs/path`）渲染成文件卡片：
 * 点开后全屏铺满、从顶部飞入；顶部小白条收起，右上角菜单可保存到设备 / 系统打开 / 分享；
 * 预览里的任务勾选可以点，铅笔改完保存直接覆盖原文件（应用无权限时走 root）。
 */
internal fun markdownFileTarget(info: String?): String? {
    val raw = info?.trim().orEmpty()
    val index = raw.indexOf("file=")
    if (index < 0) return null
    return raw.substring(index + 5)
        .trim()
        .takeWhile { !it.isWhitespace() }
        .takeIf { it.isNotBlank() && it.contains('/') }
}

internal data class MarkdownFileSpec(val path: String, val content: String)

/** info 串带 `file=` 优先；否则看正文首行的 `<!-- file=/path -->` 注释。 */
internal fun markdownFileSpec(info: String?, body: String): MarkdownFileSpec? {
    markdownFileTarget(info)?.let { return MarkdownFileSpec(it, body) }
    val firstLine = body.lineSequence().firstOrNull()?.trim().orEmpty()
    val match = Regex("^<!--\\s*file=(.+?)\\s*-->$").find(firstLine) ?: return null
    val path = match.groupValues[1].trim()
    if (!path.contains('/')) return null
    val rest = body.removePrefix(firstLine).removePrefix("\n")
    return MarkdownFileSpec(path, rest)
}

@Composable
internal fun MarkdownFileCard(path: String, content: String) {
    var open by remember(path) { mutableStateOf(false) }
    val name = path.substringAfterLast('/').ifBlank { path }
    val lines = remember(content) { content.lineSequence().count() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 12.dp)
            .clickable { open = true }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = path,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$lines 行",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    if (open) {
        MarkdownFileDialog(path = path, original = content, onDismiss = { open = false })
    }
}

@Composable
private fun MarkdownFileDialog(path: String, original: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entered by remember(path) { mutableStateOf(false) }
    var closing by remember(path) { mutableStateOf(false) }
    // 整页下拉位移：小白条拖动时整页跟手，退出也从当前位置继续下滑。
    var dragAccum by remember(path) { mutableFloatStateOf(0f) }
    var editing by remember(path) { mutableStateOf(false) }
    var text by remember(path) { mutableStateOf(original) }
    var saving by remember(path) { mutableStateOf(false) }
    var menuOpen by remember(path) { mutableStateOf(false) }
    // 勾选/编辑过就置 dirty：预览态也要能保存，否则勾了等于白勾。
    var dirty by remember(path) { mutableStateOf(false) }
    val name = path.substringAfterLast('/').ifBlank { path }

    fun close() {
        if (!entered || closing) return
        closing = true
        scope.launch {
            // 关窗前把没保存的改动落盘（勾选已即时保存，这里主要兜编辑态）。
            if (dirty) {
                val error = withContext(Dispatchers.IO) { writeMarkdownFile(context, path, text) }
                if (error == null) dirty = false
            }
            // 从当前下拉位置继续滑出屏幕，避免先跳回顶部再播退出动画。
            animate(
                initialValue = dragAccum,
                targetValue = 4000f,
                animationSpec = tween(260, easing = FastOutLinearInEasing),
            ) { value, _ -> dragAccum = value }
            onDismiss()
        }
    }

    val saveToDevice = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri: Uri? ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray())
                }
                true
            }.getOrDefault(false)
            Toast.makeText(context, if (ok) "已保存到设备" else "保存失败", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(path) {
        entered = true
        // 消息里的块只是初始快照；磁盘有文件时以文件为准，避免「存过了但还显示旧内容」。
        val disk = withContext(Dispatchers.IO) { runCatching { File(path).readText() }.getOrNull() }
        if (disk != null && disk != text) text = disk
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        AnimatedVisibility(
            visible = entered,
            enter = slideInVertically(
                animationSpec = tween(340, easing = FastOutSlowInEasing),
                initialOffsetY = { -it },
            ) + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(
                animationSpec = tween(300, easing = FastOutLinearInEasing),
                targetOffsetY = { it },
            ) + fadeOut(animationSpec = tween(220)),
        ) {
            val density = androidx.compose.ui.platform.LocalDensity.current
            val closeThreshold = with(density) { 120.dp.toPx() }
            // 进页后整页向下弹一下再回弹，提示「可以往下拉」。
            val hintPull = remember { Animatable(0f) }
            val hintDistance = with(density) { 14.dp.toPx() }
            LaunchedEffect(Unit) {
                delay(360)
                hintPull.animateTo(hintDistance, tween(190, easing = FastOutSlowInEasing))
                hintPull.animateTo(
                    0f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset {
                        IntOffset(0, (dragAccum + hintPull.value).roundToInt())
                    }
                    .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 28.dp),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding(),
            ) {
                // 顶部小白条：点一下收起。
                // 顶部小白条只做手势热区：拖动时整页跟着走。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 2.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = {
                                    if (dragAccum > closeThreshold) {
                                        // 关键：不要先把位移清零，close() 会从当前位置继续往下滑。
                                        close()
                                    } else {
                                        val from = dragAccum
                                        scope.launch {
                                            animate(
                                                initialValue = from,
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                    stiffness = Spring.StiffnessMedium,
                                                ),
                                            ) { value, _ -> dragAccum = value }
                                        }
                                    }
                                },
                                onVerticalDrag = { _, delta ->
                                    dragAccum = (dragAccum + delta).coerceAtLeast(0f)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(
                                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MiuixTheme.textStyles.body1,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = path,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (editing) {
                                // 编辑态：磁盘 = 保存并回到预览，和参考交互一致
                                scope.launch {
                                    saving = true
                                    val error = withContext(Dispatchers.IO) { writeMarkdownFile(context, path, text) }
                                    saving = false
                                    Toast.makeText(
                                        context,
                                        if (error == null) "已保存" else "保存失败：$error",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    if (error == null) {
                                        dirty = false
                                        editing = false
                                    }
                                }
                            } else {
                                editing = true
                            }
                        },
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                    ) {
                        Icon(
                            imageVector = if (editing) Icons.Rounded.Save else Icons.Rounded.Edit,
                            contentDescription = stringResource(R.string.action_save),
                            modifier = Modifier.size(18.dp),
                            tint = if (editing) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    IconButton(
                        onClick = { menuOpen = true },
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (editing) {
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                dirty = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                        )
                    } else {
                        StaticMarkdown(
                            content = text,
                            tone = MarkdownTone.Answer,
                            modifier = Modifier.fillMaxWidth(),
                            onTaskToggle = { offset ->
                                val next = toggleTaskAt(text, offset)
                                if (next != text) {
                                    text = next
                                    dirty = true
                                    // 勾选即保存：写盘成功才清 dirty，失败保留「保存」按钮并提示原因。
                                    scope.launch {
                                        val error = withContext(Dispatchers.IO) {
                                            writeMarkdownFile(context, path, next)
                                        }
                                        if (error == null) {
                                            dirty = false
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "自动保存失败：$error",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
            if (menuOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.18f))
                        .clickable { menuOpen = false },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 52.dp, end = 12.dp)
                        .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 14.dp)
                        .padding(vertical = 4.dp),
                ) {
                    MenuRow("保存到设备") {
                        menuOpen = false
                        saveToDevice.launch(name)
                    }
                    MenuRow("系统打开") {
                        menuOpen = false
                        runCatching {
                            val uri = stageShareFile(context, name, text)
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "text/markdown")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    MenuRow("分享文件") {
                        menuOpen = false
                        runCatching {
                            val uri = stageShareFile(context, name, text)
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType("text/markdown")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    null,
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MiuixTheme.textStyles.body2,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

/** 点一下任务项：把源码里那一行的 `[ ]` / `[x]` 对调。 */
private fun toggleTaskAt(source: String, offset: Int): String {
    if (offset !in source.indices) return source
    val lineStart = source.lastIndexOf('\n', offset - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = source.indexOf('\n', offset).let { if (it < 0) source.length else it }
    val line = source.substring(lineStart, lineEnd)
    val replaced = when {
        line.contains("[ ]") -> line.replaceFirst("[ ]", "[x]")
        line.contains("[x]") -> line.replaceFirst("[x]", "[ ]")
        line.contains("[X]") -> line.replaceFirst("[X]", "[ ]")
        else -> return source
    }
    return source.substring(0, lineStart) + replaced + source.substring(lineEnd)
}

/** 写一份到缓存再经 FileProvider 交出去（系统打开 / 分享用）。 */
private fun stageShareFile(context: Context, name: String, content: String): Uri {
    val dir = File(context.cacheDir, "shared_docs").apply { mkdirs() }
    val safe = name.ifBlank { "document.md" }.replace('/', '_')
    val file = File(dir, safe).apply { writeText(content) }
    return FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
}

/** 有写权限就直接写；没有（例如 /storage 的公共目录）就借 su 覆盖原文件。成功返回 null，失败返回原因。 */
private fun writeMarkdownFile(context: Context, path: String, content: String): String? = runCatching {
    val target = File(path)
    val parent = target.parentFile
    if (parent != null && (parent.canWrite() || (target.exists() && target.canWrite()))) {
        target.writeText(content)
        return@runCatching null
    }
    val staging = File(context.cacheDir, "md-edit-${System.currentTimeMillis()}.md").apply { writeText(content) }
    val quoted = path.replace("'", "'\\''")
    val shell = listOf("/system/bin/su", "su").firstOrNull { File(it).canExecute() } ?: "su"
    val process = ProcessBuilder(shell, "-c", "cat '${staging.absolutePath}' > '$quoted'")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    staging.delete()
    if (exit == 0) null else "su($shell) 退出码 $exit ${output.take(120)}"
}.getOrElse { it.message ?: it::class.simpleName ?: "未知错误" }
