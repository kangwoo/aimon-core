# 메모리 백엔드 경계 — 등록 항목 6건 (열림 6)

`aimon-memory-{postgres,mongodb}` 를 제거하고 `aimon-memory-file` 을 코어로 병합하면서,
분산 메모리의 자리를 별도 저장소 `aimon-memory`(Postgres+pgvector 서비스)로 넘겼다.
그 작업이 끝나고 **남은 것**을 여기 모은다.

여섯 중 넷은 **다른 저장소에서** 해야 하는 일이다. 그 저장소에 이슈 트래커가 없어서
(§4 참조) 여기 적는다 — 열린 항목의 정본은 이 디렉토리이지 저장소 경계가 아니다.

출처는 2026-09-04 ~ 09-05 의 작업과 그에 대한 3회차 독립 리뷰다.
줄 번호는 **2026-09-05 확인**이며 드리프트한다.

---

## 0. 먼저 — 이 항목들의 순서는 강제되어 있다

M-3(코어 릴리스) → M-2(배선) 이고, M-2 에 착수하면 M-1 이 즉시 빨간불로 드러난다.
따라서 M-1 을 M-2 의 하위 작업으로 여기지 말 것 — **어댑터 소스 변경이고 정책 뒤집기**다.

```
M-3  aimon-core 0.3.0 릴리스
  └→ M-2  aimon-memory 가 testkit 을 GAV 로 당겨 RemotePeerMemory 를 스위트에 건다
      └→ M-1  RemoteSearcher 가 세션 id 를 거절하도록 고친다  ← 여기서 터진다
```

---

## 1. 열린 항목

### M-1 — `RemotePeerMemory` 가 계약을 지키지 않으며, 지금은 그 사실이 아무도 깨뜨리지 않는다 · **열림**

**무엇** — `aimon-memory` 의 `RemoteSearcher.search()` 가 세션 id 실린 질의를 거절하도록 고치고,
그것을 정당화하던 tier javadoc 의 입장을 뒤집는다.

**왜 — 관측 가능한 결과**

`AbstractPeerMemoryContractTest.sessionIdIsRejectedRatherThanIgnored`
(`modules/aimon-memory-testkit/src/main/java/at/aimon/memory/testkit/AbstractPeerMemoryContractTest.java:422`)
는 `narrowsBySession()` 이 `false` 인 백엔드가 세션 id 를 받으면 `IllegalArgumentException` 을
던질 것을 요구한다. 기본 백엔드는 지킨다 — `StoreBackedPeerMemory.java:198`.

원격 어댑터는 지키지 않는다. `RemoteSearcher.search()`
(`aimon-memory` `modules/aimon-memory-client/src/main/java/at/aimon/memory/client/RemotePeerMemory.java:250`)
는 `MemorySearchQuery.getSessionId()` 를 **한 번도 읽지 않고**, `narrowsBySession()` 은 `:290` 에서
`false` 를 돌려준다. 즉 세션 하나로 좁혀 달라는 질의에 **전 세션 결과**를 돌려주고, 호출자는 그것을
좁혀진 결과로 읽는다.

어댑터 javadoc 은 그것을 결함이 아니라 **정책**으로 적는다 — *"The session is not dropped quietly …
this flag is how the caller finds that out"*. 스위트는 정확히 그 입장을 불충분하다고 선언한다
(*"A filter that did not run must not read as one that did"*). 그러므로 이것은 버그 수정이 아니라
**어느 쪽 입장을 택할지의 결정**이고, 계약이 이미 한쪽을 택해 두었다.

**지금 아무 일도 일어나지 않는 이유**는 스위트가 그 어댑터에 걸려 있지 않기 때문이다(M-2).
계약이 있고 위반이 있는데 둘이 만나지 않는 상태다.

**어디** (2026-09-05)
- 계약: `modules/aimon-memory-testkit/.../AbstractPeerMemoryContractTest.java:422`
- 지키는 쪽: `modules/aimon-core/.../memory/StoreBackedPeerMemory.java:198`
- 안 지키는 쪽: `aimon-memory` `.../client/RemotePeerMemory.java:250` · `:290`
- 기록된 자리: `aimon-memory` `docs/adr/0007-aimon-core-boundary.md` — *"Step 3 is not wiring alone"*

**언제 다시 볼까** — M-2 에 착수하는 순간. 그때 이 항목은 선택이 아니라 전제가 된다.

---

### M-2 — `RemotePeerMemory` 가 다섯 티어 계약 스위트에 걸려 있지 않다 · **열림 · M-3 대기**

**무엇** — `aimon-memory` 가 `at.aimon.core:aimon-memory-testkit` 을 `testImplementation` 으로 당기고
`AbstractPeerMemoryContractTest` 를 상속해 `newBackend()` 를 구현한다.

**왜 — 관측 가능한 결과**

스위트를 배포 대상으로 승격시킨 **유일한 이유**가 이것이었다. 승격은 끝났고 소비는 시작되지
않았으므로, 지금은 **기본 백엔드 하나만** 계약에 걸려 있다 — 정작 다른 구현과 답이 같은지 물어야
할 원격 어댑터가 빠져 있다. 그 자리를 대신하는 `RemotePeerMemoryWireTest` 는 *보내고 받는 것*이
맞는지만 보고, *답의 뜻이 다른 백엔드와 같은지*는 보지 못한다.

**함정 하나** — `aimon-memory` 에는 이미 `:aimon-memory-testkit` 이라는 **동명의 내부 프로젝트**가
있다(`settings.gradle.kts:15`, 그쪽은 픽스처와 스텁). 이름만 같고 아무 관계가 없으므로 외부
아티팩트는 **반드시 GAV 로** 참조한다. `project(":aimon-memory-testkit")` 로 쓰면 조용히 엉뚱한
것을 집는다.

**어디** (2026-09-05)
- 절차: `aimon-memory` `docs/adr/0007-aimon-core-boundary.md` "Contract verification" 절 1~4단계
- 이름 충돌: `aimon-memory` `settings.gradle.kts:15`
- 선례로 쓸 하네스: `aimon-memory` `RemotePeerMemoryWireTest`

**언제 다시 볼까** — M-3 이 닫히는 즉시. 그 전에는 좌표가 해석되지 않는다.

---

### M-3 — 코어 릴리스는 `0.3.0` 이어야 하고, testkit 배포가 거기 실려 나간다 · **열림**

**무엇** — `aimon-memory-{postgres,mongodb}` 제거와 `aimon-memory-testkit` 배포를 담은 릴리스를
`0.3.0` 으로 낸다.

**왜 — 관측 가능한 결과**

배포 모듈 3개가 사라지는 변경이다. `docs/project/api-stability.md` §1 이 패치 올림에 대해
*"소스·바이너리 호환"* 을 약속하므로 `0.2.5` 로 낼 수 없다 — 그렇게 내면 그 문서가 자기 규칙을
어기는 예시를 자기 안에 싣게 된다.

그리고 **두 일은 분리할 수 없다.** 저장소 전체가 버전 하나를 공유하고(`allprojects { version = … }`)
릴리스 스크립트가 `publishAllPublicationsToMavenCentralRepository` 로 한 번에 올리므로,
"testkit 만 먼저, 제거는 나중에" 가 성립하지 않는다.

이미 나간 `at.aimon.core:aimon-memory-{file,mongodb,postgres}:0.2.4` 는 Central 에 영구히 남는다.
기존 사용자가 당장 깨지지는 않고, 새 버전이 나오지 않을 뿐이다.

**어디** (2026-09-05)
- 정책: `docs/project/api-stability.md` §1 · §4
- 버전 원천: `gradle.properties:2` (`VERSION_NAME`)
- 일괄 배포: `scripts/release.sh`

**언제 다시 볼까** — 이 브랜치가 main 에 들어간 뒤 첫 릴리스 시점.

---

### M-4 — `aimon-memory` 는 로컬 전용이고 LICENSE 가 없다 · **열림 · 결정 대기**

**무엇** — 그 저장소를 원격에 올릴지 정하고, 올린다면 LICENSE 파일을 **먼저** 넣는다.

**왜 — 관측 가능한 결과**

`aimon-memory` 에는 git remote 가 없고 GitHub 저장소도 없다(2026-09-05 확인:
`gh repo view kangwoo/aimon-memory` → `Could not resolve to a Repository`). 그래서 그쪽 작업은
PR 도 이슈도 만들 수 없고, M-1·M-2 가 이 문서에 적히는 이유가 그것이다.

그리고 그 저장소의 POM 은 **없는 파일을 가리킨다.** `gradle.properties:14-16` 이 Apache-2.0 을
선언하는데 `LICENSE` 파일이 없다. 그 사실이 같은 파일 `:12` 에 스스로 적혀 있다 —
*"NOTE: the repository still has no LICENSE file — add one before anything is published"*.
`aimon-memory-client` 는 배포 대상이므로 이것은 배포 전 차단 항목이다.

**어디** (2026-09-05)
- `aimon-memory` `gradle.properties:12` · `:14-16`
- `aimon-memory` 저장소 루트에 `LICENSE` 없음

**언제 다시 볼까** — `aimon-memory-client` 를 처음 배포하려 할 때. 그보다 먼저 M-2 를 하려면
원격 여부는 상관없다(로컬에서도 배선은 된다).

---

### M-5 — 배포되는 testkit 이 Spring Boot 플랫폼 전체를 `api` 로 내보낸다 · **열림 · 의도된 선택**

**무엇** — 이 선택을 유지할지, JUnit 버전을 카탈로그로 옮기고 플랫폼을 내릴지 다시 본다.

**왜 — 관측 가능한 결과**

`modules/aimon-memory-testkit/build.gradle.kts:44` 의 `api(platform(libs.spring.boot.dependencies))`
가 발행 metadata 의 `apiElements`·`runtimeElements` 양쪽에 실린다. 소비자의
`testImplementation` 컨피규레이션에 Spring Boot 3.5.16 의 의존성 관리 전체가 따라 들어가
Jackson·Logback·Netty·Testcontainers 버전이 조용히 정렬된다. 배포 21개 중 플랫폼을 `api` 로
내보내는 모듈은 **이것 하나뿐**이다.

**이것은 결함이 아니라 결정이다.** 빼면 좁아지는 것이 아니라 **깨진다** — `junit-jupiter` 가
버전 없이 발행되고 이 빌드의 유일한 버전 원천이 그 플랫폼이기 때문이다. 카탈로그에 핀을 박으면
저장소에 두 번째 JUnit 버전이 생겨 다른 모듈이 테스트하는 버전과 드리프트한다. 근거는 그 자리
주석에 기록되어 있다.

그럼에도 항목으로 남기는 이유는 `api-stability.md` §5 가 이것을 **one-way door** 로 만들기
때문이다 — 첫 릴리스 뒤에는 다음 마이너까지 되돌릴 수 없다.

**어디** (2026-09-05)
- `modules/aimon-memory-testkit/build.gradle.kts:44` 와 그 위 주석
- 정책: `docs/project/api-stability.md` §5

**언제 다시 볼까** — M-3 의 태그 직전 마지막 판단. 또는 소비자가 실제로 버전 충돌을 겪었을 때.

---

### M-6 — 도커 티어를 서술하는 문장이 문자 그대로는 참이 아니다 · **열림 · 기존 부정확성**

**무엇** — *"these are the ONLY tests there are"* / *"sole verification"* 을 실제와 맞추거나,
톤을 낮춘다.

**왜 — 관측 가능한 결과**

그 문장은 다섯 모듈의 테스트가 **전부** `@Tag("docker")` 라고 말하지만 실측은 그렇지 않다
(2026-09-05): gridfs 21개 중 19, s3 18개 중 16, session-redis 15개 중 9, session-postgres
11개 중 8, session-mongodb 14개 중 7.

**이번 변경이 만든 것이 아니다.** 원문이 일곱 모듈에 대해 같은 주장을 하고 있었고 이번에는
목록과 숫자(seven→five)만 고쳤다. 세 회차 리뷰가 모두 "고치지 말 것"으로 판단했으므로
그 판단을 뒤집으려면 근거가 필요하다 — 같은 문단이 인용하는 커버리지 수치가 이미 "전부"가
아님을 보여 주므로, 고친다면 `sole verification` → `the verification that matters` 정도의
톤 조정이 정확하다.

**어디** (2026-09-05)
- `.github/workflows/build.yml:143-144`
- `scripts/release.sh` 의 같은 문단

**언제 다시 볼까** — 도커 티어 게이트를 다시 손댈 때. 단독으로 열 일은 아니다.

---

## 2. 닫힌 것은 여기 없다

이 작업이 남긴 항목은 위 여섯이 전부다. 작업 중 나온 리뷰 지적 23건(1차 10 · 2차 8 · 3차 5)은
22건이 반영되었고 1건(M-6)이 위로 올라왔다. 반영 내역은 PR 과 커밋 히스토리에 있으며,
**이 문서는 그것을 되풀이하지 않는다** — 여기 있는 것은 열린 것뿐이다.

---

## 관련 문서

- [`../design/memory/pluggable-memory-backend.md`](../design/memory/pluggable-memory-backend.md) — 메모리 백엔드 seam 의 설계 근거
- [`../project/api-stability.md`](../project/api-stability.md) — M-3 · M-5 가 근거로 삼는 약속
- [`../migration/rename-maps.md`](../migration/rename-maps.md) — `at.aimon.memory.file` → `at.aimon.core.memory.file`
- [`multi-instance-readiness.md`](multi-instance-readiness.md) — 메모리 축의 "분산 구현" 칸이 이 작업으로 비었다
