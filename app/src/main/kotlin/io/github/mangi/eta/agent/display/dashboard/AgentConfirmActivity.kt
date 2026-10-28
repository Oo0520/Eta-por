package io.github.mangi.eta.agent.display.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient

/**
 * 半屏确认页。
 *
 * 与生成式卡片不同，这里贴的是 VirtualDisplay 的真实帧（裁中部一块），
 * 用户看到什么就是当时的画面。三个出口：
 * - 确认：回到控制权状态机继续执行；
 * - 改：跳到接管全屏，让用户自己点；
 * - 取消：终止这次运行。
 * 另带一条「告诉 AI」的输入，走 steering 通道（运行中插话，不打断当前批次）。
 */
internal class AgentConfirmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "待确认操作" }
        val risk = intent?.getStringExtra(EXTRA_RISK).orEmpty()
        val fieldsRaw = intent?.getStringExtra(EXTRA_FIELDS).orEmpty()
        val previewPath = intent?.getStringExtra(EXTRA_PREVIEW_PATH).orEmpty()
        val conversation = intent?.getStringExtra(EXTRA_CONVERSATION).orEmpty()

        val fields = fieldsRaw.split("|").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }
        setContent {
            var bitmap by remember { mutableStateOf(loadBitmap(previewPath)) }
            LaunchedEffect(token) {
                if (bitmap == null) {
                    val captured = withContext(Dispatchers.IO) { capturePreview(applicationContext, token) }
                    bitmap = loadBitmap(captured)
                }
            }
            Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(Color(0xFF14171B))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText(
                        title,
                        style = TextStyle(color = Color(0xFFF2F4F7), fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    )
                    BasicText(
                        "AI 准备执行这一步，确认后继续；有异议可以直接让它改。",
                        style = TextStyle(color = Color(0xFF9AA4B2), fontSize = 12.sp),
                    )
                    if (risk.isNotBlank()) {
                        BasicText(
                            when (risk) {
                                "MONEY" -> "涉及资金，提交前请核对"
                                "PRIVACY" -> "涉及个人信息"
                                else -> "不可撤销操作"
                            },
                            style = TextStyle(color = Color(0xFFFFC44D), fontSize = 12.sp),
                        )
                    }
                    val preview = bitmap
                    if (preview != null) {
                        Image(
                            bitmap = preview.asImageBitmap(),
                            contentDescription = "当前画面",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                    if (fields.isEmpty()) {
                        BasicText(
                            "没有可预览的字段，建议先「去接管」看一眼真实画面。",
                            style = TextStyle(color = Color(0xFF9AA4B2), fontSize = 12.sp),
                        )
                    }
                    fields.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth()) {
                            BasicText(
                                label,
                                style = TextStyle(color = Color(0xFF9AA4B2), fontSize = 13.sp),
                                modifier = Modifier.width(72.dp),
                            )
                            BasicText(
                                value,
                                style = TextStyle(color = Color(0xFFF2F4F7), fontSize = 13.sp),
                            )
                        }
                    }
                    var steer by remember { mutableStateOf("") }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF1E232A))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (steer.isEmpty()) {
                                BasicText("告诉 AI 要改什么…", style = TextStyle(color = Color(0xFF6E7884), fontSize = 13.sp))
                            }
                            BasicTextField(
                                value = steer,
                                onValueChange = { steer = it },
                                textStyle = TextStyle(color = Color(0xFFF2F4F7), fontSize = 13.sp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        BasicText(
                            "发送",
                            style = TextStyle(color = Color(0xFF7BD8FF), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            modifier = Modifier.clickable {
                                if (steer.isNotBlank()) {
                                    runCatching { AgentRuntimeClient(applicationContext, io.github.mangi.eta.core.AndroidAgentLogger)
                                        .steerRun(steer, conversation) }
                                    steer = ""
                                }
                            }.padding(start = 10.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetButton("取消", Color(0xFFFF6B6B), Modifier.weight(1f)) {
                            AgentLiveCoordinator.onAction(this@AgentConfirmActivity, token, LiveActionKind.REJECT)
                            finish()
                        }
                        SheetButton("去接管", Color(0xFFB6BEC9), Modifier.weight(1f)) {
                            // 去接管 = 让悬浮窗自己铺成全屏，不再跳旧查看页。
                            AgentLiveCoordinator.requestTakeover(this@AgentConfirmActivity)
                            finish()
                        }
                        SheetButton("确认", Color(0xFF5FE08C), Modifier.weight(1f), filled = true) {
                            AgentLiveCoordinator.onAction(this@AgentConfirmActivity, token, LiveActionKind.CONFIRM)
                            finish()
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }

    private fun loadBitmap(path: String): Bitmap? {
        if (path.isBlank()) return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    companion object {
        const val EXTRA_TOKEN = "confirm_token"
        const val EXTRA_TITLE = "confirm_title"
        const val EXTRA_RISK = "confirm_risk"
        const val EXTRA_FIELDS = "confirm_fields"
        const val EXTRA_PREVIEW_PATH = "confirm_preview"
        const val EXTRA_CONVERSATION = "confirm_conversation"

        /** 从当前虚拟屏真帧裁中部一块，作为确认页的「证据图」。 */
        fun capturePreview(context: Context, token: String): String = runCatching {
            val result = io.github.mangi.eta.agent.display.VirtualScreenSession.observeForViewer(context)
            val reference = result.images.firstOrNull()?.reference ?: return ""
            val base64 = reference.substringAfter("base64,", "")
            if (base64.isBlank()) return ""
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
            val w = full.width
            val h = full.height
            val cw = (w * 0.72f).toInt().coerceAtLeast(1)
            val ch = (h * 0.72f).toInt().coerceAtLeast(1)
            val crop = Bitmap.createBitmap(full, (w - cw) / 2, (h - ch) / 2, cw, ch)
            val file = java.io.File(context.cacheDir, "eta-confirm-$token.png")
            java.io.FileOutputStream(file).use { crop.compress(Bitmap.CompressFormat.PNG, 92, it) }
            file.absolutePath
        }.getOrDefault("")
    }
}

@Composable
private fun SheetButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) accent else accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (filled) Color(0xFF0A1013) else Color(0xFFF2F4F7),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
