package com.boldframe.boldcut

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode { SYSTEM, LIGHT, DARK }

class AppPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("boldcut_prefs", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(sp.getString("theme", "SYSTEM") ?: "SYSTEM")
        }.getOrDefault(ThemeMode.SYSTEM)
        set(v) {
            sp.edit().putString("theme", v.name).apply()
        }
}

@Immutable
class BcPalette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val stroke: Color,
    val timelineBg: Color,
    val tick: Color,
    val clipBg: Color,
    val previewBg: Color,
    val headerTop: Color,
    val headerMid: Color
)

val DarkPalette = BcPalette(
    dark = true,
    bg = Color(0xFF0B0B10),
    surface = Color(0xFF17171F),
    surface2 = Color(0xFF23232C),
    text = Color(0xFFFFFFFF),
    muted = Color(0xFF9A9FA8),
    accent = Color(0xFFFFC107),
    onAccent = Color(0xFF000000),
    stroke = Color(0xFF2E2E38),
    timelineBg = Color(0xFF121216),
    tick = Color(0xFF6B6F76),
    clipBg = Color(0xFF2A2A2E),
    previewBg = Color(0xFF000000),
    headerTop = Color(0xFF3B2A7A),
    headerMid = Color(0xFF1A1236)
)

val LightPalette = BcPalette(
    dark = false,
    bg = Color(0xFFF6F5FB),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEDEBF6),
    text = Color(0xFF14141A),
    muted = Color(0xFF6B6F7B),
    accent = Color(0xFFF5A800),
    onAccent = Color(0xFF000000),
    stroke = Color(0xFFDEDCEB),
    timelineBg = Color(0xFFECEAF4),
    tick = Color(0xFF8A8D99),
    clipBg = Color(0xFFD9D6E8),
    previewBg = Color(0xFF1A1A1F),
    headerTop = Color(0xFFD9D0FF),
    headerMid = Color(0xFFEDE8FF)
)

val LocalBc = staticCompositionLocalOf { DarkPalette }

object Bc {
    val c: BcPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalBc.current
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun BoldCutTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val pal = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = pal.accent,
            onPrimary = pal.onAccent,
            background = pal.bg,
            onBackground = pal.text,
            surface = pal.surface,
            onSurface = pal.text,
            surfaceVariant = pal.surface2,
            onSurfaceVariant = pal.muted
        )
    } else {
        lightColorScheme(
            primary = pal.accent,
            onPrimary = pal.onAccent,
            background = pal.bg,
            onBackground = pal.text,
            surface = pal.surface,
            onSurface = pal.text,
            surfaceVariant = pal.surface2,
            onSurfaceVariant = pal.muted
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                window.statusBarColor = pal.bg.toArgb()
                window.navigationBarColor = pal.surface.toArgb()
                val ctl = WindowCompat.getInsetsController(window, view)
                ctl.isAppearanceLightStatusBars = !dark
                ctl.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalBc provides pal) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

@Composable
fun BcIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Bc.c.text,
    size: Dp = 24.dp
) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

@Composable
fun IconBtn(
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = Bc.c.text,
    size: Dp = 24.dp
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        BcIcon(icon, tint = if (enabled) tint else tint.copy(alpha = 0.3f), size = size)
    }
}
