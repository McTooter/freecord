package com.music.bitchord.platform

/**
 * The character folding iOS does where the JVM uses `java.text.Normalizer`.
 *
 * Kotlin/Native maps `NSString` onto `String` itself, so Foundation's
 * normalization methods are unreachable from Kotlin, and a cinterop shim into
 * ICU is not something a klib built on Windows could carry. What the call
 * sites actually want is narrower than full Unicode normalization anyway:
 * they fold artist and title text so "José" and "Jose" compare equal and
 * fullwidth forms collapse. A table over Latin plus the fullwidth block does
 * exactly that — and both sides of every comparison are folded by this same
 * code, so the fold is internally consistent on the platform.
 */
internal fun foldDiacritics(text: String): String {
    if (text.isEmpty()) return text
    val out = StringBuilder(text.length)
    for (ch in text) {
        val mapped = LATIN_FOLD[ch]
        if (mapped != null) {
            out.append(mapped)
            continue
        }
        val cp = ch.code
        // Combining marks are dropped outright: a decomposed "e" + U+0301
        // arrives from somewhere else's normalization and must fold to "e".
        if (cp in 0x0300..0x036F ||
            cp in 0x1AB0..0x1AFF ||
            cp in 0x1DC0..0x1DFF ||
            cp in 0x20D0..0x20FF
        ) {
            continue
        }
        out.append(foldFullwidth(cp) ?: ch)
    }
    return out.toString()
}

/** Compatibility folding: diacritics, then the ligatures NFKC would expand. */
internal fun foldCompatibility(text: String): String {
    val folded = foldDiacritics(text)
    if (!folded.any { it in LIGATURES }) return folded
    val out = StringBuilder(folded.length)
    for (ch in folded) {
        val ligature = LIGATURES[ch]
        if (ligature != null) out.append(ligature) else out.append(ch)
    }
    return out.toString()
}

/** Fullwidth ASCII variants and the ideographic space; null when not one. */
private fun foldFullwidth(cp: Int): Char? = when (cp) {
    in 0xFF01..0xFF5E -> (cp - 0xFEE0).toChar()
    0x3000 -> ' '
    else -> null
}

private val LIGATURES = mapOf(
    'ﬁ' to "fi",
    'ﬂ' to "fl",
    'ﬃ' to "ffi",
    'ﬄ' to "ffl",
    'ﬅ' to "st",
    'ﬆ' to "st",
)

/** Precomposed letters and the marks they carry, Latin-1 through Latin Extended-B. */
private val LATIN_FOLD: Map<Char, String> = buildMap {
    fun pair(from: String, to: String) {
        for ((ch, target) in from.zip(to)) put(ch, target.toString())
    }

    // Latin-1 supplement.
    pair("ÀÁÂÃÄÅ", "AAAAAA"); pair("àáâãäå", "aaaaaa")
    pair("ÇĆĈĊČ", "CCCCC"); pair("çćĉċč", "ccccc")
    pair("ÈÉÊË", "EEEE"); pair("èéêë", "eeee")
    pair("ÌÍÎÏ", "IIII"); pair("ìíîï", "iiii")
    pair("ÑŃŅŇ", "NNNN"); pair("ñńņň", "nnnn")
    pair("ÒÓÔÕÖØ", "OOOOOO"); pair("òóôõöø", "oooooo")
    pair("ÙÚÛÜ", "UUUU"); pair("ùúûü", "uuuu")
    pair("ÝŸ", "YY"); pair("ýÿ", "yy")
    pair("Ð", "D"); pair("ð", "d")

    // Latin Extended-A.
    pair("ĀĂĄ", "AAA"); pair("āăą", "aaa")
    pair("ĈĊ", "CC"); pair("ĉċ", "cc")
    pair("ĎĐ", "DD"); pair("ďđ", "dd")
    pair("ĒĔĖĘĚ", "EEEEE"); pair("ēĕėęě", "eeeee")
    pair("ĜĠĢ", "GGG"); pair("ĝġģ", "ggg")
    pair("ĤĦ", "HH"); pair("ĥħ", "hh")
    pair("ĨĪĬĮ", "IIII"); pair("ĩīĭį", "iiii")
    pair("İ", "I"); pair("ı", "i")
    pair("Ĵ", "J"); pair("ĵ", "j")
    pair("Ķ", "K"); pair("ķ", "k")
    pair("ĹĻĽĿ", "LLLL"); pair("ĺļľŀ", "llll")
    pair("Ŋ", "N"); pair("ŉ", "n"); pair("ŋ", "n")
    pair("ŌŎŐ", "OOO"); pair("ōŏő", "ooo")
    pair("ŔŖŘ", "RRR"); pair("ŕŗř", "rrr")
    pair("ŚŜŞŠ", "SSSS"); pair("śŝşš", "ssss")
    pair("ŢŤŦ", "TTT"); pair("ţťŧ", "ttt")
    pair("ŨŪŬŮŲŰ", "UUUUUU"); pair("ũūŭůųű", "uuuuuu")
    pair("Ŵ", "W"); pair("ŵ", "w")
    pair("Ŷ", "Y"); pair("ŷ", "y")
    pair("Ÿ", "Y")
    pair("ŹŻŽ", "ZZZ"); pair("źżž", "zzz")
    pair("ſ", "s")

    // Anything that expands to more than one letter is entered by hand: a zip
    // would fold "ß" to just "s" and lose the second s entirely.
    put('ß', "ss")
    put('Þ', "Th")
    put('þ', "th")
    put('Æ', "AE")
    put('æ', "ae")
    put('Œ', "OE")
    put('œ', "oe")
    put('Ĳ', "IJ")
    put('ĳ', "ij")
    put('Ǆ', "DZ")
    put('ǅ', "Dz")
    put('ǆ', "dz")
    put('Ǉ', "LJ")
    put('ǈ', "Lj")
    put('ǉ', "lj")
    put('Ǌ', "NJ")
    put('ǋ', "Nj")
    put('ǌ', "nj")
    put('Ǳ', "DZ")
    put('ǲ', "Dz")
    put('ǳ', "dz")

    // Latin Extended-B, the letters music metadata actually runs into.
    pair("ǍǏǑǓ", "AIOU"); pair("ǎǐǒǔ", "aiou")
    pair("ȦȮ", "AO"); pair("ȧȯ", "ao")
    pair("Ǹ", "N"); pair("ǹ", "n")
    pair("Ǻ", "A"); pair("ǻ", "a")
    pair("Ǿ", "O"); pair("ǿ", "o")
}
