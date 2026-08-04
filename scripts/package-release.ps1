param(
    [string]$RepoRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$OutRoot = (Join-Path (Get-Location).Path "dist-release"),
    [string]$Revision = "2.0.0-SNAPSHOT",
    [switch]$SkipBuild,
    [switch]$Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Copy-DirectoryContents {
    param([string]$Source, [string]$Destination)
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | Copy-Item -Destination $Destination -Recurse -Force
}

$repoPath = (Resolve-Path $RepoRoot).Path
$outPath = [System.IO.Path]::GetFullPath($OutRoot)
$platformVersion = ([xml](Get-Content -Raw -LiteralPath (Join-Path $repoPath "pom.xml"))).project.version
$releaseName = "company-agent-platform-$platformVersion"
$stage = Join-Path $outPath $releaseName
$mavenStage = Join-Path $outPath "agentscope-maven-repo-$Revision"
$runtimeStage = Join-Path $stage "runtime"

if ($Clean -and (Test-Path -LiteralPath $outPath)) {
    Remove-Item -LiteralPath $outPath -Recurse -Force
}
New-Item -ItemType Directory -Path $stage -Force | Out-Null

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "Required command not found: mvn"
}

if (-not $SkipBuild) {
    Write-Host "[1/5] Build platform ..."
    & mvn -f (Join-Path $repoPath "pom.xml") clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "Platform build failed: $LASTEXITCODE"
    }
} else {
    Write-Host "[1/5] Skip platform build"
}

$jar = Get-ChildItem -LiteralPath (Join-Path $repoPath "target") -Filter "company-platform-*.jar" -File |
    Where-Object { $_.Name -notlike "*-plain.jar" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw "Platform JAR not found under target/."
}
$jarEntries = & jar tf $jar.FullName 2>$null
if ($LASTEXITCODE -ne 0 -or -not ($jarEntries | Select-String '^BOOT-INF/lib/')) {
    throw "Platform JAR is not a Spring Boot executable JAR. Run a full Maven package before creating the runtime release."
}

Write-Host "[2/5] Stage platform runtime ..."
New-Item -ItemType Directory -Path $runtimeStage -Force | Out-Null
Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $runtimeStage "app.jar") -Force
Copy-Item -LiteralPath (Join-Path $repoPath "scripts\start-company-platform.template.ps1") -Destination (Join-Path $runtimeStage "start.ps1") -Force
Copy-Item -LiteralPath (Join-Path $repoPath "scripts\start.template.bat") -Destination (Join-Path $runtimeStage "start.bat") -Force
if (Test-Path -LiteralPath (Join-Path $repoPath "workspace\skills")) {
    Copy-DirectoryContents -Source (Join-Path $repoPath "workspace\skills") -Destination (Join-Path $runtimeStage "workspace\skills")
}
if (Test-Path -LiteralPath (Join-Path $repoPath "workspace\tools")) {
    Copy-DirectoryContents -Source (Join-Path $repoPath "workspace\tools") -Destination (Join-Path $runtimeStage "workspace\tools")
}

$runtimeReadme = @"
# Company Agent Platform runtime

powershell -File .\start.ps1
start.bat

The runtime JAR already contains the platform's Java dependencies, including AgentScope.
The AgentScope Maven archive is only needed when building the platform from source.
"@
Set-Content -LiteralPath (Join-Path $runtimeStage "README.md") -Value $runtimeReadme -Encoding UTF8

Write-Host "[3/5] Stage AgentScope Maven repository ..."
$localMavenRoot = Join-Path $env:USERPROFILE ".m2\repository"
$agentScopeSource = Join-Path $localMavenRoot "io\agentscope"
if (-not (Test-Path -LiteralPath $agentScopeSource -PathType Container)) {
    throw "AgentScope artifacts not found in local Maven repository: $agentScopeSource"
}

$revisionDirs = Get-ChildItem -LiteralPath $agentScopeSource -Directory -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -eq $Revision }
if (-not $revisionDirs) {
    throw "AgentScope revision $Revision was not found in $agentScopeSource"
}

New-Item -ItemType Directory -Path $mavenStage -Force | Out-Null
Copy-DirectoryContents -Source $agentScopeSource -Destination (Join-Path $mavenStage "io\agentscope")
Copy-Item -LiteralPath (Join-Path $repoPath "scripts\bootstrap-agentscope.ps1") -Destination (Join-Path $stage "bootstrap-agentscope.ps1") -Force

$licenseFile = Join-Path $mavenStage "AGENTSCOPE-LICENSE-APACHE-2.0.txt"
$localLicense = Join-Path $repoPath "third-party\AGENTSCOPE-LICENSE-APACHE-2.0.txt"
if (Test-Path -LiteralPath $localLicense -PathType Leaf) {
    Copy-Item -LiteralPath $localLicense -Destination $licenseFile -Force
} else {
    Write-Host "[3/5] Download Apache 2.0 license for the AgentScope release asset ..."
    Invoke-WebRequest -Uri "https://www.apache.org/licenses/LICENSE-2.0.txt" -OutFile $licenseFile -UseBasicParsing
}
Set-Content -LiteralPath (Join-Path $mavenStage "AGENTSCOPE-NOTICE.txt") -Value @"
AgentScope Java
Source: https://github.com/agentscope-ai/agentscope-java
License: Apache License 2.0
This archive contains AgentScope Maven artifacts used by Company Agent Platform.
"@ -Encoding UTF8
Copy-Item -LiteralPath $licenseFile -Destination (Join-Path $runtimeStage "AGENTSCOPE-LICENSE-APACHE-2.0.txt") -Force
Copy-Item -LiteralPath (Join-Path $mavenStage "AGENTSCOPE-NOTICE.txt") -Destination (Join-Path $runtimeStage "AGENTSCOPE-NOTICE.txt") -Force

$releaseReadme = @"
# Company Agent Platform release

## Runtime

Use `runtime\start.ps1` or `runtime\start.bat`. The runtime JAR is self-contained.

## Build from source

Install the AgentScope dependencies shipped with this release:

    powershell -File .\bootstrap-agentscope.ps1 -ArchivePath .\agentscope-maven-repo-$Revision.zip

Then build the platform from the source repository:

    mvn -DskipTests clean package

The AgentScope artifacts are distributed as a Maven repository archive so the platform does not depend on an adjacent AgentScope source directory.
"@
Set-Content -LiteralPath (Join-Path $stage "README.md") -Value $releaseReadme -Encoding UTF8

Write-Host "[4/5] Create archives ..."
$mavenZip = Join-Path $outPath "agentscope-maven-repo-$Revision.zip"
$runtimeZip = Join-Path $outPath "$releaseName-runtime.zip"
if (Test-Path -LiteralPath $mavenZip) { Remove-Item -LiteralPath $mavenZip -Force }
if (Test-Path -LiteralPath $runtimeZip) { Remove-Item -LiteralPath $runtimeZip -Force }
Compress-Archive -LiteralPath (Join-Path $mavenStage "io"), $licenseFile, (Join-Path $mavenStage "AGENTSCOPE-NOTICE.txt") -DestinationPath $mavenZip -CompressionLevel Optimal
Compress-Archive -LiteralPath $runtimeStage -DestinationPath $runtimeZip -CompressionLevel Optimal

Write-Host "[5/5] Release artifacts"
Write-Host "  $mavenZip"
Write-Host "  $runtimeZip"
Write-Host "  $(Join-Path $stage "README.md")"
