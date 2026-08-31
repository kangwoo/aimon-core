---
paths:
  - "modules/aimon-core/src/**/llm/**/*.java"
  - "modules/aimon-llm-*/src/**/*.java"
---

# LLM Provider Development Rules

## Interface Contract
- Implement `LlmClient` interface from `at.aimon.core.llm`
- Method signature: `sendMessage(systemPrompt, messages, tools, modelConfig) -> LlmResponse`
- Must be thread-safe and stateless
- Provider-specific implementations go in separate modules (e.g., `aimon-llm-openai`)

## Message Model
- Use `Message`, `Role`, `ToolUse`, `ToolUseResult`, `TokenUsage` from core
- Content blocks: `TextContent`, `ImageContent`, `DocumentContent`
- Use `ToolDefinition` / `ToolDefinitionProvider` (static or dynamic) for tool schemas

## Error Handling
- Wrap provider-specific exceptions in `LlmClientException`
- Never expose provider SDK types outside the module boundary

## Module Separation
- Core module (`aimon-core`) defines interfaces and abstractions only
- Implementation modules depend on core, not the other way around
