package nl.koen.autoscherm

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Berekent of de zon onder is op een bepaalde plek (NOAA-benadering, nauwkeurig op een paar minuten). */
object Sun {

    fun isDark(lat: Double, lon: Double, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }
        val day = cal.get(Calendar.DAY_OF_YEAR)
        val minutesUtc = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val g = 2 * Math.PI / 365 * (day - 1 + (minutesUtc / 60.0 - 12) / 24)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) -
                0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
                0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)

        val latR = Math.toRadians(lat)
        val x = (cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)).coerceIn(-1.0, 1.0)
        val ha = Math.toDegrees(acos(x))

        val sunrise = 720 - 4 * (lon + ha) - eqTime
        val sunset = 720 - 4 * (lon - ha) - eqTime
        return minutesUtc < sunrise || minutesUtc > sunset
    }
}
