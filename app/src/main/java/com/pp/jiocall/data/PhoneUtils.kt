package com.pp.jiocall.data

import com.google.i18n.phonenumbers.PhoneNumberUtil
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber
import java.util.Collections

/**
 * Phone number normalization, formatting, and matching modeled after Google Phone (AOSP Dialer +
 * libphonenumber) and Fossify Phone.
 *
 * Designed as pure JVM/Kotlin logic so unit tests and Android runtime produce identical, deterministic
 * results regardless of emulator/system locale defaults.
 */
object PhoneUtils {

    const val DEFAULT_COUNTRY_ISO = "IN"

    private const val CACHE_LIMIT = 512

    private val phoneUtil: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }
    private val BIDI_REGEX = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    private val ASCII_VANITY_REGEX = Regex("^[0-9A-Za-z+*#()\\-\\s.\\u00A0]+$")
    private val TEL_PREFIX_REGEX = Regex("^tel:", RegexOption.IGNORE_CASE)
    private val NON_DIAL_REGEX = Regex("[^0-9+*#]")
    private val WHITESPACE_RUN_REGEX = Regex("\\s+")

    private fun newLruMap(): MutableMap<String, String> =
        Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(256, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                    size > CACHE_LIMIT
            },
        )

    private val cleanCache = newLruMap()
    private val dialCache = newLruMap()
    private val displayCache = newLruMap()

    /** Removes invisible directional characters, `tel:` prefixes, and outer whitespace. */
    fun stripBidiAndTrim(raw: String): String =
        raw.replace(BIDI_REGEX, "").trim().replace(TEL_PREFIX_REGEX, "").trim()

    fun isSipUri(raw: String): Boolean =
        raw.contains('@') ||
            raw.startsWith("sip:", ignoreCase = true) ||
            raw.startsWith("sips:", ignoreCase = true)

    /**
     * Converts dialable input (including vanity letters like `1-800-FLOWERS`, spaces, NBSP,
     * parentheses, dashes) into a compact string containing `[0-9+*#]`.
     *
     * Preserves leading `+` (and USSD prefixes `*...#`) while rejecting stray mid-number `+` signs.
     */
    fun cleanDialInput(number: String): String {
        if (number.isEmpty()) return ""
        cleanCache[number]?.let { return it }
        val result = computeCleanDialInput(number)
        if (number.length <= 64) cleanCache[number] = result
        return result
    }

    private fun computeCleanDialInput(number: String): String {
        val trimmed = stripBidiAndTrim(number)
        if (trimmed.isEmpty()) return ""
        if (isSipUri(trimmed)) return trimmed

        val digitCount = trimmed.count { it.isDigit() }
        val expanded = if (digitCount >= 3 && trimmed.any { it.isLetter() } && ASCII_VANITY_REGEX.matches(trimmed)) {
            PhoneNumberUtil.convertAlphaCharactersInNumber(trimmed)
        } else {
            trimmed
        }

        val rawKeep = expanded.replace(NON_DIAL_REGEX, "")
        if (rawKeep.isEmpty()) return ""
        if (rawKeep.startsWith("*") || rawKeep.startsWith("#")) return rawKeep

        // Keep at most one leading '+' sign.
        val hasLeadingPlus = rawKeep.startsWith("+")
        val withoutPluses = rawKeep.replace("+", "")
        return if (hasLeadingPlus) "+$withoutPluses" else withoutPluses
    }

    /** Extracts last 10 digits from a number, ignoring formatting symbols and country prefixes. */
    fun extractLast10Digits(number: String): String {
        val digits = cleanDialInput(number).filter { it.isDigit() }
        return if (digits.length >= 10) digits.takeLast(10) else digits
    }

    /** Fast hash bucket key for grouping candidate phone numbers before exact [matches] check. */
    fun lookupBucket(number: String): String {
        val clean = cleanDialInput(number)
        if (clean.isEmpty()) return ""
        if (isSipUri(clean) || clean.startsWith("*") || clean.startsWith("#")) {
            return clean.substringBefore('@').lowercase()
        }
        val digits = clean.filter { it.isDigit() }
        return when {
            digits.isEmpty() -> clean
            digits.length <= 5 -> "S:$clean"
            digits.length >= 10 -> digits.takeLast(10)
            else -> digits
        }
    }

    private fun parseSafe(cleaned: String, region: String): PhoneNumber? {
        if (cleaned.isEmpty() || cleaned.startsWith("*") || cleaned.startsWith("#")) return null
        return runCatching { phoneUtil.parseAndKeepRawInput(cleaned, region) }.getOrNull()
    }

    /**
     * Normalizes a number before placing a SIP call:
     * - Preserves SIP URIs, USSD codes (`*...#`), and short/emergency codes (`<= 5` chars) intact.
     * - Strips trunk `0` after country code (e.g. `+91 09876543210` -> `+919876543210`).
     * - Converts IDD `00...` international prefixes (e.g. `0014155550199` -> `+14155550199`,
     *   `00917060829556` -> `+917060829556`) while never altering `000` short codes.
     * - Normalizes national numbers (`9876543210`, `09876543210`, `919876543210`, `01123456789`)
     *   to canonical E.164 (`+91...`) using libphonenumber, while keeping national toll-free
     *   (`1800...` / `1860...`) untouched when dialed without an explicit `+`/`00` prefix.
     * - Preserves any explicit international `+` prefix (`+1...`, `+44...`, etc.).
     */
    fun normalizeForDialing(
        number: String,
        defaultRegion: String = DEFAULT_COUNTRY_ISO,
    ): String {
        if (number.isEmpty()) return ""
        val cacheKey = "$defaultRegion|$number"
        dialCache[cacheKey]?.let { return it }
        val result = computeNormalizeForDialing(number, defaultRegion)
        if (number.length <= 64) dialCache[cacheKey] = result
        return result
    }

    private fun computeNormalizeForDialing(
        number: String,
        defaultRegion: String,
    ): String {
        val trimmed = stripBidiAndTrim(number)
        if (trimmed.isEmpty()) return ""
        if (isSipUri(trimmed)) return trimmed

        val sanitized = cleanDialInput(trimmed)
        if (sanitized.isEmpty()) return ""

        // Keep USSD (* / #) and short/emergency codes untouched (never add +91 to 198, 112, etc.).
        if (sanitized.startsWith("*") || sanitized.startsWith("#") || sanitized.length <= 5) {
            return sanitized
        }

        val parsed = parseSafe(sanitized, defaultRegion)
        if (parsed != null) {
            val numberType = phoneUtil.getNumberType(parsed)
            val isValid = phoneUtil.isValidNumber(parsed)
            val isPossible = phoneUtil.isPossibleNumber(parsed)
            val hasExplicitIntl = sanitized.startsWith("+") || sanitized.startsWith("00")

            if (isValid || isPossible) {
                val keepNationalServiceNumber = !hasExplicitIntl && (
                    numberType == PhoneNumberUtil.PhoneNumberType.TOLL_FREE ||
                        numberType == PhoneNumberUtil.PhoneNumberType.SHARED_COST ||
                        numberType == PhoneNumberUtil.PhoneNumberType.UAN ||
                        numberType == PhoneNumberUtil.PhoneNumberType.PREMIUM_RATE
                    )
                if (keepNationalServiceNumber) {
                    return sanitized
                }
                return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
        }

        // Fallback if an IDD 00-prefixed international number failed region parse directly.
        if (sanitized.startsWith("00") && !sanitized.startsWith("000") && sanitized.length >= 9) {
            val e164Candidate = "+" + sanitized.drop(2)
            val parsedE164 = parseSafe(e164Candidate, defaultRegion)
            if (parsedE164 != null && (phoneUtil.isValidNumber(parsedE164) || phoneUtil.isPossibleNumber(parsedE164))) {
                return phoneUtil.format(parsedE164, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
            return e164Candidate
        }

        // Handle explicit +91 with extra trunk 0 on Indian numbers (e.g. +9109876543210).
        if (sanitized.startsWith("+910") && sanitized.length >= 13) {
            val withoutZero = "+91" + sanitized.drop(4)
            val reParsed = parseSafe(withoutZero, defaultRegion)
            if (reParsed != null && phoneUtil.isPossibleNumber(reParsed)) {
                return phoneUtil.format(reParsed, PhoneNumberUtil.PhoneNumberFormat.E164)
            }
            return withoutZero
        }

        return sanitized
    }

    /**
     * Formats a stored or remote phone number cleanly for display (Contacts, Recents, Call Screen,
     * SmartDial subtitles):
     * - Short codes (`<= 5` digits without explicit `+`) lose accidental Android Contacts spacing
     *   (e.g. `"1 98"` -> `"198"`).
     * - USSD codes (`*123#`) and SIP URIs remain untouched.
     * - Explicit E.164 (`+91...`, `+1...`, `+44...`) formats with international grouping
     *   (`+91 92595 18300`, `+1 415-555-0199`).
     * - National numbers keep user's local/trunk choice cleanly spaced (`98765 43210`, `098765 43210`).
     */
    fun formatForDisplay(
        number: String,
        defaultRegion: String = DEFAULT_COUNTRY_ISO,
    ): String {
        if (number.isEmpty()) return ""
        val cacheKey = "$defaultRegion|$number"
        displayCache[cacheKey]?.let { return it }
        val result = computeFormatForDisplay(number, defaultRegion)
        if (number.length <= 64) displayCache[cacheKey] = result
        return result
    }

    private fun computeFormatForDisplay(
        number: String,
        defaultRegion: String,
    ): String {
        val trimmed = stripBidiAndTrim(number)
        if (trimmed.isEmpty()) return ""
        if (isSipUri(trimmed)) return trimmed

        val cleaned = cleanDialInput(trimmed)
        if (cleaned.isEmpty()) return trimmed
        if (cleaned.startsWith("*") || cleaned.startsWith("#")) return cleaned

        val digits = cleaned.filter { it.isDigit() }
        if (digits.length <= 5) {
            return if (cleaned.startsWith("+") && trimmed.startsWith("+") && digits.length >= 3) {
                trimmed.replace(WHITESPACE_RUN_REGEX, " ")
            } else {
                cleaned
            }
        }

        val parsed = parseSafe(cleaned, defaultRegion)
        if (parsed != null && phoneUtil.isPossibleNumber(parsed)) {
            if (cleaned.startsWith("+")) {
                return phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
            }
            if (cleaned.startsWith("00")) {
                return phoneUtil.formatInOriginalFormat(parsed, defaultRegion)
            }
            val national = phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
            if (cleaned.startsWith("0") || national == phoneUtil.formatInOriginalFormat(parsed, defaultRegion)) {
                return national
            }
            // Strip synthetic national trunk '0' if the user entered digits without leading '0'.
            val intl = phoneUtil.format(parsed, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL)
            val prefixToken = "+${parsed.countryCode} "
            return if (intl.startsWith(prefixToken)) intl.removePrefix(prefixToken) else national
        }

        return trimmed.replace(WHITESPACE_RUN_REGEX, " ")
    }

    /**
     * Live As-You-Type formatter for the Dialpad input display, avoiding premature grouping
     * on short service codes (`198`) and USSD sequences (`*123#`).
     */
    fun formatAsYouType(
        dialpadInput: String,
        defaultRegion: String = DEFAULT_COUNTRY_ISO,
    ): String {
        val raw = stripBidiAndTrim(dialpadInput)
        if (raw.isEmpty()) return ""
        if (isSipUri(raw) || raw.startsWith("*") || raw.startsWith("#")) return raw

        val digitCount = raw.count { it.isDigit() }
        if (!raw.startsWith("+") && !raw.startsWith("0") && digitCount < 6) {
            return raw
        }

        val formatter = phoneUtil.getAsYouTypeFormatter(defaultRegion)
        var formatted = ""
        for (ch in raw) {
            if (ch.isDigit() || ch == '+') {
                formatted = formatter.inputDigit(ch)
            }
        }
        return formatted.ifEmpty { raw }
    }

    /**
     * Generates normalized prefix-stripped search candidates for Dialpad T9 / Contact search
     * (so typing `+919876` or `09876` still matches a contact saved locally as `9876543210`).
     */
    fun searchDigitVariants(
        query: String,
        defaultRegion: String = DEFAULT_COUNTRY_ISO,
    ): List<String> {
        val clean = cleanDialInput(query)
        val digits = clean.filter { it.isDigit() }
        if (digits.isEmpty()) return emptyList()

        val variants = linkedSetOf(digits)
        val parsed = parseSafe(clean, defaultRegion)
        if (parsed != null && clean.startsWith("+")) {
            val ccStr = parsed.countryCode.toString()
            if (digits.startsWith(ccStr) && digits.length > ccStr.length) {
                variants.add(digits.removePrefix(ccStr).trimStart('0').ifEmpty { digits })
            }
        } else if (clean.startsWith("+")) {
            // Partial +<CC>... prefix while still typing
            val defaultCc = phoneUtil.getCountryCodeForRegion(defaultRegion).toString()
            if (digits.startsWith(defaultCc) && digits.length > defaultCc.length) {
                variants.add(digits.removePrefix(defaultCc))
            }
        }
        if (digits.startsWith("00") && digits.length > 2) {
            val afterIdd = digits.drop(2)
            variants.add(afterIdd)
            val defaultCc = phoneUtil.getCountryCodeForRegion(defaultRegion).toString()
            if (afterIdd.startsWith(defaultCc) && afterIdd.length > defaultCc.length) {
                variants.add(afterIdd.removePrefix(defaultCc))
            }
        } else if (digits.startsWith("0") && digits.length > 1) {
            variants.add(digits.drop(1))
        }
        if (digits.length > 10) {
            variants.add(digits.takeLast(10))
        }
        return variants.toList()
    }

    /**
     * Compares two phone numbers accounting for formatting differences, trunk `0`, IDD `00`,
     * `+91`/country codes, short codes (`198`), USSD, and cross-country non-collision.
     */
    fun matches(
        numA: String,
        numB: String,
        defaultRegion: String = DEFAULT_COUNTRY_ISO,
    ): Boolean {
        val rawA = stripBidiAndTrim(numA)
        val rawB = stripBidiAndTrim(numB)
        if (rawA.isEmpty() || rawB.isEmpty()) return false

        if (isSipUri(rawA) || isSipUri(rawB)) {
            if (isSipUri(rawA) && isSipUri(rawB)) {
                val uA = rawA.removePrefix("sip:").removePrefix("sips:").substringBefore(';').lowercase()
                val uB = rawB.removePrefix("sip:").removePrefix("sips:").substringBefore(';').lowercase()
                return uA == uB
            }
            val nonSip = if (isSipUri(rawA)) rawB else rawA
            val sipUser = (if (isSipUri(rawA)) rawA else rawB)
                .removePrefix("sip:")
                .removePrefix("sips:")
                .substringBefore('@')
            return matches(sipUser, nonSip, defaultRegion)
        }

        val cleanA = cleanDialInput(rawA)
        val cleanB = cleanDialInput(rawB)
        if (cleanA.isEmpty() || cleanB.isEmpty()) return false

        if (cleanA.startsWith("*") || cleanA.startsWith("#") || cleanB.startsWith("*") || cleanB.startsWith("#")) {
            return cleanA == cleanB
        }

        val digitsA = cleanA.filter { it.isDigit() }
        val digitsB = cleanB.filter { it.isDigit() }
        if (digitsA.isEmpty() || digitsB.isEmpty()) return cleanA == cleanB

        // Short codes (e.g. 198, 112) must not mix with country-prefixed strings (+91 198)
        // or falsely substring-match longer numbers.
        if (digitsA.length <= 5 || digitsB.length <= 5) {
            return cleanA == cleanB
        }

        val parsedA = parseSafe(cleanA, defaultRegion)
        val parsedB = parseSafe(cleanB, defaultRegion)

        if (parsedA != null && parsedB != null) {
            val explicitA = parsedA.hasCountryCodeSource() &&
                parsedA.countryCodeSource != PhoneNumber.CountryCodeSource.FROM_DEFAULT_COUNTRY
            val explicitB = parsedB.hasCountryCodeSource() &&
                parsedB.countryCodeSource != PhoneNumber.CountryCodeSource.FROM_DEFAULT_COUNTRY
            if (explicitA && explicitB && parsedA.countryCode != parsedB.countryCode) {
                return false
            }

            val matchType = phoneUtil.isNumberMatch(parsedA, parsedB)
            if (matchType == PhoneNumberUtil.MatchType.EXACT_MATCH ||
                matchType == PhoneNumberUtil.MatchType.NSN_MATCH
            ) {
                return true
            }
            if (matchType == PhoneNumberUtil.MatchType.SHORT_NSN_MATCH &&
                parsedA.countryCode == parsedB.countryCode
            ) {
                val nsnA = parsedA.nationalNumber.toString()
                val nsnB = parsedB.nationalNumber.toString()
                if (nsnA.length >= 7 && nsnB.length >= 7 &&
                    (nsnA.endsWith(nsnB) || nsnB.endsWith(nsnA))
                ) {
                    return true
                }
            }
        }

        // Prevent explicit different +<CC> numbers from matching via last-10-digit fallback.
        if (hasConflictingExplicitCountry(cleanA, cleanB, parsedA, parsedB)) {
            return false
        }

        if (digitsA == digitsB) return true
        if (digitsA.length >= 10 && digitsB.length >= 10) {
            if (digitsA.takeLast(10) == digitsB.takeLast(10)) return true
        }
        if (digitsA.length >= 7 && digitsB.endsWith(digitsA)) return true
        if (digitsB.length >= 7 && digitsA.endsWith(digitsB)) return true

        return false
    }

    private fun hasConflictingExplicitCountry(
        cleanA: String,
        cleanB: String,
        parsedA: PhoneNumber?,
        parsedB: PhoneNumber?,
    ): Boolean {
        val ccA = parsedA?.takeIf {
            cleanA.startsWith("+") || cleanA.startsWith("00")
        }?.countryCode
        val ccB = parsedB?.takeIf {
            cleanB.startsWith("+") || cleanB.startsWith("00")
        }?.countryCode
        return ccA != null && ccB != null && ccA != ccB
    }

    /** Alias for matches(). */
    fun matchTenDigits(numA: String, numB: String): Boolean = matches(numA, numB)

    /** Resolves contact name from contacts list by normalized matching. */
    fun resolveName(number: String, contacts: List<ContactItem>): String? {
        if (number.isBlank() || contacts.isEmpty()) return null
        return contacts.firstOrNull { matches(number, it.number) }?.name
    }
}
