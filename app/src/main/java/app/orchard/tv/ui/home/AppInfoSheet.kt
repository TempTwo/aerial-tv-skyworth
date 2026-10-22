package app.orchard.tv.ui.home

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.orchard.tv.R
import app.orchard.tv.apps.AppEntry
import app.orchard.tv.ui.components.consumeTaps
import app.orchard.tv.ui.components.dismissOnTap
import app.orchard.tv.ui.theme.Glyph
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * App details, for firmwares whose Settings app has no "application details" screen.
 *
 * The usual way in is the system's own screen
 * ([android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS]); on the Skyworth 8H52_G3
 * (Android 5.1) there is no com.android.settings at all, so that intent resolves to nothing and
 * the menu item looks dead. This sheet is the fallback: what the system screen would have shown,
 * drawn by Aerial.
 */
@Composable
fun AppInfoSheet(
    visible: Boolean,
    app: AppEntry?,
    onLaunch: () -> Unit,
    onUninstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    val shownApp = rememberLatest(app) ?: return

    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(visible, shownApp.key) {
        if (visible) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }

    val details by produceState<Details?>(null, shownApp.key) {
        value = withContext(Dispatchers.Default) { readDetails(context.packageManager, shownApp.packageName) }
    }
    val icon by produceState<ImageBitmap?>(null, shownApp.key) {
        value = withContext(Dispatchers.Default) { loadIcon(context.packageManager, shownApp.packageName) }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + scaleIn(tween(300, easing = Motion.Drawer), initialScale = 0.94f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150, easing = Motion.EaseOut), targetScale = 0.97f),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().dismissOnTap(onDismiss).background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(520.dp)
                    .consumeTaps()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Palette.Glass)
                    .border(1.dp, Palette.Hairline, RoundedCornerShape(28.dp))
                    .padding(28.dp),
            ) {
                val iconBitmap = icon
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (iconBitmap != null) {
                        Image(iconBitmap, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)))
                        Spacer(Modifier.width(16.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        BasicText(shownApp.label, style = Type.LargeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicText(
                            shownApp.packageName,
                            style = Type.Caption.copy(color = Palette.TertiaryLabel),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                details?.let { d ->
                    DetailRow(stringResource(R.string.appinfo_version), d.version)
                    DetailRow(stringResource(R.string.appinfo_size), d.size)
                    DetailRow(stringResource(R.string.appinfo_installed), d.installed)
                }
                Spacer(Modifier.height(16.dp))
                MenuRow(Glyph.Play, stringResource(R.string.appinfo_launch), onLaunch, focusRequester = first)
                if (shownApp.removable) {
                    MenuRow(Glyph.Trash, stringResource(R.string.appinfo_uninstall), onUninstall, destructive = true)
                }
                MenuRow(Glyph.Check, stringResource(R.string.appinfo_close), onDismiss)
            }
        }
    }
}

/** One "label: value" line in the sheet. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        BasicText(label, style = Type.Body.copy(color = Palette.SecondaryLabel), modifier = Modifier.width(120.dp))
        BasicText(value, style = Type.Body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

private data class Details(val version: String, val size: String, val installed: String)

private fun readDetails(pm: PackageManager, pkg: String): Details? = runCatching {
    @Suppress("DEPRECATION")
    val info: PackageInfo = pm.getPackageInfo(pkg, 0)
    val bytes = runCatching { File(info.applicationInfo?.sourceDir ?: "").length() }.getOrDefault(0L)
    val version = buildString {
        append(info.versionName ?: "?")
        append(" (")
        @Suppress("DEPRECATION")
        append(info.versionCode)
        append(")")
    }
    Details(
        version = version,
        size = if (bytes > 0) "%.1f MB".format(bytes / 1048576.0) else "?",
        installed = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(info.firstInstallTime)),
    )
}.getOrNull()

private fun loadIcon(pm: PackageManager, pkg: String): ImageBitmap? = runCatching {
    val drawable = pm.getApplicationIcon(pkg)
    val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, 96, 96)
    drawable.draw(canvas)
    bitmap.asImageBitmap()
}.getOrNull()
