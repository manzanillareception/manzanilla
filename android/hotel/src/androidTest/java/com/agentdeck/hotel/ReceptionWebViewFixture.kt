package com.agentdeck.hotel

import android.app.Instrumentation
import android.Manifest
import android.content.Intent
import android.webkit.WebView
import android.widget.FrameLayout
import com.agentdeck.ui.ReceptionView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Exercises the shipped assets and real addJavascriptInterface boundary, with no audio provider. */
internal object ReceptionWebViewFixture {
    /** A stale or forged bridge start cannot turn on capture before device BYOK is saved. */
    fun verifyTranslationMissingKey(instrumentation: Instrumentation) {
        val context = instrumentation.targetContext
        check(SecretStore(context, "openai").status() == SecretStore.Status.ABSENT) { "Missing-key fixture requires empty synthetic app data" }
        val permissionBefore = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        val activity = instrumentation.startActivitySync(Intent(context, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HotelActivity
        val ownerField = HotelActivity::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val audioField = HotelActivity::class.java.getDeclaredField("audioState").apply { isAccessible = true }
        val handle = HotelActivity::class.java.getDeclaredMethod("handle", JSONObject::class.java).apply { isAccessible = true }
        val page = HotelActivity::class.java.getDeclaredField("reception").apply { isAccessible = true }.get(activity) as ReceptionView
        lateinit var browser: WebView
        instrumentation.runOnMainSync { browser = page.getChildAt(0) as WebView }
        fun js(script: String) = evaluate(instrumentation, browser, script)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (js("typeof window.manzania === 'object'") != "true") {
                check(System.nanoTime() < deadline) { "Reception did not initialize" }
                Thread.sleep(100)
            }
            js("document.querySelector('[data-action=translation]').click()")
            check(js("document.body.innerText.includes('Add your OpenAI API key in Translation setup.')") == "true")
            check(js("!!document.querySelector('[data-action=setup-translation]')") == "true")
            check(js("!document.querySelector('[data-action=start]:not([disabled])')") == "true") {
                "Translation without BYOK exposed an enabled Start button"
            }
            repeat(2) {
                instrumentation.runOnMainSync {
                    handle.invoke(activity, JSONObject().put("action", "start").put("mode", "translation")
                        .put("session_id", UUID.randomUUID().toString()).put("language", "fr").put("staff_language", "en"))
                    check((ownerField.get(activity) as SessionOwner).current == null) { "Rejected translation retained session ownership" }
                    check(audioField.get(activity) == "off") { "Rejected translation changed audio state" }
                }
            }
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == permissionBefore)
            check(js("document.documentElement.dataset.captureActive") == "\"false\"")
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun evaluate(instrumentation: Instrumentation, browser: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync { browser.evaluateJavascript(script) { value = it; completed.countDown() } }
        check(completed.await(15, TimeUnit.SECONDS)) { "WebView JavaScript callback timed out" }
        return value
    }

    fun verify(instrumentation: Instrumentation) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val messages = LinkedBlockingQueue<JSONObject>()
        lateinit var page: ReceptionView
        lateinit var browser: WebView
        var unavailable = false
        instrumentation.runOnMainSync {
            page = ReceptionView(activity, { messages.offer(it) }) { unavailable = true }
            browser = page.getChildAt(0) as WebView
            activity.addContentView(page, FrameLayout.LayoutParams(-1, -1))
            // Exercise native events queued before page initialization, not a hand-written JS bridge.
            page.push(JSONObject().put("type", "reception_info").put("audio_available", true)
                .put("translation_available", true).put("openai_configured", true).put("reception_available", false)
                .put("local_audio_state", "off").put("hotel_name", "Bridge fixture"))
            page.push(JSONObject().put("type", "language_preferences").put("language", "fr").put("staff_language", "en"))
            page.setShown(true)
        }
        fun evaluate(script: String): String {
            val completed = CountDownLatch(1)
            var value = ""
            instrumentation.runOnMainSync { browser.evaluateJavascript(script) { value = it; completed.countDown() } }
            check(completed.await(10, TimeUnit.SECONDS)) { "WebView JavaScript callback timed out" }
            return value
        }
        fun expectAction(action: String): JSONObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val message = messages.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (message.optString("action") == action) return message
            }
            error("Shipped WebView did not deliver expected native action: $action")
        }
        try {
            expectAction("info")
            check(!unavailable) { "Shipped assets failed to load" }
            // Initial info is sent while JS initializes; onPageFinished then flushes the native queue.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (evaluate("document.getElementById('hotel-label').textContent") != "\"Bridge fixture\"") {
                check(System.nanoTime() < deadline) { "Queued native startup events did not reach the page" }
                Thread.sleep(100)
            }
            check(evaluate("!!document.querySelector('[data-action=appearance]')") == "true")
            evaluate("document.querySelector('[data-action=appearance]').click()")
            check(expectAction("appearance").optString("value") in listOf("light", "dark"))
            evaluate("document.querySelector('[data-action=translation]').click()")
            check(evaluate("document.querySelector('[data-language-side=language]').value") == "\"fr\"")
            check(evaluate("document.querySelector('[data-language-side=staff_language]').value") == "\"en\"")
            check(evaluate("document.querySelector('[data-action=start]').disabled") == "false") { "Start stays disabled after native capability/pair" }
            evaluate("document.querySelector('[data-action=start]').click()")
            val start = expectAction("start")
            check(start.optString("mode") == "translation")
            check(start.optString("language") == "fr" && start.optString("staff_language") == "en")
            check(UUID.fromString(start.getString("session_id")).toString() == start.getString("session_id"))
            check(evaluate("document.documentElement.dataset.page") == "\"active\"")
        } finally {
            instrumentation.runOnMainSync { page.dispose(); activity.finish() }
        }
    }
}
