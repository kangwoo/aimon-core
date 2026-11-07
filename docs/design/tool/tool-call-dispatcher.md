# 도구 호출의 단일 입구 — `ToolCallDispatcher` 와 호출 경로

> **Status**: IMPLEMENTED — `aimon-core` (`at.aimon.core.agent.tool`, `at.aimon.core.toolinvocation`, `at.aimon.core.skill.execution`,
> `at.aimon.core.hook.event`, `at.aimon.core.skill.hook.declarative`). 공개 API 호환을 깨는 항목이 있다(§6). 남은 것은 §8 이다.

## 1. 목적과 범위

모델이 고른 도구 호출은 관문 — 허용 목록, 실링, PermissionRequest 훅, 승인 관문, PreTool 훅, 실행, PostTool 훅 — 을 지나야 한다.
그 관문 앞에 서는 루프는 셋이다 — 메인 루프, 포크 루프, 사용자가 친 슬래시 명령의 스킬 루프. 이 문서는 세 루프가 관문에 닿는
입구를 `ToolCallDispatcher` 하나로 두고, 입구가 없을 때 관문 없이 실행하는 길을 두지 않으며, 호출이 어떤 경로로 왔는지
(`ToolCallOrigin`)를 훅이 볼 수 있게 하는 결정을 담는다. [프로그래매틱 도구 호출](programmatic-tool-calling.md) 이 이 입구로 코드의 도구
호출을 보내지만, 이 문서는 그것 없이도 성립한다.

| 주제 | 정본 |
|---|---|
| 관문의 순서, 스키마 검증 | [도구 계약](tool-contract.md) |
| 부작용 축, 실링, 승인 관문 | [부작용 축](side-effect-axes.md) |
| 병렬 실행 게이트 | [병렬 실행](parallel-execution.md) |
| 훅 이벤트와 컨텍스트 | [훅 시스템](../hook/hook-system.md) |
| 환경 알림의 드레인과 렌더링 | [실행 환경](execution-environment.md) §10 |

---

## 2. 경로

| 경로 | 입구 | 관문 |
|---|---|---|
| 메인 루프 (`OrcaAgentExecutor.executeToolUses`) | `ParallelToolDispatcher` 가 배치를 나눈 뒤 호출마다 턴 컨텍스트의 `ToolCallDispatcher` | 지난다 |
| 포크 루프 (`DefaultSubagentExecutor`) | 포크 자신의 컨텍스트에 묶은 `ToolCallDispatcher` | 지난다 |
| 슬래시 명령의 스킬 루프 (`LlmSkillExecutor`) | 커맨드 컨텍스트의 `ToolCallDispatcher` 를 스킬에 맞춰 `narrow` 한 것. 없으면 실행 거절 | 지난다 |
| rewake 리플레이 (`DefaultRewakeFireListener`) | 도구를 실행하지 않는다. 기록된 호출에 대해 PreTool 훅만 다시 발화하고, 그 컨텍스트의 경로는 `REPLAY` | 해당 없음 |
| 루틴 스텝 (`RoutineExecutor`) | `Tool#execute` 직접 | 건너뛴다 — `ToolExecutionGateArchitectureTest` 의 기록된 예외 |
| 메모리 deriver (`ReActLlmDeriver`) | `Tool#execute` 직접 | 건너뛴다 — 기록된 예외 |
| `ArtifactAware{Edit,Write}Tool`, `RecentFilesRestoreHook` | 위임 실행 | 건너뛴다 — 기록된 예외 |

관문을 지키는 ArchUnit 규칙은 둘이다. **스키마 관문** — `Tool#execute` 는 `DefaultToolExecutor` 와 기록된 예외만 부른다 — 은
`ToolExecutionManager` 를 직접 부르는 호출자를 보지 못한다. 그 호출자는 스키마 검증을 지나면서 훅과 승인을 모두 건너뛴다. 그래서
**입구 규칙**(§3 #8)이 그 아래를 막는다.

모델이 부르는 `Skill` 도구는 `LlmSkillExecutor` 를 쓰지 않는다(인라인은 내용 주입, 포크는 서브에이전트). 스킬 루프는 사용자가 친
`/my-skill` 에서만 돈다. 그래서 스킬 루프 위에는 그것을 감싸는 도구 호출도, ReAct 루프가 묶은 디스패처도 없고, 에이전트 실행기가
커맨드 컨텍스트를 손으로 지으면서 디스패처를 넣는다.

---

## 3. 설계 결정

| # | 쟁점 | 결정 | 이유 | 기각한 대안 |
|---|---|---|---|---|
| 1 | 입구 | `ToolCallDispatcher` — 실행 루프가 만들어 `ToolContext` 에 묶는 바인딩. 인터페이스는 `agent.tool`, 관문을 지나는 구현 `DefaultToolCallDispatcher` 는 `toolinvocation`. 메인·포크 루프와 슬래시 명령의 스킬 루프가 이것을 부른다 | 세 루프의 `ToolInvocationSpec` 조립이 구현 하나에 모인다. 구현이 훅·`ToolContextKeys` 에 의존하므로 `SingleToolInvoker` 와 같은 이유로 `agent..` 밖에 둔다(`PackageDependencyArchitectureTest`) | 선택 키 하나 더(스킬 루프 전용 디스패처 키): "없으면 관문 없이" 라는 폴백이 또 하나의 우회로가 된다 |
| 2 | 묶이지 않았을 때 | 소비자는 거절한다(fail-closed). 스킬 루프는 첫 LLM 호출 전에 스킬을 실패시키고, 메인·포크 루프는 그 호출에 오류 결과를 준다. 관문 없는 실행이 필요한 테스트와 임베더는 이름에 드러나는 `ToolCallDispatchers.ungated(executionManager, registry)` 를 명시적으로 고른다. `ungated` 는 허용 목록과 매니저의 실링을 적용하고 환경 알림도 비우지만, 훅은 하나도 발화하지 않고 승인을 묻지 않는다 | 관문 없는 실행은 선택이어야 하지 기본값이면 안 된다. 실행 매니저 하나만으로는 레지스트리를 알 수 없으므로 `ungated` 는 레지스트리를 함께 받는다 | 매니저 직행 폴백 유지: 슬래시를 치는 것이 에이전트라면 물었을 도구를 묻지 않고 실행하는 길이 된다 |
| 3 | 바인딩에 고정하는 것 | 레지스트리, 세션 레지스트리, 허용 목록, `SingleToolInvoker`, 훅 레지스트리, 실행 속성, 인터럽트 코디네이터, `InvokerType`·이름. 호출마다 받는 것은 `ToolUse`, 호출 경로, 컨텍스트. 턴 안에서 바뀌는 iteration 번호는 write-once 가 아닌 컨텍스트 키 `ToolContextKeys.ITERATION_COUNT` 로 싣고, 없으면 `0` 으로 읽는다 | 루프마다 다른 것은 만들 때 정해지고, 호출마다 다른 것만 인자로 남는다. PreTool/PostTool 훅은 iteration 번호를 받아야 하는데, 인자로 올리면 코드 경로처럼 iteration 이 없는 소비자도 값을 지어내야 한다 | 모든 것을 호출마다: `ToolInvocationSpec` 을 그대로 SPI 로 올리는 것이다 |
| 4 | 배치와 스트리밍 | 디스패처 밖, 루프에 남긴다 — 메인 루프의 스트리밍 선실행(`StreamingToolScheduler`)·harvest, 포크의 배치 전 쿼터 승인, 병렬 게이트 | 루프마다 의미가 다르다. 루프는 배치를 나눈 뒤 호출마다 `dispatch` 를 부른다 | `dispatchAll`: 세 루프의 차이를 옵션으로 흡수해야 한다 |
| 5 | 좁히기 | `narrow(registry, allowedTools)` — 새 레지스트리의 모든 도구가 원래 레지스트리에 같은 이름의 같은(`equals`) 도구로 있어야 하고, 허용 목록은 `AllowedTools.intersect`. 교집합이 비면(서로소) `IllegalArgumentException` — 빈 목록은 "무제한" 으로 읽히기 때문이다. 좁힌 디스패처는 새 레지스트리에서 인터럽트 동작도 읽는다(원래 레지스트리와 같은 인스턴스면 원래 세션 레지스트리를 유지). `ungated` 도 같은 검사를 공유한다(`ToolCallNarrowing`) | 슬래시 명령의 스킬 루프가 스킬 레지스트리와 스킬 `allowed-tools` 로 좁힌다. 원래 허용 목록이 비어 "무제한" 이어도 레지스트리가 넓어지면 안 된다. 같은 이름의 다른 구현은 원래 없던 도구다 | 레지스트리 교체 허용: 원래 없던 도구가 실행된다. 빈 교집합을 빈 목록으로: 가장 엄격한 짝이 가장 느슨한 것으로 뒤집힌다 |
| 5a | 좁히기는 programmatic 설정도 나른다 | `narrow(registry, allowedTools, programmatic)` — 좁힌 디스패처가 호출마다 `ToolContextKeys.CALLER_PROGRAMMATIC` 으로 싣는 설정은 `programmatic.narrowedBy(이 디스패처의 설정)` 이다. 결과가 비면 `none()` 이고 오류가 아니다 — 빈 허용 목록과 달리 `none()` 은 "아무것도 없음" 으로만 읽힌다. `DefaultToolCallDispatcher.Builder.programmatic` 은 필수다 | 스킬 안에서 띄운 포크는 스킬의 실효 설정으로 좁혀져야 한다([PTC](programmatic-tool-calling.md) §21 B-DV1). 그 값이 디스패처에 있고 스펙은 `dispatch` 한 곳에서만 지어지므로, 스킬 루프에 닿는 길은 좁히기뿐이다 | 호출자의 설정을 그대로 둔다: PTC 없는 스킬이 포크에게 호출자의 PTC 를 준다. 스킬이 컨텍스트 키를 따로 싣는다: 디스패처가 이미 가진 값에 두 번째 출처가 생기고 아무 도구나 그 키를 실을 수 있다. 2-인자 `narrow` 를 기본 메서드로 남긴다: 이 디스패처의 설정을 남기면 위 누수, `none()` 을 넣으면 기존 호출자의 포크가 조용히 바뀐다 |
| 6 | 호출 경로 | `ToolCallOrigin` (`DIRECT` / `CODE` / `SKILL` / `REPLAY`)을 호출마다 받아 PermissionRequest·PreTool·PostTool 훅 컨텍스트에 싣는다. `PermissionDeniedContext` 에는 싣지 않는다. `InvokerType` 과는 직교한다 | 훅이 "코드에서 온 `Bash` 는 막는다" 같은 정책을 쓸 수 있어야 하고, 슬래시 명령의 스킬 경로는 `InvokerType` 만으로는 `MAIN_AGENT` 와 구분되지 않는다. `InvokerType` 은 누가, 경로는 어떤 경로로다. PermissionDenied 는 결정이 끝난 뒤의 알림 체인이라 경로에 따라 갈라질 정책이 없다 | `InvokerType` 확장: 두 축이 섞인다. 바인딩에 고정: 한 바인딩이 여러 경로를 섬긴다(포크의 디스패처는 포크 루프와 그 루프가 돌리는 코드 둘 다) |
| 7 | 결과 | `ToolDispatchResult` — 원래 호출의 `toolUseId`, 텍스트, 알림·훅 피드백(문자열 목록), 오류·재시도·렌더 페이로드. `ToolUseResult` 로의 변환(`ToolDispatchResults.toToolUseResult`)은 루프가 트랜스크립트에 넣을 때 한 번 — 피드백은 뒤에 `<system-reminder>` 블록으로, 알림은 앞에 `[environment]` 줄로 붙인다 | 알림·피드백을 결과 텍스트에 붙이는 것은 결과를 모델에게 넘기는 루프의 몫이다. 다른 소비자(코드 호출)는 그것을 따로 모은다 | `ToolUseResult` 반환: 붙인 뒤에는 떼어 낼 수 없다 |
| 8 | 강제 | ArchUnit — `ToolExecutionManager.execute`·`executeAll` 의 호출과 메서드 참조는 `at.aimon.core.toolinvocation..` 에서만. 호출 지점 허용 목록은 없다. `ToolExecutionManager` 구현 클래스 자신은 자기 오버로드끼리 위임하므로 면제한다 | 스키마 관문 규칙이 잡지 못하는 우회를 잡는다. 허용 목록을 두면 다음 예외가 한 줄로 들어온다 | 규약만으로: 다음 우회가 조용히 생긴다 |

---

## 4. 인터페이스

```java
public interface ToolCallDispatcher {
    /** Never throws. Denials, unknown tools and failures come back as error results. */
    ToolDispatchResult dispatch(ToolUse toolUse, ToolCallOrigin origin, ToolContext context);

    /**
     * Can only do less: every tool in the new registry must exist in this one; the allow-list is intersected, and so
     * are the programmatic settings published to every call (CALLER_PROGRAMMATIC) — an empty result is none().
     */
    ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools,
            ProgrammaticToolSettings programmatic);
}
```

| 지점 | 디스패처 |
|---|---|
| 메인 루프 | 턴 컨텍스트를 지을 때 넣는다. 에이전트 레지스트리·허용 목록·programmatic 설정, 턴의 세션 레지스트리, `MAIN_AGENT` |
| 포크 | 컨텍스트를 새 빌더로 지을 때 자기 것을 넣는다. 서브에이전트 레지스트리·허용 목록, 호출자로 좁힌 실효 programmatic 설정, `SUBAGENT`. 부모의 것은 물려받지 않는다 |
| 슬래시 명령의 스킬 루프 | 에이전트 실행기가 커맨드 컨텍스트에 넣은 디스패처(에이전트의 관문, `MAIN_AGENT`. 턴 밖이라 세션 레지스트리 자리에 에이전트 레지스트리)를 스킬 루프가 `narrow(스킬 레지스트리, 스킬 allowed-tools ∩ 호출자 허용 목록, 스킬 자신의 programmatic 설정 ∩ 호출자)` 한다. 스킬의 `allowed-tools` 가 제안 필터만이 아니라 실행 시점에도 강제된다. 스킬 루프가 도구에 건네는 컨텍스트에는 원래 디스패처 대신 좁힌 디스패처가 들어가, 더 디스패치하는 도구도 스킬의 범위에 묶인다. `LlmSkillExecutor` 는 `SingleToolInvoker` 를 모른다 |

`ToolContextKeys.TOOL_CALL_DISPATCHER` 는 write-once 키다(`ToolContextKey.BUILT_IN_WRITE_ONCE_NAMES`, `ToolContextKeysWriteOnceTest`).
메인·포크 루프는 그것을 enricher 보다 먼저 넣으므로 `ToolContextEnricher` 가 관문을 바꿔 끼울 수 없다. 다시 묶어야 하는 지점은 없다 —
포크와 스킬 루프는 컨텍스트를 새 빌더로 짓는다.

### 4.1 `ToolDispatchResult`

| 필드 | 내용 |
|---|---|
| `toolUseId` | 원래 호출의 id. `ToolUseResult` 로 바꿀 때 쓴다 |
| `text` | 텍스트 결과. PostTool 훅이 고치는 값 |
| `notices`, `hookFeedback` | 문자열 목록. 직접 경로에서는 루프가 `ToolUseResult` 로 바꿀 때 알림은 앞에, 피드백은 뒤에 붙인다 |
| `isError`, `retryable`, `renderPayload` | `ToolUseResult` 의 같은 이름 값. 변환에서 그대로 옮긴다 |

### 4.2 `ToolCallOrigin`

```java
public enum ToolCallPath { DIRECT, CODE, SKILL, REPLAY;  public String wireName(); }   // "direct", "code", ...

public final class ToolCallOrigin {   // 정적 팩토리 — ToolResult 선례. 경로마다 하나라 경로와 맞지 않는 부모를 짝지을 수 없다
    public static ToolCallOrigin direct();
    public static ToolCallOrigin code(String parentToolUseId, String language);
    public static ToolCallOrigin skill(String skillName);
    public static ToolCallOrigin replay();   // DefaultRewakeFireListener 의 rewake 리플레이
    public ToolCallPath getPath();
    public Optional<String> getParentToolUseId();   // CODE 에서만
    public Optional<String> getLanguage();          // CODE 에서만
    public Optional<String> getSkillName();         // SKILL 에서만
}
```

셸 훅(preTool, postTool, permissionRequest)에는 `AIMON_TOOL_PATH` (`ToolCallPath.wireName()`) 와 `AIMON_TOOL_PARENT_ID` 를 넘긴다.
`AIMON_TOOL_PARENT_ID` 는 `code` 가 아닌 경로에서 비우지 않고 빈 문자열로 넘겨, 스크립트가 어느 경로에서든 같은 방식으로 읽는다.

---

## 5. 경로별 동작

| 경로 | 동작 |
|---|---|
| 메인 루프 | 바인딩한 `ToolCallDispatcher` 를 컨텍스트에서 꺼내 `DIRECT` 로 부르고 결과를 변환한다. 컨텍스트에 없으면 그 호출에 거절 오류 결과 — 턴은 언제나 묶으므로 손으로 지은 컨텍스트에서만 닿는다 |
| 포크 루프 | 같음 |
| 슬래시 명령의 스킬 루프 | `narrow` 한 디스패처만, `SKILL(스킬 이름)` 으로. 없으면 첫 LLM 호출 전에 스킬 실패. `narrow` 가 거절하면 — 레지스트리가 넓어지거나(원래 레지스트리에 없는 도구, 같은 이름의 다른 도구), 허용 목록이 서로소라 교집합이 비거나(`ToolCallNarrowing.intersect`, 결정 5) — 같은 실패. 허용 목록 밖 호출과 권한 위반은 그 호출 하나가 오류 결과를 받고 스킬은 계속한다 — 에이전트 자신의 루프와 같은 의미다 |
| rewake 리플레이 | 도구를 실행하지 않고 PreTool 컨텍스트를 `REPLAY` 로 지어 훅만 발화한다 |
| 루틴 스텝, 메모리 deriver, 위임 실행 | 그대로(스키마 관문 규칙의 기록된 예외). 디스패처를 묶지 않는다 |

---

## 6. 호환을 깨는 변경

| 변경 | 전 | 후 |
|---|---|---|
| 스킬 디스패처 흡수 | `SkillToolDispatcher`, `ToolContextKeys.SKILL_TOOL_DISPATCHER_KEY`, 없으면 `ToolExecutionManager.executeAll` 로 폴백 | 삭제. 스킬 루프는 `ToolContextKeys.TOOL_CALL_DISPATCHER` 만 읽고, 없으면 거절한다. 권한 위반이 스킬 전체의 "Permission denied" 실패가 아니라 그 호출의 오류 결과가 된다 |
| 훅 컨텍스트에 호출 경로 | 없음 | `PreToolContext` · `PostToolContext` · `PermissionRequestContext` 의 빌더가 `origin(ToolCallOrigin)` 을 필수로 받는다. 직접 짓는 호출부(`DefaultRewakeFireListener`, 훅을 직접 시험하는 코드)도 바뀐다 |
| `SingleToolInvoker.invoke` | `ToolUseResult` 반환, 피드백과 알림을 붙인 채 | `ToolDispatchResult` 반환, 피드백과 알림은 옆 목록. `ToolInvocationSpec` 은 `origin` 을 필수로 받는다 |
| 루프의 도구 실행 | 각 루프가 `ToolInvocationSpec` 을 조립 | 디스패처 구현 하나 |
| 좁히기가 programmatic 설정을 나른다(결정 5a, PTC 기능 마무리 PR 2) | `narrow(registry, allowedTools)` | `narrow(registry, allowedTools, programmatic)` — 2-인자 형은 없어졌다(기본 메서드로도 남기지 않는다). 구현은 인자를 더하고 자기 설정보다 넓은 설정을 싣지 않는다. programmatic 설정을 넘길 뜻이 없는 호출자는 `ProgrammaticToolSettings.none()`. `DefaultToolCallDispatcher.Builder.programmatic(...)` 은 새로 생겼고 필수다(없으면 `build()` 가 던진다). `ToolCallDispatchers.ungated(manager, registry, programmatic)` 이 새로 생겼고, 2-인자 `ungated` 는 `none()` 을 싣는다 |

---

## 7. 보장과 그것을 지키는 테스트

| 보장 | 테스트 |
|---|---|
| 디스패처는 어떤 바인딩 모양(`InvokerType`, 좁히기 여부)으로 지어져도 같은 관문을 같은 순서로 지난다(PermissionRequest → 승인 → PreTool → 실행 → PostTool) | `DefaultToolCallDispatcherTest` |
| 세 루프가 실제로 디스패처를 묶어 그것으로 호출한다(관문 순서가 아니라 훅이 그 루프의 호출을 보는지로 확인) | `SlashSkillToolDispatchE2EIntegrationTest#hooksSeeTheCallsOrigin`(메인·슬래시), `DefaultSubagentExecutorHookEnvironmentTest#aForksToolHooksSeeADirectCallAtItsIteration`(포크) |
| 스킬 루프에서 스킬 `allowed-tools` 밖 도구가 실행 시점에 그 호출만 거절되고 스킬은 계속한다 | `LlmSkillExecutorTest`, `SlashSkillToolDispatchE2EIntegrationTest` |
| `narrow` 는 원래 레지스트리에 없는 도구(원래 허용 목록이 비었을 때 포함)와 같은 이름의 다른 도구를 거절하고, 허용 목록을 넓히지 못하며, 서로소 목록을 거절한다 | `DefaultToolCallDispatcherTest`, `ToolCallDispatchersTest` |
| `narrow` 는 programmatic 설정을 넓히지 못하고, 모든 `dispatch` 가 디스패처의 설정을 `CALLER_PROGRAMMATIC` 으로 싣는다(호출자가 미리 넣은 값은 덮인다) | `DefaultToolCallDispatcherTest`, `ToolCallDispatchersTest`, `SlashSkillProgrammaticCeilingE2EIntegrationTest` |
| 디스패처 없는 컨텍스트에서 스킬 루프는 LLM 을 부르지 않고 실패하며 도구는 돌지 않는다 | `LlmSkillExecutorTest` |
| 디스패처 없는 컨텍스트에서 메인·포크 루프는 그 호출에 같은 문구의 거절 오류 결과를 준다 | `OrcaAgentExecutorDispatchTest`, `DefaultSubagentExecutorDispatchTest` |
| `dispatch` 는 던지지 않는다 | `DefaultToolCallDispatcherTest`, `ToolCallDispatchersTest` |
| 각 경로의 훅 컨텍스트가 맞는 `ToolCallOrigin` 과 iteration 을 받는다(메인 `DIRECT`, 포크 `DIRECT`·`SUBAGENT`, 스킬 `SKILL`) | `DefaultToolCallDispatcherTest`, `SlashSkillToolDispatchE2EIntegrationTest`, `DefaultSubagentExecutorHookEnvironmentTest` |
| 셸 훅이 `AIMON_TOOL_PATH`·`AIMON_TOOL_PARENT_ID` 를 받는다 | `DeclarativePreToolHookTest`, `DeclarativePostToolHookTest`, `AbstractDeclarativeShellHookTest`(permissionRequest) |
| `TOOL_CALL_DISPATCHER` 는 write-once 다 | `ToolContextKeysWriteOnceTest` |
| `ToolExecutionManager.execute`·`executeAll` 은 `at.aimon.core.toolinvocation..` 에서만 불린다(호출 지점 허용 목록 없음, 구현 클래스 면제). 스키마 관문 규칙의 허용 목록은 늘지 않는다 | `ToolExecutionGateArchitectureTest` |

---

## 8. 의도적으로 제외

- **루틴 스텝·deriver 를 디스패처로 옮기기.** 무인 실행이라 승인 질의를 받을 사람이 없고, 지금은 기록된 예외로 관리된다. 바꾸려면
  무인 실행의 승인 의미부터 정해야 한다.
- **`ParallelToolDispatcher` 시그니처 변경.** 병렬 게이트는 동시성·인터럽트 축만 읽으므로 이 변경과 무관하다.
- **`PermissionDeniedContext` 의 호출 경로.** 결정 6. 경로에 따라 갈라질 정책이 생기면 그때 싣는다.

---

## 9. 하지 말 것

- **디스패처가 없다고 `ToolExecutionManager` 로 내려가지 않는다.** 거절한다. 관문 없는 실행이 필요하면 `ToolCallDispatchers.ungated`
  를 이름으로 고른다.
- **`TOOL_CALL_DISPATCHER` 를 enricher 뒤에 넣지 않는다.** write-once 키를 먼저 넣어야 enricher 가 관문을 바꿔 끼우지 못한다.
- **`narrow` 를 우회해 다른 레지스트리로 새 디스패처를 짓지 않는다.** 소비자가 받은 디스패처보다 더 할 수 있는 길은 없어야 한다.
- **`ToolCallOrigin` 을 모델 입력에서 짓지 않는다.** 경로는 디스패처를 부르는 코드가 정하는 사실이다. 모델이나 도구 입력이 정하게 하면
  훅의 경로 정책이 무의미해진다.
- **알림·피드백을 디스패처 안에서 텍스트에 붙이지 않는다.** 붙이는 것은 결과를 트랜스크립트에 넣는 루프의 몫이다(결정 7).

---

## 10. 참조 파일

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 무엇 | 파일 |
|---|---|
| SPI 와 값 타입 | `agent/tool/ToolCallDispatcher.java`, `agent/tool/ToolCallOrigin.java`, `agent/tool/ToolCallPath.java`, `agent/tool/ToolDispatchResult.java` |
| 구현 | `toolinvocation/DefaultToolCallDispatcher.java`, `toolinvocation/ToolCallDispatchers.java`, `toolinvocation/ToolCallNarrowing.java`, `toolinvocation/ToolDispatchResults.java` |
| 관문 파이프라인 | `toolinvocation/SingleToolInvoker.java`, `toolinvocation/ToolInvocationSpec.java` |
| 컨텍스트 키 | `tools/ToolContextKeys.java` (`TOOL_CALL_DISPATCHER`, `ITERATION_COUNT`), `agent/tool/ToolContextKey.java` |
| 루프 | `agent/impl/orca/OrcaAgentExecutor.java`, `subagent/execution/DefaultSubagentExecutor.java`, `skill/execution/llm/LlmSkillExecutor.java` |
| 훅 컨텍스트와 셸 환경 | `hook/event/PreToolContext.java`, `hook/event/PostToolContext.java`, `hook/event/PermissionRequestContext.java`, `skill/hook/declarative/SkillHookEnv.java`, `hook/rewake/impl/DefaultRewakeFireListener.java` |
| 강제 | `modules/aimon-core/src/test/java/at/aimon/core/architecture/ToolExecutionGateArchitectureTest.java` |
