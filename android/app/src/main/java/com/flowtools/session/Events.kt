package com.flowtools.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors flowtools-protocol ClientEvent JSON (field names must match serde). */
@Serializable
sealed interface ClientEvent {
    @Serializable @SerialName("cursor_move")
    data class CursorMove(val dx: Float, val dy: Float) : ClientEvent

    @Serializable @SerialName("click")
    data class Click(val button: MouseButton) : ClientEvent

    @Serializable @SerialName("scroll")
    data class Scroll(val dx: Float, val dy: Float) : ClientEvent

    @Serializable @SerialName("key_press")
    data class KeyPress(val key: String, val modifiers: List<String> = emptyList()) : ClientEvent

    @Serializable @SerialName("text_input")
    data class TextInput(val target: TextTarget, val text: String) : ClientEvent

    @Serializable @SerialName("media")
    data class Media(val action: MediaAction) : ClientEvent

    @Serializable @SerialName("volume")
    data class Volume(val level: Int) : ClientEvent

    @Serializable @SerialName("browser_navigate")
    data class BrowserNavigate(val url: String) : ClientEvent

    @Serializable @SerialName("browser_action")
    data class BrowserActionEv(val action: BrowserAction) : ClientEvent

    @Serializable @SerialName("app_action")
    data class AppActionEv(val app_id: String, val action: AppAction) : ClientEvent

    @Serializable @SerialName("shortcut")
    data class Shortcut(val id: String) : ClientEvent

    @Serializable @SerialName("lock_pc")
    data object LockPc : ClientEvent

    @Serializable @SerialName("shutdown_pc")
    data class ShutdownPc(val confirmed: Boolean) : ClientEvent

    @Serializable @SerialName("viewport_request")
    data class ViewportRequest(val mode: ViewportMode, val zoom: Float = 1f) : ClientEvent

    @Serializable @SerialName("file_offer")
    data class FileOffer(val name: String, val size_bytes: Long) : ClientEvent

    @Serializable @SerialName("ping")
    data object Ping : ClientEvent
}

@Serializable
enum class MouseButton { @SerialName("left") Left, @SerialName("right") Right, @SerialName("middle") Middle }

@Serializable
enum class TextTarget {
    @SerialName("active_window") ActiveWindow,
    @SerialName("browser") Browser,
    @SerialName("clipboard") Clipboard,
}

@Serializable
enum class MediaAction { @SerialName("play_pause") PlayPause, @SerialName("previous") Previous, @SerialName("next") Next }

@Serializable
enum class BrowserAction { @SerialName("back") Back, @SerialName("forward") Forward, @SerialName("reload") Reload, @SerialName("open_browser") OpenBrowser }

@Serializable
enum class AppAction { @SerialName("launch") Launch, @SerialName("focus") Focus, @SerialName("close") Close }

@Serializable
enum class ViewportMode { @SerialName("fit_phone") FitPhone, @SerialName("zoom") Zoom, @SerialName("desktop") Desktop }

/** Mirrors flowtools-protocol ServerEvent JSON. */
@Serializable
sealed interface ServerEvent {
    @Serializable @SerialName("ack")
    data class Ack(val ok: Boolean, val message: String) : ServerEvent

    @Serializable @SerialName("state")
    data class State(val state: ProtoState) : ServerEvent

    @Serializable @SerialName("token")
    data class Token(val token: String) : ServerEvent

    @Serializable @SerialName("frame")
    data class Frame(val viewport: ViewportMode, val jpeg_base64: String, val seq: Long) : ServerEvent

    @Serializable @SerialName("media_state")
    data class MediaState(val playing: Boolean, val volume: Int) : ServerEvent

    @Serializable @SerialName("file_progress")
    data class FileProgress(val name: String, val done_bytes: Long, val total_bytes: Long) : ServerEvent

    @Serializable @SerialName("file_done")
    data class FileDone(val name: String, val destination: String) : ServerEvent

    @Serializable @SerialName("file_ready")
    data class FileReady(val ticket: String, val name: String, val size_bytes: Long) : ServerEvent

    @Serializable @SerialName("error")
    data class Error(val code: UserErrorCode) : ServerEvent

    @Serializable @SerialName("pong")
    data object Pong : ServerEvent
}

@Serializable
enum class ProtoState {
    @SerialName("unpaired") Unpaired,
    @SerialName("pairing") Pairing,
    @SerialName("connected") Connected,
    @SerialName("reconnecting") Reconnecting,
    @SerialName("offline") Offline,
    @SerialName("permission_required") PermissionRequired,
}

@Serializable
enum class UserErrorCode {
    @SerialName("offline") Offline,
    @SerialName("permission_needed") PermissionNeeded,
    @SerialName("invalid_code") InvalidCode,
    @SerialName("expired_session") ExpiredSession,
    @SerialName("file_exists") FileExists,
    @SerialName("too_large") TooLarge,
    @SerialName("transfer_cancelled") TransferCancelled,
    @SerialName("browser_closed") BrowserClosed,
    @SerialName("unknown") Unknown,
}

fun ProtoState.toConnectionState(): ConnectionState = when (this) {
    ProtoState.Unpaired -> ConnectionState.Unpaired
    ProtoState.Pairing -> ConnectionState.Pairing
    ProtoState.Connected -> ConnectionState.Connected
    ProtoState.Reconnecting -> ConnectionState.Reconnecting
    ProtoState.Offline -> ConnectionState.Offline
    ProtoState.PermissionRequired -> ConnectionState.PermissionRequired
}
