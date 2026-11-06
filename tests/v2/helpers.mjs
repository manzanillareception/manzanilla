// Test helpers: a fake Google issuer (real RSA signatures, mocked JWKS fetch)
// and an in-memory Durable Object namespace for UsersDO.
import { UsersDO } from '../../manzanilla-web/src/v2/users.js';
import { bytesToB64url } from '../../manzanilla-web/src/v2/auth.js';

const enc = new TextEncoder();

export async function makeFakeGoogle() {
  const pair = await crypto.subtle.generateKey({ name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' }, true, ['sign', 'verify']);
  const jwk = await crypto.subtle.exportKey('jwk', pair.publicKey);
  jwk.kid = 'test-key-1';
  jwk.alg = 'RS256';
  jwk.use = 'sig';
  const jwks = { keys: [jwk] };
  const fetchImpl = async (url) => {
    if (String(url).includes('certs')) return new Response(JSON.stringify(jwks), { status: 200 });
    return new Response('not found', { status: 404 });
  };
  async function sign(payload, { kid = 'test-key-1', key = pair.privateKey } = {}) {
    const header = bytesToB64url(enc.encode(JSON.stringify({ alg: 'RS256', kid, typ: 'JWT' })));
    const body = bytesToB64url(enc.encode(JSON.stringify(payload)));
    const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, enc.encode(header + '.' + body));
    return header + '.' + body + '.' + bytesToB64url(new Uint8Array(sig));
  }
  const now = Math.floor(Date.now() / 1000);
  const basePayload = (over = {}) => Object.assign({
    iss: 'https://accounts.google.com',
    aud: 'web-client-id',
    sub: 'user-1',
    email: 'guest@example.com',
    email_verified: true,
    name: 'Test Guest',
    iat: now - 10,
    exp: now + 600,
  }, over);
  return { sign, basePayload, fetchImpl, privateKey: pair.privateKey };
}

class MemoryStorage {
  constructor() { this.map = new Map(); }
  async get(k) { return this.map.get(k); }
  async put(k, v) { this.map.set(k, structuredClone(v)); }
  async list({ prefix } = {}) { const out = new Map(); for (const [k, v] of this.map) if (!prefix || k.startsWith(prefix)) out.set(k, v); return out; }
}

export function makeEnv() {
  const doState = { storage: new MemoryStorage() };
  const instance = new UsersDO(doState);
  return {
    // test-only handle to pre-seed DO state (e.g. aging a session lease)
    __doStorage: doState.storage,
    GOOGLE_CLIENT_IDS: 'web-client-id,android-client-id',
    ADMIN_EMAILS: 'admin@example.com',
    SESSION_SECRET: 'test-session-secret-test-session-secret-0123456789',
    CARTESIA_API_KEY: 'not-a-real-key',
    CARTESIA_AGENT_NIGHT: 'agent_night_test',
    CARTESIA_VERSION: '2026-08-14',
    USERS: {
      idFromName: () => ({}),
      get: () => ({ fetch: (url, init) => instance.fetch(new Request(url, init)) }),
    },
  };
}
