export type ActivityStatus = 'pending' | 'running' | 'success' | 'skipped' | 'warning' | 'error'

export interface ActivityItem {
  id: string
  type: string
  title: string
  status: ActivityStatus
  summary?: string
  started_at?: string
  finished_at?: string
  duration_ms?: number
  refs?: Record<string, unknown>
  detail?: Record<string, unknown>
}

export interface ConsoleResource {
  id: string
  kind: string
  title: string
  status?: string
  source?: string
  refs?: Record<string, unknown>
  metadata?: Record<string, unknown>
}

export interface LongTermMemoryRef {
  id: number
  kind?: 'long_term'
  memory_type?: string
  content: string
  status?: string
  confidence?: number
  domain?: string
  scope?: string
  why_used?: string[]
  source?: Record<string, unknown>
}

export interface MemoryCenterItem {
  id: number
  scope?: string
  scope_id?: string
  project_id?: string
  agent_id?: string
  domain?: string
  business_key?: string
  memory_type?: string
  status?: string
  content: string
  content_summary?: string
  confidence?: number
  expired_at?: string | null
  source_session_id?: string
  source_run_id?: string
  source_ref?: Record<string, unknown> | null
  created_at?: string
  updated_at?: string
}

export interface EpisodicMemoryRef {
  id: number
  kind?: 'episodic'
  summary: string
  session_id?: string
  topics?: string[]
  why_used?: string[]
}

export interface TurnContext {
  trace_id: string
  session_id: string
  domain: string
  feature?: string
  turn_id?: string
  activity: ActivityItem[]
  resources: {
    attachments: ConsoleResource[]
    documents: ConsoleResource[]
    citations: ConsoleResource[]
    kg_hits: ConsoleResource[]
    tool_calls: ConsoleResource[]
    artifacts: ConsoleResource[]
  }
  memory_usage: {
    session: Record<string, unknown>
    long_term: LongTermMemoryRef[]
    episodic: EpisodicMemoryRef[]
    written: {
      session_messages: unknown[]
      summary: string | null
      long_term_memory_ids: number[]
      episodic_ids: number[]
    }
  }
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  trace_id?: string
}
