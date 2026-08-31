---
translated_from: docs/features/llm/llm-provider-development-guide.md
source_commit: 30adc679
---

# LLM Provider Development Guide

> A guide to developing an LLM provider

This document covers what you need in order to add a new LLM provider to the aimon-core framework.

## Table of contents

1. [Overview](#overview)
2. [The core interfaces](#the-core-interfaces)
3. [Implementation steps](#implementation-steps)
4. [Message conversion](#message-conversion)
5. [Tool calling support](#tool-calling-support)
6. [Configuration classes](#configuration-classes)
7. [Error handling](#error-handling)
8. [A full example](#a-full-example)

---

## Overview

An LLM provider implements the `LlmClient` interface to integrate with an LLM API — OpenAI, Anthropic, Google and so on.

### Core principles

| Principle | Description |
|------|------|
| **Thread-safe** | Able to serve concurrent requests |
| **Stateless** | Keeps no state between requests |
| **Tool calling support** | Function/tool calling conversion is mandatory |
| **Error handling** | Every error is wrapped in `LlmClientException` |

### Package layout

```
at.aimon.llm/
├── core/                          # the core abstractions (aimon-core)
│   ├── LlmClient.java             # the LLM client interface
│   ├── LlmResponse.java           # an LLM response
│   ├── LlmModel.java              # model settings
│   ├── Message.java               # a conversation message
│   ├── Role.java                  # the message role (USER, ASSISTANT, TOOL)
│   ├── ToolDefinition.java        # a tool definition
│   ├── ToolUse.java               # a tool invocation request
│   ├── ToolUseResult.java         # a tool execution result
│   ├── TokenUsage.java            # token usage
│   └── exception/
│       └── LlmClientException.java
└── openai/                        # the OpenAI implementation (aimon-llm-openai)
    ├── OpenAILlmClient.java
    ├── OpenAIConfig.java
    ├── OpenAIMessageConverter.java
    └── exception/
```

---

## The core interfaces

### LlmClient

```java
public interface LlmClient {

    /**
     * Sends messages to the LLM and returns its response.
     *
     * @param systemPrompt the system prompt
     * @param messages     the conversation history
     * @param tools        the available tool definitions
     * @param modelConfig  model settings (temperature, tokens, ...)
     * @return the LLM response
     * @throws LlmClientException if the API call fails
     */
    LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                           List<ToolDefinition> tools, LlmModel modelConfig);

    /**
     * Returns the provider name.
     */
    String getProviderName();
}
```

### LlmResponse

```java
public class LlmResponse {
    private final String textContent;      // the text response
    private final List<ToolUse> toolUses;  // tool invocation requests
    private final TokenUsage tokenUsage;   // token usage
}
```

### Message

```java
public class Message {
    private final Role role;                      // USER, ASSISTANT, TOOL
    private final String content;                 // the text content
    private final List<ToolUse> toolUses;         // tool invocations (ASSISTANT)
    private final List<ToolUseResult> toolUseResults; // tool results (TOOL)
}
```

---

## Implementation steps

### 1. Create the configuration class

```java
public class CustomLlmConfig {
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;

    private CustomLlmConfig(Builder builder) {
        this.apiKey = Objects.requireNonNull(builder.apiKey, "API key cannot be null");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException("API key cannot be blank");
        }
        this.model = builder.model != null ? builder.model : "default-model";
        this.baseUrl = builder.baseUrl;
        this.temperature = builder.temperature;
        this.maxTokens = builder.maxTokens;
        this.timeout = builder.timeout != null ? builder.timeout : Duration.ofSeconds(60);
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters...

    public static class Builder {
        private String apiKey;
        private String model;
        private String baseUrl;
        private double temperature = 0.7;
        private int maxTokens = 4096;
        private Duration timeout;

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        // Other builder methods...

        public CustomLlmConfig build() {
            return new CustomLlmConfig(this);
        }
    }
}
```

> **Configuration validity ends here.** Since the constructor rejects a blank key, a configuration
> that was constructed successfully is by definition valid. That is why `LlmClient` has no method
> asking "am I configured right now?" — no implementation could ever make such a method return
> `false`, so it would only go through the motions of checking. Holding a client *is* the statement
> that the configuration is valid, and **whether the key actually works** can only be learned from
> a call's outcome (`LlmClientException`).

### 2. Implement LlmClient

```java
public class CustomLlmClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(CustomLlmClient.class);

    private final CustomLlmConfig config;
    private final HttpClient httpClient;  // or the provider's SDK client

    public CustomLlmClient(CustomLlmConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.httpClient = createHttpClient(config);
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                                   List<ToolDefinition> tools, LlmModel modelConfig) {
        Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(modelConfig, "Model config cannot be null");

        try {
            // 1. build the request
            var request = buildRequest(systemPrompt, messages, tools, modelConfig);

            // 2. call the API
            var response = callApi(request);

            // 3. convert the response
            return convertResponse(response);

        } catch (Exception e) {
            logger.error("API call failed: {}", e.getMessage(), e);
            throw new LlmClientException("API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String getProviderName() {
        return "Custom Provider (" + config.getModel() + ")";
    }

    // Private helper methods...
}
```

### 3. Create the message converter

```java
public class CustomMessageConverter {

    /**
     * Converts an aimon Message into the provider's format.
     */
    public List<ProviderMessage> convertMessages(List<Message> messages) {
        List<ProviderMessage> result = new ArrayList<>();

        for (Message message : messages) {
            switch (message.getRole()) {
                case USER -> result.add(convertUserMessage(message));
                case ASSISTANT -> result.add(convertAssistantMessage(message));
                case TOOL -> result.addAll(convertToolResults(message));
            }
        }

        return result;
    }

    /**
     * Converts an aimon ToolDefinition into the provider's format.
     */
    public List<ProviderTool> convertTools(List<ToolDefinition> tools) {
        return tools.stream()
            .map(this::convertTool)
            .toList();
    }

    // Private conversion methods...
}
```

---

## Message conversion

### Role mapping

| aimon Role | OpenAI | Anthropic |
|------------|--------|-----------|
| `USER` | `user` | `user` |
| `ASSISTANT` | `assistant` | `assistant` |
| `TOOL` | `tool` | `user` (tool_result content) |

### Converting a user message

```java
private ProviderMessage convertUserMessage(Message message) {
    return ProviderMessage.builder()
        .role("user")
        .content(message.getContent())
        .build();
}
```

### Converting an assistant message (with tool invocations)

```java
private ProviderMessage convertAssistantMessage(Message message) {
    var builder = ProviderMessage.builder()
        .role("assistant")
        .content(message.getContent());

    // when there are tool invocations
    if (message.hasToolUses()) {
        List<ProviderToolCall> toolCalls = message.getToolUses().stream()
            .map(this::convertToolUse)
            .toList();
        builder.toolCalls(toolCalls);
    }

    return builder.build();
}
```

### Converting tool-result messages

The conversion differs from provider to provider:

```java
// the OpenAI style: each result becomes its own tool message
private List<ProviderMessage> convertToolResultsOpenAI(Message message) {
    return message.getToolUseResults().stream()
        .map(result -> ProviderMessage.builder()
            .role("tool")
            .toolCallId(result.getToolUseId())
            .content(result.getContent())
            .build())
        .toList();
}

// the Anthropic style: tool_result content inside a user message
private ProviderMessage convertToolResultsAnthropic(Message message) {
    List<ContentBlock> contents = message.getToolUseResults().stream()
        .map(result -> ContentBlock.toolResult(result.getToolUseId(), result.getContent()))
        .toList();

    return ProviderMessage.builder()
        .role("user")
        .content(contents)
        .build();
}
```

---

## Tool calling support

### ToolDefinition → the provider's tool type

```java
private ProviderTool convertTool(ToolDefinition tool) {
    return ProviderTool.builder()
        .type("function")
        .function(ProviderFunction.builder()
            .name(tool.getName())
            .description(tool.getDescription())
            .parameters(tool.getInputSchema())  // JSON Schema
            .build())
        .build();
}
```

### The provider's tool call → ToolUse

```java
private ToolUse convertToolCall(ProviderToolCall toolCall) {
    Map<String, Object> input = parseJsonToMap(toolCall.getArguments());

    return ToolUse.of(
        toolCall.getId(),       // the tool invocation id
        toolCall.getName(),     // the tool name
        input                   // the parameters
    );
}

private Map<String, Object> parseJsonToMap(String json) {
    try {
        return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
        throw new ToolConversionException("Failed to parse tool arguments: " + e.getMessage(), e);
    }
}
```

---

## Configuration classes

### LlmModel (per-request settings)

Every request can carry its own settings:

```java
LlmModel modelConfig = LlmModel.builder()
    .name("gpt-4-turbo")          // model name (optional)
    .temperature(0.5)             // temperature (optional)
    .maxTokens(8192)              // max tokens (optional)
    .topP(0.9)                    // Top-P (optional)
    .presencePenalty(0.1)         // presence penalty (optional)
    .frequencyPenalty(0.1)        // frequency penalty (optional)
    .build();

// merging: modelConfig wins over the base config
String model = modelConfig.getName().orElse(config.getModel());
double temp = modelConfig.getTemperature().orElse(config.getTemperature());
```

---

## Error handling

### LlmClientException

Every error is wrapped in `LlmClientException`:

```java
@Override
public LlmResponse sendMessage(...) {
    try {
        // the API call
        return callApi(...);

    } catch (ProviderRateLimitException e) {
        logger.warn("Rate limit exceeded: {}", e.getMessage());
        throw new LlmClientException("Rate limit exceeded. Please retry later.", e);

    } catch (ProviderAuthException e) {
        logger.error("Authentication failed: {}", e.getMessage());
        throw new LlmClientException("Authentication failed. Check your API key.", e);

    } catch (ProviderTimeoutException e) {
        logger.warn("Request timeout: {}", e.getMessage());
        throw new LlmClientException("Request timeout. Please retry.", e);

    } catch (Exception e) {
        logger.error("Unexpected error: {}", e.getMessage(), e);
        throw new LlmClientException("API call failed: " + e.getMessage(), e);
    }
}
```

### Custom exceptions

```java
public class MessageConversionException extends RuntimeException {
    public MessageConversionException(String message) {
        super(message);
    }

    public MessageConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class ToolConversionException extends RuntimeException {
    public ToolConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

---

## A full example

### The OpenAI implementation, for reference

Look at the implementation in the `aimon-llm-openai` module:

```java
public class OpenAILlmClient implements LlmClient {

    private final OpenAIConfig config;
    private final OpenAIClient client;
    private final OpenAIMessageConverter converter;

    public OpenAILlmClient(OpenAIConfig config) {
        this.config = Objects.requireNonNull(config, "Config cannot be null");
        this.client = createOpenAIClient(config);
        this.converter = new OpenAIMessageConverter();
    }

    @Override
    public LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                                   List<ToolDefinition> tools, LlmModel modelConfig) {
        Objects.requireNonNull(systemPrompt, "System prompt cannot be null");
        Objects.requireNonNull(messages, "Messages cannot be null");
        Objects.requireNonNull(tools, "Tools cannot be null");
        Objects.requireNonNull(modelConfig, "Model config cannot be null");

        try {
            // 1. build the messages (system prompt + conversation history)
            List<ChatCompletionMessageParam> chatMessages = buildChatMessages(systemPrompt, messages);

            // 2. build the request (merging the settings)
            ChatCompletionCreateParams.Builder requestBuilder = ChatCompletionCreateParams.builder()
                .model(modelConfig.getName().orElse(config.getModel()))
                .messages(chatMessages)
                .temperature(modelConfig.getTemperature().orElse(config.getTemperature()))
                .maxCompletionTokens((long) modelConfig.getMaxTokens().orElse(config.getMaxTokens()));

            // 3. add the tools
            if (!tools.isEmpty()) {
                List<ChatCompletionTool> openaiTools = converter.convertTools(tools);
                requestBuilder.tools(openaiTools);
            }

            // 4. call the API
            ChatCompletion result = client.chat().completions().create(requestBuilder.build());

            // 5. convert the response
            return convertResponse(result);

        } catch (Exception e) {
            logger.error("OpenAI API call failed: {}", e.getMessage(), e);
            throw new LlmClientException("OpenAI API call failed: " + e.getMessage(), e);
        }
    }

    private LlmResponse convertResponse(ChatCompletion result) {
        var choice = result.choices().get(0);
        var message = choice.message();

        String textContent = message.content().orElse("");
        List<ToolUse> toolUses = new ArrayList<>();

        // convert the tool invocations
        if (message.toolCalls().isPresent()) {
            for (var toolCall : message.toolCalls().get()) {
                if (toolCall.function().isPresent()) {
                    var functionCall = toolCall.function().get();
                    Map<String, Object> input = converter.parseJsonToMap(functionCall.function().arguments());
                    toolUses.add(ToolUse.of(functionCall.id(), functionCall.function().name(), input));
                }
            }
        }

        TokenUsage tokenUsage = extractTokenUsage(result);
        return LlmResponse.of(textContent, toolUses, tokenUsage);
    }

    @Override
    public String getProviderName() {
        return "OpenAI (" + config.getModel() + ")";
    }
}
```

---

## Checklist

Check these off when developing a new LLM provider:

### Mandatory

- [ ] Implements the `LlmClient` interface
- [ ] `sendMessage()` performs null checks
- [ ] Every error is wrapped in `LlmClientException`
- [ ] The implementation is thread-safe
- [ ] Tool calling is supported

### Message conversion

- [ ] USER, ASSISTANT and TOOL messages are converted
- [ ] ASSISTANT messages carrying tool invocations are converted
- [ ] Tool-result messages are converted (in the provider's own shape)

### Configuration

- [ ] A configuration class exists (builder pattern)
- [ ] **Configuration validity ends in the constructor** — reject a blank API key, and leave nothing to be asked about later
- [ ] `LlmModel` per-request settings are supported
- [ ] The base settings and the per-request settings are merged

### Testing

- [ ] Unit tests are written
- [ ] Integration tests are written (with the API mocked)
- [ ] Error cases are tested

---

## Related documents

- [OpenAILlmClient.java](../../../modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/OpenAILlmClient.java) — the reference implementation
- [OpenAIMessageConverter.java](../../../modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/OpenAIMessageConverter.java) — the message converter
- [LlmClient.java](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/LlmClient.java) — the interface definition
- [Message.java](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/Message.java) — the message class
