package nl.koen.autoscherm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Zoekt de maximumsnelheid van de weg waar je rijdt in OpenStreetMap.
 * Kent Nederlandse standaardwaarden en tijdgebonden limieten (bijv. 100 overdag op de snelweg).
 */
object SpeedLimit {

    private const val ENDPOINT = "https://overpass-api.de/api/interpreter"

    /** Netwerkaanroep: niet op de hoofdthread aanroepen. Geeft null als het onbekend is. */
    fun lookup(lat: Double, lon: Double): Int? {
        val query = "[out:json][timeout:8];way(around:30,$lat,$lon)[highway][~\"^maxspeed\"~\".\"];out tags geom;"
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 9000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", "Autoscherm/1.0 (persoonlijke auto-app)")
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        if (conn.responseCode != 200) return null
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val elements = json.optJSONArray("elements") ?: return null

        var bestDist = Double.MAX_VALUE
        var bestLimit: Int? = null
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val tags = el.optJSONObject("tags") ?: continue
            val limit = limitFrom(
                tags.optString("maxspeed"),
                tags.optString("maxspeed:conditional")
            ) ?: continue
            val geom = el.optJSONArray("geometry") ?: continue
            var d = Double.MAX_VALUE
            for (k in 0 until geom.length() - 1) {
                val a = geom.getJSONObject(k)
                val b = geom.getJSONObject(k + 1)
                d = minOf(d, distToSegment(lat, lon, a.getDouble("lat"), a.getDouble("lon"), b.getDouble("lat"), b.getDouble("lon")))
            }
            if (d < bestDist) {
                bestDist = d
                bestLimit = limit
            }
        }
        return bestLimit
    }

    fun limitFrom(maxspeed: String, conditional: String): Int? {
        conditionalValue(conditional)?.let { return it }
        return parse(maxspeed)
    }

    private fun parse(raw: String): Int? {
        val v = raw.trim().lowercase()
        if (v.isEmpty() || v == "none" || v == "signals" || v == "variable") return null
        return when (v) {
            "nl:urban" -> 50
            "nl:rural" -> 80
            "nl:trunk" -> 100
            "nl:motorway" -> 130
            "nl:zone30" -> 30
            "nl:zone60" -> 60
            "nl:living_street", "walk" -> 15
            else -> {
                val number = Regex("^(\\d+)").find(v)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                if (v.contains("mph")) (number * 1.609).toInt() else number
            }
        }
    }

    /** Bijv. "100 @ (06:00-19:00)" of "130 @ (19:00-06:00); 100 @ (06:00-19:00)". */
    private fun conditionalValue(raw: String): Int? {
        if (raw.isBlank()) return null
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        for (part in raw.split(";")) {
            val m = Regex("(\\d+)\\s*@\\s*\\(?[^0-9]*?(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})").find(part) ?: continue
            val (value, h1, m1, h2, m2) = m.destructured
            val from = h1.toInt() * 60 + m1.toInt()
            val to = h2.toInt() * 60 + m2.toInt()
            val inside = if (from <= to) now in from until to else now >= from || now < to
            if (inside) return value.toIntOrNull()
        }
        return null
    }

    private fun distToSegment(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val k = cos(Math.toRadians(pLat)) * 111_320.0
        val px = pLon * k; val py = pLat * 110_540.0
        val ax = aLon * k; val ay = aLat * 110_540.0
        val bx = bLon * k; val by = bLat * 110_540.0
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }
}
