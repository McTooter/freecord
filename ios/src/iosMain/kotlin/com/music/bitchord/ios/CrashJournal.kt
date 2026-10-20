@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.music.bitchord.ios

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSException
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSSetUncaughtExceptionHandler
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.unlink

/**
 * The app's own crash recorder, kept where a system that writes no crash
 * reports cannot lose it: an append-only file in the app's container,
 * written from the one hook iOS runs before it terminates a faulting
 * process ([NSSetUncaughtExceptionHandler]) and from plain stdio, which
 * the platform's preferences daemon — fussy about domains in a container
 * this app may not own — never sits between.
 *
 * The loop it closes: a launch that dies — in the framework's own
 * initializer, in the first frame's composition, anywhere a debugger
 * would have to go — leaves its record here, and the launch after it
 * opens on that record instead of the app, so the phone itself reports
 * what happened. The record carries the launch's own stage marks
 * ([mark]) alongside the Swift entry's (BitChordApp's own appends, see
 * CrashTrail in the iosApp target), and together they date any death:
 * no record at all is before every marker, `stage swift-app-init` is as
 * far as SwiftUI got, `stage init-ok` proves Kotlin's startup finished,
 * and no `stage first-frame` line means the death was while the first
 * screen was being drawn.
 *
 * A plain file, and stdio to read and write it, because both sides of
 * this app — Swift before Kotlin runs, Kotlin after — must append to the
 * same trail through calls whose failure is at worst silence, and
 * NSUserDefaults' guarantee ends at a daemon that may not recognize the
 * container this process was handed.
 */
internal object CrashJournal {

    private const val FILE = "launch-journal.txt"

    private var installed = false

    /** The container's Documents directory, falling back to the temp one. */
    private val path: String
        get() {
            val documents = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory, NSUserDomainMask, true,
            ).firstOrNull() as? String
            val directory = documents ?: NSTemporaryDirectory().trimEnd('/')
            return "$directory/$FILE"
        }

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
        val text = read()
        unlink(path)
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

    /** Whole file, or null when it does not exist yet. */
    private fun read(): String? {
        val handle = fopen(path, "r") ?: return null
        try {
            fseek(handle, 0, SEEK_END)
            val size = ftell(handle)
            fseek(handle, 0, SEEK_SET)
            if (size <= 0L) return ""
            val bytes = ByteArray(size.toInt())
            val received = bytes.usePinned { fread(it.addressOf(0), 1uL, size.toULong(), handle) }.toLong()
            return bytes.decodeToString(0, received.toInt())
        } finally {
            fclose(handle)
        }
    }

    private fun append(text: String) {
        val handle = fopen(path, "a") ?: return
        try {
            val bytes = text.encodeToByteArray()
            bytes.usePinned { fwrite(it.addressOf(0), 1uL, bytes.size.toULong(), handle) }
        } finally {
            fclose(handle)
        }
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
