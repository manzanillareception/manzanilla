# Action-agent outbound calling (2026-11-05, candidate - not deployed)

Owner request (verbatim, 2026-11-05 13:09 IST voice): the Cartesia agent is "not for translation";
it must be "a proper AI agent" that takes actions - e.g. the receptionist picks up the hardware
phone and says "call this XYZ people... ask why they are not coming on time", and the agent calls
that person and asks exactly that. OpenAI is reserved for the translation path.

## Flow

1. Desk agent (existing nightdesk-style Cartesia managed agent, websocket from the device) hears
   "call <person/number>, ask <question>".
2. It invokes the webhook tool `call_guest` (tools/call_guest.webhook-tool.json) with
   {to_number, question, guest_name}. pre_tool_speech=force so the receptionist hears what is
   about to happen.
3. Cartesia POSTs to the worker `/api/place-call`, which validates (E.164, question length),
   then calls Cartesia `POST /agents/calls` with `from_number_id` and a dedicated
   outbound-caller agent (`CARTESIA_AGENT_CALLER`), passing the question as a dynamic variable.
4. The outbound-caller agent (prompts/outbound-caller.md) phones the guest and asks exactly that.
5. The worker returns the agent_call_id to the desk agent, which can tell the receptionist the
   call is underway. (Call outcome reporting back to the desk is a later step - Cartesia event
   webhook -> worker -> desk display.)

## Blockers / dependencies (not code)

- Telephony: Cartesia-provisioned numbers are US-only and outbound-US-only. Pilot guests are in
  Spain (+34). International outbound requires a Twilio-imported number or SIP trunk (billed by
  that provider). No account exists yet; vault and memory checked 2026-11-05 - nothing to reuse.
  The one Cartesia number on the account (+13144148291) belongs to the "dani" project and stays
  untouched. Until a number route is approved, `/api/place-call` returns 503
  `calling_not_configured` (env `CARTESIA_FROM_NUMBER_ID`/`CARTESIA_AGENT_CALLER` unset).
- Outbound calls to guests need the hotel's lawful basis/consent under the destination country's
  rules; Cartesia's AUP makes this the deployer's responsibility. This is a deploy-time
  requirement, not legal advice.

## Test status

- test-place-call.mjs: 10/10 (validation, payload shape, provider error surfacing, config gate).
- No live call has been placed. No number provisioned. No telephony spend.
- Websocket comparative model tests (tool-invocation correctness + latency across candidate
  non-OpenAI LLMs) are the next step; they need TTS-synthesized utterances (Cartesia credits,
  covered by the 2026-11-01 owner grant to use existing Cartesia credits/plan).
