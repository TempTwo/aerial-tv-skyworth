package app.orchard.tv.update

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import app.orchard.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Whether a newer release is out (issues #24, #76). Most owners stay on the version they installed
 * and report bugs fixed long ago. At most once a day Aerial reads the latest release's tag from the
 * website (reachable in China, unlike GitHub); Control Center then names the new version and says
 * how to update. Nothing is downloaded or installed in the background.
 */
object Updates {
    private const val TAG = "AerialUpdates"
    private const val PREFS = "updates"
    private const val VERSION_URL = "https://aerial.qust.me/download/version.txt"
    private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L

    /** This build's version, e.g. "1.17" (the guard build's "-guard" left off). */
    val current: String = BuildConfig.VERSION_NAME.substringBefore('-')

    /** The newer release's version, or null when this is the latest (or nothing is known yet). */
    var available by mutableStateOf<String?>(null); private set

    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L

    fun init(context: Context) {
        if (started) return
        started = true
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        available = prefs.getString("latest", null)?.takeIf { newer(it, current) }
    }

    fun checkIfStale() {
        if (!started) return
        val now = System.currentTimeMillis()
        if (now - lastAttempt < CHECK_EVERY_MS / 24 || now - prefs.getLong("checked_at", 0) < CHECK_EVERY_MS) return
        lastAttempt = now
        scope.launch {
            val latest = withContext(Dispatchers.IO) {
                runCatching { read(VERSION_URL).trim().removePrefix("v") }
                    .onFailure { Log.w(TAG, "could not check for a new version", it) }
                    .getOrNull()
                    ?.takeIf { it.matches(Regex("\\d+(\\.\\d+)*")) }
            } ?: return@launch
            prefs.edit { putString("latest", latest); putLong("checked_at", System.currentTimeMillis()) }
            available = latest.takeIf { newer(it, current) }
        }
    }

    /** Whether version [a] comes after [b], comparing dotted numbers ("1.10" after "1.9"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
