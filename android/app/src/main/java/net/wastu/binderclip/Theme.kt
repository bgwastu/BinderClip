package net.wastu.binderclip

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DebugLightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF0066CC),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD6E4FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001C3D),
    secondary = androidx.compose.ui.graphics.Color(0xFF007ACC),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD0E4FF),
)

private val DebugDarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF99CBFF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF00325B),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF004880),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD6E4FF),
    secondary = androidx.compose.ui.graphics.Color(0xFF82B1FF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF003B71),
)

@Composable
fun BinderClipTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        BuildConfig.DEBUG && dark -> DebugDarkColorScheme
        BuildConfig.DEBUG -> DebugLightColorScheme
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        // Edge-to-edge is enforced on API 35+ and these calls are deprecated
        // there (and no-ops). Only apply legacy bar colors below that.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = colors.background.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colors.background.toArgb()
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
