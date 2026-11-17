# 잘린 응답과 정체 가드 — 네 도구 루프의 종료 판정

> Status: **IMPLEMENTED** — 적용 대상 `aimon-core`(`agent.budget` · 턴 · 포크 · 스킬 루프 · deriver),
> `aimon-llm-anthropic` · `aimon-llm-openai`(stop reason 매핑과 reasoning 토큰 카운터).
> 이 문서에 걸린 열린 항목은 없다. 남은 일은 [backlog](../../backlog/agent-execution-open-items.md) 가 정본이다.
>
> 관련 문서: [턴 루프의 구조](orca-executor.md) · [실행 중단](interrupt.md) ·
> [서브에이전트 실행 정책](../subagent/execution-policy.md) · [서브에이전트 실행](../subagent/execution.md) ·
> [`ToolCallDispatcher`](../tool/tool-call-dispatcher.md) · [스트리밍](../llm/streaming.md) ·
> [Anthropic thinking](../llm/anthropic-thinking.md)

이 문서는 LLM 응답을 받아 도구를 부르는 루프 넷이 두 가지 상황을 같은 규칙으로 끝내는 방식을 정한다. 하나는
provider 가 출력 한도(`max_tokens`)에서 자른 응답이고, 다른 하나는 도구를 계속 부르지만 아무 진전이 없는 반복이다.

잘린 응답은 두 모양으로 온다 — 도구 호출이 없는 최종 답, 그리고 도구 호출을 담은 응답. 네 도구 루프는 두 모양
모두에 같은 답을 준다. 루프마다 다른 답을 주면 같은 stop reason 이 한 경로에서는 완결된 답으로 보이고 다른
경로에서는 인자 없는 도구 호출로 실행되기 때문이다.

루프 안에서 판정이 어느 자리에 서는지(정상 종료 분기 앞, 큐 드레인 앞)와 중립 `StopReason` 의 정의는
[`orca-executor.md`](orca-executor.md) 가 정본이다. 이 문서는 판정의 규칙만 다룬다.

---

## 1. 도구 루프 넷

main 소스에서 `LlmResponse.hasToolUses()` 로 도는 루프는 넷이다. 다시 셀 때는
`git grep -n 'hasToolUses()' -- 'modules/*/src/main/**'` 에서 `LlmResponse` 에 대한 호출만 남긴다 — 나머지는
`Message.hasToolUses()` 를 부르는 압축·codec·provider 변환기로, 루프를 이루지 않는다. `TruncatedResponses` 를 쓰는
main 클래스도 같은 넷이다.

| 루프 | 위치 | 잘린 응답 처리 | 정체 가드 | 상한 | 도달 가능 | 인터럽트 가능 |
|---|---|---|---|---|---|---|
| 턴 | `OrcaAgentExecutor` | 최종 답 → 마커 + `TRUNCATED`, 도구 응답 → 전부 거절 | 있음 | 에이전트 `maxIterations`(기본 무제한) + 요청 예산 | 예 | 예 |
| 포크 | `DefaultSubagentExecutor` | 최종 답 → 마커 + `TRUNCATED`(grace round 는 예외, §2.2), 도구 응답 → 전부 거절 | 있음 | 서브에이전트 `max-iterations`(기본 1000) + 실행 정책이 있으면 그 예산([execution-policy](../subagent/execution-policy.md)) | 예 | 예 |
| 스킬 | `LlmSkillExecutor` | 최종 답 → 마커 + `isTruncated()`, 도구 응답 → 전부 거절 | 있음 | 스킬 `max-iterations`(기본 100), 예산 없음 | 예 — 슬래시 커맨드가 이 루프를 탄다 | **아니오** — 슬래시 커맨드는 인터럽트할 수 없다 |
| deriver | `ReActLlmDeriver` | 도구 응답 → 전부 거절만 | 없음 | 6 iteration + 토큰 예산 | 아니오 — main 소스에 생성 지점이 없다 | — |

포크의 grace round(한도에 닿은 정책 포크가 한 번 더 하는 호출)는 다섯째 루프가 아니다. 루프가 끝난 뒤의 호출
하나이고, 잘림 규칙에서는 §2.2 의 예외 한 줄로 다룬다.

`aimon-graaljs` 의 워크플로와 `ExecuteCode` 엔진은 자기 LLM 루프가 없다 — 포크 경로를 쓰거나 도구를 직접 부른다.

---

## 2. 잘린 응답

### 2.1 정의 — 판정 하나, 정의 하나

응답이 잘렸는지는 `StopReason.isTruncated()` 하나로 판정한다. provider 가 자기 잘림 코드를 `MAX_TOKENS` 로
매핑하면 그 응답은 잘린 응답이다. Anthropic 은 `max_tokens` 를, OpenAI 는 `length` 와
`incomplete: max_output_tokens` 를 매핑한다. `UNKNOWN` 이나 stop reason 이 없는 응답은 감지하지 않는다 —
`UNKNOWN` 은 동작을 바꾸지 않는다는 것이 `StopReason` 의 계약이다.

판정과 그에 따르는 문구는 `at.aimon.core.agent.budget.TruncatedResponses` 한 곳에 있다. 네 루프가 모두 이것만 읽는다.

| 공개 멤버 | 역할 |
|---|---|
| `isTruncated(LlmResponse)` | 응답이 stop reason 을 갖고 그것이 `isTruncated()` 일 때만 참 |
| `TRUNCATION_MARKER` | 잘린 최종 답 끝에 붙는 `"\n\n[System: response truncated at max_tokens]"`. 이름과 값은 고정이다 |
| `REFUSED_TOOL_CALL_MESSAGE` · `refusal(ToolUse)` | 거절된 호출 하나에 답하는 오류 `ToolUseResult` (§2.3) |
| `reasoningClause(TokenUsage)` | 잘림 WARN 의 꼬리 절 (§3.1) |

`OrcaAgentExecutor.TRUNCATION_MARKER` 는 이름과 값을 유지한 채 공유 상수로 초기화되어 컴파일 타임 상수로 남는다.
두 실행기의 테스트가 같은 상수를 참조하는 것이 둘의 일치를 고정한다.

판정은 `StopReason` 만 읽으므로 어느 `LlmClient`, 어느 경로(블로킹이든 스트리밍이든)에서도 답이 같다.

### 2.2 잘린 최종 답 — 마커와 `TRUNCATED`

도구 호출이 없는 잘린 응답은 부분 텍스트에 `TRUNCATION_MARKER` 를 붙여 내보낸다. 트랜스크립트와 결과 양쪽에 붙고,
WARN 하나를 남긴다.

- **턴과 포크**는 `CompletionReason.TRUNCATED` 로 끝난다. 결과는 success 모양이다 — `isSuccess() == true`,
  `isSuccessful() == false`, 부분 텍스트가 답(포크는 `getSummary()`)에 남는다. OnStop 훅은 `success=true` 와 마커가
  붙은 답으로 돈다. 포크의 진행 스트림은 `[completed: TRUNCATED at max_tokens after N iterations]` 로 끝난다.
- `CompletionReason.TRUNCATED` 는 턴 또는 포크의 잘린 최종 답을 뜻한다. 도구 호출을 담은 채 잘린 응답은
  `TRUNCATED` 로 끝나지 않는다 — 거절하고 계속한다(§2.3).
- **포크의 grace round** 답이 잘리면 마커만 붙고 사유는 `MAX_ITERATIONS_GRACE` 그대로다 —
  [execution-policy §8.1](../subagent/execution-policy.md#81-iteration-한도의-grace-round). grace round 의 tool_use 는
  원래 실행되지 않으므로 거절 규칙과 부딪히지 않는다.
- 마커는 답의 **꼬리**에 붙으므로, 꼬리를 남기는 `Task` 도구의 생략(`SubagentResultFormatter.truncateTailKeep`)을
  견딘다 — [subagent/execution.md](../subagent/execution.md).
- 잘린 포크가 부모 모델에게는 `Task` 도구 결과 뒤의 `Completion reason: TRUNCATED` 줄로도 보인다. 그 줄의 위치·조건·
  CLI 표시는 [execution-policy §8](../subagent/execution-policy.md#8-결과-계약) 이 정본이다.

스킬 루프와 deriver 의 최종 답은 §2.5 에 있다.

### 2.3 잘린 도구 응답 — 전부 거절하고 계속

도구 호출을 담은 잘린 응답은 **어느 호출도 실행하지 않는다.** 각 `tool_use` 에 응답 순서대로 `refusal(toolUse)`
오류 결과 하나씩으로 답한다. 결과는 실행한 결과와 똑같이 assistant 메시지 뒤에 커밋하고(모든 `tool_use` 에는 답이
있어야 한다) 루프를 잇는다. 실행하지 않을 호출에 답하는 이 모양은 인터럽트로 건너뛴 호출에 답하는 모양과 같다 —
[interrupt.md](interrupt.md).

**거절된 호출에는 도구 경로가 돌지 않는다.** 권한(allow-list) 검사, PermissionRequest · PreTool · PostTool 훅,
도구 실행, TOOL span 이 모두 없다. 잘린 응답의 호출은 어떤 `ToolCallDispatcher` 에도 넘기지 않는다 — 검사는 도구
경로 안에 있고 거절은 그 앞에서 답하기 때문이다. 포크는 정의의 `tools:` 를, 스킬 루프는 스킬의 허용 목록을
검사하지만 잘린 응답의 호출은 거기에 닿지 않는다. 스킬 루프의 도구 경로와 권한 위반의 처리는
[`tool-call-dispatcher.md`](../tool/tool-call-dispatcher.md) 가 정한다.

**예외 하나 — 스트리밍 도구 중첩.** 턴에서 스트리밍 중첩이 켜져 있으면, stop reason 이 도착하기 전에 이미 닫힌
블록의 `CONCURRENT_SAFE` 호출이 미리 시작되었을 수 있다. 그 호출은 훅까지 포함해 도구 경로 전체를 돌았다. 턴은
거절하기 전에 미리 시작한 작업을 버리고(`discardEagerToolUses`), 그 결과 대신 거절을 트랜스크립트에 남긴다.
잘린 블록 자체는 미리 시작될 수 없다 — 잘린 블록은 `TOOL_USE_READY` 에 이르지 않는다(§2.4).

**잘린 `Skill` 호출은 승인을 묻지 않는다.** 턴은 잘린 응답이면 스킬 사전 스캔을 건너뛰어, 인자가 없을 수 있는
호출을 사람에게 승인해 달라고 하지 않는다.

**거절 문구.** 정확한 문장은 코드(`REFUSED_TOOL_CALL_MESSAGE`)에 맡기고, 문구가 지켜야 할 세 요소만 계약으로 둔다.

1. `max_tokens` 에서 잘렸다고 말한다.
2. 상태를 바꾸는 호출은 하나도 실행되지 않았고 어느 호출에도 결과가 없다고 말한다. "아무 호출도 실행되지
   않았다" 고 말하지 않는다 — 스트리밍 중첩이 `CONCURRENT_SAFE` 호출을 이미 시작했을 수 있고, 그 선언이 상태를
   바꾸지 않음을 보장하기 때문이다.
3. 같은 호출을 다시 보내지 말고 한 응답의 출력을 줄이라고 말한다 — 한 번에 도구 호출 수를 줄이거나, 큰 인자를
   여러 호출로 나누라고.

거절된 iteration 은 결과가 전부 오류이므로 정체 가드가 센다(§4).

### 2.4 잘린 호출의 인자 모양 — 스트리밍과 블로킹

잘린 호출도 도구 호출로 도착한다. 그 인자는 경로마다 모양이 다르다.

- **스트리밍.** `tool_use` 블록이 **시작될 때** 슬롯이 생기고, 슬롯마다 `ToolUse` 가 된다. `TOOL_USE_READY` 는
  블록이 끝날 때만 나온다. 끝나지 않은 인자는 닫히지 않은 JSON 이라 `ChunkAggregator` 가 빈 map 으로 만든다.
  Anthropic 스트리밍에서는 잘린 `tool_use` 블록에 `content_block_stop` 이 오지 않는 것이 관찰되었다. 그래서 잘린
  블록은 `TOOL_USE_READY` 에 이르지 않고, 스트리밍 중첩이 미리 시작할 수 있는 것은 잘리기 전에 닫힌 블록뿐이다.
  OpenAI 의 Chat · Responses 스트림도 인자가 끝나기 전에 슬롯을 만든다.
- **블로킹.** Anthropic 블로킹 경로에서는 잘린 `tool_use` 가 끝난 인자만 담은 well-formed 객체로 오는 것이
  관찰되었다(필수 인자가 빠진 채). 같은 접두를 스트리밍 경로는 닫히지 않은 JSON 으로 받아 빈 map 이 된다.

어느 모양이든 그 호출을 실행하면 모델이 쓰던 인자 없이 도구를 돌리게 되므로 거절한다. 두 관찰은 각각 한 번이며,
어떤 결정도 그것에 기대지 않는다.

### 2.5 루프별 결과 — 스킬의 `isTruncated()` 플래그 · 커맨드 턴 · deriver

**스킬 루프.** `LlmSkillExecutor` 는 응답마다 `isTruncated` 를 한 번 읽는다. 잘린 도구 응답은 §2.3 대로 거절하고
계속한다. 잘린 최종 답은 마커를 붙여 `SkillExecutionResult.truncated(flagged, metadata)` 로 돌려준다 — 성공이고
`isTruncated() == true` 다.

**스킬 결과는 잘림을 사유(`CompletionReason`)가 아니라 `isTruncated()` 플래그로 말한다.** 스킬 결과 · 포크 모드
스킬 결과 · 커맨드 결과 세 타입은 모든 경로에서 참인 사유를 알 수 없고(인터럽트 · 훅 거부 · iteration 소진도
`ERROR` 라고 해야 한다), 생산자가 아는 것은 잘렸다는 한 가지뿐이기 때문이다.

플래그는 이렇게 흐른다.

| 타입 | 세우는 곳 |
|---|---|
| `SkillExecutionResult.truncated(…)` | 인라인 스킬 — `LlmSkillExecutor` 의 잘린 최종 답 |
| `SkillForkOutcome.truncated(…)` | 포크 모드 스킬 — `SubagentBackedSkillForkExecutor` 가 포크의 `TRUNCATED` 를 옮기고, `LlmSkillExecutor.executeFork` 가 이어 받는다 |
| `CommandExecutionResult.truncated(…)` | `SkillBackedCommandExecutor.toCommandResult` 가 스킬의 플래그를 옮긴다. `DirectCommandExecutor` 는 메타데이터를 덧씌울 때 플래그를 유지한다 |

**슬래시 커맨드 턴.** `OrcaAgentExecutor.executeCommandFlow` 는 커맨드 결과가 `isTruncated()` 면 턴을
`CompletionReason.TRUNCATED` 의 success 로 끝낸다 — 에이전트 자신의 잘린 답과 같은 모양이다. 인라인 스킬과 포크 모드
스킬 모두 그렇다. 마커는 텍스트에도 그대로 있다. 모델 경로(`SkillTool`)에서는 스킬의 답이 도구 결과일 뿐이고
턴은 계속된다.

**deriver.** `ReActLlmDeriver.derive` 는 잘린 도구 응답의 호출을 `invokeTool` 없이 거절만 하고 계속한다. 잘린
텍스트 응답은 잘리지 않은 텍스트 응답처럼 루프를 끝낸다 — deriver 는 텍스트가 아니라 관찰을 돌려주므로 마커를 읽을
쪽이 없다. 정체 가드는 없다. 여섯 iteration 과 토큰 예산이 반복을 끝내고, 잘린 응답에서는 아무것도 영속되지 않는다.

---

## 3. 운영자에게 보이는 것

### 3.1 잘림 WARN 과 reasoning 절

잘린 응답 하나에 WARN 하나가 남는다. 예외는 포크의 grace round([execution-policy §8.1](../subagent/execution-policy.md#81-iteration-한도의-grace-round))다 —
잘린 grace 답은 마커를 달고 `MAX_ITERATIONS_GRACE` 결과가 될 뿐 잘림 WARN 을 남기지 않는다(`DefaultSubagentExecutor` 의
grace round 처리. 그 답에 구조화 결과 보고 호출이 있었으면 "받지 않는다" 는 WARN 만 남고, 거기에는 reasoning 절이 없다).
WARN 은 루프 · iteration · 도구 이름을 적고, 인자는 적지 않는다. 턴의 도구
응답 WARN 은 스트리밍 중첩이 이미 시작한 호출이 있었는지도 말한다("…had already started have their results
discarded, and the others are not run" / "none of them is run").

**모든 잘림 WARN 은 reasoning 절로 끝난다.** 절은 잘린 응답 **자신의** usage(실행의 누적 usage 가 아니다)로
만든다. 잘린 응답의 출력 토큰 수와 reasoning 토큰 수만 적고 해석을 붙이지 않는다 — "reasoning 이 대부분을 썼다" 고
말할 비율 문턱을 정당화할 수 없고, `TokenUsage` 는 reasoning 수가 출력 수보다 클 수도 있게 허용하기 때문이다.

reasoning 카운터(`TokenUsage.getReasoningTokens()`)를 채우는 경로는 Anthropic 의 두 경로(블로킹 · 스트리밍)와
OpenAI Responses 경로다. OpenAI Chat Completions 는 스트리밍 · 블로킹 모두 0 이다. 카운터가 0 이거나 보고되지
않으면 절은 빈 문자열이라, WARN 이 그 절이 없을 때와 똑같이 읽힌다 — 카운터를 채우지 않는 경로가 나빠지지 않는다.
thinking 예산이 출력 한도를 얼마나 쓰는지는 [anthropic-thinking.md](../llm/anthropic-thinking.md) 가 다룬다.

### 3.2 블로킹 경로의 클라이언트 WARN

`AnthropicLlmClient` 와 OpenAI 클라이언트의 블로킹 경로는 잘린 응답에 각자 WARN 을 남긴다. 이 WARN 은 그대로 둔다.

- **두 실행기는 여기에 닿지 않는다.** 턴과 포크는 언제나 live 취소 토큰(`SignalBackedLlmCancellation`)을 넘기므로
  스트리밍 경로를 탄다. 그들의 잘림은 §3.1 의 WARN 이 말한다.
- **블로킹 호출자는 닿는다** — 압축 요약기, 스킬 루프, 메모리, wiki, deriver. 이들에게 클라이언트 WARN 은 잘림을
  알리는 유일한 신호이거나(압축 · 메모리 · wiki), 루프 WARN 과 함께 나오는 신호다(스킬 루프 · deriver).

### 3.3 이벤트와 터미널

- **턴**은 거절된 호출마다 `ToolUseStarted` 와 `ToolResultReady` 를 오류 결과와 함께 순서대로 낸다. REPL 은
  `ToolResultReady` 로 `Tool '<name>' failed: …` 한 줄만 찍는다. REPL 의 평소 도구 호출 줄은 PreTool 훅
  (`ToolCallDisplayHook`)이 찍으므로 거절된 호출에는 나오지 않는다.
- **포크**는 거절된 호출마다 진행 스트림에 `→ name` 과 오류 결과를 쓴다.
- **스킬 루프**의 거절 한 번은 WARN 과 모델이 다음에 읽는 결과에만 남는다. 커맨드 경로는 스킬의 어느 도구 호출에도
  lifecycle 이벤트를 내지 않으므로, 이것은 잘림 고유의 틈이 아니다. 거절이 이어지면 정체 가드가 스킬을 실패로
  끝내고, `max_tokens` 를 말하는 그 실패 메시지(§4.4)가 `/skill` 을 실행한 사람에게 처음 보이는 것이다.

### 3.4 `ChunkAggregator` 의 parse WARN

스트리밍 인자 JSON 을 파싱하지 못하면 `ChunkAggregator` 는 빈 map 을 돌려주고 WARN 하나를 남긴다. WARN 은 예외
이름, 알 수 있을 때의 오프셋, 길이만 적고 인자 텍스트는 적지 않는다(`…: <예외 이름>[ at offset N] (<길이> chars);
the arguments are not logged`).

- Jackson 의 예외 메시지가 문제 토큰을 그대로 옮겨 적으므로, 원문 JSON 만 빼서는 인자가 샌다.
- 오프셋은 `JsonProcessingException` 이 위치를 갖고 `getCharOffset()` 가 음수가 아닐 때만 적는다. 알 수 없는
  오프셋은 `-1` 로 보고되는데, `at offset -1` 은 사실처럼 읽힌다.
- 흔한 잘림에서는 예외 종류와 길이와 같은 오프셋만으로 "일찍 끝났다" 가 읽힌다.

---

## 4. 정체 가드

### 4.1 정의와 공유

정체 가드(`at.aimon.core.agent.budget.StalledIterationGuard`)는 진전 없이 도구만 부르는 반복을 끝낸다. 실패 응답에
continuation 을 계속 돌리지 않게 하는 참조 구현의 death-spiral 가드와 같은 목표다 — 그 유래와, 트리거가 참조 구현의
"마지막 메시지가 API 에러" 가 아니라 "결과가 전부 오류" 인 이유는 [`orca-executor.md` §1](orca-executor.md#1-루프-뼈대) 에 있다.

- **정체 iteration** — 도구 호출을 하나 이상 냈고 그 결과가 **전부** 오류인 iteration (`isStalled(List<ToolUseResult>)`).
- **임계** — `MAX_CONSECUTIVE_STALLED_ITERATIONS = 3`. 정체 iteration 이 세 번 연속이면 그 실행을 끝낸다.
- **리셋** — 성공한 결과가 하나라도 있는 iteration, 또는 `reset()`.
- **상태** — `int` streak 와 "streak 의 iteration 이 전부 잘린 응답의 거절이었나" 를 뜻하는 `boolean` 하나. 둘 다
  리셋 때 지워진다.
- **수명** — thread-safe 하지 않다. 실행마다 하나, 그 루프에 갇혀 산다(`BudgetTracker` 와 같다).

턴 · 포크 · 스킬 루프가 이 한 정의를 각자 실행별 인스턴스로 쓴다. deriver 에는 없다(§1).
`OrcaAgentExecutor.MAX_CONSECUTIVE_STALLED_ITERATIONS` 는 `StalledIterationGuard.MAX_CONSECUTIVE_STALLED_ITERATIONS`
로 정의하고 공개 이름 · 값(3)을 유지한다. 같은 수를 리터럴로 한 번 더 두면 정의가 둘이 되기 때문이다.

가드는 **원인과 무관하게** 센다. 잘린 응답의 거절, 도구 오류, 모르는 도구 이름, schema `ENFORCE` 거부,
PermissionRequest · PreTool 차단, allow-list 거부, 부작용 승인 게이트의 거부가 모두 오류 결과다. 그래서:

- **포크**에서는 물어볼 채널이 없어 승인 게이트가 거부한 호출도 센다. 정책이 실행하지 않은 호출을 가드가 세는
  규칙은 [execution-policy §4.1](../subagent/execution-policy.md#41-연속-실패한-도구) 에 있다.
- **스킬 루프**에서는 사용자가 상태를 바꾸는 호출을 세 iteration 연속 거부해도 스킬이 끝난다. 스킬 루프는 결과를
  커밋한 뒤 다음 LLM 호출 전에 기록한다. 이 루프에 가드를 두는 이유는 사람이 앞에 앉아 있는데도 슬래시 커맨드를
  인터럽트할 수 없기 때문이다 — 가드가 없으면 거절이 `max-iterations`(기본 100)까지, 예산 없이 이어진다.

### 4.2 취소된 iteration 은 세지 않는다

취소로 끊긴 batch 에서는 아직 시작하지 않은 호출이 모두 오류로 답해지므로, 그 iteration 은 가드에게 정체처럼
보인다. 세면 세 번째 정체 iteration 에 떨어진 취소가 `INTERRUPTED` 대신 `ERROR` 로 끝난다. 그래서 두 실행기 모두
취소된 iteration 을 세지 않는다.

- **턴**은 취소 신호를 먼저 읽고(소비하지 않는 읽기), 취소면 가드를 리셋한다. 이 읽기의 규칙은
  [interrupt.md](interrupt.md) 가 정본이다.
- **포크**는 iteration 끝의 취소 검사를 먼저 하고 그다음에 가드를 기록한다 — 취소면 `INTERRUPTED`, 아니고 가드가
  걸리면 `ERROR`. 포크에는 큐 드레인이 없지만 둘 사이에 도구 호출 예산의 정지가 하나 있다 — 호출 수 상한을 넘었거나
  토큰 때문에 호출을 멈췄으면 가드를 기록하지 않고 도구 호출 예산 결과로 끝난다
  ([execution-policy §4](../subagent/execution-policy.md#4-도구-호출-수)). 그 iteration 의 거절된 호출은 정체로 세지 않는다.
- **스킬 루프**는 취소가 없다(§1).

### 4.3 가드가 멈춘 실행의 결과 — `ERROR`

- **턴과 포크**는 `CompletionReason.ERROR` 로 끝난다. 새 상수는 없다 — `CompletionReason.ERROR` 의 javadoc 이 가드가
  멈춘 실행을 포함한다. 포크는 OnStop 을 `success=false` 로 돌리고 진행 스트림에 `[ended: <중단 메시지>]` 를 쓴다.
- **스킬 루프**는 완료 사유가 없으므로 `SkillExecutionResult.failure(중단 메시지, …)` 로 끝난다.

**잘린 응답의 거절만으로 이루어진 정체도 `TRUNCATED` 가 아니라 `ERROR` 로 끝난다.** `TRUNCATED` 는 마커를 단 부분
답을 약속하는데 정체에는 내보일 답이 없고, 잘림 WARN 과 중단 메시지가 이미 `max_tokens` 를 말하기 때문이다.

`ERROR` 를 재사용한 이유와 기각한 사유 상수는 §5 의 결정 표에 있다.

### 4.4 중단 메시지의 `max_tokens` 절

`stopMessage()` 는 기본 문구 `Execution aborted: N consecutive tool-only iterations made no progress (all tool calls
failed)` 를 돌려준다. streak 의 iteration 이 **전부** 잘린 응답의 거절이었으면 ` — each of those responses was cut off
at max_tokens, and its tool calls were refused` 를 덧붙인다. 섞인 streak 은 기본 문구다. 턴 · 포크 · 스킬 루프가 같은
메시지를 쓴다.

절을 두는 이유: 구분이 필요한 독자는 프로그램이 아니라 부모 모델이다. 실패하는 도구에는 다른 도구로, 잘린 응답에는
한 응답의 출력을 줄이는 것으로 대응해야 하는데, 부모 모델이 읽는 것은 실패 상태와 `ERROR` 와 이 메시지뿐이다.

가드 자신의 WARN 에는 `max_tokens` 를 쓰지 않는다. 그래야 "잘린 응답 하나에 `max_tokens` 를 말하는 WARN 이 정확히
하나" 라는 성질이 유지된다(§3.1).

---

## 5. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|---|---|---|
| 잘린 도구 응답의 호출 | **전부 거절하고 계속한다** (§2.3). 잘린 호출의 인자는 없거나 일부뿐이라 실행하면 모델이 쓰던 인자 없이 도구를 돌린다. 필수 인자가 있는 도구는 "missing parameter" 로 실패해 같은 호출을 부른다. 실행하지 않을 호출에 답하는 모양(인터럽트 건너뛰기)이 이미 있고, 반복은 정체 가드가 끝낸다 | **실행하고 WARN 만 남긴다** — 운영자는 알지만 모델은 모르고, 빈 인자로 도구가 돈다. **완결된 호출은 돌리고 잘린 것만 거절한다** — 중립 `LlmResponse` 는 어느 호출이 잘렸는지 말하지 않는다. "마지막 호출" 은 executor 가 볼 수 없는 provider 사실이고, 틀리면 빈 인자로 실행한다. 스트리밍 중첩과 섞여 부분 수확이 된다. 완결된 호출이 한 iteration 뒤에 다시 오는 비용이 더 작다. **첫 잘린 호출에서 `TRUNCATED` 로 끝낸다** — 큰 인자를 나누면 회복할 수 있는 경우를 버리고(그것은 모델만 할 수 있다), `TRUNCATED` 가 약속하는 부분 답이 없다 |
| 판정 위치 | **각 루프가 `StopReason.isTruncated()` 를 읽는다.** `StopReason` 은 core 가 provider 어휘 없이 이것을 판정하라고 있는 값이다 | **`ChunkAggregator` 에서 처리** — 블로킹 경로에 닿지 않는다. 집계기는 모든 스트리밍 호출자의 응답을 만들므로 정책을 정할 수 없고, 거기서 도구 호출을 버리면 잘린 도구 응답이 조용히 텍스트 답이 된다. **`ToolUse` 별 complete 플래그** — 공개 값 타입과 매퍼 셋을 바꾸며 스트리밍 경로에만 존재한다. **Anthropic wire 필드로 원인 귀속** — provider 전용이고 executor 는 그 필드를 보지 못한다 |
| 공유 정의의 위치 | **`at.aimon.core.agent.budget.TruncatedResponses`.** ArchUnit 이 포크에서 `at.aimon.core.agent.impl..` 참조를 막으므로 중립 패키지가 필요하다. `agent.budget` 은 이미 `CompletionReason`(실행이 왜 끝났는지)을 갖고 `at.aimon.core.llm` 에 의존하므로 새 의존 쌍이 생기지 않는다 | **`agent.loop`** — 그 패키지의 타입은 제어 흐름에 끼지 않는다고 패키지 문서가 정한다. **실행기마다 private 리터럴 + 동등 테스트** — 모델이 읽는 문구의 정의가 둘이 된다. **`CompletionReason` 상수** — 공개 enum 에 무관한 멤버. **`at.aimon.core.llm`** — 문구의 독자는 LLM 클라이언트가 아니라 에이전트 루프의 모델이다 |
| reasoning 절의 모양 | **개수만 적는다** (§3.1) | **비율과 해석 문장** — 정당화할 문턱이 없다. **"N 중 R"** — reasoning 수가 출력 수보다 클 수 있다 |
| 블로킹 경로의 클라이언트 WARN | **그대로 둔다** (§3.2). 블로킹 호출자에게는 유일한 신호다 | **제거** — 압축·메모리·wiki 에서 신호가 사라진다. **스트림 매퍼로 옮기기 / 양쪽에 두기** — 스트림을 받는 main 호출자는 두 실행기뿐이고 이미 더 자세한 WARN 을 남기므로 독자가 늘지 않고 WARN 만 두 배가 된다 |
| 포크의 잘린 최종 답 | **턴과 같은 모양 — `success(…, TRUNCATED)` + 마커** (§2.2). 포크 답의 모든 독자(부모 모델, 워크플로, 백그라운드 task 기록)가 부분 답임을 알아야 하고, `success` 가 부분 텍스트를 `getSummary()` 에 남긴다 | **`failure(…, TRUNCATED)`** — 부모가 부분 텍스트를 잃고 턴과 갈라진다. **차이를 의도된 것으로 기록** — 기록할 이유가 없다. **포크는 잘린 호출을 실행** — 첫 행의 이유가 그대로 적용된다 |
| 스킬 결과의 잘림 신호 | **사유가 아니라 `isTruncated()` 플래그.** 커맨드 턴은 그 플래그로 `TRUNCATED` 가 된다 (§2.5) | **세 결과 타입에 `CompletionReason`** — 모든 경로에서 참인 값을 알 수 없다. **잘린 최종 답을 실패로 돌려주기** — 이 경로에서만 부분 텍스트를 잃는다 |
| deriver | **거절만 한다** (§2.5). 생성 지점은 없지만 몇 줄로 고정할 수 있다 | **생성 지점이 생기면 고친다** — 그날의 변경은 다른 파일(메모리 배선, starter)이라 이 클래스에도 등록 항목에도 닿지 않는다. 항목은 깨어나지 않고 결함이 생성과 함께 조용히 배포된다 |
| "진전 없음" 의 정의 | **공유 정체 가드 하나** (§4.1). 턴 · 포크 · 스킬 루프가 같은 정의를 쓴다 | **잘림 전용 카운터** — ① 턴의 정의 옆에 두 번째 "진전 없음" 정의가 생긴다 ② 잘린 응답과 실패한 재시도가 번갈아 오면 매번 리셋되어 걸리지 않는다 ③ 포크의 잘림 아닌 반복(기본 1000 iteration, 지켜보는 이 없는 백그라운드)이 남는다 |
| 포크에서 가드를 기록하는 자리 | **iteration 끝 취소 검사 뒤** (§4.2). 취소된 iteration 이 정체로 세지지 않는다 | — |
| 정체로 멈춘 실행의 사유 | **`ERROR` 재사용** (§4.3). 포크 사유의 어느 독자도 정체와 다른 오류를 구분해 분기하지 않는다. 구분이 필요한 부모 모델은 중단 메시지의 텍스트로 받는다(§4.4) | **`STALLED`** — 아무도 분기하지 않는 공개 · 영속 enum 이름이다. `TaskResult` / `JsonTaskResultCodec` 은 모르는 이름을 success 플래그로 디코드하므로 롤링 업그레이드 중 한 기록이 두 뜻이 된다. `StepOutcomeCodec` 의 strict decode 는 모르는 이름에 예외를 던진다. GraalJS `AgentResultView` 는 이름을 스크립트에 그대로 노출하므로 스크립트 작성자가 새 문자열을 만난다. **`TRUNCATED`** — 성공 사유 아래의 실패가 된다(아래 행). **`MAX_ITERATIONS`** — "한 번 더 돌면 된다" 는 신호인데, 정체한 포크는 같은 목표로 다시 돌려도 또 정체한다. **`ABORTED`** — 프레임워크가 시작한 취소다 |
| 거절만으로 이루어진 정체 | **`ERROR`** (§4.3) | **`TRUNCATED`** — 내보일 답이 없다 |
| 중단 메시지의 `max_tokens` 절 | **streak 이 전부 거절일 때만 붙인다** (§4.4) | **포크에만 붙이기** — 한 조건에 문구가 둘이 된다. **절 없음** — 차이에 따라 행동하는 독자(부모 모델)가 그것을 보지 못한다 |
| 스킬 루프의 가드 | **턴 · 포크와 같은 가드** (§4.1). 거절이 스킬 루프에 반복을 만들고, 그 루프는 인터럽트할 수 없다 | **잘림 전용 카운터** — "진전 없음" 행의 이유 |
| parse WARN 의 내용 | **예외 이름 · 오프셋 · 길이만** (§3.4) | **`getMessage()` 유지** — 토큰이 샌다. **길이를 제한한 발췌** — 여전히 내용(명령줄, 파일 본문)이다 |
| 이어쓰기(continuation) | 만들지 않았고 seam 도 없다 — 이유는 [`orca-executor.md`](orca-executor.md) 의 결정 표 | — |

---

## 6. 실패 모드

| 상황 | 일어나는 일 | 받아들이는 이유 |
|---|---|---|
| 모든 호출이 완결되었는데 provider 가 `MAX_TOKENS` 를 보고한다(마지막 블록이 닫힌 뒤 잘림) | 완결된 호출도 거절되고, 모델이 다음 iteration 에 다시 낸다 | 한 iteration 비용이다. 대안은 어느 호출이 잘렸는지 추측하는 것이다(§5 첫 행) |
| 잘림이 `UNKNOWN` 으로 보고되거나 보고되지 않는다(stop reason 을 버리는 게이트웨이, `max_output_tokens` 가 아닌 `incomplete` 사유) | 감지하지 않고 호출이 실행된다 | `UNKNOWN` 은 동작을 바꾸지 않는다는 `StopReason` 의 계약. 이 문서는 "provider 가 `MAX_TOKENS` 로 매핑한 stop reason" 이상을 주장하지 않는다 |
| 응답이 계속 잘린다(thinking 이 한도를 먹는다 등) | 턴 · 포크: 잘림 WARN 셋 뒤 `ERROR`, 중단 메시지가 `max_tokens` 를 말한다. 스킬: 같은 메시지로 실패. LLM 호출은 정확히 세 번 | 유계이고 WARN 과 메시지가 원인을 말한다 |
| 거절과 진짜 실패가 섞인다 | 같은 streak 으로 세고, 셋에서 기본 문구로 끝난다 | 가드의 뜻은 "진전 없음" 이고 둘 다 진전이 없다 |
| 임계 전에 성공한 호출이 하나 있다 | streak 이 리셋되고 아무것도 끝나지 않는다 | 진전이 있었다 |
| 스트리밍 중첩이 잘린 응답의 `CONCURRENT_SAFE` 호출을 이미 시작했다 | 그 호출은 훅까지 포함해 돌았을 수 있다. 결과는 버리고 트랜스크립트에는 거절이 남는다. 거절된 iteration 은 그대로 센다 | 미리 시작할 수 있는 것은 동시에 돌리고 버려도 안전하다고 선언한 도구뿐이고, 잘리기 전에 닫힌 블록뿐이다(§2.4) |
| 잘린 응답에 승인이 필요한 `Skill` 호출이 있다 | 멈추지 않고 거절한다 | 인자가 없을 수 있는 호출을 승인해 달라고 묻는 편이 더 나쁘다. 다음 완결된 호출은 평소대로 스캔을 거친다 |
| 훅이나 감사가 PreTool / PostTool 로 도구 호출을 센다 | 거절된 호출은 보이지 않는다 | 실행되지 않았다 — 인터럽트 건너뛰기와 같다 |
| 포크의 사유를 `== COMPLETED` 로 비교하는 소비자 | 잘린 포크 답이 "완료 아님" 으로 읽힌다(워크플로 step 은 `isComplete()` 거짓, 캐시 안 됨) | 부분 답임을 알리는 것이 목적이다 |
| 거절된 `tool_use` 와 그 오류 결과가 다음 요청에 재전송된다 | 두 provider 모두 받는다 | assistant 모양은 실행한 경우와 같고 결과 내용만 다르다 |
| 블로킹 경로에서 SDK 가 망가진 블록을 변환하다 예외를 던진다 | `LlmClientException` — 스킬은 기존 메시지로 실패하고 deriver 는 멈춘다 | 잘림 처리 이전과 같다 |
| 세 번째 정체 iteration 에 취소가 떨어진다 | 턴 · 포크 `INTERRUPTED`. 스킬 루프는 취소가 없다 | §4.2 |
| `maxIterations` 가 3 보다 작다 | 포크는 `MAX_ITERATIONS`, 스킬은 max-iterations 실패가 먼저 온다 | 상한이 먼저 닿는다 |
| streak 중간에 예산이 소진된다 | 포크는 다음 iteration 머리의 예산 검사가 그 예산의 사유로 끝낸다 | 예산이 가드보다 먼저다 |
| 정체한 step 을 가진 워크플로를 재개한다 | 캐시되지 않았으므로 다시 돌고, 또 정체할 수 있다 | 다른 `ERROR` step 과 같다 |
| 백그라운드 포크가 정체한다 | task 는 `FAILED`, 알림에는 중단 메시지가 실린다 | 메시지는 알림 길이 상한 안에 든다 |
| 슬래시 스킬에서 거절이 한 번 일어난다 | 터미널에는 아무것도 없고 WARN 만 남는다 | 커맨드 경로는 원래 도구 이벤트를 내지 않는다(§3.3) |
| deriver 가 계속 잘린다 | 거절마다 iteration 과 토큰을 쓰고, 여섯 iteration 이나 예산에서 끝난다. 잘린 응답에서는 아무것도 영속되지 않는다 | 생성 지점이 없고, 생기더라도 유계다 |
| 잘린 응답마다 WARN 이 쌓인다 | 잘린 응답당 하나, 가드나 상한이 수를 묶는다 | 트래픽 조건이다 |

모델이 거절을 읽은 뒤에도 잘린 호출을 계속 내는지는 재지 않았다. 어떤 결정도 그것에 기대지 않는다 — 계속 낸다면
정체 가드가 세 번째 iteration 에서 끝낸다.

---

## 7. 하지 말 것

- **루프 안에서 stop reason 을 따로 해석하지 않는다.** 판정은 `TruncatedResponses.isTruncated` 하나다. 새 도구
  루프를 만들면 §1 의 방법으로 다시 세고, 그 루프도 이 판정과 정체 가드를 쓴다.
- **잘린 응답의 호출 중 "완결돼 보이는" 것을 골라 실행하지 않는다.** 응답은 어느 호출이 잘렸는지 말하지 않는다.
  블로킹 경로의 well-formed 인자도 일부일 수 있다.
- **거절된 호출을 도구 경로(`ToolCallDispatcher`, 훅, 권한 검사)에 넘기지 않는다.** 거절은 그 앞에서 답한다.
- **거절 문구를 "아무 호출도 실행되지 않았다" 로 바꾸지 않는다.** 스트리밍 중첩에서 거짓이 된다. 세 요소(§2.3)를
  지킨다.
- **`TRUNCATION_MARKER` 와 `MAX_CONSECUTIVE_STALLED_ITERATIONS` 의 이름 · 값을 바꾸지 않는다.** 둘 다 공개 상수이고
  여러 루프와 테스트가 같은 값을 읽는다. 임계를 리터럴로 한 번 더 쓰지 않는다.
- **잘림 전용 정체 카운터를 따로 두지 않는다.** "진전 없음" 의 정의가 둘이 된다.
- **정체를 위한 `CompletionReason` 을 새로 만들지 않는다.** 분기하는 독자가 없고, 영속 이름이 롤링 업그레이드와
  strict decoder 를 위험하게 한다.
- **가드를 취소 검사보다 앞에 두지 않는다.** 취소된 iteration 이 `ERROR` 로 끝난다.
- **가드의 WARN 에 `max_tokens` 를 쓰지 않는다.** 잘린 응답당 WARN 하나라는 성질이 깨진다.
- **스킬 결과 · 커맨드 결과에 `CompletionReason` 을 싣지 않는다.** 잘림은 `isTruncated()` 플래그로만 말한다.
- **reasoning 절에 해석이나 비율을 넣지 않고, 0 일 때 "0 reasoning tokens" 를 적지 않는다.** 카운터를 채우지 않는
  경로의 WARN 이 바뀐다.
- **parse WARN 에 예외 메시지나 인자 발췌를 적지 않는다.**
- **블로킹 경로의 클라이언트 WARN 을 지우지 않는다.** 블로킹 호출자에게는 유일한 신호다.

---

## 8. 참조 파일 지도

| 절 | 파일 |
|---|---|
| §2.1 판정 · 마커 · 거절 · reasoning 절 | `modules/aimon-core/src/main/java/at/aimon/core/agent/budget/TruncatedResponses.java`, `modules/aimon-core/src/main/java/at/aimon/core/llm/StopReason.java` |
| §2.2 · §4.3 완료 사유 | `modules/aimon-core/src/main/java/at/aimon/core/agent/budget/CompletionReason.java` |
| §1 · §2 · §4 턴 | `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java` (`refuseTruncatedToolUses`, `createTruncatedResult`, `executeCommandFlow`, `handleStalledIteration`) |
| §1 · §2 · §4 포크 · grace round | `modules/aimon-core/src/main/java/at/aimon/core/subagent/execution/DefaultSubagentExecutor.java` |
| §1 · §2.5 · §4 스킬 루프 | `modules/aimon-core/src/main/java/at/aimon/core/skill/execution/llm/LlmSkillExecutor.java` |
| §2.5 스킬 플래그 | `modules/aimon-core/src/main/java/at/aimon/core/skill/execution/SkillExecutionResult.java`, `modules/aimon-core/src/main/java/at/aimon/core/skill/fork/SkillForkOutcome.java`, `modules/aimon-core/src/main/java/at/aimon/core/skill/fork/SubagentBackedSkillForkExecutor.java`, `modules/aimon-core/src/main/java/at/aimon/core/command/execution/CommandExecutionResult.java`, `modules/aimon-core/src/main/java/at/aimon/core/command/execution/skill/SkillBackedCommandExecutor.java`, `modules/aimon-core/src/main/java/at/aimon/core/command/execution/direct/DirectCommandExecutor.java` |
| §1 · §2.5 deriver | `modules/aimon-core/src/main/java/at/aimon/core/memory/deriver/ReActLlmDeriver.java` |
| §2.2 마커 꼬리 보존 | `modules/aimon-core/src/main/java/at/aimon/core/subagent/execution/SubagentResultFormatter.java`, `modules/aimon-core/src/main/java/at/aimon/core/tools/task/TaskTool.java` |
| §2.3 · §2.4 스트리밍 중첩 | `modules/aimon-core/src/main/java/at/aimon/core/agent/tool/StreamingToolScheduler.java` |
| §2.4 · §3.4 인자 집계 · parse WARN | `modules/aimon-core/src/main/java/at/aimon/core/llm/streaming/ChunkAggregator.java` |
| §3.1 reasoning 카운터 | `modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/AnthropicUsages.java`, `modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/OpenAiResponseUsages.java`, `modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/OpenAiChatUsages.java` |
| §3.2 블로킹 클라이언트 WARN | `modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/AnthropicLlmClient.java`, `modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/` 의 두 exchange |
| §4 정체 가드 | `modules/aimon-core/src/main/java/at/aimon/core/agent/budget/StalledIterationGuard.java` |
| §5 `STALLED` 기각의 세 독자 | `modules/aimon-core/src/main/java/at/aimon/core/subagent/task/codec/JsonTaskResultCodec.java`, `modules/aimon-core/src/main/java/at/aimon/core/workflow/impl/StepOutcomeCodec.java`, `modules/aimon-graaljs/src/main/java/at/aimon/graaljs/workflow/AgentResultView.java` |
| 테스트 — 잘림 | `OrcaAgentExecutorTruncationTest`, `DefaultSubagentExecutorTruncationTest`, `LlmSkillExecutorTruncationTest`, `TruncatedResponsesTest`, `ReActLlmDeriverTest`, `SlashSkillToolDispatchE2EIntegrationTest`, `ChunkAggregatorTest` (`modules/aimon-core/src/test/java/at/aimon/core/` 아래) |
| 테스트 — 정체 가드 | `StalledIterationGuardTest`, `OrcaAgentExecutorStalledIterationTest`, `DefaultSubagentExecutorStalledIterationTest` (같은 트리) |
