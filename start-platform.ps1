[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$SkipInstall,
    [switch]$SkipFrontendInstall,
    [switch]$BackendOnly,
    [switch]$FrontendOnly,
    [switch]$NoStopExisting,
    [switch]$SkipDemoMcp,
    [switch]$ForceInstall,
    [switch]$Stop,
    [switch]$SkipAgentScopeBootstrap,
    [string]$Workspace = "",
    [string]$AgentScopeArchive = "",
    [string]$AgentScopeArchiveUrl = "",
    [string]$MavenRepository = "",
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
$RuntimeStateDir = Join-Path $RepoRoot ".run"
$PidFile = Join-Path $RuntimeStateDir "platform-pids.json"
$script:StartedProcesses = @()
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

function Stop-ProcessTree {
    param([int]$ProcessId)

    $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty ProcessId)
    foreach ($childId in $children) {
        Stop-ProcessTree -ProcessId ([int]$childId)
    }

    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($process -and $process.Id -ne $PID) {
        Write-Host "[platform] Stop tracked process: PID $($process.Id) ($($process.ProcessName))"
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
}

function Stop-TrackedProcesses {
    if (-not (Test-Path -LiteralPath $PidFile)) {
        return
    }

    try {
        $tracked = @(Get-Content -LiteralPath $PidFile -Raw | ConvertFrom-Json)
        foreach ($entry in $tracked) {
            if ($entry.pid) {
                Stop-ProcessTree -ProcessId ([int]$entry.pid)
            }
        }
    } catch {
        Write-Warning "Unable to read tracked process state: $PidFile"
    }

    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Stop-PlatformServices {
    Stop-TrackedProcesses
    Stop-PortListener -Port $BackendPort -Label "backend"
    Stop-PortListener -Port $FrontendPort -Label "frontend"
    Stop-PortListener -Port $DemoMcpHttpPort -Label "demo MCP streamable-http"
    Stop-PortListener -Port $DemoMcpSsePort -Label "demo MCP sse"
    Write-Host "[platform] Stop command completed."
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
        $process = Start-Process `
            -FilePath $invocation.FilePath `
            -ArgumentList $visibleArgs `
            -WorkingDirectory $WorkingDirectory `
            -PassThru
        $script:StartedProcesses += [pscustomobject]@{ pid = $process.Id; title = $Title }
        return
    }

    $logPath = Join-Path $LogDir $LogName
    $errorLogPath = Join-Path $LogDir ($LogName -replace '\.log$', '.err.log')
    Write-Host "[platform]   log: $logPath"
    Write-Host "[platform]   err: $errorLogPath"
    $standardArgs = @($invocation.ArgumentList)

    $process = Start-Process `
        -FilePath $invocation.FilePath `
        -ArgumentList $standardArgs `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $logPath `
        -RedirectStandardError $errorLogPath `
        -WindowStyle Hidden `
        -PassThru
    $script:StartedProcesses += [pscustomobject]@{ pid = $process.Id; title = $Title }
}

function Test-MavenArtifact {
    param(
        [string]$MavenRepository,
        [string]$GroupPath,
        [string]$ArtifactId,
        [string]$Version
    )

    $artifactPath = Join-Path $MavenRepository "$GroupPath\$ArtifactId\$Version\$ArtifactId-$Version.jar"
    return Test-Path $artifactPath
}

function Test-AgentScopeArtifactsInstalled {
    param([string]$MavenRepository)
    return (Test-MavenArtifact -MavenRepository $MavenRepository -GroupPath "io\agentscope" -ArtifactId "agentscope-harness" -Version $Revision) `
        -and (Test-MavenArtifact -MavenRepository $MavenRepository -GroupPath "io\agentscope" -ArtifactId "agentscope-extensions-rag-simple" -Version $Revision)
}

if ($Stop) {
    Stop-PlatformServices
    exit 0
}

Require-Command "java"
Require-Command "mvn"
$WorkspaceDir = if ($Workspace -and $Workspace.Trim()) {
    $Workspace
} else {
    $WorkspaceDir
}
$WorkspaceDir = [System.IO.Path]::GetFullPath($WorkspaceDir)
$MavenRepository = if ($MavenRepository -and $MavenRepository.Trim()) {
    [System.IO.Path]::GetFullPath($MavenRepository)
} else {
    Join-Path $env:USERPROFILE ".m2\repository"
}
New-Item -ItemType Directory -Path $WorkspaceDir -Force | Out-Null
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
New-Item -ItemType Directory -Path $RuntimeStateDir -Force | Out-Null
$env:AGENT_PLATFORM_WORKSPACE = $WorkspaceDir
$env:AGENT_PLATFORM_PERSISTENCE_MODE = $PersistenceMode.ToLowerInvariant()
if ($SqliteUrl -and $SqliteUrl.Trim()) {
    $env:AGENT_PLATFORM_SQLITE_URL = $SqliteUrl.Trim()
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
    if (-not $SkipInstall) {
        if (-not (Test-AgentScopeArtifactsInstalled -MavenRepository $MavenRepository)) {
            if ($SkipAgentScopeBootstrap) {
                throw "AgentScope Maven artifacts are missing in $MavenRepository and bootstrap was disabled."
            }
            if ([string]::IsNullOrWhiteSpace($AgentScopeArchive) -and [string]::IsNullOrWhiteSpace($AgentScopeArchiveUrl)) {
                throw "AgentScope Maven artifacts are missing in $MavenRepository. Provide -AgentScopeArchive or -AgentScopeArchiveUrl, or install the artifacts first."
            }
            $bootstrap = Join-Path $RepoRoot "scripts\bootstrap-agentscope.ps1"
            if (-not (Test-Path -LiteralPath $bootstrap)) {
                throw "AgentScope bootstrap script not found: $bootstrap"
            }
            $bootstrapArgs = @(
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                $bootstrap,
                "-Revision",
                $Revision,
                "-MavenRepository",
                $MavenRepository
            )
            if (-not [string]::IsNullOrWhiteSpace($AgentScopeArchive)) {
                $bootstrapArgs += @("-ArchivePath", $AgentScopeArchive)
            } else {
                $bootstrapArgs += @("-ArchiveUrl", $AgentScopeArchiveUrl)
            }
            Write-Host "[platform] Bootstrapping AgentScope Maven artifacts..."
            & powershell.exe @bootstrapArgs
            if ($LASTEXITCODE -ne 0) {
                throw "AgentScope dependency bootstrap failed: $LASTEXITCODE"
            }
        }
        if (Test-AgentScopeArtifactsInstalled -MavenRepository $MavenRepository) {
            Write-Host "[platform] AgentScope artifacts already installed; skipping reactor install."
            if ($ForceInstall) {
                Write-Host "[platform] -ForceInstall is ignored in the standalone repository; install AgentScope artifacts from the AgentScope source repository."
            }
        } else {
            throw "AgentScope Maven artifacts are still missing in $MavenRepository after bootstrap."
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
        "-Dmaven.repo.local=$MavenRepository",
        "-DskipTests",
        "-DAGENT_PLATFORM_PERSISTENCE_MODE=$($env:AGENT_PLATFORM_PERSISTENCE_MODE)",
        "-Dspring-boot.run.arguments=--server.port=$BackendPort",
        "org.springframework.boot:spring-boot-maven-plugin:4.0.4:run"
    )
    if ($env:AGENT_PLATFORM_PERSISTENCE_MODE -eq "sqlite" -and $env:AGENT_PLATFORM_SQLITE_URL) {
        $backendCommandArgs =
            @(
                "-Dmaven.repo.local=$MavenRepository",
                "-DskipTests",
                "-DAGENT_PLATFORM_PERSISTENCE_MODE=$($env:AGENT_PLATFORM_PERSISTENCE_MODE)",
                "-DAGENT_PLATFORM_SQLITE_URL=$($env:AGENT_PLATFORM_SQLITE_URL)",
                "-Dspring-boot.run.arguments=--server.port=$BackendPort",
                "org.springframework.boot:spring-boot-maven-plugin:4.0.4:run"
            )
    }
    Start-PlatformProcess `
        -Title "Agent Platform Backend :$BackendPort" `
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
        -Title "Agent Platform Frontend :$FrontendPort" `
        -WorkingDirectory $FrontendDir `
        -CommandFilePath $npmPath `
        -CommandArgs $frontendCommandArgs `
        -LogName "frontend.log"
}

if ($script:StartedProcesses.Count -gt 0) {
    $script:StartedProcesses | ConvertTo-Json | Set-Content -LiteralPath $PidFile -Encoding UTF8
}

Write-Host ""
Write-Host "[platform] Startup commands dispatched."
Write-Host "[platform] Workspace: $WorkspaceDir"
Write-Host "[platform] PersistenceMode: $env:AGENT_PLATFORM_PERSISTENCE_MODE"
if ($env:AGENT_PLATFORM_PERSISTENCE_MODE -eq "sqlite" -and $env:AGENT_PLATFORM_SQLITE_URL) {
    Write-Host "[platform] SQLite URL: $env:AGENT_PLATFORM_SQLITE_URL"
}
Write-Host "[platform] Logs:     $LogDir"
if (-not $FrontendOnly) {
    Write-Host "[platform] Backend:  http://127.0.0.1:$BackendPort"
}
if (-not $BackendOnly) {
    Write-Host "[platform] Frontend: http://127.0.0.1:$FrontendPort/platform/live?domain=platform&org_id=platform&user_id=platform_admin"
}
