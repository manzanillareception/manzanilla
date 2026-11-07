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
        fun push(event: JSONObject) = instrumentation.runOnMainSync { page.push(event) }
        fun scoped(type: String, session: String, generation: Int) = JSONObject()
            .put("type", type).put("session_id", session).put("generation", generation)
        fun expectJs(script: String, message: String) {
            check(evaluate(script) == "true") { message }
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
            val session = start.getString("session_id")
            val generation = 7
            evaluate("window.fixtureVisible=function(selector){var node=document.querySelector(selector);if(!node||node.hidden)return false;var rect=node.getBoundingClientRect();return getComputedStyle(node).display!=='none'&&rect.width>0&&rect.height>0;};true")
            push(scoped("audio_state", session, generation).put("state", "recording"))
            push(scoped("reception_event", session, generation).put("phase", "listening"))
            val firstText = "<img src=x>Bonjour. Première phrase."
            push(scoped("translation_text", session, generation).put("turn_id", "fixture-fr")
                .put("text", "<img src=x>Bonjour.").put("operation", "append").put("final", false))
            push(scoped("translation_text", session, generation).put("turn_id", "fixture-fr")
                .put("text", " Première phrase.").put("operation", "append").put("final", false))
            push(scoped("translation_text", session, generation).put("turn_id", "fixture-fr")
                .put("text", "").put("operation", "append").put("final", true))
            push(scoped("translation_text", session, generation).put("turn_id", "fixture-fr")
                .put("text", " Late output").put("operation", "append").put("final", false))
            push(scoped("translation_text", session, generation).put("turn_id", "fixture-en")
                .put("text", "Hello. A new response.").put("operation", "append").put("final", true))
            expectJs("document.querySelectorAll('.live-transcript-text').length===2", "Translation responses were concatenated or duplicated")
            expectJs("document.querySelector('[data-turn-id=fixture-fr]').textContent===${JSONObject.quote(firstText)}&&document.querySelector('[data-turn-id=fixture-fr]').dataset.final==='true'", "Empty final did not seal and preserve the first response")
            expectJs("document.querySelector('[data-turn-id=fixture-en]').textContent==='Hello. A new response.'&&!document.querySelector('.live-transcript img,.caption-side,.caption-guest,.caption-reception')", "Centered response text was unsafe or acquired speaker sides")
            expectJs("(function(){var box=document.querySelector('.conversation').getBoundingClientRect();var first=document.querySelector('[data-turn-id=fixture-fr]').getBoundingClientRect();var second=document.querySelector('[data-turn-id=fixture-en]').getBoundingClientRect();return Math.abs((box.left+box.right-first.left-first.right)/2)<2&&Math.abs((box.left+box.right-second.left-second.right)/2)<2&&second.top>=first.bottom-1;})()", "Response rows are not separately centered")
            push(scoped("translation_text", UUID.randomUUID().toString(), generation).put("turn_id", "stale-session").put("text", "Wrong session"))
            push(scoped("translation_text", session, generation + 1).put("turn_id", "stale-generation").put("text", "Wrong generation"))
            expectJs("document.querySelectorAll('.live-transcript-text').length===2", "Stale native events changed the transcript")
            evaluate("window.fixtureFirstRow=document.querySelector('[data-turn-id=fixture-fr]');true")
            push(scoped("audio_state", session, generation).put("state", "muted"))
            expectJs("window.fixtureFirstRow===document.querySelector('[data-turn-id=fixture-fr]')&&document.querySelector('.voice-ball').getAttribute('aria-pressed')==='true'", "Muting rebuilt the response or lost microphone status")
            push(JSONObject().put("type", "update_status").put("version", "99.0.0").put("phase", "ready").put("text", "Synthetic update ready"))
            expectJs("!window.fixtureVisible('#notifications-toggle')&&!window.fixtureVisible('#notifications-panel')&&!document.querySelector('#update-offer')&&!window.fixtureVisible('#toast')&&!window.fixtureVisible('#notices')", "An update interrupted translation with a banner or notification")
            check(messages.none { it.optString("action") in listOf("apply_update", "check_updates") }) { "Update notification triggered an automatic action" }
            push(JSONObject().put("type", "visibility").put("visible", false))
            push(scoped("audio_state", session, generation).put("state", "off"))
            push(JSONObject().put("type", "visibility").put("visible", true))
            expectJs("window.fixtureVisible('#notifications-toggle')&&!window.fixtureVisible('#notifications-panel')", "Home did not retain its closed notification bell")
            evaluate("document.getElementById('notifications-toggle').click();true")
            expectJs("window.fixtureVisible('#notifications-panel')&&document.getElementById('update-notification').dataset.phase==='ready'&&document.getElementById('update-notification').textContent.indexOf('Synthetic update ready')>=0", "The home notification panel lost the queued update")
            evaluate("document.getElementById('update-notification').click();true")
            expectAction("apply_update")
            push(JSONObject().put("type", "reception_info").put("translation_available", true)
                .put("openai_configured", true).put("reception_available", true).put("local_audio_state", "off"))
            evaluate("document.querySelector('[data-action=reception]').click();true")
            expectJs("!window.fixtureVisible('#notifications-toggle')&&!window.fixtureVisible('#notifications-panel')&&!!document.querySelector('.assistant-heading .assistant-mark svg')", "Night setup did not show its focused voice identity or close notifications")
            evaluate("document.querySelector('[data-action=assist-go]').click();true")
            val nightStart = expectAction("start")
            check(nightStart.optString("mode") == "reception")
            val nightSession = nightStart.getString("session_id")
            val nightGeneration = 8
            push(scoped("audio_state", nightSession, nightGeneration).put("state", "recording"))
            push(scoped("reception_event", nightSession, nightGeneration).put("phase", "listening"))
            push(scoped("assistant_text", nightSession, nightGeneration).put("text", "How can I help").put("final", false))
            push(scoped("assistant_text", nightSession, nightGeneration).put("text", "How can I help with your stay?").put("final", true))
            expectJs("document.documentElement.dataset.voice==='reception'&&!window.fixtureVisible('.topbar')&&!!document.querySelector('.session-top .assistant-mark svg')&&!!document.querySelector('.assistant-suggestions')&&!document.querySelector('.voice-ball').disabled", "Night reception did not show a focused, usable voice interface")
            expectJs("document.querySelectorAll('.live-transcript-text').length===1&&document.querySelector('.live-transcript-text').textContent==='How can I help with your stay?'", "Assistant final duplicated its streamed draft")
            push(JSONObject().put("type", "update_status").put("version", "99.0.0").put("phase", "confirm").put("text", "Synthetic installer ready"))
            expectJs("!window.fixtureVisible('#notifications-toggle')&&!window.fixtureVisible('#notifications-panel')&&!document.querySelector('#update-offer')&&!window.fixtureVisible('#toast')&&!window.fixtureVisible('#notices')", "An update interrupted the assistant with a banner or notification")
            push(JSONObject().put("type", "visibility").put("visible", false))
            push(scoped("audio_state", nightSession, nightGeneration).put("state", "off"))
            push(JSONObject().put("type", "visibility").put("visible", true))
            evaluate("document.getElementById('notifications-toggle').click();true")
            expectJs("window.fixtureVisible('#notifications-panel')&&document.getElementById('update-notification').dataset.phase==='confirm'", "Assistant-time update status was not retained for home")
            evaluate("document.getElementById('check-updates').click();true")
            expectAction("check_updates")
        } finally {
            instrumentation.runOnMainSync { page.dispose(); activity.finish() }
        }
    }
}
