package com.boldframe.boldcut

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    var openId by remember { mutableStateOf<String?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val id = openId
    if (id == null) {
        HomeScreen(refreshTick = refreshTick, onOpen = { openId = it })
    } else {
        EditorScreen(
            projectId = id,
            onBack = {
                openId = null
                refreshTick++
            }
        )
    }
}
