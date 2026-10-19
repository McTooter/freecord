package com.music.bitchord.platform

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleLanguageCode
import platform.Foundation.componentsFromLocaleIdentifier
import platform.Foundation.currentLocale
import platform.Foundation.preferredLanguages
import kotlin.time.TimeSource

private val monotonicOrigin = TimeSource.Monotonic.markNow()

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
