package com.pp.jiocall.sip

/**
 * Advanced Linksys/Cisco dial plan engine.
 * Directly ports the algorithm from `crates/rustone-core/src/dialplan.rs`.
 *
 * Rules separated by `|` (surrounding spaces and brackets ignored).
 * In a rule:
 * - `x` or `X` stands for any digit [0-9]
 * - `.` repeats what comes before it
 * - `[2-9]` and other character classes work
 * - `<match:replace>` swaps the part matching `match` for `replace`
 *   (e.g., `<:91>` prepends 91, `<0:>` strips leading 0).
 */
object DialPlan {

    fun formatNumber(number: String, prefix: String, plan: String): String? {
        val trimmed = number.trim()
        if (trimmed.contains('<') && trimmed.contains('>')) {
            return trimmed
        }
        var formatted = trimmed
        if (isPstn(trimmed)) {
            formatted = formatted.filter { !it.isWhitespace() && it !in ".-()/" }
            if (prefix.isNotEmpty() && formatted.length > 3) {
                if (formatted.startsWith("+")) {
                    formatted = formatted.removePrefix("+")
                }
                formatted = "$prefix$formatted"
            }
        }

        val cleanPlan = plan.trim().trim('(', ')', ' ')
        if (cleanPlan.isEmpty()) {
            return formatted
        }

        for (rule in cleanPlan.split('|').filter { it.isNotBlank() }) {
            val result = applyRule(rule.trim(), formatted)
            if (result != null) {
                return result
            }
        }
        // If a dial plan was specified but no rule matched, the call is blocked per dialplan semantics
        return null
    }

    private fun isPstn(number: String): Boolean {
        return number.all { it.isDigit() || it.isWhitespace() || it in "+*#.-()/" }
    }

    private fun applyRule(rule: String, number: String): String? {
        val pattern = StringBuilder("^")
        val replaces = mutableListOf<String>()
        var i = 0
        while (i < rule.length) {
            val c = rule[i]
            if (c != '<') {
                pattern.append(translate(c))
                i++
                continue
            }
            val end = rule.indexOf('>', i + 1)
            if (end < 0) {
                pattern.append(translate(c))
                i++
                continue
            }
            val group = rule.substring(i + 1, end)
            val colon = group.indexOf(':')
            if (colon >= 0) {
                val matchedPart = group.substring(0, colon)
                val replacement = group.substring(colon + 1)
                pattern.append('(')
                for (mc in matchedPart) pattern.append(translate(mc))
                pattern.append(')')
                replaces.add(replacement)
            } else {
                for (gc in group) pattern.append(translate(gc))
            }
            i = end + 1
        }
        pattern.append('$')

        val regex = runCatching { Regex(pattern.toString(), RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        val captures = regex.find(number) ?: return null
        val out = StringBuilder()
        var last = 0
        for ((idx, replacement) in replaces.withIndex()) {
            val grp = captures.groups[idx + 1] ?: continue
            out.append(number, last, grp.range.first)
            out.append(replacement)
            last = grp.range.last + 1
        }
        out.append(number, last, number.length)
        return out.toString()
    }

    private fun translate(c: Char): String = when (c) {
        'x', 'X' -> "[0-9]"
        '.' -> "*"
        '*', '+', '?', '(', ')', '{', '}' -> "\\$c"
        else -> c.toString()
    }
}
