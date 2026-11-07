package com.agentdeck.hotel

import android.app.Instrumentation

/** Debug fixtures must never obtain a bundled provider credential. */
internal object ProviderBuildFixture {
    fun verifyDebug(test: Instrumentation) {
        val config = test.targetContext.classLoader.loadClass("com.agentdeck.hotel.BuildConfig")
        check(config.getField("DEBUG").get(null) == true) { "Voice fixtures require the key-free debug target" }
        check(config.fields.none { it.name.contains("OPENAI", ignoreCase = true) || it.name.contains("FISH", ignoreCase = true) })
        check(config.fields.filter { it.name.endsWith("API_KEY") }.map { it.name } == listOf("CARTESIA_API_KEY"))
        check(config.getField("CARTESIA_API_KEY").get(null) == "") { "Debug target contains a provider key" }
    }
}
