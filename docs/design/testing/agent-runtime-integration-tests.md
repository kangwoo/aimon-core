# 에이전트 런타임 통합 테스트 — IT-L0 ~ IT-L4 는 각각 무엇을 격리하는가

> Status: **IMPLEMENTED** — IT-L0 ~ IT-L4 전 층이 `at.aimon.core.agent.impl.orca.it` 에 있다.
>
> 적용 대상: `aimon-core` 테스트 소스 (`OrcaRuntimeItSupport` · `ScriptedLlmClient` · `*IntegrationTest`)
> 관련 규칙: [`.claude/rules/testing.md`](../../../.claude/rules/testing.md),
> [`.claude/rules/scheduling.md`](../../../.claude/rules/scheduling.md)
> 관련 문서: [`scope-model.md`](../../overview/scope-model.md),
> [`subagent/execution.md`](../subagent/execution.md),
> [`agent-execution/agent-runtime-scope.md`](../agent-execution/agent-runtime-scope.md),
> [`tool-development-guide.md`](../../features/tool/tool-development-guide.md)

이 문서는 `OrcaAgentRuntime` 통합 테스트의 층 분류, 배치 결정, 하네스 계약을 정한다. 어느 테스트가 무엇을 덮는지는
테스트 소스의 `@DisplayName` 이 답한다.

---

## 1. 왜 통합 테스트 층이 따로 있는가

단위 테스트는 부품을 각각 검증하지만 실제 팩토리로 조립한 런타임을 통째로 돌리지는 않는다. 그래서 프로바이더가 등록을
멈추거나, 레지스트리 배선이 끊기거나, 협력자가 null 로 떨어지는 조립 회귀는 단위 테스트로 잡히지 않는다.

executor 단위 스위트는 이 층을 원리적으로 볼 수 없다. `OrcaAgentExecutorTestSupport` 는 `OrcaAgentRuntime.builder()` 로
런타임을 손으로 조립하고 목적에 맞춘 가짜 도구를 넣는다. 실행 메커니즘을 격리하려는 의도적 설계이므로 옳지만, 그 대가로
조립 회귀는 검출할 수 없다.

통합 테스트 층은 그 공백을 **`LlmClient` 만 대역으로 두고 나머지는 전부 프로덕션 타입으로 조립해 실제 턴을 돌려서**
메운다.

## 2. 목표 / 비목표

### 목표

- 조립된 `OrcaAgentRuntime` 이 실제로 동작하는지를 층별로 나눠 증명한다.
- 여러 에이전트·여러 세션이 동시에 돌 때 영역 침범이 없는지를 회귀 테스트로 고정한다.
- 새 도구·프로바이더를 더할 때 어느 층에 무엇을 더해야 하는지를 분명히 한다.

### 비목표

- **실제 LLM 호출** — `LlmClient` 는 항상 스크립트된 대역이다. 실제 프로바이더 검증은 `aimon-llm-*` 모듈의 책임이다.
- **Docker/Testcontainers 를 요구하는 백엔드 검증** — `@Tag("docker")` 층이 담당한다.
- **프로덕션 동작 변경** — IT 가 드러낸 동작은 (버그가 아닌 한) 테스트가 **기록**하고, 프로덕션을 고쳐 테스트에 맞추지
  않는다. 예: `EditTool` 은 편집 결과의 마지막 개행을 의도적으로 지우므로 결과가 시드 파일과 byte-identical 하지 않다.
  배선 결함이 아니라 문서화된 동작이므로 테스트가 그 동작에 맞춘다.

## 3. 층 정의 (IT-L0 ~ IT-L4)

각 층은 **"이 층이 없으면 어떤 회귀가 통과하는가"** 로 정의된다. 층이 올라갈수록 검사 대상이 조립에서 동작으로, 단일
턴에서 다중 세션으로 넓어진다.

| 층 | 이름 | 무엇을 증명하는가 | 없으면 통과해 버리는 회귀 |
|----|------|------------------|--------------------------|
| **IT-L0** | Assembly | 실제 팩토리 + 실제 기본 프로바이더로 만든 런타임의 **모양** — 어떤 도구가 등록되고, 어떤 레지스트리·협력자가 non-null 이며, 무엇이 모델에게 전달되고, `close()` 가 무엇을 닫는가 | 프로바이더가 등록을 멈춤, 레지스트리 배선 누락, 복구 전략이 NoOp 으로 떨어짐, 등록은 됐는데 모델에게 전달 안 됨 |
| **IT-L1** | Single-turn feature | 도구 하나가 **왕복 전체**를 통과하는가 — 모델이 요청 → executor 가 조립된 레지스트리에서 발견 → 프로덕션 도구가 실제 저장소에 실행 → 관측이 다음 프롬프트로 귀환 → 루프 종료 | 도구는 단위 테스트를 통과하는데 왕복 어딘가(디스패치·관측 피드백·종료 조건)가 끊김 |
| **IT-L2** | Session state | 턴을 가로지르는 상태 — transcript 누적, `SessionTotals`, compaction 발동, 세션별 승인 | 두 번째 턴이 첫 턴을 못 봄, 누적치가 리셋됨, compaction 이 조용히 비활성 |
| **IT-L3** | Control flow | 정상 경로가 아닌 흐름 — 도구 실패 복구, 최대 iteration 도달, 중단/인터럽트, prompt-too-long 복구, 서브에이전트 위임 | 실패가 턴 전체를 죽임, 무한 루프, 복구 경로가 사문화 |
| **IT-L4** | Isolation | **여러 에이전트·세션의 영역 침범 없음** — 런타임 간 도구 레지스트리·파일 시스템·승인 저장소, 같은 런타임 안 세션 간 todo·transcript, 서브에이전트 포크의 id 승계 | 한 에이전트의 파일이 다른 에이전트에 보임, 세션 A 의 todo 가 B 에 나타남, 포크가 부모 세션 id 를 사칭 |

IT-L0 는 하네스 자체의 검증이기도 하다. IT-L0 가 통과하면 위 층의 실패를 하네스가 아니라 대상 동작의 실패로 읽을 수 있다.

새 IT 는 다음을 따른다. 커버리지 맵(§7)이 이 규칙에 기댄다.

1. 대상이 어느 층인지 위 표에서 고른다. 층이 애매하면 **더 낮은 층**에 둔다.
2. 클래스명은 `*IntegrationTest`, `@DisplayName` 은 `RT-IT-L<n>: …` 로 시작한다. 이 접두사의 `L<n>` 이 이 문서의 IT-L<n> 이다.
3. 단언은 **왕복 전체**를 겨눈다. "도구가 성공을 반환했다" 만 보는 단언은 단위 테스트의 중복이다 — 파일이 실제로 변했는지,
   관측이 다음 프롬프트에 들어갔는지까지 본다.

## 4. 배치 — 별도 모듈이 아니라 `aimon-core/src/test`

스위트는 `aimon-core` 테스트 소스의 `at.aimon.core.agent.impl.orca.it` 에 둔다. 별도의 `aimon-integration-tests` 모듈을
만들지 않는다.

1. **의존성이 코어 밖으로 나가지 않는다.** 스위트가 필요로 하는 것은 `OrcaAgentRuntimeFactory`, 기본 프로바이더들,
   `LocalFileSystem`, 실행 매니저들 — 전부 `aimon-core` 안에 있다. 모듈을 새로 만들어도 의존성은 `aimon-core` 하나뿐이다.
2. **`.impl` 임포트 규칙을 지킨다.** 검증 대상인 `OrcaAgentRuntime` / `OrcaAgentExecutor` / `OrcaAgentRuntimeFactory` 는
   `at.aimon.core.agent.impl.orca` 에 있고, ArchUnit 이 그 트리 바깥에서의 직접 임포트를 막는다. 외부 모듈에서 테스트하려면
   지킬 가치가 있는 그 규칙을 우회하거나 완화해야 한다. 같은 트리 안(`…impl.orca.it`)에 두면 규칙을 건드리지 않고 구현
   타입에 정당하게 접근한다.
3. **하네스를 재사용한다.** 기존 테스트 대역 관례(`RecordingLlmClient`, `ConcurrentScriptedLlmClient`, `CapturingLlmClient`)가
   코어 테스트 소스에 있다. 같은 소스 셋에 두어야 관례를 잇는다.

### 4.1 별도 모듈이 정당해지는 조건

다음이 필요해지면 그때 `aimon-integration-tests` 모듈을 만든다.

- **여러 구현 모듈을 동시에 조립**해야 할 때 — 예: 실제 `aimon-llm-anthropic` + `aimon-session-redis` +
  `aimon-filesystem-s3` 를 한 시나리오에 물려 돌리는 종단 테스트. 코어가 구현 모듈에 의존하게 되므로 `aimon-core` 테스트
  소스는 그 의존성을 가질 수 없다.
- CLI 부트스트랩(`AgentSetupFactory`)까지 포함한 어셈블리 종단 테스트를 코어 밖에서 돌려야 할 때.

## 5. 하네스 계약

`OrcaRuntimeItSupport` 와 `ScriptedLlmClient` 두 파일이 스위트 전체의 기반이다. 이 계약을 벗어나면 IT 가 아니라 또 하나의
단위 테스트가 된다.

### 5.1 노드 조립 — 무엇이 진짜인가

```
Node = 실제 팩토리 + 실제 기본 프로바이더 + 실제 LocalFileSystem + 실제 실행 매니저들
       + 스크립트된 LlmClient (유일한 대역)
```

- **런타임은 반드시 `new OrcaAgentRuntimeFactory().create(...)` + `defaultToolProviders()` 로 만든다.**
  `OrcaAgentRuntime.builder()` 를 직접 부르는 순간 조립 회귀 검출력이 사라진다. 그 방식은 `OrcaAgentExecutorTestSupport` 의
  영역이고, 둘 다 필요하되 섞지 않는다.
- **노드는 서로 격리된다.** `Node` 마다 자기 루트의 `LocalFileSystem`, 자기 executor, 자기 transcript 저장소를 갖고, 기본적으로
  두 노드가 공유하는 것은 `@TempDir` 루트뿐이다. 그래서 IT-L4 에서 관측되는 상태 누출은 하네스가 아니라 프로덕션 코드를
  통과한 누출이다 — 격리 테스트가 의미를 갖는 유일한 전제다.
- **공유 모드는 opt-in 예외 하나다.** `Options#shareSubagentManagerWith(Node)` 를 쓰면 두 노드가 하나의
  `DefaultSubagentExecutionManager`(따라서 하나의 `BackgroundTaskStore`)를 공유한다. 프로덕션에서는 모든 런타임이 하나의
  executor 와 백그라운드 태스크 저장소를 공유하므로([`scope-model.md`](../../overview/scope-model.md)) 분리된 기본값이 오히려
  프로덕션을 덜 닮았다. 매니저를 분리한 채 "다른 런타임은 남의 task id 를 못 본다" 를 단언하면 저장소가 비어 있어서
  통과하는 단언이 되고, `AgentOutputTool` / `TaskStopTool` 의 소유권 검사를 지워도 초록이다. 공유 모드에서는 침입자가 그
  태스크를 담은 바로 그 저장소를 보므로 남는 장벽은 소유권 검사뿐이다. 빌린 매니저는 만든 노드가 닫는다
  (`ownsSubagentManager`) — 이중 close 를 하지 않는다.
- **`scheduledTaskManager` 와 `credentialStore` 는 null 이다.** 둘 다 런타임 조립의 필수 요소가 아니며, 빼놓음으로써 팩토리의
  null-safe 경로가 계속 null-safe 함을 증명한다. 스케줄링 전용 IT 는 실제 매니저를 넘긴다.
- **`close()` 는 하네스가 만든 것만 닫는다.** 런타임을 닫는다고 application-scoped 협력자를 닫지 않는다.
- **턴을 도는 방법은 두 가지고, 둘 다 진짜다.** `Node#run(...)` 은 executor 를 직접 부른다 — 대부분의 층에는 이것으로
  충분하다. `Node#openLiveSession(SessionId[, LiveSessionOptions])` 은 이 노드의 런타임·executor·훅 매니저·레코드 저장소 위에
  실제 `DefaultLiveSession` 을 세운다(`MessageQueueManager` 는 null — 큐잉하는 것이 없다). 후자는 executor 아래에 답이 없는
  질문에 쓴다 — 인터럽트가 어느 턴을 겨누는가(executor 는 자기가 받은 `InterruptCoordinator` 하나만 안다),
  `SessionTotals` / `budgetOverride` 가 그것을 쓴 핸들보다 오래 사는가, 세션 훅이 언제 발화하는가.
- **티어다운은 핸들을 런타임보다 먼저 닫는다.** `LiveSession.close()` 가 OnSessionEnd 를 발화시키고 그 훅이 런타임의
  레지스트리를 읽으므로, 반대 순서는 이미 해체된 컨텍스트에 훅을 쏜다.
- **`Node#recordStore()` 가 세션 상태의 유일한 증인이다.** 턴이 돌려주는 `OrcaAgentExecutionResult` 는 그 턴이 무엇을 했는지만
  말하고, 그것은 레코드가 써졌든 버려졌든 같다. 영속을 겨누는 단언은 저장소를 본다.
- **세션 상태를 쓰기 전에 레코드를 provision 한다.** `SessionRecordStore.setTotalsAndBudgetOverride` 는 레코드가 없으면
  아무것도 하지 않는 것이 SPI 계약이다([`session/session-store.md` §2](../session/session-store.md#2-레코드-저장소-계약--sessionrecordstore)).
  프로덕션에서는 세션 라우터가 먼저 provision 하므로, 하네스도 같은 순서로 `node.recordStore().provision(sessionId)` 를
  선행한다. 그러지 않으면 "영속되지 않았다" 는 프로덕션 결함이 아니라 하네스의 누락이다.

### 5.2 라우팅과 관측 — `ScriptedLlmClient`

- **세션 id 로 라우팅한다.** `OrcaAgentExecutor` 가 `LlmCallMetadata.traceId` 에 세션 id 를 실어 넘기므로, 동시 세션이 하나의
  큐를 두고 경쟁하지 않는다.
- **세션 자신이 아닌 호출은 파생 라우트로 갈라진다.** 서브에이전트 포크는 `forkRoute(traceId, component)`, 요약기는
  `compactionRoute(sessionId)` 로 간다 — 후자는 `LlmCallMetadata.getFeature()` 가 `"compaction"` 일 때다. 갈라 두지 않으면
  요약기 호출이 다음 ReAct iteration 을 위해 스크립트된 응답을 먹어 치운다.
- **등록되지 않은 trace id 의 호출은 `UNROUTED` 로 떨어진다.** 배선이 틀어지면 다른 세션의 스크립트를 조용히 훔쳐 가는 대신
  unrouted 호출로 드러나게 하기 위해서다. IT-L0 의 `callCount(UNROUTED) == 0` 단언이 이 라우팅 자체를 지키는 감시자다.
- **`Call` 은 런타임이 모델에게 제시한 것 전부를 기록한다** — system prompt, messages, tool definitions. executor 동시성
  스위트용 `ConcurrentScriptedLlmClient` 와 따로 있는 이유가 이것이다.
- **한 세션의 여러 턴은 하나의 스크립트다.** `script(route, ...)` 는 그 라우트의 커서를 0 으로 되감으므로, 턴 사이에 다시
  스크립트하면 새 응답이 이어지지 않고 첫 항목부터 재생된다. 여러 턴의 응답은 한 번의 `script(...)` 호출에 이어서 넣고,
  턴마다 갈라 쓰려면 세션을 가른다.

### 5.3 단언을 공허하게 만드는 기본값

다음 기본값과 동작은 단언을 조용히 공허하게 만든다. 하네스 계약의 일부로 지킨다.

- **도구 관측은 `Message.getContent()` 에 없다.** 도구 결과는 `Role.TOOL` 메시지에 실린 구조화된 `ToolUseResult` 이고, 그런
  메시지의 텍스트 뷰는 비어 있다. "관측이 모델에게 돌아갔는가" 는 `Call.observations()` 로 단언한다.
- **스크립트되지 않은 라우트는 조용히 성공한다.** `globalFallback` 의 기본값은 `text("done")` 이라, 라우트를 잘못 계산했거나
  등록하지 않았어도 호출은 성공한다. "요약이 일어났다" 류의 단언은 요약기가 한 번도 불리지 않은 채 통과할 수 있으므로,
  compaction 을 겨누는 단언은 내용과 함께 `callCount(compactionRoute(...))` 를 고정한다. `UNROUTED` 카운터는 trace id 자체가
  미등록일 때만 올라가고 파생 라우트의 오산은 잡지 못한다.
- **스킬 정책 기본값은 승인 단언을 공허하게 만든다.** 팩토리 기본 스킬 정책(`AlwaysAllowSkillInvocationPolicy`)과
  `RuleBasedSkillInvocationPolicy` 의 `safeByDefault=true` 는 둘 다 평범한 스킬을 허용하므로, 승인을 다루는 IT 는
  `Options#skillInvocationPolicy` 로 `.safeByDefault(false).defaultDecision(DENY)` 체인을 심는다. 거부된 스킬은 도구 오류
  관측이고 턴은 성공으로 끝난다 — 거부를 `isSuccess()` 로 단언하지 않는다.

## 6. 태깅

빌드 규약은 `test` 가 `@Tag("docker")` 와 `@Tag("packaging")` 을 제외하고, `integrationTest` 가 `docker` 태그만 돌게 한다.

- **IT-L0 ~ IT-L3 은 태그를 붙이지 않는다.** Docker 를 요구하지 않고 빠르므로 `test` 에 그대로 포함된다. "IT" 라는 이름
  때문에 느린 층으로 분류하면 회귀 검출이 CI 뒤로 밀린다.
- **IT-L4 의 동시성 시나리오 중 느린 것만** 별도 태그로 분리한다. 그 태그(`@Tag("agent-it")`)는 필요해지는 시점에 도입하고
  `integrationTest` 를 `includeTags("docker | agent-it")` 로 넓힌다. 지금은 없다. 기본 도구 세트 격리처럼 빠른 IT-L4 테스트는
  태그 없이 `test` 에 남긴다.

## 7. 커버리지 맵

어느 층에 어떤 그룹이 속하는지의 지도다. 어느 클래스가 그중 무엇을 덮는지는 적지 않는다 — 테스트가 더해질 때마다 바뀌므로
`@DisplayName("RT-IT-L<n>: …")` 이 그 답이다.

| 층 | 대상 그룹 |
|----|----------|
| IT-L0 | 팩토리 조립 · 기본 도구 세트 · 레지스트리/협력자 배선 · 결정론적 런타임 id · 모델 전달 · `close()` |
| IT-L1 | 도구 왕복 — 파일(`Read`/`Write`/`Edit`/`Grep`) · `Bash` · `TodoWrite` · `Task`(서브에이전트) · `Skill` · 도구 실패 · 미등록 도구 |
| IT-L2 | transcript 누적 · `SessionTotals` / `budgetOverride` 의 핸들 너머 생존 · compaction 발동과 그 뒤로 이어지는 세션 · `/compact` · 승인 도달 범위(세션 / 에이전트 전역) |
| IT-L3 | 턴이 끝나는 방식 — 답변 · 최대 iteration · 예산 소진(iteration · 토큰 · 벽시계 · 비용) · `TurnId` 를 겨눈 인터럽트 · 훅 발화 지점 · prompt-too-long 복구 · 위임 |
| IT-L4 | 런타임 간 격리 (레지스트리 · 파일 시스템 · 승인 저장소) · 같은 런타임 안 세션 간 격리 (todo · transcript) · 서브에이전트 포크의 id 승계 |

### 7.1 IT-L4 키

- **todo 는 세션별로 쪼개진다.** `TodoWriteTool.CONTEXT_ID_KEY`(`"todo_write.context_id"`)로 버킷이 갈리고, 없으면
  `DEFAULT_CONTEXT_ID` 로 떨어진다. 격리 테스트는 fallback 으로 떨어져 두 세션이 한 버킷을 공유하는 상황을 겨눈다.
- **포크는 `SessionId` 가 없고, 승인은 `invokingSessionId` 로 도달한다.** 포크는 세션의 턴이 아니므로 툴 컨텍스트에
  `SESSION_ID` 가 없고 실행 정체성인 `ExecutionId` 를 `EXECUTION_ID` 로 공개한다. 포크가 다시 포크를 띄우면 사용자의 세션 id 가
  그대로 승계된다([`scope-model.md` §6](../../overview/scope-model.md#6-알려진-오칭-misnomer)). `forkTranscriptLabel` 은
  `TranscriptBuffer` 가 `SessionId` 로 타입되어 있어서 생기는 라벨이지 조회 키가 아니다.

## 8. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 대역의 범위 | `LlmClient` 하나만 대역, 나머지는 프로덕션 타입 | 목적별 가짜 도구·손 조립 — executor 단위 스위트의 영역이고 조립 회귀를 원리적으로 못 본다 |
| 배치 | `aimon-core` 테스트 소스의 `…impl.orca.it` | 별도 `aimon-integration-tests` 모듈 — 의존성은 같은데 `.impl` 임포트 규칙을 우회해야 한다. 여러 구현 모듈을 함께 조립해야 할 때만 정당하다 |
| 노드 간 공유 | 기본은 완전 격리, 서브에이전트 매니저 공유는 opt-in | 늘 공유 — 하네스가 누출 경로가 된다 / 늘 분리 — 소유권 검사 단언이 빈 저장소 때문에 통과한다 |
| 미등록 trace id | `UNROUTED` 로 떨어뜨리고 IT-L0 가 0 을 단언 | 기본 라우트로 흡수 — 배선 오류가 다른 세션의 스크립트를 조용히 소비한다 |
| 태그 | IT-L0 ~ IT-L3 무태그, 느린 IT-L4 만 필요할 때 태그 | 전 층 별도 태그 — 빠른 회귀 검출이 CI 뒤로 밀린다 |
| IT 가 드러낸 동작 | 버그가 아니면 테스트가 기록 | 프로덕션을 고쳐 테스트에 맞춤 — 프로덕션 동작 변경은 이 스위트의 비목표다(§2) |

## 9. 하지 말 것

- IT 에서 `OrcaAgentRuntime.builder()` 로 런타임을 조립하지 않는다.
- 노드 사이에 하네스 차원의 상태(파일 시스템, executor, transcript 저장소)를 몰래 공유하지 않는다. 공유가 필요하면 opt-in
  옵션을 쓴다.
- 영속을 실행 결과로 단언하지 않는다. `recordStore()` 를 본다.
- 한 세션의 턴 사이에 `script(...)` 를 다시 부르지 않는다.
- compaction 단언을 내용만으로 하지 않는다. 요약 라우트의 호출 수를 함께 고정한다.
- 승인 IT 를 기본 스킬 정책 위에서 쓰지 않는다.
- 런타임을 핸들보다 먼저 닫지 않는다.

---

## 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 노드 조립 · 옵션 · 티어다운 | [`OrcaRuntimeItSupport.java`](../../../modules/aimon-core/src/test/java/at/aimon/core/agent/impl/orca/it/OrcaRuntimeItSupport.java) |
| 라우팅 · 관측 · fallback | [`ScriptedLlmClient.java`](../../../modules/aimon-core/src/test/java/at/aimon/core/agent/impl/orca/it/ScriptedLlmClient.java) |
| IT-L0 의 대표 | [`RuntimeAssemblyIntegrationTest.java`](../../../modules/aimon-core/src/test/java/at/aimon/core/agent/impl/orca/it/RuntimeAssemblyIntegrationTest.java) |
| executor 단위 스위트의 손 조립 | [`OrcaAgentExecutorTestSupport.java`](../../../modules/aimon-core/src/test/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutorTestSupport.java) |
| 태그 규약 | [`aimon.java-conventions.gradle.kts`](../../../buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts) |
| 레코드가 없을 때의 쓰기 계약 | [`SessionRecordStore.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/store/SessionRecordStore.java) |
| todo 버킷 키 | [`TodoWriteTool.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/todo/TodoWriteTool.java) |

## 관련 문서

- [`scope-model.md`](../../overview/scope-model.md) — 수명·소유권·소멸 책임. IT-L4 의 근거
- [`subagent/execution.md`](../subagent/execution.md) — 포크의 실행과 백그라운드 태스크 저장소
- [`tool-development-guide.md`](../../features/tool/tool-development-guide.md) — 도구 계약(`execute()` 는 던지지 않는다).
  IT-L1 의 "도구 실패는 관측이지 턴 실패가 아니다" 의 근거
- [`.claude/rules/testing.md`](../../../.claude/rules/testing.md) — `@Tag("docker")` 관례
