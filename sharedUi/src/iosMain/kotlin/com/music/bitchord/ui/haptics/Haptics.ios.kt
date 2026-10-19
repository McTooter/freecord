@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.ui.haptics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.CoreHaptics.CHHapticEngine
import platform.CoreHaptics.CHHapticEvent
import platform.CoreHaptics.CHHapticEventParameter
import platform.CoreHaptics.CHHapticEventParameterIDHapticIntensity
import platform.CoreHaptics.CHHapticEventParameterIDHapticSharpness
import platform.CoreHaptics.CHHapticEventTypeHapticTransient
import platform.CoreHaptics.CHHapticParameterCurve
import platform.CoreHaptics.CHHapticPattern

/**
 * The Taptic Engine, through Core Haptics. Each [Haptic] is a fixed pattern of
 * transients — the same briefness the file's contract asks for, timed in
 * seconds from the press.
 *
 * A simulator has no motor: its engine object constructs, but the start fails,
 * and every play lands on the same nothing a desktop gets.
 */
actual class Haptics(private val engine: CHHapticEngine?) {
    actual fun play(haptic: Haptic) {
        val engine = engine ?: return
        val events = beatsFor(haptic).map { beat ->
            CHHapticEvent(
                eventType = CHHapticEventTypeHapticTransient!!,
                parameters = listOf(
                    CHHapticEventParameter(
                        parameterID = CHHapticEventParameterIDHapticIntensity!!,
                        value = beat.intensity,
                    ),
                    CHHapticEventParameter(
                        parameterID = CHHapticEventParameterIDHapticSharpness!!,
                        value = beat.sharpness,
                    ),
                ),
                relativeTime = beat.time,
                duration = 0.0,
            )
        }
        val pattern = CHHapticPattern(
            events = events,
            parameterCurves = emptyList<CHHapticParameterCurve>(),
            error = null,
        )
        val player = engine.createPlayerWithPattern(pattern, null) ?: return
        player.startAtTime(0.0, null)
    }
}

/** One transient, [time] seconds in: how sharply it lands, how hard. */
private class Beat(val time: Double, val sharpness: Float, val intensity: Float)

// The three shapes every pattern is cut from. A screen never names these —
// the [Haptic] it asks for carries the meaning.
private const val TickSharpness = 0.55f
private const val LowTickSharpness = 0.25f
private const val ClickSharpness = 0.9f
private const val LowTickIntensity = 0.85f

private fun beatsFor(haptic: Haptic): List<Beat> = when (haptic) {
    Haptic.Tick -> listOf(Beat(0.0, TickSharpness, 1f))
    Haptic.Tap, Haptic.Select -> listOf(Beat(0.0, ClickSharpness, 1f))
    Haptic.ToggleOn -> listOf(
        Beat(0.0, LowTickSharpness, LowTickIntensity),
        Beat(0.05, TickSharpness, 1f),
    )
    Haptic.ToggleOff -> listOf(
        Beat(0.0, TickSharpness, 1f),
        Beat(0.05, LowTickSharpness, LowTickIntensity),
    )
    // An accelerating triplet: the gaps close as it goes.
    Haptic.SkipNext -> listOf(
        Beat(0.0, TickSharpness, 1f),
        Beat(0.065, TickSharpness, 1f),
        Beat(0.115, ClickSharpness, 1f),
    )
    // The same triplet read backwards, which is what makes the pair legible.
    Haptic.SkipPrevious -> listOf(
        Beat(0.0, ClickSharpness, 1f),
        Beat(0.05, TickSharpness, 1f),
        Beat(0.115, TickSharpness, 1f),
    )
    // Swelling into the beat that lands.
    Haptic.Resume -> listOf(
        Beat(0.0, LowTickSharpness, LowTickIntensity),
        Beat(0.075, ClickSharpness, 1f),
    )
    // Lands first, then releases.
    Haptic.Pause -> listOf(
        Beat(0.0, ClickSharpness, 1f),
        Beat(0.055, LowTickSharpness, LowTickIntensity),
    )
    Haptic.Expand -> listOf(
        Beat(0.0, TickSharpness, 1f),
        Beat(0.055, ClickSharpness, 1f),
    )
}

/**
 * Starts the engine once, or null when it won't start: on a simulator the
 * engine constructs and only the start reports that there is nothing under it.
 */
private fun startEngine(): CHHapticEngine? {
    val engine = CHHapticEngine()
    return engine.takeIf { it.startAndReturnError(null) }
}

@Composable
actual fun rememberHaptics(): Haptics = remember { Haptics(startEngine()) }
