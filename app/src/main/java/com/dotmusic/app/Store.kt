package com.dotmusic.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object Store {
    private lateinit var file: File
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val data = MutableStateFlow(Lib())

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "library.json")
        data.value = runCatching { json.decodeFromString(Lib.serializer(), file.readText()) }.getOrDefault(Lib())
    }

    fun update(f: (Lib) -> Lib) {
        data.update(f)
        val s = json.encodeToString(Lib.serializer(), data.value)
        scope.launch { runCatching { file.writeText(s) } }
    }
}

object Downloads {
    val progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun file(t: Track): File {
        val dir = File(App.inst.filesDir, "music").apply { mkdirs() }
        return File(dir, t.key() + ".m4a")
    }

    fun exists(t: Track): Boolean = file(t).let { it.exists() && it.length() > 0 }

    private fun setProgress(id: String, p: Int?) {
        progress.update { m -> if (p == null) m - id else m + (id to p) }
    }

    suspend fun download(t: Track): Boolean = withContext(Dispatchers.IO) {
        if (t.src == "lo") return@withContext true
        if (exists(t)) return@withContext true
        setProgress(t.id, 0)
        val tmp = File(file(t).path + ".part")
        try {
            val url = if (t.src == "np") Sources.resolveStream(t.uri) else t.uri
            val chunk = 1_000_000L
            FileOutputStream(tmp).use { out ->
                var pos = 0L
                var total = -1L
                while (total < 0 || pos < total) {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", Net.UA)
                        .header("Range", "bytes=$pos-${pos + chunk - 1}").build()
                    var got = 0
                    Net.client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) error("HTTP ${r.code}")
                        if (total < 0) {
                            total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                                ?: r.body!!.contentLength()
                        }
                        val bytes = r.body!!.bytes()
                        out.write(bytes)
                        got = bytes.size
                        pos += got
                        if (r.code == 200) total = pos
                    }
                    if (got == 0) break
                    if (total > 0) setProgress(t.id, (pos * 100 / total).toInt().coerceIn(0, 99))
                }
            }
            tmp.renameTo(file(t))
            Store.update { it.copy(downloads = listOf(t) + it.downloads.filter { x -> x.id != t.id }) }
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        } finally {
            setProgress(t.id, null)
        }
    }

    fun remove(t: Track) {
        file(t).delete()
        Store.update { it.copy(downloads = it.downloads.filter { x -> x.id != t.id }) }
    }
}
