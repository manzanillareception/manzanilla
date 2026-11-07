#!/usr/bin/env python3
"""Bounded, opt-in CI benchmark of the exported native GPT-Live contract.

Only two fixed synthetic clips are allowed. No key, transcript, audio, prompt or
provider response is printed or saved. This measures CI network/provider delivery,
not Android capture, playout, acoustic latency or translation quality.

Official contracts:
https://developers.openai.com/api/docs/guides/voice-websockets
https://developers.openai.com/api/docs/guides/live-conversations
https://developers.openai.com/api/docs/guides/text-to-speech
"""
from __future__ import annotations

import argparse
import array
import asyncio
import base64
import binascii
import contextlib
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable

HOST = "api.openai.com"
ENDPOINT = "wss://api.openai.com/v1/live/sessions"
MODEL = "gpt-live-1"
TTS_MODEL = "gpt-4o-mini-tts"
TTS_RATE = 24_000
RATE = 16_000
FRAME_SECONDS = 0.02
FRAME_BYTES = RATE * 2 // 50
RMS_THRESHOLD = 160  # About -46 dBFS, evaluated over 10ms windows.
MAX_TTS_BYTES = TTS_RATE * 2 * 8
MAX_EVENT_CHARS = 1_000_000
MAX_DELTA_BYTES = 256_000
MAX_EVENTS = 4096


@dataclass(frozen=True)
class Case:
    id: str
    source: str
    target: str
    text: str


CASES = (
    Case("en_es", "en", "es", "The taxi will arrive at nine. Please wait inside."),
    Case("es_en", "es", "en", "La farmacia está a dos calles. Cierra a las diez."),
)


@dataclass(frozen=True)
class Limits:
    session_seconds: float = 20.0
    ready_seconds: float = 5.0
    quiet_seconds: float = 1.0
    drain_seconds: float = 2.0
    max_upload_lag_seconds: float = 0.1
    send_timeout_seconds: float = 0.3


class BenchmarkError(Exception):
    def __init__(self, code: str, http_status: int | None = None):
        self.code = code
        self.http_status = http_status


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value: str | bytes) -> str:
    return hashlib.sha256(value.encode() if isinstance(value, str) else value).hexdigest()


def load_contract(path: Path) -> dict[str, Any]:
    if path.stat().st_size > 65_536:
        raise BenchmarkError("contract_too_large")
    try:
        contract = json.loads(path.read_text())
        expected = {"schema_version": 1, "endpoint": ENDPOINT, "model": MODEL,
                    "input_sample_rate": RATE, "output_sample_rate": RATE,
                    "encoding": "pcm_s16le_mono", "input_audio_event": "session.input_audio.append",
                    "ready_event": "session.started", "output_audio_event": "session.output_audio.delta",
                    "output_transcript_event": "session.output_transcript.delta",
                    "close_event": "session.close", "closed_event": "session.closed"}
        if not isinstance(contract, dict) or any(contract.get(key) != value for key, value in expected.items()):
            raise BenchmarkError("unsupported_native_contract")
        start = contract["session_start"]
        session = start["session"]
        if (set(start) != {"type", "session"} or start["type"] != "session.start" or
                set(session) != {"model", "store", "instructions", "audio", "delegation"} or
                session["model"] != MODEL or session["store"] is not False or
                session["delegation"] != {"type": "client"} or
                session["audio"]["format"] != {"type": "audio/pcm", "rate": RATE} or
                not isinstance(session["instructions"], str) or not session["instructions"].strip()):
            raise BenchmarkError("unsafe_native_contract")
        return contract
    except BenchmarkError:
        raise
    except (ValueError, KeyError, TypeError):
        raise BenchmarkError("invalid_native_contract") from None


def samples(pcm: bytes) -> array.array:
    if len(pcm) % 2:
        raise BenchmarkError("invalid_pcm_alignment")
    result = array.array("h", pcm)
    if sys.byteorder != "little":
        result.byteswap()
    return result


def active_bounds(pcm: bytes, rate: int = RATE) -> tuple[int, int] | None:
    values = samples(pcm)
    window = rate // 100
    first = last = None
    for index in range(0, len(values), window):
        frame = values[index:index + window]
        if frame and sum(value * value for value in frame) >= RMS_THRESHOLD ** 2 * len(frame):
            if first is None:
                first = index
            last = index + len(frame)
    return None if first is None else (first, last)


def resample(pcm24: bytes) -> bytes:
    if not shutil.which("ffmpeg"):
        raise BenchmarkError("ffmpeg_required")
    try:
        converted = subprocess.run(["ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
            "-f", "s16le", "-ar", str(TTS_RATE), "-ac", "1", "-i", "pipe:0",
            "-f", "s16le", "-ar", str(RATE), "-ac", "1", "pipe:1"],
            input=pcm24, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        raise BenchmarkError("audio_resampling_failed") from None
    if not converted or len(converted) > RATE * 2 * 8 or active_bounds(converted) is None:
        raise BenchmarkError("invalid_resampled_audio")
    return converted


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def generate_clip(key: str, case: Case) -> bytes:
    if case not in CASES:
        raise BenchmarkError("unapproved_synthetic_case")
    body = {"model": TTS_MODEL, "voice": "coral", "input": case.text,
            "instructions": "Speak clearly and naturally in " + ("English." if case.source == "en" else "Spanish."),
            "response_format": "pcm", "speed": 1.0}
    request = urllib.request.Request("https://api.openai.com/v1/audio/speech", method="POST",
        data=canonical(body).encode(), headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
    try:
        with urllib.request.build_opener(NoRedirects(), urllib.request.HTTPSHandler(context=ssl.create_default_context())).open(request, timeout=12) as response:
            audio = response.read(MAX_TTS_BYTES + 1)
            if len(audio) < 480 or len(audio) > MAX_TTS_BYTES or len(audio) % 2 or audio.startswith(b"RIFF"):
                raise BenchmarkError("invalid_tts_audio")
            return resample(audio)
    except urllib.error.HTTPError as error:
        raise BenchmarkError("tts_http_error", error.code) from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise BenchmarkError("tts_transport_error") from None


class Measurements:
    def __init__(self, clip: bytes, began: float):
        bounds = active_bounds(clip)
        if bounds is None or len(clip) > RATE * 2 * 8:
            raise BenchmarkError("invalid_source_clip")
        self.clip = bytes(RATE * 2 * 3 // 10) + clip  # 300ms initial microphone silence.
        self.speech_start_seconds = 0.3 + bounds[0] / RATE
        self.speech_end_seconds = 0.3 + bounds[1] / RATE
        self.began = began
        self.anchor: float | None = None
        self.ready_at: float | None = None
        self.first_output_at: float | None = None
        self.last_active_at: float | None = None
        self.last_transcript_at: float | None = None
        self.input_complete = False
        self.input_frames = self.output_bytes = self.output_events = 0
        self.max_upload_lag = 0.0
        self.transcript = ""
        self.transcript_events = 0
        self.transcript_start_ms: int | None = None
        self.transcript_end_ms: int | None = None
        self.output_arrivals: list[dict[str, Any]] = []
        self.closed = False
        self.final_usage_seconds: float | None = None

    def output(self, pcm: bytes, now: float) -> None:
        self.output_bytes += len(pcm)
        self.output_events += 1
        if self.output_bytes > RATE * 2 * 20:
            raise BenchmarkError("output_audio_limit")
        active = active_bounds(pcm)
        if active is not None:
            if self.first_output_at is None:
                self.first_output_at = now
            self.last_active_at = now
        if len(self.output_arrivals) < 128:
            self.output_arrivals.append({"received_ms_from_input_start":
                round((now - self.anchor) * 1000, 3) if self.anchor is not None else None,
                "pcm_bytes": len(pcm), "non_silent": active is not None})

    def transcript_delta(self, event: dict[str, Any], now: float) -> None:
        text = event.get("delta")
        if not isinstance(text, str) or len(self.transcript) + len(text) > 16_384:
            raise BenchmarkError("invalid_transcript_event")
        self.transcript += text
        self.transcript_events += 1
        self.last_transcript_at = now
        for key, attribute in (("start_ms", "transcript_start_ms"), ("end_ms", "transcript_end_ms")):
            value = event.get(key)
            if value is not None:
                if type(value) is not int or value < 0:
                    raise BenchmarkError("invalid_transcript_timestamp")
                previous = getattr(self, attribute)
                setattr(self, attribute, value if previous is None else
                        (min(previous, value) if key == "start_ms" else max(previous, value)))

    def report(self, case: Case, now: float, error: BenchmarkError | None = None) -> dict[str, Any]:
        latency = None
        if self.anchor is not None and self.first_output_at is not None:
            # Keep negative values: a simultaneous interpreter can begin before source speech ends.
            latency = (self.first_output_at - self.anchor - self.speech_end_seconds) * 1000
        if error is None and (self.first_output_at is None or not self.transcript.strip()):
            error = BenchmarkError("no_output_audio_or_transcript")
        if error is None and not self.input_complete:
            error = BenchmarkError("session_ended_before_source_complete")
        premature = (self.first_output_at is not None and self.anchor is not None and
                     self.first_output_at < self.anchor + self.speech_start_seconds - FRAME_SECONDS)
        if error is None and premature:
            error = BenchmarkError("unsolicited_output_before_source")
        if error is None and not self.closed:
            error = BenchmarkError("finalization_unconfirmed")
        result = {"case": case.id, "source_language": case.source, "target_language": case.target,
            "status": "error" if error else "completed", "session_elapsed_ms": round((now - self.began) * 1000, 3),
            "connection_ready_ms": round((self.ready_at - self.began) * 1000, 3) if self.ready_at is not None else None,
            "input_audio_duration_ms": round(len(self.clip) / (RATE * 2) * 1000, 3),
            "source_speech_end_ms_from_input_start": round(self.speech_end_seconds * 1000, 3),
            "speech_end_to_first_non_silent_output_ms": round(latency, 3) if latency is not None else None,
            "first_output_received_ms_from_input_start": round((self.first_output_at - self.anchor) * 1000, 3)
                if self.first_output_at is not None and self.anchor is not None else None,
            "max_upload_schedule_lag_ms": round(self.max_upload_lag * 1000, 3),
            "input_complete": self.input_complete, "input_frame_count": self.input_frames,
            "output_before_source_speech": premature,
            "output_pcm_bytes": self.output_bytes, "output_audio_event_count": self.output_events,
            "output_arrivals": self.output_arrivals, "output_arrivals_truncated": self.output_events > 128,
            "transcript_character_count": len(self.transcript), "transcript_delta_count": self.transcript_events,
            "transcript_sha256": digest(self.transcript), "transcript_start_ms": self.transcript_start_ms,
            "transcript_end_ms": self.transcript_end_ms, "finalization_confirmed": self.closed,
            "final_usage_seconds": self.final_usage_seconds, "translation_quality_validated": False}
        if error:
            result["error"] = error.code
            if error.http_status is not None:
                result["http_status"] = error.http_status
        return result


async def stream_microphone(socket, contract: dict[str, Any], measurements: Measurements,
                            limits: Limits, stop: asyncio.Event, clock: Callable[[], float], sleeper,
                            deadline: float = math.inf) -> None:
    measurements.anchor = clock()
    index = 0
    while not stop.is_set() and clock() < deadline:
        scheduled = measurements.anchor + index * FRAME_SECONDS
        await sleeper(max(0.0, scheduled - clock()))
        lag = max(0.0, clock() - scheduled)
        measurements.max_upload_lag = max(measurements.max_upload_lag, lag)
        if lag > limits.max_upload_lag_seconds:
            raise BenchmarkError("input_pacing_fell_behind")
        if stop.is_set():
            break
        start = index * FRAME_BYTES
        chunk = measurements.clip[start:start + FRAME_BYTES]
        if not chunk:
            measurements.input_complete = True
        chunk += bytes(FRAME_BYTES - len(chunk))
        await asyncio.wait_for(socket.send(canonical({"type": contract["input_audio_event"],
            "audio": base64.b64encode(chunk).decode("ascii")})), timeout=limits.send_timeout_seconds)
        measurements.input_frames += 1
        index += 1


def provider_error(event: dict[str, Any]) -> BenchmarkError:
    details = event.get("error")
    code = details.get("code") if isinstance(details, dict) else None
    public = {"invalid_api_key": "live_authentication_failed", "insufficient_quota": "live_quota_exceeded",
              "rate_limit_exceeded": "live_rate_limited", "model_not_found": "live_model_unavailable"}
    return BenchmarkError(public.get(code, "live_provider_error"))


async def _run_trial(key: str, case: Case, clip: bytes, contract: dict[str, Any], connector,
                    limits: Limits = Limits(), clock: Callable[[], float] = time.monotonic,
                    sleeper=asyncio.sleep) -> dict[str, Any]:
    if not 0.2 <= limits.session_seconds <= 20:
        raise BenchmarkError("invalid_session_budget")
    measurements = Measurements(clip, clock())
    deadline = measurements.began + limits.session_seconds - 0.15  # Transport cleanup reserve.
    error = None
    try:
        async with connector(contract["endpoint"], additional_headers={"Authorization": "Bearer " + key},
            open_timeout=min(5, limits.session_seconds / 3), close_timeout=0.1,
            max_size=MAX_EVENT_CHARS, max_queue=16, ssl=ssl.create_default_context()) as socket:
            await asyncio.wait_for(socket.send(canonical(contract["session_start"])), timeout=1)
            stop = asyncio.Event()
            sender = None
            closing = False
            event_count = 0
            try:
                while clock() < deadline:
                    now = clock()
                    if sender is not None and sender.done():
                        sender.result()
                    if measurements.ready_at is None and now - measurements.began > limits.ready_seconds:
                        raise BenchmarkError("live_startup_timeout")
                    if not closing and measurements.input_complete and measurements.anchor is not None:
                        speech_ended = now - measurements.anchor - measurements.speech_end_seconds >= limits.drain_seconds
                        quiet = (measurements.last_active_at is not None and
                                 now - measurements.last_active_at >= limits.quiet_seconds)
                        text_quiet = (measurements.last_transcript_at is not None and
                                      now - measurements.last_transcript_at >= limits.quiet_seconds)
                        if speech_ended and quiet and text_quiet:
                            closing = True; stop.set()
                            await asyncio.wait_for(socket.send(canonical({"type": contract["close_event"]})),
                                                   timeout=min(0.3, max(0.001, deadline - clock())))
                    try:
                        raw = await asyncio.wait_for(socket.recv(), timeout=min(0.1, max(0.001, deadline - clock())))
                    except asyncio.TimeoutError:
                        continue
                    now = clock()
                    event_count += 1
                    if event_count > MAX_EVENTS or not isinstance(raw, str) or len(raw) > MAX_EVENT_CHARS:
                        raise BenchmarkError("invalid_live_event")
                    try:
                        event = json.loads(raw)
                    except ValueError:
                        raise BenchmarkError("invalid_live_event") from None
                    if not isinstance(event, dict):
                        raise BenchmarkError("invalid_live_event")
                    kind = event.get("type")
                    if kind == contract["ready_event"]:
                        if measurements.ready_at is None:
                            resolved = event.get("session", {})
                            if resolved.get("model", MODEL) != MODEL:
                                raise BenchmarkError("resolved_model_mismatch")
                            actual_format = resolved.get("audio", {}).get("format")
                            if actual_format is not None and actual_format != {"type": "audio/pcm", "rate": RATE}:
                                raise BenchmarkError("resolved_audio_format_mismatch")
                            measurements.ready_at = now
                            sender = asyncio.create_task(stream_microphone(socket, contract, measurements, limits, stop, clock, sleeper, deadline))
                    elif kind == contract["output_audio_event"]:
                        if measurements.ready_at is None:
                            raise BenchmarkError("output_before_session_ready")
                        encoded = event.get("delta")
                        if not isinstance(encoded, str) or len(encoded) > MAX_DELTA_BYTES * 4 // 3 + 4:
                            raise BenchmarkError("invalid_output_audio")
                        try:
                            pcm = base64.b64decode(encoded, validate=True)
                        except binascii.Error:
                            raise BenchmarkError("invalid_output_audio") from None
                        if len(pcm) % 2 or len(pcm) > MAX_DELTA_BYTES:
                            raise BenchmarkError("invalid_output_audio")
                        measurements.output(pcm, now)
                    elif kind == contract["output_transcript_event"]:
                        measurements.transcript_delta(event, now)
                    elif kind == contract["closed_event"]:
                        measurements.closed = True
                        seconds = (event.get("usage") or {}).get("seconds")
                        if type(seconds) in (int, float) and math.isfinite(seconds) and 0 <= seconds <= 60:
                            measurements.final_usage_seconds = seconds
                        if not closing:
                            raise BenchmarkError("unexpected_session_closed")
                        break
                    elif kind in ("error", "session.error"):
                        raise provider_error(event)
                    elif kind in ("session.delegation.created", "response.event"):
                        raise BenchmarkError("unsupported_delegation_requested")
                if not measurements.closed:
                    error = BenchmarkError("live_session_budget_reached")
            finally:
                stop.set()
                if sender is not None:
                    sender.cancel()
                    await asyncio.gather(sender, return_exceptions=True)
                if not closing and not measurements.closed:
                    # Best effort bounded cleanup on failure; never retry or open another session.
                    with contextlib.suppress(Exception):
                        await asyncio.wait_for(socket.send(canonical({"type": contract["close_event"]})), timeout=0.1)
    except BenchmarkError as caught:
        error = caught
    except Exception as caught:
        response = getattr(caught, "response", None)
        status = getattr(response, "status_code", None)
        error = BenchmarkError("live_transport_error", status if type(status) is int else None)
    return measurements.report(case, clock(), error)


async def run_trial(key: str, case: Case, clip: bytes, contract: dict[str, Any], connector,
                    limits: Limits = Limits(), clock: Callable[[], float] = time.monotonic,
                    sleeper=asyncio.sleep) -> dict[str, Any]:
    began = clock()
    try:
        return await asyncio.wait_for(_run_trial(key, case, clip, contract, connector, limits, clock, sleeper),
                                      # Reserve for the bounded close command and TLS transport cleanup.
                                      timeout=max(0.1, limits.session_seconds - min(0.4, limits.session_seconds * 0.2)))
    except asyncio.TimeoutError:
        return Measurements(clip, began).report(case, clock(), BenchmarkError("live_session_hard_timeout"))


def make_connector():
    from websockets.asyncio.client import connect

    class NoRedirectConnect(connect):
        def process_redirect(self, exc):
            # Returning the original exception disables ALL automatic redirect attempts.
            # Never inspect, request or log a Location URL supplied by a remote server.
            return exc

    return NoRedirectConnect


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--run-provider-benchmark", action="store_true")
    args = parser.parse_args(argv)
    report = {"schema_version": 1, "status": "error", "scope": "synthetic_ci_network_and_provider_only",
        "android_audio_measured": False, "translation_quality_validated": False,
        "timing_reference": "paced_input_last_non_silent_10ms_window_to_first_received_non_silent_output_chunk",
        "audio_server_timestamps_available": False, "rms_threshold_pcm16": RMS_THRESHOLD,
        "input_frame_ms": 20, "speech_activity_window_ms": 10,
        "max_tts_requests": 2, "max_live_sessions": 2, "max_session_seconds": 20,
        "tts_requests_attempted": 0, "live_sessions_attempted": 0,
        "raw_text_or_audio_saved": False, "cases": []}
    try:
        if not args.run_provider_benchmark or os.environ.get("GITHUB_ACTIONS") != "true":
            raise BenchmarkError("explicit_ci_execution_required")
        contract = load_contract(args.contract)
        if not shutil.which("ffmpeg"):
            raise BenchmarkError("ffmpeg_required")
        key = os.environ.get("OPENAI_API_KEY", "")
        if not key or len(key) > 512 or any(ord(char) not in range(33, 127) for char in key):
            raise BenchmarkError("missing_or_invalid_openai_key")
        connect = make_connector()
        report["provider"] = {"name": "OpenAI", "host": HOST, "model": contract["model"], "transport": "websocket"}
        report["contract_sha256"] = digest(canonical(contract))
        report["tts_model"] = TTS_MODEL
        for case in CASES:
            try:
                report["tts_requests_attempted"] += 1
                clip = generate_clip(key, case)
                report["live_sessions_attempted"] += 1
                result = asyncio.run(run_trial(key, case, clip, contract, connect))
            except BenchmarkError as caught:
                result = {"case": case.id, "status": "error", "error": caught.code}
                if caught.http_status is not None:
                    result["http_status"] = caught.http_status
            report["cases"].append(result)
            if result["status"] != "completed":
                break  # Fail closed without consuming the remaining request/session budget.
        report["status"] = "completed" if len(report["cases"]) == 2 and all(
            result["status"] == "completed" for result in report["cases"]) else "incomplete"
    except BenchmarkError as caught:
        report["error"] = caught.code
    except Exception:
        report["error"] = "benchmark_setup_error"
    serialized = canonical(report)
    try:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(serialized + "\n")
    except OSError:
        report["status"] = "error"
        report["error"] = "report_write_failed"
        serialized = canonical(report)
    print(serialized)
    return 0 if report["status"] == "completed" else 1


if __name__ == "__main__":
    sys.exit(main())
