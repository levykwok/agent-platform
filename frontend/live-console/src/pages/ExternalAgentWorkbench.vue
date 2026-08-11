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
  data: string
  parsed: unknown
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
const codeLanguage = ref<'curl' | 'javascript' | 'python'>('curl')

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
  tenant_id: tenantId.value.trim() || 'external',
  user_id: userId.value.trim() || 'external-user',
  session_id: sessionId.value.trim() || `session_${Date.now()}`,
  message: message.value,
}))
const endpoint = computed(() => `${normalizedBaseUrl()}/agents/${encodeURIComponent(agentId.value.trim() || 'researcher')}/chat${callMode.value === 'stream' ? '/stream' : ''}`)
const totalMs = computed(() => startedAt.value && finishedAt.value ? Math.max(0, finishedAt.value - startedAt.value) : null)
const firstEventMs = computed(() => startedAt.value && firstEventAt.value ? Math.max(0, firstEventAt.value - startedAt.value) : null)
const statusLabel = computed(() => ({ idle: '等待调用', loading: '调用中', success: '已完成', error: '调用失败', aborted: '已中止' }[requestStatus.value]))
const statusClass = computed(() => ({ idle: 'idle', loading: 'running', success: 'success', error: 'error', aborted: 'warning' }[requestStatus.value]))

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

function addEvent(event: string, id: string, data: string, raw = '') {
  if (firstEventAt.value === null) firstEventAt.value = Date.now()
  const parsed = parseData(data)
  events.value.push({ sequence: events.value.length + 1, event: event || 'message', id, receivedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }), data, parsed })
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
}

function finish(status: 'success' | 'error' | 'aborted') {
  finishedAt.value = Date.now()
  requestStatus.value = status
  sending.value = false
  abortController = null
  if (timeoutId) window.clearTimeout(timeoutId)
  timeoutId = undefined
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

onMounted(loadAgents)
onBeforeUnmount(() => {
  abortController?.abort()
  if (timeoutId) window.clearTimeout(timeoutId)
})
</script>

<template>
  <div class="external-workbench">
    <div class="external-intro">
      <div>
        <div class="external-eyebrow">EXTERNAL INTEGRATION</div>
        <h1>外部接入测试工作台</h1>
        <p>模拟外部系统通过稳定的 `/api/v1` 协议调用 Agent，快速确认鉴权、请求体、同步响应和 SSE 事件链路。</p>
      </div>
      <div class="external-security"><span class="security-mark">●</span><div><strong>Key 仅驻留当前页面</strong><small>不会写入 localStorage、后端或设计文档</small></div></div>
    </div>

    <div class="external-grid">
      <aside class="external-panel access-panel">
        <div class="external-panel-head"><div><span class="panel-kicker">01 / ACCESS</span><h2>接入配置</h2></div><span class="badge badge-blue">v1</span></div>
        <div class="external-field"><label for="external-base">API Base URL</label><input id="external-base" v-model="apiBaseUrl" spellcheck="false" placeholder="http://localhost:8080/api/v1"></div>
        <div class="external-field"><label>认证方式</label><div class="segmented"><button :class="{active: authMode === 'x-api-key'}" @click="authMode = 'x-api-key'">X-API-Key</button><button :class="{active: authMode === 'bearer'}" @click="authMode = 'bearer'">Bearer</button></div></div>
        <div class="external-field"><label for="external-key">API Key <span>必填</span></label><input id="external-key" v-model="apiKey" type="password" autocomplete="off" placeholder="输入测试 Key，不会保存"></div>
        <div class="field-hint">请求头：{{ authMode === 'bearer' ? 'Authorization: Bearer &lt;key&gt;' : 'X-API-Key: &lt;key&gt;' }}</div>
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
      </aside>

      <section class="external-panel request-panel">
        <div class="external-panel-head"><div><span class="panel-kicker">02 / REQUEST</span><h2>请求编辑器</h2></div><div class="mode-switch"><button :class="{active: callMode === 'stream'}" @click="callMode = 'stream'">流式 SSE</button><button :class="{active: callMode === 'sync'}" @click="callMode = 'sync'">同步 JSON</button></div></div>
        <div class="external-field"><label for="external-agent">Agent ID</label><input id="external-agent" v-model="agentId" list="external-agent-list" spellcheck="false" placeholder="researcher"><datalist id="external-agent-list"><option v-for="item in agents" :key="item.agentId" :value="item.agentId">{{ item.name }}</option></datalist></div>
        <div v-if="selectedAgent" class="selected-agent"><span class="agent-dot"></span><span><strong>{{ selectedAgent.name }}</strong><small>{{ selectedAgent.orchestration }} · {{ selectedAgent.capabilities.join(' / ') || 'chat' }}</small></span></div>
        <div class="external-form-grid">
          <div class="external-field"><label for="external-tenant">tenant_id</label><input id="external-tenant" v-model="tenantId"></div>
          <div class="external-field"><label for="external-user">user_id</label><input id="external-user" v-model="userId"></div>
          <div class="external-field wide"><label for="external-session">session_id</label><input id="external-session" v-model="sessionId"></div>
        </div>
        <div class="external-field message-field"><label for="external-message">message</label><textarea id="external-message" v-model="message" rows="7" placeholder="输入要交给外部 Agent 的问题"></textarea></div>
        <div class="request-actions"><button class="btn btn-primary" :disabled="sending" @click="sendRequest">{{ sending ? '调用中…' : `发送${callMode === 'stream' ? '流式' : '同步'}请求` }}</button><button v-if="sending" class="btn btn-danger" @click="stopRequest">停止</button><button class="btn btn-ghost" :disabled="sending" @click="clearOutput">清空结果</button><span class="request-tip">超时：60 秒 · {{ endpoint }}</span></div>
        <div v-if="errorMessage" class="request-error"><strong>{{ statusLabel }}</strong><span>{{ errorMessage }}</span></div>
        <details class="request-preview"><summary>查看实际请求体</summary><pre>{{ JSON.stringify(requestPayload, null, 2) }}</pre></details>
        <div class="code-section"><div class="code-head"><span>外部调用示例</span><div><button v-for="lang in ['curl', 'javascript', 'python']" :key="lang" :class="{active: codeLanguage === lang}" @click="codeLanguage = lang as 'curl' | 'javascript' | 'python'">{{ lang }}</button><button class="copy-button" @click="copyCode">复制</button></div></div><pre class="code-block">{{ displayCode }}</pre></div>
      </section>

      <section class="external-panel response-panel">
        <div class="external-panel-head"><div><span class="panel-kicker">03 / RESPONSE</span><h2>响应与事件</h2></div><span class="response-status" :class="statusClass"><i></i>{{ statusLabel }}<em v-if="responseStatus">HTTP {{ responseStatus }}</em></span></div>
        <div class="metrics"><div><span>总耗时</span><strong>{{ totalMs === null ? '—' : `${totalMs} ms` }}</strong></div><div><span>首事件</span><strong>{{ firstEventMs === null ? '—' : `${firstEventMs} ms` }}</strong></div><div><span>事件数</span><strong>{{ events.length }}</strong></div></div>
        <div class="answer-box"><div class="answer-head"><span>Agent 输出</span><span v-if="callMode === 'stream'" class="stream-label">LIVE STREAM</span></div><div v-if="responseAnswer" class="answer-text">{{ responseAnswer }}</div><div v-else class="answer-empty">发送请求后，Agent 的最终输出会显示在这里。</div></div>
        <div class="output-tabs"><button :class="{active: outputTab === 'timeline'}" @click="outputTab = 'timeline'">事件时间线 <span>{{ events.length }}</span></button><button :class="{active: outputTab === 'raw'}" @click="outputTab = 'raw'">原始响应</button></div>
        <div v-if="outputTab === 'timeline'" class="event-list"><div v-if="!events.length" class="event-empty"><span>⌁</span><p>还没有事件</p><small>流式调用后可在这里检查 event / id / data</small></div><div v-for="item in events" :key="`${item.sequence}-${item.id}`" class="event-row"><div class="event-index">{{ item.sequence }}</div><div class="event-main"><div class="event-head"><strong>{{ item.event }}</strong><code v-if="item.id">{{ item.id }}</code><time>{{ item.receivedAt }}</time></div><pre>{{ formatData(item.parsed) }}</pre></div></div></div>
        <pre v-else class="raw-output">{{ responseRaw || events.map((item) => item.data).join('\n\n') || '还没有原始响应' }}</pre>
      </section>
    </div>
  </div>
</template>

<style scoped>
.external-workbench{flex:1;min-height:0;overflow:auto;padding:22px 24px 32px;background:linear-gradient(180deg,#f7f9fc 0%,#eef2f7 100%)}
.external-intro{display:flex;align-items:flex-start;justify-content:space-between;gap:20px;max-width:1560px;margin:0 auto 18px}.external-eyebrow,.panel-kicker{font-size:10px;font-weight:800;letter-spacing:.12em;color:#2563eb}.external-intro h1{font-size:24px;letter-spacing:-.03em;margin:5px 0 5px}.external-intro p{font-size:13px;color:var(--muted);line-height:1.6}.external-security{display:flex;align-items:center;gap:9px;background:#f0fdf4;border:1px solid #bbf7d0;border-radius:10px;padding:9px 12px;color:#166534;flex-shrink:0}.security-mark{font-size:18px}.external-security strong,.external-security small{display:block}.external-security strong{font-size:12px}.external-security small{font-size:11px;margin-top:3px;color:#15803d}
.external-grid{max-width:1560px;margin:0 auto;display:grid;grid-template-columns:260px minmax(390px,1fr) minmax(350px,1fr);gap:14px;align-items:start}.external-panel{background:#fff;border:1px solid var(--border);border-radius:14px;box-shadow:var(--shadow-sm);min-width:0}.access-panel,.request-panel,.response-panel{padding:16px}.external-panel-head{display:flex;align-items:flex-start;justify-content:space-between;gap:10px;margin-bottom:16px}.external-panel-head h2{font-size:15px;margin-top:4px}.external-field{display:flex;flex-direction:column;gap:6px;margin-bottom:12px}.external-field label{font-size:11px;color:#475569;font-weight:800}.external-field label span{font-weight:500;color:#94a3b8;margin-left:4px}.external-field input,.external-field select,.external-field textarea{width:100%;font-size:12px}.external-field textarea{line-height:1.6;min-height:132px}.segmented,.mode-switch{display:flex;padding:3px;background:#f1f5f9;border-radius:8px;gap:3px}.segmented button,.mode-switch button,.output-tabs button{border:0;background:transparent;color:#64748b;font-size:11px;font-weight:700;border-radius:6px;padding:7px 9px}.segmented button{flex:1}.segmented button.active,.mode-switch button.active{background:#fff;color:#1d4ed8;box-shadow:var(--shadow-sm)}.field-hint{font-size:10px;color:#94a3b8;line-height:1.5;margin-top:-5px;margin-bottom:12px}.refresh-btn{width:100%;margin-bottom:14px}.inline-error,.request-error{border:1px solid #fecaca;background:#fff7f7;color:#b91c1c;border-radius:8px;padding:9px 10px;font-size:11px;line-height:1.5;margin-bottom:12px}.inline-error small{display:block;color:#dc2626;margin-top:3px}.agent-discovery{border-top:1px solid var(--border);padding-top:13px}.discovery-head{display:flex;justify-content:space-between;align-items:center;font-size:11px;color:#64748b;font-weight:700;margin-bottom:8px}.discovery-head strong{color:#1d4ed8}.agent-list{display:grid;gap:5px;max-height:210px;overflow:auto}.agent-option{display:flex;align-items:center;gap:8px;text-align:left;width:100%;border:1px solid transparent;border-radius:8px;background:#fff;padding:8px;color:var(--text)}.agent-option:hover{background:#f8fafc;border-color:var(--border)}.agent-option.active{background:#eff6ff;border-color:#bfdbfe}.agent-option strong,.agent-option small,.selected-agent strong,.selected-agent small{display:block}.agent-option strong,.selected-agent strong{font-size:11px}.agent-option small,.selected-agent small{font-size:10px;color:var(--muted);margin-top:2px}.agent-dot{display:inline-block;width:8px;height:8px;flex:0 0 8px;border-radius:50%;background:#22c55e;box-shadow:0 0 0 3px #dcfce7}.agent-empty{text-align:center;background:#f8fafc;border-radius:8px;padding:14px 8px;color:#94a3b8;font-size:11px;line-height:1.7}.agent-empty small{font-size:10px}.endpoint-hint{display:grid;gap:5px;border-top:1px solid var(--border);margin-top:14px;padding-top:12px}.endpoint-hint span{font-size:10px;color:#94a3b8}.endpoint-hint code,.request-tip,.event-head code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:10px;color:#475569;word-break:break-all}
.mode-switch{gap:2px}.mode-switch button{padding:6px 8px}.selected-agent{display:flex;align-items:center;gap:9px;margin:-4px 0 13px;padding:8px 10px;border:1px solid #dbeafe;background:#eff6ff;border-radius:8px}.external-form-grid{display:grid;grid-template-columns:1fr 1fr 1.25fr;gap:9px}.external-form-grid .external-field{min-width:0}.request-actions{display:flex;align-items:center;gap:7px;flex-wrap:wrap;margin-top:2px}.request-actions .request-tip{flex:1;min-width:180px;color:#94a3b8}.request-error{display:flex;gap:8px;align-items:baseline;margin-top:12px;margin-bottom:0}.request-error strong{white-space:nowrap}.request-preview{margin-top:13px;border-top:1px solid var(--border);padding-top:12px}.request-preview summary{font-size:11px;color:#64748b}.request-preview pre{margin-top:7px;background:#f8fafc;border-radius:8px;padding:9px;white-space:pre-wrap;font-size:10px;line-height:1.5;overflow:auto}.code-section{border-top:1px solid var(--border);margin-top:13px;padding-top:12px}.code-head{display:flex;align-items:center;justify-content:space-between;gap:8px;font-size:11px;color:#475569;font-weight:700}.code-head>div{display:flex;gap:3px}.code-head button{border:0;background:transparent;color:#94a3b8;padding:4px 6px;border-radius:5px;font-size:10px}.code-head button.active{background:#dbeafe;color:#1d4ed8}.code-head .copy-button{color:#2563eb;margin-left:4px}.code-block{background:#0f172a;color:#dbeafe;border-radius:8px;margin-top:7px;padding:11px;font:10px/1.55 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;white-space:pre-wrap;overflow:auto;max-height:185px;word-break:break-word}
.response-status{display:flex;align-items:center;gap:5px;font-size:11px;font-weight:700;color:#64748b;white-space:nowrap}.response-status i{width:7px;height:7px;border-radius:50%;background:#94a3b8}.response-status.running{color:#1d4ed8}.response-status.running i{background:#3b82f6;box-shadow:0 0 0 4px #dbeafe}.response-status.success{color:#15803d}.response-status.success i{background:#22c55e}.response-status.error{color:#b91c1c}.response-status.error i{background:#ef4444}.response-status.warning{color:#92400e}.response-status.warning i{background:#f59e0b}.response-status em{font-style:normal;color:#94a3b8;font-weight:500;margin-left:3px}.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:7px;margin-bottom:12px}.metrics>div{background:#f8fafc;border:1px solid #eef2f7;border-radius:8px;padding:9px}.metrics span{display:block;font-size:10px;color:#94a3b8}.metrics strong{display:block;font-size:16px;margin-top:4px}.answer-box{border:1px solid #dbeafe;background:#f8fbff;border-radius:10px;min-height:145px;padding:11px 12px}.answer-head{display:flex;align-items:center;justify-content:space-between;font-size:11px;font-weight:800;color:#475569;margin-bottom:8px}.stream-label{font-size:9px;letter-spacing:.08em;color:#2563eb}.answer-text{font-size:13px;line-height:1.7;white-space:pre-wrap;word-break:break-word}.answer-empty{font-size:12px;color:#94a3b8;padding:24px 4px;text-align:center}.output-tabs{display:flex;gap:5px;border-bottom:1px solid var(--border);margin-top:14px;padding-bottom:5px}.output-tabs button{position:relative}.output-tabs button.active{background:#dbeafe;color:#1d4ed8}.output-tabs button span{display:inline-block;min-width:16px;padding:1px 4px;margin-left:3px;border-radius:99px;background:#e2e8f0;color:#64748b}.event-list{max-height:380px;overflow:auto;padding:12px 2px}.event-empty{text-align:center;color:#94a3b8;padding:48px 12px}.event-empty span{font-size:32px;color:#bfdbfe}.event-empty p{color:#64748b;font-size:12px;margin-top:8px}.event-empty small{display:block;margin-top:4px;font-size:10px}.event-row{display:grid;grid-template-columns:22px minmax(0,1fr);gap:8px;position:relative;padding-bottom:10px}.event-row:not(:last-child)::before{content:"";position:absolute;left:10px;top:20px;bottom:0;width:1px;background:#e2e8f0}.event-index{width:20px;height:20px;border-radius:50%;display:grid;place-items:center;background:#dbeafe;color:#1d4ed8;font-size:9px;font-weight:800;z-index:1}.event-main{border:1px solid var(--border);border-radius:8px;padding:8px;background:#fff;min-width:0}.event-head{display:flex;align-items:center;gap:6px;flex-wrap:wrap}.event-head strong{font-size:11px;color:#334155}.event-head code{color:#64748b;max-width:100px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.event-head time{margin-left:auto;color:#94a3b8;font-size:10px}.event-main pre{white-space:pre-wrap;word-break:break-word;font:10px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;color:#64748b;margin-top:6px;max-height:110px;overflow:auto}.raw-output{margin-top:12px;min-height:380px;max-height:470px;overflow:auto;background:#0f172a;color:#dbeafe;border-radius:8px;padding:12px;font:10px/1.55 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;white-space:pre-wrap;word-break:break-word}
@media(max-width:1250px){.external-grid{grid-template-columns:240px minmax(360px,1fr)}.response-panel{grid-column:1 / -1}.event-list{max-height:320px}}@media(max-width:820px){.external-workbench{padding:16px}.external-intro{display:block}.external-security{margin-top:12px;width:max-content;max-width:100%}.external-grid{grid-template-columns:1fr}.response-panel{grid-column:auto}.external-form-grid{grid-template-columns:1fr 1fr}.external-form-grid .wide{grid-column:1 / -1}}@media(max-width:520px){.external-form-grid{grid-template-columns:1fr}.external-form-grid .wide{grid-column:auto}.external-panel-head{display:block}.external-panel-head .mode-switch{margin-top:10px;width:max-content}.request-actions .request-tip{flex-basis:100%}}
</style>
