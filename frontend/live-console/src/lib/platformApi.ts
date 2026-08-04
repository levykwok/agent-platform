export type JsonMap = Record<string, any>

export function localValue(name: string, fallback = ''): string {
  try {
    return new URLSearchParams(location.search).get(name) || localStorage.getItem(name) || fallback
  } catch {
    return fallback
  }
}

export function currentUser(): string {
  return localValue('platform_live_user_id', localValue('user_id', 'platform_admin'))
}

export function currentDomain(fallback = 'platform'): string {
  try {
    const fromQuery = new URLSearchParams(location.search).get('domain')
    if (fromQuery) {
      localStorage.setItem('platform_live_domain', fromQuery)
      return fromQuery
    }
  } catch {
    // ignore
  }
  return localValue('platform_live_domain', fallback)
}

export function currentOrgId(fallback = 'platform'): string {
  try {
    const params = new URLSearchParams(location.search)
    const fromQuery = params.get('org_id') || params.get('org')
    if (fromQuery) {
      localStorage.setItem('platform_live_org_id', fromQuery)
      return fromQuery
    }
  } catch {
    // ignore
  }
  return localValue('platform_live_org_id', fallback)
}

export function makeHeaders(json = false, org = 'platform'): Record<string, string> {
  const headers: Record<string, string> = { 'x-org-id': org || 'platform', 'x-user-id': currentUser() }
  if (json) headers['content-type'] = 'application/json'
  return headers
}

export async function readJson<T = JsonMap>(response: Response): Promise<T> {
  const text = await response.text()
  let data: any = {}
  try { data = text ? JSON.parse(text) : {} } catch { data = { detail: text } }
  if (!response.ok) throw new Error(typeof data.detail === 'string' ? data.detail : text || response.statusText)
  return data as T
}

export function contextHref(href: string, domain = 'platform', org = 'platform'): string {
  const url = new URL(href, location.origin)
  url.searchParams.set('domain', domain || 'platform')
  url.searchParams.set('org_id', org || 'platform')
  url.searchParams.set('user_id', currentUser())
  return `${url.pathname}${url.search}`
}

export function fmtDate(value: unknown): string {
  if (!value) return ''
  try { return new Date(String(value)).toLocaleString('zh-CN', { hour12: false }) } catch { return String(value) }
}

export function infraState(snapshot: JsonMap, name: string): string {
  const services = snapshot.services || snapshot.infra || snapshot.dependencies || snapshot
  const row = services?.[name]
  if (typeof row === 'boolean') return row ? 'ok' : 'down'
  if (typeof row === 'string') return row
  if (typeof row === 'object' && row) return String(row.status || row.state || row.health || (row.ok ? 'ok' : 'unknown'))
  return 'unknown'
}

export function statusDotClass(status: unknown): string {
  const value = String(status || '').toLowerCase()
  if (['ok', 'up', 'healthy', 'available', 'enabled', 'succeeded', 'running'].includes(value)) return 'green'
  if (['down', 'failed', 'error', 'unhealthy', 'disabled'].includes(value)) return 'red'
  return 'gray'
}
