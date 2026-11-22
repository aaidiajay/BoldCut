package com.boldframe.boldcut

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

const val BASE_DP_PER_SEC = 50f
private const val TILE_DP = 90f
private const val MAX_FRAMES = 150
private const val MIN_CLIP_MS = 300L
private val Accent = Color(0xFFFFC107)
private val MarkerColor = Color(0xFF2EC4B6)

/**
 * CapCut-style timeline: playhead beech me fixed, clips scroll hote hain.
 * Do ungliyon se pinch karke zoom (lambi/choti) hota hai.
 */
@Composable
fun Timeline(
    clips: List<Clip>,
    markers: List<Marker>,
    selectedId: Long?,
    posMs: Long,
    playing: Boolean,
    scrollState: ScrollState,
    dpPerSec: Float,
    onZoom: (Float) -> Unit,
    onSelect: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onUserDrag: () -> Unit,
    onTrim: (Long, Long, Long) -> Unit,
    onMarkerClick: (Marker) -> Unit
) {
    val density = LocalDensity.current
    val pxPerMs = with(density) { dpPerSec.dp.toPx() } / 1000f
    val dragged by scrollState.interactionSource.collectIsDraggedAsState()
    val playingNow by rememberUpdatedState(playing)
    val posNow by rememberUpdatedState(posMs)
    val seek by rememberUpdatedState(onSeek)
    val zoom by rememberUpdatedState(onZoom)
    val totalMs = clips.sumOf { it.lengthMs }
    val pal = Bc.c

    LaunchedEffect(dragged) { if (dragged) onUserDrag() }

    LaunchedEffect(posMs, playing) {
        if (playing) scrollState.scrollTo((posMs * pxPerMs).roundToInt())
    }

    // Zoom badalne par playhead usi time pe rahe
    LaunchedEffect(pxPerMs) {
        if (!playingNow) {
            val target = (posNow * pxPerMs).roundToInt()
            scrollState.scrollTo(target)
            withFrameNanos { }
            scrollState.scrollTo(target)
        }
        snapshotFlow { scrollState.value }.collect { px ->
            if (!playingNow) seek((px / pxPerMs).toLong())
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .background(pal.timelineBg)
            .pointerInput(Unit) {
                // Do ungliyon ka pinch: Initial pass me dekhte hain taaki scroll se na takraye
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                zoom(z)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
        val half = maxWidth / 2
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.horizontalScroll(scrollState)) {
                Spacer(Modifier.width(half))
                Column {
                    val totalDp = with(density) { (totalMs * pxPerMs).toDp() }
                    Box(Modifier.width(totalDp).height(22.dp)) {
                        Ruler(totalMs, Modifier.fillMaxSize(), pal.tick, dpPerSec)
                        markers.filter { it.timeMs <= totalMs }.forEach { m ->
                            Box(
                                Modifier
                                    .offset(x = with(density) { (m.timeMs * pxPerMs).toDp() } - 12.dp)
                                    .width(24.dp)
                                    .fillMaxHeight()
                                    .clickable { onMarkerClick(m) },
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    Modifier
                                        .width(8.dp)
                                        .height(14.dp)
                                        .background(MarkerColor, RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                    Row {
                        clips.forEach { clip ->
                            ClipBlock(
                                clip = clip,
                                selected = clip.id == selectedId,
                                pxPerMs = pxPerMs,
                                onClick = { onSelect(clip.id) },
                                onTrim = onTrim
                            )
                        }
                    }
                }
                Spacer(Modifier.width(half))
            }

            if (clips.isEmpty()) {
                Text(
                    "Neeche \"Add\" dabake video chuno",
                    color = pal.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Playhead
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(pal.text)
            )

            // Zoom buttons
            Row(
                Modifier.align(Alignment.TopEnd).padding(4.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
            ) {
                ZoomBtn(Icons.Outlined.ZoomOut) { onZoom(0.7f) }
                ZoomBtn(Icons.Outlined.ZoomIn) { onZoom(1.4f) }
            }
        }
    }
}

@Composable
private fun ZoomBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val pal = Bc.c
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(pal.surface.copy(alpha = 0.8f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { BcIcon(icon, tint = pal.text, size = 18.dp) }
}

@Composable
private fun Ruler(totalMs: Long, modifier: Modifier, tick: Color, dpPerSec: Float) {
    // Zoom ke hisaab se tick ki ginti badalti hai
    val stepSec = when {
        dpPerSec >= 40f -> 1
        dpPerSec >= 15f -> 5
        else -> 10
    }
    Canvas(modifier) {
        val secs = (totalMs / 1000).toInt()
        val pxSec = dpPerSec.dp.toPx()
        var s = 0
        while (s <= secs) {
            val x = s * pxSec
            val h = if (s % (stepSec * 5) == 0) size.height * 0.7f else size.height * 0.35f
            drawLine(
                tick,
                Offset(x, size.height - h),
                Offset(x, size.height),
                1.dp.toPx()
            )
            s += stepSec
        }
    }
}

@Composable
private fun ClipBlock(
    clip: Clip,
    selected: Boolean,
    pxPerMs: Float,
    onClick: () -> Unit,
    onTrim: (Long, Long, Long) -> Unit
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val pal = Bc.c

    // Frames source video ki range se tay hote hain (zoom/speed se nahi), taaki baar-baar reload na ho
    val srcLen = clip.sourceLenMs
    val frameCount = ceil(srcLen / 1000f * BASE_DP_PER_SEC / TILE_DP).toInt().coerceIn(1, MAX_FRAMES)
    val fullWidth = with(density) { (clip.lengthMs * pxPerMs).toDp() }
    val frameWidth = fullWidth / frameCount

    val thumbs by produceState<List<ImageBitmap?>>(
        List(frameCount) { null }, clip.id, clip.trimStartMs, clip.trimEndMs
    ) {
        val r = MediaMetadataRetriever()
        try {
            val ok = withContext(Dispatchers.IO) {
                runCatching { r.setDataSource(ctx, clip.uri) }.isSuccess
            }
            if (ok) {
                val frames = arrayOfNulls<ImageBitmap>(frameCount)
                val order = (0 until frameCount step 4) + (0 until frameCount).filter { it % 4 != 0 }
                var done = 0
                for (i in order) {
                    frames[i] = withContext(Dispatchers.IO) { decodeFrame(r, clip, i, frameCount) }
                    done++
                    if (done % 3 == 0) value = fillGaps(frames)
                }
                value = fillGaps(frames)
            }
        } finally {
            r.release()
        }
    }

    // Trim drag ke time ka temporary change (timeline ms me); ungli chhodne pe commit
    var pendingLeft by remember(clip.id, clip.trimStartMs, clip.trimEndMs, clip.speed) { mutableLongStateOf(0L) }
    var pendingRight by remember(clip.id, clip.trimStartMs, clip.trimEndMs, clip.speed) { mutableLongStateOf(0L) }
    val curClip by rememberUpdatedState(clip)

    val effLen = clip.lengthMs - pendingLeft + pendingRight
    val widthDp = with(density) { (effLen * pxPerMs).toDp() }
    val cropDp = with(density) { (pendingLeft * pxPerMs).toDp() }

    fun commitTrim() {
        if (pendingLeft != 0L || pendingRight != 0L) {
            val c = curClip
            onTrim(
                c.id,
                c.trimStartMs + (pendingLeft * c.speed).toLong(),
                c.trimEndMs + (pendingRight * c.speed).toLong()
            )
        }
    }

    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .width(widthDp)
            .height(56.dp)
            .clip(shape)
            .background(pal.clipBg)
            .border(
                if (selected) 2.dp else 0.5.dp,
                if (selected) Accent else pal.stroke,
                shape
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier
                .height(56.dp)
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .offset(x = -cropDp)
        ) {
            thumbs.forEach { bmp ->
                if (bmp != null) {
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(frameWidth)
                            .fillMaxHeight()
                    )
                } else {
                    Spacer(Modifier.width(frameWidth))
                }
            }
        }

        // Duration + speed label
        val speedTxt = if (clip.speed != 1f) "  " + String.format(Locale.US, "%.2fx", clip.speed) else ""
        Text(
            durationLabel(clip.lengthMs) + speedTxt,
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .background(Color(0x99000000), RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp)
        )

        if (selected) {
            TrimHandle(
                modifier = Modifier.align(Alignment.CenterStart),
                onDrag = { dx ->
                    val c = curClip
                    val dMs = (dx / pxPerMs).toLong()
                    val lo = -(c.trimStartMs / c.speed).toLong()
                    val hi = maxOf(c.lengthMs + pendingRight - MIN_CLIP_MS, lo)
                    pendingLeft = (pendingLeft + dMs).coerceIn(lo, hi)
                },
                onEnd = { commitTrim() }
            )
            TrimHandle(
                modifier = Modifier.align(Alignment.CenterEnd),
                onDrag = { dx ->
                    val c = curClip
                    val dMs = (dx / pxPerMs).toLong()
                    val hi = ((c.durationMs - c.trimEndMs) / c.speed).toLong()
                    val lo = minOf(-(c.lengthMs - pendingLeft - MIN_CLIP_MS), hi)
                    pendingRight = (pendingRight + dMs).coerceIn(lo, hi)
                },
                onEnd = { commitTrim() }
            )
        }
    }
}

private fun durationLabel(ms: Long): String =
    String.format(Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)

@Composable
private fun TrimHandle(modifier: Modifier, onDrag: (Float) -> Unit, onEnd: () -> Unit) {
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    Box(
        modifier
            .width(16.dp)
            .fillMaxHeight()
            .background(Accent)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        drag(dx)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(18.dp)
                .background(Color.Black)
        )
    }
}

private fun decodeFrame(r: MediaMetadataRetriever, clip: Clip, i: Int, count: Int): ImageBitmap? {
    return try {
        val offset = ((i + 0.5) * clip.sourceLenMs / count).toLong()
        val t = (clip.trimStartMs + offset).coerceIn(0L, maxOf(clip.trimEndMs - 1, 0L))
        val b = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        if (b == null) {
            null
        } else {
            val w = 160
            val h = (160f * b.height / b.width).toInt().coerceAtLeast(1)
            val s = Bitmap.createScaledBitmap(b, w, h, true)
            if (s !== b) b.recycle()
            s.asImageBitmap()
        }
    } catch (e: Exception) {
        null
    }
}

// Jo frames abhi decode nahi hue unki jagah pichla frame dikhao
private fun fillGaps(frames: Array<ImageBitmap?>): List<ImageBitmap?> {
    val out = ArrayList<ImageBitmap?>(frames.size)
    var last: ImageBitmap? = null
    for (f in frames) {
        if (f != null) last = f
        out.add(last)
    }
    val first = frames.firstOrNull { it != null }
    for (i in out.indices) {
        if (out[i] == null) out[i] = first else break
    }
    return out
}
