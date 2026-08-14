package roc.win.lottery.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream

/**
 * 解码图片、限制最长边并应用 EXIF 方向。
 *
 * @param openStream 每次调用都返回一条从图片起点读取的新输入流。
 * @param maxEdgePixels 解码后允许保留的最长边像素数。
 * @return 已归一方向的位图；内容无法读取或解码时返回空。
 */
internal fun decodeNormalizedBitmap(
    openStream: () -> InputStream?,
    maxEdgePixels: Int,
): Bitmap? {
    val orientation =
        openStream()?.use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsStream = openStream() ?: return null
    boundsStream.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxEdgePixels) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decoded = openStream()?.use { stream -> BitmapFactory.decodeStream(stream, null, options) } ?: return null
    return applyExifOrientation(decoded, orientation)
}

/** 按 EXIF 方向对位图执行旋转或镜像。 */
private fun applyExifOrientation(
    bitmap: Bitmap,
    orientation: Int,
): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
            matrix.setScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_180 -> {
            matrix.setRotate(180f)
        }

        ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
            matrix.setRotate(180f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.setRotate(90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_90 -> {
            matrix.setRotate(90f)
        }

        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.setRotate(-90f)
            matrix.postScale(-1f, 1f)
        }

        ExifInterface.ORIENTATION_ROTATE_270 -> {
            matrix.setRotate(-90f)
        }

        else -> {
            return bitmap
        }
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { normalized ->
        if (normalized !== bitmap) bitmap.recycle()
    }
}
