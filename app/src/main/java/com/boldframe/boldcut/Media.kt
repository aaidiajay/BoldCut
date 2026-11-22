package com.boldframe.boldcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun readDurationMs(ctx: Context, uri: Uri): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        r.release()
    }
}

// App restart ke baad bhi video ka access bana rahe (best effort)
fun persistReadAccess(ctx: Context, uri: Uri) {
    runCatching {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

fun isReadable(ctx: Context, uri: Uri): Boolean =
    runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

suspend fun importClips(ctx: Context, uris: List<Uri>): List<Clip> =
    withContext(Dispatchers.IO) {
        uris.mapNotNull { u ->
            persistReadAccess(ctx, u)
            val d = readDurationMs(ctx, u)
            if (d > 0) Clip(Ids.next(), u, d) else null
        }
    }

fun firstFrame(ctx: Context, clip: Clip): Bitmap? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, clip.uri)
        val b = r.getFrameAtTime(clip.trimStartMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        if (b == null) {
            null
        } else {
            val w = 360
            val h = (360f * b.height / b.width).toInt().coerceAtLeast(1)
            val s = Bitmap.createScaledBitmap(b, w, h, true)
            if (s !== b) b.recycle()
            s
        }
    } catch (e: Exception) {
        null
    } finally {
        r.release()
    }
}
