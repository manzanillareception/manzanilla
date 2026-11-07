from __future__ import annotations

import json
import math
import re
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, StrictBool, field_validator, model_validator


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)


class HotelCreate(Strict):
    name: str = Field(min_length=1, max_length=100)
    timezone: str = Field(min_length=1, max_length=80)

    @field_validator("timezone")
    @classmethod
    def timezone_valid(cls, value):
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError):
            raise ValueError("Unknown IANA timezone")
        return value


class Invitation(Strict):
    email: str = Field(min_length=3, max_length=320)
    role: Literal["manager", "operator", "viewer"]

    @field_validator("email")
    @classmethod
    def email_valid(cls, value):
        if not re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", value):
            raise ValueError("Invalid email")
        return value.lower()


class UserChange(Strict):
    enabled: StrictBool


class MemberChange(Strict):
    role: Literal["manager", "operator", "viewer"]


class PairStart(Strict):
    installation_id: str = Field(min_length=16, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
    device_name: str = Field(min_length=1, max_length=100)
    poll_token: str | None = Field(default=None, min_length=32, max_length=200, pattern=r"^[A-Za-z0-9_-]+$")


class PairPoll(Strict):
    poll_token: str = Field(min_length=32, max_length=200)


class PairApprove(Strict):
    hotel_id: str = Field(min_length=1, max_length=36)
    user_code: str | None = Field(default=None, min_length=8, max_length=12)


class PairCodeApprove(Strict):
    hotel_id: str = Field(min_length=1, max_length=36)
    user_code: str = Field(min_length=8, max_length=12, pattern=r"^[A-Za-z0-9]+$")


BOOLEAN_METRICS = {"microphone_permission", "audio_released", "charging", "success"}
NUMERIC_METRICS = {
    "connection_ready_ms", "native_audio_start_ms", "max_upload_queue_audio_ms",
    "max_local_receive_dispatch_ms", "stream_start_to_first_output_event_ms",
    "pcm_arrival_to_first_write_ms", "pcm_arrival_to_playback_head_ms", "queued_audio_ms",
    "interruption_flush_ms", "session_duration_ms", "tool_duration_ms", "committed_task_count",
    "storage_available_bytes", "battery_percent",
}


def validate_metrics(value, *, health=False):
    if not isinstance(value, dict) or len(value) > 24:
        raise ValueError("Invalid metric object")
    for key, item in value.items():
        if health and key == "network_type":
            if item not in ("offline", "wifi", "cellular", "ethernet", "unknown"):
                raise ValueError("Invalid network type")
        elif key in BOOLEAN_METRICS:
            if not isinstance(item, bool):
                raise ValueError("Boolean metric required")
        elif key in NUMERIC_METRICS:
            if isinstance(item, bool) or not isinstance(item, (int, float)) or not math.isfinite(item):
                raise ValueError("Finite numeric metric required")
            maximum = 100 if key == "battery_percent" else 2**63 - 1 if key == "storage_available_bytes" else 86_400_000
            if item < 0 or item > maximum:
                raise ValueError("Metric out of range")
        else:
            raise ValueError("Unknown metric; private text is not telemetry")
    return value


class Heartbeat(Strict):
    schema_version: Literal[1]
    boot_id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
    boot_generation: int = Field(ge=1, le=2**53 - 1, strict=True)
    sequence: int = Field(ge=0, le=2**53 - 1)
    app_version: str = Field(min_length=1, max_length=40, pattern=r"^[A-Za-z0-9._+-]+$")
    version_code: int = Field(ge=0, le=2**31 - 1)
    foreground: StrictBool
    mode: Literal["idle", "translation", "reception"]
    mic_state: Literal["off", "on", "stopping", "unknown"]
    config_version: int = Field(ge=0, le=2**31 - 1)
    update_state: Literal["idle", "checking", "current", "ready", "progress", "confirm", "failed", "installed", "waiting", "validating", "installing", "confirmation_required", "unknown"]
    health: dict = Field(default_factory=dict)

    @field_validator("health")
    @classmethod
    def allowed_health(cls, value):
        return validate_metrics(value, health=True)


class EventInput(Strict):
    id: str = Field(min_length=1, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
    type: Literal["app_start", "voice_timing", "diagnostics", "audio_error", "session_started", "session_ended", "tool_outcome"]
    occurred_at: float = Field(ge=0, allow_inf_nan=False)
    metrics: dict = Field(default_factory=dict)

    @field_validator("metrics")
    @classmethod
    def allowed_metrics(cls, value):
        return validate_metrics(value)


class EventsInput(Strict):
    events: list[EventInput] = Field(min_length=1, max_length=100)


class PublicPlace(Strict):
    name: str = Field(min_length=1, max_length=160)
    category: str = Field(default="", max_length=80)
    address: str = Field(default="", max_length=500)
    summary: str = Field(default="", max_length=1500)
    source: str = Field(min_length=1, max_length=500)
    reviewed_at_ms: int = Field(gt=0)
    latitude: float | None = Field(default=None, ge=-90, le=90, allow_inf_nan=False)
    longitude: float | None = Field(default=None, ge=-180, le=180, allow_inf_nan=False)

    @model_validator(mode="after")
    def coordinates(self):
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("Coordinates require latitude and longitude")
        return self


class PublicConfig(Strict):
    name: str = Field(default="", max_length=160)
    address: str = Field(default="", max_length=500)
    area: str = Field(default="", max_length=250)
    opening_hours: str = Field(default="", max_length=1000)
    facts: str = Field(default="", max_length=12000)
    latitude: float | None = Field(default=None, ge=-90, le=90, allow_inf_nan=False)
    longitude: float | None = Field(default=None, ge=-180, le=180, allow_inf_nan=False)
    local_pack: list[PublicPlace] = Field(default_factory=list, max_length=100)
    availability: str = Field(default="", max_length=1000)
    availability_updated_at_ms: int = Field(default=0, ge=0)

    @model_validator(mode="after")
    def bounded(self):
        if (self.latitude is None) != (self.longitude is None):
            raise ValueError("Coordinates require latitude and longitude")
        if len(json.dumps(self.model_dump(exclude_none=True), ensure_ascii=False).encode()) > 65536:
            raise ValueError("Configuration exceeds 64 KiB")
        return self


class ConfigChange(Strict):
    expected_version: int = Field(ge=0)
    config: PublicConfig


class ConfigAck(Strict):
    version: int = Field(ge=0)


class CommandCreate(Strict):
    type: Literal["refresh_config", "check_update", "diagnostics"]
    arguments: dict = Field(default_factory=dict)
    idempotency_key: str = Field(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")

    @field_validator("arguments")
    @classmethod
    def no_remote_arguments(cls, value):
        if value:
            raise ValueError("This command does not accept remote arguments")
        return value


class CommandResult(Strict):
    state: Literal["received", "deferred", "running", "succeeded", "failed", "reconciliation_required"]
    result_code: str | None = Field(default=None, max_length=64, pattern=r"^[a-z][a-z0-9_]{0,63}$")


class Rotation(Strict):
    idempotency_key: str = Field(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")
