package com.music.bitchord.ui.theme

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable

// The shell draws the status bar itself, so there are no glyphs here to tint.
@Composable
actual fun StatusBarIcons(dark: Boolean) = Unit

// Nor a bar of its own to hide — the shell owns that too.
@Composable
actual fun HideStatusBar(hidden: Boolean) = Unit

@Composable
actual fun stableStatusBarInsets(): WindowInsets = WindowInsets.statusBars
