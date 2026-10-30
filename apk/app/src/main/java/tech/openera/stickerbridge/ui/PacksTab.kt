package tech.openera.stickerbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import tech.openera.stickerbridge.R
import tech.openera.stickerbridge.data.Pack
import tech.openera.stickerbridge.whatsapp.WaApp
import java.io.File

@Composable
fun PacksTab(
    packs: List<Pack>,
    dir: File,
    installed: List<WaApp>,
    status: Map<String, Map<WaApp, Boolean?>>,
    banner: @Composable () -> Unit,
    onAdd: (Pack, WaApp) -> Unit,
    onRename: (String, String) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
) {
    var renaming by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var deleting by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Packs", null)
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { banner() }
            if (packs.isEmpty()) {
                item {
                    InfoPanel(icon = R.drawable.ic_info) {
                        Text("Long-press stickers to make a pack")
                    }
                }
            }
            items(packs, key = { it.id }) { p ->
                Box(Modifier.animateItem()) {
                    PackCard(p, dir, installed, status[p.id].orEmpty(), onAdd, onRename = { renaming = p.id }, onDelete = { deleting = p.id })
                }
            }
            if (installed.isEmpty() && packs.any { it.ready }) {
                item {
                    InfoPanel(icon = R.drawable.ic_info) { Text("Install WhatsApp to add your packs.") }
                }
            }
            if (status.values.any { m -> m.values.any { it == true } }) {
                item {
                    // WhatsApp doesn't let other apps add favourites, so explain the quickest way.
                    InfoPanel(icon = R.drawable.ic_info) {
                        Text("Long-press a sticker in WhatsApp to add it to favourites")
                    }
                }
            }
        }
    }

    packs.firstOrNull { it.id == renaming }?.let { p ->
        var name by androidx.compose.runtime.saveable.rememberSaveable(p.id) {
            androidx.compose.runtime.mutableStateOf(p.name.removePrefix(tech.openera.stickerbridge.data.UserPack.PREFIX).removePrefix(" · "))
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename pack") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                    label = { Text("Pack name") }, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { onRename(p.id, name); renaming = null }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    packs.firstOrNull { it.id == deleting }?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${p.name}?") },
            text = { Text("The stickers stay in the app.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { onDelete(p.id); deleting = null }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PackCard(
    p: Pack, dir: File, installed: List<WaApp>, status: Map<WaApp, Boolean?>, onAdd: (Pack, WaApp) -> Unit,
    onRename: () -> Unit, onDelete: () -> Unit,
) {
    var menu by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Group {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(
                    "${p.stickers.size} / ${Pack.MAX}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    androidx.compose.material3.IconButton(onClick = { menu = true }) { Ico(R.drawable.ic_more, "Pack options") }
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val shown = p.stickers.takeLast(6).reversed()
                repeat(6) { i ->
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        shown.getOrNull(i)?.let { StickerImage(File(dir, it.fileName), null, Modifier.fillMaxSize().padding(4.dp)) }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            if (!p.ready) {
                LinearProgressIndicator(
                    progress = { p.stickers.size / Pack.MIN.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    strokeCap = StrokeCap.Round,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${p.missing} more to unlock",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                installed.forEach { app ->
                    if (status[app] == true) {
                        InfoPanel(icon = R.drawable.ic_check, tone = Tone.Success) {
                            Text("Added to ${app.label}")
                        }
                    } else {
                        PrimaryButton("Add to ${app.label}", { onAdd(p, app) }, leading = R.drawable.ic_plus)
                    }
                }
            }
        }
    }
}
