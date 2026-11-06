# 훅이 도는 자리 (Hook Environment)

> **Status**: IMPLEMENTED — `aimon-core` 의 `at.aimon.core.hook` · `hook.event` · `skill.hook` · `skill.hook.declarative` · `config.hook` 과 `aimon-cli` 의 `AgentSetupFactory`. 열린 항목은 [등록부](../../backlog/execution-environment-open-items.md)의 EE-63, EE-110, EE-112, EE-119 다.

훅의 명령이 어느 환경의 어느 셸에서 도는가, 스킬 훅이 어느 실행에 보이는가의 정본이다. 훅 종류와 결과 모델, exit 2 는 [훅 시스템](hook-system.md), 판단하지 못한 가드는 [fail-closed](hook-fail-closed.md), 훅 파일의 변조는 [변조 경계](hook-tamper-boundary.md)가 소유한다. 설정 문법은 [hook-config-guide](../../features/hook/hook-config-guide.md) 에 있다.

## 1. 원칙

**훅은 자기가 다루는 실행의 환경에서 돌고, 호스트는 실행이 없을 때만 쓴다.**

호스트 셸로 통하는 길은 위험이다. 도구는 샌드박스에서 도는데 스킬의 셸 훅이 호스트에서 돌면 같은 스크립트가 두 곳에서 돌고, 스킬 작성자가 호스트 셸을 얻는다. 반대로 정책 훅이 호스트에만 있으면 자기가 지키는 워크스페이스를 보지 못한다.

`hooks.json` 은 운영자의 정책이므로 제약이 하나 더 있다. 에이전트의 환경에 놓인 명령을 에이전트 상태가 조종할 수 없어야 한다. 그래서 [격리 실행](#7-격리-실행)으로 돌린다. 스킬 훅은 격리하지 않는다. 그 스킬의 도구와 같은 셸 상태에서 시작한다.

세 경계는 모두 격리를 넘는 문제를 막는다. 스킬 훅을 에이전트 전체에 흘리면 세션 A 가 활성화한 스킬의 훅이 세션 B 의 이벤트에 발화해 B 의 환경에서 명령을 돌린다. [§11](#11-스킬-훅-범위)이 이것을 막는다.

## 2. 실행 안·밖 분류

이벤트 목록의 단일 출처는 `HookEventType` 이고 실행 안·밖 분류의 단일 출처는 `HookEventType.firesInsideExecution()` 이다. 실행 밖 이벤트는 셋(`onSessionStart`, `onSessionEnd`, `onConfigReload`)이고 나머지 열 개는 실행 안이다. 판정 쪽의 이벤트 분류는 [fail-closed](hook-fail-closed.md) 머리의 표에 있다.

`firesInsideExecution()` 은 라이브 발화 지점의 분류이지 보장이 아니다. 실행 안 이벤트도 발화 시점에 환경이 없을 수 있다.

- rewake 재발화는 실행 밖에서 `preTool` · `preCompact` 를 다시 발화한다. 체인이 아니라 원래 훅 하나에만 발화한다.
- 손으로 만든 `ToolContext`, 환경을 넘기지 않는 서드파티 `CompactionGuard`.
- 에이전트 단위 러너가 스폰한 포크의 `subagentStart`.

환경이 있되 사용 불가일 수도 있다(`UnavailableExecutionEnvironment`). 이 경우들은 파싱으로 막을 수 없어 [발화 시점 판정](hook-fail-closed.md)이 받는다.

조립된 런타임의 실행 안 발화 지점은 모두 자기 환경을 넘긴다. 실행은 있는데 환경이 없는 발화는 손으로 만든 `ToolContext` 와 환경을 넘기지 않는 서드파티 `CompactionGuard` 뿐이다.

| 발화 지점 | 환경의 출처 |
|---|---|
| `SingleToolInvoker` (`preTool` · `postTool` · `permissionRequest` · `permissionDenied`) | 도구 컨텍스트의 `EXECUTION_ENVIRONMENT` |
| `OrcaAgentExecutor` (`onStart` · `onStop`) | 실행 스코프의 환경 |
| `DefaultSubagentExecutor`, `SubagentOnStartGate` | 포크 루프 컨텍스트의 환경 |
| `CompactCommand` | 커맨드 `ToolContext` 의 환경 |
| 압축 경로 | [§15](#15-압축-경로) |
| `DefaultSubagentExecutionManager` | [§13](#13-subagentstartsubagentstop-의-환경) |

## 3. `hasExecution`

실행 안 이벤트가 환경 없이 발화하면 두 경우를 가려야 한다. 뒤에 실행이 없는 것과, 실행은 있는데 환경이 훅에 닿지 않은 것이다. 뒤쪽은 배선 결함이고 호스트로 보내면 "호스트 폴백 없음" 을 어긴다. 그래서 컨텍스트가 적극적으로 답한다.

| 컨텍스트 | `hasExecution()` |
|---|---|
| `HookContext` 기본값 | `true` |
| `OnSessionStartContext`, `OnSessionEndContext`, `OnConfigReloadContext` | `false` |
| `SubagentStartContext`, `SubagentStopContext` | 빌더 플래그 `spawnedOutsideExecution` 의 반대 |
| `SignalDetachedHookContext` | 감싼 컨텍스트에 위임 |

규칙은 하나다. **발화가 다루는 어느 실행에도 환경이 없을 때만 실행이 없는 발화다.**

- 에이전트 단위 러너의 `subagentStart` 는 실행이 없는 발화다. 그 러너는 부모 환경 없이 스폰한다.
- 같은 러너의 `subagentStop` 은 포크가 환경을 해석했으면 실행이 있는 발화이고 그 포크의 환경에서 돈다. 로컬 제공자에서는 부모가 없으면 루트 환경을 돌려주므로 워크스페이스 루트에서 돈다.
- 환경을 해석하지 않는 경로(모르는 서브에이전트, 호출자 상한 거절, 풀 거절, 해석 전의 예외, 코드 behavior)의 `subagentStop` 은 스폰한 쪽 환경도 없으면 실행이 없는 발화다.

**알려진 예외.** `spawnedOutsideExecution` 은 스폰한 쪽 환경이 비었는지로 정한다. 그래서 실행은 있지만 환경이 없는 스폰한 쪽(`Task` 를 부르는 손으로 만든 `ToolContext`)은 실행 없음으로 읽히고, 그 `subagentStart` 는 기본 배치 명령을 호스트에서 돌린다. 같은 실행의 `preTool` 은 `NO_ENVIRONMENT` 다. 조립된 런타임은 이 경우를 만들지 않고 `subagentStart` 는 가드 이벤트가 아니므로 "호출한 실행이 있는가" 플래그를 두지 않는다. `HookContext#hasExecution()` Javadoc 이 이 예외를 적는다.

## 4. `HookContext` 의 환경

`HookContext.getExecutionEnvironment()` 는 `Optional<ExecutionEnvironment>` 이고 기본값은 비어 있다. `getEnvironmentDescriptor()` 는 거기서 파생된다. 컨텍스트는 환경 하나만 필드로 든다. 서술자를 따로 들면 "어디서 도는가" 의 출처가 둘이 된다.

실행 안 열 이벤트의 컨텍스트만 `executionEnvironment(...)` 빌더 메서드를 갖는다. 실행 밖 셋에는 없으므로 실수로도 채울 수 없고, 인터페이스 기본값이 "비어 있는 것이 정답" 을 표현한다.

사용 불가 환경은 빈 값으로 바꾸지 않고 그대로 싣는다. 도구 경로와 같은 규칙이고 훅이 `descriptor()` 로 사용 불가를 구분할 수 있다.

## 5. 실행기 구성

실행기는 셸을 쥐지 않고 발화 컨텍스트의 환경에서 얻는다. 포크 스킬의 훅은 포크의 도구에서 발화하고 포크는 환경을 따로 해석하며, 파싱된 `SkillHookSet` 은 실행 사이에 공유된다. `ThreadLocal` 은 풀 스레드에서 전파가 조용히 끊긴다.

| 실행기 | 어디서 돌리나 | 쓰는 곳 |
|---|---|---|
| `DefaultShellActionExecutor` | 컨텍스트 환경의 셸. 로컬 제공자에서는 워크스페이스를 cwd 로 하는 셸 | 스킬 훅 |
| `IsolatedShellActionExecutor` | 기반 환경의 셸에서 격리 실행 | `hooks.json` 의 환경 배치 |
| `HostShellActionExecutor` | 생성자로 받은 고정 셸. 컨텍스트를 무시한다 | `hooks.json` 의 호스트 배치 |
| `ExecutionOrHostShellActionExecutor` | `hasExecution()` 으로 앞의 둘 중 하나로 보낸다 | `hooks.json` 의 기본 배치 |
| `NoOpShellActionExecutor` | 돌리지 않는다(`SHELL_UNSUPPORTED`) | 셸 미지원 조립 |

- `ShellActionExecutor.run` 은 컨텍스트를 받고 던지지 않는다. 컨텍스트가 없는 진입점은 없다. 있으면 셸을 고를 수 없고 호스트로 돌아가는 길이 된다.
- 판단 메서드는 둘이다. `requiresExecutionEnvironment()` 는 컨텍스트의 환경 없이 돌 수 없는가이고, `canRunOn(HookEventType)` 은 그 답과 이벤트 분류를 합친 [파싱 시점 거부](#10-파싱-시점-거부) 규칙이다.
- `ExecutionOrHostShellActionExecutor` 의 `requiresExecutionEnvironment()` 는 거짓이다. 어디서든 돌 수 있기 때문이다. `canRunOn` 과 `toRewakeSpec` 은 이것을 호스트 실행기처럼 다룬다. `config.hook` 이 만들어야 하므로 공개 클래스다.
- 네 실행기가 공유하는 실행 사다리는 `ShellActionRunner` 하나다. 환경에서 셸을 얻는 일은 `EnvironmentShells.withShell` 이 람다에 셸을 건네는 방식으로 하고, 셸을 필드로 남기지 않는다.
- `HookRegistryApplier` 는 두 실행기를 들고 배치에 맞는 것으로 핸들러를 만든다. `HookHotReloadBootstrap.Builder.shellExecutor(...)` 는 호스트 실행기이고 호스트 배치만 맡는다. `environmentShellExecutor(...)` 의 기본값은 `IsolatedShellActionExecutor` 라 제공자 전용 코드 없이 어느 조립에서나 동작한다. 커스텀 환경 실행기는 `AIMON_HOOK_DIR` 을 스스로 내보내야 한다.
- 스택은 호스트 셸을 만들지 않는다. `hooks.json` 핫리로드를 배선한 어셈블리(CLI)가 `hookConfigShell` 을 소유한다. 이 셸은 실행 밖 이벤트, `"shell": "host"`, `asyncRewake` 예외, 실행이 없는 발화에만 쓰인다.
- 훅의 명령이 모델의 셸 세션 밖에서 도는 계약(`ExecutionOptions.isHook()`)은 [셸 계약](../tool/shell-contract.md)이 소유한다.

## 6. `hooks.json` 배치 표

배치는 적용 시점에 `HookRegistryApplier` 가 핸들러마다 한 번 정한다.

| 핸들러의 `"shell"` | 실행 안 이벤트 | 실행 밖 이벤트 (`onSessionStart`, `onSessionEnd`, `onConfigReload`) |
|---|---|---|
| 없음 | 환경(격리 실행). 실행이 없는 발화는 호스트. [예외](#61-asyncrewake-예외) 하나 | 호스트 |
| `"environment"` | 환경. 실행이 없는 발화는 `NO_ENVIRONMENT` | WARN 후 건너뜀(`canRunOn`). 셋 다 가드 이벤트가 아니다 |
| `"host"` | 호스트 | 호스트 |

호스트 배치는 실행 밖 세 이벤트, `"shell": "host"`, 실행이 없는 발화, [`asyncRewake` 예외](#61-asyncrewake-예외)에만 쓴다. 이벤트별로 정적으로 나누므로 실행 밖 훅을 선언할 자리가 그대로 남는다. 모든 `hooks.json` 을 호스트에 두면 정책 훅이 자기가 지키는 워크스페이스를 보지 못한다.

실행은 있는데 컨텍스트에 환경이 없으면 기본 배치도 호스트로 가지 않고 `NO_ENVIRONMENT` 다. 해결책은 `"shell": "host"` 를 명시하는 것이다.

`"shell"` 값은 대소문자를 가리지 않는다. 모르는 값과 `command` 가 아닌 핸들러의 `"shell"` 은 파싱 실패다(시작은 멈추고 리로드는 이전 설정을 유지한다). 오타 `"hots"` 를 관대하게 읽으면 핸들러가 말없이 환경으로 옮겨 가기 때문이고, 같은 파일의 모르는 `type` 도 같은 규칙이다.

### 6.1 `asyncRewake` 예외

`"shell"` 이 없는 `command` 핸들러가 rewake 할 수 있는 실행 안 이벤트(`preTool`, `preCompact`)에 `asyncRewake` 를 선언하면 호스트에 남고 WARN 을 남긴다. 재발화가 실행 밖에서 발화하기 때문이다. WARN 은 호스트에서 도는 이유와 `"shell": "host"` 로 끌 수 있음을 말한다. 다른 이벤트의 `asyncRewake` 는 `toRewakeSpec` 이 WARN 후 떨구므로 지킬 것이 없고 핸들러는 기본 배치를 따른다.

명시적 `"environment"` 는 다른 규칙이다. 그 핸들러는 환경 실행기에 묶이고 `toRewakeSpec` 이 rewake 를 WARN 후 떨군다. 훅 자체는 등록된다. 두 규칙은 대상이 다르다. 앞은 기본 배치이고 뒤는 명시적 배치다.

스킬 frontmatter 훅은 `asyncRewake` 를 갖지 않는다.

## 7. 격리 실행

`hooks.json` 의 환경 배치 명령은 `ExecutionOptions.isolated` 를 켠 격리 실행이다. 옵션 계약은 [셸 계약](../tool/shell-contract.md)이 소유한다. 이 절은 훅 쪽 규칙만 적는다.

`isHook()` 만으로는 부족하다. 세션 잠금을 잡지 않고 상태를 저장하지 않지만 세션의 현재 상태에서 시작할 수 있으므로, 에이전트의 `BASH_ENV` · `LD_PRELOAD` · `PATH` · cwd 가 정책 훅에 닿는다.

**셸이 선언하지 않으면 돌리지 않는다.** `ISOLATED_EXECUTION` 을 선언하지 않은 셸에서는 `hooks.json` 명령을 돌리지 않는다(`ISOLATION_UNSUPPORTED`). 비격리로 돌리면 에이전트 상태가 정책 훅을 조종할 수 있는데도 아무도 모르기 때문이다. WARN 은 셸 클래스와 해결책(제공자 업그레이드, `"shell": "host"`)을 말한다. `supports` 가 던져도 같은 결과로 읽는다([셸 계약](../tool/shell-contract.md)의 `ShellFeature` 절). 선언해 놓고 지키지 않는 셸은 코어가 잡을 수 없는 제공자 결함이다.

**기반 환경의 루트에서 돈다.** 격리 실행은 기반 환경의 루트에서 시작한다(로컬 제공자에서는 워크스페이스 루트). 기반 환경은 `ExecutionEnvironment.isolatedFrom()` 사슬의 끝이고, 사슬이 비었으면 컨텍스트의 환경 자신이다. `IsolatedShellActionExecutor.baseOf` 가 사슬을 걷는다. 32단계에서 멈추고 던지는 `isolatedFrom()` 은 계보 없음으로 읽어 WARN 한다. 제공자 결함이 훅을 멈추거나 실패시키지 못하게 하기 위해서다.

격리 브랜치의 뷰에서 돌리지 않는 이유는 둘이다. 상대 스크립트 경로가 exit 127 이 되어 `preTool` 이면 그 단계의 모든 도구 호출을 막고, 상대 경로 쓰기가 `WorktreeMerge` 로 프로젝트에 올라간다. 그래서 브랜치의 파일은 그 루트에서 `.worktrees/{key}/` 로 닿고, 상대 경로 쓰기는 기반 환경의 루트에 남아 병합되지 않는다. 스킬 훅은 이 규칙 밖이다. 컨텍스트 자신의 셸(브랜치 뷰)에서 돈다.

**제공자 계약.** 계보를 `isolatedFrom()` 으로 밝힌 브랜치는 기반 환경의 뷰다. `isolate()` 가 정말 별개의 환경을 돌려주는 제공자는 계보를 그렇게 밝히면 안 된다. 밝히면 그 브랜치의 훅이 부모에서 돈다. `isIsolated()` 와 `isolatedFrom()` 의 Javadoc 이 이것을 적는다.

**PATH.** 코어는 `PATH` 를 정하지 않는다. 격리 실행의 기본 변수가 이미 에이전트 밖의 값이고, 상수는 제공자마다 맞지 않으며, 핸들러별 키는 에이전트가 쓸 수 있는 프로젝트 층에서 기본값보다 강하지 않다. 코어는 훅의 고정 `AIMON_*` 변수(`SkillHookEnv`)만 더한다. 기본 변수는 로컬에서 JVM 의 환경이고 샌드박스에서 프로파일의 exec 환경이다. 운영자 지정은 [EE-112](../../backlog/execution-environment-open-items.md)가 추적한다.

**층을 섞지 않는다.** 기반 환경의 루트는 명령이 시작하는 자리다. 프로젝트 루트는 `<projectRoot>/.aimon` 층을 찾는 호스트 경로이고 명령이 도는 자리가 아니다.

## 8. CLI 의 cwd

CLI 의 `hooks.json` 명령은 모든 이벤트에서 워크스페이스 루트에서 돈다. 제공자 중립적인 "JVM cwd 유지" 규칙이 없고, 한 파일이 두 cwd 로 갈리면 안 되며, 상대 경로는 그 파일을 선언한 층 옆에서 풀려야 한다.

CLI 의 워크스페이스 루트가 무엇인지는 [실행 환경](../tool/execution-environment.md)의 로컬 제공자 절에 있다. 호스트 셸(`createHookConfigShell`)을 그 값에 묶으므로 환경 배치와 호스트 배치가 같은 cwd 를 쓴다. CLI 는 프로젝트 루트에도 같은 값을 넘긴다. 다른 조립에서는 달라도 된다.

## 9. `AIMON_HOOK_DIR` 의 로드·발화 실패

`hooks.json` 명령 텍스트가 `AIMON_HOOK_DIR` 을 언급하면 적용 시점에 층의 `hooks/` 디렉터리가 액션에 실린다(`ShellAction` 의 `HookScriptDirectory`). 값은 선언한 파일 옆의 `hooks/` 다. 사용자 층은 `~/.aimon/hooks/`, 프로젝트와 로컬 층은 `<projectRoot>/.aimon/hooks/` 다. 언급하지 않는 핸들러는 아무것도 치르지 않는다.

훅은 경로를 내보내지 않는다. `ExecutionOrHostShellActionExecutor` 가 발화마다 경로를 정하므로 실제로 돌리는 실행기만 그 셸에 맞는 값을 줄 수 있다. 액션이 디렉터리를 나르므로 `ShellActionExecutor.run` 시그니처는 그대로다. 호스트 실행기는 호스트 경로를, 격리 실행기는 실행 디렉터리를 내보낸다. 이 값의 뜻과 읽기 규칙은 [변조 경계](hook-tamper-boundary.md)가 소유한다.

`HookScriptDirectory` 는 `skill.hook.action` 에 있고 그 팩터리가 층의 `.aimon` 을 루트로 하는 읽기 전용 소스를 스스로 만든다. `config.hook` 은 `filesystem` 패키지를 볼 수 없어 `Path` 만 넘긴다. 소스의 루트 안에서 `hooks` 만 스캔하므로 그 밖은 읽히지 않는다.

| 시점 | 상황 | 처리 |
|---|---|---|
| 로드 | `hooks/` 가 없거나 디렉터리가 아니다. 층의 파일을 알 수 없는 설정(코드로 조립)도 같다 | 적용 불가. 가드 훅은 시작을 멈추고(`HookConfigParseException`), 그 밖은 WARN 후 건너뜀 |
| 로드 | `hooks/` 안의 파일 | 로드는 존재만 확인하고 스캔하지 않는다. 거부는 발화 때 일어난다 |
| 발화 | 셸이 `RUN_DIRECTORY` 를 선언하지 않는다 | `RUN_DIRECTORY_UNSUPPORTED` |
| 발화 | 읽을 수 없음, 상한 초과, 읽는 중 변경, 둘 수 없는 이름 | `HOOK_DIR_STAGING_FAILED` |

**리터럴 호스트 경로의 힌트.** 호스트 실행기에만 묶이지 않은 핸들러의 명령이 층 디렉터리를 글자로 적으면(`~/.aimon/`, `$HOME/.aimon/`, `${HOME}/.aimon/`, 절대 층 경로, `.aimon-staged` 가 아닌 상대 `.aimon/`) 적용 시점에 표시를 단다. 그런 명령이 가드 이벤트에서 exit 126 · 127 로 끝나면 사유에 힌트가 붙는다(`withCouldNotStartHint`). 힌트는 일치한 표기만 말하고 명령을 싣지 않는다. advisory 이벤트에서는 사유를 만들지 않으므로 WARN 의 종료 코드만 남는다. 로드 시점에 거부하지 않는 이유는 그 시점에 코어가 환경이 호스트인지 알 수 없고, 로컬 제공자에서는 그 경로가 있기 때문이다.

## 10. 파싱 시점 거부

규칙은 한 줄이고 `ShellActionExecutor.canRunOn` 에 있다. **실행기가 환경을 요구하고 이벤트가 실행 밖이면 셸 액션을 거부한다.** 스킬 파서와 `hooks.json` 적용기가 같은 메서드를 부르므로 두 프런트엔드의 조건식이 어긋날 수 없다.

- **`SkillHookSetParser`.** 실행 밖 세 이벤트는 `SkillHookSet.supportedEvents()` 에 없어 "unknown event" 로 먼저 거부되고, 메시지가 이유를 적는다. `canRunOn` 검사는 그 집합에 실행 밖 이벤트가 더해지는 순간 막는 불변식이다. 실패는 `SkillParseException` 이다.
- **`HookRegistryApplier`.** 실행 밖 이벤트는 가드 이벤트가 아니므로 WARN 후 그 핸들러만 건너뛴다.
- **발화 시점만으로 막지 않는다.** 실행기가 실패를 삼키면 조용하다. 파싱에서 막을 수 있는 것은 파싱에서 막는다.

**한 스킬의 거부가 다른 스킬을 무너뜨리지 않는다.** `DefaultSkillRegistry` 의 `getAllSkills()` · `reloadAll()` 은 `SkillParseException` 도 잡아 그 스킬만 건너뛰고 WARN 한다. `getSkill(name)` · `reloadSkill(name)` 은 그 스킬을 지목한 호출이므로 던진다.

## 11. 스킬 훅 범위

스킬 훅의 범위는 **그 스킬의 포크와 포크의 후손**이다. 런타임의 `HookRegistry` 에 등록하지 않는다.

스킬 훅의 범위는 id 비교가 아니라 구조로 정해진다. 포크는 받은 레지스트리로 모든 이벤트를 디스패치하므로(`HookContext.getHookRegistry()`, 전역 조회가 아니다), 뷰를 쥔 실행만 그 스킬 훅을 본다. 공유 목록에 넣고 거르면 `HookContext` 에 공통 정체성이 없고, `EXECUTION_ID` 는 전달되지 않아 계보 추적이 필요하며, 다른 실행의 디스패치 순서에 끼어든다.

**뷰.** `SkillScopedHookRegistry` 는 런타임 레지스트리 위에 불변 스킬 층을 얹은 레지스트리다.

- 읽기는 기반의 목록 뒤에 활성인 스킬 층을 붙인다. 런타임 훅이 먼저 돌고 스킬 훅이 뒤에 돈다. 기반을 매번 읽으므로 `hooks.json` 리로드가 그대로 비친다.
- 스킬 층은 읽기에만 보이고 쓰기는 기반으로 간다. 포크 안에서 동적으로 등록한 훅은 런타임 전체에 간다.
- `close()` 는 원자적 플래그로 층을 끈다. 뷰를 계속 쥔 백그라운드 후손도 닫힌 뒤에는 런타임 훅만 본다.
- 활성 상태는 저장소가 아니라 한 실행이 쥔 객체다. 실행은 띄운 프로세스 안에서만 돌고 스킬 포크에는 스냅숏 재개 경로가 없으므로 다른 노드가 이 상태를 알아야 할 순간이 없다. `LiveSession` 의 메시지 큐와 같은 부류이고 저장소 인터페이스로 뽑을 대상이 아니다.

**전달.** 실행은 자기가 디스패치하는 레지스트리를 `ToolContextKeys.HOOK_REGISTRY` 로 싣는다. 스폰 지점 넷(`TaskTool`, `WorkflowTool`, `GraalJsWorkflowTool`, `SubagentBackedSkillForkExecutor`)은 `HookRegistryAccess.of(context).orElse(생성자 값)` 을 포크에 넘긴다. 그러지 않으면 후손이 런타임 레지스트리로 돌아가 스킬의 가드가 그 하위 트리에서 꺼진다.

- `HOOK_REGISTRY` 는 write-once 다. enricher 가 포크의 스폰 도구가 쓰는 레지스트리를 바꿔치기해 가드를 벗길 수 없다. 교체 지점은 `HookRegistryAccess.withHookRegistry` 하나이고 덮어쓰지 않고 새 컨텍스트를 조립한다.
- 활성화 호출처는 `SkillTool` 과 `LlmSkillExecutor` 둘이다. 슬래시 경로도 스킬 훅을 받는다.
- 범위는 활성화의 try-with-resources 다. 포크가 답을 돌려주면 닫힌다.
- 중첩 스킬의 뷰는 바깥 뷰 위에 얹히므로 두 스킬의 훅이 모두 보인다.
- 세션 없는 실행(루틴, 포크)도 호출의 `ToolContext` 에서 기반을 읽어 같은 규칙을 따른다.
- 층이 만들어지다 실패해도 공유 상태를 건드리지 않았으므로 되돌릴 것이 없고, 호출자가 `ToolResult.error` 로 바꾼다.

**활성화한 실행 자신에게는 발화하지 않는다.** 계약은 "포크한 에이전트의 도구 호출을 관찰한다" 이다. 부모에게 발화하려면 진행 중인 실행의 레지스트리를 바꿔 끼우는 이음매가 새로 필요하다. INLINE 스킬은 얹을 포크가 없으므로 훅이 발화하지 않는다. 훅을 선언한 INLINE 스킬은 거절하지 않고 적재 때 WARN 한다.

**훅이 자기 자원을 찾는 법.** 활성화가 포크에 넘기는 것은 경로가 아니라 스킬의 `StagedResource` 이고 뷰가 훅과 함께 싣는다. 훅은 발화할 때 `context.getHookRegistry()` 에서 자기 자신이 든 층을 **동일성**으로 찾는다. 이름으로 찾지 않는다. `hooks.json` 훅의 가짜 스킬 이름이 활성 스킬과 겹쳐도 그 스킬의 디렉터리를 받으면 안 되기 때문이다. 변수 값은 [변조 경계](hook-tamper-boundary.md)가 소유한다.

**강제할 수 없는 곳.** 사용자 정의 `SkillForkExecutor` 가 `HOOK_REGISTRY` 를 읽지 않으면 그 포크에서 스킬 훅이 발화하지 않는다. `SkillForkExecutor` Javadoc 이 요구 사항으로 적는다. 사용자 정의 도구가 에이전트 단위 러너의 `runInBackground` 를 직접 부르면 스킬 훅이 따라가지 않으며 `WorkflowRunner.runInBackground` Javadoc 이 먼저 `activeSkillGuards` 로 거절하라고 적는다.

**패키지 순환.** 활성화기가 읽는 `HOOK_REGISTRY` 키가 `tools.ToolContextKeys` 에 있어 `skill.hook` 과 `tools` 는 서로 참조한다. 키를 옮기는 API 변경 없이는 풀리지 않으며 순환은 ArchUnit baseline 이 고정한다.

## 12. 끊기는 경로 거절

뷰를 물려줄 수 없거나 뷰가 스킬보다 오래 살아남는 일은 시작하지 않는다. 그대로 두면 스킬이 아직 활성인데 그 하위 트리에서만 가드가 소리 없이 꺼진다. 근거는 [판단하지 못한 가드는 막는다](hook-fail-closed.md) 는 규칙이다.

판정은 **이벤트 기준**이다. 호출 컨텍스트의 레지스트리 사슬에 가드 이벤트의 훅을 선언한 활성 스킬이 있으면 거절한다(`SkillHookSet.hasGuards`, `HookRegistryAccess.activeSkillGuards`). 네 도구 모두 같은 판정이다.

| 경로 | 끊기는 이유 | 가드 이벤트에 훅이 있는 스킬 | advisory 이벤트에만 훅이 있는 스킬 |
|---|---|---|---|
| `Workflow` · `WorkflowJs` background | 에이전트 단위 러너에서 돌아 호출 컨텍스트와 끊긴다 | 거절(`backgroundRefusal`) | WARN 후 실행. 그 하위 트리에서 스킬 훅은 발화하지 않는다 |
| 백그라운드 `Task` | 뷰를 물려받지만 스킬이 끝나면 층이 꺼진다 | 거절(`backgroundTaskRefusal`) | WARN 후 실행 |
| `ScheduleTask` | 루틴은 나중에 런타임 레지스트리에서 돈다 | 거절(`scheduleRefusal`) | WARN 없이 실행 |

**백그라운드 워크플로에 뷰를 실어 보내지 않는다.** 러너 SPI 에 호출별 환경을 받는 자리가 없고, `RunId` 가 요청 내용에서 나와 같은 요청이 진행 중이면 다른 호출자의 실행에 합류하므로 "누구의 스킬 훅" 인지 정할 수 없으며, 실행이 스킬보다 오래 살아 닫힌 뒤에는 어차피 층이 꺼진다. 포그라운드 모드는 뷰를 물려받으므로 모델은 포그라운드로 다시 부를 수 있다. 호출 컨텍스트의 스킬 훅을 물려받는 길은 [EE-63](../../backlog/execution-environment-open-items.md)이 추적한다.

**`failOpen` 은 이 판정을 바꾸지 않는다.** 가드 이벤트의 `failOpen: true` 감사 훅만 있는 스킬(가드 훅은 없다)도 거절한다. 같은 훅이 `hooks.json` 에서는 적용 단계에서 가드로 치지 않는다. 낱말은 [가드 이벤트와 가드 훅](hook-fail-closed.md)으로 구별한다. 거절이 과하게 닫히는 쪽이라 위험은 없고, 비용은 그런 스킬이 백그라운드 일을 시작하지 못하는 것이다. 좁히는 변경의 후보는 [EE-119](../../backlog/execution-environment-open-items.md)가 추적한다.

**데코레이터 한계.** 판정은 컨텍스트의 레지스트리가 `SkillScopedHookRegistry` 자신일 때만 동작한다. 그것을 감싸는 데코레이터는 이 거절을 끈다. `HookRegistryAccess` Javadoc 이 적는다.

## 13. `subagentStart`·`subagentStop` 의 환경

훅은 자기가 다루는 실행의 환경에서 도는데, 이 둘은 시점이 다르다.

- **`subagentStop` 은 포크의 환경을 싣는다.** `DefaultSubagentExecutor` 는 돌려주는 모든 결과에 실행 id 와 함께 포크의 환경을 찍는다(`SubagentExecutionResult.withExecutionEnvironment`). 살아 있는 핸들이지 값이 아니므로 `equals` · `hashCode` · `toString` · `TaskResult.from` 에서 제외한다. 발화는 `result.getExecutionEnvironment().or(launchContext::getExecutionEnvironment)` 를 쓴다.
- **결과에 환경이 없는 경로는 스폰한 쪽 환경으로 떨어진다.** 코드 behavior(스폰한 쪽 환경에서 도므로 그것이 맞는 답이다), 모르는 서브에이전트, 호출자 상한 거절, 매니저가 만든 예외 결과, 풀 거절이다. 그쪽도 없으면 [실행이 없는 발화](#3-hasexecution)다.
- **`subagentStart` 는 스폰한 쪽 환경을 유지한다.** 포크의 환경이 아직 해석되지 않았다. 앞당기면 포크의 `ExecutionId` 를 일찍 발급해야 하고 단일 해석 지점이 옮겨 간다. 포크 환경을 싣는 일은 [EE-110](../../backlog/execution-environment-open-items.md)이 추적한다.
- **종료 순서.** 코어는 포크의 환경을 닫지 않는다. `ExecutionEnvironmentProvider` 에 해제 메서드가 없다. `subagentStop` 은 `execute` 가 돌아온 직후, 제어가 `Task` · `SkillTool` · 워크플로 분기로 돌아가기 전에 발화하므로 `WorktreeMerge` 와 브랜치 정리보다 앞이다. 그래서 훅이 발화할 때 환경은 열려 있다.

스킬 훅은 격리되지 않으므로 포크의 셸 상태에서 시작한다. 격리 실행은 세션 상태를 읽지 않으므로, 포크 환경의 차이가 셸 상태뿐인 제공자에서 `hooks.json` 의 `subagentStop` 은 달라지는 것이 없다.

## 14. 포크 본문의 재렌더

훅의 `AIMON_SKILL_DIR` 과 본문의 값은 다르다. 본문 값은 [스테이징](../tool/execution-environment-staging.md)이 소유한다.

## 15. 압축 경로

압축 훅의 환경은 요청 객체로 흐른다. `ContextRequest` → `CompactionGuardRequest` → `CompactionRequest` · `SummaryRequest` → `DefaultCompactionEngine` 이다.

- `CompactionGuard` 의 입구는 `maybeCompact(CompactionGuardRequest)` 하나를 더한 것이다. 오버로드를 환경 축으로 늘리면 넷이 여덟이 된다.
- 기본 구현은 기존 메서드에 위임하므로 환경이 떨어진다. 재정의하지 않은 서드파티 가드의 압축 훅에는 환경이 비어 가고, 그 발화는 `NO_ENVIRONMENT` 다. 조용하지 않다.
- 훅 레지스트리가 없는 `LlmSkillExecutor` 의 스크래치 루프는 환경을 싣지 않는다. 압축하지 않으므로 압축 훅이 발화하지 않는다.
- `agent.compact` 는 environment 패키지 중 `ExecutionEnvironment` 한 타입만 본다. 참조를 훅 컨텍스트로 옮기기만 하고 파일 시스템·셸·서술자를 읽지 않기 때문이다. 허용 목록을 패키지째 열지 않는다.
- `RecentFilesRestoreHook` 은 읽기에 쓸 `ToolContext` 를 호출자에게서 받는다. 훅이 `ToolContextKeys` 를 import 하면 `hook` 과 `tools` 사이에 순환이 생긴다.

## 16. 종료 순서

스택의 마지막 단계는 `HOOK_EXECUTOR` 이고, `hooks.json` 핫리로드를 배선한 어셈블리(CLI)만 `HOOK_HOT_RELOAD` → `HOOK_EXECUTOR` → `HOOK_CONFIG_SHELL` 로 호스트 셸을 닫는다. 환경의 셸은 제공자 소유라 `AGENT_RESOURCES` 에서 닫힌다. 그 뒤에 발화한 스킬 훅이 닫힌 셸을 만나면 `EXECUTION_FAILED` 로, 일반 규칙대로 읽는다. 스케줄 루틴은 훅을 발화하지 않으므로 이 경로가 아니다. 로컬 셸의 `close()` 는 아무것도 하지 않으므로 로컬에는 닫힌 셸이 없다.

백그라운드 명령과 스택 종료는 [백그라운드 명령](../tool/background-commands.md)이 소유한다.

`확인 필요`: `AGENT_RUNTIMES` 에서 훅 레지스트리가 닫힌 뒤 스킬 훅이 실제로 발화할 수 있는 경로가 남아 있는지, 그리고 닫힌 원격 셸이 무엇을 던지는지.

## 17. 설계 결정

| 결정 | 이유 | 기각한 대안 |
|---|---|---|
| 컨텍스트는 환경 자체를 싣고 서술자를 파생한다 | 환경·서술자·파일 시스템을 읽는 훅마다 필드를 더하지 않는다. 출처가 하나다 | 컨텍스트에 `VirtualShell` 만 싣는다: 훅마다 필드가 늘고 단일 출처를 잃는다. 서술자 필드를 유지하고 환경 필드를 추가한다: 한쪽만 채운 컨텍스트가 생긴다 |
| 실행기는 발화 컨텍스트에서 셸을 얻는다 | 포크는 환경을 따로 해석하고 파싱된 훅은 실행 사이에 공유된다 | 활성화 시점에 셸을 묶는다: 스폰한 쪽 셸이라 틀린 셸을 묶는다. `ThreadLocal` 로 찾는다: 풀 스레드에서 전파가 조용히 끊겨 원인이 안 보인다 |
| 환경이 없으면 호스트로 가지 않는다 | 호스트 폴백은 막으려는 통로다 | `DefaultShellActionExecutor` 의 호스트 폴백. 기본 배치의 `NO_ENVIRONMENT` 를 모두 호스트로 보낸다: "실행 없음" 과 "환경이 닿지 않음" 을 가를 수 없다. 이벤트와 빈 환경으로 실행기 안에서 추론한다: 답을 아는 코드에서 먼 곳에서 같은 모호함을 만든다 |
| 분류는 이벤트의 성질로 `HookEventType` 에 둔다 | 셸 전용 훅만의 성질이 아니다 | `DeclarativeShellHookBinding` 테이블: `preTool` · `postTool` 이 거기 없다 |
| 파싱 시점에 거부한다 | 실행기가 실패를 삼키면 조용하다 | 발화 시점에만 막는다 |
| `hooks.json` 은 실행의 환경에서 돈다 | 정책 훅이 지키는 워크스페이스를 봐야 한다 | 호스트에 두고 문서화한다: 정책 훅이 워크스페이스를 보지 못한다 |
| 호출별 옵션과 기능 비트로 격리한다 | 플래그 하나와 상수 하나로 작고, 코어가 격하 대신 거절할 수 있다 | `isHook()` 만 쓴다: 에이전트 상태가 훅을 조종한다. `ExecutionEnvironment.isolatedShell()` 기본 메서드: 재정의하지 않은 제공자에서 조용히 상태를 읽는다. WARN 과 함께 비격리로 돌린다: 같은 무결성 손실이 조용히 일어난다 |
| 코어는 `PATH` 를 정하지 않는다 | [§7](#7-격리-실행) | 상수로 고정한다: 로컬 동작이 바뀌고 제공자마다 맞지 않는다. 핸들러별 `env` · `path` 키: 에이전트가 쓸 수 있는 프로젝트 층에서 기본값보다 강하지 않다 |
| 호스트 경로는 힌트만 준다 | 로드 시점에 호스트 여부를 모른다 | 로드 시점에 거부한다: 로컬 제공자를 깨뜨린다. 명령이 언급한 경로를 자동으로 싣는다: 셸 텍스트 파싱은 믿을 수 없고 명시적 변수는 예측할 수 있다 |
| `"shell": "environment"` 의 실행 밖 이벤트는 WARN 후 건너뛴다 | `HookHandlerSpec` 은 자기 이벤트를 모르고 셋 다 가드가 아니라 잃는 가드가 없다 | 파싱 오류 |
| 모르는 `"shell"` 값은 파싱 실패다 | 오타가 핸들러를 말없이 옮기면 안 된다 | 관대하게 읽고 기본값을 유지한다 |
| 격리 실행은 기반 환경의 셸에서 돈다 | 상대 경로와 병합이 깨지지 않는다 | 브랜치 뷰에서 돌린다: [§7](#7-격리-실행). 기반의 `descriptor().workingDirectory()` 를 브랜치 셸에 준다: 서술자는 프롬프트 글이고 셸이 받는 경로라는 보장이 없다 |
| 포크 환경을 결과에 찍는다 | 모든 출구가 지나는 `withExecutionId` 선례가 있다 | 실행기가 채우는 가변 홀더: 매니저와 실행기 사이에 공유 가변 상태가 생긴다. 매니저에서 먼저 해석한다: `subagentStart` 에 포크 환경이 가고 해석 지점이 옮겨 간다 |
| 스킬 훅은 뷰로 범위를 정한다 | 구조로 정해져 id 비교가 없다 | 공유 레지스트리에 넣고 실행 id 로 거른다: [§11](#11-스킬-훅-범위). 세션을 범위로 한다: 세션 없는 실행이 빠지고 형제 포크가 서로의 훅을 맞는다. 활성 상태를 저장소로 공유한다: 소비자가 없고 훅 인스턴스는 직렬화 대상이 아니다. `SkillForkExecutor.fork` 에 레지스트리 인자를 더한다: 공개 SPI 가 바뀌고 중첩 스폰에는 어차피 키가 필요하다 |
| 끊기는 경로는 거절한다 | 판단을 보장할 수 없으면 막는다 | 백그라운드 워크플로에 뷰를 실어 보낸다: [§12](#12-끊기는-경로-거절). 가드 손실을 문서화만 한다: 가드가 신호 없이 꺼진다 |
| 거절 판정은 이벤트 기준이다 | 과하게 닫히는 쪽이라 지금 위험이 없다 | `failOpen` 훅을 판정에서 뺀다: 코드 변경이 필요하고, 과하게 닫히는 쪽이라 급하지 않다([EE-119](../../backlog/execution-environment-open-items.md)) |

## 18. 하지 말 것

- **훅 실행기가 셸을 생성자로 쥐지 말 것.** 스킬을 파싱할 때 묶은 셸은 호스트의 것이다. 예외는 `HostShellActionExecutor` 하나이고 그것을 스킬 파서에 넘기면 안 된다. `PackageDependencyArchitectureTest.skillHooksHoldNoShell` 이 강제한다.
- **환경에 놓인 `hooks.json` 명령을 격리 없이 돌리지 말 것.** 모델의 `export` 로 조종되는 정책 훅은 정책이 아니다.
- **스킬 훅을 런타임의 `HookRegistry` 에 등록하지 말 것.** 그 레지스트리는 같은 에이전트의 모든 세션이 디스패치한다.
- **포크를 띄우는 코드에서 생성자의 레지스트리를 먼저 쓰지 말 것.** `HookRegistryAccess.of(toolContext)` 가 먼저다. 아니면 스킬의 가드가 그 하위 트리에서 조용히 꺼진다.
- **실행이 있는데 환경이 없는 발화를 실행 없음으로 표시하지 말 것.** 환경을 넘기도록 고친다. 호스트로 보내려고 `hasExecution` 을 거짓으로 답하면 안 된다.
- **`HookContext` 를 감싸는 뷰에서 `hasExecution()` 을 떨구지 말 것.** 위임한다.
- **`"shell"` 기본 배치를 정적 호스트로 고정하지 말 것.** 실행이 없는 발화만 호스트다.
- **`SkillScopedHookRegistry` 를 데코레이터로 감싸지 말 것.** 끊기는 경로의 거절이 꺼진다.

## 19. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 내용 | 타입 | 경로 |
|---|---|---|
| 실행 안·밖 분류 | `HookEventType` | `hook/HookEventType.java` |
| 컨텍스트의 환경, `hasExecution` | `HookContext`, `SubagentStartContext`, `SubagentStopContext` | `hook/execution/HookContext.java`, `hook/event/` |
| 실행기 | `ShellActionExecutor`, `DefaultShellActionExecutor`, `IsolatedShellActionExecutor`, `HostShellActionExecutor`, `ExecutionOrHostShellActionExecutor`, `ShellActionRunner`, `EnvironmentShells` | `skill/hook/declarative/` |
| 배치 | `HookRegistryApplier`, `HookHandlerSpec`, `HookHotReloadBootstrap` | `config/hook/` |
| `AIMON_HOOK_DIR` | `HookScriptDirectory`, `ConfigHookDirectory` | `skill/hook/action/`, `skill/hook/declarative/` |
| 파싱 거부 | `SkillHookSetParser`, `SkillHookSet`, `DefaultSkillRegistry` | `skill/parser/`, `skill/hook/`, `skill/` |
| 스킬 훅 범위 | `SkillScopedHookRegistry`, `ScopedSkillHookActivator`, `SkillHookActivator`, `SkillHookDirectory` | `skill/hook/`, `skill/hook/declarative/` |
| 전달과 거절 | `ToolContextKeys`, `HookRegistryAccess`, `TaskTool`, `WorkflowTool`, `ScheduleTaskTool`, `SubagentBackedSkillForkExecutor` | `tools/`, `tools/task/`, `tools/workflow/`, `tools/scheduling/`, `skill/fork/` |
| 포크의 환경 | `DefaultSubagentExecutionManager`, `DefaultSubagentExecutor`, `SubagentExecutionResult` | `subagent/`, `subagent/execution/` |
| 압축 경로 | `CompactionGuard`, `CompactionGuardRequest`, `DefaultCompactionEngine`, `ContextRequest` | `agent/compact/`, `agent/context/` |
| 다른 모듈 | `GraalJsWorkflowTool` | `modules/aimon-graaljs/` |
| CLI 조립 | `AgentSetupFactory` | `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java` |
| 종료 순서 | `TeardownPhase` | `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/TeardownPhase.java` |
| 아키텍처 규칙 | `PackageDependencyArchitectureTest` | `modules/aimon-core/src/test/java/at/aimon/core/architecture/` |
