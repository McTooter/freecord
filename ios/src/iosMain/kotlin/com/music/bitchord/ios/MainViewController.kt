@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // Before anything else this launch does, arm the capture for this one,
    // and see how the launch before it ended: one that finished draws the
    // app as normal, one that died comes up on its own record instead.
    CrashJournal.install()
    val unfinished = CrashJournal.takeUnfinishedLaunch()
    CrashJournal.mark("stage launch-begin")
    val failure = runCatching { IosAppInit.installOnce() }.exceptionOrNull()
    if (failure != null) {
        CrashJournal.mark("stage init-failed")
        NSLog("%@", "BitChord startup failed: ${failure.stackTraceToString().take(1_500)}")
        return ComposeUIViewController { StartupFailure(failure) }
    }
    CrashJournal.mark("stage init-ok")
    return ComposeUIViewController {
        var journalDismissed by remember { mutableStateOf(false) }
        if (unfinished != null && !journalDismissed) {
            CrashReport(unfinished, onDismiss = { journalDismissed = true })
        } else {
            IosApp()
        }
    }
}

/**
 * What the screen says when the launch before this one died: its journal —
 * the exception iOS captured at the moment of termination if it captured
 * one, and the stage marks either way, which date the death even when no
 * text was — with a way past it into a fresh launch. The journal is
 * consumed before this shows, so a tap and a clean start is all that is
 * left of the crash.
 */
@Composable
private fun CrashReport(journal: String, onDismiss: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "The last launch didn't finish",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "\nThe app died before it could draw, and this is what iOS kept of it:\n\n" +
                        journal +
                        "\n\nThe lines beginning with \"stage\" are how far that launch got: " +
                        "\"init-ok\" means startup finished, and no \"first-frame\" line means " +
                        "the death was while the first screen was being drawn.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDismiss) {
                    Text("Start BitChord")
                }
            }
        }
    }
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
