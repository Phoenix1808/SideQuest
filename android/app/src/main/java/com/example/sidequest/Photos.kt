package com.example.sidequest

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

object Photos {

    private const val MAX_SIDE = 1280

    fun newPhotoFile(context: Context): File {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        return File(dir, "quest_${System.currentTimeMillis()}.jpg")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    // Camera photos are 3-12 MB. Shrink to 1280px, fix the rotation, overwrite the

    fun shrink(file: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw ApiException("Couldn't read that photo. Please take it again.")

        val matrix = Matrix()
        val longest = max(decoded.width, decoded.height)
        if (longest > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / longest
            matrix.postScale(scale, scale)
        }
        matrix.postRotate(rotationOf(file))
        val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val bytes = ByteArrayOutputStream().use { out ->
            result.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        file.writeBytes(bytes)
        return bytes
    }

    fun thumbnail(path: String, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun rotationOf(file: File): Float =
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
}
