package io.github.mangi.eta.ui.screens.terminal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import io.github.mangi.eta.ui.components.ImagePreviewOverlay
import io.github.mangi.eta.ui.components.PaneHintBar
import io.github.mangi.eta.ui.onboarding.OnboardingKeys
import io.github.mangi.eta.ui.onboarding.tourAnchor
import io.github.mangi.eta.ui.onboarding.tourAnchorFirst
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.mangi.eta.ui.markdown.MarkdownFileDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 工作区文件面板：会话页整体右移、面板占主视觉。
 *
 * 关键在 [WorkspaceShiftHost]：它把会话页（NavDisplay）作为一个整体向右推走，
 * 面板从右边滑入填补空位，所以左侧会露出被推走的会话页边缘，顶栏那一行留在原处。
 * 这比「半透明遮罩盖住」更符合操作直觉——被盖住的页面看着像被锁定，被推走的页面看着还能回去。
 *
 * 根目录是 root 工作区（/data/local/tmp/eta，Linux 侧 /workspace）；
 * Markdown 走内置全屏预览，其余类型经 FileProvider 转 cache 后交给外部应用。
 */
@Composable
internal fun WorkspaceBrowserSheet(
    visible: Boolean,
    topInset: androidx.compose.ui.unit.Dp,
    onDismiss: () -> Unit,
    /** 预览交给宿主渲染：弹层是树内底部弹层，放在被裁剪的面板里会被截掉。 */
    onOpenMarkdown: ((String, String) -> Unit)? = null,
) {
    // 入场从右滑到定位、退场滑回右侧：之前是 visible 一翻转就整块出现，观感是「突变」。

    val context = LocalContext.current
    val store = remember(context.applicationContext) { WorkspaceFileStore(context) }
    var previewImage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val expanded = remember { mutableListOf<String>().toMutableStateList() }
    var rootEntries by remember { mutableStateOf<List<WorkspaceEntry>?>(null) }
    var rows by remember { mutableStateOf<List<TreeRow>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    // 面板自己的刷新计数：顶栏不提供刷新，按钮在面板头部。
    var refreshTick by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    // 打开方式选择器是否展开
    var markdownPath by remember { mutableStateOf<String?>(null) }
    var markdownText by remember { mutableStateOf("") }

    LaunchedEffect(revision, refreshTick) {
        rootEntries = try {
            store.list("")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    // 展开变化时重读全树。store 复用同一个 backend，不会每次展开都新起 shell 进程。
    LaunchedEffect(rootEntries, expanded.toList(), revision, refreshTick) {
        val base = rootEntries
        val expandedSnapshot = expanded.toList()
        rows = if (base == null) emptyList() else withContext(Dispatchers.IO) {
            flattenTree(store, base, expandedSnapshot)
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            initialOffsetX = { it },
        ) + fadeIn(tween(200)),
        exit = slideOutHorizontally(
            animationSpec = tween(240, easing = FastOutLinearInEasing),
            targetOffsetX = { it },
        ) + fadeOut(tween(180)),
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 从顶栏以下开始：顶栏那一行保持原样，不在这层里重画。
            .padding(top = topInset),
    ) {
        // 面板铺满：会话页被完全盖住但状态保留，返回时原样露出。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(MiuixTheme.colorScheme.surface)
                .tourAnchor("workspace.tree"),
        ) {
            PaneHintBar(
                id = OnboardingKeys.WorkspaceHint,
                text = "点文件直接预览，Markdown 会渲染成卡片；空目录可以让 Agent 建个文件。",
            )
            // 面板自己的头：路径 + 刷新 + 在 MT2 中打开。
            // 顺序按用户要求：刷新在左，MT2 固定在其右边。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // 显示真实根路径，不再写死 /workspace：那个只是 Linux 侧的挂载名，
                    // 用户在 Android 侧看到它会对不上，排查时也没法直接拿去用。
                    text = io.github.mangi.eta.agent.terminal.TerminalRuntime.ROOT_WORKSPACE_PATH,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 刷新：转一整圈作为反馈。
                var spinning by remember { mutableStateOf(false) }
                val spin by animateFloatAsState(
                    targetValue = if (spinning) 360f else 0f,
                    animationSpec = tween(600, easing = FastOutSlowInEasing),
                    finishedListener = { spinning = false },
                    label = "refresh-spin",
                )
                IconButton(onClick = {
                    spinning = true
                    revision++
                }) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.workspace_browser_refresh),
                        modifier = Modifier.size(20.dp).rotate(spin),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            val current = rootEntries
            when {
                current == null -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { InfiniteProgressIndicator(size = 24.dp) }

                rows.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.workspace_browser_empty),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows, key = { it.path }) { row ->
                        WorkspaceTreeRow(
                            row = row,
                            expanded = row.path in expanded,
                            onClick = {
                                if (row.entry.directory) {
                                    if (row.path in expanded) expanded.remove(row.path)
                                    else expanded.add(row.path)
                                } else if (isImageFile(row.entry.name)) {
                                    // 图片不交给 MT2：直接在本页全屏看，缩放/翻页都能用。
                                    previewImage = store.absolutePath(row.path)
                                } else if (isMarkdown(row.entry.name)) {
                                    loading = true
                                    scope.launch {
                                        val text = try {
                                            store.readText(row.path)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            null
                                        }
                                        loading = false
                                        if (text == null) {
                                            message = context.getString(R.string.workspace_browser_read_failed)
                                        } else if (onOpenMarkdown != null) {
                                            onOpenMarkdown(row.path, text)
                                        } else {
                                            markdownPath = row.path
                                            markdownText = text
                                        }
                                    }
                                } else {
                                    // 非 Markdown：直接丢给 MT2 管理器，不弹应用列表。
                                    // Codex 的「用 VSCode 打开」是同一个思路——固定默认编辑器，不让人每次挑。
                                    scope.launch {
                                        val error = openInMtManager(context, store, row.entry.path)
                                        if (error != null) message = error
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) { InfiniteProgressIndicator(size = 28.dp) }
        }
    }
    }

    previewImage?.let { path ->
        ImagePreviewOverlay(
            images = listOf(path),
            onDismiss = { previewImage = null },
        )
    }

    message?.let { text ->
        val localized = when (text) {
            "MT_NOT_INSTALLED" -> context.getString(R.string.workspace_browser_mt_missing)
            "MT_LAUNCH_FAILED" -> context.getString(R.string.workspace_browser_mt_failed)
            // 带上原始码：出问题时能从截图直接看出是复制失败、取 URI 失败还是别的原因，
            // 不用再让用户复现一遍等我抓日志。
            else -> context.getString(R.string.workspace_browser_read_failed) + " [$text]"
        }
        LaunchedEffect(text) {
            Toast.makeText(context, localized, Toast.LENGTH_SHORT).show()
            message = null
        }
    }

    if (markdownPath != null) {
        MarkdownFileDialog(
            path = markdownPath!!,
            original = markdownText,
            onDismiss = { markdownPath = null },
            readDisk = { p -> readViaStore(store, p) },
            // 树里的 path 是工作区相对路径；交给 MT 时必须换成真实绝对路径，
            // 否则 am 会把 file://<相对路径> 解析成目录，MT 报「Read failed: Is a directory」。
            editRealPath = {
                val relative = markdownPath
                if (relative == null) {
                    null
                } else {
                    File(io.github.mangi.eta.agent.terminal.TerminalRuntime.ROOT_WORKSPACE_PATH, relative)
                        .absolutePath
                }
            },
        )
    }

}

/** 树的一行。 */
private data class TreeRow(val path: String, val entry: WorkspaceEntry, val depth: Int)

/**
 * 把已展开的目录递归读出来，扁平化成可见行。
 *
 * store 复用同一个 backend（一次 shell 会话），所以展开多层不会再逐层起进程。
 */
private suspend fun flattenTree(
    store: WorkspaceFileStore,
    entries: List<WorkspaceEntry>,
    expanded: List<String>,
): List<TreeRow> {
    val out = mutableListOf<TreeRow>()
    suspend fun walk(list: List<WorkspaceEntry>, depth: Int) {
        list.forEach { entry ->
            out += TreeRow(entry.path, entry, depth)
            if (entry.directory && entry.path in expanded) {
                val children = try {
                    store.list(entry.path)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                walk(children, depth + 1)
            }
        }
    }
    walk(entries, 0)
    return out
}

@Composable
private fun WorkspaceTreeRow(
    row: TreeRow,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .tourAnchorFirst("workspace.file")
            .padding(start = (12 + row.depth * 18).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon: ImageVector = when {
            // 展开时换成「打开的文件夹」：形态本身变化，比只改颜色更像状态切换。
            row.entry.directory && expanded -> Icons.Outlined.FolderOpen
            row.entry.directory -> Icons.Outlined.Folder
            isMarkdown(row.entry.name) -> Icons.Rounded.Description
            else -> Icons.AutoMirrored.Rounded.InsertDriveFile
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (row.entry.directory) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.entry.name,
                style = MiuixTheme.textStyles.body1,
                // 这个面板用的是自绘背景，没有接内容色，暗色下会退回默认黑，文件名整个看不见。
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val summary = when {
                row.entry.directory -> null
                isMarkdown(row.entry.name) -> stringResource(
                    R.string.workspace_browser_markdown_summary,
                    Formatter.formatShortFileSize(context, row.entry.size),
                )
                else -> stringResource(
                    R.string.workspace_browser_binary_summary,
                    Formatter.formatShortFileSize(context, row.entry.size),
                )
            }
            if (summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when {
            // 同一个箭头转 180°，比换两个图标更像「展开」这个动作本身。
            row.entry.directory -> {
                // 未展开朝左、展开朝下：起点 -90° 而不是 180°，
                // 之前写成 180° 会让收起态箭头朝上，与「还能再点开」的直觉相反。
                val arrowRotation by animateFloatAsState(
                    targetValue = if (expanded) 0f else -90f,
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    label = "tree-arrow",
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.workspace_browser_collapse else R.string.workspace_browser_expand,
                    ),
                    modifier = Modifier.size(22.dp).rotate(arrowRotation),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            !isMarkdown(row.entry.name) -> Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = stringResource(R.string.workspace_browser_open_with),
                modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** MT2 管理器的包名（用户装的是 canary 通道）。 */
private const val MT_MANAGER_PACKAGE = "bin.mt.plus.canary"

/** 找不到 MT2 时的兜底包名（正式版）。 */
private const val MT_MANAGER_PACKAGE_STABLE = "bin.mt.plus"

/** 日志 tag：用 Log.i / Log.e，R8 不会剥掉（Log.d / Log.v 才会）。 */
private const val WS_TAG = "EtaWorkspace"

/** 有 root 读写能力的 MT2 包名：文件可以直传真实路径，不必复制进 cache。 */
private val MT_PACKAGES = setOf(MT_MANAGER_PACKAGE, MT_MANAGER_PACKAGE_STABLE)

/**
 * 把工作区里的文件交给选定的应用打开。
 *
 * 两条路，按目标应用有没有 root 能力区分：
 *
 *   1. MT2 管理器：它有 root，能直接读 /data/local/tmp/eta，所以把**真实路径**编成
 *      file:// URI 交给它，不复制。省掉一次整文件拷贝，也没有 2 MB 之类的预览上限。
 *      file:// 在 app 进程里发会撞 FileUriExposedException，所以由 root shell 发 am start。
 *   2. 其他应用（系统文件管理器等）：没有 root，读不到工作区原路径，只能把文件复制进
 *      cache 再给 content:// URI。
 *
 * MIME 一律用通配类型：MT2 注册的是「text / image / audio / video / application」这类
 * 静态类型，给 application/vnd.android.package-archive 这种精确 mime 反而匹配不上，
 * 表现出来就是「没有找到能打开这个文件的应用」。
 *
 * @return null 表示已交付；否则是要显示给用户的原因
 */
private suspend fun openInMtManager(
    context: Context,
    store: WorkspaceFileStore,
    relativePath: String,
): String? = withContext(Dispatchers.IO) {
    val target = resolveOpenTarget(context)
    val root = File(io.github.mangi.eta.agent.terminal.TerminalRuntime.ROOT_WORKSPACE_PATH)
    val realPath = if (relativePath.isBlank()) root.absolutePath else File(root, relativePath).absolutePath

    // 目标有 root（MT2）：直传真实路径
    if (target != null && target in MT_PACKAGES) {
        Log.i(WS_TAG, "open $realPath via $target (direct path)")
        val cmd = "am start -a android.intent.action.VIEW " +
            "-d 'file://$realPath' -t '*/*' -p '$target'"
        val result = runCatching { runShell(cmd) }.getOrNull()
        return@withContext if (result != null && result.exitCode == 0) null else "MT_LAUNCH_FAILED"
    }

    // 其他应用：没有 root，必须复制进 cache 再走 content://
    val uri = stageToCache(context, store, relativePath) ?: return@withContext "STAGE_FAILED"
    Log.i(WS_TAG, "open $relativePath -> $uri via ${target ?: "system"}")
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // target 为 null 时不指定包名：交给系统默认应用或选择器。
        if (target != null) setPackage(target)
    }
    val error = runCatching { context.startActivity(intent) }.exceptionOrNull()
    if (error == null) null else {
        Log.e(WS_TAG, "startActivity failed for $relativePath", error)
        "MT_LAUNCH_FAILED"
    }
}

/**
 * 按字节把文件复制进 cacheDir/shared_docs，再经 FileProvider 拿到 content URI。
 *
 * 只给「没有 root 的目标应用」用（系统文件管理器等）：它们读不到 /data/local/tmp/eta，
 * 只能拿一份 App 能供给的副本。用 copyTo 而不是 readText——后者强制 UTF-8 解码
 * 且受 2 MB 预览上限约束，APK / 图片 / 压缩包会被误判成读取失败。
 */
private suspend fun stageToCache(
    context: Context,
    store: WorkspaceFileStore,
    relativePath: String,
): Uri? = withContext(Dispatchers.IO) {
    val name = relativePath.substringAfterLast('/')
    val dir = File(context.cacheDir, "shared_docs").apply { mkdirs() }
    val staged = File(dir, "${name}-${System.currentTimeMillis()}")
    val copied = runCatching { store.copyTo(relativePath, staged) }
    if (copied.isFailure) {
        Log.e(WS_TAG, "copy failed: $relativePath", copied.exceptionOrNull())
        staged.delete()
        return@withContext null
    }
    val uri = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", staged)
    }
    if (uri.isFailure) {
        Log.e(WS_TAG, "FileProvider failed for $staged", uri.exceptionOrNull())
        staged.delete()
        return@withContext null
    }
    staged.deleteOnExit()
    uri.getOrNull()
}

/** 走 root shell 发一条 am 命令。 */
private fun runShell(command: String) = io.github.mangi.eta.agent.terminal.runOneShotShell(
    io.github.mangi.eta.agent.terminal.ShellProcessSupervisor(),
    "root",
    command,
    timeoutSeconds = 10,
)

/** 这个包名是不是装着。 */
private fun installed(context: Context, pkg: String): Boolean =
    runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

/**
 * 解析该用哪个应用打开文件。
 *
 * 优先级：用户在「打开方式」里显式选的 → 自动（MT2 canary / 正式版）→ null。
 * null 表示不指定包名、交给系统：有系统文件管理器就它上，没有就弹系统选择器。
 * 这条兜底是给没装 MT2 的设备的——以前直接返回「没有安装」，等于整个功能不可用。
 */
private fun resolveOpenTarget(context: Context): String? {
    WorkspaceOpenWithStore.current()?.let { chosen ->
        if (installed(context, chosen)) return chosen
        // 选过的应用被卸载了：清掉设置，回落到自动。
        Log.w(WS_TAG, "chosen package gone: $chosen, falling back to auto")
        WorkspaceOpenWithStore.clear()
    }
    return listOf(MT_MANAGER_PACKAGE, MT_MANAGER_PACKAGE_STABLE)
        .firstOrNull { installed(context, it) }
}

/** 把整个工作区目录交给 MT2 打开（顶部「在 MT2 中打开」按钮）。 */
private suspend fun openWorkspaceInMtManager(
    context: Context,
    store: WorkspaceFileStore,
    relativePath: String,
): String? = withContext(Dispatchers.IO) {
    val target = resolveOpenTarget(context)
    val root = File(io.github.mangi.eta.agent.terminal.TerminalRuntime.ROOT_WORKSPACE_PATH)
    // blank 表示工作区根目录。不能直接 File(root, "/workspace")：
    // Java 会把绝对路径拼成 /data/local/tmp/eta/workspace，那个目录不存在。
    val dir = if (relativePath.isBlank()) root else File(root, relativePath)
    if (!dir.isDirectory) {
        Log.e(WS_TAG, "not a directory: ${dir.absolutePath}")
        return@withContext "STAGE_FAILED"
    }
    // 目录无法经 FileProvider 分享（它只能供文件），而 app 进程里用 file:// 会撞
    // FileUriExposedException。改由 root shell 发 am start：那条路径不经过本应用的 StrictMode 检查。
    // 类型必须用 */*：MT2 不声明 resource/folder，传它直接 unable to resolve Intent。
    // 没指定目标时不加 -p，交给系统默认应用；指定了才限定包名。
    val pkgArg = target?.let { " -p '$it'" }.orEmpty()
    val cmd = "am start -a android.intent.action.VIEW " +
        "-d 'file://${dir.absolutePath}' -t '*/*'$pkgArg"
    val result = runCatching { runShell(cmd) }.getOrNull()
    if (result != null && result.exitCode == 0) null else "MT_LAUNCH_FAILED"
}

private suspend fun readViaStore(store: WorkspaceFileStore, path: String): String? =
    try {
        store.readText(path)
    } catch (_: Exception) {
        null
    }

private fun isImageFile(name: String): Boolean {
    val lower = name.lowercase()
    return listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".heic").any(lower::endsWith)
}

private fun isMarkdown(name: String): Boolean =
    name.endsWith(".md", ignoreCase = true) || name.endsWith(".markdown", ignoreCase = true)


/** 打开方式的一个候选。packageName 为 null 表示「自动」。 */
private data class OpenWithOption(val label: String, val packageName: String?, val selected: Boolean)

/**
 * 常见文件管理器的包名。
 *
 * 不能只靠 `vnd.android.document/directory` 查询：OPPO 的 com.coloros.filemanager
 * 只声明压缩包类型，Google/AOSP 的 documentsui 才声明目录类型。两条来源取并集，
 * 才不会漏掉用户手机上真正在用的那个。
 */
private val KNOWN_FILE_MANAGERS = listOf(
    MT_MANAGER_PACKAGE,          // MT2 管理器 canary
    MT_MANAGER_PACKAGE_STABLE,   // MT2 管理器正式版
    "com.coloros.filemanager",   // ColorOS 文件管理
    "com.android.documentsui",   // AOSP 文件
    "com.google.android.documentsui",
    "com.android.fileexplorer",
    "com.mi.android.globalFileexplorer",
    "com.huawei.hidisk",
    "com.sec.android.app.myfiles",
)

/**
 * 生成「打开方式」候选：自动 + 已装的文件管理器。
 *
 * 只收文件管理器，不收所有能 VIEW 的应用——用通配类型查询会把「信息」「拨号」
 * 「支付宝二维码识别」全捞进来，对「用哪个应用打开工作区文件」没有意义。
 */
private fun openWithOptions(context: Context): List<OpenWithOption> {
    val pm = context.packageManager
    val current = WorkspaceOpenWithStore.current()
    val out = mutableListOf(
        OpenWithOption(
            label = context.getString(R.string.workspace_browser_open_with_auto),
            packageName = null,
            selected = current == null,
        ),
    )
    val seen = mutableSetOf<String>()
    // 名单优先：按 KNOWN_FILE_MANAGERS 的顺序（MT2 在最前，符合「默认 MT2」的预期）
    KNOWN_FILE_MANAGERS.forEach { pkg ->
        if (!installed(context, pkg) || !seen.add(pkg)) return@forEach
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: return@forEach
        out += OpenWithOption(label, pkg, current == pkg)
    }
    // 再补上声明了目录类型的其他文件管理器
    runCatching {
        pm.queryIntentActivities(
            Intent(Intent.ACTION_VIEW).setType("vnd.android.document/directory"),
            0,
        ).forEach { resolve ->
            val pkg = resolve.activityInfo.packageName
            if (pkg != context.packageName && seen.add(pkg)) {
                out += OpenWithOption(resolve.loadLabel(pm).toString(), pkg, current == pkg)
            }
        }
    }
    return out
}
