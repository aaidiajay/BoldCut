package com.boldframe.boldcut

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fmtDur(ms: Long): String =
    String.format(Locale.US, "%02d:%02d", ms / 60000, (ms / 1000) % 60)

private fun defaultName(): String =
    "Project " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun HomeScreen(refreshTick: Int, onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val c = Bc.c
    val ctx = LocalContext.current
    val store = remember { ProjectStore(ctx.applicationContext) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var projects by remember { mutableStateOf(emptyList<ProjectMeta>()) }
    var busy by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectMeta?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectMeta?>(null) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    fun soon(what: String) = toast("$what jald aayega")
    fun refresh() {
        scope.launch { projects = withContext(Dispatchers.IO) { store.list() } }
    }

    LaunchedEffect(refreshTick) {
        projects = withContext(Dispatchers.IO) { store.list() }
    }

    BackHandler(enabled = tab != 0) { tab = 0 }

    fun startImport(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            busy = true
            val clips = layoutSequential(importClips(ctx, uris))
            if (clips.isEmpty()) {
                busy = false
                toast("Ye file load nahi ho payi (format support nahi ya kharab file)")
            } else {
                val p = withContext(Dispatchers.IO) { store.create(defaultName(), clips) }
                busy = false
                onOpen(p.id)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris -> startImport(uris) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> startImport(uris) }

    fun newVideo() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }

    fun importFiles() {
        filePicker.launch(arrayOf("*/*"))
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> EditTab(
                    store = store,
                    projects = projects,
                    onNewVideo = { newVideo() },
                    onImportFiles = { importFiles() },
                    onEditPhoto = { soon("Edit photo") },
                    onSeeAll = { tab = 2 },
                    onOpen = onOpen,
                    onSoon = { soon(it) },
                    onSettings = onOpenSettings
                )
                2 -> ProjectsTab(
                    store = store,
                    projects = projects,
                    onOpen = onOpen,
                    onRename = { renameTarget = it },
                    onDuplicate = { p ->
                        scope.launch {
                            withContext(Dispatchers.IO) { store.duplicate(p.id) }
                            refresh()
                        }
                    },
                    onDelete = { deleteTarget = it }
                )
                1 -> ComingSoonTab(Icons.Outlined.ViewQuilt, "Templates")
                3 -> ComingSoonTab(Icons.Outlined.Notifications, "Inbox")
                else -> ComingSoonTab(Icons.Outlined.Person, "Me")
            }

            if (busy) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xAA000000))
                        .clickable(onClick = {}),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = c.accent)
                        Spacer(Modifier.height(12.dp))
                        Text("Video load ho rahi hai...", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }

        NavigationBar(containerColor = c.surface, tonalElevation = 0.dp) {
            val items = listOf(
                Icons.Outlined.ContentCut to "Edit",
                Icons.Outlined.ViewQuilt to "Templates",
                Icons.Outlined.Folder to "Projects",
                Icons.Outlined.Notifications to "Inbox",
                Icons.Outlined.Person to "Me"
            )
            items.forEachIndexed { i, (icon, label) ->
                NavigationBarItem(
                    selected = tab == i,
                    onClick = { tab = i },
                    icon = { Icon(icon, contentDescription = null) },
                    label = { Text(label, fontSize = 10.sp, maxLines = 1, softWrap = false) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = c.accent,
                        selectedTextColor = c.accent,
                        unselectedIconColor = c.muted,
                        unselectedTextColor = c.muted,
                        indicatorColor = c.accent.copy(alpha = 0.16f)
                    )
                )
            }
        }
    }

    renameTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = text.trim()
                    if (n.isNotEmpty()) {
                        scope.launch {
                            withContext(Dispatchers.IO) { store.rename(target.id, n) }
                            refresh()
                        }
                    }
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Project delete karein?") },
            text = { Text("\"${target.name}\" hamesha ke liye hat jayega. Gallery ka original video safe rahega.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { store.delete(target.id) }
                        refresh()
                    }
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EditTab(
    store: ProjectStore,
    projects: List<ProjectMeta>,
    onNewVideo: () -> Unit,
    onImportFiles: () -> Unit,
    onEditPhoto: () -> Unit,
    onSeeAll: () -> Unit,
    onOpen: (String) -> Unit,
    onSoon: (String) -> Unit,
    onSettings: () -> Unit
) {
    val c = Bc.c
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(c.headerTop, c.headerMid, c.bg)))
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 16.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("BoldCut", color = c.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("Shoot. Edit. Create.", color = c.muted, fontSize = 14.sp)
                    }
                    IconBtn(Icons.Outlined.Settings, onSettings)
                }
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth().height(150.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(
                        Modifier
                            .weight(1.4f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFFFFD54F), Color(0xFFFF9800))
                                )
                            )
                            .clickable(onClick = onNewVideo),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) { BcIcon(Icons.Outlined.Add, tint = Color.White, size = 28.dp) }
                        Spacer(Modifier.height(10.dp))
                        Text("New video", color = Color.Black, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(24.dp))
                            .background(c.surface2)
                            .clickable(onClick = onEditPhoto),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BcIcon(Icons.Outlined.Image, tint = c.text, size = 30.dp)
                        Spacer(Modifier.height(10.dp))
                        Text("Edit photo", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("jald aayega", color = c.muted, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(c.surface2)
                        .clickable(onClick = onImportFiles)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BcIcon(Icons.Outlined.FolderOpen, tint = c.text, size = 24.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Files se import", color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("MKV, AVI, MOV, WebM... koi bhi format", color = c.muted, fontSize = 11.sp)
                    }
                }
            }
        }

        // Recent projects
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Recent projects",
                color = c.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text("See all", color = c.accent, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onSeeAll))
        }

        if (projects.isEmpty()) {
            Box(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.surface)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Abhi koi project nahi. \"New video\" dabake shuru karo.",
                    color = c.muted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects.take(8), key = { it.id }) { p ->
                    Column(Modifier.width(110.dp).clickable { onOpen(p.id) }) {
                        Box(Modifier.size(110.dp)) {
                            ProjectThumb(store, p, Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)))
                            Text(
                                fmtDur(p.durationMs),
                                color = Color.White,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(6.dp)
                                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            p.name,
                            color = c.text,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                item {
                    Box(
                        Modifier
                            .size(110.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(c.surface2)
                            .clickable(onClick = onSeeAll),
                        contentAlignment = Alignment.Center
                    ) { BcIcon(Icons.Outlined.ChevronRight, tint = c.text, size = 32.dp) }
                }
            }
        }

        // Tools
        Text(
            "Tools",
            color = c.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
        )
        val tools: List<Pair<ImageVector, String>> = listOf(
            Icons.Outlined.Bolt to "AutoCut",
            Icons.Outlined.AutoFixHigh to "Retouch",
            Icons.Outlined.AutoAwesome to "AI generator",
            Icons.Outlined.PhotoLibrary to "Photo tools",
            Icons.Outlined.MenuBook to "AI story maker",
            Icons.Outlined.Videocam to "Shoot and record"
        )
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            tools.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (icon, label) ->
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSoon(label) }
                                .padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                Modifier
                                    .size(58.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(c.surface)
                                    .border(1.dp, c.stroke, RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) { BcIcon(icon, tint = c.text, size = 26.dp) }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                label,
                                color = c.text,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                minLines = 2
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProjectsTab(
    store: ProjectStore,
    projects: List<ProjectMeta>,
    onOpen: (String) -> Unit,
    onRename: (ProjectMeta) -> Unit,
    onDuplicate: (ProjectMeta) -> Unit,
    onDelete: (ProjectMeta) -> Unit
) {
    val c = Bc.c
    Column(Modifier.fillMaxSize()) {
        Text(
            "Projects",
            color = c.text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 12.dp)
        )
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Abhi koi project nahi", color = c.muted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.id }) { p ->
                    ProjectRow(
                        store = store,
                        p = p,
                        onClick = { onOpen(p.id) },
                        onRename = { onRename(p) },
                        onDuplicate = { onDuplicate(p) },
                        onDelete = { onDelete(p) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectRow(
    store: ProjectStore,
    p: ProjectMeta,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    val c = Bc.c
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ProjectThumb(store, p, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                p.name,
                color = c.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text("${fmtDur(p.durationMs)} · ${p.clipCount} clip", color = c.muted, fontSize = 12.sp)
            Text(fmtDate(p.modifiedAt), color = c.muted, fontSize = 11.sp)
        }
        Box {
            IconBtn(Icons.Outlined.MoreVert, onClick = { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun ProjectThumb(store: ProjectStore, meta: ProjectMeta, modifier: Modifier) {
    val c = Bc.c
    val bmp by produceState<ImageBitmap?>(null, meta.id, meta.modifiedAt) {
        value = withContext(Dispatchers.IO) { store.loadThumb(meta.id)?.asImageBitmap() }
    }
    Box(modifier.background(c.surface2), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            BcIcon(Icons.Outlined.Videocam, tint = c.muted, size = 28.dp)
        }
    }
}

@Composable
private fun ComingSoonTab(icon: ImageVector, title: String) {
    val c = Bc.c
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BcIcon(icon, tint = c.muted, size = 44.dp)
            Spacer(Modifier.height(8.dp))
            Text(title, color = c.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("jald aayega", color = c.muted, fontSize = 13.sp)
        }
    }
}
