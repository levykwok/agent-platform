param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$SessionToken = $env:AGENT_PLATFORM_E2E_SESSION_TOKEN,
    [string]$DecisionModel = ''
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($SessionToken)) {
    throw 'Provide -SessionToken or set AGENT_PLATFORM_E2E_SESSION_TOKEN.'
}

$base = $BaseUrl.TrimEnd('/')
$baseUri = [Uri]$base
$webSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
$webSession.Cookies.Add(
    [System.Net.Cookie]::new('platform_session', $SessionToken, '/', $baseUri.Host)
)

function Invoke-PlatformJson {
    param(
        [Parameter(Mandatory = $true)][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body
    )

    $parameters = @{
        Uri = "$base$Path"
        Method = $Method
        WebSession = $webSession
        UseBasicParsing = $true
        TimeoutSec = 60
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    $response = Invoke-WebRequest @parameters
    if ([string]::IsNullOrWhiteSpace($response.Content)) {
        return $null
    }
    return $response.Content | ConvertFrom-Json
}

function New-AgentSpec {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][hashtable]$Orchestration,
        [hashtable]$OutputSchema = @{}
    )

    $modelPolicy = @{}
    if (-not [string]::IsNullOrWhiteSpace($DecisionModel)) {
        $modelPolicy.orchestration = $DecisionModel.Trim()
    }
    $modelPolicy.runtime = @{
        root_timeout_ms = 240000
        root_max_agent_calls = 20
        root_max_tokens = 100000
        root_max_depth = 6
    }
    if ($OutputSchema.Count -gt 0) {
        $modelPolicy.output_schema = $OutputSchema
    }

    return @{
        config_json = @{
            name = $Name
            description = $Description
            domain = 'platform'
            enabled = $true
            # By default the suite follows the platform orchestration/QA slot. A caller can
            # provide -DecisionModel for a repeatable latency benchmark.
            model_policy = $modelPolicy
            skill_scope = @{ include = @() }
            mcp_scope = @{ include = @() }
            orchestration = $Orchestration
            prompt_policy = @{
                role = $Role
                planner_rules = @()
                require_structured_plan = $false
            }
            retrieval_policy = @{ domain = 'platform' }
        }
    }
}

$definitions = @(
    @{
        id = 'orchestration-e2e-single-analysis'
        name = 'Orchestration E2E - Single Analysis'
        description = 'Tool-free Single Agent for orchestration end-to-end validation.'
        spec = New-AgentSpec `
            -Name 'Orchestration E2E - Single Analysis' `
            -Description 'Tool-free Single Agent for orchestration end-to-end validation.' `
            -Role 'You are the analysis node in an orchestration acceptance test. Do not call tools. Return the required Agent business-result JSON with data.conclusion as one short sentence and summary beginning with E2E_ANALYSIS_OK.' `
            -Orchestration @{ mode = 'SINGLE'; subagents = @(); routes = @(); workflow = @() } `
            -OutputSchema @{ type = 'object'; required = @('conclusion'); properties = @{ conclusion = @{ type = 'string' } } }
    },
    @{
        id = 'orchestration-e2e-single-format'
        name = 'Orchestration E2E - Single Format'
        description = 'Tool-free Single Agent that formats upstream orchestration output.'
        spec = New-AgentSpec `
            -Name 'Orchestration E2E - Single Format' `
            -Description 'Tool-free Single Agent that formats upstream orchestration output.' `
            -Role 'You are the formatting node in an orchestration acceptance test. Do not call tools. Return the required Agent business-result JSON with data.formatted as one short sentence and summary beginning with E2E_FORMAT_OK.' `
            -Orchestration @{ mode = 'SINGLE'; subagents = @(); routes = @(); workflow = @() } `
            -OutputSchema @{ type = 'object'; required = @('formatted'); properties = @{ formatted = @{ type = 'string' } } }
    },
    @{
        id = 'orchestration-e2e-workflow'
        name = 'Orchestration E2E - Agent Pipeline'
        description = 'Two-step Agent-owned Pipeline; this is not a standalone Workflow canvas asset.'
        spec = New-AgentSpec `
            -Name 'Orchestration E2E - Agent Pipeline' `
            -Description 'Two-step Agent-owned Pipeline; this is not a standalone Workflow canvas asset.' `
            -Role 'Run the declared two-step Pipeline without calling tools.' `
            -Orchestration @{
                mode = 'PIPELINE'
                subagents = @()
                routes = @()
                workflow = @(
                    @{
                        stepId = 'analyze'
                        agentId = 'orchestration-e2e-single-analysis'
                        instruction = 'Analyze the input and produce the acceptance conclusion.'
                        maxRetries = 0
                        failurePolicy = 'FAIL_FAST'
                        transitions = @()
                    },
                    @{
                        stepId = 'format'
                        agentId = 'orchestration-e2e-single-format'
                        instruction = 'Format the previous result and preserve its acceptance marker.'
                        maxRetries = 0
                        failurePolicy = 'FAIL_FAST'
                        transitions = @()
                    }
                )
            }
    },
    @{
        id = 'orchestration-e2e-router'
        name = 'Orchestration E2E - Router'
        description = 'Router example that targets a Single Agent or Agent serial chain.'
        spec = New-AgentSpec `
            -Name 'Orchestration E2E - Router' `
            -Description 'Router example that targets a Single Agent or Agent serial chain.' `
            -Role 'Forward the request using only the declared routing rules.' `
            -Orchestration @{
                mode = 'ROUTER'
                subagents = @()
                workflow = @()
                routes = @(
                    @{
                        ruleId = 'serial-workflow'
                        targetAgentId = 'orchestration-e2e-workflow'
                        contains = 'serial validation'
                        keywords = @('workflow', 'serial')
                        defaultRoute = $false
                    },
                    @{
                        ruleId = 'single-analysis'
                        targetAgentId = 'orchestration-e2e-single-analysis'
                        contains = 'single analysis'
                        keywords = @('single', 'analysis')
                        defaultRoute = $false
                    },
                    @{
                        ruleId = 'default-format'
                        targetAgentId = 'orchestration-e2e-single-format'
                        contains = ''
                        keywords = @()
                        defaultRoute = $true
                    }
                )
            }
    },
    @{
        id = 'orchestration-e2e-supervisor'
        name = 'Orchestration E2E - Supervisor'
        description = 'Supervisor example that runs two new child Agents and summarizes them.'
        spec = New-AgentSpec `
            -Name 'Orchestration E2E - Supervisor' `
            -Description 'Supervisor example that runs two new child Agents and summarizes them.' `
            -Role 'You are the orchestration acceptance-test Supervisor. Do not call tools. After receiving both child results, output exactly one line: E2E_SUPERVISOR_OK | analysis=<whether E2E_ANALYSIS_OK is present> | format=<whether E2E_FORMAT_OK is present>.' `
            -Orchestration @{
                mode = 'SUPERVISOR'
                maxSupervisorSteps = 5
                supervisorParallelEnabled = $true
                maxSupervisorParallelism = 2
                routes = @()
                workflow = @()
                subagents = @(
                    @{
                        bindingId = 'analysis-child'
                        targetAgentId = 'orchestration-e2e-single-analysis'
                        role = 'analysis specialist'
                        description = 'Produce the first independent acceptance result'
                        exposeToUser = $true
                        toolRefs = @()
                    },
                    @{
                        bindingId = 'format-child'
                        targetAgentId = 'orchestration-e2e-single-format'
                        role = 'formatting specialist'
                        description = 'Produce the second independent acceptance result'
                        exposeToUser = $true
                        toolRefs = @()
                    }
                )
            }
    }
)

$catalog = Invoke-PlatformJson -Method GET -Path '/platform/frontend/agents'
$existing = @{}
foreach ($agent in @($catalog.agents)) {
    $existing[[string]$agent.agent_id] = $true
}

$saved = @()
foreach ($definition in $definitions) {
    $agentId = [string]$definition.id
    if (-not $existing.ContainsKey($agentId)) {
        Invoke-PlatformJson -Method POST -Path '/platform/frontend/agents' -Body @{
            agent_id = $agentId
            name = $definition.name
            display_name = $definition.name
            description = $definition.description
            domain = 'platform'
            source = 'e2e-example'
            visibility = 'PUBLIC'
            enabled = $true
        } | Out-Null
    }
    $encoded = [Uri]::EscapeDataString($agentId)
    $result = Invoke-PlatformJson -Method PUT -Path "/platform/frontend/agents/$encoded/spec" -Body $definition.spec
    $saved += $result.agent
}

[pscustomobject]@{
    ok = $true
    count = $saved.Count
    agent_ids = @($definitions | ForEach-Object { $_.id })
} | ConvertTo-Json -Depth 5
