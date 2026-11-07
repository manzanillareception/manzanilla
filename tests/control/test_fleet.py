"""Synthetic API integration: no provider or deployed hotel calls."""
import secrets
import time

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import func, select

from control.app import create_app
from control.auth import IdentityError, Principal, WebSession
from control.db import Base
from control.models import (Alert, Command, Device, DeviceCredential, DeviceEvent, Enrollment,
                            Membership, User, WorkerLease, WorkerProgress)
from control.settings import Settings
from control.worker import run_once


@pytest.fixture
def fleet():
    app = create_app(Settings(environment="test"), identity_provider=object())
    Base.metadata.create_all(app.state.engine)
    users = {name: Principal(name, name + "@example.invalid", name == "owner")
             for name in ("owner", "manager", "other", "operator", "viewer")}
    actor = [users["owner"]]
    app.dependency_overrides[app.state.auth.require_user] = lambda: actor[0]
    with app.state.sessions.begin() as db:
        db.add_all(User(id=u.id, email=u.email, enabled=True, platform_owner=u.platform_owner) for u in users.values())
        db.add(WorkerLease(name="fleet_maintenance", owner="", expires_at=0))
        db.add(WorkerProgress(name="fleet_maintenance", cursor="", processed=0))
    with TestClient(app) as client:
        one = client.post("/api/v2/hotels", json={"name": "Synthetic One", "timezone": "Europe/Madrid"}).json()
        two = client.post("/api/v2/hotels", json={"name": "Synthetic Two", "timezone": "Europe/Paris"}).json()
        assert "id" in one and "id" in two
        with app.state.sessions.begin() as db:
            db.add_all([Membership(user_id="manager", hotel_id=one["id"], role="manager"),
                        Membership(user_id="other", hotel_id=two["id"], role="manager"),
                        Membership(user_id="viewer", hotel_id=one["id"], role="viewer"),
                        Membership(user_id="operator", hotel_id=one["id"], role="operator")])
        yield app, client, actor, users, one["id"], two["id"]


def enroll(fleet, hotel=None):
    app, client, actor, users, one, _ = fleet
    actor[0] = users["owner"]
    value = {"installation_id": secrets.token_urlsafe(20), "device_name": "Synthetic reception",
             "poll_token": secrets.token_urlsafe(32)}
    result = client.post("/api/v2/enrollment/start", json=value)
    assert result.status_code == 201, result.text
    pair = result.json()
    approved = client.post("/api/v2/enrollments/approve", json={"hotel_id": hotel or one, "user_code": pair["user_code"]})
    assert approved.status_code == 200, approved.text
    issued = client.post(f"/api/v2/enrollment/{pair['request_id']}/poll", json={"poll_token": pair["poll_token"]})
    assert issued.status_code == 200, issued.text
    device = issued.json()
    headers = {"Authorization": "Bearer " + device["device_token"]}
    assert client.post("/api/v2/device/activate", headers=headers).status_code == 200
    return device, headers, pair


def heartbeat(sequence=1, boot="synthetic-boot", **changes):
    result = {"schema_version": 1, "boot_id": boot, "boot_generation": 1, "sequence": sequence,
              "app_version": "17.0.45", "version_code": 75, "foreground": True,
              "mode": "idle", "mic_state": "off", "config_version": 0,
              "update_state": "current", "health": {"microphone_permission": True, "audio_released": True}}
    result.update(changes)
    return result


def test_current_membership_isolation_and_revocation(fleet):
    app, client, actor, users, one, two = fleet
    actor[0] = users["manager"]
    assert [h["id"] for h in client.get("/api/v2/hotels").json()["items"]] == [one]
    assert client.get(f"/api/v2/hotels/{two}").status_code == 403
    assert client.get("/api/v2/devices", params={"hotel_id": two}).status_code == 403
    assert client.post("/api/v2/hotels", json={"name": "Denied", "timezone": "UTC"}).status_code == 403
    actor[0] = users["owner"]
    assert client.delete(f"/api/v2/hotels/{one}/members/manager").status_code == 200
    actor[0] = users["manager"]
    assert client.get(f"/api/v2/hotels/{one}").status_code == 403


def test_stale_principal_cannot_preserve_owner_privilege(fleet):
    app, client, actor, users, one, _ = fleet
    with app.state.sessions.begin() as db:
        db.get(User, "owner").platform_owner = False
    assert client.post("/api/v2/hotels", json={"name": "Denied", "timezone": "UTC"}).status_code == 403


def test_owner_account_management_and_live_roles(fleet):
    app, client, actor, users, one, _ = fleet
    assert client.patch("/api/v2/users/owner", json={"enabled": False}).status_code == 409
    assert client.patch("/api/v2/users/manager", json={"enabled": False}).status_code == 200
    actor[0] = users["manager"]
    assert client.get("/api/v2/hotels").status_code == 401
    actor[0] = users["owner"]
    assert client.patch("/api/v2/users/manager", json={"enabled": True}).status_code == 200
    assert client.patch(f"/api/v2/hotels/{one}/members/manager", json={"role": "viewer"}).status_code == 200
    actor[0] = users["manager"]
    assert client.put(f"/api/v2/hotels/{one}/config", json={"expected_version": 0, "config": {}}).status_code == 403
    assert client.get("/api/v2/users").status_code == 403


def test_disabling_account_deletes_existing_browser_sessions(fleet):
    app, client, *_ = fleet
    with app.state.sessions.begin() as db:
        db.add(WebSession(sid_hash="f" * 64, subject="manager", csrf_hash="e" * 64,
            encrypted_credentials="synthetic-encrypted-placeholder", expires_at=time.time() + 60,
            provider_expires_at=time.time() + 60, revision=1, flow="login", assurance_level="aal1"))
    assert client.patch("/api/v2/users/manager", json={"enabled": False}).status_code == 200
    with app.state.sessions() as db:
        assert db.scalar(select(WebSession).where(WebSession.subject == "manager")) is None


def test_invitation_retry_and_confirmed_user_second_hotel(fleet):
    app, client, actor, users, one, two = fleet

    class FakeInvite:
        calls = 0
        resolutions = 0

        def invite(self, email, redirect_to):
            self.calls += 1
            assert redirect_to.endswith("/")
            return "trusted-managed-subject"

        def resolve_existing_user(self, subject, email):
            self.resolutions += 1
            assert subject == "trusted-managed-subject" and email == "new@example.invalid"
            return subject

    provider = FakeInvite()
    app.state.auth.provider = provider
    payload = {"email": "new@example.invalid", "role": "manager"}
    first = client.post(f"/api/v2/hotels/{one}/invitations", json=payload)
    assert first.status_code == 201, first.text
    assert first.json()["status"] == "invited"
    retry = client.post(f"/api/v2/hotels/{one}/invitations", json=payload)
    assert retry.status_code == 201 and retry.json()["user_id"] == first.json()["user_id"]
    assert provider.calls == 1 and provider.resolutions == 0
    second = client.post(f"/api/v2/hotels/{two}/invitations", json=payload)
    assert second.status_code == 201, second.text
    assert second.json()["status"] == "assigned"
    assert provider.calls == 1 and provider.resolutions == 1
    with app.state.sessions() as db:
        assert db.get(Membership, ("trusted-managed-subject", two)).role == "manager"


def test_stale_email_cannot_grant_existing_account_another_hotel(fleet):
    app, client, actor, users, one, two = fleet

    class StaleIdentity:
        def resolve_existing_user(self, subject, email):
            raise IdentityError(409, "Identity mapping requires review")

    app.state.auth.provider = StaleIdentity()
    response = client.post(f"/api/v2/hotels/{two}/invitations", json={"email": users["manager"].email, "role": "manager"})
    assert response.status_code == 409
    with app.state.sessions() as db:
        assert db.get(Membership, ("manager", two)) is None


def test_enrollment_first_response_loss_recovery_and_capability_binding(fleet):
    app, client, actor, users, one, _ = fleet
    body = {"installation_id": secrets.token_urlsafe(20), "device_name": "One", "poll_token": secrets.token_urlsafe(32)}
    first = client.post("/api/v2/enrollment/start", json=body).json()
    retry = client.post("/api/v2/enrollment/start", json=body)
    assert retry.status_code == 201
    assert retry.json() == first
    assert client.post("/api/v2/enrollment/start", json={**body, "device_name": "Different"}).status_code == 409
    assert client.post(f"/api/v2/enrollment/{first['request_id']}/poll", json={"poll_token": secrets.token_urlsafe(32)}).status_code == 403
    with app.state.sessions() as db:
        row = db.get(Enrollment, first["request_id"])
        assert row.poll_hash != body["poll_token"]
        assert db.scalar(select(func.count()).select_from(Enrollment)) == 1


def test_manager_physical_code_only_flow_and_private_pending_list(fleet):
    app, client, actor, users, one, two = fleet
    pending = client.post("/api/v2/enrollment/start", json={"installation_id": secrets.token_urlsafe(20), "device_name": "Pending"}).json()
    actor[0] = users["manager"]
    assert client.get("/api/v2/enrollments").json()["items"] == []
    assert client.post(f"/api/v2/enrollments/{pending['request_id']}/approve", json={"hotel_id": one}).status_code == 403
    assert client.post("/api/v2/enrollments/approve", json={"hotel_id": two, "user_code": pending["user_code"]}).status_code == 403
    approved = client.post("/api/v2/enrollments/approve", json={"hotel_id": one, "user_code": pending["user_code"]})
    assert approved.status_code == 200
    assert client.post("/api/v2/enrollments/approve", json={"hotel_id": one, "user_code": pending["user_code"]}).json() == approved.json()


def test_activation_clears_encrypted_issuance_and_does_not_duplicate_device(fleet):
    app, client, actor, users, one, _ = fleet
    device, headers, pair = enroll(fleet)
    assert client.post("/api/v2/device/activate", headers=headers).status_code == 200
    polled = client.post(f"/api/v2/enrollment/{pair['request_id']}/poll", json={"poll_token": pair["poll_token"]}).json()
    assert polled == {"status": "activated"}
    with app.state.sessions() as db:
        credential = db.scalar(select(DeviceCredential))
        assert credential.token_cipher is None
        assert credential.token_hash != device["device_token"]
        assert db.scalar(select(func.count()).select_from(Device)) == 1


def test_heartbeat_replay_retired_boot_and_lost_first_attempt(fleet):
    app, client, *_ = fleet
    device, headers, _ = enroll(fleet)
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(4)).status_code == 200
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(4)).status_code == 409
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(1, boot="new-boot", boot_generation=2)).status_code == 200
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(5)).status_code == 409


def test_unseen_delayed_boot_cannot_replace_current_generation(fleet):
    app, client, *_ = fleet
    device, headers, _ = enroll(fleet)
    # Generation 1 never reached the server before generation 2 started.
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(4, boot="current-boot", boot_generation=2)).status_code == 200
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(99, boot="unseen-old-boot", boot_generation=1)).status_code == 409
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(100, boot="another-boot", boot_generation=2)).status_code == 409
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(5, boot="current-boot", boot_generation=2)).status_code == 200
    # A higher generation cannot recycle an already retired boot identity.
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(1, boot="third-boot", boot_generation=3)).status_code == 200
    assert client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(6, boot="current-boot", boot_generation=4)).status_code == 409
    with app.state.sessions() as db:
        stored = db.get(Device, device["device_id"])
        assert stored.boot_id == "third-boot" and stored.boot_generation == 3


def test_private_metrics_rejected_without_reflection(fleet):
    _, client, *_ = fleet
    _, headers, _ = enroll(fleet)
    secret = "PRIVATE_GUEST_OR_KEY_MUST_NOT_BE_REFLECTED"
    response = client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(health={"transcript": secret}))
    assert response.status_code == 422
    assert secret not in response.text
    response = client.post("/api/v2/device/events", headers=headers, json={"events": [{"id": "bad", "type": "voice_timing", "occurred_at": time.time(), "metrics": {"caption": secret}}]})
    assert response.status_code == 422 and secret not in response.text


def test_event_dedup_conflict_atomicity_and_no_false_presence(fleet):
    app, client, *_ = fleet
    device, headers, _ = enroll(fleet)
    client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat())
    with app.state.sessions.begin() as db:
        db.get(Device, device["device_id"]).last_seen = time.time() - 1000
    item = {"id": "event-one", "type": "voice_timing", "occurred_at": time.time() - 30, "metrics": {"native_audio_start_ms": 10}}
    assert client.post("/api/v2/device/events", headers=headers, json={"events": [item]}).json()["accepted_ids"] == ["event-one"]
    assert client.post("/api/v2/device/events", headers=headers, json={"events": [item]}).json()["duplicate_ids"] == ["event-one"]
    changed = {**item, "metrics": {"native_audio_start_ms": 11}}
    new = {**item, "id": "rollback-event"}
    assert client.post("/api/v2/device/events", headers=headers, json={"events": [new, changed]}).status_code == 409
    with app.state.sessions() as db:
        assert db.get(DeviceEvent, (device["device_id"], "rollback-event")) is None
    assert client.get(f"/api/v2/devices/{device['device_id']}").json()["connectivity"] == "offline"


def test_report_counts_and_timing_summary_use_received_window_and_disclose_limit(fleet):
    from control.service import telemetry_summary

    app, client, *_ = fleet
    device, headers, _ = enroll(fleet)
    now = time.time()
    items = [
        {"id": "timing-one", "type": "voice_timing", "occurred_at": now - 120, "metrics": {"native_audio_start_ms": 10}},
        {"id": "timing-two", "type": "voice_timing", "occurred_at": now - 60, "metrics": {"native_audio_start_ms": 30}},
        {"id": "start-one", "type": "session_started", "occurred_at": now - 120, "metrics": {}},
        {"id": "end-one", "type": "session_ended", "occurred_at": now - 60, "metrics": {}},
        {"id": "error-one", "type": "audio_error", "occurred_at": now - 60, "metrics": {}},
    ]
    assert client.post("/api/v2/device/events", headers=headers, json={"events": items}).status_code == 200
    summary = client.get(f"/api/v2/devices/{device['device_id']}").json()["telemetry_summary"]
    assert summary["window_basis"] == "received_at" and summary["window_days"] == 14
    assert summary["event_counts"] == {"session_started": 1, "session_ended": 1, "audio_error": 1}
    assert summary["timings"]["native_audio_start_ms"] == {"sample_count": 2, "mean_ms": 20, "min_ms": 10, "max_ms": 30}
    assert summary["sampled_timing_event_count"] == summary["total_timing_event_count"] == 2
    assert summary["truncated"] is False
    with app.state.sessions.begin() as db:
        db.get(DeviceEvent, (device["device_id"], "timing-one")).received_at = now - 30
        bounded = telemetry_summary(db, device["device_id"], event_limit=1, now=now + 1)
        assert bounded["sampled_timing_event_count"] == 1 and bounded["total_timing_event_count"] == 2
        assert bounded["truncated"] is True
        assert bounded["timings"]["native_audio_start_ms"]["sample_count"] == 1
    assert client.get(f"/api/v2/devices/{device['device_id']}").json()["connectivity"] == "unknown"


def test_config_cas_visibility_and_applied_receipts(fleet):
    app, client, actor, users, one, two = fleet
    _, headers, _ = enroll(fleet)
    change = {"expected_version": 0, "config": {"name": "Approved public hotel", "facts": "Breakfast at seven"}}
    assert client.put(f"/api/v2/hotels/{one}/config", json=change).json()["version"] == 1
    assert client.put(f"/api/v2/hotels/{one}/config", json=change).status_code == 409
    assert client.get("/api/v2/device/config", headers=headers).json()["config"]["facts"] == "Breakfast at seven"
    assert client.post("/api/v2/device/config/ack", headers=headers, json={"version": 9}).status_code == 409
    assert client.post("/api/v2/device/config/ack", headers=headers, json={"version": 1}).status_code == 200
    assert client.post("/api/v2/device/config/ack", headers=headers, json={"version": 0}).status_code == 409
    actor[0] = users["other"]
    assert client.get(f"/api/v2/hotels/{one}/config").status_code == 403


def test_authoritative_config_clears_coordinates_and_rejects_unimplemented_keys(fleet):
    app, client, actor, users, one, two = fleet
    _, headers, _ = enroll(fleet)
    assert client.put(f"/api/v2/hotels/{one}/config", json={"expected_version": 0, "config": {"latitude": 40.4, "longitude": -3.7}}).status_code == 200
    cleared = client.put(f"/api/v2/hotels/{one}/config", json={"expected_version": 1, "config": {"latitude": None, "longitude": None}})
    assert cleared.status_code == 200
    config = client.get("/api/v2/device/config", headers=headers).json()["config"]
    assert "latitude" in config and "longitude" in config
    assert config["latitude"] is None and config["longitude"] is None
    assert client.put(f"/api/v2/hotels/{one}/config", json={"expected_version": 2, "config": {"default_language": "es"}}).status_code == 422


def test_commands_idempotency_transition_expiry_and_unknown_outcome(fleet):
    app, client, actor, users, one, _ = fleet
    device, headers, _ = enroll(fleet)
    endpoint = f"/api/v2/devices/{device['device_id']}/commands"
    actor[0] = users["viewer"]
    command = {"type": "diagnostics", "arguments": {}, "idempotency_key": "operation-one"}
    assert client.post(endpoint, json=command).status_code == 403
    actor[0] = users["operator"]
    created = client.post(endpoint, json=command).json()
    assert client.post(endpoint, json=command).json()["id"] == created["id"]
    assert client.post(endpoint, json={**command, "type": "check_update"}).status_code == 409
    assert client.post(endpoint, json={**command, "arguments": {"url": "https://untrusted.invalid"}}).status_code == 422
    receipt = f"/api/v2/device/commands/{created['id']}/receipt"
    assert client.post(receipt, headers=headers, json={"state": "succeeded"}).status_code == 409
    assert client.post(receipt, headers=headers, json={"state": "received"}).status_code == 200
    # The running request may have been lost before reaching the server.
    assert client.post(receipt, headers=headers, json={"state": "reconciliation_required", "result_code": "interrupted"}).status_code == 200
    assert client.get("/api/v2/device/commands", headers=headers).json()["items"] == []
    second = client.post(endpoint, json={**command, "idempotency_key": "operation-two"}).json()
    with app.state.sessions.begin() as db:
        db.get(Command, second["id"]).expires_at = time.time() - 1
    assert client.get("/api/v2/device/commands", headers=headers).json()["items"] == []
    assert client.post(f"/api/v2/device/commands/{second['id']}/receipt", headers=headers, json={"state": "running"}).status_code == 409


def test_device_revocation_and_credential_rotation(fleet):
    app, client, actor, users, one, _ = fleet
    device, old_headers, _ = enroll(fleet)
    body = {"idempotency_key": "rotation-one"}
    rotated = client.post("/api/v2/device/credentials/rotate", headers=old_headers, json=body)
    assert rotated.status_code == 200, rotated.text
    assert client.post("/api/v2/device/credentials/rotate", headers=old_headers, json=body).json() == rotated.json()
    new_headers = {"Authorization": "Bearer " + rotated.json()["device_token"]}
    assert client.post("/api/v2/device/heartbeat", headers=new_headers, json=heartbeat()).status_code == 401
    assert client.post("/api/v2/device/activate", headers=new_headers).status_code == 200
    assert client.post("/api/v2/device/heartbeat", headers=old_headers, json=heartbeat()).status_code == 401
    assert client.post("/api/v2/device/heartbeat", headers=new_headers, json=heartbeat()).status_code == 200
    assert client.post(f"/api/v2/devices/{device['device_id']}/revoke").status_code == 200
    assert client.get("/api/v2/device/config", headers=new_headers).status_code == 401


def test_worker_alerts_are_deduplicated_and_resolve(fleet):
    app, client, *_ = fleet
    device, headers, _ = enroll(fleet)
    client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(health={"microphone_permission": False}))
    with app.state.sessions.begin() as db:
        db.get(Device, device["device_id"]).last_seen = time.time() - 1000
    assert run_once(app.state.settings, app.state.sessions)["claimed"] is True
    assert run_once(app.state.settings, app.state.sessions)["claimed"] is True
    first = client.get("/api/v2/alerts").json()["items"]
    assert len(first) == 2
    assert client.post(f"/api/v2/alerts/{first[0]['id']}/acknowledge").status_code == 200
    client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat(2))
    run_once(app.state.settings, app.state.sessions)
    with app.state.sessions() as db:
        assert all(a.state == "resolved" for a in db.scalars(select(Alert)))
        assert db.get(WorkerProgress, "fleet_maintenance").last_success is not None
    # A late acknowledgment must not revive an already resolved alert.
    assert client.post(f"/api/v2/alerts/{first[0]['id']}/acknowledge").json()["status"] == "resolved"


def test_activated_device_without_first_report_raises_alert(fleet):
    app, client, *_ = fleet
    device, headers, _ = enroll(fleet)
    with app.state.sessions.begin() as db:
        db.get(Device, device["device_id"]).created_at = time.time() - 600
    run_once(app.state.settings, app.state.sessions)
    items = client.get("/api/v2/alerts").json()["items"]
    assert [item["kind"] for item in items] == ["never_reported"]
    client.post("/api/v2/device/heartbeat", headers=headers, json=heartbeat())
    run_once(app.state.settings, app.state.sessions)
    assert client.get("/api/v2/alerts").json()["items"][0]["state"] == "resolved"


def test_scope_pagination_and_no_secrets_in_device_dto(fleet):
    app, client, actor, users, one, two = fleet
    first, _, _ = enroll(fleet)
    enroll(fleet, two)
    result = client.get("/api/v2/devices", params={"limit": 1}).json()
    assert result["total"] == 2 and result["next_offset"] == 1
    assert len(client.get("/api/v2/devices", params={"limit": 1, "offset": 1}).json()["items"]) == 1
    actor[0] = users["manager"]
    response = client.get("/api/v2/devices")
    assert response.json()["total"] == 1
    assert first["device_token"] not in response.text and "token_hash" not in response.text


def test_bounds_invalid_timezone_and_legacy_routes_absent(fleet):
    _, client, *_ = fleet
    assert client.post("/api/v2/hotels", json={"name": "Bad", "timezone": "Not/AZone"}).status_code == 422
    assert client.post("/api/v2/enrollment/start", content=b"x" * 262145).status_code == 413
    assert client.get("/admin").status_code == 404
    assert client.get("/api/hotels").status_code == 404
