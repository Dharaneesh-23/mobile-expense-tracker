[CmdletBinding()]
param([switch]$CheckDevice)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\Enter-Dev.ps1"
git --version
java -version
python --version
Write-Host 'Installed SDK platforms:'
Get-ChildItem -LiteralPath "$env:ANDROID_HOME\platforms" -Name
Write-Host 'SDK command-line tools present:'
Test-Path -LiteralPath "$env:ANDROID_HOME\cmdline-tools"
$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
$ghPath = if ($ghCommand) { $ghCommand.Source } else { 'C:\Program Files\GitHub CLI\gh.exe' }
if (Test-Path -LiteralPath $ghPath) { & $ghPath --version } else { Write-Warning 'GitHub CLI is unavailable.' }
if ($CheckDevice) { adb devices -l }
