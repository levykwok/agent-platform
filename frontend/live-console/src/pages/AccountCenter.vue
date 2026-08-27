<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { clearAuthContext, readJson } from '../lib/platformApi'

type JsonMap = Record<string, any>
const mode = ref<'login' | 'apply' | 'setup'>('login')
const loading = ref(false)
const message = ref('')
const error = ref('')
const user = ref<JsonMap | null>(null)
const loginForm = ref({ email: '', password: '' })
const applyForm = ref({ email: '', display_name: '', project: '', reason: '' })
const setupForm = ref({ token: '', password: '', confirm_password: '', display_name: '' })

const isAdmin = computed(() => user.value?.role === 'PLATFORM_ADMIN')
const title = computed(() => mode.value === 'apply' ? '申请平台账号' : mode.value === 'setup' ? '完成账号设置' : '登录 Agent Platform')

function postLoginPath(): string {
  const redirect = new URLSearchParams(location.search).get('redirect')
  if (!redirect) return '/platform/live'
  try {
    const target = new URL(redirect, location.origin)
    if (target.origin === location.origin && target.pathname.startsWith('/')) {
      return `${target.pathname}${target.search}${target.hash}`
    }
  } catch { /* fall back to the platform home page */ }
  return '/platform/live'
}

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
    window.setTimeout(() => { location.replace(postLoginPath()) }, 350)
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
  if (setupForm.value.password.length < 10) { error.value = '密码至少需要 10 位'; return }
  if (setupForm.value.password !== setupForm.value.confirm_password) { error.value = '两次输入的密码不一致'; return }
  loading.value = true; error.value = ''; message.value = ''
  try {
    await request('/platform/auth/setup-password', { method: 'POST', body: JSON.stringify({ token: setupForm.value.token, password: setupForm.value.password, display_name: setupForm.value.display_name }) })
    message.value = '密码设置完成，正在进入平台…'
    window.setTimeout(() => { location.href = '/platform/live' }, 350)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { loading.value = false }
}

async function logout() {
  loading.value = true
  try { await request('/platform/auth/logout', { method: 'POST' }) } catch { /* the local session is cleared below */ }
  clearAuthContext()
  location.replace('/platform/live/access')
}

onMounted(async () => {
  const params = new URLSearchParams(location.search)
  const token = params.get('token')
  if (token) { mode.value = 'setup'; setupForm.value.token = token }
  await loadMe()
})
</script>

<template>
  <main class="account-page">
    <section class="account-card">
      <div class="account-brand"><div class="logo-icon">AI</div><div><strong>AI Agent Platform</strong><small>账号与访问管理</small></div></div>
      <div class="account-head"><span class="eyebrow">ACCOUNT CENTER</span><h1>{{ title }}</h1></div>
      <div v-if="message" class="account-message success">{{ message }}</div>
      <div v-if="error" class="account-message error">{{ error }}</div>

      <section v-if="mode === 'login' && user" class="account-session">
        <div class="session-avatar">{{ String(user.display_name || user.email || 'U').slice(0, 1).toUpperCase() }}</div>
        <div class="session-copy"><strong>{{ user.display_name || '已登录用户' }}</strong><span>{{ user.email }}</span><small>{{ isAdmin ? '平台管理员' : '平台成员' }}</small></div>
        <div class="session-actions"><a class="account-primary" href="/platform/live">进入平台</a><a v-if="isAdmin" class="account-secondary" href="/platform/live/accounts">账号审核</a><button class="account-link" :disabled="loading" @click="logout">退出登录</button></div>
      </section>

      <form v-else-if="mode === 'login'" class="account-form" @submit.prevent="login">
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
        <label>确认密码<input v-model="setupForm.confirm_password" type="password" minlength="10" autocomplete="new-password" required /></label>
        <label>显示名称<input v-model="setupForm.display_name" placeholder="可选" /></label>
        <button class="account-primary" :disabled="loading">{{ loading ? '保存中…' : '完成设置' }}</button>
      </form>

    </section>
  </main>
</template>

<style scoped>
.account-page{min-height:100vh;background:#f8fafc;display:grid;place-items:center;padding:24px;color:#0f172a}.account-card{width:min(620px,100%);background:#fff;border:1px solid #e2e8f0;border-radius:18px;box-shadow:0 18px 60px rgba(15,23,42,.10);padding:32px}.account-brand{display:flex;align-items:center;gap:10px;color:#0f172a;margin-bottom:40px}.account-brand strong,.account-brand small{display:block}.account-brand small{font-size:11px;color:#94a3b8;margin-top:3px}.logo-icon{width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:#2563eb;color:#fff;font-weight:800}.eyebrow{font-size:10px;letter-spacing:.15em;color:#2563eb;font-weight:800}.account-head h1{font-size:26px;margin:8px 0 22px}.account-form{display:flex;flex-direction:column;gap:14px}.account-form label{display:flex;flex-direction:column;gap:6px;color:#475569;font-size:12px}.account-form input,.account-form textarea{font:inherit;font-size:14px;border:1px solid #cbd5e1;border-radius:8px;padding:10px;color:#0f172a}.account-form input:focus,.account-form textarea:focus{outline:2px solid #bfdbfe;border-color:#2563eb}.account-form small{color:#94a3b8}.account-primary,.account-secondary,.account-link{border-radius:8px;padding:10px 14px;font:inherit;font-size:12px;cursor:pointer}.account-primary{border:1px solid #2563eb;background:#2563eb;color:#fff;text-decoration:none;text-align:center}.account-primary:hover{background:#1d4ed8}.account-primary:disabled,.account-secondary:disabled{opacity:.55;cursor:wait}.account-secondary{border:1px solid #cbd5e1;background:#fff;color:#334155;text-decoration:none;text-align:center}.account-link{border:0;background:transparent;color:#2563eb}.account-message{border-radius:8px;padding:10px 12px;font-size:12px;margin-bottom:14px;white-space:pre-wrap;word-break:break-all}.account-message.success{background:#ecfdf5;color:#047857}.account-message.error{background:#fef2f2;color:#b91c1c}.account-session{border:1px solid #e2e8f0;background:#fbfcff;border-radius:12px;padding:18px;display:grid;grid-template-columns:auto 1fr;gap:12px;align-items:center}.session-avatar{width:42px;height:42px;border-radius:12px;display:grid;place-items:center;background:#e8efff;color:#2563eb;font-weight:800;font-size:16px}.session-copy strong,.session-copy span,.session-copy small{display:block}.session-copy strong{font-size:15px}.session-copy span{font-size:12px;color:#64748b;margin-top:3px}.session-copy small{font-size:11px;color:#2563eb;margin-top:5px}.session-actions{grid-column:1/-1;display:flex;gap:8px;align-items:center;margin-top:6px}.session-actions .account-link{margin-left:auto}.account-link:disabled{opacity:.55;cursor:wait}@media(max-width:600px){.account-card{padding:22px}.session-actions{flex-wrap:wrap}.session-actions .account-link{margin-left:0;width:100%}}
</style>
