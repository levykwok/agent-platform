<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  currentDomain,
  currentOrgId,
  makeHeaders,
  readJson,
  type JsonMap,
} from "../lib/platformApi";
import { notifyError, notifySuccess } from "../stores/notify";
import { formDialog } from "../stores/dialog";
import WorkflowConnectionInspector from "../components/WorkflowConnectionInspector.vue";
import WorkflowNodeInspector from "../components/WorkflowNodeInspector.vue";

const NODE_TYPES = [
  { value: "workflow.input", label: "Workflow 输入", group: "流程", icon: "IN", description: "定义流程接收的数据" },
  { value: "workflow.output", label: "Workflow 输出", group: "流程", icon: "OUT", description: "定义流程最终返回的数据" },
  { value: "return", label: "返回结果", group: "流程", icon: "RET", description: "立即返回当前节点结果" },
  { value: "http.request", label: "HTTP / API", group: "业务", icon: "API", description: "调用安全的外部 HTTP 接口" },
  { value: "database.query", label: "数据库查询", group: "业务", icon: "DB", description: "通过参数化 SQL 查询数据" },
  { value: "database.write", label: "数据库写入", group: "业务", icon: "SQL", description: "执行带幂等保护的数据写入" },
  { value: "data.transform", label: "数据转换", group: "业务", icon: "FX", description: "提取、映射或格式化上游数据" },
  { value: "message.send", label: "消息发送", group: "业务", icon: "MSG", description: "向 Webhook 或消息网关投递内容" },
  { value: "agent.invoke", label: "Agent 调用", group: "AI", icon: "AG", description: "单次调用一个已发布 Agent" },
  { value: "agent.react", label: "ReActAgent", group: "AI", icon: "RX", description: "调用可自主使用工具的 Agent" },
  { value: "llm.chat", label: "LLM 调用", group: "AI", icon: "LLM", description: "直接调用一个模型完成生成" },
  { value: "skill.invoke", label: "Skill 调用", group: "AI", icon: "SK", description: "隔离调用 Agent 上的单个 Skill" },
  { value: "mcp.invoke", label: "MCP 调用", group: "AI", icon: "MCP", description: "隔离调用 Agent 上的单个 MCP" },
  { value: "subflow.invoke", label: "子流程", group: "控制", icon: "WF", description: "调用另一个已发布 Workflow" },
  { value: "condition", label: "条件分支", group: "控制", icon: "IF", description: "按控制边条件选择一条路径" },
  { value: "foreach", label: "Foreach", group: "控制", icon: "EACH", description: "逐项处理输入列表" },
  { value: "parallel", label: "并行分叉", group: "控制", icon: "PAR", description: "同时启动多个执行分支" },
  { value: "join", label: "并行汇聚", group: "控制", icon: "JOIN", description: "等待并汇总所有并行分支" },
  { value: "human.approval", label: "人工审批", group: "控制", icon: "OK", description: "挂起运行并等待人工确认" },
];
const GRAPH_NODE_WIDTH = 220;
const GRAPH_NODE_HEIGHT = 128;

const workflows = ref<JsonMap[]>([]);
const workflowVersions = ref<JsonMap[]>([]);
const workflowTools = ref<JsonMap[]>([]);
const scheduledTasks = ref<JsonMap[]>([]);
const catalogAgents = ref<JsonMap[]>([]);
const catalogModels = ref<JsonMap[]>([]);
const catalogSkills = ref<JsonMap[]>([]);
const catalogMcpServers = ref<JsonMap[]>([]);
const workflow = ref<JsonMap | null>(null);
const selectedWorkflowId = ref("");
const nodes = ref<JsonMap[]>([]);
const edges = ref<JsonMap[]>([]);
const selectedNodeId = ref("");
const loading = ref(false);
const creating = ref(false);
const saving = ref(false);
const publishing = ref(false);
const testing = ref(false);
const validating = ref(false);
const validation = ref<JsonMap | null>(null);
const detailDrawerOpen = ref(false);
const canvasOpen = ref(false);
const initialParams = new URLSearchParams(window.location.search);
const standaloneCanvas = initialParams.get("view") === "canvas";
const requestedWorkflowId = initialParams.get("workflow_id") || "";
const testPanelOpen = ref(false);
const testInput = ref("");
const testOutput = ref("");
const testEvents = ref<JsonMap[]>([]);
const graphCanvasRef = ref<HTMLElement | null>(null);
const paletteDragType = ref("");
const dragState = ref<{
  nodeId: string;
  offsetX: number;
  offsetY: number;
} | null>(null);
const connectionState = ref<{
  sourceId: string;
  sourcePortId: string;
  x: number;
  y: number;
} | null>(null);
const activeNodePropertyTab = ref<"config" | "connection" | "runtime">(
  "config",
);
const canvasZoom = ref(1);
const canvasPanning = ref(false);
const paletteQuery = ref("");
const canvasPanState = ref<{
  startX: number;
  startY: number;
  scrollLeft: number;
  scrollTop: number;
} | null>(null);
const nodeIdSnapshot = new WeakMap<JsonMap, string>();
const MIN_CANVAS_ZOOM = 0.4;
const MAX_CANVAS_ZOOM = 1.6;
const CANVAS_ZOOM_STEP = 0.1;

watch(
  nodes,
  (current) => {
    for (const node of current) {
      const currentId = String(node.nodeId || "");
      const previousId = nodeIdSnapshot.get(node);
      if (previousId && previousId !== currentId) {
        edges.value.forEach((edge) => {
          const from = (edge.from || {}) as JsonMap;
          const to = (edge.to || {}) as JsonMap;
          if (String(from.nodeId) === previousId) from.nodeId = currentId;
          if (String(to.nodeId) === previousId) to.nodeId = currentId;
        });
      }
      nodeIdSnapshot.set(node, currentId);
    }
  },
  { deep: true },
);

const selectedNode = computed(
  () =>
    nodes.value.find((node) => node.nodeId === selectedNodeId.value) || null,
);
const selectedNodeEdges = computed(() =>
  edges.value.filter((edge) => {
    const from = (edge.from || {}) as JsonMap;
    const to = (edge.to || {}) as JsonMap;
    return (
      String(from.nodeId) === selectedNodeId.value ||
      String(to.nodeId) === selectedNodeId.value
    );
  }),
);
watch(selectedNodeId, () => {
  activeNodePropertyTab.value = "config";
});
const status = computed(() => String(workflow.value?.status || "DRAFT"));
const isPublished = computed(() => status.value === "PUBLISHED");
const activePublishedVersion = computed(() =>
  Number(workflow.value?.active_published_version || 0),
);
const hasActivePublishedVersion = computed(
  () => activePublishedVersion.value > 0,
);
const workflowSchedules = computed(() =>
  scheduledTasks.value.filter(
    (item) => String(item.workflow_id || "") === selectedWorkflowId.value,
  ),
);
const graphEdges = computed(() => {
  const result: {
    id: string;
    from: string;
    fromPort: string;
    to: string;
    toPort: string;
    label: string;
    dashed: boolean;
  }[] = [];
  edges.value.forEach((edge, index) => {
    const from = (edge.from || {}) as JsonMap;
    const to = (edge.to || {}) as JsonMap;
    if (!from.nodeId || !to.nodeId) return;
    result.push({
      id: String(edge.edgeId || `edge_${index + 1}`),
      from: String(from.nodeId),
      fromPort: String(from.portId || "value"),
      to: String(to.nodeId),
      toPort: String(to.portId || "value"),
      label:
        edge.kind === "control"
          ? "控制"
          : String(
              edge.binding && Object.keys(edge.binding as JsonMap).length
                ? "映射"
                : "数据",
            ),
      dashed: edge.kind === "control",
    });
  });
  return result;
});
const paletteGroups = computed(() => {
  const query = paletteQuery.value.trim().toLowerCase();
  return ["流程", "业务", "AI", "控制"]
    .map((label) => ({
      label,
      nodes: NODE_TYPES.filter((node) => {
        if (node.group !== label) return false;
        if (!query) return true;
        return [node.label, node.value, node.description, node.icon]
          .join(" ")
          .toLowerCase()
          .includes(query);
      }),
    }))
    .filter((group) => group.nodes.length);
});
const graphSize = computed(() => {
  const width = Math.max(
    860,
    ...nodes.value.map(
      (node) =>
        Number((node.position as JsonMap)?.x || 0) + GRAPH_NODE_WIDTH + 100,
    ),
  );
  const height = Math.max(
    560,
    ...nodes.value.map(
      (node) =>
        Number((node.position as JsonMap)?.y || 0) + GRAPH_NODE_HEIGHT + 100,
    ),
  );
  return { width, height };
});

function headers(json = false) {
  return makeHeaders(json, currentOrgId());
}
async function api(path: string, options: RequestInit = {}) {
  return await readJson<JsonMap>(
    await fetch(path, {
      ...options,
      headers: {
        ...headers(Boolean(options.body)),
        ...(options.headers || {}),
      },
    }),
  );
}

async function loadNodeCatalogs() {
  const domain = encodeURIComponent(currentDomain("platform"));
  const safeLoad = async (path: string): Promise<JsonMap> => {
    try {
      return await api(path);
    } catch {
      return {};
    }
  };
  const [agents, models, skills, mcps] = await Promise.all([
    safeLoad(`/platform/frontend/agents?domain=${domain}`),
    safeLoad(`/platform/frontend/models?domain=${domain}`),
    safeLoad(`/platform/frontend/skills?domain=${domain}`),
    safeLoad("/platform/frontend/mcp"),
  ]);
  catalogAgents.value = (Array.isArray(agents.items) ? agents.items : agents.agents || []) as JsonMap[];
  catalogModels.value = (Array.isArray(models.items) ? models.items : models.models || []) as JsonMap[];
  catalogSkills.value = (Array.isArray(skills) ? skills : skills.items || skills.skills || []) as JsonMap[];
  catalogMcpServers.value = (Array.isArray(mcps.items) ? mcps.items : mcps.mcp_servers || mcps.servers || []) as JsonMap[];
}

function nodeTypeLabel(type: string) {
  return NODE_TYPES.find((item) => item.value === type)?.label || type;
}

function nodeTypeMeta(type: string) {
  return NODE_TYPES.find((item) => item.value === type) || NODE_TYPES[0];
}

function nodeTone(type: string): string {
  const group = nodeTypeMeta(type).group;
  return group === "AI"
    ? "ai"
    : group === "业务"
      ? "business"
      : group === "控制"
        ? "control"
        : "flow";
}

function nodeConfig(node: JsonMap): JsonMap {
  return (node.config && typeof node.config === "object" && !Array.isArray(node.config)
    ? node.config
    : {}) as JsonMap;
}

function compactText(value: unknown, fallback = "尚未配置"): string {
  const text = String(value || "").replace(/\s+/g, " ").trim();
  return text ? (text.length > 38 ? `${text.slice(0, 38)}…` : text) : fallback;
}

function catalogName(items: JsonMap[], id: unknown, key: string): string {
  const value = String(id || "");
  const item = items.find((row) => String(row[key] || row.id || "") === value);
  return String(item?.name || item?.display_name || item?.alias_name || value);
}

function nodeSummary(node: JsonMap): string {
  const type = String(node.type || "");
  const config = nodeConfig(node);
  if (type === "workflow.input" || type === "workflow.output") {
    const schema = nodeConfig({ config: config.schema as JsonMap });
    const propertyCount = Object.keys(nodeConfig({ config: schema.properties as JsonMap })).length;
    const label = String(schema.type || "string");
    return propertyCount ? `${label} · ${propertyCount} 个字段` : label;
  }
  if (type === "agent.invoke" || type === "agent.react")
    return catalogName(catalogAgents.value, node.refId, "agent_id") || "请选择 Agent";
  if (type === "llm.chat")
    return node.refId ? catalogName(catalogModels.value, node.refId, "model_id") : "平台默认模型";
  if (type === "skill.invoke")
    return `${catalogName(catalogSkills.value, node.refId, "skill_id") || "请选择 Skill"}`;
  if (type === "mcp.invoke")
    return `${catalogName(catalogMcpServers.value, node.refId, "id") || "请选择 MCP"}`;
  if (type === "subflow.invoke")
    return catalogName(workflows.value, node.refId, "workflow_id") || "请选择 Workflow";
  if (type === "foreach")
    return `${config.target_type === "workflow" ? "Workflow" : "Agent"} · 并发 ${Number(config.concurrency || 1)}`;
  if (type === "http.request" || type === "message.send")
    return `${String(config.method || "POST")} · ${compactText(config.url)}`;
  if (type === "database.query" || type === "database.write")
    return compactText(config.sql, "请配置参数化 SQL");
  if (type === "data.transform") {
    if (Object.keys(nodeConfig({ config: config.mapping as JsonMap })).length) return "字段映射";
    if (config.template) return compactText(config.template);
    if (config.path) return `提取 ${config.path}`;
    return "原样透传";
  }
  if (type === "human.approval") return compactText(config.title, "需要人工审批");
  if (type === "condition") return "根据连线条件选择分支";
  if (type === "parallel") return "并行启动下游分支";
  if (type === "join") return "等待并汇总分支结果";
  if (type === "return") return "返回当前数据";
  return compactText(node.instruction);
}

function nodeIsConfigured(node: JsonMap): boolean {
  const type = String(node.type || "");
  const config = nodeConfig(node);
  if (["workflow.input", "workflow.output", "return", "condition", "parallel", "join"].includes(type)) return true;
  if (["agent.invoke", "agent.react", "subflow.invoke", "llm.chat", "foreach"].includes(type))
    return type === "llm.chat" || (type === "foreach" && !node.refId) || Boolean(String(node.refId || "").trim());
  if (type === "skill.invoke" || type === "mcp.invoke") return Boolean(String(node.refId || "").trim() && String(config.agent_id || "").trim());
  if (type === "http.request" || type === "message.send") {
    const method = String(config.method || "POST").toUpperCase();
    return Boolean(String(config.url || "").trim() && (["GET", "DELETE"].includes(method) || String(config.idempotency_key || "").trim()));
  }
  if (type === "database.query" || type === "database.write")
    return Boolean(
      String(config.jdbc_url || "").trim() &&
        String(config.sql || "").trim() &&
        (type !== "database.write" || String(config.sql).includes("{{idempotency_key}}")),
    );
  if (type === "human.approval") return Boolean(String(node.instruction || "").trim());
  return true;
}

function isBoundaryNode(node: JsonMap | null | undefined): boolean {
  return node?.type === "workflow.input" || node?.type === "workflow.output";
}

function boundaryNode(
  type: "workflow.input" | "workflow.output",
  nodeId: string,
  position: { x: number; y: number },
  schema: JsonMap = {},
): JsonMap {
  return {
    nodeId,
    type,
    refId: "",
    instruction: "",
    config: { schema },
    inputMapping: {},
    outputSchema: {},
    timeoutMs: null,
    maxRetries: 0,
    failurePolicy: "FAIL_FAST",
    inputPorts: defaultPorts(type, true, { schema }),
    outputPorts: defaultPorts(type, false, { schema }),
    position,
  };
}

function defaultPorts(
  type: string,
  input: boolean,
  config: JsonMap = {},
): JsonMap[] {
  if (
    (type === "workflow.input" && input) ||
    (type === "workflow.output" && !input)
  )
    return [];
  return [
    {
      portId: "value",
      direction: input ? "input" : "output",
      contractRef:
        type === "workflow.input"
          ? "workflow.input"
          : type === "workflow.output"
            ? "workflow.output"
            : "",
      schema: (config.schema || {}) as JsonMap,
      required: input && type !== "workflow.input",
      cardinality: input && type === "join" ? "many" : "one",
      description: input ? "节点输入" : "节点输出",
    },
  ];
}

function defaultNodeConfig(type: string): JsonMap {
  if (type === "http.request" || type === "message.send")
    return { method: "POST", url: "", body: "{{input}}", idempotency_key: "" };
  if (type === "database.query")
    return {
      jdbc_url: "",
      sql: "",
      parameters: [],
      max_rows: 1000,
      username: "",
      password: "",
    };
  if (type === "database.write")
    return {
      jdbc_url: "",
      sql: "",
      parameters: [],
      idempotency_key: "",
      username: "",
      password: "",
    };
  if (type === "data.transform") return { mapping: {} };
  if (type === "foreach") return { target_type: "agent", concurrency: 1 };
  if (type === "human.approval") return { title: "需要人工审批" };
  if (type === "skill.invoke" || type === "mcp.invoke") return { agent_id: "" };
  return {};
}

function normalizePorts(
  raw: unknown,
  type: string,
  input: boolean,
  config: JsonMap = {},
): JsonMap[] {
  if (Array.isArray(raw) && raw.length) {
    return raw.map((port) => {
      const item = (port || {}) as JsonMap;
      return {
        portId: String(item.portId || item.port_id || "value"),
        direction: String(item.direction || (input ? "input" : "output")),
        contractRef: String(item.contractRef || item.contract_ref || ""),
        schema: (item.schema || {}) as JsonMap,
        required: item.required === true,
        cardinality: String(item.cardinality || "one"),
        description: String(item.description || ""),
      };
    });
  }
  return defaultPorts(type, input, config);
}

function nodeInputPorts(node: JsonMap): JsonMap[] {
  return (node.inputPorts || []) as JsonMap[];
}
function nodeOutputPorts(node: JsonMap): JsonMap[] {
  return (node.outputPorts || []) as JsonMap[];
}

function withBoundaryNodes(
  rawNodes: JsonMap[],
  inputSchema: JsonMap = {},
  outputSchema: JsonMap = {},
): JsonMap[] {
  const result = [...rawNodes];
  if (!result.some((node) => node.type === "workflow.input")) {
    const minX = result.length
      ? Math.min(
          ...result.map((node) => Number((node.position as JsonMap)?.x || 70)),
        )
      : 70;
    result.unshift(
      boundaryNode(
        "workflow.input",
        "workflow_input",
        { x: Math.max(20, minX - GRAPH_NODE_WIDTH - 60), y: 60 },
        inputSchema,
      ),
    );
  } else {
    const input = result.find((node) => node.type === "workflow.input");
    if (input && !(input.config as JsonMap)?.schema)
      input.config = {
        ...((input.config || {}) as JsonMap),
        schema: inputSchema,
      };
  }
  if (!result.some((node) => node.type === "workflow.output")) {
    const maxX = result.length
      ? Math.max(
          ...result.map((node) => Number((node.position as JsonMap)?.x || 70)),
        )
      : 70;
    result.push(
      boundaryNode(
        "workflow.output",
        "workflow_output",
        { x: maxX + GRAPH_NODE_WIDTH + 60, y: 60 },
        outputSchema,
      ),
    );
  } else {
    const output = result.find((node) => node.type === "workflow.output");
    if (output && !(output.config as JsonMap)?.schema)
      output.config = {
        ...((output.config || {}) as JsonMap),
        schema: outputSchema,
      };
  }
  return result;
}

function positionValue(value: unknown, fallback: number) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function workflowLabel(item: JsonMap) {
  return `${item.name || item.workflow_id} · ${item.workflow_id}`;
}

function normalizeNode(raw: JsonMap, index: number): JsonMap {
  const config = { ...((raw.config || {}) as JsonMap) };
  const type = String(raw.type || "agent.invoke");
  const savedPosition = (raw.position ||
    raw.canvas_position ||
    config.canvas_position ||
    {}) as JsonMap;
  return {
    nodeId: String(
      raw.nodeId ||
        raw.node_id ||
        raw.stepId ||
        raw.step_id ||
        `node_${index + 1}`,
    ),
    type,
    refId: String(raw.refId || raw.ref_id || raw.agentId || raw.agent_id || ""),
    instruction: String(raw.instruction || ""),
    config,
    position: {
      x: positionValue(savedPosition.x, 70 + (index % 3) * 280),
      y: positionValue(savedPosition.y, 60 + Math.floor(index / 3) * 180),
    },
    inputMapping: {
      ...((raw.inputMapping || raw.input_mapping || {}) as JsonMap),
    },
    outputSchema: {
      ...((raw.outputSchema || raw.output_schema || {}) as JsonMap),
    },
    timeoutMs: raw.timeoutMs || raw.timeout_ms || null,
    maxRetries: raw.maxRetries || raw.max_retries || 0,
    failurePolicy: raw.failurePolicy || raw.failure_policy || "FAIL_FAST",
    inputPorts: normalizePorts(
      raw.inputPorts || raw.input_ports,
      type,
      true,
      config,
    ),
    outputPorts: normalizePorts(
      raw.outputPorts || raw.output_ports,
      type,
      false,
      config,
    ),
  };
}

function normalizeEdge(raw: JsonMap, index: number): JsonMap {
  const from = (raw.from || {}) as JsonMap;
  const to = (raw.to || {}) as JsonMap;
  return {
    edgeId: String(raw.edgeId || raw.edge_id || `edge_${index + 1}`),
    from: {
      nodeId: String(from.nodeId || from.node_id || ""),
      portId: String(from.portId || from.port_id || "value"),
    },
    to: {
      nodeId: String(to.nodeId || to.node_id || ""),
      portId: String(to.portId || to.port_id || "value"),
    },
    kind: String(raw.kind || "data"),
    binding: (raw.binding || {}) as JsonMap,
    mapping: Object.entries((raw.binding || {}) as JsonMap).map(
      ([targetField, expression]) => ({
        targetField,
        sourcePath:
          typeof expression === "string"
            ? expression
            : String(((expression || {}) as JsonMap).source_path || ""),
      }),
    ),
    condition: (raw.condition || {}) as JsonMap,
    defaultEdge: raw.defaultEdge === true || raw.default_edge === true,
  };
}

async function loadWorkflows() {
  loading.value = true;
  try {
    const [data, tools, schedules] = await Promise.all([
      api(
        `/platform/frontend/workflows?domain=${encodeURIComponent(currentDomain("platform"))}`,
      ),
      api("/platform/frontend/workflow-tools"),
      api("/api/scheduled-tasks"),
    ]);
    workflows.value = (
      Array.isArray(data.items)
        ? data.items
        : Array.isArray(data.workflows)
          ? data.workflows
          : []
    ) as JsonMap[];
    workflowTools.value = (
      Array.isArray(tools.items)
        ? tools.items
        : Array.isArray(tools.tools)
          ? tools.tools
          : []
    ) as JsonMap[];
    scheduledTasks.value = (
      Array.isArray(schedules.items) ? schedules.items : []
    ) as JsonMap[];
    if (
      selectedWorkflowId.value &&
      workflows.value.some(
        (item) => String(item.workflow_id) === selectedWorkflowId.value,
      )
    ) {
      await selectWorkflow(selectedWorkflowId.value);
    } else if (workflows.value.length) {
      await selectWorkflow(String(workflows.value[0].workflow_id));
    } else {
      clearEditor();
    }
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

function workflowItem(data: JsonMap) {
  return (data.item || data.workflow || data) as JsonMap;
}

function applyWorkflow(item: JsonMap) {
  const workflowId = String(item.workflow_id || "");
  if (!workflowId) throw new Error("Workflow 响应缺少 workflow_id");
  const previousSelection = selectedNodeId.value;
  selectedWorkflowId.value = workflowId;
  workflow.value = item;
  nodes.value = withBoundaryNodes(
    (Array.isArray(item.nodes) ? item.nodes : []).map(normalizeNode),
    (item.input_schema || {}) as JsonMap,
    (item.output_schema || {}) as JsonMap,
  );
  edges.value = (Array.isArray(item.edges) ? item.edges : []).map(
    normalizeEdge,
  );
  selectedNodeId.value = nodes.value.some(
    (node) => String(node.nodeId) === previousSelection,
  )
    ? previousSelection
    : String(nodes.value[0]?.nodeId || "");
}

function graphPoint(event: PointerEvent | DragEvent) {
  const canvas = graphCanvasRef.value;
  if (!canvas) return { x: 70, y: 60 };
  const rect = canvas.getBoundingClientRect();
  return {
    x: Math.max(
      20,
      (event.clientX - rect.left + canvas.scrollLeft) / canvasZoom.value,
    ),
    y: Math.max(
      20,
      (event.clientY - rect.top + canvas.scrollTop) / canvasZoom.value,
    ),
  };
}

function clampCanvasZoom(value: number) {
  return Math.min(MAX_CANVAS_ZOOM, Math.max(MIN_CANVAS_ZOOM, value));
}

function setCanvasZoom(value: number, clientX?: number, clientY?: number) {
  const nextZoom = clampCanvasZoom(Math.round(value * 10) / 10);
  const previousZoom = canvasZoom.value;
  if (nextZoom === previousZoom) return;
  const canvas = graphCanvasRef.value;
  if (!canvas) {
    canvasZoom.value = nextZoom;
    return;
  }
  const rect = canvas.getBoundingClientRect();
  const anchorX = clientX == null ? rect.width / 2 : clientX - rect.left;
  const anchorY = clientY == null ? rect.height / 2 : clientY - rect.top;
  const worldX = (canvas.scrollLeft + anchorX) / previousZoom;
  const worldY = (canvas.scrollTop + anchorY) / previousZoom;
  canvasZoom.value = nextZoom;
  requestAnimationFrame(() => {
    canvas.scrollLeft = worldX * nextZoom - anchorX;
    canvas.scrollTop = worldY * nextZoom - anchorY;
  });
}

function zoomCanvas(direction: number) {
  setCanvasZoom(canvasZoom.value + direction * CANVAS_ZOOM_STEP);
}

function handleCanvasWheel(event: WheelEvent) {
  if (!event.ctrlKey && !event.metaKey) return;
  event.preventDefault();
  setCanvasZoom(
    canvasZoom.value + (event.deltaY < 0 ? CANVAS_ZOOM_STEP : -CANVAS_ZOOM_STEP),
    event.clientX,
    event.clientY,
  );
}

function fitCanvasView() {
  const canvas = graphCanvasRef.value;
  if (!canvas || !nodes.value.length) return;
  const positions = nodes.value.map((node) =>
    (node.position || { x: 0, y: 0 }) as JsonMap,
  );
  const minX = Math.min(...positions.map((position) => Number(position.x || 0)));
  const minY = Math.min(...positions.map((position) => Number(position.y || 0)));
  const maxX = Math.max(
    ...positions.map((position) => Number(position.x || 0) + GRAPH_NODE_WIDTH),
  );
  const maxY = Math.max(
    ...positions.map((position) => Number(position.y || 0) + GRAPH_NODE_HEIGHT),
  );
  const contentWidth = Math.max(GRAPH_NODE_WIDTH, maxX - minX);
  const contentHeight = Math.max(GRAPH_NODE_HEIGHT, maxY - minY);
  const nextZoom = clampCanvasZoom(
    Math.min(
      1.2,
      (canvas.clientWidth - 80) / contentWidth,
      (canvas.clientHeight - 80) / contentHeight,
    ),
  );
  canvasZoom.value = Math.round(nextZoom * 10) / 10;
  requestAnimationFrame(() => {
    canvas.scrollLeft =
      ((minX + maxX) / 2) * canvasZoom.value - canvas.clientWidth / 2;
    canvas.scrollTop =
      ((minY + maxY) / 2) * canvasZoom.value - canvas.clientHeight / 2;
  });
}

function resetCanvasView() {
  canvasZoom.value = 1;
  requestAnimationFrame(() => {
    const canvas = graphCanvasRef.value;
    if (!canvas) return;
    canvas.scrollLeft = 0;
    canvas.scrollTop = 0;
  });
}

function startCanvasPan(event: PointerEvent) {
  if ((event.button !== 0 && event.button !== 1) || connectionState.value) return;
  const canvas = graphCanvasRef.value;
  if (!canvas) return;
  event.preventDefault();
  selectedNodeId.value = "";
  canvasPanning.value = true;
  canvasPanState.value = {
    startX: event.clientX,
    startY: event.clientY,
    scrollLeft: canvas.scrollLeft,
    scrollTop: canvas.scrollTop,
  };
  window.addEventListener("pointermove", moveCanvasPan);
  window.addEventListener("pointerup", stopCanvasPan);
}

function moveCanvasPan(event: PointerEvent) {
  const canvas = graphCanvasRef.value;
  const state = canvasPanState.value;
  if (!canvas || !state) return;
  canvas.scrollLeft = state.scrollLeft - (event.clientX - state.startX);
  canvas.scrollTop = state.scrollTop - (event.clientY - state.startY);
}

function stopCanvasPan() {
  canvasPanning.value = false;
  canvasPanState.value = null;
  window.removeEventListener("pointermove", moveCanvasPan);
  window.removeEventListener("pointerup", stopCanvasPan);
}

function startNodeDrag(event: PointerEvent, node: JsonMap) {
  if (event.button !== 0) return;
  const activeElement = document.activeElement;
  if (
    activeElement instanceof HTMLElement &&
    ["INPUT", "TEXTAREA", "SELECT"].includes(activeElement.tagName)
  ) {
    activeElement.blur();
  }
  const point = graphPoint(event);
  const position = (node.position || { x: 0, y: 0 }) as JsonMap;
  selectedNodeId.value = String(node.nodeId);
  dragState.value = {
    nodeId: String(node.nodeId),
    offsetX: point.x - Number(position.x || 0),
    offsetY: point.y - Number(position.y || 0),
  };
  window.addEventListener("pointermove", moveNode);
  window.addEventListener("pointerup", stopNodeDrag);
}

function moveNode(event: PointerEvent) {
  if (!dragState.value) return;
  const node = nodes.value.find(
    (item) => String(item.nodeId) === dragState.value?.nodeId,
  );
  if (!node) return;
  const point = graphPoint(event);
  node.position = {
    x: Math.max(20, point.x - dragState.value.offsetX),
    y: Math.max(20, point.y - dragState.value.offsetY),
  };
}

function stopNodeDrag() {
  dragState.value = null;
  window.removeEventListener("pointermove", moveNode);
  window.removeEventListener("pointerup", stopNodeDrag);
}

function startPaletteDrag(event: DragEvent, type: string) {
  paletteDragType.value = type;
  event.dataTransfer?.setData("text/workflow-node", type);
  if (event.dataTransfer) event.dataTransfer.effectAllowed = "copy";
}

function dropPaletteNode(event: DragEvent) {
  const type =
    event.dataTransfer?.getData("text/workflow-node") || paletteDragType.value;
  paletteDragType.value = "";
  if (type) addNode(type, graphPoint(event));
}

function startConnection(event: PointerEvent, node: JsonMap, port: JsonMap) {
  const point = graphPoint(event);
  connectionState.value = {
    sourceId: String(node.nodeId),
    sourcePortId: String(port.portId || "value"),
    x: point.x,
    y: point.y,
  };
  window.addEventListener("pointermove", moveConnection);
  window.addEventListener("pointerup", cancelConnection);
}

function moveConnection(event: PointerEvent) {
  if (!connectionState.value) return;
  const point = graphPoint(event);
  connectionState.value = { ...connectionState.value, x: point.x, y: point.y };
}

function finishConnection(event: PointerEvent, target: JsonMap, port: JsonMap) {
  event.stopPropagation();
  const sourceId = connectionState.value?.sourceId;
  const sourcePortId = connectionState.value?.sourcePortId || "value";
  const targetId = String(target.nodeId);
  const targetPortId = String(port.portId || "value");
  if (!sourceId || sourceId === targetId) {
    cancelConnection();
    return;
  }
  const sourceIndex = nodes.value.findIndex(
    (node) => String(node.nodeId) === sourceId,
  );
  const targetIndex = nodes.value.findIndex(
    (node) => String(node.nodeId) === targetId,
  );
  if (targetIndex < 0 || sourceIndex < 0) return cancelConnection();
  const source = nodes.value.find((node) => String(node.nodeId) === sourceId);
  if (!source) return cancelConnection();
  const sourcePort = nodeOutputPorts(source).find(
    (item) => String(item.portId) === sourcePortId,
  );
  const targetPort = nodeInputPorts(target).find(
    (item) => String(item.portId) === targetPortId,
  );
  if (
    !sourcePort ||
    String(sourcePort.direction || "output").toLowerCase() !== "output"
  ) {
    notifyError("连线必须从输出端口开始");
    cancelConnection();
    return;
  }
  if (
    !targetPort ||
    String(targetPort.direction || "input").toLowerCase() !== "input"
  ) {
    notifyError("连线必须连接到输入端口");
    cancelConnection();
    return;
  }
  if (
    !edges.value.some(
      (edge) =>
        String((edge.from as JsonMap)?.nodeId) === sourceId &&
        String((edge.from as JsonMap)?.portId) === sourcePortId &&
        String((edge.to as JsonMap)?.nodeId) === targetId &&
        String((edge.to as JsonMap)?.portId) === targetPortId,
    )
  ) {
    edges.value.push({
      edgeId: `edge_${Date.now()}_${edges.value.length + 1}`,
      from: { nodeId: sourceId, portId: sourcePortId },
      to: { nodeId: targetId, portId: targetPortId },
      kind: "data",
      binding: {},
      mapping: [],
      condition: {},
      defaultEdge: true,
    });
  }
  selectedNodeId.value = sourceId;
  cancelConnection();
}

function cancelConnection() {
  connectionState.value = null;
  window.removeEventListener("pointermove", moveConnection);
  window.removeEventListener("pointerup", cancelConnection);
}

function portCenterY(node: JsonMap, portId: string, input: boolean) {
  const ports = input ? nodeInputPorts(node) : nodeOutputPorts(node);
  const index = Math.max(
    0,
    ports.findIndex((port) => String(port.portId) === portId),
  );
  return (
    Number(((node.position || {}) as JsonMap).y || 0) +
    GRAPH_NODE_HEIGHT * ((index + 1) / (Math.max(1, ports.length) + 1))
  );
}

function edgePath(edge: {
  from: string;
  fromPort: string;
  to: string;
  toPort: string;
}) {
  const from = nodes.value.find((node) => String(node.nodeId) === edge.from);
  const to = nodes.value.find((node) => String(node.nodeId) === edge.to);
  if (!from || !to) return "";
  const fromPosition = (from.position || {}) as JsonMap;
  const toPosition = (to.position || {}) as JsonMap;
  const x1 = Number(fromPosition.x || 0) + GRAPH_NODE_WIDTH;
  const y1 = portCenterY(from, edge.fromPort, false);
  const x2 = Number(toPosition.x || 0);
  const y2 = portCenterY(to, edge.toPort, true);
  const direction = x2 >= x1 ? 1 : -1;
  const bend = Math.max(45, Math.abs(x2 - x1) / 2);
  return `M ${x1} ${y1} C ${x1 + direction * bend} ${y1}, ${x2 - direction * bend} ${y2}, ${x2} ${y2}`;
}

function connectionPath() {
  if (!connectionState.value) return "";
  const source = nodes.value.find(
    (node) => String(node.nodeId) === connectionState.value?.sourceId,
  );
  if (!source) return "";
  const position = (source.position || {}) as JsonMap;
  const x1 = Number(position.x || 0) + GRAPH_NODE_WIDTH;
  const y1 = portCenterY(source, connectionState.value.sourcePortId, false);
  const direction = connectionState.value.x >= x1 ? 1 : -1;
  const bend = Math.max(45, Math.abs(connectionState.value.x - x1) / 2);
  return `M ${x1} ${y1} C ${x1 + direction * bend} ${y1}, ${connectionState.value.x - direction * bend} ${connectionState.value.y}, ${connectionState.value.x} ${connectionState.value.y}`;
}

function graphNodeStyle(node: JsonMap) {
  const position = (node.position || {}) as JsonMap;
  return {
    left: `${Number(position.x || 0)}px`,
    top: `${Number(position.y || 0)}px`,
  };
}

function upsertWorkflow(item: JsonMap) {
  const workflowId = String(item.workflow_id || "");
  if (!workflowId) return;
  workflows.value = [
    item,
    ...workflows.value.filter((row) => String(row.workflow_id) !== workflowId),
  ];
}

function clearEditor() {
  workflow.value = null;
  selectedWorkflowId.value = "";
  nodes.value = [];
  edges.value = [];
  workflowVersions.value = [];
  selectedNodeId.value = "";
  detailDrawerOpen.value = false;
  canvasOpen.value = false;
}

async function openWorkflow(workflowId: string) {
  detailDrawerOpen.value = true;
  await selectWorkflow(workflowId);
}

function openCanvas() {
  if (!workflow.value || !selectedWorkflowId.value) return;
  const url = new URL(window.location.href);
  url.searchParams.set("view", "canvas");
  url.searchParams.set("workflow_id", selectedWorkflowId.value);
  const tab = window.open(url.toString(), "_blank", "noopener,noreferrer");
  if (!tab) notifyError("浏览器拦截了新标签页，请允许本站打开新窗口");
}

function closeCanvas() {
  if (!saving.value && !publishing.value && !testing.value)
    canvasOpen.value = false;
  if (
    standaloneCanvas &&
    !saving.value &&
    !publishing.value &&
    !testing.value
  ) {
    if (window.opener) {
      window.close();
      return;
    }
    const url = new URL(window.location.href);
    url.searchParams.delete("view");
    url.searchParams.delete("workflow_id");
    window.location.href = url.toString();
  }
}

function closeDetailDrawer() {
  if (canvasOpen.value) return;
  detailDrawerOpen.value = false;
}

async function selectWorkflow(workflowId: string) {
  if (!workflowId) return;
  selectedWorkflowId.value = workflowId;
  loading.value = true;
  try {
    const [data, versions] = await Promise.all([
      api(`/platform/frontend/workflows/${encodeURIComponent(workflowId)}`),
      api(
        `/platform/frontend/workflows/${encodeURIComponent(workflowId)}/versions`,
      ),
    ]);
    applyWorkflow(workflowItem(data));
    workflowVersions.value = (
      Array.isArray(versions.items)
        ? versions.items
        : Array.isArray(versions.versions)
          ? versions.versions
          : []
    ) as JsonMap[];
  } catch (error) {
    notifyError(error);
  } finally {
    loading.value = false;
  }
}

async function registerWorkflowTool() {
  if (!workflow.value || !hasActivePublishedVersion.value) {
    notifyError("请先发布 Workflow 版本");
    return;
  }
  const versionOptions = workflowVersions.value.map((item) => ({
    value: String(item.version || ""),
    label: `v${item.version}${Number(item.version) === activePublishedVersion.value ? "（当前活动）" : ""}`,
  }));
  const values = await formDialog({
    title: "注册 Workflow Tool",
    message:
      "注册记录会固定到所选的不可变 Workflow 版本；允许的 Agent 留空表示不按 Agent ID 限制。",
    fields: [
      {
        key: "name",
        label: "工具名称",
        default: String(
          workflow.value.name || workflow.value.workflow_id || "",
        ),
      },
      {
        key: "description",
        label: "工具描述",
        type: "textarea",
        default: String(workflow.value.description || ""),
      },
      {
        key: "workflow_version",
        label: "固定版本",
        type: "select",
        default: String(activePublishedVersion.value),
        options: versionOptions,
      },
      {
        key: "allowed_agents",
        label: "允许的 Agent ID（逗号分隔）",
        placeholder: "agent-a, agent-b",
      },
      {
        key: "visibility",
        label: "可见范围",
        type: "select",
        default: "PRIVATE",
        options: [
          { value: "PRIVATE", label: "仅自己" },
          { value: "ORGANIZATION", label: "当前组织" },
          { value: "PUBLIC", label: "平台公共（仅平台管理员）" },
        ],
      },
    ],
    confirmLabel: "注册 Tool",
  });
  if (!values) return;
  try {
    const data = await api("/platform/frontend/workflow-tools", {
      method: "POST",
      body: JSON.stringify({
        workflow_id: selectedWorkflowId.value,
        workflow_version: Number(
          values.workflow_version || activePublishedVersion.value,
        ),
        name: String(values.name || "").trim(),
        description: String(values.description || "").trim(),
        allowed_agents: String(values.allowed_agents || "")
          .split(",")
          .map((item) => item.trim())
          .filter(Boolean),
        visibility: values.visibility || "PRIVATE",
        enabled: true,
      }),
    });
    workflowTools.value = [
      (data.item || data.tool) as JsonMap,
      ...workflowTools.value,
    ];
    notifySuccess("Workflow Tool 已注册并固定版本");
  } catch (error) {
    notifyError(error);
  }
}

async function toggleWorkflowTool(item: JsonMap) {
  const toolId = String(item.tool_id || "");
  try {
    const data = await api(
      `/platform/frontend/workflow-tools/${encodeURIComponent(toolId)}`,
      {
        method: "PUT",
        body: JSON.stringify({
          workflow_id: item.workflow_id,
          workflow_version: item.workflow_version,
          name: item.name,
          description: item.description,
          allowed_agents: item.allowed_agents || [],
          enabled: item.enabled !== true,
        }),
      },
    );
    const updated = (data.item || data.tool) as JsonMap;
    workflowTools.value = workflowTools.value.map((row) =>
      String(row.tool_id) === toolId ? updated : row,
    );
    notifySuccess(
      updated.enabled ? "Workflow Tool 已启用" : "Workflow Tool 已停用",
    );
  } catch (error) {
    notifyError(error);
  }
}

async function editWorkflowTool(item: JsonMap) {
  const values = await formDialog({
    title: "编辑 Workflow Tool",
    message: `当前固定 ${item.workflow_id}@v${item.workflow_version}；升级版本需显式选择。`,
    fields: [
      { key: "name", label: "工具名称", default: String(item.name || "") },
      {
        key: "description",
        label: "工具描述",
        type: "textarea",
        default: String(item.description || ""),
      },
      {
        key: "workflow_version",
        label: "固定版本",
        default: String(item.workflow_version || ""),
      },
      {
        key: "allowed_agents",
        label: "允许的 Agent ID（逗号分隔）",
        default: Array.isArray(item.allowed_agents)
          ? item.allowed_agents.join(", ")
          : "",
      },
      {
        key: "visibility",
        label: "可见范围",
        type: "select",
        default: String(item.visibility || "PRIVATE"),
        options: [
          { value: "PRIVATE", label: "仅自己" },
          { value: "ORGANIZATION", label: "当前组织" },
          { value: "PUBLIC", label: "平台公共（仅平台管理员）" },
        ],
      },
    ],
    confirmLabel: "保存 Tool",
  });
  if (!values) return;
  const toolId = String(item.tool_id || "");
  try {
    const data = await api(
      `/platform/frontend/workflow-tools/${encodeURIComponent(toolId)}`,
      {
        method: "PUT",
        body: JSON.stringify({
          workflow_id: item.workflow_id,
          workflow_version: Number(
            values.workflow_version || item.workflow_version,
          ),
          name: values.name,
          description: values.description,
          allowed_agents: String(values.allowed_agents || "")
            .split(",")
            .map((value) => value.trim())
            .filter(Boolean),
          visibility: values.visibility,
          enabled: item.enabled === true,
        }),
      },
    );
    const updated = (data.item || data.tool) as JsonMap;
    workflowTools.value = workflowTools.value.map((row) =>
      String(row.tool_id) === toolId ? updated : row,
    );
    notifySuccess("Workflow Tool 已更新");
  } catch (error) {
    notifyError(error);
  }
}

async function deleteWorkflowTool(item: JsonMap) {
  const toolId = String(item.tool_id || "");
  if (!window.confirm(`确定删除 Workflow Tool「${item.name || toolId}」吗？`))
    return;
  try {
    await api(
      `/platform/frontend/workflow-tools/${encodeURIComponent(toolId)}`,
      { method: "DELETE" },
    );
    workflowTools.value = workflowTools.value.filter(
      (row) => String(row.tool_id) !== toolId,
    );
    notifySuccess("Workflow Tool 已删除");
  } catch (error) {
    notifyError(error);
  }
}

async function createWorkflowSchedule() {
  if (!workflow.value || !hasActivePublishedVersion.value) {
    notifyError("请先发布 Workflow 版本");
    return;
  }
  const values = await formDialog({
    title: "创建定时触发器",
    message: `定时任务将固定到当前活动版本 v${activePublishedVersion.value}。`,
    fields: [
      {
        key: "name",
        label: "任务名称",
        default: `${String(workflow.value.name || selectedWorkflowId.value)} 定时运行`,
      },
      {
        key: "prompt",
        label: "运行输入",
        type: "textarea",
        placeholder: "每次触发时传给 Workflow 的输入",
      },
      { key: "cron", label: "Cron（5 位或 6 位）", default: "0 0 9 * * *" },
      { key: "timezone", label: "时区", default: "Asia/Shanghai" },
      {
        key: "webhook_url",
        label: "结果回调 URL（可选）",
        placeholder: "https://example.com/hooks/result",
      },
    ],
    confirmLabel: "创建触发器",
  });
  if (!values) return;
  try {
    const data = await api("/api/scheduled-tasks", {
      method: "POST",
      body: JSON.stringify({
        workflow_id: selectedWorkflowId.value,
        name: values.name,
        prompt: values.prompt,
        cron: values.cron,
        timezone: values.timezone,
        webhook_url: values.webhook_url,
        enabled: true,
      }),
    });
    scheduledTasks.value = [data.item as JsonMap, ...scheduledTasks.value];
    notifySuccess("定时触发器已创建并固定 Workflow 版本");
  } catch (error) {
    notifyError(error);
  }
}

async function toggleWorkflowSchedule(item: JsonMap) {
  const taskId = String(item.task_id || "");
  try {
    const data = await api(
      `/api/scheduled-tasks/${encodeURIComponent(taskId)}/${item.enabled ? "disable" : "enable"}`,
      { method: "POST" },
    );
    const updated = data.item as JsonMap;
    scheduledTasks.value = scheduledTasks.value.map((row) =>
      String(row.task_id) === taskId ? updated : row,
    );
    notifySuccess(updated.enabled ? "定时触发器已启用" : "定时触发器已暂停");
  } catch (error) {
    notifyError(error);
  }
}

async function deleteWorkflowSchedule(item: JsonMap) {
  const taskId = String(item.task_id || "");
  if (!window.confirm(`确定删除定时触发器「${item.name || taskId}」吗？`))
    return;
  try {
    await api(`/api/scheduled-tasks/${encodeURIComponent(taskId)}`, {
      method: "DELETE",
    });
    scheduledTasks.value = scheduledTasks.value.filter(
      (row) => String(row.task_id) !== taskId,
    );
    notifySuccess("定时触发器已删除");
  } catch (error) {
    notifyError(error);
  }
}

async function createWorkflow() {
  if (creating.value) return;
  const values = await formDialog({
    title: "新建 Workflow",
    message: "先创建流程资产，创建后可在画布中配置输入、输出节点和调用入口。",
    fields: [
      {
        key: "name",
        label: "Workflow 名称",
        placeholder: "例如：订单审核流程",
      },
      {
        key: "description",
        label: "描述",
        type: "textarea",
        placeholder: "这个流程解决什么业务问题？",
      },
    ],
    confirmLabel: "创建 Workflow",
  });
  if (!values) return;
  const name = String(values.name || "").trim();
  if (!name) {
    notifyError("请输入 Workflow 名称");
    return;
  }
  creating.value = true;
  try {
    const data = await api("/platform/frontend/workflows", {
      method: "POST",
      body: JSON.stringify({
        name,
        description: String(values.description || "").trim(),
        domain: currentDomain("platform"),
        nodes: [
          boundaryNode("workflow.input", "workflow_input", { x: 70, y: 60 }),
          boundaryNode("workflow.output", "workflow_output", { x: 430, y: 60 }),
        ],
      }),
    });
    const item = workflowItem(data);
    applyWorkflow(item);
    upsertWorkflow(item);
    await selectWorkflow(selectedWorkflowId.value);
    await loadWorkflows();
    detailDrawerOpen.value = true;
    notifySuccess("Workflow 已创建");
  } catch (error) {
    notifyError(error);
  } finally {
    creating.value = false;
  }
}

function addNode(type = "agent.invoke", position?: { x: number; y: number }) {
  if (type === "workflow.input" || type === "workflow.output") {
    const nodeId = `${type === "workflow.input" ? "workflow_input" : "workflow_output"}_${nodes.value.filter((node) => node.type === type).length + 1}`;
    nodes.value.push(
      boundaryNode(
        type,
        nodeId,
        position || {
          x: 70 + (nodes.value.length % 3) * 280,
          y: 60 + Math.floor(nodes.value.length / 3) * 180,
        },
      ),
    );
    selectedNodeId.value = nodeId;
    return;
  }
  const node: JsonMap = {
    nodeId: `node_${nodes.value.length + 1}`,
    type,
    refId: "",
    instruction: "",
    config: defaultNodeConfig(type),
    inputMapping: {},
    outputSchema: {},
    timeoutMs: null,
    maxRetries: 0,
    failurePolicy: "FAIL_FAST",
    inputPorts: defaultPorts(type, true),
    outputPorts: defaultPorts(type, false),
    position: position || {
      x: 70 + (nodes.value.length % 3) * 280,
      y: 60 + Math.floor(nodes.value.length / 3) * 180,
    },
  };
  nodes.value.push(node);
  selectedNodeId.value = String(node.nodeId);
}

function removeSelectedNode() {
  const index = nodes.value.findIndex(
    (node) => node.nodeId === selectedNodeId.value,
  );
  if (index < 0) return;
  if (isBoundaryNode(nodes.value[index])) {
    notifyError("Workflow 输入和输出是必需的边界节点，不能删除");
    return;
  }
  nodes.value.splice(index, 1);
  edges.value = edges.value.filter((edge) => {
    const from = (edge.from || {}) as JsonMap;
    const to = (edge.to || {}) as JsonMap;
    return (
      String(from.nodeId) !== selectedNodeId.value &&
      String(to.nodeId) !== selectedNodeId.value
    );
  });
  selectedNodeId.value = nodes.value[Math.max(0, index - 1)]?.nodeId || "";
}

function normalizeNodeType(node: JsonMap) {
  if (node.type === "workflow.input" || node.type === "workflow.output") {
    node.refId = "";
    node.instruction = "";
    node.config = {
      ...((node.config || {}) as JsonMap),
      schema: (((node.config || {}) as JsonMap).schema || {}) as JsonMap,
    };
  } else {
    node.refId = "";
    node.instruction = "";
    node.config = defaultNodeConfig(String(node.type || "agent.invoke"));
  }
  node.inputPorts = normalizePorts(
    node.inputPorts,
    String(node.type || "agent.invoke"),
    true,
    (node.config || {}) as JsonMap,
  );
  node.outputPorts = normalizePorts(
    node.outputPorts,
    String(node.type || "agent.invoke"),
    false,
    (node.config || {}) as JsonMap,
  );
}

function syncBoundarySchema(node: JsonMap) {
  if (!isBoundaryNode(node)) return;
  const schema = (nodeConfig(node).schema || {}) as JsonMap;
  if (node.type === "workflow.input") {
    node.outputPorts = nodeOutputPorts(node).map((port) => ({ ...port, schema }));
  } else {
    node.inputPorts = nodeInputPorts(node).map((port) => ({ ...port, schema }));
  }
}

function addPort(node: JsonMap, input: boolean) {
  const key = input ? "inputPorts" : "outputPorts";
  if (!Array.isArray(node[key])) node[key] = [];
  (node[key] as JsonMap[]).push({
    portId: `${input ? "input" : "output"}_${(node[key] as JsonMap[]).length + 1}`,
    direction: input ? "input" : "output",
    contractRef: "",
    schema: {},
    required: input,
    cardinality: "one",
    description: "",
  });
}

function removePort(node: JsonMap, input: boolean, index: number) {
  const key = input ? "inputPorts" : "outputPorts";
  const ports = (node[key] || []) as JsonMap[];
  const removed = ports[index];
  ports.splice(index, 1);
  const portId = String(removed?.portId || "");
  if (!portId) return;
  edges.value = edges.value.filter((edge) => {
    const from = (edge.from || {}) as JsonMap;
    const to = (edge.to || {}) as JsonMap;
    return !(input
      ? String(to.nodeId) === String(node.nodeId) &&
        String(to.portId) === portId
      : String(from.nodeId) === String(node.nodeId) &&
        String(from.portId) === portId);
  });
}

function updateSelectedNodeId(node: JsonMap, value: string) {
  const next = String(value || "").trim();
  const previous = String(node.nodeId || "");
  if (
    !next ||
    next === previous ||
    nodes.value.some((item) => item !== node && String(item.nodeId) === next)
  ) {
    node.nodeId = previous;
    return;
  }
  node.nodeId = next;
  edges.value.forEach((edge) => {
    const from = (edge.from || {}) as JsonMap;
    const to = (edge.to || {}) as JsonMap;
    if (String(from.nodeId) === previous) from.nodeId = next;
    if (String(to.nodeId) === previous) to.nodeId = next;
  });
  selectedNodeId.value = next;
}

function moveSelected(direction: number) {
  const index = nodes.value.findIndex(
    (node) => node.nodeId === selectedNodeId.value,
  );
  const target = index + direction;
  if (index < 0 || target < 0 || target >= nodes.value.length) return;
  const current = nodes.value[index];
  nodes.value[index] = nodes.value[target];
  nodes.value[target] = current;
}

function cleanNodeConfig(node: JsonMap): JsonMap {
  const config = { ...((node.config || {}) as JsonMap) };
  delete config.schema_text;
  config.canvas_position = { ...((node.position || {}) as JsonMap) };
  return config;
}

function cleanNodes() {
  return nodes.value
    .map((node) => ({
      nodeId: String(node.nodeId || "").trim(),
      type: String(node.type || "agent.invoke"),
      refId: String(node.refId || "").trim(),
      instruction: String(node.instruction || "").trim(),
      config: cleanNodeConfig(node),
      inputMapping: (node.inputMapping || {}) as JsonMap,
      outputSchema: (node.outputSchema || {}) as JsonMap,
      timeoutMs: node.timeoutMs || null,
      maxRetries: Number(node.maxRetries || 0),
      failurePolicy: node.failurePolicy || "FAIL_FAST",
      inputPorts: normalizePorts(
        node.inputPorts,
        String(node.type || "agent.invoke"),
        true,
        (node.config || {}) as JsonMap,
      ),
      outputPorts: normalizePorts(
        node.outputPorts,
        String(node.type || "agent.invoke"),
        false,
        (node.config || {}) as JsonMap,
      ),
    }))
    .filter((node) => node.nodeId);
}

function cleanEdges() {
  return edges.value
    .map((edge) => {
      const from = (edge.from || {}) as JsonMap;
      const to = (edge.to || {}) as JsonMap;
      const binding: JsonMap = {};
      for (const mapping of (edge.mapping || []) as JsonMap[]) {
        const target = String(mapping.targetField || "").trim();
        const source = String(mapping.sourcePath || "").trim();
        if (target && source) binding[target] = source;
      }
      return {
        edgeId: String(edge.edgeId || "").trim(),
        from: {
          nodeId: String(from.nodeId || "").trim(),
          portId: String(from.portId || "value").trim(),
        },
        to: {
          nodeId: String(to.nodeId || "").trim(),
          portId: String(to.portId || "value").trim(),
        },
        kind: String(edge.kind || "data"),
        binding,
        condition: (edge.condition || {}) as JsonMap,
        defaultEdge: edge.defaultEdge === true,
      };
    })
    .filter(
      (edge) =>
        edge.edgeId &&
        edge.from.nodeId &&
        edge.to.nodeId &&
        edge.from.portId &&
        edge.to.portId,
    );
}

function removeEdge(edge: JsonMap) {
  edges.value = edges.value.filter(
    (item) => item !== edge && String(item.edgeId) !== String(edge.edgeId),
  );
}

function portStyle(_node: JsonMap, index: number, count: number) {
  return { top: `${100 * ((index + 1) / (Math.max(1, count) + 1))}%` };
}

function boundarySchema(type: "workflow.input" | "workflow.output"): JsonMap {
  const node = nodes.value.find((item) => item.type === type);
  if (!node) return {};
  const config = (node.config || {}) as JsonMap;
  return (config.schema || {}) as JsonMap;
}

function boundaryNodeLabel(type: "workflow.input" | "workflow.output"): string {
  return nodes.value.find((node) => node.type === type)?.nodeId || "未配置";
}

async function saveWorkflow(showNotice = true) {
  if (!workflow.value || !selectedWorkflowId.value) return false;
  saving.value = true;
  try {
    const clean = cleanNodes();
    const data = await api(
      `/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}`,
      {
        method: "PUT",
        body: JSON.stringify({
          workflow_id: selectedWorkflowId.value,
          name: String(workflow.value.name || "").trim(),
          description: String(workflow.value.description || "").trim(),
          domain: workflow.value.domain || currentDomain("platform"),
          trigger_type: workflow.value.trigger_type || "manual",
          status: "DRAFT",
          input_schema: boundarySchema("workflow.input"),
          output_schema: boundarySchema("workflow.output"),
          nodes: clean,
          edges: cleanEdges(),
        }),
      },
    );
    const item = workflowItem(data);
    applyWorkflow(item);
    upsertWorkflow(item);
    await selectWorkflow(selectedWorkflowId.value);
    nodes.value = clean.map(normalizeNode);
    if (showNotice) notifySuccess("Workflow 草稿已保存");
    return true;
  } catch (error) {
    notifyError(error);
    return false;
  } finally {
    saving.value = false;
  }
}

async function validateWorkflow() {
  if (!workflow.value || !selectedWorkflowId.value || validating.value) return;
  validating.value = true;
  try {
    const data = await api(
      `/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}/validate`,
      {
        method: "POST",
        body: JSON.stringify({
          name: String(workflow.value.name || "").trim(),
          description: String(workflow.value.description || "").trim(),
          domain: workflow.value.domain || currentDomain("platform"),
          trigger_type: workflow.value.trigger_type || "manual",
          input_schema: boundarySchema("workflow.input"),
          output_schema: boundarySchema("workflow.output"),
          nodes: cleanNodes(),
          edges: cleanEdges(),
        }),
      },
    );
    validation.value = data;
    if (data.valid === false)
      notifyError("Workflow 契约校验未通过，请查看右侧诊断");
    else notifySuccess("Workflow 契约校验通过");
  } catch (error) {
    notifyError(error);
  } finally {
    validating.value = false;
  }
}

async function publishWorkflow() {
  if (!workflow.value) return;
  if (!(await saveWorkflow(false))) return;
  publishing.value = true;
  try {
    const data = await api(
      `/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}/publish`,
      { method: "POST" },
    );
    const item = workflowItem(data);
    applyWorkflow(item);
    upsertWorkflow(item);
    notifySuccess("Workflow 已发布");
  } catch (error) {
    notifyError(error);
  } finally {
    publishing.value = false;
  }
}

async function unpublishWorkflow() {
  if (!workflow.value) return;
  try {
    const data = await api(
      `/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}/unpublish`,
      { method: "POST" },
    );
    const item = workflowItem(data);
    applyWorkflow(item);
    upsertWorkflow(item);
    notifySuccess("Workflow 已退回草稿");
  } catch (error) {
    notifyError(error);
  }
}

async function deleteWorkflow() {
  if (
    !workflow.value ||
    !window.confirm(`确定删除 Workflow「${workflow.value.name}」吗？`)
  )
    return;
  try {
    await api(
      `/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}`,
      { method: "DELETE" },
    );
    detailDrawerOpen.value = false;
    canvasOpen.value = false;
    clearEditor();
    await loadWorkflows();
    notifySuccess("Workflow 已删除");
  } catch (error) {
    notifyError(error);
  }
}

async function runWorkflow() {
  if (!workflow.value || !hasActivePublishedVersion.value) {
    notifyError("请先发布 Workflow 后再运行");
    return;
  }
  if (!testInput.value.trim()) {
    notifyError("请输入测试请求");
    return;
  }
  testing.value = true;
  testOutput.value = "";
  testEvents.value = [];
  try {
    const response = await fetch(
      `/platform/frontend/workflows/${encodeURIComponent(selectedWorkflowId.value)}/run/stream`,
      {
        method: "POST",
        headers: headers(true),
        body: JSON.stringify({
          query: testInput.value.trim(),
          session_id: `workflow_${Date.now()}`,
        }),
      },
    );
    if (!response.ok || !response.body)
      throw new Error(`HTTP ${response.status}`);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const parts = buffer.split("\n\n");
      buffer = parts.pop() || "";
      for (const part of parts) {
        const line = part.split("\n").find((item) => item.startsWith("data:"));
        if (!line) continue;
        const event = JSON.parse(line.slice(5).trim()) as JsonMap;
        testEvents.value.push(event);
        if (event.type === "token")
          testOutput.value += String(event.delta || "");
        if (event.type === "done")
          testOutput.value = String(
            (event.result as JsonMap)?.answer || testOutput.value,
          );
        if (event.type === "waiting_user_input") {
          const waiting = (event.waiting || {}) as JsonMap;
          testOutput.value = `运行已挂起，等待人工审批：${String(waiting.question || waiting.waiting_id || "")}`;
        }
        if (event.type === "error")
          throw new Error(String(event.message || "运行失败"));
      }
    }
  } catch (error) {
    notifyError(error);
  } finally {
    testing.value = false;
  }
}

function handleCanvasKeydown(event: KeyboardEvent) {
  if (!canvasOpen.value || !workflow.value) return;
  const target = event.target as HTMLElement | null;
  const editing =
    target?.isContentEditable ||
    ["INPUT", "TEXTAREA", "SELECT"].includes(target?.tagName || "");
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
    event.preventDefault();
    if (!saving.value) void saveWorkflow();
    return;
  }
  if (editing) return;
  if (event.key === "Escape") {
    if (testPanelOpen.value) {
      testPanelOpen.value = false;
      return;
    }
    cancelConnection();
    stopNodeDrag();
    stopCanvasPan();
    return;
  }
  if ((event.key === "Delete" || event.key === "Backspace") && selectedNode.value) {
    event.preventDefault();
    removeSelectedNode();
    return;
  }
  if (event.key === "0") {
    event.preventDefault();
    fitCanvasView();
    return;
  }
  if (event.key === "+" || event.key === "=") {
    event.preventDefault();
    zoomCanvas(1);
    return;
  }
  if (event.key === "-") {
    event.preventDefault();
    zoomCanvas(-1);
  }
}

onMounted(async () => {
  window.addEventListener("keydown", handleCanvasKeydown);
  if (standaloneCanvas && requestedWorkflowId)
    selectedWorkflowId.value = requestedWorkflowId;
  await Promise.all([loadWorkflows(), loadNodeCatalogs()]);
  if (standaloneCanvas && workflow.value) {
    canvasOpen.value = true;
    await nextTick();
    fitCanvasView();
  }
});
onUnmounted(() => {
  stopNodeDrag();
  cancelConnection();
  stopCanvasPan();
  window.removeEventListener("keydown", handleCanvasKeydown);
});
</script>

<template>
  <section
    :class="[
      'orchestration-page',
      { 'standalone-orchestration-page': standaloneCanvas },
    ]"
  >
    <template v-if="!standaloneCanvas">
      <header class="orchestration-header">
        <div>
          <div class="eyebrow">ORCHESTRATION CENTER</div>
          <h1>编排工作台</h1>
          <p>Workflow 是独立平台资产，可被手动、API 或 Agent Tool 调用。</p>
        </div>
        <button
          class="btn btn-primary"
          :disabled="creating"
          @click="createWorkflow"
        >
          {{ creating ? "创建中…" : "＋ 新建 Workflow" }}
        </button>
      </header>

      <section class="asset-library panel">
        <div class="library-toolbar">
          <div>
            <div class="panel-title">Workflow 资产</div>
            <p class="panel-hint">
              独立保存，不再挂在 Agent 配置下；点击资产查看详情。
            </p>
          </div>
          <span class="library-count">{{ workflows.length }} 个 Workflow</span>
        </div>
        <div v-if="loading && !workflows.length" class="library-empty">
          正在加载 Workflow…
        </div>
        <div v-else-if="!workflows.length" class="library-empty">
          <div class="empty-icon">＋</div>
          <strong>还没有 Workflow</strong
          ><span>点击右上角“新建 Workflow”开始创建。</span>
        </div>
        <div v-else class="workflow-list">
          <button
            v-for="item in workflows"
            :key="String(item.workflow_id)"
            class="workflow-list-item"
            :class="{ selected: item.workflow_id === selectedWorkflowId }"
            @click="openWorkflow(String(item.workflow_id))"
          >
            <span class="workflow-list-icon">↯</span>
            <span class="asset-item-main"
              ><strong>{{ item.name || item.workflow_id }}</strong
              ><small
                >{{ item.workflow_id }} · {{ item.node_count || 0 }} 个节点 ·
                {{ item.trigger_type || "manual" }}</small
              ></span
            >
            <span class="workflow-list-meta"
              ><span
                class="asset-status"
                :class="
                  Number(item.active_published_version || 0) > 0
                    ? 'published'
                    : 'draft'
                "
                >{{
                  item.status === "PUBLISHED"
                    ? `活动 v${item.active_published_version}`
                    : Number(item.active_published_version || 0) > 0
                      ? `草稿 / 活动 v${item.active_published_version}`
                      : "未发布"
                }}</span
              ><span class="workflow-list-arrow">查看详情 ›</span></span
            >
          </button>
        </div>
      </section>

      <section class="asset-library panel">
        <div class="library-toolbar">
          <div>
            <div class="panel-title">Workflow Tools</div>
            <p class="panel-hint">
              将不可变发布版本注册为 Agent 可绑定的工具，可独立启停和限定
              Agent。
            </p>
          </div>
          <span class="library-count">{{ workflowTools.length }} 个 Tool</span>
        </div>
        <div v-if="!workflowTools.length" class="library-empty">
          <strong>还没有 Workflow Tool</strong
          ><span>打开一个已有发布版本的 Workflow 后即可注册。</span>
        </div>
        <div v-else class="workflow-list">
          <div
            v-for="item in workflowTools"
            :key="String(item.tool_id)"
            class="workflow-list-item tool-list-item"
          >
            <span class="workflow-list-icon">⌘</span>
            <span class="asset-item-main"
              ><strong>{{ item.name || item.tool_id }}</strong
              ><small
                >{{ item.tool_id }} · {{ item.workflow_id }}@v{{
                  item.workflow_version
                }}
                · {{ item.visibility || "PRIVATE" }}</small
              ></span
            >
            <span class="workflow-list-meta"
              ><span
                class="asset-status"
                :class="item.enabled ? 'published' : 'draft'"
                >{{ item.enabled ? "已启用" : "已停用" }}</span
              ><button
                class="btn btn-ghost btn-sm"
                @click="editWorkflowTool(item)"
              >
                编辑</button
              ><button
                class="btn btn-ghost btn-sm"
                @click="toggleWorkflowTool(item)"
              >
                {{ item.enabled ? "停用" : "启用" }}</button
              ><button
                class="btn btn-danger btn-sm"
                @click="deleteWorkflowTool(item)"
              >
                删除
              </button></span
            >
          </div>
        </div>
      </section>

      <section class="orchestration-note panel">
        <strong>资产生命周期</strong
        ><span
          >草稿可编辑；发布后才会出现在 Flow 目录，并作为后续 API / Agent Tool
          的稳定入口。</span
        >
      </section>

      <div
        v-if="detailDrawerOpen && workflow"
        class="drawer-backdrop"
        @click.self="closeDetailDrawer"
      >
        <aside class="workflow-detail-drawer">
          <div class="drawer-head">
            <div>
              <div class="eyebrow">WORKFLOW ASSET</div>
              <h2>{{ workflow.name || workflow.workflow_id }}</h2>
              <small>{{ workflow.workflow_id }}</small>
            </div>
            <button class="btn btn-ghost btn-sm" @click="closeDetailDrawer">
              关闭
            </button>
          </div>
          <div class="drawer-body">
            <div class="drawer-status-row">
              <span
                class="status-pill"
                :class="isPublished ? 'published' : 'draft'"
                >{{
                  isPublished ? "当前草稿等于活动版本" : "有未发布修改"
                }}</span
              ><span
                >草稿 v{{ workflow.version || 1 }} · 活动 v{{
                  activePublishedVersion || "—"
                }}
                · {{ nodes.length }} 个节点</span
              ><button
                class="btn btn-ghost btn-sm"
                :disabled="loading"
                @click="selectWorkflow(selectedWorkflowId)"
              >
                刷新
              </button>
            </div>
            <div class="detail-grid">
              <div>
                <small>触发方式</small
                ><strong>{{ workflow.trigger_type || "manual" }}</strong>
              </div>
              <div>
                <small>业务域</small
                ><strong>{{
                  workflow.domain || currentDomain("platform")
                }}</strong>
              </div>
              <div>
                <small>更新时间</small
                ><strong>{{ workflow.updated_at || "—" }}</strong>
              </div>
              <div>
                <small>调用入口</small
                ><strong>{{
                  hasActivePublishedVersion
                    ? `活动版本 v${activePublishedVersion}`
                    : "发布后可用"
                }}</strong>
              </div>
            </div>
            <div class="drawer-section">
              <div class="drawer-section-title">描述</div>
              <p>{{ workflow.description || "暂无描述" }}</p>
            </div>
            <div class="drawer-section">
              <div class="drawer-section-title">
                不可变版本 <span>{{ workflowVersions.length }}</span>
              </div>
              <div v-if="!workflowVersions.length" class="drawer-empty">
                尚未发布过版本。
              </div>
              <div v-else class="drawer-node-list">
                <div
                  v-for="item in workflowVersions"
                  :key="String(item.version)"
                  class="drawer-node-item"
                >
                  <span>v{{ item.version }}</span>
                  <div>
                    <strong>{{
                      Number(item.version) === activePublishedVersion
                        ? "当前活动版本"
                        : "历史版本"
                    }}</strong
                    ><small>{{
                      item.published_at || item.updated_at || "—"
                    }}</small>
                  </div>
                </div>
              </div>
              <button
                class="btn btn-primary btn-sm"
                :disabled="!hasActivePublishedVersion"
                @click="registerWorkflowTool"
              >
                注册为 Workflow Tool
              </button>
            </div>
            <div class="drawer-section">
              <div class="drawer-section-title">
                触发入口 <span>{{ workflowSchedules.length }}</span>
              </div>
              <p class="endpoint-hint">
                平台调用：POST /platform/frontend/workflows/{{
                  selectedWorkflowId
                }}/run<br />外部系统 / Webhook：POST /api/v1/workflows/{{
                  selectedWorkflowId
                }}/run（X-API-Key，授权目标 workflow:{{ selectedWorkflowId }}）
              </p>
              <div v-if="workflowSchedules.length" class="drawer-node-list">
                <div
                  v-for="item in workflowSchedules"
                  :key="String(item.task_id)"
                  class="drawer-node-item"
                >
                  <span>⏱</span>
                  <div>
                    <strong>{{ item.name }}</strong
                    ><small
                      >v{{ item.workflow_version }} ·
                      {{ item.cron_expression }} · {{ item.timezone }}</small
                    >
                  </div>
                  <button
                    class="btn btn-ghost btn-sm"
                    @click="toggleWorkflowSchedule(item)"
                  >
                    {{ item.enabled ? "暂停" : "启用" }}</button
                  ><button
                    class="btn btn-danger btn-sm"
                    @click="deleteWorkflowSchedule(item)"
                  >
                    删除
                  </button>
                </div>
              </div>
              <button
                class="btn btn-primary btn-sm"
                :disabled="!hasActivePublishedVersion"
                @click="createWorkflowSchedule"
              >
                创建定时触发器
              </button>
            </div>
            <div class="drawer-section">
              <div class="drawer-section-title">
                节点概览 <span>{{ nodes.length }}</span>
              </div>
              <div v-if="!nodes.length" class="drawer-empty">
                还没有节点，打开画布开始编排。
              </div>
              <div v-else class="drawer-node-list">
                <div
                  v-for="(node, index) in nodes"
                  :key="String(node.nodeId)"
                  class="drawer-node-item"
                >
                  <span>{{ index + 1 }}</span>
                  <div>
                    <strong>{{ node.nodeId }}</strong
                    ><small
                      >{{ nodeTypeLabel(String(node.type))
                      }}<template v-if="node.refId">
                        · {{ node.refId }}</template
                      ></small
                    >
                  </div>
                </div>
              </div>
            </div>
            <div class="drawer-section">
              <div class="drawer-section-title">边界节点</div>
              <p>
                输入：{{ boundaryNodeLabel("workflow.input") }} · 输出：{{
                  boundaryNodeLabel("workflow.output")
                }}
              </p>
            </div>
          </div>
          <div class="drawer-actions">
            <button class="btn btn-ghost" @click="deleteWorkflow">删除</button
            ><span></span
            ><button
              v-if="!isPublished"
              class="btn btn-ghost"
              :disabled="publishing || saving"
              @click="publishWorkflow"
            >
              {{ publishing ? "发布中…" : "发布草稿为新版本" }}</button
            ><button
              v-if="hasActivePublishedVersion"
              class="btn btn-ghost"
              @click="unpublishWorkflow"
            >
              取消活动版本</button
            ><button class="btn btn-primary" @click="openCanvas">
              新标签页打开画布
            </button>
          </div>
        </aside>
      </div>
    </template>

    <div v-if="standaloneCanvas && !workflow" class="standalone-canvas-loading">
      {{ loading ? "正在加载 Workflow 画布…" : "Workflow 不存在或已被删除" }}
    </div>
    <div
      v-if="canvasOpen && workflow"
      :class="[
        'canvas-backdrop',
        { 'standalone-canvas-backdrop': standaloneCanvas },
      ]"
    >
      <section class="canvas-modal">
        <header class="canvas-modal-head">
          <div>
            <div class="eyebrow">WORKFLOW CANVAS</div>
            <h2>{{ workflow.name || workflow.workflow_id }}</h2>
            <span
              >{{ workflow.trigger_type || "manual" }} ·
              {{ nodes.length }} 个节点</span
            >
          </div>
          <div class="canvas-modal-actions">
            <span
              class="status-pill"
              :class="isPublished ? 'published' : 'draft'"
              >{{
                isPublished
                  ? `活动 v${activePublishedVersion}`
                  : hasActivePublishedVersion
                    ? `草稿 / 活动 v${activePublishedVersion}`
                    : "未发布"
              }}</span
            ><button
              class="btn btn-ghost"
              :disabled="saving"
              @click="saveWorkflow()"
            >
              {{ saving ? "保存中…" : "保存草稿" }}</button
            ><button
              class="btn btn-ghost"
              :disabled="validating"
              @click="validateWorkflow"
            >
              {{ validating ? "校验中…" : "校验契约" }}</button
            ><button
              v-if="!isPublished"
              class="btn btn-primary"
              :disabled="publishing"
              @click="publishWorkflow"
            >
              {{ publishing ? "发布中…" : "发布草稿为新版本" }}</button
            ><button
              v-if="hasActivePublishedVersion"
              class="btn btn-ghost"
              @click="unpublishWorkflow"
            >
              取消活动版本</button
            ><button
              class="btn btn-ghost"
              :class="{ active: testPanelOpen }"
              @click="testPanelOpen = !testPanelOpen"
            >
              {{ testPanelOpen ? "收起测试" : "测试面板" }}</button
            ><button class="btn btn-ghost" @click="closeCanvas">
              关闭画布
            </button>
          </div>
        </header>
        <div class="canvas-editor-layout">
          <aside class="node-palette panel">
            <div class="panel-title">节点库</div>
            <p class="panel-hint">点击或拖拽节点加入画布</p>
            <label class="palette-search">
              <span>搜索节点</span>
              <input v-model="paletteQuery" type="search" placeholder="搜索 Agent、数据库、并行…" />
            </label>
            <div
              v-for="group in paletteGroups"
              :key="group.label"
              class="palette-group"
            >
              <div class="palette-group-title">{{ group.label }}</div>
              <button
                v-for="nodeType in group.nodes"
                :key="nodeType.value"
                class="palette-node"
                draggable="true"
                @dragstart="startPaletteDrag($event, nodeType.value)"
                @click="addNode(nodeType.value)"
              >
                <span class="palette-icon" :class="`tone-${nodeTone(nodeType.value)}`">{{ nodeType.icon }}</span>
                <span class="palette-copy"><strong>{{ nodeType.label }}</strong><small>{{ nodeType.description }}</small></span>
                <span class="palette-add">＋</span>
              </button>
            </div>
            <div v-if="!paletteGroups.length" class="palette-empty">没有匹配的节点类型</div>
          </aside>
          <main class="workflow-canvas panel">
            <div class="canvas-toolbar">
              <div>
                <strong>{{ workflow.name || "未选择 Workflow" }}</strong
                ><span class="canvas-meta"
                  >{{ workflow.trigger_type || "manual" }} ·
                  {{ nodes.length }} 个节点</span
                >
              </div>
              <div class="canvas-toolbar-actions">
                <span class="canvas-shortcuts">拖动空白平移 · Ctrl+滚轮缩放 · 0 适配</span>
                <div class="canvas-zoom-controls" aria-label="画布视图控制">
                  <button type="button" title="缩小画布（-）" aria-label="缩小画布" @click="zoomCanvas(-1)">−</button>
                  <span>{{ Math.round(canvasZoom * 100) }}%</span>
                  <button type="button" title="放大画布（+）" aria-label="放大画布" @click="zoomCanvas(1)">＋</button>
                  <button type="button" title="显示全部节点（0）" @click="fitCanvasView">适配</button>
                  <button type="button" title="恢复 100% 并回到原点" @click="resetCanvasView">1:1</button>
                </div>
                <span class="canvas-status">{{
                  loading ? "加载中" : canvasPanning ? "移动画布" : "拖拽编排"
                }}</span>
              </div>
            </div>
            <div
              v-if="!nodes.length"
              class="canvas-empty"
              @dragover.prevent
              @drop="dropPaletteNode"
            >
              <div class="empty-icon">＋</div>
              <strong>从左侧节点库拖入节点</strong
              ><span>拖动节点定位；从节点右侧端口连到下一个节点。</span>
            </div>
            <div
              v-else
              ref="graphCanvasRef"
              class="graph-canvas"
              :class="{ panning: canvasPanning }"
              @dragover.prevent
              @drop="dropPaletteNode"
              @pointerdown="startCanvasPan"
              @pointerup="cancelConnection"
              @wheel="handleCanvasWheel"
            >
              <div class="graph-viewport" :style="{
                width: `${graphSize.width * canvasZoom}px`,
                height: `${graphSize.height * canvasZoom}px`,
              }">
                <div
                  class="graph-surface"
                :style="{
                  width: `${graphSize.width}px`,
                  height: `${graphSize.height}px`,
                  transform: `scale(${canvasZoom})`,
                }"
                @pointermove="moveConnection"
              >
                <svg
                  class="graph-edges"
                  :width="graphSize.width"
                  :height="graphSize.height"
                  :viewBox="`0 0 ${graphSize.width} ${graphSize.height}`"
                  aria-hidden="true"
                >
                  <defs>
                    <marker
                      id="workflow-arrow"
                      markerWidth="8"
                      markerHeight="8"
                      refX="7"
                      refY="4"
                      orient="auto"
                    >
                      <path d="M0,0 L8,4 L0,8 Z" fill="#94a3b8" />
                    </marker>
                  </defs>
                  <template v-for="edge in graphEdges" :key="edge.id">
                    <path
                      :d="edgePath(edge)"
                      class="graph-edge"
                      :class="{ dashed: edge.dashed }"
                      marker-end="url(#workflow-arrow)"
                    >
                      <title>{{ edge.label }}</title>
                    </path>
                  </template>
                  <path
                    v-if="connectionState"
                    :d="connectionPath()"
                    class="graph-edge graph-edge-preview"
                    marker-end="url(#workflow-arrow)"
                  /></svg
                ><button
                  v-for="(node, index) in nodes"
                  :key="String(node.nodeId)"
                  class="flow-node graph-node"
                  :class="{ selected: node.nodeId === selectedNodeId }"
                  :style="graphNodeStyle(node)"
                  @pointerdown.stop="startNodeDrag($event, node)"
                  @click.stop="selectedNodeId = String(node.nodeId)"
                >
                  <span
                    v-for="(port, portIndex) in nodeInputPorts(node)"
                    :key="`in-${node.nodeId}-${port.portId}`"
                    class="node-port node-port-in"
                    :style="
                      portStyle(node, portIndex, nodeInputPorts(node).length)
                    "
                    :title="`输入：${port.portId}`"
                    @pointerup.stop="finishConnection($event, node, port)"
                  ></span
                  ><span class="node-accent" :class="`tone-${nodeTone(String(node.type))}`"></span
                  ><span class="flow-index" :class="`tone-${nodeTone(String(node.type))}`">{{ nodeTypeMeta(String(node.type)).icon }}</span
                  ><span class="flow-node-body">
                    <span class="node-card-title"><strong>{{ node.nodeId }}</strong><em :class="nodeIsConfigured(node) ? 'configured' : 'incomplete'">{{ nodeIsConfigured(node) ? '已配置' : '待配置' }}</em></span>
                    <small class="node-card-type">{{ nodeTypeLabel(String(node.type)) }}</small>
                    <small class="node-card-summary">{{ nodeSummary(node) }}</small>
                  </span
                  ><span
                    v-for="(port, portIndex) in nodeOutputPorts(node)"
                    :key="`out-${node.nodeId}-${port.portId}`"
                    class="node-port node-port-out"
                    :style="
                      portStyle(node, portIndex, nodeOutputPorts(node).length)
                    "
                    :title="`输出：${port.portId}`"
                    @pointerdown.stop="startConnection($event, node, port)"
                  ></span>
                </button>
                </div>
              </div>
            </div>
          </main>
          <aside class="node-properties panel">
            <div class="panel-title">
              {{ selectedNode ? "节点属性" : "Workflow 属性" }}
            </div>
            <div v-if="selectedNode" class="properties-body">
              <div class="property-actions">
                <button class="btn btn-ghost btn-sm" @click="moveSelected(-1)">
                  上移</button
                ><button class="btn btn-ghost btn-sm" @click="moveSelected(1)">
                  下移</button
                ><button
                  class="btn btn-danger btn-sm"
                  @click="removeSelectedNode"
                >
                  删除
                </button>
              </div>
              <nav class="node-property-tabs" aria-label="节点属性分类">
                <button
                  type="button"
                  :class="{ active: activeNodePropertyTab === 'config' }"
                  @click="activeNodePropertyTab = 'config'"
                >
                  配置
                </button>
                <button
                  type="button"
                  :class="{ active: activeNodePropertyTab === 'connection' }"
                  @click="activeNodePropertyTab = 'connection'"
                >
                  连接
                  <span v-if="selectedNodeEdges.length" class="property-tab-badge">{{ selectedNodeEdges.length }}</span>
                </button>
                <button
                  type="button"
                  :class="{ active: activeNodePropertyTab === 'runtime' }"
                  @click="activeNodePropertyTab = 'runtime'"
                >
                  运行
                </button>
              </nav>
              <WorkflowNodeInspector
                v-if="activeNodePropertyTab === 'config'"
                mode="config"
                :node="selectedNode"
                :node-types="NODE_TYPES"
                :workflows="workflows.filter((item) => String(item.workflow_id) !== selectedWorkflowId)"
                :agents="catalogAgents"
                :models="catalogModels"
                :skills="catalogSkills"
                :mcp-servers="catalogMcpServers"
                @type-change="normalizeNodeType"
                @schema-change="syncBoundarySchema"
              />
              <div
                v-if="activeNodePropertyTab === 'runtime' && !isBoundaryNode(selectedNode)"
                class="property-section"
              >
                <div class="property-section-head property-section-title">
                  <strong>运行策略</strong><span class="panel-hint">超时、重试与失败处理</span>
                </div>
                <div class="property-collapse-body">
                <label
                  >超时（ms）<input
                    v-model.number="selectedNode.timeoutMs"
                    type="number"
                    min="100" /></label
                ><label
                  >最大重试次数<input
                    v-model.number="selectedNode.maxRetries"
                    type="number"
                    min="0"
                    max="10" /></label
                ><label
                  >失败策略<select v-model="selectedNode.failurePolicy">
                    <option value="FAIL_FAST">失败并终止</option>
                    <option value="SKIP">跳过节点</option>
                    <option value="USE_INPUT">使用原输入</option>
                  </select></label
                >
                </div>
              </div>
              <WorkflowConnectionInspector
                v-if="activeNodePropertyTab === 'connection'"
                :node="selectedNode"
                :nodes="nodes"
                :edges="edges"
                @remove-edge="removeEdge"
              />
              <details
                v-if="activeNodePropertyTab === 'connection'"
                class="property-section property-collapse port-advanced"
              >
                <summary>端口高级设置 <small>ID、Contract、基数与端口增删</small></summary>
                <div class="property-collapse-body">
                <div class="property-section-head">
                  <strong>输入端口</strong
                  ><button
                    class="btn btn-ghost btn-sm"
                    @click="addPort(selectedNode, true)"
                  >
                    添加
                  </button>
                </div>
                <div
                  v-for="(port, index) in nodeInputPorts(selectedNode)"
                  :key="`input-port-${index}`"
                  class="port-row"
                >
                  <input v-model="port.portId" placeholder="端口 ID" />
                  <input
                    v-model="port.contractRef"
                    placeholder="Contract ID（可选）"
                  />
                  <select v-model="port.schema.type" title="端口数据类型">
                    <option value="">任意数据</option>
                    <option value="string">文本</option>
                    <option value="object">对象</option>
                    <option value="array">列表</option>
                    <option value="number">数字</option>
                    <option value="integer">整数</option>
                    <option value="boolean">布尔值</option>
                  </select>
                  <input v-model="port.description" placeholder="端口说明（可选）" />
                  <select v-model="port.cardinality">
                    <option value="one">单值</option>
                    <option value="many">多值</option>
                  </select>
                  <label class="checkbox"
                    ><input type="checkbox" v-model="port.required" />
                    必填</label
                  ><button
                    class="icon-button"
                    @click="removePort(selectedNode, true, index)"
                  >
                    ×
                  </button>
                </div>
                <div class="property-section-head">
                  <strong>输出端口</strong
                  ><button
                    class="btn btn-ghost btn-sm"
                    @click="addPort(selectedNode, false)"
                  >
                    添加
                  </button>
                </div>
                <div
                  v-for="(port, index) in nodeOutputPorts(selectedNode)"
                  :key="`output-port-${index}`"
                  class="port-row"
                >
                  <input v-model="port.portId" placeholder="端口 ID" />
                  <input
                    v-model="port.contractRef"
                    placeholder="Contract ID（可选）"
                  />
                  <select v-model="port.schema.type" title="端口数据类型">
                    <option value="">任意数据</option>
                    <option value="string">文本</option>
                    <option value="object">对象</option>
                    <option value="array">列表</option>
                    <option value="number">数字</option>
                    <option value="integer">整数</option>
                    <option value="boolean">布尔值</option>
                  </select>
                  <input v-model="port.description" placeholder="端口说明（可选）" />
                  <select v-model="port.cardinality">
                    <option value="one">单值</option>
                    <option value="many">多值</option>
                  </select>
                  <button
                    class="icon-button"
                    @click="removePort(selectedNode, false, index)"
                  >
                    ×
                  </button>
                </div>
                </div>
              </details>
              <div
                v-if="activeNodePropertyTab === 'runtime' && isBoundaryNode(selectedNode)"
                class="property-tab-empty"
              >
                Workflow 输入和输出节点不执行独立任务，因此没有超时与重试策略。
              </div>
              <WorkflowNodeInspector
                v-if="activeNodePropertyTab === 'runtime'"
                mode="advanced"
                :node="selectedNode"
                :node-types="NODE_TYPES"
                :workflows="workflows.filter((item) => String(item.workflow_id) !== selectedWorkflowId)"
                :agents="catalogAgents"
                :models="catalogModels"
                :skills="catalogSkills"
                :mcp-servers="catalogMcpServers"
                @type-change="normalizeNodeType"
                @schema-change="syncBoundarySchema"
              />
            </div>
            <div v-else class="properties-body">
              <label>Workflow 名称<input v-model="workflow.name" /></label
              ><label
                >描述<textarea
                  v-model="workflow.description"
                  rows="3"
                  placeholder="这个流程解决什么业务问题？"
                ></textarea></label
              ><label
                >触发方式<select v-model="workflow.trigger_type">
                  <option value="manual">手动</option>
                  <option value="api">API</option>
                  <option value="chat">对话</option>
                  <option value="webhook">Webhook</option>
                  <option value="schedule">定时</option>
                </select></label
              >
            </div>
          </aside>
        </div>
        <section v-if="testPanelOpen" class="workflow-test panel canvas-test-drawer">
          <div class="test-head">
            <div>
              <div class="panel-title">独立 Workflow 运行测试</div>
              <p class="panel-hint">
                调用活动发布版本 v{{
                  activePublishedVersion || "—"
                }}，结果会写入运行观测。
              </p>
            </div>
            <div class="test-head-actions">
              <button class="btn btn-ghost btn-sm" @click="testPanelOpen = false">关闭</button>
              <button
                class="btn btn-primary"
                :disabled="testing || !hasActivePublishedVersion"
                @click="runWorkflow"
              >
                {{ testing ? "运行中…" : "运行 Workflow" }}
              </button>
            </div>
          </div>
          <div class="test-grid">
            <textarea
              v-model="testInput"
              placeholder="输入测试请求，例如：查询订单 10086"
            ></textarea>
            <div class="test-result">
              <div v-if="testOutput" class="result-answer">
                {{ testOutput }}
              </div>
              <div v-else class="properties-empty">运行结果会显示在这里。</div>
              <details v-if="testEvents.length">
                <summary>查看运行事件（{{ testEvents.length }}）</summary>
                <pre>{{ JSON.stringify(testEvents, null, 2) }}</pre>
              </details>
            </div>
          </div>
        </section>
      </section>
    </div>
  </section>
</template>

<style scoped>
.tool-list-item {
  cursor: default;
}
.endpoint-hint {
  padding: 9px 10px;
  border: 1px solid #dbeafe;
  border-radius: 8px;
  background: #eff6ff;
  color: #1e3a8a;
  font:
    10px/1.65 ui-monospace,
    Menlo,
    Consolas,
    monospace;
  overflow-wrap: anywhere;
}
.orchestration-page {
  padding: 24px 28px 40px;
  min-height: 100%;
  background: #f8fafc;
  color: #0f172a;
}
.orchestration-header {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  align-items: flex-end;
  margin-bottom: 20px;
}
.eyebrow {
  font-size: 10px;
  letter-spacing: 0.14em;
  font-weight: 800;
  color: #2563eb;
  margin-bottom: 7px;
}
h1 {
  font-size: 26px;
  line-height: 1.15;
  margin: 0 0 7px;
  letter-spacing: -0.03em;
}
.orchestration-header p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
}
.orchestration-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.status-pill,
.asset-status {
  font-size: 11px;
  border-radius: 999px;
  padding: 4px 8px;
}
.status-pill.published,
.asset-status.published {
  background: #dcfce7;
  color: #15803d;
}
.status-pill.draft,
.asset-status.draft {
  background: #fef3c7;
  color: #a16207;
}
.panel {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  box-shadow: 0 4px 18px rgba(15, 23, 42, 0.04);
}
.orchestration-layout {
  display: grid;
  grid-template-columns: 250px minmax(380px, 1fr) 310px;
  gap: 14px;
  align-items: stretch;
}
.asset-sidebar {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}
.asset-panel,
.node-palette,
.node-properties {
  padding: 17px;
}
.panel-title {
  font-size: 14px;
  font-weight: 800;
}
.panel-hint {
  font-size: 11px;
  color: #94a3b8;
  margin: 5px 0 12px;
}
.create-row {
  display: flex;
  gap: 6px;
  margin-bottom: 12px;
}
.create-row input,
.properties-body input,
.properties-body select,
.properties-body textarea,
.transition-row input,
.transition-row select,
.orchestration-actions select {
  width: 100%;
  border: 1px solid #cbd5e1;
  border-radius: 7px;
  padding: 8px;
  font: inherit;
  font-size: 12px;
  color: #0f172a;
  background: #fff;
}
.asset-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  text-align: left;
  border: 1px solid transparent;
  background: #f8fafc;
  border-radius: 8px;
  padding: 9px;
  margin-bottom: 6px;
  cursor: pointer;
}
.asset-item:hover,
.asset-item.selected {
  border-color: #93c5fd;
  background: #eff6ff;
}
.asset-item-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  flex: 1;
}
.asset-item-main strong {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.asset-item-main small {
  font-size: 10px;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.palette-search {
  position: sticky;
  top: -17px;
  z-index: 5;
  display: block;
  margin: 10px -4px 0;
  padding: 5px 4px 8px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(8px);
}
.palette-search > span {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
.palette-search input {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #dbe5f2;
  border-radius: 9px;
  padding: 8px 10px;
  background: #f8fafc;
  color: #334155;
  font: inherit;
  font-size: 10px;
  outline: none;
}
.palette-search input:focus {
  border-color: #3b82f6;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}
.palette-empty {
  margin-top: 12px;
  padding: 18px 8px;
  border: 1px dashed #cbd5e1;
  border-radius: 9px;
  color: #94a3b8;
  font-size: 10px;
  text-align: center;
}
.palette-group {
  margin-top: 14px;
}
.palette-group-title {
  font-size: 10px;
  color: #94a3b8;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: 7px;
}
.palette-node {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
  border-radius: 10px;
  padding: 9px;
  margin-bottom: 7px;
  color: #334155;
  text-align: left;
  cursor: pointer;
}
.palette-node:hover {
  border-color: #93c5fd;
  background: #fff;
  box-shadow: 0 5px 14px rgba(15, 23, 42, 0.08);
  transform: translateY(-1px);
}
.palette-icon {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  background: #e0ecff;
  color: #1d4ed8;
  font-size: 9px;
  font-weight: 900;
  letter-spacing: .03em;
}
.palette-copy {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.palette-copy strong { color: #1e293b; font-size: 11px; }
.palette-copy small { color: #94a3b8; font-size: 9px; line-height: 1.35; white-space: normal; }
.palette-add { color: #94a3b8; font-size: 15px; }
.tone-flow { background: #e0ecff !important; color: #1d4ed8 !important; }
.tone-business { background: #dcfce7 !important; color: #15803d !important; }
.tone-ai { background: #f3e8ff !important; color: #7e22ce !important; }
.tone-control { background: #ffedd5 !important; color: #c2410c !important; }
.workflow-canvas {
  min-height: 620px;
  background: linear-gradient(#fff, #f8fbff);
  overflow: hidden;
}
.canvas-toolbar {
  padding: 15px 18px;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.canvas-meta {
  margin-left: 10px;
  color: #94a3b8;
  font-size: 11px;
}
.canvas-status {
  font-size: 11px;
  color: #2563eb;
  background: #eff6ff;
  padding: 4px 8px;
  border-radius: 999px;
  white-space: nowrap;
}
.canvas-toolbar-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  min-width: 0;
}
.canvas-shortcuts {
  color: #94a3b8;
  font-size: 9px;
  white-space: nowrap;
}
.canvas-zoom-controls {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 3px;
  border: 1px solid #dbe5f2;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 2px 7px rgba(15, 23, 42, 0.05);
}
.canvas-zoom-controls button {
  min-width: 27px;
  height: 27px;
  padding: 0 7px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #475569;
  font-size: 10px;
  font-weight: 750;
  cursor: pointer;
}
.canvas-zoom-controls button:hover {
  color: #1d4ed8;
  background: #eff6ff;
}
.canvas-zoom-controls > span {
  min-width: 40px;
  color: #334155;
  font-size: 10px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}
@media (max-width: 1300px) {
  .canvas-shortcuts {
    display: none;
  }
}
@media (max-width: 1100px) {
  .canvas-toolbar {
    padding: 12px;
  }
  .canvas-status {
    display: none;
  }
}
.canvas-empty {
  min-height: 530px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 8px;
  color: #64748b;
}
.empty-icon {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: #eff6ff;
  color: #2563eb;
  display: grid;
  place-items: center;
  font-size: 25px;
}
.canvas-empty strong {
  color: #334155;
}
.canvas-empty span {
  font-size: 12px;
}
.node-flow {
  padding: 28px 15%;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}
.flow-node {
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: left;
  background: #fff;
  border: 1px solid #cbd5e1;
  border-radius: 14px;
  padding: 13px 14px 13px 16px;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.08);
  transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease;
}
.flow-node:hover,
.flow-node.selected {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px #dbeafe, 0 10px 26px rgba(37, 99, 235, .14);
  transform: translateY(-1px);
}
.flow-index {
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  border-radius: 11px;
  background: #eff6ff;
  color: #2563eb;
  display: grid;
  place-items: center;
  font-size: 9px;
  font-weight: 900;
  letter-spacing: .03em;
}
.flow-node-body {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}
.flow-node-body strong {
  min-width: 0;
  overflow: hidden;
  color: #0f172a;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.flow-node-body small {
  font-size: 10px;
  color: #64748b;
}
.node-card-title { display: flex; align-items: center; gap: 6px; min-width: 0; }
.node-card-title strong { flex: 1; }
.node-card-title em { flex: 0 0 auto; border-radius: 999px; padding: 2px 5px; font-size: 8px; font-style: normal; font-weight: 800; }
.node-card-title em.configured { background: #ecfdf5; color: #047857; }
.node-card-title em.incomplete { background: #fff7ed; color: #c2410c; }
.node-card-type { color: #64748b !important; font-weight: 650; }
.node-card-summary { max-width: 132px; overflow: hidden; color: #94a3b8 !important; text-overflow: ellipsis; white-space: nowrap; }
.node-accent { position: absolute; left: 0; top: 14px; bottom: 14px; width: 3px; border-radius: 0 4px 4px 0; }
.flow-chevron {
  font-size: 22px;
  color: #94a3b8;
}
.flow-edge {
  height: 32px;
  display: grid;
  place-items: center;
  color: #94a3b8;
  font-size: 18px;
}
.properties-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 15px;
}
.properties-body label {
  font-size: 11px;
  color: #64748b;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.property-actions {
  display: flex;
  gap: 5px;
  margin-bottom: 4px;
}
.node-property-tabs {
  position: sticky;
  top: -1px;
  z-index: 4;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 3px;
  padding: 4px;
  border: 1px solid #dbe5f2;
  border-radius: 10px;
  background: rgba(248, 250, 252, 0.96);
  backdrop-filter: blur(8px);
}
.node-property-tabs button {
  min-width: 0;
  min-height: 32px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #64748b;
  font-size: 11px;
  font-weight: 750;
  cursor: pointer;
}
.node-property-tabs button:hover {
  color: #1d4ed8;
  background: #eff6ff;
}
.node-property-tabs button.active {
  color: #fff;
  background: #2563eb;
  box-shadow: 0 3px 8px rgba(37, 99, 235, 0.22);
}
.property-tab-badge {
  display: inline-grid;
  place-items: center;
  min-width: 16px;
  height: 16px;
  margin-left: 3px;
  padding: 0 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.22);
  font-size: 9px;
}
.property-tab-empty {
  padding: 14px;
  border: 1px dashed #cbd5e1;
  border-radius: 10px;
  background: #f8fafc;
  color: #64748b;
  font-size: 10px;
  line-height: 1.6;
}
.property-section {
  border-top: 1px solid #e2e8f0;
  padding-top: 12px;
}
.property-section-title {
  align-items: flex-start;
  flex-direction: column;
  gap: 2px;
}
.connection-section {
  border-top: 0;
  padding-top: 0;
}
.property-section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  margin-bottom: 8px;
}
.transition-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 5px;
  margin-bottom: 6px;
}
.transition-row .checkbox {
  grid-column: 1 / 2;
  display: block;
  flex-direction: row;
  align-items: center;
}
.checkbox input {
  width: auto;
}
.icon-button {
  border: 0;
  background: transparent;
  color: #ef4444;
  cursor: pointer;
  grid-column: 2;
  grid-row: 2;
}
.properties-empty {
  color: #94a3b8;
  font-size: 12px;
  padding: 28px 8px;
  text-align: center;
}
.orchestration-note {
  margin-top: 14px;
  padding: 14px 17px;
  display: flex;
  gap: 10px;
  align-items: center;
  font-size: 12px;
  color: #64748b;
}
.orchestration-note strong {
  color: #334155;
}
@media (max-width: 1100px) {
  .orchestration-layout {
    grid-template-columns: 220px 1fr;
  }
  .orchestration-layout > .node-properties {
    grid-column: 1 / -1;
  }
  .orchestration-note {
    margin-top: 14px;
  }
}
@media (max-width: 700px) {
  .orchestration-page {
    padding: 16px;
  }
  .orchestration-header {
    display: block;
  }
  .orchestration-actions {
    margin-top: 14px;
  }
  .orchestration-layout {
    grid-template-columns: 1fr;
  }
  .workflow-canvas {
    min-height: 420px;
  }
  .canvas-empty {
    min-height: 360px;
  }
  .node-flow {
    padding: 24px 8%;
  }
  .orchestration-note {
    align-items: flex-start;
    flex-direction: column;
  }
}
.workflow-test {
  margin-top: 14px;
  padding: 17px;
}
.test-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.test-head-actions {
  display: flex;
  align-items: center;
  gap: 7px;
}
.test-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  margin-top: 12px;
}
.test-grid textarea {
  width: 100%;
  min-height: 100px;
  resize: vertical;
  border: 1px solid #cbd5e1;
  border-radius: 7px;
  padding: 8px;
  font: inherit;
  font-size: 12px;
}
.test-result {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px;
  min-height: 100px;
  font-size: 12px;
}
.result-answer {
  white-space: pre-wrap;
  line-height: 1.6;
}
.test-result details {
  margin-top: 12px;
  color: #64748b;
}
.test-result pre {
  max-height: 220px;
  overflow: auto;
  background: #f8fafc;
  padding: 8px;
  font-size: 10px;
}
@media (max-width: 700px) {
  .test-grid {
    grid-template-columns: 1fr;
  }
}
.asset-library {
  padding: 20px;
}
.library-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  border-bottom: 1px solid #e2e8f0;
  padding-bottom: 16px;
}
.library-count {
  font-size: 12px;
  color: #64748b;
  background: #f8fafc;
  border-radius: 999px;
  padding: 6px 10px;
}
.workflow-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-top: 16px;
}
.workflow-list-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 13px;
  text-align: left;
  border: 1px solid #e2e8f0;
  background: #fff;
  border-radius: 12px;
  padding: 14px;
  cursor: pointer;
  transition: 0.15s;
}
.workflow-list-item:hover,
.workflow-list-item.selected {
  border-color: #93c5fd;
  background: #f8fbff;
  box-shadow: 0 0 0 3px #eff6ff;
}
.workflow-list-icon {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: #eff6ff;
  color: #2563eb;
  font-size: 20px;
  font-weight: 700;
}
.workflow-list-meta {
  display: flex;
  align-items: center;
  gap: 14px;
  color: #64748b;
  font-size: 11px;
}
.workflow-list-arrow {
  white-space: nowrap;
}
.library-empty {
  min-height: 300px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #64748b;
}
.library-empty strong {
  color: #334155;
}
.library-empty span {
  font-size: 12px;
}
.drawer-backdrop,
.canvas-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  justify-content: flex-end;
  z-index: 1200;
  padding: 0;
}
.workflow-detail-drawer {
  width: min(480px, 94vw);
  height: 100%;
  background: #fff;
  box-shadow: -16px 0 48px rgba(15, 23, 42, 0.2);
  display: flex;
  flex-direction: column;
}
.drawer-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 22px 22px 18px;
  border-bottom: 1px solid #e2e8f0;
}
.drawer-head h2,
.canvas-modal-head h2 {
  font-size: 19px;
  line-height: 1.25;
  margin: 2px 0 5px;
  color: #0f172a;
}
.drawer-head small {
  font-size: 11px;
  color: #94a3b8;
}
.drawer-body {
  padding: 18px 22px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.drawer-status-row {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #64748b;
  font-size: 12px;
}
.drawer-status-row button {
  margin-left: auto;
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1px;
  background: #e2e8f0;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  overflow: hidden;
}
.detail-grid > div {
  display: flex;
  flex-direction: column;
  gap: 5px;
  background: #f8fafc;
  padding: 12px;
}
.detail-grid small,
.drawer-section-title,
.drawer-schema small {
  font-size: 11px;
  color: #94a3b8;
}
.detail-grid strong {
  font-size: 12px;
  color: #334155;
  word-break: break-word;
}
.drawer-section {
  border-top: 1px solid #e2e8f0;
  padding-top: 15px;
}
.drawer-section-title {
  font-weight: 700;
  color: #334155;
  margin-bottom: 9px;
}
.drawer-section-title span {
  color: #2563eb;
  margin-left: 4px;
}
.drawer-section p {
  font-size: 12px;
  color: #64748b;
  line-height: 1.65;
  margin: 0;
  white-space: pre-wrap;
}
.drawer-empty {
  font-size: 12px;
  color: #94a3b8;
  padding: 15px 0;
}
.drawer-node-list {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.drawer-node-item {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 9px;
}
.drawer-node-item > span {
  width: 22px;
  height: 22px;
  border-radius: 7px;
  background: #eff6ff;
  color: #2563eb;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
}
.drawer-node-item div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.drawer-node-item strong {
  font-size: 12px;
  color: #334155;
}
.drawer-node-item small {
  font-size: 11px;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.drawer-schema {
  border-top: 1px solid #e2e8f0;
  padding-top: 14px;
  color: #334155;
  font-size: 12px;
}
.drawer-schema > div {
  margin-top: 12px;
}
.drawer-schema pre {
  max-height: 150px;
  overflow: auto;
  background: #f8fafc;
  border-radius: 7px;
  padding: 9px;
  font-size: 10px;
  color: #475569;
}
.drawer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 22px;
  border-top: 1px solid #e2e8f0;
}
.drawer-actions span {
  flex: 1;
}
.canvas-backdrop {
  z-index: 1300;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.canvas-modal {
  position: relative;
  width: min(1500px, 100%);
  max-height: calc(100vh - 48px);
  background: #f8fafc;
  border-radius: 16px;
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.28);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.canvas-modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  background: #fff;
  padding: 16px 20px;
  border-bottom: 1px solid #e2e8f0;
}
.canvas-modal-head > div:first-child {
  min-width: 0;
}
.canvas-modal-head span {
  font-size: 11px;
  color: #64748b;
}
.canvas-modal-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 7px;
  flex-wrap: wrap;
}
.canvas-editor-layout {
  display: grid;
  grid-template-columns: 220px minmax(380px, 1fr) 340px;
  gap: 12px;
  padding: 12px;
  flex: 1;
  min-height: 430px;
  overflow: hidden;
}
.canvas-editor-layout > .node-palette,
.canvas-editor-layout > .node-properties {
  overflow: auto;
}
.canvas-editor-layout > .workflow-canvas {
  min-height: 0;
  height: 100%;
  overflow: auto;
}
.canvas-modal .workflow-test {
  margin: 0 12px 12px;
}
.canvas-modal .canvas-test-drawer {
  position: absolute;
  right: 12px;
  bottom: 12px;
  left: 12px;
  z-index: 30;
  max-height: min(430px, 52vh);
  margin: 0;
  overflow: auto;
  border: 1px solid #cbd5e1;
  box-shadow: 0 -12px 36px rgba(15, 23, 42, 0.18);
}
.canvas-modal .canvas-empty {
  min-height: 360px;
}
.canvas-modal .node-flow {
  padding: 24px 12%;
}
@media (max-width: 1000px) {
  .canvas-editor-layout {
    grid-template-columns: 190px minmax(300px, 1fr);
  }
  .canvas-editor-layout > .node-properties {
    grid-column: 1 / -1;
    max-height: 280px;
  }
  .canvas-modal {
    max-height: calc(100vh - 24px);
  }
  .canvas-modal-head {
    align-items: flex-start;
    flex-direction: column;
  }
  .canvas-modal-actions {
    justify-content: flex-start;
  }
}
@media (max-width: 700px) {
  .orchestration-page {
    padding: 16px;
  }
  .workflow-list-meta {
    gap: 6px;
    flex-direction: column;
    align-items: flex-end;
  }
  .canvas-backdrop {
    padding: 8px;
  }
  .canvas-editor-layout {
    grid-template-columns: 1fr;
    overflow: auto;
  }
  .canvas-editor-layout > .node-palette {
    max-height: 180px;
  }
  .canvas-editor-layout > .node-properties {
    grid-column: auto;
    max-height: none;
  }
  .canvas-modal .workflow-test {
    margin: 0 8px 8px;
  }
  .canvas-modal .canvas-test-drawer {
    right: 8px;
    bottom: 8px;
    left: 8px;
    margin: 0;
  }
  .drawer-actions {
    flex-wrap: wrap;
  }
  .drawer-actions span {
    display: none;
  }
}
.standalone-orchestration-page {
  padding: 0;
  min-height: 100vh;
}
.standalone-canvas-loading {
  min-height: 100vh;
  display: grid;
  place-items: center;
  color: #64748b;
  background: #f8fafc;
}
.standalone-canvas-backdrop {
  position: static;
  min-height: 100vh;
  background: #f8fafc;
  padding: 0;
}
.standalone-canvas-backdrop .canvas-modal {
  width: 100%;
  height: 100vh;
  min-height: 0;
  max-height: 100vh;
  border-radius: 0;
  box-shadow: none;
}
.canvas-editor-layout > .workflow-canvas {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.graph-canvas {
  position: relative;
  flex: 1;
  min-height: 390px;
  overflow: auto;
  background: #f8fbff;
  cursor: grab;
}
.graph-canvas:active {
  cursor: grabbing;
}
.graph-canvas.panning {
  cursor: grabbing;
}
.graph-viewport {
  position: relative;
  min-width: 100%;
  min-height: 100%;
}
.graph-surface {
  position: relative;
  transform-origin: 0 0;
  background-color: #f8fbff;
  background-image:
    linear-gradient(to right, rgba(148, 163, 184, 0.14) 1px, transparent 1px),
    linear-gradient(to bottom, rgba(148, 163, 184, 0.14) 1px, transparent 1px);
  background-size: 24px 24px;
}
.graph-edges {
  position: absolute;
  inset: 0;
  overflow: visible;
  pointer-events: none;
  z-index: 1;
}
.graph-edge {
  fill: none;
  stroke: #94a3b8;
  stroke-width: 2;
}
.graph-edge.dashed {
  stroke-dasharray: 7 5;
}
.graph-edge-preview {
  stroke: #2563eb;
  stroke-dasharray: 6 4;
}
.graph-node {
  position: absolute;
  width: 220px;
  height: 128px;
  min-height: 128px;
  z-index: 2;
  box-sizing: border-box;
  user-select: none;
}
.graph-node.selected {
  border-color: #2563eb;
  box-shadow:
    0 0 0 3px #dbeafe,
    0 5px 16px rgba(37, 99, 235, 0.12);
}
.property-collapse { padding: 0 !important; overflow: hidden; }
.property-collapse > summary { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px 11px; color: #475569; font-size: 11px; font-weight: 800; cursor: pointer; list-style: none; }
.property-collapse > summary::-webkit-details-marker { display: none; }
.property-collapse > summary::after { content: '⌄'; color: #94a3b8; font-size: 13px; }
.property-collapse[open] > summary::after { transform: rotate(180deg); }
.property-collapse > summary small { color: #94a3b8; font-size: 9px; font-weight: 500; }
.property-collapse-body { display: flex; flex-direction: column; gap: 8px; padding: 0 10px 10px; }
.node-port {
  position: absolute;
  top: 50%;
  width: 12px;
  height: 12px;
  border: 2px solid #fff;
  border-radius: 50%;
  background: #94a3b8;
  box-shadow: 0 0 0 2px #cbd5e1;
  transform: translateY(-50%);
  z-index: 3;
  cursor: crosshair;
}
.node-port:hover {
  background: #2563eb;
  box-shadow: 0 0 0 3px #bfdbfe;
}
.node-port-in {
  left: -7px;
}
.node-port-out {
  right: -7px;
  background: #2563eb;
  box-shadow: 0 0 0 2px #93c5fd;
}
.canvas-empty[draggable] {
  cursor: copy;
}
.port-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 28px;
  gap: 5px;
  align-items: center;
  padding: 8px;
  border: 1px solid #e2e8f0;
  border-radius: 9px;
  background: #f8fafc;
  margin-bottom: 7px;
}
.port-row > :not(.icon-button) { min-width: 0; }
.port-row > .icon-button { grid-column: 3; grid-row: 1; }
.port-row .checkbox {
  display: flex;
  flex-direction: row;
  align-items: center;
  white-space: nowrap;
}
.port-row .checkbox input {
  width: auto;
}
.graph-canvas .graph-node {
  transform: none;
}
</style>
