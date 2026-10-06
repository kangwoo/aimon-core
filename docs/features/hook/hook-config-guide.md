# Hook Configuration Guide (`hooks.json`)

> Claude Code 호환 `hooks.json` 스키마 및 사용 예제.

이 문서는 AIMON 의 선언적 후킹(Declarative Hook) 설정을 작성하는 방법을 다룹니다.
설정은 [Claude Code `hooks.json`](https://docs.claude.com/en/docs/claude-code/hooks)
포맷과 동일한 모양을 사용하므로 기존 Claude Code 설정 파일을 그대로 가져올 수 있습니다.

---

## 목차

1. [설정 위치 / 4-tier 레이어](#설정-위치--4-tier-레이어)
2. [핫리로드 (Hot Reload)](#핫리로드-hot-reload)
3. [최상위 구조](#최상위-구조)
4. [지원 이벤트와 매핑](#지원-이벤트와-매핑)
5. [Matcher 문법](#matcher-문법)
6. [Handler 타입](#handler-타입)
   - [`command`](#command)
   - [`http`](#http)
   - [`mcp`](#mcp)
   - [`deny`](#deny)
7. [가드가 막는 경우](#가드가-막는-경우)
8. [템플릿 변수](#템플릿-변수)
9. [Async Rewake (`asyncRewake`)](#async-rewake-asyncrewake)
10. [예제 모음](#예제-모음)
11. [트러블슈팅](#트러블슈팅)

---

## 설정 위치 / 4-tier 레이어

AIMON 은 다음 4 개 소스에서 후킹 설정을 읽고 우선순위(precedence) 가 낮은 쪽
부터 높은 쪽으로 누적 병합한다 (낮음 → 높음 순서대로 디스패치).

| Source     | 경로                                | 우선순위 | 비고                                                |
|------------|-------------------------------------|----------|-----------------------------------------------------|
| `USER`     | `~/.aimon/hooks.json`               | 10       | 사용자 전역 설정                                    |
| `PROJECT`  | `<project>/.aimon/hooks.json`       | 20       | 프로젝트 공통 (커밋 대상)                           |
| `LOCAL`    | `<project>/.aimon/hooks.local.json` | 30       | 개인 오버라이드 (`.gitignore` 권장)                 |
| `SKILL`    | Skill 의 frontmatter `hooks:` 블록  | 0        | skill scope 동안만 활성, 별도 격리 (USER/PROJECT/LOCAL 와 합쳐지지 않음) |

- 누락된 파일은 **조용히 무시**되며 DEBUG 로그만 남는다.
- 같은 이벤트에 여러 entry 가 있을 경우 **추가(additive)** 만 일어나며 덮어쓰기는 하지 않는다.
- 디스패치 순서는 `USER → PROJECT → LOCAL` 이므로 더 좁은 layer 가 마지막에 실행된다.

---

## 핫리로드 (Hot Reload)

> `hooks.json` 편집은 CLI 재시작 없이 반영된다.

CLI 부트스트랩(`AgentSetupFactory`)은 `HookConfigWatcher` + `HookRegistryReloader`
를 application-scope 으로 구성하여 다음 세 파일의 변경을 감시한다:

- `~/.aimon/hooks.json` (USER)
- `<project>/.aimon/hooks.json` (PROJECT)
- `<project>/.aimon/hooks.local.json` (LOCAL)

> SKILL frontmatter 의 `hooks:` 블록은 핫리로드 대상이 **아니다** — skill 활성/
> 비활성 사이클을 그대로 따른다.

### 동작 흐름

1. **폴링** — `HookConfigWatcher` 가 1 초 간격으로 mtime 을 검사 (macOS WatchService
   latency 회피).
2. **디바운스** — 2 초 윈도우로 burst 편집을 묶어 단일 reload 로 collapse.
3. **트랜잭셔널 swap** — `HookRegistryReloader` 가 새 layered config 를 materialise
   하고, 라이브 `DefaultHookRegistry` 의 *managed* hook 만 LIFO 로 교체한다.
   프로그래매틱하게(코드로) 등록된 hook 은 영향받지 않는다.
4. **이벤트 발사** — swap 직후 `OnConfigReload` 이벤트가 발사되어
   `OnConfigReloadHook` 구독자에게 결과(`successful` / `failureReason` /
   `reloadCounter` / `configSource`)가 전달된다.

### SLA / 보장

| 항목                                          | 값                              |
|-----------------------------------------------|---------------------------------|
| 편집 → `OnConfigReload` 발사                  | ≤ 2 s (E2E 테스트로 검증)       |
| 폴링 간격                                     | 1 s (default)                   |
| 디바운스 윈도우                               | 2 s (default)                   |
| 재진입 방지                                   | monotonic counter, max depth 1  |
| 부분 실패 시                                  | 이전 registry 상태로 자동 롤백  |

### 부트스트랩과의 차이

- **bootstrap**: CLI 시작 시 1 회 실행. `OnConfigReload` 이벤트는 발사되지
  않는다 (계약상 reload 가 아니라 초기 로드).
- **reload**: 파일 편집 트리거. `OnConfigReload` 이벤트가 발사된다.
- bootstrap 이 실패하면 **시작이 멈춘다.** 세 계층 가운데 한 파일이라도 파싱되지 않거나 읽히지 않으면
  `HookHotReloadBootstrap.start()` 가 `HookConfigParseException` 을 던지고, CLI 는
  `Configuration error: hooks config /…/.aimon/hooks.json (PROJECT layer) is invalid: … line: 3, column: 5 …` 를 내고
  종료한다(REPL 은 뜨지 않는다). hook 은 하나도 등록되지 않고 watcher 도 시작되지 않는다. 파일이 **없는** 것은 실패가
  아니다 — 그 계층이 없는 것으로 정상 시작한다. 깨진 파일을 안고 띄우는 설정 스위치는 없다: 파일을 고치거나 치운다.
  `start()` 를 직접 부르는 호스트가 그래도 띄우려면 그 예외를 코드에서 명시적으로 잡아야 한다.
- **실패가 아닌 것 · 실패인 것.** 비어 있는 파일(0 바이트, 공백만, `null`)은 hook 이 없는 계층으로 읽혀 정상 시작한다.
  경로 중간이 디렉터리가 아니라 일반 파일이면(예: `~/.aimon` 이 파일) 그 자리에 설정 파일이 있을 수 없으므로 없는 것으로
  보고, 그 파일을 가리키는 WARN 을 남긴 채 시작한다. 반대로 존재 여부를 알 수 없으면 — 예를 들어 홈이나 `.aimon` 디렉터리를
  검색할 권한이 없으면 — `… could not be read: cannot determine whether the file exists (java.nio.file.AccessDeniedException: …)`
  로 시작이 멈춘다. 판단할 수 없는 계층을 "hook 없음" 으로 읽지 않는다. 리로드에서도 같은 판정이다: 실패면 이전 설정이 남는다.

### 실패 모드

| 상황                              | 동작                                                          |
|-----------------------------------|---------------------------------------------------------------|
| **시작 시** `hooks.json` 이 파싱 실패 · 읽기 실패, 또는 가드 이벤트에 **적용할 수 없는 항목**이 있음 | **시작 중단.** 예외 메시지에 파일 경로 · 계층 · 원인(적용 불가 항목이면 이벤트 · entry 번호 · handler 번호까지). 다른 계층이 멀쩡해도 뜨지 않는다 — 한 계층을 빼고 띄우면 그 계층의 가드가 꺼진 채 돈다 |
| 새 `hooks.json` 이 파싱 실패 · 읽기 실패, 또는 가드 이벤트에 적용할 수 없는 항목이 있음 (리로드) | swap 하지 않음. **이전 설정이 그대로 유지**된다(이전 hook · 가드 그대로). `OnConfigReload(failed)` 발사, `failureReason` 에 파일 경로 · 계층 |
| swap 도중 일부 hook 등록 실패      | LIFO undo 로 새 hook 제거 + 원래 순서로 이전 hook 재등록      |
| listener 가 예외를 던짐            | 로그만 남기고 watcher 는 계속 동작 (poison 방지)              |
| watcher 시작 자체가 실패           | CLI 는 핫리로드 없이 계속 동작 (WARN 로그)                    |

### 프로그래매틱 구독 예제

`HookRegistry` 는 이벤트별 `register*` 메서드를 갖지 않는다 — typed token 을 받는 제네릭
`register(HookEventType<H>, H)` 하나뿐이다. `OnConfigReloadHook` 은 `@FunctionalInterface`
이므로 람다로 바로 등록할 수 있다.

```java
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.HookResult;

hookRegistry.register(HookEventType.ON_CONFIG_RELOAD, ctx -> {
    if (ctx.isSuccessful()) {
        log.info("hooks.json reloaded ({}): {}", ctx.getReloadCounter(), ctx.getConfigSource());
    } else {
        // getFailureReason() 은 String 을 반환한다 (Optional 이 아니며, 성공 시 빈 문자열).
        log.warn("hooks.json reload failed: {}", ctx.getFailureReason());
    }
    return HookResult.allow();
});
```

> ⚠️ `ON_CONFIG_RELOAD` 는 advisory 체인이다. 반환한 `HookResult` 의 feedback 은 어디에도
> 전달되지 않고 폐기되므로, 이 hook 은 부수효과(로깅·알림·캐시 무효화)로만 쓴다.

> ℹ️ CLI 는 부트스트랩에서 자동으로 핫리로드를 켠다. Web 등 다른 부트스트랩은
> `at.aimon.core.config.hook.HookHotReloadBootstrap.builder()...start()` 한 번
> 호출로 동일하게 채택할 수 있다 — `AgentSessionOpener` javadoc 의 canonical
> 예제를 참고.

---

## 최상위 구조

```jsonc
{
  "hooks": {
    "<EventName>": [
      {
        "matcher": "<도구 매처>",     // 선택, 기본값 "*"
        "hooks": [
          { "type": "<handler>", ... }, // 1개 이상
          ...
        ]
      },
      ...
    ],
    ...
  }
}
```

- `hooks` 필드 자체가 비어있거나 누락된 파일은 빈 설정으로 간주된다.
- **알 수 없는 최상위/엔트리 필드** 는 `WARN` 로그를 남기고 무시된다 — 새 설정
  파일이 구버전 바이너리를 깨뜨리지 않는다. (`asyncRewake` 는 Phase 4A 부터
  정식 필드로 인식된다 — [Async Rewake](#async-rewake-asyncrewake) 참조.)

---

## 지원 이벤트와 매핑

`HookEventName` 이 다음과 같이 Claude Code 이름과 AIMON 내부 이름을 양방향으로 매핑한다.

| Claude Code (`hooks.json`) | AIMON 내부 이벤트     | 설명                                    | Blocking? |
|----------------------------|-----------------------|-----------------------------------------|-----------|
| `PreToolUse`               | `preTool`             | 도구 호출 직전 (allow/deny/입력 변형)   | ✅        |
| `PostToolUse`              | `postTool`            | 도구 호출 직후 (audit/metrics)          | ❌        |
| `Stop`                     | `onStop`              | 턴 종료 시                              | ❌        |
| `PreCompact`               | `preCompact`          | compact 직전                            | ✅        |
| `SessionStart`             | `onSessionStart`      | 대화 시작                               | ❌        |
| `SessionEnd`               | `onSessionEnd`        | 대화 종료                               | ❌        |
| `SubagentStop`             | `subagentStop`        | 서브에이전트 종료                       | ❌        |
| (없음)                     | `onStart`             | AIMON-only: 턴 시작                     | ✅        |
| (없음)                     | `postCompact`         | AIMON-only: compact 직후                | ❌        |
| (없음)                     | `subagentStart`       | AIMON-only: 서브에이전트 시작           | ❌        |
| (없음)                     | `permissionRequest`   | AIMON-only: 권한 판정 시점              | ✅        |
| (없음)                     | `permissionDenied`    | AIMON-only: 거부 후 후처리              | ❌        |
| (없음)                     | `onConfigReload`      | AIMON-only: 설정 핫리로드 직후          | ❌        |

AIMON 고유 이벤트는 `hooks.json` 에 AIMON 내부 이름을 그대로 적으면 된다
(대소문자 무시 — `"onStart"`, `"onstart"` 모두 동작).

다음 이벤트는 **현재 미지원**이며 entry 가 있으면 WARN 로그와 함께 무시된다:
`Notification`, `UserPromptSubmit`, `stop_hook_active`.

**모르는 이벤트 이름**도 WARN 후 무시된다 — 더 새로운 AIMON 이나 Claude Code 의 이벤트일 수 있기 때문이다. 다만 아는
이름과 **두 글자 이내**로 다르면 오타로 본다: WARN 이 가장 가까운 이름을 알려 주고(`unknown event 'postTol' … did you mean
'postTool'?`), 그 가까운 이름이 **가드 이벤트**(`preTool` · `PreToolUse` · `onStart` · `preCompact` · `permissionRequest`)면
WARN 이 아니라 **시작 실패**다(`… is invalid: unknown event 'preTol' - did you mean 'preTool'? …`). `preTol` 은 미래의
이벤트가 아니고, 건너뛰면 적어 둔 가드가 빠진 채 뜬다.

> `permissionRequest` / `subagentStart` 의 "(없음)" 은 `HookEventName` 의 역매핑을 그대로 옮긴
> 것인데, 상류 스펙에는 두 이벤트가 존재한다. 정방향 resolve 는 정상이고 역방향만 비어 있다 —
> 자세한 사정은 [`hooks-specification.md` §4](../../references/hooks-specification.md) 참고.

> ⚠️ **Blocking? = ❌ 인 이벤트에서는 거부가 효력이 없다.** 프레임워크에서 hook 의
> `block`/`deny` 를 실제로 소비하는 호출 지점은 `preTool`, `permissionRequest`,
> `onStart`, `preCompact` 넷뿐이고, 나머지 이벤트의 거부는 WARN 로그만 남기고
> 무시된다. 감사/알림 hook 을 게이트로 쓰려고 하지 말 것.
>
> 선언적 hook 에서 이 네 이벤트의 거부는 `command` handler 의 **exit 2** 로 표현한다
> ([종료 코드](#command) 참조). `type: "deny"` 는 그중에서도 `preTool` 전용이며, 다른
> 이벤트에 두면 적용할 수 없는 항목이다 — 가드 이벤트에서는 시작 실패, 나머지에서는 건너뛴다.

---

## Matcher 문법

`matcher` 는 어떤 도구 호출에 hook 을 적용할지를 결정한다. 비어 있거나
`"*"` 이면 모든 도구에 매치된다 (`NameOnlyPredicate.ANY`).

문법은 `PredicateParser` 가 받는 것이 전부다: **도구 이름**, **`도구(글롭)`**, 그리고 그 둘을 잇는 **`|`**(OR).

| 패턴                                  | 의미                                                                |
|---------------------------------------|---------------------------------------------------------------------|
| `Bash`                                | 도구 이름이 정확히 `Bash` (대소문자 구분)                           |
| `Read\|Write\|Edit`                   | 셋 중 하나 — `\|` 는 OR 이고 양옆 공백은 무시된다                    |
| `mcp__*`                              | 이름 글롭 — `mcp__` 으로 시작하는 모든 도구. 와일드카드는 `*` 하나뿐이다 |
| `Bash(git push*)`                     | `Bash` 의 서브커맨드 글롭 — `command` 를 나눈 조각 중 하나가 글롭 **전체**와 일치 |
| `Edit(*.env)`                         | 경로 글롭 — `Edit` 의 경로 인자가 글롭 **전체**와 일치               |
| `Bash(rm -rf*)\|Write(*.env)`         | 위 항들을 `\|` 로 묶은 것. 괄호 **안**의 `\|` 는 패턴의 일부다       |

- **글롭.** `*` 는 0개 이상의 임의 문자(`/` 도 넘는다)이고 **그 밖의 모든 문자는 리터럴**이다 — `.` · `?` · `^` · `\s` ·
  `**` 에 특별한 뜻이 없다. 앞뒤를 고정해 비교하므로 "…로 시작" 은 `git push*`, "…를 포함" 은 `*--force*` 로 적는다.
- **`Bash(글롭)`.** `command` 문자열을 `&&` · `||` · `;` · `|` · 줄바꿈에서 나누고, 백틱 · `$(…)` · 큰따옴표 안의 내용도 따로
  조각으로 본다(`bash -c "git push"` 의 `git push`). 조각 하나라도 글롭과 일치하면 매치다. 셸을 해석하는 것이 아니라
  **글자를 비교**한다 — `git  push`(공백 두 칸) · `sudo git push` 는 `git push*` 에 걸리지 않는다. 우회를 막아야 하는
  가드라면 글롭에 기대지 말고 `command` handler 가 stdin 의 `tool_input.command` 를 직접 검사하게 한다.
- **`도구(글롭)` 을 받는 도구**는 `Bash` 와 경로 도구 여덟(`Read` · `Edit` · `Write` · `MultiEdit` · `Glob` · `Grep` · `LS` ·
  `NotebookEdit`)뿐이다. 경로 도구는 모델이 넘긴 경로 문자열 그대로를 본다(`Read` · `Edit` · `Write` · `MultiEdit` 는
  `file_path`, `Glob` 은 `pattern` · `path`, `Grep` 은 `path` · `pattern`, `LS` 는 `path`, `NotebookEdit` 는 `notebook_path`).
  절대 경로일 수도 상대 경로일 수도 있으므로 디렉터리가 붙어도 걸리게 `*.env` 처럼 `*` 로 시작한다. 그 밖의 도구에
  괄호를 붙이면(`WebFetch(…)`, `mcp__x(…)`) 해석되지 않는다.
- **없는 것.** 정규식, AND(`&`), 입력 필드 지정(`command=…`), 부정은 문법에 없다. 도구 권한의 패턴 문법
  (`allowed-tools` 의 `Bash(git:*)` · `Read(/tmp/**)`)과도 **다른 문법**이다 — 매처에서 `:` 와 `**` 는 리터럴이다.
- **해석되지 않는 매처** — 짝이 맞지 않는 괄호, 빈 패턴(`Bash()`), 빈 항(`Read|`), 닫는 괄호 뒤의 글자, 괄호를 받지 않는
  도구 — 는 `preTool` 에서 **시작 실패**다([시작할 때 막는 경우](#시작할-때-막는-경우)). `postTool` 에서는 문자열 전체를 도구
  이름으로 보는 `name-only` fallback 으로 떨어지고 WARN 이 남는다(그 이름의 도구는 없으므로 hook 은 발화하지 않는다).
- **해석은 되지만 아무것도 맞추지 못하는 매처는 잡히지 않는다.** 괄호가 없는 항은 통째로 도구 이름이고 괄호 안은 통째로
  글롭이라, 위에 없는 문법으로 적은 매처는 오류 없이 등록되어 **한 번도 발화하지 않는다**: `mcp__.*` 는 `mcp__.` 으로
  시작하는 이름만, `Bash & input.command~^npm` 은 그 글자 그대로의 이름만, `Bash(command=^git\s+push)` 는 그 글자 그대로의
  커맨드만 맞춘다. 이 가이드는 한때 그 세 형태를 문법으로 실었다 — 그대로 옮겨 적은 `deny` 가드가 있다면 지금까지 걸린
  적이 없으니 위 표의 형태로 고쳐 쓴다.

---

## Handler 타입

### `command`

쉘 명령을 실행한다. `ShellAction` + `ShellActionExecutor` 가 처리. **모든 이벤트에서
사용할 수 있는 유일한 handler 타입**이다 (`http` / `mcp` 는 `preTool`·`postTool` 전용,
`deny` 는 `preTool` 전용 — 다른 이벤트에 두면 적용할 수 없는 항목이다: 가드 이벤트에서는 시작 실패,
나머지 이벤트에서는 skip + WARN. 아래 "시작할 때 막는 경우").

```jsonc
{
  "type": "command",
  "command": "jq -r '.tool_input.file_path'",
  "timeout": 5      // 선택, 초 단위 (Claude Code parity). 미지정 시 30초
  // "timeoutMs": 500  // 대안: 밀리초 단위 별칭. 둘 다 있으면 timeoutMs 가 이긴다
  // "failOpen": true  // 선택, 기본 false. 커맨드를 돌리지 못했을 때 막지 않고 통과시킨다 (아래 "종료 코드")
}
```

**어디서 도는가.** `hooks.json` 의 커맨드는 **호스트 셸**에서 돈다 — 이 파일은 운영자 설정이고, 실행 밖에서
발화하는 이벤트(`onSessionStart` · `onSessionEnd` · `onConfigReload`)를 선언할 수 있는 유일한 자리이기 때문이다
(`HostShellActionExecutor`). 스킬 frontmatter 가 선언한 훅의 `shell` 액션은 다르다 — 그것은 훅이 발화한 실행의
**실행 환경 셸**에서 돌고, 그래서 실행 밖 이벤트에는 선언할 수 없다. 스킬 훅만 받는 환경 변수도 하나 있다:
`AIMON_SKILL_DIR`(그 스킬 디렉터리를 훅이 도는 환경에 스테이징한 경로)이다. `hooks.json` 의 커맨드에는 스킬 디렉터리가
없으므로 그 변수가 **설정되지 않는다** — 빈 문자열이 아니라 unset 이고, stdin payload 에도 `skill_dir` 필드가 없다.

**입력 전달.** 커맨드 문자열은 **템플릿 렌더링되지 않는다** — 셸에 verbatim 으로 전달되므로
`${tool_input.x}` 를 커맨드에 써도 placeholder 가 아니라 (비어 있는) 셸 변수일 뿐이다.
신뢰할 수 없는 도구 입력을 커맨드 라인에 절대 싣지 않기 위한 의도된 설계다. 컨텍스트는 두 경로로
들어온다 (Claude Code 와 동일):

1. **stdin JSON payload** — `AIMON_*` env 를 prefix 제거 + 소문자화한 스칼라 필드
   (`AIMON_TOOL_NAME` → `tool_name`) + 도구 이벤트 한정 중첩 객체 `tool_input`.
2. **`AIMON_*` 환경 변수** — 같은 값의 평면 뷰.

```bash
payload=$(cat)
tool=$(echo "$payload" | jq -r '.tool_name')
path=$(echo "$payload" | jq -r '.tool_input.file_path')
```

**종료 코드.**

| exit | 의미                                                                              |
|------|-----------------------------------------------------------------------------------|
| `0`  | 정상 진행.                                                                        |
| `2`  | **veto** — stderr 가 거부 사유가 된다 (Claude Code parity). 4000자를 넘으면 잘린다.  |
| `126` · `127` | 셸이 커맨드를 **시작하지 못했다** (실행할 수 없음 · 찾지 못함). 결정 채널이 있는 이벤트에서는 **veto 와 같다** — 아래 "커맨드를 돌리지 못했을 때". 나머지 이벤트에서는 "그 외" 와 같다. |
| 그 외 | `WARN` 로그 + fail-soft (정상 진행). 깨진 스크립트가 조용한 게이트키퍼가 되면 안 된다. |
| 없음 | 커맨드가 종료 코드를 내지 못했다 (timeout, 셸 실패). 결정 채널이 있는 이벤트에서는 **veto 와 같다** — 아래 "커맨드를 돌리지 못했을 때". |

veto 는 **결정 채널이 있는 네 이벤트에서만** 효력이 있다. 나머지 이벤트의 exit 2 는
WARN 로그만 남기고 진행한다 (`AbstractDeclarativeShellHook#vetoResult`).

| 이벤트                | exit 2 의 효과                                                       |
|-----------------------|----------------------------------------------------------------------|
| `preTool`             | `block` — 도구 호출을 건너뛰고 stderr 가 tool 결과로 모델에 전달된다  |
| `onStart`             | `block` — 메인 실행은 `ExecutionBlockedByHookException` 으로 턴 자체가 중단되고, fork(스킬 fork · `Task` · 워크플로 SubAgent)는 **시작하지 않고** 사유가 실린 실패 결과로 부모에게 돌아간다. 어느 쪽도 `onStop` 은 발화하지 않는다 |
| `preCompact`          | `block` — AUTO compaction 스킵 / MANUAL 은 사유 보고                  |
| `permissionRequest`   | `deny` — 디스패치 전에 거부                                           |
| 그 외 9개 이벤트      | 무시 (WARN 로그 후 정상 진행)                                         |

> `onStart` 의 veto 는 이번 하드닝에서 추가되었다. 그 전에는 선언적 `onStart` hook 이
> exit 2 로 끝나도 아무 일도 일어나지 않았다.

**`hooks.json` 의 `onStart` 는 모든 fork 에도 걸린다.** `onStart` 는 메인 턴뿐 아니라 fork 가 시작할 때마다 발화하고, 그때의
"사용자 메시지" 는 그 fork 가 받은 goal 이다. exit 2 를 내거나 커맨드를 돌리지 못하면 그 fork 는 LLM 을 한 번도 부르지 않고
끝나며, 부모는 `Execution blocked by OnStart hook [SUBAGENT/<이름>]: <사유>` 를 받는다(`Task` 는 `Status: FAILURE`, 스킬은
`Skill fork failed for '<스킬>': …`, 백그라운드 작업은 `FAILED`). 그 fork 의 완료 사유는 `ERROR` 가 아니라 `BLOCKED` 다 —
`Task` 결과의 `Completion reason: BLOCKED` 줄과 워크플로 단계의 `completionReason` 으로 읽는다. 사용자 입력만 검사하려던 hook 이라면 스크립트에서
`AIMON_INVOKER_TYPE` 로 가른다 — 메인 턴은 `MAIN_AGENT`, fork 는 `SUBAGENT` 다. fork 를 빼는 방법은 이것 하나다:
`failOpen: true` 는 커맨드를 **돌리지 못했을 때만** 통과시키고 exit 2 는 그대로 막는다. 이름에 코드
behavior(`SubagentBehavior`)가 등록된 SubAgent 도 같다 — ReAct 루프를 돌지 않지만 behavior 를 부르기 전에 `onStart` 가
발화하고, block 이면 behavior 는 실행되지 않는다.

**커맨드를 돌리지 못했을 때 (fail-closed).** 위 네 이벤트의 `command` handler 가 **종료 코드를 내지 못하면** — timeout,
셸 실패 — 그 이벤트의 거부 결과를 낸다(`preTool` · `onStart` · `preCompact` 는 block, `permissionRequest` 는 deny). 판단하지
못한 가드는 통과시키지 않는다. 사유는 `Blocked: guard hook '<source>#<n>' (<event>) could not run its command — timed out:
…` 꼴로 원인을 싣고, 커맨드 문자열은 싣지 않는다. **exit 126 · 127 도 같은 경우다** — 셸이 "실행할 수 없다" · "찾지
못했다" 로 끝낸 것이라 가드는 아무 답도 하지 않았다(`… — command not found: exit code 127`; 셸의 stderr 는 커맨드 줄을
인용하므로 사유에 싣지 않는다). 스크립트가 스스로 126 · 127 로 끝나도 구별할 수 없으므로 똑같이 막힌다 — 가드 스크립트의
"허용하되 오류" 는 1 처럼 다른 코드로 낸다. 가드가 아니라 **관찰** 용도의 handler(감사 로그, 메트릭)라면
`"failOpen": true` 를 선언한다 — 그러면 WARN 만 남기고 진행한다. `failOpen` 은 exit 2 를 약하게 하지 않는다. 가드를 여는
값은 JSON 불리언 `true` 하나뿐이다: `"true"` · `1` · `null` 같은 불리언 아닌 값은 강제 변환되지 않고 **`false` 로 읽힌다** —
handler 는 그대로 등록되어 가드가 닫힌 채 남고, 파일 · 이벤트 · handler 를 밝힌 WARN 이 남는다(가드를 푸는 키이므로 느슨하게
읽지 않고, 그렇다고 오타 하나로 파일의 다른 가드까지 잃지도 않는다). 나머지 9개 이벤트에서는 전처럼 WARN 후 진행한다.

**`timeout` 단위 (breaking change).** `timeout` 은 Claude Code 와 동일하게 **초(seconds)**
단위다. 밀리초가 필요하면 AIMON 고유 별칭 `timeoutMs` 를 쓴다. 둘 다 있으면 더 정밀한
`timeoutMs` 가 이긴다. 두 값 모두 양수여야 하며, 0 이나 음수는 파싱 단계에서 거절된다.

| 표기                 | 의미                                  |
|----------------------|---------------------------------------|
| `"timeout": 60`      | 60초 (60000 ms)                       |
| `"timeoutMs": 1500`  | 1500 ms — 1초 미만이 필요할 때        |

> ⚠️ **마이그레이션.** `timeout` 은 예전에 밀리초로 읽혔다. 그 시절 설정을 그대로 두면
> `"timeout": 5000` 이 5초가 아니라 **5000초** 로 해석된다(반대로 Claude Code 에서 가져온
> `"timeout": 60` 은 예전 바이너리에서 60 ms 였다). 너무 짧게 읽힌 쪽은 결정 채널이 있는
> 이벤트에서 timeout 으로 **막히고**, 나머지 이벤트에서는 조용히 잘린다 — 기존 설정은 값을 1000
> 으로 나누거나 `timeoutMs` 로 키를 바꿔야 한다.

**`timeout` 과 hook policy.** 선언된 budget 은 executor 가 강제하며, 값이 hook policy 의
timeout(기본 30초) **이상**이면 executor 의 바깥 그물이 그만큼 **넓어진다**(+5초 grace).
따라서 `"timeout": 120` 같은 장시간 handler 도 30초에 잘리지 않는다. 정책 timeout 과 정확히
같은 값(예: `timeoutMs` 를 생략한 셸 handler 의 기본 30초)도 grace 를 받는다. 반대로 선언
budget 이 정책보다 짧으면 무시된다 — 그물을 좁혀 봐야 handler 자신의 deadline 과 경주할 뿐이다.
선언 budget 은 **10분(`MAX_DECLARED_BUDGET`)으로 클램프**되므로, 설정 실수가 턴을 무한정
붙잡아 둘 수 없다 (초과 시 WARN 로그 후 10분으로 잘림).

**바깥 그물이 먼저 터지면.** handler 가 자기 timeout 을 지키지 못해 — 취소를 구현하지 않은 원격 셸, 멈춘 I/O, 중단 신호에
반응하지 않는 MCP 클라이언트 — 바깥 그물이 그 hook 을 끊으면, 결정 채널이 있는 네 이벤트의 선언적 hook 은 **막는다**(`Hook timed out
after …ms (limit=…ms)`). 이벤트 정책의 `timeoutBehavior` 기본값은 `FAIL_OPEN` 이지만, 선언적 가드는 hook 마다 `FAIL_CLOSED` 를
선언하고 실행기는 정책보다 그 선언을 따른다(`ExecutionHook#getTimeoutBehavior()`). `"failOpen": true` 인 handler 와 나머지
9개 이벤트의 handler 는 아무것도 선언하지 않으므로 이벤트 정책(기본 `FAIL_OPEN` — 진행)을 따른다. 코드로 등록한 hook 의
동작은 바뀌지 않았다.

### `http`

HTTP 웹훅을 호출한다. `HttpAction` + `HttpActionExecutor`.

```jsonc
{
  "type": "http",
  "url": "https://example.test/hooks/pre-tool",
  "method": "POST",                                    // 선택, 기본값 POST
  "headers": {                                         // 선택
    "X-Auth-Token": "${env.AIMON_HOOK_TOKEN}"
  },
  "body": "{\"tool\":\"${context.tool_name}\",\"path\":\"${tool_input.file_path}\"}",
  "allowedEnvVars": ["AIMON_HOOK_TOKEN"],             // ${env.X} 로 참조 가능한 화이트리스트
  "timeout": 3                                          // 초 (밀리초가 필요하면 "timeoutMs": 3000)
}
```

응답 본문은 `{ "decision": "allow" | "deny" | "defer", "reason": "...", "feedback": "...", "updatedInput": {...} }`
JSON 스키마를 따르면 `HookResult` 로 자동 매핑된다. `preTool` 에서는 **판정을 받았는지**가 중요하다 — 판정을 받지 못한
가드는 막는다([가드가 막는 경우](#가드가-막는-경우)). `"failOpen": true` 는 `command` 와 똑같이 이 handler 에도 쓴다.

| 응답 | 읽는 법 |
|------|---------|
| 2xx, JSON 객체, 거부를 말함 — `decision` 이 `deny` · `block`, `hookSpecificOutput.permissionDecision` 이 `deny`, `continue` 가 `false` | 판정: 거부. 사유는 **거부한 필드의 짝**에서만 온다 — `decision` 은 `reason`, `permissionDecision` 은 `permissionDecisionReason`, `continue` 는 `stopReason`. 짝이 없으면 기본 문구(`Denied by HTTP hook`) |
| 2xx, JSON 객체, `hookSpecificOutput.permissionDecision` 이 `ask` | 판정: 묻는다 — `AskPromptHandler` 가 답한다 (`permissionDecisionReason` 이 질문) |
| 2xx, JSON 객체, `decision` 이 `allow` · `defer`, `permissionDecision` 이 `allow`, 또는 아무 결정도 없음 | 판정: 허용. `feedback`(없으면 `systemMessage`, 그다음 `hookSpecificOutput.additionalContext`)은 모델에게 전하고, `updatedInput` 은 **최상위와 `hookSpecificOutput` 안 두 자리** 어느 쪽에 있든 도구 입력을 바꾼다 |
| 2xx, 본문이 비었거나, JSON 으로 선언되지 않은 텍스트(`ok`)이거나, 객체가 아닌 JSON | 판정: 허용 — 결정을 싣지 않는 웹훅 |
| 2xx 인데 읽을 수 없음 — `Content-Type` 이 JSON 인데 파싱되지 않는 본문, 문자열이 아니거나 아는 값이 아닌 `decision`(예: `"approve"`) · `permissionDecision`(예: `"defer"`), 불리언이 아닌 `continue`, 객체가 아닌 `hookSpecificOutput`, 객체가 아닌 `updatedInput`(두 자리 어느 쪽이든), 두 자리의 `updatedInput` 이 서로 다름 | **판정 없음** (`response could not be read`) |
| non-2xx (본문이 무엇이든) | **판정 없음** (`call failed: HTTP <status>`) — 거부는 2xx 의 `decision: deny` 로 표현한다 |
| 연결 실패 · 전송 오류 | **판정 없음** (`call failed: <예외 타입>`) |
| `timeout` 초과 | **판정 없음** (`timed out`) |
| 실행기 미배선 | **판정 없음** (`action executor not wired`) |

Claude Code 의 `PreToolUse` 용으로 만든 정책 엔드포인트의 **응답**은 고치지 않고 읽는다 — 다만 `http` 에서만, 그리고
아래 두 가지를 빼고. 읽는 것: `hookSpecificOutput.permissionDecision`(`allow` · `deny` · `ask`)과 그 옆의
`permissionDecisionReason` · `updatedInput` · `additionalContext`, `continue: false` 와 `stopReason`, `systemMessage`,
`decision: block`. 읽지 않는 것: `permissionDecision: defer`(판정 없음, 아래)와 그 밖의 필드(`suppressOutput` 등 — 무시).
**요청**은 맞춰 주지 않는다: 본문은 이 handler 의 `body` 템플릿이 만든 것이고 Claude Code 가 보내는 입력 JSON 이 아니며,
non-2xx 는 거부도 통과도 아닌 판정 없음이다. `mcp` handler 는 이 철자들을 **읽지 않는다** — MCP 도구 결과는 Claude Code
용으로 쓰인 것이 아니어서, 그쪽에서는 `decision`(`block` 포함) · `reason` · `feedback` · 최상위 `updatedInput` 만 읽는다.
한 문서에 여러 철자가 있고 서로 다르면 **가장 엄한 쪽**이
판정이다: 거부 > 읽을 수 없음 > 묻는다 > 허용. `decision: allow` 옆에 `permissionDecision: deny` 가 있으면 거부다.
모델에게 보이는 거부 사유는 거부한 필드의 짝에서만 온다 — 거부하지 않은 필드의 사유(`decision: allow` 옆의 `reason`)도,
`feedback` · `systemMessage` · `additionalContext` 도 사유가 되지 않는다. `updatedInput` 은 허용일 때만 적용하고, 두 자리에
서로 다른 값이 있으면 어느 쪽도 고르지 않고 판정 없음으로 읽는다. `ask` 는 hook 실행 관리자의 `AskPromptHandler` 가 허용 · 거부로 바꾼다: 호스트가 넣지 않았으면
**거부**이고, 환경 변수 `AIMON_HOOK_ASK_DEFAULT=allow` 가 그 기본을 허용으로 바꾼다. `ask` 는 판정이므로 `failOpen` 이
열지 않는다. `permissionDecision: defer` 는 `decision: defer` 와 달리 판정 없음이다 — Claude Code 의 `defer` 는 "호출한
애플리케이션이 재개할 때까지 이 도구 호출을 보류" 라는 뜻이고 그렇게 할 수단이 없으므로, 도구를 실행하는 쪽으로 읽지
않는다.

`postTool` 에서는 판정 없음이 전처럼 WARN 후 정상 진행이다. 거부도 막을 것이 없으므로 WARN 후 진행이고, `ask` 는 물을
상대가 없으므로 허용으로 읽는다 — 모델에게는 응답의 `feedback`(`systemMessage` · `additionalContext`)만 전하고 질문
문구는 전하지 않는다.

> ℹ️ **`aimon-cli` 는 `http` · `mcp` handler 를 실행한다** — `hooks.json` 의 것도, 스킬 frontmatter 의 것도. `http` 는 언제나,
> `mcp` 는 CLI 설정(`mcp.servers`)에 서버가 하나라도 있을 때다. 서버가 하나도 없는 CLI 에서 `mcp` handler 는 돌 수 없는
> 항목이다 — `hooks.json` 의 `preTool` 에 있으면 CLI 가 **뜨지 않고**(`… type=mcp cannot run: no McpActionExecutor is wired in
> this assembly`; `failOpen: true` 면 등록되어 매 호출 WARN 후 통과), 그 액션을 선언한 스킬은 로드되지 않는다.
> `aimon-bootstrap` 과 Spring Boot 스타터는 **배선하지 않는다**: 임베딩 호스트가
> `HookHotReloadBootstrap.builder().httpExecutor(…).mcpExecutor(…)` 와 `AimonStackSpec` 의 `skillParser` 로 직접 배선한다.

**`http` handler 가 보내고 받는 것.** 이 handler 는 설정 파일이 호스트 프로세스에게 바깥으로 요청을 보내게 하는 자리다.

- **URL 은 적힌 그대로다** (템플릿 대상이 아니다). `https://` 와 `http://` 를 모두 받는다 — 헤더에 토큰을 싣는다면 `https://` 를
  쓴다.
- **리다이렉트는 따라가지 않는다.** 3xx 는 다른 non-2xx 와 같이 판정 없음이다(`call failed: HTTP 307`). 따라가면 `${env.X}` 로
  채운 헤더가 설정에 없는 호스트로 함께 가고, 그 호스트의 답이 판정이 된다. `url` 에는 최종 주소를 적는다.
- **응답 본문은 1 MiB 까지 읽는다.** 넘으면 잘라서 추측하지 않고 판정 없음이다(`response could not be read: response larger
  than 1048576 bytes`).
- **`timeout` 은 교환 전체를 잰다 — 응답 본문까지.** 헤더를 보내고 멈추는 엔드포인트도, 본문을 1 MiB 아래로 한 바이트씩
  흘리는 엔드포인트도 시한에 끊기고(진행 중이던 요청은 취소되고 연결은 닫힌다) 결과는 `timed out` 이다. 연결 timeout 은
  5초로 고정이다.
- **프록시는 JVM 기본값이다** — `https.proxyHost` 같은 시스템 프로퍼티는 적용되고 `HTTPS_PROXY` 환경 변수는 보지 않는다.
- **`${env.X}` 는 호스트 프로세스의 환경을 읽는다.** 읽을 수 있는 이름은 그 handler 가 **스스로 적은** `allowedEnvVars` 다. 이
  목록은 템플릿이 적어 두지 않은 변수를 읽지 못하게 할 뿐, 설정을 쓴 사람이 무엇을 내보낼 수 있는지를 제한하지 않는다. 스킬
  frontmatter 의 `http` 액션도 똑같이 읽는다 — CLI 에서 스킬의 `shell` 액션이 이미 같은 환경과 네트워크로 도는 것과 같은
  권한이다. 스킬 승인은 스킬 **이름** 단위이고 hook 의 내용을 보여 주지 않으므로, 믿지 않는 스킬은 설치하지 않는다.
- **템플릿 값은 이스케이프되지 않는다.** JSON 본문에 `"${tool_input.command}"` 를 쓰면 따옴표가 든 명령은 깨진 JSON 이 되고
  (서버가 4xx 로 답하면 판정 없음 — `failOpen` 인 감사 hook 은 그 호출을 **기록하지 못한 채 통과**시킨다), 모델이 본문에
  필드를 끼워 넣을 수도 있다. 모델이 고르는 값을 가드에 넘길 때는 값마다 따로 치환되어 구조가 깨지지 않는 `mcp` handler 의
  `args` 나, stdin 으로 JSON 을 받는 `command` handler 가 안전하다.

> 🔒 환경 변수 참조는 **화이트리스트(`allowedEnvVars`)에 있는 키만** 치환된다.
> 화이트리스트에 없는 변수는 빈 문자열로 처리되고 WARN 로그가 남는다.

### `mcp`

MCP 서버의 tool 을 호출한다. `McpToolAction` + `McpActionExecutor`.

```jsonc
{
  "type": "mcp",
  "server": "policy-server",
  "tool": "evaluate_pre_tool",
  "args": {
    "tool_name": "${context.tool_name}",
    "command": "${tool_input.command}"
  },
  "timeout": 4
}
```

응답이 `{decision, reason, feedback, updatedInput}` 모양이면 `HookResult` 로 매핑된다. 읽는 것은 **이 네 필드뿐**이다 —
`decision` 은 `allow` · `defer` · `deny` 와 `deny` 의 동의어 `block` 이고, Claude Code 의 철자(`hookSpecificOutput`,
`continue`, `stopReason`, `systemMessage`)는 값이 무엇이든 보지 않는다. 도구 결과에 `continue` 라는 필드(페이지 플래그,
이어 읽기 토큰)가 있다고 해서 판정이 되지는 않는다. 판정과 "판정 없음" 을 가르는 선은
`http` 와 같다: 오류 없이 돌아온 결과는 판정이고(빈 내용 · 일반 텍스트 · 객체가 아닌 JSON 은 허용, JSON 객체는 결정 문서로
읽는다), 서버가 등록되지 않았거나 연결되지 않았을 때 · 전송 오류 · `isError` 결과(`call failed`), 읽을 수 없는 결정
문서(`response could not be read`), 실행기 미배선은 **판정 없음**이다 — `preTool` 에서는 막고 `"failOpen": true` 면 통과한다.
`mcp` handler 의 `timeout`(기본 10초)은 **호출을 끊는다.** 시한이 지나면 호출하던 요청이 중단되고 결과는 **판정 없음**
(`timed out: no response within <n>ms`)이다 — `preTool` 에서는 막고(`"failOpen": true` 면 통과), `postTool` 에서는 WARN 후
진행한다. MCP 서버마다 설정하는 `requestTimeout`(기본 30초)은 그 아래에서 그대로 적용되므로 **둘 중 짧은 쪽이 호출을
끝낸다**: handler 의 `timeout` 을 길게 적어도 요청은 서버의 `requestTimeout` 을 넘겨 기다리지 않고, 그쪽이 먼저 끝내면 사유는
`call failed` 다. 끊긴 요청은 이쪽에서 더 기다리지 않는다 — stdio 서버에는 요청이 이미 전달되어 있으므로 서버는 일을
계속할 수 있고, 늦게 온 응답은 버려진다(`notifications/cancelled` 는 보내지 않는다). 같은 서버로 가는 다른 요청이 끝나기를
기다리는 중이었다면 이 시한으로는 끊기지 않고 hook 실행기의 바깥 그물이 끊는다 — 가드는 그때도 막는다.

### `deny`

`preTool` 전용 short-circuit. 별도의 transport 없이 즉시 거절한다.

```jsonc
{
  "type": "deny",
  "reason": "Production rm -rf 는 정책상 차단됩니다."
}
```

- `preTool` 외의 이벤트에 두면 적용할 수 없는 항목이다 — 가드 이벤트(`onStart` · `preCompact` ·
  `permissionRequest`)에서는 시작 실패, 나머지에서는 handler 를 건너뛰고 WARN 로그를 남긴다.
- 다른 이벤트에서 거부하려면 `command` handler 의 exit 2 를 쓴다. 단 exit 2 가 실제 결정으로
  이어지는 이벤트는 **`preTool` / `onStart` / `preCompact` (block) 와 `permissionRequest`
  (deny) 네 개뿐**이다. `postTool`, `onStop`, `onSessionStart`, `onSessionEnd`,
  `subagentStart`, `subagentStop`, `postCompact`, `permissionDenied`, `onConfigReload`
  에서는 exit 2 가 WARN 로그만 남기고 무시된다 — 이 아홉 이벤트에는 거부를 실을 결정 채널이
  아예 없다.
- `reason` 은 비어 있을 수 없다 (`preTool` 의 가드이므로 validation 실패는 시작 실패다).

---

## 가드가 막는 경우

결정 채널이 있는 네 이벤트(`preTool` · `onStart` · `preCompact` · `permissionRequest`)에서 선언적 hook 이 **무엇을 거부로
읽는지**, 그리고 `failOpen: true` 가 **무엇을 바꾸는지**를 한 표에 모은다. 규칙은 하나다 — **판단하지 못한 가드는 막는다.**
`failOpen: true` 는 "이 handler 는 가드가 아니라 관찰용" 이라는 선언이고, **판정을 약하게 하지는 않는다.** 나머지 9개
이벤트에서는 어느 행도 막지 않는다(WARN 후 진행). `hooks.json` 과 스킬 frontmatter 의 hook 에 똑같이 적용된다.

| handler 에 일어난 일 | 읽는 법 | 기본 | `failOpen: true` |
|----------------------|---------|------|------------------|
| `command` 가 exit 0 | 판정: 허용 | 진행 | 진행 |
| `command` 가 exit 2 | 판정: 거부 (stderr 가 사유) | **막는다** | **막는다** |
| `command` 가 exit 126 · 127 (실행할 수 없음 · 찾지 못함) | 돌리지 못함 | **막는다** | 진행 (WARN) |
| `command` 가 그 밖의 종료 코드 (1 · 3 · 130 …) | 스크립트 오작동 | 진행 (WARN) | 진행 (WARN) |
| `command` 가 종료 코드를 내지 못함 — timeout, 셸 실패, 실행 환경 없음 · 사용 불가, 스킬 디렉터리 스테이징 실패, 셸을 지원하지 않는 실행기, 실행기가 던진 예외 | 돌리지 못함 | **막는다** | 진행 (WARN) |
| `deny` handler | 판정: 거부 | **막는다** | **막는다** — `deny` 에서는 `failOpen` 을 읽지 않는다(WARN). 아래 "이벤트 정책을 따른다" 세 행에서도 `deny` handler 는 기본 열대로 막는다 |
| `http` · `mcp` 가 거부로 답함 (`decision: deny` · `block`; `http` 는 `permissionDecision: deny`, `continue: false` 도) | 판정: 거부 (거부한 필드의 짝이 사유) | **막는다** | **막는다** |
| `http` 가 `permissionDecision: ask` 로 답함 | 판정: 묻는다 | `AskPromptHandler` 의 답대로 — 기본은 **막는다** | 같다 — 판정이므로 `failOpen` 과 무관 |
| `http` · `mcp` 가 그 밖의 읽을 수 있는 답을 함 (`allow` · `defer` · 결정 없음 · 빈 본문 · 일반 텍스트) | 판정: 허용 | 진행 | 진행 |
| `http` · `mcp` 가 판정을 받지 못함 — 연결 실패, timeout, non-2xx, MCP 서버 미등록 · 미연결 · `isError`, 읽을 수 없는 답, 실행기 미배선, 실행기가 던진 예외 | 판정 없음 | **막는다** | 진행 (WARN) |
| handler 가 자기 timeout 을 넘겨 돌다가 hook 실행기의 바깥 그물(선언 timeout + 5초)에 끊김 | 판정 없음 | **막는다** | 이벤트 정책을 따른다 — 기본 정책은 진행 (WARN) |
| hook 실행기의 풀이 hook 을 받지 않음 — 포화, 또는 스택 종료로 풀이 닫힘 | 돌리지 못함 | **막는다** (`Hook could not be run (the hook executor rejected it) …`) | 이벤트 정책을 따른다 — 기본 정책은 진행 (WARN) |
| hook 이 handler 를 부르기 전에 예외로 끝남 — `matcher` 평가 중의 예외 · `StackOverflowError` 등 | 판정 없음 | **막는다** (`Hook failed before it returned a verdict (<예외 타입>)`) | 이벤트 정책을 따른다 — 기본 정책은 진행 (WARN) |
| 실행이 인터럽트되어 `command` 가 중단됨 (또는 이미 취소된 실행에서 발화) | 실행 취소 | **막는다** | **막는다** — 끝나는 실행은 다음 단계로 가지 않는다 |

"막는다" 는 `preTool` · `onStart` · `preCompact` 에서는 block, `permissionRequest` 에서는 deny 다. 돌리지 못해 막힌 사유는
`Blocked: guard hook '<이름>' (<이벤트>) could not run its command — <원인>. A guard that cannot decide blocks
(fail-closed).` 꼴이고(`http` · `mcp` 는 `could not get a verdict from its http call` · `… its mcp call`), 커맨드 문자열 ·
셸의 stderr · URL · 헤더 · 응답 본문 · 예외 메시지 · `failOpen` 이라는 이름은 싣지 않는다 — 그 사유를 읽는 쪽이 가드가
제약하는 당사자이기 때문이다. 메시지가 실리는 원인은 둘뿐이다: 스킬 디렉터리 스테이징 실패와 실행 환경 사용 불가
(`execution environment unavailable: …`). 둘 다 같은 실패에서 도구 호출(`Skill`, `Bash` · `Read` 등)이 모델에게 이미
돌려주는 문장 그대로다. `http` · `mcp` handler 는 `preTool` 과 `postTool` 에만 둘 수 있으므로, 이 표에서 그 네 행이
해당하는 가드 이벤트는 `preTool` 하나다.

풀이 받지 않은 hook 과 handler 밖에서 예외로 끝난 hook 은 바깥 그물에 끊긴 hook 과 **같은 선언**으로 막힌다 — 선언적 가드가
hook 마다 내는 `FAIL_CLOSED`(`ExecutionHook#getTimeoutBehavior()`)다. "느렸다" · "시작하지 못했다" · "죽었다" 는 호출한 쪽에서
보면 같은 일(가드가 아무 말도 하지 않았다)이라, 하나에만 닫혀 있으면 나머지가 가드를 끄는 방법이 된다. 스택을 내릴 때 풀은
일부러 닫히지만 그 시점에는 가드를 발화시킬 턴이 이미 끝나 있고, 종료 중에 발화하는 `onStop` · `onSessionEnd` 는 가드 이벤트가
아니어서 막히지 않는다. 그래도 도착한 도구 호출은 기다리지 않고 곧바로 막힌다. 코드로 등록한 hook 은 스스로 `FAIL_CLOSED` 를
선언하지 않는 한 전처럼 이벤트 정책의 `onException` 을 따른다.

### 시작할 때 막는 경우

위 표는 hook 이 **발화했을 때**의 일이다. 그 앞 단계가 하나 더 있다 — 파싱은 됐지만 **적용할 수 없는 항목**. 가드
이벤트 아래에서는 그런 항목을 건너뛰지 않고 **시작을 멈춘다**(리로드라면 이전 설정 유지). 건너뛰면 적어 둔 가드가 빠진 채
뜨기 때문이고, 깨진 파일과 같은 통로 · 같은 문구로 실패한다: `hooks config <경로> (<계층> layer) is invalid: <이벤트> entry
#<n>, handler #<m>: <사유>`. 번호는 그 계층 · 그 이벤트 안에서 0부터 센다. 커맨드나 URL 은 메시지에 싣지 않는다.

| 적용할 수 없는 항목 | 가드 이벤트 (`preTool` · `onStart` · `preCompact` · `permissionRequest`) | 나머지 이벤트 |
|---------------------|------------------------------------------------------------------------|---------------|
| 필수 필드 누락 · 잘못된 값 — `command` 없는 `command`, `reason` 없는 `deny`, URI 가 아닌 `url`, 모르는 `method`, `server` · `tool` 없는 `mcp` | **시작 실패** | skip + WARN |
| 그 이벤트가 받지 않는 handler 타입 — `preTool` 밖의 `deny`, `preTool` · `postTool` 밖의 `http` · `mcp` | **시작 실패** | skip + WARN |
| handler 가 하나도 없는 entry | **시작 실패** | skip + WARN |
| 해석되지 않는 `matcher` | `preTool`: **시작 실패** | `postTool`: name-only fallback + WARN |
| 이 호스트에서 돌 수 없는 handler — 셸을 지원하지 않는 실행기의 `command`, 실행기가 배선되지 않은 `http` · `mcp` | **시작 실패** (`"failOpen": true` 인 handler 는 가드가 아니므로 전처럼 처리 — `command` 는 skip + WARN, `http` · `mcp` 는 등록) | `command` 는 skip + WARN, `http` · `mcp` 는 등록되어 호출 때 WARN |
| 가드 이벤트와 두 글자 이내로 다른 이벤트 이름 (`preTol`) | **시작 실패** | — |
| 그 밖의 모르는 이벤트 이름 · 미지원 이벤트 | — | skip + WARN (가까운 이름이 있으면 알려 준다) |

스킬 frontmatter 의 hook 에는 이 표가 필요 없다 — 그쪽 파서는 처음부터 엄격해서 위 경우가 전부 그 스킬의 로드 실패다.

**인터럽트.** 사용자가 실행을 중단하면 돌고 있던 `command` 는 **실행의 취소 신호로** 멈춘다 — 스레드 인터럽트에 반응하지
않는 셸(원격 셸)에서도 자기 timeout 까지 돌지 않는다. 사유는 `Blocked: hook '<이름>' (<이벤트>) was stopped — execution
cancelled. An interrupted execution does not proceed.` 이고 `failOpen` 과 무관하다. 신호를 싣는 이벤트는 `permissionRequest` ·
`preTool`(항상), `postTool` · `permissionDenied`(실행이 아직 취소되지 않았을 때만 — 취소된 뒤에 발화한 감사 커맨드는 끝까지
돈다), fork 의 `onStart` 다. 메인 턴의 `onStart`, `onStop`, compaction · 서브에이전트 이벤트, 실행 밖 이벤트
(`onSessionStart` · `onSessionEnd` · `onConfigReload`)의 커맨드는 신호를 받지 않아 전처럼 셸이 스레드 인터럽트에 반응해야
멈춘다.

---

## 템플릿 변수

`http.body`, `http.headers.*`, `mcp.args` 값은 다음 변수가 치환된다. **`command` 는 치환
대상이 아니다** — 셸 handler 의 컨텍스트 전달은 위 [`command`](#command) 절의 stdin
payload / `AIMON_*` env 를 참조.

placeholder 는 `${<prefix>.<name>}` 세 종류뿐이다.

| Prefix               | 의미                                                                     |
|----------------------|--------------------------------------------------------------------------|
| `${tool_input.X}`    | 도구 입력의 키 `X`. 중첩은 점 표기 (`${tool_input.payload.id}`).          |
| `${env.X}`           | 화이트리스트(`allowedEnvVars`)의 환경 변수 `X`. 화이트리스트 밖은 항상 `""`. |
| `${context.X}`       | 아래 표의 firing 컨텍스트 속성.                                          |

`${context.X}` 로 쓸 수 있는 `X`:

| 이름                  | 의미                                              |
|-----------------------|---------------------------------------------------|
| `event`               | `preTool` 또는 `postTool`                         |
| `skill_name`          | hook 을 등록한 소스 이름 (`project#0` 형태)       |
| `invoker_name`        | 호출자 이름                                       |
| `invoker_type`        | `MAIN_AGENT` / `SUBAGENT` 등                      |
| `tool_name`           | 도구 이름                                         |
| `iteration`           | ReAct 루프 iteration 번호                         |
| `tool_result_status`  | (`postTool` 한정) `success` 또는 `error`          |

- 알려진 prefix 가 아닌 `${...}` 는 **그대로 남는다** (셸 스니펫에 리터럴로 쓸 수 있도록).
  placeholder 는 반드시 `<prefix>.<name>` 점 표기여야 하므로 `${session_id}` 같은 표기는
  치환되지 않고 리터럴로 전송된다 — `session_id` 라는 컨텍스트 키는 존재하지 않는다.
- 알려진 prefix 이지만 값이 없으면 빈 문자열로 치환된다.
- 치환 값은 **이스케이프되지 않는다.** 대상 포맷(JSON 문자열 등)의 인용은 작성자 책임이다.

---

## Async Rewake (`asyncRewake`)

> hook 이 즉시 결정을 내리지 않고 **나중에 다시 깨워달라**고
> 프레임워크에 요청할 수 있다. handler 의 `asyncRewake` 블록이 그 약속을
> 선언적으로 표현한다.

`asyncRewake` 는 모든 handler 타입(`command` / `http` / `mcp` / `deny`) 에
**선택적으로** 부착할 수 있는 직교(orthogonal) 필드이다. 다만 재발사 시점에 컨텍스트를
재구성할 수 있는 이벤트에서만 유효하다 — `preTool`, `preCompact`, `onSessionStart`,
`onSessionEnd`, `onConfigReload`. 그 외 이벤트에 두면 spec 이 WARN 과 함께 무시된다
(hook 은 정상 등록된다). 예를 들어:

- 외부 승인 시스템이 응답할 때까지 5 분 후 다시 시도 (`delay`)
- 매시 정각마다 상태 체크 (`cron`, Quartz 환경 한정)
- 외부 webhook 이 도착할 때 깨어남 (`event`)

```jsonc
{
  "type": "http",
  "url": "https://approvals.internal/check",
  "asyncRewake": {
    "trigger": { "delay": "5m" },     // 또는 cron / event — 셋 중 정확히 하나
    "timeout": "1h",                  // 선택, 기본 1h
    "maxAttempts": 4,                 // 선택, 기본 3
    "payload": { "ticket": "T-123" }, // 선택, 임의의 string→string 맵
    "reason": "awaiting human approval" // 필수
  }
}
```

### 트리거 종류 (정확히 하나)

| 트리거                                 | 의미                                                    |
|----------------------------------------|---------------------------------------------------------|
| `{ "delay": "<duration>" }`            | 한 번만 발사. `now + delay` 이후                        |
| `{ "cron": "<expr>", "zone": "<tz>" }` | 반복 발사. `timeout` 까지 (Quartz 환경 전용)            |
| `{ "event": { "type": "...", "key": "..." } }` | 외부 이벤트(`type, key` 매칭)가 도착하면 발사    |

#### Duration 표기

`delay` / `timeout` 은 두 가지 표기를 모두 받는다:

- **shorthand** — `30s`, `5m`, `1h`, `1h30m`, `1h2m3s` (대소문자 무시; 0 또는 음수는 거절)
- **ISO-8601** — `PT5M`, `PT1H30M`, `PT0.5S` (`P`/`p` 로 시작하면 자동 인식)

#### `cron` 트리거 주의사항

- 표현식은 **5 필드 cron** — 분 시 일 월 요일, 일요일은 `0` (예: `"0 * * * *"` — 매시 정각,
  `"*/30 * * * *"` — 30분마다). `ScheduledTask` 와 같은 방언이며, Quartz 백엔드가 내부에서 6 필드로
  번역한다.
- 초 필드, `?`, `L`, `W`, `#`, `@daily` 는 받지 않는다. 이들이 필요하면 표현할 방법이 없으므로
  트리거를 나눈다. 일(day-of-month)과 요일을 **동시에** 제한하는 표현식은 파싱은 되지만 Quartz 가
  그 합집합을 표현하지 못해 스케줄 시점에 거절된다 — 두 개의 훅으로 나눌 것.
- `zone` 은 IANA 타임존 ID (`UTC` / `Asia/Seoul` 등). 미지정 시 UTC.

> **마이그레이션 (6 필드 → 5 필드).** 예전 `hooks.json` 은 Quartz 6 필드를 그대로 받았다. 이제
> `"0 0 * * * ?"` 같은 표현식은 **파일을 읽는 시점에** `HookConfigParseException` 으로 거절된다.
> 앞의 초 필드를 떼고 뒤의 `?` 를 `*` 로 바꾸면 되며(`"0 0 * * * ?"` → `"0 * * * *"`), 요일을 숫자로
> 지정했다면 Quartz 의 일요일 `1` 이 여기서는 `0` 이므로 1씩 줄인다. 로드 시점 거절은 의도된 변화다 —
> 예전에는 잘못된 cron 이 조용히 로드됐다가 훅이 발사되는 순간, 즉 에이전트 턴 한가운데에서 처음 터졌다.
- `cron` 은 **`aimon-scheduling-quartz` 모듈** 이 wiring 된 환경에서만 동작한다.
  in-memory `DefaultRewakeService` 는 cron envelope 을 거절(`UnsupportedOperationException`).

#### `event` 트리거

`event.type` + `event.key` 가 정확히 일치하는 외부 이벤트가
`RewakeService.resolve(...)` 로 들어오면 envelope 이 즉시 발사되고 envelope payload
에 호출 측 payload 가 합쳐져 (호출 측 우선) hook 이 다시 호출된다. `key` 는 리터럴
문자열이다 — `asyncRewake` 블록은 템플릿 렌더링을 거치지 않으므로 `${tool_input.X}` 를
써도 치환되지 않는다.

### 공통 필드

| 필드          | 타입            | 기본값      | 비고                                             |
|---------------|-----------------|-------------|--------------------------------------------------|
| `trigger`     | object          | (필수)      | `delay` / `cron` / `event` 중 정확히 하나        |
| `timeout`     | duration string | `1h`        | 이 시간을 넘은 fire 는 폐기 + WARN               |
| `maxAttempts` | integer ≥ 1     | `3`         | 누적 fire 횟수. 초과 시 envelope 자동 cancel     |
| `payload`     | string→string   | `{}`        | hook 이 다시 깨어났을 때 받을 임의 데이터        |
| `reason`      | string          | (필수, non-blank) | 로그/관측에 노출되는 사람이 읽는 사유      |

### 라이프사이클

1. **스케줄** — hook 이 처음 실행되어 `HookResult.asyncRewake(spec)` 또는
   handler config 의 `asyncRewake` 블록을 통해 spec 을 반환하면, 프레임워크가
   `RewakeService.schedule(envelope)` 로 envelope 을 등록한다.
   원래 turn 은 `ALLOW` 로 즉시 진행된다 — rewake 는 turn 을 차단하지 않는다.
2. **발사** — 트리거 조건이 만족되면 `RewakeService` 가 envelope 을 listener 에게
   전달한다. listener 는 `AgentRuntimeRegistry` 에서 원래 컨텍스트를
   재수화(re-hydrate) 하고, 발생 시점의 hook 만 단독으로 다시 호출한다
   (sibling hook 은 다시 호출되지 않음).
3. **재발사 / 종료** — 선언적 hook 은 fire 여부와 무관하게 매번 자기 spec 을 재부착한다
   (`DeclarativeRewake.attach` 는 최초 실행인지 재발사인지 구분할 수 없다 — 여기서 걸러내면
   최초 envelope 까지 사라진다). 체인의 상한은 `DefaultRewakeFireListener#chainFollowUps`
   에서 트리거별로 정해진다:
   - `delay` / `event` 는 1 회성이므로 fire 마다 다음 링크를 체이닝하며 `maxAttempts` 로 상한.
   - `cron` envelope 은 스케줄러의 네이티브 cron 트리거로 등록되어 **스스로 반복**하고
     `timeout` / `maxAttempts` 도달 시 멈추므로, follow-up 을 **체이닝하지 않는다**.
     체이닝하면 fire 마다 또 하나의 "스스로 반복하는" 계열이 생겨 live envelope 수가
     fire 당 2배로 분기한다(`~2^(maxAttempts-1)`).

   fire 직후 1 회성 envelope 은 pending 목록에서 제거된다.
4. **핫리로드 cancel** — `hooks.json` 편집으로 originating hook 이
   사라지면 (Java 관점에서 `hookId` 가 새 설정에 더 이상 존재하지 않으면)
   해당 hook 이 등록한 모든 pending envelope 이 swap 직후 자동으로 cancel 된다.

### 한계 & 알려진 제약

- **JVM 재시작 시 in-memory envelope 은 유실된다.** 영속이 필요하면
  `aimon-scheduling-quartz` 의 Quartz-backed `RewakeService` 를 wiring 해야 한다.
- **Rewake 체이닝은 `maxAttempts` 까지만 허용된다 (design §6.4 해결)** — rewake 로
  다시 호출된 hook 이 또 새 `RewakeSpec` 을 반환하면 listener 가 follow-up envelope 을
  스케줄한다. 단, `previous.attemptNumber + 1 > spec.maxAttempts` 이면 WARN 로그와 함께
  폐기된다 (기본 `maxAttempts=3`, 즉 첫 fire 1 + 체이닝 2 = 3 회까지). 체이닝을 활성화하려면
  bootstrap 에서 `DefaultRewakeFireListener.bindRewakeService(...)` 로 service 를 주입해야
  하며, 미주입 시 follow-up 은 INFO 로그와 함께 폐기된다.
- **PRE_TOOL 만 re-dispatch 된다** — Phase 4A iteration 에서는 `PreToolUse`
  envelope 만 listener 가 다시 hook 을 호출한다. 다른 이벤트 타입의 envelope 은
  스케줄은 되지만 발사 시점에 WARN 로그와 함께 폐기된다.
- **Class-keyed `hookId`** — `hooks.json` 으로 등록한 declarative hook 은 모두
  같은 자바 클래스를 공유하므로 기본 `hookId` 도 동일하다. 따라서 같은 클래스의
  hook 이 일부만 제거된 reload 에서는 cancel 이 발생하지 않는다 — 클래스 전체가
  사라져야 cancel 된다.
- **best-effort delivery** — agent context 가 사라졌거나 (`AgentRuntime`
  가 unregister 됐을 때) `RewakeCapableRuntime` 를 구현하지 않는 stub 컨텍스트면
  fire 는 WARN 로그를 남기고 조용히 폐기된다. 도착이 보장되는 신뢰성 있는 채널이
  아니라는 점에 주의.
- **Per-context quota (design §6.3 해결)** — `DefaultRewakeService.withQuotaManager(...)`
  로 `RewakeQuotaManager` 를 설치하면 `agentExecutionContextId` 단위로 동시 pending
  envelope 수가 제한된다. `DefaultRewakeQuotaManager` 는 기본 cap 64 (생성자로 변경
  가능, `setCustomQuota(contextId, cap)` 로 per-context override). cap 초과 시
  `schedule(...)` 은 envelope 을 폐기하고 WARN 로그를 남긴다 — 폭주하는 hook 이나
  설정 reload 가 스케줄러를 포화시키지 않도록 보호한다. 기본값은 `RewakeQuotaManager.NOOP`
  (무제한) 이므로 enforcement 는 opt-in.

> 자세한 설계 배경은 [`docs/design/hook/async-rewake.md`](../../design/hook/async-rewake.md) 참조.

---

## 예제 모음

### 1. 모든 Bash 호출을 감사(audit) 서버로 전송

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash",
        "hooks": [
          {
            "type": "http",
            "url": "https://audit.internal/aimon/pre-bash",
            "headers": { "X-Auth": "${env.AUDIT_TOKEN}" },
            "body": "{\"cmd\":\"${tool_input.command}\",\"invoker\":\"${context.invoker_name}\",\"iteration\":\"${context.iteration}\"}",
            "allowedEnvVars": ["AUDIT_TOKEN"],
            "timeout": 2,
            "failOpen": true
          }
        ]
      }
    ]
  }
}
```

감사 hook 은 가드가 아니므로 `"failOpen": true` 를 둔다 — 없으면 감사 서버에 닿지 못할 때마다 `Bash` 가 막힌다
([가드가 막는 경우](#가드가-막는-경우)). `aimon-cli` 는 이 handler 를 실행한다. 본문 템플릿은 값을 이스케이프하지 않으므로
따옴표가 든 명령은 깨진 JSON 으로 나간다 — [`http`](#http) 절의 "보내고 받는 것" 참조.

### 2. 위험한 명령을 즉시 차단

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash(rm -rf /*)",
        "hooks": [
          { "type": "deny", "reason": "위험한 rm -rf 명령은 차단됩니다." }
        ]
      }
    ]
  }
}
```

매처는 서브커맨드의 **글자**를 본다 — `rm -rf /` 와 `cd /tmp && rm -rf /var/lib` 는 걸리지만 `rm -fr /` 나
`sudo rm -rf /` 는 걸리지 않는다(뒤쪽까지 잡으려면 `Bash(*rm -rf /*)`). 표기를 바꿔 피할 수 있으면 안 되는 차단은 `command`
handler 가 `tool_input.command` 를 직접 검사하게 한다 — [Matcher 문법](#matcher-문법).

### 3. PostTool 에서 메트릭만 수집 (fail-soft)

커맨드는 템플릿 렌더링되지 않으므로 컨텍스트는 `AIMON_*` 환경 변수로 읽는다
(`${tool_name}` 이라고 쓰면 셸이 자기 변수로 해석해 빈 문자열이 된다).

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "*",
        "hooks": [
          {
            "type": "command",
            "command": "logger -t aimon \"tool=$AIMON_TOOL_NAME status=$AIMON_TOOL_RESULT_STATUS\"",
            "timeout": 1
          }
        ]
      }
    ]
  }
}
```

같은 값을 stdin JSON payload 에서 읽어도 된다 — 두 채널은 같은 맵에서 파생되므로 절대
드리프트하지 않는다:

```json
{
  "type": "command",
  "command": "jq -r '\"tool=\\(.tool_name) status=\\(.tool_result_status) path=\\(.tool_input.file_path)\"' | logger -t aimon"
}
```

### 4. MCP 정책 서버로 라우팅

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [
          {
            "type": "mcp",
            "server": "policy-server",
            "tool": "evaluate_write",
            "args": {
              "path": "${tool_input.file_path}",
              "invoker": "${context.invoker_name}"
            }
          }
        ]
      }
    ]
  }
}
```

`policy-server` 는 호스트가 그 에이전트에 연결한 MCP 서버의 이름이다 — `aimon-cli` 에서는 설정 파일의 `mcp.servers[].name`
이다. 가드이므로 서버에 닿지 못하거나 `timeout`(기본 10초) 안에 답이 없으면 `Edit` · `Write` 는 막힌다. 설정에 없는 서버
이름은 오류로 잡히지 않고 매 호출 판정 없음(`call failed: MCP server not registered`)이 되므로 이름을 맞춰 적는다. 그 서버의
도구는 다른 MCP 도구와 마찬가지로 모델의 도구 목록에도 올라간다.

### 5. 4-tier 레이어 결합 (USER + PROJECT + LOCAL)

`~/.aimon/hooks.json` (USER, 광역 audit):
```json
{ "hooks": { "PostToolUse": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "logger -t aimon-user \"$AIMON_TOOL_NAME\"" }] }] } }
```

`<project>/.aimon/hooks.json` (PROJECT, 팀 정책):
```json
{ "hooks": { "PreToolUse": [{ "matcher": "Bash(git push*--force*)", "hooks": [{ "type": "deny", "reason": "force push 금지" }] }] } }
```

`<project>/.aimon/hooks.local.json` (LOCAL, 개인 디버그):
```json
{ "hooks": { "PreToolUse": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "echo PRE \"$AIMON_TOOL_NAME\" >&2" }] }] } }
```

→ 디스패치 순서: `USER PostTool log` → `PROJECT PreTool deny` → `LOCAL PreTool echo`.

### 6. 외부 승인 대기 후 자동 재시도 (`asyncRewake` + `event` 트리거)

위험한 prod 배포 명령을 즉시 차단하지 않고, 외부 승인 webhook 이 들어올 때까지
기다렸다가 다시 hook 을 깨운다. 승인이 도착하지 않으면 1 시간 후 timeout.

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash(kubectl apply*-prod*)",
        "hooks": [
          {
            "type": "mcp",
            "server": "approval-gateway",
            "tool": "request_human_approval",
            "args": {
              "command": "${tool_input.command}",
              "invoker": "${context.invoker_name}"
            },
            "asyncRewake": {
              "trigger": { "event": { "type": "approval", "key": "prod-kubectl-apply" } },
              "timeout": "1h",
              "maxAttempts": 1,
              "reason": "awaiting human approval for prod kubectl apply"
            }
          }
        ]
      }
    ]
  }
}
```

외부 승인 시스템이 `RewakeService.resolve("approval", "prod-kubectl-apply", { "decision": "approved" })`
를 호출하면 envelope 이 발사되고 hook 이 다시 호출되어 최종 ALLOW/DENY 를 결정한다.

> ⚠️ `asyncRewake` 블록은 템플릿 렌더링을 거치지 않으므로 `event.key` 는 **리터럴 문자열**
> 이어야 한다. `${...}` 를 써도 치환되지 않고 그대로 매칭 키가 된다.

### 7. Skill frontmatter `hooks:` 블록

> ⚠️ SKILL.md frontmatter 는 `hooks.json` 과 **스키마가 다르다.** `SkillHookSetParser` 는
> Claude Code 이벤트 별칭(`PreToolUse` 등)도, entry 안에 중첩된 `hooks:` handler 배열도
> 받지 않는다. 이벤트 키는 AIMON 내부 이름이고, 각 entry 는 handler 배열 대신 단일
> `action:` 매핑을 갖는다.

```yaml
---
name: my-skill
description: ...
hooks:
  preTool:
    - matcher: "Read"
      action: { type: shell, command: "echo skill-pre-read >&2", timeoutMs: 5000 }
    - matcher: "Bash"
      action: { type: deny, reason: "이 skill 에서는 Bash 를 쓰지 않습니다" }
  postTool:
    - matcher: "*"
      action: { type: shell, command: "echo skill-post >&2" }
  onStart:
    - action: { type: shell, command: "echo skill-started >&2" }
---
```

frontmatter 스키마 요약:

| 항목            | 규칙                                                                            |
|-----------------|---------------------------------------------------------------------------------|
| 이벤트 키       | `onStart` / `preTool` / `postTool` / `onStop` / `subagentStart` / `subagentStop` / `permissionRequest` / `permissionDenied` / `preCompact` / `postCompact` |
| `matcher`       | `preTool` / `postTool` 에서만 허용 (생략 시 `"*"`). 다른 이벤트에 두면 파싱 실패  |
| `action.type`   | `shell` / `deny` / `http` / `mcp`. `deny` 는 `preTool` 전용, `http`·`mcp` 는 `preTool`·`postTool` 전용 |
| 타임아웃 필드   | `action.timeoutMs` (**밀리초**). frontmatter 에는 초 단위 `timeout` 별칭이 없다   |
| `failOpen`      | entry 수준 키(`matcher` · `action` 과 나란히). YAML 불리언만 받고 기본 `false`. 액션이 답을 내지 못했을 때 통과시킨다 — `shell` 의 종료 코드 없음 · exit 126/127, `http` · `mcp` 의 판정 없음 ([가드가 막는 경우](#가드가-막는-경우)) |

`onSessionStart` / `onSessionEnd` / `onConfigReload` 는 skill 호출 바깥(세션·애플리케이션
라이프사이클)에서 발사되므로 frontmatter 에서 거절된다 — `hooks.json` 에 선언한다.

위 hook 은 `my-skill` 이 **fork 한 에이전트**(와 그 에이전트가 띄운 fork)에만 적용되고, 스킬이 답을 돌려주면 끝난다. 같은
에이전트의 다른 세션이나 스킬을 호출한 쪽에는 발화하지 않으며, 런타임의 hook registry 에 등록되지 않는다. inline 스킬은
fork 가 없어 발화하지 않는다. 전체 규칙은 [`aimon-skill-extensions.md`](../../references/aimon-skill-extensions.md) 에 있다.

---

## 트러블슈팅

| 증상                                                                     | 원인 / 해결책                                                                |
|--------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `hooks.json` 을 수정해도 반영되지 않음                                    | 4-tier 중 어느 layer 에 있는지 확인. SKILL 은 skill 활성 시점에만 적용. CLI 외 환경(web) 은 핫리로드 미지원 — 재시작 필요. |
| 편집 후 2 초 안에 반영되지 않음                                           | mtime 이 갱신되었는지(`stat`) 확인. 초 단위 해상도 FS 에서는 같은 초에 두 번 저장하면 두 번째가 무시될 수 있음. |
| `OnConfigReload` 가 `failed=true` 로 발사됨                              | `failureReason` 의 파서 에러를 보고 JSON 문법/필수 필드를 검증. 라이브 registry 는 이전 상태 유지. |
| 시작 시 `Configuration error: hooks config … (… layer) is invalid: …` / `… could not be read: …` 로 종료 | 메시지가 가리키는 파일의 그 위치를 고치거나 파일을 치운다. 깨진 JSON · 알 수 없는 `type` · 0 이하 `timeout` · 읽을 수 없는 파일 · 파일 자리에 있는 디렉터리가 모두 여기로 온다. 파일 hook 없이 뜨는 일은 없다. |
| fork 가 `Execution blocked by OnStart hook [SUBAGENT/…]` 로 끝남          | `hooks.json`(또는 스킬 frontmatter)의 `onStart` hook 이 그 fork 를 막았다. 사용자 입력용 hook 이라면 `AIMON_INVOKER_TYPE` 이 `SUBAGENT` 일 때 exit 0 으로 빠지게 한다. |
| `Configuration error: hooks config … is invalid: <event> entry #n, handler #m: …` 로 CLI 가 뜨지 않음 | 가드 이벤트 아래에 적용할 수 없는 항목이 있다. 메시지가 가리키는 handler 를 고치거나 지운다. 관찰용이라 못 돌아도 되는 handler 라면 `"failOpen": true`. 전체 표는 [시작할 때 막는 경우](#시작할-때-막는-경우). |
| `WARN hooks: matcher '...' could not be parsed`                          | `postTool` 의 `PredicateParser` 문법 오류. fallback 으로 name-only 적용 중. (`preTool` 에서는 시작 실패.) |
| `deny` · 가드 hook 이 등록됐는데 한 번도 걸리지 않음                       | 매처가 해석은 되지만 아무 호출과도 맞지 않는다 — 정규식(`mcp__.*`, `\s+`) · `&` · `command=…` · 권한 패턴(`Bash(git:*)`)은 매처 문법이 아니고 오류도 나지 않는다. [Matcher 문법](#matcher-문법)의 형태로 고친다. |
| `WARN hooks: invalid handler in PROJECT on event 'postTool': ...`        | 가드가 아닌 이벤트에서 필수 필드 누락 (`command`/`url`/`server+tool`/`reason`). 해당 handler 만 스킵. |
| `WARN hooks: 'deny' is not valid on postTool ...`                        | `deny` 는 `preTool` 전용. 다른 이벤트에서는 handler 가 무시됨.                |
| `WARN hooks: '...' event is not supported by AIMON in this phase`        | `Notification` / `UserPromptSubmit` / `stop_hook_active` 뿐이다. 나머지는 모두 지원. |
| `WARN hooks: unknown event '...'`                                        | 오타이거나 AIMON 이 모르는 이벤트. [지원 이벤트 표](#지원-이벤트와-매핑)의 이름을 사용 (대소문자 무시). 가까운 이름이 있으면 `did you mean '…'?` 가 붙고, 그것이 가드 이벤트면 WARN 이 아니라 시작 실패다. |
| `WARN hooks: only 'command' actions are valid on ...`                    | `preTool`/`postTool` 외의 이벤트는 셸 handler 전용. 가드가 아닌 이벤트일 때만 WARN 이다. |
| `WARN hooks: 'command' on ... cannot run: the configured shell executor does not support shell actions` | 셸을 지원하지 않는 실행기(`NoOpShellActionExecutor`)로 `hooks.json` 을 적용했다. 가드가 아닌 `command` handler(가드가 아닌 이벤트, 또는 `failOpen: true`)는 등록되지 않는다 — `HostShellActionExecutor` 를 배선한다. 가드 이벤트의 `command` 라면 WARN 이 아니라 시작 실패다. |
| `WARN hooks config at ...: ... has a 'failOpen' that is not a JSON boolean (...); it is read as false` | `failOpen` 에 `"true"` · `1` · `null` 을 썼다. 그 handler 는 `failOpen: false` 로 등록된다(커맨드를 못 돌리면 막는다). 관찰용이라면 `true` 로 고칠 것. |
| 도구가 `Blocked: guard hook '...' could not run its command` 로 막힘      | 결정 채널이 있는 이벤트의 `command` handler 가 종료 코드를 내지 못했거나(timeout 등) 셸이 커맨드를 시작하지 못했다(`command not found: exit code 127` · `command not executable: exit code 126` — 스크립트 경로와 실행 권한을 확인). 사유의 원인을 고치거나, 관찰용 handler 라면 `"failOpen": true` 를 선언. 전체 표는 [가드가 막는 경우](#가드가-막는-경우). |
| 도구가 `Blocked: guard hook '...' could not get a verdict from its http call` (또는 `mcp call`) 로 막힘 | `preTool` 의 `http` · `mcp` handler 가 판정을 받지 못했다. 사유의 원인을 본다 — `action executor not wired`(호스트가 실행기를 배선하지 않았다 — `aimon-cli` 는 배선하므로 임베딩 호스트의 경우다), `call failed: MCP server not registered`(`server` 가 설정된 MCP 서버 이름이 아니다), `call failed: HTTP 307`(리다이렉트는 따라가지 않는다), `call failed: HTTP 503` · `call failed: ConnectException`(정책 서버), `timed out`, `response could not be read`(`decision` · `permissionDecision` 값이 아는 값이 아니다 — [http](#http) 의 표). 관찰용 handler 라면 `"failOpen": true`. |
| `WARN hooks: 'asyncRewake' is not supported on event '...'`              | rewake 가능한 이벤트는 `preTool`/`preCompact`/`onSessionStart`/`onSessionEnd`/`onConfigReload`. hook 자체는 정상 등록됨. |
| 셸 hook 이 exit 2 로 끝났는데 차단되지 않음                                | 해당 이벤트에 결정 채널이 없음. veto 는 `preTool`/`onStart`/`preCompact`(block), `permissionRequest`(deny) 에서만 유효. |
| 커맨드 안의 `${tool_input.x}` / `${tool_name}` 이 빈 문자열                | 의도된 동작. 커맨드는 렌더링되지 않는다 — stdin JSON payload 나 `AIMON_*` env(`$AIMON_TOOL_NAME` 등) 를 사용. |
| 긴 `timeout` 을 줬는데 30초에 잘림                                        | Phase 5 이전 동작. 현재는 handler 가 선언한 budget 이 hook policy timeout 이상이면 그물이 함께 넓어진다(+5초 grace). |
| handler 가 예상보다 1000배 빨리/느리게 타임아웃                           | `timeout` 은 이제 **초** 단위다 (Claude Code parity). 밀리초가 필요하면 `timeoutMs` 를 쓴다. 예전 설정은 값을 1000 으로 나눌 것. |
| `WARN Hook declared an execution budget of ... exceeding the maximum`     | 선언 budget 이 `MAX_DECLARED_BUDGET`(10분)을 넘어 클램프됨. 설정값을 줄일 것.  |
| SKILL.md frontmatter 의 `hooks:` 가 파싱 실패                             | frontmatter 는 `hooks.json` 스키마가 아니다. 이벤트 키는 AIMON 내부 이름, entry 는 중첩 `hooks:` 배열이 아니라 단일 `action:` 매핑. [예제 7](#7-skill-frontmatter-hooks-블록) 참조. |
| `${env.X}` 치환이 빈 문자열                                              | `allowedEnvVars` 화이트리스트에 `X` 가 없음 — 보안 정책상 차단됨.             |
| `WARN Hook returned N rewake spec(s) but no RewakeService is wired`      | application bootstrap 에서 `RewakeService` 가 `NOOP` 상태. 실제 동작하려면 `DefaultRewakeService` 또는 Quartz 기반 impl 을 wiring 해야 한다. |
| `Cron triggers require the Quartz-backed RewakeService impl`             | cron 트리거를 in-memory `DefaultRewakeService` 에 전달했음. `aimon-scheduling-quartz` 모듈을 의존성에 추가하고 `QuartzRewakeService` 를 wiring. |
| `asyncRewake.trigger.cron is not a valid five-field cron expression`     | Quartz 6 필드 표현식을 쓰고 있음. 초 필드를 떼고 `?` 를 `*` 로 바꾼다 (`"0 0 * * * ?"` → `"0 * * * *"`). 숫자 요일은 1씩 줄인다 (Quartz 일요일 `1` → 여기서는 `0`). |
| `... are both restricted, which means "either day" here but cannot be expressed in Quartz` | 일과 요일을 동시에 제한했음. 5 필드 cron 은 둘의 **합집합**이지만 Quartz 는 한쪽을 `?` 로 비워야 해서 합집합을 말할 수 없다. 훅을 두 개로 나눌 것. |
| Rewake 가 발사됐는데 hook 이 호출되지 않음                                | (1) agent context 가 registry 에서 사라졌거나 (2) 컨텍스트가 `RewakeCapableRuntime` 를 구현하지 않거나 (3) hot-reload 로 originating hook 이 제거된 경우. WARN 로그에 정확한 사유가 남는다. |

---

## 관련 문서

- [Hook Development Guide](hook-development-guide.md) — 프로그래매틱 hook 작성
- [Hook System Upgrade 설계](../../design/hook/hook-system.md) — 이 설정 체계가 왜 이렇게 생겼는지
- [Async Rewake Design](../../design/hook/async-rewake.md) — Phase 4A 설계 배경
- [Claude Code hooks.json reference](https://docs.claude.com/en/docs/claude-code/hooks)
