"""Authenticated APK distribution from private GitHub release assets.

The GitHub credential stays server-side. Android receives only app metadata and
APK bytes through its authenticated update-service session.
"""
import re
import threading
import time
from typing import Literal
from urllib.parse import urlparse
import httpx
from fastapi import APIRouter,Depends,HTTPException,Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel,Field
from .config import settings
from .security import current_user

router=APIRouter(prefix='/api/v1/app-updates',tags=['private app updates'])
PACKAGES={'qa':'com.dharaneesh.expensetracker.qa','prod':'com.dharaneesh.expensetracker'}
MAX_APK=100_000_000

class UpdateManifest(BaseModel):
    channel: Literal['qa','prod']
    package_name: str
    version_code: int = Field(gt=0,le=2100000000)
    version_name: str = Field(min_length=1,max_length=80)
    sha256: str = Field(pattern=r'^[0-9a-f]{64}$')
    size: int = Field(gt=0,le=MAX_APK)
    apk_name: str = Field(pattern=r'^expense-(qa|prod)-[0-9]+\.apk$')
    notes: str = Field(default='',max_length=4000)
    min_sdk: int = Field(default=26,ge=26,le=100)

class ReleaseGateway:
    def __init__(self):
        self.cache={}
        self.lock=threading.Lock()

    def client(self):
        cfg=settings()
        token=cfg.updates_github_token.get_secret_value()
        if not token:raise HTTPException(503,'Private update publishing is not configured yet')
        if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+',cfg.updates_github_repository):
            raise HTTPException(503,'Invalid update repository configuration')
        return httpx.Client(timeout=30,follow_redirects=False,headers={
            'Authorization':'Bearer '+token,'Accept':'application/vnd.github+json',
            'X-GitHub-Api-Version':'2022-11-28','User-Agent':'personal-expense-updater'})

    def asset_response(self,client,asset_id):
        url=f'https://api.github.com/repos/{settings().updates_github_repository}/releases/assets/{int(asset_id)}'
        headers={'Accept':'application/octet-stream'}
        for _ in range(4):
            request=client.build_request('GET',url,headers=headers)
            # Never forward a GitHub token to a redirected asset-storage host.
            if urlparse(url).hostname!='api.github.com':request.headers.pop('Authorization',None)
            response=client.send(request,stream=True)
            if response.status_code in (301,302,303,307,308):
                target=response.headers.get('location','');response.close()
                host=urlparse(target).hostname or ''
                if urlparse(target).scheme!='https' or not (host=='github.com' or host.endswith('.githubusercontent.com')):
                    raise HTTPException(502,'Update storage returned an invalid redirect')
                url=target
                continue
            if response.status_code!=200:
                response.close()
                raise HTTPException(502,'Private update asset is unavailable')
            return response
        raise HTTPException(502,'Update storage redirected too many times')

    def release(self,channel):
        key=(channel,settings().updates_github_repository)
        with self.lock:
            cached=self.cache.get(key)
            if cached and cached[0]>time.monotonic():return cached[1]
        try:
            with self.client() as client:
                chosen=None
                for page in range(1,11):
                    response=client.get(f'https://api.github.com/repos/{settings().updates_github_repository}/releases',params={'per_page':100,'page':page})
                    if response.status_code!=200:raise HTTPException(502,'Cannot read private update releases')
                    releases=response.json()
                    if not isinstance(releases,list):raise HTTPException(502,'Invalid release response')
                    for release in releases:
                        match=re.fullmatch(r'android-'+channel+r'-v([0-9]+)',release.get('tag_name',''))
                        if not release.get('draft') and match:
                            code=int(match.group(1))
                            if chosen is None or code>chosen[0]:chosen=(code,release)
                    if len(releases)<100:break
                if chosen is None:raise HTTPException(404,'No update has been published for this channel')
                assets=chosen[1].get('assets',[])
                manifest_asset=next((a for a in assets if a.get('name')==f'update-{channel}.json'),None)
                if not manifest_asset:raise HTTPException(502,'Release metadata is missing')
                response=self.asset_response(client,manifest_asset['id'])
                try:
                    payload=bytearray()
                    for chunk in response.iter_bytes():
                        payload.extend(chunk)
                        if len(payload)>32_000:raise HTTPException(502,'Release metadata is too large')
                    manifest=UpdateManifest.model_validate_json(bytes(payload))
                finally:response.close()
                if manifest.channel!=channel or manifest.package_name!=PACKAGES[channel] or manifest.version_code!=chosen[0]:
                    raise HTTPException(502,'Release metadata does not match its channel')
                if manifest.apk_name!=f'expense-{channel}-{manifest.version_code}.apk':
                    raise HTTPException(502,'Invalid APK asset name')
                apk=next((a for a in assets if a.get('name')==manifest.apk_name),None)
                if not apk or apk.get('size')!=manifest.size:raise HTTPException(502,'Release APK size does not match metadata')
                result=(manifest,int(apk['id']))
            with self.lock:self.cache[key]=(time.monotonic()+60,result)
            return result
        except HTTPException:raise
        except Exception:raise HTTPException(502,'Private update service is temporarily unavailable') from None

gateway=ReleaseGateway()

@router.get('/{channel}')
def latest(channel:Literal['qa','prod'],user=Depends(current_user)):
    manifest,_=gateway.release(channel)
    return dict(manifest.model_dump(),download_path=f'/api/v1/app-updates/{channel}/download?version_code={manifest.version_code}')

@router.get('/{channel}/download')
def download(channel:Literal['qa','prod'],version_code:int=Query(gt=0),user=Depends(current_user)):
    manifest,asset_id=gateway.release(channel)
    if manifest.version_code!=version_code:raise HTTPException(409,'A newer release was published; check for updates again')
    client=gateway.client()
    try:response=gateway.asset_response(client,asset_id)
    except Exception:
        client.close()
        raise
    def chunks():
        count=0
        try:
            for chunk in response.iter_bytes(chunk_size=65536):
                count+=len(chunk)
                if count>manifest.size:raise RuntimeError('Update size exceeded manifest')
                yield chunk
        finally:
            response.close();client.close()
    return StreamingResponse(chunks(),media_type='application/vnd.android.package-archive',headers={
        'Content-Disposition':f'attachment; filename="{manifest.apk_name}"',
        'Content-Length':str(manifest.size),'Cache-Control':'private, no-store'})
