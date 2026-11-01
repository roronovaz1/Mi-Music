package com.dotmusic.app

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Now(
    val track: Track? = null,
    val playing: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = 0,
    val index: Int = 0
)

sealed interface ImportUi {
    object Idle : ImportUi
    data class Running(val done: Int, val total: Int) : ImportUi
    data class Done(val name: String, val count: Int, val missed: Int, val id: String) : ImportUi
    data class Failed(val msg: String) : ImportUi
}

class MainVM(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private var ctrl: MediaController? = null
    private var lastId: String? = null

    val queue = MutableStateFlow<List<Track>>(emptyList())
    val now = MutableStateFlow(Now())
    val pos = MutableStateFlow(0L to 0L)
    val accent = MutableStateFlow(Color(0xFFD71921))
    val lyrics = MutableStateFlow<LyricsState>(LyricsState.None)
    val lib = Store.data
    val dl = Downloads.progress

    val trending = MutableStateFlow<List<Track>>(emptyList())
    val discover = MutableStateFlow<List<Track>>(emptyList())
    val local = MutableStateFlow<List<Track>>(emptyList())

    val query = MutableStateFlow("")
    val ytRes = MutableStateFlow<List<Track>>(emptyList())
    val auRes = MutableStateFlow<List<Track>>(emptyList())
    val loRes = MutableStateFlow<List<Track>>(emptyList())
    val searching = MutableStateFlow(false)
    val searched = MutableStateFlow(false)

    val importState = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val showImport = MutableStateFlow(false)
    val toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val goSearch = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { ctrl?.let { sync(it) } }
    }

    init {
        connect()
        loadHome()
        loadLocal()
        viewModelScope.launch {
            while (true) {
                ctrl?.let { c ->
                    if (c.currentMediaItem != null) pos.value = c.currentPosition to maxOf(c.duration, 0L)
                }
                delay(300)
            }
        }
    }

    private fun connect() {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        f.addListener({
            runCatching {
                val c = f.get()
                ctrl = c
                c.addListener(listener)
                sync(c)
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun sync(c: MediaController) {
        val t = queue.value.firstOrNull { it.id == c.currentMediaItem?.mediaId }
        now.value = Now(
            t,
            c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
            c.shuffleModeEnabled, c.repeatMode, c.currentMediaItemIndex
        )
        if (t?.id != lastId) {
            lastId = t?.id
            if (t != null) onTrackChanged(t)
        }
    }

    private fun onTrackChanged(t: Track) {
        Store.update { it.copy(recent = (listOf(t) + it.recent.filter { x -> x.id != t.id }).take(30)) }
        // lyrics
        lyrics.value = LyricsState.Loading
        viewModelScope.launch {
            val l = runCatching { LyricsRepo.get(t) }.getOrNull()
            if (lastId == t.id) lyrics.value = if (l == null) LyricsState.None else LyricsState.Found(l)
        }
        // dynamic accent from album art
        viewModelScope.launch(Dispatchers.IO) {
            var color = Color(0xFFD71921)
            if (t.art != null) runCatching {
                val req = ImageRequest.Builder(ctx).data(t.art).allowHardware(false).size(200).build()
                val bmp = ((ctx.imageLoader.execute(req) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                val p = bmp?.let { Palette.from(it).maximumColorCount(16).generate() }
                val sw = p?.vibrantSwatch ?: p?.lightVibrantSwatch ?: p?.dominantSwatch
                if (sw != null) {
                    var rgb = sw.rgb
                    if (ColorUtils.calculateLuminance(rgb) < 0.28) rgb = ColorUtils.blendARGB(rgb, android.graphics.Color.WHITE, 0.4f)
                    color = Color(rgb)
                }
            }
            accent.value = color
        }
        // pre-resolve upcoming YouTube streams so next song starts instantly
        val q = queue.value
        val i = q.indexOfFirst { it.id == t.id }
        if (i >= 0) q.drop(i + 1).take(2).filter { it.src == "np" }.forEach { n ->
            viewModelScope.launch(Dispatchers.IO) { runCatching { Sources.resolveCached(n.uri) } }
        }
    }

    private fun item(t: Track, downloaded: Set<String>): MediaItem {
        val uri = when {
            t.id in downloaded && Downloads.exists(t) -> Uri.fromFile(Downloads.file(t))
            t.src == "np" -> Uri.parse("np://t?u=" + Uri.encode(t.uri))
            else -> Uri.parse(t.uri)
        }
        return MediaItem.Builder()
            .setMediaId(t.id)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist)
                    .setArtworkUri(t.art?.let { Uri.parse(it) }).build()
            ).build()
    }

    // ---------- playback ----------
    fun play(list: List<Track>, index: Int) {
        val c = ctrl ?: run { toast.tryEmit("Player is starting, try again"); return }
        if (list.isEmpty()) return
        val dls = lib.value.downloads.map { it.id }.toSet()
        queue.value = list
        c.setMediaItems(list.map { item(it, dls) }, index.coerceIn(0, list.lastIndex), 0L)
        c.prepare()
        c.play()
    }

    fun playNext(t: Track) {
        val c = ctrl ?: return
        if (c.mediaItemCount == 0) { play(listOf(t), 0); return }
        val at = c.currentMediaItemIndex + 1
        c.addMediaItem(at, item(t, lib.value.downloads.map { it.id }.toSet()))
        queue.update { q -> q.toMutableList().also { it.add(at.coerceAtMost(it.size), t) } }
        toast.tryEmit("Playing next")
    }

    fun enqueue(t: Track) {
        val c = ctrl ?: return
        if (c.mediaItemCount == 0) { play(listOf(t), 0); return }
        c.addMediaItem(item(t, lib.value.downloads.map { it.id }.toSet()))
        queue.update { it + t }
        toast.tryEmit("Added to queue")
    }

    fun toggle() { ctrl?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { ctrl?.seekToNextMediaItem() }
    fun prev() {
        ctrl?.let { if (it.currentPosition > 4000) it.seekTo(0) else it.seekToPreviousMediaItem() }
    }
    fun jump(i: Int) { ctrl?.let { it.seekToDefaultPosition(i); it.play() } }
    fun seekFrac(f: Float) { ctrl?.let { c -> val d = c.duration; if (d > 0) c.seekTo((d * f).toLong()) } }
    fun seekTo(ms: Long) { ctrl?.seekTo(ms) }
    fun toggleShuffle() { ctrl?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        ctrl?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // ---------- browse ----------
    fun loadHome() {
        viewModelScope.launch { trending.value = Sources.trendingAudius() }
        viewModelScope.launch { discover.value = Sources.searchYt("top hits 2026") }
    }

    fun loadLocal() {
        val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(ctx, perm) != PackageManager.PERMISSION_GRANTED) return
        viewModelScope.launch { local.value = withContext(Dispatchers.IO) { Sources.scanLocal(ctx) } }
    }

    fun submit(q: String) {
        val s = q.trim()
        if (s.isEmpty()) return
        if (s.startsWith("http://") || s.startsWith("https://")) importFrom(s) else search(s)
    }

    fun search(q: String) {
        query.value = q
        searched.value = true
        searching.value = true
        loRes.value = local.value.filter { it.title.contains(q, true) || it.artist.contains(q, true) }
        viewModelScope.launch {
            coroutineScope {
                launch { ytRes.value = Sources.searchYt(q) }
                launch { auRes.value = Sources.searchAudius(q) }
            }
            searching.value = false
        }
    }

    fun mood(q: String) { goSearch.tryEmit(Unit); search(q) }

    // ---------- library ----------
    fun toggleLike(t: Track) = Store.update { l ->
        if (l.liked.any { it.id == t.id }) l.copy(liked = l.liked.filter { it.id != t.id })
        else l.copy(liked = listOf(t) + l.liked)
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): String {
        val id = System.currentTimeMillis().toString()
        Store.update { it.copy(playlists = it.playlists + Playlist(id, name, tracks)) }
        return id
    }

    fun addToPlaylist(pid: String, t: Track) = Store.update { l ->
        l.copy(playlists = l.playlists.map { p ->
            if (p.id == pid && p.tracks.none { it.id == t.id }) p.copy(tracks = p.tracks + t) else p
        })
    }

    fun removeFromPlaylist(pid: String, t: Track) = Store.update { l ->
        l.copy(playlists = l.playlists.map { p -> if (p.id == pid) p.copy(tracks = p.tracks.filter { it.id != t.id }) else p })
    }

    fun deletePlaylist(pid: String) = Store.update { l -> l.copy(playlists = l.playlists.filter { it.id != pid }) }

    fun download(t: Track) {
        if (t.src == "lo") { toast.tryEmit("Already on your phone"); return }
        if (lib.value.downloads.any { it.id == t.id }) { Downloads.remove(t); toast.tryEmit("Download removed"); return }
        if (Downloads.progress.value.containsKey(t.id)) return
        toast.tryEmit("Downloading…")
        viewModelScope.launch {
            val ok = Downloads.download(t)
            toast.tryEmit(if (ok) "Downloaded: ${t.title}" else "Download failed")
        }
    }

    // ---------- import ----------
    fun importFrom(text: String) {
        showImport.value = true
        importState.value = ImportUi.Running(0, 0)
        viewModelScope.launch {
            runCatching { Importer.run(text) { d, n -> importState.value = ImportUi.Running(d, n) } }
                .onSuccess { r ->
                    if (r.tracks.isEmpty()) importState.value = ImportUi.Failed("No songs could be matched")
                    else {
                        val id = createPlaylist(r.name, r.tracks)
                        importState.value = ImportUi.Done(r.name, r.tracks.size, r.missed, id)
                        if (!showImport.value) toast.tryEmit("Imported ${r.tracks.size} songs: ${r.name}")
                    }
                }
                .onFailure { importState.value = ImportUi.Failed(it.message ?: "Import failed") }
        }
    }

    fun closeImport() {
        showImport.value = false
        if (importState.value !is ImportUi.Running) importState.value = ImportUi.Idle
    }

    fun resetImport() { importState.value = ImportUi.Idle }

    override fun onCleared() {
        ctrl?.removeListener(listener)
        ctrl?.release()
        super.onCleared()
    }
}
