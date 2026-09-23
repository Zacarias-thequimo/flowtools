package com.flowtools.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flowtools.session.Api
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okio.*
import java.io.IOException

@Serializable
private data class UploadResp(
    val ticket: String,
    val name: String,
    val size_bytes: Long,
    val destination: String,
)

data class UploadUi(
    val uploading: Boolean = false,
    val progress: Float? = null,
    val destination: String? = null,
    val fileName: String? = null,
    val error: String? = null,
)

/** Counts uploaded bytes for progress (Ktor 2.x has no onUpload hook). */
private class CountingBody(
    private val delegate: RequestBody,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun writeTo(sink: BufferedSink) {
        var sent = 0L
        val total = contentLength()
        val counting = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                sent += byteCount
                onProgress(sent, total)
            }
        }
        delegate.writeTo(counting.buffer())
        counting.flush()
    }
}

class FilesViewModel : ViewModel() {
    private val _ui = MutableStateFlow(UploadUi())
    val ui: StateFlow<UploadUi> = _ui.asStateFlow()

    private var call: Call? = null
    private val client = OkHttpClient()

    fun upload(server: String, token: String, name: String, bytes: ByteArray) {
        call?.cancel()
        _ui.value = UploadUi(uploading = true, progress = 0f, fileName = name)
        val rawBody = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) {
                sink.write(bytes)
            }
        }
        val fileBody = CountingBody(rawBody) { sent, total ->
            if (total > 0) _ui.value = _ui.value.copy(progress = sent.toFloat() / total)
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("token", token)
            .addFormDataPart("file", name, fileBody)
            .build()
        val req = Request.Builder()
            .url("${server.trimEnd('/')}/v1/files/inbox")
            .post(body)
            .build()
        call = client.newCall(req).also { c ->
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    _ui.value = if (call.isCanceled()) UploadUi(error = "Transferência cancelada.")
                    else UploadUi(error = "O PC não está disponível. Verifique a ligação.")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { r ->
                        if (!r.isSuccessful) {
                            _ui.value = UploadUi(error = when (r.code) {
                                403 -> "Esta ferramenta precisa de autorização."
                                413 -> "Ficheiro demasiado grande (máx. 50 MB)."
                                401 -> "A sessão expirou. Volte a ligar."
                                else -> "Algo correu mal. Tente novamente."
                            })
                            return
                        }
                        val text = r.body?.string().orEmpty()
                        val done = runCatching {
                            Api.json.decodeFromString<UploadResp>(text)
                        }.getOrNull()
                        _ui.value = if (done != null) {
                            UploadUi(
                                destination = "Destino: ${done.destination}/${done.name}",
                                fileName = done.name,
                            )
                        } else {
                            UploadUi(error = "Algo correu mal. Tente novamente.")
                        }
                    }
                }
            })
        }
    }

    fun cancel() {
        call?.cancel()
        if (_ui.value.uploading) _ui.value = UploadUi(error = "Transferência cancelada.")
    }

    override fun onCleared() {
        call?.cancel()
    }
}
