# 运行事件脱敏规则

## 需脱敏字段

```text
- api_key
- authorization header
- file path（本地绝对路径、可疑敏感路径）
- source code snippet（包含密钥片段、凭据）
- tool args（可能包含 token、密码、客户隐私）
- model raw response（含 PII、机密配置）
- memory content（提交给模型前建议模糊化敏感字段）
```

## 前端展示规则

```text
1. 统一渲染为不可编辑文本预览。
2. 敏感字段使用掩码显示（如 ********a3f1）。
3. tool args 长字符串超限时只保留前后 30 个字符。
4. 点击“查看原文”需要权限校验（debug/admin）。
```

## 后端日志规则

```text
1. 入库前统一走 redaction util：字段名包含 api_key/authorization/password/token/path 时脱敏。
2. 对 content/model raw response 做长度截断，并记录 content_len 而非完整内容。
3. file path 进行规范化后替换为 <path>，保留目录深度摘要用于排障。
4. tool args 做 key-aware 过滤：args 中若出现 key in [api_key, token, secret, password, authorization, header]，值统一替换为 ***。
5. memory content 以事件类型 memory_save / memory_import 时仅保留 hash 与长度，详情需单独审计权限。
```

## 例外

```text
- 非生产环境可放宽匿名化阈值，但仍保留事件结构完整性
- 审计人员读取明文前必须经过二次授权与审计日志记录
```

