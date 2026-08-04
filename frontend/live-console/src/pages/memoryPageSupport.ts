import { currentOrgId, localValue, makeHeaders, readJson, type JsonMap } from '../lib/platformApi'

export function confidenceText(value: unknown): string {
  return typeof value === 'number' ? value.toFixed(2) : String(value ?? '')
}

export function useMemoryApi() {
  function orgId() { return currentOrgId() }
  function userId() { return localValue('platform_live_user_id', localValue('user_id', 'platform_admin')) }
  function headers(json = false) { return makeHeaders(json, orgId()) }
  return {
    orgId,
    userId,
    headers,
    async list(query: string) {
      const data = await readJson<JsonMap>(await fetch(`/platform/frontend/memory/long-term?${query}`, { headers: headers(false) }))
      return Array.isArray(data.items) ? data.items : []
    },
    async get(id: unknown) {
      return await readJson<JsonMap>(await fetch(`/platform/frontend/memory/long-term/${encodeURIComponent(String(id))}`, { headers: headers(false) }))
    },
    async audit(query: string) {
      return await readJson<JsonMap>(await fetch(`/platform/frontend/memory/audit?${query}`, { headers: headers(false) }))
    },
    async daily(agentId = 'researcher') {
      const p = new URLSearchParams()
      if (agentId) p.set('agent_id', agentId)
      return await readJson<JsonMap>(await fetch(`/platform/frontend/memory/daily?${p}`, { headers: headers(false) }))
    },
    async episodic(domain: string) {
      const p = new URLSearchParams()
      if (domain) p.set('domain', domain)
      return await readJson<JsonMap>(await fetch(`/platform/frontend/memory/episodic/status?${p}`, { headers: headers(false) }))
    },
    async save(editor: JsonMap, domain: string) {
      const payload = { content: editor.content, scope: editor.scope, memory_type: editor.memory_type, status: editor.status, confidence: Number(editor.confidence), domain }
      if (editor.id) return await readJson(await fetch(`/platform/frontend/memory/long-term/${encodeURIComponent(String(editor.id))}`, { method: 'PATCH', headers: headers(true), body: JSON.stringify(payload) }))
      return await readJson(await fetch('/platform/frontend/memory/long-term', { method: 'POST', headers: headers(true), body: JSON.stringify(payload) }))
    },
    async patch(id: unknown, payload: JsonMap) { return await readJson(await fetch(`/platform/frontend/memory/long-term/${encodeURIComponent(String(id))}`, { method: 'PATCH', headers: headers(true), body: JSON.stringify(payload) })) },
    async patchStatus(id: unknown, status: string) { return await readJson(await fetch(`/platform/frontend/memory/long-term/${encodeURIComponent(String(id))}`, { method: 'PATCH', headers: headers(true), body: JSON.stringify({ status }) })) },
    async confirm(id: unknown, update_content = '', comment = '') { return await readJson(await fetch(`/platform/frontend/memory/long-term/${encodeURIComponent(String(id))}/confirm`, { method: 'POST', headers: headers(true), body: JSON.stringify({ update_content, comment }) })) },
    async reject(id: unknown, comment: string) { return await readJson(await fetch(`/platform/frontend/memory/long-term/${encodeURIComponent(String(id))}/reject`, { method: 'POST', headers: headers(true), body: JSON.stringify({ comment }) })) },
    async merge(id: unknown, target_memory_id: number, update_content = '', comment = '') { return await readJson(await fetch(`/platform/frontend/memory/long-term/${encodeURIComponent(String(id))}/merge`, { method: 'POST', headers: headers(true), body: JSON.stringify({ target_memory_id, update_content: update_content || undefined, comment }) })) },
    async remove(id: unknown) { return await readJson(await fetch(`/platform/frontend/memory/long-term/${encodeURIComponent(String(id))}`, { method: 'DELETE', headers: headers(false) })) },
    async removeRuntimeEntry(agentId: string, content: string) { return await readJson(await fetch(`/platform/frontend/memory/agent/${encodeURIComponent(agentId)}/main-entry`, { method: 'DELETE', headers: headers(true), body: JSON.stringify({ content }) })) },
    async maintenanceStatus(domain: string) {
      const p = new URLSearchParams()
      if (domain) p.set('domain', domain)
      return await readJson<JsonMap>(await fetch(`/platform/frontend/memory/maintenance/status?${p}`, { headers: headers(false) }))
    },
    async maintenanceDryRun(domain: string) { return await readJson<JsonMap>(await fetch('/platform/frontend/memory/maintenance/dry-run', { method: 'POST', headers: headers(true), body: JSON.stringify({ scope: 'user', all_users: true, domain: domain || null, limit: 1000 }) })) },
    async episodicMaintenance(domain: string, dry_run: boolean) { return await readJson<JsonMap>(await fetch('/platform/frontend/memory/episodic/maintenance/run', { method: 'POST', headers: headers(true), body: JSON.stringify({ domain: domain || '', dry_run, delete_expired: true, limit: 1000 }) })) },
    async episodicRebuild(domain: string, dry_run: boolean) { return await readJson<JsonMap>(await fetch('/platform/frontend/memory/episodic/rebuild', { method: 'POST', headers: headers(true), body: JSON.stringify({ domain: domain || '', dry_run, clear_existing: !dry_run, ttl_days: 90, limit: 1000 }) })) },
    async episodicClear(domain: string) { return await readJson<JsonMap>(await fetch('/platform/frontend/memory/episodic/clear', { method: 'POST', headers: headers(true), body: JSON.stringify({ domain }) })) },
  }
}
