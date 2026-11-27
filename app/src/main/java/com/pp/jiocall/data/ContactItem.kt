package com.pp.jiocall.data

import android.content.Context
import android.provider.ContactsContract
import androidx.compose.runtime.Immutable
import org.json.JSONObject

@Immutable
data class ContactItem(
    val id: Long = 0L,
    val name: String = "",
    val number: String = "",
    val photoUri: String? = null,
    val lookupKey: String? = null,
) {
    val initial: String
        get() = name.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
            ?: name.firstOrNull { it.isDigit() }?.toString()
            ?: "#"

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (name.lowercase().contains(q)) return true
        val cleanNumber = PhoneUtils.cleanDialInput(number)
        val cleanQuery = PhoneUtils.cleanDialInput(q)
        if (cleanQuery.isNotEmpty()) {
            if (cleanNumber.contains(cleanQuery)) return true
            if (PhoneUtils.matches(cleanNumber, cleanQuery)) return true
            val numDigits = cleanNumber.filter(Char::isDigit)
            if (PhoneUtils.searchDigitVariants(q).any { variant -> numDigits.contains(variant) }) {
                return true
            }
        }
        return false
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("number", number)
        if (photoUri != null) put("photoUri", photoUri)
        if (lookupKey != null) put("lookupKey", lookupKey)
    }

    companion object {
        fun fromJson(json: JSONObject): ContactItem = ContactItem(
            id = json.optLong("id", 0L),
            name = json.optString("name", ""),
            number = json.optString("number", ""),
            photoUri = json.optString("photoUri").takeIf { it.isNotEmpty() },
            lookupKey = json.optString("lookupKey").takeIf { it.isNotEmpty() },
        )
    }
}

object DeviceContacts {
    fun readDeviceContacts(context: Context): List<ContactItem> {
        val list = mutableListOf<ContactItem>()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
        )
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"

        runCatching {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI)
                val lookupIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx).orEmpty().trim()
                    val number = cursor.getString(numIdx).orEmpty().trim()
                    val photo = if (photoIdx >= 0) cursor.getString(photoIdx) else null
                    val lookup = if (lookupIdx >= 0) cursor.getString(lookupIdx) else null
                    if (name.isNotEmpty() || number.isNotEmpty()) {
                        list.add(ContactItem(id, name.ifEmpty { number }, number, photo, lookup))
                    }
                }
            }
        }
        return list.distinctBy { it.name.lowercase() to PhoneUtils.cleanDialInput(it.number) }
    }
}
