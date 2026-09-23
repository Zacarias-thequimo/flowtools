package com.flowtools

import com.flowtools.session.userMessageFor
import io.ktor.client.plugins.HttpRequestTimeoutException
import org.junit.Assert.*
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException

class NetErrorsTest {
    @Test fun `host desconhecido diz para confirmar o IP`() {
        val msg = userMessageFor(UnknownHostException("no address"))
        assertTrue(msg.contains("IP"))
    }

    @Test fun `recusa de ligacao fala de servidor e firewall`() {
        val msg = userMessageFor(ConnectException("refused"))
        assertTrue(msg.contains("firewall") || msg.contains("servidor"))
    }

    @Test fun `timeout fala da rede`() {
        val msg = userMessageFor(HttpRequestTimeoutException("https://x", 8000L))
        assertTrue(msg.contains("demorou") || msg.contains("rede"))
    }

    @Test fun `erro generico continua PT sem detalhes tecnicos`() {
        val msg = userMessageFor(RuntimeException("socket boom"))
        assertFalse(msg.contains("socket"))
        assertFalse(msg.contains("Exception"))
    }
}
