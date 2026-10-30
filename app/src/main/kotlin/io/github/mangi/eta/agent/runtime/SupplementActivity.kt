package io.github.mangi.eta.agent.runtime

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.components.EtaTextButton
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「插入对话」的轻量输入弹层。
 *
 * 由运行进度通知的第三个按钮拉起：不跳进 Eta 主界面，直接在通知上下文里补一句话，
 * 提交后交给 [AgentRuntimeService] 的 supplement 通道插进当前运行。
 *
 * 样式：整屏透明，底部一张圆角卡片；点卡片外区域关闭。
 */
internal class SupplementActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val conversationId = intent?.getStringExtra(AgentRuntimeService.EXTRA_SUPPLEMENT_CONVERSATION_ID)
            .orEmpty()
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "当前运行" }
        val appearance = runBlocking { AppearanceSettingsRepository.settings() }

        setContent {
            AgentAppTheme(
                appearance = appearance,
                applyInterfaceScale = false,
            ) {
                var text by remember { mutableStateOf("") }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.surface.copy(alpha = 0.35f))
                        .clickable { finish() },
                ) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .imePadding()
                            .padding(16.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MiuixTheme.colorScheme.surface)
                            // 吃掉卡片内的点击，避免误触背景关闭
                            .clickable(enabled = false) {}
                            .padding(20.dp),
                    ) {
                        Text(
                            text = "插入到「$title」",
                            style = MiuixTheme.textStyles.title2,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "补充内容会作为用户补充指令插入当前运行；不会打断正在执行的步骤。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(12.dp))
                        TextField(
                            value = text,
                            onValueChange = { text = it },
                            label = "补充一句…",
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            EtaTextButton(
                                text = "取消",
                                modifier = Modifier.weight(1f),
                                onClick = { finish() },
                            )
                            EtaTextButton(
                                text = "插入",
                                modifier = Modifier.weight(1f),
                                enabled = text.isNotBlank(),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                                onClick = {
                                    val payload = text.trim()
                                    if (payload.isEmpty()) return@EtaTextButton
                                    startService(
                                        Intent(this@SupplementActivity, AgentRuntimeService::class.java)
                                            .setAction(AgentRuntimeService.ACTION_SUPPLEMENT_RUN)
                                            .putExtra(AgentRuntimeService.EXTRA_SUPPLEMENT_TEXT, payload)
                                            .putExtra(
                                                AgentRuntimeService.EXTRA_SUPPLEMENT_CONVERSATION_ID,
                                                conversationId,
                                            ),
                                    )
                                    finish()
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "io.github.mangi.eta.agent.runtime.EXTRA_SUPPLEMENT_TITLE"
    }
}
