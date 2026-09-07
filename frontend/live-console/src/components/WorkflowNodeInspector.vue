<script setup lang="ts">
import { computed } from 'vue'
import type { JsonMap } from '../lib/platformApi'
import { notifyError } from '../stores/notify'

type NodeTypeOption = {
  value: string
  label: string
  group: string
  icon?: string
  description?: string
}

const props = defineProps<{
  node: JsonMap
  nodeTypes: NodeTypeOption[]
  workflows: JsonMap[]
  agents: JsonMap[]
  models: JsonMap[]
  skills: JsonMap[]
  mcpServers: JsonMap[]
  mode?: 'config' | 'advanced'
}>()

const emit = defineEmits<{
  typeChange: [node: JsonMap]
  schemaChange: [node: JsonMap]
}>()

const typeMeta = computed(() => props.nodeTypes.find((item) => item.value === String(props.node.type)) || props.nodeTypes[0])
const config = computed<JsonMap>(() => {
  if (!props.node.config || typeof props.node.config !== 'object' || Array.isArray(props.node.config)) props.node.config = {}
  return props.node.config as JsonMap
})

const instructionTypes = new Set([
  'agent.invoke',
  'agent.react',
  'llm.chat',
  'skill.invoke',
  'mcp.invoke',
  'subflow.invoke',
  'foreach',
  'human.approval',
])

function objectValue(value: unknown): JsonMap {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonMap : {}
}

function listValue(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

function entries(key: string): { key: string; value: unknown }[] {
  return Object.entries(objectValue(config.value[key])).map(([entryKey, value]) => ({ key: entryKey, value }))
}

function addEntry(key: string, prefix: string, initial = '') {
  const current = { ...objectValue(config.value[key]) }
  let index = Object.keys(current).length + 1
  let next = `${prefix}${index}`
  while (Object.prototype.hasOwnProperty.call(current, next)) next = `${prefix}${++index}`
  current[next] = initial
  config.value[key] = current
}

function updateEntry(collection: string, previousKey: string, nextKey: string, value: unknown) {
  const current = { ...objectValue(config.value[collection]) }
  delete current[previousKey]
  const normalized = nextKey.trim()
  if (normalized) current[normalized] = value
  config.value[collection] = current
}

function removeEntry(collection: string, key: string) {
  const current = { ...objectValue(config.value[collection]) }
  delete current[key]
  config.value[collection] = current
}

function parameters(): unknown[] {
  if (!Array.isArray(config.value.parameters)) config.value.parameters = []
  return config.value.parameters as unknown[]
}

function addParameter() {
  config.value.parameters = [...parameters(), '$.field']
}

function updateParameter(index: number, value: string) {
  const current = [...parameters()]
  current[index] = value
  config.value.parameters = current
}

function removeParameter(index: number) {
  const current = [...parameters()]
  current.splice(index, 1)
  config.value.parameters = current
}

function schema(): JsonMap {
  const current = objectValue(config.value.schema)
  if (!current.type) current.type = Object.keys(objectValue(current.properties)).length ? 'object' : 'string'
  config.value.schema = current
  return current
}

function schemaType(): string {
  return String(schema().type || 'string')
}

function setSchemaType(value: string) {
  const current: JsonMap = { ...schema(), type: value }
  if (value === 'object') {
    current.properties = objectValue(current.properties)
    current.required = listValue(current.required).map(String)
  } else {
    delete current.properties
    delete current.required
  }
  if (value === 'array') current.items = { type: String(objectValue(current.items).type || 'string') }
  else delete current.items
  config.value.schema = current
  emit('schemaChange', props.node)
}

function schemaFields(): { key: string; value: JsonMap }[] {
  return Object.entries(objectValue(schema().properties)).map(([key, value]) => ({ key, value: objectValue(value) }))
}

function addSchemaField() {
  const current = { ...objectValue(schema().properties) }
  let index = Object.keys(current).length + 1
  let key = `field_${index}`
  while (Object.prototype.hasOwnProperty.call(current, key)) key = `field_${++index}`
  current[key] = { type: 'string', description: '' }
  config.value.schema = { ...schema(), type: 'object', properties: current }
  emit('schemaChange', props.node)
}

function updateSchemaField(previousKey: string, nextKey: string, field: JsonMap) {
  const current = { ...objectValue(schema().properties) }
  delete current[previousKey]
  const normalized = nextKey.trim()
  if (normalized) current[normalized] = { ...field }
  const required = listValue(schema().required).map(String).filter((key) => key !== previousKey)
  if (normalized && isSchemaRequired(previousKey)) required.push(normalized)
  config.value.schema = { ...schema(), properties: current, required: [...new Set(required)] }
  emit('schemaChange', props.node)
}

function updateSchemaFieldValue(key: string, property: string, value: unknown) {
  const current = { ...objectValue(schema().properties) }
  current[key] = { ...objectValue(current[key]), [property]: value }
  config.value.schema = { ...schema(), properties: current }
  emit('schemaChange', props.node)
}

function removeSchemaField(key: string) {
  const current = { ...objectValue(schema().properties) }
  delete current[key]
  config.value.schema = {
    ...schema(),
    properties: current,
    required: listValue(schema().required).map(String).filter((item) => item !== key),
  }
  emit('schemaChange', props.node)
}

function isSchemaRequired(key: string): boolean {
  return listValue(schema().required).map(String).includes(key)
}

function setSchemaRequired(key: string, checked: boolean) {
  const required = new Set(listValue(schema().required).map(String))
  if (checked) required.add(key)
  else required.delete(key)
  config.value.schema = { ...schema(), required: [...required] }
  emit('schemaChange', props.node)
}

function setArrayItemType(value: string) {
  config.value.schema = { ...schema(), type: 'array', items: { type: value } }
  emit('schemaChange', props.node)
}

function applyRawConfig(event: Event) {
  try {
    const parsed = JSON.parse((event.target as HTMLTextAreaElement).value || '{}')
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('配置必须是 JSON 对象')
    props.node.config = parsed as JsonMap
    emit('schemaChange', props.node)
  } catch (error) {
    notifyError(error)
  }
}

function transformMode(): string {
  if (Object.keys(objectValue(config.value.mapping)).length) return 'mapping'
  if (String(config.value.template || '')) return 'template'
  if (String(config.value.path || '')) return 'path'
  return 'passthrough'
}

function setTransformMode(mode: string) {
  delete config.value.mapping
  delete config.value.template
  delete config.value.path
  if (mode === 'mapping') config.value.mapping = { result: '$' }
  if (mode === 'template') config.value.template = '{{input}}'
  if (mode === 'path') config.value.path = '$.field'
}

function targetLabel(item: JsonMap, idKey: string): string {
  return String(item.name || item.display_name || item.alias_name || item[idKey] || '')
}
</script>

<template>
  <div class="node-inspector">
    <template v-if="mode !== 'advanced'">
    <div class="inspector-hero">
      <span class="inspector-icon">{{ typeMeta?.icon || 'NODE' }}</span>
      <div>
        <strong>{{ typeMeta?.label || node.type }}</strong>
        <small>{{ typeMeta?.description || '配置节点执行方式和数据输入输出。' }}</small>
      </div>
    </div>

    <section class="inspector-section">
      <div class="section-title"><span>基础信息</span><small>用于画布识别和运行日志</small></div>
      <label>节点名称 / ID<input v-model="node.nodeId" placeholder="例如 fetch_order" /></label>
      <label>节点类型
        <select v-model="node.type" @change="emit('typeChange', node)">
          <optgroup v-for="group in ['流程', '业务', 'AI', '控制']" :key="group" :label="group">
            <option v-for="type in nodeTypes.filter((item) => item.group === group)" :key="type.value" :value="type.value">{{ type.label }}</option>
          </optgroup>
        </select>
      </label>
    </section>

    <section v-if="node.type === 'workflow.input' || node.type === 'workflow.output'" class="inspector-section">
      <div class="section-title"><span>{{ node.type === 'workflow.input' ? '输入数据' : '输出数据' }}</span><small>通过字段表定义，无需编写 Schema</small></div>
      <label>数据类型
        <select :value="schemaType()" @change="setSchemaType(($event.target as HTMLSelectElement).value)">
          <option value="string">文本</option><option value="object">对象</option><option value="array">列表</option><option value="number">数字</option><option value="integer">整数</option><option value="boolean">布尔值</option>
        </select>
      </label>
      <template v-if="schemaType() === 'object'">
        <div class="repeat-head"><strong>字段</strong><button class="mini-action" type="button" @click="addSchemaField">＋ 添加字段</button></div>
        <div v-if="!schemaFields().length" class="form-empty">还没有字段。对象可以为空，也可以添加业务字段。</div>
        <div v-for="field in schemaFields()" :key="field.key" class="schema-row">
          <input :value="field.key" placeholder="字段名" @change="updateSchemaField(field.key, ($event.target as HTMLInputElement).value, field.value)" />
          <select :value="String(field.value.type || 'string')" @change="updateSchemaFieldValue(field.key, 'type', ($event.target as HTMLSelectElement).value)">
            <option value="string">文本</option><option value="number">数字</option><option value="integer">整数</option><option value="boolean">布尔</option><option value="object">对象</option><option value="array">列表</option>
          </select>
          <input :value="String(field.value.description || '')" placeholder="字段说明（可选）" @input="updateSchemaFieldValue(field.key, 'description', ($event.target as HTMLInputElement).value)" />
          <label class="inline-check"><input type="checkbox" :checked="isSchemaRequired(field.key)" @change="setSchemaRequired(field.key, ($event.target as HTMLInputElement).checked)" />必填</label>
          <button class="remove-row" type="button" title="删除字段" @click="removeSchemaField(field.key)">×</button>
        </div>
      </template>
      <label v-if="schemaType() === 'array'">列表元素类型
        <select :value="String(objectValue(schema().items).type || 'string')" @change="setArrayItemType(($event.target as HTMLSelectElement).value)">
          <option value="string">文本</option><option value="number">数字</option><option value="integer">整数</option><option value="boolean">布尔</option><option value="object">对象</option>
        </select>
      </label>
    </section>

    <section v-if="node.type === 'agent.invoke' || node.type === 'agent.react'" class="inspector-section">
      <div class="section-title"><span>调用 Agent</span><small>{{ node.type === 'agent.react' ? '允许目标 Agent 使用自身工具循环处理' : '单次调用目标 Agent' }}</small></div>
      <label>目标 Agent
        <select v-model="node.refId"><option value="">请选择 Agent</option><option v-for="item in agents" :key="String(item.agent_id)" :value="String(item.agent_id)">{{ targetLabel(item, 'agent_id') }} · {{ item.agent_id }}</option></select>
      </label>
    </section>

    <section v-if="node.type === 'llm.chat'" class="inspector-section">
      <div class="section-title"><span>调用模型</span><small>留空时跟随平台默认 Chat 模型</small></div>
      <label>模型
        <select v-model="node.refId"><option value="">平台默认模型</option><option v-for="item in models" :key="String(item.model_id || item.alias_name)" :value="String(item.model_id || item.alias_name)">{{ targetLabel(item, 'model_id') }}</option></select>
      </label>
    </section>

    <section v-if="node.type === 'skill.invoke'" class="inspector-section">
      <div class="section-title"><span>调用 Skill</span><small>只开放所选 Skill，不继承目标 Agent 的其他能力</small></div>
      <label>承载 Agent<select v-model="config.agent_id"><option value="">请选择 Agent</option><option v-for="item in agents" :key="String(item.agent_id)" :value="String(item.agent_id)">{{ targetLabel(item, 'agent_id') }}</option></select></label>
      <label>Skill<select v-model="node.refId"><option value="">请选择 Skill</option><option v-for="item in skills" :key="String(item.skill_id || item.id)" :value="String(item.skill_id || item.id)">{{ targetLabel(item, 'skill_id') }}</option></select></label>
    </section>

    <section v-if="node.type === 'mcp.invoke'" class="inspector-section">
      <div class="section-title"><span>调用 MCP</span><small>只开放所选 MCP，不继承目标 Agent 的其他能力</small></div>
      <label>承载 Agent<select v-model="config.agent_id"><option value="">请选择 Agent</option><option v-for="item in agents" :key="String(item.agent_id)" :value="String(item.agent_id)">{{ targetLabel(item, 'agent_id') }}</option></select></label>
      <label>MCP 服务器<select v-model="node.refId"><option value="">请选择 MCP</option><option v-for="item in mcpServers" :key="String(item.id || item.server_id)" :value="String(item.id || item.server_id)">{{ targetLabel(item, 'id') }}</option></select></label>
    </section>

    <section v-if="node.type === 'subflow.invoke'" class="inspector-section">
      <div class="section-title"><span>调用子流程</span><small>发布时自动固定目标 Workflow 版本</small></div>
      <label>目标 Workflow<select v-model="node.refId"><option value="">请选择 Workflow</option><option v-for="item in workflows" :key="String(item.workflow_id)" :value="String(item.workflow_id)">{{ targetLabel(item, 'workflow_id') }} · {{ item.workflow_id }}</option></select></label>
    </section>

    <section v-if="node.type === 'foreach'" class="inspector-section">
      <div class="section-title"><span>循环处理</span><small>输入必须是列表，按顺序汇总结果</small></div>
      <label>执行目标<select v-model="config.target_type"><option value="agent">Agent</option><option value="workflow">Workflow</option></select></label>
      <label v-if="config.target_type !== 'workflow'">目标 Agent<select v-model="node.refId"><option value="">仅原样遍历，不调用目标</option><option v-for="item in agents" :key="String(item.agent_id)" :value="String(item.agent_id)">{{ targetLabel(item, 'agent_id') }}</option></select></label>
      <label v-else>目标 Workflow<select v-model="node.refId"><option value="">请选择 Workflow</option><option v-for="item in workflows" :key="String(item.workflow_id)" :value="String(item.workflow_id)">{{ targetLabel(item, 'workflow_id') }}</option></select></label>
      <label>并发数<input v-model.number="config.concurrency" type="number" min="1" max="16" /></label>
    </section>

    <section v-if="node.type === 'http.request' || node.type === 'message.send'" class="inspector-section">
      <div class="section-title"><span>{{ node.type === 'message.send' ? '消息投递' : 'HTTP 请求' }}</span><small>支持 <code v-text="'{{input}}'"></code> 引用上游输入</small></div>
      <label>请求地址<input v-model="config.url" type="url" placeholder="https://api.example.com/orders" /></label>
      <label>请求方法<select v-model="config.method"><option>GET</option><option>POST</option><option>PUT</option><option>PATCH</option><option>DELETE</option></select></label>
      <label v-if="config.method !== 'GET' && config.method !== 'DELETE'">请求体<textarea v-model="config.body" rows="5" placeholder="例如：{{input}}"></textarea></label>
      <label v-if="config.method !== 'GET' && config.method !== 'DELETE'">幂等键<input v-model="config.idempotency_key" placeholder="例如：order-{{input}}" /><small class="field-help">写请求需要稳定的幂等键，防止重试产生重复操作。</small></label>
      <div class="repeat-head"><strong>请求头</strong><button class="mini-action" type="button" @click="addEntry('headers', 'Header-')">＋ 添加</button></div>
      <div v-if="!entries('headers').length" class="form-empty">没有自定义请求头。敏感值请填写 env:环境变量名。</div>
      <div v-for="entry in entries('headers')" :key="entry.key" class="key-value-row">
        <input :value="entry.key" placeholder="Header 名称" @change="updateEntry('headers', entry.key, ($event.target as HTMLInputElement).value, entry.value)" />
        <input :value="String(entry.value || '')" placeholder="值或 env:SECRET" @input="updateEntry('headers', entry.key, entry.key, ($event.target as HTMLInputElement).value)" />
        <button class="remove-row" type="button" @click="removeEntry('headers', entry.key)">×</button>
      </div>
    </section>

    <section v-if="node.type === 'database.query' || node.type === 'database.write'" class="inspector-section">
      <div class="section-title"><span>{{ node.type === 'database.write' ? '数据库写入' : '数据库查询' }}</span><small>使用预编译参数，禁止拼接输入</small></div>
      <label>JDBC 地址<input v-model="config.jdbc_url" placeholder="jdbc:postgresql://db:5432/app" /></label>
      <div class="two-column"><label>用户名<input v-model="config.username" placeholder="env:DB_USER" /></label><label>密码<input v-model="config.password" type="password" placeholder="env:DB_PASSWORD" /></label></div>
      <label>SQL<textarea v-model="config.sql" rows="6" :placeholder="node.type === 'database.write' ? 'INSERT ... VALUES (?, {{idempotency_key}})' : 'SELECT ... WHERE id = ?'"></textarea><small class="field-help">业务参数使用 ? 占位；写入 SQL 必须且只能包含一次 <code v-text="'{{idempotency_key}}'"></code>。</small></label>
      <label v-if="node.type === 'database.query'">最大返回行数<input v-model.number="config.max_rows" type="number" min="1" max="10000" /></label>
      <label v-else>幂等键补充<input v-model="config.idempotency_key" placeholder="例如：$.order_id" /></label>
      <div class="repeat-head"><strong>SQL 参数</strong><button class="mini-action" type="button" @click="addParameter">＋ 添加参数</button></div>
      <div v-for="(parameter, index) in parameters()" :key="index" class="parameter-row"><span>{{ index + 1 }}</span><input :value="String(parameter ?? '')" placeholder="$.field 或固定值" @input="updateParameter(index, ($event.target as HTMLInputElement).value)" /><button class="remove-row" type="button" @click="removeParameter(index)">×</button></div>
      <div v-if="!parameters().length" class="form-empty">SQL 没有 ? 参数时可留空。</div>
    </section>

    <section v-if="node.type === 'data.transform'" class="inspector-section">
      <div class="section-title"><span>数据转换</span><small>从上游数据提取、映射或套用文本模板</small></div>
      <label>转换方式<select :value="transformMode()" @change="setTransformMode(($event.target as HTMLSelectElement).value)"><option value="passthrough">原样透传</option><option value="path">提取一个字段</option><option value="mapping">字段映射</option><option value="template">文本模板</option></select></label>
      <label v-if="transformMode() === 'path'">数据路径<input v-model="config.path" placeholder="$.order.customer.name" /></label>
      <label v-if="transformMode() === 'template'">输出模板<textarea v-model="config.template" rows="5" placeholder="Workflow received: {{input}}"></textarea></label>
      <template v-if="transformMode() === 'mapping'">
        <div class="repeat-head"><strong>输出字段映射</strong><button class="mini-action" type="button" @click="addEntry('mapping', 'field_')">＋ 添加字段</button></div>
        <div v-for="entry in entries('mapping')" :key="entry.key" class="key-value-row"><input :value="entry.key" placeholder="输出字段" @change="updateEntry('mapping', entry.key, ($event.target as HTMLInputElement).value, entry.value)" /><input :value="String(entry.value || '')" placeholder="上游路径，如 $.name" @input="updateEntry('mapping', entry.key, entry.key, ($event.target as HTMLInputElement).value)" /><button class="remove-row" type="button" @click="removeEntry('mapping', entry.key)">×</button></div>
      </template>
    </section>

    <section v-if="node.type === 'human.approval'" class="inspector-section">
      <div class="section-title"><span>审批设置</span><small>运行会挂起，等待用户同意或拒绝</small></div>
      <label>审批标题<input v-model="config.title" placeholder="需要人工审批" /></label>
      <label>通过后的输出<select v-model="config.output_mode"><option value="input">继续使用原输入</option><option value="response">使用审批人的回复</option></select></label>
    </section>

    <section v-if="instructionTypes.has(String(node.type))" class="inspector-section">
      <div class="section-title"><span>{{ node.type === 'human.approval' ? '审批问题' : '节点指令' }}</span><small>可以使用 <code v-text="'{{input}}'"></code> 引用上游内容</small></div>
      <textarea v-model="node.instruction" rows="5" :placeholder="node.type === 'human.approval' ? '请确认是否继续执行' : '告诉目标能力要完成什么任务'"></textarea>
    </section>

    <section v-if="['condition', 'parallel', 'join', 'return'].includes(String(node.type))" class="inspector-section guidance-card">
      <strong>{{ node.type === 'condition' ? '条件配置在连线上完成' : node.type === 'parallel' ? '从此节点连接至少两个并行分支' : node.type === 'join' ? '把并行分支都连接到此节点' : '返回当前输入并结束流程' }}</strong>
      <p v-if="node.type === 'condition'">选中与此节点相连的控制边，在下方填写判断路径、操作符和比较值，并设置一个默认分支。</p>
      <p v-else-if="node.type === 'parallel'">所有分支最终必须汇聚到同一个“并行汇聚”节点。</p>
      <p v-else-if="node.type === 'join'">汇聚节点会按分支顺序输出结果列表。</p>
    </section>
    </template>

    <section v-else class="inspector-section advanced-config">
      <div class="section-title"><span>专家配置</span><small>仅用于兼容旧字段或暂未图形化的扩展能力</small></div>
      <p>仅用于迁移旧配置或处理尚未图形化的扩展字段。普通配置无需修改这里。</p>
      <textarea :value="JSON.stringify(config, null, 2)" rows="9" spellcheck="false" @change="applyRawConfig"></textarea>
    </section>
  </div>
</template>

<style scoped>
.node-inspector { display: flex; flex-direction: column; gap: 12px; }
.inspector-hero { display: flex; gap: 11px; align-items: center; padding: 12px; border: 1px solid #dbe5f2; border-radius: 12px; background: linear-gradient(135deg, #f8fbff, #f1f6ff); }
.inspector-icon { min-width: 42px; height: 42px; padding: 0 7px; border-radius: 11px; display: grid; place-items: center; background: #172554; color: white; font-size: 10px; font-weight: 900; letter-spacing: .04em; }
.inspector-hero div { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.inspector-hero strong { font-size: 13px; color: #0f172a; }
.inspector-hero small { font-size: 10px; line-height: 1.45; color: #64748b; }
.inspector-section { display: flex; flex-direction: column; gap: 9px; padding: 12px; border: 1px solid #e2e8f0; border-radius: 11px; background: #fff; }
.section-title { display: flex; flex-direction: column; gap: 2px; margin-bottom: 1px; }
.section-title span { color: #0f172a; font-size: 12px; font-weight: 800; }
.section-title small, .field-help { color: #94a3b8; font-size: 10px; line-height: 1.45; }
label { display: flex; flex-direction: column; gap: 5px; color: #475569; font-size: 11px; font-weight: 650; }
input, select, textarea { width: 100%; box-sizing: border-box; border: 1px solid #cbd5e1; border-radius: 8px; padding: 8px 9px; background: #fff; color: #0f172a; font: inherit; font-size: 11px; outline: none; }
input:focus, select:focus, textarea:focus { border-color: #3b82f6; box-shadow: 0 0 0 3px rgba(59,130,246,.12); }
textarea { resize: vertical; line-height: 1.5; }
.two-column { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; }
.repeat-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.repeat-head strong { font-size: 11px; color: #334155; }
.mini-action { border: 0; background: #eff6ff; color: #2563eb; border-radius: 7px; padding: 5px 8px; font-size: 10px; font-weight: 700; cursor: pointer; }
.key-value-row, .parameter-row { display: grid; grid-template-columns: minmax(0, .8fr) minmax(0, 1.2fr) 26px; gap: 5px; align-items: center; }
.parameter-row { grid-template-columns: 22px minmax(0, 1fr) 26px; }
.parameter-row > span { display: grid; place-items: center; width: 20px; height: 20px; border-radius: 6px; background: #f1f5f9; color: #64748b; font-size: 9px; font-weight: 800; }
.schema-row { display: grid; grid-template-columns: minmax(0, .9fr) 82px 1fr; gap: 5px; padding: 8px; border: 1px solid #e2e8f0; border-radius: 9px; background: #f8fafc; }
.schema-row .inline-check { flex-direction: row; align-items: center; gap: 5px; }
.inline-check input { width: auto; }
.remove-row { width: 26px; height: 26px; border: 0; border-radius: 7px; background: #fff1f2; color: #e11d48; font-size: 16px; cursor: pointer; }
.form-empty { padding: 9px; border: 1px dashed #cbd5e1; border-radius: 8px; color: #94a3b8; font-size: 10px; line-height: 1.5; }
.guidance-card { background: #f8fafc; }
.guidance-card strong { color: #334155; font-size: 11px; }
.guidance-card p { margin: 0; color: #64748b; font-size: 10px; line-height: 1.55; }
.advanced-config { background: #f8fafc; }
.advanced-config p { color: #94a3b8; font-size: 10px; line-height: 1.5; }
.advanced-config textarea { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 10px; }
@media (max-width: 700px) { .two-column { grid-template-columns: 1fr; } }
</style>
