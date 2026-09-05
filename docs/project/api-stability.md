# API 안정성 정책 (API Stability)

AIMON Core 가 **무엇을 약속하고 무엇을 약속하지 않는지** 정의한다. 이 문서의 대상은
Maven Central 에 배포되는 모듈을 의존성으로 쓰는 사람이다.

현재 버전은 `0.x` 다. 아래 내용은 그 사실에서 대부분 따라 나온다.

---

## 1. 한 줄 요약

> **`0.x` 동안 마이너 버전 올림(`0.2` → `0.3`)은 호환성을 깨뜨릴 수 있다.**
> 패치 올림(`0.2.2` → `0.2.3`)은 깨뜨리지 않는다.

Semantic Versioning 은 `0.x` 를 "아직 안정을 약속하지 않은 구간"으로 규정한다. 이 프로젝트는 그것을
문자 그대로 쓴다 — 지어낸 예외 조항이 없다는 뜻이다.

| 버전 변화 | 약속 |
|-----------|------|
| `0.2.2` → `0.2.3` (patch) | **소스·바이너리 호환**. 버그 수정과 내부 변경만 |
| `0.2` → `0.3` (minor) | **깨질 수 있다.** 변경은 `CHANGELOG.md` 에 전부 기록된다 |
| `0.x` → `1.0` | §6 의 조건이 충족될 때 |

의존성을 고정할 때 `0.2.+` 같은 열린 범위를 쓰지 않기를 권한다. 정확한 버전을 박고,
올릴 때 CHANGELOG 를 읽는 편이 이 구간에서는 더 싸다.

---

## 2. 무엇이 공개 API 인가

**패키지 위치가 답이다.** 애노테이션이 아니다.

| 패키지 모양 | 지위 |
|-------------|------|
| `at.aimon.core.<domain>` | **공개 API** — 인터페이스, 도메인 타입, 불변 값 객체 |
| `at.aimon.core.<domain>.impl` | **내부 구현** — 예고 없이 바뀐다 |
| `at.aimon.core.agent.orca` | **공개 SPI** — 외부 모듈이 구현하는 확장점 |
| 그 외 모듈의 `at.aimon.<module>` | 해당 모듈의 공개 API |

이 경계는 문서상의 권고가 아니라 **빌드가 강제한다**. `PackageDependencyArchitectureTest`
(`aimon-core`) 가 `*.impl` 패키지를 그 도메인 트리 밖에서 import 하는 것을 막는다. 즉
`at.aimon.core.filesystem.impl` 을 다른 곳에서 쓰려고 하면 리뷰가 아니라 빌드가 먼저 거절한다.

현재 `impl` 로 격리된 도메인은 일곱 개다 — `tracing`, `shell`, `agent`, `filesystem`, `workflow`,
`hook`, `hook.rewake`.

> 이 프로젝트에는 아직 `@Experimental` · `@Beta` · `@Internal` 애노테이션이 **없다**. 만들지 않은
> 이유는 패키지 경계가 이미 그 일을 하고 있고, 강제되지 않는 애노테이션은 지켜지지 않는 표식이 되기
> 때문이다. 패키지로 가를 수 없는 실험적 표면이 생기면 그때 도입하고, **도입과 동시에 ArchUnit 규칙을
> 함께 넣는다.**

### 공개 API 가 *아닌* 것

- `*.impl` 아래의 모든 것
- 테스트 소스, `aimon-filesystem-testkit` 이 노출하는 계약 테스트의 내부 구조
- `aimon-cli` 전체 — 애플리케이션이지 라이브러리가 아니며 배포 대상도 아니다
- `samples/` 아래 모듈 — 예제이며 배포하지 않는다
- 로그 메시지의 문구, 예외 메시지의 문구
- **저장 포맷의 자바 식별자와 와이어 키가 어긋나 있는 자리** — 예를 들어 자바 타입은 `Session*` 인데
  Mongo 컬렉션은 `conversation_*` 다. 이것은 실수가 아니라 **의도적으로 동결된** 경계이며, 자바 이름이
  바뀌어도 저장된 이름은 바뀌지 않는다 (§4)

---

## 3. 이름이 바뀔 때

`0.x` 구간에서 이 프로젝트는 이름을 실제로 바꿔 왔다. 최근 두 번은 둘 다
**이름이 수명을 잘못 말하고 있었기** 때문이다:

- `AgentExecutionContext` → `AgentRuntime`
- `Conversation` → `SessionRecord`, `AgentSession` → `LiveSession`

배경은 [`../overview/scope-model.md` §7](../overview/scope-model.md) 에 있다.

이런 변경은 `CHANGELOG.md` 에 기록하고, **옛 이름 ↔ 새 이름 매핑표**는
[`../migration/rename-maps.md`](../migration/rename-maps.md) 에 더한다. 릴리스 노트에서
"rename" 세 글자만 보고 grep 으로 알아내야 하는 상황을 만들지 않는다는 뜻이다.

---

## 4. 명시적으로 동결된 것 — 이름보다 강한 약속

자바 식별자보다 **더 강하게 보장되는** 표면이 있다. 저장된 데이터와 프로세스 간 계약이다.

| 표면 | 정책 |
|------|------|
| 영속 필드 이름, 컬렉션·테이블·채널 이름 | **동결.** 리팩터가 건드리지 않는다 |
| 툴 컨텍스트의 와이어 키 (`"conversationId"` 등) | **동결** |
| Redis 키 prefix, Postgres DDL | **동결** |

`aimon-session-*` 백엔드로 이미 데이터를 쌓아 둔 쪽에게는, 자바 API 보다 이쪽이
중요하다. 그래서 자바 이름이 `Session*` 로 바뀐 뒤에도 저장된 이름은 `conversation_*` 로 남아 있다 —
**어긋나 보이는 것이 정상이고, 그것이 약속의 증거다.**

깨야 할 일이 생기면 마이그레이션 경로 없이는 하지 않는다.

### 4.1 표면이 **없어지는** 것은 그 약속을 깨는 것이 아니다

이 절은 한때 `aimon-memory-*` 백엔드의 DDL 과 컬렉션 이름도 이름으로 지목해 동결했다. 그
`aimon-memory-{mongodb,postgres}` 두 모듈은 **제거되었다** — 아직 릴리스되지 않았고
[`CHANGELOG`](../../CHANGELOG.md) 의 `[Unreleased]` 에 있다. 위 문장과 모순처럼 보이므로 경계를
여기서 못박는다.

번호를 여기 박지 않는 것은 게으름이 아니다. **§1 이 그 번호를 이미 정하고 있다** — 이것은 아래에서
스스로 "깨지는 변경" 이라고 부르는 것이므로 `0.2.x` 패치로는 나갈 수 없고, `0.x` 에서 그런 변경이
타는 자리는 마이너 올림이다. 실제 번호는 릴리스가 정하며, 그때까지 이 문서가 존재하지 않는 버전을
가리키고 있으면 **이 문서의 존재 이유 — 어느 올림이 무엇을 깨는가 —** 가 첫 예시부터 틀린다.

동결은 **살아 있는 표면**에 대한 약속이다. *"같은 데이터를 계속 읽으면서 이름만 바꾸지 않는다"*
— 그것이 지금까지 `conversation_*` 를 지켜 온 규칙이다. 모듈이 없어지는 것은 다른 사건이다: 새 코드가
옛 이름으로 옛 데이터를 잘못 읽는 일이 일어날 수 없다. **읽는 코드가 없기 때문**이다.

| 사건 | 동결 약속의 적용 |
|---|---|
| 자바 타입을 개명한다 | **적용된다.** 저장된 이름은 따라 바뀌지 않는다 ([`rename-maps.md`](../migration/rename-maps.md)) |
| 컬럼/컬렉션 이름을 고친다 | **적용된다.** 마이그레이션 경로 없이는 하지 않는다 |
| 모듈을 제거한다 | **적용되지 않는다.** 지킬 대상이 남지 않는다 — 대신 아래 두 가지를 진다 |

제거가 공짜라는 뜻은 아니다. 두 가지를 대신 진다.

1. **`0.x` 의 제거 규칙을 그대로 따른다** — §3·§5 의 절차이며, 그 백엔드로 데이터를 쌓아 둔 쪽에게는
   **깨지는 변경**이다. `CHANGELOG` 의 `[Unreleased]` 가 그렇게 적고 있다
2. **"이전(migration)이 아니라 제거"임을 말한다.** 분산 메모리는 이제 별도 저장소의 서비스
   ([aimon-memory](https://github.com/kangwoo/aimon-memory))가 맡지만, **그 서비스의 스키마는 다른
   물건이고 옛 `mem_*` 데이터가 그리로 옮겨가지 않는다.** 이름이 비슷해서 이전으로 읽히는 것이 가장
   비싼 오해이므로 문서마다 같은 문장으로 적는다

`aimon-memory-file` 은 제거가 아니라 **흡수**였고, 따라서 동결이 그대로 적용된다 — JSONL 저장 포맷과
파일 레이아웃은 한 글자도 바뀌지 않았다. 바뀐 것은 좌표(`aimon-core` 안으로)와 자바 패키지
(`at.aimon.memory.file` → `at.aimon.core.memory.file`)뿐이며, 그것은 §3 의 개명이므로
[`rename-maps.md`](../migration/rename-maps.md) 에 표가 있다.

### 4.2 역방향 결합 — core 의 `PeerMemory` 는 이제 다른 저장소의 빌드를 깬다

위 절들은 전부 **이 저장소가 소비자에게 지는 의무**를 말한다. `PeerMemory` 하나는 방향이 반대인 결합을
새로 만들었으므로 따로 적는다.

[aimon-memory](https://github.com/kangwoo/aimon-memory) 의 `aimon-memory-client` 는
`at.aimon.core.memory.PeerMemory` 와 그 다섯 티어 인터페이스에 **컴파일된다**. 릴리스된 좌표
(`at.aimon.core:aimon-core:0.2.4` 이상)에 대해서만 그렇게 하도록 그쪽 빌드에 가드가 있다 —
`verifyCoreIsReleased` 는 aimon-core 가 프로젝트로 치환되었거나 그 jar 에 `PeerMemory` 가 없으면
publish 를 거절한다.

결과는 이렇다.

- **`PeerMemory` 와 다섯 티어 인터페이스(`MemorySnapshotReader` · `MemorySearcher` ·
  `DialecticEngine` · `ObservationRecorder` · `MemoryIngestor`)와 그 요청·결과 값 객체는, 이 저장소의
  기준으로 공개 API 일 뿐 아니라 다른 저장소의 컴파일 대상이다.** 여기서 시그니처를 바꾸면 그쪽 빌드가
  깨지고, 그 사실은 **다음 릴리스가 나가고 그쪽이 좌표를 올릴 때** 드러난다 — 이 빌드는 초록인 채로
- **그러므로 순서가 있다.** 티어 SPI 를 바꿀 때는 (1) 여기서 `@Deprecated` 로 한 릴리스 겹치게 두고
  (§5), (2) 릴리스한 뒤, (3) 그쪽이 새 좌표로 올라오게 한다. 반대 순서는 그쪽을 컴파일 불가 상태로
  세워 둔다
- **`aimon-memory-testkit` 이 배포되는 이유가 이것이기도 하다.** 그 스위트가 계약의 코드 표현이므로,
  그쪽은 시그니처가 아니라 **행동**이 어긋난 것도 자기 빌드에서 잡을 수 있다

`aimon-memory-testkit` 자체도 배포되는 순간 같은 성격의 표면이 된다 —
`AbstractPeerMemoryContractTest` 의 protected 확장점(`newBackend()` 등)은 그쪽 테스트가 override 하는
것이므로 §2 의 공개 API 규칙이 적용된다.

---

## 5. Deprecation 절차

현재 코드베이스에 `@Deprecated` 는 **0건**이다. 이름을 바꿀 때 어댑터를 남기는 대신 한 번에 옮겨 왔기
때문이며, `0.x` 에서는 그쪽이 정직하다 — 지키지 않을 유예 기간을 표시하는 것보다 낫다.

`1.0` 이후로는 다음을 따른다:

1. `@Deprecated` 와 `@deprecated` javadoc 을 함께 단다. javadoc 에는 **대체 수단을 명시**한다
2. `CHANGELOG.md` 에 기록한다
3. **최소 한 번의 마이너 릴리스**를 유예 기간으로 둔다
4. 그다음 메이저에서 제거한다

`0.x` 동안 deprecation 을 쓴다면 위 절차를 따르되, 유예 기간은 보장하지 않는다.

---

## 6. `1.0` 진입 조건

`1.0` 은 날짜가 아니라 **상태**로 정한다. 아래가 모두 참일 때 올린다.

- [ ] **핵심 SPI 가 한 릴리스 주기 동안 변경 없이 유지됨** — `Tool`, `Hook`, `LlmClient`,
      `VirtualFileSystem`, `SessionRecordStore`, `AgentExecutor`
- [ ] **`aimon-core` 밖에서 온 백엔드 구현이 하나 이상 존재** — SPI 가 정말 구현 가능한지는 이 프로젝트가
      직접 쓴 구현체만으로는 증명되지 않는다
- [ ] **스코프 모델이 이름 변경 없이 한 주기를 넘김** — 최근 두 번의 파괴적 변경이 모두 여기서 나왔다
- [ ] **Spring Boot starter 의 미결 항목이 정리됨**
      ([`../backlog/spring-boot-starter-open-items.md`](../backlog/spring-boot-starter-open-items.md))
- [ ] **공개 API 에 대한 javadoc 이 빠짐없이 존재**
- [ ] **번역된 문서가 코드와 어긋나지 않음을 검사하는 CI 가 동작** — 문서가 API 의 일부인 프로젝트이므로

이 목록은 [`roadmap.md`](roadmap.md) 와 함께 읽는다.

---

## 7. 호환성을 확인하는 방법

지금 이 저장소가 자동으로 검사하는 것과 검사하지 않는 것을 구분해 둔다.

**검사한다:**

- `*.impl` 경계 침범 (`PackageDependencyArchitectureTest`)
- 금지된 타입 이름 — 맨 `Session` / `AgentSession` (`SessionNamingArchitectureTest`)
- `turn` / `iteration` / `execution` 어휘 혼용 (`TurnVocabularyArchitectureTest`)
- BOM 이 관리하는 좌표와 실제 배포 대상의 일치 (`:aimon-bom:verifyBom`)
- 릴리스 게이트가 CI 게이트보다 좁지 않은지 (`ReleaseGateMatchesCiGateTest`)

**아직 검사하지 않는다:**

- **바이너리 호환성 (japicmp / revapi 같은 도구)** — `0.x` 에서 마이너가 깨져도 되는 동안에는
  검사기가 매번 "깨졌다"고만 말한다. `1.0` 진입 조건에 함께 넣는 것이 자연스럽다

즉 **"빌드가 통과했다"가 "호환된다"를 뜻하지 않는다.** 지금은 `CHANGELOG.md` 가 그 역할을 한다.

---

## 관련 문서

- [`../../CHANGELOG.md`](../../CHANGELOG.md) — 변경 이력
- [`../migration/rename-maps.md`](../migration/rename-maps.md) — 옛 이름 ↔ 새 이름 매핑
- [`../migration/frozen-names.md`](../migration/frozen-names.md) — 개명하지 않기로 한 이름들
- [`roadmap.md`](roadmap.md) — 어디로 가고 있는가
- [`publishing-guide.md`](publishing-guide.md) — 릴리스 절차
- [`../overview/scope-model.md`](../overview/scope-model.md) — 수명·소유권 규칙과 개명의 배경
- [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md) — 기여 절차
