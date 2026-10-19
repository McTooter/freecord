@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import com.music.bitchord.data.DebugLog
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess

/**
 * The phone's and the lock screen's idea of what is playing: the now-playing
 * card (title, artist, sleeve line, position, rate) and the remote commands —
 * play/pause from the earbuds, next/previous from the watch, a scrub from the
 * Control Center slider. Both are plain AVPlayer-adjacent APIs; nothing here
 * knows about Compose.
 */
internal object IosNowPlaying {

    private const val TAG = "BitChord"

    private var registered = false

    /**
     * The targets handed back by addTargetWithHandler. The command center
     * holds its targets weakly, so a handler that isn't kept in a strong
     * field is collected and the buttons go dead — keep them here, one place,
     * for the life of the process.
     */
    private val commandTargets = mutableListOf<Any>()

    /** Wires the transport's controls into the system's, once. */
    fun register(commands: IosPlayback) {
        if (registered) return
        registered = true
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        commandTargets += center.playCommand.addTargetWithHandler {
            commands.togglePlayPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandTargets += center.pauseCommand.addTargetWithHandler {
            commands.togglePlayPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        // The double-tap on the earbud stem lands here rather than on play or
        // pause individually.
        commandTargets += center.togglePlayPauseCommand.addTargetWithHandler {
            commands.togglePlayPause()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandTargets += center.nextTrackCommand.addTargetWithHandler {
            commands.next()
            MPRemoteCommandHandlerStatusSuccess
        }
        commandTargets += center.previousTrackCommand.addTargetWithHandler {
            commands.previous()
            MPRemoteCommandHandlerStatusSuccess
        }
        // The Control Center and lock-screen scrubber: a seek to a moment the
        // user picked, in seconds off the start of the track.
        commandTargets += center.changePlaybackPositionCommand.addTargetWithHandler { event ->
            val seconds = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
            if (seconds == null) {
                DebugLog.w(TAG, "changePlaybackPosition arrived without a position")
                return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            }
            commands.seekTo((seconds * 1000.0).toLong())
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    /**
     * Puts what is playing now on the card. Called on every state change
     * worth showing — a track starting, play/pause, a seek — and reads the
     * transport's values at that moment.
     */
    fun publish(commands: IosPlayback) {
        val song = commands.song.value ?: return
        val info = HashMap<Any?, Any?>()
        info[MPMediaItemPropertyTitle] = song.title
        info[MPMediaItemPropertyArtist] = song.artist
        info[MPMediaItemPropertyAlbumTitle] = song.albumName ?: ""
        // Doubles: the card interpolates the position between updates off the
        // rate, which is why a paused card must carry 0.0 rather than vanish.
        info[MPMediaItemPropertyPlaybackDuration] = commands.durationMs.value / 1000.0
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = commands.position.positionMs / 1000.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = if (commands.isPlaying.value) 1.0 else 0.0
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    /** Takes the card away — the queue is empty and nothing is playing. */
    fun clear() {
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
    }
}
