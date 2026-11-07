package com.agentdeck.hotel

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Bounded synchronous HTTP client; called only by the fleet worker, never audio/UI. */
internal class FleetClient(base: String, private val token: String? = null, client: OkHttpClient = sharedHttp) {
    private val origin = FleetProtocol.base(base)
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun get(path: String): JSONObject = request(path, null)
    fun post(path: String, body: JSONObject = JSONObject()): JSONObject = request(path, body)
    private fun request(path: String, body: JSONObject?): JSONObject {
        require(path.startsWith('/') && !path.contains('?') && !path.contains('#') && !path.contains(".."))
        val request = Request.Builder().url(origin + FleetProtocol.PREFIX + path).header("Accept", "application/json")
        if (token != null) request.header("Authorization", "Bearer " + FleetProtocol.secret(token))
        if (body == null) request.get() else {
            val text = body.toString()
            require(text.toByteArray(Charsets.UTF_8).size <= FleetProtocol.MAX_RESPONSE_BYTES)
            request.post(text.toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) throw FleetHttpException(response.code)
            val stream = response.body?.byteStream() ?: throw IllegalStateException("Empty fleet response")
            val bytes = stream.readBytesBounded(FleetProtocol.MAX_RESPONSE_BYTES)
            return JSONObject(bytes.toString(Charsets.UTF_8))
        }
    }
    private fun java.io.InputStream.readBytesBounded(maximum: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer)
            if (count < 0) return result.toByteArray()
            check(result.size() + count <= maximum) { "Fleet response too large" }
            result.write(buffer, 0, count)
        }
    }
    companion object {
        private val sharedHttp = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
}

internal class FleetHttpException(val status: Int) : Exception("Fleet request failed ($status)")
