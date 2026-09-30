# 실행 환경 — 등록 항목 47건 (열림 37 · 닫힘 10)

출처는 `ExecutionEnvironment` 구현 작업이다. 설계는 [`../design/tool/execution-environment.md`](../design/tool/execution-environment.md)
이고, 구현 계획(승인본)과 구현이 그 계획에서 벗어난 점은
[`../design/tool/execution-environment-implementation.md`](../design/tool/execution-environment-implementation.md) 에 있다.
계획의 §9 "Open questions" 는 그 작업이 기본값을 정해 두고 진행한 질문들이고, 설계의 §14 "열린 질문" 은 설계 시점에 남긴
것이다. 그중 **이 변경 밖으로 결과가 번지는 것**만 여기로 올렸다. 각 항목의 "출처" 줄이 원래 자리(계획 §9 의 `Q번호`, 설계
§14, 계획 §10 의 차이 목록)를 가리킨다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**다. 줄 번호는 **마지막 확인 날짜와 함께** 적는다. EE-1~EE-14 의
인용은 2026-09-28 기준이고, EE-15~EE-25 는 빌드 리뷰 3 의 비차단 지적을 2026-09-29 에 옮긴 것이다(같은 리뷰의 차단 지적과
같은 계열의 지적 셋 — 스테이징 스윕의 심볼릭 링크, `relPath`/`contentKey` 검증, `rg` 의 링크 대상과 timeout — 은 이 변경에서
고쳤으므로 여기 없다). EE-26~EE-34 는 빌드 리뷰 4 의 비차단 지적 가운데 이 변경에서 고치지 않은 것을 2026-09-29 에 옮긴
것이다. 같은 리뷰의 차단 지적(링크된 스킬 디렉터리가 빈 사본으로 스테이징됨)은 링크 규칙(설계 §4.4)으로 고쳤고, 그 규칙이
EE-16 도 닫았다. 테스트 Javadoc 이 낡았다는 지적도 이 변경에서 고쳤다. EE-35~EE-39 는 PR #195 의 리뷰 1 에서 나온 비차단
지적 가운데 고치지 않은 것을 2026-09-29 에 옮긴 것이다. 같은 리뷰의 차단 지적(링크 규칙이 거부한 스킬 하나가 스킬 목록
전체와 `Skill` 도구를 무너뜨림)과 비차단 지적 여덟 건은 고쳤고, 그 내용은 구현 문서 §10 에 있다. 그 리뷰가 EE-27 과
EE-33 의 범위를 넓혔으므로 두 항목의 본문도 고쳤다. EE-40 · EE-41 은 aimon-sandbox 의 워크스페이스 샌드박스 설계를 이 구현에
대조한 리뷰에서 2026-09-29 에 옮긴 것이고, 같은 리뷰가 EE-1 · EE-18 · EE-30 의 본문에 샌드박스 쪽 영향을 더했다. 그
리뷰가 샌드박스 구현 순서의 선행 조건으로 꼽은 셋(EE-18 · EE-40 · EE-41)은 2026-09-29 에 한 변경(PR #196)에서 닫았고,
EE-42 는 그 PR 의 리뷰가 남긴 것이고 2026-09-29 에 닫았다. EE-43~EE-45 는 EE-42 의 승인된 설계
([`../design/tool/execution-environment-ee42-workflow-attributes.md`](../design/tool/execution-environment-ee42-workflow-attributes.md))가
남긴 열린 질문 가운데 이 변경 밖으로 결과가 번지는 것이다. 결정 항목 여섯(EE-6 · EE-7 · EE-8 · EE-12 · EE-13 ·
EE-14)은 2026-09-29 에 메인테이너가 결정했다. 결정은 남은 일을 없애지 않고 확정할 뿐이므로(`README.md` 규칙 넷) 여섯 다 열림으로 센다. 결정 전에 전제를 소스로
확인했고, 그중 셋(EE-7 · EE-12 · EE-13)은 착수 범위가 항목의 서술보다 크다는 것이 드러나 각 결정문에 적었다.
워크플로 격리 브랜치를 다룬 다섯(EE-8 · EE-25 · EE-27 · EE-28 · EE-29)은 2026-09-29 에 한 변경에서 닫았다. 그중 EE-8 이
결정 항목이었으므로 결정됨이되 열린 항목은 이제 다섯이다. 그 변경의 설계와 구현이 설계에서 벗어난 점은
[`../design/tool/workflow-isolation-hardening.md`](../design/tool/workflow-isolation-hardening.md) 에 있다. EE-46 · EE-47 는
그 설계의 열린 질문(Q5, Q1 · Q4) 가운데 이 변경 밖으로 결과가 번지는 것을 옮긴 것이다. EE-43~EE-45 는 같은 시기에
EE-42 를 다룬 변경이 먼저 썼으므로 이 둘은 EE-46 부터 번호를 받았다.

---

## EE-1 — 외부 도구 제공자 둘이 컴파일되지 않는다 · **열림**

**무엇을.** aimon-sandbox 의 `OrcaSandboxToolProvider` 와 aimon-browser 의 `OrcaBrowserToolProvider` 를 새 SPI 로 옮긴다.

**왜.** 둘 다 `OrcaToolProviderContext.getFileSystem()`(브라우저는 `getCredentialStore()` 와 함께)을 읽는데, 이 메서드는
삭제되었다(설계 §6). 다음 코어 릴리스를 올리는 순간 두 저장소의 빌드가 깨진다. 샌드박스는 워크스페이스 샌드박스 설계에서
샌드박스 전용 실행 도구가 모두 없어지고(명령·파일은 코어 도구가 샌드박스 환경에서 처리한다) 슬롯 수명을 다루는
오케스트레이터 도구(`SandboxList`·`SandboxStart`·`SandboxStop`)만 남아 실행마다
`EXECUTION_ENVIRONMENT` 에서 바인딩을 꺼내고, 브라우저는 산출 파일을 `env.fileSystem()` 에, artifact 는 §9.3 경로로 보내야 한다.

**어디.** 두 외부 저장소. 코어 쪽 SPI 는 `modules/aimon-core/src/main/java/at/aimon/core/agent/orca/tool/OrcaToolProviderContext.java`.

**언제 다시 볼까.** 이 변경이 들어간 코어를 두 저장소가 처음 의존할 때.

출처: 계획 §8 "Public-SPI breaks".

## EE-2 — 공급 VFS 배치에서 스테이징 사본을 호스트 셸이 못 보고, 사본이 쌓인다 · **열림**

**무엇을.** `FileSystemSpec.supplied` / `.factory`(GridFS·S3) 배치의 스테이징과 셸을 정한다 — 별도 제어 저장소를
요구하거나, 셸을 원격 쪽으로 보내거나, 스테이징 영역을 제공자마다 나눠 스윕한다.

**왜.** 그 배치에서 로컬 제공자는 `.fileSystem(vfs)` 모드로 돈다. 사본은 원격 VFS 의 `/.aimon-staged/…` 에 쓰이므로 파일
도구는 읽지만 호스트 `LocalShell` 은 못 본다 — `bash ${AIMON_SKILL_DIR}/x.sh` 가 실패한다. 이 배치에서 셸 경로가 원래
안 맞던 한계(설계 §1.5)가 명시된 것이다. 그리고 공유 VFS 는 다른 런타임이 쓰는 중일 수 있어 시작 스윕을 하지 않으므로,
사본은 서로 다른 스킬 버전 수만큼 쌓인다.

**어디.** `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/assemble/StackAgentRuntimeProvisioner.java` 의
`createStores`, `modules/aimon-core/src/main/java/at/aimon/core/environment/impl/LocalExecutionEnvironmentProvider.java`
의 `sweepStaging` 호출 조건.

**언제 다시 볼까.** 공급 VFS 배치에서 스킬 스크립트를 셸로 돌려야 할 때, 또는 원격 저장소의 `.aimon-staged/` 가 눈에 띄게
커질 때.

출처: 계획 §9 Q11 · Q16.

## EE-3 — 로드 뒤 디스크에서 고친 스킬은 재시작 전까지 스테이징이 실패한다 · **열림**

**무엇을.** 스테이징 중 내용이 달라진 스킬을 **다시 키를 매겨**(복사하며 계산한 해시로) 스테이징하거나, CLI·부트스트랩에
사용자 레이어 스킬 재적재 경로를 둔다.

**왜.** `contentKey` 는 레지스트리가 스킬을 읽을 때 한 번 계산된다(설계 §4.4). `stage()` 는 복사하는 바이트를 다시
해시해 키와 다르면 `StagingException` 을 던진다 — 새 바이트를 옛 키로 저장하지 않으려는 것이다. 그런데 CLI 와 부트스트랩
main 에는 `reloadSkill` / `reloadAll` 을 부르는 경로가 없다. 사용자가 `.aimon/skills/foo/scripts/x.sh` 를 고치면 그 스킬은
프로세스를 재시작할 때까지 호출마다 실패한다. 예전에는 고친 스크립트가 그냥 돌았다. 오류 문구는 "재시작하거나 스킬
레지스트리를 다시 읽으라" 고 할 수 있는 일을 말한다(리뷰 3 의 비차단 지적).

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/environment/impl/LocalStaging.java` 의 `copy` 끝.

**언제 다시 볼까.** 사용자가 이 오류를 처음 보고할 때, 또는 스킬 hot-reload 를 붙일 때.

출처: 계획 §7 "Host skill directory edited without a registry reload" · 리뷰 3.

## EE-4 — `.aimon-staged/` 를 `.gitignore` 에 넣는 코드가 없다 · **열림**

**무엇을.** 로컬 제공자가 `.aimon-staged/.gitignore`(`*`)를 쓰거나, CLI 에 프로젝트 초기화를 둔다.

**왜.** 설계 §9.2 는 "CLI 의 프로젝트 초기화가 `.gitignore` 에 넣는다" 고 적지만 그런 초기화는 없다. 지금은 문서가 사용자에게
직접 넣으라고 안내한다. 넣지 않으면 스테이징 사본이 사용자 저장소의 `git status` 에 뜬다.

**어디.** 없음(추가할 자리는 `LocalExecutionEnvironmentProvider` 또는 CLI 조립).

**언제 다시 볼까.** 사용자 저장소에 사본이 커밋되었다는 보고가 있을 때.

출처: 계획 §9 Q4.

## EE-5 — stamp 의 etag: GridFS 는 과민하고 로컬은 없다 · **열림**

**무엇을.** GridFS 에 내용 해시 etag 를 두고(드라이버 5.x 에서 `md5` 가 없어졌다), 로컬 파일 시스템에 선택형 내용 해시 etag 를
둘지 정한다.

**왜.** GridFS 의 etag 는 파일 문서의 `ObjectId` 다. 쓸 때마다 바뀌므로 내용이 같아도 "바뀌었다" 로 판정한다 — 안전한
오탐이다. 로컬은 etag 없이 크기+mtime 을 쓴다. APFS/ext4 는 나노초라 괜찮지만 초 단위 mtime 인 파일 시스템(HFS+, 일부 네트워크
마운트)에서는 같은 초, 같은 크기의 재작성을 놓친다.

**어디.** `modules/aimon-filesystem-gridfs/src/main/java/at/aimon/filesystem/core/gridfs/GridFSFileSystem.java` 의
`getMetadata`, `modules/aimon-core/src/main/java/at/aimon/core/environment/FileStamp.java`.

**언제 다시 볼까.** 초 단위 mtime 파일 시스템에서 낡은 쓰기가 통과했다는 보고가 있을 때, 또는 GridFS 에서 "Read it again"
이 불필요하게 잦을 때.

GridFS etag 가 설계 §7 의 "GridFS(md5)" 와 다르다는 점은 빌드 리뷰 3 이 짚었고, 계획 §10.2 에 차이로 적었다.

출처: 계획 §9 Q6 · Q7 · 빌드 리뷰 3.

## EE-6 — 번들 스킬 머티리얼라이저를 은퇴시킬지 · **열림 · 결정됨** *(2026-09-29)*

**무엇을.** 클래스패스 저장소가 직접 스테이징할 수 있게 되었으니, 시작 시 `.aimon/bundled-skills` 로 복사하는
`BundledSkillMaterializer` 를 없앤다.

**왜.** 머티리얼라이즈된 사본은 이제 파일 도구가 읽지 못하는 제어 저장소에 있고, 모델에게는 다시 `.aimon-staged/` 로
복사되어 간다. 스킬 한 버전당 복사가 한 번 더 있는 셈이다. 없애려면 부트스트랩 레이어 구성, `StackPaths`, 스타터의 AOT 힌트
(`AimonRuntimeHints`), 문서화된 디렉터리 배치가 함께 바뀐다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/skill/repository/BundledSkillMaterializer.java`,
`OrcaAgentRuntimeFactory.buildMaterializedSkillRegistry`.

**언제 다시 볼까.** 시작 시간이나 제어 저장소 용량이 문제가 될 때.

출처: 계획 §9 Q17.

**결정 (2026-09-29) — 지금은 은퇴시키지 않는다. 메인테이너가 골랐다.** 비용은 시작할 때 스킬 한 버전당 복사가 한 번 더 있는
것뿐이고, 없애는 쪽은 부트스트랩 레이어 구성 · `StackPaths` · `AimonRuntimeHints` · 문서화된 디렉터리 배치를 함께 바꾼다.
재검토 트리거(시작 시간 · 제어 저장소 용량)는 그대로 둔다 — 결정되었지만 그 트리거를 지키는 자리로 열려 있다.

## EE-7 — 부트스트랩의 제공자는 런타임마다 하나이고, 축출과 함께 닫힌다 · **열림 · 결정됨** *(2026-09-29)*

**무엇을.** 제공자를 애플리케이션 수명으로 두고 런타임별 축출 훅을 붙일지 정한다.

**왜.** 설계 §4.3 은 제공자를 Application 수명으로 적지만, 부트스트랩은 워크스페이스를 런타임마다 만들므로 제공자도 런타임마다
만들고 그 런타임의 teardown 싱크가 닫는다. 그래서 축출된 테넌트의 백그라운드 `Bash` 작업은 셸을 잃고 `BashOutput` 이 셸이
닫혔다는 오류를 보고한다. 예전에 `ownedShell` 이 런타임과 함께 닫히던 것과 같은 동작이다.

**어디.** `StackAgentRuntimeProvisioner.createProvider`.

**언제 다시 볼까.** 축출이 잦은 멀티테넌트 배포에서 백그라운드 명령이 끊긴다는 보고가 있을 때.

출처: 계획 §9 Q15.

**결정 (2026-09-29) — 제공자를 애플리케이션 수명으로 올리고, 런타임 축출에는 그 런타임 몫만 정리하는 훅을 단다(설계 §4.3
과 맞춘다). 메인테이너가 골랐다.**

**결정 전에 확인한 전제 (규칙 넷).** 둘이 이 항목의 서술보다 크다.

1. 제공자가 런타임마다 생기는 것은 부트스트랩의 선택만이 아니다. `LocalExecutionEnvironmentProvider` 는 워크스페이스 루트
   하나에 묶여 만들어지고(`StackAgentRuntimeProvisioner.createLocalStores` 가 런타임별 `workspaceRoot(workspace)` 를 넘긴다),
   팩토리 배치의 SPI 도 `AgentRuntimeId` 를 받아 제공자를 돌려주는 함수다(`createProvider`, 2026-09-29 기준 301~325행). 제공자
   하나가 여러 런타임을 받으려면 **요청의 런타임으로 워크스페이스를 고르게** 바뀌어야 하고, 팩토리 SPI 의 모양도 바뀐다.
2. 제공자만 올려서는 축출 뒤 백그라운드 명령을 되찾지 못한다. 작업 목록인 `BackgroundBashManager` 는
   `OrcaBashToolProvider`(41행)가 런타임의 `ToolRegistry` 마다 새로 만든다 — agent-scoped 다. 축출된 런타임이 다시 만들어지면
   셸이 살아 있어도 새 `BashOutput` 은 옛 task id 를 모른다. 결정의 목적(축출이 백그라운드 명령을 끊지 않는다)을 이루려면
   작업 목록도 런타임보다 오래 살아야 하고, 멀티 인스턴스 원칙대로라면 저장소를 인터페이스로 분리해야 한다. 다른 노드에서
   다시 열린 세션은 그 프로세스에 닿지 못하므로, 거기서는 "다른 노드에서 도는 작업" 으로 보고하는 것이 한계다.

그래서 착수 범위는 셋이다 — 제공자가 요청에서 런타임별 워크스페이스를 고르기, 축출 훅, 백그라운드 작업 목록의 수명
상향. EE-13 의 종료 도구도 같은 작업 목록에서 작업을 찾으므로 함께 설계한다.

## EE-8 — 워크플로 브랜치가 쓴 `.aimon/` 파일은 병합에서 거절된다 · **닫힘** *(2026-09-29)*

**무엇을.** 이 동작을 확정하거나(문서화), 병합 전에 걸러낼지 정한다.

**왜.** 브랜치 안의 `.aimon/x` 는 `.worktrees/{key}/.aimon/x` 로 쓰이고(루트 기준 규칙이라 막히지 않는다), 병합하면 루트
`.aimon/x` 로 올라가다가 `DENY` 에 걸려 fail-fast 로 실패한다. 예전에는 브랜치가 제어 평면에 쓸 수 있었으므로 의도한 방어지만,
사용자에게는 "병합 실패" 로 보인다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/environment/WorktreeMerge.java`.

**언제 다시 볼까.** 스크립트가 브랜치에서 `.aimon/` 을 쓰는 사례가 나올 때.

출처: 계획 §9 Q20.

**결정 (2026-09-29) — 브랜치 안의 `.aimon/` 쓰기를 쓰는 시점에 거절한다. 메인테이너가 골랐다.** 병합 전에 조용히 걸러내는
쪽(쓴 데이터가 말없이 사라진다)과 지금 동작을 문서화만 하는 쪽은 기각했다.

**결정 전에 확인한 전제.** 서술대로다 — `LocalIsolatedEnvironment` 는 부모의 경로 규칙 파일 시스템 위에
`ScopedVirtualFileSystem`(접두어 `.worktrees/{key}`)을 얹으므로, 루트 기준 `.aimon/` 규칙이 브랜치 안의 `.aimon/` 에 닿지
않는다. 그리고 현재 동작은 서술보다 나쁘다. `WorktreeMerge` 의 승격은 fail-fast 이되 **롤백이 없어서**(61~75행 Javadoc)
`.aimon/` 파일을 만나기 전에 승격된 파일은 남는다 — 사용자가 보는 것은 "병합 실패" 가 아니라 "절반 병합" 이다. 쓰는 시점에
거절하면 이것도 없어진다.

구현은 브랜치 파일 시스템에 부모와 같은 경로 규칙을 브랜치 루트 기준으로 한 번 더 거는 것이다. 샌드박스는 git worktree 로
격리하므로 같은 규칙이 코어 밖에서도 필요하다 — EE-41 의 공개 팩토리(`VirtualFileSystems.withPathRules`)로 표현되는지
착수할 때 확인한다.

### 닫힘 (2026-09-29)

결정대로 쓰는 시점에 거절한다. `LocalIsolatedEnvironment` 가 부모의 경로 규칙을 브랜치 루트 기준으로 옮겨
(`.aimon` → `.worktrees/{key}/.aimon`) `VirtualFileSystems.withPathRules` 로 한 번 더 건다 — EE-41 의 팩토리로 표현된다.
규칙은 부모의 것을 옮긴 것이라 규칙을 비운 어셈블리의 브랜치에는 규칙이 없다. 테스트는
`LocalIsolatedEnvironmentTest.branchLocalControlDirectoryIsDenied`·`shellWrittenControlFileIsNeverPromoted`·
`branchRulesFollowTheParent`.

착수해 보니 적힌 것과 달랐던 점이 셋이다. (1) **규칙 층을 어디 두느냐가 처방의 전부였다.** 스코프 **위**에 두면
스코프의 작업 디렉터리가 `"."` 이라 절대 경로(`{ws}/.aimon/x`, `{ws}/.worktrees/k/.aimon/x`)가 규칙을 비껴간다. 그래서
규칙 층은 스코프 **아래**, 브랜치 경로의 표기가 이미 한 위임 경로로 줄어든 자리에 있다. "같은 규칙을 한 번 더 건다" 는
서술만으로는 우회되는 구현이 나온다. **(빌드 리뷰에서 보탬.)** 닫을 때는 "모든 표기가 한 위임 경로로 줄어든다" 고
적었는데 참이 아니었다. 스코프가 브랜치 루트의 호스트 경로를 정규화 전에, 대소문자를 구분해 맞췄으므로
`{ws}/./.worktrees/k/.aimon/x`·`{ws}//.worktrees/k/.aimon/x`·`{ws}/.worktrees/K/.aimon/x` 는
`.worktrees/k/.worktrees/k/.aimon/x` 로 중첩되어 브랜치 규칙을 비껴갔고, 병합이 그것을 브랜치 `k` 자신의 `.aimon/` 으로
올렸다. 같은 변경의 후속 커밋이 정규화한 뒤 대소문자를 무시하고 맞추도록 고쳤다(설계 §8.4 DV-7). 여전히 줄지 않는 표기
— 브랜치 안에서 `.worktrees/` 를 가리키는 경로 — 는 EE-46 이다. (2) 적용되는 것은 "쓰기 거절" 보다 넓은 `DENY` 다 — 브랜치의 `.aimon/` 은 루트의 것처럼 보이지도
않는다. 그래서 셸이 거기 쓴 파일은 목록에 나오지 않아 **승격되지 않고 보고되지도 않은 채** 브랜치 디렉터리에 남는다.
루트 `.aimon/` 에 셸이 쓴 파일과 같은 처지이고, 기각한 "조용히 걸러내기" 와 다른 점은 파일 도구가 그 파일을 모델에게
애초에 보여 주지 않는다는 것이다. (3) 브랜치 파일 시스템으로 브랜치 자신을 `deleteRecursive(".")` 할 수 없게 되었다(보호
디렉터리를 품은 트리) — 브랜치 정리는 부모를 거친다. main 코드에 그렇게 지우는 곳은 없다.

## EE-9 — 훅 컨텍스트 대부분에 실행 환경 서술자가 없다 · **열림**

**무엇을.** `HookContext.getEnvironmentDescriptor()` 를 PreCompact/PostCompact(`CompactionRequest` 경유), OnStart/OnStop,
SubagentStart/Stop, PermissionRequest/Denied 컨텍스트에도 채운다.

**왜.** 이번 변경은 PreTool/PostTool 컨텍스트만 채웠다. 나머지 훅은 `Optional.empty()` 를 받아, "명령이 어디서 도는가" 를
알아야 하는 훅이 여전히 호스트를 가정하게 된다(설계 §10 이 경고한 재발).

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/hook/execution/HookContext.java`, 각 `hook/event/*Context.java`,
`agent/compact/CompactionRequest.java`.

**언제 다시 볼까.** 서술자를 읽는 훅이 처음 생길 때, 또는 샌드박스 제공자를 붙일 때.

출처: 계획 §10 차이 목록.

## EE-10 — `AgentEnvironmentSnapshot` 이 작업 디렉터리를 여전히 든다 · **열림**

**무엇을.** 스냅숏에서 `workingDirectory` 를 뺀다.

**왜.** 사용자 컨텍스트 블록의 작업 디렉터리는 이제 실행 서술자의 값이 이긴다. 스냅숏 쪽 값은 서술자가 비었을 때(사용 불가 환경)만
쓰이는 죽은 필드에 가깝고, 에이전트 단위로 한 번 모으는 값이 실행마다 다른 사실을 들고 있는 모양이다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/AgentEnvironmentSnapshot.java`,
`agent/prompt/UserContextMessageBuilder.java`.

**언제 다시 볼까.** 스냅숏 수집기를 손볼 때.

출처: 계획 §10 차이 목록.

## EE-11 — 스케줄 루틴에는 read stamp 가 없다 · **열림**

**무엇을.** 루틴의 `ToolContext` 에 `FILE_STAMPS_KEY` 를 넣을지 정한다.

**왜.** 두 실행기는 실행마다 stamp 맵을 넣지만 루틴은 넣지 않았다. 넣으면 기존 파일을 덮어쓰는 루틴 `Write` 단계가 `Read`
없이는 실패하게 된다 — 이미 배포된 루틴이 깨진다. 지금은 예전과 같다: 루틴의 `Edit` 은 늘 "읽지 않았다" 로 거절되고 `Write` 는
검사하지 않는다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/scheduling/RoutineExecutor.java` 의 `buildToolContext`.

**언제 다시 볼까.** 루틴에서 `Edit` 을 쓰고 싶다는 요청이 있을 때.

출처: 계획 §10 차이 목록.

## EE-12 — 스킬 선언 훅의 셸은 여전히 호스트다 · **열림 · 결정됨** *(2026-09-29)*

**무엇을.** 스킬 파일이 선언한 훅의 셸 액션을 실행 환경의 셸로 보낼지, 호스트에 두고 신뢰된 스킬에만 허용할지 정한다.

**왜.** `AimonStackBuilder` 의 `skillHookShell` 은 호스트 `LocalShell` 이다. 샌드박스를 쓰면 같은 스킬의 스크립트가 `Bash` 로는
샌드박스에서, 훅으로는 호스트에서 돈다 — 스킬 작성자가 호스트 셸을 얻는 통로이기도 하다.

**어디.** `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java` 의 `skillHookShell`.

**언제 다시 볼까.** 샌드박스 제공자를 붙일 때.

출처: 설계 §14 · 계획 §9 Q12.

**결정 (2026-09-29) — 스킬 선언 훅의 셸 액션은 실행 환경의 셸에서 돈다. 실행 환경이 없는 시점에 발화하는 훅은 셸 액션을
쓸 수 없다. 메인테이너가 골랐다.** 신뢰된 스킬에만 호스트 셸을 허용하는 쪽은 기각했다 — 신뢰 모델을 하나 더 들이고, 허용된
스킬은 여전히 샌드박스를 우회한다.

**결정 전에 확인한 전제.** 셋이 서술보다 크다.

1. 갈래가 둘이다. 서술은 `AimonStackBuilder`(265~268행)만 적지만, CLI 의 `AgentSetupFactory`(544~545행)도 따로 호스트
   `LocalShell` 을 만들어 스킬 파서에 넘긴다.
2. 셸은 **파싱할 때** 묶인다. `new SkillHookSetParser(new DefaultShellActionExecutor(skillHookShell))` — 실행기가 스킬을 읽는
   시점에 셸을 쥔다. 실행 환경은 실행마다 고르므로, `ShellActionExecutor` 가 셸을 쥐지 않고 발화 시점의 컨텍스트에서 얻도록
   모양이 바뀐다.
3. 훅 컨텍스트에는 환경이 없다. `HookContext` 는 `getEnvironmentDescriptor()`(서술자, 그것도 PreTool/PostTool 에만 — EE-9)를
   줄 뿐 `ExecutionEnvironment` 자체를 주지 않는다. 그리고 선언형 훅 13종 가운데 `OnConfigReload` · `OnSessionStart` ·
   `OnSessionEnd` · `OnStart` · `OnStop` 은 실행 밖에서 발화할 수 있다. 이벤트마다 실행 안에서 발화하는지는 착수할 때 센다.
   실행 밖 이벤트의 셸 액션은 **파싱 시점에 거부**한다 — 발화 시점의 실패는 조용하기 때문이다(`DefaultShellActionExecutor` 는
   훅이 막히지 않도록 실패를 삼킨다).

그래서 EE-9 가 이 항목의 선행 조건이 된다. 다만 서술자가 아니라 환경을 훅 컨텍스트에 싣는 쪽으로 넓혀야 한다. 실행 밖
이벤트에 셸 액션을 쓴 기존 스킬은 깨진다(0.x 정책상 허용한다).

## EE-13 — 백그라운드 명령을 끝낼 수단이 없다 · **열림 · 결정됨** *(2026-09-29)*

**무엇을.** 백그라운드 `Bash` 를 끝내는 도구를 코어에 둘지, 환경이 상한을 정하게 할지 정한다.

**왜.** 백그라운드 명령의 상한은 24시간(`BACKGROUND_TIMEOUT_MS`)이고 끝내는 도구가 없다. 샌드박스에서는 도는 명령이 idle
판정을 막아 슬롯을 하루 동안 깨워 둔다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/tools/bash/BashTool.java`.

**언제 다시 볼까.** 샌드박스 제공자를 붙일 때.

출처: 설계 §14 · 계획 §9 Q12.

**결정 (2026-09-29) — 둘 다 한다. (1) 코어에 백그라운드 `Bash` 를 끝내는 도구를 둔다. (2) 실행 환경이 백그라운드 명령의
상한을 정한다 — 모델이 끝내기를 잊었을 때의 안전장치다. 메인테이너가 골랐다.**

**결정 전에 확인한 전제.** (1) 이 서술보다 크다. 작업 목록(`BackgroundBashManager`)에서 작업을 빼는 것으로는 명령이 멈추지
않는다. 백그라운드 명령은 `CompletableFuture.supplyAsync` 안에서 동기 `VirtualShell.execute(...)` 를 부르는 것이고
(`BashTool.executeInBackground`, 2026-09-29 기준 370~385행), `CompletableFuture.cancel` 은 그 스레드를 인터럽트하지 않는다.
`VirtualShell` SPI 에는 취소 수단이 없다(`execute` 두 개와 `getWorkingDirectory` · `supports` · `close` 뿐). 그래서 종료 도구는
**셸 SPI 에 취소를 들이는 일**이다 — `ExecutionOptions` 에 취소 신호를 싣거나 실행이 핸들을 돌려주게 하는 식으로. 샌드박스
셸도 같은 SPI 를 구현하므로 원격 명령 취소가 계약에 들어간다. `ShellFeature` 로 지원 여부를 알리면 취소를 못 하는 셸에서는
도구가 오류로 답할 수 있다.

(2) 는 실행 환경(또는 서술자)에 백그라운드 상한을 싣고 `BashTool` 이 `BACKGROUND_TIMEOUT_MS`(132행) 대신 그 값을 쓰게 하는
것이다. 환경이 정하지 않으면 지금의 24시간이다. 작업 목록의 수명은 EE-7 의 결정과 얽힌다.

## EE-14 — `Environment` 에는 `timeZone` 만 남았다 · **열림 · 결정됨** *(2026-09-29)*

**무엇을.** `Environment` 를 `UserLocale` 같은 이름으로 옮기고 없앨지 정한다.

**왜.** 작업 디렉터리·platform·OS 버전은 실행 서술자로 옮겨졌다. 남은 `timeZone` 은 환경이 아니라 사용자·애플리케이션의
속성인데, 이름은 여전히 "환경" 이라 `ExecutionEnvironment` 와 헷갈린다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/Environment.java`.

**언제 다시 볼까.** 다음 공개 SPI 정리 때.

출처: 설계 §14.

**결정 (2026-09-29) — `Environment` 를 없애고 `timeZone` 을 `UserLocale`(이름은 착수할 때 확정)로 옮긴다. EE-1 을 내보내는
코어 릴리스에 함께 넣어, 공개 SPI 의 파괴적 변경을 사용자가 한 번만 겪게 한다. 메인테이너가 골랐다.**

**결정 전에 확인한 전제.** 서술대로다 — `Environment` 의 필드는 `timeZone` 하나다. 다만 파급은 서술이 암시하는 것보다 넓다.
`at.aimon.core.agent.Environment` 를 import 하는 파일이 main 에 46개, 테스트를 포함하면 170개다(2026-09-29). `HookContext` 의
Javadoc 은 아직 `Environment` 를 "working directory, platform, OS version" 으로 적는다(21행) — 옮길 때 같이 고친다.

## EE-15 — 스킬 명령 경로는 스테이징 예외 두 종류만 잡는다 · **열림**

**무엇을.** `SkillBackedCommandExecutor` 가 `stage()` 에서 나오는 모든 실패를 `CommandExecutionResult.failure` 로 바꾸게 한다.

**왜.** 지금은 `StagingException | ExecutionEnvironmentUnavailableException` 만 잡는다. `stage()` 는 `InvalidPathException`
(디렉터리 이름에 `:` 가 든 스킬)이나 `LocalStaging` 의 쓰기에서 나온 백엔드 예외도 던질 수 있고, 그것들은 실패 결과가 아니라
예외로 새어 나간다. `SkillTool` 은 바깥 catch 가 막아 준다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/command/execution/skill/SkillBackedCommandExecutor.java` 의 스테이징
호출(2026-09-29 기준 88행).

**언제 다시 볼까.** 슬래시 명령이 스킬 스테이징 중 예외로 끝났다는 보고가 있을 때, 또는 `stage()` 의 예외 계약을 좁힐 때.

출처: 빌드 리뷰 3.

## EE-16 — 호스트 경로 스킬 안의 심볼릭 링크 파일이 스테이징된다 · **닫힘** *(2026-09-29)*

**무엇을.** `ReadOnlyLocalFileSystem` 이 링크를 따라가지 않게 하거나, 루트 밖을 가리키는 링크를 목록에서 뺀다.

**왜.** `Files.isRegularFile` 은 링크를 따라가므로, 스킬 디렉터리 안의 링크 파일은 그 대상의 내용이 워크스페이스로 복사된다.
입력(호스트 스킬 디렉터리)은 운영자가 정하는 것이라 차단은 아니다. 계획 §3 의 표도 "symlinks are followed, as today" 라고 적었다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/filesystem/impl/local/ReadOnlyLocalFileSystem.java` 의 목록 필터.

**언제 다시 볼까.** 스킬 디렉터리를 운영자가 아닌 쪽(원격 저장소, 사용자 업로드)에서 받게 될 때.

출처: 빌드 리뷰 3.

### 닫힘 (2026-09-29)

빌드 리뷰 4 의 차단 지적을 고치며 링크 규칙(설계 §4.4)이 생겼다. `ReadOnlyLocalFileSystem` 은 링크를 따라가되, 훑거나 읽는
항목의 실제 경로가 스킬 저장소 루트 안이거나 운영자가 명시한 허용 루트(`PathSkillRepository.builder(root).allowedLinkRoot(...)`)
안일 때만 따라간다. 그 밖을 가리키는 링크 파일은 이제 스테이징되지 않고, 링크와 실제 경로를 밝힌 오류로 그 스킬의 적재가
실패한다. 루트 안을 가리키는 링크를 따라가는 것은 의도된 동작이다. 테스트는 `SkillLinkStagingTest` 와
`ReadOnlyLocalFileSystemTest.linkedFileConfined`.

## EE-17 — 스테이징이 프로세스 사이에서 경합한다 · **열림**

**무엇을.** 임시 형제 디렉터리에 복사한 뒤 이름을 바꾸는 방식으로 스테이징을 원자화한다.

**왜.** `LocalStaging` 의 잠금은 JVM 안에서만 유효하다. 같은 워크스페이스를 쓰는 두 프로세스는 한쪽의 `deleteRecursive(target)`
(중단된 복사 정리)와 다른 쪽의 복사를 섞을 수 있다. 설계 원칙의 멀티 인스턴스 요구와 맞지 않는다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/environment/impl/LocalStaging.java` 의 `stage` / `copy`.

**언제 다시 볼까.** 한 워크스페이스를 여러 노드나 프로세스가 공유하는 배치를 지원할 때.

출처: 빌드 리뷰 3.

## EE-18 — 백그라운드 `Bash` 가 사용 불가 환경과 notice 를 다루지 않는다 · **닫힘** *(2026-09-29)*

**무엇을.** 백그라운드 `Bash` 가 사용 불가 환경에서 오류를 내고, `ShellCommandResult.notices()` 를 결과에 싣게 한다.

**왜.** 세 가지다. 사용 불가 환경에서도 "Background task started" 를 보고한다. `BackgroundBashTask` 가 notice 를 버린다. timeout
경로에는 notice 가 아예 없다. 빌드 리뷰 1 부터 열려 있던 지적이다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/tools/bash/BashTool.java` 의 백그라운드 분기,
`BackgroundBashTask.java`.

**언제 다시 볼까.** 샌드박스 제공자를 붙일 때(notice 를 처음 내는 셸), 또는 EE-13 을 다룰 때. 워크스페이스
샌드박스 설계의 구현 순서 3단계(OpenSandbox 제공자)가 이 항목을 선행 조건으로 둔다 — 샌드박스 셸은 세션 재생성·샌드박스
소실을 notice 로만 알리므로, 백그라운드 경로가 그것을 버리면 모델은 cwd 가 초기화된 것을 모른다.

출처: 빌드 리뷰 1 · 3.

### 닫힘 (2026-09-29)

세 가지를 모두 고쳤다. (1) 백그라운드 분기는 셸을 부르기 전에 환경이 `UnavailableExecutionEnvironment` 인지 보고, 그렇다면
포그라운드와 같은 문구(`UnavailableExecutionEnvironment.message()`)의 오류를 돌려준다 — "Background task started" 를 보고하지
않는다. (2) `BackgroundBashTask` 가 결과의 notice 를 보관하고 `BashOutput` 이 완료·실패 보고의 출력 앞에
`[environment] ...` 줄로 싣는다. 출력과 같은 한 번 읽기 계약이고(`takeNotices()`), `filter` 정규식은 notice 에 적용되지 않는다.
PR #196 의 리뷰가 찾은 경합도 고쳤다 — `BashOutput` 의 대기가 원시 future 에 걸려 있어 완료 핸들러가 결과를
기록하기 전에 깨어나면 exit code·출력·notice 없이 "Failed" 를 보고할 수 있었다. 이제 대기와 상태는 기록된 결과만
보고(`settled`), `BashOutput` 은 상태를 먼저 읽고 출력을 읽는다. (3) timeout·실행 실패 경로에도 notice 가 실리도록 `ShellExecutionException`(과 `ShellTimeoutException`)에 `notices()` 와 그것을
받는 생성자를 더했다. 포그라운드 `Bash` 의 timeout·실패 오류도 이제 notice 를 앞에 싣는다. 셸 구현(샌드박스 셸)이 새 생성자로
notice 를 넘겨야 실제로 보인다. 테스트는 `BashToolTest` 와 `BashOutputToolTest` 의 notice·사용 불가 환경 케이스.

## EE-19 — artifact 보관이 이름 충돌·중복 집계·조용한 실패를 낸다 · **열림**

**무엇을.** 보관 경로에 원본의 상대 경로를 반영하고, 재등록을 한 번만 세고, 크기를 모를 때와 예외가 났을 때 note 를 붙인다.

**왜.** 네 가지다. `a/report.md` 와 `b/report.md` 가 같은 보관 경로로 간다. `Edit` 마다 다시 등록하면서 바이트가 한도에 다시
더해진다. `getMetadata` 가 실패하면 `fallbackSize=0` 이라 한도 검사를 건너뛴다. 바깥 `catch (Exception)` 은 note 를 달지 않는다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/tools/artifact/ArtifactArchive.java` 의 `register`.

**언제 다시 볼까.** artifact 가 덮어써졌거나 한도에 일찍 걸린다는 보고가 있을 때.

출처: 빌드 리뷰 3.

## EE-20 — 실행 환경 키가 없는 컨텍스트에서 `Skill` 이 성공한다 · **열림**

**무엇을.** `EXECUTION_ENVIRONMENT` 가 없는 `ToolContext` 에서 `Skill` 이 오류를 돌려주게 한다.

**왜.** 지금은 경고를 남기고 `${AIMON_SKILL_DIR}` 를 비운 채 성공한다. 설계 §3 의 "호스트 폴백 없음" 결정대로라면 오류다. 키가
없는 컨텍스트는 손으로 만든 것뿐이라(모든 실행 경로가 키를 넣는다) 실제 영향은 테스트와 임베더에 한정된다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/tools/SkillRenderContextAccess.java`.

**언제 다시 볼까.** 손으로 만든 컨텍스트로 `Skill` 을 부르는 임베더가 생길 때.

출처: 빌드 리뷰 3.

## EE-21 — 제공자 팩토리로 만든 제공자는 소유자가 없다 · **열림**

**무엇을.** `OrcaAgentRuntimeFactory.withExecutionEnvironmentProviderFactory` 로 만든 제공자를 누가 닫는지 정하고 Javadoc 을
고친다.

**왜.** 그렇게 만든 제공자는 아무도 닫지 않고, `doCreate` 가 제공자를 만든 뒤 실패하면 새어 나간다. Javadoc 은 "Bootstrap uses
this" 라고 하지만 부트스트랩은 `withExecutionEnvironmentProvider` 를 쓴다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntimeFactory.java` 의
`withExecutionEnvironmentProviderFactory` 와 `doCreate`.

**언제 다시 볼까.** 이 팩토리 경로를 쓰는 조립이 처음 생길 때.

출처: 빌드 리뷰 3.

## EE-22 — `AimonStack.fileSystem(id)` 가 제어 저장소를 돌려줄 수 있다 · **열림**

**무엇을.** 로컬이 아닌 팩토리나 공유 제공자(Spring 빈) 배치에서도 `fileSystem(id)` 가 워크스페이스를 돌려주게 하거나,
Javadoc 을 실제 동작에 맞춘다.

**왜.** 그 배치에서는 제공자의 파일 시스템을 알 수 없어 제어 저장소를 돌려준다. Javadoc 은 워크스페이스라고 적는다.

**어디.** `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/assemble/StackAgentRuntimeProvisioner.java`(2026-09-29 기준
295–297행).

**언제 다시 볼까.** 스타터 사용자가 `fileSystem(id)` 로 워크스페이스를 읽으려 할 때.

출처: 빌드 리뷰 3.

## EE-23 — 프로비저닝이 중간에 실패하면 자원이 샌다 · **열림**

**무엇을.** `createStores` 이후 `createRuntime` 이 던지면 이미 만든 제어 파일 시스템과 제공자(파일 시스템·셸)를 닫는다.

**왜.** 지금은 teardown 싱크에 등록되기 전에 실패하므로 아무도 닫지 않는다.

**어디.** `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/assemble/StackAgentRuntimeProvisioner.java`(2026-09-29 기준
350행).

**언제 다시 볼까.** 런타임 생성 실패가 반복되는 배포에서 파일 핸들이나 셸 프로세스가 쌓일 때.

출처: 빌드 리뷰 3.

## EE-24 — 사용 불가 환경의 사용자 컨텍스트가 호스트 디렉터리를 보여 줄 수 있다 · **열림**

**무엇을.** 서술자의 작업 디렉터리가 비었을 때 스냅숏 값으로 떨어지지 않게 한다.

**왜.** 사용 불가 환경의 서술자는 작업 디렉터리가 비어 있고, `UserContextMessageBuilder` 는 그때 스냅숏의 값을 쓴다. 그 값은
호스트 경로일 수 있어 설계 §5.1 과 어긋난다. EE-10(스냅숏에서 작업 디렉터리를 빼기)을 하면 함께 사라진다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/prompt/UserContextMessageBuilder.java`(호출은
`OrcaAgentExecutor`).

**언제 다시 볼까.** EE-10 을 다룰 때.

출처: 빌드 리뷰 3.

## EE-25 — 워크플로 격리 오류가 실제 원인을 가린다 · **닫힘** *(2026-09-29)*

**무엇을.** 부모 환경이 사용 불가일 때 그 원인을 오류에 싣는다.

**왜.** 지금은 "does not support isolation" 으로 보고되어, 환경이 왜 사용 불가인지(제공자 없음, 샌드박스 다운)가 사라진다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/workflow/impl/DefaultWorkflowContext.java` 의 `resolveEnv`.

**언제 다시 볼까.** 격리 워크플로가 실패했다는 보고에서 원인을 찾기 어려울 때.

출처: 빌드 리뷰 3.

### 닫힘 (2026-09-29)

`ExecutionEnvironment.isolate` 의 계약에 두 대답을 나눴다 — **빈 값**은 "이런 환경에는 격리가 없다", **던짐**은 "여기서
격리를 거절한다, 메시지가 그 이유다". `UnavailableExecutionEnvironment.isolate` 는 파일 시스템·셸·`stage()` 처럼 자기
`ExecutionEnvironmentUnavailableException`(원인을 담은)을 던진다. `resolveEnv` 는 원래 던짐을 잡아 메시지를 싣고
있었으므로, 바꾼 것은 그 예외를 `WorkflowException` 의 원인으로 잇는 것뿐이다. 이제 오류는 "could not isolate branch
'…': Execution environment unavailable: no ExecutionEnvironmentProvider is configured — refusing to run unscoped (C30)"
처럼 읽힌다. 테스트는 `UnavailableExecutionEnvironmentTest.isolateThrowsWithTheCause`,
`WorkflowPhase4Test.isolateWithoutEnvironmentIsRunFatal`·`isolateOnFailingProviderReportsTheReason`.

**어디가 달랐다.** 항목은 `resolveEnv` 를 가리켰지만 원인이 사라지는 자리는 환경 쪽이었다 — 사용 불가 환경이 원인을
들고 있으면서 `isolate()` 에만 빈 값을 돌려줬다. `resolveEnv` 에서 `instanceof UnavailableExecutionEnvironment` 를 보는
처방은 ArchUnit 의 `workflow` 허용 목록을 넓혀야 했고, `isolate()` 를 부르는 다른 코드는 여전히 빈 값을 받았을 것이다.

## EE-26 — 워크스페이스 안 스킬의 스테이징 경로를 격리 브랜치가 쓸 수 없다 · **열림**

**무엇을.** 소스가 워크스페이스 자체일 때의 지름길이 격리 브랜치에서도 유효한 경로를 돌려주게 하거나, 브랜치에서는
지름길을 쓰지 않고 `.aimon-staged/` 로 복사한다.

**왜.** 지름길은 `absolute(sourceDir)` 를 그대로 돌려주고 `LocalIsolatedEnvironment.stage` 는 그 부모 경로를 통과시킨다.
브랜치 안에서는 `ScopedVirtualFileSystem` 이 그 경로에 `.worktrees/<k>/…` 접두어를 다시 붙이는데, 그 자리에는 아무것도 없다.
브랜치와 공유되는 것은 `.aimon-staged/` 뿐이다. 워크스페이스 파일 시스템 위의 VFS 스킬 저장소에만 해당하고 CLI 의
`.aimon/skills` 는 해당하지 않는다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/environment/impl/LocalStaging.java` 의 `stage` 첫 분기(66행,
2026-09-29), `LocalIsolatedEnvironment.stage`.

**언제 다시 볼까.** 워크스페이스에 둔 VFS 스킬 저장소를 격리 워크플로 단계에서 쓸 때.

출처: 빌드 리뷰 4.

## EE-27 — 브랜치의 공유 접두어 검사는 대소문자를 구분한다 · **닫힘** *(2026-09-29)*

**무엇을.** `ScopedVirtualFileSystem` 의 공유 접두어 검사를 경로 규칙과 같은 `VfsPaths.isUnderIgnoreCase` 로 맞춘다.

**왜.** 경로 규칙은 대소문자를 무시하는데 공유 접두어 검사는 구분한다. 브랜치에서 `.AIMON-STAGED/x` 에 쓰면 브랜치 안에서는
허용되고 승격 때 거절되어 병합이 중단된다. 우회되는 것은 없다. EE-8 과 같은 모양이다. PR #195 리뷰 1 이 두 가지를 더
확인했다. 그런 쓰기는 부모의 스테이징 영역이 아니라 브랜치 안의 `.worktrees/<k>/.AIMON-STAGED/x` 에 놓인다(무해하지만
일관되지 않다). 그리고 대소문자를 구분하지 않는 파일 시스템에서는 브랜치 키 `a` 와 `A` 가 한 디렉터리를 나눠 쓴다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/filesystem/impl/ScopedVirtualFileSystem.java` 의 `sharedPrefixes`
검사.

**언제 다시 볼까.** EE-8 을 다룰 때, 또는 대소문자를 구분하지 않는 파일 시스템에서 병합 중단 보고가 나올 때.

출처: 빌드 리뷰 4.

### 닫힘 (2026-09-29)

적힌 처방대로 `VfsPaths.isUnderIgnoreCase` 로 바꿨다. 브랜치의 `.AIMON-STAGED/x` 쓰기는 이제 부모의 스테이징 영역으로
가서 부모의 `READ_ONLY` 규칙(대소문자 무시)에 쓰는 시점에 걸린다 — 브랜치 안에 떨어지지 않고 병합까지 가지 않는다.
테스트는 `ScopedVirtualFileSystemTest.sharedPrefixMatchesIgnoringCase`,
`LocalIsolatedEnvironmentTest.stagingInAnotherCaseIsReadOnly`.

**고치지 않은 것: 브랜치 키 `a` 와 `A` 의 충돌.** 러너가 만드는 키는 구조 경로의 소문자 조각과 인덱스뿐이라 대소문자만
다른 두 키가 나오지 않는다 — 설계 리뷰가 소스로 확인했다. 충돌할 수 있는 것은 키를 직접 고르는 `isolate()` 호출자뿐이고,
막으려면 환경마다 발급한 키를 기억해야 한다(멀티 인스턴스라면 저장소까지). 가설적 호출자에게 그 값은 과하다고 보고
`LocalExecutionEnvironment.isolate` 의 javadoc 에 한계로 적었다. **새로 드러난 전제:** 이 라우팅이 안전한 것은 부모가
스테이징 영역을 `READ_ONLY` 로 지키기 때문이다. 그 규칙을 뺀 어셈블리에서는 어떤 표기로든 브랜치가 루트 스테이징 영역에
그대로 쓴다(정확한 표기로는 이전부터 그랬다). 설계 §9.2 에 적었다.

**빌드 리뷰에서 보탬.** 대소문자를 접은 뒤로 셸이 만든 브랜치 안의 스테이징 디렉터리가 새 틈이 됐다. 브랜치 `k` 의 셸이
`.aimon-staged/x`(또는 `.Aimon-Staged/x`)를 만들면 그 자리의 브랜치 규칙은 `DENY` 가 아니라 `READ_ONLY` 라 목록에는
나오는데, 파일 도구의 모든 표기는 루트 스테이징 영역으로 간다 — 병합이 루트의 파일을 읽거나 없는 것을 찾다 멈추고, 최악에는
루트 `READ_ONLY` 영역에 쓰다 반쯤 된 채 끝난다. 같은 변경의 후속 커밋에서 스코프가 브랜치 접두어 아래에서 공유 접두어에
걸리는 항목을 목록·검색에서 빼도록 고쳤다(설계 §8.4 DV-6, `LocalIsolatedEnvironmentTest.shellWrittenStagingCopyIsNeverPromoted`).

## EE-28 — `WorktreeMerge.promote` 가 브랜치의 소속을 확인하지 않는다 · **닫힘** *(2026-09-29)*

**무엇을.** `promote(parent, branches, policy)` 에 동일성·소유 검사를 둔다 — `parent` 자신이나 다른 부모의 브랜치가
`branches` 에 섞이면 거부한다.

**왜.** 브랜치 키 검사가 빠지고 경계 검사도 없어서, 그런 입력이 오면 파일마다 자기 자신 위로 복사한 뒤 브랜치 쪽 사본을
지운다. 브랜치 안에 셸로 만든 심볼릭 링크가 있으면 `getMetadata` 가 던져 병합이 반쯤 된 채 남는데, 이것은 이 변경 이전부터다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/environment/WorktreeMerge.java` 의 `promote`(76행,
2026-09-29).

**언제 다시 볼까.** `promote` 를 워크플로 엔진 밖에서 부르는 코드가 생길 때, 또는 병합이 반쯤 된 채 남았다는 보고가 나올 때.

출처: 빌드 리뷰 4.

### 닫힘 (2026-09-29)

`promote` 가 파일을 읽거나 쓰기 전에 브랜치를 검사해 `IllegalArgumentException` 으로 거부한다 — 부모 자신, 부모의 파일 시스템을
공유하는 환경(자기 위로 복사한 뒤 지우는 데이터 손실의 직접 원인), 두 번 넘긴 같은 브랜치, 새 SPI 메서드
`ExecutionEnvironment.isolatedFrom()` 이 다른 부모를 가리키는 브랜치. 비교는 동일성이다. 계보를 밝히지 않는 브랜치
(외부 제공자)는 나머지 검사만으로 받는다 — 그 느슨함은 EE-47 로 옮겼다. 심볼릭 링크의 반쯤 된 병합도 닫았다 — 올릴
파일의 메타데이터를 먼저 모두 읽어 보고, 하나라도 실패하면 아무것도 올리지 않는다. 테스트는 `WorktreeMergeTest` 의
소속 검사 다섯 건과 `unreadableBranchFileAbortsBeforePromoting`.

**확인한 것.** 항목의 "`getMetadata` 가 던진다" 는 참이다. 실제 심볼릭 링크로 돌려 봤다 — 로컬 `listRecursive` 는 파일을
가리키는 링크를 목록에 넣고(`Files.isRegularFile` 이 링크를 따라간다), `getMetadata` 와 `openInputStream` 은 둘 다
`PathValidator` 의 링크 검사에서 거절한다. `getMetadata` 가 그중 먼저 불리므로 사전 점검은 그것으로 충분하다. 자기 위로
복사한 뒤 지우는 손실은 코드를 읽어 확인했고 돌려 보지는 않았다 — 새 테스트는 거부만 확인한다.

**빌드 리뷰에서 보탬.** 닫을 때 적은 "입출력 전에" 는 로컬 제공자에서만 참이다. 소속 검사도 각 환경의 `fileSystem()` 을
부르는데, 다른 제공자는 그 첫 호출에 프로비저닝할 수 있다(설계 §13). 그래서 문구를 "파일을 읽거나 쓰기 전에" 로 고쳤다.
그리고 메타데이터 사전 점검은 원본을 읽을 수 있는지만 봤지 올릴 곳에 쓸 수 있는지는 보지 않았다 — 셸이 브랜치의
`READ_ONLY` 디렉터리(사용자 규칙의 `vendor/` 같은 것)에 쓴 파일은 여전히 병합을 반쯤 된 채 남겼다. 이제 모든 목적지를
부모의 경로 규칙과 먼저 대조한다. 규칙은 새 중립 접근자 `VirtualFileSystems.pathRules` 로 읽으며(`WorktreeMerge` 는
`filesystem.impl` 을 import 할 수 없다), 가장 바깥 층이 `withPathRules` 로 만든 파일 시스템일 때만 보인다 — 로컬 부모는
그렇고, 다른 것은 예전처럼 쓸 때 거절된다. 테스트는 `WorktreeMergeTest.readOnlyDestinationAbortsBeforePromoting`
(설계 §8.4 DV-8·DV-9).

## EE-29 — 격리 브랜치 안에서 다시 격리할 수 없다 · **닫힘** *(2026-09-29)*

**무엇을.** `LocalIsolatedEnvironment` 가 `isolate()` 를 재정의해 중첩 격리를 지원하거나, 지원하지 않는다는 것을 원인과 함께
보고한다.

**왜.** 재정의가 없어서, 격리 브랜치 안에서 시작한 워크플로에 격리 단계가 있으면 그 실행이 실패한다. EE-25(격리 오류가 실제
원인을 가린다)와 이웃한다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/environment/impl/LocalIsolatedEnvironment.java`.

**언제 다시 볼까.** 중첩 워크플로에서 격리 단계를 쓰려 할 때.

출처: 빌드 리뷰 4.

### 닫힘 (2026-09-29)

두 번째 길 — 지원하지 않는다는 것을 원인과 함께 보고한다. `LocalIsolatedEnvironment.isolate` 가 "nested isolation is
not supported: this environment is already the isolated workflow branch 'k' (.worktrees/k); …" 를 담은
`UnsupportedOperationException` 을 던지고, EE-25 의 경로로 러너의 run-fatal 오류에 실린다. 빈 값을 돌려주지 않은 것은
그러면 EE-25 의 증상("does not support isolation")이 그대로 재현되기 때문이다. 테스트는
`LocalIsolatedEnvironmentTest.nestedIsolationIsRefused`, `WorkflowPhase4Test.nestedIsolationIsRunFatalWithTheReason`.

**지원하지 않은 근거**(설계 §1.4). 스코프 위의 스코프는 작업 디렉터리가 `"."` 이라 절대 경로를 잃는다. 바깥 브랜치를
병합하면 안쪽 브랜치의 미병합 디렉터리가 루트의 `.worktrees/` 로, 곧 **다른 브랜치의 디렉터리로** 올라간다. EE-28 의
소속 검사는 계보 사슬이 필요해진다. 샌드박스의 git worktree 안 worktree 는 코어가 시험할 수 없는 약속이다.
**착수해 보니 더 큰 것:** 둘째 위험은 중첩과 무관하게 **지금도** 있다 — 브랜치가 `.worktrees/other/x` 에 쓰면 그것이
병합에서 다른 브랜치의 디렉터리로 올라간다. EE-46 으로 옮겼다.

## EE-30 — 백그라운드 워크플로가 호출자의 실행 환경을 잃는다 · **열림**

**무엇을.** `Workflow` 와 `GraalJsWorkflow` 의 백그라운드 경로가 쓰는 에이전트 범위 러너에 호출한 실행의 부모 환경을 넘긴다.

**왜.** 그 러너는 부모 환경 없이 만들어진다. 그래서 격리 브랜치 안에서 시작한 백그라운드 워크플로는 기본 워크스페이스를
기준으로 환경을 해석한다. 이 변경 이전부터 있던 모양이지만 설계 §5.2 의 전달 규칙이 이 생성 경로를 덮지 않는다. 리뷰어가
하위 에이전트 보고를 옮긴 것이고 전부를 직접 다시 확인하지는 않았다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntimeFactory.java` 의 에이전트 범위
`WorkflowRunners.create` 호출(1088~1092행, 2026-09-29).

**언제 다시 볼까.** 격리 브랜치 안에서 백그라운드 워크플로를 돌릴 때, 또는 샌드박스 제공자를 붙일 때. 부모 환경 없이
해석하는 그 요청에는 에이전트 런타임 id 와 주체만 실리고 세션·실행 id·에이전트가 없다. 워크스페이스 샌드박스의 바인딩
정책은 그런 요청으로 워크스페이스를 정할 수 없으므로 사용 불가 환경을 돌려주고, 그 러너의 격리 단계는 C30 으로 실패한다.

출처: 빌드 리뷰 4.

## EE-31 — 슬래시 커맨드 인라인 스킬에는 read stamp 가 없다 · **열림**

**무엇을.** 스킬 기반 슬래시 커맨드가 만드는 `ToolContext` 에 `FILE_STAMPS_KEY` 를 싣는다.

**왜.** 그 컨텍스트에는 stamp 가 없어서 인라인 스킬의 `Edit` 가 언제나 "읽지 않았다" 로 실패한다. 회귀는 아니고 EE-11(스케줄
루틴에는 read stamp 가 없다)과 같은 종류의 빈틈이다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java` 의 커맨드 컨텍스트
조립(2113행 부근, 2026-09-29).

**언제 다시 볼까.** EE-11 을 다룰 때, 또는 슬래시 커맨드 스킬이 파일을 고쳐야 할 때.

출처: 빌드 리뷰 4.

## EE-32 — `ToolContextKey` 의 한 번만 쓰는 이름 집합이 클래스 초기화에 기댄다 · **열림**

**무엇을.** 한 번만 쓰는 키 이름을 `ToolContextKeys` 의 클래스 초기화와 무관하게 등록한다(예: 이름 집합을 상수로 고정).

**왜.** 이름 집합은 `ToolContextKeys` 가 초기화될 때 채워진다. 그 전에 문자열 키로 넣은 값은 검사되지 않는다. 운영 경로는
모두 상수를 먼저 건드리므로 위험은 이론적이다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/tool/ToolContextKey.java` 의 `WRITE_ONCE_NAMES`(42행,
2026-09-29).

**언제 다시 볼까.** 문자열 키로 `ToolContext` 를 채우는 새 경로가 생길 때.

출처: 빌드 리뷰 4.

## EE-33 — 경로 규칙이 Windows 의 이름 별칭을 모른다 · **열림**

**무엇을.** Windows 호스트에서 규칙이 맞을 때 NTFS 의 끝 점·공백 별칭(`.aimon.`)과 8.3 짧은 이름 같은 세그먼트를 거부한다.

**왜.** 경로 규칙은 이름을 접어서(대소문자·유니코드) 비교하지만, 그런 별칭은 정규화하지 않는다. Windows 에서는 같은
디렉터리를 다른 이름으로 부를 수 있다.

처음 등록할 때는 대소문자 접기가 ASCII 뿐이라는 것도 이 항목에 들어 있었고, 별칭은 Windows 에만 있다고 적었다. 틀렸다.
PR #195 리뷰 1 이 **macOS 에도 별칭이 있음**을 재현했다 — APFS 는 U+017F `ſ` 를 `s` 로 접으므로, 기본 제공자에서
`.aimon-ſtaged/…` 쓰기가 받아들여져 스테이징된 스크립트가 바뀌었고, `PathRule.deny(".secrets")` 는 `.ſecrets/key` 로
읽혔다. 그 절반은 이 변경에서 고쳤다: `VfsPaths.isUnderIgnoreCase` 가 NFKC → 소문자·대문자·소문자 → NFC 로 접은 이름을
비교한다(`VfsPaths.foldCase`, 구현 문서 §10.2). 첫 수정은 대문자·소문자만 거쳐서 U+1E9E `ẞ` 를 `ß` 에서 멈췄고, APFS 에서
`.ẞh/id` 가 `deny(".ssh")` 를 우회했다(PR 리뷰 2). 앞에 소문자화를 한 번 더 두어 `ss` 까지 접히게 고쳤다. 리뷰가 APFS 가
한두 글자 ASCII 이름과 같게 보는 BMP 코드 포인트를 전수 대조해 어긋난 것은 `ẞ` 하나였다 — 짧은 이름에 대해 확인한 것이지
유니코드 전체에 대해 증명한 것은 아니다. 남은 것은 위의 Windows 전용 별칭이다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/filesystem/VfsPaths.java`.

**언제 다시 볼까.** Windows 호스트 배포를 지원 대상으로 삼을 때.

출처: 빌드 리뷰 4.

## EE-34 — 경로 없는 `getUsageSummary()` 가 제어 저장소 크기를 드러낸다 · **열림**

**무엇을.** `PathRuleVirtualFileSystem.getUsageSummary()` 가 규칙이 가린 경로의 사용량을 빼거나, 가린 쪽을 합치지 않는다.

**왜.** 인자 없는 호출은 가드 없이 위임되어 제어 저장소의 크기까지 합친다. 새는 것은 크기뿐이다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/filesystem/impl/PathRuleVirtualFileSystem.java` 의
`getUsageSummary()`(207행, 2026-09-29).

**언제 다시 볼까.** 사용량을 모델이나 외부 사용자에게 보여 주는 도구가 생길 때.

출처: 빌드 리뷰 4.

## EE-35 — 링크 허용 루트를 설정 파일로 정할 수 없다 · **열림**

**무엇을.** `PathSkillRepository` 의 허용 링크 루트(`allowedLinkRoot(s)`)를 에이전트 번들 로더, CLI, 부트스트랩, 스타터
설정으로 노출한다.

**왜.** 허용 루트는 빌더로만 정할 수 있다. `FileSystemAgentBundleLoader` 는 `new PathSkillRepository(skillsPath)` 로 저장소를
만들고, CLI·부트스트랩·스타터에는 그 값을 넘길 설정이 없다. 그래서 디스크에서 읽는 에이전트는 공유 헬퍼를 링크한 스킬
(`skills/foo -> /opt/shared/foo`)을 허용할 수 없다 — 그 스킬은 적재되지 않는다(이제 그 스킬만 빠진다). 사용자 결정의 "허용
루트는 설정할 수 있게 한다" 는 프로그램으로 조립할 때만 채워졌다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/FileSystemAgentBundleLoader.java` 171행(2026-09-29),
`modules/aimon-core/src/main/java/at/aimon/core/skill/repository/PathSkillRepository.java` 의 `Builder`.

**언제 다시 볼까.** 링크로 설치한 스킬이 적재되지 않는다는 보고가 나올 때, 또는 스타터에 스킬 설정 절이 생길 때.

출처: PR #195 리뷰 1.

## EE-36 — `ReadOnlyLocalFileSystem` 은 읽기와 전체 목록만 실제 경로를 검사한다 · **열림**

**무엇을.** `exists`, `isDirectory`, `getMetadata`, `list` 에도 링크 규칙의 실제 경로 검사를 적용하고, `read` 는 검사한 실제
경로로 파일을 연다.

**왜.** 네 메서드는 검사 없이 링크를 따라가므로 루트 밖 파일의 존재와 크기가 드러난다. `read` 는 실제 경로를 검사한 뒤 링크
경로로 파일을 열어, 검사와 사용 사이에 링크를 바꿀 틈이 있다. 스킬 디렉터리는 운영자가 정하는 것이라 위험은 낮다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/filesystem/impl/local/ReadOnlyLocalFileSystem.java` 의 `read`(133행),
`exists`(152행), `isDirectory`(157행), `getMetadata`(162행), `list`(180행) (2026-09-29).

**언제 다시 볼까.** 스킬 디렉터리를 운영자가 아닌 쪽에서 받게 될 때(EE-16 의 재검토 조건과 같다).

출처: PR #195 리뷰 1.

## EE-37 — 스테이징 마커는 있는지만 본다 · **열림**

**무엇을.** `.staged` 마커에 이미 쓰고 있는 `contentKey` 를 읽어, 경로의 키와 같을 때만 "이미 스테이징됨" 으로 본다.

**왜.** 설계 §4.4·§15 대로 마커가 생략을 정하지만, 지금은 마커가 있는지만 검사한다. 키는 결정적이고 예측할 수 있으므로,
미리 심어 둔 `.aimon-staged/<name>/<key>/` 와 `.staged` 는 그대로 제공된다. 그런 사본은 복제한 저장소에서 올 수 있다(EE-4).
마커 내용까지 비교해도 비용은 작다. 다만 셸이 사본을 고칠 수 있다는 전제(§2 비목표)는 그대로라 경계가 되지는 않는다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/environment/impl/LocalStaging.java` 의 마커 검사(95·104행,
2026-09-29).

**언제 다시 볼까.** EE-4 를 다룰 때, 또는 스테이징 영역을 저장소에 커밋한 사례가 나올 때.

출처: PR #195 리뷰 1.

## EE-38 — 대소문자를 구분하는 소스를 구분하지 않는 디스크에 스테이징하면 파일이 합쳐진다 · **열림**

**무엇을.** 스테이징 전에 접은 이름이 겹치는 파일 쌍을 찾아 거부하거나, 복사한 결과를 디스크에서 다시 해시한다.

**왜.** 클래스패스·S3·GridFS 소스에 `RUN.sh` 와 `run.sh` 가 함께 있으면 APFS·NTFS 에서는 한 파일로 합쳐진다. 복사 검증은
소스에서 읽은 바이트를 해시하므로 디스크에 실제로 놓인 것과 달라도 통과한다. 드문 경우다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/environment/impl/LocalStaging.java` 의 `copy`(130행 부근,
2026-09-29).

**언제 다시 볼까.** 스테이징된 스킬 파일이 소스와 다르다는 보고가 나올 때.

출처: PR #195 리뷰 1.

## EE-39 — 경로 규칙 파일 시스템의 `search` 가 결과를 덜 돌려줄 수 있다 · **열림**

**무엇을.** `PathRuleVirtualFileSystem.search` 가 위임에 더 많이 요청하거나(over-fetch), 가려진 항목을 걷는 동안 걸러
`maxResults` 를 채운다.

**왜.** 지금은 `maxResults` 를 그대로 위임에 넘긴 뒤 가려진 결과를 뺀다. 그래서 보이는 결과가 더 있어도 그보다 적게 돌아올 수
있다. 가려진 것이 새지는 않는다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/filesystem/impl/PathRuleVirtualFileSystem.java` 의 `search`(201행,
2026-09-29).

**언제 다시 볼까.** `search` 를 결과 개수에 기대는 도구가 쓰게 될 때.

출처: PR #195 리뷰 1.

## EE-40 — 포크의 환경 요청에 포크 자신의 정의가 없고, 에이전트 정의에 임의 속성이 없다 · **닫힘** *(2026-09-29)*

**무엇을.** 두 가지다. (1) `DefaultSubagentExecutor` 가 만드는 `EnvironmentRequest` 에 포크 자신의 정의(서브에이전트 이름과
메타데이터)를 싣는다. (2) `AgentMetadata`·`SubagentMetadata` 에 제공자가 읽을 수 있는 임의 속성 맵(`attributes`,
`Map<String, String>`)을 두고, 에이전트·서브에이전트 정의 파일의 front matter 에서 채운다.

**왜.** 설계 §5.2 는 포크가 어느 샌드박스(슬롯)에서 돌지를 제공자의 바인딩 정책이 정하게 한다. 워크스페이스 샌드박스
설계의 기본 정책은 그 값을 정의의 `sandbox.slot`·`sandbox.profile` 에서 읽는다. 지금은 둘 다 불가능하다 — 포크의 요청에는
`agent` 가 비어 있고(`agentRuntimeId`·`executionId`·`invokingSessionId`·`principal`·`parent` 만 싣는다), 메인 턴의 요청에
실리는 `Agent` 도 메타데이터에 임의 키가 없다(`tags` 는 `Set<String>` 이고 `SubagentMetadata` 에는 그것도 없다). 슬롯이
하나뿐인 동안(워크스페이스 샌드박스 구현 순서 2·3단계)은 필요 없다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/subagent/execution/DefaultSubagentExecutor.java` 의
`resolveExecutionEnvironment`(656~670행, 2026-09-29), `EnvironmentRequest` 에 서브에이전트를 실을 자리,
`agent/AgentMetadata.java`, `subagent/SubagentMetadata.java` 와 두 파서.

**언제 다시 볼까.** 워크스페이스 샌드박스 구현 순서 4단계(멀티 슬롯)를 시작할 때. 그 단계의 선행 조건이다.

출처: 워크스페이스 샌드박스 설계 리뷰(2026-09-29).

### 닫힘 (2026-09-29)

(1) `EnvironmentRequest.fork()` 가 `ForkDefinition`(서브에이전트 이름과 속성)을 싣고, `DefaultSubagentExecutor` 가 포크마다
채운다. 제공자가 읽을 값은 `EnvironmentRequest.definitionAttributes()` 한 곳이다(포크면 포크의 것, 아니면 에이전트의 것). `Subagent` 를 그대로 싣지 않은 것은 `subagent` 패키지가 이미 `environment` 를 의존하기 때문이다 — 반대 방향을 더하면
패키지 순환이 생겨 ArchUnit 의 순환 검사가 막는다. (2) `AgentMetadata`·`SubagentMetadata`(그리고 `AgentDefinition`)에
`getAttributes()`(`Map<String, String>`)를 두었다. 두 정의 파일 모두 front matter 의 `attributes:` 블록에서 채우고, 읽는 규칙은
`at.aimon.core.base.DefinitionAttributes` 한 곳이다 — 중첩 맵은 점 표기 키로 펼친다(`sandbox: {slot: build}` 와
`sandbox.slot: build` 는 같은 속성). 스칼라는 문자열이 되고, 리스트·빈 값·빈 중첩 맵·빈 키, 같은 키를 중첩과 점 표기로
두 번 쓴 것, 값이면서 그룹인 키(`sandbox: x` 와 `sandbox.slot: y`)는 키를 밝힌 파싱 오류다. 같은 수준에서 같은 키를 두 번
쓴 것은 YAML 기본값대로 뒤의 것이 이긴다. 따옴표 없는 값은 YAML 1.1 이 먼저 타입을 입힌다(`010` → `8`) — 평범한 텍스트가
아니면 따옴표로 감싼다. 속성은 `AgentDefinitionVersion` 의 해시에도 들어가(있을 때만 — 없는 정의의 해시는 그대로다)
스케줄된 루틴의 슬롯이 바뀌면 정의 변경으로 보고된다. 코어는 이 값을 싣기만 하고
읽지 않는다 — 키 이름(`sandbox.*`)은 제공자가 정한다. 테스트는 `DefinitionAttributesTest`, 두 파서 테스트,
`DefaultSubagentExecutorTest.environmentRequestCarriesForkDefinition`.

## EE-41 — 경로 규칙 파일 시스템을 코어 밖에서 만들 수 없다 · **닫힘** *(2026-09-29)*

**무엇을.** `VirtualFileSystems` 에 `withPathRules(VirtualFileSystem, List<PathRule>)` 같은 공개 팩토리를 둔다.

**왜.** 설계 §4.4·§13 은 스테이징 영역을 파일 도구에 읽기 전용으로 두라고 **모든 제공자**에게 요구한다. 로컬 제공자는
`PathRuleVirtualFileSystem` 으로 그렇게 하지만, 그 클래스는 `filesystem.impl` 에 있어 외부 제공자(aimon-sandbox)가 쓸
공개 경로가 없다. `PathRule` 은 공개인데 규칙을 적용할 수단이 없는 셈이다. 외부 제공자가 검사를 따로 구현하면 경로
정규화와 대소문자·유니코드 접기(구현 문서 §10.2)가 두 벌이 된다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/filesystem/VirtualFileSystems.java`,
`filesystem/impl/PathRuleVirtualFileSystem.java`.

**언제 다시 볼까.** 워크스페이스 샌드박스 구현 순서 2단계(`SandboxFileSystem`)를 시작할 때. 그 단계의 선행 조건이다.

출처: 워크스페이스 샌드박스 설계 리뷰(2026-09-29).

### 닫힘 (2026-09-29)

`VirtualFileSystems.withPathRules(VirtualFileSystem, List<PathRule>)` 를 더했다. `PathRuleVirtualFileSystem` 을 감싸 돌려줄 뿐이라
경로 정규화와 대소문자·유니코드 접기는 로컬 제공자와 한 벌이다. 로컬 제공자도 이 팩토리를 거치도록 바꿔, 규칙을 적용하는
경로가 코어 안팎에서 하나가 되었다. 결과는 위임 대상을 빌린다(`close()` 가 위임 대상을 닫지 않는다). 테스트는
`VirtualFileSystemsTest`.

## EE-42 — 워크플로 스크립트의 인라인 서브에이전트가 속성을 싣지 못한다 · **닫힘** *(2026-09-29)*

**무엇을.** GraalJS 워크플로의 `agent({...})` 단계와 `WorkflowTool` 의 내장 단계가 만드는 서브에이전트에 속성을 실을 길을
둔다. `agentType` 으로 등록된 서브에이전트를 가리키면 그 정의의 속성을 복사하고, 스크립트가 속성을 직접 줄 수도 있게 한다.

**왜.** EE-40 으로 포크의 요청에 서브에이전트의 속성이 실리지만, 워크플로 단계는 서브에이전트를 등록된 정의에서 가져오지
않고 이름(`graaljs:<agentType>`)부터 새로 만든다. 그래서 속성이 늘 비고, 슬롯을 속성으로 고르는 제공자는 워크플로 단계를
조용히 기본 슬롯에 둔다. `SubagentResolver.resolve` 는 위치 인자를 받는 공개 인터페이스라, 인자를 하나 더하면 구현체가
깨진다 — 작은 서술자 객체를 받도록 바꾸는 편이 낫다.

**어디.** `modules/aimon-workflow-graaljs/src/main/java/at/aimon/workflow/graaljs/InlineSubagentResolver.java`(37~50행,
2026-09-29), `SubagentResolver.java`, `modules/aimon-core/src/main/java/at/aimon/core/tools/workflow/WorkflowTool.java`
(367~395행).

**언제 다시 볼까.** 워크스페이스 샌드박스 구현 순서 4단계(멀티 슬롯)에서 워크플로 단계를 슬롯에 나눠 둬야 할 때.

출처: PR #196 리뷰(2026-09-29).

### 닫힘 (2026-09-29)

`SubagentResolver.resolve` 는 이제 `SubagentDescriptor`(불변 클래스 + 빌더) 하나를 받는다. 기본 해석기
`SubagentResolver.inline(SubagentRegistry)` 는 `agentType` 과 같은 이름으로 등록된 서브에이전트의 속성을 복사하고,
스크립트가 `agent({..., attributes})` 로 준 속성을 더한다(`DefinitionAttributes.overlay`). 등록된 정의가 정한 키는
**고정된다** — 스크립트가 그 키에 다른 값을 주면 `agentType`·키·두 값을 적은 `JsScriptException` 으로 스크립트가 실패하고,
같은 값이면 아무 일도 없다. 스크립트가 더할 수 있는 것은 등록된 정의가 정하지 않은 키뿐이고, 등록된 키를 지울 수도 없다.
`overlay` 자체는 코어의 일반 규칙(같은 키는 덮는 쪽이 이긴다)으로 남고, 고정 검사는 graaljs 해석기가 `overlay` 를 부르기
전에 한다. 처음 구현은 스크립트가 같은 키를 이기게 했으나, 리뷰에서 모델이 쓴 스크립트가 운영자가 격리 슬롯으로 등록한
서브에이전트를 특권 슬롯으로 옮길 수 있다는 지적을 받아 고쳤다(EE-45 참고). 스크립트의 `attributes` 는 정의 파일의 블록과
같은 규칙으로 읽는다(중첩과 점 표기가 같은 속성, 스칼라는 글자, 리스트·값이 `null` 인 항목·값이면서 그룹인 키·유한하지 않은
수는 스크립트 실패). 등록된 정의에서 가져오는 것은 속성뿐이고
이름(`graaljs:<agentType>`)·프롬프트·도구는 그대로다. `GraalJsWorkflowTool` 은 자기 레지스트리로 이 해석기를 기본으로 쓴다.
내장 `Workflow` 도구의 단계는 역할마다 정해진 이름(`workflow-perspective` · `workflow-synthesizer` · `workflow-candidate` ·
`workflow-judge` · `workflow-skeptic`)으로 등록된 서브에이전트의 속성을, 실행마다 역할당 한 번 조회해 복사한다(팬아웃
도중 레지스트리가 바뀌어도 형제 단계가 서로 다른 슬롯에 가지 않는다). 조회가 실패하면 WARN 을 남기고 그 역할의 단계를
속성 없이(기본 배치로) 돌린다 — 스크립트를 실패시키는 graaljs 와 반대다. **운영자 주의:** 그 이름의 정의는
`Workflow` 에게는 속성만 주지만, 보통의 서브에이전트이기도 해서 모델이 목록에서 보고 `Task` 로 부를 수 있다 — 그때는
정의의 프롬프트가 쓰인다(EE-44). `EnvironmentRequest` 는 바뀌지 않았다. 테스트는 `DefinitionAttributesTest`,
`WorkflowToolAttributesTest`(실제 포크 실행기를 거쳐 `EnvironmentRequest.definitionAttributes()` 까지), `SubagentResolverTest`,
`SubagentDescriptorTest`, `MarshallingUnitTest`, `WorkflowBindingsFanoutTest`, `GraalJsWorkflowToolTest`,
`GraalJsEnvironmentRequestTest`(실제 실행 관리자로 `agent()`·`parallel()`·`pipeline()` 단계의 요청까지). 설계와 구현이 달라진 점은 설계
문서 §8 에 있다.

## EE-43 — 속성이 빈 포크를 제공자가 어디에 두는지 정해지지 않았다 · **열림**

**무엇을.** 속성이 빈 포크(속성을 적지 않은 서브에이전트, 역할 정의가 없는 `Workflow` 단계)에 대해 제공자가 "부모와 같은
샌드박스"(설계 §5.2 의 기본)를 따르는지 "전역 기본 슬롯"을 쓰는지를 워크스페이스 샌드박스 제공자 쪽에서 확정한다. 후자라면
워크플로 단계가 **호출한 실행의 속성을 물려받는** 경로(EE-42 설계의 기각안 C — 메인·포크 실행기가 한 번 쓰기 키로 자기
정의의 속성을 게시)를 다시 검토한다.

**왜.** EE-42 는 단계에 속성을 실을 길만 열었다. 역할 정의나 `attributes` 가 없는 단계는 여전히 빈 속성으로 요청되고, 그때의
배치는 제공자의 바인딩 정책이 정한다. 설계 §5.2 대로 부모를 따르면 문제가 없지만, 부모를 무시하는 정책이면 슬롯 X 의 포크가
부른 `Workflow` 의 단계가 조용히 기본 슬롯으로 간다.

**어디.** aimon-sandbox 의 바인딩 정책. 코어 쪽은 `DefaultSubagentExecutor.resolveExecutionEnvironment`,
`modules/aimon-core/src/main/java/at/aimon/core/tools/workflow/WorkflowTool.java` 의 `roleAttributes`(2026-09-29).

**언제 다시 볼까.** 워크스페이스 샌드박스 구현 순서 4단계(멀티 슬롯)의 바인딩 정책을 정할 때.

출처: EE-42 설계 §7 Q2.

## EE-44 — `Workflow` 역할 정의가 모델에게 `Task` 서브에이전트로도 보인다 · **열림**

**무엇을.** 내장 단계를 배치하려고 `workflow-judge` 같은 이름으로 정의한 서브에이전트를 모델의 서브에이전트 목록에서 숨길
수단을 둔다 — 레지스트리의 "숨김" 표시, 또는 역할 → 속성을 레지스트리가 아닌 도구 설정(맵)으로 받는 방식.

**왜.** 서브에이전트 레지스트리에는 숨김 표시가 없다. 역할 정의는 `Workflow` 에게 속성만 주려고 만든 것인데, 모델은 그것을
보통의 서브에이전트로 보고 `Task` 로 부를 수 있고, 그때는 아무도 쓸 생각이 없던 정의의 프롬프트로 돈다. 지금은 문서
(`WorkflowTool` Javadoc, 워크플로 CLI 가이드)로만 경고한다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/subagent/SubagentRegistry.java`, `SubagentMetadata`,
`modules/aimon-core/src/main/java/at/aimon/core/tools/workflow/WorkflowTool.java`(2026-09-29).

**언제 다시 볼까.** 운영자가 역할 정의를 실제로 두기 시작할 때, 또는 서브에이전트에 노출 범위 속성을 두는 작업이 생길 때.

출처: EE-42 설계 §7 Q5.

## EE-45 — `WorkflowJs` 스크립트의 `attributes` 로 모델이 배치를 고를 수 있다 · **열림**

**무엇을.** 모델이 쓴 GraalJS 스크립트가 `attributes` 를 아예 쓸 수 있어야 하는지 정한다. 절반은 이미 닫혔다 — 등록된
`agentType` 의 키는 고정되어 스크립트가 덮을 수 없다(EE-42 리뷰 반영). 남은 절반은 **등록되지 않은 `agentType`(또는
`agentType` 없는 단계)** 이다. 거기에는 고정할 키가 없어서 스크립트가 `sandbox.slot` 을 비롯해 아무 속성이나 적을 수 있다.
선택지는 (a) 그대로 둔다, (b) 등록된 `agentType` 에만 `attributes` 를 허용한다(새 키를 더하는 것까지 포함할지도 정한다),
(c) `GraalJsWorkflowTool` 빌더에 허용 키 목록(또는 끄는 스위치)을 둔다.

**왜.** EE-42 설계는 내장 `Workflow` 의 입력에 `attributes` 를 두지 않았다 — 코드가 어디서 돌지는 운영자 정책이지 모델이
고를 일이 아니라는 이유였다(기각안 D). 그런데 `WorkflowJs` 의 스크립트는 모델이 쓴다. 스크립트 `attributes` 는 백로그 항목이
요구한 기능이라 넣었지만, 모델이 `sandbox.slot` 을 적으면 같은 논리가 뚫린다. 운영자가 `untrusted-runner` 를
`sandbox.slot: isolated` 로 등록해도, 스크립트가 `agentType` 을 등록되지 않은 이름으로 바꾸거나 빼고 `privileged` 를 적으면
그 정의를 거치지 않고 특권 슬롯을 요청할 수 있다 — 고정은 등록된 정의를 **덮는** 길만 막는다. 구현은 도구 설명에
`attributes` 를 광고하지 않는 데서 멈췄다(EE-42 설계 §8). 제공자가 슬롯을 속성만 보고 고르지 않고 자기 정책으로 거르면
(예: 허용 목록) 이 틈은 제공자 쪽에서 닫힌다.

**어디.** `modules/aimon-workflow-graaljs/src/main/java/at/aimon/workflow/graaljs/AgentTaskMarshaller.java`,
`InlineSubagentResolver.java`, `GraalJsWorkflowTool.java`(2026-09-29).

**언제 다시 볼까.** 슬롯마다 권한이나 비용이 다른 제공자가 생길 때.

출처: EE-42 구현(설계 §8 의 차이 목록).

## EE-46 — 브랜치 안에서 `.worktrees/` 를 가리키는 경로는 병합에서 브랜치 디렉터리로 승격된다 · **열림**

**무엇을.** 브랜치 안의 `.worktrees/` 를 브랜치 목록에서 빼거나 쓰는 시점에 거절한다.

**왜.** 브랜치 `k` 가 `.worktrees/other/x` 에 쓰면(상대 경로로든 `{ws}/.worktrees/other/x` 로든) `.worktrees/k/.worktrees/other/x`
에 놓이고, 병합은 그것을 루트의 `.worktrees/other/x` — 브랜치 `other` 의 디렉터리 — 로 올린다. 부모의 규칙은 `.worktrees/`
를 지키지 않고, 브랜치 규칙(EE-8)은 부모의 것을 옮긴 것이라 역시 지키지 않는다. 대상이 **브랜치 자신의 디렉터리**일 수도
있다 — 상대 경로로 쓴 `.worktrees/k/…` 는 모든 디스크에서, `.worktrees/K/…` 는 대소문자를 구분하지 않는 디스크에서 병합이
루트의 `.worktrees/k/…` 로 올린다. 그 아래 `.aimon/` 이면(`.worktrees/k/.aimon/x`) 브랜치 규칙이 숨긴 자리에 파일이 놓이고,
아니면 브랜치 `k` 의 파일로 보여서 다음 병합이 루트로 한 번 더 올린다. 그리고 러너가 만드는 키는 실행마다 같으므로(모든
실행의 첫 격리 단계가 `a0`) 동시에 또는 잇달아 도는 두 실행이 `.worktrees/a0` 을 나눠 쓴다 — EE-28 의 소속 검사도 이것은
잡지 못한다.

**달라진 것 (2026-09-29, 빌드 리뷰).** 처음 등록할 때 이 항목은 대소문자만 다른 **절대** 경로(`{ws}/.worktrees/K/.aimon/x`)
도 다뤘다. 그 경로와 `./`·`//` 가 섞인 표기는 같은 변경의 후속 커밋에서 스코프가 정규화한 뒤 대소문자를 무시하고 브랜치
루트를 벗기도록 고쳐 이제 브랜치 자신의 `.aimon/x` 로 가서 거절된다(설계 §8.4 DV-7). 남은 것은 위 세 가지다. 상대 경로의
`.worktrees/k/…` 를 같은 식으로 벗기지 않은 것은 상대 경로가 이미 브랜치 기준이기 때문이다 — 그것을 벗기면 브랜치 안의
`.worktrees/k/` 라는 정당한 디렉터리 이름을 빼앗는다. `other` 로 가는 두 표기(상대·절대), 상대 경로 `k`·`K`, 그리고 그 병합 결과는
임시 테스트로 돌려 확인했고(macOS APFS, 대소문자 비구분), 실행 간 키 공유는 코드를 읽어 확인했다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/environment/impl/LocalIsolatedEnvironment.java` 의 브랜치 규칙
(생성자, 2026-09-29), `filesystem/impl/ScopedVirtualFileSystem.java` 의 `toBranchRelative`·`stripBranchRoot`(2026-09-29),
`workflow/impl/DefaultWorkflowContext.java` 의 `sanitizeBranchKey`.

**언제 다시 볼까.** 중첩 격리를 지원하려 할 때(바깥 브랜치 목록에서 `.worktrees/` 를 숨기는 것이 그 선행 조건이다), 한
워크스페이스에서 격리 워크플로를 동시에 둘 이상 돌릴 때, 또는 병합이 엉뚱한 브랜치 디렉터리에 파일을 남겼다는 보고가 나올
때. 가장 싼 처방 — 브랜치 규칙에 `DENY {branchPrefix}/.worktrees` 를 더하는 것 — 은 "브랜치 규칙은 부모의 것" 이라는
불변식을 깨므로 설계가 기본값으로 고르지 않았다. 셸이 만든 공유 접두어 사본에 쓴 처방(설계 §8.4 DV-6: 스코프가 목록에서
뺀다)이 여기에도 후보다 — 브랜치 접두어 아래의 `.worktrees/` 를 목록에서 빼면 규칙을 건드리지 않고 병합에서 제외된다.
다만 파일 도구로 쓴 것이 조용히 남는다는 점이 다르다(그 사본은 파일 도구로 쓸 수 없다).

출처: [`../design/tool/workflow-isolation-hardening.md`](../design/tool/workflow-isolation-hardening.md) §7 Q5 와 그 설계
리뷰(2026-09-29), §8.4 의 빌드 리뷰(2026-09-29).

## EE-47 — `WorktreeMerge.promote` 는 계보를 밝히지 않는 브랜치를 약한 검사로만 받는다 · **열림**

**무엇을.** 외부 제공자(aimon-sandbox)의 `isolate()` 가 `isolatedFrom()` 으로 부모를 밝히게 한 뒤, `promote` 가 계보를
밝히지 않는 브랜치를 거부할지 정한다. 같은 키로 두 번 `isolate()` 한 서로 다른 두 객체를 한 번에 넘기는 경우도 함께 본다.

**왜.** `promote` 의 소속 검사(EE-28)는 `isolatedFrom()` 이 빈 브랜치를 부모 자신·파일 시스템 공유·중복 검사만으로
받는다 — 지금의 샌드박스 제공자가 계보를 밝힐 수 없고, 설계 §13 은 `promote` 가 그 환경에서도 동작하라고 요구하기
때문이다. 그래서 다른 부모의 샌드박스 브랜치는 걸러지지 않는다. 그리고 한 키의 두 객체는 동일성 비교에 걸리지 않아
`FAIL` 정책에서 그 키의 모든 파일을 충돌로 보고한다(데이터 손실은 없다). 지금은 javadoc 에 "각 브랜치를 한 번씩" 이라고만
적혀 있다.

**어디.** `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/environment/WorktreeMerge.java` 의
`validateBranches`(175행, 2026-09-29), 설계 [`../design/tool/execution-environment.md`](../design/tool/execution-environment.md)
§13 의 권장 행, aimon-sandbox 의 `isolate()` 구현.

**언제 다시 볼까.** aimon-sandbox 가 `isolate()` 를 구현하거나 `isolatedFrom()` 을 밝힐 때, 또는 `promote` 를 워크플로
엔진 밖에서 부르는 코드가 생길 때.

출처: [`../design/tool/workflow-isolation-hardening.md`](../design/tool/workflow-isolation-hardening.md) §7 Q1 · Q4.
