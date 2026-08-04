param(
    [int]$BackendPort = 8080,
    [int]$FrontendPort = 5173,
    [int]$DemoMcpHttpPort = 8765,
    [int]$DemoMcpSsePort = 8766
)

$startScript = Join-Path $PSScriptRoot "start-platform.ps1"
& powershell.exe `
    -NoProfile `
    -ExecutionPolicy Bypass `
    -File $startScript `
    -Stop `
    -BackendPort $BackendPort `
    -FrontendPort $FrontendPort `
    -DemoMcpHttpPort $DemoMcpHttpPort `
    -DemoMcpSsePort $DemoMcpSsePort

exit $LASTEXITCODE
