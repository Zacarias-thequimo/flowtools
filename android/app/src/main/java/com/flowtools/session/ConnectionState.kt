package com.flowtools.session

/** Mirrors flowtools-protocol SessionState. Text + icon always, never color only. */
enum class ConnectionState(val label: String) {
    Unpaired("Ligue um PC para começar."),
    Pairing("A ligar ao PC…"),
    Connected("Ligado"),
    Reconnecting("A ligação foi interrompida."),
    Offline("O PC não está disponível."),
    PermissionRequired("Esta ferramenta precisa de autorização."),
}

enum class Capability(val label: String) {
    Cursor("Cursor"),
    Keyboard("Teclado"),
    ScreenCapture("Captura de ecrã"),
    Browser("Browser"),
    Applications("Aplicações"),
    Files("Ficheiros"),
    Notifications("Notificações"),
    Media("Multimédia"),
    System("Sistema (bloquear/desligar)"),
}

/** Tool -> required capabilities (mirrors protocol.required_for_tool). */
fun requiredForTool(tool: String): Set<Capability> = when (tool) {
    "remote_control" -> setOf(Capability.Cursor, Capability.Media)
    "browser" -> setOf(Capability.Browser, Capability.ScreenCapture)
    "app_control" -> setOf(Capability.Applications, Capability.ScreenCapture)
    "quick_text" -> setOf(Capability.Keyboard)
    "shortcuts" -> setOf(Capability.Keyboard)
    "screen_capture" -> setOf(Capability.ScreenCapture)
    "file_transfer" -> setOf(Capability.Files)
    "notifications" -> setOf(Capability.Notifications)
    "audio" -> setOf(Capability.Media)
    "lock", "shutdown" -> setOf(Capability.System)
    else -> emptySet()
}

fun missingCapabilities(granted: Set<Capability>, required: Set<Capability>): Set<Capability> =
    required - granted
