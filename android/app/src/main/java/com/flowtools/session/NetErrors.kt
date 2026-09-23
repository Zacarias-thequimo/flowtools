package com.flowtools.session

import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * Traduz falhas de rede em mensagens PT acionáveis, em vez de um único
 * "PC não disponível" para tudo. Isto distingue: IP errado, firewall/PC
 * desligado, bloqueio de cleartext e erros HTTP do servidor.
 */
fun userMessageFor(t: Throwable): String {
    if (t is CancellationException) throw t
    return when (t) {
        is UnknownHostException ->
            "Endereço não encontrado. Confirme o IP do PC no QR ou código."
        is ConnectException ->
            "O PC recusou a ligação. Verifique se o servidor corre e se a firewall deixa passar a porta."
        is HttpRequestTimeoutException ->
            "O PC demorou a responder. Verifique a rede (p. ex. hotspot com sinal fraco)."
        is ClientRequestException -> when (t.response.status.value) {
            401 -> "Código inválido. Tente novamente."
            403 -> "O PC recusou estas permissões."
            404 -> "Emparelhamento não encontrado. Gere um novo código no PC."
            410 -> "O código expirou. Gere um novo no PC."
            else -> "O servidor respondeu com erro (${t.response.status.value})."
        }
        is ServerResponseException ->
            "O servidor falhou (${t.response.status.value}). Tente novamente."
        is ResponseException ->
            "O servidor respondeu com erro (${t.response.status.value})."
        else -> {
            val msg = t.message.orEmpty()
            if ("CLEARTEXT" in msg.uppercase()) {
                "O Android bloqueou a ligação HTTP. Atualize a app: este erro já não devia acontecer."
            } else {
                "O PC não está disponível. Verifique a ligação."
            }
        }
    }
}

/** GET {host}/v1/health → "ok (versão X)" ou exceção para [userMessageFor]. */
suspend fun healthCheck(host: String): String {
    val body: String = Api.http.get("${host.trimEnd('/')}/v1/health").body()
    val version = runCatching {
        Api.json.parseToJsonElement(body)
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("version")?.toString()?.trim('"')
    }.getOrNull()
    return if (version != null) "PC alcançável (servidor v$version)." else "PC alcançável."
}
