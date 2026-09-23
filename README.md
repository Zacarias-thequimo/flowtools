# FlowTools — O teu PC, mais perto.

Suíte de ferramentas para Android que permite controlar o PC, o browser e
outras aplicações do computador a partir do telemóvel, sem pausar o vídeo
que estás a ver nem sair da aplicação atual.

Três componentes:

| Componente | Pasta | Tecnologia |
|---|---|---|
| Servidor de sessão (pairing, auth, relay, inbox de ficheiros) | `crates/server` | Rust + Axum + Tokio |
| Client do PC (input, captura, apps, browser, ficheiros) | `crates/pc-client` | Rust (enigo, xcap) |
| App Android (UI, gestos, pairing, socket) | `android/` | Kotlin + Jetpack Compose + Material 3 |
| Contrato partilhado (estados, eventos WS, erros) | `crates/protocol` | Rust (espelhado em Kotlin) |

Especificação visual e funcional: `flowtools-ui-ux.md` e
`flowtools-ui-spec.json`. Conceito visual: `flowtools-ui-concept.png`.

---

## 1. Requisitos

- **Servidor e PC:** Linux (testado em Ubuntu 26.04), Rust estável
  (`rustup`), `pkg-config` + libs: `libssl-dev libwayland-dev
  libxkbcommon-dev libx11-dev libxtst-dev libpipewire-0.3-dev clang
  libegl-dev libgl-dev` (para `enigo`/`xcap`), `playerctl` e `pactl`
  (opcionais, para multimédia/volume), `wmctrl`/`xdotool` (opcionais,
  para foco de janelas).
- **Android:** JDK 17, Android SDK (platform 34, build-tools 34),
  Gradle 8.10+ (ver `android/local.properties` para o caminho do SDK).
- Telemóvel e PC na **mesma rede local** (a v1 privilegia ligação
  direta; relay remoto é opt-in via `FLOWTOOLS_RELAY=1`).

## 2. Arranque rápido (rede local)

```bash
# 1. Servidor de sessão (porta 8787 por defeito; usa PORT para mudar)
./target/debug/flowtools-server
# ou release:
cargo run --release -p flowtools-server

# 2. Client do PC (mostra código temporário + QR + permissões)
./target/debug/flowtools-pc --server http://127.0.0.1:8787 --pc-name "PC de trabalho"

# 3. App Android: instalar o APK de debug (ver secção 4) e emparelhar.
```

## 3. Emparelhar (QR ou código)

1. Corre o client do PC. Ele regista o PC e imprime:
   - nome do PC, **código temporário de 6 dígitos** (válido 5 min, uso único),
   - payload QR `flowtools://pair?id=…&code=…&host=…`,
   - lista de permissões pedidas.
2. Na app: **Início → Emparelhar PC** (ou banner "Não emparelhado").
3. Aponta a câmara ao QR **ou** insere ID + código + endereço à mão.
4. Desmarca as capacidades que **não** quiseres autorizar
   (cursor, teclado, captura, browser, apps, ficheiros, notificações,
   multimédia, sistema). Nada é obrigatório.
5. **Autorizar selecionadas.** O token de sessão fica guardado no
   Keystore (Android) e roda a cada ligação.

Revogar: **Definições → Revogar dispositivo** (derruba sockets vivos e
apaga o token guardado). No servidor também existe
`POST /v1/devices/:id/revoke`.

## 4. App Android — compilar e instalar

```bash
export ANDROID_SDK_ROOT=/opt/android-sdk ANDROID_HOME=/opt/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
gradle :app:assembleDebug          # em android/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Testes unitários: `gradle :app:testDebugUnitTest` (estados, permissões,
paridade JSON com o protocolo Rust, parser do QR).
Teste de UI (navegação): `gradle :app:connectedAndroidTest` com
emulador/dispositivo ligado.

> No emulador, o PC é alcançado em `http://10.0.2.2:8787`.
> Num telemóvel real, usa o IP LAN do PC (ex. `http://192.168.1.5:8787`).

### 4.1. Gerar APKs assinados (release)

1. Gerar a chave (uma vez):
   ```bash
   keytool -genkeypair -v -keystore flowtools-release.jks -alias flowtools \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
   Guarda o `.jks` e as passwords **fora** do repo (nunca commitar).
2. Criar `android/key.properties` (ignorado pelo git):
   ```properties
   storeFile=/caminho/para/flowtools-release.jks
   storePassword=…
   keyAlias=flowtools
   keyPassword=…
   ```
3. Ativar a assinatura em `android/app/build.gradle.kts`
   (bloco `signingConfigs` a ler `key.properties` + `buildTypes.release`
   com `signingConfig = signingConfigs.getByName("release")`).
4. Compilar e obter os artefactos:
   ```bash
   gradle :app:assembleRelease
   gradle :app:bundleRelease        # AAB para a Play Store
   ```
   Saídas: `app/build/outputs/apk/release/app-release.apk` e
   `app/build/outputs/bundle/release/app-release.aab`.
5. Verificar a assinatura:
   ```bash
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```
6. Instalar: `adb install app-release.apk`, ou distribuir o `.aab`
   via Play Console (assinatura da Play opcional).

## 5. O que a v1 faz

- **Início:** cartão do PC (nome + estado por texto e ícone), 4 ações
  rápidas (controlo remoto a **1 toque**), recentes.
- **Controlo remoto:** touchpad grande (1 dedo move, toque clica,
  2 dedos = botão direito/scroll), botões alternativos acessíveis,
  anterior/play·pause/seguinte, volume, **Bloquear** imediato e
  **Desligar PC com confirmação**.
- **Browser remoto:** barra de endereço (voltar/avançar/recarregar),
  viewport vertical **9:16** em "Ajustar ao telemóvel" por defeito,
  pinça para zoom + botões +/−, "Abrir browser no PC".
- **Aplicações do PC:** só apps **autorizadas**, abrir/focar/fechar,
  viewport 9:16, barra flutuante recolhível.
- **Texto rápido:** janela ativa / browser / clipboard, histórico
  opt-in (nada sensível guardado por defeito).
- **Atalhos** por categoria (multimédia, janelas, browser, edição, sistema).
- **Ficheiros:** envio telemóvel→PC com progresso e cancelamento,
  destino `~/Downloads/FlowTools`, **nunca substitui sem confirmação**
  (devolve `file_exists`), máx. 50 MB.
- **Estados de ligação** sempre visíveis com texto + ícone:
  Não emparelhado · A ligar · Ligado · A reconectar · Offline ·
  Permissão necessária. Reconnect com backoff preserva a ferramenta.

## 6. Segurança (baseline v1)

Pairing com código/QR de uso único · tokens rotativos · token Android no
Keystore · permissões granulares por capacidade · revogação com efeito
imediato · timeout de sessão 8h · indicador visível no PC
(`notify-send` + consola) enquanto a sessão está ativa · confirmação
para desligar e para substituir ficheiros · allow-list de apps ·
mensagens de erro PT sem detalhes técnicos (sem sockets/exceções na UI).

## 7. Erros que a app mostra (e o que fazer)

| Mensagem | Causa provável | Ação |
|---|---|---|
| O PC não está disponível. | Servidor/PC offline | Verificar rede e que ambos correm |
| A sessão expirou. | Token rodado após reconnect | Voltar a ligar / re-emparelhar |
| Esta ferramenta precisa de autorização. | Capacidade não aprovada | Rever permissões no pairing |
| Código inválido. | Erro de digitação | Tentar de novo |
| O código expirou. | Passaram 5 min | Gerar novo (reiniciar PC client) |
| Já existe um ficheiro com esse nome. | Duplicado no destino | Renomear antes de enviar |
| Ficheiro demasiado grande (máx. 50 MB). | Limite da inbox | Dividir/comprimir |
| O browser não está aberto no PC. | — | "Abrir browser no PC" |

Bateria de erros executável contra um servidor local:
`python3 /tmp/force_errors.py http://127.0.0.1:PORT`
(24 casos: pairing, upload, tickets one-shot, WS, rotação, revogação).
Cobriu e corrigiu: limite 2 MB do axum (agora 413 JSON limpo) e
condição de corrida em downloads concorrentes com o mesmo nome
(criação atómica — o primeiro ganha, o segundo recebe `file_exists`).

## 8. Testes (como correr)

```bash
cargo test --workspace        # protocolo (6), servidor (4, incl. E2E WS), PC (7)
gradle :app:testDebugUnitTest # Android (13): estados, permissões, JSON, QR
```

## 9. Limites conhecidos da v1

- Captura e input reais exigem sessão gráfica no PC (X11/Wayland);
  em headless a sessão continua ligada mas input/frames avisam com erro
  compreensível em vez de cair.
- Relay remoto é só flag (`FLOWTOOLS_RELAY=1`); sem TURN/STUN próprio.
- Um PC de cada vez; um monitor (primário); sem atalhos personalizados.
- Ficheiros só no sentido telemóvel→PC; texto do PC→telemóvel não.

## 10. Roadmap (v2)

Relay remoto com consentimento explícito · múltiplos PCs e monitores ·
macros/atalhos personalizados · notificações do PC · tray Tauri no
client · histórico sincronizado · download PC→telemóvel.
