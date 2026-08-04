# Agent Runtime Research Note

AgentScope based runtimes need clear separation between platform management and agent execution.

The platform should own model configuration, MCP server lifecycle, tool discovery, skill package storage, and session persistence.

The agent runtime should receive an already assembled capability set and focus on model calls, tool calls, memory access, and orchestration events.

For debugging, the UI should show meaningful business steps such as capability loading, tool invocation, tool results, skill loading, and workflow transitions.
