"""Offline benchmark contract/timing tests. All network and provider behavior is fake."""
import array
import asyncio
import base64
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import AsyncMock, patch

SPEC = importlib.util.spec_from_file_location("benchmark_translation", Path(__file__).with_name("benchmark_openai_translation.py"))
benchmark = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = benchmark
SPEC.loader.exec_module(benchmark)
CONTRACT_PATH = Path(__file__).with_name("openai_translation_contract.json")


def pcm(value, count):
    values = array.array("h", [value] * count)
    if sys.byteorder != "little":
        values.byteswap()
    return values.tobytes()


class Clock:
    def __init__(self):
        self.now = 100.0

    def __call__(self):
        return self.now

    async def sleep(self, seconds):
        self.now += seconds
        await asyncio.sleep(0)


class Socket:
    def __init__(self, mode="normal"):
        self.queue = asyncio.Queue()
        self.sent = []
        self.audio_frames = 0
        self.mode = mode
        self.headers = None
        self.url = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass

    async def send(self, raw):
        event = json.loads(raw)
        self.sent.append(event)
        if event["type"] == "session.start":
            if self.mode == "no_ready":
                return
            if self.mode == "error":
                self.queue.put_nowait(json.dumps({"type": "error", "error": {"code": "invalid_api_key", "message": "secret-response-must-not-escape"}}))
                return
            if self.mode == "delegation":
                self.queue.put_nowait(json.dumps({"type": "session.delegation.created", "message": "do-not-run-tools"}))
                return
            self.queue.put_nowait(json.dumps({"type": "session.started", "session": {"model": "gpt-live-1"}}))
        elif event["type"] == "session.input_audio.append":
            self.audio_frames += 1
            self.queue.put_nowait(json.dumps({"type": "session.input_transcript.delta", "delta": "ignored fake source"}))
            if self.audio_frames == 18 and self.mode != "silent":
                self.queue.put_nowait(json.dumps({"type": "session.output_audio.delta", "delta": base64.b64encode(pcm(2000, 320)).decode()}))
                self.queue.put_nowait(json.dumps({"type": "session.output_transcript.delta", "delta": "never-log-synthetic-output", "start_ms": 300, "end_ms": 400}))
        elif event["type"] == "session.close" and self.mode != "no_close_ack":
            self.queue.put_nowait(json.dumps({"type": "session.closed", "usage": {"seconds": 0.6}}))

    async def recv(self):
        return await self.queue.get()

    def connect(self, url, **kwargs):
        self.url = url
        self.headers = kwargs.get("additional_headers")
        return self


class ContractAndPcmTests(unittest.TestCase):
    def test_loads_exported_native_contract_and_preserves_actual_start_object(self):
        contract = benchmark.load_contract(CONTRACT_PATH)
        self.assertEqual(json.loads(CONTRACT_PATH.read_text())["session_start"], contract["session_start"])
        self.assertEqual("gpt-live-1", contract["session_start"]["session"]["model"])
        self.assertFalse(contract["session_start"]["session"]["store"])

    def test_rejects_other_hosts_or_paid_backend_contracts_before_network(self):
        valid = json.loads(CONTRACT_PATH.read_text())
        for mutate in (lambda c: c.update(endpoint="wss://other.invalid/live"),
                       lambda c: c["session_start"]["session"].update(delegation={"type": "responses"})):
            contract = copy.deepcopy(valid)
            mutate(contract)
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "contract.json"
                path.write_text(json.dumps(contract))
                with self.assertRaises(benchmark.BenchmarkError):
                    benchmark.load_contract(path)

    def test_10ms_rms_speech_bounds_ignore_trailing_and_leading_silence(self):
        clip = pcm(0, 160) + pcm(2000, 320) + pcm(0, 320)
        self.assertEqual((160, 480), benchmark.active_bounds(clip))
        self.assertIsNone(benchmark.active_bounds(pcm(20, 320)))
        with self.assertRaises(benchmark.BenchmarkError):
            benchmark.active_bounds(b"\x01")

    def test_latency_keeps_negative_simultaneous_output_and_never_exposes_text(self):
        measurements = benchmark.Measurements(pcm(2000, 1600), 9.0)
        measurements.anchor = 10.0
        measurements.ready_at = 9.5
        measurements.input_complete = measurements.closed = True
        measurements.output(pcm(2000, 320), 10.35)
        measurements.transcript_delta({"delta": "do-not-log-this-output", "start_ms": 200, "end_ms": 300}, 10.4)
        report = measurements.report(benchmark.CASES[0], 11.0)
        self.assertAlmostEqual(-50, report["speech_end_to_first_non_silent_output_ms"], places=5)
        self.assertEqual("completed", report["status"])
        self.assertNotIn("do-not-log", json.dumps(report))
        self.assertEqual(200, report["transcript_start_ms"])

    def test_early_greeting_cannot_be_reported_as_fast_translation(self):
        measurements = benchmark.Measurements(pcm(2000, 1600), 9.0)
        measurements.anchor = 10.0
        measurements.input_complete = measurements.closed = True
        measurements.output(pcm(2000, 320), 10.1)
        measurements.transcript_delta({"delta": "greeting"}, 10.1)
        report = measurements.report(benchmark.CASES[0], 11.0)
        self.assertEqual("unsolicited_output_before_source", report["error"])
        self.assertTrue(report["output_before_source_speech"])

    def test_cli_requires_explicit_ci_gate_without_reading_a_key_or_making_requests(self):
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "report.json"
            with patch.dict(os.environ, {"OPENAI_API_KEY": "not-for-logs"}, clear=True), patch.object(benchmark, "generate_clip") as generate:
                with contextlib.redirect_stdout(output):
                    status = benchmark.main(["--contract", str(CONTRACT_PATH), "--output", str(report)])
            self.assertEqual(json.loads(report.read_text()), json.loads(output.getvalue()))
        self.assertEqual(1, status)
        self.assertEqual("explicit_ci_execution_required", json.loads(output.getvalue())["error"])
        generate.assert_not_called()
        self.assertNotIn("not-for-logs", output.getvalue())

    def test_websocket_and_https_redirects_never_follow_provider_supplied_urls(self):
        self.assertIsNone(benchmark.NoRedirects().redirect_request(None, None, 302, "fixture", {}, "https://external.invalid"))
        connector_class = benchmark.make_connector()
        connector = object.__new__(connector_class)
        original = RuntimeError("external URL must never be requested")
        self.assertIs(original, connector.process_redirect(original))

    def test_cli_request_budget_is_two_fixed_cases_without_retries(self):
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ,
                {"GITHUB_ACTIONS": "true", "OPENAI_API_KEY": "fake-budget-key"}, clear=True), \
                patch.object(benchmark.shutil, "which", return_value="/fixture/ffmpeg"), \
                patch.object(benchmark, "generate_clip", return_value=pcm(2000, 1600)) as generate, \
                patch.object(benchmark, "make_connector", return_value=object()), \
                patch.object(benchmark, "run_trial", new_callable=AsyncMock, return_value={"status": "completed"}) as trial:
            path = Path(directory) / "report.json"
            with contextlib.redirect_stdout(output):
                status = benchmark.main(["--contract", str(CONTRACT_PATH), "--output", str(path), "--run-provider-benchmark"])
            report = json.loads(path.read_text())
        self.assertEqual(0, status)
        self.assertEqual(2, generate.call_count)
        self.assertEqual(2, trial.call_count)
        self.assertEqual(2, report["tts_requests_attempted"])
        self.assertEqual(2, report["live_sessions_attempted"])
        self.assertFalse(report["translation_quality_validated"])
        self.assertNotIn("fake-budget-key", output.getvalue())

    def test_cli_failure_does_not_spend_the_second_case_budget(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ,
                {"GITHUB_ACTIONS": "true", "OPENAI_API_KEY": "fake-budget-key"}, clear=True), \
                patch.object(benchmark.shutil, "which", return_value="/fixture/ffmpeg"), \
                patch.object(benchmark, "generate_clip", return_value=pcm(2000, 1600)) as generate, \
                patch.object(benchmark, "make_connector", return_value=object()), \
                patch.object(benchmark, "run_trial", new_callable=AsyncMock,
                    return_value={"status": "error", "error": "live_quota_exceeded"}) as trial:
            path = Path(directory) / "report.json"
            with contextlib.redirect_stdout(io.StringIO()):
                status = benchmark.main(["--contract", str(CONTRACT_PATH), "--output", str(path), "--run-provider-benchmark"])
            report = json.loads(path.read_text())
        self.assertEqual(1, status)
        self.assertEqual(1, generate.call_count)
        self.assertEqual(1, trial.call_count)
        self.assertEqual("incomplete", report["status"])


class StreamingTests(unittest.IsolatedAsyncioTestCase):
    async def test_realtime_20ms_upload_keeps_silence_flowing_after_clip(self):
        clock = Clock()
        measurements = benchmark.Measurements(pcm(2000, 640), clock())
        stop = asyncio.Event()
        frames = []

        class Recorder:
            async def send(self, raw):
                frames.append((clock(), json.loads(raw)))
                if len(frames) == 22:
                    stop.set()

        await benchmark.stream_microphone(Recorder(), benchmark.load_contract(CONTRACT_PATH), measurements,
            benchmark.Limits(), stop, clock, clock.sleep)
        self.assertTrue(measurements.input_complete)
        self.assertEqual(22, len(frames))
        for index, (at, frame) in enumerate(frames):
            self.assertAlmostEqual(100 + index * 0.02, at, places=6)
            self.assertEqual(640, len(base64.b64decode(frame["audio"])))
        self.assertEqual(bytes(640), base64.b64decode(frames[-1][1]["audio"]))

    async def test_fake_session_uses_exact_native_start_and_collects_only_sanitized_metrics(self):
        socket = Socket()
        clock = Clock()
        contract = benchmark.load_contract(CONTRACT_PATH)
        result = await benchmark.run_trial("fake-fixture-key", benchmark.CASES[0], pcm(2000, 1600), contract,
            socket.connect, benchmark.Limits(session_seconds=2, drain_seconds=0.02, quiet_seconds=0.02), clock, clock.sleep)
        self.assertEqual("completed", result["status"], result)
        self.assertEqual(contract["session_start"], socket.sent[0])
        self.assertEqual(benchmark.ENDPOINT, socket.url)
        self.assertEqual({"Authorization": "Bearer fake-fixture-key"}, socket.headers)
        self.assertTrue(result["finalization_confirmed"])
        self.assertEqual(0.6, result["final_usage_seconds"])
        self.assertFalse(result["translation_quality_validated"])
        self.assertNotIn("fake-fixture-key", json.dumps(result))
        self.assertNotIn("never-log", json.dumps(result))
        self.assertGreater(result["input_frame_count"], 18)

    async def test_provider_error_is_fixed_code_and_never_echoes_response(self):
        socket = Socket("error")
        result = await benchmark.run_trial("not-for-logs", benchmark.CASES[0], pcm(2000, 1600),
            benchmark.load_contract(CONTRACT_PATH), socket.connect)
        self.assertEqual("live_authentication_failed", result["error"])
        self.assertNotIn("secret-response", json.dumps(result))
        self.assertNotIn("not-for-logs", json.dumps(result))

    async def test_client_delegation_request_is_rejected_without_extra_api_calls(self):
        socket = Socket("delegation")
        result = await benchmark.run_trial("fake", benchmark.CASES[0], pcm(2000, 1600),
            benchmark.load_contract(CONTRACT_PATH), socket.connect)
        self.assertEqual("unsupported_delegation_requested", result["error"])
        self.assertEqual(["session.start", "session.close"], [event["type"] for event in socket.sent])

    async def test_unready_session_has_a_bounded_timeout_and_no_microphone_upload(self):
        socket = Socket("no_ready")
        result = await benchmark.run_trial("fake", benchmark.CASES[0], pcm(2000, 1600),
            benchmark.load_contract(CONTRACT_PATH), socket.connect,
            benchmark.Limits(session_seconds=0.4, ready_seconds=0.02))
        self.assertEqual("live_startup_timeout", result["error"])
        self.assertLess(result["session_elapsed_ms"], 400)
        self.assertEqual(0, result["input_frame_count"])

    async def test_no_close_ack_never_reports_completed_and_stops_at_session_budget(self):
        socket = Socket("no_close_ack")
        result = await benchmark.run_trial("fake", benchmark.CASES[0], pcm(2000, 1600),
            benchmark.load_contract(CONTRACT_PATH), socket.connect,
            benchmark.Limits(session_seconds=0.8, drain_seconds=0.02, quiet_seconds=0.02))
        self.assertEqual("error", result["status"])
        self.assertFalse(result["finalization_confirmed"])
        self.assertLess(result["session_elapsed_ms"], 800)


if __name__ == "__main__":
    unittest.main()
