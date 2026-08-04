# 平台 Python Tool 编写模板

用于普通业务上传/发布 Python Tool 的最小兼容格式。

## 1. metadata

```text
{
  "name": "platform_xxx",
  "title": "工具标题",
  "version": "1.0.0",
  "description": "用途说明",
  "author": "team",
  "scope": "agent|global",
  "timeout_ms": 5000
}
```

## 2. input_schema

必须使用 JSON Schema 风格（兼容 tool args 校验）：

```json
{
  "type": "object",
  "properties": {
    "text": {"type": "string", "description": "输入文本"}
  },
  "required": ["text"],
  "additionalProperties": false
}
```

## 3. main(args)

建议入口：

```python
def main(args):
    # args: dict
    # return: dict | str
    ...
```

返回建议：

```json
{"ok": true, "data": {...}, "message": "ok"}
```

## 4. stdout/stderr 规范

```text
stdout：仅用于人读日志（非结构化时可见但不作为解析源）。
stderr：记录可观测日志（仅在异常/告警时输出）。
平台应优先消费 main 返回值，不依赖 stdout 解析。
```

## 5. 错误格式

```json
{"ok": false, "error": {"code": "bad_input", "message": "text required"}}
```

异常场景也应尽量返回上述 JSON 格式；若抛出未捕获异常，平台应记录 `tool_execution_error`。

## 6. timeout

```text
推荐 3000~10000ms，默认 5000ms
超时后返回：{"ok": false, "error": {"code":"timeout", "message":"tool execution timeout"}}
```

## 7. 安全限制

```text
1. 禁止执行系统命令（如 os.system、subprocess 未经白名单）。
2. 禁止网络访问（除平台明确放行）。
3. 禁止读取环境变量与密钥文件。
4. 限制文件写入路径到当前工作区。
5. 单次执行禁止过量内存/超大输出。
```

