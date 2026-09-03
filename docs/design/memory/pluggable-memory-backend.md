# 교체 가능한 메모리 백엔드 (Pluggable Memory Backend)

> Status: **PARTIALLY IMPLEMENTED** — §12 의 **Step 1~6 구현됨**. 다섯 티어 SPI · `PeerMemory` ·
> `StoreBackedPeerMemory` · 능력 기반 도구 등록 · `RedactingPeerMemory` · CLI 이주 · 수집 이음매 ·
> `aimon-memory-testkit` 이 트리에 있다. **Step 7~9(Dyad·Honcho 어댑터와 원격 설정 표면)는 미구현**이며
> §15 의 1·2번(두 서버를 띄워 확인하지 않았다)이 그대로 열려 있는 것이 그 이유다.
> §7.2 의 수집 델타는 **(a) 로 확정**되었고 §15 의 6·8번(teardown 이동 안전성, 수집 델타)은
> 해소되었다.
> 현재 구현(`at.aimon.core.memory` + `aimon-memory-{file,mongodb,postgres}`)의
> 설계 사양은 [`peer-memory.md`](peer-memory.md) 이며, 본 문서는 그 사양의 **대부분을 유지하되 그 문서의
> 비목표 한 줄을 철회한다** — 아래 §0.1. 사용자 노출 표면은
> [메모리 사용 가이드](../../features/memory/memory-usage-guide.md) 참조.

---

## 0. 결론 먼저

1. **가능하다. 단, 지금의 확장점으로는 불가능하다.** 현재 seam 은 `ObservationStore` /
   `RepresentationStore` / `WorkspaceStore` 세 **저장소** 인터페이스인데, 바꿔치기 대상인 Honcho 와
   Dyad 는 저장소를 노출하지 않고 **서비스**(`context` / `recall` / `chat` / `messages`)를 노출한다.
   저장소 고도에서는 `RepresentationStore.save`, `ObservationStore.merge`,
   `findByConfidenceBelow`, `purgeSoftDeletedBefore` 처럼 **원격 대응물이 아예 없는 메서드**가 남는다(§1.3).

2. **그러므로 이 설계의 본체는 서비스 고도의 SPI 를 새로 긋는 것이다.** 다섯 개의 능력(capability)
   — `SNAPSHOT` · `SEARCH` · `CHAT` · `OBSERVE` · `INGEST` — 을 각각 별도 인터페이스로 두고,
   `PeerMemory` 파사드가 **자기가 가진 티어만** 내놓는다. 능력 집합은 백엔드가 선언하는 것이 아니라
   **티어 접근자에서 계산**되며 그것을 담는 메서드가 인터페이스에 아예 없으므로, "CHAT 을 지원한다고
   말해 놓고 빈 응답을 주는" 상태는 표현할 자리가 없다(§3.2).
   이 불변식이 잡지 못하는 것이 둘 있고 문서가 둘 다 따로 처분한다 — 티어 *안*의 손실(원격 스냅샷에
   개별 관찰이 없는 것, 점수를 못 내는 것)은 `observationsAvailable` 류의 명시 신호로(§3.3), 재료는
   있는데 그 연산을 못 하는 스토어는 §4.1 의 계약과 §8.1 의 testkit 으로 막는다(§3.2).
   다섯 중 **둘은 이미 있다** — `MemoryContextProvider` 와 `DialecticEngine` 은 이미 서비스 고도다.
   즉 고도 문제는 국소적이며, 틀어진 자리는 **검색·관찰·수집 세 곳**뿐이다.

3. **기존 저장소 SPI 는 살아남고, 백엔드 모듈 셋도 그대로 산다.** 격하는 패키지 이동이 아니라
   **참조 방향**으로 표현한다 — 새 SPI 의 어떤 시그니처도 `ObservationStore` / `RepresentationStore` 를
   언급하지 않는다. 그 셋은 `StoreBackedPeerMemory`(기본 백엔드)의 **내부 재료**가 된다(§4).
   패키지를 `at.aimon.core.memory.impl` 로 옮기면 `aimon-memory-*` 세 모듈과 CLI 가 **전부 컴파일
   불가**가 되므로 하지 않는다 — 근거는 §4.2.

가장 큰 미해결 항목은 SPI 가 아니라 **수집 경로**다. 오늘 AIMON 에는 대화를 메모리로 흘려보내는 이음매가
사실상 없다 — CLI 는 REPL 프로세스가 **종료될 때 딱 한 번** 전사 전체를 큐에 넣고, 스타터는
`memory-write-path` degradation 을 올린 채 아무것도 넣지 않는다. Honcho·Dyad 는 둘 다 "메시지를 계속
먹여야 결론이 쌓이는" 모델이므로, 이 이음매 없이는 어댑터를 붙여도 **영원히 빈 메모리**를 읽는다(§7).

두 번째 미해결 항목은 **CLI 다.** 오늘 다섯 티어를 전부 배선해서 도는 유일한 조립 경로인데, 그 경로는
`MemorySpec` 도 `MemoryAssembly` 도 지나지 않는다 — 이 설계의 교체 기계가 CLI 에 닿지 않는다는 뜻이다.
그래서 CLI 이주가 설정 표면 작업의 곁다리가 아니라 **독립 단계**(§12 Step 4)로 서 있다. 사실 확인은 §5.0.

### 0.1 `peer-memory.md` 의 비목표 한 줄을 **철회한다**

IMPORTANT: 이 설계는 기존 정본과 **조용히 모순되지 않기 위해** 그 모순을 여기서 먼저 적는다.
[`peer-memory.md`](peer-memory.md) §1 은 두 줄의 비목표를 선언한다.

| `peer-memory.md` §1 의 비목표 | 이 문서의 처분 |
|---|---|
| "외부 메모리 서버를 호출하는 **원격 클라이언트 통합** (MCP 경로는 별도 트랙)" | **철회한다.** `aimon-memory-honcho` / `aimon-memory-dyad` 가 정확히 그것이다(§8.1). 괄호 안의 단서 — MCP 는 별도 트랙 — 는 유지된다(§14 A2) |
| "외부 SDK / 스키마 호환" | **유지한다.** AIMON 은 Honcho 의 와이어 포맷을 흉내 내지 않고 그 SDK 를 재수출하지도 않는다(§13). §2.2 의 `ObservationType` 4값 확장은 호환이 아니라 **어휘 손실을 없애기 위한 자체 도메인 확장**이다 — 값 이름이 겹치는 것은 두 시스템이 같은 개념을 부르는 이름이 같기 때문이지 스키마를 맞추기 위해서가 아니다 |

철회는 문장으로만 하지 않는다. **`peer-memory.md` §1 의 그 줄을 §12 Step 1 과 같은 커밋에서 고치고**,
그 자리에 이 문서로의 링크를 남긴다. Step 1 의 인수 조건이다.

### 0.2 `peer-memory.md` §10.3 은 **지금 틀려 있다** — 이 문서가 그것을 부정한다

같은 규율을 한 번 더 적용한다. 정본의 `### 10.3 CLI — memory yaml 블록`(`peer-memory.md:772-795`)에
두 가지 오류가 있고, 이 문서 §9.2 가 둘 다 반대로 적는다.

| 정본이 말하는 것 | 실제 | 이 문서 |
|---|---|---|
| yaml 예제가 `workspace-id` · `peer-id` · `peer-name` · `storage-path` · `reconciler-enabled` (kebab) | `MemoryConfig` 의 `@JsonProperty` 가 전부 camelCase. **그대로 따라 쓰면 `ConfigurationException` 으로 부팅이 실패한다**(§9.2 의 실측) | §9.2 가 camelCase 로 적는다 |
| *"모르는 값은 시작 경고와 함께 `file` 로 떨어진다"* | 경고는 **관찰 스토어에서만** 나온다. `createRepresentationStore:929-943` 은 말없이 폴백한다 | §9.2 가 그 절반을 짚는다 |

IMPORTANT: 이 수정은 **Step 1 로 당긴다** — 지금 틀린 정본이 읽는 사람을 부팅 실패로 보내고 있고,
이 수정은 다른 어떤 단계에도 의존하지 않는다. 사용 가이드
([`memory-usage-guide.md:71`](../../features/memory/memory-usage-guide.md))는 camelCase 로 **맞게**
적혀 있으므로 고칠 것은 설계 정본 한 곳뿐이다.

---

## 목차

- [1. 문제 — 확장 seam 의 고도가 틀렸다](#1-문제--확장-seam-의-고도가-틀렸다)
- [2. 가능한가 — 대상별 기능 대조](#2-가능한가--대상별-기능-대조)
- [3. 새 SPI — 서비스 고도의 다섯 티어](#3-새-spi--서비스-고도의-다섯-티어)
- [4. 기존 저장소 SPI 는 어떻게 되는가](#4-기존-저장소-spi-는-어떻게-되는가)
- [5. 도구 4개의 재배선과 등록 판단](#5-도구-4개의-재배선과-등록-판단)
- [6. 드리머·디라이버·리컨사일러·레닥션](#6-드리머--디라이버--리컨사일러--레닥션)
- [7. 수집(ingest) — 오늘 없는 이음매](#7-수집ingest--오늘-없는-이음매)
- [8. 모듈 배치](#8-모듈-배치)
- [9. 설정 표면](#9-설정-표면)
- [10. 라이선스 경계](#10-라이선스-경계)
- [11. 호환성과 마이그레이션](#11-호환성과-마이그레이션)
- [12. 단계별 구현 계획](#12-단계별-구현-계획)
- [13. 비목표](#13-비목표)
- [14. 기각한 대안](#14-기각한-대안)
- [15. 아직 확인되지 않은 것](#15-아직-확인되지-않은-것)

---

## 1. 문제 — 확장 seam 의 고도가 틀렸다

### 1.1 지금 무엇을 바꿔 끼우게 되어 있는가

`MemorySpec` 은 **재료로 스토어를 받는다**. 조립 계층의 신호는 명확하다 —
`MemorySpec.Builder.representationStore(...)` / `.observationStore(...)` 두 개가 확장점이고,
`MemoryAssembly` 가 그 둘로부터 주입 provider·툴 컨텍스트 enricher·툴 provider 를 만든다.

```
MemorySpec(observationStore, representationStore)
   └─ MemoryAssembly
        ├─ RepresentationMemoryContextProvider(representationStore, …)   ← 프롬프트 주입
        ├─ MemoryToolContextEnricher(workspace, observer)                ← 툴 컨텍스트
        └─ OrcaMemoryToolProvider(representationStore, observationStore,
                                  redactionPolicy)                       ← 도구 3종
```

그러므로 "메모리를 다른 구현으로 바꾼다" 는 현재 **세 저장소 인터페이스를 구현한다**는 뜻이다. 실제로
`aimon-memory-file` / `-mongodb` / `-postgres` 셋이 정확히 그것을 한다.

### 1.2 바꿔치기 대상은 저장소가 아니다

| 대상 | 실제 노출 표면 | 저장소를 노출하는가 |
|---|---|---|
| **현재 구현** (in-tree) | `ObservationStore` / `RepresentationStore` / `WorkspaceStore` + reasoning 컴포넌트 | 그렇다 |
| **Honcho** v3.1.0 | AGPL-3.0 **HTTP 서비스**. `/peers/{p}/context`, `/peers/{p}/chat`, `/conclusions/query`, `/sessions/{s}/messages` | **아니다** |
| **Dyad** (`/Users/kangwoo/Workspaces/research/memory/dyad`) | 독립 **Spring Boot HTTP 서비스** (Postgres + pgvector). Tier 0 `context()` / Tier 1 `recall()` / Tier 2 `chat()` | **아니다** |

두 원격 대상은 **자기 저장소를 자기가 소유한다**. Honcho 는 Postgres + Redis 를, Dyad 는 Postgres +
pgvector 를 자기 프로세스 뒤에 감춘다. HTTP 밖으로 나오는 것은 결론(conclusion)과 렌더된 표현
문자열뿐이다.

### 1.3 저장소 고도에서 어댑터를 쓰면 정확히 무엇이 막히는가

`ObservationStore` 11개 메서드를 HTTP 어댑터로 구현한다고 가정하면 이렇게 갈린다.

| 메서드 | Honcho | Dyad | 왜 |
|---|---|---|---|
| `save(Observation)` | △ | △ | `POST /conclusions` 가 있지만 `confidence` 필드가 없다 — 값이 버려진다 |
| `findById(ObservationId)` | ✗ | ✗ | 단건 조회 엔드포인트가 없다 (Dyad 는 `/conclusions/{id}/chain` 으로 우회 가능) |
| `findBySubject(PeerView, int)` | ○ | ○ | 목록 API 있음 |
| `count(PeerView)` | △ | ○ | Honcho 는 `Page.total` 로 근사 |
| `semanticSearch(...)` | ○ | ○ | 유일하게 깨끗하게 대응되는 메서드 |
| `findSubjects(Workspace, int)` | ○ | ○ | peer 목록 API |
| `findByConfidenceBelow(...)` | **✗** | **✗** | 원격은 confidence 로 필터하지 않는다. Dreamer 전용 질의 |
| `delete` / `softDelete` | ○ | ○ | `DELETE /conclusions/{id}` |
| `purgeSoftDeletedBefore(...)` | **✗** | **✗** | 감사 윈도 purge 는 서버 내부 사이클(Reconciler)이 한다 |
| `merge(winner, loser, merged)` | **✗** | **✗** | 병합은 서버의 3단 중복 제거가 한다. 외부에서 지시할 API 가 없다 |

`RepresentationStore` 는 더 나쁘다.

| 메서드 | Honcho | Dyad |
|---|---|---|
| `save(Representation)` | **✗** | **✗** — 표현은 저장하는 것이 아니라 **읽을 때 계산되는 것**이다 |
| `findLatestGlobal(subject)` | ○ (`/peers/{p}/context`) | △ (`/peer-card`) |
| `findLatestLocal(subject, observer, sessionId)` | ○ (`/sessions/{s}/context`) | ○ (`/sessions/{s}/context`) |
| `deleteOlderThan(...)` | **✗** | **✗** |

그리고 반환 타입 자체가 맞지 않는다. `Representation` 은 `List<Observation>` + `summary` +
`tokenCount` 를 든 **구조화 애그리게이트**인데, 두 서비스가 주는 것은 **렌더된 문자열 한 덩어리**다
(Honcho 의 `peer_representation`, Dyad 의 `ContextResponse.representation`). 어댑터가
`Representation` 을 만들려면 그 문자열을 **되파싱해서 없는 구조를 지어내야 한다.**

IMPORTANT: 위 표의 `✗` 는 "구현하기 어렵다" 가 아니라 **"구현할 방법이 없다"** 이다.

그리고 여기서 논거를 정확히 골라야 한다 — **"`UnsupportedOperationException` 을 던지면 안 된다" 는 이
인터페이스에 대해서는 참이 아니다.** `MongoObservationStore:126-136` 과 `PostgresObservationStore:229-244`
는 `semanticSearch` 자리에서 **항상** 그 예외를 던지고, 그것은 실수가 아니라 설계다 —
`IndexedObservationStore` 의 클래스 javadoc(`:14-33`)이 *"Some metadata stores — notably
`PostgresObservationStore` — deliberately do not implement `semanticSearch` and throw
`UnsupportedOperationException` instead. Wrapping such a store here restores search"* 라고 그 패턴을
명시한다. 즉 이 인터페이스에는 **예외를 두고 데코레이터로 복원하는 규범이 이미 있다.**

차이는 **복원 가능성**이다.

| 자리 | 예외를 둘 수 있는가 | 왜 |
|---|---|---|
| `semanticSearch` | **있다** (기존 규범) | 복원할 대상(`ObservationIndex`)이 코어에 있고 `IndexedObservationStore` 가 그것을 끼운다 |
| `merge` · `findByConfidenceBelow` · `purgeSoftDeletedBefore` · `RepresentationStore.save` | **없다** | 복원할 대상이 **원격에 존재하지 않는다.** 데코레이터를 아무리 겹쳐도 없는 API 를 만들어 내지 못한다 |

아래 칸의 예외는 `IndexedObservationStore` 로 흡수되지 않고 Dreamer·Reconciler 의 호출 지점에서
**런타임에** 터진다. 그것이 저장소 고도를 쓸 수 없는 이유이며, LSP 위반이라는 일반론이 아니다.

---

## 2. 가능한가 — 대상별 기능 대조

**답: 서비스 고도로 seam 을 옮기면 세 대상 모두 가능하다.** 다만 무엇이 되고 무엇이 안 되는지는
티어마다 다르다. 아래 두 표가 이 문서의 사실 근거다.

### 2.1 티어별 대응표

범례: **✓** 그대로 된다 · **△** 되지만 손실이 있다 · **✗** 안 된다

| 능력 | 현재 구현 (기본) | Honcho v3.1.0 | Dyad |
|---|---|---|---|
| **SNAPSHOT** (프롬프트 주입 / recall) | ✓ `RepresentationStore` — 구조화 `Representation` + `tokenCount` | △ `GET /v3/…/peers/{p}/context` · `GET /v3/…/sessions/{s}/context` — 렌더 문자열만 | △ `GET /v1/…/sessions/{s}/context` — 렌더 문자열만, **세션 필수** |
| ├ 세션 없는 실행(포크) | ✓ GLOBAL 폴백 | ✓ peer 스코프 context 엔드포인트가 따로 있다 | △ `GET /v1/…/peer-card` 로 대체 — 요약 라인만 |
| ├ 토큰 예산 | ✓ `tokenCount` 로 정확 계산 | △ `tokens` 파라미터 있음, 응답에 총량 없음 | ✓ `tokens` + 응답에 `summaryTokens`/`messageTokens` |
| └ 구조화 observation | ✓ | ✗ | ✗ |
| └ "못 준다" 를 말할 수단 | — | `MemorySnapshot.observationsAvailable` (§3.3) | 같음 |
| **SEARCH** | ✓ `semanticSearch` (KnowledgeStore 위임) | ✓ `POST /v3/…/conclusions/query` (`query`,`top_k`,`distance`) | ✓ `POST /v1/…/recall` — **6신호 융합 + `explain`** |
| ├ 점수 | △ 순위만 — `ranksByScore()=false`(§3.3) | △ distance → score 변환 | ✓ [0,1] 고정 가중 융합 점수 |
| └ 근거 설명 | ✗ | ✗ | ✓ `explain{sem,kw,ent,reinf,rec,lvl}` |
| **CHAT** (dialectic) | ✓ `LlmDialecticEngine` | ✓ `POST /v3/…/peers/{p}/chat`, 추론 레벨 5단 | ✓ `POST /v1/…/chat`, 추론 레벨 5단 |
| ├ 스트리밍 | △ `default` 구현이 **답변 전체를 한 청크로** 흘린다 (진짜 스트리밍 아님) | ✓ SSE | ✓ SSE (`/chat/stream`) |
| ├ `observationsConsidered` | ✓ | ✗ 응답이 `{content}` 뿐 | ✗ (`toolCalls` 는 준다) |
| └ `TokenUsage` | ✓ | ✗ | ✗ |
| **OBSERVE** (사실 직접 등록) | ✓ `ObservationStore.save`, confidence 포함 | △ `POST /v3/…/conclusions` — **confidence 필드 없음** | △ `POST /v1/…/conclusions` — confidence·level 필드 없음(서버가 explicit 로 고정) |
| **INGEST** (대화 수집) | △ 구현은 있으나 **CLI 프로세스 종료 시 1회만** 배선, 스타터는 미배선 | ✓ `POST /v3/…/sessions/{s}/messages` (배치 ≤100) | ✓ `POST /v1/…/sessions/{s}/messages` |
| └ read-your-writes | ✗ 큐 비동기 | ✗ 최대 30분 (`DERIVER_FLUSH_ENABLED` 는 전역 스위치라 켜면 배치 이점을 통째로 잃는다) | ✓ **`?wait=derive`** — 요청 단위 동기화 |
| **프로버넌스** (결론 → 전제 → 원문) | ✗ (`sourceMessageIds` 자체는 여러 곳이 읽는다 — `DeriverMessageLinkTool:98-112`, `RandomWalkDreamer:300-302`, `DefaultReconciler:251-252` — 그러나 그 id 로 **원문 메시지를 되짚는 경로**가 없다) | ✗ REST 로는 없음 (dialectic 내부 도구 `get_reasoning_chain` 전용) | ✓ `GET /v1/…/recall/provenance`, `GET …/conclusions/{id}/chain` |
| **감사 로그** | △ soft-delete 윈도만 | ✗ | ✓ `GET …/conclusions/{id}/events` |
| **Dreamer 소유자** | AIMON (`DefaultDreamerEngine` + Quartz) | 서버 (`POST /v3/…/schedule_dream`) | 서버 (`POST /v1/…/dreams`) |
| **레닥션** | ✓ 큐 진입 직전 강제 게이트 | ✗ 서버에 없음 → **클라이언트가 해야 한다** | ✗ 서버에 없음 → **클라이언트가 해야 한다** |
| **멀티 인스턴스** | △ Postgres 백엔드에서만 | ✓ 서버가 책임 | ✓ 서버가 책임 |
| **워크스페이스 격리** | ✓ 컴파일 타임(값 객체 강제) | ✓ 서버 (JWT 스코프) | ✓ 서버 (JWT pair 스코프) |

### 2.2 도메인 모델의 손실 — 무엇을 넓혀야 하는가

| AIMON | Honcho | Dyad | 판정 |
|---|---|---|---|
| `ObservationType` = `{EXPLICIT, DEDUCTIVE}` | `level` = `{explicit, deductive, inductive, contradiction}` | 같음 (`ConclusionLevel`) | **넓혀야 한다.** 지금 매핑하면 원격 결론의 절반이 `DEDUCTIVE` 로 뭉개진다 |
| `Observation.confidence` (primitive `double`, 필수) | 없음 | `confidence` (nullable, inductive 전용) | 어댑터가 `type.baseConfidence()` 로 **자리 채움**한다. 순위는 `MemoryHit` **리스트 순서**가 정본이고 confidence 는 순위 신호가 아니다(§3.3) — 그런데 이 자리 채움은 모델에게 보이므로 신호가 필요하다(아래) |
| `Observation.sourceMessageIds` | `message_ids` + `source_ids`(추론 트리) | `message_ids` + `source_ids`(추론 트리) | 대응됨. `source_ids` 는 **두 대상 모두** 갖고 있고(Honcho `honcho-java-spec.md:228,240`, Dyad `Dtos.java:90` 의 `List<String> sourceIds`) AIMON 에만 대응물이 없다 — §13 비목표 |
| `PeerView(workspace, Principal)` | `(workspace, observer, observed)` | `PairKey(workspace, observer, observed)` | 대응됨. `Principal.Type` 은 원격에 없으므로 peer 이름으로 평탄화된다 |
| `ReasoningLevel` = `{FAST, BALANCED, DEEP}` | 5단 `minimal…max` | 5단 `MINIMAL…MAX` | 3 → 5 매핑은 정보 손실 없음(단사). 역방향은 없음 — 어댑터가 정한다 |
| `Workspace` + `WorkspaceStore` | 서버 소유 (get-or-create) | 서버 소유 | 원격에서 `WorkspaceStore` 는 배선하지 않는다 (§4.3) |

#### confidence 왕복 손실 — §3.3 의 기준을 그대로 적용한다

§3.3 이 `observationsAvailable` 을 도입하며 세운 기준은 **"모델이 보느냐"** 였다. confidence 는
**세 도구 전부에서 모델에게 출력된다.**

| 도구 | 출력 지점 |
|---|---|
| `ObserveTool` | `:202` — `sb.append("confidence: ").append(String.format(…, saved.getConfidence()))` |
| `MemorySearchTool` | `:179` — 히트마다 `(type=…, confidence=…)` |
| `MemoryRecallTool` | `:175` — 관찰마다 `(confidence=…)` |

더 나쁜 것은 `ObserveTool` 이 confidence 를 **입력으로도 받는다**는 것이다(스키마 `:112`, `[0,1]`
범위 검증 `:129-131`, 저장 `:193`). 원격에는 그 자리가 없다 — Dyad 의
`Requests.CreateConclusion(observer, observed, session, content, entities, expiresAt)` 에 confidence
필드가 없고 서버가 `ConclusionLevel.EXPLICIT, null` 로 고정하며(`ConclusionController:108-110`),
Honcho 의 `documents` 테이블에도 confidence 컬럼이 없다. 즉 **모델이 `confidence: 0.3` 을 보내면
서버는 버리고, 도구는 `0.30`(또는 `baseConfidence`)을 되돌려 준다.** 모델은 자기가 기록한 값이 저장된
줄 안다.

IMPORTANT: 이것은 `observationsAvailable` 이 막는 손실보다 **나쁘다.** 스냅샷은 빈 값을 주지만
confidence 는 **그럴듯한 거짓값**을 준다 — §5.2 가 없애겠다고 선언한 "조용히 틀린 답" 그 자체다.
그래서 두 가지를 함께 한다.

1. **신호를 둔다.** 세 도구가 confidence 를 찍으므로 신호도 **셋**이다 — `ObserveTool` 은
   `ObservationRecorder.storesConfidence()`, `MemorySearchTool` 은 `MemoryHit.confidenceAvailable`,
   `MemoryRecallTool` 은 `MemorySnapshot.confidenceAvailable` 을 본다(전부 §3.3).
   `observationsAvailable` 과 같은 기계다.

   셋째는 **오늘은 발현하지 않는 자리**를 막는다. `MemoryRecallTool` 은 스냅샷의 `observations`
   에서만 confidence 를 얻는데 §2.1 이 Honcho·Dyad 둘 다 구조화 observation 을 ✗ 로 적었으므로
   ("관찰은 주면서 confidence 는 가짜" 인 조합이 세 대상 어디에도 없다) 기본 백엔드에서는 언제나
   `true` 다. 그럼에도 두는 이유는 **네 번째 백엔드**가 그 조합을 만들 수 있고, 그때 이 필드가
   없으면 Recall 이 지어낸 숫자를 모델에게 조용히 넘기기 때문이다 — §3.3 이 `observationsAvailable`
   을 도입할 때 쓴 것과 같은 논거다
2. **스키마를 좁힌다.** `storesConfidence()` 가 false 면 `ObserveTool` 이 입력 스키마에서
   `confidence` 파라미터를 **뺀다.** 없는 파라미터는 모델이 보낼 수 없으므로 왕복 손실 자체가
   사라진다. 오늘 `ObserveTool.createInputSchema():103` 은 정적이지만, `AbstractTool` 에
   `ToolDefinitionProvider` 를 받는 생성자가 이미 있어
   ([도구 개발 가이드](../../features/tool/tool-development-guide.md)의 "동적 정의") 런타임에 스키마를
   정하는 것이 규약 안이다. 같은 조건에서 도구 설명에 *"이 백엔드는 confidence 를 저장하지 않는다"* 를
   붙이고, 세 도구의 렌더도 그때는 confidence 를 찍지 않는다

IMPORTANT: 이 결정은 §11.2 의 *"도구 4종의 입력 스키마 = 불변"* 과 정면으로 부딪힌다. **여기가
이긴다** — 스키마를 얼리면 모델이 저장되지 않을 값을 계속 보내게 되고, 그것이 §5.2 가 없애겠다고 한
"조용히 틀린 답" 이다. §11.2 의 그 행은 *"기존 사용자에게는 불변"* 으로 한정했다: 기본 백엔드는
`storesConfidence()=true` 라 오늘 도는 배포의 스키마가 실제로 그대로이기 때문이다.

남는 것은 `type`(level)이다 — Dyad 는 직접 주입을 `EXPLICIT` 로 고정하므로 모델이 고른 `DEDUCTIVE` 도
버려진다. 같은 기계를 `type` 까지 넓힐지는 §15 에 미확인으로 올린다.

`ObservationType` 을 4값으로 넓히는 것은 **기존 코드에 안전하다** — 트리 전체에 이 enum 을 대상으로 하는
exhaustive `switch` 가 하나도 없고, 사용처는 전부 `valueOf` 와 `baseConfidence()` 다. 대신 **다운그레이드가
깨진다**: 새 값이 파일/Mongo/Postgres 에 저장된 뒤 옛 jar 로 되돌리면 `valueOf` 가 던진다. `0.x` 에서
받아들일 만한 비용이며 §11 에 명시한다.

### 2.3 결론 — 대상별 한 줄

| 대상 | 판정 |
|---|---|
| **현재 구현** | 다섯 티어 전부 ✓. 단 INGEST 는 **배선이 없는 것이지 구현이 없는 것이 아니다**(§7) |
| **Dyad** | 다섯 티어 전부 ✓. SEARCH 는 현재 구현보다 **낫다**(융합 점수 + explain). SNAPSHOT 의 세션 없는 경로만 약하다 |
| **Honcho** | 다섯 티어 전부 ✓. SNAPSHOT 의 세션 없는 경로는 **가장 낫다**(peer 스코프 엔드포인트). SEARCH 는 시맨틱 단독 |

---

## 3. 새 SPI — 서비스 고도의 다섯 티어

### 3.1 왜 그 고도인가

seam 은 **양쪽이 같은 말을 할 수 있는 가장 낮은 지점**에 그어야 한다. 저장소 고도는 그 지점이 아니다 —
§1.3 이 보였듯 절반이 번역되지 않는다. 반대로 **한 단계 위**(예: "메모리 전체를 도구 하나로") 로 올리면
프롬프트 자동 주입이 불가능해진다. 주입은 모델이 부르는 것이 아니라 실행기가 매 실행마다 무조건 하는
것이기 때문이다.

그 사이에 정확히 다섯 개의 연산이 있고, 그것이 세 대상이 **모두 이름을 갖고 있는** 연산이다.

| 능력 | 인터페이스 | 상태 | 소비자 | Honcho | Dyad |
|---|---|---|---|---|---|
| `SNAPSHOT` | `MemorySnapshotReader` | **신규** | `MemoryContextProvider` 기본 구현, `MemoryRecallTool` | `…/context` | `…/context`, `…/peer-card` |
| `SEARCH` | `MemorySearcher` | **신규** | `MemorySearchTool` | `…/conclusions/query` | `…/recall` |
| `CHAT` | `DialecticEngine` | **기존 그대로** | `MemoryChatTool` | `…/chat` | `…/chat` |
| `OBSERVE` | `ObservationRecorder` | **신규** | `ObserveTool` | `POST …/conclusions` | `POST …/conclusions` |
| `INGEST` | `MemoryIngestor` | **신규** | 실행기의 실행 종료 이음매 (§7) | `POST …/messages` | `POST …/messages` |

**다섯 중 둘은 이미 서비스 고도에 있다.** 이것이 이 설계에서 가장 중요한 사실이다.

- `MemoryContextProvider.provide(MemoryContextRequest) → Optional<SystemPromptPart>` 는 이미
  "실행 정체성을 받아 프롬프트 조각을 돌려준다" 는 완전한 서비스 계약이다. 저장소를 한 글자도 언급하지
  않으며, peer 해석은 `MemoryPeerResolver` 라는 별도 전략에 이미 위임되어 있다. **원격 어댑터가 이것을
  직접 구현하면 실행기 쪽은 한 줄도 바뀌지 않는다.**
- `DialecticEngine.query(DialecticQuery) → DialecticResponse` 역시 그렇다. `DialecticQuery` 는
  workspace · subject · observer · sessionId · question · level 을 들고 있고, 이 여섯은 Honcho 의
  `DialecticOptions` 와 Dyad 의 `ChatRequest` 에 **하나도 빠짐없이 대응된다**.

즉 고도 문제는 전면적이지 않고 **국소적**이다. 틀어진 자리는 검색·관찰·수집 세 개이며, 여기에 SNAPSHOT
티어를 하나 추가하는 이유는 `MemoryRecallTool` 이 아직 `RepresentationStore` 를 직접 쥐고 있기
때문이다(§5).

### 3.2 파사드와 능력 협상 — `PeerMemory`

```java
package at.aimon.core.memory;

/**
 * 한 배포가 쓰는 메모리 백엔드. application-scoped.
 */
public interface PeerMemory extends ApplicationScoped {

    /** 진단·로그·degradation 문구에 쓰는 백엔드 식별자 ("default" | "honcho" | "dyad"). */
    String backendId();

    Optional<MemorySnapshotReader> snapshotReader();
    Optional<MemorySearcher>       searcher();
    Optional<DialecticEngine>      dialecticEngine();
    Optional<ObservationRecorder>  observationRecorder();
    Optional<MemoryIngestor>       ingestor();
}

public enum MemoryCapability { SNAPSHOT, SEARCH, CHAT, OBSERVE, INGEST }

/** 능력은 <b>계산되는 것이지 선언되는 것이 아니다</b>. 그래서 인터페이스 밖에 산다. */
public final class MemoryCapabilities {
    private MemoryCapabilities() { }
    public static Set<MemoryCapability> of(PeerMemory backend) { /* 다섯 접근자를 본다 */ }
}
```

IMPORTANT: 능력 집합이 **`PeerMemory` 의 메서드가 아닌 것**이 이 설계의 핵심 불변식이다. 백엔드가
`Set.of(CHAT)` 를 선언하고 `dialecticEngine()` 이 `Optional.empty()` 를 돌려주는 상태 —
"할 수 있다고 말해 놓고 못 하는" 상태 — 가 **표현할 자리 자체가 없어서** 만들어지지 않는다.
`RuntimeDegradations` 는 이 계산 결과를 읽어서 기록만 한다. 두 번째 진실 원천이 생기지 않는다.

초안은 이것을 `PeerMemory` 의 `default Set<MemoryCapability> capabilities()` 로 두었는데, **그 형태로는
주장이 성립하지 않는다.** 자바의 `default` 메서드는 `final` 로 만들 수 없으므로 구현체가
`@Override public Set<MemoryCapability> capabilities() { return Set.of(CHAT); }` 를 쓸 수 있고, 그
순간 §14 A4 가 기각당한 바로 그 상태가 재현된다 — §5.2 의 등록 루프는 그 집합 하나만 보고 판단하므로
없는 도구가 등록되고 첫 호출에서 `Optional.empty()` 를 만난다. 그것은 A3 를 기각한 이유
("판단 시점이 런타임으로 밀린다")와 정확히 같은 결과다.

IMPORTANT: **이 불변식에는 예외가 하나 있고, 그것은 티어 경계에 있다.** 재료는 있는데 그 연산을 못
하는 스토어를 넘기면(메타데이터 전용 `ObservationStore`) 티어가 **있는 채로 던진다** — "SEARCH 를
한다고 말해 놓고 못 하는" 상태가 이 경로로는 만들어진다. 접근자가 `Optional.empty()` 를 돌려주지
않으므로 능력 계산이 그것을 볼 수 없기 때문이다. §4.1 이 그것을 계약으로 막고 §8.1 의 testkit 이
강제한다. §0 의 한정("티어 경계에서만 참")은 티어 *안*의 손실을 가리키는 것이었고, 이것은 **세 번째
종류**다.

정적 유틸로 옮기면 **재정의할 자리가 없어져 주장이 참이 된다.** `abstract class` 로 두고
`public final` 메서드를 쓰는 방법도 가능하지만, `PeerMemory` 를 클래스로 만들면 어댑터가 상속을
강요당하므로 고르지 않았다. §3.4 가 요청 객체 형태에 대해 *"규약이지 강제가 아니다"* 를 인정하고
강제 수단을 따로 만든 것과 같은 정직함을 여기에도 적용한 것이다 — 다만 여기서는 타입 배치를 바꾸는
것만으로 실제 강제가 되므로 새 ArchUnit 규칙이 필요 없다.

`Optional` 접근자 다섯 개가 장황해 보일 수 있으나, 대안(`Set<MemoryCapability>` + 캐스팅)은 타입 안전을
잃고, 또 다른 대안(하나의 뚱뚱한 인터페이스 + `UnsupportedOperationException`)은 §14 A3 에서 기각한다.

### 3.3 티어 인터페이스와 값 객체

모든 값 객체는 `final class` + builder + `Objects.requireNonNull` 이다 — `record` 금지 규약
([`code-style.md`](../../../.claude/rules/code-style.md),
[`immutability-pattern.md`](../../../.claude/rules/immutability-pattern.md)).

```java
// ── SNAPSHOT ────────────────────────────────────────────────────────────
public interface MemorySnapshotReader {
    Optional<MemorySnapshot> read(MemorySnapshotQuery query);
}

public final class MemorySnapshotQuery {   // builder
    PeerView  subject;                     // 누구에 대한 스냅샷인가. ★ 워크스페이스는 여기서 나온다
    PeerView  observer;                    // null 이면 global
    String    sessionId;                   // null 이면 세션 없는 실행
    MemorySnapshotScope scope;             // LOCAL | GLOBAL | LOCAL_THEN_GLOBAL(기본)
    MemoryInjectionMode mode;              // 기존 enum 재사용 — SUMMARY_ONLY | FULL
    int       maxTokens;                   // 0 = 상한 없음
}

public final class MemorySnapshot {        // builder
    String  renderedText;                  // ★ 정본. 원격은 이것만 준다
    MemorySnapshotScope resolvedScope;     // 실제로 무엇이 응답했는가
    Instant generatedAt;
    int     tokenCount;                    // 원격이 안 주면 TokenEstimator 로 추정 (estimated 플래그)
    boolean tokenCountEstimated;
    boolean truncated;                     // 예산 때문에 잘렸는가
    boolean observationsAvailable;         // ★ 이 백엔드가 개별 관찰을 노출하는가
    boolean confidenceAvailable;           // ★ 그 관찰의 confidence 가 진짜인가 (§2.2)
    List<Observation> observations;        // observationsAvailable=false 면 항상 빈 리스트
}
```

`renderedText` 가 정본이고 `observations` 가 best-effort 인 것은 §2.1 의 사실을 그대로 타입에 옮긴
것이다. `MemoryInjectionMode` 는 **원격에서는 힌트**다 — 기본 백엔드는 정확히 지키고, 어댑터는 자기
서버의 예산 파라미터(`summary` / `max_conclusions` / `tokens`)로 번역한다. 이 비대칭은 숨기지 않고
`MemorySnapshot.truncated` 와 `tokenCountEstimated` 로 **관측 가능하게** 만든다.

IMPORTANT: `observationsAvailable` 이 있어야 하는 이유는 §3.2 의 불변식이 **티어 경계에서만** 참이기
때문이다. 능력 협상은 "이 백엔드가 SNAPSHOT 을 하는가" 까지만 답하고, "그 스냅샷이 개별 관찰을 싣는가"
는 답하지 않는다. 그런데 오늘의 렌더 코드(`MemoryRecallTool:170-178`)는
`if (!overBudget && !rep.getObservations().isEmpty())` 로 빈 리스트를 만나면 `Observations:` 절을
**통째로 생략**한다 — 모델에게 **"관찰이 하나도 없다"** 와 **"이 백엔드는 개별 관찰을 안 준다"** 가
글자 하나 다르지 않게 된다. 이 문서가 §5.2 에서 없애겠다고 한 실패 모드(조용히 빈 답) 그 자체다.
플래그가 있으면 `MemoryRecallTool` 이 *"이 백엔드는 개별 관찰을 노출하지 않는다 — 요약이 전부다"* 를
렌더할 수 있고, `renderedText` 가 정본이고 손실은 관측 가능하게 둔다는 이 절의 원칙과도 일관된다.

참고로 CHAT 쪽의 같은 손실(`observationsConsidered` · `TokenUsage` 가 원격에서 빈다)에는 플래그를 두지
않는다. `MemoryChatTool:110-113` 이 그 둘을 **로그에만** 쓰고 모델에게는 `getAnswer()` 만 돌려주므로,
비어도 모델이 보는 것이 달라지지 않기 때문이다.

```java
// ── SEARCH ──────────────────────────────────────────────────────────────
public interface MemorySearcher {
    /** 결과는 <b>항상</b> 관련도 내림차순이다 — 그것이 순위의 정본이다. */
    List<MemoryHit> search(MemorySearchQuery query);

    /** 이 백엔드가 순위를 <b>수치</b>로도 낼 수 있는가. 기본 백엔드는 false — 아래 참조. */
    boolean ranksByScore();

    /** 이 백엔드가 검색을 한 세션으로 좁힐 수 있는가. 기본 백엔드는 false — 아래 처분표 참조. */
    boolean narrowsBySession();
}

public final class MemorySearchQuery {     // builder
    PeerView  subject;                     // ★ 워크스페이스는 여기서 나온다
    PeerView  observer;
    String    query;
    int       topK;
    double    minScore;                    // 0 = 미적용. ranksByScore()=false 면 양수는 거절된다
    String    sessionId;                   // null = cross-session
}

public final class MemoryHit {             // builder
    Observation observation;               // 도메인 타입 재사용
    double      score;                     // ranksByScore()=false 면 0. [0,1]
    boolean     confidenceAvailable;       // ★ observation.confidence 가 진짜인가 (§2.2)
    Map<String, Double> signals;           // Dyad 의 explain 6신호. 다른 백엔드는 빈 맵
}
```

`MemoryHit` 이 `Observation` 을 그대로 싣는 것은 의도다 — Dyad 의 `RecallHitResponse{conclusion,
score, explain}` 와 모양이 같고, 도구가 렌더할 때 이미 있는 타입을 그대로 쓴다. 반환이 값 객체 래퍼가
아니라 `List<MemoryHit>` 인 것은 이것이 대체하는 `ObservationStore.semanticSearch` 와 같은 모양이기
때문이다. 대신 Dyad 의 `analyzedQuery` / `candidatesConsidered` 같은 진단 필드는 버려진다 — 필요해지면
`MemorySearchResult` 래퍼를 도입하는 별개 변경이다.

IMPORTANT: **순위의 정본은 `score` 가 아니라 리스트 순서다.** 초안은 `score` 를 "순위의 정본" 으로
승격시켰는데, **기본 백엔드에는 점수가 아예 없다** — `ObservationStore.semanticSearch` 는
`List<Observation>` 을 돌려주고(`:46`), 그 아래 `ObservationIndex.search`(`:61`)의 javadoc(`:53-54`)도
*"Returns up to `topK` observation ids … **ordered from most to least relevant**"* 로 **순서만**
약속한다. `StoreBackedPeerMemory` 는 그 둘 위에 서므로 점수를 만들 재료가 없다.

이것은 `observationsAvailable`(§3.3)·`confidenceAvailable`(§2.2)에 이은 **세 번째 티어 안 손실**이고,
같은 기계로 처분한다.

| | 처분 |
|---|---|
| 순서 | **모든 백엔드가 보장한다.** 티어 계약이며, 기본 백엔드의 인덱스가 이미 그렇게 약속한다 |
| `score` | `ranksByScore()=true` 인 백엔드만 채운다. false 면 `0` 이다 — **순위에서 역산한 가짜 점수를 지어내지 않는다** |
| `minScore` | `ranksByScore()=false` 인데 양수를 주면 **거절한다**(`IllegalArgumentException`). 조용히 무시하면 호출자는 필터가 걸린 줄 알고 안 걸린 결과를 받는다 |
| `sessionId` | 같은 처분. `narrowsBySession()=false` 인데 세션 id 를 주면 **거절한다** — 기본 백엔드의 `ObservationStore.semanticSearch(subject, query, topK)` 에는 좁힐 세션 축이 아예 없다. 이것이 `ranksByScore()` · `storesConfidence()` 에 이은 **세 번째 신호**다 |
| `observer` | **거절하지 않는다.** 이 축은 "누가 묻는가" 이지 결과를 좁히는 약속이 아니다 — peer 쌍으로 스코프하는 원격 백엔드가 두 짝을 다 필요로 하기 때문에 질의에 있고, 기본 백엔드는 `subject` 에서 전부 파생시킨다. 좁혀 준다고 약속하지 않았으므로 안 좁혀도 거짓말이 아니다 |

`storesConfidence()` 가 `ObserveTool` 의 스키마를 좁히는 것과 같은 모양이다 — 못 하는 것을 **말하고**,
호출자가 그것을 보고 요청을 바꾸게 한다. 모델은 이 축에 노출되지 않는다(`MemorySearchTool` 의 입력
스키마는 `query` + `top_k` 뿐이고 §11.2 가 그것을 불변으로 둔다). 노출면은
`peerMemory.searcher()` 를 직접 쓰는 애플리케이션 하나이며, 바로 그 호출자가 `minScore` 를 줄 수 있는
유일한 주체다.

```java
// ── OBSERVE ─────────────────────────────────────────────────────────────
public interface ObservationRecorder {
    Observation observe(ObservationDraft draft);

    /** 이 백엔드가 {@code ObservationDraft.confidence} 를 실제로 저장하는가. 원격은 대개 false (§2.2). */
    boolean storesConfidence();
}

public final class ObservationDraft {      // builder
    PeerView  subject;                     // ★ 워크스페이스는 여기서 나온다
    PeerView  observer;
    String    sessionId;                   // nullable
    String    content;                     // ★ 이미 레닥션을 통과한 텍스트여야 한다
    ObservationType type;
    double    confidence;                  // 원격은 버린다 — §2.2 · storesConfidence() 를 먼저 볼 것
    Map<String, String> metadata;
}

// ── INGEST ──────────────────────────────────────────────────────────────
public interface MemoryIngestor {
    MemoryIngestReceipt ingest(MemoryIngestRequest request);
}

public final class MemoryIngestRequest {   // builder
    PeerView      observer;                // ★ 워크스페이스는 여기서 나온다
    String        sessionId;
    List<Message> messages;                // ★ 이미 레닥션을 통과한 메시지여야 한다
    boolean       waitForDerivation;       // Dyad 의 ?wait=derive. 못 하는 백엔드는 무시
}

public final class MemoryIngestReceipt {   // builder
    int     accepted;
    boolean derived;                       // waitForDerivation 이 실제로 지켜졌는가
}
```

### 3.4 요청 객체 형태는 **규약이지, 오늘의 빌드가 강제하는 것이 아니다**

초안은 `MemoryArchitectureTest` 가 이 형태를 강제한다고 적었는데 **그것은 사실이 아니다.** 두 규칙이
실제로 막는 범위는 이렇다.

| 규칙 | 실제 사정거리 | `search(Workspace, String, int)` 를 막는가 |
|---|---|---|
| `onlyWhitelistedMethodTakesRawStringId` (`:50-58`) | `.haveRawParameterTypes(String.class)` — 파라미터 목록이 **정확히 `(String)`** 인 메서드만. 테스트 자신의 주석(`:62-64`)이 *"ArchUnit's `haveRawParameterTypes` only matches the exact list"* 라고 적어 둔다 | **아니다** |
| `noMemoryStoreMethodTakesStringAsFirstParameter` (`:68-88`) | `{WorkspaceStore, ObservationStore, RepresentationStore}` **세 개를 이름으로 열거**하고, `String` 이 **첫** 파라미터인 것만 | **아니다** (첫 파라미터가 `Workspace` 다) |

즉 오늘의 규칙은 **멀티테넌트 격리**(맨 `String` id 금지)를 막을 뿐, 파라미터를 요청 객체로 접는 것과는
무관하다. 다섯 티어를 두 번째 규칙의 배열에 추가해도 마찬가지다.

그래도 요청 객체를 쓰는 이유는 규칙이 아니라 **`MemoryContextRequest` 가 이미 적어 둔 것**이다 —
새 정체성 축은 provide 의 파라미터가 아니라 요청 타입의 필드로 더해서 *"widening the input never breaks
an implementation"* 을 지킨다. 원격 백엔드가 늘어날수록 질의 축(필터·임계값·스코프)이 늘어나므로 이
성질이 실제로 필요하다.

강제를 원한다면 만들어야 한다. §12 Step 1 의 인수 조건 ②·③ 이 그것이다.

1. 다섯 티어를 `noMemoryStoreMethodTakesStringAsFirstParameter` 의 배열에 추가한다 (격리 규칙 확장)
2. **새 규칙**을 하나 더 쓴다. 문구는 아래와 같고, **"메서드 전부" 가 아니라 "질의를 받는 메서드" 에
   한정하며 신규 네 티어에만 건다.**

   > **신규 네 티어**(`MemorySnapshotReader` · `MemorySearcher` · `ObservationRecorder` ·
   > `MemoryIngestor`)의 public 메서드 중 **파라미터를 하나라도 받는 것**은, 파라미터가 정확히 1개이고
   > 그 타입이 `at.aimon.core.memory..` 의 요청 값 객체여야 한다.

IMPORTANT: 이 한정은 문체가 아니라 **필수**다. 초안의 문구("다섯 티어 인터페이스의 public 메서드는
파라미터가 정확히 1개")는 **이 문서 자신의 타입 둘에 대해 성립하지 않아서**, 문자 그대로 구현하면
Step 1 이 빨간 빌드로 끝난다.

| 반례 | 왜 걸리나 | 처분 |
|---|---|---|
| `ObservationRecorder.storesConfidence()` · `MemorySearcher.ranksByScore()` (§3.3) | 파라미터 **0개** — 능력 신호는 **질의가 아니라 물음**이므로 인자를 받지 않는다 | *"파라미터를 하나라도 받는 것"* 한정으로 자연히 빠진다. 둘 다 §2.2·§3.3 이 손실을 관측 가능하게 만들려고 도입한 신호이므로 지울 수 없고, **앞으로 늘어날 자리**이기도 하다 |
| `DialecticEngine.queryStream(DialecticQuery, LlmStreamSink)` (`:49`) | 파라미터 **2개**, 게다가 `LlmStreamSink` 는 `at.aimon.core.llm.streaming` 이다 | **`DialecticEngine` 을 규칙 대상에서 뺀다** — CHAT 티어는 기존 인터페이스이고 §11.2 가 "불변" 을 약속했으므로, 규칙을 맞추려고 그것을 고치면 약속을 깬다 |
| *(반례 아님)* 패키지 범위 | 규칙 문구가 `at.aimon.core.memory` 직하로 읽히면 하위 패키지의 요청 타입이 걸린다 | 문구를 `at.aimon.core.memory..` 로 적는다. **오늘 걸리는 반례는 없다** — `DialecticEngine` 을 뺀 뒤 규칙이 보는 요청 타입은 §3.3 의 넷뿐이고 전부 직하에 있다. 장래에 하위 패키지로 나뉠 것에 대비한 상위집합이다 |

**둘 중 어느 쪽으로 갈지 문서가 골라 적는다** — `LlmStreamSink` 를 화이트리스트에 예외로 적는 길도
있지만 고르지 않았다. 예외를 하나 열면 다음 스트리밍 시그니처가 같은 예외를 요구하고, 규칙의 값은
예외의 수에 반비례한다. `DialecticEngine` 은 이 설계가 **만들지 않은** 유일한 티어이므로 규칙 밖에
두는 편이 경계로서 설명하기 쉽다.

이것이 없으면 요청 객체 형태는 규약일 뿐이다 — 그리고 지금 상태가 정확히 그것이다.

### 3.5 `MemoryContextProvider` 와의 관계 — 흡수가 아니라 **유지**

`MemoryContextProvider` 는 **실행기 쪽 이음매로 그대로 남는다.** `SystemPromptRenderer` 는 지금처럼
`provide(MemoryContextRequest)` 를 부르고, `ExecutorSpec.memoryContextProvider` 도 그대로다.

바뀌는 것은 **기본 구현이 무엇 위에 서는가**뿐이다.

```
[지금]  RepresentationMemoryContextProvider(RepresentationStore, workspace, resolver, mode, maxTokens)
                                            └── 스토어 고도

[제안]  SnapshotMemoryContextProvider(MemorySnapshotReader, workspace, resolver, mode, maxTokens)
                                      └── 서비스 고도. 어떤 백엔드든 꽂힌다
```

이름을 `Representation…` 에서 `Snapshot…` 으로 바꾸는 것은 미용이 아니다 — 원격 백엔드에는
`Representation` 이라는 타입이 **존재하지 않으므로**, 그 이름을 유지하면 클래스 이름이 거짓말을 한다.
이 프로젝트가 두 번의 대규모 개명을 한 이유와 같은 이유다
([`scope-model.md` §7](../../overview/scope-model.md)). 개명 항목은 §11 에 있다.

`MemoryContextRequest`(실행의 **미해석 정체성** — sessionId + principal)와 `MemorySnapshotQuery`
(**해석된 읽기** — workspace + subject + observer + 예산)는 다른 것이다. 전자를 후자로 바꾸는 것이
`MemoryPeerResolver` 의 일이고, 그 경계는 이미 존재한다.

### 3.6 수명 · 소유권 · 소멸

[`scope-model.md`](../../overview/scope-model.md) 의 규칙 — *"만든 쪽이 닫는다. 빌려온 것은 닫지
않는다."* — 를 그대로 따른다.

| 컴포넌트 | 스코프 | 생성 | 소멸 |
|---|---|---|---|
| `PeerMemory` (조립이 프로퍼티로 만든 것) | Application | `MemoryAssembly` | 스택. 새 `TeardownPhase.MEMORY_BACKEND` |
| `PeerMemory` (호출자가 준 것 — 빈/인스턴스) | Application | 애플리케이션 | **애플리케이션**. 스택은 손대지 않는다 |
| **`RedactingPeerMemory`** (§6.2 가 필수로 만든 래퍼) | Application | `MemoryAssembly` | **자기 자원이 없다.** teardown 에 올리는 것은 **감싼 delegate** 이며, 그것이 조립이 만든 것일 때만이다 |
| 티어 구현체 (어댑터가 소유한 HTTP 클라이언트) | Application | `PeerMemory` 구현체 | 그 `PeerMemory` 의 `close()` |

`VirtualShell` 의 `ownedShell` 과 정확히 같은 모양이다 — 조립이 만들었을 때만 닫는다. `PeerMemory` 는
`ApplicationScoped` 마커를 구현하되 `AutoCloseable` 을 **강제하지 않는다**: 자원을 쥔 어댑터만 스스로
`AutoCloseable` 을 구현하고, 조립이 `instanceof` 로 확인해 teardown 에 올린다.

IMPORTANT: **그 `instanceof` 는 래퍼가 아니라 delegate 에 걸어야 한다.** §6.2 이후 스택에 들어가는
물건은 `RedactingPeerMemory` 이고 그것은 아무 자원도 쥐지 않으므로, 래퍼에 검사를 걸면 어댑터의 HTTP
클라이언트가 **영원히 안 닫힌다**. 이 저장소의 규약대로 닫을 대상은 명시적으로 골라야 하고
([`scope-model.md`](../../overview/scope-model.md) §2 — *"마커에 대한 fan-out 은 없다"*), 누락의 증상은
Step 7(첫 어댑터) 이전에는 나타나지 않는다(자원을 쥔 어댑터가 아직 없다). 마커에 대한 fan-out 이
없다는 사실(§scope-model.md §2)과 정합하다.

#### `TeardownPhase` — 새 phase 하나로는 부족하다

초안은 새 `MEMORY_BACKEND` 를 기존 메모리 phase 넷 **뒤**에 놓는다고 적었는데, 그 자리는 여전히
`SESSIONS` **앞**이다 — `TeardownPhase` 의 선언 순서가 그렇고(`TeardownRegistry#closeAll()` 이
선언 순서로 훑는다), `SESSIONS` 는 *"stops accepting submits, lets in-flight turns finish within the
configured drain timeout, then releases every held session lease"* 를 하는 phase 다(`:79-88`).

```
MEMORY_FINAL_DERIVATION → MEMORY_QUEUE → DREAMER → MEMORY_MAINTENANCE → SESSIONS → CHECKPOINTS → …
                                                    ↑ 초안의 MEMORY_BACKEND 자리
```

그러면 §7 이 도입하는 두 수집 모드가 **자기가 막겠다던 유실을 그대로 일으킨다.**

| 모드 | 언제 발화하나 | 초안 배치에서 무엇을 치나 |
|---|---|---|
| `session-end` | 라이브 세션이 닫힐 때 = `SESSIONS` | 이미 닫힌 백엔드, 이미 멈춘 큐 |
| `execution-end` | 실행이 끝날 때. 종료 시에는 `SESSIONS` 드레인 중의 in-flight 실행 | 같음 |

원인은 축이 다르다는 것이다 — 기존 네 phase 는 CLI 의 `MEMORY_FINAL_DERIVATION`(프로세스 종료 시
전사 일괄 투입, `AgentSetupFactory:640`)에 맞춰 배치된 것이고, §7 의 두 모드는 **세션·실행 수명**에
붙는다. 따라서 phase 하나를 더하는 것이 아니라 **메모리 블록 전체를 `SESSIONS` 뒤로 옮긴다.**

```
… → SESSIONS → CHECKPOINTS
      → MEMORY_FINAL_DERIVATION → MEMORY_QUEUE → DREAMER → MEMORY_MAINTENANCE → MEMORY_BACKEND
      → SESSION_TRANSPORT → AGENT_RUNTIMES → …
```

`CHECKPOINTS` **뒤**인 이유: `SESSIONS` 의 javadoc 이 *"closing a session performs the final end-of-turn
save through the checkpoint mailbox"* 라고 그 둘을 붙여 두었으므로 사이에 끼어들지 않는다. 그 뒤로
가면 전사가 완전히 저장된 뒤에 마지막 수집이 돌아 오히려 정확해진다. `MEMORY_BACKEND` 가 블록의
마지막인 것은 앞의 넷이 전부 백엔드를 통해 쓰기 때문이다.

**위험과 그 근거**: `MEMORY_FINAL_DERIVATION` 은 전사를 `TranscriptManager.initialize(sessionId, null)`
로 읽는데, 그 뒤에 있는 `SessionRecordStore` 는 application-scoped 이므로 `SESSIONS` 가 닫지 않는다
([`scope-model.md`](../../overview/scope-model.md) §5.2). 따라서 이동 후에도 전사는 읽힌다 — 다만
**실측하지 않았고**, §12 Step 5 의 인수 조건에 "이동 전후로 CLI 최종 derivation 이 같은 수의 관찰을
만든다" 를 넣는다.

이것은 배포된 모듈의 enum 선언 순서를 바꾸는 것이므로 **동작 변경**이다 — §11.3 에 적는다.

---

## 4. 기존 저장소 SPI 는 어떻게 되는가

### 4.1 남는다. 격하는 참조 방향으로 표현한다

`ObservationStore` · `RepresentationStore` · `WorkspaceStore` 는 **삭제하지도, 옮기지도, 시그니처를
바꾸지도 않는다.** 바뀌는 것은 지위다.

| | 이전 | 이후 |
|---|---|---|
| 지위 | **메모리를 교체하는 확장점** | **기본 백엔드의 저장 계약** |
| 누가 구현하는가 | 메모리를 바꾸려는 모든 사람 | 기본 백엔드에 **새 저장소**를 붙이려는 사람 |
| 조립이 보는가 | `MemorySpec` 이 직접 받는다 | `StoreBackedPeerMemory` 의 재료로만 |

격하가 실재한다는 것은 **한 문장으로 검증 가능**하다 — **새 SPI 다섯 인터페이스의 어떤 시그니처도
`ObservationStore` / `RepresentationStore` / `WorkspaceStore` 를 언급하지 않는다.** ArchUnit 규칙으로
못 박을 수 있으며, §12 Step 1 의 인수 조건이다.

기본 백엔드는 이렇게 조립된다.

```java
PeerMemory backend = StoreBackedPeerMemory.builder()
        .representationStore(representationStore)    // → MemorySnapshotReader
        .observationStore(observationStore)          // → MemorySearcher, ObservationRecorder
        .dialecticEngine(dialecticEngine)            // → 그대로 통과
        .derivationQueue(derivationQueueManager)     // → MemoryIngestor
        .build();
```

#### 워크스페이스는 질의의 `PeerView` 에서 나온다 — 필드로 두지 않는다

앞선 개정은 빌더와 질의 값 객체 넷이 각자 `Workspace` 를 들되 *"질의가 이긴다, 다르면 거절하지
않는다"* 로 정했다. **그 처분을 뒤집는다.** `PeerView:20` 이 `private final Workspace workspace` 를
자기가 들고 있으므로, 질의에 별도 필드를 두면 `MemorySnapshotQuery` 안에만 워크스페이스가 최대 세 벌
(필드 + `subject` 안 + `observer` 안)이 되고 빌더까지 네 벌이 된다. 이 문서가 A4 를 기각한 사유가
*"진실 원천이 둘이 된다"* 인데, 여기서 셋을 만들고 있었다.

그리고 "다르면 거절하지 않는다" 는 멀티테넌트 격리에서 가장 위험한 처분이다 — `subject` 가 A
워크스페이스인데 필드가 B 이면 **B 의 저장소에서 A 의 peer 를 찾는 질의**가 조용히 만들어진다.
같은 패키지의 선례는 정반대다: `Observation:57-62` 는 `id` 의 워크스페이스와 `subject`/`observer` 의
워크스페이스가 다르면 `IllegalArgumentException` 을 던진다.

그래서 **필드를 뺀다.** 다섯 티어의 질의는 전부 `PeerView` 를 들고, 워크스페이스는
`subject.getWorkspace()` 가 정본이다. 선례가 이미 그렇다 — `ObservationStore` 의 시그니처는
워크스페이스를 따로 받지 않고 `PeerView` 에서 파생시키며(`:33/35/46/53`), `Workspace` 를 별도로
받는 **두** 메서드 `findSubjects(:66)` 와 `purgeSoftDeletedBefore(:115)` 는 **둘 다 peer 가 없다.**
같은 규칙: peer 없는 티어 질의가 생기면 그때 `Workspace` 파라미터를 준다.

**예외는 이 설계가 만들지 않은 CHAT 티어다.** `DialecticQuery` 는 워크스페이스 필드를 `subject`/
`observer` 와 함께 들지만(`:26-28`), `:43-48` 이 셋의 **일치를 강제**하므로 위에서 경계한 처분
("다르면 거절하지 않는다")이 여기에는 없다 — 이 절이 올바른 선례로 든 `Observation:57-62` 와 정확히
같은 모양이다. §11.2 가 그 타입을 불변으로 두므로 손대지 않는다. §3.4 가 요청 객체 규칙에서
`DialecticEngine` 을 카빙한 것과 같은 카빙이며, 두 절이 같은 이유로 같은 예외를 둔다.

`StoreBackedPeerMemory` 의 빌더에도 `.workspace(...)` 를 두지 않는다. `PeerMemory` 는
application-scoped 이므로 **한 프로세스가 여러 워크스페이스를 서빙할 수 있어야 하고**, 생성 시
워크스페이스에 묶이면 그것이 불가능해진다 — 워크스페이스는 `MemoryArchitectureTest` 가 지키려는 것처럼
**호출과 함께 온다**. (원격 어댑터에서는 워크스페이스가 URL 경로 세그먼트가 되므로 질의마다 달라도
비용이 없다.)

IMPORTANT: **재료는 있는데 그 연산을 못 하는 경우가 따로 있다.** `MongoObservationStore:126-136` 과
`PostgresObservationStore:229-244` 는 `semanticSearch` 에서 **항상** `UnsupportedOperationException` 을
던지므로(§1.3 이 인용한 사실), 그 스토어를 그대로 넘기면 `StoreBackedPeerMemory` 는 `SEARCH` 티어를
**있는 것으로 내놓고** 매 호출이 `ToolResult.error` 로 떨어진다 — §5.2 의 IMPORTANT("백엔드가 못 하는
능력의 도구를 등록하지 않는다")의 정반례다. 그래서 계약을 하나 적는다.

> `.observationStore(...)` 에 넘기는 것은 `semanticSearch` 를 **실제로 답하는** 스토어여야 한다.
> 메타데이터 전용 스토어는 조립이 `IndexedObservationStore` 로 감싸서 넘긴다.

이것은 회귀가 아니라 **현상 유지**다 — 오늘의 `registerCliTools` 와 `OrcaMemoryToolProvider` 도 스토어
유무만 보고 `MemorySearchTool` 을 등록한다. 다만 이 설계가 능력 협상을 약속하는 이상 계약으로 적어야
하고, 강제는 §8.1 의 testkit 이 맡는다("SEARCH 티어가 있으면 `UnsupportedOperationException` 을 던지지
않는다").

빠진 재료는 그 티어를 **비운다**. 스타터의 현재 상태(디라이버 없음)가 정확히 그것이다 —
`derivationQueue` 가 없으면 `ingestor()` 가 비고, 계산된 능력 집합에 `INGEST` 가 없으며,
`MemoryAssembly` 가 지금 하드코딩으로 올리던 `memory-write-path` degradation 을 **계산해서** 올린다.

### 4.2 왜 `at.aimon.core.memory.impl` 로 옮기지 않는가

옮기면 **배포된 모듈들이 내부 패키지에 의존하게 된다.** [`api-stability.md` §2](../../project/api-stability.md)
의 `*.impl` 경계는 규약이고, `at.aimon.core.<domain>.impl` 은 그 도메인 트리 밖에서 import 하지 않는다.

정확히 해 둘 것이 둘 있다 — 초안은 이 금지를 "`PackageDependencyArchitectureTest` 가 강제한다" 고
적었는데 **오늘의 memory 도메인에는 그 강제가 없다.**

1. 그 테스트의 `.impl` 규칙은 **도메인마다 손으로 쓴 것**이고(`workflow.impl:267`, `filesystem.impl:345`,
   `shell.impl:358`, `agent.impl:318` 근방) `memory` 용은 없다. `api-stability.md:44` 도 *"현재 `impl` 로
   격리된 도메인은 일곱 개"* 로 memory 를 제외한다
2. 더 근본적으로, 그 테스트는 `aimon-core` 테스트 클래스패스에서 `importPackages("at.aimon")`(`:147`)
   을 돌므로 **형제 Gradle 모듈을 아예 보지 못한다.** 증거가 트리 안에 있다 —
   `MemoryArchitectureTest.inMemoryImplsAreInternalToMemoryPackage:92-99` 는 `InMemory*` 를 memory
   패키지 밖에서 참조하는 것을 금지하는데, `aimon-cli` 와 `aimon-spring-boot-starter` 가 실제로
   `InMemoryObservationStore` 를 import 하고도 빌드가 초록이다

그러므로 옮기지 않는 이유는 "빌드가 막는다" 가 아니라 **아래 표의 다섯 모듈이 전부 배포 대상이거나
애플리케이션인데 그들이 내부 패키지를 import 하게 된다**는 것이다. 규약을 지키려면 그들의 코드를 먼저
옮겨야 하고, 그것이 이 설계와 독립적인 별개 리팩터다.

| 옮기면 깨지는 것 | 무엇을 import 하고 있나 |
|---|---|
| `aimon-memory-file` | `File*Store implements ObservationStore/RepresentationStore/WorkspaceStore` |
| `aimon-memory-mongodb` | 같음 |
| `aimon-memory-postgres` | 같음 + `PostgresDerivationQueueManager implements DerivationQueueManager` |
| `aimon-cli` | `LlmDeriver`, `DefaultDreamerEngine`, `RandomWalkDreamer`, `EmbeddingSurprisalScorer`, `LlmJudgeSurprisalScorer`, `DefaultReconciler`, `InMemory*Store`, `LlmDialecticEngine` — **구체 클래스 13종을 직접 import 해 조립한다** |
| `aimon-spring-boot-starter` | `InMemoryObservationStore`, `InMemoryRepresentationStore`, `DefaultRedactionPolicy`, `StrictRedactionPolicy` |

즉 `.impl` 이동은 **저장소 SPI 를 옮기는 작업이 아니라 CLI 의 조립 코드를 코어 안의 팩토리로 끌어들이는
작업**이다. §12 에 후속 단계로 남기고 전제조건을 적는다. 옮기기로 한다면 그때 **memory 용 `.impl`
ArchUnit 규칙을 함께 쓴다** — 규칙 없는 `.impl` 패키지는 표식일 뿐이다.

### 4.3 `aimon-memory-{file,mongodb,postgres}` 는 그대로 산다

세 모듈은 **한 줄도 바뀌지 않는다.** 그들이 구현하는 인터페이스도, 시그니처도, DDL 도, 컬렉션 이름도
그대로다 — [`api-stability.md` §4](../../project/api-stability.md) 의 동결 약속이 여기에 걸려 있다.

`WorkspaceStore` 는 티어가 **되지 않는다.** 원격 백엔드는 워크스페이스 CRUD 와 테넌시를 자기가 소유하고
(Honcho·Dyad 둘 다 get-or-create + JWT 스코프), AIMON 쪽은 설정으로 받은 워크스페이스 이름을 경로에
실어 보내기만 한다. `WorkspaceAccessPolicy` 도 마찬가지로 기본 백엔드 전용이다.

---

## 5. 도구 4개의 재배선과 등록 판단

### 5.0 먼저 — 조립 경로는 둘이고, 하나만 `MemoryAssembly` 를 지난다

IMPORTANT: 아래 §5.2 의 능력 기반 등록도, §9.2 의 `backend: honcho|dyad` 도 `MemorySpec` →
`MemoryAssembly` 를 전제한다. 그런데 **오늘 메모리를 다섯 티어까지 배선하는 유일한 조립인 CLI 는 그 둘을
한 번도 쓰지 않는다.** `grep -rn "MemorySpec\|MemoryAssembly" modules/aimon-cli/src/main/java/` → **0건**.

| 조립 경로 | `MemorySpec`/`MemoryAssembly` 를 지나는가 | 오늘 배선되는 능력 |
|---|---|---|
| `aimon-spring-boot-starter` | **지난다** (`AimonMemoryAutoConfiguration` → `MemoryContribution`) | SNAPSHOT · SEARCH · OBSERVE. **CHAT 없음**(§5.3) · **INGEST 없음**(§7.1) |
| `aimon-cli` (`AgentSetupFactory`) | **지나지 않는다** | 다섯 **전부** |
| 애플리케이션 직접 조립 | 선택 (`AimonStackSpec.memory(...)` 를 부르면 지난다) | 스펙이 준 만큼 |

CLI 가 대신 하는 것은 손으로 쓴 메서드 아홉 개다 — `registerCliTools:899` ·
`createRepresentationStore:924` · `createObservationStore:957` · `buildMemoryWiring:980` ·
`buildMemoryContextProvider:1008` · `buildMemoryDeriver:1027` · `buildDerivationQueue:1053` ·
`buildMemoryFinalDerivation:1073` · `buildDreamerSubsystem:1098`. **전부 `RepresentationStore`/`ObservationStore`
타입을 직접 받는다.** `backend: honcho` 에는 그 두 스토어가 존재하지 않으므로, 이 아홉 개를 그대로 두면
CLI 로는 원격 백엔드를 고를 수 없다.

거기에 규칙이 하나 더 걸려 있다. `AimonStackSpec:122-130` 은 `MemorySpec`(with representation store)과
`ExecutorSpec.memoryContextProvider` 를 **상호 배타**로 거절하는데, CLI 는 지금 후자를 쓴다
(`AgentSetupFactory:562`).

**그러므로 CLI 이주는 설정 표면 작업이 아니라 독립 단계다** (§12 Step 4). 초안이 이것을 마지막 단계
("CLI/스타터 설정 표면", 지금의 Step 9) 안에 넣은 것은 규모를 심하게 낮춰 잡은 것이었다. 이주의 모양은 이렇다.

| CLI 의 오늘 | 이주 후 |
|---|---|
| `createRepresentationStore` · `createObservationStore` · `buildDerivationQueue` · `LlmDialecticEngine` 생성 | `backend` 값에 따라 `PeerMemory` 하나를 만든다 — `file`/`in-memory` 면 `StoreBackedPeerMemory`, `honcho`/`dyad` 면 어댑터 |
| `.executor(ExecutorSpec…memoryContextProvider(…))` | `.memory(MemorySpec…peerMemory(backend))`. **상호 배타 규칙은 그대로 둔다** — CLI 가 후자를 안 쓰게 되므로 애초에 걸리지 않는다 |
| `registerCliTools` 가 메모리 도구 4종 등록 | `MemoryAssembly` 의 툴 provider 가 등록. `registerCliTools` 에는 `ConsoleOutputTool` 만 남는다 |
| `buildMemoryWiring` 의 `MemoryToolContextEnricher` | `MemoryAssembly` 가 만든다 (같은 타입, 같은 인자) |
| `buildDreamerSubsystem` · `buildMemoryMaintenance` | **CLI 에 남는다.** 티어가 아니라 기본 백엔드 전용 배경 작업이다(§6.1). 원격 백엔드에서는 §6.1 의 검증이 이것을 끈다 |

### 5.1 무엇이 무엇 위에 서는가

| 도구 | 지금 | 이후 | 필요한 능력 |
|---|---|---|---|
| `MemoryRecallTool` | `RepresentationStore` | `MemorySnapshotReader` | `SNAPSHOT` |
| `MemorySearchTool` | `ObservationStore` | `MemorySearcher` | `SEARCH` |
| `MemoryChatTool` | `DialecticEngine` | **변경 없음** | `CHAT` |
| `ObserveTool` | `ObservationStore` | `ObservationRecorder` | `OBSERVE` |

`ToolContext` 키 4종(`memory.workspace` / `memory.observer` / `memory.subject` / `memory.sessionId`)과
`MemoryToolContextEnricher` 는 바뀌지 않는다 — 도구가 **누구에 대해** 묻는지는 백엔드와 무관하다.

### 5.2 누가 등록을 결정하는가 — `MemoryAssembly`

**조립 계층이 결정한다. 도구도 백엔드도 아니다.** 판단 근거는 `MemoryCapabilities.of(backend)` 가
다섯 티어 접근자에서 **계산한** 집합 하나뿐이다 — `PeerMemory` 에는 그 집합을 돌려주는 메서드가 없고,
없는 것이 §3.2 의 불변식이다.

능력 하나가 도구 하나에 대응하지는 **않는다** — 셋이 어긋난다. SNAPSHOT 은 소비자가 둘(주입
provider + `MemoryRecallTool`)이고, INGEST 는 도구가 아예 없으며(소비자는 실행기 이음매),
`memory-tools` 는 능력이 아니라 **조립 모양**(고정 observer 유무)에서 나온다. 그래서 루프는 도구만
돌고 나머지는 밖에 적는다.

```java
// MemoryAssembly (개념)
Set<MemoryCapability> caps = MemoryCapabilities.of(backend);   // 선언이 아니라 계산 (§3.2)

// (1) 능력 → 도구. 1:1 인 것은 이 셋뿐이다
registerIf(caps, SEARCH,  () -> new MemorySearchTool(backend.searcher().get(), redaction));
registerIf(caps, OBSERVE, () -> new ObserveTool(backend.observationRecorder().get(), redaction));
registerIf(caps, CHAT,    () -> new MemoryChatTool(backend.dialecticEngine().get()));

// (2) SNAPSHOT 은 소비자가 둘 — 도구 하나와 프롬프트 주입 provider 하나
if (caps.contains(SNAPSHOT)) {
    register(new MemoryRecallTool(backend.snapshotReader().get()));
    contextProvider = new SnapshotMemoryContextProvider(backend.snapshotReader().get(), ...);
}

// (3) INGEST 는 도구가 없다 — 실행기 이음매에 꽂힐 뿐이다 (§7.2)
if (caps.contains(INGEST) && !perCaller) { ingestor = backend.ingestor().get(); }

// (4) 없는 능력마다 결과 문장을 남긴다
for (MemoryCapability missing : complementOf(caps)) {
    degradations.add(degradationKey(missing), consequenceText(missing, backend.backendId()));
}
```

이것이 오늘의 하드코딩된 세 개의 degradation 키(`memory-write-path`, `memory-tools`,
`memory-redaction`)를 **능력 다섯 개에 대해 계산된 키**로 바꾼다.

| degradation 키 | 언제 오르는가 | 결과 문장이 말해야 하는 것 |
|---|---|---|
| `memory-snapshot` | `SNAPSHOT` 없음 | 프롬프트에 메모리 조각이 안 들어가고 `MemoryRecall` 이 등록되지 않는다 |
| `memory-search` | `SEARCH` 없음 | `MemorySearch` 미등록 |
| `memory-chat` | `CHAT` 없음 | `MemoryChat` 미등록 |
| `memory-observe` | `OBSERVE` 없음 | `Observe` 미등록 — 모델이 사실을 남길 방법이 없다 |
| `memory-ingest` | `INGEST` 없음 **또는** per-caller 모드 | **오늘의 `memory-write-path` 를 대체한다**(§11.1). 대화가 메모리로 흐르지 않으므로 다른 것이 채우지 않는 한 영원히 빈다. per-caller 에서도 오르는 이유는 §7.2 |
| `memory-tools` | 고정 observer 가 없음 (per-caller 모드) | 지금과 동일. 능력이 아니라 **조립 모양**의 문제다 |
| `memory-redaction` | 레닥션 정책 없이 `OBSERVE`/`INGEST` 가 켜짐 | **능력에 대한 진술이어야 한다** — "모델이 관찰하는 것이 그대로 영속된다" 가 아니라 "이 백엔드에 쓰이는 것을 마스킹하는 것이 아무것도 없다" 다. 조건이 능력 하나이므로 문장도 그 폭이어야 한다: per-caller 처럼 도구가 등록되지 않는 조합에서도 티어 접근자(§3.2)는 공개이므로 경고는 여전히 참이지만, 도구를 이름으로 부르면 거짓이 된다. 원격 백엔드에서는 **더 심각**하다 (§6.2) |

IMPORTANT: 백엔드가 못 하는 능력의 도구를 **등록하지 않는 것**이 이 설계의 요구사항이다. 등록해 놓고
`ToolResult.error("not supported")` 를 돌려주면 모델은 매 실행마다 그 도구를 다시 시도하고, 실패는
프롬프트 예산과 iteration 을 태운다. 오늘 `OrcaMemoryToolProvider` 의 javadoc 이 같은 이유로
*"callers that cannot supply an observer should not register this provider at all"* 이라고 적어 둔 것과
같은 판단이다.

### 5.3 `MemoryChatTool` 은 조립 경로에서 빠져 있다 (기존 결함)

확인된 사실: `OrcaMemoryToolProvider` 는 `MemoryRecallTool` · `MemorySearchTool` · `ObserveTool`
**세 개만** 등록한다. `MemoryChatTool` 은 `aimon-cli` 의 `registerCliTools` 에서만 등록되며,
`MemorySpec` 에는 `DialecticEngine` 을 담을 필드가 아예 없다. 즉 **스타터로 부팅한 배포는
`MemoryChat` 을 쓸 수 없다.**

능력 기반 등록으로 옮기면 이 구멍이 메워진다 — `CHAT` 능력이 **있으면** 등록되고, 없으면 degradation
이 오른다. 별도 수정 항목이 아니라 §12 Step 3 의 부산물이다.

다만 "있으면" 이 조건절이라는 것이 중요하다. 스타터의 `backend=in-memory` 경로는 `DialecticEngine` 을
만들지 않으므로(그 클래스가 발행하는 **스토어** 빈은 `aimonInMemoryRepresentationStore:95` 와
`aimonInMemoryObservationStore:112` 둘뿐이고 — 세 번째 `@Bean` 은 스펙을 나르는
`aimonMemoryContribution:137` 이다 — `DialecticEngine` 빈은 스타터 main 소스 전체에 **0건**이다)
Step 3 이 끝나도 **그 배포에서는 여전히 등록되지 않고**
`memory-chat` degradation 이 대신 오른다. 실제로 등록되는 것은 `backend=supplied` + `PeerMemory` 빈
경로(§9.3)와, 그 뒤의 원격 백엔드다. 구멍이 메워진다는 것은 **"경로가 생긴다"** 는 뜻이지
"모든 배포에서 켜진다" 는 뜻이 아니다.

---

## 6. 드리머 · 디라이버 · 리컨사일러 · 레닥션

### 6.1 원격 백엔드는 이것들을 자기가 한다

| 컴포넌트 | 기본 백엔드 | Honcho | Dyad |
|---|---|---|---|
| Deriver (메시지 → 결론) | `LlmDeriver` / `ReActLlmDeriver` | 서버 워커 | 서버 워커 (idle flush 3초) |
| Reconciler (충돌 조정) | `DefaultReconciler` | 서버 (2단 중복 제거) | 서버 (3단 중복 제거 + 이벤트 로그) |
| Dreamer (통합) | `DefaultDreamerEngine` + Quartz | 서버 (`schedule_dream`) | 서버 (`POST /dreams`) |
| Index (벡터) | `KnowledgeStore` 위임 | 서버 | 서버 (pgvector + BM25 + 엔티티) |

IMPORTANT: **원격 백엔드를 고른 배포에서 AIMON 쪽 Deriver / Dreamer / Reconciler 를 함께 켜면 안 된다.**
둘 다 같은 대화에서 결론을 만들고 서로 모르므로, 같은 사실이 두 저장소에 갈라져 쌓이고 어느 쪽이 진실인지
정할 수 없게 된다. 이 배제는 설정 검증으로 강제한다 — `backend` 가 `honcho`/`dyad` 인데
`memory.dreamer.enabled=true` 또는 `memory.reconcilerEnabled=true` 이면 **부팅을 실패시킨다**
(조용히 무시하면 운영자가 켰다고 믿는 것이 돌지 않는다). 스타터가 `redaction=supplied` 인데 빈이 없으면
부팅을 실패시키는 것과 같은 판단이다.

### 6.2 레닥션만은 코어에 남는다 — 그리고 더 중요해진다

IMPORTANT: **원격 백엔드에서 레닥션은 선택이 아니다.** 다만 그 이유를 정확히 적어야 한다 — 초안은
*"기본 백엔드에서는 마스킹하지 않은 메시지가 같은 프로세스 안의 LLM 클라이언트로 갔다"* 고 썼는데
그것은 사실이 아니다. `LlmDialecticEngine` 도 `LlmDeriver` 도 `LlmClient` 를 통해 **외부 LLM API 로**
나간다. 텍스트는 이미 프로세스 밖으로 나가고 있다.

원격 백엔드가 더하는 위험은 "처음으로 밖에 나간다" 가 아니라 **목적지가 하나 늘고, 그 목적지는 LLM
벤더의 데이터 취급 약정 밖에 있다**는 것이다. 메모리 서버는 받은 것을 **영구화**하는 것이 존재
이유이므로, 시크릿이 한 번 들어가면 되돌릴 수 없다는 [`peer-memory.md` §6.5](peer-memory.md) 의
논리가 그대로, 그리고 더 강하게 걸린다.

#### 초안의 계획으로는 보장이 약해진다 — 그것을 먼저 인정한다

초안은 게이트를 "코어의 티어 호출 지점" 에 두고 `ObservationDraft.content` / `MemoryIngestRequest.messages`
의 **javadoc 이 '이미 레닥션을 통과한 텍스트'를 계약으로 명시한다** 고 적은 뒤, 기존 보장이 "그대로
유효하다" 고 했다. **그대로 유효하지 않다.** 오늘의 보장은 javadoc 이 아니라 구현이 만든다.

| | 오늘 | 초안의 계획 |
|---|---|---|
| 강제 수단 | `InMemoryDerivationQueueManager:83` 이 `task.withMessages(messageRedactor.redactAll(...))` 를 **구현 안에서** 부른다. `DerivationQueueManager:9-12` javadoc: *"Redaction is mandatory."* | 호출자가 지켜야 하는 **문서화된 전제조건** |
| 우회 가능성 | 없다 — 큐에 넣는 모든 경로가 같은 게이트를 지난다 | **있다.** `MemoryIngestor` 는 `at.aimon.core.memory` 의 공개 SPI 이므로 애플리케이션이 `peerMemory.ingestor().ingest(raw)` 를 그냥 부를 수 있다 |
| 실패 시 결과 | 마스킹 안 된 텍스트가 같은 프로세스의 LLM 으로 | 마스킹 안 된 텍스트가 **제3자 HTTP 로**. 되돌릴 수 없다 |

[`peer-memory.md` §14.2](peer-memory.md) 가 *"레닥션 게이트를 우회하는 큐 경로를 만들지 말 것"* 이라고
적어 둔 그 금지를, 새 SPI 가 우회 경로를 하나 여는 것으로 위반하게 된다.

#### 그러므로 계약을 뒤집는다 — 게이트는 구현 안으로 들어간다

```
실행 종료 ─▶ ExecutionMemorySink (마스킹하지 않는다)
                    │
                    ▼
       RedactingPeerMemory(delegate, redactionPolicy)  ← ★ 조립이 반드시 씌우는 데코레이터.
                    │   ingest · observe · search · chat 네 티어의    유일한 게이트다
                    │   바깥으로 나가는 텍스트를 전부 마스킹
                    ▼
       MemoryIngestor / ObservationRecorder /
       MemorySearcher / DialecticEngine                ← 여기서부터가 어댑터
```

> **정정 (Step 5 구현 시점).** 이 그림의 초안에는 실행기 이음매에도 `MessageRedactor.redactAll` 이 있었고
> 단계가 ①② 둘이었다. 구현은 **하나만 만들었다** — 그리고 그것이 맞다. 이 절의 논지 자체가
> *"②가 새로 추가되는 것이고, 이것이 강제의 자리다"* 이기 때문이다. 실행기에 게이트를 하나 더 두면
> 멱등이라 해롭지는 않지만, 실행기가 쓰지도 않는 `RedactionPolicy` 를 받아야 하고, 무엇보다 **게이트가
> 둘이 되어 "하나뿐이므로 우회 경로가 없다" 는 보장의 문장이 약해진다.** 아래 "이중 적용은 안전하다"
> 항목도 그에 맞춰 고쳤고, 첫 불릿의 "②" 도 다이어그램의 `★` 를 가리키도록 바꿨다 — 없는 기호를
> 가리키는 문장은 이 정정이 없애려던 바로 그 종류의 잔여물이다.

- **★ 데코레이터가 새로 추가되는 것이고, 이것이 강제의 자리다.** `MemoryAssembly` 는 `RedactionPolicy` 가 있는 한
  `PeerMemory` 를 `RedactingPeerMemory` 로 **감싸서만** 스택에 넘긴다. 감싸지 않은 백엔드가 스택에
  들어가는 경로가 없으므로 게이트는 다시 하나다
- **CHAT 도 감싼다.** `DialecticEngine` 은 기존 인터페이스이므로 데코레이터를 별도 타입
  (`RedactingDialecticEngine`)으로 두고 `RedactingPeerMemory.dialecticEngine()` 이 그것을 돌려준다.
  SNAPSHOT 만 감싸지 않는데, 그 티어의 입력에는 **호출자가 쓴 자유 텍스트가 없기 때문**이다(아래 표)
- 정책이 없으면(스타터 `redaction=none`) 감싸지 않고 `memory-redaction` degradation 이 오른다. 원격
  백엔드에서는 그 조합 자체를 **거절**한다(§9.3)
- 어댑터는 레닥션을 **모른다.** 어댑터마다 게이트를 두면 새 어댑터가 하나 빠뜨리는 순간 보장이 무너지고,
  그 누락은 시크릿이 나간 뒤에야 보인다
- **이중 적용은 안전하다** — `RedactionPolicy.redact` 는 계약상 idempotent 다. 도구가 이미 마스킹한
  텍스트(`ObserveTool` · `MemorySearchTool`)가 데코레이터를 다시 지나고, 기본 백엔드에서는
  `DerivationQueueManager.enqueue` 안에서 세 번째로 적용되는데 전부 같은 이유로 안전하다. 안전하다는
  것이 **더 두라는 뜻은 아니다** — 강제의 자리는 하나여야 그 하나를 지켰는지 확인할 수 있다

#### 마스킹 대상은 넷이다 — 기준은 "밖으로 나가는 텍스트" 다

초안은 이 목록을 **셋**으로 못 박았는데 하나가 빠져 있었다. 기준을 먼저 세운다: **호출자(사람 또는
모델)가 쓴 자유 텍스트가 백엔드로 나가는 자리**가 게이트가 필요한 자리다. 도구의 개수가 기준이
아니다 — 도구 중 둘(`MemoryRecall`·`MemoryChat`)이 오늘 `RedactionPolicy` 참조 0건인데, 그중 하나만
문제다.

| 티어 | 나가는 자유 텍스트 | 오늘 어디서 마스킹되나 |
|---|---|---|
| INGEST | `MemoryIngestRequest.messages` — 대화 전체 | `InMemoryDerivationQueueManager:83` (구현 안, 우회 불가) |
| OBSERVE | `ObservationDraft.content` — 모델이 적어 넣은 사실 | `ObserveTool:152` → `:176` (`redactionPolicy.redact(content)`) |
| SEARCH | `MemorySearchQuery.query` | `MemorySearchTool:158` (`redactionPolicy.redact(query)`) |
| **CHAT** | **`DialecticQuery.question` — 모델이 대화에서 끌어와 작성한 질문** | **아무 데서도.** `MemoryChatTool` 은 `redact`/`RedactionPolicy` 참조가 **0건**이고 생성자가 `MemoryChatTool(DialecticEngine)` 하나뿐이다(`:61`). `question`(`:86`, `input.getRequiredString("question")`)이 그대로 `DialecticQuery.question`(`:104`)이 된다 |
| SNAPSHOT | **없다** | 해당 없음 — 입력이 peer·세션·모드·예산뿐이다 |

마지막 줄이 기준을 증명한다. `MemoryRecallTool` 도 레닥션 참조가 0건이지만 **구멍이 아니다** — 그
도구의 입력 스키마는 `mode`(enum `GLOBAL`/`LOCAL`)와 `max_tokens`(number) 둘뿐이고
(`MemoryRecallTool.createInputSchema`), 호출자가 쓴 텍스트를 밖으로 내보내지 않는다. 읽기만 하는
티어에는 게이트가 필요 없다.

CHAT 은 반대다. Honcho 는 그 문자열을 `DialecticOptions.query`(필수, 최대 10000자)로,
Dyad 는 `ChatRequest(@NotBlank String question, …)`(`Requests.java:52`)로 **본문에 그대로 실어**
원격 서버로 보낸다. 오늘 게이트가 하나도 없으므로 이 자리는 목록에 있는 넷 중 **유일하게 신규 게이트가
필요한 자리**다.

SEARCH 의 게이트는 오늘 `MemorySearchTool`(코어) 안에 있어 원격에서도 즉시 구멍은 아니다. 그래도
`RedactingPeerMemory` 가 `search` 와 `chat` 을 함께 감싸는 이유는 **도구를 거치지 않는 호출자**
(애플리케이션이 `peerMemory.searcher()` / `peerMemory.dialecticEngine()` 을 직접 쓰는 경우)가 남기
때문이다 — §3.2 가 그 둘을 공개 접근자로 올린다.

이 절의 결정은 [`peer-memory.md` §14.2](peer-memory.md) 의 금지 항목을 *"레닥션 게이트를 우회하는 큐
경로나 티어 호출 경로를 만들지 말 것"* 으로 넓히는 것을 함께 요구한다 — **`RedactingPeerMemory` 가
들어가는 커밋(§12 Step 3)에서 함께 고친다.** §0.1·§0.2 의 정본 수정(Step 1)과는 다른 커밋이다:
그 둘은 지금 틀려 있어서 당기는 것이고, 이것은 새 데코레이터가 생겨야 참이 되는 문장이다.

### 6.3 패키지는 옮기지 않는다

`deriver` / `dreamer` / `reconciler` / `index` / `redaction` 다섯 하위 패키지는 **제자리에 남는다.**
§4.2 와 같은 이유이고, 추가로 이 넷은 각자 코어 밖에 실제 구현자·소비자를 갖고 있다.

| 패키지 | 왜 공개로 남아야 하는가 |
|---|---|
| `deriver` | `PostgresDerivationQueueManager` 가 `DerivationQueueManager` 를 구현한다 (모듈 밖) |
| `redaction` | `RedactionPolicy` 가 `MemorySpec` 필드이고 스타터의 `supplied` 값이 그 빈을 읽는다 |
| `index` | `IndexedObservationStore` 가 Mongo/Postgres 백엔드의 `semanticSearch` 복원 경로다 |
| `dialectic` | `DialecticEngine` 이 **티어 인터페이스 그 자체**다 |
| `dreamer`, `reconciler` | CLI 가 구체 클래스 5종을 직접 `new` 한다 (§4.2) |

격하는 문서와 참조 방향으로 표현하고(§4.1), 물리적 이동은 CLI 조립을 코어 팩토리로 옮긴 **뒤에** 별개
변경으로 검토한다.

---

## 7. 수집(ingest) — 오늘 없는 이음매

### 7.1 사실 확인

| 경로 | 지금 무엇이 수집을 부르는가 |
|---|---|
| `aimon-cli` | `TeardownPhase.MEMORY_FINAL_DERIVATION` — **REPL 프로세스가 끝날 때 딱 한 번**, 전사 전체를 `DerivationTask` 하나로 큐에 넣는다 |
| `aimon-spring-boot-starter` | **없다.** `MemoryAssembly` 가 `memory-write-path` degradation 을 올린다 |
| 그 외 (`aimon-core` 를 직접 조립하는 애플리케이션) | 없다. `ObserveTool` 로 모델이 직접 넣는 것이 유일 |

즉 오늘 AIMON 의 메모리는 **프로세스 수명 단위로 한 번 쓰고 계속 읽는** 모양이다. 그 모양은 CLI 에서는
그럭저럭 동작하지만(세션이 하나뿐이다), 원격 백엔드에서는 성립하지 않는다 — Honcho·Dyad 는
**메시지 스트림을 계속 먹는 것**을 전제로 배치·게이팅·idle flush 를 설계했다.

### 7.2 제안 — 실행기의 실행 종료 이음매

`MemoryContextProvider` 가 프롬프트 조립 지점에 꽂혀 있는 것과 **대칭**으로, 실행이 끝나는 지점에
`MemoryIngestor` 를 꽂는다.

IMPORTANT: `turn` 이 아니라 **`execution`** 이다. 이 이음매는 세션의 턴에서도, 세션이 없는 실행
(서브에이전트 포크·스킬 포크·rewake 리플레이·스케줄 루틴)에서도 같은 자리에 선다 —
두 경로가 공유하는 것을 말할 때는 `turn` 이라고 쓰지 않는다
([`glossary.md` §4](../../overview/glossary.md)).

```
OrcaAgentExecutorFactory.withMemoryContextProvider(...)   ← 이미 있다 (읽기)
OrcaAgentExecutorFactory.withExecutionMemorySink(...)     ← 추가 (쓰기)
```

이름이 `withMemoryIngestor` 가 아닌 이유: 실행기는 peer 를 모른다. `MemoryIngestor` 를 그대로 받으면
실행기가 `PeerView` 를 만들어야 하고, 그것은 `MemoryPeerResolver` 가 이미 하는 일을 읽기 쪽과 쓰기 쪽에서
따로 하게 만든다. 그래서 실행기는 `ExecutionMemorySink` 를 받고, 기본 구현
(`IngestingExecutionMemorySink`)이 **읽기와 같은 resolver 로** peer 를 풀어 `MemoryIngestor` 를 부른다 —
두 이음매가 서로 다른 peer 를 답하는 상태가 생기지 않는다. 실행기가 넘기는 값
(`ExecutionMemoryUpdate`)은 `MemoryContextRequest` 와 같은 모양(sessionId + principal)에 메시지가 붙은
것이다.

계약:

- **실행이 끝난 뒤** 그 실행에서 새로 추가된 메시지만 넘긴다 (전사 전체가 아니다 — 매번 전체를 보내면
  같은 메시지를 N번 보낸다). **그 델타를 얻는 primitive 는 오늘 없다** — 아래를 먼저 읽는다
- **fire-and-forget.** 수집 실패가 실행을 실패시키지 않는다. 도구 계약과 같은 정신이다
- **레닥션 게이트를 반드시 경유한다** (§6.2)
- **포크 수집은 기본 off** 다. 포크에는 `SessionId` 가 없어 원격의 `sessions/{s}/messages` 에 실을
  세션 이름이 없고, 포크의 대화가 부모 세션 peer 의 사실이라는 보장도 없다. 켤 수 있게 두되 켤 때는
  `invokingSessionId` 를 세션 이름으로 쓴다

#### 델타를 어떻게 얻는가 — 오늘 primitive 가 없고, compaction 이 인덱스를 무효화한다

IMPORTANT: 위 계약의 첫 줄("그 실행에서 새로 추가된 메시지만")은 **오늘 만들 수 있는 방법이 없다.**

- `OrcaAgentExecutionResult` 의 접근자 열 개(`:417-534`)에 실행 단위 델타가 없다.
  `getConversationHistory():441` 도 `getSnapshot():429` 도 **전체 이력**이다
- `TranscriptBuffer` 의 공개 API 에도 "이 실행이 추가한 것" 을 뽑는 메서드가 없다
- 남는 방법은 실행 전후 `size()` 를 인덱스로 잡는 것인데, **실행 도중 이력이 통째로 교체된다** —
  `OrcaAgentExecutor:2882` 의 `scope.transcriptBuffer.replaceWith(recovered)`(프롬프트 크기 복구)와
  `DefaultCompactionEngine:253` 의 `memory.replaceWith(rebuilt)`(컴팩션). 그 순간 인덱스는 무의미해진다

이 함정은 **트리가 이미 만나서 풀어 둔 것**이다. `SessionTranscript:48-56` 이 `SessionRewindPoint` 를
전사 **안**에 두는 이유를 이렇게 적는다(인용문은 `:54-55`) — *"compaction rewrites the history through `replaceWith`, and a
point that survived that would index into a history that no longer exists"*. 그래서 rewrite 가 그 점을
함께 버린다.

수집 워터마크도 같은 성질을 요구하므로 선택지는 둘이다.

| 방안 | 모양 | 대가 |
|---|---|---|
| (a) `SessionRewindPoint` 와 **같은 기계** — 수집 워터마크를 전사 안에 두고 `replaceWith` 가 함께 버린다 | 이미 있는 패턴을 재사용. 구현이 작다 | compaction 이 일어난 실행은 델타를 못 낸다. 그때 무엇을 보낼지(아무것도 / 컴팩션 요약 / 전체)를 정해야 한다 |
| (b) **메시지 단위 id 워터마크** — 마지막으로 보낸 메시지 id 를 백엔드별로 기억한다 | rewrite 에 견딘다 | `Message` 에 안정적 id 가 필요하고, 컴팩션이 메시지를 합치면 그 id 가 사라진다 |

#### 확정: **(a)** — 전사 안의 워터마크. (b) 는 오늘 만들 수 없다

**Step 5 가 (a) 를 골랐다.** 고른 이유는 취향이 아니라 (b) 가 성립하지 않기 때문이다 —
`at.aimon.core.llm.Message` 에는 **안정적인 id 가 없다**(`grep -n "getId" Message.java` 는
`ToolUse.getId()` 한 건만 반환한다). (b) 를 하려면 `Message` 에 id 를 더해야 하고, 그 타입은
전사에 영속되므로 세션 레코드의 와이어 포맷이 함께 넓어진다 — 메모리 설계가 건드리기로 한 범위가
아니고, [`frozen-names.md`](../../migration/frozen-names.md) 가 지키는 경계 바로 옆이다.

구현은 트리의 선례를 그대로 복제한다. `TranscriptBuffer.markIngestPoint()` / `messagesSinceIngestMark()`
가 `beginTurn` / `rewindPoint` 와 **같은 자리에서 세워지고 같은 자리에서 버려진다** —
`replaceWith` 가 둘을 함께 버리고, `clear()` 도 그렇다. 워터마크는 **영속하지 않는다**: 한 실행 안에서
세워지고 한 실행 안에서 읽히므로 재시작이 복원할 것이 없고, 수명이 저장 한 번보다 짧은 값을 위해
와이어 포맷을 넓히는 것은 남는 장사가 아니다.

**compaction 이 일어난 실행에서는 아무것도 보내지 않는다.** 세 선택지의 대가를 나란히 놓으면 이것이
가장 싸다.

| 그때 보낼 것 | 대가 |
|---|---|
| **아무것도** (채택) | 그 실행의 메시지가 메모리에 안 들어간다. 다음 실행이 다시 마크하고 스트림은 이어진다. compaction 은 세션당 드물게 일어나므로 손실은 실행 하나 |
| 컴팩션 요약 | 요약을 대화인 척 먹인다. 디라이버가 **패러프레이즈의 패러프레이즈**에서 사실을 뽑는다 |
| 전사 전체 | 이미 수집된 메시지를 다시 보낸다. 원격 백엔드가 중복 제거로 저장은 막아도 **추출 LLM 호출 비용은 이미 발생한** 뒤다 — 실패가 조용하고 비싸다 |

이 손실은 `session-end` 모드에는 **적용되지 않는다**. 그 모드는 델타를 쓰지 않고 세션이 닫힐 때 전사를
통째로 한 번 넘기므로, 델타 문제 자체가 없다. 그리고 그것이 CLI 의 기본값이다 — 델타의 대가를 지는 것은
`execution-end` 를 명시적으로 고른 배포뿐이다.

Step 5 의 인수 조건 ①("같은 메시지가 두 번 안 간다")은 이제 검증 가능한 문장이다: 마크는 실행마다
전진하고(연속한 두 실행의 델타가 겹치지 않는다), rewrite 된 실행은 빈 델타를 낸다.

#### workspace 와 observer 는 어디서 오는가 — per-caller 에서는 오지 않는다

IMPORTANT: `MemoryIngestRequest` 는 `workspace` 와 `observer` 를 요구하는데, **쓰기 쪽에는
`MemoryPeerResolver` 의 대응물이 없다.** 읽기는 `MemoryContextRequest`(sessionId + principal)를
`MemoryPeerResolver` 에 넘겨 peer 를 얻지만, 실행 종료 이음매에는 그 전략이 없다.

| 조립 모양 | 해석 방법 | INGEST 능력 |
|---|---|---|
| **고정 peer** (`MemorySpec.forPeer`) | 조립 시점에 묶는다. CLI 가 오늘 하는 것과 같다 — `buildMemoryFinalDerivation:1073-1081` 이 `memoryWiring.workspace` / `.observer` 를 클로저에 가둔다 | **켜진다** |
| **per-caller** (`MemorySpec.perCaller`) | 없다 | **꺼진다.** `memory-ingest` degradation 이 오른다 |

per-caller 에서 불가능한 이유는 도구 쪽에서 이미 확인된 것과 **같은 이유**다 —
`MemoryAssembly:94-97` 이 *"The enrichment info a `ToolContextEnricher` receives carries a session and
an execution but **no principal**, so there is nothing to resolve a per-call observer from"* 이라고
적어 둔 그 seam 이 실행 종료 이음매에도 그대로 없다. §5.2 가 도구에 대해 `memory-tools` degradation 을
올리는 자리에서 INGEST 도 같이 꺼진다고 말하지 않으면, 그 배포는 "메모리가 켜졌는데 아무것도 쌓이지
않는" 상태를 진단할 근거를 잃는다.

이 seam 을 넓히는 것 — 실행에 principal 을 실어 per-caller 쓰기를 가능하게 하는 것 — 은 이 설계의
범위 밖이다(§13).

CLI 의 종료 시점 수집은 **삭제하지 않는다.** 설정으로 고른다 (`ingest: off | execution-end |
session-end`), 기본은 기존 동작인 `session-end` 다 — 기존 사용자의 LLM 호출 횟수가 조용히 늘지 않게
하려는 것이다. `session-end` 는 라이브 세션이 닫힐 때 전사를 한 번에 넘기며, CLI 에서는 그 순간이
지금의 `TeardownPhase.MEMORY_FINAL_DERIVATION` 과 같다. 원격 백엔드는 `execution-end` 를 권장값으로
문서화한다 — 그래야 Dyad 의 idle flush 와 `?wait=derive` 가 비로소 의미를 갖는다.

---

## 8. 모듈 배치

### 8.1 새 모듈 셋

| 모듈 | 성격 | 배포 | 의존 |
|---|---|---|---|
| `aimon-memory-honcho` | Honcho v3 HTTP 어댑터 | Maven Central | `aimon-core`, `java.net.http`(JDK), Jackson |
| `aimon-memory-dyad` | Dyad v1 HTTP 어댑터 | Maven Central | 같음 |
| `aimon-memory-testkit` | 다섯 티어의 **공유 계약 테스트** | **미배포** | `aimon-core`, JUnit |

HTTP 클라이언트는 JDK 의 `java.net.http.HttpClient` 를 쓴다 — 코어에 이미 선례가 있고
(`skill.hook.declarative.HttpActionExecutor`), OkHttp 를 끌어오면 어댑터 하나 때문에 배포 의존성이
늘어난다. 스트리밍(SSE)은 `HttpResponse.BodyHandlers.ofLines()` 로 처리해
`at.aimon.core.llm.streaming.LlmStreamSink` 에 흘린다 — 새 reactive 스택은 도입하지 않는다
([`peer-memory.md` §6.2](peer-memory.md) 의 금지 사항).

`aimon-memory-testkit` 은 `aimon-filesystem-testkit` / `aimon-session-testkit` 의 선례를 그대로
따른다. **이것이 "두 백엔드가 같은 답을 한다" 를 확인할 수 있는 유일한 장치**다 — 다섯 티어의 계약
(빈 결과 vs 예외, 예산 초과 시 truncated, 세션 없는 질의의 의미, 레닥션 통과 전제)을 한 곳에 적고 세
구현이 그것을 돈다. 그중 넷은 **능력 협상 자체의 계약**이다.

1. *"내놓은 티어는 답한다 — `UnsupportedOperationException` 을 던지지 않는다."* §4.1 이
   `semanticSearch` 를 못 하는 스토어에 대해 적은 계약이 여기서 강제된다
2. *"SEARCH 결과는 언제나 관련도 내림차순이다"* — 점수를 못 내는 백엔드도 이것은 지킨다(§3.3)
3. *"`ranksByScore()=false` 인 백엔드는 `minScore>0` 을 **조용히 무시하지 않는다**"* — 거절하거나,
   `storesConfidence()` 처럼 호출 전에 읽혀야 한다
4. *"`narrowsBySession()=false` 인 백엔드는 세션 id 를 **조용히 무시하지 않는다**"* — 3번과 같은 규칙을
   질의의 다른 좁힘 축에 적용한 것이다. 둘을 한 항으로 합치지 않고 나눠 적는 이유는 testkit 이 각각에
   케이스를 갖고, 백엔드가 한쪽만 지키는 것이 실제로 가능하기 때문이다

`aimon-memory-{file,mongodb,postgres}` 는 여기에 참여하지 않는다 — 그들은 티어가 아니라 저장소를
구현하기 때문이다.

### 8.2 `settings.gradle.kts` 와 BOM

- `settings.gradle.kts` 의 `include(...)` 와 `project(...).projectDir` 양쪽에 세 줄씩 추가한다
- **BOM 은 손대지 않는다.** `aimon-bom` 은 `com.vanniktech.maven.publish` 플러그인을 적용한
  서브프로젝트를 **구성(configuration) 시점에 열거**해서 constraint 를 만든다 (`publishedProjects()` 가
  `evaluationDependsOn` 으로 형제 프로젝트를 먼저 평가시킨다). 어댑터 둘은 `aimon.publishable` 을 적용하고
  `gradle.properties` 에 `POM_ARTIFACT_ID` 등을 선언하면 자동으로 들어가고, testkit 은 적용하지
  않으므로 자동으로 빠진다. BOM 의 자체 검증 태스크가 "플러그인만 적용하고 좌표를 선언하지 않은" 실수를
  잡는다
- 모듈 이름의 접미사가 뜻하는 것이 **바뀐다** — 지금까지 `aimon-memory-<X>` 의 `X` 는 저장소 기술
  (`file`/`postgres`/`mongodb`)이었으나 앞으로는 저장소 기술 **또는** 원격 서비스다. 사용자가 고르는
  축(`memory.backend`)이 하나이므로 접두어를 나누지 않는 편이 낫다 — 다만 이 사실을
  [메모리 사용 가이드](../../features/memory/memory-usage-guide.md) §9 의 백엔드 표에 명시한다

### 8.3 의존성 방향

```
                     aimon-core
              (at.aimon.core.memory — 다섯 티어 SPI + StoreBackedPeerMemory)
                          ▲
    ┌─────────────────────┼─────────────────────┬──────────────────┐
    │                     │                     │                  │
aimon-memory-file   aimon-memory-postgres  aimon-memory-honcho  aimon-memory-dyad
aimon-memory-mongodb                       (PeerMemory 구현)    (PeerMemory 구현)
(저장소 구현 — 티어를 구현하지 않는다)
```

왼쪽 셋과 오른쪽 둘은 **서로 다른 것을 구현하며 서로를 모른다.** 이것이 §4.1 의 격하가 그림으로 보이는
자리다.

---

## 9. 설정 표면

### 9.1 `MemorySpec` (`aimon-bootstrap`)

두 진입점(`forPeer` / `perCaller`)과 peer 결정의 강제 선택은 **그대로 둔다** — 그 결정은 백엔드와
무관하고, 그것을 없애면 "식별된 요청은 A 의 메모리를, 익명 요청은 B 의 메모리를 읽는" 상태가 다시
열린다.

바뀌는 것은 재료 하나다.

```java
// 새 경로 — 백엔드를 통째로 준다
MemorySpec.forPeer(workspace, peer)
        .peerMemory(backend)              // ← 추가
        .injectionMode(SUMMARY_ONLY)
        .maxTokens(0)
        .redactionPolicy(policy)
        .build();

// 기존 경로 — 그대로 동작한다 (내부에서 StoreBackedPeerMemory 로 접힌다)
MemorySpec.forPeer(workspace, peer)
        .representationStore(reps)
        .observationStore(obs)
        .build();
```

`peerMemory(...)` 와 스토어 setter 는 **상호 배타**이며 `build()` 가 거절한다. `AimonStackSpec` 이 이미
`MemorySpec` + `ExecutorSpec.memoryContextProvider` 동시 지정을 같은 방식으로 거절하고 있으므로 새 관례가
아니다.

`dialecticEngine` 을 담을 자리는 따로 만들지 **않는다** — `PeerMemory` 가 이미 그것을 티어로 들고
있으므로 `peerMemory(...)` 경로에서는 CHAT 이 자동으로 들어온다. §5.3 의 구멍(스타터에서 `MemoryChat`
이 등록되지 않는 것)은 그렇게 메워진다.

**기존 두 불변식은 넓혀야 한다.** `MemorySpec:79-89` 는 오늘 두 가지를 던지는데, 둘 다 `PeerMemory`
경로를 모른다.

| 오늘의 검사 | `peerMemory(...)` 경로에서 무슨 일이 나는가 | 처분 |
|---|---|---|
| 스토어가 하나도 없으면 *"A memory spec needs at least one store"* | `peerMemory` 만 준 스펙이 **여기 걸린다** | 조건을 *"스토어 **또는** `PeerMemory`"* 로 넓힌다 |
| per-caller 인데 representation store 가 없으면 *"Per-caller memory needs a representation store"* | per-caller + `peerMemory` 가 **여기 걸린다** | 조건을 *"per-caller 는 SNAPSHOT 능력을 요구한다"* 로 바꾼다 — 뜻은 같고 표현만 티어 어휘로 옮긴 것이다 |
| `AimonStackSpec:122-130` — `MemorySpec`(representation store 있음) + `ExecutorSpec.memoryContextProvider` 동시 지정을 거절 | 가드가 `getRepresentationStore().isPresent()` 에 걸려 있어 **`peerMemory` 만 준 스펙에는 발화하지 않는다.** 그런데 §5.2 는 SNAPSHOT 능력이 있으면 `SnapshotMemoryContextProvider` 를 설치하므로 주입 provider 가 둘이 되고 하나가 조용히 버려진다 — 그 가드의 주석이 서술하는 실패 그대로다 | 조건을 *"`MemorySpec` 이 주입 provider 를 만들 수 있으면(representation store **또는** SNAPSHOT 능력)"* 으로 넓힌다 |

세 번째 행은 CLI 에는 닿지 않는다 — §5.0 대로 CLI 가 `ExecutorSpec.memoryContextProvider` 를 안 쓰게
되기 때문이다. 노출되는 것은 `AimonStackSpec` 을 직접 조립하는 애플리케이션이다. 그래서 Step 4 의
인수 조건 ③("상호 배타 규칙을 고치지 않고 통과한다")은 **CLI 에 대해서만** 참이며, 가드 자체를 넓히는
것은 Step 3 의 일이다.

세 메시지의 **의도는 그대로 유지된다** — "설정된 것처럼 보이는데 아무것도 배선하지 않는 스펙" 을
거절하는 것. Step 3 의 인수 조건 *"기존 조립 경로 동작 불변"* 은 이 변경을 덮지 못하므로 별도 조건으로
적는다.

### 9.2 CLI — `memory` 블록

IMPORTANT: **CLI 의 키는 camelCase 다.** `MemoryConfig` 의 필드가 전부
`@JsonProperty("workspaceId")` · `("peerId")` · `("peerName")` · `("storagePath")` · `("backend")` ·
`("reconcilerEnabled")` 로 선언되어 있고 네이밍 전략 설정은 없다. kebab-case 는 **스타터 프로퍼티**
(`aimon.memory.workspace-id`)의 규약이며 두 표면은 섞이지 않는다.

이 문서의 앞선 개정은 kebab 을 적은 것을 정정하면서 **그 정정의 근거를 또 틀렸다.** 초안 2 는
*"세 필드가 null 이 되어 `MemoryConfig.isEnabled()` 가 false 를 돌려주고 메모리가 경고 없이 꺼진다"*
고 적었는데, 실제로는 **부팅이 실패한다.** 프로젝트가 쓰는 jackson 2.22.2 + snakeyaml 로 같은 모양을
실행해 확인했다.

```
Unrecognized field "workspace-id" (class T$M), not marked as ignorable
  (3 known properties: "workspaceId", "peerId", "storagePath")
→ com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
```

`CliConfigLoader:36` 은 `new ObjectMapper(new YAMLFactory())` 를 그대로 쓰고
`FAIL_ON_UNKNOWN_PROPERTIES` 를 끄지 않으며, CLI 설정 클래스 어디에도 `@JsonIgnoreProperties` 가
없다(0건). `UnrecognizedPropertyException` 은 `JsonMappingException` 의 하위이므로
`CliConfigLoader:64-65` 가 그것을 `ConfigurationException("Invalid configuration structure in: …")` 로
감싸 **던진다** (`:63` 은 `JsonParseException` 쪽의 *"Invalid YAML syntax"* 다). 즉 실패 모드는 조용한 축소가 아니라 **시끄러운 정지**다.

결론("CLI 는 camelCase 다")은 두 번 다 옳았고, 틀린 것은 두 번 다 근거였다. 이번에는 돌려서
확인했다.

```yaml
memory:
  workspaceId: ops
  peerId: alice
  peerName: Alice
  backend: file              # file(기본) | in-memory | honcho | dyad
  storagePath: ~/.aimon/memory/representations.jsonl   # 필수. file 백엔드가 쓴다
  reconcilerEnabled: true                              # 기본 백엔드 전용
  dreamer:                                             # 기본 백엔드 전용
    enabled: true
  ingest: session-end        # off | session-end(기본, 기존 동작) | execution-end
  remote:                    # backend 가 honcho|dyad 일 때만 읽는다
    baseUrl: http://localhost:8000
    token: ${MEMORY_TOKEN}
    timeout: 10s
    observed: alice          # 관찰 대상 peer 이름. 미지정 시 peerId
    waitForDerivation: false # dyad 전용 (?wait=derive)
```

새로 더하는 키(`ingest`, `remote.*`)도 CLI 규약인 camelCase 로 맞춘다 — 이 문서는 CLI 설정 키의
개명을 제안하지 않는다. `workspaceId` · `peerId` · `storagePath` 세 필드가 모두 채워져야 메모리가
켜지는 현재 동작(`MemoryConfig.isEnabled()`)도 그대로다. **원격 백엔드에서도 `storagePath` 가 필수로
남는 것은 오늘의 활성화 조건이 그렇기 때문이며**, 그것을 푸는 것(원격일 때 `storagePath` 없이도 켜지게
하는 것)은 Step 9 에서 함께 처리한다 — `isEnabled()` 를 백엔드별로 갈라야 하므로 설정 표면 변경이다.

- `backend` 의 기본값은 **`file` 그대로**다. 모르는 값이 `file` 로 떨어지는 현재 동작도 그대로 —
  다만 오늘 그 경고는 **관찰 스토어에서만** 나온다(`createObservationStore:967-970` 은
  `"unknown backend '...', falling back to file for observations"` 를 내지만
  `createRepresentationStore:929-943` 은 `in-memory` 가 아니면 **말없이** `FileRepresentationStore` 로
  간다). 원격 값이 오타 났을 때 절반만 경고하는 것은 §5.2 가 없애려는 실패 모드이므로, Step 9 에서
  양쪽을 맞춘다
- `remote` 블록이 비었는데 `backend` 가 원격이면 **부팅 실패**. `baseUrl` 없는 HTTP 백엔드는 조용히
  실패할 방법이 없다
- 반대로 `backend` 가 `file`/`in-memory` 인데 `remote` 가 채워져 있으면 **부팅 실패** — 스타터의
  `refuseOrphanedStores` 와 같은 이유다. 설정했는데 안 읽히는 것이 가장 나쁘다
- `dreamer.enabled` / `reconcilerEnabled` 가 원격 백엔드와 함께 켜져 있으면 **부팅 실패** (§6.1).
  두 키 모두 CLI 표면에만 있다 — 스타터의 `AimonProperties.Memory` 필드는 일곱 개(`backend` ·
  `workspaceId` · `peerMode` · `peerId` · `injectionMode` · `maxTokens` · `redaction`)뿐이고 reconciler
  프로퍼티는 **아예 없다**

### 9.3 스타터 — `aimon.memory.*`

| 키 | 값 | 변화 |
|---|---|---|
| `backend` | `none`(기본) · `in-memory` · `supplied` · **`honcho`** · **`dyad`** | 값 두 개 추가. 기본값 불변 |
| `workspace-id` | 문자열 | 불변 — 백엔드 지정 시 필수 |
| `peer-mode` / `peer-id` | 불변 | |
| `injection-mode` / `max-tokens` | 불변 | |
| `redaction` | 불변 (기본 `default`) | 원격 백엔드에서 `none` 은 **거절**한다 (§6.2) |
| `ingest` | `off`(기본) · `execution-end` · `session-end` | **신규.** 기본 `off` 가 오늘의 동작이다 |
| `honcho.base-url` / `.token` / `.timeout` | | **신규** |
| `dyad.base-url` / `.token` / `.timeout` / `.wait-for-derivation` | | **신규** |

`supplied` 는 뜻이 **하나 늘어난다** — 애플리케이션이 `PeerMemory` 빈을 선언하거나, 기존처럼 스토어
빈을 선언한다. 둘 다 선언하면 거절한다. 스타터가 이미 갖고 있는 거절 규칙 셋(고아 스토어, 정책 선언
불일치, per-caller + observation store 단독)과 같은 문장 형태로 쓴다.

### 9.4 기존 사용자가 아무것도 안 고쳐도 그대로 도는가

| 경로 | 답 |
|---|---|
| CLI yaml | **그대로 돈다.** 키는 camelCase 그대로(§9.2), 기본값 `file`, `ingest` 기본이 기존 동작(`session-end`) |
| 스타터 프로퍼티 | **그대로 돈다.** 기본값 `none`, `ingest` 기본 `off` |
| `MemorySpec` 프로그램 조립 | **그대로 돈다.** 스토어 setter 유지 |
| `at.aimon.core.memory` 를 직접 쓰는 코드 | **한 곳 깨진다** — `RepresentationMemoryContextProvider` 개명 (§11.1) |
| `at.aimon.bootstrap.assemble` 을 직접 쓰는 코드 | **한 곳 깨진다** — `MemoryAssembly.CAPABILITY_WRITE_PATH` 상수 (§11.1) |
| `aimon-memory-{file,mongodb,postgres}` 사용자 | **그대로 돈다.** 저장 포맷·DDL·컬렉션 이름 전부 불변 |
| **종료 순서에 의존하는 배포** | **바뀐다** — `TeardownPhase` 의 메모리 블록이 `SESSIONS` 뒤로 옮겨진다 (§3.6, §11.3) |

---

## 10. 라이선스 경계

| 대상 | 라이선스 | 이 설계에서의 취급 |
|---|---|---|
| Honcho **서버** (`src/`, FastAPI) | **AGPL-3.0** | 이 저장소에 들어오지 않는다. 운영자가 자기 인프라에 띄운다 |
| Honcho **SDK** (`sdks/`) · OpenAPI 스펙 | Apache-2.0 | `aimon-memory-honcho` 가 참조하는 **유일한** 자료 |
| Dyad | 별도 저장소(`/Users/kangwoo/Workspaces/research/memory/dyad`), 자체 클린룸 구현 | 서버는 그쪽에 남고 여기에는 HTTP 어댑터만 |
| `aimon-memory-honcho` | 이 저장소의 라이선스 | 아래 근거로 AGPL 전염되지 않는다 |

**HTTP 클라이언트는 파생저작물이 아니다.** AGPL-3.0 §13(네트워크 상호작용 조항)은 **개작된 프로그램을
네트워크로 서비스하는 자**에게 소스 제공 의무를 지운다. 그 프로그램을 **이용하는 클라이언트**는 개작물이
아니며 §13 의 대상도 아니다. 어댑터가 참조하는 것은 인터페이스 명세(OpenAPI)와 Apache-2.0 SDK 뿐이다.

IMPORTANT: 다음을 하면 경계가 깨진다.

- Honcho 서버의 **프롬프트 문자열**을 옮기는 것 — dialectic 시스템 프롬프트, deriver 추출 프롬프트,
  표현 렌더 포맷의 문구
- 서버의 **알고리즘 코드**를 옮기는 것 — `context()` 의 토큰 예산 분배식, 중복 제거 유사도 임계값 로직
- 어댑터 개발 중 **서버 소스를 열어 놓고 대조**하는 것 (Dyad 의 ADR 0005 가 같은 이유로 원본 저장소를
  작업 트리에서 배제했다)

어댑터가 해야 하는 것은 요청을 만들고 응답을 파싱하는 것뿐이며, 그 둘은 OpenAPI 스펙이 전부 기술한다.
**이 문단을 `aimon-memory-honcho` 의 `package-info.java` 에 요약해 둔다** — 문서에만 적힌 경계는
다음 사람이 읽지 않는다.

배포 형태의 함의도 적어 둔다: AIMON 을 SaaS 로 제공하면서 그 뒤에 Honcho 서버를 띄우면, **Honcho 를
개작하지 않는 한** §13 의무는 발생하지 않는다. 개작하면 발생한다 — 그 판단은 운영자의 것이고 이 문서는
경계만 표시한다.

---

## 11. 호환성과 마이그레이션

[`api-stability.md`](../../project/api-stability.md) 의 `0.x` 약속: 마이너 올림은 깰 수 있고,
변경은 `CHANGELOG.md` 에 전부 기록하며, **개명은 [`rename-maps.md`](../../migration/rename-maps.md) 에
표를 더한다.**

### 11.1 깨는 이름 변경 — 둘이다

초안은 "정확히 하나" 라고 적었으나 **공개 상수 하나를 빠뜨렸다.**

| 모듈 | 옛 이름 | 새 이름 | 이유 |
|---|---|---|---|
| `aimon-core` | `RepresentationMemoryContextProvider` | `SnapshotMemoryContextProvider` | 원격 백엔드에는 `Representation` 이 없다. 이름이 그 위에 선 것을 잘못 말한다 |
| `aimon-bootstrap` | `MemoryAssembly.CAPABILITY_WRITE_PATH` (값 `"memory-write-path"`) | `MemoryAssembly.CAPABILITY_INGEST` (값 `"memory-ingest"`) | 쓰기 경로가 하나의 능력(`INGEST`)으로 이름을 얻었다(§5.2) |

둘 다 `rename-maps.md` 에 들어간다. 두 번째가 공개 API 인 근거: `aimon-bootstrap/build.gradle.kts` 가
`id("aimon.publishable")` 을 적용하므로 `at.aimon.bootstrap.assemble` 은 배포 모듈의 공개 패키지다
([`api-stability.md` §2](../../project/api-stability.md) — *"그 외 모듈의 `at.aimon.<module>` | 해당
모듈의 공개 API"*). 상수 이름뿐 아니라 **값**도 바뀐다. 그 값이 로그 문구가 아니라 `stack.degradations().has(...)` 로
**조회되는 키**이기 때문에 공개 API 로 취급한다 — 다만 근거를 정확히 적어야 한다: 트리 안에서
`"memory-write-path"` **리터럴**은 선언부(`MemoryAssembly:37`) 한 곳뿐이고, 인용되는 두 테스트
(`AimonStackBuilderTest:658,685` · `MemoryAssemblyTest:101,139`)는 전부 상수
`MemoryAssembly.CAPABILITY_WRITE_PATH` 를 참조한다. 즉 **트리 안에서는 이름과 값을 함께 바꿔도 소스
호환**이며, 깨지는 것은 리터럴로 조회하는 **외부** 코드다. 그 가능성 때문에 공개 API 로 세는 것이지,
트리 안에 증거가 있어서가 아니다.

#### degradation 키는 동결 대상인가 — 아니다

[`api-stability.md` §4](../../project/api-stability.md) 의 동결 대상은 **영속 필드·테이블·채널·와이어
키**다. degradation 키는 프로세스가 살아 있는 동안만 존재하고 저장되지 않으므로 그 목록에 들지 않는다.
다만 프로그램이 조회하는 값이므로 **로그 문구와 달리 공개 API 로 취급**하고 여기에 적는다.

새로 생기는 키는 넷이다 — `memory-snapshot` · `memory-search` · `memory-chat` · `memory-observe`
(§5.2). 기존 `memory-tools` 와 `memory-redaction` 은 이름과 값이 그대로다.

이 프로젝트의 관례대로 어댑터를 남기지 않고 한 번에 옮긴다 — `0.x` 에서는 지키지 않을 유예 기간을
표시하는 것보다 정직하다(§5 of api-stability).

### 11.2 깨지지 않는 것 — 그리고 왜

| 표면 | 판정 |
|---|---|
| `ObservationStore` / `RepresentationStore` / `WorkspaceStore` | **불변.** 시그니처 한 줄도 안 바뀐다 |
| `aimon-memory-{file,mongodb,postgres}` | **불변.** DDL·컬렉션·키 prefix 전부 동결 (§4 of api-stability) |
| `MemoryContextProvider` / `MemoryContextRequest` / `MemoryPeerResolver` | **불변** |
| `DialecticEngine` / `DialecticQuery` / `DialecticResponse` | **불변** |
| 도구 4종의 `TOOL_NAME` · `ToolContext` 키 | **불변** |
| 도구 4종의 **입력 스키마** | **기존 사용자에게는 불변.** 단 한 자리가 백엔드에 따라 달라진다 — `ObserveTool` 의 `confidence` 파라미터는 백엔드가 `storesConfidence()=false` 일 때 스키마에서 빠진다(§2.2). 기본 백엔드는 `true` 이므로 오늘 도는 배포의 스키마는 그대로다 |
| 도구 **3종**의 **생성자** | **바뀐다.** 스토어를 받는 생성자가 사라지고 남는 생성자는 전부 티어를 받는다. 스토어 경로는 이름 있는 팩토리(`MemoryRecallTool.overStore(...)` 등)다. `RedactionPolicy` 유무 오버로드는 그대로 — 모호했던 축이 아니다. `MemoryChatTool` 은 `DialecticEngine` 을 받으므로 애초에 잃을 스토어 생성자가 없다 — 아래 참조 |
| `OrcaMemoryToolProvider` 생성자 | 바뀐다. **내부 패키지**(`agent.impl.orca.tool`)이므로 공개 API 가 아니다 (§2 of api-stability) |
| `MemorySpec` | 순수 추가 |

스토어 생성자를 편의 생성자로 남기려던 계획은 폐기했다. 남기면 스토어와 티어가 **서로의 오버로드**가
되는데, 이것은 두 가지를 뜻한다. 첫째로 `null` 리터럴을 넘기는 호출자에게 컴파일이 모호해진다. 둘째가
더 중요하다 — 오버로드는 "이 둘은 바꿔 넣을 수 있는 것" 이라고 읽히고, 그것이 정확히 이 이음매가
부정하려는 명제다. `SnapshotMemoryContextProvider.readerOver(...)` 는 같은 이유로 이미 이름 있는
팩토리를 골랐으므로(§11.1), 스토어 생성자를 가진 도구 셋만 반대 선택을 하면 한 PR 안에서 규칙이
갈린다. 고도는 오버로드 해석이 아니라 **이름**이 말한다.

### 11.3 이름이 아니라 **동작**이 바뀌는 것

#### `TeardownPhase` 선언 순서

§3.6 이 메모리 phase 블록을 `CHECKPOINTS` 뒤로 옮긴다. `TeardownPhase` 는 `at.aimon.bootstrap` 의
공개 enum 이고 `TeardownRegistry#closeAll()` 이 **선언 순서**로 훑으므로, 이것은 개명이 아니라
**종료 순서의 동작 변경**이다 — `rename-maps.md` 가 아니라 `CHANGELOG.md` 항목이다. `ordinal()` 에
의존하는 코드는 트리 안에 없으나(`TeardownPhase` 는 키로만 쓰인다) 외부 코드가 그럴 수 있으므로
적어 둔다.

바뀌는 관찰 가능한 동작: 종료 시 세션 드레인과 체크포인트 저장이 **메모리 최종 derivation 보다 먼저**
끝난다. CLI 에서는 마지막 관찰이 더 완전한 전사 위에서 만들어지므로 개선이고, 그 외 배포에서는
§7 의 두 수집 모드가 비로소 살아 있는 백엔드를 본다.

#### `ObservationType` 확장

`ObservationType` 을 4값으로 넓히면(§2.2) **다운그레이드가 깨진다** — `INDUCTIVE`/`CONTRADICTION` 이
저장된 뒤 옛 jar 로 되돌리면 `valueOf` 가 던진다. 이것은 개명이 아니라 값 추가이므로 `rename-maps.md`
가 아니라 `CHANGELOG.md` 항목이다. 완화책은 두 가지이며 **둘 다 하지 않는다**:

- 저장 시점에 옛 두 값으로 접기 → 그러면 넓힌 의미가 없다
- 미지의 값을 `DEDUCTIVE` 로 읽는 관대한 `valueOf` → 옛 jar 를 고쳐야 하므로 불가능하다

### 11.4 데이터 마이그레이션은 하지 않는다

기본 백엔드 ↔ Honcho ↔ Dyad 사이의 데이터 이전은 **이 설계의 범위 밖**이다(§13). 백엔드를 바꾸면 새
백엔드는 비어 있는 상태에서 시작하고, 그 사실을 기동 시점에 말한다. 이전이 필요해지면 `Observe` 티어를
쓰는 일회성 도구를 별도로 만든다 — 세 대상 모두 결론 직접 주입 API 를 갖고 있으므로 가능은 하다.

---

## 12. 단계별 구현 계획

각 단계는 **독립적으로 머지 가능**하며, 단계가 끝난 시점에 트리가 초록이고 사용자 관찰 동작이 명시된
것 외에는 바뀌지 않는다.

| Step | 내용 | 끝났을 때 관찰되는 변화 | 인수 조건 |
|---|---|---|---|
| **1** | 다섯 티어 인터페이스 + 값 객체 + `PeerMemory` + `MemoryCapability` + `StoreBackedPeerMemory` 를 `at.aimon.core.memory` 에 추가 | **없음** (새 타입만) | ① ArchUnit: 티어 SPI 가 `*Store` 를 시그니처에 언급하지 않는다 ② `MemoryArchitectureTest` 격리 규칙 배열에 다섯 개 추가 ③ **요청 객체 형태를 강제하는 새 ArchUnit 규칙** — 신규 네 티어에 한정하고 `DialecticEngine` 은 제외한다(§3.4) ③′ `PeerMemory` 에 `capabilities()` 류의 능력 집합 메서드가 **없다**(§3.2) ④ **[`peer-memory.md`](peer-memory.md) §1 의 비목표 첫 줄을 같은 커밋에서 철회**하고 이 문서를 링크한다(§0.1) ⑤ **같은 커밋에서 `peer-memory.md` §10.3 의 kebab yaml 예제를 camelCase 로 고치고 "경고와 함께 폴백" 문장을 정정한다**(§0.2) — 다른 단계에 의존하지 않고, 지금 그 예제가 읽는 사람을 부팅 실패로 보낸다 |
| **2** | `ObservationType` 4값 확장 + 도구 4종·컨텍스트 provider 를 티어 위로 재배선. `RepresentationMemoryContextProvider` → `SnapshotMemoryContextProvider` 개명 | 동작 동일. 이름 하나 바뀜 (기본 백엔드는 `storesConfidence()=true` 이므로 스키마도 그대로다) | ① 기존 메모리 테스트 전부 통과 ② `MemoryRecallTool` 이 `observationsAvailable=false` 를 렌더한다(§3.3) ③ **`storesConfidence()=false` 일 때 `ObserveTool` 의 스키마에서 `confidence` 가 빠지고 세 도구가 그 값을 찍지 않는다**(§2.2) ④ `MemoryHit.confidenceAvailable` 과 `MemorySnapshot.confidenceAvailable` 이 각각 `MemorySearchTool` · `MemoryRecallTool` 의 렌더를 가른다(§2.2) ⑤ `rename-maps.md` · `CHANGELOG.md` 갱신 |
| **3** | `MemorySpec.peerMemory(...)` + `MemoryAssembly` 의 능력 기반 등록 + degradation 키 재편 + **`RedactingPeerMemory`**(§6.2) | 스타터의 `supplied` + `PeerMemory` 빈 경로에서 `MemoryChat` 이 **처음으로** 등록된다. `in-memory` 는 `DialecticEngine` 빈이 없으므로 `memory-chat` degradation 이 오른다 (§5.3) | ① degradation 문구가 백엔드 id 를 포함 ② 기존 조립 경로 동작 불변 ③ `MemorySpec` 의 두 불변식을 `PeerMemory` 경로까지 넓힌다(§9.1) ④ `CAPABILITY_WRITE_PATH` → `CAPABILITY_INGEST` 를 `rename-maps.md` 에(§11.1) ⑤ **감싸지 않은 `PeerMemory` 가 스택에 들어가는 경로가 없고, 감싸는 대상은 INGEST·OBSERVE·SEARCH·CHAT 넷이다**(SNAPSHOT 제외 — 나가는 자유 텍스트가 없다, §6.2. Step 1 ③ 의 "신규 네 티어" 와는 **다른 넷**이다 — 그쪽은 `DialecticEngine` 을 뺀 집합이다) ⑥ [`peer-memory.md`](peer-memory.md) §14.2 의 레닥션 금지 항목을 넓힌다(§6.2) ⑦ **`AimonStackSpec:122-130` 의 상호 배타 가드를 SNAPSHOT 능력까지 넓힌다**(§9.1 표의 세 번째 행) — 넓히지 않으면 `peerMemory` + `ExecutorSpec.memoryContextProvider` 조합에서 주입 provider 가 둘이 되고 하나가 조용히 버려진다 |
| **4** | **CLI 이주** — `AgentSetupFactory` 의 메모리 배선 아홉 개를 `MemorySpec`/`MemoryAssembly` 경로로 옮긴다 (§5.0) | CLI 관찰 동작 **불변** (아직 `file`/`in-memory` 뿐) | ① `grep MemorySpec modules/aimon-cli/src/main` 이 0건이 아니게 된다 ② `registerCliTools` 에 `ConsoleOutputTool` 만 남는다 ③ `AimonStackSpec:122-130` 상호 배타 규칙을 **CLI 측에서는 고치지 않고** 통과한다 (가드 자체를 넓히는 것은 Step 3 이다 — §9.1) ④ dreamer·maintenance 는 CLI 소유로 남는다 |
| **5** | `MemoryIngestor` 실행기 이음매 + `ingest` 설정 + **`TeardownPhase` 메모리 블록 이동**(§3.6) | CLI 기본은 `session-end` = 기존 동작 | ① **§7.2 의 델타 방안 (a)/(b) 중 하나를 먼저 확정한다**(§15-8) — 이것 없이는 "같은 메시지가 두 번 안 간다" 가 검증 불가다 ② 실행기 이음매가 받는 `MemoryIngestor` 는 **Step 3 의 데코레이터를 지난 것**이다 (감싸지 않은 것을 직접 꺼내 쓰는 경로가 없다) ③ **phase 이동 전후로 CLI 최종 derivation 이 같은 수의 관찰을 만든다** ④ per-caller 에서 INGEST 가 꺼지고 degradation 이 오른다(§7.2) |
| **6** | `aimon-memory-testkit` — 다섯 티어 계약 스위트. 기본 백엔드를 통과시킨다 | **없음** (테스트 전용) | 계약이 문서가 아니라 코드로 존재. 특히 **능력 협상 4계약**(§8.1) — ① 내놓은 티어는 `UnsupportedOperationException` 을 던지지 않는다 ② SEARCH 결과는 언제나 관련도 내림차순이다 ③ `ranksByScore()=false` 가 `minScore>0` 을 조용히 무시하지 않는다 ④ `narrowsBySession()=false` 가 세션 id 를 조용히 무시하지 않는다 |
| **7** | `aimon-memory-dyad` | `backend: dyad` 가 동작 | testkit 통과 · `?wait=derive` 경로 검증 |
| **8** | `aimon-memory-honcho` | `backend: honcho` 가 동작 | testkit 통과 · `package-info` 에 §10 요약 |
| **9** | CLI/스타터 설정 표면 + 문서 갱신 | yaml/프로퍼티로 원격 선택 가능 | ① §9 의 거절 규칙 전부 테스트 ② 원격 백엔드에서 `storagePath` 를 요구하지 않도록 `MemoryConfig.isEnabled()` 를 가른다(§9.2) ③ 미지 backend 경고를 표상·관찰 양쪽에서 낸다(§9.2) ④ **[`memory-usage-guide.md`](../../features/memory/memory-usage-guide.md) 와 `memory-usage-guide.en.md` 를 같은 커밋에서 갱신하고 `source_commit` 을 맞춘다** ⑤ **`CLAUDE.md` 의 Module Structure 목록에 두 모듈(`aimon-memory-honcho` · `-dyad`) 추가** — testkit 은 넣지 않는다. 그 목록은 기존 두 testkit(`aimon-filesystem-testkit` · `aimon-session-testkit`)도 싣지 않으며(`grep testkit CLAUDE.md` → 0건) 여기서 관례를 깨지 않는다 |

Step 4(CLI 이주)를 Step 5(수집) 앞에 두는 이유: 수집 이음매는 `MemoryIngestor` 를 스택에 꽂아야 하는데,
CLI 가 `MemorySpec` 을 지나지 않는 동안에는 **꽂을 자리가 CLI 에 없다.** 순서를 뒤집으면 Step 5 가
CLI 전용 배선을 한 번 만들고 Step 4 에서 다시 지우게 된다.

`RedactingPeerMemory` 는 Step 5 가 아니라 **Step 3 에 둔다.** Step 3 이 `peerMemory(...)` 를 여는
순간부터 감싸지지 않은 백엔드가 스택에 들어갈 수 있으므로, 데코레이터가 그보다 뒤에 오면 Step 3~4
구간에서 §6.2 의 보장이 성립하지 않는다. 그 구간에 원격 어댑터가 아직 없다는 것은 사실이지만
(Step 7 부터다), 각 단계가 **독립적으로 머지 가능**하다는 이 절의 약속에 기대면 "다음 단계가 곧
채운다" 를 근거로 쓸 수 없다. 데코레이터는 `PeerMemory` 와 `RedactionPolicy` 외에 의존이 없으므로
당기는 데 비용도 없다.

Step 7 을 8 보다 먼저 두는 이유: Dyad 는 우리가 소스를 갖고 있어 계약이 어긋났을 때 **어느 쪽이 틀렸는지
확인할 수 있다.** Honcho 는 그럴 수 없으므로 testkit 이 안정된 뒤에 붙이는 편이 싸다.

**문서 갱신은 Step 9 에만 있는 것이 아니다.** `peer-memory.md` 는 세 곳이 고쳐진다 — Step 1 에서 두 곳
(§1 비목표 철회, §10.3 의 kebab yaml 과 "경고와 함께 폴백" 정정), Step 3 에서 한 곳(§14.2 레닥션 금지
확장). `rename-maps.md` 는 Step 2 와 Step 3 에서 각각 한 행씩 늘어난다.
두 파일 모두 `.en.md` 가 없지만 **이유는 다르다** — `peer-memory.md` 가 사는 `design/` 은 번역
**대상 아님**(결정)이고, `rename-maps.md` 가 사는 `migration/` 은 **아직 아님**(우선순위로 미룬 것)이다
([`docs/README.md`](../../README.md) 의 번역 대상 표). 결론은 같다: `.en.md` 갱신이 필요한 것은
`features/memory/` 하나뿐이다.

### 후속 (이 설계의 범위 밖, 전제조건이 붙는 것)

| 항목 | 전제조건 |
|---|---|
| `deriver`/`dreamer`/`reconciler` 를 `at.aimon.core.memory.impl` 로 이동 | CLI 가 직접 조립하는 구체 클래스 13종을 코어 조립 팩토리 뒤로 먼저 옮겨야 한다 (§4.2) |
| 프로버넌스 티어 (`source_ids` → 원문 역추적) | 기본 백엔드에 대응 구현이 없다. Dyad 전용 능력을 SPI 로 올릴지는 소비자가 생긴 뒤 |
| `DialecticEngine.queryStream` 진짜 스트리밍 | 지금 `default` 구현은 답변을 한 청크로 흘린다. 원격 SSE 를 붙일 때 함께 본다 |
| per-caller 쓰기 (`ToolContextEnricher`/실행 이음매에 principal 을 싣는 것) | 실행 정체성 전달 경로를 넓히는 것이므로 메모리 밖의 변경이다. 그때 `memory-tools` 와 `memory-ingest` 두 degradation 이 함께 사라진다 (§7.2) |
| 백엔드 간 데이터 이전 도구 | 위 전부 |

---

## 13. 비목표

- **외부 SDK / 스키마 호환.** [`peer-memory.md`](peer-memory.md) §1 의 두 번째 비목표는 **그대로
  유지된다**(§0.1). AIMON 은 Honcho 의 와이어 포맷을 흉내 내지 않고, 그 SDK 를 재수출하지도 않으며,
  `Observation` 을 원격 스키마에 맞추지도 않는다 — §2.2 의 `ObservationType` 확장은 우리 어휘의 손실을
  없애는 것이지 스키마를 맞추는 것이 아니다
- **per-caller 배포에서의 쓰기.** 실행 이음매에 principal 이 실리지 않는 한 INGEST 는 per-caller 에서
  꺼진다(§7.2). 그 seam 을 넓히는 것은 메모리 밖의 변경이다
- **데이터 마이그레이션.** 백엔드를 바꾸면 새 백엔드는 빈 상태에서 시작한다 (§11.4)
- **Honcho 와의 와이어 호환.** AIMON 이 Honcho 의 API 를 흉내 내는 서버가 되지 않는다. 우리는 클라이언트다
- **Dyad 서버를 이 저장소로 가져오는 것.** 어댑터만 온다. Dyad 는 자기 저장소에서 자기 수명으로 산다
- **Honcho 의 `scope` · webhook · peer card 를 1급으로 올리는 것.** 세 대상 중 하나에만 있는 것을 SPI 에
  올리면 나머지 둘의 구현이 전부 빈 구현이 된다. 필요해지면 `PeerMemory` 에서 백엔드별 확장 인터페이스로
  캐스팅하는 별도 경로를 연다
- **`source_ids` 추론 트리를 AIMON 도메인에 도입하는 것.** `Observation.sourceMessageIds` 는 메시지까지만
  가리키고 결론 간 전제 관계를 갖지 않는다. 넓히려면 Reconciler·Dreamer 가 함께 바뀐다
- **기본 백엔드의 `KnowledgeStore` 위임을 바꾸는 것.** [D2](peer-memory.md) 는 그대로다
- **`MemoryChatTool` 의 스트리밍 노출.** 도구는 한 번에 한 결과를 돌려준다. SSE 는 어댑터 내부에서
  소비되어 하나의 답변으로 접힌다
- **관측성 배선.** `peer-memory.md` §14.1 의 미해결 항목이며 백엔드 교체와 독립이다

---

## 14. 기각한 대안

| # | 대안 | 왜 기각했나 |
|---|---|---|
| **A1** | **`ObservationStore` / `RepresentationStore` 를 HTTP 로 구현한다** (가장 뻔한 길) | §1.3 의 표가 답이며, 기각 사유는 LSP 일반론이 **아니다** — 이 인터페이스는 `semanticSearch` 자리에서 이미 `UnsupportedOperationException` 을 규범으로 허용한다(`MongoObservationStore:126-136`, `PostgresObservationStore:229-244`). 차이는 **복원 가능성**이다: 그 예외는 `IndexedObservationStore` 가 흡수하지만, `merge` · `findByConfidenceBelow` · `purgeSoftDeletedBefore` · `RepresentationStore.save` 는 **복원할 대상이 원격에 없어서** 데코레이터로도 되살아나지 않고 Dreamer·Reconciler 의 호출 지점에서 터진다. 게다가 `Representation`(구조화 애그리게이트)을 만들려면 원격이 준 **렌더 문자열을 되파싱**해야 한다 |
| **A2** | **MCP 서버로 붙인다** — Honcho/Dyad 를 MCP 도구로 노출 | 프롬프트 **자동 주입**이 불가능하다. 주입은 모델이 부를지 말지 정하는 것이 아니라 실행기가 매 실행마다 무조건 하는 것이고, 그게 `MemoryContextProvider` 가 존재하는 이유다. 또 능력 협상이 없고(도구는 있거나 없거나뿐), 레닥션 게이트가 MCP 경계 밖에 남는다. **CHAT 티어에 한해서는 유효한 보완 경로**이며 배제하지 않는다 |
| **A3** | **뚱뚱한 인터페이스 하나 + `default` 메서드가 `UnsupportedOperationException`** | A1 과 같은 이유로 "예외 자체가 금지" 라고는 말하지 않는다. 기각 사유는 **판단 시점**이다 — 능력을 예외로만 표현하면 도구 등록 결정이 조립 시점에서 **런타임 실패로** 밀리고, 모델은 매 실행마다 없는 도구를 다시 부르며 실패에 iteration 과 프롬프트 예산을 태운다. `Optional` 접근자는 그 결정을 조립 시점으로 되돌린다. `semanticSearch` 의 선례가 성립한 것은 그 예외를 **데코레이터가 조립 시점에 흡수**했기 때문이지, 예외가 좋은 표현이어서가 아니다 |
| **A4** | **백엔드가 `Set<MemoryCapability>` 를 선언한다** | 진실 원천이 둘이 된다. 선언과 실제 구현이 어긋난 백엔드가 만들어질 수 있고, 그 어긋남은 조립이 아니라 첫 호출에서 드러난다. 티어 접근자에서 **계산**하면 그 상태가 표현 불가능해진다 — 단 그것이 참이려면 계산이 `PeerMemory` **밖**에 있어야 한다. 인터페이스의 `default` 메서드로 두면 재정의로 이 기각이 무효화되므로 `MemoryCapabilities.of(...)` 정적 유틸이다(§3.2) |
| **A5** | **Honcho 의 도메인 모델을 AIMON 의 것으로 채택한다** (4단 level · `source_ids` · `times_derived`) | 전제로 삼으면 이 작업이 도메인 재설계가 된다 — Deriver·Reconciler·Dreamer·세 저장소 백엔드가 전부 따라 바뀐다. 실제로 필요한 것은 `ObservationType` 하나를 넓히는 것뿐이고(§2.2), 나머지는 원격이 자기 안에서 쓰면 된다 |
| **A6** | **원격 백엔드와 AIMON 의 Dreamer/Deriver 를 함께 돌린다** | 같은 대화에서 두 시스템이 서로 모르는 채 결론을 만든다. 어느 쪽이 진실인지 정할 수 없고, 비용은 두 배다. §6.1 에서 **설정 검증으로 금지**한다 |
| **A7** | **새 SPI 를 `at.aimon.core.memory.spi` 하위에 둔다** | 패키지 규약이 이미 `at.aimon.core.<domain>` 을 SPI 표면으로 정하고 있다([`api-stability.md` §2](../../project/api-stability.md)). `spi` 하위 패키지는 그 규약과 경쟁하는 두 번째 규약을 만든다. 덤으로 `MemoryArchitectureTest` 의 워크스페이스 격리 규칙이 정확히 `at.aimon.core.memory` 패키지를 대상으로 하므로, 하위로 내리면 **그 규칙의 사정거리 밖으로 나간다** |
| **A8** | **`MemoryRecallTool` 을 `MemoryContextProvider` 위에 세운다** (SNAPSHOT 티어를 만들지 않고 `MemoryContextRequest` 를 넓혀서) | 티어 하나를 아낄 수 있지만 도구가 `SystemPromptPart` 를 받아 풀어 쓰게 되고(프롬프트 조각을 도구 결과로 렌더하는 것은 층 위반), `MemoryRecall` 이 보고하던 GLOBAL/LOCAL 해석 결과가 사라진다. 타입 하나를 아끼려고 두 가지를 잃는다 |

---

## 15. 아직 확인되지 않은 것

**실측하지 않은 것은 실측하지 않았다고 적는다.**

1. **Honcho 서버를 실제로 띄워 응답을 확인하지 않았다.** §2 의 Honcho 칸은
   `honcho-java-spec.md`(v3.1.0 기준 리버스 스펙)와 그것이 인용한 OpenAPI 를 근거로 한다.
   `/peers/{p}/context` 가 세션 없이 정말로 응답하는지, `conclusions/query` 의 `distance` 가 어떤
   범위인지는 **Step 8**(Honcho 어댑터) 착수 시점에 확인해야 한다
2. **Dyad 서버를 띄워 응답을 확인하지 않았다.** 소스는 읽었고 컨트롤러 시그니처는 정확하지만, 실제 응답
   본문과 인증 흐름(`POST /v1/tokens` 의 스코프 조합)은 **Step 7**(Dyad 어댑터)에서 확인한다
3. **주입 지연.** 원격 백엔드에서는 `MemoryContextProvider.provide` 가 **프롬프트 조립 경로에서 HTTP 를
   탄다.** 매 실행마다 붙는 지연이며, 실측하지 않았다. 완화책(짧은 TTL 캐시, 비동기 프리페치)은
   **Step 7**(첫 원격 어댑터)에서 수치를 본 뒤 정한다 — 지금 넣으면 최적화할 대상 없이 캐시 무효화
   문제만 산다
3′. **수집 지연.** §15-3 의 대칭 자리이며 같은 이유로 미실측이다 —
   `OrcaAgentExecutor.feedExecutionMemory` 는 `execute()` 의 finally 에서 **동기로**
   `ExecutionMemorySink.afterExecution` 을 부르고, `IngestingExecutionMemorySink` 가 곧바로
   `MemoryIngestor.ingest` 를 부른다. 원격 어댑터라면 대화 델타 전체를 실은 HTTP POST 가 **실행 스레드
   위에서** 일어난다(`waitForDerivation=false` 여도 왕복은 남는다). 기본 백엔드에서는 무해하다 —
   `QueueIngestor` 가 큐에 넣고 즉시 돌아온다. 완화책(비동기 오프로드, 배치)은 §15-3 과 마찬가지로
   **Step 7 에서 수치를 본 뒤** 정한다. 지금 넣으면 최적화할 대상 없이 순서 보장만 잃는다

4. **`MemoryInjectionMode` 의 원격 매핑 품질.** `FULL` 을 Honcho 의 `max_conclusions` 로, Dyad 의
   `tokens` 로 옮기는 것이 실제로 비슷한 양을 내놓는지는 픽스처로 비교해야 안다 — Step 7·8 의 인수
   조건에 붙일 것
5. **`ObservationType` 확장의 소비자 영향.** 트리 안에 exhaustive `switch` 가 없다는 것은 확인했으나,
   프롬프트 문구(`LlmDeriver` 의 type 분류 지시)가 4값을 어떻게 다룰지는 정하지 않았다 — 기본 백엔드의
   Deriver 는 계속 2값만 만들게 두는 것이 §12 Step 2 의 전제다
6. ~~**`TeardownPhase` 블록 이동의 안전성.**~~ — **해소됨 (Step 5).** §3.6 이 근거로 든 것
   (`SessionRecordStore` 가 application-scoped 라 `SESSIONS` 가 닫지 않는다)은 그대로 맞고, 이제
   추론이 아니라 측정이다 — `AimonStackBuilderTest` 의
   `memoryFinalDerivationStillReadsTheTranscriptAfterTheMove` 가 `SESSIONS`·`CHECKPOINTS` 뒤에 등록된
   `MEMORY_FINAL_DERIVATION` 항목이 전사에서 메시지 2건을 읽는 것을 세고, 그 항목이 정말 두 phase
   **뒤에** 돌았다는 것도 teardown plan 인덱스로 확인한다. 옛 순서는 트리에 남아 있지 않으므로
   "이동 전후 관찰 개수 비교" 라는 문자 그대로의 형태는 불가능하고, 이동이 위협한 유일한 것(전사를
   못 읽게 되는 것)을 직접 재는 것이 그 자리를 대신한다
7. **Honcho `conclusions/query` 를 peer 쌍으로 좁힐 수 있는가.** §2.1 은 그 티어를 `✓` 로 놓았지만,
   `honcho-java-spec.md:343` 이 기술하는 파라미터는 `query` · `top_k` · `distance` 셋뿐이고
   **observer/observed 로 좁히는 방법이 명시되어 있지 않다.** `honcho-java-spec.md` 의
   `§8.3 필터 DSL`(그 파일 `:654`)이 기술하는 범용 `filters` 로 되리라 보이지만, 그것이 이
   엔드포인트에 걸리는지는 스펙에 없다. `MemorySearchQuery` 는 `subject`/`observer` 를
   요구하므로, 좁힐 수 없으면 그 티어가 **워크스페이스 전역 결과를 subject 의 것처럼** 돌려준다 —
   §5.2 가 없애려는 실패 모드다. Step 8 착수 시 첫 번째로 확인할 것
8. ~~**수집 델타를 어떻게 잡을지 정하지 않았다.**~~ — **해소됨 (Step 5).** (a) 전사 내 워터마크를
   골랐다. (b) 는 선택지가 아니었다 — `Message` 에 안정적인 id 가 없고, 더하는 것은 세션 레코드의 와이어
   포맷을 넓히는 일이다. compaction 이 일어난 실행은 **아무것도 보내지 않는다**. 근거와 세 선택지의
   대가 비교는 §7.2 의 *"확정: (a)"* 절에 있다. 남은 미확정은 없다
9. **`ObservationDraft.type` 의 왕복 손실 처리.** confidence 는 §2.2 에서 신호 + 스키마 축소로
   정했지만, Dyad 가 직접 주입을 `EXPLICIT` 로 고정하는 것(`ConclusionController:108-110`) 때문에
   모델이 고른 `DEDUCTIVE` 도 버려진다. 같은 기계를 `type` 까지 넓힐지는 정하지 않았다
10. **CLI 이주의 실제 크기.** §5.0 은 아홉 개 메서드와 상호 배타 규칙을 짚었지만, `buildDreamerSubsystem`
   이 `InMemoryWorkspaceStore` 를 자기 안에서 만드는 것처럼 `MemorySpec` 이 담지 않는 재료가 더 있는지는
   전수 확인하지 않았다. Step 4 를 시작할 때 `AgentSetupFactory` 의 **구체 클래스 13종**(§4.2 의 수 —
    `at.aimon.core.memory*` import 는 전부 30개이고 그중 구체 클래스가 13개다)을 다시 센다

---

## 관련 문서

- [Peer Memory 통합 설계](peer-memory.md) — 현재 구현의 사양. 이 문서가 확장하는 대상
- [Memory(Peer Memory) 사용 가이드](../../features/memory/memory-usage-guide.md) — 배선·설정·운영
- [스코프 모델](../../overview/scope-model.md) — application-scoped 결정과 소멸 책임 규칙
- [용어집](../../overview/glossary.md) — turn / iteration / execution 의 구분 (§7.2)
- [API 안정성 정책](../../project/api-stability.md) — `0.x` 약속과 `*.impl` 경계 (§4.2, §11)
- [개명 조회표](../../migration/rename-maps.md) — §11.1 의 행이 들어갈 곳
- [SOLID 원칙](../../project/solid-principles.md) — A1·A3 기각 근거
- [Knowledge Search 와 RAG 설계](../knowledge/knowledge-and-rag.md) — 기본 백엔드가 벡터 검색을 위임하는 곳
- [Spring Boot Starter 설계](../integration/spring-boot-starter.md) — §9.3 이 얹히는 자동설정
- [멀티 인스턴스 준비도](../../backlog/multi-instance-readiness.md) — 메모리 축의 현재 상태
