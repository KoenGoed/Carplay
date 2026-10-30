package nl.koen.autoscherm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Luistert, zolang het autoscherm open is, naar "Hey Golf".
 * Werkt helemaal op de telefoon zelf (Vosk), dus zonder internet en zonder piepjes.
 * Het Nederlandse spraakmodel (ca. 40 MB) wordt de eerste keer één keer gedownload.
 */
class WakeWord(
    private val context: Context,
    private val onWake: () -> Unit,
    private val onStatus: (String) -> Unit,
) : RecognitionListener {

    companion object {
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-nl-0.22.zip"
        private const val MODEL_NAME = "vosk-model-small-nl-0.22"

        // Alleen deze woorden hoeft het model te herkennen; al het andere wordt [unk]
        private const val GRAMMAR = "[\"hey golf\", \"hé golf\", \"he golf\", \"golf\", \"[unk]\"]"
        private val WAKE = Regex("(^|\\s)(hey|hé|he|\\[unk])\\s+golf(\\s|$)")
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var service: SpeechService? = null
    private var wanted = false
    private var busy = false

    private val modelDir get() = File(context.filesDir, MODEL_NAME)
    private val readyMarker get() = File(context.filesDir, "$MODEL_NAME.klaar")

    fun start() {
        wanted = true
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        when {
            model != null -> listen()
            busy -> Unit
            readyMarker.exists() -> loadModel()
            else -> download()
        }
    }

    fun stop() {
        wanted = false
        service?.let {
            try { it.stop(); it.shutdown() } catch (ignored: Exception) { }
        }
        service = null
    }

    private fun listen() {
        if (!wanted || service != null) return
        val m = model ?: return
        try {
            val rec = Recognizer(m, 16000.0f, GRAMMAR)
            service = SpeechService(rec, 16000.0f).also { it.startListening(this) }
        } catch (e: Exception) {
            service = null
        }
    }

    private fun loadModel() {
        busy = true
        Thread {
            val m = try { Model(modelDir.absolutePath) } catch (e: Exception) { null }
            main.post {
                busy = false
                if (m == null) {
                    readyMarker.delete()   // beschadigd: volgende keer opnieuw downloaden
                    onStatus("Hey Golf kon niet starten")
                } else {
                    model = m
                    listen()
                }
            }
        }.start()
    }

    private fun download() {
        busy = true
        onStatus("Hey Golf wordt eenmalig klaargezet (40 MB)…")
        Thread {
            val ok = try {
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                ZipInputStream(conn.inputStream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val out = File(context.filesDir, entry.name)
                        if (!out.canonicalPath.startsWith(context.filesDir.canonicalPath)) throw SecurityException("Ongeldig bestand")
                        if (entry.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
                readyMarker.createNewFile()
                true
            } catch (e: Exception) {
                false
            }
            main.post {
                busy = false
                if (ok) {
                    onStatus("Hey Golf staat aan")
                    if (wanted) loadModel()
                } else {
                    onStatus("Hey Golf klaarzetten mislukt, probeer het later met internet")
                }
            }
        }.start()
    }

    private fun check(json: String?, field: String) {
        val text = try { JSONObject(json ?: return).optString(field) } catch (e: Exception) { return }
        if (WAKE.containsMatchIn(text.lowercase())) {
            stop()
            main.post { onWake() }
        }
    }

    override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = Unit
    override fun onError(exception: Exception?) {
        stop()
        wanted = true
        main.postDelayed({ if (wanted) listen() }, 2000)
    }
    override fun onTimeout() {
        service = null
        if (wanted) listen()
    }
}
