#Requires -Version 5.1

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$envFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (Test-Path -LiteralPath $envFile) {
    Write-Host 'Using the existing .env. Make sure JWT_SECRET is set before starting Compose.'
    return
}

$jwtBytes = New-Object byte[] 32
$databaseBytes = New-Object byte[] 24
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $rng.GetBytes($jwtBytes)
    $rng.GetBytes($databaseBytes)
} finally {
    $rng.Dispose()
}

$jwtSecret = [Convert]::ToBase64String($jwtBytes)
$databasePassword = [BitConverter]::ToString($databaseBytes).Replace('-', '').ToLowerInvariant()
$contents = "# Local CareerForge Docker credentials.`nJWT_SECRET=$jwtSecret`nDB_PASSWORD=$databasePassword`n`n# Set a SerpApi key to enable live salary research.`nSERPAPI_API_KEY=`n"
$utf8 = New-Object System.Text.UTF8Encoding($false)
$bytes = $utf8.GetBytes($contents)
$stream = [System.IO.File]::Open($envFile, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
try {
    $stream.Write($bytes, 0, $bytes.Length)
} finally {
    $stream.Dispose()
}

Write-Host 'Created .env for local Docker Compose.'
