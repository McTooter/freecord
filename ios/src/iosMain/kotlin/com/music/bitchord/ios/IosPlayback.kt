@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ios

import com.music.bitchord.data.DebugLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import com.music.bitchord.ui.player.RepeatModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.currentItem
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL

/**
 * The iOS transport: the queue, the repeat and shuffle state, and one
 * [AVPlayer] behind them. The transport model mirrors the phone's session —
 * [source] is the queue as it was authored, [order] is what actually plays,
 * and a shuffle is the same songs in a different [order] — so the same
 * controls mean the same things here as there.
 *
 * Position and duration come from AVPlayer's periodic time observer (twice a
 * second — no KVO), and a track running out from
 * [AVPlayerItemDidPlayToEndTimeNotification]. All of it happens on the main
 * queue, which is also where Compose draws.
 */
internal object IosPlayback {

    private const val TAG = "BitChord"

    /** The playhead the player draws, dated per reading — see PlaybackPosition. */
    val position = PlaybackPosition()

    private val _song = MutableStateFlow<Song?>(null)
    val song: StateFlow<Song?> = _song.asStateFlow()

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _queueIndex = MutableStateFlow(0)
    val queueIndex: StateFlow<Int> = _queueIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatModes.OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    /**
     * Kept and drawn for parity with the other platforms; the radio loader
     * that fills the queue behind the last track is a later phase, so on iOS
     * this is a stored preference and nothing more.
     */
    private val _autoplayEnabled = MutableStateFlow(false)
    val autoplayEnabled: StateFlow<Boolean> = _autoplayEnabled.asStateFlow()

    /** The queue as it was authored — what shuffle-off restores. */
    private var source: List<Song> = emptyList()

    /** What actually plays, in order — [queue]'s backing list. */
    private var order: List<Song> = emptyList()

    private val player = AVPlayer()

    init {
        // Position, duration and the system volume, twice a second while a
        // track is attached. The queue's own state changes are events, not
        // polls — only the playhead ticks.
        player.addPeriodicTimeObserverForInterval(CMTimeMakeWithSeconds(0.5, 600), null) { time ->
            val seconds = CMTimeGetSeconds(time)
            if (seconds.isFinite() && seconds >= 0.0) {
                position.report((seconds * 1000.0).toLong())
                currentDurationMs()?.let { _durationMs.value = it }
            }
            IosVolume.read()
        }
        // A track ran out — advance the way Media3 does, honoring repeat.
        NSNotificationCenter.defaultCenter.addObserverForName(
            AVPlayerItemDidPlayToEndTimeNotification,
            null,
            NSOperationQueue.mainQueue,
        ) {
            onEnded()
        }
        // The lock screen and Control Center commands, once, on this transport.
        IosNowPlaying.register(this)
    }

    /** Starts [songs] playing from [startIndex], replacing whatever was queued. */
    fun play(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        source = songs
        order = songs
        _shuffleEnabled.value = false
        _queue.value = order
        _queueIndex.value = startIndex.coerceIn(0, songs.size - 1)
        loadCurrent(play = true)
    }

    fun togglePlayPause() {
        if (_song.value == null) return
        if (_isPlaying.value) {
            player.pause()
            _isPlaying.value = false
        } else {
            player.play()
            _isPlaying.value = true
        }
        IosNowPlaying.publish(this)
    }

    fun next() {
        when {
            order.isEmpty() -> return
            // Repeat-one replays this track rather than advancing.
            _repeatMode.value == RepeatModes.ONE -> replay()
            _queueIndex.value < order.size - 1 -> {
                _queueIndex.value++
                loadCurrent(play = true)
            }
            _repeatMode.value == RepeatModes.ALL -> {
                _queueIndex.value = 0
                loadCurrent(play = true)
            }
            // The end of the queue: stopped, not cleared — the queue is still
            // there to jump back into, exactly as Media3 leaves it.
            else -> {
                player.pause()
                _isPlaying.value = false
                IosNowPlaying.publish(this)
            }
        }
    }

    fun previous() {
        // Media3's rule: past three seconds, "previous" means "start this
        // track over" — and below that, go back.
        if (position.positionMs > 3_000L) {
            seekTo(0L)
            return
        }
        when {
            _queueIndex.value > 0 -> {
                _queueIndex.value--
                loadCurrent(play = true)
            }
            _repeatMode.value == RepeatModes.ALL && order.isNotEmpty() -> {
                _queueIndex.value = order.size - 1
                loadCurrent(play = true)
            }
            else -> seekTo(0L)
        }
    }

    fun seekTo(targetMs: Long) {
        val clamped = targetMs.coerceAtLeast(0L)
        player.seekToTime(CMTimeMakeWithSeconds(clamped / 1000.0, 600))
        // A jump on purpose — the lyrics can tell it from a late reading.
        position.report(clamped)
        position.seeks++
        IosNowPlaying.publish(this)
    }

    /** The scrubber's answer, in the same terms the other hosts give it. */
    fun seekToFraction(fraction: Float) {
        val duration = _durationMs.value
        if (duration > 0) seekTo((fraction.toDouble() * duration).toLong())
    }

    fun jumpTo(index: Int) {
        if (index !in order.indices) return
        _queueIndex.value = index
        loadCurrent(play = true)
    }

    fun removeFromQueue(index: Int) {
        if (index !in order.indices) return
        val removed = order[index]
        order = order.filterIndexed { i, _ -> i != index }
        // The authored queue loses the same song by identity, so leaving
        // shuffle restores a queue that is really one shorter.
        val sourceIndex = source.indexOfFirst { it.videoId == removed.videoId }
        if (sourceIndex >= 0) source = source.filterIndexed { i, _ -> i != sourceIndex }
        _queue.value = order
        when {
            order.isEmpty() -> stop()
            // The removed rows were all before the current one: the same song
            // is now one row earlier.
            index < _queueIndex.value -> _queueIndex.value--
            index == _queueIndex.value -> {
                if (_queueIndex.value >= order.size) _queueIndex.value = order.size - 1
                loadCurrent(play = _isPlaying.value)
            }
        }
    }

    fun moveInQueue(from: Int, to: Int) {
        if (from == to || from !in order.indices || to !in order.indices) return
        val current = order.getOrNull(_queueIndex.value)
        order = order.toMutableList().apply { add(to, removeAt(from)) }
        // Under shuffle the authored order is not this order, so it keeps its
        // own; the drawer edits what the drawer shows, which is [order].
        if (!_shuffleEnabled.value) source = order
        _queue.value = order
        val movedIndex = current?.let { playing -> order.indexOfFirst { it === playing } } ?: -1
        if (movedIndex >= 0) _queueIndex.value = movedIndex
    }

    fun clearQueue() {
        source = emptyList()
        order = emptyList()
        _queue.value = emptyList()
        _queueIndex.value = 0
        stop()
    }

    fun toggleShuffle() {
        val current = order.getOrNull(_queueIndex.value) ?: return
        if (!_shuffleEnabled.value) {
            // Current first, everything else in a random order after it.
            val rest = order.filterIndexed { i, _ -> i != _queueIndex.value }.shuffled()
            order = listOf(current) + rest
            _queueIndex.value = 0
            _shuffleEnabled.value = true
        } else {
            // Back to the authored queue, with the same song still current.
            order = source
            _queueIndex.value = source.indexOfFirst { it.videoId == current.videoId }.coerceAtLeast(0)
            _shuffleEnabled.value = false
        }
        _queue.value = order
    }

    /** The phone's cycle: off → all → one → off (MainActivity). */
    fun cycleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatModes.OFF -> RepeatModes.ALL
            RepeatModes.ALL -> RepeatModes.ONE
            else -> RepeatModes.OFF
        }
        IosNowPlaying.publish(this)
    }

    fun toggleAutoplay() {
        _autoplayEnabled.value = !_autoplayEnabled.value
    }

    /** Loads the current [order] entry's stream and starts it. */
    private fun loadCurrent(play: Boolean) {
        val current = order.getOrNull(_queueIndex.value)
        _queue.value = order
        _song.value = current
        if (current == null) {
            stop()
            return
        }
        _isLoading.value = true
        position.report(0L)
        position.seeks++
        _durationMs.value = 0L
        IosNowPlaying.publish(this)
        scope.launch {
            val url = try {
                StreamResolver.resolve(current.videoId)
            } catch (e: Throwable) {
                DebugLog.w(TAG, "stream resolve failed for ${current.videoId}: ${e.message}")
                _isLoading.value = false
                IosPlayerHost.showMessage("Couldn't play ${current.title}")
                return@launch
            }
            val nsUrl = NSURL.URLWithString(url)
            if (nsUrl == null) {
                _isLoading.value = false
                IosPlayerHost.showMessage("Couldn't play ${current.title}")
                return@launch
            }
            // The headers whoever minted the URL expects on the fetch — the
            // same map the phone's Media3 data source carries.
            val headers = StreamResolver.mediaHeadersFor(url)
            val options = mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers)
            val asset = AVURLAsset(nsUrl, options)
            player.replaceCurrentItemWithPlayerItem(AVPlayerItem.playerItemWithAsset(asset))
            if (play) {
                player.play()
                _isPlaying.value = true
            }
            _isLoading.value = false
            IosVolume.read()
        }
    }

    /** AVPlayer's own end-of-track, honoring repeat-one before anything else. */
    private fun onEnded() {
        when {
            _repeatMode.value == RepeatModes.ONE -> replay()
            _queueIndex.value < order.size - 1 -> {
                _queueIndex.value++
                loadCurrent(play = true)
            }
            _repeatMode.value == RepeatModes.ALL && order.isNotEmpty() -> {
                _queueIndex.value = 0
                loadCurrent(play = true)
            }
            else -> {
                player.pause()
                _isPlaying.value = false
                _durationMs.value.takeIf { it > 0 }?.let { position.report(it) }
                IosNowPlaying.publish(this)
            }
        }
    }

    private fun replay() {
        seekTo(0L)
        player.play()
        _isPlaying.value = true
        IosNowPlaying.publish(this)
    }

    private fun stop() {
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        _song.value = null
        _isPlaying.value = false
        _isLoading.value = false
        _durationMs.value = 0L
        position.report(0L)
        IosNowPlaying.clear()
    }

    /** The attached item's duration in ms, or null until it is known. */
    private fun currentDurationMs(): Long? {
        val seconds = CMTimeGetSeconds(player.currentItem?.duration ?: return null)
        return if (seconds.isFinite() && seconds > 0.0) (seconds * 1000.0).toLong() else null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}
