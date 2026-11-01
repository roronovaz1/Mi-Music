package com.dotmusic.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.atomic.AtomicInteger

object Importer {
    class Result(val name: String, val tracks: List<Track>, val missed: Int)

    private val shortHosts = listOf("spotify.link", "spoti.fi", "link.tospotify.com", "deezer.page.link", "link.deezer.com", "dzr.page.link")

    suspend fun run(input: String, progress: (Int, Int) -> Unit): Result = withContext(Dispatchers.IO) {
        val text = input.trim()
        val raw = Regex("""https?://\S+""").find(text)?.value
            ?: return@withContext fromQueries(
                "Imported list",
                text.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(200),
                progress
            )
        val url = if (shortHosts.any { raw.contains(it) }) Net.finalUrl(raw) else raw
        when {
            "spotify.com" in url -> spotify(url, progress)
            "deezer.com" in url -> deezer(url, progress)
            "youtube.com" in url || "youtu.be" in url || "soundcloud.com" in url || "bandcamp.com" in url -> newpipe(url)
            else -> generic(url, progress)
        }
    }

    private fun findKey(e: JsonElement, key: String): JsonElement? = when (e) {
        is JsonObject -> e[key] ?: e.values.firstNotNullOfOrNull { findKey(it, key) }
        is JsonArray -> e.firstNotNullOfOrNull { findKey(it, key) }
        else -> null
    }

    private suspend fun fromQueries(name: String, queries: List<String>, progress: (Int, Int) -> Unit): Result {
        if (queries.isEmpty()) error("Nothing to import")
        val sem = Semaphore(4)
        val done = AtomicInteger(0)
        progress(0, queries.size)
        val res = coroutineScope {
            queries.map { q ->
                async {
                    sem.withPermit {
                        val r = Sources.searchYt(q).firstOrNull() ?: Sources.searchAudius(q).firstOrNull()
                        progress(done.incrementAndGet(), queries.size)
                        r
                    }
                }
            }.awaitAll()
        }
        return Result(name, res.filterNotNull().distinctBy { it.id }, res.count { it == null })
    }

    private suspend fun spotify(u: String, progress: (Int, Int) -> Unit): Result {
        val m = Regex("""spotify\.com/(?:intl-[a-zA-Z-]+/)?(playlist|album|track)/([A-Za-z0-9]+)""").find(u)
            ?: error("Spotify link not recognised (playlist, album or track links work)")
        val (type, id) = m.destructured
        val html = Net.get("https://open.spotify.com/embed/$type/$id")
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("Could not read the Spotify page. Paste the song names instead.")
        val root = Json.parseToJsonElement(json)
        val entity = findKey(root, "entity") as? JsonObject
        val name = entity?.get("name").str() ?: entity?.get("title").str() ?: "Spotify import"
        val list = findKey(root, "trackList") as? JsonArray
        val queries = if (list != null) {
            list.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val t = o["title"].str() ?: return@mapNotNull null
                "$t ${o["subtitle"].str().orEmpty()}".trim()
            }
        } else {
            val artists = (entity?.get("artists") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("name").str() }
                ?.joinToString(", ").orEmpty()
            listOf("$name $artists".trim())
        }
        return fromQueries(name, queries.take(200), progress)
    }

    private suspend fun deezer(u: String, progress: (Int, Int) -> Unit): Result {
        val m = Regex("""deezer\.com/(?:[a-z]{2}/)?(playlist|album|track)/(\d+)""").find(u)
            ?: error("Deezer link not recognised")
        val (type, id) = m.destructured
        val main = Json.parseToJsonElement(Net.get("https://api.deezer.com/$type/$id")).jsonObject
        val name = main["title"].str() ?: "Deezer import"
        val queries = if (type == "track") {
            listOf("$name ${(main["artist"] as? JsonObject)?.get("name").str().orEmpty()}")
        } else {
            val tr = Json.parseToJsonElement(Net.get("https://api.deezer.com/$type/$id/tracks?limit=200")).jsonObject
            (tr["data"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                "${o["title"].str().orEmpty()} ${(o["artist"] as? JsonObject)?.get("name").str().orEmpty()}".trim()
            }
        }
        return fromQueries(name, queries, progress)
    }

    private fun newpipe(u: String): Result {
        val url = u.replace("music.youtube.com", "www.youtube.com")
        val isList = "list=" in url || "/playlist" in url || "/sets/" in url || "/album" in url
        if (isList) {
            val r = runCatching {
                val service = NewPipe.getServiceByUrl(url)
                val info = PlaylistInfo.getInfo(url)
                val items = ArrayList<StreamInfoItem>(info.relatedItems)
                var page = info.nextPage
                var n = 0
                while (page != null && n < 5) {
                    val p = PlaylistInfo.getMoreItems(service, url, page)
                    items += p.items
                    page = p.nextPage
                    n++
                }
                Result(info.name.orEmpty().ifBlank { "Imported playlist" }, items.map { Sources.fromItem(it) }.distinctBy { it.id }, 0)
            }
            if (r.isSuccess && r.getOrThrow().tracks.isNotEmpty()) return r.getOrThrow()
        }
        val info = StreamInfo.getInfo(url)
        return Result(info.name.orEmpty(), listOf(Sources.fromInfo(info)), 0)
    }

    private fun collectRecordings(e: JsonElement, out: MutableList<String>) {
        when (e) {
            is JsonObject -> {
                if (e["@type"].str() == "MusicRecording") {
                    val n = e["name"].str()
                    val by = e["byArtist"]
                    val a = when (by) {
                        is JsonObject -> by["name"].str()
                        is JsonArray -> by.firstOrNull().let { (it as? JsonObject)?.get("name").str() }
                        else -> by.str()
                    }.orEmpty()
                    if (!n.isNullOrBlank()) out += "$n $a".trim()
                }
                e.values.forEach { collectRecordings(it, out) }
            }
            is JsonArray -> e.forEach { collectRecordings(it, out) }
            else -> {}
        }
    }

    private suspend fun generic(u: String, progress: (Int, Int) -> Unit): Result {
        val html = Net.get(u)
        val queries = ArrayList<String>()
        Regex("""<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m -> runCatching { collectRecordings(Json.parseToJsonElement(m.groupValues[1]), queries) } }
        if (queries.isEmpty()) error("Could not read songs from this link. Paste the song names (one per line) instead.")
        val title = Regex("""<meta[^>]+property="og:title"[^>]+content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: "Imported playlist"
        return fromQueries(title, queries.distinct().take(200), progress)
    }
}
