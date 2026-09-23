//! FlowTools session server (v1).
//!
//! HTTPS (here HTTP for local MVP, TLS terminated in front for prod):
//!   POST /v1/pair/start, POST /v1/pair/claim, POST /v1/devices/:id/revoke
//!   GET  /v1/session/state, GET /v1/health
//! WebSocket (low latency):
//!   WS /v1/session/ws?token=...   (Android: sends ClientEvent, gets ServerEvent)
//!   WS /v1/pc/channel?pc_id=...   (PC: receives ClientEvent, sends ServerEvent)
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
use futures::{SinkExt as _, StreamExt as _};
use serde::{Deserialize, Serialize};
use std::{
    collections::{HashMap, HashSet},
    net::SocketAddr,
    sync::Arc,
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::sync::{mpsc, RwLock};
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
    known_pcs: HashSet<String>,
    /// Live routing: pc_id -> senders (to PC, to each Android client by token).
    links: HashMap<String, PcLink>,
}

#[derive(Default)]
struct PcLink {
    pc_tx: Option<mpsc::UnboundedSender<String>>,
    android: HashMap<String, mpsc::UnboundedSender<String>>,
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

fn to_msg(json: &str) -> axum::extract::ws::Message {
    axum::extract::ws::Message::Text(json.to_owned().into())
}

fn server_msg(ev: &ServerEvent) -> axum::extract::ws::Message {
    to_msg(&serde_json::to_string(ev).unwrap())
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
    w.known_pcs.insert(req.pc_id.clone());
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
    // Collect tokens of this device, drop sessions and live senders.
    let dead_tokens: Vec<String> = w
        .sessions
        .iter()
        .filter(|(_, sess)| sess.device_id == device_id)
        .map(|(tok, _)| tok.clone())
        .collect();
    w.sessions.retain(|_, sess| sess.device_id != device_id);
    for link in w.links.values_mut() {
        for tok in &dead_tokens {
            link.android.remove(tok);
        }
    }
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

#[derive(Deserialize)]
struct PcQuery {
    pc_id: Option<String>,
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

// ------------------------- Android WebSocket -------------------------------

async fn ws_handler(
    State(s): State<AppState>,
    ws: WebSocketUpgrade,
    Query(q): Query<SessionQuery>,
) -> impl IntoResponse {
    let token = q.token.unwrap_or_default();
    ws.on_upgrade(move |socket| handle_android(socket, s, token))
}

async fn handle_android(
    socket: axum::extract::ws::WebSocket,
    state: AppState,
    token: String,
) {
    use axum::extract::ws::Message;
    // Auth first; rotate token on connect and tell the client the new one.
    let (granted, pc_id, new_token) = {
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
                let out = (
                    rotated.granted.clone(),
                    rotated.pc_id.clone(),
                    rotated.token.clone(),
                );
                w.sessions.insert(rotated.token.clone(), rotated);
                out
            }
            _ => {
                let mut socket = socket;
                let _ = socket.send(server_msg(&ServerEvent::Error {
                    code: UserErrorCode::ExpiredSession,
                })).await;
                return;
            }
        }
    };

    // Register this connection for PC->Android fan-out (frames, etc).
    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    let _ = tx.send(serde_json::to_string(&ServerEvent::State { state: SessionState::Connected }).unwrap());
    let _ = tx.send(serde_json::to_string(&ServerEvent::Token { token: new_token.clone() }).unwrap());
    {
        let mut w = state.inner.write().await;
        w.links.entry(pc_id.clone()).or_default().android.insert(new_token.clone(), tx.clone());
    }

    let (mut ws_tx, mut ws_rx) = socket.split();
    // Pump server->client messages.
    let pump = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if ws_tx.send(to_msg(&msg)).await.is_err() {
                break;
            }
        }
    });

    // Main loop: validate capability per event, forward to PC.
    while let Some(Ok(msg)) = ws_rx.next().await {
        let text = match msg {
            Message::Text(t) => t.to_string(),
            Message::Close(_) => break,
            _ => continue,
        };
        let event: Result<ClientEvent, _> = serde_json::from_str(&text);
        let reply: Option<ServerEvent> = match event {
            Err(_) => Some(ServerEvent::Error { code: UserErrorCode::Unknown }),
            Ok(ClientEvent::Ping) => Some(ServerEvent::Pong),
            Ok(ev) => {
                if let ClientEvent::ShutdownPc { confirmed: false } = ev {
                    Some(ServerEvent::Error { code: UserErrorCode::Unknown })
                } else if !missing_capabilities(&granted, &required_for_event(&ev)).is_empty() {
                    Some(ServerEvent::Error { code: UserErrorCode::PermissionNeeded })
                } else {
                    // Forward to PC; PC acks and executes.
                    let pc_tx = {
                        state.inner.read().await
                            .links.get(&pc_id)
                            .and_then(|l| l.pc_tx.clone())
                    };
                    match pc_tx {
                        Some(pc) if pc.send(text).is_ok() => None,
                        _ => Some(ServerEvent::Error { code: UserErrorCode::Offline }),
                    }
                }
            }
        };
        if let Some(ev) = reply {
            let _ = tx.send(serde_json::to_string(&ev).unwrap());
        }
    }

    // Cleanup.
    {
        let mut w = state.inner.write().await;
        if let Some(link) = w.links.get_mut(&pc_id) {
            link.android.remove(&new_token);
        }
    }
    pump.abort();
}

// ---------------------------- PC WebSocket ---------------------------------

async fn pc_handler(
    State(s): State<AppState>,
    ws: WebSocketUpgrade,
    Query(q): Query<PcQuery>,
) -> impl IntoResponse {
    let pc_id = q.pc_id.unwrap_or_default();
    ws.on_upgrade(move |socket| handle_pc(socket, s, pc_id))
}

async fn handle_pc(
    socket: axum::extract::ws::WebSocket,
    state: AppState,
    pc_id: String,
) {
    use axum::extract::ws::Message;
    let known = state.inner.read().await.known_pcs.contains(&pc_id);
    let (mut ws_tx, mut ws_rx) = socket.split();
    if !known {
        let _ = ws_tx.send(server_msg(&ServerEvent::Error { code: UserErrorCode::ExpiredSession })).await;
        return;
    }

    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    {
        let mut w = state.inner.write().await;
        w.links.entry(pc_id.clone()).or_default().pc_tx = Some(tx);
    }
    info!(pc_id, "pc channel connected");

    let pump = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if ws_tx.send(to_msg(&msg)).await.is_err() {
                break;
            }
        }
    });

    // PC -> Android fan-out: frames, media state, file progress, acks.
    while let Some(Ok(msg)) = ws_rx.next().await {
        let text = match msg {
            Message::Text(t) => t.to_string(),
            Message::Close(_) => break,
            _ => continue,
        };
        // Validate it is a well-formed ServerEvent (no raw passthrough).
        if serde_json::from_str::<ServerEvent>(&text).is_err() {
            continue;
        }
        let targets = {
            state.inner.read().await
                .links.get(&pc_id)
                .map(|l| l.android.values().cloned().collect::<Vec<_>>())
                .unwrap_or_default()
        };
        for target in targets {
            let _ = target.send(text.clone());
        }
    }

    {
        let mut w = state.inner.write().await;
        if let Some(link) = w.links.get_mut(&pc_id) {
            link.pc_tx = None;
        }
    }
    info!(pc_id, "pc channel disconnected");
    pump.abort();
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

fn app(state: AppState) -> Router {
    Router::new()
        .route("/v1/health", get(health))
        .route("/v1/pair/start", post(pair_start))
        .route("/v1/pair/claim", post(pair_claim))
        .route("/v1/devices/:id/revoke", post(revoke))
        .route("/v1/session/state", get(session_state))
        .route("/v1/session/ws", get(ws_handler))
        .route("/v1/pc/channel", get(pc_handler))
        .with_state(state)
        .layer(tower_http::cors::CorsLayer::permissive())
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

    let port: u16 = std::env::var("PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8787);
    let addr = SocketAddr::from(([0, 0, 0, 0], port));
    info!("FlowTools session server on {addr} (relay={relay_enabled})");
    let listener = tokio::net::TcpListener::bind(addr).await.unwrap();
    axum::serve(listener, app(state)).await.unwrap();
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::StreamExt;

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

    // ---- Integration: pairing -> PC channel + Android WS forwarding --------

    async fn spawn_test_server() -> String {
        let state = AppState {
            inner: Arc::new(RwLock::new(Store::default())),
            relay_enabled: false,
        };
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move {
            axum::serve(listener, app(state)).await.unwrap();
        });
        format!("http://{addr}")
    }

    async fn next_text<S>(stream: &mut S) -> String
    where
        S: StreamExt<Item = Result<tokio_tungstenite::tungstenite::Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
    {
        let msg = tokio::time::timeout(
            std::time::Duration::from_secs(5),
            stream.next(),
        )
        .await
        .expect("timed out waiting for ws message")
        .expect("stream ended")
        .unwrap();
        match msg {
            tokio_tungstenite::tungstenite::Message::Text(t) => t.to_string(),
            other => panic!("expected text, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn android_event_reaches_pc_and_frame_returns() {
        let base = spawn_test_server().await;
        let http = reqwest::Client::new();
        let ws_base = base.replacen("http", "ws", 1);

        // Pair with cursor + capture granted (files NOT granted).
        let challenge: PairChallenge = http
            .post(format!("{base}/v1/pair/start"))
            .json(&PairRequest {
                pc_name: "PC de trabalho".into(),
                pc_id: "pc-test".into(),
                offered: Capability::all().into_iter().collect(),
                local_only: true,
            })
            .send().await.unwrap()
            .json().await.unwrap();
        let session: SessionToken = http
            .post(format!("{base}/v1/pair/claim"))
            .json(&PairClaim {
                pairing_id: challenge.pairing_id,
                code: challenge.code,
                device_name: "Pixel".into(),
                approved: [Capability::Cursor, Capability::Media, Capability::ScreenCapture]
                    .into_iter().collect(),
            })
            .send().await.unwrap()
            .json().await.unwrap();

        // PC connects its channel.
        let (mut pc, _) = tokio_tungstenite::connect_async(format!("{ws_base}/v1/pc/channel?pc_id=pc-test"))
            .await.unwrap();
        // Android connects; rotation gives a fresh token.
        let (mut android, _) = tokio_tungstenite::connect_async(format!("{ws_base}/v1/session/ws?token={}", session.token))
            .await.unwrap();
        assert!(next_text(&mut android).await.contains("connected")); // State
        let token_msg = next_text(&mut android).await;
        let new_token: String = serde_json::from_str::<serde_json::Value>(&token_msg)
            .unwrap()["token"].as_str().unwrap().to_owned();
        assert_ne!(new_token, session.token);

        // Cursor event is forwarded to the PC.
        let cursor = serde_json::to_string(&ClientEvent::CursorMove { dx: 4.0, dy: -2.0 }).unwrap();
        android.send(tokio_tungstenite::tungstenite::Message::Text(cursor.clone().into())).await.unwrap();
        assert_eq!(next_text(&mut pc).await, cursor);

        // PC frame is fanned out to Android.
        let frame = serde_json::to_string(&ServerEvent::Frame {
            viewport: ViewportMode::FitPhone, jpeg_base64: "eA==".into(), seq: 7,
        }).unwrap();
        pc.send(tokio_tungstenite::tungstenite::Message::Text(frame.clone().into())).await.unwrap();
        assert_eq!(next_text(&mut android).await, frame);

        // FileOffer without Files capability -> PermissionNeeded, PC sees nothing.
        let offer = serde_json::to_string(&ClientEvent::FileOffer { name: "a.zip".into(), size_bytes: 9 }).unwrap();
        android.send(tokio_tungstenite::tungstenite::Message::Text(offer.into())).await.unwrap();
        assert!(next_text(&mut android).await.contains("permission_needed"));

        // Ping -> Pong locally (never forwarded).
        let ping = serde_json::to_string(&ClientEvent::Ping).unwrap();
        android.send(tokio_tungstenite::tungstenite::Message::Text(ping.into())).await.unwrap();
        assert!(next_text(&mut android).await.contains("pong"));

        // Old (rotated-out) token is rejected; new token reconnects (reconnect path).
        let (mut stale, _) = tokio_tungstenite::connect_async(format!("{ws_base}/v1/session/ws?token={}", session.token))
            .await.unwrap();
        assert!(next_text(&mut stale).await.contains("expired_session"));
        let (mut android2, _) = tokio_tungstenite::connect_async(format!("{ws_base}/v1/session/ws?token={new_token}"))
            .await.unwrap();
        assert!(next_text(&mut android2).await.contains("connected"));
    }
}
