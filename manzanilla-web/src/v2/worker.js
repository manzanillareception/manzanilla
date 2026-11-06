// Manzanilla V2 worker: Google sign-in + admin approval gating ALL provider
// access. The Cartesia key lives only in this worker's secrets; clients
// (web pilot, mobile app) never see it. Translation is parked: only the
// night-receptionist agent is exposed here.
//
// Routes:
//   GET  /v2/health
//   GET  /v2/login           Google sign-in page (web testing only; mobile uses its own GIS flow)
//   POST /v2/auth            {credential} -> {status, session?}  (session only when approved)
//   WSS  /v2/relay           first message {type:'auth',token}; bridges to Cartesia night agent
//   POST /v2/admin/list      {credential} admin: list users
//   POST /v2/admin/approve   {credential, sub}
//   POST /v2/admin/revoke    {credential, sub}
//   POST /v2/admin/audit     {credential}
//   GET  /v2/admin           minimal admin page
//
// Env: GOOGLE_CLIENT_IDS (comma-separated OAuth client IDs), ADMIN_EMAILS
// (comma-separated, lowercased compare), SESSION_SECRET (>=32 chars),
// CARTESIA_API_KEY (secret), CARTESIA_AGENT_NIGHT, CARTESIA_VERSION,
// V2_DAILY_SESSION_CAP (optional, default 10), USERS (UsersDO binding).

import { AuthError, createGoogleVerifier, createSessionTokens } from './auth.js';
import { UsersDO } from './users.js';
export { UsersDO };

const DEFAULT_AUTH_TIMEOUT_MS = 10000;
const STATUS_POLL_MS = 10000;
const MAX_CALL_MS = 15 * 60 * 1000;

export default {
  async fetch(req, env, ctx) {
    const url = new URL(req.url);
    try {
      switch (url.pathname) {
        case '/v2/health': return json(200, { status: 'ok', scope: 'v2-auth' });
        case '/v2/login': return html(loginPage(env));
        case '/v2/admin': return html(adminPage(env));
        case '/v2/auth': return await onAuth(req, env);
        case '/v2/relay': return await onRelay(req, env, ctx);
        case '/v2/admin/list': return await onAdmin(req, env, 'list');
        case '/v2/admin/approve': return await onAdmin(req, env, 'approve');
        case '/v2/admin/revoke': return await onAdmin(req, env, 'revoke');
        case '/v2/admin/audit': return await onAdmin(req, env, 'audit');
        default: return json(404, { error: 'unknown_route' });
      }
    } catch (e) {
      if (e instanceof AuthError) return json(401, { error: e.message });
      return json(500, { error: 'internal' });
    }
  },
};

function audiences(env) {
  return String(env.GOOGLE_CLIENT_IDS || '').split(',').map((s) => s.trim()).filter(Boolean);
}
function adminEmails(env) {
  return String(env.ADMIN_EMAILS || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
}
// Verifiers are expensive to construct and hold the in-memory JWKS cache, so
// keep one per isolate keyed on the config that shapes it. Rebuilding one per
// request would refetch Google's JWKS document on every auth/admin call.
let verifierCache = null;
function googleVerifier(env) {
  const key = JSON.stringify([audiences(env), env.V2_JWKS_URI || '']);
  if (!verifierCache || verifierCache.key !== key) {
    verifierCache = { key, verifier: createGoogleVerifier({ audiences: audiences(env), jwksUri: env.V2_JWKS_URI || undefined }) };
  }
  return verifierCache.verifier;
}

function usersStub(env) {
  return env.USERS.get(env.USERS.idFromName('users'));
}
async function usersCall(env, path, body) {
  const r = await usersStub(env).fetch('https://users.internal' + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}),
  });
  return r.json();
}

async function onAuth(req, env) {
  if (req.method !== 'POST') return json(405, { error: 'method' });
  const { credential } = await req.json().catch(() => ({}));
  const id = await googleVerifier(env)(credential);
  if (!id.emailVerified) throw new AuthError('email_unverified');
  const isAdmin = adminEmails(env).includes(id.email);
  await usersCall(env, '/touch', id);
  if (isAdmin) await usersCall(env, '/approve', { sub: id.sub, by: 'admin-allowlist' }).catch(() => null);
  const fresh = (await usersCall(env, '/user?sub=' + encodeURIComponent(id.sub), {})).user;
  const status = (fresh && fresh.status) || 'pending';
  if (status !== 'approved') return json(403, { status });
  const tokens = createSessionTokens({ secret: env.SESSION_SECRET });
  const session = await tokens.issue({ sub: id.sub, email: id.email });
  return json(200, { status: 'approved', session, expires_ms: 15 * 60 * 1000 });
}

async function requireAdmin(req, env) {
  const { credential } = await req.json().catch(() => ({}));
  const id = await googleVerifier(env)(credential);
  if (!id.emailVerified) throw new AuthError('email_unverified');
  if (!adminEmails(env).includes(id.email)) throw new AuthError('not_admin');
  return id;
}

async function onAdmin(req, env, action) {
  if (req.method !== 'POST') return json(405, { error: 'method' });
  const body = await req.clone().json().catch(() => ({}));
  await requireAdmin(req, env);
  if (action === 'approve' || action === 'revoke') {
    if (!body.sub) return json(400, { error: 'missing_sub' });
    return json(200, await usersCall(env, '/' + action, { sub: body.sub, by: 'admin' }));
  }
  if (action === 'list') {
    const r = await usersStub(env).fetch('https://users.internal/list');
    return json(200, await r.json());
  }
  if (action === 'audit') {
    const r = await usersStub(env).fetch('https://users.internal/audit');
    return json(200, await r.json());
  }
  return json(404, { error: 'unknown_route' });
}

// WSS relay: the 101 response is returned immediately; auth happens on the
// first message. Client sends {type:'auth',token}; we verify the session,
// require an approved user, acquire the one-active lease, then bridge to the
// Cartesia night agent upstream. The provider key is added only on the
// upstream handshake. No token in the URL. Frames arriving before
// session_ready are dropped; clients wait for session_ready by contract.
async function onRelay(req, env, ctx) {
  if (req.headers.get('Upgrade') !== 'websocket') return json(426, { error: 'upgrade_required' });
  const pair = new WebSocketPair();
  const [client, server] = Object.values(pair);
  server.accept();

  let upstream = null;
  let released = false;
  let poller = null;
  let killer = null;
  let authed = false;
  let session = null;
  let sessionId = null;

  const fail = (reason, code) => {
    try { server.send(JSON.stringify({ type: 'error', error: reason })); } catch {}
    try { server.close(code || 4401, reason); } catch {}
  };
  const cleanup = (code, reason) => {
    if (released) return; released = true;
    if (poller) clearInterval(poller);
    if (killer) clearTimeout(killer);
    if (session && sessionId) ctx.waitUntil(usersCall(env, '/release', { sub: session.sub, session_id: sessionId }));
    try { if (upstream) upstream.close(); } catch {}
    try { server.close(code || 1000, reason || ''); } catch {}
  };

  const authTimer = setTimeout(() => { if (!authed) fail('auth_required'); },
    parseInt(env.V2_AUTH_TIMEOUT_MS || String(DEFAULT_AUTH_TIMEOUT_MS), 10));

  server.addEventListener('message', (ev) => {
    if (authed) {
      try { if (upstream) upstream.send(ev.data); } catch { cleanup(1011, 'upstream_send_failed'); }
      return;
    }
    let token = null;
    try { const m = JSON.parse(ev.data); if (m && m.type === 'auth' && typeof m.token === 'string') token = m.token; } catch {}
    if (!token) { clearTimeout(authTimer); fail('auth_required'); return; }
    authed = true; // exactly one auth attempt
    clearTimeout(authTimer);
    bridge(ev, token).catch(() => cleanup(1011, 'bridge_failed'));
  });
  server.addEventListener('close', () => cleanup(1000, 'client_closed'));
  server.addEventListener('error', () => cleanup(1011, 'client_error'));

  async function bridge(_ev, token) {
    try {
      session = await createSessionTokens({ secret: env.SESSION_SECRET }).verify(token);
    } catch (e) { fail(e instanceof AuthError ? e.message : 'bad_session'); return; }

    sessionId = crypto.randomUUID();
    const cap = parseInt(env.V2_DAILY_SESSION_CAP || '10', 10);
    const acq = await usersCall(env, '/acquire', { sub: session.sub, session_id: sessionId, daily_cap: cap });
    if (!acq.ok) { fail(acq.error || 'not_allowed'); return; }

    const agentId = env.CARTESIA_AGENT_NIGHT;
    const upstreamBase = env.CARTESIA_UPSTREAM_WS_BASE || 'wss://api.cartesia.ai';
    const upstreamUrl = upstreamBase + '/v1/agents/websocket/' + agentId + '?cartesia_version=' + encodeURIComponent(env.CARTESIA_VERSION || '2026-08-14');
    let upResp;
    try {
      upResp = await fetch(upstreamUrl.replace('wss://', 'https://').replace('ws://', 'http://'), {
        headers: { Upgrade: 'websocket', 'Cartesia-Api-Key': env.CARTESIA_API_KEY },
      });
    } catch { cleanup(1011, 'upstream_unreachable'); return; }
    upstream = upResp.webSocket;
    if (!upstream) { cleanup(1011, 'upstream_rejected'); return; }
    upstream.accept();
    upstream.addEventListener('message', (ev2) => { try { server.send(ev2.data); } catch { cleanup(1011, 'client_send_failed'); } });
    upstream.addEventListener('close', () => cleanup(1000, 'upstream_closed'));
    upstream.addEventListener('error', () => cleanup(1011, 'upstream_error'));

    // Mid-call enforcement: re-check approval and our lease; a revoked user is
    // cut off within one poll interval, not just at the next connect.
    poller = setInterval(async () => {
      try {
        const r = await usersCall(env, '/user?sub=' + encodeURIComponent(session.sub), {});
        if (!r.user || r.user.status !== 'approved' || !r.user.active) cleanup(4403, 'access_revoked');
      } catch { /* transient store error: keep the call, re-check next tick */ }
    }, STATUS_POLL_MS);
    killer = setTimeout(() => cleanup(4408, 'max_duration'), MAX_CALL_MS);

    server.send(JSON.stringify({ type: 'session_ready', session_id: sessionId }));
  }

  return new Response(null, { status: 101, webSocket: client });
}

function loginPage(env) {
  const cid = (audiences(env)[0] || '').replace(/[<>&"]/g, '');
  return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Manzanilla V2 sign in</title>
<body style="font:16px system-ui;max-width:560px;margin:48px auto;padding:0 16px">
<h1>Manzanilla Reception V2</h1>
<p>Sign in with Google. New accounts stay pending until the administrator approves them.</p>
<script src="https://accounts.google.com/gsi/client" async defer></script>
<div id="g_id_onload" data-client_id="${cid}" data-callback="onGoogle"></div>
<div class="g_id_signin" data-type="standard"></div>
<pre id="out" style="white-space:pre-wrap"></pre>
<script>
async function onGoogle(r){
  const res = await fetch('/v2/auth',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({credential:r.credential})});
  const b = await res.json().catch(()=>({}));
  document.getElementById('out').textContent = res.ok
    ? 'Approved. Session token (15 minutes):\\n' + b.session
    : 'Access ' + (b.status || b.error || 'denied');
}
</script>`;
}

function adminPage(env) {
  const cid = (audiences(env)[0] || '').replace(/[<>&"]/g, '');
  return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Manzanilla V2 admin</title>
<body style="font:16px system-ui;max-width:720px;margin:48px auto;padding:0 16px">
<h1>Manzanilla V2 admin</h1>
<script src="https://accounts.google.com/gsi/client" async defer></script>
<div id="g_id_onload" data-client_id="${cid}" data-callback="onGoogle"></div>
<div class="g_id_signin" data-type="standard"></div>
<div id="app" style="display:none">
<button onclick="load()">Refresh</button>
<pre id="out" style="white-space:pre-wrap"></pre>
</div>
<script>
let cred=null;
async function call(path,body){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(Object.assign({credential:cred},body||{}))});return r.json();}
async function onGoogle(r){cred=r.credential;const b=await call('/v2/admin/list');if(b.error){document.getElementById('out').textContent='Not an admin: '+b.error;return;}document.getElementById('app').style.display='block';render(b.users);}
function render(users){const out=document.getElementById('out');out.textContent='';for(const u of users){out.textContent+=u.email+'  ['+u.status+']  sessions today: '+u.sessions_today+(u.active?' (active)':'')+'\\n';}}
async function approve(){const sub=prompt('User sub to approve:');if(!sub)return;await call('/v2/admin/approve',{sub});load();}
async function revoke(){const sub=prompt('User sub to revoke:');if(!sub)return;await call('/v2/admin/revoke',{sub});load();}
async function load(){const b=await call('/v2/admin/list');if(b.users)render(b.users);}
document.addEventListener('DOMContentLoaded',()=>{const b1=document.createElement('button');b1.textContent='Approve user';b1.onclick=approve;const b2=document.createElement('button');b2.textContent='Revoke user';b2.onclick=revoke;document.getElementById('app').prepend(b1,b2);});
</script>`;
}

function json(status, obj) { return new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json' } }); }
function html(s) { return new Response(s, { headers: { 'Content-Type': 'text/html; charset=utf-8' } }); }
