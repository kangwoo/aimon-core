# 가상 파일시스템 백엔드 계약 (Virtual File System Backend Contract)

> Status: **IMPLEMENTED** — GFS-01 ~ GFS-05 · GFS-07 이 반영되었고, GFS-06 은 조사 결과 이미 해소되어
> 있어 기각했다. 미해결로 남아 있던 질문 넷은 §9 의 결정으로 닫혔다. 남은 항목은 §11.
>
> 적용 대상: `aimon-core` — `at.aimon.core.filesystem{,.impl,.impl.local,.config,.exception}` ·
> `aimon-filesystem-gridfs` · `aimon-filesystem-s3` · `aimon-filesystem-testkit`
>
> 관련 규칙: [`.claude/rules/architecture.md`](../../../.claude/rules/architecture.md),
> [`.claude/rules/multi-instance-design.md`](../../../.claude/rules/multi-instance-design.md),
> [`.claude/rules/testing.md`](../../../.claude/rules/testing.md)
>
> 관련 문서: [`../agent-execution/artifact.md`](../agent-execution/artifact.md) (VFS 에 쌓인 파일이
> 사용자에게 건네지는 경로), [`../integration/sandbox.md`](../integration/sandbox.md),
> [`../../overview/architecture.md`](../../overview/architecture.md)

---

## 1. 무엇을 풀었는가

`VirtualFileSystem` 은 백엔드를 바꿔 끼울 수 있는 추상화로 광고되어 있었지만, **계약이 말하지 않은
것들이 백엔드마다 달랐다.** 로컬에서 돌던 에이전트를 GridFS 로 옮기면 디렉토리가 사라지고, 업로드가
실패하면 원본 파일이 같이 없어졌다. 이 문서는 그 간극을 메운 변경(GFS-01 ~ 07)과, 그 과정에서
드러난 네 개의 질문에 대한 결정을 기록한다.

| 선언 (호출자가 믿던 것) | 그때의 실제 보장 | 항목 |
|---|---|---|
| 백엔드를 바꿔도 같은 코드가 같게 동작한다 | 디렉토리가 무엇인지 계약에 없었다. 로컬은 inode 를 갖고 GridFS 는 갖지 않아, `createDirectory` 한 디렉토리가 목록에서 사라졌다 | GFS-01 |
| `write(path, …)` 는 파일을 덮어쓴다 | 기존 리비전을 **먼저 지우고** 업로드했다. 업로드가 실패하면 원본이 남지 않았다 | GFS-02 |
| `list` / `search` 는 디렉토리를 본다 | 버킷 전체를 클라이언트로 끌어와 자바에서 걸렀다 | GFS-03 |
| `getUsageSummary()` 로 사용량을 잰다 | 백엔드 전체 합계뿐이었다. 경로별 쿼터를 매길 방법이 없었다 | GFS-04 |
| 애플리케이션이 이미 `MongoClient` 를 갖고 있다 | 설정이 connection string 만 받아, 같은 클러스터에 커넥션 풀을 하나 더 열었다 | GFS-05 |
| 기본값만으로도 GridFS 백엔드가 뜬다 | 기본 DB 이름이 `at/aimon` 이었고 MongoDB 는 이름의 `/` 를 거부한다 — 설정하지 않은 모든 실행이 실패했다 | GFS-07 |
| `maxFileSize` 는 파일시스템 설정이다 | **로컬 백엔드에만** 있었다. GridFS·S3 는 무제한이라 폭주한 쓰기 한 번이 저장소를 채울 수 있었다 | §5 |

| ID | 결과 |
|----|------|
| **GFS-01** | 디렉토리 시맨틱을 `VirtualFileSystem` 계약으로 명문화하고, GridFS 는 마커 문서로 그것을 표현한다 |
| **GFS-02** | 업로드가 성공한 **뒤에** 이전 리비전을 회수한다. 실패하면 원본이 그대로 남는다 |
| **GFS-03** | `filename` 접두 범위 쿼리 + projection. 합계는 서버 사이드 `$group` |
| **GFS-04** | `getUsageSummary(String path)` 를 계약에 추가하고 Local · Scoped · GridFS 가 구현한다 |
| **GFS-05** | `GridFSFileSystem(GridFSConfig, MongoClient)` 로 외부 클라이언트를 빌려 쓴다. `ownsClient` 가 소멸 책임을 가른다 |
| **GFS-06** | **기각** — 릴리스 스크립트에 GridFS 테스트 제외가 이미 없었고, `ReleaseGateMatchesCiGateTest` 가 그것을 지키고 있었다 |
| **GFS-07** | `FileSystemFactory` 의 기본 DB 이름 `at/aimon` → `aimon` |
| **§5** | `maxFileSize` 를 `VirtualFileSystem` 계약으로 승격. 세 백엔드가 **하나의 시행 지점**을 공유한다 |

---

## 2. 디렉토리는 계약이지 백엔드 사정이 아니다

백엔드는 디렉토리의 물리적 실재에 대해 서로 다른 말을 한다. 로컬에는 진짜 inode 가 있고, GridFS 와 S3
는 평평한 키 공간이라 디렉토리란 어떤 파일 경로의 부작용에 지나지 않는다 — 비어 있을 수 없고, 마지막
파일이 사라지면 같이 사라진다. **그 차이가 호출자에게 도달하면 안 된다.** `createDirectory("out")` 을
실행한 에이전트는 어느 백엔드에서든 부모 목록에서 `out` 을 봐야 한다.

그래서 계약은 저장 모델이 아니라 **관측 가능한 동작**으로 쓰였다.

- **빈 디렉토리가 존재한다.** `createDirectory` 이후 `exists` / `isDirectory` 가 참이고 `getMetadata`
  가 `isDirectory` 를 세운 메타데이터를 돌려준다 — 아무것도 담고 있지 않아도
- **유도된 디렉토리도 존재한다.** `docs/a.txt` 를 쓰면 `docs` 는 디렉토리이며, 명시적으로 만든 것과
  구별되지 않는다
- **`list` 는 파일과 바로 아래 하위 디렉토리**를, `listRecursive` 는 **파일만** 돌려준다
- **반환 경로는 VFS 루트 기준**이다. `list("docs")` 는 `docs/a.txt` 이지 `a.txt` 가 아니다
- **없는 경로를 나열하면 던진다.** 빈 목록이 아니라 `FileNotFoundException` 이고, 일반 파일을 나열하면
  `InvalidPathException` 이다
- **`delete` 는 빈 디렉토리를 지우고** 내용이 있으면 거부한다. 트리 삭제는 `deleteRecursive` 의 일이다
- **파일과 디렉토리는 충돌할 수 없다.** 파일이 점유한 경로에 `createDirectory` 하면
  `FileAlreadyExistsException`, 디렉토리 이름으로 쓰기를 하면 거부다

### 2.1 GridFS 가 그것을 표현하는 방법 — 마커

GridFS 에는 디렉토리가 없으므로 만들어 넣는다. 마커는 **`/` 로 끝나는 `filename`** 을 가진 빈 문서이며
`metadata.type` 이 `directory` 다(`GridFSFileSystem.DIRECTORY_MARKER_TYPE`). 조회는 마커와 유도
디렉토리를 하나로 합쳐 본다 — `docs/` 마커가 있든 없든 `docs/a.txt` 하나면 `docs` 는 디렉토리다.

**그 모양은 AIMON 예약이다.** 이 클래스가 관리하는 버킷 안에서 `/` 로 끝나는 filename 과
`metadata.type = "directory"` 는 AIMON 의 것이고, 다른 주체가 써서는 안 된다. 둘 중 하나의 모양을 한
외부 문서는 디렉토리로 읽히므로, 진짜 파일이라면 이름을 점유한 채 `read(String)` 에서 보이지 않게 된다.

이것은 **문서화된 제약이지 강제된 제약이 아니다.** 강제하려면 모든 배포가 기동마다 버킷을 훑거나
마커를 별도 컬렉션으로 빼야 하는데, 두 비용 모두 *애초에 공유하면 안 되는 버킷*을 방어하려고 지불된다
(§9). 이 파일시스템은 자기 버킷을 가리켜야 하고, `GridFSConfig#getBucketName()` 이 그것을 쉽게 만들려고
존재한다.

---

## 3. 쓰기는 원본을 파괴하지 않는다

GFS-02 는 이 작업에서 **유일하게 데이터를 잃던 결함**이었다. 옛 순서는 지우고-올리기였다. 업로드가
실패하면 — 네트워크가 한 번 딸꾹하면 — 파일은 새것도 옛것도 없이 사라졌다. 에이전트 작업 공간을 받치는
VFS 에서 이것은 "쓰기가 실패했다" 가 아니라 "파일이 증발했다" 로 관측된다.

지금은 **올리고-회수하기**다. `GridFSFileSystem.uploadReplacing` 이 업로드를 열기 전에 대체할 리비전
id 를 스냅샷하고(그 뒤에 찍으면 방금 쓴 리비전이 목록에 들어와 자기 자신을 지운다), 스트림이 성공적으로
닫힌 뒤에야 옛 리비전을 회수한다. 회수는 조용히 실패해도 된다 — 새 리비전은 이미 durable 하고 모든
조회에서 이기므로, 남은 옛 리비전은 정확성이 아니라 저장 공간의 문제다.

조회가 "이긴다" 는 것은 정렬로 보장된다. `findFile` 은 `uploadDate` 내림차순, 동률이면 `_id`
내림차순으로 첫 문서를 취한다 — `uploadDate` 는 밀리초 단위라 같은 밀리초에 두 번 쓰면 동률이 나고,
그때 `_id` 가 순서를 결정한다.

두 쓰기 경로가 같은 업로드 스트림을 지나가는 것도 여기서 정해졌다. 벌크 경로가
`GridFSBucket.uploadFromStream` 을 쓰지 않고 손으로 복사하는 이유는 그 헬퍼가 복사 중 예외를
`MongoGridFSException` 으로 감싸 버려, 크기 거부가 계약이 약속한 `InsufficientStorageException` 이 아니라
백엔드 오류로 둔갑하기 때문이다.

---

## 4. 조회는 버킷 전체를 읽지 않는다

`list` · `listRecursive` · `search` · `getUsageSummary` 는 전부 `fs.files` 전체를 클라이언트로 끌어와
자바에서 걸렀다. 파일 수에 선형이고, 그 상수는 네트워크였다.

지금은 두 가지로 바뀌었다.

- **접두 범위 쿼리.** `underPrefix(prefix)` 가 `filename >= prefix` 와 `filename < prefix⁺` 의 논리곱을
  만든다(`prefix⁺` 는 마지막 문자를 하나 올린 것). 필요한 필드만 projection 으로 내린다
- **서버 사이드 집계.** `usageUnder` 는 `$match` + `$group` 으로 `totalSize` 와 `fileCount` 를 몽고에서
  접는다. 마커는 `metadata.type != "directory"` 로 제외되며, 이 `$ne` 는 metadata 자체가 없는 문서도
  매치하므로 마커 도입 이전에 쓰인 파일도 정상적으로 집계된다

### 4.1 `getUsageSummary(String path)` — 기본 구현은 경로를 무시한다

계약에 추가된 것은 `default` 메서드다. 기본 구현은 `path` 를 무시하고 백엔드 전체 합계를 돌려준다.
`abstract` 로 두면 트리 밖의 모든 백엔드 구현이 컴파일되지 않고, 무엇보다 **쿼터에서 과대 보고는 안전한
방향**이기 때문이다 — 덜 보고하면 한도를 넘겨 쓰게 되지만, 더 보고하면 일찍 막힐 뿐이다.

`directoryCount` 는 **마커와 유도 디렉토리를 합쳐** 센다. 마커만 세면 `docs/a.txt` 하나뿐인 버킷의
디렉토리 수가 0 이 되어, `list` 가 보여 주는 것과 요약이 서로 다른 말을 하게 된다.

---

## 5. 최대 파일 크기 — 로컬 전용 설정에서 전 백엔드 계약으로

`maxFileSize` 는 원래 `LocalFileSystemConfig` 의 필드였다. 같은 에이전트를 GridFS 나 S3 로 옮기면 그
한도가 조용히 사라졌다 — 쿼터 시스템 없이 작업 공간에 줄 수 있는 유일한 상한인데, 그것이 백엔드 선택에
따라 있다 없다 했다.

이제 계약이 말한다. **백엔드는 캡을 제공하지 않아도 되지만, 제공한다면 아래가 성립한다.**

- **두 쓰기 경로 모두 강제한다.** `write(...)` 와 `openOutputStream(...)` 이 돌려주는 스트림이 하나의
  시행 지점(`SizeLimitedOutputStream`)을 공유한다. 스트리밍 API 를 골라서 캡을 피할 수 없다
- **세는 것은 실제로 쓰인 바이트**이지 선언된 `contentLength` 가 아니다. 길이를 적게 부르거나 `-1` 로
  불러도 똑같이 잘린다. 선언된 길이가 이미 캡을 넘으면 한 바이트도 저장하기 전에 추가로 거부할 수 있다
- **거부는 `InsufficientStorageException`** 이고, `write` 또는 선을 넘는 그 `write`/`close` 호출에서
  나온다. `openOutputStream(...)` 자체에서는 나오지 않는다 — 그 시점엔 판단할 바이트가 없다
- **`write(...)` 는 잘린 파일을 남기지 않는다.** 거부된 뒤 그 경로는 없거나 이전 내용을 들고 있다.
  절대 남으면 안 되는 것은 *들어맞은 접두부*다
- **스트리밍 경로의 잔여물은 백엔드마다 다르다.** 그 스트림의 수명은 호출자 것이므로, 넘치기 전에
  받아들여진 바이트가 보일 수도(로컬) 통째로 버려질 수도(GridFS) 있다. 각 백엔드가 자기가 무엇을 하는지
  명시한다

### 5.1 시행 지점은 하나다

`SizeLimitedOutputStream` 은 `at.aimon.core.filesystem` — 즉 `impl` 이 **아닌** 중립 패키지에 있다.
ArchUnit 이 도메인 트리 밖에서 `at.aimon.core.filesystem.impl..` 을 import 하는 것을 막으므로, 다른
모듈의 백엔드가 이 클래스에 닿으려면 다른 방법이 없고, 닿지 못하면 각자 자기 카운터를 기르다가 셋이 서로
다른 경계에서 거부하게 된다.

캡 없음을 뜻하는 값은 `VirtualFileSystem.NO_MAX_FILE_SIZE = -1` 이며, 각 설정 객체의 상수는 이것을
가리킨다(`GridFSConfig.NO_MAX_FILE_SIZE`, `S3Config.NO_MAX_FILE_SIZE`) — 센티널이 백엔드마다 어긋나지
않게 하려는 것이다. **`0` 은 무제한이 아니라 아무것도 쓸 수 없는 캡**이고, `-1` 외의 음수는 거부된다.
넘침 판정은 `additional > maxBytes - written` 형태로 쓰여 `long` 오버플로를 만들지 않는다.

거부 직전에 `onLimitExceeded()` 가 한 번 불린다. 백엔드가 이미 넘긴 것을 되돌리는 자리다 — GridFS 는
업로드를 abort 해서 청크를 회수하고, S3 는 버퍼를 거부 표시해 `close()` 가 객체를 올리지 않게 한다.

### 5.2 백엔드가 갈리는 자리

| 백엔드 | `write(...)` 가 거부할 때 | `openOutputStream(...)` 이 거부할 때 |
|---|---|---|
| **Local** | 대상 파일을 **지운다** — 이전 내용도 남지 않는다 | 받아들여진 접두부가 디스크에 남는다 |
| **GridFS** | 업로드를 abort — 청크도 파일 항목도 남지 않고, **이전 리비전이 그대로** 선다 | 같음. abort 되어 아무것도 남지 않는다 |
| **S3** | 요청을 보내기 **전에** 거부 — 이전 객체 그대로 | 메모리에만 버퍼링했으므로 객체가 생기지 않는다 |

계약이 "잘린 접두부를 남기지 않는다" 까지만 약속하고 "이전 내용을 보존한다" 로 올라가지 않은 이유가 이
표다. 로컬은 거부 시 대상을 지우므로 이전 내용을 지켜 주지 못하고, GridFS·S3 는 지켜 준다. 셋 모두가
지킬 수 있는 것만 계약이 되고, 나머지는 각 백엔드의 javadoc 이 말한다.

---

## 6. 커넥션 소유권과 기본값

`GridFSFileSystem` 의 생성자는 둘이다. `GridFSConfig` 만 받으면 connection string 으로 클라이언트를
직접 만들고(`ownsClient = true`), `MongoClient` 를 함께 받으면 그것을 빌려 쓴다(`ownsClient = false`).
`close()` 는 자기가 만든 것만 닫는다 — **만든 쪽이 닫는다** 는 규칙이 여기서도 그대로다
([`../../overview/scope-model.md`](../../overview/scope-model.md) §2).

살아 있는 클라이언트는 **설정 값 객체에 들어가지 않는다.** `GridFSConfig` 는 불변 값이고 `equals` /
`hashCode` / `toString` 의 대상이며, 그 자리에 커넥션 풀이 있으면 값이 아니게 된다. 대신
`GridFSConfig.forSharedClient(...)` 가 connection string 없는 설정을 만들고, 클라이언트는 생성자로
따로 들어온다.

GFS-07 은 배선 기본값 하나다. `FileSystemFactory.createFromEnvironment()` 의 기본 DB 이름이
`at/aimon` 이었는데 MongoDB 는 데이터베이스 이름의 `/` 를 거부하므로, GridFS 를 골랐지만 DB 를 지정하지
않은 모든 실행이 실패했다. 지금은 `aimon` 이다.

---

## 7. 계약을 한 곳에서 기술한다 — `aimon-filesystem-testkit`

위의 계약 문장들은 백엔드마다 **각자의 테스트**가 각자의 이해대로 확인하고 있었다. 그래서 GFS-01 같은
결함이 가능했다 — 각 백엔드가 자기 자신에 대한 설명과는 일치했다.

`AbstractVirtualFileSystemContractTest` 는 그 문장들을 한 번만 적고, 백엔드는
`VirtualFileSystem newFileSystem()` 하나만 구현해 상속한다. 현재 `LocalFileSystem` ·
`ScopedVirtualFileSystem` · `GridFSFileSystem` 이 같은 테스트를 돈다.

**모듈이 따로 있는 이유**는 취향이 아니다. 자연스러운 자리는 `aimon-core` 의 `java-test-fixtures`
였고, 그것은 **작동하지 않는다**: 발행 플러그인(`com.vanniktech.maven.publish` 0.30.0)이 그 플러그인에
반응해 Gradle 9 에서 제거된 내부 생성자를 호출하고, 발행 대상 모듈은 설정 단계에서
`NoSuchMethodError: ProjectDerivedCapability.<init>(Project, String)` 로 죽는다. 평범한 모듈 하나면
릴리스 인프라를 건드리지 않고 같은 곳에 도달한다.

**발행하지 않는 것도 의도**다. `aimon.publishable` 을 붙이지 않았고, `aimon-bom` 은 관리 목록을 그
플러그인에서 유도하므로 이 모듈을 자동으로 빼놓는다 — `aimon-sample-*` 이 빠지는 방식과 같다.

캡을 지원하지 않는 백엔드를 위해 두 번째 훅 `newFileSystem(long maxFileSize)` 이 있는데, 이것은
`abstract` 가 아니라 **`null` 을 돌려주는 `default`** 다. 트리 밖의 구현이 상속할 수 있어야 하고,
`assumeTrue` 로 건너뛰면 "캡 케이스가 돌지 않았다" 가 리포트에 **skip 으로 남는다** — 조용히 통과하는
것과 구별된다.

---

## 8. 테넌트 격리 — 논리 격리로 확정

멀티 테넌트 배치에서 테넌트를 어떻게 가를 것인가는 이 작업 내내 열려 있던 질문이고, **접두 기반 논리
격리로 확정**했다. `ScopedVirtualFileSystem` 이 경로 접두로 시야를 자르고, 쿼터는 §4.1 의
`getUsageSummary(String path)` 로 이미 잴 수 있다.

그 결과 **테넌트 단위 백업과 물리적 삭제는 애플리케이션의 몫으로 남는다.** 프레임워크는 테넌트를
데이터베이스나 버킷으로 갈라 주지 않는다. 갈라야 하는 배치는 `GridFSConfig` 가 이미 `databaseName` 과
`bucketName` 을 받으므로 애플리케이션이 테넌트마다 파일시스템 인스턴스를 만들면 되고, 그 라우팅 계층은
프레임워크가 제공하지 않는다.

---

## 9. 설계 결정

| 쟁점 | 결정 | 기각한 대안과 이유 |
|---|---|---|
| GridFS 에서 디렉토리를 어떻게 표현하나 | `/` 로 끝나는 filename + `metadata.type = "directory"` 마커. 조회는 마커와 유도 디렉토리를 합쳐 본다 | **마커를 별도 컬렉션에** — 접두 범위 스캔 하나로 끝나던 조회가 두 저장소 조인이 된다. **trailing slash 없이 metadata 로만** — 서브트리 조회가 접두 범위 쿼리로 표현되지 않아 §4 의 최적화를 되돌려야 한다 |
| 마커 모양의 예약을 강제하나 | **문서화만 한다.** javadoc 과 이 문서가 제약을 못박고, 런타임 검사는 없다 | **기동 시 프리플라이트 스캔** (`initialize()` 가 trailing-slash filename 과 `metadata.type` 을 훑는다) — 모든 배포가 매 기동 2회 쿼리를 지불해서, *애초에 공유하면 안 되는 버킷*을 방어한다. **확인 절차만 문서화** — 지금 결정과 실질적으로 같으면서 운영 문서만 늘린다 |
| `getUsageSummary` 의 `directoryCount` | 마커와 **유도 디렉토리를 합쳐** 센다 | **마커만 세기** — `docs/a.txt` 뿐인 버킷이 디렉토리 0 개로 보고되어, 같은 버킷에 대해 `list` 와 요약이 서로 다른 말을 한다 |
| 경로별 사용량을 계약에 어떻게 넣나 | `default` 메서드로 추가하고, 기본 구현은 `path` 를 무시한 전체 합계 | **`abstract`** — 트리 밖의 모든 백엔드 구현이 깨진다. 기본값이 과대 보고인 것은 의도다: 쿼터에서 덜 보고하는 쪽만 위험하다 |
| 테넌트 격리 | `ScopedVirtualFileSystem` 접두 기반 **논리 격리로 확정**. 테넌트 단위 백업·물리 삭제는 애플리케이션 몫 | **물리 분리 라우팅 계층** — 테넌트→파일시스템 라우팅을 프레임워크가 떠안는다. `GridFSConfig` 가 이미 DB·버킷을 받으므로 필요한 배치는 애플리케이션에서 조립할 수 있다. **논리 기본 + 선택적 물리** — 두 경로를 다 살려 두면 쿼터·백업 의미가 배치마다 갈린다. **보류** — 이미 논리 격리로 돌고 있는 코드가 미결로 남는다 |
| `maxFileSize` 의 범위 | **전 백엔드 계약으로 승격.** GridFS·S3 에 대응 필드와 업로드 중 검사를 추가 | **승격 안 함(로컬 전용 유지)** — 같은 작업 공간이 백엔드를 바꾸면 무제한이 된다. **GridFS 에만 추가** — S3 만 남는 구멍이고, 계약이 아니라 백엔드 두 개의 우연한 일치가 된다 |
| 캡을 어디서 시행하나 | `SizeLimitedOutputStream` 하나. `impl` 이 아닌 중립 패키지 | **백엔드마다 카운터** — ArchUnit 이 `filesystem.impl..` import 를 막으므로 외부 모듈은 어차피 각자 기르게 되고, 셋이 서로 다른 경계에서 거부한다 |
| 거부 후 잔여물을 계약이 어디까지 약속하나 | **"잘린 접두부를 남기지 않는다"** 까지. 스트리밍 잔여물은 백엔드가 각자 명시 | **전 백엔드 동일 보장** — 로컬 스트리밍은 이미 디스크에 나간 바이트를 되돌릴 수 없다. 약속할 수 없는 것을 계약에 적으면 계약이 거짓말을 한다 |
| 공유 계약 테스트의 소재지 | 발행하지 않는 별도 모듈 `aimon-filesystem-testkit` | **`aimon-core` 의 `java-test-fixtures`** — 발행 플러그인이 Gradle 9 에서 제거된 내부 생성자를 호출해 설정 단계에서 실패한다(§7). 릴리스 인프라를 건드리는 것보다 모듈 하나가 싸다 |
| 캡을 지원하지 않는 백엔드의 계약 테스트 | `newFileSystem(long)` 을 `null` 반환 `default` 로 두고 `assumeTrue` 로 skip | **`abstract`** — 트리 밖 서브클래스가 컴파일되지 않는다. **조용히 통과** — 캡 케이스가 돌았는지 아닌지가 리포트에서 구별되지 않는다 |

---

## 10. 하지 말 것

- **GridFS 버킷을 다른 쓰기 주체와 공유하지 말 것.** `/` 로 끝나는 filename 과
  `metadata.type = "directory"` 는 AIMON 예약이며, 강제되지 않는다(§2.1)
- **`write(...)` 가 실패한 뒤 이전 내용이 남아 있다고 가정하지 말 것.** GridFS·S3 는 남기지만 로컬은
  대상을 지운다. 계약이 약속하는 것은 "잘린 접두부가 없다" 까지다(§5.2)
- **`openOutputStream(...)` 이 캡 위반을 던질 거라 기대하지 말 것.** 그 시점엔 판단할 바이트가 없다.
  거부는 선을 넘는 `write` 또는 `close` 에서 온다
- **`maxFileSize` 의 `0` 을 무제한으로 읽지 말 것.** 무제한은 `NO_MAX_FILE_SIZE`(-1) 뿐이고, `0` 은
  아무것도 쓸 수 없는 캡이다
- **백엔드 안에 새 크기 카운터를 만들지 말 것.** 시행 지점은 `SizeLimitedOutputStream` 하나이고,
  되돌릴 것이 있으면 `onLimitExceeded()` 를 구현한다
- **빌려온 `MongoClient` 를 닫지 말 것.** `ownsClient == false` 인 파일시스템의 `close()` 는
  클라이언트를 건드리지 않는다 — 반대로, 준 쪽이 닫아야 한다
- **`getUsageSummary(String)` 의 반환값을 백엔드 확인 없이 정밀 쿼터로 쓰지 말 것.** 기본 구현은
  `path` 를 무시하고 전체 합계를 돌려준다(과대 보고는 의도된 안전 방향이다)
- **`aimon-filesystem-testkit` 에 `aimon.publishable` 을 붙이지 말 것.** BOM 이 그 플러그인에서 관리
  목록을 유도하므로, 붙이는 순간 테스트 전용 모듈이 공개 API 표면으로 올라간다

---

## 11. 남은 것

| 항목 | 상태 |
|---|---|
| S3 가 공유 계약 테스트를 돌지 않는다 | 열림 — `aimon-filesystem-s3` 는 `S3FileSystemMaxFileSizeTest` 만 갖고 있다. 계약 테스트를 붙이려면 디렉토리 마커를 S3 에서 어떻게 표현할지부터 정해야 하고, 그것은 §2 를 이 백엔드에 다시 적용하는 별도 작업이다 |
| S3 스트리밍 경로가 전량 메모리 버퍼링 | 열림 — 큰 파일에 취약하다. 멀티파트 업로드로 바꾸면 거부 시 abort 가 필요해지고, 지금의 "아무것도 보내지 않는다" 라는 강한 보장이 GridFS 와 같은 모양으로 약해진다 |
| GridFS 접두 범위 쿼리가 기대는 인덱스 | 열림 — 드라이버가 버킷 초기화 시 만드는 `fs.files` 인덱스에 의존하고 있고, 코드는 자기 쿼리 모양에 맞는 인덱스를 스스로 보장하지 않는다 |
| 테넌트별로 다른 캡 | 열림 — `ScopedVirtualFileSystem` 은 쓰기를 위임하므로 캡은 언제나 delegate 의 것이다. 스코프마다 다른 캡을 주려면 이 계층에 자체 필드가 필요하다 |

---

## 부록 · 참조 파일 지도

| 항목 | 파일 |
|---|---|
| 계약 | `at/aimon/core/filesystem/VirtualFileSystem.java` (디렉토리 시맨틱 · 최대 파일 크기 · `NO_MAX_FILE_SIZE`), `FileSystemUsage.java`, `FileMetadata.java` |
| 캡 시행 | `at/aimon/core/filesystem/SizeLimitedOutputStream.java`, `at/aimon/core/filesystem/exception/InsufficientStorageException.java` |
| 로컬 백엔드 | `at/aimon/core/filesystem/impl/local/LocalFileSystem.java`, `LocalFileSystemConfig.java` |
| 스코프 백엔드 | `at/aimon/core/filesystem/impl/ScopedVirtualFileSystem.java` |
| 배선 기본값 | `at/aimon/core/filesystem/config/FileSystemFactory.java` |
| GridFS 백엔드 | `at/aimon/filesystem/core/gridfs/GridFSFileSystem.java` (`DIRECTORY_MARKER_TYPE`, `NEWEST_REVISION_FIRST`, `uploadReplacing`, `underPrefix`, `usageUnder`, `countDirectories`, `SupersedingUploadStream`), `GridFSConfig.java` |
| S3 백엔드 | `at/aimon/filesystem/core/s3/S3FileSystem.java` (`S3OutputStream`), `S3Config.java` |
| 공유 계약 테스트 | `at/aimon/filesystem/testkit/AbstractVirtualFileSystemContractTest.java` (`aimon-filesystem-testkit`), `modules/aimon-filesystem-testkit/build.gradle.kts` (모듈이 따로 있는 이유) |
| 계약 테스트 구현 | `LocalFileSystemContractTest`, `ScopedVirtualFileSystemContractTest` (`aimon-core`), `GridFSFileSystemContractTest` (`aimon-filesystem-gridfs`) |
| 캡 통합 테스트 | `GridFSFileSystemMaxFileSizeTest`, `S3FileSystemMaxFileSizeTest` |
| 설정 테스트 | `GridFSConfigTest`, `S3ConfigTest` |

## 관련 문서

- [`../../overview/architecture.md`](../../overview/architecture.md) — `VirtualFileSystem` 이 놓인 자리
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 만든 쪽이 닫는다 (§6 의 `ownsClient`)
- [`../agent-execution/artifact.md`](../agent-execution/artifact.md) — VFS 에 쌓인 파일이 사용자에게 건네지는 경로
- [`../integration/sandbox.md`](../integration/sandbox.md) — 격리 실행 환경과 파일시스템의 경계
- [`../../features/tool/tool-development-guide.md`](../../features/tool/tool-development-guide.md) — `Read`/`Write`/`Edit` 가 이 계약 위에 선다
