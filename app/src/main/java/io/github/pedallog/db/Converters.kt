package io.github.pedallog.db

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.TypeConverter
import java.io.ByteArrayOutputStream

// This class contains function to convert a bitmap image into byte array to be stored into room database and vice versa
class Converters {

    @TypeConverter
    fun fromBitmap(bmp: Bitmap?): ByteArray? {
        if (bmp == null) return null

        // Map snapshots may be backed by hardware buffers on newer Android versions.
        // Hardware bitmaps can't be reliably compressed, so copy into a software bitmap.
        val softwareBitmap = if (bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bmp
        }

        // Keep a size cap to avoid huge DB rows, but allow higher-quality thumbnails.
        // (Final size is primarily controlled at save time based on user preference.)
        val maxSizePx = 2048
        val scaledBitmap = run {
            val width = softwareBitmap.width
            val height = softwareBitmap.height
            if (width <= 0 || height <= 0) return null

            val maxDim = maxOf(width, height)
            if (maxDim <= maxSizePx) softwareBitmap else {
                val scale = maxSizePx.toFloat() / maxDim.toFloat()
                val targetW = (width * scale).toInt().coerceAtLeast(1)
                val targetH = (height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(softwareBitmap, targetW, targetH, true)
            }
        }

        val outputStream = ByteArrayOutputStream()
        val okJpeg = scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        if (!okJpeg || outputStream.size() == 0) {
            outputStream.reset()
            val okPng = scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            if (!okPng || outputStream.size() == 0) return null
        }
        return outputStream.toByteArray()
    }

    @TypeConverter
    fun toBitmap(byteArray: ByteArray?): Bitmap? {
        if (byteArray == null || byteArray.isEmpty()) return null
        return BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
    }
}
