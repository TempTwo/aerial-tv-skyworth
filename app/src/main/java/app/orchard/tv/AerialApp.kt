package app.orchard.tv

import android.app.Application
import app.orchard.tv.net.ClockTls
import app.orchard.tv.time.TimeSync

/**
 * The process is up well before the desktop paints, so the clock starts asking Alibaba's time
 * servers here rather than in the activity: by the time Home is on screen the date and time are
 * already right.
 */
class AerialApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TimeSync.init(this)
        ClockTls.init(this)
    }
}
