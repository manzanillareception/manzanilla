"""Offline safety/contract tests. No credentials or provider calls are required."""
import contextlib
import asyncio
import base64
import copy
import importlib.util
import io
import json
import os
import pathlib
import sys
import types
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("provision", pathlib.Path(__file__).with_name("provision_cartesia_v1.py"))
provision = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(provision)


class FakeApi:
    def __init__(self):
        self.agents = []
        self.tools = []
        self.writes = []
        self.template = {"config": {"model": {"id": "claude-haiku-4.5"},
                                   "audio": {"output": {"voice_id": "known_voice_id", "background_sound": {"file_id": "must_not_copy"}}},
                                   "tools": [{"id": "secret_webhook_tool"}],
                                   "system_tools": {"transfer_to_number": {"phone": "must_not_copy"}},
                                   "providers": {"api_key": "must_not_copy"}}}

    def listing(self, path):
        return copy.deepcopy(self.tools if path.endswith("/tools") else self.agents)

    def request(self, method, path, body=None):
        if method == "GET" and path == "/v1/agents/" + provision.TEMPLATE_AGENT:
            return copy.deepcopy(self.template)
        if method == "GET":
            return copy.deepcopy(next(agent for agent in self.agents if path.endswith("/" + agent["id"])))
        self.writes.append((method, path, copy.deepcopy(body)))
        if method != "POST":
            raise AssertionError("Existing resources must never be mutated")
        if path.endswith("/tools"):
            resource = dict(copy.deepcopy(body), id=f"agent_tool_{len(self.tools)}")
            self.tools.append(resource)
        else:
            resource = dict(copy.deepcopy(body), id="agent_dedicated_v1")
            self.agents.append(resource)
        return copy.deepcopy(resource)


class ProvisionTests(unittest.TestCase):
    @patch.dict(os.environ, {}, clear=True)
    def test_inspect_reads_without_writing_or_returning_template_details(self):
        api = FakeApi()
        result = provision.provision(api, False, provision.TEMPLATE_AGENT)
        self.assertEqual("missing", result["status"])
        self.assertEqual(5, result["missing_tool_count"])
        self.assertEqual([], api.writes)
        self.assertNotIn("must_not_copy", json.dumps(result))

    @patch.dict(os.environ, {}, clear=True)
    def test_new_dedicated_agent_has_only_five_client_tools_and_scalar_template_ids(self):
        api = FakeApi()
        result = provision.provision(api, True, provision.TEMPLATE_AGENT)
        self.assertEqual("created", result["status"])
        self.assertEqual(6, len(api.writes))
        for method, path, body in api.writes[:5]:
            self.assertEqual(("POST", "/v1/agents/tools"), (method, path))
            self.assertEqual("client", body["type"])
            self.assertTrue(body["expects_response"])
        agent = api.writes[-1][2]
        self.assertEqual(provision.AGENT_NAME, agent["name"])
        self.assertIn("{{hotel_context}}", agent["config"]["instructions"])
        self.assertEqual({"id": "claude-haiku-4.5"}, agent["config"]["model"])
        self.assertEqual("known_voice_id", agent["config"]["audio"]["output"]["voice_id"])
        self.assertIsNone(agent["config"]["audio"]["output"]["background_sound"])
        self.assertNotIn("must_not_copy", json.dumps(agent))
        self.assertNotIn("providers", agent["config"])
        self.assertNotIn("system_tools", agent["config"])

    @patch.dict(os.environ, {}, clear=True)
    def test_repeated_run_reuses_resources_without_mutation(self):
        api = FakeApi()
        first = provision.provision(api, True, provision.TEMPLATE_AGENT)
        api.writes.clear()
        second = provision.provision(api, True, provision.TEMPLATE_AGENT)
        self.assertEqual(first["agent_id"], second["agent_id"])
        self.assertEqual("reused", second["status"])
        self.assertEqual([], api.writes)

    @patch.dict(os.environ, {}, clear=True)
    def test_unmanaged_same_name_is_not_overwritten_or_reused(self):
        api = FakeApi()
        api.agents.append({"id": "agent_unrelated", "name": provision.AGENT_NAME, "description": "Somebody else's agent"})
        with self.assertRaises(provision.ProvisionError) as error:
            provision.provision(api, True, provision.TEMPLATE_AGENT)
        self.assertEqual("dedicated_agent_name_conflict", error.exception.code)
        self.assertEqual([], api.writes)

    @patch.dict(os.environ, {}, clear=True)
    def test_modified_managed_agent_is_rejected_without_repairing_shared_resources(self):
        api = FakeApi()
        provision.provision(api, True, provision.TEMPLATE_AGENT)
        api.writes.clear()
        api.agents[0]["config"]["instructions"] = "Changed remotely"
        with self.assertRaises(provision.ProvisionError) as error:
            provision.provision(api, True, provision.TEMPLATE_AGENT)
        self.assertEqual("dedicated_agent_configuration_mismatch", error.exception.code)
        self.assertEqual([], api.writes)

    @patch.dict(os.environ, {}, clear=True)
    def test_similar_foreign_tool_is_not_reused(self):
        api = FakeApi()
        foreign = dict(provision.tool_bodies()[0], id="foreign_tool", description="External tool")
        api.tools.append(foreign)
        provision.provision(api, True, provision.TEMPLATE_AGENT)
        self.assertNotIn({"id": "foreign_tool"}, api.agents[0]["config"]["tools"])
        self.assertEqual("External tool", api.tools[0]["description"])

    @patch.dict(os.environ, {"CARTESIA_API_KEY": "test_only_not_a_real_key"}, clear=True)
    def test_main_error_output_contains_only_fixed_status(self):
        output = io.StringIO()
        with patch.object(provision.Api, "request", side_effect=provision.ProvisionError("cartesia_http_error", 401)):
            with contextlib.redirect_stdout(output):
                status = provision.main(["--inspect"])
        self.assertEqual(1, status)
        self.assertEqual({"status": "error", "error": "cartesia_http_error", "http_status": 401}, json.loads(output.getvalue()))
        self.assertNotIn("test_only", output.getvalue())

    def test_pagination_cursors_cannot_choose_another_host(self):
        api = provision.Api("test_only_not_a_real_key")
        with patch.object(api, "request", return_value={"data": [], "has_more": True, "next_page": "https://example.invalid/steal"}):
            with self.assertRaises(provision.ProvisionError) as error:
                api.listing("/v1/agents")
        self.assertEqual("invalid_pagination", error.exception.code)

    def test_search_categories_match_device_backend(self):
        search = next(body for body in provision.tool_bodies() if body["name"] == "search_nearby")
        self.assertEqual(["all", "food", "cafe", "sights", "pharmacy", "transport", "groceries"],
                         search["parameters"]["properties"]["category"]["enum"])

    def test_ready_format_must_guarantee_native_16000hz_output(self):
        provision.assert_ready_format({"audio": {"input_format": "pcm_16000", "output_delivery": "speaking_pace"}})
        for event in ({}, {"audio": {"input_format": "pcm_44100"}}):
            with self.assertRaises(provision.ProvisionError) as error:
                provision.assert_ready_format(event)
            self.assertEqual("probe_unsupported_audio_format", error.exception.code)
        with self.assertRaises(provision.ProvisionError) as error:
            provision.assert_ready_format({"audio": {"input_format": "pcm_16000", "output_delivery": "as_available"}})
        self.assertEqual("probe_unpaced_audio_output", error.exception.code)


class FakeSocket:
    def __init__(self, events):
        self.events = iter(events)
        self.sent = []
        self.closed = False

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass

    def __aiter__(self):
        return self

    async def __anext__(self):
        await asyncio.sleep(0.015)  # Allow the concurrent 40ms input sender to run.
        try:
            return json.dumps(next(self.events))
        except StopIteration:
            raise StopAsyncIteration

    async def send(self, frame):
        self.sent.append(json.loads(frame))

    async def close(self, **kwargs):
        self.closed = True


class ProbeTests(unittest.IsolatedAsyncioTestCase):
    def websocket_modules(self, socket):
        module = types.ModuleType("websockets.asyncio.client")
        module.connect = lambda *args, **kwargs: socket
        return {"websockets": types.ModuleType("websockets"),
                "websockets.asyncio": types.ModuleType("websockets.asyncio"),
                "websockets.asyncio.client": module}

    async def test_readiness_only_probe_does_not_send_artificial_audio_or_tools(self):
        socket = FakeSocket([{"type": "session_ready", "audio": {"input_format": "pcm_16000", "output_delivery": "speaking_pace"}}])
        with patch.dict(sys.modules, self.websocket_modules(socket)):
            result = await provision.probe_ready("test-only-key", "agent_fixture")
        self.assertEqual("session_ready", result["status"])
        self.assertFalse(result["audio_device_verified"])
        self.assertEqual(["session_create"], [frame["type"] for frame in socket.sent])
        self.assertEqual("speaking_pace", socket.sent[0]["audio"]["output_delivery"])
        self.assertTrue(socket.closed)

    async def test_voice_probe_streams_during_output_and_consumes_only_synthetic_hotel_result(self):
        socket = FakeSocket([
            {"type": "session_ready", "audio": {"input_format": "pcm_16000", "output_delivery": "speaking_pace"}},
            {"type": "audio_output", "audio": "AQA="},
            {"type": "audio_output_clear"},
            {"type": "client_tool_call", "tool_call_id": "call_probe", "tool_name": "get_hotel_context", "parameters": {}, "expects_response": True},
            {"type": "audio_output", "audio": "AgA="},
            {"type": "turn_ended", "role": "assistant", "text": "The Wi-Fi network is Cedar Guest."},
        ])
        with patch.dict(sys.modules, self.websocket_modules(socket)):
            result = await provision.probe_ready("test-only-key", "agent_fixture", b"\x01\x00" * 1000)
        self.assertEqual("audio_and_tool_verified", result["synthetic_voice_status"])
        self.assertTrue(result["hotel_tool_roundtrip"])
        self.assertTrue(result["tool_result_spoken"])
        self.assertTrue(result["duplex_streaming_observed"])
        self.assertTrue(result["barge_in_observed"])
        self.assertFalse(result["audio_device_verified"])
        results = [frame for frame in socket.sent if frame["type"] == "client_tool_result"]
        self.assertEqual(1, len(results))
        self.assertEqual("call_probe", results[0]["tool_call_id"])
        self.assertIn("CedarGuest", results[0]["result"])
        audio_frames = [frame for frame in socket.sent if frame["type"] == "audio_input"]
        self.assertGreater(len(audio_frames), 1)
        self.assertTrue(all(len(base64.b64decode(frame["audio"])) == 1280 for frame in audio_frames))

    async def test_greeting_audio_without_tool_backed_response_is_not_voice_success(self):
        socket = FakeSocket([
            {"type": "session_ready", "audio": {"input_format": "pcm_16000", "output_delivery": "speaking_pace"}},
            {"type": "audio_output", "audio": "AQA="},
            {"type": "turn_ended", "role": "assistant", "text": "Hello."},
        ])
        with patch.dict(sys.modules, self.websocket_modules(socket)):
            with self.assertRaises(provision.ProvisionError):
                await provision.probe_ready("test-only-key", "agent_fixture", b"\x01\x00" * 1000)


if __name__ == "__main__":
    unittest.main()
