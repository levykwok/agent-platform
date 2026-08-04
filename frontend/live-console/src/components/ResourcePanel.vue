<script setup lang="ts">
import type { ConsoleResource } from '../types'

const props = defineProps<{
  resources: Record<string, ConsoleResource[]>
}>()

function allResources(groups: Record<string, ConsoleResource[]>) {
  return Object.entries(groups).flatMap(([group, items]) => items.map((item) => ({ ...item, group })))
}
</script>

<template>
  <section class="panel-section">
    <div class="section-title">
      <span>Session Resources</span>
      <small>{{ allResources(props.resources).length }} items</small>
    </div>
    <div v-if="allResources(props.resources).length" class="resource-list">
      <article v-for="item in allResources(props.resources)" :key="`${item.group}:${item.id}`" class="resource-card">
        <div>
          <strong>{{ item.title || item.id }}</strong>
          <p>{{ item.kind }} · {{ item.group }} · {{ item.status || 'unknown' }}</p>
        </div>
        <details>
          <summary>refs</summary>
          <pre>{{ JSON.stringify({ refs: item.refs, metadata: item.metadata }, null, 2) }}</pre>
        </details>
      </article>
    </div>
    <div v-else class="empty-state">当前 session 暂无资源引用。</div>
  </section>
</template>
