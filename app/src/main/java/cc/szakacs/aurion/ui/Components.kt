package cc.szakacs.aurion.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cc.szakacs.aurion.brain.CardKind
import cc.szakacs.aurion.brain.Reply
import cc.szakacs.aurion.ui.theme.AurionColors

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x10FFFFFF)))
            )
            .border(BorderStroke(1.dp, AurionColors.GlassBorder), RoundedCornerShape(24.dp))
            .padding(18.dp),
        content = content
    )
}

@Composable
fun ReplyCard(reply: Reply, modifier: Modifier = Modifier, showText: Boolean = true) {
    if (reply.weather != null) {
        Column(modifier) {
            WeatherCard(reply.weather, reply.weatherDay, Modifier.fillMaxWidth())
            if (showText) {
                Spacer(Modifier.size(12.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(reply.text, style = MaterialTheme.typography.bodyLarge, color = AurionColors.TextHigh)
                }
            }
        }
        return
    }
    GlassCard(modifier) {
        Text(reply.text, style = MaterialTheme.typography.bodyLarge, color = AurionColors.TextHigh)
        if (reply.card != CardKind.NONE && (reply.cardTitle != null || reply.cardLines.isNotEmpty())) {
            Spacer(Modifier.size(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0x33050A18))
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(
                        when (reply.card) {
                            CardKind.REMINDER -> Icons.Rounded.NotificationsActive
                            CardKind.SHOPPING -> Icons.Rounded.ShoppingBasket
                            CardKind.MEMORY -> Icons.Rounded.Psychology
                            CardKind.WEATHER -> Icons.Rounded.WbSunny
                            CardKind.NONE -> Icons.Rounded.AutoAwesome
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(reply.cardTitle ?: "", style = MaterialTheme.typography.titleMedium, color = AurionColors.TextHigh)
                }
                reply.cardLines.take(12).forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextMid, modifier = Modifier.padding(top = 6.dp, start = 42.dp))
                }
            }
        }
    }
}

@Composable
fun IconBubble(icon: ImageVector, size: Int = 32) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(AurionColors.Blue, AurionColors.Cyan))),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size((size * 0.56f).dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = AurionColors.TextLow,
        modifier = modifier.padding(start = 6.dp, top = 22.dp, bottom = 8.dp)
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AurionColors.Blue, contentColor = Color.White)
    ) { Text(text) }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, AurionColors.GlassBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AurionColors.TextHigh)
    ) { Text(text) }
}

@Composable
fun StatusRow(title: String, subtitle: String, ok: Boolean, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(if (ok) AurionColors.Cyan else AurionColors.Amber)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = AurionColors.TextHigh)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextMid)
            }
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.width(8.dp))
            SecondaryButton(action, onClick = onAction)
        }
    }
}
