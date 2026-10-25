package de.picsync.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.picsync.app.ContactRepository
import de.picsync.app.PreviewItem
import de.picsync.app.PreviewStatus
import de.picsync.app.SyncState
import de.picsync.app.decodeScaled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Filter(val label: String) { CHANGES("Vorschläge"), ACTIONABLE("Mit Bild"), ALL("Alle") }

private const val THUMB_PX = 144

@Composable
fun PreviewScreen(
    state: SyncState.Preview,
    contacts: ContactRepository,
    onToggle: (Long) -> Unit,
    onSelectAll: (Collection<Long>, Boolean) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by rememberSaveable { mutableStateOf(Filter.CHANGES) }
    val visible = state.items.filter {
        when (filter) {
            Filter.CHANGES -> it.status == PreviewStatus.NEW || it.status == PreviewStatus.REPLACE
            Filter.ACTIONABLE -> it.status.actionable
            Filter.ALL -> true
        }
    }
    val visibleSelectable = visible.filter { it.status.actionable }.map { it.contact.id }
    val counts = state.items.groupingBy { it.status }.eachCount()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Vorschau", style = MaterialTheme.typography.titleMedium)
        Text(
            PreviewStatus.entries.filter { (counts[it] ?: 0) > 0 }.joinToString(" · ") { "${it.label}: ${counts[it]}" },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Filter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.selected.size} ausgewählt",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSelectAll(visibleSelectable, true) }) { Text("Alle") }
            TextButton(onClick = { onSelectAll(visibleSelectable, false) }) { Text("Keine") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.contact.id }) { item ->
                PreviewRow(item, item.contact.id in state.selected, contacts) { onToggle(item.contact.id) }
                HorizontalDivider()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            Button(onClick = onApply, enabled = state.selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                Text("${state.selected.size} Fotos übernehmen")
            }
        }
    }
}

@Composable
private fun PreviewRow(item: PreviewItem, selected: Boolean, contacts: ContactRepository, onToggle: () -> Unit) {
    val selectable = item.status.actionable
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable, onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() }, enabled = selectable)
        Column(Modifier.weight(1f)) {
            Text(item.contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(item.status.label)
                    if (item.excluded) append(" · früher abgewählt")
                },
                style = MaterialTheme.typography.bodySmall,
                color = statusColor(item.status),
            )
        }
        Thumb(key = "c${item.contact.id}") {
            if (item.contact.hasPhoto) contacts.loadThumbnail(item.contact.id, THUMB_PX) else null
        }
        Text("→", color = MaterialTheme.colorScheme.outline)
        Thumb(key = item.image?.path) {
            item.image?.takeIf { it.exists() }?.let { decodeScaled(it.readBytes(), THUMB_PX) }
        }
    }
}

@Composable
private fun statusColor(status: PreviewStatus) = when (status) {
    PreviewStatus.NEW, PreviewStatus.REPLACE -> MaterialTheme.colorScheme.primary
    PreviewStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun Thumb(key: Any?, load: suspend () -> Bitmap?) {
    val bitmap by produceState<Bitmap?>(null, key) {
        value = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull() }
    }
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}
