package com.apusapp.utils

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OcrHelper {

    /**
     * Extracts text using ML Kit with multi-language Devanagari (Hindi) and Latin (English) support.
     */
    suspend fun extractTextFromImage(context: Context, imageFile: File): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val recognizer = try {
                TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
            } catch (_: Exception) {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
            val image = InputImage.fromFilePath(context, Uri.fromFile(imageFile))

            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        recognizer.close()
                        val text = visionText.text.trim()
                        continuation.resume(if (text.isEmpty()) "No readable text detected in this page." else text)
                    }
                    .addOnFailureListener { exception ->
                        recognizer.close()
                        continuation.resumeWithException(exception)
                    }
            }
        }
    }
}
