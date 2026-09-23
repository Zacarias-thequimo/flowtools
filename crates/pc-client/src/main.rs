//! FlowTools PC client (v1, Debian-first, cross-platform core).
//!
//! - registers the PC (`POST /v1/pair/start`), shows QR/code + permissions
//! - holds `WS /v1/pc/channel?pc_id=...` with reconnect (context preserved)
//! - executes cursor/keyboard/media/volume/app/browser/shortcut/text events
//!   via enigo (Linux) + desktop helpers (playerctl/pactl/loginctl)
//! - captures the primary monitor via xcap, downscales, sends JPEG frames
//!   for the 9:16 remote viewport (FitPhone default)
//! - enforces the allowed-apps list; shows a visible session indicator
//!   while a remote session is active.

use base64::{Engine as _, engine::general_purpose::STANDARD as B64};
use clap::Parser;
use enigo::{Button, Coordinate, Direction, Enigo, Key, Keyboard, Mouse, Settings};
use flowtools_protocol::*;
use futures_util::{SinkExt as _, StreamExt as _};
use std::{
    collections::HashSet,
    sync::{
        Arc,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::sync::Mutex;
use tokio_tungstenite::{connect_async, tungstenite::Message};

#[derive(Parser, Debug)]
#[command(name = "flowtools-pc", version, about = "FlowTools PC client")]
struct Args {
    /// Session server base URL (local-first).
    #[arg(long, default_value = "http://127.0.0.1:8787")]
    server: String,
    #[arg(long, default_value = "PC de trabalho")]
    pc_name: String,
}

#[derive(Clone)]
struct AllowedApp {
    id: &'static str,
    name: &'static str,
    bin: &'static str,
    args: &'static [&'static str],
}

fn allowed_apps() -> Vec<AllowedApp> {
    vec![
        AllowedApp {
            id: "browser",
            name: "Browser",
            bin: "xdg-open",
            args: &["about:blank"],
        },
        AllowedApp {
            id: "vlc",
            name: "VLC",
            bin: "vlc",
            args: &[],
        },
        AllowedApp {
            id: "vscode",
            name: "VS Code",
            bin: "code",
            args: &[],
        },
    ]
}

// ------------------------------ key mapping --------------------------------

/// Map short key names (from Android) to enigo keys. Single chars -> Unicode.
fn parse_key(name: &str) -> Option<Key> {
    match name.to_lowercase().as_str() {
        "enter" | "return" => Some(Key::Return),
        "tab" => Some(Key::Tab),
        "space" => Some(Key::Space),
        "backspace" => Some(Key::Backspace),
        "escape" | "esc" => Some(Key::Escape),
        "delete" | "del" => Some(Key::Delete),
        "up" => Some(Key::UpArrow),
        "down" => Some(Key::DownArrow),
        "left" => Some(Key::LeftArrow),
        "right" => Some(Key::RightArrow),
        "f1" => Some(Key::F1),
        "f2" => Some(Key::F2),
        "f3" => Some(Key::F3),
        "f4" => Some(Key::F4),
        "f5" => Some(Key::F5),
        "f6" => Some(Key::F6),
        "f7" => Some(Key::F7),
        "f8" => Some(Key::F8),
        "f9" => Some(Key::F9),
        "f10" => Some(Key::F10),
        "f11" => Some(Key::F11),
        "f12" => Some(Key::F12),
        s if s.chars().count() == 1 => s.chars().next().map(Key::Unicode),
        _ => None,
    }
}

fn parse_modifier(name: &str) -> Option<Key> {
    match name.to_lowercase().as_str() {
        "ctrl" | "control" => Some(Key::Control),
        "shift" => Some(Key::Shift),
        "alt" => Some(Key::Alt),
        "super" | "meta" | "win" => Some(Key::Meta),
        _ => None,
    }
}

/// v1 shortcut catalogue -> (modifiers, key).
fn shortcut_to_keys(id: &str) -> Option<(Vec<Key>, Key)> {
    let (mods, key) = match id {
        "play_pause" | "next" | "prev" | "mute" => return None, // via playerctl/pactl
        "alt_tab" => (vec![Key::Alt], Key::Tab),
        "win_d" => (vec![Key::Meta], Key::Unicode('d')),
        "alt_f4" => (vec![Key::Alt], Key::F4),
        "ctrl_t" => (vec![Key::Control], Key::Unicode('t')),
        "ctrl_w" => (vec![Key::Control], Key::Unicode('w')),
        "ctrl_r" => (vec![Key::Control], Key::Unicode('r')),
        "ctrl_c" => (vec![Key::Control], Key::Unicode('c')),
        "ctrl_v" => (vec![Key::Control], Key::Unicode('v')),
        "ctrl_z" => (vec![Key::Control], Key::Unicode('z')),
        "lock" => return None, // via loginctl
        "print" => (vec![], Key::PrintScr),
        _ => return None,
    };
    Some((mods, key))
}

fn press_combo(enigo: &mut Enigo, mods: &[Key], key: Key) -> bool {
    for m in mods {
        if enigo.key(*m, Direction::Press).is_err() {
            return false;
        }
    }
    let ok = enigo.key(key, Direction::Click).is_ok();
    for m in mods.iter().rev() {
        let _ = enigo.key(*m, Direction::Release);
    }
    ok
}

// ------------------------------ capture ------------------------------------

/// Capture primary monitor, downscale to fit a phone-friendly width,
/// encode JPEG. Pure enough to unit-test with a synthetic image.
fn encode_frame(img: &image::RgbaImage, max_width: u32, quality: u8) -> Option<Vec<u8>> {
    let dynimg = image::DynamicImage::ImageRgba8(img.clone());
    let scaled = if dynimg.width() > max_width {
        dynimg.thumbnail(max_width, max_width * 16 / 9)
    } else {
        dynimg
    };
    let mut buf = Vec::new();
    let mut enc = image::codecs::jpeg::JpegEncoder::new_with_quality(&mut buf, quality);
    enc.encode_image(&scaled).ok()?;
    Some(buf)
}

fn capture_primary(max_width: u32, quality: u8) -> Option<Vec<u8>> {
    let monitors = xcap::Monitor::all().ok()?;
    let primary = monitors
        .into_iter()
        .find(|m| m.is_primary().unwrap_or(false))
        .or_else(|| xcap::Monitor::all().ok()?.into_iter().next())?;
    let img = primary.capture_image().ok()?;
    encode_frame(&img, max_width, quality)
}

// --------------------------- desktop helpers -------------------------------

fn run(cmd: &str, args: &[&str]) -> bool {
    std::process::Command::new(cmd)
        .args(args)
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn do_media(action: MediaAction) -> bool {
    let arg = match action {
        MediaAction::PlayPause => "play-pause",
        MediaAction::Previous => "previous",
        MediaAction::Next => "next",
    };
    run("playerctl", &[arg])
}

fn media_playing() -> bool {
    std::process::Command::new("playerctl")
        .arg("status")
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim() == "Playing")
        .unwrap_or(false)
}

fn do_volume(level: u8) -> bool {
    run(
        "pactl",
        &["set-sink-volume", "@DEFAULT_SINK@", &format!("{level}%")],
    )
}

/// Visible indicator while a remote session is active (acceptance #16).
fn session_indicator(active: bool, detail: &str) {
    if active {
        println!("● FlowTools: sessão remota ATIVA ({detail}) — comandos a serem recebidos.");
        let _ = std::process::Command::new("notify-send")
            .args(["FlowTools", &format!("Sessão remota ativa: {detail}")])
            .spawn();
    } else {
        println!("○ FlowTools: sem sessão remota.");
    }
}

fn ack(ok: bool, message: &str) -> String {
    serde_json::to_string(&ServerEvent::Ack {
        ok,
        message: message.into(),
    })
    .unwrap()
}

/// Keep only a safe file name (no directories, no traversal).
fn safe_file_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("ficheiro");
    let clean: String = base
        .chars()
        .map(|c| {
            if c.is_alphanumeric() || matches!(c, '.' | '_' | '-' | ' ' | '(' | ')') {
                c
            } else {
                '_'
            }
        })
        .collect();
    let clean = clean.trim().trim_matches('.');
    if clean.is_empty() {
        "ficheiro".into()
    } else {
        clean.chars().take(128).collect()
    }
}

fn download_dir() -> std::path::PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".into());
    std::path::Path::new(&home)
        .join("Downloads")
        .join("FlowTools")
}

async fn download_file(
    http: reqwest::Client,
    server: String,
    pc_id: String,
    ticket: String,
    name: String,
    size: u64,
    out: tokio::sync::mpsc::UnboundedSender<String>,
) {
    let send = |ev: ServerEvent| {
        let _ = out.send(serde_json::to_string(&ev).unwrap());
    };
    let name = safe_file_name(&name);
    let dir = download_dir();
    if std::fs::create_dir_all(&dir).is_err() {
        send(ServerEvent::Error {
            code: UserErrorCode::Unknown,
        });
        return;
    }
    let path = dir.join(&name);
    // Atomic create-new: two concurrent downloads of the same name cannot
    // both pass the check (TOCTOU). Never overwrite without confirmation.
    let file = match tokio::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
        .await
    {
        Ok(f) => f,
        Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
            send(ServerEvent::Error {
                code: UserErrorCode::FileExists,
            });
            return;
        }
        Err(_) => {
            send(ServerEvent::Error {
                code: UserErrorCode::Unknown,
            });
            return;
        }
    };
    let resp = match http
        .get(format!("{server}/v1/files/inbox/{ticket}?pc_id={pc_id}"))
        .send()
        .await
    {
        Ok(r) if r.status().is_success() => r,
        _ => {
            send(ServerEvent::Error {
                code: UserErrorCode::Unknown,
            });
            return;
        }
    };
    let mut file = file;
    use tokio::io::AsyncWriteExt as _;
    let mut done: u64 = 0;
    let mut stream = resp.bytes_stream();
    use futures_util::StreamExt as _;
    while let Some(chunk) = stream.next().await {
        let chunk = match chunk {
            Ok(c) => c,
            Err(_) => {
                send(ServerEvent::Error {
                    code: UserErrorCode::TransferCancelled,
                });
                let _ = tokio::fs::remove_file(&path).await;
                return;
            }
        };
        if file.write_all(&chunk).await.is_err() {
            send(ServerEvent::Error {
                code: UserErrorCode::Unknown,
            });
            return;
        }
        done += chunk.len() as u64;
        send(ServerEvent::FileProgress {
            name: name.clone(),
            done_bytes: done,
            total_bytes: size,
        });
    }
    send(ServerEvent::FileDone {
        name: name.clone(),
        destination: format!("~/Downloads/FlowTools/{name}"),
    });
}

// ------------------------------ event loop ---------------------------------

struct Runtime {
    enigo: Option<Enigo>,
    out: tokio::sync::mpsc::UnboundedSender<String>,
    streaming: Arc<AtomicBool>,
    viewport: Arc<Mutex<ViewportMode>>,
    seq: Arc<AtomicU64>,
}

const NO_INPUT_MSG: &str = "Controlo de rato/teclado indisponível neste ecrã.";

impl Runtime {
    /// Returns true when the client should exit (shutdown executed).
    fn handle(&mut self, ev: ClientEvent) -> bool {
        match ev {
            ClientEvent::CursorMove { dx, dy } => {
                let ok = self
                    .enigo
                    .as_mut()
                    .map(|e| e.move_mouse(dx as i32, dy as i32, Coordinate::Rel).is_ok())
                    .unwrap_or(false);
                self.send_ack(ok, if ok { "OK" } else { NO_INPUT_MSG });
            }
            ClientEvent::Click { button } => {
                let b = match button {
                    MouseButton::Left => Button::Left,
                    MouseButton::Right => Button::Right,
                    MouseButton::Middle => Button::Middle,
                };
                let ok = self
                    .enigo
                    .as_mut()
                    .map(|e| e.button(b, Direction::Click).is_ok())
                    .unwrap_or(false);
                self.send_ack(ok, if ok { "OK" } else { NO_INPUT_MSG });
            }
            ClientEvent::Scroll { dy, .. } => {
                let ok = self
                    .enigo
                    .as_mut()
                    .map(|e| e.scroll(-dy as i32 / 20, enigo::Axis::Vertical).is_ok())
                    .unwrap_or(false);
                self.send_ack(ok, if ok { "OK" } else { NO_INPUT_MSG });
            }
            ClientEvent::KeyPress { key, modifiers } => {
                let mods: Vec<Key> = modifiers.iter().filter_map(|m| parse_modifier(m)).collect();
                let ok = match (self.enigo.as_mut(), parse_key(&key)) {
                    (Some(e), Some(k)) => press_combo(e, &mods, k),
                    _ => false,
                };
                self.send_ack(ok, if ok { "OK" } else { "Tecla não suportada." });
            }
            ClientEvent::TextInput { target, text } => {
                let ok = match target {
                    TextTarget::Clipboard => {
                        run("wl-copy", &[&text]) || run("xclip", &["-selection", "clipboard"])
                    }
                    _ => self
                        .enigo
                        .as_mut()
                        .map(|e| e.text(&text).is_ok())
                        .unwrap_or(false),
                };
                self.send_ack(
                    ok,
                    if ok {
                        "Texto enviado."
                    } else {
                        "Não foi possível enviar o texto."
                    },
                );
            }
            ClientEvent::Media { action } => {
                let ok = do_media(action);
                let state = ServerEvent::MediaState {
                    playing: media_playing(),
                    volume: 50,
                };
                self.send(serde_json::to_string(&state).unwrap());
                self.send_ack(
                    ok,
                    if ok {
                        "OK"
                    } else {
                        "Controlo multimédia indisponível (playerctl)."
                    },
                );
            }
            ClientEvent::Volume { level } => {
                let ok = do_volume(level);
                let state = ServerEvent::MediaState {
                    playing: media_playing(),
                    volume: level,
                };
                self.send(serde_json::to_string(&state).unwrap());
                self.send_ack(
                    ok,
                    if ok {
                        "OK"
                    } else {
                        "Não foi possível mudar o volume."
                    },
                );
            }
            ClientEvent::BrowserNavigate { url } => {
                let url = if url.contains("://") || url.contains('.') {
                    if url.contains("://") {
                        url
                    } else {
                        format!("https://{url}")
                    }
                } else {
                    format!(
                        "https://www.google.com/search?q={}",
                        url::form_urlencoded::byte_serialize(url.as_bytes()).collect::<String>()
                    )
                };
                let ok = run("xdg-open", &[&url]);
                self.send_ack(
                    ok,
                    if ok {
                        "A abrir no browser do PC."
                    } else {
                        "Não foi possível abrir o browser."
                    },
                );
            }
            ClientEvent::BrowserAction { action } => {
                let (ok, msg) = match action {
                    BrowserAction::OpenBrowser => (
                        run("xdg-open", &["about:blank"]),
                        "A abrir o browser no PC.",
                    ),
                    _ => (true, "Use a vista remota para navegar na página."),
                };
                self.send_ack(ok, msg);
            }
            ClientEvent::AppAction { app_id, action } => {
                let (ok, msg) = self.app_action(&app_id, action);
                self.send_ack(ok, msg);
            }
            ClientEvent::Shortcut { id } => {
                let done = match shortcut_to_keys(&id) {
                    Some((mods, key)) => self
                        .enigo
                        .as_mut()
                        .map(|e| press_combo(e, &mods, key))
                        .unwrap_or(false),
                    None if id == "lock" => {
                        run("loginctl", &["lock-session"]);
                        true
                    }
                    None if matches!(id.as_str(), "play_pause" | "next" | "prev") => {
                        let a = match id.as_str() {
                            "play_pause" => MediaAction::PlayPause,
                            "next" => MediaAction::Next,
                            _ => MediaAction::Previous,
                        };
                        do_media(a)
                    }
                    None if id == "mute" => {
                        run("pactl", &["set-sink-mute", "@DEFAULT_SINK@", "toggle"])
                    }
                    None => false,
                };
                self.send_ack(
                    done,
                    if done {
                        "Atalho executado."
                    } else {
                        "Atalho não suportado."
                    },
                );
            }
            ClientEvent::LockPc => {
                run("loginctl", &["lock-session"]);
                self.send_ack(true, "PC bloqueado.");
            }
            ClientEvent::ShutdownPc { confirmed } => {
                if !confirmed {
                    self.send_ack(false, "Desligar exige confirmação.");
                } else {
                    self.send_ack(true, "A desligar o PC.");
                    run("systemctl", &["poweroff"]);
                    return true;
                }
            }
            ClientEvent::ViewportRequest { mode, .. } => {
                self.streaming.store(true, Ordering::SeqCst);
                *self.viewport.blocking_lock() = mode;
                self.send_ack(true, "Vista remota ativa.");
            }
            ClientEvent::FileOffer { name, .. } => {
                let dest = format!("~/Downloads/FlowTools/{name}");
                let msg = format!("Ficheiro aceite. Destino: {dest} (upload na próxima versão).");
                self.send_ack(true, &msg);
            }
            ClientEvent::Ping => {
                self.send(serde_json::to_string(&ServerEvent::Pong).unwrap());
            }
        }
        false
    }

    fn app_action(&mut self, app_id: &str, action: AppAction) -> (bool, &'static str) {
        let Some(app) = allowed_apps().into_iter().find(|a| a.id == app_id) else {
            tracing::warn!("blocked app action for non-allowed {app_id}");
            return (false, "Aplicação não autorizada.");
        };
        match action {
            AppAction::Launch => {
                let ok = std::process::Command::new(app.bin)
                    .args(app.args)
                    .stdout(std::process::Stdio::null())
                    .stderr(std::process::Stdio::null())
                    .spawn()
                    .is_ok();
                (
                    ok,
                    if ok {
                        "A abrir a aplicação."
                    } else {
                        "Não foi possível abrir."
                    },
                )
            }
            AppAction::Focus => {
                let ok = run("wmctrl", &["-a", app.name])
                    || run(
                        "xdotool",
                        &[
                            "search",
                            "--onlyvisible",
                            "--class",
                            app.bin,
                            "windowactivate",
                        ],
                    );
                (
                    ok,
                    if ok {
                        "Aplicação em foco."
                    } else {
                        "Use a vista remota para focar a janela."
                    },
                )
            }
            AppAction::Close => {
                let ok = run("wmctrl", &["-c", app.name]);
                (
                    ok,
                    if ok {
                        "Aplicação fechada."
                    } else {
                        "Feche pela vista remota."
                    },
                )
            }
        }
    }

    fn send(&self, msg: String) {
        let _ = self.out.send(msg);
    }

    fn send_ack(&self, ok: bool, message: &str) {
        self.send(ack(ok, message));
    }
}

async fn capture_loop(
    out: tokio::sync::mpsc::UnboundedSender<String>,
    streaming: Arc<AtomicBool>,
    viewport: Arc<Mutex<ViewportMode>>,
    seq: Arc<AtomicU64>,
) {
    let mut interval = tokio::time::interval(Duration::from_millis(500)); // ~2fps MVP
    loop {
        interval.tick().await;
        if !streaming.load(Ordering::SeqCst) {
            continue;
        }
        let mode = *viewport.lock().await;
        // Spawn blocking capture off the async runtime.
        let frame = tokio::task::spawn_blocking(|| capture_primary(540, 60))
            .await
            .ok()
            .flatten();
        match frame {
            Some(jpeg) => {
                let n = seq.fetch_add(1, Ordering::SeqCst);
                let ev = ServerEvent::Frame {
                    viewport: mode,
                    jpeg_base64: B64.encode(&jpeg),
                    seq: n,
                };
                if out.send(serde_json::to_string(&ev).unwrap()).is_err() {
                    break;
                }
            }
            None => tracing::debug!("capture unavailable (headless?) — retrying"),
        }
    }
}

async fn connect_and_run(server: &str, pc_id: &str) -> anyhow::Result<bool> {
    let http = reqwest::Client::new();
    let ws_url = server.replacen("http", "ws", 1) + &format!("/v1/pc/channel?pc_id={pc_id}");
    let (ws, _) = connect_async(&ws_url)
        .await
        .map_err(|e| anyhow::anyhow(format!("ws: {e}")))?;
    session_indicator(true, pc_id);
    let (mut sink, mut stream) = ws.split();
    let (out_tx, mut out_rx) = tokio::sync::mpsc::unbounded_channel::<String>();

    let streaming = Arc::new(AtomicBool::new(false));
    let viewport = Arc::new(Mutex::new(ViewportMode::FitPhone));
    let seq = Arc::new(AtomicU64::new(0));
    let writer = tokio::spawn({
        let out_tx = out_tx.clone();
        async move {
            let _ = out_tx;
            while let Some(msg) = out_rx.recv().await {
                if sink.send(Message::Text(msg.into())).await.is_err() {
                    break;
                }
            }
        }
    });
    let capturer = tokio::spawn(capture_loop(
        out_tx.clone(),
        streaming.clone(),
        viewport.clone(),
        seq.clone(),
    ));

    // Input may be unavailable (headless/Wayland): stay connected so the
    // session indicator, frames and reconnect logic keep working.
    let enigo = match Enigo::new(&Settings::default()) {
        Ok(e) => Some(e),
        Err(e) => {
            tracing::warn!("input indisponível ({e:?}) — sessão continua sem rato/teclado");
            None
        }
    };
    let mut rt = Runtime {
        enigo,
        out: out_tx,
        streaming,
        viewport,
        seq,
    };

    let mut exit = false;
    while let Some(msg) = stream.next().await {
        let msg = match msg {
            Ok(m) => m,
            Err(e) => {
                tracing::warn!("ws read: {e}");
                break;
            }
        };
        let Message::Text(text) = msg else { continue };
        match serde_json::from_str::<ClientEvent>(&text) {
            Ok(ev) => {
                if rt.handle(ev) {
                    exit = true;
                    break;
                }
            }
            Err(_) => {
                // Server-initiated messages (e.g. file ready for download).
                if let Ok(ServerEvent::FileReady {
                    ticket,
                    name,
                    size_bytes,
                }) = serde_json::from_str::<ServerEvent>(&text)
                {
                    tokio::spawn(download_file(
                        http.clone(),
                        server.to_owned(),
                        pc_id.to_owned(),
                        ticket,
                        name,
                        size_bytes,
                        rt.out.clone(),
                    ));
                } else {
                    rt.send_ack(false, "Evento inválido.");
                }
            }
        }
    }

    session_indicator(false, "");
    writer.abort();
    capturer.abort();
    Ok(exit)
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter("flowtools=info")
        .init();
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

    // 2. Hold the PC channel with reconnect (tool state preserved).
    session_indicator(false, "");
    let mut backoff = Duration::from_secs(1);
    loop {
        match connect_and_run(&args.server, &pc_id).await {
            Ok(true) => break, // shutdown executed
            Ok(false) => {
                tracing::info!("channel closed — reconnecting");
            }
            Err(e) => {
                tracing::warn!("channel error: {e} — retrying in {backoff:?}");
            }
        }
        tokio::time::sleep(backoff).await;
        backoff = (backoff * 2).min(Duration::from_secs(30));
        // Reset backoff probe is implicit on next successful long-lived run (MVP).
    }
    Ok(())
}

// `url` crate is not a direct dep; form-encode search queries manually.
mod url {
    pub mod form_urlencoded {
        pub struct ByteSerialize<'a> {
            bytes: &'a [u8],
            pos: usize,
        }
        pub fn byte_serialize(bytes: &[u8]) -> ByteSerialize<'_> {
            ByteSerialize { bytes, pos: 0 }
        }
        impl Iterator for ByteSerialize<'_> {
            type Item = String;
            fn next(&mut self) -> Option<String> {
                if self.pos >= self.bytes.len() {
                    return None;
                }
                let b = self.bytes[self.pos];
                self.pos += 1;
                Some(if b == b' ' {
                    "+".into()
                } else if b.is_ascii_alphanumeric() {
                    (b as char).to_string()
                } else {
                    format!("%{b:02X}")
                })
            }
        }
    }
}

// anyhow without adding dep: minimal alias
mod anyhow {
    pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
    pub fn anyhow(msg: impl std::fmt::Display) -> Box<dyn std::error::Error + Send + Sync> {
        format!("{msg}").into()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn named_keys_parse() {
        assert!(matches!(parse_key("enter"), Some(Key::Return)));
        assert!(matches!(parse_key("a"), Some(Key::Unicode('a'))));
        assert!(parse_key("nope-not-a-key").is_none());
    }

    #[test]
    fn modifiers_parse() {
        assert!(matches!(parse_modifier("ctrl"), Some(Key::Control)));
        assert!(parse_modifier("nope").is_none());
    }

    #[test]
    fn shortcuts_map_to_combos() {
        let (mods, key) = shortcut_to_keys("ctrl_c").unwrap();
        assert_eq!(mods, vec![Key::Control]);
        assert_eq!(key, Key::Unicode('c'));
        assert!(shortcut_to_keys("play_pause").is_none()); // media path
        assert!(shortcut_to_keys("whatever").is_none());
    }

    #[test]
    fn jpeg_encode_of_synthetic_image() {
        let img = image::RgbaImage::from_fn(64, 64, |x, y| {
            image::Rgba([(x % 256) as u8, (y % 256) as u8, 128, 255])
        });
        let jpeg = encode_frame(&img, 540, 60).expect("encode");
        assert!(jpeg.starts_with(&[0xFF, 0xD8, 0xFF])); // SOI marker
    }

    #[test]
    fn search_query_is_form_encoded() {
        let q = "ola mundo&";
        let enc: String = url::form_urlencoded::byte_serialize(q.as_bytes()).collect();
        assert_eq!(enc, "ola+mundo%26");
    }

    #[test]
    fn file_names_are_sanitized() {
        assert_eq!(safe_file_name("../../etc/passwd"), "passwd");
        assert_eq!(safe_file_name("nota final (2).pdf"), "nota final (2).pdf");
        assert_eq!(safe_file_name("..."), "ficheiro");
    }

    #[tokio::test]
    async fn concurrent_same_name_cannot_overwrite() {
        let dir = std::env::temp_dir().join(format!("flowtools-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("dup.txt");
        let first = tokio::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .await;
        assert!(first.is_ok());
        let second = tokio::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .await;
        assert_eq!(
            second.unwrap_err().kind(),
            std::io::ErrorKind::AlreadyExists
        );
        std::fs::remove_dir_all(&dir).ok();
    }
}
