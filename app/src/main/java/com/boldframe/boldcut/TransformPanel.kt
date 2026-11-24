package com.boldframe.boldcut

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

private enum class TfMode(val icon: ImageVector) {
    Move(Icons.Outlined.OpenWith),
    Rotate(Icons.Outlined.RotateRight),
    Scale(Icons.Outlined.OpenInFull),
    Skew(Icons.Outlined.FormatItalic)
}

private fun f1(v: Float): String = String.format(Locale.US, "%.1f", v)

/**
 * Move & Transform panel (reference jaisa): Move / Rotate / Scale / Skew.
 * Values: ungli se swipe karke ya number pe tap karke type karke.
 */
@Composable
fun TransformPanel(
    clip: Clip,
    canvasW: Int,
    canvasH: Int,
    onChange: (Transform) -> Unit,
    onEnd: () -> Unit,
    onClose: () -> Unit,
    onSoon: (String) -> Unit
) {
    val c = Bc.c
    val tf = clip.transform
    val tfNow by rememberUpdatedState(tf)
    val change by rememberUpdatedState(onChange)
    val endNow by rememberUpdatedState(onEnd)

    var mode by remember { mutableStateOf(TfMode.Move) }
    var linked by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().height(236.dp).background(c.surface)) {
        // ---- Left column ----
        Column(
            Modifier.width(56.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            IconBtn(Icons.Outlined.ArrowBack, onClose)
            KeyframeButton { onSoon("Keyframes agle step (8B) me") }
            IconBtn(Icons.Outlined.Timeline, { onSoon("Easing curves keyframes ke saath aayenge") })
            Box {
                IconBtn(Icons.Outlined.MoreHoriz, { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Reset transform") },
                        onClick = {
                            menu = false
                            onChange(Transform())
                            onEnd()
                        }
                    )
                }
            }
        }

        // ---- Center ----
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(c.bg.copy(alpha = 0.5f))
                .padding(8.dp)
        ) {
            when (mode) {
                TfMode.Move -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("X", f1(tf.x * canvasW), Modifier.weight(1f)) { editing = "x" }
                        NumberField("Y", f1(tf.y * canvasH), Modifier.weight(1f)) { editing = "y" }
                        NumberField("Z", "0.0", Modifier.weight(0.8f), active = false) {
                            onSoon("Z (3D depth) Step 10 me")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ScrubPad(
                        "Swipe here to move layer",
                        Modifier.weight(1f).fillMaxWidth(),
                        onDrag = { dx, dy, w, h ->
                            val t = tfNow
                            change(t.copy(x = t.x + dx / w, y = t.y + dy / h))
                        },
                        onEnd = { endNow() }
                    )
                }

                TfMode.Scale -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        NumberField("Width", f1(canvasW * tf.scaleX), Modifier.weight(1f)) { editing = "w" }
                        Box(
                            Modifier
                                .padding(top = 2.dp)
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (linked) c.accent.copy(alpha = 0.25f) else c.surface2)
                                .clickable { linked = !linked },
                            contentAlignment = Alignment.Center
                        ) {
                            BcIcon(
                                if (linked) Icons.Outlined.Link else Icons.Outlined.LinkOff,
                                tint = if (linked) c.accent else c.text
                            )
                        }
                        NumberField("Height", f1(canvasH * tf.scaleY), Modifier.weight(1f)) { editing = "h" }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (linked) {
                        ScrubPad(
                            "Swipe to scale",
                            Modifier.weight(1f).fillMaxWidth(),
                            onDrag = { dx, _, w, _ ->
                                val t = tfNow
                                val k = exp(dx / (w / 2f))
                                change(
                                    t.copy(
                                        scaleX = (t.scaleX * k).coerceIn(0.01f, 50f),
                                        scaleY = (t.scaleY * k).coerceIn(0.01f, 50f)
                                    )
                                )
                            },
                            onEnd = { endNow() }
                        )
                    } else {
                        ScrubPad(
                            "Width",
                            Modifier.weight(1f).fillMaxWidth(),
                            onDrag = { dx, _, w, _ ->
                                val t = tfNow
                                change(t.copy(scaleX = (t.scaleX * exp(dx / (w / 2f))).coerceIn(0.01f, 50f)))
                            },
                            onEnd = { endNow() }
                        )
                        Spacer(Modifier.height(6.dp))
                        ScrubPad(
                            "Height",
                            Modifier.weight(1f).fillMaxWidth(),
                            onDrag = { dx, _, w, _ ->
                                val t = tfNow
                                change(t.copy(scaleY = (t.scaleY * exp(dx / (w / 2f))).coerceIn(0.01f, 50f)))
                            },
                            onEnd = { endNow() }
                        )
                    }
                }

                TfMode.Rotate -> {
                    RotationDial(
                        degrees = tf.rotation,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        onRotate = { d ->
                            val t = tfNow
                            change(t.copy(rotation = t.rotation + d))
                        },
                        onEnd = { endNow() },
                        onTapValue = { editing = "rot" }
                    )
                }

                TfMode.Skew -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("X SKEW", f1(tf.skewX) + "°", Modifier.weight(1f)) { editing = "kx" }
                        NumberField("Y SKEW", f1(tf.skewY) + "°", Modifier.weight(1f)) { editing = "ky" }
                    }
                    Spacer(Modifier.height(8.dp))
                    ScrubPad(
                        "X skew",
                        Modifier.weight(1f).fillMaxWidth(),
                        onDrag = { dx, _, w, _ ->
                            val t = tfNow
                            change(t.copy(skewX = (t.skewX + dx / w * 60f).coerceIn(-80f, 80f)))
                        },
                        onEnd = { endNow() }
                    )
                    Spacer(Modifier.height(6.dp))
                    ScrubPad(
                        "Y skew",
                        Modifier.weight(1f).fillMaxWidth(),
                        onDrag = { dx, _, w, _ ->
                            val t = tfNow
                            change(t.copy(skewY = (t.skewY + dx / w * 60f).coerceIn(-80f, 80f)))
                        },
                        onEnd = { endNow() }
                    )
                }
            }
        }

        // ---- Right column: modes ----
        Column(
            Modifier.width(60.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            TfMode.values().forEach { m ->
                val sel = m == mode
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) c.accent.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { mode = m },
                    contentAlignment = Alignment.Center
                ) { BcIcon(m.icon, tint = if (sel) c.accent else c.text, size = 26.dp) }
            }
        }
    }

    // ---- Number entry dialog ----
    editing?.let { key ->
        val (title, initial) = when (key) {
            "x" -> "X (px)" to f1(tf.x * canvasW)
            "y" -> "Y (px)" to f1(tf.y * canvasH)
            "w" -> "Width (px)" to f1(canvasW * tf.scaleX)
            "h" -> "Height (px)" to f1(canvasH * tf.scaleY)
            "rot" -> "Rotation (°)" to f1(tf.rotation)
            "kx" -> "X skew (°)" to f1(tf.skewX)
            else -> "Y skew (°)" to f1(tf.skewY)
        }
        var text by remember(key) { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.trim().toFloatOrNull()
                    if (v != null) {
                        val t = tfNow
                        val nt = when (key) {
                            "x" -> t.copy(x = v / canvasW)
                            "y" -> t.copy(y = v / canvasH)
                            "w" -> {
                                val ns = (v / canvasW).coerceIn(0.01f, 50f)
                                if (linked) {
                                    val k = ns / t.scaleX
                                    t.copy(scaleX = ns, scaleY = (t.scaleY * k).coerceIn(0.01f, 50f))
                                } else t.copy(scaleX = ns)
                            }
                            "h" -> {
                                val ns = (v / canvasH).coerceIn(0.01f, 50f)
                                if (linked) {
                                    val k = ns / t.scaleY
                                    t.copy(scaleY = ns, scaleX = (t.scaleX * k).coerceIn(0.01f, 50f))
                                } else t.copy(scaleY = ns)
                            }
                            "rot" -> t.copy(rotation = v)
                            "kx" -> t.copy(skewX = v.coerceIn(-80f, 80f))
                            else -> t.copy(skewY = v.coerceIn(-80f, 80f))
                        }
                        change(nt)
                        endNow()
                    }
                    editing = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    text: String,
    modifier: Modifier,
    active: Boolean = true,
    onClick: () -> Unit
) {
    val c = Bc.c
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface2)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text,
                color = if (active) c.accent else c.muted,
                fontSize = 18.sp,
                maxLines = 1
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = c.muted, fontSize = 11.sp)
    }
}

@Composable
private fun ScrubPad(
    hint: String,
    modifier: Modifier,
    onDrag: (Float, Float, Float, Float) -> Unit,
    onEnd: () -> Unit
) {
    val c = Bc.c
    val dragNow by rememberUpdatedState(onDrag)
    val endNow by rememberUpdatedState(onEnd)
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface2)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { endNow() },
                    onDragCancel = { endNow() }
                ) { change, amount ->
                    change.consume()
                    dragNow(amount.x, amount.y, size.width.toFloat(), size.height.toFloat())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(hint, color = c.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RotationDial(
    degrees: Float,
    modifier: Modifier,
    onRotate: (Float) -> Unit,
    onEnd: () -> Unit,
    onTapValue: () -> Unit
) {
    val c = Bc.c
    val rotNow by rememberUpdatedState(onRotate)
    val endNow by rememberUpdatedState(onEnd)
    var last by remember { mutableStateOf(0f) }
    val ringColor = c.surface2
    val knobColor = c.text

    Box(
        modifier.pointerInput(Unit) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            detectDragGestures(
                onDragStart = { off ->
                    last = Math.toDegrees(atan2((off.y - cy).toDouble(), (off.x - cx).toDouble())).toFloat()
                },
                onDragEnd = { endNow() },
                onDragCancel = { endNow() }
            ) { change, _ ->
                change.consume()
                val a = Math.toDegrees(
                    atan2((change.position.y - cy).toDouble(), (change.position.x - cx).toDouble())
                ).toFloat()
                var d = a - last
                if (d > 180f) d -= 360f
                if (d < -180f) d += 360f
                last = a
                rotNow(d)
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width, size.height) / 2f - 14.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(ringColor, radius = r, center = center, style = Stroke(4.dp.toPx()))
            val rad = Math.toRadians(degrees.toDouble())
            val k = Offset(
                center.x + r * cos(rad).toFloat(),
                center.y + r * sin(rad).toFloat()
            )
            drawCircle(knobColor, radius = 14.dp.toPx(), center = k)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(c.surface2)
                .clickable(onClick = onTapValue)
                .padding(horizontal = 22.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(f1(degrees) + "°", color = c.accent, fontSize = 26.sp)
        }
    }
}

@Composable
private fun KeyframeButton(onClick: () -> Unit) {
    val col = Bc.c.text
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(26.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w / 2f, 0f)
                lineTo(w, h / 2f)
                lineTo(w / 2f, h)
                lineTo(0f, h / 2f)
                close()
            }
            drawPath(path, col, style = Stroke(2.dp.toPx()))
            drawLine(col, Offset(w / 2f, h * 0.3f), Offset(w / 2f, h * 0.7f), 2.dp.toPx())
            drawLine(col, Offset(w * 0.3f, h / 2f), Offset(w * 0.7f, h / 2f), 2.dp.toPx())
        }
    }
}
