package com.dotmusic.app

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Shared between the UI and the playback service (same process). */
object Bridge {
    var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var prefs: SharedPreferences

    val sleepLeft = MutableStateFlow<Long?>(null)
    val sleepEndOfTrack = MutableStateFlow(false)

    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    val eqOn = MutableStateFlow(false)
    val bassLevel = MutableStateFlow(0)
    val bandLevels = MutableStateFlow<List<Int>>(emptyList())
    var bandRange: Pair<Int, Int> = -1500 to 1500
    var bandFreqs: List<Int> = emptyList()
    var presets: List<String> = emptyList()

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("dot", Context.MODE_PRIVATE)
        eqOn.value = prefs.getBoolean("eq_on", false)
        bassLevel.value = prefs.getInt("bass", 0)
    }

    fun attach(p: ExoPlayer) { player = p }

    // ---------- Sleep timer ----------
    fun startSleep(min: Int) {
        job?.cancel()
        sleepEndOfTrack.value = false
        if (min <= 0) { sleepLeft.value = null; return }
        job = scope.launch {
            var left = min * 60_000L
            while (left > 0) { sleepLeft.value = left; delay(1000); left -= 1000 }
            sleepLeft.value = null
            val p = player
            if (p != null) {
                for (i in 20 downTo 0) { p.volume = i / 20f; delay(100) }
                p.pause()
                p.volume = 1f
            }
        }
    }

    fun sleepAtTrackEnd() {
        job?.cancel()
        sleepLeft.value = null
        sleepEndOfTrack.value = true
    }

    // ---------- Equalizer ----------
    fun attachFx(session: Int) {
        releaseFx()
        if (session <= 0) return
        runCatching {
            val e = Equalizer(0, session)
            eq = e
            bandRange = e.bandLevelRange[0].toInt() to e.bandLevelRange[1].toInt()
            val n = e.numberOfBands.toInt()
            bandFreqs = (0 until n).map { e.getCenterFreq(it.toShort()) / 1000 }
            presets = (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) }
            val saved = prefs.getString("eq_levels", null)?.split(",")?.mapNotNull { it.toIntOrNull() }
            val lv = if (saved != null && saved.size == n) saved
            else (0 until n).map { e.getBandLevel(it.toShort()).toInt() }
            lv.forEachIndexed { i, v -> e.setBandLevel(i.toShort(), v.toShort()) }
            bandLevels.value = lv
            e.enabled = eqOn.value
        }
        runCatching {
            val b = BassBoost(0, session)
            bass = b
            if (b.strengthSupported) b.setStrength(bassLevel.value.toShort())
            b.enabled = eqOn.value
        }
    }

    private fun releaseFx() {
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        eq = null; bass = null
    }

    fun setEqOn(on: Boolean) {
        eqOn.value = on
        prefs.edit().putBoolean("eq_on", on).apply()
        runCatching { eq?.enabled = on }
        runCatching { bass?.enabled = on }
    }

    fun setBand(i: Int, level: Int) {
        runCatching { eq?.setBandLevel(i.toShort(), level.toShort()) }
        val l = bandLevels.value.toMutableList()
        if (i in l.indices) l[i] = level
        bandLevels.value = l
        prefs.edit().putString("eq_levels", l.joinToString(",")).apply()
    }

    fun usePreset(i: Int) {
        val e = eq ?: return
        runCatching { e.usePreset(i.toShort()) }
        val lv = (0 until e.numberOfBands.toInt()).map { e.getBandLevel(it.toShort()).toInt() }
        bandLevels.value = lv
        prefs.edit().putString("eq_levels", lv.joinToString(",")).apply()
        setEqOn(true)
    }

    fun setBass(level: Int) {
        bassLevel.value = level
        prefs.edit().putInt("bass", level).apply()
        runCatching { if (bass?.strengthSupported == true) bass?.setStrength(level.toShort()) }
    }
}
