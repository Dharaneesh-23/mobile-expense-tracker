[CmdletBinding()]
param([ValidateSet('dev','qa','prod')][string]$Environment='dev', [switch]$Release, [switch]$Test)
$ErrorActionPreference='Stop'
. "$PSScriptRoot\Enter-Dev.ps1"
$androidRoot=Join-Path (Split-Path $PSScriptRoot) 'android'
$socketDir=Join-Path $androidRoot 'build\tmp'
New-Item -ItemType Directory -Force -Path $socketDir | Out-Null
$oldJavaOptions=$env:JAVA_TOOL_OPTIONS
try {
    # Windows Java sockets need a short, ordinary path in sandboxed terminals.
    $env:JAVA_TOOL_OPTIONS=($oldJavaOptions+' -Djdk.net.unixdomain.tmpdir='+$socketDir.Replace('\','/')).Trim()
    $variant=$Environment.Substring(0,1).ToUpper()+$Environment.Substring(1)+$(if($Release){'Release'}else{'Debug'})
    $tasks=@(":app:assemble$variant")
    if($Test){$tasks+=":app:test${variant}UnitTest";$tasks+=":app:lint$variant"}
    Push-Location $androidRoot
    try { & .\gradlew.bat --no-daemon @tasks; if($LASTEXITCODE -ne 0){throw 'Android build failed'} }
    finally { Pop-Location }
} finally { $env:JAVA_TOOL_OPTIONS=$oldJavaOptions }
