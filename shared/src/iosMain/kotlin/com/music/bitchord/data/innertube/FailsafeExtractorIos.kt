package com.music.bitchord.data.innertube

/**
 * iOS has no NewPipe: the jar is JVM-only and its extraction engine (Rhino
 * running YouTube's player JavaScript) has no Kotlin/Native build, so there is
 * nothing behind [FailsafeExtractor] here. See the interface's doc — the
 * resolver walks InnerTubeX's catalog alone on this platform.
 */
internal actual fun platformFailsafeExtractor(): FailsafeExtractor? = null

/**
 * Nothing on iOS links against a library that could go missing at runtime —
 * native binaries are statically linked — so no failure can be this kind.
 */
internal actual fun isLinkageFailure(error: Throwable): Boolean = false
