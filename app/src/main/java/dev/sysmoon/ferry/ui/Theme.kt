package dev.sysmoon.ferry.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.OutlinedButtonDefaults
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

// ---- Shared component styling: navy surfaces, gold focus, so focus and selection read clearly on a TV.

private val FocusFill = Color(0xFF1B2D47)

@Composable
fun ferryListItemColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = FerryColors.Text,
    focusedContainerColor = FocusFill,
    focusedContentColor = FerryColors.Text,
    selectedContainerColor = FerryColors.Navy,
    selectedContentColor = FerryColors.Gold,
    focusedSelectedContainerColor = FocusFill,
    focusedSelectedContentColor = FerryColors.Gold,
)

@Composable
fun ferryListItemBorder() = ListItemDefaults.border(
    focusedBorder = Border(BorderStroke(2.dp, FerryColors.Gold), shape = RoundedCornerShape(10.dp)),
    focusedSelectedBorder = Border(BorderStroke(2.dp, FerryColors.Gold), shape = RoundedCornerShape(10.dp)),
)

@Composable
fun ferryButtonColors() = ButtonDefaults.colors(
    containerColor = FerryColors.Navy,
    contentColor = FerryColors.Text,
    focusedContainerColor = FerryColors.Gold,
    focusedContentColor = FerryColors.Deep,
)

@Composable
fun ferryOutlinedButtonColors() = OutlinedButtonDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = FerryColors.Text,
    focusedContainerColor = FerryColors.Gold,
    focusedContentColor = FerryColors.Deep,
)
