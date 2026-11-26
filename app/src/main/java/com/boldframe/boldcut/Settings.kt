package com.boldframe.boldcut

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(mode: ThemeMode, onMode: (ThemeMode) -> Unit, onClose: () -> Unit) {
    val c = Bc.c
    BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Outlined.ArrowBack, onClose)
            Spacer(Modifier.width(4.dp))
            Text("Settings", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionTitle("Appearance")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.surface)
            ) {
                val options = listOf(
                    Triple(ThemeMode.SYSTEM, Icons.Outlined.BrightnessAuto, "System default"),
                    Triple(ThemeMode.LIGHT, Icons.Outlined.LightMode, "Light"),
                    Triple(ThemeMode.DARK, Icons.Outlined.DarkMode, "Dark")
                )
                options.forEach { (m, icon, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onMode(m) }
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BcIcon(icon, tint = if (mode == m) c.accent else c.muted)
                        Spacer(Modifier.width(14.dp))
                        Text(
                            label,
                            color = c.text,
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (mode == m) BcIcon(Icons.Outlined.Check, tint = c.accent)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            SectionTitle("About")
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(c.surface)
                    .padding(16.dp)
            ) {
                Text("BoldCut", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Version 0.8.1", color = c.muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Bc.c.muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}
