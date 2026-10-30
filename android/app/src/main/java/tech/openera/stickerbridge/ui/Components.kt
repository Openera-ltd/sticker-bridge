package tech.openera.stickerbridge.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import tech.openera.stickerbridge.R
import tech.openera.stickerbridge.ui.theme.LocalExtra
import java.io.File

@Composable
fun Ico(@DrawableRes res: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface, size: Dp = 20.dp) {
    Icon(painterResource(res), contentDescription, modifier.size(size), tint = tint)
}

@Composable
fun LogoMark(size: Dp) {
    Image(painterResource(R.drawable.logo_mark), contentDescription = null, modifier = Modifier.size(size))
}

@Composable
fun StickerImage(file: File, contentDescription: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx)
            .data(file)
            .memoryCacheKey("${file.path}:${file.lastModified()}")
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, @DrawableRes trailing: Int? = null, @DrawableRes leading: Int? = null) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (leading != null) {
            Ico(leading, null, tint = MaterialTheme.colorScheme.onPrimary, size = 18.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Ico(trailing, null, tint = MaterialTheme.colorScheme.onPrimary, size = 18.dp)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = color) }
}

/** A soft panel for tips and statuses. */
@Composable
fun InfoPanel(
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    tone: Tone = Tone.Neutral,
    content: @Composable () -> Unit,
) {
    val extra = LocalExtra.current
    val (bg, fg) = when (tone) {
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.onSurfaceVariant
        Tone.Success -> extra.successContainer to extra.onSuccessContainer
        Tone.Accent -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Tone.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f) to MaterialTheme.colorScheme.error
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Ico(icon, null, tint = fg, size = 18.dp)
        Box(Modifier.weight(1f)) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides fg,
                androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium,
            ) { content() }
        }
    }
}

enum class Tone { Neutral, Success, Accent, Error }

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** A bordered group of rows, like Android settings. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column { content() } }
}

@Composable
fun ScreenHeader(title: String, subtitle: String?, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        action()
    }
}

@Composable
fun NewBadge(modifier: Modifier = Modifier) {
    Text(
        "New",
        color = MaterialTheme.colorScheme.onPrimary,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp()),
        modifier = modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun Double.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
