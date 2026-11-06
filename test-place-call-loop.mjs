// Full-loop test: real worker fetch handler + real Request/Response, Cartesia API mocked.
import worker from './manzanilla-web/src/index.js';

let pass = 0, fail = 0;
async function t(name, fn){ try { await fn(); pass++; console.log('PASS', name); } catch(e){ fail++; console.log('FAIL', name, '-', e.message); } }
function assert(c, m){ if (!c) throw new Error(m); }

const upstream = [];
let upstreamBehavior = async () => new Response(JSON.stringify({ calls: [{ agent_call_id: 'ac_MOCK', to_number: '+34612345678' }] }), { status: 200 });
globalThis.fetch = async (url, opts) => { upstream.push({ url: String(url), opts }); return upstreamBehavior(); };

const env = {
  ACCESS_KEY: 'k',
  CALLING_WEBHOOK_SECRET: 'test-secret',
  CARTESIA_API_KEY: 'TESTKEY',
  CARTESIA_FROM_NUMBER_ID: 'ap_TEST',
  CARTESIA_AGENT_CALLER: 'agent_TEST',
  BUDGET: { idFromName: () => 'g', get: () => ({ fetch: async () => new Response('{}') }) },
};
const req = (body, auth, method='POST') => new Request('https://worker.local/api/place-call', {
  method, body: body === null ? null : JSON.stringify(body),
  headers: auth ? { authorization: auth, 'content-type': 'application/json' } : { 'content-type': 'application/json' },
});
const okBody = { to_number: '+34612345678', question: 'Why are you not coming on time?', guest_name: 'Room 204' };

await t('full loop: valid POST reaches provider with correct payload, returns agent_call_id', async () => {
  upstream.length = 0;
  const r = await worker.fetch(req(okBody, 'Bearer test-secret'), env, {});
  assert(r.status === 200, 'status ' + r.status);
  const j = await r.json();
  assert(j.agent_call_id === 'ac_MOCK', 'missing call id: ' + JSON.stringify(j));
  assert(upstream.length === 1, 'expected 1 upstream call, got ' + upstream.length);
  const u = upstream[0];
  assert(u.url.includes('api.cartesia.ai') && u.url.includes('calls'), 'upstream url ' + u.url);
  assert(u.opts.method === 'POST', 'upstream method');
  const ub = JSON.parse(u.opts.body);
  const oc = ub.outbound_calls && ub.outbound_calls[0] || {};
  assert(oc.to_number === '+34612345678', 'upstream to_number ' + JSON.stringify(ub));
  assert(ub.agent_id === 'agent_TEST' && ub.from_number_id === 'ap_TEST', 'agent/number id ' + JSON.stringify(ub));
  assert(oc.dynamic_variables && oc.dynamic_variables.question === okBody.question, 'question not passed as dynamic variable');
  const hdrs = u.opts.headers || {};
  const key = hdrs['X-API-Key'] || hdrs['x-api-key'] || hdrs['Authorization'];
  assert(key && String(key).includes('TESTKEY'), 'api key header missing');
});
await t('full loop: missing auth -> 401, no upstream', async () => {
  upstream.length = 0;
  const r = await worker.fetch(req(okBody, null), env, {});
  assert(r.status === 401, 'status ' + r.status);
  assert(upstream.length === 0, 'upstream called without auth');
});
await t('full loop: wrong secret -> 401, no upstream', async () => {
  upstream.length = 0;
  const r = await worker.fetch(req(okBody, 'Bearer wrong'), env, {});
  assert(r.status === 401, 'status ' + r.status);
  assert(upstream.length === 0, 'upstream called with wrong secret');
});
await t('full loop: env secret unset -> fail-closed 401', async () => {
  const env2 = { ...env }; delete env2.CALLING_WEBHOOK_SECRET;
  const r = await worker.fetch(req(okBody, 'Bearer test-secret'), env2, {});
  assert(r.status === 401, 'status ' + r.status);
});
await t('full loop: bad E.164 -> 400, no upstream', async () => {
  upstream.length = 0;
  const r = await worker.fetch(req({ ...okBody, to_number: '+346123456789012345' }, 'Bearer test-secret'), env, {});
  assert(r.status === 400, 'status ' + r.status);
  const j = await r.json();
  assert(j.error === 'bad_to_number_e164', 'error ' + JSON.stringify(j));
  assert(upstream.length === 0, 'upstream called for bad number');
});
await t('full loop: provider 429 with secret-laden body -> 502 redacted', async () => {
  upstreamBehavior = async () => new Response(JSON.stringify({ secret: 'test-secret', detail: 'rate limited' }), { status: 429 });
  const r = await worker.fetch(req(okBody, 'Bearer test-secret'), env, {});
  assert(r.status === 502, 'status ' + r.status);
  const txt = await r.text();
  assert(!txt.includes('rate limited') && !txt.includes('test-secret'), 'leaked provider body: ' + txt);
  upstreamBehavior = async () => new Response(JSON.stringify({ calls: [{ agent_call_id: 'ac_MOCK' }] }), { status: 200 });
});
await t('full loop: provider error per-call object surfaces as 200 with error text', async () => {
  upstreamBehavior = async () => new Response(JSON.stringify({ calls: [{ error: 'line busy' }] }), { status: 200 });
  const r = await worker.fetch(req(okBody, 'Bearer test-secret'), env, {});
  assert(r.status === 200, 'status ' + r.status);
  const j = await r.json();
  assert(j.error === 'line busy', 'error passthrough ' + JSON.stringify(j));
  upstreamBehavior = async () => new Response(JSON.stringify({ calls: [{ agent_call_id: 'ac_MOCK' }] }), { status: 200 });
});
console.log(`LOOP: ${pass} pass, ${fail} fail`);
process.exit(fail ? 1 : 0);
