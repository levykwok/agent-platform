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
        @{ label = 'pipeline'; agent = 'orchestration-e2e-workflow'; query = 'Run the parallel fan-out and join Pipeline orchestration acceptance test.' },
        @{ label = 'router'; agent = 'orchestration-e2e-router'; query = 'First inspect the acceptance request carefully, then transform that analysis into the required concise output.' },
        @{ label = 'supervisor'; agent = 'orchestration-e2e-supervisor'; query = 'Run the combined orchestration acceptance test and summarize every declared child result.' }
    )

    $startedAt = [DateTimeOffset]::UtcNow
    $results = @()
    foreach ($case in $cases) {
        Write-Host "Running $($case.label): $($case.agent)" -ForegroundColor Cyan
        $results += Invoke-StreamingRun -Label $case.label -AgentId $case.agent -Query $case.query
        Write-Host "Completed $($case.label) in $($results[-1].total_ms) ms" -ForegroundColor Green
    }

    $requiredLlmDecisions = @{
        router = 'router_decision'
        supervisor = 'supervisor_plan'
    }
    foreach ($label in $requiredLlmDecisions.Keys) {
        $result = @($results | Where-Object { $_.label -eq $label } | Select-Object -First 1)
        $decision = @($result.persisted_events | Where-Object { $_.event_type -eq $requiredLlmDecisions[$label] } | Select-Object -Last 1)
        if ($decision.Count -eq 0 -or [string]$decision[0].payload.decision_source -ne 'llm') {
            throw "$label did not persist a successful LLM orchestration decision."
        }
    }
    foreach ($result in $results) {
        if ([string]::IsNullOrWhiteSpace([string]$result.persisted_run.agent_version) `
            -or $null -eq $result.persisted_run.config_snapshot) {
            throw "$($result.label) did not persist the Agent version/config snapshot."
        }
        $budgetEvent = @($result.persisted_events | Where-Object { $_.event_type -eq 'orchestration_budget' } | Select-Object -Last 1)
        if ($budgetEvent.Count -eq 0 -or $null -eq $budgetEvent[0].payload.budget.agent_calls) {
            throw "$($result.label) did not persist root orchestration budget usage."
        }
    }
    $pipelineResult = @($results | Where-Object { $_.label -eq 'pipeline' } | Select-Object -First 1)
    $agentPipelineEvent = @($pipelineResult.persisted_events | Where-Object { $_.payload.agent_pipeline -eq $true } | Select-Object -First 1)
    if ($agentPipelineEvent.Count -eq 0) {
        throw 'Agent PIPELINE events were not marked as the Agent-owned pipeline.'
    }
    $pipelineSnapshot = $pipelineResult.persisted_run.config_snapshot.orchestration
    $pipelineProperties = @($pipelineSnapshot.PSObject.Properties.Name)
    if ([string]$pipelineSnapshot.mode -ne 'PIPELINE' `
        -or $pipelineProperties -notcontains 'pipeline' `
        -or $pipelineProperties -contains 'workflow') {
        throw 'Agent PIPELINE run snapshot did not use the canonical orchestration.pipeline schema.'
    }
    $parallelStarts = @($pipelineResult.persisted_events | Where-Object { $_.event_type -eq 'pipeline_parallel_start' })
    $parallelEnds = @($pipelineResult.persisted_events | Where-Object { $_.event_type -eq 'pipeline_parallel_end' })
    $parallelStepEnds = @($pipelineResult.persisted_events | Where-Object { $_.event_type -eq 'pipeline_step_end' -and $_.payload.parallel_group -eq 'acceptance-fanout' })
    if ($parallelStarts.Count -ne 1 -or $parallelEnds.Count -ne 1 -or $parallelStepEnds.Count -ne 2) {
        throw 'Agent PIPELINE did not persist one complete two-step parallel fan-out group.'
    }
    if ([string]$parallelStarts[0].payload.max_parallelism -ne '2' `
        -or [string]$pipelineSnapshot.maxPipelineParallelism -ne '2' `
        -or [string]$pipelineResult.answer -notmatch 'E2E_PIPELINE_PARALLEL_OK') {
        throw 'Agent PIPELINE parallel policy, joined output, or acceptance marker was not preserved.'
    }
    $supervisorResult = @($results | Where-Object { $_.label -eq 'supervisor' } | Select-Object -First 1)
    $supervisorSteps = @($supervisorResult.persisted_events | Where-Object { $_.event_type -eq 'supervisor_step_start' })
    $supervisorRevisions = @($supervisorResult.persisted_events | Where-Object { $_.event_type -eq 'supervisor_revise' })
    if ($supervisorSteps.Count -lt 2 -or $supervisorRevisions.Count -lt 1) {
        throw 'Supervisor did not execute and revise a multi-step LLM plan.'
    }
    $supervisorPlan = @($supervisorResult.persisted_events | Where-Object { $_.event_type -eq 'supervisor_plan' } | Select-Object -Last 1)
    if ($supervisorPlan.Count -eq 0 -or $supervisorPlan[0].payload.parallel_enabled -ne $true) {
        throw 'Supervisor demo did not persist its optional parallel-group policy.'
    }
    $businessResults = @($supervisorResult.persisted_events | Where-Object { $_.event_type -eq 'supervisor_subagent_result' })
    if (@($businessResults | Where-Object { $null -eq $_.payload.business_result.status -or $null -eq $_.payload.business_result.data }).Count -gt 0) {
        throw 'Supervisor child events did not persist the unified business-result contract.'
    }
    $metrics = Get-Json -Path '/platform/frontend/agents/orchestration/metrics'
    if ($null -eq $metrics.metrics.supervisor_fallbacks `
        -or $null -eq $metrics.metrics.run_p95_ms `
        -or $null -eq $metrics.metrics.pipeline_runs `
        -or $null -eq $metrics.metrics.pipeline_p95_ms) {
        throw 'Orchestration quality metrics endpoint did not return the required aggregates.'
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
