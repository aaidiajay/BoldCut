package com.boldframe.boldcut

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

// ---------------- Grid overlay ----------------

enum class GridMode(val label: String) {
    OFF("Grid off"),
    THIRDS("Rule of thirds"),
    CENTER("Center lines"),
    GRID4("4×4 grid"),
    SAFE("Safe zones")
}

@Composable
fun GridOverlay(mode: GridMode, aspect: Float, modifier: Modifier) {
    if (mode == GridMode.OFF) return
    Canvas(modifier) {
        val boxW = size.width
        val boxH = size.height
        var w = boxW
        var h = w / aspect
        if (h > boxH) {
            h = boxH
            w = h * aspect
        }
        val left = (boxW - w) / 2f
        val top = (boxH - h) / 2f
        val col = Color(0xAAFFFFFF)
        val sw = 1.dp.toPx()

        fun vline(f: Float) =
            drawLine(col, Offset(left + w * f, top), Offset(left + w * f, top + h), sw)

        fun hline(f: Float) =
            drawLine(col, Offset(left, top + h * f), Offset(left + w, top + h * f), sw)

        when (mode) {
            GridMode.THIRDS -> {
                vline(1f / 3f); vline(2f / 3f)
                hline(1f / 3f); hline(2f / 3f)
            }
            GridMode.CENTER -> {
                vline(0.5f); hline(0.5f)
            }
            GridMode.GRID4 -> {
                for (i in 1..3) {
                    vline(i / 4f)
                    hline(i / 4f)
                }
            }
            GridMode.SAFE -> {
                drawRect(
                    col,
                    topLeft = Offset(left + w * 0.05f, top + h * 0.05f),
                    size = Size(w * 0.9f, h * 0.9f),
                    style = Stroke(sw)
                )
                drawRect(
                    col,
                    topLeft = Offset(left + w * 0.10f, top + h * 0.10f),
                    size = Size(w * 0.8f, h * 0.8f),
                    style = Stroke(sw)
                )
            }
            GridMode.OFF -> {}
        }
    }
}

// ---------------- Speed sheet ----------------

@Composable
fun SpeedSheet(initial: Float, onApply: (Float) -> Unit, onDismiss: () -> Unit) {
    val c = Bc.c
    var speed by remember { mutableStateOf(initial) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = c.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                    Text("Speed", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        String.format(Locale.US, "%.2fx", speed),
                        color = c.accent,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = log10(speed),
                        onValueChange = {
                            val v = 10f.pow(it)
                            speed = ((v * 20f).roundToInt() / 20f).coerceIn(0.1f, 10f)
                        },
                        valueRange = -1f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = c.accent,
                            activeTrackColor = c.accent
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0.1x", color = c.muted, fontSize = 11.sp)
                        Text("1x", color = c.muted, fontSize = 11.sp)
                        Text("10x", color = c.muted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 5f, 10f).forEach { v ->
                            val label = if (v == v.toInt().toFloat()) "${v.toInt()}x" else "${v}x"
                            OptChip(label, speed == v) { speed = v }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { onApply(speed) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = c.accent,
                            contentColor = c.onAccent
                        )
                    ) { Text("Apply", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ---------------- Clip toolbar (clip select karne par) ----------------

@Composable
fun ClipAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Bc.c
    Column(
        Modifier
            .width(76.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BcIcon(icon, tint = c.text, size = 24.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = c.muted, maxLines = 1)
    }
}

@Composable
fun ClipBar(
    clip: Clip,
    onDone: () -> Unit,
    onSpeed: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onSoon: (String) -> Unit
) {
    val c = Bc.c
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBtn(Icons.Outlined.Close, onDone, size = 20.dp)
            Text(
                "Clip  ·  " + String.format(Locale.US, "%.2fx", clip.speed),
                color = c.muted,
                fontSize = 12.sp
            )
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            ClipAction(Icons.Outlined.Speed, "Speed", onSpeed)
            ClipAction(Icons.Outlined.VerticalSplit, "Split", onSplit)
            ClipAction(Icons.Outlined.ContentCopy, "Duplicate", onDuplicate)
            ClipAction(Icons.Outlined.Delete, "Delete", onDelete)
            ClipAction(Icons.Outlined.VolumeUp, "Volume") { onSoon("Volume Step 11 me") }
            ClipAction(Icons.Outlined.OpenWith, "Transform") { onSoon("Move & Transform Step 8 me") }
            ClipAction(Icons.Outlined.Opacity, "Blend") { onSoon("Blending & Opacity Step 9 me") }
            ClipAction(Icons.Outlined.FormatColorFill, "Color") { onSoon("Color & Fill Step 9 me") }
            ClipAction(Icons.Outlined.BorderStyle, "Border") { onSoon("Border & Shadow Step 9 me") }
            ClipAction(Icons.Outlined.Category, "Shape") { onSoon("Edit Shape Step 9 me") }
            ClipAction(Icons.Outlined.StarBorder, "Presets") { onSoon("Presets Step 12 me") }
            ClipAction(Icons.Outlined.AutoAwesome, "Effects") { onSoon("Effects Step 12 me") }
        }
        Spacer(Modifier.height(4.dp))
    }
}
