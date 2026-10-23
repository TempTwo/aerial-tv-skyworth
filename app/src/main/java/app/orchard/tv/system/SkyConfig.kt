package app.orchard.tv.system

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.tianci.system.data.TCRangeSetData

/**
 * Skyworth/Coocaa picture config, through the vendor's exported `content://com.tianci.provider`.
 *
 * The panel backlight is not wired to Android's screen brightness: the 8H52_G3 has no lights HAL
 * and writes to Settings.System.SCREEN_BRIGHTNESS change nothing on screen. The backlight lives in
 * the TV's own config store, which its picture settings app writes through this provider.
 *
 * The protocol mirrors com.tianci.system.api.TCHotelApi on the same firmware:
 * GET_CONFIG takes {"key": String} and returns {"ret": TCSetData};
 * SET_CONFIG takes {"data": TCSetData}, and the reply carries no value Aerial needs.
 */
object SkyConfig {
    private val URI: Uri = Uri.parse("content://com.tianci.provider")
    private const val METHOD_GET = "METHOD_SYSTEM_CMD_GET_CONFIG"
    private const val METHOD_SET = "METHOD_SYSTEM_CMD_SET_CONFIG"

    fun getRange(context: Context, key: String): TCRangeSetData? = runCatching {
        val args = Bundle().apply { putString("key", key) }
        val reply = context.contentResolver.call(URI, METHOD_GET, null, args)
        reply?.getSerializable("ret") as? TCRangeSetData
    }.getOrNull()

    fun setRange(context: Context, key: String, current: Int, min: Int, max: Int): Boolean =
        runCatching {
            val data = TCRangeSetData().apply {
                name = key
                this.current = current
                this.min = min
                this.max = max
            }
            val args = Bundle().apply { putSerializable("data", data) }
            context.contentResolver.call(URI, METHOD_SET, null, args)
        }.isSuccess
}
