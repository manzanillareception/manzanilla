import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeEnv } from './helpers.mjs';

const call = (env, path, body) => env.USERS.get({}).fetch('https://users.internal' + path, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}),
});

test('one active relay per user and a daily cap', async () => {
  const env = makeEnv();
  await call(env, '/touch', { sub: 'u1', email: 'u1@example.com' });
  await call(env, '/approve', { sub: 'u1', by: 'test' });
  let r = await (await call(env, '/acquire', { sub: 'u1', session_id: 's1', daily_cap: 2 })).json();
  assert.equal(r.ok, true);
  // second concurrent session blocked
  r = await (await call(env, '/acquire', { sub: 'u1', session_id: 's2', daily_cap: 2 })).json();
  assert.equal(r.error, 'active_session');
  await call(env, '/release', { sub: 'u1', session_id: 's1' });
  r = await (await call(env, '/acquire', { sub: 'u1', session_id: 's2', daily_cap: 2 })).json();
  assert.equal(r.ok, true);
  await call(env, '/release', { sub: 'u1', session_id: 's2' });
  // third session in the same day exceeds cap 2
  r = await (await call(env, '/acquire', { sub: 'u1', session_id: 's3', daily_cap: 2 })).json();
  assert.equal(r.error, 'daily_cap');
});

test('pending and revoked users cannot acquire a relay', async () => {
  const env = makeEnv();
  await call(env, '/touch', { sub: 'u2', email: 'u2@example.com' });
  let r = await (await call(env, '/acquire', { sub: 'u2', session_id: 's1' })).json();
  assert.equal(r.error, 'not_approved');
  assert.equal(r.status, 'pending');
  await call(env, '/approve', { sub: 'u2', by: 'test' });
  await call(env, '/revoke', { sub: 'u2', by: 'test' });
  r = await (await call(env, '/acquire', { sub: 'u2', session_id: 's1' })).json();
  assert.equal(r.status, 'revoked');
});

test('revocation clears an active session lease', async () => {
  const env = makeEnv();
  await call(env, '/touch', { sub: 'u3', email: 'u3@example.com' });
  await call(env, '/approve', { sub: 'u3', by: 'test' });
  await call(env, '/acquire', { sub: 'u3', session_id: 's1' });
  await call(env, '/revoke', { sub: 'u3', by: 'test' });
  const u = (await (await call(env, '/user?sub=u3', {})).json()).user;
  assert.equal(u.active, false);
});

test('unknown user cannot acquire', async () => {
  const env = makeEnv();
  const r = await call(env, '/acquire', { sub: 'ghost', session_id: 's1' });
  assert.equal(r.status, 404);
});

test('an aged-out lease is recovered instead of locking the user out', async () => {
  const env = makeEnv();
  await call(env, '/touch', { sub: 'u9', email: 'u9@example.com' });
  await call(env, '/approve', { sub: 'u9', by: 'test' });
  let r = await (await call(env, '/acquire', { sub: 'u9', session_id: 's1' })).json();
  assert.equal(r.ok, true);
  // abrupt isolate loss: release never ran, lease timestamp older than the 16 min TTL
  const u = env.__doStorage.map.get('u:u9');
  u.active_session.at = Date.now() - 17 * 60 * 1000;
  r = await (await call(env, '/acquire', { sub: 'u9', session_id: 's2' })).json();
  assert.equal(r.ok, true);
  assert.equal(r.sessions_today, 2);
  const audit = (await (await call(env, '/audit', {})).json()).audit;
  assert.ok(audit.some((e) => e.event === 'session_recovered' && e.stale_session_id === 's1'));
});

test('a fresh lease still blocks a second session', async () => {
  const env = makeEnv();
  await call(env, '/touch', { sub: 'u10', email: 'u10@example.com' });
  await call(env, '/approve', { sub: 'u10', by: 'test' });
  await call(env, '/acquire', { sub: 'u10', session_id: 's1' });
  // 15 minutes 59 seconds later: still inside the TTL, must block
  const u = env.__doStorage.map.get('u:u10');
  u.active_session.at = Date.now() - (16 * 60 * 1000 - 1000);
  const r = await (await call(env, '/acquire', { sub: 'u10', session_id: 's2' })).json();
  assert.equal(r.error, 'active_session');
});
