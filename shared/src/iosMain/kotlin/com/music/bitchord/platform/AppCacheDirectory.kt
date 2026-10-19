@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.music.bitchord.platform

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * The same directory of entries, on iOS: the platform's own text encoding
 * rather than gzip — the entries are kilobytes, and pulling a zlib binding
 * into the klib to say the same bytes out loud is not a trade this cache is
 * worth. Paths are private to this platform, so no reader of [read] on any
 * other one ever meets them.
 */
actual class AppCacheDirectory actual constructor(path: String) {
    private val directoryPath = path

    actual fun read(name: String): String? = runCatching {
        val data = NSData.dataWithContentsOfFile(directoryPath + "/" + name) ?: return@runCatching null
        val length = data.length.toInt()
        if (length == 0) return@runCatching ""
        val source = data.bytes ?: return@runCatching null
        val bytes = ByteArray(length)
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), source, length.toULong())
        }
        bytes.decodeToString()
    }.getOrNull()

    actual fun write(name: String, text: String): Boolean {
        if (!ensureDirectory()) return false
        val encoded = text.encodeToByteArray()
        val data = encoded.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), encoded.size.toULong())
        } ?: return false
        return data.writeToFile(directoryPath + "/" + name, atomically = true)
    }

    actual fun touch(name: String) {
        runCatching {
            NSFileManager.defaultManager.setAttributes(
                mapOf(NSFileModificationDate to NSDate()),
                ofItemAtPath = directoryPath + "/" + name,
                error = null,
            )
        }
    }

    actual fun trim(extension: String, maxBytes: Long) {
        val fileManager = NSFileManager.defaultManager
        val names = fileManager.contentsOfDirectoryAtPath(directoryPath, error = null)
            ?.filterIsInstance<String>()
            .orEmpty()
        data class Entry(val name: String, val bytes: Long, val modified: Double)
        val entries = names
            .filter { it.endsWith(".$extension") }
            .mapNotNull { name ->
                val path = directoryPath + "/" + name
                val attributes = fileManager.attributesOfItemAtPath(path, error = null)
                    ?: return@mapNotNull null
                val bytes = (attributes[NSFileSize] as? NSNumber)?.longValue
                    ?: return@mapNotNull null
                val modified = (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970
                    ?: 0.0
                Entry(name, bytes, modified)
            }
            .sortedByDescending { it.modified }
        var kept = 0L
        entries.forEach { entry ->
            kept += entry.bytes
            if (kept > maxBytes) {
                fileManager.removeItemAtPath(directoryPath + "/" + entry.name, error = null)
            }
        }
    }

    private fun ensureDirectory(): Boolean {
        val fileManager = NSFileManager.defaultManager
        if (fileManager.fileExistsAtPath(directoryPath)) return true
        return fileManager.createDirectoryAtPath(
            directoryPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }
}
