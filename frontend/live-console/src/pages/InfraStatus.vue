<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { fmtDate, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'

const snapshot = ref<JsonMap>({})
const health = ref<JsonMap>({})
const memoryStatus = ref<JsonMap>({})
const memoryPreview = ref<JsonMap | null>(null)
const memoryPreviewState = ref<'idle' | 'loading' | 'done' | 'error'>('idle')
const sandboxRuns = ref<JsonMap[]>([])
const sandboxDetail = ref<JsonMap | null>(null)
const sandboxDetailTitle = ref('')
const error = ref('')
const loading = ref(false)
const lastRefresh = ref('')
const domainEditorOpen = ref(false)
const domainForm = reactive({ domain: '', display_name: '', description: '' })

let refreshTimer: ReturnType<typeof setInterval> | undefined

const domains = computed(() => {
  const raw = snapshot.value.domains && typeof snapshot.value.domains === 'object' ? snapshot.value.domains : {}
  return Object.entries(raw).map(([domain, snap]: [string, any]) => ({ domain, ...(snap || {}) }))
})
const runtimeSandbox = computed(() => snapshot.value.runtime_sandbox || {})
const memoryConfigured = computed(() => !!memoryStatus.value.long_term_memory_configured)
const configSources = computed<JsonMap>(() => {
  const raw =
    (snapshot.value as JsonMap).config_sources ??
    (snapshot.value as JsonMap).configSources ??
    (snapshot.value as JsonMap).configuration_sources ??
    (snapshot.value as JsonMap).configurationSources ??
    {}
  return raw && typeof raw === 'object' ? raw as JsonMap : {}
})

interface ConfigSourceRow {
  component: string
  source: string
  details: string
}

interface SvcDetail { k: string; v: string }
interface SvcCard {
  icon: string
  bg: string
  name: string
  type: string
  ok: boolean | null
  label?: string
  details: SvcDetail[]
}

function dotClass(ok: boolean | null) { return ok === null ? 'gray' : ok ? 'green' : 'red' }
function statusLabel(card: SvcCard) {
  if (card.ok === null) return '未启用'
  if (card.ok) return card.label || '正常'
  return '异常'
}

const services = computed<SvcCard[]>(() => {
  const rt = snapshot.value
  return [
    {
      icon: '🗄', bg: '#dbeafe', name: 'PostgreSQL', type: '关系型数据库', ok: Boolean(rt.databases?.platform_configured), label: '已连接',
      details: [
        { k: '平台库', v: rt.databases?.platform_configured ? '已配置' : '未配置' },
        { k: '活跃域库', v: rt.databases?.active_domain_configured ? '已连接' : '未连接' },
      ],
    },
    {
      icon: '⚡', bg: '#dcfce7', name: 'Redis', type: '缓存 / 限流', ok: rt.redis?.enabled ? Boolean(rt.redis?.configured) : null,
      details: [
        { k: '启用状态', v: rt.redis?.enabled ? '已启用' : '未启用' },
        { k: '前缀', v: rt.redis?.prefix || '—' },
      ],
    },
    {
      icon: '📨', bg: '#fef3c7', name: 'Kafka', type: '消息总线', ok: rt.kafka?.enabled ? Boolean(rt.kafka?.available) : null,
      details: [
        { k: '启用状态', v: rt.kafka?.enabled ? '已启用' : '未启用' },
        { k: '可用', v: rt.kafka?.available ? '是' : '否' },
        { k: 'Bootstrap', v: rt.kafka?.bootstrap_servers || '—' },
      ],
    },
    {
      icon: '🗂', bg: '#f3e8ff', name: 'MinIO', type: '对象存储', ok: rt.object_storage?.minio_enabled ? true : null,
      details: [
        { k: '启用状态', v: rt.object_storage?.minio_enabled ? '已启用' : '未启用' },
        { k: '默认 Bucket', v: rt.object_storage?.bucket || '—' },
      ],
    },
    {
      icon: '🏥', bg: '#fef2f2', name: '平台健康检查', type: 'HTTP /healthz', ok: health.value.status === 'ok', label: health.value.status,
      details: [{ k: '状态', v: health.value.status || '—' }],
    },
  ]
})
const configSourceRows = computed<ConfigSourceRow[]>(() => {
  const rows: ConfigSourceRow[] = []
  const raw = configSources.value
  for (const [component, value] of Object.entries(raw)) {
    const sourceValue = stringifyConfigSource(value)
    const detailsValue = typeof value === 'object' && value && !Array.isArray(value)
      ? Object.entries(value)
          .map(([k, v]) => `${k}: ${stringifyConfigSource(v)}`)
          .join(' · ')
      : ''
    rows.push({ component, source: sourceValue, details: detailsValue })
  }
  return rows
})

function stringifyConfigSource(value: unknown): string {
  if (value == null) return '未设置'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) return value.map(stringifyConfigSource).join('；') || '未设置'
  return JSON.stringify(value) || '未设置'
}

const summary = computed(() => {
  const list = services.value
  return {
    total: list.length,
    ok: list.filter((s) => s.ok === true).length,
    warn: list.filter((s) => s.ok === null).length,
    err: list.filter((s) => s.ok === false).length,
  }
})

const ragServices = computed<SvcCard[]>(() => {
  const rag = snapshot.value.rag || {}
  return [
    {
      icon: '🔍', bg: '#dbeafe', name: 'BM25 检索', type: 'OpenSearch / 关键词', ok: Boolean(rag.bm25_service?.configured),
      details: [
        { k: '索引', v: rag.bm25_index || '—' },
        { k: '服务类型', v: rag.bm25_service?.type || '—' },
      ],
    },
    {
      icon: '🧠', bg: '#dcfce7', name: '向量检索 (主)', type: rag.vector_backend || '向量引擎', ok: Boolean(rag.vector_service?.configured),
      details: [
        { k: '后端', v: rag.vector_backend || '—' },
        { k: 'Collection/Index', v: rag.vector_collection || rag.vector_index || '—' },
      ],
    },
    {
      icon: '🔗', bg: '#fef3c7', name: '向量检索 (副)', type: rag.vector_secondary_backend || 'Secondary', ok: Boolean(rag.vector_secondary_service?.configured),
      details: [
        { k: '后端', v: rag.vector_secondary_backend || '—' },
        { k: 'Collection/Index', v: rag.vector_secondary_collection || rag.vector_secondary_index || '—' },
      ],
    },
  ]
})

const sandboxCard = computed<SvcCard>(() => {
  const rt = runtimeSandbox.value
  return {
    icon: '🧪', bg: '#e0f2fe', name: 'Runtime Sandbox', type: rt.status || 'runtime', ok: Boolean(rt.platform_service), label: rt.status,
    details: [
      { k: '服务', v: rt.platform_service ? 'RuntimeSandboxService' : '未启用' },
      { k: '契约', v: rt.service_contract || '—' },
      { k: '运行记录', v: rt.run_record || '—' },
      { k: '后端', v: (rt.backends || []).join(', ') || '—' },
      { k: '类型', v: (rt.supported_types || []).join(', ') || '—' },
      { k: '策略', v: rt.policy_scope || '—' },
      { k: '产物', v: rt.artifact_scope || '—' },
    ],
  }
})

function pretty(value: unknown) { try { return JSON.stringify(value ?? {}, null, 2) } catch { return String(value || '') } }
function contextHeaders(json = false) { return makeHeaders(json, 'platform') }

async function loadHealth() { health.value = await readJson<JsonMap>(await fetch('/platform/frontend/infra/health')) }
async function loadStatus() { snapshot.value = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: contextHeaders(false) })) }
async function loadMemoryStatus() { memoryStatus.value = await readJson<JsonMap>(await fetch('/platform/frontend/memory/maintenance/status', { headers: contextHeaders(false) })) }

function openDomainEditor() {
  domainForm.domain = ''
  domainForm.display_name = ''
  domainForm.description = ''
  domainEditorOpen.value = true
}

async function saveDomain() {
  if (!domainForm.domain.trim()) {
    error.value = '请填写业务域 ID'
    return
  }
  try {
    await readJson<JsonMap>(await fetch('/platform/frontend/domains', {
      method: 'POST',
      headers: contextHeaders(true),
      body: JSON.stringify(domainForm),
    }))
    domainEditorOpen.value = false
    await loadStatus()
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
}

async function previewMemoryMaintenance() {
  memoryPreviewState.value = 'loading'
  try {
    memoryPreview.value = await readJson<JsonMap>(await fetch('/platform/frontend/memory/maintenance/dry-run', {
      method: 'POST', headers: contextHeaders(true),
      body: JSON.stringify({ scope: 'user', all_users: true, domain: null, limit: 1000 }),
    }))
    memoryPreviewState.value = 'done'
  } catch (err) {
    memoryPreview.value = { error: err instanceof Error ? err.message : String(err) }
    memoryPreviewState.value = 'error'
  }
}

async function loadSandboxRuns() {
  const data = await readJson<JsonMap>(await fetch('/platform/frontend/runtime-sandbox/runs?limit=8', { headers: contextHeaders(false) }))
  sandboxRuns.value = Array.isArray(data.items) ? data.items : []
}
async function openSandboxRunDetail(runId: unknown) {
  sandboxDetailTitle.value = `沙箱运行详情 ${runId}`
  sandboxDetail.value = await readJson<JsonMap>(await fetch(`/platform/frontend/runtime-sandbox/runs/${encodeURIComponent(String(runId))}`, { headers: contextHeaders(false) }))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await Promise.all([loadHealth(), loadStatus(), loadMemoryStatus(), loadSandboxRuns()])
    lastRefresh.value = `更新于 ${new Date().toLocaleTimeString('zh-CN')}`
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
    lastRefresh.value = '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  refreshTimer = setInterval(load, 30000)
})
onUnmounted(() => { if (refreshTimer) clearInterval(refreshTimer) })
</script>

<template>
  <section class="content">
    <p v-if="error" class="error-line">{{ error }}</p>

    <div class="summary-bar">
      <div class="sum-card"><div class="sum-icon gray">📊</div><div><div class="sum-val">{{ summary.total }}</div><div class="sum-label">监控服务</div></div></div>
      <div class="sum-card"><div class="sum-icon ok">✅</div><div><div class="sum-val ok">{{ summary.ok }}</div><div class="sum-label">正常</div></div></div>
      <div class="sum-card"><div class="sum-icon warn">⚠️</div><div><div class="sum-val warn">{{ summary.warn }}</div><div class="sum-label">未启用</div></div></div>
      <div class="sum-card"><div class="sum-icon err">❌</div><div><div class="sum-val err">{{ summary.err }}</div><div class="sum-label">异常</div></div></div>
    </div>

    <div>
      <div class="section-head">
        <div class="section-title">基础设施服务<span class="section-sub">当前域: {{ snapshot.app_domain || 'platform' }}</span></div>
        <div style="display:flex;align-items:center;gap:10px">
          <span style="font-size:12px;color:var(--muted)">{{ lastRefresh }}</span>
          <button class="btn btn-ghost btn-sm" @click="load">{{ loading ? '刷新中' : '🔄 刷新' }}</button>
        </div>
      </div>
      <div class="service-grid">
        <div v-for="svc in services" :key="svc.name" class="svc-card">
          <div class="svc-head">
            <div class="svc-icon" :style="{ background: svc.bg }">{{ svc.icon }}</div>
            <div><div class="svc-name">{{ svc.name }}</div><div class="svc-type">{{ svc.type }}</div></div>
            <div class="svc-status"><span class="infra-dot" :class="dotClass(svc.ok)"></span>{{ statusLabel(svc) }}</div>
          </div>
          <div class="svc-detail">
            <div v-for="d in svc.details" :key="d.k" class="detail-row"><span class="detail-key">{{ d.k }}</span><span class="detail-val" :title="d.v">{{ d.v }}</span></div>
          </div>
        </div>
      </div>
    </div>

    <div>
      <div class="section-title">RAG 检索资源</div>
      <div class="service-grid">
        <div v-for="svc in ragServices" :key="svc.name" class="svc-card">
          <div class="svc-head">
            <div class="svc-icon" :style="{ background: svc.bg }">{{ svc.icon }}</div>
            <div><div class="svc-name">{{ svc.name }}</div><div class="svc-type">{{ svc.type }}</div></div>
            <div class="svc-status"><span class="infra-dot" :class="dotClass(svc.ok)"></span>{{ statusLabel(svc) }}</div>
          </div>
          <div class="svc-detail">
            <div v-for="d in svc.details" :key="d.k" class="detail-row"><span class="detail-key">{{ d.k }}</span><span class="detail-val" :title="d.v">{{ d.v }}</span></div>
          </div>
        </div>
      </div>
    </div>

    <div>
      <div class="section-title">配置来源状态<span class="section-sub">用于确认参数来自环境变量、数据库、工作区文件或默认配置。</span></div>
      <div class="service-grid">
        <template v-if="configSourceRows.length">
          <div v-for="row in configSourceRows" :key="row.component" class="svc-card">
            <div class="svc-head">
              <div class="svc-icon" style="background:#f1f5f9">🧩</div>
              <div><div class="svc-name">{{ row.component }}</div><div class="svc-type">配置来源</div></div>
            </div>
            <div class="svc-detail">
              <div class="detail-row"><span class="detail-key">来源</span><span class="detail-val">{{ row.source }}</span></div>
              <div v-if="row.details" class="detail-row"><span class="detail-key">明细</span><span class="detail-val">{{ row.details }}</span></div>
            </div>
          </div>
        </template>
        <div v-else class="svc-card">
          <div class="svc-head">
            <div class="svc-icon" style="background:#f1f5f9">🧩</div>
            <div><div class="svc-name">配置来源</div><div class="svc-type">兼容态</div></div>
          </div>
          <div class="svc-detail">
            <div class="empty">当前兼容实例未返回配置来源详情，暂无法展示来源追溯。</div>
          </div>
        </div>
      </div>
    </div>

    <div>
      <div class="section-title">记忆维护策略<span class="section-sub">长期记忆只注入 active；自动整理由后端定时任务执行</span></div>
      <div class="service-grid">
        <div class="svc-card" style="grid-column:span 2">
          <div class="svc-head">
            <div class="svc-icon" style="background:#eef2ff">🧠</div>
            <div><div class="svc-name">长期记忆维护</div><div class="svc-type">{{ memoryStatus.org_id }}{{ memoryStatus.domain ? ' / ' + memoryStatus.domain : '' }}</div></div>
            <div class="svc-status"><span class="infra-dot" :class="memoryConfigured ? 'green' : 'gray'"></span>{{ memoryConfigured ? '已配置' : '未配置' }}</div>
          </div>
          <div class="svc-detail">
            <div class="detail-row"><span class="detail-key">执行模式</span><span class="detail-val">{{ memoryStatus.maintenance?.mode || 'external_scheduler' }}</span></div>
            <div class="detail-row"><span class="detail-key">页面 apply</span><span class="detail-val">{{ memoryStatus.maintenance?.apply_from_live ? '允许' : '关闭' }}</span></div>
            <div class="detail-row"><span class="detail-key">默认策略</span><span class="detail-val">过期停用 / 精确去重 / dry-run 优先</span></div>
            <pre class="json-box" style="margin-top:8px">{{ memoryStatus.scheduled_command || '—' }}</pre>
            <div style="display:flex;gap:8px;align-items:center;margin-top:10px">
              <button class="btn btn-ghost btn-sm" type="button" @click="previewMemoryMaintenance">预检维护</button>
              <span style="font-size:12px;color:var(--muted)">{{ { idle: '', loading: '预检中…', done: '完成', error: '失败' }[memoryPreviewState] }}</span>
            </div>
          </div>
        </div>
        <div class="svc-card">
          <div class="svc-head">
            <div class="svc-icon" style="background:#f1f5f9">📋</div>
            <div><div class="svc-name">最近预检</div><div class="svc-type">dry-run</div></div>
          </div>
          <div class="svc-detail">
            <div v-if="!memoryPreview" class="empty">尚未预检</div>
            <template v-else-if="memoryPreviewState === 'error'">{{ memoryPreview.error }}</template>
            <template v-else>
              <div class="detail-row"><span class="detail-key">扫描记忆</span><span class="detail-val">{{ memoryPreview.scanned ?? 0 }}</span></div>
              <div class="detail-row"><span class="detail-key">用户数</span><span class="detail-val">{{ memoryPreview.user_count ?? 0 }}</span></div>
              <div class="detail-row"><span class="detail-key">计划动作</span><span class="detail-val">{{ memoryPreview.planned_actions ?? 0 }}</span></div>
              <div class="detail-row"><span class="detail-key">原因</span><span class="detail-val" :title="Object.entries(memoryPreview.reason_counts || {}).map(([k,v]) => `${k}: ${v}`).join(' · ') || '无'">{{ Object.entries(memoryPreview.reason_counts || {}).map(([k,v]) => `${k}: ${v}`).join(' · ') || '无' }}</span></div>
            </template>
          </div>
        </div>
      </div>
    </div>

    <div>
      <div class="section-head">
        <div class="section-title">运行沙箱<span class="section-sub">平台 RuntimeSandboxService 执行记录</span></div>
        <button class="btn btn-ghost btn-sm" @click="loadSandboxRuns">刷新运行记录</button>
      </div>
      <div class="service-grid">
        <div class="svc-card">
          <div class="svc-head">
            <div class="svc-icon" :style="{ background: sandboxCard.bg }">{{ sandboxCard.icon }}</div>
            <div><div class="svc-name">{{ sandboxCard.name }}</div><div class="svc-type">{{ sandboxCard.type }}</div></div>
            <div class="svc-status"><span class="infra-dot" :class="dotClass(sandboxCard.ok)"></span>{{ statusLabel(sandboxCard) }}</div>
          </div>
          <div class="svc-detail">
            <div v-for="d in sandboxCard.details" :key="d.k" class="detail-row"><span class="detail-key">{{ d.k }}</span><span class="detail-val" :title="d.v">{{ d.v }}</span></div>
          </div>
        </div>
      </div>
      <div class="table-wrap" style="margin-top:12px">
        <table>
          <thead><tr><th>Run</th><th>工具</th><th>状态</th><th>Trace</th><th>耗时</th><th>时间</th><th></th></tr></thead>
          <tbody>
            <tr v-if="!sandboxRuns.length"><td colspan="7" class="empty">暂无沙箱运行记录</td></tr>
            <tr v-for="item in sandboxRuns" :key="String(item.sandbox_run_id)">
              <td><span class="mono">{{ item.sandbox_run_id }}</span><div class="muted-small">{{ item.profile_id }} / {{ item.sandbox_type }}</div></td>
              <td>{{ item.tool_name || '—' }}<div class="muted-small">{{ item.agent_id || '' }}</div></td>
              <td><span class="badge" :class="item.status === 'succeeded' ? 'badge-green' : ['failed','error'].includes(String(item.status)) ? 'badge-red' : item.status === 'rejected' ? 'badge-gray' : 'badge-blue'">{{ item.status }}</span></td>
              <td class="mono">{{ item.trace_id || '—' }}</td>
              <td>{{ item.duration_ms ?? '—' }} ms</td>
              <td>{{ item.created_at ? fmtDate(item.created_at) : '—' }}</td>
              <td><button class="btn btn-ghost btn-sm" @click="openSandboxRunDetail(item.sandbox_run_id)">详情</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <section v-if="sandboxDetail" class="panel">
      <div class="section-head"><div><div class="section-title">{{ sandboxDetailTitle }}</div><div class="section-sub">完整 payload / result / artifact refs。</div></div><button class="btn btn-ghost btn-sm" @click="sandboxDetail = null">关闭</button></div>
      <pre class="json-box">{{ pretty(sandboxDetail) }}</pre>
    </section>

    <div>
      <div class="section-head">
        <div>
          <div class="section-title">业务域状态</div>
          <div class="section-sub">业务域是平台资源隔离和筛选维度，供 Agent、Skill、模型策略等模块复用。</div>
        </div>
        <button class="btn btn-primary btn-sm" @click="openDomainEditor">+ 注册业务域</button>
      </div>
      <div class="domain-grid">
        <div v-for="d in domains" :key="d.domain" class="domain-card">
          <div class="domain-head">
            <div class="domain-icon">{{ d.icon || '→' }}</div>
            <div class="domain-name">{{ d.display_name || d.domain }}</div>
            <a v-if="d.live_available && d.live_url" class="domain-link" :href="d.live_url">→ 进入域</a>
            <span v-else class="domain-link" style="color:var(--muted)">未启用</span>
          </div>
          <div class="domain-rows">
            <div class="domain-row"><span class="k">域数据库</span><span class="v"><span class="badge" :class="d.database_configured ? 'badge-green' : 'badge-gray'">{{ d.database_configured ? '已连接' : '未配置' }}</span></span></div>
            <div v-for="(v, k) in (d.worker_health_keys || {})" :key="k" class="domain-row"><span class="k">{{ k }}</span><span class="v">{{ v }}</span></div>
          </div>
        </div>
        <div v-if="!domains.length" class="empty">无域配置</div>
      </div>
    </div>

    <div v-if="domainEditorOpen" class="modal-backdrop" @click.self="domainEditorOpen = false">
      <div class="modal-card">
        <div class="modal-head">
          <div class="modal-title">注册业务域</div>
          <button class="btn btn-ghost btn-sm" @click="domainEditorOpen = false">关闭</button>
        </div>
        <div class="modal-body">
          <div class="form-group"><label>业务域 ID</label><input v-model="domainForm.domain" placeholder="training / finance / ops" /></div>
          <div class="form-group"><label>显示名称</label><input v-model="domainForm.display_name" placeholder="不填则使用业务域 ID" /></div>
          <div class="form-group"><label>说明</label><textarea v-model="domainForm.description" rows="3" placeholder="这个业务域管理哪些 Agent / Skill / 数据。" /></div>
        </div>
        <div class="modal-actions">
          <button class="btn btn-ghost" @click="domainEditorOpen = false">取消</button>
          <button class="btn btn-primary" @click="saveDomain">保存</button>
        </div>
      </div>
    </div>

    <section class="panel">
      <div class="section-head"><div><div class="section-title">原始状态快照</div><div class="section-sub">用于排查部署差异，保持和原平台状态页同一数据源。</div></div></div>
      <pre class="json-box">{{ pretty(snapshot) }}</pre>
    </section>
  </section>
</template>

<style scoped>
.modal-backdrop { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 28px; }
.modal-card { width: min(520px, 96vw); max-height: 90vh; background: #fff; border-radius: 16px; box-shadow: 0 18px 44px rgba(15, 23, 42, .18); display: flex; flex-direction: column; overflow: hidden; }
.modal-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--border); }
.modal-title { font-size: 16px; font-weight: 800; color: var(--text); }
.modal-body { padding: 18px 20px; display: flex; flex-direction: column; gap: 14px; overflow-y: auto; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; padding: 14px 20px; border-top: 1px solid var(--border); }
.form-group { display: flex; flex-direction: column; gap: 5px; }
.form-group label { font-size: 12px; font-weight: 700; color: var(--muted); }
.form-group textarea { resize: vertical; min-height: 78px; }
</style>
