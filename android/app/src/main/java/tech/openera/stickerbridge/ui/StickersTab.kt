package tech.openera.stickerbridge.ui

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.openera.stickerbridge.BuildConfig
import tech.openera.stickerbridge.R
import tech.openera.stickerbridge.data.LocalSticker
import tech.openera.stickerbridge.data.Pack
import tech.openera.stickerbridge.data.SyncStatus
import java.io.File

val EMOJI_CHOICES = listOf("😀", "😂", "🤣", "😍", "🥰", "😎", "🤔", "😐", "😱", "😭", "😡", "🥳", "👍", "👌", "🙌", "🙏", "💅", "💯", "🔥", "✨", "❤️", "💔", "👀", "🙈")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickersTab(
    stickers: List<LocalSticker>,
    packs: List<Pack>,
    dir: File,
    sync: SyncStatus,
    lastSyncAt: Long,
    banner: @Composable () -> Unit,
    onSync: () -> Unit,
    onRemove: (String) -> Unit,
    onEmojis: (String, List<String>) -> Unit,
    onSeen: () -> Unit,
) {
    val ctx = LocalContext.current
    val newest = remember(stickers) { stickers.sortedByDescending { it.addedAt } }
    val newCount = stickers.count { it.isNew }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val syncing = sync is SyncStatus.Running

    // Leaving the tab clears "New" badges.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { onSeen() } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Stickers",
            subtitle = when {
                syncing -> "Checking for new stickers…"
                stickers.isEmpty() -> "Nothing yet"
                else -> buildString {
                    append(stickers.size).append(if (stickers.size == 1) " sticker" else " stickers")
                    if (newCount > 0) append(" · ").append(newCount).append(" new")
                    if (lastSyncAt > 0) append(" · synced ").append(DateUtils.getRelativeTimeSpanString(lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase())
                }
            },
        ) {
            IconButton(onClick = onSync, enabled = !syncing) { Ico(R.drawable.ic_sync, "Check for new stickers") }
        }

        PullToRefreshBox(isRefreshing = syncing, onRefresh = onSync, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { banner() }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    InfoPanel(icon = R.drawable.ic_send) {
                        Text(buildAnnotatedString {
                            append("Send any sticker to ")
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                                append("@${BuildConfig.BOT_USERNAME}")
                            }
                            append(" on TikTok to add it here.")
                        })
                    }
                }
                if (stickers.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyState { openTikTokProfile(ctx) } }
                }
                items(newest, key = { it.id }) { s ->
                    StickerTile(s, File(dir, s.fileName)) { open = s.id }
                }
            }
        }
    }

    val current = stickers.firstOrNull { it.id == open }
    if (current != null) {
        StickerSheet(
            sticker = current,
            file = File(dir, current.fileName),
            packName = packs.firstOrNull { p -> p.stickers.any { it.id == current.id } }?.name,
            onDismiss = { open = null },
            onRemove = { onRemove(current.id); open = null },
            onEmojis = { onEmojis(current.id, it) },
        )
    }
}

@Composable
private fun StickerTile(s: LocalSticker, file: File, onClick: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = "Sticker details", onClick = onClick),
    ) {
        StickerImage(file, "Sticker ${s.emojis.joinToString(" ")}", Modifier.fillMaxSize().padding(10.dp))
        if (s.isNew) NewBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
        if (s.animated) {
            Text(
                "GIF",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LogoMark(48.dp)
        Spacer(Modifier.height(16.dp))
        Text("No stickers yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Send a sticker to @${BuildConfig.BOT_USERNAME} on TikTok. It shows up here within a minute.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        SecondaryButton("Open TikTok", onOpen)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun StickerSheet(
    sticker: LocalSticker,
    file: File,
    packName: String?,
    onDismiss: () -> Unit,
    onRemove: () -> Unit,
    onEmojis: (List<String>) -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var chosen by remember(sticker.id) { mutableStateOf(sticker.emojis) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(chosen) { if (chosen != sticker.emojis && chosen.isNotEmpty()) onEmojis(chosen) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Box(
                Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) { StickerImage(file, "Sticker preview", Modifier.size(168.dp)) }
            Spacer(Modifier.height(16.dp))
            Text(packName ?: "Not in a pack yet", style = MaterialTheme.typography.titleMedium)
            Text(
                if (sticker.animated) "Animated sticker" else "Still sticker · 512 × 512 WebP",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Text("Emojis", style = MaterialTheme.typography.titleSmall)
            Text(
                "Up to 3. WhatsApp uses them to find this sticker when you type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EMOJI_CHOICES.forEach { e ->
                    val on = e in chosen
                    Surface(
                        onClick = {
                            chosen = when {
                                on && chosen.size > 1 -> chosen - e
                                on -> chosen
                                chosen.size >= 3 -> chosen.drop(1) + e
                                else -> chosen + e
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(44.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            if (confirm) {
                InfoPanel(tone = Tone.Error) { Text("Remove this sticker from the app and its WhatsApp pack?") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Cancel", { confirm = false }, Modifier.weight(1f))
                    SecondaryButton("Remove", onRemove, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { confirm = true }) {
                    Ico(R.drawable.ic_trash, null, tint = MaterialTheme.colorScheme.error, size = 18.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Remove sticker", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
