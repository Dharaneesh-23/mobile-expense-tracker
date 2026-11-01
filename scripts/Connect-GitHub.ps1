[CmdletBinding()]
param([switch]$UsePat)

$ErrorActionPreference = 'Stop'
$ghCommand = Get-Command gh -ErrorAction SilentlyContinue
$ghPath = if ($ghCommand) { $ghCommand.Source } else { 'C:\Program Files\GitHub CLI\gh.exe' }
if (-not (Test-Path -LiteralPath $ghPath)) { throw 'Install GitHub CLI first.' }

if ($UsePat) {
    # Hidden input avoids command history and keeps the PAT out of project files.
    $secureToken = Read-Host 'GitHub PAT (hidden)' -AsSecureString
    $tokenPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
    try {
        $plainToken = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPointer)
        $plainToken | & $ghPath auth login --hostname github.com --git-protocol https --with-token
        if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication failed.' }
    } finally {
        $plainToken = $null
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPointer)
        $secureToken.Dispose()
    }
} else {
    & $ghPath auth login --hostname github.com --git-protocol https --web
    if ($LASTEXITCODE -ne 0) { throw 'GitHub authentication failed.' }
}

& $ghPath auth setup-git --hostname github.com
if ($LASTEXITCODE -ne 0) { throw 'Git credential helper setup failed.' }
& $ghPath repo view Dharaneesh-23/mobile-expense-tracker --json nameWithOwner,isPrivate,defaultBranchRef
if ($LASTEXITCODE -ne 0) { throw 'Authenticated account cannot access the project repository.' }
Write-Host 'GitHub connection verified. No branches have been pushed by this script.'
