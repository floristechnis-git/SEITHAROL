package com.example

import android.app.Application
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
                    .maxSizePercent(0.12) // Limit image memory cache to 12% heap for 2GB RAM budget phones
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(25L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("CascadeApplication", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            // Never let background worker/IO/network/media thread exceptions kill the app process
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
        // On low-RAM devices (especially during lock/unlock transitions), trim Coil image memory caches
        // so Android Low Memory Killer (LMKD) never terminates background audio playback!
        runCatching {
            coil.Coil.imageLoader(this).memoryCache?.clear()
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
