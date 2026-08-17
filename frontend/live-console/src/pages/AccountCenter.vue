<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { readJson } from '../lib/platformApi'

type JsonMap = Record<string, any>
const mode = ref<'login' | 'apply' | 'setup' | 'admin'>('login')
const loading = ref(false)
const message = ref('')
const error = ref('')
const user = ref<JsonMap | null>(null)
const applications = ref<JsonMap[]>([])
const loginForm = ref({ email: '', password: '' })
const applyForm = ref({ email: '', display_name: '', project: '', reason: '' })
const setupForm = ref({ token: '', password: '', display_name: '' })

const isAdmin = computed(() => user.value?.role === 'PLATFORM_ADMIN')
const title = computed(() => mode.value === 'admin' ? '账号申请审核' : mode.value === 'apply' ? '申请平台账号' : mode.value === 'setup' ? '完成账号设置' : '登录 Agent Platform')

async function request(path: string, options: RequestInit = {}) {
  const response = await fetch(path, { ...options, headers: { 'content-type': 'application/json', ...(options.headers || {}) } })
  return await readJson<JsonMap>(response)
}

async function loadMe() {
  try {
    const response = await fetch('/platform/auth/me', { cache: 'no-store' })
    if (response.ok) user.value = await readJson<JsonMap>(response)
  } catch { /* login page can still render */ }
}

async function login() {
  loading.value = true; error.value = ''; message.value = ''
  try {
    user.value = await request('/platform/auth/login', { method: 'POST', body: JSON.stringify(loginForm.value) })
    message.value = '登录成功，正在进入平台…'
    window.setTimeout(() => { location.href = '/platform/live' }, 350)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function apply() {
  loading.value = true; error.value = ''; message.value = ''
  try {
    await request('/platform/auth/apply', { method: 'POST', body: JSON.stringify(applyForm.value) })
    message.value = '申请已提交，请等待管理员审核。'
    applyForm.value = { email: '', display_name: '', project: '', reason: '' }
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function setupPassword() {
  loading.value = true; error.value = ''; message.value = ''
  try {
    await request('/platform/auth/setup-password', { method: 'POST', body: JSON.stringify(setupForm.value) })
    message.value = '密码设置完成，正在进入平台…'
    window.setTimeout(() => { location.href = '/platform/live' }, 350)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function loadApplications() {
  loading.value = true; error.value = ''
  try {
    const data = await request('/platform/admin/accounts/applications')
    applications.value = Array.isArray(data.items) ? data.items : []
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function review(item: JsonMap, action: 'approve' | 'reject') {
  const reason = window.prompt(action === 'approve' ? '可选：备注或分配组织名称' : '请输入拒绝原因', action === 'approve' ? '' : '')
  if (action === 'reject' && reason === null) return
  loading.value = true; error.value = ''; message.value = ''
  try {
    const data = await request(`/platform/admin/accounts/applications/${encodeURIComponent(String(item.application_id))}/${action}`, {
      method: 'POST', body: JSON.stringify(action === 'approve' ? { organization: reason || '', role: 'BUILDER' } : { review_reason: reason || '' }),
    })
    message.value = action === 'approve' ? (data.setup_url ? `已通过。邮件未配置，初始化链接：${data.setup_url}` : '已通过，初始化邮件已进入发送队列。') : '已拒绝该申请。'
    await loadApplications()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

onMounted(async () => {
  const params = new URLSearchParams(location.search)
  const token = params.get('token')
  if (token) { mode.value = 'setup'; setupForm.value.token = token }
  await loadMe()
  if (isAdmin.value) { mode.value = 'admin'; await loadApplications() }
})
</script>

<template>
  <main class="account-page">
    <section class="account-card">
      <div class="account-brand"><div class="logo-icon">AI</div><div><strong>AI Agent Platform</strong><small>账号与访问管理</small></div></div>
      <div class="account-head"><span class="eyebrow">ACCOUNT CENTER</span><h1>{{ title }}</h1></div>
      <div v-if="message" class="account-message success">{{ message }}</div>
      <div v-if="error" class="account-message error">{{ error }}</div>

      <form v-if="mode === 'login'" class="account-form" @submit.prevent="login">
        <label>邮箱<input v-model="loginForm.email" type="email" autocomplete="email" required /></label>
        <label>密码<input v-model="loginForm.password" type="password" autocomplete="current-password" required /></label>
        <button class="account-primary" :disabled="loading">{{ loading ? '登录中…' : '登录' }}</button>
        <button type="button" class="account-link" @click="mode = 'apply'">没有账号？申请访问</button>
      </form>

      <form v-else-if="mode === 'apply'" class="account-form" @submit.prevent="apply">
        <label>邮箱<input v-model="applyForm.email" type="email" autocomplete="email" required /></label>
        <label>姓名/昵称<input v-model="applyForm.display_name" required /></label>
        <label>项目或组织名称<input v-model="applyForm.project" placeholder="可选" /></label>
        <label>申请用途<textarea v-model="applyForm.reason" rows="4" placeholder="请简单说明想测试什么"></textarea></label>
        <button class="account-primary" :disabled="loading">{{ loading ? '提交中…' : '提交申请' }}</button>
        <button type="button" class="account-link" @click="mode = 'login'">返回登录</button>
      </form>

      <form v-else-if="mode === 'setup'" class="account-form" @submit.prevent="setupPassword">
        <label>设置密码<input v-model="setupForm.password" type="password" minlength="10" autocomplete="new-password" required /><small>至少 10 位</small></label>
        <label>显示名称<input v-model="setupForm.display_name" placeholder="可选" /></label>
        <button class="account-primary" :disabled="loading">{{ loading ? '保存中…' : '完成设置' }}</button>
      </form>

      <section v-else class="account-admin">
        <div class="admin-toolbar"><span>共 {{ applications.length }} 条申请</span><button class="account-secondary" @click="loadApplications">刷新</button></div>
        <div v-if="!applications.length" class="account-empty">暂无申请</div>
        <article v-for="item in applications" :key="item.application_id" class="application-item">
          <div><strong>{{ item.display_name }}</strong><span>{{ item.email }}</span><small>{{ item.project || '未填写项目' }} · {{ item.created_at }}</small><p>{{ item.reason || '未填写申请理由' }}</p></div>
          <div class="application-actions"><span :class="['application-status', String(item.status).toLowerCase()]">{{ item.status }}</span><template v-if="item.status === 'PENDING'"><button class="account-secondary" :disabled="loading" @click="review(item, 'reject')">拒绝</button><button class="account-primary compact" :disabled="loading" @click="review(item, 'approve')">通过</button></template></div>
        </article>
      </section>
    </section>
  </main>
</template>

<style scoped>
.account-page{min-height:100vh;background:#f8fafc;display:grid;place-items:center;padding:24px;color:#0f172a}.account-card{width:min(620px,100%);background:#fff;border:1px solid #e2e8f0;border-radius:18px;box-shadow:0 18px 60px rgba(15,23,42,.10);padding:32px}.account-brand{display:flex;align-items:center;gap:10px;color:#0f172a;margin-bottom:40px}.account-brand strong,.account-brand small{display:block}.account-brand small{font-size:11px;color:#94a3b8;margin-top:3px}.logo-icon{width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:#2563eb;color:#fff;font-weight:800}.eyebrow{font-size:10px;letter-spacing:.15em;color:#2563eb;font-weight:800}.account-head h1{font-size:26px;margin:8px 0 22px}.account-form{display:flex;flex-direction:column;gap:14px}.account-form label{display:flex;flex-direction:column;gap:6px;color:#475569;font-size:12px}.account-form input,.account-form textarea{font:inherit;font-size:14px;border:1px solid #cbd5e1;border-radius:8px;padding:10px;color:#0f172a}.account-form input:focus,.account-form textarea:focus{outline:2px solid #bfdbfe;border-color:#2563eb}.account-form small{color:#94a3b8}.account-primary,.account-secondary,.account-link{border-radius:8px;padding:10px 14px;font:inherit;font-size:12px;cursor:pointer}.account-primary{border:1px solid #2563eb;background:#2563eb;color:#fff}.account-primary:hover{background:#1d4ed8}.account-primary:disabled,.account-secondary:disabled{opacity:.55;cursor:wait}.account-primary.compact{padding:7px 10px}.account-secondary{border:1px solid #cbd5e1;background:#fff;color:#334155}.account-link{border:0;background:transparent;color:#2563eb}.account-message{border-radius:8px;padding:10px 12px;font-size:12px;margin-bottom:14px;white-space:pre-wrap;word-break:break-all}.account-message.success{background:#ecfdf5;color:#047857}.account-message.error{background:#fef2f2;color:#b91c1c}.admin-toolbar{display:flex;justify-content:space-between;align-items:center;font-size:12px;color:#64748b;margin-bottom:14px}.application-item{border:1px solid #e2e8f0;border-radius:10px;padding:14px;display:flex;justify-content:space-between;gap:16px;margin-bottom:9px}.application-item strong,.application-item span,.application-item small{display:block}.application-item strong{font-size:14px}.application-item span{font-size:12px;color:#475569;margin-top:4px}.application-item small{font-size:11px;color:#94a3b8;margin-top:5px}.application-item p{font-size:12px;color:#64748b;white-space:pre-wrap;margin:10px 0 0}.application-actions{display:flex;flex-direction:column;align-items:flex-end;gap:7px;min-width:70px}.application-status{font-size:10px!important;border-radius:99px;padding:4px 7px;background:#f1f5f9;color:#475569!important}.application-status.pending{background:#fef3c7;color:#a16207!important}.application-status.approved{background:#dcfce7;color:#15803d!important}.application-status.rejected{background:#fee2e2;color:#b91c1c!important}.account-empty{text-align:center;padding:40px;color:#94a3b8;font-size:12px}@media(max-width:600px){.account-card{padding:22px}.application-item{display:block}.application-actions{margin-top:12px;align-items:flex-start;flex-direction:row;flex-wrap:wrap}}
</style>
