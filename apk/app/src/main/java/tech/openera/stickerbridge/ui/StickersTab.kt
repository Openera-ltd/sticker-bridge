package tech.openera.stickerbridge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.openera.stickerbridge.BuildConfig
import tech.openera.stickerbridge.R
import tech.openera.stickerbridge.data.LocalSticker
import tech.openera.stickerbridge.data.Pack
import tech.openera.stickerbridge.data.SyncStatus
import tech.openera.stickerbridge.data.UserPack
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickersTab(
    stickers: List<LocalSticker>,
    packs: List<Pack>,
    dir: File,
    sync: SyncStatus,
    banner: @Composable () -> Unit,
    onSync: () -> Unit,
    onRemove: (String) -> Unit,
    onSend: (File) -> Unit,
    onCreatePack: (String, List<String>) -> Unit,
    onAddToPack: (String, List<String>) -> Unit,
    onSeen: () -> Unit,
) {
    val ctx = LocalContext.current
    val newest = remember(stickers) { stickers.sortedByDescending { it.addedAt } }
    val newCount = stickers.count { it.isNew }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) } // "new" | "add"
    val selecting = selected.isNotEmpty()
    val syncing = sync is SyncStatus.Running

    // Leaving the tab clears "New" badges.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onSeen() } }
    BackHandler(enabled = selecting) { selected = emptyList() }

    fun toggle(id: String) {
        selected = if (id in selected) selected - id else selected + id
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            ScreenHeader(title = "${selected.size} selected", subtitle = null) {
                IconButton(onClick = { selected = emptyList() }) { Ico(R.drawable.ic_close, "Cancel selection") }
            }
        } else {
            ScreenHeader(
                title = "Stickers",
                subtitle = when {
                    stickers.isEmpty() -> null
                    newCount > 0 -> "${stickers.size} · $newCount new"
                    else -> "${stickers.size}"
                },
            ) {
                IconButton(onClick = onSync, enabled = !syncing) { Ico(R.drawable.ic_sync, "Check for new stickers") }
            }
        }

        PullToRefreshBox(isRefreshing = syncing, onRefresh = onSync, modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (!selecting) {
                    item(span = { GridItemSpan(maxLineSpan) }) { banner() }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PrimaryButton("Send stickers to @${BuildConfig.BOT_USERNAME}", { openTikTokProfile(ctx) }, leading = R.drawable.ic_send)
                    }
                }
                if (stickers.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState() }
                }
                items(newest, key = { it.id }) { s ->
                    Box(Modifier.animateItem()) {
                        StickerTile(
                            s, File(dir, s.fileName), selected = s.id in selected,
                            onClick = { if (selecting) toggle(s.id) else open = s.id },
                            onLongClick = { toggle(s.id) },
                        )
                    }
                }
            }
        }

        if (selecting) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryButton("New pack", { dialog = "new" }, Modifier.weight(1f), leading = R.drawable.ic_plus)
                if (packs.isNotEmpty()) SecondaryButton("Add to pack", { dialog = "add" }, Modifier.weight(1f).heightIn(min = 52.dp))
            }
        }
    }

    when (dialog) {
        "new" -> NewPackDialog(
            count = selected.size,
            onCreate = { name -> onCreatePack(name, selected); selected = emptyList(); dialog = null },
            onDismiss = { dialog = null },
        )
        "add" -> AddToPackDialog(
            packs = packs,
            onPick = { id -> onAddToPack(id, selected); selected = emptyList(); dialog = null },
            onDismiss = { dialog = null },
        )
    }

    val current = stickers.firstOrNull { it.id == open }
    if (current != null) {
        StickerSheet(
            file = File(dir, current.fileName),
            inPacks = packs.filter { p -> p.stickers.any { it.id == current.id } }.map { it.name },
            onDismiss = { open = null },
            onSend = { onSend(File(dir, current.fileName)) },
            onRemove = { onRemove(current.id); open = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StickerTile(s: LocalSticker, file: File, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
            .combinedClickable(onClickLabel = "Open sticker", onLongClickLabel = "Select", onClick = onClick, onLongClick = onLongClick),
    ) {
        StickerImage(file, if (s.animated) "Animated sticker" else "Sticker", Modifier.fillMaxSize().padding(10.dp))
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Ico(R.drawable.ic_check, null, tint = MaterialTheme.colorScheme.onPrimary, size = 14.dp) }
        } else if (s.isNew) {
            NewBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

@Composable
private fun EmptyState() {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LogoMark(48.dp)
        Spacer(Modifier.height(16.dp))
        Text("No stickers yet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NewPackDialog(count: Int, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New pack") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    singleLine = true,
                    label = { Text("Pack name") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "${UserPack.displayName(name)} · $count",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddToPackDialog(packs: List<Pack>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to pack") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                packs.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .selectable(selected = false, role = Role.RadioButton) { onPick(p.id) }
                            .heightIn(min = 52.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = false, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.stickers.size} / ${Pack.MAX}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StickerSheet(
    file: File,
    inPacks: List<String>,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    onRemove: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirm by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Box(
                Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) { StickerImage(file, "Sticker preview", Modifier.size(184.dp)) }
            if (inPacks.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(inPacks.joinToString("\n"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Send to WhatsApp", onSend, leading = R.drawable.ic_send)
            Spacer(Modifier.height(8.dp))
            if (confirm) {
                InfoPanel(tone = Tone.Error) { Text("Remove this sticker?") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Cancel", { confirm = false }, Modifier.weight(1f))
                    SecondaryButton("Remove", onRemove, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { confirm = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Ico(R.drawable.ic_trash, null, tint = MaterialTheme.colorScheme.error, size = 18.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Remove sticker", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
