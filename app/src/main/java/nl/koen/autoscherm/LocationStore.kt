package nl.koen.autoscherm

import android.content.Context
import android.location.Location

/** Bewaart de laatst bekende plek van de auto en waar je geparkeerd hebt. */
object LocationStore {

    data class Spot(val lat: Double, val lon: Double, val time: Long)

    private fun prefs(c: Context) = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)

    fun saveLast(c: Context, loc: Location) {
        prefs(c).edit()
            .putString("last_lat", loc.latitude.toString())
            .putString("last_lon", loc.longitude.toString())
            .putLong("last_time", loc.time)
            .apply()
    }

    fun last(c: Context): Spot? = read(c, "last")

    fun parked(c: Context): Spot? = read(c, "parked")

    /** Wordt aangeroepen als de carkit loskoppelt. */
    fun markParked(c: Context, fresh: Location?) {
        val best = when {
            fresh != null && (last(c)?.time ?: 0L) <= fresh.time -> Spot(fresh.latitude, fresh.longitude, System.currentTimeMillis())
            else -> last(c)?.copy(time = System.currentTimeMillis())
        } ?: return
        prefs(c).edit()
            .putString("parked_lat", best.lat.toString())
            .putString("parked_lon", best.lon.toString())
            .putLong("parked_time", best.time)
            .apply()
    }

    private fun read(c: Context, key: String): Spot? {
        val p = prefs(c)
        val lat = p.getString("${key}_lat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("${key}_lon", null)?.toDoubleOrNull() ?: return null
        return Spot(lat, lon, p.getLong("${key}_time", 0L))
    }
}
