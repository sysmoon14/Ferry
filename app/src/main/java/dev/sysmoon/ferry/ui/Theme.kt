package dev.sysmoon.ferry.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object FerryColors {
    val Navy = Color(0xFF0E1A2B)
    val Deep = Color(0xFF08111D)
    val Gold = Color(0xFFF2C14E)
    val Sea = Color(0xFF3FA7D6)
    val Muted = Color(0xFF8A99AD)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FerryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FerryColors.Gold,
            onPrimary = FerryColors.Deep,
            secondary = FerryColors.Sea,
            background = FerryColors.Deep,
            surface = FerryColors.Navy,
            onSurface = Color(0xFFE8EDF3),
            onSurfaceVariant = FerryColors.Muted,
        ),
        content = content,
    )
}
