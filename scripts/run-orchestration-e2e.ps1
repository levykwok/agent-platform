param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$SessionToken = $env:AGENT_PLATFORM_E2E_SESSION_TOKEN,
    [string]$OutputDirectory = 'output/orchestration-e2e'
)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Net.Http

if ([string]::IsNullOrWhiteSpace($SessionToken)) {
    throw 'Provide -SessionToken or set AGENT_PLATFORM_E2E_SESSION_TOKEN.'
}

$base = $BaseUrl.TrimEnd('/')
$resolvedOutput = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDirectory))
[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null

$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.UseCookies = $true
$handler.CookieContainer = [System.Net.CookieContainer]::new()
$handler.CookieContainer.Add(
    [Uri]$base,
    [System.Net.Cookie]::new('platform_session', $SessionToken, '/')
)
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromMinutes(8)

function Get-Json {
    param([Parameter(Mandatory = $true)][string]$Path)
    $response = $client.GetAsync("$base$Path").GetAwaiter().GetResult()
    $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "GET $Path failed with $([int]$response.StatusCode): $content"
    }
    return $content | ConvertFrom-Json
}

function Add-TimelineEvent {
    param(
        [System.Collections.Generic.List[object]]$Timeline,
        [System.Diagnostics.Stopwatch]$Stopwatch,
        [string]$EventName,
        [object]$Data
    )

    $step = if ($null -ne $Data.step) { [string]$Data.step } else { $EventName }
    $summary = if ($null -ne $Data.summary) { [string]$Data.summary } elseif ($null -ne $Data.delta) { 'First text delta' } else { '' }
    $source = if ($null -ne $Data.source) { [string]$Data.source } else { '' }
    $Timeline.Add([pscustomobject]@{
        relative_ms = [long]$Stopwatch.ElapsedMilliseconds
        event = $EventName
        step = $step
        source = $source
        summary = $summary
    })
}

function Invoke-StreamingRun {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$AgentId,
        [Parameter(Mandatory = $true)][string]$Query
    )

    $payload = @{
        agent_id = $AgentId
        session_id = "orchestration-e2e-$Label-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        input_type = 'chat'
        payload = @{ query = $Query }
        context = @{}
        artifacts = @()
    } | ConvertTo-Json -Depth 10 -Compress

    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        "$base/agent-runs/run/stream"
    )
    $request.Content = [System.Net.Http.StringContent]::new(
        $payload,
        [System.Text.Encoding]::UTF8,
        'application/json'
    )
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $response = $client.SendAsync(
        $request,
        [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
    ).GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        $errorBody = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        throw "Streaming run for $AgentId failed with $([int]$response.StatusCode): $errorBody"
    }

    $timeline = [System.Collections.Generic.List[object]]::new()
    $reader = [System.IO.StreamReader]::new(
        $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    )
    $eventName = 'message'
    $dataLines = [System.Collections.Generic.List[string]]::new()
    $firstTokenSeen = $false
    $runId = ''
    $status = ''
    $answer = ''

    while (-not $reader.EndOfStream) {
        $line = $reader.ReadLineAsync().GetAwaiter().GetResult()
        if ($line.StartsWith('event:')) {
            $eventName = $line.Substring(6).Trim()
            continue
        }
        if ($line.StartsWith('data:')) {
            $dataLines.Add($line.Substring(5).TrimStart())
            continue
        }
        if (-not [string]::IsNullOrEmpty($line) -or $dataLines.Count -eq 0) {
            continue
        }

        $dataText = $dataLines -join "`n"
        $dataLines.Clear()
        $data = $dataText | ConvertFrom-Json
        if ($null -ne $data.run_id -and [string]::IsNullOrWhiteSpace($runId)) {
            $runId = [string]$data.run_id
        }
        if ($eventName -eq 'activity') {
            Add-TimelineEvent -Timeline $timeline -Stopwatch $stopwatch -EventName $eventName -Data $data
        } elseif ($eventName -eq 'token' -and -not $firstTokenSeen) {
            $firstTokenSeen = $true
            Add-TimelineEvent -Timeline $timeline -Stopwatch $stopwatch -EventName 'first_token' -Data $data
        } elseif ($eventName -in @('done', 'error')) {
            Add-TimelineEvent -Timeline $timeline -Stopwatch $stopwatch -EventName $eventName -Data $data
        }
        if ($eventName -eq 'done') {
            $status = [string]$data.status
            if ($null -ne $data.result.answer) {
                $answer = [string]$data.result.answer
            }
        }
        if ($eventName -eq 'error') {
            $status = 'failed'
            $answer = [string]$data.message
        }
        $eventName = 'message'
    }

    $stopwatch.Stop()
    $reader.Dispose()
    $request.Dispose()
    $response.Dispose()

    if ([string]::IsNullOrWhiteSpace($runId)) {
        throw "Run $Label did not return a run_id."
    }
    if ($status -ne 'succeeded') {
        throw "Run $Label ($runId) finished with status '$status': $answer"
    }

    $encodedRunId = [Uri]::EscapeDataString($runId)
    $persistedRun = Get-Json -Path "/platform/frontend/agents/runs/$encodedRunId"
    $persistedSteps = Get-Json -Path "/platform/frontend/agents/runs/$encodedRunId/steps"
    $persistedEvents = Get-Json -Path "/platform/frontend/agents/runs/$encodedRunId/events"

    $previous = 0L
    $timelineWithGaps = @(
        foreach ($item in $timeline) {
            $gap = [long]$item.relative_ms - $previous
            $previous = [long]$item.relative_ms
            [pscustomobject]@{
                relative_ms = [long]$item.relative_ms
                gap_ms = $gap
                event = $item.event
                step = $item.step
                source = $item.source
                summary = $item.summary
            }
        }
    )

    return [pscustomobject]@{
        label = $Label
        agent_id = $AgentId
        query = $Query
        run_id = $runId
        status = $status
        total_ms = [long]$stopwatch.ElapsedMilliseconds
        first_token_ms = @($timelineWithGaps | Where-Object { $_.event -eq 'first_token' } | Select-Object -First 1).relative_ms
        answer = $answer
        timeline = $timelineWithGaps
        persisted_run = $persistedRun.run
        persisted_steps = @($persistedSteps.steps)
        persisted_events = @($persistedEvents.events)
    }
}

try {
    $cases = @(
        @{ label = 'single'; agent = 'orchestration-e2e-single-analysis'; query = 'Run the single-agent orchestration acceptance test.' },
        @{ label = 'workflow'; agent = 'orchestration-e2e-workflow'; query = 'Run the two-step workflow orchestration acceptance test.' },
        @{ label = 'router'; agent = 'orchestration-e2e-router'; query = 'Run serial validation and confirm the Router entered the two-step workflow.' },
        @{ label = 'supervisor'; agent = 'orchestration-e2e-supervisor'; query = 'Run the combined orchestration acceptance test and summarize every declared child result.' }
    )

    $startedAt = [DateTimeOffset]::UtcNow
    $results = @()
    foreach ($case in $cases) {
        Write-Host "Running $($case.label): $($case.agent)" -ForegroundColor Cyan
        $results += Invoke-StreamingRun -Label $case.label -AgentId $case.agent -Query $case.query
        Write-Host "Completed $($case.label) in $($results[-1].total_ms) ms" -ForegroundColor Green
    }
    $finishedAt = [DateTimeOffset]::UtcNow

    $report = [pscustomobject]@{
        started_at = $startedAt.ToString('o')
        finished_at = $finishedAt.ToString('o')
        base_url = $base
        all_succeeded = @($results | Where-Object { $_.status -ne 'succeeded' }).Count -eq 0
        results = $results
    }
    $jsonPath = Join-Path $resolvedOutput 'report.json'
    [System.IO.File]::WriteAllText(
        $jsonPath,
        ($report | ConvertTo-Json -Depth 30),
        [System.Text.UTF8Encoding]::new($false)
    )

    $markdown = [System.Text.StringBuilder]::new()
    [void]$markdown.AppendLine('# Orchestration E2E runtime report')
    [void]$markdown.AppendLine()
    [void]$markdown.AppendLine("- Started: $($report.started_at)")
    [void]$markdown.AppendLine("- Finished: $($report.finished_at)")
    [void]$markdown.AppendLine("- Result: $(if ($report.all_succeeded) { 'PASS' } else { 'FAIL' })")
    [void]$markdown.AppendLine()
    [void]$markdown.AppendLine('| Mode | Agent | Run | Total | First token | Status |')
    [void]$markdown.AppendLine('|---|---|---|---:|---:|---|')
    foreach ($result in $results) {
        [void]$markdown.AppendLine("| $($result.label.ToUpperInvariant()) | ``$($result.agent_id)`` | ``$($result.run_id)`` | $($result.total_ms) ms | $($result.first_token_ms) ms | $($result.status) |")
    }
    foreach ($result in $results) {
        [void]$markdown.AppendLine()
        [void]$markdown.AppendLine("## $($result.label.ToUpperInvariant())")
        [void]$markdown.AppendLine()
        [void]$markdown.AppendLine("Final answer: $($result.answer.Replace("`r", ' ').Replace("`n", ' '))")
        [void]$markdown.AppendLine()
        [void]$markdown.AppendLine('| At | Gap | Step | Source | Summary |')
        [void]$markdown.AppendLine('|---:|---:|---|---|---|')
        foreach ($event in $result.timeline) {
            $summary = ([string]$event.summary).Replace('|', '\|').Replace("`r", ' ').Replace("`n", ' ')
            [void]$markdown.AppendLine("| $($event.relative_ms) ms | $($event.gap_ms) ms | ``$($event.step)`` | ``$($event.source)`` | $summary |")
        }
    }
    $markdownPath = Join-Path $resolvedOutput 'report.md'
    [System.IO.File]::WriteAllText(
        $markdownPath,
        $markdown.ToString(),
        [System.Text.UTF8Encoding]::new($false)
    )

    [pscustomobject]@{
        ok = $report.all_succeeded
        report_json = $jsonPath
        report_markdown = $markdownPath
        runs = @($results | ForEach-Object { @{ mode = $_.label; run_id = $_.run_id; total_ms = $_.total_ms } })
    } | ConvertTo-Json -Depth 6
} finally {
    $client.Dispose()
    $handler.Dispose()
}
