package com.gamut.feature.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.gamut.core.designsystem.squareArtUrl
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_COVER_SIDE = 1000

/**
 * What to hand to CoverImage: a user-picked cover is a local `file:` URI and is used as is; anything else is
 * remote album art and goes through [squareArtUrl] like before.
 */
internal fun resolveCoverUrl(url: String?, size: Int): String = when {
    url.isNullOrBlank() -> ""
    // file: = a cover the user picked / an embedded cover; content: = art of a song stored on the phone.
    url.startsWith("file:") || url.startsWith("content:") -> url
    else -> squareArtUrl(url, size).orEmpty()
}

/**
 * Returns a function that opens the system photo picker. The picked image is downscaled and copied into the app's
 * private storage (the picker's URI permission is temporary), and [onPicked] receives the stable `file:` URI.
 */
@Composable
internal fun rememberCoverPicker(onPicked: (String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch { saveCover(context.applicationContext, uri)?.let { latest.value(it) } }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

private suspend fun saveCover(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also applies the photo's EXIF rotation.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_COVER_SIDE) {
                    val s = MAX_COVER_SIDE.toFloat() / longest
                    decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_COVER_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("cannot decode cover")
        }
        val dir = File(context.filesDir, "playlist_covers").apply { mkdirs() }
        val file = File(dir, "cover_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        Uri.fromFile(file).toString()
    }.getOrNull()
}