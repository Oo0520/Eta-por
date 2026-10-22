package io.github.mangi.eta.ui.markdown

/**
 * 行内公式的可读化：把 `$…$` 里的 LaTeX 换成人一眼能懂的 Unicode 文本。
 *
 * 为什么不做真排版：行内公式嵌在文字流里，WebView 跟不住基线与换行；改用位图虽然能排，
 * 但滚动和流式输出会抖，代价不对称。这里按「能看懂」优先——
 * 常见符号直接换 Unicode，上下标能映射就映射，复杂结构退成 `a/b`、`√x`、`^(…)` 这类写法。
 */
internal object LatexReadable {

    fun toReadable(tex: String): String {
        val converted = Reader(tex).readAll()
        return converted
            .replace(Regex("[ \t]{2,}"), " ")
            .trim()
            .ifEmpty { tex.trim() }
    }

    /**
     * 正文里的行内公式：把成对的 `$…$` 换成人能读的文本。
     *
     * 解析器没开 GFM 数学扩展，`$…$` 只是普通文本节点，所以在这里做文本级替换。
     * 规则保守：`$` 后不能是空白、闭合 `$` 前不能是空白、内容不跨行且不超过 200 字符；
     * 不满足就原样留着（价格 `$5`、没闭合的美元符都不会被误伤）。
     */
    fun inline(source: String): String {
        if ('$' !in source) return source
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val ch = source[index]
            if (ch == '\\' && index + 1 < source.length && source[index + 1] == '$') {
                out.append('$')
                index += 2
                continue
            }
            if (ch != '$') {
                out.append(ch)
                index++
                continue
            }
            // `$$` 是块级定界符，留给条级排版，不在这里处理。
            if (index + 1 < source.length && source[index + 1] == '$') {
                out.append("$$")
                index += 2
                continue
            }
            val close = findInlineClose(source, index + 1)
            if (close < 0) {
                out.append('$')
                index++
                continue
            }
            out.append(toReadable(source.substring(index + 1, close)))
            index = close + 1
        }
        return out.toString()
    }

    private fun findInlineClose(source: String, from: Int): Int {
        if (from >= source.length || source[from].isWhitespace()) return -1
        var index = from
        while (index < source.length) {
            val ch = source[index]
            if (ch == '\n' || index - from > 200) return -1
            if (ch == '\\') {
                index += 2
                continue
            }
            if (ch == '$') {
                return if (index > from && !source[index - 1].isWhitespace()) index else -1
            }
            index++
        }
        return -1
    }

    private class Reader(private val text: String) {
        private var index = 0

        fun readAll(): String = readGroup(until = null).toString()

        private fun readGroup(until: Char?): StringBuilder {
            val out = StringBuilder()
            while (index < text.length) {
                val ch = text[index]
                if (until != null && ch == until) {
                    index++
                    return out
                }
                when (ch) {
                    '\\' -> readCommand(out)
                    '^' -> {
                        index++
                        out.append(script(argument(), superscript = true))
                    }
                    '_' -> {
                        index++
                        out.append(script(argument(), superscript = false))
                    }
                    '{' -> {
                        index++
                        out.append(readGroup('}'))
                    }
                    // 单独出现的右括号只是分组残留，丢掉即可。
                    '}' -> index++
                    else -> {
                        out.append(ch)
                        index++
                    }
                }
            }
            return out
        }

        private fun readCommand(out: StringBuilder) {
            index++ // 吃掉反斜杠
            if (index >= text.length) return
            if (text[index].isLetter()) {
                val start = index
                while (index < text.length && text[index].isLetter()) index++
                val name = text.substring(start, index)
                when (name) {
                    "frac", "dfrac", "tfrac" -> out.append(fraction())
                    "sqrt" -> out.append(radical())
                    // 只影响样式的命令：效果交给外层排版，这里保留内容。
                    "text", "mathrm", "mathbf", "mathit", "mathsf", "mathtt",
                    "operatorname", "mbox", "hbox" -> out.append(argument())
                    "overline", "bar" -> out.append(argument()).append('\u0304')
                    "hat", "widehat" -> out.append(argument()).append('\u0302')
                    "vec" -> out.append(argument()).append('\u20d7')
                    "left", "right", "middle", "displaystyle", "textstyle", "scriptstyle",
                    "limits", "nolimits", "big", "Big", "bigg", "Bigg" -> Unit
                    "quad", "qquad", "enspace", "thinspace", "medspace" -> out.append(' ')
                    else -> out.append(SYMBOLS[name] ?: name)
                }
            } else {
                val ch = text[index]
                index++
                when (ch) {
                    ',', ';', ':', '!', ' ' -> out.append(' ')
                    '{' -> out.append('{')
                    '}' -> out.append('}')
                    '_' -> out.append('_')
                    '%' -> out.append('%')
                    '&' -> out.append('&')
                    '#' -> out.append('#')
                    '$' -> out.append('$')
                    '\\' -> out.append('\n')
                    else -> out.append(ch)
                }
            }
        }

        /** `\frac{a}{b}`：一行里能读的写法就是 a/b，参数含运算符时加括号。 */
        private fun fraction(): String {
            val numerator = wrap(argument())
            val denominator = wrap(argument())
            return if (denominator.isEmpty()) "$numerator/" else "$numerator/$denominator"
        }

        private fun wrap(value: String): String =
            if (value.any { it.isWhitespace() || it == '+' || it == '-' || it == '=' || it == '<' || it == '>' }) {
                "($value)"
            } else {
                value
            }

        /** `\sqrt{x}` → √x；`\sqrt[3]{x}` → 3√x。 */
        private fun radical(): String {
            var degree = ""
            if (index < text.length && text[index] == '[') {
                val close = text.indexOf(']', index)
                if (close > 0) {
                    degree = text.substring(index + 1, close).trim()
                    index = close + 1
                }
            }
            val body = argument()
            return when {
                body.isEmpty() -> "√"
                degree.isEmpty() -> "√$body"
                else -> "$degree√$body"
            }
        }

        /** 读一个参数：`{…}` 整组，或紧跟的单个字符。返回内容已转换。 */
        private fun argument(): String {
            while (index < text.length && (text[index] == ' ' || text[index] == '\t')) index++
            if (index >= text.length) return ""
            return if (text[index] == '{') {
                index++
                readGroup('}').toString()
            } else if (text[index] == '\\') {
                val sb = StringBuilder()
                readCommand(sb)
                sb.toString()
            } else {
                val ch = text[index]
                index++
                ch.toString()
            }
        }

        /** 上/下标：能全部映射成 Unicode 就用 Unicode，否则退成 `^(…)` / `_(…)`。 */
        private fun script(body: String, superscript: Boolean): String {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return ""
            val table = if (superscript) SUPERSCRIPT else SUBSCRIPT
            val out = StringBuilder()
            for (ch in trimmed) {
                val mapped = table[ch]
                if (mapped == null) {
                    return (if (superscript) "^(" else "_(") + trimmed + ")"
                }
                out.append(mapped)
            }
            return out.toString()
        }
    }

    private val SUPERSCRIPT = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴',
        '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '−' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'g' to 'ᵍ',
        'h' to 'ʰ', 'i' to 'ⁱ', 'j' to 'ʲ', 'k' to 'ᵏ', 'l' to 'ˡ', 'm' to 'ᵐ',
        'n' to 'ⁿ', 'o' to 'ᵒ', 'p' to 'ᵖ', 'r' to 'ʳ', 's' to 'ˢ', 't' to 'ᵗ',
        'u' to 'ᵘ', 'v' to 'ᵛ', 'w' to 'ʷ', 'x' to 'ˣ', 'y' to 'ʸ', 'z' to 'ᶻ',
        '*' to '*', '′' to '′', '∘' to '∘',
    )

    private val SUBSCRIPT = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄',
        '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '−' to '₋', '=' to '₌', '(' to '₍', ')' to '₎',
        'a' to 'ₐ', 'e' to 'ₑ', 'h' to 'ₕ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ',
        'l' to 'ₗ', 'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ',
        's' to 'ₛ', 't' to 'ₜ', 'u' to 'ᵤ', 'v' to 'ᵥ', 'x' to 'ₓ',
    )

    private val SYMBOLS = mapOf(
        // 希腊字母
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ",
        "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η",
        "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ",
        "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
        "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ",
        "varsigma" to "ς", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
        "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ",
        "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ", "Upsilon" to "Υ",
        "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        // 运算与关系
        "int" to "∫", "iint" to "∬", "iiint" to "∭", "oint" to "∮",
        "sum" to "∑", "prod" to "∏", "coprod" to "∐",
        "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
        "ne" to "≠", "neq" to "≠", "approx" to "≈", "equiv" to "≡",
        "sim" to "∼", "simeq" to "≃", "propto" to "∝", "ll" to "≪", "gg" to "≫",
        "times" to "×", "div" to "÷", "cdot" to "·", "pm" to "±", "mp" to "∓",
        "ast" to "∗", "star" to "⋆", "circ" to "∘", "bullet" to "•",
        "infty" to "∞", "partial" to "∂", "nabla" to "∇", "deg" to "°",
        "to" to "→", "rightarrow" to "→", "leftarrow" to "←",
        "leftrightarrow" to "↔", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "Leftrightarrow" to "⇔", "mapsto" to "↦", "implies" to "⇒",
        "iff" to "⇔", "uparrow" to "↑", "downarrow" to "↓",
        // 集合与逻辑
        "in" to "∈", "notin" to "∉", "ni" to "∋",
        "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
        "cup" to "∪", "cap" to "∩", "setminus" to "∖",
        "emptyset" to "∅", "varnothing" to "∅",
        "forall" to "∀", "exists" to "∃", "nexists" to "∄",
        "neg" to "¬", "land" to "∧", "lor" to "∨", "wedge" to "∧", "vee" to "∨",
        "therefore" to "∴", "because" to "∵",
        // 几何与其他
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "triangle" to "△",
        "ldots" to "…", "dots" to "…", "cdots" to "⋯", "vdots" to "⋮", "ddots" to "⋱",
        "prime" to "′", "ell" to "ℓ", "hbar" to "ℏ", "Re" to "ℜ", "Im" to "ℑ",
    )
}
