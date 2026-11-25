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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cc.szakacs.aurion.brain.CardKind
import cc.szakacs.aurion.brain.Reply
import cc.szakacs.aurion.ui.theme.AurionColors

/** true im Seitentasten-Overlay: Karten deckend statt durchscheinend, damit Text über jeder App lesbar ist */
val LocalSolidCards = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val solid = LocalSolidCards.current
    Column(
        modifier
            .then(if (solid) Modifier.shadow(16.dp, RoundedCornerShape(24.dp)) else Modifier)
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (solid) Brush.verticalGradient(listOf(Color(0xF7141B2E), Color(0xF70D1220)))
                else Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color(0x10FFFFFF)))
            )
            .border(BorderStroke(1.dp, AurionColors.GlassBorder), RoundedCornerShape(24.dp))
            .padding(18.dp),
        content = content
    )
}

@Composable
fun ReplyCard(
    reply: Reply,
    modifier: Modifier = Modifier,
    showText: Boolean = true,
    onCheckItem: ((String) -> Unit)? = null,
    onConfirm: ((Boolean) -> Unit)? = null,
) {
    if (reply.card == CardKind.MESSAGE) {
        MessageCard(reply, modifier, onConfirm)
        return
    }
    if (reply.card == CardKind.TIMER && reply.timerId != null || reply.card == CardKind.CALL) {
        Column(modifier) {
            if (showText) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(reply.text, style = MaterialTheme.typography.bodyLarge, color = AurionColors.TextHigh)
                }
                Spacer(Modifier.size(12.dp))
            }
            if (reply.card == CardKind.TIMER) TimerCard(reply.timerId!!, Modifier.fillMaxWidth())
            else CallCard(reply.contacts, reply.needsCallPermission, reply.cardTitle, Modifier.fillMaxWidth())
        }
        return
    }
    if (reply.shopping != null) {
        Column(modifier) {
            ShoppingCard(reply.shopping, onCheck = { onCheckItem?.invoke(it) }, modifier = Modifier.fillMaxWidth())
            if (showText) {
                Spacer(Modifier.size(12.dp))
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(reply.text, style = MaterialTheme.typography.bodyLarge, color = AurionColors.TextHigh)
                }
            }
        }
        return
    }
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
                            else -> Icons.Rounded.AutoAwesome
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

/** WhatsApp-Nachricht zur Bestätigung (Vorschau wie eine Sprechblase) */
@Composable
fun MessageCard(reply: Reply, modifier: Modifier = Modifier, onConfirm: ((Boolean) -> Unit)?) {
    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(AurionColors.Blue, AurionColors.Cyan))),
                contentAlignment = Alignment.Center
            ) { Text(reply.msgTo?.firstOrNull()?.uppercase() ?: "?", color = Color.White, style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(reply.msgTo ?: "WhatsApp", color = AurionColors.TextHigh, style = MaterialTheme.typography.titleMedium)
                Text("WhatsApp", color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium)
            }
        }
        reply.msgText?.let { msg ->
            Spacer(Modifier.size(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                Text(
                    msg, color = Color.White, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp))
                        .background(Brush.linearGradient(listOf(AurionColors.Blue, Color(0xFF3A66E8))))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        Spacer(Modifier.size(12.dp))
        when {
            reply.msgSent -> Text("✓ " + reply.text, color = AurionColors.Cyan, style = MaterialTheme.typography.bodyMedium)
            reply.msgText != null && onConfirm != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Senden", Modifier.weight(1f)) { onConfirm(true) }
                SecondaryButton("Abbrechen", Modifier.weight(1f)) { onConfirm(false) }
            }
            else -> Text(reply.text, color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium)
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
