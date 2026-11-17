# 에이전트 실행 — 등록 항목 23건 (열림 23 · 닫힘 0)

출처는 2026-11-17 의 `docs/design/agent-execution/` 문서 재구성이다. 재구성 전의 다섯 문서 — 실행기(`orca-executor.md`
§13 · §10 · §11.2), 런타임 범위(`agent-runtime-scope.md` §6), 아티팩트(`artifact.md` §10), 압축(`compaction.md` §11),
context engine(`context-engine.md` §12 · §13.5) — 가 본문 끝에 들고 있던 열린 항목과, 재구성하면서 코드와 대조해 드러난 설계
공백 넷(AE-6 · AE-15 · AE-17 · AE-18)을 여기로 옮겼다. 설계 문서는 현재 설계만 적고, 남은 일은 여기에 둔다.

옮기지 않은 것과 그 이유:

- **투기적 side-work** — 이미 [`../design/backlog/orca-executor-speculative-side-work.md`](../design/backlog/orca-executor-speculative-side-work.md) 가 갖는다
- **잘림 이어쓰기(continuation)** — 열린 항목이 아니라 기각한 대안이다. 잘림 문서의 결정 표가 갖는다
- **부분 스트림 tombstone 정리** — 닫았다. 재시도 · 모델 교체 시 폐기된 시도의 부분 출력 정리는
  [`../design/llm/streaming.md`](../design/llm/streaming.md) 의 reset 통지 계약과 시도 단위 수명이 이미 설계로 갖는다.
  남은 뜻이 전사(transcript) 쪽이라면 그때 새 항목으로 연다
- **압축의 부분 compaction 정책** — `CompactionRange` 는 deprecated 경로에만 남는다
- **`Message` 의 id 필드** — 근거였던 L0(`TimeBasedMicrocompact`)가 deprecated 다
- **기본 배선되지 않은 셋**(Post 복원 훅, `TimeBasedMicrocompact`, redaction) — 미결이 아니라 현재 상태다
- **두 engine 의 `/compact` 경합 계약 차이, FALLBACK 의 iteration 별 기록** — 설계 사실로 확정해 설계 문서에 적었다

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**다.

각 항목의 **어디** 줄은 줄 번호 없이 파일만 가리키며, 모두 2026-11-17 에 확인했다. 경로는
`modules/aimon-core/src/main/java/at/aimon/core/` 기준이고, 다른 모듈은 모듈 경로부터 적는다.

---

## AE-1 — 턴 루프와 포크 루프가 ReAct 코어를 따로 갖는다 · **열림**

**무엇을.** 메인 루프(`OrcaAgentExecutor`)와 포크 루프(`DefaultSubagentExecutor`)의 공통부를 정하고, 그 모양으로 두 루프를
맞춰 공용 ReAct 코어를 뽑는다. 이음매는 `impl` 밖에 둔 `at.aimon.core.agent.loop.LoopTransition` 이다.

**왜.** 같은 iteration 규칙(취소 검사, 예산, 쿼터, 압축, 게이트웨이 호출, 도구 실행)이 두 곳에 따로 구현되어 있어 한쪽에만
들어간 변경이 다른 쪽에서 빠진다. 2026-08-31 아키텍처 리뷰의 측정으로 `OrcaAgentExecutor.executeReActLoop` 는 335줄(main
소스 최장 메서드), `DefaultSubagentExecutor.runReActLoop` 는 131줄, `OrcaAgentExecutor` 전체는 3,238줄(main 소스 최대
클래스)이었다. 2026-11-17 에는 4,076줄이다. 335줄은 이 항목에서 해소될 증상이지 별도 항목이 아니다.

**기각한 대안.** 메인 루프의 iteration 전처리(인터럽트 검사 → 예산 판정 → 압축 게이트 → 스팬 오픈 → `LoopTransition` 태깅)를
`IterationGate` 로 뽑는 것. 메서드는 짧아지지만 *메인 루프 안*에 새 이음매를 하나 더 만들어, 공용 코어를 뽑을 때 맞춰야 할
형태가 하나 는다. 착수 순서는 "메인 루프를 정리하고 나중에 합친다" 가 아니라 "두 루프의 공통부를 먼저 정한다" 다.

**어디.** `agent/impl/orca/OrcaAgentExecutor.java`, `subagent/execution/DefaultSubagentExecutor.java`,
`agent/loop/LoopTransition.java`. 설계는 [실행기](../design/agent-execution/orca-executor.md).

**언제 다시 볼까.** 두 루프 중 한쪽에만 iteration 규칙을 더하는 변경이 또 생길 때, 또는 `OrcaAgentExecutor` 를 나누는
작업이 시작될 때 — 그 작업은 이 항목의 순서를 따른다.

## AE-2 — 포크 루프는 스트리밍 툴 중첩을 쓰지 않는다 · **열림**

**무엇을.** `DefaultSubagentExecutor` 에도 `StreamingToolScheduler` 를 설치할 수 있게 한다.

**왜.** `ToolConcurrencyConfig.streamingOverlap` 을 켜도 툴 실행이 스트림에 겹치는 것은 메인 루프뿐이다. 포크 루프에는
스케줄러 참조가 없어, 같은 설정 아래에서 포크의 툴 지연은 그대로 사용자 지연이 된다. SPI 상으로는 같은 dispatcher 를 주입하면
된다.

**어디.** `subagent/execution/DefaultSubagentExecutor.java`, `agent/tool/StreamingToolScheduler.java`. 설계는
[실행기](../design/agent-execution/orca-executor.md).

**언제 다시 볼까.** 포크의 툴 지연이 체감된다는 보고가 있을 때, 또는 AE-1 로 두 루프가 코어를 공유하게 될 때(그때는 따로
할 일이 없어진다).

## AE-3 — 프롬프트 초과 복구는 drop 한 계층뿐이다 · **열림**

**무엇을.** `LlmPromptTooLongException` 복구를 2계층으로 만든다 — 먼저 요약 압축을 시도하고, 실패했을 때만 가장 오래된
메시지를 버리는 drop 으로 폴백한다.

**왜.** 지금 `DefaultContextEngine.recover` 는 `DefaultPromptSizeRecoveryStrategy` 로 drop 만 한다. 하드 실패는 없지만,
손실이 적은 요약보다 손실이 큰 drop 이 먼저 쓰인다 — 프롬프트 초과를 만난 턴은 요약될 수 있었던 앞부분을 통째로 잃는다.

**어디.** `agent/context/DefaultContextEngine.java`(`recover`), `agent/compact/DefaultPromptSizeRecoveryStrategy.java`.
설계는 [context engine](../design/agent-execution/context-engine.md), 실행기 쪽 배선은
[실행기](../design/agent-execution/orca-executor.md).

**언제 다시 볼까.** 프롬프트 초과 복구로 맥락을 잃었다는 보고가 있을 때.

## AE-4 — 런타임 모델 해상도가 없다 · **열림**

**무엇을.** 실행 중 상황에 따라 모델을 바꾸는 해상도 규칙(plan-mode 승격, 200k 를 넘는 컨텍스트의 승격)을 둘지 정한다.

**왜.** 참조 구현 대비 남은 격차다. 지금 모델 교체는 `LlmFallbackPolicy` 의 과부하 fallback 뿐이고, 요청의 모델 이름은
[모델 이름 해석](../design/llm/model-name-resolution.md) 의 정적 순서로 정해진다. 그 문서도 실행 중 승격은 다루지 않는다.

**어디.** `llm/invoke/LlmCallGateway.java`, `llm/retry/LlmFallbackPolicy.java`. 설계는
[실행기](../design/agent-execution/orca-executor.md).

**언제 다시 볼까.** 큰 컨텍스트에서 더 큰 창의 모델로 옮겨야 하는 에이전트가 생길 때.

## AE-5 — 컨텍스트 refresh 는 턴 시작 1회뿐이다 · **열림**

**무엇을.** `ContextAssembler` 의 재조립 트리거를 정한다 — N iteration 주기, 파일 변경 이벤트, 또는 지금대로.

**왜.** 턴 안에서 작업 디렉터리의 파일이 바뀌어도 그 턴의 시스템 컨텍스트는 턴 시작 때의 것이다. 자주 다시 조립하면 프롬프트
캐시가 무효화되므로 비용과의 균형 문제로 남아 있다.

**어디.** `agent/context/ContextAssembler.java`. 설계는 [실행기](../design/agent-execution/orca-executor.md).

**언제 다시 볼까.** 긴 턴에서 낡은 컨텍스트 때문에 틀린 행동이 보고될 때, 또는 AE-23(캐시 경계)이 들어가 재조립 비용을 잴 수
있게 될 때.

## AE-6 — `CompactBoundary.strategyName` 이 PRUNE 에도 "summarization" 이다 · **열림**

**무엇을.** 압축 경계 이벤트의 `strategyName` 을 `CompactionMetadata.kind`(`PRUNE` · `ROLLING` · `FULL` · `FALLBACK`)에서
파생할지 정한다.

**왜.** `OrcaAgentExecutor.COMPACTION_STRATEGY_NAME` 은 상수 `"summarization"` 이고, `RollingContextEngine` 은 요약 없이
prune 만으로 끝나는 압축(`CompactionKind.PRUNE`)도 COMPACT 결정으로 돌려준다. 그 iteration 의 이벤트(`agent.stream.CompactBoundary`)는
아무것도 요약하지 않았는데 요약이라고 말한다. 설계 문서는 지금 상수를 "압축 경계" 라는 뜻으로 서술하고 종류는
`CompactionMetadata.kind` 가 싣는다고 적고, 상수의 javadoc 도 같은 뜻(고정된 경계 라벨, 실제 종류는 `kind`)으로 적는다. 남은
것은 값 자체가 요약이라고 말한다는 점이다. 파생으로 바꾸면 이벤트 계약이 바뀌므로 소비자 확인이 먼저다.

**어디.** `agent/impl/orca/OrcaAgentExecutor.java`(`COMPACTION_STRATEGY_NAME`, `emitCompactBoundary`),
`agent/stream/CompactBoundary.java`, `agent/compact/CompactionMetadata.java`. 설계는
[실행기](../design/agent-execution/orca-executor.md), 종류는 [롤링 context engine](../design/agent-execution/rolling-context-engine.md).

**언제 다시 볼까.** `strategyName` 으로 압축 종류를 구분하려는 소비자(관측 · UI)가 생길 때.

## AE-7 — hot-reload 로 교체된 런타임을 누가 닫는지 정해지지 않았다 · **열림**

**무엇을.** agent 정의가 실행 중에 바뀔 때의 런타임 재발급 정책과, 교체된 이전 인스턴스의 자원 회수 책임을 정한다.

**왜.** `AgentRegistry` 는 같은 이름의 agent 를 등록하면 교체한다. 그 agent 의 런타임이 쥔 도구 레지스트리 · 훅 레지스트리 ·
MCP 연결을 누가 닫는지는 정해져 있지 않다 — 교체가 반복되면 닫히지 않은 런타임이 쌓일 수 있다.

**어디.** `agent/AgentRegistry.java`, `agent/impl/orca/OrcaAgentRuntimeManager.java`(`destroyRuntime`). 설계는
[런타임 범위](../design/agent-execution/agent-runtime-scope.md).

**언제 다시 볼까.** agent 정의를 재시작 없이 바꾸는 기능을 만들 때.

## AE-8 — 세션 단위 MCP 슬롯이 없다 · **열림**

**무엇을.** 세션마다 다른 MCP 서버 연결이 필요할 때 라이브 세션 쪽에 슬롯을 더한다.

**왜.** MCP 연결(`McpClientManager`)은 agent-scoped 런타임이 소유하므로 같은 agent 의 모든 세션이 같은 연결을 쓴다. 세션별
자격증명이나 세션별 서버를 요구하는 쓰임새는 지금 표현할 수 없다.

**어디.** `agent/impl/orca/OrcaAgentRuntime.java`. 설계는 [런타임 범위](../design/agent-execution/agent-runtime-scope.md).

**언제 다시 볼까.** 세션 단위 MCP 연결을 요구하는 쓰임새가 생길 때.

## AE-9 — 런타임 id 의 이름 형식을 강제하지 않는다 · **열림** *(결정 대기)*

**무엇을.** `agent:<name>[:<discriminator>]` 의 각 부분에 권장 규칙(영문 소문자 · 숫자 · 하이픈)을 강제할지 정한다.

**왜.** 지금 `AgentRuntimeId` 는 콜론과 빈 값(blank)만 막는다. 그 밖의 문자는 통과하므로 로그 · 메트릭 · 저장 키에서 id 를 읽기
어려워질 수 있다. 강제하면 이미 쓰는 id 가 거부될 수 있다.

**어디.** `agent/AgentRuntimeId.java`. 설계는 [런타임 범위](../design/agent-execution/agent-runtime-scope.md).

**언제 다시 볼까.** id 의 문자 때문에 저장 키나 메트릭 이름이 깨졌다는 보고가 있을 때.

## AE-10 — "현재 요청 → 어느 discriminator" 를 프레임워크가 주지 않는다 · **열림** *(결정 대기)*

**무엇을.** 요청에서 discriminator 를 고르는 패턴(테넌트 컨텍스트, request scope)을 프레임워크가 일부라도 제공할지 정한다.

**왜.** 테넌트 런타임을 만들고 회수하는 쪽은 있다(`AgentRuntimeResolver`, aimon-bootstrap). 하지만 어느 요청이 어느
discriminator 인지는 호출자가 정해 `SubmitRequest.contextDiscriminator` 에 싣는다. 호스트마다 같은 매핑 코드를 따로 쓴다.

**어디.** `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/runtime/AgentRuntimeResolver.java`,
`modules/aimon-session-routing/src/main/java/at/aimon/session/routing/LiveSessionOpener.java`. 설계는
[런타임 범위](../design/agent-execution/agent-runtime-scope.md).

**언제 다시 볼까.** 두 번째 호스트가 같은 discriminator 매핑을 직접 쓰게 될 때.

## AE-11 — 포크의 아티팩트가 결과에 드러나지 않는다 · **열림**

**무엇을.** 포크가 모은 아티팩트를 `SubagentExecutionResult` 로 올린다.

**왜.** `DefaultSubagentExecutor` 는 도구 패리티를 위해 수집기를 만들어 컨텍스트에 넣지만, 모인 것을 결과로 올리지 않는다.
포크가 만든 파일은 부모의 실행 결과에도, 전사에도 아티팩트로 나타나지 않는다.

**어디.** `subagent/execution/DefaultSubagentExecutor.java`, `subagent/execution/SubagentExecutionResult.java`. 설계는
[아티팩트](../design/agent-execution/artifact.md).

**언제 다시 볼까.** 포크가 만든 파일을 사용자에게 내려받게 해야 할 때.

## AE-12 — `downloadToken` 발급자가 없다 · **열림** *(결정 대기)*

**무엇을.** 다운로드 토큰을 코어가 발급할지, 애플리케이션 계층의 일로 남길지 정한다.

**왜.** 코어에는 필드 · 직렬화 · 변형자(`MessageArtifact#withDownloadToken`)만 있고 토큰을 만드는 코드가 없다. 다운로드는
애플리케이션 계층의 일이라는 설계와 맞지만, 호스트마다 발급 · 검증을 따로 만든다.

**어디.** `llm/MessageArtifact.java`, `agent/artifact/FileArtifact.java`. 설계는 [아티팩트](../design/agent-execution/artifact.md).

**언제 다시 볼까.** 두 번째 호스트가 다운로드 토큰을 직접 발급하게 될 때.

## AE-13 — 파일을 만드는 다른 도구는 아티팩트 위임을 쓰지 않는다 · **열림**

**무엇을.** `Bash` 처럼 파일을 만들 수 있는 도구에도 아티팩트 데코레이터의 위임 패턴을 넓힌다.

**왜.** 아티팩트를 등록하는 것은 쓰기 · 편집 도구의 데코레이터(`ArtifactAwareWriteTool` · `ArtifactAwareEditTool`)뿐이다.
`Bash` 로 만든 파일은 아티팩트로 잡히지 않아 사용자가 내려받을 수 없다.

**어디.** `tools/artifact/ArtifactAwareWriteTool.java`, `tools/artifact/ArtifactAwareEditTool.java`. 설계는
[아티팩트](../design/agent-execution/artifact.md).

**언제 다시 볼까.** 셸로 만든 산출물을 내려받게 해 달라는 요구가 있을 때.

## AE-14 — `tool_use` 없는 assistant 메시지의 아티팩트가 압축 스트리핑에서 떨어진다 · **열림**

**무엇을.** `MessageStripper` 가 `tool_use` 없는 assistant 메시지의 `artifacts` 도 보존하게 한다.

**왜.** `MessageStripper.rebuild` 는 `hasToolUses()` 인 메시지의 `artifacts` 만 보존한다. 커맨드 처리 경로는
`Message.assistant(response, List.of(), artifacts)` — `tool_use` 없이 아티팩트를 가진 메시지 — 를 만들므로, 그 아티팩트는 요약
입력을 만드는 스트리핑에서 떨어진다.

**어디.** `agent/compact/MessageStripper.java`. 설계는 [아티팩트](../design/agent-execution/artifact.md),
[압축](../design/agent-execution/compaction.md).

**언제 다시 볼까.** 압축 뒤 커맨드가 만든 아티팩트가 사라졌다는 보고가 있을 때.

## AE-15 — 전사의 아티팩트는 저장소 구분을 싣지 않는다 · **열림**

**무엇을.** `MessageArtifact`(와 `ArtifactMetadata`, 전사 코덱)에 저장소(`ArtifactStorage` — WORKSPACE / CONTROL)를 싣는다.

**왜.** `FileArtifact.getStorage()` 는 아티팩트가 작업 파일 시스템(WORKSPACE)에 있는지 보관 사본(CONTROL)에 있는지 구분하고,
다운로드 계층은 그에 따라 저장소를 연다. 그런데 `FileArtifact.toMessageArtifact()` 는 path · fileName · size · mimeType ·
toolUseId · downloadToken 만 복사한다. 전사에 영속되는 것은 `MessageArtifact` 이므로, 비영속 실행 환경에서 보관 사본으로
등록된 아티팩트를 전사만 보고 내려받으면 어느 저장소를 열지 알 수 없다. 지금 보관 사본의 다운로드는 실행 결과의 `FileArtifact`
를 써야 한다.

**어디.** `agent/artifact/FileArtifact.java`(`toMessageArtifact`, `getStorage`), `llm/MessageArtifact.java`,
`base/ArtifactMetadata.java`, `subagent/task/codec/JsonSessionSnapshotCodec.java`. 설계는
[아티팩트](../design/agent-execution/artifact.md).

**언제 다시 볼까.** 실행이 끝난 뒤 전사만으로 보관 사본을 내려받는 호스트가 생길 때. 전사 코덱을 바꾸는 일이므로 롤링
업그레이드 규칙(모르는 필드는 버리고 읽는다)을 함께 정한다.

## AE-16 — L2 Session Memory Compact 가 없다 · **열림**

**무엇을.** 백그라운드 요약 에이전트가 세션 메모리를 미리 갱신해 두고, 압축 때 그것을 요약으로 쓰는 계층(L2)을 설계한다.

**왜.** 지금 압축은 임계값에 닿은 그 iteration 에서 요약 호출을 동기로 낸다. 미리 만든 요약이 있으면 그 지연과 비용을 압축
시점에서 뺄 수 있다. 백그라운드 요약 에이전트가 선행 조건이고 별도 설계 대상이다.
[`../design/backlog/self-managed-context-engine.md`](../design/backlog/self-managed-context-engine.md) 는 L2 를 다루지 않는다.

**어디.** 설계는 [압축](../design/agent-execution/compaction.md).

**언제 다시 볼까.** 압축 시점의 요약 지연이 턴 지연의 문제로 보고될 때, 또는 백그라운드 에이전트 실행 기반이 생길 때.

## AE-17 — 공유 compaction breaker 를 bootstrap · 스타터가 배선하지 않는다 · **열림**

**무엇을.** 멀티 인스턴스 배포에서 `SessionRecordCompactionFailureStore` 를 기본으로 배선할지 정하고, 정하면 `AimonStackBuilder` ·
스타터에 배선한다.

**왜.** 압축 실패 카운터(circuit breaker)의 기본 저장소는 in-memory 다. 노드 사이에서 공유하려면
`OrcaAgentRuntimeFactory.withCompactionFailureStore(new SessionRecordCompactionFailureStore(...))` 로 갈아끼워야 하는데, 그것을
쓰는 main 소스는 `OrcaAgentRuntimeFactory` 의 메서드 정의와 javadoc 예시뿐이다 — aimon-bootstrap · aimon-spring-boot-starter
에는 참조가 없다. 관측 가능한 결과는 **세션이 노드를 옮기면 그 세션의 연속 실패 수가 0 에서 다시 시작**한다는 것이다. 설계
문서는 지금 "어셈블리가 직접 갈아끼운다(bootstrap · 스타터는 하지 않는다)" 로 적는다.

**어디.** `agent/compact/SessionRecordCompactionFailureStore.java`, `agent/impl/orca/OrcaAgentRuntimeFactory.java`
(`withCompactionFailureStore`), `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java`. 설계는
[압축](../design/agent-execution/compaction.md).

**언제 다시 볼까.** 스케일아웃 배포에서 breaker 가 노드마다 따로 열린다는 보고가 있을 때, 또는 세션 레코드 저장소를 쓰는
다른 노드 로컬 상태를 bootstrap 이 기본 배선하게 될 때.

## AE-18 — v2 의 커스텀 guard 거절은 지원 조립에서만 강제된다 · **열림**

**무엇을.** v2 쓰기 형식에서 커스텀 `CompactionGuard` · 요약하지 못하는 요약기 · engine 없는 기본 guard 를 거절하는 검사를
`OrcaAgentRuntime.Builder` 경로에도 걸지, 아니면 그 경로를 알려진 틈으로 문서에 남길지 정한다.

**왜.** 거절은 `DefaultContextEngine.Builder.writeFormat(V2)` 의 `build()` 에서만 일어난다. `OrcaAgentRuntimeFactory` 는 노드의
쓰기 형식으로 그것을 걸지만, `OrcaAgentRuntime.Builder` 가 파생하는 engine 은 형식을 받지 않고 `.writeFormat(SessionLogFormat.V1)`
로 만들어진다. 그래서 그 경로로 들어온 커스텀 guard 는 기동 시 거절되지 않고, 그 노드가 v2 레코드를 만나면 AE-19 의 모양으로
압축하지 않는다. 설계 문서는 계약을 "지원 조립(`OrcaAgentRuntimeFactory` · `AimonStackBuilder` · 스타터)에서 기동 실패" 로 좁혀
적는다.

**어디.** `agent/impl/orca/OrcaAgentRuntime.java`(`Builder#build`), `agent/context/DefaultContextEngine.java`(`Builder`).
설계는 [context engine](../design/agent-execution/context-engine.md).

**언제 다시 볼까.** 팩토리를 거치지 않고 `OrcaAgentRuntime.Builder` 로 런타임을 조립하는 호스트가 v2 를 쓰게 될 때.

## AE-19 — in-place 로 압축할 수 없는 v2 로그는 압축되지 않는다 · **열림**

**무엇을.** 뷰 모드를 못 하는 engine 이 뷰 상태나 manifest 를 가진 v2 버퍼를 만났을 때의 동작을 정한다 — 압축할 길을 주거나,
그 조합을 더 이르게 실패시킨다.

**왜.** 그런 engine 은 투영한 뷰를 보내고 압축하지 않는다. 로그는 계속 자라고, blocking 한계에 닿기 전까지는 prompt-too-long
복구(뷰에서 drop)만 크기를 줄인다. 그 구성으로 v2 레코드를 오래 서빙하면 턴이 결국 한계에서 실패한다. 지원 조립에서는 그런
노드가 v2 에서 기동하지 않으므로, 이것은 v1 노드가 v2 로 올라간 레코드를 만났을 때(롤링 업그레이드)와 AE-18 의 경로에서 생긴다.

**어디.** `agent/context/DefaultContextEngine.java`. 설계는 [context engine](../design/agent-execution/context-engine.md).

**언제 다시 볼까.** v1 → v2 롤링 업그레이드를 하는 배포가 생길 때, 또는 AE-18 을 처리할 때.

## AE-20 — 롤링 요약의 풍화를 검증하지 않는다 · **열림** *(결정 대기)*

**무엇을.** 누적 요약의 섹션 항목에 id 를 붙이고, 갱신 전후 diff 로 "삭제된 결정" 을 탐지할지 정한다.

**왜.** 롤링 요약은 갱신될 때마다 이전 요약을 다시 쓰므로 앞의 결정이 조용히 빠질 수 있다. 지금은 그것을 잡는 장치가 없다.
`CompactionMetadata` 의 관측값을 먼저 본다.

**어디.** `agent/context/RollingContextEngine.java`, `agent/compact/CompactionMetadata.java`. 설계는
[롤링 context engine](../design/agent-execution/rolling-context-engine.md).

**언제 다시 볼까.** 긴 세션에서 앞서 정한 결정을 잊었다는 보고가 있을 때, 또는 압축 관측값으로 요약 크기의 비정상 감소가
보일 때.

## AE-21 — 토큰 추정 오차를 보정하지 않는다 · **열림** *(결정 대기)*

**무엇을.** 휴리스틱 토큰 추정을 프로바이더가 돌려준 usage 로 보정할지 정한다.

**왜.** 휴리스틱 추정기는 과대 추정한다. 롤링 engine 은 압축 빈도가 높아 그 오차가 불필요한 압축 — 곧 요약 호출 비용 — 으로
쌓인다.

**어디.** `llm/token/HeuristicTokenEstimator.java`. 설계는 [context engine](../design/agent-execution/context-engine.md),
[롤링 context engine](../design/agent-execution/rolling-context-engine.md).

**언제 다시 볼까.** 압축이 실제 창보다 훨씬 이르게 일어난다는 관측이 있을 때.

## AE-22 — 기본 engine 에서는 전체 compaction 뒤 되찾기가 없다 · **열림** *(결정 대기)*

**무엇을.** `DefaultContextEngine` 의 전체 compaction 뒤에도 `SessionHistoryTool` 을 열지 정한다.

**왜.** 되찾기 도구는 롤링 engine 에만 등록된다. 기본 engine 의 모델은 요약이 잃은 사실을 되찾을 길이 없다 — 리그의 비교에서도
이 차이가 두 engine 의 결과를 가른다([`context-engine-pressure-rig-open-items.md`](context-engine-pressure-rig-open-items.md) CP-3).

**어디.** `tools/session/SessionHistoryTool.java`, `agent/context/DefaultContextEngine.java`. 설계는
[context engine](../design/agent-execution/context-engine.md).

**언제 다시 볼까.** CP-3 의 결정이 날 때, 또는 기본 engine 에서 요약 뒤 사실을 잃었다는 보고가 있을 때.

## AE-23 — LLM 어댑터가 프롬프트 캐시 경계를 붙이지 않는다 (CTX-05) · **열림**

**무엇을.** 어댑터가 `SystemPromptParts` 의 이음매에 캐시 경계를 붙인다(`LlmClient` 의 parts-aware `sendMessage` 를
override). 그 뒤에 시스템 프롬프트의 메모리 주입을 고정할지 정한다.

**왜.** 시스템 프롬프트는 이미 정적 · 동적 파트로 나뉘어 어댑터에 전달되지만(`SystemPromptRenderer`), 두 어댑터 모두 그 자리를
쓰지 않는다. Anthropic 어댑터는 `cache_control` 을 보내지 않아 캐시되지 않고, OpenAI 의 자동 prefix 캐시는 메모리 파트 뒤의
context SYSTEM 블록이 실행마다 바뀔 수 있어 이득이 적다. 메모리 주입 고정(세션 시작 시점의 메모리로 prefix 를 지키는 것)은 이것이
선행 조건이다 — 그때 고정 단위는 "메모리 스냅샷이 의미 있게 바뀌었을 때" 같은 명시적 갱신 정책이 낫다. 코드 주석 ·
테스트는 이 일을 `CTX-05` 라는 이름으로 추적한다.

**어디.** `llm/LlmClient.java`(parts-aware `sendMessage`), `agent/impl/orca/SystemPromptRenderer.java`,
`modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/AnthropicLlmClient.java`. 설계는
[context engine](../design/agent-execution/context-engine.md).

**언제 다시 볼까.** 프롬프트 캐시 비용이 운영 비용에서 눈에 띄게 될 때, 또는 어느 어댑터든 캐시 경계를 구현하기 시작할 때.
