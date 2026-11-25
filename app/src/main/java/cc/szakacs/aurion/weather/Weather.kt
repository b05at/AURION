package cc.szakacs.aurion.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import cc.szakacs.aurion.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

data class Place(val name: String, val lat: Double, val lon: Double)

data class DayForecast(
    val date: LocalDate,
    val code: Int,
    val tMin: Double,
    val tMax: Double,
    val rainProb: Int,
    val rainSum: Double,
    val windMax: Double,
)

data class Forecast(
    val place: Place,
    val currentTemp: Double,
    val currentFeels: Double,
    val currentCode: Int,
    val currentWind: Double,
    val days: List<DayForecast>,
)

/** Kostenloses Wetter über Open-Meteo (kein Konto, kein API-Key). */
object WeatherService {

    suspend fun forecast(place: Place): Forecast = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max" +
            "&timezone=auto&forecast_days=7"
        val o = JSONObject(get(url))
        val c = o.getJSONObject("current")
        val d = o.getJSONObject("daily")
        val dates = d.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            DayForecast(
                date = LocalDate.parse(dates.getString(i)),
                code = d.getJSONArray("weather_code").optInt(i),
                tMin = d.getJSONArray("temperature_2m_min").optDouble(i),
                tMax = d.getJSONArray("temperature_2m_max").optDouble(i),
                rainProb = d.getJSONArray("precipitation_probability_max").optInt(i, 0),
                rainSum = d.getJSONArray("precipitation_sum").optDouble(i, 0.0),
                windMax = d.getJSONArray("wind_speed_10m_max").optDouble(i, 0.0),
            )
        }
        Forecast(
            place,
            c.optDouble("temperature_2m"),
            c.optDouble("apparent_temperature"),
            c.optInt("weather_code"),
            c.optDouble("wind_speed_10m"),
            days
        )
    }

    /** Ortsname -> Koordinaten (Open-Meteo Geocoding) */
    suspend fun geocode(name: String, lang: String = "de"): Place? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://geocoding-api.open-meteo.com/v1/search?name=" +
                URLEncoder.encode(name.trim(), "UTF-8") + "&count=1&language=$lang&format=json"
            val r = JSONObject(get(url)).optJSONArray("results") ?: return@runCatching null
            if (r.length() == 0) return@runCatching null
            val p = r.getJSONObject(0)
            Place(p.getString("name"), p.getDouble("latitude"), p.getDouble("longitude"))
        }.getOrNull()
    }

    /** Fester Ort aus den Einstellungen, sonst Gerätestandort */
    suspend fun homePlace(ctx: Context): Place? {
        val s = Settings.get(ctx)
        if (!s.weatherUseDevice && s.weatherPlaceName != null) {
            return Place(s.weatherPlaceName!!, s.weatherLat, s.weatherLon)
        }
        deviceLocation(ctx)?.let { loc ->
            val name = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(ctx, Locale.GERMAN).getFromLocation(loc.latitude, loc.longitude, 1)
                        ?.firstOrNull()?.let { it.locality ?: it.subAdminArea }
                }.getOrNull()
            }
            return Place(name ?: "hier", loc.latitude, loc.longitude)
        }
        return s.weatherPlaceName?.let { Place(it, s.weatherLat, s.weatherLon) }
    }

    fun hasLocationPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private suspend fun deviceLocation(ctx: Context): Location? {
        if (!hasLocationPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        val last = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 3 * 3600_000L) return last
        if (Build.VERSION.SDK_INT >= 30 && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            val fresh = withTimeoutOrNull(6000) {
                suspendCancellableCoroutine<Location?> { cont ->
                    runCatching {
                        lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { cont.resume(it) }
                    }.onFailure { cont.resume(null) }
                }
            }
            if (fresh != null) return fresh
        }
        return last
    }

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (conn.responseCode !in 200..299) throw Exception("Wetterdienst nicht erreichbar (${conn.responseCode})")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ---------- Texte ----------

    fun describe(code: Int, en: Boolean): String = when (code) {
        0 -> if (en) "clear sky" else "klar und sonnig"
        1 -> if (en) "mostly clear" else "überwiegend sonnig"
        2 -> if (en) "partly cloudy" else "teilweise bewölkt"
        3 -> if (en) "overcast" else "bedeckt"
        45, 48 -> if (en) "foggy" else "neblig"
        51, 53, 55 -> if (en) "drizzle" else "Nieselregen"
        56, 57 -> if (en) "freezing drizzle" else "gefrierender Nieselregen"
        61 -> if (en) "light rain" else "leichter Regen"
        63 -> if (en) "rain" else "Regen"
        65 -> if (en) "heavy rain" else "starker Regen"
        66, 67 -> if (en) "freezing rain" else "gefrierender Regen"
        71 -> if (en) "light snow" else "leichter Schneefall"
        73 -> if (en) "snow" else "Schneefall"
        75 -> if (en) "heavy snow" else "starker Schneefall"
        77 -> if (en) "snow grains" else "Schneegriesel"
        80, 81 -> if (en) "rain showers" else "Regenschauer"
        82 -> if (en) "heavy showers" else "heftige Regenschauer"
        85, 86 -> if (en) "snow showers" else "Schneeschauer"
        95 -> if (en) "thunderstorms" else "Gewitter"
        96, 99 -> if (en) "thunderstorms with hail" else "Gewitter mit Hagel"
        else -> if (en) "mixed weather" else "wechselhaft"
    }

    fun isWet(code: Int) = code in 51..67 || code in 80..82 || code >= 95
    fun isSnow(code: Int) = code in 71..77 || code in 85..86

    fun deg(v: Double) = v.roundToInt().toString()
}
