package com.agentdeck.hotel

import org.json.JSONObject
import java.net.URI

/** Reads either the CDN-served immutable manifest or the legacy GitHub release response.
 * APK signing/package/version verification still runs on the downloaded archive.
 */
object UpdateFeed {
    fun parse(document: JSONObject, fetch: (String) -> JSONObject): UpdateRequest? {
        if (document.optBoolean("draft") || document.optBoolean("prerelease")) return null
        if (document.has("schema")) return pinned(UpdateRequest.fromJson(document))

        val tag = document.getString("tag_name")
        // Historical CI tags are not installable versions.
        if (!tag.startsWith("v") || !ReleaseVersion.valid(tag)) return null
        val assets = document.getJSONArray("assets")
        val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
        val manifest = entries.singleOrNull { it.optString("name") == "release-metadata.json" }
            ?: error("Release metadata asset is missing or ambiguous")
        val manifestUrl = manifest.getString("browser_download_url")
        https(manifestUrl)
        val request = UpdateRequest.fromJson(fetch(manifestUrl)).let {
            it.copy(publishedAt = document.optString("published_at").ifBlank { null })
        }
        require(request.tag == tag) { "Release tag/metadata mismatch" }
        val apk = entries.singleOrNull {
            it.optString("browser_download_url") == request.url && it.optString("name").endsWith(".apk")
        } ?: error("Release APK/metadata mismatch")
        val rawDigest = apk.opt("digest")
        val digest = if (rawDigest == null || rawDigest == JSONObject.NULL || rawDigest == "") null else {
            require(rawDigest is String && Regex("sha256:[0-9a-fA-F]{64}").matches(rawDigest)) {
                "Invalid release APK digest"
            }
            rawDigest.removePrefix("sha256:").lowercase()
        }
        if (request.sha256 != null && digest != null) {
            require(request.sha256 == digest) { "Release digests disagree" }
        }
        return pinned(request.copy(sha256 = digest ?: request.sha256))
    }

    private fun pinned(request: UpdateRequest): UpdateRequest {
        require(request.sha256 != null) { "Release metadata must include an APK digest" }
        val uri = https(request.url)
        val path = uri.path.orEmpty()
        require(path == uri.normalize().path &&
            Regex(".*/releases/download/${Regex.escape(request.tag)}/[^/]+\\.apk").matches(path)) {
            "APK URL is not pinned to its release"
        }
        return request
    }

    private fun https(url: String): URI = URI(url).also {
        require(it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null) {
            "Update metadata requires HTTPS"
        }
    }
}
