# Hook Spec Parity — Claude Code ↔ AIMON

> AIMON 의 `hooks.json` 은 **Claude Code 의 훅 설정 포맷과 호환**된다. 이 문서는 그 호환의
> **경계**를 기록한다 — 무엇이 그대로 통하고, 무엇이 이름만 같고, 무엇이 아예 없는가.
>
> 상류 스펙 본문은 여기에 복제하지 않는다. 원문은
> [Claude Code Hooks reference](https://docs.claude.com/en/docs/claude-code/hooks) 를 본다.
> AIMON 쪽 설정 문법·예제·트러블슈팅은
> [`hook-config-guide.md`](../features/hook/hook-config-guide.md) 가 소유한다.

이 문서는 **레퍼런스**다. 설계 근거는 [`design/hook/hook-system.md`](../design/hook/hook-system.md),
작성 규칙은 [`.claude/rules/hook-development.md`](../../.claude/rules/hook-development.md) 에 있다.

---

## 1. 왜 이 문서만 남았는가

한때 이 자리에는 Claude Code 훅 문서를 옮겨 적은 993줄짜리 사본이 있었다. 그것을 걷어낸 이유는 셋이다.

- **소유하지 않은 스펙은 드리프트한다.** 상류가 이벤트를 추가해도 사본은 조용히 낡는다. 실제로
  낡아 있었다 (§4).
- **AIMON 이 쓰지 않는 것까지 규범처럼 읽혔다.** `claude --debug`, `/hooks` 메뉴,
  `$CLAUDE_PROJECT_DIR`, `~/.claude/settings.json` 은 AIMON 에서 아무것도 하지 않는다. AIMON 은
  `.aimon/hooks.json` 파일 tier 3개를 읽고(`HookConfigLoader`), 여기에 스킬 frontmatter 를 네 번째
  소스로 얹는다.
- **정작 필요한 것은 델타뿐이다.** 포맷을 그대로 쓸 수 있다는 약속이 어디서 끊기는지 — 그것만
  적어 두면 된다.

---

## 2. 이벤트 매핑

권위는 `at.aimon.core.config.hook.HookEventName` 이다. 로더는 **Claude Code 이름과 AIMON 이름을
모두** 받으며, 대조는 대소문자를 가리지 않는다 (`preToolUse` 도 resolve 된다).

### 2.1 그대로 통하는 것 — 7개

| Claude Code | AIMON | 차단 가능 |
|-------------|-------|----------|
| `PreToolUse` | `preTool` | Yes |
| `PostToolUse` | `postTool` | No |
| `Stop` | `onStop` | No |
| `PreCompact` | `preCompact` | Yes |
| `SessionStart` | `onSessionStart` | No |
| `SessionEnd` | `onSessionEnd` | No |
| `SubagentStop` | `subagentStop` | No |

`HookEventName.toClaudeCode(...)` 가 이 표의 역방향이다.

### 2.2 AIMON 전용 — 6개

`hooks.json` 에 AIMON 이름으로 적으면 동작하지만, Claude Code 로 가져가면 무시된다.

| AIMON | 언제 |
|-------|------|
| `onStart` | 턴 시작 (차단 가능) |
| `postCompact` | 압축 완료 후 |
| `subagentStart` | 서브에이전트 생성 시 |
| `permissionRequest` | 권한 판정 시 (deny 가능) |
| `permissionDenied` | 권한이 거부된 뒤 |
| `onConfigReload` | `hooks.json` 핫리로드 반영 후 |

합쳐서 **13개**가 `HookEventType` 의 전부다.

### 2.3 받지 않는 것 — 3개

`HookEventName.UNSUPPORTED` 에 있는 이름은 **조용히 버리지 않고 WARN 후 스킵**한다. 설정을 옮겨 온
사람이 "왜 안 걸리지" 로 시간을 쓰지 않게 하기 위한 것이다.

`notification` · `userpromptsubmit` · `stop_hook_active`

여기에 없는 상류 이벤트(`PostToolUseFailure`, `TeammateIdle`, `TaskCompleted` 등)는 매핑에도
UNSUPPORTED 에도 없으므로 **알 수 없는 이벤트로 스킵**된다.

---

## 3. 핸들러 타입

권위는 `HookHandlerSpec.Type` 이다.

| `type` | 출처 | 비고 |
|--------|------|------|
| `command` | Claude Code parity | 셸 명령 |
| `http` | Claude Code parity | HTTP 웹훅 |
| `mcp` | **AIMON 확장** | MCP 도구 호출 |
| `deny` | **AIMON 확장** | `preTool` 거부 단축형 — Claude Code 는 `permissionDecision: "deny"` 를 쓴다 |

Claude Code 의 `prompt`(LLM 단일 평가) 와 `agent`(서브에이전트) 핸들러는 **AIMON 에 없다.**
그 타입이 든 설정은 파싱 단계에서 `HookConfigParseException` 으로 실패한다 — 이벤트 이름과 달리
WARN 후 스킵되지 않는다 (`HookConfigLoader.loadOptional` 이 잡는 것은 IO 오류뿐이다). 설정을
가져올 때 먼저 걷어내야 하는 것이 이 둘이다.

### 3.1 `timeout` 은 초 단위다 (과거 breaking change)

`timeout` 은 Claude Code 와 동일하게 **초**로 읽는다. 밀리초가 필요하면 AIMON 확장인 `timeoutMs` 를
쓴다. 한때 `timeout` 을 밀리초로 읽던 시절이 있었고, 그때는 Claude Code 에서 가져온 설정이 예산을
1000배 적게 받았다 — 훅 타임아웃은 fail-soft 라 증상이 "훅이 그냥 안 걸린다" 뿐이었다
(`HookHandlerSpec` javadoc).

---

## 4. 알려진 불일치 — `PermissionRequest` / `SubagentStart`

`HookEventName` 의 javadoc 은 `permissionRequest` 와 `subagentStart` 를 **"Claude Code 에 대응물이
없는 AIMON 확장"** 이라고 적고 있고, 그래서 `AIMON_TO_CC` 역매핑에서 빠져 있다. 그러나 상류 훅
스펙에는 두 이벤트가 **존재한다** — 이 javadoc 이 쓰인 뒤에 추가된 것으로 보인다.

실제 효과는 이렇다.

- **정방향은 문제없다.** `CC_TO_AIMON` 이 `permissionrequest` / `subagentstart` 를 받으므로,
  Claude Code 철자로 적힌 설정도 resolve 된다.
- **역방향만 빈다.** `toClaudeCode(...)` 가 두 이벤트에 대해 `Optional.empty()` 를 준다.

역방향 매핑을 쓰는 곳이 늘어나기 전에 정리하는 편이 낫다. 손대기 전에 상류 스펙에서 두 이벤트의
페이로드가 AIMON 것과 실제로 같은지부터 확인할 것 — 이름만 같고 필드가 다르면 매핑하는 쪽이 더 나쁘다.

---

## 5. 설정 위치는 공유하지 않는다

포맷은 같아도 **파일이 놓이는 자리는 다르다.** `HookConfigLoader` 는 `.claude/` 를 보지 않는다.

| Tier | AIMON | (Claude Code) |
|------|-------|---------------|
| USER | `~/.aimon/hooks.json` | `~/.claude/settings.json` |
| PROJECT | `<project>/.aimon/hooks.json` | `.claude/settings.json` |
| LOCAL | `<project>/.aimon/hooks.local.json` | `.claude/settings.local.json` |

AIMON 은 훅을 **별도 `hooks.json`** 에 두지, Claude Code 처럼 `settings.json` 안에 중첩하지 않는다.

계층 병합은 **덮어쓰기가 아니라 additive** 다 (`HookConfigMerger`) — 세 tier 에 있는 항목이 전부
발화한다. USER → PROJECT → LOCAL 순으로 **뒤에 append** 되므로, 같은 matcher 를 공유하면 나중
tier 가 디스패치 순서에서 뒤에 서고 순서에 의존하는 결과(`updatedInput` 스레딩 등)만 사실상
"이긴다". "LOCAL 이 PROJECT 를 지운다" 로 읽으면 안 된다.

스킬/에이전트 frontmatter 훅은 양쪽 다 지원하며, AIMON 쪽 시맨틱은
[`aimon-skill-extensions.md`](aimon-skill-extensions.md) 에 있다.

---

## 6. 셸 훅의 계약

| 항목 | AIMON | Claude Code parity |
|------|-------|-------------------|
| 컨텍스트 전달 | stdin JSON (`ShellHookPayload`) + `AIMON_*` 환경변수 | 필드 이름은 `AIMON_` 을 떼고 소문자화한 것 — `AIMON_TOOL_NAME` → `tool_name` |
| exit 0 | 통과 | ✔ |
| exit 2 | **거부**, stderr 가 사유 | ✔ |
| 그 외 non-zero | **허용** — 깨진 스크립트가 조용한 게이트키퍼가 되면 안 된다 | ✔ |
| 사유 길이 | 4000자에서 자른다 (`MAX_DENY_REASON_LENGTH`) | AIMON 이 정한 상한 |

exit 2 가 실제로 효력을 갖는 곳은 결정 채널이 있는 네 이벤트뿐이다 —
`preTool` / `onStart` / `preCompact` 는 block, `permissionRequest` 는 deny. 나머지는 로그만 남고
진행한다.

환경변수 이름은 **공유하지 않는다.** `$CLAUDE_PROJECT_DIR` 는 AIMON 에 없다.

---

## 관련 문서

- [`hook-config-guide.md`](../features/hook/hook-config-guide.md) — `hooks.json` 문법·예제·트러블슈팅
- [`hook-development-guide.md`](../features/hook/hook-development-guide.md) — 프로그래매틱 훅
- [`design/hook/hook-system.md`](../design/hook/hook-system.md) — 결과 모델과 설계 근거
- [`design/hook/async-rewake.md`](../design/hook/async-rewake.md) — 훅이 예약하는 지연 재개
- [Claude Code Hooks reference](https://docs.claude.com/en/docs/claude-code/hooks) — 상류 원문
- [Claude Code Hooks guide](https://docs.claude.com/en/docs/claude-code/hooks-guide) — 상류 사용 가이드
