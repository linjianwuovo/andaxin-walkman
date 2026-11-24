package com.ljyh.mei.utils.image

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import com.ljyh.mei.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

suspend fun saveImageToGallery(context: Context, imageUrl: String) = withContext(Dispatchers.Main) {
    Toast.makeText(context, R.string.detail_cover_saving, Toast.LENGTH_SHORT).show()
    val message = try {
        withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context).data(imageUrl).size(Size.ORIGINAL).allowHardware(false).build()
            val result = context.imageLoader.execute(request) as? SuccessResult ?: error("Unable to load artwork")
            val bitmap = result.image.toBitmap()
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MeiMusic")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val image = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            var published = false
            try {
                checkNotNull(resolver.openOutputStream(image)).use {
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it))
                }
                val finished = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(image, finished, null, null) > 0)
                published = true
            } finally {
                if (!published) resolver.delete(image, null, null)
            }
        }
        R.string.detail_cover_saved
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Timber.w(error, "Could not save cover artwork")
        R.string.detail_cover_save_failed
    }
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
