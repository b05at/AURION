package cc.szakacs.aurion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.szakacs.aurion.brain.ShoppingView
import cc.szakacs.aurion.bring.BringItem
import cc.szakacs.aurion.ui.theme.AurionColors
import kotlinx.coroutines.delay

/**
 * Animierte Einkaufslisten-Karte: Kacheln mit Symbolen, neue Artikel hüpfen herein und leuchten,
 * Antippen hakt einen Artikel in Bring! ab.
 */
@Composable
fun ShoppingCard(view: ShoppingView, onCheck: (String) -> Unit, modifier: Modifier = Modifier) {
    val checked = remember(view) { mutableStateListOf<String>() }
    val visibleItems = view.items.filter { it.name !in checked }
    // Neu hinzugefügte zuerst
    val sorted = visibleItems.sortedByDescending { it.name.lowercase() in view.added }

    val appear = remember { Animatable(0f) }
    LaunchedEffect(view) {
        appear.snapTo(0f)
        appear.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
    }
    val t = rememberInfiniteTransition(label = "shop")
    val sweep by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "sweep")

    Column(
        modifier
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 40f
                scaleX = 0.96f + 0.04f * appear.value
                scaleY = 0.96f + 0.04f * appear.value
            }
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0C2D57), Color(0xFF145C86), Color(0xFF1B7FA6))))
            .drawBehind {
                // wandernder Lichtschein
                val x = size.width * sweep
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color(0x1FFFFFFF), Color.Transparent),
                        start = Offset(x - 220f, 0f), end = Offset(x + 220f, size.height)
                    )
                )
            }
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
            .padding(16.dp)
    ) {
        // Kopf
        Row(verticalAlignment = Alignment.CenterVertically) {
            BouncingBasket(view.added.size + view.removed.size)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(view.listName, color = Color.White, style = MaterialTheme.typography.titleLarge)
                val sub = buildList {
                    add("${visibleItems.size} Artikel")
                    if (view.added.isNotEmpty()) add("+${view.added.size} neu")
                    if (view.removed.isNotEmpty()) add("−${view.removed.size} erledigt")
                }.joinToString(" · ")
                Text(sub, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color(0x33FFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                Text("${visibleItems.size}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }

        // Erledigt (entfernt)
        if (view.removed.isNotEmpty()) {
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                view.removed.take(4).forEach {
                    Text(
                        "✓ $it",
                        color = Color.White.copy(alpha = 0.55f),
                        textDecoration = TextDecoration.LineThrough,
                        fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x22000000)).padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Spacer(Modifier.size(12.dp))
        if (sorted.isEmpty()) {
            Text(
                "Alles erledigt – die Liste ist leer. 🎉",
                color = Color.White, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp), textAlign = TextAlign.Center
            )
        } else {
            // Kachel-Raster (3 Spalten)
            sorted.take(24).chunked(3).forEachIndexed { row, chunk ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chunk.forEachIndexed { col, item ->
                        val idx = row * 3 + col
                        Box(Modifier.weight(1f)) {
                            ItemTile(item, isNew = item.name.lowercase() in view.added, index = idx) {
                                checked += item.name
                                onCheck(item.name)
                            }
                        }
                    }
                    repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (sorted.size > 24) {
                Text("+ ${sorted.size - 24} weitere", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
            }
            Text(
                "Antippen zum Abhaken",
                color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp), textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ItemTile(item: BringItem, isNew: Boolean, index: Int, onCheck: () -> Unit) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(item.name) {
        delay(120L + index * 70L)
        pop.animateTo(1f, spring(dampingRatio = if (isNew) 0.45f else 0.8f, stiffness = Spring.StiffnessMediumLow))
    }
    val t = rememberInfiniteTransition(label = "new")
    val glow by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "glow")

    Column(
        Modifier
            .graphicsLayer {
                scaleX = pop.value; scaleY = pop.value
                alpha = pop.value.coerceIn(0f, 1f)
            }
            .fillMaxWidth()
            .aspectRatio(0.95f)
            .clip(RoundedCornerShape(18.dp))
            .background(if (isNew) Color(0x334FD8FF) else Color(0x1FFFFFFF))
            .border(
                if (isNew) 1.5.dp else 1.dp,
                if (isNew) AurionColors.Cyan.copy(alpha = glow) else Color(0x22FFFFFF),
                RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onCheck)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isNew) {
            Text(
                "NEU", color = Color(0xFF05243B), fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(AurionColors.Cyan).padding(horizontal = 5.dp, vertical = 1.dp)
            )
            Spacer(Modifier.size(2.dp))
        }
        Text(emojiFor(item.name), fontSize = 28.sp)
        Text(
            item.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, lineHeight = 15.sp
        )
        if (item.spec.isNotBlank()) {
            Text(item.spec, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BouncingBasket(changes: Int) {
    val bounce = remember { Animatable(0f) }
    LaunchedEffect(changes) {
        bounce.snapTo(-18f)
        bounce.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessLow))
    }
    Box(
        Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(AurionColors.Blue, AurionColors.Cyan))),
        contentAlignment = Alignment.Center
    ) {
        Text("🛒", fontSize = 26.sp, modifier = Modifier.graphicsLayer { translationY = bounce.value })
    }
}

/** Passendes Symbol zum Artikel (deutsch & englisch) */
fun emojiFor(name: String): String {
    val n = name.lowercase()
    val map = listOf(
        listOf("milch", "milk") to "🥛", listOf("ei", "eier", "egg") to "🥚", listOf("brot", "semmel", "gebäck", "toast", "bread", "brötchen", "weckerl") to "🍞",
        listOf("käse", "cheese") to "🧀", listOf("butter") to "🧈", listOf("joghurt", "jogurt", "yogurt", "topfen", "quark") to "🥣",
        listOf("apfel", "äpfel", "apple") to "🍏", listOf("banane", "banana") to "🍌", listOf("birne", "pear") to "🍐",
        listOf("zitrone", "lemon") to "🍋", listOf("orange", "mandarine") to "🍊", listOf("traube", "grape") to "🍇",
        listOf("erdbeer", "strawberr", "beere", "berr") to "🍓", listOf("tomate", "tomato", "paradeiser") to "🍅",
        listOf("kartoffel", "erdäpfel", "potato") to "🥔", listOf("zwiebel", "onion") to "🧅", listOf("knoblauch", "garlic") to "🧄",
        listOf("karotte", "möhre", "carrot") to "🥕", listOf("salat", "lettuce", "spinat") to "🥬", listOf("gurke", "cucumber") to "🥒",
        listOf("paprika", "pepper") to "🫑", listOf("brokkoli", "broccoli", "gemüse", "vegetable") to "🥦", listOf("pilz", "champignon", "mushroom") to "🍄",
        listOf("mais", "corn") to "🌽", listOf("avocado") to "🥑", listOf("obst", "fruit") to "🍎",
        listOf("fleisch", "rind", "schwein", "steak", "faschiert", "hack", "meat", "beef") to "🥩", listOf("huhn", "hähnchen", "hendl", "chicken", "pute") to "🍗",
        listOf("wurst", "schinken", "speck", "salami", "ham", "bacon", "sausage") to "🥓", listOf("fisch", "lachs", "thunfisch", "fish", "salmon") to "🐟",
        listOf("nudel", "pasta", "spaghetti") to "🍝", listOf("reis", "rice") to "🍚", listOf("mehl", "flour", "grieß") to "🌾",
        listOf("zucker", "sugar", "honig", "honey") to "🍯", listOf("salz", "salt", "gewürz", "pfeffer") to "🧂",
        listOf("öl", "oil", "essig") to "🫒", listOf("kaffee", "coffee") to "☕", listOf("tee", "tea") to "🍵",
        listOf("wasser", "mineral", "water") to "💧", listOf("saft", "juice") to "🧃", listOf("bier", "beer") to "🍺",
        listOf("wein", "sekt", "wine", "prosecco") to "🍷", listOf("cola", "limo", "soda") to "🥤",
        listOf("schoko", "chocolate", "süß", "candy", "gummi") to "🍫", listOf("keks", "kekse", "cookie") to "🍪", listOf("chips", "snack") to "🥨",
        listOf("eis", "ice cream") to "🍦", listOf("kuchen", "torte", "cake") to "🍰", listOf("pizza") to "🍕",
        listOf("müsli", "cereal", "haferflocken", "oats") to "🥣", listOf("marmelade", "jam", "nutella") to "🍯",
        listOf("klopapier", "toilettenpapier", "küchenrolle", "taschentuch", "toilet paper") to "🧻",
        listOf("seife", "duschgel", "shampoo", "soap", "zahnpasta", "deo") to "🧼", listOf("waschmittel", "spülmittel", "reiniger", "detergent") to "🧺",
        listOf("windel", "diaper") to "👶", listOf("katzenfutter", "hundefutter", "tierfutter", "pet food") to "🐾",
        listOf("batterie", "battery") to "🔋", listOf("blume", "flower") to "💐", listOf("medizin", "tabletten", "medicine") to "💊",
    )
    for ((keys, emoji) in map) {
        if (keys.any { k -> if (k.length <= 3) n == k || n.startsWith("$k ") || n.endsWith(" $k") else n.contains(k) }) return emoji
    }
    return "🛍️"
}
