package com.pp.jiocall.data

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import com.pp.jiocall.sip.AccountStatus
import com.pp.jiocall.sip.RegState

@Immutable
data class RegistrationUiStatus(
    val label: String,
    val dotColor: Color,
    val chipBg: Color,
    val textColor: Color,
)

object GooglePhoneColors {
    val Pink = Color(0xFFE91E63)
    val Green = Color(0xFF4CAF50)
    val Yellow = Color(0xFFFFB300)
    val Coral = Color(0xFFEF5350)
    val Blue = Color(0xFF1E88E5)
    val Purple = Color(0xFF8E24AA)
    val Teal = Color(0xFF00897B)
    val CallGreen = Color(0xFF1E8E3E)
    val CallGreenDeep = Color(0xFF197A34)
    val DialpadBg = Color(0xFFEDEEF2)
    val KeyBg = Color(0xFFFFFFFF)
    val TextDark = Color(0xFF1A1C1E)
    val TextMuted = Color(0xFF5F6368)
    val HighlightBlue = Color(0xFF1A73E8)

    fun background(isDark: Boolean): Color = if (isDark) Color(0xFF0C0E13) else Color(0xFFFBFBFD)
    fun surface(isDark: Boolean): Color = if (isDark) Color(0xFF171A21) else Color(0xFFF1F3F8)
    fun surfaceElevated(isDark: Boolean): Color = if (isDark) Color(0xFF1E222B) else Color(0xFFFFFFFF)
    fun cardBorder(isDark: Boolean): Color = if (isDark) Color(0xFF282D38) else Color(0xFFE3E6ED)
    fun searchBarBg(isDark: Boolean): Color = if (isDark) Color(0xFF191C24) else Color(0xFFEFF2F7)
    fun keyBg(isDark: Boolean): Color = if (isDark) Color(0xFF232732) else Color(0xFFFFFFFF)
    fun keyPressedBg(isDark: Boolean): Color = if (isDark) Color(0xFF313847) else Color(0xFFD6E4FC)
    fun keyBorder(isDark: Boolean): Color = if (isDark) Color(0xFF2E3442) else Color(0xFFE2E6EF)
    fun textPrimary(isDark: Boolean): Color = if (isDark) Color(0xFFE9EBF0) else TextDark
    fun textSecondary(isDark: Boolean): Color = if (isDark) Color(0xFF9EA4B0) else TextMuted
    fun divider(isDark: Boolean): Color = if (isDark) Color(0xFF20242D) else Color(0xFFECEFF4)
    fun highlight(isDark: Boolean): Color = if (isDark) Color(0xFF9ECAFF) else HighlightBlue
    fun chipBg(isDark: Boolean): Color = if (isDark) Color(0xFF1E232D) else Color(0xFFECEFF5)
    fun chipSelectedBg(isDark: Boolean): Color = if (isDark) Color(0xFF1A385C) else Color(0xFFD6E4FF)
    fun menuBg(isDark: Boolean): Color = if (isDark) Color(0xFF1E222B) else Color.White
    fun fabContainer(isDark: Boolean): Color = if (isDark) Color(0xFF0E4D7A) else Color(0xFFD3E3FD)
    fun fabContent(isDark: Boolean): Color = if (isDark) Color(0xFFD9EBFF) else Color(0xFF041E49)
    fun bottomNavBg(isDark: Boolean): Color = if (isDark) Color(0xFF14171D) else Color(0xFFF3F5FA)
    fun bottomNavIndicator(isDark: Boolean): Color = if (isDark) Color(0xFF163D63) else Color(0xFFD3E3FD)
    fun bottomNavSelectedIcon(isDark: Boolean): Color = if (isDark) Color(0xFFD6E7FF) else Color(0xFF041E49)
    fun bottomNavUnselectedIcon(isDark: Boolean): Color = if (isDark) Color(0xFF8C929E) else Color(0xFF555961)

    private val Palette = listOf(
        Pink,    // A -> Amit Sharma: Pink
        Blue,    // B
        Teal,    // C
        Purple,  // D
        Coral,   // E
        Pink,    // F
        Green,   // G
        Green,   // H -> HELLO: Green
        Blue,    // I
        Yellow,  // J -> Jio Care: Yellow
        Teal,    // K
        Purple,  // L
        Pink,    // M
        Coral,   // N
        Green,   // O
        Coral,   // P -> Pooja Verma: Coral
        Purple,  // Q
        Teal,    // R
        Pink,    // S
        Blue,    // T
        Yellow,  // U
        Green,   // V
        Purple,  // W
        Teal,    // X
        Coral,   // Y
        Blue,    // Z
    )

    fun getAvatarColor(name: String): Color {
        val firstChar = name.firstOrNull { it.isLetter() }?.uppercaseChar() ?: 'A'
        val index = (firstChar - 'A').coerceIn(0, Palette.size - 1)
        return Palette[index]
    }

    fun getRegistrationUiStatus(
        accounts: List<Account>,
        statuses: Map<Long, AccountStatus>,
        isDark: Boolean = false,
    ): RegistrationUiStatus {
        val enabled = accounts.filter { it.enabled }
        return when {
            enabled.isEmpty() -> RegistrationUiStatus(
                label = "No account",
                dotColor = if (isDark) Color(0xFF8E9196) else Color(0xFF5F6368),
                chipBg = if (isDark) Color(0xFF282A2F) else Color(0xFFF1F3F4),
                textColor = if (isDark) Color(0xFFC4C7C5) else Color(0xFF5F6368),
            )
            enabled.any { statuses[it.id]?.state == RegState.REGISTERED } -> RegistrationUiStatus(
                label = "JioFiber",
                dotColor = if (isDark) Color(0xFF6DD58C) else Color(0xFF1E8E3E),
                chipBg = if (isDark) Color(0xFF143823) else Color(0xFFE6F4EA),
                textColor = if (isDark) Color(0xFFA8F5C4) else Color(0xFF137333),
            )
            enabled.any { statuses[it.id]?.state == RegState.REGISTERING } -> RegistrationUiStatus(
                label = "Connecting…",
                dotColor = if (isDark) Color(0xFFFFD566) else Color(0xFFF9AB00),
                chipBg = if (isDark) Color(0xFF3E2E04) else Color(0xFFFEF7E0),
                textColor = if (isDark) Color(0xFFFFE082) else Color(0xFFB06000),
            )
            else -> RegistrationUiStatus(
                label = "Offline",
                dotColor = if (isDark) Color(0xFFF28B82) else Color(0xFFD93025),
                chipBg = if (isDark) Color(0xFF3E1A1A) else Color(0xFFFCE8E6),
                textColor = if (isDark) Color(0xFFF6AEA9) else Color(0xFFC5221F),
            )
        }
    }
}

object SmartDial {

    val HighlightBlue = GooglePhoneColors.HighlightBlue

    fun charToT9(c: Char): Char? {
        return when (c.lowercaseChar()) {
            in 'a'..'c' -> '2'
            in 'd'..'f' -> '3'
            in 'g'..'i' -> '4'
            in 'j'..'l' -> '5'
            in 'm'..'o' -> '6'
            in 'p'..'s' -> '7'
            in 't'..'v' -> '8'
            in 'w'..'z' -> '9'
            in '0'..'9' -> c
            else -> null
        }
    }

    @Immutable
    data class ContactMatch(
        val contact: ContactItem,
        val highlightedName: AnnotatedString,
        val highlightedNumber: AnnotatedString,
        val isMatch: Boolean,
    )

    fun match(contact: ContactItem, query: String, isDark: Boolean = false): ContactMatch? {
        val displayNumber = PhoneUtils.formatForDisplay(contact.number).ifEmpty { contact.number }
        val formattedNumber = if (displayNumber.isNotEmpty()) "Mobile $displayNumber" else ""
        val cleanQuery = query.filter { it.isDigit() }
        if (cleanQuery.isEmpty()) {
            return ContactMatch(
                contact = contact,
                highlightedName = AnnotatedString(contact.name),
                highlightedNumber = AnnotatedString(formattedNumber),
                isMatch = true,
            )
        }

        val name = contact.name
        val highlightColor = GooglePhoneColors.highlight(isDark)

        // 1. Name T9 matching preserving original character offsets even across punctuation
        val nameMatchRanges = mutableListOf<IntRange>()
        var wordStart = -1
        for (i in 0..name.length) {
            val isSep = i == name.length || name[i].isWhitespace()
            if (!isSep && wordStart < 0) {
                wordStart = i
            } else if (isSep && wordStart >= 0) {
                val t9Chars = StringBuilder()
                val origIndices = ArrayList<Int>(i - wordStart)
                for (j in wordStart until i) {
                    val t9 = charToT9(name[j])
                    if (t9 != null) {
                        t9Chars.append(t9)
                        origIndices.add(j)
                    }
                }
                if (t9Chars.startsWith(cleanQuery) && origIndices.size >= cleanQuery.length) {
                    nameMatchRanges.add(origIndices[0]..origIndices[cleanQuery.length - 1])
                }
                wordStart = -1
            }
        }

        // 2. Number digits matching (supporting +, 00, and trunk 0 prefix variants)
        val numberMatchRanges = mutableListOf<IntRange>()
        val queriesToTry = PhoneUtils.searchDigitVariants(query).ifEmpty { listOf(cleanQuery) }

        for (qToMatch in queriesToTry) {
            var numSearchIdx = 0
            while (numSearchIdx < formattedNumber.length) {
                val nextDigitIdx = formattedNumber.indexOfFirst(numSearchIdx) { it.isDigit() }
                if (nextDigitIdx < 0) break

                var match = true
                var qIdx = 0
                var cur = nextDigitIdx
                var endCur = nextDigitIdx

                while (qIdx < qToMatch.length && cur < formattedNumber.length) {
                    val c = formattedNumber[cur]
                    if (c.isDigit()) {
                        if (c != qToMatch[qIdx]) {
                            match = false
                            break
                        }
                        qIdx++
                        endCur = cur + 1
                    }
                    cur++
                }

                if (match && qIdx == qToMatch.length) {
                    numberMatchRanges.add(nextDigitIdx until endCur)
                    numSearchIdx = endCur
                } else {
                    numSearchIdx = nextDigitIdx + 1
                }
            }
            if (numberMatchRanges.isNotEmpty()) break
        }

        val matched = nameMatchRanges.isNotEmpty() || numberMatchRanges.isNotEmpty()
        if (!matched) return null

        val annotatedName = buildAnnotatedString {
            append(name)
            for (range in nameMatchRanges) {
                val safeStart = range.first.coerceIn(0, name.length)
                val safeEnd = (range.last + 1).coerceIn(0, name.length)
                if (safeStart < safeEnd) {
                    addStyle(
                        SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold),
                        safeStart,
                        safeEnd,
                    )
                }
            }
        }

        val annotatedNumber = buildAnnotatedString {
            append(formattedNumber)
            for (range in numberMatchRanges) {
                val safeStart = range.first.coerceIn(0, formattedNumber.length)
                val safeEnd = (range.last + 1).coerceIn(0, formattedNumber.length)
                if (safeStart < safeEnd) {
                    addStyle(
                        SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold),
                        safeStart,
                        safeEnd,
                    )
                }
            }
        }

        return ContactMatch(
            contact = contact,
            highlightedName = annotatedName,
            highlightedNumber = annotatedNumber,
            isMatch = true,
        )
    }

    private fun String.indexOfFirst(startIndex: Int, predicate: (Char) -> Boolean): Int {
        for (i in startIndex until length) {
            if (predicate(this[i])) return i
        }
        return -1
    }

    fun search(contacts: List<ContactItem>, query: String, isDark: Boolean = false): List<ContactMatch> {
        val trimmed = query.trim()
        val cleanQuery = trimmed.filter { it.isDigit() }
        if (cleanQuery.isEmpty()) {
            val ordered = if (trimmed == "+") {
                contacts.sortedByDescending { it.number.trim().startsWith("+") }
            } else {
                contacts
            }
            return ordered.map { contact ->
                val displayNumber = PhoneUtils.formatForDisplay(contact.number).ifEmpty { contact.number }
                ContactMatch(
                    contact = contact,
                    highlightedName = AnnotatedString(contact.name),
                    highlightedNumber = AnnotatedString(if (displayNumber.isNotEmpty()) "Mobile $displayNumber" else ""),
                    isMatch = false,
                )
            }
        }

        return contacts.mapNotNull { match(it, trimmed, isDark) }
    }
}
