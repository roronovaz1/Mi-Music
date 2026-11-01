package com.dotmusic.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

object Sources {
    private val rc = ConcurrentHashMap<String, Pair<String, Long>>()
    private val yt get() = ServiceList.YouTube
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun fromItem(i: StreamInfoItem): Track = Track(
        id = "np:" + i.url,
        title = i.name.orEmpty(),
        artist = i.uploaderName.orEmpty().removeSuffix(" - Topic"),
        art = i.thumbnails.maxByOrNull { it.height }?.url,
        durationMs = maxOf(i.duration, 0L) * 1000,
        uri = i.url,
        src = "np"
    )

    fun fromInfo(i: StreamInfo): Track = Track(
        id = "np:" + i.url,
        title = i.name.orEmpty(),
        artist = i.uploaderName.orEmpty().removeSuffix(" - Topic"),
        art = i.thumbnails.maxByOrNull { it.height }?.url,
        durationMs = maxOf(i.duration, 0L) * 1000,
        uri = i.url,
        src = "np"
    )

    suspend fun searchYt(q: String): List<Track> = withContext(Dispatchers.IO) {
        for (filter in listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, YoutubeSearchQueryHandlerFactory.VIDEOS)) {
            val r = runCatching {
                val qh = yt.searchQHFactory.fromQuery(q, listOf(filter), "")
                SearchInfo.getInfo(yt, qh).relatedItems
                    .filterIsInstance<StreamInfoItem>().map { fromItem(it) }
            }.getOrDefault(emptyList())
            if (r.isNotEmpty()) return@withContext r
        }
        emptyList()
    }

    // ---- Audius (free, legal) ----
    private fun audiusTrack(o: JsonObject): Track? {
        val id = o["id"].str() ?: return null
        val art = (o["artwork"] as? JsonObject)?.let { it["480x480"].str() ?: it["150x150"].str() }
        val artist = ((o["user"] as? JsonObject)?.get("name")).str() ?: ""
        return Track(
            id = "au:$id",
            title = o["title"].str() ?: "Unknown",
            artist = artist,
            art = art,
            durationMs = (o["duration"].str()?.toLongOrNull() ?: 0L) * 1000,
            uri = "https://api.audius.co/v1/tracks/$id/stream?app_name=DotMusic",
            src = "au"
        )
    }

    private suspend fun fetchAudius(url: String): List<Track> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = Json.parseToJsonElement(Net.get(url)).jsonObject["data"] as JsonArray
            arr.mapNotNull { (it as? JsonObject)?.let { o -> audiusTrack(o) } }
        }.getOrDefault(emptyList())
    }

    suspend fun searchAudius(q: String) =
        fetchAudius("https://api.audius.co/v1/tracks/search?query=${enc(q)}&limit=20&app_name=DotMusic")

    suspend fun trendingAudius() =
        fetchAudius("https://api.audius.co/v1/tracks/trending?limit=20&app_name=DotMusic")

    // ---- Stream URL resolving (YouTube, SoundCloud, ...) ----
    fun resolveStream(url: String): String {
        val info = StreamInfo.getInfo(url)
        val a = info.audioStreams.filter { it.isUrl }
        val best = a.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
            ?: a.maxByOrNull { it.averageBitrate }
        return best?.content ?: error("No audio stream found")
    }

    fun resolveCached(url: String): String {
        val e = rc[url]
        if (e != null && e.second > System.currentTimeMillis()) return e.first
        val r = resolveStream(url)
        rc[url] = r to (System.currentTimeMillis() + 4 * 3600_000L)
        return r
    }

    fun evict(url: String) { rc.remove(url) }

    // ---- Local files ----
    fun scanLocal(ctx: Context): List<Track> {
        val out = ArrayList<Track>()
        val m = MediaStore.Audio.Media
        val proj = arrayOf(m._ID, m.TITLE, m.ARTIST, m.DURATION, m.ALBUM_ID)
        runCatching {
            ctx.contentResolver.query(
                m.EXTERNAL_CONTENT_URI, proj,
                "${m.IS_MUSIC}!=0 AND ${m.DURATION}>30000", null, "${m.TITLE} COLLATE NOCASE"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val album = c.getLong(4)
                    val uri = ContentUris.withAppendedId(m.EXTERNAL_CONTENT_URI, id).toString()
                    val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), album).toString()
                    out += Track(
                        id = "lo:$id", title = c.getString(1) ?: "Unknown",
                        artist = (c.getString(2) ?: "").let { if (it == "<unknown>") "" else it },
                        art = art, durationMs = c.getLong(3), uri = uri, src = "lo"
                    )
                }
            }
        }
        return out
    }
}
