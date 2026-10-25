package de.picsync.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.picsync.app.MainActivity
import de.picsync.app.ContactRepository
import de.picsync.app.SyncEngine
import de.picsync.app.SyncState
import de.picsync.app.WhatsAppSession
import de.picsync.app.WhatsAppSession.Status

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    session: WhatsAppSession,
    sync: SyncEngine,
    contacts: ContactRepository,
    initialOverwrite: Boolean,
    onOverwriteChanged: (Boolean) -> Unit,
    hasContactPermission: () -> Boolean,
) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        val status by session.status.collectAsStateWithLifecycle()
        val log by session.log.collectAsStateWithLifecycle()
        val syncState by sync.state.collectAsStateWithLifecycle()
        val excludedCount by sync.excludedCount.collectAsStateWithLifecycle()

        Scaffold(topBar = { TopAppBar(title = { Text("PicSync") }) }) { padding ->
            val content = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
            val preview = syncState as? SyncState.Preview
            if (status == Status.Connected && preview != null) {
                PreviewScreen(
                    state = preview,
                    contacts = contacts,
                    onToggle = sync::toggle,
                    onSelectAll = sync::setSelection,
                    onApply = sync::apply,
                    onCancel = sync::cancelPreview,
                    modifier = content,
                )
                return@Scaffold
            }
            Column(modifier = content, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (val s = status) {
                    Status.Starting, Status.Connecting -> Busy(if (s == Status.Starting) "Starte…" else "Verbinde mit WhatsApp…")
                    Status.NotPaired -> PairCard(onPair = session::pair)
                    is Status.Pairing -> PairingCodeCard(s.code, onCancel = session::cancelPairing)
                    is Status.Error -> ErrorCard(s.message, onRetry = session::reconnect)
                    Status.Connected -> SyncCard(
                        state = syncState,
                        excludedCount = excludedCount,
                        initialOverwrite = initialOverwrite,
                        onOverwriteChanged = onOverwriteChanged,
                        hasContactPermission = hasContactPermission,
                        onLoadPreview = sync::loadPreview,
                        onClearExclusions = sync::clearExclusions,
                        onDismissResult = sync::dismissResult,
                        onLogout = session::logout,
                    )
                }
                LogView(log, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.height(24.dp))
        Text(text)
    }
}

@Composable
private fun PairCard(onPair: (String) -> Unit) {
    var phone by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mit WhatsApp koppeln", style = MaterialTheme.typography.titleMedium)
            Text(
                "PicSync meldet sich als verknüpftes Gerät an (wie WhatsApp Web). " +
                    "Gib deine eigene Nummer im internationalen Format ein.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Eigene Nummer, z. B. +49 176 1234567") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onPair(phone) }, enabled = phone.count(Char::isDigit) >= 8) {
                Text("Kopplungscode anfordern")
            }
        }
    }
}

@Composable
private fun PairingCodeCard(code: String?, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (code == null) {
                Busy("Fordere Kopplungscode an…")
            } else {
                Text("Kopplungscode", style = MaterialTheme.typography.titleMedium)
                Text(code, fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                val clipboard = LocalClipboardManager.current
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(code.replace("-", ""))) }) {
                    Text("Code kopieren")
                }
                Text(
                    "WhatsApp sollte eine Benachrichtigung zeigen. Alternativ: WhatsApp → Einstellungen → " +
                        "Verknüpfte Geräte → Gerät hinzufügen → „Stattdessen mit Telefonnummer verknüpfen“ " +
                        "und diesen Code eingeben. Der Code steht auch in der PicSync-Benachrichtigung " +
                        "und ist etwa 2 Minuten gültig.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(onClick = onCancel) { Text("Abbrechen") }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Fehler", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(message)
            Button(onClick = onRetry) { Text("Erneut versuchen") }
        }
    }
}

@Composable
private fun SyncCard(
    state: SyncState,
    excludedCount: Int,
    initialOverwrite: Boolean,
    onOverwriteChanged: (Boolean) -> Unit,
    hasContactPermission: () -> Boolean,
    onLoadPreview: (Boolean) -> Unit,
    onClearExclusions: () -> Unit,
    onDismissResult: () -> Unit,
    onLogout: () -> Unit,
) {
    var overwrite by rememberSaveable { mutableStateOf(initialOverwrite) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        permissionDenied = !result.values.all { it }
        if (!permissionDenied) onLoadPreview(overwrite)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("✅ Mit WhatsApp verbunden", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Eigene Fotos zum Ersetzen vorschlagen")
                    Text(
                        "Aus: Kontakte mit eigenem Foto erscheinen nur als „Eigenes Foto“ und sind nicht vorausgewählt",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = overwrite,
                    enabled = !state.busy,
                    onCheckedChange = { overwrite = it; onOverwriteChanged(it) },
                )
            }

            Button(
                enabled = !state.busy,
                onClick = {
                    if (hasContactPermission()) onLoadPreview(overwrite)
                    else permissionLauncher.launch(MainActivity.CONTACT_PERMISSIONS)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.busy) "Bitte warten…" else "Vorschau laden") }

            if (permissionDenied) {
                Text("Ohne Kontakt-Berechtigung geht es nicht.", color = MaterialTheme.colorScheme.error)
            }

            StateView(state, onDismissResult)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLogout, enabled = !state.busy) { Text("Gerät abmelden") }
                if (excludedCount > 0) {
                    TextButton(onClick = onClearExclusions, enabled = !state.busy) {
                        Text("$excludedCount abgewählte zurücksetzen")
                    }
                }
            }
        }
    }
}

@Composable
private fun StateView(state: SyncState, onDismiss: () -> Unit) {
    when (state) {
        SyncState.Idle, is SyncState.Preview -> Unit
        is SyncState.Loading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (state.total > 0) "${state.phase} (${state.done}/${state.total})" else state.phase)
            if (state.total > 0) {
                LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        is SyncState.Applying -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Fotos übernehmen (${state.done}/${state.total})")
            LinearProgressIndicator(
                progress = { if (state.total == 0) 1f else state.done.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is SyncState.Done -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Fertig: ${state.updated} Fotos übernommen" +
                    (if (state.failed > 0) ", ${state.failed} fehlgeschlagen" else "") +
                    (if (state.excluded > 0) ", ${state.excluded} abgewählt gemerkt" else ""),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
        is SyncState.Failed -> Text("Abgebrochen: ${state.message}", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LogView(lines: List<String>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.lastIndex) }
    Column(modifier) {
        Text("Log", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
        }
    }
}
