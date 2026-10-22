package app.orchard.tv.wallpaper

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Apple TV's aerial screensaver films. Aerial ships only the catalogue (assets/aerials.json, built
 * from Apple's public resources-16.tar): addresses and place names. The films themselves stream from
 * Apple's servers while the screensaver runs, the way the Apple TV plays them.
 */
class Aerial(
    val id: String,
    val label: String,
    val category: String,
    val url: String,
    /** (second, caption) pairs: tvOS changes the place name as the camera moves on. */
    val captions: List<Pair<Int, String>>,
) {
    fun captionAt(seconds: Int): String = captions.lastOrNull { it.first <= seconds }?.second ?: label
}

object Aerials {
    @Volatile
    private var cache: List<Aerial>? = null

    fun all(context: Context): List<Aerial> = cache ?: synchronized(this) {
        cache ?: load(context).also { cache = it }
    }

    private fun load(context: Context): List<Aerial> {
        val zh = Locale.getDefault().language == "zh"
        val root = JSONObject(context.assets.open("aerials.json").bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("videos")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val caps = o.getJSONArray("captions")
            Aerial(
                id = o.getString("id"),
                label = o.getString("label"),
                category = o.getString("category"),
                url = o.getString("url"),
                captions = (0 until caps.length()).map { j ->
                    val c = caps.getJSONArray(j)
                    c.getInt(0) to (if (zh) c.getString(1) else c.getString(2)).replace(' ', ' ')
                },
            )
        }
    }
}
