package app.orchard.tv.ui.wallpaper

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.settings.FocusStyle
import app.orchard.tv.ui.components.Pressable
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.wallpaper.PhoneServer
import app.orchard.tv.wallpaper.ScreensaverMode
import app.orchard.tv.wallpaper.Wallpaper
import app.orchard.tv.wallpaper.WallpaperKind
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Wallpaper and screensaver, chosen on the TV. A sheet rises from the bottom and leaves the top of
 * the screen showing Home, so a new wallpaper can be seen the moment it is chosen. A QR code on the
 * right opens the same settings on a phone, where sending photos is easy.
 */
@Composable
fun WallpaperSheet(visible: Boolean, onDismiss: () -> Unit, onStartScreensaver: () -> Unit, onPin: (on: Boolean) -> Unit) {
    val context = LocalContext.current
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }

    BackHandler(enabled = visible, onBack = onDismiss)

    // The phone page only listens while this sheet is up, plus a few minutes so an upload that is
    // already under way can finish.
    LaunchedEffect(visible) {
        if (visible) {
            val url = withContext(Dispatchers.IO) { PhoneServer.start(context) }
            phoneUrl = url
            qr = url?.let { withContext(Dispatchers.Default) { PhoneServer.qr(it).asImageBitmap() } }
        } else {
            delay(5 * 60 * 1000L)
            withContext(Dispatchers.IO) { PhoneServer.stop() }
        }
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(180)), modifier = Modifier.fillMaxSize()) {
        // A tap above the sheet closes it, back to Control Center as Back does.
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.25f)))
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(380, easing = Motion.Drawer)) { it / 3 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200, easing = Motion.EaseOut)) { it / 4 },
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .consumeTaps()
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    // Nearly opaque: Home is not blurred behind this sheet, so the wallpaper above
                    // it stays sharp while you choose, and the app grid must not show through.
                    .background(Color(0xF71C1C1E))
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .padding(start = 40.dp, end = 32.dp, top = 24.dp),
            ) {
                val scroll = rememberScrollState()
                val scope = rememberCoroutineScope()
                // How Home looks, in the order you see it: the picture behind everything, what
                // replaces it when idle, how the focused app stands out, and the weather.
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)) {
                    BasicText(stringResource(R.string.wp_title), style = Type.LargeTitle)
                    Spacer(Modifier.height(16.dp))
                    SectionTitle(R.string.wp_wallpaper)
                    WallpaperRows()

                    Spacer(Modifier.height(10.dp))
                    SectionTitle(R.string.wp_screensaver)
                    ScreensaverChoice(onStartScreensaver)
                    Spacer(Modifier.height(12.dp))
                    ScreensaverDelay()
                    Spacer(Modifier.height(8.dp))
                    BasicText(stringResource(R.string.ss_back_hint), style = Type.Caption.copy(color = Palette.TertiaryLabel))

                    Spacer(Modifier.height(24.dp))
                    SectionTitle(R.string.st_focus_style)
                    FocusChoice()
                    Spacer(Modifier.height(8.dp))
                    BasicText(
                        stringResource(if (AerialSettings.focusStyle == FocusStyle.HighContrast) R.string.st_focus_high_hint else R.string.st_focus_standard_hint),
                        style = Type.Caption.copy(color = Palette.TertiaryLabel),
                    )

                    Spacer(Modifier.height(24.dp))
                    SectionTitle(R.string.st_names)
                    NamesChoice()

                    Spacer(Modifier.height(24.dp))
                    SectionTitle(R.string.st_pin)
                    // The last row that takes focus: bring the weather below it into view too.
                    PinChoice(onPin, onFocused = { scope.launch { scroll.animateScrollTo(scroll.maxValue) } })
                    Spacer(Modifier.height(8.dp))
                    BasicText(stringResource(R.string.st_pin_hint), style = Type.Caption.copy(color = Palette.TertiaryLabel))

                    Spacer(Modifier.height(24.dp))
                    SectionTitle(R.string.wp_weather)
                    WeatherLine()
                    Spacer(Modifier.height(32.dp))
                }
                Spacer(Modifier.width(20.dp))
                PhoneCard(phoneUrl, qr)
            }
        }
    }
}

@Composable
private fun WallpaperRows() {
    val items = Wallpapers.items
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val daily = items.filter { it.kind == WallpaperKind.Daily }
    val rows = buildList {
        if (daily.isNotEmpty()) {
            add(R.string.wp_daily to listOf(Wallpaper(Wallpapers.LATEST_DAILY, WallpaperKind.Daily, stringResource(R.string.wp_daily_latest))) + daily)
        }
        items.filter { it.kind == WallpaperKind.Photo }.takeIf { it.isNotEmpty() }?.let { photos ->
            // With two or more, the first card takes turns through all of them, one a day (#27).
            val rotate = if (photos.size > 1) listOf(Wallpaper(Wallpapers.ROTATE_PHOTOS, WallpaperKind.Photo, stringResource(R.string.wp_photos_rotate))) else emptyList()
            add(R.string.wp_photos to rotate + photos)
        }
        add(R.string.wp_builtin to items.filter { it.kind == WallpaperKind.Builtin || it.kind == WallpaperKind.Auto })
    }
    rows.forEachIndexed { r, (title, list) ->
        BasicText(stringResource(title), style = Type.Callout)
        Spacer(Modifier.height(8.dp))
        // A row clips to its bounds, and the focused card grows by 8%: room on the left for that,
        // with the row moved back by as much so the cards still line up with the titles.
        // clipToBounds: the row sits 10 dp to the left, so without a clip the card that runs past
        // the right edge is drawn under the phone card's QR code (seen on Skyworth 8H52_G3).
        LazyRow(
            modifier = Modifier.offset(x = (-10).dp).clipToBounds(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
        ) {
            items(list, key = { it.id }) { wp ->
                WallpaperCard(wp, if (r == 0 && wp === list.first()) first else null)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun WallpaperCard(wp: Wallpaper, focusRequester: FocusRequester?) {
    val selected = Wallpapers.selectedId == wp.id
    val thumb by produceState<ImageBitmap?>(null, wp.id, Wallpapers.items) {
        value = withContext(Dispatchers.Default) { Wallpapers.thumbnail(wp.id)?.asImageBitmap() }
    }
    Pressable(onClick = { Wallpapers.select(wp.id) }, focusRequester = focusRequester, focusedScale = 1.08f) { state ->
        Column(Modifier.width(160.dp)) {
            Box(
                Modifier
                    .size(160.dp, 90.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.Fill)
                    .border(
                        if (state.focused || selected) 3.dp else 1.dp,
                        when {
                            state.focused -> Palette.Focus
                            selected -> Palette.Focus.copy(alpha = 0.55f)
                            else -> Palette.Hairline
                        },
                        RoundedCornerShape(12.dp),
                    ),
            ) {
                thumb?.let {
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                    }
                }
                if (selected) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape).background(Palette.Focus),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Check, Palette.OnFocus, 13.dp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                wp.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    BasicText(stringResource(title), style = Type.Headline)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun FocusChoice() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            FocusStyle.HighContrast to R.string.st_focus_high,
            FocusStyle.Standard to R.string.st_focus_standard,
        ).forEach { (style, label) ->
            Chip(stringResource(label), on = AerialSettings.focusStyle == style) { AerialSettings.chooseFocusStyle(style) }
        }
    }
}

/** App names: only under the focused app, as on tvOS, or under every app (issue #28). */
@Composable
private fun NamesChoice() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(false to R.string.st_names_focused, true to R.string.st_names_all).forEach { (all, label) ->
            Chip(stringResource(label), on = AerialSettings.showAllNames == all) { AerialSettings.chooseShowAllNames(all) }
        }
    }
}

/** Parental PIN on or off; either way goes through the PIN pad (issue #10). */
@Composable
private fun PinChoice(onPin: (on: Boolean) -> Unit, onFocused: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(false to R.string.st_pin_off, true to R.string.st_pin_on).forEach { (on, label) ->
            Chip(stringResource(label), on = AerialSettings.pinSet == on, onFocused = onFocused) {
                if (AerialSettings.pinSet != on) onPin(on)
            }
        }
    }
}

/** Where the weather is for and what it is now. The city is set on the phone page. */
@Composable
private fun WeatherLine() {
    val w = Weather.now
    val place = Weather.place
    val kind = w?.let {
        stringResource(
            when (it.kind) {
                WeatherKind.Clear -> R.string.wx_clear
                WeatherKind.PartlyCloudy -> R.string.wx_partly_cloudy
                WeatherKind.Cloudy -> R.string.wx_cloudy
                WeatherKind.Fog -> R.string.wx_fog
                WeatherKind.Rain -> R.string.wx_rain
                WeatherKind.Snow -> R.string.wx_snow
                WeatherKind.Thunder -> R.string.wx_thunder
            },
        )
    }
    BasicText(
        if (w != null) listOfNotNull(place, "${w.celsius}° $kind").joinToString(" · ") else stringResource(R.string.wp_weather_none),
        style = Type.Body,
    )
    Spacer(Modifier.height(4.dp))
    BasicText(
        stringResource(if (Weather.chosen) R.string.wp_weather_chosen else R.string.wp_weather_guessed),
        style = Type.Caption.copy(color = Palette.TertiaryLabel),
    )
}

/** A pill to pick one of a few options: a check on the chosen one, white when focused. */
@Composable
private fun Chip(label: String, on: Boolean, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
        if (onFocused != null) LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "chip-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(bg).height(40.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (on) GlyphIcon(Glyph.Check, fg, 14.dp)
            BasicText(label, style = Type.Body.copy(color = fg))
        }
    }
}

/** How long Home sits idle before the screensaver starts (issue #66). */
@Composable
private fun ScreensaverDelay() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BasicText(stringResource(R.string.ss_delay), style = Type.Body.copy(color = Palette.SecondaryLabel))
        AerialSettings.screensaverChoices.forEach { minutes ->
            Chip(stringResource(R.string.ss_minutes, minutes), on = AerialSettings.screensaverMinutes == minutes) {
                AerialSettings.chooseScreensaverMinutes(minutes)
            }
        }
    }
}

@Composable
private fun ScreensaverChoice(onStart: () -> Unit) {
    // The choice first, then the action that tries it.
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            ScreensaverMode.Aerial to R.string.ss_aerial,
            ScreensaverMode.Photos to R.string.ss_photos,
            ScreensaverMode.Clock to R.string.ss_clock,
        ).forEach { (mode, label) ->
            Chip(stringResource(label), on = Wallpapers.screensaver == mode) { Wallpapers.chooseScreensaver(mode) }
        }
        Spacer(Modifier.width(8.dp))
        Pressable(onClick = onStart, focusedScale = 1.06f) { state ->
            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "ss-start")
            val fg = if (state.focused) Palette.OnFocus else Palette.Label
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(bg).height(40.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlyphIcon(Glyph.Play, fg, 14.dp)
                BasicText(stringResource(R.string.ss_start), style = Type.Body.copy(color = fg))
            }
        }
    }
}

@Composable
private fun PhoneCard(url: String?, qr: ImageBitmap?) {
    Column(Modifier.width(156.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(124.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else {
                GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 40.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        BasicText(stringResource(R.string.wp_phone_title), style = Type.Headline)
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(if (url != null) R.string.wp_phone_body else R.string.wp_phone_offline),
            style = Type.Caption.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        if (url != null) {
            Spacer(Modifier.height(6.dp))
            BasicText(url.removePrefix("http://").substringBefore('/') , style = Type.Caption.copy(color = Palette.TertiaryLabel))
        }
    }
}
