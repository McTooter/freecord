package com.music.bitchord.platform

/**
 * Unicode normalization forms the lyrics and matching code folds text with.
 *
 * `java.text.Normalizer` on the JVM; on iOS the NSString bridging does the
 * same canonical and compatibility decomposition, and the answer is a Kotlin
 * String again either way.
 */
expect fun normalizeNfd(text: String): String

expect fun normalizeNfkc(text: String): String
