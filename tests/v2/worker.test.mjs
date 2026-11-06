import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../../manzanilla-web/src/v2/worker.js';
import { makeFakeGoogle, makeEnv } from './helpers.mjs';

let google, env, realFetch;

before(async () => {
  google = await makeFakeGoogle();
  env = makeEnv();
  realFetch = globalThis.fetch;
  globalThis.fetch = google.fetchImpl; // worker resolves Google JWKS through global fetch
});
after(() => { globalThis.fetch = realFetch; });

const post = (path, body) => worker.fetch(new Request('https://v2.example.com' + path, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}),
}), env, { waitUntil: () => {} });

const googleToken = (over) => google.sign(google.basePayload(over));

test('health is public', async () => {
  const r = await worker.fetch(new Request('https://v2.example.com/v2/health'), env, { waitUntil: () => {} });
  assert.equal(r.status, 200);
});

test('new non-admin user is pending and gets no session', async () => {
  const r = await post('/v2/auth', { credential: await googleToken({ sub: 'user-new', email: 'newbie@example.com' }) });
  assert.equal(r.status, 403);
  assert.equal((await r.json()).status, 'pending');
});

test('garbage credential is rejected', async () => {
  const r = await post('/v2/auth', { credential: 'not-a-jwt' });
  assert.equal(r.status, 401);
});

test('admin allowlist email is auto-approved and receives a session', async () => {
  const r = await post('/v2/auth', { credential: await googleToken({ sub: 'admin-1', email: 'admin@example.com' }) });
  assert.equal(r.status, 200);
  const b = await r.json();
  assert.equal(b.status, 'approved');
  assert.equal(typeof b.session, 'string');
});

test('admin approves a pending user; user then gets a session', async () => {
  const cred = await googleToken({ sub: 'user-2', email: 'two@example.com' });
  assert.equal((await post('/v2/auth', { credential: cred })).status, 403);
  const adminCred = await googleToken({ sub: 'admin-1', email: 'admin@example.com' });
  const ap = await post('/v2/admin/approve', { credential: adminCred, sub: 'user-2' });
  assert.equal(ap.status, 200);
  const r2 = await post('/v2/auth', { credential: cred });
  assert.equal(r2.status, 200);
  assert.equal((await r2.json()).status, 'approved');
});

test('revoked user loses access even with a valid Google token', async () => {
  const adminCred = await googleToken({ sub: 'admin-1', email: 'admin@example.com' });
  await post('/v2/admin/revoke', { credential: adminCred, sub: 'user-2' });
  const r = await post('/v2/auth', { credential: await googleToken({ sub: 'user-2', email: 'two@example.com' }) });
  assert.equal(r.status, 403);
  assert.equal((await r.json()).status, 'revoked');
});

test('non-admin cannot use admin endpoints', async () => {
  const r = await post('/v2/admin/list', { credential: await googleToken({ sub: 'user-new', email: 'newbie@example.com' }) });
  assert.equal(r.status, 401);
});

test('admin can list users and read the audit log', async () => {
  const adminCred = await googleToken({ sub: 'admin-1', email: 'admin@example.com' });
  const list = await (await post('/v2/admin/list', { credential: adminCred })).json();
  assert.ok(list.users.some((u) => u.sub === 'user-2' && u.status === 'revoked'));
  const audit = await (await post('/v2/admin/audit', { credential: adminCred })).json();
  assert.ok(audit.audit.some((e) => e.event === 'revoked' && e.sub === 'user-2'));
});

test('relay requires a websocket upgrade', async () => {
  const r = await worker.fetch(new Request('https://v2.example.com/v2/relay'), env, { waitUntil: () => {} });
  assert.equal(r.status, 426);
});

test('unknown routes 404', async () => {
  const r = await worker.fetch(new Request('https://v2.example.com/v2/nope'), env, { waitUntil: () => {} });
  assert.equal(r.status, 404);
});
