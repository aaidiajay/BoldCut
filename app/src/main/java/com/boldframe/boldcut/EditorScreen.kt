package com.boldframe.boldcut

import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private val Bg = Color(0xFF0E0E10)
private val Panel = Color(0xFF18181B)
private val Accent = Color(0xFFFFC107)

private fun fmt(ms: Long): String =
    String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60)

@Composable
fun EditorScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val clips = remember { mutableStateListOf<Clip>() }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var posMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    val idGen = remember { AtomicLong(1) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun totalMs(): Long = clips.sumOf { it.lengthMs }

    fun seekGlobal(ms: Long) {
        var rem = ms.coerceAtLeast(0L)
        for ((i, c) in clips.withIndex()) {
            if (rem < c.lengthMs || i == clips.lastIndex) {
                player.seekTo(i, rem.coerceAtMost((c.lengthMs - 1).coerceAtLeast(0L)))
                return
            }
            rem -= c.lengthMs
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Clips badle to player ki playlist dobara banao
    LaunchedEffect(Unit) {
        snapshotFlow { clips.toList() }.collect { list ->
            if (list.isEmpty()) {
                player.clearMediaItems()
                posMs = 0L
            } else {
                player.setMediaItems(list.map { it.toMediaItem() })
                player.prepare()
                posMs = posMs.coerceAtMost(list.sumOf { it.lengthMs })
                seekGlobal(posMs)
            }
        }
    }

    // Playing ke time global position track karo
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            if (player.isPlaying) {
                val idx = player.currentMediaItemIndex.coerceAtMost(clips.size)
                var base = 0L
                for (i in 0 until idx) base += clips[i].lengthMs
                posMs = base + player.currentPosition.coerceAtLeast(0L)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val added = withContext(Dispatchers.IO) {
                    uris.mapNotNull { u ->
                        runCatching {
                            val r = MediaMetadataRetriever()
                            try {
                                r.setDataSource(ctx, u)
                                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                    ?.toLongOrNull() ?: 0L
                                if (d > 0) Clip(idGen.getAndIncrement(), u, d) else null
                            } finally {
                                r.release()
                            }
                        }.getOrNull()
                    }
                }
                if (added.isEmpty()) toast("Video load nahi hui") else clips.addAll(added)
            }
        }
    }

    fun addVideo() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    fun togglePlay() {
        if (clips.isEmpty()) {
            toast("Pehle video add karo")
            return
        }
        if (playing) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED || posMs >= totalMs() - 50) {
                posMs = 0L
                seekGlobal(0L)
            }
            player.play()
        }
    }

    fun splitAtPlayhead() {
        var start = 0L
        for ((i, c) in clips.withIndex()) {
            val end = start + c.lengthMs
            if (posMs in start..end) {
                val off = posMs - start
                if (off < 300 || c.lengthMs - off < 300) {
                    toast("Playhead ko clip ke beech me lao")
                    return
                }
                val splitAt = c.trimStartMs + off
                clips[i] = c.copy(trimEndMs = splitAt)
                clips.add(i + 1, c.copy(id = idGen.getAndIncrement(), trimStartMs = splitAt))
                return
            }
            start = end
        }
        toast("Pehle video add karo")
    }

    fun deleteSelected() {
        val id = selectedId
        if (id == null) {
            toast("Pehle clip pe tap karke select karo")
            return
        }
        clips.removeAll { it.id == id }
        selectedId = null
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("BoldCut", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Button(
                onClick = { toast("Export Step 3 me aayega") },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) { Text("Export", fontSize = 13.sp) }
        }

        // Preview
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PlayerView(c).apply {
                        this.player = player
                        useController = false
                    }
                }
            )
            if (clips.isEmpty()) {
                Text("Preview yahan dikhega", color = Color(0xFF5C6066), fontSize = 14.sp)
            }
        }

        // Transport
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${fmt(posMs)} / ${fmt(totalMs())}",
                color = Color(0xFFB0B4BA),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color.White)
                    .clickable { togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (playing) "⏸" else "▶", color = Color.Black, fontSize = 18.sp)
            }
            Spacer(Modifier.weight(1f))
        }

        // Timeline
        Timeline(
            clips = clips,
            selectedId = selectedId,
            posMs = posMs,
            playing = playing,
            onSelect = { selectedId = if (selectedId == it) null else it },
            onSeek = { ms ->
                posMs = ms
                seekGlobal(ms)
            },
            onUserDrag = { player.pause() }
        )

        // Bottom toolbar
        Row(
            Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState())
        ) {
            ToolItem("➕", "Add") { addVideo() }
            ToolItem("✂️", "Split") { splitAtPlayhead() }
            ToolItem("🗑", "Delete") { deleteSelected() }
            ToolItem("🔤", "Text") { toast("Text Step 3 me") }
            ToolItem("🎵", "Audio") { toast("Audio aage ke Step me") }
            ToolItem("✨", "Effects") { toast("Effects aage ke Step me") }
            ToolItem("◐", "Mask") { toast("Mask/Roto aage ke Step me") }
            ToolItem("🤖", "AI") { toast("AI generate aage ke Step me") }
        }
    }
}

@Composable
private fun ToolItem(icon: String, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(68.dp).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 20.sp)
        Text(label, fontSize = 11.sp, color = Color(0xFFB0B4BA))
    }
}
