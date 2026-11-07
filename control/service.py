from __future__ import annotations

import hashlib
import json
import secrets
import time

from cryptography.fernet import Fernet
from fastapi import HTTPException
from sqlalchemy import case, func, select
from sqlalchemy.orm import Session

from .models import (
    Alert, AuditEvent, Command, CommandReceipt, ConfigRevision, Device, DeviceBoot,
    DeviceCredential, DeviceEvent, Enrollment, Hotel, Membership, Organization,
    RateBucket, User,
)

ROLE_LEVEL = {"viewer": 1, "operator": 2, "manager": 3, "owner": 4}
TERMINAL = {"succeeded", "failed", "expired", "reconciliation_required", "cancelled"}
COMMAND_TRANSITIONS = {
    "queued": {"received", "deferred", "running", "failed", "reconciliation_required"},
    "received": {"deferred", "running", "failed", "reconciliation_required"},
    "deferred": {"received", "running", "failed", "reconciliation_required"},
    "running": {"deferred", "succeeded", "failed", "reconciliation_required"},
}


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def fingerprint(value) -> str:
    return digest(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False))


def audit(db: Session, actor: str, action: str, target: str, hotel_id=None):
    db.add(AuditEvent(hotel_id=hotel_id, actor_id=actor, action=action, target_id=target))


def current_actor(db: Session, actor):
    user = db.get(User, actor.id)
    if not user or not user.enabled:
        raise HTTPException(401, "Account unavailable")
    return user


def hotel_scope(db: Session, actor, hotel_id: str, minimum="viewer") -> tuple[Hotel, str]:
    user = current_actor(db, actor)
    hotel = db.get(Hotel, hotel_id)
    if not hotel:
        raise HTTPException(404, "Hotel not found")
    role = "owner" if user.platform_owner else None
    if role is None:
        membership = db.get(Membership, (user.id, hotel_id))
        role = membership.role if membership else None
    if ROLE_LEVEL.get(role, 0) < ROLE_LEVEL[minimum]:
        raise HTTPException(403, "Hotel access denied")
    return hotel, role


def visible_hotels(db: Session, actor):
    user = current_actor(db, actor)
    if user.platform_owner:
        return select(Hotel.id)
    return select(Membership.hotel_id).where(Membership.user_id == user.id)


def hotel_json(hotel: Hotel, role: str):
    return {"id": hotel.id, "organization_id": hotel.organization_id, "name": hotel.name,
            "timezone": hotel.timezone, "config_version": hotel.config_version, "role": role}


def create_hotel(db, actor, value):
    if not current_actor(db, actor).platform_owner:
        raise HTTPException(403, "Owner access required")
    org = Organization(name=value.name)
    db.add(org)
    db.flush()
    hotel = Hotel(name=value.name, timezone=value.timezone, organization_id=org.id)
    db.add(hotel)
    db.flush()
    audit(db, actor.id, "hotel_created", hotel.id, hotel.id)
    return hotel_json(hotel, "owner")


def device_scope(db, actor, device_id, minimum="viewer", *, lock=False):
    query = select(Device).where(Device.id == device_id)
    if lock:
        query = query.with_for_update()
    device = db.scalar(query)
    if not device:
        raise HTTPException(404, "Device not found")
    hotel_scope(db, actor, device.hotel_id, minimum)
    return device


def device_auth(db, authorization: str, *, pending=False, lock=False):
    if not authorization.startswith("Bearer "):
        raise HTTPException(401, "Device authentication required")
    token = authorization[7:]
    if not 32 <= len(token) <= 200:
        raise HTTPException(401, "Invalid device credential")
    credential = db.scalar(select(DeviceCredential).where(DeviceCredential.token_hash == digest(token)))
    if credential and lock:
        # Every device mutation takes Device before Credential, matching owner
        # revocation and activation. Locking the reverse order can deadlock PG.
        device = db.scalar(select(Device).where(Device.id == credential.device_id)
                           .with_for_update().execution_options(populate_existing=True))
        credential = db.scalar(select(DeviceCredential).where(DeviceCredential.id == credential.id)
                               .with_for_update().execution_options(populate_existing=True))
    else:
        device = db.get(Device, credential.device_id) if credential else None
    now = time.time()
    if not credential or credential.revoked_at or (credential.expires_at is not None and credential.expires_at <= now):
        raise HTTPException(401, "Device credential expired or revoked")
    allowed = ("active", "pending") if pending else ("active",)
    if not device or device.status not in allowed or (not pending and credential.state != "active"):
        raise HTTPException(401, "Device is not active")
    return device, credential


def device_json(db, device, now=None):
    now = time.time() if now is None else now
    age = now - device.last_seen if device.last_seen is not None else None
    connectivity = "unknown" if age is None else "online" if age <= 90 else "stale" if age <= 300 else "offline"
    hotel = db.get(Hotel, device.hotel_id)
    return {"id": device.id, "hotel_id": device.hotel_id, "name": device.name,
            "status": device.status, "connectivity": connectivity, "last_seen": device.last_seen,
            "app_version": device.app_version, "version_code": device.version_code,
            "foreground": device.foreground, "mode": device.mode, "mic_state": device.mic_state,
            "update_state": device.update_state, "health": device.health,
            "config_version": device.config_version, "desired_config_version": hotel.config_version}


def telemetry_summary(db, device_id, *, event_limit=1000, now=None):
    """Accepted metadata only; report-window counts and explicitly bounded timings."""
    now = time.time() if now is None else now
    cutoff = now - 14 * 86400
    scope = (DeviceEvent.device_id == device_id, DeviceEvent.received_at >= cutoff,
             DeviceEvent.received_at <= now)
    counts = {"session_started": 0, "session_ended": 0, "audio_error": 0}
    for kind, count in db.execute(select(DeviceEvent.type, func.count()).where(
            *scope, DeviceEvent.type.in_(counts)).group_by(DeviceEvent.type)):
        counts[kind] = count
    total = db.scalar(select(func.count()).select_from(DeviceEvent).where(
        *scope, DeviceEvent.type == "voice_timing"))
    events = list(db.scalars(select(DeviceEvent).where(*scope, DeviceEvent.type == "voice_timing")
                  .order_by(DeviceEvent.received_at.desc(), DeviceEvent.id).limit(event_limit)))
    values = {}
    for event in events:
        for metric, value in event.metrics.items():
            # Ingestion already validates numeric metrics; keep the projection
            # defensive and restrict it to timing units, not arbitrary counters.
            if metric.endswith("_ms") and isinstance(value, (int, float)) and not isinstance(value, bool):
                values.setdefault(metric, []).append(value)
    timings = {name: {"sample_count": len(samples), "mean_ms": sum(samples) / len(samples),
                      "min_ms": min(samples), "max_ms": max(samples)}
               for name, samples in sorted(values.items())}
    return {"window_days": 14, "window_start": cutoff, "window_end": now,
            "window_basis": "received_at", "event_counts": counts, "timings": timings,
            "timing_event_limit": event_limit, "sampled_timing_event_count": len(events),
            "total_timing_event_count": total, "truncated": total > len(events)}


def limit(db, key, maximum=10, window_seconds=600):
    """Durable atomic counters, including when more than one API worker runs."""
    window = int(time.time()) // window_seconds
    hashed = digest(key)
    if db.bind.dialect.name == "postgresql":
        from sqlalchemy.dialects.postgresql import insert
    else:
        from sqlalchemy.dialects.sqlite import insert
    statement = insert(RateBucket).values(key=hashed, window=window, count=1)
    statement = statement.on_conflict_do_update(index_elements=[RateBucket.key], set_={
        "window": window,
        "count": case((RateBucket.window != window, 1), else_=RateBucket.count + 1),
    }).returning(RateBucket.count)
    count = db.scalar(statement)
    if count > maximum:
        # The caller must commit the counter even for a rejected request.
        db.commit()
        raise HTTPException(429, "Too many attempts; retry later", headers={"Retry-After": str(window_seconds)})


def cipher(settings) -> Fernet:
    key = settings.session_encryption_key
    if not key:
        raise HTTPException(503, "Encryption is not configured")
    return Fernet(key.encode() if isinstance(key, str) else key)


def start_enrollment(db, value, client_host):
    for old in db.scalars(select(Enrollment).where(Enrollment.installation_id == value.installation_id,
                          Enrollment.status.in_(("pending", "approved")), Enrollment.expires_at <= time.time())):
        old.status = "expired"
    db.flush()
    active = db.scalar(select(Enrollment).where(Enrollment.installation_id == value.installation_id,
                       Enrollment.status.in_(("pending", "approved")), Enrollment.expires_at > time.time()))
    if active:
        if value.poll_token and secrets.compare_digest(active.poll_hash, digest(value.poll_token)) and active.device_name == value.device_name:
            return {"request_id": active.id, "user_code": active.user_code,
                    "poll_token": value.poll_token, "expires_at": active.expires_at}
        raise HTTPException(409, "Pairing already pending; use the stored poll capability")
    existing = db.scalar(select(Device).where(Device.installation_id == value.installation_id))
    if existing and existing.status != "revoked":
        raise HTTPException(409, "Installation already enrolled; recover its stored pairing state")
    token = value.poll_token or secrets.token_urlsafe(32)
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    pairing = Enrollment(installation_id=value.installation_id, device_name=value.device_name,
                         user_code="".join(secrets.choice(alphabet) for _ in range(8)),
                         poll_hash=digest(token), expires_at=time.time() + 600)
    db.add(pairing)
    db.flush()
    return {"request_id": pairing.id, "user_code": pairing.user_code, "poll_token": token,
            "expires_at": pairing.expires_at}


def approve_enrollment(db, settings, actor, pairing_id, value):
    hotel_scope(db, actor, value.hotel_id, "manager")
    pairing = db.scalar(select(Enrollment).where(Enrollment.id == pairing_id).with_for_update())
    if not pairing or pairing.expires_at <= time.time():
        raise HTTPException(404, "Pairing expired or not found")
    if not current_actor(db, actor).platform_owner:
        if not value.user_code or not secrets.compare_digest(pairing.user_code, value.user_code.upper()):
            raise HTTPException(403, "Physical pairing code required")
    if pairing.status == "approved":
        device = db.get(Device, pairing.device_id)
        if device.hotel_id != value.hotel_id:
            raise HTTPException(409, "Pairing is already assigned")
        return {"status": "approved", "device_id": device.id}
    if pairing.status != "pending":
        raise HTTPException(409, "Pairing has already been consumed")
    device = db.scalar(select(Device).where(Device.installation_id == pairing.installation_id).with_for_update())
    if device:
        if device.status != "revoked" or device.hotel_id != value.hotel_id:
            raise HTTPException(409, "Installation cannot be reassigned")
        device.status = "pending"
        device.name = pairing.device_name
    else:
        device = Device(hotel_id=value.hotel_id, installation_id=pairing.installation_id, name=pairing.device_name)
        db.add(device)
        db.flush()
    token = secrets.token_urlsafe(32)
    encrypted = cipher(settings).encrypt((device.id + ":" + token).encode()).decode()
    db.add(DeviceCredential(device_id=device.id, token_hash=digest(token), token_cipher=encrypted,
                            operation_id="pair:" + pairing.id, expires_at=time.time() + 900))
    pairing.device_id, pairing.approved_by, pairing.status = device.id, actor.id, "approved"
    audit(db, actor.id, "enrollment_approved", device.id, device.hotel_id)
    return {"status": "approved", "device_id": device.id}


def poll_enrollment(db, settings, pairing_id, token, client_host):
    pairing = db.scalar(select(Enrollment).where(Enrollment.id == pairing_id))
    if not pairing or not secrets.compare_digest(pairing.poll_hash, digest(token)):
        raise HTTPException(403, "Invalid pairing capability")
    if pairing.expires_at <= time.time():
        return {"status": "expired"}
    if pairing.status != "approved":
        return {"status": pairing.status}
    credential = db.scalar(select(DeviceCredential).where(DeviceCredential.device_id == pairing.device_id,
                           DeviceCredential.operation_id == "pair:" + pairing.id))
    if not credential or not credential.token_cipher or credential.revoked_at:
        return {"status": "activated"}
    raw = cipher(settings).decrypt(credential.token_cipher.encode()).decode()
    device_id, token = raw.split(":", 1)
    device = db.get(Device, device_id)
    return {"status": "approved", "device_id": device.id, "device_token": token, "hotel_id": device.hotel_id}


def activate(db, authorization):
    device, credential = device_auth(db, authorization, pending=True, lock=True)
    if credential.state == "pending":
        for old in db.scalars(select(DeviceCredential).where(DeviceCredential.device_id == device.id,
                              DeviceCredential.id != credential.id, DeviceCredential.revoked_at.is_(None))):
            old.revoked_at = time.time()
            old.token_cipher = None
        credential.state, credential.expires_at, credential.token_cipher = "active", None, None
        device.status = "active"
        for pair in db.scalars(select(Enrollment).where(Enrollment.device_id == device.id, Enrollment.status == "approved")):
            pair.status = "activated"
        audit(db, device.id, "device_activated", device.id, device.hotel_id)
    return {"status": "active", "device_id": device.id, "hotel_id": device.hotel_id}


def heartbeat(db, authorization, value):
    device, _ = device_auth(db, authorization, lock=True)
    if value.boot_generation < device.boot_generation:
        raise HTTPException(409, "Stale boot generation")
    if value.boot_generation == device.boot_generation:
        if device.boot_id != value.boot_id:
            raise HTTPException(409, "Boot identity changed without a new generation")
        if value.sequence <= device.sequence:
            raise HTTPException(409, "Stale heartbeat sequence")
    else:
        if db.get(DeviceBoot, (device.id, value.boot_id)):
            raise HTTPException(409, "Retired boot identity")
        db.add(DeviceBoot(device_id=device.id, boot_id=value.boot_id))
    hotel = db.get(Hotel, device.hotel_id)
    if value.config_version > hotel.config_version:
        raise HTTPException(409, "Unknown configuration version")
    device.boot_id, device.boot_generation = value.boot_id, value.boot_generation
    device.sequence, device.last_seen = value.sequence, time.time()
    for name in ("app_version", "version_code", "foreground", "mode", "mic_state", "update_state", "health"):
        setattr(device, name, getattr(value, name))
    # Applied version is advanced only by the explicit configuration receipt.
    return {"device_id": device.id, "server_time": device.last_seen,
            "desired_config_version": hotel.config_version}


def ingest_events(db, authorization, value):
    device, _ = device_auth(db, authorization, lock=True)
    accepted, duplicate = [], []
    for item in value.events:
        if item.occurred_at > time.time() + 300:
            raise HTTPException(422, "Event timestamp is in the future")
        fp = fingerprint(item.model_dump())
        existing = db.get(DeviceEvent, (device.id, item.id))
        if existing:
            if existing.fingerprint != fp:
                raise HTTPException(409, "Event identity reused with different content")
            duplicate.append(item.id)
        else:
            db.add(DeviceEvent(device_id=device.id, **item.model_dump(), fingerprint=fp))
            db.flush()
            accepted.append(item.id)
    return {"accepted_ids": accepted, "duplicate_ids": duplicate}


def get_config(db, hotel):
    revision = db.get(ConfigRevision, (hotel.id, hotel.config_version))
    return {"version": hotel.config_version, "config": revision.config if revision else {}}


def change_config(db, actor, hotel_id, value):
    hotel_scope(db, actor, hotel_id, "manager")
    hotel = db.scalar(select(Hotel).where(Hotel.id == hotel_id).with_for_update().execution_options(populate_existing=True))
    if hotel.config_version != value.expected_version:
        raise HTTPException(409, "Configuration changed; refresh before editing")
    hotel.config_version += 1
    config = value.config.model_dump(exclude_none=True)
    # This is an authoritative profile revision. Explicit null coordinates
    # remove a previous hotel location rather than silently retaining it.
    config["latitude"], config["longitude"] = value.config.latitude, value.config.longitude
    db.add(ConfigRevision(hotel_id=hotel.id, version=hotel.config_version, config=config, author_id=actor.id))
    audit(db, actor.id, "config_changed", str(hotel.config_version), hotel.id)
    return {"version": hotel.config_version, "config": config}


def ack_config(db, authorization, value):
    device, _ = device_auth(db, authorization, lock=True)
    hotel = db.get(Hotel, device.hotel_id)
    if value.version > hotel.config_version or value.version < device.config_version:
        raise HTTPException(409, "Unknown or superseded configuration")
    if value.version != device.config_version:
        device.config_version = value.version
        audit(db, device.id, "config_applied", str(value.version), device.hotel_id)
    return {"version": device.config_version, "status": "acknowledged"}


def command_json(command):
    return {"id": command.id, "device_id": command.device_id, "type": command.type,
            "arguments": command.arguments, "state": command.state, "result_code": command.result_code,
            "created_at": command.created_at, "expires_at": command.expires_at}


def create_command(db, actor, device_id, value):
    device = device_scope(db, actor, device_id, "operator", lock=True)
    if device.status != "active":
        raise HTTPException(409, "Device is not active")
    fp = fingerprint({"type": value.type, "arguments": value.arguments})
    existing = db.scalar(select(Command).where(Command.device_id == device.id, Command.idempotency_key == value.idempotency_key))
    if existing:
        if existing.fingerprint != fp:
            raise HTTPException(409, "Command key reused with different content")
        return command_json(existing)
    if value.type == "refresh_config":
        for old in db.scalars(select(Command).where(Command.device_id == device.id, Command.type == value.type,
                              Command.state.in_(("queued", "received", "deferred")))):
            old.state, old.result_code, old.updated_at = "cancelled", "superseded", time.time()
    command = Command(device_id=device.id, hotel_id=device.hotel_id, creator_id=actor.id,
                      type=value.type, arguments={}, idempotency_key=value.idempotency_key,
                      fingerprint=fp, expires_at=time.time() + 900)
    db.add(command)
    db.flush()
    audit(db, actor.id, "command_created", command.id, device.hotel_id)
    return command_json(command)


def command_receipt(db, authorization, command_id, value):
    device, _ = device_auth(db, authorization, lock=True)
    command = db.scalar(select(Command).where(Command.id == command_id, Command.device_id == device.id).with_for_update())
    if not command:
        raise HTTPException(404, "Command not found")
    now = time.time()
    if command.expires_at <= now and command.state not in TERMINAL:
        command.state = "reconciliation_required" if command.state == "running" else "expired"
        command.updated_at = now
        db.commit()
        raise HTTPException(409, "Command expired; reconcile before retrying")
    if command.state == value.state and command.result_code == value.result_code:
        return command_json(command)
    if value.state not in COMMAND_TRANSITIONS.get(command.state, set()):
        raise HTTPException(409, "Invalid command transition")
    command.state, command.result_code, command.updated_at = value.state, value.result_code, now
    db.add(CommandReceipt(command_id=command.id, state=value.state, result_code=value.result_code))
    audit(db, device.id, "command_" + value.state, command.id, device.hotel_id)
    return command_json(command)


def rotate_credential(db, settings, authorization, value):
    device, _ = device_auth(db, authorization, lock=True)
    operation = "rotation:" + value.idempotency_key
    existing = db.scalar(select(DeviceCredential).where(DeviceCredential.device_id == device.id,
                         DeviceCredential.operation_id == operation))
    if existing:
        if existing.state != "pending" or existing.revoked_at or not existing.token_cipher or existing.expires_at <= time.time():
            raise HTTPException(409, "Rotation already completed or expired")
        token = cipher(settings).decrypt(existing.token_cipher.encode()).decode().split(":", 1)[1]
        return {"device_id": device.id, "credential_id": existing.id, "device_token": token,
                "expires_at": existing.expires_at}
    pending = db.scalar(select(DeviceCredential).where(DeviceCredential.device_id == device.id,
                        DeviceCredential.state == "pending", DeviceCredential.revoked_at.is_(None),
                        DeviceCredential.expires_at > time.time()))
    if pending:
        raise HTTPException(409, "Another credential rotation is pending")
    token = secrets.token_urlsafe(32)
    credential = DeviceCredential(device_id=device.id, token_hash=digest(token),
        token_cipher=cipher(settings).encrypt((device.id + ":" + token).encode()).decode(),
        operation_id=operation, expires_at=time.time() + 900)
    db.add(credential)
    db.flush()
    audit(db, device.id, "credential_rotation_started", credential.id, device.hotel_id)
    return {"device_id": device.id, "credential_id": credential.id, "device_token": token,
            "expires_at": credential.expires_at}
