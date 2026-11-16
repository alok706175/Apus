package com.apusapp.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class HomeSubSection(val title: String) {
    RECENT("Recent"),
    ALL_FILES("All Files")
}

enum class PdfListFilter(val title: String) {
    ALL("All"),
    STARRED("Starred ⭐"),
    SCANNED("Scanned"),
    MERGED("Merged"),
    COMPRESSED("Compressed"),
    PROTECTED("Protected")
}

enum class PdfSortOption(val title: String) {
    DATE_DESC("Newest First"),
    DATE_ASC("Oldest First"),
    NAME_ASC("Name (A to Z)"),
    NAME_DESC("Name (Z to A)"),
    SIZE_DESC("Largest First"),
    SIZE_ASC("Smallest First")
}

data class PdfFileItem(
    val name: String,
    val path: String,
    val size: Long,
    val dateModified: Long, // in seconds
    val uri: Uri,
    val pageCount: Int? = null
)

data class PdfSearchMatch(
    val pageIndex: Int,
    val matchIndexOnPage: Int,
    val totalMatchesOnPage: Int,
)

data class PdfOutlineItem(
    val title: String,
    val pageIndex: Int,
    val depth: Int = 0
)

data class PdfMetadata(
    val title: String = "",
    val author: String = "",
    val subject: String = "",
    val keywords: String = "",
    val creator: String = "",
    val producer: String = "",
    val creationDate: String = "",
    val modificationDate: String = "",
    val pageCount: Int = 0,
    val pdfVersion: String = "",
    val isEncrypted: Boolean = false,
    val fileSizeFormatted: String = ""
)

object PdfHelper {

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.2f MB", mb)
        } else {
            String.format(Locale.US, "%.1f KB", kb)
        }
    }

    suspend fun getPdfMetadata(file: File): PdfMetadata = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext PdfMetadata()
        var doc: PDDocument? = null
        try {
            doc = PDDocument.load(file)
            val info = doc.documentInformation
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            val createStr = info?.creationDate?.time?.let { sdf.format(it) } ?: "Unknown"
            val modStr = info?.modificationDate?.time?.let { sdf.format(it) } ?: "Unknown"
            val version = try { "PDF ${doc.version}" } catch (_: Exception) { "PDF 1.7" }

            PdfMetadata(
                title = info?.title.orEmpty(),
                author = info?.author.orEmpty(),
                subject = info?.subject.orEmpty(),
                keywords = info?.keywords.orEmpty(),
                creator = info?.creator.orEmpty(),
                producer = info?.producer.orEmpty(),
                creationDate = createStr,
                modificationDate = modStr,
                pageCount = doc.numberOfPages,
                pdfVersion = version,
                isEncrypted = doc.isEncrypted,
                fileSizeFormatted = formatFileSize(file.length())
            )
        } catch (e: Exception) {
            PdfMetadata(
                pageCount = 0,
                isEncrypted = e.message?.contains("encrypted", ignoreCase = true) == true,
                fileSizeFormatted = formatFileSize(file.length())
            )
        } finally {
            try { doc?.close() } catch (_: Exception) {}
        }
    }

    suspend fun updatePdfMetadata(
        file: File,
        title: String,
        author: String,
        subject: String,
        keywords: String
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.exists()) { "File does not exist: ${file.absolutePath}" }
            val doc = PDDocument.load(file)
            try {
                var info = doc.documentInformation
                if (info == null) {
                    info = PDDocumentInformation()
                    doc.documentInformation = info
                }
                info.title = title
                info.author = author
                info.subject = subject
                info.keywords = keywords
                info.modificationDate = Calendar.getInstance()

                val tempFile = File(file.parentFile ?: file, "meta_${System.currentTimeMillis()}_${file.name}")
                doc.save(tempFile)
                doc.close()

                if (tempFile.exists() && tempFile.length() > 0) {
                    file.delete()
                    tempFile.renameTo(file)
                }
                file
            } finally {
                try { doc.close() } catch (_: Exception) {}
            }
        }
    }

    suspend fun createSamplePdf(
        context: Context,
        fileName: String = "sample_generated.pdf",
        title: String = "PDF Utility App - Generated Document",
        author: String = "PDFUtilityApp User",
        bodyText: List<String> = listOf(
            "Welcome to PDFUtilityApp!",
            "This document was created directly on Android using PDFBox-Android.",
            "Features included in this template:",
            "  • Material 3 UI Design with Jetpack Compose",
            "  • Coil image loading integration",
            "  • Java 17 Compatibility & Gradle Kotlin DSL",
            "  • High-performance PDF generation & manipulation"
        )
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val outputDir = context.getExternalFilesDir(null) ?: context.filesDir
            val pdfFile = File(outputDir, fileName)

            val document = PDDocument()
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)

            val info = PDDocumentInformation().apply {
                this.title = title
                this.author = author
                this.creator = "PDFUtilityApp"
                this.creationDate = Calendar.getInstance()
            }
            document.documentInformation = info

            PDPageContentStream(document, page).use { contentStream ->
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA_BOLD, 18f)
                contentStream.newLineAtOffset(50f, 750f)
                contentStream.showText(title)
                contentStream.endText()

                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA_OBLIQUE, 12f)
                contentStream.newLineAtOffset(50f, 725f)
                contentStream.showText("Author: $author | Date: ${Calendar.getInstance().time}")
                contentStream.endText()

                contentStream.setLineWidth(1.5f)
                contentStream.moveTo(50f, 710f)
                contentStream.lineTo(545f, 710f)
                contentStream.stroke()

                var yOffset = 680f
                contentStream.setFont(PDType1Font.HELVETICA, 12f)
                for (line in bodyText) {
                    contentStream.beginText()
                    contentStream.newLineAtOffset(50f, yOffset)
                    contentStream.showText(line)
                    contentStream.endText()
                    yOffset -= 24f
                }
            }

            document.save(pdfFile)
            document.close()

            pdfFile
        }
    }

    // Cache to prevent continuous disk churn
    private var cachedFiles: List<PdfFileItem>? = null
    private var lastScanTimeMs: Long = 0L
    private const val CACHE_EXPIRY_MS = 10_000L // 10 seconds cache unless force refreshed

    fun invalidateCache() {
        cachedFiles = null
        lastScanTimeMs = 0L
    }

    fun removeFileFromCache(file: File) {
        val absPath = file.absolutePath
        val canPath = try { file.canonicalPath } catch (_: Exception) { absPath }
        cachedFiles = cachedFiles?.filter { it.path != absPath && it.path != canPath }
    }

    /**
     * High-speed storage scanner combining MediaStore queries and targeted directory discovery.
     */
    suspend fun fileContainsQuery(file: File, query: String): Boolean = withContext(Dispatchers.IO) {
        if (query.isBlank() || !file.exists() || file.length() > 30 * 1024 * 1024) return@withContext false
        runCatching {
            PDDocument.load(file).use { document ->
                val stripper = PDFTextStripper()
                val checkPages = document.numberOfPages.coerceAtMost(20)
                for (i in 1..checkPages) {
                    stripper.startPage = i
                    stripper.endPage = i
                    val text = stripper.getText(document)
                    if (text.contains(query, ignoreCase = true)) {
                        return@withContext true
                    }
                }
            }
        }
        false
    }

    suspend fun searchPdfFiles(context: Context, query: String = "", forceRefresh: Boolean = false): List<PdfFileItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && query.isBlank() && cachedFiles != null && (now - lastScanTimeMs) < CACHE_EXPIRY_MS) {
            return@withContext cachedFiles!!
        }

        val uniqueMap = LinkedHashMap<String, PdfFileItem>(128)

        // 1. High-speed MediaStore Query
        try {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )

            val selection = "(${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.pdf' OR ${MediaStore.Files.FileColumns.DATA} LIKE '%.pdf')"
            val selectionArgs = arrayOf("application/pdf")
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                val sizeCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    if (path.isNullOrBlank()) continue

                    val id = if (idCol >= 0) cursor.getLong(idCol) else -1
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    val date = if (dateCol >= 0) cursor.getLong(dateCol) else (System.currentTimeMillis() / 1000)

                    val contentUri = if (id != -1L) {
                        ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                    } else {
                        Uri.fromFile(File(path))
                    }

                    val cleanName = name ?: path.substringAfterLast(File.separatorChar)
                    uniqueMap[path] = PdfFileItem(
                        name = cleanName,
                        path = path,
                        size = size,
                        dateModified = date,
                        uri = contentUri
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Targeted directory discovery for app & standard public locations
        val searchDirs = ArrayList<File>(8)
        try {
            searchDirs.add(StorageLocationManager.getTargetDirectory(context))
            searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
            searchDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS))
            searchDirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ApusPDF"))
            searchDirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Apus"))
            searchDirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "ApusPDF"))
            context.getExternalFilesDir(null)?.let { searchDirs.add(it) }
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.let { searchDirs.add(it) }
            searchDirs.add(context.filesDir)

            // Direct external root scan only if permission is granted and MediaStore is sparse
            if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) && uniqueMap.size < 5) {
                val root = Environment.getExternalStorageDirectory()
                if (root != null && root.exists()) {
                    searchDirs.add(root)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        for (dir in searchDirs) {
            if (dir.exists() && dir.isDirectory) {
                scanDirectoryRecursively(dir, uniqueMap, currentDepth = 0, maxDepth = 2)
            }
        }

        val allItems = uniqueMap.values.toList()
        if (query.isBlank()) {
            cachedFiles = allItems
            lastScanTimeMs = System.currentTimeMillis()
            allItems
        } else {
            val q = query.trim()
            val nameMatches = allItems.filter { it.name.contains(q, ignoreCase = true) }
            if (nameMatches.size >= 10 || q.length < 3) {
                nameMatches
            } else {
                // If few filename matches, check content of first 25 documents
                val nameMatchPaths = nameMatches.map { it.path }.toSet()
                val contentMatches = allItems
                    .filter { it.path !in nameMatchPaths }
                    .take(25)
                    .filter { fileContainsQuery(File(it.path), q) }
                nameMatches + contentMatches
            }
        }
    }

    private fun scanDirectoryRecursively(
        dir: File,
        map: MutableMap<String, PdfFileItem>,
        currentDepth: Int = 0,
        maxDepth: Int = 2
    ) {
        if (currentDepth > maxDepth) return
        val files = dir.listFiles() ?: return
        for (f in files) {
            try {
                val name = f.name
                // Skip hidden folders, Android system dirs, cache, and secure vault
                if (name.startsWith(".") || name.equals("Android", ignoreCase = true) || name.equals("cache", ignoreCase = true) || name.equals("secure_vault", ignoreCase = true) || name.startsWith("vault_preview_", ignoreCase = true)) {
                    continue
                }
                if (f.isDirectory) {
                    scanDirectoryRecursively(f, map, currentDepth + 1, maxDepth)
                } else if (f.isFile && name.endsWith(".pdf", ignoreCase = true) && !name.startsWith("vault_preview_", ignoreCase = true)) {
                    val path = f.path
                    if (!map.containsKey(path)) {
                        val size = f.length()
                        if (size > 0) {
                            map[path] = PdfFileItem(
                                name = name,
                                path = path,
                                size = size,
                                dateModified = f.lastModified() / 1000,
                                uri = Uri.fromFile(f)
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Safely renames a PDF file in storage and updates its file reference.
     */
    suspend fun renamePdf(file: File, newDisplayName: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext Result.failure(Exception("File does not exist"))

            val cleanName = if (newDisplayName.endsWith(".pdf", ignoreCase = true)) newDisplayName else "$newDisplayName.pdf"
            val targetFile = File(file.parentFile, cleanName)

            if (targetFile.exists() && targetFile.canonicalPath != file.canonicalPath) {
                return@withContext Result.failure(Exception("A file with that name already exists."))
            }

            val success = file.renameTo(targetFile)
            if (success) {
                invalidateCache()
                Result.success(targetFile)
            } else {
                Result.failure(Exception("Unable to rename file."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Safely deletes a PDF file from storage.
     */
    suspend fun deletePdf(context: Context, file: File): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) {
                removeFileFromCache(file)
                return@withContext Result.success(true)
            }

            // Try MediaStore delete first
            try {
                val uri = MediaStore.Files.getContentUri("external")
                context.contentResolver.delete(
                    uri,
                    "${MediaStore.Files.FileColumns.DATA} = ?",
                    arrayOf(file.canonicalPath)
                )
            } catch (_: Exception) {}

            val deleted = file.delete()
            if (deleted || !file.exists()) {
                removeFileFromCache(file)
                Result.success(true)
            } else {
                Result.failure(Exception("Could not delete file from device."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reads page count from PDF document.
     */
    suspend fun getPdfPageCount(file: File): Int? = withContext(Dispatchers.IO) {
        try {
            PDDocument.load(file).use { doc ->
                doc.numberOfPages
            }
        } catch (_: Exception) {
            null
        }
    }

    fun formatRelativeDate(timestampSec: Long): String {
        val millis = timestampSec * 1000
        val calFile = Calendar.getInstance().apply { timeInMillis = millis }
        val calNow = Calendar.getInstance()

        val isSameDay = calFile.get(Calendar.YEAR) == calNow.get(Calendar.YEAR) &&
                calFile.get(Calendar.DAY_OF_YEAR) == calNow.get(Calendar.DAY_OF_YEAR)
        if (isSameDay) {
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            return "Today, ${timeFormat.format(Date(millis))}"
        }

        val calYesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = calFile.get(Calendar.YEAR) == calYesterday.get(Calendar.YEAR) &&
                calFile.get(Calendar.DAY_OF_YEAR) == calYesterday.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) {
            val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
            return "Yesterday, ${timeFormat.format(Date(millis))}"
        }

        val isSameYear = calFile.get(Calendar.YEAR) == calNow.get(Calendar.YEAR)
        val dateFormat = if (isSameYear) {
            SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
        } else {
            SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        }
        return dateFormat.format(Date(millis))
    }

    suspend fun searchTextInPdf(file: File, query: String): List<Int> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val matchingPages = mutableListOf<Int>()
        runCatching {
            PDDocument.load(file).use { document ->
                val stripper = PDFTextStripper()
                for (i in 1..document.numberOfPages) {
                    stripper.startPage = i
                    stripper.endPage = i
                    val text = stripper.getText(document)
                    if (text.contains(query, ignoreCase = true)) {
                        matchingPages.add(i - 1)
                    }
                }
            }
        }.onFailure { it.printStackTrace() }
        matchingPages
    }

    suspend fun searchDetailedMatchesInPdf(file: File, query: String): List<PdfSearchMatch> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val matches = mutableListOf<PdfSearchMatch>()
        runCatching {
            PDDocument.load(file).use { document ->
                val stripper = PDFTextStripper()
                for (i in 1..document.numberOfPages) {
                    stripper.startPage = i
                    stripper.endPage = i
                    val text = stripper.getText(document)
                    if (text.contains(query, ignoreCase = true)) {
                        var idx = 0
                        val indices = mutableListOf<Int>()
                        while (true) {
                            val found = text.indexOf(query, startIndex = idx, ignoreCase = true)
                            if (found == -1) break
                            indices.add(found)
                            idx = found + query.length.coerceAtLeast(1)
                        }
                        val totalOnPage = indices.size
                        for (m in 0 until totalOnPage) {
                            matches.add(PdfSearchMatch(pageIndex = i - 1, matchIndexOnPage = m, totalMatchesOnPage = totalOnPage))
                        }
                    }
                }
            }
        }.onFailure { it.printStackTrace() }
        matches
    }

    suspend fun extractPageText(file: File, pageNumber1Based: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            PDDocument.load(file).use { document ->
                if (pageNumber1Based in 1..document.numberOfPages) {
                    val stripper = PDFTextStripper()
                    stripper.startPage = pageNumber1Based
                    stripper.endPage = pageNumber1Based
                    stripper.getText(document).trim()
                } else ""
            }
        }.getOrDefault("")
    }

    suspend fun addWatermarkToPdf(context: Context, inputFile: File, watermarkText: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = PDDocument.load(inputFile)
            val total = doc.numberOfPages
            for (i in 0 until total) {
                val page = doc.getPage(i)
                val width = page.mediaBox.width
                val height = page.mediaBox.height

                // Render watermark using native Canvas & Paint (supports Hindi, Devanagari, Unicode, Emojis)
                val bmpW = (width * 1.5f).toInt().coerceAtLeast(400)
                val bmpH = (height * 1.5f).toInt().coerceAtLeast(400)
                val wmBitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(wmBitmap)

                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(75, 140, 140, 140)
                    textSize = (bmpW * 0.08f).coerceIn(36f, 96f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textAlign = android.graphics.Paint.Align.CENTER
                }

                canvas.save()
                // Rotate 45 degrees diagonally across center
                canvas.rotate(-45f, bmpW / 2f, bmpH / 2f)
                canvas.drawText(watermarkText, bmpW / 2f, bmpH / 2f, paint)
                canvas.restore()

                val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, wmBitmap)
                val contentStream = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                contentStream.drawImage(pdImage, 0f, 0f, width, height)
                contentStream.close()
                wmBitmap.recycle()
            }
            val outName = "${inputFile.nameWithoutExtension}_watermarked.pdf"
            val temp = File(context.cacheDir, "wm_${System.currentTimeMillis()}.pdf")
            doc.save(temp)
            doc.close()
            StorageLocationManager.savePdfToStorage(context, temp, outName).also { temp.delete() }
        }
    }

    suspend fun addImageWatermarkToPdf(
        context: Context,
        inputFile: File,
        imageBitmap: Bitmap,
        opacity: Float = 0.35f,
        scaleRatio: Float = 0.45f
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = PDDocument.load(inputFile)
            val total = doc.numberOfPages

            val alphaInt = (opacity.coerceIn(0.05f, 1.0f) * 255).toInt()
            val preparedBmp = Bitmap.createBitmap(imageBitmap.width, imageBitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(preparedBmp)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                alpha = alphaInt
            }
            canvas.drawBitmap(imageBitmap, 0f, 0f, paint)

            val pdImage = com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(doc, preparedBmp)
            preparedBmp.recycle()

            for (i in 0 until total) {
                val page = doc.getPage(i)
                val width = page.mediaBox.width
                val height = page.mediaBox.height

                val targetW = width * scaleRatio.coerceIn(0.1f, 0.9f)
                val targetH = targetW * (imageBitmap.height.toFloat() / imageBitmap.width.toFloat())
                val posX = (width - targetW) / 2f
                val posY = (height - targetH) / 2f

                val contentStream = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                contentStream.drawImage(pdImage, posX, posY, targetW, targetH)
                contentStream.close()
            }

            val outName = "${inputFile.nameWithoutExtension}_watermarked.pdf"
            val temp = File(context.cacheDir, "wm_img_${System.currentTimeMillis()}.pdf")
            doc.save(temp)
            doc.close()
            StorageLocationManager.savePdfToStorage(context, temp, outName).also { temp.delete() }
        }
    }

    suspend fun addImageWatermarkToPdf(
        context: Context,
        inputFile: File,
        imageUri: Uri,
        opacity: Float = 0.35f
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val inputStream = context.contentResolver.openInputStream(imageUri)
                ?: throw IllegalArgumentException("Could not open image file")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap == null) throw IllegalArgumentException("Invalid or unsupported image file")
            try {
                addImageWatermarkToPdf(context, inputFile, bitmap, opacity).getOrThrow()
            } finally {
                bitmap.recycle()
            }
        }
    }

    suspend fun addPageNumbersToPdf(context: Context, inputFile: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val doc = PDDocument.load(inputFile)
            val total = doc.numberOfPages
            for (i in 0 until total) {
                val page = doc.getPage(i)
                val width = page.mediaBox.width
                val contentStream = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)
                contentStream.beginText()
                contentStream.setFont(PDType1Font.HELVETICA, 11f)
                contentStream.setNonStrokingColor(100, 100, 100)
                val pageText = "Page ${i + 1} of $total"
                contentStream.newLineAtOffset(width / 2f - 30f, 25f)
                contentStream.showText(pageText)
                contentStream.endText()
                contentStream.close()
            }
            val outName = "${inputFile.nameWithoutExtension}_numbered.pdf"
            val temp = File(context.cacheDir, "num_${System.currentTimeMillis()}.pdf")
            doc.save(temp)
            doc.close()
            StorageLocationManager.savePdfToStorage(context, temp, outName).also { temp.delete() }
        }
    }

    private fun parseOutlineNode(
        doc: PDDocument,
        node: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem,
        depth: Int,
        outList: MutableList<PdfOutlineItem>
    ) {
        val destPage = try { node.findDestinationPage(doc) } catch (_: Exception) { null }
        val pIndex = if (destPage != null) {
            val idx = doc.pages.indexOf(destPage)
            if (idx >= 0) idx else 0
        } else 0
        outList.add(PdfOutlineItem(title = node.title ?: "Untitled Chapter", pageIndex = pIndex, depth = depth))

        var child = node.firstChild
        while (child != null) {
            parseOutlineNode(doc, child, depth + 1, outList)
            child = child.nextSibling
        }
    }

    suspend fun extractPdfOutlines(file: File): List<PdfOutlineItem> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            PDDocument.load(file).use { doc ->
                val outline = doc.documentCatalog.documentOutline ?: return@use emptyList()
                val list = mutableListOf<PdfOutlineItem>()
                var current = outline.firstChild
                while (current != null) {
                    parseOutlineNode(doc, current, 0, list)
                    current = current.nextSibling
                }
                list
            }
        }.getOrDefault(emptyList())
    }

    fun getPdfFileItem(file: File): PdfFileItem? {
        if (!file.exists()) return null
        return PdfFileItem(
            name = file.name,
            path = file.absolutePath,
            size = file.length(),
            dateModified = file.lastModified() / 1000,
            uri = Uri.fromFile(file),
        )
    }
}
