package com.flowtools.session

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/** Shared Ktor HTTP client (pairing claim, file upload). */
object Api {
    val json = Json { ignoreUnknownKeys = true }

    val http = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
    }
}
