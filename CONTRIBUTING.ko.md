---
translated_from: CONTRIBUTING.md
source_commit: aa2bb4a
---

# AIMON Core 기여 가이드

> English: [`CONTRIBUTING.md`](CONTRIBUTING.md)

먼저, AIMON Core 에 기여할 생각을 해 주셔서 고맙습니다. 이 문서는 개발 환경을 어떻게 갖추는지, 어떤 규약을 따르는지, 변경을 어떤 절차로 제출하는지를 정리한 것입니다.

여기서 다루지 않는 것 — 아키텍처, 기능별 가이드, 설계 기록 — 은 문서 카탈로그인
[`docs/README.md`](docs/README.md) 에서 시작하세요.

## 목차

- [행동 강령](#행동-강령)
- [기여하는 방법](#기여하는-방법)
- [개발 환경 설정](#개발-환경-설정)
- [프로젝트 구조](#프로젝트-구조)
- [코딩 규약](#코딩-규약)
- [커밋 메시지](#커밋-메시지)
- [브랜치와 Pull Request](#브랜치와-pull-request)
- [개발자 원본 증명서 (DCO)](#개발자-원본-증명서-dco)
- [보안 문제 신고](#보안-문제-신고)

---

## 행동 강령

이 프로젝트는 [Contributor Covenant 행동 강령](CODE_OF_CONDUCT.md)을 따릅니다. 참여하는 것은 그 조항을 지키겠다는 뜻입니다.

## 기여하는 방법

- **버그 신고** — *Bug report* 템플릿으로 이슈를 엽니다
- **기능 제안** — *Feature request* 템플릿으로 이슈를 열거나, 설계 논의가 필요하면 Discussion 을 시작합니다
- **문서 개선** — 오타, 설명 보강, 번역 모두 환영합니다
- **코드 제출** — 버그 수정, 도구/훅 추가, 새 LLM 프로바이더나 스토리지 백엔드 구현
- **트리아지** — 신고된 버그 재현, 이슈 라벨링, 풀 리퀘스트 리뷰

처음이라 어디서부터 손대야 할지 모르겠다면 `good first issue` 나 `help wanted` 라벨이 붙은 이슈를 찾아보세요.

## 개발 환경 설정

### 사전 준비물

- **Java 17+** (빌드 툴체인이 JDK 17 로 고정되어 있습니다)
- **Gradle 8.x** — 저장소에 포함된 래퍼(`./gradlew`)를 씁니다. 시스템에 따로 설치할 필요 없습니다
- 라이브 API 테스트를 돌릴 때만 필요한 **LLM API 키** — `ANTHROPIC_KEY` 또는 `OPENAI_KEY`. [라이브 API 테스트](#라이브-api-테스트) 참고
- (선택) Testcontainers 를 쓰는 테스트를 위한 **Docker** (MongoDB, Redis, PostgreSQL, OpenSearch)

### 빌드

```bash
./gradlew build               # 전체 모듈 컴파일 + 테스트
./gradlew :aimon-core:build   # 단일 모듈
./gradlew :aimon-cli:run      # REPL 실행
```

### 테스트 실행

```bash
./gradlew test                                                        # 전체 단위 테스트 (@Tag("docker") 제외)
./gradlew :aimon-core:test                                            # 단일 모듈
./gradlew :aimon-core:test --tests "at.aimon.core.agent.tool.*Test"   # 글롭 패턴
./gradlew :aimon-core:test --tests "at.aimon.core.agent.tool.ToolInputTest"  # 단일 클래스
```

### 라이브 API 테스트

실제 프로바이더 API 를 호출하는 테스트 클래스가 넷 있고, 각각 그 프로바이더의 키로
`@EnabledIfEnvironmentVariable` 게이트가 걸려 있습니다.

| 클래스 | 모듈 | 키 |
|--------|------|-----|
| `AnthropicThinkingLiveTest` | `aimon-llm-anthropic` | `ANTHROPIC_KEY` |
| `AnthropicLlmClientIntegrationTest` | `aimon-llm-anthropic` | `ANTHROPIC_KEY` |
| `OpenAIReasoningLiveTest` | `aimon-llm-openai` | `OPENAI_KEY` |
| `OpenAILlmClientIntegrationTest` | `aimon-llm-openai` | `OPENAI_KEY` |

**이 계층에는 CI 신호가 전혀 없습니다.** 어느 워크플로도 두 키를 주지 않으므로, 키가 없는 곳에서는 —
CI 를 포함해 — 이 클래스들이 각각 `SKIPPED` 로 보고되고 `checkAll` 은 초록으로 남습니다.
이 계층은 사람이 검증하기로 마음먹었을 때만 검증됩니다.

```bash
ANTHROPIC_KEY=... OPENAI_KEY=... \
./gradlew :aimon-llm-anthropic:test --rerun \
              --tests 'at.aimon.core.llms.anthropic.AnthropicThinkingLiveTest' \
              --tests 'at.aimon.core.llms.anthropic.AnthropicLlmClientIntegrationTest' \
          :aimon-llm-openai:test --rerun \
              --tests 'at.aimon.core.llms.openai.OpenAIReasoningLiveTest' \
              --tests 'at.aimon.core.llms.openai.OpenAILlmClientIntegrationTest'
```

**돌릴 때마다 돈이 듭니다** — 키 주인의 계정에 청구되는 실제 호출입니다. 키를 커밋하지 말고, 이슈나
풀 리퀘스트에 붙이는 실패 출력에서는 키를 가리세요.

**게이트는 반대 방향으로도 걸리고, 위 명령이 키를 그 명령에만 붙이는 이유가 그것입니다.** 이 클래스들을
평범한 빌드에서 빼 주는 것은 환경 변수 하나뿐입니다 — 태그가 없고, 기본 `test` 태스크는 `docker` 와
`packaging` 만 뺍니다. 그래서 어떤 셸에 키가 export 되어 있는 동안에는 — 이 계층을 위해서든 CLI 를 돌리기
위해서든 — 그 셸의 모든 `./gradlew test` 와 `checkAll` 이 위 명령만이 아니라 그 프로바이더의 라이브 클래스까지
돌립니다. 그 모듈의 `test` 태스크가 `UP-TO-DATE` 로 보고되지 않고 실제로 돌 때마다 그렇고, 그것은 첫 빌드와
그 모듈에 닿는 변경 뒤의 모든 빌드입니다. 그 실행은 API 를 호출하고 청구됩니다. 그리고 빌드하는 변경과 무관한
이유로 빨개질 수 있습니다 — 더는 유효하지 않은 키는 인증에서(HTTP 401) 실패하고, 아래에 적은 썩음은 프로바이더
쪽에서 실패합니다. 키는 위처럼 그것이 필요한 명령 하나 앞에 붙이거나, 빌드하기 전에 `unset` 하세요.

**`--rerun` 은 선택이 아닙니다.** Gradle 은 환경 변수를 `test` 의 입력으로 치지 않으므로, 이 태스크들이
마지막으로 돈 뒤로 달리 바뀐 것이 없으면 실행은 `UP-TO-DATE` 로 보고되고 아무것도 돌지 않습니다.
키 없이 한 번 돌린 뒤 키를 주고 다시 돌리는 것이 정확히 그 경우입니다 — 1초도 안 걸려 초록이고,
테스트 출력은 한 줄도 없습니다. 출력이 테스트를 `PASSED` 로 나열하는지 확인하세요. `SKIPPED` 는 키가
빠졌다는 뜻이고, `PASSED` 든 `SKIPPED` 든 테스트 줄이 한 줄도 없으면 태스크가 돌지 않은 것입니다.

이 계층은 누군가 마지막으로 돌린 뒤로 썩어 있으리라고 예상하세요. 신호가 없으니 썩음은 뜻밖의 일이 아니라
정상 상태입니다 — 프로바이더가 에러 문구를 바꾸거나, 모델을 은퇴시키거나, 어떤 요청 모양을 거절하기
시작합니다 — 그리고 그것은 다음에 이 계층을 돌리는 사람이 발견합니다. 그게 당신이라면 지나치지 말고
고치거나 이슈를 여세요. 이 계층에 정기 실행이 없는 이유와 무엇이 그것을 바꿀지는
[`docs/backlog/live-api-test-tier.md`](docs/backlog/live-api-test-tier.md) 에 기록되어 있습니다.

### 품질 검사

푸시하기 전에:

```bash
./gradlew format     # Spotless 적용 (Eclipse formatter)
./gradlew checkAll   # checkFormat + checkStyle + 모든 모듈의 단위 테스트
```

`checkAll` 이 유일한 게이트입니다. 포맷 검사, Checkstyle, **그리고** 각 모듈의 `test` 태스크까지
한 번에 돕니다. `./gradlew test` 를 따로 돌릴 필요는 이제 없습니다. Docker/Testcontainers 테스트는
여기서 빠집니다 — `@Tag("docker")` 가 붙어 있고 `./gradlew integrationTest` 로 돕니다.

검사가 실패하면 HTML 리포트가 이유를 말해 줍니다.

```
modules/<module>/build/reports/checkstyle/main.html   # Checkstyle 위반
modules/<module>/build/reports/tests/test/index.html  # 테스트 실패
modules/<module>/build/reports/jacoco/                # 커버리지
```

CI(GitHub Actions)는 모든 PR 에서 `./gradlew checkAll` 을 돌리므로 깨진 빌드는 자동으로 드러납니다. `.github/workflows/build.yml` 을 보세요.

문서에는 `checkAll` 이 다루지 않는 별도의 게이트가 있습니다.

```bash
python3 scripts/check-doc-links.py             # 모든 상대 마크다운 링크의 대상과 앵커
python3 scripts/check-translation-staleness.py # 각 번역본이 정본과 같은 시점인가
python3 scripts/check-translation-structure.py # 각 번역본이 여전히 같은 모양인가
python3 scripts/check-backlog-registers.py     # docs/backlog/: 한 등록부 안의 중복 ID, 본문과 어긋난 건수
```

첫 번째는 저장소의 모든 `*.md` 를 훑으면서 두 가지에 대해 실패합니다. 존재하지 않는 경로를 가리키는 링크,
그리고 대상 파일의 어느 제목과도 맞지 않는 `#fragment` 입니다. 두 번째가 들리는 것보다 중요합니다 —
앵커가 틀려도 페이지는 그대로 열리기 때문에, 독자는 문서 맨 위에 떨어지고서도 자기가 엉뚱한 절로
보내졌다는 사실을 끝내 알지 못합니다. 외부 URL 은 일부러 검사하지 않습니다. 남의 호스트가 죽었다고
빨개지는 게이트는 아무도 읽지 않게 되니까요. CI 는 이것을 `docs-links` 잡으로 돌립니다.

두 번째와 세 번째는 번역에 관한 것이고 `translations` 잡에서 함께 돕니다. 각각 무엇에 실패하고 그중
하나는 왜 대체로 실패하지 않는지는 [번역](#번역) 절에 있습니다. 둘 다 전체 git 이력이 필요하므로 얕은
클론에서는 실패시키는 대신 보고하고 그 사실을 말합니다.

네 번째는 `docs/backlog/` 만 읽고 여러 경우에 실패합니다. 그중 셋은 한 등록부가 항목 ID 를 두 번 쓸 때,
등록부의 제목(`등록 항목 N건 (…)`)이나 `docs/backlog/README.md` 색인의 행이 항목과 어긋날 때, 그리고 항목
ID 로 시작하는데 항목으로 읽히지 않는 제목이 있을 때입니다(소리 없이 건너뛴 제목은 아무도 세지 않는 항목이
됩니다). 이력이 필요 없으므로 CI 는 이것을 `docs-links` 잡의 두 번째 스텝으로 돌립니다. 먼저 검사를 돌려
빨간 스텝이 틀린 제목이나 행을 가리키게 하고, 이어서 `--self-test` 가 트리를 한 번에 한 가지씩 망가뜨려
검사가 여전히 자기가 말하는 것을 읽는지 보입니다. 무엇을 항목과 상태로 읽는지는 스크립트의 docstring 에,
항목을 등록하는 사람이 알아야 할 것은 `docs/backlog/README.md` 규칙 일곱의 끝에 있습니다.

### 문서 사이트 미리보기

`docs/` 는 <https://kangwoo.github.io/aimon-core/> 에 사이트(MkDocs Material)로도 발행됩니다.
자기 변경이 독자에게 어떻게 보일지 확인하려면:

```bash
pip install -r docs-requirements.txt
mkdocs serve            # http://127.0.0.1:8000
mkdocs build --strict   # CI 가 돌리는 것. 경고가 곧 실패다
```

링크를 고치기 전에 이 사이트에 대해 알아 둘 것이 둘 있습니다.

한국어가 사이트의 기본 언어이고 루트에 빌드됩니다. 영어 번역은 `*.en.md` 파일이며 `/en/` 아래로
서빙됩니다. 아직 번역이 없는 페이지는 404 가 *아닙니다* — 그 자리에 한국어 정본이 서빙되므로,
번역이 진행되는 동안에도 사이트는 늘 온전합니다. 번역 규약과 어느 디렉토리가 사이트에 게시되는지는
[`docs/project/documentation-guide.md`](docs/project/documentation-guide.md) 를 보세요.

`docs/` 바깥을 가리키는 링크 — 소스 파일이나 `CHANGELOG.md` — 는 소스에서 **상대 경로**로 둡니다.
GitHub 에서 그 파일을 읽을 때 동작하는 형태가 그것이기 때문입니다. 사이트를 빌드할 때 빌드 훅
(`scripts/mkdocs_github_links.py`)이 그런 링크만 GitHub URL 로 바꿔 줍니다. 그러니 GitHub 에서
동작하는 링크를 쓰고 사이트 쪽은 훅에 맡기면 됩니다.

### IDE 설정

일관된 포맷을 위해 Eclipse formatter 를 IDE 에 가져옵니다.

- **설정 파일:** `config/eclipse/eclipse-formatter.xml`
- **import 순서:** `java` → `javax` → `jakarta` → `org` → `com` → (빈 줄) → 프로젝트 import

## 프로젝트 구조

```
modules/
├── aimon-bom                    # java-platform BOM: 모든 모듈의 버전
├── aimon-core                   # 프레임워크 코어: 에이전트, 도구, 스킬, 훅, 스케줄링, VFS
├── aimon-bootstrap              # 프레임워크 중립 어셈블리: AimonStack + 순서 있는 teardown
├── aimon-spring-boot-starter    # aimon-bootstrap 위의 Spring Boot 자동 설정
├── aimon-cli                    # 레퍼런스 REPL 애플리케이션
│
├── aimon-llm-openai             # OpenAI LlmClient
├── aimon-llm-anthropic          # Anthropic LlmClient
├── aimon-llm-capability-testkit # 공유 모델 capability 바인딩 계약 테스트
│
├── aimon-filesystem-gridfs      # MongoDB GridFS VFS
├── aimon-filesystem-s3          # AWS S3 VFS
├── aimon-filesystem-testkit     # 공유 VirtualFileSystem 계약 테스트
│
├── aimon-sandbox                # 샌드박스 추상화
├── aimon-sandbox-docker         # Docker 백엔드
├── aimon-sandbox-kubernetes     # Kubernetes 백엔드
│
├── aimon-session-routing        # 멀티 노드 세션 라우팅 (SPI 는 aimon-core 에)
├── aimon-session-testkit        # 공유 멀티 노드 세션 계약 테스트
├── aimon-session-redis          # Redis 세션 저장소
├── aimon-session-postgres       # PostgreSQL 세션 저장소
├── aimon-session-mongodb        # MongoDB 세션 저장소
│
├── aimon-memory-testkit         # 공유 다섯 티어 PeerMemory 계약 스위트 (배포됨)
│
├── aimon-knowledge-opensearch   # OpenSearch 지식 저장소
├── aimon-scheduling-quartz      # 분산 cron 스케줄러
├── aimon-workflow-graaljs       # GraalJS 스크립트 기반 서브에이전트 워크플로
├── aimon-rewake-webhook         # HMAC 검증 HTTP 엔드포인트로 rewake 발화
└── aimon-browser-playwright     # Playwright 브라우저 자동화

samples/
├── aimon-sample-app             # 최소 임베딩 예제
├── aimon-sample-skills-alpha    # 예제 스킬 번들
└── aimon-sample-skills-beta     # 예제 스킬 번들
```

새 JVM 모듈은 `buildSrc/src/main/kotlin/` 아래의 미리 컴파일된 스크립트 플러그인으로 공유 빌드
설정에 참여합니다 — 모든 모듈에 `aimon.java-conventions`, Maven Central 에 배포되는 모듈에는
`aimon.publishable` 을 더합니다.

새 기능을 추가할 때는 새 모듈을 만들기보다 기존 모듈을 확장하는 쪽을 우선하세요. 새 모듈은 먼저 이슈에서 논의하는 것이 좋습니다.

## 코딩 규약

### 언어와 스타일

- **Java 17** — 패턴 매칭, `record`(아껴서 — 아래 참조), 가독성이 나아지는 자리의 `var` 를 자유롭게 씁니다
- **`record` 보다 `class` 를 선호** — 도메인 객체는 빌더 패턴을 쓰는 불변 클래스입니다. 정본 예시는 `at.aimon.core.agent.AgentContent` 입니다.
- **널 안전성** — 생성자·메서드 진입점에서 `Objects.requireNonNull`, 널이 될 수 있는 반환은 `Optional<T>`, 명확해지는 자리에는 JetBrains `@Nullable` / `@NotNull` 을 붙입니다.
- **`Tool#execute` 에서 예외를 던지지 않습니다** — 대신 `ToolResult.error(...)` 를 반환합니다. [tool-development-guide.md](docs/features/tool/tool-development-guide.md) 를 보세요.

### 확장점

대부분의 기여는 네 확장점 중 하나에 꽂히며, 각각 전용 가이드가 있습니다.

| 확장점 | 가이드 |
|-----------------|-------|
| **Tool** — 에이전트가 바깥 세계에 작용하는 방법 | [tool-development-guide.md](docs/features/tool/tool-development-guide.md) |
| **Hook** — 라이프사이클 개입 (`PreTool`, `PostTool`, `OnStop`, …) | [hook-development-guide.md](docs/features/hook/hook-development-guide.md) |
| **LLM provider** — 새 `LlmClient` 구현 | [llm-provider-development-guide.md](docs/features/llm/llm-provider-development-guide.md) |
| **Skill** — 프롬프트·도구·훅을 묶은 선언적 번들 | [agentskills-specification.md](docs/references/agentskills-specification.md) + [aimon-skill-extensions.md](docs/references/aimon-skill-extensions.md) |

새 타입 이름을 정하기 전에 [scope-model.md](docs/overview/scope-model.md) 와
[glossary.md](docs/overview/glossary.md) 를 읽으세요. 컴포넌트 수명 규칙(Application / Agent /
Session / Live session)과 `turn` · `iteration` · `execution` 의 구분은 ArchUnit 테스트가 강제하므로,
그것과 어긋나는 이름은 리뷰가 아니라 빌드에서 먼저 걸립니다.

### SOLID

[SOLID 원칙](docs/project/solid-principles.md)을 따릅니다.

- 클래스 하나에 책임 하나
- 기존 코드를 고치는 것이 아니라 인터페이스로 확장
- 하위 타입은 상위 타입의 계약을 지킬 것
- 작고 집중된 인터페이스
- 추상화에 의존하고 생성자로 주입

### 테스트 작성

- 기본은 **JUnit 5 + AssertJ + Mockito** 이고, 통합 테스트는 **Testcontainers** 로 하되
  `@Tag("docker")` 를 붙여 `checkAll` 밖에 둡니다
- 테스트 클래스 이름은 `<ClassUnderTest>Test`, 동작 하나당 테스트 하나
- 관련 케이스는 `@Nested` 클래스로 묶고 모든 테스트에 `@DisplayName` 을 답니다. 이 스위트는 둘 다
  적극적으로 쓰며, 표시 이름은 주변 주석이 한국어인 자리에서도 **영어**로 씁니다 — 실패한 CI 가
  찍어 내는 것이 그것이기 때문입니다
- 메서드 이름 스타일은 둘이 공존합니다. 서술형 camelCase(`shouldReturnErrorWhenPathIsMissing`)가
  다수이고, 옛 `method_Condition_ExpectedResult` 형식이 남아 있습니다. 변환하지 말고 **지금 고치는
  파일에 맞추세요** — 이름만 바꾼 diff 는 정작 변경을 리뷰에서 파묻습니다
- 테스트는 Checkstyle 을 지킬 필요는 없지만 포맷은 맞춰야 합니다 (`./gradlew format`)
- 새 코드에는 의미 있는 커버리지를 목표로 합니다 — JaCoCo 리포트는 `modules/<module>/build/reports/jacoco/` 에 있습니다

대표적인 테스트 클래스는 이렇게 생겼습니다.

```java
class ExampleToolTest {

    @TempDir
    Path tempDir;

    private ExampleTool tool;

    @BeforeEach
    void setUp() {
        tool = new ExampleTool(/* 의존성 */);
    }

    @Test
    @DisplayName("rejects a null dependency at construction")
    void shouldRejectNullDependency() {
        assertThatThrownBy(() -> new ExampleTool(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("cannot be null");
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("returns the rendered content for valid input")
        void shouldReturnContentForValidInput() {
            ToolResult result = tool.execute(ToolInput.of("param", "value"), ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("expected output");
        }

        @Test
        @DisplayName("reports a missing required parameter as an error, not an exception")
        void shouldReturnErrorWhenRequiredParameterMissing() {
            ToolResult result = tool.execute(ToolInput.of(), ToolContext.empty());

            assertThat(result.isError()).isTrue();
        }
    }
}
```

마지막 케이스는 구색 맞추기가 아닙니다. `Tool#execute` 는 계약상 예외를 던지는 것이 금지되어 있으므로,
에러를 반환한다는 것을 증명하는 그 테스트가 곧 계약을 지키는 장치입니다.

### 문서화

- 공개 API 에는 Javadoc 이 필요합니다
- 사용자에게 보이는 문자열(예외 메시지, 로그 메시지)은 **영어**로 씁니다
- 내부 주석은 영어와 한국어 모두 가능하지만, 새 파일은 전 세계 기여자가 접근할 수 있도록 영어를 우선합니다
- 사용자에게 보이는 동작을 바꿨다면 `docs/` 의 관련 가이드도 갱신합니다
- 문서 사이의 링크는 **상대 경로**로 걸고, `#anchor` 는 실제로 존재하는 제목을 가리키게 합니다 —
  `scripts/check-doc-links.py` 가 둘 다 검사합니다
- 제목 앵커는 GitHub 과 문서 사이트에서 똑같이 생성되므로, 검사기가 통과시킨 앵커는 양쪽에서
  동작합니다. `.` 은 하이픈을 남기지 않고 사라진다는 점에 주의하세요 — `AimonCli.call()` 이라는
  제목에는 `#aimonclicall` 로 닿습니다

### 번역

문서는 두 언어로 제공되며, 어느 쪽이 원본인지는 **파일이 어디에 있는지**에 따라 다릅니다.
`docs/` 아래에서는 **한국어가 정본**이고 영어 번역이 `*.en.md` 입니다. 저장소 루트에서는 방향이
반대입니다 — `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md` 는 영어로 쓰고
`*.ko.md` 로 번역합니다. 양쪽에서 공통으로 성립하는 규칙은 하나입니다: **언어 접미사가 붙은 파일이
번역본**입니다.

모든 번역본은 앞머리(front matter)에 그 사실과, 어느 시점의 정본에서 옮겼는지를 적습니다.

```yaml
---
translated_from: docs/features/hook/hook-config-guide.md
source_commit: eec9ccd
---
```

`source_commit` 은 이번 수정 **직전**의 정본 커밋입니다 — 지금 만들려는 커밋의 SHA 를 미리 적을 수는
없기 때문입니다. `scripts/check-translation-staleness.py` 는 이 사실을 알고 있어서 정본과 번역본을
함께 건드린 커밋은 건너뜁니다. 따라서 한 커밋만큼의 지연은 낡음으로 보고되지 않습니다.

**정본을 고쳤다면 같은 PR 에서 번역본도 함께 고치세요.** 그럴 수 없다면 — 다른 언어에 자신이 없거나
변경이 크다면 — PR 에 그렇게 적고 이슈를 남기세요. 번역 때문에 정본 수정을 붙잡아 두면 안 됩니다.
번역이 일주일 뒤처지는 것은 문서가 양쪽 언어 모두에서 틀린 것보다 작은 문제입니다. CI 가 낡은 번역을
경고로만 보고하고 절대 실패시키지 않는 이유가 바로 이것입니다. 구조 검사도 그것을 따릅니다 —
불일치가 빌드를 실패시키는 것은 **쌍이 같은 시점일 때뿐**입니다. 그때가 시작하지도 않은 번역이 아니라
하던 번역을 끝내라는 요구를 받는 경우이기 때문입니다.

번역이 어떤 축에서 정당하게 어긋날 수밖에 없다면, 검사와 다투는 대신 그 파일에 선언합니다.

```yaml
structure_exempt: list-items
structure_exempt_reason: "정본의 3항 목록이 영어에서는 관용적으로 2항이 된다"
```

두 줄 모두 필수이고 두 표기 모두 강제됩니다. 쉼표 문자열과 큰따옴표는 문체가 아닙니다 — 같은
frontmatter 를 mkdocs 는 YAML 파서로, 스크립트는 줄 정규식으로 읽는데, YAML 목록이나 콜론·백틱이
들어간 따옴표 없는 이유는 두 파서를 갈라놓습니다. 최악의 경우 mkdocs 가 메타데이터를 조용히 버리고
그것을 본문으로 발행합니다. 예외는 만료되기도 합니다 — 축이 다시 일치하면 검사가 지우라고 합니다.

**다만 CI 가 해석하지 못하는 `source_commit` 은 빌드를 실패시킵니다.** 이것은 다른 종류의 발견입니다.
낡음은 검사가 돌았고 그 답이 나쁘다는 뜻이지만, 해석 불가는 **답이 없다**는 뜻입니다 — 그 파일이 최신인지
1년 뒤처졌는지 말해 주지 못합니다. 여기서 실패시키는 것은 번역을 건너뛰라는 압력이 되지 않습니다.
존재하는 SHA 를 적으라는 것이고, 그것은 한 줄입니다. 히스토리 재작성(스쿼시·리베이스)으로 적어 둔 SHA 가
사라졌다면, 정본이 **번역한 그 상태로** 담겨 있는 가장 오래된 커밋을 `source_commit` 으로 겨눕니다 —
그리고 적기 전에 정말 그 상태인지 확인합니다. 내용이 어긋난 번역에 붙은 해석 가능한 SHA 는 해석
불가보다 나쁩니다. 그것은 초록으로 보고합니다.

번역을 쓸 때는 이렇게 합니다.

- **구조를 정확히 맞춥니다.** 제목 개수, 표의 행 수, 코드 블록이 같아야 합니다. 단어가 하나도 겹치지
  않더라도 두 파일의 **모양**은 깔끔하게 대응해야 합니다. 이 항목은
  `scripts/check-translation-structure.py` 가 검사하며, **다시 접어도 살아남는 것**을 셉니다 —
  제목과 그 레벨, 펜스와 그 언어, 표별 행 수, 리스트 항목과 그 중첩, 그리고 인용 **블록**입니다.
  줄 수는 어느 방향으로도 비교할 수 없습니다 — 한글은 칸당 정보가 많아 같은 문단이 영어에서 더
  많은 줄로 접힙니다
- **제목을 번역하면 앵커가 바뀝니다.** 문서 안의 `#링크` 를 다시 겨누고 `scripts/check-doc-links.py`
  를 돌리세요
- **식별자는 건드리지 않습니다** — 타입·패키지 이름, 파일 경로, 설정 키, CLI 명령과 플래그, 애노테이션,
  enum 상수, 그리고 의도적으로 동결된 와이어 이름(`conversationId`, `conversation_locks`). 반면 코드
  블록 **안의 주석**은 산문이므로 번역합니다
- **ASCII 다이어그램은 고치지 말고 다시 그립니다.** 한글은 두 칸 폭이라, 상자 가운데에 한국어를
  끼워 넣으면 편집기에서는 멀쩡해 보여도 정렬이 깨집니다
- **정본이 말하는 것을 옮기지, 말했어야 하는 것을 옮기지 않습니다.** 정본이 낡았다면 정본을 별도
  커밋으로 고치세요 — 번역본이 원본을 조용히 고쳐 두면 두 문서가 어긋난 채로 그 이유가 어디에도
  남지 않습니다

되풀이되는 용어는 [`docs/project/translation-glossary.md`](docs/project/translation-glossary.md)
가 구속력 있는 기준이며, 특히 전에 문제를 일으켰던 `turn` / `iteration` / `execution` 을 정리해
두었습니다. 문서 규칙 전체 — 새 문서를 어디에 둘지, `design/` 과 `plan/` 이 어떻게 다른지, 어느
디렉토리가 사이트에 게시되는지 — 는 [`docs/project/documentation-guide.md`](docs/project/documentation-guide.md)
에 있습니다.

## 커밋 메시지

가벼운 [Conventional Commits](https://www.conventionalcommits.org/) 스타일을 따릅니다.

```
<type>(<scope>): <short summary>

<optional body explaining the WHY, wrapped at ~72 cols>

<optional footer: BREAKING CHANGE:, Refs:, Co-authored-by:>
```

**타입:** `feat`, `fix`, `refactor`, `docs`, `test`, `chore`, `perf`, `build`, `ci`

**스코프:** 모듈 약칭(`core`, `cli`, `llm-openai`, `session`, `sandbox` 등) 또는 기능 영역.

예시:

```
feat(session): add per-turn SubmitOptions to AgentSession
fix(llm-usage): preserve native streaming through MeteringLlmClient
refactor(session-mongodb): normalize nested maps for cross-backend uniformity
docs(tool): clarify error-handling pattern in tool-development-guide
```

커밋은 하나의 논리적 변경으로 좁게 유지하세요. PR 을 열기 전에 작업 중 커밋은 스쿼시합니다.

## 브랜치와 Pull Request

### 브랜치 이름

- `feat/<short-description>`
- `fix/<short-description>`
- `docs/<short-description>`
- `chore/<short-description>`

### 작업 흐름

1. 저장소를 포크합니다 (쓰기 권한이 있다면 토픽 브랜치를 만듭니다)
2. `main` 에서 브랜치를 땁니다
3. DCO 사인오프를 붙여 좁은 커밋을 만듭니다 (아래 참조)
4. 푸시하고 `main` 을 향해 Pull Request 를 엽니다
5. PR 템플릿을 채우고, 관련 이슈를 `Fixes #123` / `Refs #123` 으로 연결합니다
6. CI 가 녹색인지 확인합니다
7. 리뷰 피드백은 추가 커밋으로 반영합니다 (요청받지 않는 한 리뷰 중 force-push 는 하지 않습니다)
8. 메인테이너가 상황에 맞게 스쿼시 머지하거나 머지합니다

### PR 체크리스트

- [ ] 로컬에서 `./gradlew checkAll` 통과 (포맷 + checkstyle + 단위 테스트)
- [ ] 마크다운을 고쳤다면 `python3 scripts/check-doc-links.py` 통과
- [ ] `docs/` 나 `mkdocs.yml` 을 고쳤다면 `mkdocs build --strict` 통과
- [ ] 새 코드에 테스트가 있음
- [ ] 공개 API 변경에 Javadoc 이 있음
- [ ] 사용자에게 보이는 변경이 `docs/` 와 `CHANGELOG.md`(Unreleased 절)에 반영됨
- [ ] 커밋에 사인오프가 되어 있음 (DCO)

## 개발자 원본 증명서 (DCO)

이 프로젝트에 기여한다는 것은, 그 패치를 본인이 작성했거나 프로젝트 라이선스로 제출할 권리를 달리 가지고 있음을 증명하는 것입니다 — [DCO 1.1 원문](https://developercertificate.org/)을 보세요.

동의를 표시하려면 모든 커밋에 사인오프를 합니다.

```bash
git commit -s -m "feat(core): add cool new thing"
```

이렇게 하면 git 설정의 `user.name` / `user.email` 을 써서 `Signed-off-by: Your Name <you@example.com>` 트레일러가 붙습니다. 둘은 한 번만 설정하면 됩니다.

```bash
git config user.name "Your Name"
git config user.email "you@example.com"
```

사인오프가 빠진 PR 은 amend 를 요청받습니다. 여러 커밋에서 빠뜨렸다면:

```bash
git rebase --signoff main
git push --force-with-lease
```

## 보안 문제 신고

보안 취약점은 공개 이슈로 **열지 마세요**. 비공개 제보 경로는 [SECURITY.md](SECURITY.md) 에 있습니다.

---

기여해 주셔서 다시 한번 고맙습니다. 이 문서에서 불분명한 부분이 있다면 Discussion 이나 PR 을 열어 주세요 — 여러분이 짐작하게 두는 것보다 문서를 고치는 편이 낫습니다.
