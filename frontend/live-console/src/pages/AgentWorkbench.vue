<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import AgentWaitingCard from '../components/AgentWaitingCard.vue'
import { currentDomain, currentOrgId, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError } from '../stores/notify'

const agents = ref<JsonMap[]>([])
const selectedId = ref('')
const domainFilter = ref(currentDomain(''))
const keyword = ref('')
const domainOptions = ref<JsonMap[]>([{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }])
const sessions = ref<JsonMap[]>([])
const selectedSessionId = ref('')
const sessionDetail = ref<JsonMap | null>(null)
const sessionAssetTab = ref<'context' | 'log' | 'compaction' | 'tasks' | 'memory' | 'files'>('context')
const sessionPanelOpen = ref(false)
const messages = ref<JsonMap[]>([])
const query = ref('')
const imageInputs = ref<JsonMap[]>([])
const imageInputEl = ref<HTMLInputElement | null>(null)
type DocumentInput = {
  localId: string
  file: File
  status: 'uploading' | 'ready' | 'error'
  docId?: string
  error?: string
}
const documentInputs = ref<DocumentInput[]>([])
const documentInputEl = ref<HTMLInputElement | null>(null)
const running = ref(false)
const flowKey = ref('')
const msgsEl = ref<HTMLElement | null>(null)

const selected = computed(() => agents.value.find((a) => a.agent_id === selectedId.value) || null)
const visible = computed(() => agents.value.filter((a) => {
  if (domainFilter.value && String(a.domain || '') !== domainFilter.value) return false
  const q = keyword.value.trim().toLowerCase()
  if (!q) return true
  return [a.display_name, a.name, a.agent_id, a.description].join(' ').toLowerCase().includes(q)
}))
const flowKeys = computed<string[]>(() => {
  const wf = (selected.value?.flow_bindings || selected.value?.flows) as JsonMap | undefined
  return wf && typeof wf === 'object' && !Array.isArray(wf) ? Object.keys(wf) : []
})
const sessionRaw = computed(() => (sessionDetail.value?.raw || {}) as JsonMap)
const sessionFiles = computed(() => (sessionDetail.value?.files || {}) as JsonMap)
const sessionTasks = computed(() => sessionDetail.value?.tasks || {})
const sessionMemory = computed(() => (sessionDetail.value?.memory || {}) as JsonMap)
const contextEntryCount = computed(() => Array.isArray(sessionDetail.value?.context_entries) ? sessionDetail.value.context_entries.length : 0)
const logEntryCount = computed(() => Array.isArray(sessionDetail.value?.log_entries) ? sessionDetail.value.log_entries.length : 0)
const compactionEntries = computed(() => {
  const rows = [
    ...(Array.isArray(sessionDetail.value?.context_entries) ? sessionDetail.value.context_entries as JsonMap[] : []),
    ...(Array.isArray(sessionDetail.value?.log_entries) ? sessionDetail.value.log_entries as JsonMap[] : []),
  ]
  return rows.filter((row) => {
    const text = JSON.stringify(row).toLowerCase()
    return text.includes('compaction') || text.includes('__compaction_summary__') || String(row.type || '').toLowerCase() === 'summary'
  })
})
const compactionCount = computed(() => compactionEntries.value.length)
const documentsUploading = computed(() => documentInputs.value.some((item) => item.status === 'uploading'))

function headers(json = false) { return makeHeaders(json, currentOrgId()) }
function isBuiltin(a: JsonMap) { return String(a.source || 'builtin') === 'builtin' }
function normalizeMessage(m: JsonMap) {
  return { role: String(m.role || 'assistant'), content: String(m.content || m.text || '') }
}

async function loadDomains() {
  try {
    const status = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: headers(false) }))
    const raw = status.domains && typeof status.domains === 'object' ? status.domains as JsonMap : {}
    domainOptions.value = [{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }, ...Object.entries(raw).map(([d, s]) => ({ domain: d, label: String((s as JsonMap)?.display_name || d) })).filter((r) => r.domain !== 'platform')]
  } catch { /* ignore */ }
}
async function loadAgents() {
  try {
    const qs = domainFilter.value ? `?domain=${encodeURIComponent(domainFilter.value)}` : ''
    const d = await readJson<JsonMap>(await fetch(`/platform/frontend/agents${qs}`, { headers: headers(false) }))
    agents.value = (d.items || d.agents || []) as JsonMap[]
    if ((!selectedId.value || !agents.value.some((a) => a.agent_id === selectedId.value)) && agents.value[0]) selectAgent(String(agents.value[0].agent_id))
  } catch (err) { notifyError(err) }
}
async function loadSessions() {
  try {
    const p = new URLSearchParams()
    if (domainFilter.value) p.set('domain', domainFilter.value)
    if (selectedId.value) p.set('agent_id', selectedId.value)
    const qs = p.toString() ? `?${p}` : ''
    const d = await readJson<JsonMap>(await fetch(`/platform/frontend/chat/sessions${qs}`, { headers: headers(false) }))
    sessions.value = (d.items || d.sessions || []) as JsonMap[]
    if (!selectedSessionId.value && sessions.value[0]) await selectSession(String(sessions.value[0].session_id || sessions.value[0].id))
  } catch (err) { notifyError(err) }
}
async function createSession() {
  try {
    const title = selected.value ? `和 ${selected.value.display_name || selected.value.agent_id} 的对话` : '新对话'
    const d = await readJson<JsonMap>(await fetch('/platform/frontend/chat/sessions', { method: 'POST', headers: headers(true), body: JSON.stringify({ title, domain: currentDomain('platform'), agent_id: selectedId.value }) }))
    const row = (d.session || d.item || d) as JsonMap
    await loadSessions()
    await selectSession(String(row.session_id || row.id))
  } catch (err) { notifyError(err) }
}
async function selectSession(id: string) {
  selectedSessionId.value = id
  await loadSessionDetail(id, true)
}
async function loadSessionDetail(id: string, replaceMessages: boolean) {
  try {
    const qs = selectedId.value ? `?agent_id=${encodeURIComponent(selectedId.value)}` : ''
    const d = await readJson<JsonMap>(await fetch(`/platform/frontend/chat/sessions/${encodeURIComponent(id)}${qs}`, { headers: headers(false) }))
    sessionDetail.value = d
    if (replaceMessages) messages.value = ((d.messages || []) as JsonMap[]).map(normalizeMessage)
    await scrollDown()
  } catch (err) { notifyError(err) }
}
async function deleteSession(id: string) {
  try {
    const qs = selectedId.value ? `?agent_id=${encodeURIComponent(selectedId.value)}` : ''
    await readJson<JsonMap>(await fetch(`/platform/frontend/chat/sessions/${encodeURIComponent(id)}${qs}`, { method: 'DELETE', headers: headers(false) }))
    if (selectedSessionId.value === id) { selectedSessionId.value = ''; messages.value = []; sessionDetail.value = null }
    await loadSessions()
  } catch (err) { notifyError(err) }
}
async function selectAgent(id: string) {
  selectedId.value = id
  messages.value = []
  sessionDetail.value = null
  flowKey.value = ''
  selectedSessionId.value = ''
  await loadSessions()
}

async function scrollDown() { await nextTick(); if (msgsEl.value) msgsEl.value.scrollTop = msgsEl.value.scrollHeight }

function resultTextFromRun(run: JsonMap) {
  const out = (run.output_ref || {}) as JsonMap
  const res = (out.result || out) as JsonMap
  return String(res.answer || res.text || '').trim()
}

function normalizeEventType(ev: JsonMap) {
  return String(ev.step || ev.activity_type || ev.event_type || ev.type || '').trim().toLowerCase().replaceAll('.', '_')
}

function eventTitle(ev: JsonMap) {
  const type = normalizeEventType(ev)
  if (type === 'receive' || type === 'turn_received' || type === 'turn.received') return '接收问题'
  if (type === 'waiting_user_input') return '等待用户输入'
  if (type === 'waiting_resumed') return '已提交用户输入'
  if (type === 'waiting_rejected') return '用户取消等待'
  if (type === 'resume_continuation_scheduled') return '已排队继续执行'
  if (type === 'resume_continuation_started') return '继续执行'
  if (type === 'capability_loaded') return '加载能力'
  if (type === 'tool_call') return '工具调用'
  if (type === 'tool_call_start') return '工具调用开始'
  if (type === 'tool_call_delta') return '工具调用参数'
  if (type === 'tool_call_end') return '工具调用完成'
  if (type === 'tool_result') return '工具结果'
  if (type === 'tool_result_start') return '工具结果开始'
  if (type === 'tool_result_text_delta') return '工具结果输出'
  if (type === 'tool_result_data_delta') return '工具结果数据'
  if (type === 'tool_result_end') return '工具结果完成'
  if (type === 'memory_save') return '记忆保存'
  if (type === 'skill_call_start') return 'Skill 调用开始'
  if (type === 'skill_call_end') return 'Skill 调用完成'
  if (type === 'model_call_start') return '模型调用开始'
  if (type === 'model_call_end') return '模型调用完成'
  if (type === 'run_succeeded') return '运行完成'
  if (type === 'run_failed') return '运行失败'
  if (type === 'agent_end') return 'Agent 完成'
  return type || '事件'
}

function eventStatus(ev: JsonMap) {
  const type = normalizeEventType(ev)
  if (type.includes('failed') || type.includes('rejected') || type.includes('expired')) return 'error'
  if (type.includes('started') || type.includes('scheduled') || type === 'waiting_user_input') {
    return type === 'waiting_user_input' ? 'warning' : 'running'
  }
  return 'success'
}

function asString(value: unknown) { return value == null ? '' : String(value).trim() }

function asJsonMap(value: unknown): JsonMap {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as JsonMap) : {}
}

function eventSummary(ev: JsonMap) {
  const payload = (ev.summary || ev.detail || ev.payload || {}) as JsonMap
  const type = normalizeEventType(ev)
  const summary = asString(ev.summary)
  if (summary) return summary

  const toolObj = asJsonMap(payload.tool)
  const skillObj = asJsonMap(payload.skill)
  const toolCallName = asString(payload.tool_call_name || payload.tool_name || toolObj.tool_name || toolObj.name || toolObj.id || toolObj.toolId)
  const toolCallId = asString(payload.tool_call_id || payload.tool_id)
  const toolCallState = asString(payload.tool_call_state || payload.tool_result_state)
  const skillName = asString(
    payload.skill_call_name || payload.skill_name || skillObj.skill_name || skillObj.name || skillObj.id || skillObj.skillId,
  )
  const toolResultText = asString(payload.tool_result_text)
  const toolResultData = asString(payload.tool_result_data)
  const toolResultDataType = asString(payload.tool_result_data_type || 'text')
  if (toolCallName || toolCallId) {
    const name = toolCallName || '未知工具'
    const id = toolCallId ? `（${toolCallId}）` : ''
    const state = toolCallState ? ` [${toolCallState}]` : ''
    return `工具调用：${name}${id}${state}`
  }
  if (toolResultText) {
    const text = toolResultText.length > 90 ? toolResultText.slice(0, 90) + '…' : toolResultText
    return `工具结果：${text}`
  }
  if (toolResultData) {
    const text = toolResultData.length > 90 ? toolResultData.slice(0, 90) + '…' : toolResultData
    return `工具结果(${toolResultDataType})：${text}`
  }
  if (skillName) return `Skill 调用：${skillName}`
  const refText = eventRefText(ev)
  if (refText) return refText
  if (type === 'waiting_user_input') return `等待用户输入：${asString(payload.question || payload.waiting_id || payload.waiting_question)}`
  return asString(payload.tool_name || payload.waiting_id || payload.error)
}

function eventRefText(ev: JsonMap) {
  const refs = (ev.refs || {}) as JsonMap
  if (!refs || typeof refs !== 'object') return ''
  const parts: string[] = []
  const tool = asString(refs.tool_name || refs.tool_call_name)
  const skill = asString(refs.skill_name || refs.skill_id)
  const toolCall = asString(refs.tool_call_id)
  const state = asString(refs.tool_call_state || refs.tool_result_state)
  if (tool) parts.push(`tool=${tool}`)
  if (toolCall) parts.push(`call=${toolCall}`)
  if (skill) parts.push(`skill=${skill}`)
  if (state) parts.push(`state=${state}`)
  return parts.join(' | ')
}

function eventRaw(ev: JsonMap) {
  const detail = (ev.detail || ev.payload) as JsonMap
  const src = detail && typeof detail === 'object' ? detail : null
  if (!src) return ''
  return JSON.stringify(src, null, 2)
}

function usefulEvent(ev: JsonMap) {
  const type = normalizeEventType(ev)
  const payload = ((ev.detail || ev.payload || {}) as JsonMap)
  if (type === 'activity' || type === 'researcher' || type.startsWith('researcher')) {
    const keys = Object.keys(payload).filter((key) => !['agent_id', 'reply_id', 'runtime'].includes(key))
    return keys.length > 0 || !!asString(ev.summary)
  }
  if (type.startsWith('tool_') || type.startsWith('skill_')) return true
  if (type === 'capability_loaded' || type.startsWith('workflow_') || type === 'router_decision' || type === 'single_agent_start') return true
  const keys = Object.keys(payload).filter((key) => !['agent_id', 'reply_id', 'runtime'].includes(key))
  if (keys.length) return true
  const summary = asString(ev.summary)
  return !!summary && summary !== asString(payload.agent_id)
}

function stepKey(ev: JsonMap) {
  const type = normalizeEventType(ev)
  const payload = ((ev.detail || ev.payload || {}) as JsonMap)
  if (type.startsWith('tool_call_') || type.startsWith('tool_result_')) {
    const callId = asString(payload.tool_call_id || payload.tool_id)
    const toolName = asString(payload.tool_call_name || payload.tool_name)
    return `tool:${callId || toolName || type}`
  }
  const id = asString(ev.event_id || ev.id)
  return id || `${type}:${asString(ev.title)}:${eventSummary(ev)}`
}

function stepFromEvent(ev: JsonMap) {
  const payload = (ev.payload || ev.detail || {}) as JsonMap
  const type = normalizeEventType(ev)
  const isTool = type.startsWith('tool_call_') || type.startsWith('tool_result_')
  const toolName = asString(payload.tool_call_name || payload.tool_name)
  const toolArgDelta = asString(payload.tool_call_delta)
  const toolResultText = asString(payload.tool_result_text)
  const toolResultData = asString(payload.tool_result_data)
  const resultState = asString(payload.tool_result_state)
  const callState = asString(payload.tool_call_state)
  const status = resultState === 'SUCCESS' || type === 'tool_result_end'
    ? 'success'
    : type.includes('error') || type.includes('fail')
      ? 'error'
      : isTool
        ? 'running'
        : eventStatus(ev)
  return {
    event_id: String(ev.event_id || ev.id || ''),
    group_key: stepKey(ev),
    step: ev.event_type || ev.type || ev.step,
    title: isTool && toolName ? `工具调用：${toolName}` : eventTitle(ev),
    status,
    summary: eventSummary(ev),
    detail: payload,
    refs: ev.refs || {},
    refText: eventRefText(ev),
    raw: eventRaw(ev),
    tool_state: resultState || callState,
    tool_arg_delta: toolArgDelta,
    tool_result_preview: toolResultText || toolResultData,
  }
}

function mergeToolSummary(prev: JsonMap, row: JsonMap) {
  const title = asString(row.title || prev.title)
  const arg = asString(row.tool_arg_delta || prev.tool_arg_delta)
  const result = asString(row.tool_result_preview || prev.tool_result_preview)
  const state = asString(row.tool_state || prev.tool_state)
  const parts: string[] = []
  if (arg) parts.push(`参数：${arg.length > 120 ? arg.slice(0, 120) + '…' : arg}`)
  if (result) parts.push(`结果：${result.length > 120 ? result.slice(0, 120) + '…' : result}`)
  else if (state) parts.push(`状态：${state}`)
  return parts.length ? parts.join(' · ') : asString(row.summary || prev.summary || title)
}

function upsertStep(msg: JsonMap, row: JsonMap) {
  const key = String(row.group_key || row.event_id || '')
  const idx = key ? (msg.steps || []).findIndex((s: JsonMap) => String(s.group_key || s.event_id || '') === key) : -1
  if (idx >= 0) {
    const prev = msg.steps[idx] as JsonMap
    const merged = {
      ...prev,
      ...row,
      detail: { ...((prev.detail || {}) as JsonMap), ...((row.detail || {}) as JsonMap) },
      refs: { ...((prev.refs || {}) as JsonMap), ...((row.refs || {}) as JsonMap) },
      tool_arg_delta: asString(row.tool_arg_delta) || prev.tool_arg_delta,
      tool_result_preview: asString(row.tool_result_preview) || prev.tool_result_preview,
      raw: asString(row.raw) || prev.raw,
    }
    msg.steps[idx] = {
      ...merged,
      summary: String(row.group_key || '').startsWith('tool:') ? mergeToolSummary(prev, merged) : asString(row.summary) || prev.summary,
    }
  } else {
    msg.steps.push(row)
  }
}

function appendEventSteps(msg: JsonMap, events: JsonMap[]) {
  for (const ev of events) {
    if (!usefulEvent(ev)) continue
    upsertStep(msg, stepFromEvent(ev))
  }
}

async function fetchRunEvents(runId: string, afterId?: number) {
  const p = new URLSearchParams({ limit: '200' })
  if (afterId && afterId > 0) p.set('after_id', String(afterId))
  return await readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(runId)}/events?${p}`, { headers: headers(false) }))
}

async function refreshRunMessage(msg: JsonMap) {
  const runId = String(msg.meta?.run_id || '')
  if (!runId) return null
  const afterId = Number((msg.meta || {}).events_after_id || 0)
  const [run, waiting, eventPage] = await Promise.all([
    readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(runId)}`, { headers: headers(false) })),
    readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(runId)}/waiting`, { headers: headers(false) })).catch(() => ({ item: null })),
    fetchRunEvents(runId, afterId).catch((): JsonMap => ({ events: [], items: [], next_after_id: afterId, waiting: null })),
  ])
  const detail = (run.run || run) as JsonMap
  const events = Array.isArray(eventPage.events) ? eventPage.events : Array.isArray(eventPage.items) ? eventPage.items : []
  appendEventSteps(msg, events)
  msg.meta = { ...(msg.meta || {}), events_after_id: Number(eventPage.next_after_id || afterId || 0) }
  msg.waiting = waiting.item || eventPage.waiting || null
  if (detail.status === 'succeeded') {
    msg.content = resultTextFromRun(detail) || msg.content || '已完成。'
    msg.pending = false
    msg.waiting = null
  } else if (['failed', 'cancelled', 'canceled'].includes(String(detail.status || ''))) {
    msg.content = detail.error?.message ? `执行失败：${detail.error.message}` : msg.content || '执行失败。'
    msg.meta = { ...(msg.meta || {}), error: true }
    msg.pending = false
  } else if (detail.status === 'waiting_user_input') {
    msg.pending = false
  } else {
    msg.pending = true
  }
  await scrollDown()
  return detail
}

async function pollRunUntilSettled(msg: JsonMap) {
  for (let i = 0; i < 60; i += 1) {
    const detail = await refreshRunMessage(msg)
    const status = String(detail?.status || '')
    if (['succeeded', 'failed', 'cancelled', 'canceled', 'waiting_user_input'].includes(status)) return
    await new Promise((resolve) => setTimeout(resolve, 1500))
  }
  msg.pending = false
}

async function onWaitingResumed(msg: JsonMap) {
  msg.waiting = null
  msg.pending = true
  msg.content = '已提交，继续执行中...'
  await pollRunUntilSettled(msg)
}

async function onWaitingRejected(msg: JsonMap) {
  msg.waiting = null
  msg.pending = false
  msg.content = '已取消本次等待。'
  await refreshRunMessage(msg)
}

async function send() {
  const text = query.value.trim()
  if ((!text && !imageInputs.value.length && !documentInputs.value.length) || running.value || documentsUploading.value || !selectedId.value) return
  if (!selectedSessionId.value) await createSession()
  const documents = documentInputs.value.filter((item) => item.status === 'ready' && item.docId)
  messages.value.push({ role: 'user', content: text || '（附件）', image_count: imageInputs.value.length, document_count: documents.length, documents: documents.map((item) => item.file.name) })
  messages.value.push({ role: 'assistant', content: '', steps: [], meta: null, pending: true })
  const msg = messages.value[messages.value.length - 1]
  query.value = ''
  const images = imageInputs.value
  imageInputs.value = []
  documentInputs.value = []
  running.value = true
  await scrollDown()
  const t0 = Date.now()
  const handle = (ev: JsonMap) => {
    if (ev.type === 'activity') {
      if (usefulEvent(ev as JsonMap)) upsertStep(msg, stepFromEvent(ev as JsonMap))
      scrollDown()
    } else if (ev.type === 'token') {
      msg.content += String(ev.delta || '')
      scrollDown()
    } else if (ev.type === 'waiting_user_input') {
      msg.waiting = ev.waiting || ev
      msg.pending = false
      msg.content = ''
      msg.meta = { ...(msg.meta || {}), run_id: ev.run_id, status: 'waiting_user_input' }
      msg.steps.push({ step: 'waiting_user_input', title: '等待用户输入', status: 'warning', summary: String((ev.waiting || ev).question || '') })
      scrollDown()
    } else if (ev.type === 'done') {
      const out = (ev.output_ref || {}) as JsonMap
      const res = (out.result || out) as JsonMap
      msg.content = ev.status === 'waiting_user_input' ? '' : String(res.answer || res.text || msg.content || '').trim() || '（无回答）'
      msg.meta = { route: res.route || res.effective_mode || ev.flow_name, trace_id: ev.trace_id, citations: Array.isArray(res.citations) ? res.citations : [], elapsed: Date.now() - t0, run_id: ev.run_id }
      msg.pending = ev.status !== 'waiting_user_input' ? false : msg.pending
      if (ev.status === 'waiting_user_input' && out.waiting_user_input) msg.waiting = out.waiting_user_input
    } else if (ev.type === 'error') {
      msg.content = `出错：${ev.message || ev.error || '执行失败'}`
      msg.meta = { error: true }
      msg.pending = false
    }
  }
  try {
    const documentIds = documents.map((item) => item.docId as string)
    const body: JsonMap = { agent_id: selectedId.value, session_id: selectedSessionId.value, input_type: 'chat', payload: { query: text, images, document_ids: documentIds }, context: {}, artifacts: [] }
    if (flowKey.value) body.flow_name = flowKey.value
    const resp = await fetch('/agent-runs/run/stream', { method: 'POST', headers: headers(true), body: JSON.stringify(body) })
    if (!resp.ok || !resp.body) throw new Error(`HTTP ${resp.status}`)
    const reader = resp.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const parts = buf.split('\n\n')
      buf = parts.pop() || ''
      for (const part of parts) {
        const line = part.split('\n').find((l) => l.startsWith('data:'))
        if (!line) continue
        try { handle(JSON.parse(line.slice(5).trim())) } catch { /* ignore */ }
      }
    }
    if (msg.pending) { msg.content = msg.content || '（无回答）'; msg.pending = false }
  } catch (err) {
    msg.content = `请求失败：${err instanceof Error ? err.message : String(err)}`
    msg.meta = { error: true }
    msg.pending = false
    notifyError(err)
  } finally {
    running.value = false
    await loadSessions()
    if (selectedSessionId.value) await loadSessionDetail(selectedSessionId.value, false)
    await scrollDown()
  }
}

function addImage(ev: Event) {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  for (const file of files) {
    if (!file.type.startsWith('image/')) {
      notifyError(`${file.name} 不是图片`)
      continue
    }
    const reader = new FileReader()
    reader.onload = () => {
      const url = String(reader.result || '')
      const marker = url.indexOf(',')
      if (marker < 0) return
      imageInputs.value.push({ data: url.slice(marker + 1), media_type: file.type })
    }
    reader.readAsDataURL(file)
  }
}

async function addDocuments(ev: Event) {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  for (const file of files) {
    const extension = file.name.split('.').pop()?.toLowerCase() || ''
    if (!['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'md', 'markdown', 'txt', 'csv'].includes(extension)) {
      notifyError(`${file.name} 不是支持的文档类型`)
      continue
    }
    const item: DocumentInput = { localId: `${Date.now()}_${Math.random().toString(36).slice(2)}`, file, status: 'uploading' }
    documentInputs.value.push(item)
    try {
      if (!selectedSessionId.value) await createSession()
      const form = new FormData()
      form.set('file', file)
      form.set('session_id', selectedSessionId.value)
      form.set('domain', currentDomain('platform'))
      const data = await readJson<JsonMap>(await fetch('/platform/session/attachments', { method: 'POST', headers: headers(false), body: form }))
      const attached = (data.item || data) as JsonMap
      const docId = String(attached.doc_id || '')
      if (!docId) throw new Error('上传后没有生成文档标识')
      if (String(attached.parse_status || '') !== 'parsed') throw new Error('未提取到原生文本，OCR 版本上线后可处理扫描件')
      item.docId = docId
      item.status = 'ready'
    } catch (err) {
      item.status = 'error'
      item.error = err instanceof Error ? err.message : String(err)
      notifyError(`${file.name} 上传失败：${item.error}`)
    }
  }
}

function removeDocument(localId: string) {
  documentInputs.value = documentInputs.value.filter((item) => item.localId !== localId)
}

onMounted(async () => { await loadDomains(); await loadAgents(); await loadSessions() })
</script>

<template>
  <section class="agent-admin">
    <aside class="left-pane">
      <div class="pane-head"><h2>选择 Agent</h2></div>
      <div class="wb-filters">
        <select v-model="domainFilter" @change="loadAgents"><option v-for="d in domainOptions" :key="d.domain as string" :value="d.domain">{{ d.label }}</option></select>
        <input v-model="keyword" placeholder="搜索 agent 名称 / ID…" />
      </div>
      <div class="entity-list">
        <div v-for="a in visible" :key="a.agent_id" class="entity-item" :class="{ selected: selectedId === a.agent_id }" @click="selectAgent(String(a.agent_id))">
          <div class="entity-name">{{ a.display_name || a.name || a.agent_id }}</div>
          <span class="entity-type">{{ a.domain || 'platform' }}</span>
          <span class="badge" :class="isBuiltin(a) ? 'badge-builtin' : 'badge-db'">{{ isBuiltin(a) ? '内置' : '自定义' }}</span>
        </div>
        <div v-if="!visible.length" class="empty">暂无 Agent</div>
      </div>
      <div class="session-panel">
        <div class="session-head">
          <h3>会话</h3>
          <button class="btn btn-primary btn-sm" @click="createSession">新建</button>
        </div>
        <div class="session-list">
          <div v-for="s in sessions" :key="s.session_id || s.id" class="session-item" :class="{ selected: selectedSessionId === String(s.session_id || s.id) }" @click="selectSession(String(s.session_id || s.id))">
            <div class="session-title">{{ s.title || '新对话' }}</div>
            <button class="session-del" title="删除会话" @click.stop="deleteSession(String(s.session_id || s.id))">×</button>
          </div>
          <div v-if="!sessions.length" class="empty small">暂无会话</div>
        </div>
      </div>
    </aside>

    <main class="wb-main">
      <div v-if="selected" class="wb-bar">
        <div class="wb-icon">🤖</div>
        <div class="wb-agent">
          <strong>{{ selected.display_name || selected.name || selected.agent_id }}</strong>
          <span>{{ selected.agent_id }}</span>
          <span v-if="selectedSessionId">session: {{ selectedSessionId }}</span>
        </div>
        <div class="wb-bar-right">
          <button class="btn btn-ghost btn-sm" :disabled="!selectedSessionId" @click="sessionPanelOpen = true">会话详情</button>
          <select v-if="flowKeys.length" v-model="flowKey" class="wb-flow"><option value="">自动路由 / 默认 Flow</option><option v-for="k in flowKeys" :key="k" :value="k">{{ k }}</option></select>
          <button class="btn btn-ghost btn-sm" :disabled="!messages.length" @click="messages = []">清空对话</button>
        </div>
      </div>

      <div ref="msgsEl" class="wb-msgs">
        <div v-if="!messages.length" class="wb-empty">
          <div class="wb-empty-icon">💬</div>
          <h3>{{ selected ? '开始与 ' + (selected.display_name || selected.agent_id) + ' 对话' : '选择左侧 Agent' }}</h3>
          <p>这里用你配置好的 Agent 正式对话（按其 Flow / 工具 / 技能 / 模型策略执行）。</p>
        </div>
        <div v-for="(m, i) in messages" :key="i" class="msg-row" :class="m.role">
          <div class="msg-avatar" :class="m.role === 'user' ? 'user' : 'ai'">{{ m.role === 'user' ? '你' : 'AI' }}</div>
          <div class="bubble-wrap">
            <details v-if="m.role !== 'user' && (m.steps || []).length" class="wb-steps" :open="m.pending">
              <summary>执行过程 · {{ m.steps.length }} 步</summary>
              <div v-for="(st, si) in m.steps" :key="si" class="wb-step" :class="st.status">
                <span class="wb-step-dot"></span>
                <div class="wb-step-ct">
                  <div class="wb-step-t">{{ st.title }}</div>
                  <span v-if="st.summary" class="wb-step-s">{{ st.summary }}</span>
                  <span v-if="st.refText" class="wb-step-meta">{{ st.refText }}</span>
                  <details v-if="st.raw" class="wb-step-raw">
                    <summary>明细</summary>
                    <pre>{{ st.raw }}</pre>
                  </details>
                </div>
              </div>
            </details>
            <div v-if="m.role !== 'user' || m.content" class="bubble" :class="[m.role === 'user' ? 'user' : 'ai', m.meta?.error ? 'err' : '', m.pending && !m.content ? 'streaming' : '']">{{ m.content || (m.pending ? (m.steps && m.steps.length ? '生成回答中…' : '运行中…') : '') }}</div>
            <AgentWaitingCard v-if="m.waiting && m.meta?.run_id" :run-id="String(m.meta.run_id)" :waiting="m.waiting" @resumed="onWaitingResumed(m)" @rejected="onWaitingRejected(m)" />
            <div v-if="m.meta && !m.meta.error" class="msg-meta">
              <span v-if="m.meta.route">route: {{ m.meta.route }}</span>
              <span v-if="m.meta.elapsed">{{ m.meta.elapsed }} ms</span>
              <span v-if="(m.meta.citations || []).length">{{ m.meta.citations.length }} 引用</span>
            </div>
            <div v-if="m.role === 'user' && (m.documents || []).length" class="sources attached-documents">
              <span v-for="(name, di) in m.documents" :key="di" class="source-chip">📄 {{ name }}</span>
            </div>
            <div v-if="(m.meta?.citations || []).length" class="sources">
              <span v-for="(c, ci) in m.meta.citations.slice(0, 5)" :key="ci" class="source-chip">📄 {{ c.filename || c.doc_id || c.title || '来源' }}</span>
            </div>
          </div>
        </div>
      </div>

      <div class="wb-composer">
        <div v-if="documentInputs.length" class="document-queue">
          <span v-for="item in documentInputs" :key="item.localId" class="document-chip" :class="item.status">
            <span>{{ item.status === 'uploading' ? '⏳' : item.status === 'ready' ? '📄' : '⚠️' }}</span>
            <span class="document-chip-name">{{ item.file.name }}</span>
            <span class="document-chip-status">{{ item.status === 'uploading' ? '上传解析中' : item.status === 'ready' ? '本轮会检索' : item.error || '上传失败' }}</span>
            <button type="button" title="移除此文件" @click="removeDocument(item.localId)">×</button>
          </span>
        </div>
        <input ref="imageInputEl" type="file" accept="image/*" multiple class="hidden" @change="addImage" />
        <input ref="documentInputEl" type="file" accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.md,.markdown,.txt,.csv" multiple class="hidden" @change="addDocuments" />
        <button class="btn btn-ghost" :disabled="running || !selectedId" @click="imageInputEl?.click()">图片{{ imageInputs.length ? ` (${imageInputs.length})` : '' }}</button>
        <button class="btn btn-ghost" :disabled="running || !selectedId" @click="documentInputEl?.click()">文档{{ documentInputs.length ? ` (${documentInputs.length})` : '' }}</button>
        <textarea v-model="query" :disabled="!selectedId" rows="1" placeholder="输入消息，Enter 发送，Shift+Enter 换行…" @keydown.enter.exact.prevent="send" />
        <button class="btn btn-primary" :disabled="running || documentsUploading || (!query.trim() && !imageInputs.length && !documentInputs.length) || !selectedId" @click="send">{{ documentsUploading ? '文档解析中…' : running ? '运行中…' : '发送' }}</button>
      </div>
    </main>

    <div v-if="sessionPanelOpen" class="session-drawer-mask" @click="sessionPanelOpen = false"></div>
    <aside v-if="sessionPanelOpen" class="session-drawer">
      <div class="drawer-head">
        <div>
          <h3>会话详情</h3>
          <p>AgentScope workspace 里的 context / log / tasks / memory。</p>
        </div>
        <button class="drawer-close" @click="sessionPanelOpen = false">×</button>
      </div>
      <div class="drawer-session">
        <span>session</span>
        <strong>{{ selectedSessionId || '未选择' }}</strong>
      </div>
      <div class="asset-tabs">
        <button :class="{ active: sessionAssetTab === 'context' }" @click="sessionAssetTab = 'context'">上下文 {{ contextEntryCount }}</button>
        <button :class="{ active: sessionAssetTab === 'log' }" @click="sessionAssetTab = 'log'">日志 {{ logEntryCount }}</button>
        <button :class="{ active: sessionAssetTab === 'compaction' }" @click="sessionAssetTab = 'compaction'">压缩 {{ compactionCount }}</button>
        <button :class="{ active: sessionAssetTab === 'tasks' }" @click="sessionAssetTab = 'tasks'">任务</button>
        <button :class="{ active: sessionAssetTab === 'memory' }" @click="sessionAssetTab = 'memory'">记忆</button>
        <button :class="{ active: sessionAssetTab === 'files' }" @click="sessionAssetTab = 'files'">文件</button>
      </div>
      <pre v-if="sessionAssetTab === 'context'" class="asset-raw">{{ sessionRaw.context || '暂无 context jsonl。' }}</pre>
      <pre v-else-if="sessionAssetTab === 'log'" class="asset-raw">{{ sessionRaw.log || '暂无 log jsonl。' }}</pre>
      <pre v-else-if="sessionAssetTab === 'compaction'" class="asset-raw">{{ compactionEntries.length ? JSON.stringify(compactionEntries, null, 2) : '暂无上下文压缩记录。默认需要达到消息数或 token 阈值才会触发。' }}</pre>
      <pre v-else-if="sessionAssetTab === 'tasks'" class="asset-raw">{{ JSON.stringify(sessionTasks, null, 2) }}</pre>
      <pre v-else-if="sessionAssetTab === 'memory'" class="asset-raw">{{ sessionMemory.memory_md || '暂无 MEMORY.md。' }}</pre>
      <pre v-else class="asset-raw">{{ JSON.stringify(sessionFiles, null, 2) }}</pre>
    </aside>
  </section>
</template>

<style scoped>
.wb-filters { padding: 10px 12px; border-bottom: 1px solid var(--border); display: grid; gap: 7px; }
.wb-filters select, .wb-filters input { height: 34px; width: 100%; }
.session-panel { border-top: 1px solid var(--border); padding: 12px; display: flex; flex-direction: column; gap: 10px; min-height: 190px; }
.session-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.session-head h3 { font-size: 13px; font-weight: 800; color: var(--text); }
.session-list { display: flex; flex-direction: column; gap: 6px; overflow-y: auto; }
.session-item { display: flex; align-items: center; gap: 8px; padding: 8px 9px; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; cursor: pointer; transition: border-color .16s, background .16s; }
.session-item:hover { border-color: #bfdbfe; background: #f8fbff; }
.session-item.selected { border-color: #2563eb; background: #eff6ff; }
.session-title { flex: 1; min-width: 0; font-size: 12px; font-weight: 700; color: #334155; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.session-del { border: 0; background: transparent; color: #94a3b8; cursor: pointer; font-size: 18px; line-height: 1; }
.session-del:hover { color: #dc2626; }
.empty.small { padding: 14px; font-size: 12px; }
.wb-main { display: flex; flex-direction: column; overflow: hidden; min-height: 0; }
.document-queue { flex-basis: 100%; display: flex; gap: 6px; flex-wrap: wrap; padding: 0 2px 2px; }
.document-chip { display: inline-flex; align-items: center; gap: 5px; max-width: 100%; padding: 5px 7px; border: 1px solid #bfdbfe; border-radius: 8px; background: #eff6ff; color: #1e3a5f; font-size: 11px; }
.document-chip.uploading { border-color: #fde68a; background: #fffbeb; color: #92400e; }
.document-chip.error { border-color: #fecaca; background: #fef2f2; color: #991b1b; }
.document-chip-name { max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 700; }
.document-chip-status { color: inherit; opacity: .74; }
.document-chip button { border: 0; background: transparent; padding: 0 1px; color: inherit; font-size: 16px; line-height: 1; cursor: pointer; }
.attached-documents { margin-top: 5px; }
.wb-bar { display: flex; align-items: center; gap: 12px; padding: 12px 18px; border-bottom: 1px solid var(--border); background: #fff; flex-shrink: 0; }
.wb-icon { width: 38px; height: 38px; border-radius: 10px; background: linear-gradient(135deg, var(--blue), #818cf8); display: flex; align-items: center; justify-content: center; font-size: 18px; flex-shrink: 0; }
.wb-agent { display: flex; flex-direction: column; min-width: 0; }
.wb-agent strong { font-size: 14px; font-weight: 700; }
.wb-agent span { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; }
.wb-bar-right { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.wb-flow { height: 32px; font-size: 12px; max-width: 220px; }
.wb-msgs { flex: 1; overflow-y: auto; padding: 22px 24px; display: flex; flex-direction: column; gap: 16px; background: linear-gradient(180deg, #f7f9fc, #eef2f8); }
.wb-empty { margin: auto; text-align: center; color: #94a3b8; max-width: 420px; }
.wb-empty-icon { font-size: 42px; margin-bottom: 8px; }
.wb-empty h3 { font-size: 16px; font-weight: 700; color: var(--text); margin-bottom: 6px; }
.wb-empty p { font-size: 13px; line-height: 1.7; }
.bubble.err { background: #fef2f2; border-color: #fecaca; color: #991b1b; }
.msg-meta { display: flex; gap: 10px; flex-wrap: wrap; font-size: 11px; color: var(--muted); margin-top: 4px; }
.wb-steps { margin-bottom: 6px; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; padding: 6px 10px; max-width: 600px; }
.wb-steps summary { font-size: 11px; font-weight: 600; color: var(--muted); cursor: pointer; }
.wb-step { display: flex; align-items: center; gap: 7px; font-size: 12px; padding: 4px 0; color: #334155; }
.wb-step-dot { width: 8px; height: 8px; border-radius: 50%; background: #cbd5e1; flex-shrink: 0; }
.wb-step.success .wb-step-dot { background: var(--green); }
.wb-step.skipped .wb-step-dot { background: #cbd5e1; }
.wb-step.error .wb-step-dot, .wb-step.failed .wb-step-dot { background: var(--red); }
.wb-step-ct { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.wb-step-t { font-weight: 600; }
.wb-step-s { color: var(--muted); font-size: 11px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.wb-step-meta { color: #64748b; font-size: 10px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.wb-step-raw { margin-top: 2px; }
.wb-step-raw summary { color: #64748b; font-size: 10px; cursor: pointer; }
.wb-step-raw pre {
  margin: 4px 0 0;
  padding: 8px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #0f172a;
  color: #dbeafe;
  max-width: 560px;
  overflow-x: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 11px;
}
.wb-composer { border-top: 1px solid var(--border); padding: 12px 16px; display: flex; gap: 10px; align-items: flex-end; background: #fff; flex-shrink: 0; }
.wb-composer textarea { flex: 1; min-height: 44px; max-height: 140px; resize: none; line-height: 1.5; padding: 10px 14px; }
.session-drawer-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, .24); z-index: 30; }
.session-drawer { position: fixed; top: 0; right: 0; bottom: 0; width: min(520px, 92vw); background: #fff; border-left: 1px solid var(--border); box-shadow: -18px 0 45px rgba(15, 23, 42, .18); z-index: 31; display: flex; flex-direction: column; }
.drawer-head { padding: 18px 20px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between; gap: 14px; }
.drawer-head h3 { font-size: 18px; font-weight: 800; color: var(--text); margin-bottom: 5px; }
.drawer-head p { font-size: 12px; color: var(--muted); line-height: 1.6; }
.drawer-close { border: 0; background: transparent; color: #64748b; cursor: pointer; font-size: 26px; line-height: 1; }
.drawer-close:hover { color: #0f172a; }
.drawer-session { margin: 14px 18px 10px; padding: 10px 12px; border: 1px solid #e2e8f0; border-radius: 12px; background: #f8fafc; display: grid; gap: 4px; }
.drawer-session span { font-size: 11px; color: var(--muted); text-transform: uppercase; letter-spacing: .08em; }
.drawer-session strong { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; color: #334155; overflow-wrap: anywhere; }
.asset-tabs { display: flex; gap: 7px; flex-wrap: wrap; padding: 0 18px 12px; border-bottom: 1px solid var(--border); }
.asset-tabs button { border: 1px solid #dbeafe; background: #fff; border-radius: 999px; padding: 6px 11px; font-size: 12px; color: #334155; cursor: pointer; }
.asset-tabs button.active { background: #2563eb; border-color: #2563eb; color: #fff; }
.asset-raw { flex: 1; overflow: auto; margin: 0; padding: 16px 18px; background: #0f172a; color: #dbeafe; font-size: 12px; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 980px) {
  .agent-admin { grid-template-columns: 1fr; overflow: visible; }
  .wb-main { min-height: 600px; }
}
</style>
