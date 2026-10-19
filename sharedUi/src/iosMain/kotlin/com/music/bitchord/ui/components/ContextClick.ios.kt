package com.music.bitchord.ui.components

import androidx.compose.ui.Modifier

// A phone has no secondary button to press; the hold is this platform's menu
// gesture, and it is already wired where it is wanted.
actual fun Modifier.contextClick(onClick: (() -> Unit)?): Modifier = this
