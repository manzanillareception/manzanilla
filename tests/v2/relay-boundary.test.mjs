// Real boundary test: the v2 worker runs in workerd (miniflare), the client is
// a real WebSocket over HTTP upgrade, and the Cartesia upstream is a local WS
// server. Covers the auth-then-bridge path, mid-call revocation actually
// killing an in-flight relay, and pre-bridge denials.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { WebSocketServer } from 'ws';
import { Miniflare } from 'miniflare';
import { makeFakeGoogle } from './helpers.mjs';

let google, jwksServer, upstreamServer, upstreamWss, mf, base, lastUpstream;

before(async () => {
  google = await makeFakeGoogle();
  const jwks = await google.fetchImpl('https://www.googleapis.com/oauth2/v3/certs').then((r) => r.json());

  // One HTTP server serving fake Google JWKS; one WS server as the Cartesia upstream.
  jwksServer = http.createServer((req, res) => {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(jwks));
  });
  await new Promise((r) => jwksServer.listen(0, '127.0.0.1', r));

  upstreamServer = http.createServer((req, res) => { res.writeHead(404); res.end(); });
  upstreamWss = new WebSocketServer({ server: upstreamServer });
  upstreamWss.on('connection', (ws, req) => {
    lastUpstream = { ws, auth: req.headers['cartesia-api-key'] || null, url: req.url };
    ws.on('message', (d) => ws.send(d)); // echo
  });
  await new Promise((r) => upstreamServer.listen(0, '127.0.0.1', r));

  mf = new Miniflare({
    modules: [
      { type: 'ESModule', path: 'manzanilla-web/src/v2/worker.js' },
      { type: 'ESModule', path: 'manzanilla-web/src/v2/auth.js' },
      { type: 'ESModule', path: 'manzanilla-web/src/v2/users.js' },
    ],
    durableObjects: { USERS: { className: 'UsersDO' } },
    compatibilityDate: '2025-10-01',
    bindings: {
      GOOGLE_CLIENT_IDS: 'web-client-id,android-client-id',
      ADMIN_EMAILS: 'admin@example.com',
      SESSION_SECRET: 'boundary-test-secret-boundary-test-secret-42',
      CARTESIA_API_KEY: 'upstream-key-check',
      CARTESIA_AGENT_NIGHT: 'agent_night_test',
      CARTESIA_VERSION: '2026-08-14',
      V2_JWKS_URI: `http://127.0.0.1:${jwksServer.address().port}/certs`,
      CARTESIA_UPSTREAM_WS_BASE: `ws://127.0.0.1:${upstreamServer.address().port}`,
      V2_AUTH_TIMEOUT_MS: '2000',
    },
  });
  base = await mf.ready;
});

after(async () => {
  await mf.dispose();
  await new Promise((r) => jwksServer.close(r));
  await new Promise((r) => upstreamServer.close(r));
});

const auth = (over) => mf.dispatchFetch(base + 'v2/auth', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ credential: null, ...over }),
});
async function sessionFor(sub, email) {
  const credential = await google.sign(google.basePayload({ sub, email }));
  const r = await auth({ credential });
  return { status: r.status, body: await r.json() };
}
async function approve(sub) {
  const credential = await google.sign(google.basePayload({ sub: 'admin-1', email: 'admin@example.com' }));
  return mf.dispatchFetch(base + 'v2/admin/approve', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ credential, sub }),
  });
}
async function revoke(sub) {
  const credential = await google.sign(google.basePayload({ sub: 'admin-1', email: 'admin@example.com' }));
  return mf.dispatchFetch(base + 'v2/admin/revoke', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ credential, sub }),
  });
}
function connectRelay() {
  return mf.dispatchFetch(base + 'v2/relay', { headers: { Upgrade: 'websocket' } });
}
function nextMsg(ws, ms = 5000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('msg_timeout')), ms);
    const h = (ev) => {
      clearTimeout(t); ws.removeEventListener('message', h);
      const d = typeof ev.data === 'string' ? ev.data : new TextDecoder().decode(ev.data);
      resolve(d);
    };
    ws.addEventListener('message', h);
  });
}
function closed(ws, ms = 15000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('close_timeout')), ms);
    ws.addEventListener('close', (ev) => { clearTimeout(t); resolve({ code: ev.code, reason: ev.reason }); }, { once: true });
  });
}

test('approved user bridges to upstream; upstream got the key header; echo works', async () => {
  const { status, body } = await sessionFor('relay-u1', 'u1@example.com');
  assert.equal(status, 403); // pending first
  assert.equal((await approve('relay-u1')).status, 200);
  const again = await sessionFor('relay-u1', 'u1@example.com');
  assert.equal(again.status, 200);
  const res = await connectRelay();
  const ws = res.webSocket;
  assert.ok(ws, 'upgrade accepted');
  ws.accept();
  ws.send(JSON.stringify({ type: 'auth', token: again.body.session }));
  const ready = JSON.parse(await nextMsg(ws));
  assert.equal(ready.type, 'session_ready');
  ws.send('audio-frame-1');
  assert.equal(await nextMsg(ws), 'audio-frame-1'); // echoed via real upstream
  assert.equal(lastUpstream.auth, 'upstream-key-check'); // key only on upstream handshake
  assert.ok(lastUpstream.url.includes('/v1/agents/websocket/agent_night_test'));
  ws.close();
});

test('mid-call revocation terminates the in-flight relay with 4403', async () => {
  await sessionFor('relay-u2', 'u2@example.com');
  await approve('relay-u2');
  const s = await sessionFor('relay-u2', 'u2@example.com');
  const res = await connectRelay();
  const ws = res.webSocket;
  ws.accept();
  ws.send(JSON.stringify({ type: 'auth', token: s.body.session }));
  await nextMsg(ws); // session_ready
  const closedP = closed(ws);
  await revoke('relay-u2');
  const c = await closedP; // poll interval is 10s; close must arrive within it
  assert.equal(c.code, 4403);
  assert.equal(c.reason, 'access_revoked');
});

test('session of a user revoked before connect cannot open a relay', async () => {
  await sessionFor('relay-u3', 'u3@example.com');
  await approve('relay-u3');
  const s = await sessionFor('relay-u3', 'u3@example.com');
  await revoke('relay-u3');
  const res = await connectRelay();
  const ws = res.webSocket;
  ws.accept();
  const errP = nextMsg(ws);
  const closedP = closed(ws);
  ws.send(JSON.stringify({ type: 'auth', token: s.body.session }));
  const err = JSON.parse(await errP);
  assert.equal(err.error, 'not_approved');
  assert.equal((await closedP).code, 4401);
});

test('bad session token is rejected at the relay boundary', async () => {
  const res = await connectRelay();
  const ws = res.webSocket;
  ws.accept();
  const errP = nextMsg(ws);
  const closedP = closed(ws);
  ws.send(JSON.stringify({ type: 'auth', token: 'forged.token' }));
  assert.ok((await errP).includes('error'));
  assert.equal((await closedP).code, 4401);
});

test('no auth message within the timeout closes the socket', async () => {
  const res = await connectRelay();
  const ws = res.webSocket;
  ws.accept();
  const c = await closed(ws, 8000); // V2_AUTH_TIMEOUT_MS=2000
  assert.equal(c.code, 4401);
});

test('one active relay per user across the real boundary', async () => {
  await sessionFor('relay-u4', 'u4@example.com');
  await approve('relay-u4');
  const s = await sessionFor('relay-u4', 'u4@example.com');
  const ws1 = (await connectRelay()).webSocket; ws1.accept();
  ws1.send(JSON.stringify({ type: 'auth', token: s.body.session }));
  await nextMsg(ws1);
  const ws2 = (await connectRelay()).webSocket; ws2.accept();
  const errP = nextMsg(ws2);
  const closedP = closed(ws2);
  ws2.send(JSON.stringify({ type: 'auth', token: s.body.session }));
  assert.equal(JSON.parse(await errP).error, 'active_session');
  assert.equal((await closedP).code, 4401);
  ws1.close();
});
