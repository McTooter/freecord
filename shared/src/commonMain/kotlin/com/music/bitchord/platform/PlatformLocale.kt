package com.music.bitchord.platform

/**
 * The three answers the shared code asks a platform for about language tags.
 *
 * `Locale.ROOT` case conversions need nothing here — Kotlin's own
 * `lowercase()` / `uppercase()` are locale-invariant, which is exactly what
 * `Locale.ROOT` meant at every call site. What is left is reading the device's
 * tag, taking the language out of a tag, and naming a language for a reader:
 * `java.util.Locale` on the JVM, `NSLocale` on iOS.
 */

/** The device's own language tag, e.g. "en-US"; "en" where it says nothing better. */
expect fun platformSystemLanguageTag(): String

/** The two-or-three letter language subtag of [tag], blank when there is none. */
expect fun platformLanguageOfTag(tag: String): String

/** [tag] named in the language [inLanguageTag] speaks, e.g. "Japanese" in English. */
expect fun platformDisplayNameOfTag(tag: String, inLanguageTag: String): String
