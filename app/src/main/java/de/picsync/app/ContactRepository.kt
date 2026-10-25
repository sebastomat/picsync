package de.picsync.app

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.RawContacts
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale

data class PhoneContact(
    val id: Long,
    val name: String,
    val numbers: Set<String>,
    val hasPhoto: Boolean,
)

/** Reads phone contacts and writes contact photos via the Contacts Provider. */
class ContactRepository(private val context: Context) {

    private val resolver = context.contentResolver

    /** Contacts with at least one number that could be normalized to E.164. */
    fun loadContacts(): List<PhoneContact> {
        val country = defaultCountryIso()
        val byId = LinkedHashMap<Long, PhoneContact>()
        val projection = arrayOf(
            Phone.CONTACT_ID,
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER,
            Phone.NORMALIZED_NUMBER,
            Phone.PHOTO_ID,
        )
        resolver.query(Phone.CONTENT_URI, projection, null, null, Phone.DISPLAY_NAME_PRIMARY)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val e164 = toE164(c.getString(2), c.getString(3), country) ?: continue
                val existing = byId[id]
                byId[id] = existing?.copy(numbers = existing.numbers + e164)
                    ?: PhoneContact(
                        id = id,
                        name = c.getString(1).orEmpty(),
                        numbers = setOf(e164),
                        hasPhoto = !c.isNull(4) && c.getLong(4) > 0,
                    )
            }
        }
        return byId.values.toList()
    }

    /** Writes [jpeg] as full-size photo of the contact. Returns false if no writable raw contact exists. */
    fun setPhoto(contactId: Long, jpeg: ByteArray): Boolean {
        val rawId = pickRawContact(contactId) ?: return false
        val uri = Uri.withAppendedPath(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawId),
            RawContacts.DisplayPhoto.CONTENT_DIRECTORY,
        )
        resolver.openAssetFileDescriptor(uri, "rw")?.use { fd ->
            fd.createOutputStream().use { it.write(jpeg) }
        } ?: return false
        return true
    }

    /** Small version of the contact's current photo, or null if it has none. */
    fun loadThumbnail(contactId: Long, sizePx: Int): Bitmap? {
        val uri = ContentUris.withAppendedId(Contacts.CONTENT_URI, contactId)
        val bytes = Contacts.openContactPhotoInputStream(resolver, uri, false)?.use { it.readBytes() } ?: return null
        return decodeScaled(bytes, sizePx)
    }

    /** Prefers the Google account, never writes into messenger-owned raw contacts. */
    private fun pickRawContact(contactId: Long): Long? {
        val candidates = mutableListOf<Pair<Long, String?>>()
        resolver.query(
            RawContacts.CONTENT_URI,
            arrayOf(RawContacts._ID, RawContacts.ACCOUNT_TYPE),
            "${RawContacts.CONTACT_ID}=? AND ${RawContacts.DELETED}=0",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) candidates += c.getLong(0) to c.getString(1)
        }
        val writable = candidates.filter { it.second !in SKIPPED_ACCOUNT_TYPES }
        return (writable.firstOrNull { it.second == "com.google" } ?: writable.firstOrNull())?.first
    }

    private fun toE164(number: String?, normalized: String?, country: String): String? {
        if (!normalized.isNullOrBlank() && normalized.startsWith("+")) return normalized
        if (number.isNullOrBlank()) return null
        return PhoneNumberUtils.formatNumberToE164(number, country)
    }

    private fun defaultCountryIso(): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        return listOf(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.ROOT)
            ?: "DE"
    }

    private companion object {
        val SKIPPED_ACCOUNT_TYPES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.thoughtcrime.securesms",
            "com.viber.voip",
            "com.facebook.orca",
        )
    }
}

/** Decodes [bytes] downsampled to roughly [sizePx] on the shorter side. */
fun decodeScaled(bytes: ByteArray, sizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= sizePx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
