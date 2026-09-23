package com.flowtools.session

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val ProtoJson = Json { ignoreUnknownKeys = true }

/**
 * FlowSocket: Ktor WebSocket client for `WS /v1/session/ws?token=...`.
 * - single session owner: sends ClientEvent JSON, receives ServerEvent
 * - applies token rotation (Token event replaces the stored token)
 * - reconnects with backoff, preserving UI/tool state (tool state lives
 *   in ViewModels, never here)
 * - never blocks the UI thread; all errors surface as ConnectionState
 *   + user message, never raw exceptions.
 */
class FlowSocket(
    private val conn: ConnectionManager,
    private val scope: CoroutineScope,
) {
    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    private var serverHttp: String? = null
    private var token: String? = null
    private var job: Job? = null

    private val outbox = MutableSharedFlow<String>(extraBufferCapacity = 64)

    private val _incoming = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ServerEvent> = _incoming.asSharedFlow()

    private val _lastUserMessage = MutableStateFlow<String?>(null)
    val lastUserMessage: StateFlow<String?> = _lastUserMessage.asStateFlow()

    fun currentToken(): String? = token

    fun attach(serverHttp: String, token: String) {
        this.serverHttp = serverHttp
        this.token = token
        reconnect()
    }

    fun detach() {
        job?.cancel()
        job = null
    }

    fun reconnect() {
        job?.cancel()
        val base = serverHttp ?: return
        val tok = token ?: return
        job = scope.launch(Dispatchers.IO) {
            var backoff = 1000L
            while (isActive) {
                try {
                    runSession(base, tok)
                    conn.onDisconnected()
                    delay(2000)
                    backoff = 1000L
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    conn.onDisconnected()
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(30_000)
                }
            }
        }
    }

    private suspend fun runSession(base: String, startToken: String) {
        var tok = startToken
        while (true) {
            conn.connecting()
            val wsUrl = base.trimEnd('/') + "/v1/session/ws?token=$tok"
            val url = wsUrl.replaceFirst("http", "ws")
            try {
                val session = client.webSocketSession(urlString = url)
                try {
                    val sender = session.launch {
                        outbox.collect { msg ->
                            session.send(Frame.Text(msg))
                        }
                    }
                    try {
                        for (frame in session.incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            val ev = runCatching { ProtoJson.decodeFromString<ServerEvent>(text) }.getOrNull()
                                ?: continue
                            when (ev) {
                                is ServerEvent.Token -> {
                                    tok = ev.token
                                    token = ev.token
                                }
                                is ServerEvent.State -> {
                                    if (ev.state == ProtoState.Connected) conn.markConnected()
                                }
                                is ServerEvent.Error -> {
                                    _lastUserMessage.value = when (ev.code) {
                                        UserErrorCode.Offline -> "O PC não está disponível. Verifique a ligação."
                                        UserErrorCode.PermissionNeeded -> "Esta ferramenta precisa de autorização."
                                        UserErrorCode.InvalidCode -> "Código inválido. Tente novamente."
                                        UserErrorCode.ExpiredSession -> "A sessão expirou. Volte a ligar."
                                        UserErrorCode.FileExists -> "Já existe um ficheiro com esse nome."
                                        UserErrorCode.TooLarge -> "Ficheiro demasiado grande (máx. 50 MB)."
                                        UserErrorCode.TransferCancelled -> "Transferência cancelada."
                                        UserErrorCode.BrowserClosed -> "O browser não está aberto no PC."
                                        UserErrorCode.Unknown -> "Algo correu mal. Tente novamente."
                                    }
                                    if (ev.code == UserErrorCode.ExpiredSession) {
                                        conn.onOffline()
                                        return
                                    }
                                    if (ev.code == UserErrorCode.PermissionNeeded) {
                                        conn.requirePermission()
                                    }
                                }
                                else -> {}
                            }
                            _incoming.tryEmit(ev)
                        }
                    } finally {
                        sender.cancel()
                    }
                } finally {
                    session.cancel("done")
                }
                return // clean close; outer loop reconnects
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                conn.onDisconnected()
                delay(3000)
                tok = token ?: tok // pick up rotated token if we got one
            }
        }
    }

    /** Fire-and-forget; no-op while detached. Never throws. */
    fun send(ev: ClientEvent) {
        if (token == null || serverHttp == null) return
        outbox.tryEmit(ProtoJson.encodeToString(ev))
    }

    fun close() {
        detach()
        client.close()
    }
}
