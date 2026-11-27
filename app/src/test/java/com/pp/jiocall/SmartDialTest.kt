package com.pp.jiocall

import com.pp.jiocall.data.ContactItem
import com.pp.jiocall.data.GooglePhoneColors
import com.pp.jiocall.data.SmartDial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDialTest {

    private val contacts = listOf(
        ContactItem(id = 1, name = "Amit Sharma", number = "+919876543210"),
        ContactItem(id = 2, name = "Pooja Verma", number = "+919123456789"),
        ContactItem(id = 3, name = "Jio Care", number = "18008969999"),
        ContactItem(id = 4, name = "HELLO", number = "(706) 082-9556"),
    )

    @Test
    fun testAvatarColors() {
        assertEquals(GooglePhoneColors.Pink, GooglePhoneColors.getAvatarColor("Amit Sharma"))
        assertEquals(GooglePhoneColors.Green, GooglePhoneColors.getAvatarColor("HELLO"))
        assertEquals(GooglePhoneColors.Yellow, GooglePhoneColors.getAvatarColor("Jio Care"))
        assertEquals(GooglePhoneColors.Coral, GooglePhoneColors.getAvatarColor("Pooja Verma"))
    }

    @Test
    fun testTyping7MatchesScreenshot1() {
        val matches = SmartDial.search(contacts, "7")
        // In screenshot 1:
        // Amit Sharma (S=7 in T9, 7 in number)
        // HELLO (7 in number (706))
        // Pooja Verma (P=7 in T9, 7 in number)
        assertEquals(3, matches.size)
        val names = matches.map { it.contact.name }
        assertTrue(names.contains("Amit Sharma"))
        assertTrue(names.contains("HELLO"))
        assertTrue(names.contains("Pooja Verma"))
    }

    @Test
    fun testEmptyQueryReturnsAllContacts() {
        val all = SmartDial.search(contacts, "")
        assertEquals(4, all.size)
    }
}
