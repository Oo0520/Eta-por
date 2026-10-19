package io.github.mangi.eta.ui.markdown

import android.graphics.Color as AndroidColor
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 块级数学公式：用打包在 assets 里的 KaTeX 离线渲染，不联网。
 * 首帧按滚动高度自动撑开，避免 WebView 固定高度把公式裁掉。
 */
@Composable
internal fun MathBlockView(tex: String, modifier: Modifier = Modifier) {
    val textColor = "#%06X".format(MiuixTheme.colorScheme.onSurface.toArgb() and 0xFFFFFF)
    val html = remember(tex, textColor) { katexHtml(tex, textColor) }
    var height by remember(tex) { mutableStateOf(132) }
    AndroidView(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.domStorageEnabled = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        // KaTeX 字体是异步加载的，首帧量到的高度会偏小：等字体现成再量，并留余量。
                        val measure = {
                            view.evaluateJavascript(
                                "(function(){var d=document.querySelector('.katex-display')||document.body;" +
                                    "var r=d.getBoundingClientRect();" +
                                    "return Math.ceil(r.height + r.top + 16);})()",
                            ) { raw ->
                                val px = raw?.trim()?.toFloatOrNull() ?: return@evaluateJavascript
                                // WebView 的 CSS 像素≈dp（页面按 density 缩放）；再除 density 会把高度算小，底部被裁。
                                height = px.toInt().coerceIn(48, 900)
                            }
                        }
                        view.evaluateJavascript(
                            "(document.fonts && document.fonts.ready) ? document.fonts.ready.then(function(){return 1}) : 1",
                        ) { _ ->
                            view.postDelayed({ measure() }, 60)
                            view.postDelayed({ measure() }, 420)
                        }
                    }
                }
                loadDataWithBaseURL(ASSET_BASE, html, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            view.loadDataWithBaseURL(ASSET_BASE, html, "text/html", "utf-8", null)
        },
    )
}

private const val ASSET_BASE = "file:///android_asset/katex/"

private fun katexHtml(tex: String, textColor: String): String {
    val quoted = JSONObject.quote(tex)
    return """
<!doctype html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<link rel="stylesheet" href="katex.min.css">
<style>
html, body { margin:0; padding:0; background:transparent; }
body { color:$textColor; font-size:16px; }
.katex-display { margin: 4px 0; }
</style>
</head><body>
<div id="m"></div>
<script src="katex.min.js"></script>
<script>
try {
  katex.render($quoted, document.getElementById('m'), { displayMode: true, throwOnError: false });
} catch (e) {
  document.getElementById('m').textContent = $quoted;
}
</script>
</body></html>
""".trimIndent()
}
