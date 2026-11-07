# 도구 계약 — 등록 항목 23건 (열림 22 · 닫힘 1)

출처는 `docs/design/tool/` 의 도구 계약·부작용 축·병렬 실행·도구 검색·모니터 문서가 남긴 작업이다. 설계 문서는 현재 설계만
적고, 남은 일은 여기에 둔다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**다. 프로세스 그룹 종료는 이미 열려 있는
[EE-55](execution-environment-open-items.md) 라서 여기 없다. 부분 병렬화와 virtual thread 는
[병렬 실행](../design/tool/parallel-execution.md) 의 제외 결정이고, 심볼릭 링크와 TOCTOU 는
[도구 계약](../design/tool/tool-contract.md) 의 경계 결정이므로 등록하지 않는다.

각 항목의 **어디** 줄은 줄 번호 없이 파일만 가리키며, 모두 2026-11-02 에 확인했다. 경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

---

## TC-1 — `SchemaValidationMode` 기본값을 `ENFORCE` 로 올린다 · **열림**

**무엇을.** `DefaultToolExecutor` 와 `OrcaAgentExecutorFactory` 의 기본 모드를 `WARN` 에서 `ENFORCE` 로 바꾼다.

**왜.** `WARN` 에서는 스키마 위반이 로그만 남기고 도구가 그대로 실행된다. 위반이 도구 안의 에러로 늦게 나타난다.

**어디.** `agent/tool/schema/SchemaValidationMode.java`, `agent/tool/execution/DefaultToolExecutor.java`,
`agent/impl/orca/OrcaAgentExecutorFactory.java`. 설계는 [도구 계약](../design/tool/tool-contract.md).

**언제 다시 볼까.** `WARN` 로그 한 사이클이 보고되는 위반이 정말로 모델의 실수임을 보인 뒤.

## TC-2 — 중첩 스키마를 재귀로 검증한다 · **열림**

**무엇을.** 스키마 검증이 중첩 구조와 `$ref` / `oneOf` 까지 다루게 한다.

**왜.** 자체 검증기는 중첩 계약을 지키지 못한다. 그것을 다루려면 JSON Schema 구현체를 들여야 하고, 그때는 자체 검증기를
버리는 결정이 함께 온다.

**어디.** `agent/tool/schema/DefaultToolInputSchemaValidator.java`.

**언제 다시 볼까.** 중첩 계약이 중요한 도구가 `GenericTool` 로 해결되지 않을 때.

## TC-3 — `schedule_task` 의 예약 시점에 스키마를 검증한다 · **열림**

**무엇을.** `ScheduleTaskTool` 이 각 스텝의 `tool_params` 를 대상 도구 스키마로 검증한다.

**왜.** 발화 시점(`RoutineExecutor`)은 스키마 관문 밖이고 그때는 오류를 고칠 모델도 없다. 잘못된 입력은 예약이 성공한 뒤
발화 시점에야 실패한다. 비용은 `Supplier<ToolRegistry>` 주입 하나다.

**어디.** `tools/scheduling/ScheduleTaskTool.java`, `scheduling/RoutineExecutor.java`. 지금 `ScheduleTaskTool` 에는 검증기와 레지스트리 공급자가 없다.

**언제 다시 볼까.** 루틴이 발화 시점에 스키마로 실패했다는 보고가 들어올 때.

## TC-4 — 입력을 보는 동시성 정책과 리소스 키 · **열림**

**무엇을.** `getConcurrencyBehavior()` 가 `ToolInput` 을 받게 하고, 같은 자원을 다투는지 판정할 `parallelResourceKeys` 또는
`RESOURCE_EXCLUSIVE(key)` 를 함께 둔다.

**왜.** 정책이 도구 단위로만 선언되므로 `Edit` / `Write` 가 서로 다른 파일을 건드리는 배치도 순차로 돈다.

**어디.** `agent/tool/Tool.java`, `agent/tool/ConcurrencyBehavior.java`, `agent/tool/DefaultParallelToolDispatcher.java`.

**언제 다시 볼까.** 요구하는 도구가 나타날 때.

## TC-5 — 부재와 명시적 `null` 을 구분한다 · **열림**

**무엇을.** 원본 맵을 `ToolInput` 옆에 별도로 싣는다.

**왜.** JSON `null` 을 부재로 정규화하므로 도구는 "값을 지우라" 와 "값을 주지 않았다" 를 구별할 수 없다.

**어디.** `agent/tool/ToolInput.java`, `base/NullSafeMaps.java`.

**언제 다시 볼까.** 구분이 필요한 도구가 생길 때.

## TC-6 — exit code 허용목록 (보류) · **열림**

**무엇을.** `grep` / `diff` / `test` 의 exit 1 을 실패가 아닌 것으로 읽는 허용목록을 둔다.

**왜.** 지금은 모델이 `[exit code: 1]` 과 빈 출력을 함께 보고 "매치 없음" 을 읽어야 한다. 보류한 이유는
[셸 계약](../design/tool/shell-contract.md) 에 있다.

**어디.** `tools/bash/BashTool.java`.

**언제 다시 볼까.** 모델이 무매치를 실패로 오해하는 것이 관측될 때.

## TC-7 — 인트리 도구의 `NON_DESTRUCTIVE` · `IDEMPOTENT` 감사 · **열림**

**무엇을.** 인트리 도구를 하나씩 보고 `DestructiveBehavior` 와 `IdempotencyBehavior` 를 선언한다.

**왜.** 인트리 도구 가운데 `NON_DESTRUCTIVE` 나 `IDEMPOTENT` 를 선언한 것이 없다(`McpTool` 은 서버 주장을 옮길 뿐이다). 그래서
게이트를 배선하면 파괴적 도구가 아니라 쓰는 도구 전부가 무인 경로에서 실패하고, 면제선을 `MUTATING` 으로 올려도 면제되는 것이
없다. routine 재시도도 읽기 도구와 `TRUST` 된 MCP 도구에서만 일어난다. 도구 하나하나에 대한 판단이므로 일괄 변경으로 묶지 않는다.

**어디.** `agent/tool/Tool.java`, `toolinvocation/approval/SideEffectApprovalGate.java`. 설계는
[부작용 축](../design/tool/side-effect-axes.md).

**언제 다시 볼까.** 승인 게이트를 프로덕션 경로에 배선하기 전.

## TC-8 — `SessionHistoryTool` 이 `READ_ONLY` 를 선언하지 않는다 · **열림**

**무엇을.** `SessionHistoryTool` 에 `getSideEffectLevel()` override 를 더한다.

**왜.** javadoc 은 "Stateless and read-only" 라고 적고 `CONCURRENT_SAFE` 도 선언하지만, 부작용 선언은 기본값 `MUTATING` +
`DESTRUCTIVE` 다. `READ_ONLY` 실링 아래서는 정의가 숨겨지고 실행이 거부되며, routine 단계도 재시도되지 않고, 게이트를 배선하면
묻게 된다. 의도일 가능성을 코드에서 찾지 못했을 뿐 확정한 것은 아니다.

**어디.** `tools/session/SessionHistoryTool.java`.

**언제 다시 볼까.** 바로. 별도 코드 변경으로 한다.

## TC-9 — 도구 검색을 인트리에서 켤 수 있게 한다 · **열림**

**무엇을.** `OrcaAgentRuntimeFactory` 에 `ToolSearchCatalog` 를 받는 이음매를 열고, 어떤 도구를 deferred 로 등록할지 고르는 길을
`AimonStack` 까지 잇는다.

**왜.** 팩토리는 레지스트리를 `new DefaultToolRegistry()` 로 직접 만들고 받는 자리가 없다. 그래서 팩토리·`AimonStack`·CLI·Spring
어디서도 켤 수 없고 `OrcaAgentRuntime.builder().toolRegistry(catalog)` 로 런타임을 손으로 조립할 때만 된다. 이때 팩토리가 하던
provider 등록과 `ToolSearchTool` 등록을 조립이 직접 해야 한다. `ToolSearchTool` 은 부작용을 선언하지 않아 `MUTATING` +
`DESTRUCTIVE` 이므로, 배선할 때 `READ_ONLY` 실링과 승인 게이트에서 어떻게 보일지도 함께 정해야 한다.

**어디.** `agent/impl/orca/OrcaAgentRuntimeFactory.java`, `agent/impl/orca/OrcaAgentRuntime.java`,
`tools/search/ToolSearchTool.java`. main 소스에 `new ToolSearchTool` 호출이 없고, `SearchableTool.deferred` 는 자기 정의뿐이다.

**언제 다시 볼까.** 조립이 deferred 도구를 쓰려 할 때.

## TC-10 — 활성화 상태를 세션 수명으로 올린다 · **열림**

**무엇을.** 활성화 상태를 `SessionRecord` 쪽에 두고 `ToolActivationState` 를 인터페이스로 뽑는다.

**왜.** 활성화 수명이 execution 이므로 같은 세션의 다음 턴에서 모델이 `ToolSearch` 를 다시 호출해야 한다.

**어디.** `agent/tool/search/ToolActivationState.java`, `agent/tool/search/ToolSearchCatalog.java`.

**언제 다시 볼까.** 같은 세션에서 반복되는 `ToolSearch` 비용이 관측될 때.

## TC-11 — `createSessionRegistry` · "per-session" 이름 정리 · **열림**

**무엇을.** 메서드 이름과 javadoc 을 실제 수명(execution)에 맞춘다.

**왜.** 이름과 주석은 세션이라고 말하지만 수명은 턴 1회 또는 포크 1회다. 읽는 사람이 활성화가 턴을 넘는다고 오해한다.

**어디.** `OrcaAgentExecutor`, `DefaultSubagentExecutor`, `ToolActivationState`, `ToolSearchCatalog`, `ToolSearchRegistry`.

**언제 다시 볼까.** 해당 코드를 건드릴 때.

## TC-12 — 도구 검색 랭킹 고도화 · **열림**

**무엇을.** BM25, inverted index, 임베딩 기반 시맨틱 검색을 `ToolSearchStrategy` 구현으로 넣는다.

**왜.** 기본 전략은 키워드 가중합이라 어휘가 다른 질의를 찾지 못한다.

**어디.** `agent/tool/search/ToolSearchStrategy.java`, `agent/tool/search/KeywordToolSearchStrategy.java`.

**언제 다시 볼까.** 키워드 랭킹의 정확도 문제가 관측될 때.

## TC-13 — `ToolSearch` 의 eager 등록을 강제한다 · **열림**

**무엇을.** `ToolSearchCatalog` 가 `ToolSearchTool` 의 deferred 등록을 거부한다.

**왜.** `catalog.register(SearchableTool.deferred(new ToolSearchTool()))` 가 통과한다. 그러면 deferred 도구를 찾을 방법이 없고 실패는
조용하다. 지금은 [도구 검색](../design/tool/tool-search.md) 이 조립의 책임으로 적는다.

**어디.** `agent/tool/search/ToolSearchCatalog.java`, `agent/tool/search/SearchableTool.java`.

**언제 다시 볼까.** TC-9 를 배선할 때. 또는 조립이 이 실수를 했다는 보고가 들어올 때.

## TC-14 — `AnnotationTrust` 의 부분 신뢰 값 · **열림**

**무엇을.** 낮추는 주장 일부만 믿는 값(예: 읽기 전용만)을 `AnnotationTrust` 에 더한다.

**왜.** `McpToolTraits.resolve` 는 값 하나로 세 축을 함께 정한다. 읽기 전용 주장만 믿고 싶은 운영자에게 선택지가 없다. 보수적인
선언만 믿는 값은 기본값과 결과가 같아 뜻이 없지만, 이 값은 뜻이 있다.

**어디.** `mcp/McpServerConfig.java`, `mcp/McpToolTraits.java`. 결정은 [MCP 도구](../design/integration/mcp-tool.md).

**언제 다시 볼까.** 실사용 사례가 나올 때.

## TC-15 — `openWorldHint` 축 · **닫힘** *(2026-11-06)*

**무엇을.** `openWorldHint` 에 대응하는 선언을 만든다.

**왜.** `McpToolAnnotations` 가 값을 보관하지만 어떤 선언도 만들지 않으므로 그 값을 읽는 쪽이 없다. 통째로 채택하면 넷째 축이
따라온다.

**어디.** `mcp/McpToolAnnotations.java`, `mcp/McpToolTraits.java`.

**언제 다시 볼까.** 그것을 읽을 소비자가 생길 때.

**어떻게 닫았나.** 소비자가 생겼다 — [프로그래매틱 도구 호출](../design/tool/programmatic-tool-calling.md) §7.1 의 데이터 도달 범위.
넷째 축 `DataReach`(`agent/tool/DataReach.java`, `Tool#dataReach()`)를 두고, `McpToolTraits.resolve` 가 `openWorldHint` 를
그것으로 옮긴다. 규칙은 [MCP 도구](../design/integration/mcp-tool.md) D13 이다.

## TC-16 — `PermissionSubject` 의 URL 종류 · **열림**

**무엇을.** `PermissionSubject.Kind` 에 URL 을 더한다.

**왜.** `Monitor` 의 `ws` 형태는 도구 자신의 규칙으로 판정한다. URL 패턴이 필요한 도구가 늘면 같은 규칙이 도구마다 반복된다.

**어디.** `agent/tool/permission/PermissionSubject.java`, `tools/monitor/MonitorTool.java`. 설계는
[모니터](../design/tool/monitor.md).

**언제 다시 볼까.** `WebFetch` 까지 URL 패턴이 필요해질 때.

## TC-17 — `DefaultSubagentExecutor` 의 스트리밍 중첩 · **열림**

**무엇을.** 서브에이전트 실행기에도 스트리밍 중 도구 실행을 켠다.

**왜.** 같은 dispatcher 를 주입하면 SPI 상 가능하지만 메인 실행기에만 있다. 서브에이전트의 도구 실행은 응답이 끝나기를 기다린다.

**어디.** `subagent/execution/DefaultSubagentExecutor.java`, `agent/tool/StreamingToolScheduler.java`.

**언제 다시 볼까.** 서브에이전트 지연이 문제 될 때. 이 트리거는 재료에 없어 등록할 때 정했다.

## TC-18 — 설정이 다른 노드가 나눠 쓰는 샌드박스에서 셸 엔진의 스윕 · **열림**

**무엇을.** `.aimon-ptc/` 스윕의 나이가 설정에 기대지 않게 한다 — 예를 들어 런타임이 실행 중 자기 디렉터리의 mtime 을 주기적으로
갱신하고(heartbeat) 스윕 나이를 상수로 둔다.

**왜.** 스윕 나이는 지우는 실행의 설정 `max(2 × maxWallClock, 20 분)` 이다. `maxWallClock` 이 서로 다르고 10 분을 넘는 두 프로세스
(다르게 설정된 노드)가 한 샌드박스를 나눠 쓰면, 짧은 쪽의 실행이 긴 쪽의 살아 있는 실행 디렉터리를 지울 수 있다. 그러면 긴 쪽의
응답 파일 쓰기가 실패해 그 실행이 `SCRIPT_ERROR` 로 끝난다. heartbeat 는 이 의존을 없애지만, 멈춘 감시 스레드가 살아 있는 실행을
지우게 하는 자기 실패 모드를 들인다 — 그래서 [설계 기록](../design/tool/ptc-qc4-and-nonblocking-fixes.md) §3 item 5 가 기각했다.

**어디.** `tools/code/shell/ShellCodeExecutionEngine.java` 의 `sweepAge`, 런타임 `aimon_ptc.py` 의 `_sweep`(경로는
`modules/aimon-core/src/main/resources/at/aimon/core/tools/code/shell/`). 설계는 [셸 엔진](../design/tool/programmatic-tool-calling-shell.md) §8.
2026-11-06 확인.

**언제 다시 볼까.** 노드마다 `maxWallClock` 이 다른 배포가 한 샌드박스를 나눠 쓰게 될 때, 또는 원인 모를 "reply could not be written"
이 그런 배포에서 보고될 때.

## TC-19 — `aimon-sandbox` 쪽 Q-C4 후속 · **열림**

**무엇을.** 셸 엔진 런타임이 셸의 프로세스 그룹 종료로 끝나게 된 코어(Q-C4 수정)를 `aimon-sandbox` 에 들인다 — testkit 계층에서 꺼 둔
엔진 계약 테스트 `wallClock` 과 `bridgeLimitStopsAtOnce` 를 다시 켜고, 워크스페이스 샌드박스 설계 §20 의 Q-C4 항목과 제공자 문서
§9 Q-C4 를 닫고, `SandboxShell` 과 제공자를 `ExecutionEnvironment.shell()` · `VirtualShell.supports` 의 "아무것도 프로비저닝하지
않는다" javadoc 에 대어 본다.

**왜.** 고친 코어가 배포되기 전에는 그 저장소가 그것을 쓸 수 없다. 그동안 그쪽의 두 테스트는 꺼져 있고, 문서는 결함이 열려 있다고
말한다.

**어디.** 저장소 [aimon-sandbox](https://github.com/kangwoo/aimon-sandbox) — `ShellEngineContractTest`, `docs/design/ptc-shell-provider.md`
§9, `docs/design/workspace-sandbox.md` §20. 코어 쪽은 [설계 기록](../design/tool/ptc-qc4-and-nonblocking-fixes.md) §2. 2026-11-06 확인.

**언제 다시 볼까.** 이 수정을 담은 코어 스냅숏(또는 릴리스)이 배포될 때.

## TC-20 — 셸 엔진 응답 쓰기 드레인 상한의 값 · **열림**

**무엇을.** 실행을 닫을 때 진행 중인 응답 쓰기를 기다리는 상한(5 초)을 실측으로 정한다.

**왜.** 5 초는 "OpenSandbox 에서 응답 쓰기 몇 번" 으로 고른 값이고 재지 않았다. 너무 짧으면 상한을 넘긴 쓰기가 지운 뒤의 실행
디렉터리에 파일을 남겨 다음 실행의 스윕까지 머물고(WARN), 너무 길면 샌드박스가 멈췄을 때 `ExecuteCode` 가 그만큼 늦게 끝난다.
`close()` 는 실제로 기다린 시간을 DEBUG 로 남긴다.

**어디.** `tools/code/shell/ShellProgramRun.java` 의 `REPLY_DRAIN`, `close()`. 2026-11-06 확인.

**언제 다시 볼까.** "a reply write was still in flight" WARN 이 운영에서 보일 때, 또는 PTC 지연 측정(PTC 문서 §18)을 할 때.

## TC-21 — 주석 줄바꿈 가드를 PTC 밖으로 넓힌다 · **열림**

**무엇을.** `CommentReflowTest` 의 검사 범위(`JAVA_ROOTS`, `MARKDOWN`)를 패키지 하나씩 넓혀, 걸리는 줄을 고친다.

**왜.** 포매터가 `join_lines_in_comments=false` 라 120 칸을 조금 넘긴 주석 줄은 끊기기만 하고 다시 붙지 않는다. 한두 단어가 줄 하나를
차지하는 자리가 저장소 전체에 약 450 개 있고(2026-11-07 휴리스틱 스캔), 가드는 그중 PTC 코드의 약 20 개만 막는다. 포매터 설정을 켜는
것은 모든 주석을 한 번에 다시 흘려 열린 브랜치가 모두 충돌하므로 [설계 기록](../design/tool/ptc-features-core.md) A.1 이 기각했다.

**어디.** `modules/aimon-core/src/test/java/at/aimon/core/architecture/CommentReflowTest.java`, `config/eclipse/eclipse-formatter.xml`.
설계는 [기능 마무리 설계](../design/tool/ptc-features-core.md) A.1 · §1.3 (OQ-A1). 2026-11-07 확인.

**언제 다시 볼까.** 다른 패키지에서 같은 줄바꿈이 리뷰 지적으로 나올 때 — 그 패키지를 범위에 넣는다.

## TC-22 — 중첩 호출 span 의 결과 내용 기록 · **열림**

**무엇을.** `ExecuteCode` 의 중첩 호출 TOOL span 이 결과 내용을 담을지 고르는 도구 문맥 키를 `TRACER` 옆에 둔다(지금은
`TracePayloadPolicy.summaryOnly()` 고정 — `isError` · `contentChars` 만).

**왜.** 어떤 도구 문맥 키도 `TracePayloadPolicy` 를 나르지 않고, 중첩 결과는 프로그램이 거르는 데이터라 흔히 크고 민감할 수 있어 요약만
남기기로 했다. 직접 호출 span 은 실행기의 정책을 따르므로, 내용 기록을 켠 배포에서도 코드 경로의 호출만 내용이 없다.

**어디.** `tools/code/DefaultToolCallBridge.java` 의 `dispatch`, `tools/ToolContextKeys.java`. 설계는
[기능 마무리 설계](../design/tool/ptc-features-core.md) A.5.2. 2026-11-07 확인.

**언제 다시 볼까.** 코드 경로 호출의 결과를 trace 에서 봐야 하는 디버깅 요청이 올 때.

## TC-23 — 슬래시 스킬 루프의 ITERATION · TOOL span · **열림**

**무엇을.** `LlmSkillExecutor` 의 루프가 iteration 마다 ITERATION span 을, 도구 호출마다 TOOL span 을 COMMAND span 아래에 연다.

**왜.** 그 루프에는 span 이 없다. 그래서 슬래시 스킬에서는 `ExecuteCode` 를 제안하지 못한 이유(`ptc.unavailable_reason`)가 남을 자리가
없고, 스킬의 직접 도구 호출은 trace 에 나오지 않는다 — 스킬의 `ExecuteCode` 가 낸 중첩 호출만 COMMAND span 아래에 붙는다(편차 A-DV2).

**어디.** `skill/execution/llm/LlmSkillExecutor.java`, `agent/impl/orca/CommandTrace.java`. 설계는
[기능 마무리 설계](../design/tool/ptc-features-core.md) A.5.4 · §1.3, [PTC](../design/tool/programmatic-tool-calling.md) §21. 2026-11-07 확인.

**언제 다시 볼까.** 슬래시 스킬이 `ExecuteCode` 를 제안받게 될 때(PTC PR 2) — 그때 이유를 남길 자리가 필요해진다.
