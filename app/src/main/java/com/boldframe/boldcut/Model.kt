package com.boldframe.boldcut

import android.net.Uri
import androidx.media3.common.MediaItem
import java.util.concurrent.atomic.AtomicLong

/**
 * Core data model. Aage keyframes, effects, masks sab isi pe judenge.
 * Project -> Track -> Clip
 */
data class Clip(
    val id: Long,
    val uri: Uri,
    val durationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs,
    val speed: Float = 1f,
    val transform: Transform = Transform(),
    val startMs: Long = 0L   // timeline pe clip kahan se shuru hoti hai (beech me khaali jagah ho sakti hai)
) {
    val endMs: Long get() = startMs + lengthMs

    // Timeline pe clip ki lambai (speed ke hisaab se)
    val lengthMs: Long get() = ((trimEndMs - trimStartMs) / speed).toLong()

    // Source video ki lambai (trim ke baad, speed ke bina)
    val sourceLenMs: Long get() = trimEndMs - trimStartMs
}

/**
 * Clip ka transform. x/y frame ki width/height ke fraction me (0 = center),
 * rotation/skew degrees me.
 */
data class Transform(
    val x: Float = 0f,
    val y: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val skewX: Float = 0f,
    val skewY: Float = 0f
) {
    val isIdentity: Boolean get() = this == Transform()
}

// Abhi UI ek hi main video track use karta hai; baaki tracks aage Step me.
data class Track(val id: Long, val clips: List<Clip>)

data class Project(val tracks: List<Track>)

fun Clip.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setUri(uri)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(trimStartMs)
                .setEndPositionMs(trimEndMs)
                .build()
        )
        .build()

// Premiere-style marker: timeline ke global time pe
data class Marker(val id: Long, val timeMs: Long)

// Undo/redo ke liye poore edit ka snapshot
data class EditState(val clips: List<Clip>, val markers: List<Marker>)

// Unique ids (clips, markers) - time based, taaki alag sessions me bhi na takraye
object Ids {
    private val n = AtomicLong(System.currentTimeMillis())
    fun next(): Long = n.incrementAndGet()
}

// Disk pe save hone wala project
data class SavedProject(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val clips: List<Clip>,
    val markers: List<Marker>
)

// Home screen ki list ke liye halka summary
data class ProjectMeta(
    val id: String,
    val name: String,
    val modifiedAt: Long,
    val durationMs: Long,
    val clipCount: Int
)

/** Clips ko ek ke baad ek rakh do (startAt se). */
fun layoutSequential(clips: List<Clip>, startAt: Long = 0L): List<Clip> {
    var cur = startAt
    return clips.map {
        val c = it.copy(startMs = cur)
        cur += c.lengthMs
        c
    }
}
