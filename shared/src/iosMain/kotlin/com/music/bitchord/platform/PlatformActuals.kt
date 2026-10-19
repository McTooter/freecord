package com.music.bitchord.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinHttpRequestException
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode
import platform.Foundation.componentsFromLocaleIdentifier
import platform.Foundation.currentLocale
import platform.Foundation.preferredLanguages
import platform.Foundation.timeIntervalSince1970
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.native.concurrent.ThreadLocal
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

/**
 * `toList()` already hands back a fresh list, and Kotlin's read-only `List`
 * is all common callers hold — there is no `Collections` to wrap it in, and
 * no cast on this platform that would need one.
 */
actual fun <T> immutableCopyOf(source: Collection<T>): List<T> = source.toList()

actual fun platformMonotonicNanos(): Long = monotonicOrigin.elapsedNow().inWholeNanoseconds

actual fun platformWallClockMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

/**
 * URLSession's own failures, wrapped by the ktor Darwin engine - the breadth
 * of answer `IOException` gave the retry loops on the JVM.
 */
internal actual fun isIoFailure(error: Throwable): Boolean = error is DarwinHttpRequestException

actual fun platformLogStamp(millis: Long): String {
    val formatter = NSDateFormatter()
    // en_US_POSIX: the Gregorian calendar, ASCII digits and a 24-hour clock,
    // in the device's time zone - the same stamp Locale.US gave the JVM.
    formatter.locale = NSLocale(localeIdentifier = "en_US_POSIX")
    formatter.dateFormat = "HH:mm:ss.SSS"
    // NSDate's only time-based initializer counts from its reference date
    // (1 January 2001 UTC), 978307200 seconds after the Unix epoch.
    val sinceReferenceDate = millis / 1000.0 - 978_307_200.0
    return formatter.stringFromDate(NSDate(timeIntervalSinceReferenceDate = sinceReferenceDate))
}

/**
 * Native has no per-instance thread local; `@ThreadLocal` gives one value per
 * *declaration*. A per-thread map keyed by the instance stands in, so the
 * common class keeps its `ThreadLocal` semantics.
 */
@ThreadLocal
private val threadSlots = HashMap<AppThreadLocal<*>, Any?>()

internal actual class AppThreadLocal<T : Any?> actual constructor(private val initial: T) {
    actual fun get(): T {
        val slots = threadSlots
        return if (this in slots) {
            @Suppress("UNCHECKED_CAST")
            slots[this] as T
        } else {
            initial
        }
    }

    actual fun set(value: T) {
        threadSlots[this] = value
    }
}

/**
 * No thread-context propagation exists on Native - see the expect. The slot
 * is never set from this element; TrackLog lines there are filed without a
 * track and every export falls back to its time window instead.
 */
internal actual fun <T> AppThreadLocal<T>.asContextElement(value: T): CoroutineContext = EmptyCoroutineContext
