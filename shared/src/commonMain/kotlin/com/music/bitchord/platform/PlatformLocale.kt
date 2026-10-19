package com.music.bitchord.platform

/**
 * The answers the shared code asks a platform for about language tags and
 * the two display forms that follow the user's own locale.
 *
 * `Locale.ROOT` case conversions need nothing here — Kotlin's own
 * `lowercase()` / `uppercase()` are locale-invariant, which is exactly what
 * `Locale.ROOT` meant at every call site. What is left is reading the device's
 * tag, taking the language out of a tag, naming a language for a reader, and
 * the two forms a reader's locale really rules: casing a label through their
 * alphabet, and spelling a decimal their way.
 */

/** The device's own language tag, e.g. "en-US"; "en" where it says nothing better. */
expect fun platformSystemLanguageTag(): String

/** The two-or-three letter language subtag of [tag], blank when there is none. */
expect fun platformLanguageOfTag(tag: String): String

/** [tag] named in the language [inLanguageTag] speaks, e.g. "Japanese" in English. */
expect fun platformDisplayNameOfTag(tag: String, inLanguageTag: String): String

/**
 * [text] uppercased the way the running locale would — `Locale.getDefault()`'s
 * mapping: Turkish dotted İ, German SS for ß. Kotlin's own `uppercase()` is the
 * *invariant* mapping instead, a different answer on exactly those alphabets.
 * Only labels a reader sees go through here; identifiers keep the invariant
 * conversion they have always had.
 */
expect fun platformDefaultUppercase(text: String): String

/**
 * [value] in fixed point with [fractionDigits] after the separator, always
 * carrying its sign — the lyrics offset label, "+1.5 s", is the caller. The
 * separator and digits follow the running locale, as `String.format`'s
 * "%+.1f" did: a comma where the locale writes one.
 */
expect fun platformSignedDecimal(value: Float, fractionDigits: Int): String
