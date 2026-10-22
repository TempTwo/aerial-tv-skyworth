package app.orchard.tv.guard

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Brings Home Guard back after the firmware kills Aerial.
 *
 * Xiaomi force-stops every third-party app when the TV goes to standby, and Android drops a
 * force-stopped app's accessibility services from the enabled list for good. Notification
 * listeners are treated differently: the system keeps the approval and binds the listener again
 * shortly after its process dies. That rebind is the one thing that reliably restarts Aerial
 * after a standby, so it is used for exactly this. No notification is read or touched.
 */
class RevivalListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Log.i("AerialGuard", "revived by the notification listener binding")
        GuardSetup.noteRevival(this)
        GuardSetup.ensureEnabled(this)
        // After a crash Android keeps the guard enabled but does not bind it again (TCL MT5879),
        // and nothing else would notice until Aerial is opened: Home went to the TCL launcher.
        Handler(Looper.getMainLooper()).postDelayed({ GuardSetup.rebindIfStalled(this) }, 5_000)
    }
}
