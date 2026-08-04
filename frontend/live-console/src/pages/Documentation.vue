<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import MarkdownIt from 'markdown-it'

type DocEntry = {
  path: string
  title: string
  description: string
  section: string
}

const docModules = import.meta.glob('../../../../docs/**/*.md', {
  eager: true,
  query: '?raw',
  import: 'default',
}) as Record<string, string>

const docContent = Object.fromEntries(
  Object.entries(docModules).map(([path, content]) => [
    path.replace(/^.*\/docs\//, '').replaceAll('\\', '/'),
    content,
  ]),
) as Record<string, string>

const docs: DocEntry[] = [
  { path: 'index.md', title: '文档首页', description: '平台定位与推荐使用路径', section: '开始使用' },
  { path: 'getting-started.md', title: '快速开始', description: '环境、启动方式与访问地址', section: '开始使用' },
  { path: 'concepts.md', title: '核心概念', description: '平台资源及其关系', section: '开始使用' },
  { path: 'guide/platform-overview.md', title: '平台概览', description: '首页、状态与侧栏入口', section: '界面与功能' },
  { path: 'guide/model-access.md', title: '模型接入', description: '供应商、模型、插槽与审计', section: '界面与功能' },
  { path: 'guide/tools-mcp-skills.md', title: 'Tools、MCP 与 Skills', description: 'Agent 能力的创建与接入', section: '界面与功能' },
  { path: 'guide/agent-management.md', title: 'Agent 管理', description: '八步配置与快速测试', section: '界面与功能' },
  { path: 'guide/external-api.md', title: '对外 Agent API', description: 'API Key、同步调用与流式调用', section: '界面与功能' },
  { path: 'guide/conversation.md', title: '交互问答与工作台', description: '对话、上下文与运行调试', section: '界面与功能' },
  { path: 'guide/memory.md', title: '记忆管理', description: '长期记忆、审核与维护', section: '界面与功能' },
  { path: 'guide/pending-features.md', title: '待开放功能', description: '已实现但未挂菜单的页面', section: '界面与功能' },
  { path: 'operations/configuration.md', title: '配置与数据', description: '工作区、持久化与备份', section: '运行维护' },
  { path: 'operations/troubleshooting.md', title: '故障排查', description: '常见问题的定位方法', section: '运行维护' },
]

const renderer = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
})

renderer.renderer.rules.link_open = (tokens, idx, options, _env, self) => {
  const href = tokens[idx].attrGet('href') || ''
  if (/^https?:\/\//i.test(href)) {
    tokens[idx].attrSet('target', '_blank')
    tokens[idx].attrSet('rel', 'noreferrer')
  }
  return self.renderToken(tokens, idx, options)
}

const currentPath = ref('index.md')
const keyword = ref('')
const sections = ['开始使用', '界面与功能', '运行维护']
const currentEntry = computed(() => docs.find((doc) => doc.path === currentPath.value) || docs[0])
const rendered = computed(() => {
  const source = docContent[currentPath.value] || '# 文档暂不可用'
  const normalized = source.replace(
    /```\{(note|warning)\}\r?\n([\s\S]*?)```/g,
    (_match, kind: string, body: string) => {
      const label = kind === 'warning' ? '注意' : '提示'
      return `> **${label}**\n>\n${body.trim().split(/\r?\n/).map((line) => `> ${line}`).join('\n')}`
    },
  )
  return renderer.render(normalized)
})
const filteredDocs = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  if (!query) return docs
  return docs.filter((doc) =>
    `${doc.title} ${doc.description} ${docContent[doc.path] || ''}`.toLowerCase().includes(query),
  )
})

function docsInSection(section: string) {
  return filteredDocs.value.filter((doc) => doc.section === section)
}

function openDoc(path: string) {
  if (!docContent[path]) return
  currentPath.value = path
  nextTick(() => document.querySelector('.docs-article-scroll')?.scrollTo({ top: 0 }))
}

function resolveInternalLink(href: string) {
  const cleanHref = href.split('#')[0].split('?')[0]
  if (!cleanHref || !cleanHref.endsWith('.md')) return ''
  const base = new URL(currentPath.value, 'https://platform-docs.local/')
  const resolved = new URL(cleanHref, base)
  return decodeURIComponent(resolved.pathname.replace(/^\/+/, ''))
}

function handleArticleClick(event: MouseEvent) {
  const anchor = (event.target as HTMLElement).closest('a')
  if (!anchor) return
  const path = resolveInternalLink(anchor.getAttribute('href') || '')
  if (!path || !docContent[path]) return
  event.preventDefault()
  openDoc(path)
}
</script>

<template>
  <div class="docs-workspace">
    <aside class="docs-nav">
      <div class="docs-nav-head">
        <div class="docs-nav-eyebrow">HELP CENTER</div>
        <h2>平台使用文档</h2>
        <p>内容与仓库 docs 保持同步</p>
      </div>
      <div class="docs-search">
        <input v-model="keyword" placeholder="搜索文档…" />
      </div>
      <div class="docs-nav-scroll">
        <template v-for="section in sections" :key="section">
          <div v-if="docsInSection(section).length" class="docs-section">
            <div class="docs-section-title">{{ section }}</div>
            <button
              v-for="doc in docsInSection(section)"
              :key="doc.path"
              class="docs-link"
              :class="{ active: currentPath === doc.path }"
              @click="openDoc(doc.path)"
            >
              <span>{{ doc.title }}</span>
              <small>{{ doc.description }}</small>
            </button>
          </div>
        </template>
        <div v-if="!filteredDocs.length" class="docs-no-result">没有找到相关文档</div>
      </div>
    </aside>

    <section class="docs-reader">
      <div class="docs-reader-head">
        <div>
          <div class="docs-breadcrumb">使用文档 / {{ currentEntry.section }}</div>
          <strong>{{ currentEntry.title }}</strong>
        </div>
        <span class="docs-source-badge">源文件：docs/{{ currentPath }}</span>
      </div>
      <div class="docs-article-scroll">
        <article class="docs-article" @click="handleArticleClick" v-html="rendered"></article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.docs-workspace{flex:1;min-height:0;display:grid;grid-template-columns:290px minmax(0,1fr);background:#eef2f7;overflow:hidden}
.docs-nav{min-height:0;background:#fff;border-right:1px solid var(--border);display:flex;flex-direction:column}
.docs-nav-head{padding:22px 20px 16px;border-bottom:1px solid var(--border)}
.docs-nav-eyebrow{font-size:10px;letter-spacing:.12em;font-weight:800;color:#2563eb;margin-bottom:7px}
.docs-nav-head h2{font-size:17px;margin:0;color:#0f172a}
.docs-nav-head p{font-size:11px;color:var(--muted);margin-top:5px}
.docs-search{padding:12px;border-bottom:1px solid var(--border)}
.docs-search input{width:100%;background:#f8fafc}
.docs-nav-scroll{flex:1;min-height:0;overflow:auto;padding:10px}
.docs-section{margin-bottom:14px}
.docs-section-title{padding:7px 8px 5px;font-size:10px;font-weight:800;letter-spacing:.08em;color:#94a3b8}
.docs-link{width:100%;border:0;background:transparent;border-radius:9px;padding:9px 10px;text-align:left;display:grid;gap:3px;color:#334155}
.docs-link:hover{background:#f1f5f9}
.docs-link.active{background:#eff6ff;color:#1d4ed8;box-shadow:inset 3px 0 #3b82f6}
.docs-link span{font-size:12px;font-weight:700}
.docs-link small{font-size:10px;color:#94a3b8;line-height:1.4}
.docs-link.active small{color:#60a5fa}
.docs-no-result{padding:24px 10px;text-align:center;font-size:12px;color:var(--muted)}
.docs-reader{min-width:0;min-height:0;margin:16px;background:#fff;border:1px solid var(--border);border-radius:14px;box-shadow:var(--shadow-sm);display:flex;flex-direction:column;overflow:hidden}
.docs-reader-head{min-height:64px;padding:12px 20px;border-bottom:1px solid var(--border);display:flex;align-items:center;justify-content:space-between;gap:16px;background:#fbfcfe}
.docs-reader-head strong{font-size:14px;color:#0f172a}
.docs-breadcrumb{font-size:10px;color:#94a3b8;margin-bottom:3px}
.docs-source-badge{max-width:45%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:10px;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:#64748b;background:#f1f5f9;border:1px solid #e2e8f0;border-radius:999px;padding:4px 9px}
.docs-article-scroll{flex:1;min-height:0;overflow:auto}
.docs-article{max-width:920px;margin:0 auto;padding:38px 48px 72px;color:#334155;font-size:14px;line-height:1.8}
.docs-article :deep(h1){font-size:30px;line-height:1.25;color:#0f172a;margin:0 0 24px;letter-spacing:-.025em}
.docs-article :deep(h2){font-size:21px;color:#0f172a;margin:36px 0 13px;padding-top:4px;border-bottom:1px solid #e2e8f0;padding-bottom:8px}
.docs-article :deep(h3){font-size:16px;color:#172554;margin:27px 0 9px}
.docs-article :deep(p){margin:10px 0}
.docs-article :deep(ul),.docs-article :deep(ol){padding-left:24px;margin:10px 0}
.docs-article :deep(li){margin:4px 0}
.docs-article :deep(a){color:#2563eb;text-decoration:none}
.docs-article :deep(a:hover){text-decoration:underline}
.docs-article :deep(table){display:table;width:100%;margin:18px 0;border:1px solid #e2e8f0;border-radius:10px;overflow:hidden;font-size:12px}
.docs-article :deep(th){background:#f8fafc;color:#475569}
.docs-article :deep(th),.docs-article :deep(td){padding:10px 12px;border-right:1px solid #e2e8f0}
.docs-article :deep(th:last-child),.docs-article :deep(td:last-child){border-right:0}
.docs-article :deep(code){font-family:ui-monospace,SFMono-Regular,Menlo,monospace;background:#f1f5f9;color:#be123c;border-radius:5px;padding:2px 5px;font-size:.9em}
.docs-article :deep(pre){background:#0f172a;color:#e2e8f0;border-radius:10px;padding:16px 18px;overflow:auto;margin:16px 0}
.docs-article :deep(pre code){background:transparent;color:inherit;padding:0}
.docs-article :deep(blockquote){margin:16px 0;padding:10px 14px;border-left:4px solid #60a5fa;background:#eff6ff;color:#1e3a8a;border-radius:0 8px 8px 0}
@media(max-width:900px){.docs-workspace{grid-template-columns:230px minmax(0,1fr)}.docs-article{padding:28px 24px 56px}.docs-source-badge{display:none}}
</style>
