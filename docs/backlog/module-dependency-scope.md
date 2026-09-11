# 백엔드 모듈의 POM 스코프와 테스트 클래스패스 버전 — 등록 항목 3건 (열림 3 · 결정 대기)

`.claude/rules/code-style.md` 와 `.claude/rules/architecture.md` 가 같은 규칙을 두 번 적고 있다 —
**implementation 모듈은 `implementation(project(":aimon-core"))` 를 쓰고, 파사드만 `api()` 를 쓴다.**
이 문서는 그 규칙을 뒤집자는 것이 아니라, 규칙이 감수하기로 한 **비용이 어디에 남아 있는지**를
기록한다.

출처는 2026-08-31 의 아키텍처 리뷰다. §2 는 그 이웃 축 — 모듈이 발행하는 **버전**과 그 모듈의 테스트가
도는 버전 — 을 #99 (2026-09-11) 에서 받는다.

---

## 0. 착수하며 정정한 것 — 리뷰가 이 규칙을 모르고 17개 모듈을 바꿨다

리뷰는 이 자리를 **결함**으로 진단하고 발행 모듈 17개를 `api` 로 바꿨다. 그 다음에
`.claude/rules/code-style.md` 를 읽었고, 되돌렸다.

**규칙을 몰랐던 것이 아니라 찾지 않았다.** 그리고 규칙은 리뷰가 발견했다고 여긴 것을 이미
알고 있었다 — 규칙 본문이 스스로 *"Gradle 의 `implementation` 은 POM 에 `<scope>runtime</scope>`
으로 나가므로 소비자 앱은 jar 는 받지만 `Agent` / `LiveSession` / `Tool` 에 **컴파일할 수 없다**"*
라고 적고, 그럼에도 파사드가 아닌 모듈은 `implementation` 을 유지한다고 정한다.

즉 리뷰가 "발견" 한 메커니즘은 **결정문 안에 이미 인용되어 있었다.** 이것이 백로그
`README.md` 규칙 다섯의 마지막 문단이 말하는 것과 같은 모양이다 — *"처방이 '이 값을 바꿔라'
형태일 때는, 고치기 전에 그 값이 지금 왜 그 값인지 설명이 있는지 먼저 찾는다. 설명이 있으면
그것은 결함이 아니라 결정이고, 항목이 반박해야 할 대상은 코드가 아니라 그 설명이다."*

그 문장이 겨눈 곳은 코드 주석이었는데, 이 건에서 설명이 있던 곳은 **`.claude/rules/`** 였다.
그래서 규칙 다섯에 자리가 하나 더 붙는다: 값이 왜 그 값인지 찾을 때 보는 곳은 코드 주석과
`docs/` 만이 아니다.

**남긴 것** — 그 실수를 잡는 가드를 대신 넣었다
(`PublishedModuleApiScopeTest`, `modules/aimon-core/src/test/java/at/aimon/core/architecture/`).
비-파사드가 `api` 를 쓰면 실패하고, `FACADE_MODULES` 에 이름이 있는데 실제로는 파사드가 아니면
그것도 실패한다. **틀린 처방을 되돌리는 것으로 끝내지 않고 다음 사람이 같은 자리에서 같은
결론에 이르는 것을 막는 장치를 남긴다**(규칙 둘).

---

## 1. 열린 항목

### D-1 — 백엔드 모듈의 POM 이 말하는 것과 그 모듈의 공개 API 가 다르다 · **열림 · 결정 대기**

**무엇** — 발행 모듈 17개가 core 타입을 공개 시그니처에 노출하면서 core 를 runtime 스코프로
발행한다. 이대로 둘지, 축을 바꿀지 결정한다.

**왜 — 관측 가능한 결과**

측정(2026-08-31): `implementation(project(":aimon-core"))` 를 선언한 발행 모듈 **17개 전부**가
core 타입을 공개 상속/구현 또는 공개 메서드 시그니처에 노출한다.

| 모듈 | 예 |
|------|-----|
| `aimon-session-redis` | `RedisSessionRecordStore implements SessionRecordStore`, `provision(SessionId): SessionRecordView` |
| `aimon-filesystem-s3` | `S3FileSystem implements VirtualFileSystem` |
| `aimon-llm-openai` | `OpenAILlmClient implements LlmClient` |
| `aimon-rewake-webhook` | 공개 API 의 core 타입 2종 (가장 적은 모듈) |

`implementation` 은 POM 에 `<scope>runtime</scope>` 로 나가므로, `aimon-session-redis` 만
선언한 소비자는 자기가 방금 만든 객체의 타입 이름을 **쓸 수 없다.**

**그런데 이것이 오늘 누군가를 물고 있지는 않다.** `README.md:114` 이 *"Add `aimon-core` and at
least one LLM provider"* 라고 안내하고 예제가 둘 다 선언하며, BOM 이 버전을 맞춘다. 문서화된
경로는 동작한다. 남는 것은 **POM 이 사실이 아닌 말을 한다**는 것뿐이고, 규칙은 그 대가로
전이 표면을 좁게 유지하는 쪽을 골랐다.

**결정에 필요한 것** (규칙 넷 — 결정문 전에 전제를 소스로 확인한다)

| 물어야 할 것 | 지금 아는 것 |
|-------------|-------------|
| "전이 누출을 막는다" 가 실제로 무엇을 막고 있나 | **미확인.** 소비자가 core 를 어차피 선언하므로, 좁아지는 것이 컴파일 클래스패스인지 아무것도 아닌지 실측되지 않았다 |
| 저장소 밖 소비자가 실제로 어떻게 선언하나 | **표본 0.** `roadmap.md` §3 — 밖에서 온 백엔드 구현 0건 |
| 규칙을 뒤집으면 무엇이 깨지나 | 깨지지 않는다. `runtime` → `compile` 은 **넓히는** 방향이라 기존 소비자에게 호환된다 |

가운데 줄이 이 항목이 **결정 대기**인 이유다. 세 번째 줄이 "비용이 낮다" 를 말하지만, 낮은
비용은 착수 근거가 아니다 — 첫 줄의 답을 모르는 채 바꾸면 **규칙이 지키려던 것이 무엇이었는지
모르는 채로** 지우게 된다.

**언제 다시 볼까** — 셋 중 하나.
- **저장소 밖에서 온 첫 백엔드 구현**이 생길 때 (`api-stability.md` §6 의 `1.0` 진입 조건이기도
  하다). 그 사람이 어떻게 선언하는지가 첫 줄의 실측이다
- 소비자가 "`aimon-session-redis` 만 넣었더니 컴파일이 안 된다" 를 보고할 때 — 그때는 결정이
  아니라 결함이다
- `aimon-bootstrap` · `aimon-spring-boot-starter` 말고 **세 번째 파사드**가 생길 때. 파사드가
  늘어난다는 것은 "재수출이 정상 경로" 라는 뜻이고 규칙의 전제가 흔들린다

**여기서 하지 않기로 한 것** — 리뷰가 한 번 한 것(17개를 `api` 로)을 다시 하지 않는다.
되돌린 이유가 §0 이고, 다시 하려면 이 항목의 첫 줄에 답이 있어야 한다.

---

## 2. 테스트 클래스패스의 버전 — #99 가 남긴 것

§1 과 이웃한 축이다. §1 은 모듈이 **발행하는 스코프**가 그 모듈의 공개 API 와 어긋나는 자리이고, 이 절은
모듈이 **발행하는 버전**과 그 모듈의 **테스트가 도는 버전**이 어긋나는 자리다.

기준은 #91 이 세웠다 — *모듈의 테스트는 그 모듈이 발행하는 버전 위에서 돈다. 그 차이가 의도해서 기록한
선택이 아니라면.* #95 가 테스트킷의 Spring Boot 플랫폼이 만든 차이 20건을 없앴고, #99 가 남은 아홉을
출처별로 결정했다. `spring-boot-starter-test` 가 `aimon-cli` 에서 올린 셋은 **맞췄고**
(`modules/aimon-cli/build.gradle.kts`), Testcontainers 의 `org.jetbrains:annotations` 와 프로바이더 SDK 의
`error_prone_annotations` 는 이유를 적고 **받아들였다**. 결정문은 `gradle/libs.versions.toml` 의 `junit` 노트
바로 위에 있고, 근거와 기각한 대안은
[`../design/testing/test-classpath-shipped-versions.md`](../design/testing/test-classpath-shipped-versions.md) 에 있다.

아래 둘은 그 결정이 닿지 않은 것이다. 측정은 전부 2026-09-11, `main` `9b642cc` 에서 `runtimeClasspath` 와
`testRuntimeClasspath` 를 비교한 것이다. D-2 의 표는 #114·#120 에서 `c561e17` 과 그 변경 뒤에 다시 쟀고, 그 결정과
측정은 [`../design/testing/shipped-logback-and-test-classpath-followups.md`](../design/testing/shipped-logback-and-test-classpath-followups.md)
에 있다.

### D-2 — `spring-boot-starter-test` 가 #99 가 결정하지 않은 모듈에서 발행 버전을 테스트 아래 올린다 · **열림 · 결정 대기**

**무엇** — 아래 두 모듈의 차이를 `aimon-cli` 처럼 맞출지(두 테스트 클래스패스를 `runtimeClasspath` 와 일관되게
해석), 이유를 적고 받아들일지 모듈별로 결정한다.

**왜 — 관측 가능한 결과**

| 모듈 | 아티팩트 | 발행 → 테스트 |
|------|---------|--------------|
| `aimon-scheduling-quartz` | `jakarta.xml.bind:jakarta.xml.bind-api` | 4.0.4 → 4.0.5 |
| `aimon-knowledge-opensearch` | `jakarta.annotation:jakarta.annotation-api` | 1.3.5 → 2.1.1 |

둘 다 #99 가 `aimon-cli` 에서 맞춘 것과 **같은 출처**다. #99 가 받아들인 두 출처와 이 둘을 가르는 것은 jar 의 종류가
아니라 — `jakarta.annotation-api` 도 주석 jar 다 — 테스트 실행에 닿는 방식이다. `jakarta.xml.bind-api` 는 코드를 담고,
`jakarta.annotation-api` 는 프레임워크가 실행 중에 읽는 RUNTIME-retention 주석을 담으며 1.3.5 와 2.1.1 사이에 패키지가
`javax` 에서 `jakarta` 로 바뀐다.

- `jakarta.xml.bind-api` 는 클래스 108개 가운데 78개가 주석이 아닌 코드다(`JAXBContext` · `ContextFinder` ·
  `DatatypeConverter` …). `aimon-cli` 에서 맞춘 이유 중 하나가 그대로 걸린다.
- `jakarta.annotation-api` 는 버전 차이보다 나쁘다. 1.3.5 는 `javax.annotation.*` 패키지이고 2.1.1 은
  `jakarta.annotation.*` 이므로, **발행되는 클래스가 테스트 클래스패스에는 아예 없다.** 2.1.1 의 주석 타입 16개
  가운데 15개가 RUNTIME-retention 이다(`@PostConstruct` · `@PreDestroy` · `@Resource` 처럼 컨테이너가 실행 중에 읽는
  것들). 그 모듈의 테스트 경로가 그 클래스를 필요로 하는지는 실측하지 않았다.
- `aimon-session-testkit` 의 Logback 쌍(1.5.13 → 1.5.34)은 이 표에서 빠졌다(#114, 2026-09-11). 맞춘 것이 아니다 —
  카탈로그 `logback` 이 1.6.3 으로 올라 `spring-boot-starter-test` 가 가져오는 1.5.34 보다 높아졌고, 이제 두
  클래스패스가 모두 1.6.3 을 해석한다(`dependencyInsight`). 이 모듈의 빌드 스크립트는 그대로이므로, Spring Boot 가
  카탈로그보다 높은 Logback 을 관리하게 되면 차이는 다시 생긴다.

**어디** — `modules/aimon-scheduling-quartz/build.gradle.kts`, `modules/aimon-knowledge-opensearch/build.gradle.kts`
(2026-09-11, `c561e17`). 맞추는 쪽을 고른다면 선례는 `modules/aimon-cli/build.gradle.kts` 의
`configurations { … shouldResolveConsistentlyWith(…) }` 블록이다.

**언제 다시 볼까**
- 이 두 빌드 스크립트 중 하나를 다음에 고칠 때
- `aimon-scheduling-quartz` 나 `aimon-knowledge-opensearch` 의 테스트가 JAXB 나 `javax.annotation` 클래스에서
  실패할 때
- D-3 의 검사를 만들 때 — 그 검사는 이 둘을 목록에 올리거나 없애라고 먼저 요구한다
- `spring-boot` 올림이 카탈로그 `logback` 보다 높은 Logback 을 가져올 때 — `aimon-session-testkit` 의 쌍이 이 표로
  돌아온다

### D-3 — 테스트와 발행 버전의 차이를 적은 기록을 아무것도 검사하지 않는다 · **열림 · 결정 대기**

**무엇** — 모듈의 `runtimeClasspath` 와 `testRuntimeClasspath`(원한다면 `testCompileClasspath` 도)의 버전이
기록된 목록 밖에서 어긋나면 실패하는 검사를 둘지 결정한다.

**왜 — 관측 가능한 결과**
- 기록이 산문이다. Spring Boot · Testcontainers · SDK 를 올리거나 테스트 라이브러리를 하나 더하면 차이가
  생기거나 사라지는데, 그것을 알리는 것이 없다. `gradle/libs.versions.toml` 의 결정문은 그 날짜의 측정이고,
  다음 버전 올림이 그것을 조용히 틀리게 만든다.
- 지금까지 차이는 **읽어서** 찾았고, 읽은 범위가 결과를 정했다. #95 는 테스트킷의 소비자 일곱만 셌고, 빌드
  전체를 센 것은 #99 의 설계가 처음이다 — 그래서 D-2 가 나왔다.
- 컴파일 축은 통째로 기록되지 않았다. #95 와 #99 는 런타임과 테스트 런타임만 비교했다. `aimon-cli` 의 방법을
  `aimon.java-conventions` 로 옮기는 실험에서는 23개 프로젝트의 항목 94개가 바뀌었고, 그중 72개가
  `testCompileClasspath` 였다.

**어디** — 아직 코드가 없다(2026-09-11). #99 의 설계가 쓴 프로브는 저장소에 들어오지 않았고, 핵심은 이것뿐이다:
프로젝트마다 `runtimeClasspath` 와 `testRuntimeClasspath` 의 `incoming.resolutionResult.allComponents` 에서
`ModuleComponentIdentifier` 인 것만 골라 `group:name` 으로 맞대고, 버전이 다른 쌍을 낸다. 검사로 만든다면 자리는
`buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` 가 모듈마다 등록하고 루트의 `checkAll` 이 모으는
태스크이고, 받아들인 목록은 산문 노트가 아니라 그 태스크가 읽을 수 있는 곳에 있어야 한다.

**언제 다시 볼까**
- 다음 차이가 검사가 아니라 읽기로 발견될 때
- 다음 Spring Boot 또는 Testcontainers 버전 올림
- D-2 를 결정할 때 — 받아들이는 쪽을 고르면 산문 목록이 한 번 더 늘어난다
- Gradle 을 올릴 때 — `aimon-cli` 가 테스트 클래스패스를 맞추는 `shouldResolveConsistentlyWith` 는 `@Incubating` 이다
  (#120). 없어지면 모든 빌드가 설정 단계에서 멈춘다. 동작만 바뀌면 아무것도 실패하지 않은 채 그 모듈의 테스트가 다시
  `spring-boot-starter-test` 가 올린 버전 위에서 돌 수 있다. 그것을 알아챌 것이 이 검사다.

---

## 3. 관련

- `.claude/rules/code-style.md` · `.claude/rules/architecture.md` — 규칙 원문
- `modules/aimon-filesystem-testkit/build.gradle.kts` — 규칙이 **왜** 그런지 산문으로 적힌 유일한
  빌드 파일 (*"to keep a published POM honest"*)
- `gradle/libs.versions.toml` 의 `junit` 노트와 그 바로 위 블록 — §2 의 결정문
- `modules/aimon-cli/build.gradle.kts` — 테스트 클래스패스를 발행 버전에 맞춘 모듈과 그 방법
- [`../design/testing/test-classpath-shipped-versions.md`](../design/testing/test-classpath-shipped-versions.md) — §2 의 설계 근거와 기각한 대안
- [`multi-instance-readiness.md`](multi-instance-readiness.md) — 같은 리뷰에서 나온 다른 항목
- [`../project/api-stability.md`](../project/api-stability.md) — `0.x` 가 약속하는 것
