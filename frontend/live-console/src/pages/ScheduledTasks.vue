<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { fmtDate, makeHeaders, readJson } from '../lib/platformApi'

type Row = Record<string, any>

const tasks = ref<Row[]>([])
const runs = ref<Row[]>([])
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const selected = ref<Row | null>(null)
const form = reactive({ task_id: '', name: '', agent_id: '', session_id: '', prompt: '', cron: '0 */5 * * * *', timezone: '', webhook_url: '', webhook_secret: '', webhook_enabled: true })

function resetForm() {
  Object.assign(form, { task_id: '', name: '', agent_id: '', session_id: '', prompt: '', cron: '0 */5 * * * *', timezone: '', webhook_url: '', webhook_secret: '', webhook_enabled: true })
  selected.value = null
  runs.value = []
}

function edit(task: Row) {
  Object.assign(form, {
    task_id: task.task_id || '', name: task.name || '', agent_id: task.agent_id || '', session_id: task.session_id || '',
    prompt: task.prompt || '', cron: task.cron_expression || task.cron || '', timezone: task.timezone || '',
    webhook_url: task.webhook_url || '', webhook_secret: '', webhook_enabled: task.webhook_enabled !== false,
  })
  selected.value = task
  loadRuns(task)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await readJson<Row>(await fetch('/api/scheduled-tasks', { headers: makeHeaders(false) }))
    tasks.value = Array.isArray(data.items) ? data.items : []
  } catch (e: any) { error.value = e?.message || '读取定时任务失败' } finally { loading.value = false }
}

async function loadRuns(task: Row) {
  try {
    const data = await readJson<Row>(await fetch(`/api/scheduled-tasks/${encodeURIComponent(task.task_id)}/runs?limit=20`, { headers: makeHeaders(false) }))
    runs.value = Array.isArray(data.items) ? data.items : []
  } catch { runs.value = [] }
}

async function save() {
  saving.value = true; error.value = ''
  try {
    const method = form.task_id ? 'PUT' : 'POST'
    const path = form.task_id ? `/api/scheduled-tasks/${encodeURIComponent(form.task_id)}` : '/api/scheduled-tasks'
    const payload: Record<string, any> = { ...form }
    // The API never returns the secret. A blank edit field means “keep the existing secret”.
    if (!form.webhook_secret) delete payload.webhook_secret
    const data = await readJson<Row>(await fetch(path, { method, headers: makeHeaders(true), body: JSON.stringify(payload) }))
    const task = data.item
    await load()
    if (task) edit(task)
  } catch (e: any) { error.value = e?.message || '保存定时任务失败' } finally { saving.value = false }
}

async function toggle(task: Row) {
  try {
    await readJson(await fetch(`/api/scheduled-tasks/${encodeURIComponent(task.task_id)}/${task.enabled ? 'disable' : 'enable'}`, { method: 'POST', headers: makeHeaders(false) }))
    await load()
  } catch (e: any) { error.value = e?.message || '更新任务状态失败' }
}

async function runNow(task: Row) {
  try {
    await readJson(await fetch(`/api/scheduled-tasks/${encodeURIComponent(task.task_id)}/run-now`, { method: 'POST', headers: makeHeaders(false) }))
    await loadRuns(task)
  } catch (e: any) { error.value = e?.message || '立即执行失败' }
}

async function remove(task: Row) {
  if (!window.confirm(`确定删除「${task.name || task.task_id}」？`)) return
  try {
    await readJson(await fetch(`/api/scheduled-tasks/${encodeURIComponent(task.task_id)}`, { method: 'DELETE', headers: makeHeaders(false) }))
    if (selected.value?.task_id === task.task_id) resetForm()
    await load()
  } catch (e: any) { error.value = e?.message || '删除任务失败' }
}

onMounted(load)
</script>

<template>
  <section class="scheduled-page">
    <div class="scheduled-head">
      <div><div class="eyebrow">SCHEDULED TASKS</div><h1>定时任务</h1><p>按 Cron 定时唤起 Agent，结果会写入绑定会话并生成通知。</p></div>
      <div class="scheduled-actions"><button class="btn btn-ghost" @click="load">刷新</button><button class="btn btn-primary" @click="resetForm">新建任务</button></div>
    </div>
    <div v-if="error" class="scheduled-error">{{ error }}</div>
    <div class="scheduled-grid">
      <div class="scheduled-panel task-list-panel">
        <div class="panel-title">我的任务 <span>{{ tasks.length }}</span></div>
        <div v-if="loading" class="empty">读取中…</div>
        <div v-else-if="!tasks.length" class="empty">还没有定时任务<br><small>点击右上角新建一个。</small></div>
        <button v-for="task in tasks" :key="task.task_id" class="task-item" :class="{selected: selected?.task_id === task.task_id}" @click="edit(task)">
          <span class="task-status" :class="task.enabled ? 'on' : 'off'"></span>
          <span class="task-item-main"><strong>{{ task.name }}</strong><small>{{ task.agent_id }} · {{ task.cron_expression }}</small><small>下次：{{ fmtDate(task.next_run_at) || '—' }}</small></span>
        </button>
      </div>
      <div class="scheduled-panel form-panel">
        <div class="panel-title">{{ form.task_id ? '编辑任务' : '创建任务' }}</div>
        <div class="field-grid">
          <label>名称<input v-model="form.name" placeholder="例如：每日行业摘要" /></label>
          <label>Agent ID<input v-model="form.agent_id" placeholder="已发布 Agent ID" /></label>
          <label class="wide">Prompt<textarea v-model="form.prompt" rows="5" placeholder="到点后发送给 Agent 的指令"></textarea></label>
          <label>Cron<input v-model="form.cron" placeholder="0 0 9 * * *" /><small>支持 5 位或 Spring 6 位格式</small></label>
          <label>时区<input v-model="form.timezone" placeholder="Asia/Shanghai（可留空）" /></label>
          <label class="wide">会话 ID（可选）<input v-model="form.session_id" placeholder="留空则自动创建绑定会话" /></label>
          <label class="wide">Webhook 地址（可选）<input v-model="form.webhook_url" placeholder="https://industrial-ai.example.com/hooks/agent-result" /><small>任务完成后向此地址发送结果；留空则只写 Session 和站内通知</small></label>
          <label>Webhook Secret<input v-model="form.webhook_secret" type="password" placeholder="新建或更换签名 Secret" /><small>编辑时留空表示保留原 Secret</small></label>
          <label class="webhook-toggle"><span>启用 Webhook</span><input v-model="form.webhook_enabled" type="checkbox" /></label>
        </div>
        <div class="form-actions"><button class="btn btn-primary" :disabled="saving" @click="save">{{ saving ? '保存中…' : '保存任务' }}</button><button class="btn btn-ghost" @click="resetForm">清空</button></div>
        <div v-if="form.task_id" class="detail-actions"><button class="btn btn-ghost" @click="toggle(selected!)">{{ selected?.enabled ? '暂停' : '启用' }}</button><button class="btn btn-ghost" @click="runNow(selected!)">立即执行</button><button class="btn btn-danger" @click="remove(selected!)">删除</button></div>
        <div v-if="form.task_id" class="run-history"><div class="panel-title">最近执行</div><div v-if="!runs.length" class="empty compact">暂无执行记录</div><div v-for="run in runs" :key="run.run_id" class="run-row"><span class="run-dot" :class="String(run.status).toLowerCase()"></span><span><strong>{{ run.status }}</strong><small>{{ fmtDate(run.started_at) }}<template v-if="run.error_message"> · {{ run.error_message }}</template></small></span></div></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.scheduled-page{padding:24px 28px 40px;min-height:100%;background:#f8fafc;color:#0f172a}.scheduled-head{display:flex;justify-content:space-between;align-items:flex-end;gap:20px;margin-bottom:20px}.eyebrow{font-size:10px;letter-spacing:.14em;font-weight:800;color:#2563eb;margin-bottom:7px}.scheduled-head h1{font-size:26px;line-height:1.15;margin:0 0 7px;letter-spacing:-.03em}.scheduled-head p{margin:0;color:#64748b;font-size:13px}.scheduled-actions,.form-actions,.detail-actions{display:flex;gap:8px;flex-wrap:wrap}.scheduled-grid{display:grid;grid-template-columns:320px minmax(0,1fr);gap:14px;align-items:start}.scheduled-panel{background:#fff;border:1px solid #e2e8f0;border-radius:14px;box-shadow:0 4px 18px rgba(15,23,42,.04)}.task-list-panel{padding:14px}.form-panel{padding:18px}.panel-title{font-size:14px;font-weight:800;margin-bottom:14px}.panel-title span{color:#94a3b8;font-size:11px;margin-left:5px}.task-item{width:100%;display:flex;gap:9px;text-align:left;border:1px solid transparent;background:#f8fafc;border-radius:9px;padding:10px;margin-bottom:7px;cursor:pointer}.task-item:hover,.task-item.selected{border-color:#93c5fd;background:#eff6ff}.task-status{width:8px;height:8px;flex:0 0 8px;border-radius:50%;margin-top:4px}.task-status.on{background:#22c55e;box-shadow:0 0 0 3px #dcfce7}.task-status.off{background:#94a3b8}.task-item-main{display:flex;flex-direction:column;gap:3px;min-width:0}.task-item strong{font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.task-item small{font-size:10px;color:#64748b;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.empty{padding:34px 10px;text-align:center;color:#94a3b8;font-size:12px;line-height:1.7}.empty small{font-size:10px}.compact{padding:16px}.scheduled-error{padding:10px 12px;border:1px solid #fecaca;background:#fff7f7;color:#b91c1c;border-radius:8px;font-size:12px;margin-bottom:14px}.field-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.field-grid label{display:flex;flex-direction:column;gap:6px;font-size:11px;color:#64748b}.field-grid label.wide{grid-column:1 / -1}.field-grid input,.field-grid textarea{width:100%;border:1px solid #cbd5e1;border-radius:8px;padding:9px;font:inherit;font-size:12px;color:#0f172a;background:#fff}.field-grid textarea{resize:vertical;line-height:1.6}.field-grid small{font-size:10px;color:#94a3b8;margin-top:-2px}.webhook-toggle{justify-content:center}.webhook-toggle input{width:auto;align-self:flex-start;margin-top:7px}.form-actions{margin-top:17px}.detail-actions{border-top:1px solid #e2e8f0;margin-top:17px;padding-top:14px}.run-history{border-top:1px solid #e2e8f0;margin-top:20px;padding-top:16px}.run-row{display:flex;gap:8px;align-items:flex-start;border-top:1px solid #f1f5f9;padding:9px 0;font-size:11px}.run-row span:last-child{display:flex;flex-direction:column;gap:3px}.run-row small{color:#94a3b8}.run-dot{width:7px;height:7px;border-radius:50%;margin-top:4px;background:#94a3b8}.run-dot.succeeded{background:#22c55e}.run-dot.failed{background:#ef4444}@media(max-width:820px){.scheduled-head{display:block}.scheduled-actions{margin-top:14px}.scheduled-grid{grid-template-columns:1fr}.field-grid{grid-template-columns:1fr}.field-grid label.wide{grid-column:auto}}
</style>
