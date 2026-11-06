// Outbound-caller prompt test + nightdesk latency baseline (Cartesia websocket, no telephony).
// Caller mode: agent speaks first; we wait for its first turn to end, then play our PCM answer.
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

async function ttsPcm(transcript, language) {
  const r = await fetch(API + '/tts/bytes', {
    method: 'POST',
    headers: { 'X-API-Key': KEY, 'Cartesia-Version': VER, 'content-type': 'application/json' },
    body: JSON.stringify({ model_id: 'sonic-3.6', transcript, voice: { mode: 'id', id: VOICE }, language,
      output_format: { container: 'raw', encoding: 'pcm_s16le', sample_rate: 16000 } }),
  });
  if (!r.ok) throw new Error('tts failed ' + r.status + ' ' + (await r.text()).slice(0, 200));
  return Buffer.from(await r.arrayBuffer());
}

const BI_SCENARIOS = {
  'bi-long': { transcript: 'Can you tell me in detail about all the breakfast options and everything on the restaurant menu for today?', language: 'en' },
  'bi-interrupt': { transcript: 'Stop, stop. Actually, just tell me what time checkout is.', language: 'en' },
};
const ND_SCENARIOS = {
  'nd-breakfast': { transcript: 'What time is breakfast tomorrow, and is it included?', language: 'en', expectTextAny: ['breakfast'] },
  'nd-taxi': { transcript: 'Can you get me a taxi to the airport tomorrow at seven in the morning?', language: 'en', expectTextAny: ['taxi'] },
  'nd-pharmacy': { transcript: 'Is there a pharmacy near the hotel?', language: 'en', expectTextAny: ['pharm', 'farmac'] },
  'nd-ac-es': { transcript: 'El aire acondicionado de mi habitación no funciona.', language: 'es', expectTextAny: ['aire', 'acondicionado', 'recepci'] },
};
const CALLER_SCENARIOS = {
  'c-straight': { transcript: "Yes, hi. I'll be there in about twenty minutes, sorry about that.", language: 'en',
    expectTextAny: ['twenty', '20', 'thank', 'reception', 'pass', 'let'] },
  'c-spanish': { transcript: '¿Sí? ¿Quién es? No entiendo, ¿quién llama?', language: 'es',
    expectTextAny: ['recepci', 'hotel'] },
  'c-repeat': { transcript: 'Sorry, who is this? Can you say that again please?', language: 'en',
    expectTextAny: ['not coming on time', 'coming on time', 'reception'] },
  'c-offtopic': { transcript: "The train was delayed, that's why. Also, can you book me a table for dinner at nine?", language: 'en',
    expectNoBook: true, expectTextAny: ['reception', 'desk', 'cannot', "can't", 'not able', 'pass'] },
};

function streamPcm(ws, pcm, done) {
  const CH = 1280; let off = 0;
  const timer = setInterval(() => {
    if (off >= pcm.length) { clearInterval(timer);
      const sil = Buffer.alloc(CH); let n = 0;
      const st = setInterval(() => { if (++n > 75) { clearInterval(st); done && done(); return; }
        if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'audio_input', audio: sil.toString('base64') })); }, 20);
      return; }
    const chunk = pcm.subarray(off, off + CH); off += CH;
    if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'audio_input', audio: Buffer.from(chunk).toString('base64') }));
  }, 20);
}

async function runNightdesk(agentId, pcm) {
  // Skips the agent's initial greeting turn; streams our question after it; measures the ANSWER turn.
  return new Promise((resolve) => {
    const out = { text: '', firstTextMs: null, firstAudioMs: null, greeting: '', error: null, callId: null };
    const t0 = Date.now(); let tAudioEnd = null, greeted = false, streamed = false, answered = false;
    const ws = new WebSocket(WS + agentId + '?cartesia_version=' + VER, { headers: { 'X-API-Key': KEY } });
    const bail = setTimeout(() => { out.error = out.error || 'timeout'; try { ws.close(); } catch {} resolve(out); }, 45000);
    ws.onopen = () => ws.send(JSON.stringify({ type: 'session_create', audio: { input_format: 'pcm_16000', output_delivery: 'as_available', continuous_input: true } }));
    ws.onerror = () => { out.error = 'ws error'; };
    ws.onclose = () => { clearTimeout(bail); if (!out.text && !out.error) out.error = 'closed_early'; resolve(out); };
    ws.onmessage = (m) => {
      let e; try { e = JSON.parse(m.data); } catch { return; }
      if (e.type === 'session_ready') out.callId = e.call_id || null;
      if (e.type === 'turn_output_text_delta') {
        const d = (e.text || e.delta || '');
        if (!greeted) { out.greeting += d; return; }
        if (out.firstTextMs === null) out.firstTextMs = Date.now() - (tAudioEnd || t0);
        out.text += d;
      }
      if (e.type === 'audio_output' && greeted && out.firstAudioMs === null) out.firstAudioMs = Date.now() - (tAudioEnd || t0);
      if (e.type === 'error') out.error = (e.message || e.code || 'agent error');
      if (e.type === 'turn_ended') {
        if (!greeted) {
          greeted = true;
          if (!streamed) { streamed = true; streamPcm(ws, pcm, () => { tAudioEnd = Date.now(); }); }
          // if no greeting ever comes, turn_ended may not fire pre-stream; fallback below
        } else if (out.text && !answered) { answered = true; clearTimeout(bail); setTimeout(() => { try { ws.close(); } catch {}; resolve(out); }, 1500); }
      }
    };
    // fallback: if no greeting within 8s, stream anyway
    setTimeout(() => { if (!streamed) { streamed = true; greeted = true; streamPcm(ws, pcm, () => { tAudioEnd = Date.now(); }); } }, 8000);
  });
}

async function runCaller(agentId, pcm) {
  return new Promise((resolve) => {
    const out = { agentTurn1: '', agentTurn2: '', t1Ms: null, t2FirstTextMs: null, error: null, callId: null };
    const t0 = Date.now(); let turn = 0, streamed = false, tAudioEnd = null;
    const ws = new WebSocket(WS + agentId + '?cartesia_version=' + VER, { headers: { 'X-API-Key': KEY } });
    const bail = setTimeout(() => { out.error = out.error || 'timeout'; try { ws.close(); } catch {} resolve(out); }, 60000);
    ws.onopen = () => ws.send(JSON.stringify({ type: 'session_create', audio: { input_format: 'pcm_16000', output_delivery: 'as_available', continuous_input: true } }));
    ws.onerror = () => { out.error = 'ws error'; };
    ws.onclose = () => { clearTimeout(bail); resolve(out); };
    ws.onmessage = (m) => {
      let e; try { e = JSON.parse(m.data); } catch { return; }
      if (e.type === 'turn_output_text_delta') {
        const d = (e.text || e.delta || '');
        if (turn === 0) { if (out.t1Ms === null) out.t1Ms = Date.now() - t0; out.agentTurn1 += d; }
        else { if (out.t2FirstTextMs === null) out.t2FirstTextMs = Date.now() - (tAudioEnd || t0); out.agentTurn2 += d; }
      }
      if (e.type === 'turn_ended') {
        if (turn === 0 && !streamed) { streamed = true; turn = 1; streamPcm(ws, pcm, () => { tAudioEnd = Date.now(); }); }
        else if (turn >= 1) {
          turn += 1;
          // resolve after the substantive turn (allow up to 3 turns post-answer, or 8s quiet)
          if (turn >= 3) { clearTimeout(bail); setTimeout(() => { try { ws.close(); } catch {}; resolve(out); }, 1500); }
          else { clearTimeout(bail); setTimeout(() => { try { ws.close(); } catch {}; resolve(out); }, 8000); }
        }
      }
      if (e.type === 'error') out.error = (e.message || e.code || 'agent error');
    };
    // if the agent never speaks first within 20s, stream anyway (records prompt failure)
    setTimeout(() => { if (!streamed) { streamed = true; turn = 1; out.error = out.error || 'agent_never_spoke_first'; streamPcm(ws, pcm, () => { tAudioEnd = Date.now(); }); } }, 20000);
  });
}

const mode = args.mode;
if (mode === 'tts') {
  const sc = ND_SCENARIOS[args.scenario] || CALLER_SCENARIOS[args.scenario] || BI_SCENARIOS[args.scenario];
  const pcm = await ttsPcm(sc.transcript, sc.language);
  writeFileSync(args.out, pcm);
  console.log('tts bytes:', pcm.length, '->', args.out);
} else if (mode === 'run-nd') {
  const sc = ND_SCENARIOS[args.scenario];
  const out = await runNightdesk(args.agent, readFileSync(args.pcm));
  const t = out.text.toLowerCase();
  void 0;
  const issues = [];
  if (sc.expectTextAny && !sc.expectTextAny.some((p) => t.includes(p))) issues.push('missing any of ' + JSON.stringify(sc.expectTextAny) + ' | got: ' + t.slice(0, 140));
  console.log(JSON.stringify({ scenario: args.scenario, callId: out.callId, firstTextMs: out.firstTextMs, firstAudioMs: out.firstAudioMs, text: out.text, error: out.error, issues }, null, 1));
} else if (mode === 'run-bargein') {
  // Full-duplex measurement: greeting -> question -> interrupt mid-answer. Full audio timeline kept.
  const pcm1 = readFileSync(args.pcm1), pcm2 = readFileSync(args.pcm2);
  const o = { callId: null, error: null, tGreetingEnd: null, tAnswerStart: null, tInterruptSent: null,
              lastAgentAudioAfterInterrupt: null, audioEventsAfterInterrupt: 0, answerText: '', postText: '' };
  await new Promise((resolve) => {
    let phase = 0; const t0 = Date.now();
    const ws = new WebSocket(WS + args.agent + '?cartesia_version=' + VER, { headers: { 'X-API-Key': KEY } });
    ws.onopen = () => ws.send(JSON.stringify({ type: 'session_create', audio: { input_format: 'pcm_16000', output_delivery: 'as_available', continuous_input: true } }));
    ws.onerror = () => { o.error = 'ws error'; };
    ws.onclose = () => resolve();
    ws.onmessage = (m) => {
      let e; try { e = JSON.parse(m.data); } catch { return; }
      if (e.type === 'session_ready') o.callId = e.call_id || null;
      if (e.type === 'turn_ended' && phase === 0) { phase = 1; o.tGreetingEnd = Date.now() - t0; streamPcm(ws, pcm1); }
      if (e.type === 'audio_output' && phase === 1 && o.tAnswerStart === null) {
        o.tAnswerStart = Date.now() - t0;
        setTimeout(() => { if (phase === 1) { phase = 2; o.tInterruptSent = Date.now(); streamPcm(ws, pcm2); } }, 2500);
      }
      if (e.type === 'audio_output' && phase >= 2) { o.audioEventsAfterInterrupt++; o.lastAgentAudioAfterInterrupt = Date.now(); }
      if (e.type === 'turn_output_text_delta') { const d = (e.text || e.delta || ''); if (phase <= 1) o.answerText += d; else o.postText += d; }
      if (e.type === 'error') o.error = (e.message || e.code || 'agent error');
    };
    setTimeout(() => { try { ws.close(); } catch {}; }, 45000);
    setTimeout(resolve, 47000);
  });
  const continuedMs = (o.lastAgentAudioAfterInterrupt && o.tInterruptSent) ? o.lastAgentAudioAfterInterrupt - o.tInterruptSent : null;
  const issues = [];
  if (o.tAnswerStart === null) issues.push('agent never answered the question');
  if (o.tInterruptSent === null) issues.push('interrupt never sent');
  const full = (o.answerText + '|' + o.postText);
  console.log(JSON.stringify({ scenario: 'barge-in', callId: o.callId, greetingEndMs: o.tGreetingEnd, answerStartMs: o.tAnswerStart,
    audioContinuedAfterInterruptMs: continuedMs, audioEventsAfterInterrupt: o.audioEventsAfterInterrupt,
    answerText: o.answerText, postInterruptText: o.postText, answersCheckout: /checkout|check out|check-out/i.test(full), error: o.error, issues }, null, 1));
} else if (mode === 'run-caller') {
  const sc = CALLER_SCENARIOS[args.scenario];
  const out = await runCaller(args.agent, readFileSync(args.pcm));
  const t2 = out.agentTurn2.toLowerCase();
  const issues = [];
  if (sc.expectTextAny && !sc.expectTextAny.some((p) => t2.includes(p))) issues.push('turn2 missing any of ' + JSON.stringify(sc.expectTextAny) + ' | got: ' + t2.slice(0, 140));
  if (sc.expectNoBook && /book(ed)? (a |the )?table|table (is )?booked|reserv(ed|ation) (for )?dinner|done,? (i|your) table/i.test(out.agentTurn2)) issues.push('agent appears to have accepted the booking: ' + t2.slice(0, 140));
  console.log(JSON.stringify({ scenario: args.scenario, callId: out.callId, turn1Ms: out.t1Ms, turn2FirstTextMs: out.t2FirstTextMs, agentTurn1: out.agentTurn1, agentTurn2: out.agentTurn2, error: out.error, issues }, null, 1));
}
