//! FlowTools shared protocol contract (v1).
//!
//! Single source of truth for session states, capabilities,
//! pairing payloads, WebSocket events and user-facing errors.
//! Used by `flowtools-server` (Axum), `flowtools-pc` (Rust)
//! and mirrored by the Android client (Kotlin).

use serde::{Deserialize, Serialize};
use std::collections::HashSet;

// ---------------------------------------------------------------------------
// Session & connection states (never expose socket internals to UI)
// ---------------------------------------------------------------------------

/// Canonical session states. Android must render text + icon, never color only.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Hash)]
#[serde(rename_all = "snake_case")]
pub enum SessionState {
    Unpaired,
    Pairing,
    Connected,
    Reconnecting,
    Offline,
    PermissionRequired,
}

impl SessionState {
    /// User-facing PT message (UI layer may override, but default here).
    pub fn user_message(&self) -> &'static str {
        match self {
            SessionState::Unpaired => "Ligue um PC para começar.",
            SessionState::Pairing => "A ligar ao PC…",
            SessionState::Connected => "Ligado",
            SessionState::Reconnecting => "A ligação foi interrompida.",
            SessionState::Offline => "O PC não está disponível.",
            SessionState::PermissionRequired => "Esta ferramenta precisa de autorização.",
        }
    }

    pub fn is_usable(&self) -> bool {
        matches!(self, SessionState::Connected)
    }
}

// ---------------------------------------------------------------------------
// Granular capabilities / permissions
// ---------------------------------------------------------------------------

/// Granular capabilities. User is never forced to grant all.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Hash)]
#[serde(rename_all = "snake_case")]
pub enum Capability {
    Cursor,
    Keyboard,
    ScreenCapture,
    Browser,
    Applications,
    Files,
    Notifications,
    Media,
    System, // lock / shutdown (dangerous, always explicit)
}

impl Capability {
    pub fn all() -> Vec<Capability> {
        vec![
            Capability::Cursor,
            Capability::Keyboard,
            Capability::ScreenCapture,
            Capability::Browser,
            Capability::Applications,
            Capability::Files,
            Capability::Notifications,
            Capability::Media,
            Capability::System,
        ]
    }

    pub fn label(&self) -> &'static str {
        match self {
            Capability::Cursor => "Cursor",
            Capability::Keyboard => "Teclado",
            Capability::ScreenCapture => "Captura de ecrã",
            Capability::Browser => "Browser",
            Capability::Applications => "Aplicações",
            Capability::Files => "Ficheiros",
            Capability::Notifications => "Notificações",
            Capability::Media => "Multimédia",
            Capability::System => "Sistema (bloquear/desligar)",
        }
    }
}

/// Permission check helpers (unit-tested).
pub fn has_capability(granted: &HashSet<Capability>, required: Capability) -> bool {
    granted.contains(&required)
}

pub fn missing_capabilities(
    granted: &HashSet<Capability>,
    required: &[Capability],
) -> Vec<Capability> {
    required
        .iter()
        .copied()
        .filter(|c| !granted.contains(c))
        .collect()
}

/// Tool -> required capabilities mapping (v1).
pub fn required_for_tool(tool: &str) -> Vec<Capability> {
    match tool {
        "remote_control" => vec![Capability::Cursor, Capability::Media],
        "browser" => vec![Capability::Browser, Capability::ScreenCapture],
        "app_control" => vec![Capability::Applications, Capability::ScreenCapture],
        "quick_text" => vec![Capability::Keyboard],
        "shortcuts" => vec![Capability::Keyboard],
        "screen_capture" => vec![Capability::ScreenCapture],
        "file_transfer" => vec![Capability::Files],
        "notifications" => vec![Capability::Notifications],
        "audio" => vec![Capability::Media],
        "lock" | "shutdown" => vec![Capability::System],
        _ => vec![],
    }
}

// ---------------------------------------------------------------------------
// Pairing
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairRequest {
    pub pc_name: String,
    pub pc_id: String,
    /// Capabilities the PC offers (subset of Capability::all()).
    pub offered: HashSet<Capability>,
    pub local_only: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairChallenge {
    pub pairing_id: String,
    /// 6-digit temporary code, 5 min TTL.
    pub code: String,
    /// QR payload: `flowtools://pair?id=...&code=...&host=...`
    pub qr_payload: String,
    pub expires_in_secs: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairClaim {
    pub pairing_id: String,
    pub code: String,
    pub device_name: String,
    /// Capabilities the user approved (must be subset of offered).
    pub approved: HashSet<Capability>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SessionToken {
    pub session_id: String,
    pub device_id: String,
    pub pc_id: String,
    pub token: String,
    pub granted: HashSet<Capability>,
    pub expires_at_unix: i64,
}

// ---------------------------------------------------------------------------
// WebSocket events (low latency). JSON frames, `type` discriminated.
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientEvent {
    CursorMove { dx: f32, dy: f32 },
    Click { button: MouseButton },
    Scroll { dx: f32, dy: f32 },
    KeyPress { key: String, modifiers: Vec<String> },
    TextInput { target: TextTarget, text: String },
    Media { action: MediaAction },
    Volume { level: u8 },
    BrowserNavigate { url: String },
    BrowserAction { action: BrowserAction },
    AppAction { app_id: String, action: AppAction },
    Shortcut { id: String },
    LockPc,
    ShutdownPc { confirmed: bool },
    ViewportRequest { mode: ViewportMode, zoom: f32 },
    FileOffer { name: String, size_bytes: u64 },
    Ping,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum MouseButton {
    Left,
    Right,
    Middle,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum TextTarget {
    ActiveWindow,
    Browser,
    Clipboard,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum MediaAction {
    PlayPause,
    Previous,
    Next,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum BrowserAction {
    Back,
    Forward,
    Reload,
    OpenBrowser,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AppAction {
    Launch,
    Focus,
    Close,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ViewportMode {
    /// Default: fit 9:16 vertical, preserve aspect.
    FitPhone,
    Zoom,
    Desktop,
}

impl Default for ViewportMode {
    fn default() -> Self {
        ViewportMode::FitPhone
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerEvent {
    Ack { ok: bool, message: String },
    State { state: SessionState },
    Frame { viewport: ViewportMode, jpeg_base64: String, seq: u64 },
    MediaState { playing: bool, volume: u8 },
    AppList { running: Vec<AppInfo>, allowed: Vec<AppInfo> },
    FileProgress { name: String, done_bytes: u64, total_bytes: u64 },
    FileDone { name: String, destination: String },
    Error { code: UserErrorCode },
    Pong,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppInfo {
    pub app_id: String,
    pub name: String,
    pub running: bool,
}

/// User-facing errors: never leak socket/exception details.
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum UserErrorCode {
    Offline,
    PermissionNeeded,
    InvalidCode,
    ExpiredSession,
    FileExists,
    TransferCancelled,
    BrowserClosed,
    Unknown,
}

impl UserErrorCode {
    pub fn user_message(&self) -> &'static str {
        match self {
            UserErrorCode::Offline => "O PC não está disponível. Verifique a ligação.",
            UserErrorCode::PermissionNeeded => "Esta ferramenta precisa de autorização.",
            UserErrorCode::InvalidCode => "Código inválido. Tente novamente.",
            UserErrorCode::ExpiredSession => "A sessão expirou. Volte a ligar.",
            UserErrorCode::FileExists => "Já existe um ficheiro com esse nome.",
            UserErrorCode::TransferCancelled => "Transferência cancelada.",
            UserErrorCode::BrowserClosed => "O browser não está aberto no PC.",
            UserErrorCode::Unknown => "Algo correu mal. Tente novamente.",
        }
    }
}

/// Validate shutdown requires explicit confirmation (acceptance #14).
pub fn validate_shutdown(confirmed: bool) -> Result<(), UserErrorCode> {
    if confirmed {
        Ok(())
    } else {
        Err(UserErrorCode::Unknown)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn states_have_user_messages() {
        assert_eq!(
            SessionState::Unpaired.user_message(),
            "Ligue um PC para começar."
        );
        assert!(SessionState::Connected.is_usable());
        assert!(!SessionState::Offline.is_usable());
    }

    #[test]
    fn granular_permissions_are_subset_checked() {
        let granted: HashSet<Capability> =
            [Capability::Cursor, Capability::Media].into_iter().collect();
        assert!(has_capability(&granted, Capability::Cursor));
        assert!(!has_capability(&granted, Capability::Files));
        let missing = missing_capabilities(&granted, &[Capability::Cursor, Capability::Files]);
        assert_eq!(missing, vec![Capability::Files]);
    }

    #[test]
    fn browser_requires_browser_and_capture() {
        let req = required_for_tool("browser");
        assert!(req.contains(&Capability::Browser));
        assert!(req.contains(&Capability::ScreenCapture));
        assert!(!req.contains(&Capability::Files));
    }

    #[test]
    fn shutdown_needs_confirmation() {
        assert!(validate_shutdown(true).is_ok());
        assert!(validate_shutdown(false).is_err());
    }

    #[test]
    fn default_viewport_is_fit_phone_9_16() {
        assert_eq!(ViewportMode::default(), ViewportMode::FitPhone);
    }

    #[test]
    fn errors_do_not_leak_internals() {
        for code in [
            UserErrorCode::Offline,
            UserErrorCode::PermissionNeeded,
            UserErrorCode::Unknown,
        ] {
            let msg = code.user_message();
            assert!(!msg.contains("socket"));
            assert!(!msg.contains("Exception"));
            assert!(!msg.contains("tokio"));
        }
    }
}
