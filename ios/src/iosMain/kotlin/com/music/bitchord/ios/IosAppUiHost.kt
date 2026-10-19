package com.music.bitchord.ios

import androidx.compose.runtime.Composable
import com.music.bitchord.data.settings.LibrarySort
import com.music.bitchord.data.settings.LibraryViewType
import com.music.bitchord.ui.AppUiHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the shared pages — Home first, the rest as the tabs arrive — read from
 * the app around them, answered from this platform's defaults. The shape is
 * DesktopAppUiHost's; the values are the phone's out-of-the-box ones, and
 * they move behind an iOS settings surface when that lands.
 */
internal object IosAppUiHost : AppUiHost {

    override val swipeToPlayNext: StateFlow<Boolean> = MutableStateFlow(true)

    private val _homeRecentsViewType = MutableStateFlow(LibraryViewType.LIST)
    override val homeRecentsViewType: StateFlow<LibraryViewType> = _homeRecentsViewType

    override fun setHomeRecentsViewType(value: LibraryViewType) {
        _homeRecentsViewType.value = value
    }

    /** Nothing is pinned here yet: the pin action lives in the Library page, which is a later phase. */
    override val pinnedPlaylists: StateFlow<List<String>> = MutableStateFlow(emptyList())

    override val librarySort: StateFlow<LibrarySort> = MutableStateFlow(LibrarySort.DEFAULT)

    /** No downloads exist on iOS yet, so no row carries the mark. */
    @Composable
    override fun downloadedIds(): Set<String> = emptySet()
}
