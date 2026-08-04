param(
    [switch]$SkipInstall,
    [switch]$SkipFrontendInstall,
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [switch]$NoStopExisting,
    [switch]$SkipDemoMcp,
    [switch]$ForceInstall,
    [string]$Workspace = "",
    [switch]$VisibleWindows,
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [int]$DemoMcpHttpPort = 8765,
    [int]$DemoMcpSsePort = 8766,
    [string]$Revision = "2.0.0-SNAPSHOT",
    [ValidateSet("file", "sqlite")]
    [string]$PersistenceMode = "sqlite",
    [string]$SqliteUrl = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$RepoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$BackendDir = $RepoRoot
$FrontendDir = Join-Path $RepoRoot "frontend\live-console"
$WorkspaceDir = Join-Path $RepoRoot "workspace"
$DemoMcpScript = Join-Path $BackendDir "mcp-servers\platform-demo\server.mjs"
$LogDir = Join-Path $RepoRoot "logs\platform"
$env:NO_COLOR = "1"
$env:FORCE_COLOR = "0"
$env:NODE_DISABLE_COLORS = "1"

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $Name"
    }
}

function Quote-CmdArg {
    param([string]$Value)
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Build-CommandInvocation {
    param(
        [string]$CommandFilePath,
        [string[]]$CommandArgs
    )
    $ext = [System.IO.Path]::GetExtension($CommandFilePath).ToLowerInvariant()
    if ($ext -in @(".cmd", ".bat")) {
        $args = @("/c", (Quote-CmdArg $CommandFilePath))
        if ($null -ne $CommandArgs -and $CommandArgs.Count -gt 0) {
            $args += $CommandArgs
        }
        return @{
            FilePath = "cmd.exe"
            ArgumentList = $args
        }
    }
    if ($ext -eq ".ps1") {
        $args = @(
            "-NoProfile",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            $CommandFilePath
        )
        if ($null -ne $CommandArgs -and $CommandArgs.Count -gt 0) {
            $args += $CommandArgs
        }
        return @{
            FilePath = "powershell.exe"
            ArgumentList = $args
        }
    }

    return @{
        FilePath = $CommandFilePath
        ArgumentList = $CommandArgs
    }
}

function Stop-PortListener {
    param(
        [int]$Port,
        [string]$Label
    )

    $connections = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if (-not $connections) {
        return
    }

    $processIds = $connections | Select-Object -ExpandProperty OwningProcess -Unique
    foreach ($processId in $processIds) {
        if (-not $processId -or $processId -eq $PID) {
            continue
        }
        $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
        if (-not $process) {
            continue
        }
        Write-Host "[platform] Stop existing $Label listener on port ${Port}: PID $processId ($($process.ProcessName))"
        Stop-Process -Id $processId -Force
    }
}

function Start-PlatformProcess {
    param(
        [string]$Title,
        [string]$WorkingDirectory,
        [string]$CommandFilePath,
        [string[]]$CommandArgs,
        [string]$LogName
    )

    Write-Host "[platform] Start: $Title"
    $invocation = Build-CommandInvocation -CommandFilePath $CommandFilePath -CommandArgs $CommandArgs

    if ($VisibleWindows) {
        $visibleArgs = @($invocation.ArgumentList)
        Start-Process `
            -FilePath $invocation.FilePath `
            -ArgumentList $visibleArgs `
            -WorkingDirectory $WorkingDirectory
        return
    }

    $logPath = Join-Path $LogDir $LogName
    $errorLogPath = Join-Path $LogDir ($LogName -replace '\.log$', '.err.log')
    Write-Host "[platform]   log: $logPath"
    Write-Host "[platform]   err: $errorLogPath"
    $standardArgs = @($invocation.ArgumentList)

    Start-Process `
        -FilePath $invocation.FilePath `
        -ArgumentList $standardArgs `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $logPath `
        -RedirectStandardError $errorLogPath `
        -WindowStyle Hidden
}

function Test-MavenArtifact {
    param(
        [string]$GroupPath,
        [string]$ArtifactId,
        [string]$Version
    )

    $artifactPath = Join-Path $env:USERPROFILE ".m2\repository\$GroupPath\$ArtifactId\$Version\$ArtifactId-$Version.jar"
    return Test-Path $artifactPath
}

function Test-AgentScopeArtifactsInstalled {
    return (Test-MavenArtifact -GroupPath "io\agentscope" -ArtifactId "agentscope-harness" -Version $Revision) `
        -and (Test-MavenArtifact -GroupPath "io\agentscope" -ArtifactId "agentscope-extensions-rag-simple" -Version $Revision)
}

Require-Command "java"
Require-Command "mvn"
$WorkspaceDir = if ($Workspace -and $Workspace.Trim()) {
    $Workspace
} else {
    $WorkspaceDir
}
$WorkspaceDir = [System.IO.Path]::GetFullPath($WorkspaceDir)
New-Item -ItemType Directory -Path $WorkspaceDir -Force | Out-Null
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
$env:COMPANY_PLATFORM_WORKSPACE = $WorkspaceDir
$env:COMPANY_PLATFORM_PERSISTENCE_MODE = $PersistenceMode.ToLowerInvariant()
if ($SqliteUrl -and $SqliteUrl.Trim()) {
    $env:COMPANY_PLATFORM_SQLITE_URL = $SqliteUrl.Trim()
}

if (-not $BackendOnly) {
    Require-Command "node"
    Require-Command "npm"
    if (-not (Test-Path $FrontendDir)) {
        throw "Frontend directory not found: $FrontendDir"
    }
}

if (-not $FrontendOnly -and -not $SkipDemoMcp) {
    Require-Command "node"
    if (-not (Test-Path $DemoMcpScript)) {
        throw "Demo MCP server script not found: $DemoMcpScript"
    }
}

if (-not $NoStopExisting) {
    if (-not $FrontendOnly) {
        Stop-PortListener -Port $BackendPort -Label "backend"
    }
    if (-not $BackendOnly) {
        Stop-PortListener -Port $FrontendPort -Label "frontend"
    }
    if (-not $FrontendOnly -and -not $SkipDemoMcp) {
        Stop-PortListener -Port $DemoMcpHttpPort -Label "demo MCP streamable-http"
        Stop-PortListener -Port $DemoMcpSsePort -Label "demo MCP sse"
    }
}

if (-not $FrontendOnly) {
    $BackendClassesDir = Join-Path $RepoRoot "target\classes"
    if (Test-Path $BackendClassesDir) {
        Write-Host "[platform] Cleaning backend compiled classes..."
        Remove-Item -LiteralPath $BackendClassesDir -Recurse -Force
    }
    if (-not $SkipInstall) {
        if (-not (Test-AgentScopeArtifactsInstalled)) {
            throw "AgentScope Maven artifacts are missing. Install agentscope-harness and agentscope-extensions-rag-simple first, or run with -SkipInstall after configuring a released version."
        } else {
            Write-Host "[platform] AgentScope artifacts already installed; skipping reactor install."
            if ($ForceInstall) {
                Write-Host "[platform] -ForceInstall is ignored in the standalone repository; install AgentScope artifacts from the AgentScope source repository."
            }
        }
    }
}

if (-not $BackendOnly -and -not $SkipFrontendInstall) {
    $NodeModules = Join-Path $FrontendDir "node_modules"
    if (-not (Test-Path $NodeModules)) {
        Push-Location $FrontendDir
        try {
            Write-Host "[platform] Installing frontend dependencies..."
            npm install
        } finally {
            Pop-Location
        }
    }
}

if (-not $FrontendOnly) {
    if (-not $SkipDemoMcp) {
        $nodePath = (Get-Command "node").Source
        Start-PlatformProcess `
            -Title "Demo MCP streamable-http :$DemoMcpHttpPort" `
            -WorkingDirectory $BackendDir `
            -CommandFilePath $nodePath `
            -CommandArgs @("mcp-servers/platform-demo/server.mjs", "--transport", "streamable-http", "--port", "$DemoMcpHttpPort") `
            -LogName "mcp-streamable-http.log"
        Start-PlatformProcess `
            -Title "Demo MCP sse :$DemoMcpSsePort" `
            -WorkingDirectory $BackendDir `
            -CommandFilePath $nodePath `
            -CommandArgs @("mcp-servers/platform-demo/server.mjs", "--transport", "sse", "--port", "$DemoMcpSsePort") `
            -LogName "mcp-sse.log"
    }

    $backendMvnPath = (Get-Command "mvn").Source
    $backendCommandArgs = @(
        "-DskipTests",
        "-DCOMPANY_PLATFORM_PERSISTENCE_MODE=$($env:COMPANY_PLATFORM_PERSISTENCE_MODE)",
        "-Dspring-boot.run.arguments=--server.port=$BackendPort",
        "org.springframework.boot:spring-boot-maven-plugin:4.0.4:run"
    )
    if ($env:COMPANY_PLATFORM_PERSISTENCE_MODE -eq "sqlite" -and $env:COMPANY_PLATFORM_SQLITE_URL) {
        $backendCommandArgs =
            @(
                "-DskipTests",
                "-DCOMPANY_PLATFORM_PERSISTENCE_MODE=$($env:COMPANY_PLATFORM_PERSISTENCE_MODE)",
                "-DCOMPANY_PLATFORM_SQLITE_URL=$($env:COMPANY_PLATFORM_SQLITE_URL)",
                "-Dspring-boot.run.arguments=--server.port=$BackendPort",
                "org.springframework.boot:spring-boot-maven-plugin:4.0.4:run"
            )
    }
    Start-PlatformProcess `
        -Title "Company Platform Backend :$BackendPort" `
        -WorkingDirectory $BackendDir `
        -CommandFilePath $backendMvnPath `
        -CommandArgs $backendCommandArgs `
        -LogName "backend.log"
}

if (-not $BackendOnly) {
    $npmPath = (Get-Command "npm").Source
    $frontendCommandArgs = @(
        "run",
        "dev",
        "--",
        "--host",
        "0.0.0.0",
        "--port",
        "$FrontendPort"
    )
    Start-PlatformProcess `
        -Title "Company Platform Frontend :$FrontendPort" `
        -WorkingDirectory $FrontendDir `
        -CommandFilePath $npmPath `
        -CommandArgs $frontendCommandArgs `
        -LogName "frontend.log"
}

Write-Host ""
Write-Host "[platform] Startup commands dispatched."
Write-Host "[platform] Workspace: $WorkspaceDir"
Write-Host "[platform] PersistenceMode: $env:COMPANY_PLATFORM_PERSISTENCE_MODE"
if ($env:COMPANY_PLATFORM_PERSISTENCE_MODE -eq "sqlite" -and $env:COMPANY_PLATFORM_SQLITE_URL) {
    Write-Host "[platform] SQLite URL: $env:COMPANY_PLATFORM_SQLITE_URL"
}
Write-Host "[platform] Logs:     $LogDir"
if (-not $FrontendOnly) {
    Write-Host "[platform] Backend:  http://127.0.0.1:$BackendPort"
}
if (-not $BackendOnly) {
    Write-Host "[platform] Frontend: http://127.0.0.1:$FrontendPort/platform/live?domain=platform&org_id=platform&user_id=platform_admin"
}
