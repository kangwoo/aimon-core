# File Artifact — 에이전트가 만든 파일을 사용자에게 건넨다

> Status: **IMPLEMENTED** (코어 측). 다운로드 엔드포인트는 애플리케이션 계층의 몫이며 이 저장소에 없다.
> 적용 대상: `aimon-core`, `aimon-browser-playwright`
> 관련 규칙: [`.claude/rules/tool-development.md`](../../../.claude/rules/tool-development.md),
> [`.claude/rules/immutability-pattern.md`](../../../.claude/rules/immutability-pattern.md)
> 관련 문서: [`orca-executor.md`](orca-executor.md) (ReAct 루프와 `ExecutionScope`),
> [`compaction.md`](compaction.md) (압축이 메시지를 다시 쓸 때의 취급)

---

## 1. 무엇을 푸는가

도구가 파일을 쓰면 `ToolResult` 에는 `"Successfully wrote 15360 bytes to /reports/sales.csv"` 같은
**문장 하나**만 남는다. 호출자(Web API, CLI)가 "이번 실행에서 사용자에게 건넬 파일이 무엇인가"를 알려면
그 문장을 파싱해야 하고, 파싱은 도구 메시지 문구가 바뀌는 순간 깨진다.

아티팩트는 그 자리를 **구조화된 참조**로 채운다. 파일 콘텐츠는 여전히 `VirtualFileSystem` 에만 있고,
코어가 나르는 것은 경로·크기·MIME·파일명뿐이다.

원칙 넷.

- **의도는 LLM 이 표현한다** — 모든 파일이 아티팩트는 아니다. 임시 스크립트와 중간 산출물은 사용자에게
  건넬 것이 아니므로, 도구가 `artifact` 파라미터를 노출하고 모델이 그 값을 정한다. 기본값은 `true` 다
  (건넬 파일이 다수이고, 아닌 쪽을 명시하는 편이 짧다)
- **코어는 참조만 관리한다** — 콘텐츠 저장·전송은 `VirtualFileSystem` 과 애플리케이션 계층의 일이다
- **아티팩트 등록은 도구 결과를 바꾸지 않는다** — 쓰기는 이미 성공했다. 메타데이터 조회가 실패했다고
  도구를 에러로 만들면 모델이 멀쩡히 저장된 파일을 다시 쓴다
- **백엔드에 무관하다** — 경로는 `VirtualFileSystem` 기준이므로 Local/GridFS/S3 어디서든 같은 값이다

| 용어 | 뜻 |
|------|-----|
| 아티팩트 | 실행 중 생성된 파일 중 **사용자에게 건네려고 명시적으로 등록된** 것의 참조 정보 |
| `ArtifactCollector` | 실행 1회(턴 또는 포크) 동안 아티팩트를 모으는 수집기 |
| `artifact` 파라미터 | 아티팩트 인지 여부를 모델이 정하는 도구 입력 플래그 (기본 `true`) |

---

## 2. 계층

```
  도구 데코레이터                수집                    실행 결과 / 메시지
┌──────────────────────┐   ┌──────────────────┐   ┌───────────────────────────┐
│ ArtifactAwareWrite   │   │ ArtifactCollector│   │ OrcaAgentExecutionResult  │
│ ArtifactAwareEdit    │──▶│  add / size      │──▶│  getArtifacts()           │
│ ArtifactAwareBrowser │   │  sliceFrom       │   ├───────────────────────────┤
└──────────┬───────────┘   └──────────────────┘   │ Message.assistant(        │
           │ 위임                  ▲              │   text, toolUses,         │
           ▼                       │              │   List<MessageArtifact>)  │
   Write / Edit / Browser    ToolContext          └───────────────────────────┘
           │                 ARTIFACT_COLLECTOR
           ▼                 CURRENT_TOOL_USE_ID_KEY
   VirtualFileSystem
```

| 컴포넌트 | 패키지 | 역할 |
|----------|--------|------|
| `ArtifactMetadata` | `at.aimon.core.base` | 두 구현이 공유하는 메타데이터 계약 (path, fileName, size, mimeType, toolUseId, downloadToken) |
| `FileArtifact` | `at.aimon.core.agent.artifact` | 수집기 쪽 구현. `toMessageArtifact()` 로 메시지용 값을 만든다 |
| `MessageArtifact` | `at.aimon.core.llm` | 메시지에 실리는 구현. 전사와 함께 직렬화된다 |
| `ArtifactCollector` | `at.aimon.core.agent.artifact` | 실행 단위 수집기 (`CopyOnWriteArrayList`) |
| `ArtifactFileNames` | `at.aimon.core.agent.artifact` | `/` 와 `\` 를 모두 처리하는 파일명 추출 |
| `ArtifactAware{Write,Edit}Tool` | `at.aimon.core.tools.artifact` | `WriteTool`/`EditTool` 에 위임 + 등록 |
| `ArtifactAwareBrowserTool` | `at.aimon.browser.playwright.artifact` | 스크린샷 저장 시 등록 |
| `OrcaFileToolProvider` | `at.aimon.core.agent.impl.orca.tool` | `artifactEnabled` 로 어느 쪽을 등록할지 고른다 |
| `OrcaAgentExecutor` / `OrcaAgentExecutionResult` | `at.aimon.core.agent.impl.orca` | 수집기 생성·주입, 결과와 메시지에 부착 |

값 타입이 둘인 것은 계층이 둘이기 때문이다. `FileArtifact` 는 실행 파이프라인(수집기 → 결과)에서 살고,
`MessageArtifact` 는 LLM 메시지 안에서 살며 세션 전사와 함께 영속된다
(`at.aimon.core.subagent.task.codec.JsonSessionSnapshotCodec` 가 `downloadToken` 까지 포함해 인코딩한다).
`at.aimon.core.llm` 은 에이전트 실행 패키지에 의존하지 않으므로 메시지 쪽이 `FileArtifact` 를 직접 들 수
없고, 그래서 공통 계약만 `at.aimon.core.base` 에 두고 구현을 갈랐다.

---

## 3. 값 타입

`FileArtifact` 와 `MessageArtifact` 는 같은 여섯 필드를 갖는다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `path` | String | O | `VirtualFileSystem` 기준 경로. null/blank 불가 |
| `fileName` | String | O | 사용자에게 보여줄 이름 (`Content-Disposition` 용). null/blank 불가 |
| `size` | long | O | 바이트 수. 음수 불가 |
| `mimeType` | String | X | `Optional` 로 반환 |
| `toolUseId` | String | X | 이 파일을 만든 `tool_use` 의 id — 어느 호출의 산출물인지 추적 |
| `downloadToken` | String | X | 다운로드 저장소로 잇는 토큰. **코어는 발급하지 않는다**(§8) |

둘 다 불변이며 빌더로 만든다. `MessageArtifact.withDownloadToken(...)` 만 예외적으로 새 인스턴스를
돌려주는 변형자인데, 토큰을 붙이는 주체가 애플리케이션 계층이고 그때는 이미 값이 만들어져 있기 때문이다.

`ArtifactFileNames.extractFileName` 은 경로 구분자를 `/` 로 정규화하고 끝의 구분자를 떼어 낸 뒤 마지막
조각을 취한다. `"/reports/"` 는 `"reports"`, null/빈 문자열은 입력 그대로다 — 아티팩트 등록이 파일명
추출 때문에 실패하지 않게 하기 위한 것이다.

---

## 4. 수집 — `ArtifactCollector`

**실행 단위**로 하나 만든다. 메인 에이전트는 `OrcaAgentExecutor` 가 `ExecutionScope` 를 만들 때,
서브에이전트 포크는 `DefaultSubagentExecutor` 가 자기 실행을 시작할 때 각각 새로 만든다. `AgentRuntime`
수준이 아닌 이유는 명확하다 — 런타임은 agent-scoped 이고 여러 세션이 공유하므로, 거기에 두면 다른 세션의
파일이 내 결과에 섞인다.

도구는 `ToolContextKeys.ARTIFACT_COLLECTOR` 로 수집기를 꺼낸다. 없으면 그냥 등록하지 않는다 —
아티팩트를 쓰지 않는 어셈블리에서도 도구가 그대로 동작해야 한다. (같은 자리를 가리키던 문자열 상수
`ArtifactCollector.CONTEXT_KEY` 는 제거되었고, 그것이 들고 있던 와이어 이름 `"artifactCollector"` 는
타입 있는 키 쪽에 그대로 남아 있다.)

`CopyOnWriteArrayList` 를 쓰는 것은 같은 iteration 의 도구들이 병렬로 실행될 수 있기 때문이다
(`ConcurrencyBehavior.CONCURRENT_SAFE`). 읽기 API 두 개는 그 병렬성을 전제로 설계되었다.

- `getArtifacts()` — `List.copyOf` 스냅샷. 반환 후 수집기에 뭐가 더 들어와도 이 리스트는 변하지 않는다
- `sliceFrom(int fromIndex)` — "표시해 둔 지점 이후에 들어온 것". **스냅샷을 한 번만 뜨고** 그 안에서
  자른다. 크기를 읽고 다시 원소를 읽는 식이면 두 읽기 사이에 들어온 `add` 가 창을 밀어 버린다.
  범위를 벗어난 `fromIndex` 는 예외가 아니라 빈 리스트다 — 표시 이후 아무것도 안 들어왔다는 뜻일 뿐이고,
  한 iteration 의 아티팩트 장부 때문에 턴을 죽일 이유는 없다

---

## 5. 도구 데코레이터

기존 `WriteTool` / `EditTool` 은 **한 줄도 바뀌지 않았다**. 아티팩트 기능은 같은 이름(`"Write"`,
`"Edit"`)으로 등록되는 별도 클래스가 위임으로 얹는다. 모델은 도구 이름만 보므로 프롬프트도 바뀌지 않는다.

실행 순서는 셋뿐이다.

1. `artifact` 플래그를 **방어적으로** 읽는다. `getBoolean` 은 키가 있는데 Boolean 이 아니면 던지는데,
   이 코드는 위임 호출보다 앞에 있다. 모델이 `"true"`(문자열)를 보냈다고 멀쩡한 쓰기가 취소되고 예외가
   `execute()` 를 벗어나면 도구 계약 위반이므로, 경고를 남기고 기본값(`true`)으로 진행한다
2. 위임 도구를 그대로 호출하고, 그 `ToolResult` 를 **그대로** 돌려준다
3. 성공 + `artifact=true` 일 때만 등록한다. `fileSystem.getMetadata(path)` 로 크기와 MIME 을 얻고,
   실패하면 대체값을 쓴다 — `Write` 는 `content` 의 UTF-8 바이트 길이, `Edit` 는 원본 크기를 알 방법이
   없으므로 `0`. 등록 경로 전체가 try/catch 로 감싸여 있고 실패는 경고 로그로 끝난다

`toolUseId` 는 도구가 만드는 값이 아니라 `ToolContextKeys.CURRENT_TOOL_USE_ID_KEY` 로 주입받는 값이며,
그것을 넣는 곳은 `at.aimon.core.toolinvocation.SingleToolInvoker` 다 — 도구 호출 1건마다 컨텍스트를
파생시키는 지점이 거기 하나뿐이므로, 메인 에이전트와 서브에이전트가 같은 코드를 공유한다.

권한 판정은 위임 도구에게 넘긴다. `Write(/tmp/**)` 같은 패턴은 아티팩트 변형이 등록되어 있든 아니든 같은
경로를 판정해야 하기 때문이다. `artifact` 플래그는 판정 주체(subject)에 들어가지 않는다 — 그 값은
"어느 파일을 건드리는가"가 아니라 "쓴 파일을 사용자에게 내려 줄 것인가"를 정하고, 권한 패턴은 전자에
대한 것이다.

**브라우저 도구**는 두 가지가 다르다. 등록 조건이 `action=screenshot` 이고 `save_path` 가 주어졌을 때로
좁고, 위임 전에 `artifact` 키를 **입력에서 제거**한다. 위임 대상이 `GenericTool` 이라 입력 `record` 에
없는 키를 거부하는데, 그 키는 데코레이터가 혼자 선언한 것이기 때문이다.

---

## 6. 어디에 붙는가

**메시지** — iteration 마다 도구 실행 직전에 `collector.size()` 로 표시를 남기고, 실행 뒤
`sliceFrom(표시)` 로 그 iteration 이 만든 것만 잘라 `Message.assistant(text, toolUses, artifacts)` 에
싣는다. 이렇게 하면 아티팩트가 그것을 만든 assistant turn 옆에 남고, `toolUseId` 로 개별 `tool_use`
까지 좁혀진다.

**실행 결과** — `OrcaAgentExecutionResult` 는 `artifacts` 필드를 갖고 `AgentExecutionResult.getArtifacts()`
(기본 구현은 빈 리스트)를 채운다. 부착은 **모든 종료 경로**에서 일어난다 — COMPLETED, TRUNCATED, ERROR,
INTERRUPTED, SUSPENDED, MAX_ITERATIONS, 그리고 커맨드 처리 경로. 실패한 실행에도 아티팩트가 실린다는
뜻이고, 이는 의도다. 파일은 이미 `VirtualFileSystem` 에 있고 사용자는 부분 결과라도 받을 수 있어야 한다.
호출자는 `isSuccess() == false` 인데 `getArtifacts()` 가 비어 있지 않은 경우를 처리해야 한다.

**압축** — `MessageStripper` 는 assistant 메시지를 다시 만들 때 `tool_use` 가 붙어 있으면 `artifacts` 를
그대로 옮긴다. 압축이 이미지·문서 블록을 걷어내도 파일 참조는 살아남는다.

---

## 7. 켜는 방법

기본은 **꺼져 있다**. `new OrcaFileToolProvider()` 는 `artifactEnabled=false` 이고 평범한
`WriteTool`/`EditTool` 을 등록한다. 아티팩트가 필요한 어셈블리(Web API 등)가
`new OrcaFileToolProvider(true)` 를 커스텀 프로바이더로 넘겨 켠다. 아티팩트를 쓸 일이 없는 CLI 는
`artifact` 파라미터가 스키마에 나타나지 않으므로 모델이 볼 일도 없다.

`OrcaFileToolProvider.FILE_TOOL_NAMES` 는 워크트리 격리와 락스텝이다. VFS 를 쓰는 도구를
`registerTools` 에 추가하면 이 집합에도 넣어야 하며, 그러지 않으면
`WorktreeToolEnvironmentFactory` 가 그 도구를 베이스 파일시스템에 묶인 채 넘겨 격리를 조용히 무력화한다.

---

## 8. 다운로드는 애플리케이션 계층의 일

코어는 `artifacts` 목록까지만 만든다. 파일을 실제로 내려 주는 것은 이 저장소 밖이며, 그 계층이 반드시
풀어야 할 것이 셋이다.

| 위협 | 대응 |
|------|------|
| Path traversal (`../`) | 경로 정규화 후 허용 루트 안인지 검증 |
| 미등록 파일 다운로드 | 경로를 그대로 받는 엔드포인트는 VFS 전체를 노출한다. 등록된 아티팩트만 허용 |
| 무단 접근 | 기존 API 인증 + 이 세션/사용자가 그 아티팩트의 주인인지 확인 |

`downloadToken` 이 이 셋을 한 번에 접기 위한 자리다. 경로 대신 토큰을 URL 에 싣게 하면 엔드포인트는
경로 문자열을 해석할 필요가 없고, 토큰 자체가 화이트리스트이자 권한이 된다. **코어에는 발급자가 없다** —
필드와 직렬화, `withDownloadToken` 변형자만 준비되어 있고 토큰을 만들고 검증하는 것은 애플리케이션의
몫이다. 발급 방식은 그쪽 인프라를 따른다(단일 인스턴스면 인메모리 TTL 캐시, 멀티 인스턴스면 Redis 나 DB).

메타데이터 자체는 이제 메시지에 실려 세션 전사와 함께 영속되므로, 다운로드 시점에 실행 결과를 붙들고
있을 필요는 없다.

---

## 9. 설계 결정

**기존 도구를 고치지 않고 위임한다.** 직접 수정하면 아티팩트가 필요 없는 환경에도 `artifact` 파라미터가
노출되고, 상속은 불가능하다 — `AbstractTool` 생성자가 스키마를 받으므로 하위 클래스가 스키마를 넓힐 수
없다. "모든 파일을 자동으로 아티팩트로" 는 임시 파일과 산출물을 구분할 방법을 없애므로 기각했다.

**수집기는 `ToolContext` 로 전달한다.** `ToolResult` 에 필드를 더하면 모든 도구가 그 변경을 지고 가야
한다. 초기 설계는 문자열 키였고 지금은 타입 있는 `ToolContextKey` 로 옮겨 왔다 — 기존 키는 남겨 두되
제거 예정으로 표시했다.

**등록 실패는 삼킨다.** §1 의 세 번째 원칙 그대로다. 다만 삼키는 범위는 **등록**뿐이고, 위임 도구가
낸 실패는 그대로 전달된다.

**`toolUseId` 주입은 호출 지점 하나에서.** 도구 실행 관리자 안에서 주입하면 관심사가 섞이고,
실행기마다 따로 주입하면 서브에이전트 경로가 빠진다. `SingleToolInvoker` 는 두 경로가 공유하는
유일한 도구 호출 지점이다.

---

## 10. 남은 것

- **포크의 아티팩트가 결과에 드러나지 않는다.** `DefaultSubagentExecutor` 는 수집기를 만들어 컨텍스트에
  넣지만(도구 패리티), 모인 것을 `SubagentExecutionResult` 로 올리지는 않는다
- **`downloadToken` 발급자가 없다.** 코어에는 필드·직렬화·변형자만 있다 (§8)
- **다른 도구로의 확장.** `Bash` 처럼 파일을 만들 수 있는 도구는 아직 같은 위임 패턴을 쓰지 않는다
- **`tool_use` 없는 assistant 메시지의 아티팩트.** `MessageStripper` 는 `tool_use` 가 붙은 메시지의
  `artifacts` 만 보존하므로, 커맨드 처리 경로가 만드는 메시지의 아티팩트는 압축 스트리핑에서 떨어진다

의도적으로 다루지 않는 것: 파일 TTL(정리는 스케줄링과 연동), 파일별 ACL, 청크/재개 다운로드
(`VirtualFileSystem` 이 이미 스트리밍한다), `FileArtifact` 의 타임스탬프 필드
(`FileMetadata` 에서 조회할 수 있다).

---

## 부록 — 참조 파일 지도

| 관심사 | 파일 |
|--------|------|
| 공통 계약 | [`ArtifactMetadata.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/base/ArtifactMetadata.java) |
| 수집기 쪽 값 | [`FileArtifact.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/artifact/FileArtifact.java) |
| 메시지 쪽 값 | [`MessageArtifact.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/llm/MessageArtifact.java) |
| 수집 | [`ArtifactCollector.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/artifact/ArtifactCollector.java) |
| 파일명 추출 | [`ArtifactFileNames.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/artifact/ArtifactFileNames.java) |
| 쓰기 데코레이터 | [`ArtifactAwareWriteTool.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/artifact/ArtifactAwareWriteTool.java) |
| 편집 데코레이터 | [`ArtifactAwareEditTool.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/artifact/ArtifactAwareEditTool.java) |
| 스크린샷 데코레이터 | [`ArtifactAwareBrowserTool.java`](../../../modules/aimon-browser-playwright/src/main/java/at/aimon/browser/playwright/artifact/ArtifactAwareBrowserTool.java) |
| 등록 스위치 | [`OrcaFileToolProvider.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/tool/OrcaFileToolProvider.java) |
| 컨텍스트 키 | [`ToolContextKeys.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/tools/ToolContextKeys.java) |
| 결과 부착 | [`OrcaAgentExecutionResult.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutionResult.java) |
| 실행 흐름 | [`OrcaAgentExecutor.java`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentExecutor.java) |

관련 문서: [`tool-development-guide.md`](../../features/tool/tool-development-guide.md),
[`solid-principles.md`](../../project/solid-principles.md)
