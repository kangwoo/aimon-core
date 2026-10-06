# 훅 시스템 (Hook System)

> Status: **IMPLEMENTED** — 13개 이벤트, 2축 결과 모델, 선언적 `hooks.json` 4-tier 로더와 핫리로드,
> typed-token `HookRegistry` 까지 전부 들어가 있다. 참조 대상은 Claude Code 의 hook 스펙
> ([`references/hooks-specification.md`](../../references/hooks-specification.md)) 이며 JSON 포맷 호환을 유지한다.
>
> 적용 대상: `aimon-core` — `at.aimon.core.hook` (`HookRegistry`, `HookEventType`, `HookFeedback`),
> `at.aimon.core.hook.execution` (`HookResult`, `Decision`, `FlowControl`, `DefaultHookExecutor`),
> `at.aimon.core.hook.event` (13개 이벤트 + 컨텍스트), `at.aimon.core.skill.hook.declarative`
> (선언적 훅 · 술어 · 셸 페이로드), `at.aimon.core.skill.hook.action` (`ShellAction`/`HttpAction`/`McpToolAction`),
> `at.aimon.core.config.hook` (`hooks.json` 로더·머저·워처).
>
> 이 문서는 **왜 이 모양인가**를 담는다. 작성 규칙은
> [`.claude/rules/hook-development.md`](../../../.claude/rules/hook-development.md), 설정 문법은
> [`hook-config-guide.md`](../../features/hook/hook-config-guide.md) 가 소유한다.

---

## 1. 훅이 끼어드는 자리

훅은 에이전트 실행의 정해진 지점에서 발화하는 확장점이다. 이벤트는 13개이고 `HookEventType` 의 상수가
canonical 목록이다.

| 축 | 이벤트 |
|----|--------|
| 실행 수명 | `onStart` · `onStop` |
| 도구 | `preTool` · `postTool` |
| 권한 | `permissionRequest` · `permissionDenied` |
| 서브에이전트 | `subagentStart` · `subagentStop` |
| 컴팩션 | `preCompact` · `postCompact` |
| 라이브 세션 | `onSessionStart` · `onSessionEnd` |
| 설정 | `onConfigReload` |

`onSessionStart` / `onSessionEnd` 는 이름과 달리 **`LiveSession` 의 열기/닫기**에 발화한다 — 같은 세션이
재개되면 다시 발화한다 ([`glossary.md`](../../overview/glossary.md) §3 의 알려진 오칭).

---

## 2. 결과 모델 — 하나의 enum 이 아니라 두 개의 축

`HookResult` 는 두 축이 **독립**이다. 하나로 합치지 않은 것이 이 설계의 첫 결정이다.

| 축 | 값 | 읽는 곳 |
|----|-----|--------|
| `Decision` | `ALLOW` / `ASK` / `DENY` | `ASK` 를 읽는 것은 권한 체인뿐 |
| `FlowControl` | `CONTINUE` / `BLOCK` | 아래 §2.1 의 네 체인 |

여기에 부수 채널 넷이 붙는다 — `updatedInput` · `updatedOutput` (입출력 변형), `feedback` (모델에게 할 말),
`rewakeSpecs` (지연 재개 예약).

`HookResult.merge` 는 여러 훅의 결과를 두 축 각각에서 **가장 강한 값**으로 접는다 —
`deny > ask > allow`, `block > continue`. 결정론적이므로 훅 등록 순서가 결론을 바꾸지 않는다.

`deny(reason)` 의 사유는 `feedback` 필드에 들어간다. 즉 blocked 결과의 feedback 은 **그 자체가 거부 사유**이며,
advisory feedback 으로 한 번 더 노출하면 모델이 같은 말을 두 번 듣는다 (`HookFeedback.collectAdvisory` 가
blocked 결과를 걸러 내는 이유).

### 2.1 BLOCK 이 실제로 먹히는 곳은 넷뿐이다

거부권은 **호출부가 결과를 읽어야** 성립한다. 읽는 곳은 넷이다.

| 체인 | BLOCK 의 효과 |
|------|--------------|
| `preTool` | 도구 호출이 취소되고 사유가 tool result 로 모델에 돌아간다 |
| `permissionRequest` | 디스패치 전에 거부 |
| `onStart` | 메인 실행은 `ExecutionBlockedByHookException` 으로 턴 자체를 중단하고, 포크는 시작하지 않고 사유가 실린 실패 결과로 끝난다. 어느 쪽도 `onStop` 은 없다 |
| `preCompact` | AUTO 컴팩션은 건너뛰고, MANUAL 은 사유를 보고 |

나머지 아홉은 **advisory** 다 — `block()` 을 돌려줘도 조용히 무시된다. 새 이벤트에 거부권을 기대하는 훅을
쓰면 안 되는 이유다.

### 2.2 feedback 이 모델에게 닿는 경로도 셋뿐이다

| 체인 | 렌더링 방식 |
|------|------------|
| `permissionRequest` / `preTool` / `postTool` | `SingleToolInvoker` 가 **그 도구의 결과에 덧붙인다** |
| `onStart` | user role 메시지로 덧붙인다 (lifecycle 중 유일) |
| `preCompact` | 메시지가 아니라 요약 시스템 프롬프트의 custom instruction 으로 접힌다 |

도구 체인이 별도 user 메시지가 될 수 없는 이유는 프로토콜이다 — `tool_use` 와 그 `tool_result` 사이에는
user 턴이 낄 수 없다. 그래서 `HookFeedback` 이 `<system-reminder key="hook-feedback">` 블록으로 감싸
결과 본문에 실어 보낸다.

나머지 여덟 이벤트는 feedback 을 버린다. 부수효과 전용이며, 배선하는 것은 버그 수정이 아니라 기능 추가다.

---

## 3. 실행 모델

### 3.1 기본은 순차다

`HookExecutionPolicy.ExecutionMode` 는 `SEQUENTIAL` / `PARALLEL` 두 값이고, 팩토리 메서드 다섯 개가
**전부 `SEQUENTIAL`** 을 넘긴다 — 병렬은 정책을 직접 짜야 얻는다. 훅은 서로의
부수효과에 의존할 수 있고, `updatedInput` → 다음 훅 → `updatedOutput` 파이프라인은 순서에 의미가 있다.
병렬은 그 두 성질을 포기해도 좋다고 선언하는 opt-in 이어야 한다.

`stopOnBlocked` 는 **순차 모드 전용 플래그**다. SEQUENTIAL 에서 blocked 결과를 만나면 뒤의 훅을 실행하지
않고 단축한다. PARALLEL 에서는 **no-op** 이다 — `executeParallel` 은 이미 발사된 훅을 전부 기다려 모든
결과를 반환하며, 결과를 버리지도 실행을 취소하지도 않는다. blocked 여부는 호출부가 병합 결과에서 본다.

### 3.2 타임아웃은 바깥 그물이고, 선언된 예산은 바닥이다

각 훅 호출에는 `HookExecutionPolicy.timeout()`(기본 30s)이 걸린다. 구현은
`ExecutorService.submit` + 호출 스레드에서의 `Future.get(timeout)` + `cancel(true)` 다.
`CompletableFuture.supplyAsync` 를 쓰지 않은 이유는 둘이다 — `cancel(true)` 가 실제로 훅 스레드를
interrupt 하고, 풀 스레드가 다른 풀 스레드를 기다리지 않으므로 주입된 bounded pool 에서 데드락이 없다.

훅이 자기만의 긴 마감을 가질 때는 `ExecutionHook.getExecutionBudget()` 으로 선언한다. 그 값은
**floor 이지 override 가 아니다.**

- 정책 timeout 보다 **짧으면 무시**한다 — 좁혀 봐야 훅 자신의 graceful 결과와 경주할 뿐이다
- **같거나 길면** `DECLARED_BUDGET_GRACE`(5s)를 얹어 넓힌다 → 훅 내부 마감이 항상 먼저 이긴다
  (동률이 실제로 흔하다: `ShellAction.DEFAULT_TIMEOUT` 도 30s 다)
- 상한은 `MAX_DECLARED_BUDGET`(10분)이고 넘으면 WARN 후 절단한다

이 정책이 없으면 바깥 그물이 먼저 잘라 버려, 액션이 스스로 만들 수 있었던 제대로 된 `HookResult` 대신
뭉툭한 cancel 이 나간다. 병렬 모드에서 timeout 은 **대기를 제한할 뿐 이미 끝난 작업을 버리지 않는다.**

**그물이 터졌을 때의 뜻도 훅이 선언할 수 있다(EE-64).** 정책의 `TimeoutBehavior` 는 체인 전체의 기본값이고, `DefaultHookExecutionManager` 가
이벤트에 주는 기본 정책은 전부 `FAIL_OPEN` 이다(`HookExecutionPolicy.failClosedStopOnBlocked()` 는 호스트가 골라 쓰는 팩토리다). 거부가 목적인 훅에는 틀린 답이다 — 그물에 끊긴 가드는 아무 말도 하지 않았고, 통과시키면 "느려지는 것" 이
가드를 끄는 방법이 된다. 그래서 `ExecutionHook.getTimeoutBehavior()` 로 훅이 자기 동작을 선언하고, 실행기는
`timeoutBehaviorFor(hook)` 로 정책보다 그 선언을 따른다. 선언적 가드 훅(`failOpen` 아님)은 `FAIL_CLOSED` 를 선언한다. 정책
기본값을 바꾸지 않은 이유는 그것이 프로그램으로 등록한 모든 훅의 동작을 바꾸기 때문이다 — 그 훅들은 여전히 정책을 따르고,
`onStart` 정책(`continueOnExceptionAndNeverStop`) 아래에서 던지거나 그물에 끊긴 `OnStartHook` 이 성공으로 읽히는 것도
그대로다.

### 3.3 예외는 정책이 매핑한다 — 인터럽트는 아니다

훅은 던지지 않아야 하지만, 새어 나온 예외는 `HookExecutionPolicy.onException` 이 `HookResult` 로 매핑한다.
`failClosedStopOnBlocked` 정책에서는 그 매핑이 버그를 block 으로 바꾼다 — 의도된 선택이며, 그래서 기본
정책은 fail-open 이다.

**`FAIL_CLOSED` 를 선언한 훅은 이 매퍼도 타지 않는다.** §3.2 의 선언은 처음에 그물(timeout)에만 걸렸고, 판정 없이 끝나는
길이 둘 더 남아 있었다 — 풀이 훅을 받지 않는 것(`RejectedExecutionException`: 포화, 또는 닫힌 풀)과 훅 본문이 던지는
것이다. 선언적 가드는 **액션**이 던진 것은 스스로 잡아 fail-closed 로 읽지만 그 `try` 밖 — matcher 술어, 컨텍스트 접근,
`Error` — 은 실행기까지 올라왔고, 출하 정책의 매퍼는 그것을 성공으로 읽었다. 그래서 `failsClosedWithoutVerdict(hook)` 이
참이면 두 경우 모두 BLOCKED 다.

그 길을 실제로 낸 것은 `BashSubcommandPredicate` 였다 — 중첩된 `$(` 마다 재귀해 깊이를 모델이 골랐고,
`StackOverflowError` 가 난 `Bash(rm *)` 가드는 그 호출을 통과시켰다. 분할기는 이제 깊이(64단계)와 길이(65,536자)에 상한을
두고, 넘는 명령은 나누지 않은 채 **매치로** 답한다. "맞지 않는다" 로 답하면 한 단계 더 중첩하는 것으로 모든 `Bash(…)`
매처를 비켜 갈 수 있고(셸은 그 명령을 실행한다), 던지면 `postTool` · `failOpen` 훅은 기록 없이 지나간다 — 매처가 정하는
것은 훅에게 **물을지**뿐이므로, 볼 수 없을 때는 묻는다. 실행기의 분기는 그대로 둔다: matcher 는 `ToolInputPredicate` 이면
무엇이든 될 수 있다.

같은 원칙이 짝 없는 따옴표에도 걸린다. 분할기는 짝이 없는 따옴표 · 백틱 · `$(` 에서 명령 전체를 한 조각으로 돌려주고
그만두었고, 그래서 `echo "it's"; rm -rf x` 의 `rm` 이 매처에게 보이지 않았다(큰따옴표 안의 `'` 는 셸에게 글자다). 이제 그
글자를 글자로 읽고 계속 나누며, 전체도 한 조각으로 함께 둔다 — 전에 맞던 것은 그대로 맞는다. 주석(`#`)은 **대신** 읽지
않고 **더해서** 읽는다: `#` 가 있는 명령은 주석 안의 따옴표와 치환을 읽지 않는 채로 한 번 더 나누고, 두 읽기의 조각을
모두 본다. 주석 읽기로 바꿔 치우면 분할기가 주석으로 읽고 셸은 그러지 않는 `#`(큰따옴표 안, 이스케이프된 공백 뒤) 뒤의
`$(…)` 가 읽히지 않는다 — 셸은 그것을 실행한다. 첫 구현이 그렇게 했고 리뷰의 차등 프로브가 잡았다. 분할기를 고칠 때 볼
것은 하나다: `main` 의 것과 나란히 돌려 **전에 맞던 것이 여전히 맞는가.** here-document 는 읽지 않는다.

글롭 자체도 모델이 고른 글에 돈다. `*` 를 `.*` 로 옮긴 정규식은 뒤의 리터럴이 실패하면 앞의 `*` 로 되돌아가 모든 배치를
시도하므로(`*a*a*b` 대 `a` 8,000자는 100초 안에 끝나지 않았다), `NameOnlyPredicate.compileGlob` 은 두 `*` 사이의 리터럴을
**첫 출현에서 고정**한다 — 가장 이른 자리가 뒤에 가장 많은 글을 남기므로 잃는 매치가 없다.

같은 선언을 쓰고 새 선언을 만들지 않았다 — "느렸다" · "시작하지 못했다" · "죽었다" 가 서로 다른 값을 가질 수 있으면 열린
쪽이 곧 우회로이기 때문이다. 반대로 정책의 `timeoutBehavior()` 로는 **떨어지지 않는다**: 아무것도 선언하지 않은 훅의 실패는
매퍼의 질문이고, `FAIL_CLOSED` timeout 과 관대한 매퍼를 함께 쓰는 정책에서 프로그램 훅의 동작이 바뀌면 안 된다. 사유는
고정 문자열에 예외의 **타입 이름**만 붙는다(메시지는 싣지 않는다).

닫힌 풀은 종료 순서가 일부러 만드는 상태다(`TeardownPhase.HOOK_EXECUTOR`). 그 단계는 `SESSIONS` · `AGENT_RUNTIMES` 뒤라
가드를 발화시킬 턴이 남아 있지 않고, 종료 중에 발화하는 `onStop` · `onSessionEnd` 는 아무것도 선언하지 않는 advisory
이벤트다. 드레인 시한을 넘겨 살아남은 실행의 도구 호출이 그래도 도착하면 거절은 즉시 돌아오므로 기다림은 생기지 않고,
그 호출은 가드 없이 실행되는 대신 막힌다.

**인터럽트는 이 매퍼를 타지 않는다.** 매퍼가 답하는 질문은 "훅이 *실패*하면 어떻게 할 것인가" 인데,
인터럽트는 훅의 실패가 아니라 **그 판정을 기다릴 이쪽의 능력**이 끊긴 것이다. 넘겨 버리면 묻지도 않은
질문에 fail-open 이 적용되고, 결과는 보안 사고다 — 플래그가 살아 있는 스레드에서 `future.get` 은 기다리지
않고 던지므로 체인의 모든 훅이 **돌지도 않은 채 SUCCESS** 로 보고되고, BLOCKED 를 내려던 PreTool 훅이 조용히
allow 로 강등된다. 그래서 인터럽트로 끊긴 대기는 정책과 무관하게 BLOCKED 다. 판정이 없다는 것은 진행해도
좋다는 허가가 없다는 뜻이고, 이 경로는 턴을 모는 스레드가 실제로 인터럽트됐을 때 — 즉 어차피 취소되는
턴에서만 — 닿는다. 근거와 나머지 방어층은
[`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) §8 에 있다.

---

## 4. 무엇에 붙일 것인가 — `ToolInputPredicate`

이름만 보는 매처로는 "`Bash` 중 `git push` 만" 같은 것을 표현할 수 없다 — 초기 `ToolMatcher` 가 그랬고,
그래서 제거됐다. `ToolInputPredicate` 는 이름과 **인자**를 함께 본다.

| 구현 | 판정 |
|------|------|
| `NameOnlyPredicate` | 도구 이름 |
| `BashSubcommandPredicate` | `command` 의 서브커맨드 |
| `PathGlobPredicate` | 경로 파라미터의 글로브 |
| `CompositePredicate` | 위의 AND/OR 조합 |

Claude Code 풍 `if` 문법은 `PredicateParser` 가 위 구현들로 번역한다. 그 문법이 만드는 것은 **이름 · `도구(글롭)` · 그 둘의
OR(`|`)** 뿐이다 — `CompositePredicate.and` 는 코드에서만 닿고, 정규식과 입력 필드 지정은 어느 구현에도 없다. 문법에 없는
표기는 대개 파싱 오류가 아니라 **아무것도 맞추지 못하는 이름이나 글롭**이 되므로(괄호 없는 항은 통째로 이름이다), 사용자
문서가 문법을 틀리게 적으면 그대로 옮긴 가드는 조용히 꺼진다. 사용자 쪽 정본은
[`hook-config-guide.md` › Matcher 문법](../../features/hook/hook-config-guide.md#matcher-문법)이고,
`DocumentedMatcherGrammarTest` 가 그 표의 각 행을 파서에 고정한다. `…declarative.predicate` 하위 패키지는 SPI 가 아니라 **impl** 이다. 바깥에서 닿을 수 있는 것은 부모
패키지의 `ToolInputPredicate` 인터페이스뿐이며, `PackageDependencyArchitectureTest`
가 이 두 규칙(하위 클래스는 전부 `ToolInputPredicate` 구현일 것 · 허용된 호출자 밖에서 import 금지)을 빌드에서
강제한다.

---

## 5. 선언적 훅 — 설정으로 붙이는 훅

`hooks.json` 과 SKILL.md frontmatter 로 코드 없이 훅을 붙인다. 소스는 4종(`HookConfigSource`)이며
`USER(10) → PROJECT(20) → LOCAL(30)` 순으로 덮어쓰는 레이어드 3-tier 에 **스코프 격리된** `SKILL(0)` 이
따로 선다 — 스킬 훅은 우선순위 경쟁에 참여하지 않고 자기 스킬 범위 안에서만 산다. 그 범위는 **그 스킬의 포크와 포크의
후손**이다: 스킬 훅은 런타임의 `HookRegistry` 에 등록되지 않고, 포크가 디스패치하는 레지스트리 위에 얹힌다
(`SkillScopedHookRegistry`). 같은 에이전트의 다른 세션과 스킬을 호출한 실행은 그 훅을 보지 못한다.

핸들러는 셋이다 — 셸(`ShellAction`), HTTP(`HttpAction`), MCP 도구(`McpToolAction`).

### 5.1 신뢰 경계 — 셸 커맨드는 렌더링하지 않는다

`TemplateRenderer` 는 `${tool_input.X}` / `${env.X}` / `${context.X}` 세 prefix 를 치환하지만
**HTTP·MCP 액션에만** 적용된다. `ShellAction.command` 는 호스트 셸에 **verbatim** 으로 전달된다 —
커맨드 안의 `${tool_input.x}` 는 자리표시자가 아니라 셸 변수다.

이유는 인젝션이다. 모델이 만든 도구 입력을 커맨드 문자열에 문자 치환으로 끼워 넣으면 그 값이 곧 셸
문법이 된다. 대신 컨텍스트는 **out-of-band** 로만 간다 — `ShellHookPayload` 가 발화 컨텍스트를 JSON 으로
**stdin** 에 실어 보내고, 보조로 `AIMON_*` 환경변수를 준다.

### 5.2 exit 2 = veto

셸 훅은 fire-and-forget 이 아니다. **exit 2 는 거부**이고 stderr 가 사유다(Claude Code parity).
효력이 있는 곳은 결정 채널을 가진 네 이벤트뿐이다 — `preTool`/`onStart`/`preCompact` 는 block,
`permissionRequest` 는 deny. 나머지는 로그만 남기고 진행한다.

**exit 2 외의 non-zero 는 허용**이다. 깨진 스크립트가 조용한 게이트키퍼가 되면 안 된다. 예외는 셸 자신이 보고하는 두 코드다 —
**126(실행할 수 없음) · 127(찾지 못함)** 은 스크립트가 돌지 않았다는 뜻이므로 결정 채널을 가진 네 이벤트에서는 아래 "종료
코드 없음" 과 같이 읽는다(`ShellHookOutcome.asGuardAnswer()`). 스크립트가 스스로 그 코드를 낼 수도 있어 구별되지 않지만,
가드 스크립트가 없는 환경에서 가드가 통과로 바뀌는 쪽이 더 나쁘다(EE-66).

**종료 코드가 없으면 거부다(fail-closed).** 명령을 돌리지 못했거나 끝나지 않았을 때 — 실행 환경 없음 · 사용 불가, timeout,
셸 실패 — 실행기는 원인을 실어 보고하고(`ShellHookOutcome.notRun(cause, detail)`), 결정 채널을 가진 네 이벤트의 훅은 그것을
거부로 읽는다. "스크립트가 틀린 답을 냈다"(non-zero)와 "답을 못 들었다"(종료 코드 없음)를 가르는 것이다: 앞쪽을 막으면 깨진
감사 스크립트가 게이트키퍼가 되고, 뒤쪽을 통과시키면 가드를 느리게 만들거나 환경을 떨어뜨리는 것으로 가드를 끌 수 있다.
같은 이벤트가 가드와 감사 두 용도로 쓰이므로 훅마다 `failOpen: true` 로 빠질 수 있다. 사유에는 원인만 싣는다 — 명령
문자열(비밀이 들어갈 수 있다)도, 푸는 방법(그 글을 읽는 것은 가드를 받는 쪽이다)도 싣지 않는다.
**`http` · `mcp` 핸들러도 같은 규칙을 탄다(EE-65).** `preTool` 에서 두 실행기는 "판정" 과 "판정 없음" 을 갈라 보고한다
(`ActionCallOutcome`) — 정책 서버가 `deny` 라고 답한 것은 판정이고, 닿지 못했거나 답을 읽을 수 없는 것은 판정 없음이다.
판정 없음은 셸의 "종료 코드 없음" 과 같은 자리(`ShellHookVerdicts`)에서 같은 규칙으로 읽는다: 막고, `failOpen: true` 면
통과한다. `postTool` 은 전처럼 WARN 후 진행한다.

**두 실행기를 누가 배선하는가.** 코어는 실행기를 만들지 않는다 — `HookRegistryApplier` 와 `SkillHookSetParser` 가 받은 것을
훅에 넘길 뿐이다. 트리 안에서 배선하는 조립은 `aimon-cli` 하나이고(`HookActionExecutors`), `hooks.json` 과 스킬 frontmatter
양쪽에 같은 실행기를 준다. 순서가 문제였다: 스킬 파서는 번들을 읽기 위해 스택보다 먼저 만들어지고 파싱된 훅은 그때 받은
실행기를 계속 쥐는데, `McpClientManager` 는 런타임이 만들어진 뒤에야 있다. 그래서 `McpActionExecutor.lateBound(supplier)` 로
만들어 두고 런타임이 서면 묶는다. 매니저는 agent-scoped 이고 런타임이 닫는다(`AGENT_RUNTIMES`) — 실행기는 빌려 쓸 뿐 닫지
않으며, Java 17 의 `HttpClient` 에는 닫을 것이 없어 종료 순서에 올릴 항목도 없다. CLI 설정에 MCP 서버가 없으면 MCP 실행기는
`null` 이다: 항상 실패하는 실행기를 주면 `mcp` 가드가 로드된 뒤 걸리는 호출을 전부 막지만, 없다고 말하면 시작에서 멈춘다.

`aimon-bootstrap` 과 스타터는 배선하지 않는다. 이유는 둘이다. (1) 스택은 런타임을 여러 개 가질 수 있고 `McpClientManager` 는
런타임마다 하나인데, 실행기는 **발화한 런타임**을 모른다(`attempt` 가 `HookContext` 를 받지 않는다) — 스택 전역 실행기는
엉뚱한 에이전트의 서버를 부르게 된다. (2) 스킬의 `http` 액션은 호스트 JVM 에서, 호스트의 환경 변수와 네트워크로 나간다.
CLI 에서는 스킬의 `shell` 액션이 이미 같은 권한으로 도므로 새로 주는 것이 없지만, 실행 환경이 샌드박스인 호스트에서는 스킬
파일이 샌드박스 밖으로 요청을 보내는 길이 된다. 그 선택은 호스트가 `AimonStackSpec.skillParser` 와
`HookHotReloadBootstrap.Builder` 로 직접 한다.

**기본 HTTP 클라이언트는 좁게 잡았다.** `HttpActionExecutor.createDefault()` 는 리다이렉트를 따라가지 않고(JDK 클라이언트는
리다이렉트 대상에 요청 헤더를 다시 보낸다 — `${env.X}` 로 채운 토큰이 설정에 없는 호스트로 가고 그 호스트의 답이 판정이
된다), 응답 본문을 `MAX_RESPONSE_BYTES`(1 MiB)까지만 읽는다. 둘 다 "판정 없음" 으로 떨어지므로 가드는 닫힌 채 남는다.
남겨 둔 것은 결정이 필요한 것들이다 — 평문 `http://` 허용, `allowedEnvVars` 가 그 선언 자신이 적는 목록이라는 점, 템플릿
값이 이스케이프되지 않아 JSON 본문에 모델의 텍스트가 그대로 들어간다는 점. 사용자 문서는 셋을 그대로 적는다
([`hook-config-guide.md` › `http`](../../features/hook/hook-config-guide.md#http)).

**취소는 "돌리지 못함" 이 아니다(EE-80).** 훅의 셸 명령은 실행의 취소 신호(`HookContext.getExecutionCancellation()`)에
묶여 돌고, 인터럽트가 오면 그 신호로 멈춘다 — 포그라운드 `Bash` 와 같은 길이다(EE-54). 그렇게 멈춘 명령은
`Unrun.CANCELLED` 로 보고되고, 가드 이벤트에서는 `failOpen` 이어도 막는다. 훅 실행기가 기다리던 스레드의 인터럽트를
정책과 무관하게 BLOCKED 로 답하는 것(§3.3)과 같은 이유다: 판정이 없으면 진행 허가도 없고, 이 경로는 실행이 어차피 끝나는
중일 때만 닿는다. 스레드 인터럽트에 반응하는 셸(`LocalShell`)이 `InterruptedException` 으로 끝낸 것도 같은 원인으로 읽어
두 종류의 셸에서 결과가 같다. 신호는 그것을 쥐고 있는 발화 지점만 싣는다 — 도구 범위 이벤트와 fork 의 `onStart`. 이미
일어난 일을 알리는 `postTool` · `permissionDenied` 는 실행이 취소되기 전까지만 싣는다(취소 뒤의 감사 명령이 시작조차 못 하는
것을 막는다).

사유 문자열은 `MAX_DENY_REASON_LENGTH`(4000자)로 자른다 — 훅이 스택트레이스를 통째로 뱉어 대화 컨텍스트에
무제한 주입되는 것을 막는다.

### 5.3 훅 id 는 내용에서 파생되고 리로드에 안정적이다

`ExecutionHook.getHookId()` 의 기본 구현은 클래스 이름이다. 선언적 훅은 같은 클래스가 여러 번 등록되므로
그대로 두면 전부 같은 id 를 공유하고, async-rewake 라우팅과 핫리로드 취소가 엉뚱한 훅을 건드린다.

`DeclarativeHookId.of(class, skillName, discriminator)` → `<class>@<skillName>#<discriminator>` 가 답이다.
discriminator 는 설정 소스와 entry/handler 위치에서 파생되므로 **내용이 같으면 리로드해도 같은 id** 이고,
무관한 편집이 남의 rewake 를 취소하지 않는다.

### 5.4 핫리로드는 트랜잭셔널 swap 이다

`HookConfigWatcher` 가 3-tier `hooks.json` 을 디바운스 폴링하고, `HookRegistryReloader` 가 라이브
`DefaultHookRegistry` 에 반영한다. 교체는 LIFO undo + 원래 순서 재등록 rollback 을 갖춘 트랜잭셔널
swap 이며, 반영 후 `onConfigReload` 를 발사한다. **프로그래매틱하게 등록된 훅은 swap 대상이 아니다.**

지원하지 않는 Claude Code 이벤트 이름은 조용히 버리지 않고 WARN 한다. 현재 미지원은
`HookEventName.UNSUPPORTED` 의 셋 — `notification` / `userpromptsubmit` / `stop_hook_active` 뿐이다.

---

## 6. 레지스트리 표면 — 이벤트당 메서드가 아니라 typed token

이벤트 13개 × (register/unregister/get/clear) = 52개 메서드였던 `HookRegistry` 를 **5개**로 접었다.

```java
<H extends ExecutionHook<?>> void register(HookEventType<H> type, H hook);
<H extends ExecutionHook<?>> boolean unregister(HookEventType<H> type, H hook);
<H extends ExecutionHook<?>> List<H> getHooks(HookEventType<H> type);
boolean isEmpty();
void clearAll();
```

이벤트 종류는 `HookEventType<H extends ExecutionHook<?>>` 라는 typed token 이 표현하고,
`HookEventType.values()` 가 13개 상수의 canonical 순서를 보장한다. 타입 안전성은 그대로다 —
`register` 가 `H` 만 넣는다는 invariant 덕분에 unchecked cast 는 `getHooks` 한 군데에만 있다.

효과는 표면 축소가 아니라 **새 이벤트 추가 비용**이다. `HookRegistryReloader` 의 13행 하드코딩
테이블이 `for (HookEventType<?> t : HookEventType.values())` 한 줄이 되었고, 이벤트를 하나 늘리는 데
필요한 것은 `HookEventType` 상수 한 줄뿐이다. BC shim 은 두지 않았다(clean break).

---

## 7. 기각한 대안

| 대안 | 왜 기각했나 |
|------|------------|
| `ExecutionMode` 기본값 `PARALLEL` | 훅은 서로의 부수효과에 의존할 수 있고 `updatedInput` 파이프라인은 순서가 의미다. 병렬은 opt-in 이어야 안전하다 |
| `Decision` 에 `MODIFY` 추가 | 입력 변형은 **결정 축이 아니다**. `updatedInput`/`updatedOutput` 이라는 별도 채널이 담당한다 |
| `stopOnBlocked` 가 병렬도 단축 | `executeParallel` 은 발사된 훅을 끝까지 기다려 전 결과를 반환한다. 단축하려면 취소 의미론을 새로 정의해야 하는데 그 값이 작다 |
| timeout 을 `CompletableFuture.orTimeout` 으로 | cancel 이 훅 스레드를 실제로 interrupt 하지 못하고, bounded pool 에서 풀 스레드가 풀 스레드를 기다려 데드락이 난다 |
| 선언된 budget 을 override 로 | 정책보다 짧은 budget 을 존중하면 훅 자신의 graceful deadline 과 경주하게 된다. floor 로만 쓴다 |
| `TemplateRenderer` 가 셸 커맨드도 렌더 | 모델이 만든 값이 셸 문법이 된다. 컨텍스트는 stdin JSON + env 로만 |
| 셸 훅은 fire-and-forget, exit code 무시 | 참조 스펙과의 parity 가 깨진다. exit 2 = veto |
| 훅 id 를 클래스 이름으로 | 같은 이벤트에 핸들러가 여럿이면 id 가 충돌해 rewake·핫리로드가 엉뚱한 훅을 건드린다 |
| 이벤트당 메서드 4개 유지 | 이벤트 추가마다 인터페이스·구현·리로더 3곳을 고쳐야 한다 |

---

## 8. 남은 것

- **미지원 이벤트 셋** — `notification` / `userpromptsubmit` / `stop_hook_active` 는 파싱 단계에서 WARN 후 스킵
- **feedback 을 버리는 여덟 이벤트** — 배선은 기능 추가이지 버그 수정이 아니다(§2.2)
- **advisory 이벤트의 거부권** — 아홉 이벤트에는 결정 채널 자체가 없다. 필요해지면 호출부부터 만들어야 한다

---

## 부록: 참조 파일 지도

| 파일 | 확인할 것 |
|------|----------|
| `hook/HookEventType.java:44-89` | 13개 상수 = canonical 이벤트 목록 |
| `hook/HookRegistry.java` | 5개 메서드 표면 |
| `hook/execution/Decision.java` · `FlowControl.java` | 두 축의 값 |
| `hook/execution/HookResult.java:299-304` | 2축 + 부수 채널 4개 |
| `hook/execution/HookResult.java:270` | `merge` 의 접기 규칙 |
| `hook/execution/HookExecutionPolicy.java:24,30` | `DEFAULT_TIMEOUT` 30s, `DECLARED_BUDGET_GRACE` 5s |
| `hook/execution/HookExecutionPolicy.java:41` | `MAX_DECLARED_BUDGET` 10분 clamp |
| `hook/execution/HookExecutionPolicy.java:107,289` | `stopOnBlocked` 순차 전용, `ExecutionMode` 두 값 |
| `hook/execution/DefaultHookExecutor.java:310-336` | `submit` + `get(timeout)` + `cancel(true)` |
| `hook/HookFeedback.java:53,72` | `hook-feedback` 리마인더 키, blocked 결과 제외 |
| `skill/hook/declarative/TemplateRenderer.java:41,62` | 3개 prefix, 셸 제외 |
| `skill/hook/declarative/predicate/` | 4개 predicate impl + `PredicateParser` |
| `config/hook/HookConfigSource.java:17-26` | `USER`/`PROJECT`/`LOCAL` 가중치와 격리된 `SKILL` |
| `skill/hook/declarative/ShellHookOutcome.java:34,45` | `DENY_EXIT_CODE=2`, 사유 4000자 clamp |
| `skill/hook/declarative/DeclarativeHookId.java:49` | `<class>@<skill>#<discriminator>` |
| `config/hook/HookEventName.java:53` | 미지원 이벤트 셋 |
| `config/hook/HookRegistryReloader.java` | 트랜잭셔널 swap |

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

---

## 관련 문서

- [`.claude/rules/hook-development.md`](../../../.claude/rules/hook-development.md) — 훅 작성 규칙 (이 문서의 규범 대응물)
- [`hook-config-guide.md`](../../features/hook/hook-config-guide.md) — `hooks.json` 스키마, 4-tier 레이어, 이벤트 매핑 표, 템플릿 변수
- [`hook-development-guide.md`](../../features/hook/hook-development-guide.md) — 프로그래매틱 훅 작성
- [`async-rewake.md`](async-rewake.md) — 훅이 예약하는 지연 재개
- [`references/hooks-specification.md`](../../references/hooks-specification.md) — parity 경계 (매핑·확장·미지원)
