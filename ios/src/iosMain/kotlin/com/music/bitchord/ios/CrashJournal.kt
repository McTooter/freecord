@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.music.bitchord.ios

import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSException
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSSetUncaughtExceptionHandler

/**
 * The app's own crash recorder, kept where a system that writes no crash
 * reports cannot lose it: the platform's defaults, written from the one
 * hook iOS runs before it terminates a faulting process
 * ([NSSetUncaughtExceptionHandler]).
 *
 * The loop it closes: a launch that dies — in the framework's own
 * initializer, in the first frame's composition, anywhere a debugger would
 * have to go — leaves its record here, and the launch after it opens on
 * that record instead of the app, so the phone itself reports what
 * happened. The journal also carries the launch's own stage marks
 * ([mark]): a record without `stage first-frame` proves the death was
 * before the first frame even when no exception text was captured.
 */
internal object CrashJournal {

    private const val KEY = "launch_journal"

    private var installed = false

    /** Hooks the capture, once, as early as Kotlin is running at all. */
    fun install() {
        if (installed) return
        installed = true
        NSSetUncaughtExceptionHandler(staticCFunction(::onUncaught))
    }

    /** Records that the launch reached this stage. Never throws. */
    fun mark(stage: String) {
        runCatching { append(stage + "\n") }
    }

    /**
     * The previous launch's record when it ended without reaching the first
     * frame, and null when it ran clean. The record is consumed either way —
     * this launch writes its own from here on.
     */
    fun takeUnfinishedLaunch(): String? = runCatching {
        val defaults = NSUserDefaults.standardUserDefaults
        val text = defaults.stringForKey(KEY)
        defaults.removeObjectForKey(KEY)
        defaults.synchronize()
        if (text == null || text.contains("stage first-frame")) null else text
    }.getOrNull()

    /** The exception handler's side of it — runs on the dying process's thread. */
    fun capture(exception: NSException?) {
        runCatching {
            val text = buildString {
                append("exception ")
                appendLine(exception?.name ?: "unknown")
                appendLine(exception?.reason ?: "")
                exception?.callStackSymbols?.take(40)?.forEach { appendLine("  $it") }
            }
            append(text)
        }
    }

    /**
     * Kept in the platform's defaults rather than a file in the container:
     * it is one call either way, and the one call that matters — [capture],
     * writing from a process the OS is already tearing down — can force it
     * to disk with [NSUserDefaults.synchronize] while a file's buffers
     * might still be in the page cache when the process dies.
     */
    private fun read(): String? = runCatching {
        NSUserDefaults.standardUserDefaults.stringForKey(KEY)
    }.getOrNull()

    private fun append(text: String) {
        val defaults = NSUserDefaults.standardUserDefaults
        defaults.setObject((read() ?: "") + text, forKey = KEY)
        defaults.synchronize()
    }
}

/**
 * The handler itself: a top-level function, because [staticCFunction]
 * cannot capture — the journal it feeds is reached through the object, not
 * through captured state.
 */
private fun onUncaught(exception: NSException?) {
    CrashJournal.capture(exception)
}
