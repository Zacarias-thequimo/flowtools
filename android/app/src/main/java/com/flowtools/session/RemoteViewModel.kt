package com.flowtools.session

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Holds remote-tool UI state across reconnects (volume, viewport, frame).
 * Delegates transport to FlowSocket; safe to use while detached
 * (actions no-op until attach() with a valid session token).
 */
class RemoteViewModel : ViewModel() {
    private var socket: FlowSocket? = null

    private val _volume = MutableStateFlow(0.5f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _viewportMode = MutableStateFlow(ViewportMode.FitPhone)
    val viewportMode: StateFlow<ViewportMode> = _viewportMode.asStateFlow()

    private val _frame = MutableStateFlow<Bitmap?>(null)
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    fun attach(conn: ConnectionManager, serverHttp: String, token: String) {
        if (socket != null) return
        val s = FlowSocket(conn, viewModelScope)
        socket = s
        viewModelScope.launch {
            s.incoming.collect { ev ->
                when (ev) {
                    is ServerEvent.Frame -> decodeFrame(ev.jpeg_base64)
                    is ServerEvent.MediaState -> {
                        _playing.value = ev.playing
                        _volume.value = ev.volume / 100f
                    }
                    else -> {}
                }
            }
        }
        s.attach(serverHttp, token)
        s.send(ClientEvent.ViewportRequest(_viewportMode.value))
    }

    fun detach() {
        socket?.detach()
        socket = null
    }

    private fun decodeFrame(b64: String) {
        viewModelScope.launch(Dispatchers.Default) {
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return@launch
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@launch
            _frame.value = bmp
        }
    }

    // ---- actions (one tap each) ----
    fun cursor(dx: Float, dy: Float) = socket?.send(ClientEvent.CursorMove(dx, dy))
    fun click(button: MouseButton = MouseButton.Left) = socket?.send(ClientEvent.Click(button))
    fun scroll(dx: Float, dy: Float) = socket?.send(ClientEvent.Scroll(dx, dy))
    fun media(action: MediaAction) = socket?.send(ClientEvent.Media(action))
    fun setVolume(v: Float) {
        _volume.value = v
        socket?.send(ClientEvent.Volume((v * 100).toInt()))
    }
    fun lock() = socket?.send(ClientEvent.LockPc)
    fun shutdown(confirmed: Boolean) = socket?.send(ClientEvent.ShutdownPc(confirmed))
    fun setViewport(mode: ViewportMode) {
        _viewportMode.value = mode
        socket?.send(ClientEvent.ViewportRequest(mode))
    }
    fun sendText(target: TextTarget, text: String) = socket?.send(ClientEvent.TextInput(target, text))
    fun key(key: String, modifiers: List<String> = emptyList()) = socket?.send(ClientEvent.KeyPress(key, modifiers))
    fun shortcut(id: String) = socket?.send(ClientEvent.Shortcut(id))
    fun browserNavigate(url: String) = socket?.send(ClientEvent.BrowserNavigate(url))
    fun browserAction(action: BrowserAction) = socket?.send(ClientEvent.BrowserActionEv(action))
    fun appAction(appId: String, action: AppAction) = socket?.send(ClientEvent.AppActionEv(appId, action))

    override fun onCleared() {
        socket?.close()
        socket = null
    }
}
