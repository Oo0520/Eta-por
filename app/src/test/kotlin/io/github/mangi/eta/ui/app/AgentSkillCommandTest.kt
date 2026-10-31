package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentSkillCommandPromptCodec
import io.github.mangi.eta.ui.model.AgentCommandInput
import io.github.mangi.eta.ui.model.AgentCommandKind
import io.github.mangi.eta.ui.model.AgentPaletteBadge
import io.github.mangi.eta.ui.model.AgentPalettePayload
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.filterAgentCommands
import io.github.mangi.eta.ui.model.commandPaletteEntries
import io.github.mangi.eta.ui.model.parseAgentCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 斜杠命令调用技能的纯逻辑：命令表展开、解析、注入块与展示剥离。 */
class AgentSkillCommandTest {

    private fun skill(
        id: String,
        description: String = "把重复流程记成技能",
        enabled: Boolean = true,
        installed: Boolean = true,
    ) = SkillItemUi(
        id = id,
        name = id,
        description = description,
        source = "user",
        enabled = enabled,
        installed = installed,
        capabilities = emptyList(),
    )

    @Test
    fun `skills expand into slash commands`() {
        val commands = AgentCommands.withSkills(listOf(skill("self-improving-agent")))
        val command = commands.first { it.name == "self-improving-agent" }
        assertEquals(AgentCommandKind.Skill, command.kind)
        assertEquals("self-improving-agent", command.skillId)
        assertTrue(commands.any { it.name == AgentCommands.NEW })
    }

    @Test
    fun `disabled skills stay callable and are marked`() {
        val commands = AgentCommands.withSkills(
            listOf(skill("off", enabled = false), skill("ghost", installed = false)),
        )
        val off = commands.first { it.skillId == "off" }
        assertTrue(!off.skillEnabled)
        assertTrue(off.waitsForArgs)
        assertTrue(commands.none { it.skillId == "ghost" })
    }

    @Test
    fun `skill id clashing with a built in gets a prefix`() {
        val commands = AgentCommands.withSkills(listOf(skill("help")))
        val command = commands.first { it.skillId == "help" }
        assertEquals("skill-help", command.name)
    }

    @Test
    fun `palette entries carry badge and payload`() {
        val commands = AgentCommands.withSkills(
            listOf(skill("on-skill"), skill("off-skill", enabled = false)),
        )
        val entries = commandPaletteEntries(commands)
        val disabled = entries.first { it.key == "cmd:off-skill" }
        assertEquals("/off-skill", disabled.title)
        assertEquals(AgentPaletteBadge.SkillDisabled, disabled.badge)
        assertTrue(disabled.payload is AgentPalettePayload.Run)
        assertEquals(AgentPaletteBadge.Skill, entries.first { it.key == "cmd:on-skill" }.badge)
        assertNull(entries.first { it.key == "cmd:new" }.badge)
    }

    @Test
    fun `slash command with arguments resolves to the skill command`() {
        val commands = AgentCommands.withSkills(listOf(skill("self-improving-agent")))
        val parsed = parseAgentCommand("/self-improving-agent 记住这次的坑", commands)
        assertTrue(parsed is AgentCommandInput.Run)
        parsed as AgentCommandInput.Run
        assertEquals("self-improving-agent", parsed.command.skillId)
        assertEquals("记住这次的坑", parsed.args)
    }

    @Test
    fun `empty slash lists every command with built ins first`() {
        val commands = AgentCommands.withSkills(listOf(skill("aaa-skill"), skill("zzz-skill")))
        val listed = filterAgentCommands("/", commands)
        assertEquals(commands.size, listed.size)
        assertEquals(
            AgentCommands.builtIns.size,
            listed.takeWhile { it.kind == AgentCommandKind.BuiltIn }.size,
        )
        assertEquals("aaa-skill", listed[AgentCommands.builtIns.size].name)
    }

    @Test
    fun `skill block round trips and is stripped from the visible prompt`() {
        val content = AgentSkillCommandPromptCodec.format(
            skillId = "self-improving-agent",
            skillName = "self improving agent",
            skillPath = "/data/skills/self-improving-agent/SKILL.md",
            body = "# 步骤\n1. 记录失败",
            truncated = false,
            request = "记住这次的坑",
        )
        assertTrue(content.contains("<skill_command"))
        assertEquals("记住这次的坑", AgentSkillCommandPromptCodec.strip(content))
    }

    @Test
    fun `skill header is readable from the message content`() {
        val content = AgentSkillCommandPromptCodec.format(
            skillId = "demo",
            skillName = "demo skill",
            skillPath = "/data/skills/demo/SKILL.md",
            body = "正文",
            truncated = false,
            request = "跑一下",
        )
        val header = AgentSkillCommandPromptCodec.parseHeader(content)
        assertEquals("demo", header?.skillId)
        assertEquals("demo skill", header?.skillName)
        assertNull(AgentSkillCommandPromptCodec.parseHeader("只是一句普通消息"))
    }

    @Test
    fun `history keeps only a compact skill marker`() {
        val content = AgentSkillCommandPromptCodec.format(
            skillId = "demo",
            skillName = "demo",
            skillPath = "/data/skills/demo/SKILL.md",
            body = "很长的正文".repeat(400),
            truncated = false,
            request = "跑一下",
        )
        val history = AgentSkillCommandPromptCodec.compactForHistory(content)
        assertTrue(history.startsWith("<skill_command id=\"demo\""))
        assertTrue(history.length < 400)
        assertTrue(history.endsWith("跑一下"))
        assertTrue(!history.contains("很长的正文"))
    }

    @Test
    fun `file reference parse also hides the skill block`() {
        val request = AgentSkillCommandPromptCodec.format(
            skillId = "demo",
            skillName = "demo",
            skillPath = "/data/skills/demo/SKILL.md",
            body = "正文",
            truncated = false,
            request = "看下这个文件",
        )
        val content = AgentFileReferencePromptCodec.format(
            request = request,
            references = listOf(
                AgentFileReference(
                    displayName = "a.txt",
                    absolutePath = "/sdcard/a.txt",
                    kind = AgentFileReferenceKind.File,
                ),
            ),
        )
        val parsed = AgentFileReferencePromptCodec.parse(content)
        assertEquals("看下这个文件", parsed.request)
        assertEquals("/sdcard/a.txt", parsed.references.single().absolutePath)
    }
}
