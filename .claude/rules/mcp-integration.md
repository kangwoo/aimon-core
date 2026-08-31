---
paths:
  - "modules/aimon-core/src/**/mcp/**/*.java"
---

# MCP (Model Context Protocol) Integration Rules

## Architecture
- `McpTransport` interface: abstracts stdio/SSE communication
- `McpClient`: protocol-level abstraction over transport
- `McpTool`: wrapper that implements `Tool` interface for MCP tools
- `McpServerConfig`: server configuration with name validation
- `McpClientManager`: manages MCP client lifecycle (connect/disconnect)
- `OrcaMcpToolProvider`: registers MCP tools into the Tool system

## Key Principles
- MCP tools must be transparent — they behave identically to local tools from the Agent's perspective
- `McpClient` must be thread-safe
- Server config names must follow validation rules in `McpServerConfig`
- Lifecycle management: `McpClientManager` handles connection/disconnection
