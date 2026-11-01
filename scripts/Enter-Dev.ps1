[CmdletBinding()]
param(
    [string]$SdkPath = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$JdkPath = 'C:\Program Files\Android\Android Studio\jbr'
)

$ErrorActionPreference = 'Stop'
# Sandboxed terminals may redirect LOCALAPPDATA away from the installed SDK.
if (-not $PSBoundParameters.ContainsKey('SdkPath') -and
    -not (Test-Path -LiteralPath "$SdkPath\platform-tools\adb.exe")) {
    $SdkPath = 'C:\Users\Dharaneesh\AppData\Local\Android\Sdk'
}
if (-not (Test-Path -LiteralPath "$SdkPath\platform-tools\adb.exe")) {
    throw "Android platform tools not found under $SdkPath. Supply -SdkPath."
}
if (-not (Test-Path -LiteralPath "$JdkPath\bin\java.exe")) {
    throw "Android Studio Java runtime not found under $JdkPath. Supply -JdkPath."
}

# Changes apply only to the current PowerShell process.
$env:ANDROID_HOME = $SdkPath
$env:ANDROID_SDK_ROOT = $SdkPath
$env:JAVA_HOME = $JdkPath
$env:Path = "$JdkPath\bin;$SdkPath\platform-tools;$SdkPath\emulator;$env:Path"
Write-Host "Android SDK: $env:ANDROID_HOME"
Write-Host "Java runtime: $env:JAVA_HOME"
Write-Host 'Ready. Use adb devices -l to check your phone.'
