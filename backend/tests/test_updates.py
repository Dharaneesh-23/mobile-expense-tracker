import hashlib
import json
import httpx
import pytest
from pydantic import SecretStr
from test_api import client,account,headers
from app import updates
from app.config import settings

APK=b'synthetic-apk-content'
def manifest(channel='qa',version=201):
    return {'channel':channel,'package_name':updates.PACKAGES[channel],'version_code':version,
            'version_name':f'0.3.0-{version}','sha256':hashlib.sha256(APK).hexdigest(),'size':len(APK),
            'apk_name':f'expense-{channel}-{version}.apk','min_sdk':26,'notes':'Private test release'}

@pytest.fixture(autouse=True)
def reset_gateway():
    updates.gateway.cache.clear()
    yield
    updates.gateway.cache.clear()

def mock_github(monkeypatch,bad_channel=False,evil_redirect=False):
    requests=[]
    def handler(request):
        requests.append(request)
        path=request.url.path
        if path.endswith('/releases'):
            return httpx.Response(200,json=[
                {'tag_name':'android-prod-v401','draft':False,'assets':[]},
                {'tag_name':'android-qa-v201','draft':False,'assets':[{'id':11,'name':'update-qa.json','size':400},{'id':22,'name':'expense-qa-201.apk','size':len(APK)}]},
                {'tag_name':'android-qa-v102','draft':False,'assets':[]}])
        if path.endswith('/assets/11'):
            if evil_redirect:return httpx.Response(302,headers={'location':'https://evil.example/metadata'})
            return httpx.Response(200,json=manifest('prod' if bad_channel else 'qa'))
        if path.endswith('/assets/22'):
            return httpx.Response(302,headers={'location':'https://release-assets.githubusercontent.com/test/apk'})
        if request.url.host=='release-assets.githubusercontent.com':
            assert 'authorization' not in request.headers
            return httpx.Response(200,content=APK)
        raise AssertionError('Unexpected upstream request')
    monkeypatch.setattr(updates.gateway,'client',lambda:httpx.Client(transport=httpx.MockTransport(handler),headers={'Authorization':'Bearer synthetic-github-token'}))
    return requests

def test_update_routes_require_login_and_disabled_service_is_clear(client,monkeypatch):
    assert client.get('/api/v1/app-updates/qa').status_code==401
    assert client.get('/api/v1/app-updates/qa/download?version_code=201').status_code==401
    user=account(client)
    monkeypatch.setattr(settings(),'updates_github_token',SecretStr(''))
    assert client.get('/api/v1/app-updates/qa',headers=headers(user)).status_code==503

def test_private_channel_metadata_and_stream_never_expose_github_token(client,monkeypatch):
    requests=mock_github(monkeypatch)
    user=account(client)
    result=client.get('/api/v1/app-updates/qa',headers=headers(user))
    assert result.status_code==200,result.text
    assert result.json()['version_code']==201
    assert result.json()['package_name']==updates.PACKAGES['qa']
    assert 'synthetic-github-token' not in result.text
    apk=client.get(result.json()['download_path'],headers=headers(user))
    assert apk.status_code==200 and apk.content==APK
    assert any(r.url.host=='release-assets.githubusercontent.com' for r in requests)

def test_changed_release_requires_rechecking(client,monkeypatch):
    mock_github(monkeypatch)
    assert client.get('/api/v1/app-updates/qa/download?version_code=100',headers=headers(account(client))).status_code==409

def test_wrong_channel_manifest_is_rejected(client,monkeypatch):
    mock_github(monkeypatch,bad_channel=True)
    assert client.get('/api/v1/app-updates/qa',headers=headers(account(client))).status_code==502

def test_untrusted_asset_redirect_is_rejected(client,monkeypatch):
    requests=mock_github(monkeypatch,evil_redirect=True)
    assert client.get('/api/v1/app-updates/qa',headers=headers(account(client))).status_code==502
    assert all(r.url.host=='api.github.com' for r in requests)
