package com.boldframe.boldcut

import android.content.Intent
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
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

// Bottom tool tabs (reference jaisa poora list). step = kis step me kaam karega
private enum class ToolTab(val label: String, val icon: ImageVector, val step: Int?, val ai: Boolean = false) {
    Edit("Edit", Icons.Outlined.ContentCut, null),
    Media("Media", Icons.Outlined.PermMedia, null),
    Audio("Audio", Icons.Outlined.MusicNote, 11),
    Text("Text", Icons.Outlined.TextFields, 11),
    Effects("Effects", Icons.Outlined.AutoAwesome, 12),
    Overlay("Overlay", Icons.Outlined.Layers, 9),
    Captions("Captions", Icons.Outlined.Subtitles, 14),
    Filters("Filters", Icons.Outlined.FilterVintage, 12),
    Adjust("Adjust", Icons.Outlined.Tune, 12),
    Stickers("Stickers", Icons.Outlined.StickyNote2, 9),
    Shape("Shape", Icons.Outlined.Category, 9),
    Draw("Freehand", Icons.Outlined.Gesture, 9),
    Vector("Vector", Icons.Outlined.Create, 9),
    Generate("Generate", Icons.Outlined.CreateNewFolder, 14, true),
    Avatar("AI avatar", Icons.Outlined.Face, 14, true),
    Transcript("Transcript", Icons.Outlined.Article, 14),
    Aspect("Aspect", Icons.Outlined.AspectRatio, 8),
    Background("Background", Icons.Outlined.Texture, 8),
    Template("Templates", Icons.Outlined.ViewQuilt, 14)
}

@Composable
fun EditorScreen(projectId: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val c = Bc.c
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val store = remember { ProjectStore(ctx.applicationContext) }
    val runner = remember { ExportRunner(ctx.applicationContext, scope) }

    var dpPerSec by remember { mutableStateOf(BASE_DP_PER_SEC) }

    var name by remember { mutableStateOf("Project") }
    var loaded by remember { mutableStateOf(false) }
    var clips by remember { mutableStateOf(emptyList<Clip>()) }
    var markers by remember { mutableStateOf(emptyList<Marker>()) }
    val totalNow = clips.sumOf { it.lengthMs }
    val effDps = clampDps(dpPerSec, totalNow, density.density)
    val pxPerMs = with(density) { effDps.dp.toPx() } / 1000f
    LaunchedEffect(totalNow) {
        if (dpPerSec != effDps) dpPerSec = effDps
    }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var posMs by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var undoStack by remember { mutableStateOf(emptyList<EditState>()) }
    var redoStack by remember { mutableStateOf(emptyList<EditState>()) }
    var tab by remember { mutableStateOf(ToolTab.Edit) }
    var showExport by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(GridConfig()) }
    var showGrid by remember { mutableStateOf(false) }
    var pz by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var showSide by remember { mutableStateOf(false) }
    var fullPreview by remember { mutableStateOf(false) }
    var lowQ by remember { mutableStateOf(false) }
    var outlines by remember { mutableStateOf(false) }
    val screenWdp = LocalConfiguration.current.screenWidthDp
    var videoSize by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var exportSettings by remember { mutableStateOf(ExportSettings()) }
    val thumbKey = remember { arrayOf<String?>(null) }

    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun totalMs(): Long = clips.sumOf { it.lengthMs }

    // Full-screen preview: landscape video ho to phone ghuma dete hain
    fun setFull(on: Boolean) {
        fullPreview = on
        val act = ctx.findActivity()
        val landscape = videoSize?.let { it.first > it.second } == true
        act?.requestedOrientation =
            if (on && landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    DisposableEffect(Unit) {
        onDispose {
            ctx.findActivity()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun zoomBy(f: Float) {
        dpPerSec = clampDps(dpPerSec * f, totalMs(), density.density)
    }

    // Poora project screen me fit karo
    fun fitZoom() {
        val sec = totalMs() / 1000f
        if (sec <= 0f) {
            toast("Pehle video add karo")
            return
        }
        dpPerSec = clampDps(screenWdp * 0.85f / sec, totalMs(), density.density)
    }

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

    BackHandler(onBack = {
        if (fullPreview) setFull(false)
        else if (selectedId != null) selectedId = null
        else leave()
    })

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

    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        snapshotFlow { Triple(clips, markers, name) }
            .drop(1)
            .collectLatest { (cl, mk, n) ->
                delay(600)
                withContext(Dispatchers.IO) { persist(cl, mk, n) }
            }
    }

    // Preview grid ke liye pehle clip ka size
    LaunchedEffect(clips.firstOrNull()?.id) {
        val f = clips.firstOrNull()
        videoSize = if (f == null) null else withContext(Dispatchers.IO) { readVideoSize(ctx, f.uri) }
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

    // ---- Player helpers (speed ke saath) ----
    // rem = clip ke andar timeline ms; player ko source ms chahiye (rem * speed)
    fun seekGlobal(ms: Long) {
        var rem = ms.coerceAtLeast(0L)
        for ((i, cl) in clips.withIndex()) {
            if (rem < cl.lengthMs || i == clips.lastIndex) {
                val src = (rem * cl.speed).toLong()
                player.seekTo(i, src.coerceAtMost((cl.sourceLenMs - 1).coerceAtLeast(0L)))
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

    // Playing ke time global position track karo aur clip ki speed lagao
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            if (player.isPlaying) {
                val idx = player.currentMediaItemIndex.coerceAtMost(clips.size)
                val cur = clips.getOrNull(idx)
                if (cur != null && abs(player.playbackParameters.speed - cur.speed) > 0.001f) {
                    player.setPlaybackSpeed(cur.speed)
                }
                var base = 0L
                for (i in 0 until idx) base += clips[i].lengthMs
                val speed = cur?.speed ?: 1f
                posMs = base + (player.currentPosition.coerceAtLeast(0L) / speed).toLong()
            }
        }
    }

    val exporting = runner.state is ExportState.Running
    LaunchedEffect(exporting) {
        while (runner.state is ExportState.Running) {
            delay(300)
            runner.pollProgress()
        }
    }

    // Low quality preview: player ko 480p tak limit (asli proxy Step 7 me)
    LaunchedEffect(lowQ) {
        val b = player.trackSelectionParameters.buildUpon()
        if (lowQ) b.setMaxVideoSize(854, 480) else b.clearVideoSizeConstraints()
        player.trackSelectionParameters = b.build()
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
            clips.getOrNull(player.currentMediaItemIndex)?.let { player.setPlaybackSpeed(it.speed) }
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
                val splitAt = cl.trimStartMs + (off * cl.speed).toLong()
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

    fun duplicateSelected() {
        val id = selectedId ?: return
        val i = clips.indexOfFirst { it.id == id }
        if (i < 0) return
        val list = clips.toMutableList()
        val copy = clips[i].copy(id = Ids.next())
        list.add(i + 1, copy)
        commit(newClips = list)
        selectedId = copy.id
    }

    fun applySpeed(s: Float) {
        val id = selectedId ?: return
        commit(newClips = clips.map { if (it.id == id) it.copy(speed = s) else it })
        showSpeed = false
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
        showExport = true
    }

    val selClip = clips.firstOrNull { it.id == selectedId }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // Top bar
        if (!fullPreview) {
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

        }

        // Preview: zoom/pan + grid + right side toolbar
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(c.previewBg)
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        pz = (pz * zoomChange).coerceIn(0.25f, 4f)
                        pan = if (pz > 1f) pan + panChange else Offset.Zero
                    }
                }
        ) {
            val boxW = maxWidth
            val boxH = maxHeight
            val vs = videoSize
            val aspect = if (vs != null) vs.first.toFloat() / vs.second.toFloat() else 16f / 9f
            Box(
                Modifier
                    .align(Alignment.Center)
                    .requiredSize(boxW * pz, boxH * pz)
                    .offset { IntOffset(pan.x.roundToInt(), pan.y.roundToInt()) }
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
                GridOverlay(grid, aspect, Modifier.fillMaxSize())
                if (outlines && clips.isNotEmpty()) OutlineOverlay(aspect, Modifier.fillMaxSize())
            }
            if (clips.isEmpty()) {
                Text(
                    "Preview yahan dikhega",
                    color = c.muted,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            if (showSide && !fullPreview) {
                PreviewSideBar(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    zoomPct = (pz * 100).roundToInt(),
                    gridOn = grid.enabled,
                    lowQ = lowQ,
                    outlinesOn = outlines,
                    onFull = { setFull(true) },
                    onLowQ = {
                        lowQ = !lowQ
                        toast(if (lowQ) "Low quality preview ON (asli proxy Step 7 me)" else "Low quality preview OFF")
                    },
                    onGrid = { showGrid = true },
                    onOutlines = { outlines = !outlines },
                    onCamera = { toast("3D camera view Step 10 me") },
                    onZoomIn = { pz = (pz * 1.25f).coerceIn(0.25f, 4f) },
                    onZoomOut = {
                        pz = (pz / 1.25f).coerceIn(0.25f, 4f)
                        if (pz <= 1f) pan = Offset.Zero
                    },
                    onZoomReset = {
                        pz = 1f
                        pan = Offset.Zero
                    }
                )
            }

            // Full-screen controls
            if (fullPreview) {
                val white = Color(0xE6FFFFFF)
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBtn(
                        if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        onClick = { togglePlay() },
                        tint = white
                    )
                    Text(
                        "${fmt(posMs)} / ${fmt(totalMs())}",
                        color = white,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    IconBtn(Icons.Outlined.FullscreenExit, onClick = { setFull(false) }, tint = white)
                }
            }
        }

        if (!fullPreview) {
        // Transport row (reference jaisa): undo redo prev play next duplicate panel-toggle
        Row(
            Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${fmt(posMs)} / ${fmt(totalMs())}",
                color = c.muted,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f).padding(start = 6.dp)
            )
            IconBtn(Icons.Outlined.Undo, onClick = { undo() }, enabled = undoStack.isNotEmpty(), box = 38.dp)
            IconBtn(Icons.Outlined.Redo, onClick = { redo() }, enabled = redoStack.isNotEmpty(), box = 38.dp)
            IconBtn(Icons.Outlined.SkipPrevious, onClick = { skipClip(false) }, box = 38.dp)
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.text)
                    .clickable { togglePlay() },
                contentAlignment = Alignment.Center
            ) {
                BcIcon(
                    if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    tint = c.bg,
                    size = 24.dp
                )
            }
            IconBtn(Icons.Outlined.SkipNext, onClick = { skipClip(true) }, box = 38.dp)
            IconBtn(
                Icons.Outlined.ContentCopy,
                onClick = {
                    if (selectedId == null) toast("Pehle clip select karo") else duplicateSelected()
                },
                box = 38.dp,
                size = 22.dp
            )
            // Preview side panel toggle (fit-screen icon + chhota arrow)
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .clickable { showSide = !showSide },
                contentAlignment = Alignment.Center
            ) {
                BcIcon(
                    Icons.Outlined.FitScreen,
                    tint = if (showSide) c.accent else c.text,
                    size = 22.dp,
                    modifier = Modifier.offset(y = 3.dp)
                )
                BcIcon(
                    if (showSide) Icons.Outlined.ArrowDropUp else Icons.Outlined.ArrowDropDown,
                    tint = if (showSide) c.accent else c.text,
                    size = 16.dp,
                    modifier = Modifier.align(Alignment.TopCenter)
                )
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
            dpPerSec = dpPerSec,
            onZoom = { zoomBy(it) },
            onFit = { fitZoom() },
            onAdd = { addVideo() },
            onSelect = { selectedId = if (selectedId == it) null else it },
            onSeek = { ms ->
                posMs = ms
                seekGlobal(ms)
            },
            onUserDrag = { player.pause() },
            onTrim = { id, s, e -> trimClip(id, s, e) },
            onMarkerClick = { jumpTo(it.timeMs) }
        )

        // Clip select ho to uska toolbar, warna tool tabs
        if (selClip != null) {
            ClipBar(
                clip = selClip,
                onDone = { selectedId = null },
                onSpeed = { showSpeed = true },
                onSplit = { splitAtPlayhead() },
                onDuplicate = { duplicateSelected() },
                onDelete = { deleteSelected() },
                onSoon = { toast(it) }
            )
        } else {
            Column(Modifier.fillMaxWidth().background(c.surface)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    ToolTab.values().forEach { t ->
                        val sel = t == tab
                        Column(
                            Modifier
                                .width(76.dp)
                                .clickable { tab = t }
                                .padding(top = 8.dp, bottom = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box {
                                BcIcon(t.icon, tint = if (sel) c.accent else c.muted, size = 24.dp)
                                if (t.ai) {
                                    Text(
                                        "AI",
                                        fontSize = 8.sp,
                                        color = Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 8.dp, y = (-4).dp)
                                            .background(Color(0xFF3DE0FF), RoundedCornerShape(4.dp))
                                            .padding(horizontal = 3.dp)
                                    )
                                }
                            }
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
                            ClipAction(Icons.Outlined.VerticalSplit, "Split") { splitAtPlayhead() }
                            ClipAction(Icons.Outlined.Delete, "Delete") { deleteSelected() }
                            ClipAction(Icons.Outlined.BookmarkBorder, "Marker") { toggleMarker() }
                            ClipAction(Icons.Outlined.ChevronLeft, "Prev marker") { jumpMarker(false) }
                            ClipAction(Icons.Outlined.ChevronRight, "Next marker") { jumpMarker(true) }
                        }
                        ToolTab.Media -> Row(Modifier.horizontalScroll(rememberScrollState())) {
                            ClipAction(Icons.Outlined.Videocam, "Add video") { addVideo() }
                            ClipAction(Icons.Outlined.Image, "Add photo") { toast("Photo support Step 7 me") }
                            ClipAction(Icons.Outlined.Mic, "Record") { toast("Record aage ke Step me") }
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
        }
    }

    // ---- Grid sheet ----
    if (showGrid) {
        GridSheet(grid, onChange = { grid = it }, onDismiss = { showGrid = false })
    }

    // ---- Speed sheet ----
    if (showSpeed && selClip != null) {
        SpeedSheet(
            initial = selClip.speed,
            onApply = { applySpeed(it) },
            onDismiss = { showSpeed = false }
        )
    }

    // ---- Export dialogs ----
    if (showExport) {
        val src = videoSize ?: (1920 to 1080)
        ExportSheet(
            totalMs = totalMs(),
            srcW = src.first,
            srcH = src.second,
            initial = exportSettings,
            onStart = { s ->
                exportSettings = s
                showExport = false
                val (cw, ch) = canvasSize(src.first, src.second, s.shortSide)
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
private fun PreviewSideBar(
    modifier: Modifier,
    zoomPct: Int,
    gridOn: Boolean,
    lowQ: Boolean,
    outlinesOn: Boolean,
    onFull: () -> Unit,
    onLowQ: () -> Unit,
    onGrid: () -> Unit,
    onOutlines: () -> Unit,
    onCamera: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onZoomReset: () -> Unit
) {
    val c = Bc.c
    val white = Color(0xE6FFFFFF)
    Column(
        modifier
            .padding(6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x99000000)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        IconBtn(Icons.Outlined.Fullscreen, onFull, tint = white)
        IconBtn(Icons.Outlined.RoundedCorner, onLowQ, tint = if (lowQ) c.accent else white)
        IconBtn(Icons.Outlined.GridOn, onGrid, tint = if (gridOn) c.accent else white)
        IconBtn(Icons.Outlined.Layers, onOutlines, tint = if (outlinesOn) c.accent else white)
        // 3D camera view on/off (Step 10 me kaam karega)
        IconBtn(Icons.Outlined.VideocamOff, onCamera, tint = Color(0xFF9A9AB0))
        Spacer(Modifier.height(6.dp))
        IconBtn(Icons.Outlined.Add, onZoomIn, tint = white, size = 20.dp)
        Text(
            "$zoomPct%",
            color = white,
            fontSize = 11.sp,
            modifier = Modifier.clickable(onClick = onZoomReset).padding(vertical = 4.dp, horizontal = 4.dp)
        )
        IconBtn(Icons.Outlined.Remove, onZoomOut, tint = white, size = 20.dp)
        Spacer(Modifier.height(4.dp))
    }
}
