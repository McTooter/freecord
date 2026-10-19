package com.music.bitchord.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig

/**
 * The engine-backed HTTP client common code builds on.
 *
 * `ktor-client-core` runs everywhere but knows no engine on its own: the JVM
 * answer is okhttp, where the app's traffic already went, and iOS is
 * URLSession through ktor's Darwin engine. Callers configure timeouts and
 * plugins through [block] and share the resulting instance; the engine choice
 * never reaches common code.
 */
internal expect fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient

/**
 * Whether this failure means the call could not be *made*, rather than was
 * made and answered badly - the question the Innertube retry loop asks
 * before attempting a request again.
 *
 * The JVM answer is `IOException`, exactly the predicate that loop caught
 * before this existed, so its retry behaviour there is unchanged. iOS has no
 * `IOException`: every URLSession failure arrives as the engine's own
 * exception wrapping an `NSError`, and that class is the same breadth of
 * answer - connection trouble of any kind - that `IOException` gave the JVM.
 */
internal expect fun isIoFailure(error: Throwable): Boolean
