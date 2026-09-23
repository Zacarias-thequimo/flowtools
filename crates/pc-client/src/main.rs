//! FlowTools PC client (v1, Debian-first, cross-platform core).
//!
//! Responsibilities:
//! - register PC (`POST /v1/pair/start`), show QR/code + requested permissions
//! - visible session indicator while remote session is active
//! - receive cursor/keyboard/media/volume/app/browser/shortcut/text/file events
//! - capture authorized window/viewport, send remote-view frames
//! - authorize/revoke devices, enforce allowed-apps list
//!
//! Platform abstraction: `InputController`, `ScreenCapturer`, `AppManager`
//! have Linux (enigo/xcap stubs here) impls; Windows/macOS plug in later
//! without changing the session core.

use clap::Parser;
use flowtools_protocol::*;
use futures_util::{SinkExt as _, StreamExt as _};
use std::collections::HashSet;
use tokio_tungstenite::connect_async;

#[derive(Parser, Debug)]
#[command(name = "flowtools-pc", version, about = "FlowTools PC client")]
struct Args {
    /// Session server base URL (local-first).
    #[arg(long, default_value = "http://127.0.0.1:8787")]
    server: String,
    #[arg(long, default_value = "PC de trabalho")]
    pc_name: String,
}

// ------------------------- Platform abstractions ---------------------------

trait InputController: Send + Sync {
    fn move_cursor(&self, dx: f32, dy: f32);
    fn click(&self, button: MouseButton);
    fn scroll(&self, dx: f32, dy: f32);
    fn key_press(&self, key: &str, modifiers: &[String]);
    fn type_text(&self, target: TextTarget, text: &str);
    fn media(&self, action: MediaAction);
    fn set_volume(&self, level: u8);
    fn lock(&self);
    fn shutdown(&self);
}

trait AppManager: Send + Sync {
    fn running_apps(&self) -> Vec<AppInfo>;
    fn allowed_apps(&self) -> Vec<AppInfo>;
    fn app_action(&self, app_id: &str, action: AppAction) -> bool;
}

struct LinuxStubInput;
struct LinuxStubApps {
    allowed: Vec<AppInfo>,
}

impl InputController for LinuxStubInput {
    fn move_cursor(&self, dx: f32, dy: f32) {
        // TODO: enigo/mouse warping; stub logs for MVP.
        tracing::info!("cursor move {dx:+.1},{dy:+.1}");
    }
    fn click(&self, button: MouseButton) {
        tracing::info!("click {:?}", button);
    }
    fn scroll(&self, dx: f32, dy: f32) {
        tracing::info!("scroll {dx:+.1},{dy:+.1}");
    }
    fn key_press(&self, key: &str, modifiers: &[String]) {
        tracing::info!("key {key} mods={modifiers:?}");
    }
    fn type_text(&self, target: TextTarget, text: &str) {
        tracing::info!("text -> {:?} ({} chars)", target, text.len());
    }
    fn media(&self, action: MediaAction) {
        tracing::info!("media {:?}", action);
    }
    fn set_volume(&self, level: u8) {
        tracing::info!("volume {level}");
    }
    fn lock(&self) {
        tracing::info!("lock PC");
        let _ = std::process::Command::new("loginctl").arg("lock-session").spawn();
    }
    fn shutdown(&self) {
        tracing::info!("shutdown PC (confirmed)");
        let _ = std::process::Command::new("systemctl")
            .args(["poweroff"])
            .spawn();
    }
}

impl AppManager for LinuxStubApps {
    fn running_apps(&self) -> Vec<AppInfo> {
        vec![AppInfo {
            app_id: "browser".into(),
            name: "Browser".into(),
            running: true,
        }]
    }
    fn allowed_apps(&self) -> Vec<AppInfo> {
        self.allowed.clone()
    }
    fn app_action(&self, app_id: &str, action: AppAction) -> bool {
        // Enforce allow-list: never expose all installed apps.
        if !self.allowed.iter().any(|a| a.app_id == app_id) {
            tracing::warn!("blocked app action for non-allowed {app_id}");
            return false;
        }
        tracing::info!("app {app_id} -> {:?}", action);
        true
    }
}

/// Visible indicator while a remote session is active (acceptance #16).
/// MVP: persistent console + desktop notification; Tauri tray plugs in here.
fn session_indicator(active: bool, device: &str) {
    if active {
        println!("● FlowTools: sessão remota ATIVA ({device}) — comandos a serem recebidos.");
        let _ = std::process::Command::new("notify-send")
            .args(["FlowTools", &format!("Sessão remota ativa: {device}")])
            .spawn();
    } else {
        println!("○ FlowTools: sem sessão remota.");
    }
}

fn default_allowed_apps() -> Vec<AppInfo> {
    vec![
        AppInfo { app_id: "browser".into(), name: "Browser".into(), running: false },
        AppInfo { app_id: "vlc".into(), name: "VLC".into(), running: false },
        AppInfo { app_id: "vscode".into(), name: "VS Code".into(), running: false },
    ]
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt().with_env_filter("flowtools=info").init();
    let args = Args::parse();
    let pc_id = format!("pc-{}", &uuid::Uuid::new_v4().to_string()[..8]);

    let offered: HashSet<Capability> = Capability::all().into_iter().collect();
    let http = reqwest::Client::new();

    // 1. Register / start pairing.
    let resp = http
        .post(format!("{}/v1/pair/start", args.server))
        .json(&PairRequest {
            pc_name: args.pc_name.clone(),
            pc_id: pc_id.clone(),
            offered: offered.clone(),
            local_only: true,
        })
        .send()
        .await?;
    let challenge: PairChallenge = resp.json().await?;
    println!("Emparelhe o telemóvel:");
    println!("  PC: {}", args.pc_name);
    println!("  Código temporário: {}", challenge.code);
    println!("  QR: {}", challenge.qr_payload);
    println!("  Permissões solicitadas:");
    for c in Capability::all() {
        println!("    - {}", c.label());
    }
    println!("(O utilizador não é obrigado a autorizar todas.)");

    session_indicator(false, "");
    println!("A aguardar claim em POST /v1/pair/claim ... (Ctrl+C para sair)");

    // MVP: poll sessions via state endpoint is done by Android; pc-client
    // would normally open a persistent control channel. Keep alive here.
    let input = LinuxStubInput;
    let apps = LinuxStubApps { allowed: default_allowed_apps() };
    let _ = (input, apps);

    // Placeholder control WS: connects once a token exists (poll stdin).
    // Full impl: server pushes to pc channel; kept simple for v1 foundation.
    let _ = connect_async("ws://127.0.0.1:8787/v1/session/ws?token=none");
    tokio::signal::ctrl_c().await?;
    session_indicator(false, "");
    Ok(())
}

// anyhow without adding dep: minimal alias
mod anyhow {
    pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
}
