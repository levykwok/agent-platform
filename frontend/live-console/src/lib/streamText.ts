/**
 * Streaming model output can contain several progress sentences in one text
 * block without whitespace between them. Keep those progress updates readable
 * while leaving ordinary prose untouched.
 */
export function formatInterimText(value: unknown): string {
  const text = String(value ?? '').replace(/\r\n/g, '\n')
  if (!text) {
    return text
  }
  return text.replace(
    /([.!?。！？:：])\s*(?=(?:I'll|I’ll|Let me|Let's|I need to|I will|I'm going to|I’m going to|I now|Python(?:3)?|The smoke check|The shell|当前进展|已完成|验证脚本|让我|接下来|现在我|我将|首先|然后|继续)(?=\s|[A-Za-z0-9`]|[一-龥]|$))/gi,
    '$1\n',
  )
}
