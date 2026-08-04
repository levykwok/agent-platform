<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import PlatformHome from './pages/PlatformHome.vue'
import KnowledgeManagement from './pages/KnowledgeManagement.vue'
import KgBrowser from './pages/KgBrowser.vue'
import SkillsCenter from './pages/SkillsCenter.vue'
import ToolsCatalog from './pages/ToolsCatalog.vue'
import McpServers from './pages/McpServers.vue'
import ModelsAdmin from './pages/ModelsAdmin.vue'
import AgentsAdmin from './pages/AgentsAdmin.vue'
import AgentWorkbench from './pages/AgentWorkbench.vue'
import RunsObserve from './pages/RunsObserve.vue'
import QaWorkspace from './pages/QaWorkspace.vue'
import MemoryManagement from './pages/MemoryManagement.vue'
import InfraStatus from './pages/InfraStatus.vue'
import Documentation from './pages/Documentation.vue'
import Icon from './components/Icon.vue'
import ToastHost from './components/ToastHost.vue'
import DialogHost from './components/DialogHost.vue'
import { contextHref, currentDomain, currentOrgId, currentUser, makeHeaders, readJson } from './lib/platformApi'

type PageKey = 'home' | 'knowledge' | 'qa' | 'kg' | 'skills' | 'tools' | 'mcp' | 'models' | 'agents' | 'workbench' | 'memory' | 'runs' | 'infra' | 'docs'

const navItems = [
  { key: 'home', href: '/platform/live', icon: 'home', label: '平台概览', section: '核心能力', native: true },
  { key: 'models', href: '/platform/live/models', icon: 'models', label: '模型接入', section: '核心能力', native: true },
  { key: 'tools', href: '/platform/live/tools', icon: 'tools', label: 'Tools 目录', section: '核心能力', native: true },
  { key: 'mcp', href: '/platform/live/mcp', icon: 'mcp', label: 'MCP 服务器', section: '核心能力', native: true },
  { key: 'skills', href: '/platform/live/skills', icon: 'skills', label: 'Skills 中心', section: '核心能力', native: true },
  { key: 'knowledge', href: '/platform/live/knowledge', icon: 'knowledge', label: '知识库（Beta）', section: '核心能力', native: true },
  { key: 'agents', href: '/platform/live/agents', icon: 'agents', label: 'Agent 管理', section: '核心能力', native: true },
  { key: 'qa', href: '/platform/live/qa', icon: 'qa', label: '交互问答', section: '核心能力', native: true },
  { key: 'workbench', href: '/platform/live/workbench', icon: 'qa', label: 'Agent 工作台', section: '核心能力', native: true },
  { key: 'memory', href: '/platform/live/memory', icon: 'memory', label: '记忆管理', section: '核心能力', native: true },
  { key: 'docs', href: '/platform/live/docs', icon: 'docs', label: '使用文档', section: '帮助', native: true },
]

function pageKeyFromPath(pathname: string): PageKey {
  const path = pathname.replace(/\/+$/, '') || '/platform/live'
  const match = navItems.find((item) => item.native && item.href === path)
  return (match?.key as PageKey) || 'home'
}

const activePage = ref<PageKey>(pageKeyFromPath(location.pathname))
const title = computed(() => ({ home: '平台概览', knowledge: '知识库（Beta）', qa: '交互问答', kg: '知识图谱', skills: 'Skills 中心', tools: 'Tools 目录', mcp: 'MCP 服务器', models: '模型接入', agents: 'Agent 管理', workbench: 'Agent 工作台', memory: '记忆管理', runs: '运行观测', infra: '平台状态', docs: '使用文档' }[activePage.value]))
const coreItems = computed(() => navItems.filter((item) => item.section === '核心能力'))
const opsItems = computed(() => navItems.filter((item) => item.section === '运维'))
const helpItems = computed(() => navItems.filter((item) => item.section === '帮助'))

function navigate(key: string, href: string, native = false) {
  if (native && ['home', 'knowledge', 'qa', 'kg', 'skills', 'tools', 'mcp', 'models', 'agents', 'workbench', 'memory', 'runs', 'infra', 'docs'].includes(key)) {
    activePage.value = key as PageKey
    const target = contextHref(href, currentDomain(), currentOrgId())
    if (`${location.pathname}${location.search}` !== target) {
      history.pushState(null, '', target)
    }
    return
  }
  location.href = contextHref(href, currentDomain(), currentOrgId())
}

const healthStatus = ref<'checking' | 'ok' | 'down'>('checking')
const healthLabel = computed(() => ({ checking: '检查中…', ok: '平台运行正常', down: '后端连接异常' }[healthStatus.value]))
const healthDotClass = computed(() => ({ checking: 'gray', ok: 'green', down: 'red' }[healthStatus.value]))
const appDomain = ref('')
let healthTimer: number | undefined

async function loadHealth() {
  try {
    const data = await readJson(await fetch('/platform/frontend/infra/health', { cache: 'no-store' }))
    healthStatus.value = data.status === 'ok' ? 'ok' : 'down'
  } catch {
    healthStatus.value = 'down'
  }
}

async function loadDomainBadge() {
  try {
    const org = currentOrgId()
    const data = await readJson(await fetch('/platform/frontend/infra/status', { headers: makeHeaders(false, org) }))
    appDomain.value = String(data.app_domain || 'platform')
  } catch {
    appDomain.value = ''
  }
}

onMounted(() => {
  loadHealth()
  healthTimer = window.setInterval(loadHealth, 10000)
  loadDomainBadge()
  window.addEventListener('popstate', () => {
    activePage.value = pageKeyFromPath(location.pathname)
  })
})
onUnmounted(() => {
  if (healthTimer) window.clearInterval(healthTimer)
})
</script>

<template>
  <div class="pl-shell">
    <aside class="sidebar">
      <div class="logo"><div class="logo-icon">AI</div><div class="logo-text">AI Agent Platform<span class="logo-sub">私有化智能体平台</span></div></div>
      <nav class="nav">
        <div class="nav-section">核心能力</div>
        <button v-for="item in coreItems" :key="item.key" class="nav-item" :class="{active: activePage === item.key}" @click="navigate(item.key, item.href, Boolean(item.native))"><Icon :name="item.icon" />{{ item.label }}</button>
        <template v-if="opsItems.length">
          <div class="nav-section">运维</div>
          <button v-for="item in opsItems" :key="item.key" class="nav-item" :class="{active: activePage === item.key}" @click="navigate(item.key, item.href, Boolean(item.native))"><Icon :name="item.icon" />{{ item.label }}</button>
        </template>
        <template v-if="helpItems.length">
          <div class="nav-section">帮助</div>
          <button v-for="item in helpItems" :key="item.key" class="nav-item" :class="{active: activePage === item.key}" @click="navigate(item.key, item.href, Boolean(item.native))"><Icon :name="item.icon" />{{ item.label }}</button>
        </template>
      </nav>
      <div class="sidebar-footer"><div class="user-row"><div class="avatar">PA</div><div><div class="user-name">Platform Admin</div><div class="user-role">{{ currentUser() }}</div></div><a class="logout-link" href="/platform/live/logout">⎋</a></div></div>
    </aside>
    <main class="main">
      <div class="topbar"><div class="topbar-title">{{ title }}</div><span class="status-dot" :class="healthDotClass"></span><span class="status-label">{{ healthLabel }}</span><span v-if="appDomain" class="domain-badge">当前域: {{ appDomain }}</span></div>
      <PlatformHome v-if="activePage === 'home'" @navigate="navigate" />
      <KnowledgeManagement v-else-if="activePage === 'knowledge'" />
      <QaWorkspace v-else-if="activePage === 'qa'" />
      <KgBrowser v-else-if="activePage === 'kg'" />
      <SkillsCenter v-else-if="activePage === 'skills'" />
      <ToolsCatalog v-else-if="activePage === 'tools'" />
      <McpServers v-else-if="activePage === 'mcp'" />
      <ModelsAdmin v-else-if="activePage === 'models'" />
      <AgentsAdmin v-else-if="activePage === 'agents'" />
      <AgentWorkbench v-else-if="activePage === 'workbench'" />
      <MemoryManagement v-else-if="activePage === 'memory'" />
      <RunsObserve v-else-if="activePage === 'runs'" />
      <Documentation v-else-if="activePage === 'docs'" />
      <InfraStatus v-else />
    </main>
    <ToastHost />
    <DialogHost />
  </div>
</template>
