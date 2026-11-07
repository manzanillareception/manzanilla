"""Durable, bounded fleet maintenance; no voice/provider calls or external effects."""
from __future__ import annotations

import argparse
import sys
import time
import uuid

from sqlalchemy import delete, select
from sqlalchemy.exc import SQLAlchemyError

from .auth import AuthRateBucket, WebSession
from .db import make_engine, make_session_factory
from .models import Alert, Command, Device, DeviceEvent, Enrollment, RateBucket, WorkerLease, WorkerProgress
from .settings import Settings

JOB_NAME = "fleet_maintenance"


def evaluate_alert(db, device, kind, active, message, now):
    alert = db.scalar(select(Alert).where(Alert.device_id == device.id, Alert.kind == kind,
                      Alert.state.in_(("open", "acknowledged"))).with_for_update())
    if active and not alert:
        db.add(Alert(hotel_id=device.hotel_id, device_id=device.id, kind=kind, message=message))
    elif not active and alert:
        alert.state, alert.resolved_at = "resolved", now


def run_once(settings, session_factory=None):
    owned_engine = None
    if session_factory is None:
        owned_engine = make_engine(settings.database_url)
        session_factory = make_session_factory(owned_engine)
    now = time.time()
    try:
        with session_factory.begin() as db:
            # The initial migration seeds this row. SKIP LOCKED prevents duplicate
            # workers; the row lock and all derived mutations commit together.
            lease = db.scalar(select(WorkerLease).where(WorkerLease.name == JOB_NAME).with_for_update(skip_locked=True))
            if lease is None:
                return {"processed": 0, "claimed": False}
            lease.owner, lease.expires_at = str(uuid.uuid4()), now + 120
            progress = db.get(WorkerProgress, JOB_NAME)
            if progress is None:
                raise RuntimeError("Worker schema is not migrated")
            devices = list(db.scalars(select(Device).where(Device.id > progress.cursor).order_by(Device.id).limit(500)))
            for device in devices:
                live = device.status == "active"
                evaluate_alert(db, device, "device_offline", live and device.last_seen is not None and now - device.last_seen > 300,
                               "Device has not reported for more than five minutes", now)
                evaluate_alert(db, device, "never_reported", live and device.last_seen is None and now - device.created_at > 300,
                               "Enrolled device has not sent its first status report", now)
                evaluate_alert(db, device, "microphone_permission", live and device.health.get("microphone_permission") is False,
                               "Device reports microphone permission unavailable", now)
                free = device.health.get("storage_available_bytes")
                evaluate_alert(db, device, "storage_low", live and free is not None and free < 100_000_000,
                               "Device reports less than 100 MB of available storage", now)
                evaluate_alert(db, device, "update_failed", live and device.update_state == "failed",
                               "Device reports an update failure", now)
            progress.cursor = devices[-1].id if len(devices) == 500 else ""
            progress.processed = len(devices)
            for command in db.scalars(select(Command).where(Command.expires_at <= now,
                                      Command.state.in_(("queued", "received", "deferred", "running"))).with_for_update(skip_locked=True).limit(500)):
                command.state = "reconciliation_required" if command.state == "running" else "expired"
                command.result_code, command.updated_at = "expired", now
            for pairing in db.scalars(select(Enrollment).where(Enrollment.expires_at <= now,
                                      Enrollment.status.in_(("pending", "approved"))).limit(500)):
                pairing.status = "expired"
            # Bounded deletions keep maintenance transactions short; subsequent
            # runs continue pruning. Operational commands/audit are not erased.
            old_events = select(DeviceEvent.device_id, DeviceEvent.id).where(DeviceEvent.received_at < now - 14 * 86400).limit(500)
            for device_id, event_id in db.execute(old_events):
                db.execute(delete(DeviceEvent).where(DeviceEvent.device_id == device_id, DeviceEvent.id == event_id))
            for item in db.scalars(select(WebSession).where(WebSession.expires_at <= now).limit(500)):
                db.delete(item)
            for item in db.scalars(select(AuthRateBucket).where(AuthRateBucket.expires_at <= now).limit(500)):
                db.delete(item)
            for item in db.scalars(select(RateBucket).where(RateBucket.window < int(now) // 600 - 2).limit(500)):
                db.delete(item)
            progress.last_success, lease.expires_at = now, 0
            return {"processed": len(devices), "claimed": True}
    finally:
        if owned_engine:
            owned_engine.dispose()


def healthy(settings):
    engine = make_engine(settings.database_url)
    try:
        with make_session_factory(engine)() as db:
            progress = db.get(WorkerProgress, JOB_NAME)
            return bool(progress and progress.last_success is not None and time.time() - progress.last_success < 120)
    except SQLAlchemyError:
        return False
    finally:
        engine.dispose()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--healthcheck", action="store_true")
    args = parser.parse_args()
    settings = Settings.from_env()
    if args.healthcheck:
        return 0 if healthy(settings) else 1
    while True:
        try:
            result = run_once(settings)
            print("fleet maintenance completed", result, flush=True)
        except (SQLAlchemyError, RuntimeError):
            print("fleet maintenance unavailable", file=sys.stderr, flush=True)
            if args.once:
                return 1
        if args.once:
            return 0
        time.sleep(15)


if __name__ == "__main__":
    raise SystemExit(main())
