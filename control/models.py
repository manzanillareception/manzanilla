from __future__ import annotations

import time
import uuid

from sqlalchemy import BigInteger, Boolean, Float, ForeignKey, Integer, JSON, String, UniqueConstraint, Index
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


def uid() -> str:
    return str(uuid.uuid4())


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    email: Mapped[str] = mapped_column(String(320), nullable=False)
    enabled: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    platform_owner: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)


class Organization(Base):
    __tablename__ = "organizations"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)


class Hotel(Base):
    __tablename__ = "hotels"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    organization_id: Mapped[str] = mapped_column(ForeignKey("organizations.id"), index=True)
    name: Mapped[str] = mapped_column(String(100))
    timezone: Mapped[str] = mapped_column(String(80))
    config_version: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)


class Membership(Base):
    __tablename__ = "memberships"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    hotel_id: Mapped[str] = mapped_column(ForeignKey("hotels.id"), primary_key=True)
    role: Mapped[str] = mapped_column(String(16), nullable=False)


class Device(Base):
    __tablename__ = "devices"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    hotel_id: Mapped[str] = mapped_column(ForeignKey("hotels.id"), index=True)
    installation_id: Mapped[str] = mapped_column(String(128), unique=True)
    name: Mapped[str] = mapped_column(String(100))
    status: Mapped[str] = mapped_column(String(16), default="pending", nullable=False)
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)
    last_seen: Mapped[float | None] = mapped_column(Float, nullable=True)
    boot_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    boot_generation: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    sequence: Mapped[int] = mapped_column(BigInteger, default=-1, nullable=False)
    app_version: Mapped[str | None] = mapped_column(String(40), nullable=True)
    version_code: Mapped[int | None] = mapped_column(Integer, nullable=True)
    foreground: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    mode: Mapped[str] = mapped_column(String(20), default="idle", nullable=False)
    mic_state: Mapped[str] = mapped_column(String(20), default="unknown", nullable=False)
    update_state: Mapped[str] = mapped_column(String(30), default="unknown", nullable=False)
    config_version: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    health: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)


class DeviceCredential(Base):
    __tablename__ = "device_credentials"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    state: Mapped[str] = mapped_column(String(16), default="pending", nullable=False)
    token_cipher: Mapped[str | None] = mapped_column(String(2048), nullable=True)
    operation_id: Mapped[str] = mapped_column(String(128))
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)
    expires_at: Mapped[float | None] = mapped_column(Float, nullable=True)
    revoked_at: Mapped[float | None] = mapped_column(Float, nullable=True)
    __table_args__ = (UniqueConstraint("device_id", "operation_id"),)


class Enrollment(Base):
    __tablename__ = "enrollments"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    installation_id: Mapped[str] = mapped_column(String(128), index=True)
    device_name: Mapped[str] = mapped_column(String(100))
    user_code: Mapped[str] = mapped_column(String(12), unique=True)
    poll_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    status: Mapped[str] = mapped_column(String(16), default="pending", nullable=False)
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)
    expires_at: Mapped[float] = mapped_column(Float, nullable=False)
    device_id: Mapped[str | None] = mapped_column(ForeignKey("devices.id"), nullable=True)
    approved_by: Mapped[str | None] = mapped_column(ForeignKey("users.id"), nullable=True)


Index("uq_enrollments_active_installation", Enrollment.installation_id, unique=True,
      postgresql_where=Enrollment.status.in_(("pending", "approved")),
      sqlite_where=Enrollment.status.in_(("pending", "approved")))


class RateBucket(Base):
    __tablename__ = "rate_buckets"
    key: Mapped[str] = mapped_column(String(128), primary_key=True)
    window: Mapped[int] = mapped_column(Integer, nullable=False)
    count: Mapped[int] = mapped_column(Integer, nullable=False)


class DeviceEvent(Base):
    __tablename__ = "device_events"
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), primary_key=True)
    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    type: Mapped[str] = mapped_column(String(40), nullable=False)
    occurred_at: Mapped[float] = mapped_column(Float, nullable=False)
    received_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)
    metrics: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    fingerprint: Mapped[str] = mapped_column(String(64), nullable=False)
    __table_args__ = (Index("ix_device_events_received_at", "received_at"),)


class DeviceBoot(Base):
    __tablename__ = "device_boots"
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), primary_key=True)
    boot_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    first_seen: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)


class ConfigRevision(Base):
    __tablename__ = "config_revisions"
    hotel_id: Mapped[str] = mapped_column(ForeignKey("hotels.id"), primary_key=True)
    version: Mapped[int] = mapped_column(Integer, primary_key=True)
    config: Mapped[dict] = mapped_column(JSON, nullable=False)
    author_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)


class Command(Base):
    __tablename__ = "commands"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    hotel_id: Mapped[str] = mapped_column(ForeignKey("hotels.id"), index=True)
    creator_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    type: Mapped[str] = mapped_column(String(30), nullable=False)
    arguments: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(128))
    fingerprint: Mapped[str] = mapped_column(String(64))
    state: Mapped[str] = mapped_column(String(30), default="queued", nullable=False)
    result_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)
    expires_at: Mapped[float] = mapped_column(Float, nullable=False)
    updated_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)
    __table_args__ = (UniqueConstraint("device_id", "idempotency_key"),)


class CommandReceipt(Base):
    __tablename__ = "command_receipts"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    command_id: Mapped[str] = mapped_column(ForeignKey("commands.id"), index=True)
    state: Mapped[str] = mapped_column(String(30))
    result_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)


class Alert(Base):
    __tablename__ = "alerts"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    hotel_id: Mapped[str] = mapped_column(ForeignKey("hotels.id"), index=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    kind: Mapped[str] = mapped_column(String(40))
    state: Mapped[str] = mapped_column(String(20), default="open", nullable=False)
    message: Mapped[str] = mapped_column(String(200))
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False)
    acknowledged_at: Mapped[float | None] = mapped_column(Float, nullable=True)
    resolved_at: Mapped[float | None] = mapped_column(Float, nullable=True)


class AuditEvent(Base):
    __tablename__ = "audit_events"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    hotel_id: Mapped[str | None] = mapped_column(ForeignKey("hotels.id"), nullable=True, index=True)
    actor_id: Mapped[str] = mapped_column(String(128))
    action: Mapped[str] = mapped_column(String(60))
    target_id: Mapped[str] = mapped_column(String(128))
    created_at: Mapped[float] = mapped_column(Float, default=time.time, nullable=False, index=True)


class WorkerLease(Base):
    __tablename__ = "worker_leases"
    name: Mapped[str] = mapped_column(String(50), primary_key=True)
    owner: Mapped[str] = mapped_column(String(36))
    expires_at: Mapped[float] = mapped_column(Float)


class WorkerProgress(Base):
    __tablename__ = "worker_progress"
    name: Mapped[str] = mapped_column(String(50), primary_key=True)
    last_success: Mapped[float | None] = mapped_column(Float, nullable=True)
    cursor: Mapped[str] = mapped_column(String(36), default="", nullable=False)
    processed: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
