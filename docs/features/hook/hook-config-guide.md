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
7. [템플릿 변수](#템플릿-변수)
8. [Async Rewake (`asyncRewake`)](#async-rewake-asyncrewake)
9. [예제 모음](#예제-모음)
10. [트러블슈팅](#트러블슈팅)

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
- bootstrap 이 실패해도 CLI 는 WARN 로그만 남기고 계속 진행 — 이후 핫리로드는
  여전히 시도된다.

### 실패 모드

| 상황                              | 동작                                                          |
|-----------------------------------|---------------------------------------------------------------|
| 새 `hooks.json` 이 파싱 실패       | swap 하지 않음. 이전 hook 유지. `OnConfigReload(failed)` 발사 |
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
> 이벤트에 두면 부트스트랩이 entry 를 건너뛴다.

---

## Matcher 문법

`matcher` 는 어떤 도구 호출에 hook 을 적용할지를 결정한다. 비어 있거나
`"*"` 이면 모든 도구에 매치된다 (`NameOnlyPredicate.ANY`).

| 패턴                                  | 의미                                                                |
|---------------------------------------|---------------------------------------------------------------------|
| `Bash`                                | 도구 이름이 정확히 `Bash`                                           |
| `Read\|Write\|Edit`                   | 셋 중 하나                                                          |
| `mcp__.*`                             | 정규식 — `mcp__` 으로 시작하는 모든 도구                            |
| `Bash(command=^git\\s+push)`          | 도구 이름 + 입력 필드 매칭 (`PredicateParser`)                      |
| `Bash & input.command~^npm`           | 합성 — 이름과 입력 술어를 `&`/`\|` 로 결합                          |

- `PredicateParser` 가 해석하지 못하는 패턴은 `name-only` fallback 으로 떨어지고
  WARN 로그가 남는다.
- 정규식은 Java `Pattern` 문법을 따른다.

---

## Handler 타입

### `command`

쉘 명령을 실행한다. `ShellAction` + `ShellActionExecutor` 가 처리. **모든 이벤트에서
사용할 수 있는 유일한 handler 타입**이다 (`http` / `mcp` 는 `preTool`·`postTool` 전용,
`deny` 는 `preTool` 전용 — 다른 이벤트에 두면 entry skip + WARN).

```jsonc
{
  "type": "command",
  "command": "jq -r '.tool_input.file_path'",
  "timeout": 5      // 선택, 초 단위 (Claude Code parity). 미지정 시 30초
  // "timeoutMs": 500  // 대안: 밀리초 단위 별칭. 둘 다 있으면 timeoutMs 가 이긴다
}
```

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
| 그 외 | `WARN` 로그 + fail-soft (정상 진행). 깨진 스크립트가 조용한 게이트키퍼가 되면 안 된다. |

veto 는 **결정 채널이 있는 네 이벤트에서만** 효력이 있다. 나머지 이벤트의 exit 2 는
WARN 로그만 남기고 진행한다 (`AbstractDeclarativeShellHook#vetoResult`).

| 이벤트                | exit 2 의 효과                                                       |
|-----------------------|----------------------------------------------------------------------|
| `preTool`             | `block` — 도구 호출을 건너뛰고 stderr 가 tool 결과로 모델에 전달된다  |
| `onStart`             | `block` — `ExecutionBlockedByHookException` 으로 턴 자체가 중단된다   |
| `preCompact`          | `block` — AUTO compaction 스킵 / MANUAL 은 사유 보고                  |
| `permissionRequest`   | `deny` — 디스패치 전에 거부                                           |
| 그 외 9개 이벤트      | 무시 (WARN 로그 후 정상 진행)                                         |

> `onStart` 의 veto 는 이번 하드닝에서 추가되었다. 그 전에는 선언적 `onStart` hook 이
> exit 2 로 끝나도 아무 일도 일어나지 않았다.

**`timeout` 단위 (breaking change).** `timeout` 은 Claude Code 와 동일하게 **초(seconds)**
단위다. 밀리초가 필요하면 AIMON 고유 별칭 `timeoutMs` 를 쓴다. 둘 다 있으면 더 정밀한
`timeoutMs` 가 이긴다. 두 값 모두 양수여야 하며, 0 이나 음수는 파싱 단계에서 거절된다.

| 표기                 | 의미                                  |
|----------------------|---------------------------------------|
| `"timeout": 60`      | 60초 (60000 ms)                       |
| `"timeoutMs": 1500`  | 1500 ms — 1초 미만이 필요할 때        |

> ⚠️ **마이그레이션.** `timeout` 은 예전에 밀리초로 읽혔다. 그 시절 설정을 그대로 두면
> `"timeout": 5000` 이 5초가 아니라 **5000초** 로 해석된다(반대로 Claude Code 에서 가져온
> `"timeout": 60` 은 예전 바이너리에서 60 ms 였다). hook timeout 은 fail-soft 이므로 증상이
> 조용하다 — 기존 설정은 값을 1000 으로 나누거나 `timeoutMs` 로 키를 바꿔야 한다.

**`timeout` 과 hook policy.** 선언된 budget 은 executor 가 강제하며, 값이 hook policy 의
timeout(기본 30초) **이상**이면 executor 의 바깥 그물이 그만큼 **넓어진다**(+5초 grace).
따라서 `"timeout": 120` 같은 장시간 handler 도 30초에 잘리지 않는다. 정책 timeout 과 정확히
같은 값(예: `timeoutMs` 를 생략한 셸 handler 의 기본 30초)도 grace 를 받는다. 반대로 선언
budget 이 정책보다 짧으면 무시된다 — 그물을 좁혀 봐야 handler 자신의 deadline 과 경주할 뿐이다.
선언 budget 은 **10분(`MAX_DECLARED_BUDGET`)으로 클램프**되므로, 설정 실수가 턴을 무한정
붙잡아 둘 수 없다 (초과 시 WARN 로그 후 10분으로 잘림).

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

응답 본문은 `{ "decision": "deny" | "allow", "reason": "...", "updatedInput": {...} }`
JSON 스키마를 따르면 `HookResult` 로 자동 매핑된다. 스키마를 따르지 않으면 정상 통과.

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

응답이 `{decision, reason, updatedInput}` 모양이면 `HookResult` 로 매핑된다.

### `deny`

`preTool` 전용 short-circuit. 별도의 transport 없이 즉시 거절한다.

```jsonc
{
  "type": "deny",
  "reason": "Production rm -rf 는 정책상 차단됩니다."
}
```

- `preTool` 외의 이벤트에 두면 부트스트랩이 handler 를 건너뛰고 WARN 로그를 남긴다.
- 다른 이벤트에서 거부하려면 `command` handler 의 exit 2 를 쓴다. 단 exit 2 가 실제 결정으로
  이어지는 이벤트는 **`preTool` / `onStart` / `preCompact` (block) 와 `permissionRequest`
  (deny) 네 개뿐**이다. `postTool`, `onStop`, `onSessionStart`, `onSessionEnd`,
  `subagentStart`, `subagentStop`, `postCompact`, `permissionDenied`, `onConfigReload`
  에서는 exit 2 가 WARN 로그만 남기고 무시된다 — 이 아홉 이벤트에는 거부를 실을 결정 채널이
  아예 없다.
- `reason` 은 비어 있을 수 없다 (validation 실패 시 entry skip).

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
            "timeout": 2
          }
        ]
      }
    ]
  }
}
```

### 2. 위험한 명령을 즉시 차단

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Bash(command=^rm\\s+-rf\\s+/)",
        "hooks": [
          { "type": "deny", "reason": "위험한 rm -rf 명령은 차단됩니다." }
        ]
      }
    ]
  }
}
```

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

### 5. 4-tier 레이어 결합 (USER + PROJECT + LOCAL)

`~/.aimon/hooks.json` (USER, 광역 audit):
```json
{ "hooks": { "PostToolUse": [{ "matcher": "*", "hooks": [{ "type": "command", "command": "logger -t aimon-user \"$AIMON_TOOL_NAME\"" }] }] } }
```

`<project>/.aimon/hooks.json` (PROJECT, 팀 정책):
```json
{ "hooks": { "PreToolUse": [{ "matcher": "Bash(command=^git\\s+push.*--force)", "hooks": [{ "type": "deny", "reason": "force push 금지" }] }] } }
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
        "matcher": "Bash(command=^kubectl\\s+apply.*-prod)",
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

`onSessionStart` / `onSessionEnd` / `onConfigReload` 는 skill 호출 바깥(세션·애플리케이션
라이프사이클)에서 발사되므로 frontmatter 에서 거절된다 — `hooks.json` 에 선언한다.

위 hook 은 `my-skill` 이 활성화된 동안만 적용되고 비활성 시 자동으로 unregister 된다.

---

## 트러블슈팅

| 증상                                                                     | 원인 / 해결책                                                                |
|--------------------------------------------------------------------------|------------------------------------------------------------------------------|
| `hooks.json` 을 수정해도 반영되지 않음                                    | 4-tier 중 어느 layer 에 있는지 확인. SKILL 은 skill 활성 시점에만 적용. CLI 외 환경(web) 은 핫리로드 미지원 — 재시작 필요. |
| 편집 후 2 초 안에 반영되지 않음                                           | mtime 이 갱신되었는지(`stat`) 확인. 초 단위 해상도 FS 에서는 같은 초에 두 번 저장하면 두 번째가 무시될 수 있음. |
| `OnConfigReload` 가 `failed=true` 로 발사됨                              | `failureReason` 의 파서 에러를 보고 JSON 문법/필수 필드를 검증. 라이브 registry 는 이전 상태 유지. |
| `WARN hooks: matcher '...' could not be parsed`                          | `PredicateParser` 문법 오류. fallback 으로 name-only 적용 중.                 |
| `WARN hooks: invalid handler in PROJECT on event 'preTool': ...`         | 필수 필드 누락 (`command`/`url`/`server+tool`/`reason`). 해당 entry 만 스킵. |
| `WARN hooks: 'deny' is not valid on postTool ...`                        | `deny` 는 `preTool` 전용. 다른 이벤트에서는 handler 가 무시됨.                |
| `WARN hooks: '...' event is not supported by AIMON in this phase`        | `Notification` / `UserPromptSubmit` / `stop_hook_active` 뿐이다. 나머지는 모두 지원. |
| `WARN hooks: unknown event '...'`                                        | 오타. [지원 이벤트 표](#지원-이벤트와-매핑)의 이름을 사용 (대소문자 무시).    |
| `WARN hooks: only 'command' actions are valid on ...`                    | `preTool`/`postTool` 외의 이벤트는 셸 handler 전용.                          |
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
