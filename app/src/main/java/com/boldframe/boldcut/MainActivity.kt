package com.boldframe.boldcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }
}

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx.applicationContext) }
    var mode by remember { mutableStateOf(prefs.themeMode) }
    var openId by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

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
    }
}
