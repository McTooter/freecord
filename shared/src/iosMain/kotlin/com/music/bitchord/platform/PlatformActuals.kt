package com.music.bitchord.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDate
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode
import platform.Foundation.componentsFromLocaleIdentifier
import platform.Foundation.currentLocale
import platform.Foundation.preferredLanguages
import platform.Foundation.timeIntervalSince1970
import kotlin.time.TimeSource

private val monotonicOrigin = TimeSource.Monotonic.markNow()

internal actual fun platformHttpClient(block: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Darwin, block)

/**
 * URLSession answers on its own threads, so there is no blocking pool to
 * preserve here; `Dispatchers.IO` on Native would be an internal alias of
 * Default anyway.
 */
internal actual val AppIoDispatcher get() = Dispatchers.Default

actual class AppLock {
    private val lock = platform.Foundation.NSLock()

    actual fun lock() {
        lock.lock()
    }

    actual fun unlock() {
        lock.unlock()
    }
}

actual fun platformSystemLanguageTag(): String {
    // The same answer java.util.Locale.getDefault().toLanguageTag() gives the
    // JVM: the user's first preferred language, "en-US"-shaped.
    val preferred = NSLocale.preferredLanguages.firstOrNull() as? String
    if (!preferred.isNullOrBlank()) return preferred
    return NSLocale.currentLocale.description ?: "en"
}

actual fun platformLanguageOfTag(tag: String): String {
    val components = NSLocale.componentsFromLocaleIdentifier(tag.replace('_', '-'))
    return components[NSLocaleLanguageCode] as? String ?: ""
}

actual fun platformDisplayNameOfTag(tag: String, inLanguageTag: String): String {
    val inLocale = NSLocale(localeIdentifier = inLanguageTag)
    val named = inLocale.displayNameForKey(NSLocaleLanguageCode, tag.replace('_', '-'))
    // A tag the system knows nothing about comes back as its own identifier —
    // the same echo java.util.Locale gives, and as unhelpful.
    return named as? String ?: tag
}

actual fun normalizeNfd(text: String): String = foldDiacritics(text)

actual fun normalizeNfkc(text: String): String = foldCompatibility(text)

actual fun platformMonotonicNanos(): Long = monotonicOrigin.elapsedNow().inWholeNanoseconds

actual fun platformWallClockMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/**
 * URLSession's own failures, wrapped by the ktor Darwin engine - the breadth
 * of answer `IOException` gave the retry loops on the JVM.
 */
internal actual fun isIoFailure(error: Throwable): Boolean = error is DarwinHttpRequestException
