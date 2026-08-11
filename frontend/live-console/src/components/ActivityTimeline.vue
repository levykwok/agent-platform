<script setup lang="ts">
import type { ActivityItem } from '../types'
import { formatInterimText } from '../lib/streamText'

defineProps<{
  items: ActivityItem[]
}>()

function statusLabel(status: ActivityItem['status']) {
  return ({pending: '等待', running: '运行中', success: '完成', skipped: '跳过', warning: '警告', error: '错误'} as const)[status]
}

function duration(item: ActivityItem) {
  const value = Number(item.duration_ms || 0)
  if (!value) return ''
  if (value < 1_000) return `${value} ms`
  if (value < 10_000) return `${(value / 1_000).toFixed(2)} s`
  return `${(value / 1_000).toFixed(1)} s`
}

function refText(item: ActivityItem) {
  if (!item.refs || Object.keys(item.refs).length === 0) return ''
  return JSON.stringify(item.refs)
}

function detailText(item: ActivityItem) {
  if (!item.detail || Object.keys(item.detail).length === 0) return ''
  return JSON.stringify(item.detail, null, 2)
}

function detailValue(item: ActivityItem, key: string) {
  const detail = item.detail || {}
  const value = detail[key]
  return value == null || !String(value).trim() ? '' : String(value)
}

function agentName(item: ActivityItem) {
  return detailValue(item, 'target_agent_id')
    || detailValue(item, 'agent_id')
    || detailValue(item, 'source')
    || detailValue(item, 'tool_name')
    || detailValue(item, 'tool_id')
    || detailValue(item, 'skill_name')
    || detailValue(item, 'skill_id')
    || parseAgentFromSummary(item.summary || '')
}

function parseAgentFromSummary(summary: string) {
  const arrow = String(summary || '').match(/->\s*([A-Za-z0-9._-]+)/)
  if (arrow) return arrow[1]
  const single = String(summary || '').match(/agent\s+([A-Za-z0-9._-]+)/i)
  return single ? single[1] : ''
}

function niceType(type: string) {
  const t = String(type || '').toLowerCase()
  return ({
    receive: '接收问题',
    turn_received: '接收问题',
    'turn.received': '接收问题',
    capability_loaded: '加载能力',
    tool_call: '工具调用',
    workflow_start: 'Workflow 开始',
    workflow_step_start: '步骤开始',
    workflow_step_end: '步骤完成',
    workflow_final_step: '最终步骤',
    router_decision: '路由决策',
    supervisor_start: 'Supervisor 启动',
    single_agent_start: 'Agent 启动',
    agent_start: 'Agent 开始',
    agent_result: 'Agent 结果',
    agent_end: 'Agent 完成',
    model_call_start: '模型调用开始',
    model_call_end: '模型调用完成',
    text_block_start: '文本生成开始',
    text_block_end: '文本生成完成',
    tool_call_start: '工具调用开始',
    tool_call_end: '工具调用完成',
    tool_result: '工具结果',
    tool_result_start: '工具结果开始',
    tool_result_text_delta: '工具结果输出',
    tool_result_data_delta: '工具结果数据',
    tool_result_end: '工具结果完成',
    skill_call_start: 'Skill 调用开始',
    skill_call_end: 'Skill 调用完成',
    memory_save: '记忆保存',
  } as Record<string, string>)[t] || type
}

function trackBy(item: ActivityItem) {
  return item.id || `${item.type}:${item.title}`
}

function cssClass(status: ActivityItem['status']) {
  return `status-${status}`
}

function icon(status: ActivityItem['status']) {
  if (status === 'running') return '↻'
  if (status === 'success') return '✓'
  if (status === 'warning') return '!'
  if (status === 'error') return '×'
  if (status === 'skipped') return '·'
  return '…'
}

function isExpanded(item: ActivityItem) {
  return Boolean(refText(item) || detailText(item))
}

function title(item: ActivityItem) {
  const base = niceType(item.type || item.title)
  const agent = agentName(item)
  const rawTitle = String(item.title || '').trim()
  const displayTitle = !rawTitle || rawTitle.toLowerCase() === 'activity' ? base : rawTitle
  if (!agent || item.type === 'turn.received') return displayTitle
  if (displayTitle.startsWith('Workflow')) return `${agent} · ${displayTitle}`
  return `${agent} · ${base}`
}

function sourceLabel(item: ActivityItem) {
  const agent = agentName(item)
  if (!agent) return ''
  const mode = detailValue(item, 'mode')
  return mode ? `${agent} / ${mode}` : agent
}
function displaySummary(value: unknown) {
  return formatInterimText(value)
}
</script>

<template>
  <section class="panel-section">
    <div class="section-title">
      <span>执行过程</span>
      <small>{{ items.length }} 步</small>
    </div>
    <div class="timeline" v-if="items.length">
      <article v-for="item in items" :key="trackBy(item)" class="timeline-item" :class="cssClass(item.status)">
        <div class="rail"><span>{{ icon(item.status) }}</span></div>
        <div class="timeline-card">
          <div class="timeline-head">
            <strong>{{ title(item) }}</strong>
            <em>{{ statusLabel(item.status) }}</em>
          </div>
          <p v-if="item.summary">{{ displaySummary(item.summary) }}</p>
          <div class="timeline-meta">
            <span v-if="sourceLabel(item)" class="agent-chip">{{ sourceLabel(item) }}</span>
            <span>{{ item.type }}</span>
            <span v-if="duration(item)">{{ duration(item) }}</span>
          </div>
          <details v-if="isExpanded(item)">
            <summary>查看详情</summary>
            <pre v-if="refText(item)">{{ refText(item) }}</pre>
            <pre v-if="detailText(item)">{{ detailText(item) }}</pre>
          </details>
        </div>
      </article>
    </div>
    <div v-else class="empty-state">开始一轮对话后，这里会显示执行过程。</div>
  </section>
</template>

<style scoped>
.agent-chip {
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 999px;
  color: #1d4ed8;
  font-weight: 700;
  padding: 2px 7px;
}
</style>
