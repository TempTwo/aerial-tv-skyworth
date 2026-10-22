package app.orchard.tv.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.orchard.tv.CcPage
import app.orchard.tv.HomeViewModel
import app.orchard.tv.Overlay
import app.orchard.tv.R
import app.orchard.tv.settings.AerialSettings
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.control.ControlCenter
import app.orchard.tv.ui.wallpaper.WallpaperSheet
import app.orchard.tv.wallpaper.Wallpapers
import app.orchard.tv.ui.theme.LocalReducedMotion
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val COLUMNS = 5

/** Grid geometry for the screen at hand. On a 1080p TV this is a 960×540 dp canvas. */
private class GridMetrics(screenW: Dp, screenH: Dp) {
    val margin = 56.dp
    val gap = 28.dp
    val tileW = (screenW - margin * 2 - gap * (COLUMNS - 1)) / COLUMNS
    val tileH = tileW * 9f / 16f
    val pitchX = tileW + gap
    val pitchY = tileH + 50.dp

    /** The top row sits a little past the middle, under the shelf, as on tvOS. */
    val topRowY = screenH * 0.555f

    /** Where a row rests once you go down into the grid: the row above stays in view. */
    val restY = screenH * 0.37f
    val screenH = screenH

    fun slot(index: Int) = Pair(margin + pitchX * (index % COLUMNS), topRowY + pitchY * (index / COLUMNS))

    fun scrollFor(row: Int, rows: Int): Dp {
        if (row <= 0) return 0.dp
        val wanted = topRowY + pitchY * row - restY
        val bottom = topRowY + pitchY * rows + 36.dp - screenH
        return minOf(wanted, maxOf(bottom, topRowY + pitchY - restY))
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpen: (Intent) -> Unit,
    onAppInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onStartScreensaver: () -> Unit,
) {
    val apps = vm.apps
    val overlay = vm.overlay
    val moving = vm.movingKey
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val now by rememberNow()
    val scope = rememberCoroutineScope()

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val pillRequester = remember { FocusRequester() }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    var pillFocused by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableStateOf(0) }
    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val focusedRow = if (pillFocused || focusedIndex < 0) 0 else focusedIndex / COLUMNS
    val gridEnabled = overlay == Overlay.None

    // Grid scroll offset (px). The remote sets it from the focused row; a finger drags it freely
    // (issue #60), and then it stays where the finger left it until the next key press.
    val scroll = remember { Animatable(0f) }
    var dragged by remember { mutableStateOf(false) }
    val movingNow by rememberUpdatedState(moving)

    fun focusApp(index: Int) {
        val app = apps.getOrNull(index.coerceIn(0, (apps.size - 1).coerceAtLeast(0))) ?: return
        runCatching { requester(app.key).requestFocus() }
    }

    // First focus once the apps are in; afterwards, put focus back wherever it was when an overlay closes.
    LaunchedEffect(vm.loaded, overlay) {
        if (!vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        withFrameNanos { }
        val index = apps.indexOfFirst { it.key == focusedKey }
        if (pillFocused && focusedKey != null) runCatching { pillRequester.requestFocus() } else focusApp(if (index >= 0) index else 0)
    }
    // An app vanished from under the focus (uninstalled or hidden): take its neighbour.
    LaunchedEffect(apps) {
        if (focusedKey != null && apps.none { it.key == focusedKey } && overlay == Overlay.None) {
            withFrameNanos { }
            focusApp(lastFocusedIndex)
        }
        // While moving, focus follows the app to its new slot.
        moving?.let { withFrameNanos { }; runCatching { requester(it).requestFocus() } }
    }
    // Moving starts from the menu, which is still on screen for a frame: focus the app once it is gone.
    LaunchedEffect(moving) {
        val key = moving ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, vm.homeResetPending, vm.loaded) {
        if (windowFocused && vm.homeResetPending && vm.loaded) {
            withFrameNanos { }
            dragged = false
            focusApp(0)
            vm.homeResetPending = false
        }
    }

    BackHandler(enabled = overlay == Overlay.None && moving == null) {
        // tvOS: Back from inside the grid returns to the top row; Back on the top row starts the
        // screensaver. A grid scrolled by touch counts as being inside it.
        val scrolled = dragged && scroll.value > 1f
        dragged = false
        if (focusedRow > 0 || scrolled) focusApp(focusedIndex.coerceAtLeast(0) % COLUMNS) else onStartScreensaver()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                // The remote takes the grid back from the finger: scroll follows focus again.
                // Back leaves it to BackHandler, which needs to know the grid was dragged
                // (gesture navigation sends Back as a key too).
                if (e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_BACK) dragged = false
                // Moving takes the remote here rather than on the moving tile: the tile does not
                // always get focus back when the menu closes, and focus then landed on the first
                // tile, which moved focus instead of the app (#58).
                if (moving != null) {
                    val code = e.nativeKeyEvent.keyCode
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent code != NativeKeyEvent.KEYCODE_HOME
                    when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> vm.moveBy(-1)
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> vm.moveBy(1)
                        NativeKeyEvent.KEYCODE_DPAD_UP -> vm.moveBy(-COLUMNS)
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> vm.moveBy(COLUMNS)
                        NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> vm.endMove()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_MENU -> {
                        // On an app tile, Menu opens that app's menu (AppTile): remotes whose held
                        // OK never reads as held had no other way to it (issue #28). Control
                        // Center stays one press up, on the status pill, where Menu still opens it.
                        if (overlay == Overlay.None && !pillFocused && focusedIndex >= 0) return@onPreviewKeyEvent false
                        vm.overlay = if (overlay is Overlay.ControlCenter) Overlay.None else Overlay.ControlCenter()
                        true
                    }
                    NativeKeyEvent.KEYCODE_TV_INPUT -> {
                        vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val metrics = remember(maxWidth, maxHeight) { GridMetrics(maxWidth, maxHeight) }
        val rows = (apps.size + COLUMNS - 1) / COLUMNS
        val collapsePx = with(density) { (metrics.topRowY - metrics.restY).toPx() }

        val target = with(density) { metrics.scrollFor(focusedRow, rows).toPx() }
        val maxScroll = with(density) { metrics.scrollFor(rows - 1, rows).toPx() }.coerceAtLeast(0f)
        LaunchedEffect(maxScroll) { scroll.updateBounds(0f, maxScroll) }
        val pastShelf by remember(collapsePx) { derivedStateOf { scroll.value > collapsePx / 2 } }
        val decay = rememberSplineBasedDecay<Float>()
        LaunchedEffect(target, dragged) {
            if (dragged) return@LaunchedEffect
            if (reduced) scroll.snapTo(target)
            // A spring keeps its velocity when a quick second press retargets it mid-flight.
            else scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 170f))
        }

        val backdropBlur by animateDpAsState(
            if (overlay is Overlay.ControlCenter || overlay is Overlay.HiddenApps) 30.dp else 0.dp,
            tween(320, easing = Motion.EaseOut), label = "backdrop-blur",
        )

        val ambientApp = apps.getOrNull(focusedIndex) ?: apps.firstOrNull()

        val dragState = rememberDraggableState { delta ->
            dragged = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { scroll.snapTo(scroll.value - delta) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    enabled = gridEnabled && rows > 1,
                    onDragStopped = { velocity ->
                        if (!reduced) scroll.animateDecay(-velocity, decay)
                    },
                )
                // While moving an app, a tap on empty space ends the move, as OK does.
                .pointerInput(Unit) { detectTapGestures { if (movingNow != null) vm.endMove() } }
                .graphicsLayer {
                    val r = backdropBlur.toPx()
                    renderEffect = if (r > 0.5f && Build.VERSION.SDK_INT >= 31) BlurEffect(r, r, TileMode.Clamp) else null
                },
        ) {
            HomeBackground(
                // The chosen wallpaper; in "Follow App" mode, light from the focused app.
                backdrop = Wallpapers.backdrop ?: ambientApp?.art?.backdrop,
                frosted = Wallpapers.frosted.takeIf { Wallpapers.backdrop != null },
                scroll = { scroll.value },
                collapse = collapsePx,
            )

            Box(Modifier.fillMaxSize().offset { IntOffset(0, -scroll.value.roundToInt()) }) {
                ShelfClock(
                    now = now,
                    modifier = Modifier
                        .padding(start = metrics.margin, top = metrics.topRowY - 196.dp)
                        .graphicsLayer { alpha = 1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f) },
                )

                apps.forEachIndexed { index, app ->
                    key(app.key) {
                        val (x, y) = metrics.slot(index)
                        val slotPx = with(density) { IntOffset(x.roundToPx(), y.roundToPx()) }
                        val slot = animateIntOffsetAsState(
                            slotPx,
                            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                            label = "slot",
                        )
                        AppTile(
                            app = app,
                            width = metrics.tileW,
                            height = metrics.tileH,
                            focusRequester = requester(app.key),
                            enabled = gridEnabled,
                            moving = moving == app.key,
                            appearDelayMs = (index * 28).coerceAtMost(320),
                            onFocused = {
                                focusedKey = app.key
                                lastFocusedIndex = index
                                pillFocused = false
                            },
                            onClick = { bounds ->
                                val mover = moving
                                if (mover == null) onLaunch(app, bounds)
                                // While moving, tapping a tile puts the app there; tapping the app
                                // itself is done.
                                else if (mover == app.key) vm.endMove()
                                else {
                                    vm.moveBy(index - apps.indexOfFirst { it.key == mover })
                                    runCatching { requester(mover).requestFocus() }
                                }
                            },
                            onLongPress = { bounds ->
                                if (moving == null) vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero)
                            },
                            modifier = Modifier.offset { slot.value },
                        )
                    }
                }
            }
        }

        // Rows that scroll up under the status pill fade out instead of colliding with it.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .graphicsLayer { alpha = (scroll.value / collapsePx).coerceIn(0f, 1f) },
        ) {
            drawRect(Brush.verticalGradient(0f to Color.Black, 0.5f to Color.Black.copy(alpha = 0.82f), 1f to Color.Transparent))
        }

        StatusPill(
            now = now,
            network = vm.network,
            showTime = if (dragged) pastShelf else focusedRow > 0,
            enabled = gridEnabled && moving == null,
            focusRequester = pillRequester,
            onFocused = { gained ->
                // The window hands its first focus to the top-most control; the top row is where Home starts.
                if (gained) {
                    if (focusedKey == null) {
                        if (apps.isNotEmpty()) focusApp(0)
                    } else {
                        pillFocused = true
                    }
                }
            },
            onClick = {
                // A tap focuses the pill and opens Control Center in the same frame, before
                // onFocused hears of it: note it here so focus comes back to the pill.
                pillFocused = true
                vm.overlay = Overlay.ControlCenter()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = metrics.margin - 14.dp)
                .graphicsLayer { alpha = if (overlay is Overlay.ControlCenter) 0f else 1f },
        )

        AnimatedVisibility(
            visible = moving != null,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            // Screens that can be touched also say how to move with a finger.
            val touch = LocalConfiguration.current.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
            BasicText(
                stringResource(if (touch) R.string.move_hint_touch else R.string.move_hint),
                style = Type.Body,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Glass)
                    .height(40.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        ControlCenter(
            visible = overlay is Overlay.ControlCenter,
            page = (overlay as? Overlay.ControlCenter)?.page ?: CcPage.Main,
            vm = vm,
            now = now,
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onClose = { vm.overlay = Overlay.None },
        )

        val menu = overlay as? Overlay.AppMenu
        AppMenu(
            app = menu?.let { m -> apps.firstOrNull { it.key == m.key } },
            anchor = menu?.anchor,
            onMove = { vm.startMove(it) },
            onHide = { key ->
                vm.overlay = Overlay.None
                vm.hide(key)
            },
            onInfo = { vm.overlay = Overlay.None; onAppInfo(it) },
            onUninstall = { vm.overlay = Overlay.None; onUninstall(it) },
            onUnpin = { key ->
                vm.overlay = Overlay.None
                apps.firstOrNull { it.key == key }?.source?.let { vm.setSourcePinned(it, false) }
            },
            onResetIcon = { key ->
                vm.overlay = Overlay.None
                vm.resetIcon(key)
            },
            onDismiss = { vm.overlay = Overlay.None },
        )

        HiddenAppsSheet(
            visible = overlay is Overlay.HiddenApps,
            apps = vm.hiddenApps,
            onUnhide = { key ->
                vm.unhide(key)
                if (vm.hiddenApps.isEmpty()) vm.overlay = Overlay.None
            },
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
        )

        val info = overlay as? Overlay.AppInfo
        AppInfoSheet(
            visible = info != null,
            app = info?.let { i -> apps.firstOrNull { it.key == i.key } },
            onLaunch = {
                vm.overlay = Overlay.None
                info?.let { i -> apps.firstOrNull { it.key == i.key } }?.let { onLaunch(it, null) }
            },
            onUninstall = {
                vm.overlay = Overlay.None
                info?.let { onUninstall(it.key) }
            },
            onDismiss = { vm.overlay = Overlay.None },
        )

        WallpaperSheet(
            visible = overlay is Overlay.Wallpaper,
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
            onStartScreensaver = {
                vm.overlay = Overlay.None
                onStartScreensaver()
            },
            onPin = { on ->
                if (on) vm.overlay = Overlay.Pin(PinMode.Create) { vm.overlay = Overlay.Wallpaper }
                else vm.whenUnlocked { AerialSettings.choosePin(null); vm.overlay = Overlay.Wallpaper }
            },
        )

        val pin = overlay as? Overlay.Pin
        PinPad(
            visible = pin != null,
            mode = pin?.mode,
            onDone = { pin?.onDone?.invoke() },
            onCancel = { vm.overlay = Overlay.None },
        )

        Screensaver(visible = vm.screensaverVisible, apps = apps, now = now, skips = vm.screensaverSkips)
    }
}
