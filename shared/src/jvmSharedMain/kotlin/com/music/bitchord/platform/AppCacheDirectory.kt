package com.music.bitchord.platform

import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The storage translation's cache always had on the JVM — gzip files in a
 * directory — moved here whole so the shared side never sees a `File`.
 */
actual class AppCacheDirectory actual constructor(path: String) {
    private val directory = File(path)

    actual fun read(name: String): String? {
        val file = File(directory, name)
        if (!file.isFile) return null
        return runCatching {
            GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    actual fun write(name: String, text: String): Boolean {
        if (!directory.exists() && !directory.mkdirs()) return false
        val destination = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        return runCatching {
            GZIPOutputStream(temporary.outputStream()).bufferedWriter().use { it.write(text) }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            true
        }.getOrElse {
            temporary.delete()
            false
        }
    }

    actual fun touch(name: String) {
        runCatching { File(directory, name).setLastModified(System.currentTimeMillis()) }
    }

    actual fun trim(extension: String, maxBytes: Long) {
        val files = directory.listFiles { file -> file.extension == extension }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var kept = 0L
        files.forEach { file ->
            kept += file.length()
            if (kept > maxBytes) file.delete()
        }
    }
}
