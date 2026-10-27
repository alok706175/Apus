package com.swiftapp.data.model

import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.util.UUID

enum class CompressionPreset(
    val title: String,
    val subtitle: String,
    val estimatedReduction: String,
    val scaleFactor: Float,
    val quality: Float,
) {
    EXTREME(
        title = "High Compression (Low Quality)",
        subtitle = "Maximum file size reduction with noticeable loss in image and text quality.",
        estimatedReduction = "",
        scaleFactor = 0.8f,
        quality = 0.35f,
    ),
    BALANCED(
        title = "Mid Compression (Moderate Quality)",
        subtitle = "A great balance that slightly reduces quality to significantly lower the file size.",
        estimatedReduction = "",
        scaleFactor = 1.2f,
        quality = 0.60f,
    ),
    HIGH_QUALITY(
        title = "Low Compression (High Quality)",
        subtitle = "It reduces the document size by up to 25% while keeping the images and text within it clear and sharp.",
        estimatedReduction = "",
        scaleFactor = 1.8f,
        quality = 0.85f,
    ),
    CUSTOM_TARGET(
        title = "Custom Target Size",
        subtitle = "Choose Preferred Size of Document",
        estimatedReduction = "",
        scaleFactor = 1.0f,
        quality = 0.50f,
    ),
}

data class CompressPdfItem(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val fileName: String,
    val fileSizeBytes: Long,
    val pageCount: Int,
    val thumbnail: Bitmap? = null,
    val isEncrypted: Boolean = false,
    val isUnlocked: Boolean = !isEncrypted,
    val password: String? = null,
    val isCorrupted: Boolean = false,
    val errorMessage: String? = null,
    val compressedFile: File? = null,
    val compressedSizeBytes: Long = 0L,
) {
    val isValid: Boolean
        get() = !isCorrupted && isUnlocked && pageCount > 0
}

data class CompressResult(
    val originalItem: CompressPdfItem,
    val compressedFile: File,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val totalPages: Int,
    val isAlreadyOptimized: Boolean,
) {
    val reductionPercent: Int
        get() {
            if (originalSizeBytes <= 0L) return 0
            val diff = originalSizeBytes - compressedSizeBytes
            return if (diff > 0) ((diff.toDouble() / originalSizeBytes.toDouble()) * 100).toInt() else 0
        }
}

sealed interface CompressUiState {
    data object Idle : CompressUiState
    data class LoadingMetadata(val currentCount: Int, val totalCount: Int) : CompressUiState
    data class Processing(val stepDescription: String, val progress: Float) : CompressUiState
    data class Success(val results: List<CompressResult>) : CompressUiState
    data class Error(val message: String) : CompressUiState
}
