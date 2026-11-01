package com.dotmusic.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Dest(val label: String) { Home("HOME"), Search("SEARCH"), Library("LIBRARY"), More("MORE") }

@Composable
fun DotApp(vm: MainVM) {
    var dest by remember { mutableStateOf(Dest.Home) }
    var full by remember { mutableStateOf(false) }
    var openPl by remember { mutableStateOf<String?>(null) }
    var addTarget by remember { mutableStateOf<Track?>(null) }
    val now by vm.now.collectAsState()
    val showImport by vm.showImport.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.toast.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }
    LaunchedEffect(Unit) { vm.goSearch.collect { dest = Dest.Search; openPl = null } }
    BackHandler(enabled = full || openPl != null) { if (full) full = false else openPl = null }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Scaffold(
            containerColor = Color.Black,
            bottomBar = {
                Column {
                    if (now.track != null) MiniPlayer(vm) { full = true }
                    NavBar(dest) { dest = it; openPl = null }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                val pl = openPl
                if (pl != null) PlaylistScreen(vm, pl, { openPl = null }, { addTarget = it })
                else when (dest) {
                    Dest.Home -> HomeScreen(vm) { addTarget = it }
                    Dest.Search -> SearchScreen(vm) { addTarget = it }
                    Dest.Library -> LibraryScreen(vm, { openPl = it }, { addTarget = it })
                    Dest.More -> MoreScreen()
                }
            }
        }
        AnimatedVisibility(full, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            FullPlayer(vm, { full = false }, { addTarget = it })
        }
    }
    addTarget?.let { AddToPlaylistDialog(vm, it) { addTarget = null } }
    if (showImport) ImportDialog(vm) { id -> vm.closeImport(); openPl = id; dest = Dest.Library }
}

@Composable
fun NavBar(sel: Dest, onSel: (Dest) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    NavigationBar(containerColor = Color.Black, tonalElevation = 0.dp) {
        Dest.values().forEach { d ->
            NavigationBarItem(
                selected = sel == d,
                onClick = { onSel(d) },
                icon = {
                    Icon(
                        when (d) {
                            Dest.Home -> Icons.Rounded.Home
                            Dest.Search -> Icons.Rounded.Search
                            Dest.Library -> Icons.Rounded.LibraryMusic
                            Dest.More -> Icons.Rounded.Tune
                        }, null
                    )
                },
                label = { Text(d.label, fontFamily = Mono, fontSize = 10.sp, letterSpacing = 1.sp) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = Color.Transparent, selectedIconColor = accent, selectedTextColor = accent,
                    unselectedIconColor = Dim, unselectedTextColor = Dim
                )
            )
        }
    }
}

@Composable
fun MiniPlayer(vm: MainVM, onOpen: () -> Unit) {
    val now by vm.now.collectAsState()
    val pos by vm.pos.collectAsState()
    val t = now.track ?: return
    val accent = MaterialTheme.colorScheme.primary
    val dur = if (pos.second > 0) pos.second else t.durationMs
    val frac = if (dur > 0) (pos.first.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().background(Color(0xFF0C0C0C)).clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(t.art, Modifier.size(44.dp), 6.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dim, fontSize = 12.sp)
            }
            DotBars(now.playing, accent)
            IconButton({ vm.toggle() }) {
                Icon(if (now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(30.dp))
            }
            IconButton({ vm.next() }) { Icon(Icons.Rounded.SkipNext, null) }
        }
        LinearProgressIndicator({ frac }, Modifier.fillMaxWidth().height(2.dp), color = accent, trackColor = Color(0xFF222222))
    }
}

@Composable
fun TrackRow(vm: MainVM, list: List<Track>, index: Int, onAdd: (Track) -> Unit, onRemove: (() -> Unit)? = null) {
    val t = list[index]
    val now by vm.now.collectAsState()
    val lib by vm.lib.collectAsState()
    val dl by vm.dl.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val cur = now.track?.id == t.id
    var menu by remember { mutableStateOf(false) }
    val liked = lib.liked.any { it.id == t.id }
    val downloaded = lib.downloads.any { it.id == t.id }
    Row(
        Modifier.fillMaxWidth().clickable { vm.play(list, index) }.padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box {
            Art(t.art, Modifier.size(50.dp), 6.dp)
            if (cur) Box(Modifier.matchParentSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                DotBars(now.playing, accent, Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp,
                fontWeight = FontWeight.Medium, color = if (cur) accent else Color.Unspecified
            )
            Text(
                listOf(t.artist, t.srcLabel(), if (t.durationMs > 0) fmtTime(t.durationMs) else "").filter { it.isNotBlank() }
                    .joinToString("  ·  "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = Dim, fontSize = 12.sp
            )
        }
        dl[t.id]?.let {
            CircularProgressIndicator({ it / 100f }, Modifier.size(18.dp), color = accent, strokeWidth = 2.dp)
        }
        if (downloaded) Icon(Icons.Rounded.DownloadDone, null, tint = accent, modifier = Modifier.size(18.dp))
        Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, null, tint = Dim) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Play next") }, { menu = false; vm.playNext(t) })
                DropdownMenuItem({ Text("Add to queue") }, { menu = false; vm.enqueue(t) })
                DropdownMenuItem({ Text("Add to playlist") }, { menu = false; onAdd(t) })
                DropdownMenuItem({ Text(if (liked) "Remove like" else "Like") }, { menu = false; vm.toggleLike(t) })
                if (t.src != "lo") DropdownMenuItem(
                    { Text(if (downloaded) "Remove download" else "Download") }, { menu = false; vm.download(t) }
                )
                if (onRemove != null) DropdownMenuItem({ Text("Remove from playlist") }, { menu = false; onRemove() })
            }
        }
    }
}

@Composable
fun CardRow(vm: MainVM, list: List<Track>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(list) { i, t ->
            Column(Modifier.width(140.dp).clickable { vm.play(list, i) }) {
                Art(t.art, Modifier.size(140.dp), 10.dp)
                Spacer(Modifier.height(6.dp))
                Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(t.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = Dim)
            }
        }
    }
}

@Composable
fun HomeScreen(vm: MainVM, onAdd: (Track) -> Unit) {
    val lib by vm.lib.collectAsState()
    val trending by vm.trending.collectAsState()
    val discover by vm.discover.collectAsState()
    val moods = listOf(
        "Energize" to "energetic songs", "Relax" to "relaxing songs", "Workout" to "workout music",
        "Focus" to "focus music", "Party" to "party hits", "Sad" to "sad songs",
        "Romance" to "romantic songs", "Bengali" to "bengali songs", "Hindi" to "hindi hits"
    )
    val disc = discover.take(12)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(110.dp)) {
                DotGrid(Modifier.fillMaxSize(), Color.White)
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text("DOT MUSIC", Modifier.weight(1f), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = 3.sp)
                    IconButton({ vm.showImport.value = true }) { Icon(Icons.Rounded.Link, null) }
                }
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(moods) { m -> Chip(m.first) { vm.mood(m.second) } }
            }
        }
        if (lib.recent.isNotEmpty()) { item { SectionTitle("Recently played") }; item { CardRow(vm, lib.recent) } }
        if (lib.liked.isNotEmpty()) { item { SectionTitle("Liked songs") }; item { CardRow(vm, lib.liked) } }
        if (trending.isNotEmpty()) { item { SectionTitle("Trending on Audius") }; item { CardRow(vm, trending) } }
        if (disc.isNotEmpty()) {
            item { SectionTitle("Discover") }
            itemsIndexed(disc) { i, _ -> TrackRow(vm, disc, i, onAdd) }
        }
    }
}

@Composable
fun SearchScreen(vm: MainVM, onAdd: (Track) -> Unit) {
    val q by vm.query.collectAsState()
    val yt by vm.ytRes.collectAsState()
    val au by vm.auRes.collectAsState()
    val lo by vm.loRes.collectAsState()
    val searching by vm.searching.collectAsState()
    val searched by vm.searched.collectAsState()
    var filter by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val accent = MaterialTheme.colorScheme.primary
    val sections = buildList {
        if ((filter == 0 || filter == 1) && yt.isNotEmpty()) add("YouTube" to yt.take(20))
        if ((filter == 0 || filter == 2) && au.isNotEmpty()) add("Audius" to au.take(20))
        if ((filter == 0 || filter == 3) && lo.isNotEmpty()) add("On your phone" to lo.take(20))
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = q, onValueChange = { vm.query.value = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
            placeholder = { Text("Search, or paste a playlist link") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton({ vm.query.value = "" }) { Icon(Icons.Rounded.Close, null) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); vm.submit(q) }),
            shape = RoundedCornerShape(50),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent, unfocusedBorderColor = Color(0xFF2A2A2A),
                focusedContainerColor = Panel, unfocusedContainerColor = Panel
            )
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("ALL", "YOUTUBE", "AUDIUS", "LOCAL").withIndex().toList()) { (i, l) -> Chip(l, filter == i) { filter = i } }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (searching) item {
                Text("SEARCHING…", Modifier.padding(16.dp), fontFamily = Mono, color = Dim, fontSize = 12.sp, letterSpacing = 2.sp)
            }
            sections.forEach { (title, list) ->
                item { SectionTitle(title) }
                itemsIndexed(list) { i, _ -> TrackRow(vm, list, i, onAdd) }
            }
            if (!searching && searched && sections.isEmpty()) item {
                Text("NO RESULTS", Modifier.padding(24.dp), fontFamily = Mono, color = Dim, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
fun TrackList(vm: MainVM, list: List<Track>, empty: String, onAdd: (Track) -> Unit, onRemove: ((Track) -> Unit)? = null) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(empty, color = Dim, fontFamily = Mono, textAlign = TextAlign.Center, fontSize = 13.sp)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { PlayShuffleRow(vm, list) }
        itemsIndexed(list) { i, t -> TrackRow(vm, list, i, onAdd, onRemove?.let { f -> { f(t) } }) }
    }
}

@Composable
fun PlayShuffleRow(vm: MainVM, list: List<Track>) {
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button({ vm.play(list, 0) }, colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)) {
            Icon(Icons.Rounded.PlayArrow, null); Text("PLAY", fontFamily = Mono, letterSpacing = 1.sp)
        }
        OutlinedButton({ vm.play(list.shuffled(), 0) }) {
            Icon(Icons.Rounded.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("SHUFFLE", fontFamily = Mono, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun LibraryScreen(vm: MainVM, onOpenPl: (String) -> Unit, onAdd: (Track) -> Unit) {
    val lib by vm.lib.collectAsState()
    val local by vm.local.collectAsState()
    var sub by remember { mutableIntStateOf(0) }
    var newName by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text("LIBRARY", Modifier.padding(16.dp), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 3.sp)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("PLAYLISTS", "LIKED", "DOWNLOADS", "LOCAL").withIndex().toList()) { (i, l) -> Chip(l, sub == i) { sub = i } }
        }
        Spacer(Modifier.height(8.dp))
        when (sub) {
            0 -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton({ newName = "" }) { Icon(Icons.Rounded.Add, null); Text("NEW", fontFamily = Mono) }
                        OutlinedButton({ vm.showImport.value = true }) { Icon(Icons.Rounded.Link, null); Spacer(Modifier.width(6.dp)); Text("IMPORT", fontFamily = Mono) }
                    }
                }
                items(lib.playlists.reversed()) { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenPl(p.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Art(p.tracks.firstOrNull()?.art, Modifier.size(56.dp), 8.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(p.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${p.tracks.size} songs", color = Dim, fontSize = 12.sp)
                        }
                    }
                }
                if (lib.playlists.isEmpty()) item {
                    Text("No playlists yet.\nCreate one, or import from a link.", Modifier.padding(24.dp), color = Dim, fontFamily = Mono, fontSize = 13.sp)
                }
            }
            1 -> TrackList(vm, lib.liked, "NO LIKED SONGS YET", onAdd)
            2 -> TrackList(vm, lib.downloads, "NO DOWNLOADS YET.\nUse the ⋮ menu on a song.", onAdd)
            else -> TrackList(vm, local, "NO LOCAL SONGS FOUND.\nAllow music permission and restart the app.", onAdd)
        }
    }
    newName?.let { n ->
        AlertDialog(
            onDismissRequest = { newName = null }, containerColor = Panel,
            title = { Text("NEW PLAYLIST", fontFamily = Mono) },
            text = { OutlinedTextField(n, { newName = it }, singleLine = true, label = { Text("Name") }) },
            confirmButton = { TextButton({ if (n.isNotBlank()) vm.createPlaylist(n.trim()); newName = null }) { Text("CREATE") } },
            dismissButton = { TextButton({ newName = null }) { Text("CANCEL") } }
        )
    }
}

@Composable
fun PlaylistScreen(vm: MainVM, id: String, onBack: () -> Unit, onAdd: (Track) -> Unit) {
    val lib by vm.lib.collectAsState()
    val p = lib.playlists.firstOrNull { it.id == id }
    if (p == null) { LaunchedEffect(Unit) { onBack() }; return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.tracks.size} songs", color = Dim, fontSize = 12.sp, fontFamily = Mono)
            }
            IconButton({ vm.deletePlaylist(id) }) { Icon(Icons.Rounded.Delete, null, tint = Dim) }
        }
        TrackList(vm, p.tracks, "EMPTY PLAYLIST", onAdd) { vm.removeFromPlaylist(id, it) }
    }
}

@Composable
fun MoreScreen() {
    val accent = MaterialTheme.colorScheme.primary
    val left by Bridge.sleepLeft.collectAsState()
    val eot by Bridge.sleepEndOfTrack.collectAsState()
    val on by Bridge.eqOn.collectAsState()
    val lv by Bridge.bandLevels.collectAsState()
    val bass by Bridge.bassLevel.collectAsState()
    var cacheMb by remember { mutableLongStateOf(App.cache.cacheSpace / 1_000_000) }
    val sc = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent, inactiveTrackColor = Panel2)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("MORE", Modifier.padding(16.dp), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 3.sp) }
        item { SectionTitle("Sleep timer") }
        item {
            val status = when {
                left != null -> "Stops in ${fmtTime(left!!)}"
                eot -> "Stops after this song"
                else -> "Off"
            }
            Text(status, Modifier.padding(horizontal = 16.dp), color = if (left != null || eot) accent else Dim, fontFamily = Mono)
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Chip("OFF", left == null && !eot) { Bridge.startSleep(0) } }
                items(listOf(15, 30, 45, 60)) { m -> Chip("$m MIN") { Bridge.startSleep(m) } }
                item { Chip("END OF SONG", eot) { Bridge.sleepAtTrackEnd() } }
            }
        }
        item { SectionTitle("Equalizer") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Enable", Modifier.weight(1f))
                Switch(on, { Bridge.setEqOn(it) }, colors = SwitchDefaults.colors(checkedTrackColor = accent, checkedThumbColor = Color.Black))
            }
        }
        if (lv.isEmpty()) item {
            Text("Play a song first, then the equalizer appears here.", Modifier.padding(16.dp), color = Dim, fontSize = 13.sp)
        } else {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Bridge.presets.withIndex().toList()) { (i, n) -> Chip(n.uppercase()) { Bridge.usePreset(i) } }
                }
            }
            itemsIndexed(lv) { i, v ->
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${Bridge.bandFreqs.getOrNull(i) ?: 0} Hz", Modifier.width(80.dp), fontFamily = Mono, fontSize = 12.sp, color = Dim)
                    Slider(
                        v.toFloat(), { Bridge.setBand(i, it.toInt()) },
                        valueRange = Bridge.bandRange.first.toFloat()..Bridge.bandRange.second.toFloat(), colors = sc
                    )
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("BASS", Modifier.width(80.dp), fontFamily = Mono, fontSize = 12.sp, color = Dim)
                Slider(bass.toFloat(), { Bridge.setBass(it.toInt()) }, valueRange = 0f..1000f, colors = sc)
            }
        }
        item { SectionTitle("Storage") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Stream cache: $cacheMb MB", Modifier.weight(1f), color = Dim, fontFamily = Mono, fontSize = 13.sp)
                OutlinedButton({
                    App.cache.keys.toList().forEach { runCatching { App.cache.removeResource(it) } }
                    cacheMb = App.cache.cacheSpace / 1_000_000
                }) { Text("CLEAR", fontFamily = Mono) }
            }
        }
    }
}

@Composable
fun AddToPlaylistDialog(vm: MainVM, track: Track, onDone: () -> Unit) {
    val lib by vm.lib.collectAsState()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone, containerColor = Panel,
        title = { Text("ADD TO PLAYLIST", fontFamily = Mono, fontSize = 16.sp) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                lib.playlists.forEach { p ->
                    Text(
                        p.name, Modifier.fillMaxWidth().clickable { vm.addToPlaylist(p.id, track); onDone() }.padding(vertical = 12.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("New playlist") })
            }
        },
        confirmButton = {
            TextButton({ if (name.isNotBlank()) { vm.addToPlaylist(vm.createPlaylist(name.trim()), track); onDone() } }) { Text("CREATE + ADD") }
        },
        dismissButton = { TextButton(onDone) { Text("CLOSE") } }
    )
}

@Composable
fun ImportDialog(vm: MainVM, onOpen: (String) -> Unit) {
    val st by vm.importState.collectAsState()
    var text by remember { mutableStateOf("") }
    val s = st
    AlertDialog(
        onDismissRequest = { vm.closeImport() }, containerColor = Panel,
        title = { Text("IMPORT PLAYLIST", fontFamily = Mono, fontSize = 16.sp) },
        text = {
            Column {
                when (s) {
                    is ImportUi.Idle -> {
                        Text("Paste a Spotify, Deezer, YouTube, SoundCloud or Apple Music link, or a list of songs (one per line).", color = Dim, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(text, { text = it }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                    }
                    is ImportUi.Running -> {
                        Text(if (s.total == 0) "Reading link…" else "Matching songs… ${s.done}/${s.total}")
                        Spacer(Modifier.height(12.dp))
                        if (s.total == 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator({ s.done / s.total.toFloat() }, Modifier.fillMaxWidth())
                    }
                    is ImportUi.Done -> Text("Imported \"${s.name}\": ${s.count} songs" + if (s.missed > 0) " (${s.missed} not found)" else "")
                    is ImportUi.Failed -> Text(s.msg, color = Color(0xFFFF8A8A))
                }
            }
        },
        confirmButton = {
            when (s) {
                is ImportUi.Idle -> TextButton({ vm.importFrom(text) }, enabled = text.isNotBlank()) { Text("IMPORT") }
                is ImportUi.Done -> TextButton({ onOpen(s.id) }) { Text("OPEN") }
                is ImportUi.Failed -> TextButton({ vm.resetImport() }) { Text("TRY AGAIN") }
                else -> {}
            }
        },
        dismissButton = { TextButton({ vm.closeImport() }) { Text("CLOSE") } }
    )
}
