# 도구 호출의 단일 입구 — `ToolCallDispatcher` 와 호출 경로

> **Status**: PROPOSED — `aimon-core` (`at.aimon.core.agent.tool`, `at.aimon.core.toolinvocation`, `at.aimon.core.skill.execution`,
> `at.aimon.core.hook.event`). 공개 API 호환을 깨는 항목이 있다(§6).

## 1. 목적과 범위

모델이 고른 도구 호출은 관문 — 허용 목록, 실링, PermissionRequest 훅, 승인 관문, PreTool 훅, 실행, PostTool 훅 — 을 지나야 한다.
지금 그 관문을 지나는 경로는 셋이고, 각자 `ToolInvocationSpec` 을 조립해 `SingleToolInvoker` 를 부른다. 그리고 그 셋과 별도로, 관문을
**건너뛰는** 경로가 하나 있다 — 슬래시 명령의 스킬 루프는 디스패처가 묶이지 않았으면 `ToolExecutionManager` 로 바로 간다.

이 문서는 그 경로들을 하나의 입구 `ToolCallDispatcher` 로 모으고, 관문 없는 폴백을 없애고, 호출이 어떤 경로로 왔는지(`ToolCallOrigin`)를
훅이 볼 수 있게 한다. [프로그래매틱 도구 호출](programmatic-tool-calling.md) 이 이 입구로 코드의 도구 호출을 보내지만, 이 문서는 그것
없이도 성립하고 그 자체로 지금의 관문 결함을 고친다.

| 주제 | 정본 |
|---|---|
| 관문의 순서, 스키마 검증 | [도구 계약](tool-contract.md) |
| 부작용 축, 실링, 승인 관문 | [부작용 축](side-effect-axes.md) |
| 병렬 실행 게이트 | [병렬 실행](parallel-execution.md) |
| 훅 이벤트와 컨텍스트 | [훅 시스템](../hook/hook-system.md) |

---

## 2. 지금의 경로

| 경로 | 지금 | 관문 |
|---|---|---|
| 메인 루프 (`OrcaAgentExecutor.executeToolUses`) | `ParallelToolDispatcher` → `ToolInvocationSpec` → `SingleToolInvoker` | 지난다 |
| 포크 루프 (`DefaultSubagentExecutor`) | 같은 구성, 포크마다 다시 조립 | 지난다 |
| 슬래시 명령의 스킬 루프 (`LlmSkillExecutor`) | 손으로 지은 커맨드 컨텍스트에 `SKILL_TOOL_DISPATCHER_KEY` 가 있으면 그것(`OrcaAgentExecutor.commandToolDispatcher`), **없으면 `ToolExecutionManager` 직행** | 디스패처가 있을 때만 |
| 루틴 스텝 (`RoutineExecutor`) | `Tool#execute` 직접 | 건너뛴다 — `ToolExecutionGateArchitectureTest` 의 기록된 예외 |
| 메모리 deriver (`ReActLlmDeriver`) | `Tool#execute` 직접 | 건너뛴다 — 기록된 예외 |
| `ArtifactAware{Edit,Write}Tool`, `RecentFilesRestoreHook` | 위임 실행 | 건너뛴다 — 기록된 예외 |

`ToolExecutionGateArchitectureTest` 가 지키는 것은 **스키마 관문**(`Tool#execute` 는 `DefaultToolExecutor` 와 기록된 예외만 부른다)뿐이다.
`ToolExecutionManager` 를 직접 부르는 경로는 그 테스트를 통과하면서 훅·승인을 모두 건너뛸 수 있고, 스킬 루프의 폴백이 바로 그렇다.

모델이 부르는 `Skill` 도구는 `LlmSkillExecutor` 를 쓰지 않는다(인라인은 내용 주입, 포크는 서브에이전트). 스킬 루프는 사용자가 친
`/my-skill` 에서만 돈다. 그래서 스킬 루프 위에는 그것을 감싸는 도구 호출도, ReAct 루프가 묶은 디스패처도 없다.

---

## 3. 설계 결정

| # | 쟁점 | 결정 | 이유 | 기각한 대안 |
|---|---|---|---|---|
| 1 | 입구 | `ToolCallDispatcher` — 실행 루프가 만들어 `ToolContext` 에 묶는 바인딩. 인터페이스는 `agent.tool`, 구현은 `toolinvocation`. 메인·포크 루프와 슬래시 명령의 스킬 루프가 이것을 부른다 | 세 군데의 `ToolInvocationSpec` 조립이 구현 하나로 모인다. 구현이 훅·`ToolContextKeys` 에 의존하므로 `SingleToolInvoker` 와 같은 이유로 `agent..` 밖에 둔다(`PackageDependencyArchitectureTest`) | 선택 키 하나 더(`SKILL_TOOL_DISPATCHER_KEY` 방식): "없으면 관문 없이" 라는 폴백이 또 하나의 우회로가 된다 |
| 2 | 묶이지 않았을 때 | 소비자는 거절한다(fail-closed). 테스트와 임베더는 이름에 드러나는 `ToolCallDispatchers.ungated(executionManager, registry)` 를 명시적으로 고른다 | 관문 없는 실행은 선택이어야 하지 기본값이면 안 된다 | 폴백 유지: 지금의 결함 |
| 3 | 바인딩에 고정하는 것 | 레지스트리, 세션 레지스트리, 허용 목록, `SingleToolInvoker`, 훅, 인터럽트 코디네이터, `InvokerType`. 호출마다 받는 것은 `ToolUse`, 호출 경로, 컨텍스트. 턴 안에서 바뀌는 iteration 번호는 컨텍스트의 write-once 가 아닌 키 `ToolContextKeys.ITERATION_COUNT` 로 싣는다 | 루프마다 다른 것은 만들 때 정해지고, 호출마다 다른 것만 인자로 남는다. PreTool/PostTool 훅은 iteration 번호를 받는다 | 모든 것을 호출마다: `ToolInvocationSpec` 을 그대로 SPI 로 올리는 것이다 |
| 4 | 배치와 스트리밍 | 디스패처 밖, 루프에 남긴다 — 메인 루프의 스트리밍 선실행(`StreamingToolScheduler`)·harvest, 포크의 배치 전 쿼터 승인, 병렬 게이트 | 루프마다 의미가 다르다. 루프는 배치를 나눈 뒤 호출마다 `dispatch` 를 부른다 | `dispatchAll`: 세 루프의 차이를 옵션으로 흡수해야 한다 |
| 5 | 좁히기 | `narrow(registry, allowedTools)` — 새 레지스트리의 모든 도구가 원래 레지스트리에 같은 이름의 같은 인스턴스(`equals`)로 있어야 하고, 허용 목록은 `AllowedTools.intersect`. 교집합이 비면(서로소) `IllegalArgumentException` — 빈 목록은 "무제한" 으로 읽히기 때문이다 | 슬래시 명령의 스킬 루프가 스킬 레지스트리와 스킬 `allowed-tools` 로 좁힌다. 원래 허용 목록이 비어 "무제한" 이어도 레지스트리가 넓어지면 안 된다 | 레지스트리 교체 허용: 원래 없던 도구가 실행된다 |
| 6 | 호출 경로 | `ToolCallOrigin` (`DIRECT` / `CODE` / `SKILL` / `REPLAY`)을 호출마다 받아 PreTool·PostTool·PermissionRequest 훅 컨텍스트에 싣는다(`PermissionDeniedContext` 는 대상 밖). `InvokerType` 과는 직교한다 | 훅이 "코드에서 온 `Bash` 는 막는다" 같은 정책을 쓸 수 있어야 하고, 지금 슬래시 명령의 스킬 경로는 `MAIN_AGENT` 로만 보고된다. `InvokerType` 은 누가, 경로는 어떤 경로로다 | `InvokerType` 확장: 두 축이 섞인다 |
| 7 | 결과 | `ToolDispatchResult` — 텍스트, 알림·피드백(문자열 목록), 오류·재시도·렌더 페이로드. `ToolUseResult` 로의 변환은 루프가 트랜스크립트에 넣을 때 한 번 | 알림·피드백을 결과 텍스트에 붙이는 것은 직접 경로 루프의 몫이다. 다른 소비자(코드 호출)는 그것을 따로 모은다 | `ToolUseResult` 반환: 붙인 뒤에는 떼어 낼 수 없다 |
| 8 | 강제 | ArchUnit — `ToolExecutionManager.execute`·`executeAll` 은 `at.aimon.core.toolinvocation..` 에서만 부른다 | 스키마 관문 테스트가 잡지 못하는 우회를 잡는다. 지금 위반자는 스킬 폴백 하나라 이 변경 안에서 닫힌다 | 규약만으로: 다음 우회가 조용히 생긴다 |

---

## 4. 인터페이스

```java
public interface ToolCallDispatcher {
    /** Never throws. Denials, unknown tools and failures come back as error results. */
    ToolDispatchResult dispatch(ToolUse toolUse, ToolCallOrigin origin, ToolContext context);

    /** Can only do less: every tool in the new registry must exist in this one; the allow-list is intersected. */
    ToolCallDispatcher narrow(ToolRegistry registry, List<AllowedTool> allowedTools);
}
```

| 지점 | 디스패처 |
|---|---|
| 메인 루프 | 턴 컨텍스트를 지을 때 넣는다 |
| 포크 | 컨텍스트를 새 빌더로 지을 때 자기 것을 넣는다 |
| 슬래시 명령의 스킬 루프 | 실행기가 지은 디스패처의 `narrow(스킬 레지스트리, 스킬 allowed-tools)`. 스킬의 `allowed-tools` 가 제안 필터만이 아니라 실행 시점에도 강제된다. `LlmSkillExecutor` 는 `SingleToolInvoker` 를 몰라도 된다 |

`ToolContextKeys.TOOL_CALL_DISPATCHER` 는 write-once 키다. `ToolContextKey.BUILT_IN_WRITE_ONCE_NAMES` 와 `ToolContextKeysWriteOnceTest` 에
추가한다. 다시 묶어야 하는 지점은 없다 — 포크와 스킬 루프는 컨텍스트를 새 빌더로 짓는다.

### 4.1 `ToolDispatchResult`

| 필드 | 내용 |
|---|---|
| `toolUseId` | 원래 호출의 id. `ToolUseResult` 로 바꿀 때 쓴다 |
| `text` | 텍스트 결과. PostTool 훅이 고치는 값 |
| `notices`, `hookFeedback` | 문자열 목록. 직접 경로에서는 루프가 `ToolUseResult` 로 바꿀 때 알림은 앞에, 피드백은 뒤에 붙인다(지금 동작) |
| `isError`, `retryable`, `renderPayload` | 지금과 같음 |

### 4.2 `ToolCallOrigin`

```java
public enum ToolCallPath { DIRECT, CODE, SKILL, REPLAY }

public final class ToolCallOrigin {   // 정적 팩토리 — ToolResult 선례
    public static ToolCallOrigin direct();
    public static ToolCallOrigin code(String parentToolUseId, String language);
    public static ToolCallOrigin skill(String skillName);
    public static ToolCallOrigin replay();   // DefaultRewakeFireListener 의 rewake 리플레이
    public ToolCallPath path();
}
```

셸 훅(preTool, postTool, permissionRequest)에는 `AIMON_TOOL_PATH` (`direct` / `code` / `skill` / `replay`) 와 `AIMON_TOOL_PARENT_ID` 를
넘긴다. `AIMON_TOOL_PARENT_ID` 는 `code` 가 아닌 경로에서 빈 문자열이다.

---

## 5. 경로별 결과

| 경로 | 이후 |
|---|---|
| 메인 루프 | `ToolCallDispatcher` 를 묶고 부른다 |
| 포크 루프 | 같음 |
| 슬래시 명령의 스킬 루프 | `narrow` 한 디스패처만. 없으면 첫 LLM 호출 전에 실행 거절. 허용 목록 밖 호출은 그 호출 하나가 오류 결과를 받고 스킬은 계속한다 — 에이전트 자신의 루프와 같은 의미다(관문 없는 폴백 시절에는 스킬 전체가 실패했다) |
| 루틴 스텝, 메모리 deriver, 위임 실행 | 그대로(스키마 관문 테스트의 기록된 예외). 디스패처를 묶지 않는다 |

---

## 6. 호환을 깨는 변경

| 변경 | 전 | 후 |
|---|---|---|
| 스킬 디스패처 흡수 | `SkillToolDispatcher`, `SKILL_TOOL_DISPATCHER_KEY`, 없으면 관문 없는 폴백 | 삭제. 스킬 루프는 `ToolCallDispatcher` 만 |
| 훅 컨텍스트에 호출 경로 | 없음 | `PreToolContext` · `PostToolContext` · `PermissionRequestContext` 가 `ToolCallOrigin` 을 필수로 가진다. 직접 짓는 호출부 `DefaultRewakeFireListener` 도 바뀐다 |
| 루프의 도구 실행 | 각 루프가 `ToolInvocationSpec` 을 조립 | 디스패처 구현 하나 |

---

## 7. 테스트

| 층 | 무엇 |
|---|---|
| 관문 | 세 루프 모두 같은 관문 순서를 지남. 스킬 루프에서 스킬 `allowed-tools` 밖 도구가 실행 시점에 거절됨. `narrow` 가 원래 레지스트리에 없는 도구를 거절함(원래 허용 목록이 비었을 때 포함). 디스패처 없는 컨텍스트에서 스킬 루프가 실행을 거절함 |
| 훅 | 각 경로에서 훅 컨텍스트의 `ToolCallOrigin` 과 셸 훅 환경 변수 |
| ArchUnit | `ToolExecutionManager.execute`·`executeAll` 은 `at.aimon.core.toolinvocation..` 에서만(호출 지점 허용 목록 없음. `ToolExecutionManager` 구현 클래스 자신은 자기 오버로드끼리 부르므로 면제). `ToolExecutionGateArchitectureTest` 의 허용 목록이 늘지 않음 |

---

## 8. 의도적으로 제외

- **루틴 스텝·deriver 를 디스패처로 옮기기.** 무인 실행이라 승인 질의를 받을 사람이 없고, 지금은 기록된 예외로 관리된다. 바꾸려면
  무인 실행의 승인 의미부터 정해야 한다.
- **`ParallelToolDispatcher` 시그니처 변경.** 병렬 게이트는 동시성·인터럽트 축만 읽으므로 이 변경과 무관하다.
