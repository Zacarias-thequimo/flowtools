package com.flowtools

import com.flowtools.pairing.capabilityWireName
import com.flowtools.pairing.parsePairInvite
import org.junit.Assert.*
import org.junit.Test

class QrParserTest {
    @Test fun `valid qr parses`() {
        val inv = parsePairInvite("flowtools://pair?id=abc-123&code=283914&host=http://192.168.1.5:8787")
        assertNotNull(inv)
        assertEquals("abc-123", inv!!.pairingId)
        assertEquals("283914", inv.code)
        assertEquals("http://192.168.1.5:8787", inv.host)
    }

    @Test fun `host defaults to local server`() {
        val inv = parsePairInvite("flowtools://pair?id=x&code=000001")
        assertEquals("http://127.0.0.1:8787", inv!!.host)
    }

    @Test fun `rejects bad scheme and bad code`() {
        assertNull(parsePairInvite("https://example.com/?id=x&code=000001"))
        assertNull(parsePairInvite("flowtools://pair?id=x&code=12"))
        assertNull(parsePairInvite("flowtools://pair?code=000001"))
        assertNull(parsePairInvite("  "))
    }

    @Test fun `capability labels map to wire names`() {
        assertEquals("screen_capture", capabilityWireName("Captura de ecrã"))
        assertEquals("system", capabilityWireName("Sistema (bloquear/desligar)"))
        assertEquals("cursor", capabilityWireName("Cursor"))
    }
}
