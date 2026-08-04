<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { currentDomain, currentOrgId, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'

const tools = ref<JsonMap[]>([])
const audit = ref<JsonMap[]>([])
const auditOpen = ref(false)
const domain = ref(currentDomain(''))
const domainOptions = ref<JsonMap[]>([{ domain: 'platform', label: '平台' }])
const activeCategory = ref('')
const activeToolTab = ref('all')
const output = ref<JsonMap | null>(null)
const error = ref('')
const createKind = ref<'http' | 'db-query' | 'python'>('python')
const createOpen = ref(false)
const editingToolId = ref('')
function resetForm() {
  form.tool_id = ''
  form.tool_name = ''
  form.display_name = ''
  form.description = ''
  form.url = ''
  form.method = 'POST'
  form.query_template = 'select 1'
  form.max_rows = 50
  form.runner_url = ''
  form.script = defaultPythonScript()
  form.parameter_schema = defaultParameterSchema()
  form.test_args = `{
  "text": "hello"
}`
  form.timeout_ms = 5000
}
function openCreate() {
  editingToolId.value = ''
  createKind.value = 'python'
  resetForm()
  createOpen.value = true
}
function defaultPythonScript() {
  return `import json
import sys

def run(args, context):
    text = args.get("text", "")
    return {"ok": True, "result": {"echo": text, "tool_id": context.get("tool_id")}}

payload = json.loads(sys.stdin.readline() or "{}")
print(json.dumps(run(payload.get("args", {}), payload.get("context", {})), ensure_ascii=False))`
}
function defaultParameterSchema() {
  return `{
  "type": "object",
  "properties": {
    "text": {
      "type": "string",
      "description": "输入文本"
    }
  },
  "required": ["text"]
}`
}
const form = reactive({
  tool_id: '',
  tool_name: '',
  display_name: '',
  description: '',
  url: '',
  method: 'POST',
  query_template: 'select 1',
  max_rows: 50,
  runner_url: '',
  script: defaultPythonScript(),
  parameter_schema: defaultParameterSchema(),
  test_args: `{
  "text": "hello"
}`,
  timeout_ms: 5000,
})
const riskChecked = ref<Record<string, boolean>>({})

function labelDomain(d: string) {
  if (d === 'platform') return '平台'
  if (d === 'global') return '全局'
  return d
}
function labelCategory(c: string) {
  return ({ flow: '流程工具', standard: '标准工具', package: '平台工具包', domain: '业务域工具' } as Record<string, string>)[c] || c
}
function isRisky(t: JsonMap): boolean {
  if (String(t.source_type || '').toLowerCase() === 'mcp') return false
  const risk = String(t.risk_level || '').toLowerCase()
  const effect = String(t.side_effect || '').toLowerCase()
  return ['high', 'critical'].includes(risk) || ['write', 'code_exec'].includes(effect)
}
function isRiskApproved(t: JsonMap): boolean {
  const override = (t.config_override as JsonMap) || {}
  return Boolean(override.risk_approved || (override.risk_approval as JsonMap)?.approved)
}
function toolOrigin(t: JsonMap): string {
  const sourceType = String(t.source_type || '').toLowerCase()
  const toolId = String(t.tool_id || t.name || '')
  const explicitSource = String(t.source_label || '')
  if (sourceType === 'mcp' || explicitSource.toLowerCase().startsWith('mcp:')) {
    const runtime = String(t.runtime_name || '')
    const m = toolId.match(/^mcp:([^:]+):/)
    if (m) return `MCP.${m[1]}`
    if (runtime) return `MCP.${runtime}`
    return 'MCP'
  }
  if (explicitSource) return explicitSource
  if (sourceType === 'java') return '平台内置工具'
  if (sourceType === 'python') return '自定义脚本工具'
  if (sourceType === 'http' || sourceType === 'remote') return '外部接口工具'
  if (sourceType === 'db-query' || sourceType === 'db_query') return '数据库查询工具'
  return sourceType || 'runtime'
}
function runtimeLabel(t: JsonMap): string {
  const sourceType = String(t.source_type || '').toLowerCase()
  if (sourceType === 'java') return 'Java / 运行时工具'
  if (sourceType === 'python') return 'Python 脚本工具'
  if (sourceType === 'http' || sourceType === 'remote') return 'HTTP 工具'
  if (sourceType === 'db-query' || sourceType === 'db_query') return '数据库查询工具'
  return sourceType || '平台 / 业务工具'
}
function toolTabKey(t: JsonMap): string {
  const sourceType = String(t.source_type || '').toLowerCase()
  const serverId = mcpServerId(t)
  if (sourceType === 'mcp' || serverId) return 'mcp'
  if (sourceType === 'java') return 'builtin'
  if (sourceType === 'python') return 'script'
  if (sourceType === 'http' || sourceType === 'remote') return 'external'
  if (sourceType === 'db-query' || sourceType === 'db_query') return 'database'
  return 'other'
}
function tabLabel(key: string): string {
  return ({
    all: '全部',
    builtin: '平台内置',
    script: '自定义脚本',
    external: '外部接口',
    database: '数据库查询',
    mcp: 'MCP 工具',
    other: '其他',
  } as Record<string, string>)[key] || key
}
function mcpServerId(t: JsonMap): string {
  const toolId = String(t.tool_id || t.name || '')
  const match = toolId.match(/^mcp:([^:]+):/)
  return String(t.mcp_server_id || t.server_id || t.runtime_name || match?.[1] || '')
}
function shortToolName(t: JsonMap): string {
  const toolId = String(t.tool_id || t.name || '')
  const match = toolId.match(/^mcp:[^:]+:(.+)$/)
  return String(t.tool_name || t.name || match?.[1] || t.display_name || toolId)
}
function transportLabel(t: JsonMap): string {
  return String(t.transport || t.transport_type || t.mcp_transport || '').toLowerCase()
}
function toolTypeLabel(t: JsonMap): string {
  const sourceType = String(t.source_type || '').toLowerCase()
  if (sourceType === 'python') return 'Python'
  if (sourceType === 'mcp' || !!mcpServerId(t)) return 'MCP'
  if (sourceType === 'http' || sourceType === 'remote') return 'HTTP'
  if (sourceType === 'db-query' || sourceType === 'db_query') return 'DB'
  return '平台'
}
function toolVisibleLabel(t: JsonMap): string {
  return String(t.binding_visibility || 'discoverable') === 'hidden' ? '隐藏' : '可见'
}
function toolEnabledLabel(t: JsonMap): string {
  return String(t.binding_status || 'enabled') === 'disabled' ? '已停用' : '已启用'
}
function parameterCount(t: JsonMap): number {
  if (Array.isArray(t.parameter_names)) return (t.parameter_names as unknown[]).length
  const schema = (t.parameter_schema as JsonMap) || {}
  const props = schema?.properties
  return props && typeof props === 'object' ? Object.keys(props).length : 0
}

const visible = computed(() => {
  return tools.value.filter((t) => {
    if (activeToolTab.value !== 'all' && toolTabKey(t) !== activeToolTab.value) return false
    if (activeCategory.value && String(t.category || '') !== activeCategory.value) return false
    return true
  })
})
const tabCounts = computed(() => {
  const counts = new Map<string, number>([['all', tools.value.length]])
  for (const t of tools.value) {
    const key = toolTabKey(t)
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  return ['all', 'builtin', 'script', 'external', 'database', 'mcp', 'other']
    .map((key) => [key, counts.get(key) || 0] as [string, number])
    .filter(([key, count]) => key === 'all' || count > 0)
})
const visibleGroups = computed(() => {
  const groups = new Map<string, { key: string; title: string; subtitle: string; transport: string; items: JsonMap[] }>()
  for (const t of visible.value) {
    const sourceType = String(t.source_type || '').toLowerCase()
    const serverId = mcpServerId(t)
    const key = sourceType === 'mcp' || serverId ? `mcp:${serverId || 'unknown'}` : `source:${toolOrigin(t)}`
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        title: serverId ? `MCP 服务: ${serverId}` : toolOrigin(t),
        subtitle: serverId ? `${transportLabel(t) || 'mcp'} 传输` : runtimeLabel(t),
        transport: transportLabel(t),
        items: [],
      })
    }
    groups.get(key)!.items.push(t)
  }
  return Array.from(groups.values()).sort((a, b) => a.title.localeCompare(b.title))
})
const domainCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const t of tools.value) {
    const d = String(t.domain || 'platform')
    counts.set(d, (counts.get(d) || 0) + 1)
  }
  return Array.from(counts.entries()).sort(([a], [b]) => (a === 'platform' ? -1 : b === 'platform' ? 1 : a.localeCompare(b)))
})
const categoryCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const t of tools.value) {
    const c = String(t.category || 'unknown')
    counts.set(c, (counts.get(c) || 0) + 1)
  }
  return Array.from(counts.entries()).sort(([a], [b]) => a.localeCompare(b))
})
const errorCount = computed(() => tools.value.filter((t) => t.load_error).length)

function headers(json = false) {
  return makeHeaders(json, currentOrgId())
}
async function loadTools() {
  const qs = domain.value ? `?domain=${encodeURIComponent(domain.value)}` : ''
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/tools${qs}`, { headers: headers(false) }))
  const list = (data.items || data.tools || []) as JsonMap[]
  const nextChecked = {} as Record<string, boolean>
  for (const t of list) {
    const key = String(t.tool_id || t.name)
    if (key) nextChecked[key] = isRiskApproved(t) || false
  }
  riskChecked.value = nextChecked
  tools.value = list
}
async function loadDomains() {
  const status = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: headers(false) }))
  const raw = status.domains && typeof status.domains === 'object' ? status.domains as JsonMap : {}
  domainOptions.value = [
    { domain: 'platform', label: '平台' },
    ...Object.entries(raw)
      .map(([d, s]) => ({ domain: d, label: String((s as JsonMap)?.display_name || d) }))
      .filter((row) => row.domain !== 'platform'),
  ]
  if (!domainOptions.value.some((row) => row.domain === domain.value)) {
    domain.value = 'platform'
  }
}

async function setBinding(t: JsonMap, status: string, visibility?: string) {
  const key = String(t.tool_id || t.name)
  const nextStatus = status
  const nextVisibility = visibility || t.binding_visibility || 'discoverable'
  try {
    const body: JsonMap = {
      domain: t.domain && t.domain !== 'platform' ? t.domain : null,
      binding_status: nextStatus,
      binding_visibility: nextVisibility,
    }
    if (isRisky(t)) {
      const override = (t.config_override as JsonMap) || {}
      const riskApproved = riskChecked.value[key] ?? isRiskApproved(t)
      const riskApproval = (override.risk_approval as JsonMap) || {}
      body.config_override = {
        ...override,
        risk_approved: riskApproved,
        risk_approval: {
          ...riskApproval,
          approved: riskApproved,
        },
      }
    }
    await readJson(await fetch(`/platform/frontend/tools/bindings/${encodeURIComponent(key)}`, { method: 'PUT', headers: headers(true), body: JSON.stringify(body) }))
    await loadTools()
    if (visibility && visibility !== t.binding_visibility) {
      notifySuccess(`${key} 可见性已更新`)
    } else if (nextStatus !== (t.binding_status || 'enabled')) {
      notifySuccess(`${key} 已${nextStatus === 'disabled' ? '停用' : '启用'}`)
    } else {
      notifySuccess(`${key} 配置已更新`)
    }
  } catch (err) {
    notifyError(err)
  }
}
async function saveRiskApproval(t: JsonMap) {
  const key = String(t.tool_id || t.name)
  if (!key) return
  if (!isRisky(t)) return
  await setBinding(t, String(t.binding_status || 'enabled'), String(t.binding_visibility || 'discoverable'))
}
async function savePolicy(t: JsonMap) {
  try {
    const body: JsonMap = { domain: t.domain && t.domain !== 'platform' ? t.domain : null, action: 'allow' }
    await readJson(
      await fetch(
        `/platform/frontend/tools/agents/${encodeURIComponent('platform_knowledge_agent')}/policies/${encodeURIComponent(String(t.tool_id || t.name))}`,
        { method: 'PUT', headers: headers(true), body: JSON.stringify(body) }
      )
    )
    notifySuccess(`已为 platform_knowledge_agent 保存 ${t.tool_id || t.name} 的放行策略`)
  } catch (err) {
    notifyError(err)
  }
}
async function createTool() {
  const tool_id = form.tool_id.trim()
  if (!tool_id) {
    notifyError('请填写工具 ID')
    return
  }
  try {
    const base: JsonMap = {
      tool_id,
      tool_name: form.tool_name.trim() || tool_id,
      display_name: form.display_name || form.tool_name || tool_id,
      description: form.description,
      domain: domain.value && domain.value !== 'platform' ? domain.value : null,
    }
    let body: JsonMap
    let url: string
    if (createKind.value === 'http') {
      if (!form.url.trim()) { notifyError('请填写 URL'); return }
      body = { ...base, url: form.url.trim(), method: form.method, timeout_ms: form.timeout_ms }
      url = '/platform/frontend/tools/http'
    } else if (createKind.value === 'db-query') {
      if (!form.query_template.trim()) { notifyError('请填写 SQL 模板'); return }
      body = { ...base, query_template: form.query_template.trim(), max_rows: form.max_rows, timeout_ms: form.timeout_ms, parameter_schema: { type: 'object', properties: {} } }
      url = '/platform/frontend/tools/db-query'
    } else {
      if (!form.script.trim()) { notifyError('请填写 Python 脚本'); return }
      let parameter_schema: JsonMap
      try {
        parameter_schema = JSON.parse(form.parameter_schema || '{}')
      } catch {
      notifyError('参数模式不是合法 JSON')
        return
      }
      body = { ...base, script: form.script, timeout_ms: form.timeout_ms, parameter_schema }
      url = '/platform/frontend/tools/python'
    }
    output.value = await readJson(await fetch(url, { method: 'POST', headers: headers(true), body: JSON.stringify(body) }))
    resetForm()
    createOpen.value = false
    await loadTools()
    notifySuccess(`工具 ${tool_id} 已创建`)
  } catch (err) {
    notifyError(err)
  }
}
async function openEditTool(t: JsonMap) {
  const toolId = String(t.tool_id || t.name)
  if (String(t.source_type || '').toLowerCase() !== 'python') return
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/tools/python/${encodeURIComponent(toolId)}`, { headers: headers(false) }))
    editingToolId.value = toolId
    createKind.value = 'python'
    form.tool_id = toolId
    form.tool_name = toolId
    form.display_name = String(t.display_name || toolId)
    form.description = String(data.description || t.description || '')
    form.script = String(data.script || '')
    form.parameter_schema = JSON.stringify(data.parameter_schema || {}, null, 2)
    form.test_args = form.test_args || '{}'
    form.timeout_ms = Number(data.timeout_ms || t.timeout_ms || 5000)
    createOpen.value = true
  } catch (err) {
    notifyError(err)
  }
}
async function saveTool() {
  if (editingToolId.value) {
    await updatePythonTool()
  } else {
    await createTool()
  }
}
async function updatePythonTool() {
  const toolId = editingToolId.value
  const parameter_schema = parseJsonInput(form.parameter_schema, '参数模式')
  if (!parameter_schema) return
  if (!form.script.trim()) {
    notifyError('请填写 Python 脚本')
    return
  }
  try {
    output.value = await readJson(
      await fetch(`/platform/frontend/tools/python/${encodeURIComponent(toolId)}`, {
        method: 'PUT',
        headers: headers(true),
        body: JSON.stringify({
          description: form.description,
          script: form.script,
          parameter_schema,
          timeout_ms: form.timeout_ms,
        }),
      })
    )
    createOpen.value = false
    editingToolId.value = ''
    await loadTools()
    notifySuccess(`工具 ${toolId} 已更新`)
  } catch (err) {
    notifyError(err)
  }
}
function parseJsonInput(text: string, label: string): JsonMap | null {
  try {
    return JSON.parse(text || '{}')
  } catch {
    notifyError(`${label} 不是合法 JSON`)
    return null
  }
}
async function validatePythonTool(runOnly = false) {
  if (!form.script.trim()) {
    notifyError('请填写 Python 脚本')
    return
  }
  const parameter_schema = parseJsonInput(form.parameter_schema, '参数模式')
  if (!parameter_schema) return
  const args = parseJsonInput(form.test_args, '测试参数')
  if (!args) return
  try {
    output.value = await readJson(
      await fetch('/platform/frontend/tools/python/validate', {
        method: 'POST',
        headers: headers(true),
        body: JSON.stringify({
          tool_id: form.tool_id.trim() || 'draft_python_tool',
          script: form.script,
          parameter_schema,
          arguments: args,
          timeout_ms: form.timeout_ms,
        }),
      })
    )
    if (output.value?.ok) notifySuccess(runOnly ? '测试运行通过' : '检查通过')
    else notifyError(output.value?.error || '检查失败，查看下方结果')
  } catch (err) {
    notifyError(err)
  }
}
async function testSavedTool(t: JsonMap) {
  const toolId = String(t.tool_id || t.name)
  const raw = window.prompt(`测试工具 ${toolId}，输入 JSON 参数`, '{}')
  if (raw === null) return
  const args = parseJsonInput(raw, '测试参数')
  if (!args) return
  try {
    output.value = await readJson(
      await fetch(`/platform/frontend/tools/${encodeURIComponent(toolId)}/test`, {
        method: 'POST',
        headers: headers(true),
        body: JSON.stringify({ arguments: args }),
      })
    )
    if (output.value?.ok) notifySuccess(`${toolId} 测试通过`)
    else notifyError(output.value?.error || `${toolId} 测试失败`)
  } catch (err) {
    notifyError(err)
  }
}
async function loadAudit() {
  auditOpen.value = !auditOpen.value
  if (!auditOpen.value) return
  try {
    const data = await readJson<JsonMap>(await fetch('/platform/frontend/tools/audit?limit=50', { headers: headers(false) }))
    audit.value = data.items || []
  } catch (err) {
    notifyError(err)
  }
}
function outputHeadline(d: JsonMap) {
  return d.message || d.detail || (d.tool_id ? `工具已创建: ${d.tool_id}` : '操作完成，详见下方响应。')
}
onMounted(async () => {
  try {
    await loadDomains()
    await loadTools()
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
})
</script>

<template>
  <section class="content">
    <p v-if="error" class="error-line">{{ error }}</p>
    <div class="stats">
      <div class="stat"><div class="stat-label">工具</div><div class="stat-val">{{ tools.length }}</div></div>
      <div class="stat"><div class="stat-label">业务域</div><div class="stat-val">{{ domainCounts.length }}</div></div>
      <div class="stat"><div class="stat-label">分类</div><div class="stat-val">{{ categoryCounts.length }}</div></div>
      <div class="stat"><div class="stat-label">加载异常</div><div class="stat-val" :class="errorCount ? 'err' : ''">{{ errorCount }}</div></div>
    </div>
    <section class="panel">
      <div class="section-head"><div><div class="section-title">工具目录</div><div class="section-sub">查看、启停绑定。</div></div><div class="actions"><button class="btn btn-primary btn-sm" @click="openCreate">+ 创建工具</button><button class="btn btn-ghost btn-sm" @click="loadAudit">{{ auditOpen ? '隐藏审计' : '审计' }}</button><button class="btn btn-ghost btn-sm" @click="loadTools">刷新</button></div></div>
      <div class="tools-toolbar">
        <div class="tool-tabs">
          <button v-for="[key, count] in tabCounts" :key="key" class="tool-tab" :class="{ active: activeToolTab === key }" @click="activeToolTab = key">
            {{ tabLabel(key) }} <span>{{ count }}</span>
          </button>
        </div>
      </div>
      <div v-if="auditOpen" class="table-wrap">
        <table>
          <thead><tr><th>事件</th><th>目标</th><th>业务域 / 代理</th><th>时间</th></tr></thead>
          <tbody>
            <tr v-for="a in audit" :key="a.id as string"><td><strong>{{ a.event_type || a.action }}</strong></td><td>{{ a.target_id || a.tool_id || a.name }}</td><td>{{ a.domain || 'platform' }} {{ a.agent_id || '' }}</td><td>{{ a.created_at }}</td></tr>
            <tr v-if="!audit.length"><td colspan="4" class="empty">暂无审计记录</td></tr>
          </tbody>
        </table>
      </div>
      <div class="tool-groups">
        <section v-for="group in visibleGroups" :key="group.key" class="tool-group">
          <div class="tool-group-head">
            <div>
              <div class="tool-group-title">{{ group.title }}</div>
              <div class="tool-group-sub">{{ group.subtitle }}</div>
            </div>
            <span class="tool-group-count">{{ group.items.length }} 个工具</span>
          </div>
          <div class="tool-grid">
            <article v-for="t in group.items" :key="(t.tool_id || t.name) as string" class="tool-card" :class="{ error: t.load_error }">
              <div class="tool-card-head">
                <div class="tool-title-wrap">
                  <strong>{{ shortToolName(t) }}</strong>
                </div>
                <div class="tool-mini-id">{{ t.tool_id || t.name }}</div>
              </div>
              <div class="tool-compact-meta">
                <div class="kv"><span>类型</span><b>{{ toolTypeLabel(t) }}</b></div>
                <div class="kv"><span>来源</span><b>{{ toolOrigin(t) }}</b></div>
                <div class="kv"><span>参数</span><b>{{ parameterCount(t) }}</b></div>
                <div class="kv"><span>启用</span><b>{{ toolEnabledLabel(t) }}</b></div>
                <div class="kv"><span>可见</span><b>{{ toolVisibleLabel(t) }}</b></div>
              </div>
              <div class="tool-actions">
                <label class="toggle" :title="t.binding_status === 'disabled' ? '点击启用' : '点击停用'">
                  <input type="checkbox" :checked="t.binding_status !== 'disabled'" @change="setBinding(t, t.binding_status === 'disabled' ? 'enabled' : 'disabled')" />
                  <span class="toggle-slider"></span>
                </label>
                <select class="select-sm" :value="t.binding_visibility || 'discoverable'" @change="setBinding(t, String(t.binding_status || 'enabled'), ($event.target as HTMLSelectElement).value)">
                  <option value="discoverable">可见</option>
                  <option value="hidden">隐藏</option>
                </select>
                <button v-if="String(t.source_type || '').toLowerCase() === 'python'" class="btn btn-ghost btn-xs" @click="openEditTool(t)">编辑</button>
                <button class="btn btn-ghost btn-xs" @click="testSavedTool(t)">测试</button>
              </div>
              <details class="tool-detail">
                <summary>参数模式 / 详情</summary>
                <p class="tool-desc">{{ t.description || '无描述' }}</p>
                <pre class="json-box">{{ JSON.stringify(t.parameter_schema || {}, null, 2) }}</pre>
              </details>
              <label v-if="isRisky(t)" class="tool-risk">
                <input type="checkbox" v-model="riskChecked[String(t.tool_id || t.name)]" @change="saveRiskApproval(t)" /> 风险确认
              </label>
              <div v-if="t.load_error" class="tool-load-error">加载异常：{{ t.load_error }}</div>
              <div v-if="t.catalog_status" class="tool-load-error">{{ t.catalog_status }}</div>
            </article>
          </div>
        </section>
        <div v-if="!visible.length" class="empty">暂无工具</div>
      </div>
    </section>
    <section v-if="output" class="panel"><div class="section-title">结果</div><div class="result-summary"><p>{{ outputHeadline(output) }}</p></div><details><summary>查看完整响应</summary><pre class="json-box">{{ JSON.stringify(output, null, 2) }}</pre></details></section>

    <!-- ═══ 创建工具右侧抽屉 ═══ -->
    <div v-if="createOpen" class="drawer-backdrop" @click.self="createOpen = false">
      <aside class="tool-drawer">
        <div class="drawer-head">
          <div>
            <div class="drawer-title">{{ editingToolId ? '编辑工具' : '创建工具' }}</div>
            <div class="drawer-sub">{{ editingToolId ? form.tool_name || '编辑中' : '创建 Python 脚本工具' }}</div>
          </div>
          <div class="drawer-head-actions">
            <button class="btn btn-ghost btn-sm" @click="createOpen = false; editingToolId = ''">关闭</button>
          </div>
        </div>
        <div class="tool-drawer-body">
          <div class="form-group"><label>类型</label><select v-model="createKind" disabled><option value="python">Python 脚本工具</option></select><div class="hint">HTTP / DB 工具后端暂未完整实现，先不开放创建入口。</div></div>
        <div class="form-row">
          <div class="form-group"><label>工具 ID *</label><input v-model="form.tool_id" :disabled="!!editingToolId" placeholder="weather_lookup" /></div>
          <div class="form-group"><label>业务域</label><select v-model="domain" @change="loadTools"><option v-for="d in domainOptions" :key="String(d.domain)" :value="d.domain">{{ d.label || d.domain }}</option></select></div>
        </div>
        <div class="form-row">
          <div class="form-group"><label>工具名</label><input v-model="form.tool_name" placeholder="同工具ID" /></div>
          <div class="form-group"><label>显示名</label><input v-model="form.display_name" /></div>
        </div>
        <div class="form-group"><label>描述</label><input v-model="form.description" /></div>

        <template v-if="createKind === 'http'">
          <div class="form-row">
            <div class="form-group" style="flex:2"><label>URL *</label><input v-model="form.url" placeholder="https://api.example.com/run" /></div>
            <div class="form-group"><label>方法</label><select v-model="form.method"><option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option></select></div>
          </div>
        </template>
        <template v-else-if="createKind === 'db-query'">
          <div class="form-group"><label>SQL 模板 *</label><textarea v-model="form.query_template" rows="3" placeholder="SELECT id,name FROM equipment WHERE name=:name" /></div>
          <div class="form-group"><label>最大行数</label><input v-model.number="form.max_rows" type="number" min="1" max="500" /></div>
        </template>
        <template v-else>
          <div class="form-group">
            <label>固定格式说明</label>
            <div class="hint">脚本从 stdin 读取 JSON：{"args": {...}, "context": {...}}；stdout 输出 {"ok": true, "result": ...}。</div>
          </div>
          <div class="form-group"><label>参数模式 (JSON) *</label><textarea v-model="form.parameter_schema" rows="7" /></div>
          <div class="form-group"><label>测试参数 (JSON)</label><textarea v-model="form.test_args" rows="4" /></div>
          <div class="form-group"><label>Python 脚本 *</label><textarea v-model="form.script" rows="12" class="code-textarea" /></div>
        </template>
        <div class="form-group"><label>超时 (ms)</label><input v-model.number="form.timeout_ms" type="number" min="100" step="100" /></div>

        </div>
        <div class="drawer-actions">
          <button class="btn btn-ghost" @click="createOpen = false; editingToolId = ''">取消</button>
          <button v-if="createKind === 'python'" class="btn btn-ghost" @click="validatePythonTool(false)">检查脚本</button>
          <button v-if="createKind === 'python'" class="btn btn-ghost" @click="validatePythonTool(true)">测试运行</button>
          <button class="btn btn-primary" @click="saveTool">{{ editingToolId ? '保存' : '创建' }}</button>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.tools-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; margin: 14px 0 18px; padding: 10px; border: 1px solid #e2e8f0; border-radius: 18px; background: #fff; box-shadow: 0 1px 2px rgba(15, 23, 42, .04); }
.tool-tabs { display: flex; gap: 6px; flex-wrap: wrap; align-content: flex-start; }
.tool-tab { border: 1px solid #e2e8f0; border-radius: 12px; padding: 8px 12px; background: #f8fafc; color: #475569; font-size: 12px; font-weight: 800; cursor: pointer; display: inline-flex; align-items: center; gap: 7px; transition: .16s ease; }
.tool-tab:hover { border-color: #bfdbfe; color: #1d4ed8; }
.tool-tab span { min-width: 20px; padding: 1px 6px; border-radius: 999px; background: #e2e8f0; color: #334155; font-size: 11px; text-align: center; }
.tool-tab.active { background: #0f172a; color: #fff; box-shadow: 0 8px 20px rgba(15, 23, 42, .16); }
.tool-tab.active span { background: rgba(255,255,255,.18); color: #fff; }
.tool-groups { display: flex; flex-direction: column; gap: 14px; }
.tool-group { border: 1px solid #e2e8f0; border-radius: 18px; padding: 14px; background: linear-gradient(180deg, #fff, #fbfdff); box-shadow: 0 1px 2px rgba(15, 23, 42, .04); }
.tool-group-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 12px; padding-bottom: 10px; border-bottom: 1px solid #edf2f7; }
.tool-group-title { font-size: 15px; font-weight: 850; color: var(--text); }
.tool-group-sub { margin-top: 2px; font-size: 12px; color: var(--muted); }
.tool-group-count { flex: none; border: 1px solid #dbeafe; border-radius: 999px; padding: 4px 9px; font-size: 12px; font-weight: 800; color: #1d4ed8; background: #eff6ff; }
.tool-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(285px, 1fr)); gap: 12px; }
.tool-card { min-height: 0; padding: 12px; border: 1px solid #e5e7eb; border-radius: 14px; background: #fff; box-shadow: 0 1px 2px rgba(15, 23, 42, .04); display: flex; flex-direction: column; gap: 8px; }
.tool-card:hover { border-color: #bfdbfe; box-shadow: 0 0 0 3px #eff6ff; }
.tool-card.error { border-color: #fecaca; background: #fffafa; }
.tool-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.tool-title-wrap { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.tool-title-wrap strong { font-size: 13.5px; line-height: 1.25; overflow-wrap: anywhere; }
.tool-display-name { font-size: 11px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tool-mini-id { font-size: 10px; color: #64748b; font-family: ui-monospace, Menlo, Consolas, monospace; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 170px; }
.tool-desc { margin: 0; font-size: 12px; color: var(--muted); line-height: 1.35; }
.tool-compact-meta { display: grid; gap: 4px 8px; grid-template-columns: repeat(2, minmax(0, 1fr)); }
.tool-compact-meta .kv { display: flex; justify-content: space-between; align-items: center; gap: 6px; font-size: 11px; padding: 3px 5px; border-radius: 7px; background: #f8fafc; border: 1px solid #eef2f7; }
.tool-compact-meta .kv span { color: #64748b; }
.tool-compact-meta .kv b { color: #1e293b; font-weight: 650; max-width: 70%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tool-detail { font-size: 11px; color: #475569; }
.tool-detail summary { cursor: pointer; font-weight: 700; font-size: 11.5px; }
.tool-detail .tool-desc { margin-top: 5px; }
.tool-detail .json-box { margin: 6px 0 0; max-height: 120px; overflow: auto; background: #0f172a; color: #f8fafc; border-radius: 8px; padding: 8px; font-size: 11px; line-height: 1.3; }
.tool-risk { display: inline-flex; align-items: center; gap: 6px; color: #b45309; font-size: 11px; font-weight: 650; }
.tool-actions { display: flex; align-items: center; justify-content: space-between; gap: 6px; margin-top: auto; }
.select-sm { height: 28px; min-width: 112px; border: 1px solid var(--border); border-radius: 7px; background: #fff; color: var(--text); font-size: 11px; padding: 0 7px; }
.toggle { position: relative; display: inline-block; width: 34px; height: 19px; flex-shrink: 0; }
.toggle input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; cursor: pointer; inset: 0; background: #cbd5e1; border-radius: 99px; transition: .2s; }
.toggle-slider::before { content: ""; position: absolute; width: 13px; height: 13px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: .2s; }
.toggle input:checked + .toggle-slider { background: var(--blue); }
.toggle input:checked + .toggle-slider::before { transform: translateX(15px); }
.drawer-backdrop { position: fixed; inset: 0; background: rgba(15, 23, 42, .28); display: flex; justify-content: flex-end; z-index: 1000; }
.tool-drawer { width: min(720px, 92vw); height: 100vh; background: #fff; border-left: 1px solid var(--border); box-shadow: -18px 0 44px rgba(15, 23, 42, .18); display: flex; flex-direction: column; overflow: hidden; }
.drawer-head { padding: 18px 20px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; gap: 12px; align-items: center; }
.drawer-title { font-size: 16px; font-weight: 700; }
.drawer-sub { font-size: 12px; color: var(--muted); margin-top: 3px; }
.drawer-head-actions { display: flex; gap: 8px; align-items: center; }
.tool-drawer-body { padding: 18px 20px 22px; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; }
.tool-drawer .form-row { display: flex; gap: 12px; flex-wrap: wrap; }
.tool-drawer .form-group { display: flex; flex-direction: column; gap: 5px; flex: 1; min-width: 130px; }
.tool-drawer .form-group label { font-size: 12px; font-weight: 600; color: var(--muted); }
.drawer-actions { display: flex; gap: 8px; justify-content: flex-end; border-top: 1px solid var(--border); padding-top: 14px; }
@media (max-width: 980px) {
  .tools-toolbar { align-items: stretch; }
}
</style>
