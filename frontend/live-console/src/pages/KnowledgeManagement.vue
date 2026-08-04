<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { contextHref, currentDomain, currentUser, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { confirmDialog } from '../stores/dialog'
import { notifyError, notifySuccess } from '../stores/notify'

const domainOptions = ref<JsonMap[]>([{ domain: 'platform', label: '平台', org_id: 'platform' }])
const selectedDomain = ref(currentDomain())
const docs = ref<JsonMap[]>([])
const collections = ref<JsonMap[]>([])
const selectedFolder = ref('all')
const selectedDocKey = ref('')
const search = ref('')
const newFolderTitle = ref('')
const uploadTarget = ref('all')
const uploading = ref('')
const uploadColor = ref('')
const error = ref('')
const replaceStatus = ref('')
const previewStatus = ref('')
const previewMode = ref<'empty' | 'text' | 'image' | 'frame'>('empty')
const previewText = ref('')
const previewUrl = ref('')
const previewEnsureKeys = ref(new Set<string>())
const previewRequestId = ref(0)

const currentOrg = computed(() => String(domainOptions.value.find((item) => item.domain === selectedDomain.value)?.org_id || selectedDomain.value || 'platform'))
const selectedDoc = computed(() => docs.value.find((doc) => docKey(doc) === selectedDocKey.value) || null)
const specialFolders = computed(() => [
  { key: 'all', title: '全部文档', count: docs.value.length, icon: '▦' },
  { key: 'unfiled', title: '未分组', count: docsInFolder('unfiled').length, icon: '▸' },
])
// 按 org / 业务域分组的层级树
const folderGroups = computed(() => {
  const groups = new Map<string, { org: string; label: string; count: number; items: JsonMap[] }>()
  for (const c of collections.value) {
    const org = String(c.org_id || '')
    if (!groups.has(org)) groups.set(org, { org, label: orgLabel(org), count: 0, items: [] })
    const g = groups.get(org)!
    const cnt = Number(c.item_count ?? c.resource_count ?? (c.items || []).length)
    g.items.push({ key: collectionKey(c), title: c.title || c.collection_id, count: cnt })
    g.count += cnt
  }
  return Array.from(groups.values()).sort((a, b) => a.label.localeCompare(b.label))
})
// 扁平列表仅供「上传到」下拉等使用
const folders = computed(() => [
  ...specialFolders.value,
  ...collections.value.map((c) => ({ key: collectionKey(c), title: `${orgLabel(c.org_id)} / ${c.title || c.collection_id}`, count: Number(c.item_count ?? c.resource_count ?? (c.items || []).length), icon: '▸' })),
])
const collapsedOrgs = ref<Set<string>>(new Set())
function toggleOrg(org: string) { const s = new Set(collapsedOrgs.value); s.has(org) ? s.delete(org) : s.add(org); collapsedOrgs.value = s }
function orgExpanded(org: string) { return !collapsedOrgs.value.has(org) }
function pickFolder(key: string) {
  selectedFolder.value = key
  uploadTarget.value = key
  const next = docsInFolder(key)[0]
  if (next) void selectDoc(next)
  else { selectedDocKey.value = ''; resetPreview() }
}
const visibleDocs = computed(() => {
  const q = search.value.trim().toLowerCase()
  return docsInFolder(selectedFolder.value).filter((doc) => {
    if (!q) return true
    const folder = collectionForDoc(doc)
    return [doc.filename, doc.title, doc.doc_id, folder?.title, ...(Array.isArray(doc.tags) ? doc.tags : [])].join(' ').toLowerCase().includes(q)
  })
})
const selectedPreview = computed(() => selectedDoc.value ? previewSource(selectedDoc.value) : null)

function docKey(doc: JsonMap) { return `${doc.doc_id || doc.id || ''}::${doc.version_id || ''}::${doc.org_id || ''}` }
function itemKey(item: JsonMap) { return `${item.item_id || item.resource_id || ''}::${item.item_version_id || ''}` }
function collectionKey(c: JsonMap) { return `${c.org_id || ''}::${c.collection_id || ''}` }
function parseCollectionKey(key: string) { const idx = key.indexOf('::'); return idx >= 0 ? { org_id: key.slice(0, idx), collection_id: key.slice(idx + 2) } : { org_id: '', collection_id: key } }
function orgLabel(orgId: unknown) { const value = String(orgId || '').trim() || 'unknown'; const d = domainOptions.value.find((item) => item.org_id === value); return d ? `${d.label} · ${value}` : value }
function collectionForDoc(doc: JsonMap) { const key = `${doc.doc_id || doc.id || ''}::${doc.version_id || ''}`; return collections.value.find((c) => String(c.org_id || '') === String(doc.org_id || '') && Array.isArray(c.items) && c.items.some((item: JsonMap) => itemKey(item) === key)) || null }
function docsInFolder(folderKey: string) {
  if (folderKey === 'all') return docs.value
  const assigned = new Set(collections.value.flatMap((c) => (c.items || []).map((item: JsonMap) => `${c.org_id || ''}::${itemKey(item)}`)))
  if (folderKey === 'unfiled') return docs.value.filter((doc) => !assigned.has(`${doc.org_id || ''}::${doc.doc_id || doc.id || ''}::${doc.version_id || ''}`))
  const target = parseCollectionKey(folderKey)
  const c = collections.value.find((row) => row.collection_id === target.collection_id && String(row.org_id || '') === String(target.org_id || ''))
  const keys = new Set((c?.items || []).map(itemKey))
  return docs.value.filter((doc) => String(doc.org_id || '') === String(c?.org_id || '') && keys.has(`${doc.doc_id || doc.id || ''}::${doc.version_id || ''}`))
}
function parseStatus(doc: JsonMap) { return String(doc.parse_status || doc.status || 'unknown') }
function blockCount(doc: JsonMap) { return Number(doc.block_count ?? doc.blocks_count ?? doc.parsed_blocks_count ?? 0) || 0 }
function docExtension(doc: JsonMap) { const name = String(doc.filename || doc.object_key || doc.doc_id || '').toLowerCase(); const idx = name.lastIndexOf('.'); return idx >= 0 ? name.slice(idx + 1) : String(doc.doc_type || '').toLowerCase() }
function canPreviewRaw(doc: JsonMap) { const ext = docExtension(doc); const mime = String(doc.object_mime_type || '').toLowerCase(); return mime.startsWith('image/') || mime.startsWith('text/') || ['pdf','txt','md','markdown','csv','json','html','htm','svg','png','jpg','jpeg','gif','webp'].includes(ext) }
function canGeneratePreview(doc: JsonMap) { const ext = docExtension(doc); return Boolean(doc.raw_available && !doc.preview_available && ['doc','docx','ppt','pptx','xls','xlsx','odt','ods','odp','rtf'].includes(ext)) }
function previewSource(doc: JsonMap) {
  if (doc.preview_available) return { url: `/platform/frontend/knowledge/docs/${encodeURIComponent(String(doc.doc_id || doc.id))}/${encodeURIComponent(String(doc.version_id || 'v1'))}/preview`, label: '文件预览', hint: String(doc.preview_mime_type || 'application/pdf') }
  return null
}
function previewUnavailableText(doc: JsonMap) {
  if (doc.preview_message) return String(doc.preview_message)
  if (doc.preview_reason === 'cjk_font_missing') return '该文档包含中文正文，但当前后台环境缺少中文字体，已跳过 PDF 预览生成。'
  if (doc.raw_available) return '当前文件类型需要后台转换后预览，预览文件尚未生成。'
  return '当前文档没有可读取的原始文件或预览文件。'
}
function previewEnsureKey(doc: JsonMap) { return `${doc.org_id || ''}::${doc.doc_id || ''}::${doc.version_id || ''}` }
function docHeaders(doc: JsonMap, json = false) { return { ...makeHeaders(json, String(doc.org_id || currentOrg.value)), 'x-user-id': currentUser() } }
function revokePreviewUrl() { if (previewUrl.value?.startsWith('blob:')) URL.revokeObjectURL(previewUrl.value); previewUrl.value = '' }
function resetPreview() { previewRequestId.value++; revokePreviewUrl(); previewMode.value = 'empty'; previewText.value = ''; previewStatus.value = selectedDoc.value ? previewUnavailableText(selectedDoc.value) : '' }

async function loadDomains() {
  try {
    const data = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: makeHeaders(false, 'platform') }))
    const raw = data.domains && typeof data.domains === 'object' ? data.domains : {}
    const rows = Object.entries(raw).map(([domain, meta]: [string, any]) => ({ domain, label: meta?.display_name || domain, org_id: meta?.org_id || domain }))
    domainOptions.value = [{ domain: 'platform', label: '平台', org_id: 'platform' }, ...rows.filter((item) => item.domain !== 'platform')]
    if (!domainOptions.value.some((item) => item.domain === selectedDomain.value)) selectedDomain.value = 'platform'
  } catch {}
}
async function loadDocs() {
  error.value = ''
  try {
    const domain = encodeURIComponent(selectedDomain.value)
    const [docData, colData] = await Promise.all([
      readJson<JsonMap>(await fetch(`/platform/frontend/knowledge/docs?latest_only=true&limit=500&domain=${domain}`, { headers: makeHeaders(false, 'platform') })),
      readJson<JsonMap>(await fetch(`/platform/frontend/knowledge/collections?domain=${domain}&include_items=true&item_type=document`, { headers: makeHeaders(false, 'platform') })),
    ])
    docs.value = docData.documents || docData.items || []
    collections.value = colData.items || []
    if (!selectedDocKey.value || !docs.value.some((doc) => docKey(doc) === selectedDocKey.value)) {
      selectedDocKey.value = docs.value[0] ? docKey(docs.value[0]) : ''
    }
  } catch (err) { error.value = err instanceof Error ? err.message : String(err) }
}
async function selectDoc(doc: JsonMap) {
  if (selectedDocKey.value === docKey(doc)) return
  selectedDocKey.value = docKey(doc)
  replaceStatus.value = ''
  resetPreview()
  const preview = previewSource(doc)
  if (preview) await loadDocumentPreview(doc, preview)
}
async function changeDomain() {
  localStorage.setItem('platform_live_domain', selectedDomain.value)
  localStorage.setItem('platform_live_org_id', currentOrg.value)
  const params = new URLSearchParams(location.search)
  params.set('domain', selectedDomain.value)
  params.set('org_id', currentOrg.value)
  history.replaceState(null, '', `${location.pathname}?${params.toString()}${location.hash}`)
  selectedFolder.value = 'all'
  uploadTarget.value = 'all'
  selectedDocKey.value = ''
  resetPreview()
  await loadDocs()
}
async function createFolder() {
  const title = newFolderTitle.value.trim()
  if (!title) return
  const data = await readJson<JsonMap>(await fetch('/platform/frontend/knowledge/collections', { method: 'POST', headers: makeHeaders(true, currentOrg.value), body: JSON.stringify({ domain: selectedDomain.value, title, collection_type: 'folder', scope: 'org_shared' }) }))
  newFolderTitle.value = ''
  await loadDocs()
  const id = data.item?.collection_id || data.collection_id
  if (id) selectedFolder.value = `${currentOrg.value}::${id}`
}
async function pollParseStatus(docId: unknown, versionId: unknown, filename: string) {
  const maxAttempts = 40
  for (let i = 0; i < maxAttempts; i++) {
    await new Promise((r) => setTimeout(r, 3000))
    try {
      const domain = encodeURIComponent(selectedDomain.value)
      const data = await readJson<JsonMap>(await fetch(`/platform/frontend/knowledge/docs?latest_only=true&limit=500&domain=${domain}`, { headers: makeHeaders(false, 'platform') }))
      const list: JsonMap[] = data.documents || data.items || []
      const doc = list.find((x) => String(x.doc_id) === String(docId) && String(x.version_id || '') === String(versionId || ''))
      if (!doc) continue
      const s = parseStatus(doc)
      if (s === 'parsed' || s === 'succeeded') { uploading.value = `✓ ${filename} 解析完成 (${blockCount(doc)} 块)`; uploadColor.value = 'ok'; await loadDocs(); return }
      if (s === 'failed' || s === 'parse_failed') { uploading.value = `⚠ ${filename} 解析失败（文件已上传，可手动重试解析）`; uploadColor.value = 'warn'; return }
      uploading.value = `⏳ ${filename} 解析中... (${blockCount(doc)} 段落)`
      uploadColor.value = ''
    } catch { break }
  }
  uploading.value = `${filename} 上传完成（解析状态未知）`
  uploadColor.value = ''
}
async function uploadFiles(ev: Event) {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (!files.length) return
  const target = parseCollectionKey(uploadTarget.value)
  let lastDocId: unknown = null
  let lastVersionId: unknown = null
  let lastName = ''
  for (const file of files) {
    uploading.value = `上传中: ${file.name}...`
    uploadColor.value = ''
    try {
      const fd = new FormData()
      fd.append('file', file)
      fd.append('domain', selectedDomain.value)
      if (target.collection_id && !['all', 'unfiled'].includes(target.collection_id)) fd.append('collection_id', target.collection_id)
      const data = await readJson<JsonMap>(await fetch('/platform/frontend/knowledge/upload', { method: 'POST', headers: makeHeaders(false, target.org_id || currentOrg.value), body: fd }))
      lastDocId = data.doc_id
      lastVersionId = data.version_id
      lastName = file.name
      uploading.value = `✓ ${file.name} 上传成功，等待解析...`
    } catch (err) {
      uploading.value = `✗ ${file.name}: ${err instanceof Error ? err.message : String(err)}`
      uploadColor.value = 'error'
    }
  }
  if (target.collection_id && !['all', 'unfiled'].includes(target.collection_id)) selectedFolder.value = uploadTarget.value
  await loadDocs()
  if (lastDocId) {
    const doc = docs.value.find((d) => String(d.doc_id) === String(lastDocId) && String(d.version_id || '') === String(lastVersionId || ''))
    if (doc) await selectDoc(doc)
    pollParseStatus(lastDocId, lastVersionId, lastName)
  }
}
async function moveDoc(doc: JsonMap, folderKey: string) {
  const target = parseCollectionKey(folderKey)
  const id = doc.doc_id || doc.id
  const version = doc.version_id || ''
  for (const c of collections.value) {
    if (!Array.isArray(c.items) || !c.items.some((item: JsonMap) => itemKey(item) === `${id}::${version}`)) continue
    await fetch(`/platform/frontend/knowledge/collections/${encodeURIComponent(String(c.collection_id))}/items/document/${encodeURIComponent(String(id))}?item_version_id=${encodeURIComponent(String(version))}`, { method: 'DELETE', headers: makeHeaders(false, String(c.org_id || currentOrg.value)) })
  }
  if (target.collection_id && !['all', 'unfiled'].includes(target.collection_id)) await readJson(await fetch(`/platform/frontend/knowledge/collections/${encodeURIComponent(target.collection_id)}/items`, { method: 'POST', headers: makeHeaders(true, target.org_id || currentOrg.value), body: JSON.stringify({ item_type: 'document', item_id: id, item_version_id: version }) }))
  await loadDocs()
}
async function reindexDoc(doc: JsonMap) {
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/knowledge/docs/${encodeURIComponent(String(doc.doc_id || doc.id))}/${encodeURIComponent(String(doc.version_id || 'v1'))}/reindex?org_id=${encodeURIComponent(String(doc.org_id || currentOrg.value))}`, { method: 'POST', headers: docHeaders(doc) }))
    const bm = (data.bm25 as JsonMap) || {}
    notifySuccess(`已重新解析并建立索引：${bm.indexed ?? 0} 个文本块`)
  } catch (err) { notifyError(err) }
}
async function deleteDoc(doc: JsonMap) {
  if (!await confirmDialog(`确定删除文档 ${doc.filename || doc.doc_id} 吗？`, { title: '删除文档', danger: true })) return
  try {
    await readJson(await fetch(`/platform/frontend/knowledge/docs/${encodeURIComponent(String(doc.doc_id || doc.id))}/${encodeURIComponent(String(doc.version_id || ''))}?org_id=${encodeURIComponent(String(doc.org_id || currentOrg.value))}`, { method: 'DELETE', headers: makeHeaders(false, String(doc.org_id || currentOrg.value)) }))
    selectedDocKey.value = ''
    resetPreview()
    await loadDocs()
    notifySuccess('文档已删除')
  } catch (err) { notifyError(err) }
}
async function replaceDoc(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  const doc = selectedDoc.value
  if (!file || !doc) return
  replaceStatus.value = '正在上传替换文件...'
  const fd = new FormData()
  fd.append('file', file)
  const resp = await fetch(`/platform/frontend/knowledge/docs/${encodeURIComponent(String(doc.doc_id))}/${encodeURIComponent(String(doc.version_id || 'v1'))}/replace`, { method: 'POST', headers: docHeaders(doc), body: fd })
  const data = await resp.json().catch(() => ({}))
  if (!resp.ok) { replaceStatus.value = `替换失败: ${data.detail || data.message || resp.status}`; return }
  replaceStatus.value = data.new_version_id || data.version_id ? `已替换为 ${data.new_version_id || data.version_id}` : '已替换'
  await loadDocs()
  const next = docs.value.find((item) => String(item.doc_id) === String(doc.doc_id) && (!data.new_version_id || String(item.version_id) === String(data.new_version_id)))
  if (next) await selectDoc(next)
}
async function ensureDocumentPreview(doc: JsonMap, auto = false) {
  previewEnsureKeys.value.add(previewEnsureKey(doc))
  previewStatus.value = auto ? '正在自动生成预览...' : '正在生成预览...'
  const resp = await fetch(`/platform/frontend/knowledge/docs/${encodeURIComponent(String(doc.doc_id))}/${encodeURIComponent(String(doc.version_id || 'v1'))}/preview/ensure?org_id=${encodeURIComponent(String(doc.org_id || currentOrg.value))}`, { method: 'POST', headers: docHeaders(doc) })
  const data = await resp.json().catch(() => ({}))
  if (!resp.ok) { previewStatus.value = `预览生成失败: ${data.detail || data.message || resp.status}`; return }
  if (data.preview_ready) { previewStatus.value = '预览已生成，正在加载...'; await loadDocs(); const next = selectedDoc.value; if (next) await loadDocumentPreview(next, previewSource(next) || data) }
  else previewStatus.value = data.preview_message || data.preview_reason || '预览暂不可用'
}
async function loadDocumentPreview(doc: JsonMap, preview: JsonMap) {
  const requestId = ++previewRequestId.value
  revokePreviewUrl()
  previewStatus.value = '正在加载预览...'
  previewMode.value = 'empty'
  const ext = docExtension(doc)
  const mime = String(doc.object_mime_type || '').toLowerCase()
  const isText = ['csv','json','txt','md','markdown'].includes(ext) || mime.startsWith('text/') || mime === 'application/json'
  const isImage = mime.startsWith('image/') || ['png','jpg','jpeg','gif','webp','svg'].includes(ext)
  try {
    const resp = await fetch(String(preview.url), { headers: docHeaders(doc) })
    if (!resp.ok) throw new Error(await resp.text())
    if (!selectedDoc.value || docKey(selectedDoc.value) !== docKey(doc) || requestId !== previewRequestId.value) return
    if (isText) {
      const text = await resp.text()
      if (!selectedDoc.value || docKey(selectedDoc.value) !== docKey(doc) || requestId !== previewRequestId.value) return
      previewText.value = text
      previewMode.value = 'text'
    } else {
      const blob = await resp.blob()
      if (!selectedDoc.value || docKey(selectedDoc.value) !== docKey(doc) || requestId !== previewRequestId.value) return
      previewUrl.value = URL.createObjectURL(blob)
      previewMode.value = isImage ? 'image' : 'frame'
    }
    previewStatus.value = `${preview.label || '预览'} · ${preview.hint || ''}`
  } catch (err) {
    if (requestId !== previewRequestId.value || !selectedDoc.value || docKey(selectedDoc.value) !== docKey(doc)) return
    previewMode.value = 'empty'
    previewStatus.value = `预览加载失败: ${err instanceof Error ? err.message : String(err)}`
  }
}
function triggerReplace() { (document.getElementById('replaceFileInput') as HTMLInputElement | null)?.click() }

onMounted(async () => { await loadDomains(); await loadDocs() })
onBeforeUnmount(revokePreviewUrl)
</script>

<template>
  <div class="knowledge-page">
    <div class="kn-topbar">
      <label>业务域</label>
      <select v-model="selectedDomain" @change="changeDomain"><option v-for="d in domainOptions" :key="d.domain" :value="d.domain">{{ d.label }} ({{ d.domain }})</option></select>
      <button class="btn btn-ghost btn-sm" @click="loadDocs">刷新文档</button>
      <span class="kn-spacer"></span>
      <a class="btn btn-primary btn-sm" :href="contextHref('/platform/live/qa', selectedDomain, currentOrg)">打开交互问答</a>
    </div>
    <section class="knowledge-workspace">
    <aside class="folder-pane"><div class="pane-head"><h2>知识库分组</h2><span class="pane-count">{{ collections.length }} 个</span></div><div class="folder-create"><input v-model="newFolderTitle" placeholder="新建分组名称" @keydown.enter="createFolder" /><button class="btn btn-primary btn-sm" @click="createFolder">新建</button></div><div class="folder-list">
        <button v-for="f in specialFolders" :key="f.key" class="folder-item" :class="{active: selectedFolder === f.key}" @click="pickFolder(f.key)"><span class="folder-icon">{{ f.icon }}</span><span class="folder-label">{{ f.title }}</span><span class="folder-count">{{ f.count }}</span></button>
        <div v-for="g in folderGroups" :key="g.org" class="folder-group">
          <button class="folder-org" @click="toggleOrg(g.org)"><span class="folder-caret">{{ orgExpanded(g.org) ? '▾' : '▸' }}</span><span class="folder-label">{{ g.label }}</span><span class="folder-count">{{ g.count }}</span></button>
          <div v-show="orgExpanded(g.org)" class="folder-children">
            <button v-for="c in g.items" :key="c.key as string" class="folder-item child" :class="{active: selectedFolder === c.key}" @click="pickFolder(String(c.key))"><span class="folder-icon">▸</span><span class="folder-label">{{ c.title }}</span><span class="folder-count">{{ c.count }}</span></button>
            <div v-if="!g.items.length" class="folder-empty">无分组</div>
          </div>
        </div>
      </div></aside>
    <aside class="left-pane"><div class="pane-head"><h2>知识文档 <span class="badge">Beta</span></h2><span class="pane-count">{{ visibleDocs.length }} / {{ docs.length }} 篇</span></div><div class="search-wrap"><input v-model="search" placeholder="搜索文档..." /></div><div class="doc-list"><div v-if="!visibleDocs.length" class="empty">当前分组暂无文档</div><div v-for="doc in visibleDocs" :key="docKey(doc)" class="doc-item" :class="{selected: selectedDocKey === docKey(doc)}" @click="selectDoc(doc)"><div class="doc-name"><span :class="['doc-status', parseStatus(doc).includes('fail') ? 'status-fail' : parseStatus(doc).includes('parsed') || parseStatus(doc).includes('succeeded') ? 'status-ok' : 'status-pending']">●</span>{{ doc.filename || doc.doc_id }}</div><div class="doc-meta">{{ orgLabel(doc.org_id) }} / {{ collectionForDoc(doc)?.title || '未分组' }} · {{ blockCount(doc) }} 块 · {{ doc.doc_type || 'unknown' }}</div></div></div><div class="upload-area"><label class="drop-zone"><input type="file" accept=".pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.md,.markdown,.txt,.csv" multiple @change="uploadFiles" /><div>📎 点击选择文档</div><div class="muted-small">支持 PDF、Office、Markdown、TXT、CSV；扫描件 OCR 敬请期待</div></label><div class="upload-target"><label>上传到:</label><select v-model="uploadTarget"><option v-for="f in folders" :key="f.key" :value="f.key">{{ f.title }}</option></select></div><div v-if="uploading" class="muted-small" :class="{ 'error-line': uploadColor === 'error', 'ok-line': uploadColor === 'ok', 'warn-line': uploadColor === 'warn' }">{{ uploading }}</div></div></aside>
    <main class="content"><p v-if="error" class="error-line">{{ error }}</p>
      <section class="panel"><div class="panel-head"><h3>文档详情</h3><div class="actions" v-if="selectedDoc"><button class="btn btn-ghost btn-sm" @click="ensureDocumentPreview(selectedDoc)">预览文件</button><a class="btn btn-primary btn-sm" :href="contextHref('/platform/live/qa', selectedDomain, selectedDoc.org_id || currentOrg) + '&doc_id=' + encodeURIComponent(selectedDoc.doc_id || selectedDoc.id) + '&version_id=' + encodeURIComponent(selectedDoc.version_id || '') + (selectedDoc.org_id ? '&doc_org_id=' + encodeURIComponent(selectedDoc.org_id) : '')">限定此文档问答</a><button class="btn btn-ghost btn-sm" @click="reindexDoc(selectedDoc)">重新解析并建索引</button><button class="btn btn-ghost btn-sm" @click="deleteDoc(selectedDoc)">删除</button></div></div>
        <div class="panel-body" v-if="selectedDoc"><div class="detail-grid"><div class="detail-key">文件名</div><div class="detail-val">{{ selectedDoc.filename || selectedDoc.doc_id }}</div><div class="detail-key">组织</div><div class="detail-val">{{ orgLabel(selectedDoc.org_id) }}</div><div class="detail-key">解析状态</div><div class="detail-val">{{ parseStatus(selectedDoc) }} · {{ blockCount(selectedDoc) }} 块</div><div class="detail-key">向量索引</div><div class="detail-val"><span class="badge">{{ selectedDoc.vector_index_status || '待建立' }}</span><span v-if="selectedDoc.vector_indexed_chunks" class="muted-small"> · {{ selectedDoc.vector_indexed_chunks }} 块</span><div v-if="selectedDoc.vector_index_message" class="muted-small">{{ selectedDoc.vector_index_message }}</div></div><div class="detail-key">Doc ID</div><div class="detail-val mono">{{ selectedDoc.doc_id || selectedDoc.id }} · {{ selectedDoc.version_id || 'v1' }}</div><div class="detail-key">类型</div><div class="detail-val"><span class="badge">{{ selectedDoc.doc_type || 'unknown' }}</span></div><div class="detail-key">分组</div><div class="detail-val"><select :value="collectionForDoc(selectedDoc) ? collectionKey(collectionForDoc(selectedDoc)!) : ''" @change="moveDoc(selectedDoc, ($event.target as HTMLSelectElement).value)"><option value="">未分组</option><option v-for="c in collections.filter(c => String(c.org_id || '') === String(selectedDoc?.org_id || ''))" :key="collectionKey(c)" :value="collectionKey(c)">{{ c.title }}</option></select></div></div><div v-if="selectedDoc.parse_message" class="muted-small">{{ selectedDoc.parse_message }}</div><div class="preview-wrap"><div class="preview-toolbar"><div><div class="preview-title">文件预览</div><div class="preview-hint">{{ previewStatus || '点击“预览文件”生成或打开预览。' }}</div></div><button v-if="selectedPreview" class="btn btn-ghost btn-sm" @click="loadDocumentPreview(selectedDoc, selectedPreview)">重新加载</button></div><pre v-if="previewMode === 'text'" class="preview-text">{{ previewText }}</pre><img v-else-if="previewMode === 'image'" class="preview-image" :src="previewUrl" alt="文档预览" /><iframe v-else-if="previewMode === 'frame'" class="preview-frame" :src="previewUrl" title="文档预览"></iframe><div v-else class="preview-empty">{{ previewStatus || 'PDF 可直接预览；Office 文件会转换为 PDF 后预览。扫描件 OCR 敬请期待。' }}</div></div></div>
        <div v-else class="empty-state"><div><div class="empty-icon">📚</div><strong>选择左侧文档查看索引信息</strong><div>这里负责知识库管理；问答请进入“交互问答”。</div></div></div>
      </section></main>
  </section>
  </div>
</template>

<style scoped>
.knowledge-page { flex: 1; display: flex; flex-direction: column; overflow: hidden; }
.kn-topbar { display: flex; align-items: center; gap: 10px; padding: 12px 18px; border-bottom: 1px solid var(--border); background: #fff; flex-shrink: 0; }
.kn-topbar label { font-size: 12px; color: var(--muted); font-weight: 700; }
.kn-topbar select { height: 34px; min-width: 220px; }
.kn-spacer { flex: 1; }
.knowledge-page .knowledge-workspace { flex: 1; min-height: 0; }
.folder-group { display: flex; flex-direction: column; }
.folder-org { width: 100%; display: flex; align-items: center; gap: 6px; padding: 8px 9px; border: 0; background: transparent; cursor: pointer; font-size: 12px; font-weight: 700; color: #475569; border-radius: 8px; text-align: left; margin-top: 2px; }
.folder-org:hover { background: #eef2f7; }
.folder-caret { width: 12px; font-size: 10px; color: #94a3b8; flex-shrink: 0; text-align: center; }
.folder-org .folder-label { flex: 1; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.folder-org .folder-count { font-size: 11px; color: var(--muted); font-weight: 600; }
.folder-children { display: flex; flex-direction: column; gap: 2px; margin-left: 6px; padding-left: 6px; border-left: 1px solid var(--border); }
.folder-item.child { padding-left: 8px; }
.folder-empty { font-size: 11px; color: #94a3b8; padding: 5px 10px; }
</style>
