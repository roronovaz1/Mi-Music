package com.dotmusic.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.*
import coil.compose.AsyncImage

val NRed = Color(0xFFD71921)
val Panel = Color(0xFF111111)
val Panel2 = Color(0xFF1B1B1B)
val Dim = Color(0xFF8C8C8C)
val Mono = FontFamily.Monospace

@Composable
fun DotTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent, onPrimary = Color.Black,
        secondary = accent, background = Color.Black, surface = Color.Black,
        onBackground = Color(0xFFF2F2F2), onSurface = Color(0xFFF2F2F2),
        surfaceVariant = Panel2, onSurfaceVariant = Dim, outline = Color(0xFF2A2A2A)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Dot-matrix background decoration. */
@Composable
fun DotGrid(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val step = 14.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            val a = (1f - y / size.height).coerceIn(0f, 1f) * 0.35f
            while (x < size.width) {
                drawCircle(color.copy(alpha = a), 1.5.dp.toPx(), Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), fontFamily = Mono, fontSize = 12.sp, letterSpacing = 2.sp, color = Dim)
    }
}

@Composable
fun Art(url: String?, modifier: Modifier, radius: Dp = 8.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(Panel2), contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = Dim)
    }
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) accent else Panel2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp, color = if (selected) Color.Black else Color(0xFFDDDDDD))
    }
}

/** Animated dot-matrix equalizer bars. */
@Composable
fun DotBars(active: Boolean, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    val t = rememberInfiniteTransition(label = "bars")
    val hs = (0 until 4).map { i ->
        t.animateFloat(
            0.2f, 1f,
            infiniteRepeatable(tween(450 + i * 140, easing = LinearEasing), RepeatMode.Reverse),
            label = "h$i"
        )
    }
    Canvas(modifier) {
        val cols = 4
        val rows = 5
        val cw = size.width / cols
        val rh = size.height / rows
        for (c in 0 until cols) {
            val level = if (active) (hs[c].value * rows).toInt().coerceAtLeast(1) else 1
            for (r in 0 until rows) {
                val on = (rows - 1 - r) < level
                drawCircle(
                    if (on) color else color.copy(alpha = 0.15f),
                    minOf(cw, rh) * 0.28f,
                    Offset(cw * c + cw / 2, rh * r + rh / 2)
                )
            }
        }
    }
}

/** Dotted seek bar (Nothing style). */
@Composable
fun DotSeekBar(progress: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val shown = drag ?: progress
    Canvas(
        modifier.fillMaxWidth().height(30.dp)
            .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let { f -> seek(f) }; drag = null },
                    onDragCancel = { drag = null }
                ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
    ) {
        val n = 52
        val gap = size.width / n
        val r = gap * 0.2f
        for (i in 0 until n) {
            val on = (i + 0.5f) / n <= shown
            drawCircle(if (on) accent else Color(0xFF3A3A3A), if (on) r * 1.2f else r, Offset(gap * i + gap / 2, size.height / 2))
        }
        drawCircle(Color.White, r * 2.4f, Offset((shown * size.width).coerceIn(r * 2.4f, size.width - r * 2.4f), size.height / 2))
    }
}
