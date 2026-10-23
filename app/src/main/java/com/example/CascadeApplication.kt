package com.example

import android.app.Application
import com.example.data.local.CascadeDatabase
import com.example.data.repository.DownloadRepository
import com.example.data.repository.MusicRepository
import com.example.player.AudioPlayerManager
import com.example.resolver.ResolverEngine

class CascadeApplication : Application() {

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

    override fun onCreate() {
        super.onCreate()
        instance = this

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
