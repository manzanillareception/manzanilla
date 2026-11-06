// Barge-in attribution run: logs EVERY event with ms offsets to JSONL.
import { readFileSync, writeFileSync, appendFileSync } from 'fs';
const KEY = readFileSync(process.argv[2], 'utf8').trim();
const AGENT = process.argv[3];
const LOG = process.argv[4];
writeFileSync(LOG, '');
const t0 = Date.now();
const log = (type, extra) => appendFileSync(LOG, JSON.stringify({ t: Date.now() - t0, type, ...extra }) + '\n');
const pcmLong = readFileSync(process.argv[5] || '/tmp/manz-harness/nd-taxi.pcm');

const ws = new WebSocket('wss://api.cartesia.ai/v1/agents/websocket/' + AGENT + '?cartesia_version=2026-08-14', { headers: { 'X-API-Key': KEY } });
let greeted = false, answering = false, interrupted = false, intAudioEnd = null, lastAgentAudio = null;
function stream(pcm, tag, done) {
  const CH = 1280; let off = 0; log('stream_start', { tag });
  const timer = setInterval(() => {
    if (off >= pcm.length) { clearInterval(timer); log('stream_end', { tag });
      const sil = Buffer.alloc(CH); let n = 0;
      const st = setInterval(() => { if (++n > 75) { clearInterval(st); log('silence_end', { tag }); done && done(); return; }
        if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'audio_input', audio: sil.toString('base64') })); }, 20);
      return; }
    const chunk = pcm.subarray(off, off + CH); off += CH;
    if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'audio_input', audio: Buffer.from(chunk).toString('base64') }));
  }, 20);
}
ws.onopen = () => { log('open'); ws.send(JSON.stringify({ type: 'session_create', audio: { input_format: 'pcm_16000', output_delivery: 'as_available', continuous_input: true } })); };
ws.onmessage = (m) => {
  let e; try { e = JSON.parse(m.data); } catch { return; }
  const ty = e.type;
  if (ty === 'audio_output') { lastAgentAudio = Date.now() - t0; if (!(Date.now() % 2)) log('audio_output', { n: (e.audio || '').length }); }
  else if (ty === 'turn_output_text_delta') log('text_delta', { d: (e.text || e.delta || '').slice(0, 40) });
  else log(ty, e.call_id ? { call_id: e.call_id } : e.message ? { message: String(e.message).slice(0, 120) } : {});
  if (ty === 'turn_ended' && !greeted) { greeted = true; stream(pcmLong, 'long'); }
  if (ty === 'turn_output_text_delta' && greeted && !answering) {
    answering = true;

  }
  if (intAudioEnd && Date.now() - t0 > intAudioEnd + 12000) { log('done'); process.exit(0); }
};
setTimeout(() => { log('global_timeout'); process.exit(0); }, 42000);
setInterval(() => { if (intAudioEnd && Date.now() - t0 > intAudioEnd + 12000) { log('done'); process.exit(0); } }, 1000);
