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
