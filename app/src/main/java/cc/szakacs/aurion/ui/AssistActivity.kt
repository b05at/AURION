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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

@Composable
private fun AssistOverlay(vm: AssistantViewModel, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val st by vm.state.collectAsState()
    var typing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    // Automatisch schließen, wenn längere Zeit nichts passiert
    LaunchedEffect(st.orb, st.reply, typing) {
        if (st.orb == OrbState.IDLE && !typing) {
            delay(if (st.reply != null) 12_000 else 8_000)
            onClose()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
    ) {
        // Abdunkelnder Verlauf von unten
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.35f to Color(0x66000000),
                        1f to Color(0xF2050814),
                    )
                )
        )

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(250)) + slideInVertically(tween(350)) { it / 3 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                st.reply?.let { reply ->
                    ReplyCard(reply, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(18.dp))
                }

                val shown = when {
                    st.transcript.isNotBlank() -> st.transcript
                    st.hint != null -> st.hint!!
                    st.orb == OrbState.LISTENING -> "Ich höre zu …"
                    st.reply == null -> "Wie kann ich helfen?"
                    else -> ""
                }
                if (shown.isNotEmpty()) {
                    Text(
                        shown,
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (st.transcript.isNotBlank()) AurionColors.TextHigh else AurionColors.TextMid,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                    )
                    Spacer(Modifier.height(12.dp))
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
                    Spacer(Modifier.height(12.dp))
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { typing = !typing; if (typing) vm.stop() }) {
                        Icon(Icons.Rounded.Keyboard, "Tippen", tint = AurionColors.TextMid)
                    }
                    Box(
                        Modifier
                            .size(150.dp)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                typing = false; vm.toggleListening()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        AurionOrb(st.orb, st.level, size = 150.dp)
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, "Schließen", tint = AurionColors.TextMid)
                    }
                }
                Text(
                    "AURION",
                    style = MaterialTheme.typography.labelLarge.copy(letterSpacing = MaterialTheme.typography.labelLarge.fontSize * 0.5f),
                    color = AurionColors.Silver.copy(alpha = 0.6f),
                    modifier = Modifier.clickable { onOpenApp() }.padding(6.dp)
                )
            }
        }
    }
}
