@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package dev.sysmoon.ferry.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Shapes
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import dev.sysmoon.ferry.R

// ---------------------------------------------------------------------------------------------
// Design tokens. Gold is the one accent: focus, selection and primary actions. Everything else
// is a quiet navy so the picture and the programme titles are what stand out.
// ---------------------------------------------------------------------------------------------

object FerryColors {
    /** App background. */
    val Deep = Color(0xFF0A1322)
    /** Cards, chips, guide cells. */
    val Navy = Color(0xFF142034)
    /** A raised surface (dialogs, the focused row's neighbour). */
    val Raised = Color(0xFF1C2B45)
    /** Fill behind the focused row / cell. */
    val FocusFill = Color(0xFF26395A)
    /** Accent: focus ring, selected item, primary button, brand. */
    val Gold = Color(0xFFF2C14E)
    /** Informational: catch-up, previews, notices. */
    val Sea = Color(0xFF4FB3E0)
    /** Live: the dot and tag on whatever is on air. */
    val Live = Color(0xFFFF5A5F)
    val Text = Color(0xFFF3F6FA)
    val Muted = Color(0xFFA6B2C4)
    val Faint = Color(0xFF6C7A90)
    /** Hairline borders on dark surfaces. */
    val Line = Color(0x1AFFFFFF)
}

// Inter, bundled (variable: one file covers every weight; opsz 32 for headings).
private val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold),
)
private fun display(weight: Int) = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", 32f))
private val InterDisplay = FontFamily(
    Font(R.font.inter, FontWeight.Medium, variationSettings = display(500)),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = display(600)),
    Font(R.font.inter, FontWeight.Bold, variationSettings = display(700)),
)

/** Lining, tabular figures: clocks and channel numbers line up. */
const val TABULAR = "tnum, lnum"

private fun t(family: FontFamily, size: Int, weight: FontWeight, line: Int, tracking: Float = 0f, features: String? = null) =
    TextStyle(
        fontFamily = family, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp,
        letterSpacing = tracking.sp, fontFeatureSettings = features,
    )

val FerryTypography = Typography(
    displayLarge = t(InterDisplay, 64, FontWeight.SemiBold, 72, -1.5f, TABULAR),
    displayMedium = t(InterDisplay, 52, FontWeight.SemiBold, 60, -1f, TABULAR),
    displaySmall = t(InterDisplay, 40, FontWeight.SemiBold, 48, -0.6f, TABULAR),
    headlineLarge = t(InterDisplay, 34, FontWeight.SemiBold, 40, -0.5f),
    headlineMedium = t(InterDisplay, 28, FontWeight.SemiBold, 34, -0.4f),
    headlineSmall = t(InterDisplay, 24, FontWeight.SemiBold, 30, -0.3f),
    titleLarge = t(InterDisplay, 22, FontWeight.SemiBold, 28, -0.2f),
    titleMedium = t(Inter, 18, FontWeight.SemiBold, 24, -0.1f),
    titleSmall = t(Inter, 16, FontWeight.SemiBold, 22),
    bodyLarge = t(Inter, 17, FontWeight.Normal, 24),
    bodyMedium = t(Inter, 15, FontWeight.Normal, 21),
    bodySmall = t(Inter, 13, FontWeight.Normal, 18),
    labelLarge = t(Inter, 15, FontWeight.SemiBold, 20),
    labelMedium = t(Inter, 13, FontWeight.SemiBold, 16, 0.2f, TABULAR),
    labelSmall = t(Inter, 11, FontWeight.Bold, 14, 1.1f, TABULAR),
)

val FerryShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FerryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FerryColors.Gold,
            onPrimary = FerryColors.Deep,
            secondary = FerryColors.Sea,
            onSecondary = FerryColors.Deep,
            background = FerryColors.Deep,
            onBackground = FerryColors.Text,
            surface = FerryColors.Navy,
            onSurface = FerryColors.Text,
            surfaceVariant = FerryColors.Raised,
            onSurfaceVariant = FerryColors.Muted,
            border = FerryColors.Line,
            borderVariant = FerryColors.Line,
            scrim = Color.Black,
        ),
        typography = FerryTypography,
        shapes = FerryShapes,
    ) {
        // tv-material Text defaults to LocalContentColor, which is black unless a Surface sets it.
        // Our screens draw their own backgrounds, so give every Text a light default here.
        CompositionLocalProvider(LocalContentColor provides FerryColors.Text, content = content)
    }
}

// ---------------------------------------------------------------------------------------------
// Component defaults: one focus treatment everywhere — a gold ring on a lifted navy fill, no
// bounce. Selected (the row playing, the section you're in) is gold text on a quiet fill.
// ---------------------------------------------------------------------------------------------

val RowShape = RoundedCornerShape(12.dp)
private val FocusRing = Border(BorderStroke(2.dp, FerryColors.Gold), shape = RowShape)

@Composable
fun ferryListItemColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = FerryColors.Text,
    focusedContainerColor = FerryColors.FocusFill,
    focusedContentColor = FerryColors.Text,
    selectedContainerColor = FerryColors.Navy,
    selectedContentColor = FerryColors.Gold,
    focusedSelectedContainerColor = FerryColors.FocusFill,
    focusedSelectedContentColor = FerryColors.Gold,
    pressedContainerColor = FerryColors.FocusFill,
    pressedContentColor = FerryColors.Text,
)

@Composable
fun ferryListItemBorder() = ListItemDefaults.border(focusedBorder = FocusRing, focusedSelectedBorder = FocusRing)

@Composable
fun ferryListItemShape() = ListItemDefaults.shape(shape = RowShape)

@Composable
fun ferryListItemScale() = ListItemDefaults.scale(scale = 1f, focusedScale = 1.015f, focusedSelectedScale = 1.015f, pressedScale = 1f)

/** Primary button: gold when focused. */
@Composable
fun ferryButtonColors() = ButtonDefaults.colors(
    containerColor = FerryColors.Raised,
    contentColor = FerryColors.Text,
    focusedContainerColor = FerryColors.Gold,
    focusedContentColor = FerryColors.Deep,
    pressedContainerColor = FerryColors.Gold,
    pressedContentColor = FerryColors.Deep,
)

@Composable
fun ferryOutlinedButtonColors() = OutlinedButtonDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = FerryColors.Text,
    focusedContainerColor = FerryColors.Gold,
    focusedContentColor = FerryColors.Deep,
    pressedContainerColor = FerryColors.Gold,
    pressedContentColor = FerryColors.Deep,
)

/** Quiet (tab-like) button: no fill until focused, gold when selected. */
@Composable
fun ferryOutlinedButtonColorsAsButton() = ButtonDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = FerryColors.Muted,
    focusedContainerColor = FerryColors.Gold,
    focusedContentColor = FerryColors.Deep,
    pressedContainerColor = FerryColors.Gold,
    pressedContentColor = FerryColors.Deep,
)

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

/**
 * Small uppercase status tag. [filled] = solid colour with dark text (strong: LIVE, WATCHING);
 * otherwise a tinted outline (quiet: ENDED, LATER). [dot] puts a pulse-free dot first (LIVE).
 */
@Composable
fun Tag(text: String, color: Color = FerryColors.Muted, filled: Boolean = true, dot: Boolean = false, upper: Boolean = true, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (filled) color else color.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (filled) FerryColors.Deep else color))
            Spacer(Modifier.width(5.dp))
        }
        Text(
            if (upper) text.uppercase() else text,
            color = if (filled) FerryColors.Deep else color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A channel logo on a small dark tile; falls back to the first letters of the name. */
@Composable
fun LogoChip(logo: String?, name: String, width: androidx.compose.ui.unit.Dp = 72.dp, height: androidx.compose.ui.unit.Dp = 44.dp, modifier: Modifier = Modifier) {
    Box(
        modifier.size(width, height).clip(RoundedCornerShape(if (height < 30.dp) 5.dp else 8.dp)).background(FerryColors.Raised),
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) AsyncImage(model = logo, contentDescription = null, modifier = Modifier.padding(if (height < 30.dp) 2.dp else 5.dp))
        else Text(
            name.trim().take(3).uppercase(),
            color = FerryColors.Muted,
            style = if (height < 30.dp) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        )
    }
}

/** 4dp progress line, gold (live) or sea (catch-up). */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = FerryColors.Gold) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.14f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).background(color))
    }
}

/** Key hints at the bottom of a panel: "OK  watch  ·  ▲▼  channels". */
@Composable
fun Hint(text: String, color: Color = FerryColors.Faint, modifier: Modifier = Modifier) {
    Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** Screen heading with an icon, the same on every secondary screen. */
@Composable
fun ScreenTitle(text: String, icon: ImageVector? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = FerryColors.Gold, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(text, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Centred empty / loading message with an icon. */
@Composable
fun EmptyMessage(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(FerryColors.Navy), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = FerryColors.Muted, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(body, color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(max = 520.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Every pop-up: a raised card with a gold eyebrow, title, and whatever [content] needs. */
@Composable
fun FerryDialog(
    onDismiss: () -> Unit,
    eyebrow: String? = null,
    eyebrowIcon: ImageVector? = null,
    title: String? = null,
    subtitle: String? = null,
    width: androidx.compose.ui.unit.Dp = 600.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier
                .width(width)
                .clip(FerryShapes.large)
                .background(FerryColors.Navy)
                .border(BorderStroke(1.dp, FerryColors.Line), FerryShapes.large)
                .padding(horizontal = 28.dp, vertical = 24.dp)
        ) {
            if (eyebrow != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (eyebrowIcon != null) {
                        Icon(eyebrowIcon, null, tint = FerryColors.Gold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(eyebrow.uppercase(), color = FerryColors.Gold, style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
            }
            if (title != null) Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = FerryColors.Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            content()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Icons. material-icons-core covers the common ones; the rest are Material Symbols paths.
// ---------------------------------------------------------------------------------------------

private fun icon(name: String, vararg paths: String, evenOdd: Boolean = false): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    for (d in paths) b.addPath(
        PathParser().parsePathString(d).toNodes(),
        fill = SolidColor(Color.White),
        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
    )
    return b.build()
}

object FerryIcons {
    val Search = Icons.Default.Search
    val Star = Icons.Default.Star
    val Settings = Icons.Default.Settings
    val List = Icons.Default.List
    val Play = Icons.Default.PlayArrow
    val Refresh = Icons.Default.Refresh
    val Info = Icons.Default.Info
    val Warning = Icons.Default.Warning
    val Bell = Icons.Default.Notifications
    val Check = Icons.Default.Check
    val Close = Icons.Default.Close
    val ChevronRight = Icons.Default.KeyboardArrowRight
    val ChevronLeft = Icons.Default.KeyboardArrowLeft
    val Lock = Icons.Default.Lock

    val StarOutline = icon("StarOutline", "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4.28L12 15.4z")
    val Pause = icon("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z")
    val Replay = icon("Replay", "M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z")
    val Forward = icon("FastForward", "M4 18l8.5-6L4 6v12zm9-12v12l8.5-6L13 6z")
    val Rewind = icon("FastRewind", "M11 18V6l-8.5 6 8.5 6zm.5-6l8.5 6V6l-8.5 6z")
    val SkipPrevious = icon("SkipPrevious", "M6 6h2v12H6zm3.5 6l8.5 6V6z")
    /** Flip back to the channel watched before (a return arrow). */
    val LastChannel = icon("LastChannel", "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z")
    val LiveTv = icon("LiveTv", "M21 6h-7.59l3.29-3.29L16 2l-4 4-4-4-.71.71L10.59 6H3c-1.1 0-2 .89-2 2v12c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V8c0-1.11-.9-2-2-2zm0 14H3V8h18v12zM9 10v8l7-4z")
    val Grid = icon("GridView", "M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z")
    val Moon = icon("Bedtime", "M12.34 2.02C6.59 1.82 2 6.42 2 12c0 5.52 4.48 10 10 10 3.71 0 6.93-2.02 8.66-5.02-7.51-.25-12.09-8.43-8.32-14.96z")
    val History = icon("History", "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z")
    val Clock = icon("Schedule", "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z")
    val Tv = icon("Tv", "M21 3H3c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h5v2h8v-2h5c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 14H3V5h18v12z")
    val Download = icon("Download", "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z")
    val Layers = icon("Layers", "M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z")
    val Key = icon("Key", "M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z")
    val LockOpen = icon("LockOpen", "M12 17c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm6-9h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6h1.9c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm0 12H6V10h12v10z")
    val Phone = icon("Smartphone", "M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z")
    val Football = ImageVector.Builder("Football", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(PathParser().parsePathString("M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20zm0 2a8 8 0 1 1 0 16a8 8 0 1 1 0-16z").toNodes(),
            fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd)
        addPath(PathParser().parsePathString("M12 8.8l3.04 2.21-1.16 3.58h-3.76l-1.16-3.58z").toNodes(), fill = SolidColor(Color.White))
        addPath(PathParser().parsePathString("M12 8.8V4.4M15.04 11.01l3.8-1.24M13.88 14.59l2.37 3.26M10.12 14.59l-2.37 3.26M8.96 11.01l-3.8-1.24").toNodes(),
            stroke = SolidColor(Color.White), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round)
    }.build()
}

/** The Ferry mark (the little ferry on two waves), drawn as a vector so it's crisp at any size. */
val BrandMark: ImageVector = ImageVector.Builder("Ferry", 96.dp, 96.dp, 120f, 120f).apply {
    group(translationX = 14f, translationY = 4f, scaleX = 0.77f, scaleY = 0.77f) {
        fun fill(d: String, color: Color, alpha: Float = 1f) =
            addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(color), fillAlpha = alpha)
        fill("M58 22h12l-2 14H60z", FerryColors.Gold)
        fill("M57 20h14a1.5 1.5 0 0 1 1.5 1.5v1A1.5 1.5 0 0 1 71 24H57a1.5 1.5 0 0 1-1.5-1.5v-1A1.5 1.5 0 0 1 57 20z", Color(0xFFE05A47))
        fill("M42 36h36a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H42a4 4 0 0 1-4-4v-8a4 4 0 0 1 4-4z", Color(0xFFE8EDF3))
        for (x in listOf(43f, 53f, 63f)) fill("M${x + 1.5f} 40.5h3a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-3a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5z", Color(0xFF0E1A2B))
        fill("M74.5 40.5h2a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-2a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5z", Color(0xFF0E1A2B))
        fill("M22 52h76l-9 16c-1.5 2.6-4.2 4-7.2 4H38.2c-3 0-5.7-1.4-7.2-4z", FerryColors.Gold)
        addPath(PathParser().parsePathString("M27 58h66").toNodes(), stroke = SolidColor(Color(0xFF0E1A2B)), strokeAlpha = 0.35f,
            strokeLineWidth = 2.4f, strokeLineCap = StrokeCap.Round)
        addPath(PathParser().parsePathString("M14 80c6-5 12-5 18 0s12 5 18 0 12-5 18 0 12 5 18 0 12-5 18 0").toNodes(),
            stroke = SolidColor(FerryColors.Sea), strokeLineWidth = 5f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        addPath(PathParser().parsePathString("M24 92c6-4 12-4 18 0s12 4 18 0 12-4 18 0 12 4 18 0").toNodes(),
            stroke = SolidColor(FerryColors.Sea), strokeAlpha = 0.55f, strokeLineWidth = 4f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
}.build()
