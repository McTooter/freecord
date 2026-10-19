package com.my.kizzy.gateway

/**
 * Android's own clock where there is one, found reflectively because this
 * source set is shared with the desktop and cannot import it; the desktop
 * answers with `System.nanoTime`, which is monotonic there.
 */
internal actual fun elapsedRealtimeMs(): Long =
    androidElapsedRealtime?.let { runCatching { it.invoke(null) as Long }.getOrNull() }
        ?: (System.nanoTime() / 1_000_000L)

private val androidElapsedRealtime: java.lang.reflect.Method? = runCatching {
    Class.forName("android.os.SystemClock").getMethod("elapsedRealtime")
}.getOrNull()
