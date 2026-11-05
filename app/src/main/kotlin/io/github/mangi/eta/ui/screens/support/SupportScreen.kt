package io.github.mangi.eta.ui.screens.support

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceColors
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaPreferenceIcon
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 支付宝个人收款码。解码自「保存收款码」海报，内容是标准 https 链接，
 * 因此可以用 alipays:// 直接唤起付款页。
 */
private const val ALIPAY_QR_URL = "https://qr.alipay.com/fkx153429ghybhk9cz6kecc"

private const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

/**
 * 爱发电主页。形如 https://afdian.com/a/<创作者 ID>。
 * 它是订阅/单次赞助页，走平台内支付，不需要二维码。
 */
// 填了才显示入口：留空时页面只有支付宝，不会出现死链。
private const val AFDIAN_URL = "https://afdian.com/a/Mcool520"

/**
 * 赞赏页。
 *
 * 两个通道的形态不同，原因是格式差异，不是偷懒：
 * - 支付宝个人码是标准 QR，内容是 https 链接，`alipays://...&qrcode=<编码后的链接>`
 *   能直接落到付款页，所以做成一键按钮。
 * - 微信赞赏码是微信私有「小程序码」（辐状点线 + 三个定位圆），第三方扫码器解不出内容，
 *   只有微信客户端认，所以只能展示图片，让用户保存后用微信「扫一扫 → 相册」。
 *
 * 也因此两边无法合成一张码：图层合并依赖 QR 的定位角与纠错区，小程序码没有这些结构。
 */
@Composable
fun SupportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    MiuixScaffoldPage(title = stringResource(R.string.support_title), onBack = onBack) {
        item(key = "support-intro") {
            Text(
                text = stringResource(R.string.support_intro),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
            )
        }

        // ── 支付宝：一键拉起付款页 ──────────────────────────────
        item(key = "support-alipay") {
            EtaPreferenceGroupTitle(stringResource(R.string.support_section_direct))
            EtaPreferenceGroup {
                EtaArrowPreference(
                    title = stringResource(R.string.support_alipay_title),
                    summary = stringResource(R.string.support_alipay_summary),
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.Rounded.AccountBalanceWallet,
                            tint = EtaPreferenceColors.Blue,
                        )
                    },
                    onClick = { openAlipay(context) },
                )

            }
        }

        // ── 微信：小程序码，只能看图和保存 ───────────────────────
        item(key = "support-wechat") {
            EtaPreferenceGroupTitle(stringResource(R.string.support_wechat_title))
            EtaPreferenceGroup {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(R.drawable.support_wechat_reward),
                        contentDescription = stringResource(R.string.support_wechat_title),
                        modifier = Modifier
                            .size(196.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.support_wechat_hint),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }

                EtaPreferenceDivider()
                EtaArrowPreference(
                    title = stringResource(R.string.support_save_title),
                    summary = stringResource(R.string.support_save_summary),
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.Rounded.Download,
                            tint = EtaPreferenceColors.Green,
                        )
                    },
                    onClick = {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) { saveRewardToGallery(context) }
                            Toast.makeText(
                                context.applicationContext,
                                context.getString(
                                    if (ok) R.string.support_save_done else R.string.support_save_failed,
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }

        // ── 说明 ────────────────────────────────────────────
        item(key = "support-note") {
            EtaPreferenceGroupTitle(stringResource(R.string.support_section_note))
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.support_note_voluntary),
                    summary = stringResource(R.string.support_note_voluntary_summary),
                    startAction = {
                        EtaPreferenceIcon(
                            icon = Icons.Rounded.Chat,
                            tint = EtaPreferenceColors.Orange,
                        )
                    },
                )
            }
        }

        // 页脚第二入口：按月/长期支持。有链接才出现。
        if (AFDIAN_URL.isNotEmpty()) {
            item(key = "support-long-term") {
                EtaPreferenceGroupTitle(stringResource(R.string.support_section_long_term))
                EtaPreferenceGroup {
                    EtaArrowPreference(
                        title = stringResource(R.string.support_afdian_title),
                        summary = stringResource(R.string.support_afdian_summary),
                        startAction = {
                            EtaPreferenceIcon(
                                icon = Icons.Rounded.Coffee,
                                tint = EtaPreferenceColors.Orange,
                            )
                        },
                        onClick = { openExternal(context, AFDIAN_URL) },
                    )
                }
            }
        }

        item(key = "support-footer") {
            Text(
                text = stringResource(R.string.support_footer),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 24.dp),
            )
        }
    }
}

/** 用 alipays:// 直接落到付款页；没装支付宝就退回复制收款链接。 */
private fun openAlipay(context: Context) {
    val encoded = Uri.encode(ALIPAY_QR_URL)
    val uri = Uri.parse("alipays://platformapi/startapp?saId=10000007&qrcode=$encoded")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        setPackage(ALIPAY_PACKAGE)
    }
    val launched = runCatching { context.startActivity(intent) }.isSuccess
    if (!launched) {
        copyToClipboard(context, ALIPAY_QR_URL)
        Toast.makeText(
            context.applicationContext,
            context.getString(R.string.support_alipay_missing),
            Toast.LENGTH_LONG,
        ).show()
    }
}

/** 用系统浏览器打开外链；没有浏览器就退回复制。 */
private fun openExternal(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (runCatching { context.startActivity(intent) }.isFailure) {
        copyToClipboard(context, url)
        Toast.makeText(
            context.applicationContext,
            context.getString(R.string.support_link_missing),
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Eta", text))
    }
}

/** 把内置的赞赏码写进相册，用户可以再从微信「扫一扫 → 相册」里选。 */
private fun saveRewardToGallery(context: Context): Boolean {
    val bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.support_wechat_reward)
        ?: return false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "eta_reward_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + "/Eta",
        )
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    val written = runCatching {
        resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        } != null
    }.getOrDefault(false)
    if (!written) {
        runCatching { resolver.delete(uri, null, null) }
        return false
    }
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    runCatching { resolver.update(uri, values, null, null) }
    bitmap.recycle()
    return true
}
