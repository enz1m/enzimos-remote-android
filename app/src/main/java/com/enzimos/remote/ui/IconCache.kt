package com.enzimos.remote.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App icons from computers, keyed by icon revision hash (content-addressed,
 * so computers share entries). Memory LRU over a small disk cache.
 */
class IconCache private constructor(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "app-icons").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun file(hash: String) = File(dir, "$hash.png")

    /** Bitmap if already decoded in memory. */
    fun cached(hash: String): Bitmap? = memory.get(hash)

    /** Whether the icon is available locally (memory or disk). */
    fun has(hash: String): Boolean = memory.get(hash) != null || file(hash).isFile

    suspend fun load(hash: String): Bitmap? = memory.get(hash) ?: withContext(Dispatchers.IO) {
        val path = file(hash)
        if (!path.isFile) return@withContext null
        BitmapFactory.decodeFile(path.path)?.also { memory.put(hash, it) }
    }

    /** Store a base64 PNG from `app_icons_result`. */
    suspend fun store(hash: String, base64Png: String): Boolean = withContext(Dispatchers.IO) {
        val bytes = try {
            Base64.decode(base64Png, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            return@withContext false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext false
        val target = file(hash)
        val temp = File(dir, "$hash.tmp")
        temp.writeBytes(bytes)
        temp.renameTo(target)
        memory.put(hash, bitmap)
        prune()
        true
    }

    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }

    companion object {
        private const val MAX_FILES = 3000

        @Volatile
        private var instance: IconCache? = null

        fun get(context: Context): IconCache =
            instance ?: synchronized(this) { instance ?: IconCache(context).also { instance = it } }
    }
}
