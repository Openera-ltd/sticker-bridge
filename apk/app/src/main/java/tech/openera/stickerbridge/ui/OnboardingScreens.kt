package tech.openera.stickerbridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tech.openera.stickerbridge.BuildConfig
import tech.openera.stickerbridge.R

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 24.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(24.dp))
            LogoMark(56.dp)
            Spacer(Modifier.height(28.dp))
            Text("Your TikTok stickers, in WhatsApp.", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(36.dp))
            Step(1, "Connect TikTok once")
            Spacer(Modifier.height(20.dp))
            Step(2, "Send stickers to @${BuildConfig.BOT_USERNAME}")
            Spacer(Modifier.height(20.dp))
            Step(3, "They sync to WhatsApp")
            Spacer(Modifier.height(24.dp))
        }
        PrimaryButton("Get started", onStart)
        Spacer(Modifier.height(14.dp))
        Text(
            "Not affiliated with TikTok or WhatsApp.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun Step(n: Int, title: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(
            shape = CircleShape,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(26.dp),
        ) {
            Text(
                "$n",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Opens @stickerbridge straight in the TikTok app (either TikTok package), or the browser if TikTok isn't installed. */
fun openTikTokProfile(context: Context) {
    val url = Uri.parse("https://www.tiktok.com/@${BuildConfig.BOT_USERNAME}")
    for (pkg in listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")) {
        val inApp = Intent(Intent.ACTION_VIEW, url).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(inApp) }.isSuccess) return
    }
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Sticker Bridge code", text))
}

@Composable
fun ConnectScreen(link: LinkUi, onBack: () -> Unit, onStart: () -> Unit, onOpened: () -> Unit, onContinue: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { if (link is LinkUi.Idle) onStart() }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
        Row(Modifier.height(56.dp).padding(start = 0.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) { Ico(R.drawable.ic_back, "Back") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text("Connect TikTok", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(10.dp))
            Text(
                buildAnnotatedString {
                    append("Send this code to ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                        append("@${BuildConfig.BOT_USERNAME}")
                    }
                    append(" on TikTok")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(28.dp))

            AnimatedContent(targetState = link, label = "link", contentKey = { it::class }) { state ->
                when (state) {
                    is LinkUi.Idle, is LinkUi.Loading -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                    }
                    is LinkUi.Code -> Column {
                        CodeCard(state.start.code) {
                            copy(ctx, state.start.code)
                        }
                        Spacer(Modifier.height(10.dp))
                        ExpiryText(state.start.expiresAt)
                        if (state.waiting) {
                            Spacer(Modifier.height(20.dp))
                            InfoPanel(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text("Waiting for your message…")
                                }
                            }
                        }
                    }
                    is LinkUi.Linked -> InfoPanel(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, icon = R.drawable.ic_check, tone = Tone.Success) {
                        Text(buildAnnotatedString {
                            append("Connected as ")
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("@${state.username}") }
                        })
                    }
                    is LinkUi.Expired -> InfoPanel(icon = R.drawable.ic_info) { Text("That code expired. Get a new one below.") }
                    is LinkUi.Error -> InfoPanel(icon = R.drawable.ic_info, tone = Tone.Error) { Text(state.message) }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        when (link) {
            is LinkUi.Linked -> PrimaryButton("Continue", onContinue)
            is LinkUi.Expired, is LinkUi.Error -> PrimaryButton("Get a new code", onStart)
            else -> {
                PrimaryButton(
                    "Open TikTok",
                    { onOpened(); openTikTokProfile(ctx) },
                    enabled = link is LinkUi.Code,
                    trailing = R.drawable.ic_open,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CodeCard(code: String, onCopy: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(code, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
            }
            OutlinedButton(onClick = { onCopy(); copied = true }, shape = RoundedCornerShape(10.dp)) {
                Ico(if (copied) R.drawable.ic_check else R.drawable.ic_copy, null, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Copied" else "Copy", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ExpiryText(expiresAt: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAt) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val left = ((expiresAt - now) / 1000).coerceAtLeast(0)
    Text(
        "Expires in %d:%02d · works once".format(left / 60, left % 60),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun TextLink(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text) }
}
