@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSLog
import platform.UIKit.UIViewController

/**
 * The one entry the Swift side of iosApp knows about: the view controller the
 * app's window hosts. Installs the platform answers first — [PlayerPlatform]
 * and [AppUi] have to be in place before the first frame asks them for
 * anything, the same order the desktop's main() does it in.
 *
 * The install is guarded: a phone build nobody can attach a debugger to dies
 * silently otherwise, and a crash report LiveContainer may not even keep is no
 * diagnostic. A failure here comes up on screen as [startupFailure] instead,
 * so the device itself says what broke.
 */
fun MainViewController(): UIViewController {
    val failure = runCatching { IosAppInit.installOnce() }.exceptionOrNull()
    if (failure != null) {
        NSLog("%@", "BitChord startup failed: ${failure.stackTraceToString().take(1_500)}")
        return ComposeUIViewController { StartupFailure(failure) }
    }
    return ComposeUIViewController { IosApp() }
}

/**
 * What the screen says when [IosAppInit] could not finish: the message, then
 * the frames closest to the failure — the two things a crash log would have
 * said, printed where the person holding the phone can read them back.
 */
@Composable
private fun StartupFailure(failure: Throwable) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text(
                    "BitChord startup failed",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "\n" + (failure.message ?: failure::class.simpleName ?: "unknown") +
                        "\n\n" + failure.stackTraceToString().take(1_500),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
