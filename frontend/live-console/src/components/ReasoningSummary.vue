<script setup lang="ts">
import { computed } from 'vue'
import type { ActivityItem, ActivityStatus } from '../types'
import { formatInterimText } from '../lib/streamText'

const props = withDefaults(defineProps<{
  items: ActivityItem[]
  running?: boolean
}>(), {
  running: false,
})

const visibleItems = computed(() => props.items.filter((item) => item.title || item.summary).slice(-8))

function statusLabel(status: ActivityStatus) {
  return ({ pending: '等待', running: '进行中', success: '完成', skipped: '跳过', warning: '等待输入', error: '异常' } as const)[status]
}

function statusIcon(status: ActivityStatus) {
  if (status === 'success') return '✓'
  if (status === 'error') return '×'
  if (status === 'warning') return '!'
  if (status === 'running') return '·'
  return '…'
}

function summary(item: ActivityItem) {
  const text = String(item.summary || '').trim()
  if (!text || /^trace_id=/i.test(text)) return ''
  const formatted = formatInterimText(text)
  return formatted.length > 110 ? formatted.slice(0, 110) + '…' : formatted
}
</script>

<template>
  <details class="reasoning-card" :open="running">
    <summary class="reasoning-head">
      <span class="reasoning-spark">✦</span>
      <span class="reasoning-title">
        <strong>{{ running ? '正在思考' : '思考过程' }}</strong>
        <small>{{ items.length }} 个步骤{{ running ? ' · 实时更新中' : ' · 已完成' }}</small>
      </span>
      <span class="reasoning-status" :class="running ? 'is-running' : 'is-done'">{{ running ? '进行中' : '完成' }}</span>
    </summary>

    <div class="reasoning-list">
      <div v-for="(item, index) in visibleItems" :key="item.id || `${item.type}-${index}`" class="reasoning-item" :class="`status-${item.status}`">
        <span class="reasoning-dot">{{ statusIcon(item.status) }}</span>
        <span class="reasoning-copy">
          <strong>{{ item.title || item.type }}</strong>
          <small v-if="summary(item)">{{ summary(item) }}</small>
        </span>
        <em>{{ statusLabel(item.status) }}</em>
      </div>
    </div>

    <p class="reasoning-note">这里展示的是可验证的执行摘要，不包含模型的隐式思维链。</p>
  </details>
</template>

<style scoped>
.reasoning-card {
  width: min(100%, 620px);
  margin-bottom: 8px;
  border: 1px solid #e4eaf3;
  border-radius: 14px;
  background: linear-gradient(180deg, #ffffff, #f8fbff);
  box-shadow: 0 5px 18px rgba(15, 23, 42, .06);
  overflow: hidden;
}

.reasoning-head {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 11px 13px;
  cursor: pointer;
  list-style: none;
}

.reasoning-head::-webkit-details-marker { display: none; }
.reasoning-head::after { content: '⌄'; margin-left: auto; color: #94a3b8; font-size: 15px; transition: transform .16s ease; }
.reasoning-card[open] .reasoning-head::after { transform: rotate(180deg); }
.reasoning-spark { width: 24px; height: 24px; border-radius: 8px; display: grid; place-items: center; color: #2563eb; background: #dbeafe; font-size: 13px; }
.reasoning-title { display: grid; gap: 2px; min-width: 0; }
.reasoning-title strong { color: #1e293b; font-size: 12px; }
.reasoning-title small { color: #94a3b8; font-size: 10px; }
.reasoning-status { padding: 3px 8px; border-radius: 999px; font-size: 10px; font-style: normal; font-weight: 700; }
.reasoning-status.is-running { color: #1d4ed8; background: #dbeafe; }
.reasoning-status.is-done { color: #166534; background: #dcfce7; }
.reasoning-list { display: grid; gap: 1px; padding: 0 13px 8px 46px; }
.reasoning-item { position: relative; display: flex; align-items: flex-start; gap: 8px; padding: 7px 0; border-top: 1px solid #eef2f7; }
.reasoning-dot { width: 17px; height: 17px; flex: 0 0 17px; display: grid; place-items: center; margin-top: 1px; border-radius: 50%; color: #64748b; background: #f1f5f9; font-size: 10px; font-weight: 800; }
.reasoning-copy { display: grid; gap: 2px; min-width: 0; flex: 1; }
.reasoning-copy strong { color: #334155; font-size: 11px; line-height: 1.4; }
.reasoning-copy small { color: #64748b; font-size: 10px; line-height: 1.45; overflow-wrap: anywhere; white-space: pre-line; }
.reasoning-item em { flex: 0 0 auto; color: #94a3b8; font-size: 10px; font-style: normal; }
.reasoning-item.status-success .reasoning-dot { color: #16a34a; background: #dcfce7; }
.reasoning-item.status-running .reasoning-dot { color: #2563eb; background: #dbeafe; animation: reasoningPulse 1.2s ease-in-out infinite; }
.reasoning-item.status-warning .reasoning-dot { color: #b45309; background: #fef3c7; }
.reasoning-item.status-error .reasoning-dot { color: #dc2626; background: #fee2e2; }
.reasoning-note { padding: 8px 13px; border-top: 1px solid #eef2f7; color: #94a3b8; font-size: 10px; line-height: 1.45; }
@keyframes reasoningPulse { 50% { box-shadow: 0 0 0 4px rgba(59, 130, 246, .12); } }
</style>
