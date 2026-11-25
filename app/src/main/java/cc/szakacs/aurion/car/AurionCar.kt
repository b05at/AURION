package cc.szakacs.aurion.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.media.CarAudioRecord
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cc.szakacs.aurion.brain.Brain
import cc.szakacs.aurion.bring.BringClient
import cc.szakacs.aurion.data.Settings
import cc.szakacs.aurion.data.Store
import cc.szakacs.aurion.reminders.Reminders
import cc.szakacs.aurion.speech.FloatList
import cc.szakacs.aurion.speech.Packs
import cc.szakacs.aurion.speech.WhisperEngine
import cc.szakacs.aurion.timers.Timers
import cc.szakacs.aurion.voice.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/** Android Auto: Aurion als Auto-App (Vorlagen der Car App Library). */
class AurionCarAppService : CarAppService() {
    // Seitengeladene App: alle Hosts (Android Auto) zulassen
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = AurionCarSession()
}

class AurionCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = HomeScreen(carContext)
}

/** Startseite im Auto */
class HomeScreen(ctx: CarContext) : Screen(ctx) {
    private var ticker: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                // Timer-Restzeiten regelmäßig auffrischen
                ticker = lifecycleScope.launch { while (isActive) { delay(5000); if (Timers.timers.value.isNotEmpty()) invalidate() } }
            }
            override fun onStop(owner: LifecycleOwner) { ticker?.cancel() }
        })
    }

    override fun onGetTemplate(): Template {
        Timers.load(carContext)
        val items = ItemList.Builder()
        items.addItem(
            Row.Builder()
                .setTitle("🎙 Mit Aurion sprechen")
                .addText("Frag einfach – ich höre über das Automikrofon zu")
                .setBrowsable(true)
                .setOnClickListener { screenManager.push(VoiceScreen(carContext)) }
                .build()
        )
        Timers.timers.value.take(2).forEach { t ->
            items.addItem(
                Row.Builder()
                    .setTitle("⏱ ${t.label ?: "Timer"} – " + if (t.ringing) "abgelaufen" else Timers.format(t.remaining()))
                    .addText(if (t.ringing) "Tippen zum Stoppen" else if (t.paused) "Pausiert" else "Läuft")
                    .setOnClickListener { if (t.ringing) Timers.stop(carContext, t.id); invalidate() }
                    .build()
            )
        }
        val next = Store.get(carContext).reminders.value.filter { !it.acknowledged }.take(2)
        next.forEach { r ->
            items.addItem(
                Row.Builder()
                    .setTitle("🔔 ${r.text}")
                    .addText(Reminders.formatWhen(carContext, r.triggerAt) + if (r.fired) " · tippen zum Quittieren" else "")
                    .setOnClickListener { if (r.fired) { Reminders.acknowledge(carContext, r.id); invalidate() } }
                    .build()
            )
        }
        items.addItem(
            Row.Builder().setTitle("🛒 Einkaufsliste").setBrowsable(true)
                .setOnClickListener { screenManager.push(ShoppingScreen(carContext)) }.build()
        )
        items.addItem(
            Row.Builder().setTitle("☀ Wetter").setBrowsable(true)
                .setOnClickListener { screenManager.push(AnswerScreen(carContext, "Wie ist das Wetter heute und morgen?")) }.build()
        )
        return ListTemplate.Builder()
            .setTitle("Aurion")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(items.build())
            .build()
    }
}

/** Zeigt eine Antwort auf eine feste Frage (z. B. Wetter) und liest sie vor */
class AnswerScreen(ctx: CarContext, private val question: String) : Screen(ctx) {
    private var text = "Einen Moment …"
    private val speaker = Speaker(ctx)

    init {
        lifecycleScope.launch {
            val reply = runCatching { Brain.get(carContext).handle(question, spoken = true) }.getOrNull()
            text = reply?.text ?: "Das hat leider nicht geklappt."
            invalidate()
            reply?.let { speaker.speak(it.text, it.lang) }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { speaker.shutdown() }
        })
    }

    override fun onGetTemplate(): Template =
        MessageTemplate.Builder(text)
            .setTitle("Aurion")
            .setHeaderAction(Action.BACK)
            .addAction(Action.Builder().setTitle("🎙 Nachfragen").setOnClickListener { screenManager.push(VoiceScreen(carContext)) }.build())
            .build()
}

/** Einkaufsliste (Bring!) – Antippen hakt ab */
class ShoppingScreen(ctx: CarContext) : Screen(ctx) {
    private var items: List<cc.szakacs.aurion.bring.BringItem>? = null
    private var error: String? = null
    private val bring = BringClient(Settings.get(ctx))

    init { load() }

    private fun load() {
        lifecycleScope.launch {
            runCatching { bring.items() }.onSuccess { items = it }.onFailure { error = it.message ?: "Bring! nicht erreichbar" }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val b = ListTemplate.Builder().setTitle(Settings.get(carContext).bringListName ?: "Einkaufsliste").setHeaderAction(Action.BACK)
        val list = items
        if (list == null && error == null) return b.setLoading(true).build()
        val il = ItemList.Builder().setNoItemsMessage(error ?: "Die Liste ist leer 🎉")
        list.orEmpty().take(30).forEach { item ->
            il.addItem(
                Row.Builder().setTitle(item.name).apply { if (item.spec.isNotBlank()) addText(item.spec) }
                    .setOnClickListener {
                        lifecycleScope.launch { runCatching { bring.removeItem(item.name) }; load() }
                    }.build()
            )
        }
        return b.setSingleList(il.build()).build()
    }
}

/**
 * Sprachgespräch im Auto: Automikrofon (CarAudioRecord) -> Whisper (lokal) -> Aurion -> Sprachausgabe.
 * Danach wird automatisch wieder zugehört, bis eine Weile niemand spricht.
 */
class VoiceScreen(ctx: CarContext) : Screen(ctx) {
    private enum class Phase { LISTENING, TRANSCRIBING, THINKING, SPEAKING, IDLE, ERROR }

    private var phase = Phase.IDLE
    private var you = ""
    private var answer = ""
    private var message = ""
    private val speaker = Speaker(ctx)
    private var job: Job? = null
    private var focus: AudioFocusRequest? = null
    private var silentRounds = 0

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { if (phase == Phase.IDLE) listen() }
            override fun onDestroy(owner: LifecycleOwner) { job?.cancel(); speaker.shutdown(); releaseFocus() }
        })
    }

    private fun set(p: Phase, msg: String? = null) {
        phase = p
        msg?.let { message = it }
        invalidate()
    }

    private fun listen() {
        val pack = Packs.firstInstalledWhisper(carContext)
        if (pack == null) {
            set(Phase.ERROR, "Für Sprache im Auto bitte in der Aurion-App unter Einstellungen › Spracherkennung „Whisper Base“ laden. Alles läuft dann lokal.")
            return
        }
        if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            carContext.requestPermissions(listOf(Manifest.permission.RECORD_AUDIO)) { granted, _ ->
                if (granted.isNotEmpty()) listen() else set(Phase.ERROR, "Mikrofon-Berechtigung fehlt.")
            }
            return
        }
        if (carContext.carAppApiLevel < 5) {
            set(Phase.ERROR, "Dein Android Auto unterstützt das Automikrofon für Apps noch nicht. Bitte Android Auto aktualisieren.")
            return
        }
        speaker.stop()
        job?.cancel()
        job = lifecycleScope.launch {
            set(Phase.LISTENING, "Ich höre zu …")
            val samples = withContext(Dispatchers.IO) { record() }
            if (samples == null) {
                silentRounds++
                if (silentRounds <= 1) { listen() } else set(Phase.IDLE, "Tippe auf „Sprechen“, wenn du noch etwas brauchst.")
                return@launch
            }
            silentRounds = 0
            set(Phase.TRANSCRIBING, "Verstehe …")
            val lang = Settings.get(carContext).lastLang.let { if (Settings.get(carContext).langMode == cc.szakacs.aurion.data.LangMode.AUTO) "" else it }
            val text = runCatching { WhisperEngine.transcribe(carContext, pack.id, samples, lang) { _, _ -> } }.getOrDefault("")
            if (text.isBlank()) { set(Phase.IDLE, "Das habe ich nicht verstanden – nochmal?"); return@launch }
            you = text
            if (Regex("^(tschüss|ende|beenden|stopp|danke,? das wars?|bye)[.!]*$", RegexOption.IGNORE_CASE).matches(text.trim())) {
                screenManager.pop(); return@launch
            }
            set(Phase.THINKING, "Denke nach …")
            val reply = runCatching { Brain.get(carContext).handle(text, spoken = true, conversation = true) }.getOrNull()
            answer = reply?.text ?: "Das hat leider nicht geklappt."
            set(Phase.SPEAKING)
            speaker.speak(answer, reply?.lang ?: "de") {
                // weiter im Gespräch
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) listen()
            }
        }
    }

    /** Nimmt auf, bis nach dem Sprechen ~1,2 s Stille herrscht (max. 20 s). null = niemand hat gesprochen. */
    private suspend fun record(): FloatArray? {
        requestFocus()
        val rec = CarAudioRecord.create(carContext)
        val out = FloatList(16000 * 20)
        val buf = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
        var spoke = false
        var silentMs = 0
        var totalMs = 0
        try {
            rec.startRecording()
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                var sum = 0.0
                var i = 0
                while (i + 1 < n) {
                    val v = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort() / 32768f
                    out.add(v); sum += v * v; i += 2
                }
                val frames = n / 2
                val ms = frames * 1000 / CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE
                totalMs += ms
                val rms = sqrt(sum / frames.coerceAtLeast(1))
                if (rms > 0.02) { spoke = true; silentMs = 0 } else silentMs += ms
                if (spoke && silentMs > 1200) break
                if (!spoke && totalMs > 7000) break
                if (totalMs > 20_000) break
            }
        } finally {
            runCatching { rec.stopRecording() }
            releaseFocus()
        }
        return if (spoke) out.toArray() else null
    }

    private fun requestFocus() {
        val am = carContext.getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        focus = req
        am.requestAudioFocus(req)
    }

    private fun releaseFocus() {
        focus?.let { carContext.getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    override fun onGetTemplate(): Template {
        val body = when (phase) {
            Phase.SPEAKING, Phase.IDLE -> if (answer.isNotBlank()) answer + if (phase == Phase.IDLE && message.isNotBlank()) "\n\n$message" else "" else message
            Phase.THINKING, Phase.TRANSCRIBING -> (if (you.isNotBlank()) "„$you“\n\n" else "") + message
            else -> message
        }.ifBlank { "Aurion" }
        val b = MessageTemplate.Builder(body.take(500))
            .setTitle(
                when (phase) {
                    Phase.LISTENING -> "🎙 Ich höre zu"
                    Phase.TRANSCRIBING, Phase.THINKING -> "Aurion denkt …"
                    Phase.SPEAKING -> "Aurion"
                    Phase.ERROR -> "Hinweis"
                    Phase.IDLE -> "Aurion"
                }
            )
            .setHeaderAction(Action.BACK)
        if (phase == Phase.IDLE || phase == Phase.ERROR || phase == Phase.SPEAKING) {
            b.addAction(Action.Builder().setTitle("🎙 Sprechen").setOnClickListener { silentRounds = 0; listen() }.build())
        }
        if (phase == Phase.LISTENING || phase == Phase.SPEAKING) {
            b.addAction(Action.Builder().setTitle("Stopp").setOnClickListener { job?.cancel(); speaker.stop(); set(Phase.IDLE, "") }.build())
        }
        return b.build()
    }
}
