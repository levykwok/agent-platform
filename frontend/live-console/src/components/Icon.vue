<script setup lang="ts">
defineProps<{ name: string }>()
</script>

<template>
  <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-if="name === 'home'">
      <path d="M3 11.5 12 4l9 7.5" /><path d="M5 10v9a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1v-9" />
    </template>
    <template v-else-if="name === 'knowledge'">
      <path d="M4 5a2 2 0 0 1 2-2h12a1 1 0 0 1 1 1v15a1 1 0 0 1-1 1H6a2 2 0 0 0-2 2V5Z" /><path d="M8 7h8M8 11h8" />
    </template>
    <template v-else-if="name === 'qa'">
      <path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2Z" /><path d="M8 9h8M8 12.5h5" />
    </template>
    <template v-else-if="name === 'kg'">
      <circle cx="6" cy="6" r="2.5" /><circle cx="18" cy="6" r="2.5" /><circle cx="12" cy="18" r="2.5" />
      <path d="M8 7.2 16 7.2M7.2 8.2 11 16M16.8 8.2 13 16" />
    </template>
    <template v-else-if="name === 'skills'">
      <path d="M13 2 4 14h6l-1 8 9-12h-6z" />
    </template>
    <template v-else-if="name === 'tools'">
      <path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L3 18l3 3 6.3-6.3a4 4 0 0 0 5.4-5.4l-2.4 2.4-2.6-.6-.6-2.6Z" />
    </template>
    <template v-else-if="name === 'mcp'">
      <path d="M9 7V4M15 7V4M9 20v-3M15 20v-3" />
      <rect x="6" y="7" width="12" height="10" rx="2" /><path d="M6 11h12" />
    </template>
    <template v-else-if="name === 'models'">
      <rect x="4" y="4" width="16" height="16" rx="2" /><rect x="9" y="9" width="6" height="6" rx="1" />
      <path d="M9 2v2M15 2v2M9 20v2M15 20v2M2 9h2M2 15h2M20 9h2M20 15h2" />
    </template>
    <template v-else-if="name === 'agents'">
      <rect x="4" y="8" width="16" height="11" rx="2" /><circle cx="9" cy="13.5" r="1.4" /><circle cx="15" cy="13.5" r="1.4" />
      <path d="M12 8V5M9 5h6" />
    </template>
    <template v-else-if="name === 'memory'">
      <path d="M9 4a3 3 0 0 0-3 3v.2A3 3 0 0 0 5 12a3 3 0 0 0 1 5.8V18a3 3 0 0 0 3 3 2 2 0 0 0 2-2V6a2 2 0 0 0-2-2Z" />
      <path d="M15 4a3 3 0 0 1 3 3v.2A3 3 0 0 1 19 12a3 3 0 0 1-1 5.8V18a3 3 0 0 1-3 3 2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z" />
    </template>
    <template v-else-if="name === 'docs'">
      <path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11a2 2 0 0 1 2 2v16a2 2 0 0 0-2-2H6.5A2.5 2.5 0 0 0 4 21.5v-16Z" />
      <path d="M20 5.5A2.5 2.5 0 0 0 17.5 3H13v18a2 2 0 0 1 2-2h2.5a2.5 2.5 0 0 1 2.5 2.5v-16Z" />
    </template>
    <template v-else-if="name === 'infra'">
      <rect x="3" y="4" width="18" height="6" rx="1.5" /><rect x="3" y="14" width="18" height="6" rx="1.5" />
      <path d="M7 7h.01M7 17h.01" />
    </template>
    <template v-else>
      <circle cx="12" cy="12" r="9" />
    </template>
  </svg>
</template>

<style scoped>
.icon { width: 16px; height: 16px; flex-shrink: 0; }
</style>
