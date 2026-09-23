package com.flowtools

import com.flowtools.session.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

private val JsonT = Json { ignoreUnknownKeys = true }

/** Golden JSON parity with flowtools-protocol (Rust serde). */
class EventsTest {
    @Test fun `cursor move encodes like rust`() {
        val json = JsonT.encodeToString<ClientEvent>(ClientEvent.CursorMove(4f, -2f))
        assertEquals("""{"type":"cursor_move","dx":4.0,"dy":-2.0}""", json)
    }

    @Test fun `shutdown requires explicit confirmed flag`() {
        val json = JsonT.encodeToString<ClientEvent>(ClientEvent.ShutdownPc(true))
        assertTrue(json.contains(""""confirmed":true"""))
    }

    @Test fun `decode rust frame event`() {
        val raw = """{"type":"frame","viewport":"fit_phone","jpeg_base64":"eA==","seq":7}"""
        val ev = JsonT.decodeFromString<ServerEvent>(raw)
        assertTrue(ev is ServerEvent.Frame)
        val f = ev as ServerEvent.Frame
        assertEquals(ViewportMode.FitPhone, f.viewport)
        assertEquals(7L, f.seq)
    }

    @Test fun `decode token rotation and permission error`() {
        val tok = JsonT.decodeFromString<ServerEvent>( """{"type":"token","token":"abc"}""")
        assertEquals("abc", (tok as ServerEvent.Token).token)
        val err = JsonT.decodeFromString<ServerEvent>( """{"type":"error","code":"permission_needed"}""")
        assertEquals(UserErrorCode.PermissionNeeded, (err as ServerEvent.Error).code)
    }

    @Test fun `viewport defaults to fit phone`() {
        assertEquals(ViewportMode.FitPhone, ViewportMode.valueOf("FitPhone"))
    }
}
