package com.boldframe.boldcut

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
private val Accent = Color(0xFFFFC107)

/**
 * CapCut-style timeline: playhead beech me fixed, clips scroll hote hain.
 * Playing ke time timeline player ko follow karta hai; pause me ungli se
 * scroll karne par player seek hota hai.
 */
@Composable
fun Timeline(
    clips: List<Clip>,
    selectedId: Long?,
    posMs: Long,
    playing: Boolean,
    onSelect: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onUserDrag: () -> Unit
) {
    val scrollState = rememberScrollState()
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
                    Ruler(totalMs, Modifier.width(totalDp).height(22.dp))
                    Row {
                        clips.forEach { clip ->
                            ClipBlock(
                                clip = clip,
                                selected = clip.id == selectedId,
                                widthDp = with(density) { (clip.lengthMs * pxPerMs).toDp() },
                                onClick = { onSelect(clip.id) }
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
    widthDp: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val ctx = LocalContext.current
    val tileMs = (TILE_DP / DP_PER_SEC * 1000).toLong()
    val tileCount = ceil(clip.lengthMs / 1000f * DP_PER_SEC / TILE_DP).toInt().coerceIn(1, 400)

    val thumbs by produceState<List<ImageBitmap?>>(
        emptyList(), clip.id, clip.trimStartMs, clip.trimEndMs
    ) {
        value = withContext(Dispatchers.IO) { loadThumbs(ctx, clip, tileCount, tileMs) }
    }

    val shape = RoundedCornerShape(4.dp)
    Row(
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
