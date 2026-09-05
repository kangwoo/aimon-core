# AIMON Memory Layer — Peer Memory 통합 설계

> Status: **IMPLEMENTED — 단, 백엔드 배치는 이 문서 이후에 바뀌었다.**
> `at.aimon.core.memory`(main 65 / test 37) + `at.aimon.core.tools.memory`(6) 은 그대로다.
>
> IMPORTANT: **아래 본문이 "영속 백엔드 셋" 이라고 부르는 `aimon-memory-{file,postgres,mongodb}` 는 더 이상
> 그 형태로 존재하지 않는다.** `-postgres` 와 `-mongodb` 는 **제거되었고**(이전이 아니라 제거 — 데이터가
> 옮겨가지 않는다), `-file` 은 `aimon-core` 의 `at.aimon.core.memory.file` 로 병합되었다. 멀티 인스턴스
> 메모리는 이제 저장소 백엔드가 아니라 **`PeerMemory` 백엔드 전체**를 바꾸는 것이며, 그 자리는 별도
> 저장소의 서비스([aimon-memory](https://github.com/kangwoo/aimon-memory))가 맡는다. 근거와 대응표는
> [`pluggable-memory-backend.md`](pluggable-memory-backend.md) §4.3.
>
> 이 문서는 **그 변경 이전의 설계 사양**으로 남긴다 — 도메인 모델·티어·도구·아키텍처 규칙은 전부 유효하고,
> 낡은 것은 저장소 모듈의 이름과 배치뿐이다. 아래에서 그 이름들이 보이면 위 문단으로 환산해 읽는다.
> 사용·통합 방법은 [Memory(Peer Memory) 사용 가이드](../../features/memory/memory-usage-guide.md) 참조.

---

## 1. 목적

**Peer / Representation / Dialectic / Dreamer** 패턴을 AIMON 코어에 네이티브 모듈로 내재화하여, IT 운영 자동화 에이전트가 **운영자·시스템·서비스에 대한 지속적이고 진화하는 메모리** 를 보유하도록 한다. 본 문서는 그 사양을 정의한다.

본 통합의 비목표(Non-Goals):
- ~~외부 메모리 서버를 호출하는 원격 클라이언트 통합~~ — **철회됨.**
  [교체 가능한 메모리 백엔드](pluggable-memory-backend.md) §0.1 이 이 줄을 거둬들이고, 서비스 고도의
  다섯 티어 SPI 위에 원격 어댑터가 서게 한다. 괄호 안의 단서는 유지된다 — **MCP 경로는 여전히 별도
  트랙**이며, 그 문서 §14 A2 가 프롬프트 자동 주입이 MCP 로는 불가능한 이유를 적는다
- 외부 SDK / 스키마 호환 — **유지된다.** AIMON 은 원격 서버의 와이어 포맷을 흉내 내지 않고 그 SDK 를
  재수출하지도 않는다

---

## 2. AIMON 기존 자산과의 매핑

| 개념 | AIMON 매핑 자산 | 필요 작업 |
|-------------|----------------|----------|
| `Peer` | `at.aimon.core.base.Principal` | **변경 없음**. `PeerView` 어댑터로 워크스페이스 컨텍스트만 부여 (§4.2) |
| `Workspace` | (신규) `at.aimon.core.memory.Workspace` | 신규 도입 — 멀티 테넌트 격리 단위 |
| `Session` | `at.aimon.core.agent.session.LiveSession` | **재사용** (세션 추상 이미 존재) |
| `Message` | `at.aimon.core.llm.content.*` | **재사용** (LLM 메시지 모델 활용) |
| `Collection / Document` | `at.aimon.core.knowledge.KnowledgeStore` | **재사용** (이미 임베딩·청크·검색 구비) |
| `Deriver Queue` | `at.aimon.core.agent.queue.MessageQueueManager` | **패턴 차용** + 워크 유닛 키 확장 |
| `Dreamer Scheduler` | `aimon-scheduling-quartz` | **재사용** (스케줄·분산락 이미 구비) |
| `LLM Provider 추상` | `at.aimon.core.llm.LlmClient` | **재사용** (Anthropic/OpenAI 모듈 존재) |
| `Vector Store` | `KnowledgeStore` 백엔드 | **재사용** (`aimon-knowledge-opensearch` 등) |
| `Session Storage` | `aimon-session-{mongodb,postgres,redis,web}` | 메시지 영속에 활용 가능 |

> **결론**: AIMON은 이미 `Peer`(Principal), `Session`(LiveSession), `Queue`(MessageQueueManager), `KnowledgeStore`, `Quartz Scheduler`, `LlmClient` 추상을 모두 보유하고 있다. **신규 도입할 것은 Workspace 격리·Observation/Representation 도메인·Reasoning 4종 에이전트** 뿐이다.

---

## 3. 모듈 구조

[`.claude/rules/architecture.md`](../../../.claude/rules/architecture.md)와
[`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md) 규칙을 따른다.

```
aimon-core/                            # 인터페이스 + 도메인 + In-Memory 기본 구현
└── at.aimon.core.memory/
    ├── Workspace.java                 # 테넌트 격리 단위 (신규)
    ├── PeerView.java                  # (Workspace, Principal) 어댑터
    ├── ObservationId.java             # workspace-bound 합성 ID
    ├── Observation.java               # 도출된 관찰 사실
    ├── Representation.java            # peer에 대한 통찰 스냅샷
    ├── ObservationStore.java          # ★ 저장소 인터페이스
    ├── RepresentationStore.java       # ★ 저장소 인터페이스
    ├── WorkspaceStore.java            # ★ 저장소 인터페이스
    ├── InMemoryObservationStore.java  # 개발/테스트 전용
    ├── InMemoryRepresentationStore.java
    ├── InMemoryWorkspaceStore.java
    ├── redaction/
    │   ├── RedactionPolicy.java       # ★ 시크릿/PII 마스킹 게이트
    │   ├── RedactionResult.java
    │   ├── RedactionMatch.java
    │   ├── DefaultRedactionPolicy.java
    │   └── StrictRedactionPolicy.java
    ├── deriver/
    │   ├── DerivationTask.java        # 큐 페이로드 (값 객체)
    │   ├── DerivationWorkUnit.java    # (workspace, session, observerType, observerId) 키
    │   ├── DerivationQueueManager.java
    │   ├── Deriver.java               # 인터페이스
    │   ├── LlmDeriver.java            # single-shot LLM 구현 (JSON 배열 응답)
    │   ├── ReActLlmDeriver.java       # ReAct 루프 LLM 구현 (3개 내부 도구 사용)
    │   └── tool/                      # Deriver 내부 LLM 도구 (사용자 노출 X)
    │       ├── DeriverObservationCreateTool.java
    │       ├── DeriverMemorySearchTool.java
    │       └── DeriverMessageLinkTool.java
    ├── dialectic/
    │   ├── DialecticEngine.java       # 인터페이스
    │   ├── DialecticQuery.java
    │   ├── DialecticResponse.java
    │   ├── ReasoningLevel.java
    │   └── LlmDialecticEngine.java
    ├── dreamer/
    │   ├── DreamerEngine.java                # 인터페이스
    │   ├── DefaultDreamerEngine.java         # 기본 구현
    │   ├── ConsolidationStrategy.java        # plan 인터페이스
    │   ├── ConsolidationPlan.java            # plan 결과 값 객체
    │   ├── ObservationCluster.java           # 군집 단위
    │   ├── DreamerCycleSummary.java          # 사이클 결과 텔레메트리
    │   ├── SurprisalScorer.java              # 인터페이스
    │   ├── EmbeddingSurprisalScorer.java     # EmbeddingClient 기반 구현
    │   └── RandomWalkDreamer.java
    ├── index/                                # ObservationStore 의 검색 인덱스 추상화
    │   ├── ObservationIndex.java             # 인터페이스 (semantic + 키워드)
    │   ├── InMemoryObservationIndex.java     # 부분 일치 + 카운트 폴백
    │   ├── KnowledgeStoreObservationIndex.java  # KnowledgeScope("memory.observation",
    │   │                                          subjectKey) 위임
    │   └── StagingFileSystem.java            # KnowledgeStore.reindex 본문 전달용
    └── reconciler/
        ├── Reconciler.java
        ├── ReconcileDecision.java     # sealed interface (Accept/Reject/Replace/Merge)
        └── DefaultReconciler.java     # heuristic 빠른 경로 + LLM judge

at.aimon.core.tools.memory/             # ★ 사용자 노출 도구 (ArchUnit: ext.tools 네임스페이스는 폐지)
├── MemorySearchTool.java               # (architecture.md)
├── MemoryChatTool.java
├── MemoryRecallTool.java
└── ObserveTool.java

aimon-memory-file/                      # 신규 모듈 (구현 완료) — 단일 노드 JSON-line 영속
├── File{Workspace,Observation,Representation}Store + JsonLineLog 어펜드 로그
├── Compactable + 임계치/기동 자동 compaction (원자적 temp→fsync→ATOMIC_MOVE)
├── 단일 프로세스 OS FileLock(sidecar <log>.lock) + AutoCloseable
└── FileMemoryMaintenanceScheduler      # Dreamer-무관 retention purge + compaction 스케줄러
   (findAll 은 WorkspaceAccessPolicy 적용)

aimon-memory-postgres/                  # 신규 모듈 (구현 완료)
├── PostgresObservationStore            # 메타데이터 행 + outbox enqueue
├── PostgresWorkspaceStore
├── PostgresRepresentationStore
├── PostgresDerivationQueueManager      # row-lock 기반 멀티인스턴스 큐
├── KnowledgeStoreOutboxRelay           # outbox → KnowledgeStore.reindex 펌프
├── RelayOptions / DrainResult          # relay 튜닝 / 결과 텔레메트리
└── internal/{OutboxStagingFileSystem, RepresentationRowCodec}
   + Flyway 스키마 (mem_observation, mem_observation_outbox, mem_active_work_unit, …)

aimon-memory-mongodb/                   # 신규 모듈 (구현 완료) — 멀티인스턴스 (컬렉션 = SSOT)
├── Mongo{Workspace,Observation,Representation}Store  # 메타데이터 전용(C3), soft-delete/retention
├── internal/{MongoMemoryCodec, DocumentKeys}         # 도메인 ↔ BSON
└── operator-applied 스키마 db/mongodb/init.js (mem_workspace/mem_observation/mem_representation + 인덱스)
   (findAll 은 WorkspaceAccessPolicy 적용; semanticSearch 는 IndexedObservationStore 로 복원)
```

> 초안의 `aimon-memory-opensearch` 별도 모듈은 채택하지 않았다 —
> §5.2 결정에 따라 OpenSearch 백엔드는 `aimon-knowledge-opensearch` 위에서
> `KnowledgeStoreObservationIndex` 가 그대로 위임하므로 별도 어댑터가 불필요.

> 인메모리 구현은 `aimon-core` 안에 함께 둔다 (CLAUDE.md *"기본 구현은 in-memory로 제공"* 원칙).

---

## 4. 도메인 모델

### 4.1 Workspace

테넌트 격리 단위.

```java
public final class Workspace {
    private final String id;              // nanoid 또는 application-supplied
    private final String displayName;
    private final Instant createdAt;
    private final Map<String, String> metadata;

    // Builder 패턴 (immutability-pattern.md 준수)
}
```

### 4.2 Peer (Principal 확장이 아닌 어댑터)

`Principal`은 이미 안정적이므로 직접 변경하지 않고 **`PeerView`** 어댑터를 둔다.

```java
public final class PeerView {
    private final Workspace workspace;
    private final Principal principal;     // 기존 자산 그대로 재사용

    public String key() {                  // 저장소 키 — Type+id 합성으로 충돌 방지
        return workspace.getId() + ":"
             + principal.getType() + ":"     // USER | SYSTEM | SERVICE | GROUP
             + principal.getId();
    }
}
```

> Principal 그 자체는 식별자, PeerView는 *"이 워크스페이스 내 어떤 표상의 주체"* 라는 합성 컨텍스트.

### 4.3 Observation

LLM이 메시지로부터 도출한 단일 관찰 사실.

```java
public final class Observation {
    private final ObservationId id;                   // (workspaceId, nanoid) 합성 ID
    private final PeerView subject;                   // 누구에 대한 사실인가
    private final PeerView observer;                  // 누가 관찰했는가 (= subject 자기 자신 가능)
    private final String content;                     // 자연어 사실
    private final ObservationType type;               // EXPLICIT, DEDUCTIVE, INDUCTIVE, CONTRADICTION
    private final List<String> sourceMessageIds;
    private final Instant createdAt;
    private final double confidence;                  // 정의는 본 절 아래 "confidence 의 정의" 참조
    private final Map<String, String> metadata;
}

public enum ObservationType { EXPLICIT, DEDUCTIVE, INDUCTIVE, CONTRADICTION }
```

> **넓혀짐** — 원래는 `{EXPLICIT, DEDUCTIVE}` 두 값이었다. 두 값으로는 메모리 백엔드가 구별하는 것의
> 절반이 `DEDUCTIVE` 로 뭉개졌으므로(패턴에서의 귀납과 기록된 충돌이 똑같이 "메시지에서 추론됨" 이 된다)
> 네 값으로 넓혔다. 근거와 다운그레이드 비용은
> [교체 가능한 메모리 백엔드](pluggable-memory-backend.md) §2.2 · §11.3 에 있다. 트리의 Deriver 는
> 여전히 앞의 두 값만 만든다 — 새 두 값은 자기 분류가 더 촘촘한 백엔드를 위한 자리다.

#### `confidence` 의 정의

```
confidence = clamp(0, 1,
    base_score(type)                  // EXPLICIT=0.9, DEDUCTIVE=0.6, INDUCTIVE=0.4, CONTRADICTION=0.3
  + reinforcement_bonus(corroborations) // 다른 메시지에서 같은 사실 재확인 시 +0.05/회 (cap 0.2)
  - contradiction_penalty               // Reconciler가 충돌 발견 시 -0.3
)
```
계산은 `LlmDeriver`가 수행한다 (LLM 자기보고가 아님 — LLM의 self-report는 신뢰성 부족). LLM은 *type 분류*까지만 한다.

> **구현됨** — `LlmDeriver` + `DeriverObservationCreateTool` 에서 위 식을 그대로 적용한다. base score 는 `ObservationType.baseConfidence()` (EXPLICIT=0.9, DEDUCTIVE=0.6, INDUCTIVE=0.4, CONTRADICTION=0.3) 에 산다. 뒤의 두 값은 위 넓힘과 함께 정해졌다 — 귀납은 다음 사례가 깨뜨릴 수 있으므로 연역보다 약하고, 충돌 기록은 넷 중 그것을 근거로 행동하기에 가장 위험하므로 가장 낮다. corroboration 마다 +0.05(cap 0.2), Reconciler 가 충돌하는 prior 를 탐지하면 −0.3. LLM 은 type 분류만 하고 confidence 를 self-report 하지 않는다.

#### 임베딩과 벡터 검색

`embedding`은 `Observation` 자체에 들고 있지 않는다. 대신 §5.2의 `ObservationStore` 가
`at.aimon.core.knowledge.embedding.EmbeddingClient` 와 `at.aimon.core.knowledge.KnowledgeStore` 를 **위임 사용**한다 (C3 결정 — §5.2 참조).
- `LlmDeriver`는 `EmbeddingClient` 를 생성자 주입받아 observation 생성 시 임베딩.
- 임베딩 차원/모델은 `EmbeddingClient` 가 결정 — Memory 모듈은 차원 가정 없음.
- 인덱스는 `KnowledgeStore` 의 별도 `KnowledgeScope`(`memory.observation`) 로 분리.

### 4.4 ObservationId

```java
public final class ObservationId {
    private final String workspaceId;     // 멀티 테넌트 격리
    private final String localId;         // workspace 내부 nanoid
    // toString() = "ws_xxx:obs_yyy"
}
```
모든 ID 기반 store API는 `ObservationId` 를 받음 → 컴파일 타임 multi-tenant 격리.

### 4.5 Representation

특정 시점·특정 관찰자 시점의 peer 통찰 스냅샷. 즉시 LLM 컨텍스트로 주입 가능한 형태.

```java
public final class Representation {
    private final PeerView subject;
    private final PeerView observer;                  // null이면 global
    private final String sessionId;                   // null이면 cross-session
    private final List<Observation> observations;
    private final String summary;                     // LLM 합성 요약
    private final Instant generatedAt;
    private final int tokenCount;                     // 컨텍스트 예산 계산용
                                                      // 산정은 at.aimon.core.llm.token 의
                                                      // TokenCounter 재사용 (모델별 토크나이저)

    public boolean isGlobal() { return observer == null; }
    public boolean isLocal()  { return observer != null; }
}
```

> **참고**: `session` 핸들 대신 `String sessionId` 만 보유한다. Representation 은 불변 값 객체(immutable value object)이므로 살아있는 세션 핸들을 들지 않는다 — 영속·직렬화·cross-session 비교가 단순해진다.

---

## 5. 저장소 인터페이스

[`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md): *저장소는 인터페이스로 분리, 인메모리 기본 제공, 교체는 구현체 교체.*

> **공통 규칙**: 모든 ID 기반 메서드는 `(Workspace ws, ...)` 또는 workspace-bound ID 값 객체(`ObservationId` 등)를 받는다. String id 단독을 받는 메서드는 두지 않는다 — 멀티 테넌트 격리를 컴파일 타임에 강제.

### 5.1 WorkspaceStore

```java
public interface WorkspaceStore {
    Workspace create(Workspace workspace);
    Optional<Workspace> findById(String id);        // 부트스트랩 예외 (아래 주석 참조)
    List<Workspace> findAll(Principal requester);   // 권한 체크 필수
    void delete(Workspace ws);                      // 객체 단위
}
```

> **부트스트랩 예외**: 공통 규칙은 *"String id 단독 금지"* 이지만, **`Workspace` 자체는 멀티 테넌트 격리의 루트 (root-of-trust)** 이므로 자기 자신을 `String id` 로 조회하는 것은 불가피하다. 다른 모든 store 메서드는 이 메서드를 거쳐 `Workspace` 객체를 손에 쥔 뒤에만 호출되도록 제한 — ArchUnit 규칙은 `WorkspaceStore.findById` 단 한 곳만 화이트리스트.

### 5.2 ObservationStore

`ObservationStore` 는 **메타데이터·관계·confidence 전용** 이며, 벡터 검색은
`KnowledgeStore` 에 위임한다. 두 RAG 스택의 평행 구축을 방지하는 결정(리뷰 C3).

```java
public interface ObservationStore {

    Observation save(Observation observation);              // 임베딩은 내부적으로 KnowledgeStore에 위임

    Optional<Observation> findById(ObservationId id);

    List<Observation> findBySubject(PeerView subject, int limit);

    /** Dreamer 에서 사용: 워크스페이스의 모든 subject(peer) 를 1회 순회. 순서 무보장, limit 상한. */
    List<PeerView> findSubjects(Workspace ws, int limit);

    long count(PeerView subject);

    /** Dialectic 에서 사용: 의미 기반 검색.
     *  내부적으로 KnowledgeStore.search(KnowledgeScope.of("memory.observation", workspace), ...)
     *  를 호출한 뒤 ObservationId 로 메타데이터를 join 하여 Observation 으로 hydrate. */
    List<Observation> semanticSearch(PeerView subject, String query, int topK);

    /** Dreamer 에서 사용: 후보군 추출 */
    List<Observation> findByConfidenceBelow(PeerView subject, double threshold, int limit);

    void delete(ObservationId id);

    /** 단일 observation 을 audit window 로 은퇴(soft-delete). 모든 백엔드 공통. */
    void softDelete(ObservationId id);

    /** 30일 audit window 가 지난 soft-deleted observation 을 영구 purge.
     *  Dreamer 사이클 시작 시점에 실행된다. */
    void purgeSoftDeletedBefore(Workspace ws, Instant cutoff);

    /** Reconciler 에서 사용: 두 observation 을 하나로 병합.
     *  loser 는 soft-delete 되어 audit window(30일)에 보관된다 — 모든 백엔드(in-memory/file/postgres)
     *  에서 즉시 폐기하지 않고 동일하게 동작한다. */
    Observation merge(ObservationId winner, ObservationId loser, Observation merged);
}
```

> **구현 참고 — 분산 쓰기 정책**: `KnowledgeStore` 백엔드(OpenSearch 등)는 PostgreSQL 트랜잭션에 참여하지 않으므로 *2-phase commit 은 사용하지 않는다*. 대신 **outbox 패턴** 을 적용:
> 1. PostgreSQL 트랜잭션 안에서 `mem_observation` 메타데이터 + `mem_outbox`(pending embedding) 동시 insert.
> 2. 별도 워커가 `mem_outbox` 를 polling 하여 `KnowledgeStore.upsert()` 를 호출, 성공 시 outbox row 삭제.
> 3. KnowledgeStore upsert 실패는 재시도 — 메타데이터는 이미 권위 있는 진실(SoT) 이므로 일관성은 *eventual*.
>
> 검토했다가 채택하지 않은 대안: outbox 없이 best-effort 동시 호출 + nightly reconciliation 잡. 운영은 단순해지지만
> 재조정이 도는 사이에 검색이 조용히 틀린 답을 준다 — 관찰 저장은 성공했는데 인덱스에는 없는 상태가 밤까지 남는다.
>
> **구현 참고 — scope 전체 재구축**: `KnowledgeStoreOutboxRelay` 는 매 dispatch 마다 (UPSERT 든 DELETE 든) 해당 subject scope 를 **생존(soft-delete 되지 않은) observation 전체로 다시 구축**한다. `KnowledgeStore.reindex` 가 scope 를 비우고 다시 채우는 의미이므로, 단일 행만 스테이징하면 같은 subject 의 다른 observation 이 함께 지워진다 — 그래서 행 단위가 아니라 subject scope 단위로 재색인한다.

### 5.3 RepresentationStore

```java
public interface RepresentationStore {
    Representation save(Representation rep);
    Optional<Representation> findLatestGlobal(PeerView subject);
    Optional<Representation> findLatestLocal(PeerView subject, PeerView observer, String sessionId);
    void deleteOlderThan(Workspace ws, Instant cutoff);
}
```

### 5.4 인메모리 기본 구현 (개발/테스트 전용)

`InMemory*Store` 는 `ConcurrentHashMap` + `CopyOnWriteArrayList` 기반.
의미 검색은 `KnowledgeStore` 의 in-memory 구현 또는 코사인 유사도 브루트포스.

> **merge/soft-delete 동작**: 인메모리 구현도 영속 백엔드와 동일하게 merge 의 loser 와 `softDelete` 대상을 즉시 폐기하지 않고 audit window(30일)에 보관하며, `purgeSoftDeletedBefore` 로 정리한다 (예전처럼 즉시 폐기하지 않는다).

> ⚠️ **운영 사용 금지**. 1만 개 이상 observation 시 OOM·검색 지연이 시작된다.
> CLI 에서 `memory.backend = "in-memory"` 로 부팅하면 비영속 스토어가 배선된다 — CLI 의 기본값은 `file` 이다(§10).

### 5.5 영속 구현 (`aimon-memory-postgres` — **모듈 제거됨**, 첫머리 배너 참조)

| 테이블 | 역할 |
|--------|------|
| `mem_workspace` | Workspace |
| `mem_observation` | Observation 메타데이터 (id, subject, observer, content, type, confidence, ...) |
| `mem_representation` | Representation 스냅샷 |
| `mem_active_work_unit` | **`(workspace, session, observer_type, observer_id)` row-lock 클레임** |
| `mem_outbox` | KnowledgeStore 임베딩 동기화 outbox (§5.2 분산 쓰기 정책) |

벡터 자체는 `KnowledgeStore` 백엔드(예: `aimon-knowledge-opensearch` 또는 pgvector 어댑터)에 저장 — 본 모듈은 벡터 컬럼을 직접 두지 않는다.

#### 데이터소스 공유 정책

`aimon-memory-postgres` 와 `aimon-session-postgres` 가 함께 사용될 때:
- 동일 PostgreSQL 인스턴스 사용을 권장하되, **별도 DataSource 를 가진다** (스키마·풀·트랜잭션 경계 분리).
- 공유 DataSource 가 필요한 경우 CLI 조립 단계에서 명시적으로 같은 인스턴스 주입.
- 마이그레이션은 Flyway, 스키마 prefix 분리 (`mem_*` vs `session_*`).

---

## 6. Reasoning 컴포넌트

### 6.1 Deriver (메시지 → Observation)

#### 6.1.1 큐 모델

`at.aimon.core.agent.queue.MessageQueueManager` 의 패턴을 따르되, 워크 유닛 키를
*세션 단위* 가 아닌 **(workspace, session, observer) triple** 로 확장한다.

```java
public final class DerivationWorkUnit {
    private final String workspaceId;
    private final String sessionId;
    private final Principal.Type observerType;   // USER | SYSTEM | SERVICE | GROUP
    private final String observerId;             // Principal.id
    // equals/hashCode → 큐 키 (Type+id 합성으로 ID 충돌 방지)
}
```

> `Principal` 객체 자체를 갖지 않고 `(Type, id)` 만 갖는 이유: Quartz `JobDataMap` 직렬화 호환·DB 컬럼 매핑 단순화. `Principal` 인스턴스가 필요한 시점에는 lookup.

```java
public interface DerivationQueueManager {
    void enqueue(DerivationTask task);
    void start();                            // 워커 풀 기동
    void stop();                             // graceful 종료
    QueueStats stats();
}
```

#### 6.1.2 클레임 모델

| 환경 | 동시성 제어 |
|------|-----------|
| 단일 인스턴스 | `Semaphore` + `ConcurrentHashMap<DerivationWorkUnit, Lock>` |
| 멀티 인스턴스 | `mem_active_work_unit` row-lock + `SELECT … FOR UPDATE SKIP LOCKED` |

> **핵심 불변식**: 동일 `DerivationWorkUnit` 은 동시에 한 워커만 처리. 서로 다른 work unit 은 자유롭게 병렬.

> **하트비트 갱신(postgres)**: `PostgresDerivationQueueManager` 는 derivation 이 진행 중인 동안 데몬 스레드가 주기적으로 `expires_at` 을 갱신(heartbeat)한다. 따라서 긴 derivation 이 도중에 다른 노드에게 work unit 을 steal 당하지 않는다 — claim 은 작업이 끝날 때까지 살아 있다.

#### 6.1.3 Deriver 인터페이스

```java
public interface Deriver {
    /** 토큰 한도 내에서 메시지 배치를 처리하고 observation 을 도출 */
    DerivationResult derive(DerivationContext ctx);
}

public final class DerivationContext {
    private final Workspace workspace;
    private final String sessionId;                  // 살아있는 세션 핸들이 아닌 id 만 보유 (불변 값 객체)
    private final PeerView observer;
    private final List<Message> messages;           // 배치
    private final int tokenBudget;
}

public final class DerivationResult {
    private final List<Observation> created;
    private final List<Observation> updated;
    private final long llmTokensUsed;
}
```

두 개의 구현이 공존한다 — 운영자는 사용 사례에 따라 선택:

| 구현 | 호출 횟수 | 특징 | 권장 사용처 |
|------|----------|------|------------|
| `LlmDeriver` | 1~2회 | JSON 배열을 한 번에 받아 파싱. (선택적으로 Representation 요약용 2차 호출) | 비용 최적화, 짧은 메시지 배치, 결정론적 처리 |
| `ReActLlmDeriver` | 1~N회 (cap) | 모델이 §6.1.4 의 3개 내부 도구를 ReAct 루프로 호출. 검색→중복 회피→생성→메시지 링크까지 모델이 자기 주도 | 복잡한 추론, 중복 관찰 회피, 출처 메시지 ID 사후 부착 |

둘 다 `Deriver` 인터페이스 구현이므로 `DerivationQueueManager` 는 동일하게 주입받는다.
`ReActLlmDeriver` 는 `maxIterations` (기본 6) 와 `DerivationContext.tokenBudget` 양쪽으로 루프를 한정하며, LLM 예외는 `LlmDeriver` 와 동일하게 swallow 후 부분 결과를 반환한다 (큐 매니저가 throws 만 실패로 판정하기 때문).

`LlmDeriver` 는 `LlmClient` 와 `ObservationStore` 를 생성자 주입받는다 (DIP).
`ReActLlmDeriver` 는 추가로 3개 내부 도구를 직접 인스턴스화하여 보유 — 도구는 stateless 이므로 동시성 안전.

#### 6.1.4 도구 노출

Deriver 가 LLM 호출 시 사용할 *내부 도구* 는 AIMON 의 `Tool` 규약을 그대로 따른다.
이름은 §8 의 사용자 노출 도구(`MemorySearchTool` 등)와 충돌하지 않도록 **`Deriver` prefix** 로 통일하며, `TOOL_NAME` 상수도 별도 네임스페이스(예: `"deriver.observation.create"`)를 사용한다:

| 클래스 | TOOL_NAME | 역할 |
|--------|-----------|------|
| `DeriverObservationCreateTool` | `deriver.observation.create` | 새 observation 생성 |
| `DeriverMemorySearchTool`      | `deriver.memory.search`      | 기존 메모리 의미 검색 (중복 회피용) |
| `DeriverMessageLinkTool`       | `deriver.message.link`       | observation 과 메시지 연결 |

이들은 `at.aimon.core.memory.deriver.tool` 하위에 둔다 (사용자 노출용 `at.aimon.core.tools.memory` 와 패키지 분리).
도구 → fail-safe (`ToolResult.error()`), stateless, 불변 I/O — [`tool-development-guide.md`](../../features/tool/tool-development-guide.md) 준수.

---

### 6.2 Dialectic Engine (자연어 질의)

```java
public interface DialecticEngine {
    DialecticResponse query(DialecticQuery query);

    /** 스트리밍은 AIMON의 LlmStreamSink 패턴을 따른다 (Reactor 의존 없음). */
    void queryStream(DialecticQuery query, LlmStreamSink sink);
}

public final class DialecticQuery {
    private final PeerView subject;
    private final PeerView observer;
    private final String sessionId;                   // optional
    private final String question;
    private final ReasoningLevel level;               // FAST, BALANCED, DEEP
    private final int maxTokens;
}
```

> `LlmStreamSink` 는 `at.aimon.core.llm.streaming` 의 기존 추상. 새로운 reactive 스택 도입 금지.

#### ReasoningLevel — maxTokens 예산 축 (단일 축)

`ReasoningLevel` 은 **레벨당 maxTokens 예산** 하나만 운반한다 (`DialecticTools` 같은 toolset 축은 존재하지 않는다):

```java
public enum ReasoningLevel {
    FAST     ( 4_000),
    BALANCED (16_000),
    DEEP     (64_000);
    // (maxTokens)
}
```

> **NON-implemented (future idea)**: 레벨별 도구셋 분리(`MINIMAL`/`STANDARD`/`FULL`)는 도입하지 않았다. 모든 레벨이 동일한 dialectic 도구 집합을 사용하며 maxTokens 예산만 달라진다. 도구셋 축은 필요 시점의 향후 확장 아이디어로 남겨둔다.

#### 처리 플로우

1. 시스템 프롬프트 구성 → 질문에 대한 임베딩 검색으로 관련 observation **prefetch**
2. 루프 진입 — `LlmDialecticEngine` 이 `LlmClient` 를 직접 돈다
3. 모델이 `DeriverMemorySearchTool`, `GetRecentHistoryTool` 등을 반복 호출
4. 합성 응답 반환 + 토큰/도구 호출 메트릭 기록 (`agent/budget` 패키지 활용)

> **`OrcaAgentExecutor` 를 감싸지 않는다.** 검토했으나 `OrcaAgentExecutionRequest` 는 세션·훅·스킬을 갖춘 턴을
> 전제하는 시그니처인데, dialectic 은 세션이 없는 내부 조회다 — 래핑하면 쓰지 않는 것을 채워 넣어야 한다.
> `LlmDialecticEngine` 은 `LlmClient` 위에 직접 서고, LLM 호출 태깅은 `BoundMetadataLlmClient` 로 받는다.

---

### 6.3 Dreamer (메모리 정제)

> **GLOBAL representation 의 생산자**: `DefaultDreamerEngine` 은 매 사이클마다 각 subject 의 현재 observation 으로부터 cross-session **GLOBAL** Representation 을 재생성한다 (결정론적 요약 — 추가 LLM 비용 없음). 즉 Dreamer 가 `RepresentationStore.findLatestGlobal` / `MemoryRecall` 의 GLOBAL 모드가 읽는 데이터의 유일한 생산자이며, GLOBAL recall 은 Dreamer 가 최소 1회 돈 뒤에야 데이터를 갖는다.

#### 6.3.1 트리거

`aimon-scheduling-quartz` 에 등록되는 long-lived 잡:

```java
public final class DreamerJob implements Job {
    @Override
    public void execute(JobExecutionContext ctx) {
        // JobDataMap 직렬화 안전성을 위해 ID만 전달, 실행 시 lookup
        String workspaceId = ctx.getMergedJobDataMap().getString("workspaceId");
        Workspace ws = workspaceStore.findById(workspaceId)
                .orElseThrow(() -> new JobExecutionException("Workspace not found: " + workspaceId));
        dreamerEngine.consolidate(ws);
    }
}
```

> CLAUDE.md *"Scheduling 컴포넌트는 long-lived"* 정합. AgentRuntime 가 소멸되어도 Dreamer 는 유지된다.

#### 6.3.2 Surprisal 기반 가치 평가

```java
public interface SurprisalScorer {
    /** 이 observation 이 기존 메모리 대비 얼마나 "놀라운가" (정보 이론적 KL/log-likelihood 근사) */
    double score(Observation obs, List<Observation> neighbors);
}
```

#### 기본 구현 식 (`EmbeddingSurprisalScorer`)

LLM 호출 없이 임베딩만으로 계산 — 비용 ≈ 0.
```
sim_max  = max over n in neighbors of cosine(emb(obs), emb(n))
sim_mean = mean over n in neighbors of cosine(emb(obs), emb(n))
surprisal = (1.0 - sim_max) * 0.7
          + (1.0 - sim_mean) * 0.3
// neighbors 가 비면 surprisal = 1.0 (완전히 새로운 사실)
```
- 점수가 **낮은**(`< 0.2`, 중복성 높은) 관찰은 통합 후보 → Dreamer 가 merge.
- 점수가 **높은**(`> 0.7`, 반박 가능성 큰) 관찰은 Reconciler 검토 후보.
- 임계값은 CLI 의 `memory.dreamer.surprisal-threshold` 로 튜닝한다(기본 `0.25` — §10).

#### 6.3.3 Random Walk Consolidation

```java
public interface ConsolidationStrategy {
    ConsolidationPlan   plan(Workspace ws, PeerView subject);
    ConsolidationResult apply(ConsolidationPlan plan);
}
```

> `apply` 는 `ConsolidationResult`(observationsRemoved / clustersApplied / failures)를 반환한다 — Dreamer 가 *계획된* 작업이 아니라 *실제로 수행된* 작업을 사이클 요약에 보고하도록 한다.

`RandomWalkDreamer` 는:
1. 최근/고가치 observation 시드 N개 선택
2. 각 시드에서 의미 검색으로 이웃 K개 확장
3. SurprisalScorer 로 클러스터링
4. LLM 으로 통합 요약 생성 → `ObservationStore.merge()` 실행

---

### 6.4 Reconciler (충돌 조정)

`Reconciler.evaluate(candidate, conflicts)` 는 새 observation 이 기존과 부딪힐 때 **네 결정 중 하나**를
돌려준다. Deriver(새 관찰이 들어올 때)와 Dreamer(정제 사이클) 양쪽에서 호출되며, 두 경로가 같은 판단기를
쓰기 때문에 단일 진실 원천이 유지된다.

| 결정 | 뜻 | 싣는 값 |
|------|-----|--------|
| `Accept` | 충돌이 아니다 — 그대로 추가 | 없음 (싱글턴) |
| `Replace` | 기존 것이 낡았다 | 대체되는 `ObservationId` |
| `Merge` | 둘이 같은 사실의 조각이다 | 상대 `ObservationId` + 병합된 `Observation` |
| `Reject` | 새 것을 버린다 | 사유 문자열 |

`ReconcileDecision` 은 **sealed interface + final class** 다. 결정이 값을 싣기 때문에 enum 으로는
표현되지 않고, `record` 는 프로젝트 규약이 금지한다
([`code-style.md`](../../../.claude/rules/code-style.md)). sealed 이므로 소비자의 분기가
컴파일 타임에 전수 검사된다 — 다섯 번째 결정을 추가하면 처리를 빠뜨린 자리가 전부 컴파일 에러가 된다.

`DefaultReconciler` 는 **heuristic 빠른 경로**로 명백한 경우를 먼저 걸러 내고 남은 것만 LLM judge 에
넘긴다. judge 응답이 conflict-set 검증을 통과하지 못하면 보수적 fallback(`Accept`)으로 떨어진다 —
판단하지 못한 것을 삭제로 해석하지 않는다.

---

### 6.5 RedactionPolicy (PII / 시크릿 차단)

IT 운영 도메인의 메시지는 토큰·비밀번호·API 키·사설 IP 가 일상적으로 등장한다.
이들이 LLM 으로 전송되어 observation 으로 영구화되면 즉시 보안 사고이므로,
**Deriver 큐 진입 직전**에 마스킹하는 강제 게이트를 둔다.

```java
public interface RedactionPolicy {
    /** 메시지 텍스트에서 민감 정보를 마스킹. 호출은 idempotent. */
    RedactionResult redact(String content);
}

public final class RedactionResult {
    private final String redactedContent;
    private final List<RedactionMatch> matches;     // {pattern, span, replacement}
    private final boolean modified;
}
```

#### 기본 구현 `DefaultRedactionPolicy`

| 패턴 카테고리 | 예 | 마스킹 |
|--------------|----|-------|
| AWS access key | `AKIA[0-9A-Z]{16}` | `[REDACTED:AWS_KEY]` |
| Bearer/JWT | `eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+` | `[REDACTED:JWT]` |
| 사설 IP | `10.*`, `192.168.*`, `172.16-31.*` | `[REDACTED:PRIVATE_IP]` |
| Email | RFC5322 단순화 | `[REDACTED:EMAIL]` |
| 일반 시크릿 | `(api[_-]?key|password|token)\s*[:=]\s*\S+` | `[REDACTED:SECRET]` |

#### 적용 지점

1. **`DerivationQueueManager.enqueue()` 내부에서 워커 디스패치 직전** 강제 호출 — 호출자(caller)가 우회할 수 없도록 매니저의 책임으로 못박는다. (§7 시퀀스 다이어그램과 일치)
2. `MemorySearchTool` / `MemoryChatTool` 의 입력 query — 사용자 입력에도 시크릿 포함 가능.

#### Observation 추적

마스킹된 observation 은 `metadata` 에 다음 키 보유:
- `redacted = true`
- `redaction.categories = "AWS_KEY,JWT"` (CSV)

#### Opt-out / 강화

정책은 교체 가능하다. 스타터에서는 `aimon.memory.redaction` 이 네 값을 갖는다:

- `default` (기본) — `DefaultRedactionPolicy`
- `strict` — `StrictRedactionPolicy`. 기본 규칙에 더해 오타 난 시크릿 키워드까지 잡는 퍼지 패스를 돈다
- `supplied` — 애플리케이션이 자기 `RedactionPolicy` 빈을 선언한다. 빈이 없으면 **부팅이 실패**하고, 반대로
  빈만 있고 이 값을 고르지 않아도 실패한다(선언과 배선이 어긋난 채 조용히 기본값으로 도는 것을 막는다)
- `none` — **운영 사용 금지**. 관찰이 그대로 저장된다

**`none` 이 기본이 아닌 이유**는 이 설정만 되돌릴 수 없기 때문이다. 에이전트는 대화에 들어온 것 중 무엇을
관찰할지 스스로 정하고, 스토어는 그것을 세션을 넘겨 보관했다가 나중 프롬프트에 되돌려 준다. 한 번 들어간
자격증명은 운영자가 고르지 않은 곳에 복사된 것이고 만료되지도 않으며, 나중에 이 값을 바꿔도 지워지지 않는다.
그래서 다른 항목은 전부 명시 선언을 요구하는 스타터가 이것 하나만 기본값을 갖는다.

---

## 7. 메시지 처리 시퀀스

```
┌────────┐      ┌────────────┐      ┌──────────────┐      ┌─────────────┐
│ Agent  │      │ LiveSess-  │      │ Derivation   │      │  Deriver    │
│ Tool   │      │ ion        │      │ QueueManager │      │  Worker     │
└───┬────┘      └─────┬──────┘      └──────┬───────┘      └──────┬──────┘
    │                 │                     │                     │
    │ ObserveTool.    │                     │                     │
    │ execute()       │                     │                     │
    ├────────────────▶│                     │                     │
    │                 │ append message      │                     │
    │                 ├──────────┐          │                     │
    │                 │          │          │                     │
    │                 │◀─────────┘          │                     │
    │                 │ enqueue task        │                     │
    │                 ├────────────────────▶│                     │
    │                 │                     │ ★ RedactionPolicy   │
    │                 │                     │   .redact()         │
    │                 │                     │ (시크릿 마스킹)     │
    │                 │                     │                     │
    │                 │                     │ claim work unit     │
    │                 │                     │ (row-lock SKIP LCK) │
    │                 │                     ├────────────────────▶│
    │                 │                     │                     │ LlmDeriver
    │                 │                     │                     │ EmbeddingClient.embed
    │                 │                     │                     │ batch + LLM
    │                 │                     │                     │ → observations
    │                 │                     │                     │
    │                 │                     │                     │ Reconciler.evaluate
    │                 │                     │                     │
    │                 │                     │                     │ ObservationStore.save
    │                 │                     │                     │  ├─ 메타: mem_observation
    │                 │                     │                     │  └─ 임베딩: KnowledgeStore
    │                 │                     │                     │
    │                 │                     │ release lock        │
    │                 │                     │◀────────────────────┤
    │                 │                     │                     │
    │                 │                     │ webhook: empty      │
    │                 │                     ├──┐                  │
    │                 │                     │  │                  │
    │                 │                     │◀─┘                  │
```

---

## 8. AIMON Tool 통합 (사용자 노출 표면)

ReAct 에이전트가 메모리 레이어를 활용할 수 있도록 4개 Tool 을 노출.
모두 `at.aimon.core.tools.memory` 패키지 — [`tool-development-guide.md`](../../features/tool/tool-development-guide.md) 준수.

| Tool | 용도 |
|------|------|
| `MemorySearchTool` | 이 peer 의 observation 들에서 키워드/의미 검색 |
| `MemoryChatTool` | "이 사용자에 대해 알려줘" 자연어 질의 (Dialectic) |
| `MemoryRecallTool` | Representation 으로 *peer 통찰* 을 토큰 예산 내 컨텍스트로 주입 |
| `ObserveTool` | 명시적 관찰 등록 (관리자/시스템용) |

각 Tool 의 `execute()` 는 결코 예외를 던지지 않으며, 실패 시 `ToolResult.error()` 반환 (CLAUDE.md tool 규칙).

#### `MemoryRecallTool` ↔ `at.aimon.core.agent.compact` 의 차이

기존 `agent.compact` 패키지는 **현재 진행 중인 대화 메시지** 를 토큰 한도에 맞게 압축한다 (단기, 세션 내).
`MemoryRecallTool` 은 **세션을 가로지른 peer 통찰 표상**(Representation)을 컨텍스트로 주입한다 (장기, 세션 간).
둘은 보완 관계이며 동시 사용 가능 — 실행기는 *현재 대화 압축본 + peer 통찰 요약* 을 함께 LLM 에 보낼 수 있다.

---

## 9. 의존성 다이어그램

```
┌──────────────────────────────────────────────────┐
│  aimon-core                                      │
│   ├ base.Principal ◀──┐                          │
│   ├ agent.session     │                          │
│   ├ agent.queue ◀──┐  │                          │
│   ├ knowledge ◀──┐ │  │                          │
│   ├ llm ◀──────┐ │ │  │                          │
│   └ memory ────┴─┴─┴──┘   (신규)                │
│        ▲                                         │
└────────┼─────────────────────────────────────────┘
         │ implementation(project(":aimon-core"))
         │
   ┌─────┴───────────┬────────────────────────┐
   │                 │                        │
aimon-memory-file  aimon-memory-postgres   aimon-memory-mongodb
(JSON-line 영속 +  (메타데이터 + outbox     (컬렉션 = SSOT,
 compaction/락/    → KnowledgeStore)        soft-delete/retention)
 maintenance)

aimon-cli  ── 둘 중 하나를 조립 선택
```

`memory` 패키지는 **인터페이스에만 의존** (DIP). 구현체는 별도 모듈.
벡터 검색은 별도 메모리 어댑터를 두지 않고 `KnowledgeStore`(예: `aimon-knowledge-opensearch`)에 위임한다 — 별도 `aimon-memory-opensearch` 모듈은 채택하지 않았다(§3).

---

## 10. 설정 표면

**코어에는 메모리 설정 트리가 없다.** 설정은 조립(assembly) 계층의 관심사이므로 CLI 와 스타터가 각자의
표면을 갖고, 둘 다 `at.aimon.bootstrap.spec.MemorySpec` 이라는 하나의 중립 스펙으로 정규화한다. 코어에
남은 설정 값 객체는 deriver 큐 튜닝 하나뿐이다 — 나머지는 어떤 조립 경로도 바인딩하지 않는 기본값이었다.

### 10.1 코어 — `DeriverProperties`

`at.aimon.core.memory.deriver.DeriverProperties` (immutable, [`.claude/rules/immutability-pattern.md`](../../../.claude/rules/immutability-pattern.md) 준수):
`workerCount`, `batchMaxTokens`, `pollInterval`. `defaults()` 와 `of(...)` 로 만든다.

### 10.2 조립 — `MemorySpec`

`MemorySpec` 은 배선에 필요한 것만 담는다: `workspace`, `fixedPeer`(peer 고정 모드일 때),
`representationStore`, `observationStore`, `injectionMode`, `maxTokens`, `redactionPolicy`.
진입점은 두 개다 — `forPeer(workspace, peer)` 와 `perCaller(workspace)`. 즉 **peer 모드는 스펙의 필드가
아니라 팩토리 선택**이며, 이 때문에 `fixed` 인데 `peerId` 가 없는 조합이 타입 수준에서 만들어지지 않는다.

### 10.3 CLI — `memory` yaml 블록

`at.aimon.cli.config.MemoryConfig`:

IMPORTANT: **CLI 의 키는 camelCase 다.** 이 절은 한때 kebab-case 로 적혀 있었고 그것은 **부팅을
실패시키는 오류**였다 — `MemoryConfig` 의 `@JsonProperty` 가 전부 camelCase 이고 네이밍 전략 설정이
없으며, `CliConfigLoader:36` 이 `FAIL_ON_UNKNOWN_PROPERTIES` 를 끄지 않으므로 `workspace-id` 는
무시되는 것이 아니라 `UnrecognizedPropertyException` → `ConfigurationException` 이 된다. kebab-case 는
**스타터 프로퍼티**(`aimon.memory.workspace-id`, §10.4)의 규약이며 두 표면은 섞이지 않는다.

```yaml
memory:
  workspaceId: ops
  peerId: alice
  peerName: Alice
  storagePath: ~/.aimon/memory
  backend: file             # file (기본) | in-memory
  reconcilerEnabled: true
  dreamer:
    enabled: true
    cron: "*/30 * * * *"
    surprisalThreshold: 0.25
    walkSeedCount: 8
    neighborTopK: 8
    scorer:
      type: llm             # llm (기본) | embedding
```

`backend` 는 `file` 이 기본이며 `file` / `in-memory` 두 값만 배선된다. 모르는 값은 `file` 로 떨어지되
**경고는 절반만 나온다** — `createObservationStore:967-970` 은 관찰 스토어에 대해
`"unknown backend '...', falling back to file for observations"` 를 내지만
`createRepresentationStore:929-943` 은 `in-memory` 가 아니면 **말없이** `FileRepresentationStore` 로
간다. 양쪽을 맞추는 것은
[교체 가능한 메모리 백엔드](pluggable-memory-backend.md) §9.2 의 항목이다.
PostgreSQL·OpenSearch 백엔드는 모듈로는 존재하지만 CLI 에 배선되어 있지 않다.

### 10.4 스타터 — `aimon.memory.*`

`AimonProperties.Memory`:

| 키 | 값 | 비고 |
|----|-----|------|
| `backend` | `none`(기본) / `in-memory` / `supplied` | 기본이 `none` 인 것은 의도다 — 켜져 있는데 비어 있는 메모리는 고장 난 메모리로 읽힌다 |
| `workspace-id` | 문자열 | 백엔드를 지정하는 순간 **필수**. 기본값 없음 |
| `peer-mode` | `fixed` / `caller` | 박싱되어 있다 — "안 건드림"과 "`fixed` 를 골랐음"이 구분되어야 하기 때문 |
| `peer-id` | 문자열 | `peer-mode=fixed` 에서 필수, `caller` 에서는 거부 |
| `injection-mode` | `MemoryInjectionMode` | 박싱 |
| `max-tokens` | 정수 | 박싱 — `0` 이 "상한 없음"이라는 적법한 값이라 "미설정"을 겸할 수 없다 |
| `redaction` | `default`(기본) / `strict` / `supplied` / `none` | §6.5 |

`redaction` 만 기본값을 갖고 나머지는 명시 선언을 요구하는 이유는 §6.5 에 있다.

---

## 11. 백엔드별 보장 — 무엇이 어디까지 보장되는가

백엔드는 `ObservationStore` / `RepresentationStore` / `WorkspaceStore` / `DerivationQueueManager` 네
인터페이스를 각자 구현하지만, **모두가 같은 것을 보장하지는 않는다.** 어느 백엔드를 고르는가가 곧 어떤
보장을 사는가다.

| 보장 | in-memory | file | mongodb | postgres |
|------|-----------|------|---------|----------|
| 재시작 후 복원 | ✗ | ✓ | ✓ | ✓ |
| soft-delete + 감사 윈도 | ✓ | ✓ | ✓ | ✓ |
| **멀티 인스턴스** | ✗ | ✗ | ✗ | **✓** |

**멀티인스턴스 보장은 PostgreSQL 백엔드에만 있다.** `PostgresDerivationQueueManager` 가 다중 holder 경합,
만료 claim steal, holder 소유 검증, work-unit 단위 직렬화 + 단위 간 병렬을 지원하며, 여기에 **claim
하트비트**(데몬 스레드가 in-flight 중 `expires_at` 을 갱신)가 붙어 긴 derivation 의 work unit 을 다른
노드가 훔쳐 가지 못하게 한다. 나머지 셋은 데이터가 남을 뿐 **단일 JVM 한정**이다 — 파일·Mongo 백엔드를
두 노드에서 같은 저장소에 붙이면 큐가 같은 work unit 을 두 번 돌린다.

`softDelete` / `purgeSoftDeletedBefore` 는 `ObservationStore` 의 **`default` 메서드**이고 기본 구현은
하드 삭제 + no-op 이다. 즉 감사 윈도는 인터페이스가 아니라 **구현체가 주는 것**인데, 현재 트리의 네
구현이 전부 override 하고 있으므로 실제로는 어디서나 성립한다. 새 백엔드를 만들 때 둘 중 하나만
override 하면 조용히 감사 추적을 잃는다 — javadoc 이 "둘을 함께 override 하라"고 명시하는 이유다.

**`Deriver` 구현이 둘인 것은 미완성이 아니라 선택지다.** single-shot(`LlmDeriver`)과 ReAct
루프(`ReActLlmDeriver`)는 비용·정확도 트레이드오프가 다르므로 §6.1 의 비교표대로 운영자가 고른다.

---

## 12. 보안 · 안전성

메모리 계층은 **LLM 이 쓴 내용을 영구화**하는 유일한 서브시스템이다. 그래서 방어는 전부 "쓰기 전"에 있다.

| 방어 | 무엇을 막는가 | 어디에 있는가 |
|------|--------------|--------------|
| **레닥션 게이트** | 토큰·비밀번호·PII 가 observation 으로 영구화되는 것 | `MessageRedactor` 가 **Deriver 큐 진입 직전** 마스킹한다. 모든 큐 매니저가 이 게이트를 공유하므로 우회 경로가 없다 (§6.5) |
| **레닥션 기본값** | 설정 누락으로 보호가 꺼지는 것 | `redaction` 만 기본값(`default`)을 갖는 프로퍼티다. `none` 은 시작 시 degradation 으로 기록된다 (§10) |
| **workspace 강제** | 한 워크스페이스 데이터가 다른 곳에 노출되는 것 | 모든 store 메서드가 `Workspace` 또는 workspace-bound 값 객체(`ObservationId`)를 받는다 — **컴파일 타임** 강제. `MemoryArchitectureTest` 가 `at.aimon.core.memory` 의 메서드 시그니처에 이 규칙을 건다 |
| **감사 윈도** | Dreamer 의 병합이 좋은 메모리를 회복 불가하게 지우는 것 | `merge` 의 loser 는 삭제가 아니라 soft-delete 다. `purgeSoftDeletedBefore` 가 30일 윈도를 강제하며 매 Dreamer 사이클 시작 시 실행된다 (§11) |
| **비영속 기본값 회피** | 인메모리 스토어로 운영에 진입하는 것 | 어떤 조립 경로도 기본으로 `in-memory` 를 가리키지 않는다 — CLI 는 `file`, 스타터는 `none`(아무것도 배선하지 않음)이다. `in-memory` 는 명시 선택해야 켜진다 |
| **비용 폭증 억제** | Deriver 가 LLM 호출을 무한정 내는 것 | 배치 크기·polling 간격의 보수적 기본값 + `agent/budget` 의 상한. 임베딩은 비동기이므로 메시지 추가 경로를 막지 않는다 |

---

## 13. 설계 결정

| # | 쟁점 | 결정 |
|---|------|------|
| D1 | **Workspace ↔ AgentRuntime 수명** — Memory 컴포넌트는 application-scoped 인가 agent-scoped 인가 | **application-scoped**, builder 주입. CLAUDE.md 의 *"AgentRuntimeRegistry 는 외부에서 생성해 주입한다"* 와 같은 이유다 — 메모리는 에이전트 런타임보다 오래 살아야 한다. |
| D2 | **Memory ↔ KnowledgeStore 경계** | `ObservationStore` 가 `KnowledgeStore` 로 위임하되 인덱스를 `KnowledgeScope("memory.observation")` 로 분리한다(§5.2 C3). 검색 엔진을 다시 만들지 않으면서 지식 문서와 관찰이 같은 결과 목록에 섞이지 않는다. |
| D3 | **Principal vs PeerView** — 어댑터인가 base 확장인가 | 어댑터(`PeerView`). `at.aimon.core.base.Principal` 을 건드리면 영향 범위가 메모리 밖으로 나간다. |
| D4 | **세션 메시지 영속** — Memory 가 사본을 갖는가 참조만 갖는가 | **참조만**. 영속은 `aimon-session-*` 에 위임하고 observation 의 `sourceMessageIds` 가 외래키 역할을 한다. 같은 메시지를 두 수명이 각자 보관하면 어느 쪽이 진실인지 정할 수 없다. |
| D5 | **임베딩 모델 선택** | `EmbeddingClient` 가 정한다. 메모리 모듈은 차원·모델에 무관하다. |
| D6 | **`Deriver` 구현을 둘 다 남긴다** | 하나로 수렴시키지 않았다. 비용·정확도 트레이드오프가 배포마다 다르고, 둘 다 같은 인터페이스 뒤에 있으므로 유지 비용이 구현체 하나만큼이다 (§6.1). |
| D7 | **감사 윈도를 인터페이스 강제가 아니라 `default` 로 둔다** | 추상 메서드로 만들면 감사 능력이 없는 백엔드를 아예 구현할 수 없다. 대신 기본 구현을 **하드 삭제**로 두어, override 하지 않은 백엔드가 조용히 감사를 잃는 것이 아니라 명시적으로 갖지 않게 했다 (§11). |

---

## 14. 남은 것 · 하지 말 것

### 14.1 아직 코드가 없는 것

| 항목 | 현재 | 필요한 것 |
|------|------|----------|
| **관측성 배선** | 계층이 자기 수치를 **값 객체로 내놓는 데까지만** 간다 — `DreamerCycleSummary` 의 `elapsedMillis` · `clusterFailures`, 큐의 `QueueStats` | OpenTelemetry span 이나 Prometheus 메트릭(queue depth, derive latency, dreamer cycle time)으로의 배선. 계측 백엔드를 고르는 것은 조립 계층의 일이므로 **이 계층에 넣지 않는다** — 조립 쪽에 붙일 어댑터가 없는 것이다 |
| **file / mongodb 백엔드의 멀티 인스턴스** | 단일 JVM 한정 (§11) | 큐의 claim 을 저장소 수준에서 원자적으로 만들 방법이 백엔드마다 다르다. Postgres 의 row-lock 에 해당하는 것을 각자 찾아야 한다 |
| **인메모리 인덱스의 노드 간 동기화** | `InMemoryObservationIndex` 는 프로세스 로컬 | `KnowledgeStoreObservationIndex` 로 외부 검색 백엔드를 쓰면 자연히 해소된다. 별도 동기화 기계를 만들 계획은 없다 |

### 14.2 하지 말 것

- **store 메서드에 `String` id 를 단독 파라미터로 받지 말 것.** `Workspace` 또는 workspace-bound 값
  객체를 받는다. `MemoryArchitectureTest` 가 빌드에서 막으며, 이것이 멀티테넌시 격리의 유일한
  컴파일 타임 강제다.
- **레닥션 게이트를 우회하는 큐 경로나 티어 호출 경로를 만들지 말 것.** 새 `DerivationQueueManager`
  구현은 `MessageRedactor` 를 반드시 경유한다. 티어 쪽도 같다 — `MemoryIngestor` · `ObservationRecorder`
  · `MemorySearcher` · `DialecticEngine` 은 조립이 씌우는 `RedactingPeerMemory` 를 지나야 하며,
  감싸지 않은 `PeerMemory` 를 스택에 넘기는 경로를 만들면 안 된다. 게이트가 하나이기 때문에 §12 의
  보장이 성립하고, 새 SPI 가 공개 접근자를 열었으므로 그 하나를 **구현 안**에 두는 것이 유일한 방법이다
  ([교체 가능한 메모리 백엔드](pluggable-memory-backend.md) §6.2).
  `MemorySnapshotReader` 만 예외인데, 그 티어의 입력에는 호출자가 쓴 자유 텍스트가 없기 때문이다.
- **`softDelete` 만 override 하고 `purgeSoftDeletedBefore` 를 두지 말 것.** 감사 윈도가 무한이 되어
  soft-delete 가 영구 누적으로 바뀐다 (D7).
- **Memory 가 세션 메시지 사본을 갖게 하지 말 것.** `sourceMessageIds` 참조만 든다 (D4).
- **`record` 를 쓰지 말 것.** 모든 값 객체는 `final class` + builder + `Objects.requireNonNull` 이다
  ([`immutability-pattern.md`](../../../.claude/rules/immutability-pattern.md),
  [`code-style.md`](../../../.claude/rules/code-style.md)).

---

## 부록. 참조 파일 지도

| 위치 | 무엇을 보나 |
|------|------------|
| `at/aimon/core/memory/` (root) | 도메인 값 객체 + 세 저장소 인터페이스 + 인메모리 구현 + `IndexedObservationStore` 데코레이터 + 워크스페이스 접근 정책 |
| `at/aimon/core/memory/ObservationStore.java` | `softDelete` / `purgeSoftDeletedBefore` 의 `default` 본문과 그 계약 (§11, D7) |
| `at/aimon/core/memory/deriver/` | `Deriver` 두 구현과 큐 매니저. `deriver/tool/` 은 ReAct deriver 전용 내부 도구 3종 |
| `at/aimon/core/memory/redaction/` | `MessageRedactor` 게이트와 두 정책 (§6.5, §12) |
| `at/aimon/core/memory/dreamer/` | 통합 사이클 — `DefaultDreamerEngine`, `RandomWalkDreamer`, surprisal scorer 2종 |
| `at/aimon/core/memory/reconciler/` | `DefaultReconciler` — heuristic 빠른 경로 + LLM judge |
| `at/aimon/core/memory/dialectic/` | `LlmDialecticEngine` — 질의 응답과 스트리밍 |
| `at/aimon/core/memory/index/` | `ObservationIndex` 와 `KnowledgeStoreObservationIndex` 위임 (D2) |
| `at/aimon/core/tools/memory/` | 사용자에게 노출되는 도구 4종 |
| ~~`modules/aimon-memory-postgres/`~~ | 제거됨 — 멀티 인스턴스는 원격 `PeerMemory` 백엔드가 맡는다 (문서 첫머리) |
| `at/aimon/core/memory/file/` (옛 `modules/aimon-memory-file/`) | 영속하지만 단일 JVM 한정인 백엔드. `-mongodb` 는 함께 제거됨 |
| `at/aimon/core/architecture/MemoryArchitectureTest.java` | 시그니처 규칙이 실제로 강제되는 지점 (§12) |

## 관련 문서

- [Memory(Peer Memory) 사용 가이드](../../features/memory/memory-usage-guide.md) — 배선·설정·운영
- [Knowledge Search 와 RAG 설계](../knowledge/knowledge-and-rag.md) — 위임 대상 `KnowledgeStore` (D2)
- [스코프 모델](../../overview/scope-model.md) — application-scoped 결정의 근거 (D1)
- [SOLID 원칙](../../project/solid-principles.md)
