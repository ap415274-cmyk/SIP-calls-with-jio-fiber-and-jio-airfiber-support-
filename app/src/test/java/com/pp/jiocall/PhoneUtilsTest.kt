package com.pp.jiocall

import com.pp.jiocall.data.CallLogEntry
import com.pp.jiocall.data.ContactItem
import com.pp.jiocall.data.PhoneUtils
import com.pp.jiocall.data.SmartDial
import com.pp.jiocall.sip.DialPlan
import com.pp.jiocall.ui.components.ContactActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneUtilsTest {

    @Test
    fun testNormalizeForDialing() {
        // Plain 10 digits (Indian mobile)
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("7060829556"))

        // Leading 0 (trunk STD prefix)
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("07060829556"))

        // 91 country code without plus
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("917060829556"))

        // +91 standard E.164 and redundant trunk 0 after +91
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("+917060829556"))
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("+91 070608 29556"))

        // Indian landlines with STD trunk 0 or +91 0...
        assertEquals("+911123456789", PhoneUtils.normalizeForDialing("01123456789"))
        assertEquals("+911123456789", PhoneUtils.normalizeForDialing("+91 011 2345 6789"))

        // Formatted with spaces / dashes / brackets / Bidi marks / tel: URI
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("\u200E+91 70608 29556"))
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("tel:+91 70608 29556"))
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("(07060) 829556"))
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("07060-829-556"))
        assertEquals("+917060829556", PhoneUtils.normalizeForDialing("00917060829556"))

        // International + prefixes and 00 IDD exit codes preserved / normalized cleanly
        assertEquals("+14155550199", PhoneUtils.normalizeForDialing("+1 (415) 555-0199"))
        assertEquals("+16505550199", PhoneUtils.normalizeForDialing("+1 650 555 0199"))
        assertEquals("+14155550199", PhoneUtils.normalizeForDialing("00 1 415 555 0199"))
        assertEquals("+447911123456", PhoneUtils.normalizeForDialing("+44 7911 123456"))
        assertEquals("+447911123456", PhoneUtils.normalizeForDialing("00447911123456"))

        // Indian toll-free national service numbers keep dialed format unless explicit +/00
        assertEquals("18008969999", PhoneUtils.normalizeForDialing("1800 896 9999"))
        assertEquals("+9118008969999", PhoneUtils.normalizeForDialing("+91 1800 896 9999"))

        // Vanity keypad letters & SIP URIs
        assertEquals("+18005554357", PhoneUtils.normalizeForDialing("+1-800-555-HELP"))
        assertEquals("alice@iptel.org", PhoneUtils.normalizeForDialing("alice@iptel.org"))

        // Short codes, USSD, and emergency numbers kept intact
        assertEquals("198", PhoneUtils.normalizeForDialing("198"))
        assertEquals("198", PhoneUtils.normalizeForDialing("1 98"))
        assertEquals("199", PhoneUtils.normalizeForDialing("199"))
        assertEquals("112", PhoneUtils.normalizeForDialing("112"))
        assertEquals("000", PhoneUtils.normalizeForDialing("000"))
        assertEquals("*123#", PhoneUtils.normalizeForDialing("*123#"))
        assertEquals("#31#+919876543210", PhoneUtils.normalizeForDialing("#31# +91 98765 43210"))
    }

    @Test
    fun testFormatForDisplayAndAsYouType() {
        // Short codes lose accidental Contacts spaces (never "1 98")
        assertEquals("198", PhoneUtils.formatForDisplay("1 98"))
        assertEquals("198", PhoneUtils.formatForDisplay("198"))
        assertEquals("*123#", PhoneUtils.formatForDisplay("*123#"))
        assertEquals("+91 198", PhoneUtils.formatForDisplay("+91 198"))

        // E.164 international and Indian numbers
        assertEquals("+91 92595 18300", PhoneUtils.formatForDisplay("+919259518300"))
        assertEquals("+91 92595 18300", PhoneUtils.formatForDisplay("+91 092595 18300"))
        assertEquals("+1 415-555-0199", PhoneUtils.formatForDisplay("+14155550199"))
        assertEquals("+44 7911 123456", PhoneUtils.formatForDisplay("+447911123456"))

        // National Indian numbers keep user's trunk '0' presence intact
        assertEquals("92595 18300", PhoneUtils.formatForDisplay("9259518300"))
        assertEquals("092595 18300", PhoneUtils.formatForDisplay("09259518300"))

        // Live Dialpad As-You-Type formatting
        assertEquals("1", PhoneUtils.formatAsYouType("1"))
        assertEquals("19", PhoneUtils.formatAsYouType("19"))
        assertEquals("198", PhoneUtils.formatAsYouType("198"))
        assertEquals("+", PhoneUtils.formatAsYouType("+"))
        assertEquals("+91 92595 18300", PhoneUtils.formatAsYouType("+919259518300"))
        assertEquals("*123#", PhoneUtils.formatAsYouType("*123#"))
    }

    @Test
    fun testMatchesCrossCountryAndVariants() {
        // Same number saved in different styles
        assertTrue(PhoneUtils.matches("07060829556", "917060829556"))
        assertTrue(PhoneUtils.matches("07060829556", "+917060829556"))
        assertTrue(PhoneUtils.matches("7060829556", "07060829556"))
        assertTrue(PhoneUtils.matches("+91 70608 29556", "07060829556"))
        assertTrue(PhoneUtils.matches("917060829556", "7060829556"))
        assertTrue(PhoneUtils.matches("0014155550199", "+1 415 555 0199"))

        // Conflicting explicit country codes must NOT match even with identical trailing 10 digits
        assertFalse(PhoneUtils.matches("+1 7060829556", "+91 7060829556"))

        // Different numbers must NOT match
        assertFalse(PhoneUtils.matches("07060829556", "07060829557"))
        assertFalse(PhoneUtils.matches("9876543210", "9123456789"))

        // Short codes match strictly and don't mix with +91198
        assertTrue(PhoneUtils.matches("1 98", "198"))
        assertFalse(PhoneUtils.matches("198", "+91 198"))
    }

    @Test
    fun testResolveName() {
        val contacts = listOf(
            ContactItem(id = 1, name = "Rahul Sharma", number = "07060829556"),
            ContactItem(id = 2, name = "Priya Patel", number = "919876543210"),
            ContactItem(id = 3, name = "Amit Verma", number = "+919123456789"),
            ContactItem(id = 4, name = "hello hello", number = "+91 198"),
            ContactItem(id = 5, name = "jh hh", number = "1 98"),
        )

        assertEquals("Rahul Sharma", PhoneUtils.resolveName("+917060829556", contacts))
        assertEquals("Rahul Sharma", PhoneUtils.resolveName("7060829556", contacts))
        assertEquals("Priya Patel", PhoneUtils.resolveName("+919876543210", contacts))
        assertEquals("Amit Verma", PhoneUtils.resolveName("09123456789", contacts))
        assertEquals("jh hh", PhoneUtils.resolveName("198", contacts))
        assertEquals("hello hello", PhoneUtils.resolveName("+91198", contacts))
    }

    @Test
    fun testContactActionsWhatsAppAndMatchingHistory() {
        assertEquals("919876543210", ContactActions.formatWhatsAppDigits("9876543210"))
        assertEquals("919876543210", ContactActions.formatWhatsAppDigits("09876543210"))
        assertEquals("919876543210", ContactActions.formatWhatsAppDigits("+91 98765 43210"))
        assertEquals("14155550199", ContactActions.formatWhatsAppDigits("+1 (415) 555-0199"))
        assertEquals("14155550199", ContactActions.formatWhatsAppDigits("0014155550199"))
        assertNull(ContactActions.formatWhatsAppDigits("198"))
        assertNull(ContactActions.formatWhatsAppDigits(""))

        val calls = listOf(
            CallLogEntry(id = "1", number = "+919876543210", name = "Priya"),
            CallLogEntry(id = "2", number = "198", name = "Jio Care"),
            CallLogEntry(id = "3", number = "09876543210", name = "Priya"),
            CallLogEntry(id = "4", number = "9123456789", name = "Other"),
        )
        assertEquals(listOf("1", "3"), ContactActions.matchingHistory(calls, "9876543210").map { it.id })
        assertEquals(listOf("2"), ContactActions.matchingHistory(calls, "198").map { it.id })
        assertTrue(ContactActions.matchingHistory(calls, "").isEmpty())
    }

    @Test
    fun testSmartDialWithPlusPrefixAndShortCode() {
        val contacts = listOf(
            ContactItem(id = 1, name = "Rahul Sharma", number = "7060829556"),
            ContactItem(id = 2, name = "jh hh", number = "1 98"),
            ContactItem(id = 3, name = "US Friend", number = "+1 415 555 0199"),
        )

        // Typing +917060 finds contact saved as local 7060829556
        val plusMatches = SmartDial.search(contacts, "+917060")
        assertEquals(listOf("Rahul Sharma"), plusMatches.map { it.contact.name })

        // Typing 07060 finds local 7060829556
        val trunkMatches = SmartDial.search(contacts, "07060")
        assertEquals(listOf("Rahul Sharma"), trunkMatches.map { it.contact.name })

        // Typing + keeps all contacts (not empty) with + contacts prioritized
        val barePlus = SmartDial.search(contacts, "+")
        assertEquals(3, barePlus.size)
        assertEquals("US Friend", barePlus.first().contact.name)

        // Short code contact displays cleanly as "Mobile 198", not "Mobile 1 98"
        val shortMatches = SmartDial.search(contacts, "198")
        assertEquals("Mobile 198", shortMatches.first().highlightedNumber.text)
    }

    @Test
    fun testSpacedShortCodeContactMatchesAndDialsWithoutSpaces() {
        val spaced = ContactItem(id = 4, name = "jh hh", number = "1 98")
        assertEquals("jh hh", PhoneUtils.resolveName("198", listOf(spaced)))
        assertTrue(spaced.matches("198"))
        assertEquals("198", PhoneUtils.normalizeForDialing("1 98"))
        assertEquals("198", DialPlan.formatNumber("1\u00A098", "", ""))
    }
}
