package hu.otthon.launcher

import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

enum class Phase(val key: String) {
    HAJNAL("hajnal"), NAPPAL("nappal"), ALKONY("alkony"), EJSZAKA("ejszaka")
}

object Sun {
    private fun rad(d: Double) = d * PI / 180.0
    private fun deg(r: Double) = r * 180.0 / PI

    /** Returns sunrise and sunset as epoch millis, or null in polar day/night. */
    fun times(date: LocalDate, lat: Double, lon: Double): Pair<Long, Long>? {
        val jd = date.toEpochDay() + 2440587.5
        val n = ceil(jd - 2451545.0 + 0.0008)
        val jStar = n - lon / 360.0
        val m = (357.5291 + 0.98560028 * jStar) % 360.0
        val c = 1.9148 * sin(rad(m)) + 0.02 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = (m + c + 180.0 + 102.9372) % 360.0
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
        val sinDecl = sin(rad(lambda)) * sin(rad(23.4397))
        val decl = asin(sinDecl)
        val cosW = (sin(rad(-0.833)) - sin(rad(lat)) * sinDecl) / (cos(rad(lat)) * cos(decl))
        if (cosW < -1.0 || cosW > 1.0) return null
        val w = deg(acos(cosW))
        val rise = jTransit - w / 360.0
        val set = jTransit + w / 360.0
        return Pair(toMillis(rise), toMillis(set))
    }

    private fun toMillis(j: Double): Long = ((j - 2440587.5) * 86400000.0).toLong()

    fun phase(now: Long, lat: Double, lon: Double): Phase {
        val today = java.time.Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val t = times(today, lat, lon) ?: return Phase.NAPPAL
        val (rise, set) = t
        val min = 60_000L
        return when {
            now >= rise - 50 * min && now < rise + 70 * min -> Phase.HAJNAL
            now >= rise + 70 * min && now < set - 70 * min -> Phase.NAPPAL
            now >= set - 70 * min && now < set + 50 * min -> Phase.ALKONY
            else -> Phase.EJSZAKA
        }
    }
}
