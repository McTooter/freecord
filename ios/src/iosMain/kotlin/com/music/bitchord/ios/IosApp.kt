@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.music.bitchord.ios

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.ui.player.NowPlayingScreen
import com.music.bitchord.ui.screens.HomeScreen
import com.music.bitchord.ui.utils.guardSheetFromContentTouches
import kotlinx.coroutines.delay

/**
 * The whole iOS shell: the phone's Home feed, the shared player raised over
 * it the way MainActivity raises the sheet, and the wiring between them.
 *
 * Everything on screen is :sharedUi's — this file hosts, it does not draw
 * pages. The two things it draws itself are the host's own, the same way
 * MainActivity draws its own bars around the shared screens: the dock shown
 * while the player sheet is down (the phone's MiniPlayer is app-side code,
 * not in :sharedUi yet), and the one-line notice [IosPlayerHost.showMessage]
 * floats.
 */
@Composable
internal fun IosApp() {
    // The shell's own theme, until the phone's palette has a common home to
    // be reached from. The surfaces below are all Material and read these.
    MaterialTheme(colorScheme = darkColorScheme()) {
        val playback = IosPlayback
        val song by playback.song.collectAsState()
        val queue by playback.queue.collectAsState()
        val queueIndex by playback.queueIndex.collectAsState()
        val isPlaying by playback.isPlaying.collectAsState()
        val isLoading by playback.isLoading.collectAsState()
        val durationMs by playback.durationMs.collectAsState()
        val repeatMode by playback.repeatMode.collectAsState()
        val shuffleEnabled by playback.shuffleEnabled.collectAsState()
        val autoplayEnabled by playback.autoplayEnabled.collectAsState()

        // The feed, reloaded on every retry and pull-refresh. No continuation
        // paging here yet — Home's "more" shelves are wired with the detail
        // pages they open, which are parity phases.
        var homeState by remember { mutableStateOf<UiState<List<HomeShelf>>>(UiState.Loading) }
        var homeRefreshes by remember { mutableIntStateOf(0) }
        var homeRefreshing by remember { mutableStateOf(false) }
        LaunchedEffect(homeRefreshes) {
            homeState = UiState.Loading
            homeState = YtMusicRepository.home().fold(
                { UiState.Success(it.shelves) },
                { UiState.Error(it.message ?: "Couldn't load Home") },
            )
            homeRefreshing = false
        }

        // Raised over the feed the way MainActivity's sheet is. The shell's
        // own state rather than the song's presence: dismissing the sheet
        // hides the player, not the music.
        var playerOpen by remember { mutableStateOf(false) }

        // The shell's notice, floated and timed out. Cleared on its own so a
        // message shown while composition is suspended still goes away.
        val notice by IosPlayerHost.messages.collectAsState()
        LaunchedEffect(notice) {
            if (notice != null) {
                delay(2_500)
                IosPlayerHost.messages.value = null
            }
        }

        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowWidth = with(density) { constraints.maxWidth.toDp() }
            val windowHeight = with(density) { constraints.maxHeight.toDp() }

            HomeScreen(
                state = homeState,
                listState = rememberLazyListState(),
                // The queue is the shelf the card came from, in its own order,
                // starting at the card tapped — the same read the desktop
                // makes of a tap (see DesktopApp.openShelfItem).
                onItemClick = { item, shelfTitle ->
                    val shelf = (homeState as? UiState.Success)?.data
                        ?.firstOrNull { it.title == shelfTitle }
                    val songs = shelf?.items.orEmpty()
                        .filter { it.videoId != null }
                        .map { it.toSong() }
                    val start = songs.indexOfFirst { it.videoId == item.videoId }
                    if (item.videoId != null && start >= 0) {
                        playback.play(songs, start)
                        playerOpen = true
                    } else {
                        // Album and playlist cards point at pages this shell
                        // doesn't have yet — a notice rather than a dead tap.
                        IosPlayerHost.showMessage("Collections aren't in this build yet")
                    }
                },
                onRetry = { homeRefreshes++ },
                refreshing = homeRefreshing,
                onRefresh = {
                    homeRefreshing = true
                    homeRefreshes++
                },
                pullState = rememberPullToRefreshState(),
                contentPadding = WindowInsets.safeDrawing.asPaddingValues(),
                title = "Listen now",
                // Anonymous this build: sign-in and the library it unlocks are
                // parity phases, and Home answers for an anonymous session.
                signedIn = false,
                onSignIn = null,
                currentSong = song,
                isPlaying = isPlaying,
            )

            // The dock, while the player sheet is down and a track is still
            // playing — see [IosDock] for why this shell draws its own.
            val docked = song
            if (docked != null && !playerOpen) {
                IosDock(
                    song = docked,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    onExpand = { playerOpen = true },
                    onPlayPause = playback::togglePlayPause,
                    onNext = playback::next,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            notice?.let { text ->
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        // Clear of the dock when it is up, clear of the home
                        // indicator either way.
                        .padding(bottom = if (docked != null && !playerOpen) 88.dp else 24.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Text(
                            text,
                            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // ---- Now Playing ----
            // The same mount MainActivity gives it: full-height, square
            // corners, transparent container so the player's own artwork
            // fills every pixel, no drag handle, and the sheet's own gestures
            // kept off the content (see guardSheetFromContentTouches).
            if (playerOpen) {
                val playing = song
                if (playing != null) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ModalBottomSheet(
                        onDismissRequest = { playerOpen = false },
                        sheetState = sheetState,
                        shape = RectangleShape,
                        containerColor = Color.Transparent,
                        dragHandle = null,
                        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                        sheetMaxWidth = Dp.Unspecified,
                    ) {
                        Box(Modifier.fillMaxSize().guardSheetFromContentTouches(sheetState)) {
                            NowPlayingScreen(
                                song = playing,
                                isPlaying = isPlaying,
                                isLoading = isLoading,
                                position = playback.position,
                                durationMs = durationMs,
                                audioVersionSwitching = false,
                                qualityUpgraded = false,
                                queue = queue,
                                queueIndex = queueIndex,
                                hasPrevious = playing != null,
                                hasNext = queueIndex < queue.size - 1,
                                repeatMode = repeatMode,
                                shuffleEnabled = shuffleEnabled,
                                autoplayEnabled = autoplayEnabled,
                                signedIn = false,
                                accountName = null,
                                likeStatus = LikeStatus.INDIFFERENT,
                                // The account-side actions need a session,
                                // which is a parity phase — see the signedIn
                                // answer above. A notice, never a dead button.
                                onToggleLike = {
                                    IosPlayerHost.showMessage("Liking needs an account — not in this build yet")
                                },
                                onPlayPause = playback::togglePlayPause,
                                onNext = playback::next,
                                onPrevious = playback::previous,
                                onBlockedControl = {
                                    IosPlayerHost.showMessage("Only the party host can change playback")
                                },
                                onSeek = playback::seekTo,
                                onSeekFraction = playback::seekToFraction,
                                onToggleShuffle = playback::toggleShuffle,
                                onCycleRepeat = playback::cycleRepeat,
                                onToggleAutoplay = playback::toggleAutoplay,
                                onJumpTo = playback::jumpTo,
                                onRemoveFromQueue = playback::removeFromQueue,
                                onMoveInQueue = playback::moveInQueue,
                                onClearQueue = playback::clearQueue,
                                onOpenMenu = {
                                    IosPlayerHost.showMessage("The track menu isn't in this build yet")
                                },
                                onOpenAlbum = {
                                    IosPlayerHost.showMessage("Album pages aren't in this build yet")
                                },
                                onOpenArtist = { _, _ ->
                                    IosPlayerHost.showMessage("Artist pages aren't in this build yet")
                                },
                                // The caption above the player names the feed
                                // shelf; there is no page beneath it to return
                                // to in this shell, so the control goes back
                                // to the feed itself.
                                onOpenPlaybackSource = { playerOpen = false },
                                onListenTogether = {
                                    IosPlayerHost.showMessage("Listen Together isn't in this build yet")
                                },
                                // Lyrics need a provider session to fetch
                                // through — parity phase. Unavailable is the
                                // player's own honest answer for it.
                                lyrics = null,
                                lyricsSource = null,
                                lyricsProviderStates = emptyMap(),
                                onSelectLyricsProvider = {},
                                lyricsUnavailable = true,
                                lyricsOffsetOpen = false,
                                onDismissLyricsOffset = {},
                                windowWidth = windowWidth,
                                windowHeight = windowHeight,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * This shell's dock — the strip of artwork, title and transport controls
 * shown while a track plays with the player sheet down.
 *
 * The phone draws its own dock here (app-side MiniPlayer, plus a pull that
 * raises the player), and the desktop docks differently again; neither lives
 * in :sharedUi, so until a shared dock exists this is the host's own, kept to
 * the same job: show what is playing, keep play/pause and next reachable, and
 * raise the real player on a tap.
 */
@Composable
private fun IosDock(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    onExpand: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable(onClick = onExpand),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = song.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPlayPause) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
        }
    }
}

/**
 * A feed card as a playable track — the same read the desktop makes of one
 * (DesktopApp's private toSong): the byline segment that is neither a
 * duration, a type word nor a view/song tally is the artist.
 */
private fun ShelfItem.toSong(): Song {
    val parts = subtitle.split(" • ", " · ", " | ").filter(String::isNotBlank)
    val artist = parts.firstOrNull {
        !it.matches(SHELF_DURATION) &&
            it.lowercase() !in SHELF_TYPE_WORDS &&
            !it.matches(SHELF_TALLY)
    } ?: "Unknown Artist"
    return Song(
        videoId = videoId.orEmpty(),
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = parts.firstOrNull { it.matches(SHELF_DURATION) },
    )
}

private val SHELF_DURATION = Regex("""\d{1,2}:\d{2}(?::\d{2})?""")
private val SHELF_TALLY = Regex("[0-9,.]+\\s*(?:views?|likes?|songs?)", RegexOption.IGNORE_CASE)
private val SHELF_TYPE_WORDS = setOf("song", "video", "album", "ep", "single", "playlist", "mix")
