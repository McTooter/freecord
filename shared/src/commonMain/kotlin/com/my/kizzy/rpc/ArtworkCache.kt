package com.my.kizzy.rpc

import com.music.bitchord.platform.AppConcurrentMap

internal object ArtworkCache {
    private val cache = AppConcurrentMap<String, String>()

    suspend fun getOrFetch(key: String, fetch: suspend () -> String?): String? {
        return cache[key] ?: fetch()?.also { cache[key] = it }
    }
}
