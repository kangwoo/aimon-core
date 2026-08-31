# Knowledge Search 와 RAG 설계

> Status: **IMPLEMENTED** — `at.aimon.core.knowledge`(main 12 + `embedding/` 3 / test 9) +
> `aimon-knowledge-opensearch`(main 11 / test 5). 같은 트리의 `at.aimon.core.knowledge.wiki`(main 42)는
> **이 문서의 범위가 아니다**(§2.2). 남은 작업은 §12.

에이전트가 보유한 문서를 **키워드 검색**과 **시맨틱 검색(RAG)** 으로 조회하는 통합 지식 계층의 설계다.
검색 백엔드는 `KnowledgeStore` 인터페이스 뒤에 있으므로 인메모리 TF-IDF ↔ OpenSearch BM25/kNN 을
구현체 교체로 바꾼다.

| 능력 | 무엇을 하는가 |
|------|--------------|
| **코어 SPI** | `KnowledgeStore` 하나로 인덱싱·검색·상태조회를 묶고, 문서 원본은 VFS 가 소유한다 (§3) |
| **멀티테넌시** | `KnowledgeScope(agentName, contextId)` 로 문서를 태깅해 공유 인덱스 위에서 격리한다 (§4) |
| **키워드 검색** | 외부 의존성 없는 인메모리 역인덱스 + TF-IDF, 스코프별 CopyOnWrite 스냅샷 (§5) |
| **임베딩 추상화** | `EmbeddingClient` — LLM 프로바이더 모듈이 구현한다 (§6) |
| **OpenSearch 백엔드** | `SearchMode(KEYWORD/VECTOR/HYBRID)` 로 BM25·kNN·가중 하이브리드를 고른다 (§7) |
| **도구 배선** | `KnowledgeSearch` 도구를 eager 로 등록하고 `ToolContext` 로 store+scope 를 주입한다 (§8) |

---

## 1. 개요

### 1.1 목적

에이전트가 특정 디렉토리에 보유한 문서(knowledge)를 키워드 또는 시맨틱 검색으로 조회할 수 있게 한다.
검색 백엔드(키워드, 벡터 DB)는 인터페이스 기반으로 교체 가능해야 한다.

### 1.2 배경

`GrepTool` / `ReadTool` 만으로는 다음 네 가지가 해결되지 않는다.

| 문제 | 설명 |
|------|------|
| **시맨틱 검색 불가** | 정규식·키워드 매칭만 가능. "배포 실패 시 대응 방법" 같은 자연어 질의에 취약 |
| **에이전트별 지식 범위 미분리** | 모든 도구가 VFS 전체를 대상으로 동작. 에이전트별 지식 디렉토리 개념이 없음 |
| **문서 검색 최적화 부재** | 청크 분할·인덱싱 없이 원본 파일을 통째로 검색 |
| **구조화된 결과 부재** | `GrepTool` 은 라인 매칭을 반환. 문서 단위의 관련도 정렬 불가 |

### 1.3 핵심 설계 원칙

- **인터페이스 기반 백엔드 교체** — `KnowledgeStore` 를 core 에 두고 백엔드 구현은 별도 모듈로 (DIP)
- **기존 VFS 활용** — 문서 원본은 `VirtualFileSystem` 이 소유한다. `KnowledgeStore` 는 **검색 인덱스만** 관리
- **공유 인덱스 위의 격리** — 인덱스를 에이전트마다 쪼개는 대신 문서를 스코프로 태깅하고 검색 시 필수 필터로 건다
- **점수 계약의 통일** — 모든 구현체가 `score` 를 `[0.0, 1.0]` 으로 정규화한다 (§9 D8)
- **멀티 인스턴스 호환** — 인덱스 저장소를 인터페이스로 분리하여 스케일아웃 환경에서 동작 가능

### 1.4 용어

| 용어 | 정의 |
|------|------|
| Knowledge | 에이전트가 보유한 문서 파일 집합. VFS 의 특정 디렉토리에 저장 |
| Document | 단일 문서 파일 (예: `runbook.md`, `faq.txt`) |
| Chunk | Document 를 검색 단위로 분할한 텍스트 조각 |
| Scope | 인덱스된 문서의 소유자 — `(agentName, contextId)` 쌍 |
| Embedding | 텍스트를 벡터로 변환한 수치 표현. 시맨틱 검색에 사용 |
| Indexing | 문서를 검색 가능한 형태로 변환하여 저장하는 과정 |

---

## 2. 아키텍처

### 2.1 계층

```
┌──────────────────────────────────────────────────────────────┐
│ Tool Layer                                                   │
│   KnowledgeSearchTool  ("KnowledgeSearch")                   │
│     ↑ ToolContext: KNOWLEDGE_STORE + KNOWLEDGE_SCOPE         │
└──────────────────────────────────────────────────────────────┘
┌──────────────────────────────────────────────────────────────┐
│ aimon-core : at.aimon.core.knowledge                         │
│                                                              │
│   SPI          KnowledgeStore   DocumentChunker              │
│                EmbeddingClient  (.embedding)                 │
│   값 객체       KnowledgeScope  KnowledgeSource               │
│                SearchQuery  SearchResult  DocumentChunk      │
│                IndexOptions  IndexResult  IndexStatus        │
│   기본 구현     KeywordKnowledgeStore  SimpleDocumentChunker  │
└──────────────────────────────────────────────────────────────┘
        ▲                                    ▲
        │ implements KnowledgeStore          │ implements EmbeddingClient
┌───────┴────────────────────────┐  ┌────────┴─────────────────┐
│ aimon-knowledge-opensearch     │  │ aimon-llm-openai         │
│   OpenSearchKnowledgeStore     │  │   OpenAIEmbeddingClient  │
│   OpenSearchConfig / SearchMode│  │   OpenAIEmbeddingConfig  │
│   OpenSearchClientFactory      │  └──────────────────────────┘
│   OpenSearchIndexManager       │
│   OpenSearchDocumentMapper     │
│   ScopeFilter                  │
│   OpenSearchSearchStrategy     │
│     ├ KeywordSearchStrategy    │  (BM25)
│     ├ VectorSearchStrategy     │  (kNN)
│     └ HybridSearchStrategy     │  (가중 결합)
└────────────────────────────────┘
```

의존 방향은 한 방향이다 — 백엔드 모듈과 LLM 프로바이더 모듈이 `aimon-core` 를 향하고, core 는 둘 중
어느 쪽도 알지 못한다. `aimon-llm-openai` 가 `EmbeddingClient` 를 구현할 수 있는 것은 그 인터페이스가
knowledge 모듈이 아니라 core 에 있기 때문이다 (§9 D3).

### 2.2 스코프 — `KnowledgeStore` 는 application-scoped 다

`KnowledgeStore extends ApplicationScoped, AutoCloseable` 이다. 즉 **`AgentRuntime` 소멸과 함께 닫으면
안 된다.** 저장소 하나를 여러 agent runtime 이 공유하고, 각 runtime 은 호출할 때마다 자기
`KnowledgeScope` 를 넘긴다.

이는 초기 설계(저장소를 agent-scoped 로 두고 runtime 이 닫는다)와 다르다. 뒤집힌 이유는 §10 에 있다.
`ApplicationScoped` 가 **문서 목적의 마커**일 뿐 자동 소멸을 뜻하지 않는다는 점은
[`scope-model.md`](../../overview/scope-model.md) §2 를 따른다.

> `at.aimon.core.knowledge.wiki` (`WikiKnowledgeBase` 이하)는 같은 패키지 트리에 있지만 **이 문서가
> 다루는 계층이 아니다** — 문서를 생성·병합·린트하는 별개 서브시스템이며 `KnowledgeStore` SPI 를
> 경유하지 않는다. 이 문서의 두 출처(키워드 검색 설계, OpenSearch RAG 설계) 중 어느 쪽도 그것을
> 설계하지 않았으므로 여기에 근거를 만들어 붙이지 않는다.

---

## 3. 코어 SPI 와 값 객체

### 3.1 `KnowledgeStore`

```java
public interface KnowledgeStore extends ApplicationScoped, AutoCloseable {
    IndexResult index(KnowledgeScope scope, KnowledgeSource source, IndexOptions options);
    IndexResult reindex(KnowledgeScope scope, KnowledgeSource source, IndexOptions options);
    List<SearchResult> search(KnowledgeScope scope, SearchQuery query);
    IndexStatus getStatus();
    @Override void close();
}
```

**행위 명세**

- `index()` — `source` 의 VFS·디렉토리에서 파일을 스캔하고, `DocumentChunker` 로 청크를 분할한 뒤
  인덱스에 저장한다. 현재 `index()` 와 `reindex()` 의 동작은 동일한 전체 인덱싱이다. 증분 인덱싱이
  도입되면 `index()` 만 파일 해시 비교로 변경분을 처리하도록 확장된다(§12).
- `reindex()` — 그 스코프의 기존 인덱스를 버리고 전체를 다시 인덱싱한다. 증분 인덱싱이 도입된 뒤에도
  항상 전체 재인덱싱이다.
- `search()` — 관련도 내림차순 `SearchResult` 목록. 결과가 없으면 **빈 리스트**(에러 아님).
  인덱싱이 진행 중(`INDEXING`)이어도 블로킹하지 않고 그때까지 인덱싱된 범위에서 부분 결과를 낸다.
- `getStatus()` — 문서 수·청크 수·마지막 인덱싱 시각 등.
- `close()` — **멱등**. 주입받은 협력자(`EmbeddingClient`, 외부 커넥션)는 닫지 않는다 — 빌려온 것은
  닫지 않는다는 규칙 그대로다.

**구현체가 지켜야 할 것** (인터페이스 javadoc 이 계약으로 명시)

1. `search()` 는 동시 호출에 안전하다
2. `score` 는 `[0.0, 1.0]` 으로 정규화한다 (클수록 관련도 높음)
3. `close()` 는 멱등이다

**인터페이스를 쪼개지 않은 이유** — 메서드가 5개로 적고 인덱싱과 검색이 같은 인덱스를 공유한다.
`KnowledgeSearchTool` 은 `search()` 만 쓰지만, ISP 로 얻는 이득보다 일관된 관리 단위로서의 가치가 크다.
인덱싱과 검색의 수명이 갈라지면 그때 `KnowledgeIndexer` / `KnowledgeSearcher` 로 나눈다.

### 3.2 `KnowledgeSource` — VFS 를 저장소가 아니라 호출에 싣는다

```java
public final class KnowledgeSource {
    private final VirtualFileSystem fileSystem;
    private final String directory;
}
```

읽을 대상(파일시스템 + 디렉토리)이 **생성자가 아니라 인덱싱 호출의 인자**다. application-scoped 저장소
하나가 서로 다른 VFS 를 쓰는 여러 agent runtime 을 서빙하려면 VFS 참조를 저장소가 붙들고 있으면 안
되기 때문이다.

### 3.3 `SearchQuery`

| 필드 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `queryText` | String | (필수) | 검색 질의문. null·빈 문자열 불가 |
| `maxResults` | int | `DEFAULT_MAX_RESULTS = 5` | 최대 결과 수. 1 이상 |
| `minScore` | double | `DEFAULT_MIN_SCORE = 0.0` | 최소 관련도. `[0.0, 1.0]` |
| `filePatterns` | List\<String\> | 빈 목록 | 파일 필터 glob (예: `*.md`) |
| `metadata` | Map\<String,String\> | 빈 맵 | 메타데이터 필터 |
| `crossContext` | boolean | `false` | §4 참조 |

### 3.4 `SearchResult`

| 필드 | 타입 | 설명 |
|------|------|------|
| `documentPath` | String | 원본 문서의 VFS 경로. null·빈 문자열 불가 |
| `chunkContent` | String | 매칭된 청크 텍스트. null·빈 문자열 불가 |
| `score` | double | **정규화된** 관련도 `[0.0, 1.0]` |
| `chunkIndex` | int | 문서 내 청크 순서 (0-based). 0 이상 |
| `metadata` | Map\<String,String\> | 문서 제목·섹션명 등 |

### 3.5 `DocumentChunk` 와 `DocumentChunker`

```java
public interface DocumentChunker {
    List<DocumentChunk> chunk(String documentPath, String content);
}
```

`DocumentChunk` 는 `documentPath` + `content` + `chunkIndex` + `metadata`. 빈 문서는 빈 리스트다.

**기본 구현 `SimpleDocumentChunker`** (`chunkSize=500`, `overlap=50`)

1. 마크다운 헤더(`#`, `##`, …) 기준으로 섹션 분할
2. 헤더가 없는 문서(`.txt` 등)는 문자 수 기반 분할로 fallback
3. `chunkSize` 를 초과하는 섹션은 줄 경계로 추가 분할
4. 연속 청크는 `overlap` 문자만큼 겹쳐 문맥 연속성을 유지

`chunkSize` 는 `IndexOptions.maxChunkSize` 를 넘을 수 없다 — 저장소가 인덱싱 시
`min(chunker.chunkSize, options.maxChunkSize)` 를 적용해 과대 청크를 막는다.

### 3.6 `IndexOptions` / `IndexResult` / `IndexStatus`

`IndexOptions` — `filePatterns`(기본 `["*.md", "*.txt"]`), `recursive`(기본 `true`),
`maxDocuments`(기본 1000, 메모리 보호), `maxChunkSize`(기본 2000).
`DocumentChunker` 는 **행위 객체이므로 여기 넣지 않는다** — 생성자로 주입한다 (§9 D7).

`IndexResult` — `indexedDocumentCount`, `indexedChunkCount`, `skippedDocumentCount`, `durationMs`,
`errors`(개별 파일 실패 메시지; null 불가, 빈 리스트 = 전체 성공). 모든 카운트는 0 이상.

`IndexStatus` — `documentCount`, `chunkCount`, `lastIndexedAt`(null 이면 미인덱싱), `indexedDirectory`,
`state ∈ {READY, INDEXING, EMPTY, ERROR}`.

---

## 4. 멀티테넌시 — `KnowledgeScope`

```java
public final class KnowledgeScope {
    private final String agentName;
    private final String contextId;   // agent runtime id
}
```

인덱싱 시점에 모든 문서가 이 두 값으로 태깅되고, 검색 시점에 **필수 필터**로 걸린다. 스코프 없이는
검색이 성립하지 않는다 — 도구는 컨텍스트에 스코프가 없으면
`"No knowledge scope configured for this agent"` 로 거절한다(§8).

| 모드 | `crossContext` | 필터 |
|------|---------------|------|
| 기본 | `false` | `agentName` **AND** `contextId` |
| 교차 컨텍스트 | `true` | `agentName` 만 — 같은 에이전트의 **모든** 컨텍스트 |

교차 컨텍스트 모드는 같은 에이전트의 다른 runtime 이 인덱싱해 둔 지식을 회수하기 위한 것이다. 반대로
`agentName` 은 어느 모드에서도 풀리지 않는다 — 에이전트 경계는 넘을 수 없다.

`contextId` 는 `AgentRuntimeId` 를 담는다. 즉 **agent-scoped 값**이므로 세션이 바뀌어도 유지되고,
세션별로 지식을 가르지는 않는다.

구현체가 필터를 어떻게 표현하는지는 백엔드마다 다르다 — `KeywordKnowledgeStore` 는 스코프 키별로
독립 인덱스를 두고(§5), OpenSearch 는 공유 인덱스에 term 필터를 건다(`ScopeFilter`, §7.4).

---

## 5. 키워드 검색 — `KeywordKnowledgeStore`

외부 의존성 없이 core 에서 바로 쓰는 기본 구현이다. VFS 파일을 읽어 **인메모리 역인덱스**를 만들고
TF-IDF 로 점수를 매긴다.

```
필드
    chunker: DocumentChunker                               // 생성자 주입
    scopeIndices: ConcurrentHashMap<String, IndexSnapshot> // 스코프 키 → 스냅샷

index(scope, source, options)
    files = source.fileSystem 에서 source.directory 하위 조회 (options.recursive)
    options.filePatterns 로 필터, options.maxDocuments 까지
    각 file: content 읽기 → chunker.chunk() → 청크·토큰 카운트 누적, 역인덱스 등록
    scopeIndices.put(scopeKey(scope), 새 IndexSnapshot)     // 원자적 참조 교체
    IndexResult 반환

search(scope, query)
    snapshots = collectSnapshots(scope, query.isCrossContext())
    queryText 토큰화 → 역인덱스 조회 → TF-IDF 스코어 합산
    최대값 기준 [0.0, 1.0] 정규화 → minScore 필터 → 정렬 → maxResults
```

**스코프별 독립 인덱스.** `scopeIndices` 의 키가 스코프이므로 한 스코프의 인덱싱이 다른 스코프의
검색을 방해하지 않는다. `crossContext=true` 면 `agentName` 이 같은 스냅샷들을 모아 훑는다.

**동시성.** `search()` 는 스냅샷 참조를 한 번 읽고 그 불변 스냅샷 위에서만 동작한다(CopyOnWrite).
인덱싱은 새 스냅샷을 만들어 맵 항목을 통째로 교체하므로, 검색 중인 스레드는 자기가 읽은 스냅샷의
일관된 상태를 계속 본다. **같은 스코프에 대한 동시 인덱싱은 보호되지 않는다** — 마지막 교체가 이긴다.

**토큰화**

- 공백·구두점·특수문자 기준 분리 (`[\s\p{Punct}]+`)
- 소문자 정규화 (대소문자 무관)
- 1자 이하 토큰 제거
- 인덱싱 시와 검색 시 **같은 로직**을 쓴다
- **한국어 한계** — 공백 기반 분리만 하므로 "배포실패" 같은 복합어에 약하다. 형태소 분석 토크나이저는
  미착수 항목이다(§12)

---

## 6. 임베딩 추상화

```java
package at.aimon.core.knowledge.embedding;

public interface EmbeddingClient {
    EmbeddingResult embed(String text);
    List<EmbeddingResult> embedBatch(List<String> texts);   // 순서 보장
    int getDimensions();
}

public final class EmbeddingResult {   // builder
    private final float[] vector;
    private final int tokenCount;
}
```

`EmbeddingException` 이 같은 패키지에 있다.

반환 타입이 raw `float[]` 이 아니라 `EmbeddingResult` 인 것은 **토큰 사용량을 함께 실어 보내기 위해서**다
— 임베딩도 과금 대상이므로 호출자가 비용을 셀 수 있어야 한다.

**첫 구현체 `OpenAIEmbeddingClient`** (`aimon-llm-openai`). 기존 모듈에 얹었다 — OpenAI Java SDK 에
Embeddings API 가 이미 있어 추가 의존성이 없고, `OpenAIEmbeddingConfig` 가 API key·endpoint 를
`OpenAILlmClient` 와 같은 방식으로 다룬다. 별도 `aimon-embedding-openai` 모듈을 만들 이유가 없었다.

---

## 7. OpenSearch 백엔드

`aimon-knowledge-opensearch` — 패키지 `at.aimon.core.knowledges.opensearch`.

### 7.1 구성

| 타입 | 역할 |
|------|------|
| `OpenSearchKnowledgeStore` | `KnowledgeStore` 구현. 인덱싱(bulk) + 모드별 검색 위임 |
| `OpenSearchConfig` | 연결·인덱스·모드·가중치 설정 (불변, builder) |
| `SearchMode` | `KEYWORD`(BM25) / `VECTOR`(kNN) / `HYBRID` |
| `OpenSearchClientFactory` | 클라이언트 생성 (인증 포함) |
| `OpenSearchIndexManager` | 인덱스 생성·매핑 적용 |
| `OpenSearchDocumentMapper` | 문서 ↔ 필드 매핑, 필드명 상수 소유 |
| `ScopeFilter` | `KnowledgeScope` → term 필터 쿼리 |
| `OpenSearchSearchStrategy` | 검색 전략 인터페이스 — Keyword/Vector/Hybrid 3구현 |

### 7.2 설정

```java
public final class OpenSearchConfig {   // builder
    host; port = 9200; scheme = "https";
    indexName = "aimon-knowledge";
    username; password;                       // optional
    searchMode = SearchMode.KEYWORD;
    vectorDimensions = 1536;
    keywordWeight = 0.3f; vectorWeight = 0.7f;   // HYBRID 전용
}
```

`KEYWORD` 모드는 `EmbeddingClient` 가 필요 없고, `VECTOR`/`HYBRID` 는 필수다. 기본값이 `KEYWORD` 인
것은 임베딩 자격증명 없이도 이 백엔드를 켤 수 있어야 하기 때문이다.

### 7.3 인덱스 매핑

```json
{
  "mappings": {
    "properties": {
      "document_path":  { "type": "keyword" },
      "chunk_content":  { "type": "text", "analyzer": "standard" },
      "chunk_index":    { "type": "integer" },
      "embedding":      { "type": "knn_vector", "dimension": 1536 },
      "metadata":       { "type": "object", "dynamic": true },
      "indexed_at":     { "type": "date" },
      "agent_name":     { "type": "keyword" },
      "context_id":     { "type": "keyword" }
    }
  },
  "settings": { "index.knn": true }
}
```

`agent_name` / `context_id` 두 필드가 §4 의 스코프를 물리적으로 실현한다. 둘 다 `keyword` 인 것은
분석 없이 정확 일치 term 필터로만 쓰이기 때문이다.

### 7.4 검색 전략

`ScopeFilter.toFilterQueries(crossContext)` 가 **모든 모드의 쿼리에 붙는 필수 필터**를 만든다 —
`crossContext=false` 면 `agent_name` + `context_id` 두 개, `true` 면 `agent_name` 하나.

| 전략 | 방식 |
|------|------|
| `KeywordSearchStrategy` | `chunk_content` 에 대한 BM25 match |
| `VectorSearchStrategy` | 질의문을 `EmbeddingClient` 로 임베딩 → `embedding` 필드 kNN |
| `HybridSearchStrategy` | 두 결과를 **가중 합산 후 정규화** |

**하이브리드 결합** — RRF 가 아니라 가중 선형 결합이다.

1. 키워드·벡터 결과를 `(documentPath, chunkIndex)` 키로 합류시킨다. 한쪽에만 있는 항목은 없는 쪽 점수를
   0 으로 둔다
2. `combined = keywordScore × keywordWeight + vectorScore × vectorWeight`
3. 배치 내 `maxCombined` 로 나눠 다시 `[0.0, 1.0]` 으로 정규화한다. `maxCombined == 0.0` 이면 빈 결과
4. `minScore` 필터 → 정렬 → `maxResults`

두 입력이 이미 `[0.0, 1.0]` 계약을 지키기 때문에(§9 D8) 이 결합이 성립한다. 그럼에도 결합 뒤 다시
정규화하는 것은, 가중합의 최대값이 1.0 에 못 미칠 수 있어 `minScore` 의 의미가 모드마다 달라지는 것을
막기 위해서다.

---

## 8. 도구와 배선

### 8.1 `KnowledgeSearchTool`

```
이름:     "KnowledgeSearch"     (ToolCategories.SEARCH)
설명:     Search agent's knowledge base for relevant documents.
          Returns document chunks ranked by relevance.
          Use this tool to find information from the agent's configured knowledge directory.

입력 스키마 (additionalProperties: false)
    query        (string, 필수)  검색 질의문
    max_results  (number)        최대 결과 수 (기본 5)
    file_pattern (string)        파일 필터 glob (예: '*.md')
```

실행 순서는 **거절 → 파라미터 → 검색 → 포맷**이다.

```
KNOWLEDGE_STORE 없음 → ToolResult.error("No knowledge store configured for this agent")
KNOWLEDGE_SCOPE 없음 → ToolResult.error("No knowledge scope configured for this agent")
결과 없음            → ToolResult.success("No relevant documents found for: " + queryText)
IllegalArgument      → ToolResult.error("Invalid parameter: ...")
검색 실패            → ToolResult.error("Failed to search knowledge: ...")
```

결과 없음이 `error` 가 아니라 `success` 인 것이 중요하다 — 지식에 없다는 것은 도구의 실패가 아니라
유효한 관찰이고, 모델은 그 답을 받고 다른 경로(Grep, WebSearch)로 넘어가야 한다.

결과 포맷은 **출처를 먼저** 적는다. 문서 내용에 섞인 지시문을 모델이 사용자 입력과 혼동하지 않도록
하는 방어이기도 하다(§11).

```
Found 3 results for "CrashLoopBackOff":

---
[1] runbook.md (score: 0.92, chunk: 3)
<청크 내용>

---
[2] troubleshooting.md (score: 0.78, chunk: 1)
...
```

### 8.2 등록과 주입

`OrcaKnowledgeToolProvider` 가 도구를 등록한다. 레지스트리가 `ToolSearchCatalog` 면
`SearchableTool.eager(tool)` 로 — 즉 **항상 모델에게 보이는 도구**로 등록한다. 지식 디렉토리가 설정된
에이전트에게 지식 검색은 부수 기능이 아니라 핵심 기능이므로, 매 대화에서 도구를 발견하는 왕복을 물릴
이유가 없다. 지식이 설정되지 않았으면 도구를 아예 등록하지 않는다 — deferred 대상도 아니다.

주입은 `ToolContext` 의 타입 키 두 개다.

```java
ToolContextKeys.KNOWLEDGE_STORE   // ToolContextKey<KnowledgeStore>, 와이어 키 "knowledgeStore"
ToolContextKeys.KNOWLEDGE_SCOPE   // ToolContextKey<KnowledgeScope>, 와이어 키 "knowledgeScope"
```

둘은 **함께 실린다.** 저장소가 application-scoped 이므로(§2.2) 저장소만으로는 "누구의 지식인가"가
정해지지 않고, 스코프 없는 검색은 격리가 깨진 검색이기 때문이다.

지식 디렉토리 경로는 `OrcaAgentRuntime` 을 빌드하는 **어셈블리 쪽**(CLI, 서버 초기화)이 정한다.
`Agent` / `AgentMetadata` 는 건드리지 않는다.

---

## 9. 설계 결정

| # | 결정 | 근거 | 기각안 |
|---|------|------|--------|
| **D1** | `KnowledgeStore` 인터페이스와 **키워드 구현까지** core 에 둔다 | 외부 의존성 없이 즉시 사용 가능. DIP 준수 | 전부 별도 `aimon-knowledge` 모듈로(기본 검색에도 모듈 의존 필요) / 도구 레벨에서만 처리(백엔드 교체 불가, OCP 위반) |
| **D2** | 문서 원본은 VFS 가 소유하고 저장소는 **인덱스만** 관리 | 저장 계층을 두 벌 만들지 않는다. VFS 의 `listRecursive`/`read`/경로 검증을 그대로 재사용 | 저장소가 원본까지 보관(중복 저장, 경로 검증 재구현) |
| **D3** | `EmbeddingClient` 를 **core** 에 배치 | `LlmClient` 와 같은 수준의 AI 프로바이더 추상화다. knowledge 모듈에 두면 `aimon-llm-openai` 가 knowledge 모듈에 의존하는 역방향이 생긴다 | knowledge 모듈 배치(의존 역전) |
| **D4** | 벡터 저장/검색을 **별도 SPI 로 쪼개지 않는다** | 백엔드가 하나뿐인 시점에 중간 SPI 를 두는 비용이 이득보다 컸다. `OpenSearchKnowledgeStore` 가 `KnowledgeStore` 를 직접 구현하고 `SearchMode` 로 BM25·kNN·하이브리드를 고른다 | `EmbeddingClient` + `VectorStore` 2단 분리 — **채택됐다가 뒤집혔다**(§10) |
| **D5** | 인덱스 저장은 키워드=인메모리, 벡터=외부 DB 위임 | 에이전트별 지식 규모(수십~수백 문서)에서 인메모리 역인덱스면 충분하고, 벡터는 전문 DB 에 맡기는 것이 효율적 | 파일 기반 인덱스(Lucene 등 — 외부 의존성, 멀티 인스턴스 어려움) / 모든 단계 외부 DB(초기부터 과도한 인프라) |
| **D6** | `DocumentChunker` 전략 패턴, 기본은 마크다운 섹션 분할 | 문서 유형마다 최적 청크 전략이 다르다 (마크다운 vs YAML vs 로그) | 고정 라인 수 분할(문맥 경계 무시) / 저장소 내부에 청크 로직 포함(SRP 위반) |
| **D7** | `DocumentChunker` 는 **생성자 주입**, `IndexOptions` 에 넣지 않는다 | `IndexOptions` 는 직렬화 가능한 순수 값 객체여야 한다. 행위 객체를 섞으면 불변·직렬화 의미론이 흐려진다 | `IndexOptions` 포함(값 객체에 행위 객체 혼입) |
| **D8** | 모든 구현체가 `score` 를 `[0.0, 1.0]` 으로 **정규화**한다 | 원시 점수 범위가 제각각이다(TF-IDF `0~∞`, 코사인 `-1~1`, BM25 무상한). 하이브리드 결합과 `minScore` 의 의미를 백엔드 무관하게 만들려면 계약으로 강제해야 한다 | 원시 점수 그대로 반환(모드마다 `minScore` 의미가 달라짐) |
| **D9** | 하이브리드는 **가중 선형 결합 후 재정규화** (RRF 아님) | 입력이 이미 정규화 계약을 지키므로 순위 기반 융합(RRF)까지 갈 필요가 없고, 가중치로 키워드/벡터 비중을 설정에서 조절할 수 있다(기본 0.3/0.7) | RRF(가중치 조절 표면이 사라짐) |
| **D10** | 저장소를 `ToolContext` 키로 주입한다 | `VirtualFileSystem`·`Environment` 와 동일한 기존 패턴. 도구는 무상태로 남는다 | 도구 생성자 주입(에이전트마다 도구 인스턴스 필요, `OrcaToolProvider` 패턴과 불일치) / 전역 서비스 레지스트리(전역 상태, 테스트 어려움) |
| **D11** | 인덱스를 스코프별로 쪼개는 대신 **문서를 스코프로 태깅**한다 | OpenSearch 에서 테넌트마다 인덱스를 만들면 인덱스 수가 에이전트 수만큼 늘고 매핑·샤드 관리가 그만큼 늘어난다. term 필터 두 개면 같은 격리를 얻는다 | 스코프별 물리 인덱스(운영 비용) |

---

## 10. 뒤집힌 결정 — 벡터 계층의 2단 분리

초기 설계는 벡터 계층을 **`EmbeddingClient` + `VectorStore` 두 SPI 로 분리**하고, 그 둘을 조합하는
`VectorKnowledgeStore` 를 core 에 두며, 백엔드마다 모듈(`aimon-knowledge-vector`,
`aimon-knowledge-qdrant`, `aimon-knowledge-pgvector`)을 갖는 그림이었다. 근거는 "임베딩 모델과 벡터 DB 는
독립적으로 교체할 수 있어야 한다" 였다.

**착수 전에 뒤집혔다.** 실제로 구현된 것은 아래 오른쪽이다.

| 초기 안 | 실제 |
|---------|------|
| `EmbeddingClient` @ `at.aimon.core.embedding`, `embed() → float[]`, `getDimension()` | `EmbeddingClient` @ **`at.aimon.core.knowledge.embedding`**, `embed() → EmbeddingResult`, `getDimensions()` |
| `VectorStore` SPI (`upsert`/`search`/`delete`/`initialize`) + `VectorEntry` · `VectorSearchResult` 값 객체 | **도입되지 않음** |
| `VectorKnowledgeStore` (임베딩 + `VectorStore` 조합) | **도입되지 않음** |
| `OpenSearchVectorStore` @ `at.aimon.knowledge.opensearch` | **`OpenSearchKnowledgeStore`** @ `at.aimon.core.knowledges.opensearch` — `KnowledgeStore` 를 직접 구현하고 `SearchMode` 로 분기 |
| `aimon-knowledge-vector` + 백엔드별 모듈 N개 | `aimon-knowledge-opensearch` 하나 |

임베딩 추상화(`EmbeddingClient`)만 남고 벡터 저장소는 별도 SPI 가 아니라 `KnowledgeStore` 구현체
**안으로** 들어갔다. 백엔드가 하나뿐인 시점에 중간 SPI 를 두는 비용이 이득보다 컸고, 무엇보다
`VectorStore` 를 경유하면 하이브리드 검색(BM25 + kNN 을 **한 엔진 안에서** 섞는 것)이 표현되지 않는다 —
그 조합은 OpenSearch 가 이미 잘하는 일이지 우리가 두 SPI 사이에서 조립할 일이 아니었다.

두 번째로 뒤집힌 것은 **저장소의 수명**이다. 초기 설계는 저장소를 agent-scoped 로 보고
`AgentRuntime.close()` 가 함께 닫는 그림이었다. 실제로는 `ApplicationScoped` 이고, 대신 스코프를
호출마다 넘긴다(§2.2, §4). 커넥션을 쥐는 백엔드를 agent 수만큼 복제할 이유가 없었기 때문이다.

세 번째는 **패키지 이동**이다. `KnowledgeSearchTool` 은 `at.aimon.core.ext.tools.knowledge` 에서
`at.aimon.core.tools.knowledge` 로 옮겨졌다 (`ext.tools.*` → `tools.*` 전면 이동의 일부).

---

## 11. 보안과 멀티 인스턴스

| 위협 | 대응 |
|------|------|
| 경로 조작으로 지식 디렉토리 밖 파일 접근 | VFS 의 기존 경로 검증(`PathValidator`)을 그대로 탄다. 저장소는 `KnowledgeSource.directory` 하위만 스캔한다 |
| 대량 문서 인덱싱으로 메모리 고갈 | `IndexOptions.maxDocuments`(1000) · `maxChunkSize`(2000) 상한 |
| 프롬프트 인젝션 (문서에 심어진 지시문) | 검색 결과에 **출처 경로를 먼저** 명시해 모델이 검색 결과와 사용자 입력을 구분할 수 있게 한다 |
| 테넌트 간 데이터 노출 | `KnowledgeScope` 가 검색의 **필수** 필터다. 스코프가 없으면 검색을 거절한다(§8.1). `crossContext` 로도 `agentName` 경계는 풀리지 않는다 |
| 벡터 DB 접근 권한 | `OpenSearchConfig` 의 `username`/`password` 로 백엔드 인증 |

| 환경 | 동작 |
|------|------|
| 단일 인스턴스 | `KeywordKnowledgeStore` 인메모리 역인덱스로 프로세스 내 완결 |
| 멀티 인스턴스 (키워드) | 인스턴스마다 독립적으로 인덱스를 구성한다 — 인덱스 공유 없음. VFS 가 공유 백엔드(GridFS/S3)면 같은 문서를 각자 인덱싱한다 |
| 멀티 인스턴스 (OpenSearch) | 인덱스가 외부 클러스터에 있으므로 자동 공유. 재시작해도 재인덱싱이 필요 없다 |

---

## 12. 남은 작업

| 항목 | 설명 |
|------|------|
| `KnowledgeManageTool` | 모델이 재인덱싱·상태 조회를 할 수 있는 관리 도구 |
| 증분 인덱싱 | 파일 해시 비교로 변경분만 처리 — `index()` 만 확장하고 `reindex()` 는 전체 유지 |
| 비동기 인덱싱 | 대량 문서 벡터 인덱싱 시 runtime 생성이 블로킹되지 않도록 |
| 다국어 토크나이저 | 한국어·일본어 형태소 분석 기반 토큰화 (§5 의 복합어 한계) |
| 추가 백엔드 | Qdrant, pgvector 등. `KnowledgeStore` 를 직접 구현하는 방식으로 (D4) |
| `KnowledgeAugmenter` (autoRag) | 도구 호출 없이 시스템 프롬프트를 자동 증강하는 경로. 현재 RAG 는 **도구 기반 단일 경로**다 |
| `KnowledgeIndexer` / `KnowledgeSearcher` 분리 | 인덱싱과 검색의 수명이 갈라질 때 (§3.1) |

**의도적으로 범위 밖에 둔 것**

- **문서 편집/작성** — `KnowledgeStore` 는 읽기 전용 검색만 담당. 문서 수정은 `Write`/`Edit` 도구
- **실시간 파일 감시** — 복잡도 대비 가치가 낮다. 명시적 `reindex()` 로 충분
- **분산 인메모리 인덱스 동기화** — OpenSearch 백엔드를 쓰면 자연히 해소되는 문제
- **문서별 ACL** — 격리 단위는 `KnowledgeScope` 다. 문서 단위 권한은 다루지 않는다
- **검색 결과 캐싱** — 매 요청 검색. 성능 측정 후 필요해지면 그때

---

## 관련 문서

- [OpenSearch KnowledgeStore 사용 가이드](../../features/knowledge/opensearch-knowledge-store-guide.md)
- [Tool 개발 가이드](../../features/tool/tool-development-guide.md)
- [Tool Search 설계](../tool/tool-search.md) — eager/deferred 등록 패턴
- [스코프 모델](../../overview/scope-model.md) — `ApplicationScoped` 마커와 소멸 책임
- [SOLID 원칙](../../project/solid-principles.md)
