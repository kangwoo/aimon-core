# 설계 문서 (Design Docs)

각 서브시스템이 **왜 그렇게 생겼는가**를 담는다. 무엇을 만들지의 계획이 아니라 이미 내려진 결정과
그 근거, 그리고 기각한 대안이 여기 있다.

- **어떻게 쓰는가**를 알고 싶으면 → [`../features/`](../features/)
- **무엇이 있는가**를 훑고 싶으면 → [`../overview/architecture.md`](../overview/architecture.md)
- **언제 무엇이 죽는가**가 궁금하면 → [`../overview/scope-model.md`](../overview/scope-model.md)
- **아직 정하지 않은 것**은 → [`../backlog/`](../backlog/) 와 [`backlog/`](backlog/)

---

## 1. 구성 축 — 도메인이지 상태가 아니다

이 디렉토리는 [`../overview/architecture.md`](../overview/architecture.md) 의 서브시스템 구분을 그대로
따르는 **도메인 축**으로 나뉜다. 디렉토리 이름은 [`../features/`](../features/) 의 것과 일치한다 —
같은 주제의 사용 가이드와 설계 근거가 같은 이름으로 마주 보게 하려는 것이다.

예전에는 **상태 축**이었다. 루트가 "제안", `implemented/` 가 "완료" 였고, 문서는 구현이 끝나면 옮겨졌다.
그 축을 버린 이유는 셋이다.

- 찾는 사람은 "이게 구현됐나" 가 아니라 **"세션 이야기가 어디 있나"** 로 찾는다
- 한 문서 안에서 절반은 구현되고 절반은 남는 경우가 흔했고, 그때 디렉토리는 거짓말을 했다
- 구현 여부는 파일 위치가 아니라 문서 첫머리의 `Status` 한 줄이 말하면 된다

`design/implemented/` 는 **더 이상 존재하지 않는다.** 옛 경로로 들어온 링크는 §4 의 표로 옮긴다.

---

## 2. 문서 목록

### agent-execution — ReAct 루프와 그 경계

| 문서 | 무엇이 있나 |
|------|------------|
| [`orca-executor.md`](agent-execution/orca-executor.md) | 메인 ReAct 루프 — iteration 구조, 도구 디스패치, 예산, 스트리밍 |
| [`agent-runtime-scope.md`](agent-execution/agent-runtime-scope.md) | `AgentExecutionContext` → `AgentRuntime` 재정의. agent-scoped 로 옮긴 이유와 `AgentRuntimeId` 가 결정론적인 이유 |
| [`interrupt.md`](agent-execution/interrupt.md) | `InterruptBehavior` 4종, capability 와 coordinator 분리, 도구를 안전하게 끊는 경로 |
| [`interceptor.md`](agent-execution/interceptor.md) | `AgentExecutionInterceptor` — `execute()` 경계를 가로채는 동기 체인 |
| [`compaction.md`](agent-execution/compaction.md) | 컨텍스트가 차기 전 대화 요약. 트리거 조건, 실패 처리, `/compact` |
| [`artifact.md`](agent-execution/artifact.md) | 에이전트가 만든 파일을 사용자에게 건네는 경로 |
| [`integration-test-layers.md`](agent-execution/integration-test-layers.md) | `OrcaAgentRuntime` 통합 테스트의 계층 구분과 무엇을 어디서 검증하는가 |

### session — 영속 세션과 노드 로컬 핸들

| 문서 | 무엇이 있나 |
|------|------------|
| [`session-model.md`](session/session-model.md) | `SessionRecord` : `LiveSession` = 1 : 0..N 비대칭. 무엇이 재시작을 넘는가, 식별자 축, 함정 |
| [`spi-extraction.md`](session/spi-extraction.md) | 세션 SPI 를 `aimon-core` 로 내리고 라우팅만 밖에 남긴 이관 |
| [`routing.md`](session/routing.md) | sticky 라우팅 없이 세션당 턴을 직렬화하는 멀티 노드 계층 |
| [`backends.md`](session/backends.md) | PostgreSQL · MongoDB · Redis 세 백엔드의 스키마와 보장 차이 |

### tool — 도구 계약

| 문서 | 무엇이 있나 |
|------|------------|
| [`contract-hardening.md`](tool/contract-hardening.md) | 스키마 게이트, `additionalProperties: false`, `GenericTool` 바인딩 |
| [`side-effect-axes.md`](tool/side-effect-axes.md) | 부작용을 하나의 등급이 아니라 축으로 나눈 이유 |
| [`parallel-execution.md`](tool/parallel-execution.md) | `ConcurrencyBehavior` 와 2단 게이트(모델 의도 + 프레임워크 안전성) |
| [`tool-search.md`](tool/tool-search.md) | 도구가 많아졌을 때 스키마를 지연 로드하는 검색 계층 |

### skill · hook · subagent · workflow — 확장점

| 문서 | 무엇이 있나 |
|------|------------|
| [`skill/command-unification.md`](skill/command-unification.md) | 슬래시 명령과 스킬을 하나의 진실로 합친 통합 |
| [`skill/approval-scope.md`](skill/approval-scope.md) | pending → session → agent 승인 체인과 각 스코프의 도달 범위 |
| [`hook/hook-system.md`](hook/hook-system.md) | 훅 종류, 설정 체계, 실행 순서 |
| [`hook/async-rewake.md`](hook/async-rewake.md) | 외부 이벤트로 에이전트를 다시 깨우는 rewake 봉투와 바운드 |
| [`subagent/execution.md`](subagent/execution.md) | 포크 실행 — 세션 없는 실행의 정체성, 예산, 격리 |
| [`subagent/background-task-result-persistence.md`](subagent/background-task-result-persistence.md) | 백그라운드 태스크의 **결과**를 저장소로 내린 자리 — 순서 계약, `block=true` 폴링, 크기 정책 |
| [`subagent/code-defined-registration.md`](subagent/code-defined-registration.md) | 마크다운이 아니라 코드로 서브에이전트를 등록하는 경로 |
| [`workflow/workflow.md`](workflow/workflow.md) | 서브에이전트 오케스트레이션 — 스크립트 프론트엔드, 러너 수명, GraalJS 샌드박스 |

### llm — 프로바이더 계약

| 문서 | 무엇이 있나 |
|------|------------|
| [`streaming.md`](llm/streaming.md) | 부분 텍스트 스트리밍 — 청크 타입과 싱크 계약 |
| [`cancellation.md`](llm/cancellation.md) | 진행 중인 LLM 호출을 끊는 경로 |
| [`multimodal-content.md`](llm/multimodal-content.md) | 이미지·문서를 메시지에 싣는 콘텐츠 모델 |

### 상태를 갖는 서브시스템

| 문서 | 무엇이 있나 |
|------|------------|
| [`filesystem/backend-contract.md`](filesystem/backend-contract.md) | `VirtualFileSystem` 계약 — 디렉토리 시맨틱, 최대 파일 크기, 백엔드가 갈리는 자리, 공유 계약 테스트 |
| [`memory/peer-memory.md`](memory/peer-memory.md) | Peer Memory — observation · derivation · Dreamer 사이클, 백엔드별 보장 |
| [`memory/pluggable-memory-backend.md`](memory/pluggable-memory-backend.md) | 메모리 백엔드 교체 — 서비스 고도의 다섯 티어 SPI 와 능력 협상, 없는 수집 이음매, Honcho·Dyad 대조 |
| [`knowledge/knowledge-and-rag.md`](knowledge/knowledge-and-rag.md) | `KnowledgeStore` SPI, 키워드 검색, OpenSearch 벡터/RAG |
| [`scheduling/llm-scheduling-agent.md`](scheduling/llm-scheduling-agent.md) | 자연어를 cron + routine 으로 굳혀 세션 없이 다시 돌리는 경로 |
| [`observability/tracing.md`](observability/tracing.md) | 실행 트레이싱 — 계측 지점, payload 캡처, 레닥션 |

### integration — 바깥과 만나는 자리

| 문서 | 무엇이 있나 |
|------|------------|
| [`spring-boot-starter.md`](integration/spring-boot-starter.md) | 조립 지식을 프레임워크 중립 층(`aimon-bootstrap`)으로 꺼내고 그 위에 얹은 자동설정 |
| [`sandbox.md`](integration/sandbox.md) | 격리 실행 환경을 identifier 로 재사용하는 추상화와 Docker·Kubernetes 구현 |
| [`mcp-tool.md`](integration/mcp-tool.md) | MCP 서버의 도구를 로컬 도구와 구분되지 않게 만드는 어댑터 |

### backlog — 아직 결정하지 않은 것

| 문서 | 무엇이 있나 |
|------|------------|
| [`backlog/orca-executor-speculative-side-work.md`](backlog/orca-executor-speculative-side-work.md) | 투기적 side-work — 착수 조건이 갖춰지지 않아 보류된 항목 |

---

## 3. 문서 규약

### 3.1 설계 문서에 있어야 하는 것

- 첫머리 **`Status` 한 줄** — 구현 상태와 적용 대상 모듈. 무엇이 남았는지는 마지막 절을 가리킨다
- **설계 결정** 표 — 쟁점과 그에 대한 결정, 그리고 **기각한 대안과 그 이유**
- **하지 말 것** — 이 설계가 무너지는 방식. 대부분 코드 주석에 이미 박혀 있는 것들이다
- **참조 파일 지도** — "이 절의 근거를 코드에서 확인하려면 어디를 보나"

### 3.2 설계 문서에 없어야 하는 것

| 넣지 않는 것 | 어디에 속하나 |
|-------------|--------------|
| 체크박스 · WI/WU 표 · Phase 로그 | `docs/plan/` — 진행 중인 계획이 있을 때만 존재하고 끝나면 지운다 ([`../README.md`](../README.md) 참조) |
| "착수 전 기록" · rev.1/rev.2 정정 이력 | 어디에도. 정정은 본문에 반영하고 흔적은 지운다 |
| 사용법 · 설정 예시 · 트러블슈팅 | [`../features/`](../features/) |
| 구현 순서 · 테스트 전략 · 기술 스택 | 계획 산출물. 결정만 남기고 뺀다 |

### 3.3 링크와 코드 참조

- 문서 간 링크는 **상대 경로**로 쓴다. `docs/design/<domain>/x.md` 기준으로 저장소 루트는 `../../../`,
  `docs/overview/` 는 `../../overview/`, 형제 도메인은 `../<domain>/` 이다
- 본문에 **`file:line` 을 박지 않는다.** 줄 번호는 다음 커밋에 틀린다. 클래스·메서드 이름으로 가리키고,
  위치는 부록의 참조 파일 지도에 파일 단위로 적는다
- 문서를 옮기거나 절 번호를 바꾸면 **javadoc 의 참조도 함께 고친다.** 코드에서 이 디렉토리를 가리키는
  주석이 여럿 있다

---

## 4. 옛 경로 대응표

상태 축에서 도메인 축으로 옮기면서 파일이 병합·개명되었다. 백로그가 구현되어 도메인 디렉토리로
올라간 것도 여기 적는다. 옛 이름으로 검색해 들어온 경우 아래를 본다.

| 옛 경로 (`docs/design/`) | 현재 |
|--------------------------|------|
| `implemented/orca-agent-executor-improvement-design.md` | [`agent-execution/orca-executor.md`](agent-execution/orca-executor.md) |
| `implemented/agent-execution-context-rescoping.md` | [`agent-execution/agent-runtime-scope.md`](agent-execution/agent-runtime-scope.md) |
| `implemented/interrupt-behavior-design.md` | [`agent-execution/interrupt.md`](agent-execution/interrupt.md) |
| `implemented/agent-execution-interceptor-design.md` | [`agent-execution/interceptor.md`](agent-execution/interceptor.md) |
| `implemented/conversation-compaction-design.md` | [`agent-execution/compaction.md`](agent-execution/compaction.md) |
| `implemented/file-artifact-design.md` | [`agent-execution/artifact.md`](agent-execution/artifact.md) |
| `implemented/orca-runtime-integration-test-design.md` | [`agent-execution/integration-test-layers.md`](agent-execution/integration-test-layers.md) |
| `implemented/session-first-restructure-design.md` | [`session/session-model.md`](session/session-model.md) |
| `implemented/conversation-state-persistence-design.md` | [`session/session-model.md`](session/session-model.md) |
| `implemented/conversation-decomposition-scope.md` | [`session/session-model.md`](session/session-model.md) |
| `session-spi-extraction-design.md` | [`session/spi-extraction.md`](session/spi-extraction.md) |
| `implemented/web-agent-session-manager-design.md` | [`session/routing.md`](session/routing.md) |
| `implemented/web-agent-session-postgres-design.md` | [`session/backends.md`](session/backends.md) |
| `implemented/web-agent-session-mongodb-design.md` | [`session/backends.md`](session/backends.md) |
| `tool-contract-hardening-design.md` | [`tool/contract-hardening.md`](tool/contract-hardening.md) |
| `tool-side-effect-axes-review.md` | [`tool/side-effect-axes.md`](tool/side-effect-axes.md) |
| `implemented/tool-parallel-execution-design.md` | [`tool/parallel-execution.md`](tool/parallel-execution.md) |
| `implemented/tool-search-design.md` | [`tool/tool-search.md`](tool/tool-search.md) |
| `implemented/skill-command-unification-design.md` | [`skill/command-unification.md`](skill/command-unification.md) |
| `implemented/skill-approval-conversation-scope-design.md` | [`skill/approval-scope.md`](skill/approval-scope.md) |
| `implemented/hook-system-upgrade-design.md` | [`hook/hook-system.md`](hook/hook-system.md) |
| `implemented/async-rewake.md` | [`hook/async-rewake.md`](hook/async-rewake.md) |
| `subagent-execution-improvement-design.md` | [`subagent/execution.md`](subagent/execution.md) |
| `implemented/code-defined-subagent-registration-design.md` | [`subagent/code-defined-registration.md`](subagent/code-defined-registration.md) |
| `backlog/background-task-result-persistence.md` | [`subagent/background-task-result-persistence.md`](subagent/background-task-result-persistence.md) — 구현되어 백로그를 벗어났다 |
| `subagent-workflow-design.md` | [`workflow/workflow.md`](workflow/workflow.md) |
| `subagent-workflow-phase3-design.md` | [`workflow/workflow.md`](workflow/workflow.md) |
| `subagent-workflow-phase4-design.md` | [`workflow/workflow.md`](workflow/workflow.md) |
| `subagent-workflow-phase5-design.md` | [`workflow/workflow.md`](workflow/workflow.md) |
| `implemented/llm-partial-text-streaming-design.md` | [`llm/streaming.md`](llm/streaming.md) |
| `llm-client-cancellation-design.md` | [`llm/cancellation.md`](llm/cancellation.md) |
| `implemented/llm-multimodal-content-design.md` | [`llm/multimodal-content.md`](llm/multimodal-content.md) |
| `implemented/peer-memory-integration.md` | [`memory/peer-memory.md`](memory/peer-memory.md) |
| `implemented/knowledge-search-design.md` | [`knowledge/knowledge-and-rag.md`](knowledge/knowledge-and-rag.md) |
| `implemented/opensearch-rag-design.md` | [`knowledge/knowledge-and-rag.md`](knowledge/knowledge-and-rag.md) |
| `implemented/llm-scheduling-agent-design.md` | [`scheduling/llm-scheduling-agent.md`](scheduling/llm-scheduling-agent.md) |
| `agent-execution-tracing-design.md` | [`observability/tracing.md`](observability/tracing.md) |
| `trace-payload-capture-design.md` | [`observability/tracing.md`](observability/tracing.md) |
| `spring-boot-starter-design.md` | [`integration/spring-boot-starter.md`](integration/spring-boot-starter.md) |
| `implemented/sandbox.md` | [`integration/sandbox.md`](integration/sandbox.md) |
| `implemented/mcp-tool-design.md` | [`integration/mcp-tool.md`](integration/mcp-tool.md) |

병합된 문서(세션 3종 → `session-model.md`, 워크플로 4종 → `workflow.md`, 지식 2종 →
`knowledge-and-rag.md`, 트레이싱 2종 → `tracing.md`, 세션 백엔드 2종 → `backends.md`)는 **절 번호가
보존되지 않는다.** 옛 문서의 `§N` 을 인용하던 링크는 대상 문서의 목차에서 다시 찾아야 한다.

---

## 관련 문서

- [`../README.md`](../README.md) — 문서 전체 지도
- [`../overview/architecture.md`](../overview/architecture.md) — 이 디렉토리의 구성 축이 따르는 서브시스템 구분
- [`../overview/glossary.md`](../overview/glossary.md) — 용어와 수명 사전
- [`../overview/scope-model.md`](../overview/scope-model.md) — 수명·소유권·소멸 책임 규칙
- [`../features/README.md`](../features/README.md) — 같은 도메인 이름의 사용 가이드
- [`../project/solid-principles.md`](../project/solid-principles.md) — 설계 원칙
