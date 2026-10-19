package com.music.bitchord.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The write vocabulary every ported cache sits on.
 *
 * [AppConcurrentMap.set] once answered `map.put`'s previous value and threw
 * when there was none, which is to say on every first insert — the whole
 * write path, exercised by nothing. These are the answers the callers
 * (stream loudness, track log, the ISRC cache) were written against.
 */
class AppConcurrentMapTest {

    @Test
    fun setInsertsAndReplacesWithoutThrowing() {
        val map = AppConcurrentMap<String, String>()
        map["a"] = "first"
        assertEquals("first", map["a"])
        map["a"] = "second"
        assertEquals("second", map["a"])
    }

    @Test
    fun putAnswersThePreviousValueOrNull() {
        val map = AppConcurrentMap<String, String>()
        assertNull(map.put("a", "first"))
        assertEquals("first", map.put("a", "second"))
    }

    @Test
    fun putIfAbsentKeepsWhatWasThereAndSaysWhetherItStored() {
        val map = AppConcurrentMap<String, String>()
        assertNull(map.putIfAbsent("a", "first"))
        assertEquals("first", map.putIfAbsent("a", "other"))
        assertEquals("first", map["a"])
    }

    @Test
    fun computeIfAbsentRunsOncePerKey() {
        val map = AppConcurrentMap<String, String>()
        var built = 0
        val first = map.computeIfAbsent("a") { built++; "made" }
        val second = map.computeIfAbsent("a") { built++; "other" }
        assertEquals("made", first)
        assertEquals("made", second)
        assertEquals(1, built)
    }

    @Test
    fun removeAnswersWhatWasThere() {
        val map = AppConcurrentMap<String, String>()
        map["a"] = "first"
        assertEquals("first", map.remove("a"))
        assertNull(map["a"])
        assertTrue(map.isEmpty)
        assertFalse(map.containsKey("a"))
    }
}
