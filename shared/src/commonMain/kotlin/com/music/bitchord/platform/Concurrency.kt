package com.music.bitchord.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Mutual exclusion for code every platform runs.
 *
 * The JVM sources this port grew on used `ConcurrentHashMap` and friends
 * directly; on iOS there is no `java.util.concurrent`, and the contention here
 * is light — caches and registries, never the audio path — so one small lock
 * primitive carries all of it.
 */
expect class AppLock() {
    fun lock()
    fun unlock()
}

inline fun <T> AppLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}

/**
 * An atomic holder for a reference, the shape `AtomicReference` gave the
 * cache hot-spots in the JVM code. Not a lock-free CAS — the callers here
 * read-then-write inside one scope, which a lock expresses exactly.
 */
class AppAtomic<T>(initial: T) {
    private val lock = AppLock()

    @PublishedApi
    internal var value: T = initial

    fun get(): T = lock.withLock { value }

    fun set(next: T) = lock.withLock { value = next }

    /** Replaces the value and answers the previous one, as `getAndSet` did. */
    fun getAndSet(next: T): T = lock.withLock {
        val previous = value
        value = next
        previous
    }

    /** Reads, computes, writes, answers the new value — one critical section. */
    fun update(transform: (T) -> T): T = lock.withLock {
        value = transform(value)
        value
    }
}

/**
 * A map guarded by one lock, standing in for `ConcurrentHashMap`.
 *
 * Every caller in this codebase holds it for a lookup or a small transform;
 * none of them iterate under mutation or rely on weakly-consistent iteration,
 * which is the only behaviour the JDK class offered that this cannot.
 */
class AppConcurrentMap<K, V> {
    private val lock = AppLock()
    private val map = LinkedHashMap<K, V>()

    val size: Int get() = lock.withLock { map.size }

    val isEmpty: Boolean get() = lock.withLock { map.isEmpty() }

    operator fun get(key: K): V? = lock.withLock { map[key] }

    operator fun set(key: K, value: V): V = lock.withLock { map.put(key, value) ?: throw PutReturnedNull() }

    fun put(key: K, value: V): V? = lock.withLock { map.put(key, value) }

    fun putIfAbsent(key: K, value: V): V? = lock.withLock {
        val existing = map[key]
        if (existing == null) map[key] = value
        existing
    }

    fun getOrPut(key: K, defaultValue: () -> V): V = lock.withLock { map.getOrPut(key, defaultValue) }

    fun computeIfAbsent(key: K, mappingFunction: (K) -> V): V = lock.withLock {
        val existing = map[key]
        if (existing != null) return@withLock existing
        val created = mappingFunction(key)
        map[key] = created
        created
    }

    fun remove(key: K): V? = lock.withLock { map.remove(key) }

    fun containsKey(key: K): Boolean = lock.withLock { map.containsKey(key) }

    fun clear() = lock.withLock { map.clear() }

    /** A snapshot of the keys, safe to iterate without holding the lock. */
    fun keys(): Set<K> = lock.withLock { map.keys.toSet() }

    /** A snapshot of the values, safe to iterate without holding the lock. */
    fun values(): List<V> = lock.withLock { map.values.toList() }

    /** A snapshot of the entries, safe to iterate without holding the lock. */
    fun entries(): List<Pair<K, V>> = lock.withLock { map.entries.map { it.key to it.value } }

    /** Runs [block] over a snapshot of the entries. */
    inline fun forEachSnapshot(block: (K, V) -> Unit) {
        val snapshot = entries()
        for ((key, value) in snapshot) block(key, value)
    }
}

private class PutReturnedNull : IllegalStateException("map.put returned null; use put() for the nullable answer")

/**
 * Where the app's blocking-oriented work goes: fetches, disk, anything that
 * used `Dispatchers.IO` in the sources this port reads.
 *
 * The JVM keeps that pool exactly. iOS reads [Dispatchers.Default] - Native
 * has no public IO dispatcher, and URLSession does not need one: the engine
 * returns to its own callback threads and this pool never has to absorb
 * blocking work on that side.
 */
internal expect val AppIoDispatcher: CoroutineDispatcher
