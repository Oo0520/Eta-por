package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContextCompositionTest {

    /** 系统段、工具目录、对话段各归各位：按 CJK 1 字 / 其余 4 字符 1 token 折算。 */
    @Test
    fun estimate_splitsSystemToolsAndConversation() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "abcd"))
            .put(JSONObject().put("role", "system").put("content", "efgh"))
            .put(JSONObject().put("role", "user").put("content", "abcdefgh"))
            .put(JSONObject().put("role", "assistant").put("content", "12345678"))
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", "demo").put("description", "abcdefgh"),
            ),
        )

        val estimate = AgentContextComposition.estimate(messages, systemCount = 2, tools = tools)

        assertEquals(2, estimate.systemPromptTokens)
        assertEquals(4, estimate.conversationTokens)
        assertTrue(estimate.toolDefinitionTokens > 0)
        assertEquals(
            estimate.systemPromptTokens + estimate.toolDefinitionTokens + estimate.conversationTokens,
            estimate.total,
        )
        assertTrue(!estimate.isEmpty)
    }

    /** 中文按字计数，不被 4 字符规则吞掉。 */
    @Test
    fun estimate_countsCjkCharactersOneToOne() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "系统提示词"))
        val estimate = AgentContextComposition.estimate(messages, systemCount = 1, tools = JSONArray())
        assertEquals(5, estimate.systemPromptTokens)
        assertEquals(0, estimate.toolDefinitionTokens)
    }

    /** 不传段数时按「前导 role=system」判定系统段，请求侧因此不需要额外记账。 */
    @Test
    fun estimate_detectsLeadingSystemMessages() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "abcd"))
            .put(JSONObject().put("role", "system").put("content", "efgh"))
            .put(JSONObject().put("role", "user").put("content", "abcdefgh"))
        val estimate = AgentContextComposition.estimate(messages, JSONArray())
        assertEquals(2, estimate.systemPromptTokens)
        assertEquals(2, estimate.conversationTokens)
    }

    /** 没有任何内容时判定为空，面板据此不画分段条。 */
    @Test
    fun estimate_isEmptyWithoutContent() {
        val estimate = AgentContextComposition.estimate(JSONArray(), systemCount = 0, tools = JSONArray())
        assertTrue(estimate.isEmpty)
    }

    /** systemCount 超出实际条数时不能越界，全部按系统段计。 */
    @Test
    fun estimate_clampsSystemCount() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "abcd"))
        val estimate = AgentContextComposition.estimate(messages, systemCount = 9, tools = JSONArray())
        assertEquals(1, estimate.systemPromptTokens)
        assertEquals(0, estimate.conversationTokens)
    }
}
