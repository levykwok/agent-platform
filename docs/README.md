# AI Agent Platform 文档

这里存放私有化智能体平台的用户文档，内容以当前仓库中的
`frontend/live-console` 和平台后端实现为准。

## 本地预览

```powershell
cd docs
python -m pip install -e ".[dev]"
jupyter-book build .
```

构建结果位于 `docs/_build/html/index.html`。

## 维护约定

- 界面入口、按钮或流程发生变化时，同步更新 `guide/`。
- 启动参数、端口或配置文件发生变化时，同步更新 `operations/`。
- 只记录已经实现的能力；尚未挂到菜单的页面统一放在“待开放功能”中。
- 文档默认面向平台使用者，代码实现细节放在仓库 `doc/agent-platform/`。
