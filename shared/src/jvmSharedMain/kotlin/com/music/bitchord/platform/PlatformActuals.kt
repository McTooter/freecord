package com.music.bitchord.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import java.text.Normalizer
import java.util.concurrent.locks.ReentrantLock

internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(OkHttp) {
        // The shared OkHttp instance: DNS, connection pool, address family,
        // the app-wide dispatcher and the WebDAV interceptor all come with
        // it - and, decisively, it is the same client ExoPlayer streams
        // through, which is what keeps a minted stream URL and the fetch
        // that uses it on one connection context. See Http.
        engine { preconfigured = com.music.bitchord.data.Http.client }
        block()
    }

/** The JVM's blocking pool, exactly as the code that called it always had. */
internal actual val AppIoDispatcher get() = Dispatchers.IO

/** Connection trouble, as the retry loops read it - an `IOException`, exactly what they asked for. */
internal actual fun isIoFailure(error: Throwable): Boolean = error is java.io.IOException

actual class AppLock {
    private val lock = ReentrantLock()

    actual fun lock() {
        lock.lock()
    }

    actual fun unlock() {
        lock.unlock()
    }
}

actual fun platformSystemLanguageTag(): String =
    java.util.Locale.getDefault().toLanguageTag().ifEmpty { "en" }

actual fun platformLanguageOfTag(tag: String): String =
    java.util.Locale.forLanguageTag(tag.replace('_', '-')).language

actual fun platformDisplayNameOfTag(tag: String, inLanguageTag: String): String {
    val inLocale = java.util.Locale.forLanguageTag(inLanguageTag)
    // getDisplayName echoes the tag back when it knows nothing about it.
    return java.util.Locale.forLanguageTag(tag).getDisplayName(inLocale)
}

actual fun normalizeNfd(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

actual fun normalizeNfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

actual fun platformMonotonicNanos(): Long = System.nanoTime()

actual fun platformWallClockMillis(): Long = System.currentTimeMillis()
