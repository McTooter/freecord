package com.music.bitchord.data

import com.music.bitchord.platform.platformMonotonicNanos

/**
 * Milliseconds on a monotonic clock. The phone installs
 * `SystemClock.elapsedRealtime`, which keeps counting through deep sleep; the
 * desktop keeps the JVM's own, and iOS reads `TimeSource.Monotonic` — both
 * monotonic since boot, which is the whole requirement.
 *
 * The sink is not `@Volatile`: that annotation is JVM-only, and the field is
 * written once at application start before any of the readers exist. Reference
 * reads are atomic on every target this compiles for.
 */
object MonotonicClock {
    var nowMs: () -> Long = { platformMonotonicNanos() / 1_000_000L }
}
