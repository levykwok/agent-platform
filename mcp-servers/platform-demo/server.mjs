#!/usr/bin/env node
'use strict';

import http from 'node:http';
import { randomUUID } from 'node:crypto';

const PROTOCOL_VERSION = '2025-11-25';
const SERVER_INFO = { name: 'agent-platform-demo-mcp', version: '0.3.0' };

const TOOLSETS = {
  stdio: [
  {
    name: 'platform_calculate',
    title: 'Platform Calculate',
    description: 'Calculate a simple arithmetic expression such as (12 + 8) / 5. Supports +, -, *, /, %, parentheses, and decimals.',
    inputSchema: {
      type: 'object',
      properties: {
        expression: { type: 'string', description: 'Arithmetic expression to calculate.' }
      },
      required: ['expression'],
      additionalProperties: false
    }
  },
  {
    name: 'platform_text_brief',
    title: 'Platform Text Brief',
    description: 'Summarize basic text metrics and extract top keywords for quick content inspection.',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: 'Text to analyze.' }
      },
      required: ['text'],
      additionalProperties: false
    }
  },
  {
    name: 'platform_markdown_toc',
    title: 'Platform Markdown TOC',
    description: 'Extract markdown headings into a table of contents.',
    inputSchema: {
      type: 'object',
      properties: {
        markdown: { type: 'string', description: 'Markdown content.' }
      },
      required: ['markdown'],
      additionalProperties: false
    }
  }
],
  'streamable-http': [
  {
    name: 'platform_fetch_url',
    title: 'Platform Fetch URL',
    description: 'Fetch a public HTTP/HTTPS URL and return status, content type, title, and body preview.',
    inputSchema: {
      type: 'object',
      properties: {
        url: { type: 'string', description: 'HTTP or HTTPS URL to fetch.' },
        max_chars: { type: 'number', description: 'Maximum response body characters to include. Default 1000.' }
      },
      required: ['url'],
      additionalProperties: false
    }
  },
  {
    name: 'platform_extract_links',
    title: 'Platform Extract Links',
    description: 'Extract links from an HTML string or from a fetched URL.',
    inputSchema: {
      type: 'object',
      properties: {
        html: { type: 'string', description: 'HTML string. Optional when url is provided.' },
        url: { type: 'string', description: 'Optional HTTP/HTTPS URL to fetch HTML from.' },
        limit: { type: 'number', description: 'Maximum links to return. Default 20.' }
      },
      additionalProperties: false
    }
  },
  {
    name: 'platform_make_id',
    title: 'Platform Make ID',
    description: 'Generate stable-looking IDs for tests, tickets, documents, or demo records.',
    inputSchema: {
      type: 'object',
      properties: {
        prefix: { type: 'string', description: 'ID prefix, for example task_ or doc_.' },
        count: { type: 'number', description: 'Number of IDs to generate, 1-20. Default 1.' }
      },
      additionalProperties: false
    }
  }
],
  sse: [
  {
    name: 'platform_json_validate',
    title: 'Platform JSON Validate',
    description: 'Validate JSON and report type, keys, count, and scalar value types.',
    inputSchema: {
      type: 'object',
      properties: {
        json: { type: 'string', description: 'JSON document string.' }
      },
      required: ['json'],
      additionalProperties: false
    }
  },
  {
    name: 'platform_json_pick',
    title: 'Platform JSON Pick',
    description: 'Read a value from JSON by a dot path, for example user.name or items.0.id.',
    inputSchema: {
      type: 'object',
      properties: {
        json: { type: 'string', description: 'JSON document string.' },
        path: { type: 'string', description: 'Dot path to read.' }
      },
      required: ['json', 'path'],
      additionalProperties: false
    }
  },
  {
    name: 'platform_date_math',
    title: 'Platform Date Math',
    description: 'Add days or hours to an ISO date/time. Useful for schedule and deadline tests.',
    inputSchema: {
      type: 'object',
      properties: {
        date: { type: 'string', description: 'ISO date/time, for example 2026-07-07T10:00:00+08:00.' },
        days: { type: 'number', description: 'Days to add. Can be negative. Default 0.' },
        hours: { type: 'number', description: 'Hours to add. Can be negative. Default 0.' }
      },
      required: ['date'],
      additionalProperties: false
    }
  }
]
};

const args = new Map();
for (let i = 2; i < process.argv.length; i += 1) {
  const arg = process.argv[i];
  if (!arg.startsWith('--')) continue;
  const key = arg.slice(2);
  const next = process.argv[i + 1];
  if (next && !next.startsWith('--')) {
    args.set(key, next);
    i += 1;
  } else {
    args.set(key, 'true');
  }
}

const transport = args.get('transport') || 'stdio';
const tools = TOOLSETS[transport] || [];
if (transport === 'stdio') {
  startStdio();
} else if (transport === 'streamable-http' || transport === 'http') {
  startHttp({ mode: 'streamable-http', port: Number(args.get('port') || 8765) });
} else if (transport === 'sse') {
  startHttp({ mode: 'sse', port: Number(args.get('port') || 8766) });
} else {
  process.stderr.write(`Unsupported transport: ${transport}\n`);
  process.exit(2);
}

function startStdio() {
  let buffer = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buffer += chunk;
    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (line) writeJsonRpc(process.stdout, handleJsonRpcLine(line));
    }
  });
}

function startHttp({ mode, port }) {
  const sessions = new Map();
  const server = http.createServer(async (req, res) => {
    try {
      if (req.method === 'GET' && (req.url === '/health' || req.url === '/')) {
        sendJson(res, 200, { ok: true, mode, serverInfo: SERVER_INFO });
        return;
      }
      if (mode === 'sse' && req.method === 'GET' && req.url.startsWith('/sse')) {
        openSse(req, res, sessions);
        return;
      }
      if (
        mode === 'sse' &&
        req.method === 'POST' &&
        (req.url.startsWith('/message') || req.url.startsWith('/sse/message'))
      ) {
        const message = await readJson(req);
        const sessionId = new URL(req.url, 'http://localhost').searchParams.get('sessionId') || message.sessionId;
        const session = sessions.get(sessionId);
        const response = await handleJsonRpc(message);
        if (session && response) sendSse(session.res, response);
        sendJson(res, 202, { ok: true, sessionId });
        return;
      }
      if (mode === 'streamable-http' && req.method === 'POST' && (req.url === '/mcp' || req.url === '/')) {
        const message = await readJson(req);
        const response = await handleJsonRpc(message);
        if (response === undefined) {
          res.writeHead(202).end();
          return;
        }
        sendJson(res, 200, response, { 'Mcp-Session-Id': req.headers['mcp-session-id'] || randomUUID() });
        return;
      }
      sendJson(res, 404, { error: 'not_found' });
    } catch (error) {
      sendJson(res, 500, errorEnvelope(null, -32603, error.message || 'Internal error'));
    }
  });
  server.listen(port, '127.0.0.1', () => {
    process.stderr.write(`[platform-demo-mcp] ${mode} listening on http://127.0.0.1:${port}\n`);
  });
}

function openSse(req, res, sessions) {
  const sessionId = new URL(req.url, 'http://localhost').searchParams.get('sessionId') || randomUUID();
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no'
  });
  sessions.set(sessionId, { res });
  sendSse(res, `message?sessionId=${encodeURIComponent(sessionId)}`, 'endpoint', true);
  req.on('close', () => sessions.delete(sessionId));
}

async function handleJsonRpcLine(line) {
  try {
    return await handleJsonRpc(JSON.parse(line));
  } catch (error) {
    return errorEnvelope(null, -32700, 'Parse error');
  }
}

async function handleJsonRpc(request) {
  if (!request || request.jsonrpc !== '2.0' || typeof request.method !== 'string') {
    return errorEnvelope(request?.id ?? null, -32600, 'Invalid Request');
  }

  const { id, method, params } = request;
  try {
    if (method === 'notifications/initialized' || method.startsWith('notifications/')) {
      return undefined;
    }
    if (method === 'initialize') {
      return resultEnvelope(id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: { listChanged: false } },
        serverInfo: SERVER_INFO,
        instructions: 'Demo MCP server for the agent platform.'
      });
    }
    if (method === 'tools/list') {
      return resultEnvelope(id, { tools });
    }
    if (method === 'tools/call') {
      return resultEnvelope(id, await callTool(params || {}));
    }
    return errorEnvelope(id, -32601, `Method not found: ${method}`);
  } catch (error) {
    return errorEnvelope(id, -32603, error.message || 'Internal error');
  }
}

async function callTool(params) {
  const name = String(params.name || '');
  const callArgs = params.arguments && typeof params.arguments === 'object' ? params.arguments : {};
  if (name === 'platform_calculate') {
    return textResult(JSON.stringify(calculateExpression(String(callArgs.expression ?? '')), null, 2));
  }
  if (name === 'platform_text_brief') {
    const text = String(callArgs.text ?? '');
    const lines = text ? text.split(/\r?\n/) : [];
    const words = text.trim() ? text.trim().split(/\s+/) : [];
    const cjk = text.match(/[\u3400-\u9fff]/g) || [];
    return textResult(JSON.stringify({
      chars: text.length,
      lines: lines.length,
      words: words.length,
      cjk_chars: cjk.length,
      keywords: topKeywords(text)
    }, null, 2));
  }
  if (name === 'platform_markdown_toc') {
    const markdown = String(callArgs.markdown ?? '');
    const headings = markdown.split(/\r?\n/)
      .map((line, index) => ({ line: index + 1, match: /^(#{1,6})\s+(.+?)\s*$/.exec(line) }))
      .filter((item) => item.match)
      .map((item) => ({ line: item.line, level: item.match[1].length, title: item.match[2] }));
    return textResult(JSON.stringify({ headings }, null, 2));
  }
  if (name === 'platform_fetch_url') {
    return await fetchUrl(callArgs);
  }
  if (name === 'platform_extract_links') {
    return textResult(JSON.stringify(await extractLinks(callArgs), null, 2));
  }
  if (name === 'platform_make_id') {
    const prefix = String(callArgs.prefix || '');
    const count = Math.max(1, Math.min(20, Number(callArgs.count || 1)));
    return textResult(JSON.stringify(Array.from({ length: count }, () => `${prefix}${randomUUID()}`), null, 2));
  }
  if (name === 'platform_json_validate') {
    const value = JSON.parse(String(callArgs.json ?? ''));
    const type = Array.isArray(value) ? 'array' : value === null ? 'null' : typeof value;
    return textResult(JSON.stringify({
      valid: true,
      type,
      keys: type === 'object' ? Object.keys(value) : [],
      count: Array.isArray(value) ? value.length : undefined,
      field_types: type === 'object' ? Object.fromEntries(Object.entries(value).map(([key, val]) => [key, Array.isArray(val) ? 'array' : val === null ? 'null' : typeof val])) : {}
    }, null, 2));
  }
  if (name === 'platform_json_pick') {
    const json = JSON.parse(String(callArgs.json ?? ''));
    const path = String(callArgs.path ?? '').split('.').filter(Boolean);
    let current = json;
    for (const part of path) {
      if (current == null) break;
      current = Array.isArray(current) && /^\d+$/.test(part) ? current[Number(part)] : current[part];
    }
    return textResult(JSON.stringify({ path: path.join('.'), value: current }, null, 2));
  }
  if (name === 'platform_date_math') {
    const date = new Date(String(callArgs.date || ''));
    if (Number.isNaN(date.getTime())) throw new Error('date must be a valid ISO date/time');
    const days = Number(callArgs.days || 0);
    const hours = Number(callArgs.hours || 0);
    const result = new Date(date.getTime() + days * 86400000 + hours * 3600000);
    return textResult(JSON.stringify({ input: date.toISOString(), days, hours, result: result.toISOString() }, null, 2));
  }
  throw new Error(`Unknown tool: ${name}`);
}

function calculateExpression(expression) {
  if (!/^[\d\s+\-*/%().]+$/.test(expression)) {
    throw new Error('expression contains unsupported characters');
  }
  // Demo-only arithmetic evaluator after strict character allow-listing.
  // eslint-disable-next-line no-new-func
  const value = Function(`"use strict"; return (${expression});`)();
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error('expression did not produce a finite number');
  }
  return { expression, value };
}

function topKeywords(text) {
  const stop = new Set(['the', 'and', 'for', 'with', 'this', 'that', '你', '我', '的', '了', '是']);
  const counts = new Map();
  for (const word of text.toLowerCase().match(/[a-z0-9_]{2,}|[\u3400-\u9fff]{2,}/g) || []) {
    if (stop.has(word)) continue;
    counts.set(word, (counts.get(word) || 0) + 1);
  }
  return Array.from(counts.entries())
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, 8)
    .map(([word, count]) => ({ word, count }));
}

async function fetchUrl(callArgs) {
  const url = String(callArgs.url || '');
  if (!/^https?:\/\//i.test(url)) throw new Error('url must start with http:// or https://');
  const maxChars = Math.max(100, Math.min(5000, Number(callArgs.max_chars || 1000)));
  const response = await fetch(url);
  const body = await response.text();
  const title = /<title[^>]*>([\s\S]*?)<\/title>/i.exec(body)?.[1]?.replace(/\s+/g, ' ').trim() || '';
  return textResult(JSON.stringify({
    url,
    status: response.status,
    ok: response.ok,
    content_type: response.headers.get('content-type') || '',
    title,
    body_preview: body.slice(0, maxChars)
  }, null, 2));
}

async function extractLinks(callArgs) {
  let html = String(callArgs.html || '');
  const url = String(callArgs.url || '');
  if (!html && url) {
    if (!/^https?:\/\//i.test(url)) throw new Error('url must start with http:// or https://');
    const response = await fetch(url);
    html = await response.text();
  }
  const limit = Math.max(1, Math.min(100, Number(callArgs.limit || 20)));
  const links = [];
  const regex = /<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
  let match;
  while ((match = regex.exec(html)) && links.length < limit) {
    links.push({
      href: match[1],
      text: match[2].replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim()
    });
  }
  return { count: links.length, links };
}

function textResult(text) {
  return { content: [{ type: 'text', text }] };
}

function resultEnvelope(id, result) {
  return { jsonrpc: '2.0', id, result };
}

function errorEnvelope(id, code, message) {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function writeJsonRpc(stream, message) {
  Promise.resolve(message)
    .then((resolved) => {
      if (resolved === undefined) return;
      stream.write(`${JSON.stringify(resolved)}\n`);
    })
    .catch((error) => {
      stream.write(`${JSON.stringify(errorEnvelope(null, -32603, error.message || 'Internal error'))}\n`);
    });
}

function sendJson(res, status, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': 'application/json', ...headers });
  res.end(JSON.stringify(body));
}

function sendSse(res, body, event = 'message', raw = false) {
  res.write(`event: ${event}\n`);
  res.write(`data: ${raw ? String(body) : JSON.stringify(body)}\n\n`);
}

function readJson(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    req.setEncoding('utf8');
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      try { resolve(body ? JSON.parse(body) : {}); }
      catch (error) { reject(error); }
    });
    req.on('error', reject);
  });
}

process.on('uncaughtException', (error) => {
  process.stderr.write(`[platform-demo-mcp] ${error.stack || error.message}\n`);
});

