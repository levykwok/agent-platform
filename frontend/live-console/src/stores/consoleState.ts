import { reactive } from 'vue'
import type { ActivityItem, TurnContext } from '../types'

export const consoleState = reactive({
  domain: 'platform',
  sessionId: `live_${Date.now()}`,
  query: '',
  streaming: false,
  activeTraceId: '',
  activeTab: 'activity',
  activity: [] as ActivityItem[],
  turnContext: null as TurnContext | null,
  error: '',
})

export function resetTurn(traceId: string) {
  consoleState.activeTraceId = traceId
  consoleState.activity = [
    {
      id: `turn_${traceId}`,
      type: 'turn.received',
      title: '接收用户问题',
      status: 'success',
      summary: `trace_id=${traceId}`,
    },
  ]
  consoleState.turnContext = null
  consoleState.error = ''
}

export function upsertActivity(item: ActivityItem) {
  const idx = consoleState.activity.findIndex((row) => row.id === item.id)
  if (idx >= 0) {
    consoleState.activity[idx] = { ...consoleState.activity[idx], ...item }
  } else {
    consoleState.activity.push(item)
  }
}
