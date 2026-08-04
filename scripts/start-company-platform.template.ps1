param(
    [int]$Port = 8080,
    [string]$Workspace
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar = Join-Path $Root 'app.jar'
if (-not (Test-Path $Jar)) {
    throw "Jar not found: $Jar"
}

$Java = if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    Join-Path $env:JAVA_HOME 'bin\java.exe'
} else {
    'java'
}

$WorkspacePath = if ($Workspace -and $Workspace.Trim()) { $Workspace } else { Join-Path $Root 'workspace' }
New-Item -ItemType Directory -Path $WorkspacePath -Force | Out-Null
$env:COMPANY_PLATFORM_WORKSPACE = $WorkspacePath

& $Java "-Dserver.port=$Port" -jar $Jar
