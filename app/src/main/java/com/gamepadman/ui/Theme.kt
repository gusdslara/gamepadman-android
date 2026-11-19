package com.gamepadman.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Dark = darkColorScheme(
    primary = Color(0xFFA3E635),
    onPrimary = Color(0xFF13200A),
    primaryContainer = Color(0xFF2B3A16),
    onPrimaryContainer = Color(0xFFD9F99D),
    secondary = Color(0xFF9AA3AE),
    onSecondary = Color(0xFF101215),
    secondaryContainer = Color(0xFF262B33),
    onSecondaryContainer = Color(0xFFE6E9ED),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE8EBEF),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFE8EBEF),
    surfaceVariant = Color(0xFF1E2228),
    onSurfaceVariant = Color(0xFFA2AAB5),
    surfaceContainer = Color(0xFF16191E),
    surfaceContainerHigh = Color(0xFF1C2026),
    surfaceContainerHighest = Color(0xFF242930),
    outline = Color(0xFF3A414B),
    outlineVariant = Color(0xFF2A3038),
    error = Color(0xFFF87171),
    onError = Color(0xFF2B0A0A),
    errorContainer = Color(0xFF3B1517),
    onErrorContainer = Color(0xFFFECACA),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3F6212),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F5C4),
    onPrimaryContainer = Color(0xFF1A2E05),
    secondary = Color(0xFF55606C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E8EC),
    onSecondaryContainer = Color(0xFF1B1F24),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF15181C),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF15181C),
    surfaceVariant = Color(0xFFE6E9ED),
    onSurfaceVariant = Color(0xFF4F5863),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F2F4),
    surfaceContainerHighest = Color(0xFFE8EBEE),
    outline = Color(0xFFC3C9D0),
    outlineVariant = Color(0xFFDDE1E6),
    error = Color(0xFFC0262D),
    onError = Color.White,
    errorContainer = Color(0xFFFDE2E3),
    onErrorContainer = Color(0xFF5A0B10),
)

@Composable
fun PadTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

@Composable
fun Section(title: String, @DrawableRes icon: Int, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            content()
        }
    }
}

enum class Tone { OK, WARN, ERROR, IDLE }

@Composable
fun StatusPill(text: String, tone: Tone) {
    val cs = MaterialTheme.colorScheme
    val (bg, fg) = when (tone) {
        Tone.OK -> cs.primaryContainer to cs.onPrimaryContainer
        Tone.WARN -> Color(0x33EAB308) to (if (isSystemInDarkTheme()) Color(0xFFFDE68A) else Color(0xFF713F12))
        Tone.ERROR -> cs.errorContainer to cs.onErrorContainer
        Tone.IDLE -> cs.surfaceVariant to cs.onSurfaceVariant
    }
    Row(
        Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(fg, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** One setup requirement row: state icon, text and an optional action. */
@Composable
fun CheckRow(done: Boolean, title: String, detail: String, optional: Boolean = false, action: @Composable RowScope.() -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painterResource(if (done) com.gamepadman.R.drawable.ic_circle_check else com.gamepadman.R.drawable.ic_circle_alert),
            contentDescription = if (done) "Done" else "Needed",
            tint = if (done) cs.primary else if (optional) cs.onSurfaceVariant else Color(0xFFEAB308),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        action()
    }
}

@Composable
fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
