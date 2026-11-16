package com.apusapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * High-performance Two-Level (Memory + Disk) PDF First-Page Thumbnail Cache.
 * Renders real page previews for Home Dashboard (like Google Drive & Adobe Acrobat).
 */
object ThumbnailManager {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val memoryCacheSize = maxMemory / 8 // 1/8th of heap memory

    private val memoryCache = object : LruCache<String, Bitmap>(memoryCacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private fun getDiskCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "pdf_thumbnails")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun generateKey(file: File): String {
        val input = "${file.absolutePath}_${file.lastModified()}"
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(input.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            "${file.name.hashCode()}_${file.lastModified()}"
        }
    }

    /**
     * Retrieves cached thumbnail from Memory -> Disk -> Renders from PDF file.
     */
    suspend fun getThumbnail(context: Context, file: File, targetWidth: Int = 180): Bitmap? = withContext(Dispatchers.IO) {
        if (!file.exists() || !file.canRead()) return@withContext null

        val key = generateKey(file)

        // 1. Check Memory Cache (0ms instant)
        memoryCache.get(key)?.let { return@withContext it }

        val diskCacheDir = getDiskCacheDir(context)
        val diskFile = File(diskCacheDir, "$key.jpg")

        // 2. Check Disk Cache
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                val diskBitmap = BitmapFactory.decodeFile(diskFile.absolutePath, opts)
                if (diskBitmap != null) {
                    memoryCache.put(key, diskBitmap)
                    return@withContext diskBitmap
                }
            } catch (_: Exception) {}
        }

        // 3. Render page 0 using Android native PdfRenderer
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) return@withContext null

            val page = renderer.openPage(0)
            try {
                val scale = targetWidth.toFloat() / page.width.toFloat()
                val targetHeight = (page.height * scale).toInt().coerceIn(120, 360)

                val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                // Save to Disk Cache
                try {
                    FileOutputStream(diskFile).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                    }
                } catch (_: Exception) {}

                // Put in Memory Cache
                memoryCache.put(key, bitmap)
                bitmap
            } finally {
                page.close()
            }
        } catch (_: Exception) {
            null
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    fun clearCache() {
        memoryCache.evictAll()
    }
}

