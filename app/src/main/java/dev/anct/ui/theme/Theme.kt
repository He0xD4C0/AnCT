package dev.anct.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF006B5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2E1),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    tertiary = Color(0xFF456179),
    error = Color(0xFFBA1A1A),
    surface = Color(0xFFF7FAF8),
    surfaceVariant = Color(0xFFDAE5E1),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF82D5C5),
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF005047),
    onPrimaryContainer = Color(0xFF9EF2E1),
    secondary = Color(0xFFB1CCC5),
    tertiary = Color(0xFFADCAE5),
    error = Color(0xFFFFB4AB),
    surface = Color(0xFF0E1513),
    surfaceVariant = Color(0xFF3F4946),
)

@Composable
fun AnctTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}

