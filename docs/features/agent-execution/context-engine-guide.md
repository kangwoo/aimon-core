# Context Engine 가이드 — 긴 대화의 컨텍스트 줄이기

에이전트의 대화가 모델 창보다 길어질 때 **LLM 에 무엇을 보낼지** 정하는 자리가 `ContextEngine` 이다. 이 문서는 두 engine
중 무엇을 고르고 어떻게 켜는지를 다룬다. 설계 근거는
[`context-engine.md`](../../design/agent-execution/context-engine.md) 와
[`session-log.md`](../../design/session/session-log.md) 에 있다.

---

## 1. 두 engine

| engine | 무엇을 하나 | 언제 고르나 |
|--------|------------|------------|
| `default` (기본) | 모델의 auto-compact 임계값에서 **뷰 전체**를 요약 하나로 바꾼다. 지금까지의 동작 그대로다 | 대화가 대개 창 안에서 끝난다 |
| `rolling` | **head**(세션의 첫 요청)와 **tail**(최근 대화)을 원문으로 두고 가운데만 요약한다. 더 일찍, 더 작게 압축하고 요약은 매번 **갱신**한다. `SessionHistory` 도구를 등록한다 | 한 세션이 창의 몇 배로 길어진다 — 운영 대화, 긴 조사 |

두 engine 모두 **로그는 건드리지 않는다**(버전 2 쓰기 형식에서). 압축은 뷰 상태에 "어느 구간을 어떤 요약으로 대체했는가" 를
적을 뿐이고, 원문은 레코드(또는 봉인된 세그먼트)에 그대로 남는다. 그래서 메모리 ingest 는 요약문이 아니라 원문을 받는다.

## 2. 켜기

`rolling` 은 **버전 2 로그 쓰기 형식**을 요구한다 — 요약 구간을 뷰 상태에 적어야 하는데 버전 1 레코드는 그것을 담지 못한다.
버전 1 쓰기 노드에서 `rolling` 을 고르면 **기동이 실패한다.** CLI(`aimon-cli`)도 기본은 `v1` 이므로, AGENT.md 에
`context-engine: rolling` 을 적은 에이전트를 CLI 로 띄우려면 `cli.sessionLogWriteFormat: v2` 를 준다(§2.4).

IMPORTANT: 쓰기 형식은 클러스터 전체가 두 단계로 바꾼다. 먼저 모든 노드를 버전 2 를 **읽을 수 있는** 빌드로 배포하고(쓰기는
여전히 `v1`), 그 배포가 끝난 뒤에 `v2` 로 바꾼다. 한번 버전 2 로 쓰인 레코드는 버전 1 쓰기 노드도 버전 2 로 다시 쓴다.

### 2.1 Spring Boot 스타터

```yaml
aimon:
  session:
    log-write-format: v2    # v1(기본) | v2
  context:
    engine: rolling         # default(기본) | rolling — 에이전트가 따로 적지 않았을 때의 값
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

### 2.3 Spring 이 아닌 조립 — `AimonStackSpec`

```java
AimonStackSpec.builder()
        .session(SessionSpec.builder().logWriteFormat(SessionLogFormat.V2).build())
        .executor(ExecutorSpec.builder().contextEngine(ContextEngineKind.ROLLING).build())
        // ...
        .build();
```

`OrcaAgentRuntimeFactory` 를 직접 쓰는 조립은 `withSessionLogWriteFormat(...)` · `withContextEngine(...)` 로 같은 값을 준다.
엔진 자체를 만들어 넣으려면 `RollingContextEngine.builder()` 로 비율을 바꿀 수 있다(§4).

### 2.4 CLI

```yaml
cli:
  sessionLogWriteFormat: v2   # v1(기본) | v2 — CLI 설정의 다른 키처럼 camelCase
```

CLI 는 세션을 메모리에 두므로 §2 의 두 단계 배포가 필요 없다 — 이 값만 바꾸면 된다. `v2` 면 CLI 는 in-memory 세그먼트
저장소를 함께 붙여, 압축이 가린 구간을 레코드 밖으로 봉인하고 `SessionHistory` 가 그것을 다시 읽는다. `context-engine` 은
AGENT.md 에서 고른다(§2.2).

## 3. `rolling` 이 하는 일

```
뷰:  [ head ][ 경계 요약 ][ 원문 ........ ][ tail ...... ]
```

- **언제** — `min(0.6 × effective window, auto-compact 임계값)` 에서 압축한다. 예산이 강제한 패스(budget-forced)는 그보다
  `warningBuffer` 만큼 낮은 선에서 한다. 모든 크기는 시스템 프롬프트를 포함한다
- **무엇을 먼저** — 흡수될 구간의 큰 도구 결과(기본 500 토큰 이상)를 `[tool result elided: seq=N]` 으로 가리는 것부터 해 본다.
  그것만으로 임계값 아래로 내려가면 요약하지 않는다
- **어디서 자르나** — tail 예산(창의 20%) → 그 절반 → 마지막 합법 절단면 순으로 물러나며, 예상 크기가 임계값 아래인 첫 자리를
  고른다. 최선으로도 내려가지 못하면 압축하지 않고 경고만 남긴다. blocking 한계에서만 head 까지 요약에 넣는다
- **요약** — 이전 요약 + 새로 흡수되는 원문으로 **갱신**한다. `Primary Request and Intent` · `Key decisions and constraints` ·
  `Pending Tasks` 는 누적 섹션이다
- **물러나기** — 창이 작거나 시스템 프롬프트가 커서 압축 직후에도 warning 선 아래로 내려갈 수 없는 모델이면, 그 호출은 `default`
  engine 으로 처리하고 모델마다 한 번 WARN 을 남긴다. 버전 1 로그도 마찬가지다

`/compact` 는 임계값 판정만 건너뛰고 같은 방식으로 자른다. 같은 세션을 다른 압축이 처리하는 중이면 기다리지 않고 실패를 보여 준다.

압축 뒤 최근 파일·스킬 목록을 다시 붙이는 복원 훅(`RecentFilesRestoreHook`, `InvokedSkillsRestoreHook`)은 등록하지 않는다 —
tail 이 이미 원문이고, 붙인 파일이 tail 에 쌓여 다음 압축을 앞당긴다.

## 4. 조정

`RollingContextEngine.Builder` 의 값이다. 스타터 프로퍼티로는 노출하지 않는다.

| 값 | 기본 | 뜻 |
|----|------|----|
| `autoCompactRatio` | 0.6 | 압축 시작점, effective window 대비 |
| `headTokenRatio` | 0.05 | head 의 대화 부분 상한. 넘으면 head 는 비고 요약이 맡는다 |
| `tailTokenRatio` | 0.20 | 원문으로 남길 tail 예산 |
| `summaryTokenRatio` | 0.08 | 요약에 요구하는 길이 |
| `minTailRatio` | 0.05 | "롤링을 감당하는가" 판정에 쓰는 최소 tail |
| `pruneMinTokens` | 500 | 가릴 만한 도구 결과의 최소 크기 |
| `summaryModel` | 호출 모델 | 요약 전용 모델. 같은 프로바이더여야 한다 |

압축 결과의 `CompactionMetadata` 에는 `kind`(`PRUNE`/`ROLLING`/`FULL`/`FALLBACK`), head·span·tail 토큰, 요약 토큰, 흡수한 seq
범위가 실린다 — 튜닝은 이 값을 보고 한다.

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

## 관련 문서

- [`context-engine.md`](../../design/agent-execution/context-engine.md) — engine 의 설계와 결정 근거
- [`session-log.md`](../../design/session/session-log.md) — append-only 로그, 뷰 상태, 봉인, 쓰기 형식 이행
- [`compaction.md`](../../design/agent-execution/compaction.md) — 요약을 만드는 부품
- [임베딩 가이드 §4](../../getting-started/embedding-agent-in-application.md) — 스타터 프로퍼티 전체
