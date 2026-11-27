package cc.szakacs.aurion.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import cc.szakacs.aurion.R
import cc.szakacs.aurion.brain.Brain
import cc.szakacs.aurion.data.LangMode
import cc.szakacs.aurion.data.Settings
import cc.szakacs.aurion.data.Store
import cc.szakacs.aurion.speech.FloatList
import cc.szakacs.aurion.speech.Packs
import cc.szakacs.aurion.speech.WhisperEngine
import cc.szakacs.aurion.voice.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Aurion in Android Auto als Medien-App.
 * Medien-Apps zeigt Android Auto auch dann an, wenn sie nicht aus dem Play Store stammen
 * ("Unbekannte Quellen"). Fragen kommen über die Suche / Google-Sprachsuche oder über
 * den Eintrag "Frag Aurion" (Mikrofon + Whisper); die Antwort wird vorgelesen und als
 * "Wiedergabe" mit Titel und Text angezeigt.
 */
class AurionMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private lateinit var speaker: Speaker
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var focus: AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
        speaker = Speaker(this)
        session = MediaSessionCompat(this, "Aurion").apply {
            setCallback(callback)
            setPlaybackState(state(PlaybackStateCompat.STATE_NONE))
            isActive = true
        }
        sessionToken = session.sessionToken
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        runCatching { speaker.shutdown() }
        releaseFocus()
        session.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Liste

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT, Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
        })

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val en = Settings.get(this).lastLang == "en"
        val items = when (parentId) {
            ROOT -> mutableListOf(
                playable("ask", if (en) "🎙️ Ask Aurion" else "🎙️ Frag Aurion", if (en) "Speak your question" else "Frage einsprechen"),
                playable("q:" + (if (en) "What's the weather today?" else "Wie wird das Wetter heute?"), if (en) "Weather today" else "Wetter heute", null),
                playable("q:" + (if (en) "What's the weather tomorrow?" else "Wie wird das Wetter morgen?"), if (en) "Weather tomorrow" else "Wetter morgen", null),
                playable("q:" + (if (en) "What's on my shopping list?" else "Was steht auf der Einkaufsliste?"), if (en) "Shopping list" else "Einkaufsliste", if (en) "Read aloud" else "Vorlesen"),
                playable("q:" + (if (en) "Which reminders do I have?" else "Welche Erinnerungen habe ich?"), if (en) "Reminders" else "Erinnerungen", if (en) "Read aloud" else "Vorlesen"),
                browsable(RECENT, if (en) "Recent answers" else "Letzte Antworten"),
            )
            RECENT -> {
                val chat = Store.get(this).chat.value
                chat.withIndex().filter { !it.value.fromUser }.toList().takeLast(10).asReversed().map { (i, m) ->
                    val q = chat.getOrNull(i - 1)?.takeIf { it.fromUser }?.text ?: "Aurion"
                    playable("msg:$i", q.take(60), plain(m.text).take(90))
                }.toMutableList()
            }
            else -> mutableListOf()
        }
        result.sendResult(items)
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        val q = query.trim()
        result.sendResult(
            if (q.isEmpty()) mutableListOf()
            else mutableListOf(playable("q:$q", q, if (Settings.get(this).lastLang == "en") "Ask Aurion" else "Aurion fragen"))
        )
    }

    private fun desc(id: String, title: String, sub: String?) = MediaDescriptionCompat.Builder()
        .setMediaId(id)
        .setTitle(title)
        .setSubtitle(sub)
        .setIconUri(Uri.parse("android.resource://$packageName/${R.drawable.aurion_emblem}"))
        .build()

    private fun playable(id: String, title: String, sub: String?) =
        MediaBrowserCompat.MediaItem(desc(id, title, sub), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)

    private fun browsable(id: String, title: String) =
        MediaBrowserCompat.MediaItem(desc(id, title, null), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)

    // --------------------------------------------------------------- Steuerung

    private val callback = object : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val id = mediaId ?: return
            when {
                id == "ask" -> listenAndAsk()
                id.startsWith("q:") -> ask(id.removePrefix("q:"))
                id.startsWith("msg:") -> {
                    val m = Store.get(this@AurionMediaService).chat.value.getOrNull(id.removePrefix("msg:").toIntOrNull() ?: -1) ?: return
                    say("Aurion", m.text, Settings.get(this@AurionMediaService).lastLang)
                }
            }
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            if (query.isNullOrBlank()) listenAndAsk() else ask(query)
        }

        override fun onPlay() = listenAndAsk()

        // Lenkradtasten (Weiter/Zurück) starten ebenfalls "Frag Aurion", solange Aurion die aktive Medienquelle ist
        override fun onSkipToNext() = listenAndAsk()
        override fun onSkipToPrevious() = listenAndAsk()

        override fun onPause() = stopAll()
        override fun onStop() = stopAll()
    }

    private fun stopAll() {
        job?.cancel()
        speaker.stop()
        releaseFocus()
        session.setPlaybackState(state(PlaybackStateCompat.STATE_STOPPED))
    }

    private fun state(s: Int, error: String? = null) = PlaybackStateCompat.Builder()
        .setActions(
            PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        )
        .setState(s, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
        .apply { if (error != null) setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, error) }
        .build()

    private fun show(title: String, text: String) {
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title.take(80))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title.take(80))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, plain(text).take(160))
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, plain(text).take(160))
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Aurion")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, "android.resource://$packageName/${R.drawable.aurion_emblem}")
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1)
                .build()
        )
    }

    /** Frage an Aurion stellen und die Antwort vorlesen */
    private fun ask(question: String) {
        job?.cancel()
        speaker.stop()
        job = scope.launch {
            show(question, "Denke nach …")
            session.setPlaybackState(state(PlaybackStateCompat.STATE_BUFFERING))
            val reply = runCatching { Brain.get(this@AurionMediaService).handle(question, spoken = true) }.getOrNull()
            if (!isActive) return@launch
            say(question, reply?.text ?: "Das hat leider nicht geklappt.", reply?.lang ?: "de")
        }
    }

    private fun say(title: String, text: String, lang: String) {
        show(title, text)
        requestFocus()
        session.setPlaybackState(state(PlaybackStateCompat.STATE_PLAYING))
        speaker.speak(plain(text), lang) {
            releaseFocus()
            session.setPlaybackState(state(PlaybackStateCompat.STATE_PAUSED))
        }
    }

    /** Mikrofon -> Whisper (lokal) -> Aurion */
    private fun listenAndAsk() {
        val en = Settings.get(this).lastLang == "en"
        val pack = Packs.firstInstalledWhisper(this)
        if (pack == null) {
            say("Aurion", if (en) "Please download Whisper in the Aurion app under Settings, Speech recognition."
            else "Bitte lade in der Aurion-App unter Einstellungen, Spracherkennung, das Whisper-Modell.", if (en) "en" else "de")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            say("Aurion", if (en) "Please allow microphone access in the Aurion app." else "Bitte erlaube in der Aurion-App den Zugriff aufs Mikrofon.", if (en) "en" else "de")
            return
        }
        job?.cancel()
        speaker.stop()
        // 1) Aurion-Fenster direkt öffnen (braucht "Über anderen Apps anzeigen") – sichtbares Fenster = Mikrofon erlaubt,
        //    unabhängig davon, welcher Assistent gerade eingestellt ist
        val overlayOk = android.provider.Settings.canDrawOverlays(this) && runCatching {
            startActivity(
                android.content.Intent(this, cc.szakacs.aurion.ui.AssistActivity::class.java)
                    .putExtra("listen", true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        // 2) sonst als Standard-Assistent zuhören
        if (overlayOk || (cc.szakacs.aurion.assist.AssistantRole.isDefault(this) &&
                cc.szakacs.aurion.assist.AurionInteractionService.launchListening())) {
            show(if (en) "Listening – speak now" else "Ich höre zu – sprich jetzt", if (en) "The answer will be read aloud" else "Die Antwort wird vorgelesen")
            session.setPlaybackState(state(PlaybackStateCompat.STATE_PAUSED))
            return
        }
        job = scope.launch {
            show(if (en) "Listening …" else "Ich höre zu …", if (en) "Ask your question" else "Stell deine Frage")
            session.setPlaybackState(state(PlaybackStateCompat.STATE_PLAYING))
            requestFocus()
            val samples = withContext(Dispatchers.IO) { record() }
            if (!isActive) return@launch
            if (samples == null) {
                say("Aurion",
                    if (en) "I couldn't hear you. You can also say: Hey Google, play weather tomorrow on Aurion."
                    else "Ich konnte dich nicht hören. Du kannst auch sagen: Hey Google, spiele Wetter morgen auf Aurion.",
                    if (en) "en" else "de")
                return@launch
            }
            show(if (en) "Understanding …" else "Verstehe …", "")
            val s = Settings.get(this@AurionMediaService)
            val lang = if (s.langMode == LangMode.AUTO) "" else s.lastLang
            val text = runCatching { WhisperEngine.transcribe(this@AurionMediaService, pack.id, samples, lang) { _, _ -> } }.getOrDefault("")
            if (!isActive) return@launch
            if (text.isBlank()) {
                say("Aurion", if (en) "Sorry, I didn't catch that." else "Das habe ich leider nicht verstanden.", if (en) "en" else "de")
                return@launch
            }
            ask(text)
        }
    }

    /** Nimmt auf, bis nach dem Sprechen ~1,2 s Stille herrscht (max. 20 s). null = niemand hat gesprochen. */
    @SuppressLint("MissingPermission")
    private suspend fun record(): FloatArray? {
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate))
        }.getOrNull() ?: return null
        val out = FloatList(rate * 20)
        val buf = ShortArray(rate / 20)
        var spoke = false
        var silentMs = 0
        var totalMs = 0
        try {
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
            while (kotlin.coroutines.coroutineContext.isActive) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                var sum = 0.0
                for (k in 0 until n) { val v = buf[k] / 32768f; out.add(v); sum += v * v }
                val ms = n * 1000 / rate
                totalMs += ms
                val rms = sqrt(sum / n)
                if (rms > 0.02) { spoke = true; silentMs = 0 } else silentMs += ms
                if (spoke && silentMs > 1200) break
                if (!spoke && totalMs > 7000) break
                if (totalMs > 20_000) break
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        return if (spoke) out.toArray() else null
    }

    private fun requestFocus() {
        if (focus != null) return
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        focus = req
        am.requestAudioFocus(req)
    }

    private fun releaseFocus() {
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun plain(t: String) = t.replace(Regex("```[\\s\\S]*?```"), "").replace(Regex("[*_`#>]+"), "").trim()

    companion object {
        private const val ROOT = "root"
        private const val RECENT = "recent"
    }
}
