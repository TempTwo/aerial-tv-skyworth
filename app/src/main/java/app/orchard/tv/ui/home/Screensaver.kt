package app.orchard.tv.ui.home

import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.AerialStream
import app.orchard.tv.wallpaper.Aerials
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.WallpaperKind
import app.orchard.tv.wallpaper.Wallpapers
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Aerial's screensaver, shown on Home after a few idle minutes, before the firmware's
 * (ad-carrying) one would start. Three kinds, chosen in Personalize:
 *
 * - Aerial: Apple TV's aerial films, streamed from Apple, with the place named as the camera moves.
 * - Photos: the owner's own photos (the daily ones when there are none), slowly drifting, each
 *   with where it was taken.
 * - Clock: a large clock over the wallpaper, moving once a minute so nothing burns in.
 *
 * If the films cannot be reached, Aerial falls back to Photos. Left and right step to the previous
 * or next film or photo, as on Apple TV; see [ScreensaverSkips].
 */
@Composable
fun Screensaver(visible: Boolean, apps: List<AppEntry>, now: LocalDateTime, skips: ScreensaverSkips) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(1200, easing = Motion.EaseOut)),
        // Leaves quickly: the viewer pressed a key and wants Home back.
        exit = fadeOut(tween(220, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        var aerialFailed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (Wallpapers.screensaver) {
                ScreensaverMode.Aerial -> if (aerialFailed) PhotoSaver(now, skips.steps) else AerialSaver(now, skips.steps, onUnavailable = { aerialFailed = true })
                ScreensaverMode.Photos -> PhotoSaver(now, skips.steps)
                ScreensaverMode.Clock -> ClockSaver(apps, now)
            }
        }
    }
}

/**
 * Left / right on the remote while the screensaver is up. The host (MainActivity on Home,
 * AerialDream as the system screensaver) offers every key here first; keys not taken here wake.
 */
class ScreensaverSkips {
    private val flow = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val steps: SharedFlow<Int> get() = flow

    /** True if the screensaver took [event], both its down and its up. The clock has nothing to step through. */
    fun onKey(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return false
        }
        if (Wallpapers.screensaver == ScreensaverMode.Clock) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) flow.tryEmit(step)
        return true
    }
}

/** Place name bottom-left, time top-right: how tvOS labels its screensaver. */
@Composable
private fun Labels(caption: String?, now: LocalDateTime) {
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // Just enough shade under the text to read it over bright sky or snow.
            drawRect(Brush.verticalGradient(0.7f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f)))
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.18f to Color.Transparent))
        }
        BasicText(
            timeText(now),
            style = Type.LargeTitle.copy(fontSize = Type.LargeTitle.fontSize * 1.3f, color = Color.White.copy(alpha = 0.92f)),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 56.dp),
        )
        AnimatedContent(
            targetState = caption.orEmpty(),
            transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(600)) },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 44.dp),
            label = "caption",
        ) { text ->
            BasicText(
                text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = Type.Title.copy(color = Color.White.copy(alpha = 0.92f)),
                modifier = Modifier.widthIn(max = 620.dp),
            )
        }
    }
}

private const val TAG = "AerialScreensaver"

/**
 * Before Android 7.0 network_security_config is ignored, so Apple Root CA is never trusted and
 * HTTPS to Apple's video host always fails, but only after the player has retried for about half a
 * minute (34 s on TCL 75S365C). Those versions start on HTTP. From 7.0 on this is set for the rest of
 * the process once a film fails over HTTPS.
 */
private var aerialsOverHttp by mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.N)

@Composable
private fun AerialSaver(now: LocalDateTime, steps: SharedFlow<Int>, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val films = remember { AerialStream.withOpenerFirst(context, Aerials.all(context).shuffled()) }
    val player = remember { AerialStream.player(context) }
    var index by remember { mutableIntStateOf(0) }
    var failures by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf<String?>(null) }
    // Bumped whenever a film is (re)started, so the stall check below starts over.
    var attempt by remember { mutableIntStateOf(0) }
    var shownAny by remember { mutableStateOf(false) }
    val film = films[index.mod(films.size)]

    // Black veil that lifts once the film is actually moving, and drops when the viewer skips.
    val veil by animateFloatAsState(if (playing) 0f else 1f, tween(if (playing) 1500 else 400), label = "veil")

    fun load(at: Int) {
        player.setMediaItems(films.map { AerialStream.mediaItem(it, aerialsOverHttp) }, at, 0)
        player.prepare()
        player.playWhenReady = true
    }

    fun skip(step: Int) {
        playing = false
        if (step < 0) player.seekToPreviousMediaItem() else player.seekToNextMediaItem()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    DisposableEffect(player) {
        val opened = SystemClock.elapsedRealtime()
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!playing) Log.i(TAG, "first frame of ${player.currentMediaItem?.mediaId} after ${SystemClock.elapsedRealtime() - opened} ms")
                playing = true
                shownAny = true
                failures = 0
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex
                attempt++
                // A film that simply ended hands over to the next one, already loaded, without a gap.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) playing = false
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "aerial film failed (${error.errorCodeName}): ${player.currentMediaItem?.localConfiguration?.uri}")
                playing = false
                // Apple's video host chains to Apple Root CA, which Android does not ship. From 7.0 on
                // network_security_config trusts it for this host; 5.0 and 6.0 ignore that file and
                // start on HTTP. The same files are served over plain HTTP; try that before giving up.
                if (!aerialsOverHttp) {
                    aerialsOverHttp = true
                    load(player.currentMediaItemIndex)
                } else {
                    failures++
                    skip(1)
                }
                attempt++
            }
        }
        player.addListener(listener)
        load(0)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(index) {
        caption = null
        while (true) {
            delay(1000)
            if (playing) caption = film.captionAt((player.currentPosition / 1000).toInt())
        }
    }
    LaunchedEffect(steps) {
        steps.collect { step ->
            if (films.size < 2) return@collect
            skip(step)
        }
    }
    // A film the CDN serves slowly is passed over rather than waited for.
    LaunchedEffect(attempt, playing) {
        if (playing) return@LaunchedEffect
        delay(STALL_MS)
        Log.w(TAG, "no picture from ${player.currentMediaItem?.mediaId} after $STALL_MS ms; next film")
        failures++
        // Nothing has played at all this time: Apple's CDN is out of reach. Hand over to the photo
        // screensaver after two films rather than three, so the screen is not black for long
        // (Sony A8G and X90J, TCL 65S515D, issue #20).
        if (!shownAny && failures >= 2) onUnavailable() else skip(1)
    }
    LaunchedEffect(failures) {
        if (failures >= 3) onUnavailable()
    }
    // While a film plays, get the next screensaver's first film ready on disk.
    LaunchedEffect(playing) {
        if (playing) AerialStream.prepareOpener(context, films, film, aerialsOverHttp)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
        onRelease = { player.clearVideoSurfaceView(it) },
    )
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(Color.Black))
    Labels(if (playing) caption ?: film.captionAt(0) else null, now)
}

/** How long a film may take to show its first frame before the next one is tried. */
private const val STALL_MS = 8_000L

@Composable
private fun PhotoSaver(now: LocalDateTime, steps: SharedFlow<Int>) {
    val reduced = LocalReducedMotion.current
    val pool = remember(Wallpapers.items) {
        // The owner's own photos when there are any, else the daily ones (issue #27).
        val pictures = Wallpapers.items.filter { it.kind == WallpaperKind.Photo }
            .ifEmpty { Wallpapers.items.filter { it.kind == WallpaperKind.Daily } }
        (pictures.ifEmpty { Wallpapers.items.filter { it.kind == WallpaperKind.Builtin } }).shuffled()
    }
    var slide by remember { mutableIntStateOf(0) }
    var image by remember { mutableStateOf<Pair<Int, ImageBitmap>?>(null) }

    // Restarts on every slide, so a step from the remote also restarts the 18 s wait.
    LaunchedEffect(pool, slide) {
        if (pool.isEmpty()) return@LaunchedEffect
        val wp = pool[slide.mod(pool.size)]
        val bmp = withContext(Dispatchers.Default) { Wallpapers.picture(wp, Wallpapers.screenW, Wallpapers.screenH)?.asImageBitmap() }
        if (bmp != null) image = slide to bmp
        delay(18_000)
        slide++
    }
    LaunchedEffect(steps) {
        steps.collect { slide += it }
    }

    Crossfade(targetState = image, animationSpec = tween(2000, easing = LinearEasing), label = "photo") { current ->
        if (current != null) {
            // Ken Burns: a slow push in, alternating direction slide to slide.
            val push = remember { Animatable(0f) }
            LaunchedEffect(Unit) { if (!reduced) push.animateTo(1f, tween(20_000, easing = LinearEasing)) }
            val dir = if (current.first.mod(2) == 0) 1f else -1f
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    val s = 1.04f + 0.08f * push.value
                    scaleX = s; scaleY = s
                    translationX = dir * (push.value - 0.5f) * 40.dp.toPx()
                },
            ) {
                drawImage(current.second, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
            }
        }
    }
    val wp = pool.getOrNull((image?.first ?: 0).mod(pool.size.coerceAtLeast(1)))
    Labels(wp?.caption?.takeIf { it.isNotBlank() } ?: wp?.title, now)
}

/** A large clock that moves once a minute over the wallpaper or the apps' light. */
@Composable
private fun ClockSaver(apps: List<AppEntry>, now: LocalDateTime) {
    val minute = now.withSecond(0).withNano(0)
    val scene = remember(minute, apps.size, Wallpapers.backdrop) {
        val rnd = Random(minute.hashCode())
        Scene(
            light = Wallpapers.backdrop ?: apps.takeIf { it.isNotEmpty() }?.get(rnd.nextInt(apps.size))?.art?.backdrop,
            x = rnd.nextFloat(),
            y = rnd.nextFloat(),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        Crossfade(targetState = scene, animationSpec = tween(2400, easing = Motion.EaseInOut), label = "clock-scene") { s ->
            Box(Modifier.fillMaxSize()) {
                s.light?.let { light ->
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(light, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low, alpha = 0.75f)
                    }
                }
                Column(
                    Modifier.offset(
                        x = 64.dp + (w - 64.dp * 2 - 420.dp) * s.x,
                        y = 56.dp + (h - 56.dp * 2 - 170.dp) * s.y,
                    ),
                ) { ShelfClock(now = now) }
            }
        }
    }
}

private data class Scene(val light: ImageBitmap?, val x: Float, val y: Float)
