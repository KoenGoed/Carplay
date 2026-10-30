package nl.koen.autoscherm

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.content.res.Configuration
import android.location.Location
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.LinearLayout
import android.location.LocationListener
import android.location.LocationManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val PREFS = "autoscherm"
        private const val SPOTIFY = "com.spotify.music"
        private const val MAPS = "com.google.android.apps.maps"
        const val EXTRA_LISTEN = "listen"
        const val EXTRA_CAR_START = "car_start"

        /** Staat het autoscherm nu in beeld? (gebruikt door het belscherm) */
        @Volatile
        var visible = false
        private const val REQ_SPEECH = 41
        private const val REQ_CONTACTS = 42
        private const val REQ_PERMS = 43
        private const val DEFAULT_LAT = 52.3
        private const val DEFAULT_LON = 5.3
        private const val RELEASES_URL = "https://api.github.com/repos/KoenGoed/Carplay/releases/latest"
    }

    private data class AppDef(
        val label: String,
        val packages: List<String>,
        val tileId: Int,
        val spoken: List<String>,
    )

    private val apps = listOf(
        AppDef("Spotify", listOf(SPOTIFY), R.id.tileSpotify, listOf("spotify")),
        AppDef("Maps", listOf(MAPS), R.id.tileMaps, listOf("maps", "kaart", "navigatie")),
        AppDef("Flitsmeister", listOf("com.flitsmeister.fmnew", "nl.flitsmeister.android"), R.id.tileFlits,
            listOf("flitsmeister", "flits meister", "flitser")),
        AppDef("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), R.id.tileWhatsapp,
            listOf("whatsapp", "whats app", "wats app", "watsapp")),
    )

    private lateinit var prefs: SharedPreferences
    private lateinit var sessionManager: MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var listenerAdded = false
    private var tts: TextToSpeech? = null
    private var pendingCall: String? = null
    private var downloadId = -1L
    private var lastUpdateCheck = 0L
    private var versionLabel = ""

    // Terugknop pas weghalen als het autoscherm echt in beeld blijft.
    // (Na een spraakopdracht wordt het scherm heel even 'hervat' terwijl de andere app al opent;
    // toen werd de knop meteen weer weggehaald. Dat was de bug.)
    private val stopBubble = Runnable { stopService(Intent(this, BubbleService::class.java)) }

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var trackTitle: TextView
    private lateinit var trackArtist: TextView
    private lateinit var albumArt: ImageView
    private lateinit var mediaHint: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var updateBanner: TextView
    private lateinit var speedValue: TextView
    private lateinit var speedLimit: TextView
    private lateinit var destCarInfo: TextView

    // Snelheid en maximumsnelheid
    private val locationListener = LocationListener { loc: Location -> onLocation(loc) }
    private var locating = false
    private var lastSaved = 0L
    private var limitLookupBusy = false
    private var lastLimitLoc: Location? = null
    private var lastLimitTime = 0L
    private var currentLimit: Int? = null
    private var currentSpeed: Int? = null
    private var limitFailed = false

    // "Hey Golf"
    private lateinit var wake: WakeWord

    // Nachtstand
    private var nightOn: Boolean? = null

    // Tabbladen
    private lateinit var pages: List<View>
    private lateinit var tabs: List<View>
    private var currentTab = 0
    private lateinit var gestures: GestureDetector

    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE d MMMM", Locale("nl", "NL")).format(now) + versionLabel
            applyNight()
            handler.postDelayed(this, 10_000)
        }
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> pickController(list) }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMedia()
        override fun onSessionDestroyed() {
            controller = null
            updateMedia()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        sessionManager = getSystemService(MediaSessionManager::class.java)

        clock = findViewById(R.id.clock)
        date = findViewById(R.id.date)
        trackTitle = findViewById(R.id.trackTitle)
        trackArtist = findViewById(R.id.trackArtist)
        albumArt = findViewById(R.id.albumArt)
        mediaHint = findViewById(R.id.mediaHint)
        btnPlay = findViewById(R.id.btnPlay)
        updateBanner = findViewById(R.id.updateBanner)
        speedValue = findViewById(R.id.speedValue)
        speedLimit = findViewById(R.id.speedLimit)
        destCarInfo = findViewById(R.id.destCarInfo)
        findViewById<View>(R.id.destCar).setOnClickListener { walkToCar() }

        setupTiles()
        setupDestinations()
        setupMediaButtons()
        setupVoice()
        setupUpdates()
        setupTabs()
        arrangeDrivePage()
        setupRouteSettings()
        handleListenExtra(intent)
        handleCarStart(intent)
        versionLabel = try { "\nversie " + packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        askPermissions()
    }

    /**
     * Eén keer alle toestemmingen vragen die nog ontbreken:
     * carkit herkennen, snelheid en parkeerplek (locatie), en het belscherm (telefoon, oproeplijst).
     */
    private fun askPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 31) wanted += Manifest.permission.BLUETOOTH_CONNECT
        val missing = wanted.filter { !hasPerm(it) }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleListenExtra(intent)
        handleCarStart(intent)
    }

    override fun onResume() {
        super.onResume()
        // Terug in het autoscherm: zwevende knop weg
        visible = true
        handler.removeCallbacks(stopBubble)
        handler.postDelayed(stopBubble, 1200)
        startLocation()
        refreshCarTile()
        refreshSettingsInfo()
        applyNight()
        wake.start()
        handler.post(clockTick)
        connectMedia()
        hideSystemBars()
        if (System.currentTimeMillis() - lastUpdateCheck > 15 * 60_000) checkForUpdate()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(stopBubble)
        visible = false
        stopLocation()
        wake.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (listenerAdded) sessionManager.removeOnActiveSessionsChangedListener(sessionsChanged)
        controller?.unregisterCallback(mediaCallback)
        tts?.shutdown()
        try { unregisterReceiver(downloadDone) } catch (ignored: Exception) { }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    // ---------- App-tegels ----------

    private fun setupTiles() {
        for (app in apps) {
            val tile = findViewById<View>(app.tileId)
            tile.findViewById<TextView>(R.id.tileLabel).text = app.label
            val intent = launchIntentFor(app)
            val pkg = intent?.component?.packageName ?: intent?.`package`
            if (pkg != null) {
                try {
                    tile.findViewById<ImageView>(R.id.tileIcon)
                        .setImageDrawable(packageManager.getApplicationIcon(pkg))
                } catch (ignored: Exception) { }
            } else {
                tile.alpha = 0.45f
            }
            tile.setOnClickListener { openApp(app) }
        }
    }

    @Suppress("DEPRECATION")
    private fun launchIntentFor(app: AppDef): Intent? {
        for (p in app.packages) {
            packageManager.getLaunchIntentForPackage(p)?.let { return it }
        }
        // Valt terug op zoeken op naam, voor het geval de pakketnaam anders is
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = packageManager.queryIntentActivities(main, 0).firstOrNull {
            it.loadLabel(packageManager).toString().contains(app.label, ignoreCase = true)
        } ?: return null
        return packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
    }

    private fun openApp(app: AppDef) {
        val intent = launchIntentFor(app)
        if (intent == null) {
            Toast.makeText(this, "${app.label} is niet geïnstalleerd", Toast.LENGTH_SHORT).show()
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(app.label))))
            } catch (ignored: Exception) { }
            return
        }
        launchWithBubble(intent)
    }

    private fun launchWithBubble(intent: Intent) {
        if (!Settings.canDrawOverlays(this) && !prefs.getBoolean("askedOverlay", false)) {
            AlertDialog.Builder(this)
                .setTitle("Terugknop aanzetten?")
                .setMessage("Dan zie je in Maps, Flitsmeister en WhatsApp een rond knopje. Eén tik en je bent terug in je autoscherm.\n\nZet bij de volgende stap 'Autoscherm' aan en ga daarna terug.")
                .setPositiveButton("Aanzetten") { _, _ ->
                    prefs.edit().putBoolean("askedOverlay", true).apply()
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                .setNegativeButton("Niet nu") { _, _ ->
                    prefs.edit().putBoolean("askedOverlay", true).apply()
                    startOther(intent)
                }
                .show()
            return
        }
        startOther(intent)
    }

    private fun startOther(intent: Intent) {
        if (Settings.canDrawOverlays(this)) {
            try {
                startForegroundService(Intent(this, BubbleService::class.java))
            } catch (ignored: Exception) { }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (ignored: Exception) {
            Toast.makeText(this, "Kon de app niet openen", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Vaste bestemmingen ----------

    private fun setupDestinations() {
        bindDestination(R.id.destHome, R.id.destHomeAddr, "dest_home", null, "Thuis")
        bindDestination(R.id.destWork, R.id.destWorkAddr, "dest_work", R.id.destWorkName, "Werk")
    }

    /** Naam van de tweede vaste plek; die kies je zelf (bijv. Werk, School, Stage). */
    private fun workName() = prefs.getString("dest_work_name", null)?.takeIf { it.isNotBlank() } ?: "Werk"

    private fun bindDestination(boxId: Int, addrId: Int, key: String, nameId: Int?, defaultName: String) {
        val box = findViewById<View>(boxId)
        val addrView = findViewById<TextView>(addrId)
        val nameView = nameId?.let { findViewById<TextView>(it) }
        fun name() = if (nameView != null) workName() else defaultName
        fun refresh() {
            nameView?.text = name()
            addrView.text = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: "Lang indrukken om in te stellen"
        }
        refresh()
        box.setOnClickListener {
            val addr = prefs.getString(key, null)
            if (addr.isNullOrBlank()) editDestination(key, name(), nameView != null) { refresh() } else navigateTo(addr)
        }
        box.setOnLongClickListener {
            editDestination(key, name(), nameView != null) { refresh() }
            true
        }
    }

    private fun editDestination(key: String, name: String, nameEditable: Boolean, done: () -> Unit) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val form = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(this).apply {
            setText(name)
            hint = "Naam, bijv. Werk of School"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val addrInput = EditText(this).apply {
            setText(prefs.getString(key, ""))
            hint = "Straat en plaats"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        }
        if (nameEditable) form.addView(nameInput)
        form.addView(addrInput)
        AlertDialog.Builder(this)
            .setTitle(if (nameEditable) "Plek wijzigen" else "Adres voor $name")
            .setView(form)
            .setPositiveButton("Opslaan") { _, _ ->
                val edit = prefs.edit().putString(key, addrInput.text.toString().trim())
                if (nameEditable) edit.putString("dest_work_name", nameInput.text.toString().trim())
                edit.apply()
                done()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun navigateTo(address: String) {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(address))).setPackage(MAPS)
        if (nav.resolveActivity(packageManager) != null) {
            launchWithBubble(nav)
        } else {
            Toast.makeText(this, "Google Maps is niet geïnstalleerd", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Muziek (Spotify) ----------

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(packageName)
    }

    private fun connectMedia() {
        if (!hasNotificationAccess()) {
            mediaHint.visibility = View.VISIBLE
            trackTitle.text = "Muziek"
            trackArtist.text = "Knoppen werken al, titel zie je na het aanzetten"
            return
        }
        mediaHint.visibility = View.GONE
        val component = ComponentName(this, MediaListener::class.java)
        try {
            if (!listenerAdded) {
                sessionManager.addOnActiveSessionsChangedListener(sessionsChanged, component, handler)
                listenerAdded = true
            }
            pickController(sessionManager.getActiveSessions(component))
        } catch (ignored: SecurityException) {
            mediaHint.visibility = View.VISIBLE
        }
    }

    private fun pickController(list: List<MediaController>?) {
        val chosen = list?.firstOrNull { it.packageName == SPOTIFY } ?: list?.firstOrNull()
        if (chosen?.sessionToken == controller?.sessionToken && chosen != null) {
            updateMedia()
            return
        }
        controller?.unregisterCallback(mediaCallback)
        controller = chosen
        chosen?.registerCallback(mediaCallback, handler)
        updateMedia()
    }

    private fun updateMedia() {
        val c = controller
        val meta = c?.metadata
        if (meta == null) {
            if (hasNotificationAccess()) {
                trackTitle.text = "Niets aan het spelen"
                trackArtist.text = "Druk op afspelen om Spotify te starten"
            }
            albumArt.setImageResource(R.drawable.ic_music)
            btnPlay.setImageResource(R.drawable.ic_play)
            syncMini()
            return
        }
        trackTitle.text = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Onbekend nummer"
        trackArtist.text = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) albumArt.setImageBitmap(art) else albumArt.setImageResource(R.drawable.ic_music)
        btnPlay.setImageResource(if (isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
        syncMini()
    }

    /** Het kleine muziekblok op het tabblad Rijden volgt het grote. */
    private fun syncMini() {
        findViewById<TextView>(R.id.miniTitle).text = trackTitle.text
        findViewById<TextView>(R.id.miniArtist).text = trackArtist.text
        findViewById<ImageView>(R.id.miniArt).setImageDrawable(albumArt.drawable?.constantState?.newDrawable(resources) ?: albumArt.drawable)
        findViewById<ImageButton>(R.id.miniPlay).setImageResource(if (isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun isPlaying() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    private fun setupMediaButtons() {
        mediaHint.setOnClickListener {
            Toast.makeText(this, "Zet 'Autoscherm' aan en ga terug", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener {
            controller?.transportControls?.skipToPrevious() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener {
            controller?.transportControls?.skipToNext() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        findViewById<ImageButton>(R.id.miniPlay).setOnClickListener { btnPlay.performClick() }
        findViewById<ImageButton>(R.id.miniNext).setOnClickListener { findViewById<ImageButton>(R.id.btnNext).performClick() }
        btnPlay.setOnClickListener {
            val c = controller
            when {
                c == null -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                isPlaying() -> c.transportControls.pause()
                else -> c.transportControls.play()
            }
        }
    }

    private fun sendMediaKey(code: Int) {
        val audio = getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // ---------- Spraakbesturing ----------

    private fun setupVoice() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("nl", "NL")
        }
        findViewById<ImageButton>(R.id.btnMic).setOnClickListener { startListening() }
        wake = WakeWord(
            this,
            onWake = { if (visible) startListening() },
            onStatus = { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() },
        )
    }

    private fun handleListenExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_LISTEN, false) == true) {
            intent?.removeExtra(EXTRA_LISTEN)
            handler.postDelayed({ startListening() }, 400)
        }
    }

    private fun startListening() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "nl-NL")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Bijv. bel mama, open Flitsmeister, rij naar huis")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            say("Spraakherkenning is niet beschikbaar. Installeer of update de Google-app.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) handleCommand(text)
        }
    }

    private fun say(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "autoscherm")
    }

    /** Vertaalt wat je zegt naar een actie. */
    private fun handleCommand(raw: String) {
        val t = raw.lowercase(Locale("nl", "NL")).trim().trimEnd('.', '!', '?')

        fun after(prefixes: List<String>): String? =
            prefixes.firstOrNull { t.startsWith(it) }?.let { t.removePrefix(it).trim() }

        // Parkeerplek
        if (t.contains("mijn auto") || t.contains("geparkeerd") || t.contains("parkeerplek")) {
            walkToCar(); return
        }

        // Nachtstand
        if (t.startsWith("nachtstand") || t.startsWith("nachtmodus")) {
            val mode = when {
                t.contains("uit") -> "off"
                t.contains("aan") -> "on"
                else -> "auto"
            }
            prefs.edit().putString("night_mode", mode).apply()
            nightOn = null
            applyNight()
            say(when (mode) { "on" -> "Nachtstand aan"; "off" -> "Nachtstand uit"; else -> "Nachtstand automatisch" })
            return
        }

        // Bellen
        after(listOf("bel naar ", "bellen naar ", "bel ", "telefoneer "))?.let { name ->
            callContact(name.removeSuffix(" op").trim()); return
        }

        // Snelste route naar huis (vaste routes vergelijken met de files van nu)
        if (t.contains("snelste route") || t.contains("welke route") || t.contains("beste route") ||
            (t.contains("route") && t.contains("huis") && t.contains("snel"))
        ) {
            fastestHome(); return
        }

        // Navigeren
        val navTarget = after(listOf("navigeer naar ", "rij naar ", "route naar ", "breng me naar ", "ga naar ", "navigatie naar "))
        if (navTarget != null || t == "naar huis" || t == "ik wil naar huis") {
            val target = navTarget ?: "huis"
            when (target) {
                "huis", "thuis", "mijn huis" -> navigateSaved("dest_home", "Thuis")
                "school", "werk", "mijn werk", "de school" -> navigateSaved("dest_work", workName())
                else -> if (target == workName().lowercase(Locale("nl", "NL"))) navigateSaved("dest_work", workName()) else { say("Route naar $target"); navigateTo(target) }
            }
            return
        }

        // Muziek bedienen
        when (t) {
            "pauze", "pauzeer", "stop", "stop muziek", "stop de muziek", "muziek uit", "zet muziek uit", "zet de muziek uit" -> {
                controller?.transportControls?.pause() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE); return
            }
            "volgende", "volgend nummer", "volgende nummer", "skip", "volgend liedje", "volgende liedje" -> {
                controller?.transportControls?.skipToNext() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT); return
            }
            "vorige", "vorig nummer", "vorige nummer", "terug", "vorig liedje" -> {
                controller?.transportControls?.skipToPrevious() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS); return
            }
            "speel", "speel af", "afspelen", "play", "muziek aan", "zet muziek aan", "zet de muziek aan", "speel muziek", "verder", "ga verder" -> {
                controller?.transportControls?.play() ?: sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); return
            }
        }
        after(listOf("speel ", "zet ", "draai "))?.let { rest ->
            val q = rest.removeSuffix(" op").removeSuffix(" af").removeSuffix(" op spotify").removeSuffix(" aan").trim()
            val isApp = apps.any { a -> a.spoken.any { q.contains(it) } }
            if (!isApp && q.isNotEmpty() && (t.startsWith("speel") || t.startsWith("draai"))) {
                playSearch(q); return
            }
        }

        // App openen: "open flitsmeister", "zet flitsmeister aan", of alleen "flitsmeister"
        apps.firstOrNull { a -> a.spoken.any { t.contains(it) } }?.let { app ->
            say("${app.label} wordt geopend")
            openApp(app); return
        }

        say("Dat snapte ik niet: $raw")
    }

    private fun navigateSaved(key: String, name: String) {
        val addr = prefs.getString(key, null)
        if (addr.isNullOrBlank()) {
            say("Er is nog geen adres voor $name. Houd die knop lang ingedrukt om het in te stellen.")
        } else {
            say("Route naar ${name.lowercase()}")
            navigateTo(addr)
        }
    }

    private fun playSearch(query: String) {
        say("Ik zet $query op")
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(SPOTIFY)
            .putExtra(SearchManager.QUERY, query)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (i.resolveActivity(packageManager) == null) {
            say("Spotify is niet geïnstalleerd")
            return
        }
        startOther(i)
        // Spotify springt naar voren; na een paar seconden terug naar het autoscherm
        handler.postDelayed({
            try {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            } catch (ignored: Exception) { }
        }, 3500)
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun callContact(spokenName: String) {
        if (!hasPerm(Manifest.permission.READ_CONTACTS)) {
            pendingCall = spokenName
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), REQ_CONTACTS)
            return
        }
        val match = findContact(spokenName)
        if (match == null) {
            say("Ik kan $spokenName niet vinden in je contacten")
            return
        }
        val (name, number) = match
        prefs.edit().putString("last_called_name", name).putString("last_called_number", number).apply()
        say("Ik bel $name")
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val call = if (hasPerm(Manifest.permission.CALL_PHONE)) Intent(Intent.ACTION_CALL, uri) else Intent(Intent.ACTION_DIAL, uri)
        handler.postDelayed({ startOther(call) }, 600)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            startLocation()
            if (visible) wake.start()
            return
        }
        if (requestCode != REQ_CONTACTS) return
        val name = pendingCall ?: return
        pendingCall = null
        if (hasPerm(Manifest.permission.READ_CONTACTS)) callContact(name)
        else say("Zonder toegang tot je contacten kan ik niet bellen")
    }

    /** Zoekt het contact dat het best past bij wat je zei. Kent ook mama/papa-varianten. */
    private fun findContact(spoken: String): Pair<String, String>? {
        val q = spoken.lowercase(Locale("nl", "NL")).trim()
        val aliases = mapOf(
            "mama" to listOf("mama", "mam", "moeder", "mams", "ma"),
            "mam" to listOf("mam", "mama", "moeder", "mams", "ma"),
            "moeder" to listOf("moeder", "mama", "mam"),
            "papa" to listOf("papa", "pap", "vader", "paps", "pa"),
            "pap" to listOf("pap", "papa", "vader", "paps", "pa"),
            "vader" to listOf("vader", "papa", "pap"),
        )
        val wanted = aliases[q] ?: listOf(q)

        var best: Pair<String, String>? = null
        var bestScore = 0
        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, null
        ) ?: return null
        try {
            while (cursor.moveToNext()) {
                val display: String = cursor.getString(0) ?: ""
                val number: String = cursor.getString(1) ?: ""
                if (display.isBlank() || number.isBlank()) continue
                val name = display.lowercase(Locale("nl", "NL"))
                val words = name.split(' ', '-', '(', ')').filter { it.isNotBlank() }
                var score = 0
                wanted.forEachIndexed { idx, w ->
                    val bonus = if (idx == 0) 5 else 0
                    val s = when {
                        name == w -> 100
                        words.firstOrNull() == w -> 85
                        words.contains(w) -> 75
                        name.startsWith(w) -> 60
                        w.length >= 3 && name.contains(w) -> 40
                        else -> 0
                    }
                    if (s > 0) score = maxOf(score, s + bonus)
                }
                if (score > bestScore) {
                    bestScore = score
                    best = display to number
                }
            }
        } finally {
            cursor.close()
        }
        return best
    }

    // ---------- Updates ----------

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == downloadId) installDownloaded(id)
        }
    }

    private fun setupUpdates() {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadDone, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(downloadDone, filter)
        }
        updateBanner.visibility = View.GONE
    }

    private fun currentVersion(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun checkForUpdate() {
        lastUpdateCheck = System.currentTimeMillis()
        Thread {
            try {
                val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                if (conn.responseCode != 200) return@Thread
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val latest = json.optString("tag_name").trimStart('v', 'V').toLongOrNull() ?: return@Thread
                val assets = json.optJSONArray("assets") ?: return@Thread
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) apkUrl = a.optString("browser_download_url")
                }
                val url = apkUrl ?: return@Thread
                val notes = json.optString("body").ifBlank { "Verbeteringen" }
                if (latest > currentVersion()) {
                    runOnUiThread { showUpdate(latest, url, notes) }
                }
            } catch (ignored: Exception) { }
        }.start()
    }

    private fun showUpdate(version: Long, url: String, notes: String) {
        updateBanner.text = "Update 1.$version"
        updateBanner.visibility = View.VISIBLE
        updateBanner.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Nieuwe versie 1.$version")
                .setMessage(notes)
                .setPositiveButton("Installeren") { _, _ -> downloadUpdate(url) }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun downloadUpdate(url: String) {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Eenmalige toestemming")
                .setMessage("Zet bij de volgende stap 'Toestaan uit deze bron' aan, ga terug en tik nog een keer op Update.")
                .setPositiveButton("Oké") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                }
                .show()
            return
        }
        val fileName = "autoscherm-update.apk"
        File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName).delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Autoscherm update")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
        val dm = getSystemService(DownloadManager::class.java)
        downloadId = dm.enqueue(request)
        updateBanner.text = "Downloaden…"
        Toast.makeText(this, "Update wordt gedownload", Toast.LENGTH_SHORT).show()
    }

    private fun installDownloaded(id: Long) {
        val dm = getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(id)
        if (uri == null) {
            updateBanner.text = "Download mislukt, tik opnieuw"
            return
        }
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(install)
        } catch (e: Exception) {
            Toast.makeText(this, "Installeren lukte niet: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Snelheid en maximumsnelheid ----------

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocation() {
        if (locating || !hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
            locating = true
        } catch (ignored: Exception) { }
    }

    private fun stopLocation() {
        if (!locating) return
        try { getSystemService(LocationManager::class.java).removeUpdates(locationListener) } catch (ignored: Exception) { }
        locating = false
    }

    private fun onLocation(loc: Location) {
        if (loc.time - lastSaved > 10_000) {
            LocationStore.saveLast(this, loc)
            lastSaved = loc.time
        }
        val kmh = if (loc.hasSpeed()) Math.round(loc.speed * 3.6f) else null
        currentSpeed = if (kmh != null && kmh < 3) 0 else kmh
        speedValue.text = currentSpeed?.toString() ?: "–"
        maybeLookupLimit(loc)
        colorSpeed()
    }

    private fun maybeLookupLimit(loc: Location) {
        if (limitLookupBusy) return
        val prev = lastLimitLoc
        val now = System.currentTimeMillis()
        val moved = prev == null || prev.distanceTo(loc) > 250
        val stale = now - lastLimitTime > 45_000
        if (!moved && !stale) return
        if (prev != null && (currentSpeed ?: 0) < 5 && !stale) return
        limitLookupBusy = true
        lastLimitLoc = loc
        lastLimitTime = now
        val lat = loc.latitude
        val lon = loc.longitude
        val heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2) loc.bearing else null
        val key = TomTom.key(this)
        Thread {
            var result: Int? = null
            var ok = false
            // 1. TomTom (betrouwbaar, als er een sleutel is ingesteld)
            if (key != null) {
                try {
                    result = TomTom.speedLimit(key, lat, lon, heading)
                    ok = true
                } catch (ignored: Exception) { }
            }
            // 2. Gratis OpenStreetMap-servers als reserve
            if (result == null) {
                try {
                    result = SpeedLimit.lookup(lat, lon)
                    ok = true
                } catch (ignored: Exception) { }
            }
            runOnUiThread {
                limitLookupBusy = false
                limitFailed = !ok
                if (ok) currentLimit = result
                showLimit()
            }
        }.start()
    }

    private fun showLimit() {
        val limit = currentLimit
        if (limit == null && limitFailed) {
            // Even geen verbinding met de dienst: laat zien dat de app het probeert
            speedLimit.text = "?"
            speedLimit.visibility = View.VISIBLE
        } else if (limit == null) {
            speedLimit.visibility = View.INVISIBLE
        } else {
            speedLimit.text = limit.toString()
            speedLimit.visibility = View.VISIBLE
        }
        colorSpeed()
    }

    private fun colorSpeed() {
        val limit = currentLimit
        val speed = currentSpeed
        val tooFast = limit != null && speed != null && speed > limit + 3
        speedValue.setTextColor(if (tooFast) 0xFFFF4D4D.toInt() else getColor(R.color.fg))
    }

    // ---------- Parkeerplek ----------

    private fun refreshCarTile() {
        val p = LocationStore.parked(this)
        destCarInfo.text = if (p == null) {
            "Onthoud ik als je uitstapt"
        } else {
            val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).let { it.format(Date(p.time)) == it.format(Date()) }
            val fmt = if (sameDay) "'Geparkeerd om' HH:mm" else "'Geparkeerd' d MMM HH:mm"
            SimpleDateFormat(fmt, Locale("nl", "NL")).format(Date(p.time))
        }
    }

    private fun walkToCar() {
        val p = LocationStore.parked(this)
        if (p == null) {
            say("Ik weet nog niet waar je auto staat. Dat onthoud ik zodra je de carkit loskoppelt.")
            return
        }
        say("Ik loop met je mee naar je auto")
        val walk = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${p.lat},${p.lon}&mode=w")).setPackage(MAPS)
        val map = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(Mijn auto)"))
        try {
            startActivity(if (walk.resolveActivity(packageManager) != null) walk else map)
        } catch (e: Exception) {
            Toast.makeText(this, "Kon de kaart niet openen", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Nachtstand ----------

    private fun applyNight() {
        val dark = when (prefs.getString("night_mode", "auto")) {
            "on" -> true
            "off" -> false
            else -> {
                val spot = LocationStore.last(this)
                Sun.isDark(spot?.lat ?: DEFAULT_LAT, spot?.lon ?: DEFAULT_LON)
            }
        }
        if (dark == nightOn) return
        nightOn = dark
        val lp = window.attributes
        lp.screenBrightness = if (dark) 0.06f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        findViewById<View>(android.R.id.content).alpha = if (dark) 0.75f else 1f
    }

    // ---------- Tabbladen ----------

    private fun setupTabs() {
        pages = listOf(
            findViewById(R.id.pageDrive),
            findViewById(R.id.mediaCard),
            findViewById(R.id.pageApps),
            findViewById(R.id.pagePlaces),
        )
        tabs = listOf(
            findViewById(R.id.tabDrive),
            findViewById(R.id.tabMusic),
            findViewById(R.id.tabApps),
            findViewById(R.id.tabPlaces),
        )
        tabs.forEachIndexed { i, tab -> tab.setOnClickListener { showTab(i) } }

        // Vegen naar links of rechts wisselt van tabblad
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (kotlin.math.abs(dx) < 120 || kotlin.math.abs(dx) < kotlin.math.abs(dy) * 1.5f) return false
                if (dx < 0 && currentTab < pages.size - 1) showTab(currentTab + 1)
                else if (dx > 0 && currentTab > 0) showTab(currentTab - 1)
                return true
            }
        })
        currentTab = prefs.getInt("tab", 0).coerceIn(0, pages.size - 1)
        showTab(currentTab, animate = false)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestures.isInitialized) gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun showTab(index: Int, animate: Boolean = true) {
        val from = currentTab
        currentTab = index
        prefs.edit().putInt("tab", index).apply()
        pages.forEachIndexed { i, page ->
            if (i == index) {
                page.visibility = View.VISIBLE
                if (animate && i != from) {
                    val dir = if (index > from) 1 else -1
                    page.translationX = dir * page.width * 0.25f
                    page.alpha = 0f
                    page.animate().translationX(0f).alpha(1f).setDuration(180).start()
                }
            } else {
                page.animate().cancel()
                page.translationX = 0f
                page.alpha = 1f
                page.visibility = View.GONE
            }
        }
        val active = getColor(R.color.amber)
        val idle = getColor(R.color.muted)
        val ids = listOf(
            R.id.tabDriveIcon to R.id.tabDriveLabel,
            R.id.tabMusicIcon to R.id.tabMusicLabel,
            R.id.tabAppsIcon to R.id.tabAppsLabel,
            R.id.tabPlacesIcon to R.id.tabPlacesLabel,
        )
        ids.forEachIndexed { i, (icon, label) ->
            val c = if (i == index) active else idle
            findViewById<ImageView>(icon).setColorFilter(c)
            findViewById<TextView>(label).setTextColor(c)
        }
    }

    /** Liggend: snelheid en muziek naast elkaar. Staand: onder elkaar. */
    private fun arrangeDrivePage() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val page = findViewById<LinearLayout>(R.id.pageDrive)
        page.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val gap = (12 * resources.displayMetrics.density).toInt()
        val speed = findViewById<View>(R.id.speedCard)
        val mini = findViewById<View>(R.id.miniPlayer)
        speed.layoutParams = if (landscape) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.3f)
        mini.layoutParams = (if (landscape) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)).apply {
            if (landscape) marginStart = gap else topMargin = gap
        }
    }

    // ---------- Instappen: Flitsmeister op de achtergrond en muziek aan ----------

    private fun handleCarStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CAR_START, false) != true) return
        intent.removeExtra(EXTRA_CAR_START)

        val flits = apps.firstOrNull { it.label == "Flitsmeister" }?.let { launchIntentFor(it) }
        if (flits != null) {
            try {
                startActivity(flits.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (ignored: Exception) { }
            // Flitsmeister blijft op de achtergrond draaien; het autoscherm komt weer naar voren
            handler.postDelayed({ bringToFront() }, 2500)
        }
        // Even wachten tot het geluid via de carkit loopt
        handler.postDelayed({ startMusic() }, if (flits != null) 4500L else 3000L)
    }

    private fun bringToFront() {
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (ignored: Exception) { }
    }

    private fun startMusic() {
        val audio = getSystemService(AudioManager::class.java)
        if (audio.isMusicActive) return
        val c = controller
        if (c != null && c.packageName == SPOTIFY) c.transportControls.play()
        else sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)

        // Speelt er na 2 seconden nog niets? Dan Spotify zelf laten starten met je muziek
        handler.postDelayed({
            if (!audio.isMusicActive) {
                val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .setPackage(SPOTIFY)
                    .putExtra(SearchManager.QUERY, "")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (play.resolveActivity(packageManager) != null) {
                    try { startActivity(play) } catch (ignored: Exception) { }
                    handler.postDelayed({ bringToFront() }, 3000)
                }
            }
        }, 2000)
    }

    // ---------- Snelste route naar huis ----------

    private fun routeName(slot: String) =
        prefs.getString("route_${slot}_name", null)?.takeIf { it.isNotBlank() }
            ?: if (slot == "a") "Via het kanaal" else "Via OK"

    private fun routeVia(slot: String) = prefs.getString("route_${slot}_via", null)?.trim().orEmpty()

    private fun setupRouteSettings() {
        findViewById<View>(R.id.routeHome).setOnClickListener { fastestHome() }
        findViewById<View>(R.id.routeSettings).setOnClickListener { editRoutes() }
        findViewById<View>(R.id.tomtomSettings).setOnClickListener { editTomTomKey() }
        refreshSettingsInfo()
    }

    private fun refreshSettingsInfo() {
        val names = listOf("a", "b").filter { routeVia(it).isNotEmpty() }.map { routeName(it) }
        findViewById<TextView>(R.id.routeSettingsInfo).text =
            if (names.isEmpty()) "Nog niet ingesteld" else names.joinToString("  •  ")
        findViewById<TextView>(R.id.tomtomInfo).text =
            if (TomTom.key(this) == null) "Nog niet ingesteld — nodig voor files en maximumsnelheid" else "Ingesteld"
    }

    private fun formField(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }

    private fun formLayout(vararg views: View): LinearLayout {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            views.forEach { addView(it) }
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.muted))
        textSize = 13f
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun editRoutes() {
        val aName = formField("Naam, bijv. Via het kanaal", routeName("a"))
        val aVia = formField("Punt op die route, bijv. een straat en plaats", routeVia("a"))
        val bName = formField("Naam, bijv. Via OK", routeName("b"))
        val bVia = formField("Punt op die route, bijv. OK tankstation + straat + plaats", routeVia("b"))
        AlertDialog.Builder(this)
            .setTitle("Routes naar huis")
            .setView(formLayout(
                label("Route 1"), aName, aVia,
                label("Route 2"), bName, bVia,
                label("Vul bij elke route een plek in waar alleen die route langs komt. De app vergelijkt dan de reistijd met de files van nu."),
            ))
            .setPositiveButton("Opslaan") { _, _ ->
                prefs.edit()
                    .putString("route_a_name", aName.text.toString().trim())
                    .putString("route_a_via", aVia.text.toString().trim())
                    .putString("route_b_name", bName.text.toString().trim())
                    .putString("route_b_via", bVia.text.toString().trim())
                    .apply()
                refreshSettingsInfo()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun editTomTomKey() {
        val keyField = formField("Plak hier je sleutel", TomTom.key(this) ?: "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val help = label(
            "Maak gratis een account op developer.tomtom.com, ga naar Keys en kopieer je API-sleutel. " +
                    "Daarmee ziet de app de files van nu en de maximumsnelheid."
        ).apply { setTextIsSelectable(true) }
        AlertDialog.Builder(this)
            .setTitle("Verkeersinfo (TomTom)")
            .setView(formLayout(help, keyField))
            .setPositiveButton("Opslaan") { _, _ ->
                prefs.edit().putString("tomtom_key", keyField.text.toString().trim()).apply()
                refreshSettingsInfo()
            }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    private fun fastestHome() {
        val key = TomTom.key(this)
        if (key == null) {
            say("Stel eerst bij Plekken je TomTom-sleutel in, dan kan ik de files bekijken.")
            return
        }
        val home = prefs.getString("dest_home", null)
        if (home.isNullOrBlank()) {
            say("Ik weet je thuisadres nog niet. Stel het in bij Plekken.")
            return
        }
        val routes = listOf("a", "b").filter { routeVia(it).isNotEmpty() }.map { routeName(it) to routeVia(it) }
        val last = LocationStore.last(this)
        val work = prefs.getString("dest_work", null)
        say("Even kijken wat het snelst is")
        Thread {
            try {
                val fromGps = last != null && System.currentTimeMillis() - last.time < 15 * 60_000
                val origin: TomTom.Point = (if (fromGps) TomTom.Point(last!!.lat, last.lon)
                    else work?.takeIf { it.isNotBlank() }?.let { TomTom.geocode(this, key, it) })
                    ?: throw IllegalStateException("Geen startpunt")
                val dest = TomTom.geocode(this, key, home) ?: throw IllegalStateException("Thuisadres niet gevonden")
                val direct = TomTom.route(key, listOf(origin, dest)) ?: throw IllegalStateException("Geen route")
                val options = routes.mapNotNull { (name, via) ->
                    val p = TomTom.geocode(this, key, via) ?: return@mapNotNull null
                    TomTom.route(key, listOf(origin, p, dest))?.let { name to it }
                }
                runOnUiThread { announceRoutes(direct, options, home) }
            } catch (e: Exception) {
                runOnUiThread { say("Het lukte niet om de routes op te halen. Controleer je internet en je TomTom-sleutel.") }
            }
        }.start()
    }

    private fun announceRoutes(direct: TomTom.Route, options: List<Pair<String, TomTom.Route>>, home: String) {
        fun minutes(s: Int) = (s + 30) / 60
        fun spoken(name: String) = name.replaceFirst(Regex("^Via", RegexOption.IGNORE_CASE), "via")
        fun delayText(s: Int) = if (s >= 120) " Er staat ongeveer ${minutes(s)} minuten file." else ""

        if (options.isEmpty()) {
            say("Naar huis ben je ${minutes(direct.seconds)} minuten onderweg.${delayText(direct.delaySeconds)} " +
                    "Stel bij Plekken je vaste routes in om ze te vergelijken.")
            return
        }
        val sorted = options.sortedBy { it.second.seconds }
        val best = sorted.first()

        // Is een heel andere weg duidelijk sneller? Dan is er waarschijnlijk iets gebeurd: Maps aan.
        if (direct.seconds < best.second.seconds - 180) {
            say("Er is iets aan de hand op je vaste routes. Via een andere weg ben je in ${minutes(direct.seconds)} minuten thuis. Ik zet Maps aan.")
            handler.postDelayed({ navigateTo(home) }, 5000)
            return
        }

        val overview = sorted.joinToString(", ") { (name, r) -> "${spoken(name)} ${minutes(r.seconds)} minuten" }
        val advice = if (sorted.size > 1 && sorted[1].second.seconds - best.second.seconds < 60) {
            "Ze zijn ongeveer even snel, dus kies wat je fijn vindt."
        } else {
            "Rij ${spoken(best.first)}."
        }
        val text = overview.replaceFirstChar { it.uppercase() } + ". " + advice + delayText(best.second.delaySeconds)
        say(text)
    }
}
