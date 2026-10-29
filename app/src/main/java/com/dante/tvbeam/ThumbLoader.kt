package com.dante.tvbeam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Small in-memory cache + loader for the preview images served by the phone. */
object ThumbLoader {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val gate = Semaphore(3)

    fun cached(url: String): Bitmap? = cache.get(url)

    suspend fun get(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    try {
                        c.connectTimeout = 4000
                        c.readTimeout = 20000
                        if (c.responseCode != 200) {
                            null
                        } else {
                            val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                            if (bmp != null) cache.put(url, bmp)
                            bmp
                        }
                    } finally {
                        c.disconnect()
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
}
