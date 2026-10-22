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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Share
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 带文件路径的代码块（info string 写成 `markdown file=/abs/path`）渲染成文件卡片：
 * 点开后全屏铺满、从顶部飞入；顶部小白条收起，右上角菜单（窄卡片、带图标）可保存到设备 / 系统打开 / 分享；
 * 预览里的任务勾选可以点并即时保存；铅笔直接交给 MT 管理器编辑原文件，应用内不再提供编辑态。
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
/**
 * Markdown 文件的全屏预览对话框。
 *
 * 工作区文件浏览器也复用它：浏览器里点 .md 文件时，直接用同一个对话框读磁盘内容，
 * 不另造一套预览。编辑不走这里，铅笔把原文件交给 MT 管理器。
 *
 * @param readDisk 可选，从磁盘重新读取文本。默认直读文件；调用方需要 root 或自定义后端时传入，
 *   返回 null 表示读不到（保持当前内容不变）。工作区浏览器传基于 ShellFileToolBackend 的读取。
 */
internal fun MarkdownFileDialog(
    path: String,
    original: String,
    onDismiss: () -> Unit,
    readDisk: (suspend (String) -> String?)? = null,
    editRealPath: (() -> String?)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 底部弹层（与「会话导航」同一套）：先置 false，再拉起来播放入场动画。
    var visible by remember(path) { mutableStateOf(false) }
    var text by remember(path) { mutableStateOf(original) }
    var menuOpen by remember(path) { mutableStateOf(false) }
    // 送去 MT 编辑时置位，回到 Eta 的前台时重读一次磁盘，省得手动关开卡片。
    var refreshOnResume by remember(path) { mutableStateOf(false) }
    var reloadTick by remember(path) { mutableStateOf(0) }
    // 勾选过就置 dirty：勾选即保存，写失败时留到关闭再补一次。
    var dirty by remember(path) { mutableStateOf(false) }
    val name = path.substringAfterLast('/').ifBlank { path }

    fun requestClose() {
        if (!visible) return
        scope.launch {
            // 关窗前把没落盘的勾选改动再试一次（写失败时 dirty 仍为 true）。
            if (dirty) {
                val error = withContext(Dispatchers.IO) { writeMarkdownFile(context, path, text) }
                if (error == null) dirty = false
            }
            // 交给弹层播退场动画，动画走完再让宿主移除，避免生硬消失。
            visible = false
            delay(280)
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

    LaunchedEffect(path, reloadTick) {
        visible = true
        // 消息里的块只是初始快照；磁盘有文件时以文件为准，避免「存过了但还显示旧内容」。
        // 调用方给了 readDisk（如工作区浏览器需要 root 读取）就优先用它，否则直读文件。
        val disk = withContext(Dispatchers.IO) {
            if (readDisk != null) readDisk(path) else runCatching { File(path).readText() }.getOrNull()
        }
        if (disk != null && disk != text) {
            text = disk
            dirty = false
        }
    }

    // 从 MT 管理器返回时重读：Activity 重新回到前台就刷一次（只在送去编辑之后生效）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && refreshOnResume) {
                refreshOnResume = false
                reloadTick += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 复用「会话导航」的底部弹层：从底部滑入、上方留出背景、下拉收起，不占满全屏。
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sheetHeight = with(density) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()
    } * 0.9f

    OverlayBottomSheet(
        show = visible,
        title = name,
        onDismissRequest = { requestClose() },
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = path,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            // 「笔」把原文件交给 MT 管理器，回来时自动刷新预览。
                            val target = editRealPath?.invoke() ?: path
                            if (openInMtEditor(context, target)) {
                                refreshOnResume = true
                            } else {
                                Toast.makeText(context, "没有找到可用的编辑器", Toast.LENGTH_SHORT).show()
                            }
                        },
                        minWidth = 36.dp,
                        minHeight = 36.dp,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "用 MT 管理器编辑",
                            modifier = Modifier.size(18.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
                        .padding(horizontal = 20.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    StaticMarkdown(
                        content = text,
                        tone = MarkdownTone.Answer,
                        modifier = Modifier.fillMaxWidth(),
                        onTaskToggle = { offset ->
                            val next = toggleTaskAt(text, offset)
                            if (next != text) {
                                text = next
                                dirty = true
                                // 勾选即保存：写盘成功才清 dirty，失败提示原因，关闭时再兜底重试。
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
                        .padding(top = 8.dp, end = 12.dp)
                        .widthIn(min = 196.dp)
                        .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 16.dp)
                        .padding(vertical = 6.dp),
                ) {
                    MenuRow(Icons.Rounded.Download, "保存到设备") {
                        menuOpen = false
                        saveToDevice.launch(name)
                    }
                    MenuRow(Icons.AutoMirrored.Rounded.OpenInNew, "系统打开") {
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
                    MenuRow(Icons.Rounded.Share, "分享文件") {
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
/** 菜单行：图标 + 文字，宽度由内容决定，不撑满屏幕（与对话页溢出菜单一致）。 */
@Composable
private fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 22.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(14.dp))
        Text(text = label, style = MiuixTheme.textStyles.body2)
    }
}

/** MT 管理器的包名与文本编辑入口（Canary 与稳定版都试一遍）。 */
private const val MT_PACKAGE_CANARY = "bin.mt.plus.canary"
private const val MT_PACKAGE_STABLE = "bin.mt.plus"
private const val MT_EDIT_ACTIVITY = ".OpenTextActivity"

/**
 * 把原文件交给 MT 管理器编辑。
 *
 * 必须用 content://：Android 7+ 把 file:// 交给别的应用会抛 FileUriExposedException，
 * FileProvider 的路径已在 eta_file_paths.xml 里放开外部存储根。
 * 返回 false 表示 MT 打不开（未安装或组件改名），调用方负责提示。
 */
private fun openInMtEditor(context: Context, path: String): Boolean {
    // 只认真实存在的绝对路径：相对路径会被 am 当成 file://<host>，MT 那边报「Is a directory」。
    if (!path.startsWith("/") || !File(path).isFile) return false
    val packages = listOf(MT_PACKAGE_CANARY, MT_PACKAGE_STABLE).filter { isInstalled(context, it) }
    // 没装 MT：按约定交给系统的「打开方式」。
    if (packages.isEmpty()) return openWithSystemChooser(context, path)

    // 一档：API 层点名 MT 的文本入口，能过系统校验就最快，中间没有选择。
    val uri = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(path))
    }.getOrNull()
    if (uri != null) {
        val base = Intent(Intent.ACTION_EDIT)
            .setDataAndType(uri, "text/plain")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for (pkg in packages) {
            val explicit = Intent(base).setClassName(pkg, MT_EDIT_ACTIVITY)
            if (runCatching { context.startActivity(explicit) }.isSuccess) return true
        }
    }
    // 二档：系统把这类非公开入口拦成「打开方式」时，改由 root 直接叫起 MT。
    for (pkg in packages) {
        if (rootAmStartEditor(pkg, path)) return true
    }
    return openWithSystemChooser(context, path)
}

/** 系统「打开方式」：不指定应用，交给系统挑。 */
private fun openWithSystemChooser(context: Context, path: String): Boolean {
    val uri = runCatching {
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", File(path))
    }.getOrNull() ?: return false
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "text/plain")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}

/**
 * 借 root 的 `am start` 拉起 MT 的文本入口。
 *
 * 这里给真实路径（file://）而不是 content://：am 带不出去 URI 授权，
 * MT 自己有 MANAGE_EXTERNAL_STORAGE 与 root，读写原文件没问题。
 */
private fun rootAmStartEditor(pkg: String, path: String): Boolean = runCatching {
    val shell = listOf("/system/bin/su", "su").firstOrNull { File(it).canExecute() } ?: "su"
    val quoted = path.replace("'", "'\\''")
    val component = "$pkg/$MT_EDIT_ACTIVITY"
    val cmd = "am start --user 0 -a android.intent.action.EDIT -t 'text/plain' " +
        "-d 'file://$quoted' -n '$component'"
    val process = ProcessBuilder(shell, "-c", cmd).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    val exit = process.waitFor()
    exit == 0 &&
        !output.contains("Error", ignoreCase = true) &&
        !output.contains("Permission Denial", ignoreCase = true)
}.getOrDefault(false)

private fun isInstalled(context: Context, pkg: String): Boolean =
    runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

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
