# Hook Development Guide

> 프로그래매틱 Hook 개발 가이드

이 문서는 aimon-core 프레임워크에서 Java 코드로 Hook 을 작성·등록할 때 필요한 정보를 제공합니다.
`hooks.json` / SKILL.md frontmatter 로 선언적 hook 을 구성하는 방법은
[Hook Config Guide](hook-config-guide.md) 를 참조하세요.

## 목차

1. [개요](#개요)
2. [이벤트 종류](#이벤트-종류)
3. [Hook 구현](#hook-구현)
4. [Hook 등록](#hook-등록)
5. [Context 객체](#context-객체)
6. [HookResult 반환](#hookresult-반환)
7. [실행 정책](#실행-정책)
8. [전체 예제](#전체-예제)

---

## 개요

Hook 은 에이전트 실행 중 특정 시점에 호출되는 확장 포인트입니다. Hook 을 통해 에이전트 동작을
관찰하거나(감사·메트릭), 도구 호출을 차단·변형할 수 있습니다.

### 핵심 원칙

| 원칙 | 설명 |
|------|------|
| **Single Responsibility** | 하나의 Hook 은 하나의 명확한 작업만 수행 |
| **Thread-safe** | 같은 인스턴스가 여러 에이전트·스레드에서 동시에 실행될 수 있음 |
| **예외를 던지지 않음** | 탈출한 예외는 정책에 따라 block 으로 변환될 수 있음 |
| **차단은 4개 체인에서만** | 나머지 이벤트의 `block()` 은 조용히 무시됨 ([아래](#이벤트-종류)) |
| **Stateless** | 실행 간 상태를 유지하지 않음 (필요하면 외부 저장소로 분리) |

### 패키지 구조

```
at.aimon.core.hook/
├── HookRegistry.java              # Hook 레지스트리 인터페이스
├── DefaultHookRegistry.java       # 기본 레지스트리 구현
├── HookEventType.java             # 이벤트 typed token (13개 상수)
├── HookExecutionManager.java      # Hook 실행 관리자
├── HookFeedback.java              # feedback → 모델 메시지 렌더러
├── event/                         # 이벤트별 Hook 인터페이스 + Context
│   ├── PreToolHook.java / PreToolContext.java
│   ├── PostToolHook.java / PostToolContext.java
│   ├── OnStartHook.java / OnStopHook.java
│   ├── PermissionRequestHook.java / PermissionDeniedHook.java
│   ├── SubagentStartHook.java / SubagentStopHook.java
│   ├── OnSessionStartHook.java / OnSessionEndHook.java
│   ├── PreCompactHook.java / PostCompactHook.java
│   └── OnConfigReloadHook.java
├── execution/                     # 실행 모델
│   ├── ExecutionHook.java         # 모든 hook 의 상위 인터페이스
│   ├── HookContext.java           # 모든 context 의 상위 인터페이스
│   ├── HookResult.java            # Decision × FlowControl 결과
│   ├── HookExecutionPolicy.java   # timeout / 병렬 / dedup 정책
│   └── DefaultHookExecutor.java
└── rewake/                        # 비동기 재호출 (async rewake)
```

---

## 이벤트 종류

`HookEventType<H>` 은 typed token 입니다 — 상수가 hook 인터페이스 타입을 들고 있어서
`registry.register(HookEventType.PRE_TOOL, hook)` 은 `PreToolHook` 만 받습니다. 총 13개:

| 상수 | Hook 인터페이스 | 실행 시점 | 차단 가능 |
|------|-----------------|-----------|-----------|
| `PERMISSION_REQUEST` | `PermissionRequestHook` | 도구 권한 판정 시점 | ✅ deny |
| `PRE_TOOL` | `PreToolHook` | 도구 실행 직전 | ✅ block |
| `POST_TOOL` | `PostToolHook` | 도구 실행 직후 | ❌ |
| `PERMISSION_DENIED` | `PermissionDeniedHook` | 권한 거부 후 후처리 | ❌ |
| `ON_START` | `OnStartHook` | 턴 시작 | ✅ block |
| `ON_STOP` | `OnStopHook` | 턴 종료 | ❌ |
| `ON_SESSION_START` | `OnSessionStartHook` | 대화 시작 | ❌ |
| `ON_SESSION_END` | `OnSessionEndHook` | 대화 종료 | ❌ |
| `SUBAGENT_START` | `SubagentStartHook` | 서브에이전트 시작 | ❌ |
| `SUBAGENT_STOP` | `SubagentStopHook` | 서브에이전트 종료 | ❌ |
| `PRE_COMPACT` | `PreCompactHook` | 컨텍스트 compact 직전 | ✅ block |
| `POST_COMPACT` | `PostCompactHook` | 컨텍스트 compact 직후 | ❌ |
| `ON_CONFIG_RELOAD` | `OnConfigReloadHook` | 설정 핫리로드 직후 | ❌ |

> ⚠️ **차단 가능 = ❌ 인 이벤트에서 `HookResult.block()` 을 반환해도 아무 일도 일어나지 않는다.**
> `BLOCK` 을 실제로 소비하는 호출 지점은 위 네 곳뿐이다:
>
> - `PRE_TOOL` — 도구 호출을 건너뛰고 사유를 tool 결과로 모델에 전달
> - `PERMISSION_REQUEST` — 디스패치 전에 거부
> - `ON_START` — `ExecutionBlockedByHookException` 으로 턴 중단
> - `PRE_COMPACT` — AUTO compaction 스킵 / MANUAL compaction 은 사유 보고
>
> 나머지 이벤트는 전부 advisory 다. 감사·알림 hook 을 게이트로 설계하지 말 것.
>
> 선언적 hook (`hooks.json` / SKILL.md) 도 같은 네 체인에서만 거부할 수 있고, 거부는 셸
> handler 의 **exit 2** 로 표현한다. `ON_START` 의 선언적 veto 는 최근에 추가되었다 —
> 그 전에는 `onStart` 셸 hook 이 exit 2 로 끝나도 아무 일도 일어나지 않았다.

새 이벤트를 추가하려면 hook 인터페이스 + context 타입 + `HookEventType` 상수 +
`HookExecutionManager` 메서드 + **발화 지점** 을 모두 추가해야 한다. 발화 지점이 없는 상수는
죽은 설정이다.

---

## Hook 구현

모든 hook 은 `ExecutionHook<C extends HookContext>` 의 하위 인터페이스이고, 추상 메서드가
`HookResult execute(C context)` 하나뿐이므로 람다로 작성할 수 있습니다.

### PreToolHook (차단 / 입력 변형 가능)

```java
// 보안 Hook — 위험한 명령어 차단
PreToolHook securityHook = context -> {
    if ("Bash".equals(context.getCurrentToolUse().getName())) {
        String command = (String) context.getCurrentToolUse().getInput().get("command");
        if (command != null && command.contains("rm -rf")) {
            return HookResult.block("위험한 명령어 차단: " + command);
        }
    }
    return HookResult.success();
};

// 조언 Hook — 차단하지 않고 모델에게 한마디
PreToolHook adviceHook = context -> HookResult
        .withFeedback("이 저장소에서는 npm 대신 pnpm 을 사용하세요.");

// 입력 변형 Hook — 다음 hook 과 실제 도구 호출에 전파된다
PreToolHook normalizeHook = context -> {
    ToolInput current = ToolInput.of(context.getCurrentToolUse().getInput());
    if (!current.has("timeout")) {
        Map<String, Object> patched = new LinkedHashMap<>(current.toMap());
        patched.put("timeout", 30_000);
        return HookResult.withUpdatedInput(ToolInput.of(patched));
    }
    return HookResult.success();
};
```

### PostToolHook (관찰 / 출력 변형)

```java
PostToolHook auditHook = context -> {
    auditLogger.log(context.getInvokerName(), context.getToolUse().getName(),
            context.getCurrentToolUseResult().isError() ? "FAILURE" : "SUCCESS");
    return HookResult.success();
};

// 출력 마스킹 — 모델이 보는 결과를 바꾼다
PostToolHook redactHook = context -> {
    ToolResult out = context.currentOutput();
    if (out.getContent().contains("BEGIN PRIVATE KEY")) {
        return HookResult.withUpdatedOutput(ToolResult.success("[redacted: private key]"));
    }
    return HookResult.success();
};
```

### 라이프사이클 Hook

```java
OnStartHook initHook = context -> {
    log.info("Agent '{}' started at {}", context.getInvokerName(), context.getTimestamp());
    return HookResult.success();
};

OnStopHook summaryHook = context -> {
    notificationService.sendSummary(context.getFinalAnswer());
    return HookResult.success();
};
```

### 긴 작업을 하는 Hook

기본 hook timeout(30초)보다 오래 걸리는 hook 은 스스로 예산을 선언해야 합니다. 선언하지 않으면
executor 의 바깥 그물이 먼저 잘라, hook 이 만들어낼 정상 결과가 버려집니다.

```java
class ExternalPolicyHook implements PreToolHook {

    @Override
    public Optional<Duration> getExecutionBudget() {
        return Optional.of(Duration.ofSeconds(90));   // 그물이 90s + grace 로 넓어진다
    }

    @Override
    public HookResult execute(PreToolContext context) {
        return policyClient.evaluate(context.getCurrentToolUse());   // 자체 deadline 보유
    }
}
```

선언된 예산은 **floor 이지 override 가 아닙니다** — 그물을 넓히기만 하고 좁히지는 않습니다.
상한은 `MAX_DECLARED_BUDGET` 10분이며, 그보다 큰 예산은 WARN 로그와 함께 10분으로 잘립니다.

### `getHookId()`

한 클래스의 인스턴스를 여러 개 등록할 수 있다면 `getHookId()` 를 override 하세요. async rewake
라우팅과 설정 핫리로드 취소가 이 값을 키로 씁니다. id 는 **내용에서 파생되고 리로드에도 안정적**
이어야 합니다 (선언적 hook 의 `DeclarativeHookId` 참조).

---

## Hook 등록

### HookRegistry 사용

`HookRegistry` 는 이벤트별 메서드 대신 5개의 제네릭 메서드를 노출합니다.

```java
HookRegistry registry = new DefaultHookRegistry();

registry.register(HookEventType.ON_START, initHook);
registry.register(HookEventType.PRE_TOOL, securityHook);
registry.register(HookEventType.PRE_TOOL, adviceHook);   // 여러 개 등록 가능
registry.register(HookEventType.POST_TOOL, auditHook);
registry.register(HookEventType.ON_STOP, summaryHook);

List<PreToolHook> preTool = registry.getHooks(HookEventType.PRE_TOOL);  // 타입 안전
registry.unregister(HookEventType.PRE_TOOL, adviceHook);
```

타입이 맞지 않으면 컴파일되지 않습니다 — `register(HookEventType.PRE_TOOL, auditHook)` 은
`PostToolHook` 을 `PreToolHook` 자리에 넣으려 하므로 컴파일 에러입니다.

### 실행 순서

같은 이벤트에 여러 hook 이 등록되면 **등록 순서대로** 실행됩니다.

```java
// 실행 순서: securityHook -> loggingHook -> rateLimitHook
registry.register(HookEventType.PRE_TOOL, securityHook);
registry.register(HookEventType.PRE_TOOL, loggingHook);
registry.register(HookEventType.PRE_TOOL, rateLimitHook);
```

`updatedInput` / `updatedOutput` 은 이 순서대로 누적되어 다음 hook 의 `getCurrentToolUse()` /
`currentOutput()` 에 반영됩니다.

---

## Context 객체

각 Hook 은 실행 시점에 맞는 Context 객체를 받습니다. 모든 Context 는 `HookContext` 를 구현합니다.

### 공통 필드 (`HookContext`)

| 접근자 | 타입 | 설명 |
|--------|------|------|
| `getInvokerType()` | `InvokerType` | 실행자 유형 (MAIN_AGENT, SUBAGENT 등) |
| `getInvokerName()` | `String` | 실행자 이름 |
| `getHookRegistry()` | `HookRegistry` | Hook 레지스트리 |
| `getEnvironment()` | `Environment` | 환경 설정 |
| `getTimestamp()` | `Instant` | 타임스탬프 |
| `getExecutionAttributes()` | `Map<String, Object>` | 실행 부가 정보 |

### `PreToolContext`

| 접근자 | 타입 | 설명 |
|--------|------|------|
| `getOriginalToolUse()` | `ToolUse` | 어떤 hook 도 손대기 전의 원본 |
| `getCurrentToolUse()` | `ToolUse` | 앞선 hook 들의 `updatedInput` 이 반영된 현재 값 — 실행될 도구를 보려면 이쪽 |
| `getIterationCount()` | `int` | 현재 ReAct 루프 반복 횟수 |

### `PostToolContext`

| 접근자 | 타입 | 설명 |
|--------|------|------|
| `getToolUse()` | `ToolUse` | 실행된 도구 |
| `getOriginalToolUseResult()` | `ToolUseResult` | 원본 결과 |
| `getCurrentToolUseResult()` | `ToolUseResult` | 앞선 hook 들의 `updatedOutput` 이 반영된 결과 |
| `originalOutput()` / `currentOutput()` | `ToolResult` | 같은 값의 `ToolResult` 뷰 |
| `getIterationCount()` | `int` | 현재 ReAct 루프 반복 횟수 |

### `OnStopContext`

| 접근자 | 타입 | 설명 |
|--------|------|------|
| `isSuccess()` | `boolean` | 턴 성공 여부 |
| `getFinalAnswer()` | `String` | 최종 응답 |
| `getMetadata()` | `ExecutionMetadata` | iteration 수 등 실행 메타데이터 |

나머지 이벤트의 Context 도 같은 규칙을 따릅니다 — 공통 필드 + 이벤트 고유 필드, 전부 immutable.

---

## HookResult 반환

`HookResult` 는 **하나의 enum 이 아니라 두 개의 독립 축**입니다.

- **`Decision`** — `ALLOW` / `ASK` / `DENY` (`ASK` 는 권한 체인만 해석)
- **`FlowControl`** — `CONTINUE` / `BLOCK`

여러 hook 의 결과는 `HookResult.merge(...)` 로 `deny > ask > allow`, `block > continue`
우선순위에 따라 병합됩니다.

### 팩토리

```java
HookResult.success();                        // ALLOW + CONTINUE
HookResult.allow();                          // success() 의 의미 명시 버전
HookResult.withFeedback("...");              // ALLOW + CONTINUE + 모델에게 전달할 메시지
HookResult.block("사유");                     // BLOCK — 차단 가능한 4개 체인에서만 유효
HookResult.deny("사유");                      // DENY + BLOCK (권한 체인)
HookResult.ask("사유");                       // ASK — 사용자 확인 요청
HookResult.withUpdatedInput(toolInput);      // preTool 입력 변형
HookResult.withUpdatedOutput(toolResult);    // postTool 출력 변형
HookResult.asyncRewake(spec);                // 나중에 다시 깨워달라는 요청
HookResult.builder()...build();              // 여러 축을 동시에 설정
```

> `deny(reason)` / `block(reason)` 의 사유는 **feedback 필드에 저장됩니다.** 차단된 결과의
> feedback 이 곧 그 거부 사유이므로, 이를 advisory feedback 으로 한 번 더 노출하지 마세요.

### Feedback 이 모델에게 닿는 경로

`withFeedback(msg)` 는 모델에게 말을 거는 유일한 수단입니다. 직접 조립하지 말고 `HookFeedback`
로 렌더하세요.

렌더링은 계약의 절반일 뿐입니다 — **발화 지점이 반환된 결과를 읽어야** feedback 이 모델에게
닿습니다. 현재 그 경로는 셋뿐이고, 나머지 발화 지점은 결과를 그냥 버립니다.

| 체인 | feedback 의 행선지 | 렌더 위치 |
|------|--------------------|-----------|
| `PERMISSION_REQUEST` / `PRE_TOOL` / `POST_TOOL` | 해당 도구의 결과에 `<system-reminder key="hook-feedback">` 로 덧붙음 | `SingleToolInvoker` |
| `ON_START` | user role 메시지로 대화에 추가 (같은 래퍼) | `OrcaAgentExecutor`, `DefaultSubagentExecutor` |
| `PRE_COMPACT` | 메시지가 아니라 **요약 프롬프트의 custom instruction** 으로 합쳐짐 | `DefaultCompactionEngine` |

도구 관련 체인의 feedback 이 별도의 user 메시지가 될 수 없는 이유는 `tool_use` 와 그
`tool_result` 사이에 user 턴이 낄 수 없기 때문입니다.

> ⚠️ **나머지 8개 이벤트는 feedback 을 조용히 버립니다** — `PERMISSION_DENIED`, `ON_STOP`,
> `ON_SESSION_START`, `ON_SESSION_END`, `SUBAGENT_START`, `SUBAGENT_STOP`, `POST_COMPACT`,
> `ON_CONFIG_RELOAD` 의 발화 지점은 반환값을 읽지 않고 부수효과만 위해 호출합니다.
> "라이프사이클 체인은 user 메시지로 덧붙는다" 는 `ON_START` 하나에만 해당합니다.
> 새 경로를 여는 것은 버그 수정이 아니라 기능 추가입니다 — 블록을 어디에 놓을지 결정하는
> 호출 지점이 함께 필요합니다.

> 차단된 결과의 feedback 은 곧 거부 사유이므로 `HookFeedback.collectAdvisory(...)` 가
> 걸러냅니다. 차단 지점이 이미 사유를 에러로 렌더하기 때문입니다.

---

## 실행 정책

`HookExecutionPolicy` 가 체인 실행 방식을 결정합니다.

| 항목 | 기본값 | 설명 |
|------|--------|------|
| `timeout` | 30초 | hook 하나당 바깥 안전망 |
| `timeoutBehavior` | `FAIL_OPEN` | 타임아웃 시 통과(`FAIL_OPEN`) / 차단(`FAIL_CLOSED`) |
| `executionMode` | `SEQUENTIAL` | 병렬 실행은 opt-in |
| `stopOnBlocked` | 정책별 | 차단 결과가 나오면 후속 hook 단축 |
| `dedupKeyExtractor` | 없음 | 같은 키의 중복 hook 제거 |

- **`timeoutFor(hook)`** 이 실제 적용 시간입니다. hook 이 `getExecutionBudget()` 으로 정책 timeout
  **이상**의 예산을 선언하면 그만큼 넓어지고(+`DECLARED_BUDGET_GRACE` 5초), 짧게 선언하면
  무시됩니다. 비교는 `<` 이므로 **정책 timeout 과 정확히 같은 예산도 grace 를 받습니다** —
  `ShellAction.DEFAULT_TIMEOUT` 과 `HookExecutionPolicy.DEFAULT_TIMEOUT` 이 둘 다 30초라
  `timeoutMs` 를 생략한 선언적 셸 hook 이 전부 이 경우에 해당하기 때문입니다. 등호를 배제하면
  기본 설정에서 그물이 hook 자신의 deadline 과 경주하게 됩니다.
- **선언 예산은 `MAX_DECLARED_BUDGET`(10분)으로 클램프됩니다.** 선언 예산은 설정
  (`hooks.json` / frontmatter 의 `timeoutMs`) 에서 오는 검증되지 않은 값이므로, 상한이 없으면
  hook 하나가 턴을 무한정 붙잡을 수 있고 `Long.MAX_VALUE` 근처 값은 executor 의 나노초 변환에서
  오버플로합니다. 초과 시 WARN 로그를 남기고 10분으로 자릅니다.
- **병렬 모드**에서 timeout 은 **대기를 제한할 뿐, 이미 끝난 작업을 버리지 않습니다.** 결과는 항상
  등록 순서대로 재조립됩니다.
- **`stopOnBlocked` 는 `SEQUENTIAL` 에서만 의미가 있습니다.** `PARALLEL` 에서는 이미 제출된
  hook 을 취소할 수 없으므로 no-op 이며, 모든 결과가 그대로 반환됩니다.
- **인터럽트로 끊긴 대기는 정책과 무관하게 BLOCKED 입니다.** 턴을 모는 스레드가 인터럽트되면
  `future.get` 은 기다리지 않고 던지므로, 그 예외를 `onException` 으로 넘기면 체인의 모든 hook 이
  **돌지도 않은 채** fail-open 으로 통과합니다. 판정이 없다는 것은 진행해도 좋다는 허가가 없다는
  뜻이므로 실행기가 BLOCKED 로 답합니다 (`TimeoutBehavior.FAIL_CLOSED` 와 같은 이유). 이미 끝난
  hook 은 영향이 없습니다 — 완료된 결과는 그대로 돌아옵니다.

### Async rewake 재부착

`getExecutionBudget()` 과 마찬가지로 rewake 도 트리거 종류에 따라 재부착 규칙이 다릅니다
(`DeclarativeRewake`):

- **`delay` / `event`** — envelope 이 정확히 한 번 발사되므로, 매 fire 마다 spec 을 다시 붙여야
  다음 링크가 생깁니다. 체인은 `RewakeSpec#getMaxAttempts()` 로 상한이 걸립니다.
- **`cron`** — envelope 이 스케줄러의 네이티브 cron 트리거로 등록되어 스스로 반복하므로 **다시
  붙이지 않습니다.** 재부착하면 fire 마다 새 체인 envelope 이 추가로 생겨 live envelope 수가
  fire 당 2배(대략 `2^(maxAttempts-1)`)로 분기합니다.
- **예외**는 `onException` 으로 매핑됩니다. `failClosedStopOnBlocked` 정책에서는 hook 의 버그가
  차단으로 이어지므로, hook 안에서 예외를 잡아 명시적 결과로 바꾸는 편이 안전합니다.

---

## 전체 예제

### 보안 및 감사 Hook 시스템

```java
package at.aimon.example.hook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookResult;

/**
 * 보안 및 감사를 위한 Hook 시스템 설정 예제.
 */
public class SecurityAuditHookSetup {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditHookSetup.class);

    public HookRegistry createSecurityAuditRegistry() {
        HookRegistry registry = new DefaultHookRegistry();

        // 턴 시작 — 세션 초기화
        registry.register(HookEventType.ON_START, context -> {
            log.info("[AUDIT] Turn started: agent={}, type={}, time={}", context.getInvokerName(),
                    context.getInvokerType(), context.getTimestamp());
            return HookResult.success();
        });

        // PreTool — 보안 검증 (이 체인은 실제로 차단된다)
        registry.register(HookEventType.PRE_TOOL, context -> {
            String toolName = context.getCurrentToolUse().getName();
            var input = context.getCurrentToolUse().getInput();

            if ("Bash".equals(toolName)) {
                String command = (String) input.get("command");
                if (isDangerousCommand(command)) {
                    log.warn("[SECURITY] Blocked dangerous command: {}", command);
                    return HookResult.block("Security policy violation: dangerous command blocked");
                }
            }

            if ("Read".equals(toolName) || "Write".equals(toolName)) {
                String path = (String) input.get("file_path");
                if (isSensitivePath(path)) {
                    log.warn("[SECURITY] Blocked access to sensitive path: {}", path);
                    return HookResult.block("Security policy violation: access to sensitive path blocked");
                }
            }

            return HookResult.success();
        });

        // PreTool — 감사 로깅 (같은 이벤트에 두 번째 hook, 등록 순서대로 실행)
        registry.register(HookEventType.PRE_TOOL, context -> {
            log.info("[AUDIT] Tool invocation: tool={}, iteration={}, input={}",
                    context.getCurrentToolUse().getName(), context.getIterationCount(),
                    context.getCurrentToolUse().getInput());
            return HookResult.success();
        });

        // PostTool — 결과 감사 (차단 불가; 관찰 전용)
        registry.register(HookEventType.POST_TOOL, context -> {
            log.info("[AUDIT] Tool completed: tool={}, error={}", context.getToolUse().getName(),
                    context.getCurrentToolUseResult().isError());
            return HookResult.success();
        });

        // 턴 종료 — 요약
        registry.register(HookEventType.ON_STOP, context -> {
            log.info("[AUDIT] Turn ended: agent={}, success={}", context.getInvokerName(), context.isSuccess());
            return HookResult.success();
        });

        return registry;
    }

    private boolean isDangerousCommand(String command) {
        if (command == null) {
            return false;
        }
        return command.contains("rm -rf") || command.contains("sudo") || command.contains("chmod 777")
                || command.contains("> /dev/");
    }

    private boolean isSensitivePath(String path) {
        if (path == null) {
            return false;
        }
        return path.contains("/etc/passwd") || path.contains("/etc/shadow") || path.contains(".ssh/")
                || path.contains(".env");
    }
}
```

---

## 관련 문서

- [Hook Config Guide](hook-config-guide.md) — `hooks.json` / SKILL.md 선언적 hook
- [HookEventType.java](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/HookEventType.java)
- [HookResult.java](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/execution/HookResult.java)
- [HookRegistry.java](../../../modules/aimon-core/src/main/java/at/aimon/core/hook/HookRegistry.java)
- [Hook System Upgrade 설계](../../design/hook/hook-system.md) — Phase 1–5 구현 기록
- [Tool 개발 가이드](../tool/tool-development-guide.md)
