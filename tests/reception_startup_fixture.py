"""Exercise untouched packaged HTML/scripts/CSP with an isolated native transport.

The fake transport only records outbound JSON. It cannot grant microphone access
or contact a provider. Resource allowlisting and the bridge name/CSP are read
from the native implementation so browser fixture conveniences cannot hide an
unloadable production page.
"""
import json
from pathlib import Path
import re
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "android/hotel/src/main/assets/reception"
NATIVE = ROOT / "android/hotel/src/main/java/com/agentdeck/ui"


def run_startup_fixture(browser, legacy_object_api=False):
    native = (NATIVE / "ReceptionView.kt").read_text()
    policy = (NATIVE / "ReceptionAssets.kt").read_text()
    host = re.search(r'const val HOST = "([^"]+)"', policy).group(1)
    paths = set(re.findall(r'"(/[^\"]+)"', policy))
    bridge = re.search(r'}, "(\w+Native)"\)', native).group(1)
    csp = re.search(r'"Content-Security-Policy" to "([^"]+)"', native).group(1)
    context = browser.new_context(viewport={"width": 837, "height": 471}, service_workers="block")
    errors, loaded = [], []

    def intercept(route):
        url = urlsplit(route.request.url)
        if (url.scheme != "https" or url.netloc != host or url.path not in paths
                or url.query or route.request.method != "GET"):
            errors.append(f"Blocked request: {url.scheme}://{url.netloc}{url.path}")
            route.abort()
            return
        suffix = Path(url.path).suffix
        mime = {".js": "application/javascript", ".css": "text/css", ".html": "text/html"}[suffix]
        loaded.append(url.path)
        route.fulfill(body=(ASSETS / url.path.lstrip("/")).read_bytes(), headers={
            "Content-Type": mime + "; charset=UTF-8", "Content-Security-Policy": csp,
            "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
        })

    context.route("**/*", intercept)
    context.add_init_script("""
        window.fixtureMessages = [];
        window[%s] = {postMessage: raw => window.fixtureMessages.push(JSON.parse(raw))};
        %s
    """ % (json.dumps(bridge), "delete Object.hasOwn; delete Object.fromEntries;" if legacy_object_api else ""))
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    try:
        page.goto(f"https://{host}/index.html")
        assert not errors, f"Packaged page failed during startup: {errors}"
        assert set(loaded) == paths, f"Packaged resources not loaded: {paths - set(loaded)}"
        assert page.evaluate("fixtureMessages.some(event => event.action === 'info')"), "Native startup handshake missing"
        assert page.locator('[data-action="translation"]').is_visible(), "Home did not render"
        assert page.locator('#notifications-toggle').is_visible(), "The update bell belongs on home"
        assert page.locator('#notifications-panel').is_hidden(), "Notifications stay closed until the bell is opened"
        assert page.locator('#check-updates').is_hidden(), "Update controls belong inside the closed notification panel"
        page.locator('#notifications-toggle').click()
        assert page.locator('#notifications-panel').is_visible() and page.locator('#check-updates').is_visible()
        page.locator('#notifications-close').click()
        assert page.locator('#notifications-panel').is_hidden()

        def receive(event):
            page.evaluate("event => window.manzania.receive(event)", event)

        receive({"type": "reception_info", "translation_available": True, "reception_available": True, "openai_configured": True, "local_audio_state": "off", "staff_language": "en"})
        receive({"type": "language_preferences", "language": "en", "staff_language": "en"})
        page.locator('[data-action="translation"]').click()
        assert page.locator('#notifications-toggle').is_hidden() and page.locator('#notifications-panel').is_hidden(), "Translation setup cannot show update notifications"
        assert page.locator('[data-action="start"]').is_disabled(), "Same-language start must be blocked"
        assert "two different languages" in page.locator("#translation-readiness").inner_text()
        assert page.locator('label[for="language"]').inner_text() == "Language 1"
        assert page.locator('label[for="staff_language"]').inner_text() == "Language 2"
        receive({"type": "reception_info", "translation_available": False, "reception_available": True, "openai_configured": False, "local_audio_state": "off",
                 "audio_gate": "Add your OpenAI API key to use live translation."})
        assert "OpenAI" in page.locator("#translation-readiness").inner_text()
        assert page.locator('[data-action="start"]').is_disabled(), "Unavailable translation must not offer a start"
        assert page.locator('[data-action="setup-translation"]').is_visible(), "Missing OpenAI key offers in-app BYOK setup"
        page.locator('[data-action="setup-translation"]').click()
        assert page.evaluate("fixtureMessages.filter(event => event.action === 'voice_setup').length") == 1
        assert page.locator("#settings").is_visible(), "Translation status must remain reachable"
        page.locator('#settings').click()
        assert page.evaluate("fixtureMessages.filter(event => event.action === 'voice_setup').length") == 2
        assert not page.evaluate("fixtureMessages.some(event => event.action === 'start')")
        assert not page.evaluate("fixtureMessages.some(event => event.action === 'settings')"), "Translation status must not expose hotel administration"
        receive({"type": "visibility", "visible": False})
        receive({"type": "visibility", "visible": True})
        receive({"type": "translation_setup_return"})
        assert page.locator('#language').input_value() == "en"
        assert page.locator('#staff_language').input_value() == "en"
        assert page.locator('[data-action="start"]').is_disabled()
        # The isolated bridge advertises configured OpenAI availability without a key.
        receive({"type": "reception_info", "translation_available": True, "reception_available": True,
                 "openai_configured": True, "local_audio_state": "off"})
        page.locator('#language').select_option("es")
        assert page.locator('[data-action="start"]').is_enabled(), "Distinct languages with configured translation must be able to start"
        page.locator('[data-action="start"]').click()
        starts = page.evaluate("fixtureMessages.filter(event => event.action === 'start')")
        assert len(starts) == 1 and starts[0]["language"] == "es" and starts[0]["staff_language"] == "en", starts
        assert starts[0]["session_id"] and starts[0]["mode"] == "translation", starts
        assert not errors, f"Packaged page runtime errors: {errors}"
        return {"fixture": "packaged_page_startup", "passed": True,
                "object_has_own_available": not legacy_object_api, "resources": sorted(loaded),
                "scope": "unchanged packaged page; isolated bridge; no microphone or provider"}
    finally:
        context.close()
