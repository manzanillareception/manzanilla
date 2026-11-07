// V2 authentication primitives: Google ID-token verification (RS256/JWKS) and
// short-lived HMAC session tokens. Web Crypto only; runs on Cloudflare Workers
// and Node 18+. No secrets, no network beyond the JWKS document.

export class AuthError extends Error {
  constructor(message) { super(message); this.name = 'AuthError'; }
}

const enc = new TextEncoder();
const dec = new TextDecoder();

export function b64urlToBytes(s) {
  s = String(s).replace(/-/g, '+').replace(/_/g, '/');
  const pad = '='.repeat((4 - (s.length % 4)) % 4);
  const bin = atob(s + pad);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}
export function bytesToB64url(b) {
  let bin = '';
  for (const x of b) bin += String.fromCharCode(x);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function parseJson(bytes, what) {
  try { return JSON.parse(dec.decode(bytes)); } catch { throw new AuthError('malformed_' + what); }
}

// Verifies Google-issued ID tokens. audiences: array of accepted OAuth client IDs
// (web client + Android client). JWKS responses are cached in memory.
export function createGoogleVerifier({
  audiences,
  jwksUri = 'https://www.googleapis.com/oauth2/v3/certs',
  fetchImpl = (...a) => fetch(...a),
  nowMs = () => Date.now(),
  cacheTtlMs = 3600e3,
} = {}) {
  if (!Array.isArray(audiences) || audiences.length === 0) throw new AuthError('no_audiences_configured');
  let cache = { keys: null, at: 0 };
  async function keys() {
    if (cache.keys && nowMs() - cache.at < cacheTtlMs) return cache.keys;
    const r = await fetchImpl(jwksUri);
    if (!r.ok) throw new AuthError('jwks_unavailable');
    const j = await r.json();
    if (!j || !Array.isArray(j.keys)) throw new AuthError('jwks_malformed');
    cache = { keys: j.keys, at: nowMs() };
    return cache.keys;
  }
  return async function verify(idToken) {
    const parts = String(idToken || '').split('.');
    if (parts.length !== 3) throw new AuthError('malformed_token');
    const header = parseJson(b64urlToBytes(parts[0]), 'header');
    const payload = parseJson(b64urlToBytes(parts[1]), 'payload');
    if (header.alg !== 'RS256' || typeof header.kid !== 'string') throw new AuthError('bad_header');
    const jwk = (await keys()).find((k) => k.kid === header.kid);
    if (!jwk) throw new AuthError('unknown_kid');
    const key = await crypto.subtle.importKey('jwk', jwk, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['verify']);
    const ok = await crypto.subtle.verify('RSASSA-PKCS1-v1_5', key, b64urlToBytes(parts[2]), enc.encode(parts[0] + '.' + parts[1]));
    if (!ok) throw new AuthError('bad_signature');
    if (payload.iss !== 'https://accounts.google.com' && payload.iss !== 'accounts.google.com') throw new AuthError('bad_iss');
    const auds = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
    if (!auds.some((a) => audiences.includes(a))) throw new AuthError('bad_aud');
    const now = Math.floor(nowMs() / 1000);
    if (typeof payload.exp !== 'number' || payload.exp <= now) throw new AuthError('expired');
    if (typeof payload.iat !== 'number' || payload.iat > now + 300) throw new AuthError('bad_iat');
    if (!payload.sub) throw new AuthError('no_sub');
    return {
      sub: String(payload.sub),
      email: String(payload.email || '').toLowerCase(),
      emailVerified: payload.email_verified === true,
      name: String(payload.name || ''),
    };
  };
}

// Issues and verifies short-lived session tokens (HMAC-SHA256 over a b64url
// JSON body). Verification uses crypto.subtle.verify, which is constant-time.
// Revocation is NOT in the token: callers must re-check user status per use.
export function createSessionTokens({ secret, ttlMs = 15 * 60 * 1000, nowMs = () => Date.now() } = {}) {
  if (typeof secret !== 'string' || secret.length < 32) throw new AuthError('weak_session_secret');
  const keyP = crypto.subtle.importKey('raw', enc.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign', 'verify']);
  return {
    async issue({ sub, email }) {
      const body = bytesToB64url(enc.encode(JSON.stringify({ sub, email, exp: nowMs() + ttlMs, jti: crypto.randomUUID() })));
      const sig = await crypto.subtle.sign('HMAC', await keyP, enc.encode(body));
      return body + '.' + bytesToB64url(new Uint8Array(sig));
    },
    async verify(token) {
      const t = String(token || '');
      const i = t.lastIndexOf('.');
      if (i <= 0) throw new AuthError('malformed_session');
      const body = t.slice(0, i);
      let sig;
      try { sig = b64urlToBytes(t.slice(i + 1)); } catch { throw new AuthError('malformed_session'); }
      const ok = await crypto.subtle.verify('HMAC', await keyP, sig, enc.encode(body));
      if (!ok) throw new AuthError('bad_session_signature');
      const payload = parseJson(b64urlToBytes(body), 'session');
      if (typeof payload.exp !== 'number' || payload.exp <= nowMs()) throw new AuthError('session_expired');
      if (!payload.sub) throw new AuthError('malformed_session');
      return payload;
    },
  };
}
