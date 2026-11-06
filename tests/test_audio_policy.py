"""Removed voice routes stay closed without affecting staff-led reception."""
import sqlite3
import time
import httpx
import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect
from cloud.db import Store
from cloud.models import Hotel
import cloud.app as server
import cloud.realtime as realtime


@pytest.fixture
def audio_gate(tmp_path,monkeypatch):
    store=Store(str(tmp_path/'policy.db'))
    store.save_hotel(Hotel(id='a',name='Synthetic unconfigured hotel').model_dump())
    monkeypatch.setattr(server,'store',store)
    monkeypatch.setattr(server,'tickets',{})
    monkeypatch.setenv('GEMINI_API_KEY','OFFLINE-TEST-KEY')
    monkeypatch.setenv('ORANGE_ADMIN_TOKEN','x'*40)
    monkeypatch.setattr(server,'device_connections',{})
    monkeypatch.setattr(server,'edge_connections',{})
    monkeypatch.setattr(server,'edge_pending',{})
    calls=[]
    def forbidden(*args,**kwargs):
        calls.append((args,kwargs))
        raise AssertionError('Blocked routes must not reach provider code')
    monkeypatch.setattr(server,'relay',forbidden)
    monkeypatch.setattr(httpx,'AsyncClient',forbidden)
    monkeypatch.setattr(realtime,'connect',forbidden)
    dev=store.issue('a','device')
    headers={'Authorization':'Bearer '+dev['token']}
    with TestClient(server.app) as client:
        yield store,client,dev,headers,calls
    store.db.close()


@pytest.mark.parametrize('body',[
    {'session_id':'closed-translation','language':'es','staff_language':'en','mode':'translation'},
    {'session_id':'closed-reception','language':'es','staff_language':'es','mode':'reception'},
])
def test_generic_relay_admission_stays_closed(audio_gate,body):
    store,client,_,headers,calls=audio_gate
    assert client.post('/api/device/session',json=body).status_code==401
    response=client.post('/api/device/session',headers=headers,json=body)
    assert response.status_code==503
    assert 'staff-led' in response.json()['detail']
    assert 'unavailable' in response.json()['detail']
    assert not calls and not server.tickets
    assert not store.rows('SELECT * FROM sessions')


def test_stale_audio_ticket_cannot_bypass_the_closed_relay(audio_gate):
    store,client,dev,_,calls=audio_gate
    server.tickets['stale-ticket']={
        'device':store.auth(dev['token'],'device'),'expires':time.time()+30,
        'metadata':{'session_id':'stale','generation':0,'language':'es','staff_language':'en','mode':'translation'}}
    with pytest.raises(WebSocketDisconnect) as denied:
        with client.websocket_connect('/ws/device',headers={'X-Orange-Ticket':'stale-ticket'}):
            pytest.fail('Gated websocket must not be accepted')
    assert denied.value.code==4403
    assert not server.tickets and not calls
    assert not store.rows('SELECT * FROM sessions')


def test_staff_led_operations_remain_operational(audio_gate):
    store,client,_,headers,calls=audio_gate
    sid='staff-led'
    started=client.post('/api/device/operations/start',headers=headers,json={
        'session_id':sid,'language':'es','staff_language':'en','mode':'reception'})
    assert started.status_code==200
    missing=client.post('/api/device/operations/'+sid+'/action',headers=headers,json={
        'action':'message','call_id':'missing','arguments':{}}).json()
    hotel=store.hotel('a')
    hotel['checkin_instructions']='Ask reception for arrival assistance.'
    store.save_hotel(hotel)
    assert missing['status']=='needs_arguments'
    draft=client.post('/api/device/operations/'+sid+'/action',headers=headers,json={
        'action':'message','call_id':'message','arguments':{'original_request':'Synthetic staff-led request'}}).json()
    assert draft['status']=='confirmation_required' and not store.rows('SELECT * FROM items')
    guide=client.post('/api/device/operations/'+sid+'/action',headers=headers,json={
        'action':'checkin','call_id':'guide','arguments':{}}).json()
    assert guide['status']=='ok' and guide['result']['instructions']==hotel['checkin_instructions']
    assert guide['result']['guidance_only'] is True and guide['result']['checkin_completed'] is False
    response=client.post('/api/device/operations/'+sid+'/confirm',headers=headers,json={
        'confirmation_id':draft['confirmation_id'],'accepted':True}).json()
    assert response['status']=='saved' and response['result']['item_id']
    assert response['result']['staff_acknowledged'] is False
    assert response['result']['pms_delivered'] is False
    assert len(store.rows('SELECT * FROM items'))==1 and not calls


@pytest.mark.parametrize('path',[
    '/api/device/fish-session',
    '/api/device/fish-session/old-session/end',
    '/api/device/fish-session-requests/old-request/end',
])
def test_removed_provider_endpoints_do_not_create_sessions_or_use_network(audio_gate,path):
    store,client,_,headers,calls=audio_gate
    for auth in ({},headers):
        assert client.post(path,headers=auth,json={}).status_code==404
    assert not calls
    assert not store.rows('SELECT * FROM sessions')
    names={row['name'] for row in store.rows("SELECT name FROM sqlite_master WHERE type='table'")}
    assert 'fish_sessions' not in names and 'provider_agents' not in names


def test_device_revocation_still_invalidates_staff_led_sessions_without_provider_calls(audio_gate):
    store,client,dev,headers,calls=audio_gate
    assert client.post('/api/device/operations/start',headers=headers,json={
        'session_id':'revocation','language':'es','staff_language':'en','mode':'reception'}).status_code==200
    response=client.delete('/api/credentials/'+dev['id'],headers={
        'Authorization':'Bearer '+'x'*40})
    assert response.status_code==200 and response.json()=={'status':'revoked'}
    assert store.auth(dev['token'],'device') is None
    assert store.rows("SELECT status FROM sessions WHERE id='revocation'")==[{'status':'revoked'}]
    assert client.post('/api/device/operations/revocation/action',headers=headers,json={
        'action':'question','call_id':'after-revocation','arguments':{'query':'breakfast'}}).status_code==401
    assert not calls


def test_existing_provider_history_is_preserved_without_schema_migration(tmp_path):
    path=tmp_path/'existing.db'
    # Retained history belongs to the hotel. Removing its old transport does not
    # authorize changing or dropping those records during application startup.
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE fish_sessions(id TEXT PRIMARY KEY, status TEXT NOT NULL)')
        db.execute('INSERT INTO fish_sessions VALUES(?,?)',('historical-session','ended'))
        db.execute('CREATE TABLE provider_agents(provider TEXT PRIMARY KEY, agent_id TEXT NOT NULL)')
        db.execute('INSERT INTO provider_agents VALUES(?,?)',('legacy','historical-agent'))
    store=Store(str(path))
    try:
        assert store.rows('SELECT * FROM fish_sessions')==[{'id':'historical-session','status':'ended'}]
        assert store.rows('SELECT * FROM provider_agents')==[{'provider':'legacy','agent_id':'historical-agent'}]
        assert [row['name'] for row in store.rows('PRAGMA table_info(fish_sessions)')]==['id','status']
        store.save_hotel(Hotel(id='retained',name='Synthetic hotel').model_dump())
        device=store.issue('retained','device')
        assert store.auth(device['token'],'device')['hotel']=='retained'
    finally:
        store.db.close()
