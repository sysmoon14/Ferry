package dev.sysmoon.ferry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object FerryColors {
    val Navy = Color(0xFF0E1A2B)
    val Deep = Color(0xFF08111D)
    val Gold = Color(0xFFF2C14E)
    val Sea = Color(0xFF3FA7D6)
    val Muted = Color(0xFF8A99AD)
    val Text = Color(0xFFE8EDF3)
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
            onSurface = FerryColors.Text,
            onSurfaceVariant = FerryColors.Muted,
        ),
    ) {
        // tv-material Text defaults to LocalContentColor, which is black unless a Surface sets it.
        // Our screens draw their own backgrounds, so give every Text a light default here.
        CompositionLocalProvider(LocalContentColor provides FerryColors.Text, content = content)
    }
}
