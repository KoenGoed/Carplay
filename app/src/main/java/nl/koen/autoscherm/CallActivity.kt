package nl.koen.autoscherm

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Groot belscherm voor in de auto: grote knoppen om op te nemen, te weigeren en op te hangen. */
class CallActivity : Activity() {

    companion object {
        const val EXTRA_STATE = "state"
        const val EXTRA_NUMBER = "number"

        @Volatile
        var current: CallActivity? = null

        /** Wordt aangeroepen door PhoneStateReceiver bij elke verandering. */
        fun onPhoneState(context: Context, state: String, number: String?) {
            val open = current
            if (open != null) {
                open.runOnUiThread { open.apply(state, number) }
                return
            }
            if (state == TelephonyManager.EXTRA_STATE_IDLE) return
            if (!shouldShow(context)) return
            try {
                context.startActivity(
                    Intent(context, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(EXTRA_STATE, state)
                        .putExtra(EXTRA_NUMBER, number)
                )
            } catch (ignored: Exception) { }
        }

        /** Alleen in de auto: carkit verbonden, of het autoscherm/de terugknop is actief. */
        private fun shouldShow(c: Context): Boolean {
            val prefs = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean("car_connected", false) || MainActivity.visible || BubbleService.running
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nameView: TextView
    private lateinit var numberView: TextView
    private lateinit var statusView: TextView
    private lateinit var answerBtn: ImageButton
    private lateinit var endBtn: ImageButton
    private var connectedAt = 0L
    private var state = TelephonyManager.EXTRA_STATE_RINGING

    private val timer = object : Runnable {
        override fun run() {
            if (connectedAt > 0) {
                val s = (SystemClock.elapsedRealtime() - connectedAt) / 1000
                statusView.text = String.format("In gesprek  %d:%02d", s / 60, s % 60)
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
        current = this
        apply(
            intent.getStringExtra(EXTRA_STATE) ?: TelephonyManager.EXTRA_STATE_RINGING,
            intent.getStringExtra(EXTRA_NUMBER)
        )
        handler.post(timer)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent ?: return
        apply(intent.getStringExtra(EXTRA_STATE) ?: state, intent.getStringExtra(EXTRA_NUMBER))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(timer)
        if (current === this) current = null
    }

    fun apply(newState: String, number: String?) {
        if (!number.isNullOrBlank()) {
            showCaller(number)
        } else if (nameView.text.isNullOrBlank()) {
            if (newState == TelephonyManager.EXTRA_STATE_RINGING) nameView.text = "Inkomend gesprek"
            else showOutgoingGuess()
        }
        state = newState
        when (newState) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                statusView.text = "Inkomend gesprek"
                answerBtn.visibility = View.VISIBLE
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (connectedAt == 0L) connectedAt = SystemClock.elapsedRealtime()
                answerBtn.visibility = View.GONE
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                statusView.text = "Gesprek beëindigd"
                handler.postDelayed({ finish() }, 800)
            }
        }
    }

    private fun showCaller(number: String) {
        val name = contactName(number)
        nameView.text = name ?: number
        numberView.text = if (name != null) number else ""
    }

    /** Bij een gesprek dat je zelf startte (bijv. "bel mama") weten we de naam al. */
    private fun showOutgoingGuess() {
        val p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        nameView.text = p.getString("last_called_name", null) ?: "Gesprek"
        numberView.text = p.getString("last_called_number", "") ?: ""
    }

    private fun contactName(number: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun answer() {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Geef Golf toestemming voor 'Telefoon' om op te nemen", Toast.LENGTH_LONG).show()
            return
        }
        getSystemService(TelecomManager::class.java).acceptRingingCall()
    }

    @SuppressLint("MissingPermission")
    private fun hangUp() {
        if (Build.VERSION.SDK_INT >= 28 &&
            checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        ) {
            endCallCompat()
        } else {
            Toast.makeText(this, "Ophangen lukt hier niet, gebruik de knop op je telefoon", Toast.LENGTH_LONG).show()
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun endCallCompat() {
        getSystemService(TelecomManager::class.java).endCall()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        statusView = TextView(this).apply {
            setTextColor(getColor(R.color.muted)); textSize = 20f
            gravity = Gravity.CENTER
        }
        nameView = TextView(this).apply {
            setTextColor(getColor(R.color.fg)); textSize = 44f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER; maxLines = 2
            setPadding(0, dp(8), 0, dp(4))
        }
        numberView = TextView(this).apply {
            setTextColor(getColor(R.color.muted)); textSize = 20f
            gravity = Gravity.CENTER
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(36), 0, 0)
        }
        endBtn = roundButton(R.drawable.bg_round_red, R.drawable.ic_call_end, "Ophangen of weigeren") { hangUp() }
        answerBtn = roundButton(R.drawable.bg_round_green, R.drawable.ic_call, "Opnemen") { answer() }
        buttons.addView(endBtn)
        buttons.addView(View(this), LinearLayout.LayoutParams(dp(72), 1))
        buttons.addView(answerBtn)

        root.addView(statusView)
        root.addView(nameView)
        root.addView(numberView)
        root.addView(buttons)
        return root
    }

    private fun roundButton(bg: Int, icon: Int, label: String, onClick: () -> Unit) =
        ImageButton(this).apply {
            setBackgroundResource(bg)
            setImageResource(icon)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            contentDescription = label
            layoutParams = LinearLayout.LayoutParams(dp(120), dp(120))
            setOnClickListener { onClick() }
        }
}
