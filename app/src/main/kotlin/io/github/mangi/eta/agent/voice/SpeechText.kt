package io.github.mangi.eta.agent.voice

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

/**
 * 把一条回答变成"该读出来的正文"。
 *
 * 两道处理：先把 Markdown 结构剥掉（代码块、链接、表格分隔等），再做一次"能不能读"的过滤——
 * URL、文件路径、文件名、长数字串、哈希、秒级时间戳、系统残留符号一律丢掉，只留中英文正文和短数字。
 * 这些内容既没人要听，又特别费合成时间（引擎按字念一遍路径/哈希能有几十秒音频，
 * 而 ColorOS 引擎的「文件合成」通道只给 30 秒墙钟），是朗读被掐断的主要来源。
 */
internal object SpeechText {
    fun readable(markdown: String): String {
        val root = MarkdownParser(GFMFlavourDescriptor())
            .buildMarkdownTreeFromString(markdown)
        val plain = buildString {
            fun appendNode(node: ASTNode) {
                when (node.type) {
                    MarkdownElementTypes.CODE_FENCE,
                    MarkdownElementTypes.CODE_BLOCK,
                    MarkdownElementTypes.CODE_SPAN,
                    MarkdownElementTypes.IMAGE,
                    MarkdownElementTypes.LINK_DEFINITION,
                    MarkdownElementTypes.HTML_BLOCK,
                    MarkdownTokenTypes.HTML_TAG,
                    MarkdownTokenTypes.HORIZONTAL_RULE,
                    MarkdownTokenTypes.ATX_HEADER,
                    MarkdownTokenTypes.SETEXT_1,
                    MarkdownTokenTypes.SETEXT_2,
                    MarkdownTokenTypes.LIST_BULLET,
                    MarkdownTokenTypes.LIST_NUMBER,
                    MarkdownTokenTypes.BLOCK_QUOTE,
                    MarkdownTokenTypes.BACKTICK,
                    MarkdownTokenTypes.EMPH,
                    GFMTokenTypes.TILDE,
                    GFMTokenTypes.TABLE_SEPARATOR,
                    GFMTokenTypes.CHECK_BOX -> Unit
                    MarkdownElementTypes.INLINE_LINK,
                    MarkdownElementTypes.FULL_REFERENCE_LINK,
                    MarkdownElementTypes.SHORT_REFERENCE_LINK -> {
                        val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                            ?: node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_LABEL }
                        label?.children?.filterNot { it.type == MarkdownTokenTypes.LBRACKET ||
                            it.type == MarkdownTokenTypes.RBRACKET }?.forEach(::appendNode)
                    }
                    else -> if (node.children.isEmpty()) append(markdown, node.startOffset, node.endOffset)
                        else node.children.forEach(::appendNode)
                }
                if (node.type == GFMTokenTypes.CELL) append(' ')
            }
            appendNode(root)
        }.replace(Regex("[ \t]+"), " ").replace(Regex("\n{3,}"), "\n\n").trim()
        return dropUnspeakable(plain)
    }

    /** 只保住该读的字：先删掉"不能读"的靶子，再收拾删除后留下的碎标点。 */
    private fun dropUnspeakable(source: String): String {
        var text = source
        for (pattern in UNSPEAKABLE_PATTERNS) text = pattern.replace(text, " ")
        text = SECONDS_PATTERN.replace(text, "$1")
        text = text.replace(INLINE_SPACE_PATTERN, " ")
            .replace(SPACE_BEFORE_PUNCTUATION_PATTERN, "$1")
            .replace(REPEATED_PUNCTUATION_PATTERN, "$1")
            .replace(BRACKET_LEAD_SEPARATOR_PATTERN, "（")
            .replace(BRACKET_TAIL_SEPARATOR_PATTERN, "）")
            .replace(EMPTY_BRACKETS_PATTERN, "")
            .replace(NEWLINE_PATTERN, "。")
            .replace(PERIOD_RUN_PATTERN, "。")
            .replace(PERIOD_BEFORE_PUNCTUATION_PATTERN, "$1")
        return text.trim().trim('，', '。', '、', '；', '：', ' ', '\n')
    }

    /** 默认片段上限：网络合成走流式，没有引擎侧的墙钟限制，500 字只为读音换气。 */
    const val DEFAULT_LIMIT = 500

    fun chunks(text: String, limit: Int = DEFAULT_LIMIT): List<String> {
        require(limit >= 2)
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.isNotEmpty()) {
            var end = minOf(limit, remaining.length)
            if (end < remaining.length) {
                val boundary = remaining.take(end).indexOfLast { it in "。！？.!?；;\n" }
                if (boundary >= end / 2) end = boundary + 1
                if (remaining[end - 1].isHighSurrogate()) end--
            }
            result += remaining.substring(0, end)
            remaining = remaining.substring(end).trimStart()
        }
        return result
    }
}

private val URL_PATTERN = Regex("""(?:https?://|www\.)[^\s，。；）)]+""")

/** 以 / 或 ./ 或 ../ 或盘符开头的整段路径（读到空白或全角标点为止）。 */
private val ABSOLUTE_PATH_PATTERN = Regex("""(?:[A-Za-z]:)?(?:[\\/]|\.{1,2}[\\/])[A-Za-z0-9_.\-\\/]*""")

/** 夹在正文里的多段路径，例如 eta-port/prev-3.3.1-xxx.apk。 */
private val SEGMENTED_PATH_PATTERN = Regex("""[A-Za-z0-9_.\-]+(?:[\\/][A-Za-z0-9_.\-]+)+""")

private val FILE_PATTERN = Regex(
    """[A-Za-z0-9_.\-]+\.(?:apk|jks|json|zip|kt|kts|md|log|jar|so|db|txt|png|jpg|jpeg|xml|toml|gradle|properties|diff|tgz|gz|sh|py)\b""",
    RegexOption.IGNORE_CASE,
)

/** 毫秒/秒这一截不留，`17:18:58.514` 只读成 `17:18`。 */
private val SECONDS_PATTERN = Regex("""(\d{1,2}:\d{2}):\d{2}(?:[.,]\d+)?""")

private val LONG_HEX_PATTERN = Regex("""[0-9a-fA-F]{12,}""")

/** 版本号这类两段以上的点分数字。 */
private val VERSION_PATTERN = Regex("""\bv?\d+(?:\.\d+){2,}\b""")

private val LONG_DIGITS_PATTERN = Regex("""\d{5,}""")

private val TECHNICAL_LABEL_PATTERN = Regex(
    """\b(?:md5|sha-?256|versionCode|versionName|runId|utteranceId|index|len|uid|pid)\b[ \t]*[:=]?[ \t]*""",
    RegexOption.IGNORE_CASE,
)

/** 中英文正文、短数字和常用标点之外的符号（emoji、箭头、制表符残留等）都不读。 */
private val UNSUPPORTED_SYMBOL_PATTERN =
    Regex("""[^\p{IsHan}A-Za-z0-9，。、；：！？…—·「」『』（）()《》〈〉,.!?;:'"“”‘’\-\s%+]""")

private val UNSPEAKABLE_PATTERNS = listOf(
    URL_PATTERN,
    ABSOLUTE_PATH_PATTERN,
    SEGMENTED_PATH_PATTERN,
    FILE_PATTERN,
    LONG_HEX_PATTERN,
    VERSION_PATTERN,
    LONG_DIGITS_PATTERN,
    TECHNICAL_LABEL_PATTERN,
    UNSUPPORTED_SYMBOL_PATTERN,
)

private val INLINE_SPACE_PATTERN = Regex("""[ \t]+""")
private val SPACE_BEFORE_PUNCTUATION_PATTERN = Regex("""[ \t]+([，。、；：,;.!?])""")
private val REPEATED_PUNCTUATION_PATTERN = Regex("""([，。、；：！？])(?:[ \t]*\1)+""")
private val BRACKET_LEAD_SEPARATOR_PATTERN = Regex("""[（(]\s*[，,、；;]\s*""")
private val BRACKET_TAIL_SEPARATOR_PATTERN = Regex("""\s*[，,、；;]\s*[）)]""")
private val EMPTY_BRACKETS_PATTERN = Regex("""（\s*）|\(\s*\)|「\s*」|『\s*』""")
private val NEWLINE_PATTERN = Regex("""[ \t]*\n[ \t]*""")
private val PERIOD_RUN_PATTERN = Regex("""。+""")
private val PERIOD_BEFORE_PUNCTUATION_PATTERN = Regex("""。([，。、；：！？])""")
