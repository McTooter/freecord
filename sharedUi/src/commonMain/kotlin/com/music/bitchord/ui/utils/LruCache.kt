package com.music.bitchord.ui.utils

/**
 * A map that drops its least-recently-used entry past [maxSize].
 *
 * The JVM builds this by subclassing a LinkedHashMap in access order and
 * overriding `removeEldestEntry`; common code cannot extend that class on
 * every platform, so the two moves — read and write — re-seat an entry at the
 * newest end and the eldest falls off the front. Main-thread only, the way
 * every cache of this size in the UI already is.
 */
internal class LruCache<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>()

    /** The cached value, re-seated as the most recently used. */
    operator fun get(key: K): V? = map.remove(key)?.also { map[key] = it }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        while (map.size > maxSize) {
            map.remove(map.keys.iterator().next())
        }
    }
}
