package io.github.boomboom.web

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/** The script's messages as `JSON.stringify` writes them. */
class TapEventTest {
    @Test
    fun appendFastPathMatchesTheJsonPath() {
        val bytes = Random(7).nextBytes(70_000)
        val text = """{"t":"append","token":"5f0c6a2e-3b1d-4c9a-9e2f-0123456789ab","id":3,"d":"${Base64.getEncoder().encodeToString(bytes)}"}"""
        val fast = TapEvent.parse(text) as TapEvent.Append
        val slow = TapEvent.parse(JSONObject(text)) as TapEvent.Append
        assertEquals("5f0c6a2e-3b1d-4c9a-9e2f-0123456789ab", fast.token)
        assertEquals(3, fast.id)
        assertArrayEquals(bytes, fast.data)
        assertArrayEquals(slow.data, fast.data)
        assertEquals(slow.token, fast.token)
    }

    @Test
    fun appendWithoutATokenIsNotAnAppend() {
        assertNull(TapEvent.parse("""{"t":"append","token":null,"id":3,"d":"AAAA"}"""))
    }

    @Test
    fun playerAnswer() {
        val text = """{"t":"player","token":"x","videoId":"dQw4w9WgXcQ","status":"OK","reason":null,"seconds":213,"isLive":false,""" +
            """"loudnessDb":-1.5,"audio":[{"itag":251,"mime":"audio/webm; codecs=\"opus\"","bitrate":134840,"quality":"AUDIO_QUALITY_MEDIUM"}],""" +
            """"chosen":{"itag":251,"mime":"audio/webm; codecs=\"opus\"","bitrate":134840,"quality":"AUDIO_QUALITY_MEDIUM","video":278}}"""
        val event = TapEvent.parse(text) as TapEvent.Player
        assertEquals("x", event.token)
        assertEquals(213.0, event.seconds!!, 0.0)
        assertEquals(-1.5, event.loudnessDb!!, 0.0)
        assertNull(event.reason)
        assertEquals("Opus", event.chosen!!.codecName)
        assertEquals(134840, event.chosen!!.bitrate)
        assertEquals(1, event.audio.size)
    }

    @Test
    fun theOthers() {
        assertEquals(TapEvent.Ready("https://music.youtube.com/watch?v=a"), TapEvent.parse("""{"t":"ready","href":"https://music.youtube.com/watch?v=a"}"""))
        assertEquals(TapEvent.Buffer("x", 1, "audio/webm; codecs=\"opus\""), TapEvent.parse("""{"t":"buffer","token":"x","id":1,"mime":"audio/webm; codecs=\"opus\"","videoId":"a"}"""))
        assertEquals(TapEvent.End("x", 1, null), TapEvent.parse("""{"t":"end","token":"x","id":1,"seconds":null}"""))
        assertEquals(TapEvent.Restart("x", 2), TapEvent.parse("""{"t":"restart","token":"x","id":2}"""))
        assertTrue(TapEvent.parse("""{"t":"error","where":"abort","message":"m"}""") is TapEvent.ScriptError)
        assertNull(TapEvent.parse("not json"))
        assertNull(TapEvent.parse("""{"t":"buffer","token":"x"}"""))
    }
}
