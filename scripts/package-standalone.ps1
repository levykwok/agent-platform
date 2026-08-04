param(
    [string]$RepoRoot = (Get-Location).Path,
    [string]$OutRoot = (Join-Path (Get-Location).Path 'dist-runtime'),
    [switch]$SkipBuild,
    [switch]$CleanBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Copy-WorkspaceTemplate {
    param([string]$SourceDir, [string]$TargetDir)
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
    foreach ($relative in @('skills', 'tools')) {
        $source = Join-Path $SourceDir $relative
        if (Test-Path $source) {
            Copy-Item -Path $source -Destination $TargetDir -Recurse -Force
        }
    }
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Required command not found: mvn'
}

$repoPath = (Resolve-Path $RepoRoot).Path
$outPath = Join-Path $OutRoot 'company-platform'

if ($CleanBuild -and (Test-Path $outPath)) {
    Remove-Item -Path $outPath -Recurse -Force
}
New-Item -ItemType Directory -Path $outPath -Force | Out-Null

if (-not $SkipBuild) {
    Write-Host '[1/4] build company platform ...'
    & mvn -f (Join-Path $repoPath 'pom.xml') clean package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        throw "mvn build failed: $LASTEXITCODE"
    }
}

$jarPath = Get-ChildItem -Path (Join-Path $repoPath 'target') -Filter 'company-platform-*.jar' -File |
    Where-Object { $_.Name -notlike '*-plain.jar' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jarPath) {
    throw 'build output not found: target/company-platform-*.jar'
}

Write-Host '[2/4] copy runtime files ...'
Copy-Item -Path $jarPath.FullName -Destination (Join-Path $outPath 'app.jar') -Force
Copy-WorkspaceTemplate -SourceDir (Join-Path $repoPath 'workspace') -TargetDir (Join-Path $outPath 'workspace')
Copy-Item -Path (Join-Path $repoPath 'scripts\start-company-platform.template.ps1') -Destination (Join-Path $outPath 'start.ps1') -Force
Copy-Item -Path (Join-Path $repoPath 'scripts\start.template.bat') -Destination (Join-Path $outPath 'start.bat') -Force

$runDoc = @"
# 运行说明（Company Platform）

powershell -File .\start.ps1
start.bat

默认端口 8080，可用参数 -Port 覆盖：
.\start.ps1 -Port 9090

关键环境变量（可选）
- COMPANY_PLATFORM_WORKSPACE：默认指向 ./workspace
"@
Set-Content -Path (Join-Path $outPath 'README.md') -Value $runDoc -Encoding UTF8

Write-Host "[done] package: $outPath"
