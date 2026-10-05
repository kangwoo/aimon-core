# 실행 환경 — 도구가 쓰는 파일 시스템과 셸을 실행마다 고른다

> Status: **IMPLEMENTED** (§11 의 1–5단계). 코드 위치·단계별 결정과 이 문서에서 벗어난 점은
> [`execution-environment-implementation.md`](execution-environment-implementation.md) 에 있다 — 그 문서 끝의
> "구현이 계획과 달라진 점" 절이 이 문서에 대한 차이도 함께 적는다. 남은 열린 항목의 정본은
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) 다.
> 하위 호환은 목표가 아니다. 공개 SPI(`OrcaToolProviderContext`,
> 도구 생성자, `ToolContextKeys`, `at.aimon.core.workflow.WorktreeEnvironmentFactory` 와
> `OrcaAgentRuntimeFactory.withWorktreeEnvironmentFactory(...)`, `withShell(...)`)가 바뀌며, 이행 계층은 두지 않는다
> ([`api-stability.md`](../../project/api-stability.md) §5 의 `0.x` 정책).
>
> 적용 대상: `aimon-core` — 새 패키지 `at.aimon.core.environment{,.impl}`, `at.aimon.core.tools.{file,bash,wiki,skill,artifact}`,
> `at.aimon.core.agent.impl.orca{,.tool,.environment}`, `at.aimon.core.subagent.execution`, `at.aimon.core.agent.context`,
> `at.aimon.core.filesystem.FileMetadata` · `aimon-bootstrap` · `aimon-spring-boot-starter` · `aimon-cli`
>
> 동기: aimon-sandbox 의 [워크스페이스 샌드박스 설계](https://github.com/kangwoo/aimon-sandbox/blob/main/docs/design/workspace-sandbox.md)
> 는 기존 `Bash`/`Read`/`Write`/`Edit`/`Grep` 이 원격 격리 환경에서 돌게 만들려 한다. 이 문서는 그 전제인 코어
> 쪽 변경을 다룬다. 다만 샌드박스가 없어도 성립하는 문제들(§1)을 함께 고치므로, 샌드박스 도입 여부와 무관하게
> 먼저 들어갈 수 있다.

---

## 1. 무엇을 푸는가

아래는 전부 현재 코드에서 확인한 사실이다.

### 1.1 도구가 파일 시스템과 셸을 생성자로 쥔다

`OrcaFileToolProvider.registerTools()` 는 `context.getFileSystem()` 을 꺼내 `ReadTool` · `WriteTool` ·
`EditTool` · `GrepTool` 의 **생성자**에 넣는다. `OrcaBashToolProvider` 도 `context.getShell()` 을 `BashTool` 의
생성자에 넣는다(`BashOutputTool` 은 셸이 아니라 둘이 공유하는 `BackgroundBashManager` 를 받는다). 도구는
agent-scoped `ToolRegistry` 에 한 번 등록되므로, 도구가 보는 파일 시스템과 셸은 **에이전트 단위로 고정**된다.
같은 에이전트의 두 세션이 서로 다른 작업 디렉터리나 격리 환경을 쓸 방법이 없다.

### 1.2 그래서 워크트리 격리가 레지스트리를 복제한다

워크플로 격리 브랜치는 브랜치마다 다른 파일 시스템 뷰가 필요하다. 그래서 `WorktreeToolEnvironmentFactory`
(공개 SPI `at.aimon.core.workflow.WorktreeEnvironmentFactory` 의 기본 구현)는 `SubagentExecutionEnvironment` 를
파생할 때 **레지스트리를 새로 만들어** 파일 도구 넷을 `ScopedVirtualFileSystem(base, ".worktrees/" + branchKey)`
위에 다시 등록한다. artifact-aware 변형을 썼는지까지 분기해서다(그 분기가 없던 때 격리 브랜치에서 artifact 등록이
조용히 빠졌다고 클래스 javadoc 이 적고 있다). 게다가 파일 도구가 아닌 도구는 그대로 옮겨 오므로 `Bash` 는 베이스
셸에 묶인 채다. 브랜치 안에서 `Bash` 로 쓴 파일은 격리되지 않는다. 격리가 파일 도구에만 걸려 있다.

### 1.3 VFS 하나가 작업 파일과 제어 평면을 겸한다

`OrcaAgentRuntimeFactory.doCreate()` 는 같은 `fileSystem` 을 다음 모두에 쓴다.

| 용도 | 코드 |
|------|------|
| 모델의 파일 도구 | `OrcaToolProviderContext.fileSystem` → `OrcaFileToolProvider` |
| 서브에이전트 정의 | `new DefaultSubagentRegistry(fileSystem, agentsDirectory)` |
| 스킬 정의 | `buildSkillRegistry(agentBundle, fileSystem, skillsDirectory)` (스킬 레지스트리를 주입받지 않았을 때) |
| 커맨드 정의 | `new DefaultCommandRegistry(…, skillRegistry, fileSystem, commandsDirectory)` |
| 백그라운드 태스크 출력 | `new VfsTaskOutputStore(fileSystem)` (기본값. `taskOutputStoreFactory` 가 있으면 그것에 `fileSystem` 을 넘긴다) |
| 서브에이전트 태스크 결과 | `taskResultStoreFactory.apply(fileSystem)` (팩토리가 설정됐을 때) |
| 세션 스냅숏 | `sessionSnapshotStoreFactory.apply(fileSystem)` (팩토리가 설정됐을 때. 기본은 in-memory) |
| 워크플로 격리 | `new WorktreeToolEnvironmentFactory(fileSystem)` (또는 `worktreeEnvironmentFactoryFactory.apply(fileSystem)`) |
| 런타임이 들고 있는 VFS | `OrcaAgentRuntime.builder().fileSystem(fileSystem)` → `OrcaAgentExecutor` 가 `ContextAssemblyRequest` 에 실어 `GitStatusContextProvider`·`DirectorySummaryContextProvider` 가 읽는다 |
| 프롬프트의 작업 디렉터리 | `Environment.createWithWorkingDirectory(fileSystem.getWorkingDirectory())` |

결과가 둘이다. 첫째, **모델이 `Write` 로 자기 스킬·서브에이전트 정의와 태스크 출력을 덮어쓸 수 있다.** 그것을
막는 장치는 경로 규약뿐이다. 둘째, 작업 파일을 격리 환경으로 옮기려면 제어 평면까지 따라 옮겨야 한다. 스킬
정의를 샌드박스 컨테이너 안에 두자는 사람은 없다.

### 1.4 프롬프트가 호스트를 묘사한다

`SystemPromptRenderer` 와 `EnvironmentContextProvider` 는 `Environment` 의 `Platform` · `OS Version` 을
프롬프트에 넣는다. 이 값은 JVM 이 도는 호스트의 것이다. 셸이 다른 곳(컨테이너, 원격 샌드박스)에서 돌면
프롬프트는 macOS 라고 말하는데 명령은 Linux 에서 돈다. 모델은 `sed -i ''` 같은 호스트 방언을 쓰게 된다.

### 1.5 스킬 경로가 "셸도 같은 VFS 를 본다"고 가정한다

`SkillTool` 은 스킬의 VFS `baseDir` 을 렌더 컨텍스트에 넣고, 스킬 본문의 `${AIMON_SKILL_DIR}` 자리표시자가 그
경로로 치환되어 모델에게 간다. 모델은 그 경로로 `bash ${AIMON_SKILL_DIR}/scripts/x.sh` 를 실행하거나 `Read` 로
참고 파일을 연다. 이것이 동작하는 것은 `LocalFileSystem` 의 루트와 `LocalShell` 의 파일 시스템이 우연히 같은
디스크이기 때문이다. GridFS/S3 VFS 에서는 지금도 동작하지 않는다. 게다가 스킬 기반 슬래시 커맨드 경로는 그
디렉터리를 렌더 컨텍스트에 아예 싣지 않는다(§4.4).

### 1.6 `VIRTUAL_FILE_SYSTEM` 키는 채워지지 않는다

`WikiIngestTool` 은 `ToolContextKeys.VIRTUAL_FILE_SYSTEM` 에서 VFS 를 꺼내고, 없으면 `"No virtual file system
configured for this agent"` 에러를 낸다. 그런데 이 저장소의 main 소스에서 그 키를 `put` 하는 곳은 없다(테스트만
넣는다). 애플리케이션이 enricher 로 직접 넣지 않으면 이 도구는 운영에서 항상 실패한다. 도구가 파일 시스템을
얻는 경로가 둘(생성자, 컨텍스트 키)로 갈라져 있고, 그중 하나가 배선되지 않은 것이다.

### 1.7 작은 것 셋

- **`Grep` 은 파일을 하나씩 읽는다.** `listRecursive` 후 파일마다 `read` 한다. 로컬에서는 견딜 만하지만
  원격 VFS 에서는 파일 수만큼 왕복이 생긴다
- **`READ_FILES_KEY` 는 경로 집합이다.** `Edit` 은 "읽은 적이 있는가"만 확인한다. 읽은 **뒤에** 다른
  실행이나 셸이 파일을 바꿨으면 모델은 낡은 내용을 기준으로 고친다. `Write` 는 아예 검사하지 않는다
- **셸 소유권이 두 방식이다.** `OrcaAgentRuntimeFactory.withShell()` 로 주면 어셈블리가 소유하고, 안 주면
  팩토리가 `LocalShells.create()` 로 만들어 `ownedShell` 로 런타임에 넘기고 런타임이 닫는다. 같은 타입의 수명
  규칙이 배선 방식에 따라 갈린다

---

## 2. 목표와 비목표

**목표**

- 도구가 보는 파일 시스템과 셸을 **실행(execution)마다** 정할 수 있다
- 파일 도구와 셸은 한 환경에서 나오며, **같은 파일 시스템을 본다**
- 모델이 만지는 **작업 환경**과 프레임워크가 쓰는 **제어 저장소**를 타입으로 분리한다
- 프롬프트가 묘사하는 환경은 명령이 실제로 도는 환경이다
- 워크트리 격리가 레지스트리 복제 없이 환경 파생으로 된다

**비목표**

- 샌드박스 자체의 구현. 코어는 SPI 와 로컬 구현만 갖는다
- 셸 경로를 막는 파일 권한 모델. 모델이 셸을 가지면 파일 도구의 경로 제한은 우회된다. 경계는 환경 자체
  (로컬이면 운영자 책임, 샌드박스면 컨테이너)다

---

## 3. 결정 요약

1. **`ExecutionEnvironment` 를 중립 SPI 로 둔다**(`at.aimon.core.environment`). 파일 시스템 · 셸 · 서술자 ·
   선택 기능을 한 값으로 묶는다
2. **실행기가 실행마다 `ExecutionEnvironmentProvider` 에게 묻고**, 결과를 `ToolContextKeys.EXECUTION_ENVIRONMENT`
   에 넣는다. enricher 가 키를 덮어쓰는 방식이 아니다(§12)
3. **파일 도구 · `Bash` · `WikiIngest` · `Skill` 은 생성자에서 환경을 받지 않는다.** `execute()` 마다
   컨텍스트에서 꺼낸다. 없으면 에러를 낸다. 프롬프트 조립(서술자, git·디렉터리 요약)도 같은 환경을 쓴다
4. **제어 저장소를 분리한다.** 런타임 팩토리는 `controlFileSystem`(에이전트·스킬·커맨드 정의, 태스크 출력,
   스냅숏, artifact 보관)을 따로 받는다. 파일 도구는 그것을 보지 않는다
5. **격리는 환경의 기능이다.** `ExecutionEnvironment.isolate(branchKey)` 가 파생 환경을 돌려주고,
   `WorktreeEnvironmentFactory`(공개 SPI)와 `WorktreeToolEnvironmentFactory` 는 사라진다
6. **프롬프트의 환경 블록은 `EnvironmentDescriptor` 에서 나온다.** 호스트 JVM 값이 아니다
7. **파일 stamp 로 낡은 쓰기를 막는다.** `Edit` 과 기존 파일을 덮어쓰는 `Write` 는, 읽은 시점의 stamp 와 지금
   stamp 가 다르면 거부한다
8. **셸과 파일 시스템의 소유자는 환경 제공자다.** 런타임은 그것들을 직접 닫지 않는다. 제공자 자체를 닫는 것은
   만든 쪽이고, 런타임이 닫는 경우는 런타임별 제공자 함수가 돌려준 제공자 하나뿐이다(§4.3)

---

## 4. SPI

### 4.1 타입

```java
package at.aimon.core.environment;

/** The filesystem, shell and self-description one execution's tools run against. Not Closeable: a view. */
public interface ExecutionEnvironment {
    VirtualFileSystem fileSystem();
    VirtualShell shell();
    EnvironmentDescriptor descriptor();

    default Optional<ContentSearch> contentSearch() { return Optional.empty(); }

    /** Files written here outlive the execution? false for ephemeral environments (sandbox /workspace). */
    default boolean durable() { return true; }

    /** Makes control-plane files readable from this environment's shell and file tools; returns the path (§4.4). */
    String stage(StagedResource resource);

    /** A derived environment whose writes are isolated under branchKey; empty = no isolation, throw = refused (why). */
    default Optional<ExecutionEnvironment> isolate(String branchKey) { return Optional.empty(); }

    /** The environment this branch was isolated from, if it declares its lineage (WorktreeMerge checks it). */
    default Optional<ExecutionEnvironment> isolatedFrom() { return Optional.empty(); }

    /** The longest a background command may run here; empty = the caller's default of 24 hours (§5.3). */
    default Optional<Duration> backgroundCommandTimeout() { return Optional.empty(); }
}

public interface ExecutionEnvironmentProvider {
    ExecutionEnvironment resolve(EnvironmentRequest request);   // once per execution

    /** Called by the assembly per runtime it builds; closing the handle says that runtime is gone (§4.3). */
    default RuntimeBinding bindRuntime(AgentRuntimeId agentRuntimeId) { return RuntimeBinding.NONE; }
}
```

| 타입 | 내용 |
|------|------|
| `EnvironmentDescriptor` | `workingDirectory` · `platform` · `osVersion` · `shellName` · `notes`(자유 텍스트 한두 줄, e.g. "isolated sandbox; network restricted"). 불변 + 빌더 |
| `EnvironmentRequest` | `Agent` · `AgentRuntimeId` · `SessionId`? · `ExecutionId`? · `invokingSessionId`? · `Principal`? · `parent`(`ExecutionEnvironment`?, 포크의 부모 환경) · `branchKey`? · `fork`(`ForkDefinition`?, 포크가 도는 서브에이전트의 이름과 `attributes`) |
| `ContentSearch` | `search(ContentQuery) → ContentSearchResult`. `GrepTool` 의 입력(패턴 · 경로 · glob · 대소문자 · 컨텍스트 줄 · 출력 모드 · head limit)을 그대로 옮긴 값 |
| `StagedResource` | 제어 저장소 쪽 파일 묶음 — `sourceFileSystem` · `sourceDir` · `contentKey`(디렉터리 해시, 사본 경로의 일부, §4.4) · `name`. 스킬의 것은 `SkillRepository.resolveSource` 가 낸다 |
| `FileStamp` | `size` · `modifiedAt` · `etag`? (§7) |
| `RuntimeBinding` | `close()` 하나짜리 핸들(`NONE` 포함). 제공자가 런타임 하나를 위해 쥔 것을 놓는다 — 멱등이고 던지지 않으며, **도는 명령은 멈추지 않는다**(§4.3) |

`ExecutionEnvironment` 가 `Closeable` 이 아닌 것은 의도다. 환경은 실행마다 만들어지는 **뷰**이고, 그 뒤의
자원(로컬 셸 프로세스 풀, 원격 연결)은 제공자가 소유한다. 실행이 끝났다고 셸을 닫으면 같은 셸을 쓰는 다른
실행이 끊긴다.

### 4.2 기본 구현 — 로컬

`LocalExecutionEnvironmentProvider`(`at.aimon.core.environment.impl`)는 `LocalFileSystem` 하나와 `LocalShell`
하나를 소유하고, 모든 요청에 같은 `LocalExecutionEnvironment` 를 돌려준다.

같은 이름의 타입이 이미 있다 — `at.aimon.core.agent.impl.orca.environment` 의 `VirtualExecutionEnvironment`
(`fileSystem()`·`shell()` 두 메서드)와 그 구현 `LocalExecutionEnvironment`. main 소스에 소비자가 없고 테스트만
쓴다. 새 타입이 이 둘을 **대체**하며 옛 것은 1단계(§11)에서 지운다. 두 `LocalExecutionEnvironment` 가 공존하는
기간을 두지 않는다. 같은 PR 에서 ArchUnit 규칙 `filesystemImplMustNotLeakOutsideFilesystemTree` 의 허용 패키지
(지금 `at.aimon.core.agent.impl.orca.environment..`)를 `at.aimon.core.environment.impl..` 로 옮긴다 — 로컬
제공자가 `LocalFileSystem`·`ScopedVirtualFileSystem` 을 직접 만드는 유일한 조립 지점이 되기 때문이다.

- `descriptor()` — 호스트를 읽어 만든 `EnvironmentDescriptor`(작업 디렉터리·platform·OS 버전·셸 이름). 로컬에서는
  호스트가 곧 실행 환경이므로 참이다. 이 설계를 쓸 때는 같은 값을 옛 `Environment.createDefault()` 가 들고 있었고,
  그 타입은 그 뒤 시간대만 든 `UserLocale` 이 되었다(EE-14)
- `contentSearch()` — `rg` 가 PATH 에 있으면 그것을, 없으면 비어 있음(→ `GrepTool` 이 기존 방식으로 돈다)
- `stage()` — 소스가 작업 환경과 **같은 `VirtualFileSystem` 인스턴스**면(제어 저장소를 가르기 전인 §11 1·2단계)
  그 경로를 그대로 돌려준다. 아니면 §4.4 의 규칙대로 작업 환경의 스테이징 영역에 복사한다. 스테이징 영역은 파일
  도구에게 **읽기 전용**이다(§9.2)
- `isolate(branchKey)` — `ScopedVirtualFileSystem` + **`workingDirectory` 를 브랜치 루트로 둔 셸 뷰**. 셸 뷰는
  모든 명령의 기본 cwd 를 브랜치 루트로 바꿀 뿐이다. 절대 경로 쓰기까지 막지는 못한다. 로컬 격리가 "파일
  도구 + 기본 cwd" 수준이라는 것을 javadoc 에 적는다. 지금은 셸 쪽 격리가 아예 없으니 그보다는 낫다.
  파생 환경은 `durable() == false` 를 돌려준다 — 브랜치 디렉터리는 병합 뒤나 폐기 시 사라지므로 그 안의 경로를
  artifact 로 등록하면 안 된다(§9.3). 브랜치는 부모의 경로 규칙을 **브랜치 루트 기준으로 한 번 더** 건다(§9.2) —
  브랜치의 `.aimon/` 도 루트의 것처럼 보이지 않는다. 브랜치는 `isolatedFrom()` 으로 부모를 밝히고, 다시
  `isolate()` 하면 이유를 담은 `UnsupportedOperationException` 을 던진다(중첩 격리 없음 —
  [`workflow-isolation-hardening.md`](workflow-isolation-hardening.md) §1.4). 키는 대소문자만 다른 두 값을 구분하지
  못한다 — 대소문자를 구분하지 않는 디스크에서 `a` 와 `A` 는 한 디렉터리다. 러너가 만드는 키는 소문자뿐이라
  부딪히지 않는다

### 4.3 수명

| 대상 | 수명 | 소유·종료 |
|------|------|----------|
| `ExecutionEnvironmentProvider` | Application — 런타임별 제공자 함수가 만든 것은 Agent | 어셈블리가 만들고 앱 shutdown 에 닫는다(`AutoCloseable` 구현은 선택). 런타임별 함수가 돌려준 것은 런타임이 소유한다 — 아래 |
| `RuntimeBinding` | Agent — 런타임 인스턴스 하나 | 런타임을 만든 어셈블리가 `bindRuntime` 으로 얻어 그 런타임과 함께 닫는다. 런타임 자신은 닫지 않는다 |
| 제공자가 쥔 셸·파일 시스템·원격 연결 | Application (또는 제공자가 정한 수명) | 제공자. 런타임 하나의 몫은 그 바인딩이 닫힐 때 놓는다 |
| `BackgroundBashManager` (작업 목록 · 노드 로컬 핸들 · 실행 스레드) | 부트스트랩 스택에서는 Application. 코어만 쓰는 조립의 기본은 도구 레지스트리마다 하나(Agent) | 만든 쪽. 스택은 `TeardownPhase.BACKGROUND_COMMANDS` 에서 닫는다(§5.3) |
| `BackgroundBashStore` (작업의 메타데이터) | Application | 넘겨준 쪽. 기본은 매니저가 만든 in-memory |
| `ExecutionEnvironment` | Execution | `ToolContext` 와 함께 버려진다. 닫을 것이 없다 |
| `controlFileSystem` | Application | 어셈블리 |

`OrcaAgentRuntime` 의 `ownedShell` 과 "withShell 이면 어셈블리 소유, 아니면 런타임 소유"의 두 갈래 규칙은
없어진다. 런타임은 셸·파일 시스템을 소유하지 않는다.

**예외 하나 — 런타임별 제공자 함수.** `OrcaAgentRuntimeFactory.withExecutionEnvironmentProviderFactory(id -> ...)` 가
돌려준 제공자는 그 런타임이 소유한다. `OrcaAgentRuntime.close()` 가 닫고(다른 자원을 다 닫은 뒤 마지막으로),
`create(...)` 가 함수의 답을 받은 뒤 실패하면 `create(...)` 가 닫는다. 그 함수를 넘긴 호출자는 런타임이 언제
사라지는지도, 생성이 실패했는지도 알 수 없으므로 닫을 시점이 없다 — 원칙대로 두면 테넌트마다 제공자(와 그 뒤의 셸
프로세스)가 샌다(EE-21). 그래서 함수는 그 런타임 전용 제공자를 돌려줘야 하고, 여러 런타임이 나눠 쓰는 제공자는
`withExecutionEnvironmentProvider(p)` 로 준다 — 이쪽은 계속 빌린다. 소유가 설정에 따라 갈린다는 점은 옛 `ownedShell`
과 같지만, 갈리는 기준이 "어느 API 로 넘겼는가" 하나이고 둘 다 제공자 단위다. 부트스트랩은 이 함수를 쓰지 않는다 —
제공자를 직접 만들어 `withExecutionEnvironmentProvider` 로 넘기고 teardown 계획에 올린다.

**부트스트랩의 제공자는 스택당 하나다 (EE-7).** 제공자가 런타임마다 하나였을 때는 테넌트 런타임이 축출되면 제공자도
닫혔다. 닫힘이 실제 동작인 셸(샌드박스)에서는 그 런타임이 띄워 둔 백그라운드 명령이 끊기고, 로컬에서는 명령은 남되
작업 목록이 사라져 아무도 추적하지 못한다. 그래서 제공자를 표의 첫 행대로 Application 수명에 두고, 런타임별 정리는
**바인딩**으로 받는다.

- 제공자는 `request.agentRuntimeId()` 로 워크스페이스를 고른다. 로컬 기본값은 `PerRuntimeLocalEnvironmentProvider`
  (`at.aimon.core.environment.impl`) — 런타임 id 별 슬롯에 `LocalExecutionEnvironmentProvider` 를 하나씩 둔다
- 어셈블리는 런타임을 만들 때 `bindRuntime(id)` 를 **가장 먼저** 부르고, 돌려받은 `RuntimeBinding` 을 그 런타임의
  자원으로 올린다. 그래서 런타임 → 제어 저장소 → 바인딩 순으로 닫힌다. 만들다 실패하면 EE-23 의 되감기가 바인딩도 닫는다
- 훅이 `onRuntimeEvicted(id)` 같은 id 콜백이 아니라 핸들인 것은, **같은 id 의 런타임 둘이 잠시 함께 살 수 있기**
  때문이다. `AgentRuntimeResolver.invalidate` 는 id 를 즉시 내리고 옛 런타임은 마지막 보유자가 놓을 때 닫는다. 그 사이의
  요청이 새 런타임을 만든다. id 콜백이면 옛 런타임의 늦은 close 가 새 런타임이 쓰는 몫을 정리한다. 로컬 슬롯은 바인딩
  수가 0 이 될 때만 닫힌다
- 바인딩을 닫는 것은 **도는 명령을 멈추지 않는다.** 놓을 수 없는 것은 명령이 끝날 때나 제공자의 `close()` 때 놓는다.
  로컬 슬롯이 이 계약을 그냥 닫는 것으로 지킬 수 있는 까닭은 `LocalShell` 이 명령에 대해 아무것도 쥐지 않기 때문이고
  (명령마다 프로세스), 그래서 슬롯 함수의 반환 타입이 인터페이스가 아니라 `LocalExecutionEnvironmentProvider` 다
- 런타임은 바인딩하지 않는다. "런타임은 아무것도 닫지 않는다" 는 원칙과 위의 예외 하나는 그대로다. 그래서 부트스트랩을
  거치지 않는 조립은 `bindRuntime` 을 스스로 불러야 한다(EE-56)

`ExecutionEnvironmentSpec` 의 모양도 이에 맞춰 바뀌었다 — 런타임마다 제공자를 돌려주던 `factory(Function)` 은 없어지고,
스택이 한 번 불러 얻은 하나를 소유하는 `provider(Supplier)` 와 호출자가 소유하는 `shared(provider)` 가 남는다. 설계와
구현이 설계에서 벗어난 점은 [`execution-environment-ee13-ee7-background-lifecycle.md`](execution-environment-ee13-ee7-background-lifecycle.md)
에 있다.

### 4.4 스테이징

스킬 정의(`SKILL.md`)를 읽는 것은 프레임워크가 제어 저장소에서 JVM 으로 직접 하므로 환경과 무관하다. 스테이징이
필요한 것은 모델이 스킬 본문을 따라 **스킬 디렉터리 안의 다른 파일**(스크립트, 참고 문서)을 셸이나 파일 도구로 열
때다. 로컬·샌드박스 제공자 모두 아래 규칙을 따른다.

**경로는 내용 주소다.** 사본은 `{stagingRoot}/{name}/{contentKey}/` 에 둔다. `contentKey` 는 스킬 디렉터리 전체
(상대 경로 + 내용)의 해시다. 원본이 바뀌면 경로가 바뀌므로 옛 사본을 덮어쓰거나 지울 일이 없고, 같은 실행 안에서
먼저 렌더된 본문이 가리키던 경로도 계속 유효하다. 해시는 `stage()` 호출마다 계산하지 않는다 — 스킬 레지스트리가
정의를 읽거나 다시 읽을 때 한 번 계산해 `StagedResource` 에 싣는다. 호출마다 계산하면 스킬 디렉터리 전체를 매번
제어 저장소에서 읽게 된다.

옛 `contentKey` 사본은 실행 중에는 지우지 않는다(아직 그 경로를 들고 있는 실행이 있을 수 있다). 로컬 제공자는
시작할 때, 즉 어떤 실행도 그 경로를 참조할 수 없을 때 이름마다 최신이 아닌 사본을 지운다. 샌드박스는 재생성이 곧
정리다.

**생략은 대상 쪽을 보고 판단한다.** 복사가 끝나면 마지막에 `{…}/{contentKey}/.staged` 마커를 쓴다. `stage()` 는
마커가 **대상 환경에 있을 때만** 복사를 건너뛴다. 제공자 메모리에 "이미 올렸다"를 기억하는 방식은 쓰지 않는다 —
비영속 환경(`durable() == false`)은 재생성되면 사본이 사라지는데, 메모리의 기록은 남아 스크립트가 없는 경로를
모델에게 준다. 마커를 마지막에 쓰므로 중간에 끊긴 복사는 다음 호출이 다시 한다.

*2026-10-05 (EE-37) 이후:* 마커만으로는 증거가 되지 않는다 — `contentKey` 는 예측 가능하므로 저장소에 커밋되었거나 셸로
심은 사본도 경로와 마커를 함께 갖는다. 로컬 제공자는 **처음 만나는 사본을 한 번 검증한다**: 마커가 그 키를 담고, 자원의
파일이 모두 있고, 그 파일들이 키로 해시되어야 재사용한다. 자원의 파일이 아닌 것은 지운다(실행이 남긴 `__pycache__` 같은
것이 사본 전체의 재복사를 부르지 않게). 검증을 통과한 대상은 그 제공자 인스턴스가 기억하고, 이후 호출은 마커의
`exists` 만 본다 — 위 문단이 경계한 "메모리의 기록" 은 **마커가 있을 때만** 쓰이므로 재생성된 비영속 환경을 속이지 않는다.

**변조는 막지 않고 격리한다.** 파일 도구에는 스테이징 영역이 읽기 전용이지만 셸은 쓸 수 있다(§2 비목표). 모델이
셸로 사본을 고치면 같은 `contentKey` 경로에 고친 내용이 남는다. 이것은 모델 자신의 환경 안의 일이라 보안 경계를
넘지 않고, 원본(제어 저장소)은 영향을 받지 않는다. 비용이 큰 매 실행 재복사나 **매 호출** 전체 해시 재검증은 두지 않는다
— 처음 만나는 사본의 한 번 검증(위)은 그것과 다르며, 검증 뒤 셸이 고친 내용은 여전히 비목표다.

**크기 상한이 있다.** 스킬 디렉터리 하나의 스테이징 총량에 상한을 두고(설정 이름은 3단계 PR), 넘으면 `stage()` 는
예외를 던지고 호출자는 `ToolResult.error` 로 바꾼다. 스킬 디렉터리의 `.stageignore`(gitignore 문법)로 큰 에셋을
뺄 수 있다. 첫 `stage()` 는 게으른 프로비저닝(§13)을 촉발하므로 샌드박스에서는 첫 스킬 호출이 느릴 수 있다 —
의도된 비용이며, 스킬을 부르지 않는 실행은 치르지 않는다.

**격리 브랜치와 공유한다.** `stagingRoot` 는 환경의 작업 트리 **밖**이다. `isolate()` 가 만든 파생 환경은 부모와
같은 `stagingRoot` 를 쓴다. 브랜치마다 다시 복사하지 않고, 워크트리 병합(로컬 `WorktreeMerge`, 샌드박스 git)에
사본이 섞이지 않는다. 로컬은 `{project}/.aimon-staged/`(작업 트리와 같은 디스크이지만 `isolate()` 의 브랜치
접두어 밖), 샌드박스는 제공자가 정한다(§13).

**스킬을 렌더하는 모든 경로가 거친다.** `${AIMON_SKILL_DIR}` 에 들어가는 값은 언제나 `stage()` 의 반환값이다.
`Skill` 도구, 스킬 포크, 스킬 기반 슬래시 커맨드(`SkillBackedCommandExecutor` → `LlmSkillExecutor`)가 모두 여기에
해당한다. 커맨드 경로는 지금 렌더 컨텍스트에 스킬 디렉터리를 아예 싣지 않아 `${AIMON_SKILL_DIR}` 가 빈 문자열이
되는 결함이 있고, 이 설계와 별개로 먼저 고친다(PR #193). 그 수정이 두 경로의 렌더 컨텍스트 조립을 한 곳으로
모은다 — 스킬만 보는 부분(`at.aimon.core.skill.render.SkillRenderContexts.resolveSkillBaseDir`)과 `ToolContext`
를 읽는 부분(`at.aimon.core.tools.SkillRenderContextAccess.builderFor(Skill, ToolContext)`)으로. `stage()` 는
환경이 필요하므로 뒤쪽, `SkillRenderContextAccess` 에서 `EXECUTION_ENVIRONMENT` 를 꺼내 `resolveSkillBaseDir`
의 결과(제어 저장소 쪽 경로) 대신 `env.stage(...)` 의 반환값을 `skillBaseDir` 로 넣으면 된다. `skill.render` 는
환경을 모르는 채로 남는다.

**모든 스킬 저장소가 스테이징 소스를 낸다.** `SkillRepository` 는 스킬마다 `stage()` 에 넘길 소스 — 읽기만 하는
`VirtualFileSystem` 과 그 안의 스킬 디렉터리 — 를 돌려준다(`resolveSource`). VFS 저장소는 자기 VFS 를, 호스트
경로 저장소(`PathSkillRepository`)는 그 루트 위의 읽기 전용 로컬 파일 시스템(`ReadOnlyLocalFileSystem`)을, 클래스패스 저장소
(`ClasspathSkillRepository`)는 번들 스킬 머티리얼라이즈와 같은 방식으로 트리를 걷는 읽기 전용 클래스패스 VFS 를
낸다. 그래서 스킬이 어디서 왔든 레지스트리가 `StagedResource` 를 싣고, `${AIMON_SKILL_DIR}` 는 언제나
`stage()` 의 반환값이다 — 호스트 경로 스킬이라고 그 호스트 경로를 그대로 넣지 않는다(비로컬 제공자에서는 그
경로가 없다). 소스는 선택 사항이 아니다: 스킬을 찾았는데 소스가 비었으면 저장소 결함으로 보고 적재가 실패한다.
클래스패스 배치를 열거할 수 없으면(지원하지 않는 URL 프로토콜) 소스에는 `SKILL.md` 만 보이고 경고가 남는다 —
스킬은 쓸 수 있고, 다른 파일은 지금처럼 닿지 않는다. 레지스트리를 거치지 않고 손으로 조립한 `Skill` 처럼
`StagedResource` 가 없는 스킬은 `${AIMON_SKILL_DIR}` 를 빈 문자열로 렌더하고 경고한다 — 저장소 경로로 되돌아가지
않는다. 스킬 도구가 모델에게 보여 주는 파일 목록도 같은 스테이징 경로 기준이다.

**심볼릭 링크는 루트 안에서만 따라간다.** 호스트 경로 저장소의 소스(`ReadOnlyLocalFileSystem`)가 스킬 디렉터리를
훑거나 읽을 때, 링크는 그 **실제 경로(real path)** 가 스킬 저장소 루트 안이거나 운영자가 명시한 허용 루트 안일 때만
따라간다. 스킬 디렉터리 자체가 링크인 경우(`skills/foo -> ../shared/foo` 로 설치·공유한 스킬)와 스킬 안의 하위
디렉터리·파일이 링크인 경우에 똑같이 적용된다 — 훑는 동안 만나는 모든 항목의 실제 경로를 검사한다. 있는지·디렉터리인지
묻거나 메타데이터·한 단계 목록을 요청할 때도 같은 검사를 거치며, 허용 범위 밖을 가리키는 링크에는 "없다" 가 아니라 같은
오류로 답한다(대상이 실제로 있든 없든 답이 같아서 루트 밖에 무엇이 있는지 드러나지 않는다). 읽기는 검사한 실제 경로를
연다. 두 경우가 아니면
건너뛰지 않고 링크와 그 실제 경로를 밝힌 오류로 스캔을 거부하므로, 그 스킬은 적재되지 않는다. 조용히 빠진 파일로 만든
사본이 `${AIMON_SKILL_DIR}` 에 놓이는 것보다 적재 실패가 낫다. 읽을 수 없는 파일도 같다 — `.stageignore` 로 빼지 않은
항목을 스캔이 읽지 못하면 그 파일을 건너뛰지 않고 스캔이 실패한다. 적재되지 않는 것은 **그 스킬 하나**다. 스킬 목록
(`getAllSkills`, `reloadAll`)은 그 스킬을 경고 로그와 함께 빼고 나머지를 돌려주므로, 목록으로 만드는 `Skill` 도구의
정의, `/skills`, 스킬 기반 슬래시 커맨드, REPL 배너는 그대로 뜬다. 그 스킬을 이름으로 부르면(`getSkill`) 같은 오류가
난다. 허용 루트는
`PathSkillRepository.builder(root).allowedLinkRoot(...)` 로 정하고, 기본값은 비어 있어 저장소 루트만 허용한다. 조상으로
되돌아가는 링크는 경고와 함께 건너뛰고, 끊긴 링크는 일반 파일이 아니므로 목록에 없다. 마지막 안전망으로, 소스에서
`SKILL.md` 가 보이는 디렉터리가 파일 0개로 스캔되고 소스의 목록에도 아무 파일이 없으면 레지스트리가 적재를 실패시킨다 —
소스가 디렉터리 안을 보지 못한 것이고, 그대로 두면 빈 사본이 스테이징된다. 목록에는 파일이 있는데 `.stageignore` 가 전부
뺀 경우는 작성자가 원한 것이므로 빈 사본으로 적재된다. 이 규칙은 **읽는 쪽**의 것이다. 로컬 제공자의 시작 스윕은
**지우는 쪽**이라 반대로 링크를 전혀 따라가지 않는다(링크된 스테이징 루트는 건너뛰고, 이름·사본 수준에서
`NOFOLLOW_LINKS`). 스윕은 또 작업 트리 루트 자체는 훑지 않고, 이름이 `contentKey` 모양(정확히 16자리 소문자 16진수)인 사본 디렉터리만
지운다. `stagingRoot` 는 작업 트리 아래 디렉터리 이름 하나여야 하고(`.`·`..`·구분자 불가) 제어 저장소일 수 없으며,
로컬 제공자는 이를 `build()` 에서 검사한다 — 잘못 설정된 스테이징 루트가 사용자의 디렉터리를 지우게 두지 않는다.

---

## 5. 실행기 통합

### 5.1 조립 순서

`OrcaAgentExecutor.createToolContext()` 와 `DefaultSubagentExecutor` 의 컨텍스트 조립:

```
1. env = provider.resolve(request)            <- new, once at execution start (before prompt assembly);
                                                 failure => UnavailableExecutionEnvironment
2. framework keys (AGENT_RUNTIME_ID, SESSION_ID/EXECUTION_ID, PRINCIPAL, ARTIFACT_COLLECTOR, ...)
3. put EXECUTION_ENVIRONMENT = env
4. put FILE_STAMPS_KEY = new ConcurrentHashMap  (replaces READ_FILES_KEY)
5. applyEnrichers(builder, ...)               <- may read EXECUTION_ENVIRONMENT, may not replace it
```

`resolve()` 는 **실행 시작에 한 번**, 반복 루프와 프롬프트 조립보다 먼저 부른다. `createToolContext()` 는 지금도
실행당 한 번이지만, 환경을 읽는 쪽은 도구만이 아니다 — `ContextAssemblyRequest` 의 서술자(§10)와 `fileSystem`
(`GitStatusContextProvider`·`DirectorySummaryContextProvider`)도 같은 값을 받는다. 지금 그 자리에 들어가는
`agentRuntime.getFileSystem()` 은 없어진다. 한 실행 안에서 프롬프트가 묘사하는 환경과 도구가 쓰는 환경이 다를 수
없게 하는 것이 목적이다.

실행마다 `resolve()` 를 부르는 곳은 셋이다 — `OrcaAgentExecutor.execute()` 의 시작(메인 턴과 슬래시 커맨드 흐름이
같은 값을 쓴다. 커맨드 흐름은 ReAct 루프 대신 도는 같은 실행의 분기이지 별도 실행이 아니다), `DefaultSubagentExecutor`
(포크 — 워크플로 단계도 포크로 돈다), `RoutineExecutor`(스케줄 루틴). 실행 밖의 호출이 하나 더 있다: CLI 의
`AgentSetupFactory.workingDirectoryOf` 가 부트스트랩 때 작업 디렉터리를 알아내려고 한 번 부른다. 어떤 실행에도 속하지
않으므로 "실행당 한 번" 규칙과 부딪히지 않는다. 이 밖에 `ToolContext` 를 손으로 조립하는 경로는 `resolve()` 를 다시
부르지 않고, 이미 해석된 실행의 환경을 싣는다. 한 실행에서 두 번 부르면 한 실행에 환경이 둘이 된다.

**프롬프트 조립이 환경을 읽으면 게으른 제공자는 그때 원격 자원을 만든다.** 서술자(§10)는 `resolve()` 가 돌려준
값이라 괜찮지만, `GitStatusContextProvider`·`DirectorySummaryContextProvider` 는 매 턴 `fileSystem()` 을 읽는다
(`.git/HEAD`, 루트 목록). 두 제공자는 옵트인이라 기본 조립에는 없다. 샌드박스처럼 첫 파일 접근에서 프로비저닝하는
제공자와 함께 쓰면 "명령을 한 번도 실행하지 않는 턴"도 샌드박스를 띄우고, 일시 정지된 샌드박스를 매 턴 깨운다.
그 조합을 쓸지는 어셈블리가 정한다 — 코어는 두 제공자에게 환경의 준비 상태를 알리는 수단을 두지 않는다.

`resolve()` 가 예외를 던지면 실행을 실패시키지 않고 `UnavailableExecutionEnvironment` 를 넣는다. 이 환경은
파일 시스템·셸의 모든 호출에서 원인을 담은 예외를 던지고, 도구는 그것을 `ToolResult.error` 로 바꾼다. 서술자는
던지지 않는다 — `notes` 에 "execution environment unavailable: {원인}" 을 싣고 나머지 필드는 비워, 모델이 첫
도구 호출 전에 사정을 안다. 파일·셸이 필요 없는 턴(질문에 답만 하는 턴)은 영향을 받지 않고, 필요한 턴은 이유를
알고 실패한다. 호스트 환경으로 되돌아가는 경로는 없다.

`ToolContext.Builder` 에서 `EXECUTION_ENVIRONMENT` 를 enricher 가 **덮어쓰면 예외**를 낸다. 지금 빌더의
`put` 은 `HashMap.put` 이라 조용히 덮어쓴다. 한 번 쓰기 키(write-once key)를 `ToolContextKey` 의 속성으로 새로
두고, 이 키에 붙인다. 문자열 키 `put(String, Object)` 로 같은 이름을 넣는 우회도 막아야 하므로, 검사는 키 객체가
아니라 빌더가 기억하는 write-once 이름 집합으로 한다.

### 5.2 포크와 워크플로

- **서브에이전트·스킬 포크** — `EnvironmentRequest.parent` 에 부모 환경을 싣는다. 로컬 제공자는 `parent` 를 그대로
  돌려준다(부모가 격리 브랜치면 그 브랜치 환경이지 베이스가 아니다). 샌드박스 제공자는 **부모와 같은 워크스페이스**
  안의 환경을 돌려주되, 셸은 새로 잡고, 어느 샌드박스(슬롯)에서 돌지는 자기 바인딩 정책으로 정한다 — 기본은 부모와
  같은 샌드박스다. 코어가 약속하는 것은 "포크는 부모의 격리 단위(작업 공간) 밖으로 나가지 않는다"이고, "부모와
  같은 파일 시스템"은 기본 제공자의 성질이지 계약이 아니다. 부모가 사용 불가 환경이면 제공자는 포크도 사용 불가로
  돌려줘야 한다 — 부모를 만들지 못한 이유(주체 거부 등)를 포크가 새 해석으로 우회하면 안 된다.
  `SubagentExecutionEnvironment` 에 부모 `ExecutionEnvironment` 필드를 더한다. 포크의 요청에는 포크 자신의 정의도
  실린다 — `EnvironmentRequest.fork()` 가 서브에이전트 이름과 정의 파일의 `attributes`(점 표기로 펼친
  `Map<String, String>`, 예: `sandbox.slot`)를 담은 `ForkDefinition` 을 준다. 제공자가 포크마다 다른 슬롯을 고르는
  근거가 이것이다. 메인 턴은 `agent()` 의 `getAttributes()` 에서 같은 값을 읽는다. 두 경우를 한 번에 푸는 것이
  `EnvironmentRequest.definitionAttributes()` 다 — 포크면 포크의 것, 아니면 에이전트의 것, 둘 다 없으면 빈 맵. 제공자는
  이것을 읽는다(`agent()` 만 읽으면 모든 포크가 메인 턴의 슬롯에 들어가는데 아무것도 실패하지 않는다). 코어는 속성을
  싣기만 하고 읽지 않는다 — 키 이름은 제공자가 정한다. 따옴표 없는 값은 YAML 1.1 이 먼저 타입을 입히므로(`010` →
  `8`, `on` → `true`) 평범한 텍스트가 아닌 값은 따옴표로 감싼다. 워크플로 단계도 포크이므로 같은 값을 싣는다(EE-42).
  단계의 서브에이전트는 등록된 정의가 아니라 인라인으로 만들어지므로, 속성은 이렇게 채운다 — GraalJS 의
  `agent({...})` 단계는 `agentType` 과 같은 이름으로 등록된 서브에이전트의 속성을 복사하고, 스크립트가 준
  `attributes` 는 등록된 정의가 정하지 않은 키만 더할 수 있다(등록된 키는 고정되어 다른 값을 주면 스크립트가 실패하고,
  키를 지울 수도 없다. 등록되지 않은 `agentType` 에는 고정할 키가 없다 — EE-45). 내장 `Workflow` 도구의
  단계는 역할마다 정해진 이름(`workflow-perspective` · `workflow-synthesizer` · `workflow-candidate` · `workflow-judge` ·
  `workflow-skeptic`)으로 등록된 서브에이전트의 속성을 복사한다. 어느 쪽이든 등록된 정의에서 가져오는 것은 속성뿐이고,
  이름·프롬프트·도구는 단계의 것 그대로다. 속성이 비면 제공자는 위의 기본(부모와 같은 샌드박스)을 따른다. 설계와
  구현이 달라진 점은 [`execution-environment-ee42-workflow-attributes.md`](execution-environment-ee42-workflow-attributes.md)
- **워크플로 격리 브랜치** — 러너는 `parentEnv.isolate(branchKey)` 를 부른다. 비어 있으면(격리를 지원하지 않는
  환경) 브랜치를 격리 없이 돌리지 않고 **실행을 거부**한다. 격리를 요청한 스크립트가 격리 없이 돌면 병렬 브랜치가
  서로의 파일을 덮는다. `isolate()` 가 던지면(격리를 여기서 거절한다 — 사용 불가 환경, 이미 브랜치인 환경) 러너는
  그 메시지를 오류에 싣고 예외를 원인으로 잇는다. 사용 불가 환경은 빈 값이 아니라 자기 원인(제공자 없음, 샌드박스
  다운)을 담은 `ExecutionEnvironmentUnavailableException` 을 던진다
- `SubagentExecutionEnvironment.toolRegistry` 는 부모 레지스트리 그대로다. 브랜치별 레지스트리가 없어진다
- `WorktreeMerge.promote(baseVfs, branchKeys, policy)` 는 지금 베이스 VFS 하나와 브랜치 키 목록을 받아
  `.worktrees/{key}/` 를 스스로 찾아간다. 브랜치 위치를 아는 것이 환경이 되므로, 부모 환경과 브랜치 환경 목록을
  받는 형태로 바뀐다. 동작(브랜치 간 충돌을 먼저 훑고 `Policy` 로 고른 뒤 VFS 복사로 올리는 병합)은 그대로다.
  `promote` 는 파일을 읽거나 쓰기 전에 브랜치의 소속을 확인한다 — 부모 자신, 부모의 파일 시스템을 공유하는 환경,
  두 번 넘긴 같은 브랜치, `isolatedFrom()` 이 다른 부모를 가리키는 브랜치는 `IllegalArgumentException` 이다. 이 검사도
  각 환경의 `fileSystem()` 은 부르므로, 입출력이 전혀 없는 것은 로컬 제공자뿐이다(다른 제공자는 첫 호출에
  프로비저닝할 수 있다, §13). 계보를 밝히지 않는 브랜치는 나머지 검사만으로 받는다. 그다음 두 가지를 먼저 확인하고,
  하나라도 걸리면 아무것도 올리지 않고 멈춘다 — 올릴 곳이 부모의 경로 규칙에 막히는지(부모의 파일 시스템이
  `withPathRules` 로 만든 것일 때만 미리 알 수 있다, `VirtualFileSystems.pathRules`), 올릴 파일의 메타데이터를 읽을 수
  있는지(셸이 만든 심볼릭 링크).
  **병합은 명시적이다** — 러너는 병합하지 않고, 조립 코드가 `promote` 를 부른다(workflow.md §6.3). 그래서 병합 방식은
  SPI 가 아니라 호출자의 선택이다. `promote` 는 파일 시스템만 쓰므로 어떤 환경에서도 동작한다. git worktree 로 격리하는
  환경(샌드박스)은 git 병합을 **자기 모듈의 API** 로 따로 줄 수 있고, 그것을 쓸지는 조립 코드가 고른다. 코어는
  `ExecutionEnvironment` 에 병합 메서드를 두지 않는다

### 5.3 백그라운드 명령

`Bash(run_in_background=true)` 는 작업을 시작하는 순간 `env.shell()` 을 `BackgroundBashManager.start(...)` 에 넘기고,
도는 명령이 그 셸을 붙잡는다(작업 객체는 셸을 필드로 쥐지 않는다 — §15). 작업은 시작한 실행보다, 그리고 **런타임보다**
오래 살 수 있으므로, 캡처한 셸은 실행이 아니라 제공자의 수명에 기대야 한다 — §4.3 에서 셸을 제공자가 소유하는 이유가
여기서도 쓰인다.

**작업 목록은 런타임보다 오래 산다 (EE-7).** `BackgroundBashManager` 는 `Bash` · `BashOutput` · `KillShell` 이 나눠 쓰는
작업 목록이고, 명령을 돌리는 스레드도 이제 여기 있다(도구 인스턴스가 쥔 풀에서 돌면 축출된 런타임의 것에 기대게 된다).
부트스트랩 스택은 매니저를 **하나** 만들어 모든 런타임의 세 도구에 넘긴다 — `OrcaBashToolProvider(BackgroundBashManager)`.
그래서 축출 뒤 다시 만들어진 런타임의 `BashOutput` 이 옛 task id 를 찾는다. 인자 없는 `OrcaBashToolProvider()` 는 전처럼
도구 레지스트리마다 매니저를 만든다(코어만 쓰는 조립의 기본 — EE-56).

- **저장소와 핸들을 가른다.** 누가 시작했고 어느 노드에서 도는가 하는 메타데이터(`BackgroundBashRecord`)는
  `BackgroundBashStore` 에 들어간다 — 인터페이스이고 기본은 `InMemoryBackgroundBashStore` 다. future · 취소 신호 · 출력
  버퍼는 그 노드의 매니저가 메모리에 든다(`BackgroundBashTask`). 프로세스가 그 노드에만 있으므로 옮길 수 없다
- **조회 결과는 셋 중 하나** — 이 노드의 핸들 / 저장소에만 있는 레코드 / 없음. 둘째가 "다른 노드에서 도는 작업"(또는
  이 노드가 재시작으로 잃은 작업) 보고이고, 그 출력을 읽거나 명령을 멈출 수는 없다(EE-53)
- **소유 범위 (EE-58).** 한 목록을 모든 테넌트가 보게 되므로, 작업은 소유자(`BackgroundBashOwner`)를 기록하고 도구는
  자기 호출의 소유자와 **전부 같을 때만** 찾는다. 소유자는 런타임과, 그 안에서 작업을 띄운 호출이 **대신하는 세션**이다 —
  `SESSION_ID`(턴), 없으면 `INVOKING_SESSION_ID`(포크). 세션을 대신하지 않는 실행(스케줄 루틴, 호출자 없는 포크)은 자기
  `EXECUTION_ID` 가 소유자다. 그래서 세션의 턴과 그 세션을 위해 띄운 포크는 서로의 작업을 읽고 멈추고, **같은 런타임의
  다른 세션은 그러지 못한다.** 범위 밖의 task id 는 없는 id 와 같은 "없음" 이다 — 저장소 레코드의 소유자를 "다른 노드"
  판정보다 먼저 비교하므로 어느 노드에서 물어도 같다. task id 는 32비트라 경계가 될 수 없다. 저장소는 소유 필드
  셋(`ownerRuntimeId` · `ownerSessionId` · `ownerExecutionId`)을 그대로 돌려줘야 한다 — 하나라도 잃으면 그 작업은 주인을
  포함해 누구에게도 보이지 않는다
- **보존.** 끝난 지 보존 기간(기본 24시간)이 지난 작업은 다음 `start` 때 레코드와 함께 치운다. 전에는 런타임과 함께
  GC 되었다

**끝낼 수단 (EE-13).** 셸 SPI 에 명령 단위 취소 신호가 있다 — `ExecutionOptions.getCancellation()`
(`ShellCancellation`, 거는 쪽은 `ShellCancellationSource`). `ShellFeature.CANCELLATION` 을 선언한 셸은 신호가 걸리면
명령과 그 명령이 띄운 것 전부를 멈추고 그 `execute` 가 `ShellCancelledException` 을 던지게 한다. 이미 걸린 신호로
불리면 명령을 **띄우지 않는다**. 선언하지 않은 셸은 신호를 무시한다. 매니저는 셸이 선언했을 때만 작업마다 신호를
만들어 옵션에 싣는다. `KillShell(taskId)` 가 그 신호를 건다 — 취소된 작업은 `KILLED` 로 정착하고 죽기 전까지의 출력은
`BashOutput` 으로 읽는다. 취소를 선언하지 않은 셸에서 돈 작업은 `KillShell` 이 오류로 답하고, `Bash` 의 시작 응답이
미리 그렇게 말한다.

**상한.** 모델이 끝내기를 잊은 명령의 안전장치는 `ExecutionEnvironment.backgroundCommandTimeout()` 이다. `BashTool` 은
백그라운드 경로에서 이 값을 timeout 으로 쓰고, 환경이 정하지 않으면 24시간이다. 환경의 값이 **양방향으로** 이긴다
(24시간보다 길어도 된다). 0 이하는 받지 않는다 — 셸이 "무한" 으로 읽는다. 서술자가 아니라 환경에 실은 것은 서술자가
프롬프트에 렌더되고 `equals` 가 프롬프트 캐시에 쓰이기 때문이다. 환경이 정한 값이면 시작 응답이 모델에게 알린다.

**스택이 닫히면 도는 명령을 멈춘다.** `BACKGROUND_COMMANDS` 단계가 `AGENT_RUNTIMES` 뒤(마지막으로 배출되는 실행이 아직
명령을 시작할 수 있다), `AGENT_RESOURCES` 앞(취소는 제공자의 셸을 거쳐 나간다)에 있다. 테넌트의 바인딩은 그보다 먼저
닫히지만 바인딩은 도는 명령을 건드리지 않으므로(§4.3) 순서가 성립한다.

**셸은 지금 백그라운드 명령을 알아볼 수 없다.** `BashTool` 의 `backgroundOptions()` 는 `foregroundOptions()` 에
긴 타임아웃만 준 것이고, 둘 다 같은 `VirtualShell.execute(command, options)` 로 들어간다. 로컬 셸은 명령마다
프로세스를 새로 띄우므로 상관없지만, 한 번에 명령 하나만 도는 지속 셸 세션을 쓰는 환경(샌드박스)은 백그라운드
명령이 세션을 쥐면 그 셸의 다음 명령이 모두 기다린다. 그래서 `ExecutionOptions` 에 `background`(기본 false)를
더하고 `BashTool` 이 백그라운드 경로에서 켠다. 켜진 명령을 어떻게 돌릴지(샌드박스는 세션 cwd 를 넘긴 one-shot)는
셸 구현이 정하고, 로컬 셸은 무시한다.

백그라운드 경로는 셸 호출을 미루므로, 사용 불가 환경(§5.1)은 **시작하기 전에** 확인해 포그라운드와 같은 오류를
돌려준다 — "시작했다"고 보고한 뒤 조회 때 실패하지 않는다. 명령의 notice(§8)는 `BackgroundBashTask` 가 보관했다가
`BashOutput` 의 완료·실패 보고에 한 번 싣는다.

---

## 6. 도구 변경

| 도구 | 지금 | 바뀐 뒤 |
|------|------|--------|
| `Read` | 생성자 VFS | `env.fileSystem()`. 읽은 뒤 `FileStamp` 를 기록 |
| `Write` | 생성자 VFS, 검사 없음 | 기존 파일 덮어쓰기는 stamp 가 있어야 하고 일치해야 한다. 새 파일은 검사 없음 |
| `Edit` | 생성자 VFS, "읽었는가"만 | stamp 일치 검사. 쓴 뒤 stamp 갱신 |
| `Grep` | 생성자 VFS, 파일별 read | `env.contentSearch()` 가 있으면 위임, 없으면 기존 방식 |
| `Bash` | 생성자 셸 | `env.shell()`. 결과의 `notices()` 를 출력 앞에 붙인다(§8) |
| `BashOutput` | 매니저 | 변화 없음(작업이 셸을 기억한다) |
| `WikiIngest` | `VIRTUAL_FILE_SYSTEM` 키 (미배선) | `env.fileSystem()`. 키는 삭제 |
| `ArtifactAwareWriteTool`/`ArtifactAwareEditTool` | 경로를 등록 | `env.durable()` 이 false 면 등록 전에 `controlFileSystem` 의 artifact 영역으로 복사하고 그 경로를 등록(§9.3) |
| `Skill` | VFS `baseDir` 을 `${AIMON_SKILL_DIR}` 로 | `env.stage(skill)` 이 돌려준 경로를 `${AIMON_SKILL_DIR}` 로(§4.4) |
| 스킬 기반 커맨드 (도구 아님) | `${AIMON_SKILL_DIR}` 가 빈 문자열(결함) | `Skill` 과 같은 경로로 `env.stage(skill)`. 커맨드도 실행 안에서 돌므로 그 실행의 환경을 쓴다 |

생성자가 비는 도구들은 `OrcaFileToolProvider` 의 artifact 분기만 남는다. `WorktreeToolEnvironmentFactory` 가
그 분기를 다시 적을 일도 없다.

`OrcaToolProviderContext` 에서 `getFileSystem()` 과 `getShell()` 을 **삭제**하고 `getControlFileSystem()` 을
더한다. 도구 등록 시점에 작업 환경을 붙잡을 수 있는 통로를 남겨 두면, 외부 프로바이더가 그것을 생성자에 넣어
§1.1 을 다시 만든다. `getEnvironment()` 는 §10 이후 `timeZone` 만 남은 값을 돌려주게 되므로 그 결정(§14)을
따른다 — 그 결정은 내려졌고(EE-14), 지금 이 접근자는 `getUserLocale()` 이며 `at.aimon.core.base.UserLocale` 을
돌려준다. 외부 소비자 둘의 영향:

- `OrcaSandboxToolProvider`(aimon-sandbox) — 워크스페이스 샌드박스 설계에서 샌드박스 전용 실행 도구는 모두
  없어진다. 명령과 파일은 코어의 `Bash`·파일 도구가 샌드박스 환경에서 처리한다. 남는 것은 슬롯의 수명을 다루는
  오케스트레이터 도구(`SandboxList`·`SandboxStart`·`SandboxStop`)뿐이고, 명시적으로 허용된 에이전트에게만 등록된다. 이 도구들은 파일 시스템을
  생성자로 받지 않고, 실행마다 `EXECUTION_ENVIRONMENT` 에서 샌드박스 환경의 바인딩을 꺼내 호출자의 워크스페이스를
  안다
- `OrcaBrowserToolProvider`(aimon-browser) — 스크린숏 등 산출 파일은 `env.fileSystem()` 에, artifact 는 §9 경로로.
  그 저장소의 변경이 필요하다

---

## 7. 낡은 쓰기 방지 — `FileStamp`

```
Read(p)            -> stamps.put(p, stamp(p))
Edit(p) / Write(p) -> if exists(p):
                        s = stamps.get(p)
                        if s == null            -> error "Read the file before modifying it"
                        if s != stamp(p)        -> error "File changed since it was read; Read it again"
                      write ...
                      stamps.put(p, stamp(p))
```

`stamp(p)` 는 `VirtualFileSystem.getMetadata(p)` 가 돌려주는 `FileMetadata` 에서 만든다. `size` + `modifiedAt`
을 쓰고, 백엔드가 `etag` 를 주면 그것을 우선한다. 지금 `FileMetadata` 에는 etag 가 없으므로(`customMetadata`
맵뿐이다) **`Optional<String> getEtag()` 를 더하고** GridFS(md5)·S3(ETag) 백엔드가 채운다. 자유 형식 맵의 관례
키로 두지 않는 것은, 계약이 요구하는 값을 타입에 보이게 하려는 것이다.

**VFS 계약에 한 줄을 더한다:** `getMetadata` 는 내용이 바뀌면 `modifiedAt` 또는 `etag` 중 하나가 반드시 바뀌는
값을 돌려줘야 한다. mtime 해상도가 초 단위인 파일 시스템에서 같은 초 안에 크기를 바꾸지 않고 두 번 쓰면 놓칠 수
있다. 그 백엔드는 `etag` 로 내용 해시를 준다.

stamp 맵의 키는 모델이 넘긴 문자열이 아니라 **환경의 파일 시스템이 정규화한 경로**다. `a.txt` 로 읽고
`./a.txt` 나 절대 경로로 고치는 흔한 경우가 "읽지 않았다"로 거부되면 안 된다.

이 검사가 잡는 것: 다른 실행이 파일 도구로 바꾼 경우, **셸이 바꾼 경우**(`sed -i`, `git checkout`, 포매터),
사람이 바꾼 경우. 실제 파일 상태와 비교하기 때문이다. 잡지 못하는 것: 검사와 쓰기 사이의 경합(창은 한 호출
안이다). 그것을 막으려면 조건부 쓰기(`write(path, content, expectedStamp)`)가 필요하다. 로컬 파일 시스템에서는
원자적으로 구현할 수 없어 SPI 에 올리지 않았다(§12).

`ReadTool.READ_FILES_KEY`(`ToolContextKey<Set<String>>`)는 삭제하고 `FILE_STAMPS_KEY`(`Map<String, FileStamp>`,
동시 접근 안전)로 바꾼다. 실행 단위 값이라는 점은 같다 — 두 실행기가 `createToolContext()` 에서 실행당 한 번
만든다.

실행 단위라는 것이 행동 변화를 하나 만든다. 지금은 `Edit` 만 "이번 실행에서 읽었는가"를 보지만, 바뀐 뒤에는 **기존
파일을 덮어쓰는 `Write` 도** 같은 실행 안에서 `Read` 를 먼저 요구한다. 대화형 세션에서 이전 턴에 읽은 파일을
이번 턴에 덮어쓰려면 다시 읽어야 하고, 포크도 부모의 stamp 를 물려받지 않는다. 의도한 것이다 — 턴 사이에는 사람과
셸이 파일을 바꿀 시간이 가장 길다. 에러 문구가 다음 행동("Read it again")을 알려 주므로 모델은 한 번의 왕복으로
회복한다.

---

## 8. 셸 결과의 알림

`ShellCommandResult` 에 `List<String> notices()`(기본 빈 목록)를 더한다. 환경이 모델에게 알려야 하는 사실 —
"셸 세션이 새로 열려 cwd·환경 변수가 초기화되었다", "환경이 재생성되어 작업 디렉터리가 비었다" — 을 싣는다.
`BashTool` 은 notice 가 있으면 `[environment] …` 줄로 출력 앞에 붙인다. 명령이 timeout 되거나 실패해도 환경은
바뀌었을 수 있으므로 `ShellExecutionException`(과 `ShellTimeoutException`)도 같은 `notices()` 를 싣고, `BashTool` 은
그 오류 앞에도 notice 를 붙인다. 백그라운드 명령은 `BashOutput` 이 싣는다(§5.3). 어느 경우든 notice 는 `filter`
정규식이나 출력 잘림의 대상이 아니다.

stderr 에 섞지 않는다. stderr 는 명령이 낸 것이고, 모델은 그것을 명령의 오류로 해석한다. 로컬 셸은 notice 를
만들 일이 없다.

---

## 9. 제어 저장소와 작업 환경의 분리

### 9.1 경계

| | 제어 저장소 (`controlFileSystem`) | 작업 환경 (`ExecutionEnvironment`) |
|---|---|---|
| 담는 것 | 에이전트·스킬·커맨드 정의, 태스크 출력, 세션 스냅숏, 보관된 artifact | 모델이 읽고 쓰는 코드·데이터, 명령 실행 |
| 수명 | Application — 영속 | 제공자가 정한다. 샌드박스면 일시적일 수 있다 |
| 누가 쓰나 | 프레임워크 | 모델(도구 경유) |
| 모델에게 보이나 | 아니다. 스킬 파일은 `stage()` 로만 넘어간다 | 그렇다 |

`OrcaAgentRuntimeFactory` 는 `controlFileSystem` 과 `ExecutionEnvironmentProvider` 를 따로 받는다. §1.3 표의
행은 이렇게 나뉜다.

- **작업 환경으로** — 파일 도구, 런타임이 들고 있던 VFS(→ git·디렉터리 요약 컨텍스트 제공자), 프롬프트의 작업
  디렉터리. 셋 다 "모델이 지금 어디서 일하는가"를 말한다
- **사라짐** — 워크플로 격리(`isolate()` 로 대체, §5.2)
- **제어 저장소로** — 나머지 전부(서브에이전트·스킬·커맨드 정의, 태스크 출력·결과, 세션 스냅숏)

### 9.2 로컬 어셈블리의 기본값

CLI 처럼 "사용자 프로젝트 디렉터리에서 돈다"는 배치에서는 두 저장소를 같은 디스크에 둘 수 있다. 다만 루트를
가른다 — 제어 저장소는 `{project}/.aimon/`, 작업 환경은 `{project}/`. 작업 환경의 파일 도구는 `.aimon/` 를
보지 **않도록** 로컬 제공자가 막는다. 사용자가 에이전트에게 스킬을 고치게 하고 싶다면 어셈블리 설정으로 명시적으로
연다. 기본이 닫힌 쪽이다.

이것을 막을 장치는 아직 없다. `ScopedVirtualFileSystem` 은 접두어를 붙이고 `../` 탈출을 거부할 뿐 제외 규칙이
없다. 경로 접두어별로 `DENY`/`READ_ONLY` 를 거는 VFS 래퍼를 `at.aimon.core.filesystem.impl` 에 새로 둔다. 로컬
제공자가 거는 기본 규칙은 둘이다 — `.aimon/` 은 `DENY`, 스테이징 영역 `.aimon-staged/` 는 `READ_ONLY`(§4.4).
스테이징 영역을 `.aimon/` 아래에 두지 않는 것은 이 두 규칙이 겹치지 않게 하려는 것이다. `.aimon-staged/` 는 사본일
뿐이므로 git 에 들어가지 않아야 한다 — 로컬 제공자가 첫 복사 때 그 안에 `*` 한 줄짜리 `.gitignore` 를 직접 쓴다(EE-4,
2026-10-05). 프로젝트 초기화 단계는 필요 없다.

격리 브랜치(§4.2)는 이 규칙을 **브랜치 루트 기준으로 다시 건다** — `.aimon` 은 `.worktrees/{key}/.aimon` 이
된다. 규칙은 부모의 것을 옮긴 것이지 새로 정한 것이 아니므로, 규칙을 비운 어셈블리의 브랜치에도 규칙이 없다. 이
규칙 층은 `ScopedVirtualFileSystem` **아래**에 둔다. 위에 두면 스코프의 작업 디렉터리(`"."`) 기준으로 경로를
풀어서 절대 경로가 규칙을 비껴간다. 아래에서는 브랜치 경로의 표기 — 브랜치 기준 상대 경로, 워크스페이스나 브랜치
루트 아래의 절대 경로(`./`·`//` 가 섞이거나 브랜치 키의 대소문자가 달라도) — 가 모두 `.worktrees/{key}/` 아래의 한
위임 경로로 줄어든 뒤다(나머지 세그먼트의 대소문자는 규칙이 무시한다). 줄지 않는 것은 브랜치 안에서 `.worktrees/`
를 가리키는 경로다 — `.worktrees/other/x` 나 상대 경로로 쓴 `.worktrees/{key}/x` 는 브랜치 안에 중첩되어 규칙 밖에
놓이고, 병합이 그 디렉터리로 올린다(백로그 EE-46). 브랜치가 공유하는 스테이징 접두어도 대소문자를 무시하고 맞추므로,
`.AIMON-STAGED/x` 쓰기는 브랜치 안에 떨어지지 않고 부모의 `READ_ONLY` 규칙에 걸린다. 셸이 브랜치 루트에 만든
스테이징 디렉터리는 어떤 표기로도 닿지 않으므로(모두 부모의 것으로 간다) 브랜치 목록에서 빠지고, 병합이 올리지
않는다. 이것은 부모가 스테이징 영역을 지킨다는 전제 위에 있다 — 그 규칙을 뺀 어셈블리의 브랜치는 루트 스테이징
영역에 그대로 쓴다.

셸은 `.aimon/` 을 여전히 읽을 수 있다. 로컬에는 셸 경로의 격리가 없기 때문이다(§2 비목표). 이 설정이 막는 것은
"모델이 파일 도구로 무심코 자기 정의를 고치는 일"이지, 악의적인 셸 명령이 아니다. 그것을 막으려면 샌드박스
제공자를 쓴다.

### 9.3 artifact

`ArtifactCollector` 에 등록되는 경로는 사용자가 나중에 내려받는다. 환경이 `durable() == false` 이면 그 경로는
실행 뒤 사라질 수 있으므로, artifact-aware 도구는 등록 전에 파일을
`controlFileSystem` 의 `/artifacts/{executionId}/{fileName}` 로 복사하고 **그 경로**를 등록한다. 로컬 격리
브랜치도 여기에 해당한다(§4.2).

그러면 `ArtifactMetadata.getPath()` 가 가리키는 저장소가 둘이 된다 — durable 환경이면 작업 환경, 아니면 제어
저장소. 다운로드 엔드포인트(애플리케이션 계층)가 어느 쪽을 열지 알아야 하므로 `FileArtifact` 에 저장소 구분
(`WORKSPACE` / `CONTROL`)을 더한다. 늘 복사해서 하나로 맞추는 쪽은 §14 에 남긴다.

복사 상한을 둘 설정 객체는 아직 없다. 지금 artifact 설정은 `OrcaFileToolProvider(boolean artifactEnabled)`
하나뿐이므로, 3단계 PR 에서 그 불리언을 설정 객체로 바꾸면서 상한을 함께 넣는다. 기본값은 파일당 50MB · 실행당
총 100MB 다(샌드박스 쪽 옛 `TarSecurityPolicy` 의 값). 넘으면 등록하지 않고 도구 결과에 그 사실을 적는다 — 쓰기
자체는 성공했으므로 에러가 아니다.

복사 경로와 상한의 정본은 이 절이다. 복사하는 것이 코어의 artifact-aware 도구이기 때문이다. 제공자는 `durable()`
만 정직하게 답하면 되고, 자기 쪽 artifact 경로나 전송 상한을 따로 두지 않는다(§13).

---

## 10. 프롬프트의 환경 서술

`SystemPromptRenderer` 와 `EnvironmentContextProvider` 는 `Environment` 대신 **그 실행의**
`EnvironmentDescriptor` 를 렌더한다. `Environment` 의 `platform`/`osVersion` 필드와
`Environment.createWithWorkingDirectory(fileSystem.getWorkingDirectory())` 호출은 삭제한다. `timeZone` 은 환경이
아니라 사용자·애플리케이션의 속성이므로 `Environment` 에 남긴다(→ 이름을 바꿀지는 §14).

이 문단과 아래 문단의 `Environment` 는 그 뒤 **없어졌다**(EE-14). `timeZone` 은 `at.aimon.core.base.UserLocale` 로 옮겨
갔고, 훅 컨텍스트 · 도구 프로바이더 컨텍스트 · 런타임의 접근자는 `getUserLocale()`, `ToolContext` 의 키는
`ToolContextKeys.USER_LOCALE`(`"userLocale"`)이다. 옮긴 것은 이름과 위치뿐이다 — 프롬프트는 전에도 지금도 시간대를 싣지
않고, `timeZone` 을 읽는 운영 코드는 없다(백로그 EE-60). 옛 이름과 새 이름의 대응은
[`../../migration/rename-maps.md`](../../migration/rename-maps.md), 설계는
[`execution-environment-ee14-user-locale.md`](execution-environment-ee14-user-locale.md) 에 있다.

`Environment` 는 프롬프트 밖에서도 읽힌다 — 훅 컨텍스트(`SingleToolInvoker`·`DefaultCompactionEngine` 이
`HookContext` 에 싣는다), `OrcaSkillToolProvider`, `OrcaSystemCommandProvider`. 이들이 `platform`/`osVersion`/
`workingDirectory` 를 쓰는지 2단계 PR 에서 전수 확인하고, 쓰는 곳은 실행의 서술자를 받게 바꾼다. 특히 훅은 "명령이
어디서 도는가"를 알아야 하는 소비자라 호스트 값을 계속 주면 §1.4 가 훅 쪽에서 재발한다.

훅이 받는 것은 서술자만이 아니라 **실행 환경 자체**다 — `HookContext.getExecutionEnvironment()`. 실행 안에서 발화하는 열
이벤트(`preTool`·`postTool`·`permissionRequest`·`permissionDenied`·`onStart`·`onStop`·`subagentStart`·`subagentStop`·
`preCompact`·`postCompact`)의 컨텍스트가 그 실행의 환경을 싣고, `getEnvironmentDescriptor()` 는 거기서 파생된다(컨텍스트는
환경 하나만 든다 — 출처가 둘이 되지 않게). 실행 밖에서 발화하는 셋(`onSessionStart`·`onSessionEnd`·`onConfigReload`)은
비어 있는 것이 정답이고, 그 분류의 단일 출처는 `HookEventType.firesInsideExecution()` 이다. 사용 불가 환경은 빈 값으로
바꾸지 않고 그대로 싣는다(§15). **스킬 파일이 선언한 훅의 셸 액션은 이 환경의 셸에서 돈다** — 실행기
(`DefaultShellActionExecutor`)는 셸을 쥐지 않고 발화 시점의 컨텍스트에서 얻는다. 컨텍스트에 환경이 없으면 명령을 돌리지
않는다(호스트로 되돌아가지 않는다). 설계와 이벤트별 발화 지점은
[`execution-environment-ee9-ee12-hook-environment.md`](execution-environment-ee9-ee12-hook-environment.md) 에 있다.

**훅의 명령은 모델의 셸 세션 밖에서 돈다.** 훅의 셸 액션은 `Bash` 와 같은 `VirtualShell.execute(command, options)` 로
들어가므로, 셸은 옵션 없이는 그것을 모델의 도구 호출과 구별할 수 없다. 그래서 `ExecutionOptions` 에 `hook`(기본 false)을
두고, 훅의 셸 액션을 돌리는 공용 사다리(`ShellActionRunner`)가 켠다 — 스킬 선언 훅(`DefaultShellActionExecutor`)과
`hooks.json` 훅(`HostShellActionExecutor`)이 모두 그 사다리를 지난다. 세션별 상태를 지닌 셸(`cd`·`export` 가 명령 사이에
남고 세션마다 한 번에 명령 하나만 도는 샌드박스)은 켜진 명령을 그 세션 밖에서 돌린다: 세션 잠금을 잡지 않고 상태를 저장하지
않는다. 세션의 현재 상태에서 시작하는 것은 된다. 그렇지 않으면 병렬 도구 호출의 `preTool` 셸 가드가 모델의 명령을 기다리다
"셸이 바쁘다" 로 실패해 **거부**로 읽히고(EE-51), 훅의 `cd`·`export` 가 모델의 셸 상태에 남는다. 명령마다 프로세스를 새로
띄우고 상태를 남기지 않는 셸(`LocalShell`)은 무시한다.

**명령을 돌리지 못한 가드는 막는다 (EE-51).** 환경이 없거나 사용 불가일 때, 그리고 timeout · 셸 실패로 종료 코드를 얻지
못했을 때, 실행기는 원인을 실어 보고하고(`ShellHookOutcome.notRun(cause, detail)`) 거부 채널이 있는 네 이벤트(`preTool` ·
`onStart` · `preCompact` · `permissionRequest`)의 훅은 그것을 **거부**로 읽는다 — 사유에 원인이 실린다. 훅이 `failOpen: true`
를 선언했으면 통과시킨다. 나머지 이벤트는 전처럼 WARN 후 진행한다. 그래서 환경 제공자가 실패한 실행에서는 셸 가드가 걸린
도구가 환경을 쓰지 않는 것까지 막힌다 — 가드가 꺼진 채 실행되던 것의 반대쪽이다. `onStart` 의 거부는 메인 실행에서는 턴을,
포크에서는 **그 포크의 시작**을 막는다(EE-70) — 포크의 환경 제공자가 실패하면 스킬의 `onStart` 셸 가드는 포크를 돌리지 않는다.
설계는 [`execution-environment-ee70-ee71-fail-closed.md`](execution-environment-ee70-ee71-fail-closed.md) 에 있다.

**스킬 훅은 그 스킬의 포크에서만 발화한다 (EE-49).** 스킬이 선언한 훅은 런타임의 `HookRegistry` 에 등록되지 않고, 포크가
디스패치하는 레지스트리 위에 얹힌다(`SkillScopedHookRegistry`). 같은 에이전트의 다른 세션이 낸 이벤트는 그 훅을 치지
않으므로, 세션마다 환경이 다른 제공자에서도 한 세션의 스킬 훅이 다른 세션의 환경에서 도는 일이 없다. 실행은 자기가
디스패치하는 레지스트리를 `ToolContextKeys.HOOK_REGISTRY`(write-once)로 싣고, 포크를 띄우는 도구는 그 값을 포크에 넘긴다.
두 변경의 설계는
[`execution-environment-ee49-ee51-ee58-isolation-boundary.md`](execution-environment-ee49-ee51-ee58-isolation-boundary.md) 에
있다.

프롬프트 캐시에 대한 영향: 서술자는 환경마다 한 번 정해지고 한 세션 안에서는 바뀌지 않는다(샌드박스가 재생성되어도
이미지가 같으면 같다). 따라서 세션 단위 캐시 접두부는 안정적이다. 서술자가 에이전트 단위로 캐시되던 자리
(`AgentEnvironmentSnapshot`)에서 빠져 실행 단위 조립으로 옮겨 가는 것이 이 변경의 비용이다.

---

## 11. 이행 순서

하위 호환을 두지 않지만 리뷰 가능한 크기로 자른다. 각 PR 이 끝나면 빌드가 녹색이다.

1. **SPI 와 로컬 제공자** — `at.aimon.core.environment` 패키지, `LocalExecutionEnvironmentProvider`,
   `EXECUTION_ENVIRONMENT` 키(write-once), 실행기 두 곳의 조립. 옛 `VirtualExecutionEnvironment`·
   `LocalExecutionEnvironment`(`agent.impl.orca.environment`) 삭제와 ArchUnit 허용 패키지 이동(§4.2).
   도구는 아직 생성자를 쓴다
2. **도구 전환** — 파일 도구 · `Bash` · `WikiIngest` · `Skill` 이 컨텍스트에서 환경을 읽는다. 컨텍스트 제공자도
   `ContextAssemblyRequest` 로 실행의 환경을 받는다(§5.1). `ExecutionOptions.background`(§5.3).
   `OrcaToolProviderContext.getFileSystem()/getShell()` 삭제, `withShell()`·`ownedShell` 삭제, `VIRTUAL_FILE_SYSTEM`
   키 삭제
3. **제어 저장소 분리** — `controlFileSystem` 도입, 로컬 기본 루트 분리와 경로 규칙 래퍼(§9.2), 스테이징
   복사(§4.4), artifact 복사와 `FileArtifact` 저장소 구분(§9.3)
4. **격리** — `isolate()`, 워크플로 러너 전환, `WorktreeEnvironmentFactory`·`WorktreeToolEnvironmentFactory`
   삭제, `WorktreeMerge` 시그니처 변경
5. **stamp · 내용 검색 · 알림 · 서술자** — §7(`FileMetadata.getEtag()` 와 백엔드 모듈 포함), `ContentSearch`,
   §8, §10. 서로 독립이라 순서가 없다

CLI(`AgentSetupFactory`) · `aimon-bootstrap`(`AimonStack`) · `aimon-spring-boot-starter` 는 2·3 단계에서 같이 바뀐다.
스타터의 새 속성 이름은 3단계 PR 에서 정한다.

---

## 12. 기각한 대안

| 대안 | 왜 기각했나 |
|------|------------|
| 생성자 주입을 두고 실행마다 레지스트리를 복제 | §1.2 의 방식을 모든 실행으로 넓히는 것이다. 도구 N개를 실행마다 만들고, 도구 변형마다 분기를 다시 적는다 |
| `ToolContextEnricher` 가 환경 키를 덮어씀 | enricher 순서에 정확성이 걸린다. 덮어쓰는 enricher 가 없거나 실패하면 **호스트 환경이 그대로 남는다**(열린 쪽 실패). 명시적 제공자 하나가 답하게 하면 실패의 모양이 하나다 |
| `ThreadLocal` 로 현재 환경 전달 | 병렬 도구 디스패치와 백그라운드 명령이 스레드를 바꾼다. 값이 어디서 왔는지 호출 그래프에 보이지 않는다 |
| 경로 접두어로 VFS 를 고르는 합성 라우터 | 파일 도구만 라우팅되고 셸은 따라오지 않는다. 파일 도구와 셸이 같은 것을 본다는 불변식을 깨는 쪽으로 쉽게 쓰인다 |
| `VirtualExecutionEnvironment`(impl 패키지)를 그대로 공개 | impl 패키지는 ArchUnit 이 외부 import 를 막는다. 서술자·검색·스테이징·격리가 빠져 있어 어차피 바뀐다 |
| stamp 로 항상 내용 해시 | 모든 `Read`/`Edit` 가 파일 전체를 한 번 더 해시한다. 메타데이터로 충분한 백엔드가 대부분이고, 부족한 백엔드는 `etag` 로 해시를 준다 |
| VFS 에 조건부 쓰기(`expectedStamp`) 추가 | 로컬 파일 시스템에서 원자적으로 구현할 수 없다. 원자적이지 않은 조건부 쓰기는 검사+쓰기와 같은 창을 가지면서 더 강한 보장처럼 읽힌다 |
| 제어 평면을 그대로 두고 경로 규약으로 보호 | 지금 상태다(§1.3). 규약은 샌드박스로 옮기는 순간 무너진다 |
| 격리 미지원 환경에서 브랜치를 비격리로 실행 | 병렬 브랜치가 서로의 파일을 덮는다. 격리를 요청했다는 사실이 정확성 조건이다 |
| `ExecutionEnvironment` 를 `Closeable` 로 | 실행마다 닫히면 공유 자원(셸, 원격 연결)을 실행마다 만들어야 한다. 수명은 제공자에 둔다 |

---

## 13. 샌드박스 제공자와의 계약

aimon-sandbox 는 `ExecutionEnvironmentProvider` 를 구현한다. 이 문서가 그쪽에 약속하는 것과 요구하는 것:

| 코어가 보장 | 샌드박스가 보장 |
|------------|---------------|
| 실행마다 `resolve()` 를 한 번, 도구 호출 전에 부른다 | `resolve()` 는 원격 자원을 만들지 않는다(게으른 프로비저닝) — 파일·셸을 처음 쓸 때 만든다 |
| 포크에 `parent` 를 싣는다 | `parent` 와 같은 워크스페이스, 새 셸을 돌려준다. 슬롯은 바인딩 정책이 정하고 기본은 부모와 같은 샌드박스다. 부모가 사용 불가면 포크도 사용 불가다(§5.2) |
| 워크플로 브랜치는 `isolate()` 로만 만든다. 병합은 하지 않는다 — 조립 코드가 고른다(§5.2) | `isolate()` 는 git worktree 를 쓴다. 파일 복사 병합(`WorktreeMerge.promote`)이 그 환경에서도 동작해야 하고, git 병합은 자기 모듈 API 로 따로 준다 |
| `durable() == false` 면 artifact 를 복사한다 | `/workspace` 에 대해 `durable() == false` 를 돌려준다 |
| `notices()` 를 모델에게 보인다 | 셸 세션·샌드박스 재생성 시 notice 를 싣는다 |
| 백그라운드 명령에 `ExecutionOptions.background` 를 켠다(§5.3) | 켜진 명령은 지속 셸 세션을 쥐지 않는다 |
| 훅의 셸 액션(스킬 선언 훅과 `hooks.json` 훅)에 `ExecutionOptions.hook` 을 켠다(§10) | 켜진 명령은 지속 셸 세션 밖에서 돈다 — 세션 잠금을 잡지 않고 `cd`·`export` 를 세션에 저장하지 않는다. 세션의 현재 상태에서 시작해도 된다. 옵션을 파생하는 래퍼는 플래그를 넘긴다 |
| 셸이 `ShellFeature.CANCELLATION` 을 선언하면 백그라운드 명령마다 `ExecutionOptions.getCancellation()` 에 신호를 싣고, `KillShell` 과 스택 종료가 그 신호를 건다(§5.3) | 선언했다면 신호가 걸릴 때 원격 명령과 그 명령이 띄운 것을 멈추고 그 `execute` 가 `ShellCancelledException` 을 던진다(멈춤을 요청만 하고 돌아와도 된다). 이미 걸린 신호면 명령을 띄우지 않는다. 옵션을 파생하는 래퍼는 신호를 넘긴다. 선언하지 않으면 `KillShell` 은 오류로 답한다 |
| 백그라운드 명령의 timeout 으로 `backgroundCommandTimeout()` 을 쓴다. 비어 있으면 24시간(§5.3) | 도는 명령이 슬롯을 깨워 두는 배치라면 감당할 수 있는 상한을 돌려준다. 0 이하는 무시된다 |
| 런타임을 만들 때마다 `bindRuntime(id)` 를 부르고, 그 런타임이 사라질 때 핸들을 닫는다. 제공자 자체는 스택이 끝날 때 닫는다(§4.3) | 런타임별 자원을 쥔다면 `bindRuntime` 으로 통지를 받는다. 핸들이 닫혀도 **도는 명령은 멈추지 않고**, 같은 id 의 다른 바인딩이 쓰는 것은 놓지 않는다. 바인딩되지 않은 id 의 `resolve` 도 답한다 |
| artifact 복사 경로와 상한을 정한다(§9.3) | 복사 대상 경로를 따로 정하지 않는다 — 코어의 artifact 도구가 복사한다 |
| 스킬을 렌더하는 모든 경로에서 `stage()` 를 거친다(§4.4) | `stage()` 는 마커를 대상에서 확인하고, 재생성 뒤에는 다시 복사한다. `stagingRoot` 는 git worktree 밖이며 `isolate()` 파생 환경과 공유한다 |
| 서술자를 프롬프트에 렌더한다 — `resolve()` 직후, 첫 도구 호출 전에 | 서술자는 이미지의 실제 platform·OS 다. 원격 자원 없이 알 수 있어야 하므로 설정(프로파일)에 선언한 값이고, 프로비저닝 때 실제 이미지와 대조한다. 같은 세션에서는 재생성을 넘어 같은 값을 돌려준다(프롬프트 캐시, §10) |
| 스테이징 영역을 파일 도구에 읽기 전용으로 둔다(§4.4) — 경로 규칙으로. 로컬 제공자와 외부 제공자가 같은 공개 팩토리 `VirtualFileSystems.withPathRules` 를 쓴다 | 샌드박스의 스테이징 영역도 파일 도구에 읽기 전용이다. 셸은 쓸 수 있다 |
| `WorktreeMerge.promote` 는 부모 자신·중복·부모와 파일 시스템을 공유하는 브랜치를 거부하고, `isolatedFrom()` 이 밝힌 계보가 다르면 거부한다(§5.2) | (권장) `isolate()` 가 만든 브랜치는 `isolatedFrom()` 으로 부모를 밝히고, 부모의 경로 규칙을 브랜치 루트 기준으로 `VirtualFileSystems.withPathRules` 로 건다(§9.2). 사용 불가이거나 이미 브랜치여서 격리를 거절할 때는 빈 값이 아니라 이유를 담은 예외를 던진다 |
| `resolve()` 실패 시 호스트로 되돌아가지 않는다 | 실패를 예외로 알린다 |
| 스킬 선언 훅의 셸 액션을 그 실행의 `shell().execute` 로 돌린다 — `ExecutionOptions` 에 `timeout`·`environment`(`AIMON_*` 변수)·`stdin`(JSON 페이로드)을 싣는다(§10) | 셸이 `environment` 와 `stdin` 옵션을 받는다. 받지 못하면 그 훅은 돌지 않은 것으로 처리된다 — 거부 채널이 있는 이벤트(`preTool` · `onStart` · `preCompact` · `permissionRequest`)에서는 `failOpen` 이 아닌 한 **거부**이고, 나머지는 WARN 후 진행이다. `shell()` · `execute` 가 사용 불가 예외를 던질 때도 같다(§10) |
| 백그라운드 작업을 그것을 띄운 세션(또는 세션 없는 실행)에만 보인다. 레코드에 소유 필드 셋을 싣는다(§5.3) | `BackgroundBashStore` 를 구현한다면 소유 필드 셋을 그대로 저장하고 돌려준다 |
| 프롬프트 조립이 `fileSystem()` 을 읽을 수 있다(옵트인 컨텍스트 제공자, §5.1) | 그 읽기가 프로비저닝을 일으킨다는 것을 문서에 밝힌다 |

워크스페이스 샌드박스 설계 문서의 §7 이 이 표를 샌드박스 쪽 구현으로 풀어 적는다.

---

## 14. 열린 질문

> 이 절은 설계 시점의 기록이다. 다섯 질문 가운데 셋 — `Environment` 의 남은 필드, 스킬 선언 훅의 셸, 백그라운드 명령을
> 끝낼 수단 — 은 2026-09-29 에 결정되었고, 결정문과 착수 범위는 백로그의 EE-14 · EE-12 · EE-13 에 있다
> ([`execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md)). 그중 **스킬 선언 훅의 셸은
> 2026-10-03 에 닫혔다**(EE-12, 선행 조건 EE-9 와 함께) — 아래 불릿의 "`AimonStackBuilder` 가 전용 `skillHookShell` 로
> 돌린다" 는 더는 사실이 아니고, 지금의 동작은 §10 에 있다. 나머지 둘(artifact 를 늘 복사할지, `contentSearch` 결과
> 형식)은 아직 열려 있다. **백그라운드 명령을 끝낼 수단도 2026-10-03 에 닫혔다**(EE-13, 작업 목록의 수명을 다룬 EE-7 과
> 함께) — 아래 불릿의 "끝내는 도구가 없다" 는 더는 사실이 아니고, 지금의 동작은 §5.3 에 있다. **`Environment` 의 남은 필드도
> 2026-10-03 에 닫혔다**(EE-14) — 아래 불릿의 "옮기고 없앨지" 는 더는 질문이 아니다. `Environment` 는 없어졌고 `timeZone` 은
> `at.aimon.core.base.UserLocale` 에 있다. 지금의 이름은 §10 에, 설계는
> [`execution-environment-ee14-user-locale.md`](execution-environment-ee14-user-locale.md) 에 있다.

- **`Environment` 의 남은 필드** — `platform`/`osVersion`/`workingDirectory` 가 서술자로 가면 `timeZone` 만 남는다.
  `UserLocale` 같은 이름으로 옮기고 `Environment` 를 없앨지
- **스킬 선언 훅의 셸 액션**(`at.aimon.core.skill.hook.declarative.ShellActionExecutor`) — 운영자 설정이 아니라
  **스킬 파일이 선언한** 훅이다. `AimonStackBuilder` 가 전용 `skillHookShell`(호스트 `LocalShell`)로 돌린다. 그래서
  샌드박스를 쓰면 같은 스킬의 스크립트가 `Bash` 로는 샌드박스에서, 훅으로는 호스트에서 돈다 — 스킬 작성자가 호스트
  셸을 얻는 통로이기도 하다. 훅도 실행 환경의 셸로 보낼지(그러면 훅 실행 시점에 환경이 있어야 한다), 호스트에 두고
  신뢰된 스킬에만 허용할지는 샌드박스 쪽 결정과 함께 정한다
- **artifact 를 늘 복사할지** — §9.3 은 비영속 환경에서만 복사하고 `FileArtifact` 에 저장소 구분을 둔다. 늘
  복사하면 구분이 필요 없고 다운로드 엔드포인트가 제어 저장소 하나만 본다. 대가는 로컬에서 같은 파일이 두 벌
  생기는 것이다
- **백그라운드 명령을 끝낼 수단** — `BashTool` 의 백그라운드 명령은 상한이 24시간이고(`BACKGROUND_TIMEOUT_MS`)
  그것을 끝내는 도구가 없다. 로컬에서는 프로세스 하나가 남는 정도지만, 샌드박스에서는 도는 명령이 idle 판정을
  막아 슬롯을 하루 동안 깨워 둔다(워크스페이스 샌드박스 설계 §20). 끝내는 도구를 코어에 둘지, 환경이 상한을
  정하게 할지는 샌드박스 쪽 결정과 함께 정한다
- **`contentSearch` 결과 형식** — `rg --json` 을 그대로 파싱할지, 셸 독립적인 값으로 정규화할지. 후자가 SPI 로 맞지만
  구현이 둘(로컬 `rg`, 샌드박스 `rg`)뿐인 동안은 파서 공유로 충분할 수 있다

---

## 15. 하지 말 것

- **도구 생성자에 파일 시스템이나 셸을 넣지 말 것.** 실행마다 `EXECUTION_ENVIRONMENT` 에서 꺼낸다(§6)
- **환경을 얻지 못했을 때 기본 환경으로 되돌아가지 말 것.** `UnavailableExecutionEnvironment` 가 에러를 낸다(§5.1)
- **enricher 에서 `EXECUTION_ENVIRONMENT` 를 덮어쓰지 말 것.** write-once 이며 제공자만 쓴다(§5.1)
- **실행이 끝날 때 환경의 셸·파일 시스템을 닫지 말 것.** 제공자 소유다(§4.3)
- **훅 실행기가 셸을 생성자로 쥐지 말 것.** 스킬 선언 훅의 셸 액션은 발화 시점의 `HookContext.getExecutionEnvironment()`
  에서 셸을 얻는다(§10). 스킬을 파싱할 때 묶은 셸은 호스트의 것이다. 예외는 운영자가 쓴 `hooks.json` 전용
  `HostShellActionExecutor` 하나이고, 그것을 스킬 파서에 넘기면 안 된다. `PackageDependencyArchitectureTest.skillHooksHoldNoShell`
  이 강제한다
- **스킬 훅을 런타임의 `HookRegistry` 에 등록하지 말 것.** 그 레지스트리는 같은 에이전트의 모든 세션이 디스패치한다.
  스킬 훅은 포크가 받는 레지스트리에 얹는다(§10). 포크를 띄우는 코드는 생성자에서 받은 레지스트리가 아니라
  `HookRegistryAccess.of(toolContext)` 를 먼저 쓴다 — 아니면 스킬의 가드가 그 하위 트리에서 조용히 꺼진다
- **종료 코드를 얻지 못한 셸 훅을 "통과" 로 보고하지 말 것.** `ShellActionExecutor` 는 원인을 실어 `notRun` 으로
  보고하고, 통과시킬지는 훅의 선언(`failOpen`)이 정한다(§10)
- **런타임이 사라질 때 제공자를 닫지 말 것.** 닫는 것은 그 런타임의 `RuntimeBinding` 이다. 제공자는 다른 런타임과, 사라진
  런타임이 띄워 둔 백그라운드 명령이 계속 쓴다(§4.3)
- **`RuntimeBinding.close()` 에서 도는 명령을 멈추지 말 것.** 축출은 종료 요청이 아니다. 멈추는 것은 `KillShell`, 상한,
  스택 종료 셋뿐이다(§5.3)
- **옵션을 파생하는 셸 래퍼에서 취소 신호를 떨구지 말 것.** `ExecutionOptions.toBuilder()` 가 신호를 — `background`·`hook`
  플래그와 함께 — 넘긴다. 새 옵션을 빌더로 처음부터 만들면 취소와 두 플래그가 **조용히** 사라진다(§5.3, §10)
- **백그라운드 작업이 셸을 필드로 쥐지 말 것.** 셸은 `BackgroundBashManager.start(...)` 의 인자로만 받고 도는 명령이
  붙잡는다(§5.3). `toolsHoldNoFileSystemOrShellFields` 가 강제한다
- **제어 저장소를 파일 도구에 노출하지 말 것.** 스킬 파일은 `stage()` 를 거친다(§9)
- **"이미 스테이징했다"를 제공자 메모리만으로 판단하지 말 것.** 대상의 마커를 본다. 메모리는 마커가 있는 사본을
  다시 해시하지 않기 위한 것이다(§4.4, EE-37)
- **`${AIMON_SKILL_DIR}` 를 `stage()` 밖에서 채우지 말 것.** 커맨드·포크 경로도 같다(§4.4)
- **`isolate()` 가 비었거나 던졌을 때 격리 없이 브랜치를 돌리지 말 것**(§5.2). 던진 이유는 버리지 말고 싣는다
- **비영속 환경의 경로를 artifact 로 등록하지 말 것.** 복사 후 제어 저장소 경로를 등록한다(§9.3)

---

## 관련 문서

- [워크스페이스 샌드박스](https://github.com/kangwoo/aimon-sandbox/blob/main/docs/design/workspace-sandbox.md) — 이 SPI 의 첫 외부 구현
- [`execution-environment-implementation.md`](execution-environment-implementation.md) — 이 설계의 구현 계획(승인본)과 구현이 그 계획에서 벗어난 점
- [`execution-environment-ee9-ee12-hook-environment.md`](execution-environment-ee9-ee12-hook-environment.md) — 훅 컨텍스트에 실행 환경을 싣고 스킬 선언 훅의 셸을 실행 환경으로 옮긴 설계(EE-9 · EE-12)
- [`execution-environment-ee13-ee7-background-lifecycle.md`](execution-environment-ee13-ee7-background-lifecycle.md) — 백그라운드 `Bash` 종료(`KillShell`, 셸 취소 계약, 환경이 정하는 상한)와 제공자 · 작업 목록의 수명 상향 설계(EE-13 · EE-7)
- [`execution-environment-ee14-user-locale.md`](execution-environment-ee14-user-locale.md) — `Environment` 를 없애고 `timeZone` 을 `UserLocale` 로 옮긴 설계(EE-14)
- [`execution-environment-ee49-ee51-ee58-isolation-boundary.md`](execution-environment-ee49-ee51-ee58-isolation-boundary.md) — 한 런타임을 나눠 쓰는 실행들 사이의 경계 셋: 스킬 훅의 발화 범위, 명령을 돌리지 못한 가드의 fail-closed, 백그라운드 작업의 가시 범위(EE-49 · EE-51 · EE-58)
- [`execution-environment-ee70-ee71-fail-closed.md`](execution-environment-ee70-ee71-fail-closed.md) — 그 fail-closed 를 두 곳에 더 이은 설계: `onStart` 훅이 막으면 포크가 시작하지 않는다, 로드되지 않는 `hooks.json` 으로는 뜨지 않는다(EE-70 · EE-71)
- [`../workflow/workflow.md`](../workflow/workflow.md) §6.3 — worktree 격리
- [`../agent-execution/artifact.md`](../agent-execution/artifact.md) — `ArtifactCollector`
- [`../filesystem/backend-contract.md`](../filesystem/backend-contract.md) — VFS 백엔드 계약 (§7 의 `getMetadata` 조항이 들어갈 자리)
- [`parallel-execution.md`](parallel-execution.md) — 병렬 도구 디스패치와 실행 단위 컨텍스트 값
- [`../../overview/scope-model.md`](../../overview/scope-model.md) — 수명과 소멸 책임
