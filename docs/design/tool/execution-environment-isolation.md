# 격리 브랜치와 병합 — `isolate`, `isolatedFrom`, `WorktreeMerge`

> **Status**: IMPLEMENTED — `aimon-core` 의 `ExecutionEnvironment.isolate` · `isolatedFrom`, `LocalIsolatedEnvironment`, `ScopedVirtualFileSystem`, `WorktreeMerge`. 남은 것은 [§13](#13-남은-것).

## 1. 목표와 범위

격리 브랜치는 `ExecutionEnvironment.isolate(key)` 가 부모에서 파생한 환경이다. 쓰기가 그 키 아래에 격리되므로 병렬 단계가 서로의 파일을 덮지
않는다. 격리는 환경의 기능이고 브랜치를 쓰는 쪽은 같은 도구 인스턴스를 그대로 쓴다.

목표는 하나다. 잘못된 입력은 가장 이른 지점(쓰는 시점 또는 `promote` 의 첫 줄)에서 거절하고, 모든 거절은 원인을 말한다.

이름을 구분한다. **격리 브랜치**는 이 문서의 주제이고, **격리 실행**(`ExecutionOptions.isolated`)은 훅 명령을 도는 방식이다. 후자와 계보를 밝힌
브랜치에서 훅이 도는 기반 환경은 [훅 환경](../hook/hook-environment.md) 에 있다. 러너가 격리를 쓰는 방식(레지스트리 복제 없음, 캐시와의 상호작용,
병합은 명시적)은 [워크플로](../workflow/workflow.md) 가 소유하고, 제공자가 지킬 행은 [실행 환경](execution-environment.md) 의 샌드박스 제공자 계약
절에 있다.

## 2. 설계 결정

| 쟁점 | 결정 | 이유 | 기각한 대안 |
|---|---|---|---|
| 지원하지 못할 때의 답 | 빈 값은 "이런 환경에는 격리가 없다", 던짐은 "여기서 거절하며 메시지가 이유" | 사용 불가 환경은 "격리 없음" 이라고 정직하게 답할 수 없다. 능력을 모르고, 이유가 호출자에게 필요한 전부다 | 러너의 `instanceof UnavailableExecutionEnvironment`: 그 호출자 하나만 고친다. 서술자 `notes` 파싱: 프롬프트용 글에 묶인다. 이유를 싣는 결과 타입: 진단 하나로 SPI 를 깬다 |
| 비지원·거절 시 비격리 실행 | 하지 않는다. 러너의 실행 전체 실패 | 병렬 브랜치가 서로의 파일을 덮는다. 격리를 요청했다는 사실이 정확성 조건이다 | 격리 없이 실행 |
| 브랜치의 `.aimon/` 쓰기 | 쓰는 시점에 거절한다 | 병합에서 걸러 내면 쓴 데이터가 말없이 사라지고, 현 동작을 문서화하면 사용자가 절반 병합을 본다 | 병합에서 걸러 내기. 현 동작 문서화 |
| 브랜치 규칙의 출처 | 부모 규칙을 브랜치 루트로 옮긴다 | 어셈블리가 규칙을 바꾸거나 비우면 브랜치도 따라야 한다 | `deny(".aimon")` 고정 |
| 규칙 층의 위치 | `ScopedVirtualFileSystem` 아래 | 위에서는 스코프의 `"."` 기준으로 풀려 절대 경로가 규칙을 비껴간다 | 스코프 위에 규칙 층 |
| 접근 규칙의 구현 | 스코프가 다시 구현하지 않고 `PathRuleVirtualFileSystem` 을 합성한다 | `exists`·목록 숨김, 하위 트리 검사, fail-closed 의 뜻을 두 클래스에 두지 않는다 | 스코프에 거부 접두어 목록 |
| `.worktrees` | 스코프가 브랜치 루트의 그 이름을 예약한다 | 옮길 부모 규칙이 없고, 접근 수준이 아니라 스코프 자신의 이름 공간이다. 규칙을 비운 어셈블리에서도 유효하다 | 브랜치 전용 `DENY {prefix}/.worktrees`: "브랜치 규칙은 부모의 것" 을 깬다. 상대 경로 `.worktrees/k/…` 를 벗겨 다시 쓰기: 호출자가 쓴 이름을 조용히 다른 파일로 바꾼다 |
| 공유 접두어 안의 브랜치 디렉터리 | 스코프가 목록에서 뺀다 | 어떤 호출자 경로도 닿지 못한다 | 재고정 사본을 `DENY` 로: 부모 규칙이 스테이징을 덮을 때만 동작하고 "브랜치 규칙은 부모의 것" 을 깬다 |
| 스테이징 접두어 공유 | 무조건 공유한다 | 공유하지 않는 브랜치는 `stage()` 가 돌려준 절대 경로로 사본을 읽지 못한다 | 부모가 지킬 때만 공유 |
| 중첩 격리 | 이유를 담은 `UnsupportedOperationException` | §8 | 중첩 지원. 빈 값: "격리 없음" 으로 읽혀 원인을 가린다 |
| 소속 확인 | `isolatedFrom()` 과 동일성 비교 | 제공자에 상관없는 값으로 비교할 수 있고, 파일 시스템 동일성이 데이터 손실의 직접 원인을 잡는다 | `WorktreeMerge` 의 `instanceof LocalIsolatedEnvironment`: `environment.impl` 을 import 할 수 없고 로컬 전용이다. 서술자 작업 디렉터리 비교: 계약이 아니며 제공자가 모든 작업 트리를 같은 경로로 서술할 수 있다 |
| 계보를 밝히지 않는 브랜치 | 받는다. 나머지 검사만 적용 | 거부는 제공자 계약을 깬다 | 거부 |
| 사전 점검의 메타데이터 | 검증만 하고 값을 재사용하지 않는다 | 사이에 바뀐 파일을 낡은 길이로 쓰게 된다. 비용은 올린 파일당 `getMetadata` 하나 더다 | 점검 값 보관 |
| 병합 복사 | 복사, `exists` 확인, 브랜치 원본 삭제 | S3·GridFS 의 `move` 는 원자적이지 않은 복사 후 삭제다. 파일 단위로 다시 돌려도 안전하다 | 맨 `move` |
| 브랜치 키의 실행 간 공유 | 구조 경로에서만 키를 만든다. §9 | 병합 계약이 키로 브랜치를 다시 짓는다 | run id 또는 논스를 키에 넣기 |

## 3. `isolate` 의 두 답

```java
default Optional<ExecutionEnvironment> isolate(String branchKey) { return Optional.empty(); }
```

| 답 | 뜻 | 누가 |
|---|---|---|
| 파생 환경 | 격리 브랜치 | `LocalExecutionEnvironment` |
| 빈 값 | 이런 종류의 환경에는 격리가 없다 | SPI 기본 구현 |
| 던짐 | 여기서 거절한다. 메시지가 이유다 | `UnavailableExecutionEnvironment`(`ExecutionEnvironmentUnavailableException`), `LocalIsolatedEnvironment`(`UnsupportedOperationException`) |

`DefaultWorkflowContext.resolveLaunchContext` 는 어느 쪽이든 격리 없이 돌리지 않는다. 던진 경우 원인을 `WorkflowException` 에 사슬로 잇고 메시지에
이유를 싣는다. 부모 환경이 없는 러너 쪽 해석은 [실행 환경](execution-environment.md) 의 해석 절이 소유한다.

## 4. 브랜치 구조

`LocalIsolatedEnvironment` 의 구성은 다음과 같다.

```text
file tool ─► ScopedVirtualFileSystem(prefix=.worktrees/k, shared={.aimon-staged}, reserved={.worktrees})
               └─► PathRuleVFS(DENY .worktrees/k/.aimon, READ_ONLY .worktrees/k/.aimon-staged)   브랜치 층
                     └─► PathRuleVFS(DENY .aimon, READ_ONLY .aimon-staged)                       부모의 도구 파일 시스템
                           └─► LocalFileSystem
```

- **키.** `[A-Za-z0-9_]+` 만 받고 아니면 `IllegalArgumentException` 이다. 같은 키로 다시 `isolate` 하면 같은 뷰가 나온다. 병합이 키로 브랜치를 다시 짓는
  근거다.
- **셸.** `WorkingDirectoryShell` 이 명령에 작업 디렉터리가 없을 때 브랜치 루트를 채운다. 부모의 셸을 쓰므로 `backgroundCommandTimeout` 도 부모의 것이다.
- **서술자.** 작업 디렉터리는 브랜치 루트의 호스트 경로이고, `notes` 는 파일 도구와 기본 작업 디렉터리만 브랜치에 묶인다고 말한다. 부모가 자기 `.aimon/` 을
  `notes` 에 적지 않으므로 브랜치도 적지 않는다. 거절 메시지가 경로를 말한다.
- **격리의 범위.** 파일 도구와 기본 작업 디렉터리뿐이다. 셸 명령의 절대 경로는 디스크 전체에 닿는다. 표준 절대 경로 `{workspace}/a` 는 파일 도구에서는 브랜치의
  `a` 이고, `cat {workspace}/a` 는 표준 파일을 읽는다. 로컬의 한계다.
- **스테이징.** `stage()` 는 부모와 공유하는 스테이징 영역의 사본을 돌려준다. [스테이징](execution-environment-staging.md) 의 제자리 지름길과 브랜치의 사본 절.
- **내용 검색.** 브랜치 루트에 뿌리를 두되 부모의 숨긴 접두어를 유지한다. [파일 규칙](execution-environment-files.md) 의 `ContentSearch` 절.
- **`durable()`.** false 다. 브랜치 내용은 병합이나 조립 코드의 정리 대상이라 보장되지 않는다. 그래서 artifact 는 제어 저장소로 복사한다.

**코어는 브랜치 디렉터리를 치우지 않는다.** `promote` 는 올린 파일만 지운다. 디렉터리, 진 쪽 충돌 파일, 셸이 남긴 숨은 파일은 남는다. `.worktrees` 를 지우는
코드도 폐기 API 도 없다.

## 5. 경로 규칙: 스코프 아래에 재고정

경로 규칙의 뜻은 [파일 규칙](execution-environment-files.md) 에 있다. 브랜치는 부모의 실효 규칙을 브랜치 루트 아래로 옮겨(`.aimon` → `.worktrees/k/.aimon`) 스코프
**아래** 층에 다시 건다.

위에 두면 스코프의 `getWorkingDirectory()` 인 `"."` 기준으로 경로를 풀어 `/ws/.aimon/x` 가 `ws/.aimon/x` 가 되고 어떤 규칙에도 걸리지 않는다. 아래에서는 `scope()` 가 이미
계산한 정규화된 위임 경로만 보이므로 모든 표기가 한 검사를 지난다. `PathRuleVirtualFileSystem.getWorkingDirectory()` 는 위임의 것을 돌려주므로 스코프가 잡는 기준
디렉터리도 그대로다.

규칙은 부모 것을 옮긴 것이지 새로 정한 것이 아니다.

- 규칙을 비운 어셈블리(`pathRules(List.of())`)의 브랜치에는 규칙이 없다.
- 부모의 모든 규칙을 옮긴다. 재고정한 스테이징 규칙에는 닿을 일이 없지만(§6), 어떤 규칙도 공유 접두어 집합에 조용히 기대지 않게 한다.

결과로 브랜치의 `.aimon/` 은 루트의 것처럼 동작한다. 보이지 않고 파일 도구의 모든 접근이 `FileAccessDeniedException` 이며, 목록에서 빠지므로 병합도 보지 못한다.

## 6. 공유 접두어와 대소문자

브랜치는 스테이징 루트를 부모와 공유한다. 브랜치 기준 경로가 그 접두어 아래이면(세그먼트 단위, 정규화 뒤, 대소문자 무시) 스코프가 브랜치 접두어 없이 위임에 넘긴다.
그래서 `.AIMON-STAGED/x` 쓰기는 브랜치 안에 떨어지지 않고 부모의 `READ_ONLY` 규칙에 걸린다. 돌려주는 경로는 호출자의 표기를 유지한다. 대소문자를 구분하지 않는
저장소에서는 같은 디렉터리이고, 구분하는 저장소에서는 루트의 다른 디렉터리이며 루트 규칙이 여전히 덮으므로 읽기는 그냥 없음이 된다.

**전제.** 이 공유는 부모가 스테이징 영역을 지킬 때 안전하다. 그 규칙을 뺀 어셈블리의 브랜치는 루트 스테이징 영역에 그대로 쓴다.

**셸이 만든 사본.** 셸이 브랜치 루트에 만든 스테이징 디렉터리는 어떤 표기로도 닿지 않는다. 모두 부모의 것으로 간다. 그래서 스코프가 브랜치 접두어 아래의 그 항목을 목록에서
빼고 병합이 올리지 않는다. `.aimon-staged` 자체의 목록은 여전히 부모의 사본을 보여 준다.

**브랜치 경로의 표기.** 브랜치 호스트 경로는 정규화한 뒤 대소문자를 무시하고 벗긴다. `{ws}/./.worktrees/k/x`, `{ws}//.worktrees/k/x`, `{ws}/.worktrees/K/x` 가 모두 브랜치의
`x` 다. `{ws}/.worktrees/k/../../x` 는 기준 경로 `{ws}/x` 이므로 역시 브랜치의 `x` 다. 상대 경로는 이미 브랜치 기준이라 벗기지 않는다.

**키의 대소문자.** 대소문자만 다른 두 키는 대소문자를 구분하지 않는 저장소에서 한 디렉터리다. 러너의 키는 소문자 세그먼트와 인덱스에서 나오므로 그런 쌍이 생기지 않는다.
자기 키를 고르는 호출자가 대소문자로 구분되게 지킨다. 막으려면 환경마다 발급한 키 집합이라는 상태가 필요하고, 멀티 인스턴스에서는 그 상태도 저장소 뒤로 가야 하므로 가상의
호출자를 위해 치를 값이 아니다.

## 7. `.worktrees` 예약

부모 규칙이 이미 서술하는 접두어(`.aimon` 등)는 재고정으로 다룬다. 그 의미를 `ScopedVirtualFileSystem` 에 다시 구현하지 않기 위해서다. `.worktrees` 는 다르다. 옮길 부모 규칙이
없고, 접근 수준이 아니라 스코프 자신의 이름 공간이다. 그래서 스코프가 브랜치 루트의 그 이름 하나를 예약해 모든 연산을 거절하고 목록에서 뺀다. 규칙을 비운 어셈블리에서도
유효하다.

- 브랜치는 `.worktrees/` 를 가리킬 수 없다. 그 이름 아래 쓰면 병합이 다른 브랜치나 자기 디렉터리로 올리기 때문이다. 브랜치 기준 경로가 `.worktrees` 이거나 그 아래이면(세그먼트
  단위, 정규화 뒤, 대소문자 무시) 모든 연산이 `InvalidPathException` 이다.
- 셸이 브랜치 루트에 만든 `.worktrees/` 는 `list`, `listRecursive`, `search` 에서 빠지므로 병합이 보지 못한다.
- 거절하는 이유. 브랜치의 모든 경로는 워크스페이스 루트의 같은 경로로 올라가므로, 브랜치 루트의 `.worktrees/…` 에는 목적지가 제공자의 브랜치 디렉터리 하나뿐이다. 다른 브랜치로의
  주입, 이 브랜치에서 다음 병합이 한 번 더 올릴 파일, 제공자 디렉터리의 쓰레기 말고 다른 결과가 없다. 그래서 다시 쓰지 않고 거절한다.
- 예약되는 것은 브랜치 루트의 그 이름 하나다. `docs/.worktrees/x` 는 보통 파일이다. 브랜치의 절대 루트(`{ws}/.worktrees/k/x`)는 검사 전에 벗겨지므로 브랜치의 `x` 다.
- 워크스페이스 루트는 예약 대상이 아니다. 부모의 파일 도구는 `.worktrees/` 아래에 쓸 수 있다.

이것은 경로 규칙이 아니므로 브랜치 규칙은 정확히 부모의 것 그대로다. 거절의 종류도 다르다. 예약은 이름 공간의 `InvalidPathException`, 규칙은 접근 수준의
`FileAccessDeniedException` 이다.

## 8. 중첩 거절

브랜치의 `isolate` 는 이유를 담은 `UnsupportedOperationException` 을 던진다. 메시지는 브랜치 키와 바깥에서 돌리거나 `isolate` 를 빼라는 안내를 싣고, 러너를 거치면
`could not isolate branch '<k2>': nested isolation is not supported: …` 가 된다. 중첩을 지원하지 않는 이유는 넷이다.

1. **경로가 깨진다.** 스코프 위의 스코프는 안쪽이 `"."` 를 기준으로 잡아 절대 경로의 앞 슬래시만 벗긴 엉뚱한 자리에 쓴다. 지원하려면 루트에서 복합 접두어로 환경을 다시 지어야 한다.
2. **계보.** 소속 검사가 부모 하나가 아니라 계보 사슬을 봐야 한다.
3. **제공자의 계약.** 다른 제공자의 중첩은 그 제공자의 문제이고, 코어는 시험할 수 없는 의미를 약속하게 된다.
4. **쓰임이 없다.** 쓰려는 곳이 생길 때 다시 본다.

## 9. 키와 실행 간 공유

브랜치 키는 단계의 구조 경로에서만 나오므로(`DefaultWorkflowContext.sanitizeBranchKey`) 모든 실행의 첫 격리 단계가 같은 키(`a0`)를 쓰고, 한 워크스페이스의 연속·동시 실행이 브랜치
디렉터리를 나눠 쓴다. 소속 검사는 환경 인스턴스와 계보를 비교하고 키를 보지 않으므로 이것을 보지 못한다.

바꾸지 않은 이유는 셋이다.

- 병합 계약이 키로 브랜치를 다시 짓는다. 키만 아는 조립 코드는 `parent.isolate(key)` 로 그 실행의 브랜치를 얻는다.
- `DEFAULT_RUN_ID` 때문에 run id 로도 유일해지지 않고, 논스는 재개를 넘기지 못한다.
- 실행별 키는 정리 설계 없이 디렉터리를 무한히 늘린다.

정리 설계가 먼저이며 [EE-46](../../backlog/execution-environment-open-items.md) 에 열려 있다.

## 10. `isolatedFrom` 과 `promote` 소속 검사

`ExecutionEnvironment.isolatedFrom()` 은 이 환경이 격리되어 나온 부모를 돌려준다. 브랜치가 아니거나 밝히지 않으면 빈 값이다. `LocalIsolatedEnvironment` 는 부모를 돌려준다. 훅의 기반 환경도
이 값을 쓴다([훅 환경](../hook/hook-environment.md)).

`WorktreeMerge.promote(parent, branches, policy)` 는 파일을 읽거나 쓰기 전에 소속을 확인한다. 어긋나면 `IllegalArgumentException` 이다.

1. 브랜치가 부모 자신이다.
2. 브랜치가 부모의 파일 시스템을 공유한다. 올리면 파일마다 자기 위에 복사한 뒤 지우게 된다.
3. `isolatedFrom()` 이 다른 인스턴스다.
4. 같은 브랜치 객체가 두 번 나온다. 가짜 충돌을 보고하게 된다.

비교는 동일성이다. 로컬 제공자는 환경 인스턴스 하나를 돌려주고 브랜치가 그 인스턴스를 쥔다. 그래서 호출자는 브랜치를 격리한 바로 그 인스턴스를 넘긴다.

계보를 밝히지 않는 브랜치도 받는다. 거부는 제공자 계약을 깬다. 이때 3번을 뺀 검사만 적용한다. 그 느슨함은 [EE-47](../../backlog/execution-environment-open-items.md) 에 있다.

다른 제공자는 `fileSystem()` 첫 호출에 프로비저닝할 수 있어, 소속 검사에 입출력이 전혀 없다고 약속하지 않는다. 약속은 "파일을 읽거나 쓰기 전" 까지다.

## 11. `promote` 의 사전 점검과 병합

**순서.**

1. 소속 검사(§10).
2. 사전 스캔. 브랜치마다 `listRecursive(".")` 로 경로별 작성 브랜치를 모은다. 파일이 없는 브랜치는 건너뛴다.
3. 정책. `FAIL` 이고 충돌이 있으면 아무것도 올리지 않고 충돌을 보고한다. `FIRST_WINS`, `LAST_WINS` 는 목록 순서로 이긴 쪽을 고른다.
4. 대상 점검. 올릴 곳이 부모의 경로 규칙에 막히면 아무것도 올리지 않는다.
5. 메타데이터 점검. 이긴 파일의 메타데이터를 읽을 수 없으면 아무것도 올리지 않는다.
6. 복사, 확인, 삭제. 파일마다 `parent.write(branch.openInputStream)`, `exists` 확인, `branch.delete` 순이다.

**사전 점검의 범위.**

- 대상 규칙은 `VirtualFileSystems.pathRules` 로 읽으며 가장 바깥 층만 보인다. 로컬 제공자의 부모 파일 시스템이 그 층이다. 다른 데코레이터로 감싼 제공자는 쓸 때 확인된다.
- 메타데이터 점검은 값을 재사용하지 않는다. 복사 직전에 다시 읽는다. 로컬 파일 시스템에서 셸이 만든 심볼릭 링크는 `listRecursive` 에는 나오지만 `getMetadata` 와 `openInputStream` 이
  거부하므로, `getMetadata` 가 가장 이른 실패 지점이다.

**롤백 없음.** 복사, 검증, 삭제 실패는 fail-fast 이고 롤백이 없다. 이미 올린 파일은 그대로 남고, 진행 상황은 WARN 과 예외 메시지에 실린다. 파일 단위로 멱등이므로 원인을 고치고 다시 돌리면 된다.

**호출자.** `WorktreeMerge` 는 조립 애플리케이션이 부르는 공개 헬퍼다. 러너는 병합하지 않으며 이 저장소의 main 소스에는 호출자가 없다. 키만 아는 호출자는 `parent.isolate(key).orElseThrow()` 로
브랜치를 다시 짓는다. 브랜치마다 한 번씩 넘긴다.

## 12. 실패 표

| 상황 | 동작 |
|---|---|
| 파일 도구가 브랜치에서 `.aimon/x` 를 쓴다(표기, 대소문자, 상대·절대 불문) | 쓰는 시점에 `FileAccessDeniedException`. 도구는 `ToolResult.error` 를 돌려준다. 메시지는 위임 경로(`.worktrees/k/.aimon/x`)를 말한다. 실제 위치를 말하고 모호하지 않으므로 호출자 표기로 고쳐 쓰지 않는다 |
| 셸이 `.worktrees/k/.aimon/x` 를 쓴다 | 브랜치 목록에서 숨겨지므로 올라가지도 보고되지도 않고 브랜치 디렉터리에 남는다. 셸이 쓴 루트 `.aimon/` 파일과 같은 처지이고 로컬의 한계다 |
| 브랜치가 `.AIMON-STAGED/x` 를 쓴다 | 쓰는 시점에 `FileAccessDeniedException` (read-only) |
| 셸이 브랜치 루트에 스테이징 디렉터리를 만든다 | 목록에서 빠지고 올라가지 않으며 보고되지 않는다 |
| 브랜치가 `.worktrees/other/x` 또는 상대 경로 `.worktrees/k/x` 를 쓴다 | `InvalidPathException` |
| 셸이 브랜치 루트에 `.worktrees/` 를 만든다 | 목록에서 빠지고 올라가지 않는다 |
| 부모에 경로 규칙이 없다 | 브랜치의 `.aimon/` 에 쓸 수 있다. 규칙은 부모를 따른다 |
| `branch.fileSystem().deleteRecursive(".")`, `move(".", …)` | 보호 디렉터리를 포함한다는 이유로 거절. 부모를 통한 `parent.fileSystem().deleteRecursive(".worktrees/k")` 는 된다 |
| 제공자 없음, 제공자 예외, 사용 불가 환경에서 격리 단계 | `could not isolate branch 'k': Execution environment unavailable: <cause> — refusing to run unscoped`. 원인 사슬 유지, 실행 전체 실패 |
| 브랜치 안에서 다시 격리 단계 | `could not isolate branch 'k2': nested isolation is not supported: …`. 실행 전체 실패, 서브에이전트 실행 없음 |
| 격리가 없는 환경 | `does not support isolation — refusing to run unscoped`. 실행 전체 실패 |
| `promote` 에 부모 자신, 다른 부모의 브랜치, 중복, 파일 시스템 공유 브랜치 | 파일을 읽거나 쓰기 전에 `IllegalArgumentException` |
| 올릴 곳이 부모 규칙에 막힌다(셸이 쓴 `READ_ONLY` 디렉터리 등) | `VirtualFileSystemException("Worktree promotion aborted before promoting anything: …")`. 아무것도 올리지 않는다 |
| 올릴 파일의 메타데이터를 읽을 수 없다(심볼릭 링크) | 같은 메시지로 중단. 아무것도 올리지 않는다 |
| 병합 중 복사, 쓰기, 삭제 실패 | fail-fast, 진행 상황을 메시지에 싣는다. 롤백 없음, 다시 돌리면 된다 |
| 계보를 밝히지 않는 브랜치 | 받는다(§10) |

## 13. 남은 것

- [EE-46](../../backlog/execution-environment-open-items.md): 격리 브랜치 키가 실행 사이에 공유된다(§9). 브랜치 디렉터리 정리 설계가 그 선행 작업이다.
- [EE-47](../../backlog/execution-environment-open-items.md): 계보를 밝히지 않는 브랜치와 한 키의 두 객체. 한 키의 두 객체는 동일성 비교에 걸리지 않아 `FAIL` 정책에서 그 키의 모든 파일을 충돌로 보고한다.
- 트리 안에 `WorktreeMerge` 호출자가 없다(§11). 병합은 조립 애플리케이션의 몫이다.

## 14. 하지 말 것

- **`isolate()` 가 비었거나 던졌을 때 격리 없이 브랜치를 돌리지 말 것.** 던진 이유는 버리지 말고 싣는다(§3).
- **이유가 있는 거절을 빈 값으로 답하지 말 것.** 빈 값은 "이런 환경에는 격리가 없다" 뿐이다(§3).
- **규칙 층을 스코프 위로 옮기지 말 것.** 절대 경로가 비껴간다(§5).
- **브랜치 규칙을 부모 규칙 밖에서 지어내지 말 것.** `.worktrees` 는 규칙이 아니라 예약이다(§5, §7).
- **스코프에 접근 규칙의 의미를 다시 구현하지 말 것**(§5, §7).
- **`promote` 에 맨 `move` 를 쓰지 말 것**(§11).
- **코어가 브랜치 디렉터리를 치운다고 전제하지 말 것.** 브랜치 내용이 남는다고 전제해도 안 된다(§4).
- **계보 없는 브랜치를 거부하지 말 것.** 제공자 계약을 깬다(§10).

## 15. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 절 | 타입 | 파일 |
|---|---|---|
| §3, §10 | `ExecutionEnvironment.isolate`, `isolatedFrom` | `environment/ExecutionEnvironment.java` |
| §3 | `UnavailableExecutionEnvironment` | `environment/UnavailableExecutionEnvironment.java` |
| §3, §9 | `DefaultWorkflowContext.resolveLaunchContext`, `sanitizeBranchKey` | `workflow/impl/DefaultWorkflowContext.java` |
| §4, §5, §8 | `LocalIsolatedEnvironment` | `environment/impl/LocalIsolatedEnvironment.java` |
| §4 | `LocalExecutionEnvironment.isolate`, `WorkingDirectoryShell` | `environment/impl/LocalExecutionEnvironment.java`, `environment/impl/WorkingDirectoryShell.java` |
| §5~§7 | `ScopedVirtualFileSystem` | `filesystem/impl/ScopedVirtualFileSystem.java` |
| §5 | `PathRuleVirtualFileSystem` | `filesystem/impl/PathRuleVirtualFileSystem.java` |
| §10, §11 | `WorktreeMerge`, `MergeReport` | `agent/impl/orca/environment/WorktreeMerge.java`, `agent/impl/orca/environment/MergeReport.java` |
| §11 | `VirtualFileSystems.pathRules` | `filesystem/VirtualFileSystems.java` |

관련 문서: [실행 환경](execution-environment.md), [파일 규칙](execution-environment-files.md), [스테이징](execution-environment-staging.md),
[워크플로](../workflow/workflow.md), [훅 환경](../hook/hook-environment.md).
