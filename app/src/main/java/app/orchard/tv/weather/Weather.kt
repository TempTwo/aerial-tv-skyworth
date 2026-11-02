package app.orchard.tv.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.orchard.tv.net.ClockTls
import app.orchard.tv.time.TimeSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }

data class WeatherNow(val celsius: Int, val kind: WeatherKind, val day: Boolean)

/**
 * The weather beside the home clock, ported from Quiet TV's weather (github.com/BeatsAD/quiet-tv).
 *
 * No account needed: the first provider is UAPI (uapis.cn), answered by adcode when the owner
 * named a city, or by the network address when nobody did, and the fallback is Open-Meteo, both
 * keyless. The host/key screen of the earlier QWeather port is gone.
 *
 * Fetched when Home comes to the front, at most every half hour (Quiet TV's cadence); nothing
 * runs while the TV is idle. Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    /** Quiet TV re-reads the weather every half hour. */
    private const val REFRESH_MS = 30 * 60 * 1000L
    /** A place guessed from the network is refreshed once a day; a place the owner named never is. */
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 8_000

    private const val UAPI_BASE = "https://uapis.cn/api/v1"
    private const val OPEN_METEO_BASE = "https://api.open-meteo.com"

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while it is guessed from the network. */
    var chosen by mutableStateOf(false); private set

    /** Weather needs no account any more; the phone page has nothing to fill in. */
    var configured by mutableStateOf(true); private set

    /** The last failure, for the phone page to show; null after a good reading. */
    var lastError by mutableStateOf<String?>(null); private set

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null

    /** One place: the name to show, the adcode the UAPI answers by, and where it is. */
    private data class Place(
        val name: String,
        val adcode: String = "",
        val lat: Float = Float.NaN,
        val lon: Float = Float.NaN,
    ) {
        val located: Boolean get() = !lat.isNaN() && !lon.isNaN()
    }

    fun init(context: Context) {
        if (started) return
        started = true
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        restoreCachedReading()
    }

    private fun restoreCachedReading() {
        val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull() ?: return
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) >= STALE_MS) return
        now = WeatherNow(prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true))
        place = prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
    }

    fun refreshIfStale() {
        if (!started) return
        if (System.currentTimeMillis() - lastAttempt < REFRESH_MS) return
        refresh()
    }

    private fun refresh() {
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch { measure() }
    }

    /** One reading; writes the Compose state on the main thread. */
    private suspend fun measure(): Boolean {
        val result = withContext(Dispatchers.IO) { runCatching { query() } }
        val error = result.exceptionOrNull()
        if (error != null) {
            if (error !is CancellationException) {
                Log.w(TAG, "weather unavailable: ${error.javaClass.simpleName}: ${error.message}")
                lastError = error.message
            }
            return false
        }
        val reading = result.getOrThrow()
        lastError = null
        now = reading.second
        place = reading.first
        prefs.edit {
            putLong("fetched_at", System.currentTimeMillis())
            putInt("celsius", reading.second.celsius)
            putString("kind", reading.second.kind.name)
            putBoolean("day", reading.second.day)
            putString("place", reading.first)
            putBoolean("chosen", chosen)
        }
        return true
    }

    /**
     * The place the reading is for: a city the owner named, or one guessed from the network
     * address, re-guessed at most once a day. Each value is the name shown next to the clock.
     */
    private fun resolvePlace(): Pair<Place, WeatherNow?> {
        val savedName = prefs.getString("place_name", null)
        val chosen = prefs.getBoolean("chosen", false)
        val savedLat = prefs.getFloat("lat", Float.NaN)
        val savedLon = prefs.getFloat("lon", Float.NaN)
        val savedAdcode = prefs.getString("adcode", null).orEmpty()
        val autoAt = prefs.getLong("auto_at", 0L)
        if (chosen && !savedName.isNullOrBlank()) {
            return Place(name = savedName, adcode = savedAdcode, lat = savedLat, lon = savedLon) to null
        }
        // An automatic place lives a day; the first answer also carries a reading already.
        if (System.currentTimeMillis() - autoAt < RELOCATE_MS && !savedName.isNullOrBlank()) {
            return Place(name = savedName, adcode = savedAdcode, lat = savedLat, lon = savedLon) to null
        }
        // One request without an adcode delivers everything at once: the city the network
        // address is in, its adcode, and a reading to show right away.
        val ipReading = uapiWeather(null)
        val city = ipReading.optString("city").ifBlank { ipReading.optString("district") }
        val district = ipReading.optString("district")
        val shown = district.ifBlank { city }
        val adcode = ipReading.optString("adcode", "")
        saveAutomaticPlace(shown, adcode)
        return Place(name = shown, adcode = adcode) to readingFrom(ipReading, null)
    }

    /** From a UAPI weather answer to the display values; icon codes are QWeather's. */
    private fun readingFrom(body: JSONObject, dayOverride: Boolean?): WeatherNow {
        val celsius = body.optDouble("temperature", Double.NaN)
        if (celsius.isNaN()) throw IllegalStateException("天气服务没有返回温度")
        return WeatherNow(
            celsius = celsius.roundToInt(),
            kind = kindOf(body.optString("weather_icon", "999")),
            day = dayOverride ?: isDayFromCache(),
        )
    }

    /** The chosen city and its coordinates, as the phone page handed them over. */
    suspend fun choose(name: String, lat: Double, lon: Double): Boolean {
        prefs.edit {
            putFloat("lat", lat.toFloat())
            putFloat("lon", lon.toFloat())
            putString("place_name", name)
            putBoolean("chosen", true)
            remove("adcode")
            remove("auto_at")
        }
        chosen = true
        lastAttempt = 0
        return measure()
    }

    /** Back to guessing the place from the network address. */
    fun useNetworkLocation() {
        prefs.edit {
            putBoolean("chosen", false)
            remove("place_name")
            remove("adcode")
            remove("lat")
            remove("lon")
            remove("auto_at")
        }
        chosen = false
        lastAttempt = 0
        refresh()
    }

    /** Off the main thread. UAPI first, then Open-Meteo when coordinates are known. */
    private fun query(): Pair<String, WeatherNow> {
        val (place, ipReading) = resolvePlace()
        if (ipReading != null) return place.name to ipReading
        // The adcode the owner's chosen city really is: looked up once from its coordinates.
        val adcode = place.adcode.ifBlank { adcodeFor(place) }
        if (adcode.isNotBlank()) {
            try {
                prefs.edit { putString("adcode", adcode) }
                return place.name to readingFrom(uapiWeather(adcode), null)
            } catch (error: Exception) {
                if (!place.located) throw IllegalStateException(readable(error), error)
            }
        }
        // Fallback, also keyless: Open-Meteo answers by raw coordinates.
        if (place.located) return place.name to openMeteo(place.lat.toDouble(), place.lon.toDouble())
        throw IllegalStateException("暂时无法获取天气")
    }

    /** UAPI's district list doubles as its coordinate look-up. */
    private fun adcodeFor(place: Place): String {
        val url = "$UAPI_BASE/misc/district?lat=${coord(place.lat)}&lng=${coord(place.lon)}&country=CN&limit=5"
        val results = JSONObject(get(url, null)).optJSONArray("results") ?: return ""
        for (i in 0 until results.length()) {
            val code = results.getJSONObject(i).optString("adcode", "")
            if (code.matches(Regex("[0-9]{6}"))) return code
        }
        return ""
    }

    /** One UAPI weather answer; without an adcode the network address decides the city. */
    private fun uapiWeather(adcode: String?): JSONObject {
        val body = JSONObject(get("$UAPI_BASE/misc/weather?lang=zh" + (adcode?.let { "&adcode=$it" } ?: ""), null))
        if (!adcode.isNullOrBlank() && adcode != body.optString("adcode")) {
            throw IllegalStateException("天气返回的城市不对")
        }
        return body
    }

    /** Saves an automatically guessed place, with the coordinates for the Open-Meteo fallback. */
    private fun saveAutomaticPlace(name: String, adcode: String) {
        val savedLon = prefs.getFloat("lon", Float.NaN)
        val savedLat = prefs.getFloat("lat", Float.NaN)
        var lat = savedLat
        var lon = savedLon
        if (adcode.isNotBlank() && (lat.isNaN() || lon.isNaN())) {
            // The center of the same adcode, from the district list.
            runCatching {
                val url = "$UAPI_BASE/misc/district?adcode=$adcode&country=CN&limit=1"
                val first = JSONObject(get(url, null)).optJSONArray("results")?.optJSONObject(0) ?: return@runCatching
                val center = first.optJSONObject("center") ?: return@runCatching
                val foundLat = center.optDouble("lat", Double.NaN).toFloat()
                val foundLon = center.optDouble("lng", Double.NaN).toFloat()
                if (foundLat.isNaN() || foundLon.isNaN()) return@runCatching
                lat = foundLat
                lon = foundLon
            }
        }
        prefs.edit { putString("place_name", name); putString("adcode", adcode); putString("place", name); putLong("auto_at", System.currentTimeMillis()) }
        if (!lat.isNaN() && !lon.isNaN()) {
            prefs.edit { putFloat("lat", lat); putFloat("lon", lon) }
        }
    }

    /** Open-Meteo's current hour, keyless: both the temperature and the day flag come with it. */
    private fun openMeteo(lat: Double, lon: Double): WeatherNow {
        val url = "$OPEN_METEO_BASE/v1/forecast?latitude=%.2f&longitude=%.2f".format(Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code,is_day&temperature_unit=celsius&timeformat=unixtime&forecast_days=1"
        val current = JSONObject(get(url, null)).optJSONObject("current")
            ?: throw IllegalStateException("Open-Meteo 返回了空数据")
        val celsius = current.optDouble("temperature_2m", Double.NaN)
        if (celsius.isNaN()) throw IllegalStateException("Open-Meteo 返回了空温度")
        return WeatherNow(
            celsius = celsius.roundToInt(),
            kind = meteoKind(current.optInt("weather_code", -1)),
            day = current.optInt("is_day", 1) == 1,
        )
    }

    private fun readable(error: Exception): String = error.message ?: "天气服务暂时不可用"

    private fun get(url: String, apiKey: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.useCaches = false
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Accept-Encoding", "gzip")
        c.setRequestProperty("User-Agent", "AerialTV/1.21")
        if (apiKey != null) c.setRequestProperty("X-QW-Api-Key", apiKey)
        if (c is HttpsURLConnection) c.sslSocketFactory = ClockTls.socketFactory
        return try {
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val body = if ("gzip".equals(c.contentEncoding, true)) {
                java.util.zip.GZIPInputStream(bytes.inputStream()).use { it.readBytes() }.decodeToString()
            } else bytes.decodeToString()
            if (status == 429) throw java.io.IOException("天气请求太频繁，稍后再试")
            if (status !in 200..299) throw IllegalStateException(errorOf(body) ?: "HTTP $status")
            body
        } finally {
            c.disconnect()
        }
    }

    /** UAPI puts the human-readable reason in the JSON body. */
    private fun errorOf(body: String): String? = runCatching {
        JSONObject(body).optString("message").takeIf { it.isNotBlank() }
    }.getOrNull()

    /** QWeather's condition codes, grouped into the glyphs the home screen draws. */
    private fun kindOf(code: String): WeatherKind = when (code.toIntOrNull() ?: 999) {
        100 -> WeatherKind.Clear
        150 -> WeatherKind.Clear
        101, 102, 103 -> WeatherKind.PartlyCloudy
        104 -> WeatherKind.Cloudy
        302, 303, 304 -> WeatherKind.Thunder
        in 300..399 -> WeatherKind.Rain
        in 400..499 -> WeatherKind.Snow
        in 500..515 -> WeatherKind.Fog
        else -> WeatherKind.Cloudy
    }

    /** Open-Meteo's weather codes, the same grouping Quiet TV's table makes (conditions in Chinese). */
    private fun meteoKind(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3, 45, 48 -> WeatherKind.Cloudy
        in 51..67, in 80..82 -> WeatherKind.Rain
        in 71..77, 85, 86 -> WeatherKind.Snow
        95, 96, 99 -> WeatherKind.Thunder
        else -> WeatherKind.Cloudy
    }

    /**
     * QWeather's condition code says nothing about day or night, so the sun's height decides
     * whether the clock shows a sun or a moon. The time is the one the system clock keeps.
     */
    private fun isDayFromCache(): Boolean {
        val lat = prefs.getFloat("lat", Float.NaN)
        val lon = prefs.getFloat("lon", Float.NaN)
        if (lat.isNaN() || lon.isNaN()) return TimeSync.now().hour in 7..18
        val time = TimeSync.now()
        val zone = ZoneId.systemDefault().rules.getOffset(time).totalSeconds / 3600.0
        // Local solar time: the zone's time, corrected for how far the place is from its meridian.
        val solar = time.hour + time.minute / 60.0 - zone + lon / 15.0
        val decl = Math.toRadians(23.44 * sin(Math.toRadians(360.0 / 365 * (284 + time.dayOfYear))))
        val hour = Math.toRadians(15.0 * (solar - 12))
        val phi = Math.toRadians(lat.toDouble())
        return asin(sin(phi) * sin(decl) + cos(phi) * cos(decl) * cos(hour)) > 0
    }

    private fun coord(value: Float): String = String.format(Locale.US, "%.2f", value)
}
