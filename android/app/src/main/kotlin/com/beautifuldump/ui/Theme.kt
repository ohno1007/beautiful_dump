package com.beautifuldump.ui

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

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    secondary = Color(0xFFB8C6E0),
    tertiary = Color(0xFFEFB8C8),
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF2E5BE0),
    secondary = Color(0xFF565E71),
    tertiary = Color(0xFF705574),
)

@Composable
fun BeautifulDumpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
