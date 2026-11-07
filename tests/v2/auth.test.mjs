import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createGoogleVerifier, createSessionTokens, AuthError } from '../../manzanilla-web/src/v2/auth.js';
import { makeFakeGoogle } from './helpers.mjs';

const AUDS = ['web-client-id', 'android-client-id'];

async function verifierFor(google, over = {}) {
  return createGoogleVerifier(Object.assign({ audiences: AUDS, fetchImpl: google.fetchImpl }, over));
}

test('valid Google ID token verifies (web audience)', async () => {
  const g = await makeFakeGoogle();
  const tok = await g.sign(g.basePayload());
  const id = await (await verifierFor(g))(tok);
  assert.equal(id.sub, 'user-1');
  assert.equal(id.email, 'guest@example.com');
  assert.equal(id.emailVerified, true);
});

test('valid Google ID token verifies (android audience)', async () => {
  const g = await makeFakeGoogle();
  const tok = await g.sign(g.basePayload({ aud: 'android-client-id' }));
  const id = await (await verifierFor(g))(tok);
  assert.equal(id.sub, 'user-1');
});

test('rejects a token for an unknown audience', async () => {
  const g = await makeFakeGoogle();
  const tok = await g.sign(g.basePayload({ aud: 'someone-elses-client' }));
  const verify = await verifierFor(g);
  await assert.rejects(() => verify(tok), (e) => e instanceof AuthError && e.message === 'bad_aud');
});

test('rejects the wrong issuer', async () => {
  const g = await makeFakeGoogle();
  const tok = await g.sign(g.basePayload({ iss: 'https://evil.example.com' }));
  await assert.rejects(async () => (await verifierFor(g))(tok), (e) => e.message === 'bad_iss');
});

test('rejects an expired token', async () => {
  const g = await makeFakeGoogle();
  const now = Math.floor(Date.now() / 1000);
  const tok = await g.sign(g.basePayload({ iat: now - 3600, exp: now - 60 }));
  await assert.rejects(async () => (await verifierFor(g))(tok), (e) => e.message === 'expired');
});

test('rejects a forged signature', async () => {
  const g = await makeFakeGoogle();
  const other = await makeFakeGoogle();
  const tok = await g.sign(g.basePayload(), { key: other.privateKey });
  await assert.rejects(async () => (await verifierFor(g))(tok), (e) => e.message === 'bad_signature');
});

test('rejects a token signed for an unknown key id', async () => {
  const g = await makeFakeGoogle();
  const tok = await g.sign(g.basePayload(), { kid: 'no-such-kid' });
  await assert.rejects(async () => (await verifierFor(g))(tok), (e) => e.message === 'unknown_kid');
});

test('rejects a tampered payload even with a valid-looking shape', async () => {
  const g = await makeFakeGoogle();
  const tok = await g.sign(g.basePayload());
  const [h, , s] = tok.split('.');
  const forged = h + '.' + Buffer.from(JSON.stringify(g.basePayload({ email: 'admin@example.com' }))).toString('base64url') + '.' + s;
  await assert.rejects(async () => (await verifierFor(g))(forged), (e) => e.message === 'bad_signature');
});

test('session tokens: issue then verify; tamper and expiry rejected', async () => {
  const t = createSessionTokens({ secret: 'x'.repeat(40), ttlMs: 1000 });
  const tok = await t.issue({ sub: 'user-1', email: 'a@b.c' });
  const p = await t.verify(tok);
  assert.equal(p.sub, 'user-1');
  await assert.rejects(() => t.verify(tok.slice(0, -2) + 'zz'), (e) => e.message === 'bad_session_signature');
  const short = createSessionTokens({ secret: 'x'.repeat(40), ttlMs: -1 });
  const expired = await short.issue({ sub: 'user-1', email: 'a@b.c' });
  await assert.rejects(() => short.verify(expired), (e) => e.message === 'session_expired');
  const other = createSessionTokens({ secret: 'y'.repeat(40) });
  await assert.rejects(() => other.verify(tok), (e) => e.message === 'bad_session_signature');
});
