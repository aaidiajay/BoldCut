package com.boldframe.boldcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val prefs = remember { AppPrefs(ctx.applicationContext) }
    var mode by remember { mutableStateOf(prefs.themeMode) }
    var openId by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var crashText by remember {
        mutableStateOf(
            runCatching {
                val f = File(ctx.filesDir, "last_crash.txt")
                if (f.exists()) f.readText() else null
            }.getOrNull()
        )
    }

    BoldCutTheme(mode) {
        Box(Modifier.fillMaxSize()) {
            val id = openId
            if (id == null) {
                HomeScreen(
                    refreshTick = refreshTick,
                    onOpen = { openId = it },
                    onOpenSettings = { showSettings = true }
                )
            } else {
                EditorScreen(
                    projectId = id,
                    onBack = {
                        openId = null
                        refreshTick++
                    },
                    onOpenSettings = { showSettings = true }
                )
            }
            if (showSettings) {
                SettingsScreen(
                    mode = mode,
                    onMode = {
                        mode = it
                        prefs.themeMode = it
                    },
                    onClose = { showSettings = false }
                )
            }
        }

        val ct = crashText
        if (ct != null) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("App pichli baar crash hui") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "Is screen ka screenshot (ya Copy karke) bhej do:",
                            fontSize = 12.sp
                        )
                        Text(
                            ct.take(2500),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            lineHeight = 11.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(ct)) }) { Text("Copy") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        runCatching { File(ctx.filesDir, "last_crash.txt").delete() }
                        crashText = null
                    }) { Text("Close") }
                }
            )
        }
    }
}
