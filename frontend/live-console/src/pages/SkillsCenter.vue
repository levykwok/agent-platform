<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { currentDomain, currentOrgId, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'
import { confirmDialog, promptDialog } from '../stores/dialog'

const PACKAGE_PERMISSIONS = ['db', 'kg', 'object_storage']

const skills = ref<JsonMap[]>([])
const packages = ref<JsonMap[]>([])
const domainOptions = ref<JsonMap[]>([{ domain: 'platform', display_name: '平台' }])
const activeSource = ref('')
const activeStatus = ref<'' | 'enabled' | 'disabled'>('')
const keyword = ref('')
const pkgStatus = ref('validated')
const pkgDomain = ref('')
const rejectReason = ref('')
const pkgOutput = ref<{ text: string; kind: 'ok' | 'err' | 'info' } | null>(null)
const packageError = ref('')
const error = ref('')
const domain = ref(currentDomain(''))
const uploadMode = ref<'zip' | 'folder'>('zip')
const packageFile = ref<File | null>(null)
const packageFiles = ref<File[]>([])
const packageFileName = ref('')
const packageVersion = ref('v1')
const packageSourceNote = ref('')
const uploading = ref(false)
const publishingPackageId = ref('')
const previewOpen = ref(false)
const previewPackageData = ref<JsonMap | null>(null)
const previewSelectedFile = ref('')
const previewFileContent = ref('')
const previewFileLoading = ref(false)
const detailOpen = ref(false)
const skillDetail = ref<JsonMap | null>(null)
const skillDetailLoading = ref(false)
const skillSelectedFile = ref('')
const skillFileContent = ref('')
const originalSkillFileContent = ref('')
const skillFileLoading = ref(false)
const skillTestResult = ref<JsonMap | null>(null)
const smokeTestingSkillId = ref('')
const drawerEditing = ref(false)
const skillEditorOpen = ref(false)
const editingSkillId = ref('')
const skillCreateMode = ref<'manual' | 'package'>('manual')
const skillScriptFiles = ref<File[]>([])
const skillForm = reactive({
  skill_id: '',
  name: '',
  source: 'platform',
  scope: 'agent',
  description: '',
  content: '',
  enabled: true,
})
const permDrafts = reactive<Record<string, { mode: string; checks: Set<string> }>>({})

function sourceOf(s: JsonMap) {
  return String(s.domain || s.source_domain || 'global')
}
function labelSource(src: string) {
  const found = domainOptions.value.find((item) => String(item.domain) === src)
  if (found) return String(found.display_name || found.domain)
  if (src === 'global' || src === 'platform') return '平台'
  return src || '平台'
}
function isEnabled(s: JsonMap) {
  return s.enabled !== false
}
const visibleSkills = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  return skills.value.filter((s) => {
    if (activeSource.value && sourceOf(s) !== activeSource.value) return false
    if (activeStatus.value === 'enabled' && !isEnabled(s)) return false
    if (activeStatus.value === 'disabled' && isEnabled(s)) return false
    if (!q) return true
    return [s.skill_id, s.name, s.display_name, s.description].join(' ').toLowerCase().includes(q)
  })
})
const sourceCounts = computed(() => {
  const counts = new Map<string, number>()
  for (const s of skills.value) {
    const src = sourceOf(s)
    counts.set(src, (counts.get(src) || 0) + 1)
  }
  return Array.from(counts.entries()).sort(([a], [b]) => {
    if (a === 'global' || a === 'platform') return -1
    if (b === 'global' || b === 'platform') return 1
    return a.localeCompare(b)
  })
})
const enabledCount = computed(() => skills.value.filter(isEnabled).length)
const disabledCount = computed(() => skills.value.length - enabledCount.value)
const existingSkillIds = computed(() => new Set(skills.value.map((s) => String(s.skill_id || '')).filter(Boolean)))
const uploadWillOverwrite = computed(() => {
  const id = skillForm.skill_id.trim()
  return Boolean(id && existingSkillIds.value.has(id))
})

function headers(json = false) {
  return makeHeaders(json, currentOrgId())
}
async function loadDomains() {
  try {
    const data = await readJson<JsonMap>(await fetch('/platform/frontend/domains', { headers: headers(false) }))
    const items = asList(data)
    domainOptions.value = items.length ? items : [{ domain: 'platform', display_name: '平台' }]
  } catch {
    domainOptions.value = [{ domain: 'platform', display_name: '平台' }]
  }
}
function setOutput(text: string, kind: 'ok' | 'err' | 'info' = 'info') {
  pkgOutput.value = { text, kind }
}
function packageId(p: JsonMap) {
  return String(p.id || p.package_id || '')
}
function packageWillOverwrite(p: JsonMap) {
  const id = String(p.skill_id || '')
  return Boolean(id && p.status !== 'published' && existingSkillIds.value.has(id))
}
function permissionsOf(p: JsonMap) {
  return Array.isArray(p.granted_permissions) ? p.granted_permissions : []
}
function permText(value: unknown) {
  if (value == null) return 'source 默认'
  const arr = Array.isArray(value) ? value : []
  return arr.length ? arr.join(', ') : '沙箱'
}
function statusClass(status: unknown) {
  const s = String(status || '')
  return ['validated', 'published', 'rejected'].includes(s) ? s : 'other'
}
function statusText(status: unknown) {
  return ({ validated: '待发布', published: '已发布', rejected: '已拒绝', deprecated: '已废弃' } as Record<string, string>)[String(status || '')] || String(status || 'unknown')
}
function versionText(version: unknown) {
  const value = String(version || '').trim()
  if (!value) return ''
  return value.toLowerCase().startsWith('v') ? value : `v${value}`
}
function draftFor(p: JsonMap) {
  const id = packageId(p)
  if (!permDrafts[id]) {
    const perms = permissionsOf(p)
    permDrafts[id] = { mode: p.granted_permissions == null ? 'default' : (perms.length ? 'custom' : 'sandbox'), checks: new Set(perms) }
  }
  return permDrafts[id]
}
function togglePermCheck(p: JsonMap, perm: string) {
  const draft = draftFor(p)
  if (draft.checks.has(perm)) draft.checks.delete(perm)
  else draft.checks.add(perm)
}
function collectPermissions(p: JsonMap) {
  const draft = draftFor(p)
  if (draft.mode === 'default') return null
  if (draft.mode === 'sandbox') return []
  return Array.from(draft.checks)
}
function asList(data: unknown): JsonMap[] {
  if (Array.isArray(data)) return data as JsonMap[]
  const obj = data as JsonMap
  if (Array.isArray(obj?.items)) return obj.items
  if (Array.isArray(obj?.skills)) return obj.skills
  return []
}
function filesOf(data: JsonMap | null) {
  const files = data?.files
  return Array.isArray(files) ? files.map(String) : []
}
function analysisOf(data: JsonMap | null): JsonMap {
  return (data?.analysis && typeof data.analysis === 'object' ? data.analysis : {}) as JsonMap
}
function countOf(data: JsonMap | null, key: string) {
  const value = analysisOf(data)[key]
  return typeof value === 'number' ? value : Number(value || 0)
}
function listOf(data: JsonMap | null, key: string) {
  const value = analysisOf(data)[key]
  return Array.isArray(value) ? value.map(String) : []
}
function isScriptPath(path: string) {
  const lower = path.toLowerCase()
  const name = fileName(lower)
  return lower.startsWith('scripts/') || lower.includes('/scripts/') || ['.py', '.js', '.mjs', '.ts', '.sh', '.ps1', '.bat'].some((suffix) => name.endsWith(suffix))
}
function canTestSkill(data: JsonMap | null) {
  if (!data) return false
  if (countOf(data, 'script_count') > 0) return true
  if (listOf(data, 'scripts').length > 0) return true
  return filesOf(data).some(isScriptPath)
}
function skillStorageLabel(data: JsonMap | null) {
  const type = String(data?.type || '')
  const location = String(data?.location || '')
  if (type === 'filesystem' && location === 'skills') return '平台托管'
  if (type === 'classpath') return '内置资源'
  return type || '未知'
}
function skillLogicalLocation(data: JsonMap | null) {
  const source = sourceOf(data || {})
  const skillId = String(data?.skill_id || '')
  return [source, skillId].filter(Boolean).join(' / ') || '-'
}
function boolText(value: unknown) {
  return value ? '有' : '无'
}
function fileName(path: string) {
  return path.split('/').filter(Boolean).pop() || path
}
function fileDir(path: string) {
  const parts = path.split('/').filter(Boolean)
  parts.pop()
  return parts.join('/')
}
function fileDepth(path: string) {
  return Math.max(0, path.split('/').filter(Boolean).length - 1)
}
function fileIcon(path: string) {
  return path.endsWith('.md') ? 'md' : path.includes('.') ? 'file' : 'txt'
}
function resetSkillForm() {
  editingSkillId.value = ''
  skillForm.skill_id = ''
  skillForm.name = ''
  skillForm.source = domain.value || 'platform'
  skillForm.scope = 'platform'
  skillForm.description = ''
  skillForm.content = ''
  skillForm.enabled = true
  skillScriptFiles.value = []
}
function openCreateSkill() {
  resetSkillForm()
  skillCreateMode.value = 'manual'
  uploadMode.value = 'zip'
  packageFile.value = null
  packageFiles.value = []
  packageFileName.value = ''
  packageVersion.value = 'v1'
  packageSourceNote.value = ''
  pkgOutput.value = null
  skillEditorOpen.value = true
}
function openEditSkill(s: JsonMap) {
  editingSkillId.value = String(s.skill_id || '')
  skillForm.skill_id = String(s.skill_id || '')
  skillForm.name = String(s.name || s.display_name || s.skill_id || '')
  skillForm.source = String(s.source || sourceOf(s) || 'platform')
  skillForm.scope = String(s.scope || 'agent')
  skillForm.description = String(s.description || '')
  skillForm.content = ''
  skillForm.enabled = isEnabled(s)
  skillScriptFiles.value = []
  packageVersion.value = 'v1'
  packageSourceNote.value = ''
  packageFile.value = null
  packageFiles.value = []
  packageFileName.value = ''
  pkgOutput.value = null
  drawerEditing.value = true
  if (!detailOpen.value || String(skillDetail.value?.skill_id || '') !== editingSkillId.value) {
    openSkillDetail(s, true)
  }
}
function onPickSkillScripts(event: Event) {
  skillScriptFiles.value = Array.from((event.target as HTMLInputElement).files || [])
}
async function readSkillScripts() {
  const scripts = []
  for (const file of skillScriptFiles.value) {
    scripts.push({ name: file.name, content: await file.text(), size: file.size, type: file.type || '' })
  }
  return scripts
}
async function loadSkills() {
  try {
    const d = domain.value.trim()
    if (d && !activeSource.value) activeSource.value = d
    const qs = d ? `?domain=${encodeURIComponent(d)}` : ''
    const data = await readJson(await fetch(`/platform/frontend/skills${qs}`, { headers: headers(false) }))
    skills.value = asList(data)
    error.value = ''
  } catch (err) {
    error.value = err instanceof Error ? err.message : String(err)
  }
}
async function saveSkill() {
  if (!skillForm.skill_id.trim()) {
    notifyError('请填写 Skill ID')
    return
  }
  if (!editingSkillId.value && !skillForm.description.trim()) {
    notifyError('请填写 Skill 描述')
    return
  }
  if (!editingSkillId.value && !skillForm.content.trim()) {
    notifyError('请填写 Skill 正文')
    return
  }
  try {
    const body = editingSkillId.value
      ? {
          skill_id: skillForm.skill_id.trim(),
          name: skillForm.name.trim() || skillForm.skill_id.trim(),
          description: skillForm.description,
          source: skillForm.source.trim() || 'platform',
          scope: skillForm.scope,
          enabled: skillForm.enabled,
          type: 'filesystem',
          location: 'skills',
        }
      : {
          skill_id: skillForm.skill_id.trim(),
          name: skillForm.name.trim() || skillForm.skill_id.trim(),
          description: skillForm.description,
          content: skillForm.content,
          scripts: await readSkillScripts(),
          source: skillForm.source.trim() || 'platform',
          scope: skillForm.scope,
          enabled: skillForm.enabled,
          create_file: true,
        }
    const url = editingSkillId.value
      ? `/platform/frontend/skills/${encodeURIComponent(editingSkillId.value)}`
      : '/platform/frontend/skills'
    const method = editingSkillId.value ? 'PUT' : 'POST'
    await readJson(await fetch(url, { method, headers: headers(true), body: JSON.stringify(body) }))
    skillEditorOpen.value = false
    await loadSkills()
    notifySuccess(`Skill 已保存: ${body.skill_id}`)
  } catch (err) {
    notifyError(err)
  }
}
async function saveDrawerSkill() {
  const skillId = String(skillDetail.value?.skill_id || skillForm.skill_id || '').trim()
  if (!skillId) {
    notifyError('缺少 Skill ID')
    return
  }
  try {
    const body = {
      skill_id: skillId,
      name: skillForm.name.trim() || skillId,
      description: skillForm.description,
      source: skillForm.source.trim() || 'platform',
      scope: skillForm.scope || 'platform',
      enabled: skillForm.enabled,
      type: String(skillDetail.value?.type || 'filesystem'),
      location: String(skillDetail.value?.location || 'skills'),
    }
    await readJson(await fetch(`/platform/frontend/skills/${encodeURIComponent(skillId)}`, { method: 'PUT', headers: headers(true), body: JSON.stringify(body) }))
    if (skillSelectedFile.value && skillFileContent.value !== originalSkillFileContent.value) {
      await saveSelectedSkillFile(false)
    }
    await Promise.all([loadSkills(), openSkillDetail({ skill_id: skillId }, true)])
    notifySuccess(`Skill 已保存: ${skillId}`)
  } catch (err) {
    notifyError(err)
  }
}
async function saveSelectedSkillFile(showToast = true) {
  const skillId = String(skillDetail.value?.skill_id || '')
  const path = skillSelectedFile.value
  if (!skillId || !path) {
    notifyError('请先选择要保存的文件')
    return
  }
  try {
    await readJson(await fetch(`/platform/frontend/skills/${encodeURIComponent(skillId)}/files?path=${encodeURIComponent(path)}`, { method: 'PUT', headers: headers(true), body: JSON.stringify({ content: skillFileContent.value }) }))
    originalSkillFileContent.value = skillFileContent.value
    if (showToast) notifySuccess(`文件已保存: ${path}`)
  } catch (err) {
    notifyError(err)
  }
}
async function loadPackages() {
  try {
    const params = new URLSearchParams({ limit: '200' })
    const d = pkgDomain.value.trim()
    if (d) params.set('domain', d)
    if (pkgStatus.value) params.set('status', pkgStatus.value)
    const data = await readJson(await fetch(`/platform/frontend/skills/packages?${params}`, { headers: headers(false) }))
    packages.value = asList(data)
    packageError.value = ''
    for (const p of packages.value) draftFor(p)
  } catch (err) {
    packageError.value = err instanceof Error ? err.message : String(err)
    packages.value = []
  }
}
async function syncSkills() {
  try {
    const data = await readJson<JsonMap>(await fetch('/platform/frontend/skills/sync', { method: 'POST', headers: headers(false) }))
    const count = Array.isArray(data.synced) ? data.synced.length : 0
    await Promise.all([loadSkills(), loadPackages()])
    notifySuccess(`同步完成: ${count} 个 Skills`)
  } catch (err) {
    notifyError(err)
  }
}
async function toggleSkill(s: JsonMap, enable: boolean) {
  const action = enable ? 'enable' : 'disable'
  try {
    await readJson(await fetch(`/platform/frontend/skills/${encodeURIComponent(String(s.skill_id))}/${action}`, { method: 'POST', headers: headers(true), body: JSON.stringify({ domain: domain.value || sourceOf(s) }) }))
    s.enabled = enable
    notifySuccess(`${enable ? '已启用' : '已禁用'}: ${s.skill_id}`)
  } catch (err) {
    notifyError(err)
    loadSkills()
  }
}
async function testSkill(s: JsonMap) {
  const skillId = String(s.skill_id || '')
  if (!skillId) return
  smokeTestingSkillId.value = skillId
  skillTestResult.value = null
  try {
    const d = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/${encodeURIComponent(skillId)}/test`, { method: 'POST', headers: headers(true), body: JSON.stringify({}) }))
    skillTestResult.value = { ...d, skill_id: skillId }
    const ok = d.ok === true
    notifySuccess(ok ? `Smoke Test 通过: ${skillId}` : `Smoke Test 未通过: ${skillId}`)
  } catch (err) {
    notifyError(err)
  } finally {
    smokeTestingSkillId.value = ''
  }
}
async function openSkillDetail(s: JsonMap, edit = false) {
  const skillId = String(s.skill_id || '')
  if (!skillId) return
  detailOpen.value = true
  drawerEditing.value = edit
  skillDetail.value = s
  skillDetailLoading.value = true
  skillSelectedFile.value = ''
  skillFileContent.value = ''
  originalSkillFileContent.value = ''
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/${encodeURIComponent(skillId)}`, { headers: headers(false) }))
    skillDetail.value = data
    const files = filesOf(data)
    const first = files.find((file) => file.endsWith('SKILL.md')) || files[0] || ''
    if (first) {
      await selectSkillFile(first)
    }
  } catch (err) {
    notifyError(err)
  } finally {
    skillDetailLoading.value = false
  }
}
async function selectSkillFile(path: string) {
  const skillId = String(skillDetail.value?.skill_id || '')
  if (!skillId || !path) return
  skillSelectedFile.value = path
  skillFileLoading.value = true
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/${encodeURIComponent(skillId)}/files?path=${encodeURIComponent(path)}`, { headers: headers(false) }))
    skillFileContent.value = String(data.content || '')
    originalSkillFileContent.value = skillFileContent.value
  } catch (err) {
    skillFileContent.value = `读取失败: ${err instanceof Error ? err.message : String(err)}`
    originalSkillFileContent.value = skillFileContent.value
  } finally {
    skillFileLoading.value = false
  }
}
async function deleteSkill(s: JsonMap) {
  const skillId = String(s.skill_id || '')
  if (!skillId) return
  if (!(await confirmDialog(`删除 Skill ${skillId}？`, { title: '删除 Skill', danger: true }))) return
  try {
    const res = await fetch(`/platform/frontend/skills/${encodeURIComponent(skillId)}`, { method: 'DELETE', headers: headers(false) })
    if (!res.ok) throw new Error(await res.text())
    await loadSkills()
    detailOpen.value = false
    notifySuccess(`已删除 Skill: ${skillId}`)
  } catch (err) {
    notifyError(err)
  }
}
function onPickFile(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] || null
  packageFile.value = file
  packageFileName.value = file?.name || ''
  if (file && !skillForm.skill_id.trim()) {
    skillForm.skill_id = file.name.replace(/\.zip$/i, '')
  }
}
function onPickFolder(event: Event) {
  const files = Array.from((event.target as HTMLInputElement).files || []).filter(isUploadableFolderFile)
  packageFiles.value = files
  const first = files[0] as (File & { webkitRelativePath?: string }) | undefined
  const root = first?.webkitRelativePath?.split('/')[0] || ''
  packageFileName.value = root ? `${root} (${files.length} 个文件)` : `${files.length} 个文件`
  if (root && !skillForm.skill_id.trim()) {
    skillForm.skill_id = root
  }
}
function isUploadableFolderFile(file: File) {
  const rel = ((file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name).replace(/\\/g, '/')
  const name = rel.split('/').pop() || ''
  if (!name || name === '.DS_Store' || name === 'Thumbs.db' || name === 'desktop.ini') return false
  return !rel.split('/').some(part => part === '__MACOSX')
}
async function uploadPackage() {
  const d = (skillForm.source || domain.value || 'platform').trim()
  if (!d) {
    setOutput('请选择业务域。', 'err')
    return
  }
  if (!skillForm.skill_id.trim()) {
    setOutput('请填写 Skill ID。', 'err')
    return
  }
  if (uploadMode.value === 'zip' && !packageFile.value) {
    setOutput('请选择 zip 包。', 'err')
    return
  }
  if (uploadMode.value === 'folder' && !packageFiles.value.length) {
    setOutput('请选择 Skill 文件夹。', 'err')
    return
  }
  uploading.value = true
  setOutput('上传并校验中…', 'info')
  try {
    const fd = new FormData()
    const params = new URLSearchParams({ domain: d })
    params.set('skill_id', skillForm.skill_id.trim())
    if (skillForm.name.trim()) params.set('name', skillForm.name.trim())
    if (packageVersion.value.trim()) params.set('version', packageVersion.value.trim())
    if (skillForm.description.trim()) params.set('description', skillForm.description.trim())
    if (packageSourceNote.value.trim()) params.set('source_note', packageSourceNote.value.trim())
    let url = `/platform/frontend/skills/packages/upload?${params}`
    if (uploadMode.value === 'zip') {
      fd.append('file', packageFile.value as File)
    } else {
      const paths: string[] = []
      for (const file of packageFiles.value.filter(isUploadableFolderFile)) {
        const rel = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name
        paths.push(rel)
        fd.append('files', file, file.name)
      }
      fd.append('manifest', JSON.stringify(paths))
      url = `/platform/frontend/skills/packages/upload-folder?${params}`
    }
    const data = await readJson<JsonMap>(await fetch(url, { method: 'POST', headers: headers(false), body: fd }))
    const rejected = String(data.status || '') === 'rejected'
    setOutput(JSON.stringify(data, null, 2), rejected ? 'err' : 'ok')
    pkgDomain.value = d
    await Promise.all([loadPackages(), loadSkills()])
    if (rejected) {
      notifyError(`Skill 包校验失败: ${(data.validation_errors as string[] | undefined)?.join('; ') || data.skill_id || packageFileName.value}`)
      return
    }
    if (detailOpen.value) {
      detailOpen.value = false
      drawerEditing.value = false
    }
    editingSkillId.value = ''
    skillEditorOpen.value = false
    pkgOutput.value = null
    notifySuccess(`上传成功，待发布: ${data.skill_id}@${data.version}`)
  } catch (err) {
    setOutput(`上传失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  } finally {
    uploading.value = false
  }
}
async function publishPackage(p: JsonMap) {
  const id = packageId(p)
  if (!id || publishingPackageId.value) return
  publishingPackageId.value = id
  setOutput(`正在发布 Package #${id}…`, 'info')
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(id)}/publish`, { method: 'POST', headers: headers(true), body: JSON.stringify({ permissions: collectPermissions(p) }) }))
    setOutput(`发布成功: ${data.skill_id || p.skill_id || ''}@${data.version || p.version || ''}`, 'ok')
    await Promise.all([loadPackages(), loadSkills()])
    notifySuccess(`发布成功: ${data.skill_id || ''}@${data.version || ''}`)
  } catch (err) {
    setOutput(`发布失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
    notifyError(err)
  } finally {
    publishingPackageId.value = ''
  }
}
async function previewPackage(p: JsonMap) {
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(packageId(p))}/preview`, { headers: headers(false) }))
    previewPackageData.value = data
    previewSelectedFile.value = ''
    previewFileContent.value = ''
    previewOpen.value = true
    const files = filesOf(data)
    const first = files.find((file) => file.endsWith('SKILL.md')) || files[0] || ''
    if (first) {
      await selectPreviewFile(first)
    }
  } catch (err) {
    notifyError(err)
  }
}
async function selectPreviewFile(path: string) {
  const id = packageId(previewPackageData.value || {})
  if (!id || !path) return
  previewSelectedFile.value = path
  previewFileLoading.value = true
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(id)}/preview-file?path=${encodeURIComponent(path)}`, { headers: headers(false) }))
    previewFileContent.value = String(data.content || '')
  } catch (err) {
    previewFileContent.value = `读取失败: ${err instanceof Error ? err.message : String(err)}`
  } finally {
    previewFileLoading.value = false
  }
}
async function rejectPackage(p: JsonMap) {
  try {
    const reason = await promptDialog(`拒绝 Skill 包 #${packageId(p)}`, '拒绝原因', rejectReason.value)
    if (reason === null) return
    rejectReason.value = reason.trim()
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(packageId(p))}/reject`, { method: 'POST', headers: headers(true), body: JSON.stringify({ reason: rejectReason.value }) }))
    setOutput(JSON.stringify(data, null, 2), 'ok')
    await loadPackages()
    notifySuccess(`已拒绝 Package #${packageId(p)}`)
  } catch (err) {
    setOutput(`拒绝失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  }
}
async function savePackagePermissions(p: JsonMap) {
  try {
    const data = await readJson<JsonMap>(await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(packageId(p))}/permissions`, { method: 'PATCH', headers: headers(true), body: JSON.stringify({ permissions: collectPermissions(p) }) }))
    setOutput(JSON.stringify(data, null, 2), 'ok')
    await syncSkills()
    notifySuccess(`权限已保存: Package #${packageId(p)}`)
  } catch (err) {
    setOutput(`保存权限失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  }
}
async function deletePackage(p: JsonMap) {
  if (!(await confirmDialog(`删除 Package #${packageId(p)}？`, { title: '删除包', danger: true }))) return
  try {
    const res = await fetch(`/platform/frontend/skills/packages/${encodeURIComponent(packageId(p))}`, { method: 'DELETE', headers: headers(false) })
    if (!res.ok) throw new Error(await res.text())
    setOutput(`Package #${packageId(p)} 已删除`, 'ok')
    await Promise.all([loadPackages(), loadSkills()])
    notifySuccess(`已删除 Package #${packageId(p)}`)
  } catch (err) {
    setOutput(`删除失败: ${err instanceof Error ? err.message : String(err)}`, 'err')
  }
}
onMounted(() => {
  pkgDomain.value = ''
  loadDomains()
  loadSkills()
  loadPackages()
})
</script>

<template>
  <div class="skills-workspace">
    <div class="filter-pane">
      <h3>来源</h3>
      <div class="filter-item" :class="{ active: !activeSource && !activeStatus }" @click="activeSource = ''; activeStatus = ''">
        <span>全部</span><span class="filter-count">{{ skills.length }}</span>
      </div>
      <div v-for="[src, c] in sourceCounts" :key="src" class="filter-item" :class="{ active: activeSource === src }" @click="activeSource = src; activeStatus = ''">
        <span>{{ labelSource(src) }}</span><span class="filter-count">{{ c }}</span>
      </div>
      <h3 style="margin-top:16px">状态</h3>
      <div class="filter-item" :class="{ active: activeStatus === 'enabled' }" @click="activeStatus = 'enabled'; activeSource = ''">
        <span>已启用</span><span class="filter-count">{{ enabledCount }}</span>
      </div>
      <div class="filter-item" :class="{ active: activeStatus === 'disabled' }" @click="activeStatus = 'disabled'; activeSource = ''">
        <span>已禁用</span><span class="filter-count">{{ disabledCount }}</span>
      </div>
    </div>

    <div class="skills-area">
      <div class="skills-toolbar">
        <input v-model="keyword" placeholder="搜索 Skill 名称或描述…" />
        <span class="skill-count">{{ visibleSkills.length }} 个 Skills</span>
        <button class="btn btn-primary btn-sm" @click="openCreateSkill">+ 创建 Skill</button>
        <button class="btn btn-ghost btn-sm" @click="syncSkills">刷新</button>
      </div>

      <div class="skills-grid">
        <p v-if="error" class="empty">加载失败: {{ error }}</p>
        <div v-else-if="!visibleSkills.length" class="empty">暂无 Skills</div>
        <div v-for="s in visibleSkills" :key="s.skill_id as string" class="skill-card" :class="{ disabled: !isEnabled(s) }" @click="openSkillDetail(s)">
          <div class="skill-head">
            <div style="display:flex;gap:10px;align-items:flex-start">
              <div class="skill-icon">⚡</div>
              <div>
                <div class="skill-name">{{ s.display_name || s.name || s.skill_id }}</div>
                <div class="skill-id">{{ s.skill_id }}</div>
              </div>
            </div>
            <div class="skill-toggle" @click.stop>
              <label class="toggle" :title="isEnabled(s) ? '点击禁用' : '点击启用'">
                <input type="checkbox" :checked="isEnabled(s)" @click.stop @change.stop="toggleSkill(s, ($event.target as HTMLInputElement).checked)" />
                <span class="toggle-slider"></span>
              </label>
            </div>
          </div>
          <div v-if="s.description" class="skill-desc">{{ s.description }}</div>
          <div class="skill-meta">
            <span class="skill-tag" :class="sourceOf(s) === 'global' || sourceOf(s) === 'platform' ? 'platform' : 'domain'">{{ labelSource(sourceOf(s)) }}</span>
            <span class="skill-tag" :class="isEnabled(s) ? 'enabled' : 'disabled'">{{ isEnabled(s) ? '已启用' : '已禁用' }}</span>
            <span class="skill-tag">{{ s.scope || 'agent' }}</span>
            <span v-if="s.version" class="skill-tag">{{ versionText(s.version) }}</span>
          </div>
          <div class="skill-actions">
            <button v-if="canTestSkill(s)" class="btn btn-ghost btn-sm" :disabled="smokeTestingSkillId === s.skill_id" @click.stop="testSkill(s)">{{ smokeTestingSkillId === s.skill_id ? '测试中…' : 'Smoke Test' }}</button>
            <button class="btn btn-ghost btn-sm" @click.stop="openEditSkill(s)">编辑</button>
            <button class="btn btn-danger btn-sm" @click.stop="deleteSkill(s)">删除</button>
          </div>
        </div>
      </div>

      <section class="package-panel">
        <div class="package-head">
          <div>
            <div class="package-title">Package 审核列表 <span>{{ packages.length }} 个包</span></div>
            <div class="package-desc">这里是上传后的 Skill 包队列。校验通过后需要发布，才会成为可绑定、可使用的 Skill。</div>
          </div>
          <div class="package-tools">
            <select v-model="pkgStatus" @change="loadPackages">
              <option value="validated">待发布</option>
              <option value="">全部记录</option>
              <option value="published">已发布</option>
              <option value="rejected">已拒绝</option>
            </select>
            <select v-model="pkgDomain" @change="loadPackages">
              <option value="">全部业务域</option>
              <option v-for="d in domainOptions" :key="String(d.domain)" :value="d.domain">{{ d.display_name || d.domain }}</option>
            </select>
            <button class="btn btn-ghost btn-sm" @click="loadPackages">刷新包</button>
          </div>
        </div>
        <div v-if="packageError" class="package-load-error">加载上传包失败：{{ packageError }}</div>
        <div class="package-body">
          <table class="package-table">
            <thead>
              <tr><th>Skill 包</th><th>状态</th><th>发布权限</th><th>校验结果</th><th>操作</th></tr>
            </thead>
            <tbody>
              <tr v-if="!packageError && !packages.length"><td colspan="5" class="empty">暂无审核记录。点击「创建 Skill」并选择「上传 Skill 包」后，记录会显示在这里。</td></tr>
              <tr v-for="p in packages" :key="packageId(p)">
                <td>
                  <div class="pkg-id">#{{ packageId(p) }} {{ p.skill_id }}@{{ p.version }}</div>
                  <div class="pkg-sub">{{ p.domain }} · {{ p.source || 'uploaded' }} · {{ String(p.created_at || '').slice(0, 19) }}</div>
                  <div v-if="packageWillOverwrite(p)" class="overwrite-note">发布后会覆盖当前已发布 Skill</div>
                </td>
                <td><span class="pkg-status" :class="statusClass(p.status)">{{ statusText(p.status) }}</span></td>
                <td>
                  <div class="pkg-perms" :class="{ custom: draftFor(p).mode === 'custom' }">
                    <select v-model="draftFor(p).mode">
                      <option value="default">source默认</option>
                      <option value="sandbox">沙箱</option>
                      <option value="custom">指定权限</option>
                    </select>
                    <div v-if="draftFor(p).mode === 'custom'" class="pkg-perm-checks">
                      <label v-for="perm in PACKAGE_PERMISSIONS" :key="perm">
                        <input type="checkbox" :checked="draftFor(p).checks.has(perm)" @change="togglePermCheck(p, perm)" /> {{ perm }}
                      </label>
                    </div>
                    <div class="pkg-sub">{{ permText(p.granted_permissions) }}</div>
                  </div>
                </td>
                <td>
                  <div v-if="(p.validation_errors as string[] | undefined)?.length" class="pkg-errors">{{ (p.validation_errors as string[]).join('\n') }}</div>
                  <span v-else class="pkg-pass">通过</span>
                </td>
                <td>
                  <div class="pkg-actions">
                    <button class="btn btn-ghost btn-sm" @click="previewPackage(p)">预览</button>
                    <template v-if="p.status === 'validated'">
                      <button class="btn btn-primary btn-sm" :disabled="Boolean(publishingPackageId)" @click="publishPackage(p)">{{ publishingPackageId === packageId(p) ? '发布中…' : '发布' }}</button>
                      <button class="btn btn-danger btn-sm" @click="rejectPackage(p)">拒绝</button>
                    </template>
                    <button class="btn btn-ghost btn-sm" @click="savePackagePermissions(p)">保存权限</button>
                    <button class="btn btn-danger btn-sm" @click="deletePackage(p)">删除</button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </div>

    <div v-if="detailOpen" class="drawer-backdrop" @click.self="detailOpen = false">
      <aside class="skill-drawer">
        <div class="drawer-head">
          <div>
            <div class="drawer-title">{{ drawerEditing ? '编辑 Skill' : (skillDetail?.display_name || skillDetail?.name || skillDetail?.skill_id) }}</div>
            <div class="drawer-sub">{{ skillDetail?.skill_id }}</div>
          </div>
          <div class="drawer-head-actions">
            <button v-if="!drawerEditing" class="btn btn-ghost btn-sm" @click="openEditSkill(skillDetail || {})">编辑</button>
            <button class="btn btn-ghost btn-sm" @click="detailOpen = false">关闭</button>
          </div>
        </div>
        <div class="drawer-body">
          <div v-if="skillDetailLoading" class="empty">加载中...</div>
          <template v-else>
            <div class="drawer-meta">
              <span class="skill-tag" :class="sourceOf(skillDetail || {}) === 'global' || sourceOf(skillDetail || {}) === 'platform' ? 'platform' : 'domain'">{{ labelSource(sourceOf(skillDetail || {})) }}</span>
              <span class="skill-tag" :class="isEnabled(skillDetail || {}) ? 'enabled' : 'disabled'">{{ isEnabled(skillDetail || {}) ? '已启用' : '已禁用' }}</span>
              <span class="skill-tag">{{ skillDetail?.scope || 'agent' }}</span>
              <span v-if="skillDetail?.version" class="skill-tag">{{ versionText(skillDetail.version) }}</span>
            </div>
            <section v-if="drawerEditing" class="drawer-section drawer-edit-form">
              <div class="drawer-section-title">基础配置</div>
              <div class="drawer-form-grid">
                <div class="field"><label>Skill ID</label><input v-model="skillForm.skill_id" disabled /></div>
                <div class="field"><label>名称</label><input v-model="skillForm.name" /></div>
                <div class="field"><label>业务域</label><select v-model="skillForm.source"><option v-for="d in domainOptions" :key="String(d.domain)" :value="d.domain">{{ d.display_name || d.domain }}</option></select></div>
                <div class="field"><label>作用域</label><select v-model="skillForm.scope"><option value="platform">platform</option><option value="agent">agent</option></select></div>
              </div>
              <label class="inline-check"><input v-model="skillForm.enabled" type="checkbox" /> 启用 Skill</label>
              <div class="field"><label>描述</label><textarea v-model="skillForm.description" rows="3" /></div>
            </section>
            <section v-else class="drawer-section">
              <div class="drawer-section-title">说明</div>
              <p class="drawer-text">{{ skillDetail?.description || '暂无描述' }}</p>
            </section>
            <section class="drawer-section">
              <div class="drawer-section-title">解析摘要</div>
              <div class="analysis-grid">
                <div class="analysis-card"><span>文件</span><strong>{{ countOf(skillDetail, 'file_count') }}</strong></div>
                <div class="analysis-card"><span>脚本</span><strong>{{ countOf(skillDetail, 'script_count') }}</strong></div>
                <div class="analysis-card"><span>Examples</span><strong>{{ countOf(skillDetail, 'example_count') }}</strong></div>
                <div class="analysis-card"><span>Docs</span><strong>{{ countOf(skillDetail, 'doc_count') }}</strong></div>
                <div class="analysis-card"><span>配置</span><strong>{{ countOf(skillDetail, 'config_count') }}</strong></div>
                <div class="analysis-card"><span>SKILL.md</span><strong>{{ boolText(analysisOf(skillDetail).has_skill_md) }}</strong></div>
              </div>
              <div class="analysis-list" v-if="listOf(skillDetail, 'entrypoints').length"><span>入口文件</span><code>{{ listOf(skillDetail, 'entrypoints').join(', ') }}</code></div>
              <div class="analysis-list" v-if="listOf(skillDetail, 'scripts').length"><span>脚本</span><code>{{ listOf(skillDetail, 'scripts').join(', ') }}</code></div>
              <div class="analysis-list" v-if="listOf(skillDetail, 'examples').length"><span>Examples</span><code>{{ listOf(skillDetail, 'examples').join(', ') }}</code></div>
            </section>
            <section class="drawer-section">
              <div class="drawer-section-title">{{ drawerEditing ? '文件编辑' : '文件预览' }} · {{ filesOf(skillDetail).length }}</div>
              <div class="github-preview">
                <div class="github-file-list">
                  <button v-for="file in filesOf(skillDetail)" :key="file" class="github-file-row" :class="{ active: skillSelectedFile === file }" :style="{ paddingLeft: `${10 + fileDepth(file) * 14}px` }" @click="selectSkillFile(file)">
                    <span class="github-file-icon">{{ fileIcon(file) }}</span>
                    <span class="github-file-name">{{ fileName(file) }}</span>
                    <span v-if="fileDir(file)" class="github-file-dir">{{ fileDir(file) }}</span>
                  </button>
                  <div v-if="!filesOf(skillDetail).length" class="tools-empty">暂无文件。</div>
                </div>
                <div class="github-file-view">
                  <div class="github-file-head">
                    <span>{{ skillSelectedFile || '未选择文件' }}</span>
                    <span v-if="skillFileLoading">读取中...</span>
                    <button v-else-if="drawerEditing && skillSelectedFile" class="mini-dark-btn" @click="saveSelectedSkillFile()">保存文件</button>
                  </div>
                  <textarea v-if="drawerEditing" v-model="skillFileContent" class="github-code github-editor" placeholder="选择左侧文件后编辑内容。"></textarea>
                  <pre v-else class="github-code">{{ skillFileContent || '选择左侧文件查看内容' }}</pre>
                </div>
              </div>
            </section>
            <section v-if="drawerEditing" class="drawer-section">
              <div class="drawer-section-title">重新上传文件包</div>
              <div class="upload-form-grid">
                <div class="field"><label>版本</label><input v-model="packageVersion" placeholder="v1" /></div>
                <div class="field"><label>来源备注</label><input v-model="packageSourceNote" placeholder="变更说明" /></div>
              </div>
              <div class="upload-mode">
                <button class="mode-btn" :class="{ active: uploadMode === 'zip' }" @click="uploadMode = 'zip'; packageFile = null; packageFiles = []; packageFileName = ''">ZIP 包</button>
                <button class="mode-btn" :class="{ active: uploadMode === 'folder' }" @click="uploadMode = 'folder'; packageFile = null; packageFiles = []; packageFileName = ''">文件夹</button>
              </div>
              <div v-if="uploadMode === 'zip'" class="field"><label>Skill 包 (.zip)</label>
                <label class="file-pick">
                  <input type="file" accept=".zip,application/zip" @change="onPickFile" />
                  <span>{{ packageFileName || '选择新版 zip 包，上传后进入待发布队列' }}</span>
                </label>
              </div>
              <div v-else class="field"><label>Skill 文件夹</label>
                <label class="file-pick">
                  <input type="file" webkitdirectory directory multiple @change="onPickFolder" />
                  <span>{{ packageFileName || '选择包含 SKILL.md 的新版文件夹' }}</span>
                </label>
              </div>
              <pre v-if="pkgOutput" class="package-output" :class="pkgOutput.kind">{{ pkgOutput.text }}</pre>
              <button class="btn btn-primary btn-sm drawer-upload-btn" :disabled="uploading || (uploadMode === 'zip' ? !packageFile : !packageFiles.length)" @click="uploadPackage">{{ uploading ? '上传校验中…' : '上传并校验新版包' }}</button>
            </section>
            <section class="drawer-section">
              <div class="drawer-section-title">高级信息</div>
              <div class="drawer-kv"><span>存储</span><code>{{ skillStorageLabel(skillDetail) }}</code></div>
              <div class="drawer-kv"><span>命名空间</span><code>{{ skillLogicalLocation(skillDetail) }}</code></div>
              <div class="drawer-kv"><span>文件</span><code>{{ countOf(skillDetail, 'file_count') }} 个</code></div>
            </section>
            <div class="drawer-actions">
              <span v-if="!canTestSkill(skillDetail)" class="drawer-action-note">文档型 Skill，无脚本可测试</span>
              <button v-if="canTestSkill(skillDetail) && !drawerEditing" class="btn btn-ghost" :disabled="smokeTestingSkillId === skillDetail?.skill_id" @click="testSkill(skillDetail || {})">{{ smokeTestingSkillId === skillDetail?.skill_id ? '测试中…' : 'Smoke Test' }}</button>
              <button v-if="drawerEditing" class="btn btn-ghost" @click="drawerEditing = false">取消编辑</button>
              <button v-if="drawerEditing" class="btn btn-primary" @click="saveDrawerSkill">保存</button>
              <button class="btn btn-danger" @click="deleteSkill(skillDetail || {})">删除</button>
            </div>
            <section v-if="skillTestResult && skillTestResult.skill_id === skillDetail?.skill_id" class="drawer-section smoke-result" :class="skillTestResult.ok ? 'ok' : 'failed'">
              <div class="drawer-section-title">Docker Smoke Test <span>{{ skillTestResult.ok ? '通过' : '未通过' }}</span></div>
              <div class="drawer-kv"><span>命令</span><code>{{ skillTestResult.command || '未声明 smoke_test.command' }}</code></div>
              <div class="drawer-kv"><span>退出码</span><code>{{ skillTestResult.exit_code ?? '—' }}</code></div>
              <div class="drawer-kv"><span>耗时</span><code>{{ skillTestResult.duration_ms != null ? skillTestResult.duration_ms + ' ms' : '—' }}</code></div>
              <pre v-if="skillTestResult.stdout" class="package-output ok">{{ skillTestResult.stdout }}</pre>
              <pre v-if="skillTestResult.stderr" class="package-output err">{{ skillTestResult.stderr }}</pre>
              <div v-if="skillTestResult.error" class="drawer-action-note">{{ skillTestResult.error }}</div>
            </section>
          </template>
        </div>
      </aside>
    </div>

    <div v-if="skillEditorOpen" class="skill-modal" @click.self="skillEditorOpen = false">
      <div class="skill-dialog">
        <div class="skill-dialog-head"><div class="skill-dialog-title">{{ editingSkillId ? '编辑 Skill' : '创建 Skill' }}</div><button class="btn btn-ghost btn-sm" @click="skillEditorOpen = false">关闭</button></div>
        <div class="skill-dialog-body">
          <div class="field"><label>Skill ID</label><input v-model="skillForm.skill_id" :disabled="Boolean(editingSkillId)" placeholder="company-doc-skill" /></div>
          <div class="field"><label>名称</label><input v-model="skillForm.name" placeholder="默认同 Skill ID 或 SKILL.md name" /></div>
          <div class="field"><label>业务域</label><select v-model="skillForm.source"><option v-for="d in domainOptions" :key="String(d.domain)" :value="d.domain">{{ d.display_name || d.domain }}</option></select></div>
          <div class="field"><label>描述</label><textarea v-model="skillForm.description" rows="3" placeholder="手写创建时必填；上传包时不填则使用 SKILL.md description" /></div>
          <div v-if="!editingSkillId" class="upload-mode">
            <button class="mode-btn" :class="{ active: skillCreateMode === 'manual' }" @click="skillCreateMode = 'manual'">手写创建</button>
            <button class="mode-btn" :class="{ active: skillCreateMode === 'package' }" @click="skillCreateMode = 'package'">上传 Skill 包</button>
          </div>
          <template v-if="editingSkillId || skillCreateMode === 'manual'">
            <template v-if="!editingSkillId">
              <div class="field"><label>正文 Markdown</label><textarea v-model="skillForm.content" rows="8" placeholder="写给智能体看的使用说明、步骤、约束和示例。" /></div>
              <div class="field"><label>附加脚本</label>
                <label class="file-pick">
                  <input type="file" multiple accept=".py,.js,.mjs,.ts,.sh,.ps1,.bat,text/*" @change="onPickSkillScripts" />
                  <span>{{ skillScriptFiles.length ? `${skillScriptFiles.length} 个脚本文件` : '可选：选择脚本文件，保存到 scripts/ 目录' }}</span>
                </label>
                <div v-if="skillScriptFiles.length" class="script-file-list">
                  <span v-for="file in skillScriptFiles" :key="file.name">{{ file.name }}</span>
                </div>
              </div>
            </template>
          </template>
          <template v-else>
            <div class="upload-form-grid">
              <div class="field"><label>版本</label><input v-model="packageVersion" placeholder="v1" /></div>
              <div class="field"><label>来源备注</label><input v-model="packageSourceNote" placeholder="上传人、来源系统、变更说明等" /></div>
            </div>
            <div v-if="uploadWillOverwrite" class="overwrite-alert">当前 Skill ID 已存在；上传后需要点击「发布」才会替换当前已发布 Skill。</div>
            <div class="upload-mode">
              <button class="mode-btn" :class="{ active: uploadMode === 'zip' }" @click="uploadMode = 'zip'; packageFile = null; packageFiles = []; packageFileName = ''">ZIP 包</button>
              <button class="mode-btn" :class="{ active: uploadMode === 'folder' }" @click="uploadMode = 'folder'; packageFile = null; packageFiles = []; packageFileName = ''">文件夹</button>
            </div>
            <div v-if="uploadMode === 'zip'" class="field"><label>Skill 包 (.zip)</label>
              <label class="file-pick">
                <input type="file" accept=".zip,application/zip" @change="onPickFile" />
                <span>{{ packageFileName || '点击选择 zip 包…' }}</span>
              </label>
            </div>
            <div v-else class="field"><label>Skill 文件夹</label>
              <label class="file-pick">
                <input type="file" webkitdirectory directory multiple @change="onPickFolder" />
                <span>{{ packageFileName || '点击选择包含 SKILL.md 的文件夹…' }}</span>
              </label>
              <div class="upload-hint">文件夹根目录必须包含 SKILL.md；上传后后端会打包成标准 Skill Package 再校验。</div>
            </div>
            <pre v-if="pkgOutput" class="package-output" :class="pkgOutput.kind">{{ pkgOutput.text }}</pre>
            <p class="upload-hint">上传后会自动校验；校验通过的包会进入下方「Package 审核列表」。</p>
          </template>
        </div>
        <div class="skill-dialog-actions">
          <button class="btn btn-ghost" @click="skillEditorOpen = false">取消</button>
          <button v-if="editingSkillId || skillCreateMode === 'manual'" class="btn btn-primary" @click="saveSkill">保存</button>
          <button v-else class="btn btn-primary" :disabled="uploading || (uploadMode === 'zip' ? !packageFile : !packageFiles.length)" @click="uploadPackage">{{ uploading ? '上传校验中…' : '上传并校验' }}</button>
        </div>
      </div>
    </div>

    <div v-if="previewOpen" class="skill-modal" @click.self="previewOpen = false">
      <div class="skill-dialog preview-dialog">
        <div class="skill-dialog-head">
          <div>
            <div class="skill-dialog-title">Skill 包预览</div>
            <div class="pkg-sub">{{ previewPackageData?.skill_id }}@{{ previewPackageData?.version }} · {{ previewPackageData?.domain }}</div>
          </div>
          <button class="btn btn-ghost btn-sm" @click="previewOpen = false">关闭</button>
        </div>
        <div class="preview-body">
          <div v-if="previewPackageData?.preview_error" class="package-output err">{{ previewPackageData.preview_error }}</div>
          <section class="preview-section">
            <div class="preview-title">解析摘要</div>
            <div class="analysis-grid">
              <div class="analysis-card"><span>文件</span><strong>{{ countOf(previewPackageData, 'file_count') }}</strong></div>
              <div class="analysis-card"><span>脚本</span><strong>{{ countOf(previewPackageData, 'script_count') }}</strong></div>
              <div class="analysis-card"><span>Examples</span><strong>{{ countOf(previewPackageData, 'example_count') }}</strong></div>
              <div class="analysis-card"><span>Docs</span><strong>{{ countOf(previewPackageData, 'doc_count') }}</strong></div>
              <div class="analysis-card"><span>配置</span><strong>{{ countOf(previewPackageData, 'config_count') }}</strong></div>
              <div class="analysis-card"><span>SKILL.md</span><strong>{{ boolText(analysisOf(previewPackageData).has_skill_md) }}</strong></div>
            </div>
            <div class="analysis-list" v-if="listOf(previewPackageData, 'entrypoints').length"><span>入口文件</span><code>{{ listOf(previewPackageData, 'entrypoints').join(', ') }}</code></div>
            <div class="analysis-list" v-if="listOf(previewPackageData, 'scripts').length"><span>脚本</span><code>{{ listOf(previewPackageData, 'scripts').join(', ') }}</code></div>
            <div class="analysis-list" v-if="listOf(previewPackageData, 'examples').length"><span>Examples</span><code>{{ listOf(previewPackageData, 'examples').join(', ') }}</code></div>
          </section>
          <section class="preview-section">
            <div class="preview-title">文件浏览 · {{ filesOf(previewPackageData).length }}</div>
            <div class="github-preview package">
              <div class="github-file-list">
                <button v-for="file in filesOf(previewPackageData)" :key="file" class="github-file-row" :class="{ active: previewSelectedFile === file }" :style="{ paddingLeft: `${10 + fileDepth(file) * 14}px` }" @click="selectPreviewFile(file)">
                  <span class="github-file-icon">{{ fileIcon(file) }}</span>
                  <span class="github-file-name">{{ fileName(file) }}</span>
                  <span v-if="fileDir(file)" class="github-file-dir">{{ fileDir(file) }}</span>
                </button>
                <div v-if="!filesOf(previewPackageData).length" class="tools-empty">暂无文件。</div>
              </div>
              <div class="github-file-view">
                <div class="github-file-head">
                  <span>{{ previewSelectedFile || '未选择文件' }}</span>
                  <span v-if="previewFileLoading">读取中...</span>
                </div>
                <pre class="github-code">{{ previewFileContent || '选择左侧文件查看内容' }}</pre>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>

  </div>
</template>

<style scoped>
.skills-workspace { flex: 1; display: grid; grid-template-columns: 220px 1fr; overflow: hidden; }
.filter-pane { border-right: 1px solid var(--border); background: var(--panel); display: flex; flex-direction: column; overflow-y: auto; padding: 14px; }
.filter-pane h3 { font-size: 12px; font-weight: 700; color: var(--muted); text-transform: uppercase; letter-spacing: .05em; margin-bottom: 10px; }
.filter-item { padding: 8px 10px; border-radius: 8px; font-size: 12px; cursor: pointer; margin-bottom: 2px; transition: .12s; display: flex; align-items: center; justify-content: space-between; }
.filter-item:hover { background: #f1f5f9; }
.filter-item.active { background: #dbeafe; color: var(--blue-dim); font-weight: 600; }
.filter-count { font-size: 11px; color: var(--muted); background: #f1f5f9; padding: 1px 6px; border-radius: 99px; }
.filter-item.active .filter-count { background: #bfdbfe; color: var(--blue-dim); }

.skills-area { display: flex; flex-direction: column; overflow-y: auto; }
.skills-toolbar { padding: 12px 16px; border-bottom: 1px solid var(--border); background: #f8fafc; display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.skills-toolbar input { flex: 1; height: 34px; }
.skill-count { font-size: 12px; color: var(--muted); white-space: nowrap; }

.package-panel { margin: 0 16px 16px; border: 1px solid var(--border); border-radius: 14px; background: var(--panel); display: flex; flex-direction: column; flex-shrink: 0; overflow: hidden; }
.package-head { padding: 14px 16px; display: flex; align-items: flex-start; gap: 14px; border-bottom: 1px solid var(--border); background: #fbfdff; }
.package-title { font-size: 13px; font-weight: 700; flex: 1; display: flex; align-items: center; gap: 8px; }
.package-title span { font-size: 11px; color: var(--muted); font-weight: 600; }
.package-desc { margin-top: 4px; font-size: 12px; color: var(--muted); line-height: 1.5; }
.package-tools { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.package-tools input, .package-tools select { height: 32px; font-size: 12px; }
.package-load-error { margin: 12px 16px 0; padding: 10px 12px; border-radius: 10px; border: 1px solid #fecaca; background: #fef2f2; color: #991b1b; font-size: 12px; }
.package-upload { display: grid; grid-template-columns: 170px minmax(180px, 1fr) auto; gap: 8px; padding: 12px 16px; border-bottom: 1px solid var(--border); background: #f8fafc; align-items: center; }
.package-upload input { height: 32px; font-size: 12px; }
.file-pick { display: flex; align-items: center; min-width: 0; border: 1px solid var(--border); border-radius: 7px; background: #fff; height: 32px; padding: 0 10px; font-size: 12px; color: var(--muted); cursor: pointer; overflow: hidden; }
.file-pick input { display: none; }
.file-pick span { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.package-output { margin: 0 16px 12px; padding: 9px 11px; border-radius: 8px; font-size: 12px; font-family: ui-monospace, Menlo, Consolas, monospace; white-space: pre-wrap; max-height: 150px; overflow: auto; border: 1px solid var(--border); background: #f8fafc; color: var(--text); }
.package-output.ok { background: #f0fdf4; border-color: #bbf7d0; color: #166534; }
.package-output.err { background: #fef2f2; border-color: #fecaca; color: #991b1b; }
.package-body { overflow: auto; padding: 0 16px 14px; max-height: 42vh; }
.package-table { width: 100%; border-collapse: collapse; font-size: 12px; min-width: 860px; }
.package-table th { text-align: left; padding: 9px 10px; color: var(--muted); font-size: 11px; font-weight: 700; border-bottom: 1px solid var(--border); background: #fff; position: sticky; top: 0; z-index: 1; }
.package-table td { padding: 10px; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
.pkg-id { font-family: ui-monospace, Menlo, Consolas, monospace; font-weight: 700; color: var(--text); }
.pkg-sub { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 11px; color: var(--muted); margin-top: 2px; word-break: break-all; }
.pkg-status { display: inline-flex; align-items: center; border-radius: 99px; padding: 2px 8px; font-size: 10px; font-weight: 700; }
.pkg-status.validated { background: #fef3c7; color: #92400e; }
.pkg-status.published { background: #dcfce7; color: #166534; }
.pkg-status.rejected { background: #fee2e2; color: #991b1b; }
.pkg-status.other { background: #f1f5f9; color: #475569; }
.overwrite-note { display: inline-flex; margin-top: 5px; padding: 2px 7px; border-radius: 999px; background: #fff7ed; color: #9a3412; border: 1px solid #fed7aa; font-size: 10px; font-weight: 800; }
.pkg-errors { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 11px; color: var(--red); white-space: pre-wrap; max-width: 280px; }
.pkg-pass { color: var(--green); font-size: 12px; }
.pkg-actions { display: flex; gap: 6px; flex-wrap: wrap; }
.pkg-perms { display: grid; grid-template-columns: 1fr; gap: 5px; min-width: 150px; }
.pkg-perms select { height: 30px; font-size: 12px; }
.pkg-perm-checks { display: flex; gap: 6px; flex-wrap: wrap; color: var(--muted); font-size: 11px; }
.pkg-perm-checks label { display: inline-flex; align-items: center; gap: 3px; }

.skills-grid { padding: 16px; display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 14px; align-content: start; }
.skill-card { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 16px; display: flex; flex-direction: column; gap: 10px; transition: .15s; cursor: pointer; }
.skill-card:hover { border-color: var(--blue); box-shadow: 0 0 0 3px #dbeafe; }
.skill-card.disabled { opacity: .6; }
.skill-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.skill-icon { width: 36px; height: 36px; border-radius: 9px; background: linear-gradient(135deg, #dbeafe, #e0e7ff); display: flex; align-items: center; justify-content: center; font-size: 18px; flex-shrink: 0; }
.skill-name { font-size: 13px; font-weight: 700; line-height: 1.3; }
.skill-id { font-size: 11px; color: var(--muted); font-family: ui-monospace, Menlo, Consolas, monospace; }
.skill-toggle { flex-shrink: 0; }
.toggle { position: relative; display: inline-block; width: 36px; height: 20px; }
.toggle input { opacity: 0; width: 0; height: 0; }
.toggle-slider { position: absolute; cursor: pointer; inset: 0; background: #cbd5e1; border-radius: 99px; transition: .2s; }
.toggle-slider::before { content: ""; position: absolute; width: 14px; height: 14px; left: 3px; top: 3px; background: #fff; border-radius: 50%; transition: .2s; }
.toggle input:checked + .toggle-slider { background: var(--blue); }
.toggle input:checked + .toggle-slider::before { transform: translateX(16px); }
.skill-desc { font-size: 12px; color: var(--muted); line-height: 1.5; }
.skill-meta { display: flex; gap: 6px; flex-wrap: wrap; }
.skill-tag { font-size: 10px; font-weight: 600; padding: 2px 7px; border-radius: 99px; background: #f1f5f9; color: #475569; }
.skill-tag.platform { background: #dbeafe; color: #1d4ed8; }
.skill-tag.domain { background: #e0f2fe; color: #0369a1; }
.skill-tag.enabled { background: #dcfce7; color: #166534; }
.skill-tag.disabled { background: #fee2e2; color: #dc2626; }
.skill-actions { display: flex; gap: 6px; }
.empty { padding: 30px; text-align: center; color: var(--muted); font-size: 12px; grid-column: 1 / -1; }

.drawer-backdrop { position: fixed; inset: 0; background: rgba(15, 23, 42, .28); z-index: 980; display: flex; justify-content: flex-end; }
.skill-drawer { width: min(720px, 92vw); height: 100vh; background: #fff; box-shadow: -18px 0 44px rgba(15, 23, 42, .18); display: flex; flex-direction: column; }
.drawer-head { padding: 18px 20px; border-bottom: 1px solid var(--border); display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.drawer-head-actions { display: flex; gap: 8px; align-items: center; }
.drawer-title { font-size: 18px; font-weight: 800; color: var(--text); }
.drawer-sub { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; color: var(--muted); margin-top: 3px; }
.drawer-body { padding: 18px 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; }
.drawer-meta { display: flex; gap: 6px; flex-wrap: wrap; }
.drawer-section { display: flex; flex-direction: column; gap: 8px; }
.drawer-section-title { font-size: 12px; font-weight: 800; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }
.drawer-text { margin: 0; color: var(--text); font-size: 13px; line-height: 1.6; }
.drawer-edit-form { padding: 12px; border: 1px solid var(--border); border-radius: 12px; background: #f8fafc; }
.drawer-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.drawer-edit-form .field, .drawer-section .field { display: flex; flex-direction: column; gap: 5px; }
.drawer-edit-form .field label, .drawer-section .field label { font-size: 12px; font-weight: 700; color: var(--muted); }
.drawer-edit-form input, .drawer-edit-form select, .drawer-edit-form textarea, .drawer-section input, .drawer-section select { min-height: 34px; }
.drawer-upload-btn { align-self: flex-start; }
.analysis-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.analysis-card { border: 1px solid var(--border); border-radius: 10px; padding: 9px 10px; background: #f8fafc; display: flex; flex-direction: column; gap: 3px; }
.analysis-card span { font-size: 11px; color: var(--muted); font-weight: 700; }
.analysis-card strong { font-size: 16px; color: var(--text); }
.analysis-list { display: grid; grid-template-columns: 70px minmax(0, 1fr); gap: 8px; align-items: start; font-size: 12px; }
.analysis-list span { color: var(--muted); font-weight: 800; }
.analysis-list code { color: var(--text); overflow-wrap: anywhere; font-family: ui-monospace, Menlo, Consolas, monospace; }
.drawer-kv { display: grid; grid-template-columns: 90px 1fr; gap: 8px; font-size: 12px; align-items: start; }
.drawer-kv span { color: var(--muted); font-weight: 700; }
.drawer-kv code { color: var(--text); overflow-wrap: anywhere; }
.drawer-actions { position: sticky; bottom: -18px; margin: 2px -20px -18px; padding: 14px 20px; background: #fff; border-top: 1px solid var(--border); display: flex; justify-content: flex-end; gap: 8px; }
.drawer-action-note { margin-right: auto; align-self: center; color: var(--muted); font-size: 12px; }
.smoke-result { border-left: 3px solid var(--green); padding-left: 12px; }
.smoke-result.failed { border-left-color: var(--red); }
.smoke-result .drawer-section-title span { margin-left: 8px; font-size: 12px; color: var(--green); }
.smoke-result.failed .drawer-section-title span { color: var(--red); }
.smoke-result .package-output { margin: 0; max-height: 220px; }

@media (max-width: 980px) {
  .skills-workspace { grid-template-columns: 1fr; overflow: visible; }
  .filter-pane { border-right: 0; border-bottom: 1px solid var(--border); }
  .skills-area { overflow: visible; }
  .package-panel { max-height: none; }
}

/* 上传 Skill 包弹窗 */
.skill-modal { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; align-items: center; justify-content: center; z-index: 1000; padding: 28px; }
.skill-dialog { background: #fff; border-radius: 16px; width: 520px; max-width: 96vw; max-height: 90vh; display: flex; flex-direction: column; box-shadow: 0 18px 44px rgba(15, 23, 42, .14); overflow: hidden; }
.preview-dialog { width: 860px; }
.skill-dialog-head { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--border); }
.skill-dialog-title { font-size: 16px; font-weight: 700; flex: 1; }
.skill-dialog-body { padding: 18px 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; }
.skill-dialog-body .field { display: flex; flex-direction: column; gap: 5px; }
.skill-dialog-body .field > label { font-size: 12px; font-weight: 600; color: var(--muted); }
.skill-dialog-body .field textarea { resize: vertical; min-height: 74px; }
.inline-check { display: flex; align-items: center; gap: 7px; font-size: 12px; color: var(--text); }
.upload-form-grid { display: grid; grid-template-columns: 1fr 120px; gap: 10px; }
.upload-mode { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; padding: 4px; border: 1px solid var(--border); border-radius: 10px; background: #f8fafc; }
.mode-btn { height: 34px; border: 0; border-radius: 8px; background: transparent; color: var(--muted); font-size: 12px; font-weight: 700; cursor: pointer; }
.mode-btn.active { background: #fff; color: var(--blue-dim); box-shadow: 0 1px 3px rgba(15, 23, 42, .12); }
.skill-dialog-body .file-pick { height: 38px; }
.script-file-list { display: flex; flex-wrap: wrap; gap: 6px; }
.script-file-list span { padding: 3px 7px; border-radius: 999px; background: #eff6ff; color: #1d4ed8; border: 1px solid #dbeafe; font-size: 11px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.upload-hint { font-size: 12px; color: var(--muted); line-height: 1.5; }
.overwrite-alert { padding: 9px 11px; border-radius: 10px; background: #fff7ed; border: 1px solid #fed7aa; color: #9a3412; font-size: 12px; line-height: 1.5; font-weight: 700; }
.skill-dialog-actions { display: flex; gap: 8px; justify-content: flex-end; border-top: 1px solid var(--border); padding: 14px 20px; }
.preview-body { padding: 18px 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 14px; }
.preview-section { min-width: 0; display: flex; flex-direction: column; gap: 8px; }
.preview-title { font-size: 12px; font-weight: 800; color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }
.github-preview { display: grid; grid-template-columns: 260px minmax(0, 1fr); min-height: 420px; border: 1px solid var(--border); border-radius: 12px; overflow: hidden; background: #fff; }
.github-preview.package { min-height: 560px; }
.github-file-list { background: #f8fafc; border-right: 1px solid var(--border); overflow: auto; display: flex; flex-direction: column; }
.github-file-row { width: 100%; min-height: 34px; border: 0; border-bottom: 1px solid #eef2f7; background: transparent; display: grid; grid-template-columns: 34px minmax(0, 1fr); column-gap: 6px; align-items: center; text-align: left; cursor: pointer; color: var(--text); }
.github-file-row:hover { background: #eff6ff; }
.github-file-row.active { background: #dbeafe; color: #1d4ed8; }
.github-file-icon { font-size: 10px; font-weight: 800; color: var(--muted); text-transform: uppercase; }
.github-file-name { font-size: 12px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.github-file-dir { grid-column: 2; font-size: 10px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; margin-top: -3px; }
.github-file-view { min-width: 0; display: flex; flex-direction: column; background: #0f172a; }
.github-file-head { height: 38px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 12px; border-bottom: 1px solid rgba(148, 163, 184, .22); color: #cbd5e1; font-size: 12px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.github-code { margin: 0; flex: 1; min-height: 0; max-height: 62vh; overflow: auto; padding: 14px; color: #e2e8f0; font-size: 12px; line-height: 1.65; white-space: pre-wrap; font-family: ui-monospace, Menlo, Consolas, monospace; }
.github-editor { width: 100%; resize: vertical; border: 0; outline: none; background: #0f172a; min-height: 420px; }
.mini-dark-btn { border: 1px solid rgba(148, 163, 184, .35); background: rgba(255, 255, 255, .08); color: #e2e8f0; border-radius: 7px; height: 24px; padding: 0 8px; font-size: 11px; cursor: pointer; }
.mini-dark-btn:hover { background: rgba(255, 255, 255, .14); }
.tools-empty { padding: 18px; color: var(--muted); font-size: 12px; }
@media (max-width: 820px) { .github-preview, .upload-form-grid, .drawer-form-grid { grid-template-columns: 1fr; } .github-file-list { border-right: 0; border-bottom: 1px solid var(--border); max-height: 220px; } }
</style>

