param(
    [string]$ArchivePath = "",
    [string]$ArchiveUrl = "",
    [string]$MavenRepository = (Join-Path $env:USERPROFILE ".m2\repository"),
    [string]$Revision = "2.0.0-SNAPSHOT",
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ArchivePath) -and [string]::IsNullOrWhiteSpace($ArchiveUrl)) {
    throw "Provide -ArchivePath or -ArchiveUrl for the AgentScope Maven repository archive."
}
if (-not [string]::IsNullOrWhiteSpace($ArchivePath) -and -not [string]::IsNullOrWhiteSpace($ArchiveUrl)) {
    throw "Use only one of -ArchivePath and -ArchiveUrl."
}

$MavenRepository = [System.IO.Path]::GetFullPath($MavenRepository)
New-Item -ItemType Directory -Path $MavenRepository -Force | Out-Null

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("agent-platform-agentscope-" + [guid]::NewGuid().ToString("N"))
$archive = $ArchivePath
try {
    New-Item -ItemType Directory -Path $tempRoot -Force | Out-Null

    if (-not [string]::IsNullOrWhiteSpace($ArchiveUrl)) {
        $archive = Join-Path $tempRoot "agentscope-maven-repo.zip"
        Write-Host "[agentscope] Download: $ArchiveUrl"
        Invoke-WebRequest -Uri $ArchiveUrl -OutFile $archive -UseBasicParsing
    } else {
        $archive = [System.IO.Path]::GetFullPath($ArchivePath)
    }

    if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
        throw "AgentScope Maven repository archive not found: $archive"
    }

    $extractRoot = Join-Path $tempRoot "extract"
    Expand-Archive -LiteralPath $archive -DestinationPath $extractRoot -Force

    $ioRoot = Join-Path $extractRoot "io"
    if (-not (Test-Path -LiteralPath (Join-Path $ioRoot "agentscope") -PathType Container)) {
        $nested = Get-ChildItem -LiteralPath $extractRoot -Directory -Recurse -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName "agentscope") } |
            Select-Object -First 1
        if ($nested) {
            $ioRoot = $nested.FullName
        }
    }

    $agentScopeRoot = Join-Path $ioRoot "agentscope"
    if (-not (Test-Path -LiteralPath $agentScopeRoot -PathType Container)) {
        throw "Archive does not contain an io/agentscope Maven repository tree."
    }

    $targetRoot = Join-Path $MavenRepository "io\agentscope"
    New-Item -ItemType Directory -Path $targetRoot -Force | Out-Null
    Get-ChildItem -LiteralPath $agentScopeRoot -Force |
        Copy-Item -Destination $targetRoot -Recurse -Force

    $required = @(
        (Join-Path $targetRoot "agentscope-harness\$Revision\agentscope-harness-$Revision.jar"),
        (Join-Path $targetRoot "agentscope-extensions-rag-simple\$Revision\agentscope-extensions-rag-simple-$Revision.jar")
    )
    $missing = $required | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }
    if ($missing) {
        throw "AgentScope archive installed, but required artifacts are missing:`n$($missing -join "`n")"
    }

    Write-Host "[agentscope] Installed revision $Revision into: $MavenRepository"
    Write-Host "[agentscope] Required platform artifacts are available."
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
