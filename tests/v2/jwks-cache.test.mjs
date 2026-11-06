// Regression: the worker must reuse one Google verifier per isolate so the
// in-memory JWKS cache survives across requests; building one per request
// refetched the JWKS document on every auth/admin call.
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import worker from '../../manzanilla-web/src/v2/worker.js';
import { makeFakeGoogle, makeEnv } from './helpers.mjs';

let google, env, realFetch, jwksFetches;

before(async () => {
  google = await makeFakeGoogle();
  env = makeEnv();
  jwksFetches = 0;
  realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    if (String(url).includes('certs')) jwksFetches += 1;
    return google.fetchImpl(url, init);
  };
});
after(() => { globalThis.fetch = realFetch; });

const post = (path, body) => worker.fetch(new Request('https://v2.example.com' + path, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}),
}), env, { waitUntil: () => {} });

test('repeated auth and admin calls fetch the JWKS document only once', async () => {
  const adminCred = await google.sign(google.basePayload({ sub: 'admin-1', email: 'admin@example.com' }));
  assert.equal((await post('/v2/auth', { credential: adminCred })).status, 200);
  assert.equal((await post('/v2/admin/list', { credential: adminCred })).status, 200);
  const userCred = await google.sign(google.basePayload({ sub: 'user-j', email: 'j@example.com' }));
  await post('/v2/auth', { credential: userCred });
  assert.equal(jwksFetches, 1);
});
