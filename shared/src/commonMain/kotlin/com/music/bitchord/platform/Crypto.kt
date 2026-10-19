package com.music.bitchord.platform

/**
 * The digests the shared code signs and caches with, in pure Kotlin.
 *
 * These are one-shot hashes over small payloads — InnerTube's signature `n`
 * transform, a cache key, Musixmatch's HMAC — never bulk data, so a
 * hand-rolled implementation costs nothing measurable and saves an expect
 * around every crypto framework (javax.crypto on the JVM, CommonCrypto on iOS).
 * Both platforms run this same code, so a digest cannot drift by platform.
 */

internal fun sha1(bytes: ByteArray): ByteArray {
    var h0 = 0x67452301
    var h1 = 0xEFCDAB89u.toInt()
    // Hex literals above 0x7FFFFFFF infer as Long; the `u.toInt()` pair pins
    // them to the wraparound Int the digest arithmetic runs on.
    var h2 = 0x98BADCFEu.toInt()
    var h3 = 0x10325476
    var h4 = 0xC3D2E1F0u.toInt()

    val lengthBits = bytes.size.toLong() * 8L
    val padded = ByteArray((bytes.size + 9 + 63) and 63.inv())
    bytes.copyInto(padded)
    padded[bytes.size] = 0x80.toByte()
    for (i in 0..7) {
        padded[padded.size - 8 + i] = (lengthBits ushr (56 - i * 8)).toByte()
    }

    val w = IntArray(80)
    var offset = 0
    while (offset < padded.size) {
        for (i in 0..15) {
            val j = offset + i * 4
            w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                ((padded[j + 1].toInt() and 0xFF) shl 16) or
                ((padded[j + 2].toInt() and 0xFF) shl 8) or
                (padded[j + 3].toInt() and 0xFF)
        }
        for (i in 16..79) {
            w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        for (i in 0..79) {
            val f: Int
            val k: Int
            when (i) {
                in 0..19 -> {
                    f = (b and c) or (b.inv() and d)
                    k = 0x5A827999
                }
                in 20..39 -> {
                    f = b xor c xor d
                    k = 0x6ED9EBA1
                }
                in 40..59 -> {
                    f = (b and c) or (b and d) or (c and d)
                    k = 0x8F1BBCDCu.toInt()
                }
                else -> {
                    f = b xor c xor d
                    k = 0xCA62C1D6u.toInt()
                }
            }
            val temp = a.rotateLeft(5) + f + e + k + w[i]
            e = d
            d = c
            c = b.rotateLeft(30)
            b = a
            a = temp
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        offset += 64
    }

    return digestBytes(h0, h1, h2, h3, h4)
}

private val SHA256_K = intArrayOf(
    0x428A2F98, 0x71374491, -0x4A3F0431, -0x164A245B, 0x3956C25B, 0x59F111F1, -0x6DC07D5C, -0x54E3A12B,
    -0x27F85568, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, -0x7F214E02, -0x6423F959, -0x3E640E8C,
    -0x1B64963F, -0x1041B87A, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
    -0x67C1AEAE, -0x57CE3993, -0x4FFCD838, -0x40A68039, -0x391FF40D, -0x2A586EB9, 0x06CA6351, 0x14292967,
    0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, -0x7E3D36D2, -0x6D8DD37B,
    -0x5D40175F, -0x57E599B5, -0x3DB47490, -0x3893AE5D, -0x2E6D17E7, -0x2966F9DC, -0x0BF1CA7B, 0x106AA070,
    0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
    0x748F82EE, 0x78A5636F, -0x7B3787EC, -0x7338fdf8, -0x6F410006, -0x5bAF9315, -0x41065C09, -0x398E870E,
)

internal fun sha256(bytes: ByteArray): ByteArray {
    var h0 = 0x6A09E667
    var h1 = -0x4498517B
    var h2 = 0x3C6EF372
    var h3 = -0x5AB00AC6
    var h4 = 0x510E527F
    var h5 = -0x64FA9774
    var h6 = 0x1F83D9AB
    var h7 = 0x5BE0CD19

    val lengthBits = bytes.size.toLong() * 8L
    val padded = ByteArray((bytes.size + 9 + 63) and 63.inv())
    bytes.copyInto(padded)
    padded[bytes.size] = 0x80.toByte()
    for (i in 0..7) {
        padded[padded.size - 8 + i] = (lengthBits ushr (56 - i * 8)).toByte()
    }

    // Per call, not shared: two lyrics lookups hashing at once must not scribble
    // on each other's schedule.
    val w = IntArray(64)
    var offset = 0
    while (offset < padded.size) {
        for (i in 0..15) {
            val j = offset + i * 4
            w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                ((padded[j + 1].toInt() and 0xFF) shl 16) or
                ((padded[j + 2].toInt() and 0xFF) shl 8) or
                (padded[j + 3].toInt() and 0xFF)
        }
        for (i in 16..63) {
            val s0 = (w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3))
            val s1 = (w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10))
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7
        for (i in 0..63) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + SHA256_K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
        offset += 64
    }

    return digestBytes(h0, h1, h2, h3, h4, h5, h6, h7)
}

private fun digestBytes(h0: Int, h1: Int, h2: Int, h3: Int, h4: Int): ByteArray {
    val out = ByteArray(20)
    writeInt(out, 0, h0)
    writeInt(out, 4, h1)
    writeInt(out, 8, h2)
    writeInt(out, 12, h3)
    writeInt(out, 16, h4)
    return out
}

private fun digestBytes(h0: Int, h1: Int, h2: Int, h3: Int, h4: Int, h5: Int, h6: Int, h7: Int): ByteArray {
    val out = ByteArray(32)
    writeInt(out, 0, h0)
    writeInt(out, 4, h1)
    writeInt(out, 8, h2)
    writeInt(out, 12, h3)
    writeInt(out, 16, h4)
    writeInt(out, 20, h5)
    writeInt(out, 24, h6)
    writeInt(out, 28, h7)
    return out
}

private fun writeInt(out: ByteArray, at: Int, value: Int) {
    out[at] = (value ushr 24).toByte()
    out[at + 1] = (value ushr 16).toByte()
    out[at + 2] = (value ushr 8).toByte()
    out[at + 3] = value.toByte()
}

/** HMAC-SHA256 over [message] with [key], per RFC 2104. */
internal fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    var normalizedKey = key
    if (normalizedKey.size > 64) normalizedKey = sha256(normalizedKey)
    val paddedKey = ByteArray(64)
    normalizedKey.copyInto(paddedKey)

    val inner = ByteArray(message.size + 64)
    for (i in 0..63) inner[i] = (paddedKey[i].toInt() xor 0x36).toByte()
    message.copyInto(inner, 64)

    // 64 bytes of ipad-opad key plus the 32-byte inner digest.
    val outer = ByteArray(96)
    for (i in 0..63) outer[i] = (paddedKey[i].toInt() xor 0x5C).toByte()
    sha256(inner).copyInto(outer, 64)
    return sha256(outer)
}

private val HEX = "0123456789abcdef".toCharArray()

/** Lowercase hex, the shape every cache key and signature check in this codebase expects. */
internal fun ByteArray.toHexLower(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val b = this[i].toInt() and 0xFF
        out[i * 2] = HEX[b ushr 4]
        out[i * 2 + 1] = HEX[b and 0x0F]
    }
    return out.concatToString()
}

/** Hex with the uppercase the ISRC-shaped ids use. */
internal fun ByteArray.toHexUpper(): String = toHexLower().uppercase()
