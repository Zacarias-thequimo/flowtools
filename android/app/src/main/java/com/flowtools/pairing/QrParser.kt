package com.flowtools.pairing

/** Invite encoded in the PC's QR code: `flowtools://pair?id=..&code=..&host=..`. */
data class PairInvite(val pairingId: String, val code: String, val host: String)

private const val PREFIX = "flowtools://pair?"

fun parsePairInvite(text: String): PairInvite? {
    val raw = text.trim()
    if (!raw.startsWith(PREFIX)) return null
    val params = raw.removePrefix(PREFIX).split('&')
        .mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }
        .toMap()
    val id = params["id"]?.takeIf { it.isNotBlank() } ?: return null
    val code = params["code"]?.takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
    val host = params["host"]?.takeIf { it.isNotBlank() } ?: "http://127.0.0.1:8787"
    return PairInvite(id, code, host)
}

/** Capabilities the user approved, as wire (snake_case) strings. */
fun capabilityWireName(label: String): String = when (label) {
    "Cursor" -> "cursor"
    "Teclado" -> "keyboard"
    "Captura de ecrã" -> "screen_capture"
    "Browser" -> "browser"
    "Aplicações" -> "applications"
    "Ficheiros" -> "files"
    "Notificações" -> "notifications"
    "Multimédia" -> "media"
    "Sistema (bloquear/desligar)" -> "system"
    else -> label.lowercase()
}
