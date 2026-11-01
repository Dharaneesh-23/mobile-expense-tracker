[CmdletBinding()]
param([string]$KeystorePath='C:\Users\Dharaneesh\.android\debug.keystore')
$ErrorActionPreference='Stop'
$ghCommand=Get-Command gh -ErrorAction SilentlyContinue
$ghPath=if($ghCommand){$ghCommand.Source}else{'C:\Program Files\GitHub CLI\gh.exe'}
if(-not(Test-Path -LiteralPath $KeystorePath)){throw 'The existing debug signing key was not found.'}
try {
    $encodedSigningKey=[Convert]::ToBase64String([IO.File]::ReadAllBytes($KeystorePath))
    $encodedSigningKey | & $ghPath secret set ANDROID_DEBUG_KEYSTORE_BASE64 --repo Dharaneesh-23/mobile-expense-tracker
    if($LASTEXITCODE -ne 0){throw 'Could not store the signing secret. Check repository Secrets write permission.'}
    Write-Output 'Stored the existing debug key as an encrypted GitHub Actions secret. No key bytes were printed.'
} finally {$encodedSigningKey=$null}
