package com.dotmusic.app

import android.app.Application
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.schabi.newpipe.extractor.NewPipe
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        inst = this
        NewPipe.init(DownloaderImpl())
        Store.init(this)
        Bridge.init(this)
    }

    companion object {
        lateinit var inst: App
        val cache: SimpleCache by lazy {
            SimpleCache(
                File(inst.cacheDir, "audio"),
                LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024),
                StandaloneDatabaseProvider(inst)
            )
        }
    }
}
