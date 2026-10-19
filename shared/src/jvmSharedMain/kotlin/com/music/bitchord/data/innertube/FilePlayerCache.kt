package com.music.bitchord.data.innertube

import java.io.File

/**
 * [InnerTubeXResolver.PlayerCache] over a directory — the app's files dir
 * with one subdirectory, which is what this cache was before that interface
 * existed.
 *
 * EJS preprocessed players, kept across processes. Solving a player from
 * scratch is the 8.7s step; a preprocessed one is reused per player version,
 * so only the first launch after YouTube rotates its player pays it.
 */
class FilePlayerCache(dir: File) : InnerTubeXResolver.PlayerCache {

    private val dir: File = dir.apply { mkdirs() }

    override fun read(key: String): String? =
        File(dir, key).takeIf { it.isFile }?.readText()

    override fun write(key: String, value: String?) {
        val file = File(dir, key)
        if (value == null) {
            file.delete()
            return
        }
        // Written aside and renamed, so a crash mid-write leaves the previous
        // player rather than a truncated one that would fail every solve.
        val tmp = File(dir, "$key.tmp")
        tmp.writeText(value)
        tmp.renameTo(file)
        // Players rotate every few days; only the newest are worth their megabytes.
        dir.listFiles()?.filter { !it.name.endsWith(".tmp") }?.sortedByDescending { it.lastModified() }
            ?.drop(KEPT_PLAYERS)?.forEach { it.delete() }
    }

    private companion object {
        const val KEPT_PLAYERS = 3
    }
}
