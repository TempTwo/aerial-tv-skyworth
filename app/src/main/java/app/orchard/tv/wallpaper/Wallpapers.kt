package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

enum class WallpaperKind { Daily, Builtin, Photo, Auto }

data class Wallpaper(
    val id: String,
    val kind: WallpaperKind,
    val title: String,
    /** Where the picture was taken, or who took it; shown in the screensaver. */
    val caption: String? = null,
)

enum class ScreensaverMode(val key: String) {
    Aerial("aerial"), Photos("photos"), Clock("clock");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Aerial
    }
}

/**
 * Everything behind the home screen: which wallpaper is chosen, where pictures come from, and what
 * the screensaver shows. Sources:
 *
 * - Built-in: Aerial's own generated light fields ([Gradients]).
 * - Daily: Bing's photo of the day (cn.bing.com, reachable from China without a proxy). The last
 *   eight are kept; "daily:latest" always follows the newest.
 * - Photos: pictures uploaded from a phone through the setup page ([PhoneServer]). "photos:rotate"
 *   shows a different one of them each day (issue #27).
 * - Auto: the previous behaviour, light taken from the focused app's artwork.
 *
 * State is Compose snapshot state, written on the main thread only.
 */
object Wallpapers {
    private const val TAG = "AerialWallpaper"
    const val LATEST_DAILY = "daily:latest"
    const val AUTO = "auto"
    const val ROTATE_PHOTOS = "photos:rotate"
    private const val DEFAULT_FALLBACK = "grad:aurora"

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val io = Mutex()
    private val prefs by lazy { app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE) }
    private val dailyDir by lazy { File(app.filesDir, "daily").apply { mkdirs() } }
    private val photoDir by lazy { File(app.filesDir, "photos").apply { mkdirs() } }
    private val thumbs = HashMap<String, Bitmap>()

    var items by mutableStateOf<List<Wallpaper>>(emptyList()); private set
    var selectedId by mutableStateOf(LATEST_DAILY); private set
    var screensaver by mutableStateOf(ScreensaverMode.Aerial); private set

    /** The home backdrop for the selection, shading baked in; null while loading or in Auto. */
    var backdrop by mutableStateOf<ImageBitmap?>(null); private set

    /**
     * The same picture frosted: blurred small and darkened. The glass under the top row samples it,
     * and the whole screen turns to it once focus goes down into the grid, as on tvOS.
     */
    var frosted by mutableStateOf<ImageBitmap?>(null); private set

    private var backdropJob: Job? = null
    private var started = false

    /** The UI's size in pixels. Pictures are decoded to it: 1080p on most TVs, 4K where the UI is. */
    var screenW = 1920; private set
    var screenH = 1080; private set

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        context.resources.displayMetrics.let {
            screenW = maxOf(it.widthPixels, it.heightPixels)
            screenH = minOf(it.widthPixels, it.heightPixels)
        }
        selectedId = prefs.getString("selected", LATEST_DAILY) ?: LATEST_DAILY
        screensaver = ScreensaverMode.of(prefs.getString("screensaver", null))
        rebuildList()
        rebuildBackdrop()
        scope.launch { refreshDaily() }
    }

    fun select(id: String) {
        selectedId = id
        prefs.edit { putString("selected", id) }
        rebuildBackdrop()
    }

    fun chooseScreensaver(mode: ScreensaverMode) {
        screensaver = mode
        prefs.edit { putString("screensaver", mode.key) }
    }

    /** The concrete wallpaper the selection points at today. */
    fun resolve(id: String = selectedId): Wallpaper? = when (id) {
        LATEST_DAILY -> items.firstOrNull { it.kind == WallpaperKind.Daily }
        ROTATE_PHOTOS -> items.filter { it.kind == WallpaperKind.Photo }.sortedBy { it.id }.let { photos ->
            if (photos.isEmpty()) resolve(LATEST_DAILY) else photos[Math.floorMod(today(), photos.size)]
        }
        else -> items.firstOrNull { it.id == id }
    }

    private fun today(): Long = java.time.LocalDate.now().toEpochDay()

    /** The day the rotating photo was last picked for; a new day picks the next one. */
    private var rotatedOn = 0L

    /** Moves "My Photos, rotating" on to today's photo; Aerial calls it whenever Home comes back. */
    fun rotateIfDue() {
        if (!started || selectedId != ROTATE_PHOTOS || rotatedOn == today()) return
        rebuildBackdrop()
    }

    // ---- pictures ----

    private fun fileFor(w: Wallpaper): File? = when (w.kind) {
        WallpaperKind.Daily -> File(dailyDir, w.id.removePrefix("daily:") + ".jpg")
        WallpaperKind.Photo -> File(photoDir, w.id.removePrefix("photo:") + ".jpg")
        else -> null
    }

    /** Full picture at [w]×[h] without shading, for the photo screensaver. Call off the main thread. */
    fun picture(wp: Wallpaper, w: Int, h: Int): Bitmap? = when (wp.kind) {
        WallpaperKind.Builtin -> Gradients.byKey(wp.id.removePrefix("grad:"))?.let { Gradients.render(it, w, h) }
        WallpaperKind.Daily, WallpaperKind.Photo -> fileFor(wp)?.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }
        WallpaperKind.Auto -> null
    }

    /** A 16:9 thumbnail for pickers and the phone page. Call off the main thread. */
    fun thumbnail(id: String): Bitmap? {
        synchronized(thumbs) { thumbs[id] }?.let { return it }
        val wp = (if (id == LATEST_DAILY || id == ROTATE_PHOTOS) resolve(id) else items.firstOrNull { it.id == id }) ?: return null
        val b = if (wp.kind == WallpaperKind.Auto) Gradients.render(Gradients.presets.last(), 384, 216) else picture(wp, 384, 216)
        if (b != null) synchronized(thumbs) { thumbs[id] = b }
        return b
    }

    private fun rebuildBackdrop() {
        backdropJob?.cancel()
        val id = selectedId
        backdropJob = scope.launch {
            if (id == AUTO) {
                backdrop = null
                frosted = null
                return@launch
            }
            rotatedOn = today()
            val wp = resolve(id) ?: resolve(DEFAULT_FALLBACK)
            val (bmp, frost) = withContext(Dispatchers.Default) {
                val picture = wp?.let { picture(it, screenW, screenH) }
                // Frosted from the picture before the home shading: the glass sits low on the
                // screen, where the shading is darkest, and would read as a grey slab.
                val frost = picture?.let(Images::frost)
                picture?.let(Images::shadeForHome) to frost
            }
            backdrop = bmp?.asImageBitmap()
            frosted = frost?.asImageBitmap()
        }
    }

    private fun rebuildList() {
        val daily = readDailyIndex().map { Wallpaper("daily:${it.first}", WallpaperKind.Daily, it.second, it.third) }
        val zh = Locale.getDefault().language == "zh"
        val builtins = Gradients.presets.map { Wallpaper("grad:${it.key}", WallpaperKind.Builtin, if (zh) it.nameZh else it.nameEn) }
        val photos = (photoDir.listFiles { f -> f.extension == "jpg" } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .map { Wallpaper("photo:${it.nameWithoutExtension}", WallpaperKind.Photo, if (zh) "我的照片" else "My Photo") }
        val auto = Wallpaper(AUTO, WallpaperKind.Auto, if (zh) "跟随 App" else "Follow App")
        items = daily + builtins + photos + auto
    }

    // ---- daily photos ----

    private fun readDailyIndex(): List<Triple<String, String, String>> = runCatching {
        val arr = JSONArray(File(dailyDir, "index.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Triple(o.getString("date"), o.getString("title"), o.getString("caption"))
        }.filter { File(dailyDir, it.first + ".jpg").exists() }
    }.getOrDefault(emptyList())

    private var lastDailyRefresh = 0L

    /** Bing publishes one photo a day; checking a few times a day is plenty. */
    fun refreshDailyIfStale() {
        if (!started || System.currentTimeMillis() - lastDailyRefresh < 6 * 60 * 60 * 1000L) return
        scope.launch { refreshDaily() }
    }

    suspend fun refreshDaily() = io.withLock {
        lastDailyRefresh = System.currentTimeMillis()
        val added = withContext(Dispatchers.IO) {
            runCatching {
                val json = JSONObject(get("https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=zh-CN").decodeToString())
                val images = json.getJSONArray("images")
                val index = JSONArray()
                for (i in 0 until images.length()) {
                    val img = images.getJSONObject(i)
                    val date = img.getString("startdate")
                    val file = File(dailyDir, "$date.jpg")
                    if (!file.exists()) {
                        // url is the 1920×1080 picture; a UI drawn above 1080p gets the UHD one.
                        val path = if (screenW > 1920) img.getString("urlbase") + "_UHD.jpg" else img.getString("url")
                        val bytes = get("https://cn.bing.com" + path)
                        file.writeBytes(bytes)
                    }
                    val copyright = img.optString("copyright")
                    val title = img.optString("title").ifBlank { copyright.substringBefore(" (").ifBlank { date } }
                    index.put(JSONObject().put("date", date).put("title", title).put("caption", copyright))
                }
                File(dailyDir, "index.json").writeText(index.toString())
                // Keep only what Bing still lists.
                val keep = (0 until index.length()).map { index.getJSONObject(it).getString("date") + ".jpg" }.toSet()
                dailyDir.listFiles { f -> f.extension == "jpg" && f.name !in keep }?.forEach { it.delete() }
                true
            }.onFailure { Log.w(TAG, "daily photos unavailable", it) }.getOrDefault(false)
        }
        if (added) {
            synchronized(thumbs) { thumbs.remove(LATEST_DAILY) }
            rebuildList()
            if (selectedId.startsWith("daily:")) rebuildBackdrop()
        }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        return c.inputStream.use { it.readBytes() }.also { c.disconnect() }
    }

    // ---- uploads ----

    /** Stores an uploaded picture, downscaled to at most 2560 px wide, and returns its id. */
    suspend fun addPhoto(input: InputStream): String? = io.withLock {
        val id = withContext(Dispatchers.IO) {
            val tmp = File.createTempFile("upload", ".bin", app.cacheDir)
            try {
                tmp.outputStream().use { input.copyTo(it) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(tmp.path, bounds)
                if (bounds.outWidth <= 0) return@withContext null
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 2560) sample *= 2
                val bmp = BitmapFactory.decodeFile(tmp.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext null
                val name = System.currentTimeMillis().toString(36)
                File(photoDir, "$name.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bmp.recycle()
                "photo:$name"
            } finally {
                tmp.delete()
            }
        }
        if (id != null) {
            synchronized(thumbs) { thumbs.remove(ROTATE_PHOTOS) }
            rebuildList()
            select(id)
        }
        id
    }

    fun deletePhoto(id: String) {
        // Only photos that are listed: the id comes from the phone page, and "photo:../daily/..."
        // would otherwise reach other files (#57).
        val photo = items.firstOrNull { it.id == id && it.kind == WallpaperKind.Photo } ?: return
        fileFor(photo)?.delete()
        synchronized(thumbs) { thumbs.remove(id); thumbs.remove(ROTATE_PHOTOS) }
        rebuildList()
        if (selectedId == id) select(LATEST_DAILY)
        else if (selectedId == ROTATE_PHOTOS) rebuildBackdrop()
    }
}
