# 통합 테스트 계층 — L0~L4 는 각각 무엇을 격리하는가

> Status: **IMPLEMENTED** — L0 ~ L4 전 층이 `at.aimon.core.agent.impl.orca.it` 에 있다.
> 적용 대상: `aimon-core` 테스트 소스
> 관련 규칙: [`.claude/rules/testing.md`](../../../.claude/rules/testing.md),
> [`.claude/rules/scheduling.md`](../../../.claude/rules/scheduling.md)
> 관련 문서: [`scope-model.md`](../../overview/scope-model.md),
> [`tool-development-guide.md`](../../features/tool/tool-development-guide.md)

이 문서는 **분류 체계와 배치 결정의 근거**만 남긴다 — 층이 추가·삭제되거나 배치 결정이 뒤집힐 때만
갱신된다. **무엇이 어디까지 구현됐는가**는 여기에 적지 않는다: 진행 추적 문서는 작업이 끝나면서
지워졌고(`docs/README.md` 의 `design/` · `plan/` 경계), 지금은 테스트 소스 자체가 그 답이다.

---

## 1. 배경 — 부품은 검증되어 있고 완성품은 검증되어 있지 않다

`aimon-core` 의 단위 테스트는 조밀하다. 도구 하나하나(`ReadTool`, `EditTool`, `GrepTool` …), 실행 루프
(`OrcaAgentExecutor`), 팩토리의 개별 분기(`OrcaAgentRuntimeFactoryProviderFailureTest`,
`…PromptRecoveryTest`)가 각각 커버된다. 그러나 **이것들을 실제로 조립한 결과물**을 통째로 돌려보는
테스트는 없었다. 증거는 세 가지다.

1. **실제 팩토리를 호출하는 테스트가 2개뿐이었다.** `new OrcaAgentRuntimeFactory()` 를 부르는 테스트는
   `OrcaAgentRuntimeFactoryProviderFailureTest:67` 과 `OrcaAgentRuntimeFactoryPromptRecoveryTest:54`
   두 곳이며, 둘 다 특정 실패 분기 하나씩만 겨눈다.
2. **기본 도구 세트가 조립 관점에서 검증되지 않았다.** `defaultToolProviders()` 는
   `OrcaToolProvidersTest` 에서 **리스트의 모양**만 확인됐다. 프로바이더가 실제로 어떤 도구를 레지스트리에
   등록하는지, 그것이 모델에게 전달되는지는 아무도 보지 않았다.
3. **executor 단위 스위트는 구조적으로 이 층을 볼 수 없다.** `OrcaAgentExecutorTestSupport` 는
   `OrcaAgentRuntime.builder()` 로 런타임을 **손으로 조립**하고 목적에 맞춘 가짜 도구를 넣는다. 실행
   메커니즘을 격리하려는 의도적 설계이므로 옳지만, 그 대가로 **조립 회귀는 원리적으로 검출할 수 없다**.

즉 프로바이더가 조용히 등록을 멈추거나, 레지스트리 배선이 끊기거나, 협력자가 null 로 떨어지는 종류의
회귀는 **어느 기존 테스트도 실패시키지 않는다**. 부트스트랩 경로(`AgentSetupFactory` 1626줄)에 좁은
테스트 4개가 붙어 있는 현실이 이 공백을 그대로 보여준다.

본 설계는 그 공백을 **`LlmClient` 만 mocking 한 채 나머지는 전부 프로덕션 타입으로 조립해 실제 턴을
돌리는** 통합 테스트 층으로 메운다.

## 2. 목표 / 비목표

### 목표

- 조립된 `OrcaAgentRuntime` 이 **실제로 동작하는지** 를 계층별로 나눠 증명한다.
- 여러 에이전트·여러 세션이 동시에 돌 때 **영역 침범이 없는지** 를 회귀 테스트로 고정한다.
- 새 도구/프로바이더를 추가할 때 **어느 층에 무엇을 추가해야 하는지** 를 명확히 한다.

### 비목표

- **실제 LLM 호출** — `LlmClient` 는 항상 스크립트된 double 이다. 실제 프로바이더 검증은
  `aimon-llm-*` 모듈의 책임이다.
- **Docker/Testcontainers 를 요구하는 백엔드 검증** — 그것은 이미 `@Tag("docker")` 층이 담당한다.
- **프로덕션 동작 변경** — IT 가 드러낸 동작은 (그것이 버그가 아닌 한) 테스트가 **기록**하지, 프로덕션을
  고쳐 테스트에 맞추지 않는다(§8.2 사례).

## 3. 분류 — 5개 층

각 층은 **"이 층이 없으면 어떤 회귀가 통과하는가"** 로 정의된다. 층이 올라갈수록 검사 대상이 조립에서
동작으로, 단일 턴에서 다중 세션으로 넓어진다.

| Layer | 이름 | 무엇을 증명하는가 | 없으면 통과해 버리는 회귀 |
|-------|------|------------------|--------------------------|
| **L0** | Assembly | 실제 팩토리 + 실제 기본 프로바이더로 만든 런타임의 **모양** — 어떤 도구가 등록되고, 어떤 레지스트리·협력자가 non-null 이며, 무엇이 모델에게 전달되고, `close()` 가 무엇을 닫는가 | 프로바이더가 등록을 멈춤, 레지스트리 배선 누락, 복구 전략이 NoOp 으로 떨어짐, 등록은 됐는데 모델에게 전달 안 됨 |
| **L1** | Single-turn feature | 도구 하나가 **왕복 전체**를 통과하는가 — 모델이 요청 → executor 가 조립된 레지스트리에서 발견 → 프로덕션 도구가 실제 저장소에 실행 → 관측이 다음 프롬프트로 귀환 → 루프 종료 | 도구는 단위 테스트를 통과하는데 왕복 어딘가(디스패치·관측 피드백·종료 조건)가 끊김 |
| **L2** | Session state | 턴을 가로지르는 상태 — transcript 누적, `SessionTotals`, compaction 발동, 세션별 승인 | 두 번째 턴이 첫 턴을 못 봄, 누적치가 리셋됨, compaction 이 조용히 비활성 |
| **L3** | Control flow | 정상 경로가 아닌 흐름 — 도구 실패 복구, 최대 iteration 도달, 중단/인터럽트, prompt-too-long 복구, 서브에이전트 위임 | 실패가 턴 전체를 죽임, 무한 루프, 복구 경로가 사문화 |
| **L4** | Isolation | **여러 에이전트·세션의 영역 침범 없음** — 런타임 간 도구 레지스트리/파일시스템/승인 저장소, 같은 런타임 내 세션 간 todo·transcript, 서브에이전트 fork 의 id 승계 | 한 에이전트의 파일이 다른 에이전트에 보임, 세션 A 의 todo 가 B 에 나타남, fork 가 부모 세션 id 를 사칭 |

### 3.1 착수 순서와 그 이유

**L0 → L1 → L4 → L2/L3** 순으로 진행한다.

L0 를 먼저 하는 이유는 그것이 **하네스 자체의 검증**이기도 하기 때문이다. L0 가 초록이면 "런타임이
제대로 조립됐다"가 보장되므로, 이후 층의 실패를 하네스 탓이 아니라 **대상 동작 탓**으로 읽을 수 있다.
L1 이 그 다음인 이유는 사용자가 명시적으로 요구한 "기본 기능 위주"가 이 층이기 때문이다. L4 를 L2/L3
보다 앞에 두는 것도 같은 요구("여러 에이전트가 작동할 때 영역 침범이나 오류가 없는지")를 따른 것이다.

## 4. 배치 결정 — 별도 모듈이 아니라 `aimon-core/src/test`

**결정:** `modules/aimon-core/src/test/java/at/aimon/core/agent/impl/orca/it/` 에 둔다. 별도의
`aimon-integration-tests` 모듈을 만들지 않는다.

근거는 셋이다.

1. **의존성이 코어 밖으로 나가지 않는다.** 이 스위트가 필요로 하는 것은 `OrcaAgentRuntimeFactory`,
   기본 프로바이더들, `LocalFileSystem`, 실행 매니저들 — 전부 `aimon-core` 안에 있다. 모듈을 새로
   만들어도 의존성은 `aimon-core` 하나뿐이므로, 얻는 것은 파일 경로가 길어지는 것밖에 없다.
2. **`.impl` 임포트 문제.** 검증 대상인 `OrcaAgentRuntime` / `OrcaAgentExecutor` /
   `OrcaAgentRuntimeFactory` 는 `at.aimon.core.agent.impl.orca` 에 있고, ArchUnit 이 그 트리 **바깥**
   에서의 직접 임포트를 막는다. 외부 모듈에서 이들을 테스트하려면 규칙을 우회하거나 완화해야 하는데,
   그 규칙은 지킬 가치가 있는 규칙이다. 같은 트리 안(`…impl.orca.it`)에 두면 규칙을 건드리지 않고
   구현 타입에 정당하게 접근한다.
3. **하네스 재사용.** 기존 테스트 double 관례(`RecordingLlmClient`, `ConcurrentScriptedLlmClient`,
   `CapturingLlmClient`)가 이미 코어 테스트 소스에 있다. 같은 소스 셋에 두어야 관례를 잇고, 중복이
   생기면 승격·삭제로 정리할 수 있다.

### 4.1 별도 모듈이 정당해지는 조건

다음이 필요해지면 그때 `aimon-integration-tests` 모듈을 만든다 — 지금은 해당 없음.

- **여러 구현 모듈을 동시에 조립**해야 할 때. 예: 실제 `aimon-llm-anthropic` + `aimon-session-redis` +
  `aimon-sandbox-docker` 를 한 시나리오에 물려 돌리는 종단 테스트. 이건 `aimon-core` 테스트 소스가
  가질 수 없는 의존성이므로(코어가 구현 모듈에 의존하게 됨) 반드시 별도 모듈이어야 한다.
- CLI 부트스트랩(`AgentSetupFactory`)까지 포함한 **어셈블리 종단 테스트**를 코어 밖에서 돌려야 할 때.

## 5. 하네스 계약

두 개의 파일이 스위트 전체의 기반이다. 이 계약을 벗어나면 IT 가 아니라 또 하나의 단위 테스트가 된다.

### 5.1 `OrcaRuntimeItSupport` — 무엇이 진짜인가

```
Node = 실제 팩토리 + 실제 기본 프로바이더 + 실제 LocalFileSystem + 실제 실행 매니저들
       + 스크립트된 LlmClient (유일한 double)
```

- 런타임은 **반드시** `new OrcaAgentRuntimeFactory().create(...)` + `defaultToolProviders()` 로 만든다.
  `OrcaAgentRuntime.builder()` 를 직접 부르는 순간 조립 회귀 검출력이 사라진다 —
  그 방식은 `OrcaAgentExecutorTestSupport` 의 영역이고, 둘 다 필요하되 섞으면 안 된다.
- `Node` 마다 **자기 루트의 `LocalFileSystem`, 자기 executor, 자기 transcript 저장소**를 갖는다.
  기본적으로 두 노드가 공유하는 것은 `@TempDir` 루트뿐이다. 그래서 L4 에서 관측되는 상태 누출은
  **하네스가 아니라 프로덕션 코드를 통과한 누출**이다 — 이것이 격리 테스트가 의미를 갖는 유일한 전제다.
- **분리에 예외가 하나 있고, 그것은 opt-in 이다.** `Options#shareSubagentManagerWith(Node)` 를 쓰면 두
  노드가 하나의 `DefaultSubagentExecutionManager`(따라서 하나의 `BackgroundTaskStore`)를 공유한다.
  기본값이 오히려 프로덕션을 덜 닮았기 때문이다 — 프로덕션은 `OrcaAgentRuntimeManager` 가 만드는 모든
  런타임이 **하나의 `OrcaAgentExecutor` 를 공유**하므로 백그라운드 태스크 저장소도 하나다. 매니저를
  분리해 둔 채로 "다른 런타임은 남의 task id 를 못 본다"를 단언하면 그것은 **저장소가 비어 있어서**
  통과하는 단언이고, `AgentOutputTool` / `TaskStopTool` 의 소유권 검사를 전부 지워도 여전히 초록이다.
  공유 모드에서는 침입자가 그 태스크를 담고 있는 바로 그 저장소를 보고 있으므로, 남는 장벽은 소유권
  검사뿐이다. 빌려온 매니저는 만든 노드가 닫는다(`ownsSubagentManager`) — 이중 close 금지.
- `scheduledTaskManager` 와 `credentialStore` 는 의도적으로 null 이다. 둘 다 런타임 조립의 필수
  요소가 아니며, 빼놓음으로써 팩토리의 null-safe 경로가 계속 null-safe 함을 증명한다. 스케줄링 전용
  IT 는 실제 매니저를 넘기면 된다.
- `close()` 는 **하네스가 만든 것만** 닫는다. 스코프 모델대로, 런타임을 닫는다고 application-scoped
  협력자를 닫지 않는다.
- **턴을 도는 방법은 두 가지고, 둘 다 진짜다.** `Node#run(...)` 은 executor 를 직접 부른다 — 대부분의
  층에는 이걸로 충분하고, 라이브 세션이라는 층을 하나 덜 얹는다. `Node#openLiveSession(SessionId[,
  LiveSessionOptions])` 은 이 노드의 런타임·executor·훅 매니저·레코드 저장소 위에 실제
  `DefaultLiveSession` 을 세운다(`MessageQueueManager` 는 null — 여기서 큐잉하는 것이 없다). 후자가
  필요한 질문은 **executor 아래에 답이 없는 것들**이다: 인터럽트가 어느 턴을 겨누는가(executor 는 자기가
  받은 `InterruptCoordinator` 하나만 안다), `SessionTotals` / `budgetOverride` 가 그것을 쓴 핸들보다
  오래 사는가, 세션 훅이 언제 발화하는가. 티어다운은 **핸들을 런타임보다 먼저** 닫는다 —
  `LiveSession.close()` 가 OnSessionEnd 를 발화시키고 그 훅이 런타임의 레지스트리를 읽으므로, 반대
  순서는 이미 해체된 컨텍스트에 대고 훅을 쏜다.
- **`Node#recordStore()` 가 세션 상태의 유일한 증인이다.** 턴이 돌려주는 `OrcaAgentExecutionResult` 는
  그 턴이 무엇을 했는지만 말하고, 그것은 레코드가 **써졌든 조용히 버려졌든 똑같다**. 영속을 겨누는
  단언은 저장소를 봐야 한다.
- **기본값이 단언을 공허하게 만드는 자리가 하나 있다.** 팩토리 기본 스킬 정책은
  `AlwaysAllowSkillInvocationPolicy` 이고, 그 아래서는 승인 스토어를 전부 지워도 모든 ALLOW 단언이
  통과한다. `Options#skillInvocationPolicy` 는 그래서 존재한다 — 승인을 다루는 IT 는 실제 체인을 직접
  심는다 (§8.6).

### 5.2 `ScriptedLlmClient` — 라우팅과 관측

- **세션 id 로 라우팅한다.** `OrcaAgentExecutor` 가 `LlmCallMetadata.traceId` 에 `sessionId.value()` 를
  찍어 5-arg `sendMessage` 로 넘기므로, 동시 세션이 하나의 큐를 두고 경쟁하지 않는다.
- **세션 자신이 아닌 호출은 파생 라우트로 갈라진다.** 서브에이전트 포크는
  `forkRoute(traceId, component)`, 요약기는 `compactionRoute(sessionId)`(`"<id>#compaction"`) 로 간다 —
  후자는 `LlmCallMetadata.getFeature()` 가 `"compaction"` 일 때 유도된다. 갈라 두지 않으면 요약기 호출이
  **다음 ReAct iteration 을 위해 스크립트된 응답을 먹어치운다**.
- 등록되지 않은 trace id 로 들어온 호출은 `UNROUTED` 로 떨어진다. **의도적으로** 그렇게 두었다 —
  배선이 틀어지면 다른 세션의 스크립트를 조용히 훔쳐가는 대신 unrouted 호출로 드러난다. L0 의
  `assertThat(llm.callCount(UNROUTED)).isZero()` 가 이 라우팅 자체를 지키는 감시자다.
- `Call` 은 런타임이 모델에게 **제시한 것 전부**를 기록한다: system prompt, messages, tool definitions.
  기존 `ConcurrentScriptedLlmClient` 와 갈라진 이유가 이것이다(그쪽은 executor 동시성 스위트에 맞춰져
  있고 패키지 private 이다). 둘이 수렴하면 하나를 승격시키고 다른 하나를 지운다.

### 5.3 새 IT 를 추가할 때

1. 대상이 어느 층인지 §3 표에서 고른다. 층이 애매하면 **더 낮은 층**에 둔다.
2. 클래스명은 `*IntegrationTest`, `@DisplayName` 은 `RT-IT-L<n>: …` 로 시작한다.
3. 단언은 **왕복 전체**를 겨눈다. "도구가 성공을 반환했다"만 보는 단언은 단위 테스트의 중복이다 —
   파일이 실제로 변했는지, 관측이 다음 프롬프트에 들어갔는지까지 본다.

## 6. 태깅 · 실행 정책

현재 빌드 규약(`buildSrc/.../aimon.java-conventions.gradle.kts`)은 `test` 가 `@Tag("docker")` 를
제외하고, `integrationTest` 가 그것만 포함한다.

- **L0–L3 은 태그를 붙이지 않는다.** Docker 를 요구하지 않고 빠르므로 `./gradlew test` 에 그대로
  포함되어야 한다. "IT" 라는 이름 때문에 느린 층으로 분류하면 회귀 검출이 CI 뒤로 밀린다.
- **L4 의 동시성 시나리오 중 느린 것만** 별도 태그로 분리한다. 필요해지는 시점에
  `@Tag("agent-it")` 를 도입하고 `integrationTest` 를 `includeTags("docker | agent-it")` 로 넓힌다.
  기본 도구 세트 격리처럼 빠른 L4 테스트는 태그 없이 `test` 에 남긴다.

실행:

```bash
./gradlew :aimon-core:test --tests "at.aimon.core.agent.impl.orca.it.*"
```

## 7. 커버리지 맵 — 각 층이 담당하는 그룹

어느 층에 무엇이 속하는지의 지도다. **어느 클래스가 그중 무엇을 덮는지는 여기에 적지 않는다** —
그것은 테스트가 추가될 때마다 바뀌므로, `at.aimon.core.agent.impl.orca.it` 의 `@DisplayName`
(`RT-IT-L<n>: …`)이 그 답이다.

| Layer | 대상 그룹 |
|-------|----------|
| L0 | 팩토리 조립 · 기본 도구 세트 · 레지스트리/협력자 배선 · 결정론적 런타임 id · 모델 전달 · `close()` |
| L1 | 도구 왕복 — 파일(`Read`/`Write`/`Edit`/`Grep`) · `Bash` · `TodoWrite` · `Task`(서브에이전트) · `Skill` · 도구 실패 · 미등록 도구 |
| L2 | transcript 누적 · `SessionTotals` / `budgetOverride` 의 핸들 너머 생존 · compaction 발동과 그 뒤로 이어지는 세션 · `/compact` · 승인 도달 범위(세션 / 에이전트 전역) |
| L3 | 턴이 끝나는 방식 — 답변 · 최대 iteration · 예산 소진(4차원) · `TurnId` 를 겨눈 인터럽트 · 훅 발화 지점 · prompt-too-long 복구 · 위임 |
| L4 | 런타임 간 격리 (레지스트리 · 파일시스템 · 승인 저장소) · 같은 런타임 내 세션 간 격리 (todo · transcript) · 서브에이전트 fork 의 id 승계 |

### 7.1 L4 를 쓸 때 미리 알아야 할 키

- **todo 는 세션별로 쪼개진다.** `TodoWriteTool.CONTEXT_ID_KEY`
  (`"todo_write.context_id"`, `TodoWriteTool:108`)로 버킷이 갈리고, 없으면 `DEFAULT_CONTEXT_ID` 로
  떨어진다. 격리 테스트는 **fallback 으로 떨어져 두 세션이 한 버킷을 공유하는 상황**을 겨눠야 한다.
- **fork 는 자기 `SessionId` 가 아예 없다.** 포크는 세션의 턴이 아니므로 `DefaultSubagentExecutor` 는
  툴 컨텍스트에 `SESSION_ID` 를 넣지 않고, 실행 정체성인 `ExecutionId` 를 `EXECUTION_ID` 로 공개한다.
  사용자의 승인이 fork 에 도달하는 경로는 `sessionId` 가 아니라 `invokingSessionId` 이고, fork 가 다시
  fork 를 띄우면 중간 fork 가 아니라 **사용자의** 세션 id 가 그대로 승계된다
  ([`scope-model.md`](../../overview/scope-model.md) §6).
  예전에는 포크가 자기 `SessionId` 를 새로 발급받았다 — 이 문단도 그 시절 문장을 물려받아 틀린 채로
  남아 있었다. 지금 남은 변환은 `forkTranscriptLabel` 하나뿐이고, 그것은 `TranscriptBuffer` 가
  `SessionId` 로 타입되어 있어서 생기는 **라벨**이지 조회 키가 아니다.

## 8. 알려진 함정

이 스위트를 쓰다가 실제로 밟은 것들이다. 다시 밟지 않도록 남긴다.

### 8.1 도구 관측은 `Message.getContent()` 에 없다

도구 결과는 `Role.TOOL` 메시지에 실린 **구조화된 `ToolUseResult`** 이고, 그런 메시지의 텍스트 뷰는
비어 있다. "관측이 모델에게 돌아갔는가"를 `messageContents()` 로 단언하면 `["…", "", ""]` 를 보게 된다.
`Call.observations()` 를 쓴다.

### 8.2 `EditTool` 은 마지막 개행을 의도적으로 지운다

`EditTool:267` 의 주석대로("Remove trailing newline if present (to match exact file content)")
편집 결과는 시드 파일과 byte-identical 하지 않다. 이건 배선 결함이 아니라 **프로덕션의 문서화된 동작**
이므로, 테스트가 프로덕션에 맞춰 고정한다 — 반대가 아니다.

### 8.3 셸의 `JAVA_TOOL_OPTIONS` 가 Gradle 워커를 죽일 수 있다

`JAVA_TOOL_OPTIONS=-Xms1g -Xmx4g` 같은 값이 셸에 있으면 Gradle Worker Daemon 이 "Initial heap size
set to a larger value than the maximum heap size" 로 죽는다. 빌드 규약이 테스트 JVM 힙을 핀으로
고정해둔 이유가 이것이며(`aimon.java-conventions.gradle.kts:77-79`), 그래도 걸리면
`JAVA_TOOL_OPTIONS= ./gradlew …` 로 비우고 실행한다. 환경 문제이지 코드 문제가 아니다.

### 8.4 `script()` 는 커서를 되감는다 — 한 세션의 여러 턴은 하나의 스크립트다

`script(route, ...)` 는 그 라우트의 커서를 0 으로 되감는다. 그래서 **턴 사이에 다시 스크립트하면**
새 응답이 이어지는 것이 아니라 첫 항목부터 다시 재생된다. 한 세션에서 여러 턴을 돌리려면 모든 턴의
응답을 **한 번의 `script(...)` 호출에 이어서** 넣는다. 턴마다 갈라 쓰고 싶으면 세션을 갈라야 한다.

### 8.5 스크립트되지 않은 라우트는 조용히 성공한다

`globalFallback` 의 기본값은 `text("done")` 이다. 즉 라우트를 잘못 계산했거나 아예 등록하지 않았어도
호출은 성공하고, "요약이 일어났다"류의 단언 중 일부는 **요약기가 한 번도 불리지 않은 채로** 통과할 수
있다. compaction 을 겨누는 단언은 내용뿐 아니라 `callCount(compactionRoute(...))` 를 반드시 함께
고정한다. (`UNROUTED` 카운터는 trace id 자체가 미등록일 때만 올라가고, 파생 라우트 오산은 잡지 못한다.)

### 8.6 스킬 정책 기본값 두 개가 승인 단언을 공허하게 만든다

두 겹이다. 팩토리 기본값은 `AlwaysAllowSkillInvocationPolicy` 이고, 그 아래서는 승인 스토어를 통째로
비워도 ALLOW 단언이 전부 통과한다. 그래서 `Options#skillInvocationPolicy` 로 실제 체인을 심는데 —
거기서 기본 규칙을 `RuleBasedSkillInvocationPolicy` 로 만들면 `safeByDefault` 가 **true** 이고
`isSafeSkill` 은 "INLINE 이고 훅이 없음", 즉 **평범한 SKILL.md 그 자체**다. 결국 또 전부 허용된다.
승인 IT 의 기본 규칙은 `.safeByDefault(false).defaultDecision(DENY)` 여야 한다.

덧붙여, **거부는 실패한 턴이 아니다.** 거부된 스킬은 `ToolResult.error` 관측으로 모델에게 돌아가고
턴은 정상 종료하므로 `result.isSuccess()` 는 **true** 다. 거부를 `isSuccess()` 로 단언하면 항상 실패한다.

### 8.7 레코드가 없으면 세션 상태 쓰기는 조용히 버려진다

`InMemorySessionRecordStore.setTotalsAndBudgetOverride` 는 `computeIfPresent` 다 — 레코드가 아직 없는
세션에 대한 쓰기는 예외도 로그도 없이 사라진다. 프로덕션에서는 `DefaultSessionRouter` 가 먼저
provision 하므로 문제가 없고, 테스트도 그 순서를 따라 `node.recordStore().provision(sessionId)` 를
선행해야 한다. 그러지 않으면 "영속되지 않았다"가 **프로덕션 결함이 아니라 하네스의 누락**이 된다.

## 9. 관련 문서

- [`scope-model.md`](../../overview/scope-model.md) — 수명·소유권·소멸 책임. L4 의 근거.
- [`tool-development-guide.md`](../../features/tool/tool-development-guide.md) — 도구 계약
  (`execute()` 는 절대 던지지 않는다). L1 의 "도구 실패는 관측이지 턴 실패가 아니다"의 근거.
- [`.claude/rules/testing.md`](../../../.claude/rules/testing.md) — `@Tag("docker")` 관례.
