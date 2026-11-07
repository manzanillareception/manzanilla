"""Versioned fleet API. Never mounts the legacy cloud/admin/voice application."""
from __future__ import annotations

import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from sqlalchemy import delete, func, select, text
from sqlalchemy.exc import IntegrityError, SQLAlchemyError

from . import models, schemas, service
from .auth import AuthService, IdentityError, WebSession
from .db import make_engine, make_session_factory
from .models import Alert, AuditEvent, Command, ConfigRevision, Device, DeviceCredential, DeviceEvent, Enrollment, Hotel, Membership, User
from .settings import Settings

SCHEMA_VERSION = "0001_control"


def create_app(settings=None, identity_provider=None):
    settings = settings or Settings.from_env()
    engine = make_engine(settings.database_url)
    sessions = make_session_factory(engine)
    auth = AuthService(settings, sessions, identity_provider)

    @asynccontextmanager
    async def lifespan(_):
        yield
        engine.dispose()
        close = getattr(auth.provider, "close", None)
        if close:
            close()

    app = FastAPI(title="Manzanilla Fleet Control", version="2.0.0", lifespan=lifespan,
                  docs_url=None if settings.environment == "production" else "/api/v2/docs",
                  redoc_url=None, openapi_url=None if settings.environment == "production" else "/api/v2/openapi.json")
    app.state.settings, app.state.engine, app.state.sessions, app.state.auth = settings, engine, sessions, auth
    app.include_router(auth.router, prefix="/api/v2")

    @app.middleware("http")
    async def bounds(request, call_next):
        if request.method in ("POST", "PUT", "PATCH"):
            size = 0
            body = []
            async for chunk in request.stream():
                size += len(chunk)
                if size > 262144:
                    return JSONResponse({"detail": "Request exceeds 256 KiB"}, status_code=413)
                body.append(chunk)
            request._body = b"".join(body)
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(_, exc):
        return JSONResponse({"detail": "Invalid request", "errors": [
            {"location": list(e["loc"]), "type": e["type"]} for e in exc.errors()[:20]
        ]}, status_code=422)

    @app.exception_handler(IntegrityError)
    async def constraint_error(_, exc):
        return JSONResponse({"detail": "Conflicting operation; refresh before retrying"}, status_code=409)

    @app.exception_handler(SQLAlchemyError)
    async def database_error(_, exc):
        return JSONResponse({"detail": "Database unavailable"}, status_code=503)

    @app.exception_handler(IdentityError)
    async def identity_error(_, exc):
        return JSONResponse({"detail": exc.detail}, status_code=exc.status)

    def rate(key, maximum=10):
        with sessions() as db:
            service.limit(db, key, maximum)
            db.commit()

    def page(db, query, serialize, limit, offset):
        total = db.scalar(select(func.count()).select_from(query.order_by(None).subquery()))
        items = [serialize(item) for item in db.scalars(query.limit(limit).offset(offset))]
        return {"items": items, "total": total,
                "next_offset": offset + len(items) if offset + len(items) < total else None}

    @app.get("/health/live")
    def live():
        return {"status": "ok"}

    @app.get("/health/ready")
    def ready():
        try:
            with engine.connect() as connection:
                version = connection.scalar(text("SELECT version_num FROM alembic_version"))
                connection.execute(select(User.id).limit(1))
                if version != SCHEMA_VERSION:
                    raise HTTPException(503, "Database migration required")
        except SQLAlchemyError:
            raise HTTPException(503, "Database is not ready")
        return {"status": "ok", "schema_version": SCHEMA_VERSION}

    @app.get("/api/v2/hotels")
    def hotels(actor=Depends(auth.require_user), limit: int = Query(100, ge=1, le=200), offset: int = Query(0, ge=0)):
        with sessions() as db:
            query = select(Hotel).where(Hotel.id.in_(service.visible_hotels(db, actor))).order_by(Hotel.id)
            return page(db, query, lambda h: service.hotel_json(h, service.hotel_scope(db, actor, h.id)[1]), limit, offset)

    @app.post("/api/v2/hotels", status_code=201)
    def create_hotel(value: schemas.HotelCreate, actor=Depends(auth.require_user)):
        with sessions.begin() as db:
            return service.create_hotel(db, actor, value)

    @app.get("/api/v2/hotels/{hotel_id}")
    def hotel(hotel_id: str, actor=Depends(auth.require_user)):
        with sessions() as db:
            h, role = service.hotel_scope(db, actor, hotel_id)
            return service.hotel_json(h, role)

    @app.get("/api/v2/hotels/{hotel_id}/members")
    def members(hotel_id: str, actor=Depends(auth.require_user), limit: int = Query(100, ge=1, le=200), offset: int = Query(0, ge=0)):
        with sessions() as db:
            service.hotel_scope(db, actor, hotel_id, "manager")
            query = select(Membership).where(Membership.hotel_id == hotel_id).order_by(Membership.user_id)
            return page(db, query, lambda m: {"user_id": m.user_id, "email": db.get(User, m.user_id).email, "role": m.role}, limit, offset)

    @app.get("/api/v2/users")
    def users(actor=Depends(auth.require_user), limit: int = Query(100, ge=1, le=200), offset: int = Query(0, ge=0)):
        with sessions() as db:
            if not service.current_actor(db, actor).platform_owner:
                raise HTTPException(403, "Owner access required")
            return page(db, select(User).order_by(User.id), lambda u: {
                "id": u.id, "email": u.email, "enabled": u.enabled, "platform_owner": u.platform_owner}, limit, offset)

    @app.patch("/api/v2/users/{user_id}")
    def change_user(user_id: str, value: schemas.UserChange, actor=Depends(auth.require_user)):
        with sessions.begin() as db:
            # Serialize owner changes so two owners cannot disable each other
            # concurrently and leave the platform with no accessible owner.
            owners = list(db.scalars(select(User).where(User.platform_owner.is_(True)).order_by(User.id)
                                    .with_for_update().execution_options(populate_existing=True)))
            if not service.current_actor(db, actor).platform_owner:
                raise HTTPException(403, "Owner access required")
            target = db.get(User, user_id)
            if not target:
                raise HTTPException(404, "User not found")
            if not value.enabled and (user_id == actor.id or target.platform_owner and sum(u.enabled for u in owners) <= 1):
                raise HTTPException(409, "Cannot disable this owner account")
            target.enabled = value.enabled
            if not value.enabled:
                db.execute(delete(WebSession).where(WebSession.subject == user_id))
            service.audit(db, actor.id, "user_enabled" if value.enabled else "user_disabled", target.id)
            return {"id": target.id, "email": target.email, "enabled": target.enabled, "platform_owner": target.platform_owner}

    @app.patch("/api/v2/hotels/{hotel_id}/members/{user_id}")
    def change_member(hotel_id: str, user_id: str, value: schemas.MemberChange, actor=Depends(auth.require_user)):
        with sessions.begin() as db:
            service.hotel_scope(db, actor, hotel_id, "manager")
            membership = db.scalar(select(Membership).where(Membership.user_id == user_id,
                                   Membership.hotel_id == hotel_id).with_for_update())
            if not membership:
                raise HTTPException(404, "Membership not found")
            membership.role = value.role
            service.audit(db, actor.id, "member_role_changed", user_id, hotel_id)
            return {"user_id": user_id, "hotel_id": hotel_id, "role": value.role}

    @app.post("/api/v2/hotels/{hotel_id}/invitations", status_code=201)
    def invite(hotel_id: str, value: schemas.Invitation, actor=Depends(auth.require_user)):
        # Permission is checked before invoking the external identity provider.
        with sessions() as db:
            service.hotel_scope(db, actor, hotel_id, "manager")
            candidates = list(db.scalars(select(User).where(func.lower(User.email) == value.email)))
            if len(candidates) > 1:
                raise HTTPException(409, "Ambiguous local identity; review account mapping")
            candidate = candidates[0] if candidates else None
            if candidate:
                existing = db.get(Membership, (candidate.id, hotel_id))
                if existing:
                    if existing.role != value.role:
                        raise HTTPException(409, "User already has a different role")
                    return {"status": "assigned", "user_id": candidate.id, "hotel_id": hotel_id, "role": value.role}
        rate("invite:" + actor.id, 20)
        subject = (auth.resolve_existing_user(candidate.id, value.email) if candidate else
                   auth.invite(value.email, settings.public_origin + "/"))
        with sessions.begin() as db:
            service.hotel_scope(db, actor, hotel_id, "manager")
            user = db.get(User, subject)
            if not user:
                db.add(User(id=subject, email=value.email, enabled=True, platform_owner=False))
                db.flush()
            elif user.email.lower() != value.email:
                raise HTTPException(409, "Invitation identity mismatch")
            existing = db.get(Membership, (subject, hotel_id))
            if existing and existing.role != value.role:
                raise HTTPException(409, "User already has a different role")
            if not existing:
                db.add(Membership(user_id=subject, hotel_id=hotel_id, role=value.role))
            service.audit(db, actor.id, "member_invited", subject, hotel_id)
        return {"status": "assigned" if candidate else "invited", "user_id": subject, "hotel_id": hotel_id, "role": value.role}

    @app.delete("/api/v2/hotels/{hotel_id}/members/{user_id}")
    def remove_member(hotel_id: str, user_id: str, actor=Depends(auth.require_user)):
        with sessions.begin() as db:
            service.hotel_scope(db, actor, hotel_id, "manager")
            member = db.get(Membership, (user_id, hotel_id))
            if not member:
                raise HTTPException(404, "Membership not found")
            db.delete(member)
            service.audit(db, actor.id, "member_removed", user_id, hotel_id)
        return {"status": "removed"}

    @app.get("/api/v2/devices")
    def devices(hotel_id: str | None = None, actor=Depends(auth.require_user), limit: int = Query(100, ge=1, le=200), offset: int = Query(0, ge=0)):
        with sessions() as db:
            query = select(Device).where(Device.hotel_id.in_(service.visible_hotels(db, actor)))
            if hotel_id:
                service.hotel_scope(db, actor, hotel_id)
                query = query.where(Device.hotel_id == hotel_id)
            return page(db, query.order_by(Device.id), lambda d: service.device_json(db, d), limit, offset)

    @app.get("/api/v2/devices/{device_id}")
    def device(device_id: str, actor=Depends(auth.require_user)):
        with sessions() as db:
            d = service.device_scope(db, actor, device_id)
            result = service.device_json(db, d)
            result["recent_events"] = [{"id": e.id, "type": e.type, "occurred_at": e.occurred_at,
                                        "received_at": e.received_at, "metrics": e.metrics}
                                       for e in db.scalars(select(DeviceEvent).where(DeviceEvent.device_id == d.id).order_by(DeviceEvent.received_at.desc(), DeviceEvent.id).limit(50))]
            result["commands"] = [service.command_json(c) for c in db.scalars(select(Command).where(Command.device_id == d.id).order_by(Command.created_at.desc()).limit(50))]
            result["telemetry_summary"] = service.telemetry_summary(db, d.id)
            return result

    @app.post("/api/v2/devices/{device_id}/revoke")
    def revoke(device_id: str, actor=Depends(auth.require_user)):
        with sessions.begin() as db:
            device = service.device_scope(db, actor, device_id, "manager", lock=True)
            device.status = "revoked"
            for credential in db.scalars(select(DeviceCredential).where(DeviceCredential.device_id == device_id)):
                credential.revoked_at, credential.token_cipher = time.time(), None
            for command in db.scalars(select(Command).where(Command.device_id == device_id, Command.state.not_in(service.TERMINAL))):
                command.state, command.result_code = "cancelled", "device_revoked"
            service.audit(db, actor.id, "device_revoked", device_id, device.hotel_id)
            return {"status": "revoked", "device_id": device_id}

    @app.post("/api/v2/enrollment/start", status_code=201)
    def start_enrollment(value: schemas.PairStart, request: Request):
        host = request.client.host if request.client else "unknown"
        rate("pair-ip:" + host, 30)
        rate("pair-install:" + value.installation_id, 5)
        with sessions.begin() as db:
            return service.start_enrollment(db, value, host)

    @app.post("/api/v2/enrollment/{request_id}/poll")
    def poll_enrollment(request_id: str, value: schemas.PairPoll, request: Request):
        host = request.client.host if request.client else "unknown"
        rate("pair-poll:" + host, 180)
        with sessions.begin() as db:
            return service.poll_enrollment(db, settings, request_id, value.poll_token, host)

    @app.get("/api/v2/enrollments")
    def enrollments(actor=Depends(auth.require_user), limit: int = Query(100, ge=1, le=200), offset: int = Query(0, ge=0)):
        with sessions() as db:
            user = service.current_actor(db, actor)
            query = select(Enrollment)
            if not user.platform_owner:
                query = query.where(Enrollment.device_id.in_(select(Device.id).where(Device.hotel_id.in_(service.visible_hotels(db, actor)))))
            return page(db, query.order_by(Enrollment.created_at.desc(), Enrollment.id),
                        lambda e: {"id": e.id, "request_id": e.id, "user_code": e.user_code,
                                   "device_name": e.device_name, "status": "expired" if e.expires_at <= time.time() and e.status in ("pending", "approved") else e.status,
                                   "expires_at": e.expires_at}, limit, offset)

    @app.post("/api/v2/enrollments/{request_id}/approve")
    def approve(request_id: str, value: schemas.PairApprove, actor=Depends(auth.require_user)):
        rate("pair-approve:" + actor.id, 20)
        with sessions.begin() as db:
            return service.approve_enrollment(db, settings, actor, request_id, value)

    @app.post("/api/v2/enrollments/approve")
    def approve_code(value: schemas.PairCodeApprove, actor=Depends(auth.require_user)):
        rate("pair-approve:" + actor.id, 20)
        with sessions.begin() as db:
            service.hotel_scope(db, actor, value.hotel_id, "manager")
            pairing = db.scalar(select(Enrollment).where(Enrollment.user_code == value.user_code.upper(),
                                Enrollment.expires_at > time.time(), Enrollment.status.in_(("pending", "approved"))))
            if not pairing:
                raise HTTPException(404, "Pairing expired or not found")
            return service.approve_enrollment(db, settings, actor, pairing.id, value)

    @app.post("/api/v2/device/activate")
    def activate(authorization: str = Header(default="")):
        with sessions.begin() as db:
            return service.activate(db, authorization)

    @app.post("/api/v2/device/credentials/rotate")
    def rotate(value: schemas.Rotation, authorization: str = Header(default="")):
        with sessions.begin() as db:
            return service.rotate_credential(db, settings, authorization, value)

    @app.post("/api/v2/device/heartbeat")
    def heartbeat(value: schemas.Heartbeat, authorization: str = Header(default="")):
        with sessions.begin() as db:
            return service.heartbeat(db, authorization, value)

    @app.post("/api/v2/device/events")
    def events(value: schemas.EventsInput, authorization: str = Header(default="")):
        with sessions.begin() as db:
            return service.ingest_events(db, authorization, value)

    @app.get("/api/v2/hotels/{hotel_id}/config")
    def config(hotel_id: str, actor=Depends(auth.require_user)):
        with sessions() as db:
            hotel, _ = service.hotel_scope(db, actor, hotel_id)
            return service.get_config(db, hotel)

    @app.put("/api/v2/hotels/{hotel_id}/config")
    def save_config(hotel_id: str, value: schemas.ConfigChange, actor=Depends(auth.require_user)):
        with sessions.begin() as db:
            return service.change_config(db, actor, hotel_id, value)

    @app.get("/api/v2/device/config")
    def device_config(authorization: str = Header(default="")):
        with sessions() as db:
            device, _ = service.device_auth(db, authorization)
            return service.get_config(db, db.get(Hotel, device.hotel_id))

    @app.post("/api/v2/device/config/ack")
    def ack_config(value: schemas.ConfigAck, authorization: str = Header(default="")):
        with sessions.begin() as db:
            return service.ack_config(db, authorization, value)

    @app.post("/api/v2/devices/{device_id}/commands", status_code=201)
    def create_command(device_id: str, value: schemas.CommandCreate, actor=Depends(auth.require_user)):
        with sessions.begin() as db:
            return service.create_command(db, actor, device_id, value)

    @app.get("/api/v2/devices/{device_id}/commands")
    def commands(device_id: str, actor=Depends(auth.require_user), limit: int = Query(100, ge=1, le=200), offset: int = Query(0, ge=0)):
        with sessions() as db:
            service.device_scope(db, actor, device_id)
            return page(db, select(Command).where(Command.device_id == device_id).order_by(Command.created_at.desc(), Command.id), service.command_json, limit, offset)

    @app.get("/api/v2/device/commands")
    def pending_commands(authorization: str = Header(default="")):
        with sessions.begin() as db:
            device, _ = service.device_auth(db, authorization, lock=True)
            items = []
            for command in db.scalars(select(Command).where(Command.device_id == device.id, Command.state.not_in(service.TERMINAL)).order_by(Command.created_at, Command.id).limit(100)):
                if command.expires_at <= time.time():
                    command.state = "reconciliation_required" if command.state == "running" else "expired"
                    continue
                # Native command DTO omits internal actor/device/idempotency metadata.
                item = service.command_json(command)
                item.pop("device_id")
                items.append(item)
            return {"items": items}

    @app.post("/api/v2/device/commands/{command_id}/receipt")
    def receipt(command_id: str, value: schemas.CommandResult, authorization: str = Header(default="")):
        with sessions.begin() as db:
            return service.command_receipt(db, authorization, command_id, value)

    @app.get("/api/v2/alerts")
    def alerts(actor=Depends(auth.require_user), limit: int = Query(100, ge=1, le=200), offset: int = Query(0, ge=0)):
        with sessions() as db:
            query = select(Alert).where(Alert.hotel_id.in_(service.visible_hotels(db, actor))).order_by(Alert.created_at.desc(), Alert.id)
            return page(db, query, lambda a: {"id": a.id, "hotel_id": a.hotel_id, "device_id": a.device_id, "kind": a.kind,
                        "state": a.state, "message": a.message, "created_at": a.created_at}, limit, offset)

    @app.post("/api/v2/alerts/{alert_id}/acknowledge")
    def acknowledge(alert_id: str, actor=Depends(auth.require_user)):
        with sessions.begin() as db:
            alert = db.scalar(select(Alert).where(Alert.id == alert_id).with_for_update()
                              .execution_options(populate_existing=True))
            if not alert:
                raise HTTPException(404, "Alert not found")
            service.hotel_scope(db, actor, alert.hotel_id, "operator")
            if alert.state == "open":
                alert.state, alert.acknowledged_at = "acknowledged", time.time()
                service.audit(db, actor.id, "alert_acknowledged", alert.id, alert.hotel_id)
            return {"status": alert.state}

    @app.get("/api/v2/audit")
    def audit(actor=Depends(auth.require_user), limit: int = Query(100, ge=1, le=200), offset: int = Query(0, ge=0)):
        with sessions() as db:
            user = service.current_actor(db, actor)
            query = select(AuditEvent)
            if not user.platform_owner:
                manager_hotels = select(Membership.hotel_id).where(Membership.user_id == user.id, Membership.role == "manager")
                query = query.where(AuditEvent.hotel_id.in_(manager_hotels))
            return page(db, query.order_by(AuditEvent.created_at.desc(), AuditEvent.id), lambda a: {
                "id": a.id, "hotel_id": a.hotel_id, "actor_id": a.actor_id,
                "action": a.action, "target_id": a.target_id, "created_at": a.created_at}, limit, offset)

    return app


app = create_app()
