package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.AgentCommand
import io.github.mangi.eta.ui.model.AgentCommandKind
import io.github.mangi.eta.ui.model.SkillItemUi

/**
 * 内置命令表。
 *
 * 只放「一句话就能说清、执行不经过模型」的会话控制命令；需要模型编排的能力走技能，
 * 不在这里堆。新增命令时记得同步 `AgentAppState.handleAgentCommand` 的分支。
 */
internal object AgentCommands {
    const val NEW = "new"
    const val COMPACT = "compact"
    const val MODEL = "model"
    const val EFFORT = "effort"
    const val STOP = "stop"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val RENAME = "rename"
    const val STATUS = "status"
    const val SKILLS = "skills"
    const val HELP = "help"

    val builtIns: List<AgentCommand> = listOf(
        AgentCommand(
            name = NEW,
            summary = "新建会话，输入框内容保留草稿",
            aliases = listOf("clear"),
        ),
        AgentCommand(
            name = COMPACT,
            summary = "压缩上下文，把历史收成摘要",
            requiresConfirmation = true,
        ),
        AgentCommand(
            name = MODEL,
            summary = "切换模型，不带参数时列出可用模型",
            argHint = "[模型名]",
        ),
        AgentCommand(
            name = EFFORT,
            summary = "设置思考档位",
            argHint = "[off|low|medium|high]",
        ),
        AgentCommand(
            name = STOP,
            summary = "停止正在执行的任务",
            requiresConfirmation = true,
        ),
        AgentCommand(
            name = PAUSE,
            summary = "暂停执行，保留现场",
        ),
        AgentCommand(
            name = RESUME,
            summary = "继续执行已暂停的任务",
        ),
        AgentCommand(
            name = RENAME,
            summary = "重命名当前会话",
            argHint = "[名称]",
        ),
        AgentCommand(
            name = STATUS,
            summary = "查看模型、思考档位与消息数",
        ),
        AgentCommand(
            name = SKILLS,
            summary = "列出可用技能命令，选中即注入该技能",
        ),
        AgentCommand(
            name = HELP,
            summary = "列出全部命令",
        ),
    )

    /**
     * 内置命令 + 已启用技能展开成的斜杠命令。
     *
     * 技能命令名就是技能 id（如 `self-improving-agent`）；和内置命令撞名时加 `skill-` 前缀，
     * 保证 `parseAgentCommand` 的解析结果始终唯一。
     */
    fun withSkills(skills: List<SkillItemUi>): List<AgentCommand> {
        val taken = builtIns.mapTo(mutableSetOf()) { it.name.lowercase() }
        val skillCommands = skills
            .asSequence()
            // 只要求「已安装」：开关关掉的技能也能被斜杠命令显式调用，面板里会标「未启用」。
            .filter { it.installed && it.id.isNotBlank() }
            .distinctBy { it.id }
            .sortedBy { it.id.lowercase() }
            .map { skill ->
                var name = skill.id.trim()
                while (name.lowercase() in taken) name = "skill-$name"
                taken += name.lowercase()
                AgentCommand(
                    name = name,
                    // 描述给全：面板可滚动，靠省略号截断等于让用户猜。
                    summary = skill.description
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifBlank { skill.name },
                    kind = AgentCommandKind.Skill,
                    skillId = skill.id,
                    waitsForArgs = true,
                    skillEnabled = skill.enabled,
                )
            }
            .toList()
        return builtIns + skillCommands
    }
}
