<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { currentDomain, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { confirmDialog, formDialog, promptDialog } from '../stores/dialog'
import { notifyError, notifySuccess } from '../stores/notify'

type GraphNode = { id: string; label: string; type: string; x: number; y: number; vx: number; vy: number; degree: number; fixed?: boolean }
type GraphEdge = { source: number; target: number; label: string; fact: JsonMap }

const domainOptions = ref<JsonMap[]>([{ domain: 'platform', label: '平台', org_id: 'platform' }])
const selectedDomain = ref(currentDomain())
const spaces = ref<JsonMap[]>([])
const selectedSpaceKey = ref('')
const entities = ref<JsonMap[]>([])
const facts = ref<JsonMap[]>([])
const versions = ref<JsonMap[]>([])
const activeTab = ref<'entities' | 'facts' | 'versions'>('facts')
const query = ref('')
const factSearch = ref('')
const entityType = ref('')
const selectedEntity = ref<JsonMap | null>(null)
const selectedEntityId = ref('')
const entityFacts = ref<JsonMap[]>([])
const error = ref('')
const canvasRef = ref<HTMLCanvasElement | null>(null)
const graphNodes = ref<GraphNode[]>([])
const graphEdges = ref<GraphEdge[]>([])
const graphCamX = ref(0)
const graphCamY = ref(0)
const graphCamZ = ref(1)
let graphFrame = 0
let draggingNode: GraphNode | null = null
let panning = false
let panStart = { x: 0, y: 0, camX: 0, camY: 0 }

const selectedSpace = computed(() => spaces.value.find((s) => spaceKey(s) === selectedSpaceKey.value) || spaces.value[0] || null)
const currentOrg = computed(() => String(selectedSpace.value?.org_id || domainOptions.value.find((d) => d.domain === selectedDomain.value)?.org_id || 'platform'))
const scopeParams = computed(() => selectedSpace.value ? `&domain=${encodeURIComponent(String(selectedSpace.value.domain || ''))}&graph_key=${encodeURIComponent(String(selectedSpace.value.graph_key || ''))}` : '')
const visibleEntities = computed(() => {
  const q = query.value.trim().toLowerCase()
  return entities.value.filter((e) => {
    const hitQ = !q || [e.entity_name, e.entity_norm, e.entity_type, e.description].join(' ').toLowerCase().includes(q)
    const hitType = !entityType.value || e.entity_type === entityType.value
    return hitQ && hitType
  })
})
const visibleFacts = computed(() => {
  const q = query.value.trim().toLowerCase()
  const fq = factSearch.value.trim().toLowerCase()
  let list = facts.value
  if (q) list = list.filter((f) => [factSubjectName(f), factObjectName(f), factRelationLabel(f), factEvidence(f)].join(' ').toLowerCase().includes(q))
  if (fq) list = list.filter((f) => JSON.stringify(f).toLowerCase().includes(fq))
  return list
})
const entityTypes = computed(() => Array.from(new Set(entities.value.map((e) => String(e.entity_type || '')).filter(Boolean))).sort())

function spaceKey(s: JsonMap) { return `${s.org_id || ''}::${s.domain || ''}::${s.graph_key || ''}` }
function headers(org = currentOrg.value) { return makeHeaders(false, org) }
const TYPE_PALETTE = ['#3b82f6', '#22c55e', '#f59e0b', '#a855f7', '#ef4444', '#14b8a6', '#6366f1', '#ec4899', '#84cc16', '#64748b']
function entityColor(type: unknown) {
  const key = String(type || 'unknown').trim().toLowerCase() || 'unknown'
  const fixed: Record<string, number> = { concept: 0, entity: 1, event: 2, term: 3, unknown: 9 }
  if (fixed[key] != null) return TYPE_PALETTE[fixed[key]]
  let hash = 0
  for (const ch of key) hash = ((hash * 31) + ch.charCodeAt(0)) >>> 0
  return TYPE_PALETTE[hash % TYPE_PALETTE.length]
}
function nodeColor(type: string) { return entityColor(type) }
function factSubjectId(f: JsonMap) { return String(f.subject_entity_id ?? f.source_entity_id ?? f.subject_id ?? '') }
function factObjectId(f: JsonMap) { return String(f.object_entity_id ?? f.target_entity_id ?? f.object_id ?? '') }
function factSubjectName(f: JsonMap) { return String(f.subject_name || f.subject || f.source_name || '') }
function factObjectName(f: JsonMap) { return String(f.object_name || f.object || f.target_name || '') }
function factRelationLabel(f: JsonMap) { return String(f.relation_type || f.predicate || '') }
function factEvidence(f: JsonMap) { return String(f.description || f.evidence || '') }
function confidenceLabel(value: unknown) { const n = Number(value); return Number.isFinite(n) && n > 0 ? `${Math.round(n * 100)}%` : '—' }
function versionBadgeClass(status: unknown) {
  const s = String(status || '').toLowerCase()
  if (['published', 'active', 'succeeded'].includes(s)) return 'badge-green'
  if (['failed', 'error'].includes(s)) return 'badge-red'
  if (['draft', 'pending', 'building'].includes(s)) return 'badge-amber'
  return 'badge-gray'
}
function selectedIdOf(e: JsonMap | null) { return String(e?.entity_id || e?.id || '') }
function resetGraphView() { graphCamX.value = 0; graphCamY.value = 0; graphCamZ.value = 1; drawGraph() }
function zoomGraph(factor: number) { graphCamZ.value = Math.max(0.18, Math.min(4, graphCamZ.value * factor)); drawGraph() }
function fitGraphView() { query.value = ''; entityType.value = ''; resetGraphView(); buildGraph() }

async function loadDomains() {
  try {
    const data = await readJson<JsonMap>(await fetch('/platform/frontend/infra/status', { headers: makeHeaders(false, 'platform') }))
    const raw = data.domains && typeof data.domains === 'object' ? data.domains : {}
    domainOptions.value = [{ domain: 'platform', label: '平台', org_id: 'platform' }, ...Object.entries(raw).map(([domain, meta]: [string, any]) => ({ domain, label: meta?.display_name || domain, org_id: meta?.org_id || domain })).filter((d) => d.domain !== 'platform')]
  } catch {}
}
async function loadSpaces() {
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/kg/graph-spaces?domain=${encodeURIComponent(selectedDomain.value)}`, { headers: makeHeaders(false, 'platform') }))
  spaces.value = Array.isArray(data.items) ? data.items : Array.isArray(data.graph_spaces) ? data.graph_spaces : []
  if (!spaces.value.some((s) => spaceKey(s) === selectedSpaceKey.value)) selectedSpaceKey.value = spaces.value[0] ? spaceKey(spaces.value[0]) : ''
}
async function createSpace() {
  const graphKey = await promptDialog('新建图谱空间', '图谱 key', '', { placeholder: '例如 platform_default' })
  if (!graphKey) return
  try {
    await readJson(await fetch('/platform/frontend/kg/graph-spaces', { method: 'POST', headers: makeHeaders(true, currentOrg.value), body: JSON.stringify({ domain: selectedDomain.value, graph_key: graphKey.trim(), display_name: graphKey.trim(), description: '', visibility: 'org' }) }))
    await loadSpaces()
    notifySuccess(`图谱空间 ${graphKey.trim()} 已创建`)
  } catch (err) { notifyError(err) }
}
async function editSpace() {
  const sp = selectedSpace.value
  if (!sp) return
  const res = await formDialog({
    title: '编辑图谱空间',
    message: `${sp.domain || ''} / ${sp.graph_key || ''}`,
    fields: [
      { key: 'display_name', label: '显示名称', default: String(sp.display_name || sp.graph_key || ''), placeholder: '显示名称' },
      { key: 'description', label: '描述', type: 'textarea', default: String(sp.description || ''), placeholder: '可选' },
    ],
    confirmLabel: '保存',
  })
  if (!res) return
  try {
    await readJson(await fetch('/platform/frontend/kg/graph-spaces/update', {
      method: 'POST',
      headers: makeHeaders(true, currentOrg.value),
      body: JSON.stringify({ domain: sp.domain, graph_key: sp.graph_key, display_name: (res.display_name || '').trim() || undefined, description: res.description ?? '' }),
    }))
    await loadSpaces()
    notifySuccess('图谱空间已更新')
  } catch (err) { notifyError(err) }
}
async function archiveSpace() {
  const sp = selectedSpace.value
  if (!sp) return
  const ok = await confirmDialog(`确定要归档图谱空间「${sp.display_name || sp.graph_key}」吗？归档后将从列表移除（已抽取的实体与关系不会被删除）。`, { title: '归档图谱空间', confirmLabel: '归档', danger: true })
  if (!ok) return
  try {
    await readJson(await fetch('/platform/frontend/kg/graph-spaces/archive', {
      method: 'POST',
      headers: makeHeaders(true, currentOrg.value),
      body: JSON.stringify({ domain: sp.domain, graph_key: sp.graph_key }),
    }))
    selectedSpaceKey.value = ''
    await loadSpaces()
    notifySuccess('图谱空间已归档')
  } catch (err) { notifyError(err) }
}
async function loadEntities() {
  if (!selectedSpace.value) { entities.value = []; return }
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/kg/entities?limit=500${scopeParams.value}`, { headers: headers() }))
  entities.value = Array.isArray(data.items) ? data.items : Array.isArray(data.entities) ? data.entities : []
}
async function loadFacts() {
  if (!selectedSpace.value) { facts.value = []; return }
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/kg/facts?limit=240${scopeParams.value}`, { headers: headers() }))
  facts.value = Array.isArray(data.items) ? data.items : Array.isArray(data.facts) ? data.facts : []
}
async function loadVersions() {
  if (!selectedSpace.value) { versions.value = []; return }
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/kg/versions?limit=20${scopeParams.value}`, { headers: headers() }))
  versions.value = Array.isArray(data.items) ? data.items : Array.isArray(data.versions) ? data.versions : []
}
async function reload() {
  error.value = ''
  try { await loadSpaces(); await Promise.all([loadEntities(), loadFacts(), loadVersions()]); buildGraph() }
  catch (err) { error.value = err instanceof Error ? err.message : String(err) }
}
function persistDomainContext() {
  localStorage.setItem('platform_live_domain', selectedDomain.value)
  const orgId = domainOptions.value.find((d) => d.domain === selectedDomain.value)?.org_id
  if (orgId) localStorage.setItem('platform_live_org_id', String(orgId))
}
async function changeDomain() { persistDomainContext(); selectedSpaceKey.value = ''; selectedEntity.value = null; selectedEntityId.value = ''; await reload() }
async function changeSpace() { selectedEntity.value = null; selectedEntityId.value = ''; await Promise.all([loadEntities(), loadFacts(), loadVersions()]); buildGraph() }
async function selectEntity(e: JsonMap) {
  selectedEntity.value = e
  selectedEntityId.value = selectedIdOf(e)
  const id = selectedEntityId.value
  if (!id) return
  const data = await readJson<JsonMap>(await fetch(`/platform/frontend/kg/entities/${encodeURIComponent(id)}/facts?entity_id=${encodeURIComponent(id)}&limit=100${scopeParams.value}`, { headers: headers() }))
  entityFacts.value = Array.isArray(data.items) ? data.items : Array.isArray(data.facts) ? data.facts : []
  focusGraphNode(id)
}
function selectGraphNode(id: string) { const entity = entities.value.find((e) => String(e.entity_id || e.id) === String(id)); if (entity) selectEntity(entity) }

function canvasSize() {
  const canvas = canvasRef.value
  if (!canvas) return { w: 0, h: 0, ratio: 1 }
  const ratio = window.devicePixelRatio || 1
  const rect = canvas.getBoundingClientRect()
  const w = Math.max(1, Math.floor(rect.width))
  const h = Math.max(1, Math.floor(rect.height))
  if (canvas.width !== Math.floor(w * ratio) || canvas.height !== Math.floor(h * ratio)) { canvas.width = Math.floor(w * ratio); canvas.height = Math.floor(h * ratio) }
  return { w, h, ratio }
}
function buildGraph() {
  const nodesRaw = visibleEntities.value.slice(0, 80)
  const byId = new Map<string, number>()
  const nodes: GraphNode[] = nodesRaw.map((e, i) => {
    const id = String(e.entity_id || e.id || i)
    byId.set(id, i)
    const angle = (i / Math.max(1, nodesRaw.length)) * Math.PI * 2
    const radius = 120 + (i % 9) * 16
    return { id, label: String(e.entity_name || e.name || '?'), type: String(e.entity_type || 'entity'), x: Math.cos(angle) * radius, y: Math.sin(angle) * radius, vx: 0, vy: 0, degree: 0 }
  })
  const edges: GraphEdge[] = []
  for (const f of visibleFacts.value.slice(0, 160)) {
    const sid = factSubjectId(f)
    const oid = factObjectId(f)
    const sidx = byId.get(sid)
    const oidx = byId.get(oid)
    if (sidx == null || oidx == null || sidx === oidx) continue
    nodes[sidx].degree += 1
    nodes[oidx].degree += 1
    edges.push({ source: sidx, target: oidx, label: factRelationLabel(f), fact: f })
  }
  graphNodes.value = nodes
  graphEdges.value = edges
  startGraph()
}
function graphScreenToWorld(x: number, y: number) {
  const { w, h } = canvasSize()
  return [(x - w / 2 - graphCamX.value) / graphCamZ.value, (y - h / 2 - graphCamY.value) / graphCamZ.value]
}
function graphHitNode(x: number, y: number) {
  const [wx, wy] = graphScreenToWorld(x, y)
  return graphNodes.value.find((n) => Math.hypot(n.x - wx, n.y - wy) < (16 + Math.min(n.degree, 12)) / graphCamZ.value) || null
}
function tickGraph() {
  const nodes = graphNodes.value
  const edges = graphEdges.value
  if (!nodes.length) return
  for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
    const a = nodes[i], b = nodes[j]
    let dx = b.x - a.x, dy = b.y - a.y
    let d2 = dx * dx + dy * dy || 1
    const force = Math.min(80, 2600 / d2)
    const d = Math.sqrt(d2)
    dx /= d; dy /= d
    if (!a.fixed) { a.vx -= dx * force; a.vy -= dy * force }
    if (!b.fixed) { b.vx += dx * force; b.vy += dy * force }
  }
  for (const e of edges) {
    const a = nodes[e.source], b = nodes[e.target]
    if (!a || !b) continue
    const dx = b.x - a.x, dy = b.y - a.y
    const d = Math.sqrt(dx * dx + dy * dy) || 1
    const force = (d - 150) * 0.012
    if (!a.fixed) { a.vx += (dx / d) * force; a.vy += (dy / d) * force }
    if (!b.fixed) { b.vx -= (dx / d) * force; b.vy -= (dy / d) * force }
  }
  for (const n of nodes) {
    if (n.fixed) continue
    n.vx += -n.x * 0.002
    n.vy += -n.y * 0.002
    n.x += n.vx
    n.y += n.vy
    n.vx *= 0.82
    n.vy *= 0.82
  }
}
function drawGraph() {
  const canvas = canvasRef.value
  const ctx = canvas?.getContext('2d')
  if (!canvas || !ctx) return
  const { w, h, ratio } = canvasSize()
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
  ctx.clearRect(0, 0, w, h)
  const nodes = graphNodes.value
  const edges = graphEdges.value
  if (!nodes.length) {
    ctx.fillStyle = '#64748b'
    ctx.font = '14px sans-serif'
    ctx.textAlign = 'center'
    ctx.fillText(selectedSpace.value ? '当前图谱暂无可视化数据' : '请选择业务域和图谱空间', w / 2, h / 2)
    return
  }
  ctx.save()
  ctx.translate(w / 2 + graphCamX.value, h / 2 + graphCamY.value)
  ctx.scale(graphCamZ.value, graphCamZ.value)
  for (const edge of edges) {
    const a = nodes[edge.source], b = nodes[edge.target]
    if (!a || !b) continue
    const related = selectedEntityId.value && (a.id === selectedEntityId.value || b.id === selectedEntityId.value)
    ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(b.x, b.y)
    ctx.strokeStyle = related ? '#60a5fa' : 'rgba(148,163,184,.42)'
    ctx.lineWidth = related ? 1.6 : 0.9
    ctx.stroke()
    if (graphCamZ.value > 0.5 && edge.label) {
      const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2
      const text = edge.label.slice(0, 18)
      ctx.font = `${related ? 'bold ' : ''}9px sans-serif`
      const tw = ctx.measureText(text).width
      ctx.fillStyle = 'rgba(255,255,255,.88)'
      ctx.beginPath(); ctx.roundRect(mx - tw / 2 - 4, my - 8, tw + 8, 15, 4); ctx.fill()
      ctx.fillStyle = related ? '#1d4ed8' : '#64748b'; ctx.textAlign = 'center'; ctx.fillText(text, mx, my + 3)
    }
  }
  for (const n of nodes) {
    const selected = n.id === selectedEntityId.value
    const faded = selectedEntityId.value && !selected && !edges.some((e) => { const a = nodes[e.source], b = nodes[e.target]; return (a?.id === selectedEntityId.value && b?.id === n.id) || (b?.id === selectedEntityId.value && a?.id === n.id) })
    const radius = 13 + Math.min(n.degree, 10)
    ctx.globalAlpha = faded ? 0.28 : 1
    ctx.beginPath(); ctx.arc(n.x, n.y, selected ? radius + 3 : radius, 0, Math.PI * 2); ctx.fillStyle = nodeColor(n.type); ctx.fill(); ctx.lineWidth = selected ? 3 : 1.5; ctx.strokeStyle = selected ? '#0f172a' : '#fff'; ctx.stroke()
    if (graphCamZ.value > 0.48) { ctx.font = `${selected ? 'bold ' : ''}10px sans-serif`; ctx.textAlign = 'center'; ctx.fillStyle = '#1e293b'; ctx.fillText(n.label.slice(0, 12), n.x, n.y + radius + 13) }
    ctx.globalAlpha = 1
  }
  ctx.restore()
}
function startGraph() { cancelAnimationFrame(graphFrame); const loop = () => { tickGraph(); drawGraph(); graphFrame = requestAnimationFrame(loop) }; graphFrame = requestAnimationFrame(loop) }
function stopGraph() { cancelAnimationFrame(graphFrame) }
function focusGraphNode(id: string) { const n = graphNodes.value.find((node) => node.id === String(id)); if (n) { graphCamX.value = -n.x * graphCamZ.value; graphCamY.value = -n.y * graphCamZ.value; drawGraph() } }
function onMouseDown(event: MouseEvent) { const hit = graphHitNode(event.offsetX, event.offsetY); if (hit) { draggingNode = hit; hit.fixed = true; selectGraphNode(hit.id) } else { panning = true; panStart = { x: event.offsetX, y: event.offsetY, camX: graphCamX.value, camY: graphCamY.value } } canvasRef.value?.classList.add('dragging') }
function onMouseMove(event: MouseEvent) { if (draggingNode) { const [wx, wy] = graphScreenToWorld(event.offsetX, event.offsetY); draggingNode.x = wx; draggingNode.y = wy; draggingNode.vx = 0; draggingNode.vy = 0; drawGraph() } else if (panning) { graphCamX.value = panStart.camX + event.offsetX - panStart.x; graphCamY.value = panStart.camY + event.offsetY - panStart.y; drawGraph() } }
function onMouseUp() { draggingNode = null; panning = false; canvasRef.value?.classList.remove('dragging') }
function onDblClick(event: MouseEvent) { const hit = graphHitNode(event.offsetX, event.offsetY); if (hit) hit.fixed = false }
function onWheel(event: WheelEvent) { event.preventDefault(); zoomGraph(event.deltaY < 0 ? 1.12 : 1 / 1.12) }

watch([query, entityType], async () => { await nextTick(); buildGraph() })
watch(selectedEntityId, drawGraph)
onMounted(async () => { await loadDomains(); persistDomainContext(); await reload(); window.addEventListener('resize', drawGraph) })
onBeforeUnmount(() => { stopGraph(); window.removeEventListener('resize', drawGraph) })
</script>

<template>
  <section class="kg-studio">
    <aside class="kg-rail">
      <div class="kg-brand-card">
        <div class="kg-brand-icon">KG</div>
        <div>
          <div class="kg-brand-title">知识图谱工作台</div>
          <div class="kg-brand-sub">实体、关系、版本统一浏览</div>
        </div>
      </div>

      <div class="kg-control-card">
        <label>业务域</label>
        <select v-model="selectedDomain" @change="changeDomain">
          <option v-for="d in domainOptions" :key="d.domain" :value="d.domain">{{ d.label }} ({{ d.domain }})</option>
        </select>
        <label>图谱空间</label>
        <select v-model="selectedSpaceKey" @change="changeSpace">
          <option v-for="s in spaces" :key="spaceKey(s)" :value="spaceKey(s)">{{ s.display_name || s.graph_key }}</option>
        </select>
        <div class="kg-control-actions">
          <button class="btn btn-primary btn-sm" @click="createSpace">新建空间</button>
          <button class="btn btn-ghost btn-sm" :disabled="!selectedSpace" @click="editSpace">编辑</button>
          <button class="btn btn-ghost btn-sm" :disabled="!selectedSpace" @click="archiveSpace">归档</button>
          <button class="btn btn-ghost btn-sm" @click="reload">刷新</button>
        </div>
      </div>

      <div class="kg-mini-stats">
        <div><strong>{{ entities.length }}</strong><span>实体</span></div>
        <div><strong>{{ facts.length }}</strong><span>关系</span></div>
        <div><strong>{{ versions.length }}</strong><span>版本</span></div>
      </div>

      <div class="kg-entity-panel">
        <div class="kg-panel-head">
          <div>
            <strong>实体浏览</strong>
            <span>{{ visibleEntities.length }} / {{ entities.length }}</span>
          </div>
        </div>
        <input v-model="query" class="kg-search" placeholder="搜索实体、类型、关系..." />
        <select v-model="entityType" class="kg-type-select">
          <option value="">全部类型</option>
          <option v-for="t in entityTypes" :key="t" :value="t">{{ t }}</option>
        </select>
        <div class="kg-entity-scroll">
          <div v-if="!visibleEntities.length" class="empty">暂无实体</div>
          <button v-for="e in visibleEntities" :key="e.entity_id || e.id" class="kg-entity-row" :class="{selected: selectedEntityId === selectedIdOf(e)}" @click="selectEntity(e)">
            <span class="kg-entity-dot" :style="{background: entityColor(e.entity_type)}"></span>
            <span class="kg-entity-main">
              <strong>{{ e.entity_name || e.name }}</strong>
              <small>{{ e.entity_type || 'entity' }} · #{{ e.entity_id || e.id }}</small>
            </span>
          </button>
        </div>
      </div>
    </aside>

    <main class="kg-main-stage">
      <p v-if="error" class="error-line">{{ error }}</p>
      <header class="kg-stage-head">
        <div>
          <h2>{{ selectedSpace?.display_name || selectedSpace?.graph_key || '未选择图谱空间' }}</h2>
          <p>{{ selectedSpace?.org_id || currentOrg }} / {{ selectedSpace?.domain || selectedDomain }} / {{ selectedSpace?.graph_key || '-' }}</p>
        </div>
        <div class="kg-head-actions">
          <span class="kg-count-pill">{{ graphNodes.length }} nodes</span>
          <span class="kg-count-pill">{{ graphEdges.length }} edges</span>
          <button class="btn btn-ghost btn-sm" @click="fitGraphView">重置视图</button>
          <button class="btn btn-ghost btn-sm" @click="zoomGraph(1.2)">放大</button>
          <button class="btn btn-ghost btn-sm" @click="zoomGraph(1/1.2)">缩小</button>
        </div>
      </header>

      <section class="kg-canvas-card">
        <canvas ref="canvasRef" class="graph-canvas" aria-label="知识图谱可视化" @mousedown="onMouseDown" @mousemove="onMouseMove" @mouseup="onMouseUp" @mouseleave="onMouseUp" @dblclick="onDblClick" @wheel="onWheel"></canvas>
        <div class="kg-legend-card">
          <div class="kg-legend-title">图例</div>
          <div v-for="t in entityTypes.slice(0, 8)" :key="t" class="leg-row"><span class="leg-dot" :style="{background: entityColor(t)}"></span>{{ t }}</div>
          <div class="kg-hint">拖节点定位，拖空白移动画布，滚轮缩放，双击节点释放固定。</div>
        </div>
      </section>

      <section class="kg-data-dock">
        <div class="kg-dock-tabs">
          <button :class="{active: activeTab === 'entities'}" @click="activeTab = 'entities'">实体浏览</button>
          <button :class="{active: activeTab === 'facts'}" @click="activeTab = 'facts'">关系事实</button>
          <button :class="{active: activeTab === 'versions'}" @click="activeTab = 'versions'">图谱版本</button>
        </div>
        <div v-if="activeTab === 'entities'" class="kg-dock-body">
          <table>
            <thead><tr><th>名称</th><th>类型</th><th>ID</th><th>描述</th></tr></thead>
            <tbody>
              <tr v-for="e in visibleEntities" :key="e.entity_id || e.id" class="kg-row-clickable" @click="selectEntity(e)">
                <td><strong>{{ e.entity_name || e.name }}</strong></td>
                <td><span class="badge" :style="{background: entityColor(e.entity_type) + '22', color: entityColor(e.entity_type)}">{{ e.entity_type || 'entity' }}</span></td>
                <td class="mono">{{ e.entity_id || e.id }}</td>
                <td>{{ e.description || '' }}</td>
              </tr>
              <tr v-if="!visibleEntities.length"><td colspan="4" class="empty">暂无实体</td></tr>
            </tbody>
          </table>
        </div>
        <div v-else-if="activeTab === 'facts'" class="kg-dock-body">
          <div class="kg-dock-toolbar"><input v-model="factSearch" placeholder="搜索关系或实体…" class="kg-search" /><span class="muted-small">{{ visibleFacts.length }} 条关系</span></div>
          <table>
            <thead><tr><th>主体</th><th>关系</th><th>客体</th><th>证据</th></tr></thead>
            <tbody>
              <tr v-for="f in visibleFacts" :key="f.fact_id || f.id">
                <td>{{ factSubjectName(f) }}</td>
                <td><span class="badge badge-green">{{ factRelationLabel(f) }}</span></td>
                <td>{{ factObjectName(f) }}</td>
                <td>{{ factEvidence(f) }}<div class="muted-small">置信度: {{ confidenceLabel(f.confidence) }}</div></td>
              </tr>
              <tr v-if="!visibleFacts.length"><td colspan="4" class="empty">暂无关系事实</td></tr>
            </tbody>
          </table>
        </div>
        <div v-else class="kg-dock-body">
          <table>
            <thead><tr><th>版本</th><th>空间</th><th>状态</th><th>实体</th><th>关系</th><th>时间</th></tr></thead>
            <tbody>
              <tr v-for="v in versions" :key="v.version_id || v.id">
                <td class="mono">{{ v.version_id || v.id }}</td>
                <td>{{ (v.domain || '') + '/' + (v.graph_key || '') }}</td>
                <td><span class="badge" :class="versionBadgeClass(v.status)">{{ v.status || 'unknown' }}</span></td>
                <td>{{ v.entity_count || v.entities_count || '-' }}</td>
                <td>{{ v.fact_count || v.facts_count || '-' }}</td>
                <td>{{ v.created_at || v.updated_at || '' }}</td>
              </tr>
              <tr v-if="!versions.length"><td colspan="6" class="empty">暂无版本</td></tr>
            </tbody>
          </table>
        </div>
      </section>
    </main>

    <aside class="kg-inspector">
      <div v-if="!selectedEntity" class="kg-inspector-empty">
        <div class="big-icon">🕸</div>
        <strong>选择一个实体</strong>
        <p>点击画布节点或左侧实体，查看实体关系、证据、来源和置信度。</p>
      </div>
      <template v-else>
        <div class="kg-inspector-head">
          <div class="entity-avatar">{{ String(selectedEntity.entity_name || '?').slice(0,1) }}</div>
          <div>
            <h3>{{ selectedEntity.entity_name }}</h3>
            <p>{{ selectedEntity.entity_type }} · #{{ selectedEntity.entity_id || selectedEntity.id }}</p>
          </div>
        </div>
        <div class="kg-inspector-meta">
          <div><span>关系数</span><strong>{{ entityFacts.length }}</strong></div>
          <div><span>类型</span><strong>{{ selectedEntity.entity_type || 'entity' }}</strong></div>
        </div>
        <div class="kg-relation-list">
          <div class="kg-section-title">关联事实</div>
          <div v-for="f in entityFacts" :key="f.fact_id || f.id" class="kg-relation-card">
            <div class="fact-rel">{{ factRelationLabel(f) }}</div>
            <div class="fact-target">{{ factSubjectName(f) }} → {{ factObjectName(f) }}</div>
            <div class="fact-desc">{{ factEvidence(f) }}</div>
            <div class="fact-badges">
              <span v-if="f.source_doc_id" class="mini-badge">来源: {{ String(f.source_doc_id).slice(0, 10) }}...</span>
              <span class="mini-badge">置信度: {{ confidenceLabel(f.confidence) }}</span>
            </div>
          </div>
          <div v-if="!entityFacts.length" class="empty">暂无关系事实</div>
        </div>
      </template>
    </aside>
  </section>
</template>
