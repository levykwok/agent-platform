import type { MemoryCenterItem, TurnContext } from '../types'
import { localValue, makeHeaders, readJson } from '../lib/platformApi'

export function platformHeaders(json = false): Record<string, string> {
  const fromShell = (window as unknown as { PlatformLive?: { headers?: (json?: boolean) => Record<string, string> } }).PlatformLive
  if (fromShell?.headers) return fromShell.headers(json)
  const headers = makeHeaders(json, localValue('org_id', localValue('platform_live_org_id', 'platform')))
  headers['x-user-id'] = localValue('user_id', localValue('platform_live_user_id', 'platform_admin'))
  return headers
}

export async function fetchTurnContext(traceId: string): Promise<TurnContext> {
  return readJson<TurnContext>(await fetch(`/platform/frontend/live-context/turns/${encodeURIComponent(traceId)}`, {
    headers: platformHeaders(),
  }))
}

export async function fetchSessionResources(sessionId: string): Promise<TurnContext['resources']> {
  return readJson<TurnContext['resources']>(await fetch(`/platform/frontend/live-context/sessions/${encodeURIComponent(sessionId)}/resources`, {
    headers: platformHeaders(),
  }))
}

export async function sendMemoryFeedback(memoryId: number, action: string, payload: Record<string, unknown> = {}): Promise<unknown> {
  return readJson<unknown>(await fetch(`/platform/frontend/live-context/memory/${memoryId}/feedback`, {
    method: 'POST',
    headers: platformHeaders(true),
    body: JSON.stringify({ action, ...payload }),
  }))
}

export async function fetchLongTermMemories(params: Record<string, string | number | undefined> = {}): Promise<{ items: MemoryCenterItem[], count: number }> {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') query.set(key, String(value))
  }
  return readJson<{ items: MemoryCenterItem[], count: number }>(await fetch(`/platform/frontend/memory/long-term?${query}`, {
    headers: platformHeaders(),
  }))
}

export async function fetchConversationMemoryStatus(domain = ''): Promise<Record<string, unknown>> {
  const query = new URLSearchParams()
  if (domain) query.set('domain', domain)
  return readJson<Record<string, unknown>>(await fetch(`/platform/frontend/memory/episodic/status?${query}`, {
    headers: platformHeaders(),
  }))
}

export async function createLongTermMemory(payload: Record<string, unknown>): Promise<{ id: number }> {
  return readJson<{ id: number }>(await fetch('/platform/frontend/memory/long-term', {
    method: 'POST',
    headers: platformHeaders(true),
    body: JSON.stringify(payload),
  }))
}

export async function updateLongTermMemory(memoryId: number, payload: Record<string, unknown>): Promise<{ item: MemoryCenterItem }> {
  return readJson<{ item: MemoryCenterItem }>(await fetch(`/platform/frontend/memory/long-term/${encodeURIComponent(String(memoryId))}`, {
    method: 'PATCH',
    headers: platformHeaders(true),
    body: JSON.stringify(payload),
  }))
}
