package com.music.bitchord.ios

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The one entry the Swift side of iosApp knows about: the view controller the
 * app's window hosts. Installs the platform answers first — [PlayerPlatform]
 * and [AppUi] have to be in place before the first frame asks them for
 * anything, the same order the desktop's main() does it in.
 */
fun MainViewController(): UIViewController {
    IosAppInit.installOnce()
    return ComposeUIViewController { IosApp() }
}
