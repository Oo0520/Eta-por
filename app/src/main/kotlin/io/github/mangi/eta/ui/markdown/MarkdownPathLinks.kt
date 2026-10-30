package io.github.mangi.eta.ui.markdown

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 聊天里可点击的本地路径。
 *
 * 规则（严格，宁缺勿滥）：
 * - 只认绝对路径且文件系统里确实存在；
 * - 目录不处理，避免误触打开一堆无关面板；
 * - 按扩展名分派：APK / ZIP 优先 MT 管理器，其它交给系统，MT 未安装时回落到系统。
 */
internal object MarkdownPathLinks {

    /** MT 管理器（Canary 与稳定版都试）。 */
    private val mtPackages = listOf("bin.mt.plus.canary", "bin.mt.plus")

    /** 行内代码是否像一个可以打开的文件。 */
    fun resolve(raw: String): String? {
        val path = raw.trim()
        if (path.length < 3 || !path.startsWith("/")) return null
        if (path.any { it.isWhitespace() || it == '\n' }) return null
        val file = runCatching { File(path) }.getOrNull() ?: return null
        return path.takeIf { runCatching { file.isFile && file.exists() }.getOrDefault(false) }
    }

    fun open(context: Context, path: String): Boolean {
        val file = runCatching { File(path) }.getOrNull() ?: return false
        if (!file.isFile) return false
        val mime = mimeOf(file.extension.lowercase())
        if (file.extension.lowercase() in setOf("apk", "zip") && openWithMt(context, file, mime)) return true
        return openWithSystem(context, file, mime)
    }

    /** APK / 压缩包：点名 MT 管理器打开；没装或打不开返回 false，由调用方回落系统。 */
    private fun openWithMt(context: Context, file: File, mime: String): Boolean {
        val installed = mtPackages.filter { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
        if (installed.isEmpty()) return false
        val uri = contentUri(context, file) ?: return false
        for (pkg in installed) {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setPackage(pkg)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /** 其它文件：交给系统按 MIME 找应用（多个候选时系统自己让用户选）。 */
    private fun openWithSystem(context: Context, file: File, mime: String): Boolean {
        val uri = contentUri(context, file) ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun contentUri(context: Context, file: File): Uri? =
        runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull()

    private fun mimeOf(extension: String): String = when (extension) {
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp", "heic" -> "image/*"
        "pdf" -> "application/pdf"
        "txt", "log", "json", "xml", "csv", "ini", "conf",
        "md", "kt", "java", "py", "sh", "js", "ts", "css", "html", "yml", "yaml" -> "text/plain"
        "mp4", "mkv", "webm" -> "video/*"
        "mp3", "m4a", "wav", "ogg", "flac" -> "audio/*"
        else -> "*/*"
    }
}
