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
        private const val REQ_SPEECH = 41
        private const val REQ_CONTACTS = 42
        private const val REQ_BLUETOOTH = 43
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

    private val clockTick = object : Runnable {
        override fun run() {
            val now = Date()
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            date.text = SimpleDateFormat("EEEE d MMMM", Locale("nl", "NL")).format(now) + versionLabel
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

        setupTiles()
        setupDestinations()
        setupMediaButtons()
        setupVoice()
        setupUpdates()
        handleListenExtra(intent)
        versionLabel = try { "\nversie " + packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        askBluetoothPermission()
    }

    /** Nodig om de naam van je carkit te kunnen lezen (Android 12 en nieuwer). */
    private fun askBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !hasPerm(Manifest.permission.BLUETOOTH_CONNECT)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleListenExtra(intent)
    }

    override fun onResume() {
        super.onResume()
        // Terug in het autoscherm: zwevende knop weg
        handler.removeCallbacks(stopBubble)
        handler.postDelayed(stopBubble, 1200)
        handler.post(clockTick)
        connectMedia()
        hideSystemBars()
        if (System.currentTimeMillis() - lastUpdateCheck > 15 * 60_000) checkForUpdate()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(stopBubble)
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
        bindDestination(R.id.destHome, R.id.destHomeAddr, "dest_home", "Thuis")
        bindDestination(R.id.destWork, R.id.destWorkAddr, "dest_work", "School / werk")
    }

    private fun bindDestination(boxId: Int, addrId: Int, key: String, name: String) {
        val box = findViewById<View>(boxId)
        val addrView = findViewById<TextView>(addrId)
        fun refresh() {
            addrView.text = prefs.getString(key, null)?.takeIf { it.isNotBlank() } ?: "Lang indrukken om adres in te stellen"
        }
        refresh()
        box.setOnClickListener {
            val addr = prefs.getString(key, null)
            if (addr.isNullOrBlank()) editDestination(key, name) { refresh() } else navigateTo(addr)
        }
        box.setOnLongClickListener {
            editDestination(key, name) { refresh() }
            true
        }
    }

    private fun editDestination(key: String, name: String, done: () -> Unit) {
        val input = EditText(this).apply {
            setText(prefs.getString(key, ""))
            hint = "Straat en plaats"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        }
        AlertDialog.Builder(this)
            .setTitle("Adres voor $name")
            .setView(input)
            .setPositiveButton("Opslaan") { _, _ ->
                prefs.edit().putString(key, input.text.toString().trim()).apply()
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
            return
        }
        trackTitle.text = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Onbekend nummer"
        trackArtist.text = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) albumArt.setImageBitmap(art) else albumArt.setImageResource(R.drawable.ic_music)
        btnPlay.setImageResource(if (isPlaying()) R.drawable.ic_pause else R.drawable.ic_play)
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

        // Bellen
        after(listOf("bel naar ", "bellen naar ", "bel ", "telefoneer "))?.let { name ->
            callContact(name.removeSuffix(" op").trim()); return
        }

        // Navigeren
        val navTarget = after(listOf("navigeer naar ", "rij naar ", "route naar ", "breng me naar ", "ga naar ", "navigatie naar "))
        if (navTarget != null || t == "naar huis" || t == "ik wil naar huis") {
            val target = navTarget ?: "huis"
            when (target) {
                "huis", "thuis", "mijn huis" -> navigateSaved("dest_home", "Thuis")
                "school", "werk", "mijn werk", "de school" -> navigateSaved("dest_work", "School / werk")
                else -> { say("Route naar $target"); navigateTo(target) }
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
        say("Ik bel $name")
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val call = if (hasPerm(Manifest.permission.CALL_PHONE)) Intent(Intent.ACTION_CALL, uri) else Intent(Intent.ACTION_DIAL, uri)
        handler.postDelayed({ startOther(call) }, 600)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
}
