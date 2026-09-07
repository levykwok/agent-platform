<script setup lang="ts">
import { computed } from 'vue'
import type { JsonMap } from '../lib/platformApi'

type SchemaField = {
  path: string
  name: string
  type: string
  description: string
  depth: number
}

const props = defineProps<{
  node: JsonMap
  nodes: JsonMap[]
  edges: JsonMap[]
}>()

const emit = defineEmits<{
  removeEdge: [edge: JsonMap]
}>()

const incomingEdges = computed(() => props.edges.filter((edge) => endpoint(edge, 'to').nodeId === String(props.node.nodeId)))
const outgoingEdges = computed(() => props.edges.filter((edge) => endpoint(edge, 'from').nodeId === String(props.node.nodeId)))
const outputPorts = computed(() => (Array.isArray(props.node.outputPorts) ? props.node.outputPorts : []) as JsonMap[])

function objectValue(value: unknown): JsonMap {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonMap : {}
}

function endpoint(edge: JsonMap, side: 'from' | 'to'): { nodeId: string; portId: string } {
  const value = objectValue(edge[side])
  return { nodeId: String(value.nodeId || ''), portId: String(value.portId || 'value') }
}

function nodeById(nodeId: string): JsonMap | undefined {
  return props.nodes.find((node) => String(node.nodeId) === nodeId)
}

function nodeName(nodeId: string): string {
  return String(nodeById(nodeId)?.nodeId || nodeId)
}

function portFor(node: JsonMap | undefined, portId: string, output: boolean): JsonMap | undefined {
  const ports = (Array.isArray(node?.[output ? 'outputPorts' : 'inputPorts']) ? node?.[output ? 'outputPorts' : 'inputPorts'] : []) as JsonMap[]
  return ports.find((port) => String(port.portId || 'value') === portId)
}

function hasSchema(schema: JsonMap): boolean {
  return Boolean(schema.type || Object.keys(objectValue(schema.properties)).length)
}

function inferredTransformSchema(node: JsonMap): JsonMap {
  const config = objectValue(node.config)
  const mapping = objectValue(config.mapping)
  if (Object.keys(mapping).length) {
    return {
      type: 'object',
      properties: Object.fromEntries(Object.keys(mapping).map((field) => [field, { type: 'any', description: '由数据转换生成' }])),
    }
  }
  if (config.template) return { type: 'string', description: '由文本模板生成' }
  return {}
}

function effectiveOutputSchema(edge: JsonMap): JsonMap {
  const source = endpoint(edge, 'from')
  const node = nodeById(source.nodeId)
  if (!node) return {}
  if (String(node.type) === 'workflow.input') return objectValue(objectValue(node.config).schema)
  const portSchema = objectValue(portFor(node, source.portId, true)?.schema)
  if (hasSchema(portSchema)) return portSchema
  if (String(node.type) === 'data.transform') return inferredTransformSchema(node)
  return portSchema
}

function effectiveInputSchema(edge: JsonMap): JsonMap {
  const target = endpoint(edge, 'to')
  const node = nodeById(target.nodeId)
  if (!node) return {}
  if (String(node.type) === 'workflow.output') return objectValue(objectValue(node.config).schema)
  return objectValue(portFor(node, target.portId, false)?.schema)
}

function flattenSchema(schema: JsonMap, prefix = '$', depth = 0): SchemaField[] {
  const type = String(schema.type || (Object.keys(objectValue(schema.properties)).length ? 'object' : 'any'))
  const result: SchemaField[] = depth === 0
    ? [{ path: '$', name: '整个输出', type, description: String(schema.description || ''), depth: 0 }]
    : []
  if (depth >= 3) return result
  const properties = objectValue(schema.properties)
  for (const [name, raw] of Object.entries(properties)) {
    const fieldSchema = objectValue(raw)
    const path = `${prefix}.${name}`
    const fieldType = String(fieldSchema.type || 'any')
    result.push({ path, name, type: fieldType, description: String(fieldSchema.description || ''), depth: depth + 1 })
    if (fieldType === 'object') result.push(...flattenSchema(fieldSchema, path, depth + 1))
  }
  const items = objectValue(schema.items)
  if (type === 'array' && Object.keys(items).length) {
    const itemPath = `${prefix}[0]`
    result.push({ path: itemPath, name: '第一个元素', type: String(items.type || 'any'), description: String(items.description || ''), depth: depth + 1 })
    if (String(items.type) === 'object') result.push(...flattenSchema(items, itemPath, depth + 1))
  }
  return result
}

function sourceFields(edge: JsonMap): SchemaField[] {
  return flattenSchema(effectiveOutputSchema(edge)).slice(0, 40)
}

function targetFields(edge: JsonMap): { name: string; type: string; required: boolean }[] {
  const schema = effectiveInputSchema(edge)
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : [])
  return Object.entries(objectValue(schema.properties)).map(([name, value]) => ({
    name,
    type: String(objectValue(value).type || 'any'),
    required: required.has(name),
  }))
}

function schemaSource(edge: JsonMap): string {
  const source = nodeById(endpoint(edge, 'from').nodeId)
  if (!source) return '未找到上游节点'
  if (String(source.type) === 'workflow.input') return '来自 Workflow 输入定义，修改后会实时刷新'
  if (String(source.type) === 'data.transform' && !hasSchema(objectValue(portFor(source, endpoint(edge, 'from').portId, true)?.schema))) {
    return '根据上游数据转换字段实时推导'
  }
  return hasSchema(effectiveOutputSchema(edge)) ? '来自上游输出端口契约' : '上游尚未声明字段结构，可整包透传或填写高级路径'
}

function mappings(edge: JsonMap): JsonMap[] {
  if (!Array.isArray(edge.mapping)) edge.mapping = []
  return edge.mapping as JsonMap[]
}

function inferTarget(path: string): string {
  if (!path || path === '$') return ''
  return path.replace(/\[\d+\]/g, '').split('.').filter(Boolean).at(-1) || ''
}

function addMapping(edge: JsonMap, sourcePath = '') {
  const target = inferTarget(sourcePath)
  mappings(edge).push({ sourcePath, targetField: target })
}

function addFromField(edge: JsonMap, field: SchemaField) {
  if (field.path === '$') {
    addMapping(edge, '$')
    return
  }
  if (mappings(edge).some((mapping) => String(mapping.sourcePath) === field.path)) return
  addMapping(edge, field.path)
}

function removeMapping(edge: JsonMap, index: number) {
  mappings(edge).splice(index, 1)
}

function selectSource(edge: JsonMap, mapping: JsonMap, value: string) {
  mapping.sourcePath = value === '__custom__' ? '$.' : value
  if (!mapping.targetField && value !== '__custom__') mapping.targetField = inferTarget(value)
}

function sourceIsKnown(edge: JsonMap, mapping: JsonMap): boolean {
  const path = String(mapping.sourcePath || '')
  return !path || sourceFields(edge).some((field) => field.path === path)
}

function autoMap(edge: JsonMap) {
  const sources = sourceFields(edge).filter((field) => field.path !== '$' && field.depth === 1)
  const targets = targetFields(edge)
  const selected = targets.length
    ? targets.flatMap((target) => {
        const source = sources.find((candidate) => candidate.name === target.name)
        return source ? [{ sourcePath: source.path, targetField: target.name }] : []
      })
    : sources.map((source) => ({ sourcePath: source.path, targetField: source.name }))
  edge.mapping = selected
}

function schemaTypeLabel(type: string): string {
  return ({ string: '文本', object: '对象', array: '列表', number: '数字', integer: '整数', boolean: '布尔', any: '任意' } as Record<string, string>)[type] || type || '任意'
}

function mappingCoverage(edge: JsonMap): string {
  const count = mappings(edge).filter((mapping) => mapping.sourcePath && mapping.targetField).length
  return count ? `${count} 个字段映射` : '整包透传'
}

function portSchema(port: JsonMap): JsonMap {
  if (String(props.node.type) === 'workflow.input') {
    const configured = objectValue(objectValue(props.node.config).schema)
    if (hasSchema(configured)) return configured
  }
  if (!port.schema || typeof port.schema !== 'object' || Array.isArray(port.schema)) port.schema = {}
  return port.schema as JsonMap
}

function setPortSchemaType(port: JsonMap, type: string) {
  const current: JsonMap = { ...portSchema(port), type }
  if (type === 'object') {
    current.properties = objectValue(current.properties)
    current.required = Array.isArray(current.required) ? current.required : []
  } else {
    delete current.properties
    delete current.required
  }
  if (type === 'array') current.items = objectValue(current.items).type ? current.items : { type: 'string' }
  else delete current.items
  port.schema = current
  syncWorkflowInputSchema(port)
}

function outputFields(port: JsonMap): { key: string; schema: JsonMap }[] {
  return Object.entries(objectValue(portSchema(port).properties)).map(([key, value]) => ({ key, schema: objectValue(value) }))
}

function addOutputField(port: JsonMap) {
  const schema = portSchema(port)
  const properties = { ...objectValue(schema.properties) }
  let index = Object.keys(properties).length + 1
  let key = `field_${index}`
  while (Object.prototype.hasOwnProperty.call(properties, key)) key = `field_${++index}`
  properties[key] = { type: 'string', description: '' }
  port.schema = { ...schema, type: 'object', properties, required: Array.isArray(schema.required) ? schema.required : [] }
  syncWorkflowInputSchema(port)
}

function updateOutputField(port: JsonMap, oldKey: string, key: string, property: 'type' | 'description', value: string) {
  const schema = portSchema(port)
  const properties = { ...objectValue(schema.properties) }
  const nextKey = key.trim() || oldKey
  const field = { ...objectValue(properties[oldKey]), [property]: value }
  if (nextKey !== oldKey) delete properties[oldKey]
  properties[nextKey] = field
  const required = (Array.isArray(schema.required) ? schema.required.map(String) : []).map((item) => item === oldKey ? nextKey : item)
  port.schema = { ...schema, properties, required }
  syncWorkflowInputSchema(port)
}

function setOutputRequired(port: JsonMap, key: string, checked: boolean) {
  const schema = portSchema(port)
  const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : [])
  if (checked) required.add(key)
  else required.delete(key)
  port.schema = { ...schema, required: [...required] }
  syncWorkflowInputSchema(port)
}

function removeOutputField(port: JsonMap, key: string) {
  const schema = portSchema(port)
  const properties = { ...objectValue(schema.properties) }
  delete properties[key]
  port.schema = { ...schema, properties, required: (Array.isArray(schema.required) ? schema.required.map(String) : []).filter((item) => item !== key) }
  syncWorkflowInputSchema(port)
}

function isOutputRequired(port: JsonMap, key: string): boolean {
  return (Array.isArray(portSchema(port).required) ? portSchema(port).required.map(String) : []).includes(key)
}

function syncWorkflowInputSchema(port: JsonMap) {
  if (String(props.node.type) !== 'workflow.input') return
  const config = objectValue(props.node.config)
  config.schema = port.schema
  props.node.config = config
}
</script>

<template>
  <div class="connection-inspector">
    <section class="flow-summary">
      <span><strong>{{ incomingEdges.length }}</strong> 路输入</span>
      <i>→</i>
      <b>{{ node.nodeId }}</b>
      <i>→</i>
      <span><strong>{{ outgoingEdges.length }}</strong> 路输出</span>
    </section>

    <section class="connection-section">
      <div class="section-title"><span>接收的数据</span><small>候选字段跟随上游输出定义实时刷新</small></div>
      <div v-if="!incomingEdges.length" class="empty-state">还没有上游连接。请从上游节点的输出端口拖线到当前节点。</div>
      <article v-for="edge in incomingEdges" :key="String(edge.edgeId)" class="edge-card incoming">
        <div class="edge-head">
          <div><small>来自</small><strong>{{ nodeName(endpoint(edge, 'from').nodeId) }}.{{ endpoint(edge, 'from').portId }}</strong></div>
          <span class="mapping-state">{{ edge.kind === 'control' ? '控制条件' : mappingCoverage(edge) }}</span>
        </div>
        <div class="edge-route"><span>{{ endpoint(edge, 'from').nodeId }}</span><i>→</i><span>{{ endpoint(edge, 'to').nodeId }}</span></div>
        <template v-if="edge.kind !== 'control'">
          <div class="available-head"><strong>上游可用输出</strong><small>{{ schemaSource(edge) }}</small></div>
          <div class="field-chips">
            <button v-for="field in sourceFields(edge)" :key="field.path" type="button" :title="field.description || `点击添加 ${field.path}`" @click="addFromField(edge, field)">
              <span>{{ field.path === '$' ? '整个输出 $' : field.path }}</span><em>{{ schemaTypeLabel(field.type) }}</em>
            </button>
          </div>
          <div class="mapping-head">
            <div><strong>传给当前节点</strong><small>不配置字段时，整个上游输出直接传入</small></div>
            <div><button v-if="sourceFields(edge).length > 1" type="button" class="text-action" @click="autoMap(edge)">同名自动映射</button><button type="button" class="text-action" @click="addMapping(edge)">＋ 添加映射</button></div>
          </div>
          <div v-if="!mappings(edge).length" class="passthrough-note"><b>$</b><span>整包透传</span><small>当前节点收到上游的完整输出</small></div>
          <div v-for="(mapping, index) in mappings(edge)" :key="`${edge.edgeId}-${index}`" class="mapping-row">
            <label><span>上游字段</span>
              <select v-if="sourceIsKnown(edge, mapping)" :value="String(mapping.sourcePath || '')" @change="selectSource(edge, mapping, ($event.target as HTMLSelectElement).value)">
                <option value="">请选择字段</option>
                <option v-for="field in sourceFields(edge)" :key="field.path" :value="field.path">{{ field.path === '$' ? '整个输出 ($)' : field.path }} · {{ schemaTypeLabel(field.type) }}</option>
                <option value="__custom__">高级 JSONPath…</option>
              </select>
              <div v-else class="custom-path"><input v-model="mapping.sourcePath" placeholder="$.customer.name" /><button type="button" title="返回字段选择" @click="mapping.sourcePath = ''">↩</button></div>
            </label>
            <i>→</i>
            <label><span>当前输入字段</span><input v-model="mapping.targetField" :list="`targets-${edge.edgeId}`" placeholder="例如 customer_name" /></label>
            <button type="button" class="remove-mapping" title="删除映射" @click="removeMapping(edge, index)">×</button>
            <datalist :id="`targets-${edge.edgeId}`"><option v-for="field in targetFields(edge)" :key="field.name" :value="field.name">{{ schemaTypeLabel(field.type) }}{{ field.required ? ' · 必填' : '' }}</option></datalist>
          </div>
        </template>
        <div v-else class="condition-grid">
          <label>判断字段<input v-model="edge.condition.path" placeholder="$.status" /></label>
          <label>条件<select v-model="edge.condition.operator"><option value="equals">等于</option><option value="not_equals">不等于</option><option value="contains">包含</option><option value="exists">存在</option><option value="not_exists">不存在</option><option value="greater_than">大于</option><option value="less_than">小于</option></select></label>
          <label>比较值<input v-model="edge.condition.value" /></label>
          <label class="inline-check"><input type="checkbox" v-model="edge.defaultEdge" /> 默认分支</label>
        </div>
        <div class="edge-actions"><select v-model="edge.kind"><option value="data">数据连接</option><option value="control">控制连接</option></select><button type="button" @click="emit('removeEdge', edge)">删除连接</button></div>
      </article>
    </section>

    <section class="connection-section output-contract">
      <div class="section-title"><span>本节点输出</span><small>这里声明的字段会立即出现在下游节点候选项中</small></div>
      <div v-if="!outputPorts.length" class="empty-state">这是流程终点，没有输出端口。</div>
      <article v-for="port in outputPorts" :key="String(port.portId)" class="output-port-card">
        <div class="port-contract-head"><strong>{{ port.portId || 'value' }}</strong><small v-if="node.type === 'workflow.input'">与“配置”中的 Workflow 输入定义同步</small></div>
        <label>输出数据类型<select :value="String(portSchema(port).type || '')" @change="setPortSchemaType(port, ($event.target as HTMLSelectElement).value)"><option value="">未声明 / 任意</option><option value="string">文本</option><option value="object">对象</option><option value="array">列表</option><option value="number">数字</option><option value="integer">整数</option><option value="boolean">布尔值</option></select></label>
        <template v-if="String(portSchema(port).type) === 'object'">
          <div class="mapping-head"><div><strong>输出字段</strong><small>{{ outputFields(port).length }} 个字段</small></div><button type="button" class="text-action" @click="addOutputField(port)">＋ 添加字段</button></div>
          <div v-if="!outputFields(port).length" class="empty-state compact">对象还没有字段，下游目前只能选择整个输出。</div>
          <div v-for="field in outputFields(port)" :key="field.key" class="output-field-row">
            <div><input :value="field.key" placeholder="字段名" @change="updateOutputField(port, field.key, ($event.target as HTMLInputElement).value, 'type', String(field.schema.type || 'string'))" /><select :value="String(field.schema.type || 'string')" @change="updateOutputField(port, field.key, field.key, 'type', ($event.target as HTMLSelectElement).value)"><option value="string">文本</option><option value="number">数字</option><option value="integer">整数</option><option value="boolean">布尔</option><option value="object">对象</option><option value="array">列表</option></select></div>
            <input :value="String(field.schema.description || '')" placeholder="字段说明（可选）" @input="updateOutputField(port, field.key, field.key, 'description', ($event.target as HTMLInputElement).value)" />
            <label class="inline-check"><input type="checkbox" :checked="isOutputRequired(port, field.key)" @change="setOutputRequired(port, field.key, ($event.target as HTMLInputElement).checked)" /> 必填</label><button type="button" class="remove-mapping" @click="removeOutputField(port, field.key)">×</button>
          </div>
        </template>
      </article>
    </section>

    <section class="connection-section">
      <div class="section-title"><span>发送到</span><small>本节点输出将流向这些下游节点</small></div>
      <div v-if="!outgoingEdges.length" class="empty-state">还没有下游连接。</div>
      <article v-for="edge in outgoingEdges" :key="String(edge.edgeId)" class="edge-card outgoing">
        <div class="edge-head"><div><small>发送到</small><strong>{{ nodeName(endpoint(edge, 'to').nodeId) }}.{{ endpoint(edge, 'to').portId }}</strong></div><span class="mapping-state">{{ edge.kind === 'control' ? '控制条件' : mappingCoverage(edge) }}</span></div>
        <template v-if="edge.kind !== 'control'">
          <div class="available-head"><strong>本节点可用输出</strong><small>{{ schemaSource(edge) }}</small></div>
          <div class="field-chips"><button v-for="field in sourceFields(edge)" :key="field.path" type="button" @click="addFromField(edge, field)"><span>{{ field.path === '$' ? '整个输出 $' : field.path }}</span><em>{{ schemaTypeLabel(field.type) }}</em></button></div>
          <div class="mapping-head"><div><strong>发送字段映射</strong><small>{{ mappingCoverage(edge) }}</small></div><div><button v-if="sourceFields(edge).length > 1" type="button" class="text-action" @click="autoMap(edge)">同名自动映射</button><button type="button" class="text-action" @click="addMapping(edge)">＋ 添加映射</button></div></div>
          <div v-for="(mapping, index) in mappings(edge)" :key="`${edge.edgeId}-out-${index}`" class="mapping-row">
            <label><span>本节点字段</span><select v-if="sourceIsKnown(edge, mapping)" :value="String(mapping.sourcePath || '')" @change="selectSource(edge, mapping, ($event.target as HTMLSelectElement).value)"><option value="">请选择字段</option><option v-for="field in sourceFields(edge)" :key="field.path" :value="field.path">{{ field.path === '$' ? '整个输出 ($)' : field.path }} · {{ schemaTypeLabel(field.type) }}</option><option value="__custom__">高级 JSONPath…</option></select><div v-else class="custom-path"><input v-model="mapping.sourcePath" placeholder="$.field" /><button type="button" @click="mapping.sourcePath = ''">↩</button></div></label><i>→</i><label><span>下游输入字段</span><input v-model="mapping.targetField" :list="`targets-out-${edge.edgeId}`" placeholder="目标字段" /></label><button type="button" class="remove-mapping" @click="removeMapping(edge, index)">×</button><datalist :id="`targets-out-${edge.edgeId}`"><option v-for="field in targetFields(edge)" :key="field.name" :value="field.name" /></datalist>
          </div>
        </template>
        <div v-else class="condition-grid"><label>判断字段<input v-model="edge.condition.path" placeholder="$.status" /></label><label>条件<select v-model="edge.condition.operator"><option value="equals">等于</option><option value="not_equals">不等于</option><option value="contains">包含</option><option value="exists">存在</option><option value="not_exists">不存在</option><option value="greater_than">大于</option><option value="less_than">小于</option></select></label><label>比较值<input v-model="edge.condition.value" /></label><label class="inline-check"><input type="checkbox" v-model="edge.defaultEdge" /> 默认分支</label></div>
        <div class="edge-actions"><select v-model="edge.kind"><option value="data">数据连接</option><option value="control">控制连接</option></select><button type="button" @click="emit('removeEdge', edge)">删除连接</button></div>
      </article>
    </section>
  </div>
</template>

<style scoped>
.connection-inspector { display: flex; flex-direction: column; gap: 12px; }
.flow-summary { display: grid; grid-template-columns: 1fr auto minmax(0, 1.3fr) auto 1fr; align-items: center; gap: 6px; padding: 10px; border: 1px solid #dbe5f2; border-radius: 11px; background: linear-gradient(135deg, #f8fbff, #f1f6ff); color: #64748b; font-size: 10px; text-align: center; }
.flow-summary strong { color: #2563eb; font-size: 13px; }
.flow-summary b { overflow: hidden; color: #0f172a; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.flow-summary i { color: #94a3b8; font-style: normal; }
.connection-section { display: flex; flex-direction: column; gap: 9px; }
.section-title { display: flex; flex-direction: column; gap: 2px; }
.section-title span { color: #0f172a; font-size: 12px; font-weight: 800; }
.section-title small, .available-head small, .mapping-head small { color: #94a3b8; font-size: 9px; line-height: 1.45; }
.empty-state { padding: 12px; border: 1px dashed #cbd5e1; border-radius: 9px; background: #f8fafc; color: #94a3b8; font-size: 10px; line-height: 1.5; }
.empty-state.compact { padding: 9px; }
.edge-card, .output-port-card { display: flex; flex-direction: column; gap: 9px; padding: 11px; border: 1px solid #dbe5f2; border-radius: 11px; background: #fff; }
.edge-card.incoming { border-left: 3px solid #3b82f6; }
.edge-card.outgoing { border-left: 3px solid #22c55e; }
.edge-head, .mapping-head, .port-contract-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.edge-head > div, .mapping-head > div:first-child { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.edge-head small, .port-contract-head small { color: #94a3b8; font-size: 9px; }
.edge-head strong, .mapping-head strong, .port-contract-head strong { overflow: hidden; color: #1e293b; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.mapping-state { flex: none; padding: 3px 7px; border-radius: 999px; background: #eff6ff; color: #2563eb; font-size: 9px; font-weight: 750; }
.edge-route { display: flex; align-items: center; gap: 5px; color: #64748b; font-size: 9px; }
.edge-route span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.edge-route i { color: #2563eb; font-style: normal; }
.available-head { display: flex; flex-direction: column; gap: 2px; padding-top: 2px; }
.available-head strong { color: #475569; font-size: 10px; }
.field-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.field-chips button { display: flex; align-items: center; gap: 5px; max-width: 100%; padding: 5px 7px; border: 1px solid #dbeafe; border-radius: 7px; background: #f8fbff; color: #1d4ed8; font-size: 9px; cursor: pointer; }
.field-chips button:hover { border-color: #60a5fa; background: #eff6ff; }
.field-chips span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.field-chips em { color: #94a3b8; font-size: 8px; font-style: normal; }
.mapping-head { align-items: flex-end; padding-top: 4px; }
.mapping-head > div:last-child { display: flex; justify-content: flex-end; gap: 4px; }
.text-action { border: 0; border-radius: 6px; padding: 4px 6px; background: #eff6ff; color: #2563eb; font-size: 9px; font-weight: 700; cursor: pointer; }
.passthrough-note { display: grid; grid-template-columns: 28px 1fr; gap: 1px 8px; align-items: center; padding: 8px; border-radius: 8px; background: #f0fdf4; }
.passthrough-note b { grid-row: 1 / 3; display: grid; place-items: center; width: 28px; height: 28px; border-radius: 7px; background: #dcfce7; color: #15803d; font-size: 13px; }
.passthrough-note span { color: #166534; font-size: 10px; font-weight: 750; }
.passthrough-note small { color: #65a30d; font-size: 8px; }
.mapping-row { display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(0, .8fr) 24px; gap: 5px; align-items: end; padding: 8px; border: 1px solid #e2e8f0; border-radius: 9px; background: #f8fafc; }
.mapping-row > i { padding-bottom: 9px; color: #3b82f6; font-style: normal; }
label { display: flex; flex-direction: column; gap: 4px; color: #64748b; font-size: 9px; }
input, select { width: 100%; box-sizing: border-box; border: 1px solid #cbd5e1; border-radius: 7px; padding: 7px; background: #fff; color: #0f172a; font: inherit; font-size: 9px; outline: none; }
input:focus, select:focus { border-color: #3b82f6; box-shadow: 0 0 0 2px rgba(59,130,246,.12); }
.custom-path { display: grid; grid-template-columns: 1fr 25px; gap: 4px; }
.custom-path button, .remove-mapping { border: 0; border-radius: 6px; background: #fff1f2; color: #e11d48; cursor: pointer; }
.remove-mapping { width: 24px; height: 27px; margin-bottom: 1px; font-size: 14px; }
.condition-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; }
.inline-check { flex-direction: row; align-items: center; align-self: center; }
.inline-check input { width: auto; }
.edge-actions { display: flex; justify-content: space-between; gap: 7px; padding-top: 4px; border-top: 1px solid #f1f5f9; }
.edge-actions select { width: auto; }
.edge-actions button { border: 0; background: transparent; color: #e11d48; font-size: 9px; cursor: pointer; }
.output-contract { padding-top: 11px; border-top: 1px solid #e2e8f0; }
.output-port-card { background: #f8fafc; }
.output-field-row { display: grid; grid-template-columns: 1fr 24px; gap: 5px; padding: 7px; border: 1px solid #e2e8f0; border-radius: 8px; background: #fff; }
.output-field-row > div { grid-column: 1 / -1; display: grid; grid-template-columns: minmax(0, 1fr) 82px; gap: 5px; }
.output-field-row > input { grid-column: 1 / -1; }
.output-field-row > .inline-check { grid-column: 1; }
@media (max-width: 700px) { .mapping-row { grid-template-columns: 1fr 18px 1fr 24px; } }
</style>
