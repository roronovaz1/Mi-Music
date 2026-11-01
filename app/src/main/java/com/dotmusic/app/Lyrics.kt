package com.dotmusic.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import java.net.URLEncoder
import kotlin.math.abs

data class LyricLine(val timeMs: Long, val text: String)
data class Lyrics(val synced: List<LyricLine>, val plain: String?)

sealed interface LyricsState {
    object Loading : LyricsState
    object None : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
}

object LyricsRepo {
    private val cache = HashMap<String, Lyrics?>()
    private val stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun clean(s: String): String = s
        .replace(Regex("""\(.*?\)|\[.*?\]|【.*?】"""), " ")
        .replace(Regex("""(?i)\b(official|video|audio|lyrics?|lyric video|hd|4k|mv|music video|visualizer)\b"""), " ")
        .replace(Regex("""(?i)\b(feat|ft)\.?.*$"""), "")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '|')

    fun parseLrc(s: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (line in s.lines()) {
            val ms = stamp.findAll(line).toList()
            if (ms.isEmpty()) continue
            val text = line.substring(ms.last().range.last + 1).trim()
            for (m in ms) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fr = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                out += LyricLine(min * 60000 + sec * 1000 + fr, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun query(url: String): List<JsonObject> = runCatching {
        Json.parseToJsonElement(Net.get(url, mapOf("User-Agent" to "DotMusic/1.0 (personal app)"))).jsonArray
            .filterIsInstance<JsonObject>()
    }.getOrDefault(emptyList())

    suspend fun get(t: Track): Lyrics? = withContext(Dispatchers.IO) {
        if (cache.containsKey(t.id)) return@withContext cache[t.id]
        var name = clean(t.title)
        var artist = clean(t.artist)
        if (t.title.contains(" - ")) {
            val p = t.title.split(" - ", limit = 2)
            artist = clean(p[0]); name = clean(p[1])
        }
        var res = query("https://lrclib.net/api/search?track_name=${enc(name)}&artist_name=${enc(artist)}")
        if (res.isEmpty()) res = query("https://lrclib.net/api/search?q=${enc("$name $artist")}")
        val dur = t.durationMs / 1000.0
        fun synced(o: JsonObject) = !o["syncedLyrics"].str().isNullOrBlank()
        val pick = res.filter { synced(it) }
            .minByOrNull { abs((it["duration"].str()?.toDoubleOrNull() ?: dur) - dur) }
            ?.takeIf { dur == 0.0 || abs((it["duration"].str()?.toDoubleOrNull() ?: dur) - dur) < 8 }
            ?: res.firstOrNull { synced(it) }
            ?: res.firstOrNull { !it["plainLyrics"].str().isNullOrBlank() }
        val result = pick?.let {
            val s = it["syncedLyrics"].str()
            Lyrics(if (s.isNullOrBlank()) emptyList() else parseLrc(s), it["plainLyrics"].str())
        }
        cache[t.id] = result
        result
    }
}
