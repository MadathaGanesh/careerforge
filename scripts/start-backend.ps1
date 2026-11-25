#Requires -Version 5.1

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$maven = Get-Command mvn -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $maven) {
    throw 'Maven is required. Install Maven 3.9+ and add it to PATH.'
}

if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    if ($env:SPRING_PROFILES_ACTIVE -match '(?:^|,)\s*(?:production|staging)\s*(?:,|$)') {
        throw 'Set JWT_SECRET explicitly for a staging or production run.'
    }

    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
        $env:JWT_SECRET = [Convert]::ToBase64String($bytes)
    } finally {
        $rng.Dispose()
    }
    Write-Host 'Generated a local JWT signing key for this PowerShell session.'
}

$backend = Join-Path (Split-Path -Parent $PSScriptRoot) 'backend'
Push-Location -LiteralPath $backend
try {
    & $maven.Path 'spring-boot:run'
    $mavenExit = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($mavenExit -ne 0) {
    exit $mavenExit
}
