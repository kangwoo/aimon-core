---
translated_from: docs/features/knowledge/opensearch-knowledge-store-guide.md
source_commit: a9821d44
---

# OpenSearch Knowledge Store Guide

> A guide to configuring and using the RAG (Retrieval-Augmented Generation) Knowledge Store backed by OpenSearch

## Table of contents

1. [Overview](#overview)
2. [Adding the dependency](#adding-the-dependency)
3. [Search modes](#search-modes)
4. [Configuration](#configuration)
5. [Usage](#usage)
6. [Agent integration](#agent-integration)
7. [Managing the index](#managing-the-index)
8. [Configuration reference](#configuration-reference)
9. [Troubleshooting](#troubleshooting)

---

## Overview

`OpenSearchKnowledgeStore` is the OpenSearch implementation of the `KnowledgeStore` interface. It chunks documents, indexes them into OpenSearch, and offers BM25 keyword search, kNN vector search, or a hybrid that combines the two.

### Architecture

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

### The core classes

| Class | Role |
|--------|------|
| `OpenSearchConfig` | The OpenSearch connection and search-mode configuration |
| `OpenSearchClientFactory` | Builds an OpenSearchClient from a config |
| `OpenSearchKnowledgeStore` | The KnowledgeStore implementation (indexing + search) |
| `OpenAIEmbeddingConfig` | The OpenAI Embedding API configuration |
| `OpenAIEmbeddingClient` | The EmbeddingClient implementation |

---

## Adding the dependency

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation(project(":aimon-core"))
    implementation(project(":aimon-knowledge-opensearch"))

    // needed for vector/hybrid search
    implementation(project(":aimon-llm-openai"))
}
```

### Gradle (Groovy DSL)

```groovy
dependencies {
    implementation project(':aimon-core')
    implementation project(':aimon-knowledge-opensearch')

    // needed for vector/hybrid search
    implementation project(':aimon-llm-openai')
}
```

---

## Search modes

| Mode | Description | Needs an EmbeddingClient | When it fits |
|------|------|:-------------------:|-------------|
| `KEYWORD` | BM25 text search | no | Structured documents, keyword-based matching |
| `VECTOR` | kNN vector-similarity search | yes | Meaning-based search, natural-language queries |
| `HYBRID` | A weighted combination of BM25 and kNN | yes | General purpose (recommended) |

---

## Configuration

### 1. KEYWORD mode (the simplest)

BM25 text search only, with no embeddings.

```java
// 1) the OpenSearch configuration
OpenSearchConfig config = OpenSearchConfig.builder()
        .host("localhost")
        .port(9200)
        .scheme("https")
        .indexName("my-knowledge")
        .username("admin")
        .password("admin")
        .searchMode(SearchMode.KEYWORD)
        .build();

// 2) build the OpenSearch client
OpenSearchClient client = OpenSearchClientFactory.create(config);

// 3) build the knowledge store
KnowledgeStore store = new OpenSearchKnowledgeStore(
        client,
        config,
        new SimpleDocumentChunker(),  // the default markdown chunker
        fileSystem                     // a VirtualFileSystem instance
);
```

### 2. HYBRID mode (recommended)

Combines BM25 with vector search. It needs the OpenAI Embedding API.

```java
// 1) the OpenSearch configuration (HYBRID)
OpenSearchConfig config = OpenSearchConfig.builder()
        .host("localhost")
        .port(9200)
        .scheme("https")
        .indexName("my-knowledge")
        .username("admin")
        .password("admin")
        .searchMode(SearchMode.HYBRID)
        .vectorDimensions(1536)    // text-embedding-3-small
        .keywordWeight(0.3f)       // the BM25 weight
        .vectorWeight(0.7f)        // the vector weight
        .build();

// 2) the embedding client configuration
OpenAIEmbeddingConfig embeddingConfig = OpenAIEmbeddingConfig.builder()
        .apiKey(System.getenv("OPENAI_API_KEY"))
        .model("text-embedding-3-small")
        .dimensions(1536)
        .build();

EmbeddingClient embeddingClient = new OpenAIEmbeddingClient(embeddingConfig);

// 3) build the OpenSearch client
OpenSearchClient client = OpenSearchClientFactory.create(config);

// 4) build the knowledge store
KnowledgeStore store = new OpenSearchKnowledgeStore(
        client,
        config,
        new SimpleDocumentChunker(),
        embeddingClient,
        fileSystem
);
```

### 3. VECTOR mode

Vector search only. The configuration is the same as HYBRID except for `searchMode(SearchMode.VECTOR)`.

```java
OpenSearchConfig config = OpenSearchConfig.builder()
        .host("localhost")
        .searchMode(SearchMode.VECTOR)
        .vectorDimensions(1536)
        // no need to set keywordWeight/vectorWeight
        .build();
```

---

## Usage

### Indexing documents

```java
// the default options (*.md, *.txt, recursive, at most 1000 documents)
IndexResult result = store.index("/knowledge", IndexOptions.defaults());

System.out.println("indexing done: " + result.getIndexedDocumentCount() + " documents, "
        + result.getIndexedChunkCount() + " chunks (" + result.getDurationMs() + "ms)");

if (!result.getErrors().isEmpty()) {
    System.err.println("errors: " + result.getErrors());
}
```

### Customising the indexing options

```java
IndexOptions options = IndexOptions.builder()
        .filePatterns(List.of("*.md", "*.txt", "*.yaml"))
        .recursive(true)
        .maxDocuments(500)
        .maxChunkSize(1000)
        .build();

IndexResult result = store.index("/knowledge", options);
```

### Searching

```java
// a plain search
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

### Search options

```java
// a file-pattern filter plus a minimum-score threshold
SearchQuery query = SearchQuery.builder()
        .queryText("deployment rollback procedure")
        .maxResults(10)
        .minScore(0.3)
        .filePatterns(List.of("*.md"))
        .build();

List<SearchResult> results = store.search(query);
```

### Reindexing

Deletes the existing index and indexes everything again from scratch.

```java
IndexResult result = store.reindex("/knowledge", IndexOptions.defaults());
```

### Checking the index status

```java
IndexStatus status = store.getStatus();
System.out.println("state: " + status.getState());        // READY, INDEXING, EMPTY, ERROR
System.out.println("documents: " + status.getDocumentCount());
System.out.println("chunks: " + status.getChunkCount());
System.out.println("last indexed at: " + status.getLastIndexedAt());
```

---

## Agent integration

Inject a `KnowledgeStore` into the `OrcaAgentRuntime` and the agent can search automatically through `KnowledgeSearchTool`.

### Injecting a KnowledgeStore into the ExecutionContext

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
        .knowledgeStore(store)       // inject the KnowledgeStore
        .build();
```

### The agent's RAG flow

```
the user's question
  → the LLM decides to call KnowledgeSearchTool
  → KnowledgeSearchTool.execute(query)
  → OpenSearchKnowledgeStore.search(query)
  → the results come back as a ToolResult
  → the LLM composes an answer from those results
```

The agent searches through `KnowledgeSearchTool` like this:

```
KnowledgeSearch(query: "CrashLoopBackOff troubleshooting", max_results: 5)
```

---

## Managing the index

### The OpenSearch index mapping

The structure of the index mapping that gets created automatically:

| Field | Type | Description |
|------|------|------|
| `document_path` | `keyword` | The VFS file path |
| `chunk_content` | `text` | The chunk text (what BM25 searches) |
| `chunk_index` | `integer` | The chunk's ordinal within the document |
| `embedding` | `knn_vector` | The vector (VECTOR/HYBRID modes only) |
| `metadata` | `object` | Extra metadata |
| `indexed_at` | `date` | When it was indexed |

### Internal limits

| Item | Default | Description |
|------|--------|------|
| `BULK_BATCH_SIZE` | 100 | Maximum chunks per OpenSearch bulk request |
| `EMBEDDING_BATCH_SIZE` | 50 | Maximum texts per Embedding API call |
| `MAX_FILE_SIZE_BYTES` | 10 MB | Files larger than this are excluded from indexing |

### Cleaning up resources

```java
// clean up the KnowledgeStore (this does not close the OpenSearchClient)
store.close();

// clean up the OpenSearchClient (separately)
client._transport().close();
```

When it has been injected into an `OrcaAgentRuntime`, `KnowledgeStore.close()` is called automatically as the context closes. The `OpenSearchClient`'s transport, however, has to be managed separately.

---

## Configuration reference

### OpenSearchConfig

| Property | Type | Default | Description |
|------|------|--------|------|
| `host` | `String` | (required) | The OpenSearch host |
| `port` | `int` | `9200` | The port |
| `scheme` | `String` | `"https"` | `"http"` or `"https"` |
| `indexName` | `String` | `"aimon-knowledge"` | The index name |
| `username` | `String` | `null` | The authentication user name |
| `password` | `String` | `null` | The authentication password |
| `searchMode` | `SearchMode` | `KEYWORD` | The search mode |
| `vectorDimensions` | `int` | `1536` | The number of vector dimensions |
| `keywordWeight` | `float` | `0.3` | The BM25 weight in HYBRID mode |
| `vectorWeight` | `float` | `0.7` | The vector weight in HYBRID mode |

> `keywordWeight + vectorWeight` must add up to exactly `1.0`.

### OpenAIEmbeddingConfig

| Property | Type | Default | Description |
|------|------|--------|------|
| `apiKey` | `String` | (required) | The OpenAI API key |
| `baseUrl` | `String` | `null` | A custom API URL (an OpenAI-compatible server) |
| `model` | `String` | `"text-embedding-3-small"` | The embedding model |
| `dimensions` | `int` | `1536` | The number of output vector dimensions |
| `timeout` | `Duration` | `30s` | The request timeout |

### IndexOptions

| Property | Type | Default | Description |
|------|------|--------|------|
| `filePatterns` | `List<String>` | `["*.md", "*.txt"]` | The glob patterns of files to index |
| `recursive` | `boolean` | `true` | Whether to include subdirectories |
| `maxDocuments` | `int` | `1000` | The maximum number of documents to index |
| `maxChunkSize` | `int` | `2000` | The maximum characters per chunk |

---

## Troubleshooting

### "EmbeddingClient is required for search mode VECTOR"

You did not pass an `EmbeddingClient` while in VECTOR or HYBRID mode.

```java
// wrong: HYBRID, but no EmbeddingClient
new OpenSearchKnowledgeStore(client, config, chunker, fileSystem);

// right: the EmbeddingClient is passed
new OpenSearchKnowledgeStore(client, config, chunker, embeddingClient, fileSystem);
```

### The search returns an empty list

1. Check that `store.getStatus().getState()` is `READY`
2. Check that `IndexResult.getErrors()` was empty after calling `index()`
3. Check that the OpenSearch cluster is reachable
4. Check that the index names match

### Files get skipped during indexing

- File size > 10MB → skipped automatically (a warning is logged)
- An extension that does not match `filePatterns`
- An empty file, or content that produces no chunks
- The `maxDocuments` limit was reached

### The OpenSearch connection fails

```java
// switch to HTTP
OpenSearchConfig config = OpenSearchConfig.builder()
        .host("localhost")
        .scheme("http")    // HTTP instead of HTTPS
        .build();
```

### A vectorDimensions mismatch

`OpenSearchConfig.vectorDimensions` and `OpenAIEmbeddingConfig.dimensions` must be identical. A mismatch makes indexing and search fail.

```java
// the dimensions of the two configurations must agree
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

## Related documents

- [OpenSearch RAG design](../../design/knowledge/knowledge-and-rag.md) — the design rationale for this store
- [Knowledge Search design](../../design/knowledge/knowledge-and-rag.md) — the design rationale for the `KnowledgeStore` interface and keyword search (Phase 1). That document's proposed Phase 2 vector layer has been superseded by the one above
- [Tool development guide](../tool/tool-development-guide.en.md)
- [The KnowledgeStore interface](../../../modules/aimon-core/src/main/java/at/aimon/core/knowledge/KnowledgeStore.java)
- [KnowledgeSearchTool](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/knowledge/KnowledgeSearchTool.java)
