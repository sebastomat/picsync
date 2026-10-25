package de.picsync.app

import android.content.Context
import android.util.Log
import de.picsync.bridge.Bridge
import de.picsync.bridge.Client
import de.picsync.bridge.Listener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Wraps the Go bridge: linked-device session, pairing and picture download. */
class WhatsAppSession(context: Context) : Listener {

    private val appContext = context.applicationContext

    sealed interface Status {
        data object Starting : Status
        data object NotPaired : Status
        data class Pairing(val code: String?) : Status
        data object Connecting : Status
        data object Connected : Status
        data class Error(val message: String) : Status
    }

    private val dbPath = File(context.filesDir, "whatsapp.db").path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientLock = Mutex()
    private var client: Client? = null

    private val _status = MutableStateFlow<Status>(Status.Starting)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    init {
        scope.launch { start() }
    }

    private suspend fun start() {
        val c = try {
            clientLock.withLock { client ?: Bridge.newClient(dbPath, this).also { client = it } }
        } catch (e: Exception) {
            fail("Datenbank konnte nicht geöffnet werden", e)
            return
        }
        if (!c.isPaired) {
            _status.value = Status.NotPaired
            return
        }
        _status.value = Status.Connecting
        try {
            c.connect()
        } catch (e: Exception) {
            fail("Verbindung fehlgeschlagen", e)
        }
    }

    fun reconnect() {
        scope.launch { start() }
    }

    /** Requests a pairing code for [phone] (own number, international format). */
    fun pair(phone: String) {
        scope.launch {
            _status.value = Status.Pairing(null)
            KeepAliveService.start(appContext)
            try {
                requireClient().pairWithPhone(phone)
            } catch (e: Exception) {
                fail("Kopplung fehlgeschlagen", e)
            }
        }
    }

    fun cancelPairing() {
        scope.launch {
            client?.disconnect()
            _status.value = Status.NotPaired
        }
    }

    fun logout() {
        scope.launch {
            try {
                client?.logout()
            } catch (e: Exception) {
                addLog("warn", "Abmelden: ${e.message}")
            }
            resetClient()
        }
    }

    /** Returns a map of E.164 number -> WhatsApp JID for all registered numbers. */
    suspend fun resolveNumbers(numbers: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
        requireClient().resolveNumbers(numbers.joinToString("\n"))
            .lineSequence()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .toMap()
    }

    sealed interface PictureResult {
        data object None : PictureResult
        data object Unchanged : PictureResult
        class New(val id: String, val data: ByteArray) : PictureResult
    }

    suspend fun fetchPicture(jid: String, existingId: String?): PictureResult = withContext(Dispatchers.IO) {
        val pic = requireClient().fetchPicture(jid, existingId.orEmpty())
        when {
            pic == null -> PictureResult.None
            pic.data == null || pic.data.isEmpty() -> PictureResult.Unchanged
            else -> PictureResult.New(pic.id, pic.data)
        }
    }

    private suspend fun requireClient(): Client =
        clientLock.withLock { client ?: Bridge.newClient(dbPath, this).also { client = it } }

    /** After logout the device store is gone, so start over with a fresh client. */
    private suspend fun resetClient() {
        clientLock.withLock {
            client?.disconnect()
            client = null
        }
        start()
    }

    private fun fail(message: String, e: Exception) {
        addLog("error", "$message: ${e.message}")
        _status.value = Status.Error("$message: ${e.message}")
    }

    private fun addLog(level: String, msg: String) {
        Log.println(
            when (level) {
                "error" -> Log.ERROR
                "warn" -> Log.WARN
                else -> Log.INFO
            },
            "PicSync", msg,
        )
        _log.update { (it + "[$level] $msg").takeLast(200) }
    }

    // --- Listener (called from Go threads) ---

    override fun onPairCode(code: String) {
        _status.value = Status.Pairing(code)
    }

    override fun onPaired(jid: String) {
        addLog("info", "Gekoppelt als $jid")
        _status.value = Status.Connecting
    }

    override fun onPairError(reason: String) {
        if (_status.value !is Status.Pairing) return
        val hint = if (reason == "timeout") "Code abgelaufen oder Verbindung unterbrochen" else reason
        addLog("error", "Kopplung fehlgeschlagen: $reason")
        _status.value = Status.Error("Kopplung fehlgeschlagen: $hint")
    }

    override fun onConnected() {
        _status.value = Status.Connected
    }

    override fun onLoggedOut() {
        addLog("warn", "Von WhatsApp abgemeldet")
        scope.launch { resetClient() }
    }

    override fun onLog(level: String, msg: String) {
        addLog(level, msg)
    }
}
