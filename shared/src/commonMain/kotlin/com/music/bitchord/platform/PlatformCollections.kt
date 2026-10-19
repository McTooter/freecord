package com.music.bitchord.platform

/**
 * A defensive copy of [source] that refuses mutation.
 *
 * `Collections.unmodifiableList` gave the JVM an answer that still throws
 * when a caller casts it back to `MutableList` — the behaviour the context
 * registry's tests pin. Nothing on iOS casts: the read-only `List` the copy
 * answers as is the contract there, and the copy itself is what keeps the
 * registry's own state safe from a caller that held on to a reference.
 */
expect fun <T> immutableCopyOf(source: Collection<T>): List<T>
