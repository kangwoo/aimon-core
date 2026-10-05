# 설계 — EE-9 → EE-12: 훅 컨텍스트에 실행 환경 싣기, 스킬 선언 훅의 셸을 실행 환경으로

> Status: **IMPLEMENTED** (2026-10-03). 백로그 항목 EE-9 · EE-12 의 설계이고, 설계 리뷰에서 승인된 그대로다(한 차례, 차단
> 지적 없이 통과, 비차단 지적 열한 건). 아래 본문(§1~§9)은 승인본에서 한 글자도 바꾸지 않았다 — 줄 번호 인용(`7915560`
> 기준)과 열린 질문 번호까지 그대로다. 그래서 본문에는 **구현 뒤에 사실이 아니게 된 서술**이 남아 있다. 고친 것은
> 본문이 아니라 §10 에 적는다.
>
> 더한 것은 §10 하나다 — 구현이 이 설계에서 벗어난 곳과 그 이유, 그리고 반영한 리뷰 지적. 특히 §10.1(설계 §6 의
> ArchUnit 서술이 틀렸다)과 §10.4(새 백로그 항목이 §8 의 셋이 아니라 다섯이다)를 먼저 볼 것. §9 의 열린 질문 가운데 이
> 변경 밖으로 결과가 번지는 것은 [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md)
> 로 옮겼다(EE-48 ← Q1, EE-51 ← Q2, EE-52 ← Q3; EE-49 · EE-50 ← §8). 열림/닫힘의 정본은 그 문서다. 이 설계가 확장하는
> 명세는 [`execution-environment.md`](execution-environment.md) §10 · §13 · §15 다.

> **덧붙임 (2026-10-04, EE-49 · EE-51).** §8 이 남긴 EE-49(스킬 훅이 agent-scoped 레지스트리에 등록된다)와 §9 Q2 의
> EE-51(환경이 없으면 가드 훅이 통과한다)은 닫혔다. 스킬 훅은 이제 그 스킬의 포크가 받는 레지스트리에만 얹히고
> (`RegistryBackedSkillHookActivator` → `ScopedSkillHookActivator`), 명령을 돌리지 못한 가드는 **막는다** —
> `ShellHookOutcome.notObserved()` 는 `notRun(cause, detail)` 로 바뀌었다. 아래 본문의 "통과(fail-open)" 서술은 그 시점의
> 기록이다. 설계는
> [`execution-environment-ee49-ee51-ee58-isolation-boundary.md`](execution-environment-ee49-ee51-ee58-isolation-boundary.md).
>
> **덧붙임 (2026-10-03, EE-14).** 이 문서가 말하는 `Environment`(`agent.Environment`)는 이제 없다. 하나 남았던 필드는
> `at.aimon.core.base.UserLocale` 로 옮겨 갔다. 본문은 승인본 그대로 두었고, 대응표는
> [`../../migration/rename-maps.md`](../../migration/rename-maps.md) 에 있다.

기준 커밋 `7915560` (`main`). 줄 번호는 모두 2026-10-03 에 이 워크트리에서 확인한 것이다.
경로는 따로 적지 않으면 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

## 1. 문제

훅은 "명령이 어디서 도는가" 를 알아야 하는 소비자인데, 지금 `HookContext` 가 실행 환경에 대해 주는 것은
`getEnvironmentDescriptor()` 하나이고 그것도 `SingleToolInvoker` 가 만드는 PreTool/PostTool 컨텍스트에서만 채워진다
(EE-9). 그리고 스킬 파일이 선언한 훅의 셸 액션은 **스킬을 파싱할 때** 묶인 호스트 `LocalShell` 에서 돈다
(`new SkillHookSetParser(new DefaultShellActionExecutor(skillHookShell))` — `AimonStackBuilder:265-268`,
CLI `AgentSetupFactory:544-545, 969-972`). 샌드박스 제공자를 붙이면 같은 스킬의 스크립트가 `Bash` 로는 샌드박스에서,
훅으로는 호스트에서 돌고, 스킬 작성자가 호스트 셸을 얻는 통로가 된다(EE-12). 결정(2026-09-29)은 "스킬 선언 훅의 셸
액션은 실행 환경의 셸에서 돈다, 환경이 없는 시점에 발화하는 훅은 셸 액션을 쓸 수 없다" 이다. 그러려면 (a) 실행 안에서
발화하는 모든 훅 컨텍스트가 서술자가 아니라 `ExecutionEnvironment` 자체를 줘야 하고, (b) `ShellActionExecutor` 가 셸을
쥐지 않고 발화 시점의 컨텍스트에서 얻어야 하며, (c) 실행 밖 이벤트의 셸 액션은 조용히 실패하지 않고 파싱 때 거부되어야 한다.

## 2. 소스로 센 것 — 이벤트별 발화 지점

`*Context.builder()` 호출 지점을 main 소스 전체에서 셌다(`hook/event/` 자신과 Javadoc 예시는 제외).

| 이벤트 | 발화 지점 | 실행 안인가 | 환경을 어디서 얻나 |
|---|---|---|---|
| `preTool` / `postTool` | `toolinvocation/SingleToolInvoker:211, 317` | 안 | `spec.getToolContext()` 의 `EXECUTION_ENVIRONMENT` (지금 `descriptorOf(spec)` 가 읽는 그 값) |
| `permissionRequest` / `permissionDenied` | `SingleToolInvoker:187, 294` | 안 | 같은 `spec` — 지금은 안 싣는다 |
| `onStart` | `OrcaAgentExecutor:2639`(호출 1514), `DefaultSubagentExecutor:590` | 안 | `scope.executionEnvironment`(1064 에서 해석, 1142 에서 스코프에 담김) / 포크는 357 에서 해석해 `lc.toolContext` 에 든 값 |
| `onStop` | `OrcaAgentExecutor:2666`(호출 9곳, 전부 `scope` 를 받음), `DefaultSubagentExecutor:1065, 1107, 1175`, `command/system/CompactCommand:201` | 안 | 위와 같음. `CompactCommand` 는 `executeCommand(scope, …)`(2096) 안에서 돌고 커맨드 `ToolContext` 에 환경이 실려 있다(2115) |
| `subagentStart` / `subagentStop` | `subagent/DefaultSubagentExecutionManager:777, 794` | 안 (스폰한 쪽 실행) | `SubagentExecutionEnvironment.getExecutionEnvironment()` — **스폰한 실행의** 환경. 런타임 수준 러너가 스폰하면 비어 있다(`:404`) |
| `preCompact` / `postCompact` | `agent/compact/DefaultCompactionEngine:325, 213, 289` | 안 | `CompactionRequest` / `SummaryRequest` — 지금은 환경이 여기까지 오지 않는다(§4.3) |
| `onSessionStart` / `onSessionEnd` | `agent/session/DefaultLiveSession:364, 379` | **밖** | 없음 |
| `onConfigReload` | `config/hook/HookRegistryReloader:317` | **밖** | 없음 |
| rewake 리플레이 (`preTool`·`preCompact`·`onSessionStart`·`onSessionEnd`·`onConfigReload`) | `hook/rewake/impl/DefaultRewakeFireListener:293-347` | **밖** | 없음. 원래 훅 **하나**에만 다시 발화한다(`hook.execute(rebuiltContext)`), 체인 전체가 아니다 |

**백로그 결정문과 달랐던 점 (닫을 때 적는다).**

1. 결정문은 "`OnStart` · `OnStop` 은 실행 밖에서 발화할 수 있다" 고 적었지만 **그렇지 않다.** 두 이벤트의 발화 지점은
   전부 `ExecutionScope` / `LoopContext` 를 가진 자리이고, 유일하게 의심스러웠던 `CompactCommand` 의 `onStop` 도 실행기의
   커맨드 흐름 안이다. 실행 밖 이벤트는 **셋**(`onSessionStart`·`onSessionEnd`·`onConfigReload`)뿐이다.
2. 그 셋은 스킬 frontmatter 에서 **이미 거부된다** — 셸 액션만이 아니라 이벤트째로. `SkillHookSet.supportedEvents()` 에
   없고(`skill/hook/SkillHookSet.java:45-48`), `SkillHookSetParser:190-194` 가 "unknown event" 로 던진다. 즉 "실행 밖
   이벤트의 셸 액션을 파싱 시점에 거부" 는 새 거부 규칙이 아니라 **이미 있는 화이트리스트를 불변식으로 못 박는 일**이고,
   "기존 스킬이 깨진다" 던 우려는 실현되지 않는다(지금 로드되는 스킬 가운데 이 변경으로 거부되는 것은 없다).
3. "실행 안 이벤트" 여도 **발화 시점에 환경이 없을 수 있다** — 손으로 만든 `ToolContext`, 런타임 수준 러너가 스폰한
   서브에이전트, 환경을 흘려 주지 않는 서드파티 `CompactionGuard`. 그리고 환경이 **있되 사용 불가**일 수 있다
   (`UnavailableExecutionEnvironment`). 이 둘은 파싱으로 못 막으므로 발화 시점 처리가 따로 필요하다(§6).
4. 호스트 셸 갈래는 둘이 아니라 **셋**이다. CLI 는 같은 `skillHookShell` 을 `hooks.json` 핫리로드에도 넘긴다
   (`AgentSetupFactory:1031`). `hooks.json` 은 스킬 선언이 아니라 운영자 설정이고 실행 밖 이벤트 셋을 선언할 수 있는
   유일한 자리이므로 결정의 범위 밖이다(§3.3, 열린 질문 Q1).
5. 스킬 하나의 파싱 실패가 목록 전체를 무너뜨리는 구멍이 **지금 있다.** `DefaultSkillRegistry.getAllSkills()`(231) 와
   `reloadAll()`(259) 는 `SkillRepositoryException` 만 잡는데 파서는 형제 타입 `SkillParseException` 을 던진다. PR #195 가
   고친 것은 링크 규칙 쪽(`SkillRepositoryException`)뿐이었다.

## 3. 접근

### 3.1 `HookContext` 가 환경을 주고, 서술자는 거기서 파생된다

`HookContext` 에 `default Optional<ExecutionEnvironment> getExecutionEnvironment()`(기본 empty)를 더하고,
`getEnvironmentDescriptor()` 의 기본 구현을 `getExecutionEnvironment().map(ExecutionEnvironment::descriptor)` 로 바꾼다.
컨텍스트는 **환경 하나만** 필드로 든다 — 서술자를 따로 들면 출처가 둘이 된다. §2 표의 "안" 인 열 이벤트 컨텍스트 전부에
nullable 필드와 빌더 메서드 `executionEnvironment(ExecutionEnvironment)` 를 둔다. "밖" 인 셋에는 **두지 않는다** — 빌더에
메서드가 없으니 실수로도 채울 수 없고, 인터페이스 기본값이 "비어 있는 것이 정답" 을 표현한다.

사용 불가 환경은 **그대로 싣는다**(empty 로 바꾸지 않는다). 도구 경로와 같은 규칙이고(§15 "기본 환경으로 되돌아가지 말
것"), 훅이 `descriptor()` 로 사용 불가를 구분할 수 있다.

### 3.2 `ShellActionExecutor` 는 발화 컨텍스트를 받는다

```java
public interface ShellActionExecutor {
    boolean isShellSupported();
    /** true 면 이 실행기는 발화 컨텍스트의 실행 환경 없이는 명령을 돌릴 수 없다. */
    boolean requiresExecutionEnvironment();
    ShellHookOutcome run(ShellAction action, HookContext context,
            Map<String, String> environmentOverrides, String stdinPayload);   // 절대 던지지 않는다
}
```

- `DefaultShellActionExecutor` — **인자 없는 생성자.** `context.getExecutionEnvironment()` 의 `shell()` 로 실행한다.
  `requiresExecutionEnvironment() == true`. 스킬 파서 두 갈래가 이것을 쓴다.
- `HostShellActionExecutor(VirtualShell)` — 신규. 지금의 `DefaultShellActionExecutor` 동작 그대로(고정 셸, 컨텍스트
  무시). `requiresExecutionEnvironment() == false`. **`hooks.json` 전용**(§3.3).
- `NoOpShellActionExecutor` — 그대로(`isShellSupported() == false`).
- 옛 메서드 둘(`run(action, env)`, `run(action, env, stdin)`)은 **삭제한다.** 컨텍스트가 없는 진입점을 남기면 셸을 고를
  수 없고, "컨텍스트를 버리는 기본 구현" 은 호스트 셸로 되돌아가는 길이 된다.
- 세 구현이 공유하는 실행 사다리(`ExecutionOptions` 조립, 종료 코드 로깅, timeout/예외 삼킴, `summarise`)는 패키지 전용
  헬퍼 하나로 뺀다.

호출부는 세 곳이다: `AbstractDeclarativeShellHook.execute`(이미 `context` 를 들고 있다), `DeclarativePreToolHook`,
`DeclarativePostToolHook`.

### 3.3 `hooks.json` 은 호스트에 남긴다 (이번 범위)

CLI 는 `hooks.json` 핫리로드용으로 호스트 `LocalShell` 을 **계속** 만든다. 이름을 `hookConfigShell` 로 바꾸고
`HostShellActionExecutor` 로 감싼다. 스킬 파서에는 더 이상 넘기지 않는다. `AimonStackBuilder` 는 `hooks.json` 을 다루지
않으므로 셸을 **아예 만들지 않는다.** 근거: 결정문의 주어가 "스킬 선언 훅" 이고, `hooks.json` 은 `onSessionStart` 등
실행 밖 이벤트를 선언할 수 있는 유일한 자리라 환경 셸로 옮기면 그 훅들이 전부 죽는다. 남는 비대칭(운영자 훅은 호스트,
도구는 샌드박스)은 새 백로그 항목으로 올린다(§8).

### 3.4 파싱 시점 거부는 단일 출처의 불변식으로

`HookEventType` 에 `boolean firesInsideExecution()` 을 둔다(상수 정의에 인자로 — §2 표가 근거). 이것이 분류의 단일
출처다. 규칙은 한 줄: **`shellExecutor.requiresExecutionEnvironment() && !eventType.firesInsideExecution()` 이면 셸 액션을
거부한다.**

- `SkillHookSetParser` — `shell` 액션을 파싱할 때 검사해 `IllegalArgumentException` (→ `MarkdownSkillParser` 가
  `SkillParseException` 으로 감쌈). 오늘은 unknown-event 검사가 먼저 걸리므로 도달하지 않지만, 누가
  `SkillHookSet.SUPPORTED_EVENTS` 에 실행 밖 이벤트를 더하는 순간 이 검사가 막는다. unknown-event 메시지에도 이유를
  적는다: 세 이벤트는 실행 밖에서 발화해 실행 환경이 없고, 스킬 훅의 셸 액션은 실행 환경의 셸에서만 돈다.
- `HookRegistryApplier` — 같은 검사, 기존 관례대로 WARN 후 그 핸들러만 건너뜀(`applyEntry` 의 invalid handler 처리).
  호스트 실행기에서는 `requiresExecutionEnvironment() == false` 라 걸리지 않는다. 임베더가 환경 실행기를 `hooks.json` 에
  물렸을 때 조용히 죽는 훅이 생기지 않게 하는 안전판이다.
- 불변식 테스트: `SkillHookSet.supportedEvents()` 의 모든 이벤트가 `firesInsideExecution()` 이다.

### 3.5 스킬 하나의 거부가 다른 스킬을 무너뜨리지 않게

`DefaultSkillRegistry.getAllSkills()` 와 `reloadAll()` 의 catch 를 `SkillRepositoryException | SkillParseException` 으로
넓히고 `logSkipped` 로 보낸다. `getSkill(name)` / `reloadSkill(name)` 은 그 스킬을 지목한 호출이므로 지금처럼 던진다.

### 3.6 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 훅 컨텍스트에 `VirtualShell` 만 싣는다 | 과제가 환경 자체를 요구하고(EE-12 결정문 3항), 서술자·파일 시스템을 읽는 훅마다 필드를 하나씩 더하게 된다. 서술자를 환경에서 파생시키는 단일 출처도 잃는다 |
| 서술자 필드를 유지하고 환경 필드를 **추가** | 한 컨텍스트에 "어디서 도는가" 의 출처가 둘. 한쪽만 채운 컨텍스트가 생긴다 |
| 실행기가 환경을 `ThreadLocal` / 앰비언트로 찾는다 | 훅은 `DefaultHookExecutor` 의 풀 스레드에서 돈다. 전파가 조용히 끊기고, 끊기면 "환경 없음 → 실행 안 함" 이 되어 원인이 안 보인다 |
| 스킬 활성화 시점(`SkillTool` → `SkillHookActivator.activate`)에 그 실행의 셸을 훅에 묶는다 | 포크 모드 스킬의 훅은 **포크의** 도구 호출에서 발화하고 포크는 자기 환경을 따로 해석한다(`DefaultSubagentExecutor:357`). 활성화 시점의 환경은 스폰한 쪽 것이라 틀린 셸을 묶는다. 파싱된 `SkillHookSet` 은 캐시되어 실행 사이에 공유되기도 한다 |
| `DefaultShellActionExecutor` 하나에 "환경 없으면 호스트 폴백" | 결정이 기각한 바로 그 통로. 설계 §15 "기본 환경으로 되돌아가지 말 것" |
| `hooks.json` 도 이번에 환경 셸로 | 결정 범위 밖이고 실행 밖 이벤트의 운영자 훅이 전부 죽는다. Q1 |
| 분류를 `DeclarativeShellHookBinding` 테이블에 둔다 | `preTool`/`postTool` 이 그 테이블에 없다. 분류는 이벤트의 성질이지 셸 전용 훅의 성질이 아니다 |
| 발화 시점에만 막는다(파싱 검사 없음) | 결정문이 명시적으로 기각 — 실행기가 실패를 삼켜 조용하다 |
| `CompactionGuard` 에 `ExecutionEnvironment` 오버로드 추가 | 이미 `maybeCompact`/`forceCompact` × `executionId` 유무로 4개다. 한 축을 더하면 8개. 대신 요청 객체 하나(§4.3) |
| 환경을 `agent.Environment` 에 싣는다 | `Environment` 는 agent-scoped 값(`timeZone`)이고 환경은 실행마다 고른다. EE-10 이 지우려는 "에이전트 단위 값이 실행 단위 사실을 든다" 를 다시 만든다 |

## 4. 파일·모듈별 변경

### 4.1 훅 계약 — `hook/`

- `hook/execution/HookContext.java` — `getExecutionEnvironment()` 추가, `getEnvironmentDescriptor()` 기본 구현을 파생으로.
  Javadoc 에 "실행 밖 이벤트는 비어 있는 것이 정답" 과 "사용 불가 환경은 실려 온다" 를 적는다.
- `hook/HookEventType.java` — `firesInsideExecution()`. 열 이벤트 `true`, `ON_SESSION_START`·`ON_SESSION_END`·
  `ON_CONFIG_RELOAD` `false`.
- `hook/event/` 의 열 컨텍스트(`OnStart`·`OnStop`·`PreTool`·`PostTool`·`PermissionRequest`·`PermissionDenied`·
  `SubagentStart`·`SubagentStop`·`PreCompact`·`PostCompact`) — nullable `executionEnvironment` 필드 + 빌더 메서드 +
  `getExecutionEnvironment()` 재정의. `PreToolContext`/`PostToolContext` 의 `environmentDescriptor` 필드와 빌더 메서드는
  **삭제**(파생으로 대체). `PreToolContext.withCurrentInput` 은 환경을 옮긴다. `toString` 에는 환경을 넣지 않는다.
- 세 실행 밖 컨텍스트 — 코드 변경 없음, 클래스 Javadoc 에 한 줄.

### 4.2 발화 지점

- `toolinvocation/SingleToolInvoker.java` — `descriptorOf(spec)` → `environmentOf(spec)`(`ExecutionEnvironmentAccess.of(...)
  .orElse(null)`). 네 컨텍스트 모두에 싣는다. 스킬 커맨드 디스패처는 이 경로를 타므로 따로 손대지 않는다. `RoutineExecutor` 는
  도구를 직접 부르고 훅을 발화하지 않으므로 손댈 것이 없다(첫 판은 이것도 이 경로를 탄다고 잘못 적었다).
- `agent/impl/orca/OrcaAgentExecutor.java` — `invokeOnStart`/`invokeOnStop` 에 `scope.executionEnvironment`,
  `contextRequest(scope, …)`(3194)에도.
- `subagent/execution/DefaultSubagentExecutor.java` — `fireOnStart` 와 `onStop` 세 곳, 617 의 `ContextRequest`. 환경은
  `LoopContext` 에 필드로 더하거나 `lc.toolContext` 에서 읽는다(둘은 같은 인스턴스다, 778). 빌더가 필드 쪽을 택하면
  `onStop` 세 곳의 중복 조립을 헬퍼 하나로 모아도 좋다.
- `subagent/DefaultSubagentExecutionManager.java` — `fireSubagentStart`/`fireSubagentStop` 에
  `env.getExecutionEnvironment().orElse(null)`. **스폰한 실행의 환경**이다(Q3).
- `command/system/CompactCommand.java` — `context.getToolContext()` 에서 환경을 읽어 `ContextRequest`(145)와 `onStop`
  컨텍스트(201)에 싣는다. 키가 없으면 비운다.

### 4.3 압축 경로 — `agent/context/`, `agent/compact/`

환경이 `ContextRequest → (CompactionGuard) → CompactionRequest | SummaryRequest → DefaultCompactionEngine` 로 흘러야 한다.

- `agent/context/ContextRequest.java` — `Optional<ExecutionEnvironment> getExecutionEnvironment()` + 빌더 메서드.
- `agent/compact/CompactionRequest.java`, `SummaryRequest.java` — 같은 필드.
- `agent/compact/CompactionGuardRequest.java` — **신규** 불변 class + builder: `transcriptBuffer`, `model`, `hookRegistry`,
  `environment`, `executionId?`, `executionEnvironment?`, `budgetForced`.
- `agent/compact/CompactionGuard.java` — `default CompactionDecision maybeCompact(CompactionGuardRequest request)`.
  기본 구현은 `budgetForced` 와 `executionId` 유무로 기존 네 메서드 중 하나에 위임한다(환경은 떨어진다 — 서드파티 가드는
  고치기 전까지 압축 훅에 환경이 비어 가고, 그 경우는 §6 의 발화 시점 처리가 받는다). 기존 네 메서드는 그대로 둔다.
- `agent/compact/DefaultCompactionGuard.java` — 새 메서드를 재정의해 `serializedEvaluate`/`invokeEngine` 까지 환경을
  넘기고 `CompactionRequest` 에 싣는다. 기존 네 메서드는 환경 없는 요청으로 위임.
- `agent/context/DefaultContextEngine.java` — `prepare`(170-179)의 4갈래 분기를 `maybeCompact(CompactionGuardRequest)` 한
  호출로. `compactNow`(325)와 `summarizeIntoView`(371)는 요청에서 환경을 옮긴다.
- `agent/context/RollingContextEngine.java` — `SummaryRequest`(560)에 환경.
- `agent/compact/DefaultCompactionEngine.java` — `generateSummary` 가 환경을 받아 `PreCompactContext` 에, 두
  `PostCompactContext`(213, 289)는 요청에서. 파라미터가 이미 14개(`checkstyle:ParameterNumber` 억제 중)이므로 하나 더
  얹지 말고 훅에 넘기는 값들을 묶을지는 빌더가 판단한다.
- `LlmSkillExecutor:231` 의 `ContextRequest` 는 훅 레지스트리가 없는 passthrough 라 손대지 않는다.

### 4.4 셸 액션 — `skill/hook/declarative/`, `skill/parser/`, `config/hook/`

- `ShellActionExecutor`, `DefaultShellActionExecutor`, `NoOpShellActionExecutor` — §3.2. `HostShellActionExecutor` 신규.
- `AbstractDeclarativeShellHook`, `DeclarativePreToolHook`, `DeclarativePostToolHook` — 호출에 `context` 추가.
- `skill/parser/SkillHookSetParser.java` — §3.4 검사, unknown-event 메시지, `shell` 미지원 메시지의 "Wire … with a
  DefaultShellActionExecutor" 문구는 유지(여전히 참).
- `config/hook/HookRegistryApplier.java` — §3.4 검사. `HookHotReloadBootstrap` 은 시그니처 변화 없음(Javadoc 만).
- `skill/DefaultSkillRegistry.java` — §3.5.
- Javadoc 정리: `MarkdownSkillParser`, `DefaultSkillRegistry:122`, `Adaptive`/`Classpath`/`FileSystemAgentBundleLoader`,
  `OrcaAgentRuntimeFactory`, `DenyAction`, `DeclarativeShellHookBinding` — "호스트 셸"·"`LocalShell`" 서술을 고친다.

### 4.5 조립 — `aimon-bootstrap`, `aimon-cli`

- `AimonStackBuilder.java:259-269` — `LocalShell` 생성과 `teardown.own(SKILL_HOOK_SHELL, …)` 삭제. 기본 파서는
  `new SkillHookSetParser(new DefaultShellActionExecutor())`. 주석("Closed last of everything …")도 삭제.
- `TeardownPhase.java` — `SKILL_HOOK_SHELL` → `HOOK_CONFIG_SHELL` 로 개명, Javadoc 을 "`hooks.json` 선언 훅의 호스트 셸.
  스택 자신은 만들지 않고 `hooks.json` 핫리로드를 물리는 어셈블리(CLI)가 `own` 한다" 로. `HOOK_EXECUTOR` Javadoc 의 참조도.
  순서는 그대로(마지막).
- `AgentSetupFactory.java` — `createShellAwareSkillParser()` 는 인자 없이 환경 실행기를 쓴다. `skillHookShell` →
  `hookConfigShell`, `setupHookHotReload` 가 `new HostShellActionExecutor(hookConfigShell)` 을 쓴다. 실패 경로의
  `closeSuppressing`(630)과 `decorate` 의 `own`(653)은 이름만 바뀐다. 541-543 의 주석("Supplying a parser is also what
  tells AimonStackBuilder not to open a second shell") 은 더는 참이 아니므로 고친다.
- `aimon-spring-boot-starter` — main 에 참조 없음(확인). 문서만.

### 4.6 문서

- `docs/backlog/execution-environment-open-items.md` — EE-9·EE-12 를 **닫힘**으로(각각 `### 닫힘 (날짜)` 절: §2 표, §2 의
  "달랐던 점" 다섯, 테스트 이름). 머리 카운트와 머리말("결정됨이되 열린 항목은 이제 다섯" → 넷, 이 변경 한 줄). 새 항목은
  §8. `docs/backlog/README.md` 의 인덱스 행도 같이.
- `docs/design/tool/execution-environment.md` — §14 머리 인용문(스킬 훅 셸은 **닫혔다**)과 해당 불릿, §10 의 훅 문단, §13
  샌드박스 계약에 "훅 셸 액션은 `shell().execute` 에 `environment`·`stdin` 옵션을 넘긴다" 한 줄, §15 에 "훅 실행기가 셸을
  생성자로 쥐지 말 것".
- `docs/design/tool/execution-environment-implementation.md` — 267 행(`CompactionRequest` 가 서술자가 아니라 환경을 든다),
  1123·1261 행.
- `docs/references/aimon-skill-extensions.md`(142, 171), `docs/features/skill/builtin-agent-skill-guide.md`(197),
  `docs/features/hook/hook-config-guide.md`(224 부근 — `hooks.json` 은 호스트, 스킬 훅은 실행 환경),
  `docs/getting-started/aimon-core-integration-via-cli-reference.md`(570, 855-856, 1163-1171),
  `docs/design/integration/spring-boot-starter.md`(289, 302, 1484), `docs/backlog/spring-boot-starter-open-items.md`.
- **번역본이 있는 것**: `hook-config-guide.en.md`, `builtin-agent-skill-guide.en.md`,
  `aimon-core-integration-via-cli-reference.en.md` — 같은 커밋에서 고치고 `source_commit` 갱신. 백로그·설계 문서·
  `aimon-skill-extensions.md` 는 번역본이 없다. `scope-model.md` 는 고칠 곳이 없다(확인).
  `check-translation-staleness.py`·`check-translation-structure.py`·`check-doc-links.py` 를 돌린다.
- `CHANGELOG.md` — 깨지는 변경(§5).

## 5. 바뀌는 데이터·인터페이스 모양

| 타입 | 변화 | 호환 |
|---|---|---|
| `HookContext` | `+ getExecutionEnvironment()`; `getEnvironmentDescriptor()` 가 파생 | 소스 호환(둘 다 default) |
| `HookEventType` | `+ firesInsideExecution()` | 추가 |
| 열 `*Context.Builder` | `+ executionEnvironment(...)` | 추가 |
| `PreToolContext.Builder` / `PostToolContext.Builder` | `- environmentDescriptor(...)` | **깨짐** |
| `ShellActionExecutor` | `run` 두 개 삭제, `run(action, context, env, stdin)` 과 `requiresExecutionEnvironment()` 추가 | **깨짐** (구현자·호출자) |
| `DefaultShellActionExecutor` | 생성자 `(VirtualShell)` → `()`; 의미가 "환경 셸" | **깨짐** — 옛 동작은 `HostShellActionExecutor` |
| `ContextRequest` / `CompactionRequest` / `SummaryRequest` | `+ Optional<ExecutionEnvironment>` | 추가 |
| `CompactionGuard` | `+ maybeCompact(CompactionGuardRequest)` (default) | 추가. 재정의하지 않은 가드는 환경을 떨군다 |
| `TeardownPhase` | `SKILL_HOOK_SHELL` → `HOOK_CONFIG_SHELL` | **깨짐** (enum 상수) |
| 스킬 훅 셸 액션의 실행 위치 | 호스트 JVM 의 cwd → 실행 환경의 셸(로컬 제공자에서는 워크스페이스가 cwd 인 `WorkingDirectoryShell`) | **행동 변화** — 로컬에서도 cwd 가 바뀐다 |

와이어 형식(frontmatter, `hooks.json`, `AIMON_*` 변수, stdin 페이로드)은 바뀌지 않는다. 새 타입은 모두 불변 class +
builder 다(record 없음). `*.impl` 직접 import 는 생기지 않는다 — 훅·압축·스킬 패키지가 보는 것은
`at.aimon.core.environment.ExecutionEnvironment` SPI 뿐이다.

## 6. 실패 모드

| 상황 | 처리 |
|---|---|
| 실행 밖 이벤트에 스킬 셸 액션 선언 | 파싱 시 `SkillParseException`(스킬 이름·경로·이유). 레지스트리는 그 스킬만 건너뛰고 WARN |
| `shell` 미지원 파서(`NoOp`)에 셸 액션 | 지금과 같음 — 파싱 시 거부 |
| 발화 시점에 컨텍스트에 환경이 **없음** | `DefaultShellActionExecutor` 가 명령을 **돌리지 않고** WARN(스킬·이벤트·명령·"no execution environment in hook context") 후 `ShellHookOutcome.notObserved()`. 호스트 폴백 없음. 훅은 `success` — `preTool` 거부 훅이면 **fail-open** 이다(Q2) |
| 환경이 **사용 불가** | `shell().execute` 가 `ExecutionEnvironmentUnavailableException` 을 던진다. 전용 catch 로 WARN(원인 포함) 후 `notObserved()` |
| 환경 셸이 `stdin`/`environment` 옵션을 지원하지 않거나 던짐 | 기존 사다리(`ShellExecutionException` / `RuntimeException` → WARN, `notObserved()`) |
| timeout, 비정상 종료, exit 2 | 변화 없음 |
| 서드파티 `CompactionGuard` 가 새 메서드를 재정의하지 않음 | 압축 훅 컨텍스트에 환경이 비어 감 → 위 "환경 없음" 행. 조용하지 않다(WARN) |
| 런타임 수준 러너가 스폰한 서브에이전트의 `subagentStart`/`Stop` | 환경 없음 → 같은 행 |
| 스킬 A 파싱 실패 | `getAllSkills`/`reloadAll` 이 A 만 건너뜀. `getSkill("A")` 는 던짐 — `SkillTool.execute` 가 이를 `ToolResult.error` 로 바꾸는지 빌더가 확인(도구는 던지지 않는다) |
| 종료 순서 | `AimonStackBuilder` 스택의 마지막 단계는 이제 `HOOK_EXECUTOR`. 스킬 훅 셸은 환경(제공자 소유, §15 "닫지 말 것")의 것이라 훅 쪽에서 닫지 않는다. CLI 는 `HOOK_HOT_RELOAD` → `HOOK_EXECUTOR` → `HOOK_CONFIG_SHELL` 순서 유지 |
| ArchUnit | `PackageDependencyArchitectureTest` 에 `environment` 의존을 패키지별로 제한하는 규칙이 있다(252-256, 449-461 행). `hook..`·`agent.context`·`agent.compact`·`skill..` 은 이미 `environment` SPI 를 본다. `command.system`(`CompactCommand`)과 `subagent`(매니저)의 새 의존이 규칙에 걸리면 허용 목록을 **이유와 함께** 넓힌다 — 규칙을 우회하지 않는다 |

## 7. 테스트 전략

신규·확장 (단위, `./gradlew :aimon-core:test`):

1. **이벤트별 환경 채움** — 발화 지점마다 기록용 훅을 등록하고 컨텍스트를 붙잡는다.
   - `SingleToolInvokerTest`: 네 이벤트가 `ToolContext` 의 환경과 **같은 인스턴스**를 준다. 키가 없으면 empty.
     서술자가 환경의 것과 같다(파생).
   - `OrcaAgentExecutor*Test`: `onStart`/`onStop` 이 실행의 환경. 오류 종료 경로의 `onStop` 도.
   - `DefaultSubagentExecutor*Test`: `onStart` 와 `onStop` 세 경로(성공·truncated·실패)가 **포크의** 환경.
   - `DefaultSubagentExecutionManager*Test`: `subagentStart`/`Stop` 이 **스폰한 쪽** 환경, 없으면 empty.
   - `DefaultCompactionEngine*Test`: `compact` 와 `summarize`+`summaryInstalled` 양쪽에서 pre/post 가 요청의 환경.
   - `DefaultContextEngineTest`·`RollingContextEngineTest`·`DefaultCompactionGuard*Test`: `ContextRequest` 의 환경이
     AUTO(가드 경유)·MANUAL·view 모드 세 경로 모두에서 엔진 요청까지 도달. 기본 `maybeCompact(CompactionGuardRequest)` 가
     기존 네 메서드로 바르게 위임(4조합).
   - `CompactCommand*Test`: 커맨드 `ToolContext` 의 환경이 압축 훅과 `onStop` 에.
   - 실행 밖: `OnSessionStart`/`OnSessionEnd`/`OnConfigReload` 컨텍스트와 rewake 리플레이 컨텍스트는 empty.
   - `HookEventTypeTest`: 열셋 분류 고정 + `SkillHookSet.supportedEvents()` ⊆ `firesInsideExecution()`.
2. **셸이 환경으로 간다** — `DefaultShellActionExecutorTest`: 가짜 `ExecutionEnvironment` 의 기록용 `VirtualShell` 이
   명령·env·stdin·timeout 을 받는다. 환경 없음 → 셸 호출 0회 + `notObserved`. 사용 불가 환경 → `notObserved`, 던지지 않음.
   exit 2 → `isDenied`. `HostShellActionExecutorTest`: 컨텍스트에 환경이 있어도 **고정 셸**로 간다.
   `AbstractDeclarativeShellHookTest`·`DeclarativePreToolHookTest`·`DeclarativePostToolHookTest`: 훅이 받은 컨텍스트를
   실행기에 그대로 넘긴다. 종단 하나: 포크 모드 스킬의 `preTool` 셸 훅이 **포크 환경**의 셸에서 돈다.
3. **파싱 거부** — `SkillHookSetParserTest`: 세 실행 밖 이벤트가 이유를 담은 메시지로 거부. §3.4 검사 자체는 헬퍼를
   직접 호출해(또는 `requiresExecutionEnvironment()==true` 실행기 + 실행 밖 이벤트로) 검증. `HookRegistryApplierTest`:
   호스트 실행기는 `onSessionStart` 셸을 등록하고, 환경 실행기는 WARN 후 건너뛴다.
4. **격리** — `DefaultSkillRegistryTest`: 깨진 스킬 하나 + 멀쩡한 스킬 둘 → `getAllSkills()` 가 둘을 주고,
   `reloadAll()` 뒤에도 둘이 남으며, `getSkill(깨진 것)` 은 `SkillParseException`. `SkillTool` 설명 생성이 살아 있다.
5. **조립** — `AimonStackBuilderTest`: 계획에 `skillHookShell` 이 없고 마지막 단계가 `HOOK_EXECUTOR`; 기본 파서가 셸
   액션을 받는다(기존 두 테스트 495-526 을 고쳐 쓴다). `AimonStackExtensionPointTest`·`TeardownRegistryTest` 는 개명 반영.
   `aimon-cli`: 스킬 파서가 호스트 셸 없이 만들어지고 핫리로드만 `HostShellActionExecutor` 를 쓴다.

기존 테스트 가운데 시그니처 때문에 고쳐야 하는 것: `NoOpShellActionExecutorTest`, `DeclarativeOnStart/OnStopHookTest`,
`DeclarativeShellHookBindingTest`, `MarkdownSkillParserTest`, `HookConfigHotReloadE2ETest`, `HookHotReloadBootstrapTest`,
`HookRegistryReloaderTest`, `FileSystemAgentBundleLoaderTest`.

게이트: `./gradlew format` → `./gradlew checkAll`. Docker 태그 테스트는 이 변경과 무관하다. 문서 체커 셋.

## 8. 새 백로그 항목 (이 변경 밖으로 번지는 것)

카운트: 지금 47 = 열림 35 · 닫힘 12. EE-9·EE-12 를 닫고 아래 셋을 열면 **50 = 열림 36 · 닫힘 14**.

- **EE-48 — `hooks.json` 선언 훅의 셸은 여전히 호스트다.** Q1 의 귀결. 다시 볼 때: 샌드박스 제공자를 붙일 때.
- **EE-49 — 스킬 훅은 agent-scoped `HookRegistry` 에 등록되어 다른 세션의 실행에서도 발화한다.**
  `RegistryBackedSkillHookActivator` 가 런타임의 레지스트리에 등록하므로(`OrcaSkillToolProvider:78`), 스킬이 활성인 동안
  같은 에이전트의 다른 실행이 낸 이벤트도 그 훅을 친다. 이 변경 전에는 어차피 호스트에서 돌았지만, 이제는 **그 다른
  실행의 환경**에서 돈다. 기존 결함이고 이 변경이 결과를 바꿀 뿐이다.
- **EE-50 — 훅 명령에는 `${AIMON_SKILL_DIR}` 가 없다.** `SkillHookEnv` 에 그 변수가 없어 스킬 훅이 자기 스크립트를
  경로로 부를 수 없다. 호스트에서 돌 때는 호스트 경로를 하드코딩해 우회할 수 있었지만 샌드박스에서는 안 된다. 포크가
  다른 환경에 놓이면 스테이징 사본도 스폰한 쪽에만 있다.

## 9. 열린 질문

- **Q1. `hooks.json` 의 셸을 호스트에 남기는 것이 맞는가.** 과제 문장은 "`ShellActionExecutor` 는 셸을 생성자에서 받지
  않고" 라고만 적는다. 이 설계는 그 문장을 **스킬 갈래**(`DefaultShellActionExecutor`)에 적용하고 `hooks.json` 용으로
  `HostShellActionExecutor(VirtualShell)` 을 남긴다. 수용 기준 2 의 "호스트 `LocalShell` 을 **스킬 훅용으로** 만드는 코드가
  사라진다" 는 충족하지만, CLI 에는 `hooks.json` 용 `LocalShell` 이 남는다. 이것까지 없애라는 뜻이면 실행 밖 이벤트의
  운영자 훅을 어떻게 할지 결정이 필요하다.
- **Q2. 환경이 없을 때 `preTool` 거부 훅이 fail-open 인 것.** 기존 계약("종료 코드를 내지 못한 명령은 거부로 읽지
  않는다")을 따랐다. 가드 용도라면 fail-closed 가 맞을 수 있다. 이 설계는 계약을 바꾸지 않고 WARN 만 남긴다.
- **Q3. `subagentStart`/`subagentStop` 이 싣는 환경은 스폰한 쪽 것.** 훅이 스폰한 실행의 레지스트리에서 발화하고,
  `subagentStart` 시점에는 포크의 환경이 아직 해석되지 않았다. 로컬 제공자에서는 두 환경이 같다. 포크의 환경을 원한다면
  `subagentStop` 만 다르게 하거나 해석 순서를 바꿔야 한다.
- **Q4. 깨지는 변경 넷이 0.x 정책으로 허용되는가** — `ShellActionExecutor` 시그니처, `DefaultShellActionExecutor` 의
  의미 변경(같은 이름, 다른 동작), `Pre/PostToolContext.Builder.environmentDescriptor` 삭제, `TeardownPhase` 상수 개명.
  결정문은 "기존 스킬이 깨지는 것" 만 명시적으로 허용했다. 특히 `DefaultShellActionExecutor` 는 이름을 유지하면 문서의
  "Wire … with a DefaultShellActionExecutor" 가 그대로 참이지만, 옛 생성자를 쓰던 임베더는 컴파일 오류로 알게 된다(조용한
  의미 변경은 아니다). 이름을 `EnvironmentShellActionExecutor` 로 새로 짓는 쪽을 원하면 알려 달라.
- **Q5. `CompactionGuardRequest` 를 들이는 범위.** EE-9 가 요구하는 것은 압축 훅에 환경을 싣는 것뿐인데, 가드
  인터페이스가 위치 인자 4~5개 × 오버로드 4개라 요청 객체 없이는 8개가 된다. 과하다고 보면 대안은
  `maybeCompact`/`forceCompact` 에 nullable 인자 둘을 받는 오버로드 2개다(기존 "executionId 는 non-null" 계약과 어긋난다).
- **Q6. `DefaultSkillRegistry` 의 catch 를 넓히는 것이 이 PR 의 범위인가.** 수용 기준 3 이 "확인" 을 요구했고 확인 결과
  구멍이 있다. 고치는 쪽으로 설계했지만 기존 동작(파싱 오류가 `getAllSkills` 에서 전파)에 기대는 테스트·임베더가 있는지는
  빌드 때 드러난다.
- **Q7. 새 백로그 번호 EE-48~50** 은 이 run 과 병행하는 다른 run 이 없다는 전제다(과제가 (2)·(3) 을 뒤로 미뤘다).

## 10. 구현이 이 설계에서 벗어난 점

기준은 위 본문(승인본)이다. 줄 번호는 2026-10-03 에 구현 브랜치(`herdr/ee9-ee12-hook-env-shell`)에서 확인했다.

### 10.1 설계의 서술이 틀렸던 곳

- **`agent.compact` 는 `environment` SPI 를 볼 수 없었다.** §6 의 ArchUnit 행은 "`agent.context`·`agent.compact` 는 이미
  `environment` SPI 를 본다" 고 적었고 설계 리뷰도 그렇게 확인했다. 근거로 든
  `onlyAgentContextAndCompactMayReachEnvironmentFromAgentTree` 는 실제로 `agent.compact` 를 예외로 둔다. 그러나
  `agent.compact` 에는 규칙이 하나 더 있었다 — `PackageDependencyArchitectureTest.agentCompactMayDependOnExtHook` 의
  **허용 목록**이고 거기에는 `environment` 가 없다. `CompactionRequest`·`SummaryRequest`·`CompactionGuardRequest`·
  `DefaultCompactionEngine`·`DefaultCompactionGuard` 가 `ExecutionEnvironment` 를 들자 15건으로 실패했다. 허용 목록에
  **`ExecutionEnvironment` 한 타입만 이름으로** 더했다. SPI 패키지 전체를 열지 않은 이유는 compact 가 참조를 훅
  컨텍스트로 옮기기만 하고 파일 시스템·셸·서술자를 읽지 않기 때문이고, 그 이유를 규칙 주석과 `@DisplayName` 에 적었다.
  §6 의 지침("걸리면 허용 목록을 이유와 함께 넓힌다 — 규칙을 우회하지 않는다")은 그대로 따랐다. 설계가 걸릴 것으로 본
  `command.system`·`subagent` 는 걸리지 않았다.
- **§2 표의 `(:404)`.** `subagentStart`/`subagentStop` 행이 가리키려던 것은 `DefaultSubagentExecutionManager` 의 404행이
  아니라 `SubagentExecutionEnvironment.getExecutionEnvironment()`(406행)다. 본문은 그대로 두었고 백로그 닫힘 절에는 고친
  참조를 썼다.
- **§4.6 의 문서 줄 번호**는 기준 커밋의 것이어서 그대로 쓰지 않고 해당 문단을 찾아 고쳤다. 두 곳은 목록과 다르게 했다.
  `execution-environment-implementation.md` 는 본문(§0~§9)을 승인본 그대로 두는 문서이므로 267·1123행은 고치지 않고 §10 의
  EE-9 항목에 "Since closed" 문단을 더해 거기서 정정했다. 그리고 목록에 없던 `docs/features/hook/hook-development-guide.md`
  (와 번역본)의 `HookContext` 공통 필드 표에 새 접근자 둘을 더했다.

### 10.2 반영한 리뷰 지적 (설계를 바꾼다)

- **환경 실행기에서는 `hooks.json` 셸 핸들러의 `asyncRewake` 를 떨군다.** §3.4 의 안전판은 실행 밖 이벤트의 셸 핸들러만
  건너뛰었다. rewake 리플레이는 `preTool`·`preCompact`(실행 안 이벤트)에서도 환경 없이 발화하므로 그 안전판을 통과한 뒤
  매 리플레이가 조용히 건너뛰어진다. `HookRegistryApplier.toRewakeSpec` 이 셸 액션이고 실행기가
  `requiresExecutionEnvironment()` 이면 WARN 후 spec 을 떨군다(기존 "WARN 후 drop" 관례). 훅 자체는 등록된다. CLI 는
  호스트 실행기를 쓰므로 영향이 없다.
- **ArchUnit 규칙 `skillHooksHoldNoShell`.** `at.aimon.core.skill.hook..` 와 `at.aimon.core.skill.parser..` 의 어떤 클래스도
  `VirtualShell` 을 필드나 생성자 인자로 갖지 못한다. 예외는 이름으로 하나, `HostShellActionExecutor`. §4.6 이 명세 §15 에
  더하라고 한 "훅 실행기가 셸을 생성자로 쥐지 말 것" 이 빌드에서 강제된다.
- **`firesInsideExecution()` 은 보장이 아니라 분류다.** Javadoc 에 "라이브 발화 지점의 분류이며 rewake 리플레이와 손으로
  만든 컨텍스트는 예외" 를 적어 §6 의 발화 시점 처리와 짝을 맞췄다.
- **종료 순서에서 잃는 보장.** §6 의 "종료 순서" 행은 잃는 것을 적지 않았다. 옛 `SKILL_HOOK_SHELL` 은 뒤 단계 teardown
  중에 발화하는 셸 훅을 위해 맨 마지막에 닫혔다. 환경 셸은 제공자 소유라 `AGENT_RESOURCES` 에서 닫히므로, 그 뒤 단계
  (`SCHEDULING`·`REWAKE`)에서 아직 도는 루틴의 스킬 셸 훅은 닫힌 셸을 만나 WARN 으로 끝난다. 도구도 같은 처지다.
  `TeardownPhase.HOOK_CONFIG_SHELL` 의 Javadoc 에 적었고, 닫힌 셸에서 던지지 않고 `notObserved()` 로 끝나는 것을
  `DefaultShellActionExecutorTest.run_closedShell_reportsNotObservedWithoutThrowing` 이 고정한다.
- **fail-open 의 범위와 cwd 변화를 행동 변화로 적었다.** CHANGELOG 에 별도 줄로, 그리고
  `docs/references/aimon-skill-extensions.md` 의 훅 절과 백로그 닫힘 절에.

### 10.3 설계가 빌더에게 맡긴 것의 선택

- **거부 규칙의 자리.** §3.4 의 규칙 한 줄을 `ShellActionExecutor.canRunOn(HookEventType<?>)`(default 메서드)로 인터페이스에
  두고 `SkillHookSetParser` 와 `HookRegistryApplier` 가 부른다. 두 프런트엔드의 조건식이 어긋날 수 없다.
- **`SkillHookSetParser.requireRunnableOn(executor, eventType, path)`** 는 패키지 전용 static 이다. 오늘은 unknown-event
  검사가 먼저 걸려 `parse()` 로는 도달할 수 없으므로 테스트가 직접 부른다(§7.3 이 허용한 방법).
- **포크의 환경**은 `LoopContext` 에 필드를 더하지 않고 `lc.toolContext` 에서 읽는 접근자
  `LoopContext.executionEnvironment()` 로 얻는다. 훅과 도구가 다른 인스턴스를 받을 수 없다. `onStop` 세 곳의 조립은
  합치지 않았다.
- **`DefaultCompactionEngine.generateSummary`** 는 인자를 묶지 않고 하나 더했다(기존 `ParameterNumber` 억제 아래).
  `DefaultCompactionGuard.invokeEngine` 은 8개가 되어 같은 억제를 붙였다. 묶는 리팩토링은 이 변경의 범위를 넘는다.
- **실행 사다리 헬퍼**는 `ShellActionRunner`(패키지 전용). 사용 불가 환경의 `shell()` 은 프록시를 돌려주고 `execute` 에서
  던지므로 `ExecutionEnvironmentUnavailableException` 전용 catch 는 헬퍼 안에 있다. `DefaultShellActionExecutor` 는
  `shell()` 호출 자체가 던지는 경우도 따로 받는다.
- **`maybeCompact(CompactionGuardRequest)` 의 위치**는 checkstyle(`OverloadMethodsDeclarationOrder`)이 정했다 — 인터페이스와
  구현 모두에서 나머지 `maybeCompact` 옆이다.

### 10.4 새 백로그 항목은 셋이 아니라 다섯이다

§8 은 EE-48~50 을 열라고 했다. 설계 리뷰가 Q2 를 "사람이 답해야 하는 질문" 이라고 했으므로 Q2(환경이 없거나 사용 불가면
스킬 가드 훅이 fail-open 이다)를 **EE-51** 로, 샌드박스 제공자에서 결과가 달라지는 Q3(`subagentStart`/`subagentStop` 은 스폰한
쪽 환경을 싣는다)를 **EE-52** 로 올렸다. 카운트는 §8 의 "50 = 열림 36 · 닫힘 14" 가 아니라 **52 = 열림 38 · 닫힘 14** 다.
EE-49 의 "언제 다시 볼까" 는 리뷰대로 "샌드박스 제공자를 붙이기 **전**" 이다. Q4 는 CHANGELOG 에 깨지는 변경으로 적는 것으로
답했고(0.x 정책), Q5·Q6 은 이 변경 안에서 끝났으며, Q7 의 전제(병행 run 없음)는 착수 시점에 최고 번호가 EE-47 인 것으로
확인했다 — 셋은 올리지 않았다.

### 10.5 쓰지 않은 테스트

§7 이 꼽은 것 가운데 넷은 다르게 덮었거나 덮지 못했다.

- **`OrcaAgentExecutor` 의 오류 종료 경로 `onStop`.** `invokeOnStop(scope, …)` 한 메서드를 아홉 호출부가 공유하고, 성공
  경로는 `HookFiringIntegrationTest.inExecutionHooksCarryTheTurnsExecutionEnvironment` 가 실제 턴으로 덮는다. 오류 경로를
  따로 돌려 보지는 않았다.
- **CLI 핫리로드가 `HostShellActionExecutor` 를 쓴다.** `setupHookHotReload` 는 `AgentSetupFactory.create()` 안에서만
  닿고 `create()` 는 스택 전체를 띄운다. 코드로만 확인했다. 스킬 파서 쪽은 `AgentSetupFactorySkillHookShellTest` 가 덮는다
  (그러려고 `createShellAwareSkillParser()` 를 패키지 전용으로 넓혔다).
- **`AimonStackBuilderTest` 의 "기본 파서가 셸 액션을 받는다".** 스택이 스킬 파서를 밖으로 내주지 않는다. 대신 계획에
  셸이 없다는 것(`AimonStackExtensionPointTest.theStackOpensNoSkillHookShell`)과 같은 생성식을 쓰는 CLI·코어 파서를 확인했다.
- **"포크 모드 스킬" 종단 테스트**는 스킬 활성화까지 거치지 않는다. 포크 실행기에 `DeclarativePreToolHook` +
  `DefaultShellActionExecutor` 를 파서가 만드는 모양 그대로 등록하고, 스폰한 쪽과 포크가 서로 다른 환경을 받는 제공자로
  돌린다(`DefaultSubagentExecutorHookEnvironmentTest.aSkillShellHookRunsInTheForksShell`).

### 10.6 EE-50 — 스킬 훅은 `AIMON_SKILL_DIR` 를 받는다 (2026-10-05)

§8 이 "이 변경 밖으로 번지는 것" 으로 올린 EE-50 을 구현했다. §8 의 문장은 그 시점 기록으로 두고 지금의 사실을 여기 적는다
(열림/닫힘의 정본은 백로그 등록부다).

- **무엇이 바뀌었나.** 스킬이 선언한 셸 훅의 환경에 `AIMON_SKILL_DIR` 가 생겼다(stdin JSON 의 `skill_dir`). 값은 훅이
  발화한 실행의 `ExecutionEnvironment.stage(...)` 가 돌려준 경로다. `bash "$AIMON_SKILL_DIR/scripts/guard.sh"` 가 명령이
  실제로 도는 환경의 사본을 가리킨다.
- **이벤트마다 어느 환경인가.** §2 표의 "안" 인 열 이벤트 전부에서 답이 같다 — 명령은 `context.getExecutionEnvironment()`
  의 셸에서 돌고(§3.2), 스테이징도 **같은 객체**에 한다. `subagentStart` · `subagentStop` 은 스폰한 쪽 실행의 환경에서
  돌므로 거기에 스테이징한다. 발화 시점에 그 환경에 사본이 있으리라는 보장은 어느 이벤트에도 없다(스킬을 호출한 쪽이
  스테이징한 곳은 **호출한 실행의** 환경이다). 그래서 물려받지 않고 매번 `stage()` 를 부른다 — 이미 있으면 마커 확인 한
  번이다.
- **스킬의 자원을 훅에 어떻게 닿게 했나.** 훅 객체는 스킬을 파싱할 때 만들어지고 `StagedResource` 는 그 뒤 레지스트리가
  싣는다. 훅을 다시 만들지 않고, 활성화가 fork 에 넘기는 `SkillScopedHookRegistry` 에 자원을 함께 싣는다. 훅은 발화할 때
  `context.getHookRegistry()` 에서 **자기 자신(동일성)** 이 든 층을 찾아 그 자원을 얻는다. 이름으로 찾지 않는 이유:
  `hooks.json` 훅의 가짜 스킬 이름이 활성 스킬과 겹쳐도 그 스킬의 디렉터리를 받으면 안 된다.
- **변수가 없는 경우**(빈 값이 아니라 unset). `hooks.json` 훅, `StagedResource` 가 없는 손조립 스킬,
  `requiresExecutionEnvironment() == false` 인 실행기(컨텍스트의 환경이 아닌 곳에서 도므로 그 환경의 경로를 주지 않는다),
  환경이 없는 컨텍스트(실행기가 어차피 `NO_ENVIRONMENT` 로 거절한다).
- **스테이징 실패.** 명령을 돌리지 않고 `ShellHookOutcome.notRun(Unrun.STAGING_FAILED, …)` 을 낸다. 가드 이벤트(`preTool` ·
  `onStart` · `preCompact` · `permissionRequest`)는 EE-51 · EE-70 의 규칙 그대로 거부하고 `failOpen` 이면 통과한다. 관찰
  이벤트는 WARN 만 남긴다. 어느 쪽이든 명령은 돌지 않는다 — 변수 없이 돌리면 `/scripts/guard.sh` 를 부르는 다른 명령이
  되고, 그 exit 127 은 가드에서 "허용" 으로 읽힌다(EE-20 과 같은 모양). 사유에는 `StagingException` 의 메시지를 싣는다
  (`Skill` 도구가 같은 실패에 모델에게 주는 문장이다). 그 밖의 예외는 타입 이름만 싣는다.
- **자르지 않는다.** `SkillHookEnv.truncateValue`(2000자)는 모델·사용자가 쓴 글을 위한 것이다. 경로를 자르면 다른 경로가
  되므로 적용하지 않는다.
- **EE-66 에 미치는 영향.** 종료 코드 계약은 그대로다(126 · 127 은 허용). 다만 실제로는 좁아졌다 — 전에는 스킬 훅이 자기
  스크립트를 부를 안정된 경로가 없어 127 이 흔한 결과였고, 이제 127 은 스크립트 이름을 틀렸거나 인터프리터가 없을 때만
  난다. *(2026-10-05, EE-66 — 계약이 바뀌었다. 가드 이벤트에서 126 · 127 은 "돌리지 못함" 이라 막고 `failOpen` 이면 통과한다.
  위 "스테이징 실패" 항목의 "그 exit 127 은 가드에서 허용으로 읽힌다" 도 이제 사실이 아니다 — 막히지만 사유가 스테이징
  실패가 아니라 "command not found" 가 되므로, 명령을 돌리지 않는 쪽이 여전히 맞다.)*
- **남은 것.** 스킬 **본문**의 `${AIMON_SKILL_DIR}` 는 여전히 호출한 실행의 환경에 스테이징한 경로다. fork 가 다른 환경에
  놓이면 본문 속 경로는 fork 에 없다 — 이 변경은 훅만 고쳤다. 돌려서 확인했다: fork 를 다른 로컬 작업 공간에 놓으면 본문은
  `<호출한 쪽>/.aimon-staged/<스킬>/<키>` 로, 같은 fork 의 훅은 `<fork 쪽>/.aimon-staged/<스킬>/<키>` 로 렌더된다.
- **테스트.** `SkillHookSkillDirIntegrationTest`(실제 조립: 스킬 레지스트리 → `Skill` 도구 → fork → 훅 → 로컬 환경) 넷,
  `SkillHookDirectoryTest` 열다섯.
