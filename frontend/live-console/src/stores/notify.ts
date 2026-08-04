import { reactive } from 'vue'

export type ToastKind = 'success' | 'error' | 'info'

export interface ToastItem {
  id: number
  kind: ToastKind
  message: string
}

let nextId = 1

export const toastState = reactive({
  items: [] as ToastItem[],
})

export function pushToast(message: string, kind: ToastKind = 'info', timeoutMs = 4000) {
  const id = nextId++
  toastState.items.push({ id, kind, message })
  if (timeoutMs > 0) {
    setTimeout(() => dismissToast(id), timeoutMs)
  }
  return id
}

export function dismissToast(id: number) {
  const idx = toastState.items.findIndex((item) => item.id === id)
  if (idx >= 0) toastState.items.splice(idx, 1)
}

export function notifySuccess(message: string) {
  return pushToast(message, 'success')
}

export function notifyError(err: unknown) {
  return pushToast(err instanceof Error ? err.message : String(err), 'error', 6000)
}

export function notifyInfo(message: string) {
  return pushToast(message, 'info')
}
