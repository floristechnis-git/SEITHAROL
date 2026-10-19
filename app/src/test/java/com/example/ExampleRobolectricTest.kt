package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Seitharol", appName)
  }

  @Test
  fun `verify audio player queue methods`() {
    val app = ApplicationProvider.getApplicationContext<CascadeApplication>()
    val manager = app.audioPlayerManager
    val track1 = com.example.data.model.Track(
      id = "t1",
      title = "Track One",
      artist = "Artist One",
      album = "Album One",
      durationMs = 180000L,
      audioUrl = "http://example.com/1.mp3",
      artUrl = "http://example.com/1.jpg"
    )
    val track2 = com.example.data.model.Track(
      id = "t2",
      title = "Track Two",
      artist = "Artist Two",
      album = "Album Two",
      durationMs = 200000L,
      audioUrl = "http://example.com/2.mp3",
      artUrl = "http://example.com/2.jpg"
    )

    manager.addToQueue(track1)
    manager.addToQueue(track2)
    assertEquals(2, manager.state.value.queue.size)
    assertEquals("t1", manager.state.value.queue[0].id)
    assertEquals("t2", manager.state.value.queue[1].id)

    val trackNext = com.example.data.model.Track(
      id = "t_next",
      title = "Track Next",
      artist = "Artist Next",
      album = "Album Next",
      durationMs = 210000L,
      audioUrl = "http://example.com/next.mp3",
      artUrl = "http://example.com/next.jpg"
    )
    manager.playNext(trackNext)
    assertEquals(3, manager.state.value.queue.size)
    assertEquals("t_next", manager.state.value.queue[1].id)

    manager.playTrack(track1)
  }
}
