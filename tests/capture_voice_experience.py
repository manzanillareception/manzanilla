"""Capture synthetic V1 voice screens from the shipped assets, without native audio.

Run with the existing browser-test dependencies. All requests are intercepted;
only exact packaged assets are served. No keys, hotel data, or providers are used.
"""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright


ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "android/hotel/src/main/assets/reception"
NATIVE = ROOT / "android/hotel/src/main/java/com/agentdeck/ui"
VIEWPORTS = ((1536, 691), (837, 471), (640, 360))


def packaged_page(browser, width, height):
    policy = (NATIVE / "ReceptionAssets.kt").read_text()
    native = (NATIVE / "ReceptionView.kt").read_text()
    host = re.search(r'const val HOST = "([^"]+)"', policy).group(1)
    paths = set(re.findall(r'"(/[^\"]+)"', policy))
    bridge = re.search(r'}, "(\w+Native)"\)', native).group(1)
    csp = re.search(r'"Content-Security-Policy" to "([^"]+)"', native).group(1)
    context = browser.new_context(
        viewport={"width": width, "height": height}, device_scale_factor=1,
        service_workers="block", reduced_motion="reduce")
    errors, loaded = [], set()

    def intercept(route):
        url = urlsplit(route.request.url)
        if (url.scheme != "https" or url.netloc != host or url.path not in paths
                or url.query or route.request.method != "GET"):
            errors.append(f"Blocked non-fixture request: {url.scheme}://{url.netloc}{url.path}")
            route.abort()
            return
        mime = {".js": "application/javascript", ".css": "text/css", ".html": "text/html"}[Path(url.path).suffix]
        loaded.add(url.path)
        route.fulfill(body=(ASSETS / url.path.lstrip("/")).read_bytes(), headers={
            "Content-Type": mime + "; charset=UTF-8", "Content-Security-Policy": csp,
            "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
        })

    context.route("**/*", intercept)
    context.add_init_script("""
        window.fixtureMessages = [];
        window[%s] = {postMessage: raw => window.fixtureMessages.push(JSON.parse(raw))};
    """ % json.dumps(bridge))
    page = context.new_page()
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.goto(f"https://{host}/index.html")
    assert not errors and loaded == paths, f"Packaged startup failed: {errors}; missing {paths - loaded}"
    return context, page, errors


def receive(page, event):
    page.evaluate("event => window.manzania.receive(event)", event)


def configure(page, translation=True):
    receive(page, {"type": "reception_info", "translation_available": translation,
                   "reception_available": True, "openai_configured": translation,
                   "local_audio_state": "off"})
    receive(page, {"type": "language_preferences", "language": "fr" if translation else "en",
                   "staff_language": "en" if translation else "es"})


def start_voice(page, mode):
    page.locator(f'[data-action="{mode}"]').click()
    page.locator('[data-action="start"]' if mode == "translation" else '[data-action="assist-go"]').click()
    start = page.evaluate("fixtureMessages.filter(event => event.action === 'start').at(-1)")
    assert start and start["mode"] == mode
    scope = {"session_id": start["session_id"], "generation": 1}
    receive(page, {**scope, "type": "audio_state", "state": "recording"})
    receive(page, {**scope, "type": "reception_event", "phase": "listening"})
    return scope


def assert_bounds(page, selectors):
    bounds = page.evaluate("""selectors => selectors.map(selector => {
        const node = document.querySelector(selector);
        if (!node) throw Error('Missing control: ' + selector);
        const rect = node.getBoundingClientRect();
        if (getComputedStyle(node).display === 'none' || node.hidden || rect.width <= 0 || rect.height <= 0)
            throw Error('Hidden control: ' + selector);
        if (rect.left < -1 || rect.top < -1 || rect.right > innerWidth + 1 || rect.bottom > innerHeight + 1)
            throw Error('Control exceeds viewport: ' + selector + ' ' + JSON.stringify(rect.toJSON()));
        return {selector, x:rect.x, y:rect.y, width:rect.width, height:rect.height};
    })""", selectors)
    return bounds


def assert_voice_focus(page):
    assert page.locator('#notifications-toggle').is_hidden()
    assert page.locator('#notifications-panel').is_hidden()
    assert page.locator('#update-offer').count() == 0
    assert page.locator('#toast').is_hidden()
    assert page.locator('#notices').is_hidden()
    assert page.locator('.topbar').is_hidden()
    assert page.locator('.caption-side,.caption-guest,.caption-reception').count() == 0
    assert page.locator('.voice-ball').is_enabled()


def capture(browser, output, width, height, screen):
    context, page, errors = packaged_page(browser, width, height)
    try:
        configure(page, translation=screen != "night-assistant")
        if screen == "translation":
            scope = start_voice(page, "translation")
            for turn_id, text in (
                ("response-fr-1", "Bonsoir. Pouvez-vous me recommander un restaurant à proximité ?"),
                ("response-en-1", "Good evening. Could you recommend a restaurant nearby?"),
                ("response-fr-2", "Bien sûr. Quel type de cuisine préférez-vous ?"),
            ):
                receive(page, {**scope, "type": "translation_text", "turn_id": turn_id,
                               "operation": "append", "text": text, "final": False})
                receive(page, {**scope, "type": "translation_text", "turn_id": turn_id,
                               "operation": "append", "text": "", "final": True})
            receive(page, {"type": "update_status", "version": "99.0.0", "phase": "ready",
                           "text": "Synthetic update available"})
            assert_voice_focus(page)
            assert page.locator('.live-transcript-text').count() == 3
            assert page.evaluate("""() => {
                const box = document.querySelector('.conversation').getBoundingClientRect();
                const rows = [...document.querySelectorAll('.live-transcript-text')];
                return rows.every((row, index) => {
                    const rect = row.getBoundingClientRect();
                    return Math.abs((rect.left+rect.right-box.left-box.right)/2) < 2 &&
                        (!index || rect.top >= rows[index-1].getBoundingClientRect().bottom - 1);
                });
            }"""), "Translation rows must stay separately centered"
            assert page.evaluate("""() => {
                const clip = document.querySelector('.session-scroll').getBoundingClientRect();
                const back = document.querySelector('.translate-back').getBoundingClientRect();
                const box = document.querySelector('.live-transcript').getBoundingClientRect();
                const rows = [...document.querySelectorAll('.live-transcript-text')];
                return box.top >= clip.top - 1 && box.bottom <= clip.bottom + 1 && rows.every(row => {
                    const rect = row.getBoundingClientRect();
                    const overlapsBack = rect.left < back.right && rect.right > back.left && rect.top < back.bottom && rect.bottom > back.top;
                    return rect.top >= clip.top - 1 && rect.bottom <= clip.bottom + 1 && !overlapsBack;
                });
            }"""), "The initial three response rows must be fully visible in the transcript viewport and clear of Back"
            bounds = assert_bounds(page, ['.pair-title', '.translate-back', '.live-transcript',
                                         '[data-turn-id="response-fr-1"]', '[data-turn-id="response-en-1"]',
                                         '[data-turn-id="response-fr-2"]', '.voice-ball', '#privacy'])
        elif screen == "night-assistant":
            scope = start_voice(page, "reception")
            receive(page, {**scope, "type": "assistant_text", "text": "Tell me what you need for your stay.", "final": False})
            receive(page, {**scope, "type": "assistant_text", "text": "Tell me what you need for your stay. I can check the hotel information available on this device.", "final": True})
            receive(page, {**scope, "type": "energy", "level": 0.4})
            receive(page, {"type": "update_status", "version": "99.0.0", "phase": "progress",
                           "text": "Synthetic update download", "percent": 50})
            assert_voice_focus(page)
            assert page.locator('.assistant-heading .assistant-mark svg').count() == 1
            assert page.locator('.live-transcript-text').count() == 1
            bounds = assert_bounds(page, ['.session-top', '.assistant-suggestions', '.voice-ball', '[data-action="stop"]', '#privacy'])
        else:
            receive(page, {"type": "update_status", "version": "99.0.0", "phase": "ready",
                           "text": "Synthetic update is ready. Open the installer when convenient."})
            page.locator('#notifications-toggle').click()
            assert page.locator('#notifications-panel').is_visible()
            assert page.locator('#update-notification').get_attribute('data-phase') == "ready"
            assert page.locator('#update-offer').count() == 0
            assert page.locator('#toast').is_hidden()
            bounds = assert_bounds(page, ['#notifications-toggle', '#notifications-panel', '#update-notification', '#check-updates'])
        assert not errors, f"Packaged page errors: {errors}"
        filename = f"{screen}-{width}x{height}.png"
        page.screenshot(path=str(output / filename), animations="disabled")
        return {"screen": screen, "width": width, "height": height, "file": filename, "bounds": bounds}
    finally:
        context.close()


def run(output, chromium=None):
    output.mkdir(parents=True, exist_ok=True)
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=chromium, headless=True, args=["--no-sandbox"])
        try:
            captures = [capture(browser, output, width, height, screen)
                        for width, height in VIEWPORTS
                        for screen in ("translation", "night-assistant", "home-notifications")]
            report = {"browser": browser.version,
                      "scope": "Actual packaged assets/CSP; synthetic native events; no microphone, keys, providers, or real guest data",
                      "captures": captures}
            (output / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")
            return report
        finally:
            browser.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chromium", help="Optional installed Chromium executable")
    parser.add_argument("--output", type=Path, default=ROOT / "work/voice-experience-qa")
    options = parser.parse_args()
    print(json.dumps(run(options.output, options.chromium), indent=2))
