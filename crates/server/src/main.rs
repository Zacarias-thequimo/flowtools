//! FlowTools session server (v1).
//!
//! HTTPS (here HTTP for local MVP, TLS terminated in front for prod):
//!   POST /v1/pair/start, POST /v1/pair/claim, POST /v1/devices/:id/revoke
//!   GET  /v1/session/state, GET /v1/health
//! WebSocket (low latency): WS /v1/session/ws?token=...
//! Priority: direct local connection. Remote relay is a clearly-flagged
//! opt-in capacity (prepared, disabled by default).

use axum::{
    extract::{Path, Query, State, WebSocketUpgrade},
    http::StatusCode,
    response::{IntoResponse, Json},
    routing::{get, post},
    Router,
};
use flowtools_protocol::*;
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    net::SocketAddr,
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::sync::RwLock;
use tracing::info;
use uuid::Uuid;

const CODE_TTL_SECS: u64 = 300;
const SESSION_TTL_SECS: i64 = 3600 * 8; // configurable session timeout

#[derive(Clone)]
struct AppState {
    inner: Arc<RwLock<Store>>,
    relay_enabled: bool,
}

#[derive(Default)]
struct Store {
    pairings: HashMap<String, StoredPairing>,
    sessions: HashMap<String, SessionToken>, // by token
    revoked_devices: HashSet<String>,
}

struct StoredPairing {
    challenge: PairChallenge,
    request: PairRequest,
    created_unix: u64,
}

fn now_unix() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs()
}

fn six_digit_code() -> String {
    // Not crypto PIN for prod HSM; fine for LAN MVP with 5min TTL + one-shot.
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    let mut h = DefaultHasher::new();
    Uuid::new_v4().hash(&mut h);
    format!("{:06}", h.finish() % 1_000_000)
}

// ------------------------------- HTTP --------------------------------------

#[derive(Debug, Serialize)]
struct HealthResp {
    ok: bool,
    relay_enabled: bool,
    version: &'static str,
}

async fn health(State(s): State<AppState>) -> Json<HealthResp> {
    Json(HealthResp {
        ok: true,
        relay_enabled: s.relay_enabled,
        version: "1.0.0",
    })
}

async fn pair_start(
    State(s): State<AppState>,
    Json(req): Json<PairRequest>,
) -> impl IntoResponse {
    let pairing_id = Uuid::new_v4().to_string();
    let code = six_digit_code();
    // In prod behind HTTPS; host discovered via mDNS/LAN in PC client.
    let qr_payload = format!("flowtools://pair?id={}&code={}", pairing_id, code);
    let challenge = PairChallenge {
        pairing_id: pairing_id.clone(),
        code,
        qr_payload,
        expires_in_secs: CODE_TTL_SECS,
    };
    let mut w = s.inner.write().await;
    w.pairings.insert(
        pairing_id,
        StoredPairing {
            challenge: challenge.clone(),
            request: req,
            created_unix: now_unix(),
        },
    );
    (StatusCode::CREATED, Json(challenge))
}

async fn pair_claim(
    State(s): State<AppState>,
    Json(claim): Json<PairClaim>,
) -> impl IntoResponse {
    let mut w = s.inner.write().await;
    let stored = match w.pairings.get(&claim.pairing_id) {
        Some(p) => p,
        None => {
            return (
                StatusCode::NOT_FOUND,
                Json(serde_json::json!({"error": UserErrorCode::InvalidCode})),
            )
        }
    };
    if now_unix().saturating_sub(stored.created_unix) > CODE_TTL_SECS {
        w.pairings.remove(&claim.pairing_id);
        return (
            StatusCode::GONE,
            Json(serde_json::json!({"error": UserErrorCode::ExpiredSession})),
        );
    }
    if stored.challenge.code != claim.code {
        return (
            StatusCode::UNAUTHORIZED,
            Json(serde_json::json!({"error": UserErrorCode::InvalidCode})),
        );
    }
    // Approved must be subset of offered (granular permissions).
    if !claim.approved.is_subset(&stored.request.offered) {
        return (
            StatusCode::FORBIDDEN,
            Json(serde_json::json!({"error": UserErrorCode::PermissionNeeded})),
        );
    }
    let device_id = Uuid::new_v4().to_string();
    let token = Uuid::new_v4().to_string();
    let session = SessionToken {
        session_id: Uuid::new_v4().to_string(),
        device_id: device_id.clone(),
        pc_id: stored.request.pc_id.clone(),
        token: token.clone(),
        granted: claim.approved.clone(),
        expires_at_unix: now_unix() as i64 + SESSION_TTL_SECS,
    };
    w.sessions.insert(token, session.clone());
    w.pairings.remove(&claim.pairing_id);
    info!(device_id, pc_id = %session.pc_id, "paired");
    (
        StatusCode::CREATED,
        Json(serde_json::to_value(&session).unwrap()),
    )
}

async fn revoke(
    State(s): State<AppState>,
    Path(device_id): Path<String>,
) -> impl IntoResponse {
    let mut w = s.inner.write().await;
    w.revoked_devices.insert(device_id.clone());
    w.sessions.retain(|_, sess| sess.device_id != device_id);
    info!(device_id, "revoked");
    (
        StatusCode::OK,
        Json(serde_json::json!({"ok": true, "device_id": device_id})),
    )
}

#[derive(Deserialize)]
struct SessionQuery {
    token: Option<String>,
}

async fn session_state(
    State(s): State<AppState>,
    Query(q): Query<SessionQuery>,
) -> impl IntoResponse {
    let r = s.inner.read().await;
    let state = match &q.token {
        None => SessionState::Unpaired,
        Some(t) => match r.sessions.get(t) {
            None => SessionState::Offline,
            Some(sess) => {
                if r.revoked_devices.contains(&sess.device_id) {
                    SessionState::Offline
                } else if (sess.expires_at_unix as u64) < now_unix() {
                    SessionState::Offline
                } else {
                    SessionState::Connected
                }
            }
        },
    };
    Json(serde_json::json!({
        "state": state,
        "message": state.user_message(),
        "relay_enabled": s.relay_enabled,
    }))
}

// ----------------------------- WebSocket -----------------------------------

async fn ws_handler(
    State(s): State<AppState>,
    ws: WebSocketUpgrade,
    Query(q): Query<SessionQuery>,
) -> impl IntoResponse {
    let token = q.token.unwrap_or_default();
    ws.on_upgrade(move |socket| handle_socket(socket, s, token))
}

async fn handle_socket(
    mut socket: axum::extract::ws::WebSocket,
    state: AppState,
    token: String,
) {
    use axum::extract::ws::Message;
    // Auth first; rotate token on connect (rotating session tokens).
    let granted: HashSet<Capability> = {
        let mut w = state.inner.write().await;
        match w.sessions.remove(&token) {
            Some(sess)
                if (sess.expires_at_unix as u64) >= now_unix()
                    && !w.revoked_devices.contains(&sess.device_id) =>
            {
                let rotated = SessionToken {
                    token: Uuid::new_v4().to_string(),
                    expires_at_unix: now_unix() as i64 + SESSION_TTL_SECS,
                    ..sess
                };
                let g = rotated.granted.clone();
                let new_token = rotated.token.clone();
                w.sessions.insert(new_token.clone(), rotated);
                let _ = &new_token;
                g
            }
            _ => {
                let _ = socket
                    .send(Message::Text(
                        serde_json::to_string(&ServerEvent::Error {
                            code: UserErrorCode::ExpiredSession,
                        })
                        .unwrap()
                        .into(),
                    ))
                    .await;
                return;
            }
        }
    };

    let _ = socket
        .send(Message::Text(
            serde_json::to_string(&ServerEvent::State {
                state: SessionState::Connected,
            })
            .unwrap()
            .into(),
        ))
        .await;

    // MVP relay loop: validate capability per event, ack.
    // Real input execution happens in pc-client; server only routes.
    while let Some(Ok(msg)) = socket.recv().await {
        let text = match msg {
            Message::Text(t) => t.to_string(),
            Message::Close(_) => break,
            _ => continue,
        };
        let event: Result<ClientEvent, _> = serde_json::from_str(&text);
        let reply = match event {
            Err(_) => ServerEvent::Error {
                code: UserErrorCode::Unknown,
            },
            Ok(ev) => {
                if ev.tool_id() == "shutdown" {
                    // Shutdown always needs explicit confirmation client-side;
                    // double-check server-side.
                    if let ClientEvent::ShutdownPc { confirmed: false } = ev {
                        ServerEvent::Error {
                            code: UserErrorCode::Unknown,
                        }
                    } else if !granted.contains(&Capability::System) {
                        ServerEvent::Error {
                            code: UserErrorCode::PermissionNeeded,
                        }
                    } else {
                        ServerEvent::Ack {
                            ok: true,
                            message: "A desligar o PC.".into(),
                        }
                    }
                } else if missing_capabilities(&granted, &required_for_event(&ev)).is_empty()
                {
                    ServerEvent::Ack {
                        ok: true,
                        message: "OK".into(),
                    }
                } else {
                    ServerEvent::Error {
                        code: UserErrorCode::PermissionNeeded,
                    }
                }
            }
        };
        let _ = socket
            .send(Message::Text(
                serde_json::to_string(&reply).unwrap().into(),
            ))
            .await;
    }
}

fn required_for_event(ev: &ClientEvent) -> Vec<Capability> {
    match ev {
        ClientEvent::CursorMove { .. } | ClientEvent::Click { .. } | ClientEvent::Scroll { .. } => {
            required_for_tool("remote_control")
        }
        ClientEvent::KeyPress { .. } | ClientEvent::Shortcut { .. } => {
            required_for_tool("shortcuts")
        }
        ClientEvent::TextInput { .. } => required_for_tool("quick_text"),
        ClientEvent::Media { .. } | ClientEvent::Volume { .. } => required_for_tool("audio"),
        ClientEvent::BrowserNavigate { .. } | ClientEvent::BrowserAction { .. } => {
            required_for_tool("browser")
        }
        ClientEvent::AppAction { .. } => required_for_tool("app_control"),
        ClientEvent::LockPc | ClientEvent::ShutdownPc { .. } => required_for_tool("lock"),
        ClientEvent::ViewportRequest { .. } => vec![Capability::ScreenCapture],
        ClientEvent::FileOffer { .. } => required_for_tool("file_transfer"),
        ClientEvent::Ping => vec![],
    }
}

trait ToolId {
    fn tool_id(&self) -> &'static str;
}

impl ToolId for ClientEvent {
    fn tool_id(&self) -> &'static str {
        match self {
            ClientEvent::ShutdownPc { .. } => "shutdown",
            _ => "other",
        }
    }
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_env_filter("flowtools=debug,tower_http=debug")
        .init();

    let relay_enabled = std::env::var("FLOWTOOLS_RELAY")
        .map(|v| v == "1" || v.to_lowercase() == "true")
        .unwrap_or(false); // local-first, relay opt-in

    let state = AppState {
        inner: Arc::new(RwLock::new(Store::default())),
        relay_enabled,
    };

    let app = Router::new()
        .route("/v1/health", get(health))
        .route("/v1/pair/start", post(pair_start))
        .route("/v1/pair/claim", post(pair_claim))
        .route("/v1/devices/:id/revoke", post(revoke))
        .route("/v1/session/state", get(session_state))
        .route("/v1/session/ws", get(ws_handler))
        .with_state(state)
        .layer(tower_http::cors::CorsLayer::permissive());

    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8787);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    info!("FlowTools session server on {addr} (relay={relay_enabled})");
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn pairing_code_has_6_digits() {
        let c = six_digit_code();
        assert_eq!(c.len(), 6);
        assert!(c.chars().all(|ch| ch.is_ascii_digit()));
    }

    #[tokio::test]
    async fn approved_must_be_subset_of_offered() {
        let offered: HashSet<Capability> =
            [Capability::Cursor, Capability::Keyboard].into_iter().collect();
        let bad: HashSet<Capability> = [Capability::Files].into_iter().collect();
        assert!(!bad.is_subset(&offered));
    }
}
