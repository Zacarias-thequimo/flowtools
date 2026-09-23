package com.flowtools.session

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/** Shared Ktor HTTP client (pairing claim, health check). Timeouts curtos
 *  para a UI nunca pendurar: sem resposta em 8s é "PC indisponível". */
object Api {
    val json = Json { ignoreUnknownKeys = true }

    val http = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }
}
