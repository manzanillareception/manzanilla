package com.agentdeck.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceptionAssetsTest {
    @Test fun everyResourceRequiredByTheShippedPageLoadsInTheNativeWebView() {
        val assets = File("src/main/assets/reception")
        val html = File(assets, "index.html").readText()
        val resources = Regex("(?:src|href)=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue("The actual page has script and stylesheet dependencies", resources.size >= 4)
        for (resource in listOf("index.html") + resources) {
            assertTrue("Bundled resource exists: $resource", File(assets, resource).isFile)
            assertTrue("Native WebView can load required resource: $resource",
                ReceptionAssets.allows("https", ReceptionAssets.HOST, "/$resource", null, "GET"))
        }
    }

    @Test fun unrelatedAssetsAndNetworkRequestsRemainBlocked() {
        fun allows(path: String, host: String = ReceptionAssets.HOST, scheme: String = "https",
                   query: String? = null, method: String = "GET") =
            ReceptionAssets.allows(scheme, host, path, query, method)
        assertFalse(allows("/private-provider-config.json"))
        assertFalse(allows("/../private-provider-config.json"))
        assertFalse(allows("/language-turns.js", host = "example.com"))
        assertFalse(allows("/language-turns.js", scheme = "http"))
        assertFalse(allows("/language-turns.js", query = "v=1"))
        assertFalse(allows("/language-turns.js", method = "POST"))
    }
}
