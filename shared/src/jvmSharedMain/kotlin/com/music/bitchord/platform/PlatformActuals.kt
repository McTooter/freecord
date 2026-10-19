package com.music.bitchord.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.CoroutineContext

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

actual fun <T> immutableCopyOf(source: Collection<T>): List<T> =
    Collections.unmodifiableList(source.toList())

actual fun platformMonotonicNanos(): Long = System.nanoTime()

actual fun platformWallClockMillis(): Long = System.currentTimeMillis()

/**
 * A fresh formatter per stamp: the format is fixed, and one shared
 * `SimpleDateFormat` is a cross-thread formatting race the JVM sources this
 * port reads never noticed.
 */
actual fun platformLogStamp(millis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(millis))

internal actual class AppThreadLocal<T : Any?> actual constructor(initial: T) {
    private val local = ThreadLocal.withInitial { initial }

    actual fun get(): T = local.get()

    actual fun set(value: T) {
        local.set(value)
    }
}

/** The real thing: the value is set on whichever thread resumes the coroutine and restored when the step leaves. */
internal actual fun <T> AppThreadLocal<T>.asContextElement(value: T): CoroutineContext =
    AppThreadContextElement(this, value)

private class AppThreadContextElement<T>(
    private val local: AppThreadLocal<T>,
    private val value: T,
) : ThreadContextElement<T> {
    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): T {
        val previous = local.get()
        local.set(value)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: T) {
        local.set(oldState)
    }

    private companion object Key : CoroutineContext.Key<AppThreadContextElement<*>>
}
