package io.github.mangi.eta.agent.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCustomizationTest {

    @Test
    fun versionIncrementsOnlyWhenContentChanges() {
        val empty = PromptCustomization()
        val edited = empty.edit(baseSystemPrompt = "你是测试助手")
        assertEquals(1, edited.version)

        val sameContent = edited.edit(baseSystemPrompt = "你是测试助手")
        assertEquals("内容没变不应该升版本", 1, sameContent.version)

        val enabledOnly = edited.edit(enabled = true)
        assertEquals("只改开关不应该升版本", 1, enabledOnly.version)

        val nextContent = enabledOnly.edit(appendInstructions = "回答简短")
        assertEquals(2, nextContent.version)
    }

    @Test
    fun revisionFollowsContent() {
        val first = PromptCustomization().edit(baseSystemPrompt = "A")
        val second = first.edit(baseSystemPrompt = "B")
        assertEquals(PromptCustomization.REVISION_LENGTH, first.revision.length)
        assertNotEquals(first.revision, second.revision)
        assertEquals("同一内容哈希稳定", first.revision, first.copy(version = 9).revision)
    }

    @Test
    fun historyIsCappedAndRestoreKeepsVersionMovingForward() {
        var profile = PromptCustomization()
        repeat(7) { index -> profile = profile.edit(baseSystemPrompt = "v$index") }
        assertEquals(PromptCustomization.HISTORY_LIMIT, profile.history.size)
        assertEquals(7, profile.version)

        val oldest = profile.history.last()
        val restored = profile.restore(oldest)
        assertEquals("回滚后版本号继续向前", 8, restored.version)
        assertEquals(oldest.baseSystemPrompt, restored.baseSystemPrompt)
    }

    @Test
    fun jsonRoundTripKeepsEveryField() {
        val profile = PromptCustomization()
            .edit(
                enabled = true,
                mode = PromptCustomizationMode.REPLACE,
                baseSystemPrompt = "身份",
                personaName = "小满",
                personaStyle = "简洁",
                appendInstructions = "不要编造",
            )
        val decoded = PromptCustomization.decode(profile.toJson().toString())
        assertEquals(profile, decoded)
        assertEquals(profile.revision, decoded.revision)
        assertEquals(profile.history.size, decoded.history.size)
    }

    @Test
    fun activeRequiresEnabledAndContent() {
        val content = PromptCustomization().edit(baseSystemPrompt = "身份")
        assertTrue("默认未启用时不生效", !content.active)
        assertTrue(content.edit(enabled = true).active)
        assertTrue("空内容即使启用也不生效", !PromptCustomization(enabled = true).active)
    }

    @Test
    fun brokenJsonFallsBackToDefaults() {
        val decoded = PromptCustomization.decode("{not json")
        assertEquals(PromptCustomization(), decoded)
    }
}
