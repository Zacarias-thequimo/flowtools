package com.flowtools

import com.flowtools.session.*
import org.junit.Assert.*
import org.junit.Test

class SessionRulesTest {
    @Test fun `states expose user message`() {
        assertEquals("Ligue um PC para começar.", ConnectionState.Unpaired.label)
        assertEquals("Ligado", ConnectionState.Connected.label)
    }

    @Test fun `permissions are granular`() {
        val granted = setOf(Capability.Cursor, Capability.Media)
        assertTrue(missingCapabilities(granted, setOf(Capability.Cursor)).isEmpty())
        assertEquals(setOf(Capability.Files), missingCapabilities(granted, setOf(Capability.Cursor, Capability.Files)))
    }

    @Test fun `browser requires browser and capture`() {
        val req = requiredForTool("browser")
        assertTrue(req.contains(Capability.Browser))
        assertTrue(req.contains(Capability.ScreenCapture))
        assertFalse(req.contains(Capability.Files))
    }

    @Test fun `connection manager gates tools by permission`() {
        val mgr = ConnectionManager()
        mgr.setPaired("PC", setOf(Capability.Cursor, Capability.Media), "tok")
        assertTrue(mgr.canUse("remote_control"))
        assertFalse(mgr.canUse("file_transfer"))
        mgr.revoke()
        assertFalse(mgr.canUse("remote_control"))
    }
}
