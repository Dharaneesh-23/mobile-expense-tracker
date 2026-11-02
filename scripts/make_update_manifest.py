"""Verify a built APK and generate immutable release metadata without embedding credentials."""
import argparse
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

def generate(apk,channel,version_code,version_name,notes):
    package={'qa':'com.dharaneesh.expensetracker.qa','prod':'com.dharaneesh.expensetracker'}[channel]
    sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    if not sdk:raise ValueError('Set ANDROID_HOME before generating release metadata')
    suffix='.exe' if os.name=='nt' else ''
    aapt=Path(sdk)/'build-tools/36.1.0'/('aapt2'+suffix)
    badging=subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True,encoding='utf-8')
    signer=Path(sdk)/'build-tools/36.1.0'/('apksigner.bat' if os.name=='nt' else 'apksigner')
    command=(['cmd','/c'] if os.name=='nt' else [])+[str(signer),'verify','--print-certs',str(apk)]
    certificates=subprocess.check_output(command,text=True,encoding='utf-8')
    fingerprints=re.findall(r'certificate SHA-256 digest: ([0-9a-fA-F]+)',certificates)
    expected_certificate=(Path(__file__).resolve().parents[1]/'android/signing-certificate.sha256').read_text().strip().lower()
    if len(fingerprints)!=1 or fingerprints[0].lower()!=expected_certificate:
        raise ValueError('APK signing certificate does not match the installed personal-use apps; refusing publication')
    for pattern,expected in [(r"package: name='([^']+)'",package),(r"versionCode='([^']+)'",str(version_code)),(r"versionName='([^']+)'",version_name)]:
        match=re.search(pattern,badging)
        if not match or match.group(1)!=expected:raise ValueError('Built APK does not match requested release metadata')
    size=apk.stat().st_size
    if not 0<size<=100_000_000 or not 100<version_code<=2100000000:raise ValueError('Invalid update size or version')
    if len(notes)>4000:raise ValueError('Release notes exceed 4000 characters')
    digest=hashlib.sha256()
    with apk.open('rb') as stream:
        for chunk in iter(lambda:stream.read(65536),b''):digest.update(chunk)
    minimum=re.search(r"(?:minSdkVersion|sdkVersion):'([0-9]+)'",badging)
    if minimum is None:raise ValueError('APK min SDK could not be determined')
    return {'channel':channel,'package_name':package,'version_code':version_code,'version_name':version_name,
            'sha256':digest.hexdigest(),'signing_sha256':expected_certificate,'size':size,'apk_name':f'expense-{channel}-{version_code}.apk','min_sdk':int(minimum.group(1)),'notes':notes}

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--apk',type=Path,required=True)
    parser.add_argument('--channel',choices=['qa','prod'],required=True)
    parser.add_argument('--version-code',type=int,required=True)
    parser.add_argument('--version-name',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    payload=generate(args.apk,args.channel,args.version_code,args.version_name,os.environ.get('RELEASE_NOTES','Personal-use app update'))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(payload,indent=2),encoding='utf-8')
    print(f'Verified {args.channel} APK and wrote version {args.version_code} update metadata.')
