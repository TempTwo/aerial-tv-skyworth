package app.orchard.tv.time

import android.content.Context
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The time the home clock shows, read straight from the system clock.
 *
 * The owner asked for this plainly: no server is asked the time of day, the television itself
 * keeps the time. Its Android corrects the clock on its own once the network is up, which is the
 * behaviour everyone expects. The old network measuring and the seven-day anchor carried across
 * cold boots are gone; they were only ever a workaround for this panel starting years wrong after
 * a boot — a wrong the television itself fixes within a minute or two of coming online.
 *
 * The public surface (init, onNetworkAvailable, now, nowMs) is kept so callers need no change;
 * onNetworkAvailable does nothing on purpose.
 */
object TimeSync {
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) {
        // Nothing to start: the system clock is the whole story.
    }

    /** The network coming up used to re-measure; the system clock needs no encouragement. */
    fun onNetworkAvailable() {
    }

    /** The system clock's own reading, in milliseconds. */
    fun nowMs(): Long = System.currentTimeMillis()

    /** The system clock's own reading, in the zone the television keeps. */
    fun now(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs()), ZoneId.systemDefault())
}
