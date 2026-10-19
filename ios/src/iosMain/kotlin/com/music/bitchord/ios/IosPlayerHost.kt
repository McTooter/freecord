package com.music.bitchord.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AudioQuality
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.ui.player.AudioOutputDevice
import com.music.bitchord.ui.player.AudioOutputKind
import com.music.bitchord.ui.player.CanvasVideoSpec
import com.music.bitchord.ui.player.LyricsRomanizationResult
import com.music.bitchord.ui.player.LyricsTranslationResult
import com.music.bitchord.ui.player.MAX_LYRICS_OFFSET_MS
import com.music.bitchord.ui.player.MIN_LYRICS_OFFSET_MS
import com.music.bitchord.ui.player.OutputFormatUi
import com.music.bitchord.ui.player.PartyUi
import com.music.bitchord.ui.player.PlayerHost
import com.music.bitchord.ui.player.PlayerSettingsSource
import com.music.bitchord.ui.player.SystemVolume
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.outputVolume
import platform.Foundation.NSUserDefaults

/**
 * The iOS answers to the shared player's [PlayerHost] — DesktopPlayerHost's
 * shape, with AVPlayer underneath (see [IosPlayback]) where the desktop has
 * its FFmpeg engine. The player itself is :sharedUi's, line for line.
 */
internal object IosPlayerHost : PlayerHost {

    override val settings: PlayerSettingsSource = IosPlayerSettings

    // No Canvas on iOS yet — the decoder is a parity-phase piece. Null is the
    // normal answer on the phone too: most tracks carry no Canvas at all.
    override fun cachedCanvas(song: Song): CanvasArtwork? = null

    override suspend fun canvasFor(song: Song): CanvasArtwork? = null

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = Unit

    // Nothing here resolves covers out of a remote file yet — WebDAV and SMB
    // sources are parity phases — so the row's own artwork is all there is.
    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? = song?.thumbnailUrl

    override val volume: SystemVolume = object : SystemVolume {
        // The system level, polled from the audio session — see [IosVolume].
        // Setting it is a no-op: iOS offers no public API for an app to change
        // the output level, so the bar shows the truth and the hardware keys
        // and Control Center move it.
        override val level: StateFlow<Float> = IosVolume.level
        override fun set(level: Float) = Unit
        override fun refresh() = IosVolume.read()
    }

    /**
     * One output, always active: the session AVPlayer is already playing
     * through. Route enumeration — Bluetooth names, AirPlay receivers, wired
     * DACs — is parity-phase work; until then the drawer lists the phone
     * itself and that is the whole truth there is to draw.
     */
    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> = remember {
        listOf(AudioOutputDevice(id = 0, name = "", kind = AudioOutputKind.PHONE, isActive = true))
    }

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = onOpen

    override fun selectAudioOutput(id: Int?) = Unit

    /** Nothing negotiates the output format here: AVPlayer exposes no public
     *  readout of what it settled on, so the pipeline row draws no summary. */
    override val outputFormat: StateFlow<OutputFormatUi> = MutableStateFlow(OutputFormatUi())

    /** Listen Together is desktop-first; there is no party to be in on iOS yet. */
    override val party: StateFlow<PartyUi> = MutableStateFlow(PartyUi())

    // Translation and romanization are shared code — the same client the
    // desktop runs, pointed at this app's caches directory (see IosAppInit).
    override suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult =
        when (val result = LyricsTranslation.translate(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.Result.Translated -> LyricsTranslationResult.Translated(result.lines)
            is LyricsTranslation.Result.SameLanguage -> LyricsTranslationResult.SameLanguage(result.language)
            LyricsTranslation.Result.Unavailable -> LyricsTranslationResult.Unavailable
        }

    override suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult =
        when (val result = LyricsTranslation.romanize(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.RomanizationResult.Romanized -> LyricsRomanizationResult.Romanized(result.lines)
            LyricsTranslation.RomanizationResult.AlreadyRomanized -> LyricsRomanizationResult.AlreadyRomanized
            LyricsTranslation.RomanizationResult.Unavailable -> LyricsRomanizationResult.Unavailable
        }

    /** The newest notice, drawn as a floating line by the shell — see IosApp. */
    val messages = MutableStateFlow<String?>(null)

    override fun showMessage(message: String) {
        messages.value = message
    }

    /** The signal-chain readout needs an engine that reports one; AVPlayer has no public one. */
    @Composable
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) = Unit
}

/**
 * The system output level, 0..1, read from the shared audio session.
 *
 * Polled rather than observed: AVPlayer's periodic time observer already runs
 * twice a second while anything plays (see IosPlayback) and calls [read], and
 * a level that lags a volume-key press by half a second still lets the bar
 * catch up. [read] is also called whenever the player asks for a refresh.
 */
internal object IosVolume {
    val level = MutableStateFlow(0f)

    fun read() {
        level.value = AVAudioSession.sharedInstance().outputVolume
    }

    init {
        read()
    }
}

/**
 * The shared player's settings on iOS: the two the player persists itself go
 * in the platform's defaults, the rest are this build's answers until an iOS
 * settings surface lands. The shape is DesktopPlayerSettings'.
 */
internal object IosPlayerSettings : PlayerSettingsSource {

    private val defaults get() = NSUserDefaults.standardUserDefaults

    // No Canvas decoder on this platform yet — see IosPlayerHost.cachedCanvas.
    override val animatedCanvas = MutableStateFlow(false)
    override val audioQualityCellular = MutableStateFlow(AudioQuality.MEDIUM)
    override val audioQualityWifi = MutableStateFlow(AudioQuality.LOSSLESS)
    override val canvasOverCellular = MutableStateFlow(false)
    override val fullBleedArtwork = MutableStateFlow(true)
    override val hideSongStatus = MutableStateFlow(false)
    override val showQueueHistory = MutableStateFlow(true)
    override val hideVolumeBar = MutableStateFlow(false)
    // The status bar exists here, and the player draws its artwork treatment
    // under it; keep it visible until the setting has a surface to change it from.
    override val hideStatusBar = MutableStateFlow(false)
    override val lastPlayerScreen = MutableStateFlow(
        runCatching { LastPlayerScreen.valueOf(defaults.stringForKey(KEY_LAST_SCREEN) ?: "MAIN") }
            .getOrDefault(LastPlayerScreen.MAIN),
    )
    override val legacyMeshGradient = MutableStateFlow(false)
    override val lyricsBlur = MutableStateFlow(true)
    override val lyricsOffsetMs = MutableStateFlow(
        defaults.integerForKey(KEY_LYRICS_OFFSET).toInt()
            .coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS),
    )
    override val lyricsSourceOrder = MutableStateFlow<List<LyricsSource>>(LyricsSource.offered)

    // Nothing here can tell a metered link from a fat one yet, so the
    // resolver's ceiling reads the Wi-Fi quality setting (see IosAppInit).
    override val meteredConnection = MutableStateFlow<Boolean?>(null)
    override val preferUsbDac = MutableStateFlow(false)
    override val prioritizeSpotifyCanvas = MutableStateFlow(false)
    override val reduceAnimation = MutableStateFlow(false)
    override val reduceDynamicBlur = MutableStateFlow(false)
    override val showNerdStats = MutableStateFlow(false)
    override val smartAnalysis = MutableStateFlow(SmartAnalysis())
    override val smartFadeEnabled = MutableStateFlow(false)
    override val smartMixInProgress = MutableStateFlow(false)
    override val smartMixBlend = MutableStateFlow<MixBlend?>(null)
    override val smartTransitionWindow = MutableStateFlow<TransitionWindow?>(null)
    override val spotifyCanvasAutoHide = MutableStateFlow(true)
    override val syncedLyrics = MutableStateFlow(true)
    override val translationLanguage = MutableStateFlow("")
    override val versionAlignmentInProgress = MutableStateFlow(false)

    override fun setLastPlayerScreen(value: LastPlayerScreen) {
        lastPlayerScreen.value = value
        defaults.setObject(value.name, forKey = KEY_LAST_SCREEN)
    }

    override fun setLyricsOffsetMs(value: Int) {
        val clamped = value.coerceIn(MIN_LYRICS_OFFSET_MS, MAX_LYRICS_OFFSET_MS)
        lyricsOffsetMs.value = clamped
        defaults.setInteger(clamped.toLong(), forKey = KEY_LYRICS_OFFSET)
    }

    private const val KEY_LAST_SCREEN = "last_player_screen"
    private const val KEY_LYRICS_OFFSET = "lyrics_offset_ms"
}
