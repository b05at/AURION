package cc.szakacs.aurion.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import cc.szakacs.aurion.assist.ScreenContext
import cc.szakacs.aurion.vision.Vision
import cc.szakacs.aurion.assist.AurionSession
import cc.szakacs.aurion.data.Settings
import cc.szakacs.aurion.ui.theme.AurionColors
import cc.szakacs.aurion.ui.theme.AurionTheme
import kotlinx.coroutines.delay

/**
 * Schwebendes Assistent-Overlay: unten ein leuchtender Orb, darüber Live-Transkript und Antwortkarte.
 * Tippen auf den freien Bereich schließt Aurion.
 */
class AssistActivity : ComponentActivity() {

    private val vm: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        if (!vm.hasMicPermission()) {
            // Erst einrichten
            startActivity(Intent(this, MainActivity::class.java).putExtra("tab", "settings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        vm.onConversationIdle = {
            // Nach der Antwort noch kurz sichtbar lassen – Schließen erfolgt manuell oder per Zeitlimit
        }

        setContent {
            AurionTheme {
                AssistOverlay(vm, onClose = { finish() }, onOpenApp = {
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                })
            }
        }
        if (savedInstanceState == null && Settings.get(this).listenOnOpen) vm.startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Seitentaste erneut gedrückt -> neu zuhören
        vm.reset()
        vm.startListening()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            vm.stop()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AurionSession.finishCurrent()
    }

    companion object {
        const val EXTRA_FROM_ASSIST = "fromAssist"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistOverlay(vm: AssistantViewModel, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val st by vm.state.collectAsState()
    val shot by ScreenContext.screenshot.collectAsState()
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Einkreisen
    val points = remember { mutableStateListOf<Offset>() }
    var selection by remember { mutableStateOf<Rect?>(null) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // Automatisch schließen, wenn längere Zeit nichts passiert (nicht während der Lupe)
    LaunchedEffect(st.orb, st.reply, typing, st.lens, shot) {
        if (st.orb == OrbState.IDLE && !typing && st.lens == null) {
            delay(if (shot != null) 25_000 else if (st.reply != null) 12_000 else 8_000)
            onClose()
        }
    }

    fun clearSelection() {
        points.clear(); selection = null; vm.closeLens()
    }

    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }) {
        val screenshot = shot
        if (screenshot != null) {
            // Eingefrorener Bildschirm – darauf kann eingekreist werden (wie "Circle to Search")
            // Hardware-Bitmap in eigener Ebene: wird nicht bei jedem Orb-Bild neu gezeichnet
            val shown = remember(screenshot) {
                runCatching { screenshot.copy(android.graphics.Bitmap.Config.HARDWARE, false) }.getOrNull() ?: screenshot
            }
            Image(shown.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize().graphicsLayer())
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(screenshot) {
                        detectTapGestures(onTap = { if (st.lens == null && selection == null) onClose() else clearSelection() })
                    }
                    .pointerInput(screenshot) {
                        detectDragGestures(
                            onDragStart = { points.clear(); points.add(it); selection = null; vm.closeLens() },
                            onDrag = { change, _ -> points.add(change.position) },
                            onDragEnd = {
                                if (points.size > 3) {
                                    val pad = 24f
                                    var r = Rect(
                                        points.minOf { it.x } - pad, points.minOf { it.y } - pad,
                                        points.maxOf { it.x } + pad, points.maxOf { it.y } + pad
                                    )
                                    // Zu kleine Auswahl (Strich) etwas aufziehen
                                    if (r.height < 80f) r = Rect(r.left, r.center.y - 60f, r.right, r.center.y + 60f)
                                    if (r.width < 80f) r = Rect(r.center.x - 60f, r.top, r.center.x + 60f, r.bottom)
                                    selection = r
                                    if (boxSize.width > 0) {
                                        val sx = screenshot.width.toFloat() / boxSize.width
                                        val sy = screenshot.height.toFloat() / boxSize.height
                                        val crop = Vision.crop(
                                            screenshot,
                                            android.graphics.Rect((r.left * sx).toInt(), (r.top * sy).toInt(), (r.right * sx).toInt(), (r.bottom * sy).toInt())
                                        )
                                        typing = false
                                        vm.analyzeImage(crop)
                                    }
                                }
                            }
                        )
                    }
            ) {
                SelectionLayer(points, selection)
            }
        } else {
            // Ohne Screenshot: Bildschirm bleibt unverändert, Tippen daneben schließt
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
            )
        }


        androidx.compose.runtime.CompositionLocalProvider(LocalSolidCards provides true) {
        Box(Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val lens = st.lens
                if (lens != null) {
                    LensCard(lens, vm, Modifier.fillMaxWidth()) { clearSelection() }
                    Spacer(Modifier.height(12.dp))
                } else {
                    st.reply?.let { reply ->
                        Box(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                            ReplyCard(reply, Modifier.fillMaxWidth(), onCheckItem = { vm.checkOffShopping(it) }, onConfirm = { yes -> vm.submit(if (yes) "Ja" else "Abbrechen") })
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                    st.streaming?.let { s ->
                        GlassCard(Modifier.fillMaxWidth()) { MarkdownText(s) }
                        Spacer(Modifier.height(14.dp))
                    }
                }

                // Nur echtes Transkript bzw. Fehlerhinweis – keine Platzhaltertexte
                val shown = when {
                    st.transcript.isNotBlank() && lens == null -> st.transcript
                    st.hint != null -> st.hint!!
                    else -> ""
                }
                if (shown.isNotEmpty()) {
                    Text(
                        shown,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
                        color = if (st.transcript.isNotBlank()) AurionColors.TextHigh else AurionColors.TextMid,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .shadow(12.dp, RoundedCornerShape(22.dp))
                            .clip(RoundedCornerShape(22.dp))
                            .background(Color(0xF2111829))
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                }

                if (typing) {
                    val fr = remember { FocusRequester() }
                    LaunchedEffect(Unit) { fr.requestFocus() }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(AurionColors.Glass, RoundedCornerShape(28.dp))
                            .padding(start = 4.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Frag Aurion …", color = AurionColors.TextLow) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                vm.submit(input); input = ""; typing = false
                            }),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.weight(1f).focusRequester(fr)
                        )
                        IconButton(onClick = { vm.submit(input); input = ""; typing = false }) {
                            Icon(Icons.AutoMirrored.Rounded.Send, "Senden", tint = AurionColors.Cyan)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                // Nur der Orb: Tippen = zuhören/stoppen, lange drücken = Tastatur
                Box(
                    Modifier
                        .size(if (lens != null) 96.dp else 150.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onLongClick = { typing = !typing; if (typing) vm.stop() },
                            onClick = { typing = false; vm.toggleListening() }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    AurionOrb(st.orb, level = { vm.level.value }, size = if (lens != null) 96.dp else 150.dp)
                }
            }
        }
        }
    }
}

/** Leuchtende Spur beim Einkreisen + abgedunkelter Rest nach der Auswahl */
@Composable
private fun SelectionLayer(points: List<Offset>, selection: Rect?) {
    val t = rememberInfiniteTransition(label = "sel")
    val phase by t.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "phase")
    Canvas(Modifier.fillMaxSize()) {
        if (selection != null) {
            val cut = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(selection, CornerRadius(28f))) }
            clipPath(cut, ClipOp.Difference) { drawRect(Color(0x8C000000)) }
            val c = selection.center
            val brush = ShaderBrush(
                android.graphics.SweepGradient(
                    c.x, c.y,
                    intArrayOf(AurionColors.Blue.toArgb(), AurionColors.Cyan.toArgb(), Color.White.toArgb(), AurionColors.Indigo.toArgb(), AurionColors.Blue.toArgb()),
                    null
                ).apply { setLocalMatrix(android.graphics.Matrix().apply { setRotate(phase, c.x, c.y) }) }
            )
            drawRoundRect(brush, selection.topLeft, selection.size, CornerRadius(28f), style = Stroke(width = 10f), alpha = 0.35f)
            drawRoundRect(brush, selection.topLeft, selection.size, CornerRadius(28f), style = Stroke(width = 4f))
        }
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            }
            val alpha = if (selection != null) 0.5f else 1f
            drawPath(path, AurionColors.Blue.copy(alpha = 0.35f * alpha), style = Stroke(width = 34f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, AurionColors.Cyan.copy(alpha = 0.7f * alpha), style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Color.White.copy(alpha = alpha), style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Bildschirmrand leuchtet in Aurion-Farben (rotierender Verlauf) */
@Composable
private fun EdgeGlow() {
    val t = rememberInfiniteTransition(label = "edge")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "rot")
    Canvas(Modifier.fillMaxSize()) {
        val colors = intArrayOf(
            AurionColors.Blue.toArgb(), AurionColors.Cyan.toArgb(), AurionColors.Silver.toArgb(),
            AurionColors.Indigo.toArgb(), AurionColors.Blue.toArgb()
        )
        val shader = android.graphics.SweepGradient(center.x, center.y, colors, null).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(rot, center.x, center.y) })
        }
        val brush = ShaderBrush(shader)
        for ((w, a) in listOf(40f to 0.16f, 20f to 0.32f, 7f to 0.95f)) {
            drawRoundRect(
                brush,
                topLeft = Offset(w / 2, w / 2),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(56f),
                style = Stroke(width = w),
                alpha = a
            )
        }
    }
}
