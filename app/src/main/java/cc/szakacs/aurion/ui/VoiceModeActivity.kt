package cc.szakacs.aurion.ui

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.szakacs.aurion.brain.Brain
import cc.szakacs.aurion.data.LangMode
import cc.szakacs.aurion.data.Settings
import cc.szakacs.aurion.ui.theme.AurionColors
import cc.szakacs.aurion.ui.theme.AurionTheme
import cc.szakacs.aurion.voice.SpeechCallback
import cc.szakacs.aurion.voice.SpeechInput
import cc.szakacs.aurion.voice.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class VoiceModeState(
    val orb: OrbState = OrbState.IDLE,
    val level: Float = 0f,
    val you: String = "",
    val aurion: String = "",
    val muted: Boolean = false,
    val hint: String? = null,
)

/**
 * Gesprächsmodus wie bei ChatGPT: zuhören -> denken -> (live) sprechen -> wieder zuhören.
 * Antworten werden Satz für Satz vorgelesen, während das Modell noch schreibt.
 */
class VoiceModeViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val settings = Settings.get(ctx)
    private val brain = Brain.get(ctx)
    private val speech = SpeechInput(ctx)
    private val speaker = Speaker(ctx)
    private val _s = MutableStateFlow(VoiceModeState())
    val state: StateFlow<VoiceModeState> = _s
    private var silentRounds = 0
    private var turn = 0
    var onEnd: (() -> Unit)? = null

    private fun up(f: (VoiceModeState) -> VoiceModeState) { _s.value = f(_s.value) }

    fun listen() {
        if (_s.value.muted) return
        speaker.stop()
        up { it.copy(orb = OrbState.LISTENING, you = "", hint = null, level = 0f) }
        val lang = when (settings.langMode) { LangMode.DE -> "de"; LangMode.EN -> "en"; LangMode.AUTO -> null }
        speech.start(lang, settings.lastLang, object : SpeechCallback {
            override fun onLevel(level: Float) = up { it.copy(level = level) }
            override fun onPartial(text: String) = up { it.copy(you = text) }
            override fun onFinal(text: String) { silentRounds = 0; answer(text) }
            override fun onError(message: String) {
                // Stille: noch ein paar Mal weiter zuhören, dann pausieren
                silentRounds++
                if (silentRounds <= 2 && !_s.value.muted) viewModelScope.launch { delay(200); listen() }
                else up { it.copy(orb = OrbState.IDLE, level = 0f, hint = "Tippe auf den Orb, um weiterzusprechen") }
            }
        })
    }

    private fun answer(text: String) {
        if (Regex("^(tschüss|tschüs|ciao|servus|baba|ende|beenden|stopp|stop|bye|goodbye|danke,? das wars?|das wars?)[.!]*$", RegexOption.IGNORE_CASE).matches(text.trim())) {
            speaker.speak(if (settings.lastLang == "en") "Bye!" else "Bis bald!", settings.lastLang) { onEnd?.invoke() }
            up { it.copy(you = text, aurion = "Bis bald!", orb = OrbState.SPEAKING) }
            return
        }
        speech.stop()
        val myTurn = ++turn
        up { it.copy(you = text, aurion = "", orb = OrbState.THINKING, level = 0f) }
        var spokenUpTo = 0
        var started = false
        fun startSpeaking(lang: String) {
            if (started) return
            started = true
            up { it.copy(orb = OrbState.SPEAKING) }
            speaker.begin(lang) {
                viewModelScope.launch { if (turn == myTurn) listen() }
            }
        }
        viewModelScope.launch {
            val lang = settings.lastLang
            val reply = brain.handle(text, null, spoken = true, conversation = true) { partial ->
                if (turn != myTurn) return@handle
                val clean = plain(partial)
                up { it.copy(aurion = clean) }
                // fertige Sätze sofort vorlesen
                val cut = lastSentenceEnd(clean)
                if (cut > spokenUpTo) {
                    startSpeaking(settings.lastLang)
                    speaker.say(clean.substring(spokenUpTo, cut).trim())
                    spokenUpTo = cut
                }
            }
            if (turn != myTurn) return@launch
            val full = plain(reply.text)
            up { it.copy(aurion = full) }
            startSpeaking(reply.lang.ifBlank { lang })
            if (spokenUpTo < full.length) speaker.say(full.substring(spokenUpTo.coerceAtMost(full.length)).trim())
            speaker.end()
        }
    }

    /** Tippen auf den Orb: unterbrechen und zuhören / pausieren */
    fun tap() {
        when (_s.value.orb) {
            OrbState.SPEAKING, OrbState.THINKING -> { turn++; speaker.stop(); listen() }
            OrbState.LISTENING -> { speech.stop(); up { it.copy(orb = OrbState.IDLE, hint = "Pausiert – tippen zum Weitersprechen") } }
            OrbState.IDLE -> { up { it.copy(muted = false) }; silentRounds = 0; listen() }
        }
    }

    fun toggleMute() {
        val m = !_s.value.muted
        up { it.copy(muted = m) }
        if (m) { speech.stop(); if (_s.value.orb == OrbState.LISTENING) up { it.copy(orb = OrbState.IDLE, hint = "Mikrofon stumm") } }
        else if (_s.value.orb == OrbState.IDLE) listen()
    }

    fun stopAll() { turn++; speech.stop(); speaker.stop() }

    private fun lastSentenceEnd(s: String): Int {
        val m = Regex("[.!?…:;](\\s|$)|\\n").findAll(s).lastOrNull() ?: return 0
        // Ende erst nehmen, wenn danach schon weitergeschrieben wird (sonst evtl. Abkürzung/Zahl)
        return if (m.range.last + 1 < s.length) m.range.last + 1 else 0
    }

    private fun plain(s: String) = s
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")

    override fun onCleared() {
        stopAll()
        speaker.shutdown()
        super.onCleared()
    }
}

class VoiceModeActivity : ComponentActivity() {
    private val vm: VoiceModeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vm.onEnd = { finish() }
        setContent { AurionTheme { VoiceModeUi(vm) { finish() } } }
        if (savedInstanceState == null) vm.listen()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) { vm.stopAll(); finish() }
    }
}

@Composable
private fun VoiceModeUi(vm: VoiceModeViewModel, onClose: () -> Unit) {
    val st by vm.state.collectAsState()
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF040611), Color(0xFF0A1A44), Color(0xFF040611))))
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("AURION", color = AurionColors.Silver.copy(alpha = 0.5f), fontSize = 13.sp, letterSpacing = 6.sp)
            Spacer(Modifier.height(18.dp))
            // Was du sagst
            Text(
                st.you.ifBlank {
                    when (st.orb) {
                        OrbState.LISTENING -> "Ich höre zu …"
                        OrbState.THINKING -> ""
                        else -> st.hint ?: ""
                    }
                },
                color = if (st.you.isBlank()) AurionColors.TextLow else AurionColors.TextMid,
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(280.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.tap() },
                contentAlignment = Alignment.Center
            ) {
                AurionOrb(st.orb, if (st.orb == OrbState.SPEAKING) 0.6f else st.level, size = 280.dp)
            }
            Spacer(Modifier.weight(1f))
            // Aurions Antwort als Untertitel (letzte Zeilen)
            AnimatedContent(targetState = st.aurion.takeLast(260), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "cap") { txt ->
                Text(
                    txt, color = AurionColors.TextHigh, fontSize = 22.sp, lineHeight = 30.sp,
                    textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CircleButton(if (st.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (st.muted) "Stumm" else "Mikro", st.muted) { vm.toggleMute() }
                CircleButton(Icons.Rounded.Close, "Beenden", false) { vm.stopAll(); onClose() }
            }
        }
    }
}

@Composable
private fun CircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(if (active) AurionColors.Amber.copy(alpha = 0.3f) else Color(0x26FFFFFF))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(28.dp)) }
        Text(label, color = AurionColors.TextLow, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}
