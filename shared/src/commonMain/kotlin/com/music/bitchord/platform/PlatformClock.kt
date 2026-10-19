package com.music.bitchord.platform

/**
 * A nanosetic reading of a clock that only moves forward.
 *
 * The JVM answer is `System.nanoTime`; iOS reads `TimeSource.Monotonic`, which
 * maps to CLOCK_MONOTONIC there. Neither is wall-clock time: the playhead and
 * every offset measured against a remote peer must not move when the user
 * changes the time zone.
 */
expect fun platformMonotonicNanos(): Long
