package com.example

import android.app.Application
import android.content.ComponentCallbacks2
import com.example.data.local.CascadeDatabase
import com.example.data.repository.DownloadRepository
import com.example.data.repository.MusicRepository
import com.example.player.AudioPlayerManager
import com.example.resolver.ResolverEngine

class CascadeApplication : Application(), coil.ImageLoaderFactory {

    lateinit var database: CascadeDatabase
        private set
    lateinit var musicRepository: MusicRepository
        private set
    lateinit var downloadRepository: DownloadRepository
        private set
    lateinit var resolverEngine: ResolverEngine
        private set
    lateinit var audioPlayerManager: AudioPlayerManager
        private set

    override fun newImageLoader(): coil.ImageLoader {
        return coil.ImageLoader.Builder(this)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(0.10) // Tuned to 10% heap for 2GB RAM phones
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(30L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(true) // Halves bitmap RAM usage (2 bytes/pixel instead of 4) on 2GB RAM phones
            .respectCacheHeaders(false)
            .crossfade(false) // Eliminates crossfade double-bitmap allocation spikes during scroll/unlock
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("CascadeApplication", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            if (thread.name != "main") {
                return@setDefaultUncaughtExceptionHandler
            }
            val msg = throwable.message.orEmpty()
            val className = throwable.javaClass.name
            if (className.contains("ForegroundService") ||
                className.contains("RemoteServiceException") ||
                msg.contains("startForeground")
            ) {
                return@setDefaultUncaughtExceptionHandler
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        database = CascadeDatabase.getInstance(this)
        musicRepository = MusicRepository(database)
        resolverEngine = ResolverEngine()
        downloadRepository = DownloadRepository(this, database, resolverEngine)
        audioPlayerManager = AudioPlayerManager(this, resolverEngine, musicRepository)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Only clear Coil memory cache under genuine low-memory pressure or deep background eviction.
        // Do NOT clear on TRIM_MEMORY_UI_HIDDEN (screen lock), which previously forced every visible
        // thumbnail to re-decode simultaneously on screen unlock and caused unlock crashes/stalls!
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            runCatching {
                coil.Coil.imageLoader(this).memoryCache?.clear()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching {
            coil.Coil.imageLoader(this).memoryCache?.clear()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        audioPlayerManager.release()
    }

    companion object {
        lateinit var instance: CascadeApplication
            private set
    }
}
