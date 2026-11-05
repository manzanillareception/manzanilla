import { readFileSync } from 'fs';
const src = readFileSync('manzanilla-web/src/index.js', 'utf8');
const m = src.match(/\/\*PLACECALL-BEGIN\*\/([\s\S]*?)\/\*PLACECALL-END\*\//);
if (!m) throw new Error('markers missing');
const { validatePlaceCallInput, buildOutboundPayload, handlePlaceCall } =
  new Function(m[1].replace(/__name\(([^,]+), "[^"]+"\);/g, '') + '; return { validatePlaceCallInput, buildOutboundPayload, handlePlaceCall };')();

let pass = 0, fail = 0;
function t(name, fn){ Promise.resolve().then(fn).then(()=>{pass++;console.log('PASS',name);}).catch(e=>{fail++;console.log('FAIL',name,'-',e.message);}); }
function eq(a,b,w){ if (JSON.stringify(a)!==JSON.stringify(b)) throw new Error(w+': got '+JSON.stringify(a)+' want '+JSON.stringify(b)); }
const env = { CARTESIA_API_KEY: 'TESTKEY', CARTESIA_FROM_NUMBER_ID: 'ap_TEST', CARTESIA_AGENT_CALLER: 'agent_TEST' };
const okBody = JSON.stringify({ to_number: '+34612345678', question: 'Why are you not coming on time?', guest_name: 'Room 204' });

await t('valid input passes validation, guest trimmed', () => {
  const v = validatePlaceCallInput({ to_number: ' +34612345678 ', question: 'Q?', guest_name: 'x'.repeat(150) });
  if (!v.ok) throw new Error('should pass');
  eq(v.to_number, '+34612345678', 'to');
  if (v.guest_name.length !== 100) throw new Error('guest not trimmed to 100');
});
await t('rejects non-E164 numbers', () => {
  for (const bad of ['0612345678', '+', '+0123', 'abc', '+34 612 345 678', '+346123456789012345']) {
    const v = validatePlaceCallInput({ to_number: bad, question: 'q' });
    if (v.ok) throw new Error('accepted ' + bad);
    eq(v.error, 'bad_to_number_e164', 'error kind for ' + bad);
  }
});
await t('rejects empty/oversized question', () => {
  if (validatePlaceCallInput({ to_number: '+34612345678', question: '  ' }).ok) throw new Error('empty ok');
  if (validatePlaceCallInput({ to_number: '+34612345678', question: 'x'.repeat(501) }).ok) throw new Error('long ok');
});
await t('payload carries question verbatim as dynamic variable', () => {
  const p = buildOutboundPayload(env, validatePlaceCallInput(JSON.parse(okBody)));
  eq(p.body.outbound_calls[0].dynamic_variables.question, 'Why are you not coming on time?', 'question verbatim');
  eq(p.body.outbound_calls[0].to_number, '+34612345678', 'to');
  eq(p.body.from_number_id, 'ap_TEST', 'from id');
  eq(p.headers['Cartesia-Version'], '2026-08-14', 'version default');
});
await t('503 calling_not_configured when env missing, no fetch attempted', async () => {
  let called = false;
  const r = await handlePlaceCall(okBody, { CARTESIA_API_KEY: 'K' }, async () => { called = true; });
  eq(r.status, 503, 'status'); eq(r.out.error, 'calling_not_configured', 'error');
  if (called) throw new Error('fetch attempted without config');
});
await t('provider per-call error surfaced, not hidden', async () => {
  const r = await handlePlaceCall(okBody, env, async () => ({ status: 200, json: async () => ({ calls: [{ number: '+34612345678', error: 'destination_not_allowed' }] }) }));
  eq(r.status, 200, 'status'); eq(r.out.error, 'destination_not_allowed', 'error');
});
await t('success returns agent_call_id', async () => {
  const r = await handlePlaceCall(okBody, env, async () => ({ status: 200, json: async () => ({ calls: [{ number: '+34612345678', agent_call_id: 'ac_123' }] }) }));
  eq(r.out, { agent_call_id: 'ac_123', to_number: '+34612345678' }, 'out');
});
await t('provider non-2xx -> 502 provider_rejected', async () => {
  const r = await handlePlaceCall(okBody, env, async () => ({ status: 429, json: async () => ({}) }));
  eq(r.status, 502, 'status'); eq(r.out.provider_status, 429, 'provider_status');
});
await t('network throw -> 502 provider_unreachable', async () => {
  const r = await handlePlaceCall(okBody, env, async () => { throw new Error('boom'); });
  eq(r.out.error, 'provider_unreachable', 'error');
});
await t('bad json -> 400', async () => {
  const r = await handlePlaceCall('{not json', env, async () => {});
  eq(r.status, 400, 'status');
});
setTimeout(() => { console.log(fail === 0 ? `ALL ${pass} PASS` : `${fail} FAILURES`); process.exit(fail === 0 ? 0 : 1); }, 200);
