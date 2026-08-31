# 아키텍처 리뷰가 남긴 나머지 항목 — 등록 항목 6건 (열림 4 · 닫힘 2)

2026-08-31 의 아키텍처 리뷰가 일곱 단계를 처리하고 남긴 것들이다. 같은 리뷰에서 나온 두 축은 이미
자기 문서를 가졌고([`multi-instance-readiness.md`](multi-instance-readiness.md) ·
[`module-dependency-scope.md`](module-dependency-scope.md)), **이 문서는 그 둘에 들어가지 않는 나머지**
— 검증 계층과 구조 부채 — 를 담는다.

계획 문서(`docs/plan/architecture-review-followups.md`)는 이 등록과 함께 삭제됐다. `docs/README.md` 의
규칙대로다 — 진행 추적 문서는 끝나면 지우고, 근거는 `design/` · `project/` 로, 열린 항목은 여기로.
아래에서 리뷰의 단계를 가리킬 때는 절 번호가 아니라 **한 것의 이름**으로 부른다(*게이트 편입* ·
*미사용 의존성 제거* · *사이클 베이스라인* · *뺀 것 표*). 지워진 문서의 절 번호는 아무도 되짚을 수
없고, 무엇을 했는지는 [`../../CHANGELOG.md`](../../CHANGELOG.md) 의 `[Unreleased]` 와 `a4d018a4`
앞뒤의 커밋에 남아 있다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 드리프트하므로 그 날짜 이후의 인용은 다시 세어야 한다.

---

## 0. 등록하며 정정한 것 — **미룬 이유 셋이 틀렸다**

계획은 마지막 절에서 여덟 줄짜리 표로 "왜 이번 범위에서 뺐나" 를 적었고, 그 표는 아무도 다시 검증하지 않은 채
백로그로 넘어올 참이었다. [`README.md`](README.md) 규칙 일곱이 정확히 그것을 막는다 — **분류는 표가
아니라 항목 본문에서 읽는다.** 여섯 줄을 소스로 다시 확인했더니 셋이 틀렸다.

### 0.1 `aimon-knowledge-opensearch` — 미룬 조건이 **영원히 오지 않는 조건**이었다

계획은 이렇게 적었다: *"전이 필요분일 수 있다. [게이트 편입]이 끝나야 판단 가능"* — 즉
`integrationTest` 가 CI·릴리스 게이트에 들어가면 그 모듈의 통합 테스트가 돌면서 답이 나온다는
뜻이었다.

**그 모듈에는 `@Tag("docker")` 테스트가 0건이다.** 단위 테스트 5개뿐이고 Testcontainers 를 쓰는 것이
하나도 없으므로, `integrationTest` 는 이 모듈에서 **아무것도 실행하지 않는다**. 조건이 성립할 수가
없었다.

답을 준 것은 `integrationTest` 가 아니라 `dependencyInsight` 였고, 같은 리뷰가 `commonmark` ·
`snakeyaml` 을 지울 때 쓴 것과 같은 명령이다. 결과도 같은 모양이었다 — 항목은 **닫혔다**(R-2).

**규칙에 붙는 것**: 미루는 항목에는 재검토 트리거를 적으라는 요구가 이미 있다("언제 다시 볼까").
여기서 빠져 있던 것은 그 트리거가 **이 항목에 대해 실제로 발화하는가**였다. `integrationTest` 는
발화하는 사건이 맞지만 **이 모듈에는 닿지 않는다.** 트리거를 적을 때는 그것이 일어나는지가 아니라
**그것이 이 자리를 지나가는지**를 본다.

### 0.2 스레드풀 — *"이음매가 없다"* 가 거짓이고, 센 숫자도 틀렸다

계획은 *"호스트가 자기 executor 를 꽂을 이음매가 없다"* 라고 적었다. 이음매는 **일곱 자리에 있다**
(2026-08-31 확인).

| 이음매 | 자리 |
|--------|------|
| 생성자 인자 | `DefaultSubagentExecutionManager:169` · `DefaultHookExecutor:90` · `DefaultRewakeService:91` · `LeaseRenewer:69` |
| 빌더 | `PendingTurnReaper.Builder.scheduler:181` · `HolderLossSweeper.Builder.scheduler:122` |
| 옵션 객체 | `SubagentBackgroundExecutionOptions.executorService:91` |

없는 것은 이음매가 아니라 **조립 계층의 이음매**다 — `aimon-bootstrap` 의 스펙 18개 중
`ExecutorService` 나 `ThreadFactory` 를 받는 것이 하나도 없다. 항목이 겨눠야 할 자리가 달라지므로
근거를 다시 썼다(R-4).

**이름이 함정 하나를 놓아 두었다.** `ExecutorSpec` 은 스레드풀 스펙이 **아니다** — ReAct 실행기의
선택 기능(streaming · tracing · cost · memory)이다. 이 저장소가 다른 자리에서 이미 적어 둔 규칙이
그대로 걸린다: *"이름의 마지막 명사로 수명을(여기서는 역할을) 추론하지 말 것"*
(`overview/scope-model.md` §5.2).

그리고 **29곳이라는 숫자가 틀렸다.** `Executors.new` grep 이 29건을 뱉지만 그중 하나는
`shell/impl/local/package-info.java:62` 의 **javadoc 예제 코드**이고, 대신 `new ThreadPoolExecutor(`
로 직접 만드는 자리 둘(`DefaultWorkflowRunner:386` · `DefaultSubagentExecutionManager:448`)이 빠져
있었다. 실제 생성 지점은 **30곳**이다. 규칙 여섯이 말하는 그대로다 — *"N건도 도구가 만들어 낸
숫자"*. 이 저장소의 용어집이 `new BudgetTracker(` 에 대해 적어 둔 주석과 같은 함정이다.

### 0.3 세션 테스트킷 — 항목이 그린 **경계가 절반이었다**

계획은 중복을 *"`TwoNode*Harness` 141/136/138줄"* 로 적었다 — 415줄. 세 줄 다 맞다. 그런데 그 하네스를
쓰는 **테스트 스위트 자체가 통째로 세 벌**이고(352 · 375 · 379줄), 세 파일의 테스트 메서드 이름이
**일곱 개 모두 같다**. 실제 중복은 415줄이 아니라 **약 1,520줄**이다.

이것은 규칙 둘·셋이 잡는 종류가 아니다 — 근거도 참이고 심각도도 과장되지 않았다. 틀린 것은 **범위**이고,
[`README.md`](README.md) 가 B-20 에서 이미 한 번 만난 모양이다: *"끝과 주장이 둘 다 맞아도 경계는
틀릴 수 있다."* 그때는 경계를 고쳐도 목록이 안 움직였지만, 여기서는 **항목의 크기가 3.7배가 된다.**

### 0.4 곁가지 둘 — 태그 인구조사와 낡은 javadoc

**(a) `@Tag` 숫자 둘이 grep 산물이었다.** 계획과 CHANGELOG 가 적은 *"docker 태그 71개 클래스"* 는
`Tag("docker")` 문자열 검색의 결과이고, 그중 넷은 **javadoc 안에서 그 태그를 언급한 문장**이다
(`ReleaseGateMatchesCiGateTest` · `ListenDispatcher` · `PostgresSchemaFreezeTest` ·
`HolderLossSweeperTest` — 뒤의 둘은 태그를 **달지 않은** 단위 테스트다). 애노테이션으로 세면
**68개**다. 같은 검색이 *"`@Tag("playwright")` 5건"* 도 만들어 냈는데, 실제로는 **클래스 1개의 메서드
4개**다. 결론은 어느 쪽으로도 바뀌지 않지만(7개 모듈에게 그것이 유일한 테스트라는 사실), 숫자를 다시
인용할 사람을 위해 적어 둔다. CHANGELOG 의 숫자는 이 확인에 맞춰 고쳤다.

**(b) 게이트를 지키는 테스트의 javadoc 이 게이트보다 낡아 있었다.** `ReleaseGateMatchesCiGateTest`
의 "What this cannot see" 절이 *"opt-in 계층(`integrationTest` · `packagingTest` · `playwrightTest`)은
양쪽 게이트 밖에 있으므로 보지 못한다"* 라고 적고 있었는데, 같은 리뷰가 `integrationTest` 를 양쪽에 넣었으므로
**그 테스트는 이제 그것을 본다**(CI 스텝이자 게이트 태스크이므로 다른 태스크와 똑같이 강제된다).
같은 커밋에서 고쳤다. 파생 서술은 자기가 서술하는 대상이 바뀔 때 따라 바뀌지 않는다 — 규칙 일곱이
표에 대해 말하는 것이 javadoc 에도 그대로 적용된다.

---

## 1. 무엇이 이 목록에 없는가

리뷰가 관측했지만 **이미 다른 곳이 소유한** 항목들이다. 중복 등록은 백로그를 두 배로 만들고 한쪽이
먼저 낡는다.

| 관측 | 어디에 있나 |
|------|------------|
| 발행 모듈의 POM 스코프 | [`module-dependency-scope.md`](module-dependency-scope.md) D-1 |
| 승인·보류턴 저장소의 분산 이음매 | [`multi-instance-readiness.md`](multi-instance-readiness.md) M-1 · M-2 |
| `OrcaAgentExecutor` 분해 | [`../design/agent-execution/orca-executor.md`](../design/agent-execution/orca-executor.md) §13-1 — ReAct 코어를 `DefaultSubagentExecutor` 와 공유하는 계획이 이미 있고 이음매(`agent.loop.LoopTransition`)도 놓여 있다 |
| 공개 타입 축소 (`aimon-core` **1,012개**, 저장소 전체 1,281개 — 2026-08-31 확인) | [`../project/roadmap.md`](../project/roadmap.md) §3 — `1.0` 의 javadoc 완비 조건과 같은 뿌리이고, 측정 장치(`-Xdoclint:none`, `aimon.java-conventions.gradle.kts:21`)를 켜는 것이 선행이다 |
| S3 가 공유 파일시스템 계약 테스트를 돌지 않는다 | [`../design/filesystem/backend-contract.md`](../design/filesystem/backend-contract.md) §11 — 이미 열린 항목으로 등록되어 있다. R-5 를 확인하다 나왔고, 새 번호를 주면 중복이 된다 |

마지막 줄이 규칙 하나의 예외처럼 보일 수 있으므로 적어 둔다. 그 표는 **설계 시점 기록으로 동결된 표가
아니라** 그 문서가 스스로 "지금 열려 있는 것" 으로 유지하는 절이고, 계획이 `orca-executor.md`
§13-1 에 대해 내린 판단(*"새 문서를 만들면 중복 등록이 된다"*)과 같은 이유로 그대로 둔다.

---

## 2. 항목

### R-1 — `playwrightTest` · `packagingTest` 는 어느 게이트에도 없다 · **열림**

**무엇** — 두 opt-in 테스트 계층을 CI 에 넣거나, 넣지 않기로 결정하고 그 근거를 적는다.

**왜 — 관측 가능한 결과**

`integrationTest` 가 CI job 과 릴리스 게이트에 들어가면서, **어디서도 실행되지 않는 계층은 이 둘만
남았다**. 규모는 작다(2026-08-31 확인).

| 계층 | 실체 | 실행되는 곳 |
|------|------|------------|
| `@Tag("playwright")` | `PlaywrightLifecycleManagerTest` 의 메서드 **4개** | 없음 |
| `@Tag("packaging")` | `FatJarPackagingTest` **1개 클래스** (`aimon-sample-app`) | 없음 |

작다는 것이 안전하다는 뜻은 아니다. `FatJarPackagingTest` 가 검증하는 것은 **fat jar 안에서 스킬
목록이 온전한가**이고, 그 테스트의 javadoc 이 스스로 적듯 그것이 깨지는 방식은 예외가 아니라 **조용한
누락**이며 *"이 프레임워크가 실제로 한 번 겪은 회귀"* 다. 디렉토리 클래스패스로 도는 다른 모든 테스트는
그 자리를 볼 수 없다 — 패키징하면 리소스 조회가 jar 엔트리 열거가 되고, 그 코드는 `URLConnection` 을
`JarURLConnection` 으로 캐스팅한다. 지금 그 회귀를 잡는 자동화는 없다.

`aimon-browser-playwright` 는 발행 모듈이므로 R-1 은 게이트 편입이 7개 백엔드에 대해 말한 것과
**같은 문장**이 여덟 번째 모듈에 대해 성립하는지의 문제이기도 하다. 다만 그때와 달리 여기서는 대답이
자명하지 않다 —
브라우저 바이너리 설치는 job 하나가 아니고, `packagingTest` 는 `bootJar` 에 매달려 있다.

**어디** *(2026-08-31 확인)*

- `modules/aimon-browser-playwright/build.gradle.kts:29` — `playwrightTest` 등록
- `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts:133` — `packagingTest` 등록(전 모듈)
- `.github/workflows/build.yml:100` · `scripts/release.sh:153` — 둘 다 "still opt-in" 이라고 적고 있다
- `ReleaseGateMatchesCiGateTest` — 이 둘을 **보지 못한다**고 자기 javadoc 에 적어 두었다(0.4-b 에서 갱신)

**언제 다시 볼까** — 셋 중 하나.
- 브라우저 도구가 실제 소비자를 얻을 때. 지금 `playwrightTest` 가 지키는 것은 아직 아무도 쓰지 않는 표면이다
- fat jar 가 배포 산출물이 될 때 (`aimon-sample-app` 이 샘플이 아니라 참조 배포가 되는 시점)
- CI 시간이 문제가 아니게 될 때 — 지금 두 계층을 넣지 않는 진짜 이유는 설치 비용이지 테스트 시간이 아니다

### R-2 — `aimon-knowledge-opensearch` 의 `jackson-databind` · **닫힘** *(2026-08-31)*

**무엇이었나** — 직접 임포트가 0건인데 발행 POM 에 실려 나가는 선언을 지울 수 있는가.

**왜 미뤄져 있었나** — `integrationTest` 가 돌면 판단할 수 있다고 적혀 있었다. **틀렸다**(§0.1).

**확인한 것** — `dependencyInsight` 가 세 가지를 한 번에 말했다.

| 물음 | 답 |
|------|-----|
| 이 모듈이 Jackson 을 직접 쓰는가 | 아니다. `src/` 전체에 `com.fasterxml` 임포트 **0건** |
| 전이로 들어오는가 | 그렇다. `opensearch-java:2.20.0` 이 `jackson-bom:2.17.0` 을 통해 가져온다 |
| 선언이 버전을 고정하고 있었는가 | 아니다. 카탈로그가 말한 **2.16.1 이 2.17.0 에 진다**(conflict resolution) |

즉 `snakeyaml` 과 **같은 모양**이었다 — 버전 핀으로도 동작하지 않는 중복 선언. 지운 뒤
`:aimon-knowledge-opensearch:test` 통과와 런타임 클래스패스에 2.17.0 이 그대로 남아 있음을 확인했다.

**남는 것** — 없다. 다만 이 모듈은 **Testcontainers 테스트가 0건**이므로, 게이트에 들어간
`integrationTest` 가 지켜 주는 7개 백엔드 옆에서 **아무 통합 검증도 갖지 않은** 발행 모듈로 남는다.
항목을 새로 열지는 않는다 — OpenSearch 컨테이너를 띄우는 것은 이 항목의 범위가 아니었고, 필요해지는
시점은 그 모듈에 실제 소비자가 생길 때다. 여기 한 줄로 기록만 남긴다.

### R-3 — 커버리지에 하한선이 없다 · **열림 · CI 배선 대기** *(측정 완료 2026-08-31)*

**무엇** — `jacocoTestCoverageVerification` 규칙을 두어 커버리지 회귀를 실패로 만든다. 그 전에
지금 수치를 잰다.

**왜 — 관측 가능한 결과**

JaCoCo 는 **리포트만** 만든다. `aimon.java-conventions.gradle.kts:7` 이 플러그인을 붙이고 `:149` 가
`JacocoReport` 를 설정하지만, 검증 규칙은 **한 줄도 없다**. 그래서 커버리지는 떨어져도 아무것도
깨뜨리지 않는다 — CI 는 HTML 을 업로드하고, 아무도 그것을 열지 않아도 초록이다.

이 사실은 이미 한 자리에 적혀 있다. `ReleaseGateMatchesCiGateTest.REPORTING_ONLY_CI_TASKS` 가
`jacocoTestReport` 를 릴리스 게이트 면제 목록에 넣으면서 그 이유로 *"nothing in this build configures
a `jacocoTestCoverageVerification` rule, so it cannot fail a PR either"* 라고 적는다. **면제의 근거가
결함의 서술**이다.

**하지 말 것** — 수치를 모르는 채 임계값을 정하는 것. 근거 없이 박힌 숫자는 곧 `@Disabled` 나
`-x` 로 이어지고, 그러면 규칙이 없던 때보다 나쁘다 — 게이트 편입에서 "Docker 가 있으면 돌린다" 안을
버린 것과 같은 이유다(*"가장 엄격해 보이는 설정이 가장 약한 강제를 만든다"*).

**선행이었던 측정 — 했다** *(2026-08-31)*. 그리고 그 과정에서 **리포트 자체가 틀린 말을 하고 있었다.**
JaCoCo 의 기본값은 `test.exec` 하나만 읽는데, 7개 백엔드 모듈의 테스트는 전부 `@Tag("docker")` 라
`test` 에 없다. 즉 그 모듈들의 리포트는 "커버리지가 낮다" 가 아니라 **"테스트를 빼고 쟀다"** 였다.
`aimon.java-conventions.gradle.kts` 를 고쳐 리포트가 모든 계층의 exec 데이터를 읽게 했고, 두 수치를
나란히 잰다(line 기준).

| 모듈 | `test` 만 | `test` + `integrationTest` |
|------|----------|---------------------------|
| `aimon-memory-mongodb` | **0.0%** | 84.6% |
| `aimon-memory-postgres` | **0.0%** | 77.2% |
| `aimon-session-postgres` | 6.2% | 81.8% |
| `aimon-filesystem-gridfs` | 11.0% | 84.2% |
| `aimon-filesystem-s3` | 12.9% | 82.7% |
| `aimon-session-redis` | 19.8% | 82.1% |
| `aimon-session-mongodb` | 28.0% | 75.1% |

**저 일곱은 저커버리지 모듈이 아니라 미측정 모듈이었다.** 옛 수치 위에 하한선을 그었다면 잘 덮인
모듈들에 0~20% 를 박아 놓는 셈이었고, 그것이 이 항목이 "수치를 모르는 채 임계값을 정하지 말 것" 이라고
적어 둔 이유의 실사례다.

나머지 모듈은 `test` 만으로 이미 사실이다(양쪽 수치가 같다). 전체 23개 중 낮은 쪽은 셋뿐이다 —
**`aimon-knowledge-opensearch` 32.5%** (발행 모듈 중 최저이며 docker 테스트가 0건이라 이 수치가 진짜다),
`aimon-cli` 63.7%(비발행), `aimon-llm-openai` 68.3%. 나머지 20개는 line 74.8% 이상이고 `aimon-core` 는
87.2% 다.

**막고 있는 것이 바뀌었다 — 측정이 아니라 CI 배선이다.** 지금 하한선을 걸면 **CI 에서 그 일곱이 다시
0~20% 로 측정된다**: `build` job 이 `checkAll` + `jacocoTestReport` 를 돌리고 `integration` job 은
**다른 워크스페이스**에서 `integrationTest` 만 돌리므로, 리포트를 만드는 job 에는 docker 계층의 exec
데이터가 없다. 로컬에서 통과하는 규칙이 CI 에서만 깨지는 형태이며, 그 방향의 실패는 규칙을 곧
`-x` 로 만든다.

따라서 순서는 **① 두 job 사이로 exec 데이터를 넘기거나 리포트를 통합 job 쪽에서 만든다 → ② 그 다음
하한선**이다. 하한선의 형태는 이 저장소에 이미 선례가 둘 있다 — checkstyle 의 `maxWarnings` 와
`BASELINE_TOP_LEVEL_CYCLES`. 임의의 목표치가 아니라 **지금 값을 동결하고 내려가면 실패하는 베이스라인**
이 같은 모양이고, 임의의 숫자를 박지 말라는 이 항목의 요구를 만족하는 유일한 형태다.

**어디** *(2026-08-31 확인)* — `buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` 의
`JacocoReport` 블록 · `.github/workflows/build.yml` 의 `build` / `integration` 두 job ·
`modules/aimon-core/src/test/java/at/aimon/core/architecture/ReleaseGateMatchesCiGateTest.java` 의
`REPORTING_ONLY_CI_TASKS`(면제 근거가 여전히 참이다 — 검증 규칙은 아직 없다)

**언제 다시 볼까** — CI 의 리포트가 docker 계층을 볼 수 있게 되는 때. 그 전에 하한선을 걸면 안 된다.

### R-4 — 스레드풀을 **조립 계층에서** 주입할 수 없다 · **열림 · 설계 대기**

**무엇** — 호스트 애플리케이션이 자기 executor 를 스택 전체에 건네는 길을 정한다.

**왜 — 관측 가능한 결과**

main 소스의 풀 생성 지점은 **30곳**이다(§0.2 의 인구조사). 개별 컴포넌트에는 이음매가 일곱 자리
있지만, **`aimon-bootstrap` 의 스펙 18개 중 `ExecutorService`/`ThreadFactory` 를 받는 것은 하나도
없다.** 따라서 `AimonStack.from(spec)` 으로 조립하는 애플리케이션 — 스타터를 쓰는 Spring Boot 앱을
포함해 — 은 그 일곱 자리에 닿을 수 없고, 스택이 만드는 풀의 수·이름·데몬 여부를 관측하거나 바꿀 수
없다. 컨테이너에서 스레드 예산을 관리하는 호스트에게 이것은 **알 수 없는 스레드 N개**다.

**이것이 "중앙 팩토리를 만들자" 를 뜻하지는 않는다.** 풀마다 성질이 다르다 — `BashTool` 은 무제한
캐시 풀이 필요하고 — [`spring-boot-starter-open-items.md`](spring-boot-starter-open-items.md) 의
B-12 가 그 무제한을 사고가 아니라 **결정**으로 확인했다 — 하트비트 퍼블리셔는 단일 스레드 스케줄러여야
한다. 하나의 `ExecutorService` 로 전부를 대체하는 처방은 규칙 다섯이 B-12 에서 막은 것과 **같은
모양**이므로, 착수 전에 각 자리가 왜 그 종류인지부터 읽는다.

**설계에 필요한 것**

| 물어야 할 것 | 지금 아는 것 |
|-------------|-------------|
| 호스트가 실제로 원하는 것이 주입인가 관측인가 | **미확인.** 표본 0 — 저장소 밖 임베더가 아직 없다(`roadmap.md` §3) |
| 30곳 중 몇 개가 같은 성질인가 | 미확인. 이름·데몬 여부·상한이 자리마다 다르다 |
| Java 17 이라 가상 스레드는 못 쓴다 | 확정. 툴체인이 17로 고정되어 있다 |

**어디** *(2026-08-31 확인)* — `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/spec/` (스펙 18개) ·
§0.2 의 이음매 표 · `DefaultWorkflowRunner:386` · `DefaultSubagentExecutionManager:448`

**언제 다시 볼까** — 둘 중 하나.
- 임베딩한 호스트가 스레드 수를 묻거나 자기 풀을 요구할 때. 그것이 첫 줄의 실측이다
- `aimon-spring-boot-starter` 에 스레드 관련 프로퍼티 요청이 들어올 때 — 그 시점에는 **프로퍼티가
  아니라 스펙 이음매**가 답이다(M-1 이 저장소 축에서 만난 것과 같은 모양)

### R-5 — 세션 백엔드 3종의 멀티노드 테스트가 통째로 세 벌 · **닫힘** *(2026-08-31)*

**무엇이었나** — `aimon-session-testkit` 을 만들어 멀티노드 시나리오를 한 번 기술하고, 백엔드 셋이
그것을 상속한다. 선례는 `aimon-filesystem-testkit` 이다.

**왜 — 관측 가능한 결과**

세 모듈이 **같은 일곱 시나리오**를 각자 적고 있었다.

| 파일 | 줄 |
|------|-----|
| `MultiNode{Redis,Postgres,Mongo}IntegrationTest` | 352 · 375 · 379 |
| `TwoNode{Redis,Postgres,Mongo}Harness` | 138 · 136 · 141 |
| `RecordingTestSession` × 3 | 225 · 225 · 225 — **패키지 선언과 javadoc 한 문단 말고는 바이트까지 같다** |

일곱 메서드 이름이 세 파일에서 **글자까지 같다** — `concurrentSubmitOneNodeOwnsTurn` ·
`crossNodeInterrupt` · `crossNodeStatusAndEventRelay` · `deliveredMessageVisibleToHolderInbox` ·
`forwardedTurnResultReachesTheSubmittingNode` · `holderCrashEmitsTurnScopedHolderLost` ·
`lockExpiryAllowsFailOver`. 하네스도 javadoc 문단까지 거의 같고, 실제로 갈리는 것은 **SPI 넷을 어떻게
만드는가**뿐이다(Lettuce 커넥션 셋 ↔ Hikari 풀 둘 ↔ Mongo 클라이언트).

셋이면 **일치하는 것이 우연**이다. 라우팅에 시나리오가 하나 늘면 세 곳을 고쳐야 하고, 둘만 고친
상태는 초록으로 통과한다 — 어느 백엔드가 그 시나리오를 안 지키는지 아무도 모르는 채로.
[`interrupt-open-items.md`](interrupt-open-items.md) 의 1번이 `SubmitOptions` 매핑 넷에 대해 적은
것과 **같은 문장**이며, 거기서는 실제로 조용한 분기가 있었다.

**처방의 함정** (규칙 다섯 — 처방도 검증 대상이다)

선례 문서를 먼저 읽는다. `aimon-filesystem-testkit/build.gradle.kts` 는 **`java-test-fixtures` 를 쓸
수 없는 이유**를 직접 적고 있다 — 발행 플러그인이 Gradle 9 에서 제거된 내부 생성자를 호출해 설정
단계에서 실패한다. 그래서 답은 "코어에 test-fixtures 를 붙인다" 가 아니라 **발행하지 않는 모듈
하나**다. 같은 파일이 `api(project(":aimon-core"))` 를 쓰는 이유(발행하지 않으므로 POM 정직성 규칙이
적용되지 않는다)도 적어 두었다.

**한 것** — `aimon-session-testkit` (발행하지 않음, `settings.gradle.kts` 등록). 시나리오 일곱은
`AbstractMultiNodeSessionContractTest` 하나에만 있고, 백엔드는 `SessionBackendFactory` 를 구현해
합류한다 — 노드 하나치 SPI 넷(`SessionBackend`)과, 라우터를 우회해 SPI 를 직접 잡는 세 시나리오를 위한
개별 팩토리 셋. 백엔드가 자기 커넥션을 몇 개 여는지는 테스트킷이 알지 않는다(Redis 셋 · Hikari 둘 ·
Mongo 하나). 하네스는 `TwoNodeSessionHarness` 로 합쳐졌고 `RecordingTestSession` 은 한 벌만 남았다.

| | 파일 | 줄 |
|---|------|-----|
| 전 | 9 (3모듈 × 3) | **2,196** |
| 후 | 테스트킷 5 + 서브클래스 3 | **1,282** |

**대기 시간은 세 손잡이로 줄었다** — `settle()` · `propagationTimeout()` · `holderLossTimeout()`.
기본값은 가장 빠른 Redis 값이고 백엔드는 **넓히는 쪽으로만** 재정의한다. 여기서 정직하게 적어 둘 것이
하나 있다: 옛 스위트는 **같은 백엔드 안에서 settle 값을 둘로** 쓰고 있었고(Postgres 200/300,
Mongo 500/300 — 두 시나리오 사이에서 순서가 서로 뒤집혀 있다) 무엇이 그 차이를 정당화하는지는 어디에도
없었다. 하나로 합치면서 **둘 중 넓은 쪽**을 택했으므로 어느 시나리오도 예전보다 짧게 기다리지 않는다.

**공허 통과가 아님을 확인했다** (규칙 다섯 — 처방도 검증 대상이다). 세 백엔드 모두 컨테이너를 띄워
7개씩 21개가 통과했고, 그 다음 Redis 쪽 `propagationTimeout()` 을 1ms 로 좁혀 다시 돌렸더니 **그
손잡이를 쓰는 두 시나리오만** 실패하고 나머지 다섯은 통과했다. 서브클래스의 재정의가 공유 스위트에
실제로 닿고 있으며, 그 단언들이 진짜로 백엔드의 동작을 기다린다는 뜻이다.

**착수해 보니 항목의 경계가 또 틀렸다** — 이번이 **세 번째**다. 계획서는 415줄(하네스만)이라고 적었고,
이 문서에 등록할 때 스위트를 세어 약 1,520줄로 고쳤고, 실제로 열어 보니 **2,196줄**이었다.
`RecordingTestSession` 675줄이 세 번 다 빠져 있었다 — 세 파일이 **완전히 같은 코드**인데도 그렇다.
셋 다 같은 디렉토리를 봤고 볼 때마다 파일이 하나씩 더 보였다. 규칙 여섯이 "N건도 도구가 만들어 낸
숫자" 라고 말하는 것의 디렉토리 판본이며, `ls` 한 번이면 매번 끝날 일이었다.

**어디** *(2026-08-31)* — `modules/aimon-session-testkit/` ·
`modules/aimon-session-{redis,postgres,mongodb}/src/test/java/at/aimon/session/*/multinode/`

**남는 것** — 없다. 다음 세션 백엔드는 `SessionBackendFactory` 하나를 구현하면 일곱 시나리오를 전부
물려받는다.

### R-6 — top-level 사이클 13쌍은 **동결됐을 뿐 풀리지 않았다** · **열림 · `1.0` 조건에 막힘**

**무엇** — `at.aimon.core` 최상위 패키지 사이 13쌍의 상호 의존을 실제로 끊는다.

**왜 — 관측 가능한 결과**

리뷰가 넣은 것은 규칙이 아니라 **베이스라인**이다. `PackageDependencyArchitectureTest:76-79` 의
`BASELINE_TOP_LEVEL_CYCLES` 가 13쌍을 동결하고, 새 쌍이 생기면 실패하고, 풀린 쌍이 목록에 남아
있어도 실패한다. 즉 **늘지 않는 것**은 보장되지만 **줄어드는 것**은 아무것도 시키지 않는다.

13쌍이 무엇을 뜻하는가는 리뷰가 파일 단위로 확인했다 — 거의 전부가 `agent/impl/orca/**` 한 디렉토리,
즉 **`aimon-core` 안에 있는 조립 계층**에서 나온다. 그래서 이것을 푸는 일은 임포트 몇 개를 옮기는
일이 아니라 `agent` 패키지를 쪼개는 일이다.

**막혀 있는 이유** — `project/roadmap.md` §3 의 `1.0` 진입 조건 하나가 *"스코프 모델이 개명 없이 한
주기를 넘김"* 이고, 최근 두 번의 파괴적 변경이 **둘 다** 그 자리에서 나왔다. `agent` 를 쪼개는 것은
세 번째 개명이 된다. 그러므로 이 항목은 **지금 착수하면 안 되는 항목**이고, 그 사실이 항목의 본문이다.

**어디** *(2026-08-31 확인)* —
`modules/aimon-core/src/test/java/at/aimon/core/architecture/PackageDependencyArchitectureTest.java:76-79`
(베이스라인) · 같은 파일 `:88-101` (`agent.orca` carve-out — 그 SPI 가 왜 여러 레지스트리를 모으는지)

**언제 다시 볼까** — 둘 중 하나.
- `1.0` 이 나간 뒤. 그 전에는 조건이 금지한다
- 베이스라인이 **먼저 줄어들 때** — 다른 작업의 부산물로 한 쌍이 풀리면 테스트가 그것을 실패로
  보고한다(목록에서 지우라고). 그때가 이 항목이 살아 있다는 신호다

---

## 3. 관련

- [`multi-instance-readiness.md`](multi-instance-readiness.md) · [`module-dependency-scope.md`](module-dependency-scope.md) — 같은 리뷰에서 나온 두 축
- [`README.md`](README.md) — 항목에 무엇이 있어야 하는가, 그리고 규칙 일곱 개
- [`../project/roadmap.md`](../project/roadmap.md) §3 — R-6 을 막고 있는 `1.0` 조건
- [`../design/filesystem/backend-contract.md`](../design/filesystem/backend-contract.md) §7 — R-5 의 선례(`aimon-filesystem-testkit` 이 별도 모듈인 이유)
