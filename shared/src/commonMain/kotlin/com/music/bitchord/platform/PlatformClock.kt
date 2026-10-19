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

/**
 * Wall-clock milliseconds since the epoch, as `System.currentTimeMillis`
 * answers.
 *
 * Only for things that genuinely want the wall clock: signing Innertube
 * requests with a timestamp Google will accept, and stamping track-log lines
 * with the time of day a listener recognises. Every measured *duration* goes
 * through [platformMonotonicNanos] instead.
 */
expect fun platformWallClockMillis(): Long

/**
 * Wall-clock milliseconds as `HH:mm:ss.SSS` in the device's local time, ASCII
 * digits - what `SimpleDateFormat("HH:mm:ss.SSS", Locale.US)` wrote into
 * exported track logs. Fixed to that shape on every platform: a locale that
 * writes its own digits or runs a 12-hour clock must not change what a pasted
 * log looks like.
 */
expect fun platformLogStamp(millis: Long): String
