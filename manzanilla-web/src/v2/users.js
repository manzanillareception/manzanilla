// UsersDO: one Durable Object holding V2 access state.
// A Google identity becomes a user row: pending -> approved | revoked.
// Only approved users can mint or relay. Admin actions and status changes are
// appended to a capped audit log. No guest content is stored here.

const STATUSES = new Set(['pending', 'approved', 'revoked']);
const AUDIT_CAP = 500;
// An unreleased lease older than this cannot belong to a live call (the worker
// kills every call at 15 minutes), so it is stale isolate-loss residue, not a
// reason to lock the user out forever.
const LEASE_TTL_MS = 16 * 60 * 1000;

export class UsersDO {
  constructor(state) {
    this.state = state;
  }

  async fetch(req) {
    const url = new URL(req.url);
    const body = req.method === 'POST' ? await req.json().catch(() => ({})) : {};
    switch (url.pathname) {
      case '/touch': return this.touch(body);
      case '/user': return this.getUser(url.searchParams.get('sub'));
      case '/approve': return this.setStatus(body, 'approved');
      case '/revoke': return this.setStatus(body, 'revoked');
      case '/list': return this.list();
      case '/audit': return this.audit();
      case '/acquire': return this.acquire(body);
      case '/release': return this.release(body);
      default: return json(404, { error: 'unknown_route' });
    }
  }

  async touch({ sub, email, name }) {
    if (!sub) return json(400, { error: 'missing_sub' });
    const now = Date.now();
    let u = await this.state.storage.get('u:' + sub);
    if (!u) {
      u = { sub, email: email || '', name: name || '', status: 'pending', created_at: now, sessions_today: 0, day: dayKey(now), active_session: null };
      await this.state.storage.put('u:' + sub, u);
      await this.log({ sub, event: 'registered', at: now });
    }
    return json(200, { user: publicUser(u) });
  }

  async getUser(sub) {
    if (!sub) return json(400, { error: 'missing_sub' });
    const u = await this.state.storage.get('u:' + sub);
    return json(200, { user: u ? publicUser(u) : null });
  }

  async setStatus({ sub, by }, status) {
    if (!sub || !STATUSES.has(status)) return json(400, { error: 'bad_request' });
    const u = await this.state.storage.get('u:' + sub);
    if (!u) return json(404, { error: 'unknown_user' });
    u.status = status;
    u[status === 'approved' ? 'approved_at' : 'revoked_at'] = Date.now();
    u.decided_by = by || '';
    if (status === 'revoked') u.active_session = null;
    await this.state.storage.put('u:' + sub, u);
    await this.log({ sub, event: status, by: by || '', at: Date.now() });
    return json(200, { user: publicUser(u) });
  }

  // One active relay per user, plus a daily session cap. Atomic under the DO's
  // single-threaded storage contract.
  async acquire({ sub, session_id, daily_cap = 10 }) {
    const u = await this.state.storage.get('u:' + sub);
    if (!u) return json(404, { error: 'unknown_user' });
    if (u.status !== 'approved') return json(403, { error: 'not_approved', status: u.status });
    const now = Date.now();
    if (u.day !== dayKey(now)) { u.day = dayKey(now); u.sessions_today = 0; }
    if (u.active_session) {
      const fresh = now - (u.active_session.at || 0) <= LEASE_TTL_MS;
      if (fresh) return json(429, { error: 'active_session' });
      await this.log({ sub, event: 'session_recovered', stale_session_id: u.active_session.session_id || '', at: now });
    }
    if (u.sessions_today >= daily_cap) return json(429, { error: 'daily_cap' });
    u.sessions_today += 1;
    u.active_session = { session_id, at: now };
    await this.state.storage.put('u:' + sub, u);
    return json(200, { ok: true, sessions_today: u.sessions_today });
  }

  async release({ sub, session_id }) {
    const u = await this.state.storage.get('u:' + sub);
    if (u && u.active_session && u.active_session.session_id === session_id) {
      u.active_session = null;
      await this.state.storage.put('u:' + sub, u);
    }
    return json(200, { ok: true });
  }

  async list() {
    const out = [];
    const iter = await this.state.storage.list({ prefix: 'u:' });
    for (const [, u] of iter) out.push(publicUser(u));
    out.sort((a, b) => a.created_at - b.created_at);
    return json(200, { users: out });
  }

  async log(entry) {
    const a = (await this.state.storage.get('audit')) || [];
    a.push(entry);
    while (a.length > AUDIT_CAP) a.shift();
    await this.state.storage.put('audit', a);
  }

  async audit() {
    return json(200, { audit: (await this.state.storage.get('audit')) || [] });
  }
}

function dayKey(now) { return new Date(now).toISOString().slice(0, 10); }
function publicUser(u) {
  return { sub: u.sub, email: u.email, name: u.name, status: u.status, created_at: u.created_at, approved_at: u.approved_at || null, revoked_at: u.revoked_at || null, sessions_today: u.sessions_today, active: !!u.active_session };
}
function json(status, obj) { return new Response(JSON.stringify(obj), { status, headers: { 'Content-Type': 'application/json' } }); }
