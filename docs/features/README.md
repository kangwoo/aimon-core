# 기능별 문서 (Features)

기능 하나를 깊게 파야 할 때 들어오는 곳이다. 각 디렉토리는 **기능 영역 하나**에 대응하며,
그 영역의 사용 가이드·개발 가이드·운영 가이드가 한 폴더에 모여 있다 (독자 역할이 아니라
**기능**으로 나눈다).

- 무엇이 있는지부터 훑고 싶다면 → [`../overview/features.md`](../overview/features.md) (기능 카탈로그)
- 왜 그렇게 설계했는지 → 각 절의 **설계 근거** 링크

---

## [`agent-execution/`](agent-execution/) — 에이전트 실행

ReAct 루프, 턴 처리, 중단, 입력 큐.

| 문서 | 내용 |
|------|------|
| [`command-queue-guide.md`](agent-execution/command-queue-guide.md) | 턴 도중 들어온 사용자 입력을 큐에 넣고 처리하는 방법 |
| [`interruptible-tools-guide.md`](agent-execution/interruptible-tools-guide.md) | Ctrl+C, NOW priority 입력 등 외부 중단 처리와 `InterruptBehavior` |
| [`system-reminder-convention.md`](agent-execution/system-reminder-convention.md) | `<system-reminder>` 합성 컨텍스트를 넣는 규약 |

설계 근거: [`agent-runtime-scope.md`](../design/agent-execution/agent-runtime-scope.md) ·
[`orca-executor.md`](../design/agent-execution/orca-executor.md) ·
[`interceptor.md`](../design/agent-execution/interceptor.md) ·
[`interrupt.md`](../design/agent-execution/interrupt.md) ·
[`compaction.md`](../design/agent-execution/compaction.md) ·
[`artifact.md`](../design/agent-execution/artifact.md)

## [`session/`](session/) — 세션

대화 영속, 라이브 세션 핸들, 멀티 노드 배포.

| 문서 | 내용 |
|------|------|
| [`agent-session-tutorial.md`](session/agent-session-tutorial.md) | 처음 세션을 붙일 때 — 최소 예제부터 |
| [`agent-session-guide.md`](session/agent-session-guide.md) | `LiveSession` API 레퍼런스와 이벤트 스트리밍 |
| [`web-session-deployment-guide.md`](session/web-session-deployment-guide.md) | `SessionRouter` 단일/멀티 노드 배포 |

IMPORTANT: 세션(`SessionRecord`)과 라이브 세션(`LiveSession`)의 차이를 먼저 읽는다 —
[`../overview/scope-model.md` §3](../overview/scope-model.md).

설계 근거: [`session-model.md`](../design/session/session-model.md) ·
[`session-model.md`](../design/session/session-model.md) ·
[`routing.md`](../design/session/routing.md) ·
[`backends.md`](../design/session/backends.md) ·
[`backends.md`](../design/session/backends.md)

## [`tool/`](tool/) — 도구

에이전트가 외부와 상호작용하는 단위.

| 문서 | 내용 |
|------|------|
| [`tool-development-guide.md`](tool/tool-development-guide.md) | **새 도구를 만들 때의 정본** — `Tool` 계약, 스키마, 에러 처리, 권한 |
| [`parallel-tool-execution-guide.md`](tool/parallel-tool-execution-guide.md) | 같은 배치 내 도구 병렬 실행 (`ConcurrencyBehavior`) |
| [`browser-tool-guide.md`](tool/browser-tool-guide.md) | Playwright 기반 `Browser` 도구 설정·사용 |

설계 근거: [`parallel-execution.md`](../design/tool/parallel-execution.md) ·
[`tool-search.md`](../design/tool/tool-search.md)

## [`skill/`](skill/) — 스킬

프롬프트·도구·훅을 묶은 선언적 능력 패키지.

| 문서 | 내용 |
|------|------|
| [`builtin-agent-skill-guide.md`](skill/builtin-agent-skill-guide.md) | 빌트인 Agent/Skill 시스템과 사용자 정의 오버라이드 |

표준 명세: [`agentskills-specification.md`](../references/agentskills-specification.md) ·
[`aimon-skill-extensions.md`](../references/aimon-skill-extensions.md)

설계 근거: [`command-unification.md`](../design/skill/command-unification.md) ·
[`approval-scope.md`](../design/skill/approval-scope.md)

마이그레이션: [`custom-command-to-skill.md`](../migration/custom-command-to-skill.md)

## [`hook/`](hook/) — 훅

라이프사이클 13개 지점의 개입.

| 문서 | 내용 |
|------|------|
| [`hook-development-guide.md`](hook/hook-development-guide.md) | 훅 작성 — 이벤트 타입, `HookFeedback` |
| [`hook-config-guide.md`](hook/hook-config-guide.md) | 파일 기반 훅 설정과 핫리로드, 계층 병합 |

parity 경계: [`hooks-specification.md`](../references/hooks-specification.md)

설계 근거: [`hook-system.md`](../design/hook/hook-system.md) ·
[`async-rewake.md`](../design/hook/async-rewake.md)

## [`subagent/`](subagent/) — 서브에이전트

격리 컨텍스트에서 도는 하위 에이전트.

| 문서 | 내용 |
|------|------|
| [`subagent-development-guide.md`](subagent/subagent-development-guide.md) | 코드(Java)로 서브에이전트 정의·등록 (`Subagent.builder()`) |

설계 근거: [`code-defined-registration.md`](../design/subagent/code-defined-registration.md) ·
[`execution.md`](../design/subagent/execution.md)

## [`workflow/`](workflow/) — 워크플로

여러 서브에이전트의 결정론적 오케스트레이션.

| 문서 | 내용 |
|------|------|
| [`workflow-cli-guide.md`](workflow/workflow-cli-guide.md) | CLI에서 `Workflow`/`WorkflowJs` 도구와 `/runs` 명령 |
| [`workflow-usage-guide.md`](workflow/workflow-usage-guide.md) | 코드로 조립·실행·백그라운드·재개 |

설계 근거: [`workflow.md`](../design/workflow/workflow.md) 및
phase [3](../design/workflow/workflow.md) ·
[4](../design/workflow/workflow.md) ·
[5](../design/workflow/workflow.md)

## [`llm/`](llm/) — LLM 연동

프로바이더 추상화와 계측.

| 문서 | 내용 |
|------|------|
| [`llm-provider-development-guide.md`](llm/llm-provider-development-guide.md) | 새 `LlmClient` 구현 |
| [`llm-usage-metering.md`](llm/llm-usage-metering.md) | 토큰·비용 미터링 |

설계 근거: [`streaming.md`](../design/llm/streaming.md) ·
[`multimodal-content.md`](../design/llm/multimodal-content.md) ·
[`cancellation.md`](../design/llm/cancellation.md)

## [`memory/`](memory/) — 메모리

세션을 가로질러 남는 장기 기억.

| 문서 | 내용 |
|------|------|
| [`memory-usage-guide.md`](memory/memory-usage-guide.md) | 관찰 기록부터 회상까지 |

설계 근거: [`peer-memory.md`](../design/memory/peer-memory.md)

## [`knowledge/`](knowledge/) — 지식 / 위키

외부에서 넣어 준 문서의 색인과 검색.

| 문서 | 내용 |
|------|------|
| [`opensearch-knowledge-store-guide.md`](knowledge/opensearch-knowledge-store-guide.md) | OpenSearch RAG Knowledge Store |

참조 패턴: [`llm-wiki.md`](../references/llm-wiki.md)

설계 근거: [`knowledge-and-rag.md`](../design/knowledge/knowledge-and-rag.md)(인터페이스 + 키워드 검색) ·
[`knowledge-and-rag.md`](../design/knowledge/knowledge-and-rag.md)(벡터/RAG — 앞 문서의 Phase 2 안을 대체)

## [`scheduling/`](scheduling/) — 스케줄링

cron/일회성 예약 실행과 루틴.

| 문서 | 내용 |
|------|------|
| [`quartz-scheduling-web-deployment-guide.md`](scheduling/quartz-scheduling-web-deployment-guide.md) | Quartz 클러스터 스케줄러 배포 |

설계 근거: [`llm-scheduling-agent.md`](../design/scheduling/llm-scheduling-agent.md)

## [`observability/`](observability/) — 관측

| 문서 | 내용 |
|------|------|
| [`execution-tracing-guide.md`](observability/execution-tracing-guide.md) | 실행 트레이싱 설정·조회·레닥션 |

설계 근거: [`tracing.md`](../design/observability/tracing.md) ·
[`tracing.md`](../design/observability/tracing.md)

---

## 아직 전용 가이드가 없는 영역

카탈로그에는 있지만 별도 가이드 문서가 없는 기능들이다. 지금은 **설계 문서와 Javadoc이 정본**이다.

| 영역 | 지금 볼 곳 |
|------|-----------|
| MCP 연동 | [`mcp-tool.md`](../design/integration/mcp-tool.md) |
| 샌드박스 | [`sandbox.md`](../design/integration/sandbox.md) · `modules/aimon-sandbox/README.md` |
| 파일시스템 / 셸 | `at.aimon.core.filesystem` · `at.aimon.core.shell` 의 `package-info.java` |
| 명령 (`/compact` 등) | [`command-unification.md`](../design/skill/command-unification.md) |
| 권한 / 자격증명 | [`tool-development-guide.md` › 권한 시스템](tool/tool-development-guide.md) |

## 새 문서를 여기에 추가할 때

- 기능 영역 디렉토리에 넣는다. 없으면 만든다 (디렉토리 이름은 카탈로그의 영역 이름을 따른다).
- 이 색인의 해당 절에 한 줄 추가한다.
- 그 기능이 [`../overview/features.md`](../overview/features.md) 카탈로그에 없다면 거기에도 추가한다.
- **설계 근거는 여기 쓰지 않는다** — [`../design/`](../design/) 에 두고 링크한다.
