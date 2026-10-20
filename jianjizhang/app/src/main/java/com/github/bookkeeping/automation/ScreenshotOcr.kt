package com.github.bookkeeping.automation

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.security.MessageDigest

object ScreenshotOcr {
    data class Result(val text: String, val fingerprint: String)
    suspend fun read(context: Context, uri: Uri): Result = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var n: Int
            while (input.read(buffer).also { n = it } != -1) {
                require(out.size() + n <= 20 * 1024 * 1024) { "图片超过 20 MB，请先裁剪" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        } ?: error("无法读取所选图片")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "请选择可读取的截图" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片解码失败")
        val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val text = try {
            suspendCoroutine<String> { continuation ->
                client.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { continuation.resume(it.text) }
                    .addOnFailureListener { continuation.resumeWithException(it) }
            }
        } finally { client.close(); bitmap.recycle() }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        Result(text.take(24000), "screenshot-$hash")
    }
}
