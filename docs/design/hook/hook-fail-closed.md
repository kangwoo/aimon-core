# 판단하지 못한 가드 (Hook Fail-Closed)

> **Status**: IMPLEMENTED — `aimon-core` 의 `at.aimon.core.skill.hook.declarative` · `config.hook` · `subagent.execution` 과 `aimon-cli`. 열린 항목은 [등록부](../../backlog/execution-environment-open-items.md)의 EE-74 다.

가드가 답을 내지 못했을 때 무엇이 일어나는가의 정본이다. exit 2 와 결과 모델은 [훅 시스템](hook-system.md), 인터럽트와 취소는 [훅과 인터럽트](hook-cancellation.md), 명령이 도는 자리는 [훅 환경](hook-environment.md), 변조는 [변조 경계](hook-tamper-boundary.md)가 소유한다.

## 1. 목적과 이벤트 분류

규칙이 닿아야 하는 자리는 셋이다. 명령을 돌리지 못한 가드, 포크의 `onStart`, 시작 시 로드되지 않는 `hooks.json` 이다. 어느 하나라도 통과로 읽으면 그것이 가드를 끄는 방법이 된다.

이벤트 분류는 이 표가 정본이다. 코드의 단일 출처는 `SkillHookSet.guardEvents()` · `reportEvents()` 와 `HookEventName.isGuard` 다.

| 분류 | 이벤트 | 답을 얻지 못하면 | 인터럽트 |
|---|---|---|---|
| 가드 이벤트 (4) | `onStart`, `preTool`, `permissionRequest`, `preCompact` | 막는다. `failOpen` 이면 통과 | 명령이 멈추고 막는다 |
| advisory · 보고 (6) | `postTool`, `onStop`, `subagentStart`, `subagentStop`, `permissionDenied`, `postCompact` | WARN 후 진행 | 명령이 끝까지 돈다 |
| advisory · 실행 밖 (3) | `onSessionStart`, `onSessionEnd`, `onConfigReload` | WARN 후 진행 | 설 수 있는 신호가 없다 |

- 판정을 말할 때는 **advisory**(결정 채널이 없는 아홉 전부)라고 쓴다. 인터럽트를 말할 때는 **보고**(여섯)라고 쓴다. 판정은 아홉 전부에, 인터럽트 규칙은 여섯에만 적용되므로 두 말을 합치지 않는다.
- **가드 이벤트**는 이벤트의 성질이다. **가드 훅**은 가드 이벤트의 `failOpen` 아닌 훅이다. 스킬의 끊기는 경로 거절은 이벤트 기준이고, `hooks.json` 의 적용 단계 판정은 훅 기준이다([§7](#7-로드-실패), [훅 환경](hook-environment.md)의 끊기는 경로 거절).
- `HookFeedback.collectAdvisory` 의 advisory 는 block 이 아닌 훅 피드백이라는 다른 뜻이다.
- 표의 인터럽트 칸은 선언적 훅의 셸 명령의 규칙이다. 코드로 쓴 훅은 스스로 정한다([훅과 인터럽트](hook-cancellation.md)).

## 2. 규칙과 이유

**판단하지 못한 가드는 막는다. 다만 판단할 수 있고 해롭지 않은 상황을 기동 실패로 만들지 않는다.**

가드 입장에서 timeout 과 환경 장애는 구별되지 않는 같은 사건("답을 못 들었다")이다. timeout 만 통과시키면 느리게 만드는 것으로 가드를 끌 수 있고, 환경 장애를 통과시키면 환경을 떨어뜨리는 것으로 끌 수 있다.

"스크립트가 틀린 답을 냈다" 와 "답을 못 들었다" 를 가른다. 앞쪽을 막으면 깨진 감사 스크립트가 게이트키퍼가 된다.

| 셸 명령의 결과 (가드 이벤트) | 읽는 법 |
|---|---|
| exit 0 | 허용 |
| exit 2 | 거부. `failOpen` 이 약하게 하지 않는다 |
| exit 126 · 127 | 답을 못 들은 것 |
| 그 밖의 non-zero | WARN 후 허용 |
| 종료 코드 없음 | 답을 못 들은 것 |

가드 이벤트에서 exit 126 · 127 은 답을 못 들은 것으로 읽는다. 가드 스크립트가 없는 환경에서 가드가 통과로 바뀌는 쪽이 더 나쁘다. 스크립트가 스스로 그 코드를 내면 함께 막히며, 그 비용은 받아들인다.

`http` · `mcp` 핸들러도 같은 규칙을 탄다. `preTool` 의 두 실행기는 판정과 판정 없음을 갈라 보고하고(`ActionCallOutcome`), 판정 없음을 같은 자리에서 읽는다. `postTool` 은 WARN 후 진행한다. 실행기 배선과 기본 HTTP 클라이언트는 [훅 시스템](hook-system.md#55-http--mcp-실행기는-조립이-준다)이 소유한다.

**판정은 한 곳에서 내린다.** `ShellHookVerdicts.guard` 다. 실행기는 이벤트에 결정 채널이 있는지 모르므로 원인만 보고한다(`ShellHookOutcome.notRun(cause, detail)`). 인자 없는 "못 돌렸다" 는 없다. 원인을 인자로 강제해야 새 분기가 통과 쪽으로 다시 생기지 않는다. 변환은 훅의 `vetoResult` 가 값을 낼 때만 일어나므로(`canVeto()`) advisory 이벤트는 구조적으로 영향이 없다.

**두 프런트엔드에 같은 규칙을 적용한다.** `hooks.json` 과 스킬 frontmatter 는 같은 훅 클래스를 쓰므로 규칙을 가르려면 출처를 클래스에 실어야 하고, 같은 이벤트와 같은 exit 계약이 출처에 따라 반대로 읽힌다. 운영자가 쓴 가드는 스킬 가드보다 가드일 가능성이 높다. 비용은 운영자의 가드가 timeout 과 셸 실패에 막힌다는 것이고, 실행 안의 `hooks.json` 은 환경에서 돌므로 환경 장애에도 막힌다.

**실행기 수준의 두 길도 막는다.**

- 던지는 선언 가드도 막는다. 셸 분기를 감싸 `EXECUTION_FAILED` 로 읽는다. 그러지 않으면 정책 기본 예외 매퍼가 가드에서 성공을 낸다.
- 선언 가드는 `getTimeoutBehavior()` 로 `FAIL_CLOSED` 를 선언한다. 정책 기본값은 프로그램 훅 전체에 번지므로 바꾸지 않고, 선언이 없는 프로그램 훅은 정책을 따른다. `failOpen` 을 선언한 훅도 이 선언을 내지 않아 정책을 따른다.

**비용.** 환경 제공자가 실패한 실행에서는 셸 가드가 걸린 도구가 환경을 쓰지 않는 것까지 막힌다. 가드가 꺼진 채 실행되는 것의 반대쪽이고 의도한 동작이다.

## 3. `failOpen` 과 무시되는 원인

훅별 `failOpen: true` 로만 푼다. 기본은 `false` 다. 같은 `preTool` 이 가드와 감사 두 용도로 쓰이고 선언만으로는 구별되지 않으므로, 옵션이 없으면 감사 훅 하나가 환경 장애를 전면 장애로 키운다. 훅 단위인 이유는 스킬 전체나 전역 스위치로 두면 가드 하나를 살리려는 사람이 다른 가드까지 열기 때문이다. `failOpen` 은 **관찰용 handler** 가 가드 이벤트 위에서 쓰는 선언이다.

`failOpen` 은 답이 없을 때만 뜻이 있다.

- exit 2 를 내는 훅은 `failOpen` 이어도 막는다. 그래서 `failOpen` 은 포크를 빼는 스위치가 아니다. 포크를 빼는 방법은 훅 안에서 `AIMON_INVOKER_TYPE` 으로 가르는 것이다.
- `failOpen` 은 답을 내지 못하는 액션에서만 뜻이 있다. `deny` 는 언제나 판정이 있으므로 WARN 후 무시한다.
- 막을 수 없는 이벤트의 `failOpen` 은 스킬 파서가 WARN 한다. `hooks.json` 에서는 읽히지 않을 뿐 해가 없다.

**무시되는 원인은 둘이다.** 둘 다 인프라 실패가 아니므로 `failOpen` 이 열지 않는다.

- `CANCELLED`. 실행이 끝나는 중이고 판정이 없으면 진행 허가도 없다. 자세한 것은 [훅과 인터럽트](hook-cancellation.md)가 소유한다.
- `HOOK_FILES_TAMPERED`. 공격 증거이고, 열어 두면 파일을 건드리는 것으로 모든 `failOpen` 가드를 끌 수 있다. 근거는 [변조 경계](hook-tamper-boundary.md)의 변조 표지 절이다.

**불리언만 받는다.** `failOpen` 은 가드를 푸는 키이므로 느슨하게 읽히면 안 된다.

| 프런트엔드 | 불리언이 아닌 값 |
|---|---|
| 스킬 frontmatter | 파싱 오류. 스킬 하나만 빠지고 다른 스킬의 가드는 남는다 |
| `hooks.json` | `false` 로 읽고 핸들러를 남긴다. 원문은 `HookHandlerSpec.getRejectedFailOpen()` 에 두고 `HookConfigLoader` 가 파일 · 이벤트 · 핸들러를 밝혀 WARN 한다 |

`hooks.json` 쪽을 기동 실패로 만들지 않는 이유는 가드가 닫힌 채 남아 판단할 수 있고 해롭지 않기 때문이다. Jackson 의 기본 강제 변환을 쓰지 않으므로(`"true"`, `1`, `null`, `[true]` 모두 `false`) 잘못 쓴 값이 가드를 열지 않는다. 비용은 `"failOpen": "true"` 로 쓴 감사 훅이 의도와 달리 가드로 동작하는 것이고, 그 신호는 WARN 한 줄이다.

## 4. `Unrun` 표

`ShellHookOutcome.Unrun` 은 16개다. 어느 원인이든 advisory 이벤트에서는 WARN 후 진행한다. 아래 "가드" 칸은 가드 이벤트에서의 결과다.

| 상수 | 생기는 자리 | 가드 |
|---|---|---|
| `NO_ENVIRONMENT` | 컨텍스트에 환경이 없다. 호스트로 되돌아가지 않는다 | 막음, `failOpen` 통과 |
| `ENVIRONMENT_UNAVAILABLE` | 환경이 사용 불가이거나 셸을 주지 않았다 | 막음, `failOpen` 통과 |
| `STAGING_FAILED` | 스킬 디렉터리를 실행 디렉터리로 읽거나 스테이징하지 못했다. 둘 수 없는 이름 포함 | 막음, `failOpen` 통과 |
| `HOOK_DIR_STAGING_FAILED` | `hooks/` 를 실행용으로 읽거나 둘 수 없다 | 막음, `failOpen` 통과 |
| `ISOLATION_UNSUPPORTED` | 셸이 `ISOLATED_EXECUTION` 을 선언하지 않았다 | 막음, `failOpen` 통과 |
| `RUN_DIRECTORY_UNSUPPORTED` | `AIMON_HOOK_DIR` 을 쓰는 핸들러인데 셸이 `RUN_DIRECTORY` 를 선언하지 않았다 | 막음, `failOpen` 통과 |
| `HOOK_FILES_TAMPERED` | 셸이 놓은 파일이나 입력이 받은 바이트와 다르다 | **언제나 막음** |
| `SHELL_UNSUPPORTED` | 실행기가 셸 액션을 돌리지 않는다 | 막음, `failOpen` 통과 |
| `TIMEOUT` | 명령이 timeout 안에 끝나지 않았다 | 막음, `failOpen` 통과 |
| `EXECUTION_FAILED` | 종료 코드 전에 셸이 실패했다. 실행 디렉터리를 만들거나 쓰지 못한 경우, 닫힌 셸, 던지는 선언 가드 포함 | 막음, `failOpen` 통과 |
| `CANCELLED` | 실행의 인터럽트로 명령이 멈췄다 | **언제나 막음** |
| `COMMAND_NOT_EXECUTABLE` | exit 126. 가드 이벤트에서만 이렇게 읽는다(`asGuardAnswer()`) | 막음, `failOpen` 통과 |
| `COMMAND_NOT_FOUND` | exit 127. 가드 이벤트에서만 이렇게 읽는다 | 막음, `failOpen` 통과 |
| `EXECUTOR_NOT_WIRED` | `http` · `mcp` 액션의 실행기가 없다 | 막음, `failOpen` 통과 |
| `CALL_FAILED` | `http` · `mcp` 호출이 답을 받지 못했다. 닿지 못함, 2xx 아닌 상태, 서버 미등록, 도구 오류 | 막음, `failOpen` 통과 |
| `INVALID_RESPONSE` | 답을 판정으로 읽을 수 없다 | 막음, `failOpen` 통과 |

`HOOK_DIR_STAGING_FAILED` 의 이름과 사유 문구("hook script directory could not be staged")에는 스테이징이라는 말이 남아 있지만, 뜻은 `hooks/` 를 실행용으로 읽거나 둘 수 없다는 것이다. 읽을 수 없음, 상한 초과, 읽는 중 변경, 둘 수 없는 이름이다. 공개 상수이므로 이름을 유지하고 뜻은 여기에 적는다.

스테이징 실패면 명령을 돌리지 않는다. 변수 없이 돌리면 다른 경로를 부르는 다른 명령이 되고, 막히더라도 사유가 실제 원인이 아니라 command not found 가 된다.

## 5. 사유 정책

사유에는 원인만 싣는다. 명령 문자열은 비밀이 들어갈 수 있어 싣지 않고, `failOpen` 으로 푸는 법은 읽는 쪽이 가드를 받는 포크이므로 싣지 않는다. 그 포크는 `Edit` · `Write` 와 스테이징된 스킬 사본을 쥐고 있을 수 있다. 푸는 법은 WARN 로그와 문서에만 있다.

```text
Blocked: guard hook '<hook>' (<event>) could not run its command — <cause>: <detail>. A guard that cannot decide blocks (fail-closed).
```

- `http` · `mcp` 는 "run its command" 자리가 "get a verdict from its http call" · "get a verdict from its mcp call" 이다.
- `<cause>` 는 `Unrun.description()` 이다. `TIMEOUT` 의 detail 은 `no exit status within <ms>ms` 형태다.
- 셸 실패의 detail 은 클래스 이름뿐이다. `LocalShell` 의 예외 메시지가 명령을 싣기 때문이다. `shell()` 이 사용 불가 예외가 아닌 것을 던진 `ENVIRONMENT_UNAVAILABLE` 도 같다.
- `ExecutionEnvironmentUnavailableException` 의 메시지는 싣는다. 도구가 모델에게 이미 보여 주는 값이고 그것이 원인이다.
- `CANCELLED` 와 `HOOK_FILES_TAMPERED` 는 고정 문구이고 detail 이 없다. 셸의 메시지는 파일을 말하므로 WARN 에만 남는다.
- 사유는 `MAX_DENY_REASON_LENGTH`(4000자)로 자른다. 훅이 스택트레이스를 통째로 뱉어 대화 컨텍스트에 무제한 주입되는 것을 막는다.
- 빌더가 내는 둘 수 없는 이름의 메시지는 제어 문자를 이스케이프한 상대 경로만 싣는다([변조 경계](hook-tamper-boundary.md)의 빌더 거부 절).

## 6. 포크 `onStart` block

포크는 `onStart` block 에서 루프를 시작하지 않고 실패 결과로 끝난다. 메인 실행은 block 이면 `ExecutionBlockedByHookException` 을 던져 턴을 시작하지 않는다. 포크의 모든 종료 경로는 결과 객체이고, 실패 결과는 모든 스폰 경로에서 이미 부모에게 전달된다. 그래서 새 전달 경로가 필요 없다.

판정은 `SubagentOnStartGate` 한 곳에 있고 ReAct 포크와 코드 behavior 포크가 함께 쓴다. 선언 `onStart` 훅은 exit 2 와 "돌리지 못함" 을 같은 `HookResult.block` 으로 낸다.

| 항목 | 결정 | 이유 |
|---|---|---|
| 결과 | `success=false`, `CompletionReason.BLOCKED`, iteration 0, 토큰 0, `errorMessage` 에 사유 | 호출자가 훅의 거절을 실패와 가를 수 있다. 값을 모르는 옛 노드는 `JsonTaskResultCodec` 이 실패 결과에서 `ERROR` 로 읽으므로 전과 같이 본다 |
| `onStop`(포크 안) | 발화하지 않는다 | 시작하지 않은 실행에는 멈춤이 없다. 메인과 같다 |
| `subagentStop`(스폰한 쪽) | `success=false` 와 사유로 발화 | 스폰한 실행의 이벤트이고 `subagentStart` 는 이미 나갔다 |
| LLM 호출 · 도구 | 없다 | 검사가 루프 진입 전이다 |
| block 과 함께 온 피드백 | 버린다 | 읽을 모델이 없다. 메인도 던지기 전에 붙이지 않는다 |
| 스냅숏 | goal 을 더하기 전 | 막힌 포크의 결과는 goal 을 더하기 전의 스냅숏을 싣는다. 넣으면 백그라운드 포크가 재개용으로 저장해 거부된 goal 이 다음 재개 때 모델에 재생된다. 새 포크는 빈 기록이라 저장되지 않고 재개할 수 없다 |
| 재개 | 재개된 포크도 `onStart` 를 다시 맞고 block 이면 같은 결과 | 재개마다 발화한다 |
| 백그라운드 `Task` | `FAILED`, 알림 detail 에 사유. 부모는 계속된다 | 시작을 되돌릴 수는 없고 보장은 "그 포크가 일하지 않았다" 이다 |
| 워크플로 단계 | 그 단계만 실패 | fan-out 의 각 포크는 독립 실행이다 |
| 스킬 포크 | 스킬 범위는 기존 경로로 닫힌다 | 결과가 예외가 아니다 |

**코드 behavior 포크.** 코드 behavior 포크도 `SubagentOnStartGate` 로 같은 판정을 받는다. 차이는 둘이다. 자기 환경을 풀지 않으므로 훅은 스폰한 실행의 환경을 받고, 붙일 대화가 없어 block 이 아닌 피드백은 버린다.

**취소와의 겹침.** ReAct 포크는 취소가 먼저다. `INTERRUPTED` 로 끝나고 `onStop(false)` 가 발화한다. 취소가 만든 block 은 보고하지 않는다. 코드 behavior 포크는 신호를 받지 않아 `BLOCKED` 로 끝난다([EE-99](../../backlog/execution-environment-open-items.md)). 자세한 것은 [훅과 인터럽트](hook-cancellation.md#42-포크)가 소유한다.

**여러 훅.** `onStart` 정책은 멈추지 않으므로 전부 돌고, 사유는 block 한 것만 모인다.

**프로그램 훅.** 프로그램으로 등록한 `onStart` 훅이 던지거나 실행기 수준 timeout 이 터지면 정책에 따라 성공으로 읽힌다. 메인과 포크가 같다. 선언 가드는 [§2](#2-규칙과-이유)의 두 길로 막는다.

**운영자의 `onStart`.** `hooks.json` 의 `onStart` 는 포크의 goal 에도 걸린다. 사용자 입력만 검사하려면 훅 안에서 `AIMON_INVOKER_TYPE` 으로 가른다.

## 7. 로드 실패

시작 시 로드 실패는 예외로 전파되어 기동을 멈춘다(`HookRegistryReloader.loadInitial`, `HookHotReloadBootstrap.Builder.start`). 한 계층의 오타가 모든 계층의 파일 가드를 끈 채 에이전트가 도는 것을 막는다. 예외는 `HookConfigParseException` 하나이고 "읽지 못한 경우도 포함" 이다.

**"없음" 과 "알 수 없음" 을 가른다.** 판정은 `Files.readAttributes` 의 결과로 한다.

| 상황 | 처리 |
|---|---|
| `NoSuchFileException`, 대상 없는 심볼릭 링크, 파일과 `.aimon` 없음 | 부재. 정상 기동 |
| 경로의 조상이 일반 파일이다(`~/.aimon` 이 파일) | 부재와 그 파일을 가리키는 WARN. 그 자리에 설정 파일이 있을 수 없다 |
| 그 밖의 `IOException`(검색 불가 디렉터리 등) | 기동 실패. 존재를 알 수 없으므로 "훅 없음" 으로 읽지 않는다 |
| 비정규 파일, 읽기 오류 | 기동 실패(`could not be read`) |
| 깨진 JSON, 모르는 `type`, 0 이하 timeout, 모르는 `"shell"` | 기동 실패(`is invalid`). 다른 계층이 멀쩡해도 뜨지 않는다 |
| 0 바이트, 공백만, `null` 인 파일 | 훅 없는 계층. 정상 기동 |

"디렉터리가 아님" 을 알리는 예외는 OS 마다 다르고 이유 문자열은 지역화될 수 있어 조상 검사로 가른다.

메시지는 `hooks config <절대경로> (<USER|PROJECT|LOCAL> layer) is invalid: <파서 메시지>` 또는 `could not be read: ...` 형태이고, 파서 메시지는 원문 인용 없이 줄 · 열만 붙인다. 로더에서 붙이므로 핫 리로드의 실패 사유도 같은 정보를 갖는다.

**적용 단계.** 파싱되어도 쓸 수 없는 항목이 있다(`command` 없는 핸들러, `preTool` 밖의 `deny`, 잘못된 URL, 파싱되지 않는 matcher, 이벤트가 받지 않는 핸들러 종류, 조립이 배선하지 않은 실행기, 셸 미지원 실행기의 `command`). 가드 이벤트 아래에서 적용할 수 없는 항목은 시작을 멈추고 리로드는 이전 설정을 유지한다. advisory 이벤트와 `failOpen` 을 선언한 핸들러는 WARN 후 건너뛴다. 불리언 아닌 `failOpen` 은 `false` 로 읽는다. 가드 이벤트의 이름 오타도 시작 실패다. 적용 실패는 롤백 후 던지므로 레지스트리는 손대지 않은 상태다.

**리로드.** 실패해도 이전 설정을 유지하고 `OnConfigReload(successful=false)` 를 낸다. 편집 도중 저장을 포함한다. 감시자 시작 실패는 WARN 후 핫 리로드 없이 기동한다. 가드는 이미 적용되었다.

**옛 표면.** `HookRegistryReloader.bootstrap()` 과 `Started.isBootstrapSucceeded()` 는 `@Deprecated` 다. 앞은 `loadInitial()` 을 부르고 `true` 를 돌려주고 뒤는 상수 `true` 다.

## 8. 탈출구 없음

"깨져도 띄운다" 설정 스위치를 두지 않는다.

1. 탈출구는 이미 있다. 오류 메시지가 가리키는 파일을 고치거나 치운다. 파일이 없으면 정상 기동이다.
2. 스위치는 막으려는 상태(모든 계층의 가드가 꺼진 채 실행)를 설정 한 줄로 되살리고, 한번 켜지면 남는다.
3. 임베딩 호스트에게는 코드 수준의 명시적 탈출구가 있다. `start()` 를 `try/catch (HookConfigParseException)` 로 감싼다. 기본은 막고 풀려면 코드를 쓴다.
4. 필요가 확인되면 빌더 옵션을 더하는 것은 호환 변경이다. 등록은 [EE-74](../../backlog/execution-environment-open-items.md)가 추적한다.

## 9. 경로별 동작

### 9.1 로드

트리 안에서 `hooks.json` 을 배선하는 조립은 CLI 하나다.

| 경로 | 동작 | 보이는 것 |
|---|---|---|
| `HookHotReloadBootstrap.start()` (코어) | `HookConfigParseException` 전파. 파일 훅 없음, 감시자 없음 | 예외 메시지 |
| CLI (`AgentSetupFactory`) | `startHookHotReload` 가 예외를 `ConfigurationException` 으로 감싸고, 던지기 전에 스택을 닫고 메모리 큐를 따로 멈춘다. `Error` 도 스택을 닫고 다시 던진다 | `Configuration error: hooks config ... - fix or remove the file and start again`, 설정 오류 종료 코드. REPL 은 뜨지 않는다 |
| `aimon-bootstrap` | `hooks.json` 을 배선하지 않는다. 호스트가 부른 `start()` 가 던진다 | 호스트의 기동 실패 |
| `aimon-spring-boot-starter` | 배선하지 않는다. 호스트의 `@Bean` 에서 던지면 컨텍스트가 뜨지 않는다 | Spring 기동 실패. 경로를 실은 예외는 원인 사슬에 있다 |

메모리 큐를 따로 멈추는 이유는 던지는 시점이 큐가 스택에 등록되기 전이기 때문이다. `stop()` 은 멱등이다.

영속 예약 작업 저장소를 쓰는 호스트는 스케줄링 시작 전에 `hooks.json` 을 올린다. `AimonStack.start()` 는 런타임 직후 스케줄링을 시작하므로, 순서를 어기면 저장된 루틴이 가드 없이 발화할 수 있다. `LiveSessionOpener` Javadoc 이 적는다.

### 9.2 발화

판정은 [§4](#4-unrun-표)로 한 번만 정한다. 아래 표는 같은 실패가 경로마다 어느 원인이 되는지만 적는다.

| 상황 | 스킬 훅 | `hooks.json` 환경 배치 |
|---|---|---|
| 컨텍스트에 환경이 없다 | `NO_ENVIRONMENT` | `NO_ENVIRONMENT`. 실행이 없는 기본 배치는 호스트로 간다 |
| 환경이 사용 불가다 | `ENVIRONMENT_UNAVAILABLE` | `ENVIRONMENT_UNAVAILABLE` |
| 스크립트 디렉터리를 줄 수 없다 | `STAGING_FAILED`. 상한 초과와 `RUN_DIRECTORY` 미선언은 먼저 [스테이징으로 후퇴](hook-tamper-boundary.md)한다 | `HOOK_DIR_STAGING_FAILED`, `RUN_DIRECTORY_UNSUPPORTED` |
| 셸이 격리를 지원하지 않는다 | 해당 없음. 격리하지 않는다 | `ISOLATION_UNSUPPORTED` |
| 호스트 배치 | 해당 없음 | 환경을 쓰지 않으므로 환경 원인이 생기지 않는다. `TIMEOUT`, `EXECUTION_FAILED` 는 같다. rewake 재발화가 환경 없이 불러도 돌고, 그 결과는 어떤 도구도 막지 않는다 |

`hooks.json` 의 `onStart` 는 `command` 핸들러만 받는다.

## 10. 실패 모드

| 상황 | 처리 |
|---|---|
| 환경 장애 중 가드가 걸린 스킬 포크 | 그 포크의 모든 도구 호출이 사유와 함께 막힌다. 도구 호출이 전부 실패한 iteration 이 쌓이면 `StalledIterationGuard` 가 예산보다 먼저 루프를 끊는다 |
| `onStart` 가드가 환경 장애로 턴 전체를 막는다 | 의도한 동작. 사유가 사용자에게 간다 |
| `preCompact` 가드가 못 돌아 압축이 건너뛰어진다 | 자동 압축이 계속 막히면 컨텍스트가 찬다. exit 2 거부와 같은 경로이고 새 처리를 두지 않는다. 감사 용도의 `preCompact` 훅은 `failOpen: true` 를 쓴다 |
| 포크 `onStart` 셸을 돌리지 못함 | [§6](#6-포크-onstart-block) 의 block. `failOpen: true` 면 통과 |
| CLI 기동 실패 | [§9.1](#91-로드) |

## 11. 설계 결정

| 결정 | 이유 | 기각한 대안 |
|---|---|---|
| 답을 못 들은 가드는 원인과 무관하게 막는다 | 어느 원인이든 통과시키면 그것이 가드를 끄는 길이다 | 실행기가 거부를 낸다: 실행기는 결정 채널을 모른다. timeout 은 통과시킨다: 느리게 만들어 끌 수 있다. `HookExecutionPolicy` 의 기본값을 `FAIL_CLOSED` 로 바꾼다: 프로그램 훅 전체에 번진다 |
| 훅별 `failOpen` | 같은 이벤트가 가드와 감사 두 용도다 | 옵션 없음: 감사 훅이 전면 장애를 만든다. 스킬 단위 · 전역 옵션: 가드 하나를 위해 다른 가드까지 연다 |
| `hooks.json` 에도 적용한다 | 같은 클래스와 같은 계약이다 | 스킬로만 줄인다: 같은 선언이 출처에 따라 반대로 읽힌다 |
| 불리언 아닌 `failOpen` 을 `false` 로 읽는다 | 가드가 닫힌 채 남는다 | 강제 변환: 오타가 가드를 연다. 핸들러를 뺀다: 잘못 쓴 진짜 가드가 사라진다. 기동 실패로 만든다: 판단할 수 있고 해롭지 않은 상황이다 |
| 포크 block 은 전용 실패 결과다 | 새 전달 경로가 필요 없다 | 예외를 `execute()` 밖으로 전파한다: 매니저가 오류 접두사로 감싸고 `SubagentExecutor` 호출자에게 새 예외 계약이 된다. `createFailureResult` 재사용: `onStop` 이 발화해 메인과 어긋난다. 스폰 도구가 `ToolResult.error` 로 번역한다: 스폰 지점마다 같은 번역을 하고 한 종류의 실패만 모양이 달라진다. advisory 로 확정한다: 가드가 가드가 아니게 된다 |
| 시작 로드 실패는 기동을 멈춘다 | 가드 없이 돌지 않는다 | 실패한 계층만 빼고 적용한다: 빠진 계층이 가드를 가진 계층일 수 있다. 모든 가드 이벤트를 막는 상태로 기동한다: 원인을 찾기 어렵고 합성 가드와 그것을 푸는 리로드 경로라는 새 상태가 생긴다. `bootstrap()` 만 `false` 를 유지하고 `start()` 만 던진다: 원인 예외가 오지 않고 직접 부르는 호스트가 fail-open 으로 남는다. 새 예외 타입: 구분해 쓸 독자가 없다. 플래그 · 환경 변수: [§8](#8-탈출구-없음) |
| 읽을 수 없는 파일도 로드 실패다 | `chmod 000` 으로 가드가 꺼지면 같은 실패다 | 부재로 친다 |

## 12. 하지 말 것

- **종료 코드를 얻지 못한 셸 훅을 "통과" 로 보고하지 말 것.** 실행기는 원인을 실어 `notRun` 으로 보고하고, 통과시킬지는 훅의 선언이 정한다.
- **사유에 `failOpen` 이나 명령 문자열을 싣지 말 것.**
- **판정 문구와 규칙을 훅 클래스에 따로 두지 말 것.** `ShellHookVerdicts` 한 곳이다.
- **`failOpen` 이 여는 원인을 늘리지 말 것.** `CANCELLED` 와 `HOOK_FILES_TAMPERED` 는 인프라 실패가 아니다. 반대로 인프라 실패를 변조로 올리지도 말 것.
- **부재를 판정할 수 없는 계층을 "훅 없음" 으로 읽지 말 것.**
- **막힌 포크의 결과에 goal 을 넣지 말 것.**

## 13. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 내용 | 타입 | 경로 |
|---|---|---|
| 판정 | `ShellHookVerdicts`, `ShellHookOutcome`, `ActionCallOutcome` | `skill/hook/declarative/` |
| 선언 훅 | `DeclarativePreToolHook`, `AbstractDeclarativeShellHook`, `DeclarativeHookOptions` | `skill/hook/declarative/` |
| 이벤트 분류 | `SkillHookSet`, `HookEventName` | `skill/hook/`, `config/hook/` |
| 포크 block | `SubagentOnStartGate`, `DefaultSubagentExecutor`, `SubagentBehaviorRunner` | `subagent/execution/`, `subagent/behavior/` |
| 로드 | `HookConfigLoader`, `HookRegistryReloader`, `HookHotReloadBootstrap`, `HookConfigParseException`, `JacksonHookConfigParser` | `config/hook/` |
| 적용 | `HookRegistryApplier`, `HookHandlerSpec` | `config/hook/` |
| 스킬 파서 | `SkillHookSetParser` | `skill/parser/` |
| 정체 가드, 완료 사유 | `StalledIterationGuard`, `CompletionReason` | `agent/budget/` |
| CLI | `AgentSetupFactory` | `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java` |
