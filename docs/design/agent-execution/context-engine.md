# Context Engine — LLM 에 보낼 뷰를 정하는 자리

> Status: **IMPLEMENTED** — 적용 대상 `aimon-core`, `aimon-bootstrap`, `aimon-spring-boot-starter`. 남은 일은
> [`agent-execution-open-items.md`](../../backlog/agent-execution-open-items.md) 가 정본이다.
> 저장 모델: [`../session/session-log.md`](../session/session-log.md) (로그 · 뷰 상태 · 봉인의 저장 형태)
> 관련 문서: [`rolling-context-engine.md`](rolling-context-engine.md) (장기 대화 engine),
> [`compaction.md`](compaction.md) (임계값 · 판정 사다리 · breaker · 요약기 · 훅 · `/compact`),
> [`orca-executor.md`](orca-executor.md) (턴 루프), [`../memory/peer-memory.md`](../memory/peer-memory.md),
> [`../observability/tracing.md`](../observability/tracing.md) (COMPACTION span),
> [`../testing/context-engine-pressure-rig.md`](../testing/context-engine-pressure-rig.md) (두 engine 비교 리그)
> 사용 가이드: [`../../features/agent-execution/context-engine-guide.md`](../../features/agent-execution/context-engine-guide.md)

이 문서에서 맨 "engine" 은 `ContextEngine` 이다. `CompactionEngine` 은 **요약기**라 부른다.

---

## 1. 기록과 뷰 — 무엇을 푸는가

기록과 뷰가 한 타입에 겹치면 — 압축과 prompt-too-long 복구가 `replaceWith` 로 기록 자체를 고쳐 쓰면 — 장기 대화에서
다섯 가지 누수가 생긴다. 그래서 기록과 뷰를 나눈다. v1 쓰기 모드의 in-place 경로는 지금도 이 모양이다.

| # | 누수 | 원인 |
|---|------|------|
| ① | **최근 맥락이 매번 뭉개진다** | 전체 compaction 은 방금 본 도구 결과까지 요약 안으로 밀어 넣는다. 뷰를 부분적으로 줄이는 정책이 없다 |
| ② | **요약이 요약을 요약한다** | 재압축 때 이전 `[[COMPACT_SUMMARY]]` 가 기록 속의 일반 메시지로 다시 요약된다 |
| ③ | **원문이 영구히 사라진다** | 뷰를 줄이는 쓰기가 곧 기록을 지우는 쓰기다 |
| ④ | **압축된 실행은 메모리에 남지 않는다** | 실행 끝 ingest 는 기록 위의 mark 로 델타를 구하는데, 기록이 다시 쓰이면 mark 가 무효가 되어 그 실행 전체를 건너뛴다 |
| ⑤ | **요약문이 대화로 ingest 된다** | `SESSION_END` 는 세션 종료 때 기록 전체를 넘긴다. 압축된 기록에는 원문 대신 요약문(paraphrase)이 들어 있다 |

①·② 는 정책의 문제이고 ③~⑤ 는 저장 모델의 문제다. 뷰를 줄이는 방법이 기록을 지우는 것뿐인 한 ③~⑤ 는 정책으로 막을 수
없다 — 기록을 읽는 쪽(메모리, 재조회, rewind)은 언제나 무언가를 잃는다.

답은 셋이다.

- **기록**은 append-only 로그다([`session-log.md`](../session/session-log.md)). 압축은 기록을 건드리지 않는다
- **뷰**는 기록과 **뷰 상태**(어느 구간을 어떤 요약으로 대체했는지)로부터 결정적으로 계산된다
- **`ContextEngine`** 이 뷰를 줄이는 유일한 주체다. 매 LLM 호출 직전에 뷰를 만들고, 압축 · 복구 · `/compact` 는 전부 뷰
  상태를 바꾸는 engine 의 일이다

그러면 ④·⑤ 는 해결할 필요 없이 사라지고 — 메모리는 기록을 읽는다 — ③ 은 "원문은 남는다" 까지 해소된다. 그 원문을
에이전트가 되찾는 길은 롤링 engine 이 연다([`rolling-context-engine.md`](rolling-context-engine.md)). 남는 것은 "뷰를 어떻게
줄이는가" 라는 정책 문제(①·②)이고, 그것은 교체 가능한 engine 구현이 된다.

---

## 2. 경계와 수명

```
                         ┌──────────── ContextEngine (agent-scoped) ────────────┐
TranscriptBuffer         │                                                      │
  로그 ──────────────────┼─▶ prepare(request) ─▶ ContextView ──▶ LLM 호출        │
  SessionViewState ◀─────┼── (필요하면 뷰 상태를 바꾼다 = 압축)                   │
                         │   recover(request, promptTooLong) ─▶ 더 줄인 뷰       │
                         │   compactNow(request, instructions)  ◀── /compact     │
                         │        │                                             │
                         │        └─ 요약기 CompactionEngine.summarize(...)      │
                         └──────────────────────────────────────────────────────┘
```

| 무엇 | 누가 | 수명 |
|------|------|------|
| 로그 | `TranscriptBuffer` 가 들고 레코드에 영속 | session |
| 뷰 상태 (`SessionViewState`) | `TranscriptBuffer` 가 들고 레코드에 영속. 뷰를 **줄이는** 것은 engine 뿐이다. 로그를 자르는 rewind · `/clear` 는 잘린 seq 를 가리키던 뷰 상태를 정리한다([`session-log.md` §8](../session/session-log.md#8-로그를-줄이는-두-경로)) | session |
| 뷰 (`ContextView`) | engine 이 매 호출 계산. 영속하지 않는다 | 한 LLM 호출 |
| 판단 규칙 (구간 선택, 요약 입력의 모양) | engine 구현 | agent |

뷰 상태가 레코드에 있는 것은 **재시작 · 노드 이동을 넘어 살아남아야 하기** 때문이다. 다른 노드가 세션을 이어받으면 같은
기록과 같은 뷰 상태에서 **같은 뷰**가 나와야 대화가 이어진다([§5](#5-뷰의-결정성)).

**engine 이 소유하지 않는 것.**

- **시스템 프롬프트 조립** — `SystemPromptRenderer`, `ContextAssembler`, `MemoryContextProvider`. 이미 교체 가능한 seam 이다.
  둘을 합칠 이유는 하나 — 프롬프트 캐시 경계(CTX-05)를 시스템 프롬프트와 메시지에 걸쳐 놓으려면 둘을 함께 봐야 한다 —
  이고, 그 결정은 CTX-05 의 몫이다([§12](#12-설계-결정)). engine 은 시스템 프롬프트의 **크기**만 입력으로 받는다 — 임계값
  비교가 그것을 포함하기 때문이다
- **언제 압축하는가와 요약을 어떻게 만드는가** — 임계값 · 판정 사다리 · circuit breaker · 요약기 · 요약 프롬프트의 인젝션
  방어 · Pre/PostCompact 훅 · `/compact` 명령 흐름은 [`compaction.md`](compaction.md) 가 정한다. 두 engine 은 그것을 함께
  쓴다. 이 문서는 그 판정과 요약이 **뷰 위에서** 어떻게 돌아가는지를 정한다
- **"원본 무손상"** — 압축은 요약이 실패할 때만이 아니라 성공할 때에도 원본을 손상하지 않는다. 기록을 고치지 않으므로
  성립한다

---

## 3. SPI

```java
public interface ContextEngine {

    /** iteration 경계, LLM 호출 직전. 필요하면 뷰 상태를 바꾸고(압축) 이번 호출에 보낼 뷰를 돌려준다. */
    ContextDecision prepare(ContextRequest request);

    /** 프로바이더가 prompt-too-long 을 돌려준 뒤. 더 줄인 뷰를 주거나, 줄일 수 없으면 empty. */
    Optional<ContextView> recover(ContextRequest request, LlmPromptTooLongException error);

    /** /compact. 임계값 판정 없이 지금 줄인다. */
    CompactionResult compactNow(ContextRequest request, String instructions);

    /** 압축하지 않는 engine. */
    static ContextEngine passthrough() { ... }
}
```

| 값 | 내용 |
|----|------|
| `ContextRequest` | 한 호출의 입력. 필드는 아래 표 |
| `ContextDecision` | `ContextView` + 액션(`NONE`/`WARN`/`COMPACT`/`BLOCK`) + `CompactionMetadata`(있으면) + `getViewSizeBefore()`(압축 전 뷰 크기, 보고하지 않는 engine 이면 empty) |
| `ContextView` | 보낼 `List<Message>` 와 추정 토큰(시스템 프롬프트 포함). 추정하지 않았으면 0 이다 — `passthrough()` 와 추정기 없는 파생 engine 이 그렇다 |

`ContextRequest` 의 필드:

| 필드 | 필수 | 뜻 |
|------|------|----|
| `transcriptBuffer` | 예 | 뷰를 계산할 기록 |
| `systemPrompt` | 아니오 (기본: 버퍼의 시스템 프롬프트) | 뷰와 함께 보낼 시스템 프롬프트. engine 은 모든 크기 비교에 이것을 센다 — 프로바이더가 세기 때문이다 |
| `model` | 예 | 다음 호출의 모델. 임계값 해석의 기준 |
| `hookRegistry` | 아니오 | 압축이 발화하는 PreCompact / PostCompact 훅. `DefaultContextEngine` 은 이것이 없으면 `IllegalArgumentException` 을 던진다 |
| `executionEnvironment` | 아니오 | 이 호출이 속한 실행의 실행 환경. 압축 훅에 넘긴다 |
| `executionCancellation` | 아니오 | 압축되는 실행의 취소 신호. 훅 컨텍스트에 실어 그 실행이 중단되면 훅의 명령도 멈추게 한다. 수동 `/compact` 나 rewake 리플레이처럼 끊길 신호가 없으면 비어 있다 |
| `caller` | 아니오 (기본 `ContextCaller.session()`) | 누구를 대신해 부르는가 — `ContextCaller(executionId, principal)`. 메인 루프는 세션 정체성으로 부르고 `ExecutionId` 를 싣지 않는다. 포크만 싣는다 |
| `budgetForced` | 아니오 | soft 예산 힌트가 선제 압축을 요청했는가. 존중하는 engine 은 이 호출의 유효 임계값을 warning 밴드로 내린다([`compaction.md`](compaction.md)) |
| `callMetadata` | 아니오 | engine 이 호출자를 대신해 하는 LLM 호출(요약)의 귀속 정보. 겹치는 필드는 호출자 것이 이긴다. 실행기는 자기 metadata 에서 호출 지점(`component` · `parentComponent` · `feature`)만 뺀 것(`LlmCallMetadata.withoutCallSite()`)을 싣는다 — principal · 그룹 키 · 태그가 요약 호출까지 가서, 애플리케이션이 실행에 붙인 라우팅 · 정책 태그를 요약 호출의 provider 도 본다. `/compact` 는 `component = compact-command` 를 붙인다 — `ContextCaller` 로는 component 이름을 나를 수 없다 |
| `compactionObserver` | 아니오 (기본 `CompactionObserver.none()`, 언제나 `failSafe`) | 압축 **시도**를 알리는 관찰자. engine 은 시도를 실제로 할 때만 `begin` 하고, 요약 호출의 metadata 를 `enrich` 로 넘기고, 설치까지 끝난 결과를 `record` 한다. `recover` 는 읽지 않는다. 관찰자를 요청에 싣는 결정과 어느 시도가 begin 하는지는 [`tracing.md`](../observability/tracing.md) D12 가 정본이다 |
| `compactionInstructions` | 아니오 | 이 호출이 일으키는 AUTO 압축의 요약 지침 — `/compact <지시>` 가 수동 압축에 주는 자리와 같다. 실행 정책 아래의 포크가 정책의 지침을 싣는다 |

- 액션은 `CompactionDecision.Action` 을 그대로 잇는다 — 이름도 의미도 같다. `BLOCK` 이면 실행기는
  `ContextWindowExceededException` 으로 실행을 끝낸다
- `compactNow` 는 `CompactionResult` 를 돌려준다. 새 결과 타입을 만들지 않는다. 롤링의 경합 실패도 이 결과의 실패
  사유(`CompactionContendedException`)로 표현한다
- 포크가 상속할 수 있는 engine 은 `compactionInstructions` 와 `budgetForced` 를 존중해야 한다. 포크 정책이 둘 중 하나를
  정하면 `ContextEngine.passthrough()` 가 아닌 어떤 engine 에서든 그 정책을 받아들이기 때문이다
- 전부 불변 클래스 + 빌더다. 패키지는 `at.aimon.core.agent.context`(`ContextAssembler` 옆)다

---

## 4. 호출 지점

전사 버퍼를 LLM 에 보내는 곳은 세 루프의 다섯 자리다. 다섯 자리 모두 `transcriptBuffer.getMessages()` 대신
`engine.prepare(...)` 의 뷰를 보낸다. 넷째 도구 루프인 메모리 deriver(`ReActLlmDeriver`)는 전사 버퍼가 아니라 자기 대화를 직접
보내고 engine 을 거치지 않는다([`truncation-and-stall.md`](truncation-and-stall.md) §1).

| 루프 | engine | 이유 |
|------|--------|------|
| 메인 ReAct (`OrcaAgentExecutor`) | runtime 에 배선된 engine | |
| 서브에이전트 포크 (`DefaultSubagentExecutor`) | **주입 가능**, 기본 `passthrough()`. 실행 정책이 상속을 요구하면 부모 engine | 포크는 세션 없는 압축(`ExecutionId` 식별)을 할 수 있다. 상속 경로와 정책 규칙은 [`execution-policy.md` §6.1](../subagent/execution-policy.md#61-부모-engine-상속) 이 정본이다 |
| 스킬 루프 (`LlmSkillExecutor`) | `passthrough()` | 매 실행 새 스크래치 버퍼를 만든다. 압축할 만큼 길어지지 않는다. 훅 레지스트리도 실행 환경도 없다 |

포크의 뷰 상태는 그 포크의 버퍼에만 있고 영속하지 않는다. 세션이 없으므로 봉인도 없다.

`prepare` 는 iteration 마다 한 번, 그 머리에서만 부른다. 실행기는 받은 뷰를 그대로 보낸다. 그래서 압축이 필요한 판단은
**그 한 번의 `prepare` 안에서** 끝나야 한다([§8](#8-defaultcontextengine)).

실행기가 내는 `CompactBoundary` 이벤트의 전후 크기는 로그가 아니라 **뷰**의 크기다(`ContextDecision.getViewSizeBefore()`).
로그는 압축해도 줄지 않는다. 그것을 보고하지 않는 engine(mock, passthrough)에서는 버퍼 크기를 쓴다. 이벤트를 내는 자리는
[`orca-executor.md`](orca-executor.md) 가 정한다.

---

## 5. 뷰의 결정성

같은 `(로그, 뷰 상태)` 에서는 언제나 같은 뷰가 나와야 한다.

- **노드 이동** — 다른 노드가 세션을 이어받으면 같은 레코드에서 뷰를 다시 계산한다. 결과가 다르면 모델이 보는 대화가
  노드마다 달라진다
- **프롬프트 캐시** — 뷰의 앞부분이 호출마다 바뀌면 prefix 캐시가 깨진다. 뷰는 뷰 상태가 바뀔 때만 바뀐다

그래서 뷰 계산에는 시계 · 난수 · 노드 로컬 상태 · 메모리 스냅샷이 들어가지 않는다. 요약문처럼 비결정적으로 **만들어지는**
값은 만들어진 순간 뷰 상태에 저장되고, 뷰 계산은 저장된 값을 읽기만 한다.

뷰 계산(`ViewProjection`)은 저장된 값을 너그럽게 읽는다. 모르는 `summarySpan.trigger` 는 `AUTO` 로 투영한다. trigger 는 경계
마커의 라벨일 뿐이고 대체값이 결정적이므로 결정성은 그대로다. 던지면 그 세션은 다시는 턴을 돌지 못한다.

---

## 6. 뷰 상태 연산과 합법 절단면

engine 은 뷰 상태를 세 연산으로만 바꾼다. 뷰 상태의 저장 형태와 봉인 시점은
[`session-log.md` §4](../session/session-log.md#4-뷰-상태--sessionviewstate) 가 정한다.

| 연산 | 메서드 — `TranscriptBuffer` (규칙은 `SessionLogState`) | 뜻 |
|------|------|----|
| summarize | `summarizeView(SummarySpan)` (`summarize(SummarySpan)`) | 로그의 `[fromSeq, toSeq)` 를 경계 · 요약 마커 쌍으로 대체한다. 구간 · 요약문 · 메타데이터는 `SummarySpan` 이 든다. 기존 span 을 덮으면 흡수한다 |
| drop | `dropFromView(fromSeq, toSeq)` (`drop(long, long)`) | 그 구간을 뷰에서 뺀다 — 요약 없이. prompt-too-long 복구 전용 |
| elide | `elideInView(seq, placeholder)` (`elide(long, String)`) | 그 도구 결과 본문을 placeholder 로 대체한다(롤링의 L0 prune) |

**불변식 — 합법 절단면.** `summarize` 와 `drop` 의 두 경계는 합법 절단면이어야 한다. 그 위치의 메시지가 `TOOL` 이 아니고,
앞의 모든 `tool_use` 가 해소된 곳이다(`LegalCuts`). 연산이 불변식을 검사하고, 어기면 거절한다. assistant 메시지의 시작점도
이 조건을 만족하는 절단면이다. 같은 조건이 메모리 ingest 의 청크 경계([§9](#9-메모리와의-관계))와 요약기의 구간 검증
([`compaction.md`](compaction.md))에도 쓰인다.

셋 다 **로그를 건드리지 않는다.** 뷰 상태가 바뀌면 레코드가 dirty 가 되고 로그 변경과 같은 체크포인트 경로로 저장된다.

뷰 상태가 바뀌어 원문으로 보이지 않게 된 구간은 봉인 대상이 된다. 봉인은 engine 이 아니라 저장 모델의 일이다 — 실행기가
`prepare` 에서 `COMPACT` 를 받으면 같은 스레드에서 버퍼의 봉인을 부른다
([`session-log.md` §5.3](../session/session-log.md#53-언제--어디서--버퍼를-가진-스레드에서-동기로)). engine 은 저장소를 모른다.

---

## 7. 미응답 부분

뷰의 **미응답 부분**은 마지막 assistant 메시지 뒤의 모든 것 — 방금 부른 `tool_use` 의 결과, 또는 답 뒤에 들어온 입력 —
이다(`ViewProjection.firstUnreadPosition()`). assistant 메시지가 하나도 없는 뷰는 마지막 메시지 하나가 미응답이다.

- **미응답 부분은 가리지도 흡수하지도 않는다.** 두 engine 의 모든 절단면은 미응답 부분의 시작 이하에서 고른다. 결과와 그
  `tool_use` 사이는 합법 절단이 아니므로, 미응답 부분이 도구 결과면 실제 한계는 그 `tool_use` 를 낸 assistant 메시지의
  시작이다
- **이유** — 그렇지 않으면 큰 도구 결과가 모델이 읽기 전에 요약으로 접히거나 elide 되고, 모델이 같은 도구나
  `SessionHistory` 로 다시 읽으면 같은 일이 되풀이된다. 롤링의 기본 비율로는 effective window 의 약 20% 를 넘는 결과 하나면
  일어난다. 도구 결과만이 아니라 입력까지 넓게 잡은 것은 의도다 — 최신 user 메시지도 모델이 아직 답하지 않은 입력이고,
  요약으로 접으면 모델은 원문이 아니라 요약자의 말로 질문을 받는다
- **복구도 지킨다.** `RecoveryDiff` 는 미응답 메시지를 빼는 복구 답을 거절한다. 두 engine 의 뷰 복구가 모두 거친다.
  `DefaultPromptSizeRecoveryStrategy` 는 마지막 USER 도 TOOL 도 빼지 않으므로 원래 거기에 닿지 않는다. 이 검사는 사용자
  전략을 위한 것이다
- **모델이 답하면** 그 결과는 더 이상 미응답이 아니므로 다음 iteration 의 압축이 평소처럼 가리거나 흡수한다
- **예외는 한 자리다.** 기본 engine 이 blocking 한계에서 달리 내려갈 길이 없을 때만 미응답 부분까지 요약한다([§8](#8-defaultcontextengine)).
  롤링이 미응답 부분만으로 넘칠 때의 규칙은 [`rolling-context-engine.md`](rolling-context-engine.md) 에 있다

---

## 8. `DefaultContextEngine`

배선하지 않으면 쓰이는 engine 이다. in-place 압축과 같은 판정 사다리를 뷰 위에서 돈다. 판정 정책을 바꾸지 않고도 누수
③~⑤ 가 사라진다는 것이 기록과 뷰를 나눈 첫 이득이다.

### 8.1 뷰 모드와 in-place

- **뷰 모드의 조건** — 가드가 정확히 `DefaultCompactionGuard`(하위 클래스 아님) 또는 `NoOpCompactionGuard` 이고 요약기가
  `supportsSummarize()` 일 때다
- **모드는 버퍼의 형식으로 고른다**(`TranscriptBuffer.getFormat()`) — 노드가 아니다. v2 로 올라간 레코드는 v1 쓰기 노드에서도
  뷰 모드다
- **in-place** — v1 버퍼에서는 뷰 상태를 저장할 수 없으므로 기록을 고쳐 쓰는 in-place 경로로 압축한다(`replaceWith` 로 전부
  요약). v1 쓰기 모드는 폴백 경로다 — 그 형식 전용 API 는 deprecated 이고 형식의 수명은
  [`session-log.md`](../session/session-log.md) 가 다룬다. in-place 에는 뷰도 절단면의 seq 도 없다
- **in-place 폴백** — 뷰 모드의 조건을 못 갖춘 engine 이 v2 버퍼를 만나면 in-place 로 물러나고 engine 당 WARN 을 한 번
  남긴다. 단, **뷰 상태나 manifest 를 가진 v2 버퍼는 in-place 로 압축하지 않는다.** `prepare` 는 `ViewProjection` 으로
  투영한 뷰를 보내고 `NONE`(사유 `in-place compaction refused …`, engine 당 WARN 한 번)으로 답하고, `compactNow` 는 같은
  사유로 실패하고, `recover` 는 요약이 필요 없는 뷰 drop 으로 복구한다. `replaceWith` 가 span 과 manifest 를 지우면 요약과
  봉인된 기록을 함께 잃기 때문이다. 뷰 상태도 manifest 도 없는 v2 버퍼는 in-place 로 압축한다. `PassthroughContextEngine`
  도 뷰 상태를 가진 v2 버퍼는 투영해서 보낸다
- 그 결과 뷰 모드를 못 하는 engine 구성으로 v2 레코드를 오래 서빙하면 로그가 자라고, blocking 한계 전까지는 뷰 drop 복구만
  크기를 줄이며, 실행이 결국 한계에서 실패한다. 지원되는 조립은 그런 구성을 v2 에서 거절한다([§10](#10-기존-공개-spi-의-처분))

### 8.2 판정과 AUTO 압축

**전체 compaction** 은 요약할 수 있는 앞부분 전부를 요약 하나로 대체한다. 뷰 모드에서 그 끝은 미응답 부분의 시작,
in-place 에서는 기록 끝이다.

- **판정** — 판정 사다리 · 세션 락 · 사전조건 · breaker 를 `DefaultCompactionGuard.decide(…, Compactor)` 로 재사용하고
  압축 자체만 콜백으로 받는다. 사다리와 breaker 의 규칙은 [`compaction.md`](compaction.md) 가 정본이다
- **span** — `[floorSeq, cut)`. `cut` 은 미응답 부분의 시작 이하의 마지막 합법 절단이고, 미응답 부분이 없으면 `nextSeq` 다.
  요약 입력은 그 절단면 앞의 뷰(이전 `[B, S]` 포함)다. 이전 요약을 다시 요약하는 것은 in-place 재압축과 같으므로 누수 ② 는
  기본 engine 에 그대로 남는다 — 그것을 푸는 것이 롤링 engine 이다. span 을 연산이 거절하면 실패이고 로그는 그대로다
- **결과** — 뷰는 `[B, S]` 와 그 뒤의 미응답 메시지(원문)다. 미응답 부분이 도구 결과면 그 `tool_use` 를 낸 assistant
  메시지도 함께 남는다. 뷰가 assistant 메시지로 끝나면(턴 사이) `[B, S]` 만 남는다. PostCompact 훅이 로그에 붙이는 메시지는
  미응답 메시지 **뒤에** 온다
- **미응답 부분만 남았을 때** — 그 앞에 로그 항목이 하나도 없으면(뷰가 미응답 입력뿐이거나, 이미 있는 span 의 마커 뒤에
  미응답 부분만 있다) 요약할 것이 없다. 요약 호출도 훅도 없고 뷰 상태도 그대로다. 결과는 `NothingToCompactException` 을 실은
  실패이고, 가드는 그것을 `WARN`(`auto-compact threshold reached but nothing to compact: …`)으로 돌려준다. `COMPACT` 가
  아니므로 `CompactBoundary` 이벤트와 압축 기록이 생기지 않고 breaker 에도 세지 않는다. 모델이 답할 때까지 iteration 마다 같은
  `WARN` 이 나올 뿐 요약을 되풀이하지 않는다. 이전 요약의 마커만 다시 요약하지 않는 것은 그것이 아무것도 줄이지 못하기
  때문이다
- **budget-forced** — 유효 임계값만 warning 밴드로 내려가고 절단은 같다
- **span 장부** — 다음 압축이 span 을 넓힌다. 봉인은 span 이 가린 구간만 가져가므로 미응답 메시지는 레코드에 남는다. 턴의
  rewind 지점은 대개 `cut` 과 같은 자리다(그 턴의 입력이 미응답 부분의 시작이다)

**이유.** 기본 engine 의 판정 규칙은 in-place 와 같지만 뷰 모드의 요약은 미응답 부분을 흡수하지 않는다. in-place 와 결과가
같은 것보다 모델이 읽지 않은 도구 결과와 입력을 원문으로 받는 것을 앞세운다 — 그렇지 않으면 auto 임계값을 넘는 도구 결과
하나가 모델이 읽기 전에 요약으로 접히고, 같은 도구를 다시 부르면 또 접히기 때문이다.

### 8.3 blocking 한계

실행기는 iteration 마다 `prepare` 를 한 번 부르고 받은 뷰를 그대로 보낸다. 그래서 한계에서 강제된 압축은 **그 한 번의
`prepare` 안에서** 뷰를 한계 아래로 내려야 한다. 성공이라 답하고 한계를 넘는 뷰를 내면 프로바이더가 거절하고, 복구는 미응답
부분도 마커도 뺄 수 없으므로 실행이 `LlmPromptTooLongException` 으로 끝난다. 크기는 판정한 가드의 추정기와 한계로 잰다
(`DefaultCompactionGuard.estimateTokens` · `blockingLimit`, 시스템 프롬프트 포함).

| 경우 | 동작 | 요약 호출 |
|------|------|-----------|
| 미응답 부분을 남겨도 한계 아래로 내려간다 | 그 앞만 요약하고 미응답 부분은 원문으로 남긴다 | 1 |
| 미응답 부분 앞에 아무것도 없거나, 미응답 부분만으로 한계 이상이다 | 뷰 전체를 요약한다(WARN 로그 한 줄). 그 뒤의 뷰는 `[B, S]` 뿐이다. 내려가지 못할 앞부분 요약에 호출을 먼저 쓰지 않는다 | 1 |
| 앞부분의 요약이 길어 뷰가 다시 한계 이상이다 | 같은 `prepare` 에서 뷰 전체를 한 번 더 요약한다. 두 번째가 실패하면 첫 압축은 남고 "한계를 넘은 채 보낸다" 로 보고한다 | 2 |
| 전체를 요약해야 하는데 뷰의 끝이 합법 절단면이 아니다(뷰가 결과 없는 `tool_use` 를 품은 채 TOOL 메시지로 끝난다) | 요약 호출도 훅도 없이 실패하고 가드가 `BLOCK` 으로 답한다 | 0 |
| 뷰가 결과 없는 `tool_use` 를 가진 ASSISTANT 메시지로 끝나거나, 앞에 결과 없는 `tool_use` 가 남은 채 USER 메시지로 끝난다(`DefaultCompactionGuard.preconditionMet` — USER 로 끝나도 모든 `tool_use` 가 해소되어 있으면 사전조건은 참이다) | 가드의 사전조건이 engine 보다 먼저 걸려 압축을 시도하지 않고 `WARN`(`blocking-limit reached but precondition unmet; deferring`)으로 한계를 넘은 채 보낸다 | 0 |

- 끝이 비합법인 마지막 두 행은 실행기로는 닿지 않는다. 두 실행기 모두 한 응답의 `tool_use` 마다 결과 하나를 만들어(중단되어
  건너뛴 것은 오류 결과) TOOL 메시지 **하나**로 붙이고, `prepare` 는 그 뒤 다음 iteration 의 머리에서만 부르기 때문이다. 손으로
  만든 버퍼나 다른 곳에서 쓴 레코드에 대한 방어다
- **두 번 요약해도 돌려주는 기록은 하나다**(`CompactionMetadata.getSummaryCalls()` 가 2). 압축 전 크기는 첫 호출 전의 뷰를
  가드가 센 값, 요약한 메시지 수는 그 뷰의 메시지 전부, 시작 시각은 첫 호출의 것, 도구 이름은 두 호출이 찾은 것의 합이고,
  압축 후 크기와 끝 시각은 두 번째 호출의 것이다. 뷰 상태의 span 은 두 번째 호출의 것이다 — 이전 span 을 흡수한 요약이 늘
  그렇듯이. 이 두 번은 호출자에게는 압축 한 번이고 훅에게는 두 번이다. 훅의 발화 규칙은 [`compaction.md`](compaction.md) 에 있다
- **성공이 아닌 것을 성공이라 하지 않는다.** 전체를 요약하고도 한계 이상일 수 있다 — 요약문, 시스템 프롬프트, PostCompact
  훅이 붙인 메시지 때문이다. 더 요약할 것이 없으므로 뷰는 그대로 보내고 결정은 `COMPACT` 이지만, 사유에
  `DefaultCompactionGuard.STILL_OVER_BLOCKING`(`… the view is still at or above the blocking limit …; sending it as it is`)이
  붙고 기록의 `isOverBlockingLimit()` 가 참이다. 한계에서 강제된 압축의 기록에는 언제나 `getBlockingLimit()` 가 실리고,
  압축 후 크기는 훅이 돈 뒤의 뷰를 가드의 추정기로 센 값이다. 요약 호출이 실패하면 압축 실패이고 가드는
  `BLOCK`(`compaction failed at blocking limit: …`)이다
- **롤링과 다른 것은 미응답 부분을 접느냐다.** 다 요약하고도 넘치면 그대로 보내는 것은 롤링도 같다 — 롤링은 head 까지
  흡수하고도 blocking 이상이면 `COMPACT` + `STILL_OVER_BLOCKING` 으로 뷰를 그대로 보내고, 새로 흡수할 것이 없는 같은 뷰가
  다시 올 때만 `BLOCK` 으로 끝낸다([`rolling-context-engine.md` §8](rolling-context-engine.md#8-미응답-부분만으로-넘칠-때)).
  차이는 무엇을 접느냐에 있다. 롤링은 미응답 부분을 끝내 흡수하지 않는다. 기본 engine 은 blocking 한계에서 미응답 부분까지
  요약할 수 있다(위 표의 둘째 · 셋째 행). 기본 engine 에는 접힌 원문을 되찾을 `SessionHistory` 가 없어, 실행을 끝내기보다
  접고 프로바이더의 판정과 prompt-too-long 복구에 맡긴다. 이것이 이 engine 에서 모델이 읽지 않은 것을 요약자의 말로 받는 단
  하나의 자리다 — 접는 것이 실행을 실패시키는 것의 유일한 대안이기 때문이다

### 8.4 복구와 `compactNow`

- **복구** — 기본 복구 전략(`DefaultPromptSizeRecoveryStrategy`)은 가장 오래된 USER 메시지 하나(마지막 USER 와 압축 마커
  제외)를 고른다. engine 은 그 메시지를 `drop(s, s + 1)` 한다 — USER 메시지 앞뒤는 합법 절단면이다. 흐름은 전략 1회, 재시도
  1회이고 복구 사유 로그는 `DefaultContextEngine.recover` 가 남긴다. 사용자 전략의 답은 `RecoveryDiff` 가 뷰 상태 연산으로
  옮긴다([§10](#10-기존-공개-spi-의-처분))
- **`compactNow`** — 같은 절단면까지를 MANUAL 로 요약한다. 턴 사이에는 뷰가 assistant 메시지로 끝나므로 전체다. 턴이
  중단되어 뷰가 답을 받지 못한 사용자 메시지로 끝나면 그 앞까지만 요약하고 그 메시지는 원문으로 남는다. 빈 뷰와 미응답
  부분만 남은 뷰는 요약기를 부르지 않고 `nothing to compact` 로 실패한다. 요약기의 `summarize()` 가 null 을 돌려주면 깨끗한
  실패를 돌려준다
- **`/compact` 의 경합 계약은 두 engine 이 다르다** — [`rolling-context-engine.md` §9](rolling-context-engine.md#9-복구와-compact).
  명령 흐름과 breaker 리셋은 [`compaction.md` §9](compaction.md#9-compact) 에 있다

---

## 9. 메모리와의 관계

engine 은 메모리를 부르지 않는다. 메모리는 기록을 읽는다. ingest 파이프라인 자체는
[`peer-memory.md`](../memory/peer-memory.md) 의 일이고, 이 절은 압축과의 관계와 청크 경계를 정한다.

- **`EXECUTION_END`** — 실행 끝 ingest 는 로그 위의 mark(seq)로 델타를 구한다. 압축은 로그를 바꾸지 않으므로 mark 는 무효가
  되지 않고, 압축된 실행도 원문 그대로 ingest 된다(누수 ④). 요약문은 로그에 없으므로 ingest 될 수 없다
- **`SESSION_END`** — 호출자는 `SessionLogReader` 로 로그를 넘긴다(누수 ⑤)
- **`SYNTHETIC` 제외** — 두 모드 모두 `origin = SYNTHETIC` 항목을 넘기지 않는다. 런타임이 넣는 메시지를 대화로 ingest 하지
  않기 위해서다
- **청크** — 긴 실행의 델타나 세션 전체 로그는 창의 몇 배일 수 있고, `LlmDeriver` 는 받은 메시지를 한 LLM 호출에 싣는다.
  압축이 더 이상 페이로드를 창 크기 이하로 묶어 주지 않으므로 **ingest 는 `maxIngestTokens`(기본 32,000, 설정 키 없음) 단위로
  나누어 보낸다.** 청크 경계는 합법 절단면이다 — 짝 잃은 `tool_result` 를 받으면 deriver 의 LLM 호출을 프로바이더가
  거절한다. 두 절단 사이의 run 이 예산을 넘으면 쪼개지 않고 통째로 보낸다. `SYNTHETIC` 항목을 빼는 것은 짝을 가르지 않는다(런타임
  주입은 USER 또는 짝 없는 assistant 메시지다). `IngestingExecutionMemorySink` 와 `SESSION_END` 호출자의 계약이고,
  `SessionLogReader` 의 페이지 읽기가 같은 경계로 자른다
- **압축 직전 flush** — 하지 않는다. 지키려던 원문이 사라지지 않는다

`PeerMemory` 는 "무엇을 알게 되었나" 이고 로그는 "정확히 무엇이 오갔나" 다. 둘의 분업은
[`rolling-context-engine.md`](rolling-context-engine.md) 의 되찾기 범위 결정이 쓴다.

---

## 10. 기존 공개 SPI 의 처분

뷰를 바꿀 수 있던 공개 seam 넷의 처분은 다음과 같다. 넷 다 `OrcaAgentRuntime.Builder` 와 `OrcaProviderDependencies.Builder`
로 외부에서 주입할 수 있었다.

| SPI | 결정 | 이유 |
|-----|------|------|
| `CompactionGuard` | deprecated. v1 쓰기 모드에서만 커스텀 구현을 받는다 | 계약이 "버퍼를 받아 필요하면 고친다" 이다. 뷰 상태 연산으로 기계적으로 옮길 수 없다. 판정 규칙을 바꾸고 싶은 쪽은 `ContextEngine` 을 구현한다. `DefaultCompactionGuard` · `NoOpCompactionGuard` 자체와 가드 타입을 받는 주입 자리는 뷰 모드가 재사용하므로 deprecate 하지 않는다. 가드를 **소비**하던 `CompactCommand` 는 `engine.compactNow` 를 부른다 |
| `CompactionEngine` (요약기) | 유지. `summarize(SummaryRequest)` · `supportsSummarize()` · `summaryInstalled(…)` 를 더하고 `compact(CompactionRequest)` 는 deprecated | 요약을 만드는 부분은 그대로 쓸모 있다. `supportsSummarize()` 는 default 로 false 이고 `DefaultCompactionEngine` 은 true 다 — 호출해 보지 않고 요약 능력을 탐지하려면 능력 메서드가 필요하다. `summarize` 는 `CompactionResult` 를 돌려주고 PreCompact 만 발화한다. PostCompact 는 설치된 뒤의 상태를 보는데 그것은 호출자만 만들므로, 설치한 쪽이 `summaryInstalled`(기본 no-op)를 부르고 거기서 발화한다. 롤링을 위해 `SummaryRequest` 에 `rolling` · `previousSummary` · `targetSummaryTokens` 가 있다 |
| `PromptSizeRecoveryStrategy` | 유지. 어댑터(`RecoveryDiff`)로 받는다. **줄이기만 하는 전략**만 동작한다 | 계약이 `List<Message>` 를 받아 `List<Message>` 를 돌려준다. 어댑터는 넘긴 뷰와 돌려받은 목록을 인스턴스로 대조한다 — 왼쪽부터 탐욕으로, 같은 인스턴스가 둘이면 앞의 것이 남는다. 빠진 위치를 run 으로 묶어 run 마다 `drop` 하나 — assistant(`tool_use`)와 그 `TOOL` 을 함께 뺀 정당한 결과가 개별 검사에서 거절되지 않게. 새 메시지나 고쳐 쓴 메시지(긴 도구 결과를 잘라 내는 전략 포함), 뷰가 만든 메시지(경계 · 요약 마커, elide placeholder)나 미응답 메시지를 빼는 답, 아무것도 빼지 않는 답, 합법 절단을 어기는 구간은 WARN 과 함께 거절한다. 모든 구간을 상태의 사본에서 먼저 검증하므로 답은 통째로 적용되거나 전혀 적용되지 않는다. 이를 위해 뷰 계산은 원문 항목의 `Message` 인스턴스를 그대로 쓴다 |
| `PromptTooLongHandler<TranscriptBuffer>` (`LlmCallGateway`) | Orca 에서 배선하지 않는다. 복구는 `engine.recover` 다 | 게이트웨이 안에 버퍼를 고치는 두 번째 복구 경로가 생기면 engine 의 결정성이 깨진다 |

`TimeBasedMicrocompact` 도 deprecated 다. 지금의 L0 무비용 정리는 롤링 engine 의 prune 이다
([`rolling-context-engine.md`](rolling-context-engine.md)).

**v2 에서 거절하는 조합.** `DefaultContextEngine.Builder.writeFormat(V2)` 의 `build()` 는 커스텀 `CompactionGuard`,
`supportsSummarize()` 가 false 인 요약기, 요약기 없는 Default 가드를 거절한다. 그런 조합은 v2 버퍼를 뷰 모드로 압축할 수
없기 때문이다. `OrcaAgentRuntimeFactory` 는 노드의 쓰기 형식으로 이 검사를 건다 — 지원되는 조립(`OrcaAgentRuntimeFactory`
· `AimonStackBuilder` · 스타터)에서는 그런 노드가 v2 로 기동하지 않는다. 이 거절은 그 조립에서만 강제된다.
`OrcaAgentRuntime.Builder` 가 파생하는 engine 은 형식을 받지 않으므로(v1) 그 경로로 들어온 커스텀 가드는 거절되지 않고, 그
노드가 v2 레코드를 만나면 [§8.1](#81-뷰-모드와-in-place) 의 in-place 폴백대로 압축하지 않는다. 이 틈은
[backlog](../../backlog/agent-execution-open-items.md) 에 있다.

---

## 11. 선택과 관측

- **배선** — 배선하지 않으면 `DefaultContextEngine` 이다. `OrcaAgentRuntimeFactory` 가 런타임을 만들 때 만든다. 명시한 engine
  이 없으면 `OrcaAgentRuntime.Builder.build()` 가 가드 · 복구 전략 · 요약기로 `DefaultContextEngine` 을 파생하므로
  `OrcaAgentRuntime.getContextEngine()` 은 null 이 아니다. 비-Spring 조립은 `AimonStackBuilder` 의 spec 이 engine 을 받는다
- **설정 키** — 키 이름은 결정이므로 여기서 정한다. 사용법과 나머지 키는
  [`context-engine-guide.md`](../../features/agent-execution/context-engine-guide.md) 의 몫이다
  - Spring `aimon.context.engine`(`default` | `rolling`) = `ExecutorSpec.contextEngine(...)` — 에이전트의 기본값
  - AGENT.md frontmatter `context-engine` — 에이전트별 선택. 이것이 이긴다
  - runtime 은 agent-scoped 이므로 engine 도 agent 별로 갈린다. 롤링의 요구 조건(v2 쓰기 형식)과 조정 값은
    [`rolling-context-engine.md`](rolling-context-engine.md) 에 있다
- **`CompactionMetadata` 의 필드** — 롤링은 압축 빈도가 높아 운영 중 튜닝이 필요하다. 그래서 압축 기록이 무엇을 했는지 말한다
  - `kind` — `PRUNE`(도구 결과 본문만 가림) / `ROLLING`(span 을 넓힘) / `FULL`(기본 engine 의 요약) / `FALLBACK`(압축하지 않기로
    한 결정 — 요약이 아니다)
  - 뷰의 head · span · tail 토큰, 요약 토큰, 흡수한 seq 범위(`getAbsorbedFromSeq` · `getAbsorbedToSeq`)
  - `getBlockingLimit()` · `isOverBlockingLimit()` — 판정 기준이 된 blocking 한계와, 압축 뒤에도 그 이상인지. 실행기는 결정의
    사유를 실행 결과에 싣지 않으므로 이 사실은 메타데이터로 간다. 모델의 한계를 모르는 호출자도 평소의 blocking 압축과
    구별할 수 있다
  - `getSummaryCalls()` — 한 압축이 부른 요약 호출 수([§8.3](#83-blocking-한계))
- **실행 결과의 압축 기록** — `COMPACT` 결정의 메타데이터가 실행 결과의 `compactionEvents` 에 쌓인다. `WARN` 결정 가운데
  `kind = FALLBACK` 메타데이터를 가진 것도 더한다. 다른 `WARN` 은 로그로만 남는다. FALLBACK 은 그것을 결정한 iteration 마다
  기록된다 — 임계값을 넘은 채 어떤 절단도 도움이 되지 않으면 이후 iteration 마다 같은 결정이 나므로 한 실행에 FALLBACK 항목이
  iteration 수만큼 쌓인다. 관측으로는 정확하고, 그것이 의도된 동작이다
- **COMPACTION span** — `ContextRequest.compactionObserver` 로 연다. [`tracing.md`](../observability/tracing.md) D12

---

## 12. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|------|------|-------------------|
| 뷰를 줄이는 방법 | 로그는 append-only, 뷰 상태로 대체 | **기록을 고쳐 쓰는 모델 위에 정책 추가** — 원문 보관소와 그 키 · 크래시 처리 · GC, ingest mark 와 rewind 지점의 재매핑, 압축 후 flush seam 이 모두 필요해진다. 전부 "기록을 고쳐 쓴다" 는 전제에서 나오는 비용이다 |
| 판정 · 복구 · `/compact` 의 자리 | `ContextEngine` 하나 | **각자 버퍼를 고침** — 가드, 복구 전략, 명령, 게이트웨이의 `PromptTooLongHandler` 넷이 뷰를 바꿀 수 있으면 뷰 상태의 일관성을 누구도 보장하지 않는다 |
| 정책 선택 | engine 구현 교체 | **가드에 정책 값 객체 주입** — 판정 · 구간 선택 · 요약 방식이 함께 바뀌는데 가드는 "언제" 만 아는 자리다 |
| 시스템 프롬프트 조립 | engine 밖. 크기만 입력으로 | **engine 이 흡수** — 이미 교체 가능한 seam 이고, 합칠 유일한 이유(CTX-05 캐시 경계)는 아직 없다 |
| 포크 | 주입 가능, 기본 `passthrough()`, 정책이 요구하면 부모 engine 상속 | **`passthrough()` 고정** — 포크가 가진 설정 가능한 압축을 없앤다 |
| 복구 연산 | `drop(from, to)` + 합법 절단면 불변식 | **`dropBefore(seq)` (prefix)** — 기본 복구는 USER 메시지 하나를 빼고, prefix 는 tool 짝을 자를 수 있다 |
| 복구 답의 대조 | 인스턴스 대조 + run 단위 `drop` + 통째 적용 | **인스턴스 동일성만으로 위치 결정** — 같은 인스턴스가 두 번 append 될 수 있어 모호하다. **위치마다 개별 검사** — tool 짝을 함께 뺀 정당한 답이 거절된다 |
| 기본 engine 뷰 모드의 요약 범위 | 미응답 부분을 남긴다 | **in-place 와 같은 결과(뷰 전체 요약)** — auto 임계값을 넘는 도구 결과 하나가 모델이 읽기 전에 접히고, 같은 도구를 다시 부르면 또 접힌다 |
| 기본 engine 이 blocking 한계에서 미응답 부분을 남기고는 내려가지 못할 때 | 미응답 부분까지 뷰 전체를 요약하고, 그래도 넘치면 `COMPACT` + `STILL_OVER_BLOCKING` 으로 그대로 보낸다 | **미응답 부분을 남기고 실행 종료**(롤링의 선택 — 롤링은 미응답 부분을 흡수하지 않고, 흡수할 것이 없는 같은 뷰가 다시 오면 `BLOCK` 이다) — 기본 engine 에는 접힌 원문을 되찾을 `SessionHistory` 가 없다. 판정은 프로바이더와 prompt-too-long 복구에 맡긴다 |
| ingest 크기 | `maxIngestTokens` 청크 | **한 번에** — 압축이 더 이상 페이로드를 묶어 주지 않는다 |
| 압축 전 메모리 flush | 하지 않는다 | **에이전트 주도 flush iteration** — iteration 이 하나 더 들고, 그것이 다시 컨텍스트를 키울 수 있고, 무엇을 저장할지가 모델에 달린다. 로그가 원문을 들면 지킬 것이 없다 |
| `SessionId` | 압축으로 바꾸지 않는다 | **압축마다 새 세션 + parent** — lease · 펜싱, `SessionRouter`, `IdempotencyStore`, `SessionInbox`, 1:N `LiveSession` 이 모두 `SessionId` 에 걸려 있다 |
| 메모리 주입 고정 | 하지 않는다 | **세션 시작 시점 값으로 고정** — 아래 문단. 얻는 것이 없다 |
| 구현체의 패키지 | `DefaultContextEngine` · `RollingContextEngine` · `ViewProjection` · `RecoveryDiff` 는 `agent.context` 에 둔다(`.impl` 아님) | **`agent.context.impl`** — 구현체를 `agent.context` 트리 밖에서 직접 만들거나 참조하는 호출자(`command.system.CompactCommand` · `subagent.execution.DefaultSubagentExecutor`, `aimon-bootstrap` 의 `ExecutorSpec`, 스타터의 `AimonAutoConfiguration`)가 모두 `.impl` 을 import 하게 된다 — 패키지 규약이 막는 의존이다. 같은 패키지의 `DefaultContextAssembler` 가 먼저 SPI 옆에 놓인 선례다. `agent.context.impl` 을 겨눈 ArchUnit 규칙은 없으므로 이 배치는 규칙이 아니라 이 결정이 지킨다 |
| `agent.context` 의 hook 의존 | `HookRegistry` 하나만 허용하는 ArchUnit 규칙(`agentContextMayDependOnHookRegistryOnly`) | **`agent..` 의 hook 금지 규칙 그대로** — `ContextRequest` 가 `HookRegistry` 를 들어야 한다. `agent.compact` · `agent.session` 과 같은 모양이다 |
| `ContextEngineKind` 의 위치 | `at.aimon.core.agent` | **`agent.context`** — `AgentMetadata`(`agent`)가 들고 있고 `agent.context` 는 이미 `agent` 에 의존하므로 순환이 생긴다 |
| 저장된 span 의 모르는 trigger | `AUTO` 로 투영 | **예외** — 그 세션이 다시는 턴을 돌지 못한다. trigger 는 라벨일 뿐이라 대체해도 결정성이 그대로다 |

**메모리 주입 고정을 하지 않는 이유.** 시스템 프롬프트의 메모리 파트는 실행마다 다시 읽힌다. 그러나 지금은 비용 누수가
아니다. Anthropic 어댑터는 `cache_control` 을 보내지 않고, OpenAI 의 자동 prefix 캐시는 메모리 파트 뒤의 context SYSTEM
블록 때문에 이득이 없다. CTX-05(캐시 경계)가 선행 조건이다. 그때 되살린다면 고정 단위는 "메모리 스냅샷이 의미 있게
바뀌었을 때" 같은 명시적 갱신 정책이 낫다 — 롤링에서는 span 이 넓어져도 system + head prefix 는 그대로여서, 압축마다
메모리를 다시 읽으면 오히려 그 prefix 캐시를 깬다.

롤링 engine 고유의 결정(span 개수, 복원 훅, 되찾기 범위, 롤링의 전제)은
[`rolling-context-engine.md`](rolling-context-engine.md) 에 있다.

---

## 13. 하지 말 것

- **engine 밖에서 뷰를 줄이지 않는다.** 가드 · 명령 · 훅 · 게이트웨이 핸들러가 뷰 상태를 고치면 결정성([§5](#5-뷰의-결정성))을
  누구도 지키지 않는다. 로그를 자르는 rewind · `/clear` 가 잘린 seq 를 정리하는 것만 예외다
- **합법 절단면이 아닌 곳을 자르지 않는다.** `summarize` 와 `drop` 은 불변식을 검사한다
- **미응답 부분을 가리거나 흡수하지 않는다.** 예외는 기본 engine 이 blocking 한계에서 달리 내려갈 길이 없을 때 하나다
- **뷰 계산에 비결정적 입력을 넣지 않는다.** 요약처럼 비결정적으로 만들어지는 값은 만들어진 순간 뷰 상태에 저장한다
- **로그를 LLM 에 보내지 않는다.** 호출 자리에서 `transcriptBuffer.getMessages()` 를 다시 쓰면 압축이 없는 것과 같다
- **뷰를 영속하지 않는다.** 영속하는 것은 뷰 상태다
- **engine 이 메모리를 부르지 않는다.** 메모리는 로그를 읽는다([§9](#9-메모리와의-관계))
- **런타임이 넣는 메시지를 `CONVERSATION` 으로 append 하지 않는다.** 대화로 ingest 되고 되찾기에 섞인다
- **span 을 좁히지 않는다.** span 은 넓어지기만 한다. rewind 로 로그가 잘리는 경우만 예외이며, 그 처리는
  [`session-log.md` §8.1](../session/session-log.md#81-rewind--truncatefromseq) 이 정한다
- **뷰 모드에서 `replaceWith` 로 물러나지 않는다.** 뷰 상태나 manifest 를 가진 v2 버퍼를 in-place 로 압축하면 요약과 봉인된
  기록을 잃는다([§8.1](#81-뷰-모드와-in-place))

---

## 14. 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| SPI · 요청 · 결정 · 뷰 | [`ContextEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextEngine.java), [`ContextRequest.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextRequest.java), [`ContextDecision.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextDecision.java), [`ContextView.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextView.java), [`ContextCaller.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextCaller.java) |
| 압축하지 않는 engine | [`PassthroughContextEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/PassthroughContextEngine.java) |
| 기본 engine (뷰 모드 · in-place 폴백 · blocking 한계 · 복구) | [`DefaultContextEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/DefaultContextEngine.java) |
| 뷰 계산 · 미응답 부분 | [`ViewProjection.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ViewProjection.java) |
| 복구 답의 대조 | [`RecoveryDiff.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/RecoveryDiff.java), [`DefaultPromptSizeRecoveryStrategy.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultPromptSizeRecoveryStrategy.java) |
| 뷰 상태 · 합법 절단면 | [`SessionViewState.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/SessionViewState.java), [`LegalCuts.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/LegalCuts.java), [`TranscriptBuffer.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/session/transcript/TranscriptBuffer.java) |
| 판정 재사용 (`decide`) · `STILL_OVER_BLOCKING` | [`DefaultCompactionGuard.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultCompactionGuard.java) |
| 요약기 SPI (`summarize` · `supportsSummarize` · `summaryInstalled`) | [`CompactionEngine.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactionEngine.java), [`SummaryRequest.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/SummaryRequest.java) |
| 요약할 것 없음 | [`NothingToCompactException.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/NothingToCompactException.java) |
| 압축 기록 · 종류 · 관찰자 | [`CompactionMetadata.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactionMetadata.java), [`CompactionKind.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactionKind.java), [`CompactionObserver.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/compact/CompactionObserver.java) |
| LLM 호출 자리 (메인 · 포크 · 스킬) | [`OrcaAgentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java), [`DefaultSubagentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/subagent/execution/DefaultSubagentExecutor.java), [`LlmSkillExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/skill/execution/llm/LlmSkillExecutor.java) |
| 배선 · engine 파생 · v2 검사 | [`OrcaAgentRuntimeFactory.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntimeFactory.java), [`OrcaAgentRuntime.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntime.java), [`OrcaProviderDependencies.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/orca/OrcaProviderDependencies.java) |
| 선택 (`ContextEngineKind` · spec · 속성 · frontmatter) | [`ContextEngineKind.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/ContextEngineKind.java), [`ExecutorSpec.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/spec/ExecutorSpec.java), [`AimonStackBuilder.java`](../../../modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java), [`AimonProperties.java`](../../../modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/AimonProperties.java), [`MarkdownAgentDefinitionParser.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/definition/parser/MarkdownAgentDefinitionParser.java) |
| 수동 명령 | [`CompactCommand.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/command/system/CompactCommand.java) |
| 메모리 ingest | [`IngestingExecutionMemorySink.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/memory/IngestingExecutionMemorySink.java), [`IngestChunks.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/memory/IngestChunks.java), [`LlmDeriver.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/memory/deriver/LlmDeriver.java) |
| 시스템 프롬프트 (engine 밖) | [`SystemPromptRenderer.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/SystemPromptRenderer.java), [`ContextAssembler.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/context/ContextAssembler.java) |
| 패키지 의존 규칙 | [`PackageDependencyArchitectureTest.java`](../../../modules/aimon-core/src/test/java/at/aimon/core/architecture/PackageDependencyArchitectureTest.java) |
