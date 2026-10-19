@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.InnerTubeXResolver
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.player.PlayerPlatform
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSLog
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * Everything the iOS shell puts in place before the first frame, in the order
 * the desktop's `main()` does it (see desktopApp's Main.kt): the shared log
 * sinks, the two installed hosts, the shared caches, and the stream
 * resolver's platform pieces. Called once, from MainViewController.
 */
internal object IosAppInit {

    private var installed = false

    fun installOnce() {
        if (installed) return
        installed = true

        // The data layer both applications share logs through here, into the
        // system log the way a debug phone build writes to logcat. One `%@`
        // for the whole line: a message with a `%` in it is not a format.
        DebugLog.sink = DebugLog.Sink { level, tag, message, error ->
            NSLog("%@", "$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
        }
        TrackLog.echo = { level, tag, message, error ->
            NSLog("%@", "$tag/$level: $message" + (error?.let { " (${it.message})" } ?: ""))
        }

        // The shared player and the shared pages, on this platform's answers.
        PlayerPlatform.install(IosPlayerHost)
        AppUi.install(IosAppUiHost)

        // Translated and romanized lyrics cache beside the rest of the app's
        // caches; the client itself is shared code, the same one the desktop runs.
        cachesDirectory()?.let { LyricsTranslation.cacheDir = "$it/lyrics_translation" }

        // YouTube playback is the shared StreamResolver over InnerTubeX. No
        // PoToken provider here yet: the clients that need one stay skipped,
        // which is the same answer the phone gives before sign-in. InnerTubeX
        // remembers its remote player config in the platform's defaults and
        // its preprocessed EJS players in the caches directory, so the 8.7s
        // solve happens once rather than once per launch.
        StreamResolver.maxKbps = { IosPlayerSettings.audioQualityWifi.value.maxKbps }
        InnerTubeXResolver.init(
            store = IosConfigStore,
            poTokenProvider = null,
            playerCache = IosPlayerCache(cachesDirectory()),
        )

        // Playback runs through AVPlayer, which plays through the shared
        // audio session: the category that keeps it audible in silent mode
        // and in the background, and the session whose output level the
        // player's volume bar reads (see IosVolume).
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
    }

    /** The app's caches directory, or null when the platform won't say. */
    private fun cachesDirectory(): String? =
        NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String
}

/** InnerTubeX's remote player config, remembered in the platform's defaults. */
private object IosConfigStore : InnerTubeXResolver.ConfigStore {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    // Absent is not zero: a missing key has to read as "never stored", or the
    // config looks present-but-empty and InnerTubeX never refetches it.
    // integerForKey reads the same NSInteger the setter stores.
    override fun getLong(key: String): Long? =
        if (defaults.objectForKey(key) == null) null else defaults.integerForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun putLong(key: String, value: Long) {
        defaults.setInteger(value, forKey = key)
    }
}

/**
 * Where the preprocessed EJS players are kept between processes — the caches
 * directory, same bytes the JVM's FilePlayerCache writes, read through NSData
 * the way [com.music.bitchord.platform.AppCacheDirectory] does on this
 * platform. Solving a player from scratch is the 8.7s step, so what is stored
 * here is worth the megabytes.
 */
private class IosPlayerCache(directory: String?) : InnerTubeXResolver.PlayerCache {

    private val directoryPath = directory?.let { "$it/innertubex_players" }

    override fun read(key: String): String? {
        val dir = directoryPath ?: return null
        return runCatching {
            val data = NSData.dataWithContentsOfFile("$dir/$key") ?: return null
            val length = data.length.toInt()
            if (length == 0) return ""
            val source = data.bytes ?: return null
            val bytes = ByteArray(length)
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), source, length.toULong())
            }
            bytes.decodeToString()
        }.getOrNull()
    }

    override fun write(key: String, value: String?) {
        val dir = directoryPath ?: return
        val path = "$dir/$key"
        if (value == null) {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
            return
        }
        ensureDirectory(dir)
        val encoded = value.encodeToByteArray()
        val data = encoded.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), encoded.size.toULong())
        } ?: return
        data.writeToFile(path, atomically = true)
    }

    private fun ensureDirectory(dir: String) {
        val fileManager = NSFileManager.defaultManager
        if (fileManager.fileExistsAtPath(dir)) return
        fileManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    }
}
