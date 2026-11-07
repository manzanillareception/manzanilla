import asyncio, hashlib, json, os, secrets, time, uuid
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo
from fastapi import FastAPI, HTTPException, Header, WebSocket, WebSocketDisconnect, Depends
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from .db import Store
from .models import Hotel, BookingLookup
from .gateway import Gateway
from .realtime import relay
from .operations import Operations, Start, Action, Confirmation
from .models import Strict
from typing import Literal

store = Store(os.getenv('ORANGE_DB','data/orange.db'))
app = FastAPI(title='Orange Night Reception', version='0.1.0')
tickets = {}
edge_connections = {}
edge_pending = {}
active_devices = set()
device_connections = {}

# Live audio remains disabled until an approved replacement is integrated.
# Staff-led HTTP operations remain available independently of voice providers.
GENERIC_RELAY_ENABLED = False
GENERIC_RELAY_BLOCK_REASON = 'Live audio is unavailable in this service. Use staff-led reception actions or ask hotel staff.'

def require_generic_relay():
    if not GENERIC_RELAY_ENABLED: raise HTTPException(503,GENERIC_RELAY_BLOCK_REASON)

def bearer(value): return value[7:] if value and value.startswith('Bearer ') else ''
def admin(authorization: str = Header(default='')):
    secret = os.getenv('ORANGE_ADMIN_TOKEN','')
    if len(secret)<32 or not secrets.compare_digest(bearer(authorization),secret): raise HTTPException(401,'Unauthorized')
    return True

def scoped(hid, authorization):
    secret = os.getenv('ORANGE_ADMIN_TOKEN','')
    if len(secret)>=32 and secrets.compare_digest(bearer(authorization),secret): return
    staff = store.auth(bearer(authorization),'staff')
    if not staff or staff['hotel']!=hid: raise HTTPException(403,'Forbidden')

@app.get('/health')
def health(): return {'status':'ok','version':'0.1.0','protocol':1}

@app.get('/admin')
def dashboard(): return FileResponse(Path(__file__).parent.parent/'admin/index.html')

@app.get('/api/hotels', dependencies=[Depends(admin)])
def hotels(): return [json.loads(row['config']) for row in store.rows('SELECT config FROM hotels')]

@app.put('/api/hotels/{hid}', dependencies=[Depends(admin)])
def save_hotel(hid: str, value: Hotel):
    if value.id != hid: raise HTTPException(400,'Hotel ID mismatch')
    try: ZoneInfo(value.timezone)
    except Exception: raise HTTPException(400,'Invalid timezone')
    return store.save_hotel(value.model_dump())

class Credential(BaseModel):
    role: str = Field(pattern='^(device|edge|staff)$')
@app.post('/api/hotels/{hid}/credentials', dependencies=[Depends(admin)])
def issue(hid: str, value: Credential):
    try: return store.issue(hid,value.role)
    except KeyError: raise HTTPException(404,'Hotel not found')

@app.delete('/api/credentials/{ident}', dependencies=[Depends(admin)])
async def revoke(ident: str):
    store.run('DELETE FROM credentials WHERE id=?',(ident,))
    store.run("UPDATE sessions SET status='revoked',ended=? WHERE device=? AND status='active'",(time.time(),ident))
    closing = []
    for hid, connection in list(edge_connections.items()):
        if connection['credential']['id'] == ident:
            edge_connections.pop(hid,None)
            for pending in list(edge_pending.values()):
                if pending[0] is connection and not pending[1].done():
                    pending[1].set_result({'status':'unavailable'})
            closing.append(connection['ws'])
    ws = device_connections.get(ident)
    if ws: closing.append(ws)
    async def close(ws):
        try: await ws.close(code=4401)
        except (RuntimeError,WebSocketDisconnect): pass
    await asyncio.gather(*(close(ws) for ws in closing))
    return {'status':'revoked'}

@app.get('/api/hotels/{hid}/overview')
def overview(hid: str, authorization: str = Header(default='')):
    scoped(hid,authorization)
    now = time.time()
    devices = store.rows('SELECT id,role,seen,version,config_version FROM credentials WHERE hotel=? AND role IN (\'device\',\'edge\')',(hid,))
    for row in devices: row['online'] = bool(row['seen'] and now-row['seen']<75)
    items = store.rows('SELECT * FROM items WHERE hotel=? ORDER BY created DESC LIMIT 500',(hid,))
    for item in items: item['payload']=json.loads(item['payload'])
    return {'hotel':store.hotel(hid),'devices':devices,'items':items,
            'sessions':store.rows('SELECT * FROM sessions WHERE hotel=? ORDER BY started DESC LIMIT 100',(hid,)),
            'integrations':{'pms':store.hotel(hid)['connector'], 'crm':'not configured', 'edge':hid in edge_connections}}

@app.patch('/api/hotels/{hid}/items/{ident}')
def resolve(hid: str, ident: str, authorization: str = Header(default='')):
    scoped(hid,authorization)
    cur=store.run("UPDATE items SET status='resolved' WHERE id=? AND hotel=?",(ident,hid))
    if not cur.rowcount: raise HTTPException(404,'Item not found')
    return {'status':'resolved'}

@app.get('/api/hotels/{hid}/morning')
def morning(hid: str, start: float, end: float, authorization: str = Header(default='')):
    scoped(hid,authorization)
    if end<=start or end-start>7*86400: raise HTTPException(400,'Invalid time window')
    sessions = store.rows('SELECT * FROM sessions WHERE hotel=? AND started>=? AND started<?',(hid,start,end))
    items = store.rows('SELECT * FROM items WHERE hotel=? AND created>=? AND created<?',(hid,start,end))
    for row in items: row['payload']=json.loads(row['payload'])
    return {'hotel_id':hid,'timezone':store.hotel(hid)['timezone'],'start':start,'end':end,
            'session_count':len(sessions),'automatically_resolved':None,
            'note_count':sum(i['kind']=='note' for i in items),
            'escalation_count':sum(i['kind']=='escalation' for i in items),'items':items}

class Heartbeat(BaseModel):
    app_version: str = Field(max_length=40)
    config_version: int = Field(ge=0)
    protocol: int = 1

def device_auth(auth):
    result=store.auth(bearer(auth),'device')
    if not result: raise HTTPException(401,'Unauthorized device')
    return result

class StaffCheck(BaseModel):
    staff_token: str = Field(min_length=1, max_length=200)

@app.post('/api/device/staff-check')
def staff_check(value: StaffCheck, authorization: str = Header(default='')):
    dev=device_auth(authorization)
    staff=store.auth(value.staff_token,'staff')
    if not staff or staff['hotel']!=dev['hotel']: raise HTTPException(403,'Staff authorization required')
    return {'authorized':True}

@app.post('/api/device/heartbeat')
def heartbeat(value: Heartbeat, authorization: str = Header(default='')):
    dev=device_auth(authorization)
    if value.protocol!=1: raise HTTPException(426,'Client update required')
    store.run('UPDATE credentials SET seen=?,version=?,config_version=? WHERE id=?',(time.time(),value.app_version,value.config_version,dev['id']))
    return {'hotel':store.hotel(dev['hotel']),'device_id':dev['id'],'protocol':1}

class VoiceSession(Strict):
    mode: Literal['translation','reception']
    language: str = Field(max_length=35,pattern=r'^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
    staff_language: str = Field(max_length=35,pattern=r'^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$')
    session_id: str = Field(pattern=r'^[A-Za-z0-9_-]{1,100}$')
    generation: int = Field(default=0, ge=0)

@app.post('/api/device/operations/start')
def operations_start(value: Start, authorization: str = Header(default='')):
    return Operations(store,edge_execute).start(device_auth(authorization),value)

@app.post('/api/device/operations/{sid}/action')
async def operations_action(sid: str, value: Action, authorization: str = Header(default='')):
    return await Operations(store,edge_execute).action(device_auth(authorization),sid,value)

@app.post('/api/device/operations/{sid}/confirm')
async def operations_confirm(sid: str, value: Confirmation, authorization: str = Header(default='')):
    return await Operations(store,edge_execute).confirm(device_auth(authorization),sid,value)

@app.post('/api/device/operations/{sid}/end')
def operations_end(sid: str, authorization: str = Header(default='')):
    return Operations(store,edge_execute).end(device_auth(authorization),sid)

@app.post('/api/device/session')
def session(value: VoiceSession, authorization: str = Header(default='')):
    dev=device_auth(authorization)
    require_generic_relay()
    if value.mode == 'translation' and value.language.casefold() == value.staff_language.casefold():
        raise HTTPException(422,'Choose two different interpreter languages')
    if store.rows('SELECT id FROM sessions WHERE id=?',(value.session_id,)):
        raise HTTPException(409,'Session identifier already used')
    now=time.time()
    for key in list(tickets):
        if tickets[key]['expires']<now: del tickets[key]
    # One short-lived ticket per credential prevents unbounded issuance.
    for key in list(tickets):
        if tickets[key]['device']['id']==dev['id']: del tickets[key]
    token=secrets.token_urlsafe(32)
    tickets[token]={'device':dev,'expires':now+30,'metadata':value.model_dump()}
    return {'ticket':token,'expires_in':30,'protocol':1}

async def edge_execute(hid, capability, args):
    connection=edge_connections.get(hid)
    if not connection: return {'status':'unavailable'}
    ws=connection['ws']; cred=connection['credential']
    if store.auth(bearer(ws.headers.get('authorization')),'edge') != cred:
        if edge_connections.get(hid) is connection: edge_connections.pop(hid,None)
        try: await ws.close(code=4401)
        except (RuntimeError,WebSocketDisconnect): pass
        return {'status':'unavailable'}
    ident=str(uuid.uuid4())
    future=asyncio.get_running_loop().create_future()
    edge_pending[ident]=(connection,future)
    try:
        await ws.send_json({'type':'execute','id':ident,'capability':capability,'args':args})
        result=await asyncio.wait_for(future,10)
        if store.auth(bearer(ws.headers.get('authorization')),'edge') != cred:
            return {'status':'unavailable'}
        return result
    except (TimeoutError,RuntimeError,WebSocketDisconnect): return {'status':'unavailable'}
    finally: edge_pending.pop(ident,None)

@app.post('/api/hotels/{hid}/edge-check', dependencies=[Depends(admin)])
async def edge_check(hid: str, value: BookingLookup):
    try: store.hotel(hid)
    except KeyError: raise HTTPException(404,'Hotel not found')
    return await edge_execute(hid,'read_reservation',value.model_dump())

@app.websocket('/ws/edge')
async def edge(ws: WebSocket):
    cred=store.auth(bearer(ws.headers.get('authorization')),'edge')
    if not cred or cred['hotel'] in edge_connections:
        await ws.close(code=4401); return
    await ws.accept()
    if store.auth(bearer(ws.headers.get('authorization')),'edge') != cred or cred['hotel'] in edge_connections:
        await ws.close(code=4401); return
    hid=cred['hotel']; connection={'credential':cred,'ws':ws}; edge_connections[hid]=connection
    try:
        while True:
            msg=await asyncio.wait_for(ws.receive_json(),75)
            if store.auth(bearer(ws.headers.get('authorization')),'edge') != cred: break
            store.run('UPDATE credentials SET seen=? WHERE id=?',(time.time(),cred['id']))
            if msg.get('type')=='heartbeat': await ws.send_json({'type':'pong'})
            elif msg.get('type')=='result':
                pending=edge_pending.get(msg.get('id'))
                if pending and pending[0] is connection and not pending[1].done(): pending[1].set_result(msg.get('result',{'status':'failed'}))
    except (WebSocketDisconnect,TimeoutError,ValueError,RuntimeError): pass
    finally:
        if edge_connections.get(hid) is connection: edge_connections.pop(hid,None)
        for pending in list(edge_pending.values()):
            if pending[0] is connection and not pending[1].done():
                pending[1].set_result({'status':'unavailable'})

@app.websocket('/ws/device')
async def voice(ws: WebSocket):
    # Ticket delivered in header, never query parameters/access logs.
    entry=tickets.pop(ws.headers.get('x-orange-ticket',''),None)
    if not GENERIC_RELAY_ENABLED:
        await ws.close(code=4403,reason='This voice route is disabled; use the approved interpreter session endpoint')
        return
    if not entry or entry['expires']<time.time(): await ws.close(code=4401); return
    dev=entry['device']
    valid=store.rows('SELECT id FROM credentials WHERE id=? AND role=\'device\'',(dev['id'],))
    if not valid or dev['id'] in active_devices: await ws.close(code=4409); return
    active_devices.add(dev['id']); await ws.accept(); device_connections[dev['id']]=ws
    metadata=entry['metadata']; sid=metadata['session_id']; hid=dev['hotel']; pending={}
    scope={k:metadata[k] for k in ('session_id','generation')}
    claimed=store.run('INSERT OR IGNORE INTO sessions VALUES(?,?,?,?,?,?,?)',
                      (sid,hid,dev['id'],time.time(),None,'active',metadata['language']))
    if not claimed.rowcount:
        active_devices.discard(dev['id']); device_connections.pop(dev['id'],None)
        await ws.close(code=4409); return
    def authorized():
        return bool(store.rows("SELECT s.id FROM sessions s JOIN credentials c ON c.id=s.device WHERE s.id=? AND s.status='active' AND c.role='device' AND c.hotel=s.hotel",(sid,)))
    async def device_request(kind, payload, timeout):
        ident=str(uuid.uuid4()); future=asyncio.get_running_loop().create_future(); pending[ident]=future
        try:
            await ws.send_json({'type':kind,'id':ident,**payload,**scope})
            return await asyncio.wait_for(future,timeout)
        except TimeoutError: return {}
        finally: pending.pop(ident,None)
    async def confirm(name,args):
        result=await device_request('confirm',{'tool':name,'arguments':args},60)
        return result.get('accepted') is True
    async def handoff(kind,number,backup):
        result=await device_request('handoff',{'kind':kind,'number':number,'backup':backup},15)
        status=result.get('status')
        return {'status':status if status in {'dialer_opened','failed'} else 'unavailable','number':number}
    gateway=Gateway(store,hid,sid,edge_execute,confirm,handoff,authorized)
    try:
        await relay(ws,store.hotel(hid),gateway,pending,metadata)
    except Exception:
        try: await ws.send_json({'type':'error','message':'Connection lost. Please retry or use the operator button.',**scope})
        except Exception: pass
    finally:
        store.run('UPDATE sessions SET ended=?,status=? WHERE id=?',(time.time(),'ended',sid))
        active_devices.discard(dev['id']); device_connections.pop(dev['id'],None)
        try: await ws.close()
        except RuntimeError: pass
