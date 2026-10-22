package app.orchard.tv.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.getSystemService
import app.orchard.tv.R
import app.orchard.tv.guard.HomeGuardService

enum class NetKind { Wifi, Ethernet, Offline }

/** The network apps use by default. ConnectivityManager.activeNetwork only exists from Android 6.0. */
fun ConnectivityManager.currentNetwork(): Network? {
    if (Build.VERSION.SDK_INT >= 23) return activeNetwork
    @Suppress("DEPRECATION")
    val type = activeNetworkInfo?.takeIf { it.isConnected }?.type ?: return null
    @Suppress("DEPRECATION")
    return allNetworks.firstOrNull { getNetworkInfo(it)?.type == type }
}

data class NetworkStatus(val kind: NetKind = NetKind.Offline, val name: String? = null, val level: Float = 1f)

data class VolumeStatus(val level: Int = 0, val max: Int = 100, val muted: Boolean = false) {
    val fraction: Float get() = if (max == 0) 0f else level / max.toFloat()
}

enum class SourceKind { Hdmi, DisplayPort, Tuner, Av, Other }

data class TvSource(
    val id: String,
    val label: String,
    val kind: SourceKind,
    val connected: Boolean,
    /** What HDMI-CEC says is plugged in, e.g. "Apple TV". */
    val deviceName: String?,
    val intent: Intent,
)

enum class HomeStatus { Default, Guarded, NotDefault }

object SystemProbe {

    fun network(context: Context): NetworkStatus {
        val cm = context.getSystemService<ConnectivityManager>() ?: return NetworkStatus()
        val caps = cm.getNetworkCapabilities(cm.currentNetwork() ?: return NetworkStatus()) ?: return NetworkStatus()
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus(NetKind.Ethernet)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wm = context.applicationContext.getSystemService<WifiManager>()
                @Suppress("DEPRECATION")
                val info = wm?.connectionInfo
                val ssid = info?.ssid?.trim('"')?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID.trim('<', '>') || it == "<unknown ssid>" }
                val level = if (info == null) 1f else if (Build.VERSION.SDK_INT >= 30) {
                    wm.calculateSignalLevel(info.rssi) / wm.maxSignalLevel.coerceAtLeast(1).toFloat()
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.calculateSignalLevel(info.rssi, 4) / 3f
                }
                NetworkStatus(NetKind.Wifi, ssid, level)
            }
            else -> NetworkStatus(NetKind.Ethernet)
        }
    }

    fun volume(context: Context): VolumeStatus {
        val am = context.getSystemService<AudioManager>() ?: return VolumeStatus()
        return VolumeStatus(
            level = am.getStreamVolume(AudioManager.STREAM_MUSIC),
            max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            muted = if (Build.VERSION.SDK_INT >= 23) am.isStreamMute(AudioManager.STREAM_MUSIC) else legacyMuted,
        )
    }

    /** Android 5.x has no way to read the mute state, so remember what Aerial last set. */
    private var legacyMuted = false

    /**
     * TCL's firmware only pushes a volume change to the amplifier from its own volume UI, which
     * the system shows only for FLAG_SHOW_UI; without it the number changes and the sound does not.
     */
    private fun volumeFlags(context: Context) =
        if (isInstalled(context, "com.tcl.settings")) AudioManager.FLAG_SHOW_UI else 0

    fun adjustVolume(context: Context, direction: Int) {
        val am = context.getSystemService<AudioManager>() ?: return
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            volumeFlags(context),
        )
    }

    fun toggleMute(context: Context) {
        val am = context.getSystemService<AudioManager>() ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, volumeFlags(context))
        } else {
            legacyMuted = !legacyMuted
            @Suppress("DEPRECATION")
            am.setStreamMute(AudioManager.STREAM_MUSIC, legacyMuted)
        }
    }

    fun homeStatus(context: Context): HomeStatus {
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        return when {
            resolved == context.packageName -> HomeStatus.Default
            HomeGuardService.isRunning -> HomeStatus.Guarded
            else -> HomeStatus.NotDefault
        }
    }

    // ---- Settings entries: the TV maker's own settings app where there is one ----

    /** Whether [pkg] is the TV's settings app (behind the parental PIN when one is set). */
    fun isSettingsApp(context: Context, pkg: String): Boolean =
        pkg in SETTINGS_PACKAGES ||
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName == pkg

    private val SETTINGS_PACKAGES = setOf("com.android.settings", "com.android.tv.settings", "com.xiaomi.mitv.settings", "com.tcl.settings")

    fun settingsIntent(context: Context): Intent =
        firstResolvable(
            context,
            Intent("com.xiaomi.mitv.settings.SETTINGS").setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity")),
            Intent(Settings.ACTION_SETTINGS),
            // TCL MT5879 (China): nothing answers ACTION_SETTINGS; MAIN opens TCL's full settings panel.
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
        )

    // TCL MT5879 answers ACTION_WIFI_SETTINGS with its Wi-Fi side panel.
    fun networkSettingsIntent(context: Context): Intent =
        firstResolvable(
            context,
            Intent("com.xiaomi.mitv.settings.WIFI_SETTINGS").setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.wifi.MitvWifiActivity")),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )

    /**
     * Opens the TV's own picture settings where brightness lives, for TVs whose backlight Aerial
     * cannot set itself. TCL keeps it in a side panel that its settings app shows as a service.
     */
    fun openPictureSettings(context: Context): Boolean {
        if (isInstalled(context, "com.tcl.settings")) {
            val shown = runCatching {
                context.startService(Intent("com.tcl.settings.SHOW_WINDOW").setPackage("com.tcl.settings"))
            }.getOrNull() != null
            if (shown) return true
        }
        return runCatching {
            context.startActivity(firstResolvable(context, Intent(Settings.ACTION_DISPLAY_SETTINGS), Intent(Settings.ACTION_SETTINGS)))
        }.isSuccess
    }

    /**
     * Opens the TV's own input picker, for TVs whose inputs Android does not list. TCL MT5879 keeps
     * its inputs in its own middleware (TvInputManager has none) and its remote's input key opens a
     * side panel: SystemUI's status bar service where the firmware has the custom source panel,
     * TCL settings' otherwise (TclShortCutKeyManager.startTVInputService).
     */
    fun openInputPicker(context: Context): Boolean {
        val pm = context.packageManager
        val intent = if (pm.hasSystemFeature("com.tcl.software.systemui_custom_tv_source")) {
            Intent("com.tcl.tvstatusbar.action.SHOW_WINDOW").setPackage("com.android.systemui")
                .putExtra("TvStatusBar_Type", "Source")
                .putExtra("TvStatusBar_StartFrom", "input")
        } else {
            Intent("com.tcl.insrc.SHOW_WINDOW").setPackage("com.tcl.settings")
        }
        if (pm.resolveService(intent, 0) == null) return false
        return runCatching { context.startService(intent) }.getOrNull() != null
    }

    private fun firstResolvable(context: Context, vararg intents: Intent): Intent {
        val pm = context.packageManager
        // Not Intent.resolveActivity: it returns an explicit component unchecked, so Xiaomi's
        // component was picked on TCL too, where it does not exist.
        return (intents.firstOrNull { pm.resolveActivity(it, 0) != null } ?: intents.last())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    // ---- Inputs ----

    private const val XIAOMI_PLAYER = "com.xiaomi.mitv.tvplayer"

    /** CEC logical-device placeholders some input services report instead of the device's name. */
    private val GENERIC_CEC_NAME = Regex("^(Playback|Recorder|Tuner|Audio ?System|Audio|TV|Reserved)[ _]?\\d*$", RegexOption.IGNORE_CASE)

    /**
     * Physical inputs from the TV input framework, routed through the maker's own player where the
     * generic passthrough intent has no handler (Xiaomi's China firmware).
     */
    fun sources(context: Context): List<TvSource> = frameworkSources(context).ifEmpty { tclSources(context) }

    private fun frameworkSources(context: Context): List<TvSource> {
        val tim = context.getSystemService<TvInputManager>() ?: return emptyList()
        val infos = runCatching { tim.tvInputList }.getOrNull() ?: return emptyList()
        val xiaomi = isInstalled(context, XIAOMI_PLAYER)
        val profile = DeviceProfile.current()

        return infos
            .filter { it.parentId == null && (it.isPassthroughInput || it.type == TvInputInfo.TYPE_TUNER) }
            .filter { Build.VERSION.SDK_INT < 24 || runCatching { !it.isHidden(context) }.getOrDefault(true) }
            .filter { hw(it.id) !in profile.hiddenHardware }
            .mapNotNull { info ->
                val kind = when (info.type) {
                    TvInputInfo.TYPE_HDMI -> SourceKind.Hdmi
                    TvInputInfo.TYPE_DISPLAY_PORT -> SourceKind.DisplayPort
                    TvInputInfo.TYPE_TUNER -> SourceKind.Tuner
                    TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_COMPONENT -> SourceKind.Av
                    else -> SourceKind.Other
                }
                val label = runCatching { info.loadLabel(context).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val state = runCatching { tim.getInputState(info.id) }.getOrDefault(TvInputManager.INPUT_STATE_DISCONNECTED)
                val intent = (if (xiaomi) xiaomiRoute(info, kind, profile) else null)
                    ?: genericRoute(info, kind)
                // CEC devices register as child inputs of the port they are plugged into.
                val device = infos.firstOrNull { it.parentId == info.id }
                    ?.let { runCatching { it.loadLabel(context).toString() }.getOrNull() }
                    ?.takeIf { it.isNotBlank() && !GENERIC_CEC_NAME.matches(it) }
                TvSource(
                    id = info.id,
                    label = profile.labels[hw(info.id)] ?: label,
                    kind = kind,
                    connected = device != null || state == TvInputManager.INPUT_STATE_CONNECTED && kind != SourceKind.Tuner,
                    deviceName = device,
                    intent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
    }

    private const val TCL_PLAYER = "com.tcl.tv"

    /**
     * TCL keeps its inputs in its own middleware, so the framework lists none (MT5879, China). Its
     * player switches to any of them when started with the input's EnTCLInputSource ordinal, which
     * is what TCL's own input panel does (SystemUI's ShareUtils.startLiveTV). The ports are the ones
     * MT5879 has; the firmware does not tell an app which are connected or what is plugged in.
     */
    private fun tclSources(context: Context): List<TvSource> {
        val player = ComponentName(TCL_PLAYER, "$TCL_PLAYER.TVActivity")
        if (runCatching { context.packageManager.getActivityInfo(player, 0) }.isFailure) return emptyList()
        fun source(name: String, ordinal: Int, kind: SourceKind, label: String) = TvSource(
            id = "tcl:$name",
            label = label,
            kind = kind,
            connected = false,
            deviceName = null,
            intent = Intent().setComponent(player)
                .putExtra("targetSourceId", ordinal)
                .putExtra("referer", "com.tcl.settings.module_source")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return listOf(
            source("HDMI1", 8, SourceKind.Hdmi, "HDMI 1"),
            source("HDMI2", 9, SourceKind.Hdmi, "HDMI 2"),
            source("HDMI3", 10, SourceKind.Hdmi, "HDMI 3"),
            source("HDMI4", 11, SourceKind.Hdmi, "HDMI 4"),
            source("DTV", 2, SourceKind.Tuner, context.getString(R.string.input_live_tv)),
            source("AV1", 3, SourceKind.Av, context.getString(R.string.input_av)),
        )
    }

    private fun hw(id: String): Int? = Regex("/HW(\\d+)$").find(id)?.groupValues?.get(1)?.toIntOrNull()

    private fun xiaomiRoute(info: TvInputInfo, kind: SourceKind, profile: DeviceProfile): Intent? {
        val n = hw(info.id) ?: return null
        return when (kind) {
            // MediaTek hardware ids HW5.. map onto Xiaomi's source ids 23.. for HDMI; models that
            // number theirs otherwise have them in their profile (MiTV-ANTL0, #64).
            SourceKind.Hdmi -> Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity"))
                .putExtra("input", profile.xiaomiSourceIds[n] ?: (n + 18))
            // DP and USB-C numbers do not follow the HDMI ones (#62), so only models whose numbers
            // are known get a route; the rest fall back to the generic intent.
            SourceKind.DisplayPort -> profile.xiaomiSourceIds[n]?.let {
                Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                    .setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity"))
                    .putExtra("input", it)
            }
            SourceKind.Av -> Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity"))
                .putExtra("input", 2)
            SourceKind.Tuner -> if (n == 0) {
                Intent("com.xiaomi.mitv.tvplayer.DTMB_PLAY").setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.dtmb.DTMBActivity"))
            } else {
                Intent("com.xiaomi.mitv.tvplayer.ATV_PLAY").setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.AtvActivity"))
            }
            SourceKind.Other -> null
        }
    }

    private fun genericRoute(info: TvInputInfo, kind: SourceKind): Intent =
        if (kind == SourceKind.Tuner) {
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(info.id))
        } else {
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
        }

    private fun isInstalled(context: Context, pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}

/**
 * Per-model corrections the framework cannot tell us. MiTV-MFTR0 registers a fourth HDMI input
 * (HW8) with no connector behind it, and its two tuners are better named by what they receive.
 * MiTV-MFFU1 (Redmi monitor G Pro 27U) registers its DP port (HW9) and its USB-C port (HW10) both
 * as DisplayPort; its tvplayer knows them as source 29 and 30 (#62, found in its MonitorUtils).
 * MiTV-ANTL0 (L42R7-RA, Amlogic, Android 5.1) registers only one HDMI input, HW6, labelled
 * HDMI2, but it is the HDMI 1 port and source 23 (#64, from the reporter's logcat).
 */
private class DeviceProfile(
    val hiddenHardware: Set<Int?> = emptySet(),
    val labels: Map<Int?, String> = emptyMap(),
    /** Xiaomi tvplayer source ids for inputs that have no rule or another one, by hardware id. */
    val xiaomiSourceIds: Map<Int, Int> = emptyMap(),
) {
    companion object {
        fun current(): DeviceProfile = when (Build.MODEL) {
            "MiTV_MFTR0", "MiTV-MFTR0" -> DeviceProfile(
                hiddenHardware = setOf(8),
                labels = mapOf(1 to "ATV", 0 to "DTMB", 2 to "AV", 6 to "HDMI 2 (eARC)"),
            )
            "MiTV_MFFU1", "MiTV-MFFU1" -> DeviceProfile(
                labels = mapOf(9 to "DP", 10 to "USB-C"),
                xiaomiSourceIds = mapOf(9 to 29, 10 to 30),
            )
            "MiTV_ANTL0", "MiTV-ANTL0" -> DeviceProfile(
                labels = mapOf(6 to "HDMI 1"),
                xiaomiSourceIds = mapOf(6 to 23),
            )
            else -> DeviceProfile()
        }
    }
}
