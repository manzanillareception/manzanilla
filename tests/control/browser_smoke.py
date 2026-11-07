"""Real portal/browser + local API smoke test with disposable identities and data.

Run after ``npm --prefix admin-v2 run build``. Requires Playwright and Chromium;
set MANZ_TEST_CHROMIUM to a local executable, or install Playwright Chromium.
No external identity, voice provider, email, hotel, or device is contacted.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import secrets
import socket
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).parent))

import httpx
import uvicorn
from alembic import command
from alembic.config import Config
from fastapi import HTTPException
from fastapi.responses import FileResponse
from playwright.sync_api import expect, sync_playwright
from sqlalchemy import select

from control.app import create_app
from control.models import Hotel, Membership, User
from control.settings import Settings
from test_auth import FakeIdentity


def run():
    dist = ROOT / "admin-v2/dist"
    if not (dist / "index.html").is_file():
        raise RuntimeError("Build admin-v2 before running the browser smoke test")
    evidence = ROOT / "work/v2-browser"
    evidence.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="manz-browser-") as temporary:
        db_url = "sqlite+pysqlite:///" + str(Path(temporary) / "control.db")
        # Always migrate a fresh disposable DB; never inherit an operator's URL.
        previous = os.environ.get("MANZ_DATABASE_URL")
        environment = os.environ.get("MANZ_ENVIRONMENT")
        os.environ["MANZ_DATABASE_URL"], os.environ["MANZ_ENVIRONMENT"] = db_url, "test"
        try:
            command.upgrade(Config(str(ROOT / "control/alembic.ini")), "head")
        finally:
            for key, value in (("MANZ_DATABASE_URL", previous), ("MANZ_ENVIRONMENT", environment)):
                if value is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = value
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        listener.listen(128)
        origin = "http://127.0.0.1:" + str(listener.getsockname()[1])
        provider = FakeIdentity()
        provider.owner_aal = "aal1"
        app = create_app(Settings(environment="test", database_url=db_url,
                                  public_origin=origin, bootstrap_owner_sub="owner"), provider)
        with app.state.sessions.begin() as db:
            db.add(User(id="manager", email="manager@example.test", enabled=True, platform_owner=False))

        @app.get("/{path:path}")
        def frontend(path: str):
            target = (dist / path).resolve()
            if path.startswith(("api/", "health/")) or not target.is_relative_to(dist.resolve()):
                raise HTTPException(404)
            return FileResponse(target if target.is_file() else dist / "index.html")

        server = uvicorn.Server(uvicorn.Config(app, log_level="warning", access_log=False))
        thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()
        try:
            with httpx.Client(base_url=origin, trust_env=False, timeout=10) as device:
                deadline = time.monotonic() + 15
                while not server.started:
                    if not thread.is_alive() or time.monotonic() > deadline:
                        raise RuntimeError("Disposable fixture server failed to start")
                    threading.Event().wait(0.05)
                assert device.get("/health/ready").status_code == 200
                assert device.get("/api/v2/devices").status_code == 401
                with sync_playwright() as pw:
                    launch = {"headless": True}
                    if os.environ.get("MANZ_TEST_CHROMIUM"):
                        launch["executable_path"] = os.environ["MANZ_TEST_CHROMIUM"]
                        launch["args"] = ["--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu", "--no-zygote"]
                    browser = pw.chromium.launch(**launch)
                    errors = []

                    def context(width, height):
                        result = browser.new_context(viewport={"width": width, "height": height}, service_workers="block")

                        def local_only(route):
                            if route.request.url.startswith(origin + "/"):
                                route.continue_()
                            else:
                                errors.append("Blocked request outside disposable fixture")
                                route.abort()

                        result.route("**/*", local_only)
                        return result

                    owner = context(1440, 1000)
                    page = owner.new_page()
                    page.on("pageerror", lambda e: errors.append(str(e)))

                    def sign_in(p, email):
                        p.goto(origin)
                        p.get_by_label("Email address", exact=True).fill(email)
                        p.get_by_label("Password", exact=True).fill("correct-password")
                        p.get_by_role("button", name="Sign in", exact=True).click()

                    def navigate(p, name):
                        p.get_by_role("navigation").get_by_role("button", name=name, exact=True).click()

                    sign_in(page, "owner@example.test")
                    page.get_by_role("button", name="Create authenticator setup code").click()
                    page.get_by_label("Six-digit authenticator code").fill("123456")
                    page.get_by_role("button", name="Verify and continue").click()
                    expect(page.get_by_role("navigation")).to_be_visible()
                    assert provider.mfa_verifies == 1
                    navigate(page, "Hotels & people")
                    for hotel_name in ("Synthetic Coast", "Synthetic Mountain"):
                        page.get_by_label("Hotel name", exact=True).fill(hotel_name)
                        page.get_by_role("button", name="Create hotel", exact=True).click()
                        expect(page.get_by_role("button", name=hotel_name, exact=True)).to_be_visible()
                    with app.state.sessions.begin() as db:
                        hotels = {h.name: h.id for h in db.scalars(select(Hotel))}
                        db.add(Membership(user_id="manager", hotel_id=hotels["Synthetic Mountain"], role="manager"))

                    started = device.post("/api/v2/enrollment/start", json={
                        "installation_id": secrets.token_urlsafe(24), "device_name": "Synthetic front desk",
                        "poll_token": secrets.token_urlsafe(32)})
                    assert started.status_code == 201, started.text
                    pair = started.json()
                    navigate(page, "Pair devices")
                    page.get_by_label("Pairing code shown on device").fill(pair["user_code"])
                    page.get_by_label("Assign to hotel").select_option(hotels["Synthetic Coast"])
                    page.get_by_role("button", name="Approve device", exact=True).click()
                    expect(page.get_by_text("Pairing approved.", exact=False)).to_be_visible()
                    issued = device.post(f"/api/v2/enrollment/{pair['request_id']}/poll", json={"poll_token": pair["poll_token"]})
                    assert issued.status_code == 200, issued.text
                    credential = issued.json()
                    device.headers["Authorization"] = "Bearer " + credential["device_token"]
                    assert device.post("/api/v2/device/activate").status_code == 200
                    heartbeat = {"schema_version": 1, "boot_id": "browser-fixture-boot", "boot_generation": 1, "sequence": 3,
                        "app_version": "17.0.45", "version_code": 75, "foreground": True,
                        "mode": "idle", "mic_state": "off", "config_version": 0, "update_state": "current",
                        "health": {"microphone_permission": True, "audio_released": True, "battery_percent": 81}}
                    response = device.post("/api/v2/device/heartbeat", json=heartbeat)
                    assert response.status_code == 200, response.text
                    events = {"events": [
                        {"id": "browser-event-1", "type": "diagnostics", "occurred_at": time.time(),
                         "metrics": {"success": True, "battery_percent": 81}},
                        {"id": "browser-start-1", "type": "session_started", "occurred_at": time.time(), "metrics": {}},
                        {"id": "browser-end-1", "type": "session_ended", "occurred_at": time.time(), "metrics": {}},
                        {"id": "browser-timing-1", "type": "voice_timing", "occurred_at": time.time(),
                         "metrics": {"connection_ready_ms": 120}},
                    ]}
                    assert device.post("/api/v2/device/events", json=events).status_code == 200
                    assert device.post("/api/v2/device/events", json=events).status_code == 200
                    navigate(page, "Devices")
                    page.reload()
                    page.get_by_role("button", name="Synthetic front desk", exact=True).click()
                    expect(page.get_by_role("heading", name="Synthetic front desk", exact=True)).to_be_visible()
                    detail = owner.request.get(origin + "/api/v2/devices/" + credential["device_id"]).json()
                    summary = detail["telemetry_summary"]
                    assert summary["event_counts"]["session_started"] == 1
                    assert summary["event_counts"]["session_ended"] == 1
                    assert summary["timings"]["connection_ready_ms"]["sample_count"] == 1
                    assert summary["timings"]["connection_ready_ms"]["mean_ms"] == 120
                    expect(page.get_by_role("heading", name="Reported activity", exact=True)).to_be_visible()
                    timing_row = page.get_by_role("table", name="Reported timing samples").get_by_role("row").filter(has_text="Connection ready")
                    expect(timing_row).to_contain_text("120")
                    page.get_by_role("button", name="Request diagnostics", exact=True).click()
                    expect(page.get_by_text("Command queued.", exact=False)).to_be_visible()
                    poll = device.get("/api/v2/device/commands")
                    assert poll.status_code == 200 and len(poll.json()["items"]) == 1, poll.text
                    task = poll.json()["items"][0]
                    for state in ("received", "running", "succeeded"):
                        receipt = device.post(f"/api/v2/device/commands/{task['id']}/receipt", json={"state": state, "result_code": "ok"})
                        assert receipt.status_code == 200, receipt.text
                    page.get_by_role("button", name="Close device details").click()
                    page.get_by_role("button", name="Synthetic front desk", exact=True).click()
                    expect(page.get_by_text("succeeded", exact=True)).to_be_visible()
                    page.screenshot(path=str(evidence / "owner-device.png"), full_page=True)
                    page.get_by_role("button", name="Close device details").click()
                    page.get_by_label("Filter by hotel").select_option(hotels["Synthetic Coast"])
                    navigate(page, "Hotel settings")
                    page.get_by_label("Public hotel name").fill("Synthetic Coast reception")
                    page.get_by_label("Approved hotel information").fill("Breakfast starts at 07:00. Synthetic test data only.")
                    page.get_by_role("button", name="Publish settings", exact=True).click()
                    expect(page.get_by_text("Settings published.", exact=False)).to_be_visible()
                    config = device.get("/api/v2/device/config").json()
                    assert config["version"] == 1 and config["config"]["name"] == "Synthetic Coast reception", config
                    assert device.post("/api/v2/device/config/ack", json={"version": 1}).status_code == 200

                    manager = context(1280, 900)
                    manager_page = manager.new_page()
                    manager_page.on("pageerror", lambda e: errors.append(str(e)))
                    sign_in(manager_page, "manager@example.test")
                    expect(manager_page.get_by_role("navigation")).to_be_visible()
                    allowed = manager.request.get(origin + "/api/v2/hotels").json()["items"]
                    assert [h["id"] for h in allowed] == [hotels["Synthetic Mountain"]], allowed
                    assert manager.request.get(origin + "/api/v2/hotels/" + hotels["Synthetic Coast"]).status == 403
                    assert manager.request.get(origin + "/api/v2/users").status == 403
                    navigate(manager_page, "Hotels & people")
                    expect(manager_page.get_by_role("button", name="Synthetic Mountain", exact=True)).to_be_visible()
                    expect(manager_page.get_by_role("button", name="Synthetic Coast", exact=True)).to_have_count(0)
                    manager_page.screenshot(path=str(evidence / "manager-scope.png"), full_page=True)
                    manager_page.get_by_role("button", name="Sign out", exact=True).click()
                    expect(manager_page.get_by_role("button", name="Sign in", exact=True)).to_be_visible()
                    assert device.get("/api/v2/device/config").status_code == 200
                    navigate(page, "Devices")
                    page.get_by_role("button", name="Synthetic front desk", exact=True).click()
                    page.once("dialog", lambda dialog: dialog.accept())
                    page.get_by_role("button", name="Revoke enrollment").click()
                    expect(page.get_by_text("Device enrollment revoked.", exact=False)).to_be_visible()
                    assert device.get("/api/v2/device/config").status_code == 401
                    assert errors == [], errors
                    browser.close()
            print(json.dumps({"result": "passed", "flow": "owner MFA, hotels, native-code pairing, activation, metadata, command receipts, config ACK, tenant scope, logout, revocation", "screenshots": str(evidence)}))
        finally:
            server.should_exit = True
            thread.join(timeout=10)
            listener.close()


if __name__ == "__main__":
    run()
