<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import ActivityTimeline from '../components/ActivityTimeline.vue'
import MemoryPanel from '../components/MemoryPanel.vue'
import ResourcePanel from '../components/ResourcePanel.vue'
import { fetchTurnContext, platformHeaders, sendMemoryFeedback } from '../api/liveContext'
import { consoleState, resetTurn, upsertActivity } from '../stores/consoleState'
import { currentDomain, fmtDate, localValue, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { confirmDialog } from '../stores/dialog'
import { notifyError, notifySuccess } from '../stores/notify'
import type { ActivityItem, TurnContext } from '../types'

const domainOptions = ref<JsonMap[]>([{ domain: 'platform', label: '平台', org_id: 'platform' }])
const selectedDomain = ref(currentDomain())
const agents = ref<JsonMap[]>([])
const selectedAgentId = ref('')
const sessions = ref<JsonMap[]>([])
const currentSessionId = ref('')
const sessionSearch = ref('')
const docs = ref<JsonMap[]>([])
const selectedDoc = ref('')
const attachments = ref<JsonMap[]>([])
const messages = ref<JsonMap[]>([])
const query = ref('')
const streaming = ref(false)
const showContext = ref(false)
const attachmentStatus = ref('')
const speechHint = ref('支持语音输入；回答生成后可点击朗读。')
const recording = ref(false)
const speakingMessageIndex = ref<number | null>(null)
const suggestions = ['总结这份文档的关键结论', '列出和问题相关的证据来源', '把答案整理成可执行步骤']
let mediaRecorder: MediaRecorder | null = null
let recordingStream: MediaStream | null = null
let audioContext: AudioContext | null = null
let nextAudioStart = 0
let ttsAbortController: AbortController | null = null
let currentTtsSessionId = ''
let speakingDoneTimer = 0
let activitySeq = 0

const currentOrg = computed(() => String(domainOptions.value.find((item) => item.domain === selectedDomain.value)?.org_id || selectedDomain.value || 'platform'))
const currentUser = computed(() => localValue('platform_live_user_id', localValue('user_id', 'platform_admin')))
const selectedAgent = computed(() => agents.value.find((item) => String(item.agent_id) === selectedAgentId.value) || null)
const filteredSessions = computed(() => {
  const q = sessionSearch.value.trim().toLowerCase()
  if (!q) return sessions.value
  return sessions.value.filter((item) => String(item.title || item.session_id || '').toLowerCase().includes(q))
})
const turnResources = computed<TurnContext['resources']>(() => consoleState.turnContext?.resources || { attachments: [], documents: [], citations: [], kg_hits: [], tool_calls: [], artifacts: [] })
const turnActivity = computed(() => cleanActivityList(consoleState.turnContext?.activity?.length ? consoleState.turnContext.activity : consoleState.activity))
const longTermUsed = computed(() => consoleState.turnContext?.memory_usage.long_term || [])
const episodicUsed = computed(() => consoleState.turnContext?.memory_usage.episodic || [])

async function loadDomains() {
  const status = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: makeHeaders(false, 'platform') }))
  const raw = status.domains && typeof status.domains === 'object' ? status.domains : {}
  const rows = Object.entries(raw).map(([domain, snap]: [string, any]) => ({ domain, label: snap?.display_name || domain, org_id: snap?.org_id || domain, ...(snap || {}) }))
  domainOptions.value = [{ domain: 'platform', label: '平台', org_id: 'platform' }, ...rows.filter((item) => item.domain !== 'platform')]
  if (!domainOptions.value.some((item) => item.domain === selectedDomain.value)) selectedDomain.value = 'platform'
}

async function loadAgents() {
  const params = new URLSearchParams({ domain: selectedDomain.value })
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/agents?${params}`, { headers: makeHeaders(false, currentOrg.value) }))
  agents.value = (Array.isArray(data.items) ? data.items : Array.isArray(data.agents) ? data.agents : [])
    .filter((item) => item.enabled !== false)
  if (!selectedAgentId.value || !agents.value.some((item) => String(item.agent_id) === selectedAgentId.value)) {
    selectedAgentId.value = String(agents.value[0]?.agent_id || '')
  }
}

async function loadSessions() {
  const params = new URLSearchParams({ domain: selectedDomain.value, feature: 'platform_qa', limit: '80' })
  if (selectedAgentId.value) params.set('agent_id', selectedAgentId.value)
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/chat/sessions?${params}`, { headers: makeHeaders(false, currentOrg.value) }))
  sessions.value = Array.isArray(data.items) ? data.items : Array.isArray(data.sessions) ? data.sessions : []
}

async function newSession(loadAfter = true, title = '新对话') {
  const data = await readJson<JsonMap>(await fetch('/platform/frontend/chat/sessions', { method: 'POST', headers: makeHeaders(true, currentOrg.value), body: JSON.stringify({ domain: selectedDomain.value, feature: 'platform_qa', agent_id: selectedAgentId.value, title: String(title || '新对话').slice(0, 200) }) }))
  currentSessionId.value = String(data.session_id || data.id || data.session?.session_id || '')
  consoleState.sessionId = currentSessionId.value || `live_${Date.now()}`
  messages.value = []
  attachments.value = []
  if (loadAfter) await loadSessions()
}

async function selectSession(id: string) {
  currentSessionId.value = id
  consoleState.sessionId = id
  const qs = selectedAgentId.value ? `?agent_id=${encodeURIComponent(selectedAgentId.value)}` : ''
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/chat/sessions/${encodeURIComponent(id)}${qs}`, { headers: makeHeaders(false, currentOrg.value) }))
  messages.value = Array.isArray(data.messages) ? data.messages : Array.isArray(data.session?.messages) ? data.session.messages : []
  await loadAttachments()
}

async function deleteSession(id: string) {
  if (!await confirmDialog('确定删除这个历史对话吗？', { title: '删除对话', danger: true })) return
  try {
    const qs = selectedAgentId.value ? `?agent_id=${encodeURIComponent(selectedAgentId.value)}` : ''
    const res = await fetch(`/platform/frontend/chat/sessions/${encodeURIComponent(id)}${qs}`, { method: 'DELETE', headers: makeHeaders(false, currentOrg.value) })
    if (!res.ok) throw new Error(await res.text())
    // 删除当前会话后只回到空状态，不要自动新建一个会话
    if (currentSessionId.value === id) {
      currentSessionId.value = ''
      consoleState.sessionId = ''
      messages.value = []
      attachments.value = []
    }
    await loadSessions()
    notifySuccess('对话已删除')
  } catch (err) { notifyError(err) }
}

async function changeAgent() {
  currentSessionId.value = ''
  consoleState.sessionId = ''
  messages.value = []
  attachments.value = []
  await loadSessions()
}

async function loadDocs() {
  // 检索按业务域分片：知识库范围只列当前业务域的文档，避免选了别的域的文档却检索不到
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/knowledge/docs?latest_only=true&limit=100&domain=${encodeURIComponent(selectedDomain.value || 'platform')}`, { headers: makeHeaders(false, 'platform') }))
  docs.value = Array.isArray(data.documents) ? data.documents : Array.isArray(data.items) ? data.items : Array.isArray(data.docs) ? data.docs : []
}

function applyInitialDocScope() {
  const params = new URLSearchParams(location.search)
  const docId = params.get('doc_id')
  if (!docId) return
  const versionId = params.get('version_id') || ''
  const docOrgId = params.get('doc_org_id') || params.get('org_id') || ''
  const doc = docs.value.find((item) => String(item.doc_id || item.id) === docId
    && (!versionId || String(item.version_id || '') === versionId)
    && (!docOrgId || String(item.org_id || '') === docOrgId))
  if (doc) selectedDoc.value = String(doc.id || doc.doc_id)
}

function syncDocScopeUrl() {
  const params = new URLSearchParams(location.search)
  const doc = docs.value.find((item) => String(item.id || item.doc_id) === selectedDoc.value)
  if (!doc) {
    params.delete('doc_id')
    params.delete('version_id')
    params.delete('doc_org_id')
  } else {
    params.set('doc_id', String(doc.doc_id || doc.id || ''))
    params.set('version_id', String(doc.version_id || 'v1'))
    if (doc.org_id) params.set('doc_org_id', String(doc.org_id))
    else params.delete('doc_org_id')
  }
  history.replaceState(null, '', `${location.pathname}?${params.toString()}${location.hash}`)
}

watch(selectedDoc, () => syncDocScopeUrl())

async function loadAttachments() {
  if (!currentSessionId.value) return
  const params = new URLSearchParams({ domain: selectedDomain.value, feature: 'platform_qa' })
  const data = await readJson<JsonMap>(await fetch(`/platform/session/sessions/${encodeURIComponent(currentSessionId.value)}/attachments?${params}`, { headers: makeHeaders(false, currentOrg.value) }))
  attachments.value = Array.isArray(data.items) ? data.items : []
  await refreshAttachmentStatuses()
}

async function refreshAttachmentStatuses() {
  const next = [...attachments.value]
  for (let i = 0; i < next.length; i++) {
    const id = next[i].attachment_id || next[i].id
    if (!id) continue
    const st = String(next[i].parse_status || next[i].status || '')
    if (['parsed', 'succeeded', 'failed', 'parse_failed', 'ready'].includes(st)) continue
    try {
      const data = await readJson<JsonMap>(await fetch(`/platform/session/attachments/${encodeURIComponent(String(id))}/status`, { headers: makeHeaders(false, currentOrg.value) }))
      next[i] = { ...next[i], ...(data.item || data) }
    } catch {}
  }
  attachments.value = next
}

async function uploadAttachment(ev: Event) {
  attachmentStatus.value = ''
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const extension = file.name.split('.').pop()?.toLowerCase() || ''
  if (!['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'md', 'markdown', 'txt', 'csv'].includes(extension)) {
    attachmentStatus.value = '仅支持 PDF、Office、Markdown、TXT、CSV；图片 OCR 将在后续版本提供。'
    return
  }
  try {
    if (!currentSessionId.value) {
      attachmentStatus.value = '正在创建对话以保存附件...'
      await newSession()
    }
    if (!currentSessionId.value) throw new Error('无法创建对话，请重试')
    const form = new FormData()
    form.set('file', file)
    form.set('session_id', currentSessionId.value)
    form.set('domain', selectedDomain.value)
    form.set('feature', 'platform_qa')
    attachmentStatus.value = `正在上传并解析 ${file.name}...`
    await readJson(await fetch('/platform/session/attachments', { method: 'POST', headers: makeHeaders(false, currentOrg.value), body: form }))
    await loadAttachments()
    attachmentStatus.value = `附件已就绪，发送问题时会检索 ${file.name}`
  } catch (err) {
    attachmentStatus.value = `附件上传失败：${err instanceof Error ? err.message : String(err)}`
    notifyError(err)
  }
}

async function removeAttachment(item: JsonMap) {
  const id = item.attachment_id || item.id
  if (!id) return
  await readJson(await fetch(`/platform/session/attachments/${encodeURIComponent(String(id))}`, { method: 'DELETE', headers: makeHeaders(false, currentOrg.value) }))
  await loadAttachments()
}

function attachmentStatusClass(item: JsonMap): string {
  const status = String(item.status || '').toLowerCase()
  const parseStatus = String(item.parse_status || '').toLowerCase()
  if (status === 'error' || parseStatus === 'failed' || parseStatus === 'parse_failed') return 'error'
  if (status === 'ready' || ['parsed', 'succeeded'].includes(parseStatus)) return 'ready'
  return ''
}

function attachmentStatusLabel(item: JsonMap): string {
  const status = String(item.status || '').toLowerCase()
  const parseStatus = String(item.parse_status || '').toLowerCase()
  if (status === 'error' || parseStatus === 'failed' || parseStatus === 'parse_failed') return '失败'
  if (parseStatus === 'parsing') return '解析中'
  if (parseStatus === 'uploaded') return '待解析'
  if (status === 'ready' || ['parsed', 'succeeded'].includes(parseStatus)) return '已就绪'
  return '上传中'
}

function applySuggestion(text: string) { query.value = text }
function autoResize(ev: Event) { const el = ev.target as HTMLTextAreaElement; el.style.height = 'auto'; el.style.height = Math.min(180, el.scrollHeight) + 'px' }
function handleComposerKeydown(ev: KeyboardEvent) {
  if (ev.key !== 'Enter' || ev.shiftKey || ev.ctrlKey || ev.altKey || ev.metaKey || ev.isComposing) {
    return
  }
  ev.preventDefault()
  sendQuery()
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error || new Error('读取音频失败'))
    reader.readAsDataURL(blob)
  })
}

async function readSpeechEvents(response: Response, onEvent: (event: JsonMap) => Promise<void> | void) {
  const reader = response.body?.getReader()
  if (!reader) return
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const raw of lines) {
      const line = raw.trim()
      if (!line) continue
      const payload = line.startsWith('data:') ? line.slice(5).trim() : line
      if (!payload || payload === '[DONE]') continue
      try { await onEvent(JSON.parse(payload)) } catch {}
    }
    if (done) break
  }
  const tail = buffer.trim()
  if (tail) {
    const payload = tail.startsWith('data:') ? tail.slice(5).trim() : tail
    if (payload && payload !== '[DONE]') {
      try { await onEvent(JSON.parse(payload)) } catch {}
    }
  }
}

function stopRecordingTracks() {
  recordingStream?.getTracks?.().forEach((track) => track.stop())
  recordingStream = null
}

async function toggleVoiceInput() {
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    mediaRecorder.stop()
    return
  }
  if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
    notifyError('当前浏览器不支持语音输入')
    return
  }
  try {
    recordingStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    const chunks: BlobPart[] = []
    mediaRecorder = new MediaRecorder(recordingStream)
    mediaRecorder.ondataavailable = (event) => {
      if (event.data?.size) chunks.push(event.data)
    }
    mediaRecorder.onstop = async () => {
      recording.value = false
      stopRecordingTracks()
      speechHint.value = '正在识别语音...'
      try {
        const blob = new Blob(chunks, { type: mediaRecorder?.mimeType || 'audio/webm' })
        const audio = await blobToDataUrl(blob)
        let text = ''
        const res = await fetch('/platform/media/asr/stream', {
          method: 'POST',
          headers: makeHeaders(true, currentOrg.value),
          body: JSON.stringify({ audio, language: 'Chinese' }),
        })
        if (!res.ok) throw new Error(await res.text())
        await readSpeechEvents(res, (event) => {
          text = String(event.text || event.result?.text || event.data?.text || event.transcript || text || '')
        })
        if (text) {
          query.value = query.value ? `${query.value}\n${text}` : text
          speechHint.value = '语音已识别，可继续编辑或发送。'
        } else {
          speechHint.value = '没有识别到语音内容。'
        }
      } catch (err) {
        speechHint.value = '语音识别失败。'
        notifyError(err)
      }
    }
    mediaRecorder.start()
    recording.value = true
    speechHint.value = '正在录音，再点一次麦克风结束识别。'
  } catch (err) {
    recording.value = false
    stopRecordingTracks()
    notifyError(err)
  }
}

function stopSpeechPlayback() {
  const sessionId = currentTtsSessionId
  currentTtsSessionId = ''
  ttsAbortController?.abort?.()
  ttsAbortController = null
  if (sessionId) {
    fetch('/platform/media/tts/cancel', {
      method: 'POST',
      headers: makeHeaders(true, currentOrg.value),
      body: JSON.stringify({ tts_session_id: sessionId }),
    }).catch(() => {})
  }
  if ('speechSynthesis' in window) window.speechSynthesis.cancel()
  audioContext?.close?.().catch?.(() => {})
  audioContext = null
  nextAudioStart = 0
  speakingMessageIndex.value = null
  window.clearTimeout(speakingDoneTimer)
}

function splitTtsSegments(text: string, maxChars = 90): string[] {
  const normalized = String(text || '').replace(/\s+/g, ' ').trim()
  if (!normalized) return []
  const parts = normalized.match(/[^。！？!?；;：:，,、]+[。！？!?；;：:，,、]?/g) || [normalized]
  const segments: string[] = []
  let buf = ''
  for (const part of parts) {
    const next = `${buf}${part}`.trim()
    if (buf && next.length > maxChars) {
      segments.push(buf)
      buf = part.trim()
    } else {
      buf = next
    }
    while (buf.length > maxChars * 1.5) {
      segments.push(buf.slice(0, maxChars))
      buf = buf.slice(maxChars)
    }
  }
  if (buf) segments.push(buf)
  return segments
}

async function schedulePcmChunk(pcmBase64: string, sampleRate = 24000, channels = 1) {
  const AudioContextCtor = window.AudioContext || (window as any).webkitAudioContext
  if (!AudioContextCtor) throw new Error('当前浏览器不支持流式音频播放')
  if (!audioContext || audioContext.state === 'closed') {
    audioContext = new AudioContextCtor()
    nextAudioStart = audioContext.currentTime
  }
  if (audioContext.state === 'suspended') await audioContext.resume()
  const binary = atob(String(pcmBase64 || ''))
  const pcm = new Int16Array(Math.floor(binary.length / 2))
  for (let i = 0; i < pcm.length; i += 1) {
    let sample = binary.charCodeAt(i * 2) | (binary.charCodeAt(i * 2 + 1) << 8)
    if (sample >= 0x8000) sample -= 0x10000
    pcm[i] = sample
  }
  const frameCount = Math.floor(pcm.length / channels)
  const buffer = audioContext.createBuffer(channels, frameCount, sampleRate)
  for (let ch = 0; ch < channels; ch += 1) {
    const data = buffer.getChannelData(ch)
    for (let i = 0; i < frameCount; i += 1) data[i] = pcm[i * channels + ch] / 32768
  }
  const source = audioContext.createBufferSource()
  source.buffer = buffer
  source.connect(audioContext.destination)
  const startAt = Math.max(audioContext.currentTime + 0.03, nextAudioStart || 0)
  source.start(startAt)
  nextAudioStart = startAt + buffer.duration
}

function finishSpeechControls(index: number) {
  if (speakingMessageIndex.value !== index) return
  speechHint.value = '朗读完成。'
  speakingMessageIndex.value = null
  currentTtsSessionId = ''
  ttsAbortController = null
  window.clearTimeout(speakingDoneTimer)
  speakingDoneTimer = window.setTimeout(() => {
    if (!recording.value && speakingMessageIndex.value === null) speechHint.value = '支持语音输入；回答生成后可点击朗读。'
  }, 1600)
}

function fallbackBrowserSpeech(text: string, index: number, err: unknown) {
  if (!('speechSynthesis' in window)) {
    speechHint.value = '朗读失败。'
    notifyError(err)
    speakingMessageIndex.value = null
    return
  }
  const utterance = new SpeechSynthesisUtterance(text)
  utterance.lang = 'zh-CN'
  utterance.rate = 1
  utterance.onend = () => finishSpeechControls(index)
  utterance.onerror = () => {
    speechHint.value = '朗读失败。'
    speakingMessageIndex.value = null
  }
  window.speechSynthesis.speak(utterance)
}

async function speakMessage(index: number, text: string) {
  if (speakingMessageIndex.value === index) {
    stopSpeechPlayback()
    speechHint.value = '已停止朗读。'
    return
  }
  stopSpeechPlayback()
  speakingMessageIndex.value = index
  currentTtsSessionId = `tts-${Date.now()}-${Math.random().toString(16).slice(2)}`
  speechHint.value = '正在朗读...'
  try {
    let chunkCount = 0
    for (const segment of splitTtsSegments(text)) {
      if (speakingMessageIndex.value !== index) return
      let sampleRate = 24000
      let channels = 1
      ttsAbortController = new AbortController()
      const res = await fetch('/platform/media/tts/stream', {
        method: 'POST',
        headers: makeHeaders(true, currentOrg.value),
        signal: ttsAbortController.signal,
        body: JSON.stringify({
          text: segment,
          tts_session_id: currentTtsSessionId,
          language: 'Chinese',
          instruct: '自然、清晰、适合企业知识问答场景的中文朗读',
          mode: 'design',
          voice_key: 'platform_default',
          chunk_ms: 120,
        }),
      })
      if (!res.ok) throw new Error(await res.text())
      await readSpeechEvents(res, async (event) => {
        if (speakingMessageIndex.value !== index) return
        if (event.event === 'meta') {
          sampleRate = Number(event.sample_rate || sampleRate)
          channels = Number(event.channels || channels)
          return
        }
        const pcmBase64 = event.pcm_base64 || event.audio?.pcm_base64 || event.data?.pcm_base64
        if (!pcmBase64) return
        chunkCount += 1
        await schedulePcmChunk(String(pcmBase64), Number(event.sample_rate || sampleRate), Number(event.channels || channels))
      })
      ttsAbortController = null
    }
    if (!chunkCount) throw new Error('平台朗读没有返回音频流')
    finishSpeechControls(index)
  } catch (err) {
    ttsAbortController = null
    if ((err as any)?.name === 'AbortError') return
    fallbackBrowserSpeech(text, index, err)
  }
}

function parseSsePayload(payload: string): JsonMap | null {
  const raw = payload.trim()
  if (!raw || raw === '[DONE]') return null
  try { return JSON.parse(raw) as JsonMap } catch { return null }
}

async function consumeSse(response: Response, onEvent: (event: JsonMap) => Promise<void> | void) {
  const reader = response.body?.getReader()
  if (!reader) return
  const decoder = new TextDecoder()
  let buffer = ''
  let dataBuffer = ''
  while (true) {
    const { value, done } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    const rows = buffer.split('\n')
    buffer = rows.pop() || ''
    for (const row of rows) {
      const line = row.trimEnd()
      if (!line) {
        if (dataBuffer.trim()) {
          const eventPayload = dataBuffer
          dataBuffer = ''
          await parseAndEmitSse(eventPayload, onEvent)
        }
        continue
      }
      if (!line.startsWith('data:')) continue
      const data = line.slice(5).trim()
      if (!data) continue
      dataBuffer = dataBuffer ? `${dataBuffer}\n${data}` : data
    }
    if (done) break
  }
  if (buffer.trim()) {
    dataBuffer = dataBuffer ? `${dataBuffer}\n${buffer}` : buffer
    buffer = ''
  }
  if (dataBuffer.trim()) {
    const eventPayload = dataBuffer
    dataBuffer = ''
    await parseAndEmitSse(eventPayload, onEvent)
  }
}

async function parseAndEmitSse(payload: string, onEvent: (event: JsonMap) => Promise<void> | void) {
  const event = parseSsePayload(payload)
  if (!event) return
  // 兼容 event 字段写在 data 对象里的旧/新两种风格
  if (!event.type && typeof event.event === 'string') event.type = event.event
  await onEvent(event)
}

function normalizeActivityItem(raw: ActivityItem | JsonMap): ActivityItem {
  const event = raw as JsonMap
  const detail = (event.detail || event.payload || {}) as JsonMap
  const type = String(event.step || event.activity_type || event.event_type || event.type || '').trim().toLowerCase().replaceAll('.', '_')
  const refs = (event.refs && typeof event.refs === 'object' ? event.refs : {}) as Record<string, unknown>
  const toolCallId = String(detail.tool_call_id || detail.tool_id || refs.tool_call_id || '').trim()
  const toolName = String(detail.tool_call_name || detail.tool_name || refs.tool_call_name || refs.tool_name || '').trim()
  const isToolLifecycle = type.startsWith('tool_call_') || type.startsWith('tool_result_')
  const toolResultState = String(detail.tool_result_state || refs.tool_result_state || '').trim()
  const toolCallState = String(detail.tool_call_state || refs.tool_call_state || '').trim()
  const title = String(event.title || event.label || eventTitle({ ...event, type }) || '').trim()
  return {
    ...(event as ActivityItem),
    id: isToolLifecycle
      ? `tool:${toolCallId || toolName || type}`
      : String(event.id || event.event_id || `${consoleState.activeTraceId}.${activitySeq++}.${type || 'activity'}`),
    type: type || 'activity',
    title: isToolLifecycle && toolName ? `工具调用：${toolName}` : title && title !== 'activity' ? title : eventTitle({ ...event, type }),
    status: (isToolLifecycle
      ? (toolResultState === 'SUCCESS' || type === 'tool_result_end' ? 'success' : type.includes('error') || type.includes('fail') ? 'error' : 'running')
      : (event.status as ActivityItem['status']) || eventStatus({ ...event, type })) as ActivityItem['status'],
    summary: String(event.summary || event.message || '').trim(),
    detail: detail && typeof detail === 'object' ? detail as Record<string, unknown> : {},
    refs: {
      ...refs,
      ...(toolCallId ? { tool_call_id: toolCallId } : {}),
      ...(toolName ? { tool_call_name: toolName } : {}),
      ...(toolResultState || toolCallState ? { tool_call_state: toolResultState || toolCallState } : {}),
    },
  }
}

function eventTitle(event: ActivityItem | JsonMap) {
  const type = String((event as JsonMap).type || '').trim().toLowerCase()
  if (type === 'receive' || type === 'turn_received' || type === 'turn.received') return '接收问题'
  if (type === 'capability_loaded') return '加载能力'
  if (type === 'tool_call') return '工具调用'
  if (type === 'tool_result') return '工具结果'
  if (type === 'memory_save') return '记忆保存'
  if (type === 'model_call_start') return '模型调用开始'
  if (type === 'model_call_end') return '模型调用完成'
  if (type === 'agent_end') return 'Agent 完成'
  return type || '事件'
}

function eventStatus(event: ActivityItem | JsonMap) {
  const type = String((event as JsonMap).type || '').trim().toLowerCase()
  if (type.includes('failed') || type.includes('rejected') || type.includes('expired')) return 'error'
  if (type.includes('started') || type.includes('scheduled') || type === 'waiting_user_input') {
    return type === 'waiting_user_input' ? 'warning' : 'running'
  }
  return 'success'
}

function isUsefulActivity(raw: ActivityItem | JsonMap) {
  const item = normalizeActivityItem(raw)
  const detail = (item.detail || {}) as JsonMap
  const type = String(item.type || '').toLowerCase()
  if (type === 'activity' || type === 'researcher' || type.startsWith('researcher')) {
    return String(item.summary || '').trim() !== '' || Object.keys(detail).some((key) => key && !['agent_id', 'reply_id', 'runtime'].includes(key))
  }
  if (type === 'turn_received' || type === 'turn.received') return true
  if (
    type === 'capability_loaded'
    || type.startsWith('workflow_')
    || type === 'router_decision'
    || type === 'supervisor_start'
    || type === 'single_agent_start'
    || type.startsWith('tool_')
    || type.startsWith('skill_')
  ) return true
  if (item.summary && item.summary !== String(detail.agent_id || '')) return true
  const keys = Object.keys(detail).filter((key) => !['agent_id', 'reply_id', 'runtime'].includes(key))
  return keys.length > 0
}

function cleanActivityList(items: (ActivityItem | JsonMap)[]) {
  const rows: ActivityItem[] = []
  for (const item of (items || []).map(normalizeActivityItem).filter(isUsefulActivity)) {
    const idx = rows.findIndex((row) => row.id === item.id)
    if (idx >= 0) {
      rows[idx] = {
        ...rows[idx],
        ...item,
        detail: { ...(rows[idx].detail || {}), ...(item.detail || {}) },
        refs: { ...(rows[idx].refs || {}), ...(item.refs || {}) },
        summary: item.summary || rows[idx].summary,
      }
    } else {
      rows.push(item)
    }
  }
  return rows
}

async function sendQuery() {
  const text = query.value.trim()
  if (!text || streaming.value) return
  if (!selectedAgentId.value) {
    notifyError('请先选择一个 Agent')
    return
  }
  if (!currentSessionId.value) await newSession(false, text)
  const traceId = `trace_platform_live_${Math.random().toString(16).slice(2)}_${Date.now()}`
  resetTurn(traceId)
  consoleState.domain = selectedDomain.value
  consoleState.sessionId = currentSessionId.value
  query.value = ''
  streaming.value = true
  showContext.value = true
  const userMsg = { role: 'user', content: text, trace_id: traceId }
  const assistantMsg: JsonMap = { role: 'assistant', content: '', trace_id: traceId }
  messages.value.push(userMsg, assistantMsg)
  try {
    const doc = docs.value.find((item) => String(item.id || item.doc_id || item.document_id) === selectedDoc.value)
    const readyAttachments = attachments.value.filter((item) => attachmentStatusClass(item) === 'ready' && item.doc_id)
    const activeAttachments = readyAttachments.map((item) => ({
      attachment_id: item.attachment_id || item.id,
      org_id: item.org_id || currentOrg.value,
      doc_id: item.doc_id || '',
      version_id: item.version_id || 'v1',
      file_name: item.file_name || item.filename || item.name,
      parse_status: item.parse_status || item.status,
      scope: item.scope || 'session',
    }))
    const scopeMode = activeAttachments.length ? 'attachment_only' : 'knowledge_only'
    // 全部可见文档的 owner org 与文档版本，供后端跨 org 检索使用
    const ownerOrgIds = Array.from(new Set(docs.value.map((d) => String(d.org_id || '').trim()).filter(Boolean)))
    const scopeItems = docs.value
      .map((d) => ({ org_id: String(d.org_id || '').trim(), doc_id: String(d.doc_id || d.id || '').trim(), version_id: String(d.version_id || 'v1').trim() || 'v1' }))
      .filter((d) => d.org_id && d.doc_id)
    const body: JsonMap = {
      session_id: currentSessionId.value,
      domain: selectedDomain.value,
      agent_id: selectedAgentId.value,
      query: text,
      feature: 'platform_qa',
      scope_mode: scopeMode,
      mode: 'hybrid',
      top_k: 8,
      attachments: activeAttachments,
      scene_context: {
        source: 'platform_live_vue',
        domain: selectedDomain.value,
        feature: 'platform_qa',
        page: 'platform_live_qa',
        agent_id: selectedAgentId.value,
        agent_name: selectedAgent.value?.display_name || selectedAgent.value?.name || selectedAgentId.value,
        scope_mode: scopeMode,
        doc_org_id: doc ? String(doc.org_id || '') : '',
        doc_org_ids: ownerOrgIds,
        attachments: activeAttachments,
        attachment_count: activeAttachments.length,
      },
    }
    if (activeAttachments.length) {
      const orgIds = Array.from(new Set(activeAttachments.map((item) => item.org_id).filter(Boolean)))
      body.doc_id = activeAttachments[0].doc_id
      body.version_id = activeAttachments[0].version_id
      body.retrieve_scope = { allowed_org_ids: orgIds, allowed_doc_versions: activeAttachments.map((item) => ({ org_id: item.org_id, doc_id: item.doc_id, version_id: item.version_id || 'v1' })), scope_mode: scopeMode, include_public: true }
      body.scene_context.doc_org_id = activeAttachments[0].org_id || currentOrg.value
      body.scene_context.active_doc = activeAttachments[0].file_name || activeAttachments[0].doc_id
    } else if (doc) {
      // 选定单个文档：带上该文档所属 org 与检索范围，否则后端在当前 org 下检索不到跨 org 文档
      const docOrg = String(doc.org_id || '')
      const docId = String(doc.doc_id || doc.id || '')
      const docVer = String(doc.version_id || 'v1')
      body.document_id = doc.id || doc.doc_id
      body.doc_id = doc.doc_id || doc.id
      body.version_id = docVer
      body.scene_context.active_doc = doc.filename || doc.title || doc.name || docId
      body.retrieve_scope = { allowed_org_ids: docOrg ? [docOrg] : ownerOrgIds, allowed_doc_versions: [{ org_id: docOrg, doc_id: docId, version_id: docVer }], scope_mode: scopeMode, include_public: true }
    } else {
      // 未选文档（全部）：带上全部可见文档的范围，覆盖跨 org 文档
      body.retrieve_scope = { allowed_org_ids: ownerOrgIds, allowed_doc_versions: scopeItems, include_public: true }
      body.scene_context.doc_scope_count = scopeItems.length
    }
    const response = await fetch('/platform/frontend/chat/stream', {
      method: 'POST',
      headers: { ...platformHeaders(true), 'x-org-id': currentOrg.value, 'x-user-id': currentUser.value, 'x-trace-id': traceId },
      body: JSON.stringify(body),
    })
    if (!response.ok) throw new Error(await response.text())
    await consumeSse(response, async (event) => {
      const type = String(event.type || event.event || '').trim().toLowerCase()
      if (type === 'token') {
        assistantMsg.content += String(event.delta || '')
        await nextTick()
      }
      else if (type === 'activity') {
        const activityType = String(event.step || event.activity_type || event.event_type || 'activity')
        const item = normalizeActivityItem({ ...(event as JsonMap), type: activityType })
        if (isUsefulActivity(item)) upsertActivity(item)
      }
      else if (type === 'progress') upsertActivity({ id: `progress.${event.node}.${event.phase}`, type: `progress.${event.node}`, title: String(event.label || event.node), status: event.phase === 'before' ? 'running' : 'success', summary: String(event.phase || ''), detail: event })
      else if (type === 'done') {
        if (!assistantMsg.content) assistantMsg.content = String(event.result?.answer || event.result?.text || '')
        const sources = event.result?.citations || event.result?.sources || event.result?.hits || []
        if (Array.isArray(sources) && sources.length) assistantMsg.sources = sources
      } else if (type === 'error') throw new Error(String(event.error || 'chat stream error'))
    })
    if (!assistantMsg.content) assistantMsg.content = '已完成，但没有返回回答文本。'
    try { consoleState.turnContext = await fetchTurnContext(traceId) } catch {}
    await loadSessions()
  } catch (err) {
    assistantMsg.content = `请求失败：${err instanceof Error ? err.message : String(err)}`
  } finally {
    streaming.value = false
  }
}

function triggerAttachmentInput() {
  const input = document.getElementById('fileInput') as HTMLInputElement | null
  input?.click()
}

async function handleMemoryFeedback(id: number, action: string) {
  try {
    await sendMemoryFeedback(id, action, { trace_id: consoleState.activeTraceId || '' })
    consoleState.error = ''
    if (consoleState.activeTraceId) {
      try { consoleState.turnContext = await fetchTurnContext(consoleState.activeTraceId) } catch {}
    }
  } catch (err) {
    consoleState.error = err instanceof Error ? err.message : String(err)
  }
}

async function loadForDomain() {
  await loadAgents()
  await Promise.all([loadSessions(), loadDocs()])
  applyInitialDocScope()
  // Do not auto-create a session on load — a new conversation is only created
  // when the user clicks 新建 or sends the first message (see sendQuery).
}

async function changeDomain() {
  localStorage.setItem('platform_live_domain', selectedDomain.value)
  localStorage.setItem('platform_live_org_id', currentOrg.value)
  const params = new URLSearchParams(location.search)
  params.set('domain', selectedDomain.value)
  params.set('org_id', currentOrg.value)
  params.delete('doc_id')
  params.delete('version_id')
  params.delete('doc_org_id')
  history.replaceState(null, '', `${location.pathname}?${params.toString()}${location.hash}`)
  currentSessionId.value = ''
  selectedAgentId.value = ''
  selectedDoc.value = ''
  attachments.value = []
  messages.value = []
  consoleState.sessionId = ''
  await loadForDomain()
}

onMounted(async () => {
  await loadDomains()
  await loadForDomain()
})
</script>

<template>
  <section class="workspace">
    <aside class="history-pane">
      <div class="pane-head"><h2>历史对话</h2><div class="actions"><button class="btn btn-ghost btn-sm" @click="loadSessions">刷新历史</button><button class="btn btn-ghost btn-sm" @click="newSession()">新建</button></div></div>
      <div class="search-wrap"><input v-model="sessionSearch" placeholder="搜索历史对话..." /></div>
      <div class="session-list">
        <div v-if="!filteredSessions.length" class="empty">暂无历史对话</div>
        <div v-for="s in filteredSessions" :key="s.session_id || s.id" class="session-item" :class="{active: currentSessionId === String(s.session_id || s.id)}" @click="selectSession(String(s.session_id || s.id))">
          <div><div class="session-title">{{ s.title || '未命名对话' }}</div><div class="session-meta">{{ fmtDate(s.updated_at || s.created_at) }}</div></div>
          <button class="session-delete" @click.stop="deleteSession(String(s.session_id || s.id))">×</button>
        </div>
      </div>
    </aside>
    <section class="chat-area">
      <div class="chat-settings"><label>业务域</label><select v-model="selectedDomain" @change="changeDomain"><option v-for="d in domainOptions" :key="d.domain" :value="d.domain">{{ d.label || d.domain }}</option></select><label>Agent</label><select v-model="selectedAgentId" @change="changeAgent"><option v-for="a in agents" :key="String(a.agent_id)" :value="String(a.agent_id)">{{ a.display_name || a.name || a.agent_id }}</option></select><label>知识库范围</label><select v-model="selectedDoc"><option value="">全部文档</option><option v-for="doc in docs" :key="doc.id || doc.doc_id" :value="String(doc.id || doc.doc_id)">{{ doc.filename || doc.title || doc.name }}</option></select><button class="btn btn-ghost btn-sm" @click="showContext = !showContext">{{ showContext ? '隐藏上下文' : '显示上下文' }}</button></div>
      <div class="qa-body" :class="{withContext: showContext}">
        <div class="chat-msgs"><div v-if="!messages.length" class="empty-state"><div class="empty-icon">💬</div><h3>开始一个问题</h3><p>选择 Agent 后直接对话；也可以限定知识库范围或上传附件，让 Agent 在当前会话中使用这些上下文。</p></div><div v-for="(m, idx) in messages" :key="idx" class="msg-row" :class="m.role"><div class="msg-avatar" :class="m.role === 'user' ? 'user' : 'ai'">{{ m.role === 'user' ? '你' : 'AI' }}</div><div class="bubble-wrap"><div class="bubble" :class="[m.role === 'user' ? 'user' : 'ai', streaming && idx === messages.length - 1 ? 'streaming' : '']">{{ m.content }}</div><div v-if="m.sources && m.sources.length" class="sources"><span v-for="(s, sIdx) in m.sources.slice(0, 4)" :key="sIdx" class="source-chip">📄 {{ s.filename || s.doc_id || '文档' }}</span></div><div v-if="m.role !== 'user' && m.content" class="speech-actions"><button class="speak-btn" :class="{active: speakingMessageIndex === idx}" type="button" @click="speakMessage(idx, String(m.content || ''))">{{ speakingMessageIndex === idx ? '停止朗读' : '朗读' }}</button><span v-if="speakingMessageIndex === idx" class="speech-status">正在朗读</span></div></div></div></div>
        <aside v-if="showContext" class="turn-context-panel"><nav class="tabs"><button v-for="tab in ['activity','resources','memory','evidence']" :key="tab" :class="{active: consoleState.activeTab === tab}" @click="consoleState.activeTab = tab">{{ ({ activity: '活动', resources: '资源', memory: '记忆', evidence: '证据' } as Record<string, string>)[tab] || tab }}</button></nav><p v-if="consoleState.error" class="error-line">{{ consoleState.error }}</p><ActivityTimeline v-if="consoleState.activeTab === 'activity'" :items="turnActivity" /><ResourcePanel v-else-if="consoleState.activeTab === 'resources'" :resources="turnResources" /><MemoryPanel v-else-if="consoleState.activeTab === 'memory'" :long-term="longTermUsed" :episodic="episodicUsed" @feedback="handleMemoryFeedback" /><ResourcePanel v-else :resources="{ citations: turnResources.citations, kg_hits: turnResources.kg_hits, tool_calls: turnResources.tool_calls, attachments: [], documents: [], artifacts: [] }" /></aside>
      </div>
      <div class="composer"><div v-if="!messages.length" class="suggestion-row"><button v-for="s in suggestions" :key="s" class="btn btn-ghost btn-sm" @click="applySuggestion(s)">{{ s }}</button></div><div class="attachment-tray"><span v-for="a in attachments" :key="a.id || a.attachment_id" class="attachment-chip" :class="attachmentStatusClass(a)"><span class="name">{{ a.filename || a.file_name || a.name }}</span><span class="state">{{ attachmentStatusLabel(a) }}</span><button class="chip-close" @click="removeAttachment(a)">×</button></span><span v-if="attachmentStatus" class="muted-small">{{ attachmentStatus }}</span></div><div class="composer-wrap"><input id="fileInput" type="file" accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.md,.markdown,.txt,.csv" class="hidden" @change="uploadAttachment" /><button class="btn btn-ghost" @click="triggerAttachmentInput">上传文档</button><button class="voice-btn" :class="{active: recording}" type="button" :title="recording ? '停止录音' : '语音输入'" @click="toggleVoiceInput">🎙</button><textarea v-model="query" class="composer-input" rows="1" placeholder="输入问题，按 Enter 发送，Shift+Enter 换行..." @input="autoResize" @keydown="handleComposerKeydown" /><button class="btn btn-primary" :disabled="streaming || !query.trim()" @click="sendQuery">{{ streaming ? '发送中' : '发送' }}</button></div><div class="speech-hint">{{ speechHint }}</div></div>
    </section>
  </section>
</template>

<style scoped>
.voice-btn {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  border: 1px solid var(--border, #dbe3ef);
  background: #fff;
  color: #64748b;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  font-size: 17px;
  transition: border-color .16s ease, background .16s ease, color .16s ease, transform .16s ease;
}

.voice-btn:hover {
  border-color: #2563eb;
  background: #eff6ff;
  color: #1d4ed8;
}

.voice-btn.active {
  border-color: #ef4444;
  background: #ef4444;
  color: #fff;
  transform: scale(.98);
}

.speech-hint {
  margin-top: 8px;
  min-height: 16px;
  color: #64748b;
  font-size: 12px;
}

.speech-actions {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.speak-btn {
  border: 1px solid #dbe3ef;
  background: #f8fafc;
  color: #64748b;
  font-size: 12px;
  border-radius: 999px;
  padding: 4px 10px;
  cursor: pointer;
  transition: border-color .16s ease, background .16s ease, color .16s ease;
}

.speak-btn:hover {
  border-color: #2563eb;
  background: #eff6ff;
  color: #1d4ed8;
}

.speak-btn.active {
  border-color: #ef4444;
  background: #fee2e2;
  color: #dc2626;
}

.speech-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #2563eb;
  font-size: 12px;
}

.speech-status::before {
  content: "";
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2563eb;
  animation: speechPulse 1s ease-in-out infinite;
}

@keyframes speechPulse {
  50% { opacity: .35; }
}
</style>
