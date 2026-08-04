import { reactive } from 'vue'

export interface DialogField {
  key: string
  label: string
  type?: 'text' | 'select' | 'textarea'
  default?: string
  options?: { value: string; label: string }[]
  placeholder?: string
}

interface DialogState {
  open: boolean
  title: string
  message: string
  fields: DialogField[]
  values: Record<string, string>
  confirmLabel: string
  cancelLabel: string
  danger: boolean
  resolve: ((value: Record<string, string> | null) => void) | null
}

export const dialogState = reactive<DialogState>({
  open: false,
  title: '',
  message: '',
  fields: [],
  values: {},
  confirmLabel: '确定',
  cancelLabel: '取消',
  danger: false,
  resolve: null,
})

function openDialog(opts: { title: string; message?: string; fields?: DialogField[]; confirmLabel?: string; cancelLabel?: string; danger?: boolean }): Promise<Record<string, string> | null> {
  return new Promise((resolve) => {
    dialogState.title = opts.title
    dialogState.message = opts.message || ''
    dialogState.fields = opts.fields || []
    dialogState.values = Object.fromEntries((opts.fields || []).map((f) => [f.key, f.default ?? '']))
    dialogState.confirmLabel = opts.confirmLabel || '确定'
    dialogState.cancelLabel = opts.cancelLabel || '取消'
    dialogState.danger = Boolean(opts.danger)
    dialogState.resolve = resolve
    dialogState.open = true
  })
}

export function confirmDialog(message: string, opts: { title?: string; confirmLabel?: string; danger?: boolean } = {}): Promise<boolean> {
  return openDialog({ title: opts.title || '确认操作', message, confirmLabel: opts.confirmLabel || '确定', danger: opts.danger }).then((v) => v !== null)
}

export function promptDialog(title: string, label: string, defaultValue = '', opts: { placeholder?: string; confirmLabel?: string } = {}): Promise<string | null> {
  return openDialog({
    title,
    fields: [{ key: 'value', label, default: defaultValue, placeholder: opts.placeholder }],
    confirmLabel: opts.confirmLabel,
  }).then((v) => (v ? v.value : null))
}

export function formDialog(opts: { title: string; message?: string; fields: DialogField[]; confirmLabel?: string }): Promise<Record<string, string> | null> {
  return openDialog(opts)
}

export function resolveDialog(values: Record<string, string> | null) {
  if (dialogState.resolve) dialogState.resolve(values)
  dialogState.open = false
  dialogState.resolve = null
}
