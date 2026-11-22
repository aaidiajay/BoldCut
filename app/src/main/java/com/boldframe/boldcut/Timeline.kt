package com.boldframe.boldcut

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.roundToInt

const val DP_PER_SEC = 50f
private const val TILE_DP = 56f
private const val MIN_CLIP_MS = 300L
private val Accent = Color(0xFFFFC107)
private val MarkerColor = Color(0xFF2EC4B6)

/**
 * CapCut-style timeline: playhead beech me fixed, clips scroll hote hain.
 * Playing ke time timeline player ko follow karta hai; pause me ungli se
 * scroll karne par player seek hota hai.
 */
@Composable
fun Timeline(
    clips: List<Clip>,
    markers: List<Marker>,
    selectedId: Long?,
    posMs: Long,
    playing: Boolean,
    scrollState: ScrollState,
    onSelect: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onUserDrag: () -> Unit,
    onTrim: (Long, Long, Long) -> Unit,
    onMarkerClick: (Marker) -> Unit
) {
    val density = LocalDensity.current
    val pxPerMs = with(density) { DP_PER_SEC.dp.toPx() } / 1000f
    val dragged by scrollState.interactionSource.collectIsDraggedAsState()
    val playingNow by rememberUpdatedState(playing)
    val seek by rememberUpdatedState(onSeek)
    val totalMs = clips.sumOf { it.lengthMs }

    LaunchedEffect(dragged) { if (dragged) onUserDrag() }

    LaunchedEffect(posMs, playing) {
        if (playing) scrollState.scrollTo((posMs * pxPerMs).roundToInt())
    }

    LaunchedEffect(pxPerMs) {
        snapshotFlow { scrollState.value }.collect { px ->
            if (!playingNow) seek((px / pxPerMs).toLong())
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(104.dp)
            .background(Color(0xFF121214))
    ) {
        val half = maxWidth / 2
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.horizontalScroll(scrollState)) {
                Spacer(Modifier.width(half))
                Column {
                    val totalDp = with(density) { (totalMs * pxPerMs).toDp() }
                    Box(Modifier.width(totalDp).height(22.dp)) {
                        Ruler(totalMs, Modifier.fillMaxSize())
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
                    color = Color(0xFF7C8088),
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
                    .background(Color.White)
            )
        }
    }
}

@Composable
private fun Ruler(totalMs: Long, modifier: Modifier) {
    Canvas(modifier) {
        val secs = (totalMs / 1000).toInt()
        val pxSec = DP_PER_SEC.dp.toPx()
        for (s in 0..secs) {
            val x = s * pxSec
            val h = if (s % 5 == 0) size.height * 0.7f else size.height * 0.35f
            drawLine(
                Color(0xFF6B6F76),
                Offset(x, size.height - h),
                Offset(x, size.height),
                1.dp.toPx()
            )
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
    val tileMs = (TILE_DP / DP_PER_SEC * 1000).toLong()
    val tileCount = ceil(clip.lengthMs / 1000f * DP_PER_SEC / TILE_DP).toInt().coerceIn(1, 400)

    val thumbs by produceState<List<ImageBitmap?>>(
        emptyList(), clip.id, clip.trimStartMs, clip.trimEndMs
    ) {
        value = withContext(Dispatchers.IO) { loadThumbs(ctx, clip, tileCount, tileMs) }
    }

    // Trim drag ke time ka temporary change; ungli chhodne pe hi commit hota hai
    var pendingLeft by remember(clip.id, clip.trimStartMs, clip.trimEndMs) { mutableLongStateOf(0L) }
    var pendingRight by remember(clip.id, clip.trimStartMs, clip.trimEndMs) { mutableLongStateOf(0L) }
    val curClip by rememberUpdatedState(clip)

    val effLen = clip.lengthMs - pendingLeft + pendingRight
    val widthDp = with(density) { (effLen * pxPerMs).toDp() }
    val cropDp = with(density) { (pendingLeft * pxPerMs).toDp() }

    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .width(widthDp)
            .height(56.dp)
            .clip(shape)
            .background(Color(0xFF2A2A2E))
            .border(
                if (selected) 2.dp else 0.5.dp,
                if (selected) Accent else Color(0xFF3A3A3F),
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
                            .width(TILE_DP.dp)
                            .fillMaxHeight()
                    )
                } else {
                    Spacer(Modifier.width(TILE_DP.dp))
                }
            }
        }

        if (selected) {
            TrimHandle(
                modifier = Modifier.align(Alignment.CenterStart),
                onDrag = { dx ->
                    val c = curClip
                    val dMs = (dx / pxPerMs).toLong()
                    val lo = -c.trimStartMs
                    val hi = maxOf(c.lengthMs + pendingRight - MIN_CLIP_MS, lo)
                    pendingLeft = (pendingLeft + dMs).coerceIn(lo, hi)
                },
                onEnd = {
                    if (pendingLeft != 0L || pendingRight != 0L) {
                        val c = curClip
                        onTrim(c.id, c.trimStartMs + pendingLeft, c.trimEndMs + pendingRight)
                    }
                }
            )
            TrimHandle(
                modifier = Modifier.align(Alignment.CenterEnd),
                onDrag = { dx ->
                    val c = curClip
                    val dMs = (dx / pxPerMs).toLong()
                    val hi = c.durationMs - c.trimEndMs
                    val lo = minOf(-(c.lengthMs - pendingLeft - MIN_CLIP_MS), hi)
                    pendingRight = (pendingRight + dMs).coerceIn(lo, hi)
                },
                onEnd = {
                    if (pendingLeft != 0L || pendingRight != 0L) {
                        val c = curClip
                        onTrim(c.id, c.trimStartMs + pendingLeft, c.trimEndMs + pendingRight)
                    }
                }
            )
        }
    }
}

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

private fun loadThumbs(ctx: Context, clip: Clip, count: Int, tileMs: Long): List<ImageBitmap?> {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, clip.uri)
        List(count) { i ->
            val t = (clip.trimStartMs + i * tileMs + tileMs / 2)
                .coerceAtMost(clip.trimEndMs - 1)
                .coerceAtLeast(0L)
            val b = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (b == null) {
                null
            } else {
                val w = 112
                val h = (112f * b.height / b.width).toInt().coerceAtLeast(1)
                val s = Bitmap.createScaledBitmap(b, w, h, true)
                if (s !== b) b.recycle()
                s.asImageBitmap()
            }
        }
    } catch (e: Exception) {
        emptyList()
    } finally {
        r.release()
    }
}
