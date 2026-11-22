package com.boldframe.boldcut

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val Bg = Color(0xFF0E0E10)
private val Panel = Color(0xFF18181B)
private val Accent = Color(0xFFFFC107)

private fun fmt(ms: Long): String =
    String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60)

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val pxPerMs = with(density) { DP_PER_SEC.dp.toPx() } / 1000f
    val store = remember { ProjectStore(ctx.applicationContext) }

    var name by remember { mutableStateOf("Project") }
    var loaded by remember { mutableStateOf(false) }
    var clips by remember { mutableStateOf(emptyList<Clip>()) }
    var markers by remember { mutableStateOf(emptyList<Marker>()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var posMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var undoStack by remember { mutableStateOf(emptyList<EditState>()) }
    var redoStack by remember { mutableStateOf(emptyList<EditState>()) }
    val thumbKey = remember { arrayOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun totalMs(): Long = clips.sumOf { it.lengthMs }

    // ---- Project save ----
    fun persist(c: List<Clip>, m: List<Marker>, n: String) {
        val old = store.load(projectId)
        val now = System.currentTimeMillis()
        store.save(SavedProject(projectId, n, old?.createdAt ?: now, now, c, m))
        val first = c.firstOrNull()
        if (first != null) {
            val key = "${first.id}-${first.trimStartMs}"
            if (key != thumbKey[0]) {
                firstFrame(ctx, first)?.let {
                    store.saveThumbnail(projectId, it)
                    thumbKey[0] = key
                }
            }
        }
    }

    fun leave() {
        player.pause()
        val c = clips
        val m = markers
        val n = name
        val ok = loaded
        scope.launch {
            if (ok) withContext(Dispatchers.IO) { persist(c, m, n) }
            onBack()
        }
    }

    BackHandler(onBack = { leave() })

    // Project load karo
    LaunchedEffect(projectId) {
        val p = withContext(Dispatchers.IO) { store.load(projectId) }
        if (p != null) {
            val (ok, missing) = withContext(Dispatchers.IO) {
                p.clips.partition { isReadable(ctx, it.uri) }
            }
            name = p.name
            markers = p.markers
            clips = ok
            if (missing.isNotEmpty()) {
                toast("${missing.size} clip ka video gallery me nahi mila, hata diya")
            }
        }
        loaded = true
    }

    // Auto-save: har edit ke thodi der baad
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        snapshotFlow { Triple(clips, markers, name) }
            .drop(1)
            .collectLatest { (c, m, n) ->
                delay(600)
                withContext(Dispatchers.IO) { persist(c, m, n) }
            }
    }

    // ---- Undo / Redo ----
    fun commit(newClips: List<Clip> = clips, newMarkers: List<Marker> = markers) {
        undoStack = (undoStack + EditState(clips, markers)).takeLast(50)
        redoStack = emptyList()
        clips = newClips
        markers = newMarkers
    }

    fun undo() {
        val last = undoStack.lastOrNull() ?: return
        redoStack = redoStack + EditState(clips, markers)
        undoStack = undoStack.dropLast(1)
        clips = last.clips
        markers = last.markers
        selectedId = null
    }

    fun redo() {
        val next = redoStack.lastOrNull() ?: return
        undoStack = undoStack + EditState(clips, markers)
        redoStack = redoStack.dropLast(1)
        clips = next.clips
        markers = next.markers
        selectedId = null
    }

    // ---- Player helpers ----
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

    // Timeline ko kisi bhi time pe le jao (scroll karna hi seek bhi karta hai)
    fun jumpTo(ms: Long) {
        player.pause()
        playing = false
        scope.launch { scrollState.scrollTo((ms * pxPerMs).roundToInt()) }
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
        snapshotFlow { clips }.collect { list ->
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
                val added = importClips(ctx, uris)
                if (added.isEmpty()) toast("Video load nahi hui") else commit(newClips = clips + added)
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
                scope.launch { scrollState.scrollTo(0) }
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
                val list = clips.toMutableList()
                list[i] = c.copy(trimEndMs = splitAt)
                list.add(i + 1, c.copy(id = Ids.next(), trimStartMs = splitAt))
                commit(newClips = list)
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
        commit(newClips = clips.filterNot { it.id == id })
        selectedId = null
    }

    fun trimClip(id: Long, newStart: Long, newEnd: Long) {
        commit(newClips = clips.map {
            if (it.id == id) it.copy(trimStartMs = newStart, trimEndMs = newEnd) else it
        })
    }

    // ---- Markers ----
    fun toggleMarker() {
        if (clips.isEmpty()) {
            toast("Pehle video add karo")
            return
        }
        val near = markers.firstOrNull { abs(it.timeMs - posMs) <= 150 }
        if (near != null) {
            commit(newMarkers = markers.filterNot { it.id == near.id })
            toast("Marker hataya")
        } else {
            commit(newMarkers = (markers + Marker(Ids.next(), posMs)).sortedBy { it.timeMs })
            toast("Marker add hua")
        }
    }

    fun jumpMarker(next: Boolean) {
        val t = if (next) {
            markers.filter { it.timeMs > posMs + 50 }.minByOrNull { it.timeMs }
        } else {
            markers.filter { it.timeMs < posMs - 50 }.maxByOrNull { it.timeMs }
        }
        if (t == null) {
            toast("Aur marker nahi hai")
            return
        }
        jumpTo(t.timeMs)
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "‹",
                color = Color.White,
                fontSize = 30.sp,
                modifier = Modifier.clickable { leave() }.padding(horizontal = 12.dp)
            )
            Text(
                name,
                color = Color.White,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { toast("Export Step 5 me aayega") },
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp)
            ) { Text("Export", fontSize = 13.sp) }
            Spacer(Modifier.width(8.dp))
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

        // Transport: time | marker prev, play, marker next | undo redo
        Row(
            Modifier.fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${fmt(posMs)}/${fmt(totalMs())}",
                color = Color(0xFFB0B4BA),
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            SmallBtn("◆◀") { jumpMarker(false) }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(Color.White)
                    .clickable { togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (playing) "⏸" else "▶", color = Color.Black, fontSize = 18.sp)
            }
            Spacer(Modifier.width(4.dp))
            SmallBtn("▶◆") { jumpMarker(true) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                SmallBtn("↩", enabled = undoStack.isNotEmpty()) { undo() }
                SmallBtn("↪", enabled = redoStack.isNotEmpty()) { redo() }
            }
        }

        // Timeline
        Timeline(
            clips = clips,
            markers = markers,
            selectedId = selectedId,
            posMs = posMs,
            playing = playing,
            scrollState = scrollState,
            onSelect = { selectedId = if (selectedId == it) null else it },
            onSeek = { ms ->
                posMs = ms
                seekGlobal(ms)
            },
            onUserDrag = { player.pause() },
            onTrim = { id, s, e -> trimClip(id, s, e) },
            onMarkerClick = { jumpTo(it.timeMs) }
        )

        // Bottom toolbar
        Row(
            Modifier.fillMaxWidth().background(Panel).horizontalScroll(rememberScrollState())
        ) {
            ToolItem("➕", "Add") { addVideo() }
            ToolItem("✂️", "Split") { splitAtPlayhead() }
            ToolItem("🗑", "Delete") { deleteSelected() }
            ToolItem("📍", "Marker") { toggleMarker() }
            ToolItem("🔤", "Text") { toast("Text aage ke Step me") }
            ToolItem("🎵", "Audio") { toast("Audio aage ke Step me") }
            ToolItem("✨", "Effects") { toast("Effects aage ke Step me") }
            ToolItem("◐", "Mask") { toast("Mask/Roto aage ke Step me") }
            ToolItem("🤖", "AI") { toast("AI generate aage ke Step me") }
        }
    }
}

@Composable
private fun SmallBtn(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) Color.White else Color(0xFF55595F),
        fontSize = 18.sp,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    )
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
