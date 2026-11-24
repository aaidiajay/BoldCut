package com.boldframe.boldcut

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

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

/** Phone ke decoders + FFmpeg decoders dono wala player (preview/probe ke liye). */
fun buildPlayer(ctx: Context): ExoPlayer {
    val rf = NextRenderersFactory(ctx)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    return ExoPlayer.Builder(ctx, rf).build()
}

/** MediaMetadataRetriever jis file ka time na bataye, uska duration player se nikalte hain. */
suspend fun probeDurationWithPlayer(ctx: Context, uri: Uri): Long =
    withContext(Dispatchers.Main) {
        withTimeoutOrNull(12_000L) {
            suspendCancellableCoroutine<Long> { cont ->
                val p = buildPlayer(ctx)
                var finished = false
                fun finish(v: Long) {
                    if (!finished) {
                        finished = true
                        p.release()
                        if (cont.isActive) cont.resume(v)
                    }
                }
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) finish(p.duration.coerceAtLeast(0L))
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        finish(0L)
                    }
                })
                cont.invokeOnCancellation {
                    if (!finished) {
                        finished = true
                        p.release()
                    }
                }
                p.setMediaItem(MediaItem.fromUri(uri))
                p.prepare()
            }
        } ?: 0L
    }

suspend fun importClips(ctx: Context, uris: List<Uri>): List<Clip> {
    val out = mutableListOf<Clip>()
    for (u in uris) {
        persistReadAccess(ctx, u)
        var d = withContext(Dispatchers.IO) { readDurationMs(ctx, u) }
        if (d <= 0L) d = probeDurationWithPlayer(ctx, u)
        if (d > 0L) out.add(Clip(Ids.next(), u, d))
    }
    return out
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
