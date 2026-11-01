package com.dotmusic.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player

enum class PMode { Art, Lyrics, Queue }

@Composable
fun FullPlayer(vm: MainVM, onClose: () -> Unit, onAdd: (Track) -> Unit) {
    val now by vm.now.collectAsState()
    val pos by vm.pos.collectAsState()
    val lib by vm.lib.collectAsState()
    val dl by vm.dl.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val t = now.track
    var mode by remember { mutableStateOf(PMode.Art) }
    val dur = if (pos.second > 0) pos.second else (t?.durationMs ?: 0L)
    val frac = if (dur > 0) (pos.first.toFloat() / dur).coerceIn(0f, 1f) else 0f

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.5f), Color(0xFF060606), Color.Black)))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        DotGrid(Modifier.fillMaxWidth().height(300.dp), Color.White)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(32.dp)) }
                Text(
                    "NOW PLAYING", Modifier.weight(1f), textAlign = TextAlign.Center,
                    fontFamily = Mono, fontSize = 12.sp, letterSpacing = 3.sp, color = Dim
                )
                IconButton({ t?.let(onAdd) }) { Icon(Icons.Rounded.PlaylistAdd, null) }
            }

            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                when (mode) {
                    PMode.Art -> BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val s = minOf(maxWidth, maxHeight)
                        Art(t?.art, Modifier.size(s), 22.dp)
                    }
                    PMode.Lyrics -> LyricsView(vm, pos.first)
                    PMode.Queue -> QueueView(vm, now.index)
                }
            }

            if (t != null) {
                val liked = lib.liked.any { it.id == t.id }
                val downloaded = lib.downloads.any { it.id == t.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, fontSize = 15.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton({ vm.download(t) }) {
                        val p = dl[t.id]
                        if (p != null) CircularProgressIndicator({ p / 100f }, Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
                        else Icon(if (downloaded) Icons.Rounded.DownloadDone else Icons.Rounded.Download, null, tint = if (downloaded) accent else Color.White)
                    }
                    IconButton({ vm.toggleLike(t) }) {
                        Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (liked) accent else Color.White)
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            DotSeekBar(frac, { vm.seekFrac(it) })
            Row(Modifier.fillMaxWidth()) {
                Text(fmtTime(pos.first), fontFamily = Mono, fontSize = 12.sp, color = Dim)
                Spacer(Modifier.weight(1f))
                Text(fmtTime(dur), fontFamily = Mono, fontSize = 12.sp, color = Dim)
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton({ vm.toggleShuffle() }) { Icon(Icons.Rounded.Shuffle, null, tint = if (now.shuffle) accent else Dim) }
                IconButton({ vm.prev() }) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(38.dp)) }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(accent).clickable { vm.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(42.dp), tint = Color.Black)
                }
                IconButton({ vm.next() }) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(38.dp)) }
                IconButton({ vm.cycleRepeat() }) {
                    Icon(
                        if (now.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, null,
                        tint = if (now.repeat != Player.REPEAT_MODE_OFF) accent else Dim
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
                Chip("ART", mode == PMode.Art) { mode = PMode.Art }
                Spacer(Modifier.width(8.dp))
                Chip("LYRICS", mode == PMode.Lyrics) { mode = PMode.Lyrics }
                Spacer(Modifier.width(8.dp))
                Chip("QUEUE", mode == PMode.Queue) { mode = PMode.Queue }
            }
        }
    }
}

@Composable
fun LyricsView(vm: MainVM, posMs: Long) {
    val ly by vm.lyrics.collectAsState()
    when (val l = ly) {
        LyricsState.Loading -> Text("LOADING LYRICS…", fontFamily = Mono, color = Dim, letterSpacing = 2.sp, fontSize = 12.sp)
        LyricsState.None -> Text("NO LYRICS FOUND", fontFamily = Mono, color = Dim, letterSpacing = 2.sp, fontSize = 12.sp)
        is LyricsState.Found -> {
            val lines = l.lyrics.synced
            if (lines.isNotEmpty()) {
                val cur = lines.indexOfLast { it.timeMs <= posMs + 300 }.coerceAtLeast(0)
                val st = rememberLazyListState()
                LaunchedEffect(cur) { st.animateScrollToItem((cur - 2).coerceAtLeast(0)) }
                LazyColumn(Modifier.fillMaxSize(), state = st, contentPadding = PaddingValues(vertical = 60.dp)) {
                    itemsIndexed(lines) { i, line ->
                        Text(
                            line.text.ifBlank { "♪" },
                            fontSize = if (i == cur) 26.sp else 21.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 32.sp,
                            color = if (i == cur) Color.White else Color(0x66FFFFFF),
                            modifier = Modifier.fillMaxWidth().clickable { vm.seekTo(line.timeMs) }.padding(vertical = 8.dp)
                        )
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { Text(l.lyrics.plain.orEmpty(), fontSize = 18.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium) }
                }
            }
        }
    }
}

@Composable
fun QueueView(vm: MainVM, current: Int) {
    val q by vm.queue.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val st = rememberLazyListState()
    LaunchedEffect(current) { if (current in q.indices) st.scrollToItem(current) }
    LazyColumn(Modifier.fillMaxSize(), state = st) {
        itemsIndexed(q) { i, t ->
            Row(Modifier.fillMaxWidth().clickable { vm.jump(i) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(t.art, Modifier.size(42.dp), 6.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (i == current) accent else Color.Unspecified, fontWeight = FontWeight.Medium)
                    Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dim, fontSize = 12.sp)
                }
            }
        }
    }
}
