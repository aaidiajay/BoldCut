package com.boldframe.boldcut

import android.net.Uri
import androidx.media3.common.MediaItem

/**
 * Core data model. Aage keyframes, effects, masks sab isi pe judenge.
 * Project -> Track -> Clip
 */
data class Clip(
    val id: Long,
    val uri: Uri,
    val durationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs
) {
    val lengthMs: Long get() = trimEndMs - trimStartMs
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
