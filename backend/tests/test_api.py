import os
import time
import uuid
from pathlib import Path
import pytest

os.environ['APP_ENV'] = 'dev'
os.environ['DB_SCHEMA'] = 'dev'
os.environ['JWT_SECRET_KEY'] = 'test-only-secret-which-is-at-least-32-characters'
os.environ['DATABASE_URL'] = 'sqlite://'
os.environ['ALLOW_REGISTRATION'] = 'true'

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool
from app.database import Base, get_db
from app.main import app

@pytest.fixture
def client():
    engine = create_engine('sqlite://', connect_args={'check_same_thread': False}, poolclass=StaticPool,
                           execution_options={'schema_translate_map': {'app_schema': None}})
    Base.metadata.create_all(engine)
    sessions = sessionmaker(engine, expire_on_commit=False)
    def override():
        with sessions() as db: yield db
    app.dependency_overrides[get_db] = override
    with TestClient(app) as c: yield c
    app.dependency_overrides.clear()
    engine.dispose()

def account(c, name='one'):
    response = c.post('/api/v1/auth/register', json={'email':f'{name}@example.com', 'password':'personal-test-password'})
    assert response.status_code == 201, response.text
    return response.json()

def headers(a): return {'Authorization': 'Bearer ' + a['access_token']}
def change(**overrides):
    data = {'id':str(uuid.uuid4()), 'kind':'transaction', 'updated_at':int(time.time()*1000), 'deleted':False,
            'data':{'amount':'25.00','direction':'debit','payee':'Sample Merchant','occurred_at':1793382416000,'category':'Food'}}
    data.update(overrides)
    return data

def sync(c, a, changes=None, cursor=0):
    return c.post('/api/v1/sync',headers=headers(a),json={'device_id':'test-device','cursor':cursor,'changes':changes or []})

def test_refresh_rotation_and_logout(client):
    a = account(client)
    r = client.post('/api/v1/auth/refresh',json={'refresh_token':a['refresh_token']})
    assert r.status_code == 200
    assert client.post('/api/v1/auth/refresh',json={'refresh_token':a['refresh_token']}).status_code == 401
    new = r.json()['refresh_token']
    assert client.post('/api/v1/auth/logout',json={'refresh_token':new}).status_code == 204
    assert client.post('/api/v1/auth/refresh',json={'refresh_token':new}).status_code == 401

def test_sync_idempotency_user_isolation_and_tombstones(client):
    a,b = account(client),account(client,'two')
    item = change()
    first = sync(client,a,[item]).json()
    assert first['cursor'] == 1
    assert sync(client,a,[item]).json()['cursor'] == 1
    assert sync(client,b).json()['server_changes'] == []
    assert client.get('/api/v1/transactions',headers=headers(b)).json()['total'] == 0
    item['updated_at'] += 1; item['deleted'] = True
    second = sync(client,a,[item],1).json()
    assert second['cursor'] == 2 and second['server_changes'][0]['deleted']
    assert client.get('/api/v1/transactions',headers=headers(a)).json()['total'] == 0

def test_stale_changes_do_not_overwrite_and_cross_user_id_is_safe(client):
    a,b = account(client),account(client,'two')
    item = change(); assert sync(client,a,[item]).status_code == 200
    item['updated_at'] -= 1; item['data']['amount'] = '99.00'
    assert sync(client,a,[item]).json()['cursor'] == 1
    assert client.get('/api/v1/transactions',headers=headers(a)).json()['items'][0]['amount'] == '25.00'
    assert sync(client,b,[item]).status_code == 200
    assert client.get('/api/v1/transactions',headers=headers(a)).json()['items'][0]['amount'] == '25.00'

def test_validation_filters_and_pdf(client):
    a = account(client)
    assert client.get('/api/v1/transactions').status_code == 401
    invalid = change();invalid['data']['amount'] = '-1'
    assert sync(client,a,[invalid]).status_code == 422
    assert sync(client,a,[change()]).status_code == 200
    assert client.get('/api/v1/transactions?amount_min=26',headers=headers(a)).json()['total'] == 0
    pdf = client.post('/api/v1/reports/pdf',headers=headers(a),json={})
    assert pdf.status_code == 200 and pdf.content.startswith(b'%PDF')

def test_environment_guard():
    from app.config import Settings
    with pytest.raises(ValueError): Settings(app_env='dev',db_schema='qa')
    with pytest.raises(ValueError): Settings(app_env='prod',db_schema='prod')

def test_login_rate_limit(client):
    account(client)
    statuses = [client.post('/api/v1/auth/login',json={'email':'one@example.com','password':'incorrect-passphrase'}).status_code for _ in range(11)]
    assert statuses[-1] == 429
