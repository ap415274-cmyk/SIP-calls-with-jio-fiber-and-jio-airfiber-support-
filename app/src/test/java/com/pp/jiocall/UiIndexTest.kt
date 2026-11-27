package com.pp.jiocall

import com.pp.jiocall.data.CallLogEntry
import com.pp.jiocall.data.ContactItem
import com.pp.jiocall.data.PhoneUtils
import com.pp.jiocall.ui.ContactLookupIndex
import com.pp.jiocall.ui.UiIndexBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiIndexTest {

    private val contacts = listOf(
        ContactItem(id = 1, name = "Amit Sharma", number = "+919876543210"),
        ContactItem(id = 2, name = "hello hello", number = "+91 198"),
        ContactItem(id = 3, name = "jh hh", number = "1 98"),
    )

    @Test
    fun contactLookupResolvesVariantsAndShortCodes() {
        val index = ContactLookupIndex(contacts)
        assertEquals("Amit Sharma", index.resolveName("09876543210"))
        assertEquals("Amit Sharma", index.resolveName("9876543210"))
        assertEquals("hello hello", index.resolveName("+91198"))
        assertEquals("jh hh", index.resolveName("198"))
    }

    @Test
    fun buildRecentsGroupsAndComputesBuckets() {
        val now = 1_700_000_000_000L
        val calls = listOf(
            CallLogEntry(id = "1", number = "+919876543210", startedAt = now - 10_000L, durationSeconds = 45, outgoing = true),
            CallLogEntry(id = "2", number = "09876543210", startedAt = now - 60_000L, durationSeconds = 0, outgoing = false, missed = true),
            CallLogEntry(id = "3", number = "198", startedAt = now - 5 * 86_400_000L, durationSeconds = 12, outgoing = true),
        )
        val index = ContactLookupIndex(contacts)
        val items = UiIndexBuilder.buildRecents(calls, index, emptySet(), now)

        assertEquals(2, items.size)
        assertEquals("Amit Sharma", items[0].displayName)
        assertEquals(2, items[0].count)
        assertEquals(2, items[0].matchingCount)
        assertTrue(items[0].whatsAppEnabled)
        assertEquals("Today", items[0].dateBucket)
        assertTrue(items[0].isFirstInBucket)

        assertEquals("jh hh", items[1].displayName)
        assertEquals(1, items[1].count)
        assertFalse(items[1].whatsAppEnabled)
        assertEquals("Earlier", items[1].dateBucket)
        assertTrue(items[1].isFirstInBucket)
    }

    @Test
    fun buildContactsSetsGroupHeadersAndMatchCounts() {
        val calls = listOf(
            CallLogEntry(id = "1", number = "9876543210"),
            CallLogEntry(id = "2", number = "+919876543210"),
        )
        val rows = UiIndexBuilder.buildContacts(contacts, calls)
        assertEquals(3, rows.size)

        val amit = rows.first { it.contact.name == "Amit Sharma" }
        assertTrue(amit.isFirstOfGroup)
        assertEquals("A", amit.initial)
        assertEquals(2, amit.recentCount)
        assertTrue(amit.whatsAppEnabled)

        val shortCode = rows.first { it.contact.name == "jh hh" }
        assertEquals("198", shortCode.formattedNumber)
        assertFalse(shortCode.whatsAppEnabled)
        assertEquals("S:198", PhoneUtils.lookupBucket("1 98"))
    }
}
