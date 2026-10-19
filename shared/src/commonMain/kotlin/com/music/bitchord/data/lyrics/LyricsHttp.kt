package com.music.bitchord.data.lyrics

import com.music.bitchord.platform.platformHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * Shared plumbing for the lyric providers.
 *
 * They are all raced against each other by [LyricsRepository], so a provider
 * that hangs holds up the whole lookup. [LYRICS_TIMEOUT_SECONDS] is
 * deliberately far shorter than the stream-oriented timeouts elsewhere: a
 * lyric that arrives after the second chorus is of no use to anyone, and the
 * fallbacks behind it are the better answer.
 *
 * The two clients stand in for the two callTimeouts the okhttp version of
 * this file set - one deadline for the ordinary providers, a longer one for
 * the authenticated routes that have to ask an upstream catalogue first.
 */
private const val LYRICS_TIMEOUT_SECONDS = 6L

internal const val LYRICS_AGENT = "BitChord (https://github.com/bitchord)"

val lyricsJson = Json { ignoreUnknownKeys = true; isLenient = true }

private val client by lazy {
    platformHttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = LYRICS_TIMEOUT_SECONDS * 1000
            connectTimeoutMillis = 3_000
        }
    }
}

// PaxSenix's authenticated routes often have to query an upstream catalogue
// before answering. Keep the fast deadline for the ordinary providers, but
// match PaxSenix's own client timeout here so valid requests are not
// discarded while its backend is still resolving a track.
private val authenticatedClient by lazy {
    platformHttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
        }
    }
}

/** Body of a successful GET, or null for any failure at all. */
internal suspend fun lyricsGet(url: String): String? = fetch(client, url) { headers ->
    headers["Accept"] = "application/json"
}

/** Body of an authenticated provider GET without service-specific browser headers. */
internal suspend fun lyricsGetBearer(url: String, bearer: String): String? {
    if (bearer.isBlank()) return null
    return fetch(authenticatedClient, url) { headers ->
        headers["Accept"] = "application/json, text/plain, */*"
        headers["Authorization"] = "Bearer $bearer"
    }
}

/**
 * [lyricsGet], with a bearer token and the headers Apple's own web player
 * sends alongside one - `amp-api.music.apple.com` answers a token with no
 * `Origin` at all the same way it answers a wrong one, with a 403.
 */
internal suspend fun lyricsGetAuthorized(url: String, bearer: String): String? = fetch(client, url) { headers ->
    headers["Accept"] = "application/json"
    headers["Authorization"] = "Bearer $bearer"
    headers["Origin"] = "https://music.apple.com"
    headers["Referer"] = "https://music.apple.com/"
}

private suspend fun fetch(
    client: HttpClient,
    url: String,
    more: (MutableMap<String, String>) -> Unit,
): String? = runCatching {
    val extra = mutableMapOf<String, String>()
    more(extra)
    val response = client.get(url) {
        header("User-Agent", LYRICS_AGENT)
        for ((name, value) in extra) header(name, value)
    }
    if (response.status.isSuccess()) response.bodyAsText() else null
}.getOrNull()
