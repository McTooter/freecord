package com.my.kizzy.gateway

import com.music.bitchord.platform.platformMonotonicNanos

/**
 * iOS has no deep-sleep clock of the sort Android exposes for this reading;
 * the monotonic one - CLOCK_MONOTONIC, the same clock [platformMonotonicNanos]
 * answers from on this platform - is the honest available measure. A presence
 * session on a device that slept reconnects rather than sitting stale, which
 * is the right failure for a heartbeat the app was not awake to answer anyway.
 */
internal actual fun elapsedRealtimeMs(): Long = platformMonotonicNanos() / 1_000_000L
