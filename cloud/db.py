import hashlib, json, secrets, sqlite3, threading, time, uuid
from pathlib import Path

class Store:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
        PRAGMA journal_mode=WAL;
        PRAGMA foreign_keys=ON;
        CREATE TABLE IF NOT EXISTS hotels(id TEXT PRIMARY KEY, config TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS credentials(id TEXT PRIMARY KEY, hotel TEXT NOT NULL REFERENCES hotels(id), role TEXT NOT NULL, hash TEXT NOT NULL UNIQUE, seen REAL, version TEXT, config_version INTEGER);
        CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, hotel TEXT NOT NULL REFERENCES hotels(id), device TEXT NOT NULL, started REAL, ended REAL, status TEXT, language TEXT);
        CREATE TABLE IF NOT EXISTS items(id TEXT PRIMARY KEY, hotel TEXT NOT NULL REFERENCES hotels(id), session TEXT NOT NULL, kind TEXT, created REAL, payload TEXT, status TEXT DEFAULT 'open');
        CREATE TABLE IF NOT EXISTS calls(session TEXT NOT NULL, call_id TEXT NOT NULL, tool TEXT, args_hash TEXT, result TEXT, PRIMARY KEY(session,call_id));
        CREATE TABLE IF NOT EXISTS audit(id TEXT PRIMARY KEY, hotel TEXT, session TEXT, created REAL, tool TEXT, status TEXT);
        CREATE TABLE IF NOT EXISTS operation_sessions(id TEXT PRIMARY KEY REFERENCES sessions(id), language TEXT NOT NULL, staff_language TEXT NOT NULL, expires REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS operation_actions(session TEXT NOT NULL REFERENCES operation_sessions(id) ON DELETE CASCADE, call_id TEXT NOT NULL, action TEXT NOT NULL, arguments TEXT NOT NULL, fingerprint TEXT NOT NULL, confirmation_id TEXT UNIQUE, state TEXT NOT NULL, result TEXT, PRIMARY KEY(session,call_id), UNIQUE(session,action,fingerprint));
        CREATE TABLE IF NOT EXISTS operation_call_ids(session TEXT NOT NULL REFERENCES operation_sessions(id) ON DELETE CASCADE, call_id TEXT NOT NULL, action TEXT NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(session,call_id));
        CREATE TABLE IF NOT EXISTS write_claims(session TEXT NOT NULL, tool TEXT NOT NULL, args_hash TEXT NOT NULL, call_id TEXT NOT NULL, PRIMARY KEY(session,tool,args_hash));
        INSERT OR IGNORE INTO write_claims SELECT session,tool,args_hash,call_id FROM calls WHERE tool IN ('leave_reception_note','call_taxi','notify_staff','create_guest_request','connect_operator') ORDER BY rowid;
        ''')
        # Retired provider tables in an existing database are deliberately left
        # untouched. Removing a transport must not delete historical hotel data.
        self.db.commit()
    def run(self, sql, args=()):
        with self.lock:
            cur = self.db.execute(sql, args)
            self.db.commit()
            return cur
    def rows(self, sql, args=()):
        with self.lock:
            return [dict(r) for r in self.db.execute(sql, args).fetchall()]
    def hotel(self, hid):
        rows = self.rows('SELECT config FROM hotels WHERE id=?', (hid,))
        if not rows: raise KeyError('hotel not found')
        return json.loads(rows[0]['config'])
    def save_hotel(self, hotel):
        with self.lock:
            old = self.rows('SELECT config FROM hotels WHERE id=?', (hotel['id'],))
            hotel = dict(hotel)
            hotel['config_version'] = json.loads(old[0]['config'])['config_version'] + 1 if old else 1
            self.run('INSERT INTO hotels VALUES(?,?) ON CONFLICT(id) DO UPDATE SET config=excluded.config', (hotel['id'], json.dumps(hotel)))
        return hotel
    def issue(self, hid, role, name=None):
        self.hotel(hid)
        token = secrets.token_urlsafe(32)
        ident = name or str(uuid.uuid4())
        self.run('INSERT INTO credentials(id,hotel,role,hash) VALUES(?,?,?,?)',
                 (ident, hid, role, hashlib.sha256(token.encode()).hexdigest()))
        return {'id': ident, 'token': token, 'hotel_id': hid, 'role': role}
    def auth(self, token, role):
        if not token: return None
        rows = self.rows('SELECT id,hotel,role FROM credentials WHERE hash=? AND role=?',
                         (hashlib.sha256(token.encode()).hexdigest(), role))
        return rows[0] if rows else None
    def item(self, hid, sid, kind, payload, require_session=False):
        ident = str(uuid.uuid4())
        values = (ident, hid, sid, kind, time.time(), json.dumps(payload))
        if require_session:
            # Authorization and durable insertion are one SQL statement.
            cur = self.run("""INSERT INTO items(id,hotel,session,kind,created,payload)
                SELECT ?,?,?,?,?,? WHERE EXISTS (
                    SELECT 1 FROM sessions s JOIN credentials c ON c.id=s.device
                    WHERE s.id=? AND s.hotel=? AND s.status='active'
                    AND c.hotel=s.hotel AND c.role='device'
                    AND NOT EXISTS (SELECT 1 FROM operation_sessions o WHERE o.id=s.id AND o.expires<=?))""",
                (*values,sid,hid,time.time()))
            if not cur.rowcount: raise PermissionError('Session ended or revoked')
        else:
            self.run('INSERT INTO items(id,hotel,session,kind,created,payload) VALUES(?,?,?,?,?,?)', values)
        return ident
    def event(self, hid, sid, tool, status):
        # Tool metadata only: no raw guest audio, transcript or tool arguments.
        self.run('INSERT INTO audit VALUES(?,?,?,?,?,?)', (str(uuid.uuid4()),hid,sid,time.time(),tool,status))
