package com.boldframe.boldcut

import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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

private fun fmt(ms: Long): String =
    String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60)

// Reference jaisa bottom tool tabs. step = kis step me kaam karega (null = abhi chal raha / baad me)
private enum class ToolTab(val label: String, val icon: ImageVector, val step: Int?) {
    Edit("Edit", Icons.Outlined.ContentCut, null),
    Media("Media", Icons.Outlined.PermMedia, null),
    Audio("Audio", Icons.Outlined.MusicNote, 8),
    Text("Text", Icons.Outlined.TextFields, 8),
    Shape("Shape", Icons.Outlined.Category, 11),
    Element("Element", Icons.Outlined.EmojiEmotions, 11),
    Template("Template", Icons.Outlined.ViewQuilt, 14),
    Draw("Freehand", Icons.Outlined.Gesture, 11),
    Vector("Vector", Icons.Outlined.Create, 11),
    Effects("Effects", Icons.Outlined.AutoAwesome, 10)
}

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val c = Bc.c
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val pxPerMs = with(density) { DP_PER_SEC.dp.toPx() } / 1000f
    val store = remember { ProjectStore(ctx.applicationContext) }
    val runner = remember { ExportRunner(ctx.applicationContext, scope) }

    var name by remember { mutableStateOf("Project") }
    var loaded by remember { mutableStateOf(false) }
    var clips by remember { mutableStateOf(emptyList<Clip>()) }
    var markers by remember { mutableStateOf(emptyList<Marker>()) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var posMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var undoStack by remember { mutableStateOf(emptyList<EditState>()) }
    var redoStack by remember { mutableStateOf(emptyList<EditState>()) }
    var tab by remember { mutableStateOf(ToolTab.Edit) }
    var showExport by remember { mutableStateOf(false) }
    var exportSrc by remember { mutableStateOf(1920 to 1080) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }
    val thumbKey = remember { arrayOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun totalMs(): Long = clips.sumOf { it.lengthMs }

    // ---- Project save ----
    fun persist(cl: List<Clip>, mk: List<Marker>, n: String) {
        val old = store.load(projectId)
        val now = System.currentTimeMillis()
        store.save(SavedProject(projectId, n, old?.createdAt ?: now, now, cl, mk))
        val first = cl.firstOrNull()
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
        val cl = clips
        val mk = markers
        val n = name
        val ok = loaded
        scope.launch {
            if (ok) withContext(Dispatchers.IO) { persist(cl, mk, n) }
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

    // Auto-save
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        snapshotFlow { Triple(clips, markers, name) }
            .drop(1)
            .collectLatest { (cl, mk, n) ->
                delay(600)
                withContext(Dispatchers.IO) { persist(cl, mk, n) }
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
        for ((i, cl) in clips.withIndex()) {
            if (rem < cl.lengthMs || i == clips.lastIndex) {
                player.seekTo(i, rem.coerceAtMost((cl.lengthMs - 1).coerceAtLeast(0L)))
                return
            }
            rem -= cl.lengthMs
        }
    }

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

    DisposableEffect(runner) {
        onDispose {
            if (runner.state is ExportState.Running) runner.cancel()
        }
    }

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

    // Export progress poll
    val exporting = runner.state is ExportState.Running
    LaunchedEffect(exporting) {
        while (runner.state is ExportState.Running) {
            delay(300)
            runner.pollProgress()
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
        for ((i, cl) in clips.withIndex()) {
            val end = start + cl.lengthMs
            if (posMs in start..end) {
                val off = posMs - start
                if (off < 300 || cl.lengthMs - off < 300) {
                    toast("Playhead ko clip ke beech me lao")
                    return
                }
                val splitAt = cl.trimStartMs + off
                val list = clips.toMutableList()
                list[i] = cl.copy(trimEndMs = splitAt)
                list.add(i + 1, cl.copy(id = Ids.next(), trimStartMs = splitAt))
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

    // Pichle/agle clip ki seema (boundary) pe jao
    fun skipClip(next: Boolean) {
        if (clips.isEmpty()) return
        val b = mutableListOf(0L)
        var acc = 0L
        for (cl in clips) {
            acc += cl.lengthMs
            b.add(acc)
        }
        val t = if (next) b.firstOrNull { it > posMs + 100 } else b.lastOrNull { it < posMs - 100 }
        jumpTo(t ?: if (next) totalMs() else 0L)
    }

    fun openExport() {
        if (clips.isEmpty()) {
            toast("Pehle video add karo")
            return
        }
        player.pause()
        scope.launch {
            val sz = withContext(Dispatchers.IO) { readVideoSize(ctx, clips.first().uri) } ?: (1920 to 1080)
            exportSrc = sz
            showExport = true
        }
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Outlined.ArrowBack, onClick = { leave() })
            Text(
                name,
                color = c.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconBtn(Icons.Outlined.Settings, onClick = onOpenSettings)
            Button(
                onClick = { openExport() },
                colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) {
                BcIcon(Icons.Outlined.IosShare, tint = c.onAccent, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Export", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
        }

        // Preview
        Box(
            Modifier.weight(1f).fillMaxWidth().background(c.previewBg),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctxView ->
                    PlayerView(ctxView).apply {
                        this.player = player
                        useController = false
                    }
                }
            )
            if (clips.isEmpty()) {
                Text("Preview yahan dikhega", color = c.muted, fontSize = 14.sp)
            }
        }

        // Transport row
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${fmt(posMs)} / ${fmt(totalMs())}",
                color = c.muted,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f).padding(start = 6.dp)
            )
            IconBtn(Icons.Outlined.Undo, onClick = { undo() }, enabled = undoStack.isNotEmpty())
            IconBtn(Icons.Outlined.Redo, onClick = { redo() }, enabled = redoStack.isNotEmpty())
            IconBtn(Icons.Outlined.SkipPrevious, onClick = { skipClip(false) })
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(c.text)
                    .clickable { togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                BcIcon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    tint = c.bg,
                    size = 26.dp
                )
            }
            IconBtn(Icons.Outlined.SkipNext, onClick = { skipClip(true) })
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

        // Tool tabs + panel
        Column(Modifier.fillMaxWidth().background(c.surface)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                ToolTab.values().forEach { t ->
                    val sel = t == tab
                    Column(
                        Modifier
                            .width(72.dp)
                            .clickable { tab = t }
                            .padding(top = 8.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BcIcon(t.icon, tint = if (sel) c.accent else c.muted, size = 24.dp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            t.label,
                            fontSize = 11.sp,
                            maxLines = 1,
                            color = if (sel) c.accent else c.muted,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
            Box(
                Modifier.fillMaxWidth().height(80.dp).background(c.bg.copy(alpha = 0.5f)),
                contentAlignment = Alignment.CenterStart
            ) {
                when (tab) {
                    ToolTab.Edit -> Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Action(Icons.Outlined.VerticalSplit, "Split") { splitAtPlayhead() }
                        Action(Icons.Outlined.Delete, "Delete") { deleteSelected() }
                        Action(Icons.Outlined.BookmarkBorder, "Marker") { toggleMarker() }
                        Action(Icons.Outlined.ChevronLeft, "Prev marker") { jumpMarker(false) }
                        Action(Icons.Outlined.ChevronRight, "Next marker") { jumpMarker(true) }
                    }
                    ToolTab.Media -> Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Action(Icons.Outlined.Videocam, "Add video") { addVideo() }
                        Action(Icons.Outlined.Image, "Add photo") { toast("Photo support Step 7 me") }
                        Action(Icons.Outlined.Mic, "Record") { toast("Record aage ke Step me") }
                    }
                    else -> Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BcIcon(tab.icon, tint = c.muted, size = 26.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${tab.label} tools Step ${tab.step} me aayenge",
                            color = c.muted,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    // ---- Export dialogs ----
    if (showExport) {
        ExportSheet(
            totalMs = totalMs(),
            srcW = exportSrc.first,
            srcH = exportSrc.second,
            initial = exportSettings,
            onStart = { s ->
                exportSettings = s
                showExport = false
                val (cw, ch) = canvasSize(exportSrc.first, exportSrc.second, s.shortSide)
                runner.start(clips, s, cw, ch)
            },
            onDismiss = { showExport = false }
        )
    }
    when (val st = runner.state) {
        is ExportState.Running -> ExportProgressDialog(st.progress, onCancel = { runner.cancel() })
        is ExportState.Done -> ExportDoneDialog(
            uri = st.uri,
            onShare = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, st.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(send, "Share video")
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
            },
            onClose = { runner.reset() }
        )
        is ExportState.Failed -> ExportFailedDialog(st.message, onClose = { runner.reset() })
        else -> {}
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Bc.c
    Column(
        Modifier
            .width(80.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BcIcon(icon, tint = c.text, size = 26.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = c.muted, maxLines = 1)
    }
}
