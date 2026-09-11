# 아키텍처 리뷰가 남긴 나머지 항목 — 등록 항목 8건 (열림 2 · 닫힘 6)

2026-08-31 의 아키텍처 리뷰가 일곱 단계를 처리하고 남긴 것들이다. 같은 리뷰에서 나온 두 축은 이미
자기 문서를 가졌고([`multi-instance-readiness.md`](multi-instance-readiness.md) ·
[`module-dependency-scope.md`](module-dependency-scope.md)), **이 문서는 그 둘에 들어가지 않는 나머지**
— 검증 계층과 구조 부채 — 를 담는다.

계획 문서(`docs/plan/architecture-review-followups.md`)는 이 등록과 함께 삭제됐다. `docs/README.md` 의
규칙대로다 — 진행 추적 문서는 끝나면 지우고, 근거는 `design/` · `project/` 로, 열린 항목은 여기로.
아래에서 리뷰의 단계를 가리킬 때는 절 번호가 아니라 **한 것의 이름**으로 부른다(*게이트 편입* ·
*미사용 의존성 제거* · *사이클 베이스라인* · *뺀 것 표*). 지워진 문서의 절 번호는 아무도 되짚을 수
없고, 무엇을 했는지는 [`../../CHANGELOG.md`](../../CHANGELOG.md) 의 `[0.2.4]` 와 `a4d018a4`
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

> **정정** *(2026-09-05, R-7)*: 이 문단의 **"5건" 은 범위를 적지 않은 숫자**다. playwright 모듈의
> `.java` 로 한정하면 5 가 맞고, 같은 문단이 docker 에 쓴 것과 같은 저장소 전역 범위로 세면 **6** 이다
> (`ReleaseGateMatchesCiGateTest` 의 javadoc 이 여섯 번째이며 초기 커밋부터 있었다). 즉 한 문단이 두
> 범위를 섞었고, R-7 이 그 5 를 검증 없이 물려받아 표를 두 번 더 틀리게 만들었다. 애노테이션 4 · 클래스
> 1 이라는 이 문단의 결론은 그대로 참이다. 계열과 명령은 R-7 의 인구조사 절에 있다.

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
| 공개 타입 축소 (`aimon-core` **1,012개**, 저장소 전체 1,281개 — 2026-08-31 확인) | [`../project/roadmap.md`](../project/roadmap.md) §3 — `1.0` 의 javadoc 완비 조건과 같은 뿌리이고, 측정 장치(`-Xdoclint:none`, `aimon.java-conventions.gradle.kts` 의 `tasks.withType<Javadoc>()` 블록)를 켜는 것이 선행이다 |
| S3 가 공유 파일시스템 계약 테스트를 돌지 않는다 | [`../design/filesystem/backend-contract.md`](../design/filesystem/backend-contract.md) §11 — 이미 열린 항목으로 등록되어 있다. R-5 를 확인하다 나왔고, 새 번호를 주면 중복이 된다 |

마지막 줄이 규칙 하나의 예외처럼 보일 수 있으므로 적어 둔다. 그 표는 **설계 시점 기록으로 동결된 표가
아니라** 그 문서가 스스로 "지금 열려 있는 것" 으로 유지하는 절이고, 계획이 `orca-executor.md`
§13-1 에 대해 내린 판단(*"새 문서를 만들면 중복 등록이 된다"*)과 같은 이유로 그대로 둔다.

---

## 2. 항목

### R-1 — `packagingTest` 가 어느 게이트에도 없다 · **닫힘** *(2026-08-31)*

**무엇이었나** — 이 항목은 원래 `playwrightTest` 와 `packagingTest` **둘을 한 항목으로** 들고 있었고,
제목도 그렇게 적혀 있었다. 착수하면서 그 묶음이 먼저 무너졌다.

#### 경계가 틀렸다 — 이번엔 크기가 아니라 **개수**다

§0.3 과 R-5 가 이미 두 번 기록한 모양이지만(415줄 → 1,520줄 → 2,196줄), 그것들은 전부 **한 항목이
얼마나 큰가**를 잘못 센 것이었다. 여기서 틀린 것은 **항목이 하나인가**다.

두 계층을 묶은 근거는 *"둘 다 opt-in"* 하나뿐이었다. 그런데 opt-in 은 성질이 아니라 **현재 상태**이고,
같은 상태에 있다는 것이 같은 이유로 거기 있다는 뜻은 아니다. 비용을 실제로 재 보니 자릿수가 다르다.
(**이 마지막 문장은 R-7 이 양쪽을 다 재면서 반증되었다** — 아래 `남는 것`. 분리는 옳았고 근거가 틀렸다.)

| 계층 | 실체 | 비용 | 필요한 것 |
|------|------|------|----------|
| `@Tag("packaging")` | `FatJarPackagingTest` 메서드 **4개** (`aimon-sample-app`) | **57초** — 샘플 build 디렉토리를 지우고 루트에서 `packagingTest` (warm 은 6초) | **없음.** task 가 fat jar 둘을 자기가 빌드한다 |
| `@Tag("playwright")` | `PlaywrightLifecycleManagerTest` 메서드 **4개** | ~~미측정~~ → **cold 94초 / warm 14~28초** (R-7, 2026-09-05) | 브라우저 바이너리 설치 |

**항목의 본문이 이미 그 갈라짐을 적어 두고 있었다.** *"브라우저 바이너리 설치는 job 하나가 아니고,
`packagingTest` 는 `bootJar` 에 매달려 있다"* — 한 문장 안에 **서로 다른 두 이유**를 적어 놓고도 둘을
한 항목으로 두었다. 재검토 트리거도 마찬가지였다: 셋 중 하나는 playwright 전용(브라우저 소비자),
하나는 packaging 전용(fat jar 가 배포 산출물), 하나만 공통(CI 시간)이었다. **트리거 목록이 1:1:1 로
갈려 있으면 그것은 이미 항목 하나가 아니다.**

그리고 그 문장의 뒷절은 **틀리기까지 했다.** `packagingTest` 는 `bootJar` 에 "매달려" 있지 않다 —
`samples/aimon-sample-app/build.gradle.kts` 가 `dependsOn(bootJar, bootJarClassic)` 을 선언하므로 task 가
자기 선행조건을 스스로 만든다. 먼저 무엇을 돌려 두어야 하는 계층이 아니었고, 그래서 **넣지 않을 이유로
적혀 있던 것이 사실이 아니었다**(규칙 둘).

#### 한 것

`packagingTest` 를 `build` job 의 스텝과 릴리스 게이트 태스크로 넣었다. 별도 job 이 아닌 이유는
`integration` 과 대칭이 아니기 때문이다 — 별도 job 은 **실패한 체크가 계층 이름을 말한다**는 이득을 사고
JDK 설치와 전체 컴파일을 한 번 더 치르는데, Testcontainers 를 몇 분 도는 계층에는 값어치가 있지만 이미
컴파일이 끝난 job 에 1분을 얹는 계층에는 없다. 스텝 이름이 그 역할을 대신한다.

들어간 근거는 게이트 편입이 7개 백엔드에 대해 쓴 문장과 **다르다**. 그때는 *"그 모듈의 유일한 검증"*
이었지만 `packagingTest` 는 무엇의 유일한 검증도 아니다. 대신 이쪽은 **fat jar 를 볼 수 있는 유일한
검증**이다 — 패키징하면 리소스 조회가 jar 엔트리 열거가 되고(그 코드는 `URLConnection` 을
`JarURLConnection` 으로 캐스팅한다), 깨지는 방식이 예외가 아니라 **조용히 짧아진 스킬 목록**이며,
그 테스트의 javadoc 이 적듯 *"이 프레임워크가 실제로 한 번 겪은 회귀"* 다. 디렉토리 클래스패스로 도는
다른 모든 테스트는 그 코드 경로가 아예 존재하지 않는 자리에서 돈다. 검증 대상이 샘플 모듈이라는 것은
반론이 되지 않는다 — 회귀가 나는 코드는 발행되는 `aimon-core` 쪽이고, 샘플은 그것을 **패키징된 상태로
세울 수 있는 유일한 자리**다.

**공허 통과가 아님을 확인했다** (규칙 다섯). CI 에만 넣고 릴리스 게이트에서 뺀 상태로 되돌려
`ReleaseGateMatchesCiGateTest` 를 돌렸더니 의도한 문장 그대로 실패했다 —
*"CI runs `packagingTest` … but the release gate in scripts/release.sh does not: [checkAll, integrationTest]"*.
그 다음 되돌려 3건 통과. 두 목록을 붙들고 있는 것이 실제로 붙들고 있다.

**어디** *(2026-08-31)*

- `.github/workflows/build.yml` — `build` job 의 `Fat-jar packaging tests` 스텝. 실패 리포트 업로드의
  glob 에 `samples/*` 를 더했다(그 계층만 `modules/` 밖에 살아서 아티팩트가 조용히 비어 있었을 것이다)
- `scripts/release.sh` — 게이트가 `checkAll integrationTest packagingTest` 한 줄이 됐다
- `ReleaseGateMatchesCiGateTest` 의 "What this cannot see" · `aimon.java-conventions.gradle.kts` 의
  `packagingTest` 등록 주석 — 둘 다 **"양쪽 게이트 밖"** 이라고 적고 있었으므로 같이 고쳤다(§0.4-b 와
  같은 파생 서술 문제이며, 이 문서에서 두 번째다)

**남는 것** — 없다. playwright 쪽은 R-7 로 나갔고, **거기서 이 항목이 분리 근거로 적은 문장이
반증되었다.**

이 절은 *"비용을 실제로 재 보니 자릿수가 다르다"* 로 두 계층을 갈랐다. 그런데 그때 실제로 잰 것은
**한 쪽뿐**이었다 — 표의 playwright 행은 `미측정` 이었다. 둘 다 재고 나니 자릿수는 **같다.**

**두 칸이 같은 것을 재게 맞춘다.** 첫 판본의 cold 행은 packaging 에 **태스크 전체**(57초)를, playwright
에 **설치만**(94초)을 실어 서로 다른 것을 비교하고 있었다.

| | `packaging` | `playwright` |
|---|---|---|
| cold, 태스크 전체 | 57초 | **1분48초** |
| ├ 그중 선행 산출물 만들기 | fat jar 둘 (태스크가 자기가 빌드) | Chromium 내려받기 **94초** |
| warm, 태스크 전체 | 6초 | 14~28초 (`--rerun-tasks`, 컴파일 포함. 스위트 자체는 3초 미만) |

어느 행도 한 자리 배수 안이다. 그러므로 **분리의 근거를 갈아 끼운다** — 비용이 아니라 같은 표의
**`필요한 것` 칸**이다. `packagingTest` 는 **없음**(태스크가 fat jar 를 자기가 빌드한다)이고
`playwrightTest` 는 **브라우저 바이너리 설치**, 즉 네트워크로 받아 캐시해야 하는 빌드 밖의 산출물이다.
그 차이는 양이 아니라 **종류**이고, R-7 이 실제로 부딪힌 것도 전부 그 칸에서 나왔다(30초 타임아웃,
절반 찬 캐시의 flakiness, CI 캐시 키). 분리는 옳았고 근거만 틀렸다.

IMPORTANT: 이 문단의 첫 판본은 정반대로 적혀 있었다 — *"자릿수가 다르다는 예측은 맞았지만 방향이
반대였다"*. 한 문장이 "자릿수가 다르다" 와 "같은 자릿수였다" 를 동시에 말했고, "방향" 은 가리킬 대상이
없었다(이 항목은 방향을 예측한 적이 없고, 실측은 cold·warm 양쪽에서 playwright 가 더 비싸다 — 브라우저
설치가 필요하다는 사실에서 누구나 예상할 방향이다). [`README.md`](README.md) 규칙 둘이 *"근거가
달랐으면 달랐다고 적으라"* 고 요구하는 이유가 다음 사람을 막는 것인데, 반증된 예측을 **"판단이
확인되었다"** 로 적으면 그 장치가 반대로 작동한다.

### R-7 — `playwrightTest` 는 어느 게이트에도 없었다 · **닫힘** *(2026-09-05)*

**무엇이었나** — `playwrightTest` 를 CI 에 넣거나, 넣지 않기로 결정하고 그 근거를 적는다. R-1 에서
갈라져 나온 항목이며, 항목 스스로 *"착수하려면 먼저 재야 한다"* 고 적어 두었다. 재 보니 답이 정해졌다 —
**넣었다.** 그리고 재는 과정에서 항목의 전제가 실제보다 약하다는 것이 먼저 드러났다.

#### 근거가 약했다 — "게이트에 없다" 가 아니라 **"아무 데서도 돌지 않는다"** 였다

항목은 이 계층을 *"어디서도 실행되지 않는"* 계층으로 적었고, 그 문장이 뜻한 것은 **CI 와 릴리스
게이트 밖에 있다**는 것이었다. 즉 손으로 `./gradlew playwrightTest` 를 치면 돈다는 전제였다. 돌지
않았다.

`modules/aimon-browser-playwright/build.gradle.kts` 의 `tasks.register<Test>("playwrightTest")` 에는
`testClassesDirs` 도 `classpath` 도 없었다. 맨 `register<Test>` 는 그 둘을 `test` 태스크에서 물려받지
않으므로 이 태스크는 **후보 테스트 클래스가 0개**였고, `NO-SOURCE` 로 건너뛰어졌고, **650ms 만에
초록으로 끝났다.** 통과한 실행과 글자 하나 다르지 않다. 그 상태는 **초기 커밋 이래로** 유지되었으므로
`PlaywrightLifecycleManagerTest` 의 태그된 메서드 넷은 **한 번도 실행된 적이 없다.**

이것은 [`README.md`](README.md) 규칙 셋의 사례다 — 근거(*"어느 게이트에도 없다"*)는 참이었고 틀린 것은
**심각도**였다. 그리고 그 차이는 읽어서는 나오지 않았다: 위 `tasks.register<Test>("playwrightTest")`
(`53649d0` 기준 `modules/aimon-browser-playwright/build.gradle.kts:29`)를 몇 번을 다시 읽어도 없는 두
줄은 보이지 않는다. **태스크를 한 번 돌리자 첫 줄에 나왔다.**

같은 이유로 `ReleaseGateMatchesCiGateTest` 도 이것을 잡을 수 없었다. 그 테스트가 비교하는 것은 **두
목록**이고, 양쪽에 없는 계층은 아무리 썩어도 목록 비교에 나타나지 않는다. 자기 javadoc 이 *"Nothing
here notices when it rots"* 라고 적어 둔 그대로인데, **썩는 방식이 예상보다 한 단계 아래**였다.

#### 인구조사 — 이 표는 **세 번 틀렸다.** 세 번 다 원인이 같다

세 판본을 그대로 남긴다. 고친 것보다 **왜 계속 틀렸는지**가 이 항목에서 더 쓸모 있다.

| 판본 | 뭐라고 적었나 | 무엇이 틀렸나 |
|---|---|---|
| 1 (§0.4-a, 08-31) | *"문자열 5건, 실제로는 메서드 4개"* | **세는 범위를 안 적었다.** 5 는 playwright 모듈의 `.java` 에 한정한 값이고, 같은 문단의 docker 숫자(71/68)는 **저장소 전역**이었다. 한 문단이 두 범위를 섞었다 |
| 2 (R-7 초판) | *"2026-09-05 기준 6"* | 6 은 **착수 직전**의 값이고, 같은 커밋이 5로 되돌린 뒤였다. 날짜만으로는 커밋 앞뒤를 가릴 수 없다 |
| 3 (R-7 2판) | *"5 → 6 → 5"* | **5 → 6 이라는 사건은 일어난 적이 없다.** 첫 칸의 5 를 §0.4-a 에서 검증 없이 물려받았다 |

**실제 계열이다.** 명령을 그대로 적는다 — 이 표가 세 번 틀린 이유가 결국 "명령을 안 적었다" 이기 때문이다.

```
git grep -o 'Tag("playwright")' <rev> -- 'modules/*.java' | wc -l
```

| rev | 날짜 | 값 |
|---|---|---|
| `eec9ccd` (초기 커밋) | 08-31 | **6** |
| `4edca08` · `d3a9f70` · `8bb7071` · `3649b84` · `d4608ba` · `53649d0` | 08-31 ~ 09-05 | **6** (한 번도 안 움직였다) |
| `HEAD` (이 작업) | 09-05 | **5** |

**이 숫자는 딱 한 번 움직였고, 움직인 것이 이 작업이다.** 그 문자열은 `ReleaseGateMatchesCiGateTest`
javadoc 에 **초기 커밋부터 있었고**, `d3a9f70`(R-1)은 그 줄을 **다시 썼을 뿐**이다 — diff 의 삭제줄과
추가줄 양쪽에 문자열이 있어 순증이 0 이다. 2판의 *"R-1 이 그 문자열을 새로 적었다"* 는 그래서 거짓이다.

**그리고 범위를 바꾸면 값이 또 바뀐다.** 이것이 세 판본을 관통하는 유일한 원인이다.

| 범위 | `53649d0` | `HEAD` |
|---|---|---|
| playwright 모듈의 `.java` | 5 | 5 |
| `modules/**/*.java` | 6 | 5 |
| `modules/**` (모든 파일) | **7** | **6** |

마지막 줄이 아무 판본에도 없던 것을 하나 드러낸다 — `modules/aimon-browser-playwright/README.md:256`
에도 그 문자열이 있고, **세 판본 어느 것도 그것을 센 적이 없다.** 애노테이션 4 · 클래스 1 은 여덟 개
리비전 전부에서 한 번도 움직이지 않았다.

IMPORTANT: 규칙 여섯은 *"N건도 도구가 만들어 낸 숫자"* 라고 적는데, 세 번 겪고 나서 보이는 것은 그보다
한 칸 좁다 — 틀린 것은 매번 **도구**가 아니라 **범위와 시점**이었다. grep 은 세 번 다 정확했다. 그러므로
이 종류의 숫자를 인용할 때 함께 적어야 하는 것은 결과가 아니라 **결과를 재현하는 명령과 그것을 돌린
리비전**이다. 위 표가 그 형식이고, 그것이 없으면 다음 판본이 네 번째로 틀린다.
#### 실측 — 항목이 요구한 것 *(2026-09-05, macOS arm64, 가정용 회선)*

| 무엇 | 값 |
|---|---|
| cold 설치, 기본 브라우저 전체 (`install`) | **158초 · 디스크 1.0 GB** |
| cold 설치, chromium 만 (`install chromium`) | **94초 · 디스크 520 MB** |
| warm 설치 확인 | **1초 미만** |
| Linux x64 다운로드 바이트, 기본 전체 | **479.2 MiB** (chromium 167.3 / headless-shell 110.9 / firefox 99.5 / webkit 99.2 / ffmpeg 2.3) |
| Linux x64 다운로드 바이트, chromium 만 | **280.5 MiB** |
| `actions/cache` 항목 크기 (chromium, zstd) | **229 MiB** — 랩탑에서 tar+zstd 로 근사한 값이다. 실제 CI 항목은 **249 MiB**(261,229,767 B)로 더 컸다. R-8 참조 |
| `driver-bundle` jar | **201 MB — 이미 `testRuntimeClasspath` 에 있다.** 새 Maven 다운로드는 0 |
| warm `playwrightTest` | **14~28초** |

**설치는 자동이다** — 별도 `playwright install` 스텝이 이 저장소에 있는 것이 아니라
`Playwright.create()` 안에서 일어난다. 근거는 바이트코드다: `DriverJar.installBrowsers()` 가 번들
드라이버를 **인자 없는 `install`** 로 부르므로 `browsers.json` 의 `installByDefault: true` **전부**
(chromium · chromium-headless-shell · firefox · webkit · ffmpeg)를 받는다. 끄는 것은
`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD` 와 `SELENIUM_REMOTE_URL` 둘뿐이다.

#### 실측이 답을 정하기 전에, 실측이 **두 번째 결함**을 먼저 냈다

cold 캐시에서 이 계층은 **느린 것이 아니라 빨간 것**이었다. `PlaywrightLifecycleManager:98` 이
`Playwright.create()` 를 `future.get(30, TimeUnit.SECONDS)` 로 감싸는데, 그 안에서 158초짜리
다운로드가 일어나므로 타임아웃이 먼저 터진다. **두 번 재현했다 — 2분21초와 2분27초, 둘 다 4/4 실패.**

절반만 찬 캐시는 더 나쁘다. chromium 만 있고 firefox 가 없는 상태로 돌리면 **넷 중 둘이 실패하고 둘이
통과했다** — 느린 게이트가 아니라 **flaky 게이트**다. R-3 이 하한선에 대해 경고한 것과 같은 모양이며,
이 저장소가 "가장 엄격해 보이는 설정이 가장 약한 강제를 만든다" 로 부르는 것이다.

그래서 처방이 "CI 에 한 줄 더한다" 로 끝나지 않는다. 다운로드를 **타임아웃 밖으로 끌어내고** 이 테스트가
실제로 띄우는 것(chromium)으로 좁혔다 — `installPlaywrightBrowsers` JavaExec 태스크 + 테스트 태스크의
`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. 빈 `PLAYWRIGHT_BROWSERS_PATH` 에서 **1분48초에 4/4 통과**로
확인했다.

#### 결정 — 넣는다. 근거는 `packagingTest` 가 아니라 `integrationTest` 쪽이다

막고 있던 미지수(설치 비용)가 재어 보니 **비교 대상보다 작다** — 컨테이너 다섯을 몇 분 띄우는
`integrationTest` 보다 싸고, warm 에서는 `packagingTest` 와 같은 자릿수다. 그리고 편입 근거는 R-1 이
`packagingTest` 에 쓴 좁은 것("fat jar 를 볼 수 있는 유일한 검증")이 아니라 게이트 편입이 백엔드들에
쓴 것과 **같은 문장**이다.

| | `test` 만 | `test` + `playwrightTest` |
|---|---|---|
| 모듈 line | 83.70% | **88.15%** |
| `PlaywrightLifecycleManager` line | **8.96%** (6/67) | **56.72%** (38/67) |

`aimon-browser-playwright` 는 Maven Central 발행 모듈이고, 브라우저 프로세스·데몬 워커 스레드·종료
순서를 소유하는 클래스가 **9%** 로 나가고 있었다. "소비자가 아직 없다" 는 반론이 되지 않는다 — 발행된
순간부터 소비자는 저장소 밖에 있을 수 있고, R-2 가 `aimon-knowledge-opensearch` 를 그대로 둔 이유
(*테스트가 0건이다*)는 여기에 적용되지 않는다. 여기서는 **테스트가 있는데 빌드 버그가 건너뛰고 있었다.**

#### 한 것

- `installPlaywrightBrowsers` + `playwrightTest` 의 `testClassesDirs`/`classpath`/skip 플래그
- `.github/workflows/build.yml` — `build` job 의 스텝(별도 job 이 아닌 이유는 R-1 이 `packagingTest`
  에 쓴 규칙 그대로), `actions/cache` 로 `~/.cache/ms-playwright` 캐시. **키는 Playwright 버전
  하나**이지 버전 카탈로그 해시가 아니다 — 무관한 의존성 범프마다 249 MiB 를 버리게 된다.
  `restore-keys` 가 옛 항목을 집으면 설치 태스크가 새 리비전만 채운다
- `scripts/release.sh` — 게이트가 `checkAll integrationTest packagingTest playwrightTest
  jacocoTestCoverageVerification` 가 됐다. `.claude/skills/release/SKILL.md` 의 선언도 같이
- `playwrightTest.exec` 를 커버리지 hand-off 에 실었고, 모듈 하한선을 **82 → 87** 로 다시 동결했다
  (R-3 의 `floor(측정값) − 1`). 이것이 배선을 **자기강제**로 만든다 — 단위 계층만으로는 87 에 닿지
  못하므로, 나중에 이 계층을 CI 에서 조용히 빼면 커버리지 하한선이 실패한다
- `ReleaseGateMatchesCiGateTest` 에 검사 **둘**. 둘 다 리뷰가 낳았고 둘 다 **산문을 불변식으로 바꾼다**.
  기존 `SKILL_GATE_DECLARATION` 정규식은 넓히지 **않았다** — 그것이 느슨한 산문을 훑지 않는 것은
  javadoc 이 이유를 적어 둔 결정이므로(규칙 다섯), 넓히는 대신 파싱이 필요 없는 좁은 불변식을 옆에 세웠다.

  | 검사 | 무엇을 막나 | 공허 통과가 아님을 어떻게 봤나 |
  |---|---|---|
  | `releaseSkillDoesNotCallAGatedTierUngated` | 게이트가 도는 태스크를 릴리스 스킬이 opt-in 이라고 부르는 것 | 고치기 전 `SKILL.md` 에 대고 돌려 `:83` 을 지목하며 실패 |
  | `everyTestTagIsGated` | **네 번째 `@Tag` 가 게이트 없이 생기는 것** — 세 파일이 산문으로 "opt-in 계층은 없다" 고 주장하는데 아무것도 그것을 지키지 않았다 | 프로브 여덟 개(아래 표) |

  두 번째 검사의 정규식은 **두 번 넓혔고 두 번 다 프로브가 시켰다.** 읽어서는 한 번도 안 나왔다.

  | 프로브 | 기대 | 1차 초안 | 2차 초안 | 현재 |
  |---|---|---|---|---|
  | `@Tag("smoke")` | 실패 | 실패 | 실패 | 실패 |
  | `@org.junit.jupiter.api.Tag("smoke")` | 실패 | **통과** ✗ | 실패 | 실패 |
  | `@Tag(value = "smoke")` | 실패 | 통과 ✗ | **통과** ✗ | 실패 |
  | `@Tag(value="smoke")` (공백 없음) | 실패 | — | — | 실패 |
  | javadoc `{@code @Tag("smoke")}` | 통과 | 통과 | 통과 | 통과 |
  | javadoc `{@code @Tag(value = "smoke")}` | 통과 | — | — | 통과 |
  | 주석 `// @Tag("smoke")` | 통과 | 통과 | 통과 | 통과 |
  | 문자열 리터럴 | 통과 | 통과 | 통과 | 통과 |

  아래 네 행이 위 네 행만큼 중요하다 — **넓히면 오탐이 늘 수 있으므로** 넓힐 때마다 오탐 프로브도 다시
  돌렸다. 줄 첫머리 앵커가 그 넷을 막는다. 같은 줄에 쓴 `@Test @Tag("x")` 는 그 앵커를 지나가지만
  `spotlessJavaCheck` 가 애노테이션을 자기 줄로 분리하므로 트리에 존재할 수 없다(확인함).

**공허 통과가 아님을 두 방향으로 확인했다** (규칙 다섯).

| 조건 | 결과 |
|---|---|
| 릴리스 게이트에서만 `playwrightTest` 를 뺀다 | `ReleaseGateMatchesCiGateTest` 가 **실패** — *"CI runs `playwrightTest` … but the release gate in scripts/release.sh does not"* |
| 단위 계층만으로 하한선을 돌린다 | **실패** — *"lines covered ratio is 0.83, but expected minimum is 0.87"* |
| 새 스킬 검사를 고치기 **전의** `SKILL.md` 에 대고 돌린다 | **실패** — *"SKILL.md:83 — `playwrightTest` is the only opt-in tier outside both"*. 고친 뒤 5/5 통과 |

**어디** *(2026-09-05)*

- `modules/aimon-browser-playwright/build.gradle.kts` — `installPlaywrightBrowsers` 와 고쳐진
  `playwrightTest`
- `.github/workflows/build.yml` — `build` job 의 세 스텝 · 커버리지 아카이브 glob ·
  `integration` job 주석
- `scripts/release.sh` · `.claude/skills/release/SKILL.md` · `gradle/coverage-baselines.properties`
- **낡아 있던 산문 일곱** — "셋" → "다섯" → "여섯" → 일곱. **네 번 다 적게 셌다.** 네 번째로
  세면서는 세는 법을 먼저 적는다(위 인구조사 절이 같은 이유로 세 번 틀렸다). 범위는 `53649d0` 전체이고,
  거른 것은 *"playwrightTest 는 게이트 밖 / opt-in / 아무 데서도 안 돈다"* 를 **현재형으로 주장하는 줄**이다.

  | # | 자리 | 무엇이라고 적고 있었나 | 고친 커밋 |
  |---|------|----------------------|----------|
  | 1 | `.github/workflows/build.yml` `integration` job 주석 | *"the one tier still running nowhere is `playwrightTest`"* | `5801997` |
  | 2 | `aimon.java-conventions.gradle.kts:120` | *"the tier that still runs nowhere is `playwrightTest`"* | `5801997` |
  | 3 | `ReleaseGateMatchesCiGateTest:59` | *"One tier runs in neither place"* | `5801997` |
  | 4 | **`scripts/release.sh:168`** | *"Still opt-in and therefore NOT gated here"* | `5801997` — **"다섯" 목록에서 빠져 있었다** |
  | 5 | `PlaywrightLifecycleManagerTest:28` | *"그러면 CI 가 회귀를 잡지 못한다"* | `bf6911d` |
  | 6 | `.claude/skills/release/SKILL.md:83` | *"the only opt-in tier outside both"* | `0982aa5` — **1차 리뷰가 잡았다** |
  | 7 | **`modules/aimon-browser-playwright/build.gradle.kts:28`** | *"Opt-in task for running the playwright-tagged integration tests"* | **3차에서 직접 훑다 찾았다** — 이 작업이 다시 쓴 바로 그 파일에서, 리뷰 셋이 전부 지나갔다 |

  세지 **않은** 것 둘도 적는다. 안 세면 다음 사람이 다시 세게 된다.

  | 자리 | 왜 안 세나 |
  |---|---|
  | `architecture-review-open-items.md` §0.4-b (`:90`) | 옛 javadoc 을 **인용부호 안에 과거형으로** 적은 날짜 붙은 기록이다. 서술 대상이 아니라 서술 자체 |
  | [`spring-boot-starter-open-items.md`](spring-boot-starter-open-items.md) B-5 | 다른 문서의 **닫힌 항목**이 2026-08 시점 근거를 인용한 것이다. 문장은 이제 낡았지만 그 항목이 주장하는 것(데몬 스레드)은 그대로 참이라 손대지 않았다 |
  | [`../../CHANGELOG.md`](../../CHANGELOG.md) 의 `## [0.2.4]` 절 (`53649d0:1776`) | *"`playwrightTest` stays outside both gates"* — **동결된 릴리스 이력**이다. 그 릴리스 시점에 참이었고, 발행된 이력을 소급해 고치는 것은 이 저장소가 하지 않는 일이다 |

  이 행의 좌표는 한 번 고쳤다. 처음에는 `53649d0:1776` 옆에 *"현재 `:1833`"* 을 함께 적었는데, 그 값은
  적는 순간 이미 **1832** 였고 두 커밋 뒤 CHANGELOG 에 R-8 불릿이 들어가며 **1847** 로 밀렸다 — 즉
  **이 PR 이 자기 안에서 만든 드리프트**이고, `:1833` 은 지금 인용문과 무관한 문장을 가리킨다. 그때의
  판단(*"줄 번호가 드리프트하므로 섹션이 안정적인 좌표"*)은 맞았으므로 그 판단을 끝까지 적용해 **죽는
  쪽을 지웠다**: 동결된 리비전의 줄 번호는 영원히 참이고 "현재" 줄 번호는 다음 커밋에 거짓이 된다.

  세는 도구도 한 번 더 걸린다. 위 여섯을 뽑는 줄 단위 grep 은 **1번을 놓친다** — main 의 그 주장이
  *"the one tier still running / nowhere is `playwrightTest`"* 로 **두 줄에 걸쳐** 있어서 어느 한 줄도
  전체 문장을 담지 않기 때문이다. 그래서 위 표는 grep 결과가 아니라 **파일을 열어 확인한 목록**이다.

  IMPORTANT: 네 번의 오차가 전부 **같은 방향**이다 — 매번 **적게** 셌다. "셋" 은 커밋 하나가 고친 자리의
  수였고, "다섯" 은 **이 작업이 직접 고친 파일은 뺀다**는 무의식적 기준이 만든 수였다(그 기준은 성립하지
  않는다: 1·4·6·7번 모두 이 작업이 직접 고친 파일이다). "여섯" 은 기준을 처음 글로 적고 센 값인데,
  그 기준대로 훑는 일을 **grep 에 맡겨서** 또 하나를 놓쳤다.

  7번이 그중 가장 뼈아프다. **이 작업이 다시 쓴 파일 안에 있었고**, 리뷰 세 차례가 전부 지나갔고, 세는
  기준을 명시한 표 바로 옆에서 살아남았다. 찾은 방법은 표가 시킨 것을 실제로 한 것뿐이다 —
  `opt-in|outside both|neither place|runs nowhere` 를 저장소 전체에 훑은 뒤 **hit 를 한 줄씩 열어**
  현재형 주장인지 기록인지 판정했다. 같은 훑기가 세 자리를 더 냈는데(루트 `build.gradle.kts` 와
  `aimon.java-conventions` 의 두 줄) 그것들은 playwright 가 아니라 `integrationTest` 를 opt-in 이라고
  부르고 있었다 — 그 계층은 리뷰 전부터 게이트 안이므로 **이 작업보다 오래된 낡음**이다. 같이 고쳤다.

  그러므로 이 표가 남기는 문장은 개수가 아니다: **"전수" 라고 적은 목록은 그 말을 적은 순간부터 검증
  대상이고, grep 결과를 전수로 쓰면 안 된다.** 위 표는 hit 를 하나씩 열어 만든 것이며, 다음 사람이 다시
  셀 때 쓸 명령과 판정 기준을 함께 남긴 이유가 그것이다.

  그중 6번이 가장 값진 자리다. **같은 파일의 세 줄 위(`:80`)를 이 작업이 직접 고쳤고**, 그 선언을
  지키라고 있는 테스트(`releaseSkillDescribesTheRealGate`)가 **그동안 초록이었다** —
  `SKILL_GATE_DECLARATION` 정규식이 백틱 안 태스크 목록만 읽으므로 옆줄 산문은 사정거리 밖이기 때문이다.
  그래서 고치는 것으로 끝내지 않고 검사를 둘 세웠다(위 `한 것`).

**남는 것** — **있다. R-8 로 나갔다.** 이 절은 두 판본 동안 *"없다"* 라고 적혀 있었는데, 같은 커밋이
워크플로 주석에 *"the first run of this job"* 이 정한다고 적어 두었으므로 **문서가 자기 안에서
모순했다.** 미정 둘(ubuntu 러너의 공유 라이브러리, 이 모듈 하한선의 CI 실측)은 트리거가 **하나로 같고
명시적**이므로 항목의 모양을 갖췄다 — [`README.md`](README.md) 가 *"착수 중에 나온 것은 별도 번호로
등록하고 원래 항목에 몰래 끼워 넣지 않는다"* 고 요구하는 그대로 R-8 이다.

그리고 이 항목이 닫히면서 **§0.4-b 가 서술한 카빙 자체가 사라졌다**:
`ReleaseGateMatchesCiGateTest` 가 "양쪽 게이트 밖이라 보지 못한다" 고 적을 계층이 이제 하나도 없다.
그 문단은 지우지 않고 **왜 있었는지와 무엇이 그것을 무력화했는지**로 다시 썼다 — 사각지대의 모양
(*양쪽에 없는 것은 목록 비교에 나타나지 않는다*)은 다음 계층이 생기면 그대로 되돌아오기 때문이다.

### R-8 — playwright 계층이 ubuntu 에서 실제로 도는 것을 아무도 못 봤었다 · **닫힘** *(2026-09-06)*

**무엇이었나** — R-7 이 전부 **macOS arm64 에서** 쟀고, 두 가지가 그 기계에서 결정될 수 없는 채로
게이트에 들어갔다. 트리거는 *"이 브랜치가 처음 ubuntu 에서 도는 순간"* 하나였다. **발화했다** —
PR [#32](https://github.com/kangwoo/aimon-core/pull/32), run
[`33998782676`](https://github.com/kangwoo/aimon-core/actions/runs/33998782676) (`b7945cb`).
5개 job 전부 success 이고 둘 다 정산됐다.

#### 미정 ① `--with-deps` — **필요 없었다**

`build` job 의 세 스텝이 전부 success 이고, 로그 전체(24,863줄)에서
`error while loading shared libraries` · `cannot open shared object` · `Host system is missing dependencies`
가 **0건**이다. ubuntu 러너의 시스템 라이브러리만으로 chromium 이 뜬다.

같은 로그가 **두 가지를 덤으로 확인해 주었다.**

| 확인된 것 | 증거 |
|---|---|
| `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` 이 실제로 듣는다 | 받은 것이 chromium · chromium-headless-shell · ffmpeg **셋뿐**이다. firefox·webkit 0건 — 설계대로 |
| 캐시 키와 경로가 맞다 | 첫 실행 `Cache not found for input keys: playwright-browsers-Linux-1.58.0`, post 스텝 `Cache saved with key: playwright-browsers-Linux-1.58.0`. 내려받은 곳도 `/home/runner/.cache/ms-playwright` 로 워크플로의 `path` 와 일치 |

**항목을 다시 열지 않는 기록 한 줄** *(2026-09-06)*: 위 표의 캐시 행은 miss → save 까지만 본 것이다.
다음 실행 [`33999629221`](https://github.com/kangwoo/aimon-core/actions/runs/33999629221) 이 나머지 반을
채웠다 — 그 키로 **복원**했고, `Downloading Chrome` 이 **0건**이었고, post 가
*"Cache hit occurred on the primary key …, not saving cache"* 로 재저장을 건너뛰었다. 계층 비용도 warm
**20초**(cold 32초)다. 이것은 이 항목의 미정 둘이 아니라 곁가지였고 판단을 바꾸지 않으므로 항목은 닫힌
채로 둔다 — R-2 가 자기 범위 밖 사실을 *"여기 한 줄로 기록만 남긴다"* 로 처리한 것과 같은 자리다.

#### 미정 ② 하한선 87 — **CI 가 로컬과 소수점까지 같다**

`coverage` job 이 세 exec 를 전부 복원했고(`test` · `integrationTest` · `playwrightTest`),
`jacocoTestCoverageVerification` 이 통과했다. 업로드된 `jacoco-xml` 아티팩트에서 실제 수치를 꺼내 로컬과
대조했다.

| | 로컬 (2026-09-05) | **CI (run 33998782676)** |
|---|---|---|
| LINE | 714 / 810 = **0.8815** | 714 / 810 = **0.8815** |
| INSTRUCTION | 3362 / 3782 = 0.8889 | 3362 / 3782 = 0.8889 |
| BRANCH | 202 / 254 = 0.7953 | 202 / 254 = 0.7953 |
| `PlaywrightLifecycleManager` LINE | 38 / 67 = 0.5672 | 38 / 67 = 0.5672 |

**한 줄도 다르지 않다.** `coverage-baselines.properties` 헤더의 *"Do not re-derive these from a laptop"*
가 이 모듈에 대해서는 **적용되지 않는다**는 뜻이고, 그 금지의 명시된 이유(docker 계층을 안 돌리면 0~28%
로 측정된다)가 이 모듈에 없기 때문이라는 R-8 의 예상이 맞았다. 값은 87 그대로 두고 — `floor(88.15) − 1`
이 CI 수치에서도 같은 값이다 — **예외 문단을 없앴다.** 그 파일은 다시 한 규칙만 갖는다.

#### 착수해서 알게 된 것 — **비용 판단이 보수적이었다. 그것도 크게**

R-7 은 자기 숫자가 macOS 값이라는 한계를 적어 두었다. 그 한계가 **어느 방향으로** 틀렸는지가 이 항목이
실제로 남기는 값이다.

| | macOS arm64 (가정용 회선) | **ubuntu-latest 러너** | 비 |
|---|---|---|---|
| chromium 내려받기 | **94초** | **약 6초** (`23:38:49.6` → `23:38:55.5`) | **~15배** |
| 계층 전체, cold | 1분48초 | **32초** (세 스텝 `23:38:35` → `23:39:07`) | ~3.4배 |
| `actions/cache` 항목 크기 | 229 MiB (랩탑 tar+zstd 근사) | **249 MiB** = 261,229,767 B (`gh cache list`) | 1.09배 |
| └ 그중 테스트 스텝 | — | 27초 (컴파일 + 설치 + 4 테스트) | |
| └ 캐시 저장 (post) | — | 3초 | |

마지막 행은 방향이 반대이고 폭도 작지만 같은 교훈에 속한다 — 압축된 아카이브 크기는 플랫폼과 무관해
보이지만 아니다(바이너리가 다르면 압축률도 다르다). 그 한 줄은 2026-09-06 의 PR 리뷰가 잡을 때까지
`build.yml` 에 랩탑 값으로 남아 있었다: **시간 숫자는 이 항목이 고쳤고 크기 숫자만 놓쳤다.**

즉 **R-7 이 결정을 내릴 때 근거로 삼은 숫자는 실제 CI 비용의 3배**였고, 다운로드만 보면 15배였다.
결정이 그 방향으로 틀렸다면 되돌려야 했겠지만 여기서는 **"넣는다" 를 더 강하게 만들 뿐**이다 —
비싸다고 본 것이 실제로는 더 쌌다.

여기서 규칙에 붙는 것은 한 줄이다. 규칙 셋이 *"심각도는 돌려 봐야 나온다"* 라고 말하는데, 이 건은
**어디서 돌리느냐가 그 숫자를 3~15배 바꿨다.** 실측을 근거로 결정할 때는 잰 값만이 아니라 **잰 환경이
결정이 적용될 환경인가**를 함께 적어야 하고, 아니라면 그것이 곧 미정 항목이다. R-7 이 그 한계를 적어
두었기 때문에 이 항목이 존재할 수 있었다.

**어디** *(2026-09-06)*

- `.github/workflows/build.yml` — `Playwright browser tests` 스텝의 주석(미정 → 정산된 사실로. 지우지
  않았다 — 러너 이미지가 바뀌면 다시 볼 자리다)
- `gradle/coverage-baselines.properties` — 예외 문단 제거, `aimon-browser-playwright=87` 은 그대로

**남는 것** — 없다. 다음에 이 자리를 다시 열 것은 **러너 이미지 변경**이며, 그때의 증상은 이 항목이
적어 둔 그대로다(`.so` 누락). 고칠 자리도 그대로다 — 공유 Gradle 태스크가 아니라 워크플로 스텝.


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

### R-3 — 커버리지에 하한선이 없다 · **닫힘** *(2026-09-02)*

**무엇** — `jacocoTestCoverageVerification` 규칙을 두어 커버리지 회귀를 실패로 만든다. 그 전에
지금 수치를 잰다.

**왜 — 관측 가능한 결과**

JaCoCo 는 **리포트만** 만든다. `aimon.java-conventions.gradle.kts` 의 `plugins { }` 블록이 `jacoco` 를 붙이고
`tasks.withType<JacocoReport>()` 블록이 리포트를 설정하지만, 검증 규칙은 **한 줄도 없다**. 그래서 커버리지는 떨어져도 아무것도
깨뜨리지 않는다 — CI 는 HTML 을 업로드하고, 아무도 그것을 열지 않아도 초록이다.

이 사실은 이미 한 자리에 적혀 있다. `ReleaseGateMatchesCiGateTest.REPORTING_ONLY_CI_TASKS` 가
`jacocoTestReport` 를 릴리스 게이트 면제 목록에 넣으면서 그 이유로 *"nothing in this build configures
a `jacocoTestCoverageVerification` rule, so it cannot fail a PR either"* 라고 적는다. **면제의 근거가
결함의 서술**이다.

**하지 말 것** — 수치를 모르는 채 임계값을 정하는 것. 근거 없이 박힌 숫자는 곧 `@Disabled` 나
`-x` 로 이어지고, 그러면 규칙이 없던 때보다 나쁘다 — 게이트 편입에서 "Docker 가 있으면 돌린다" 안을
버린 것과 같은 이유다(*"가장 엄격해 보이는 설정이 가장 약한 강제를 만든다"*).

**선행이었던 측정 — 했다** *(2026-08-31)*. 아래 절은 **그 날짜의 기록**이며 수치도 모듈 수도
그때의 것이다 — 이후 `aimon-memory-{mongodb,postgres}` 가 제거되어 도커 티어는 일곱이 아니라 다섯,
배포 모듈은 23 이 아니라 21 이다(표의 두 행에 붙은 *(모듈 제거됨)* 이 그 지점을 가리킨다). 기록을
소급해 고치지 않는 이유는 이 항목이 증명하려는 것이 **그때 무엇을 보고 무엇을 결정했는가**이기
때문이다. 그리고 그 과정에서 **리포트 자체가 틀린 말을 하고 있었다.**
JaCoCo 의 기본값은 `test.exec` 하나만 읽는데, 7개 백엔드 모듈의 테스트는 전부 `@Tag("docker")` 라
`test` 에 없다. 즉 그 모듈들의 리포트는 "커버리지가 낮다" 가 아니라 **"테스트를 빼고 쟀다"** 였다.
`aimon.java-conventions.gradle.kts` 를 고쳐 리포트가 모든 계층의 exec 데이터를 읽게 했고, 두 수치를
나란히 잰다(line 기준).

| 모듈 | `test` 만 | `test` + `integrationTest` |
|------|----------|---------------------------|
| `aimon-memory-mongodb` *(모듈 제거됨)* | **0.0%** | 84.6% |
| `aimon-memory-postgres` *(모듈 제거됨)* | **0.0%** | 77.2% |
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

**① CI 배선 — 했다** *(2026-08-31)*. 측정이 끝난 뒤 막고 있던 것은 CI 였다. 그때 하한선을 걸었다면
**CI 에서 그 일곱이 다시 0~20% 로 측정됐을 것이다**: `build` job 이 `jacocoTestReport` 를 돌리고
`integration` job 은 **다른 워크스페이스**에서 `integrationTest` 만 돌리므로, 리포트를 만드는 job 에
docker 계층의 exec 데이터가 없었다. 로컬에서 통과하는 규칙이 CI 에서만 깨지는 형태이며, 그 방향의
실패는 규칙을 곧 `-x` 로 만든다.

두 안 중 **exec 데이터를 job 사이로 넘기는 쪽**을 택했다. 리포트를 `integration` job 에서 만드는 안은
그 job 이 `build` 를 `needs` 해야 하고, 그러면 지금 병렬인 두 job 이 직렬이 되어 **느린 쪽이 빠른 쪽을
기다린다** — `integration` job 이 별도 job 인 이유를 그대로 되돌리는 셈이다.

한 것 — `build` 와 `integration` 이 각자 자기 `.exec` 를 tar 로 올리고, 둘을 `needs` 하는 세 번째
**`coverage` job** 이 그것을 풀어 `./gradlew jacocoTestReport -x test` 를 돌린다. 세 가지가 이 모양을
정했다.

| 결정 | 이유 |
|------|------|
| `-x test` | `jacocoTestReport` 의 `dependsOn(test)` 는 **exec 파일을 읽는다는 선언**이라 지울 수 없다(Gradle 9 가 미선언 입력을 거부한다). 그래프에서만 빼면 단위 스위트를 세 번째로 돌리지 않고 다른 두 job 의 데이터를 그대로 쓴다 — main 클래스만 컴파일된다 |
| glob 이 아니라 tar | `upload-artifact` 는 매치된 경로들의 **최장 공통 접두사**를 아티팩트 루트로 삼는다. 모듈이 여럿이면 `modules/` 지만 **하나만 매치되는 날 조용히** `modules/<하나>/build/jacoco/` 로 바뀌고, 복원이 엉뚱한 경로에 떨어져 **아무것도 실패시키지 않은 채** 그 모듈의 커버리지가 사라진다 |
| `if: always()` 없음 | 커버리지 숫자는 **먹이는 계층이 전부 실제로 돌았을 때만** 뜻이 있다. 없으면 ②의 하한선이 "커버리지가 떨어졌다" 로 실패하는데 진짜 원인은 빨간 통합 테스트 하나가 된다 — 두 job 을 가른 이유를 정면으로 어기는 오진이다. 대가는 빨간 실행에서 커버리지 XML 이 안 나오는 것이고, 그 숫자는 어차피 읽을 것이 아니었다 |

**② 하한선 — 했다** *(2026-09-02)*. 형태는 이 저장소의 선례 둘(checkstyle 의 에러 예산,
`BASELINE_TOP_LEVEL_CYCLES`)과 같다 — 임의의 목표치가 아니라 **측정값을 동결하고 내려가면 실패하는
베이스라인**이다. 값은 `gradle/coverage-baselines.properties` 에 데이터로 두었다(코드가 아니라 데이터라
숫자 하나 움직이는 것이 리뷰어가 읽을 수 있는 한 줄 diff 가 되고, 동결된 집합 전체가 한 페이지에 보인다).

**승인받은 "정수 내림" 을 쓰지 않았다 — 데이터가 그러지 말라고 했다.** CI 수치를 그대로 내림하면 여유가
이렇게 된다.

| 여유 | 모듈 수 |
|------|--------|
| 0.1pp | 4 (`memory-postgres` · `rewake-webhook` · `session-mongodb` · `session-redis`) |
| 0.2pp | 3 |
| 0.3pp 이상 | 16 |

그런데 **같은 커밋이 로컬 81.8% / CI 81.4% 로 갈렸다**(`session-postgres`). 즉 관측된 변동(0.4pp)이 7개
모듈의 여유보다 크다. 그대로 걸었으면 **하한선 자체가 flaky 게이트**가 되고, 이 항목이 첫 문장에서
경고한 것("규칙이 곧 `-x` 로 이어진다")을 스스로 실현했을 것이다. 그래서 `floor(측정값) − 1` 로 잡았다 —
읽기 쉬운 정수라는 성질은 그대로, 여유는 1.1~2.0pp 로 관측 변동보다 넉넉하게.

**어디에 걸었나** — `coverage` job 의 스텝(리포트 **뒤**에, 별도 스텝으로. 하한선이 걸리면 그 이유를
설명하는 리포트가 필요하고, 업로드가 `if: always()` 라 그때도 도착한다)과 릴리스 게이트
(`checkAll integrationTest packagingTest jacocoTestCoverageVerification`). 게이트에 넣은 것은 선택이
아니다 — 이 태스크는 **빌드를 실패시킬 수 있으므로** `ReleaseGateMatchesCiGateTest` 의 면제 목록에
넣으면 거짓말이 된다. 릴리스 경로에서는 앞의 세 태스크가 같은 워크스페이스에서 이미 돌았으므로 추가
비용도 없다.

**공허 통과가 아님을 세 방향으로 확인했다** (규칙 다섯).

| 조건 | 결과 |
|------|------|
| docker tier 없이(로컬 `test` 만) | `filesystem-gridfs` 0.10 · `memory-mongodb` 0.00 · `memory-postgres` 0.00 이 **실패** — 이 항목이 서술한 바로 그 상황을 소리 내어 잡는다 |
| 전 tier 데이터 완비(로컬에서 docker 3종 추가 실행) | 23개 **전부 통과**, violations 0 |
| `aimon-core` 를 86 → 88 로 올려 회귀를 흉내 | **실패** — *"lines covered ratio is 0.87, but expected minimum is 0.88"* |

첫 줄이 남긴 사용성 문제 하나는 태스크 `description` 으로 처리했다. JaCoCo 의 실패 문장은 *"ratio is
0.00"* 이라고만 말해서 **tier 를 안 돌린 것**이 아니라 커버리지가 붕괴한 것처럼 읽히는데, 그 구분은
메시지가 할 수 없으므로 `./gradlew tasks` 에서 보이게 적었다.

**남는 것** — 없다. `jacocoTestReport` 는 여전히 면제 목록에 있고 그 근거도 여전히 참이다: 리포트는
스스로 아무것도 실패시키지 않는다. 이제 그 옆에 실패시키는 형제가 생겼을 뿐이다.

**공허 통과가 아님을 확인했다** (규칙 다섯). 로컬에서 job 세 개의 셸을 그대로 실행해 왕복시켰다:
jacoco 디렉토리를 전부 지우고 → 두 tar 를 풀고 → `jacocoTestReport -x test` 를 돌렸더니
`aimon-session-{redis,postgres,mongodb}` · `aimon-filesystem-s3` · `aimon-knowledge-opensearch` ·
`aimon-core` 가 위 표의 `test`+`integrationTest` 수치와 **소수점까지 일치**했다. exec 파일이 0개일 때
tar 가 빈 아카이브를 조용히 만들지 않도록 두 archive 스텝에 가드를 두었고, 그것이 exit 1 로 발화하는
것도 확인했다.

**어디** *(2026-08-31)* — `.github/workflows/build.yml` 의 `build` / `integration` / **`coverage`** 세 job ·
`buildSrc/src/main/kotlin/aimon.java-conventions.gradle.kts` 의 `JacocoReport` 블록(주석이 이 배선을
서술하도록 갱신했다 — §0.4-b 가 말하는 파생 서술 문제다) ·
`modules/aimon-core/src/test/java/at/aimon/core/architecture/ReleaseGateMatchesCiGateTest.java` 의
`REPORTING_ONLY_CI_TASKS`(면제 근거가 여전히 참이다 — 검증 규칙은 아직 없다. `-x test` 는 그 테스트의
토큰 파서가 이미 버리므로 게이트 비교는 그대로다)

**언제 다시 볼까** — 다시 볼 것이 아니라 **올릴 것**이다. 베이스라인은 내려가지 않는 것만
보장하지 올라가라고 시키지 않는다(R-6 의 사이클 베이스라인과 같은 성질이다). 커버리지가 의미 있게
오른 모듈은 그 값을 새로 동결하는 것이 이 파일을 살아 있게 하는 방법이다.

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
