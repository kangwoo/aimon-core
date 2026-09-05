# 메모리 백엔드 경계 — 등록 항목 7건 (열림 3 · 닫힘 4)

`aimon-memory-{postgres,mongodb}` 를 제거하고 `aimon-memory-file` 을 코어로 병합하면서,
분산 메모리의 자리를 별도 저장소 `aimon-memory`(Postgres+pgvector 서비스)로 넘겼다.
그 작업이 끝나고 **남은 것**을 여기 모은다.

일곱 중 셋(M-1 · M-2 · M-4)은 **다른 저장소에서** 해야 하는 일이다. 그 저장소에 이슈 트래커가
없어서(M-4 참조) 여기 적는다 — 열린 항목의 정본은 이 디렉토리이지 저장소 경계가 아니다.

출처는 2026-09-04 ~ 09-05 의 작업과 그에 대한 3회차 독립 리뷰다.
줄 번호는 **2026-09-05 확인**이며 드리프트한다.

---

## 0. 먼저 — 강제되어 있던 순서를 로컬 스냅샷이 끊었다

**등록 시점(2026-09-05 오전)의 판단은 이랬다.**

```
M-3  aimon-core 0.3.0 릴리스
  └→ M-2  aimon-memory 가 testkit 을 GAV 로 당겨 RemotePeerMemory 를 스위트에 건다
      └→ M-1  RemoteSearcher 가 세션 id 를 거절하도록 고친다  ← 여기서 터진다
```

**그 화살표는 참이었지만 릴리스가 유일한 경로라는 전제가 거짓이었다.**
`publishToMavenLocal -PVERSION_NAME=0.3.0-SNAPSHOT` + 소비자 쪽 `mavenLocal()` 이면
좌표가 풀린다. `-P` 로 덮으므로 `gradle.properties` 는 건드리지 않는다 — 이 저장소는
그 실행 동안 **파일 하나도 바뀌지 않았다**(`git status --porcelain` 빈 출력로 확인).

그래서 M-1 과 M-2 는 릴리스를 기다리지 않고 **먼저 닫혔다**(§1). M-3 은 여전히 열려 있고,
이제 **비계 회수**라는 의무가 하나 붙었다.

이 항목이 남기는 교훈은 순서가 아니라 그 아래다 — **"A 없이는 B 를 못 한다" 는 문장을 만나면
A 가 정말 유일한 경로인지 먼저 묻는다.** 여기서는 아니었고, 확인 비용은 한 번의
`publishToMavenLocal` 이었다.

**그리고 그 우회로에는 대가가 있었다.** 첫 배선은 `aimon-memory` 를 fresh clone 에서 컴파일되지
않는 상태로 만들었고, 그 사실은 반나절 뒤에야 다른 작업에서 드러났다 — 배선한 머신에는 스냅샷이
이미 있었기 때문이다. 릴리스를 우회하는 것은 **가능**했지만 공짜는 아니었고, 무엇을 지불했는지는
M-3 의 "정정" 에 적었다.

---

## 1. 항목

### M-1 — `RemotePeerMemory` 가 계약을 지키지 않으며, 지금은 그 사실이 아무도 깨뜨리지 않는다 · **닫힘 (2026-09-05)**

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

**닫으며 (2026-09-05)** — 근거가 실측으로 확인된 드문 경우다. 배선만 하고 소스는 손대지 않은
상태에서 `sessionIdIsRejectedRatherThanIgnored` 가 위에 적힌 그대로 실패했다
(*"Expecting code to raise a throwable"*). `RemoteSearcher.search()` 에 거절 분기를 넣고,
현재 동작을 정책으로 옹호하던 javadoc 두 곳(tier 항목과 클래스 javadoc)의 입장을 뒤집었다.

**그런데 같은 실행에서 예상에 없던 두 번째 실패가 나왔다** — 그것이 이 항목의 진짜 소득이다.

`recordingAssignsAnIdentity` 가 실패하면서 남긴 두 문자열이 **똑같아 보이는데 달랐다**.
`PeerView.toString()` 은 `workspaceId:TYPE:principalId` 만 찍지만 `equals()` 는 `Principal` 전체를
보고, `Principal.equals()` 는 **displayName 까지** 본다. 스위트는 `Principal.user("alice", "Alice")`
를 넘기는데 어댑터는 응답의 peer id 로부터 `Principal.user("alice")` 를 재구성해 돌려주고 있었다 —
**호출자가 준 subject 와 같지 않은 subject 를 돌려주면서 같아 보이게** 하고 있었다. M-1 과 정확히
같은 종류의 결함이고, 어댑터가 잃어버린 정보를 이미 손에 쥔 채 버리고 있었다는 점만 다르다.

수정은 좁게 했다 — `toObservation` 에 호출자의 뷰를 받는 오버로드를 더하고 **돌아온 peer id 가
일치할 때만** 그것을 쓴다(서버가 다른 peer 를 답했다면 그건 보고할 사실이므로 덮지 않는다).
읽기 경로(SEARCH·SNAPSHOT)는 응답이 유일한 출처이므로 그대로다.

**이 결함은 정적 분석으로는 나오지 않았다.** 세 회차 리뷰도, 이 백로그의 등록도 놓쳤다.
계약 스위트를 *읽는 것*과 *돌리는 것*의 차이가 이 한 건이며, M-2 가 존재한 이유가 그것이다.

이후 21개 전부 통과, **skip 0**(다섯 티어가 모두 실제로 실행됨). 기존
`RemotePeerMemoryWireTest` 19개도 초록이므로 티어 신호는 바뀌지 않았다.

---

### M-2 — `RemotePeerMemory` 가 다섯 티어 계약 스위트에 걸려 있지 않다 · **닫힘 (2026-09-05)**

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

**닫으며 (2026-09-05) — "언제 다시 볼까" 가 틀렸다.** 이 항목은 M-3 을 기다릴 필요가 없었다.
로컬 스냅샷(§0)으로 좌표가 풀렸고, `RemotePeerMemoryContractTest` 가
`AbstractPeerMemoryContractTest` 를 상속해 ephemeral 포트 스텁 서버를 향하는 `newBackend()` 를
구현한다. 동명 프로젝트 함정은 카탈로그 GAV 항목으로 피했다.

**등록 시점에 이 항목이 스스로 적어 둔 재검토 트리거가 그것을 영원히 막을 뻔했다** —
`backlog/README.md` 규칙 둘이 말하는 그 실패 모양이다. 트리거를 쓸 때는 "무엇이 끝나야 하는가"
보다 **"지금 막고 있는 것이 정확히 무엇인가"** 를 적는 편이 낫다. 여기서 막고 있던 것은
릴리스가 아니라 *해석 가능한 좌표* 였고, 그건 릴리스 말고도 얻는 길이 있었다.

계약 스위트가 붙자마자 값을 냈다 — M-1 이 실측으로 확인되었고, **아무도 예상하지 못한
`PeerView` 신원 왕복 결함**이 같은 실행에서 드러났다(M-1 의 "닫으며" 참조).

**남은 것 하나** — 이 배선은 임시 비계 위에 서 있다. 회수 의무는 M-3 이 지며, 비계의 모양은
등록 당일 저녁에 한 번 바뀌었다(M-3 의 "정정" 참조).

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
PR #24 는 `c246ac4` 로 머지되었으므로 **지금이 그 시점이다.**

**추가된 의무 (2026-09-05) — 릴리스는 비계 회수를 동반한다.**

M-1·M-2 가 로컬 스냅샷 위에서 닫혔으므로(§0) `aimon-memory` 에 되돌릴 것이 있다.

**정정 (2026-09-05 저녁) — 이 절이 처음 적은 표는 반나절 만에 틀렸다.**

처음 적은 것은 `aimonCore` 를 `0.3.0-SNAPSHOT` 으로 두고 릴리스 후 `0.3.0` 으로 바꾸라는 것이었다.
**그 상태는 저장소를 깨뜨리고 있었다.** 한 좌표를 스냅샷으로 고정하면 `aimon-memory-client` 가
**aimon-core 본체까지** 로컬 `~/.m2` 에서 찾는다. `publishToMavenLocal` 을 해 본 적 없는 머신 —
**모든 fresh clone 과 모든 CI 러너** — 에서 `compileJava` 가 실패한다.

여기서 놓친 이유가 기록할 값이 있다: 배선한 머신에는 이미 스냅샷이 있었고, `checkAll` 통과만 보고
"된다" 고 판정했다. **아무도 fresh clone 을 시험하지 않았다.** 로컬 스냅샷을 쓰는 작업은 그 성질상
"내 머신에서 된다" 가 가장 믿을 수 없는 증거인데, 그것을 증거로 썼다.

`aimon-memory` 쪽에서 별도로 고쳤다(`9bb5dc9`). 지금 트리의 실제 모양은 이렇다.

| 파일 | 지금 | `0.3.0` 이 Central 에 오른 뒤 |
|---|---|---|
| `gradle/libs.versions.toml` | `aimonCore = "0.2.4"` (Central) + `aimonTestkit = "0.3.0-SNAPSHOT"` (로컬 전용) | 둘을 하나로 합친다 — `aimonCore = "0.3.0"`, `aimonTestkit` 삭제 |
| `settings.gradle.kts:19` · `build.gradle.kts:28` | `mavenLocal()` | 삭제 |
| `modules/aimon-memory-client` | 계약 스위트가 `contractTest` 소스셋에 격리 | 그대로 두어도 되고, `test` 로 되돌려도 된다 |

**그리고 이제는 조용히 지나간다 — 앞서 적은 것과 반대다.** 처음에는 `verifyCoreIsReleased` 의
`-SNAPSHOT` 거절이 회수를 강제한다고 적었지만, 좌표가 갈리면서 `aimonCore` 는 릴리스된 `0.2.4` 를
가리키므로 **그 가드는 이제 통과한다.** 회수를 잊어도 아무것도 실패하지 않는다.

대신 조용해지는 것은 계약 스위트 쪽이다. 지금 `contractTest` 티어는 **testkit 을 해석할 수 있는
머신에서만** 돈다 — 즉 `publishToMavenLocal` 을 한 개발자 머신 하나뿐이고, CI 에서는 언제나 skip
된다(그 build 파일이 스스로 그렇게 적는다). `0.3.0` 이 나온 뒤 좌표를 합치지 않으면 **그 상태가
영구화된다.**

그러면 이 작업 전체가 되돌아온다. testkit 을 배포 대상으로 승격시킨 이유(M-2)가 *"원격 백엔드가
계약에 걸려 있지 않다"* 였는데, 합치지 않으면 걸려 있되 **아무 데서도 돌지 않는** 상태가 된다.
전보다 나쁘다 — 전에는 안 걸려 있다는 것이 보였고, 지금은 초록으로 보인다.

**그러므로 회수는 정리가 아니라 M-2 를 실제로 닫는 마지막 단계다.**

**태그 직전 판단은 하나 줄었다.** M-5 는 원래 "릴리스 직전에 한 번 보라" 로 남겨 둔 항목이었으나
릴리스 전에 닫혔다 — 배포 표면에서 Spring Boot 플랫폼이 빠졌으므로 그 one-way door 는 이제
닫을 것이 없다.

---

### M-4 — `aimon-memory` 는 로컬 전용이다 (LICENSE 는 해소) · **열림 · 결정 대기**

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

**절반 처리 (2026-09-05) — LICENSE 는 들어갔다.** `aimon-memory` 루트에 Apache-2.0 전문이
추가되었고(`dae854b`), `gradle.properties:12` 의 *"the repository still has no LICENSE file"* 주석도
사실에 맞게 다시 썼다. 배포 전 차단 항목이던 쪽은 해소되었다.

**열린 채 남는 것은 원격 저장소 여부 하나다.** 그것은 공개 범위에 대한 결정이므로 여기서 내리지
않는다. 그때까지 그 저장소의 커밋은 로컬 `main` 에만 쌓이고, 그쪽 몫의 열린 항목은 계속 이 문서가
받는다.

---

### M-5 — 배포되는 testkit 이 Spring Boot 플랫폼 전체를 `api` 로 내보낸다 · **닫힘 (2026-09-05)**

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

**측정됨 (2026-09-05) — 결정은 아직 열려 있다.** M-2 로 소비자가 생겼으므로 이 항목이 "조용히
정렬된다" 고만 적어 두었던 것을 실제로 잴 수 있게 되었다. 방법은 init script 프로브 —
`:aimon-memory-client:testCompileClasspath` 를 testkit 있음/없음 두 상태로 해석해 diff 했고,
**빌드 파일은 한 줄도 바꾸지 않았다.**

**이 소비자(`aimon-memory`)가 치르는 비용: 0.**

```
with testkit: 54 modules · without: 52
ADDED:   at.aimon.core:aimon-memory-testkit, org.springframework.boot:spring-boot-dependencies (플랫폼 노드)
REMOVED: 없음
VERSION CHANGES: 없음
```

`aimon.java-conventions` 가 이미 `spring-boot-dependencies:3.5.16` 을 import 하므로 정렬할 것이
없다. 부수 관찰 하나 — testkit POM 은 `assertj-core:3.27.7` 을 선언하는데 이 저장소는 `3.27.6` 으로
해석한다. **정렬 방향이 오히려 반대**이고, 여기서 플랫폼 export 는 아무 힘도 쓰지 못한다.

**그러나 "영향 없음" 은 이 소비자의 성질이지 플랫폼의 성질이 아니다.** 같은 프로브를 Spring Boot
**3.4.0** 을 쓰는 소비자에 대해 돌리면 16개 좌표가 움직인다.

```
spring-boot-dependencies : 3.4.0        → 3.5.16     ← 소비자가 직접 선언한 플랫폼 버전
jackson-databind/core    : 2.18.1       → 2.21.4
jackson-annotations      : 2.18.1       → 2.21
logback-classic/core     : 1.5.12       → 1.5.34
netty-common             : 4.1.115.Final→ 4.1.135.Final
byte-buddy               : 1.15.10      → 1.18.3
assertj-core             : 3.26.3       → 3.27.7
junit-jupiter(+api,params,bom) : 5.11.3 → 5.12.2
junit-platform-commons   : 1.11.3       → 1.12.2
slf4j-api                : 2.0.16       → 2.0.18
```

첫 줄이 핵심이다 — **소비자가 자기 빌드에 못 박은 플랫폼 버전 자체가 끌어올려진다.** Gradle 이
플랫폼 모듈에도 최고 버전 충돌 해결을 적용하기 때문이고, testkit 을 *테스트* 의존성으로 더했다는
이유만으로 그 소비자의 Spring Boot 관리 전체가 바뀐다.

**이 숫자는 "무엇을 잠그는가" 에 대한 답이지 "잠글 것인가" 에 대한 답이 아니다.** 결정은
M-3 의 태그 직전에 한 번, 사람이 한다.

**닫으며 (2026-09-05) — 선택지가 둘이 아니라 셋이었다.**

이 항목은 "그대로 두거나 / `compileOnly` 로 내리거나 / 카탈로그에 핀을 박거나" 셋을 적었고,
뒤의 둘이 각각 "소비자 해석 실패" 와 "저장소에 두 번째 JUnit 버전" 이라는 대가를 갖는다는
이유로 **현상 유지가 가장 좁은 실수**라고 결론지었다. 그 저울질은 맞았지만, 셋 다 **Spring Boot
플랫폼 안에서만** 답을 찾고 있었다.

사용자가 물은 것은 그 바깥이었다 — *"근데 Spring Boot 가 꼭 필요한 것이니?"*

**필요 없었다.** 확인해 보니 testkit 소스가 import 하는 것은 `org.junit.jupiter.api.*` 다섯 종과
`org.assertj.core.api.Assertions` 가 전부이고 Spring 은 한 줄도 없다. 그리고 AssertJ 는 카탈로그에
이미 자기 버전이 있다(`assertj = "3.27.7"`). 즉 그 플랫폼이 실제로 정하고 있던 것은
**`junit-jupiter` 의 버전 하나**였고, 그것 하나를 위해 ~1,400개 관리 좌표가 배포 표면에 실려
나가고 있었다.

그래서 네 번째 선택지가 있었다 — **`api(platform(libs.junit.bom))`**. JUnit 좌표만 지배한다.

| Spring Boot 3.4.0 소비자 | 이전 | 이후 |
|---|---|---|
| `spring-boot-dependencies` | **3.4.0 → 3.5.16** (자기 플랫폼이 납치됨) | **3.4.0 유지** |
| `logback-classic` · `slf4j-api` | 이동 | **유지** |
| jackson ×4 · netty | 이동 | **관여 안 함** |
| JUnit ×5 · assertj · byte-buddy | 이동 | 이동 |
| **합계** | **16건** | **7건** |

남은 7건은 JUnit 5 + AssertJ 1 + byte-buddy 1(AssertJ 의 전이 의존)이며, **JUnit 계약 테스트
스위트가 정당하게 요구할 수 있는 것**이다. Jackson 과 Logback 을 옮기는 것과는 성격이 다르다.

**카탈로그 주석의 논리는 폐기되지 않고 범위가 좁아졌다.** *"JUnit carries no version here on
purpose"* 는 열네 모듈에 대해 여전히 참이다 — 그들은 `spring-boot-starter-test` 로 JUnit 을 받고,
거기 숫자를 쓰면 정말로 dead letter 가 된다. 참이 아니게 된 것은 **main 소스에서 JUnit 에 컴파일하고
그 좌표를 배포하는 모듈**에 대해서다. 그 구분을 주석에 적었고, 새 숫자가 *이 빌드 테스트의 실효
버전이 아니라 testkit 이 발행하는 제약*이라는 것도 함께 적었다.

**이 항목이 남기는 교훈** — 선택지를 세 개 적어 두면 그 셋 안에서 고르게 된다. 셋이 모두 같은
전제(여기서는 "버전 원천은 Spring Boot 플랫폼")를 공유하고 있으면, 목록이 길수록 오히려 전제가
검사받지 않는다. M-5 는 **그 전제를 명시적으로 적지 않았기 때문에** 반박당하지 않았다.

검증: `checkAll` 통과, 생성 POM 이 `junit-bom:5.12.2` 를 import 하고 `spring-boot-dependencies` 는
없음, 스냅샷 재배포 후 `aimon-memory` 의 `checkAll` 도 통과(계약 스위트 포함).

---

### M-6 — 도커 티어를 서술하는 문장이 문자 그대로는 참이 아니다 · **닫힘 (2026-09-05)**

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
- `.github/workflows/build.yml:142-146` (게이트 문단) · `:216-220` (커버리지 문단)
- `scripts/release.sh:142-147` — 같은 주장을 하는 자매 문단

**언제 다시 볼까** — 도커 티어 게이트를 다시 손댈 때. 단독으로 열 일은 아니다.

**닫으며 (2026-09-05) — 착수하니 숫자부터 틀렸다.**

세 회차 리뷰가 모두 "두라"고 했고 이 항목도 그렇게 등록되었으나, 사용자가 고치기로 정했다.
그런데 고치려고 다시 세었더니 **위에 적힌 실측값 자체가 틀렸다.** `*TestSupport` 는 테스트
클래스가 아닌데 `*Test*` 로 세는 바람에 분모에 섞여 있었다. 리뷰어의 집계와 이 항목의 등록이
같은 실수를 물려받았다.

| 모듈 | 등록된 값 | 실제 (`*Test.java`, Support 제외) |
|---|---|---|
| `aimon-filesystem-gridfs` | 19 / 21 | **19 / 20** |
| `aimon-filesystem-s3` | 16 / 18 | **16 / 17** |
| `aimon-session-redis` | 9 / 15 | **9 / 14** |
| `aimon-session-postgres` | 8 / 11 | **8 / 10** |
| `aimon-session-mongodb` | 7 / 14 | **7 / 13** |

**결론은 뒤집히지 않았다** — 여전히 "전부"가 아니다. 다만 남은 것이 무엇인지가 분명해졌고,
그게 고칠 문구를 정했다. 비도커 클래스는 모듈당 1~6개이며 **전부 코덱 왕복과 동결 이름 단언**
(`SessionSignalCodecTest`, `MongoSchemaFreezeTest`, `RedisKeyPrefixFreezeTest` 류)이다.
**하나도 커넥션을 열지 않는다.**

그래서 고친 방향은 톤 낮추기가 아니라 **주장을 정확히 좁히기**다 — "there are no other tests" 가
아니라 "these are the only tests that reach the backend". 원래 논거(드라이버·스키마·와이어가
CI 에서 한 번도 돌지 않은 채 배포되고 있었다)는 그대로 서고, 이제 참이기까지 하다.

커버리지 문단도 함께 고쳤다. 다섯 수치가 6.2%~28.0% 로 벌어져 있는 것이 **남은 것의 모양**임을
설명하도록 했다 — session-mongodb 는 코덱·동결 6개를 남겨 28.0%, session-postgres 는 2개를
남겨 6.2%. 그 편차 자체가 "every test is docker" 가 거짓이라는 증거였는데 같은 문단에 나란히
있으면서 아무도 대조하지 않았다.

`ReleaseGateMatchesCiGateTest` 는 gradle **task 목록**만 대조하므로 산문 수정에 영향받지 않는다
(수정 전 확인).

---

### M-7 — 계약 스위트가 `PeerView` **전체 동등성**을 요구해 원격 백엔드에 부담을 지운다 · **열림 · 스위트 저자 결정**

**무엇** — `recordingAssignsAnIdentity` 가 물으려는 것이 "같은 peer 인가" 인지 "같은 `PeerView`
객체인가" 인지 정하고, 전자라면 단언을 좁힌다.

**왜 — 관측 가능한 결과**

M-1 을 닫는 과정에서 이 요구가 어댑터를 한 번 넘어뜨렸다(M-1 의 "닫으며"). 단언은
`assertThat(stored.getSubject()).isEqualTo(SUBJECT)` 이고, `Principal.equals()` 가
**displayName 까지** 비교한다. 그런데 `PeerView.toString()` 은 `workspaceId:TYPE:principalId` 만
찍으므로 실패 메시지의 두 문자열이 **글자 그대로 동일**하다. 진단이 어렵다는 것 자체가 비용이다.

부담은 이 어댑터 하나에 그치지 않는다. **peer id 만 왕복시키는 모든 원격 백엔드**가 같은 자리에서
같은 방식으로 넘어진다 — 응답에서 `PeerView` 를 재구성하는 한 displayName 은 복원할 수 없기
때문이다. `aimon-memory` 는 호출자가 준 뷰를 되쓰는 방식으로 피했지만, 그것은 *쓰기* 경로에서만
가능한 회피다. 읽기 경로에서 같은 단언을 요구하는 계약이 나중에 추가되면 회피할 방법이 없다.

**계약 위반은 아니다.** 스위트가 그렇게 쓰여 있고 기본 백엔드는 통과한다. 물어야 할 것은
**그 단언이 재고자 한 것이 무엇이었는가** 이며, 그 답은 스위트를 쓴 쪽이 갖고 있다.

**어디** (2026-09-05)
- 단언: `modules/aimon-memory-testkit/.../AbstractPeerMemoryContractTest.java:463`
  (`recordingAssignsAnIdentity`, `:456`)
- 동등성: `modules/aimon-core/.../base/Principal.java:205` (`equals`)
- `toString` 이 감추는 자리: `modules/aimon-core/.../memory/PeerView.java:69`
- 실제로 넘어진 사례: `aimon-memory` `RemotePeerMemory` 의 `toObservation` 오버로드

**언제 다시 볼까** — 두 번째 원격 백엔드가 스위트에 붙을 때. 그때도 같은 자리에서 넘어지면
그것은 어댑터의 문제가 아니라 계약의 문제다.

---

## 2. 이 문서가 담지 않는 것

작업 중 나온 리뷰 지적 23건(1차 10 · 2차 8 · 3차 5)은 22건이 반영되었고 1건(M-6)이 위로 올라왔다.
반영 내역은 PR 과 커밋 히스토리에 있으며 **이 문서는 그것을 되풀이하지 않는다.**

닫힌 항목(M-1 · M-2)은 지우지 않고 "닫으며" 를 붙여 남긴다 — `README.md` 규칙 둘이 요구하는
형식이다. 둘 다 **착수해 보니 등록 시점의 판단과 달랐던 것**이 있었고, 그 어긋남이 다음 사람에게
쓸모 있는 부분이다:

- **M-1** — 근거는 맞았다(드문 경우). 대신 같은 실행에서 **아무도 예상하지 못한 두 번째 결함**이
  나왔다. 정적 분석과 실행의 차이가 그 한 건이다
- **M-2** — 근거는 맞았지만 **재검토 트리거가 틀렸다.** "M-3 이 닫히면" 이라고 적었는데 실제로
  막고 있던 것은 릴리스가 아니라 해석 가능한 좌표였고, 그건 다른 길로도 얻어진다

---

## 관련 문서

- [`../design/memory/pluggable-memory-backend.md`](../design/memory/pluggable-memory-backend.md) — 메모리 백엔드 seam 의 설계 근거
- [`../project/api-stability.md`](../project/api-stability.md) — M-3 · M-5 가 근거로 삼는 약속
- [`../migration/rename-maps.md`](../migration/rename-maps.md) — `at.aimon.memory.file` → `at.aimon.core.memory.file`
- [`multi-instance-readiness.md`](multi-instance-readiness.md) — 메모리 축의 "분산 구현" 칸이 이 작업으로 비었다
