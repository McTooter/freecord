package com.music.bitchord.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Published vectors, because a digest that is merely plausible is worse than
 * none: Innertube signs with these and Musixmatch authenticates with them, and
 * a wrong constant looks exactly like a broken service.
 */
class CryptoTest {
    @Test
    fun sha1EmptyString() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1(ByteArray(0)).toHexLower())
    }

    @Test
    fun sha1Message() {
        assertEquals(
            "a9993e364706816aba3e25717850c26c9cd0d89d",
            sha1("abc".encodeToByteArray()).toHexLower(),
        )
    }

    @Test
    fun sha256EmptyString() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHexLower(),
        )
    }

    @Test
    fun sha256Message() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".encodeToByteArray()).toHexLower(),
        )
    }

    @Test
    fun sha256MultiBlock() {
        // Long enough to cross two blocks after padding, exercising the extend loop.
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()).toHexLower(),
        )
    }

    @Test
    fun hmacSha256Rfc4231Case2() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?".
        val mac = hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray())
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            mac.toHexLower(),
        )
    }
}
