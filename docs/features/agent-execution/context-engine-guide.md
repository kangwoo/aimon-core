# Context Engine 가이드 — 긴 대화의 컨텍스트 줄이기

에이전트의 대화가 모델 창보다 길어질 때 **LLM 에 무엇을 보낼지** 정하는 자리가 `ContextEngine` 이다. 이 문서는 두 engine
중 무엇을 고르고 어떻게 켜는지를 다룬다. 설계 근거는
[`context-engine.md`](../../design/agent-execution/context-engine.md) 와
[`session-log.md`](../../design/session/session-log.md) 에 있다.

---

## 1. 두 engine

| engine | 무엇을 하나 | 언제 고르나 |
|--------|------------|------------|
| `default` (기본) | 모델의 auto-compact 임계값에서 뷰를 요약 하나로 바꾼다. 모델이 아직 답하지 않은 부분(방금 받은 도구 결과, 최신 입력)은 요약에 넣지 않고 원문으로 둔다 — 그것만 남았으면 압축하지 않고 경고만 남긴다. blocking 한계에서는 그 부분을 남겨서 한계 아래로 내려갈 수 없을 때만 그것까지 한 번에 요약하고, 그러고도 한계 이상이면 결정의 사유와 압축 기록(`isOverBlockingLimit()`)에 그 사실을 싣는다. 버전 1 로그에서는 전과 같이 전부 요약한다 | 대화가 대개 창 안에서 끝난다 |
| `rolling` | **head**(세션의 첫 요청)와 **tail**(최근 대화)을 원문으로 두고 가운데만 요약한다. 더 일찍, 더 작게 압축하고 요약은 매번 **갱신**한다. `SessionHistory` 도구를 등록한다 | 한 세션이 창의 몇 배로 길어진다 — 운영 대화, 긴 조사 |

두 engine 모두 **로그는 건드리지 않는다**(버전 2 쓰기 형식에서). 압축은 뷰 상태에 "어느 구간을 어떤 요약으로 대체했는가" 를
적을 뿐이고, 원문은 레코드(또는 봉인된 세그먼트)에 그대로 남는다. 그래서 메모리 ingest 는 요약문이 아니라 원문을 받는다.

## 2. 켜기

`rolling` 은 **버전 2 로그 쓰기 형식**을 요구한다 — 요약 구간을 뷰 상태에 적어야 하는데 버전 1 레코드는 그것을 담지 못한다.
기본 쓰기 형식이 `v2` 이므로(스타터 · `SessionSpec` · CLI 모두) 따로 설정할 것은 없다. 쓰기 형식을 `v1` 로 고정한 노드에서
`rolling` 을 고르면 **기동이 실패한다.**

IMPORTANT: 클러스터에 버전 2 를 **읽지 못하는** 빌드의 노드가 아직 남아 있으면, 그 노드가 모두 교체될 때까지 쓰기 형식을
`v1` 로 고정한다(아래 예시의 값을 `v1` 로). 교체가 끝난 뒤에 고정을 푼다. 한번 버전 2 로 쓰인 레코드는 버전 1 쓰기 노드도
버전 2 로 다시 쓴다.

### 2.1 Spring Boot 스타터

```yaml
aimon:
  session:
    log-write-format: v2    # v2(기본) | v1
  context:
    engine: rolling         # default(기본) | rolling — 에이전트가 따로 적지 않았을 때의 값
    rolling:                # 선택 — rolling 의 임계값 (§4). 적지 않은 값은 engine 기본값
      tail-token-ratio: 0.25
      prune-min-tokens: 1000
```

`aimon.session.store` 가 `in-memory` 가 아니면 같은 백엔드 모듈의 `SessionLogSegmentStore` 도 빈으로 내놓는다
(`MongoSessionLogSegmentStore` · `PostgresSessionLogSegmentStore` · `RedisSessionLogSegmentStore`). 버전 2 는 로그를 제자리에서
줄이지 않으므로, 세그먼트 저장소가 없으면 아무것도 봉인되지 않고 레코드가 세션의 전 이력을 영원히 싣는다 — 스택은 그때
`session-log-sealing` degradation 을 기록한다. `AimonStackSpec` 조립이라면 `SessionSpec.segmentStore(...)` 로 준다.
다시 열리지 않는 세션의 고아 세그먼트까지 치우려면 `aimon.session.segment-sweep-interval` 로 저장소 단위 스윕을 켠다 —
[웹 세션 배포 가이드](../session/web-session-deployment-guide.md) 참조.

### 2.2 에이전트별 — AGENT.md

```yaml
---
name: ops
context-engine: rolling     # default | rolling. 적지 않으면 배포의 기본값
---
```

키는 `allowed-tools` 처럼 kebab 이다. `contextEngine` 이라고 적으면 무시되지 않고 파싱이 실패한다 — 무시하면 작성자는
rolling 이라고 믿는 채 기본 engine 으로 돈다.

**언제 압축할지**도 에이전트가 정한다. 모델의 창 크기는 모델에 대한 사실이라 모델 레지스트리에 그대로 두고, `context`
블록은 그 위에 얹는 정책이다. 에이전트 자신의 턴에서, 호출마다 해석된 모델의 창 위에 적용된다.

```yaml
---
name: ops
context:
  max-window-tokens: 500000   # 창 상한. min(모델 창, 이 값) — 모델 창을 넘겨 올리지는 못한다
  auto-compact-ratio: 0.5     # 유효 창(창 − 출력 예약)의 이 비율을 넘으면 자동 압축. [0.1, 1], 기본 0.8
---
```

자동 압축의 시작점은 두 엔진 모두 이 비율 하나로 정해진다 — `auto-compact-ratio × 유효 창`. 적지 않으면 모델 항목의
기본값 0.8 이다(128K 창이면 86,400, 1.05M 창이면 824,000). blocking 한계는 비율이 아니라 (상한이 걸린) 창을 따른다.
`max-window-tokens` 는 출력 예약과 경고 대역이 들어갈 만큼(40,001 이상)이어야 하고, 모델의 출력 예약이 그보다 넓어
상한이 맞지 않으면 그 모델에는 상한 없이 모델의 창을 쓰고 WARN 을 한 번 남긴다. 이 키는 `model.maxTokens`(출력 토큰
상한)와 다른 값이다. 모르는 키, 값 없이 적은 `context:`, 소수나 32비트를 넘는 상한, 문자열 비율은 파싱이 실패한다.
서브에이전트 · 스킬 · 워크플로 포크는 압축 게이트 없이 돌므로 이 정책이 닿지 않는다.

### 2.3 Spring 이 아닌 조립 — `AimonStackSpec`

```java
AimonStackSpec.builder()
        .session(SessionSpec.builder().logWriteFormat(SessionLogFormat.V2).build())
        .executor(ExecutorSpec.builder().contextEngine(ContextEngineKind.ROLLING).build())
        // ...
        .build();
```

rolling 의 임계값은 `ExecutorSpec.builder().rollingContextEngineCustomizer(b -> b.tailTokenRatio(0.25))` 로 바꾼다(§4).
`OrcaAgentRuntimeFactory` 를 직접 쓰는 조립은 `withSessionLogWriteFormat(...)` · `withContextEngine(...)` ·
`withRollingContextEngineCustomizer(...)` 로 같은 값을 준다.

### 2.4 CLI

```yaml
cli:
  sessionLogWriteFormat: v2   # v2(기본) | v1 — CLI 설정의 다른 키처럼 camelCase
```

CLI 는 세션을 메모리에 두므로 §2 의 `v1` 고정이 필요 없다. `v2` 면 CLI 는 in-memory 세그먼트
저장소를 함께 붙여, 압축이 가린 구간을 레코드 밖으로 봉인하고 `SessionHistory` 가 그것을 다시 읽는다. `context-engine` 은
AGENT.md 에서 고른다(§2.2).

## 3. `rolling` 이 하는 일

```
뷰:  [ head ][ 경계 요약 ][ 원문 ........ ][ tail ...... ]
```

- **언제** — 기본 engine 과 같은 auto-compact 임계값(`autoCompactRatio × effective window`, 기본 0.8)에서 압축한다.
  예산이 강제한 패스(budget-forced)는 그보다
  `warningBuffer` 만큼 낮은 선에서 한다. 모든 크기는 시스템 프롬프트를 포함한다
- **무엇을 먼저** — 흡수될 구간의 큰 도구 결과(기본 500 토큰 이상)를 `[tool result elided: seq=N]` 으로 가리는 것부터 해 본다.
  그것만으로 임계값 아래로 내려가면 요약하지 않는다
- **어디서 자르나** — tail 예산(창의 20%) → 그 절반 → 마지막 합법 절단면 순으로 물러나며, 예상 크기가 임계값 아래인 첫 자리를
  고른다. 최선으로도 내려가지 못하면 압축하지 않고 경고만 남긴다. blocking 한계에서만 head 까지 요약에 넣는다
- **모델이 아직 답하지 않은 것은 건드리지 않는다** — 마지막 assistant 메시지 뒤(방금 부른 도구의 결과, 또는 답 뒤에 들어온
  입력)는 가리지도 요약에 넣지도 않는다. 도구 결과 하나가 tail 예산보다 커도 모델이 읽기 전에는 원문 그대로 간다. 그것만으로
  임계값을 넘으면 경고만 남기고, blocking 한계에서는 그 앞을 head 까지 모두 요약한 뒤 넘더라도 그대로 보낸다 — 그때 결정의
  사유(`ContextDecision.getReason()`)에 `RollingContextEngine.STILL_OVER_BLOCKING` 문구가 붙고, 실행 결과의 그 압축 기록은
  `CompactionMetadata.isOverBlockingLimit()` 가 참이다. 더 흡수할 것이 없으면 실행을 멈춘다(`ContextWindowExceededException`)
- **요약** — 이전 요약 + 새로 흡수되는 원문으로 **갱신**한다. `Primary Request and Intent` · `Key decisions and constraints` ·
  `Pending Tasks` 는 누적 섹션이다
- **물러나기** — 창이 작거나 시스템 프롬프트가 커서 압축 직후에도 warning 선 아래로 내려갈 수 없는 모델이면, 그 호출은 `default`
  engine 으로 처리하고 모델마다 한 번 WARN 을 남긴다. 버전 1 로그도 마찬가지다

`/compact` 는 임계값 판정만 건너뛰고 같은 방식으로 자른다(`default` engine 도 버전 2 로그에서는 아래와 같다). 같은 세션을 다른 압축이 처리하는 중이면 기다리지 않고 실패를 보여 준다.
미응답 부분도 자동 압축과 똑같이 건드리지 않는다 — 턴이 중단되어 뷰가 답을 받지 못한 사용자 메시지로 끝나면 `/compact` 는 그 앞까지만
요약하고, 그 메시지는 원문으로 남는다. 뷰가 미응답 입력뿐이면 요약할 것이 없다는 실패를 보여 준다.

압축 뒤 최근 파일·스킬 목록을 다시 붙이는 복원 훅(`RecentFilesRestoreHook`, `InvokedSkillsRestoreHook`)은 등록하지 않는다 —
tail 이 이미 원문이고, 붙인 파일이 tail 에 쌓여 다음 압축을 앞당긴다.

## 4. 조정

`RollingContextEngine.Builder` 의 값이다. 조립 방식마다 넣는 자리가 다르다. 압축을 **언제** 시작할지는 여기 없다 — 두
엔진이 같은 시작점을 쓰고, 에이전트마다 AGENT.md `context.auto-compact-ratio`(기본 0.8, §2.2)로 정한다. 예전의
`aimon.context.rolling.auto-compact-ratio` 를 적으면 스타터가 기동을 거부한다.

- **Spring Boot 스타터** — `aimon.context.rolling.*` 프로퍼티(아래 표의 kebab 이름). `summaryModel` 은 프로퍼티가 없다
- **`AimonStackSpec`** — `ExecutorSpec.builder().rollingContextEngineCustomizer(builder -> ...)`
- **`OrcaAgentRuntimeFactory`** — `withRollingContextEngineCustomizer(builder -> ...)`

customizer 는 rolling 을 쓰는 런타임마다(배포 기본값으로 골랐든 AGENT.md 로 골랐든) 새 빌더에 한 번 적용된다. 협력 객체
(`compactionEngine` · `tokenEstimator` · `writeFormat` 등)는 customizer **다음에** 팩토리가 넣으므로, customizer 에서 바꿔도
효과가 없다. 범위를 벗어난 값은 런타임을 만들 때 실패한다 — 선언된 에이전트라면 기동 시점이다. 스타터는 비율을 기동 시점에
따로 검사하므로, 지금 rolling 을 쓰는 에이전트가 없어도 잘못된 값은 거부된다.

| 값 | 스타터 프로퍼티 | 기본 | 뜻 |
|----|----------------|------|----|
| `headTokenRatio` | `head-token-ratio` | 0.05 | head 의 대화 부분 상한. 넘으면 head 는 비고 요약이 맡는다 |
| `tailTokenRatio` | `tail-token-ratio` | 0.20 | 원문으로 남길 tail 예산 |
| `summaryTokenRatio` | `summary-token-ratio` | 0.08 | 요약에 요구하는 길이 |
| `minTailRatio` | `min-tail-ratio` | 0.05 | "롤링을 감당하는가" 판정에 쓰는 최소 tail |
| `pruneMinTokens` | `prune-min-tokens` | 500 | 가릴 만한 도구 결과의 최소 크기 |
| `summaryModel` | — | 호출 모델 | 요약 전용 모델. 같은 프로바이더여야 한다 |
| `maxConsecutiveFailures` | — | 3 | 요약이 연달아 이만큼 실패하면 그 세션의 임계값 압축을 멈춘다. blocking 한계의 압축은 계속한다 |

비율은 모두 (0, 1] 범위이고 `pruneMinTokens` 와 `maxConsecutiveFailures` 는 1 이상이다.

압축 결과의 `CompactionMetadata` 에는 `kind`(`PRUNE`/`ROLLING`/`FULL`/`FALLBACK`), head·span·tail 토큰, 요약 토큰, 흡수한 seq
범위, 판정 기준이 된 blocking 한계(`getBlockingLimit()`)가 실린다 — 튜닝은 이 값을 보고 한다.

## 5. `SessionHistory` 도구

`rolling` 을 배선하면 등록된다. 에이전트는 이것으로 뷰에서 빠진 원문을 되찾는다.

| 입력 | 뜻 |
|------|----|
| `seq` | 그 메시지 원문 — `[tool result elided: seq=N]` 의 N |
| `query` | 대소문자를 무시한 부분 문자열 검색, 최근 것부터 |
| `limit` | 검색 결과 수 (기본 5, 최대 20) |
| `offset` | `seq` 와 함께: 그 메시지를 읽기 시작할 글자 위치 (기본 0) |

- 범위는 **현재 세션의 대화 항목**뿐이다 — 런타임이 넣은 `SYNTHETIC` 항목, `/clear` 이전, 다른 세션은 보이지 않는다
- 봉인된 구간도 읽는다. 세그먼트를 읽을 수 없으면 그 구간을 "읽을 수 없음" 으로 한 번 알린다
- 검색은 최근부터 100만 토큰까지만 훑고, 거기서 멈추면 그 사실을 결과에 적는다
- 결과는 일치한 메시지 앞뒤 두 개씩, 메시지마다 2000자로 자른다. 잘린 자리에는 남은 글자 수와 다음 부분의
  `offset` 이 적혀 있어, `seq` 와 그 `offset` 으로 긴 원문을 끝까지 읽을 수 있다
- 검색 결과 전체도 2만 자(메시지 상한의 10배)에서 멈추고, 보이지 않은 일치가 남았다고 적는다

## 관련 문서

- [`context-engine.md`](../../design/agent-execution/context-engine.md) — engine 의 설계와 결정 근거
- [`session-log.md`](../../design/session/session-log.md) — append-only 로그, 뷰 상태, 봉인, 쓰기 형식 이행
- [`compaction.md`](../../design/agent-execution/compaction.md) — 요약을 만드는 부품
- [임베딩 가이드 §4](../../getting-started/embedding-agent-in-application.md) — 스타터 프로퍼티 전체
