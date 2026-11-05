// Comparative action-agent model harness (Cartesia websocket, client tool call_guest).
// Usage: node compare-action-models.mjs --key-file <path> --scenario clear-number ...
// No telephony: the tool is a CLIENT tool; invocations arrive over the websocket and are
// answered locally. No real call can result.
import { readFileSync, writeFileSync } from 'fs';

const args = Object.fromEntries(process.argv.slice(2).reduce((a, x, i, arr) => {
  if (x.startsWith('--')) a.push([x.slice(2), arr[i + 1] && !arr[i + 1].startsWith('--') ? arr[i + 1] : 'true']);
  return a;
}, []));
const KEY = readFileSync(args['key-file'], 'utf8').trim();
const API = 'https://api.cartesia.ai';
const WS = 'wss://api.cartesia.ai/v1/agents/websocket/';
const VER = '2026-08-14';
const VOICE = 'db6b0ed5-d5d3-463d-ae85-518a07d3c2b4';

async function api(path, opts = {}) {
  const r = await fetch(API + path, {
    ...opts,
    headers: { 'Authorization': 'Bearer ' + KEY, 'Cartesia-Version': VER, 'content-type': 'application/json', ...(opts.headers || {}) },
  });
  const text = await r.text();
  let data = null; try { data = JSON.parse(text); } catch {}
  return { status: r.status, data, text: data ? null : text.slice(0, 300) };
}

async function ttsPcm(transcript, language) {
  const r = await fetch(API + '/tts/bytes', {
    method: 'POST',
    headers: { 'X-API-Key': KEY, 'Cartesia-Version': VER, 'content-type': 'application/json' },
    body: JSON.stringify({
      model_id: 'sonic-3.6',
      transcript,
      voice: { mode: 'id', id: VOICE },
      language,
      output_format: { container: 'raw', encoding: 'pcm_s16le', sample_rate: 16000 },
    }),
  });
  if (!r.ok) throw new Error('tts failed ' + r.status + ' ' + (await r.text()).slice(0, 200));
  return Buffer.from(await r.arrayBuffer());
}

const SCENARIOS = {
  'clear-number': {
    transcript: 'Please call plus three four six one two three four five six seven eight, and ask them why they are not coming on time.',
    language: 'en',
    expectTool: 'call_guest',
    expectParams: { to_number: '+34612345678', questionIncludes: 'not coming on time' },
  },
  'missing-number': {
    transcript: 'Can you call the guest and ask why they are late?',
    language: 'en',
    expectNoTool: true,
  },
  'missing-question': {
    transcript: 'Call plus three four six one two three four five six seven eight.',
    language: 'en',
    expectNoTool: true,
  },
  'tool-error': {
    transcript: 'Please call plus three four six one two three four five six seven eight, and ask them why they are not coming on time.',
    language: 'en',
    expectTool: 'call_guest',
    expectParams: { to_number: '+34612345678' },
    toolError: 'calling_not_configured',
  },
  'clear-number-es': {
    transcript: 'Llama al más treinta y cuatro seis doce treinta y cuatro cincuenta y seis setenta y ocho, y pregúntales por qué no llegan a tiempo.',
    language: 'es',
    expectTool: 'call_guest',
    expectParams: { to_number: '+34612345678' },
  },
};

async function runSession(agentId, pcm, scenario) {
  return new Promise((resolve) => {
    const out = { events: [], toolCalls: [], text: '', callId: null, firstTextMs: null, firstAudioMs: null, toolCallMs: null, ended: false, error: null };
    const t0 = Date.now();
    let tAudioEnd = null;
    const ws = new WebSocket(WS + agentId + '?cartesia_version=' + VER, { headers: { 'X-API-Key': KEY } });
    const bail = setTimeout(() => { out.error = 'timeout'; try { ws.close(); } catch {} resolve(out); }, 40000);
    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'session_create', audio: { input_format: 'pcm_16000', output_delivery: 'as_available', continuous_input: true } }));
    };
    ws.onerror = (e) => { out.error = 'ws error'; };
    ws.onclose = () => { if (!out.ended && !out.error) { out.error = 'closed_early'; clearTimeout(bail); resolve(out); } };
    ws.onmessage = async (m) => {
      let e; try { e = JSON.parse(m.data); } catch { return; }
      out.events.push(e.type);
      if (e.type === 'session_ready') { out.callId = e.call_id || null;
        // stream utterance in 20ms chunks (640 samples = 1280 bytes) at real-time pace
        const CH = 1280;
        let off = 0;
        const timer = setInterval(() => {
          if (off >= pcm.length) {
            clearInterval(timer);
            tAudioEnd = Date.now();
            // trailing silence ~1.5s to let endpointing fire
            const sil = Buffer.alloc(CH);
            let n = 0;
            const st = setInterval(() => {
              if (++n > 75) { clearInterval(st); return; }
              if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'audio_input', audio: sil.toString('base64') }));
            }, 20);
            return;
          }
          const chunk = pcm.subarray(off, off + CH);
          off += CH;
          if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'audio_input', audio: Buffer.from(chunk).toString('base64') }));
        }, 20);
      }
      if (e.type === 'turn_output_text_delta') {
        if (out.firstTextMs === null) out.firstTextMs = Date.now() - (tAudioEnd || t0);
        out.text += (e.text || e.delta || '');
      }
      if (e.type === 'audio_output' && out.firstAudioMs === null) out.firstAudioMs = Date.now() - (tAudioEnd || t0);
      if (e.type === 'client_tool_call') {
        out.toolCallMs = Date.now() - (tAudioEnd || t0);
        out.toolCalls.push({ name: e.tool_name, parameters: e.parameters });
        if (e.expects_response) {
          const err = scenario.toolError;
          ws.send(JSON.stringify({
            type: 'client_tool_result',
            tool_call_id: e.tool_call_id,
            result: err ? 'The call could not be placed: ' + err : JSON.stringify({ status: 'calling', agent_call_id: 'ac_TESTMOCK' }),
            is_error: !!err,
          }));
        }
      }
      if (e.type === 'turn_ended') {
        // allow a following tool turn; end after a quiet period handled by timeout below
      }
      if (e.type === 'error') out.error = (e.message || e.code || 'agent error');
    };
    // settle: resolve 6s after audio end if we already have a tool call or first text, else on timeout
    const settle = setInterval(() => {
      if (tAudioEnd && Date.now() - tAudioEnd > 12000) {
        clearInterval(settle); clearTimeout(bail);
        out.ended = true; try { ws.close(); } catch {} resolve(out);
      }
    }, 500);
  });
}

function verdict(sc, out) {
  const issues = [];
  if (sc.expectTool) {
    const call = out.toolCalls.find((c) => c.name === sc.expectTool);
    if (!call) issues.push('tool NOT invoked');
    else {
      const p = call.parameters || {};
      if (sc.expectParams.to_number && p.to_number !== sc.expectParams.to_number)
        issues.push('to_number mismatch: got ' + JSON.stringify(p.to_number));
      if (sc.expectParams.questionIncludes && !(String(p.question || '').toLowerCase().includes(sc.expectParams.questionIncludes)))
        issues.push('question missing phrase: got ' + JSON.stringify(p.question));
    }
  }
  if (sc.expectNoTool && out.toolCalls.length) issues.push('unexpected tool invocation: ' + JSON.stringify(out.toolCalls));
  return issues;
}

const mode = args.mode;
if (mode === 'tts') {
  const sc = SCENARIOS[args.scenario];
  const pcm = await ttsPcm(sc.transcript, sc.language);
  writeFileSync(args.out, pcm);
  console.log('tts bytes:', pcm.length, '->', args.out);
} else if (mode === 'run') {
  const sc = SCENARIOS[args.scenario];
  const pcm = readFileSync(args.pcm);
  const out = await runSession(args.agent, pcm, sc);
  console.log(JSON.stringify({
    scenario: args.scenario, agent: args.agent, callId: out.callId,
    firstTextMs: out.firstTextMs, firstAudioMs: out.firstAudioMs, toolCallMs: out.toolCallMs,
    toolCalls: out.toolCalls, text: out.text, error: out.error,
    eventCounts: out.events.reduce((a, t) => ((a[t] = (a[t] || 0) + 1), a), {}),
    issues: verdict(sc, out),
  }, null, 1));
} else {
  console.log('modes: tts --scenario S --out file.pcm | run --scenario S --agent agent_ID --pcm file.pcm');
}
