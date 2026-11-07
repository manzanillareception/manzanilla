# Voice experience repair — 17.0.44 / code74

The owner reported concatenated OpenAI captions, update banners covering voice
screens, and a Cartesia night-reception startup failure on the physical device.

## Findings and changes

- Translation already connects directly to OpenAI at
  `wss://api.openai.com/v1/live/sessions`, using the saved device key and
  `gpt-live-1`. There is no Cartesia translation fallback.
- GPT-Live returns timestamped transcript fragments without model turn IDs or a
  completed-turn event. The app now preserves timing and groups display segments
  into separate centered rows. These are display boundaries, never guessed
  guest/receptionist identities. Sentence boundaries and session-timeline gaps
  split rows; fragments retain their original text and spacing.
- General update notices and install/check controls move into a home-only bell
  panel. Voice screens do not show version/status overlays.
- The screenshot's `Audio startup fell behind` came from the app's one-second
  startup buffer. It was not a provider failure: output audio had already arrived.
  Native audio setup now overlaps the handshake. Incoming PCM goes directly to
  one bounded playback queue rather than retaining audio on the UI dispatcher.
  Playback starts immediately; the burst memory ceiling is not a prefill delay.
- OpenAI microphone upload backpressure is bounded to approximately 300 ms of
  encoded audio, instead of quietly accumulating several seconds behind a slow
  network. A stalled connection fails visibly and follows bounded reconnect.
- Connection diagnostics expose only fixed provider identity and numeric timing
  fields in V1 Settings. Transport startup, native preparation and received-PCM
  playback times are labelled separately; none is advertised as acoustic or
  speech-to-speech latency. No key, audio, speech text or account data is saved.

## Provider choice and honest latency scope

The current shared-microphone flow supports arbitrary pairs in the app's picker
and must translate in both directions without deciding which person is speaking.
OpenAI's dedicated `gpt-realtime-translate` endpoint is optimized for continuous
interpretation, but currently has one fixed output language per session and 13
output languages. Two sessions fed a shared microphone are an experiment, not a
verified replacement: same-language suppression is not guaranteed and overlapping
or third-language speech can produce competing outputs. The default stays on
OpenAI GPT-Live with a phrase-streaming interpreter prompt and continuous audio.
A prompt change alone does not establish lower provider latency.

Primary references:
- https://developers.openai.com/api/docs/guides/live-conversations
- https://developers.openai.com/api/docs/guides/voice-websockets
- https://developers.openai.com/api/docs/guides/realtime-translation
- https://developers.openai.com/cookbook/examples/voice_solutions/realtime_translation_guide

## Night receptionist and tests

Night audio, recognition, managed reasoning and speech are accessed through
Cartesia Managed Agents with the bundled Cartesia key. The managed LLM may be
provided by another vendor within Cartesia's service; the app needs no separate
LLM key or direct LLM connection. Keep the previously configured agent and voice.
Do not add unsupported STT/TTS configuration fields or substitute an OpenAI
translation key into night reception.

The owner explicitly requested no Cartesia API testing for this repair. Release
CI reuses the existing public agent ID and uses its key only in the APK build.
All Cartesia fixes are checked with offline protocol/audio/session fixtures.
The standalone provisioning script is a manual tool and is not run by CI.

An optional manually dispatched OpenAI-only probe uses a temporary Actions secret
and fixed synthetic English/Spanish clips. It reports client-observed first
non-silent output relative to the known synthetic speech timeline. This combines
network and provider delay; it does not measure the physical Gama804 speaker,
room acoustics, microphone echo cancellation or human translation quality.
Physical-device acceptance and matched before/after speech tests remain required
before making a subsecond latency claim.

Cartesia references:
- https://docs.cartesia.ai/agents/models
- https://www.cartesia.ai/cookbooks/voice-chat
- https://docs.cartesia.ai/line/integrations/websocket-api
