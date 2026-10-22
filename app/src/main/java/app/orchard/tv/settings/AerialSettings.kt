package app.orchard.tv.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import android.os.SystemClock
import java.security.MessageDigest

/**
 * How the focused app shows itself, named after tvOS's Accessibility > Increase Contrast > Focus Style.
 *
 * tvOS's standard focus relies on the Siri Remote's touch surface: the icon tilts with the thumb, and
 * that motion is what the eye follows. With the D-pad remotes these TVs ship with, the icon only
 * grows, and owners lost track of it. High Contrast adds a white frame and is the default here.
 */
enum class FocusStyle(val key: String) {
    Standard("standard"), HighContrast("high_contrast");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: HighContrast
    }
}

/** Aerial's own preferences. Compose state, written on the main thread only. */
object AerialSettings {
    private const val PREFS = "settings"
    private var started = false
    private lateinit var app: Context

    var focusStyle by mutableStateOf(FocusStyle.HighContrast); private set

    /** TV inputs pinned to Home as tiles, by TvInputInfo id. */
    var pinnedSources by mutableStateOf<Set<String>>(emptySet()); private set

    /**
     * The input to open when the TV is switched on (a set-top box, an Apple TV), by TvSource id;
     * null for none. Home still comes back to Aerial. Issue #25.
     */
    var bootSource by mutableStateOf<String?>(null); private set

    /**
     * Minutes of no input on Home before Aerial's screensaver starts (issue #66). The firmware's own
     * screensaver still caps it: Aerial starts just ahead of that one, see MainActivity.
     */
    /** Every app's name under its tile, not only the focused one's (issue #28). */
    var showAllNames by mutableStateOf(false); private set

    /**
     * Parental PIN (issue #10): asked before TV settings open and before hidden apps come back.
     * Kept as a SHA-256 hash. One right entry holds for a while, until Home is pressed.
     */
    var pinSet by mutableStateOf(false); private set
    private var pinHash: String? = null
    private var unlockedUntil = 0L
    private const val UNLOCK_MS = 10 * 60 * 1000L

    var screensaverMinutes by mutableStateOf(DEFAULT_SCREENSAVER_MINUTES); private set

    val screensaverChoices = listOf(2, 5, 10, 20)
    private const val DEFAULT_SCREENSAVER_MINUTES = 5

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        focusStyle = FocusStyle.of(prefs.getString("focus_style", null))
        pinnedSources = prefs.getStringSet("pinned_sources", null)?.toSet() ?: emptySet()
        bootSource = prefs.getString("boot_source", null)
        pinHash = prefs.getString("pin_hash", null)
        pinSet = pinHash != null
        showAllNames = prefs.getBoolean("show_all_names", false)
        screensaverMinutes = prefs.getInt("screensaver_minutes", DEFAULT_SCREENSAVER_MINUTES)
    }

    fun chooseBootSource(id: String?) {
        bootSource = id
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_source", id) }
    }

    val locked: Boolean get() = pinSet && SystemClock.elapsedRealtime() > unlockedUntil

    fun unlock(pin: String): Boolean {
        if (hash(pin) != pinHash) return false
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
        return true
    }

    fun relock() { unlockedUntil = 0L }

    /** Sets the PIN, or turns it off with null. */
    fun choosePin(pin: String?) {
        pinHash = pin?.let(::hash)
        pinSet = pinHash != null
        unlockedUntil = if (pin != null) SystemClock.elapsedRealtime() + UNLOCK_MS else 0L
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("pin_hash", pinHash) }
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("aerial:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun chooseShowAllNames(on: Boolean) {
        showAllNames = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_all_names", on) }
    }

    fun chooseScreensaverMinutes(minutes: Int) {
        screensaverMinutes = minutes
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("screensaver_minutes", minutes) }
    }

    fun setPinned(inputId: String, pinned: Boolean) {
        pinnedSources = if (pinned) pinnedSources + inputId else pinnedSources - inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("pinned_sources", pinnedSources) }
    }


    fun chooseFocusStyle(style: FocusStyle) {
        focusStyle = style
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("focus_style", style.key) }
    }
}
