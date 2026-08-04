<script setup lang="ts">
import { dialogState, resolveDialog } from '../stores/dialog'

function cancel() {
  resolveDialog(null)
}

function confirm() {
  resolveDialog({ ...dialogState.values })
}
</script>

<template>
  <div v-if="dialogState.open" class="dialog-backdrop" @click.self="cancel">
    <div class="dialog-card">
      <h3 class="dialog-title">{{ dialogState.title }}</h3>
      <p v-if="dialogState.message" class="dialog-message">{{ dialogState.message }}</p>
      <div v-for="field in dialogState.fields" :key="field.key" class="field wide dialog-field">
        <label>{{ field.label }}</label>
        <select v-if="field.type === 'select'" v-model="dialogState.values[field.key]">
          <option v-for="opt in field.options || []" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
        </select>
        <textarea v-else-if="field.type === 'textarea'" v-model="dialogState.values[field.key]" :placeholder="field.placeholder" />
        <input v-else v-model="dialogState.values[field.key]" :placeholder="field.placeholder" @keydown.enter="confirm" />
      </div>
      <div class="dialog-actions">
        <button class="btn btn-ghost" @click="cancel">{{ dialogState.cancelLabel }}</button>
        <button class="btn" :class="dialogState.danger ? 'btn-danger' : 'btn-primary'" @click="confirm">{{ dialogState.confirmLabel }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.dialog-backdrop { position: fixed; inset: 0; background: rgba(15,23,42,.45); display: flex; align-items: center; justify-content: center; z-index: 1100; }
.dialog-card { background: #fff; border-radius: 12px; padding: 20px; width: min(420px, 92vw); box-shadow: 0 24px 60px rgba(15,23,42,.25); display: flex; flex-direction: column; gap: 12px; }
.dialog-title { font-size: 15px; font-weight: 700; }
.dialog-message { font-size: 13px; color: var(--muted); line-height: 1.6; }
.dialog-field { min-width: 0; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
</style>
