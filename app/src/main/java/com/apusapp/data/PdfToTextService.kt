package com.apusapp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.apusapp.utils.OcrHelper
import com.apusapp.utils.StorageLocationManager
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object PdfToTextService {

    /**
     * Extracts all text from a PDF document.
     * Uses vector PDF text stripping first; if scanned or image-based, automatically
     * falls back to Google ML Kit Optical Character Recognition (OCR) with Hindi & English support.
     * Saves the resulting text file (.txt) to Documents/Apus and returns the text content.
     */
    suspend fun extractAndSaveText(
        context: Context,
        pdfFile: File,
        password: String? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): Result<Pair<File, String>> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(0.05f, "Opening document...")

            val extractedStringBuilder = StringBuilder()
            var document: PDDocument? = null
            var totalPages = 0

            try {
                document = if (!password.isNullOrBlank()) {
                    PDDocument.load(pdfFile, password)
                } else {
                    PDDocument.load(pdfFile)
                }
                totalPages = document.numberOfPages

                onProgress(0.15f, "Scanning digital text...")
                val stripper = PDFTextStripper()
                for (page in 1..totalPages) {
                    val progress = 0.15f + (0.50f * (page.toFloat() / totalPages))
                    onProgress(progress, "Reading digital page $page of $totalPages...")
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = stripper.getText(document).trim()
                    if (pageText.isNotBlank()) {
                        extractedStringBuilder.append("--- Page $page ---\n\n")
                        extractedStringBuilder.append(pageText)
                        extractedStringBuilder.append("\n\n")
                    }
                }
            } catch (e: Exception) {
                // Ignore load error here and try native renderer OCR
            } finally {
                try { document?.close() } catch (_: Exception) {}
            }

            // If document was scanned or purely image-based (no digital text found), run ML Kit OCR
            val digitalText = extractedStringBuilder.toString().trim()
            if (digitalText.length < 30) {
                extractedStringBuilder.clear()
                onProgress(0.65f, "Image document detected. Starting ML Kit OCR...")
                var pfd: ParcelFileDescriptor? = null
                var renderer: PdfRenderer? = null
                try {
                    pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
                    renderer = PdfRenderer(pfd)
                    val ocrPages = renderer.pageCount
                    for (i in 0 until ocrPages) {
                        val progress = 0.65f + (0.30f * ((i + 1).toFloat() / ocrPages))
                        onProgress(progress, "AI OCR reading page ${i + 1} of $ocrPages...")
                        val page = renderer.openPage(i)
                        val tempImg = File(context.cacheDir, "ocr_txt_${System.currentTimeMillis()}_$i.png")
                        try {
                            // High-resolution bitmap for crisp OCR accuracy
                            val scale = 2.0f
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt(),
                                (page.height * scale).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            FileOutputStream(tempImg).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos) }
                            bitmap.recycle()

                            val ocrText = OcrHelper.extractTextFromImage(context, tempImg).getOrDefault("")
                            if (ocrText.isNotBlank()) {
                                extractedStringBuilder.append("--- Page ${i + 1} (OCR) ---\n\n")
                                extractedStringBuilder.append(ocrText.trim())
                                extractedStringBuilder.append("\n\n")
                            }
                        } finally {
                            page.close()
                            tempImg.delete()
                        }
                    }
                } finally {
                    try { renderer?.close() } catch (_: Exception) {}
                    try { pfd?.close() } catch (_: Exception) {}
                }
            }

            onProgress(0.96f, "Saving text document...")
            val finalContent = extractedStringBuilder.toString().trim()
            val textToSave = if (finalContent.isNotBlank()) finalContent else "No readable text detected in document."

            val baseName = pdfFile.nameWithoutExtension
            val outputFileName = "${baseName}_extracted_text_${System.currentTimeMillis()}.txt"

            // Save to Documents/Apus
            val tempTextFile = File(context.cacheDir, outputFileName)
            tempTextFile.writeText(textToSave, Charsets.UTF_8)

            val finalSavedFile = StorageLocationManager.savePdfToStorage(
                context = context,
                sourceFile = tempTextFile,
                outputFileName = outputFileName
            )

            onProgress(1.0f, "Completed!")
            Pair(finalSavedFile, textToSave)
        }
    }
}
