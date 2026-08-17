<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { currentDomain, currentOrgId, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'
import { notifyError, notifySuccess } from '../stores/notify'
import { formDialog } from '../stores/dialog'

const NODE_TYPES = [
  { value: 'workflow.input', label: 'Workflow 输入', group: '流程' },
  { value: 'workflow.output', label: 'Workflow 输出', group: '流程' },
  { value: 'agent.invoke', label: 'Agent 调用', group: 'AI' },
  { value: 'agent.react', label: 'ReActAgent', group: 'AI' },
  { value: 'llm.chat', label: 'LLM 调用', group: 'AI' },
  { value: 'http.request', label: 'HTTP / API', group: '业务' },
  { value: 'subflow.invoke', label: '子流程', group: '控制' },
]
const GRAPH_NODE_WIDTH = 220
const GRAPH_NODE_HEIGHT = 112

const workflows = ref<JsonMap[]>([])
const workflow = ref<JsonMap | null>(null)
const selectedWorkflowId = ref('')
const nodes = ref<JsonMap[]>([])
const edges = ref<JsonMap[]>([])
const selectedNodeId = ref('')
const loading = ref(false)
const creating = ref(false)
const saving = ref(false)
const publishing = ref(false)
const testing = ref(false)
const validating = ref(false)
const validation = ref<JsonMap | null>(null)
const detailDrawerOpen = ref(false)
const canvasOpen = ref(false)
const initialParams = new URLSearchParams(window.location.search)
const standaloneCanvas = initialParams.get('view') === 'canvas'
const requestedWorkflowId = initialParams.get('workflow_id') || ''
const testInput = ref('')
const testOutput = ref('')
const testEvents = ref<JsonMap[]>([])
const graphCanvasRef = ref<HTMLElement | null>(null)
const paletteDragType = ref('')
const dragState = ref<{ nodeId: string; offsetX: number; offsetY: number } | null>(null)
const connectionState = ref<{ sourceId: string; sourcePortId: string; x: number; y: number } | null>(null)
const nodeIdSnapshot = new WeakMap<JsonMap, string>()

watch(nodes, (current) => {
  for (const node of current) {
    const currentId = String(node.nodeId || '')
    const previousId = nodeIdSnapshot.get(node)
    if (previousId && previousId !== currentId) {
      edges.value.forEach((edge) => {
        const from = (edge.from || {}) as JsonMap
        const to = (edge.to || {}) as JsonMap
        if (String(from.nodeId) === previousId) from.nodeId = currentId
        if (String(to.nodeId) === previousId) to.nodeId = currentId
      })
    }
    nodeIdSnapshot.set(node, currentId)
  }
}, { deep: true })

const selectedNode = computed(() => nodes.value.find((node) => node.nodeId === selectedNodeId.value) || null)
const selectedNodeEdges = computed(() => edges.value.filter((edge) => {
  const from = (edge.from || {}) as JsonMap
  const to = (edge.to || {}) as JsonMap
  return String(from.nodeId) === selectedNodeId.value || String(to.nodeId) === selectedNodeId.value
}))
const status = computed(() => String(workflow.value?.status || 'DRAFT'))
const isPublished = computed(() => status.value === 'PUBLISHED')
const graphEdges = computed(() => {
  const result: {
    id: string
    from: string
    fromPort: string
    to: string
    toPort: string
    label: string
    dashed: boolean
  }[] = []
  edges.value.forEach((edge, index) => {
    const from = (edge.from || {}) as JsonMap
    const to = (edge.to || {}) as JsonMap
    if (!from.nodeId || !to.nodeId) return
    result.push({
      id: String(edge.edgeId || `edge_${index + 1}`),
      from: String(from.nodeId),
      fromPort: String(from.portId || 'value'),
      to: String(to.nodeId),
      toPort: String(to.portId || 'value'),
      label: edge.kind === 'control' ? '控制' : String(edge.binding && Object.keys(edge.binding as JsonMap).length ? '映射' : '数据'),
      dashed: edge.kind === 'control',
    })
  })
  return result
})
const graphSize = computed(() => {
  const width = Math.max(
    860,
    ...nodes.value.map((node) => Number((node.position as JsonMap)?.x || 0) + GRAPH_NODE_WIDTH + 100),
  )
  const height = Math.max(
    560,
    ...nodes.value.map((node) => Number((node.position as JsonMap)?.y || 0) + GRAPH_NODE_HEIGHT + 100),
  )
  return { width, height }
})

function headers(json = false) { return makeHeaders(json, currentOrgId()) }
async function api(path: string, options: RequestInit = {}) {
  return await readJson<JsonMap>(await fetch(path, { ...options, headers: { ...headers(Boolean(options.body)), ...(options.headers || {}) } }))
}

function nodeTypeLabel(type: string) {
  return NODE_TYPES.find((item) => item.value === type)?.label || type
}

function isBoundaryNode(node: JsonMap | null | undefined): boolean {
  return node?.type === 'workflow.input' || node?.type === 'workflow.output'
}

function boundaryNode(type: 'workflow.input' | 'workflow.output', nodeId: string, position: { x: number; y: number }, schema: JsonMap = {}): JsonMap {
  return {
    nodeId,
    type,
    refId: '',
    instruction: '',
    config: { schema },
    inputMapping: {},
    outputSchema: {},
    timeoutMs: null,
    maxRetries: 0,
    failurePolicy: 'FAIL_FAST',
    inputPorts: defaultPorts(type, true, { schema }),
    outputPorts: defaultPorts(type, false, { schema }),
    position,
  }
}

function defaultPorts(type: string, input: boolean, config: JsonMap = {}): JsonMap[] {
  if ((type === 'workflow.input' && input) || (type === 'workflow.output' && !input)) return []
  return [{
    portId: 'value',
    direction: input ? 'input' : 'output',
    contractRef: type === 'workflow.input' ? 'workflow.input' : type === 'workflow.output' ? 'workflow.output' : '',
    schema: ((config.schema || {}) as JsonMap),
    required: input && type !== 'workflow.input',
    cardinality: 'one',
    description: input ? '节点输入' : '节点输出',
  }]
}

function normalizePorts(raw: unknown, type: string, input: boolean, config: JsonMap = {}): JsonMap[] {
  if (Array.isArray(raw) && raw.length) {
    return raw.map((port) => {
      const item = (port || {}) as JsonMap
      return {
        portId: String(item.portId || item.port_id || 'value'),
        direction: String(item.direction || (input ? 'input' : 'output')),
        contractRef: String(item.contractRef || item.contract_ref || ''),
        schema: ((item.schema || {}) as JsonMap),
        required: item.required === true,
        cardinality: String(item.cardinality || 'one'),
        description: String(item.description || ''),
      }
    })
  }
  return defaultPorts(type, input, config)
}

function nodeInputPorts(node: JsonMap): JsonMap[] { return (node.inputPorts || []) as JsonMap[] }
function nodeOutputPorts(node: JsonMap): JsonMap[] { return (node.outputPorts || []) as JsonMap[] }

function withBoundaryNodes(rawNodes: JsonMap[], inputSchema: JsonMap = {}, outputSchema: JsonMap = {}): JsonMap[] {
  const result = [...rawNodes]
  if (!result.some((node) => node.type === 'workflow.input')) {
    const minX = result.length ? Math.min(...result.map((node) => Number((node.position as JsonMap)?.x || 70))) : 70
    result.unshift(boundaryNode('workflow.input', 'workflow_input', { x: Math.max(20, minX - GRAPH_NODE_WIDTH - 60), y: 60 }, inputSchema))
  } else {
    const input = result.find((node) => node.type === 'workflow.input')
    if (input && !(input.config as JsonMap)?.schema) input.config = { ...((input.config || {}) as JsonMap), schema: inputSchema }
  }
  if (!result.some((node) => node.type === 'workflow.output')) {
    const maxX = result.length ? Math.max(...result.map((node) => Number((node.position as JsonMap)?.x || 70))) : 70
    result.push(boundaryNode('workflow.output', 'workflow_output', { x: maxX + GRAPH_NODE_WIDTH + 60, y: 60 }, outputSchema))
  } else {
    const output = result.find((node) => node.type === 'workflow.output')
    if (output && !(output.config as JsonMap)?.schema) output.config = { ...((output.config || {}) as JsonMap), schema: outputSchema }
  }
  return result
}

function positionValue(value: unknown, fallback: number) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : fallback
}

function workflowLabel(item: JsonMap) {
  return `${item.name || item.workflow_id} · ${item.workflow_id}`
}

function normalizeNode(raw: JsonMap, index: number): JsonMap {
  const config = { ...((raw.config || {}) as JsonMap) }
  const type = String(raw.type || 'agent.invoke')
  const savedPosition = (raw.position || raw.canvas_position || config.canvas_position || {}) as JsonMap
  return {
    nodeId: String(raw.nodeId || raw.node_id || raw.stepId || raw.step_id || `node_${index + 1}`),
    type,
    refId: String(raw.refId || raw.ref_id || raw.agentId || raw.agent_id || ''),
    instruction: String(raw.instruction || ''),
    config,
    position: {
      x: positionValue(savedPosition.x, 70 + (index % 3) * 280),
      y: positionValue(savedPosition.y, 60 + Math.floor(index / 3) * 180),
    },
    inputMapping: { ...((raw.inputMapping || raw.input_mapping || {}) as JsonMap) },
    outputSchema: { ...((raw.outputSchema || raw.output_schema || {}) as JsonMap) },
    timeoutMs: raw.timeoutMs || raw.timeout_ms || null,
    maxRetries: raw.maxRetries || raw.max_retries || 0,
    failurePolicy: raw.failurePolicy || raw.failure_policy || 'FAIL_FAST',
    inputPorts: normalizePorts(raw.inputPorts || raw.input_ports, type, true, config),
    outputPorts: normalizePorts(raw.outputPorts || raw.output_ports, type, false, config),
  }
}

function normalizeEdge(raw: JsonMap, index: number): JsonMap {
  const from = (raw.from || {}) as JsonMap
  const to = (raw.to || {}) as JsonMap
  return {
    edgeId: String(raw.edgeId || raw.edge_id || `edge_${index + 1}`),
    from: { nodeId: String(from.nodeId || from.node_id || ''), portId: String(from.portId || from.port_id || 'value') },
    to: { nodeId: String(to.nodeId || to.node_id || ''), portId: String(to.portId || to.port_id || 'value') },
    kind: String(raw.kind || 'data'),
    binding: ((raw.binding || {}) as JsonMap),
    mapping: Object.entries((raw.binding || {}) as JsonMap).map(([targetField, expression]) => ({
      targetField,
      sourcePath: typeof expression === 'string' ? expression : String(((expression || {}) as JsonMap).source_path || ''),
    })),
    condition: ((raw.condition || {}) as JsonMap),
    defaultEdge: raw.defaultEdge === true || raw.default_edge === true,
  }
}

async function loadWorkflows() {
  loading.value = true
  try {
    const data = await api(`/platform/frontend/workflows?domain=${encodeURIComponent(currentDomain('platform'))}`)
    workflows.value = (Array.isArray(data.items) ? data.items : Array.isArray(data.workflows) ? data.workflows : []) as JsonMap[]
    if (selectedWorkflowId.value && workflows.value.some((item) => String(item.workflow_id) === selectedWorkflowId.value)) {
      await selectWorkflow(selectedWorkflowId.value)
    } else if (workflows.value.length) {
      await selectWorkflow(String(workflows.value[0].workflow_id))
    } else {
      clearEditor()
    }
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function workflowItem(data: JsonMap) {
  return (data.item || data.workflow || data) as JsonMap
}

function applyWorkflow(item: JsonMap) {
  const workflowId = String(item.workflow_id || '')
  if (!workflowId) throw new Error('Workflow 响应缺少 workflow_id')
  selectedWorkflowId.value = workflowId
  workflow.value = item
  nodes.value = withBoundaryNodes(
    (Array.isArray(item.nodes) ? item.nodes : []).map(normalizeNode),
    (item.input_schema || {}) as JsonMap,
    (item.output_schema || {}) as JsonMap,
  )
  edges.value = (Array.isArray(item.edges) ? item.edges : []).map(normalizeEdge)
  selectedNodeId.value = nodes.value[0]?.nodeId || ''
}

function graphPoint(event: PointerEvent | DragEvent) {
  const canvas = graphCanvasRef.value
  if (!canvas) return { x: 70, y: 60 }
  const rect = canvas.getBoundingClientRect()
  return {
    x: Math.max(20, event.clientX - rect.left + canvas.scrollLeft),
    y: Math.max(20, event.clientY - rect.top + canvas.scrollTop),
  }
}

function startNodeDrag(event: PointerEvent, node: JsonMap) {
  if (event.button !== 0) return
  const point = graphPoint(event)
  const position = (node.position || { x: 0, y: 0 }) as JsonMap
  selectedNodeId.value = String(node.nodeId)
  dragState.value = {
    nodeId: String(node.nodeId),
    offsetX: point.x - Number(position.x || 0),
    offsetY: point.y - Number(position.y || 0),
  }
  window.addEventListener('pointermove', moveNode)
  window.addEventListener('pointerup', stopNodeDrag)
}

function moveNode(event: PointerEvent) {
  if (!dragState.value) return
  const node = nodes.value.find((item) => String(item.nodeId) === dragState.value?.nodeId)
  if (!node) return
  const point = graphPoint(event)
  node.position = {
    x: Math.max(20, point.x - dragState.value.offsetX),
    y: Math.max(20, point.y - dragState.value.offsetY),
  }
}

function stopNodeDrag() {
  dragState.value = null
  window.removeEventListener('pointermove', moveNode)
  window.removeEventListener('pointerup', stopNodeDrag)
}

function startPaletteDrag(event: DragEvent, type: string) {
  paletteDragType.value = type
  event.dataTransfer?.setData('text/workflow-node', type)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}

function dropPaletteNode(event: DragEvent) {
  const type = event.dataTransfer?.getData('text/workflow-node') || paletteDragType.value
  paletteDragType.value = ''
  if (type) addNode(type, graphPoint(event))
}

function startConnection(event: PointerEvent, node: JsonMap, port: JsonMap) {
  const point = graphPoint(event)
  connectionState.value = { sourceId: String(node.nodeId), sourcePortId: String(port.portId || 'value'), x: point.x, y: point.y }
  window.addEventListener('pointermove', moveConnection)
  window.addEventListener('pointerup', cancelConnection)
}

function moveConnection(event: PointerEvent) {
  if (!connectionState.value) return
  const point = graphPoint(event)
  connectionState.value = { ...connectionState.value, x: point.x, y: point.y }
}

function finishConnection(event: PointerEvent, target: JsonMap, port: JsonMap) {
  event.stopPropagation()
  const sourceId = connectionState.value?.sourceId
  const sourcePortId = connectionState.value?.sourcePortId || 'value'
  const targetId = String(target.nodeId)
  const targetPortId = String(port.portId || 'value')
  if (!sourceId || sourceId === targetId) {
    cancelConnection()
    return
  }
  const sourceIndex = nodes.value.findIndex((node) => String(node.nodeId) === sourceId)
  const targetIndex = nodes.value.findIndex((node) => String(node.nodeId) === targetId)
  if (targetIndex < 0 || sourceIndex < 0) return cancelConnection()
  const source = nodes.value.find((node) => String(node.nodeId) === sourceId)
  if (!source) return cancelConnection()
  const sourcePort = nodeOutputPorts(source).find((item) => String(item.portId) === sourcePortId)
  const targetPort = nodeInputPorts(target).find((item) => String(item.portId) === targetPortId)
  if (!sourcePort || String(sourcePort.direction || 'output').toLowerCase() !== 'output') {
    notifyError('连线必须从输出端口开始')
    cancelConnection()
    return
  }
  if (!targetPort || String(targetPort.direction || 'input').toLowerCase() !== 'input') {
    notifyError('连线必须连接到输入端口')
    cancelConnection()
    return
  }
  if (!edges.value.some((edge) => String((edge.from as JsonMap)?.nodeId) === sourceId
      && String((edge.from as JsonMap)?.portId) === sourcePortId
      && String((edge.to as JsonMap)?.nodeId) === targetId
      && String((edge.to as JsonMap)?.portId) === targetPortId)) {
    edges.value.push({
      edgeId: `edge_${Date.now()}_${edges.value.length + 1}`,
      from: { nodeId: sourceId, portId: sourcePortId },
      to: { nodeId: targetId, portId: targetPortId },
      kind: 'data',
      binding: {},
      mapping: [],
      condition: {},
      defaultEdge: true,
    })
  }
  selectedNodeId.value = sourceId
  cancelConnection()
}

function cancelConnection() {
  connectionState.value = null
  window.removeEventListener('pointermove', moveConnection)
  window.removeEventListener('pointerup', cancelConnection)
}

function portCenterY(node: JsonMap, portId: string, input: boolean) {
  const ports = input ? nodeInputPorts(node) : nodeOutputPorts(node)
  const index = Math.max(0, ports.findIndex((port) => String(port.portId) === portId))
  return Number(((node.position || {}) as JsonMap).y || 0)
    + GRAPH_NODE_HEIGHT * ((index + 1) / (Math.max(1, ports.length) + 1))
}

function edgePath(edge: { from: string; fromPort: string; to: string; toPort: string }) {
  const from = nodes.value.find((node) => String(node.nodeId) === edge.from)
  const to = nodes.value.find((node) => String(node.nodeId) === edge.to)
  if (!from || !to) return ''
  const fromPosition = (from.position || {}) as JsonMap
  const toPosition = (to.position || {}) as JsonMap
  const x1 = Number(fromPosition.x || 0) + GRAPH_NODE_WIDTH
  const y1 = portCenterY(from, edge.fromPort, false)
  const x2 = Number(toPosition.x || 0)
  const y2 = portCenterY(to, edge.toPort, true)
  const direction = x2 >= x1 ? 1 : -1
  const bend = Math.max(45, Math.abs(x2 - x1) / 2)
  return `M ${x1} ${y1} C ${x1 + direction * bend} ${y1}, ${x2 - direction * bend} ${y2}, ${x2} ${y2}`
}

function connectionPath() {
  if (!connectionState.value) return ''
  const source = nodes.value.find((node) => String(node.nodeId) === connectionState.value?.sourceId)
  if (!source) return ''
  const position = (source.position || {}) as JsonMap
  const x1 = Number(position.x || 0) + GRAPH_NODE_WIDTH
  const y1 = portCenterY(source, connectionState.value.sourcePortId, false)
  const direction = connectionState.value.x >= x1 ? 1 : -1
  const bend = Math.max(45, Math.abs(connectionState.value.x - x1) / 2)
  return `M ${x1} ${y1} C ${x1 + direction * bend} ${y1}, ${connectionState.value.x - direction * bend} ${connectionState.value.y}, ${connectionState.value.x} ${connectionState.value.y}`
}

function graphNodeStyle(node: JsonMap) {
  const position = (node.position || {}) as JsonMap
  return {
    left: `${Number(position.x || 0)}px`,
    top: `${Number(position.y || 0)}px`,
  }
}

function upsertWorkflow(item: JsonMap) {
  const workflowId = String(item.workflow_id || '')
  if (!workflowId) return
  workflows.value = [item, ...workflows.value.filter((row) => String(row.workflow_id) !== workflowId)]
}

function clearEditor() {
  workflow.value = null
  selectedWorkflowId.value = ''
  nodes.value = []
  edges.value = []
  selectedNodeId.value = ''
  detailDrawerOpen.value = false
  canvasOpen.value = false
}

async function openWorkflow(workflowId: string) {
  detailDrawerOpen.value = true
  await selectWorkflow(workflowId)
}

function openCanvas() {
  if (!workflow.value || !selectedWorkflowId.value) return
  const url = new URL(window.location.href)
  url.searchParams.set('view', 'canvas')
  url.searchParams.set('workflow_id', selectedWorkflowId.value)
  const tab = window.open(url.toString(), '_blank', 'noopener,noreferrer')
  if (!tab) notifyError('浏览器拦截了新标签页，请允许本站打开新窗口')
}

function closeCanvas() {
  if (!saving.value && !publishing.value && !testing.value) canvasOpen.value = false
  if (standaloneCanvas && !saving.value && !publishing.value && !testing.value) {
    if (window.opener) {
      window.close()
      return
    }
    const url = new URL(window.location.href)
    url.searchParams.delete('view')
    url.searchParams.delete('workflow_id')
    window.location.href = url.toString()
  }
}

function closeDetailDrawer() {
  if (canvasOpen.value) return
  detailDrawerOpen.value = false
}

async function selectWorkflow(workflowId: string) {
  if (!workflowId) return
  selectedWorkflowId.value = workflowId
  loading.value = true
  try {
    const data = await api(`/platform/frontend/workflows/${encodeURIComponent(workflowId)}`)
    applyWorkflow(workflowItem(data))
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function createWorkflow() {
  if (creating.value) return
  const values = await formDialog({
    title: '新建 Workflow',
    message: '先创建流程资产，创建后可在画布中配置输入、输出节点和调用入口。',
    fields: [
      { key: 'name', label: 'Workflow 名称', placeholder: '例如：订单审核流程' },
      { key: 'description', label: '描述', type: 'textarea', placeholder: '这个流程解决什么业务问题？' },
    ],
    confirmLabel: '创建 Workflow',
  })
  if (!values) return
  const name = String(values.name || '').trim()
  if (!name) {
    notifyError('请输入 Workflow 名称')
    return
  }
  creating.value = true
  try {
    const data = await api('/platform/frontend/workflows', {
      method: 'POST',
      body: JSON.stringify({
        name,
        description: String(values.description || '').trim(),
        domain: currentDomain('platform'),
        nodes: [
          boundaryNode('workflow.input', 'workflow_input', { x: 70, y: 60 }),
          boundaryNode('workflow.output', 'workflow_output', { x: 430, y: 60 }),
        ],
      }),
    })
    const item = workflowItem(data)
    applyWorkflow(item)
    upsertWorkflow(item)
    await loadWorkflows()
    detailDrawerOpen.value = true
    notifySuccess('Workflow 已创建')
  } catch (error) {
    notifyError(error)
  } finally {
    creating.value = false
  }
}

function addNode(type = 'agent.invoke', position?: { x: number; y: number }) {
  if (type === 'workflow.input' || type === 'workflow.output') {
    const nodeId = `${type === 'workflow.input' ? 'workflow_input' : 'workflow_output'}_${nodes.value.filter((node) => node.type === type).length + 1}`
    nodes.value.push(boundaryNode(type, nodeId, position || {
      x: 70 + (nodes.value.length % 3) * 280,
      y: 60 + Math.floor(nodes.value.length / 3) * 180,
    }))
    selectedNodeId.value = nodeId
    return
  }
  const node: JsonMap = {
    nodeId: `node_${nodes.value.length + 1}`,
    type,
    refId: '',
    instruction: '',
    config: type === 'http.request' ? { method: 'POST', url: '', body: '{{input}}' } : {},
    inputMapping: {},
    outputSchema: {},
    timeoutMs: null,
    maxRetries: 0,
    failurePolicy: 'FAIL_FAST',
    inputPorts: defaultPorts(type, true),
    outputPorts: defaultPorts(type, false),
    position: position || {
      x: 70 + (nodes.value.length % 3) * 280,
      y: 60 + Math.floor(nodes.value.length / 3) * 180,
    },
  }
  nodes.value.push(node)
  selectedNodeId.value = String(node.nodeId)
}

function removeSelectedNode() {
  const index = nodes.value.findIndex((node) => node.nodeId === selectedNodeId.value)
  if (index < 0) return
  nodes.value.splice(index, 1)
  edges.value = edges.value.filter((edge) => {
    const from = (edge.from || {}) as JsonMap
    const to = (edge.to || {}) as JsonMap
    return String(from.nodeId) !== selectedNodeId.value && String(to.nodeId) !== selectedNodeId.value
  })
  selectedNodeId.value = nodes.value[Math.max(0, index - 1)]?.nodeId || ''
}

function normalizeNodeType(node: JsonMap) {
  if (node.type === 'workflow.input' || node.type === 'workflow.output') {
    node.refId = ''
    node.instruction = ''
    node.config = { ...((node.config || {}) as JsonMap), schema: (((node.config || {}) as JsonMap).schema || {}) as JsonMap }
  }
  node.inputPorts = normalizePorts(node.inputPorts, String(node.type || 'agent.invoke'), true, (node.config || {}) as JsonMap)
  node.outputPorts = normalizePorts(node.outputPorts, String(node.type || 'agent.invoke'), false, (node.config || {}) as JsonMap)
}

function addPort(node: JsonMap, input: boolean) {
  const key = input ? 'inputPorts' : 'outputPorts'
  if (!Array.isArray(node[key])) node[key] = []
  ;(node[key] as JsonMap[]).push({
    portId: `${input ? 'input' : 'output'}_${(node[key] as JsonMap[]).length + 1}`,
    direction: input ? 'input' : 'output',
    contractRef: '',
    schema: {},
    required: input,
    cardinality: 'one',
    description: '',
  })
}

function removePort(node: JsonMap, input: boolean, index: number) {
  const key = input ? 'inputPorts' : 'outputPorts'
  const ports = (node[key] || []) as JsonMap[]
  const removed = ports[index]
  ports.splice(index, 1)
  const portId = String(removed?.portId || '')
  if (!portId) return
  edges.value = edges.value.filter((edge) => {
    const from = (edge.from || {}) as JsonMap
    const to = (edge.to || {}) as JsonMap
    return !(input
      ? String(to.nodeId) === String(node.nodeId) && String(to.portId) === portId
      : String(from.nodeId) === String(node.nodeId) && String(from.portId) === portId)
  })
}

function updateSelectedNodeId(node: JsonMap, value: string) {
  const next = String(value || '').trim()
  const previous = String(node.nodeId || '')
  if (!next || next === previous || nodes.value.some((item) => item !== node && String(item.nodeId) === next)) {
    node.nodeId = previous
    return
  }
  node.nodeId = next
  edges.value.forEach((edge) => {
    const from = (edge.from || {}) as JsonMap
    const to = (edge.to || {}) as JsonMap
    if (String(from.nodeId) === previous) from.nodeId = next
    if (String(to.nodeId) === previous) to.nodeId = next
  })
  selectedNodeId.value = next
}

function moveSelected(direction: number) {
  const index = nodes.value.findIndex((node) => node.nodeId === selectedNodeId.value)
  const target = index + direction
  if (index < 0 || target < 0 || target >= nodes.value.length) return
  const current = nodes.value[index]
  nodes.value[index] = nodes.value[target]
  nodes.value[target] = current
}

function cleanNodeConfig(node: JsonMap): JsonMap {
  const config = { ...((node.config || {}) as JsonMap) }
  delete config.schema_text
  config.canvas_position = { ...((node.position || {}) as JsonMap) }
  return config
}

function cleanNodes() {
  return nodes.value.map((node) => ({
    nodeId: String(node.nodeId || '').trim(),
    type: String(node.type || 'agent.invoke'),
    refId: String(node.refId || '').trim(),
    instruction: String(node.instruction || '').trim(),
    config: cleanNodeConfig(node),
    inputMapping: (node.inputMapping || {}) as JsonMap,
    outputSchema: (node.outputSchema || {}) as JsonMap,
    timeoutMs: node.timeoutMs || null,
    maxRetries: Number(node.maxRetries || 0),
    failurePolicy: node.failurePolicy || 'FAIL_FAST',
    inputPorts: normalizePorts(node.inputPorts, String(node.type || 'agent.invoke'), true, (node.config || {}) as JsonMap),
    outputPorts: normalizePorts(node.outputPorts, String(node.type || 'agent.invoke'), false, (node.config || {}) as JsonMap),
  })).filter((node) => node.nodeId)
}

function cleanEdges() {
  return edges.value
    .map((edge) => {
      const from = (edge.from || {}) as JsonMap
      const to = (edge.to || {}) as JsonMap
      const binding: JsonMap = {}
      for (const mapping of ((edge.mapping || []) as JsonMap[])) {
        const target = String(mapping.targetField || '').trim()
        const source = String(mapping.sourcePath || '').trim()
        if (target && source) binding[target] = source
      }
      return {
        edgeId: String(edge.edgeId || '').trim(),
        from: { nodeId: String(from.nodeId || '').trim(), portId: String(from.portId || 'value').trim() },
        to: { nodeId: String(to.nodeId || '').trim(), portId: String(to.portId || 'value').trim() },
        kind: String(edge.kind || 'data'),
        binding,
        condition: ((edge.condition || {}) as JsonMap),
        defaultEdge: edge.defaultEdge === true,
      }
    })
    .filter((edge) => edge.edgeId && edge.from.nodeId && edge.to.nodeId && edge.from.portId && edge.to.portId)
}

function addEdgeMapping(edge: JsonMap) {
  if (!Array.isArray(edge.mapping)) edge.mapping = []
  ;(edge.mapping as JsonMap[]).push({ targetField: '', sourcePath: '$.' })
}

function removeEdgeMapping(edge: JsonMap, index: number) {
  ;((edge.mapping || []) as JsonMap[]).splice(index, 1)
}

function removeEdge(edge: JsonMap) {
  edges.value = edges.value.filter((item) => item !== edge && String(item.edgeId) !== String(edge.edgeId))
}

function portStyle(_node: JsonMap, index: number, count: number) {
  return { top: `${100 * ((index + 1) / (Math.max(1, count) + 1))}%` }
}

function schemaText(text: string, label: string): JsonMap {
  try {
    const value = JSON.parse(text || '{}')
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
  } catch {
    throw new Error(`${label}必须是合法 JSON`)
  }
}

function boundarySchemaText(node: JsonMap): string {
  const config = (node.config || {}) as JsonMap
  return typeof config.schema_text === 'string' ? config.schema_text : JSON.stringify(config.schema || {}, null, 2)
}

function updateBoundarySchema(node: JsonMap, value: string) {
  node.config = { ...((node.config || {}) as JsonMap), schema_text: value }
  try {
    node.config.schema = schemaText(value, '节点 Schema')
    if (node.type === 'workflow.input') {
      node.outputPorts = ((node.outputPorts || []) as JsonMap[]).map((port) => ({ ...port, schema: node.config.schema }))
    } else if (node.type === 'workflow.output') {
      node.inputPorts = ((node.inputPorts || []) as JsonMap[]).map((port) => ({ ...port, schema: node.config.schema }))
    }
  } catch {
    // Keep the draft text so the save action can show the JSON error.
  }
}

function updateBoundarySchemaFromEvent(node: JsonMap, event: Event) {
  updateBoundarySchema(node, (event.target as HTMLTextAreaElement).value)
}

function boundarySchema(type: 'workflow.input' | 'workflow.output'): JsonMap {
  const node = nodes.value.find((item) => item.type === type)
  if (!node) return {}
  const config = (node.config || {}) as JsonMap
  return typeof config.schema_text === 'string'
    ? schemaText(config.schema_text, type === 'workflow.input' ? '输入节点 Schema' : '输出节点 Schema')
    : ((config.schema || {}) as JsonMap)
}

function boundaryNodeLabel(type: 'workflow.input' | 'workflow.output'): string {
  return nodes.value.find((node) => node.type === type)?.nodeId || '未配置'
}

async function saveWorkflow(showNotice = true) {
  if (!workflow.value || !selectedWorkflowId.value) return false
  saving.value = true
  try {
    const clean = cleanNodes()
    const data = await api(`/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}`, {
      method: 'PUT',
      body: JSON.stringify({
        workflow_id: selectedWorkflowId.value,
        name: String(workflow.value.name || '').trim(),
        description: String(workflow.value.description || '').trim(),
        domain: workflow.value.domain || currentDomain('platform'),
        trigger_type: workflow.value.trigger_type || 'manual',
        status: 'DRAFT',
        input_schema: boundarySchema('workflow.input'),
        output_schema: boundarySchema('workflow.output'),
        nodes: clean,
        edges: cleanEdges(),
      }),
    })
    const item = workflowItem(data)
    applyWorkflow(item)
    upsertWorkflow(item)
    nodes.value = clean.map(normalizeNode)
    if (showNotice) notifySuccess('Workflow 草稿已保存')
    return true
  } catch (error) {
    notifyError(error)
    return false
  } finally {
    saving.value = false
  }
}

async function validateWorkflow() {
  if (!workflow.value || !selectedWorkflowId.value || validating.value) return
  validating.value = true
  try {
    const data = await api(`/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}/validate`, {
      method: 'POST',
      body: JSON.stringify({
        name: String(workflow.value.name || '').trim(),
        description: String(workflow.value.description || '').trim(),
        domain: workflow.value.domain || currentDomain('platform'),
        trigger_type: workflow.value.trigger_type || 'manual',
        input_schema: boundarySchema('workflow.input'),
        output_schema: boundarySchema('workflow.output'),
        nodes: cleanNodes(),
        edges: cleanEdges(),
      }),
    })
    validation.value = data
    if (data.valid === false) notifyError('Workflow 契约校验未通过，请查看右侧诊断')
    else notifySuccess('Workflow 契约校验通过')
  } catch (error) {
    notifyError(error)
  } finally {
    validating.value = false
  }
}

async function publishWorkflow() {
  if (!workflow.value) return
  if (!(await saveWorkflow(false))) return
  publishing.value = true
  try {
    const data = await api(`/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}/publish`, { method: 'POST' })
    const item = workflowItem(data)
    applyWorkflow(item)
    upsertWorkflow(item)
    notifySuccess('Workflow 已发布')
  } catch (error) {
    notifyError(error)
  } finally {
    publishing.value = false
  }
}

async function unpublishWorkflow() {
  if (!workflow.value) return
  try {
    const data = await api(`/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}/unpublish`, { method: 'POST' })
    const item = workflowItem(data)
    applyWorkflow(item)
    upsertWorkflow(item)
    notifySuccess('Workflow 已退回草稿')
  } catch (error) {
    notifyError(error)
  }
}

async function deleteWorkflow() {
  if (!workflow.value || !window.confirm(`确定删除 Workflow「${workflow.value.name}」吗？`)) return
  try {
    await api(`/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}`, { method: 'DELETE' })
    detailDrawerOpen.value = false
    canvasOpen.value = false
    clearEditor()
    await loadWorkflows()
    notifySuccess('Workflow 已删除')
  } catch (error) {
    notifyError(error)
  }
}

async function runWorkflow() {
  if (!workflow.value || !isPublished.value) {
    notifyError('请先发布 Workflow 后再运行')
    return
  }
  if (!testInput.value.trim()) {
    notifyError('请输入测试请求')
    return
  }
  testing.value = true
  testOutput.value = ''
  testEvents.value = []
  try {
    const response = await fetch(`/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}/run/stream`, {
      method: 'POST',
      headers: headers(true),
      body: JSON.stringify({ query: testInput.value.trim(), session_id: `workflow_${Date.now()}` }),
    })
    if (!response.ok || !response.body) throw new Error(`HTTP ${response.status}`)
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''
      for (const part of parts) {
        const line = part.split('\n').find((item) => item.startsWith('data:'))
        if (!line) continue
        const event = JSON.parse(line.slice(5).trim()) as JsonMap
        testEvents.value.push(event)
        if (event.type === 'token') testOutput.value += String(event.delta || '')
        if (event.type === 'done') testOutput.value = String((event.result as JsonMap)?.answer || testOutput.value)
        if (event.type === 'error') throw new Error(String(event.message || '运行失败'))
      }
    }
  } catch (error) {
    notifyError(error)
  } finally {
    testing.value = false
  }
}

onMounted(async () => {
  if (standaloneCanvas && requestedWorkflowId) selectedWorkflowId.value = requestedWorkflowId
  await loadWorkflows()
  if (standaloneCanvas && workflow.value) canvasOpen.value = true
})
onUnmounted(() => {
  stopNodeDrag()
  cancelConnection()
})
</script>

<template>
  <section :class="['orchestration-page', { 'standalone-orchestration-page': standaloneCanvas }]">
    <template v-if="!standaloneCanvas">
    <header class="orchestration-header">
      <div>
        <div class="eyebrow">ORCHESTRATION CENTER</div>
        <h1>编排工作台</h1>
        <p>Workflow 是独立平台资产，可被手动、API 或 Agent Tool 调用。</p>
      </div>
      <button class="btn btn-primary" :disabled="creating" @click="createWorkflow">{{ creating ? '创建中…' : '＋ 新建 Workflow' }}</button>
    </header>

    <section class="asset-library panel">
      <div class="library-toolbar">
        <div><div class="panel-title">Workflow 资产</div><p class="panel-hint">独立保存，不再挂在 Agent 配置下；点击资产查看详情。</p></div>
        <span class="library-count">{{ workflows.length }} 个 Workflow</span>
      </div>
      <div v-if="loading && !workflows.length" class="library-empty">正在加载 Workflow…</div>
      <div v-else-if="!workflows.length" class="library-empty"><div class="empty-icon">＋</div><strong>还没有 Workflow</strong><span>点击右上角“新建 Workflow”开始创建。</span></div>
      <div v-else class="workflow-list">
        <button v-for="item in workflows" :key="String(item.workflow_id)" class="workflow-list-item" :class="{selected: item.workflow_id === selectedWorkflowId}" @click="openWorkflow(String(item.workflow_id))">
          <span class="workflow-list-icon">↯</span>
          <span class="asset-item-main"><strong>{{ item.name || item.workflow_id }}</strong><small>{{ item.workflow_id }} · {{ item.node_count || 0 }} 个节点 · {{ item.trigger_type || 'manual' }}</small></span>
          <span class="workflow-list-meta"><span class="asset-status" :class="item.status === 'PUBLISHED' ? 'published' : 'draft'">{{ item.status === 'PUBLISHED' ? '已发布' : '草稿' }}</span><span class="workflow-list-arrow">查看详情 ›</span></span>
        </button>
      </div>
    </section>

    <section class="orchestration-note panel"><strong>资产生命周期</strong><span>草稿可编辑；发布后才会出现在 Flow 目录，并作为后续 API / Agent Tool 的稳定入口。</span></section>

    <div v-if="detailDrawerOpen && workflow" class="drawer-backdrop" @click.self="closeDetailDrawer">
      <aside class="workflow-detail-drawer">
        <div class="drawer-head"><div><div class="eyebrow">WORKFLOW ASSET</div><h2>{{ workflow.name || workflow.workflow_id }}</h2><small>{{ workflow.workflow_id }}</small></div><button class="btn btn-ghost btn-sm" @click="closeDetailDrawer">关闭</button></div>
        <div class="drawer-body">
          <div class="drawer-status-row"><span class="status-pill" :class="isPublished ? 'published' : 'draft'">{{ isPublished ? '已发布' : '草稿' }}</span><span>v{{ workflow.version || 1 }} · {{ nodes.length }} 个节点</span><button class="btn btn-ghost btn-sm" :disabled="loading" @click="selectWorkflow(selectedWorkflowId)">刷新</button></div>
          <div class="detail-grid"><div><small>触发方式</small><strong>{{ workflow.trigger_type || 'manual' }}</strong></div><div><small>业务域</small><strong>{{ workflow.domain || currentDomain('platform') }}</strong></div><div><small>更新时间</small><strong>{{ workflow.updated_at || '—' }}</strong></div><div><small>调用入口</small><strong>{{ isPublished ? 'API / Agent Tool' : '发布后可用' }}</strong></div></div>
          <div class="drawer-section"><div class="drawer-section-title">描述</div><p>{{ workflow.description || '暂无描述' }}</p></div>
          <div class="drawer-section"><div class="drawer-section-title">节点概览 <span>{{ nodes.length }}</span></div><div v-if="!nodes.length" class="drawer-empty">还没有节点，打开画布开始编排。</div><div v-else class="drawer-node-list"><div v-for="(node, index) in nodes" :key="String(node.nodeId)" class="drawer-node-item"><span>{{ index + 1 }}</span><div><strong>{{ node.nodeId }}</strong><small>{{ nodeTypeLabel(String(node.type)) }}<template v-if="node.refId"> · {{ node.refId }}</template></small></div></div></div></div>
          <div class="drawer-section"><div class="drawer-section-title">边界节点</div><p>输入：{{ boundaryNodeLabel('workflow.input') }} · 输出：{{ boundaryNodeLabel('workflow.output') }}</p></div>
        </div>
        <div class="drawer-actions"><button class="btn btn-ghost" @click="deleteWorkflow">删除</button><span></span><button v-if="!isPublished" class="btn btn-ghost" :disabled="publishing || saving" @click="publishWorkflow">{{ publishing ? '发布中…' : '发布' }}</button><button v-else class="btn btn-ghost" @click="unpublishWorkflow">退回草稿</button><button class="btn btn-primary" @click="openCanvas">新标签页打开画布</button></div>
      </aside>
    </div>
    </template>

    <div v-if="standaloneCanvas && !workflow" class="standalone-canvas-loading">{{ loading ? '正在加载 Workflow 画布…' : 'Workflow 不存在或已被删除' }}</div>
    <div v-if="canvasOpen && workflow" :class="['canvas-backdrop', { 'standalone-canvas-backdrop': standaloneCanvas }]">
      <section class="canvas-modal">
        <header class="canvas-modal-head"><div><div class="eyebrow">WORKFLOW CANVAS</div><h2>{{ workflow.name || workflow.workflow_id }}</h2><span>{{ workflow.trigger_type || 'manual' }} · {{ nodes.length }} 个节点</span></div><div class="canvas-modal-actions"><span class="status-pill" :class="isPublished ? 'published' : 'draft'">{{ isPublished ? '已发布' : '草稿' }}</span><button class="btn btn-ghost" :disabled="saving" @click="saveWorkflow()">{{ saving ? '保存中…' : '保存草稿' }}</button><button class="btn btn-ghost" :disabled="validating" @click="validateWorkflow">{{ validating ? '校验中…' : '校验契约' }}</button><button v-if="!isPublished" class="btn btn-primary" :disabled="publishing" @click="publishWorkflow">{{ publishing ? '发布中…' : '发布' }}</button><button v-else class="btn btn-ghost" @click="unpublishWorkflow">退回草稿</button><button class="btn btn-ghost" :disabled="testing || !isPublished" @click="runWorkflow">{{ testing ? '运行中…' : '运行测试' }}</button><button class="btn btn-ghost" @click="closeCanvas">关闭画布</button></div></header>
        <div class="canvas-editor-layout">
          <aside class="node-palette panel"><div class="panel-title">节点库</div><p class="panel-hint">点击或拖拽节点加入画布</p><div v-for="group in ['流程', '业务', 'AI', '控制']" :key="group" class="palette-group"><div class="palette-group-title">{{ group }}</div><button v-for="nodeType in NODE_TYPES.filter((node) => node.group === group)" :key="nodeType.value" class="palette-node" draggable="true" @dragstart="startPaletteDrag($event, nodeType.value)" @click="addNode(nodeType.value)"><span class="palette-icon">＋</span>{{ nodeType.label }}</button></div></aside>
          <main class="workflow-canvas panel"><div class="canvas-toolbar"><div><strong>{{ workflow.name || '未选择 Workflow' }}</strong><span class="canvas-meta">{{ workflow.trigger_type || 'manual' }} · {{ nodes.length }} 个节点</span></div><span class="canvas-status">{{ loading ? '加载中' : '拖拽编排' }}</span></div><div v-if="!nodes.length" class="canvas-empty" @dragover.prevent @drop="dropPaletteNode"><div class="empty-icon">＋</div><strong>从左侧节点库拖入节点</strong><span>拖动节点定位；从节点右侧端口连到下一个节点。</span></div><div v-else ref="graphCanvasRef" class="graph-canvas" @dragover.prevent @drop="dropPaletteNode" @pointerup="cancelConnection"><div class="graph-surface" :style="{ width: `${graphSize.width}px`, height: `${graphSize.height}px` }" @pointermove="moveConnection"><svg class="graph-edges" :width="graphSize.width" :height="graphSize.height" :viewBox="`0 0 ${graphSize.width} ${graphSize.height}`" aria-hidden="true"><defs><marker id="workflow-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 Z" fill="#94a3b8" /></marker></defs><template v-for="edge in graphEdges" :key="edge.id"><path :d="edgePath(edge)" class="graph-edge" :class="{dashed: edge.dashed}" marker-end="url(#workflow-arrow)"><title>{{ edge.label }}</title></path></template><path v-if="connectionState" :d="connectionPath()" class="graph-edge graph-edge-preview" marker-end="url(#workflow-arrow)" /></svg><button v-for="(node, index) in nodes" :key="String(node.nodeId)" class="flow-node graph-node" :class="{selected: node.nodeId === selectedNodeId}" :style="graphNodeStyle(node)" @pointerdown.stop="startNodeDrag($event, node)" @click.stop="selectedNodeId = String(node.nodeId)"><span v-for="(port, portIndex) in nodeInputPorts(node)" :key="`in-${node.nodeId}-${port.portId}`" class="node-port node-port-in" :style="portStyle(node, portIndex, nodeInputPorts(node).length)" :title="`输入：${port.portId}`" @pointerup.stop="finishConnection($event, node, port)"></span><span class="flow-index">{{ index + 1 }}</span><span class="flow-node-body"><strong>{{ node.nodeId }}</strong><small>{{ nodeTypeLabel(String(node.type)) }}<template v-if="node.refId"> · {{ node.refId }}</template></small></span><span v-for="(port, portIndex) in nodeOutputPorts(node)" :key="`out-${node.nodeId}-${port.portId}`" class="node-port node-port-out" :style="portStyle(node, portIndex, nodeOutputPorts(node).length)" :title="`输出：${port.portId}`" @pointerdown.stop="startConnection($event, node, port)"></span></button></div></div></main>
          <aside class="node-properties panel"><div class="panel-title">{{ selectedNode ? '节点属性' : 'Workflow 属性' }}</div><div v-if="selectedNode" class="properties-body"><div class="property-actions"><button class="btn btn-ghost btn-sm" @click="moveSelected(-1)">上移</button><button class="btn btn-ghost btn-sm" @click="moveSelected(1)">下移</button><button class="btn btn-danger btn-sm" @click="removeSelectedNode">删除</button></div><label>节点 ID<input v-model="selectedNode.nodeId" /></label><label>节点类型<select v-model="selectedNode.type" @change="normalizeNodeType(selectedNode)"><option v-for="type in NODE_TYPES" :key="type.value" :value="type.value">{{ type.label }}</option></select></label><label v-if="['agent.invoke','agent.react','subflow.invoke'].includes(String(selectedNode.type))">目标 ID<input v-model="selectedNode.refId" placeholder="Agent 或 Workflow ID" /></label><label v-else-if="selectedNode.type === 'llm.chat'">模型 ID<input v-model="selectedNode.refId" placeholder="留空使用平台默认模型" /></label><label v-if="isBoundaryNode(selectedNode)">{{ selectedNode.type === 'workflow.input' ? '输入 Schema' : '输出 Schema' }}<textarea :value="boundarySchemaText(selectedNode)" @input="updateBoundarySchemaFromEvent(selectedNode, $event)" rows="6" spellcheck="false"></textarea></label><label v-else>节点指令<textarea v-model="selectedNode.instruction" rows="4" placeholder="传给节点的业务指令或提示"></textarea></label><template v-if="selectedNode.type === 'http.request'"><label>请求 URL<input v-model="selectedNode.config.url" placeholder="https://api.example.com/orders" /></label><label>请求方法<select v-model="selectedNode.config.method"><option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option></select></label><label>请求体<textarea v-model="selectedNode.config.body" rows="3" placeholder="{{input}}"></textarea></label></template><div class="property-section"><div class="property-section-head"><strong>输入端口</strong><button class="btn btn-ghost btn-sm" @click="addPort(selectedNode, true)">添加</button></div><div v-for="(port, index) in nodeInputPorts(selectedNode)" :key="`input-port-${index}`" class="port-row"><input v-model="port.portId" placeholder="端口 ID" /><input v-model="port.contractRef" placeholder="Contract ID（可选）" /><label class="checkbox"><input type="checkbox" v-model="port.required" /> 必填</label><button class="icon-button" @click="removePort(selectedNode, true, index)">×</button></div><div class="property-section-head"><strong>输出端口</strong><button class="btn btn-ghost btn-sm" @click="addPort(selectedNode, false)">添加</button></div><div v-for="(port, index) in nodeOutputPorts(selectedNode)" :key="`output-port-${index}`" class="port-row"><input v-model="port.portId" placeholder="端口 ID" /><input v-model="port.contractRef" placeholder="Contract ID（可选）" /><button class="icon-button" @click="removePort(selectedNode, false, index)">×</button></div></div><div v-if="selectedNodeEdges.length" class="property-section"><div class="property-section-head"><strong>连线</strong><span class="panel-hint">连线只由端口操作产生</span></div><div v-for="edge in selectedNodeEdges" :key="String(edge.edgeId)" class="edge-mapping-block"><small>{{ edge.from.nodeId }}.{{ edge.from.portId }} → {{ edge.to.nodeId }}.{{ edge.to.portId }}</small><button class="btn btn-ghost btn-sm" @click="addEdgeMapping(edge)">添加映射</button><button class="btn btn-danger btn-sm" @click="removeEdge(edge)">删除连线</button><div v-for="(mapping, index) in (edge.mapping as JsonMap[])" :key="`${edge.edgeId}-${index}`" class="port-row"><input v-model="mapping.targetField" placeholder="目标字段" /><input v-model="mapping.sourcePath" placeholder="$.source.field" /><button class="icon-button" @click="removeEdgeMapping(edge, index)">×</button></div></div></div></div><div v-else class="properties-body"><label>Workflow 名称<input v-model="workflow.name" /></label><label>描述<textarea v-model="workflow.description" rows="3" placeholder="这个流程解决什么业务问题？"></textarea></label><label>触发方式<select v-model="workflow.trigger_type"><option value="manual">手动</option><option value="api">API</option><option value="chat">对话</option><option value="webhook">Webhook</option><option value="schedule">定时</option></select></label></div></aside>
        </div>
        <section class="workflow-test panel"><div class="test-head"><div><div class="panel-title">独立 Workflow 运行测试</div><p class="panel-hint">调用已发布 Workflow 的独立运行入口，结果会写入运行观测。</p></div><button class="btn btn-primary" :disabled="testing || !isPublished" @click="runWorkflow">{{ testing ? '运行中…' : '运行 Workflow' }}</button></div><div class="test-grid"><textarea v-model="testInput" placeholder="输入测试请求，例如：查询订单 10086"></textarea><div class="test-result"><div v-if="testOutput" class="result-answer">{{ testOutput }}</div><div v-else class="properties-empty">运行结果会显示在这里。</div><details v-if="testEvents.length"><summary>查看运行事件（{{ testEvents.length }}）</summary><pre>{{ JSON.stringify(testEvents, null, 2) }}</pre></details></div></div></section>
      </section>
    </div>
  </section>
</template>

<style scoped>
.orchestration-page{padding:24px 28px 40px;min-height:100%;background:#f8fafc;color:#0f172a}.orchestration-header{display:flex;justify-content:space-between;gap:24px;align-items:flex-end;margin-bottom:20px}.eyebrow{font-size:10px;letter-spacing:.14em;font-weight:800;color:#2563eb;margin-bottom:7px}h1{font-size:26px;line-height:1.15;margin:0 0 7px;letter-spacing:-.03em}.orchestration-header p{margin:0;color:#64748b;font-size:13px}.orchestration-actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap}.status-pill,.asset-status{font-size:11px;border-radius:999px;padding:4px 8px}.status-pill.published,.asset-status.published{background:#dcfce7;color:#15803d}.status-pill.draft,.asset-status.draft{background:#fef3c7;color:#a16207}.panel{background:#fff;border:1px solid #e2e8f0;border-radius:14px;box-shadow:0 4px 18px rgba(15,23,42,.04)}.orchestration-layout{display:grid;grid-template-columns:250px minmax(380px,1fr) 310px;gap:14px;align-items:stretch}.asset-sidebar{display:flex;flex-direction:column;gap:14px;min-width:0}.asset-panel,.node-palette,.node-properties{padding:17px}.panel-title{font-size:14px;font-weight:800}.panel-hint{font-size:11px;color:#94a3b8;margin:5px 0 12px}.create-row{display:flex;gap:6px;margin-bottom:12px}.create-row input,.properties-body input,.properties-body select,.properties-body textarea,.transition-row input,.transition-row select,.orchestration-actions select{width:100%;border:1px solid #cbd5e1;border-radius:7px;padding:8px;font:inherit;font-size:12px;color:#0f172a;background:#fff}.asset-item{width:100%;display:flex;align-items:center;gap:7px;text-align:left;border:1px solid transparent;background:#f8fafc;border-radius:8px;padding:9px;margin-bottom:6px;cursor:pointer}.asset-item:hover,.asset-item.selected{border-color:#93c5fd;background:#eff6ff}.asset-item-main{display:flex;flex-direction:column;gap:3px;min-width:0;flex:1}.asset-item-main strong{font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.asset-item-main small{font-size:10px;color:#94a3b8;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.palette-group{margin-top:14px}.palette-group-title{font-size:10px;color:#94a3b8;text-transform:uppercase;letter-spacing:.08em;margin-bottom:7px}.palette-node{width:100%;display:flex;align-items:center;gap:8px;border:1px solid #e2e8f0;background:#f8fafc;border-radius:8px;padding:9px 10px;margin-bottom:7px;color:#334155;text-align:left;cursor:pointer}.palette-node:hover{border-color:#93c5fd;background:#eff6ff}.palette-icon{font-size:16px;color:#2563eb;font-weight:700}.workflow-canvas{min-height:620px;background:linear-gradient(#fff,#f8fbff);overflow:hidden}.canvas-toolbar{padding:15px 18px;border-bottom:1px solid #e2e8f0;display:flex;justify-content:space-between;align-items:center}.canvas-meta{margin-left:10px;color:#94a3b8;font-size:11px}.canvas-status{font-size:11px;color:#2563eb;background:#eff6ff;padding:4px 8px;border-radius:999px}.canvas-empty{min-height:530px;display:flex;flex-direction:column;justify-content:center;align-items:center;gap:8px;color:#64748b}.empty-icon{width:42px;height:42px;border-radius:12px;background:#eff6ff;color:#2563eb;display:grid;place-items:center;font-size:25px}.canvas-empty strong{color:#334155}.canvas-empty span{font-size:12px}.node-flow{padding:28px 15%;display:flex;flex-direction:column;align-items:stretch}.flow-node{display:flex;align-items:center;gap:12px;text-align:left;background:#fff;border:1px solid #cbd5e1;border-radius:12px;padding:13px 14px;cursor:pointer;box-shadow:0 3px 9px rgba(15,23,42,.05)}.flow-node:hover,.flow-node.selected{border-color:#2563eb;box-shadow:0 0 0 3px #dbeafe}.flow-index{width:25px;height:25px;border-radius:8px;background:#eff6ff;color:#2563eb;display:grid;place-items:center;font-size:11px;font-weight:800}.flow-node-body{display:flex;flex:1;min-width:0;flex-direction:column;gap:3px}.flow-node-body strong{font-size:13px}.flow-node-body small{font-size:11px;color:#64748b}.flow-chevron{font-size:22px;color:#94a3b8}.flow-edge{height:32px;display:grid;place-items:center;color:#94a3b8;font-size:18px}.properties-body{display:flex;flex-direction:column;gap:12px;margin-top:15px}.properties-body label{font-size:11px;color:#64748b;display:flex;flex-direction:column;gap:5px}.property-actions{display:flex;gap:5px;margin-bottom:4px}.property-section{border-top:1px solid #e2e8f0;padding-top:12px}.property-section-head{display:flex;justify-content:space-between;align-items:center;font-size:11px;margin-bottom:8px}.transition-row{display:grid;grid-template-columns:1fr 1fr;gap:5px;margin-bottom:6px}.transition-row .checkbox{grid-column:1 / 2;display:block;flex-direction:row;align-items:center}.checkbox input{width:auto}.icon-button{border:0;background:transparent;color:#ef4444;cursor:pointer;grid-column:2;grid-row:2}.properties-empty{color:#94a3b8;font-size:12px;padding:28px 8px;text-align:center}.orchestration-note{margin-top:14px;padding:14px 17px;display:flex;gap:10px;align-items:center;font-size:12px;color:#64748b}.orchestration-note strong{color:#334155}@media (max-width:1100px){.orchestration-layout{grid-template-columns:220px 1fr}.node-properties{grid-column:1 / -1}.orchestration-note{margin-top:14px}}@media (max-width:700px){.orchestration-page{padding:16px}.orchestration-header{display:block}.orchestration-actions{margin-top:14px}.orchestration-layout{grid-template-columns:1fr}.workflow-canvas{min-height:420px}.canvas-empty{min-height:360px}.node-flow{padding:24px 8%}.orchestration-note{align-items:flex-start;flex-direction:column}}
.workflow-test{margin-top:14px;padding:17px}.test-head{display:flex;justify-content:space-between;align-items:center}.test-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;margin-top:12px}.test-grid textarea{width:100%;min-height:100px;resize:vertical;border:1px solid #cbd5e1;border-radius:7px;padding:8px;font:inherit;font-size:12px}.test-result{border:1px solid #e2e8f0;border-radius:8px;padding:10px;min-height:100px;font-size:12px}.result-answer{white-space:pre-wrap;line-height:1.6}.test-result details{margin-top:12px;color:#64748b}.test-result pre{max-height:220px;overflow:auto;background:#f8fafc;padding:8px;font-size:10px}@media (max-width:700px){.test-grid{grid-template-columns:1fr}}
 .asset-library{padding:20px}.library-toolbar{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;border-bottom:1px solid #e2e8f0;padding-bottom:16px}.library-count{font-size:12px;color:#64748b;background:#f8fafc;border-radius:999px;padding:6px 10px}.workflow-list{display:flex;flex-direction:column;gap:8px;padding-top:16px}.workflow-list-item{width:100%;display:flex;align-items:center;gap:13px;text-align:left;border:1px solid #e2e8f0;background:#fff;border-radius:12px;padding:14px;cursor:pointer;transition:.15s}.workflow-list-item:hover,.workflow-list-item.selected{border-color:#93c5fd;background:#f8fbff;box-shadow:0 0 0 3px #eff6ff}.workflow-list-icon{width:34px;height:34px;border-radius:10px;display:grid;place-items:center;background:#eff6ff;color:#2563eb;font-size:20px;font-weight:700}.workflow-list-meta{display:flex;align-items:center;gap:14px;color:#64748b;font-size:11px}.workflow-list-arrow{white-space:nowrap}.library-empty{min-height:300px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:8px;color:#64748b}.library-empty strong{color:#334155}.library-empty span{font-size:12px}.drawer-backdrop,.canvas-backdrop{position:fixed;inset:0;background:rgba(15,23,42,.45);display:flex;justify-content:flex-end;z-index:1200;padding:0}.workflow-detail-drawer{width:min(480px,94vw);height:100%;background:#fff;box-shadow:-16px 0 48px rgba(15,23,42,.2);display:flex;flex-direction:column}.drawer-head{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;padding:22px 22px 18px;border-bottom:1px solid #e2e8f0}.drawer-head h2,.canvas-modal-head h2{font-size:19px;line-height:1.25;margin:2px 0 5px;color:#0f172a}.drawer-head small{font-size:11px;color:#94a3b8}.drawer-body{padding:18px 22px;overflow-y:auto;display:flex;flex-direction:column;gap:18px}.drawer-status-row{display:flex;align-items:center;gap:9px;color:#64748b;font-size:12px}.drawer-status-row button{margin-left:auto}.detail-grid{display:grid;grid-template-columns:1fr 1fr;gap:1px;background:#e2e8f0;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden}.detail-grid>div{display:flex;flex-direction:column;gap:5px;background:#f8fafc;padding:12px}.detail-grid small,.drawer-section-title,.drawer-schema small{font-size:11px;color:#94a3b8}.detail-grid strong{font-size:12px;color:#334155;word-break:break-word}.drawer-section{border-top:1px solid #e2e8f0;padding-top:15px}.drawer-section-title{font-weight:700;color:#334155;margin-bottom:9px}.drawer-section-title span{color:#2563eb;margin-left:4px}.drawer-section p{font-size:12px;color:#64748b;line-height:1.65;margin:0;white-space:pre-wrap}.drawer-empty{font-size:12px;color:#94a3b8;padding:15px 0}.drawer-node-list{display:flex;flex-direction:column;gap:7px}.drawer-node-item{display:flex;align-items:center;gap:10px;border:1px solid #e2e8f0;border-radius:8px;padding:9px}.drawer-node-item>span{width:22px;height:22px;border-radius:7px;background:#eff6ff;color:#2563eb;display:grid;place-items:center;font-size:11px;font-weight:700}.drawer-node-item div{display:flex;flex-direction:column;gap:3px;min-width:0}.drawer-node-item strong{font-size:12px;color:#334155}.drawer-node-item small{font-size:11px;color:#64748b;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.drawer-schema{border-top:1px solid #e2e8f0;padding-top:14px;color:#334155;font-size:12px}.drawer-schema>div{margin-top:12px}.drawer-schema pre{max-height:150px;overflow:auto;background:#f8fafc;border-radius:7px;padding:9px;font-size:10px;color:#475569}.drawer-actions{display:flex;align-items:center;gap:8px;padding:14px 22px;border-top:1px solid #e2e8f0}.drawer-actions span{flex:1}.canvas-backdrop{z-index:1300;align-items:center;justify-content:center;padding:24px}.canvas-modal{width:min(1500px,100%);max-height:calc(100vh - 48px);background:#f8fafc;border-radius:16px;box-shadow:0 24px 70px rgba(15,23,42,.28);display:flex;flex-direction:column;overflow:hidden}.canvas-modal-head{display:flex;align-items:center;justify-content:space-between;gap:18px;background:#fff;padding:16px 20px;border-bottom:1px solid #e2e8f0}.canvas-modal-head>div:first-child{min-width:0}.canvas-modal-head span{font-size:11px;color:#64748b}.canvas-modal-actions{display:flex;align-items:center;justify-content:flex-end;gap:7px;flex-wrap:wrap}.canvas-editor-layout{display:grid;grid-template-columns:220px minmax(400px,1fr) 300px;gap:12px;padding:12px;min-height:430px;overflow:hidden}.canvas-editor-layout>.node-palette,.canvas-editor-layout>.node-properties{overflow:auto}.canvas-editor-layout>.workflow-canvas{min-height:0;height:100%;overflow:auto}.canvas-modal .workflow-test{margin:0 12px 12px}.canvas-modal .canvas-empty{min-height:360px}.canvas-modal .node-flow{padding:24px 12%}@media (max-width:1000px){.canvas-editor-layout{grid-template-columns:190px minmax(300px,1fr)}.canvas-editor-layout>.node-properties{grid-column:1 / -1;max-height:280px}.canvas-modal{max-height:calc(100vh - 24px)}.canvas-modal-head{align-items:flex-start;flex-direction:column}.canvas-modal-actions{justify-content:flex-start}}@media (max-width:700px){.orchestration-page{padding:16px}.workflow-list-meta{gap:6px;flex-direction:column;align-items:flex-end}.canvas-backdrop{padding:8px}.canvas-editor-layout{grid-template-columns:1fr;overflow:auto}.canvas-editor-layout>.node-palette{max-height:180px}.canvas-editor-layout>.node-properties{grid-column:auto;max-height:none}.canvas-modal .workflow-test{margin:0 8px 8px}.drawer-actions{flex-wrap:wrap}.drawer-actions span{display:none}}
.standalone-orchestration-page{padding:0;min-height:100vh}.standalone-canvas-loading{min-height:100vh;display:grid;place-items:center;color:#64748b;background:#f8fafc}.standalone-canvas-backdrop{position:static;min-height:100vh;background:#f8fafc;padding:0}.standalone-canvas-backdrop .canvas-modal{width:100%;max-height:none;min-height:100vh;border-radius:0;box-shadow:none}
.canvas-editor-layout>.workflow-canvas{display:flex;flex-direction:column;min-height:0}.graph-canvas{position:relative;flex:1;min-height:390px;overflow:auto;background:#f8fbff;cursor:grab}.graph-canvas:active{cursor:grabbing}.graph-surface{position:relative;background-color:#f8fbff;background-image:linear-gradient(to right,rgba(148,163,184,.14) 1px,transparent 1px),linear-gradient(to bottom,rgba(148,163,184,.14) 1px,transparent 1px);background-size:24px 24px}.graph-edges{position:absolute;inset:0;overflow:visible;pointer-events:none;z-index:1}.graph-edge{fill:none;stroke:#94a3b8;stroke-width:2}.graph-edge.dashed{stroke-dasharray:7 5}.graph-edge-preview{stroke:#2563eb;stroke-dasharray:6 4}.graph-node{position:absolute;width:220px;min-height:112px;z-index:2;box-sizing:border-box;user-select:none}.graph-node.selected{border-color:#2563eb;box-shadow:0 0 0 3px #dbeafe,0 5px 16px rgba(37,99,235,.12)}.node-port{position:absolute;top:50%;width:12px;height:12px;border:2px solid #fff;border-radius:50%;background:#94a3b8;box-shadow:0 0 0 2px #cbd5e1;transform:translateY(-50%);z-index:3;cursor:crosshair}.node-port:hover{background:#2563eb;box-shadow:0 0 0 3px #bfdbfe}.node-port-in{left:-7px}.node-port-out{right:-7px;background:#2563eb;box-shadow:0 0 0 2px #93c5fd}.canvas-empty[draggable]{cursor:copy}
.port-row{display:grid;grid-template-columns:1fr 1.2fr auto auto;gap:5px;align-items:center;margin-bottom:7px}.port-row .checkbox{display:flex;flex-direction:row;align-items:center;white-space:nowrap}.port-row .checkbox input{width:auto}
.edge-mapping-block{margin:8px 0 10px;padding:8px;border:1px solid #e2e8f0;border-radius:8px;background:#f8fafc}.edge-mapping-block>small{display:block;color:#64748b;font-size:10px;margin-bottom:6px}.edge-mapping-block>.btn{margin-bottom:6px}
.graph-canvas .graph-node{transform:none}
</style>
