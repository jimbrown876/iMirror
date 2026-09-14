package dev.imirror.receiver.ui

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.imirror.receiver.R
import dev.imirror.receiver.airplay.NowPlayingInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NowPlayingScreenTest {
    @Test
    fun `actual music card retains artwork and metadata without any sender footer`() {
        val context = RuntimeEnvironment.getApplication()
        val screen = NowPlayingScreen(context)
        screen.update(NowPlayingInfo("Jim's iPhone", "Track Title", "Artist Name", "Album Name"))

        val column = screen.getChildAt(0) as LinearLayout
        assertEquals("Only artwork, title, artist and album belong on the card", 4, column.childCount)
        val artwork = column.getChildAt(0) as ImageView
        assertNotNull("Artwork area retains its placeholder when cover bytes have not arrived", artwork.drawable)
        assertEquals(artwork.layoutParams.width, artwork.layoutParams.height)
        assertEquals((360 * context.resources.displayMetrics.density).toInt(), artwork.layoutParams.width)
        val metadata = (1 until column.childCount).map { (column.getChildAt(it) as TextView).text.toString() }
        assertEquals(listOf("Track Title", "Artist Name", "Album Name"), metadata)
        assertFalse(metadata.any { it.contains("Jim's iPhone") || it.contains("Audio from") })
        assertEquals("Remaining card stays centered after the footer is removed", Gravity.CENTER, column.gravity)
        assertEquals(Gravity.CENTER, (column.layoutParams as FrameLayout.LayoutParams).gravity)
    }

    @Test
    fun `missing and updated metadata never brings the sender footer back`() {
        val context = RuntimeEnvironment.getApplication()
        val screen = NowPlayingScreen(context)
        screen.update(NowPlayingInfo("Jim's Mac"))
        val column = screen.getChildAt(0) as LinearLayout
        assertEquals(4, column.childCount)
        assertEquals(context.getString(R.string.now_playing_audio), (column.getChildAt(1) as TextView).text.toString())
        assertEquals(View.GONE, column.getChildAt(2).visibility)
        assertEquals(View.GONE, column.getChildAt(3).visibility)

        screen.update(NowPlayingInfo("Another sender", "Next Song", "Next Artist", "Next Album"))
        assertEquals(4, column.childCount)
        assertEquals(listOf("Next Song", "Next Artist", "Next Album"),
            (1 until column.childCount).map { (column.getChildAt(it) as TextView).text.toString() })
        assertEquals(View.VISIBLE, column.getChildAt(2).visibility)
        assertEquals(View.VISIBLE, column.getChildAt(3).visibility)
        screen.clear()
        assertNull((column.getChildAt(0) as ImageView).drawable)
    }
}
