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
) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Packs", "Each pack holds up to 30. New ones start automatically.")
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { banner() }
            if (packs.isEmpty()) {
                item {
                    InfoPanel(icon = R.drawable.ic_info) {
                        Text("Your first pack appears once you've sent 3 stickers. WhatsApp needs at least 3 per pack.")
                    }
                }
            }
            items(packs, key = { it.id }) { p ->
                PackCard(p, dir, installed, status[p.id].orEmpty(), onAdd)
            }
            if (installed.isEmpty() && packs.any { it.ready }) {
                item {
                    InfoPanel(icon = R.drawable.ic_info) { Text("Install WhatsApp to add your packs.") }
                }
            }
        }
    }
}

@Composable
private fun PackCard(p: Pack, dir: File, installed: List<WaApp>, status: Map<WaApp, Boolean?>, onAdd: (Pack, WaApp) -> Unit) {
    Group {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "${p.stickers.size} / ${Pack.MAX}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (p.animated) "Animated stickers" else "Still stickers",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
                val kind = if (p.animated) "animated sticker" else "sticker"
                Text(
                    "Send ${p.missing} more $kind${if (p.missing > 1) "s" else ""} to unlock this pack.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                installed.forEach { app ->
                    if (status[app] == true) {
                        InfoPanel(icon = R.drawable.ic_check, tone = Tone.Success) {
                            Text("In ${app.label}. New stickers appear there automatically.")
                        }
                    } else {
                        PrimaryButton("Add to ${app.label}", { onAdd(p, app) }, leading = R.drawable.ic_plus)
                    }
                }
            }
        }
    }
}
