<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { currentOrgId, fmtDate, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'
import { confirmDialog, promptDialog } from '../stores/dialog'

const BASE = '/platform/frontend/mcp'
const TOOLS_BASE = '/platform/frontend/tools'

const servers = ref<JsonMap[]>([])
const expanded = ref<Record<string, string>>({})
const serverTools = ref<Record<string, JsonMap[]>>({})
const boundAgents = ref<Record<string, JsonMap[]>>({})
const toolStatus = ref<Record<string, string>>({})
const toolVisibility = ref<Record<string, string>>({})
const toolOutput = ref<Record<string, string>>({})
const toolSearch = ref<Record<string, string>>({})
const serverProbe = ref<Record<string, JsonMap | null>>({})
const serverProbing = ref<Record<string, boolean>>({})
const quickProbe = ref<JsonMap | null>(null)
const quickProbing = ref(false)
const quickEndpoint = ref('')
const error = ref('')
const loading = ref(true)

const modalOpen = ref(false)
const modalProbe = ref<JsonMap | null>(null)
const modalTesting = ref(false)
const modalToolSearch = ref('')
const saving = ref(false)
const form = reactive({
  id: '',
  name: '',
  transport: 'streamable-http',
  endpoint: '',
  command: 'node',
  args: '',
  description: '',
  auth_header: '',
  timeout_ms: 5000,
  tool_filter: '',
})

function headers(json = false) {
  return makeHeaders(json, currentOrgId())
}
async function api(method: string, path = '', body?: JsonMap) {
  return await readJson<JsonMap>(await fetch(BASE + path, { method, headers: headers(Boolean(body)), body: body ? JSON.stringify(body) : undefined }))
}
async function toolsApi(method: string, path: string, body?: JsonMap) {
  return await readJson<JsonMap>(await fetch(TOOLS_BASE + path, { method, headers: headers(Boolean(body)), body: body ? JSON.stringify(body) : undefined }))
}

async function load() {
  loading.value = true
  try {
    const data = await api('GET', '')
    servers.value = (data.mcp_servers || data.items || data.servers || []) as JsonMap[]
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

function openAdd() {
  form.id = ''
  form.name = ''
  form.transport = 'streamable-http'
  form.endpoint = ''
  form.command = 'node'
  form.args = ''
  form.description = ''
  form.auth_header = ''
  form.timeout_ms = 5000
  form.tool_filter = ''
  modalProbe.value = null
  modalToolSearch.value = ''
  modalOpen.value = true
}
function editServer(s: JsonMap) {
  form.id = String(s.id)
  form.name = String(s.name || '')
  form.transport = String(s.transport || 'streamable-http')
  form.endpoint = String(s.endpoint || '')
  form.command = String(s.command || 'node')
  form.args = Array.isArray(s.args) ? (s.args as string[]).join('\n') : ''
  form.description = String(s.description || '')
  form.auth_header = ''
  form.timeout_ms = typeof s.timeout_ms === 'number' ? s.timeout_ms : 5000
  form.tool_filter = Array.isArray(s.tool_filter) ? (s.tool_filter as string[]).join(', ') : ''
  modalProbe.value = null
  modalToolSearch.value = ''
  modalOpen.value = true
}

async function testModalEndpoint() {
  if (form.transport !== 'stdio' && !form.endpoint.trim()) {
    notifyError('请填写 Endpoint')
    return
  }
  if (form.transport === 'stdio' && !form.command.trim()) {
    notifyError('请填写 Command')
    return
  }
  modalTesting.value = true
  modalProbe.value = null
  try {
    const data = await api('POST', '/probe', { transport: form.transport, endpoint: form.endpoint.trim(), command: form.command.trim(), args: form.args.split('\n').map((s) => s.trim()).filter(Boolean), auth_header: form.auth_header.trim() || undefined })
    modalProbe.value = (data.probe as JsonMap) || null
  } catch (err) {
    modalProbe.value = { ok: false, error: err instanceof Error ? err.message : String(err) }
  } finally {
    modalTesting.value = false
  }
}

async function save() {
  if (!form.name.trim()) {
    notifyError('请填写名称')
    return
  }
  if (form.transport !== 'stdio' && !form.endpoint.trim()) {
    notifyError('请填写 Endpoint')
    return
  }
  if (form.transport === 'stdio' && !form.command.trim()) {
    notifyError('请填写 Command')
    return
  }
  saving.value = true
  try {
    const tool_filter = form.tool_filter.split(',').map((s) => s.trim()).filter(Boolean)
    const body: JsonMap = {
      name: form.name.trim(),
      transport: form.transport,
      endpoint: form.endpoint.trim(),
      command: form.command.trim(),
      args: form.args.split('\n').map((s) => s.trim()).filter(Boolean),
      description: form.description.trim() || null,
      timeout_ms: form.timeout_ms || 5000,
      tool_filter,
    }
    if (form.auth_header.trim()) body.auth_header = form.auth_header.trim()
    if (form.id) await api('PATCH', `/${form.id}`, body)
    else await api('POST', '', body)
    modalOpen.value = false
    await load()
    notifySuccess(`MCP 服务器 ${body.name} 已${form.id ? '保存' : '注册'}`)
  } catch (err) {
    notifyError(err)
  } finally {
    saving.value = false
  }
}

function probeMessage(probe: JsonMap | null): string {
  if (!probe) return ''
  if (probe.ok) {
    const tools = Array.isArray(probe.tools) ? probe.tools.join(' ') : ''
    return `✓ 连通正常 · ${probe.server_name || ''} v${probe.server_version || ''} · ${probe.tool_count ?? 0} 个工具${tools ? `\n${tools}` : ''}`
  }
  return `✗ 连通失败 [${probe.stage || '-'}]: ${probe.error || probe.message || '未知错误'}`
}
function initialToolFilter(): string[] {
  return form.tool_filter.split(',').map((s) => s.trim()).filter(Boolean)
}
function setInitialToolFilter(values: string[]) {
  form.tool_filter = Array.from(new Set(values.map((v) => String(v || '').trim()).filter(Boolean))).join(', ')
}
function modalToolName(tool: unknown): string {
  if (typeof tool === 'string') return tool
  if (tool && typeof tool === 'object') {
    const row = tool as JsonMap
    return String(row.tool_name || row.name || row.tool_id || row.runtime_name || '')
  }
  return ''
}
function modalToolDescription(tool: unknown): string {
  if (tool && typeof tool === 'object') {
    const row = tool as JsonMap
    return String(row.description || row.summary || '')
  }
  return ''
}
function modalToolCandidates(): unknown[] {
  const probeTools = Array.isArray(modalProbe.value?.tools) ? modalProbe.value?.tools as unknown[] : []
  const selected = initialToolFilter()
  const byName = new Map<string, unknown>()
  for (const tool of probeTools) {
    const name = modalToolName(tool)
    if (name) byName.set(name, tool)
  }
  for (const name of selected) {
    if (!byName.has(name)) byName.set(name, name)
  }
  const keyword = modalToolSearch.value.trim().toLowerCase()
  const tools = Array.from(byName.values())
  if (!keyword) return tools
  return tools.filter((tool) => `${modalToolName(tool)} ${modalToolDescription(tool)}`.toLowerCase().includes(keyword))
}
function isInitialToolSelected(toolName: string): boolean {
  return initialToolFilter().includes(toolName)
}
function toggleInitialTool(toolName: string) {
  const name = toolName.trim()
  if (!name) return
  const selected = initialToolFilter()
  const idx = selected.indexOf(name)
  if (idx >= 0) selected.splice(idx, 1)
  else selected.push(name)
  setInitialToolFilter(selected)
}
function selectAllInitialTools() {
  setInitialToolFilter(modalToolCandidates().map((tool) => modalToolName(tool)))
}
function clearInitialTools() {
  form.tool_filter = ''
}
function healthMeta(s: JsonMap): string {
  const m = (s.metadata as JsonMap) || {}
  const parts: string[] = []
  if (m.last_tool_count != null) parts.push(`${m.last_tool_count} 个工具`)
  if (m.server_name) parts.push(`${m.server_name}${m.server_version ? ` v${m.server_version}` : ''}`)
  if (m.last_discovered_at) parts.push(`发现 ${fmtDate(m.last_discovered_at)}`)
  if (m.last_error) parts.push(`错误 ${m.last_error}`)
  return parts.length ? parts.join(' · ') : '尚未执行连通测试'
}
function serverTransport(s: JsonMap): string {
  return String(s.transport || 'streamable-http')
}
function serverStatus(s: JsonMap): { label: string; cls: string } {
  if (s.enabled === false) return { label: '已停用', cls: 'badge-gray' }
  const status = String((s.metadata as JsonMap | undefined)?.health_status || 'unknown')
  if (status === 'healthy') return { label: '健康', cls: 'badge-green' }
  if (status === 'unhealthy') return { label: '异常', cls: 'badge-red' }
  return { label: '未检测', cls: 'badge-blue' }
}
function serverLifeStatus(s: JsonMap): string {
  const status = String(s.status || '').toLowerCase()
  if (!status || status === 'active') return '运行中'
  if (status === 'inactive' || status === 'disabled') return '已禁用'
  if (status === 'error' || status === 'failed') return '异常'
  return status || '运行中'
}
function serverLastDiscoveredAt(s: JsonMap): string {
  const m = (s.metadata as JsonMap | undefined) || {}
  return m.last_discovered_at ? fmtDate(m.last_discovered_at) : '未同步'
}
function serverToolCount(s: JsonMap): number {
  const tools = serverTools.value[String(s.id)] || []
  if (tools.length) return tools.length
  const m = (s.metadata as JsonMap | undefined) || {}
  return Number.isFinite(Number(m.last_tool_count)) ? Number(m.last_tool_count) : 0
}
function toolParamCount(t: JsonMap): number {
  const props = (t.parameter_schema as JsonMap | undefined)?.properties
  if (!props || typeof props !== 'object') return 0
  return Object.keys(props).length
}
function paramNames(t: JsonMap): string {
  const props = (t.parameter_schema as JsonMap | undefined)?.properties
  if (!props || typeof props !== 'object') return '无'
  const keys = Object.keys(props).sort()
  return keys.length ? keys.join(', ') : '无'
}
function visibleTools(serverId: string): JsonMap[] {
  const tools = serverTools.value[serverId] || []
  const keyword = (toolSearch.value[serverId] || '').trim().toLowerCase()
  if (!keyword) return tools
  return tools.filter((t) => {
    const haystack = [
      t.tool_name,
      t.tool_id,
      t.runtime_name,
      t.description,
      paramNames(t),
    ].map((v) => String(v || '').toLowerCase()).join(' ')
    return haystack.includes(keyword)
  })
}

async function probe(s: JsonMap) {
  const id = String(s.id)
  serverProbing.value[id] = true
  try {
    const data = await api('POST', `/${id}/probe`)
    serverProbe.value[id] = (data.probe as JsonMap) || null
    if (data.server) {
      const idx = servers.value.findIndex((x) => String(x.id) === id)
      if (idx >= 0) servers.value[idx] = data.server as JsonMap
    }
    if (expanded.value[id] === 'tools') await showTools(s, true)
  } catch (err) {
    serverProbe.value[id] = { ok: false, error: err instanceof Error ? err.message : String(err) }
  } finally {
    serverProbing.value[id] = false
  }
}
async function probeQuick() {
  if (!quickEndpoint.value.trim()) {
    notifyError('请输入 endpoint')
    return
  }
  quickProbing.value = true
  try {
    const data = await api('POST', '/probe', { endpoint: quickEndpoint.value.trim() })
    quickProbe.value = (data.probe as JsonMap) || null
  } catch (err) {
    quickProbe.value = { ok: false, error: err instanceof Error ? err.message : String(err) }
  } finally {
    quickProbing.value = false
  }
}
async function toggle(s: JsonMap) {
  try {
    await api('PATCH', `/${s.id}`, { enabled: !s.enabled })
    await load()
    notifySuccess(`${s.name || s.id} 已${s.enabled ? '禁用' : '启用'}`)
  } catch (err) {
    notifyError(err)
  }
}
async function remove(s: JsonMap) {
  if (!(await confirmDialog(`确认删除 MCP 服务器 "${s.name || s.id}"？相关绑定也会一并删除。`, { title: '删除服务器', danger: true }))) return
  try {
    await api('DELETE', `/${s.id}`)
    await load()
    notifySuccess(`MCP 服务器 ${s.name || s.id} 已删除`)
  } catch (err) {
    notifyError(err)
  }
}
async function showTools(s: JsonMap, reload = false) {
  const id = String(s.id)
  if (!reload) expanded.value[id] = expanded.value[id] === 'tools' ? '' : 'tools'
  if (reload || expanded.value[id] === 'tools') {
    const data = await api('GET', `/${id}/tools`)
    const tools = (data.tools || data.items || []) as JsonMap[]
    serverTools.value[id] = tools
    const idx = servers.value.findIndex((x) => String(x.id) === id)
    if (idx >= 0) {
      const row = servers.value[idx]
      const metadata = { ...(row.metadata as JsonMap | undefined) }
      metadata.last_tool_count = tools.length
      metadata.last_discovered_at = new Date().toISOString()
      metadata.health_status = tools.length ? 'healthy' : 'unhealthy'
      if (tools.length === 0) {
        metadata.last_error = 'tools/list has no items'
      } else {
        delete metadata.last_error
      }
      servers.value[idx] = { ...(row as JsonMap), metadata }
    }
    for (const t of tools) {
      const key = `${id}:${t.tool_id}`
      toolStatus.value[key] = t.binding_status === 'enabled' ? 'enabled' : 'disabled'
      toolVisibility.value[key] = t.binding_visibility === 'discoverable' ? 'discoverable' : 'hidden'
    }
  }
}
async function probeAndReloadTools(s: JsonMap) {
  await probe(s)
  await showTools(s, true)
}
async function showBoundAgents(s: JsonMap) {
  const id = String(s.id)
  expanded.value[id] = expanded.value[id] === 'agents' ? '' : 'agents'
  if (expanded.value[id] === 'agents') {
    const data = await api('GET', `/${id}/agents`)
    boundAgents.value[id] = (data.agents || data.items || []) as JsonMap[]
  }
}
async function saveToolBinding(serverId: string, t: JsonMap) {
  const toolId = String(t.tool_id)
  const key = `${serverId}:${toolId}`
  try {
    await toolsApi('PUT', `/bindings/${encodeURIComponent(toolId)}`, {
      binding_status: toolStatus.value[key] || 'enabled',
      binding_visibility: toolVisibility.value[key] || 'discoverable',
      domain: null,
    })
    notifySuccess(`已保存工具绑定 ${t.tool_name || toolId}`)
    await showTools({ id: serverId }, true)
  } catch (err) {
    notifyError(err)
  }
}
async function testTool(serverId: string, t: JsonMap) {
  const toolId = String(t.tool_id)
  const key = `${serverId}:${toolId}`
  const raw = await promptDialog(`测试工具: ${t.tool_name || toolId}`, '输入 JSON 参数', '{}')
  if (raw === null) return
  let args: JsonMap = {}
  try {
    args = raw.trim() ? JSON.parse(raw) : {}
    if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error('参数必须是 JSON object')
  } catch (e) {
    notifyError(`JSON 参数无效: ${e instanceof Error ? e.message : String(e)}`)
    return
  }
  toolOutput.value[key] = '测试中…'
  try {
    const data = await toolsApi('POST', `/${encodeURIComponent(toolId)}/test`, { domain: null, arguments: args })
    toolOutput.value[key] = `ok · ${data.latency_ms}ms\n${data.result_preview || JSON.stringify(data.result ?? data, null, 2)}`
  } catch (err) {
    toolOutput.value[key] = `failed · ${err instanceof Error ? err.message : String(err)}`
  }
}
async function schemaHistory(serverId: string, t: JsonMap) {
  const toolId = String(t.tool_id)
  const key = `${serverId}:${toolId}`
  toolOutput.value[key] = '加载 schema 历史…'
  try {
    const data = await toolsApi('GET', `/${encodeURIComponent(toolId)}/schema-snapshots?limit=5`)
    const items = (data.items || []) as JsonMap[]
    if (!items.length) {
      toolOutput.value[key] = '暂无 schema 历史。先执行"测试连通"同步 tools/list。'
      return
    }
    toolOutput.value[key] = items
      .map((item) => {
        const props = (item.parameter_schema as JsonMap | undefined)?.properties
        const names = props && typeof props === 'object' ? Object.keys(props).sort() : []
        const time = item.discovered_at ? fmtDate(item.discovered_at) : '-'
        return `${item.version || '-'} · ${String(item.checksum || '').slice(0, 12)} · ${time} · 参数:${names.length ? names.join(',') : '无'}`
      })
      .join('\n')
  } catch (err) {
    toolOutput.value[key] = err instanceof Error ? err.message : String(err)
  }
}

onMounted(load)
</script>

<template>
  <div class="mcp-content">
    <div class="toolbar">
      <span class="toolbar-label">快速连通测试</span>
      <input v-model="quickEndpoint" class="probe-input" placeholder="输入 MCP endpoint，如 http://localhost:8101/mcp" @keydown.enter="probeQuick" />
      <div class="toolbar-right">
        <button class="btn btn-ghost btn-sm" :disabled="quickProbing" @click="probeQuick">{{ quickProbing ? '探测中…' : '测试连通性' }}</button>
        <button class="btn btn-primary btn-sm" @click="openAdd">+ 注册服务器</button>
        <button class="btn btn-ghost btn-sm" @click="load">↻ 刷新</button>
      </div>
    </div>
    <div v-if="quickProbe" class="probe-result" :class="quickProbe.ok ? 'probe-ok' : 'probe-fail'" style="margin-bottom:16px">{{ probeMessage(quickProbe) }}</div>

    <div class="section-title">已注册的 MCP 服务器</div>

    <div v-if="loading" class="empty-state"><div class="icon">🔌</div><p>正在加载...</p></div>
    <div v-else-if="error" class="empty-state"><div class="icon">⚠️</div><p style="color:var(--red)">{{ error }}</p></div>
    <div v-else-if="!servers.length" class="empty-state">
      <div class="icon">🔌</div>
      <p>还没有注册 MCP 服务器</p>
      <button class="btn btn-primary btn-sm" @click="openAdd">+ 注册第一个服务器</button>
    </div>
    <div v-else class="servers-grid">
      <div v-for="s in servers" :key="s.id as string" class="server-card" :class="{ 'disabled-card': !s.enabled }">
        <div class="server-head">
          <div class="server-icon">🔌</div>
          <div class="server-info">
            <div class="server-name">{{ s.name }}</div>
            <div class="server-endpoint">{{ s.endpoint }}</div>
            <div class="server-badges">
              <span class="badge badge-blue">传输: {{ serverTransport(s) }}</span>
              <span class="badge" :class="s.enabled ? 'badge-green' : 'badge-red'">{{ s.enabled ? '已启用' : '已禁用' }}</span>
              <span class="badge" :class="serverStatus(s).cls">{{ serverStatus(s).label }}</span>
              <span class="badge badge-blue">状态: {{ serverLifeStatus(s) }}</span>
            <span class="badge badge-gray">工具数: {{ serverToolCount(s) }}</span>
            <span class="badge badge-gray">上次发现: {{ serverLastDiscoveredAt(s) }}</span>
            </div>
          </div>
          <div class="server-toggle" @click.stop>
            <label class="toggle" :title="s.enabled ? '点击禁用' : '点击启用'">
              <input type="checkbox" :checked="Boolean(s.enabled)" @click.stop @change.stop="toggle(s)" />
              <span class="toggle-slider"></span>
            </label>
          </div>
        </div>
        <div v-if="s.description" class="server-desc">{{ s.description }}</div>
        <div class="server-desc">健康状态：{{ healthMeta(s) }}</div>
        <div class="server-tools">
          配置允许工具：
          <template v-if="(s.tool_filter as string[] | undefined)?.length">
            <span v-for="t in (s.tool_filter as string[])" :key="t">{{ t }}</span>
          </template>
          <span v-else style="color:var(--muted)">全部工具</span>
        </div>
        <div v-if="serverProbing[String(s.id)]" class="probe-result" style="background:#f8fafc;color:var(--muted)">正在探测...</div>
        <div v-else-if="serverProbe[String(s.id)]" class="probe-result" :class="serverProbe[String(s.id)]?.ok ? 'probe-ok' : 'probe-fail'">{{ probeMessage(serverProbe[String(s.id)]) }}</div>
        <div class="server-actions">
          <button class="btn btn-ghost btn-sm" @click="probe(s)">🔍 测试连通</button>
          <button class="btn btn-ghost btn-sm" @click="showTools(s)">🧰 发现工具</button>
          <button class="btn btn-ghost btn-sm" @click="showBoundAgents(s)">绑定关系</button>
          <button class="btn btn-ghost btn-sm" @click="editServer(s)">编辑</button>
          <button class="btn btn-danger btn-sm" @click="remove(s)">删除</button>
        </div>

        <div v-if="expanded[String(s.id)] === 'tools'" class="tools-panel open">
          <div class="tools-panel-head">
            <div>
              <div class="bindings-label">发现工具</div>
              <div class="tools-count">{{ serverToolCount(s) }} 个工具 · 显示 {{ visibleTools(String(s.id)).length }} 个</div>
            </div>
            <input v-model="toolSearch[String(s.id)]" class="tool-search" placeholder="搜索工具名、参数、描述..." />
          </div>
          <div v-if="(serverTools[String(s.id)] || []).length" class="tools-table compact">
            <div class="tools-table-head">
              <span>工具名</span>
              <span>参数数</span>
              <span>状态</span>
              <span>可见</span>
              <span>操作</span>
            </div>
            <div v-for="t in visibleTools(String(s.id))" :key="`${s.id}:${t.tool_id}`" class="tool-row">
              <div class="tool-main">
                <div class="tool-name">{{ t.tool_name }}</div>
                <div class="tool-meta">{{ t.tool_id }}</div>
                <div class="tool-desc" v-if="t.description">{{ t.description }}</div>
                <div v-if="!t.description" class="tool-desc">来源: {{ s.name }}</div>
              </div>
              <div class="tool-param-num">{{ toolParamCount(t) }}</div>
              <div class="tool-state">
                <span class="badge mini" :class="toolStatus[`${s.id}:${t.tool_id}`] === 'enabled' ? 'badge-green' : 'badge-red'">{{ toolStatus[`${s.id}:${t.tool_id}`] === 'enabled' ? '启用' : '停用' }}</span>
              </div>
              <div class="tool-state">
                <span class="badge mini" :class="toolVisibility[`${s.id}:${t.tool_id}`] === 'discoverable' ? 'badge-blue' : 'badge-gray'">{{ toolVisibility[`${s.id}:${t.tool_id}`] === 'discoverable' ? '可见' : '隐藏' }}</span>
              </div>
              <div class="tool-actions">
                <select v-model="toolStatus[`${s.id}:${t.tool_id}`]" class="select-sm" title="启停">
                  <option value="enabled">启用</option>
                  <option value="disabled">停用</option>
                </select>
                <select v-model="toolVisibility[`${s.id}:${t.tool_id}`]" class="select-sm" title="可见性">
                  <option value="discoverable">可见</option>
                  <option value="hidden">隐藏</option>
                </select>
                <button class="btn btn-ghost btn-sm" @click="testTool(String(s.id), t)">测试</button>
                <button class="btn btn-ghost btn-sm" @click="schemaHistory(String(s.id), t)">历史</button>
                <button class="btn btn-primary btn-sm" @click="saveToolBinding(String(s.id), t)">保存</button>
              </div>
              <div v-if="toolOutput[`${s.id}:${t.tool_id}`]" class="tool-test-out">{{ toolOutput[`${s.id}:${t.tool_id}`] }}</div>
            </div>
            <div v-if="!visibleTools(String(s.id)).length" class="tools-empty">没有匹配的工具。</div>
          </div>
          <div v-else class="tools-empty">
            没有发现到真实工具。请先确认 MCP 服务已启动，并且当前 transport 支持 tools/list。
            <button class="btn btn-ghost btn-sm" style="align-self:flex-start;margin-top:6px" @click="probeAndReloadTools(s)">测试并同步</button>
          </div>
        </div>

        <div v-if="expanded[String(s.id)] === 'agents'" class="agents-panel open">
          <div class="readonly-panel-head">
            <div>
              <strong>代理引用关系</strong>
              <div class="tools-count">只读展示；请到「代理」配置页修改 MCP 绑定。</div>
            </div>
          </div>
          <template v-if="(boundAgents[String(s.id)] || []).length">
            <div v-for="a in boundAgents[String(s.id)] || []" :key="String(a.agent_id)" class="agent-ref-row">
              <div>
                <div class="agent-ref-name">{{ a.display_name || a.name || a.agent_id }}</div>
                <div class="agent-ref-id">{{ a.agent_id }}</div>
              </div>
              <span class="badge badge-green">已引用</span>
            </div>
          </template>
          <div v-else class="tools-empty">暂无代理引用该 MCP。</div>
        </div>

      </div>
    </div>

    <div v-if="modalOpen" class="drawer-backdrop" @click.self="modalOpen = false">
      <aside class="mcp-drawer">
        <div class="drawer-head">
          <div>
            <div class="drawer-title">{{ form.id ? '编辑 MCP 服务器' : '注册 MCP 服务器' }}</div>
          <div class="drawer-sub">{{ form.name || (form.id ? '修改配置' : '新建 MCP 服务器' ) }}</div>
          </div>
          <div class="drawer-head-actions"><button class="btn btn-ghost btn-sm" @click="modalOpen = false">关闭</button></div>
        </div>
        <div class="mcp-drawer-body">
        <div class="form-group">
          <label class="form-label">服务器名称 *</label>
          <input v-model="form.name" class="form-input" placeholder="如 datetime-tools、calc-tools" />
        </div>
        <div class="form-group">
            <label class="form-label">MCP 传输 *</label>
          <select v-model="form.transport" class="form-input">
            <option value="streamable-http">streamable-http（推荐，HTTP POST /mcp）</option>
            <option value="stdio">stdio（本地进程）</option>
            <option value="sse">sse（旧式 SSE）</option>
            <option value="http">http（兼容 HTTP）</option>
          </select>
          <div class="form-hint">不同 transport 会保存成不同的 MCP 配置：stdio 使用 command/args，HTTP/SSE 使用 URL。</div>
        </div>
        <div v-if="form.transport === 'stdio'" class="form-group">
          <label class="form-label">命令 *</label>
          <input v-model="form.command" class="form-input mono" placeholder="node" />
        </div>
        <div v-if="form.transport === 'stdio'" class="form-group">
            <label class="form-label">启动参数（每行一个）</label>
          <textarea v-model="form.args" class="form-input mono" rows="4" placeholder="mcp-servers/platform-demo/server.mjs&#10;--transport&#10;stdio"></textarea>
          <div class="form-hint">本地 stdio MCP 服务器的启动参数。相对路径取决于后端进程工作目录。</div>
        </div>
        <div class="form-group">
          <label class="form-label">Endpoint {{ form.transport === 'stdio' ? '（stdio 可留空）' : '*' }}</label>
          <input v-model="form.endpoint" class="form-input mono" :disabled="form.transport === 'stdio'" placeholder="http://localhost:8101/mcp" />
          <div class="form-hint">streamable-http 通常是 /mcp；sse 通常是 /sse；stdio 不需要 URL。</div>
        </div>
        <div class="form-group">
          <label class="form-label">描述</label>
          <input v-model="form.description" class="form-input" placeholder="简要描述这个 MCP 服务器的用途" />
        </div>
        <div class="form-row">
          <div class="form-group">
            <label class="form-label">超时 (ms)</label>
            <input v-model.number="form.timeout_ms" class="form-input" type="number" min="500" max="60000" />
          </div>
          <div class="form-group">
            <label class="form-label">鉴权 Header</label>
            <input v-model="form.auth_header" class="form-input" type="password" placeholder="Bearer token...（可选）" />
          </div>
        </div>
        <div class="form-group">
          <div class="tool-filter-head">
            <div>
              <label class="form-label">初始工具过滤</label>
              <div class="form-hint">留空表示首次同步全部；点选工具即可限制初始同步范围。</div>
            </div>
            <div class="tool-filter-actions">
              <button class="btn btn-ghost btn-sm" type="button" :disabled="!modalToolCandidates().length" @click="selectAllInitialTools">全选</button>
              <button class="btn btn-ghost btn-sm" type="button" :disabled="!initialToolFilter().length" @click="clearInitialTools">清空</button>
            </div>
          </div>
          <div v-if="modalToolCandidates().length" class="tool-filter-box">
            <input v-model="modalToolSearch" class="form-input tool-filter-search" placeholder="搜索工具名或描述" />
            <div class="tool-filter-grid">
              <button
                v-for="tool in modalToolCandidates()"
                :key="modalToolName(tool)"
                type="button"
                class="tool-filter-card"
                :class="{ selected: isInitialToolSelected(modalToolName(tool)) }"
                @click="toggleInitialTool(modalToolName(tool))"
              >
                <span class="tool-filter-check">{{ isInitialToolSelected(modalToolName(tool)) ? '✓' : '+' }}</span>
                <span class="tool-filter-name">{{ modalToolName(tool) }}</span>
                <span v-if="modalToolDescription(tool)" class="tool-filter-desc">{{ modalToolDescription(tool) }}</span>
              </button>
            </div>
          </div>
          <div v-else class="tool-filter-empty">先点击“测试连通性”读取工具列表；未读取到时可在下方高级字段手动填写。</div>
          <details class="advanced-field">
            <summary>高级：兼容字段</summary>
            <input v-model="form.tool_filter" class="form-input mono" placeholder="tool1, tool2, tool3（逗号分隔）" />
          </details>
          <div class="form-hint">正式启停仍在服务器卡片的“发现工具”里逐个配置。</div>
        </div>
        <div v-if="modalProbe" class="probe-result" :class="modalProbe.ok ? 'probe-ok' : 'probe-fail'">{{ probeMessage(modalProbe) }}</div>
        <div class="drawer-actions">
          <button class="btn btn-ghost" @click="modalOpen = false">取消</button>
          <button class="btn btn-ghost" :disabled="modalTesting" @click="testModalEndpoint">🔍 {{ modalTesting ? '探测中…' : '测试连通性' }}</button>
          <button class="btn btn-primary" :disabled="saving" @click="save">{{ form.id ? '保存' : '注册' }}</button>
        </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.mcp-content { flex: 1; overflow-y: auto; padding: 24px; }
.toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; background: var(--panel); padding: 12px 16px; border-radius: 12px; border: 1px solid var(--border); }
.toolbar-label { font-size: 13px; font-weight: 600; flex-shrink: 0; }
.probe-input { flex: 1; height: 34px; font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; }
.toolbar-right { margin-left: auto; display: flex; gap: 8px; }
.section-title { font-size: 12px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; margin-bottom: 12px; }

.servers-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(340px, 1fr)); gap: 16px; align-content: start; }
.server-card { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 18px; display: flex; flex-direction: column; gap: 12px; transition: .15s; }
.server-card:hover { border-color: var(--blue); box-shadow: 0 0 0 3px #dbeafe; }
.server-card.disabled-card { opacity: .6; }
.server-head { display: flex; align-items: flex-start; gap: 12px; }
.server-icon { width: 40px; height: 40px; border-radius: 10px; background: linear-gradient(135deg, #dbeafe, #e0e7ff); display: flex; align-items: center; justify-content: center; font-size: 20px; flex-shrink: 0; }
.server-info { flex: 1; min-width: 0; }
.server-toggle { flex-shrink: 0; padding-top: 2px; }
.toggle { position: relative; display: inline-block; width: 36px; height: 20px; }
.toggle input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; cursor: pointer; inset: 0; background: #cbd5e1; border-radius: 99px; transition: .2s; }
.toggle-slider::before { content: ""; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: .2s; }
.toggle input:checked + .toggle-slider { background: var(--blue); }
.toggle input:checked + .toggle-slider::before { transform: translateX(16px); }
.server-name { font-size: 14px; font-weight: 700; }
.server-endpoint { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; margin-top: 2px; }
.server-badges { display: flex; gap: 5px; flex-wrap: wrap; margin-top: 4px; }
.server-badges .badge { font-size: 10px; font-weight: 600; padding: 2px 7px; border-radius: 99px; }
.badge-green { background: #dcfce7; color: #166534; }
.badge-red { background: #fee2e2; color: #dc2626; }
.badge-blue { background: #dbeafe; color: #1d4ed8; }
.badge-gray { background: #f1f5f9; color: #475569; }
.server-desc { font-size: 12px; color: var(--muted); line-height: 1.5; }
.server-tools { font-size: 11px; color: var(--muted); display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.server-tools span { font-family: ui-monospace, Menlo, Consolas, monospace; background: #f8fafc; padding: 1px 5px; border-radius: 4px; border: 1px solid var(--border); }
.tool-filter-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.tool-filter-actions { display: flex; gap: 6px; flex-shrink: 0; }
.tool-filter-box { border: 1px solid var(--border); border-radius: 8px; padding: 10px; background: #f8fafc; margin-top: 8px; }
.tool-filter-search { height: 32px; margin-bottom: 10px; }
.tool-filter-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 8px; max-height: 220px; overflow-y: auto; }
.tool-filter-card { min-height: 58px; text-align: left; border: 1px solid var(--border); background: #fff; border-radius: 8px; padding: 8px 9px 8px 32px; position: relative; cursor: pointer; display: flex; flex-direction: column; gap: 3px; color: var(--text); }
.tool-filter-card:hover { border-color: var(--blue); box-shadow: 0 0 0 2px #dbeafe; }
.tool-filter-card.selected { border-color: var(--blue); background: #eff6ff; }
.tool-filter-check { position: absolute; left: 9px; top: 9px; width: 16px; height: 16px; border-radius: 50%; background: #e2e8f0; color: #475569; display: flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 700; }
.tool-filter-card.selected .tool-filter-check { background: var(--blue); color: #fff; }
.tool-filter-name { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; font-weight: 700; word-break: break-all; line-height: 1.3; }
.tool-filter-desc { color: var(--muted); font-size: 11px; line-height: 1.35; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.tool-filter-empty { border: 1px dashed var(--border); border-radius: 8px; padding: 10px 12px; color: var(--muted); background: #f8fafc; font-size: 12px; margin-top: 8px; }
.advanced-field { margin-top: 8px; }
.advanced-field summary { cursor: pointer; color: var(--muted); font-size: 12px; margin-bottom: 6px; }
.server-actions { display: flex; gap: 6px; flex-wrap: wrap; border-top: 1px solid var(--border); padding-top: 10px; }
.btn-success { background: #dcfce7; color: #166534; border-color: #86efac; }
.btn-success:hover { background: #bbf7d0; }

.probe-result { font-size: 11px; padding: 8px 10px; border-radius: 8px; font-family: ui-monospace, Menlo, Consolas, monospace; white-space: pre-wrap; word-break: break-all; max-height: 160px; overflow-y: auto; }
.probe-ok { background: #dcfce7; color: #166534; border: 1px solid #86efac; }
.probe-fail { background: #fee2e2; color: #dc2626; border: 1px solid #fca5a5; }

.tools-panel, .agents-panel { border-top: 1px solid var(--border); padding-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.readonly-panel-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.tools-panel-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.tools-count { font-size: 11px; color: var(--muted); margin-top: 2px; }
.tool-search { width: 220px; max-width: 48%; height: 30px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px; font-size: 12px; outline: none; }
.tool-search:focus { border-color: var(--blue); box-shadow: 0 0 0 3px #dbeafe; }
.panel-rows { display: flex; flex-direction: column; gap: 6px; }
.agent-ref-row { display: flex; align-items: center; justify-content: space-between; gap: 10px; background: #f8fafc; border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; }
.agent-ref-name { font-size: 12px; font-weight: 700; color: var(--text); }
.agent-ref-id { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; }
.tools-table { max-height: 420px; overflow: auto; border: 1px solid var(--border); border-radius: 10px; background: #fff; }
.tools-table-head { position: sticky; top: 0; z-index: 1; display: grid; grid-template-columns: minmax(180px, 1.6fr) 72px 72px 64px minmax(220px, 1.2fr); gap: 8px; align-items: center; padding: 7px 8px; background: #f8fafc; border-bottom: 1px solid var(--border); color: var(--muted); font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .04em; }
.tools-table.compact { max-height: 380px; }
.tool-row { display: grid; grid-template-columns: minmax(180px, 1.6fr) 72px 72px 64px minmax(220px, 1.2fr); gap: 8px; align-items: start; min-width: 520px; padding: 8px; border-bottom: 1px solid var(--border); }
.tool-row:last-child { border-bottom: 0; }
.tool-row:hover { background: #f8fafc; }
.tool-main { min-width: 0; }
.tool-name { font-size: 12px; font-weight: 700; font-family: ui-monospace, Menlo, Consolas, monospace; color: var(--text); overflow-wrap: anywhere; }
.tool-meta { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; overflow-wrap: anywhere; margin-top: 3px; }
.tool-desc { font-size: 11px; color: var(--muted); line-height: 1.4; margin-top: 4px; }
.tool-param-num { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; }
.tool-state { display: flex; gap: 5px; align-items: flex-start; flex-wrap: wrap; }
.tool-state .badge { font-size: 10px; padding: 2px 6px; border-radius: 99px; }
.tool-state .badge.mini { padding: 2px 5px; min-width: 56px; justify-content: center; }
.tool-actions { display: grid; grid-template-columns: 84px 80px auto auto auto; gap: 5px; align-items: center; }
.tool-actions .btn { white-space: nowrap; }
.tool-test-out { grid-column: 1 / -1; margin-top: 6px; padding: 7px 8px; border: 1px solid var(--border); border-radius: 7px; background: #f8fafc; font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; white-space: pre-wrap; word-break: break-all; max-height: 110px; overflow: auto; }
.select-sm { border: 1px solid var(--border); border-radius: 7px; background: #fff; color: var(--text); font-size: 12px; height: 30px; padding: 0 8px; }
.tools-empty { font-size: 12px; color: var(--muted); padding: 4px 0; display: flex; flex-direction: column; }

.empty-state { text-align: center; padding: 60px 20px; color: var(--muted); }
.empty-state .icon { font-size: 48px; margin-bottom: 12px; }
.empty-state p { font-size: 14px; margin-bottom: 16px; }

.drawer-backdrop { position: fixed; inset: 0; background: rgba(0, 0, 0, .28); display: flex; justify-content: flex-end; z-index: 1000; }
.mcp-drawer { width: min(540px, 92vw); height: 100vh; background: #fff; border-left: 1px solid var(--border); box-shadow: -18px 0 44px rgba(15, 23, 42, .18); display: flex; flex-direction: column; overflow: hidden; }
.drawer-head { padding: 18px 20px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; gap: 12px; align-items: center; }
.drawer-title { font-size: 15px; font-weight: 700; }
.drawer-sub { font-size: 12px; color: var(--muted); margin-top: 3px; }
.drawer-head-actions { display: flex; gap: 8px; align-items: center; }
.mcp-drawer-body { padding: 18px 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; }
.form-group { display: flex; flex-direction: column; gap: 5px; }
.form-label { font-size: 12px; font-weight: 600; color: var(--muted); }
.form-input { border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; font-size: 13px; outline: none; font-family: inherit; height: auto; }
.form-input.mono { font-family: ui-monospace, Menlo, Consolas, monospace; }
.form-input:focus { border-color: var(--blue); }
.form-hint { font-size: 11px; color: var(--muted); }
.form-row { display: flex; gap: 10px; }
.form-row .form-group { flex: 1; }
.drawer-actions { display: flex; gap: 8px; justify-content: flex-end; border-top: 1px solid var(--border); padding-top: 14px; }

@media (max-width: 980px) {
  .mcp-content { padding: 16px; }
  .servers-grid { grid-template-columns: 1fr; }
  .toolbar { align-items: stretch; flex-direction: column; }
  .toolbar-right { margin-left: 0; }
  .tools-panel-head { align-items: stretch; flex-direction: column; }
  .tool-search { width: 100%; max-width: none; }
}
</style>
