package com.music.bitchord.data.innertube

/**
 * The container a download destination will accept, when the failsafe is asked
 * for one. Null means any progressive audio will do — what playback wants.
 */
internal enum class FailsafeContainer {
    /** AAC in MP4: what exported downloads require. */
    M4A,

    /** Opus in WebM: what app-private downloads prefer. */
    WEBM_OPUS,
}

/** A stream the failsafe extractor found, in the shape the resolver keeps. */
internal class FailsafeStream(val url: String, val kbps: Int, val mimeType: String)

/**
 * The last-resort extractor: re-derives a stream URL from the watch page when
 * every player client has been turned away — it works when nothing else does,
 * at the cost of the request Google shapes hardest. Kept off the hot path and
 * reached only when InnerTubeX finds nothing.
 *
 * The JVM answer is NewPipe (see NewPipeFailsafe). iOS has no NewPipe — the
 * jar is JVM-only and the extractor's JS engine has no Native build — so
 * there the resolver walks InnerTubeX's catalog alone.
 */
internal interface FailsafeExtractor {
    /**
     * A stream for [videoId], or null when the failsafe found nothing in the
     * container [container] asked for.
     *
     * Highest rung at or under [maxKbps]; when everything is above it, the
     * cheapest available — a rung over budget still beats no audio at all.
     *
     * @throws Exception with a reason when the extraction itself failed, after
     *   the attempts the watch page is worth.
     */
    suspend fun stream(videoId: String, maxKbps: Int, container: FailsafeContainer?): FailsafeStream?

    /** YouTube's current player revision, or null when the player script cannot be parsed. */
    suspend fun signatureTimestamp(videoId: String): Int?
}

/** The platform's failsafe extractor, or null where there is none. */
internal expect fun platformFailsafeExtractor(): FailsafeExtractor?

/**
 * Whether [error] means a class or method this build was linked against is
 * missing at runtime — the JVM's `LinkageError`, which any third-party
 * extraction code can turn up. Native links statically and nothing there can
 * fail this way, so the iOS answer is always false.
 */
internal expect fun isLinkageFailure(error: Throwable): Boolean
