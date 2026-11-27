package com.pp.jiocall.ui

import androidx.compose.runtime.Immutable
import com.pp.jiocall.data.CallLogEntry
import com.pp.jiocall.data.ContactItem
import com.pp.jiocall.data.PhoneUtils
import com.pp.jiocall.data.SmartDial

/**
 * O(1) indexed contact lookup table built on [kotlinx.coroutines.Dispatchers.Default]
 * so scrolling Recents / Contacts or ringing CallScreen never linearly scans all contacts
 * or invokes libphonenumber repeatedly on the main thread.
 */
@Immutable
class ContactLookupIndex(
    val contacts: List<ContactItem>,
) {
    private val buckets: Map<String, List<ContactItem>>
    val precomputedMatchesLight: List<SmartDial.ContactMatch>
    val precomputedMatchesDark: List<SmartDial.ContactMatch>

    init {
        val grouped = HashMap<String, MutableList<ContactItem>>(contacts.size * 2)
        for (c in contacts) {
            val bucket = PhoneUtils.lookupBucket(c.number)
            if (bucket.isNotEmpty()) {
                grouped.getOrPut(bucket) { ArrayList(2) }.add(c)
            }
        }
        buckets = grouped
        precomputedMatchesLight = SmartDial.search(contacts, "", isDark = false)
        precomputedMatchesDark = SmartDial.search(contacts, "", isDark = true)
    }

    fun resolveContact(number: String): ContactItem? {
        if (number.isBlank() || contacts.isEmpty()) return null
        val bucket = PhoneUtils.lookupBucket(number)
        if (bucket.isNotEmpty()) {
            buckets[bucket]?.firstOrNull { PhoneUtils.matches(number, it.number) }?.let { return it }
        }
        return contacts.firstOrNull { PhoneUtils.matches(number, it.number) }
    }

    fun resolveName(number: String): String? = resolveContact(number)?.name

    fun resolvePhotoUri(number: String): String? = resolveContact(number)?.photoUri
}

/**
 * Precomputed, immutable Recents row state so LazyColumn composition does zero regex parsing,
 * zero date-formatter allocations, and zero O(N) contact/call-history scans during scroll.
 */
@Immutable
data class RecentsItemUi(
    val key: String,
    val entry: CallLogEntry,
    val count: Int,
    val history: List<CallLogEntry>,
    val displayName: String,
    val formattedNumber: String,
    val showNumberSubtitle: Boolean,
    val photoUri: String?,
    val timeText: String,
    val durationText: String,
    val dateBucket: String,
    val isFirstInBucket: Boolean,
    val matchingCount: Int,
    val matchingHistory: List<CallLogEntry>,
    val whatsAppEnabled: Boolean,
    val hasRecording: Boolean,
    val searchKeyLower: String,
)

/**
 * Precomputed, immutable Contact row state eliminating per-row libphonenumber calls on scroll.
 */
@Immutable
data class ContactRowUi(
    val key: String,
    val contact: ContactItem,
    val initial: String,
    val isFirstOfGroup: Boolean,
    val formattedNumber: String,
    val recentCount: Int,
    val matchingHistory: List<CallLogEntry>,
    val whatsAppEnabled: Boolean,
    val searchKeyLower: String,
)

/**
 * Builds precomputed UI list models on a background thread.
 */
internal object UiIndexBuilder {

    fun formatWhatsAppDigitsFast(number: String): String? {
        if (number.isBlank()) return null
        val normalized = PhoneUtils.normalizeForDialing(number).ifEmpty { number }
        val digits = normalized.filter(Char::isDigit)
        return if (digits.length >= 7) digits else null
    }

    private fun buildHistoryBuckets(calls: List<CallLogEntry>): Map<String, MutableList<CallLogEntry>> {
        val map = HashMap<String, MutableList<CallLogEntry>>(calls.size)
        for (call in calls) {
            val bucket = PhoneUtils.lookupBucket(call.number)
            if (bucket.isNotEmpty()) {
                map.getOrPut(bucket) { ArrayList(4) }.add(call)
            }
        }
        return map
    }

    private fun matchingHistoryFast(
        calls: List<CallLogEntry>,
        buckets: Map<String, MutableList<CallLogEntry>>,
        number: String,
    ): List<CallLogEntry> {
        if (number.isBlank() || calls.isEmpty()) return emptyList()
        val trimmed = number.trim()
        val bucket = PhoneUtils.lookupBucket(trimmed)
        val candidates = if (bucket.isNotEmpty()) buckets[bucket] ?: emptyList() else calls
        val hits = ArrayList<CallLogEntry>(candidates.size)
        for (c in candidates) {
            if (c.number.trim() == trimmed || PhoneUtils.matches(c.number, trimmed)) {
                hits.add(c)
            }
        }
        return hits
    }

    fun buildRecents(
        calls: List<CallLogEntry>,
        contactIndex: ContactLookupIndex,
        availableRecordings: Set<String>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<RecentsItemUi> {
        if (calls.isEmpty()) return emptyList()
        val grouped = ArrayList<Triple<CallLogEntry, Int, MutableList<CallLogEntry>>>(calls.size)
        for (next in calls) {
            val prev = grouped.lastOrNull()
            val sameNumber = prev != null && (
                (prev.first.number.isNotEmpty() && prev.first.number == next.number) ||
                    PhoneUtils.matchTenDigits(prev.first.number, next.number)
                )
            if (sameNumber) {
                prev!!.third.add(next)
                grouped[grouped.lastIndex] = Triple(prev.first, prev.second + 1, prev.third)
            } else {
                grouped.add(Triple(next, 1, mutableListOf(next)))
            }
        }

        val callBuckets = buildHistoryBuckets(calls)
        val whatsAppMemo = HashMap<String, Boolean>(grouped.size)
        val matchMemo = HashMap<String, List<CallLogEntry>>(grouped.size)
        var lastBucket: String? = null

        return grouped.map { (entry, count, history) ->
            val matchedContact = contactIndex.resolveContact(entry.number)
            val formatted = PhoneUtils.formatForDisplay(entry.number).ifEmpty { entry.number }
            val resolvedName = matchedContact?.name?.takeIf { it.isNotBlank() }
            val displayName = resolvedName ?: entry.name.ifEmpty { formatted }
            val photoUri = matchedContact?.photoUri
            val bucket = entry.dayBucket(nowMs)
            val isFirstInBucket = bucket != lastBucket
            lastBucket = bucket
            val recPath = entry.recordingPath
            val hasRec = recPath != null && recPath in availableRecordings
            val numKey = entry.number.trim()
            val matching = matchMemo.getOrPut(numKey) {
                matchingHistoryFast(calls, callBuckets, numKey)
            }
            val waOk = whatsAppMemo.getOrPut(numKey) {
                formatWhatsAppDigitsFast(numKey) != null
            }
            RecentsItemUi(
                key = "${entry.id}_${entry.startedAt}",
                entry = entry,
                count = count,
                history = history,
                displayName = displayName,
                formattedNumber = formatted,
                showNumberSubtitle = displayName != formatted,
                photoUri = photoUri,
                timeText = entry.formattedTimestamp(nowMs),
                durationText = if (entry.durationSec > 0) entry.formattedDuration() else "",
                dateBucket = bucket,
                isFirstInBucket = isFirstInBucket,
                matchingCount = matching.size,
                matchingHistory = matching,
                whatsAppEnabled = waOk,
                hasRecording = hasRec,
                searchKeyLower = "${displayName.lowercase()}|${entry.number.lowercase()}|${formatted.lowercase()}",
            )
        }
    }

    fun buildContacts(
        contacts: List<ContactItem>,
        calls: List<CallLogEntry>,
    ): List<ContactRowUi> {
        if (contacts.isEmpty()) return emptyList()
        val callBuckets = buildHistoryBuckets(calls)
        val whatsAppMemo = HashMap<String, Boolean>(contacts.size)
        val matchMemo = HashMap<String, List<CallLogEntry>>(contacts.size)
        val grouped = contacts.groupBy { it.initial.uppercase() }.toSortedMap()
        val result = ArrayList<ContactRowUi>(contacts.size)

        for ((initial, list) in grouped) {
            for (idx in list.indices) {
                val contact = list[idx]
                val numKey = contact.number.trim()
                val formatted = if (numKey.isNotBlank()) {
                    PhoneUtils.formatForDisplay(contact.number).ifEmpty { contact.number }
                } else {
                    ""
                }
                val matching = if (numKey.isNotBlank()) {
                    matchMemo.getOrPut(numKey) { matchingHistoryFast(calls, callBuckets, numKey) }
                } else {
                    emptyList()
                }
                val waOk = if (numKey.isNotBlank()) {
                    whatsAppMemo.getOrPut(numKey) { formatWhatsAppDigitsFast(numKey) != null }
                } else {
                    false
                }
                result.add(
                    ContactRowUi(
                        key = "${initial}_${contact.id}_${contact.name}_${contact.number}_$idx",
                        contact = contact,
                        initial = initial,
                        isFirstOfGroup = idx == 0,
                        formattedNumber = formatted,
                        recentCount = matching.size,
                        matchingHistory = matching,
                        whatsAppEnabled = waOk,
                        searchKeyLower = "${contact.name.lowercase()}|${contact.number.lowercase()}",
                    ),
                )
            }
        }
        return result
    }
}
