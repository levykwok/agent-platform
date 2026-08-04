<script setup lang="ts">
import { computed, ref } from 'vue'
import { currentOrgId, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'

const props = defineProps<{
  runId: string
  waiting: JsonMap
}>()

const emit = defineEmits<{
  resumed: [payload: JsonMap]
  rejected: [payload: JsonMap]
}>()

const selectedOption = ref('')
const textAnswer = ref('')
const comment = ref('')
const submitting = ref(false)

const item = computed(() => props.waiting || {})
const waitingType = computed(() => String(item.value.waiting_type || 'confirmation'))
const options = computed<JsonMap[]>(() => Array.isArray(item.value.options) ? item.value.options : [])
const waitingId = computed(() => String(item.value.waiting_id || ''))
const stepId = computed(() => String(item.value.step_id || 'execute'))
const pendingAction = computed<JsonMap>(() => {
  const ref = item.value.resume_ref && typeof item.value.resume_ref === 'object' ? item.value.resume_ref : {}
  const direct = item.value.pending_action && typeof item.value.pending_action === 'object' ? item.value.pending_action : {}
  const metadata = item.value.metadata && typeof item.value.metadata === 'object' ? item.value.metadata : {}
  return direct.type ? direct : (metadata.pending_action || ref.pending_action || {})
})
const title = computed(() => ({
  clarification: '需要补充信息',
  selection: '需要选择',
  confirmation: '需要确认',
  approval: '需要审批',
} as Record<string, string>)[waitingType.value] || '等待用户输入')
const primaryLabel = computed(() => waitingType.value === 'approval' ? '批准并继续' : waitingType.value === 'confirmation' ? '确认继续' : '提交并继续')
const rejectLabel = computed(() => waitingType.value === 'approval' ? '驳回' : '取消')
const canSubmit = computed(() => {
  if (waitingType.value === 'selection' && options.value.length) return Boolean(selectedOption.value)
  if (waitingType.value === 'clarification') return Boolean(textAnswer.value.trim())
  return true
})

function optionLabel(opt: JsonMap) {
  return String(opt.label || opt.title || opt.name || opt.id || '选项')
}

function optionDescription(opt: JsonMap) {
  return String(opt.description || opt.summary || '')
}

function buildAnswer(approved = true): JsonMap {
  if (waitingType.value === 'selection') {
    const selected = options.value.find((opt) => String(opt.id || opt.value?.id || optionLabel(opt)) === selectedOption.value) || {}
    return {
      selected_option_id: selectedOption.value,
      value: selected.value ?? selected,
      text: comment.value.trim(),
    }
  }
  if (waitingType.value === 'clarification') return { text: textAnswer.value.trim(), comment: comment.value.trim() }
  if (waitingType.value === 'approval') return { approved, comment: comment.value.trim() }
  return { confirmed: approved, comment: comment.value.trim() }
}

async function submit() {
  if (!props.runId || !waitingId.value || !canSubmit.value || submitting.value) return
  submitting.value = true
  try {
    const payload = {
      step_id: stepId.value,
      action: 'submit',
      answer: buildAnswer(true),
      idempotency_key: `ui_${waitingId.value}_${Date.now()}`,
    }
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(props.runId)}/waiting/${encodeURIComponent(waitingId.value)}/resume`, {
      method: 'POST',
      headers: makeHeaders(true, currentOrgId()),
      body: JSON.stringify(payload),
    }))
    notifySuccess('已提交，继续执行中')
    emit('resumed', data)
  } catch (err) {
    notifyError(err)
  } finally {
    submitting.value = false
  }
}

async function reject() {
  if (!props.runId || !waitingId.value || submitting.value) return
  submitting.value = true
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(props.runId)}/waiting/${encodeURIComponent(waitingId.value)}/reject`, {
      method: 'POST',
      headers: makeHeaders(true, currentOrgId()),
      body: JSON.stringify({
        step_id: stepId.value,
        reason: comment.value.trim() || 'user rejected',
        idempotency_key: `ui_reject_${waitingId.value}_${Date.now()}`,
      }),
    }))
    notifySuccess('已取消等待')
    emit('rejected', data)
  } catch (err) {
    notifyError(err)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <section class="waiting-card">
    <div class="waiting-head">
      <div>
        <div class="waiting-title">{{ title }}</div>
        <div class="waiting-sub mono">{{ waitingId }} / {{ stepId }}</div>
      </div>
      <span class="badge badge-amber">{{ waitingType }}</span>
    </div>
    <p class="waiting-question">{{ item.question || item.reason || '请确认是否继续执行。' }}</p>

    <div v-if="pendingAction && pendingAction.type" class="waiting-pending">
      <span>{{ pendingAction.type }}</span>
      <b v-if="pendingAction.tool_name">{{ pendingAction.tool_name }}</b>
      <b v-else-if="pendingAction.node_id">{{ pendingAction.node_id }}</b>
    </div>

    <div v-if="waitingType === 'selection' && options.length" class="waiting-options">
      <label v-for="opt in options" :key="String(opt.id || opt.value?.id || optionLabel(opt))" class="waiting-option" :class="{ selected: selectedOption === String(opt.id || opt.value?.id || optionLabel(opt)) }">
        <input v-model="selectedOption" type="radio" :value="String(opt.id || opt.value?.id || optionLabel(opt))" />
        <span>
          <strong>{{ optionLabel(opt) }}</strong>
          <small v-if="optionDescription(opt)">{{ optionDescription(opt) }}</small>
        </span>
      </label>
    </div>

    <textarea v-if="waitingType === 'clarification'" v-model="textAnswer" class="waiting-text" rows="3" placeholder="补充说明..." />
    <textarea v-model="comment" class="waiting-text compact" rows="2" :placeholder="waitingType === 'approval' ? '审批意见，可选' : '备注，可选'" />

    <div class="waiting-actions">
      <button class="btn btn-primary btn-sm" :disabled="submitting || !canSubmit" @click="submit">{{ submitting ? '提交中...' : primaryLabel }}</button>
      <button class="btn btn-danger btn-sm" :disabled="submitting" @click="reject">{{ rejectLabel }}</button>
    </div>
  </section>
</template>

<style scoped>
.waiting-card { border: 1px solid #facc15; background: #fffbeb; border-radius: 10px; padding: 12px; display: grid; gap: 10px; max-width: 620px; }
.waiting-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.waiting-title { font-size: 13px; font-weight: 800; color: #78350f; }
.waiting-sub { margin-top: 3px; font-size: 10.5px; color: #a16207; word-break: break-all; }
.waiting-question { margin: 0; font-size: 13px; line-height: 1.6; color: #451a03; white-space: pre-wrap; word-break: break-word; }
.waiting-pending { display: inline-flex; align-items: center; gap: 8px; width: fit-content; border: 1px solid #fde68a; border-radius: 7px; background: #fff7ed; padding: 5px 8px; font-size: 11px; color: #92400e; }
.waiting-pending span { font-family: ui-monospace, Menlo, Consolas, monospace; }
.waiting-options { display: grid; gap: 7px; }
.waiting-option { display: grid; grid-template-columns: 18px 1fr; gap: 8px; align-items: flex-start; border: 1px solid #fde68a; border-radius: 8px; background: #fff; padding: 9px; cursor: pointer; }
.waiting-option.selected { border-color: #f59e0b; box-shadow: 0 0 0 3px rgba(245,158,11,.12); }
.waiting-option input { width: 14px; height: 14px; margin-top: 2px; }
.waiting-option strong { display: block; font-size: 12px; color: #451a03; }
.waiting-option small { display: block; margin-top: 3px; font-size: 11px; color: #92400e; line-height: 1.5; }
.waiting-text { width: 100%; border-color: #fde68a; background: #fff; }
.waiting-text.compact { min-height: 52px; }
.waiting-actions { display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
</style>
