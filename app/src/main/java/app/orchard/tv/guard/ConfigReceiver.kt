package app.orchard.tv.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.orchard.tv.apps.TileSheet
import app.orchard.tv.settings.AerialSettings
import kotlinx.coroutines.launch

/**
 * Options set from a computer over ADB, e.g.
 *   adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --ez keep_adb true
 *   ... --ez own_screensaver true   (keep Aerial as the system screensaver; see GuardSetup)
 *   ... --ez clear_pin true         (turn the parental PIN off, for an owner who forgot it)
 * The manifest requires the sender to hold WRITE_SECURE_SETTINGS, which the ADB shell has and
 * ordinary apps cannot get, so no other app on the TV can flip these.
 */
class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // ... --es export_tiles <name>   (every tile on one picture; see TileSheet)
        // Runs on past the broadcast's time limit; Home keeps the process alive. Look for the file.
        intent.getStringExtra("export_tiles")?.takeIf { Regex("^[A-Za-z0-9_-]+$").matches(it) }?.let { name ->
            val app = context.applicationContext
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { TileSheet.export(app, name) }.onFailure { android.util.Log.e("AerialTiles", "Export failed", it) }
            }
            resultData = "exporting $name"
            return
        }
        if (intent.getBooleanExtra("clear_pin", false)) {
            AerialSettings.init(context)
            AerialSettings.choosePin(null)
        }
        if (intent.hasExtra("keep_adb")) GuardSetup.setKeepAdb(context, intent.getBooleanExtra("keep_adb", false))
        if (intent.hasExtra("own_screensaver")) GuardSetup.setOwnScreensaver(context, intent.getBooleanExtra("own_screensaver", false))
        GuardSetup.ensureEnabled(context)
        resultData = "keep_adb=${GuardSetup.keepAdb(context)}"
    }
}
