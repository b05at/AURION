package cc.szakacs.aurion.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.szakacs.aurion.R
import cc.szakacs.aurion.ui.theme.AurionColors
import cc.szakacs.aurion.ui.theme.AurionTheme

class MainActivity : ComponentActivity() {

    private val vm: AssistantViewModel by viewModels()
    private val dictVm: DictationViewModel by viewModels()
    private val resumeTick = mutableIntStateOf(0)
    private var requestedTab by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        requestedTab = intent?.getStringExtra("tab")
        cc.szakacs.aurion.speech.Packs.resume(this)
        // Nur bei einem echten Neustart der App mit leerem Gespräch beginnen
        // (nicht, wenn Android die App im Hintergrund kurz entladen hat)
        if (savedInstanceState == null) {
            // Aus der Fertig-Benachrichtigung geöffnet: Antwort zeigen statt neu anfangen
            if (intent?.getBooleanExtra("keepChat", false) != true) vm.newConversation()
            handleShare(intent)
        }
        setContent {
            AurionTheme {
                androidx.compose.runtime.CompositionLocalProvider(LocalResumeTick provides resumeTick.intValue) {
                    AurionMain(vm, dictVm, resumeTick.intValue, requestedTab) { requestedTab = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedTab = intent.getStringExtra("tab")
        handleShare(intent)
    }

    /** Von anderen Apps geteilte Texte/Bilder oder markierter Text ("Frag Aurion") */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra("startDictation", false)) {
            requestedTab = "chat"
            intent.removeExtra("startDictation")
            window.decorView.postDelayed({ if (!dictVm.state.value.recording) dictVm.start() }, 1500)
            return
        }
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        val streamUri: android.net.Uri? = if (intent.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        } else null
        if (streamUri != null && (intent.type?.startsWith("audio/") == true || intent.type == "application/ogg" || intent.type?.startsWith("video/") == true)) {
            requestedTab = "chat"
            dictVm.transcribeFile(streamUri)
            return
        }
        if (streamUri != null && intent.type?.startsWith("image/") != true) {
            requestedTab = "chat"
            vm.attach(streamUri)
            return
        }
        val imageUri: android.net.Uri? = if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        } else null
        when {
            imageUri != null -> runCatching {
                val src = android.graphics.ImageDecoder.createSource(contentResolver, imageUri)
                val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { d, _, _ ->
                    d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    d.setTargetSampleSize(2)
                }
                requestedTab = "chat"
                vm.analyzeImage(bmp, autoExplain = false, title = "Geteiltes Bild")
            }
            !text.isNullOrBlank() -> {
                requestedTab = "chat"
                vm.analyzeText(text)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.started()
        // Wake-Word-Dienst darf nur aus dem Vordergrund starten (Android-Vorgabe fürs Mikrofon)
        cc.szakacs.aurion.speech.WakeWord.apply(this)
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
        AnswerNotifier.clear(this)
    }

    // Das Gespräch bleibt erhalten, solange die App läuft – auch im Hintergrund.
    // Geleert wird nur bei einem neuen Start (nach Beenden der App), siehe onCreate.
    override fun onStop() {
        super.onStop()
        AppVisibility.stopped()
        vm.pauseAudio()
    }
}

private enum class Tab(val key: String, val label: String) {
    CHAT("chat", "Aurion"), REMINDERS("reminders", "Erinnerungen"), SETTINGS("settings", "Einstellungen")
}

@Composable
private fun AurionMain(vm: AssistantViewModel, dictVm: DictationViewModel, tick: Int, requestedTab: String?, consumeTab: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    LaunchedEffect(requestedTab) {
        requestedTab?.let { key -> Tab.entries.firstOrNull { it.key == key }?.let { tab = it } }
        consumeTab()
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val perms = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(perms.toTypedArray())
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF0E1D45), AurionColors.Black),
                    center = androidx.compose.ui.geometry.Offset(200f, -100f),
                    radius = 1400f
                )
            )
    ) {
        Column(Modifier.fillMaxSize()) {
            Header()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.CHAT -> ChatScreen(vm, dictVm)
                    Tab.REMINDERS -> RemindersScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(tick, dictVm) { tab = Tab.CHAT }
                }
            }
            NavigationBar(containerColor = Color(0xCC070B16), tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.CHAT -> Icons.Rounded.Forum
                                    Tab.REMINDERS -> Icons.Rounded.NotificationsActive
                                    Tab.SETTINGS -> Icons.Rounded.Tune
                                }, t.label
                            )
                        },
                        label = { Text(t.label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AurionColors.Cyan,
                            selectedTextColor = AurionColors.Cyan,
                            indicatorColor = Color(0x262F7BFF),
                            unselectedIconColor = AurionColors.TextLow,
                            unselectedTextColor = AurionColors.TextLow,
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(R.drawable.aurion_emblem), null,
            modifier = Modifier.size(38.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "AURION",
            style = TextStyle(
                brush = Brush.linearGradient(listOf(Color.White, AurionColors.Silver, AurionColors.Cyan)),
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 8.sp
            )
        )
    }
}
