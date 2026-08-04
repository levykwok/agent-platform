<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { createLongTermMemory, fetchConversationMemoryStatus, fetchLongTermMemories, updateLongTermMemory } from '../api/liveContext'
import type { MemoryCenterItem } from '../types'

const props = defineProps<{
  domain: string
}>()

const items = ref<MemoryCenterItem[]>([])
const loading = ref(false)
const error = ref('')
const status = ref<Record<string, unknown>>({})
const filters = reactive({ query: '', type: '', status: 'active', scope: '' })
const draft = reactive({ content: '', memory_type: 'preference', domain: props.domain || '', confidence: 1 })

const typeLabels: Record<string, string> = {
  preference: '偏好',
  fact: '事实',
  constraint: '约束',
  experience: '经验',
  result: '结果',
}

const grouped = computed(() => {
  const query = filters.query.trim().toLowerCase()
  const rows = items.value.filter((item) => {
    if (filters.type && item.memory_type !== filters.type) return false
    if (filters.status && item.status !== filters.status) return false
    if (filters.scope && item.scope !== filters.scope) return false
    if (query) {
      const hay = [item.content, item.content_summary, item.business_key, item.domain, item.scope].join(' ').toLowerCase()
      if (!hay.includes(query)) return false
    }
    return true
  })
  return rows.reduce<Record<string, MemoryCenterItem[]>>((acc, item) => {
    const key = item.memory_type || 'fact'
    acc[key] ||= []
    acc[key].push(item)
    return acc
  }, {})
})

const stats = computed(() => {
  const rows = items.value
  return [
    { label: '全部记忆', value: rows.length },
    { label: '已确认', value: rows.filter((item) => item.status === 'active').length },
    { label: '待确认', value: rows.filter((item) => item.status === 'pending_confirm').length },
    { label: '低置信', value: rows.filter((item) => typeof item.confidence === 'number' && item.confidence < 0.65).length },
  ]
})

function confidence(item: MemoryCenterItem) {
  if (typeof item.confidence !== 'number') return 'n/a'
  return `${Math.round(item.confidence * 100)}%`
}

function typeLabel(type: string) {
  return typeLabels[type] || type || '记忆'
}

async function loadMemory() {
  loading.value = true
  error.value = ''
  try {
    const [longTerm, episodic] = await Promise.all([
      fetchLongTermMemories({ domain: props.domain, status: filters.status || undefined, limit: 100 }),
      fetchConversationMemoryStatus(props.domain),
    ])
    items.value = longTerm.items || []
    status.value = episodic
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  } finally {
    loading.value = false
  }
}

async function addMemory() {
  const content = draft.content.trim()
  if (!content) return
  await createLongTermMemory({
    scope: 'user',
    domain: draft.domain || props.domain || '',
    memory_type: draft.memory_type,
    content,
    confidence: draft.confidence,
    status: 'active',
    content_summary: content.length > 80 ? `${content.slice(0, 80)}...` : content,
  })
  draft.content = ''
  await loadMemory()
}

async function setStatus(item: MemoryCenterItem, nextStatus: string) {
  await updateLongTermMemory(item.id, { status: nextStatus })
  item.status = nextStatus
}

onMounted(loadMemory)
</script>

<template>
  <section class="memory-center">
    <header class="memory-hero">
      <div>
        <span class="eyebrow">Memory Center</span>
        <h3>这个助手记住了什么</h3>
        <p>这里是用户可理解、可管理的长期记忆，不是本轮调试日志。你可以查看偏好、事实、约束和项目上下文，也可以新增或停用记忆。</p>
      </div>
      <button @click="loadMemory" :disabled="loading">{{ loading ? '刷新中' : '刷新记忆' }}</button>
    </header>

    <div class="memory-stats">
      <article v-for="stat in stats" :key="stat.label">
        <strong>{{ stat.value }}</strong>
        <span>{{ stat.label }}</span>
      </article>
      <article>
        <strong>{{ status.enabled === false ? 'OFF' : 'ON' }}</strong>
        <span>历史会话记忆</span>
      </article>
    </div>

    <section class="memory-create-card">
      <div>
        <h4>手动告诉助手一条长期记忆</h4>
        <p>适合写用户偏好、项目背景、输出约束、长期事实。敏感或临时信息不要写进长期记忆。</p>
      </div>
      <div class="memory-create-form">
        <select v-model="draft.memory_type">
          <option value="preference">偏好</option>
          <option value="fact">事实</option>
          <option value="constraint">约束</option>
          <option value="experience">经验</option>
          <option value="result">结果</option>
        </select>
        <input v-model="draft.domain" placeholder="业务域，可留空" />
        <textarea v-model="draft.content" placeholder="例如：用户希望回复先给结论，再给操作步骤。" />
        <button @click="addMemory" :disabled="!draft.content.trim()">加入记忆</button>
      </div>
    </section>

    <section class="memory-filters">
      <input v-model="filters.query" placeholder="搜索记忆内容、业务域、business key..." />
      <select v-model="filters.type">
        <option value="">全部类型</option>
        <option value="preference">偏好</option>
        <option value="fact">事实</option>
        <option value="constraint">约束</option>
        <option value="experience">经验</option>
        <option value="result">结果</option>
      </select>
      <select v-model="filters.status" @change="loadMemory">
        <option value="active">active</option>
        <option value="pending_confirm">pending_confirm</option>
        <option value="disabled">disabled</option>
        <option value="rejected">rejected</option>
        <option value="">全部状态</option>
      </select>
      <select v-model="filters.scope">
        <option value="">全部范围</option>
        <option value="user">user</option>
        <option value="org">org</option>
        <option value="global">global</option>
      </select>
    </section>

    <p v-if="error" class="error-line">{{ error }}</p>

    <div v-if="!loading && !items.length" class="memory-empty-product">
      <h4>还没有长期记忆</h4>
      <p>对话过程中提取到的稳定偏好和事实会出现在这里；你也可以手动添加一条。</p>
    </div>

    <div class="memory-groups">
      <section v-for="(groupItems, type) in grouped" :key="type" class="memory-group">
        <div class="memory-group-title">
          <h4>{{ typeLabel(String(type)) }}</h4>
          <span>{{ groupItems.length }} 条</span>
        </div>
        <article v-for="item in groupItems" :key="item.id" class="memory-product-card">
          <div class="memory-card-head">
            <span class="memory-type-pill">{{ typeLabel(item.memory_type || 'fact') }}</span>
            <span :class="['memory-status-pill', `memory-status-${item.status || 'active'}`]">{{ item.status || 'active' }}</span>
          </div>
          <p class="memory-content">{{ item.content }}</p>
          <p v-if="item.content_summary && item.content_summary !== item.content" class="memory-summary">{{ item.content_summary }}</p>
          <div class="memory-context-row">
            <span>#{{ item.id }}</span>
            <span>{{ item.scope || 'user' }}</span>
            <span>{{ item.domain || 'all domain' }}</span>
            <span>confidence {{ confidence(item) }}</span>
          </div>
          <div class="memory-product-actions">
            <button v-if="item.status !== 'active'" @click="setStatus(item, 'active')">启用</button>
            <button v-if="item.status !== 'disabled'" @click="setStatus(item, 'disabled')">停用</button>
            <button v-if="item.status !== 'rejected'" @click="setStatus(item, 'rejected')">标记不相关</button>
          </div>
        </article>
      </section>
    </div>
  </section>
</template>
