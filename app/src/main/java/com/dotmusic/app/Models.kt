package com.dotmusic.app

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val art: String? = null,
    val durationMs: Long = 0L,
    val uri: String,
    val src: String // "np" (YouTube etc via NewPipe), "au" (Audius), "lo" (local)
)

@Serializable
data class Playlist(val id: String, val name: String, val tracks: List<Track> = emptyList())

@Serializable
data class Lib(
    val playlists: List<Playlist> = emptyList(),
    val liked: List<Track> = emptyList(),
    val recent: List<Track> = emptyList(),
    val downloads: List<Track> = emptyList()
)

fun Track.key(): String =
    MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

fun Track.srcLabel(): String = when (src) { "np" -> "YOUTUBE"; "au" -> "AUDIUS"; else -> "LOCAL" }

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
