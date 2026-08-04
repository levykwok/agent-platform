<script setup lang="ts">
import type { EpisodicMemoryRef, LongTermMemoryRef } from '../types'

defineProps<{
  longTerm: LongTermMemoryRef[]
  episodic: EpisodicMemoryRef[]
}>()

const emit = defineEmits<{ feedback: [id: number, action: string] }>()

function confidence(value?: number) {
  if (typeof value !== 'number') return 'n/a'
  return `${Math.round(value * 100)}%`
}
</script>

<template>
  <section class="panel-section memory-panel">
    <div class="section-title">
      <span>Memory Used</span>
      <small>{{ longTerm.length + episodic.length }} refs</small>
    </div>

    <h3>Long-term Memory</h3>
    <article v-for="item in longTerm" :key="item.id" class="memory-card">
      <div class="memory-topline">
        <strong>{{ item.memory_type || 'memory' }}</strong>
        <span>{{ confidence(item.confidence) }}</span>
      </div>
      <p>{{ item.content }}</p>
      <div class="memory-meta">
        <span>#{{ item.id }}</span>
        <span>{{ item.scope || 'user' }}</span>
        <span>{{ item.domain || 'all-domain' }}</span>
        <span>{{ item.status || 'active' }}</span>
      </div>
      <details v-if="item.why_used?.length">
        <summary>为什么使用</summary>
        <ul>
          <li v-for="reason in item.why_used" :key="reason">{{ reason }}</li>
        </ul>
      </details>
      <div class="memory-actions">
        <button @click="emit('feedback', item.id, 'confirm')" title="确认这条记忆有用">确认</button>
        <button @click="emit('feedback', item.id, 'disable')" title="停用这条记忆，以后不再召回（记录保留，可恢复）">别再用</button>
        <button @click="emit('feedback', item.id, 'irrelevant')" title="这次召回跟当前问题不相关；只记一次召回反馈，不会改动或删除这条记忆">不相关</button>
      </div>
    </article>
    <div v-if="!longTerm.length" class="empty-state compact">本轮没有引用长期记忆。</div>

    <h3>Past Conversations</h3>
    <article v-for="item in episodic" :key="item.id" class="memory-card episodic-card">
      <div class="memory-topline">
        <strong>episodic #{{ item.id }}</strong>
        <span>{{ item.session_id || 'session' }}</span>
      </div>
      <p>{{ item.summary }}</p>
      <div class="memory-meta">
        <span v-for="topic in item.topics || []" :key="topic">{{ topic }}</span>
      </div>
      <details v-if="item.why_used?.length">
        <summary>为什么使用</summary>
        <ul>
          <li v-for="reason in item.why_used" :key="reason">{{ reason }}</li>
        </ul>
      </details>
    </article>
    <div v-if="!episodic.length" class="empty-state compact">本轮没有引用历史会话。</div>
  </section>
</template>
