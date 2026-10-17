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
        SponsorSegmentEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class CascadeDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun sponsorDao(): SponsorDao

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
                            // Populate default offline catalog
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
        if (trackDao().getCount() > 0) return

        val initialTracks = listOf(
            TrackEntity(
                id = "kt_song_01",
                title = "Imphal Nights",
                artist = "Ronnie",
                album = "Imphal Rock Sessions",
                durationMs = 214000,
                artUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Rock",
                moodX = 0.85f,
                moodY = 0.70f,
                loudnessGainDb = -1.2f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                itag = 251,
                codec = "Opus 160k",
                lyrics = """
                    [00:00.00] (Electric guitar distortion intro)
                    [00:15.50] Streetlights reflecting in the Kangla gate
                    [00:23.00] Moving fast through the night before it gets too late
                    [00:30.80] Imphal nights are burning with electric fire
                    [00:38.20] Lifting our spirit up higher and higher
                    [00:46.00] Hear the echoes reverberating in the valley wind
                    [00:53.50] A brand new story is about to begin
                    [01:01.20] Through the silent lanes, rock and roll refrain
                    [01:08.90] Pure sound taking away all the pain
                """.trimIndent()
            ),
            TrackEntity(
                id = "kt_song_02",
                title = "Loktak Drift",
                artist = "Tapta",
                album = "Floating Phumdi",
                durationMs = 248000,
                artUrl = "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Folk-Rock",
                moodX = 0.65f,
                moodY = 0.80f,
                loudnessGainDb = -0.8f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                itag = 251,
                codec = "Opus 160k",
                lyrics = """
                    [00:00.00] (Acoustic strings with folk groove)
                    [00:14.00] Water ripples shimmering under the twilight sky
                    [00:24.00] Watching white birds as they gently fly
                    [00:34.00] Floating rings of phumdi swaying slow
                    [00:44.00] Where the sweet water rivers gently flow
                    [00:54.00] Loktak drift, take my mind into the deep
                    [01:04.00] Ancient memories the silent waters keep
                """.trimIndent()
            ),
            TrackEntity(
                id = "kt_song_03",
                title = "Sangai Dawn",
                artist = "Kanglei Folk Project",
                album = "Keibul Lamjao",
                durationMs = 215000,
                artUrl = "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Folk-Fusion",
                moodX = 0.40f,
                moodY = 0.88f,
                loudnessGainDb = -0.5f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3",
                itag = 251,
                codec = "Opus 160k",
                lyrics = """
                    [00:00.00] (Traditional acoustic Pena notes and bamboo flute)
                    [00:15.00] Dawn breaks over misty mountain peaks
                    [00:25.00] In the valley where the soft wind speaks
                    [00:35.00] Dancing graceful in the golden morning light
                    [00:45.00] Sangai dawn, pure and wondrous sight
                    [00:55.00] Tamna leirabi, nungshi khonjel tarak-e
                    [01:05.00] Timeless beauty through the stream of time
                """.trimIndent()
            ),
            TrackEntity(
                id = "kt_song_04",
                title = "Pena Echo",
                artist = "Eastern Rhythms",
                album = "Pena Sessions Vol. 1",
                durationMs = 198000,
                artUrl = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Acoustic",
                moodX = 0.35f,
                moodY = 0.75f,
                loudnessGainDb = 0.0f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3",
                itag = 140,
                codec = "AAC 128k",
                lyrics = """
                    [00:00.00] (Resonant Pena bow string vibration)
                    [00:12.00] One single string sings a thousand tales
                    [00:22.00] Traveling across the ancient hills and dales
                    [00:32.00] Ancestral rhythm beating in the soul
                    [00:42.00] Making every broken spirit whole
                """.trimIndent()
            ),
            TrackEntity(
                id = "kt_song_05",
                title = "Dark Matter",
                artist = "Imphal Rock Collective",
                album = "Imphal Rock",
                durationMs = 210000,
                artUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                isLiked = true,
                genre = "Rock",
                moodX = 0.92f,
                moodY = 0.45f,
                loudnessGainDb = -2.5f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3",
                itag = 251,
                codec = "Opus 160k",
                lyrics = """
                    [00:00.00] (Heavy rock bassline and snare drive)
                    [00:10.00] Into the dark matter, breaking the sound barrier
                    [00:20.00] Electric currents flowing through the amplifier
                    [00:30.00] Stand your ground and scream out loud!
                """.trimIndent()
            ),
            TrackEntity(
                id = "kt_song_06",
                title = "Explode",
                artist = "Apex Frequency",
                album = "Imphal Rock",
                durationMs = 184000,
                artUrl = "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                isLiked = false,
                genre = "Rock",
                moodX = 0.95f,
                moodY = 0.50f,
                loudnessGainDb = -2.0f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-6.mp3",
                itag = 251,
                codec = "Opus 160k",
                lyrics = """
                    [00:00.00] (Fast tempo rock intro)
                    [00:08.00] Counting down the seconds on the clock
                    [00:16.00] Ready for the impact and the shock
                    [00:24.00] Explode! Break into the light!
                """.trimIndent()
            ),
            TrackEntity(
                id = "kt_song_07",
                title = "Addicted",
                artist = "Ronnie",
                album = "Imphal Rock",
                durationMs = 230000,
                artUrl = "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Rock",
                moodX = 0.78f,
                moodY = 0.65f,
                loudnessGainDb = -1.0f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-7.mp3",
                itag = 251,
                codec = "Opus 160k",
                lyrics = """
                    [00:00.00] (Melodic rock guitar riff)
                    [00:14.00] Addicted to the harmony, addicted to the sound
                    [00:26.00] Feeling like our feet have finally left the ground
                """.trimIndent()
            ),
            TrackEntity(
                id = "kt_song_08",
                title = "Cross Your Fingers",
                artist = "The Dynamics",
                album = "Imphal Rock",
                durationMs = 205000,
                artUrl = "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                isLiked = false,
                genre = "Rock",
                moodX = 0.70f,
                moodY = 0.60f,
                loudnessGainDb = -1.5f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-8.mp3",
                itag = 251,
                codec = "Opus 160k",
                lyrics = """
                    [00:00.00] (Rhythmic groove and drums)
                    [00:12.00] Cross your fingers for tomorrow
                    [00:22.00] Leaving all yesterday's sorrow
                """.trimIndent()
            ),
            TrackEntity(
                id = "kt_song_09",
                title = "Running Back",
                artist = "Imphal Rock Collective",
                album = "Imphal Rock",
                durationMs = 195000,
                artUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Rock",
                moodX = 0.82f,
                moodY = 0.72f,
                loudnessGainDb = -1.4f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                itag = 251,
                codec = "Opus 160k",
                lyrics = """
                    [00:00.00] (Speedy power chords)
                    [00:11.00] Running back to where we started
                    [00:20.00] Never broken, never parted
                """.trimIndent()
            ),
            TrackEntity(
                id = "kt_song_10",
                title = "Ningol Chakouba",
                artist = "Tapta",
                album = "Celebration",
                durationMs = 260000,
                artUrl = "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Folk-Fusion",
                moodX = 0.60f,
                moodY = 0.95f,
                loudnessGainDb = -0.3f,
                audioUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3",
                itag = 251,
                codec = "Opus 160k",
                lyrics = """
                    [00:00.00] (Joyous traditional beats and acoustic strumming)
                    [00:15.00] Festival day in the Manipur sun
                    [00:25.00] Welcoming our sisters, celebration has begun
                    [00:35.00] Feasting together with love and cheer
                    [00:45.00] The most cherished day of the year
                """.trimIndent()
            ),
            TrackEntity(
                id = "m8RtlqFgiG8",
                title = "Shape of You",
                artist = "Ed Sheeran",
                album = "÷ (Divide)",
                durationMs = 233000,
                artUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Pop",
                moodX = 0.88f,
                moodY = 0.82f,
                loudnessGainDb = -0.5f,
                audioUrl = "",
                itag = 140,
                codec = "AAC 128k",
                lyrics = """
                    [00:00.00] (Marimba and percussion intro)
                    [00:10.50] The club isn't the best place to find a lover
                    [00:13.20] So the bar is where I go
                    [00:15.80] Me and my friends at the table doing shots
                    [00:18.50] Drinking fast and then we talk slow
                    [00:21.00] Girl, you know I want your love
                    [00:23.50] Your love was handmade for somebody like me
                    [00:26.50] Come on now, follow my lead
                    [00:29.00] I may be crazy, don't mind me
                    [00:31.50] Say, boy, let's not talk too much
                    [00:34.00] Grab on my waist and put that body on me
                    [00:37.00] Come on now, follow my lead
                    [00:39.50] I'm in love with the shape of you
                    [00:42.50] We push and pull like a magnet do
                    [00:45.00] Although my heart is falling too
                    [00:48.00] I'm in love with your body
                """.trimIndent()
            ),
            TrackEntity(
                id = "fHI8X483mQw",
                title = "Blinding Lights",
                artist = "The Weeknd",
                album = "After Hours",
                durationMs = 200000,
                artUrl = "https://images.unsplash.com/photo-1508700115892-45ecd05ae2ad?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Synthwave",
                moodX = 0.95f,
                moodY = 0.78f,
                loudnessGainDb = -0.2f,
                audioUrl = "",
                itag = 140,
                codec = "AAC 128k",
                lyrics = """
                    [00:00.00] (80s synth lead arpeggio intro)
                    [00:27.00] I've been tryna call
                    [00:31.00] I've been on my own for long enough
                    [00:35.00] Maybe you can show me how to love, maybe
                    [00:43.00] I'm going through withdrawals
                    [00:47.00] You don't even have to do too much
                    [00:51.00] You can turn me on with just a touch, baby
                    [00:59.00] I look around and Sin City's cold and empty
                    [01:07.00] No one's around to judge me
                    [01:10.00] I can't see clearly when you're gone
                    [01:15.00] I said, ooh, I'm blinded by the lights
                    [01:23.00] No, I can't sleep until I feel your touch
                """.trimIndent()
            ),
            TrackEntity(
                id = "dvgZkm1xWPE",
                title = "Viva La Vida",
                artist = "Coldplay",
                album = "Viva la Vida",
                durationMs = 242000,
                artUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Rock",
                moodX = 0.85f,
                moodY = 0.85f,
                loudnessGainDb = -0.8f,
                audioUrl = "",
                itag = 140,
                codec = "AAC 128k",
                lyrics = """
                    [00:00.00] (Staccato orchestral strings theme)
                    [00:15.00] I used to rule the world
                    [00:18.50] Seas would rise when I gave the word
                    [00:23.00] Now in the morning I sleep alone
                    [00:26.50] Sweep the streets I used to own
                    [00:31.00] I used to roll the dice
                    [00:34.50] Feel the fear in my enemy's eyes
                    [00:39.00] Listen as the crowd would sing
                    [00:42.50] Now the old king is dead, long live the king
                    [00:47.00] One minute I held the key
                    [00:50.50] Next the walls were closed on me
                    [00:55.00] And I discovered that my castles stand
                    [00:58.50] Upon pillars of salt and pillars of sand
                    [01:03.00] Hear Jerusalem bells a-ringing
                    [01:07.00] Roman Cavalry choirs are singing
                    [01:11.00] Be my mirror, my sword and shield
                    [01:15.00] My missionaries in a foreign field
                """.trimIndent()
            ),
            TrackEntity(
                id = "ic8j13piAhQ",
                title = "Cruel Summer",
                artist = "Taylor Swift",
                album = "Lover",
                durationMs = 178000,
                artUrl = "https://images.unsplash.com/photo-1492562080023-ab3db95bfbce?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Pop",
                moodX = 0.90f,
                moodY = 0.80f,
                loudnessGainDb = -0.6f,
                audioUrl = "",
                itag = 140,
                codec = "AAC 128k",
                lyrics = """
                    [00:00.00] (Pulsing synth drumbeat)
                    [00:12.00] Fever dream high in the quiet of the night
                    [00:16.00] You know that I caught it
                    [00:19.00] Bad, bad boy, shiny toy with a price
                    [00:23.00] You know that I bought it
                    [00:26.00] Killing me slow, out the window
                    [00:29.00] I'm always waiting for you to be waiting below
                    [00:33.00] Devils roll the dice, angels roll their eyes
                    [00:37.00] What doesn't kill me makes me want you more
                    [00:41.00] And it's new, the shape of your body
                    [00:45.00] It's blue, the feeling I've got
                    [00:48.00] And it's ooh, whoa, oh
                    [00:52.00] It's a cruel summer
                """.trimIndent()
            ),
            TrackEntity(
                id = "7wtfhZwyrcc",
                title = "Believer",
                artist = "Imagine Dragons",
                album = "Evolve",
                durationMs = 204000,
                artUrl = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Rock",
                moodX = 0.98f,
                moodY = 0.65f,
                loudnessGainDb = -0.3f,
                audioUrl = "",
                itag = 140,
                codec = "AAC 128k",
                lyrics = """
                    [00:00.00] (Stomping drum and acoustic guitar)
                    [00:09.00] First things first, I'ma say all the words inside my head
                    [00:13.00] I'm fired up and tired of the way that things have been, oh-ooh
                    [00:18.00] The way that things have been, oh-ooh
                    [00:22.00] Second things second, don't you tell me what you think that I could be
                    [00:26.50] I'm the one at the sail, I'm the master of my sea, oh-ooh
                    [00:31.00] The master of my sea, oh-ooh
                    [00:36.00] Pain! You made me a, you made me a believer, believer!
                """.trimIndent()
            ),
            TrackEntity(
                id = "BddP6PYo2gs",
                title = "Kesariya",
                artist = "Arijit Singh",
                album = "Brahmāstra",
                durationMs = 268000,
                artUrl = "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Folk-Fusion",
                moodX = 0.65f,
                moodY = 0.90f,
                loudnessGainDb = -0.4f,
                audioUrl = "",
                itag = 140,
                codec = "AAC 128k",
                lyrics = """
                    [00:00.00] (Soft guitar chords and flute)
                    [00:15.00] Mujhko itna bataaye koyi
                    [00:22.00] Kaise tujhko dhoondhe koyi
                    [00:29.00] Teri dhoop mein khilte hain phool yahaan
                    [00:36.00] Teri chaaon mein dhalta hai aasmaan
                    [00:44.00] Kesariya tera ishq hai piya
                    [00:51.00] Rang jaaun jo main haath lagaaun
                    [00:58.00] Din beete saare teri fikr mein
                    [01:05.00] Rain saari teri khair manaun
                """.trimIndent()
            ),
            TrackEntity(
                id = "H5v3kku4y6Q",
                title = "As It Was",
                artist = "Harry Styles",
                album = "Harry's House",
                durationMs = 167000,
                artUrl = "https://images.unsplash.com/photo-1500648767791-00dcc994a43e?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Pop",
                moodX = 0.85f,
                moodY = 0.75f,
                loudnessGainDb = -0.7f,
                audioUrl = "",
                itag = 140,
                codec = "AAC 128k",
                lyrics = """
                    [00:00.00] (Bright 80s synth drum beat)
                    [00:10.00] Hold on, ring the bell and check the time
                    [00:16.00] In this world, it's just us
                    [00:20.00] You know it's not the same as it was
                    [00:26.00] As it was, as it was
                    [00:30.00] You know it's not the same
                """.trimIndent()
            ),
            TrackEntity(
                id = "zABLecsR5UE",
                title = "Someone You Loved",
                artist = "Lewis Capaldi",
                album = "Divinely Uninspired",
                durationMs = 182000,
                artUrl = "https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?w=600&auto=format&fit=crop&q=80",
                isDownloaded = false,
                localPath = null,
                isLiked = true,
                genre = "Acoustic",
                moodX = 0.45f,
                moodY = 0.85f,
                loudnessGainDb = -0.5f,
                audioUrl = "",
                itag = 140,
                codec = "AAC 128k",
                lyrics = """
                    [00:00.00] (Heartfelt piano chords)
                    [00:12.00] I'm going under and this time I fear there's no one to save me
                    [00:20.00] This all or nothing really got a way of driving me crazy
                    [00:28.00] I need somebody to heal, somebody to know
                    [00:36.00] Somebody to have, somebody to hold
                    [00:44.00] Now the day bleeds into nightfall
                    [00:48.00] And you're not here to get me through it all
                    [00:52.00] I let my guard down and then you pulled the rug
                    [00:58.00] I was getting kinda used to being someone you loved
                """.trimIndent()
            )
        )

        trackDao().insertTracks(initialTracks)

        // Seed default playlists matching Kanglei Tune UI
        val playlists = listOf(
            PlaylistEntity(
                id = "pl_imphal_rock",
                title = "Imphal Rock",
                description = "The best new rock tracks every week",
                isLocal = true,
                coverUrl = "https://images.unsplash.com/photo-1511671782779-c97d3d27a1d4?w=600&auto=format&fit=crop&q=80"
            ),
            PlaylistEntity(
                id = "pl_pena_sessions",
                title = "Pena Sessions",
                description = "Soulful traditional strings and acoustic folk-fusion",
                isLocal = true,
                coverUrl = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=600&auto=format&fit=crop&q=80"
            ),
            PlaylistEntity(
                id = "pl_liked_songs",
                title = "Liked Songs",
                description = "Your favorite tracks in one place",
                isLocal = true,
                coverUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&auto=format&fit=crop&q=80"
            ),
            PlaylistEntity(
                id = "pl_relax",
                title = "Relax",
                description = "Calm lo-fi and peaceful serene melodies",
                isLocal = true,
                coverUrl = "https://images.unsplash.com/photo-1518495973542-4542c06a5843?w=600&auto=format&fit=crop&q=80"
            ),
            PlaylistEntity(
                id = "pl_workout",
                title = "Workout",
                description = "High intensity energetic beats for maximum motivation",
                isLocal = true,
                coverUrl = "https://images.unsplash.com/photo-1459749411175-04bf5292ceea?w=600&auto=format&fit=crop&q=80"
            ),
            PlaylistEntity(
                id = "pl_techno",
                title = "Techno",
                description = "Deep electronic basslines and club rhythms",
                isLocal = true,
                coverUrl = "https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=600&auto=format&fit=crop&q=80"
            ),
            PlaylistEntity(
                id = "pl_rap",
                title = "Rap",
                description = "Underground flow and punchy lyrical beats",
                isLocal = true,
                coverUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600&auto=format&fit=crop&q=80"
            )
        )

        for (pl in playlists) {
            playlistDao().insertPlaylist(pl)
        }

        // Link items
        playlistDao().insertPlaylistItems(
            listOf(
                PlaylistItemEntity("pl_imphal_rock", 0, "kt_song_05"),
                PlaylistItemEntity("pl_imphal_rock", 1, "kt_song_06"),
                PlaylistItemEntity("pl_imphal_rock", 2, "kt_song_07"),
                PlaylistItemEntity("pl_imphal_rock", 3, "kt_song_08"),
                PlaylistItemEntity("pl_imphal_rock", 4, "kt_song_09"),
                PlaylistItemEntity("pl_pena_sessions", 0, "kt_song_03"),
                PlaylistItemEntity("pl_pena_sessions", 1, "kt_song_04"),
                PlaylistItemEntity("pl_pena_sessions", 2, "kt_song_02"),
                PlaylistItemEntity("pl_liked_songs", 0, "kt_song_01"),
                PlaylistItemEntity("pl_liked_songs", 1, "kt_song_02"),
                PlaylistItemEntity("pl_liked_songs", 2, "kt_song_03"),
                PlaylistItemEntity("pl_liked_songs", 3, "kt_song_04"),
                PlaylistItemEntity("pl_relax", 0, "kt_song_04"),
                PlaylistItemEntity("pl_relax", 1, "kt_song_03"),
                PlaylistItemEntity("pl_workout", 0, "kt_song_05"),
                PlaylistItemEntity("pl_workout", 1, "kt_song_06"),
                PlaylistItemEntity("pl_techno", 0, "kt_song_01"),
                PlaylistItemEntity("pl_rap", 0, "kt_song_09")
            )
        )

        // Seed sample SponsorBlock segment on track 3
        sponsorDao().insertSegments(
            listOf(
                SponsorSegmentEntity(
                    videoId = "yt_rock_03",
                    category = "SPONSOR",
                    startMs = 5000,
                    endMs = 12000,
                    autoSkip = true
                )
            )
        )
    }
}
