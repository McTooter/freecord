package com.music.bitchord.platform

/**
 * A private directory of small text entries, kept between runs.
 *
 * The shared code names entries and nothing else. What the bytes are stored
 * as — gzip on the JVM, the platform's own encoding on iOS — belongs to the
 * platform that owns the files, along with the question of when a directory
 * has grown past what it should be. Reads answer [read] with what [write]
 * stored under the same name, or null when there is none.
 */
expect class AppCacheDirectory(path: String) {
    /** The entry stored under [name], or null when there is none or it will not read. */
    fun read(name: String): String?

    /** Stores [text] under [name], replacing anything there; false when the directory refused it. */
    fun write(name: String, text: String): Boolean

    /** Marks [name] as just-used, so [trim] reaches it last. */
    fun touch(name: String)

    /** Deletes the oldest `.<extension>` entries until the rest fit [maxBytes]. */
    fun trim(extension: String, maxBytes: Long)
}
