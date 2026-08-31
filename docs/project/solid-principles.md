# SOLID Principles

AIMON 프로젝트에서 따르는 객체지향 설계 원칙입니다.

## 개요

SOLID는 객체지향 프로그래밍에서 유지보수성과 확장성을 높이는 5가지 설계 원칙의 약어입니다.

| 원칙 | 이름 | 핵심 개념 |
|------|------|----------|
| **S** | Single Responsibility | 하나의 클래스는 하나의 책임만 |
| **O** | Open/Closed | 확장에 열림, 수정에 닫힘 |
| **L** | Liskov Substitution | 하위 타입은 상위 타입을 대체 가능 |
| **I** | Interface Segregation | 인터페이스를 작게 분리 |
| **D** | Dependency Inversion | 추상화에 의존 |

---

## S - Single Responsibility Principle (단일 책임 원칙)

> 클래스는 하나의 책임만 가져야 하며, 변경의 이유는 단 하나여야 한다.

### 위반 사례

```java
// Bad: 여러 책임이 섞여 있음
public class Tool {
    public ToolResult execute(ToolInput input) { /* 실행 로직 */ }
    public String toJson() { /* JSON 직렬화 */ }
    public void saveToFile(String path) { /* 파일 저장 */ }
    public void logExecution() { /* 로깅 */ }
}
```

### 올바른 설계

```java
// Good: 각 클래스가 단일 책임
public interface Tool {
    ToolDefinition getDefinition();
    ToolResult execute(ToolInput input, ToolContext context);
}

public class ToolSerializer {
    public String toJson(Tool tool) { /* JSON 직렬화 */ }
}

public class ToolExecutionLogger {
    public void log(Tool tool, ToolResult result) { /* 로깅 */ }
}
```

### AIMON 적용 예시

- `Tool` - 도구 실행 책임만
- `ToolRegistry` - 도구 등록/조회 책임만
- `ToolExecutionManager` - 도구 실행 관리 책임만

---

## O - Open/Closed Principle (개방-폐쇄 원칙)

> 소프트웨어 엔티티는 확장에는 열려 있고, 수정에는 닫혀 있어야 한다.

### 위반 사례

```java
// Bad: 새 타입 추가 시 기존 코드 수정 필요
public class LlmClient {
    public LlmResponse send(String provider, String prompt) {
        if (provider.equals("openai")) {
            // OpenAI 로직
        } else if (provider.equals("anthropic")) {
            // Anthropic 로직
        }
        // 새 provider 추가 시 여기 수정 필요
    }
}
```

### 올바른 설계

```java
// Good: 인터페이스로 확장 가능
public interface LlmClient {
    LlmResponse sendMessage(String systemPrompt, List<Message> messages,
                           List<ToolDefinition> tools, LlmModel modelConfig);
}

public class OpenAiLlmClient implements LlmClient {
    @Override
    public LlmResponse sendMessage(...) { /* OpenAI 구현 */ }
}

public class AnthropicLlmClient implements LlmClient {
    @Override
    public LlmResponse sendMessage(...) { /* Anthropic 구현 */ }
}
```

### AIMON 적용 예시

- `LlmClient` 인터페이스 - 새 LLM 프로바이더 추가 시 기존 코드 수정 없음
- `Tool` 인터페이스 - 새 도구 추가 시 기존 코드 수정 없음
- `VirtualFileSystem` 인터페이스 - 새 스토리지 백엔드 추가 용이

---

## L - Liskov Substitution Principle (리스코프 치환 원칙)

> 하위 타입은 상위 타입을 대체할 수 있어야 한다.

### 위반 사례

```java
// Bad: 하위 클래스가 상위 클래스의 계약 위반
public class ReadOnlyTool extends AbstractTool {
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        throw new UnsupportedOperationException("실행 불가");
    }
}
```

### 올바른 설계

```java
// Good: 모든 구현체가 인터페이스 계약 준수
public abstract class AbstractTool implements Tool {
    @Override
    public final ToolResult execute(ToolInput input, ToolContext context) {
        try {
            return doExecute(input, context);
        } catch (Exception e) {
            return ToolResult.error("Execution failed: " + e.getMessage());
        }
    }

    protected abstract ToolResult doExecute(ToolInput input, ToolContext context);
}

public class ReadTool extends AbstractTool {
    @Override
    protected ToolResult doExecute(ToolInput input, ToolContext context) {
        // 항상 유효한 ToolResult 반환
        return ToolResult.success(content);
    }
}
```

### AIMON 적용 예시

- 모든 `Tool` 구현체는 `ToolResult`를 반환 (예외 던지지 않음)
- 모든 `LlmClient` 구현체는 동일한 `LlmResponse` 계약 준수
- `AbstractTool` 템플릿 메서드로 일관된 동작 보장

---

## I - Interface Segregation Principle (인터페이스 분리 원칙)

> 클라이언트는 사용하지 않는 인터페이스에 의존하지 않아야 한다.

### 위반 사례

```java
// Bad: 거대한 인터페이스
public interface Agent {
    void execute(String input);
    void pause();
    void resume();
    void cancel();
    void schedule(String cron);
    void setHooks(List<Hook> hooks);
    void setTools(List<Tool> tools);
    void setSkills(List<Skill> skills);
    // ... 더 많은 메서드
}
```

### 올바른 설계

```java
// Good: 작고 집중된 인터페이스
public interface AgentExecutor<CTX, REQ, RES> {
    RES execute(CTX executionContext, REQ executionRequest);
}

public interface Schedulable {
    void schedule(String cronExpression);
    void cancel();
}

public interface Configurable {
    void configure(AgentConfiguration config);
}
```

### AIMON 적용 예시

- `Tool` - 실행 관련 메서드만
- `ToolRegistry` - 등록/조회 메서드만
- `AgentExecutor` - 실행 메서드만

---

## D - Dependency Inversion Principle (의존성 역전 원칙)

> 고수준 모듈은 저수준 모듈에 의존해서는 안 된다. 둘 다 추상화에 의존해야 한다.

### 위반 사례

```java
// Bad: 구체 클래스에 직접 의존
public class OrcaAgentExecutor {
    private final OpenAiLlmClient llmClient = new OpenAiLlmClient();

    public AgentResponse execute(AgentRequest request) {
        return llmClient.sendMessage(...);
    }
}
```

### 올바른 설계

```java
// Good: 추상화에 의존, 생성자 주입
public class OrcaAgentExecutor implements AgentExecutor<AgentContext, AgentRequest, AgentResponse> {
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;

    public OrcaAgentExecutor(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = Objects.requireNonNull(llmClient);
        this.toolRegistry = Objects.requireNonNull(toolRegistry);
    }

    @Override
    public AgentResponse execute(AgentContext context, AgentRequest request) {
        // 추상화된 인터페이스 사용
        LlmResponse response = llmClient.sendMessage(...);
        Tool tool = toolRegistry.findByName(toolName);
        // ...
    }
}
```

### AIMON 적용 예시

```
┌─────────────────────────────────────────────────────┐
│                   aimon-core                        │
│  ┌─────────────┐    ┌─────────────┐                │
│  │ LlmClient   │    │ VirtualFS   │  (추상화)      │
│  │ (interface) │    │ (interface) │                │
│  └──────▲──────┘    └──────▲──────┘                │
└─────────┼──────────────────┼────────────────────────┘
          │                  │
┌─────────┼──────────────────┼────────────────────────┐
│         │                  │                        │
│  ┌──────┴──────┐    ┌──────┴──────┐                │
│  │ OpenAiLlm   │    │ GridFsVFS   │  (구현체)      │
│  │ Client      │    │             │                │
│  └─────────────┘    └─────────────┘                │
│   aimon-llm-openai   aimon-filesystem-gridfs       │
└─────────────────────────────────────────────────────┘
```

---

## 체크리스트

새 코드 작성 시 확인할 SOLID 체크리스트:

### Single Responsibility
- [ ] 이 클래스의 책임을 한 문장으로 설명할 수 있는가?
- [ ] 이 클래스가 변경되어야 하는 이유가 하나인가?

### Open/Closed
- [ ] 새 기능 추가 시 기존 코드 수정 없이 확장 가능한가?
- [ ] 인터페이스나 추상 클래스를 통해 확장점을 제공하는가?

### Liskov Substitution
- [ ] 모든 하위 타입이 상위 타입의 계약을 준수하는가?
- [ ] 예외를 던지는 대신 유효한 결과를 반환하는가?

### Interface Segregation
- [ ] 인터페이스가 단일 목적에 집중하는가?
- [ ] 클라이언트가 사용하지 않는 메서드에 의존하지 않는가?

### Dependency Inversion
- [ ] 구체 클래스 대신 인터페이스에 의존하는가?
- [ ] 의존성이 생성자를 통해 주입되는가?

---

## 참고 자료

- Robert C. Martin, "Clean Architecture" (2017)
- Robert C. Martin, "Agile Software Development, Principles, Patterns, and Practices" (2002)
- [AIMON Tool 개발 가이드](../features/tool/tool-development-guide.md)
- [AIMON LLM Provider 개발 가이드](../features/llm/llm-provider-development-guide.md)
