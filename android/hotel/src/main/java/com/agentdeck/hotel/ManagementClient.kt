package com.agentdeck.hotel

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Synchronous transport seam: the app uses OkHttp on a worker executor; tests use fakes. */
fun interface ManagementTransport {
    fun execute(method: String, url: String, bearer: String?, apiKey: String?, body: JSONObject?): ManagementHttpResponse
}

data class ManagementHttpResponse(val status: Int, val body: String?)

class OkHttpManagementTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
) : ManagementTransport {
    override fun execute(method: String, url: String, bearer: String?, apiKey: String?, body: JSONObject?): ManagementHttpResponse {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        if (apiKey != null) builder.header("apikey", apiKey)
        val payload = body?.toString()?.toRequestBody("application/json".toMediaType())
        builder.method(method, if (method == "GET") null else payload ?: "{}".toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use { response ->
            return ManagementHttpResponse(response.code, response.body?.string())
        }
    }
}

/** Contract routes for the V2 fleet-management backend plus Supabase hotel password sign-in. */
class ManagementClient(private val transport: ManagementTransport) {

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        /** Server answered: error envelope code, retryable flag and optional retryAfterMs. */
        data class Rejected(val status: Int, val code: String, val retryable: Boolean, val retryAfterMs: Long?) : Result<Nothing>()
        /** No usable answer at all: DNS, TLS, timeout. Always retryable. */
        data class Unreachable(val detail: String) : Result<Nothing>()
    }

    data class EnrollResult(val deviceId: String, val deviceCredential: String, val desiredAccess: String, val policyRevision: Int)
    data class PolicySnapshot(val desiredAccess: String, val effectiveAccess: String, val policyRevision: Int, val serverTime: String)
    data class BatchResult(val accepted: Int, val duplicates: Int)
    data class AckResult(val acknowledged: Boolean, val revision: Int)
    data class Admission(val admit: Boolean, val reason: String?, val policyRevision: Int)

    private fun <T> call(parse: (JSONObject) -> T, request: () -> ManagementHttpResponse): Result<T> {
        val response = try { request() } catch (e: IOException) {
            return Result.Unreachable(e.javaClass.simpleName)
        } catch (_: Exception) {
            return Result.Unreachable("transport failure")
        }
        val body = response.body
        if (response.status in 200..299) {
            return try { Result.Ok(parse(JSONObject(body ?: "{}"))) }
            catch (_: Exception) { Result.Rejected(response.status, "BAD_RESPONSE", true, null) }
        }
        val parsed = runCatching { JSONObject(body ?: "").getJSONObject("error") }.getOrNull()
        val code = parsed?.optString("code")?.takeIf { it.isNotBlank() } ?: "HTTP_${response.status}"
        val retryable = parsed?.optBoolean("retryable") ?: (response.status == 429 || response.status >= 500)
        val retryAfterMs = parsed?.optLong("retryAfterMs", -1)?.takeIf { it >= 0 }
        return Result.Rejected(response.status, code, retryable, retryAfterMs)
    }

    /** Hotel email/password sign-in against Supabase Auth; returns the user JWT. Never persisted. */
    fun hotelSignIn(supabaseUrl: String, anonKey: String, email: String, password: String): Result<String> =
        call({ it.getString("access_token") }) {
            transport.execute("POST",
                "${supabaseUrl.trimEnd('/')}/auth/v1/token?grant_type=password",
                bearer = null, apiKey = anonKey,
                body = JSONObject().put("email", email).put("password", password))
        }

    fun enroll(base: String, hotelJwt: String?, setupToken: String?, label: String, build: String, bootId: String): Result<EnrollResult> =
        call({ json ->
            EnrollResult(json.getString("deviceId"), json.getString("deviceCredential"),
                json.getString("desiredAccess"), json.getInt("policyRevision"))
        }) {
            val body = JSONObject().put("label", label).put("build", build).put("bootId", bootId)
            if (setupToken != null) body.put("setupToken", setupToken)
            transport.execute("POST", "$base/api/v1/devices/enroll", bearer = hotelJwt, apiKey = null, body = body)
        }

    fun heartbeat(base: String, credential: String, bootId: String, sequence: Int, build: String,
                  mode: String, microphone: String, occurredAtMs: Long): Result<PolicySnapshot> =
        call({ json ->
            PolicySnapshot(json.getString("desiredAccess"), json.getString("effectiveAccess"),
                json.getInt("policyRevision"), json.optString("serverTime"))
        }) {
            transport.execute("POST", "$base/api/v1/devices/heartbeat", bearer = credential, apiKey = null,
                body = JSONObject().put("bootId", bootId).put("sequence", sequence).put("build", build)
                    .put("mode", mode).put("microphone", microphone)
                    .put("occurredAt", ManagementTelemetry.timestamp(occurredAtMs)))
        }

    fun sendBatch(base: String, credential: String, events: List<JSONObject>): Result<BatchResult> =
        call({ json -> BatchResult(json.getInt("accepted"), json.getInt("duplicates")) }) {
            transport.execute("POST", "$base/api/v1/events/batch", bearer = credential, apiKey = null,
                body = ManagementTelemetry.envelope(events))
        }

    fun policyAck(base: String, credential: String, revision: Int, bootId: String, sequence: Int): Result<AckResult> =
        call({ json -> AckResult(json.getBoolean("acknowledged"), json.getInt("revision")) }) {
            transport.execute("POST", "$base/api/v1/devices/policy-ack", bearer = credential, apiKey = null,
                body = JSONObject().put("revision", revision).put("bootId", bootId).put("sequence", sequence))
        }

    fun admitSession(base: String, credential: String, sessionId: String, attemptId: String, feature: String): Result<Admission> =
        call({ json ->
            if (json.getBoolean("admit")) Admission(true, null, json.getInt("policyRevision"))
            else Admission(false, json.optString("reason").ifBlank { "blocked" }, json.getInt("policyRevision"))
        }) {
            transport.execute("POST", "$base/api/v1/devices/sessions", bearer = credential, apiKey = null,
                body = JSONObject().put("sessionId", sessionId).put("attemptId", attemptId).put("feature", feature))
        }
}
