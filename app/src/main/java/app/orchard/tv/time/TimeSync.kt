package app.orchard.tv.time

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Collections
import java.util.Locale
import java.util.TimeZone

/**
 * The home clock has to be right the moment the desktop paints, but Android's own network time
 * sync can lag behind the network coming up, and this panel's clock starts years wrong after a
 * cold boot. Two things cover that, and neither waits for the system clock to be fixed:
 *
 * - The last good measurement is kept as an anchor (the real time it read, next to the device
 *   clock at that instant). On the next start the clock is that anchor plus the device time since,
 *   so the very first frame is already right, without a network round trip.
 * - Alibaba's time servers are asked from the moment the app process starts, every source at once,
 *   taking the answer as soon as two agree, so the anchor is refreshed within a fraction of a
 *   second of the network being up.
 *
 * Plain HTTP on purpose: a years-wrong system clock fails HTTPS certificate checks before we ever
 * get an answer.
 */
object TimeSync {
    private const val TAG = "AerialTime"
    private const val PREFS = "time"

    /** How often to re-measure, in case Android fixes the system clock underneath us. */
    private const val RESYNC_MS = 10 * 60 * 1000L

    /** How long to wait for the answers before giving up on this round. */
    private const val ASK_MS = 5_000L
    private const val TIMEOUT_MS = 4_000

    /** An answer older than this is a broken one (2023-11), not a time. */
    private const val SANITY = 1_700_000_000_000L

    /** Past this, the anchor has been carried by an unreliable RTC for too long to trust. */
    private const val ANCHOR_MAX_MS = 7 * 24 * 60 * 60 * 1000L

    /** Alibaba's timestamp API: epoch milliseconds, no key. */
    private const val API = "http://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp"

    /**
     * Fallbacks: every web server sends a Date header, and Alibaba's own hosts keep it accurate.
     * taobao.com is left out on purpose — its CDN answered six minutes behind.
     */
    private val hosts = listOf("http://www.aliyun.com/", "http://www.alibaba.com/")

    /**
     * Milliseconds to add to System.currentTimeMillis() to get the real time, once this session has
     * measured it. Compose state, so the clock repaints the moment the answer lands.
     */
    var offsetMs by mutableStateOf(0L)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var started = false
    private var synced = false

    /** The last good measurement, kept across starts. */
    private var anchorServer = 0L
    private var anchorDevice = 0L
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (started) return
        started = true
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        anchorServer = prefs?.getLong("server", 0L) ?: 0L
        anchorDevice = prefs?.getLong("device", 0L) ?: 0L
        start()
    }

    /** The network just came up: there is no point sitting out the backoff. */
    fun onNetworkAvailable() {
        if (!started || synced) return
        start()
    }

    /** Starts over, so a network event or a fresh boot measures again straight away. */
    private fun start() {
        loop?.cancel()
        loop = scope.launch {
            var wait = 0L
            while (true) {
                if (wait > 0) delay(wait)
                val offset = withContext(Dispatchers.IO) {
                    runCatching { ask() }
                        .onFailure { Log.w(TAG, "time unavailable: ${it.javaClass.simpleName}: ${it.message}") }
                        .getOrNull()
                }
                if (offset != null) {
                    offsetMs = offset
                    synced = true
                    saveAnchor(offset)
                    Log.i(TAG, "clock corrected by ${offset}ms")
                    wait = RESYNC_MS
                } else {
                    // At boot the network is often not up yet: come back sooner the first times.
                    wait = if (wait == 0L) 4_000L else minOf(wait * 2, 60_000L)
                }
            }
        }
    }

    /** The real local time: the measured difference, or the anchor, or the device clock. */
    fun now(): LocalDateTime =
        Instant.ofEpochMilli(realNowMs()).atZone(ZoneId.systemDefault()).toLocalDateTime()

    /** The corrected epoch milliseconds, for anything that has to trust a certificate's dates. */
    fun nowMs(): Long = realNowMs()

    private fun realNowMs(): Long {
        val device = System.currentTimeMillis()
        if (synced) return device + offsetMs
        // Before this session's answer: the anchor plus the time the device clock has run since it.
        // A device clock that went backwards means the RTC was lost, and the anchor with it.
        if (anchorServer > 0 && device >= anchorDevice && device - anchorDevice < ANCHOR_MAX_MS) {
            return anchorServer + (device - anchorDevice)
        }
        return device
    }

    /**
     * Off the main thread. Every source is asked at once; the moment two answers are in (or all of
     * them have finished), the median is taken, so a single host with a wrong clock cannot win.
     */
    private suspend fun ask(): Long? = withTimeoutOrNull(ASK_MS) {
        val answers = Collections.synchronizedList(mutableListOf<Long>())
        val jobs = mutableListOf<Job>()
        coroutineScope {
            // One source failing must not cancel the others: each is contained on its own.
            jobs += async(Dispatchers.IO) {
                runCatching { apiTime() }.getOrNull()?.let { answers.add(it) }
            }
            hosts.forEach { host ->
                jobs += async(Dispatchers.IO) {
                    runCatching { dateFrom(host) }.getOrNull()?.let { answers.add(it) }
                }
            }
            while (true) {
                if (answers.count { it > SANITY } >= 2) break
                if (jobs.all { it.isCompleted }) break
                delay(40)
            }
            jobs.forEach { it.cancel() }
        }
        val sane = answers.filter { it > SANITY }
        if (sane.isEmpty()) null else sane.sorted()[sane.size / 2] - System.currentTimeMillis()
    }

    private fun saveAnchor(offset: Long) {
        val device = System.currentTimeMillis()
        anchorServer = device + offset
        anchorDevice = device
        prefs?.edit {
            putLong("server", anchorServer)
            putLong("device", anchorDevice)
        }
    }

    /** Epoch milliseconds from Alibaba's timestamp API. */
    private fun apiTime(): Long? =
        JSONObject(read(API)).getJSONObject("data").getString("t").toLongOrNull()

    /** The server's Date header, in epoch milliseconds. */
    private fun dateFrom(host: String): Long? {
        val c = URL(host).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.requestMethod = "HEAD"
        c.useCaches = false
        // These hosts answer 301 to HTTPS, and following it would fail the certificate check
        // because the system clock is years wrong — the very thing we are here to fix.
        c.instanceFollowRedirects = false
        return try {
            c.connect()
            // Reading the status forces the response to be parsed, so the headers are there.
            c.responseCode
            c.getHeaderField("Date")?.let(::parseHttpDate)
        } finally {
            c.disconnect()
        }
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.useCaches = false
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    private fun parseHttpDate(raw: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .parse(raw)!!.time
    }.getOrNull()
}
