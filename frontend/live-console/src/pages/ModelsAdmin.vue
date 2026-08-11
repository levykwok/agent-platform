<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { currentOrgId, fmtDate, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'
import { confirmDialog, formDialog, promptDialog } from '../stores/dialog'

type Tab = 'providers' | 'models' | 'slots' | 'resolve' | 'audit'
type KeyValueRow = { key: string; value: string }
type ModelTestOutcome = JsonMap
const tabs: { key: Tab; label: string }[] = [
  { key: 'providers', label: '供应商' },
  { key: 'models', label: '模型目录' },
  { key: 'slots', label: '模型插槽' },
  { key: 'resolve', label: '解析预览' },
  { key: 'audit', label: '审计' },
]
const tab = ref<Tab>('providers')

const providers = ref<JsonMap[]>([])
const models = ref<JsonMap[]>([])
const slots = ref<JsonMap[]>([])
const bindings = ref<JsonMap[]>([])
const audit = ref<JsonMap[]>([])
const pingingModels = ref<Record<string, boolean>>({})
const testingModels = ref<Record<string, boolean>>({})
const modelTestResults = ref<Record<string, ModelTestOutcome>>({})
const visionTestInputEl = ref<HTMLInputElement | null>(null)
const pendingVisionTest = ref<{ model: JsonMap; prompt: string } | null>(null)
const schema = reactive<JsonMap>({ provider_types: [], model_kinds: [], provider_call_types: [], model_capabilities: [], statuses: [] })

const orgId = ref(currentOrgId())
const aliases = ref<JsonMap[]>([])
const domainOptions = ref<JsonMap[]>([])
async function loadDomains() {
  try {
    const status = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: headers(false) }))
    const raw = status.domains && typeof status.domains === 'object' ? status.domains as JsonMap : {}
    domainOptions.value = Object.entries(raw).map(([domain, snap]) => ({ domain, label: String((snap as JsonMap)?.display_name || domain) })).filter((r) => r.domain !== 'platform')
  } catch { /* ignore */ }
}

const activeProviders = computed(() => providers.value.filter((p) => p.status === 'active').length)
const activeModels = computed(() => models.value.filter((m) => m.status === 'active').length)
const configuredSlots = computed(() => slots.value.filter((s) => !!platformBinding(String(s.slot_key))).length)

function headers(json = false) { return makeHeaders(json, orgId.value) }
async function api(path: string, opts: RequestInit = {}) {
  return await readJson<JsonMap>(await fetch(path, { headers: headers(Boolean(opts.body)), ...opts }))
}

async function refresh() {
  try {
    const [p, m, s, b, sc, al] = await Promise.all([
      api('/platform/frontend/models/providers'),
      api('/platform/frontend/models'),
      api('/platform/frontend/models/slots'),
      api('/platform/frontend/models/slots/bindings'),
      api('/platform/frontend/models/schema'),
      api('/platform/frontend/models/aliases'),
    ])
    providers.value = p.providers || []
    models.value = m.models || []
    slots.value = s.slots || []
    bindings.value = b.bindings || []
    aliases.value = al.aliases || []
    Object.assign(schema, sc)
  } catch (err) { notifyError(err) }
}

function parseJsonField(text: string): JsonMap {
  const t = (text || '').trim()
  if (!t) return {}
  try { return JSON.parse(t) } catch { throw new Error('JSON 格式不正确') }
}
function safeParseJsonField(text: string): JsonMap {
  try {
    const value = parseJsonField(text)
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
  } catch {
    return {}
  }
}
function rowsToObject(rows: KeyValueRow[], parseValues = false): JsonMap {
  const out: JsonMap = {}
  for (const row of rows) {
    const key = row.key.trim()
    if (!key) continue
    out[key] = parseValues ? parseLooseValue(row.value) : row.value
  }
  return out
}
function objectToRows(value: unknown): KeyValueRow[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return []
  return Object.entries(value as JsonMap).map(([key, raw]) => ({
    key,
    value: typeof raw === 'string' ? raw : JSON.stringify(raw),
  }))
}
function formatTestDuration(result: ModelTestOutcome | null | undefined) {
  const value = Number(result?.duration_ms ?? result?.latency_ms)
  return Number.isFinite(value) ? `${Math.round(value)}ms` : ''
}
function modelTestOutput(result: ModelTestOutcome | null | undefined) {
  if (!result) return null
  if (result.error !== undefined) return result.error
  if (result.answer !== undefined) return result.answer
  if (result.result !== undefined) return result.result
  return null
}
function prettyTestOutput(result: ModelTestOutcome | null | undefined) {
  const payload = modelTestOutput(result)
  if (payload == null) return ''
  if (typeof payload === 'string' || typeof payload === 'number' || typeof payload === 'boolean') return String(payload)
  try {
    return JSON.stringify(payload, null, 2)
  } catch {
    return String(payload)
  }
}
function modelTestStateText(result: ModelTestOutcome | null | undefined) {
  if (!result) return '未测试'
  return result.ok === false || result.error !== undefined ? '测试失败' : '测试通过'
}
function modelTestResult(modelId: string) {
  return modelTestResults.value[String(modelId)] || null
}
function parseLooseValue(value: string): unknown {
  const text = String(value ?? '').trim()
  if (!text) return ''
  if (/^(true|false|null)$/i.test(text) || /^-?\d+(\.\d+)?$/.test(text) || text.startsWith('{') || text.startsWith('[')) {
    try { return JSON.parse(text) } catch { return value }
  }
  return value
}

function providerName(id: string) { return providers.value.find((p) => p.provider_id === id)?.display_name || id }
function compactUrl(base: unknown) {
  return String(base || '').trim().replace(/\/+$/, '')
}
function defaultEndpointPath(providerType: unknown) {
  const type = String(providerType || '').trim()
  if (type === 'ollama') return '/api/chat'
  if (type === 'http_chat') return ''
  if (type === 'dashscope') return '/compatible-mode/v1/chat/completions'
  if (['openai-compatible', 'openai_compatible', 'openai'].includes(type)) return '/chat/completions'
  if (['echo'].includes(type)) return ''
  return ''
}
function defaultEndpointPathForCall(providerType: unknown, callType: unknown) {
  const type = String(providerType || '').trim()
  const call = String(callType || 'generate').trim()
  if (call === 'embed') {
    if (type === 'ollama') return '/api/embeddings'
    if (['openai-compatible', 'openai_compatible', 'openai'].includes(type)) return '/embeddings'
  }
  return defaultEndpointPath(type)
}
function effectiveEndpointPath(provider: JsonMap | null, callType: unknown) {
  return String(provider?.endpoint_path || defaultEndpointPathForCall(provider?.provider_type, callType)).trim()
}
function baseAlreadyHasEndpoint(root: string) {
  return /\/(chat\/completions|embeddings|api\/chat|api\/embeddings)$/i.test(root)
}
function requestUrl(base: unknown, endpointPath: unknown, providerType: unknown) {
  const root = compactUrl(base)
  const path = String(endpointPath || '').trim()
  if (!root) return ''
  if (baseAlreadyHasEndpoint(root) || !path) return root
  const type = String(providerType || '').trim()
  if (['openai-compatible', 'openai_compatible', 'openai'].includes(type) && !/\/v1(\/|$)/.test(root)) {
    return `${root}/v1${path.startsWith('/') ? path : `/${path}`}`
  }
  if (!path) return root
  return `${root}${path.startsWith('/') ? path : `/${path}`}`
}
function maskSecret(value: unknown) {
  const text = String(value || '').trim()
  if (!text) return ''
  if (text.startsWith('env:')) return text
  if (text.length <= 8) return '********'
  return `${text.slice(0, 4)}...${text.slice(-4)}`
}

/* ═══ PROVIDERS ═══ */
const providerEditing = ref(false)
const providerIsNew = ref(false)
const providerForm = reactive({ provider_id: '', display_name: '', provider_type: 'openai-compatible', default_base_url: '', endpoint_path: '', secret_ref: '', timeout_ms: 30000, extra_config: '{}', description: '', status: 'active' })

function modelsForProvider(providerId: string) { return models.value.filter((m) => m.provider_id === providerId) }

function startNewProvider() {
  providerIsNew.value = true
  Object.assign(providerForm, { provider_id: '', display_name: '', provider_type: schema.provider_types[0] || 'openai-compatible', default_base_url: '', endpoint_path: '', secret_ref: '', timeout_ms: 30000, extra_config: '{}', description: '', status: 'active' })
  providerEditing.value = true
}
function startEditProvider(p: JsonMap) {
  providerIsNew.value = false
  Object.assign(providerForm, {
    provider_id: p.provider_id, display_name: p.display_name || '', provider_type: p.provider_type || 'openai-compatible',
    default_base_url: p.default_base_url || '', endpoint_path: p.endpoint_path || '', secret_ref: '', timeout_ms: p.timeout_ms || 30000,
    extra_config: JSON.stringify(p.extra_config || {}, null, 2), description: p.description || '', status: p.status || 'active',
  })
  providerEditing.value = true
}
async function saveProvider() {
  if (!providerForm.provider_id) { notifyError('请填写 provider_id'); return }
  if (!providerForm.display_name) { notifyError('请填写显示名'); return }
  try {
    const body: JsonMap = {
      display_name: providerForm.display_name,
      provider_type: providerForm.provider_type,
      default_base_url: providerForm.default_base_url,
      endpoint_path: providerForm.endpoint_path || null,
      timeout_ms: Number(providerForm.timeout_ms) || 30000,
      extra_config: parseJsonField(providerForm.extra_config),
      description: providerForm.description || null,
      status: providerForm.status,
    }
    if (providerForm.secret_ref) body.secret_ref = providerForm.secret_ref
    if (providerIsNew.value) {
      body.provider_id = providerForm.provider_id
      body.secret_ref = providerForm.secret_ref || ''
      await api('/platform/frontend/models/providers', { method: 'POST', body: JSON.stringify(body) })
    } else {
      await api(`/platform/frontend/models/providers/${encodeURIComponent(providerForm.provider_id)}`, { method: 'PATCH', body: JSON.stringify(body) })
    }
    await refresh()
    providerEditing.value = false
    notifySuccess(`供应商 ${providerForm.provider_id} 已保存`)
  } catch (err) { notifyError(err) }
}
async function toggleProvider(p: JsonMap) {
  const next = p.status === 'active' ? 'disabled' : 'active'
  if (next === 'disabled' && !await confirmDialog(`确定禁用供应商 ${p.provider_id} 吗？其下模型可能无法调用。`, { title: '禁用供应商', danger: true })) return
  try {
    await api(`/platform/frontend/models/providers/${encodeURIComponent(p.provider_id)}`, { method: 'PATCH', body: JSON.stringify({ status: next }) })
    await refresh()
    notifySuccess(`供应商 ${p.provider_id} 已${next === 'active' ? '启用' : '禁用'}`)
  } catch (err) { notifyError(err) }
}
async function deleteProvider(p: JsonMap) {
  if (modelsForProvider(p.provider_id).length) { notifyError('该供应商下仍有模型，请先删除或迁移模型'); return }
  if (!await confirmDialog(`确定删除供应商 ${p.provider_id} 吗？`, { title: '删除供应商', danger: true })) return
  try {
    await api(`/platform/frontend/models/providers/${encodeURIComponent(p.provider_id)}`, { method: 'DELETE' })
    await refresh()
    notifySuccess('供应商已删除')
  } catch (err) { notifyError(err) }
}
function describePing(d: JsonMap) {
  const ok = d.ok !== false
  const detail = d.error || d.message
  return `${ok ? '连接成功' : '连接失败'}${d.duration_ms != null ? ` · ${d.duration_ms}ms` : ''}${detail ? ` · ${detail}` : ''}`
}
async function pingProvider(p: JsonMap) {
  try {
    const d = await api(`/platform/frontend/models/providers/${encodeURIComponent(p.provider_id)}/ping`, { method: 'POST' })
    d.ok !== false ? notifySuccess(describePing(d)) : notifyError(describePing(d))
  } catch (err) { notifyError(err) }
}

/* ═══ MODELS ═══ */
const modelEditing = ref(false)
const modelIsNew = ref(false)
const modelForm = reactive({ model_id: '', display_name: '', provider_id: '', model_name: '', base_url: '', secret_ref: '', timeout_ms: '', model_kind: 'chat', provider_call_type: 'generate', capabilities: [] as string[], context_length: '', dimensions: '', input_price: '', output_price: '', currency: 'USD', description: '', status: 'active' })
const extraHeaderRows = ref<KeyValueRow[]>([])
const extraBodyRows = ref<KeyValueRow[]>([])
const capabilityOptions = computed(() => Array.isArray(schema.model_capabilities) ? schema.model_capabilities as JsonMap[] : [])
const selectedModelProvider = computed(() => providers.value.find((p) => p.provider_id === modelForm.provider_id) || null)
const providerEndpointPath = computed(() => providerForm.endpoint_path.trim() || defaultEndpointPath(providerForm.provider_type))
const providerRequestUrl = computed(() => requestUrl(providerForm.default_base_url, providerEndpointPath.value, providerForm.provider_type))
const providerSecretPreview = computed(() => maskSecret(providerForm.secret_ref))
const modelEffectiveBaseUrl = computed(() => modelForm.base_url.trim() || String(selectedModelProvider.value?.default_base_url || '').trim())
const modelEndpointPath = computed(() => effectiveEndpointPath(selectedModelProvider.value, modelForm.provider_call_type))
const modelRequestUrl = computed(() => requestUrl(modelEffectiveBaseUrl.value, modelEndpointPath.value, selectedModelProvider.value?.provider_type))
const modelSecretSource = computed(() => {
  if (modelForm.secret_ref.trim()) return '模型覆盖'
  if (selectedModelProvider.value?.secret_ref) return '继承供应商'
  return modelIsNew.value ? '未配置' : '留空则沿用已保存密钥'
})
const modelSecretPreview = computed(() => maskSecret(modelForm.secret_ref || selectedModelProvider.value?.secret_ref || ''))
const modelRequestBodyPreview = computed(() => {
  const providerType = String(selectedModelProvider.value?.provider_type || '').trim()
  const model = modelForm.model_name || modelForm.model_id || ''
  const extraBody = rowsToObject(extraBodyRows.value, true)
  if (providerType === 'http_chat') {
    return JSON.stringify({
      model,
      messages: [{ role: 'user', content: 'ping' }],
      prompt: 'ping',
      query: 'ping',
      input: 'ping',
      stream: false,
      ...extraBody,
    }, null, 2)
  }
  if (providerType === 'ollama') {
    return JSON.stringify({
      model,
      messages: [{ role: 'user', content: 'ping' }],
      stream: false,
      ...extraBody,
    }, null, 2)
  }
  return JSON.stringify({
    model,
    messages: [{ role: 'user', content: 'ping' }],
    stream: false,
    ...extraBody,
  }, null, 2)
})
const modelRequestHeadersPreview = computed(() => rowsToObject(extraHeaderRows.value, false))

function addExtraHeaderRow() { extraHeaderRows.value.push({ key: '', value: '' }) }
function removeExtraHeaderRow(index: number) { extraHeaderRows.value.splice(index, 1) }
function addExtraBodyRow() { extraBodyRows.value.push({ key: '', value: '' }) }
function removeExtraBodyRow(index: number) { extraBodyRows.value.splice(index, 1) }
function hasCapability(key: string) { return modelForm.capabilities.includes(key) }
function toggleCapability(key: string) {
  modelForm.capabilities = hasCapability(key)
    ? modelForm.capabilities.filter((value) => value !== key)
    : [...modelForm.capabilities, key]
}

function bindingsForModel(modelId: string) { return bindings.value.filter((b) => b.model_id === modelId) }

function startNewModel(providerId = '') {
  modelIsNew.value = true
  Object.assign(modelForm, { model_id: '', display_name: '', provider_id: providerId || providers.value[0]?.provider_id || '', model_name: '', base_url: '', secret_ref: '', timeout_ms: '', model_kind: schema.model_kinds[0] || 'chat', provider_call_type: schema.provider_call_types[0] || 'generate', capabilities: [], context_length: '', dimensions: '', input_price: '', output_price: '', currency: 'USD', description: '', status: 'active' })
  extraHeaderRows.value = []
  extraBodyRows.value = []
  modelEditing.value = true
}
function startEditModel(m: JsonMap) {
  modelIsNew.value = false
  const pricing = m.pricing_json && typeof m.pricing_json === 'object' ? m.pricing_json as JsonMap : {}
  Object.assign(modelForm, {
    model_id: m.model_id, display_name: m.display_name || '', provider_id: m.provider_id || '',
    model_name: m.model_name || '', base_url: m.base_url || '', secret_ref: '',
    timeout_ms: m.timeout_ms ?? '', model_kind: m.model_kind || 'chat', provider_call_type: m.provider_call_type || 'generate',
    capabilities: Array.isArray(m.capabilities) ? m.capabilities.map(String) : [], context_length: m.context_length ?? '', dimensions: m.dimensions ?? '',
    input_price: pricing.input_per_million_tokens ?? pricing.input ?? '',
    output_price: pricing.output_per_million_tokens ?? pricing.output ?? '',
    currency: pricing.currency || 'USD',
    description: m.description || '', status: m.status || 'active',
  })
  extraHeaderRows.value = objectToRows(m.extra_headers)
  extraBodyRows.value = objectToRows(m.extra_body)
  modelEditing.value = true
}
async function saveModel() {
  if (!modelForm.model_id) { notifyError('请填写 model_id'); return }
  if (!modelForm.display_name) { notifyError('请填写显示名'); return }
  if (!modelForm.provider_id) { notifyError('请选择供应商'); return }
  if (!modelForm.model_name) { notifyError('请填写上游模型名'); return }
  try {
    const body: JsonMap = {
      display_name: modelForm.display_name,
      provider_id: modelForm.provider_id,
      model_name: modelForm.model_name,
      base_url: modelForm.base_url,
      timeout_ms: modelForm.timeout_ms ? Number(modelForm.timeout_ms) : null,
      model_kind: modelForm.model_kind,
      provider_call_type: modelForm.provider_call_type,
      kind: modelForm.model_kind,
      capabilities: modelForm.capabilities,
      context_length: modelForm.context_length ? Number(modelForm.context_length) : null,
      dimensions: modelForm.dimensions ? Number(modelForm.dimensions) : null,
      pricing_json: {
        currency: modelForm.currency || 'USD',
        unit: '1M tokens',
        input_per_million_tokens: modelForm.input_price ? Number(modelForm.input_price) : null,
        output_per_million_tokens: modelForm.output_price ? Number(modelForm.output_price) : null,
      },
      extra_headers: rowsToObject(extraHeaderRows.value, false),
      extra_body: rowsToObject(extraBodyRows.value, true),
      description: modelForm.description || null,
      status: modelForm.status,
    }
    if (modelForm.secret_ref) body.secret_ref = modelForm.secret_ref
    if (modelIsNew.value) {
      body.model_id = modelForm.model_id
      body.secret_ref = modelForm.secret_ref || ''
      await api('/platform/frontend/models', { method: 'POST', body: JSON.stringify(body) })
    } else {
      await api(`/platform/frontend/models/${encodeURIComponent(modelForm.model_id)}`, { method: 'PATCH', body: JSON.stringify(body) })
    }
    await refresh()
    modelEditing.value = false
    notifySuccess(`模型 ${modelForm.model_id} 已保存`)
  } catch (err) { notifyError(err) }
}
async function toggleModel(m: JsonMap) {
  const next = m.status === 'active' ? 'disabled' : 'active'
  if (next === 'disabled') {
    const related = bindingsForModel(m.model_id).length
    if (!await confirmDialog(`确定禁用模型 ${m.model_id} 吗？${related ? `\n受影响插槽绑定: ${related}` : ''}`, { title: '禁用模型', danger: true })) return
  }
  try {
    await api(`/platform/frontend/models/${encodeURIComponent(m.model_id)}`, { method: 'PATCH', body: JSON.stringify({ status: next }) })
    await refresh()
    notifySuccess(`模型 ${m.model_id} 已${next === 'active' ? '启用' : '禁用'}`)
  } catch (err) { notifyError(err) }
}
async function deleteModel(m: JsonMap) {
  const related = bindingsForModel(m.model_id).length
  if (!await confirmDialog(`确定删除模型 ${m.model_id} 吗？${related ? `\n会一并解除 ${related} 个插槽绑定。` : ''}`, { title: '删除模型', danger: true })) return
  try {
    await api(`/platform/frontend/models/${encodeURIComponent(m.model_id)}`, { method: 'DELETE' })
    await refresh()
    notifySuccess('模型已删除')
  } catch (err) { notifyError(err) }
}
async function pingModel(m: JsonMap) {
  const modelId = String(m.model_id)
  if (pingingModels.value[modelId]) return
  pingingModels.value = { ...pingingModels.value, [modelId]: true }
  try {
    const d = await api(`/platform/frontend/models/${encodeURIComponent(modelId)}/ping`, { method: 'POST' })
    d.ok !== false ? notifySuccess(describePing(d)) : notifyError(describePing(d))
  } catch (err) { notifyError(err) }
  finally {
    pingingModels.value = { ...pingingModels.value, [modelId]: false }
  }
}
async function testModel(m: JsonMap) {
  let body: JsonMap
  if (m.provider_call_type === 'embed') {
    const input = await promptDialog('测试 Embedding', '输入文本', 'ping')
    if (input === null) return
    body = { input }
  } else if (m.provider_call_type === 'rerank') {
    const values = await formDialog({
      title: '测试 Rerank',
      fields: [
        { key: 'query', label: 'Query', default: 'ping' },
        { key: 'documents', label: '候选文档（每行一个）', type: 'textarea', default: 'document a\ndocument b' },
      ],
    })
    if (!values) return
    body = { query: values.query, documents: (values.documents || '').split('\n').map((s) => s.trim()).filter(Boolean) }
  } else {
    const vision = (m.capabilities || []).map(String).some((capability: string) => capability.toLowerCase() === 'vision')
    if (vision) {
      const values = await formDialog({
        title: '测试视觉模型',
        message: '视觉模型支持纯文本测试；如果需要验证图片理解，再选择“文字 + 图片”。',
        fields: [
          { key: 'prompt', label: 'Prompt', type: 'textarea', default: '请简单介绍一下你自己。' },
          {
            key: 'input_mode',
            label: '输入方式',
            type: 'select',
            default: 'text',
            options: [
              { value: 'text', label: '仅文字（不上传图片）' },
              { value: 'image', label: '文字 + 图片' },
            ],
          },
        ],
      })
      if (!values) return
      const prompt = String(values.prompt || '').trim() || 'ping'
      if (values.input_mode === 'image') {
        pendingVisionTest.value = { model: m, prompt }
        visionTestInputEl.value?.click()
        return
      }
      await submitModelTest(m, { prompt, max_tokens: 256 })
      return
    }
    const input = await promptDialog('测试模型', 'Prompt', 'ping')
    if (input === null) return
    body = { prompt: input, max_tokens: 256 }
  }
  await submitModelTest(m, body)
}

async function onVisionTestImage(ev: Event) {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  const pending = pendingVisionTest.value
  pendingVisionTest.value = null
  if (!pending || !files.length) return
  try {
    const images = await Promise.all(files.map(async (file) => {
      if (!file.type.startsWith('image/')) throw new Error(`${file.name} 不是图片文件`)
      const dataUrl = await readFileAsDataUrl(file)
      const comma = dataUrl.indexOf(',')
      if (comma < 0) throw new Error(`无法读取 ${file.name}`)
      return { data: dataUrl.slice(comma + 1), media_type: file.type }
    }))
    await submitModelTest(pending.model, { prompt: pending.prompt, images, max_tokens: 256 })
  } catch (err) {
    notifyError(err)
  }
}

function readFileAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result || ''))
    reader.onerror = () => reject(reader.error || new Error(`无法读取 ${file.name}`))
    reader.readAsDataURL(file)
  })
}

async function submitModelTest(m: JsonMap, body: JsonMap) {
  const modelId = String(m.model_id)
  if (testingModels.value[modelId]) return
  testingModels.value = { ...testingModels.value, [modelId]: true }
  try {
    const result = await api(`/platform/frontend/models/${encodeURIComponent(modelId)}/test`, { method: 'POST', body: JSON.stringify(body) })
    modelTestResults.value = { ...modelTestResults.value, [modelId]: result }
    if (result?.ok === false) {
      notifyError(`${modelId} 测试失败：${result.error || '未知错误'}`)
    } else {
      notifySuccess('测试完成')
    }
  } catch (err) {
    const failed: ModelTestOutcome = { ok: false, error: String(err) }
    modelTestResults.value = { ...modelTestResults.value, [modelId]: failed }
    notifyError(err)
  } finally {
    testingModels.value = { ...testingModels.value, [modelId]: false }
  }
}

/* ═══ SLOTS（仅平台默认绑定）═══ */
function slotDef(slotKey: string) { return slots.value.find((s) => s.slot_key === slotKey) || null }
function platformBinding(slotKey: string) { return bindings.value.find((b) => b.slot_key === slotKey && b.scope === 'platform' && !b.org_id) || null }
function modelMatchesSlot(model: JsonMap, slot: JsonMap | null) {
  if (!slot) return true
  if (String(model.model_kind || '') !== String(slot.model_kind || '')) return false
  if (String(model.provider_call_type || '') !== String(slot.provider_call_type || '')) return false
  const caps = new Set((model.capabilities || []).map(String))
  return (slot.required_capabilities || []).every((c: string) => caps.has(String(c)))
}
function compatibleModels(slotKey: string) {
  const slot = slotDef(slotKey)
  return models.value.filter((m) => m.status === 'active' && modelMatchesSlot(m, slot))
}
async function bindSlot(slotKey: string) {
  const compatible = compatibleModels(slotKey)
  if (!compatible.length) { notifyError('没有兼容该插槽的可用模型，请先在「模型目录」新增/启用对应类型的模型'); return }
  const current = platformBinding(slotKey)
  const values = await formDialog({
    title: `配置插槽 · ${slotDef(slotKey)?.display_name || slotKey}`,
    confirmLabel: '保存',
    fields: [
      { key: 'model_id', label: '平台默认模型', type: 'select', default: String(current?.model_id || compatible[0].model_id), options: compatible.map((m) => ({ value: String(m.model_id), label: `${m.display_name || m.model_id}` })) },
    ],
  })
  if (!values || !values.model_id) return
  try {
    await api(`/platform/frontend/models/slots/${encodeURIComponent(slotKey)}/platform/_`, { method: 'PUT', body: JSON.stringify({ model_id: values.model_id, org_id: '', workspace_id: '', project_id: '', fallback_enabled: false }) })
    await refresh()
    notifySuccess(`已配置 ${slotKey} → ${values.model_id}`)
  } catch (err) { notifyError(err) }
}
async function clearSlot(slotKey: string) {
  const b = platformBinding(slotKey)
  if (!b) return
  if (!await confirmDialog(`确定清除插槽「${slotDef(slotKey)?.display_name || slotKey}」的默认模型吗？`, { title: '清除插槽', danger: true })) return
  try {
    const qs = new URLSearchParams({ org_id: '', workspace_id: '', project_id: '' })
    await api(`/platform/frontend/models/slots/${encodeURIComponent(slotKey)}/platform/_?${qs}`, { method: 'DELETE' })
    await refresh()
    notifySuccess('已清除插槽默认模型')
  } catch (err) { notifyError(err) }
}

/* ═══ 自定义模型插槽（domain slot）═══ */
const aliasOpen = ref(false)
const aliasForm = reactive({ alias_name: '', domain: '', target_slot_key: '', model_id: '', org_id: '', description: '' })
const fixedSlots = computed(() => slots.value.filter((s) => !s.is_custom))
const aliasModelOptions = computed(() => aliasForm.target_slot_key ? compatibleModels(aliasForm.target_slot_key) : models.value.filter((m) => m.status === 'active'))
function openAliasForm() {
  Object.assign(aliasForm, { alias_name: '', domain: domainOptions.value[0]?.domain || '', target_slot_key: String(fixedSlots.value[0]?.slot_key || slots.value[0]?.slot_key || 'qa'), model_id: '', org_id: '', description: '' })
  aliasOpen.value = true
}
async function saveAlias() {
  if (!aliasForm.alias_name.trim()) { notifyError('请填写插槽名'); return }
  if (!aliasForm.domain) { notifyError('请选择业务域'); return }
  if (!aliasForm.model_id) { notifyError('请选择绑定模型'); return }
  try {
    await api('/platform/frontend/models/aliases', { method: 'POST', body: JSON.stringify({ alias_name: aliasForm.alias_name.trim(), domain: aliasForm.domain, target_slot_key: aliasForm.target_slot_key, model_id: aliasForm.model_id, org_id: aliasForm.org_id.trim(), description: aliasForm.description.trim() }) })
    aliasOpen.value = false
    await refresh()
    notifySuccess(`自定义插槽 ${aliasForm.alias_name} 已保存`)
  } catch (err) { notifyError(err) }
}
async function deleteAlias(a: JsonMap) {
  if (!await confirmDialog(`确定删除自定义插槽 ${a.alias_name} 吗？`, { title: '删除自定义插槽', danger: true })) return
  try {
    await api(`/platform/frontend/models/aliases/${a.id}`, { method: 'DELETE' })
    await refresh()
    notifySuccess('已删除')
  } catch (err) { notifyError(err) }
}

/* ═══ RESOLVE ═══ */
const resolveForm = reactive({ slot: 'qa', org_id: '' })
const resolveResult = ref<JsonMap | null>(null)
async function doResolve() {
  if (!resolveForm.slot) { notifyError('请选择插槽'); return }
  try {
    const p = new URLSearchParams({ slot: resolveForm.slot })
    if (resolveForm.org_id) p.set('org_id', resolveForm.org_id)
    const d = await api(`/platform/frontend/models/resolve?${p}`)
    resolveResult.value = d.resolved || d
  } catch (err) { notifyError(err); resolveResult.value = null }
}

/* ═══ AUDIT ═══ */
const auditFilter = reactive({ model_id: '', slot_key: '', limit: '100' })
async function loadAudit() {
  try {
    const p = new URLSearchParams({ limit: auditFilter.limit || '100' })
    if (auditFilter.model_id) p.set('model_id', auditFilter.model_id)
    if (auditFilter.slot_key) p.set('slot_key', auditFilter.slot_key)
    const d = await api(`/platform/frontend/models/audit?${p}`)
    audit.value = d.events || []
  } catch (err) { notifyError(err) }
}

function onTabClick(t: Tab) { tab.value = t; if (t === 'audit') loadAudit() }
onMounted(() => { refresh(); loadDomains() })
</script>

<template>
  <div class="models-page">
    <input ref="visionTestInputEl" type="file" accept="image/*" multiple class="hidden" @change="onVisionTestImage" />
    <div class="stats">
      <div class="stat"><div class="stat-label">供应商</div><div class="stat-val">{{ providers.length }}</div><div class="stat-sub">{{ activeProviders }} 启用</div></div>
      <div class="stat"><div class="stat-label">模型</div><div class="stat-val">{{ models.length }}</div><div class="stat-sub">{{ activeModels }} 启用</div></div>
      <div class="stat"><div class="stat-label">插槽</div><div class="stat-val">{{ slots.length }}</div><div class="stat-sub">{{ configuredSlots }} 已配置默认</div></div>
    </div>

    <div class="tab-bar inline">
      <button v-for="t in tabs" :key="t.key" class="tab-btn" :class="{ active: tab === t.key }" @click="onTabClick(t.key)">{{ t.label }}</button>
      <span style="flex:1"></span>
      <button class="btn btn-ghost btn-sm" @click="refresh">刷新</button>
    </div>

    <!-- ═══ 供应商 ═══ -->
    <section v-if="tab === 'providers'">
      <div class="sec-head"><h3>供应商</h3><button class="btn btn-primary btn-sm" @click="startNewProvider">+ 新增供应商</button></div>
      <div class="card-grid">
        <article v-for="p in providers" :key="p.provider_id as string" class="m-card" :class="{ off: p.status !== 'active' }">
          <div class="m-card-head">
            <div class="m-icon">🔌</div>
            <div class="m-card-id">
              <div class="m-name">{{ p.display_name || p.provider_id }}</div>
              <div class="m-sub">{{ p.provider_id }}</div>
            </div>
            <span class="badge" :class="p.status === 'active' ? 'badge-green' : 'badge-gray'">{{ p.status === 'active' ? '已启用' : '已停用' }}</span>
            <div class="model-toggle" @click.stop>
              <label class="toggle" :title="p.status === 'active' ? '点击停用' : '点击启用'">
                <input type="checkbox" :checked="p.status === 'active'" @click.stop @change.stop="toggleProvider(p)" />
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
          <div class="m-meta">
            <span class="tag tag-type">{{ p.provider_type }}</span>
            <span class="tag">{{ modelsForProvider(String(p.provider_id)).length }} 个模型</span>
            <span v-if="p.timeout_ms" class="tag">{{ p.timeout_ms }}ms</span>
          </div>
          <div v-if="p.default_base_url" class="m-line">{{ p.default_base_url }}</div>
          <div v-if="p.description" class="m-desc">{{ p.description }}</div>
          <div class="m-actions">
            <button class="btn btn-ghost btn-sm" @click="pingProvider(p)">测试连接</button>
            <button class="btn btn-ghost btn-sm" @click="startNewModel(String(p.provider_id))">加模型</button>
            <button class="btn btn-ghost btn-sm" @click="startEditProvider(p)">编辑</button>
            <button class="btn btn-danger btn-sm" @click="deleteProvider(p)">删除</button>
          </div>
        </article>
        <div v-if="!providers.length" class="empty">还没有供应商，点击「新增供应商」开始接入。</div>
      </div>
    </section>

    <!-- ═══ 模型目录 ═══ -->
    <section v-else-if="tab === 'models'">
      <div class="sec-head"><h3>模型目录</h3><button class="btn btn-primary btn-sm" @click="startNewModel()">+ 新增模型</button></div>
      <div class="card-grid">
        <article v-for="m in models" :key="m.model_id as string" class="m-card" :class="{ off: m.status !== 'active' }">
          <div class="m-card-head">
            <div class="m-icon">🧠</div>
            <div class="m-card-id">
              <div class="m-name">{{ m.display_name || m.model_id }}</div>
              <div class="m-sub">{{ m.model_id }}</div>
            </div>
            <span class="badge" :class="m.status === 'active' ? 'badge-green' : 'badge-gray'">{{ m.status === 'active' ? '已启用' : '已停用' }}</span>
            <div class="model-toggle" @click.stop>
              <label class="toggle" :title="m.status === 'active' ? '点击停用' : '点击启用'">
                <input type="checkbox" :checked="m.status === 'active'" @click.stop @change.stop="toggleModel(m)" />
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
          <div class="m-meta">
            <span class="tag tag-kind">{{ m.model_kind }}</span>
            <span class="tag">{{ m.provider_call_type }}</span>
            <span class="tag">{{ providerName(String(m.provider_id)) }}</span>
            <span v-if="bindingsForModel(String(m.model_id)).length" class="tag tag-bound">绑定 {{ bindingsForModel(String(m.model_id)).length }} 插槽</span>
          </div>
          <div class="m-line">{{ m.model_name }}<template v-if="m.effective_base_url"> · {{ m.effective_base_url }}</template></div>
          <div v-if="(m.capabilities || []).length" class="m-caps">
            <span v-for="c in (m.capabilities as string[])" :key="c" class="cap">{{ c }}</span>
          </div>
          <div class="m-actions">
            <button class="btn btn-ghost btn-sm" :disabled="Boolean(pingingModels[String(m.model_id)])" @click="pingModel(m)">{{ pingingModels[String(m.model_id)] ? 'Ping中…' : 'Ping' }}</button>
            <button class="btn btn-ghost btn-sm" :disabled="Boolean(testingModels[String(m.model_id)])" @click="testModel(m)">{{ testingModels[String(m.model_id)] ? '测试中…' : '测试' }}</button>
            <button class="btn btn-ghost btn-sm" @click="startEditModel(m)">编辑</button>
            <button class="btn btn-danger btn-sm" @click="deleteModel(m)">删除</button>
          </div>
          <div v-if="modelTestResult(String(m.model_id))" class="model-test">
            <div class="m-test-head">
              <span class="tag" :class="(modelTestResult(String(m.model_id))?.ok === false || modelTestResult(String(m.model_id))?.error) ? 'badge-gray' : 'tag-kind'">{{ modelTestStateText(modelTestResult(String(m.model_id))) }}</span>
              <span v-if="formatTestDuration(modelTestResult(String(m.model_id)))" class="tag">{{ formatTestDuration(modelTestResult(String(m.model_id))) }}</span>
            </div>
            <pre v-if="prettyTestOutput(modelTestResult(String(m.model_id)))" class="json-box">{{ prettyTestOutput(modelTestResult(String(m.model_id))) }}</pre>
          </div>
        </article>
        <div v-if="!models.length" class="empty">还没有模型，点击「新增模型」或在供应商卡片上「加模型」。</div>
      </div>
    </section>

    <!-- ═══ 模型插槽（仅平台默认）═══ -->
    <section v-else-if="tab === 'slots'">
      <div class="sec-head"><h3>模型插槽</h3><span class="sec-hint">每个能力插槽配置一个平台默认模型；Agent 的个性化模型覆盖在「Agent 管理 → 模型策略」里设置。</span></div>
      <div class="card-grid slot-grid">
        <article v-for="s in slots" :key="s.slot_key as string" class="m-card slot-card" :class="{ warn: !platformBinding(String(s.slot_key)) }">
          <div class="m-card-head">
            <div class="m-icon">{{ platformBinding(String(s.slot_key)) ? '🎯' : '➖' }}</div>
            <div class="m-card-id">
              <div class="m-name">{{ s.display_name || s.slot_key }}</div>
              <div class="m-sub">{{ s.slot_key }}</div>
            </div>
            <span class="badge" :class="s.is_custom ? 'badge-blue' : 'badge-gray'">{{ s.is_custom ? '自定义' : '固定' }}</span>
          </div>
          <div class="m-meta">
            <span class="tag tag-kind">{{ s.model_kind }}</span>
            <span class="tag">{{ s.provider_call_type }}</span>
            <span v-for="c in (s.required_capabilities as string[] || [])" :key="c" class="tag">需 {{ c }}</span>
          </div>
          <div v-if="s.description" class="m-desc">{{ s.description }}</div>
          <div class="slot-bind" :class="{ empty: !platformBinding(String(s.slot_key)) }">
            <template v-if="platformBinding(String(s.slot_key))">
              <span class="slot-bind-label">默认模型</span>
              <span class="slot-bind-model">{{ providerName(String(platformBinding(String(s.slot_key))!.model_id)) || platformBinding(String(s.slot_key))!.model_id }}</span>
            </template>
            <span v-else class="slot-bind-none">未配置默认模型</span>
          </div>
          <div class="m-actions">
            <button class="btn btn-primary btn-sm" @click="bindSlot(String(s.slot_key))">{{ platformBinding(String(s.slot_key)) ? '更换模型' : '配置模型' }}</button>
            <button v-if="platformBinding(String(s.slot_key))" class="btn btn-ghost btn-sm" @click="clearSlot(String(s.slot_key))">清除</button>
          </div>
        </article>
        <div v-if="!slots.length" class="empty">暂无插槽定义。</div>
      </div>

      <div class="sec-head custom-head">
        <div><h3>自定义模型插槽</h3><span class="sec-hint">为业务域新增可选 slot key（如 coach_large / judge_fast），指定底层能力类型并绑定具体模型；Agent 配置时选择这个 slot。</span></div>
        <button class="btn btn-primary btn-sm" :disabled="!domainOptions.length" @click="openAliasForm">+ 新增自定义插槽</button>
      </div>
      <div class="card-grid slot-grid">
        <article v-for="a in aliases" :key="a.id as string" class="m-card slot-card custom">
          <div class="m-card-head">
            <div class="m-icon">🧩</div>
            <div class="m-card-id"><div class="m-name">{{ a.alias_name }}</div><div class="m-sub">{{ a.domain }} → {{ a.target_slot_key || 'qa' }}</div></div>
            <button class="btn btn-danger btn-sm" @click="deleteAlias(a)">删除</button>
          </div>
          <div class="m-meta">
            <span v-if="a.model_kind" class="tag tag-kind">{{ a.model_kind }}</span>
            <span class="tag">{{ a.org_id || '全局' }}</span>
          </div>
          <div class="slot-bind">
            <span class="slot-bind-label">绑定模型</span>
            <span class="slot-bind-model">{{ a.model_display_name || a.model_id }}</span>
          </div>
          <div v-if="a.description" class="m-desc">{{ a.description }}</div>
        </article>
        <div v-if="!aliases.length" class="empty">暂无自定义模型插槽{{ domainOptions.length ? '，点右上「新增自定义插槽」添加。' : '（需要先有业务域）。' }}</div>
      </div>
    </section>

    <!-- ═══ 解析预览 ═══ -->
    <section v-else-if="tab === 'resolve'">
      <div class="sec-head"><h3>解析预览</h3><span class="sec-hint">查看某个插槽最终会解析到哪个模型。</span></div>
      <div class="resolve-layout">
        <div class="panel resolve-form">
          <div class="field"><label>插槽</label>
            <select v-model="resolveForm.slot">
              <option v-for="s in slots" :key="s.slot_key as string" :value="s.slot_key">{{ s.display_name || s.slot_key }}</option>
            </select>
          </div>
          <div class="field"><label>Org ID（可选）</label><input v-model="resolveForm.org_id" placeholder="留空 = 平台默认" /></div>
          <button class="btn btn-primary" @click="doResolve">解析</button>
        </div>
        <div class="panel resolve-result">
          <div class="sec-head"><h3>解析结果</h3><span v-if="resolveResult" class="badge" :class="resolveResult.model_id ? 'badge-green' : 'badge-red'">{{ resolveResult.model_id ? '已命中' : '未配置' }}</span></div>
          <div v-if="!resolveResult" class="empty">选择插槽后点击「解析」。</div>
          <template v-else>
            <div class="detail-grid">
              <div class="detail-key">插槽</div><div class="detail-val">{{ resolveResult.slot_key }}</div>
              <div class="detail-key">模型</div><div class="detail-val">{{ resolveResult.display_name || resolveResult.model_id || '—' }}</div>
              <div class="detail-key">命中作用域</div><div class="detail-val">{{ resolveResult.scope || '—' }}</div>
              <div class="detail-key">供应商</div><div class="detail-val">{{ resolveResult.provider_id || '—' }}</div>
              <div class="detail-key">上游模型</div><div class="detail-val">{{ resolveResult.model_name || '—' }}</div>
              <div class="detail-key">Base URL</div><div class="detail-val">{{ resolveResult.base_url || '—' }}</div>
            </div>
            <details><summary>原始响应</summary><pre class="json-box">{{ JSON.stringify(resolveResult, null, 2) }}</pre></details>
          </template>
        </div>
      </div>
    </section>

    <!-- ═══ 审计 ═══ -->
    <section v-else>
      <div class="sec-head"><h3>审计日志</h3></div>
      <div class="panel">
        <div class="toolbar">
          <div class="field"><label>Model ID</label><input v-model="auditFilter.model_id" placeholder="可选" /></div>
          <div class="field"><label>Slot Key</label><input v-model="auditFilter.slot_key" placeholder="可选" /></div>
          <div class="field"><label>条数</label><input v-model="auditFilter.limit" type="number" min="1" /></div>
          <button class="btn btn-primary btn-sm" @click="loadAudit">查询</button>
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>时间</th><th>事件</th><th>目标</th><th>Model</th><th>操作者</th></tr></thead>
            <tbody>
              <tr v-if="!audit.length"><td colspan="5" class="empty">暂无审计记录</td></tr>
              <tr v-for="(a, i) in audit" :key="i">
                <td>{{ fmtDate(a.created_at || a.ts) }}</td>
                <td><strong>{{ a.event_type || a.action || a.event }}</strong></td>
                <td>{{ a.slot_key || a.target_id || '—' }}</td>
                <td class="mono">{{ a.model_id || '—' }}</td>
                <td>{{ a.actor || a.user_id || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <!-- ═══ 供应商编辑弹窗 ═══ -->
    <div v-if="providerEditing" class="modal-backdrop" @click.self="providerEditing = false">
      <div class="modal">
        <div class="modal-title">{{ providerIsNew ? '新增供应商' : '编辑供应商' }}</div>
        <div class="form-row">
          <div class="form-group"><label>Provider ID *</label><input v-model="providerForm.provider_id" :disabled="!providerIsNew" placeholder="primary_llm" /></div>
          <div class="form-group"><label>显示名 *</label><input v-model="providerForm.display_name" placeholder="主力模型服务" /></div>
        </div>
        <div class="form-row">
          <div class="form-group"><label>类型</label><select v-model="providerForm.provider_type"><option v-for="t in schema.provider_types" :key="t" :value="t">{{ t }}</option></select></div>
          <div class="form-group"><label>状态</label><select v-model="providerForm.status"><option v-for="s in schema.statuses" :key="s" :value="s">{{ s }}</option></select></div>
        </div>
        <div class="form-row">
          <div class="form-group"><label>Base URL</label><input v-model="providerForm.default_base_url" placeholder="https://host 或 https://host/v1" /></div>
          <div class="form-group"><label>Endpoint Path（留空=按类型默认）</label><input v-model="providerForm.endpoint_path" :placeholder="defaultEndpointPath(providerForm.provider_type)" /></div>
        </div>
        <div class="endpoint-preview">
          <div class="endpoint-preview-title">供应商默认请求</div>
          <div class="endpoint-preview-row">
            <span>URL</span>
            <strong :class="{ warn: !providerRequestUrl }">POST {{ providerRequestUrl || '未配置 Base URL' }}</strong>
          </div>
          <div class="endpoint-preview-row">
            <span>Path</span>
            <strong>{{ providerEndpointPath }} · {{ providerForm.endpoint_path ? '手动配置' : '按类型默认' }}</strong>
          </div>
          <div class="endpoint-preview-row">
            <span>Auth</span>
            <strong>{{ providerSecretPreview ? `Bearer ${providerSecretPreview}` : providerIsNew ? '未配置' : '留空则不修改已保存密钥' }}</strong>
          </div>
        </div>
        <div class="form-row">
          <div class="form-group"><label>Secret Ref{{ providerIsNew ? '' : '（留空=不修改）' }}</label><input v-model="providerForm.secret_ref" type="password" placeholder="密钥引用" /></div>
          <div class="form-group"><label>超时 (ms)</label><input v-model.number="providerForm.timeout_ms" type="number" /></div>
        </div>
        <div class="form-group"><label>描述</label><input v-model="providerForm.description" /></div>
        <div class="form-group"><label>额外配置 (JSON)</label><textarea v-model="providerForm.extra_config" rows="3" class="mono" /></div>
        <div class="modal-actions">
          <button class="btn btn-ghost" @click="providerEditing = false">取消</button>
          <button class="btn btn-primary" @click="saveProvider">保存</button>
        </div>
      </div>
    </div>

    <!-- ═══ 模型编辑弹窗 ═══ -->
    <div v-if="modelEditing" class="modal-backdrop" @click.self="modelEditing = false">
      <div class="modal">
        <div class="modal-title">{{ modelIsNew ? '新增模型' : '编辑模型' }}</div>
        <div class="form-row">
          <div class="form-group"><label>Model ID *</label><input v-model="modelForm.model_id" :disabled="!modelIsNew" placeholder="qwen3_30b" /></div>
          <div class="form-group"><label>显示名 *</label><input v-model="modelForm.display_name" placeholder="Qwen3 30B" /></div>
        </div>
        <div class="form-row">
          <div class="form-group"><label>供应商 *</label><select v-model="modelForm.provider_id"><option v-for="p in providers" :key="p.provider_id as string" :value="p.provider_id">{{ p.display_name || p.provider_id }}</option></select></div>
          <div class="form-group"><label>上游模型名 *</label><input v-model="modelForm.model_name" placeholder="qwen3-30b" /></div>
        </div>
        <div class="form-row">
          <div class="form-group"><label>类型</label><select v-model="modelForm.model_kind"><option v-for="k in schema.model_kinds" :key="k" :value="k">{{ k }}</option></select></div>
          <div class="form-group"><label>调用类型</label><select v-model="modelForm.provider_call_type"><option v-for="c in schema.provider_call_types" :key="c" :value="c">{{ c }}</option></select></div>
          <div class="form-group"><label>状态</label><select v-model="modelForm.status"><option v-for="s in schema.statuses" :key="s" :value="s">{{ s }}</option></select></div>
        </div>
        <div class="form-row">
          <div class="form-group"><label>Base URL 覆盖</label><input v-model="modelForm.base_url" placeholder="留空=继承供应商" /></div>
          <div class="form-group"><label>Secret 覆盖</label><input v-model="modelForm.secret_ref" type="password" placeholder="留空=继承/不改" /></div>
          <div class="form-group"><label>超时覆盖 (ms)</label><input v-model="modelForm.timeout_ms" type="number" placeholder="继承" /></div>
        </div>
        <div class="endpoint-preview">
          <div class="endpoint-preview-title">最终调用预览</div>
          <div class="endpoint-preview-row">
            <span>URL</span>
            <strong :class="{ warn: !modelRequestUrl }">POST {{ modelRequestUrl || '未配置 Base URL' }}</strong>
          </div>
          <div class="endpoint-preview-row">
            <span>Path</span>
            <strong>{{ modelEndpointPath }} · 来自供应商类型/配置</strong>
          </div>
          <div class="endpoint-preview-row">
            <span>Provider</span>
            <strong>{{ selectedModelProvider?.display_name || selectedModelProvider?.provider_id || '未选择' }}</strong>
          </div>
          <div class="endpoint-preview-row">
            <span>Auth</span>
            <strong>{{ modelSecretPreview ? `Bearer ${modelSecretPreview}` : modelSecretSource }} · {{ modelSecretSource }}</strong>
          </div>
          <details v-if="Object.keys(modelRequestHeadersPreview).length" class="endpoint-body">
            <summary>附加 Header 预览</summary>
            <pre>{{ JSON.stringify(modelRequestHeadersPreview, null, 2) }}</pre>
          </details>
          <details class="endpoint-body" open>
            <summary>请求体预览</summary>
            <pre>{{ modelRequestBodyPreview }}</pre>
          </details>
        </div>
        <div class="form-row">
          <div class="form-group wide"><label>模型能力</label><div class="capability-picker"><button v-for="cap in capabilityOptions" :key="String(cap.key)" type="button" class="capability-option" :class="{ selected: hasCapability(String(cap.key)) }" :title="String(cap.description || '')" @click="toggleCapability(String(cap.key))"><span class="capability-check">{{ hasCapability(String(cap.key)) ? '✓' : '' }}</span><span>{{ cap.label || cap.key }}</span></button></div><div class="field-hint">能力用于插槽匹配和运行时路由；例如 VLM 插槽要求“视觉理解”。</div></div>
          <div class="form-group"><label>上下文长度</label><input v-model="modelForm.context_length" type="number" /></div>
          <div class="form-group"><label>向量维度</label><input v-model="modelForm.dimensions" type="number" placeholder="Embedding 模型必填/默认" /></div>
        </div>
        <div class="form-group"><label>描述</label><input v-model="modelForm.description" /></div>
        <div class="form-row">
          <div class="form-group"><label>输入价格 / 1M tokens</label><input v-model="modelForm.input_price" type="number" step="0.000001" placeholder="例如 0.14" /></div>
          <div class="form-group"><label>输出价格 / 1M tokens</label><input v-model="modelForm.output_price" type="number" step="0.000001" placeholder="例如 0.28" /></div>
          <div class="form-group"><label>币种</label><input v-model="modelForm.currency" placeholder="USD / CNY" /></div>
        </div>
        <div class="kv-editor">
          <div class="kv-head">
            <div>
              <strong>附加 Header</strong>
              <span>用于传递厂商要求的租户、版本、路由等请求头；Authorization 已由密钥单独处理。</span>
            </div>
            <button class="btn btn-sm btn-ghost" @click="addExtraHeaderRow">新增 Header</button>
          </div>
          <div v-if="!extraHeaderRows.length" class="kv-empty">暂无附加 Header</div>
          <div v-for="(row, index) in extraHeaderRows" :key="`h-${index}`" class="kv-row">
            <input v-model="row.key" placeholder="Header 名，例如 X-Tenant-ID" />
            <input v-model="row.value" placeholder="Header 值" />
            <button class="btn btn-sm btn-ghost" @click="removeExtraHeaderRow(index)">删除</button>
          </div>
        </div>
        <div class="kv-editor">
          <div class="kv-head">
            <div>
              <strong>附加 Body 参数</strong>
              <span>会合并到最终请求体里；例如 temperature、top_p、thinking。值支持数字、true/false、对象或数组。</span>
            </div>
            <button class="btn btn-sm btn-ghost" @click="addExtraBodyRow">新增参数</button>
          </div>
          <div v-if="!extraBodyRows.length" class="kv-empty">暂无附加 Body 参数</div>
          <div v-for="(row, index) in extraBodyRows" :key="`b-${index}`" class="kv-row">
            <input v-model="row.key" placeholder="参数名，例如 temperature" />
            <input v-model="row.value" placeholder="参数值，例如 0.7 / false / {&quot;type&quot;:&quot;disabled&quot;}" />
            <button class="btn btn-sm btn-ghost" @click="removeExtraBodyRow(index)">删除</button>
          </div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-ghost" @click="modelEditing = false">取消</button>
          <button class="btn btn-primary" @click="saveModel">保存</button>
        </div>
      </div>
    </div>

    <!-- ═══ 自定义模型插槽弹窗 ═══ -->
    <div v-if="aliasOpen" class="modal-backdrop" @click.self="aliasOpen = false">
      <div class="modal">
        <div class="modal-title">新增自定义模型插槽</div>
        <div class="form-group"><label>插槽名 *</label><input v-model="aliasForm.alias_name" placeholder="coach_large / judge_fast" /></div>
        <div class="form-row">
          <div class="form-group"><label>业务域 *</label><select v-model="aliasForm.domain"><option v-for="d in domainOptions" :key="d.domain as string" :value="d.domain">{{ d.label }}</option></select></div>
          <div class="form-group"><label>底层能力类型</label><select v-model="aliasForm.target_slot_key"><option v-for="s in fixedSlots" :key="s.slot_key as string" :value="s.slot_key">{{ s.display_name || s.slot_key }}</option></select></div>
        </div>
        <div class="form-group">
          <label>绑定模型 *</label>
          <select v-model="aliasForm.model_id"><option value="">选择模型…</option><option v-for="m in aliasModelOptions" :key="m.model_id as string" :value="m.model_id">{{ m.display_name || m.model_id }}</option></select>
          <div v-if="!aliasModelOptions.length" class="m-desc" style="margin-top:4px">该能力类型暂无兼容的可用模型</div>
        </div>
        <div class="form-group"><label>Org ID（留空 = 全局）</label><input v-model="aliasForm.org_id" /></div>
        <div class="form-group"><label>说明</label><input v-model="aliasForm.description" placeholder="可选" /></div>
        <div class="modal-actions">
          <button class="btn btn-ghost" @click="aliasOpen = false">取消</button>
          <button class="btn btn-primary" @click="saveAlias">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.models-page { flex: 1; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 18px; }
.stat-sub { font-size: 11px; color: var(--muted); margin-top: 4px; }
.sec-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.custom-head { margin-top: 24px; padding-top: 18px; border-top: 1px solid #eef2f7; }
.sec-head h3 { font-size: 15px; font-weight: 700; }
.sec-head .btn { margin-left: auto; }
.sec-hint { font-size: 12px; color: var(--muted); }

.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 14px; align-content: start; }
.slot-grid { grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); }
.m-card { background: var(--panel); border: 1px solid #eef2f7; border-radius: 14px; padding: 16px; display: flex; flex-direction: column; gap: 11px; box-shadow: var(--shadow-sm); transition: box-shadow .18s, transform .18s, border-color .18s; }
.m-card:hover { box-shadow: var(--shadow-md); transform: translateY(-2px); border-color: #bfdbfe; }
.m-card.off { opacity: .62; }
.m-card.slot-card.warn { border-color: #fde68a; background: #fffdf5; }
.m-card-head { display: flex; align-items: center; gap: 11px; }
.m-icon { width: 40px; height: 40px; border-radius: 11px; background: linear-gradient(135deg, #dbeafe, #e0e7ff); display: flex; align-items: center; justify-content: center; font-size: 19px; flex-shrink: 0; }
.m-card-id { flex: 1; min-width: 0; }
.model-toggle { flex-shrink: 0; }
.toggle { position: relative; display: inline-block; width: 36px; height: 20px; }
.toggle input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; cursor: pointer; inset: 0; background: #cbd5e1; border-radius: 99px; transition: .2s; }
.toggle-slider::before { content: ""; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: .2s; }
.toggle input:checked + .toggle-slider { background: var(--blue); }
.toggle input:checked + .toggle-slider::before { transform: translateX(16px); }
.m-name { font-size: 14px; font-weight: 700; line-height: 1.3; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-sub { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-meta { display: flex; gap: 6px; flex-wrap: wrap; }
.m-meta .tag { font-size: 10px; font-weight: 600; padding: 2px 8px; border-radius: 99px; background: #f1f5f9; color: #475569; }
.tag-type { background: #ede9fe; color: #6d28d9; }
.tag-kind { background: #dbeafe; color: #1d4ed8; }
.tag-bound { background: #dcfce7; color: #166534; }
.m-line { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; word-break: break-all; line-height: 1.5; }
.m-desc { font-size: 12px; color: var(--muted); line-height: 1.5; }
.m-caps { display: flex; gap: 5px; flex-wrap: wrap; }
.cap { font-size: 10px; padding: 1px 7px; border-radius: 6px; background: #f0fdf4; color: #16a34a; border: 1px solid #bbf7d0; font-family: ui-monospace, Menlo, Consolas, monospace; }
.m-actions { display: flex; gap: 6px; flex-wrap: wrap; border-top: 1px solid #f1f5f9; padding-top: 11px; margin-top: auto; }
.m-actions .btn:disabled { opacity: .68; cursor: wait; background: #eff6ff; color: #1d4ed8; border-color: #bfdbfe; }
.model-test { border-top: 1px dashed #e2e8f0; padding-top: 10px; margin-top: 4px; display: flex; flex-direction: column; gap: 8px; }
.m-test-head { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.m-test-head .tag { background: #eef2ff; color: #1e3a8a; }

.slot-bind { display: flex; align-items: center; gap: 8px; background: #f0f7ff; border: 1px solid #dbeafe; border-radius: 9px; padding: 8px 11px; }
.slot-bind.empty { background: #fffbeb; border-color: #fde68a; }
.slot-bind-label { font-size: 10px; font-weight: 700; color: #1d4ed8; text-transform: uppercase; letter-spacing: .04em; }
.slot-bind-model { font-size: 12.5px; font-weight: 700; color: var(--text); }
.slot-bind-none { font-size: 12px; color: #b45309; font-weight: 600; }

.resolve-layout { display: grid; grid-template-columns: 320px 1fr; gap: 14px; align-items: start; }
.resolve-form { padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.resolve-form .field { display: flex; flex-direction: column; gap: 5px; }
.resolve-form label { font-size: 12px; font-weight: 700; color: #475569; }
.resolve-result { padding: 16px; }
.resolve-result .detail-grid { display: grid; grid-template-columns: 92px 1fr; gap: 9px 14px; font-size: 13px; margin: 6px 0 12px; }
.resolve-result .detail-key { color: var(--muted); }
.resolve-result .detail-val { font-weight: 600; word-break: break-all; }

.test-out { padding: 16px; }
.empty { padding: 30px; text-align: center; color: #94a3b8; font-size: 13px; grid-column: 1 / -1; }

.modal-backdrop { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal { background: #fff; border-radius: 16px; padding: 24px; width: 560px; max-width: 96vw; max-height: 90vh; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; box-shadow: var(--shadow-lg); }
.modal-title { font-size: 16px; font-weight: 700; }
.modal .form-row { display: flex; gap: 12px; }
.modal .form-group { display: flex; flex-direction: column; gap: 5px; flex: 1; min-width: 0; }
.modal .form-group label { font-size: 12px; font-weight: 600; color: var(--muted); }
.modal .form-group textarea.mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; }
.endpoint-preview { border: 1px solid #bfdbfe; background: linear-gradient(180deg, #f8fbff, #eff6ff); border-radius: 12px; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
.endpoint-preview-title { font-size: 12px; font-weight: 800; color: #1d4ed8; }
.endpoint-preview-row { display: grid; grid-template-columns: 72px minmax(0, 1fr); gap: 10px; align-items: start; font-size: 12px; }
.endpoint-preview-row span { color: var(--muted); font-weight: 700; }
.endpoint-preview-row strong { color: #0f172a; font-family: ui-monospace, Menlo, Consolas, monospace; word-break: break-all; font-weight: 700; }
.endpoint-preview-row strong.warn { color: #b45309; }
.endpoint-body { border-top: 1px solid #dbeafe; padding-top: 8px; }
.endpoint-body summary { cursor: pointer; font-size: 12px; font-weight: 700; color: #2563eb; }
.endpoint-body pre { margin: 8px 0 0; padding: 10px; border-radius: 9px; background: #0f172a; color: #dbeafe; font-size: 11px; line-height: 1.5; overflow-x: auto; }
.kv-editor { border: 1px solid #e2e8f0; border-radius: 12px; padding: 12px; display: flex; flex-direction: column; gap: 8px; background: #fbfdff; }
.kv-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.kv-head div { display: flex; flex-direction: column; gap: 3px; }
.kv-head strong { font-size: 13px; color: #0f172a; }
.kv-head span { font-size: 11px; color: var(--muted); line-height: 1.45; }
.kv-row { display: grid; grid-template-columns: minmax(120px, 1fr) minmax(160px, 1.4fr) auto; gap: 8px; align-items: center; }
.kv-empty { font-size: 12px; color: #94a3b8; padding: 8px 0; }
.modal-actions { display: flex; gap: 8px; justify-content: flex-end; border-top: 1px solid var(--border); padding-top: 14px; }

@media (max-width: 980px) {
  .models-page { padding: 16px; }
  .card-grid, .slot-grid { grid-template-columns: 1fr; }
  .resolve-layout { grid-template-columns: 1fr; }
  .modal .form-row { flex-direction: column; }
  .kv-head { flex-direction: column; }
  .kv-row { grid-template-columns: 1fr; }
}
.capability-picker { display: flex; flex-wrap: wrap; gap: 8px; }
.capability-option { display: inline-flex; align-items: center; gap: 6px; min-height: 30px; padding: 5px 9px; border: 1px solid var(--border); border-radius: 7px; background: #fff; color: var(--muted); cursor: pointer; font-size: 12px; }
.capability-option.selected { color: #1d4ed8; background: #eff6ff; border-color: #93c5fd; }
.capability-check { width: 12px; font-weight: 800; }
.field-hint { margin-top: 6px; color: var(--muted); font-size: 11px; line-height: 1.5; }
</style>
