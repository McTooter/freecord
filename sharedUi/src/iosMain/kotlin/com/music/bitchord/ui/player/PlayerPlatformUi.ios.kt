package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import com.music.bitchord.platform.platformMonotonicNanos
import com.music.bitchord.platform.platformSystemLanguageTag
import platform.UIKit.UIApplication

private val startNanos = platformMonotonicNanos()

/** Milliseconds since the app first drew, on the monotonic clock of the platform. */
internal actual fun uptimeMillis(): Long = (platformMonotonicNanos() - startNanos) / 1_000_000L

// Skia blurs at every size; there is no platform floor to check.
internal actual val renderEffectBlurSupported: Boolean = true

@Composable
internal actual fun KeepScreenOn(enabled: Boolean) {
    // The one window this app has; the flag goes back off when the player goes
    // away, the same way the phone's view would restore it.
    val application = UIApplication.sharedApplication
    DisposableEffect(application, enabled) {
        application.setIdleTimerDisabled(enabled)
        onDispose { application.setIdleTimerDisabled(false) }
    }
}

@Composable
internal actual fun appLanguageTag(): String = platformSystemLanguageTag()

/**
 * Nothing registers here yet. The player's layers close by their own gestures
 * or by the controls that opened them, so nothing has asked for back; iOS's
 * edge-swipe arrives when the platform's navigation wiring is put in place.
 */
@Composable
internal actual fun PlayerBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

/**
 * A finger both scrolls the list and moves the drawer here, the same as it
 * does on Android — so a list that runs out has to carry the drawer with it.
 */
internal actual val drawerFollowsListScroll: Boolean get() = true

internal actual fun DrawScope.clipShiftedDown(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    dy: Float,
    block: DrawScope.() -> Unit,
) {
    clipRect(left = left, top = top, right = right, bottom = bottom) {
        translate(top = dy) { block() }
    }
}
