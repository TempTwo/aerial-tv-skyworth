package app.orchard.tv.ui.home

import android.text.format.DateFormat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.system.NetKind
import app.orchard.tv.system.NetworkStatus
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import app.orchard.tv.weather.WeatherNow
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The current time, ticking on the minute. */
@Composable
fun rememberNow(): State<LocalDateTime> = produceState(LocalDateTime.now()) {
    while (true) {
        value = LocalDateTime.now()
        delay(60_000L - System.currentTimeMillis() % 60_000L + 30L)
    }
}

@Composable
fun timeText(now: LocalDateTime): String {
    val context = LocalContext.current
    // 24-hour unless the owner chose 12-hour. With the setting unset, Android falls back to the
    // locale, and zh_CN means 12-hour, which TV clocks here never show (TCL 65S515D, issue #15).
    val twelve = runCatching { android.provider.Settings.System.getString(context.contentResolver, android.provider.Settings.System.TIME_12_24) }.getOrNull() == "12"
    val pattern = if (twelve) "h:mm" else "H:mm"
    return now.format(DateTimeFormatter.ofPattern(pattern))
}

@Composable
fun dateText(now: LocalDateTime): String {
    val locale = Locale.getDefault()
    val pattern = remember(locale) { DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE") }
    return now.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * The home background, in two states as on tvOS. Focus in the top row: the picture itself. Focus
 * down in the grid: the whole screen turns to the picture frosted and darker, so the rows read
 * over anything.
 *
 * Everything follows [scroll] (the grid's scroll, [collapse] being where the grid has fully come
 * in), so the change is exactly as fast as the movement that causes it. Nothing is blurred or
 * tinted live: the frosted copy is baked small ahead of time (Images.frost) and darkened here
 * once. At rest a frame is one full-screen draw; both pictures are drawn only through the middle
 * of the scroll, since each frame that draws both costs a second full-screen fill (Xiaomi
 * MiTV-MFTR0: 90th percentile 20 -> 30 ms when the crossfade ran end to end).
 */
@Composable
fun HomeBackground(backdrop: ImageBitmap?, frosted: ImageBitmap?, scroll: () -> Float, collapse: Float) {
    Crossfade(
        targetState = backdrop to frosted,
        animationSpec = tween(450, easing = Motion.EaseOut),
        modifier = Modifier.fillMaxSize(),
        label = "ambient",
    ) { (bmp, frost) ->
        // In "Follow App" mode there is no frosted copy: the app's light is soft already and
        // changes with every move, so the grid darkens it live.
        val dark = remember(frost) { frost?.let { darkened(it, 0.55f) } }
        Canvas(Modifier.fillMaxSize()) {
            if (bmp == null) {
                drawRect(Color.Black)
                return@Canvas
            }
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val g = ((scroll() / collapse - 0.3f) / 0.4f).coerceIn(0f, 1f)
            if (dark == null) {
                drawImage(
                    bmp, dstSize = full, filterQuality = FilterQuality.Low,
                    colorFilter = if (g > 0.01f) ColorFilter.tint(Color.Black.copy(alpha = g * 0.5f), BlendMode.SrcAtop) else null,
                )
            } else {
                if (g < 0.99f) drawImage(bmp, dstSize = full, filterQuality = FilterQuality.Low)
                if (g > 0.01f) drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = g)
            }
        }
    }
}

/** A small picture darkened ahead of time, so the grid's background costs no tint per frame. */
private fun darkened(src: ImageBitmap, alpha: Float): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0))
    return out.asImageBitmap()
}

/**
 * Date over a large, light clock: the lock-screen arrangement. The weather follows the date, with
 * the city it is for (issue #59). The clock screensaver shows the same.
 */
@Composable
fun ShelfClock(now: LocalDateTime, modifier: Modifier = Modifier) {
    val dim = Color.White.copy(alpha = 0.86f)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(dateText(now), style = Type.Title.copy(color = dim))
            Weather.now?.let { w ->
                GlyphIcon(glyphFor(w), dim, 22.dp, Modifier.padding(start = 18.dp, end = 6.dp))
                val place = Weather.place?.takeIf { it.length <= 12 }
                BasicText(listOfNotNull(place, "${w.celsius}°").joinToString(" "), style = Type.Title.copy(color = dim))
            }
        }
        BasicText(timeText(now), style = Type.Clock, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun glyphFor(w: WeatherNow): Glyph = when (w.kind) {
    WeatherKind.Clear -> if (w.day) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (w.day) Glyph.CloudSun else Glyph.Cloud
    WeatherKind.Cloudy -> Glyph.Cloud
    WeatherKind.Fog -> Glyph.CloudFog
    WeatherKind.Rain -> Glyph.CloudRain
    WeatherKind.Snow -> Glyph.CloudSnow
    WeatherKind.Thunder -> Glyph.CloudBolt
}

/**
 * Top right: network and, once the shelf clock has scrolled away, the time. Focusable: it is
 * where Up from the top row leads, and it opens Control Center.
 */
@Composable
fun StatusPill(
    now: LocalDateTime,
    network: NetworkStatus,
    showTime: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = 1.08f,
        modifier = modifier,
    ) { state ->
        LaunchedEffect(state.focused) { onFocused(state.focused) }
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent,
            tween(160, easing = Motion.EaseOut), label = "pill-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Color.White.copy(alpha = 0.9f)
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(bg)
                .height(34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val glyph = when (network.kind) {
                NetKind.Wifi -> Glyph.Wifi
                NetKind.Ethernet -> Glyph.Ethernet
                NetKind.Offline -> Glyph.Offline
            }
            GlyphIcon(glyph, fg, 17.dp, level = network.level)
            if (showTime || state.focused) {
                BasicText(timeText(now), style = Type.Headline.copy(color = fg))
            }
            GlyphIcon(Glyph.Switches, fg, 17.dp)
        }
    }
}
