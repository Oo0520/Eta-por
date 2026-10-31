package io.github.mangi.eta.agent.display.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmRiskDetectorTest {

    @Test
    fun moneyTapCarriesAmountAndPayee() {
        val draft = ConfirmRiskDetector.inspect(
            target = "立即支付",
            texts = listOf("订单确认", "收款方 星海便利店", "应付 ¥28.50", "立即支付"),
        )

        assertEquals(ConfirmRisk.MONEY, draft?.risk)
        assertTrue(draft!!.fields.any { it.label == "金额" && it.value.contains("28.50") })
        assertTrue(draft.fields.any { it.label == "收款方" })
    }

    @Test
    fun smsSendIsIrreversible() {
        val draft = ConfirmRiskDetector.inspect(
            target = "发送",
            texts = listOf("编辑短信", "晚点到家", "发送"),
        )

        assertEquals(ConfirmRisk.IRREVERSIBLE, draft?.risk)
    }

    @Test
    fun verificationCodeIsPrivacy() {
        val draft = ConfirmRiskDetector.inspect(
            target = "获取验证码",
            texts = listOf("登录", "手机号 13800001111", "获取验证码"),
        )

        assertEquals(ConfirmRisk.PRIVACY, draft?.risk)
        assertTrue(draft!!.fields.any { it.label == "手机号" })
    }

    @Test
    fun harmlessTapIsNotBlocked() {
        assertNull(ConfirmRiskDetector.inspect(target = "下一页", texts = listOf("商品列表", "下一页")))
    }
}
