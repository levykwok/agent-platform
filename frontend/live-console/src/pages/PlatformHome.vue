<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Icon from '../components/Icon.vue'
import { makeHeaders, readJson, type JsonMap } from '../lib/platformApi'

const emit = defineEmits<{ navigate: [key: string, href: string, native: boolean] }>()

const navItems = [
  { key: 'models', href: '/platform/live/models', icon: 'models', label: '模型接入', desc: '管理 provider、model、slot binding 和模型测试。', native: true },
  { key: 'tools', href: '/platform/live/tools', icon: 'tools', label: 'Tools 目录', desc: '查看平台、业务域和 Flow Tool 可调用工具。', native: true },
  { key: 'mcp', href: '/platform/live/mcp', icon: 'mcp', label: 'MCP 服务器', desc: '注册、测试、绑定 MCP server 和工具集。', native: true },
  { key: 'skills', href: '/platform/live/skills', icon: 'skills', label: 'Skills 中心', desc: '管理技能包启停、版本和在线测试。', native: true },
  { key: 'agents', href: '/platform/live/agents', icon: 'agents', label: 'Agent 管理', desc: '配置 Agent、Flow、工具、模型策略和测试调用。', native: true },
  { key: 'qa', href: '/platform/live/qa', icon: 'qa', label: '交互问答', desc: '通用对话入口，承载 session、历史、附件和上下文面板。', native: true },
  { key: 'workbench', href: '/platform/live/workbench', icon: 'qa', label: 'Agent 工作台', desc: '展示 Agent 运行过程、步骤事件、等待输入和执行资源。', native: true },
]

const infra = ref<JsonMap>({})
const agents = ref<JsonMap[]>([])
const skills = ref<JsonMap[]>([])
const error = ref('')
const loading = ref(false)

const domains = computed(() => {
  const raw = infra.value.domains && typeof infra.value.domains === 'object' ? infra.value.domains : {}
  return Object.entries(raw).map(([domain, snap]: [string, any]) => ({ domain, ...(snap || {}) }))
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [status, agentData, skillData] = await Promise.all([
      readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: makeHeaders(false, 'platform') })),
      readJson<JsonMap>(await fetch('/platform/frontend/agents?limit=100', { headers: makeHeaders(false, 'platform') })),
      readJson<JsonMap>(await fetch('/platform/frontend/skills', { headers: makeHeaders(false, 'platform') })),
    ])
    infra.value = status
    agents.value = Array.isArray(agentData.items) ? agentData.items : Array.isArray(agentData.agents) ? agentData.agents : []
    skills.value = Array.isArray(skillData) ? skillData : Array.isArray(skillData.items) ? skillData.items : Array.isArray(skillData.skills) ? skillData.skills : []
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  } finally {
    loading.value = false
  }
}

function open(item: (typeof navItems)[number]) {
  emit('navigate', item.key, item.href, Boolean(item.native))
}

const dbStatus = computed(() => (infra.value.databases?.platform_configured ? { label: '已连接', cls: 'ok' } : { label: '未连接', cls: 'err' }))
const kafkaStatus = computed(() => {
  if (infra.value.kafka?.available) return { label: '在线', cls: 'ok' }
  return { label: infra.value.kafka?.enabled ? '离线' : '未启用', cls: 'warn' }
})

const infraDetails = computed(() => [
  { name: 'PostgreSQL', ok: Boolean(infra.value.databases?.platform_configured), sub: '平台库' },
  { name: 'Redis', ok: Boolean(infra.value.redis?.configured), sub: infra.value.redis?.enabled ? '缓存/限流' : '未启用' },
  { name: 'Kafka', ok: Boolean(infra.value.kafka?.available), sub: infra.value.kafka?.bootstrap_servers || '消息总线' },
  { name: 'MinIO', ok: Boolean(infra.value.object_storage?.minio_enabled), sub: infra.value.object_storage?.bucket || '对象存储' },
  { name: 'BM25 (OpenSearch)', ok: Boolean(infra.value.rag?.bm25_service?.configured), sub: infra.value.rag?.bm25_index || '文档检索' },
  { name: '向量引擎', ok: Boolean(infra.value.rag?.vector_service?.configured), sub: infra.value.rag?.vector_backend || '语义检索' },
])

onMounted(load)
</script>

<template>
  <section class="content">
    <p v-if="error" class="error-line">{{ error }}</p>
    <div class="kpi-row">
      <div class="kpi"><div class="kpi-label"><span>🤖</span>注册 Agents</div><div class="kpi-value">{{ agents.length }}</div><div class="kpi-sub">跨所有工作区</div></div>
      <div class="kpi"><div class="kpi-label"><span>⚡</span>平台 Skills</div><div class="kpi-value">{{ skills.length }}</div><div class="kpi-sub">已启用技能包</div></div>
      <div class="kpi"><div class="kpi-label"><span>🗄</span>平台数据库</div><div class="kpi-value" :class="dbStatus.cls">{{ dbStatus.label }}</div><div class="kpi-sub">连接状态</div></div>
      <div class="kpi"><div class="kpi-label"><span>📡</span>Kafka</div><div class="kpi-value" :class="kafkaStatus.cls">{{ kafkaStatus.label }}</div><div class="kpi-sub">消息总线</div></div>
    </div>

    <div>
      <div class="section-title">平台核心能力</div>
      <div class="quick-grid">
        <button v-for="item in navItems" :key="item.key" class="quick-card" @click="open(item)">
          <div class="quick-icon"><Icon :name="item.icon" /></div>
          <div class="quick-label">{{ item.label }}</div>
          <div class="quick-desc">{{ item.desc }}</div>
        </button>
      </div>
    </div>

    <div class="two-col">
      <div class="card">
        <div class="card-head"><h3>基础设施状态</h3><button class="btn btn-ghost btn-sm" @click="load">{{ loading ? '刷新中' : '刷新' }}</button></div>
        <div class="infra-grid">
          <div v-for="svc in infraDetails" :key="svc.name" class="infra-item">
            <span :class="['infra-dot', svc.ok ? 'green' : 'gray']"></span><div><div class="infra-name">{{ svc.name }}</div><div class="infra-sub">{{ svc.sub }}</div></div>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-head"><h3>业务域状态</h3></div>
        <table><thead><tr><th>域</th><th>数据库</th><th>状态</th></tr></thead><tbody><tr v-for="d in domains" :key="d.domain"><td><b>{{ d.display_name || d.domain }}</b></td><td><span class="badge" :class="d.database_configured ? 'badge-green' : 'badge-gray'">{{ d.database_configured ? '已连接' : '未配置' }}</span></td><td><span class="badge badge-blue">{{ d.status || (d.live_available === false ? 'disabled' : '在线') }}</span></td></tr><tr v-if="!domains.length"><td colspan="3" class="empty">暂无业务域状态</td></tr></tbody></table>
      </div>
    </div>
  </section>
</template>
