<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

type AgentOption = {
  agentId: string
  name: string
  version: string
  orchestration: string
  capabilities: string[]
}

type ExternalEvent = {
  sequence: number
  event: string
  id: string
  receivedAt: string
  elapsedMs: number
  data: string
  parsed: unknown
}

type ImageAttachment = { id: string; name: string; mediaType: string; data: string; size: number }
type TextAttachment = { id: string; name: string; mediaType: string; content: string; size: number }
type ApiKeyRow = { key_id: string; name: string; masked_key: string; status: string; created_at: string; last_used_at: string; expires_at: string }
type InvocationRow = {
  request_id: string; agent_id: string; call_mode: CallMode; session_id: string
  status: string; http_status: number; duration_ms: number; run_id: string; observe_url: string
  started_at: string; request: Record<string, unknown>
}

type OutputTab = 'timeline' | 'raw'
type CallMode = 'stream' | 'sync'
type AuthMode = 'x-api-key' | 'bearer'

const apiBaseUrl = ref(`${location.origin}/api/v1`)
const authMode = ref<AuthMode>('x-api-key')
const apiKey = ref('')
const agents = ref<AgentOption[]>([])
const agentId = ref('researcher')
const tenantId = ref('demo')
const userId = ref('caller-001')
const sessionId = ref(`external-${Date.now()}`)
const message = ref('请介绍一下你自己，并说明本次调用经过了哪些步骤。')
const callMode = ref<CallMode>('stream')
const outputTab = ref<OutputTab>('timeline')
const codeLanguage = ref<'curl' | 'javascript' | 'python' | 'java'>('curl')
const fileInput = ref<HTMLInputElement | null>(null)
const images = ref<ImageAttachment[]>([])
const files = ref<TextAttachment[]>([])
const attachmentError = ref('')
const requestHistory = ref<InvocationRow[]>([])
const apiKeys = ref<ApiKeyRow[]>([])
const keyName = ref('External integration')
const createdSecret = ref('')
const keyError = ref('')
const currentRunId = ref('')

const loadingAgents = ref(false)
const sending = ref(false)
const requestStatus = ref<'idle' | 'loading' | 'success' | 'error' | 'aborted'>('idle')
const responseStatus = ref<number | null>(null)
const responseAnswer = ref('')
const responseRaw = ref('')
const errorMessage = ref('')
const events = ref<ExternalEvent[]>([])
const startedAt = ref<number | null>(null)
const firstEventAt = ref<number | null>(null)
const finishedAt = ref<number | null>(null)
const agentError = ref('')
let abortController: AbortController | null = null
let timeoutId: number | undefined

const selectedAgent = computed(() => agents.value.find((item) => item.agentId === agentId.value.trim()) || null)
const requestPayload = computed(() => ({
  user_id: userId.value.trim() || 'external-user',
  session_id: sessionId.value.trim() || `session_${Date.now()}`,
  message: message.value,
  ...(images.value.length ? { images: images.value.map((item) => ({ data: item.data, media_type: item.mediaType })) } : {}),
  ...(files.value.length ? { files: files.value.map((item) => ({ name: item.name, media_type: item.mediaType, content: item.content })) } : {}),
}))
const endpoint = computed(() => `${normalizedBaseUrl()}/agents/${encodeURIComponent(agentId.value.trim() || 'researcher')}/chat${callMode.value === 'stream' ? '/stream' : ''}`)
const totalMs = computed(() => startedAt.value && finishedAt.value ? Math.max(0, finishedAt.value - startedAt.value) : null)
const firstEventMs = computed(() => startedAt.value && firstEventAt.value ? Math.max(0, firstEventAt.value - startedAt.value) : null)
const statusLabel = computed(() => ({ idle: '等待调用', loading: '调用中', success: '已完成', error: '调用失败', aborted: '已中止' }[requestStatus.value]))
const statusClass = computed(() => ({ idle: 'idle', loading: 'running', success: 'success', error: 'error', aborted: 'warning' }[requestStatus.value]))
const eventTypeSummary = computed(() => {
  const counts = new Map<string, number>()
  events.value.forEach((item) => counts.set(item.event, (counts.get(item.event) || 0) + 1))
  return [...counts.entries()].sort((a, b) => b[1] - a[1])
})
const tokenSummary = computed(() => events.value.reduce((sum, item) => {
  if (!item.parsed || typeof item.parsed !== 'object') return sum
  const payload = (item.parsed as Record<string, unknown>).payload
  if (!payload || typeof payload !== 'object') return sum
  const row = payload as Record<string, unknown>
  const input = Number(row.input_tokens || 0)
  const output = Number(row.output_tokens || 0)
  return sum + (Number.isFinite(input) ? input : 0) + (Number.isFinite(output) ? output : 0)
}, 0))
const toolCallCount = computed(() => events.value.filter((item) => item.event.includes('tool_call') && !item.event.includes('delta')).length)

function normalizedBaseUrl() {
  const value = apiBaseUrl.value.trim() || `${location.origin}/api/v1`
  return value.replace(/\/+$/, '')
}

function authHeaders(accept = ''): Record<string, string> {
  const headers: Record<string, string> = { Accept: accept || 'application/json' }
  if (accept) headers['Content-Type'] = 'application/json'
  if (apiKey.value.trim()) {
    headers[authMode.value === 'bearer' ? 'Authorization' : 'X-API-Key'] = authMode.value === 'bearer' ? `Bearer ${apiKey.value.trim()}` : apiKey.value.trim()
  }
  return headers
}

async function readResponseBody(response: Response): Promise<{ data: unknown; text: string }> {
  const text = await response.text()
  let data: unknown = {}
  try { data = text ? JSON.parse(text) : {} } catch { data = text }
  return { data, text }
}

function errorFrom(data: unknown, fallback: string) {
  if (data && typeof data === 'object') {
    const row = data as Record<string, unknown>
    const nested = row.error
    if (nested && typeof nested === 'object' && typeof (nested as Record<string, unknown>).message === 'string') return String((nested as Record<string, unknown>).message)
    if (typeof nested === 'string') return nested
    if (typeof row.detail === 'string') return row.detail
    if (typeof row.message === 'string') return row.message
  }
  return fallback
}

function normalizeAgent(raw: unknown): AgentOption | null {
  if (!raw || typeof raw !== 'object') return null
  const row = raw as Record<string, unknown>
  const id = String(row.agentId || row.agent_id || row.id || '').trim()
  if (!id) return null
  return {
    agentId: id,
    name: String(row.name || row.display_name || id),
    version: String(row.version || '-'),
    orchestration: String(row.orchestration || 'single_agent'),
    capabilities: Array.isArray(row.capabilities) ? row.capabilities.map(String) : [],
  }
}

async function loadAgents() {
  loadingAgents.value = true
  agentError.value = ''
  try {
    const response = await fetch(`${normalizedBaseUrl()}/agents`, { headers: authHeaders() })
    const body = await readResponseBody(response)
    if (!response.ok) throw new Error(errorFrom(body.data, `${response.status} ${response.statusText}`))
    const rawItems = body.data && typeof body.data === 'object'
      ? ((body.data as Record<string, unknown>).items || (body.data as Record<string, unknown>).agents || [])
      : []
    agents.value = Array.isArray(rawItems) ? rawItems.map(normalizeAgent).filter((item): item is AgentOption => !!item) : []
    if (!agents.value.some((item) => item.agentId === agentId.value) && agents.value[0]) agentId.value = agents.value[0].agentId
  } catch (error) {
    agentError.value = error instanceof Error ? error.message : String(error)
  } finally {
    loadingAgents.value = false
  }
}

function parseData(data: string): unknown {
  if (!data || data === '[DONE]') return data
  try { return JSON.parse(data) } catch { return data }
}

function extractText(value: unknown): string {
  if (typeof value === 'string') return value
  if (!value || typeof value !== 'object') return ''
  const row = value as Record<string, unknown>
  for (const key of ['delta', 'text', 'answer', 'content']) {
    if (typeof row[key] === 'string') return String(row[key])
  }
  if (row.payload && typeof row.payload === 'object') return extractText(row.payload)
  if (row.data && typeof row.data === 'object') return extractText(row.data)
  return ''
}

function formatData(value: unknown) {
  if (typeof value === 'string') return value
  try { return JSON.stringify(value, null, 2) } catch { return String(value) }
}

function dateText(value: unknown) {
  const time = Date.parse(String(value || ''))
  return Number.isFinite(time) ? new Date(time).toLocaleString('zh-CN', { hour12: false }) : '—'
}

function addEvent(event: string, id: string, data: string, raw = '') {
  if (firstEventAt.value === null) firstEventAt.value = Date.now()
  const parsed = parseData(data)
  if (parsed && typeof parsed === 'object') {
    const row = parsed as Record<string, unknown>
    const payload = row.payload && typeof row.payload === 'object' ? row.payload as Record<string, unknown> : row
    const run = String(payload.run_id || payload.runId || '')
    if (run) currentRunId.value = run
  }
  events.value.push({ sequence: events.value.length + 1, event: event || 'message', id, receivedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }), elapsedMs: startedAt.value ? Date.now() - startedAt.value : 0, data, parsed })
  if (raw) responseRaw.value += `${raw}${raw.endsWith('\n\n') ? '' : '\n\n'}`
  if (data === '[DONE]') return
  const text = extractText(parsed)
  if (text) responseAnswer.value += text
  if (event === 'error') errorMessage.value = errorFrom(parsed, '外部 Agent 返回了错误事件')
}

function clearOutput() {
  requestStatus.value = 'idle'
  responseStatus.value = null
  responseAnswer.value = ''
  responseRaw.value = ''
  errorMessage.value = ''
  events.value = []
  startedAt.value = null
  firstEventAt.value = null
  finishedAt.value = null
  currentRunId.value = ''
}

function finish(status: 'success' | 'error' | 'aborted') {
  finishedAt.value = Date.now()
  requestStatus.value = status
  sending.value = false
  abortController = null
  if (timeoutId) window.clearTimeout(timeoutId)
  timeoutId = undefined
  void loadHistory()
}

function attachmentId() {
  return `attachment_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`
}

function dataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error || new Error(`无法读取 ${file.name}`))
    reader.readAsDataURL(file)
  })
}

async function selectAttachments(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = Array.from(input.files || [])
  input.value = ''
  attachmentError.value = ''
  if (images.value.length + files.value.length + selected.length > 8) {
    attachmentError.value = '一次请求最多添加 8 个附件'
    return
  }
  try {
    for (const file of selected) {
      if (file.type.startsWith('image/')) {
        if (file.size > 5 * 1024 * 1024) throw new Error(`${file.name} 超过图片 5 MB 限制`)
        const encoded = await dataUrl(file)
        images.value.push({ id: attachmentId(), name: file.name, mediaType: file.type, data: encoded.slice(encoded.indexOf(',') + 1), size: file.size })
      } else {
        if (file.size > 200_000) throw new Error(`${file.name} 超过文本附件 200 KB 限制`)
        const mediaType = file.type || (/\.json$/i.test(file.name) ? 'application/json' : /\.md$/i.test(file.name) ? 'text/markdown' : 'text/plain')
        const allowed = mediaType.startsWith('text/') || ['application/json', 'application/xml', 'application/yaml', 'application/x-yaml', 'application/csv'].includes(mediaType)
        if (!allowed) throw new Error(`${file.name} 不是支持的图片或文本文件`)
        files.value.push({ id: attachmentId(), name: file.name, mediaType, content: await file.text(), size: file.size })
      }
    }
  } catch (error) {
    attachmentError.value = error instanceof Error ? error.message : String(error)
  }
}

function removeAttachment(id: string) {
  images.value = images.value.filter((item) => item.id !== id)
  files.value = files.value.filter((item) => item.id !== id)
}

function replayHistory(item: InvocationRow) {
  const request = item.request || {}
  agentId.value = item.agent_id
  callMode.value = item.call_mode
  tenantId.value = String(request.tenant_id || 'external')
  userId.value = String(request.user_id || 'external-user')
  sessionId.value = String(request.session_id || item.session_id || `external-${Date.now()}`)
  message.value = String(request.message || '')
  images.value = []
  files.value = []
  if (request.attachments_replayable === false) {
    attachmentError.value = '历史记录不会保存附件内容；请求参数已恢复，请重新添加附件后发送。'
    return
  }
  void sendRequest()
}

async function loadManagedAccess() {
  keyError.value = ''
  try {
    const [keysResponse, historyResponse] = await Promise.all([
      fetch('/platform/frontend/external-api/keys'),
      fetch('/platform/frontend/external-api/invocations?limit=50'),
    ])
    const keysBody = await readResponseBody(keysResponse)
    const historyBody = await readResponseBody(historyResponse)
    if (!keysResponse.ok) throw new Error(errorFrom(keysBody.data, '读取 API Key 失败'))
    if (!historyResponse.ok) throw new Error(errorFrom(historyBody.data, '读取调用历史失败'))
    apiKeys.value = Array.isArray((keysBody.data as Record<string, unknown>).items) ? (keysBody.data as { items: ApiKeyRow[] }).items : []
    requestHistory.value = Array.isArray((historyBody.data as Record<string, unknown>).items) ? (historyBody.data as { items: InvocationRow[] }).items : []
  } catch (error) {
    keyError.value = error instanceof Error ? error.message : String(error)
  }
}

async function loadHistory() {
  try {
    const response = await fetch('/platform/frontend/external-api/invocations?limit=50')
    const body = await readResponseBody(response)
    if (response.ok && body.data && typeof body.data === 'object') {
      const items = (body.data as Record<string, unknown>).items
      requestHistory.value = Array.isArray(items) ? items as InvocationRow[] : []
    }
  } catch { /* invocation result remains visible even when history refresh fails */ }
}

async function createManagedKey() {
  keyError.value = ''
  createdSecret.value = ''
  try {
    const response = await fetch('/platform/frontend/external-api/keys', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: keyName.value }),
    })
    const body = await readResponseBody(response)
    if (!response.ok) throw new Error(errorFrom(body.data, '创建 API Key 失败'))
    const item = (body.data as { item: ApiKeyRow & { secret: string } }).item
    createdSecret.value = item.secret
    apiKey.value = item.secret
    await loadManagedAccess()
    await loadAgents()
  } catch (error) { keyError.value = error instanceof Error ? error.message : String(error) }
}

async function revokeManagedKey(item: ApiKeyRow) {
  if (!window.confirm(`确认撤销 ${item.name}？撤销后无法恢复。`)) return
  try {
    const response = await fetch(`/platform/frontend/external-api/keys/${encodeURIComponent(item.key_id)}/revoke`, { method: 'POST' })
    const body = await readResponseBody(response)
    if (!response.ok) throw new Error(errorFrom(body.data, '撤销 API Key 失败'))
    await loadManagedAccess()
  } catch (error) { keyError.value = error instanceof Error ? error.message : String(error) }
}

async function copyResponse() {
  try { await navigator.clipboard.writeText(responseRaw.value || responseAnswer.value) } catch { /* optional permission */ }
}

async function copyCreatedSecret() {
  try { await navigator.clipboard.writeText(createdSecret.value) } catch { /* clipboard permission is optional */ }
}

function downloadResponse() {
  const content = responseRaw.value || responseAnswer.value
  if (!content) return
  const link = document.createElement('a')
  link.href = URL.createObjectURL(new Blob([content], { type: 'application/json;charset=utf-8' }))
  link.download = `agent-response-${Date.now()}.txt`
  link.click()
  URL.revokeObjectURL(link.href)
}

function emitSseFrame(frame: { event: string; id: string; data: string[] }) {
  if (!frame.data.length) return
  const raw = [
    ...(frame.event ? [`event: ${frame.event}`] : []),
    ...(frame.id ? [`id: ${frame.id}`] : []),
    ...frame.data.map((value) => `data: ${value}`),
  ].join('\n')
  addEvent(frame.event || 'message', frame.id, frame.data.join('\n'), raw)
}

async function consumeSse(response: Response) {
  if (!response.body) throw new Error('响应没有可读取的流')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let frame: { event: string; id: string; data: string[] } = { event: '', id: '', data: [] }
  const consumeLine = (line: string) => {
    if (!line) {
      emitSseFrame(frame)
      frame = { event: '', id: '', data: [] }
      return
    }
    if (line.startsWith(':')) return
    const separator = line.indexOf(':')
    const field = separator >= 0 ? line.slice(0, separator) : line
    const value = separator >= 0 ? line.slice(separator + 1).replace(/^ /, '') : ''
    if (field === 'event') frame.event = value
    else if (field === 'id') frame.id = value
    else if (field === 'data') frame.data.push(value)
  }
  while (true) {
    const result = await reader.read()
    buffer += decoder.decode(result.value || new Uint8Array(), { stream: !result.done })
    const lines = buffer.split(/\r?\n/)
    buffer = lines.pop() || ''
    lines.forEach(consumeLine)
    if (result.done) break
  }
  if (buffer) consumeLine(buffer)
  emitSseFrame(frame)
}

async function sendRequest() {
  if (sending.value) return
  if (!agentId.value.trim()) { errorMessage.value = '请填写 Agent ID'; requestStatus.value = 'error'; return }
  if (!message.value.trim()) { errorMessage.value = '请填写消息'; requestStatus.value = 'error'; return }
  clearOutput()
  sending.value = true
  requestStatus.value = 'loading'
  startedAt.value = Date.now()
  abortController = new AbortController()
  timeoutId = window.setTimeout(() => abortController?.abort(), 60000)
  try {
    const response = await fetch(endpoint.value, {
      method: 'POST',
      headers: { ...authHeaders(callMode.value === 'stream' ? 'text/event-stream' : 'application/json'), 'Content-Type': 'application/json' },
      body: JSON.stringify(requestPayload.value),
      signal: abortController.signal,
    })
    responseStatus.value = response.status
    if (!response.ok) {
      const body = await readResponseBody(response)
      responseRaw.value = body.text
      if (body.data && typeof body.data === 'object') currentRunId.value = String((body.data as Record<string, unknown>).runId || (body.data as Record<string, unknown>).run_id || '')
      throw new Error(errorFrom(body.data, `${response.status} ${response.statusText}`))
    }
    if (callMode.value === 'stream') {
      await consumeSse(response)
    } else {
      const body = await readResponseBody(response)
      responseRaw.value = body.text
      const text = extractText(body.data)
      responseAnswer.value = ''
      if (body.data && typeof body.data === 'object' && typeof (body.data as Record<string, unknown>).requestId === 'string') {
        addEvent('response', String((body.data as Record<string, unknown>).requestId), body.text)
      }
      responseAnswer.value = text || formatData(body.data)
    }
    if (errorMessage.value) finish('error')
    else finish('success')
  } catch (error) {
    responseRaw.value = responseRaw.value || ''
    if (error instanceof DOMException && error.name === 'AbortError') {
      errorMessage.value = '请求已中止（可能是手动停止或超过 60 秒）'
      finish('aborted')
    } else {
      errorMessage.value = error instanceof Error ? error.message : String(error)
      finish('error')
    }
  }
}

function stopRequest() {
  if (sending.value) abortController?.abort()
}

function codeAuthHeader() {
  return authMode.value === 'bearer' ? 'Authorization: Bearer YOUR_API_KEY' : 'X-API-Key: YOUR_API_KEY'
}

const generatedCode = computed(() => {
  const body = JSON.stringify(requestPayload.value, null, 2)
  if (codeLanguage.value === 'javascript') {
    return `const response = await fetch(${JSON.stringify(endpoint.value)}, {\n  method: 'POST',\n  headers: {\n    'Content-Type': 'application/json',\n    '${authMode.value === 'bearer' ? 'Authorization' : 'X-API-Key'}': '${authMode.value === 'bearer' ? 'Bearer ' : ''}YOUR_API_KEY',\n    Accept: '${callMode.value === 'stream' ? 'text/event-stream' : 'application/json'}',\n  },\n  body: JSON.stringify(${body}),\n})\n\nconsole.log(await response.text())`
  }
  if (codeLanguage.value === 'python') {
    return `import requests\n\nresponse = requests.post(\n    ${JSON.stringify(endpoint.value)},\n    headers={\n        "Content-Type": "application/json",\n        "${authMode.value === 'bearer' ? 'Authorization' : 'X-API-Key'}": "${authMode.value === 'bearer' ? 'Bearer ' : ''}YOUR_API_KEY",\n        "Accept": "${callMode.value === 'stream' ? 'text/event-stream' : 'application/json'}",\n    },\n    json=${body.replace(/"([^"\\]+)":/g, '$1:')},\n    stream=${callMode.value === 'stream' ? 'True' : 'False'},\n)\nprint(response.text)`
  }
  if (codeLanguage.value === 'java') {
    const header = authMode.value === 'bearer' ? 'Authorization", "Bearer YOUR_API_KEY' : 'X-API-Key", "YOUR_API_KEY'
    return `import java.net.URI;\nimport java.net.http.*;\n\nvar request = HttpRequest.newBuilder(URI.create(${JSON.stringify(endpoint.value)}))\n    .header("Content-Type", "application/json")\n    .header("Accept", "${callMode.value === 'stream' ? 'text/event-stream' : 'application/json'}")\n    .header("${header}")\n    .POST(HttpRequest.BodyPublishers.ofString(${JSON.stringify(JSON.stringify(requestPayload.value))}))\n    .build();\nvar response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());\nSystem.out.println(response.body());`
  }
  return `curl -N -X POST ${JSON.stringify(endpoint.value)} \\\n+  -H 'Content-Type: application/json' \\\n+  -H '${codeAuthHeader()}' \\\n+  -H 'Accept: ${callMode.value === 'stream' ? 'text/event-stream' : 'application/json'}' \\\n+  -d ${JSON.stringify(body)}`
})

async function copyCode() {
  try { await navigator.clipboard.writeText(displayCode.value) } catch { /* clipboard permission is optional */ }
}

const displayCode = computed(() => {
  let code = generatedCode.value.replace(/\n\+ /g, '\n')
  if (codeLanguage.value === 'python') code = code.replace(/(\n\s*)([A-Za-z_][\w-]*):/g, '$1"$2":')
  return code
})

onMounted(async () => { await loadManagedAccess(); if (apiKey.value) await loadAgents() })
onBeforeUnmount(() => {
  abortController?.abort()
  if (timeoutId) window.clearTimeout(timeoutId)
})
</script>

<template>
  <div class="external-workbench">
    <input ref="fileInput" class="hidden-file-input" type="file" multiple accept="image/*,.txt,.md,.markdown,.json,.csv,.xml,.yaml,.yml" @change="selectAttachments">
    <div class="external-intro">
      <div>
        <div class="external-eyebrow">EXTERNAL INTEGRATION</div>
        <h1>外部接入测试工作台</h1>
        <p>模拟外部系统通过稳定的 `/api/v1` 协议调用 Agent，快速确认鉴权、请求体、同步响应和 SSE 事件链路。</p>
      </div>
      <div class="external-security"><span class="security-mark">●</span><div><strong>密钥只显示一次</strong><small>服务端只保存哈希；明文仅驻留当前页面</small></div></div>
    </div>

    <div class="external-grid">
      <aside class="external-panel access-panel">
        <div class="external-panel-head"><div><span class="panel-kicker">01 / ACCESS</span><h2>接入配置</h2></div><span class="badge badge-blue">v1</span></div>
        <div class="external-field"><label for="external-base">API Base URL</label><input id="external-base" v-model="apiBaseUrl" spellcheck="false" placeholder="http://localhost:8080/api/v1"></div>
        <div class="external-field"><label>认证方式</label><div class="segmented"><button :class="{active: authMode === 'x-api-key'}" @click="authMode = 'x-api-key'">X-API-Key</button><button :class="{active: authMode === 'bearer'}" @click="authMode = 'bearer'">Bearer</button></div></div>
        <div class="external-field"><label for="external-key">API Key <span>必填</span></label><input id="external-key" v-model="apiKey" type="password" autocomplete="off" placeholder="输入测试 Key，不会保存"></div>
        <div class="field-hint">请求头：{{ authMode === 'bearer' ? 'Authorization: Bearer &lt;key&gt;' : 'X-API-Key: &lt;key&gt;' }}</div>
        <details class="key-manager" open>
          <summary>我的 API Key <strong>{{ apiKeys.filter(item => item.status === 'ACTIVE').length }}</strong></summary>
          <div class="key-create"><input v-model="keyName" maxlength="80" placeholder="Key 名称"><button class="btn btn-primary btn-sm" @click="createManagedKey">创建</button></div>
          <div v-if="createdSecret" class="secret-once"><strong>请立即保存，关闭后无法再次查看</strong><code>{{ createdSecret }}</code><button class="btn btn-ghost btn-sm" @click="copyCreatedSecret">复制</button></div>
          <div v-if="keyError" class="inline-error">{{ keyError }}</div>
          <div v-if="apiKeys.length" class="key-list"><div v-for="item in apiKeys" :key="item.key_id" class="key-row"><div><strong>{{ item.name }}</strong><small>{{ item.masked_key }} · {{ item.status }}<br>最近调用 {{ dateText(item.last_used_at) }}</small></div><button v-if="item.status === 'ACTIVE'" @click="revokeManagedKey(item)">撤销</button></div></div>
          <div v-else class="history-empty">创建后明文只显示一次，数据库只保存 SHA-256 哈希。</div>
        </details>
        <button class="btn btn-ghost refresh-btn" :disabled="loadingAgents" @click="loadAgents">{{ loadingAgents ? '读取中…' : '刷新可用 Agent' }}</button>
        <div v-if="agentError" class="inline-error">{{ agentError }}<small>检查服务是否开启外部 API，或确认 API Key 与 Base URL。</small></div>
        <div class="agent-discovery">
          <div class="discovery-head"><span>已发布 Agent</span><strong>{{ agents.length }}</strong></div>
          <div v-if="agents.length" class="agent-list">
            <button v-for="item in agents" :key="item.agentId" class="agent-option" :class="{active: item.agentId === agentId}" @click="agentId = item.agentId"><span class="agent-dot"></span><span><strong>{{ item.name }}</strong><small>{{ item.agentId }} · {{ item.version }}</small></span></button>
          </div>
          <div v-else class="agent-empty">还没有发现 Agent<br><small>也可以在右侧手动填写 Agent ID</small></div>
        </div>
        <div class="endpoint-hint"><span>调用端点</span><code>/agents/:agentId/chat</code><code>/agents/:agentId/chat/stream</code></div>
        <div class="history-section">
          <div class="discovery-head"><span>持久化调用历史</span><strong>{{ requestHistory.length }}/50</strong></div>
          <div v-if="requestHistory.length" class="history-list">
            <div v-for="item in requestHistory" :key="item.request_id" class="history-entry">
              <button class="history-item" :title="String(item.request.message || '')" :disabled="sending" @click="replayHistory(item)">
                <span class="history-status" :class="item.status.toLowerCase()"></span>
                <span><strong>{{ item.agent_id }} · {{ item.call_mode === 'stream' ? 'SSE' : 'JSON' }}</strong><small>{{ dateText(item.started_at) }} · {{ item.duration_ms }} ms · {{ String(item.request.message || '').slice(0, 28) }}</small></span>
              </button>
              <a v-if="item.observe_url" :href="item.observe_url" class="observe-link">观测</a>
            </div>
          </div>
          <div v-else class="history-empty">外部调用会持久化在这里，并关联运行观测。</div>
        </div>
      </aside>

      <section class="external-panel request-panel">
        <div class="external-panel-head"><div><span class="panel-kicker">02 / REQUEST</span><h2>请求编辑器</h2></div><div class="mode-switch"><button :class="{active: callMode === 'stream'}" @click="callMode = 'stream'">流式 SSE</button><button :class="{active: callMode === 'sync'}" @click="callMode = 'sync'">同步 JSON</button></div></div>
        <div class="external-field"><label for="external-agent">Agent ID</label><input id="external-agent" v-model="agentId" list="external-agent-list" spellcheck="false" placeholder="researcher"><datalist id="external-agent-list"><option v-for="item in agents" :key="item.agentId" :value="item.agentId">{{ item.name }}</option></datalist></div>
        <div v-if="selectedAgent" class="selected-agent"><span class="agent-dot"></span><span><strong>{{ selectedAgent.name }}</strong><small>{{ selectedAgent.orchestration }} · {{ selectedAgent.capabilities.join(' / ') || 'chat' }}</small></span></div>
        <div class="external-form-grid">
          <div class="external-field"><label for="external-tenant">tenant_id <span>由 Key 强制绑定</span></label><input id="external-tenant" value="managed-by-api-key" disabled></div>
          <div class="external-field"><label for="external-user">user_id <span>外部业务标识</span></label><input id="external-user" v-model="userId"></div>
          <div class="external-field wide"><label for="external-session">session_id</label><input id="external-session" v-model="sessionId"></div>
        </div>
        <div class="external-field message-field"><label for="external-message">message</label><textarea id="external-message" v-model="message" rows="7" placeholder="输入要交给外部 Agent 的问题"></textarea></div>
        <div class="attachment-section">
          <div class="attachment-head"><div><strong>文件与多模态</strong><small>图片送入视觉模型；TXT / Markdown / JSON / CSV / XML / YAML 作为内联文本</small></div><button class="btn btn-ghost btn-sm" :disabled="sending" @click="fileInput?.click()">添加附件</button></div>
          <div v-if="images.length || files.length" class="attachment-list">
            <div v-for="item in [...images, ...files]" :key="item.id" class="attachment-chip"><span>{{ item.mediaType.startsWith('image/') ? 'IMG' : 'TXT' }}</span><div><strong>{{ item.name }}</strong><small>{{ item.mediaType }} · {{ Math.ceil(item.size / 1024) }} KB</small></div><button :disabled="sending" title="移除" @click="removeAttachment(item.id)">×</button></div>
          </div>
          <div v-if="attachmentError" class="attachment-error">{{ attachmentError }}</div>
        </div>
        <div class="request-actions"><button class="btn btn-primary" :disabled="sending" @click="sendRequest">{{ sending ? '调用中…' : `发送${callMode === 'stream' ? '流式' : '同步'}请求` }}</button><button v-if="sending" class="btn btn-danger" @click="stopRequest">停止</button><button class="btn btn-ghost" :disabled="sending" @click="clearOutput">清空结果</button><span class="request-tip">超时：60 秒 · {{ endpoint }}</span></div>
        <div v-if="errorMessage" class="request-error"><strong>{{ statusLabel }}</strong><span>{{ errorMessage }}</span></div>
        <details class="request-preview"><summary>查看实际请求体</summary><pre>{{ JSON.stringify(requestPayload, null, 2) }}</pre></details>
        <div class="code-section"><div class="code-head"><span>外部调用示例</span><div><button v-for="lang in ['curl', 'javascript', 'python', 'java']" :key="lang" :class="{active: codeLanguage === lang}" @click="codeLanguage = lang as 'curl' | 'javascript' | 'python' | 'java'">{{ lang }}</button><button class="copy-button" @click="copyCode">复制</button></div></div><pre class="code-block">{{ displayCode }}</pre></div>
      </section>

      <section class="external-panel response-panel">
        <div class="external-panel-head"><div><span class="panel-kicker">03 / RESPONSE</span><h2>响应与事件</h2></div><span class="response-status" :class="statusClass"><i></i>{{ statusLabel }}<em v-if="responseStatus">HTTP {{ responseStatus }}</em></span></div>
        <div class="metrics"><div><span>总耗时</span><strong>{{ totalMs === null ? '—' : `${totalMs} ms` }}</strong></div><div><span>首事件</span><strong>{{ firstEventMs === null ? '—' : `${firstEventMs} ms` }}</strong></div><div><span>事件数</span><strong>{{ events.length }}</strong></div><div><span>Token</span><strong>{{ tokenSummary || '—' }}</strong></div><div><span>工具事件</span><strong>{{ toolCallCount || '—' }}</strong></div></div>
        <div class="answer-box"><div class="answer-head"><span>Agent 输出</span><div><a v-if="currentRunId" :href="`/platform/live/runs?run_id=${encodeURIComponent(currentRunId)}`" class="observe-link">查看运行观测</a><button :disabled="!responseAnswer && !responseRaw" @click="copyResponse">复制</button><button :disabled="!responseAnswer && !responseRaw" @click="downloadResponse">下载</button><span v-if="callMode === 'stream'" class="stream-label">LIVE STREAM</span></div></div><div v-if="responseAnswer" class="answer-text">{{ responseAnswer }}</div><div v-else class="answer-empty">发送请求后，Agent 的最终输出会显示在这里。</div></div>
        <div v-if="eventTypeSummary.length" class="event-summary"><span v-for="entry in eventTypeSummary" :key="entry[0]"><strong>{{ entry[0] }}</strong> × {{ entry[1] }}</span></div>
        <div class="output-tabs"><button :class="{active: outputTab === 'timeline'}" @click="outputTab = 'timeline'">事件时间线 <span>{{ events.length }}</span></button><button :class="{active: outputTab === 'raw'}" @click="outputTab = 'raw'">原始响应</button></div>
        <div v-if="outputTab === 'timeline'" class="event-list"><div v-if="!events.length" class="event-empty"><span>⌁</span><p>还没有事件</p><small>流式调用后可在这里检查 Agent / Tool / Token / 编排事件</small></div><div v-for="item in events" :key="`${item.sequence}-${item.id}`" class="event-row"><div class="event-index">{{ item.sequence }}</div><div class="event-main"><div class="event-head"><strong>{{ item.event }}</strong><code v-if="item.id">{{ item.id }}</code><time>+{{ item.elapsedMs }} ms · {{ item.receivedAt }}</time></div><pre>{{ formatData(item.parsed) }}</pre></div></div></div>
        <pre v-else class="raw-output">{{ responseRaw || events.map((item) => item.data).join('\n\n') || '还没有原始响应' }}</pre>
      </section>
    </div>
  </div>
</template>

<style scoped>
.external-workbench{flex:1;min-height:0;overflow:auto;padding:22px 24px 32px;background:linear-gradient(180deg,#f7f9fc 0%,#eef2f7 100%)}
.hidden-file-input{display:none}
.external-intro{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;max-width:1560px;margin:0 auto 18px}.external-eyebrow,.panel-kicker{font-size:10px;font-weight:800;letter-spacing:.12em;color:#2563eb}.external-intro h1{font-size:24px;letter-spacing:-.03em;margin:5px 0 5px}.external-intro p{font-size:13px;color:var(--muted);line-height:1.6}.external-security{display:flex;align-items:center;gap:9px;background:#f0fdf4;border:1px solid #bbf7d0;border-radius:10px;padding:9px 12px;color:#166534;flex-shrink:0}.security-mark{font-size:18px}.external-security strong,.external-security small{display:block}.external-security strong{font-size:12px}.external-security small{font-size:11px;margin-top:3px;color:#15803d}
.external-grid{max-width:1560px;margin:0 auto;display:grid;grid-template-columns:260px minmax(390px,1fr) minmax(350px,1fr);gap:14px;align-items:start}.external-panel{background:#fff;border:1px solid var(--border);border-radius:14px;box-shadow:var(--shadow-sm);min-width:0}.access-panel,.request-panel,.response-panel{padding:16px}.external-panel-head{display:flex;align-items:flex-start;justify-content:space-between;gap:10px;margin-bottom:16px}.external-panel-head h2{font-size:15px;margin-top:4px}.external-field{display:flex;flex-direction:column;gap:6px;margin-bottom:12px}.external-field label{font-size:11px;color:#475569;font-weight:800}.external-field label span{font-weight:500;color:#94a3b8;margin-left:4px}.external-field input,.external-field select,.external-field textarea{width:100%;font-size:12px}.external-field textarea{line-height:1.6;min-height:132px}.segmented,.mode-switch{display:flex;padding:3px;background:#f1f5f9;border-radius:8px;gap:3px}.segmented button,.mode-switch button,.output-tabs button{border:0;background:transparent;color:#64748b;font-size:11px;font-weight:700;border-radius:6px;padding:7px 9px}.segmented button{flex:1}.segmented button.active,.mode-switch button.active{background:#fff;color:#1d4ed8;box-shadow:var(--shadow-sm)}.field-hint{font-size:10px;color:#94a3b8;line-height:1.5;margin-top:-5px;margin-bottom:12px}.refresh-btn{width:100%;margin-bottom:14px}.inline-error,.request-error{border:1px solid #fecaca;background:#fff7f7;color:#b91c1c;border-radius:8px;padding:9px 10px;font-size:11px;line-height:1.5;margin-bottom:12px}.inline-error small{display:block;color:#dc2626;margin-top:3px}.agent-discovery{border-top:1px solid var(--border);padding-top:13px}.discovery-head{display:flex;justify-content:space-between;align-items:center;font-size:11px;color:#64748b;font-weight:700;margin-bottom:8px}.discovery-head strong{color:#1d4ed8}.agent-list{display:grid;gap:5px;max-height:210px;overflow:auto}.agent-option{display:flex;align-items:center;gap:8px;text-align:left;width:100%;border:1px solid transparent;border-radius:8px;background:#fff;padding:8px;color:var(--text)}.agent-option:hover{background:#f8fafc;border-color:var(--border)}.agent-option.active{background:#eff6ff;border-color:#bfdbfe}.agent-option strong,.agent-option small,.selected-agent strong,.selected-agent small{display:block}.agent-option strong,.selected-agent strong{font-size:11px}.agent-option small,.selected-agent small{font-size:10px;color:var(--muted);margin-top:2px}.agent-dot{display:inline-block;width:8px;height:8px;flex:0 0 8px;border-radius:50%;background:#22c55e;box-shadow:0 0 0 3px #dcfce7}.agent-empty{text-align:center;background:#f8fafc;border-radius:8px;padding:14px 8px;color:#94a3b8;font-size:11px;line-height:1.7}.agent-empty small{font-size:10px}.endpoint-hint{display:grid;gap:5px;border-top:1px solid var(--border);margin-top:14px;padding-top:12px}.endpoint-hint span{font-size:10px;color:#94a3b8}.endpoint-hint code,.request-tip,.event-head code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:10px;color:#475569;word-break:break-all}
.mode-switch{gap:2px}.mode-switch button{padding:6px 8px}.selected-agent{display:flex;align-items:center;gap:9px;margin:-4px 0 13px;padding:8px 10px;border:1px solid #dbeafe;background:#eff6ff;border-radius:8px}.external-form-grid{display:grid;grid-template-columns:1fr 1fr 1.25fr;gap:9px}.external-form-grid .external-field{min-width:0}.request-actions{display:flex;align-items:center;gap:7px;flex-wrap:wrap;margin-top:2px}.request-actions .request-tip{flex:1;min-width:180px;color:#94a3b8}.request-error{display:flex;gap:8px;align-items:baseline;margin-top:12px;margin-bottom:0}.request-error strong{white-space:nowrap}.request-preview{margin-top:13px;border-top:1px solid var(--border);padding-top:12px}.request-preview summary{font-size:11px;color:#64748b}.request-preview pre{margin-top:7px;background:#f8fafc;border-radius:8px;padding:9px;white-space:pre-wrap;font-size:10px;line-height:1.5;overflow:auto}.code-section{border-top:1px solid var(--border);margin-top:13px;padding-top:12px}.code-head{display:flex;align-items:center;justify-content:space-between;gap:8px;font-size:11px;color:#475569;font-weight:700}.code-head>div{display:flex;gap:3px}.code-head button{border:0;background:transparent;color:#94a3b8;padding:4px 6px;border-radius:5px;font-size:10px}.code-head button.active{background:#dbeafe;color:#1d4ed8}.code-head .copy-button{color:#2563eb;margin-left:4px}.code-block{background:#0f172a;color:#dbeafe;border-radius:8px;margin-top:7px;padding:11px;font:10px/1.55 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;white-space:pre-wrap;overflow:auto;max-height:185px;word-break:break-word}
.key-manager{border:1px solid #dbeafe;background:#f8fbff;border-radius:9px;padding:9px;margin:0 0 12px}.key-manager summary{font-size:11px;font-weight:800;color:#334155}.key-manager summary strong{float:right;color:#2563eb}.key-create{display:grid;grid-template-columns:1fr auto;gap:6px;margin-top:9px}.key-create input{min-width:0}.secret-once{display:grid;gap:6px;margin-top:8px;padding:8px;border:1px solid #fde68a;background:#fffbeb;border-radius:7px}.secret-once strong{font-size:9px;color:#92400e}.secret-once code{font-size:9px;word-break:break-all;color:#78350f}.key-list{display:grid;gap:5px;margin-top:8px;max-height:150px;overflow:auto}.key-row{display:flex;align-items:center;justify-content:space-between;gap:6px;padding:6px;background:#fff;border:1px solid #e2e8f0;border-radius:7px}.key-row strong,.key-row small{display:block}.key-row strong{font-size:10px}.key-row small{font-size:8px;color:#94a3b8;line-height:1.5;margin-top:2px}.key-row button{border:0;background:transparent;color:#dc2626;font-size:9px}.history-section{border-top:1px solid var(--border);margin-top:14px;padding-top:12px}.history-list{display:grid;gap:5px;max-height:260px;overflow:auto}.history-entry{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:3px}.history-item{display:flex;align-items:center;gap:8px;width:100%;padding:7px;border:1px solid var(--border);border-radius:8px;background:#fff;text-align:left;color:var(--text);min-width:0}.history-item:hover{background:#f8fafc}.history-item>span:nth-child(2){min-width:0}.history-item strong,.history-item small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.history-item strong{font-size:10px}.history-item small{font-size:9px;color:#94a3b8;margin-top:3px}.history-status{width:7px;height:7px;flex:0 0 7px;border-radius:50%;background:#f59e0b}.history-status.succeeded,.history-status.success{background:#22c55e}.history-status.failed,.history-status.error,.history-status.cancelled{background:#ef4444}.observe-link{color:#2563eb;font-size:9px;text-decoration:none;white-space:nowrap}.history-empty{padding:10px;border-radius:8px;background:#f8fafc;color:#94a3b8;text-align:center;font-size:10px;line-height:1.5}
.attachment-section{margin:-2px 0 12px;padding:10px;border:1px dashed #cbd5e1;border-radius:9px;background:#f8fafc}.attachment-head{display:flex;align-items:center;justify-content:space-between;gap:10px}.attachment-head strong,.attachment-head small{display:block}.attachment-head strong{font-size:11px;color:#475569}.attachment-head small{font-size:9px;color:#94a3b8;margin-top:3px}.attachment-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px;margin-top:9px}.attachment-chip{display:grid;grid-template-columns:28px minmax(0,1fr) 20px;align-items:center;gap:6px;border:1px solid #e2e8f0;border-radius:7px;background:#fff;padding:6px}.attachment-chip>span{display:grid;place-items:center;height:24px;border-radius:5px;background:#dbeafe;color:#1d4ed8;font-size:8px;font-weight:800}.attachment-chip strong,.attachment-chip small{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.attachment-chip strong{font-size:10px}.attachment-chip small{font-size:8px;color:#94a3b8;margin-top:2px}.attachment-chip button{border:0;background:transparent;color:#94a3b8;font-size:16px}.attachment-error{margin-top:7px;color:#b91c1c;font-size:10px}
.response-status{display:flex;align-items:center;gap:5px;font-size:11px;font-weight:700;color:#64748b;white-space:nowrap}.response-status i{width:7px;height:7px;border-radius:50%;background:#94a3b8}.response-status.running{color:#1d4ed8}.response-status.running i{background:#3b82f6;box-shadow:0 0 0 4px #dbeafe}.response-status.success{color:#15803d}.response-status.success i{background:#22c55e}.response-status.error{color:#b91c1c}.response-status.error i{background:#ef4444}.response-status.warning{color:#92400e}.response-status.warning i{background:#f59e0b}.response-status em{font-style:normal;color:#94a3b8;font-weight:500;margin-left:3px}.metrics{display:grid;grid-template-columns:repeat(5,1fr);gap:7px;margin-bottom:12px}.metrics>div{background:#f8fafc;border:1px solid #eef2f7;border-radius:8px;padding:9px}.metrics span{display:block;font-size:10px;color:#94a3b8}.metrics strong{display:block;font-size:14px;margin-top:4px;white-space:nowrap}.answer-box{border:1px solid #dbeafe;background:#f8fbff;border-radius:10px;min-height:145px;padding:11px 12px}.answer-head{display:flex;align-items:center;justify-content:space-between;font-size:11px;font-weight:800;color:#475569;margin-bottom:8px}.answer-head>div{display:flex;align-items:center;gap:5px}.answer-head button{border:0;background:transparent;color:#2563eb;font-size:9px}.answer-head button:disabled{color:#cbd5e1}.stream-label{font-size:9px;letter-spacing:.08em;color:#2563eb}.answer-text{font-size:13px;line-height:1.7;white-space:pre-wrap;word-break:break-word}.answer-empty{font-size:12px;color:#94a3b8;padding:24px 4px;text-align:center}.event-summary{display:flex;gap:5px;flex-wrap:wrap;margin-top:9px}.event-summary span{padding:4px 6px;border-radius:6px;background:#eef2ff;color:#6366f1;font-size:9px}.output-tabs{display:flex;gap:5px;border-bottom:1px solid var(--border);margin-top:14px;padding-bottom:5px}.output-tabs button{position:relative}.output-tabs button.active{background:#dbeafe;color:#1d4ed8}.output-tabs button span{display:inline-block;min-width:16px;padding:1px 4px;margin-left:3px;border-radius:99px;background:#e2e8f0;color:#64748b}.event-list{max-height:380px;overflow:auto;padding:12px 2px}.event-empty{text-align:center;color:#94a3b8;padding:48px 12px}.event-empty span{font-size:32px;color:#bfdbfe}.event-empty p{color:#64748b;font-size:12px;margin-top:8px}.event-empty small{display:block;margin-top:4px;font-size:10px}.event-row{display:grid;grid-template-columns:22px minmax(0,1fr);gap:8px;position:relative;padding-bottom:10px}.event-row:not(:last-child)::before{content:"";position:absolute;left:10px;top:20px;bottom:0;width:1px;background:#e2e8f0}.event-index{width:20px;height:20px;border-radius:50%;display:grid;place-items:center;background:#dbeafe;color:#1d4ed8;font-size:9px;font-weight:800;z-index:1}.event-main{border:1px solid var(--border);border-radius:8px;padding:8px;background:#fff;min-width:0}.event-head{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.event-head strong{font-size:11px;color:#334155}.event-head code{color:#64748b;max-width:100px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.event-head time{margin-left:auto;color:#94a3b8;font-size:10px}.event-main pre{white-space:pre-wrap;word-break:break-word;font:10px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;color:#64748b;margin-top:6px;max-height:110px;overflow:auto}.raw-output{margin-top:12px;min-height:380px;max-height:470px;overflow:auto;background:#0f172a;color:#dbeafe;border-radius:8px;padding:12px;font:10px/1.55 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;white-space:pre-wrap;word-break:break-word}
@media(max-width:1250px){.external-grid{grid-template-columns:240px minmax(360px,1fr)}.response-panel{grid-column:1 / -1}.event-list{max-height:320px}}@media(max-width:820px){.external-workbench{padding:16px}.external-intro{display:block}.external-security{margin-top:12px;width:max-content;max-width:100%}.external-grid{grid-template-columns:1fr}.response-panel{grid-column:auto}.external-form-grid{grid-template-columns:1fr 1fr}.external-form-grid .wide{grid-column:1 / -1}}@media(max-width:620px){.metrics{grid-template-columns:repeat(3,1fr)}.attachment-list{grid-template-columns:1fr}}@media(max-width:520px){.external-form-grid{grid-template-columns:1fr}.external-form-grid .wide{grid-column:auto}.external-panel-head{display:block}.external-panel-head .mode-switch{margin-top:10px;width:max-content}.request-actions .request-tip{flex-basis:100%}}
</style>
