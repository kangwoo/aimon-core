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
| [`max-tokens-truncation-reporting.md`](agent-execution/max-tokens-truncation-reporting.md) | `max_tokens` 에서 잘린 응답에 두 ReAct 루프가 같은 답을 주는 자리 — 잘린 도구 호출을 실행하지 않고 거절하는 이유, 포크의 `TRUNCATED`, 추론 토큰을 숫자로만 붙이는 WARN, thinking 기록 §16.8 과 백로그 L-16 의 정정 |
| [`skill-loop-truncation-and-fork-stall.md`](agent-execution/skill-loop-truncation-and-fork-stall.md) | 그 판정이 스킬 루프와 `ReActLlmDeriver` 에 닿는 자리 — 포크와 스킬 루프가 턴의 정체 가드를 함께 쓰는 이유, 멈춘 포크를 새 값이 아닌 `ERROR` 로 두고 열한 독자가 그것을 읽는 방식, `Task` 도구가 잘린 포크를 말하는 줄 |

### session — 영속 세션과 노드 로컬 핸들

| 문서 | 무엇이 있나 |
|------|------------|
| [`session-model.md`](session/session-model.md) | `SessionRecord` : `LiveSession` = 1 : 0..N 비대칭. 무엇이 재시작을 넘는가, 식별자 축, 함정 |
| [`spi-extraction.md`](session/spi-extraction.md) | 세션 SPI 를 `aimon-core` 로 내리고 라우팅만 밖에 남긴 이관 |
| [`routing.md`](session/routing.md) | sticky 라우팅 없이 세션당 턴을 직렬화하는 멀티 노드 계층 |
| [`backends.md`](session/backends.md) | PostgreSQL · MongoDB · Redis 세 백엔드의 스키마와 보장 차이 |
| [`inbox-collect-durability.md`](session/inbox-collect-durability.md) | 인박스 `collect` 가 한 항목의 디코드 실패로 배치를 잃지 않게 — 후보 셋의 백엔드별 비용과 기각 사유 |

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
| [`streaming.md`](llm/streaming.md) | LLM 응답을 provider 중립 청크로 흘리는 전송 — 청크 kind 와 싱크 계약, 게이트웨이 재시도와 reset, 실행기 이벤트와 프리픽스 보존, 전사에 답으로 남지 않는 추론 델타 채널 |
| [`cancellation.md`](llm/cancellation.md) | 진행 중인 LLM 호출을 끊는 경로 |
| [`multimodal-content.md`](llm/multimodal-content.md) | 이미지·문서를 메시지에 싣는 콘텐츠 모델 |
| [`model-capabilities.md`](llm/model-capabilities.md) | "이 모델이 무엇을 받는가" 를 조회할 수 있는 사실로 만드는 능력 기술자와 레지스트리 — fail-open 의 정의, 모델 이름을 스니핑하지 않는 이유, 내장 표의 행과 그 측정 근거 |
| [`request-parameters.md`](llm/request-parameters.md) | 두 provider 가 와이어 요청을 조립할 때 함께 지키는 규칙 — 값을 지어내지 않는 우선순위, 샘플링 파라미터의 생략, reasoning effort ladder, 요청과 모델이 어긋날 때의 보고 |
| [`reasoning-traces.md`](llm/reasoning-traces.md) | provider 의 불투명 추론 payload 를 도구 호출 너머로 되싣는 코어 슬롯 — 부착·영속 규칙, 두 provider 의 캡처와 재전송, 드롭과 보고, reasoning 토큰 회계 |
| [`openai-responses-path.md`](llm/openai-responses-path.md) | OpenAI 클라이언트가 요청마다 Chat Completions 와 Responses 중 하나로 가는 이음매 — 라우팅 술어, Responses 요청·변환·오류, reasoning summary |
| [`anthropic-thinking.md`](llm/anthropic-thinking.md) | Anthropic thinking 요청의 해석 — 모드 × 모델 방언, 예산 사다리와 clamp, `AUTO` 정책, display, 조립 중에는 보고하지 않는 보고 계약 |
| [`configuration-surface.md`](llm/configuration-surface.md) | LLM 설정의 세 표면(CLI · 스타터 · frontmatter) — 공통과 벤더 네임스페이스를 가르는 규칙, 능력 선언 키, 바인딩 불변식, 값이 선언까지 옮겨지는지 보는 왕복 가드 |
| [`model-name-resolution.md`](llm/model-name-resolution.md) | 요청에 실리는 모델 이름과 CLI 가 보여 주는 이름 — 모델을 적지 않은 부품은 클라이언트의 기본 모델로 돈다는 원칙, 쓰인 그대로 보내는 이름, provider 전환 기동 검사 |

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
| [`config-value-expansion-and-frontmatter-strictness.md`](integration/config-value-expansion-and-frontmatter-strictness.md) | 작성자가 적은 설정 값이 말없이 버려지던 두 자리 — 필드 목록 대신 토큰 스트림 위의 일반 확장, 바인딩 전에 풀면서도 원문 스칼라를 잃지 않는 자리, 프론트매터가 못 읽는 값을 보고하게 만든 규칙 |

### documentation — 문서 자체를 지키는 장치

이 축은 제품 서브시스템이 아니라 **저장소 문서의 도구**다. §1 의 도메인 축에서 벗어나는
두 자리 중 하나이며(다른 하나는 아래 `testing`), 이 축의 이유는 `translation-structure-check.md` 의 첫 절에 적혀 있다.

| 문서 | 무엇이 있나 |
|------|------------|
| [`documentation/translation-structure-check.md`](documentation/translation-structure-check.md) | 정본과 번역본의 구조 일치를 강제하는 검사 — 여섯 축의 처분, 실패/경고를 가르는 쌍의 상태, 예외 표현, 공허 통과가 아님을 보이는 프로브 |
| [`documentation/backlog-register-check.md`](documentation/backlog-register-check.md) | 백로그 등록부의 중복 ID 와, 항목과 어긋난 표제·색인 건수에 실패하는 검사 — 무엇을 항목과 상태로 읽는가, 번호로 인용되는 등록부의 선언된 읽기, 등록부 간 ID 유일성, 면제 없는 실패, 읽기 규칙을 하나씩 끄는 셀프 테스트 |

### testing — 모듈의 테스트가 무엇 위에서 도는가

이 축도 제품 서브시스템이 아니라 **테스트가 서는 바닥** — 빌드가 모듈의 테스트에 건네는 클래스패스와 테스트킷 —
이고, 그래서 `features/` 쪽에 마주 보는 이름이 없다. 결정문은 코드 옆(`gradle/libs.versions.toml` 의 `junit`
노트)에 있고, 이 디렉토리에는 그 결정의 측정과 기각한 대안이 있다 — 빌드 스크립트의 주석에는 대안의 크기를 잰
표가 들어갈 자리가 없기 때문이다. 이름이 `build` 가 아닌 이유는 하나다: 루트 `.gitignore` 의 `build/` 가 그 이름의
디렉토리를 어느 깊이에서든 무시하므로, `docs/design/build/` 에 둔 문서는 커밋되지 않는다.

| 문서 | 무엇이 있나 |
|------|------------|
| [`testing/test-classpath-shipped-versions.md`](testing/test-classpath-shipped-versions.md) | 테스트 클래스패스가 발행 버전과 어긋난 아홉 자리를 출처별로 맞추거나 받아들인 결정 — `aimon-cli` 의 두 테스트 클래스패스만 런타임과 일관되게 해석하는 이유, 주석 jar 두 출처를 받아들인 근거, 발행되는 메모리 테스트킷의 JUnit 바닥, 크기를 재서 기각한 대안들 |
| [`testing/shipped-logback-and-test-classpath-followups.md`](testing/shipped-logback-and-test-classpath-followups.md) | CLI 배포본이 싣는 Logback 을 1.5.13 에서 1.6.3 으로 올린 결정 — 1.5.x 의 어느 버전도 아닌 이유(CVE-2026-19880 은 1.6.3 에서만 고쳐졌다), 권고를 id 로 찾지 않고 검색하는 이유, 모듈 빌드 스크립트가 `@Incubating` Gradle API 를 부르는 조건과 조용히 지나갈 수 있는 절반, 메모리 계약 스위트를 JUnit 바닥에서 한 번 돌린 기록, #111 이 남긴 기록 넷의 정리 |
| [`testing/packed-logback-and-advisory-reporting-followups.md`](testing/packed-logback-and-advisory-reporting-followups.md) | 발행되지 않는 샘플 앱이 싣는 Logback 을 Spring Boot 의 `logback.version` 으로 카탈로그의 1.6.3 에 맞춘 결정 — 권고 범위 안의 버전을 알리는 스캐너를 두지 않고 읽기에 기댄다고 적은 이유(GitHub 과 OSV 가 두 CVE 를 어떤 패키지에도 잇지 않는다), Dependabot 의 열린 PR 한도를 5 에서 50 으로 올린 이유, CLI 가 시작할 때 Logback 상태를 찍게 하던 참조되지 않은 appender, #127 의 기록이 GitHub 권고 데이터베이스와 Dependabot 에 대해 틀린 자리의 정정 |
| [`testing/release-gate-provider-keys.md`](testing/release-gate-provider-keys.md) | export 된 프로바이더 키가 평범한 빌드를 과금되는 라이브 API 실행으로 바꾸는 문제 — 키가 환경에 있으면 시작하지 않는 릴리스 스크립트, 거부 목록을 키 게이트와 대조하는 인구조사, 가드가 읽는 파일을 테스트 입력으로 선언하는 규칙 |

### backlog — 아직 결정하지 않은 것

| 문서 | 무엇이 있나 |
|------|------------|
| [`backlog/orca-executor-speculative-side-work.md`](backlog/orca-executor-speculative-side-work.md) | 투기적 side-work — 착수 조건이 갖춰지지 않아 보류된 항목 |

---

## 3. 문서 규약

§3.1 ~ §3.3 은 이 디렉토리의 문서가 따르는 규약이다. **리뷰에서 승인된 설계를 그대로 커밋한 기록**은 그중 일부를
면제받는 대신 다른 의무를 진다 — 어느 기록이 그런지, 무엇이 풀리고 무엇이 남는지는 §3.4 가 정한다.

### 3.1 설계 문서에 있어야 하는 것

- 첫머리 **`Status` 한 줄** — 구현 상태와 적용 대상 모듈. 무엇이 남았는지는 마지막 절을 가리킨다
- **설계 결정** 표 — 쟁점과 그에 대한 결정, 그리고 **기각한 대안과 그 이유**
- **하지 말 것** — 이 설계가 무너지는 방식. 대부분 코드 주석에 이미 박혀 있는 것들이다
- **참조 파일 지도** — "이 절의 근거를 코드에서 확인하려면 어디를 보나"

### 3.2 설계 문서에 없어야 하는 것

| 넣지 않는 것 | 어디에 속하나 |
|-------------|--------------|
| 체크박스 · WI/WU 표 · Phase 로그 | `docs/plan/` — 진행 중인 계획이 있을 때만 존재하고 끝나면 지운다 ([`../project/documentation-guide.md`](../project/documentation-guide.md) 참조) |
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

### 3.4 승인된 설계를 그대로 커밋한 기록

설계를 리뷰에서 승인받은 뒤 **그 글을 고치지 않고** 커밋하고, 구현이 그 글에서 벗어난 자리는 끝에 덧붙인 절에
적는 기록이 있다. 본문을 고치지 않는 이유는 하나다 — 구현이 끝난 뒤에 고친 본문은 처음부터 맞았던 것처럼
읽힌다. 이런 기록의 값은 **승인할 때 무엇을 믿었고 그중 무엇이 틀렸는가**에 있고, 본문을 고치는 순간 그 값이
사라진다. 승인된 본문에는 테스트 전략과 `file:line` 이 들어 있어 §3.2 · §3.3 과 부딪히는데, 그 둘을 빼는 것도
본문을 고치는 일이다. 이 절은 본문 쪽을 택한다.

**어느 기록인가 — 표지.** 면제는 기록이 스스로 표지를 가질 때만 성립한다. 표지는 **`Status` 블록 안의 문장**이며
두 가지를 함께 말한다. 표지를 가진 기록을 아래에서 **면제 기록**이라 부른다.

1. **경계 절을 번호로 가리킨다** — `§N` (링크여도 되고, 여럿을 가리키면 가장 앞의 것). 문서에 `## N.` 제목이
   있어야 하고, 그 절과 뒤따르는 번호 절은 승인 뒤에 덧붙인 것이다. 제목의 글은 정하지 않는다
2. **경계 앞의 본문이 승인된 글 그대로라고 말한다** — *"the body as approved … kept byte-exact"*,
   *"리뷰를 통과한 설계 그대로다"* 처럼. 정하는 것은 문구가 아니라 이 사실이다

하나라도 없으면 면제가 아니다. 경계 절이 있어도 `Status` 가 본문을 **구현에 맞춰 고쳤다**고 말하면
(*"corrected where the code disagreed"*) 표지가 아니다 — 그 본문은 이 절이 지키려는 것을 이미 잃었다.

표지를 경계 절의 제목이 아니라 `Status` 에 두는 이유는 셋이다.

- 경계 절의 제목은 기록마다 다르고(`After the build — …`, `Where the implementation departed …`,
  `구현이 이 설계와 갈라진 자리`) 이제 와서 맞출 수 없다. 제목을 바꾸면 그것을 가리키는 `#앵커` 가 죽는다
- 제목만으로는 본문을 고쳤는지 알 수 없다. 고친 본문 뒤에 이탈 목록을 붙인 기록도 같은 모양의 제목을 갖는다
- `Status` 는 독자가 본문의 세부를 믿기 **전에** 읽는 자리이고, §1 · §3.1 이 구현 상태를 말하게 한 자리 —
  원래 바뀌는 줄이다. 그래서 승인된 본문을 가진 기록이 표지를 **나중에** 얻어도 본문은 한 글자도 바뀌지 않는다

**표지가 없는 기록.** 테스트 전략이나 `file:line` 을 가졌어도 표지가 없으면 면제가 아니다. §3.1 ~ §3.3 이 전부
적용되고, 그 기록은 규약을 어긴 채로 있다. 고치는 길은 둘이고, 어느 쪽인지는 **그 기록의 이력**이 정한다.
승인된 글을 그대로 커밋했다는 것이 커밋이나 PR 에서 확인되면 `Status` 에 표지를 적는다(본문은 건드리지 않는다).
확인되지 않으면 규약대로 결정만 남긴다. 추측으로 표지를 달지 않는다 — 고친 본문에 붙은 표지는 거짓말이다.

**무엇이 풀리고 무엇이 남는가.**

| 규약 | 면제 기록에서 | 이유 |
|------|--------------|------|
| §3.1 `Status` | **적용.** 표지가 여기 있다 | 원래 바뀌는 줄이므로 표지를 적어도 본문이 바뀌지 않는다 |
| §3.1 설계 결정 표 | **내용은 적용, 표 모양은 면제.** 결정과 기각한 대안은 있어야 한다 — 리뷰가 승인한 모양대로(`D1 …` 절, `Alternatives rejected` 절) | 결정과 기각한 대안이 이 디렉토리에 들어올 자격이다. 모양을 맞추려면 본문을 고쳐야 한다 |
| §3.1 하지 말 것 | **면제** | 모양을 맞추려면 본문을 고쳐야 한다. 그 몫은 대개 승인된 본문의 실패 모드 절이 한다 |
| §3.1 참조 파일 지도 | **면제** | 본문의 파일별 변경 절과 날짜가 있는 인용이 같은 일을 한다. 나중에 붙인 지도는 본문이 읽은 트리가 아니라 그 뒤의 트리를 가리킨다 |
| §3.2 체크박스 · WI/WU 표 · Phase 로그 | **금지 — 경계 뒤 절에서도** | 본문은 고치지 않으므로 거기 박힌 진행 상태는 영원히 틀린 채 남는다. 무엇이 열려 있는지는 [`../backlog/`](../backlog/) 가 정본이다 |
| §3.2 "착수 전 기록" · rev.1/rev.2 정정 이력 | **뒤집힌다.** 정정은 본문에 반영하지 않고 경계 뒤 절에 적는다. 승인된 본문 안의 재확인 기록과 개정 메모는 본문으로 남는다 | 흔적을 지우면 승인할 때 무엇을 믿었는지가 사라진다. 이 기록이 있는 이유가 그것이다 |
| §3.2 사용법 · 설정 예시 · 트러블슈팅 | **금지.** 예외 하나 — 결정이 설정의 모양 자체일 때(키 이름 · 중첩 · 표기) 그 모양을 보인 조각은 본문에 남는다. 그 기능의 사용 가이드는 여전히 [`../features/`](../features/) 에 따로 둔다 | 조각을 빼면 결정이 빠진다. 운영 방법은 결정이 아니다 |
| §3.2 구현 순서 · 테스트 전략 · 기술 스택 | **면제** — 경계 앞뒤 모두 | 리뷰가 승인한 것이 그 계획이고, 경계 뒤 절의 이탈은 그 계획을 기준으로만 뜻을 갖는다. 기준 커밋에 묶여 있으므로 지금의 테스트를 서술한다고 읽히지 않는다 |
| §3.3 상대 경로 링크 | **적용** | `scripts/check-doc-links.py` 가 모든 문서를 검사한다 |
| §3.3 본문에 `file:line` 금지 | **면제.** 대신 인용마다 날짜를 복원할 수 있어야 한다 — 아래 "인용의 날짜" | 줄 번호가 다음 커밋에 틀린다는 이유는 참이다. 그러나 어느 커밋의 줄인지 복원할 수 있는 인용은 틀린 것이 아니라 날짜가 지난 것이다 |
| §3.3 옮기거나 절 번호를 바꾸면 javadoc 도 고친다 | **적용되고 더 엄격하다.** 면제 기록은 절 번호도 제목도 바꾸지 않는다. 덧붙이는 절은 번호를 이어 간다 | 본문을 고치지 않는 것이 면제의 조건이고, 제목이 바뀌면 그것을 가리키는 `#앵커` 가 죽는다 |

면제는 "리뷰를 통과했으니 무엇이든 된다" 가 아니다. 표에서 **금지**로 남은 두 줄은 면제 기록에도 그대로 걸린다 —
진행 상태는 `docs/plan/` 과 `docs/backlog/` 에, 운영 문서는 `docs/features/` 에 있다.

**면제 기록이 지는 의무.**

- **경계 앞의 본문은 승인 뒤에 고치지 않는다.** 틀린 곳은 경계 뒤 절이 말한다. 검색으로 그 줄에 먼저 도착한
  독자는 `Status` 를 읽지 않았으므로, 본문 자리에 경계 뒤 절을 가리키는 정정 표시를 달 수는 있다 — 승인된
  문장을 지우지 않고(취소선으로 남기고) 가리킨 절이 그 정정을 적는 한. 경계 앞에서 승인 뒤에 새로 쓰는 글은
  `Status` 와 이 표시뿐이다
- **덧붙인 절도 같다.** 나중에 틀린 것이 드러나면 고쳐 쓰지 않고 뒤에 새 절을 덧붙인다. 그 절도 쓴 시점의 기록이다
- **`file:line` 을 가진 기록은 본문이 읽은 기준 커밋을 적는다** — `Status` 나 머리 문단에

**인용의 날짜 — 파일이 아니라 줄에서 찾는다.** `file:line` 은 그 줄을 쓴 사람이 읽은 트리에서 참이다. 어느
트리인지는 인용이 선 자리가 정한다.

- **경계 앞 본문의 인용** — 기록이 적은 기준 커밋. 설계가 읽은 트리다. 기록이 들어온 커밋은 답이 아니다 — 같은
  브랜치의 코드 커밋이 인용된 줄을 먼저 옮기거나 지운다. 기준 커밋을 적지 않은 기록이라면 그 줄에 `git blame` 을
  걸어 나온 커밋의 첫 부모(`<커밋>^`)가 차선인데, 기록이 코드와 같은 커밋으로 브랜치의 첫 커밋에 들어왔을 때만
  정확하다
- **경계 뒤 절의 인용** — 그 줄을 쓴 커밋. `git blame -L <줄>,<줄> -- <기록>` 이 가리키는 커밋이고, 병합이 들여온
  줄이면 그 병합 커밋이다
- 파일이 추가된 커밋(`git log --diff-filter=A -- <기록>`)은 어느 쪽의 답도 아니다. 경계 뒤 절은 대개 그 뒤의
  커밋이, 때로는 병합이 덧붙였다

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
| `llm/openai-model-capabilities.md` | [`llm/model-capabilities.md`](llm/model-capabilities.md) · [`llm/request-parameters.md`](llm/request-parameters.md) · [`llm/openai-responses-path.md`](llm/openai-responses-path.md) · [`llm/model-name-resolution.md`](llm/model-name-resolution.md) — 마지막 판본 `378cb6e` |
| `llm/anthropic-thinking-traces.md` | [`llm/reasoning-traces.md`](llm/reasoning-traces.md) · [`llm/anthropic-thinking.md`](llm/anthropic-thinking.md) · [`llm/request-parameters.md`](llm/request-parameters.md) · [`llm/model-capabilities.md`](llm/model-capabilities.md) — 마지막 판본 `3a41fcd` |
| `llm/anthropic-sampling-capabilities.md` | [`llm/request-parameters.md`](llm/request-parameters.md) · [`llm/model-capabilities.md`](llm/model-capabilities.md) · [`llm/configuration-surface.md`](llm/configuration-surface.md) — 마지막 판본 `320fbbc` |
| `llm/model-capability-config-key.md` | [`llm/configuration-surface.md`](llm/configuration-surface.md) · [`llm/model-capabilities.md`](llm/model-capabilities.md) — 마지막 판본 `078ccd1` |
| `llm/model-capability-binding-round-trip.md` | [`llm/configuration-surface.md`](llm/configuration-surface.md) — 마지막 판본 `e62db03` |
| `llm/anthropic-thinking-config-surface.md` | [`llm/configuration-surface.md`](llm/configuration-surface.md) · [`llm/anthropic-thinking.md`](llm/anthropic-thinking.md) — 마지막 판본 `3f6c65b` |
| `llm/reasoning-model-enablement.md` | [`llm/anthropic-thinking.md`](llm/anthropic-thinking.md) · [`llm/model-capabilities.md`](llm/model-capabilities.md) · [`llm/configuration-surface.md`](llm/configuration-surface.md) · [`llm/streaming.md`](llm/streaming.md) — 마지막 판본 `7690737` |
| `llm/reasoning-effort-config-surface.md` | [`llm/request-parameters.md`](llm/request-parameters.md) · [`llm/configuration-surface.md`](llm/configuration-surface.md) · [`llm/anthropic-thinking.md`](llm/anthropic-thinking.md) · [`llm/model-capabilities.md`](llm/model-capabilities.md) — 마지막 판본 `6210326` |
| `llm/reasoning-delta-stream.md` | [`llm/streaming.md`](llm/streaming.md) · [`llm/openai-responses-path.md`](llm/openai-responses-path.md) · [`llm/anthropic-thinking.md`](llm/anthropic-thinking.md) · [`llm/configuration-surface.md`](llm/configuration-surface.md) — 마지막 판본 `eedaa8f` |
| `llm/thinking-reporting-and-dialect-records.md` | [`llm/anthropic-thinking.md`](llm/anthropic-thinking.md) · [`llm/model-capabilities.md`](llm/model-capabilities.md) — 마지막 판본 `8b5a7b9` |
| `llm/provider-switch-agent-model-check.md` | [`llm/model-name-resolution.md`](llm/model-name-resolution.md) — 마지막 판본 `eefb3da` |
| `llm/model-names-sent-and-shown.md` | [`llm/model-name-resolution.md`](llm/model-name-resolution.md) — 마지막 판본 `e69999a` |
| `llm/model-alias-docs-followups.md` | [`llm/model-name-resolution.md`](llm/model-name-resolution.md) — 마지막 판본 `0da239c` |
| `llm/provider-key-release-gate.md` | [`testing/release-gate-provider-keys.md`](testing/release-gate-provider-keys.md) — 마지막 판본 `784ae2f` |
| `llm/provider-key-census-claim-and-inputs.md` | [`testing/release-gate-provider-keys.md`](testing/release-gate-provider-keys.md) — 마지막 판본 `5da3475` |
| `llm/release-gate-docs-and-skill-input.md` | [`testing/release-gate-provider-keys.md`](testing/release-gate-provider-keys.md) — 마지막 판본 `d0a0046` |

병합된 문서(세션 3종 → `session-model.md`, 워크플로 4종 → `workflow.md`, 지식 2종 →
`knowledge-and-rag.md`, 트레이싱 2종 → `tracing.md`, 세션 백엔드 2종 → `backends.md`, llm 20종 → 개념별 10종과
`testing/release-gate-provider-keys.md`)는 **절 번호가 보존되지 않는다.** 옛 문서의 `§N` 을 인용하던 링크는 대상 문서의 목차에서 다시 찾아야 한다.
llm 의 옛 기록 16종은 트리에서 지웠다 — 원문은 표의 마지막 판본 커밋에서 `git show <커밋>:docs/design/llm/<파일>` 로 읽는다.
경로가 그대로인 `streaming.md` · `cancellation.md` · `multimodal-content.md` · `openai-responses-path.md` 도 내용이 다시 쓰여
옛 절 번호가 맞지 않는다.

---

## 관련 문서

- [`../README.md`](../README.md) — 문서 전체 지도
- [`../project/documentation-guide.md`](../project/documentation-guide.md) — 문서를 쓰고 옮기고 번역하는 규칙
- [`../overview/architecture.md`](../overview/architecture.md) — 이 디렉토리의 구성 축이 따르는 서브시스템 구분
- [`../overview/glossary.md`](../overview/glossary.md) — 용어와 수명 사전
- [`../overview/scope-model.md`](../overview/scope-model.md) — 수명·소유권·소멸 책임 규칙
- [`../features/README.md`](../features/README.md) — 같은 도메인 이름의 사용 가이드
- [`../project/solid-principles.md`](../project/solid-principles.md) — 설계 원칙
