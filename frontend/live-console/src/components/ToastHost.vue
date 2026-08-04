<script setup lang="ts">
import { toastState, dismissToast } from '../stores/notify'
</script>

<template>
  <div class="toast-host">
    <div v-for="item in toastState.items" :key="item.id" class="toast" :class="`toast-${item.kind}`" @click="dismissToast(item.id)">
      <span class="toast-icon">{{ item.kind === 'success' ? '✓' : item.kind === 'error' ? '×' : 'i' }}</span>
      <span class="toast-msg">{{ item.message }}</span>
    </div>
  </div>
</template>

<style scoped>
.toast-host { position: fixed; top: 16px; right: 16px; z-index: 1000; display: flex; flex-direction: column; gap: 8px; max-width: 360px; }
.toast { display: flex; align-items: flex-start; gap: 10px; padding: 10px 14px; border-radius: 10px; background: #fff; border: 1px solid var(--border); box-shadow: 0 10px 30px rgba(15,23,42,.12); font-size: 13px; line-height: 1.5; cursor: pointer; animation: toast-in .15s ease-out; }
.toast-icon { width: 18px; height: 18px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 800; flex-shrink: 0; color: #fff; }
.toast-success .toast-icon { background: var(--green); }
.toast-error .toast-icon { background: var(--red); }
.toast-info .toast-icon { background: var(--blue); }
.toast-msg { word-break: break-word; white-space: pre-wrap; }
@keyframes toast-in { from { opacity: 0; transform: translateY(-6px); } to { opacity: 1; transform: translateY(0); } }
</style>
