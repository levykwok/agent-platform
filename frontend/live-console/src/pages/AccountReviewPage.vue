<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fmtDate, readJson } from '../lib/platformApi'

type JsonMap = Record<string, any>
type Section = 'applications' | 'users' | 'organizations' | 'email'

const section = ref<Section>('applications')
const loading = ref(false)
const error = ref('')
const message = ref('')
const query = ref('')
const status = ref('')
const offset = ref(0)
const limit = 50
const total = ref(0)
const applications = ref<JsonMap[]>([])
const users = ref<JsonMap[]>([])
const emails = ref<JsonMap[]>([])
const organizations = ref<JsonMap[]>([])
const sessions = ref<JsonMap[]>([])
const modal = ref<'review' | 'user' | 'organization' | 'sessions' | null>(null)
const selected = ref<JsonMap | null>(null)
const reviewForm = ref({ action: 'approve', reason: '', role: 'BUILDER', org_id: '', organization: '' })
const userForm = ref({ display_name: '', status: 'ACTIVE', role: 'BUILDER', org_id: '', organization: '' })
const organizationForm = ref({ name: '', status: 'ACTIVE', creating: false })

const rows = computed(() => section.value === 'applications' ? applications.value : section.value === 'users' ? users.value : section.value === 'organizations' ? organizations.value : emails.value)
const page = computed(() => Math.floor(offset.value / limit) + 1)
const pages = computed(() => Math.max(1, Math.ceil(total.value / limit)))
const canPrevious = computed(() => offset.value > 0)
const canNext = computed(() => offset.value + rows.value.length < total.value)

async function request(path: string, options: RequestInit = {}) {
  const response = await fetch(path, { ...options, headers: { 'content-type': 'application/json', ...(options.headers || {}) } })
  return await readJson<JsonMap>(response)
}

function endpoint() {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset.value) })
  if (query.value.trim() && section.value !== 'email') params.set('query', query.value.trim())
  if (status.value) params.set('status', status.value)
  if (section.value === 'applications') return `/platform/admin/accounts/applications?${params}`
  if (section.value === 'users') return `/platform/admin/accounts/users?${params}`
  if (section.value === 'organizations') return '/platform/admin/accounts/organizations'
  return `/platform/admin/accounts/email-outbox?${params}`
}

async function loadOrganizations() {
  const data = await request('/platform/admin/accounts/organizations')
  organizations.value = Array.isArray(data.items) ? data.items : []
}

async function load(showMessage = false) {
  loading.value = true
  error.value = ''
  try {
    const data = await request(endpoint())
    const items = Array.isArray(data.items) ? data.items : []
    total.value = Number(data.total ?? items.length)
    if (section.value === 'applications') applications.value = items
    else if (section.value === 'users') users.value = items
    else if (section.value === 'organizations') organizations.value = items
    else emails.value = items
    if (showMessage) message.value = '列表已刷新'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
  } finally { loading.value = false }
}

function switchSection(next: Section) {
  section.value = next
  query.value = ''
  status.value = ''
  offset.value = 0
}

function openReview(item: JsonMap, action: 'approve' | 'reject') {
  selected.value = item
  reviewForm.value = { action, reason: '', role: 'BUILDER', org_id: '', organization: String(item.project || '') }
  modal.value = 'review'
}

function openUser(item: JsonMap) {
  selected.value = item
  userForm.value = {
    display_name: String(item.display_name || ''),
    status: String(item.status || 'ACTIVE'),
    role: String(item.role || 'BUILDER'),
    org_id: String(item.org_id || ''),
    organization: '',
  }
  modal.value = 'user'
}

function openOrganization(item?: JsonMap) {
  selected.value = item || {}
  organizationForm.value = {
    name: String(item?.name || ''),
    status: String(item?.status || 'ACTIVE'),
    creating: !item,
  }
  modal.value = 'organization'
}

async function openSessions(item: JsonMap) {
  selected.value = item
  loading.value = true; error.value = ''
  try {
    const data = await request(`/platform/admin/accounts/users/${encodeURIComponent(String(item.user_id))}/sessions`)
    sessions.value = Array.isArray(data.items) ? data.items : []
    modal.value = 'sessions'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function revokeSession(item: JsonMap) {
  if (!selected.value || !window.confirm('确定撤销这个登录会话？')) return
  loading.value = true; error.value = ''
  try {
    await request(`/platform/admin/accounts/users/${encodeURIComponent(String(selected.value.user_id))}/sessions/${encodeURIComponent(String(item.session_id))}`, { method: 'DELETE' })
    item.status = 'REVOKED'; item.revoked_at = new Date().toISOString()
    message.value = '指定会话已撤销'
    await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

function closeModal() {
  modal.value = null
  selected.value = null
}

function showResult(data: JsonMap, fallback: string) {
  message.value = data.setup_url ? `${fallback}。一次性设置链接：${data.setup_url}` : fallback
}

async function confirmReview() {
  if (!selected.value) return
  const form = reviewForm.value
  if (form.action === 'reject' && !form.reason.trim()) { error.value = '请填写拒绝原因'; return }
  loading.value = true; error.value = ''; message.value = ''
  try {
    const payload = form.action === 'approve'
      ? { role: form.role, organization_id: form.org_id, organization: form.organization, review_reason: form.reason }
      : { review_reason: form.reason }
    const data = await request(`/platform/admin/accounts/applications/${encodeURIComponent(String(selected.value.application_id))}/${form.action}`, { method: 'POST', body: JSON.stringify(payload) })
    showResult(data, form.action === 'approve' ? '申请已通过' : '申请已拒绝')
    closeModal(); await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function saveUser() {
  if (!selected.value) return
  loading.value = true; error.value = ''; message.value = ''
  const id = encodeURIComponent(String(selected.value.user_id))
  try {
    await request(`/platform/admin/accounts/users/${id}`, { method: 'PATCH', body: JSON.stringify({ display_name: userForm.value.display_name, status: userForm.value.status }) })
    const membershipChanged = userForm.value.role !== String(selected.value.role || '') || userForm.value.org_id !== String(selected.value.org_id || '') || Boolean(userForm.value.organization.trim())
    if (membershipChanged) await request(`/platform/admin/accounts/users/${id}/membership`, { method: 'PUT', body: JSON.stringify({ role: userForm.value.role, org_id: userForm.value.org_id, organization: userForm.value.organization }) })
    message.value = '账号、组织和角色已更新'
    closeModal(); await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function userAction(item: JsonMap, action: 'sessions' | 'password') {
  const text = action === 'sessions' ? '确定撤销该用户的全部登录会话？' : '确定重置该用户密码并注销其全部会话？'
  if (!window.confirm(text)) return
  loading.value = true; error.value = ''; message.value = ''
  try {
    const path = action === 'sessions' ? 'revoke-sessions' : 'reset-password'
    const data = await request(`/platform/admin/accounts/users/${encodeURIComponent(String(item.user_id))}/${path}`, { method: 'POST' })
    showResult(data, action === 'sessions' ? `已撤销 ${data.revoked_sessions || 0} 个会话` : '密码重置链接已生成')
    await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function saveOrganization() {
  if (!selected.value || !organizationForm.value.name.trim()) { error.value = '请输入组织名称'; return }
  loading.value = true; error.value = ''; message.value = ''
  try {
    const creating = organizationForm.value.creating
    const path = creating ? '/platform/admin/accounts/organizations' : `/platform/admin/accounts/organizations/${encodeURIComponent(String(selected.value.org_id))}`
    await request(path, { method: creating ? 'POST' : 'PATCH', body: JSON.stringify({ name: organizationForm.value.name.trim(), status: organizationForm.value.status }) })
    message.value = creating ? '组织已创建' : '组织已更新'
    closeModal(); await load(); await loadOrganizations()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function retryEmail(item: JsonMap) {
  loading.value = true; error.value = ''; message.value = ''
  try {
    const data = await request(`/platform/admin/accounts/email-outbox/${encodeURIComponent(String(item.email_id))}/retry`, { method: 'POST' })
    message.value = data.status === 'MAIL_DISABLED' ? '邮件发送未启用；请使用密码重置返回的一次性链接' : '邮件已重新进入发送队列'
    await load()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

function previous() { if (canPrevious.value) { offset.value = Math.max(0, offset.value - limit); load() } }
function next() { if (canNext.value) { offset.value += limit; load() } }

let queryTimer: number | undefined
watch([section, status], () => { offset.value = 0; load() })
watch(query, () => {
  window.clearTimeout(queryTimer)
  queryTimer = window.setTimeout(() => { offset.value = 0; load() }, 300)
})

onMounted(async () => {
  try { await loadOrganizations() } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
  await load()
})
</script>

<template>
  <section class="account-admin-page">
    <header class="page-head">
      <div><div class="eyebrow">ACCESS CONTROL</div><h1>账号管理</h1><p>审核访问申请，管理用户、组织、角色、Session 与初始化邮件。</p></div>
      <div class="head-actions"><a class="button secondary" href="/platform/live/access">账号入口</a><button class="button primary" :disabled="loading" @click="load(true)">{{ loading ? '刷新中…' : '刷新' }}</button></div>
    </header>

    <div class="section-tabs">
      <button :class="{active: section === 'applications'}" @click="switchSection('applications')">申请审核</button>
      <button :class="{active: section === 'users'}" @click="switchSection('users')">用户与权限</button>
      <button :class="{active: section === 'organizations'}" @click="switchSection('organizations')">组织管理</button>
      <button :class="{active: section === 'email'}" @click="switchSection('email')">邮件队列</button>
    </div>

    <div v-if="error" class="alert error">{{ error }}</div>
    <div v-if="message" class="alert success">{{ message }}</div>

    <section class="panel">
      <div class="toolbar">
        <label v-if="section === 'applications' || section === 'users'" class="search"><span>⌕</span><input v-model="query" :placeholder="section === 'users' ? '搜索姓名或邮箱' : '搜索申请人、邮箱或项目'" /></label>
        <select v-if="section !== 'organizations'" v-model="status">
          <option value="">全部状态</option>
          <template v-if="section === 'applications'"><option value="PENDING">待审核</option><option value="APPROVED">已通过</option><option value="REJECTED">已拒绝</option></template>
          <template v-else-if="section === 'users'"><option value="ACTIVE">启用</option><option value="SUSPENDED">已停用</option></template>
          <template v-else><option value="PENDING">待发送</option><option value="FAILED">发送失败</option><option value="DEAD">停止重试</option><option value="SENT">已发送</option></template>
        </select>
        <button v-if="section === 'organizations'" class="primary" @click="openOrganization()">新建组织</button>
        <span class="total">共 {{ total }} 条</span>
      </div>

      <div v-if="loading && !rows.length" class="state">正在加载…</div>
      <div v-else-if="!rows.length" class="state"><strong>暂无匹配记录</strong><span>调整搜索或筛选条件后重试</span></div>

      <div v-else class="table-wrap">
        <table v-if="section === 'applications'">
          <thead><tr><th>申请人</th><th>项目与用途</th><th>提交时间</th><th>状态</th><th>操作</th></tr></thead>
          <tbody><tr v-for="item in applications" :key="item.application_id"><td><div class="identity"><i>{{ String(item.display_name || '?').slice(0,1).toUpperCase() }}</i><div><strong>{{ item.display_name }}</strong><span>{{ item.email }}</span></div></div></td><td><strong>{{ item.project || '未填写项目' }}</strong><small>{{ item.reason || '未填写用途' }}</small></td><td>{{ fmtDate(item.created_at) }}</td><td><span class="badge" :class="String(item.status).toLowerCase()">{{ item.status === 'PENDING' ? '待审核' : item.status === 'APPROVED' ? '已通过' : '已拒绝' }}</span></td><td><div v-if="item.status === 'PENDING'" class="row-actions"><button class="approve" @click="openReview(item,'approve')">通过</button><button class="danger" @click="openReview(item,'reject')">拒绝</button></div><span v-else class="muted">已处理</span></td></tr></tbody>
        </table>

        <table v-else-if="section === 'users'">
          <thead><tr><th>用户</th><th>组织与角色</th><th>状态</th><th>资产/运行</th><th>Session</th><th>操作</th></tr></thead>
          <tbody><tr v-for="item in users" :key="item.user_id"><td><div class="identity"><i>{{ String(item.display_name || '?').slice(0,1).toUpperCase() }}</i><div><strong>{{ item.display_name }}</strong><span>{{ item.email }}</span><small>{{ item.user_id }}</small></div></div></td><td><strong>{{ item.organization_name || '未分配组织' }}</strong><small>{{ item.role || '未分配角色' }}</small></td><td><span class="badge" :class="String(item.status).toLowerCase()">{{ item.status }}</span><small v-if="item.locked_until">锁定至 {{ fmtDate(item.locked_until) }}</small></td><td><strong>{{ item.asset_usage?.total || 0 }} 个资产</strong><small>{{ item.asset_usage?.runs || 0 }} 次运行</small></td><td><button @click="openSessions(item)">{{ item.active_sessions || 0 }} 个</button></td><td><div class="row-actions"><button @click="openUser(item)">编辑</button><button @click="userAction(item,'sessions')">全部下线</button><button @click="userAction(item,'password')">重置密码</button></div></td></tr></tbody>
        </table>

        <table v-else-if="section === 'organizations'">
          <thead><tr><th>组织</th><th>状态</th><th>成员</th><th>创建时间</th><th>操作</th></tr></thead>
          <tbody><tr v-for="item in organizations" :key="item.org_id"><td><strong>{{ item.name }}</strong><small>{{ item.org_id }}</small></td><td><span class="badge" :class="String(item.status).toLowerCase()">{{ item.status }}</span></td><td>{{ item.member_count || 0 }} 人</td><td>{{ fmtDate(item.created_at) }}</td><td><button @click="openOrganization(item)">编辑</button></td></tr></tbody>
        </table>

        <table v-else>
          <thead><tr><th>收件人</th><th>类型</th><th>状态</th><th>尝试</th><th>错误/时间</th><th>操作</th></tr></thead>
          <tbody><tr v-for="item in emails" :key="item.email_id"><td><strong>{{ item.recipient }}</strong><small>{{ item.email_id }}</small></td><td>{{ item.kind }}</td><td><span class="badge" :class="String(item.status).toLowerCase()">{{ item.status }}</span></td><td>{{ item.attempts }}</td><td><small>{{ item.last_error || fmtDate(item.sent_at || item.created_at) }}</small></td><td><button v-if="item.status !== 'SENT'" @click="retryEmail(item)">重新发送</button><span v-else class="muted">已完成</span></td></tr></tbody>
        </table>
      </div>

      <footer class="pager"><button :disabled="!canPrevious" @click="previous">上一页</button><span>第 {{ page }} / {{ pages }} 页</span><button :disabled="!canNext" @click="next">下一页</button></footer>
    </section>

    <div v-if="modal && selected" class="backdrop" @click.self="closeModal">
      <section class="modal">
        <button class="close" aria-label="关闭" @click="closeModal">×</button>
        <template v-if="modal === 'review'">
          <div class="eyebrow">APPLICATION REVIEW</div><h2>{{ reviewForm.action === 'approve' ? '通过账号申请' : '拒绝账号申请' }}</h2><p>{{ selected.display_name }} · {{ selected.email }}</p>
          <template v-if="reviewForm.action === 'approve'"><label>角色<select v-model="reviewForm.role"><option value="ORG_ADMIN">组织管理员</option><option value="BUILDER">构建者</option><option value="TESTER">测试者</option><option value="VIEWER">只读用户</option></select></label><label>加入已有组织（可选）<select v-model="reviewForm.org_id"><option value="">创建个人组织</option><option v-for="org in organizations" :key="org.org_id" :value="org.org_id">{{ org.name }}</option></select></label><label v-if="!reviewForm.org_id">新组织名称<input v-model="reviewForm.organization" /></label></template>
          <label>{{ reviewForm.action === 'approve' ? '审核备注（可选）' : '拒绝原因' }}<textarea v-model="reviewForm.reason" rows="4" /></label>
          <div class="modal-actions"><button @click="closeModal">取消</button><button class="primary" :class="{danger: reviewForm.action === 'reject'}" :disabled="loading" @click="confirmReview">确认提交</button></div>
        </template>
        <template v-else-if="modal === 'user'">
          <div class="eyebrow">USER MANAGEMENT</div><h2>编辑用户</h2><p>{{ selected.email }}</p>
          <label>显示名称<input v-model="userForm.display_name" /></label><label>账号状态<select v-model="userForm.status"><option value="ACTIVE">启用</option><option value="SUSPENDED">停用</option></select></label><label>角色<select v-model="userForm.role"><option value="PLATFORM_ADMIN">平台管理员</option><option value="ORG_ADMIN">组织管理员</option><option value="BUILDER">构建者</option><option value="TESTER">测试者</option><option value="VIEWER">只读用户</option></select></label><label v-if="userForm.role !== 'PLATFORM_ADMIN'">已有组织<select v-model="userForm.org_id"><option value="">创建新组织</option><option v-for="org in organizations.filter((row) => row.org_id !== 'platform')" :key="org.org_id" :value="org.org_id">{{ org.name }}</option></select></label><label v-if="userForm.role !== 'PLATFORM_ADMIN' && !userForm.org_id">新组织名称<input v-model="userForm.organization" placeholder="请输入组织名称" /></label>
          <div class="modal-actions"><button @click="closeModal">取消</button><button class="primary" :disabled="loading" @click="saveUser">保存修改</button></div>
        </template>
        <template v-else-if="modal === 'organization'">
          <div class="eyebrow">ORGANIZATION MANAGEMENT</div><h2>{{ organizationForm.creating ? '新建组织' : '编辑组织' }}</h2><p v-if="!organizationForm.creating">{{ selected.org_id }} · {{ selected.member_count || 0 }} 位成员</p>
          <label>组织名称<input v-model="organizationForm.name" maxlength="120" /></label><label v-if="!organizationForm.creating">组织状态<select v-model="organizationForm.status"><option value="ACTIVE">启用</option><option value="SUSPENDED">停用</option></select></label>
          <div class="modal-actions"><button @click="closeModal">取消</button><button class="primary" :disabled="loading" @click="saveOrganization">保存组织</button></div>
        </template>
        <template v-else>
          <div class="eyebrow">SESSION MANAGEMENT</div><h2>登录会话</h2><p>{{ selected.display_name }} · {{ selected.email }}</p>
          <div v-if="!sessions.length" class="session-empty">暂无登录会话</div>
          <div v-for="item in sessions" :key="item.session_id" class="session-row"><div><strong>{{ item.session_id }}</strong><small>最近活动 {{ fmtDate(item.last_seen_at) }} · 到期 {{ fmtDate(item.expires_at) }}</small></div><span class="badge" :class="String(item.status).toLowerCase()">{{ item.status }}</span><button v-if="item.status === 'ACTIVE'" class="danger" @click="revokeSession(item)">撤销</button></div>
          <div class="modal-actions"><button @click="closeModal">关闭</button><button class="danger" :disabled="loading" @click="userAction(selected,'sessions')">全部下线</button></div>
        </template>
      </section>
    </div>
  </section>
</template>

<style scoped>
.account-admin-page{padding:30px 34px 48px;max-width:1480px;margin:0 auto;color:#172033}.page-head{display:flex;justify-content:space-between;gap:20px;margin-bottom:22px}.eyebrow{font-size:10px;letter-spacing:.16em;font-weight:800;color:#5474d9}.page-head h1{font-size:27px;margin:6px 0}.page-head p,.modal p{font-size:12px;color:#78859a;margin:0}.head-actions,.row-actions,.modal-actions{display:flex;gap:7px;align-items:center}.button,button,select,input,textarea{font:inherit}.button,button{border:1px solid #dce3ef;background:#fff;color:#42516c;border-radius:7px;padding:8px 11px;font-size:11px;font-weight:700;cursor:pointer;text-decoration:none}.button.primary,.primary{background:#4766d8;color:#fff;border-color:#4766d8}.button:disabled,button:disabled{opacity:.5;cursor:not-allowed}.section-tabs{display:flex;gap:5px;border-bottom:1px solid #e3e8f0;margin-bottom:16px}.section-tabs button{border:0;border-radius:7px 7px 0 0;padding:10px 16px;background:transparent}.section-tabs button.active{background:#eef2ff;color:#405bc5}.alert{padding:10px 13px;border-radius:8px;margin-bottom:12px;font-size:12px}.alert.error{background:#fff0f1;color:#b4233c}.alert.success{background:#eefbf5;color:#168057;white-space:pre-wrap;word-break:break-all}.panel{background:#fff;border:1px solid #e3e8f0;border-radius:12px;overflow:hidden}.toolbar{display:flex;align-items:center;gap:10px;padding:13px 16px;border-bottom:1px solid #edf0f5}.search{display:flex;align-items:center;gap:6px;border:1px solid #dfe5ef;border-radius:7px;padding:0 9px;width:270px;height:32px}.search input{border:0;outline:0;width:100%}.toolbar select,.modal select,.modal input,.modal textarea{border:1px solid #dce3ee;border-radius:7px;padding:8px;background:#fff;color:#27344d}.toolbar .total{margin-left:auto;font-size:11px;color:#8995a8}.table-wrap{overflow:auto}table{width:100%;border-collapse:collapse;min-width:900px}th{text-align:left;padding:11px 15px;background:#fafbfc;color:#8e9aaf;font-size:10px}td{padding:13px 15px;border-top:1px solid #eff2f6;font-size:11px;vertical-align:middle}td strong,td small,.identity span,.identity small{display:block}td small,.identity span,.identity small{color:#8d98aa;margin-top:3px;max-width:280px}.identity{display:flex;align-items:center;gap:9px}.identity i{width:31px;height:31px;border-radius:9px;display:grid;place-items:center;background:#edf1ff;color:#526bc9;font-style:normal;font-weight:800}.badge{display:inline-flex;border-radius:99px;padding:4px 8px;background:#f4f5f7;color:#65728a;font-size:10px;font-weight:800}.badge.pending{background:#fff7e6;color:#a56a0c}.badge.approved,.badge.active,.badge.sent{background:#eaf9f2;color:#168057}.badge.rejected,.badge.suspended,.badge.failed,.badge.dead{background:#fff0f1;color:#b4233c}.row-actions{flex-wrap:wrap}.row-actions button{padding:6px 8px}.row-actions .approve{color:#168057;background:#f2fbf7;border-color:#c7ead7}.row-actions .danger,.danger{color:#b4233c;background:#fff5f6;border-color:#f0cbd1}.muted{color:#a3adbc}.state{padding:60px;text-align:center;color:#8793a6}.state strong,.state span{display:block;margin:5px}.pager{display:flex;justify-content:flex-end;align-items:center;gap:10px;padding:12px 15px;border-top:1px solid #edf0f5;font-size:11px;color:#7d899d}.backdrop{position:fixed;inset:0;background:rgba(20,31,52,.34);display:grid;place-items:center;padding:20px;z-index:30}.modal{position:relative;width:min(470px,100%);max-height:86vh;overflow:auto;background:#fff;border-radius:13px;padding:24px;box-shadow:0 24px 70px rgba(20,34,64,.24)}.modal h2{font-size:20px;margin:7px 0}.modal label{display:flex;flex-direction:column;gap:6px;margin-top:13px;font-size:11px;font-weight:700;color:#647188}.modal textarea{resize:vertical}.modal-actions{justify-content:flex-end;margin-top:20px}.close{position:absolute;right:12px;top:9px;border:0;font-size:20px;background:transparent}.modal-actions .danger{background:#d9485f;color:#fff;border-color:#d9485f}@media(max-width:760px){.account-admin-page{padding:22px 16px}.page-head{display:block}.head-actions{margin-top:15px}.toolbar{flex-wrap:wrap}.search{width:100%}.section-tabs{overflow:auto}}
.badge.revoked,.badge.expired{background:#fff0f1;color:#b4233c}.session-row{display:grid;grid-template-columns:1fr auto auto;gap:10px;align-items:center;padding:11px 0;border-bottom:1px solid #edf0f5}.session-row strong,.session-row small{display:block}.session-row strong{font-size:11px}.session-row small,.session-empty{font-size:10px;color:#8793a6;margin-top:3px}.session-empty{padding:30px;text-align:center}
</style>
