package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        DownloadEntity::class,
        PlaybackCheckpointEntity::class,
        SponsorSegmentEntity::class,
        PlayHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class CascadeDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun sponsorDao(): SponsorDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: CascadeDatabase? = null

        fun getInstance(context: Context): CascadeDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CascadeDatabase::class.java,
                    "cascade_music.db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                getInstance(context).populateInitialCatalog()
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

    suspend fun populateInitialCatalog() {
        if (playlistDao().getPlaylistById("pl_liked_songs") == null) {
            playlistDao().insertPlaylist(
                PlaylistEntity(
                    id = "pl_liked_songs",
                    title = "Liked Songs",
                    description = "Your favorite tracks in one place",
                    isLocal = true,
                    coverUrl = null
                )
            )
        }
    }
}
