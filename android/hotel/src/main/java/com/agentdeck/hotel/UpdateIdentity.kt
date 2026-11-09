package com.agentdeck.hotel

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Metadata is snapshotted from one release, never reconstructed from a later latest-feed response. */
data class UpdateRequest(
    val tag: String,
    val url: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sha256: String?,
    val publishedAt: String? = null
) {
    init {
        require(ReleaseVersion.valid(tag) && tag == "v$versionName")
        require(packageName == "dev.orange.hotel" && versionCode > 0)
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        require(sha256 == null || Regex("[0-9a-f]{64}").matches(sha256))
    }

    fun json(): JSONObject = JSONObject().put("schema", 1).put("tag", tag).put("url", url)
        .put("package_name", packageName).put("version_name", versionName).put("version_code", versionCode)
        .apply { sha256?.let { put("sha256", it) } }
        .apply { publishedAt?.let { put("published_at", it) } }

    // Includes all expectations, not merely the version label, to prevent cache collisions.
    val key: String = hash(listOf(tag, url, packageName, versionName, versionCode.toString(), sha256.orEmpty())
        .joinToString("\n").toByteArray(Charsets.UTF_8))

    fun rejects(apk: ApkIdentity, installed: ApkIdentity, digest: String): String? = when {
        apk.packageName != packageName || installed.packageName != packageName -> "The APK belongs to another application."
        apk.versionName != versionName || apk.versionCode != versionCode -> "The APK version does not match its release."
        !ReleaseVersion.offer(tag, installed.versionName, installed.versionCode, apk.versionCode) -> "The APK is not newer than the installed application."
        apk.signers.isEmpty() || apk.signers != installed.signers -> "The APK signing identity does not match this installation."
        sha256 != null && sha256 != digest -> "The APK digest does not match its release."
        else -> null
    }

    companion object {
        fun fromJson(json: JSONObject): UpdateRequest {
            require(json.getInt("schema") == 1)
            return UpdateRequest(json.getString("tag"), json.getString("url"), json.getString("package_name"),
                json.getString("version_name"), json.getLong("version_code"),
                json.optString("sha256").takeIf { it.isNotEmpty() }?.lowercase(),
                json.optString("published_at").ifBlank { null })
        }
        fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

data class ApkIdentity(val packageName: String, val versionName: String, val versionCode: Long, val signers: Set<String>)
data class UpdateAttempt(val request: UpdateRequest, val revision: Long)

/** UI-thread-owned lease: cancellation/retry invalidates every in-flight callback from the old attempt. */
class UpdateOwnership {
    private var revision = 0L
    val generation: Long get() = revision
    var active: UpdateAttempt? = null
        private set
    var downloadId: Long = -1L
        private set

    fun begin(request: UpdateRequest): UpdateAttempt? {
        active?.let { return if (it.request == request) it else null }
        return UpdateAttempt(request, ++revision).also { active = it }
    }

    fun owns(attempt: UpdateAttempt): Boolean = active == attempt
    fun attach(attempt: UpdateAttempt, id: Long): Boolean {
        if (!owns(attempt) || id < 0) return false
        downloadId = id
        return true
    }
    fun ownsDownload(attempt: UpdateAttempt, id: Long, url: String): Boolean =
        owns(attempt) && downloadId == id && attempt.request.url == url

    fun detach(attempt: UpdateAttempt) { if (owns(attempt)) downloadId = -1L }
    fun invalidate(attempt: UpdateAttempt): Boolean {
        if (!owns(attempt)) return false
        cancel()
        return true
    }
    fun cancel(): UpdateAttempt? = active.also { active = null; downloadId = -1L; revision++ }
}
