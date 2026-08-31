# Tool Development Guide

> LLM Agent Tool 개발을 위한 완전한 가이드

이 문서는 aimon-core 프레임워크에서 Tool을 개발할 때 필요한 모든 정보를 제공합니다.

## 목차

1. [개요](#개요)
2. [핵심 클래스](#핵심-클래스)
3. [Tool 생성 패턴](#tool-생성-패턴)
4. [ToolInput 사용법](#toolinput-사용법)
5. [ToolResult 반환 패턴](#toolresult-반환-패턴)
6. [ToolContext 활용](#toolcontext-활용)
7. [JSON Schema 정의](#json-schema-정의)
8. [에러 처리](#에러-처리)
9. [권한 시스템](#권한-시스템)
10. [동시 실행 안전성 (ConcurrencyBehavior)](#동시-실행-안전성-concurrencybehavior)
11. [전체 예제](#전체-예제)
12. [체크리스트](#체크리스트)

---

## 개요

Tool은 LLM Agent가 외부 시스템과 상호작용할 수 있게 해주는 핵심 컴포넌트입니다.

### 핵심 원칙

| 원칙 | 설명 |
|------|------|
| **Single Responsibility** | 하나의 Tool은 하나의 명확한 작업만 수행 |
| **Fail-Safe Design** | `execute()` 메서드는 절대 예외를 던지지 않음 |
| **Immutability** | ToolInput, ToolResult, ToolContext는 불변 객체 |
| **Type Safety** | ToolInput의 타입 안전 접근자 활용 |
| **Stateless** | Tool은 실행 간 상태를 유지하지 않음 |

### 패키지 구조

```
at.aimon.core.agent.tool/
├── Tool.java                 # Tool 인터페이스
├── AbstractTool.java         # 기본 구현 클래스
├── ToolInput.java            # 입력 파라미터 래퍼
├── ToolResult.java           # 실행 결과
├── ToolContext.java          # 런타임 컨텍스트
├── ToolRegistry.java         # Tool 레지스트리
└── exception/                # 예외 클래스들
```

---

## 핵심 클래스

### Tool 인터페이스

```java
public interface Tool {
    /**
     * Tool의 정의(이름, 설명, 스키마)를 반환합니다.
     */
    ToolDefinition getDefinition();

    /**
     * Tool을 실행합니다.
     *
     * @param input   입력 파라미터
     * @param context 런타임 컨텍스트
     * @return 실행 결과 (성공 또는 에러)
     */
    ToolResult execute(ToolInput input, ToolContext context);
}
```

### AbstractTool 클래스

모든 Tool의 기본 클래스입니다. 두 가지 생성자를 제공합니다:

```java
// 정적 정의 (메타데이터가 변하지 않는 경우)
public AbstractTool(String name, String description, Map<String, Object> inputSchema)

// 동적 정의 (런타임에 설명이 변할 수 있는 경우)
public AbstractTool(ToolDefinitionProvider definitionProvider)
```

---

## Tool 생성 패턴

### 어느 베이스 클래스를 고를 것인가

베이스 클래스는 둘이다. **기본은 `AbstractTool`** 이고, `GenericTool<I, O>`
(`at.aimon.core.agent.tool.generic`)는 opt-in 이다 — `AbstractTool` 을 대체하지 않고 그 옆에 선다.

| 상황 | 고를 것 |
|------|---------|
| 파라미터가 서넛 이하이고 대부분 필수 | `AbstractTool` — 스키마를 손으로 쓰는 비용이 타입을 하나 더 만드는 비용보다 싸다 |
| 파라미터가 **5개 이상**이거나 선택적 파라미터가 많다 | `GenericTool<I, O>` — 스키마와 파라미터 추출이 **하나의 선언**(입력 `record`)에서 나오므로 둘이 어긋날 수 없다 |
| 입력 키 집합이 런타임에 정해진다 (MCP 위임 등) | `AbstractTool` — 바인딩할 컴파일 타임 타입이 없다 |

`GenericTool` 을 고르면 세 가지가 따라온다.

- **스키마가 입력 `record` 에서 파생된다** — `additionalProperties: false` 포함, 중첩 `record` 까지.
  손으로 쓴 스키마와 손으로 쓴 파라미터 추출이 서로 다른 말을 하는 사고가 구조적으로 불가능해진다
- **바인딩이 위반을 한 번에 모두 보고한다** — 그것도 실행기의 스키마 게이트와 같은 문장으로
- **`execute()` 가 `final` 이다** — "예외를 던지지 않는다"는 도구 계약이 하위 클래스의 성실함이 아니라
  타입으로 강제된다. 구현할 것은 `doExecute(I, ToolContext)` 와 `render(O)` 둘뿐이다

입력 타입은 `record` 로 쓴다. 이것이 프로젝트의 `class` 선호 규약에 대한 **유일한 예외**이며 범위는
`GenericTool` 의 입력 DTO 하나뿐이다 — 도메인 타입·값 객체·설정 객체는 포함되지 않는다
(`.claude/rules/code-style.md`).

```java
public record EchoInput(
        @ToolParam(required = true, description = "The message to repeat") String message,
        @ToolParam(description = "How many times (default: 1)") Integer times) {
}

public class EchoTool extends GenericTool<EchoInput, String> {

    public EchoTool() {
        super("Echo", "Repeats a message back", EchoInput.class);
    }

    @Override
    protected String doExecute(EchoInput input, ToolContext context) {
        return input.message().repeat(input.times() == null ? 1 : input.times());
    }

    @Override
    protected ToolResult render(String output) {
        return ToolResult.success(output);
    }
}
```

와이어 이름은 **선언하는 것이지 변환되는 것이 아니다** — 파라미터 이름이 snake_case 라면
`@ToolParam(name = "file_path")` 로 적는다. 생성기는 camelCase→snake_case 자동 변환을 하지 않는다
(`Grep` 의 `-i`·`-A` 처럼 애초에 식별자로 쓸 수 없는 이름도 있다).

아래 절부터는 `AbstractTool` 경로를 설명한다.

### 기본 구조

```java
package at.aimon.core.tools.example;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Tool의 목적과 기능에 대한 설명.
 *
 * <p>
 * 이 Tool은 다음 기능을 제공합니다:
 * <ul>
 * <li>기능 1
 * <li>기능 2
 * </ul>
 *
 * <p>
 * 사용 예제:
 * <pre>
 * {@code
 * Tool tool = new ExampleTool(dependency);
 * ToolInput input = ToolInput.of(Map.of("param1", "value1"));
 * ToolResult result = tool.execute(input, ToolContext.empty());
 * }
 * </pre>
 */
public class ExampleTool extends AbstractTool {

    public static final String TOOL_NAME = "Example";

    private static final Logger log = LoggerFactory.getLogger(ExampleTool.class);

    // 의존성 (있는 경우)
    private final SomeDependency dependency;

    /**
     * ExampleTool을 생성합니다.
     *
     * @param dependency 필요한 의존성 (null 불가)
     * @throws NullPointerException dependency가 null인 경우
     */
    public ExampleTool(SomeDependency dependency) {
        super(TOOL_NAME,
              "Tool에 대한 간결하고 명확한 설명. " +
              "LLM이 이 Tool을 언제 사용해야 하는지 이해할 수 있도록 작성.",
              createInputSchema());
        this.dependency = Objects.requireNonNull(dependency, "Dependency cannot be null");
    }

    /**
     * 입력 스키마를 생성합니다.
     */
    private static Map<String, Object> createInputSchema() {
        return Map.of(
            "type", "object",
            "additionalProperties", false,
            "properties", Map.of(
                "required_param", Map.of(
                    "type", "string",
                    "description", "필수 파라미터에 대한 설명"
                ),
                "optional_param", Map.of(
                    "type", "integer",
                    "description", "선택적 파라미터에 대한 설명 (기본값: 10)"
                )
            ),
            "required", List.of("required_param")
        );
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        // 1. null 검사
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // 2. 파라미터 추출
            final String requiredParam = input.getRequiredString("required_param");
            final int optionalParam = input.getInteger("optional_param", 10);

            log.debug("Executing with: required={}, optional={}", requiredParam, optionalParam);

            // 3. 비즈니스 로직 수행
            String result = performOperation(requiredParam, optionalParam);

            // 4. 성공 결과 반환
            log.debug("Successfully completed operation");
            return ToolResult.success(result);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (SpecificException e) {
            log.warn("Operation failed: {}", e.getMessage());
            return ToolResult.error("Operation failed: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private String performOperation(String param1, int param2) throws SpecificException {
        // 실제 비즈니스 로직
        return "Result";
    }
}
```

---

## ToolInput 사용법

ToolInput은 Tool 입력 파라미터의 **불변** 래퍼입니다.

### 파라미터 접근 메서드

#### 필수 파라미터 (Required)

파라미터가 없거나 타입이 맞지 않으면 `IllegalArgumentException`을 던집니다.

```java
String value = input.getRequiredString("param_name");
int number = input.getRequiredInteger("count");
boolean flag = input.getRequiredBoolean("enabled");
```

#### 선택적 파라미터 (Optional with Default)

파라미터가 없으면 기본값을 반환합니다.

```java
String value = input.getString("param_name", "default");
int number = input.getInteger("count", 100);
boolean flag = input.getBoolean("enabled", false);
long bigNumber = input.getLong("size", 1000L);
```

#### Nullable 파라미터

파라미터가 없으면 null을 반환합니다.

```java
String value = input.getStringOrNull("param_name");
Integer number = input.getIntegerOrNull("count");
Boolean flag = input.getBooleanOrNull("enabled");
Long bigNumber = input.getLongOrNull("size");
```

### 유효성 검사 메서드

```java
// 파라미터 존재 여부 확인
if (input.has("optional_param")) {
    // 처리
}

// 모든 키 조회
Set<String> keys = input.keys();

// 크기 확인
int size = input.size();
boolean empty = input.isEmpty();
```

### ToolInput 생성 (테스트용)

```java
// 빈 입력
ToolInput empty = ToolInput.of();

// Map으로 생성
ToolInput fromMap = ToolInput.of(Map.of("key1", "value1", "key2", 42));

// 편의 메서드 (최대 10개 파라미터)
ToolInput simple = ToolInput.of("file_path", "/path/to/file");
ToolInput multi = ToolInput.of("path", "/dir", "recursive", true);
```

---

## ToolResult 반환 패턴

ToolResult는 Tool 실행 결과의 **불변** 값 객체입니다.

### 성공 결과

```java
// 단순 성공
return ToolResult.success("Operation completed successfully");

// 데이터 포함 성공
return ToolResult.success(formattedOutput);
```

### 에러 결과

```java
// 에러 메시지만
return ToolResult.error("File not found: " + path);

// 에러 메시지 + 예외 (디버깅용)
return ToolResult.error("Failed to read file: " + e.getMessage(), e);

// 예외만 (메시지 또는 클래스명 사용)
return ToolResult.error(exception);
```

### 결과 조회

```java
String content = result.getContent();         // 메시지/출력
boolean isError = result.isError();           // 에러 여부
boolean isSuccess = result.isSuccess();       // 성공 여부
Optional<Exception> ex = result.getException(); // 예외 (있는 경우)
```

---

## ToolContext 활용

ToolContext는 런타임 컨텍스트 정보를 담는 **불변** 컨테이너입니다.

### 컨텍스트 접근

```java
// Optional 반환
Optional<Object> value = context.get("key");

// 타입 안전 접근
Optional<VirtualFileSystem> vfs = context.get("fileSystem", VirtualFileSystem.class);

// 존재 여부 확인
if (context.containsKey("environment")) {
    // 처리
}

// 전체 컨텍스트 (읽기 전용)
Map<String, Object> all = context.getContext();
```

### 일반적인 컨텍스트 키

| 키 | 타입 | 설명 |
|----|------|------|
| `fileSystem` | `VirtualFileSystem` | 파일 시스템 인스턴스 |
| `environment` | `Environment` | 환경 설정 |
| `executorType` | `InvokerType` | 실행자 유형 (MAIN_AGENT, SUBAGENT 등) |
| `read_tool.read_files` | `Set<String>` | 읽은 파일 목록 (ReadTool에서 설정) |

### 컨텍스트 생성 (테스트/초기화용)

```java
// 빈 컨텍스트
ToolContext empty = ToolContext.empty();

// Builder 패턴
ToolContext context = ToolContext.builder()
    .put("fileSystem", vfs)
    .put("environment", env)
    .put("executorType", InvokerType.MAIN_AGENT)
    .build();
```

---

## JSON Schema 정의

Tool의 입력 스키마는 [JSON Schema](https://json-schema.org/) 형식을 따릅니다.

### 기본 구조

```java
private static Map<String, Object> createInputSchema() {
    return Map.of(
        "type", "object",
        "properties", Map.of(
            // 파라미터 정의
        ),
        "required", List.of(/* 필수 파라미터 이름들 */)
    );
}
```

### 지원하는 타입

#### String

```java
"param_name", Map.of(
    "type", "string",
    "description", "파라미터 설명"
)
```

#### Number (소수 허용)

```java
"threshold", Map.of(
    "type", "number",
    "description", "임계값 (기본값: 0.5)"
)
```

#### Integer (정수만)

```java
"count", Map.of(
    "type", "integer",
    "description", "항목 수 (기본값: 10)"
)
```

`number` 는 `1.5` 도 통과시키고, `integer` 는 **소수부가 있으면 거부**한다. 라인 번호·개수·타임아웃처럼
정수만 의미가 있는 파라미터는 `integer` 로 선언한다.

단 `3.0` 은 `integer` 를 통과한다 — JSON `3` 은 `Integer` 로, `3.0` 은 `Double` 로 파싱되지만 둘 다 같은
정수를 뜻하고, 모델이 그 차이를 의도해서 만들어 낼 수 있는 것이 아니기 때문이다. 거부되는 것은 진짜
소수부(`3.5`)다.

#### Boolean

```java
"enabled", Map.of(
    "type", "boolean",
    "description", "기능 활성화 여부"
)
```

#### Enum (문자열 선택)

```java
"output_mode", Map.of(
    "type", "string",
    "description", "출력 모드: content, files_with_matches, count",
    "enum", List.of("content", "files_with_matches", "count")
)
```

### `additionalProperties: false` — 내장 도구는 반드시 넣는다

선언하지 않은 파라미터를 모델이 보냈을 때 JSON Schema 의 기본값은 **허용**이다. 그 기본값을 그대로 두면
오타(`file_paht`)가 조용히 무시되고, 도구는 필수 파라미터가 빠졌다는 엉뚱한 에러를 낸다. 그래서 **우리가
소유한 도구의 스키마에는 이 키를 명시적으로 넣는다.**

```java
private static Map<String, Object> createInputSchema() {
    return Map.of(
        "type", "object",
        "additionalProperties", false,   // ← 최상위에 넣는다
        "properties", Map.of(/* ... */),
        "required", List.of("file_path")
    );
}
```

- **내장 도구는 예외 없이 전부 선언한다.** `BuiltInToolSchemaArchitectureTest` 가 빌드에서 확인하며
  제외 목록은 없다 — 단 **검사 범위는 `at.aimon.core.tools` 패키지**다. 그 밖에 사는 내장 도구
  (`at.aimon.core.memory.deriver.tool`, `at.aimon.sandbox.tool`, Playwright·GraalJS 도구)도 규칙은
  똑같이 지키지만 이 테스트가 지켜 주지는 않는다. 범위를 패키지 관례로 잡은 것은 의도이며 이유는 테스트
  javadoc 에 있다 — `Tool` 하위 타입을 전부 스캔하면 `at.aimon.core.mcp.McpTool` 이 걸리는데, 그것이
  광고하는 스키마는 우리 것이 아니라 서버의 것이다. 검사는 **최상위 맵만** 본다 — 배열 item 스키마에만
  붙이는 것은 모델의 호출이 실제로 검사되는 자리를 비워 두는 것이므로 통과하지 못한다.
- **MCP 등 제3자 스키마는 건드리지 않는다.** 검증기는 "누가 만든 도구인가"를 묻지 않고 **키가 있으면
  강제, 없으면 관대**로만 판단한다. 엄격함을 켜는 것은 스키마 소유자의 선언이다.
- `GenericTool` 은 이 키를 **자동으로** 넣는다 (중첩 `record` 까지). 손으로 넣을 일이 없다.

키가 있을 때 오타는 이렇게 되돌아온다 —
`Unknown parameter 'file_paht'. Did you mean 'file_path'? The tool was not executed.`

### 전체 스키마 예제

```java
private static Map<String, Object> createInputSchema() {
    return Map.of(
        "type", "object",
        "additionalProperties", false,
        "properties", Map.of(
            "file_path", Map.of(
                "type", "string",
                "description", "읽을 파일의 절대 경로"
            ),
            "offset", Map.of(
                "type", "integer",
                "description", "시작 라인 번호 (1-based). 큰 파일의 경우에만 제공"
            ),
            "limit", Map.of(
                "type", "integer",
                "description", "읽을 라인 수. 큰 파일의 경우에만 제공"
            ),
            "encoding", Map.of(
                "type", "string",
                "description", "파일 인코딩 (기본값: UTF-8)",
                "enum", List.of("UTF-8", "ISO-8859-1", "EUC-KR")
            )
        ),
        "required", List.of("file_path")
    );
}
```

> 위 예제는 새 스키마를 쓰는 방법이다. 실제 `ReadTool` 의 `offset`/`limit` 은 아직 `"number"` 로 선언되어
> 있다 — 검증기 도입은 기존 선언을 일괄 수정하는 작업이 아니었기 때문이다. 옮기는 것은 계약을 좁히는
> 별개 변경이다.

---

## 에러 처리

### 핵심 원칙

> **절대 `execute()` 메서드에서 예외를 던지지 마세요.**
> 모든 에러는 `ToolResult.error()`로 반환해야 합니다.

### 스키마 검증은 `execute()` 앞에 선다

`DefaultToolExecutor` 는 도구를 호출하기 **전에** 입력을 도구가 선언한 스키마와 대조한다
(`at.aimon.core.agent.tool.schema`). 즉 아래 네 가지는 `execute()` 안에서 다시 확인하지 않아도 된다.

| 검사 | 잡는 것 |
|------|---------|
| `required` | 선언된 필수 파라미터 누락 (JSON `null` 도 **누락**으로 읽는다 — `ToolInput` 이 null 값을 버린다) |
| `type` | 타입 불일치 (`integer` 에 소수 등) |
| `enum` | 선언된 허용값 밖의 값 |
| 미선언 이름 | 오타 — 단, 스키마에 `additionalProperties: false` 가 있을 때만 |

**게이트가 하지 않는 것도 알아 두어야 한다.**

- **범위는 도구가 본다.** `minimum`/`maximum`/`minLength`/`minItems`/`default` 는 선언되어 있어도 무시된다.
  도구마다 처리 방식이 다르기 때문이다 — `BashTool` 은 과대한 timeout 을 **거부하지 않고 clamp** 하므로,
  게이트가 `maximum` 을 강제하면 지금 조용히 성공하는 호출이 에러로 바뀐다. 경계는 **모양은 게이트,
  범위는 도구**다. 즉 `offset < 1` 같은 검사는 여전히 `execute()` 안에 남는다
- **중첩은 1단계까지만 본다.** `object`/`array` 프로퍼티는 그 타입인지만 보고 내부는 보지 않는다.
  중첩 계약이 중요한 도구는 `GenericTool` 로 바인딩하는 편이 낫다
- **모르는 것은 통과시킨다.** `$ref`/`oneOf`/`anyOf` 를 쓰거나 타입 이름을 알 수 없는 프로퍼티는 건너뛴다
  (MCP 스키마를 거부하지 않기 위한 의도된 관대함이다)

반응 방식은 `SchemaValidationMode` 가 정한다 — `OFF`(검증 안 함) / **`WARN`(기본값: 위반을 로그로
남기고 도구는 그대로 실행)** / `ENFORCE`(실행하지 않고 위반 문장을 모델에게 에러로 돌려줌).
관측 먼저, 거부는 그다음이라는 순서다.

`GenericTool` 의 파라미터 바인딩도 같은 문장을 쓴다. 두 계층이 서로 다른 시점에 잡지만, 모델이 같은
불평을 두 가지 말투로 배울 이유는 없기 때문이다.

> **동작 변경 하나.** `ToolInput` 의 `"10"` → `10` 문자열→숫자 강제 변환은 `ENFORCE` 에서 도달되지
> 않는다 — 검증이 먼저 걸러 낸다. 계약을 조이는 의도된 변경이며, 그래서 기본값이 `WARN` 이다.

### 권장 패턴

```java
@Override
public ToolResult execute(ToolInput input, ToolContext context) {
    Objects.requireNonNull(input, "Input cannot be null");
    Objects.requireNonNull(context, "Context cannot be null");

    try {
        // 파라미터 추출
        final String path = input.getRequiredString("file_path");

        // 파라미터 검증
        if (path.isEmpty()) {
            return ToolResult.error("file_path cannot be empty");
        }

        // 비즈니스 로직
        String result = performOperation(path);
        return ToolResult.success(result);

    } catch (IllegalArgumentException e) {
        // 파라미터 관련 에러
        log.warn("Invalid parameter: {}", e.getMessage());
        return ToolResult.error("Invalid parameter: " + e.getMessage());

    } catch (FileNotFoundException e) {
        // 예상되는 에러 (경고 레벨)
        log.warn("File not found: {}", e.getMessage());
        return ToolResult.error("File not found: " + e.getMessage());

    } catch (SecurityException e) {
        // 보안 관련 에러
        log.warn("Access denied: {}", e.getMessage());
        return ToolResult.error("Access denied: " + e.getMessage());

    } catch (IOException e) {
        // I/O 에러 (에러 레벨 + 스택트레이스)
        log.error("I/O error: {}", e.getMessage(), e);
        return ToolResult.error("I/O error: " + e.getMessage());

    } catch (Exception e) {
        // 예상치 못한 에러 (에러 레벨 + 스택트레이스)
        log.error("Unexpected error: {}", e.getMessage(), e);
        return ToolResult.error("Unexpected error: " + e.getMessage());
    }
}
```

### 로깅 수준 가이드라인

| 수준 | 사용 시점 |
|------|----------|
| `DEBUG` | 정상 작업 흐름, 파라미터 값 |
| `WARN` | 예상되는 에러, 사용자 입력 오류 |
| `ERROR` | 예상치 못한 에러, 시스템 오류 (스택트레이스 포함) |

---

## 권한 시스템

허용 목록의 항목은 `"이름"` 또는 `"이름(패턴)"` 이다. **이름은 그 도구를 실행해도 되는지**를, **패턴은 그
도구의 어떤 호출을 실행해도 되는지**를 정한다. 패턴을 무엇과 비교해야 하는지는 도구만 알 수 있으므로
(`Bash` 는 `command`, `Read` 는 `file_path`), 도구가 그 값을 `PermissionSubject` 로 내놓는다.

### ToolPermissionSubjectAware — 보통은 이쪽

판정 대상이 입력 필드 하나로 정해지는 도구는 이 인터페이스만 구현하면 된다. 매처 선택과 패턴 대조는
프레임워크가 한다 — `CustomToolPermissionRule` 은 필요 없다.

```java
public class BashTool extends AbstractTool implements ToolPermissionSubjectAware {

    @Override
    public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
        // 원시 값으로 읽는다: getStringOrNull 은 타입이 다르면 던지는데, 이 코드는 execute() 앞에서
        // 돌기 때문에 그 예외를 받아 줄 곳이 없다. 문자열이 아닌 command 는 판정 불가 = 없는 것과 같다.
        if (!(input.get("command") instanceof String command) || command.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(PermissionSubject.command(command));
    }
}
```

**빈 값은 기권이 아니라 "판정 불가"** 다. 그 도구에 패턴이 설정되어 있으면 검증기는 그 호출을 **거부**한다.
추측해서 통과시키는 쪽(상대 경로를 프로세스 CWD 로 푸는 식)은 JVM 이 어디서 떴는지에 따라 결과가 달라져
패턴을 쓴 사람이 예측할 수 없다.

### Kind — 값의 종류가 매처를 고른다

| Kind | 판정 대상 | 매처 | 예 |
|------|----------|------|-----|
| `COMMAND` | 셸 명령줄 | `ToolPattern` — 끝의 `:*` 만 와일드카드, 나머지는 완전 일치 | `Bash(git:*)` |
| `PATH` | 파일 경로 | `PathPattern` — 글로브(`**`, `*`) | `Read(/tmp/**)` |

종류는 스펙 문자열에서 되살릴 수 없다. `AllowedTool` 파서는 괄호만 보고 `:` 를 해석하지 않으므로
`Read(/tmp/**)` 와 `Bash(git:*)` 는 파서 눈에 구분되지 않는다 — 그래서 종류를 **도구가** 함께 실어 보낸다.

매처가 둘인 이유는 하나로 겸할 수 없기 때문이다. `ToolPattern` 은 후보에 셸 메타문자
(`;` `|` `&` `` ` `` `$` `>` `<` `(` `)`)가 있으면 거부하는데, 이는 `bash -c` 로 향하는 문자열에는 맞는
방어지만 경로에 적용하면 `report(1).csv` 같은 평범한 파일이 영영 닿지 않는다.

`PATH` 주체는 **절대 경로 + 렉시컬 정규화** 된 값이어야 한다. 파일 도구는 상대 경로를 `Environment` 의
작업 디렉토리로 풀고 `..` 을 접은 뒤 내놓으므로, `/tmp/../etc/passwd` 는 `Read(/tmp/**)` 를 통과하지
못한다. 다만 심볼릭 링크는 풀지 않는다 — 권한 패턴은 에이전트가 **무엇을 요청할 수 있는지**를 좁히는
것이고, 격리는 샌드박스의 일이다.

### CustomToolPermissionAware — 값 하나로 표현되지 않을 때

여러 입력의 조합이나 외부 정책 조회로 판정해야 하는 도구는 규칙을 직접 구현한다. 현재 유일한 사례는
`Browser` 이며, `action:url` 이라는 자기만의 문법으로 판정한다. 그 `:` 는 와일드카드 표시가 아니라 두 값을
잇는 구분자이고 한 도구만의 표기법이므로, 세 번째 `Kind` 로 승격시키지 않았다.

```java
public class BrowserTool extends GenericTool<BrowserInput, String> implements CustomToolPermissionAware {

    private final CustomToolPermissionRule permissionRule = new BrowserToolPermissionRule();

    @Override
    public CustomToolPermissionRule getCustomPermissionRule() {
        return permissionRule;
    }

    // ... 나머지 구현
}
```

둘 다 구현한 도구는 **주체를 먼저** 본다.

### AllowedTool 형식

```
단순 허용:       "Read"
COMMAND 패턴:    "Bash(git:*)"
PATH 패턴:       "Read(/tmp/**)"
구체적 허용:     "Bash(npm install)"
```

### 패턴 예제

| 패턴 | Kind | 설명 |
|------|------|------|
| `Bash(git:*)` | COMMAND | 모든 git 명령 허용 |
| `Bash(./gradlew:*)` | COMMAND | 모든 Gradle 명령 허용 |
| `Bash(npm install)` | COMMAND | 정확히 `npm install`만 허용 |
| `Read(/tmp/**)` | PATH | `/tmp` 아래 모든 깊이의 파일 |
| `Read(/tmp/*.log)` | PATH | `/tmp` **바로 아래**의 `.log` 만 — `*` 는 `/` 를 넘지 않는다 |
| `Write(/etc/passwd)` | PATH | 그 파일 하나, 정확히 |
| `Read` | — | Read Tool 무제한 허용 |

주의 — **이름만 있는 항목은 같은 이름의 패턴 항목과 섞이면 무제한 허용이 아니다.** `"Read"` 와
`"Read(/tmp/**)"` 를 함께 등록하면 `/tmp` 밖의 읽기는 거부된다. 무제한이 되는 것은 그 이름으로 등록된
항목 중 **패턴을 가진 것이 하나도 없을 때**뿐이다.

그리고 **해석할 수 없는 패턴은 거부**다. 패턴이 설정됐는데 주체도 규칙도 없는 도구라면 그 호출은
거부된다 — 예전에는 이 자리가 무제한 허용이었고, 그래서 가장 엄격해 보이는 설정이 가장 약한 강제를
만들었다.

---

## 동시 실행 안전성 (ConcurrencyBehavior)

LLM이 한 응답에 여러 `tool_use`를 반환하면, 프레임워크는 **서로 독립적이고 동시 실행해도 안전한 도구들을
병렬로 실행**하여 한 iteration의 wall-clock을 단축할 수 있다. 어떤 도구가 병렬 실행에 안전한지는 도구
스스로가 `getConcurrencyBehavior()`로 선언한다. 이는 `getInterruptBehavior()`와 동일한 `default` 메서드
패턴이다.

```java
public enum ConcurrencyBehavior {
    SEQUENTIAL,        // 기본값 — 절대 병렬화되지 않음
    CONCURRENT_SAFE    // 같은 배치의 다른 CONCURRENT_SAFE 도구와 동시 실행 안전
}
```

### 기본값은 SEQUENTIAL

`Tool#getConcurrencyBehavior()`의 기본값은 `ConcurrencyBehavior.SEQUENTIAL`이다. **기존 도구는 한 줄도
고치지 않아도 순차 실행을 유지한다.** 안전한 도구만 명시적으로 override 한다:

```java
@Override
public ConcurrencyBehavior getConcurrencyBehavior() {
    return ConcurrencyBehavior.CONCURRENT_SAFE;
}
```

### CONCURRENT_SAFE 선언 체크리스트

`CONCURRENT_SAFE`로 선언하기 전에 다음을 모두 만족하는지 확인한다. 하나라도 불확실하면 `SEQUENTIAL`로 둔다.

- [ ] **부수효과가 없거나 멱등인가?** 파일/샌드박스/외부 상태를 변조하지 않는다 (읽기 전용 또는 동일 입력에
      동일 결과). `Edit`/`Write`/`Bash`/`TodoWrite`처럼 변조하는 도구는 반드시 `SEQUENTIAL`.
- [ ] **공유 가변 상태를 thread-safe 하게만 만지는가?** `ToolContext`로 전달되는 값 중 도구가 변조하는
      가변 객체가 있다면 thread-safe 여야 한다. 예: `ReadTool`이 변조하는 `READ_FILES_KEY` Set은 executor가
      `ConcurrentHashMap.newKeySet()`으로 주입한다. **새 도구가 mutable 상태를 `ToolContext`에 넣고
      변조한다면 반드시 `SEQUENTIAL`로 선언**하거나 thread-safe 자료구조를 사용해야 한다.
- [ ] **InterruptBehavior가 `NON_INTERRUPTIBLE` 또는 `COOPERATIVE`인가?** `THREAD_INTERRUPT`/
      `EXTERNALLY_TERMINATED` 도구는 실행 스레드 기준으로 terminator를 등록하므로 공유 worker 스레드에서
      의미가 모호해진다. 이런 도구는 게이트에서 자동 제외되어 병렬 경로에 진입하지 못한다(설사
      `CONCURRENT_SAFE`로 선언해도).
- [ ] **이 도구의 Pre/PostTool 훅이 thread-safe 한가?** 병렬 가능 도구의 훅은 worker 스레드에서 동시
      호출될 수 있다. 사용자 정의 훅이 내부 가변 상태를 가진다면 thread-safe 해야 한다.
- [ ] **동일 외부 리소스를 동시에 치지 않는가?** 같은 파일/같은 rate-limited 엔드포인트를 다투는 도구는
      `SEQUENTIAL`로 두는 것이 안전하다.

### 게이트 동작 (2단 판단)

병렬 실행은 **모델 의도 + 프레임워크 안전성** 2단을 모두 통과해야 한다:

1. **의도(Layer 1)** — 한 응답에 `tool_use`가 2개 이상이고, 병렬 기능이 설정으로 켜져 있어야 한다
   (`ToolConcurrencyConfig.enabled=true`, 기본은 off).
2. **안전성(Layer 2)** — 배치의 **모든** 도구가 `CONCURRENT_SAFE` + 병렬 가능 InterruptBehavior 여야 한다.
   하나라도 `SEQUENTIAL`이거나 미등록(hallucinated) 이름이면 **배치 전체를 순차** 실행한다.

병렬 실행해도 결과 리스트는 항상 입력(`tool_use`) 순서대로 재조립된다.

### 설정

병렬 실행은 기본 **off**다. `OrcaAgentExecutorFactory.withToolConcurrencyConfig(...)`로 opt-in 한다:

```java
TranscriptManager transcriptManager = new DefaultTranscriptManager(sessionRecordStore);
OrcaAgentExecutor executor = new OrcaAgentExecutorFactory()
        .withToolConcurrencyConfig(ToolConcurrencyConfig.enabled(4)) // maxConcurrency=4
        .create(llmClient, transcriptManager);
```

여러 대화/턴이 한 executor의 공유 풀을 동시에 쓰는 환경에서 한 배치가 풀을 독점하지 못하게 하려면, 전역
풀(`maxConcurrency`)은 유지한 채 **배치당 캡 `perBatchMax`**를 함께 지정한다(2-tier):

```java
ToolConcurrencyConfig.enabled(8, 2); // maxConcurrency=8(전역 풀), perBatchMax=2(배치당)
```

지정하지 않으면 `perBatchMax = maxConcurrency`로 단일 단계와 동일하게 동작한다. 유효 범위는
`[1, maxConcurrency]`다.

subagent executor는 `DefaultSubagentExecutor#withParallelToolDispatcher(...)`로 주입한다. 설정하지 않으면
양쪽 모두 순차 실행(회귀 없음)을 유지한다.

> 빠른 시작·운영·트러블슈팅·제한사항 등 전체 내용은 [도구 병렬 실행 가이드](parallel-tool-execution-guide.md)
> 를 참조한다.

---

## 전체 예제

### ReadTool 분석

```java
package at.aimon.core.tools.file;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.exception.FileNotFoundException;
import at.aimon.core.filesystem.exception.InvalidPathException;

/**
 * 파일 내용을 읽는 Tool.
 *
 * 기능:
 * - 부분 읽기 (offset, limit)
 * - 라인 번호 표시 (cat -n 형식)
 * - 긴 라인 자르기 (2000자)
 */
public class ReadTool extends AbstractTool {

    // 상수 정의
    public static final String TOOL_NAME = "Read";
    public static final String READ_FILES_KEY = "read_tool.read_files";

    private static final Logger log = LoggerFactory.getLogger(ReadTool.class);
    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final String LINE_NUMBER_FORMAT = "%6d→";

    // 의존성
    private final VirtualFileSystem fileSystem;

    public ReadTool(VirtualFileSystem fileSystem) {
        super(TOOL_NAME,
                "Read file contents from the filesystem. " +
                        "Returns file content with line numbers in cat -n format. " +
                        "Supports partial reading for large files using offset and limit parameters. " +
                        "By default, reads first 2000 lines. Lines longer than 2000 characters are truncated.",
                createInputSchema());
        this.fileSystem = Objects.requireNonNull(fileSystem, "File system cannot be null");
    }

    private static Map<String, Object> createInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "file_path", Map.of(
                                "type", "string",
                                "description", "The path to the file to read"
                        ),
                        "offset", Map.of(
                                "type", "number",
                                "description", "The line number to start reading from (1-based). " +
                                        "Only provide if the file is too large to read at once"
                        ),
                        "limit", Map.of(
                                "type", "number",
                                "description", "The number of lines to read. " +
                                        "Only provide if the file is too large to read at once"
                        )
                ),
                "required", List.of("file_path")
        );
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        try {
            // 1. 파라미터 추출
            final String filePath = input.getRequiredString("file_path");
            log.debug("Reading file: {}", filePath);

            // 2. 유효성 검사
            if (fileSystem.isDirectory(filePath)) {
                return ToolResult.error(
                        "Cannot read directory: " + filePath +
                                ". Use 'ls' command to list directory contents."
                );
            }

            // 3. 선택적 파라미터 추출 (기본값 포함)
            final int offset = input.getInteger("offset", 1);
            if (offset < 1) {
                return ToolResult.error("offset must be >= 1, got: " + offset);
            }

            final int limit = input.getInteger("limit", DEFAULT_LIMIT);
            if (limit < 1) {
                return ToolResult.error("limit must be >= 1, got: " + limit);
            }

            // 4. 핵심 작업 수행
            final String content = readFileContent(filePath, offset, limit);

            // 5. 컨텍스트 업데이트 (다른 Tool과의 연계)
            markFileAsRead(context, filePath);

            // 6. 결과 반환
            if (content.isEmpty()) {
                return ToolResult.success("[System Warning: This file is empty]");
            }

            log.debug("Successfully read file: {}", filePath);
            return ToolResult.success(content);

        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter: {}", e.getMessage());
            return ToolResult.error("Invalid parameter: " + e.getMessage());
        } catch (FileNotFoundException e) {
            log.warn("File not found: {}", e.getMessage());
            return ToolResult.error("File not found: " + e.getMessage());
        } catch (InvalidPathException e) {
            log.warn("Invalid path: {}", e.getMessage());
            return ToolResult.error("Invalid path: " + e.getMessage());
        } catch (IOException e) {
            log.error("Failed to read file: {}", e.getMessage(), e);
            return ToolResult.error("Failed to read file: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error reading file: {}", e.getMessage(), e);
            return ToolResult.error("Unexpected error: " + e.getMessage());
        }
    }

    private String readFileContent(String filePath, int offset, int limit) throws IOException {
        final StringBuilder result = new StringBuilder();

        try (InputStream inputStream = fileSystem.read(filePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            int currentLine = 1;
            int linesRead = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                // offset까지 건너뛰기
                if (currentLine < offset) {
                    currentLine++;
                    continue;
                }

                // limit 도달 시 종료
                if (linesRead >= limit) {
                    break;
                }

                // 긴 라인 자르기
                String displayLine = line;
                if (line.length() > MAX_LINE_LENGTH) {
                    displayLine = line.substring(0, MAX_LINE_LENGTH) + "...";
                }

                // cat -n 형식으로 출력
                result.append(String.format(LINE_NUMBER_FORMAT, currentLine))
                        .append(displayLine)
                        .append('\n');

                currentLine++;
                linesRead++;
            }
        }

        return result.toString();
    }

    private void markFileAsRead(ToolContext context, String filePath) {
        @SuppressWarnings("unchecked") final Set<String> readFiles = context.get(READ_FILES_KEY, Set.class).orElse(null);
        if (readFiles != null) {
            readFiles.add(filePath);
            log.debug("Marked file as read: {}", filePath);
        }
    }
}
```

---

## 체크리스트

새 Tool을 개발할 때 다음 항목을 확인하세요.

### 필수 사항

- [ ] `AbstractTool`을 상속하였는가?
- [ ] `TOOL_NAME` 상수를 정의하였는가?
- [ ] 생성자에서 `super(name, description, schema)`를 호출하였는가?
- [ ] `execute()` 메서드에서 `Objects.requireNonNull()`로 null 검사를 수행하는가?
- [ ] `execute()` 메서드가 절대 예외를 던지지 않는가?
- [ ] 모든 에러를 `ToolResult.error()`로 반환하는가?
- [ ] JSON Schema가 올바르게 정의되었는가?
- [ ] 필수 파라미터가 `required` 목록에 포함되었는가?

### 권장 사항

- [ ] Javadoc으로 클래스와 생성자를 문서화하였는가?
- [ ] 사용 예제를 포함하였는가?
- [ ] 적절한 로그 수준을 사용하였는가? (DEBUG/WARN/ERROR)
- [ ] 선택적 파라미터에 기본값을 제공하였는가?
- [ ] 의미 있는 에러 메시지를 작성하였는가?
- [ ] Thread-safe한가?

### 테스트

- [ ] 정상 케이스 테스트를 작성하였는가?
- [ ] 에러 케이스 테스트를 작성하였는가?
- [ ] 경계값 테스트를 작성하였는가?
- [ ] null 입력 테스트를 작성하였는가?

---

## 관련 문서

- [AbstractTool.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/AbstractTool.java)
- [ToolInput.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/ToolInput.java)
- [ToolResult.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/ToolResult.java)
- [ToolContext.java](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/tool/ToolContext.java)
- [ReadTool.java](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/file/ReadTool.java) - 참조 구현
