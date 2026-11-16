package com.apusapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.apusapp.utils.StorageLocationManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object GrayscalePdfService {

    /**
     * Converts a full-color PDF into a Print-Friendly Grayscale / High-Contrast B&W document.
     * Dramatically reduces printer ink consumption and improves photocopy readability.
     */
    suspend fun convertToGrayscale(
        context: Context,
        inputFile: File,
        highContrast: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(0.05f)

            var pfd: ParcelFileDescriptor? = null
            var renderer: PdfRenderer? = null
            val targetDoc = PDDocument()

            try {
                pfd = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                val totalPages = renderer.pageCount
                require(totalPages > 0) { "PDF has no pages to convert." }

                // ColorMatrix for pure grayscale conversion
                val colorMatrix = ColorMatrix().apply {
                    setSaturation(0f)
                    if (highContrast) {
                        // High-contrast threshold matrix for sharp photocopy / ink saver
                        val contrast = 1.3f
                        val scale = contrast
                        val translate = (-0.5f * contrast + 0.5f) * 255f
                        val array = floatArrayOf(
                            scale, 0f, 0f, 0f, translate,
                            0f, scale, 0f, 0f, translate,
                            0f, 0f, scale, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                        postConcat(ColorMatrix(array))
                    }
                }
                val paint = Paint().apply {
                    colorFilter = ColorMatrixColorFilter(colorMatrix)
                }

                for (i in 0 until totalPages) {
                    val progress = 0.1f + (0.8f * (i.toFloat() / totalPages))
                    onProgress(progress)

                    val page = renderer.openPage(i)
                    try {
                        val renderScale = 2.0f
                        val w = (page.width * renderScale).toInt()
                        val h = (page.height * renderScale).toInt()

                        val colorBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        colorBitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(colorBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        val grayBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
                        val canvas = Canvas(grayBitmap)
                        canvas.drawBitmap(colorBitmap, 0f, 0f, paint)
                        colorBitmap.recycle()

                        val pdImage = JPEGFactory.createFromImage(targetDoc, grayBitmap, 0.85f)
                        val pdPage = PDPage(PDRectangle(page.width.toFloat(), page.height.toFloat()))
                        targetDoc.addPage(pdPage)

                        PDPageContentStream(targetDoc, pdPage).use { stream ->
                            stream.drawImage(pdImage, 0f, 0f, page.width.toFloat(), page.height.toFloat())
                        }
                        grayBitmap.recycle()
                    } finally {
                        page.close()
                    }
                }

                onProgress(0.92f)

                val tempFile = File(context.cacheDir, "gray_${System.currentTimeMillis()}.pdf")
                FileOutputStream(tempFile).use { fos ->
                    targetDoc.save(fos)
                }

                val outName = "${inputFile.nameWithoutExtension}_bw.pdf"
                val savedFile = StorageLocationManager.savePdfToStorage(context, tempFile, outName)
                tempFile.delete()

                onProgress(1.0f)
                savedFile
            } finally {
                try { targetDoc.close() } catch (_: Exception) {}
                try { renderer?.close() } catch (_: Exception) {}
                try { pfd?.close() } catch (_: Exception) {}
            }
        }
    }
}

