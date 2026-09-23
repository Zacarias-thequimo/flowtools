package com.flowtools.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowtools.secure.SessionStore
import com.flowtools.session.Api
import com.flowtools.session.Capability
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
private data class PairClaimDto(
    val pairing_id: String,
    val code: String,
    val device_name: String,
    val approved: Set<String>,
)

@Serializable
private data class SessionTokenDto(
    val session_id: String,
    val device_id: String,
    val pc_id: String,
    val pc_name: String,
    val token: String,
    val granted: Set<String>,
    val expires_at_unix: Long,
)

sealed interface PairUiState {
    data object Idle : PairUiState
    data class Scanned(val invite: PairInvite) : PairUiState
    data object Claiming : PairUiState
    data class Error(val message: String) : PairUiState
    data object Done : PairUiState
}

val ALL_CAPABILITY_LABELS = listOf(
    "Cursor",
    "Teclado",
    "Captura de ecrã",
    "Browser",
    "Aplicações",
    "Ficheiros",
    "Notificações",
    "Multimédia",
    "Sistema (bloquear/desligar)",
)

fun wireToCapability(wire: String): Capability? = when (wire) {
    "cursor" -> Capability.Cursor
    "keyboard" -> Capability.Keyboard
    "screen_capture" -> Capability.ScreenCapture
    "browser" -> Capability.Browser
    "applications" -> Capability.Applications
    "files" -> Capability.Files
    "notifications" -> Capability.Notifications
    "media" -> Capability.Media
    "system" -> Capability.System
    else -> null
}

class PairingViewModel : ViewModel() {
    private val _state = MutableStateFlow<PairUiState>(PairUiState.Idle)
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    private val _approved = MutableStateFlow(ALL_CAPABILITY_LABELS.toSet())
    val approved: StateFlow<Set<String>> = _approved.asStateFlow()

    private val _deviceName = MutableStateFlow("O meu telemóvel")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    fun onScanned(raw: String) {
        val invite = parsePairInvite(raw)
        _state.value = if (invite != null) PairUiState.Scanned(invite)
        else PairUiState.Error("QR inválido. Tente novamente ou insira o código.")
    }

    fun onManual(pairingId: String, code: String, host: String) {
        if (host.isBlank()) {
            _state.value = PairUiState.Error("Insira o endereço do PC (ex. http://192.168.1.5:8787).")
            return
        }
        onScanned("flowtools://pair?id=$pairingId&code=$code&host=$host")
    }

    fun toggle(label: String) {
        _approved.value = if (_approved.value.contains(label)) _approved.value - label
        else _approved.value + label
    }

    fun setDeviceName(name: String) {
        _deviceName.value = name
    }

    fun retry() {
        _state.value = PairUiState.Idle
    }

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    /** Diagnóstico pedido pelo utilizador: GET /v1/health com erro legível. */
    fun testConnection(host: String) {
        _testResult.value = "A testar…"
        viewModelScope.launch(Dispatchers.IO) {
            _testResult.value = try {
                com.flowtools.session.healthCheck(host)
            } catch (e: Exception) {
                com.flowtools.session.userMessageFor(e)
            }
        }
    }

    /** Claim the pairing, persist the session and report back for socket attach. */
    fun claim(store: SessionStore, onDone: (server: String, token: String) -> Unit) {
        val invite = (_state.value as? PairUiState.Scanned)?.invite ?: return
        _state.value = PairUiState.Claiming
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = Api.http.post("${invite.host.trimEnd('/')}/v1/pair/claim") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        PairClaimDto(
                            pairing_id = invite.pairingId,
                            code = invite.code,
                            device_name = _deviceName.value.ifBlank { "O meu telemóvel" },
                            approved = _approved.value.map(::capabilityWireName).toSet(),
                        ),
                    )
                }
                if (!resp.status.isSuccess()) {
                    val msg = when (resp.status) {
                        HttpStatusCode.Unauthorized, HttpStatusCode.NotFound ->
                            "Código inválido. Tente novamente."
                        HttpStatusCode.Gone -> "O código expirou. Gere um novo no PC."
                        HttpStatusCode.Forbidden -> "O PC recusou estas permissões."
                        else -> "Algo correu mal. Tente novamente."
                    }
                    _state.value = PairUiState.Error(msg)
                    return@launch
                }
                val session = resp.body<SessionTokenDto>()
                store.saveSession(invite.host, session.pc_id, session.pc_name, session.token)
                _state.value = PairUiState.Done
                onDone(invite.host, session.token)
            } catch (e: Exception) {
                _state.value = PairUiState.Error(com.flowtools.session.userMessageFor(e))
            }
        }
    }
}
