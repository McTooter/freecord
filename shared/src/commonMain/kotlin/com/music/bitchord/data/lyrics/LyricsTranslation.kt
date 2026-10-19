package com.music.bitchord.data.lyrics

import com.music.bitchord.platform.AppCacheDirectory
import com.music.bitchord.platform.AppIoDispatcher
import com.music.bitchord.platform.AppLock
import com.music.bitchord.platform.isIoFailure
import com.music.bitchord.platform.platformHttpClient
import com.music.bitchord.platform.platformLanguageOfTag
import com.music.bitchord.platform.sha256
import com.music.bitchord.platform.toHexLower
import com.music.bitchord.platform.withLock
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile

/**
 * Lightweight, on-demand lyric translation.
 *
 * Translation models are deliberately not installed on the device. A model for
 * every app language would cost tens of megabytes each; translated lyric text
 * is normally only a few kilobytes. Requests are batched and the compact result
 * is kept in a bounded cache under [Context.getCacheDir], so Android may reclaim
 * it and the feature can never grow without limit.
 */
object LyricsTranslation {
    sealed interface Result {
        data class Translated(
            val lines: List<LyricLine>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : Result

        data class SameLanguage(val language: String) : Result
        data object Unavailable : Result
    }

    sealed interface RomanizationResult {
        data class Romanized(
            val lines: List<LyricLine>,
            val sourceLanguage: String,
            val fromCache: Boolean,
        ) : RomanizationResult

        data object AlreadyRomanized : RomanizationResult
        data object Unavailable : RomanizationResult
    }

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val INPUT_TOOLS_ENDPOINT = "https://inputtools.google.com/request"
    // Version 3 invalidates answers cached before the Latin-script retry existed;
    // otherwise a song that once came back untranslated would never take the
    // repaired path after the app is updated.
    private const val CACHE_VERSION = 3
    private const val CACHE_DIRECTORY = "lyrics_translation_v3"
    private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L
    private const val MAX_BATCH_CHARS = 3_500
    private const val MAX_PARALLEL_REQUESTS = 2
    // Input Tools changes ASCII digits to the destination script, so match the
    // private-use wrapper rather than assuming the counter remains ASCII.
    private val markerRegex = Regex("\\uE000[^\\uE001]*\\uE001")
    private val json = Json { ignoreUnknownKeys = true }
    private val diskMutex = Mutex()

    // The access-ordered map that dropped its eldest past twelve answers, kept
    // here under one lock where the JVM version had @Synchronized and a
    // removeEldestEntry override: a read moves the answer last, a write drops
    // the front while the map is over the bound.
    private val memoryLock = AppLock()
    private val memory = LinkedHashMap<String, CachedTranslation>()

    private fun memoryGet(key: String): CachedTranslation? = memoryLock.withLock {
        memory.remove(key)?.also { memory[key] = it }
    }

    private fun memoryPut(key: String, value: CachedTranslation) = memoryLock.withLock {
        memory.remove(key)
        memory[key] = value
        while (memory.size > 12) memory.remove(memory.keys.first())
    }

    /**
     * Where translated lyrics are kept between runs — the phone's cache
     * directory, the desktop's media cache. Null keeps them in memory only.
     */
    @Volatile
    var cacheDir: String? = null

    private val client by lazy {
        platformHttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 12_000
                connectTimeoutMillis = 5_000
            }
        }
    }

    @Serializable
    private data class CachedTranslation(
        val version: Int = CACHE_VERSION,
        val sourceLanguage: String,
        val targetLanguage: String,
        val texts: List<String>,
    )

    private data class TextSlot(
        val lineIndex: Int,
        val background: Boolean,
        val text: String,
        val sectionHeader: Boolean,
    )

    private data class Batch(
        val slots: List<TextSlot>,
        val payload: String,
    )

    private data class BatchAnswer(
        val translations: List<String>,
        val sourceLanguage: String,
        val sourceWeight: Int,
    )

    suspend fun translate(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): Result {
        // Sent as given rather than reduced to a base language: zh-CN and
        // zh-TW are the same language in two scripts, and canonicalising either
        // to "zh" hands back Simplified whichever one was asked for. The
        // narrowing is still done, but only where it belongs — in
        // [sameLanguage], which is asking a different question.
        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) return Result.Unavailable

        val slots = flatten(lines)
        if (slots.isEmpty()) return Result.Unavailable
        val cacheKey = cacheKey(trackId, target, slots)
        val cached = memoryGet(cacheKey) ?: readCache(cacheKey)?.also {
            memoryPut(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return if (sameLanguage(cached.sourceLanguage, target)) {
                Result.SameLanguage(cached.sourceLanguage)
            } else {
                Result.Translated(
                    lines = rebuild(lines, slots, cached.texts),
                    sourceLanguage = cached.sourceLanguage,
                    fromCache = true,
                )
            }
        }

        val batches = batches(slots)
        val answers = coroutineScope {
            // Two short requests at a time keeps a long lyric fast without
            // competing with playback for every connection in the pool.
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return Result.Unavailable
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        if (source.isBlank()) return Result.Unavailable

        var translated = complete.flatMap { it.translations }
        if (translated.size != slots.size) return Result.Unavailable

        // Google's ordinary auto-detection understands many Latin-script
        // Hindi/Urdu/Punjabi lyrics, but its NMT occasionally returns whole
        // phrases unchanged ("tera hone laga hoon" is a common example). If a
        // sizeable part of a Latin-script source survived untouched, use
        // Google's Input Tools to restore the detected language's native script
        // and translate that. Keep the first answer unless the retry actually
        // transforms more of the song, so names and genuinely bilingual lyrics
        // do not get worse merely because they contain Latin text.
        if (
            !sameLanguage(source, target) &&
            predominantlyLatin(slots) &&
            unchangedWeight(slots, translated) * 3 >= slots.sumOf { it.text.length }
        ) {
            val retried = retryRomanizedTranslation(batches, source, target)
            if (
                retried != null &&
                retried.size == slots.size &&
                unchangedWeight(slots, retried) < unchangedWeight(slots, translated)
            ) {
                translated = retried
            }
        }
        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = target,
            texts = translated,
        )
        memoryPut(cacheKey, entry)
        writeCache(cacheKey, entry)

        return if (sameLanguage(source, target)) {
            Result.SameLanguage(source)
        } else {
            Result.Translated(
                lines = rebuild(lines, slots, translated),
                sourceLanguage = source,
                fromCache = false,
            )
        }
    }

    suspend fun romanize(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): RomanizationResult {
        if (lines.isEmpty()) return RomanizationResult.Unavailable
        val slots = flatten(lines)
        if (slots.isEmpty()) return RomanizationResult.Unavailable
        if (!hasNonLatinLetters(slots)) return RomanizationResult.AlreadyRomanized

        // Romanization always ends in Latin script. The translation destination
        // is still sent as `tl` because the web endpoint requires it, but it is
        // deliberately excluded from this cache key: changing "translate to"
        // cannot change how the source language is pronounced.
        val cacheKey = cacheKey(trackId, "romanize", slots)
        val cached = memoryGet(cacheKey) ?: readCache(cacheKey)?.also {
            memoryPut(cacheKey, it)
        }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return RomanizationResult.Romanized(
                lines = rebuild(lines, slots, cached.texts),
                sourceLanguage = cached.sourceLanguage,
                fromCache = true,
            )
        }

        val target = targetLanguageTag.trim().ifBlank { "en" }
        val batches = batches(slots)
        val answers = coroutineScope {
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestRomanizationBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return RomanizationResult.Unavailable
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        val romanized = complete.flatMap { it.translations }
        if (source.isBlank() || romanized.size != slots.size) return RomanizationResult.Unavailable
        if (unchangedWeight(slots, romanized) == slots.sumOf { it.text.length }) {
            // Non-Latin input reached this point, so an unchanged response is
            // an unsupported/failed romanization rather than an already-Latin
            // lyric. The fast all-Latin check above handles the real no-op case.
            return RomanizationResult.Unavailable
        }

        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = "Latn",
            texts = romanized,
        )
        memoryPut(cacheKey, entry)
        writeCache(cacheKey, entry)
        return RomanizationResult.Romanized(
            lines = rebuild(lines, slots, romanized),
            sourceLanguage = source,
            fromCache = false,
        )
    }

    private fun flatten(lines: List<LyricLine>): List<TextSlot> = buildList {
        lines.forEachIndexed { index, line ->
            if (line.text.isNotBlank()) {
                val header = Genius.isSectionHeader(line.text)
                add(
                    TextSlot(
                        lineIndex = index,
                        background = false,
                        text = if (header) line.text.removePrefix("[").removeSuffix("]").trim() else line.text,
                        sectionHeader = header,
                    ),
                )
            }
            line.background?.takeIf { it.text.isNotBlank() }?.let { background ->
                add(TextSlot(index, background = true, background.text, sectionHeader = false))
            }
        }
    }

    private fun batches(slots: List<TextSlot>): List<Batch> {
        val result = mutableListOf<Batch>()
        var current = mutableListOf<TextSlot>()
        var length = 0

        fun flush() {
            if (current.isEmpty()) return
            result += Batch(current.toList(), payload(current))
            current = mutableListOf()
            length = 0
        }

        slots.forEach { slot ->
            val added = slot.text.length + if (current.isEmpty()) 0 else 8
            if (current.isNotEmpty() && length + added > MAX_BATCH_CHARS) flush()
            current += slot
            length += added
        }
        flush()
        return result
    }

    private fun payload(slots: List<TextSlot>): String = buildString {
        slots.forEachIndexed { index, slot ->
            if (index > 0) append('\n').append(marker(index)).append('\n')
            append(slot.text)
        }
    }

    private fun marker(index: Int): String = "\uE000${index.toString().padStart(4, '0')}\uE001"

    private suspend fun requestBatch(
        batch: Batch,
        target: String,
        sourceLanguage: String = "auto",
    ): BatchAnswer? {
        val response = postForm(
            ENDPOINT,
            listOf(
                "client" to "dict-chrome-ex",
                "sl" to sourceLanguage,
                "tl" to target,
                "dt" to "t",
                "q" to batch.payload,
            ),
        ) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val translatedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = translatedBody.split(markerRegex).map(String::trim)
            if (parts.size != batch.slots.size || parts.any { it.isBlank() }) return@runCatching null
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private suspend fun requestRomanizationBatch(batch: Batch, target: String): BatchAnswer? {
        val response = postForm(
            ENDPOINT,
            listOf(
                "client" to "dict-chrome-ex",
                "sl" to "auto",
                "tl" to target,
                "dt" to "rm",
                "q" to batch.payload,
            ),
        ) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val romanizedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(3)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = romanizedBody.split(markerRegex).map(String::trim)
            if (source.isBlank() || parts.size != batch.slots.size || parts.any { it.isBlank() }) {
                return@runCatching null
            }
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private suspend fun retryRomanizedTranslation(
        batches: List<Batch>,
        sourceLanguage: String,
        target: String,
    ): List<String>? = coroutineScope {
        val source = canonicalLanguage(sourceLanguage)
        if (source.isBlank() || source == "en") return@coroutineScope null
        val answers = batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
            group.map { batch ->
                async {
                    val nativePayload = requestNativeScript(batch.payload, source) ?: return@async null
                    requestBatch(batch.copy(payload = nativePayload), target, source)
                }
            }.awaitAll()
        }
        if (answers.any { it == null }) null else answers.filterNotNull().flatMap { it.translations }
    }

    private suspend fun requestNativeScript(text: String, sourceLanguage: String): String? {
        val response = postForm(
            INPUT_TOOLS_ENDPOINT,
            listOf(
                "text" to text,
                "itc" to "$sourceLanguage-t-i0-und",
                "num" to "1",
                "cp" to "0",
                "cs" to "1",
                "ie" to "utf-8",
                "oe" to "utf-8",
            ),
        ) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            if (root.getOrNull(0)?.jsonPrimitive?.contentOrNull != "SUCCESS") return@runCatching null
            root[1].jsonArray[0].jsonArray[1].jsonArray[0].jsonPrimitive.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun predominantlyLatin(slots: List<TextSlot>): Boolean {
        var latin = 0
        var other = 0
        slots.forEach { slot ->
            forEachCodePoint(slot.text) { codePoint ->
                if (isLetterCodePoint(codePoint)) {
                    if (isLatinCodePoint(codePoint)) latin++
                    else other++
                }
            }
        }
        return latin > 0 && latin >= other * 4
    }

    private fun hasNonLatinLetters(slots: List<TextSlot>): Boolean = slots.any { slot ->
        var found = false
        forEachCodePoint(slot.text) { codePoint ->
            if (isLetterCodePoint(codePoint) && !isLatinCodePoint(codePoint)) found = true
        }
        found
    }

    /**
     * Every code point of [text], surrogate pairs kept together — the JVM's
     * `String.codePoints()` by another route, since no platform here hands
     * that one over.
     */
    private inline fun forEachCodePoint(text: String, action: (Int) -> Unit) {
        var index = 0
        while (index < text.length) {
            val high = text[index]
            if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                action(0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00))
                index += 2
            } else {
                action(high.code)
                index++
            }
        }
    }

    /**
     * `Character.isLetter`'s one question — the Unicode letter category —
     * answered per plane: the BMP by [Char.isLetter] itself, and above it the
     * two ranges that hold letters, the historic scripts just over the BMP and
     * the CJK extensions. The emoji planes between them hold symbols, which
     * isLetter never counted either.
     */
    private fun isLetterCodePoint(codePoint: Int): Boolean = when {
        codePoint <= 0xFFFF -> codePoint.toChar().isLetter()
        codePoint < 0x1F000 -> true
        codePoint in 0x20000..0x3FFFF -> true
        else -> false
    }

    /**
     * `Character.UnicodeScript.of(codePoint) == LATIN`, read off the Latin
     * blocks since no platform here carries the script table: the whole Latin
     * extent, Basic Latin out through both Extended ranges, IPA, Additional,
     * the Latin ligatures and the fullwidth forms — every block the script
     * table names Latin, and none it names anything else.
     */
    private fun isLatinCodePoint(codePoint: Int): Boolean = LATIN_BLOCKS.any { codePoint in it }

    private val LATIN_BLOCKS = listOf(
        0x0041..0x005A, 0x0061..0x007A, // Basic Latin
        0x00C0..0x00FF, // Latin-1 Supplement
        0x0100..0x024F, // Latin Extended-A and -B
        0x0250..0x02AF, // IPA Extensions
        0x1E00..0x1EFF, // Latin Extended Additional
        0x2C60..0x2C7F, // Latin Extended-C
        0xA720..0xA7FF, // Latin Extended-D
        0xFB00..0xFB06, // Alphabetic Presentation Forms, the Latin ligatures
        0xFF21..0xFF3A, 0xFF41..0xFF5A, // Fullwidth Latin
    )

    private fun unchangedWeight(slots: List<TextSlot>, transformed: List<String>): Int =
        slots.zip(transformed).sumOf { (slot, text) ->
            if (comparable(slot.text) == comparable(text)) slot.text.length else 0
        }

    private fun comparable(text: String): String = text
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")

    /**
     * The form POST this file always made — the pairs in the order given, as
     * `application/x-www-form-urlencoded`, with the two headers every endpoint
     * here was asked for — and an answer only when the endpoint said yes.
     * [CancellationException] keeps its own way out, and only "the call could
     * not be made" reads as no answer; a timeout is one of those on every
     * platform, which is what [isIoFailure] answers for.
     */
    private suspend fun postForm(url: String, form: List<Pair<String, String>>): String? {
        val response = try {
            client.post(url) {
                header("User-Agent", "BitChord/1.7.1")
                header("Accept", "application/json")
                setBody(
                    FormDataContent(
                        parameters {
                            for ((name, value) in form) append(name, value)
                        },
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!isIoFailure(failure)) throw failure
            return null
        }
        return if (response.status.isSuccess()) response.bodyAsText() else null
    }

    private fun rebuild(
        original: List<LyricLine>,
        slots: List<TextSlot>,
        translated: List<String>,
    ): List<LyricLine> {
        val byLine = slots.zip(translated).groupBy { it.first.lineIndex }
        return original.mapIndexed { index, line ->
            val entries = byLine[index].orEmpty()
            val lead = entries.firstOrNull { !it.first.background }
            val backing = entries.firstOrNull { it.first.background }
            val leadText = lead?.let { (slot, text) -> if (slot.sectionHeader) "[$text]" else text }
                ?: line.text
            line.copy(
                text = leadText,
                words = retimeWords(line, leadText),
                timingSource = line.takeIf { it.isWordSynced },
                background = line.background?.let { source ->
                    val text = backing?.second ?: source.text
                    source.copy(
                        text = text,
                        words = retimeWords(source, text),
                        timingSource = source.takeIf { it.isWordSynced },
                    )
                },
            )
        }
    }

    /**
     * Keep the source vocal bounds without inventing translated word timings.
     * LyricLine.timingSource projects the original non-uniform character sweep
     * onto the new text, preserving holds, pauses and the original glow envelope.
     */
    private fun retimeWords(source: LyricLine, translated: String): List<LyricWord> {
        if (source.words.isEmpty()) return emptyList()
        if (translated.isEmpty()) return emptyList()
        val start = source.words.first().startMs
        return listOf(LyricWord(start, source.words.last().endMs, translated))
    }

    private fun canonicalLanguage(tag: String): String = when (
        platformLanguageOfTag(tag).lowercase()
    ) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> platformLanguageOfTag(tag).lowercase()
    }

    private fun sameLanguage(first: String, second: String): Boolean =
        canonicalLanguage(first) == canonicalLanguage(second)

    private fun cacheKey(trackId: String, target: String, slots: List<TextSlot>): String {
        val source = buildString {
            append(trackId).append('\u0000').append(target)
            slots.forEach { append('\u0000').append(it.text) }
        }
        return sha256(source.encodeToByteArray()).toHexLower()
    }

    private suspend fun readCache(key: String): CachedTranslation? =
        withContext(AppIoDispatcher) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock null
                val directory = AppCacheDirectory("$root/$CACHE_DIRECTORY")
                val stored = directory.read("$key.json.gz") ?: return@withLock null
                runCatching {
                    val value = json.decodeFromString<CachedTranslation>(stored)
                    directory.touch("$key.json.gz")
                    value.takeIf { it.version == CACHE_VERSION }
                }.getOrNull()
            }
        }

    private suspend fun writeCache(key: String, value: CachedTranslation) =
        withContext(AppIoDispatcher) {
            diskMutex.withLock {
                val root = cacheDir ?: return@withLock
                val directory = AppCacheDirectory("$root/$CACHE_DIRECTORY")
                runCatching {
                    if (directory.write("$key.json.gz", json.encodeToString(value))) {
                        directory.trim("gz", MAX_CACHE_BYTES)
                    }
                }.getOrNull()
            }
        }
}
