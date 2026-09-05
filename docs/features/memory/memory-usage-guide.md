# Memory(Peer Memory) 사용 가이드

> AIMON 의 Honcho-analogue **peer memory** 레이어를 *사용·통합* 하는 방법을 다룹니다.
> 설계 배경·내부 사양은 [`peer-memory.md`](../../design/memory/peer-memory.md) 를 참조하세요.

이 문서는 "메모리를 어떻게 켜고, 어떤 도구가 노출되며, 어떻게 직접 조립하는가" 에 초점을 둡니다.

## 목차

1. [개요](#1-개요)
2. [개념 모델](#2-개념-모델)
3. [빠른 시작 — CLI 설정으로 켜기](#3-빠른-시작--cli-설정으로-켜기)
4. [동작 흐름(Lifecycle)](#4-동작-흐름lifecycle)
5. [노출 도구 4종](#5-노출-도구-4종)
6. [자동 컨텍스트 주입](#6-자동-컨텍스트-주입)
7. [설정 레퍼런스](#7-설정-레퍼런스)
8. [Redaction (시크릿/PII 게이트)](#8-redaction-시크릿pii-게이트)
9. [저장소 백엔드 선택](#9-저장소-백엔드-선택)
10. [프로그래밍 방식 조립(인메모리 최소 골격)](#10-프로그래밍-방식-조립인메모리-최소-골격)
11. [다른 저장소 백엔드 구현하기 (SPI 스펙)](#11-다른-저장소-백엔드-구현하기-spi-스펙)
12. [운영 주의사항 / 트러블슈팅](#12-운영-주의사항--트러블슈팅)
13. [관련 코드 / 문서](#13-관련-코드--문서)

---

## 1. 개요

Peer memory 는 에이전트가 **운영자·시스템·서비스에 대한 사실(Observation)** 을 대화로부터 도출하고,
이를 **세션을 가로지르는 통찰(Representation)** 로 요약해 다음 대화의 컨텍스트로 다시 주입하는 장기 기억 레이어입니다.

| 구성 요소 | 역할 | Honcho 대응 |
|----------|------|------------|
| **Deriver** | 메시지 → Observation 도출 (LLM) | `deriver` |
| **Dialectic Engine** | 자연어 질의에 답변 | `peer.chat()` |
| **Dreamer** | 백그라운드 메모리 정제/통합 (Quartz cron) | `dreamer` |
| **Reconciler** | 충돌하는 Observation 조정 (LLM-as-judge) | — |
| **RedactionPolicy** | 시크릿/PII 마스킹 게이트 | — |

> 단기(세션 내) 대화 압축은 `at.aimon.core.agent.compact` 의 책임입니다. memory 레이어는 **세션 간(장기)** 표상을 담당하며 둘은 보완 관계입니다.

---

## 2. 개념 모델

모든 도메인 객체는 `at.aimon.core.memory` 패키지의 불변 클래스입니다.

```
Workspace            테넌트 격리 단위 (멀티테넌트의 루트)
   └─ PeerView       (Workspace, Principal) 합성 — "이 워크스페이스 안의 어떤 주체"
        ├─ Observation     단일 관찰 사실 (content, type, confidence, sourceMessageIds)
        └─ Representation  특정 시점의 peer 통찰 스냅샷 (summary + observations + tokenCount)
```

- **`Workspace`** — `Workspace.builder().id("default").build()`
- **`PeerView`** — `PeerView.of(workspace, Principal.user("ops-bot", "Ops Bot"))`
  - `subject` = 사실의 *대상* / `observer` = *관찰한 주체*. CLI 기본 배선에서는 `subject == observer`(에이전트 자신).
- **`Observation`** — `ObservationType` 은 **네 값**(`EXPLICIT` 직접 진술 / `DEDUCTIVE` 추론 /
  `INDUCTIVE` 반복된 근거에서의 일반화 / `CONTRADICTION` 충돌 기록). `confidence` 는 `[0,1]`.
  **트리 안의 생산자 셋(`Observe` 도구 · deriver 도구 · `LlmDeriver`)은 앞의 둘만 만든다** — 뒤의 두 값은
  자기 분류가 더 촘촘한 백엔드를 위한 자리이고, 세 생산자는 광고하지 않은 값을 거절한다.
  **읽는 쪽은 다르다**: 저장소는 네 값을 전부 받아야 한다(아래 §11.3).
- **`Representation`** — `isGlobal()`(observer==null, 세션 무관) / `isLocal()`(observer·session 바인딩) 으로 범위가 갈립니다.

> 모든 ID 기반 store API 는 `Workspace` 또는 workspace-bound 값 객체(`ObservationId`)를 받습니다 — 멀티테넌트 격리를 컴파일 타임에 강제합니다(ArchUnit).

---

## 3. 빠른 시작 — CLI 설정으로 켜기

가장 쉬운 경로는 `aimon.yaml`(CLI 설정)에 `memory:` 블록을 추가하는 것입니다.
세 필드(`workspaceId`, `peerId`, `storagePath`)가 모두 채워져야 memory 가 활성화됩니다 (`MemoryConfig.isEnabled()`).

```yaml
memory:
  workspaceId: "default"                              # 필수 — 멀티테넌트 격리 키
  peerId: "agent-default"                             # 필수 — 에이전트 자신의 peer id (observer=subject)
  peerName: "Aimon Agent"                             # 선택 — 미지정 시 peerId 사용
  storagePath: ".aimon/memory/representations.jsonl"  # 필수 — JSONL 로그 (observations.jsonl 이 형제 파일로 생성됨)
  backend: "file"                                     # 선택 — file(기본, 영속) | in-memory(비영속, dev/test)
  reconcilerEnabled: false                            # 선택 — 세션 종료 deriver 에서 LLM-as-judge reconciler opt-in
  dreamer:                                            # 선택 — 백그라운드 통합 (아래 §7 참조)
    enabled: false
```

설정이 활성화되면 `AgentSetupFactory` 가 다음을 자동 배선합니다(`backend` 값에 따라 store 구현 선택):

- **`backend: file`(기본)** — `FileRepresentationStore`(`storagePath`) + `FileObservationStore`(`storagePath` 의 형제 `observations.jsonl`). 둘 다 재시작 후에도 유지. (workspace 자체는 매 부팅 시 이 config 로 재구성되며 어떤 백엔드에도 영속되지 않습니다.)
- **`backend: in-memory`** — `InMemoryRepresentationStore` + `InMemoryObservationStore` (재시작 시 소실, dev/test).
- 사용자 노출 도구 4종 등록(MemorySearch / Observe / MemoryChat / MemoryRecall)
- `MemoryToolContextEnricher` — 매 도구 호출에 workspace/observer/subject/sessionId 주입
- `SnapshotMemoryContextProvider` — 매 턴 system prompt 에 통찰 요약 주입(`SUMMARY_ONLY`)
- 세션 종료 시 1회 도는 **final derivation**(대화 → observation)
- (`dreamer.enabled=true` 일 때) 전용 Quartz 스케줄러로 백그라운드 통합 잡

> ℹ️ **검색 인덱스**: file 백엔드의 `FileObservationStore` 는 내장 `InMemoryObservationIndex`(부분 일치)로 `semanticSearch` 를 제공합니다 — 인덱스 자체는 프로세스 수명(재시작 시 로그 replay 로 재구축). 임베딩 기반 벡터 검색이나 멀티 인스턴스가 필요하면 §9.2 의 PostgreSQL + `IndexedObservationStore` + `KnowledgeStore` 경로로 확장하세요(현재 CLI 미배선).

> ⚠️ **동작 변경 / 로그 증가 주의**:
> - **기본 동작 변경** — `backend` 키를 지정하지 않은 기존 설정도 이제 기본값 `file` 로 동작하여 observation 이 `observations.jsonl` 에 **새로 영속**됩니다(이전에는 in-memory 라 휘발). 이전 동작을 유지하려면 `backend: in-memory` 를 명시하세요.
> - **append-only 로그 증가** — `File*Store` 는 어펜드 로그라 `save`/`delete`/`merge` 가 매번 한 줄을 추가합니다. compaction/retention 이 없으므로 장기 운영 시 JSONL 파일이 단조 증가합니다. 운영 환경에서는 주기적 정리(또는 §9.2 의 PostgreSQL 백엔드)를 고려하세요.

---

## 4. 동작 흐름(Lifecycle)

```
대화 진행 ──▶ (세션 종료) final derivation 큐 enqueue
                     │
                     ▼
        DerivationQueueManager ──★ RedactionPolicy.redact() (시크릿 마스킹)
                     │
                     ▼
                  LlmDeriver ──▶ (선택) Reconciler.evaluate ──▶ ObservationStore.save
                     │
                     └──▶ Representation 요약 생성 ──▶ RepresentationStore.save
                                                              │
다음 대화 시작 ◀── SnapshotMemoryContextProvider.provide() (system prompt 주입)

(백그라운드) Dreamer cron ──▶ RandomWalk 통합 ──▶ SurprisalScorer ──▶ ObservationStore.merge
```

- **Deriver 트리거(CLI)**: 매 메시지가 아니라 **REPL 종료 시점**에 대화 히스토리를 한 번에 처리합니다(`memoryFinalDerivation`). 실제 도출은 큐 워커에서 비동기로 돌고, `AgentSetup.close()` 가 큐를 stop 하며 in-flight 작업을 drain 합니다.
- **Deriver 모델**: CLI 에서는 에이전트의 전역 LLM 모델(`llm.model`)을 그대로 사용합니다. 큐 튜닝값(`DeriverProperties`)에는 모델 이름 항목이 아예 없습니다 — deriver 는 생성 시점에 이미 모델을 들고 오기 때문입니다.
- **Recall 경로**: 자동 주입(`SUMMARY_ONLY`)은 매 턴 저비용으로, 전체 observation 목록이 필요하면 LLM 이 `MemoryRecall` 도구를 명시 호출합니다.
- **GLOBAL representation 의 생산자 = Dreamer**: cross-session **GLOBAL** representation(`findLatestGlobal` / `MemoryRecall` 의 `GLOBAL` 모드)은 final derivation 이 아니라 **Dreamer 가 매 사이클마다 각 subject 의 현재 observation 으로부터 결정론적으로 재생성**합니다(추가 LLM 비용 없음). 따라서 GLOBAL recall 은 Dreamer 가 최소 1회 돈 뒤에야 데이터를 가집니다(`dreamer.enabled=false` 면 GLOBAL 은 비어 있을 수 있음).

---

## 5. 노출 도구 4종

모두 `at.aimon.core.tools.memory` 패키지의 `AbstractTool` 구현이며, 실패 시 예외 대신 `ToolResult.error()` 를 반환합니다.

IMPORTANT: **어떤 도구가 등록되는지는 백엔드가 무엇을 할 수 있는지가 정한다.** 조립 계층이
`MemoryCapabilities.of(peerMemory)` — 백엔드가 선언하는 것이 아니라 티어 접근자에서 **계산되는** 집합 — 을 보고
`MemoryRecall`(SNAPSHOT) · `MemorySearch`(SEARCH) · `Observe`(OBSERVE) · `MemoryChat`(CHAT) 을 하나씩 등록합니다.
못 하는 능력의 도구는 **아예 등록하지 않습니다** — 등록해 놓고 "지원하지 않음"을 돌려주면 모델이 매 실행마다 다시
시도하며 iteration 과 프롬프트 예산을 태우기 때문입니다. 빠진 능력마다 시작 시 degradation 이 한 줄씩 올라옵니다
(`memory-snapshot` · `memory-search` · `memory-chat` · `memory-observe` · `memory-ingest`).

`MemoryChat` 은 이 규칙이 생기기 전까지 **CLI 에서만** 등록되었습니다 — `MemorySpec` 에 `DialecticEngine` 을 담을
자리가 없어서, 스타터로 부팅한 배포는 백엔드가 무엇이든 그 도구를 쓸 수 없었습니다. 지금은 CHAT 티어가 있으면
등록되고, 없으면 `memory-chat` degradation 이 대신 오릅니다.
네 도구 모두 다음 **ToolContext 키**를 공유합니다(`MemoryToolContextKeys`) — 보통 `MemoryToolContextEnricher` 가 채웁니다:

| 키 상수 | 키 이름 | 타입 | 비고 |
|--------|--------|------|------|
| `WORKSPACE` | `memory.workspace` | `Workspace` | 필수 |
| `OBSERVER` | `memory.observer` | `PeerView` | 관찰/질의 주체 |
| `SUBJECT` | `memory.subject` | `PeerView` | 미지정 시 observer 로 폴백 |
| `SESSION_ID` | `memory.sessionId` | `String` | 선택 (LOCAL 범위 상관용) |

### `MemorySearch` (`MemorySearchTool`)

peer 의 observation 을 키워드/의미 검색. 합성 답변이 아니라 raw observation 스니펫(+confidence)이 필요할 때.

| 파라미터 | 타입 | 필수 | 기본 |
|---------|------|------|------|
| `query` | string | ✅ | — |
| `top_k` | number | | 10 (최대 50) |

### `MemoryChat` (`MemoryChatTool`)

"이 사용자에 대해 뭘 알아?" 류 자연어 질의를 `DialecticEngine` 으로 처리.

| 파라미터 | 타입 | 필수 | 기본 |
|---------|------|------|------|
| `question` | string | ✅ | — |
| `level` | string(`FAST`\|`BALANCED`\|`DEEP`) | | `BALANCED` |

### `MemoryRecall` (`MemoryRecallTool`)

최신 Representation 스냅샷을 컨텍스트로 회수.

| 파라미터 | 타입 | 필수 | 기본 |
|---------|------|------|------|
| `mode` | string(`GLOBAL`\|`LOCAL`) | | `GLOBAL` |
| `max_tokens` | number | | 0 (예산 미적용) |

- `LOCAL` 모드는 `memory.observer` 가 반드시 필요합니다.
- `max_tokens` 초과 시 observation 을 떨구고 summary 만 반환합니다.

### `Observe` (`ObserveTool`)

deriver 를 우회해 사실 1건을 명시 등록(관리자/시스템 플로우, 데이터 import).

| 파라미터 | 타입 | 필수 | 기본 |
|---------|------|------|------|
| `content` | string | ✅ | — |
| `type` | string(`EXPLICIT`\|`DEDUCTIVE`) | | `DEDUCTIVE` |
| `confidence` | number `[0,1]` | | 0.7 |

> `MemorySearch` / `Observe` 는 `RedactionPolicy` 가 주입되면 입력(query/content)을 저장·검색 전에 마스킹합니다(§8).
> 또한 deriver 큐로 들어가는 대화는 공유 `MessageRedactor` 가 **모든 메시지(USER/ASSISTANT 텍스트뿐 아니라 TOOL-role 의 tool-result 본문 포함)** 를 마스킹하므로, 명령/로그 출력에 섞인 시크릿도 LLM·observation 에 닿기 전에 가려집니다(§8).

---

## 6. 자동 컨텍스트 주입

`MemoryContextProvider` 는 에이전트가 system prompt 를 조립할 때 호출되어 memory 기반 `SystemPromptPart` 를 기여합니다.
기본 구현 `SnapshotMemoryContextProvider` 는 백엔드의 **SNAPSHOT 티어**(`MemorySnapshotReader`) 위에 서며,
해석 순서는 이렇습니다:

1. `(subject, observer, sessionId)` 의 최신 **LOCAL** 스냅샷
2. 없으면 `subject` 의 최신 **GLOBAL** 스냅샷 (GLOBAL 은 Dreamer 가 생산 — Dreamer 가 1회 돈 뒤에야 존재, §4 참조)
3. 그래도 없으면 `Optional.empty()` → 실행기가 해당 part 를 생략(프롬프트 형태 불변)

기본 백엔드에서 그 스냅샷은 `RepresentationStore` 의 `Representation` 이지만, 티어 위에 서 있으므로
표현을 저장하지 않고 읽을 때 계산하는 백엔드도 같은 provider 로 동작합니다.
`SnapshotMemoryContextProvider.readerOver(representationStore)` 가 스토어 위에 그 티어를 세웁니다.

렌더 방식은 `MemoryInjectionMode` 로 결정:

- `SUMMARY_ONLY` (CLI 기본) — 요약 + 1줄 메타 헤더만. 매 턴 비용이 들므로 저비용 유지.
- `FULL` — 요약 + observation. 양수 `maxTokens` 초과 시 observation 을 떨굼(요약은 항상 유지).

executor 에는 `OrcaAgentExecutorFactory.withMemoryContextProvider(...)` 로 주입됩니다. `null` 이면 memory part 없이 동작.

---

## 7. 설정 레퍼런스

### 7.1 CLI `memory` 블록 (`MemoryConfig`)

| 키 | 타입 | 필수 | 설명 |
|----|------|------|------|
| `workspaceId` | string | ✅ | 멀티테넌트 격리 키 |
| `peerId` | string | ✅ | 에이전트 peer id (observer=subject) |
| `peerName` | string | | 표시명 (기본 = `peerId`) |
| `storagePath` | string | ✅ | JSONL 로그 경로 (representations.jsonl; `observations.jsonl` 이 형제로 생성됨) |
| `backend` | string | | `file`(기본, 영속) \| `in-memory`(비영속, dev/test). 알 수 없는 값은 file 로 폴백(+경고) |
| `ingest` | string | | 대화가 메모리로 흘러 들어가는 시점: `off` \| `session-end`(기본) \| `execution-end`. 알 수 없는 값은 `session-end` 로 폴백(+경고) |
| `reconcilerEnabled` | bool | | 세션종료 deriver 의 LLM-as-judge reconciler opt-in (기본 false) |
| `dreamer` | object | | 백그라운드 통합 (아래) |

#### `ingest` — 세 값이 무엇을 바꾸는가

| 값 | 언제 보내나 | 대가 |
|----|------------|------|
| `off` | 보내지 않는다 | 메모리는 `Observe` 호출이나 다른 프로세스로만 찬다 |
| `session-end` (기본) | REPL 이 끝날 때 전사 전체를 한 번 | 기존 동작 그대로. 델타를 쓰지 않으므로 같은 메시지가 두 번 갈 수 없다. 대신 세션이 도는 동안 배운 것은 그 세션이 쓰지 못한다 |
| `execution-end` | 실행이 끝날 때 그 실행이 추가한 메시지만 | 실행마다 디라이버가 돈다(LLM 호출이 늘어난다). 대신 메모리가 세션 안에서 즉시 쓰인다 |

IMPORTANT: `execution-end` 에는 손실이 하나 있고 그것은 의도된 것이다. 델타의 기준점은 **메시지 개수**이며
(`Message` 에 안정적인 id 가 없다), compaction 이나 프롬프트 크기 복구가 이력을 통째로 갈아 끼우면 그 기준점은
무의미해진다. 그 실행은 **아무것도 보내지 않고** 다음 실행이 다시 기준점을 잡는다 — 요약을 대화인 척 보내거나
이미 수집된 메시지를 다시 보내는 것보다 싸기 때문이다. 근거는
[교체 가능한 메모리 백엔드](../../design/memory/pluggable-memory-backend.md) §7.2 에 있다.

### 7.2 `memory.dreamer` 블록 (`MemoryDreamerConfig`)

```yaml
memory:
  # ... workspaceId/peerId/storagePath ...
  dreamer:
    enabled: true                  # 게이트 (기본 false)
    cron: "*/30 * * * *"           # 5 필드 cron (기본: 30분마다)
    scorer:
      type: llm                    # llm(기본) | embedding
      llm:                         # type=llm — 추가 자격증명 불필요
        model: "gpt-4o-mini"       # 선택 — judge 모델 override (기본: 전역 LLM 모델)
      embedding:                   # type=embedding 일 때 필수 (현재 OpenAI 호환)
        apiKey: "${OPENAI_KEY}"    # 필수
        baseUrl: "https://api.openai.com/v1"  # 선택
        model: "text-embedding-3-small"       # 선택
        dimensions: 1536                       # 선택
    surprisalThreshold: 0.25       # 선택 — 임계값 미만 쌍은 merge (기본 0.25)
    walkSeedCount: 8               # 선택 — subject 당 최신 시드 수 (기본 8)
    neighborTopK: 8                # 선택 — 시드당 semanticSearch fan-out (기본 8)
```

- `scorer.type=llm` 은 항상 ready(전역 LLM 재사용). `embedding` 은 `scorer.embedding.apiKey` 가 없으면 **fail-soft** 로 비활성화되고 시작 시 이유를 로깅합니다(`notReadyReason()`).
- `cron` 은 프레임워크 공통의 **5 필드** 방언입니다(분 시 일 월 요일, 일요일=0). Quartz 의 6 필드 형태(`"0 */30 * * * ?"`)를 쓰면 **시작 시점에 거부**됩니다 — Quartz 로의 번역은 백엔드가 알아서 합니다. 요일을 숫자로 쓰던 설정은 1 을 빼세요(Quartz 금요일 6 → 여기서는 5).
- dreamer 는 전용 Quartz 스케줄러(RAMJobStore)에서 돌아 foreground task 스케줄러와 경합하지 않습니다.
- **단일 노드 전용입니다.** RAMJobStore 는 정의상 JVM 로컬이라 잡을 프로세스 간에 공유하지 않습니다. 같은 workspace 에 CLI 를 두 개 띄우면 통합(consolidation)이 두 번 돕니다 — 스케줄러를 하나로 줄여서 해결되는 문제가 아니라 **잡 저장소가 공유되지 않기 때문**이며, 클러스터링하려면 공유 JDBC JobStore 가 필요합니다.
- dreamer 사이클은 통합(merge) 외에도 매번 (a) **각 subject 의 GLOBAL representation 을 현재 observation 으로 재생성**하고(GLOBAL recall 의 데이터 생산자), (b) **30일 audit window 를 지난 soft-deleted observation 을 purge** 합니다(`purgeSoftDeletedBefore`).

### 7.3 Spring Boot starter `aimon.memory.*`

`aimon-spring-boot-starter` 는 **읽기 경로만** 프로퍼티로 바인딩합니다. 값은 boolean 이 아니라 **선택자**이고,
추측하는 기본값을 두지 않습니다 — 잘못된 peer 에 붙은 메모리는 조용히 *남의* 이력을 답하기 때문입니다.

| 키 | 값 | 필수 | 기본값 |
|----|-----|------|--------|
| `aimon.memory.backend` | `none` \| `in-memory` \| `supplied` | | `none` (배선 안 함) |
| `aimon.memory.workspace-id` | string | ✅ (backend ≠ `none`) | — |
| `aimon.memory.peer-mode` | `fixed` \| `caller` | | `fixed` |
| `aimon.memory.peer-id` | string | ✅ (peer-mode=`fixed`) | — |
| `aimon.memory.injection-mode` | `SUMMARY_ONLY` \| `FULL` | | `SUMMARY_ONLY` (§6) |
| `aimon.memory.max-tokens` | int | | `0` (상한 없음) |
| `aimon.memory.redaction` | `default` \| `strict` \| `none` \| `supplied` | | `default` |

- `backend=supplied` 는 애플리케이션이 선언한 `RepresentationStore` / `ObservationStore` 빈을 씁니다. 둘 중 하나만
  선언해도 됩니다 — observation 스토어만 있으면 도구만, representation 스토어만 있으면 주입만 배선됩니다.
- 선택자와 빈이 **모순되면 이름을 대며 거절**합니다(`backend=none` 인데 스토어 빈이 있다, `peer-mode=caller` 인데
  `peer-id` 가 있다 등). 조용히 무시된 스토어는 런타임에서 "비어 있는 스토어" 와 구별되지 않기 때문입니다.
- `redaction` 은 이 슬라이스에서 **유일하게 조용한 기본값을 갖는 키**입니다. 마스킹은 안전한 방향이 한쪽뿐이라
  의도적으로 예외로 두었습니다(`MemoryRedaction.DEFAULT`).

스타터가 **주지 않는 것 두 가지**는 시작 시 `RuntimeDegradations` 로 소리 내어 보고됩니다(`MemoryAssembly`):

- **쓰기 경로 없음** — deriver·파생 큐·dreamer 를 배선하지 않습니다. `Observe` 도구가 observation 은 쓰지만,
  representation 은 같은 스토어를 보는 다른 프로세스가 만들어 주지 않으면 주입될 것이 없습니다.
- **`peer-mode=caller` 에서는 메모리 도구가 등록되지 않습니다** — 도구는 `ToolContextEnricher` 로 observer 를
  받는데 그 이음매에는 세션·실행·런타임만 실리고 **principal 이 실리지 않습니다**. 프롬프트 주입은 실행마다 peer 를
  다시 풀 수 있지만 도구는 고정 observer 하나만 담을 수 있습니다.

`fixed` 모드에서 등록되는 것은 §5 의 4종 중 `MemoryRecall` / `MemorySearch` / `Observe` 세 개입니다.
`MemoryChat` 은 `DialecticEngine` 을 요구하므로 스타터 배선에는 없습니다(CLI 는 §7.1 경로에서 배선합니다).

### 7.4 코어에는 메모리 설정 트리가 없다

배포 하나의 메모리를 서술하는 모델은 **`at.aimon.bootstrap.spec.MemorySpec` 하나**이며, 위의 두 표면(CLI yaml
`memory` 블록, 스타터 `aimon.memory.*`)이 각자 그것으로 바인딩합니다. 코어에는 그와 겹치는 프로퍼티 트리가 없습니다.

코어에 남은 설정 값 객체는 **`at.aimon.core.memory.deriver.DeriverProperties`** 하나뿐이고, 담는 것은
파생 큐의 실행 방식 세 가지입니다:

| 값 | 기본값(`defaults()`) | 뜻 |
|----|---------------------|-----|
| `workerCount` | 4 | 큐를 비우는 워커 스레드 수 (≥ 1, 아니면 생성 거부) |
| `batchMaxTokens` | 8000 | 워커 1개가 배치 하나에 넣을 수 있는 토큰 예산 (≥ 1, 아니면 생성 거부) |
| `pollInterval` | 500ms | 빈 큐를 다시 물어보기까지의 대기 |

모델 이름 항목은 없습니다 — deriver 는 생성 시점에 이미 자기 모델을 들고 오기 때문입니다.

> **재정정**: 예전에는 이 자리에 `at.aimon.core.memory.MemoryProperties` 가 있었고 backend/deriver/dialectic/
> dreamer/redaction 5개 영역의 기본값을 선언했습니다(`backend=in-memory` 등). 그중 실제로 읽히는 것은 deriver
> 단계뿐이었고 나머지는 **어떤 조립 경로도 바인딩하지 않는 기본값**이었습니다 — 값이 없는 것보다 나쁜 상태입니다.
> 문서를 읽은 사람은 `in-memory` 를 답으로 받아들이지만, CLI 는 `file` 로, 스타터는 "추측하지 않고 거절" 로
> 동작하고 있었기 때문입니다. 읽히던 한 단계만 위 `DeriverProperties` 로 남기고 나머지는 삭제했습니다
> (설계 근거: [`spring-boot-starter.md`](../../design/integration/spring-boot-starter.md) §7.2.15).

### 7.5 아직 CLI 로 노출되지 않은 선택지 (programmatic-only)

다음은 코어/모듈에서 **프로그래밍 방식 조립(§10/§11)으로만** 선택 가능하며, 아직 CLI yaml 설정 표면으로 노출되지 않았습니다(향후 CLI config surface 로 확장 예정 — 결함이 아니라 미노출):

- **Redaction 정책 선택(`default`|`strict`|`none`|`custom`)** — CLI 는 항상 `DefaultRedactionPolicy` 를 씁니다.
- **원격 `PeerMemory` 백엔드** — CLI 는 `file`/`in-memory` 만 배선합니다(§9.2).
- **`ReActLlmDeriver`** — CLI 는 single-shot `LlmDeriver` 를 씁니다.

CLI 가 현재 사용하는 조합은: `DefaultRedactionPolicy` + `LlmDeriver` + 설정된 `file`/`in-memory` 백엔드.

---

## 8. Redaction (시크릿/PII 게이트)

IT 운영 메시지에는 토큰·비밀번호·API 키·사설 IP 가 일상적으로 등장합니다. 이들이 observation 으로 영구화되면 즉시 보안 사고이므로,
`RedactionPolicy` 가 **deriver 큐 진입 직전**과 **`MemorySearch`/`Observe` 입력**에서 강제 마스킹합니다.

| 정책 | 클래스 | 설명 |
|------|--------|------|
| `default` | `DefaultRedactionPolicy` | AWS 키, JWT/Bearer, 사설 IP, 이메일, 일반 시크릿 패턴 |
| `strict` | `StrictRedactionPolicy` | 유사 키워드까지 강화 차단 |
| `none` | — | **운영 금지** — 시작 시 ERROR |

마스킹된 observation 은 `metadata` 에 `redacted=true`, `redaction.categories=<CSV>` 를 남깁니다.

> **모든 메시지 마스킹(TOOL-role 포함)**: 모든 큐 매니저가 공유하는 `MessageRedactor` 는 deriver 큐로 들어가는 대화의 **모든 메시지** 텍스트를 마스킹합니다 — USER/ASSISTANT 텍스트뿐 아니라 **TOOL-role 의 tool-result 본문** 까지 포함합니다. 따라서 명령 출력·로그에 노출된 시크릿도 LLM 으로 전송되거나 observation 으로 영구화되기 전에 가려집니다.

---

## 9. 저장소 백엔드 선택

세 종류의 store(`WorkspaceStore`, `ObservationStore`, `RepresentationStore`)는 모두 인터페이스이며 구현체 교체로 백엔드를 바꿉니다.

| 백엔드 | 모듈 | 구현체 | 용도 |
|--------|------|--------|------|
| In-memory | `aimon-core` | `InMemory*Store` | 개발/테스트 전용 (재시작 시 소실, 1만 건↑ OOM) |
| File | `aimon-core` (`at.aimon.core.memory.file`) | `File*Store` (JSONL 어펜드 로그 + compaction + 파일락) | 단일 노드 영속 (§9.1) |

- **여기 있는 둘은 전부 단일 JVM 한정입니다.** 멀티 인스턴스는 store 를 바꾸는 것이 아니라 백엔드 전체를 바꾸는 것이며, 그 경로는 §9.2 입니다.
- 벡터 검색은 별도 RAG 스택을 두지 않고 `KnowledgeStore`(예: `aimon-knowledge-opensearch`)에 `KnowledgeScope("memory.observation")` 로 위임합니다. 메타데이터 전용 `ObservationStore` 를 쓰는 배포에서는 `semanticSearch` 가 `UnsupportedOperationException` 을 던질 수 있고, 검색은 `IndexedObservationStore` 데코레이터로 복원합니다.

### 9.1 File 백엔드 (`at.aimon.core.memory.file`)

단일 노드용 **JSON-line 어펜드 로그** 기반 영속입니다 — 원격 메모리 서비스를 붙이기 전(또는 붙일 필요가 없을 때)의 선택지입니다(§9.2). 세 store 가 각각 자기 로그 파일을 가집니다.

```java
WorkspaceStore workspaceStore = new FileWorkspaceStore(Paths.get(".aimon/memory/workspaces.jsonl"));
RepresentationStore representationStore = new FileRepresentationStore(Paths.get(".aimon/memory/representations.jsonl"));
ObservationStore observationStore = new FileObservationStore(Paths.get(".aimon/memory/observations.jsonl"));
```

동작 특성:

- **어펜드 로그 + replay** — `save`/`delete`/`merge` 가 각각 한 줄(JSON)로 추가됩니다. 생성자에서 로그를 처음부터 replay 하여 인메모리 상태를 복원하므로, 재시작 후에도 상태가 유지됩니다.
- **fsync 옵션** — 두 번째 인자 `fsyncOnAppend`(기본 `true`)로 매 추가마다 디스크 flush 여부를 제어합니다. 처리량을 위해 끄면 크래시 시 마지막 몇 줄이 유실될 수 있습니다.
  ```java
  new FileRepresentationStore(path, /* fsyncOnAppend */ false);
  ```
- **검색(`semanticSearch`)** — `FileObservationStore` 는 기본적으로 `InMemoryObservationIndex`(부분 일치)를 사용합니다. 벡터 검색이 필요하면 인덱스를 주입하세요:
  ```java
  ObservationIndex index = new KnowledgeStoreObservationIndex(knowledgeStore);
  ObservationStore store = new FileObservationStore(path, index, /* fsyncOnAppend */ true);
  ```
- **Compaction (로그 무한증가 방지)** — `save`/`delete`/`merge`/`softDelete` 가 어펜드만 하므로 로그가 단조 증가합니다. 세 store 는 `Compactable.compact()`(현재 live + audit 상태의 최소 라인 집합으로 원자적 재작성: temp → fsync → `ATOMIC_MOVE`)를 제공하며, 저널이 `max(10_000, live×3)` 라인을 넘으면 **자동 compaction**, 그리고 **기동 시** replay 가 bloated 로그를 발견하면 1회 압축합니다 → 디스크/재시작 비용이 전체 이력이 아니라 live 상태에 비례.
- **단일 프로세스 강제 (OS 파일락)** — 생성자가 sidecar `<log>.lock` 에 배타 `FileLock` 을 잡습니다. 같은 로그를 두 번째로 열면(타 프로세스/동일 JVM) `AimonException` 으로 **즉시 실패**(조용한 손상 대신). store 는 `AutoCloseable` 이라 `close()`(또는 JVM 종료) 시 락을 해제합니다. 멀티 인스턴스/스케일아웃은 §9.2.
- **soft-delete + audit retention** — `merge` 의 loser 와 `softDelete(id)` 대상은 즉시 폐기되지 않고 audit 윈도로 soft-delete 되어 replay 후에도 유지됩니다. `purgeSoftDeletedBefore(ws, cutoff)` 가 윈도를 강제합니다.
- **Dreamer-무관 retention/compaction 스케줄러** — `FileMemoryMaintenanceScheduler` 가 주기적으로(기본 6시간) 워크스페이스별 `purgeSoftDeletedBefore`(기본 30일) + `RepresentationStore.deleteOlderThan`(기본 90일) 후 전 store `compact()` 를 실행합니다. Dreamer 가 꺼져 있어도 retention/디스크가 보장됩니다. CLI 는 `backend: file` 일 때 이를 자동 기동하고 `AgentSetup.close()` 에서 정지합니다.
  ```java
  FileMemoryMaintenanceScheduler maintenance = new FileMemoryMaintenanceScheduler(
          workspaceStore, observationStore, representationStore,
          List.of(workspaceStore, observationStore, representationStore));
  maintenance.start();   // 데몬; 종료 시 maintenance.close()
  ```
- **`findAll` 접근 제어** — `FileWorkspaceStore.findAll(Principal)` 은 `WorkspaceAccessPolicy`(기본 `DefaultWorkspaceAccessPolicy`: SYSTEM/SERVICE 전체, USER/GROUP 은 unowned 또는 `acl.owner`/`acl.members` 메타데이터 일치)로 필터합니다. 생성자에 policy 를 주입해 교체할 수 있습니다.
- CLI 의 `memory.storagePath` 는 `FileRepresentationStore` 의 로그 경로이자, 그 형제 파일 `observations.jsonl` 의 기준 경로이기도 합니다. **`backend: file`(CLI 기본)에서는 `createObservationStore` 가 `FileObservationStore` 를 함께 배선하여 observation 도 `observations.jsonl` 에 파일 영속**합니다. observation 을 휘발시키려면 `backend: in-memory` 를 명시하세요.

### 9.2 멀티 인스턴스 — 별도 메모리 서비스

> **`aimon-memory-postgres` 와 `aimon-memory-mongodb` 는 제거되었습니다.** 이 절에 있던 두 백엔드
> 조립 절차 — Flyway/`V1__init.sql`, `mem_outbox` 릴레이, `init.js`, row-lock 파생 큐 — 는 더 이상
> 이 저장소에 없습니다.

멀티 인스턴스 메모리는 이제 **저장소 백엔드가 아니라 백엔드 전체를 바꾸는 것**입니다. 교체 지점은 세
store 인터페이스가 아니라 `at.aimon.core.memory.PeerMemory` 의 다섯 티어이며, 그 자리를 채우는 구현이
별도 저장소의 서비스입니다 — [aimon-memory](https://github.com/kangwoo/aimon-memory) (Postgres +
pgvector, 자체 파생 워커, HTTP API). 그 저장소의 `aimon-memory-client` 가 `RemotePeerMemory` 로
`PeerMemory` 를 구현하므로, 애플리케이션은 조립하는 `PeerMemory` 를 바꾸기만 하면 됩니다.

| 없어진 것 | 그 자리를 맡은 것 |
|---|---|
| `PostgresDerivationQueueManager` (row-lock 파생 큐) | `aimon-memory-worker` 의 `WorkerLoop` + Representation/Summary/Dream/Deletion 컨슈머 |
| `KnowledgeStoreOutboxRelay` (outbox → 임베딩 색인) | pgvector 네이티브 (`Vectors`, `EmbeddingDimensionCheck`, `aimon-memory-embed`) |
| `Postgres`/`Mongo` `{Observation,Representation,Workspace}Store` | `aimon-memory-store` — Flyway + JDBC repository 12종 |

IMPORTANT: **이전(migration)이 아니라 제거입니다.** 서비스의 스키마는 `(workspace, observer, observed)`
복합 키 위에 선 다른 설계이고, 옛 `mem_*` 테이블·컬렉션의 데이터는 **그리로 옮겨가지 않습니다.**
두 백엔드에 데이터를 쌓아 둔 배포는 `0.2.4` 에 머무르거나, 서비스에서 빈 상태로 시작합니다.

설계 근거는 [`pluggable-memory-backend.md`](../../design/memory/pluggable-memory-backend.md) §4.3,
호환성 경계는 [`api-stability.md`](../../project/api-stability.md) §4.1 에 있습니다.

---

## 10. 프로그래밍 방식 조립(인메모리 최소 골격)

CLI 없이 애플리케이션에 직접 통합할 때의 최소 골격입니다 (CLI `AgentSetupFactory` 의 배선을 단순화한 형태).

```java
// 1) 도메인 / 식별자
Workspace workspace = Workspace.builder().id("default").build();
PeerView observer = PeerView.of(workspace, Principal.user("agent-default", "Aimon Agent"));

// 2) 저장소 (운영은 File* 또는 원격 PeerMemory 백엔드로 교체)
ObservationStore observationStore = new InMemoryObservationStore();
RepresentationStore representationStore =
        new FileRepresentationStore(Paths.get(".aimon/memory/representations.jsonl"));

// 3) 보안 게이트
RedactionPolicy redaction = new DefaultRedactionPolicy();

// 4) Deriver + 큐 (메시지 → observation)
Reconciler reconciler = new DefaultReconciler(llmClient, modelName); // 선택
Deriver deriver = new LlmDeriver(llmClient, observationStore, modelName, representationStore, reconciler);
DerivationQueueManager queue = new InMemoryDerivationQueueManager(
        deriver, redaction, DeriverProperties.defaults());
queue.start();

// 5) 자연어 질의 엔진
DialecticEngine dialectic = new LlmDialecticEngine(llmClient, observationStore, modelName);

// 6) 도구 등록 (ToolRegistry)
registry.register(MemorySearchTool.overStore(observationStore, redaction));
registry.register(ObserveTool.overStore(observationStore, redaction));
registry.register(new MemoryChatTool(dialectic));
registry.register(MemoryRecallTool.overStore(representationStore));

// 7) ToolContext 자동 채움 + system prompt 자동 주입
ToolContextEnricher enricher = new MemoryToolContextEnricher(workspace, observer);   // executor factory 에 전달
MemoryContextProvider memoryContext = new SnapshotMemoryContextProvider(
        SnapshotMemoryContextProvider.readerOver(representationStore), workspace,
        MemoryPeerResolver.fixed(observer.getPrincipal()),
        MemoryInjectionMode.SUMMARY_ONLY, 0);                                        // withMemoryContextProvider(...)
```

종료 시 `queue.stop()` 으로 in-flight derivation 을 drain 하세요. memory 컴포넌트는 **application-scoped** 이므로 `AgentRuntime` 소멸과 무관하게 유지하고, 앱 shutdown 또는 명시적 제거 시에만 정리합니다.

> 실배선 참조: `modules/aimon-cli/.../factory/AgentSetupFactory.java` 의 `buildMemoryWiring`, `buildMemoryDeriver`,
> `buildDerivationQueue`, `buildMemoryContextProvider`, `registerCliTools`, `buildDreamerSubsystem`.

---

## 11. 다른 저장소 백엔드 구현하기 (SPI 스펙)

DynamoDB, Cassandra 등 새 백엔드를 추가하는 것은 **리팩토링이 아니라 구현체 추가**입니다
([multi-instance-design](../../../.claude/rules/multi-instance-design.md)). `at.aimon.core.memory` 의 인터페이스만 구현하면 됩니다.

> 아래 스켈레톤(§11.5)은 MongoDB 를 예로 듭니다. 참조 구현으로 볼 것은 `at.aimon.core.memory.file` 입니다 — soft-delete/retention, `findAll` ACL, 어펜드 로그 + compaction 이 거기 다 있습니다. **다만 store 를 새로 구현하기 전에 §9.2 를 먼저 읽으세요**: 멀티 인스턴스가 목적이라면 필요한 것은 새 store 가 아니라 새 `PeerMemory` 백엔드입니다.

### 11.1 구현 대상 인터페이스

| 인터페이스 | 필수? | 책임 | 인메모리 참조 |
|-----------|------|------|--------------|
| `WorkspaceStore` | ✅ | 테넌트(워크스페이스) CRUD | `InMemoryWorkspaceStore` |
| `ObservationStore` | ✅ | observation 메타데이터·관계·confidence | `InMemoryObservationStore` |
| `RepresentationStore` | ✅ | representation 스냅샷 (append-only) | `InMemoryRepresentationStore` |
| `ObservationIndex` | 선택 | 검색 인덱스 (의미/키워드). 메타데이터 store 와 분리 | `InMemoryObservationIndex`, `KnowledgeStoreObservationIndex` |
| `DerivationQueueManager` | 선택 | 멀티 인스턴스 derivation 큐 | `InMemoryDerivationQueueManager` |

대부분의 새 백엔드는 **3개 store + (선택) ObservationIndex** 만 구현하면 충분합니다. 큐는 단일 노드면 인메모리 큐를 재사용해도 됩니다.

### 11.2 공통 계약 (모든 store)

- **멀티테넌트 격리(컴파일 타임)** — 모든 ID 기반 메서드는 `Workspace` 또는 workspace-bound 값 객체(`ObservationId`)를 받습니다. **bare `String id` 파라미터 메서드를 추가하면 안 됩니다.** 유일한 예외는 부트스트랩용 `WorkspaceStore.findById(String)` 하나이며, ArchUnit 이 이 한 곳만 화이트리스트합니다.
- **스레드 안전** — store 는 동시 호출에 안전해야 합니다.
- **불변 반환** — 도메인 객체(`Observation`, `Representation`, `Workspace`)는 불변입니다. 조회는 `Optional`/불변 `List` 로 반환하세요(`List.copyOf(...)`).
- **예외 래핑** — 드라이버 예외는 모듈 경계를 넘기지 말고 `AimonException`(`at.aimon.core.base`) 으로 감싸세요. 자세히는 [error-handling](../../../.claude/rules/error-handling.md).
- **workspace 일치 검증** — observation 의 `subject`·`observer` 는 같은 워크스페이스에 속해야 한다는 도메인 불변식을 저장 시 보장하세요.

### 11.3 인터페이스별 세부 계약

**`WorkspaceStore`**
- `create(Workspace)` — id 중복은 `IllegalStateException` 으로 거부.
- `findById(String)` — 부트스트랩 전용 raw 조회.
- `findAll(Principal requester)` — **접근 제어 적용**. 운영 백엔드는 실제 ACL 을 강제해야 합니다(인메모리는 전부 반환).
- `delete(Workspace)` — observation/representation cascade 정리는 호출자 또는 별도 잡 책임(또는 DB FK CASCADE).

**`ObservationStore`** (`save`, `findById`, `findBySubject(limit)`, `count`, `semanticSearch`, `findByConfidenceBelow`, `findSubjects`, `delete`, `merge`)
- `confidence` 는 `[0,1]` 범위 — 저장 시 검증(체크 제약 권장).
- `type` 은 **`ObservationType` 의 네 값을 전부 받아야 한다** (`EXPLICIT` · `DEDUCTIVE` · `INDUCTIVE` ·
  `CONTRADICTION`). 트리의 생산자가 앞의 둘만 만든다고 해서 **두 값짜리 체크 제약을 걸지 말 것** —
  자기 분류가 더 촘촘한 백엔드가 넣은 행을 읽지 못하게 된다. 저장소는 생산자가 아니라 **판독자**다.
- `findBySubject` 는 최신순, `findByConfidenceBelow` 는 confidence 오름차순(dreamer 가 통합 후보를 찾는 데 사용). `limit >= 1`.
- `findSubjects(workspace, limit)` 는 dreamer 가 워크스페이스의 모든 peer 를 1회 순회하는 데 사용 — 순서 무보장, `limit` 으로 상한.
- `merge(winner, loser, merged)` — `merged.id == winner` 여야 함. 영속 백엔드는 loser 를 **soft-delete 하고 30일 audit 보관**(인메모리는 즉시 폐기). PostgreSQL 은 `soft_deleted_at` 컬럼으로 구현.
- `semanticSearch` — **두 가지 선택지**:
  1. *직접 구현 안 함* — `UnsupportedOperationException` 을 던지고(예: `PostgresObservationStore`) 검색은 `ObservationIndex` 를 소유한 다른 store 로 위임. C3 split 권장 경로.
  2. *인덱스 결합* — `ObservationIndex` 를 주입받아 `save`/`delete`/`merge` 마다 인덱스를 동기화하고 `search` 결과 id 를 메타데이터로 hydrate. 코어의 **`IndexedObservationStore`** 데코레이터가 이 write-through 결합을 기성으로 제공하므로(인메모리 참조는 `InMemoryObservationStore(index)`), 새 백엔드는 메타데이터 store 만 만들고 `new IndexedObservationStore(metadataStore, index)` 로 감싸면 됩니다.

**`RepresentationStore`** (`save`, `findLatestGlobal`, `findLatestLocal`, `deleteOlderThan`)
- append-only 규약: 수정하지 말고 새 스냅샷을 저장.
- `findLatestGlobal` 은 `observer == null` 인 최신 스냅샷, `findLatestLocal(subject, observer, sessionId)` 은 `sessionId == null` 일 때 cross-session 매칭.
- `deleteOlderThan(workspace, cutoff)` 로 retention 정리.

**`ObservationIndex`** (선택; `index`, `delete`, `search`)
- `index` 는 같은 `ObservationId` 의 이전 엔트리를 **덮어써야** 합니다(content/confidence 갱신 반영).
- `search(subject, query, topK)` 는 `ObservationId` 만 반환(payload 미소유). 호출자가 메타데이터 store 로 hydrate.
- workspace·subject 스코프를 반드시 지킬 것: A 의 검색이 B 의 observation 을 반환하면 안 됩니다.

### 11.4 모듈 생성 절차

1. `modules/aimon-memory-dynamodb/build.gradle.kts` 생성 — `implementation(project(":aimon-core"))` + 드라이버 의존성.

   ```gradle
   plugins {
       id("aimon.java-conventions")
       id("aimon.publishable")   // Maven Central 게시 시
   }
   dependencies {
       implementation(project(":aimon-core"))     // ❗ api() 금지 — 코어 타입 누출 방지
       implementation(libs.mongodb.driver)         // gradle/libs.versions.toml 에 추가
       implementation(libs.bundles.jackson)
       implementation(libs.slf4j.api)
   }
   ```
2. `settings.gradle.kts` 에 `include("modules:aimon-memory-dynamodb")` 추가.
3. 패키지 네임스페이스 `at.aimon.memory.dynamodb` (외부 모듈은 자체 네임스페이스 — 모듈 이름과 맞춘다). `at.aimon.core.<domain>.impl` 직접 import 금지 — 코어 인터페이스에만 의존.
4. 게시 대상이면 루트 `build.gradle.kts` 의 publishable 목록에 추가.

### 11.5 스켈레톤 예시 (`WorkspaceStore` / MongoDB)

> §11.4 가 `dynamodb` 를 예로 든 것과 달리 아래는 **MongoDB 를 예로 든 별개의 스켈레톤**입니다 —
> 그래서 패키지 이름도 다릅니다. 둘을 이어 붙인 하나의 예제가 아닙니다.

```java
package at.aimon.memory.mongodb;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.mongodb.client.MongoCollection;
import org.bson.Document;

import at.aimon.core.base.AimonException;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.WorkspaceStore;

/** MongoDB-backed {@link WorkspaceStore}. Thread-safe; the driver pools connections. */
public final class MongoWorkspaceStore implements WorkspaceStore {

    private final MongoCollection<Document> workspaces;   // mem_workspace 대응 컬렉션

    public MongoWorkspaceStore(MongoCollection<Document> workspaces) {
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces cannot be null");
    }

    @Override
    public Workspace create(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        try {
            if (workspaces.find(new Document("_id", workspace.getId())).first() != null) {
                throw new IllegalStateException("workspace already exists: " + workspace.getId());
            }
            workspaces.insertOne(toDocument(workspace));   // _id = workspace.getId()
            return workspace;
        } catch (RuntimeException e) {
            throw new AimonException("MongoDB error during workspace create: " + workspace.getId(), e);
        }
    }

    @Override
    public Optional<Workspace> findById(String id) {     // 부트스트랩 전용 예외
        Objects.requireNonNull(id, "id cannot be null");
        Document doc = workspaces.find(new Document("_id", id)).first();
        return Optional.ofNullable(doc).map(MongoWorkspaceStore::fromDocument);
    }

    @Override
    public List<Workspace> findAll(Principal requester) {
        Objects.requireNonNull(requester, "requester cannot be null");
        // 운영 백엔드는 requester 기준 ACL 을 적용해야 한다.
        // ...
        return List.of();
    }

    @Override
    public void delete(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace cannot be null");
        workspaces.deleteOne(new Document("_id", workspace.getId()));
    }

    private static Document toDocument(Workspace ws) { /* 직렬화 */ return new Document(); }
    private static Workspace fromDocument(Document d) { /* 역직렬화 */ return Workspace.builder().id(d.getString("_id")).build(); }
}
```

`ObservationStore`/`RepresentationStore` 도 같은 형태로, PostgreSQL 스키마(`V1__init.sql`)의 컬럼·인덱스를 MongoDB 컬렉션·인덱스로 옮기면 됩니다. 특히:
- `mem_observation` 의 `(workspace_id, subject_principal_type, subject_principal_id, created_at DESC)` 인덱스 → `findBySubject` 가속.
- `(… , confidence)` 인덱스 → `findByConfidenceBelow` 가속.
- representation 의 latest-global / latest-local 인덱스 → `findLatest*` 가속.

### 11.6 테스트 기대치

- 단위 ≥80% 커버리지. 인터페이스 계약(멀티테넌트 격리, confidence 범위, merge 의미, latest 조회)을 회귀 테스트로 검증.
- 실DB 통합은 Testcontainers 사용(`@Tag("docker")`). `aimon-session-postgres` 의 `*IntegrationTest` 가 같은 패턴을 그대로 갖고 있으니 그쪽을 참고하세요.
- 멀티 인스턴스 큐를 구현했다면 동시 클레임·만료 steal·work-unit 직렬화 시나리오를 검증.

---

## 12. 운영 주의사항 / 트러블슈팅

- **메모리가 안 켜져요** → `workspaceId`/`peerId`/`storagePath` 세 필드가 모두 채워졌는지 확인(`MemoryConfig.isEnabled()`). 하나라도 비면 전체 비활성.
- **재시작하면 observation 이 사라져요** → `memory.backend` 가 `in-memory` 인지 확인. file 백엔드(기본)는 `storagePath` 옆 `observations.jsonl` 에 영속됩니다. 단, 검색 인덱스는 프로세스 수명(재시작 시 replay 재구축).
- **Recall 이 빈 결과예요** → deriver 는 CLI 에서 **세션 종료 시** 돕니다. 첫 종료 전에는 representation 이 없을 수 있습니다.
- **dreamer 가 안 돌아요** → 시작 로그의 `Peer memory dreamer disabled: <reason>` 확인. embedding scorer 는 `apiKey` 누락 시 fail-soft.
- **시크릿이 저장됐어요** → `redaction.policy = none` 은 운영 금지(시작 ERROR). `default`/`strict` 사용.
- **멀티인스턴스** → 인메모리/파일 백엔드는 단일 JVM 한정. 스케일아웃은 원격 `PeerMemory` 백엔드로(§9.2).

---

## 13. 관련 코드 / 문서

- 설계/사양: [`peer-memory.md`](../../design/memory/peer-memory.md)
- 코어 패키지: `modules/aimon-core/src/main/java/at/aimon/core/memory/`
  (`deriver/`, `dialectic/`, `dreamer/`, `reconciler/`, `redaction/`, `index/`)
- 메타데이터 store + 검색 인덱스 결합 데코레이터: `at.aimon.core.memory.IndexedObservationStore`
- 노출 도구: `modules/aimon-core/src/main/java/at/aimon/core/tools/memory/`
- 파일 백엔드: `modules/aimon-core/src/main/java/at/aimon/core/memory/file/`
- 단일 노드 유지보수(파일락 + compaction + retention): `at.aimon.core.memory.file.FileMemoryMaintenanceScheduler`, `Compactable`
- 워크스페이스 접근 정책: `at.aimon.core.memory.WorkspaceAccessPolicy` · `DefaultWorkspaceAccessPolicy`
- 원격 백엔드(멀티 인스턴스): [aimon-memory](https://github.com/kangwoo/aimon-memory) — 서비스와 `RemotePeerMemory` 어댑터
- 백엔드 계약 스위트: `modules/aimon-memory-testkit/` (`AbstractPeerMemoryContractTest`)
- 멀티인스턴스/저장소 분리 규칙: [`multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md)
- CLI 배선: `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java`,
  설정 클래스 `modules/aimon-cli/src/main/java/at/aimon/cli/config/MemoryConfig.java` · `MemoryDreamerConfig.java`
- 설정 예시: `modules/aimon-cli/src/main/resources/default-config.yaml` (memory 블록 주석)
- 도구 개발 일반: [`tool-development-guide.md`](../tool/tool-development-guide.md)
