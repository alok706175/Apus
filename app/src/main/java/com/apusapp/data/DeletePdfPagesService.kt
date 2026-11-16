package com.apusapp.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.apusapp.data.model.DeletePageItem
import com.apusapp.data.model.DeletePagesResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.roundToInt

class DeletePdfPagesService(private val context: Context) {

    /**
     * Inspects a PDF and returns the total page count.
     */
    suspend fun inspectPdf(
        file: File,
        password: String? = null
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val doc = if (password.isNullOrEmpty()) {
                PDDocument.load(file)
            } else {
                PDDocument.load(file, password)
            }
            val pageCount = doc.numberOfPages
            doc.close()
            Result.success(pageCount)
        } catch (e: InvalidPasswordException) {
            Result.failure(SecurityException("Password required or incorrect password."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Renders a crisp thumbnail preview for a single page.
     */
    suspend fun renderThumbnail(
        file: File,
        pageIndex: Int,
        targetWidth: Int = 300
    ): Bitmap? = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            page = renderer.openPage(pageIndex)
            val aspectRatio = page.height.toFloat() / page.width.toFloat()
            val targetHeight = (targetWidth * aspectRatio).roundToInt().coerceAtLeast(100)

            val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (_: Exception) {
            null
        } finally {
            try { page?.close() } catch (_: Exception) {}
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Deletes specified pages from the source PDF and saves the modified PDF document.
     */
    suspend fun deletePagesAndSave(
        sourceFile: File,
        password: String? = null,
        deletedPageIndices: Set<Int>,
        customOutputName: String? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<DeletePagesResult> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        var sourceDoc: PDDocument? = null
        var newDoc: PDDocument? = null

        try {
            onProgress(0.1f, "Opening document...")
            sourceDoc = if (password.isNullOrEmpty()) {
                PDDocument.load(sourceFile)
            } else {
                PDDocument.load(sourceFile, password)
            }

            val totalInitialPages = sourceDoc.numberOfPages
            val remainingPagesCount = totalInitialPages - deletedPageIndices.size

            if (remainingPagesCount <= 0) {
                return@withContext Result.failure(
                    IllegalArgumentException("Cannot delete all pages. At least one page must remain in the document.")
                )
            }

            if (deletedPageIndices.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("No pages selected for deletion.")
                )
            }

            onProgress(0.3f, "Removing selected pages while preserving metadata...")
            // Delete pages directly from sourceDoc in descending order to avoid index shift,
            // preserving document metadata, bookmarks/outlines, and AcroForms intact.
            val sortedDescending = deletedPageIndices.filter { it in 0 until totalInitialPages }.sortedDescending()
            var deletedCount = 0
            for (pageIdx in sortedDescending) {
                sourceDoc.removePage(pageIdx)
                deletedCount++
                val prog = 0.3f + 0.4f * (deletedCount.toFloat() / sortedDescending.size)
                onProgress(prog, "Removed page ${pageIdx + 1} ($deletedCount of ${sortedDescending.size})...")
            }

            onProgress(0.75f, "Writing updated PDF document...")
            val baseName = sourceFile.nameWithoutExtension
            val outputFileName = if (!customOutputName.isNullOrBlank()) {
                if (customOutputName.endsWith(".pdf", ignoreCase = true)) customOutputName else "$customOutputName.pdf"
            } else {
                "${baseName}_pages_removed.pdf"
            }

            val tempFile = File(context.cacheDir, "del_${System.currentTimeMillis()}_$outputFileName")
            FileOutputStream(tempFile).use { fos ->
                sourceDoc.save(fos)
            }

            onProgress(0.9f, "Saving to storage...")
            val finalOutputFile = saveToUserStorage(tempFile, outputFileName)

            onProgress(1.0f, "Completed!")
            val result = DeletePagesResult(
                originalFile = sourceFile,
                outputFile = finalOutputFile,
                initialPageCount = totalInitialPages,
                deletedPageCount = deletedPageIndices.size,
                remainingPageCount = remainingPagesCount,
                originalSizeBytes = sourceFile.length(),
                outputSizeBytes = finalOutputFile.length(),
                processingTimeMs = System.currentTimeMillis() - startTime
            )

            Result.success(result)
        } catch (e: InvalidPasswordException) {
            Result.failure(SecurityException("Password required or incorrect password."))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { newDoc?.close() } catch (_: Exception) {}
            try { sourceDoc?.close() } catch (_: Exception) {}
        }
    }

    private fun saveToUserStorage(sourceFile: File, outputFileName: String): File {
        return com.apusapp.utils.StorageLocationManager.savePdfToStorage(context, sourceFile, outputFileName)
    }
}
