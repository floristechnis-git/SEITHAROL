package com.example

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import com.example.data.local.CascadeDatabase
import com.example.data.repository.DownloadRepository
import com.example.data.repository.MusicRepository
import com.example.player.AudioPlayerManager
import com.example.player.MediaPlaybackService
import com.example.resolver.ResolverEngine
import com.seitharol.player.OuterTuneResolvingDataSource

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

    fun isLowRamPhone(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val maxHeap = Runtime.getRuntime().maxMemory()
        return am?.isLowRamDevice == true || maxHeap <= 200L * 1024L * 1024L
    }

    override fun newImageLoader(): coil.ImageLoader {
        val lowRam = isLowRamPhone()
        return coil.ImageLoader.Builder(this)
            .memoryCache {
                coil.memory.MemoryCache.Builder(this)
                    .maxSizePercent(if (lowRam) 0.06 else 0.10) // Ultra-safe 6% heap cap on 2GB RAM phones
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(if (lowRam) 20L * 1024 * 1024 else 30L * 1024 * 1024)
                    .build()
            }
            .allowRgb565(true) // Halves bitmap RAM usage (2 bytes/pixel instead of 4) on 2GB RAM phones
            .bitmapConfig(Bitmap.Config.RGB_565)
            .respectCacheHeaders(false)
            .crossfade(false) // Eliminates crossfade double-bitmap allocation spikes during scroll/unlock
            .build()
    }

    private fun releaseNonEssentialMemory() {
        runCatching { OuterTuneResolvingDataSource.trimMemory() }
        runCatching { MediaPlaybackService.trimMemory() }
        runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("CascadeApplication", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            if (throwable is OutOfMemoryError || throwable.cause is OutOfMemoryError) {
                releaseNonEssentialMemory()
                System.gc()
                return@setDefaultUncaughtExceptionHandler
            }
            if (thread.name != "main") {
                return@setDefaultUncaughtExceptionHandler
            }
            val msg = throwable.message.orEmpty()
            val className = throwable.javaClass.name
            if (className.contains("ForegroundService") ||
                className.contains("RemoteServiceException") ||
                className.contains("DeadSystemException") ||
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
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        ) {
            runCatching { OuterTuneResolvingDataSource.trimMemory() }
            runCatching { MediaPlaybackService.trimMemory() }
        }
        // Only clear Coil memory cache under genuine low-memory pressure or deep background eviction.
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
        releaseNonEssentialMemory()
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
