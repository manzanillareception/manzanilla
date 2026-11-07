const { test } = require('node:test');
const assert = require('node:assert/strict');

const workerModule = import('../manzanilla-web/src/index.js');

function forbidUpstream(t) {
  return t.mock.method(globalThis, 'fetch', async () => {
    throw new Error('retired route attempted an upstream request');
  });
}

function retiredEnv() {
  return {
    ACCESS_KEY: 'local-test-access',
    get BUDGET() { throw new Error('retired route accessed a stored session'); }
  };
}

test('retired public session paths never return cached tokens or contact a provider', async t => {
  const { default: worker } = await workerModule;
  const upstream = forbidUpstream(t);
  for (const path of ['/api/session', '/api/session/end', '/api/state', '/api/test-reset']) {
    for (const method of ['GET', 'POST']) {
      const response = await worker.fetch(new Request(`https://local.test${path}?k=local-test-access`, {
        method, ...(method === 'POST' ? { body: 'invalid json is irrelevant for a retired route' } : {})
      }), retiredEnv());
      assert.equal(response.status, 410, `${method} ${path}`);
      assert.equal((await response.json()).error, 'legacy_voice_route_removed');
      assert.equal(response.headers.get('cache-control'), 'no-store');
    }
  }
  assert.equal(upstream.mock.callCount(), 0);
});

test('retired public routes keep their access gate', async t => {
  const { default: worker } = await workerModule;
  const upstream = forbidUpstream(t);
  for (const path of ['/api/session', '/api/session/end', '/api/state', '/api/test-reset']) {
    const response = await worker.fetch(new Request(`https://local.test${path}`, { method: 'POST' }), retiredEnv());
    assert.equal(response.status, 403);
  }
  assert.equal(upstream.mock.callCount(), 0);
});

test('retired Durable Object paths cannot mint, disclose or reset old stored sessions', async t => {
  const { BudgetDO } = await workerModule;
  const upstream = forbidUpstream(t);
  const storage = new Proxy({}, {
    get() { throw new Error('retired route touched retained session data'); }
  });
  const object = new BudgetDO({ storage }, { TEST_MODE: 'True' });
  for (const path of ['/mint', '/end', '/state', '/test-reset']) {
    const response = await object.fetch(new Request(`https://do${path}`, { method: 'POST', body: '{}' }));
    assert.equal(response.status, 410, path);
    assert.equal((await response.json()).error, 'legacy_voice_route_removed');
  }
  assert.equal(upstream.mock.callCount(), 0);
});

test('existing Cartesia admission keeps its state and leaves old records untouched', async t => {
  const { BudgetDO } = await workerModule;
  const upstream = forbidUpstream(t);
  const legacyRecord = { status: 'done', body: '{"client_token":"old-provider-token"}' };
  const values = new Map([['req:old-request', legacyRecord]]);
  const alarms = [];
  const storage = {
    async get(key) { return structuredClone(values.get(key)); },
    async put(key, value) { values.set(key, structuredClone(value)); },
    async setAlarm(when) { alarms.push(when); }
  };
  const object = new BudgetDO({ storage }, {});
  const response = await object.fetch(new Request('https://do/mint-cartesia', {
    method: 'POST', body: JSON.stringify({ mode: 'night', guest: 'en', request_id: 'local-request-123' })
  }));
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { rid: 'local-request-123' });
  assert.deepEqual(values.get('req:old-request'), legacyRecord);
  assert.equal(values.get('vc').sessions, 1);
  assert.equal(alarms.length, 1);
  assert.equal(upstream.mock.callCount(), 0);
});

test('existing Cartesia session endpoint still forwards to its original admission path', async t => {
  const { default: worker } = await workerModule;
  const upstream = forbidUpstream(t);
  const forwarded = [];
  const env = {
    ACCESS_KEY: 'local-test-access',
    BUDGET: {
      idFromName(name) { assert.equal(name, 'global'); return 'object-id'; },
      get(id) {
        assert.equal(id, 'object-id');
        return { async fetch(request) {
          forwarded.push({ path: new URL(request.url).pathname, body: await request.json() });
          return new Response('{"rid":"existing-session"}', { headers: { 'content-type': 'application/json' } });
        } };
      }
    }
  };
  const body = { mode: 'night', guest: 'en', request_id: 'existing-session' };
  const response = await worker.fetch(new Request('https://local.test/api/cartesia-session?k=local-test-access', {
    method: 'POST', body: JSON.stringify(body)
  }), env);
  assert.equal(response.status, 200);
  assert.deepEqual(forwarded, [{ path: '/mint-cartesia', body }]);
  assert.equal(upstream.mock.callCount(), 0);
});
