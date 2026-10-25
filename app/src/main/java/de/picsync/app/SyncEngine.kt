package de.picsync.app

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class PreviewStatus(val label: String, val actionable: Boolean) {
    NEW("Neu", true),
    REPLACE("Ersetzen", true),
    KEEP("Eigenes Foto", true),
    UNCHANGED("Unverändert", false),
    NO_PICTURE("Kein Bild", false),
    FAILED("Fehler", false),
}

data class PreviewItem(
    val contact: PhoneContact,
    val status: PreviewStatus,
    val pictureId: String? = null,
    /** Downloaded WhatsApp picture, only for actionable items. */
    val image: File? = null,
    /** The user deselected this contact earlier for the same picture. */
    val excluded: Boolean = false,
)

sealed interface SyncState {
    data object Idle : SyncState
    data class Loading(val phase: String, val done: Int = 0, val total: Int = 0) : SyncState
    data class Preview(val items: List<PreviewItem>, val selected: Set<Long>) : SyncState
    data class Applying(val done: Int, val total: Int) : SyncState
    data class Done(val updated: Int, val failed: Int, val excluded: Int) : SyncState
    data class Failed(val message: String) : SyncState

    val busy: Boolean get() = this is Loading || this is Applying
}

/**
 * Two-step sync: [loadPreview] downloads WhatsApp pictures into a cache and builds a
 * selectable list, [apply] writes the selected ones into the contacts.
 *
 * Per contact it remembers which picture ID PicSync wrote (so unchanged pictures are
 * skipped and own photos may be replaced) and which picture the user deselected (so it
 * is not preselected again until the WhatsApp picture changes).
 */
class SyncEngine(
    private val context: Context,
    private val session: WhatsAppSession,
    private val contacts: ContactRepository,
) {
    private val written = context.getSharedPreferences("sync_state", Context.MODE_PRIVATE)
    private val exclusions = context.getSharedPreferences("excluded", Context.MODE_PRIVATE)
    private val cacheDir = File(context.cacheDir, "preview")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _excludedCount = MutableStateFlow(exclusions.all.size)
    val excludedCount: StateFlow<Int> = _excludedCount.asStateFlow()

    fun loadPreview(overwriteForeignPhotos: Boolean) {
        if (_state.value.busy) return
        _state.value = SyncState.Loading("Kontakte lesen")
        KeepAliveService.start(context)
        scope.launch {
            try {
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
                val all = contacts.loadContacts()

                _state.value = SyncState.Loading("WhatsApp-Nummern prüfen")
                val byNumber = HashMap<String, MutableList<PhoneContact>>()
                all.forEach { c -> c.numbers.forEach { byNumber.getOrPut(it) { mutableListOf() } += c } }
                val jids = session.resolveNumbers(byNumber.keys)

                val byJid = LinkedHashMap<String, MutableSet<PhoneContact>>()
                jids.forEach { (number, jid) -> byJid.getOrPut(jid) { linkedSetOf() } += byNumber[number].orEmpty() }

                val items = mutableListOf<PreviewItem>()
                var done = 0
                for ((jid, group) in byJid) {
                    _state.value = SyncState.Loading("Profilbilder laden", done, byJid.size)
                    items += previewGroup(jid, group, overwriteForeignPhotos)
                    done++
                    delay(REQUEST_DELAY_MS)
                }
                // A contact with several WhatsApp numbers may appear more than once; keep the most useful row.
                val rows = items
                    .groupBy { it.contact.id }
                    .map { (_, rows) -> rows.minBy { it.status.ordinal } }
                    .sortedWith(compareBy({ it.status.ordinal }, { it.contact.name.lowercase() }))
                val selected = rows
                    .filter { (it.status == PreviewStatus.NEW || it.status == PreviewStatus.REPLACE) && !it.excluded }
                    .mapTo(HashSet()) { it.contact.id }
                _state.value = SyncState.Preview(rows, selected)
            } catch (e: Exception) {
                _state.value = SyncState.Failed(e.message ?: e.toString())
            }
        }
    }

    private suspend fun previewGroup(jid: String, group: Set<PhoneContact>, overwrite: Boolean): List<PreviewItem> {
        // Only ask for "unchanged" if every contact already shows the picture PicSync wrote.
        val knownIds = group.map { if (it.hasPhoto) writtenId(it.id) else null }.distinct()
        val result = try {
            session.fetchPicture(jid, knownIds.singleOrNull())
        } catch (e: Exception) {
            return group.map { PreviewItem(it, PreviewStatus.FAILED) }
        }
        return when (result) {
            WhatsAppSession.PictureResult.None -> group.map { PreviewItem(it, PreviewStatus.NO_PICTURE) }
            WhatsAppSession.PictureResult.Unchanged -> group.map { PreviewItem(it, PreviewStatus.UNCHANGED) }
            is WhatsAppSession.PictureResult.New -> {
                val file = File(cacheDir, "${jid.hashCode().toUInt()}.jpg").apply { writeBytes(result.data) }
                group.map { c ->
                    val stored = writtenId(c.id)
                    val status = when {
                        !c.hasPhoto -> PreviewStatus.NEW
                        stored == result.id -> PreviewStatus.UNCHANGED
                        stored != null || overwrite -> PreviewStatus.REPLACE
                        else -> PreviewStatus.KEEP
                    }
                    PreviewItem(
                        contact = c,
                        status = status,
                        pictureId = result.id,
                        image = file.takeIf { status.actionable },
                        excluded = exclusions.getString(key(c.id), null) == result.id,
                    )
                }
            }
        }
    }

    fun toggle(contactId: Long) = updateSelection { if (contactId in it) it - contactId else it + contactId }

    fun setSelection(contactIds: Collection<Long>, selected: Boolean) =
        updateSelection { if (selected) it + contactIds else it - contactIds.toSet() }

    private fun updateSelection(change: (Set<Long>) -> Set<Long>) {
        _state.update { s -> if (s is SyncState.Preview) s.copy(selected = change(s.selected)) else s }
    }

    fun cancelPreview() {
        _state.value = SyncState.Idle
        cacheDir.deleteRecursively()
    }

    fun apply() {
        val preview = _state.value as? SyncState.Preview ?: return
        val actionable = preview.items.filter { it.status.actionable && it.image != null && it.pictureId != null }
        val toWrite = actionable.filter { it.contact.id in preview.selected }
        _state.value = SyncState.Applying(0, toWrite.size)
        KeepAliveService.start(context)
        scope.launch {
            var updated = 0
            var failed = 0
            for (item in toWrite) {
                val ok = runCatching { contacts.setPhoto(item.contact.id, item.image!!.readBytes()) }.getOrDefault(false)
                if (ok) {
                    written.edit { putString(key(item.contact.id), item.pictureId) }
                    exclusions.edit { remove(key(item.contact.id)) }
                    updated++
                } else {
                    failed++
                }
                _state.value = SyncState.Applying(updated + failed, toWrite.size)
            }
            // Remember suggestions the user turned down, so they stay unchecked next time.
            val declined = actionable.filter {
                it.contact.id !in preview.selected &&
                    (it.status == PreviewStatus.NEW || it.status == PreviewStatus.REPLACE)
            }
            exclusions.edit { declined.forEach { putString(key(it.contact.id), it.pictureId) } }
            _excludedCount.value = exclusions.all.size
            cacheDir.deleteRecursively()
            _state.value = SyncState.Done(updated, failed, declined.size)
        }
    }

    fun clearExclusions() {
        exclusions.edit { clear() }
        _excludedCount.value = 0
    }

    fun dismissResult() {
        if (!_state.value.busy) _state.value = SyncState.Idle
    }

    private fun writtenId(contactId: Long): String? = written.getString(key(contactId), null)

    private fun key(contactId: Long) = "pic_$contactId"

    private companion object {
        // Be gentle with WhatsApp's servers to avoid rate limits or flags on the account.
        const val REQUEST_DELAY_MS = 600L
    }
}
