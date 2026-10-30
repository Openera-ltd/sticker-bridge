package tech.openera.stickerbridge.ui

import android.Manifest
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.openera.stickerbridge.BuildConfig
import tech.openera.stickerbridge.R
import tech.openera.stickerbridge.data.AppSettings
import tech.openera.stickerbridge.data.Session
import tech.openera.stickerbridge.data.ThemeMode

private enum class Dialog { None, Interval, PackName, Emoji, Disconnect, Delete }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    s: AppSettings,
    session: Session,
    banner: @Composable () -> Unit,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onSyncNow: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDisconnect: () -> Unit,
    onDeleteAll: () -> Unit,
) {
    var dialog by remember { mutableStateOf(Dialog.None) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onChange { it.copy(notifyNewStickers = granted) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Settings", null)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)
        ) {
            banner()

            SectionLabel("Account")
            Group {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            (session.username ?: "?").take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("@${session.username ?: "unknown"}", style = MaterialTheme.typography.titleMedium)
                        Text("Connected TikTok account", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Divider()
                NavRow("Disconnect", null) { dialog = Dialog.Disconnect }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Sync")
            Group {
                SwitchRow("Sync automatically", "New stickers go straight into your WhatsApp pack.", s.autoSync) { v -> onChange { it.copy(autoSync = v) } }
                Divider()
                NavRow("Check every", intervalLabel(s.syncIntervalMin), enabled = s.autoSync) { dialog = Dialog.Interval }
                Divider()
                SwitchRow("Wi-Fi only", "Don't use mobile data for syncing.", s.wifiOnly, enabled = s.autoSync) { v -> onChange { it.copy(wifiOnly = v) } }
                Divider()
                SwitchRow("Notify me", "When new stickers arrive.", s.notifyNewStickers) { v ->
                    if (v && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else onChange { it.copy(notifyNewStickers = v) }
                }
                Divider()
                NavRow(
                    "Sync now",
                    if (session.lastSyncAt > 0) "Last " + DateUtils.getRelativeTimeSpanString(session.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase() else "Never",
                    onClick = onSyncNow,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Stickers")
            Group {
                SwitchRow("White outline", "The classic sticker border. Changing it redoes all stickers.", s.outline) { v -> onChange { it.copy(outline = v) } }
                Divider()
                NavRow("Pack name", s.packName) { dialog = Dialog.PackName }
                Divider()
                NavRow("Default emoji", s.defaultEmoji) { dialog = Dialog.Emoji }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Appearance")
            Group {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { i, m ->
                            SegmentedButton(
                                selected = s.theme == m,
                                onClick = { onChange { it.copy(theme = m) } },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                icon = {},
                            ) { Text(m.name) }
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    Divider()
                    SwitchRow("Wallpaper colours", "Match the app to your phone's colours.", s.wallpaperColors) { v -> onChange { it.copy(wallpaperColors = v) } }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Updates")
            Group {
                SwitchRow("Check automatically", "Once a day. You always choose when to install.", s.autoUpdateCheck) { v -> onChange { it.copy(autoUpdateCheck = v) } }
                Divider()
                NavRow("Check for updates", "Version ${BuildConfig.VERSION_NAME}", onClick = onCheckUpdate)
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("Privacy")
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("Stickers are kept for 30 days", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Then deleted from our server. Copies on this phone stay until you remove them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Divider()
                NavRow("Delete all my data", null, danger = true) { dialog = Dialog.Delete }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Sticker Bridge ${BuildConfig.VERSION_NAME} · ${BuildConfig.API_BASE.removePrefix("https://").trimEnd('/')}\nNot affiliated with TikTok or WhatsApp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    when (dialog) {
        Dialog.Interval -> ChoiceDialog(
            title = "Check for new stickers",
            options = AppSettings.INTERVALS,
            selected = s.syncIntervalMin,
            label = ::intervalLabel,
            onPick = { v -> onChange { it.copy(syncIntervalMin = v) }; dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        Dialog.PackName -> TextDialog("Pack name", s.packName, { v -> onChange { it.copy(packName = v) }; dialog = Dialog.None }) { dialog = Dialog.None }
        Dialog.Emoji -> EmojiDialog(s.defaultEmoji, { v -> onChange { it.copy(defaultEmoji = v) }; dialog = Dialog.None }) { dialog = Dialog.None }
        Dialog.Disconnect -> ConfirmDialog(
            "Disconnect TikTok?",
            "Stickers already on this phone stay, and your WhatsApp packs keep working. New stickers stop arriving until you connect again.",
            "Disconnect",
            danger = false,
            onConfirm = { onDisconnect(); dialog = Dialog.None },
        ) { dialog = Dialog.None }
        Dialog.Delete -> ConfirmDialog(
            "Delete all your data?",
            "Removes every sticker from this phone and our server, and disconnects TikTok. Packs disappear from WhatsApp. This can't be undone.",
            "Delete everything",
            danger = true,
            onConfirm = { onDeleteAll(); dialog = Dialog.None },
        ) { dialog = Dialog.None }
        Dialog.None -> Unit
    }
}

fun intervalLabel(min: Int): String = when {
    min < 60 -> "$min minutes"
    min == 60 -> "1 hour"
    else -> "${min / 60} hours"
}

@Composable
private fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun NavRow(title: String, value: String?, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                danger -> MaterialTheme.colorScheme.error
                enabled -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.size(4.dp))
        }
        if (!danger) Ico(R.drawable.ic_chevron, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 18.dp)
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { o ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .selectable(selected = o == selected, role = Role.RadioButton) { onPick(o) }
                            .heightIn(min = 48.dp).padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == selected, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Text(label(o), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextDialog(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(60) },
                singleLine = true,
                label = { Text("Name shown in WhatsApp") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Default emoji") },
        text = {
            Column {
                Text(
                    "Used for new stickers. WhatsApp needs at least one emoji per sticker.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EMOJI_CHOICES.forEach { e ->
                        val on = e == current
                        Surface(
                            onClick = { onPick(e) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.size(44.dp),
                        ) { Box(contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, danger: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(action, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
