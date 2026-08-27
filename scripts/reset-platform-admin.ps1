[CmdletBinding()]
param(
    [string]$DatabasePath,
    [string]$Email = 'admin@platform.local',
    [Security.SecureString]$NewPassword
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($DatabasePath)) {
    $DatabasePath = Join-Path $PSScriptRoot '..\workspace\platform-platform.db'
}
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$database = (Resolve-Path $DatabasePath).Path

if (-not $database.StartsWith($repoRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Database must be inside the repository: $repoRoot"
}

$sqlite = (Get-Command sqlite3 -ErrorAction Stop).Source
$emailValue = $Email.Trim().ToLowerInvariant()
if ($emailValue -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') {
    throw "Invalid email: $Email"
}

function SqlLiteral([string]$value) {
    return "'" + $value.Replace("'", "''") + "'"
}

function Base64Url([byte[]]$bytes) {
    $value = [Convert]::ToBase64String($bytes)
    return ($value.TrimEnd('=') -replace '\+', '-' -replace '/', '_')
}

$emailSql = SqlLiteral $emailValue
$identityOutput = & $sqlite -batch -noheader $database "SELECT u.user_id || '|' || u.status || '|' || COALESCE(m.role,'') FROM platform_users u LEFT JOIN platform_memberships m ON m.user_id = u.user_id WHERE lower(u.email) = lower($emailSql) LIMIT 1;"
$identity = if ($null -eq $identityOutput) { '' } else { ([string]::Join([Environment]::NewLine, @($identityOutput))).Trim() }
if (-not $identity) {
    throw "Admin account not found: $emailValue"
}
$identityParts = $identity -split '\|', 3
if ($identityParts.Count -lt 3 -or $identityParts[1] -ne 'ACTIVE' -or $identityParts[2] -ne 'PLATFORM_ADMIN') {
    throw "The account is not an active platform admin: $identity"
}

$lockProbe = @(& $sqlite -batch -noheader $database "PRAGMA busy_timeout = 0; BEGIN IMMEDIATE; ROLLBACK; SELECT 'ready';" 2>&1)
$lockProbeExitCode = $LASTEXITCODE
$lockProbeText = if ($null -eq $lockProbe) { '' } else { ([string]::Join([Environment]::NewLine, $lockProbe)).Trim() }
if ($lockProbeExitCode -ne 0 -or $lockProbeText -notmatch 'ready') {
    throw "Database is locked. Stop all running Agent Platform backend processes, then run this script again. SQLite output: $lockProbeText"
}

$securePassword = if ($null -ne $NewPassword) {
    $NewPassword
} else {
    Read-Host "Enter the new admin password (at least 10 characters)" -AsSecureString
}
$passwordPointer = [IntPtr]::Zero
$password = $null
try {
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    if ([string]::IsNullOrWhiteSpace($password) -or $password.Length -lt 10) {
        throw 'Password must be at least 10 characters.'
    }

    $salt = New-Object byte[] 16
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($salt)
    } finally {
        $random.Dispose()
    }
    $deriver = [Security.Cryptography.Rfc2898DeriveBytes]::new(
        $password,
        $salt,
        210000,
        [Security.Cryptography.HashAlgorithmName]::SHA256)
    try {
        $derived = $deriver.GetBytes(32)
    } finally {
        $deriver.Dispose()
    }
    $encoder = Base64Url $salt
    $digest = Base64Url $derived
    $hashText = 'pbkdf2' + [char]36 + $encoder + [char]36 + $digest
    $passwordHash = SqlLiteral $hashText
} finally {
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    $password = $null
}

$updateSql = @"
PRAGMA busy_timeout = 5000;
BEGIN IMMEDIATE;
UPDATE platform_users
SET password_hash = $passwordHash,
    must_change_password = 0,
    failed_attempts = 0,
    locked_until = NULL
WHERE lower(email) = lower($emailSql);
SELECT changes();
UPDATE platform_sessions
SET revoked_at = strftime('%Y-%m-%dT%H:%M:%fZ','now')
WHERE user_id = (SELECT user_id FROM platform_users WHERE lower(email) = lower($emailSql))
  AND revoked_at IS NULL;
COMMIT;
"@
$updateOutput = @(& $sqlite -batch -noheader $database $updateSql 2>&1)
$sqliteExitCode = $LASTEXITCODE
$result = if ($null -eq $updateOutput) { '' } else { ([string]::Join([Environment]::NewLine, $updateOutput)).Trim() }
if ($sqliteExitCode -ne 0 -or $result -notmatch '1') {
    throw "Admin password update failed. SQLite output: $result"
}

Write-Host "Admin password reset: $emailValue" -ForegroundColor Green
Write-Host 'Open /platform/live/access and sign in with the email and new password.'
