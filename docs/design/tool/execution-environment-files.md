# 실행 환경의 파일 규칙 — 제어 저장소, 경로 규칙, stamp, 내용 검색, artifact

> **Status**: IMPLEMENTED — `aimon-core` 의 `PathRule`, `PathRuleVirtualFileSystem`, `FileStamp`, `ContentSearch`, `RipgrepContentSearch`, `ArtifactArchive`. 남은 것은 [§8](#8-남은-것).

## 1. 무엇을 정하는가

작업 환경과 제어 저장소를 가르는 경계, 그리고 파일 도구가 환경에 기대는 규칙을 정한다. 도구가 환경을 받는 일반 규칙은
[실행 환경](execution-environment.md), 스킬 파일을 경계 너머로 옮기는 일은 [스테이징](execution-environment-staging.md), 브랜치 위의
경로 규칙은 [격리 브랜치](execution-environment-isolation.md) 가 소유한다.

## 2. 설계 결정

| 쟁점 | 결정 | 이유 | 기각한 대안 |
|---|---|---|---|
| 작업 파일과 제어 평면 | 저장소를 나눈다. 런타임 팩토리가 `controlFileSystem` 과 `ExecutionEnvironmentProvider` 를 따로 받는다 | 한 VFS 가 둘을 겸하면 모델이 `Write` 로 자기 정의를 덮을 수 있고, 작업 파일을 격리 환경으로 옮길 때 제어 평면도 따라가야 한다 | 경로 규약으로 보호: 샌드박스로 옮기는 순간 무너진다. 경로 접두어로 VFS 를 고르는 합성 라우터: 파일 도구만 라우팅되고 셸은 따라오지 않는다 |
| 경로 규칙의 판단 기준 | 경로가 실제로 닿는 곳(`VfsPaths.resolveUnder`). 밖으로 나가면 `DENY` 로 다룬다 | `rootRelative` 는 `../<base>/.aimon/x` 에 null 을 돌려줘 규칙이 빠지고 백엔드가 다시 워크스페이스 안으로 푼다 | `rootRelative` 로 판단 |
| 규칙의 이름 비교 | 대소문자와 유니코드를 접는다 | APFS 는 `ſ` 를 `s` 로 접어 `.aimon-ſtaged/` 가 스테이징 영역을 가리킨다. 더 접어도 숨기기만 하므로 안전한 쪽이다 | ASCII 만 접기: `ſ` 같은 이름이 규칙을 비껴간다 |
| 낡은 쓰기 판정 | 메타데이터 stamp. 백엔드가 주면 etag 를 우선한다 | 메타데이터로 충분한 백엔드가 대부분이고, 부족한 백엔드는 etag 로 해시를 준다 | stamp 로 항상 내용 해시: 모든 `Read`, `Edit` 가 파일 전체를 한 번 더 해시한다 |
| 검사와 쓰기 사이의 경합 | 받아들인다. 창은 한 호출 안이다 | 로컬 파일 시스템에서 원자적으로 구현할 수 없다. 원자적이지 않은 조건부 쓰기는 같은 창을 가지면서 더 강한 보장처럼 읽힌다 | VFS 에 조건부 쓰기(`expectedStamp`) |
| stamp 키의 정규화 | 공유 헬퍼 `VfsPaths.rootRelative`. 호출자는 `FileStamps` 하나다 | `a.txt`, `./a.txt`, 작업 디렉터리 아래 절대 경로가 한 키여야 한다 | VFS SPI 에 `normalize`: 이 문제 하나로 SPI 를 넓힌다. 작업 디렉터리 기준 절대 경로: URI 베이스에는 뜻 있는 절대 경로가 없다 |
| stamp 의 범위 | 실행 단위 | 턴 사이와 발화 사이에는 사람과 셸이 파일을 바꿀 시간이 가장 길다 | — |
| 내용 검색의 결과 | 정규화 값 `ContentSearchResult` | `GrepTool` 이 두 경로를 한 포매터로 찍고, 환경이 특정 검색 프로그램의 와이어 형식에 묶이지 않는다 | `rg --json` 원문 반환 |
| 내용 검색 실패 | 던지면 `Grep` 이 파일 시스템을 걷는다 | 두 경로의 출력이 같으므로 대답하지 못한 질의는 걷기가 대답하면 된다 | — |
| `rg` 의 제외 | 제외 glob 에 더해 대상 거부와 출력 필터 | `rg` 는 명령행 경로에 glob 을 걸지 않고 뒤 glob 이 이기므로 제외 glob 만으로는 막지 못한다 | 제외 glob 만 사용 |
| artifact 를 늘 복사 | 비영속 환경에서만 복사한다 | 로컬에서 같은 파일이 두 벌 생긴다. `FileArtifact.getStorage()` 가 두 저장소를 이미 구분한다 | 늘 제어 저장소로 복사해 저장소를 하나로 맞춘다 |
| artifact 복사 위치의 소유 | 코어의 artifact-aware 도구. 제공자는 `durable()` 만 답한다 | 복사하는 쪽이 코어이므로 경로와 상한의 정본이 하나여야 한다 | 제공자별 artifact 경로와 전송 상한 |
| archive key | 실행 id 를 그대로 쓰지 않고 수집기가 갖는 키를 쓴다 | 턴은 `ExecutionId` 를 게시하지 않기 때문이다 | — |

## 3. 제어 저장소 경계

### 3.1 경계

| | 제어 저장소 (`controlFileSystem`) | 작업 환경 (`ExecutionEnvironment`) |
|---|---|---|
| 담는 것 | 에이전트·스킬·커맨드 정의, 태스크 출력과 결과, 세션 스냅숏, 보관된 artifact, wiki 지식 베이스 | 모델이 읽고 쓰는 코드와 데이터, 명령 실행 |
| 수명 | Application, 영속 | 제공자가 정한다. 일시적일 수 있다 |
| 누가 쓰나 | 프레임워크 | 모델(도구 경유) |
| 모델에게 보이나 | 아니다. 스킬 파일은 `stage()` 로만 넘어간다 | 그렇다 |

"모델이 지금 어디서 일하는가" 를 말하는 것은 작업 환경으로 간다. 파일 도구, git·디렉터리 요약 컨텍스트 제공자, 프롬프트의 작업
디렉터리가 그렇다. 나머지는 제어 저장소다. 격리는 환경의 기능이므로 제어 저장소와 무관하다.

wiki 지식 베이스는 모델이 Wiki 도구로만 닿는 프레임워크 상태라 제어 저장소에 두고, `WikiIngest` 의 소스 문서는 작업 환경에서 읽는다.

### 3.2 기본 디렉터리

제어 저장소의 기본 디렉터리는 루트 기준 상대 이름이다.

| 대상 | 디렉터리 |
|---|---|
| 커맨드, 서브에이전트, 사용자 스킬 | `commands`, `agents`, `skills` |
| 머티리얼라이즈된 번들 스킬 | `bundled-skills` |
| 태스크 출력, 결과, 스냅숏 | `task-output`, `task-result`, `task-snapshot` |
| wiki | `wiki` |
| 보관된 artifact | `artifacts` |
| 워크플로 단계 캐시 (`VfsStepResultCache`, 기본 배선 없음. 호출자가 제어 저장소 위에 만든다) | `step-cache` |

제어 루트가 `{workspace}/.aimon/` 이므로 앞에 `.aimon/` 을 다시 붙이면 `.aimon/.aimon` 으로 이중화되어 기존 데이터가 고아가 된다.
`hooks.json` 과 파일 메모리처럼 호스트 경로를 직접 푸는 설정은 제어 저장소를 지나지 않으므로 이 규칙과 무관하다.

### 3.3 로컬 배치

로컬 배치는 두 저장소를 같은 디스크에 두되 루트를 가른다. 제어 저장소는 `{workspace}/.aimon/`, 작업 환경은 `{workspace}/` 다. 파일
도구는 §4 의 규칙으로 `.aimon/` 을 보지 못한다. 기본이 닫힌 쪽이다. 모델에게 제어 저장소를 열려면 어셈블리가 명시한다
(`pathRules(List.of())`, 부트스트랩의 `ExecutionEnvironmentSpec.controlWritable`).

- **CLI** 의 제어 저장소는 워크스페이스 파일 시스템 위의 `ScopedVirtualFileSystem(vfs, ".aimon")` 뷰다. 그 워크스페이스 루트는
  [실행 환경](execution-environment.md)의 로컬 제공자 절에 있다. 제공자는 워크스페이스를 소유하므로 스테이징 스윕이 돈다.
- **공급 VFS 배치**(GridFS, S3)는 제공자가 빌린 파일 시스템 모드로 돌고 제어 저장소도 같은 VFS 위의 스코프 뷰다. 이 배치의 제약은
  [EE-2](../../backlog/execution-environment-open-items.md) 에 있다.

셸은 `.aimon/` 을 여전히 읽을 수 있다. 로컬에는 셸 경로의 격리가 없기 때문이다. 이 설정이 막는 것은 모델이 파일 도구로 무심코 자기
정의를 고치는 일이지 악의적인 셸 명령이 아니다. 그것을 막으려면 샌드박스 제공자를 쓴다.

## 4. 경로 규칙

`PathRule` 은 파일 시스템 루트 기준 디렉터리 접두어 하나와 접근 수준이다. 대소문자와 유니코드를 접어 세그먼트 단위로 비교하므로
`.AIMON/x` 는 걸리고 `.aimon2/x` 는 걸리지 않는다. `PathRuleVirtualFileSystem` 이 규칙을 적용하며 먼저 덮는 규칙이 결정한다. 브랜치는
부모 규칙을 브랜치 루트로 옮겨 스코프 아래 층에 다시 건다([격리 브랜치](execution-environment-isolation.md)).

| 접근 수준 | 조회 | 그 밖의 접근 |
|---|---|---|
| `DENY` | `exists`, `isDirectory` 는 false. 목록과 검색에서 빠진다 | 읽기, 메타데이터, 쓰기, 삭제, 이동이 `FileAccessDeniedException` |
| `READ_ONLY` | 그대로 | 읽기는 통과. 쓰기, 삭제, 이동, 그 안으로의 복사가 `FileAccessDeniedException` |

- **기본 규칙.** 로컬 제공자는 두 모드 모두에서 `.aimon` 을 `DENY`, 스테이징 루트(`.aimon-staged`)를 `READ_ONLY` 로 건다. 스테이징 영역을
  `.aimon/` 아래에 두지 않는 것은 두 규칙이 겹치지 않게 하려는 것이다.
- **닿는 곳으로 판단한다.** `VfsPaths.resolveUnder` 로 위임의 작업 디렉터리 기준으로 풀고 정규화한 뒤 비교하므로 `./.aimon/x`,
  `a/../.aimon/x`, `{base}/.aimon/x`, `../<base-name>/.aimon/x` 가 모두 걸린다. 작업 디렉터리 아래로 떨어지지 않는 경로는 `DENY` 처럼 다룬다
  (`FileAccessDeniedException("outside this filesystem")`, `exists` 는 false).
- **유니코드 접기.** `VfsPaths.foldCase` 는 NFKC, 소문자·대문자·소문자, NFC 순으로 접는다. 호환 형식, 전체 대소문자 대응, NFD 표기를 덮는다.
  한두 글자 ASCII 이름에 대응하는 BMP 코드 포인트만 전수 검사했고 유니코드 전체를 증명한 것은 아니다. 대소문자를 구분하는 저장소에서는
  `.AIMON` 이라는 사용자 디렉터리도 숨겨지며 이것은 안전한 쪽이다. NTFS 별칭은 [EE-33](../../backlog/execution-environment-open-items.md) 에 있다.
- **재귀 삭제와 이동 거부.** 보호 접두어를 **포함하는 디렉터리**의 재귀 삭제와 이동도 거부한다. 루트 자체도 그렇다.
- **위임.** `getWorkingDirectory` 와 `FileMetadata`(etag 포함)를 그대로 전달한다. 위임을 빌리므로 `initialize()` 와 `close()` 는 아무것도 하지 않는다.
- **도구 오류.** 규칙 거부는 평범한 도구 오류다. `Read`, `Write`, `Edit` 는 예외 메시지를 돌려주고 WARN 으로 기록한다.

이것은 모델의 파일 도구가 프레임워크 상태를 실수로 고치는 일을 막는 장치이지 보안 경계가 아니다. 같은 환경의 셸은 파일에 닿는다.

## 5. `FileStamp`

```text
Read(p)            -> stamps.put(key(p), stamp(p))
Edit(p) / Write(p) -> if exists(p):
                        s = stamps.get(key(p))
                        if s == null      -> error "Read the file before modifying it"
                        if s != stamp(p)  -> error "File changed since it was read; Read it again"
                      write ...
                      stamps.put(key(p), stamp(p))
```

stamp 는 `getMetadata` 에서 만들고, 둘 다 있으면 etag 를 우선한다. 두 stamp 는 양쪽에 etag 가 있으면 etag 로, 아니면 크기와 수정 시각으로
비교한다. 백엔드가 지켜야 할 계약과 백엔드별 표는 [백엔드 계약](../filesystem/backend-contract.md) 에 있다.

**잡는 것과 잡지 못하는 것.** 실제 파일 상태와 비교하므로 다른 실행이 파일 도구로 바꾼 경우, 셸이 바꾼 경우(`sed -i`, `git checkout`,
포매터), 사람이 바꾼 경우를 잡는다. 검사와 쓰기 사이의 경합은 잡지 못한다.

**키.** stamp 맵의 키는 모델이 넘긴 문자열이 아니라 `FileStamps.key` 가 정규화한 경로다. 기준은 서술자의 작업 디렉터리가 `/` 로 시작하면
그 값이고, 아니면 파일 시스템의 작업 디렉터리다. 브랜치에서 `Read a.txt` 와 `Edit {branch-root}/a.txt` 는 한 키다. 표준 절대 경로
`{workspace}/a.txt` 는 다른 키이므로 "Read the file before modifying it" 으로 거부된다. fail-closed 이고 `Read` 한 번으로 회복한다.

**범위.** `FILE_STAMPS_KEY`(`Map<String, FileStamp>`)는 실행 단위 값이다. 두 실행기가 `createToolContext()` 에서 실행당 한 번, `RoutineExecutor` 가
`buildToolContext()` 에서 발화당 한 번 만들고, 스킬 기반 슬래시 커맨드의 컨텍스트는 명령마다 새 맵을 받는다. 그래서 대화형 세션에서 이전
턴에 읽은 파일을 이번 턴에 덮어쓰려면 다시 읽어야 하고, 포크는 부모의 stamp 를 물려받지 않으며, 루틴의 `Write` 단계는 같은 발화의 앞 단계에서
그 파일을 `Read` 해야 한다. 오류 문구가 다음 행동을 알려 주므로 모델은 왕복 한 번으로 회복한다. 병렬 실행에서 이 맵을 공유하는 규칙은
[병렬 실행](parallel-execution.md) 에 있다.

**`Edit` 과 `Write` 의 비대칭.**

| 경우 | `Edit` | `Write` |
|---|---|---|
| 있는 파일 | stamp 가 있어야 하고 일치해야 한다 | 같다 |
| 없는 파일 | 검사 없음 (존재 여부는 도구가 따로 답한다) | 검사 없음. 새 파일이다 |
| 컨텍스트에 stamp 맵이 없음 | "Read the file before modifying it" 으로 거부 | 검사하지 않는다 |

맵이 없는 컨텍스트는 손으로 만든 컨텍스트나 사용자 정의 호출기다. 이 경우 `Write` 의 가드는 조용히 꺼진다. 실행기는 언제나 맵을 싣는다.

## 6. `ContentSearch`

원격 VFS 에서 파일마다 읽으면 파일 수만큼 왕복이 생기므로 환경이 내용 검색을 맡을 수 있게 한다. `ExecutionEnvironment.contentSearch()` 는 선택
기능이고 비어 있으면 `Grep` 이 파일 시스템을 직접 걷는다.

- **질의.** `ContentQuery` 의 필드는 `pattern`, `path`, `extensions`, `glob`, `caseInsensitive`, `beforeContext`, `afterContext`, `multiline`,
  `cancellation` 이다. 출력 모드와 head limit 은 쿼리에 없고 `GrepTool` 포매터의 일이다.
- **결과.** `ContentSearchResult` 는 파일별 경로와 매치(줄과 컨텍스트)의 정규화 값이다. 경로는 환경의 파일 시스템이 나열하는 모양이다.
- **폴백.** 구현이 대답할 수 없는 질의는 던진다. `Grep` 은 빈 값이든 실패든 걷기로 넘어가며 두 경로 모두 같은 포매터를 쓴다.
- **정렬.** `Grep` 은 두 경로 모두 경로순으로 정렬한다. 바이트가 같은 출력에는 결정적 순서가 필요하다.

**로컬 구현.** `RipgrepContentSearch` 는 제공자 빌드 때 `PATH` 에서 `rg` 를 한 번 찾고(프로세스를 띄우지 않는다), 기본으로 켜져 있으며
`contentSearch(false)` 로 끈다. 걷기와 같은 글을 내도록 `--no-ignore --hidden -a` 로 돈다. `rg` 가 없거나, 패턴을 거부하거나, UTF-8 이 아닌
파일을 만나면 던지고 걷기가 대답한다.

- **멀티라인.** 대답하지 않는다. `rg -U` 는 줄 묶음을 보고하고 `Grep` 의 걷기는 매치한 글을 보고하기 때문이다.
- **timeout 과 취소.** `rg` 의 출력을 별도 스레드가 비우는 동안 호출자가 프로세스, timeout(기본 120초), `ContentQuery.isCancelled()` 를 폴링한다.
  어느 쪽이든 `rg` 를 죽인다. 도는 동안 효력이 있으므로 멈춘 `rg` 가 호출을 붙잡지 못한다.

**보안.** 숨긴(`DENY`) 접두어의 내용은 어느 경로로도 나오지 않는다.

1. 숨긴 접두어이거나 그 아래인 대상은 거부한다. `rg` 는 명령행 경로에 glob 을 걸지 않는다. 거부되면 걷기가 대답하고 경로 규칙 파일 시스템이 숨긴다.
2. 출력에서 숨긴 접두어 아래의 경로를 뺀다.
3. 제외 glob 을 사용자 glob 뒤에 넘긴다. `rg` 는 뒤 glob 이 이기기 때문이다.
4. 대상의 실제 경로를 확인한다. `rg` 는 명령행의 링크를 따라가므로 실제 경로가 루트 안이고 숨긴 접두어 밖이어야 한다. 아니면 걷기의 경로 검증이 막는다.
5. 격리 브랜치의 검색은 부모의 숨긴 접두어를 유지하고, 브랜치 루트와 대상의 실제 경로가 부모 루트 아래이며 숨긴 접두어 밖이어야 한다.

**사용 불가 환경.** 사용 불가 환경을 "결과 없음" 으로 삼키지 않는다. `Grep` 은 `ExecutionEnvironmentUnavailableException` 을 전파하고, `WikiIngest` 는
먼저 소스 디렉터리를 확인해 도구 오류로 바꾼다.

## 7. artifact

`ArtifactCollector` 에 등록되는 경로는 사용자가 나중에 내려받는다. 사용자에게 건네는 경로와 켜는 방법은 [artifact](../agent-execution/artifact.md) 가
소유하고, 이 절은 환경에서 복사하는 규칙을 소유한다. 분기는 `ArtifactArchive.register` 한 곳에 있어 artifact-aware 도구 둘이 갈라지지 않는다.

| 환경 | 등록하는 경로 | 저장소 (`FileArtifact.getStorage`) |
|---|---|---|
| `durable() == true` | 도구가 쓴 경로 | `WORKSPACE` |
| `durable() == false` | 복사한 제어 저장소 경로 | `CONTROL` |

비영속 환경의 경로는 실행 뒤에 남는다고 보장되지 않는다. 격리 브랜치도 여기에 해당한다.

**복사 위치.** 제어 저장소의 `artifacts/{archiveKey}/{relativePath}` 다.

- `archiveKey` 는 `ArtifactCollector` 가 실행마다 갖는 값이다. 포크는 자기 `ExecutionId`, 턴은 게시하지 않는 `archive:…` 값이다.
- 디렉터리 이름으로 쓸 때 `ArtifactArchive.directoryName` 이 `[A-Za-z0-9._-]` 밖의 문자를 `_` 로 바꾼다. 로컬 저장소가 `:` 를 거부하기 때문이다.
  키가 UUID 로 끝나므로 두 키가 한 디렉터리로 합쳐지지 않는다.
- `relativePath` 는 환경의 작업 디렉터리 기준 상대 경로다. 이름이 같은 파일이 다른 디렉터리에 있어도 사본이 겹치지 않는다. 작업 디렉터리 밖의
  파일은 정규화한 전체 경로로 보관한다.
- 복사는 옆의 임시 경로에 쓰고 보관 경로로 옮긴다. 중간에 실패해도 앞선 등록이 만든 사본을 잃지 않는다.
- 같은 파일을 다시 등록하면 자기 사본을 덮어쓰고 실행당 상한에는 마지막 크기로 한 번만 센다.

**상한.** `ArtifactPolicy` 의 기본값은 파일당 50 MB, 실행당 총 100 MB 다.

**등록하지 않는 경우.** 쓰기 자체는 성공했으므로 오류가 아니다. 등록하지 않고 도구 결과에 `[artifact not registered: …]` 메모를 덧붙인다.

- 상한 초과
- 크기를 읽지 못함. 상한을 검사할 수 없으므로 0 바이트로 치지 않는다
- 비영속 환경인데 제어 저장소가 없음
- 복사 실패 또는 등록 중 다른 예외

제공자는 `durable()` 만 정직하게 답하면 되고 자기 쪽 artifact 경로나 전송 상한을 두지 않는다. `ArtifactArchive` 는 `at.aimon.core.tools` 에서 파일
시스템을 쥐는 유일한 클래스이고, 그것은 모델이 일하지 않는 제어 저장소다.

## 8. 남은 것

- [EE-2](../../backlog/execution-environment-open-items.md): 공급 VFS 배치의 제어 저장소와 셸.
- [EE-33](../../backlog/execution-environment-open-items.md): 경로 규칙과 Windows 의 이름 별칭.

## 9. 하지 말 것

- **제어 저장소를 파일 도구에 노출하지 말 것.** 스킬 파일은 `stage()` 를 거친다(§3). 어셈블리가 명시해 여는 것은 예외다.
- **제어 저장소 기본 디렉터리에 `.aimon/` 을 붙이지 말 것.** 데이터가 고아가 된다(§3.2).
- **경로 규칙을 `rootRelative` 로 판단하지 말 것.** 닿는 곳으로 판단하고 밖이면 막는다(§4).
- **경로 규칙을 보안 경계로 설명하지 말 것.** 셸은 지나간다(§4).
- **제외 glob 만으로 `rg` 를 막았다고 여기지 말 것**(§6).
- **사용 불가 환경을 "결과 없음" 으로 삼키지 말 것**(§6).
- **비영속 환경의 경로를 artifact 로 등록하지 말 것.** 복사 후 제어 저장소 경로를 등록한다(§7).
- **크기를 모르는 파일을 0 바이트로 쳐서 상한을 통과시키지 말 것**(§7).

## 10. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 절 | 타입 | 파일 |
|---|---|---|
| §3 | `OrcaAgentRuntimeFactory`, 태스크 저장소 | `agent/impl/orca/OrcaAgentRuntimeFactory.java`, `subagent/task/VfsTaskOutputStore.java`, `subagent/task/VfsTaskResultStore.java`, `subagent/task/VfsSessionSnapshotStore.java` |
| §3 | `ExecutionEnvironmentSpec`, `StackPaths` (부트스트랩) | `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/spec/ExecutionEnvironmentSpec.java`, `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/assemble/StackPaths.java` |
| §3 | `AgentSetupFactory.createFileSystem` (CLI) | `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java` |
| §4 | `PathRule`, `VfsPaths` | `filesystem/PathRule.java`, `filesystem/VfsPaths.java` |
| §4 | `PathRuleVirtualFileSystem` | `filesystem/impl/PathRuleVirtualFileSystem.java` |
| §4 | `defaultPathRules` | `environment/impl/LocalExecutionEnvironmentProvider.java` |
| §5 | `FileStamp`, `FileStamps` | `environment/FileStamp.java`, `tools/file/FileStamps.java` |
| §5 | `ReadTool`, `EditTool`, `WriteTool` | `tools/file/ReadTool.java`, `tools/file/EditTool.java`, `tools/file/WriteTool.java` |
| §6 | `ContentSearch`, `ContentQuery`, `ContentSearchResult` | `environment/ContentSearch.java`, `environment/ContentQuery.java`, `environment/ContentSearchResult.java` |
| §6 | `RipgrepContentSearch`, `GrepTool` | `environment/impl/RipgrepContentSearch.java`, `tools/file/GrepTool.java` |
| §7 | `ArtifactArchive`, `ArtifactPolicy` | `tools/artifact/ArtifactArchive.java`, `tools/artifact/ArtifactPolicy.java` |
| §7 | `ArtifactCollector`, `FileArtifact`, `ArtifactStorage` | `agent/artifact/ArtifactCollector.java`, `agent/artifact/FileArtifact.java`, `agent/artifact/ArtifactStorage.java` |

관련 문서: [실행 환경](execution-environment.md), [스테이징](execution-environment-staging.md), [격리 브랜치](execution-environment-isolation.md),
[백엔드 계약](../filesystem/backend-contract.md), [artifact](../agent-execution/artifact.md).
