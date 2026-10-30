package nl.koen.autoscherm

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Verkeersinformatie via TomTom: adressen opzoeken, routes met actuele files, en maximumsnelheden.
 * Werkt alleen met een (gratis) TomTom-sleutel, die je in de app instelt bij Plekken.
 */
object TomTom {

    data class Point(val lat: Double, val lon: Double)
    data class Route(val seconds: Int, val delaySeconds: Int)

    fun key(c: Context): String? =
        c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
            .getString("tomtom_key", null)?.trim()?.takeIf { it.isNotEmpty() }

    private fun get(url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 7000
        conn.readTimeout = 9000
        val code = conn.responseCode
        if (code != 200) throw IllegalStateException("TomTom gaf code $code")
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }

    /** Netwerkaanroep. Onthoudt het resultaat per adres, zodat het maar één keer opgezocht wordt. */
    fun geocode(c: Context, key: String, address: String): Point? {
        val prefs = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        val cacheKey = "geo_" + address.trim().lowercase()
        prefs.getString(cacheKey, null)?.split(",")?.let { p ->
            if (p.size == 2) return Point(p[0].toDouble(), p[1].toDouble())
        }
        val q = URLEncoder.encode(address.trim(), "UTF-8").replace("+", "%20")
        val json = get("https://api.tomtom.com/search/2/geocode/$q.json?key=$key&countrySet=NL&limit=1&language=nl-NL")
        val pos = json.optJSONArray("results")?.optJSONObject(0)?.optJSONObject("position") ?: return null
        val point = Point(pos.getDouble("lat"), pos.getDouble("lon"))
        prefs.edit().putString(cacheKey, "${point.lat},${point.lon}").apply()
        return point
    }

    /** Netwerkaanroep. Route met de actuele verkeerssituatie, eventueel via tussenpunten. */
    fun route(key: String, points: List<Point>): Route? {
        val path = points.joinToString(":") { "${it.lat},${it.lon}" }
        val json = get(
            "https://api.tomtom.com/routing/1/calculateRoute/$path/json?key=$key" +
                    "&traffic=true&travelMode=car&routeType=fastest&departAt=now"
        )
        val summary = json.optJSONArray("routes")?.optJSONObject(0)?.optJSONObject("summary") ?: return null
        return Route(summary.optInt("travelTimeInSeconds"), summary.optInt("trafficDelayInSeconds"))
    }

    /** Netwerkaanroep. Maximumsnelheid van de weg op deze plek, of null als die onbekend is. */
    fun speedLimit(key: String, lat: Double, lon: Double, heading: Float?): Int? {
        val h = heading?.let { "&heading=${it.toInt()}" } ?: ""
        val json = get(
            "https://api.tomtom.com/search/2/reverseGeocode/$lat,$lon.json?key=$key" +
                    "&returnSpeedLimit=true&radius=40$h"
        )
        val raw = json.optJSONArray("addresses")?.optJSONObject(0)?.optJSONObject("address")
            ?.optString("speedLimit").orEmpty()
        val number = Regex("^(\\d+(?:\\.\\d+)?)").find(raw)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        return if (raw.uppercase().contains("MPH")) Math.round(number * 1.609).toInt() else Math.round(number).toInt()
    }
}
