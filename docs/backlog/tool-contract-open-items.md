# 도구 계약 — 등록 항목 31건 (열림 20 · 닫힘 11)

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

## TC-20 — 셸 엔진 응답 쓰기 드레인 상한의 값 · **닫힘** *(2026-11-07)*

**무엇을.** 실행을 닫을 때 진행 중인 응답 쓰기를 기다리는 상한(5 초)을 실측으로 정한다.

**왜.** 5 초는 "OpenSandbox 에서 응답 쓰기 몇 번" 으로 고른 값이고 재지 않았다. 너무 짧으면 상한을 넘긴 쓰기가 지운 뒤의 실행
디렉터리에 파일을 남겨 다음 실행의 스윕까지 머물고(WARN), 너무 길면 샌드박스가 멈췄을 때 `ExecuteCode` 가 그만큼 늦게 끝난다.
`close()` 는 실제로 기다린 시간을 DEBUG 로 남긴다.

**어디.** `tools/code/shell/ShellProgramRun.java` 의 `REPLY_DRAIN`, `close()`. 2026-11-06 확인.

**언제 다시 볼까.** "a reply write was still in flight" WARN 이 운영에서 보일 때, 또는 PTC 지연 측정(PTC 문서 §18)을 할 때.

**어떻게 닫았나.** 재고 2 초로 줄였다([남은 일 설계](../design/tool/ptc-leftovers-core.md) §1.12). **전제가 달랐다** — 상한이 묶는
것은 "응답 쓰기 몇 번" 이 아니라 **이미 진행 중인 쓰기마다 시도 한 번**이다. `writeReply` 는 관문을 시도 하나에만 쥐고 다음 시도 전에
`closed` 를 다시 보므로, 닫힌 뒤에는 재시도도 그 사이의 대기도 시작하지 않고, 진행 중인 쓰기들은 나란히 돈다. 그래서 값은 가장 느린
백엔드의 쓰기 한 번에 넉넉한 상한이면 된다. `close()` 가 기다린 시간을 돌려주게 하고(패키지 비공개), 게이트된 측정
`ShellReplyDrainMeasurementTest`(`AIMON_MEASURE=1`)로 `LocalShell` 위에서 프로그램 200 개(각각 응답을 기다리지 않는 호출 8 개를 쏘고
바로 끝나며, 도구는 0–20 ms 뒤에 답한다)를 네 계열로 돌렸다(2026-11-07, macOS). 로컬: 쓰기 p50 0 · p99 1 · 최대 1 ms, `close()` 대기
최대 0 ms. 쓰기에 50 ms 를 더하면 쓰기 p99 62 ms · 대기 최대 61 ms, 250 ms 를 더하면 쓰기 최대 262 ms · 대기 최대 262 ms, 꼬리가 긴
지연(대개 20–60 ms, 50 번에 한 번 0.8–1.2 s)이면 쓰기 p99 1,070 ms · 대기 p99 68 · 최대 1,070 ms — 어느 계열에서도 대기가 그 계열에서
가장 느린 쓰기 하나를 넘지 않았다(테스트가 "+50 ms 이내" 로 단언한다). 2 초인 이유는 비대칭이다: 상한을 넘기면 WARN 하나와 지워질 실행
디렉터리의 파일 하나(다음 스윕이 지운다)이고, 너무 길면 멈춘 샌드박스에서 모든 `ExecuteCode` 결과가 그만큼 늦는다. 2 초는 250 ms 원격
쓰기의 8 배, 꼬리 계열 최악의 약 두 배다. WARN 은 아직 진행 중인 쓰기 수를 말한다. **잴 수 없었던 것**: 실제 OpenSandbox 의 응답 쓰기
지연 — `aimon-sandbox` 는 다른 저장소이고 돌아가는 샌드박스가 필요하다. 다시 볼 계기는 위의 그대로 운영에서 보이는 WARN 이다.
검사: `ShellProgramRunTest`(`close()` 가 돌려주는 대기), `ShellReplyDrainMeasurementTest`.

## TC-21 — 주석 줄바꿈 가드를 PTC 밖으로 넓힌다 · **닫힘** *(2026-11-07)*

**무엇을.** `CommentReflowTest` 의 검사 범위(`JAVA_ROOTS`, `MARKDOWN`)를 패키지 하나씩 넓혀, 걸리는 줄을 고친다.

**왜.** 포매터가 `join_lines_in_comments=false` 라 120 칸을 조금 넘긴 주석 줄은 끊기기만 하고 다시 붙지 않는다. 한두 단어가 줄 하나를
차지하는 자리가 저장소 전체에 약 450 개 있고(2026-11-07 휴리스틱 스캔), 가드는 그중 PTC 코드의 약 20 개만 막는다. 포매터 설정을 켜는
것은 모든 주석을 한 번에 다시 흘려 열린 브랜치가 모두 충돌하므로 [설계 기록](../design/tool/ptc-features-core.md) A.1 이 기각했다.

**어디.** `modules/aimon-core/src/test/java/at/aimon/core/architecture/CommentReflowTest.java`, `config/eclipse/eclipse-formatter.xml`.
설계는 [기능 마무리 설계](../design/tool/ptc-features-core.md) A.1 · §1.3 (OQ-A1). 2026-11-07 확인.

**언제 다시 볼까.** 다른 패키지에서 같은 줄바꿈이 리뷰 지적으로 나올 때 — 그 패키지를 범위에 넣는다.

**어떻게 닫았나.** 패키지 하나씩이 아니라 한 번에 넓혔다([남은 일 설계](../design/tool/ptc-leftovers-core.md) PR 3 · §6).
`CommentReflowTest` 는 이제 모든 `modules/*/src/main/java` · `src/test/java`(파일 1,000 개 넘게, 모듈 15 개 이상을 단언한다)와
`docs/**/*.md`, 저장소 루트의 `README` · `CONTRIBUTING` · `SECURITY` · `CODE_OF_CONDUCT` · `CHANGELOG`(와 `.ko.md`)를 읽는다.
`CHANGELOG` 는 `[Unreleased]` 절만 읽는다 — 배포된 절은 기록이라 다시 흘리지 않는다. 걸린 줄은 버리는 스크립트로 기계적으로 다시
흘렸고, 낱말은 하나도 바뀌지 않았다(`git diff -w --word-diff` 로 확인). **규모가 달랐다** — 약 450 개는 다른 휴리스틱으로 센
값이었고, PR 1 의 판정으로 `e65443bc` 에서 저장소 전체를 다시 세니 Java 865 줄(파일 436 개, `aimon-core` 600 · `aimon-session-routing`
117 · `aimon-session-mongodb` 43 · 나머지 105), Markdown 116 줄이었다. Markdown 은 의도한 짧은 줄이 섞여 판정을 보정했다 — 다음 줄이
새 블록(링크 · `**` · 코드)이거나, 짧은 줄이 문장을 끝내고 다음 줄이 대문자나 `**` 로 시작하거나, 다음 줄의 첫 토큰이 붙을 수 없거나,
다음 줄이 통째로 둔 링크 때문에 120 칸을 넘으면 걸리지 않는다. 보정 뒤 20 줄 가운데 17 줄을 다시 흘리고 셋은 이유와 함께 `ALLOWED`
에 넣었다. 검사: `CommentReflowTest`(`noOrphansInJava` · `javaScopeCoversEveryModule` · `noOrphansInMarkdown` · `predicate` ·
`markdownCalibration`).

## TC-22 — 중첩 호출 span 의 결과 내용 기록 · **닫힘** *(2026-11-07)*

**무엇을.** `ExecuteCode` 의 중첩 호출 TOOL span 이 결과 내용을 담을지 고르는 도구 문맥 키를 `TRACER` 옆에 둔다(지금은
`TracePayloadPolicy.summaryOnly()` 고정 — `isError` · `contentChars` 만).

**왜.** 어떤 도구 문맥 키도 `TracePayloadPolicy` 를 나르지 않고, 중첩 결과는 프로그램이 거르는 데이터라 흔히 크고 민감할 수 있어 요약만
남기기로 했다. 직접 호출 span 은 실행기의 정책을 따르므로, 내용 기록을 켠 배포에서도 코드 경로의 호출만 내용이 없다.

**어디.** `tools/code/DefaultToolCallBridge.java` 의 `dispatch`, `tools/ToolContextKeys.java`. 설계는
[기능 마무리 설계](../design/tool/ptc-features-core.md) A.5.2. 2026-11-07 확인.

**언제 다시 볼까.** 코드 경로 호출의 결과를 trace 에서 봐야 하는 디버깅 요청이 올 때.

**어떻게 닫았나.** 도구 문맥 키 `ToolContextKeys.TRACE_PAYLOAD_POLICY`(write-once)를 `TRACER` 가 묶이는 모든 자리 — 메인 루프의
iteration 문맥(같은 "없을 때만" 조건으로), 슬래시 명령 문맥, 포크의 iteration 문맥, 루틴 스텝 문맥 — 에 그 실행기의 정책으로 묶고,
브리지가 그것을 읽는다. 키가 없으면 요약만이라 `TRACER` 만 묶는 임베더는 바뀌는 것이 없다. 정책은 하나다 — 내용 기록을 켠 운영자는 같은
도구의 결과를 직접 경로에서 이미 받고, 상한은 같은 `truncate` 다([남은 일 설계](../design/tool/ptc-leftovers-core.md) §1.11, OQ-3).
"중첩 결과는 크고 민감할 수 있다" 는 왜의 문장은 참이지만, 그 판단은 이미 직접 경로에서 운영자가 내린 것이었다.
검사: `DefaultToolCallBridgeTracingTest#payloadPolicyCapturesContent`·`#payloadPolicyAbsentIsSummaryOnly`,
`OrcaAgentExecutorTracingTest#payloadPolicyBoundBesideTheTracer` 와 슬래시 명령·포크·루틴의 트레이싱 테스트.

## TC-23 — 슬래시 스킬 루프의 ITERATION · TOOL span · **닫힘** *(2026-11-07)*

**무엇을.** `LlmSkillExecutor` 의 루프가 iteration 마다 ITERATION span 을, 도구 호출마다 TOOL span 을 COMMAND span 아래에 연다.

**왜.** 그 루프에는 span 이 없다. 그래서 슬래시 스킬에서는 `ExecuteCode` 를 제안하지 못한 이유(`ptc.unavailable_reason`)가 남을 자리가
없고, 스킬의 직접 도구 호출은 trace 에 나오지 않는다 — 스킬의 `ExecuteCode` 가 낸 중첩 호출만 COMMAND span 아래에 붙는다(편차 A-DV2).

**어디.** `skill/execution/llm/LlmSkillExecutor.java`, `agent/impl/orca/CommandTrace.java`. 설계는
[기능 마무리 설계](../design/tool/ptc-features-core.md) A.5.4 · §1.3, [PTC](../design/tool/programmatic-tool-calling.md) §21. 2026-11-07 확인.

**언제 다시 볼까.** 슬래시 스킬이 `ExecuteCode` 를 제안받게 될 때(PTC PR 2) — 그때 이유를 남길 자리가 필요해진다. PR 2 에서 그렇게
되었다(2026-11-07): 포크 루프는 이 속성을 갖췄고 슬래시 스킬 루프만 남았다. 이제 다시 볼 때다.

**어떻게 닫았나.** `LlmSkillExecutor` 가 LLM 호출마다 호출자의 메타데이터가 가리키는 span(사용자 슬래시 경로에서는 COMMAND span) 아래에
`iteration#k` ITERATION span 을 열고 — 속성 `iteration`, `invokerType=MAIN_AGENT`, `skill=<이름>`, 그 버전의 제안에서 읽은
`ptc.unavailable_reason` — 그 응답의 도구 호출마다 TOOL span(`tool.path=direct`, `skill`)을 그 아래에 연다. 호출의 문맥은 TOOL span 을
가리키도록 돌려, 스킬의 `ExecuteCode` 가 낸 중첩 호출은 COMMAND span 이 아니라 그 호출의 TOOL span 아래에 붙는다(편차 A-DV2 는 그렇게
바뀌었다). LLM 호출은 메타데이터 오버로드로 보내되 span 위치만 싣는다(`Spans.traceCarrier`) — 전에는 귀속이 없었고, 턴의 귀속을 실으면
사용량이 다른 곳에 매겨진다. `InvokerType` 에 새 값은 두지 않았다. 모든 span 연산은 `Spans.*Safely` 라 트레이서가 던져도 스킬은 계속하고,
트레이서가 없거나 부모가 없으면 아무것도 열지 않는다([남은 일 설계](../design/tool/ptc-leftovers-core.md) §2.1, §5). 검사:
`LlmSkillExecutorTracingTest`(트리·속성·LLM 메타데이터·`ExecuteCode` 중첩·`ptc.unavailable_reason`·최대 iteration 의 오류·트레이싱 꺼짐·
사용량 귀속). 무엇을의 "iteration 마다" 는 정확히는 "LLM 호출마다" 다 — 마지막 답의 호출도 자기 span 을 갖는다.

## TC-24 — 호출자가 PTC 를 켜지 않은 곳에서 쓸 수 있는 서브에이전트 · **닫힘** *(2026-11-07)*

**무엇을.** 서브에이전트·스킬 정의가 호출자의 programmatic 설정과 상관없이 `ExecuteCode` 를 쓸 수 있게 하는 선언(예:
`programmatic-tools: inherit` 같은 표지, 또는 운영자가 허용하는 "자기 선언만" 모드)을 둘지 정한다.

**왜.** PTC 기능 마무리 PR 2 는 가장 좁은 규칙을 골랐다 — 실효 = 자기 선언 ∩ 호출자이고, PTC 없는 호출자는 "아무것도 없음" 이다(편차
B-DV1, OQ-B5). 그래서 코드로만 일하도록 만든 서브에이전트는 PTC 를 켜지 않은 메인 에이전트 아래에서는 `ExecuteCode` 를 받지 못하고,
운영자는 메인 에이전트에도 `programmatic-tools` 를 열어야 한다. 넓히는 쪽의 선언은 "에이전트는 좁히기만" 과 부딪히므로 운영자 수준의
허가가 필요하다.

**어디.** `agent/tool/ProgrammaticToolSettings.java` 의 `narrowedBy`, `subagent/DefaultSubagentExecutionManager.java` 의
`applyProgrammaticCeiling`. 설계는 [기능 마무리 설계](../design/tool/ptc-features-core.md) B.1.1, [PTC](../design/tool/programmatic-tool-calling.md)
§7.2 · §21. 2026-11-07 확인.

**언제 다시 볼까.** `caller-ceiling` 경고("sets programmatic-tools, but its caller allows none of them")가 실제 배치에서 보고될 때.

**어떻게 닫았나.** **결정: 두지 않는다** — 서브에이전트 쪽의 `programmatic-tools: inherit` 같은 표지도, 운영자가 허용하는 "자기 선언만"
모드도. 전제는 소스로 확인했다(README 규칙 4): PTC 없는 호출자는 정말 "아무것도 없음" 이고(`narrowedBy` 가 한쪽이라도 꺼져 있으면
`none()`), 운영자는 메인 에이전트에 `programmatic-tools` **와** 허용 목록의 `ExecuteCode` 를 함께 열어야 한다 — 포크의 허용 목록도 호출자의
것과 교집합이기 때문이다. 설계 문서는 넓히기를 주장하지 않는다(PTC §7.2, §21 B-DV1 "이 비대칭은 의도다"). 이유는 넷이다.
첫째, 모든 천장 — 허용 목록, 부작용 상한, programmatic 설정, 그리고 이번의 TC-25 · TC-26 — 은 "루트 에이전트의 운영자가 정한 것이 그
아래 전부를 묶는다" 위에 서 있다. 스스로 여는 자식 하나가 다른 모든 천장을 "자식 정의를 다 읽어 봐야 아는 것" 으로 만든다. 둘째,
요청하는 운영자 수준의 허가는 이미 있고 루트에 있다 — 메인 에이전트에 `programmatic-tools` 를 여는 것이 그 허가다. 자식 쪽 두 번째
손잡이는 같은 허가를 루트가 아닌 곳에 보이게 해, 에이전트 파일 하나를 읽고 천장을 아는 감사 경로를 약하게 한다. 셋째, 서브에이전트
정의는 사용자 계층에서 온다(사용자 `agents/*.md`, 스킬 frontmatter). 운영자가 아닌 사람이 쓸 수 있는 파일이 §7.1 과 `DataReach` 가 일부러
막은 코드 경로의 데이터 범위를 여는 길이 된다. 넷째, 계기가 오지 않았다 — 실제 배치에서 `caller-ceiling` WARN 이 보고된 적이 없다.
**남는 것(등록하지 않는다).** "자식에게만 PTC" — 루트 자신의 모델은 `ExecuteCode` 를 받지 않고 포크만 받게 하는 것은 지금 표현할 수 없다.
필요해지면 불변식을 지키는 모양은 자식 쪽 허가가 아니라 **루트 쪽** 설정(예: 천장으로는 싣되 제안하지는 않는 루트의
`programmatic-tools`)이다. 여기서 설계하지 않는다([남은 일 설계](../design/tool/ptc-leftovers-core.md) §2.4). 정했고 할 일이 남지 않았으므로
닫힘이다(README 규칙 4).

## TC-25 — 스킬·포크 안에서 띄운 백그라운드 `Workflow` 실행의 천장 · **닫힘** *(2026-11-07)*

**무엇을.** `WorkflowTool` 의 백그라운드 실행이 그것을 띄운 실행(슬래시 스킬, 포크)의 허용 목록과 programmatic 설정을 천장으로 받게
한다.

**왜.** 백그라운드 실행은 런타임의 기본 런처(`OrcaAgentRuntimeFactory` 의 워크플로 기반 `SubagentLaunchContext`)로 돌아, 에이전트의
허용 목록·programmatic 설정으로만 좁혀진다. 슬래시 스킬이나 포크가 띄운 백그라운드 실행은 그 스킬·포크보다 넓을 수 있다. 허용 목록의
기존 동작이고, PTC 에서는 워크플로 서브에이전트가 programmatic 설정을 갖지 않아 지금은 효과가 없다(편차 B-DV1 의 예외).

**어디.** `tools/workflow/WorkflowTool.java` 의 `runInBackground`, `agent/impl/orca/OrcaAgentRuntimeFactory.java` 의 워크플로 런처.
설계는 [기능 마무리 설계](../design/tool/ptc-features-core.md) §2. 2026-11-07 확인.

**언제 다시 볼까.** 워크플로 서브에이전트가 programmatic 설정을 갖게 될 때(`InlineSubagentResolver` 가 그것을 복사하기 시작할 때),
또는 백그라운드 런처를 호출마다 짓게 될 때.

**어떻게 닫았나.** 제출이 제출자의 천장을 싣고 실행기가 자기 기본 런치 문맥을 그것으로 좁힌다. 새 값 `workflow.BackgroundSubmission`
(쿼터 가드, 호출자의 허용 목록, 호출자의 programmatic 설정 — 없으면 기본 문맥의 것)을 `Workflow` · `WorkflowJs` 가
`tools.workflow.BackgroundSubmissions.of(ToolContext)` 로 짓고, `WorkflowRunner.runInBackground(script, runId, QuotaGuard)` 는
`runInBackground(script, runId, BackgroundSubmission)` 로 **바뀌었다**(호환을 깨는 변경, CHANGELOG). `DefaultWorkflowRunner` 는 실행을
점유하기 **전에** 허용 목록을 교집합으로, programmatic 설정을 제출자의 것을 기본 문맥의 것으로 좁힌 값으로 만든다 — 점유 뒤에 던지면 같은
id 의 다음 제출이 모두 "이 노드에 없다" 로 실패한다. 둘이 겹치지 않으면 `BackgroundRunRefusedException` 이고, 두 도구는 그것을 "잘못된
매개변수" 가 아니라 거절로 보고한다. 같은 요청이 더 좁은 호출자에게서 오면 넓은 실행에 합류하지 않도록 두 도구가 실행 id 에 천장의
다이제스트(`ceilingDigest()`)를 붙인다. 호출마다 실행기를 짓는 대안은 기각 — 실행은 호출보다 오래 살아야 하고 풀과 재개 캐시는 에이전트
단위다([남은 일 설계](../design/tool/ptc-leftovers-core.md) §2.2, §5). 검사: `DefaultWorkflowRunnerBackgroundTest`(교집합·단조성·점유 전
거절·기본 메서드 위임), `WorkflowToolBackgroundModeTest` · `GraalJsWorkflowToolTest`(천장 둘이면 id 둘, 하나면 합류, 거절 문구),
`BackgroundWorkflowCeilingE2EIntegrationTest`(허용 목록 `[Read, Workflow]` 인 슬래시 스킬의 백그라운드 실행 — 서브에이전트의
`callerAllowedTools` ⊆ `[Read, Workflow]`). 왜의 "지금은 효과가 없다" 는 programmatic 쪽에만 맞다 — 허용 목록 쪽은 지금 효과가 있고,
programmatic 쪽은 워크플로 서브에이전트가 그 설정을 갖게 되는 변경이 이 구멍을 다시 열지 못하게 미리 이어 둔 것이다.

## TC-26 — 모델이 부른 인라인 스킬에는 스킬 루프가 없다 · **닫힘** *(2026-11-07)*

**무엇을.** 모델이 `Skill` 도구로 부른 인라인 스킬도 스킬 자신의 허용 목록과 programmatic 설정을 실행 시점에 지키게 할지 정한다.

**왜.** `SkillTool` 은 인라인 스킬의 지시문을 메인 턴에 주입할 뿐이라(`LlmSkillExecutor` 루프는 사용자 `/스킬` 경로에만 있다), 그
턴에서 일어나는 도구 호출과 그 턴이 띄운 포크의 천장은 에이전트다. PTC 기능 마무리 PR 2 의 설계는 모델이 부른 경우도 스킬로 좁혀진다고
보았지만(B.6 사례 2), 그 경로에는 좁힐 루프가 없다 — 테스트는 실제 동작(포크가 에이전트의 설정을 받는다)을 고정한다. 허용 목록에서도
같은 비대칭이 이미 있다.

**어디.** `tools/skill/SkillTool.java` 의 인라인 분기, `skill/execution/llm/LlmSkillExecutor.java`. 설계는
[기능 마무리 설계](../design/tool/ptc-features-core.md) §2, 테스트 `SlashSkillProgrammaticCeilingE2EIntegrationTest`. 2026-11-07 확인.

**언제 다시 볼까.** 스킬의 `allowed-tools` 를 모델 호출 경로에서도 강제하자는 요청이 올 때 — 둘은 같은 변경이다.

**어떻게 닫았나.** 좁히기만 하는 실행 단위 천장을 두고, `SkillTool` 이 좁히고 루프가 iteration 경계에서 읽는다. 새 타입
`agent.tool.InlineSkillCeiling`(허용 목록, programmatic 설정, 좁힌 스킬들, 버전)을 메인 루프(턴마다, 에이전트의 값에서), 포크(실행마다,
실효 범위에서), 슬래시 스킬 루프(실행마다, 스킬의 실효 값에서)가 도구 문맥 키 `ToolContextKeys.INLINE_SKILL_CEILING`(write-once)으로
묶는다. 인라인 활성화는 허용 목록을 교집합으로, programmatic 설정을 스킬의 선언을 현재 값으로 좁힌 것으로 바꾸고(키가 없는 스킬은
`none()` — 슬래시 경로와 같다, OQ-1), 겹치지 않으면 지시문 없이 도구 오류로 거절한다. 루프는 다음 iteration 부터 제안·환경 사실·디스패처를
스냅샷으로 다시 만든다(`toolinvocation.InlineSkillNarrowing`). 환경 사실을 스냅샷의 허용 목록으로 다시 계산하므로, `Bash` 를 패턴으로
좁힌 스킬 뒤에는 셸 엔진이 꺼진다. 좁혀진 디스패처가 `CALLER_*` 를 싣기 때문에 그 뒤에 띄운 포크와 백그라운드 실행(TC-25)도 스킬로
좁혀진다. 같은 응답의 호출은 이미 제안된 천장으로 돌고, 좁혀진 턴은 승인 대기로 멈추지 않는다 — 멈춘 실행을 이어 가는 다음 실행은
에이전트의 천장에서 시작하므로, 그 턴의 승인 필요 스킬은 "새 턴에서 다시 요청하라" 는 오류로 닫힌다. 모델은 스킬 결과의 한 줄로
좁혀진 사실을 듣는다([남은 일 설계](../design/tool/ptc-leftovers-core.md) §2.3, §5, PTC §7.2). 검사: `InlineSkillCeilingTest`,
`SkillToolTest`, `SlashSkillProgrammaticCeilingE2EIntegrationTest`(경우 2 가 뒤집혔다 — 모델이 부른 `Skill(probe)` 뒤의 포크는 이제 스킬로
좁혀진다. 다음 iteration 의 제안, 같은 응답의 호출, 포크 루프, 메인·포크·슬래시 루프의 패턴 `Bash`), `OrcaAgentExecutorSkillSuspendTest`
(좁혀진 턴은 멈추지 않는다, 좁히기 전은 멈춘다). 무엇을이 "정할지" 라고 물은 것은 정했다 — 강제한다. 좁히기만 하므로 결정보다 구현의 일이었다.

## TC-27 — Python `ExecuteCode` 설명이 함수를 어디서 얻는지 말하지 않는다 · **닫힘** *(2026-11-07)*

**무엇을.** Python 엔진의 `ExecuteCode` 설명(`ExecuteCodeDescription`, `ToolApiRenderer.renderPython`)에 함수가 이미 정의된 전역이라
`import` 하지 않는다는 것과, 프로그램이 이미 이벤트 루프 안에서 돈다는 것(`asyncio.run` 을 부르지 말고 최상위 `await` 를 쓴다)을 적는다.

**왜.** 실제 모델 E2E(`:aimon-cli:llmTest`, 2026-11-07)에서 `gpt-4o-mini` 와 `gpt-4o` 가 셸 변형의 첫 프로그램들을
`from functions import GetHostStatus, GetHostLoad`(`ModuleNotFoundError`)나 `asyncio.run(main())`("cannot be called from a running
event loop")으로 시작했다. 설명은 "Functions:" 아래에 `async def` 선언을 나열할 뿐이라 모델이 모듈을 추측한다. `gpt-4o` 는 같은 오류로
네 번 실패해 중첩 호출 0 으로 끝났고, `gpt-4o-mini` 는 열 번째 호출에서야 통과했다. [PTC](../design/tool/programmatic-tool-calling.md)
§18 의 "실패 유형 → 렌더링을 바꾼다" 그대로다. 문구는 모델이 보는 텍스트라 바꾸면 실제 모델로 다시 재야 한다.

**어디.** `tools/code/ExecuteCodeDescription.java`, `tools/code/ToolApiRenderer.java`. 하네스는 CONTRIBUTING.md 의
"Real-model ExecuteCode E2E". 2026-11-07 확인.

**언제 다시 볼까.** 다음에 실제 모델 E2E 를 돌릴 수 있을 때 — 문구를 고치고 같은 하네스로 전후를 잰다.

**어떻게 닫았나.** 같은 PR 2 의 리뷰 1 뒤에 고쳤다. Python 설명의 첫 항목이 "함수는 이미 전역으로 정의되어 있으니 import 하지 말고 바로
부른다", "프로그램은 이미 이벤트 루프 안에서 돈다 — 최상위 `await` 를 쓰고 `asyncio.run()` 을 부르지 않는다", "문자열이 JSON 이면
`json.loads` 로 직접 파싱한다" 를 말하고, 목록 머리말은 "Functions (already defined; do not import them):" 다. JavaScript 설명에도
"`JSON.parse` 로 직접 파싱한다" 를 더했다(`gpt-4o-mini` 가 JSON 문자열 결과를 객체로 읽은 실패). 같은 하네스로 잰 전후 —
`gpt-4o` Python: 고치기 전 프로그램 넷이 모두 `from functions import …` 로 실패(중첩 호출 0), 고친 뒤 프로그램 하나가 중첩 호출 8 번을
모두 성공하고 답이 맞았다. `gpt-4o-mini` 는 JavaScript 가 프로그램 하나·호출 10 번·정답으로 바뀌었고, Python 은 여전히 프로그램
열한 개 중 다섯이 `asyncio.run` 등으로 실패하고 출력 없이 끝난 프로그램을 되풀이하다 `MAX_ITERATIONS` 에서 멈췄다 — 약한 모델의
한계로 보고 기본 모델(OQ-B2)은 두었다. 검사: `ExecuteCodeToolTest#descriptionSaysWhereFunctionsComeFrom`.

## TC-28 — 스크립트 오류의 마지막 줄이 캐럿만일 수 있다 · **닫힘** *(2026-11-07)*

**무엇을.** `ExecuteCodeObservation.failure` 가 오류 메시지의 마지막 줄만 싣는 규칙을, 마지막 줄이 소스 발췌의 캐럿(`^`)일 때는 메시지
줄(예: GraalJS 의 `SyntaxError: … Expected …`)을 싣도록 고친다.

**왜.** 실제 모델 E2E(2026-11-07)에서 GraalJS 구문 오류가 모델에게 `ExecuteCode script error:  ^` 로만 갔다 — 무엇이 틀렸는지가
빠졌다. 구문 오류 메시지는 "메시지, 소스 줄, 캐럿" 순의 여러 줄이라 마지막 줄 규칙이 정확히 쓸모없는 줄을 고른다. 모델은 다음
프로그램을 다시 썼지만 이유를 모른 채였다.

**어디.** `tools/code/ExecuteCodeObservation.java` 의 `lastLine`, GraalJS 엔진의 오류 메시지 구성(`aimon-graaljs` 의
`at.aimon.graaljs.code`). 2026-11-07 확인.

**언제 다시 볼까.** TC-27 과 함께 — 둘 다 모델이 스크립트 오류에서 회복하는 비용이다.

**어떻게 닫았나.** **고칠 곳은 코어 하나였다** — 어디 줄이 함께 적은 GraalJS 엔진의 메시지는 이미 맞았고, 코어가 고른 줄이 틀렸다.
`ExecuteCodeObservation.lastLine` 을 `errorLine` 으로 바꿨다: 세 줄 이상이고 마지막 줄이 캐럿 줄이면 첫 줄(메시지 줄)을 싣고, 소스
줄과 캐럿을 두 칸 들여 정렬을 지킨 채 붙인다. 소스 줄이 200 자를 넘으면(압축된 코드) 메시지 줄만. 그 밖의 메시지는 마지막 줄 규칙
그대로다. 엔진은 바꾸지 않았고, 코어가 기대는 메시지 모양을 `aimon-graaljs` 의 테스트가 고정한다. 검사: `ExecuteCodeObservationTest`,
`GraalJsCodeExecutionEngineTest#syntaxErrorMessageShape`, `GraalJsSyntaxErrorObservationTest`(관찰에 `SyntaxError`·`Expected`).

## TC-29 — 모델이 보는 `[logs]` 에 호스트 경로가 실린다 · **닫힘** *(2026-11-07)*

**무엇을.** `ExecuteCode` 관찰의 `[logs]` 절(셸 엔진의 stderr 꼬리)에서 런타임 자신의 프레임과 호스트 경로를 걷어 낼지 정한다.

**왜.** 실제 모델 E2E(2026-11-07)에서 Python 스크립트 오류의 traceback 이 그대로 모델에게 갔다 —
`/var/folders/…/aimon-run-…/aimon_ptc.py` 의 런타임 프레임과 사용자의 pyenv 경로까지. 모델에게 쓸모 있는 것은 `<program>` 프레임과
마지막 예외 줄뿐이고, 나머지는 토큰을 쓰며 호스트 배치를 드러낸다. 샌드박스에서는 경로가 샌드박스 안의 것이라 덜하지만 로컬 실행에서는
그대로다. TC-28 과 같은 "모델에게 가는 오류 텍스트" 문제다.

**어디.** `tools/code/ExecuteCodeObservation.java` 의 `appendSideChannels`, 셸 엔진 런타임 `aimon_ptc.py` 의 traceback 출력.
2026-11-07 확인(PR 2 리뷰 1 의 비차단 지적).

**언제 다시 볼까.** TC-28 과 함께.

**어떻게 닫았나.** 출처에서 고쳤다 — 런타임이 잡히지 않은 예외를 **프로그램 자신의 프레임만**으로 찍는다(`<program>` 프레임과 그 코드
줄, 예외 줄; 예외 사슬은 다섯 단계까지). 같은 출력이 다섯 자리에 걸린다: 메인 스레드, `threading.excepthook`, `sys.unraisablehook`,
`asyncio` 로거, `warnings.showwarning`. 도구 함수·`call_tool` 의 코드 객체는 파일 이름이 `<runtime>` 이라 태스크 repr 에도 런타임 경로가
실리지 않는다([셸 엔진](../design/tool/programmatic-tool-calling-shell.md) §6). `appendSideChannels` 에서 경로를 지우는 길은 기각했다 —
`[logs]` 는 임의의 프로그램 출력이라 데이터와 런타임 프레임을 정규식이 가르지 못한다. **남은 구멍 하나**: `DONE` 없이 끝난 실행은 셸
자신의 stderr 꼬리를 `[logs]` 로 싣는다 — TC-31. 검사: `ShellRuntimeProgramTest`(어느 로그에도 `aimon_ptc` 와 절대 `.py` 경로가 없다).
실제 모델 E2E(2026-11-07, `gpt-4o`·`gpt-4o-mini` × JavaScript·Python)는 네 실행 모두 프로그램 하나로 통과해 오류 경로를 밟지 않았다.

## TC-30 — 호출자 천장이 포크의 PTC 를 없앨 때 iteration span 이 이유를 싣지 않는다 · **열림**

**무엇을.** 포크 자신의 정의는 `programmatic-tools` 로 opt-in 했는데 호출자의 실효 설정이 그것을 "아무것도 없음" 으로 좁혀
`ExecuteCode` 가 제안되지 않을 때, 그 포크의 iteration span 에도 `ptc.unavailable_reason` 을 싣는다.

**왜.** 지금은 싣지 않는다. 포크의 제안 문맥이 이미 좁혀진 설정(`none`)을 들고, `ExecuteCodePlan` 은 제안 문맥이 코드에 도구를 여는
에이전트에만 이유를 보고하기 때문이다(`reportedReason`). 운영자에게 알리는 것은 `DefaultSubagentExecutionManager` 의 WARN("caller
allows none") 하나뿐이고, 그것도 서브에이전트 이름마다 JVM 에 한 번이다 — trace 만 보는 사람은 그 포크가 왜 코드를 못 썼는지 알 수
없다. [남은 일 설계](../design/tool/ptc-leftovers-core.md) §1.9 경우 3 은 이 속성이 있다고 보고 테스트를 적었다가 빌드에서 틀린
전제로 드러났다(§4).

**어디.** `tools/code/ExecuteCodePlan.java` 의 `of` · `reportedReason`, `subagent/DefaultSubagentExecutionManager.java` 의
`applyProgrammaticCeiling`, `subagent/execution/DefaultSubagentExecutor.java` 의 `IterationTrace`. 고정한 테스트는
`NestedForkProgrammaticCeilingE2EIntegrationTest#middleForkWithoutPtc`. 2026-11-07 확인.

**언제 다시 볼까.** 포크가 `ExecuteCode` 를 못 받은 이유를 trace 로 찾는 요청이 올 때 — 또는 TC-23(슬래시 스킬 루프의 span)과 함께.

## TC-31 — `DONE` 없이 끝난 셸 엔진 실행의 `[logs]` 에 셸 stderr 가 실린다 · **열림**

**무엇을.** 런타임이 `DONE` 없이 끝났을 때 셸 자신의 stderr 꼬리(`ShellProgramRun.shellStderr()`)를 모델이 보는 `[logs]` 로 싣는 대신
호스트 로그에만 남길지 정한다.

**왜.** `ShellCodeExecutionEngine.classify` 는 `DONE` 없는 종료에서 런타임이 보낸 로그가 비었으면 셸 stderr 를 결과의 `logs` 로 쓴다
(`logs.isEmpty() ? stderr : logs`). TC-29 가 런타임의 출력을 프로그램 프레임만으로 줄였지만, 런타임이 출력기를 설치하기 전에 죽거나
(`main` 앞부분의 예외) `python3` 자체가 실패하면 그 traceback 이 실행 디렉터리의 호스트 경로를 실은 채 모델에게 간다. 드문 경로이고
모델이 원인을 알아야 할 수도 있어(`python3` 이 없음) 이 변경에서는 고치지 않았다([남은 일 설계](../design/tool/ptc-leftovers-core.md)
§1.7, §4).

**어디.** `tools/code/shell/ShellCodeExecutionEngine.java` 의 `classify`, `tools/code/shell/ShellProgramRun.java` 의 `shellStderr`.
2026-11-07 확인.

**언제 다시 볼까.** `DONE` 없는 종료가 운영에서 보일 때, 또는 TC-19 의 `aimon-sandbox` 쪽 후속과 함께.
