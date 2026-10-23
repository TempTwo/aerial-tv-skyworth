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
 * The weather beside the home clock, from QWeather (和风天气).
 *
 * The account's API host and key are typed on the phone page and kept in this app's preferences:
 * a key baked into an open-source APK is a key given away, and QWeather's host is per account
 * anyway.
 *
 * Fetched when Home comes to the front, at most once an hour; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val REFRESH_MS = 60 * 60 * 1000L
    /** A place guessed from the network is refreshed once a day; a place the owner named never is. */
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 8_000

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while it is guessed from the network. */
    var chosen by mutableStateOf(false); private set

    /** True once both the API host and the key have been saved from the phone page. */
    var configured by mutableStateOf(false); private set

    /** The last failure, for the phone page to show; null after a good reading. */
    var lastError by mutableStateOf<String?>(null); private set

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        place = prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
        configured = host().isNotBlank() && key().isNotBlank()
        // An unconfigured weather block shows its placeholder, never a reading from an account
        // that is no longer there.
        if (configured && System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) now = WeatherNow(prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true))
        }
    }

    fun refreshIfStale() {
        if (!started || !configured) return
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
        now = reading
        prefs.edit {
            putLong("fetched_at", System.currentTimeMillis())
            putInt("celsius", reading.celsius)
            putString("kind", reading.kind.name)
            putBoolean("day", reading.day)
        }
        return true
    }

    /**
     * Off the main thread. QWeather's current weather is asked by latitude and longitude, so a
     * place picked on the phone page needs no look-up: its coordinates are already known.
     */
    private fun query(): WeatherNow {
        val host = host()
        val apiKey = key()
        if (host.isBlank() || apiKey.isBlank()) throw IllegalStateException("还没有设置和风天气")
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        if (!chosen && (lat.isNaN() || System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS)) {
            locate()
            lat = prefs.getFloat("lat", Float.NaN)
            lon = prefs.getFloat("lon", Float.NaN)
        }
        if (lat.isNaN() || lon.isNaN()) throw IllegalStateException("还没有定位到城市")
        val url = "https://$host/weather/v1/current/${coord(lat)}/${coord(lon)}?lang=zh"
        val json = JSONObject(get(url, apiKey))
        val celsius = json.optJSONObject("temperature")?.optDouble("value", Double.NaN) ?: Double.NaN
        if (celsius.isNaN()) throw IllegalStateException("和风天气返回了空数据")
        val code = json.optJSONObject("condition")?.optString("code").orEmpty()
        return WeatherNow(
            celsius = celsius.roundToInt(),
            kind = kindOf(code),
            day = isDay(lat.toDouble(), lon.toDouble()),
        )
    }

    /**
     * A place picked from the phone page's list: the coordinates come with it, so there is nothing
     * to look up and no way to land on a village of the same name.
     */
    suspend fun choose(name: String, lat: Double, lon: Double): Boolean {
        prefs.edit {
            putFloat("lat", lat.toFloat())
            putFloat("lon", lon.toFloat())
            putString("place", name)
            putBoolean("chosen", true)
            remove("located_at")
        }
        chosen = true
        place = name
        lastAttempt = 0
        return measure()
    }

    /**
     * Saves the account typed on the phone page and tries it at once. An empty key keeps the one
     * already saved, so the key never has to travel back to the page.
     */
    suspend fun saveAccount(host: String, key: String): Boolean {
        val clean = normalizeHost(host)
        val kept = key.trim().ifBlank { prefs.getString("key", "").orEmpty() }
        prefs.edit {
            putString("host", clean)
            putString("key", kept)
        }
        configured = clean.isNotBlank() && kept.isNotBlank()
        lastAttempt = 0
        if (!configured) {
            lastError = "请填写 API Host 和 API Key"
            return false
        }
        return measure()
    }

    /** The saved API host, for the phone page to show. The key is never handed back. */
    fun apiHost(): String = if (::prefs.isInitialized) prefs.getString("host", "").orEmpty() else ""

    /** Back to guessing the place from the network. */
    fun useNetworkLocation() {
        prefs.edit { putBoolean("chosen", false); remove("lat"); remove("lon"); remove("located_at") }
        chosen = false
        lastAttempt = 0
        refresh()
    }

    /**
     * ip-api.com: plain HTTP because it has no key and this panel's clock cannot do HTTPS until it
     * has been synced. A home behind a proxy may resolve to another city; the phone page's list is
     * there for that.
     */
    private fun locate() {
        val json = JSONObject(get("http://ip-api.com/json/?lang=zh-CN&fields=status,city,lat,lon", null))
        if (json.optString("status") != "success") throw IllegalStateException("定位失败，请在手机页面选择城市")
        prefs.edit {
            putFloat("lat", json.getDouble("lat").toFloat())
            putFloat("lon", json.getDouble("lon").toFloat())
            putString("place", json.optString("city").ifBlank { null })
            putLong("located_at", System.currentTimeMillis())
        }
        place = prefs.getString("place", null)
    }

    private fun host(): String = if (::prefs.isInitialized) prefs.getString("host", "").orEmpty() else ""

    private fun key(): String = if (::prefs.isInitialized) prefs.getString("key", "").orEmpty() else ""

    private fun normalizeHost(raw: String): String =
        raw.trim().removePrefix("https://").removePrefix("http://").trimEnd('/').substringBefore('/')

    private fun coord(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun get(url: String, apiKey: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.useCaches = false
        c.setRequestProperty("Accept", "application/json")
        if (apiKey != null) c.setRequestProperty("X-QW-Api-Key", apiKey)
        if (c is HttpsURLConnection) c.sslSocketFactory = ClockTls.socketFactory
        return try {
            val status = c.responseCode
            val body = (if (status in 200..299) c.inputStream else c.errorStream)
                ?.use { it.readBytes().decodeToString() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(errorOf(body) ?: "HTTP $status")
            body
        } finally {
            c.disconnect()
        }
    }

    /** QWeather puts the human-readable reason in error.detail. */
    private fun errorOf(body: String): String? = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("detail")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** QWeather's condition codes, grouped into the glyphs the home screen draws. */
    private fun kindOf(code: String): WeatherKind = when (code.toIntOrNull() ?: 999) {
        100 -> WeatherKind.Clear
        101, 102, 103 -> WeatherKind.PartlyCloudy
        104 -> WeatherKind.Cloudy
        302, 303, 304 -> WeatherKind.Thunder
        in 300..399 -> WeatherKind.Rain
        in 400..499 -> WeatherKind.Snow
        in 500..515 -> WeatherKind.Fog
        else -> WeatherKind.Cloudy
    }

    /**
     * QWeather's condition code says nothing about day or night, so the sun's height decides
     * whether the clock shows a sun or a moon. The time is the one the clock shows, not the RTC's.
     */
    private fun isDay(lat: Double, lon: Double): Boolean {
        val time = TimeSync.now()
        val zone = ZoneId.systemDefault().rules.getOffset(time).totalSeconds / 3600.0
        // Local solar time: the zone's time, corrected for how far the place is from its meridian.
        val solar = time.hour + time.minute / 60.0 - zone + lon / 15.0
        val decl = Math.toRadians(23.44 * sin(Math.toRadians(360.0 / 365 * (284 + time.dayOfYear))))
        val hour = Math.toRadians(15.0 * (solar - 12))
        val phi = Math.toRadians(lat)
        return asin(sin(phi) * sin(decl) + cos(phi) * cos(decl) * cos(hour)) > 0
    }
}
