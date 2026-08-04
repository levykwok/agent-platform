<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { confidenceText, useMemoryApi } from './memoryPageSupport'
import { currentDomain, fmtDate, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'
import { confirmDialog, formDialog, promptDialog } from '../stores/dialog'

const api = useMemoryApi()
const domainOptions = ref<JsonMap[]>([{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }])
async function loadDomains() {
  try {
    const status = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: api.headers(false) }))
    const raw = status.domains && typeof status.domains === 'object' ? status.domains as JsonMap : {}
    domainOptions.value = [{ domain: '', label: '全部业务域' }, { domain: 'platform', label: '平台' }, ...Object.entries(raw).map(([d, s]) => ({ domain: d, label: String((s as JsonMap)?.display_name || d) })).filter((r) => r.domain !== 'platform')]
  } catch { /* ignore */ }
}
const rows = ref<JsonMap[]>([])
const keyword = ref('')
const filters = reactive({ scope: '', status: '', memory_type: '', domain: currentDomain('') })
const editor = reactive({ id: '', content: '', scope: 'user', memory_type: 'preference', status: 'active', confidence: 1 })
const episodic = ref<JsonMap>({})
const dailyRows = ref<JsonMap[]>([])
const memoryOverview = ref<JsonMap>({})
const memoryAgentId = ref('researcher')
const agentOptions = ref<JsonMap[]>([])
const selected = ref<JsonMap | null>(null)
const detailContent = ref('')
const auditRows = ref<JsonMap[]>([])
const sourceRun = ref<JsonMap | null>(null)
const statusText = ref('')
const loading = ref(false)
const editorOpen = ref(false)
const contextLabel = computed(() => `${api.orgId()} / ${api.userId()}${filters.domain ? ' / ' + filters.domain : ''}`)
function memoryStatusLabel(status: unknown) {
  const map: Record<string, string> = {
    pending_confirm: '待确认',
    active: '已生效',
    inactive: '未生效',
    expired: '已过期',
    rejected: '已拒绝',
    merged: '已合并',
    deleted: '已删除',
    disabled: '停用',
  }
  return map[String(status || 'active')] || String(status || 'active')
}
function memoryTypeLabel(type: unknown) {
  const map: Record<string, string> = {
    preference: '偏好',
    fact: '事实',
    constraint: '规则约束',
    experience: '经验',
    result: '结果',
  }
  return map[String(type || 'result')] || String(type || '')
}
const filteredRows = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return rows.value
  return rows.value.filter((item) => [item.id, item.content, item.content_summary, item.domain, item.scope, item.memory_type, item.status].join(' ').toLowerCase().includes(q))
})
const memoryMd = computed(() => String(memoryOverview.value.memory_md || ''))
const runtimeMemoryEntries = computed(() => {
  let inManagedBlock = false
  return memoryMd.value.split(/\r?\n/).flatMap((line) => {
    const trimmed = line.trim()
    if (trimmed === '<!-- agent-platform-memory:start -->') { inManagedBlock = true; return [] }
    if (trimmed === '<!-- agent-platform-memory:end -->') { inManagedBlock = false; return [] }
    if (inManagedBlock || !trimmed.startsWith('- ')) return []
    const content = trimmed.slice(2).trim()
    return content ? [content] : []
  })
})
const stats = computed(() => ({ count: filteredRows.value.length, pending: filteredRows.value.filter((item) => item.status === 'pending_confirm').length, active: filteredRows.value.filter((item) => (item.status || 'active') === 'active').length, disabled: filteredRows.value.filter((item) => ['inactive','disabled','expired','rejected','deleted','merged'].includes(String(item.status || ''))).length, actions: auditRows.value.length }))

function queryString() {
  const p = new URLSearchParams({ limit: '200' })
  if (filters.scope) p.set('scope', filters.scope)
  if (filters.status) p.set('status', filters.status)
  if (filters.memory_type) p.set('memory_type', filters.memory_type)
  if (filters.domain) p.set('domain', filters.domain)
  return p.toString()
}
function pretty(value: unknown) { try { return JSON.stringify(value ?? {}, null, 2) } catch { return String(value || '') } }
function runIdOf(item: JsonMap | null) { return String(item?.source_links?.run_id || item?.source_run_id || '') }
async function loadRows() { rows.value = await api.list(queryString()) }
async function loadAgents() {
  try {
    const data = await readJson<JsonMap>(await fetch('/platform/frontend/agents', { headers: api.headers(false) }))
    agentOptions.value = (Array.isArray(data.items) ? data.items : Array.isArray(data.agents) ? data.agents : []) as JsonMap[]
    if (!memoryAgentId.value && agentOptions.value[0]) memoryAgentId.value = String(agentOptions.value[0].agent_id || agentOptions.value[0].id || '')
  } catch {
    agentOptions.value = [{ agent_id: 'researcher', display_name: 'researcher' }]
  }
}
async function loadDaily() {
  const data = await api.daily(memoryAgentId.value)
  memoryOverview.value = (data.item || data) as JsonMap
  const overviewDaily = memoryOverview.value.daily
  dailyRows.value = Array.isArray(data.items) ? data.items : Array.isArray(overviewDaily) ? overviewDaily as JsonMap[] : []
}
async function loadEpisodic() { episodic.value = await api.episodic(filters.domain) }
async function loadAll() {
  loading.value = true
  statusText.value = '加载中…'
  try {
    await loadAgents()
    await Promise.all([loadRows(), loadDaily(), loadEpisodic()])
    statusText.value = `已刷新 · ${new Date().toLocaleTimeString('zh-CN')}`
  } catch (err) {
    statusText.value = err instanceof Error ? err.message : String(err)
  } finally {
    loading.value = false
  }
}
function edit(item: JsonMap) { editor.id = String(item.id || ''); editor.content = String(item.content || ''); editor.scope = String(item.scope || 'user'); editor.memory_type = String(item.memory_type || 'preference'); editor.status = String(item.status || 'active'); editor.confidence = Number(item.confidence ?? 1) }
function resetEditor() { editor.id = ''; editor.content = ''; editor.scope = 'user'; editor.memory_type = 'preference'; editor.status = 'active'; editor.confidence = 1 }
function openEditor(item?: JsonMap) { if (item) edit(item); else resetEditor(); editorOpen.value = true }
async function save() { try { await api.save(editor, filters.domain); notifySuccess(editor.id ? '已保存' : '已新增'); resetEditor(); editorOpen.value = false; await loadRows() } catch (err) { notifyError(err) } }
async function setStatus(item: JsonMap, status: string) { try { await api.patchStatus(item.id, status); notifySuccess(status === 'active' ? '已启用' : '已停用'); await loadRows() } catch (err) { notifyError(err) } }
async function confirmMemory(item: JsonMap) { try { await api.confirm(item.id); notifySuccess('已确认'); await loadRows(); if (selected.value?.id === item.id) await openDetail(item) } catch (err) { notifyError(err) } }
async function reject(item: JsonMap) {
  const reason = await promptDialog('拒绝记忆', '拒绝原因（可留空）', '')
  if (reason === null) return
  try { await api.reject(item.id, reason || ''); notifySuccess('已拒绝'); await loadRows() } catch (err) { notifyError(err) }
}
async function remove(item: JsonMap) {
  if (!await confirmDialog(`确定删除记忆 #${item.id} 吗？`, { title: '删除记忆', danger: true })) return
  try { await api.remove(item.id); notifySuccess('已删除'); if (selected.value?.id === item.id) closeDetail(); await loadRows() } catch (err) { notifyError(err) }
}
async function removeRuntimeMemoryEntry(content: string) {
  if (!await confirmDialog(`确定从 ${memoryAgentId.value} 的 MEMORY.md 删除这条记忆吗？`, { title: '删除运行记忆', danger: true })) return
  try {
    const result = await api.removeRuntimeEntry(memoryAgentId.value, content)
    if (!result.removed) throw new Error('未找到可删除的本地记忆条目')
    notifySuccess('已从 Agent 运行记忆删除')
    await loadDaily()
  } catch (err) { notifyError(err) }
}
async function mergeMemory(item: JsonMap) {
  const values = await formDialog({
    title: '合并记忆',
    message: `将记忆 #${item.id} 合并到目标记忆`,
    fields: [
      { key: 'target_id', label: '目标记忆 ID', placeholder: '例如 123' },
      { key: 'update', label: '合并后的目标内容（可留空）', type: 'textarea' },
      { key: 'comment', label: '合并说明（可留空）' },
    ],
  })
  if (!values) return
  const target = Number(values.target_id)
  if (!Number.isInteger(target) || target <= 0 || target === Number(item.id)) { notifyError('目标 ID 无效'); return }
  try {
    await api.merge(item.id, target, values.update || '', values.comment || '')
    notifySuccess('已合并')
    await loadRows()
  } catch (err) { notifyError(err) }
}
async function openDetail(item: JsonMap) {
  const data = await api.get(item.id)
  selected.value = data.item || data
  detailContent.value = String(selected.value?.content || '')
  sourceRun.value = null
  await loadAudit(item.id)
}
function closeDetail() { selected.value = null; auditRows.value = []; sourceRun.value = null }
async function loadAudit(id: unknown) { const p = new URLSearchParams({ target_type: 'long_term', target_id: String(id), limit: '20' }); const data = await api.audit(p.toString()); auditRows.value = Array.isArray(data.items) ? data.items : [] }
async function saveDetail() { if (!selected.value) return; try { await api.patch(selected.value.id, { content: detailContent.value }); notifySuccess('详情内容已保存'); await openDetail(selected.value); await loadRows() } catch (err) { notifyError(err) } }
async function confirmDetail() { if (!selected.value) return; try { await api.confirm(selected.value.id, detailContent.value); notifySuccess('已确认'); closeDetail(); await loadRows() } catch (err) { notifyError(err) } }
async function rejectDetail() {
  if (!selected.value) return
  const reason = await promptDialog('拒绝记忆', '拒绝原因（可留空）', '')
  if (reason === null) return
  try { await api.reject(selected.value.id, reason || ''); notifySuccess('已拒绝'); closeDetail(); await loadRows() } catch (err) { notifyError(err) }
}
async function loadSourceRun() {
  const id = runIdOf(selected.value)
  if (!id) return
  const [run, steps] = await Promise.all([
    readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(id)}`, { headers: api.headers(false) })),
    readJson<JsonMap>(await fetch(`/platform/frontend/agents/runs/${encodeURIComponent(id)}/steps`, { headers: api.headers(false) })),
  ])
  sourceRun.value = { run, steps }
}
function showPendingQueue() { filters.status = 'pending_confirm'; loadRows() }
function showDefaultQueue() { filters.status = ''; loadRows() }

onMounted(() => { loadDomains(); loadAll() })
</script>

<template>
  <div class="memory-page">
    <div class="mem-head">
      <div><div class="mem-title">记忆管理</div><div class="mem-context">{{ contextLabel }}</div></div>
      <div class="mem-head-right"><span class="mem-status">{{ statusText }}</span><button class="btn btn-ghost btn-sm" @click="loadAll">{{ loading ? '刷新中…' : '🔄 刷新' }}</button></div>
    </div>

    <div class="stats">
      <div class="stat"><div class="stat-label">当前列表</div><div class="stat-val">{{ stats.count }}</div></div>
      <div class="stat"><div class="stat-label">待确认</div><div class="stat-val" :class="stats.pending ? 'warn' : ''">{{ stats.pending }}</div></div>
      <div class="stat"><div class="stat-label">启用</div><div class="stat-val">{{ stats.active }}</div></div>
      <div class="stat"><div class="stat-label">停用</div><div class="stat-val">{{ stats.disabled }}</div></div>
      <div class="stat"><div class="stat-label">维护动作</div><div class="stat-val">{{ stats.actions }}</div></div>
    </div>

    <!-- 长期记忆：筛选 + 列表 合为一体 -->
    <section class="panel">
      <div class="section-head">
        <div><div class="section-title">长期记忆</div><div class="section-sub">SQLite 平台治理主库；只会注入“已生效”且未过期的记忆；共享范围修改需管理员角色。</div></div>
        <div class="actions">
          <button class="btn btn-ghost btn-sm" @click="showPendingQueue">待确认队列</button>
          <button class="btn btn-ghost btn-sm" @click="showDefaultQueue">默认列表</button>
          <button class="btn btn-primary btn-sm" @click="openEditor()">+ 新增记忆</button>
        </div>
      </div>
      <div class="toolbar">
        <div class="field"><label>范围</label><select v-model="filters.scope" @change="loadRows"><option value="">全部可见</option><option value="user">个人(user)</option><option value="org">组织(org)</option><option value="global">全局(global)</option></select></div>
        <div class="field"><label>状态</label><select v-model="filters.status" @change="loadRows"><option value="">默认</option><option value="pending_confirm">待确认</option><option value="active">已生效</option><option value="inactive">未生效</option><option value="expired">已过期</option><option value="rejected">已拒绝</option><option value="merged">已合并</option><option value="deleted">已删除</option><option value="disabled">停用</option></select></div>
        <div class="field"><label>类型</label><select v-model="filters.memory_type" @change="loadRows"><option value="">全部</option><option value="preference">偏好</option><option value="fact">事实</option><option value="constraint">规则约束</option><option value="experience">经验</option><option value="result">结果</option></select></div>
        <div class="field"><label>业务域</label><select v-model="filters.domain" @change="loadAll"><option v-for="d in domainOptions" :key="d.domain as string" :value="d.domain">{{ d.label }}</option></select></div>
        <div class="field wide"><label>关键词</label><input v-model="keyword" placeholder="本页过滤记忆正文" /></div>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>ID</th><th>范围</th><th>类型</th><th>状态</th><th>置信度</th><th>内容</th><th>更新时间</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-if="!filteredRows.length"><td colspan="8"><div class="empty">暂无记忆</div></td></tr>
            <tr v-for="m in filteredRows" :key="m.id">
              <td class="mono">{{ m.id }}</td>
              <td>{{ m.scope || 'user' }}<div class="muted-small">{{ m.domain || '' }}</div></td>
              <td>{{ memoryTypeLabel(m.memory_type) }}</td>
              <td><span class="badge" :class="m.status">{{ memoryStatusLabel(m.status) }}</span></td>
              <td>{{ confidenceText(m.confidence) }}</td>
              <td><div class="memory-cell">{{ m.content }}</div></td>
              <td>{{ fmtDate(m.updated_at || m.created_at) }}</td>
              <td><div class="actions">
                <button class="btn small" @click="openDetail(m)">详情</button>
                <button class="btn small" @click="openEditor(m)">编辑</button>
                <button v-if="m.status === 'pending_confirm'" class="btn small" @click="confirmMemory(m)">确认</button>
                <button v-if="m.status === 'pending_confirm'" class="btn small danger" @click="reject(m)">拒绝</button>
                <button class="btn small" @click="setStatus(m, m.status === 'active' ? 'inactive' : 'active')">{{ m.status === 'active' ? '停用' : '启用' }}</button>
                <button class="btn small" @click="mergeMemory(m)">合并</button>
                <button class="btn small danger" @click="remove(m)">删除</button>
              </div></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

      <section class="panel">
      <div class="section-head">
        <div><div class="section-title">AgentScope 运行记忆</div><div class="section-sub">展示 agent 工作区里的主 MEMORY.md 和按天 `memory/YYYY-MM-DD.md` ledger。</div></div>
        <button class="btn btn-ghost btn-sm" @click="loadDaily">刷新运行记忆</button>
      </div>
      <div class="filters">
        <div class="field">
          <label>代理</label>
          <select v-model="memoryAgentId" @change="loadDaily">
            <option v-for="agent in agentOptions" :key="String(agent.agent_id || agent.id)" :value="String(agent.agent_id || agent.id)">
              {{ agent.display_name || agent.name || agent.agent_id || agent.id }}
            </option>
          </select>
        </div>
      </div>
      <div class="daily-card">
        <div class="section-title">主记忆 MEMORY.md</div>
        <div class="section-sub">AgentScope runtime 文件，不是平台主库：{{ memoryOverview.memory_path || 'agent 工作区主记忆文件' }}</div>
        <pre class="json-box">{{ memoryMd || '暂无 MEMORY.md。平台记忆会在 agent run 前投影到这里；AgentScope memory_save 写入后会在 run 后导回待确认记忆。' }}</pre>
        <div v-if="runtimeMemoryEntries.length" class="runtime-entry-list">
          <div class="section-sub">可删除的 Agent 本地记忆（平台托管区不在此处直接删除）</div>
          <div v-for="entry in runtimeMemoryEntries" :key="entry" class="runtime-entry">
            <span>{{ entry }}</span>
            <button class="btn small danger" @click="removeRuntimeMemoryEntry(entry)">删除</button>
          </div>
        </div>
      </div>
      <div class="section-title">按天记忆 Ledger</div>
      <div class="section-sub">AgentScope 自动 flush / memory_save 追加流水</div>
      <div v-if="!dailyRows.length" class="empty">暂无按天记忆文件</div>
      <div v-else class="daily-list">
        <details v-for="item in dailyRows" :key="String(item.agent_id) + ':' + String(item.date)" class="daily-card">
          <summary><strong>{{ item.date }}</strong><span>{{ item.agent_id }}</span><em>{{ item.line_count || 0 }} 行</em></summary>
          <pre class="json-box">{{ item.content || '空文件' }}</pre>
        </details>
      </div>
    </section>

    <!-- 跨会话索引（只展示）-->
    <section class="panel">
      <div class="section-head"><div><div class="section-title">平台跨会话索引 · Episodic</div><div class="section-sub">只展示，暂未接入自动摘要召回。</div></div><button class="btn btn-ghost btn-sm" @click="loadEpisodic">刷新状态</button></div>
      <div class="mem-mini-row">
        <div class="mini"><span>检索</span><b>{{ episodic.enabled === false ? '关闭' : '开启' }}</b></div>
        <div class="mini"><span>索引写入</span><b>{{ episodic.index_enabled === false ? '关闭' : '开启' }}</b></div>
        <div class="mini"><span>TTL</span><b>{{ episodic.ttl_days ? episodic.ttl_days + ' 天' : '—' }}</b></div>
        <div class="mini"><span>条数</span><b>{{ episodic.active_count ?? 0 }} / {{ episodic.total_count ?? 0 }}</b></div>
        <div class="mini wide"><span>最近更新</span><b>{{ episodic.latest_updated_at ? fmtDate(episodic.latest_updated_at) : '—' }}</b></div>
      </div>
      <div class="empty">当前仅做状态展示。摘要生成、索引重建、过期清理后续放到单独的运维/任务页面，不在 Memory 管理页直接操作。</div>
    </section>

    <!-- 新增 / 编辑 弹窗 -->
    <div v-if="editorOpen" class="mem-modal" @click.self="editorOpen = false">
      <div class="mem-card">
        <div class="mem-card-head"><div class="mem-card-title">{{ editor.id ? '编辑记忆 #' + editor.id : '新增记忆' }}</div><button class="btn btn-ghost btn-sm" @click="editorOpen = false">关闭</button></div>
        <div class="mem-card-body">
          <div class="field wide"><label>内容</label><textarea v-model="editor.content" rows="4" placeholder="例如：用户偏好回答先给结论，再给依据。" /></div>
          <div class="form-row">
            <div class="field"><label>范围</label><select v-model="editor.scope" :disabled="!!editor.id"><option value="user">个人(user)</option><option value="org">组织(org)</option><option value="global">全局(global)</option></select></div>
            <div class="field"><label>类型</label><select v-model="editor.memory_type" :disabled="!!editor.id"><option value="preference">偏好</option><option value="fact">事实</option><option value="constraint">规则约束</option><option value="experience">经验</option><option value="result">结果</option></select></div>
            <div class="field"><label>状态</label><select v-model="editor.status"><option value="active">已生效</option><option value="pending_confirm">待确认</option><option value="inactive">未生效</option><option value="expired">已过期</option><option value="rejected">已拒绝</option><option value="merged">已合并</option><option value="deleted">已删除</option><option value="disabled">停用</option></select></div>
            <div class="field"><label>置信度</label><input v-model.number="editor.confidence" type="number" min="0" max="1" step="0.01" /></div>
          </div>
        </div>
        <div class="mem-card-actions"><button class="btn btn-ghost" @click="editorOpen = false">取消</button><button class="btn btn-primary" @click="save">保存</button></div>
      </div>
    </div>

    <!-- 记忆详情 弹窗 -->
    <div v-if="selected" class="mem-modal" @click.self="closeDetail">
      <div class="mem-card lg">
        <div class="mem-card-head"><div class="mem-card-title">记忆详情 #{{ selected.id }}</div><button class="btn btn-ghost btn-sm" @click="closeDetail">关闭</button></div>
        <div class="mem-card-body">
          <div class="detail-grid">
            <div class="detail-key">状态</div><div class="detail-val"><span class="badge" :class="selected.status || 'active'">{{ memoryStatusLabel(selected.status) }}</span></div>
            <div class="detail-key">范围</div><div class="detail-val">{{ selected.scope || 'user' }} {{ selected.scope_id || '' }}</div>
            <div class="detail-key">类型</div><div class="detail-val">{{ memoryTypeLabel(selected.memory_type) }}</div>
            <div class="detail-key">置信度</div><div class="detail-val">{{ confidenceText(selected.confidence) }}</div>
            <div class="detail-key">业务域</div><div class="detail-val">{{ selected.domain || '' }}</div>
            <div class="detail-key">来源</div><div class="detail-val">{{ selected.source_links?.session_id || selected.source_session_id || '' }} {{ runIdOf(selected) }}</div>
          </div>
          <div class="field wide"><label>内容</label><textarea v-model="detailContent" rows="4" /></div>
          <div class="detail-actions">
            <button v-if="selected.status === 'pending_confirm'" class="btn btn-primary btn-sm" @click="confirmDetail">确认并生效</button>
            <button v-if="selected.status === 'pending_confirm'" class="btn btn-danger btn-sm" @click="rejectDetail">拒绝</button>
            <button class="btn btn-ghost btn-sm" @click="saveDetail">保存内容</button>
            <button class="btn btn-ghost btn-sm" @click="openEditor(selected)">完整编辑</button>
            <button v-if="runIdOf(selected)" class="btn btn-ghost btn-sm" @click="loadSourceRun">查看 AgentRun</button>
          </div>
          <details class="mem-detail-extra"><summary>来源 / 变更记录</summary>
            <div class="field wide"><label>来源链路</label><pre class="json-box">{{ pretty(selected.source_links || {}) }}</pre></div>
            <div v-if="sourceRun" class="field wide"><label>AgentRun 与步骤</label><pre class="json-box">{{ pretty(sourceRun) }}</pre></div>
            <div class="field wide"><label>来源引用</label><pre class="json-box">{{ pretty(selected.source_ref) }}</pre></div>
            <div class="field wide"><label>最近变更</label><pre class="json-box">{{ auditRows.length ? auditRows.map(item => `${fmtDate(item.created_at)} ${item.event_type} 由 ${item.actor_user_id || 'system'}\n${item.metadata ? pretty(item.metadata) : ''}`).join('\n\n') : '暂无变更记录' }}</pre></div>
          </details>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.runtime-entry-list { display: grid; gap: 8px; margin-top: 10px; }
.runtime-entry { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 10px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; font-size: 13px; }
.runtime-entry span { overflow-wrap: anywhere; }
.memory-page { flex: 1; overflow-y: auto; padding: 24px; display: flex; flex-direction: column; gap: 18px; }
.memory-page > * { flex-shrink: 0; }
.mem-head { display: flex; align-items: center; gap: 12px; }
.mem-title { font-size: 18px; font-weight: 700; }
.mem-context { font-size: 12px; color: var(--muted); margin-top: 3px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.mem-head-right { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.mem-status { font-size: 12px; color: var(--muted); }
.stat-val.warn { color: var(--yellow); }

.mem-mini-row { display: grid; grid-template-columns: repeat(4, 1fr) 1.4fr; gap: 10px; margin-top: 4px; }
.mem-mini-row.dry { margin-top: 14px; padding-top: 14px; border-top: 1px dashed var(--border); }
.mini { background: #f8fafc; border: 1px solid #eef2f7; border-radius: 10px; padding: 9px 12px; display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.mini.wide { grid-column: span 1; }
.mini > span { font-size: 11px; color: var(--muted); }
.mini > b { font-size: 14px; font-weight: 700; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.mem-ops { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 14px; }
.op-group { display: flex; align-items: center; gap: 6px; border: 1px solid #eef2f7; border-radius: 10px; padding: 7px 10px; background: #fbfcfe; }
.op-label { font-size: 11px; font-weight: 700; color: var(--muted); margin-right: 2px; }

.mem-modal { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 28px; }
.mem-card { background: #fff; border-radius: 16px; width: 560px; max-width: 96vw; max-height: 90vh; display: flex; flex-direction: column; box-shadow: var(--shadow-lg); overflow: hidden; }
.mem-card.lg { width: 720px; }
.mem-card-head { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--border); flex-shrink: 0; }
.mem-card-title { font-size: 16px; font-weight: 700; flex: 1; }
.mem-card-body { padding: 18px 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; }
.mem-card-body .form-row { display: flex; gap: 12px; flex-wrap: wrap; }
.mem-card-body .form-row .field { flex: 1; min-width: 120px; }
.mem-card-actions { display: flex; gap: 8px; justify-content: flex-end; border-top: 1px solid var(--border); padding: 14px 20px; flex-shrink: 0; }
.mem-card .detail-grid { display: grid; grid-template-columns: 80px 1fr 80px 1fr; gap: 9px 12px; font-size: 13px; }
.mem-card .detail-key { color: var(--muted); }
.mem-card .detail-val { font-weight: 600; word-break: break-all; }
.detail-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.mem-detail-extra summary { cursor: pointer; font-size: 12px; color: var(--muted); font-weight: 600; }
.mem-detail-extra .field { margin-top: 10px; }

@media (max-width: 980px) {
  .memory-page { padding: 16px; }
  .mem-mini-row { grid-template-columns: repeat(2, 1fr); }
  .mem-card .detail-grid { grid-template-columns: 80px 1fr; }
}
</style>
