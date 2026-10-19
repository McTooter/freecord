package com.music.bitchord.data.innertube

import com.music.bitchord.data.Http
import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The JVM's failsafe answer: NewPipe behind [FailsafeExtractor] — what the
 * common resolver falls through to when InnerTubeX's catalog finds nothing.
 *
 * Everything here was StreamResolver's private machinery when that file lived
 * on the JVM, kept and named as its own type rather than bent into common
 * shape: the downloader, the extractor's leash, the watch-page extraction and
 * the classification of what extraction can say about a track.
 */
internal actual fun platformFailsafeExtractor(): FailsafeExtractor? = NewPipeFailsafe

/** The JVM's `LinkageError`: a class or method this OS version does not carry. */
internal actual fun isLinkageFailure(error: Throwable): Boolean = error is LinkageError

internal object NewPipeFailsafe : FailsafeExtractor {

    private const val TAG = "BitChord"

    /** Past this, an extractor fetch is worth flagging rather than just noting. */
    private const val SLOW_FETCH_MS = 2000L

    /** See [OkHttpDownloader.execute] — the one request the extractor is not allowed to make. */
    private const val NEXT_ENDPOINT = "/youtubei/v1/next"

    /** Well-formed, empty, and over the library's fifty-character floor. */
    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""

    /**
     * Re-derives a stream from the watch page, choosing under the ceiling —
     * or the destination's container, when it named one — exactly as the
     * resolver's two call sites used to spell out as select lambdas.
     */
    override suspend fun stream(videoId: String, maxKbps: Int, container: FailsafeContainer?): FailsafeStream? {
        val picked = newPipeStream(videoId, maxKbps, container)
        return FailsafeStream(url = picked.url, kbps = picked.kbps, mimeType = picked.mimeType)
    }

    override suspend fun signatureTimestamp(videoId: String): Int? =
        jsPlayerManager { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }

    private val init by lazy { NewPipe.init(OkHttpDownloader()) }

    /**
     * NewPipe's one-time initialisation — its downloader must be installed
     * before anything is asked of it. What StreamResolver's bare `init`
     * statements did at each entry to a resolve.
     */
    fun ensureInitialised() {
        init
    }

    /** NewPipe needs a Downloader; reuse the app's single OkHttp client. */
    private class OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            // The `next` endpoint answers "what plays after this" — related
            // videos and the autoplay queue. `fetchPage()` asks for it
            // unconditionally while building a StreamExtractor, and this app
            // never reads the answer: it is here for [audioStreams] and gets
            // its own up-next from [Innertube.next] on a different client.
            //
            // Declining it is worth a special case because of what it costs.
            // Measured across extractions, every other request in the chain
            // lands in 110-670ms, while this one takes seven seconds or simply
            // hangs — it was the single request behind
            // `extractor fetch FAILED after 12004ms`, and since one hung call
            // fails the whole attempt, an endpoint nothing here reads was
            // deciding whether a track played at all.
            //
            // Answered with an empty-but-well-formed envelope rather than an
            // error, so nothing downstream treats it as a failed fetch; NewPipe
            // finds no related items, which is exactly as many as are wanted.
            // It has to be this padded: the library rejects any JSON body under
            // fifty characters outright with "JSON response is too short", so a
            // bare `{}` fails the whole extraction rather than quietly
            // returning nothing.
            if (NEXT_ENDPOINT in request.url()) {
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, request.url())
            }
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }
            if (!hasUserAgent) {
                // Chrome, not Firefox: NewPipe's own internal fetches — the
                // player JS included — go out under whatever this default is.
                // PixelMusic-ref's equivalent downloader defaults to this same
                // Chrome UA and does not hit the "Could not parse
                // deobfuscation function" failure this app was getting on the
                // identical video and NewPipeExtractor version; a Firefox UA
                // here is the one input that differed.
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                )
            }

            // Every byte NewPipe fetches passes through here, and until this
            // line none of it was visible: an extraction that took thirty
            // seconds reported thirty seconds, with nothing to say whether that
            // was one shaped watch page, a player POST, or the player
            // JavaScript. Naming the request and its size is what makes the
            // difference between measuring the step and guessing at it.
            val requestStart = MonotonicClock.nowMs()
            val response = try {
                extractorClient.newCall(builder.build()).execute()
            } catch (e: Exception) {
                // The one that matters most, and the one a log written only on
                // the way out never sees: a request that times out or is torn
                // down produces no line at all, so an extraction killed by a
                // single hung call looks like an extraction that was slow for
                // no reason. Named here, then rethrown unchanged.
                TrackLog.w(
                    TAG,
                    "extractor fetch FAILED after ${MonotonicClock.nowMs() - requestStart}ms " +
                        "${request.httpMethod()} ${request.url()}: ${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
            val took = MonotonicClock.nowMs() - requestStart
            if (took > SLOW_FETCH_MS) {
                TrackLog.w(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${request.url()}")
            } else {
                TrackLog.d(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${request.url()}")
            }
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    /**
     * The extractor's own leash on [Http.client].
     *
     * Everything NewPipe fetches — the watch page above all — goes out through
     * here, and [Http.client] sets no `callTimeout` at all. Its 30-second read
     * timeout does not stand in for one: a read timeout is per read, so a
     * response that yields a few bytes at a time resets it forever and the call
     * never ends. That is not a hypothetical failure mode but the exact shaping
     * this file's header describes Google applying to the watch page, and it
     * was observed doing it — an extraction that simply never returned, twice
     * in a row, leaving a track buffering until ExoPlayer gave up and retried
     * into the same wall. Unbounded is the one thing this call must not be,
     * because it is the failsafe: nothing runs after it.
     *
     * It gets its **own connection pool**, and that is the point of it rather
     * than a detail. Sharing [Http.client]'s pool means the watch-page GET can
     * be handed a connection to `www.youtube.com` left over from an Innertube
     * `player` POST, and a pooled connection that the far end has quietly
     * stopped answering does not fail — it hangs, silently, until something
     * times it out. `retryOnConnectionFailure` cannot save it, because nothing
     * is failing. That is exactly the shape the measurements have: a first
     * attempt that burns the whole ceiling and a second, on a fresh connection,
     * that succeeds in 2.2 seconds. Its own pool means extraction never
     * inherits a socket some other part of the app finished with.
     *
     * Sharing the pool was never required, either. The address-family argument
     * in [Http] is about a googlevideo media fetch matching the `player`
     * request that minted its URL; this fetches HTML from `www.youtube.com`,
     * and the googlevideo URL it comes back with is fetched later through
     * [Http.client] regardless.
     *
     * The ceiling is sized against a healthy extraction — about two seconds —
     * rather than against patience. Twelve is generous enough that a merely
     * slow page still completes, and short enough that a hung one costs a few
     * seconds before [EXTRACTION_ATTEMPTS] tries again on a new connection,
     * instead of half a minute of silence.
     */
    private val extractorClient by lazy {
        Http.client.newBuilder()
            // Short, because a connection this app is *not* using is a
            // connection going stale. The observed failure is a request that
            // gets no response at all and dies on the ceiling exactly — twelve
            // thousand and one milliseconds, over and over, on three different
            // endpoints. That is not a slow server, it is a socket the far side
            // (or a NAT on the way) has silently dropped while it sat idle
            // between tracks, being handed to the next request as though it
            // were good. Half a minute of keepalive is short enough that most
            // are re-established rather than resurrected.
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            // And for the ones that go stale while held: HTTP/2 pings make the
            // client notice a dead peer itself, in seconds, instead of waiting
            // out a response that is never coming. These endpoints are all
            // HTTP/2, so this is the mechanism actually available for detecting
            // it rather than merely giving up on it.
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .callTimeout(EXTRACTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val PING_INTERVAL_SECONDS = 5L

    /**
     * Sized so that giving up and trying again is cheaper than waiting.
     *
     * A healthy extractor request lands in 100-700ms and the retry that follows
     * a hung one has, every time it has been watched, succeeded immediately —
     * so the ceiling is not protecting a slow-but-viable request, it is deciding
     * how long a dead connection costs. At twelve seconds one stall turned a
     * four-second start into twenty-six; at five, the same stall costs about
     * six seconds all in.
     */
    private const val EXTRACTOR_TIMEOUT_SECONDS = 5L

    // ---- Failsafe -----------------------------------------------------------

    /**
     * NewPipe's full extractor, kept for the case where every player client has
     * been turned away — it re-derives everything itself and is updated
     * upstream when YouTube changes, so it works when nothing else does.
     *
     * Last rather than first because of what it costs: it scrapes the watch
     * page, which is the request Google shapes hardest, and a shaped response
     * can hold this call open for the better part of a minute. Worth waiting
     * out when the alternative is silence; not worth paying for every track.
     *
     * Driven through [StreamExtractor][org.schabi.newpipe.extractor.stream.StreamExtractor]
     * directly rather than through `StreamInfo.getInfo`, which is the obvious
     * call and the expensive one. `getInfo` assembles everything a video page
     * has — it drives forty-odd extractor methods, among them `getVideoStreams`
     * and `getVideoOnlyStreams`, and YouTube answers those with twenty to
     * thirty formats whose `n` parameter each has to be transformed by running
     * YouTube's player JavaScript. This app then discards every one of them and
     * keeps the audio. Fetching the page and asking only for [audioStreams]
     * pays that transform four or five times instead: measured on-device at
     * 49.8s against 2.3s for the same track over the same connection.
     */
    private suspend fun newPipeStream(
        videoId: String,
        maxKbps: Int,
        container: FailsafeContainer?,
    ): StreamResolver.Stream {
        var failure: Exception? = null
        repeat(EXTRACTION_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(EXTRACTION_RETRY_MS * attempt)
            try {
                return extractStream(videoId, maxKbps, container)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                // Not a failed extraction — a method or class the extractor was
                // compiled against that this OS version does not have, which is
                // the same answer every time and so is not worth the remaining
                // attempts. It reaches here as an Error rather than an Exception,
                // and an Error let past this point does not fail the track: it
                // unwinds through ExoPlayer's loader thread, where nothing is
                // catching it, and takes the process with it. That is what a
                // NoSuchMethodError out of NewPipe's URL codec did on every
                // Android below 13 — see the note in the vendored
                // `org.schabi.newpipe.extractor.utils.Utils`. Reported as an
                // Exception so the layers above treat it as the load failure it
                // is, with the type name kept because the message alone ("No
                // static method ...") reads like nothing.
                throw IOException("Extractor cannot run on this device: $e", e)
            } catch (e: Exception) {
                // A verdict about the content, not about this minute. There was
                // no classification here at all, so the most expensive retry in
                // the app was spent on the one class of failure that cannot
                // benefit from it: an age-restricted track threw
                // AgeRestrictedContentException, which extends
                // ContentNotAvailableException extends ParsingException extends
                // Exception, landed in this clause, and was granted all three
                // attempts with 1s and 2s backoffs — each one re-fetching the
                // watch page and holding [extractionGate] against every other
                // resolve in the process while it did. Three identical refusals
                // per walk, and the walk itself repeated. Named and rethrown on
                // the first one instead — as the resolver's own verdict type,
                // which is what the common classification outside this object
                // recognises, since it cannot name these exception classes.
                permanentReason(e)?.let { reason ->
                    TrackLog.w(TAG, "extraction will not succeed for $videoId: $reason")
                    throw StreamResolver.PermanentlyUnplayableException(reason)
                }
                // Worth another go rather than worth giving up on: the common
                // failure here is a shaped or cut-off watch page, which is a
                // fact about this minute rather than about the track, and this
                // is the last thing standing between the listener and silence.
                TrackLog.w(
                    TAG,
                    "extraction attempt ${attempt + 1} of $EXTRACTION_ATTEMPTS failed for $videoId: ${e.message}",
                )
                failure = e
            }
        }
        throw failure ?: IllegalStateException("Track unavailable: no audio streams")
    }

    private suspend fun extractStream(
        videoId: String,
        maxKbps: Int,
        container: FailsafeContainer?,
    ): StreamResolver.Stream = extractionGate.withLock {
        withContext(Dispatchers.IO) {
            val waited = MonotonicClock.nowMs()
            val extractor = ServiceList.YouTube.getStreamExtractor(
                "https://www.youtube.com/watch?v=$videoId",
            )
            extractor.fetchPage()
            val candidates = extractor.audioStreams
                // Progressive only — DASH/HLS entries carry a manifest, not a URL.
                .filter {
                    !it.content.isNullOrBlank() &&
                        it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
                }
                .map { it.averageBitrate to it }
            // The container filter — asked of the format, not the name; see
            // [AudioStream.isM4a] — followed by the same ceiling policy
            // playback uses. Capped the same way as the player-response
            // selection, off the same setting, or the failsafe would quietly
            // hand back a rendition the user said they didn't want to keep.
            val matching = when (container) {
                null -> candidates
                FailsafeContainer.M4A -> candidates.filter { it.second.isM4a }
                FailsafeContainer.WEBM_OPUS -> candidates.filter { it.second.isWebmOpus }
            }
            val stream = underCeiling(matching, maxKbps)
                ?: error("Track unavailable: no audio streams")
            TrackLog.d(
                TAG,
                "NewPipe picked ${stream.format?.name} @ ${stream.averageBitrate}kbps " +
                    "(extraction held the gate ${MonotonicClock.nowMs() - waited}ms)",
            )
            // stream.content is already playable, not raw: YoutubeStreamExtractor
            // resolves the signature cipher and the `n` parameter itself while
            // building audioStreams, so it is handed on as-is.
            StreamResolver.Stream(
                url = stream.content,
                kbps = stream.averageBitrate,
                mimeType = stream.mime,
            )
        }
    }

    // ---- Format selection ---------------------------------------------------

    /**
     * Highest of [candidates] at or under [maxKbps]; if everything is above it
     * — Standard on a track whose AAC ladder starts at 256, say — the cheapest
     * available, because a rung over budget still beats no audio at all.
     */
    private fun <T> underCeiling(candidates: List<Pair<Int, T>>, maxKbps: Int): T? {
        if (candidates.isEmpty()) return null
        val withinBudget = candidates.filter { it.first <= maxKbps }
        return (withinBudget.maxByOrNull { it.first } ?: candidates.minByOrNull { it.first })
            ?.second
    }

    /**
     * Guards every call into [YoutubeJavaScriptPlayerManager].
     *
     * Its player-JS cache, including a *failed* parse's exception, lives in
     * static fields with no synchronization, shared by the whole process, and
     * this app resolves more than one track at once by design. A failure is
     * passed straight out rather than answered with `clearAllCaches` and a
     * retry: measured, that retry could only fail again and threw away the
     * parsed player the NewPipe fallback depends on, taking it from 2.7s to
     * 49.8s.
     */
    private val jsPlayerMutex = Mutex()

    private suspend fun <T> jsPlayerManager(block: () -> T): T = jsPlayerMutex.withLock { block() }

    /**
     * Why [e] means "never", or null if it only means "not just now".
     *
     * The distinction is the difference between one failed track and a request
     * storm, and it is not available from the exception hierarchy: NewPipe files
     * a takedown, a region block and a truncated watch page under the same
     * [ParsingException][org.schabi.newpipe.extractor.exceptions.ParsingException]
     * ancestry, so a `catch (e: Exception)` treats "this video does not exist"
     * exactly like "the page arrived cut in half". Every retry in this app sat
     * downstream of that conflation. This is the full classification; what the
     * common resolver sees after this object has had first refusal is
     * [StreamResolver.PermanentlyUnplayableException] or a transient.
     *
     * Kept as a message rather than a boolean because the message is what the
     * listener eventually sees, and "This video is age-restricted" is a
     * different thing to be told than `ERROR_CODE_IO_UNSPECIFIED`.
     */
    private fun permanentReason(e: Throwable): String? = when (e) {
        is StreamResolver.PermanentlyUnplayableException -> e.message ?: "This track cannot be played"
        // The whole reason this function exists — see [newPipeStream].
        is AgeRestrictedContentException ->
            if (Innertube.cookie == null) {
                "This track is age-restricted. Sign in to YouTube to play it."
            } else {
                "YouTube will not serve this age-restricted track to this app."
            }
        is GeographicRestrictionException -> "This track isn't available in your country"
        is UnsupportedContentInCountryException -> "This track isn't available in your country"
        is PaidContentException -> "This track is paid content"
        is YoutubeMusicPremiumContentException -> "This track needs YouTube Music Premium"
        is PrivateContentException -> "This track is private"
        is AccountTerminatedException -> "The channel behind this track was terminated"
        is SoundCloudGoPlusContentException -> "This track needs SoundCloud Go+"
        // The coroutine machinery wraps freely, and the classification has to
        // survive being wrapped or it never fires: the failure that reaches
        // this point arrives as whatever the last layer chose to throw it as.
        else -> e.cause?.takeIf { it !== e }?.let(::permanentReason)
    }

    /** How many times the watch page is worth asking for before giving up. */
    private const val EXTRACTION_ATTEMPTS = 3

    /** Multiplied by the attempt number, so the wait grows if the first retry doesn't take. */
    private const val EXTRACTION_RETRY_MS = 1000L

    /**
     * One extraction at a time, across the whole app.
     *
     * Extraction is not the network-bound step it looks like. Fetching the
     * watch page is the small part; the expensive part is running YouTube's
     * player JavaScript through Rhino to transform each format's `n`
     * parameter, which is CPU work on a phone against static state NewPipe
     * shares process-wide. Run concurrently it does not share out, it
     * collapses — measured on-device at 1.8s alone, 16.2s with two in flight
     * (both finishing within 35ms of each other, which is the tell), and 30.3s
     * with three. Read-ahead is what puts three or four in flight: the track
     * being waited on plus [AudioCache][com.music.bitchord.playback.AudioCache]'s
     * queue warm-up, every one of them an extraction now that no player client
     * is being served.
     *
     * So they are queued rather than raced. Serialised, three cost about two
     * seconds each in turn instead of thirty seconds each at once, and the one
     * a listener is actually waiting on is behind at most one other — see
     * `QUEUE_LOOKAHEAD`, kept at one for exactly this reason. The bound on how
     * long a single holder can keep the gate is [EXTRACTOR_TIMEOUT_SECONDS].
     */
    private val extractionGate = Mutex()

    /**
     * The container, which is all NewPipe's mime type reports.
     *
     * `MediaFormat.WEBMA_OPUS` — what YouTube's Opus arrives as — carries the
     * mime type `audio/webm`, identical to the Vorbis-in-WebM entry beside it.
     * Accurate about the bytes, and useless for telling the codec apart, which
     * is what [isM4a] is asked of the format for instead.
     */
    private val AudioStream.mime: String get() = format?.mimeType.orEmpty()

    /**
     * Whether these bytes are AAC in MP4, asked of the format rather than its
     * name.
     *
     * The name would in fact do here — `MediaFormat.M4A` reports `audio/mp4`,
     * the same thing the player endpoint calls it — but the enum is what
     * actually carries the answer, and the sibling case is a standing warning
     * against reading these mime types as codecs: the format that means Opus
     * says `audio/webm`, so a download demanding Opus by name concluded the
     * track hadn't any and fell through, while playback took the very same
     * stream and played it as Opus.
     */
    private val AudioStream.isM4a: Boolean
        get() = format == MediaFormat.M4A

    private val AudioStream.isWebmOpus: Boolean
        get() = format == MediaFormat.WEBMA_OPUS
}
