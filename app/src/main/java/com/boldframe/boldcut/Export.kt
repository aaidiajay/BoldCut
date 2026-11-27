package com.boldframe.boldcut

import android.content.ContentValues
import android.content.Context
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.Presentation
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

enum class Quality(val label: String, val bpp: Float) {
    LOW("Low", 0.05f),
    RECOMMENDED("Recommended", 0.09f),
    HIGH("High", 0.14f)
}

data class ExportSettings(
    val shortSide: Int = 1080,
    val fps: Int = 30,
    val quality: Quality = Quality.RECOMMENDED,
    val hevc: Boolean = false
)

val RESOLUTIONS = listOf(480 to "480p", 720 to "720p", 1080 to "1080p", 1440 to "2K", 2160 to "4K")
val FPS_OPTIONS = listOf(24, 25, 30, 50, 60)

/** Pehle clip ke aspect ratio ke hisaab se output canvas (chhoti side = shortSide). */
fun canvasSize(w: Int, h: Int, shortSide: Int): Pair<Int, Int> {
    val scale = shortSide.toFloat() / minOf(w, h).toFloat()
    fun even(v: Float): Int = ((v.roundToInt()) / 2 * 2).coerceAtLeast(2)
    return even(w * scale) to even(h * scale)
}

fun videoBitrate(w: Int, h: Int, fps: Int, q: Quality, hevc: Boolean): Int {
    val factor = if (hevc) 0.65 else 1.0
    val bps = w.toDouble() * h * fps * q.bpp.toDouble() * factor
    return bps.toLong().coerceIn(500_000L, 200_000_000L).toInt()
}

fun readVideoSize(ctx: Context, uri: Uri): Pair<Int, Int>? {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w == null || h == null) {
            null
        } else if (rot == 90 || rot == 270) {
            h to w
        } else {
            w to h
        }
    } catch (e: Exception) {
        null
    } finally {
        r.release()
    }
}

sealed interface ExportState {
    object Idle : ExportState
    data class Running(val progress: Float) : ExportState
    data class Done(val uri: Uri?) : ExportState
    data class Failed(val message: String) : ExportState
}

/** Media3 Transformer se export. Main thread pe chalao. */
class ExportRunner(private val ctx: Context, private val scope: CoroutineScope) {
    var state: ExportState by mutableStateOf<ExportState>(ExportState.Idle)
        private set

    private var transformer: Transformer? = null
    private var outFile: File? = null

    fun start(clips: List<Clip>, s: ExportSettings, w: Int, h: Int) {
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "BoldCut_${System.currentTimeMillis()}.mp4")
        outFile = out

        val sorted = clips.sortedBy { it.startMs }
        val sequenceBuilder =
            EditedMediaItemSequence.Builder()
        // Shuru me gap ho to audio/video tracks pehle se tay karne padte hain.
        // (Ye setting naye Media3 me hai; reflection se lagate hain taaki build na toote.)
        for (mName in listOf("experimentalSetForceAudioTrack", "experimentalSetForceVideoTrack")) {
            runCatching {
                sequenceBuilder.javaClass
                    .getMethod(mName, java.lang.Boolean.TYPE)
                    .invoke(sequenceBuilder, true)
            }
        }
        var cursor = 0L
        for (c in sorted) {
            val mi = MediaItem.Builder()
                .setUri(c.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(c.trimStartMs)
                        .setEndPositionMs(c.trimEndMs)
                        .build()
                )
                .build()
            val vfx = mutableListOf<Effect>()
            val afx = mutableListOf<AudioProcessor>()
            if (c.speed != 1f) {
                val sonic = SonicAudioProcessor()
                sonic.setSpeed(c.speed)
                sonic.setPitch(1f)
                afx.add(sonic)
                vfx.add(SpeedChangeEffect(c.speed))
            }
            vfx.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
            if (!c.transform.isIdentity) {
                val tm = c.transform.ndcMatrix(w.toFloat(), h.toFloat())
                vfx.add(object : MatrixTransformation {
                    override fun getMatrix(presentationTimeUs: Long): Matrix = tm
                })
            }
            vfx.add(FrameDropEffect.createDefaultFrameDropEffect(s.fps.toFloat()))
            val item = EditedMediaItem.Builder(mi)
                .setEffects(Effects(afx, vfx))
                .build()
            // Media3 video me gap support nahi karta: khaali jagah ko kaali image se bharte hain
            if (c.startMs - cursor > 40L) {
                val gapFx = mutableListOf<Effect>()
                gapFx.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT))
                val gapItem = EditedMediaItem.Builder(MediaItem.fromUri(blackImageUri()))
                    .setDurationUs((c.startMs - cursor) * 1000L)
                    .setFrameRate(s.fps)
                    .setEffects(Effects(emptyList<AudioProcessor>(), gapFx))
                    .build()
                sequenceBuilder.addItem(gapItem)
            }
            sequenceBuilder.addItem(item)
            cursor = c.endMs
        }
        val sequence = sequenceBuilder.build()
        val composition = Composition.Builder(sequence).build()

        val encoderFactory = DefaultEncoderFactory.Builder(ctx)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(videoBitrate(w, h, s.fps, s.quality, s.hevc))
                    .build()
            )
            .build()

        val t = Transformer.Builder(ctx)
            .setVideoMimeType(if (s.hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onTransformDone(out)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    val sb = StringBuilder()
                    sb.append(exportException.message ?: "Export fail ho gaya")
                    sb.append("\nCode: ").append(exportException.errorCodeName)
                    var cause: Throwable? = exportException.cause
                    var depth = 0
                    while (cause != null && depth < 4) {
                        sb.append("\n").append(cause.javaClass.simpleName)
                        val cm = cause.message
                        if (!cm.isNullOrBlank()) sb.append(": ").append(cm.take(160))
                        cause = cause.cause
                        depth++
                    }
                    state = ExportState.Failed(sb.toString())
                }
            })
            .build()
        transformer = t
        state = ExportState.Running(0f)
        t.start(composition, out.absolutePath)
    }

    fun pollProgress() {
        val t = transformer ?: return
        if (state !is ExportState.Running) return
        val holder = ProgressHolder()
        val ps = t.getProgress(holder)
        if (ps == Transformer.PROGRESS_STATE_AVAILABLE) {
            state = ExportState.Running(holder.progress / 100f)
        }
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
        outFile?.delete()
        state = ExportState.Idle
    }

    fun reset() {
        state = ExportState.Idle
    }

    private fun blackImageUri(): Uri {
        val f = File(ctx.cacheDir, "black_gap.png")
        if (!f.exists()) {
            val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.BLACK)
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
        }
        return Uri.fromFile(f)
    }

    private fun onTransformDone(file: File) {
        transformer = null
        scope.launch(Dispatchers.IO) {
            val uri = runCatching { saveToGallery(file) }.getOrNull()
            file.delete()
            withContext(Dispatchers.Main) { state = ExportState.Done(uri) }
        }
    }

    private fun saveToGallery(file: File): Uri? {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BoldCut")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                null
            } else {
                resolver.openOutputStream(uri)?.use { os ->
                    file.inputStream().use { it.copyTo(os) }
                }
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                uri
            }
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            if (dir == null) {
                null
            } else {
                val dest = File(dir, file.name)
                file.copyTo(dest, overwrite = true)
                Uri.fromFile(dest)
            }
        }
    }
}

// ---------------- UI ----------------

@Composable
internal fun OptChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bc.c
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.accent else c.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            color = if (selected) c.onAccent else c.text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
internal fun Label(text: String) {
    Text(
        text,
        color = Bc.c.muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun ExportSheet(
    totalMs: Long,
    srcW: Int,
    srcH: Int,
    initial: ExportSettings,
    onStart: (ExportSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val c = Bc.c
    var s by remember { mutableStateOf(initial) }
    val (cw, ch) = canvasSize(srcW, srcH, s.shortSide)
    val bitrate = videoBitrate(cw, ch, s.fps, s.quality, s.hevc)
    val sizeMb = (bitrate + 128_000L) * (totalMs / 1000.0) / 8.0 / 1_000_000.0

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = c.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                    Text("Export", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)

                    Label("RESOLUTION")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RESOLUTIONS.forEach { (v, label) ->
                            OptChip(label, s.shortSide == v) { s = s.copy(shortSide = v) }
                        }
                    }

                    Label("FRAME RATE")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FPS_OPTIONS.forEach { v ->
                            OptChip("$v fps", s.fps == v) { s = s.copy(fps = v) }
                        }
                    }

                    Label("QUALITY")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Quality.values().forEach { q ->
                            OptChip(q.label, s.quality == q) { s = s.copy(quality = q) }
                        }
                    }

                    Label("FORMAT")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptChip("H.264 (sab jagah chalta)", !s.hevc) { s = s.copy(hevc = false) }
                        OptChip("H.265 (chhoti file)", s.hevc) { s = s.copy(hevc = true) }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text(
                        "${cw}×${ch} · ${s.fps} fps · ~" + String.format(Locale.US, "%.0f MB", sizeMb),
                        color = c.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Source se bada resolution ya zyada fps karne se quality nahi badhti. Export ke time app khuli rakho.",
                        color = c.muted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )

                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onStart(s) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accent,
                            contentColor = c.onAccent
                        )
                    ) { Text("Export", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun ExportProgressDialog(progress: Float, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Export ho raha hai...") },
        text = {
            Column {
                LinearProgressIndicator(
                    progress = progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Text("${(progress * 100).roundToInt()}%  ·  app khuli rakho")
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } }
    )
}

@Composable
fun ExportDoneDialog(uri: Uri?, onShare: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (uri != null) "Export ho gaya" else "Export hua, par save nahi ho paya") },
        text = {
            Text(
                if (uri != null) "Video gallery me Movies/BoldCut folder me save ho gaya."
                else "Video ban gaya par gallery me save nahi ho paya. Storage check karo."
            )
        },
        confirmButton = {
            if (uri != null) TextButton(onClick = onShare) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("OK") } }
    )
}

@Composable
fun ExportFailedDialog(message: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Export fail ho gaya") },
        text = { Text(message, fontSize = 12.sp) },
        confirmButton = { TextButton(onClick = onClose) { Text("OK") } }
    )
}
