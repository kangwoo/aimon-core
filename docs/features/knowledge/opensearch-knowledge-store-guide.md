# OpenSearch Knowledge Store Guide

> OpenSearch를 백엔드로 사용하는 RAG(Retrieval-Augmented Generation) Knowledge Store 설정 및 사용 가이드

## 목차

1. [개요](#개요)
2. [의존성 추가](#의존성-추가)
3. [검색 모드](#검색-모드)
4. [설정](#설정)
5. [사용 방법](#사용-방법)
6. [Agent 통합](#agent-통합)
7. [인덱스 관리](#인덱스-관리)
8. [설정 레퍼런스](#설정-레퍼런스)
9. [트러블슈팅](#트러블슈팅)

---

## 개요

`OpenSearchKnowledgeStore`는 `KnowledgeStore` 인터페이스의 OpenSearch 구현체입니다. 문서를 청킹하여 OpenSearch에 인덱싱하고, BM25 키워드 검색, kNN 벡터 검색, 또는 두 방식을 결합한 하이브리드 검색을 제공합니다.

### 아키텍처

```
┌─────────────────────────────────────────────────────────────┐
│                      aimon-core                             │
│  KnowledgeStore (interface)  │  EmbeddingClient (interface) │
└──────────────┬───────────────┴──────────────┬───────────────┘
               │                              │
┌──────────────┴──────────────┐  ┌────────────┴───────────────┐
│  aimon-knowledge-opensearch │  │     aimon-llm-openai       │
│  OpenSearchKnowledgeStore   │  │  OpenAIEmbeddingClient     │
└─────────────────────────────┘  └────────────────────────────┘
```

### 핵심 클래스

| 클래스 | 역할 |
|--------|------|
| `OpenSearchConfig` | OpenSearch 연결 및 검색 모드 설정 |
| `OpenSearchClientFactory` | Config에서 OpenSearchClient 생성 |
| `OpenSearchKnowledgeStore` | KnowledgeStore 구현체 (인덱싱 + 검색) |
| `OpenAIEmbeddingConfig` | OpenAI Embedding API 설정 |
| `OpenAIEmbeddingClient` | EmbeddingClient 구현체 |

---

## 의존성 추가

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation(project(":aimon-core"))
    implementation(project(":aimon-knowledge-opensearch"))

    // 벡터/하이브리드 검색 시 필요
    implementation(project(":aimon-llm-openai"))
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation project(':aimon-core')
    implementation project(':aimon-knowledge-opensearch')

    // 벡터/하이브리드 검색 시 필요
    implementation project(':aimon-llm-openai')
}
```

---

## 검색 모드

| 모드 | 설명 | EmbeddingClient 필요 | 적합한 경우 |
|------|------|:-------------------:|-------------|
| `KEYWORD` | BM25 텍스트 검색 | 아니오 | 구조화된 문서, 키워드 기반 매칭 |
| `VECTOR` | kNN 벡터 유사도 검색 | 예 | 의미 기반 검색, 자연어 질의 |
| `HYBRID` | BM25 + kNN 가중 결합 | 예 | 범용 (권장) |

---

## 설정

### 1. KEYWORD 모드 (가장 단순)

Embedding 없이 BM25 텍스트 검색만 사용합니다.

```java
// 1) OpenSearch 설정
OpenSearchConfig config = OpenSearchConfig.builder()
        .host("localhost")
        .port(9200)
        .scheme("https")
        .indexName("my-knowledge")
        .username("admin")
        .password("admin")
        .searchMode(SearchMode.KEYWORD)
        .build();

// 2) OpenSearch 클라이언트 생성
OpenSearchClient client = OpenSearchClientFactory.create(config);

// 3) Knowledge Store 생성
KnowledgeStore store = new OpenSearchKnowledgeStore(
        client,
        config,
        new SimpleDocumentChunker(),  // 기본 마크다운 청커
        fileSystem                     // VirtualFileSystem 인스턴스
);
```

### 2. HYBRID 모드 (권장)

BM25와 벡터 검색을 결합합니다. OpenAI Embedding API가 필요합니다.

```java
// 1) OpenSearch 설정 (HYBRID)
OpenSearchConfig config = OpenSearchConfig.builder()
        .host("localhost")
        .port(9200)
        .scheme("https")
        .indexName("my-knowledge")
        .username("admin")
        .password("admin")
        .searchMode(SearchMode.HYBRID)
        .vectorDimensions(1536)    // text-embedding-3-small
        .keywordWeight(0.3f)       // BM25 가중치
        .vectorWeight(0.7f)        // 벡터 가중치
        .build();

// 2) Embedding 클라이언트 설정
OpenAIEmbeddingConfig embeddingConfig = OpenAIEmbeddingConfig.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("text-embedding-3-small")
        .dimensions(1536)
        .build();

EmbeddingClient embeddingClient = new OpenAIEmbeddingClient(embeddingConfig);

// 3) OpenSearch 클라이언트 생성
OpenSearchClient client = OpenSearchClientFactory.create(config);

// 4) Knowledge Store 생성
KnowledgeStore store = new OpenSearchKnowledgeStore(
        client,
        config,
        new SimpleDocumentChunker(),
        embeddingClient,
        fileSystem
);
```

### 3. VECTOR 모드

벡터 검색만 사용합니다. 설정은 HYBRID와 동일하되 `searchMode(SearchMode.VECTOR)`로 변경합니다.

```java
OpenSearchConfig config = OpenSearchConfig.builder()
        .host("localhost")
        .searchMode(SearchMode.VECTOR)
        .vectorDimensions(1536)
        // keywordWeight/vectorWeight 설정 불필요
        .build();
```

---

## 사용 방법

### 문서 인덱싱

```java
// 기본 옵션 (*.md, *.txt, 재귀, 최대 1000개)
IndexResult result = store.index("/knowledge", IndexOptions.defaults());

System.out.println("인덱싱 완료: " + result.getIndexedDocumentCount() + " 문서, "
        + result.getIndexedChunkCount() + " 청크 (" + result.getDurationMs() + "ms)");

if (!result.getErrors().isEmpty()) {
    System.err.println("에러: " + result.getErrors());
}
```

### 인덱싱 옵션 커스터마이징

```java
IndexOptions options = IndexOptions.builder()
        .filePatterns(List.of("*.md", "*.txt", "*.yaml"))
        .recursive(true)
        .maxDocuments(500)
        .maxChunkSize(1000)
        .build();

IndexResult result = store.index("/knowledge", options);
```

### 검색

```java
// 기본 검색
SearchQuery query = SearchQuery.builder()
        .queryText("CrashLoopBackOff troubleshooting")
        .maxResults(5)
        .build();

List<SearchResult> results = store.search(query);

for (SearchResult result : results) {
    System.out.printf("[%.2f] %s (chunk %d)%n",
            result.getScore(),
            result.getDocumentPath(),
            result.getChunkIndex());
    System.out.println(result.getChunkContent());
    System.out.println("---");
}
```

### 검색 옵션

```java
// 파일 패턴 필터 + 최소 점수 임계값
SearchQuery query = SearchQuery.builder()
        .queryText("deployment rollback procedure")
        .maxResults(10)
        .minScore(0.3)
        .filePatterns(List.of("*.md"))
        .build();

List<SearchResult> results = store.search(query);
```

### 재인덱싱

기존 인덱스를 삭제하고 처음부터 다시 인덱싱합니다.

```java
IndexResult result = store.reindex("/knowledge", IndexOptions.defaults());
```

### 인덱스 상태 확인

```java
IndexStatus status = store.getStatus();
System.out.println("상태: " + status.getState());        // READY, INDEXING, EMPTY, ERROR
System.out.println("문서 수: " + status.getDocumentCount());
System.out.println("청크 수: " + status.getChunkCount());
System.out.println("마지막 인덱싱: " + status.getLastIndexedAt());
```

---

## Agent 통합

`OrcaAgentRuntime`에 `KnowledgeStore`를 주입하면, Agent가 `KnowledgeSearchTool`을 통해 자동으로 검색할 수 있습니다.

### ExecutionContext에 KnowledgeStore 주입

```java
OrcaAgentRuntime context = OrcaAgentRuntime.builder()
        .agent(agent)
        .toolRegistry(toolRegistry)
        .hookRegistry(hookRegistry)
        .commandRegistry(commandRegistry)
        .subagentRegistry(subagentRegistry)
        .skillRegistry(skillRegistry)
        .fileSystem(fileSystem)
        .environment(environment)
        .knowledgeStore(store)       // KnowledgeStore 주입
        .build();
```

### Agent의 RAG 동작 흐름

```
사용자 질문
  → LLM이 KnowledgeSearchTool 호출 판단
  → KnowledgeSearchTool.execute(query)
  → OpenSearchKnowledgeStore.search(query)
  → 검색 결과를 ToolResult로 반환
  → LLM이 검색 결과를 참고하여 답변 생성
```

Agent는 `KnowledgeSearchTool`을 통해 다음과 같이 검색합니다:

```
KnowledgeSearch(query: "CrashLoopBackOff troubleshooting", max_results: 5)
```

---

## 인덱스 관리

### OpenSearch 인덱스 매핑

자동 생성되는 인덱스 매핑 구조:

| 필드 | 타입 | 설명 |
|------|------|------|
| `document_path` | `keyword` | VFS 파일 경로 |
| `chunk_content` | `text` | 청크 텍스트 (BM25 검색 대상) |
| `chunk_index` | `integer` | 문서 내 청크 순번 |
| `embedding` | `knn_vector` | 벡터 (VECTOR/HYBRID 모드만) |
| `metadata` | `object` | 추가 메타데이터 |
| `indexed_at` | `date` | 인덱싱 시각 |

### 내부 제한값

| 항목 | 기본값 | 설명 |
|------|--------|------|
| `BULK_BATCH_SIZE` | 100 | OpenSearch bulk 요청당 최대 청크 수 |
| `EMBEDDING_BATCH_SIZE` | 50 | Embedding API 호출당 최대 텍스트 수 |
| `MAX_FILE_SIZE_BYTES` | 10 MB | 이 크기를 초과하는 파일은 인덱싱에서 제외 |

### 리소스 정리

```java
// KnowledgeStore 정리 (OpenSearchClient는 닫지 않음)
store.close();

// OpenSearchClient 정리 (별도)
client._transport().close();
```

`OrcaAgentRuntime`에 주입한 경우, context가 close될 때 `KnowledgeStore.close()`가 자동 호출됩니다. 단, `OpenSearchClient`의 transport는 별도로 관리해야 합니다.

---

## 설정 레퍼런스

### OpenSearchConfig

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `host` | `String` | (필수) | OpenSearch 호스트 |
| `port` | `int` | `9200` | 포트 |
| `scheme` | `String` | `"https"` | `"http"` 또는 `"https"` |
| `indexName` | `String` | `"aimon-knowledge"` | 인덱스 이름 |
| `username` | `String` | `null` | 인증 사용자명 |
| `password` | `String` | `null` | 인증 비밀번호 |
| `searchMode` | `SearchMode` | `KEYWORD` | 검색 모드 |
| `vectorDimensions` | `int` | `1536` | 벡터 차원 수 |
| `keywordWeight` | `float` | `0.3` | HYBRID 모드 BM25 가중치 |
| `vectorWeight` | `float` | `0.7` | HYBRID 모드 벡터 가중치 |

> `keywordWeight + vectorWeight`는 반드시 `1.0`이어야 합니다.

### OpenAIEmbeddingConfig

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `apiKey` | `String` | (필수) | OpenAI API 키 |
| `baseUrl` | `String` | `null` | 커스텀 API URL (OpenAI 호환 서버) |
| `model` | `String` | `"text-embedding-3-small"` | 임베딩 모델 |
| `dimensions` | `int` | `1536` | 출력 벡터 차원 수 |
| `timeout` | `Duration` | `30s` | 요청 타임아웃 |

### IndexOptions

| 속성 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `filePatterns` | `List<String>` | `["*.md", "*.txt"]` | 인덱싱할 파일 glob 패턴 |
| `recursive` | `boolean` | `true` | 하위 디렉토리 포함 여부 |
| `maxDocuments` | `int` | `1000` | 최대 인덱싱 문서 수 |
| `maxChunkSize` | `int` | `2000` | 청크 최대 문자 수 |

---

## 트러블슈팅

### "EmbeddingClient is required for search mode VECTOR"

VECTOR 또는 HYBRID 모드에서 `EmbeddingClient`를 전달하지 않았습니다.

```java
// 잘못된 예: HYBRID인데 EmbeddingClient 없음
new OpenSearchKnowledgeStore(client, config, chunker, fileSystem);

// 올바른 예: EmbeddingClient 전달
new OpenSearchKnowledgeStore(client, config, chunker, embeddingClient, fileSystem);
```

### 검색 결과가 빈 리스트

1. `store.getStatus().getState()`가 `READY`인지 확인
2. `index()` 호출 후 `IndexResult.getErrors()`가 비어있는지 확인
3. OpenSearch 클러스터가 접속 가능한지 확인
4. 인덱스명이 일치하는지 확인

### 인덱싱 시 파일이 건너뛰어짐

- 파일 크기 > 10MB → 자동으로 건너뜀 (로그에 경고 출력)
- `filePatterns`에 매칭되지 않는 확장자
- 빈 파일 또는 청크가 생성되지 않는 내용
- `maxDocuments` 한도 도달

### OpenSearch 연결 실패

```java
// HTTP로 변경
OpenSearchConfig config = OpenSearchConfig.builder()
        .host("localhost")
        .scheme("http")    // HTTPS 대신 HTTP
        .build();
```

### vectorDimensions 불일치

`OpenSearchConfig.vectorDimensions`와 `OpenAIEmbeddingConfig.dimensions`가 동일해야 합니다. 불일치 시 인덱싱/검색이 실패합니다.

```java
// 두 설정의 dimensions 일치 필수
int dimensions = 1536;

OpenSearchConfig osConfig = OpenSearchConfig.builder()
        .vectorDimensions(dimensions)
        // ...
        .build();

OpenAIEmbeddingConfig embConfig = OpenAIEmbeddingConfig.builder()
        .dimensions(dimensions)
        // ...
        .build();
```

---

## 관련 문서

- [OpenSearch RAG 설계](../../design/knowledge/knowledge-and-rag.md) — 이 스토어의 설계 근거
- [Knowledge Search 설계](../../design/knowledge/knowledge-and-rag.md) — `KnowledgeStore` 인터페이스와 키워드 검색(Phase 1)의 설계 근거. 그 문서의 Phase 2 벡터 계층 안은 위 문서로 대체되었다
- [Tool 개발 가이드](../tool/tool-development-guide.md)
- [KnowledgeStore 인터페이스](../../../modules/aimon-core/src/main/java/at/aimon/core/knowledge/KnowledgeStore.java)
- [KnowledgeSearchTool](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/knowledge/KnowledgeSearchTool.java)
