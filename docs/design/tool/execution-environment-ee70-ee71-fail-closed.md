# 설계 — EE-70 · EE-71: 포크의 `onStart` block 과 시작 시 `hooks.json` 오류를 막는 쪽으로

> Status: **IMPLEMENTED** (2026-10-04). 백로그 항목 EE-70 · EE-71 의 설계이고, 설계 리뷰에서 승인된 그대로다(한 차례 — 차단
> 지적 없이 통과, 비차단 지적 여덟 건). 아래 본문(§1~§9)은 승인본에서 한 글자도 바꾸지 않았다 — 소스 인용(`c0335e4` 기준)과
> 열린 질문 번호까지 그대로다. 그래서 본문에는 **사실이 아닌 서술**이 남아 있다. 고친 것은 본문이 아니라 §10 에 적는다.
>
> 더한 것은 §10 하나다 — 구현이 이 설계에서 벗어난 곳과 그 이유, 반영한 리뷰 지적, 열린 질문이 어떻게 되었는지. 특히
> §10.1(F13 의 전제는 틀렸다 — 메모리 큐는 실패 시점에 스택 소유가 아니다; "포크를 빼려면 `failOpen: true`" 는 과장이다;
> Spring 의 root cause 는 이 설계가 말한 메시지가 아니다)을 먼저 볼 것. §9 의 열린 질문 가운데 이 변경 밖으로 결과가 번지는
> 것은 [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) 로 옮겼다
> (EE-72 ← Q2, EE-73 ← Q4, EE-74 ← Q3, EE-75 ← Q5 · Q6). 열림/닫힘의 정본은 그 문서다. 직전 노트는
> [`execution-environment-ee49-ee51-ee58-isolation-boundary.md`](execution-environment-ee49-ee51-ee58-isolation-boundary.md)
> 이고 두 항목의 출처가 그 §10.7 이다.

> 기준 커밋 `c0335e4` (PR #207 머지 직후, 2026-10-04). 아래의 파일·줄 인용은 모두 이 커밋에서 직접 읽은 것이다.
> 경로 접두사 `core/` = `modules/aimon-core/src/main/java/at/aimon/core/`.

## 1. 문제

EE-51 은 "판단하지 못한 가드는 막아야 가드다" 를 정했지만 두 군데가 여전히 열려 있다. **(EE-70)** 포크가 도는
`DefaultSubagentExecutor.fireOnStart`(`core/subagent/execution/DefaultSubagentExecutor.java:589`)는 `onStart` 결과를
`HookFeedback.collectAdvisory` 로만 읽어 block 을 버린다. 메인 실행(`OrcaAgentExecutor.checkOnStartHooks`, `:1518`)은 block 이면
`ExecutionBlockedByHookException` 을 던져 턴을 시작하지 않는다. 스킬 훅은 포크에서만 발화하므로(EE-49) 스킬 frontmatter 의
`onStart` 가드는 exit 2 도, "명령을 돌리지 못함" 도 막지 못하고, `hooks.json` 의 `onStart` 도 포크 안에서는 같다.
**(EE-71)** 시작 시 `hooks.json` 하나가 파싱에 실패하면 `HookRegistryReloader.bootstrap()`(`core/config/hook/HookRegistryReloader.java:147`)
이 예외를 WARN 으로 삼키고 `false` 를 돌려주며, `HookHotReloadBootstrap.start()` 는 WARN 한 줄을 더 찍고 계속한다 — 한 계층의
오타가 **모든 계층**의 파일 가드를 끈 채 에이전트가 돈다. 메인테이너 결정(2026-10-04)은 둘 다 막는 쪽이다.

## 2. 착수 전에 소스로 확인한 사실

설계가 기대는 전제이고, 몇 개는 TASK 의 서술과 다르다.

1. **메인 실행은 `onStart` block 에서 `onStop` 을 발화하지 않는다.** `checkOnStartHooks` 는 ReAct 루프 밖(`execute()` 의
   `try`, `:1169`)에서 던지고, `onStop` 을 부르는 `handleExecutionError` 는 루프 안의 `catch` 에서만 호출된다. 예외는
   `execute()` 밖으로 전파된다(결과 객체가 아니다). LLM 호출도 없다.
2. **포크는 예외가 아니라 결과 객체로 끝난다.** `runReActLoop` 의 모든 종료 경로가 `SubagentExecutionResult` 를 돌려주고,
   실패 경로(`createFailureResult`, `:1176`)는 `onStop(success=false)` 을 발화한다.
3. **실패한 포크 결과는 이미 모든 스폰 경로에서 부모에게 전달된다.** `DefaultSubagentExecutionManager.runResolvedSubagent`
   (`:599`)가 결과를 그대로 돌려주고 `subagentStop` 을 스폰한 쪽 레지스트리에서 발화한다. `Task` 포그라운드는
   `Status: FAILURE` + `Result: <오류>` + `Completion reason:` (`TaskTool.formatSubagentResult`), 백그라운드는
   `BackgroundTaskState.FAILED` + 완료 알림의 detail(`finalizeBackgroundTask`, `completionDetail`), 스킬 포크는
   `SkillForkOutcome.failure(result.getErrorMessage())` → `"Skill fork failed for '<skill>': …"`(`LlmSkillExecutor.executeFork`),
   워크플로 단계는 `AgentStepResult.isSuccess() == false`. 즉 **새 전달 경로는 필요 없다.**
4. **선언 `onStart` 훅은 이미 block 을 낸다.** `DeclarativeOnStartHook.canVeto()` 는 `true` 이고 `vetoResult` 는
   `HookResult.block` 이다 — exit 2 와 EE-51 의 "돌리지 못함" 이 같은 통로로 나온다. 버려지는 곳은 실행기 한 군데다.
5. **`onStart` 를 발화하는 곳은 둘뿐이다**(`OnStartContext.builder()` 사용처: `OrcaAgentExecutor`, `DefaultSubagentExecutor`).
   이름에 코드 behavior(`SubagentBehavior`)가 등록된 서브에이전트는 ReAct 루프를 대체하며 `onStart` 를 발화하지 않는다
   (`runResolvedSubagent` 의 `subagentBehaviorRunner.run` 분기). → §9 Q4.
6. **`hooks.json` 을 실제로 배선하는 조립은 CLI 하나다.** `HookHotReloadBootstrap` 의 main 호출처는
   `aimon-cli` 의 `AgentSetupFactory.setupHookHotReload`(`:1038`)뿐이다. `aimon-bootstrap` 과 `aimon-spring-boot-starter`
   에는 `config.hook` 참조가 없다(`TeardownPhase` 의 Javadoc 과 `LiveSessionOpener:74` 의 "호스트가 시작 시 한 번
   `start()` 를 부를 것" 이라는 안내뿐). TASK 가 말한 "각 경로의 동작" 가운데 두 경로는 **호스트 코드가 부르는 `start()`**
   를 통해서만 존재한다.
7. **CLI 는 `decorate` 가 던지면 스택을 닫지 않는다.** `AimonStackBuilder.build` 실패는 정리하지만(`AgentSetupFactory:628`),
   그 뒤 `decorate`(`:654`) 안에서 `setupHookHotReload` 가 던지는 경로에는 정리가 없다. 지금은 던질 일이 거의 없어 드러나지
   않았고, EE-71 이 그 경로를 일상적인 것으로 만든다.
8. **파싱 예외의 메시지에 파일 경로가 없다.** `JacksonHookConfigParser.parse(InputStream)` 은
   `"Failed to parse hooks JSON: " + e.getMessage()` 만 싣고(Jackson 메시지에 line/column 은 있다), `HookConfigLoader.loadOptional`
   은 경로를 덧붙이지 않는다. 알 수 없는 `type`(`HookHandlerSpec:102`)과 0 이하 timeout(`:378`·`:385`)은
   `IllegalArgumentException` 으로 던져져 Jackson 이 감싸므로 같은 `HookConfigParseException` 으로 나온다.
9. **"있지만 읽지 못한 파일" 도 지금 fail-open 이다.** `loadOptional` 은 비정규 파일과 `UncheckedIOException` 을 WARN 후
   "없음" 으로 친다(클래스 Javadoc 이 "intentional fail-soft" 라고 적는다). → §3.2 · §9 Q1.
10. **적용 단계에는 WARN 후 건너뛰는 층이 따로 있다.** `HookRegistryApplier.applyEntry` 는 잘못된 핸들러(`command` 없음,
    `preTool` 밖의 `deny`, 잘못된 URL)와 모르는 이벤트 이름을, `HookConfigMerger` 는 모르는/미지원 이벤트를 WARN 후 건너뛴다.
    파싱 실패가 아니라 항목 단위의 누락이다. → §9 Q2.
11. `onStart` 의 실행 정책은 `continueOnExceptionAndNeverStop`(`DefaultHookExecutionManager:542`)이다 — 프로그램으로 등록한 훅이
    던지거나 실행기 수준 timeout 이 터지면 성공으로 읽힌다. 메인과 포크가 같고 EE-64 와 같은 계열이다. 이 변경은 건드리지 않는다.

## 3. 접근

### 3.1 EE-70 — 포크는 `onStart` block 에서 루프를 시작하지 않고, 실패 결과로 끝난다

`fireOnStart` 를 메인의 `checkOnStartHooks` 와 같은 모양으로 바꾼다: 결과에 block 이 있으면
`ExecutionBlockedByHookException(InvokerType.SUBAGENT, <서브에이전트 이름>, "OnStart", reasons)` 를 던지고, 없으면 지금처럼
advisory 를 `<system-reminder>` 로 붙인다. `runReActLoop` 의 catch 사슬에 이 예외 전용 분기를 `catch (Exception)` 앞에 두어
**block 전용 결과**를 만든다(같은 메서드가 `MaxIterationsExceededException` 을 던지고 잡는 기존 관용과 같다).

```java
// 예시 — 모양만
} catch (ExecutionBlockedByHookException e) {
    return createBlockedResult(lc, e);   // onStop 을 발화하지 않는다
}
```

결정한 것:

| 질문 | 결정 | 근거 |
|---|---|---|
| 부모가 받는 모양 | `SubagentExecutionResult.failure(e.getMessage(), snapshot, metadata, CompletionReason.ERROR, zeroCost)` — iteration 0, 토큰 0 | 전제 3: 모든 스폰 경로가 실패 결과를 이미 부모에게 싣는다. 메시지는 메인과 같은 `ExecutionBlockedByHookException` 의 문구(실행기 종류 · 이름 · `OnStart` · 사유)라 사유가 그대로 간다 |
| `onStop`(포크 안) | **발화하지 않는다** | 전제 1 — 메인과 맞춘다. 시작하지 않은 실행에는 멈춤도 없다. `createFailureResult` 를 재사용하지 않는 이유가 이것이다 |
| `subagentStop`(스폰한 쪽) | 지금처럼 `success=false` + 사유로 발화 | 스폰한 실행의 이벤트이고 `subagentStart` 는 이미 나갔다. 알 수 없는 서브에이전트 이름 실패와 같은 모양이다 |
| LLM 호출 · 도구 | 없음 | block 검사가 루프 진입 전이다 |
| block 과 함께 온 advisory | 버린다 | 메인도 던지기 전에 붙이지 않는다. 읽을 모델이 없다 |
| 로그 | WARN 한 줄(스택 없이) | 메인의 `logError` 가 이 예외를 WARN 으로 찍는다 |
| 백그라운드 tail | `stream(lc, "\n[ended: " + msg + "]\n")` | 실패 경로와 같은 종료 표지 — tail 이 끝을 안다 |
| 백그라운드 `Task` | 변경 없음 — `FAILED`, 알림 detail 에 사유 | 전제 3 |
| 워크플로 단계 | 변경 없음 — 그 단계가 실패(`isSuccess()==false`, `ERROR`). 다른 단계는 각자 `onStart` 를 맞는다 | fan-out 의 각 포크가 독립 실행이다 |
| 재개(`Task resume`) | 재개된 포크도 `onStart` 를 다시 맞고, block 이면 같은 결과 | 지금도 재개마다 발화한다. 스냅샷에는 goal 까지 들어간다(첫 LLM 호출 전 실패와 같은 모양) |
| EE-51 "돌리지 못함" | 추가 코드 없음 | 전제 4 — 같은 `HookResult.block` 이다. `failOpen: true` 면 훅이 성공을 내므로 통과한다 |
| `hooks.json` 핸들러 | 추가 코드 없음 | 포크의 레지스트리(런타임 레지스트리 또는 그 위의 스킬 뷰)에 이미 있고 같은 체인으로 나온다 |

그 뒤 `SkillHookSet.GUARD_EVENTS` 에 `HookEventType.ON_START` 를 되돌린다(PR #207 리뷰 전의 넷, 원래 순서). 결과로
`onStart` 만 선언한 스킬도 `hasGuards()` 가 참이 되어 그 포크 안의 백그라운드 `Workflow`/`WorkflowJs` 와 `ScheduleTask` 가
거절된다(`HookRegistryAccess.activeSkillGuards` 경유 — 판정 코드는 그대로).

**기각한 대안**

- **`CompletionReason.BLOCKED` 를 새로 둔다.** 공개 enum 이고 `JsonTaskResultCodec` 이 이름으로 직렬화한다. 외부 저장소의
  망라 `switch` 와 옛 노드의 역직렬화가 깨질 수 있는데, 이 값으로 분기할 독자가 없다(부모 모델은 메시지를 읽는다 —
  stalled guard 가 `ERROR` 를 쓴 것과 같은 판단, `createStalledResult` Javadoc). 릴리스 직전에 SPI 를 넓힐 이유가 없다.
- **예외를 `execute()` 밖으로 전파한다(메인과 문자 그대로 같게).** 매니저의 `catch (Exception)` 이
  `"Subagent execution error: …"` 로 감싸 빈 스냅샷의 실패 결과를 만든다 — 우연히 동작하지만 접두사가 사유를 오류처럼 보이게
  하고, `SubagentExecutor` 를 직접 부르는 호출자에게는 새 예외 계약이 된다(SPI 동작 변경).
- **`createFailureResult` 재사용.** `onStop` 이 발화해 메인과 어긋난다.
- **스폰 도구가 `ToolResult.error` 로 바꿔 돌려준다.** 스폰 지점마다(`Task` · 스킬 · 워크플로 · 외부 도구) 같은 번역을 해야 하고,
  `Task` 는 지금도 실패한 포크를 `Status: FAILURE` 의 성공 결과로 돌려준다 — `aimon-cli` 의 `SubagentResultDisplayHook` 이 그
  블록을 파싱한다. 한 종류의 실패만 모양이 달라진다.
- **advisory 를 계약으로 확정한다.** 메인테이너가 기각했다(TASK).

### 3.2 EE-71 — 시작 시 로드 실패는 예외로 전파되어 기동을 멈춘다

`HookRegistryReloader.bootstrap()` 이 예외를 삼키지 않고 전파한다. `HookHotReloadBootstrap.Builder.start()` 는 그것을 그대로
내보낸다(감시자는 아직 만들어지지 않았으므로 정리할 것이 없다). `reload()` 는 건드리지 않는다 — 실패하면 이전 설정 유지 +
`OnConfigReload(successful=false)`.

- **메시지에 파일과 위치를 싣는다.** `HookConfigLoader.loadOptional` 이 `HookConfigParseException` 을 잡아 경로와 계층을 붙여
  다시 던진다(원인 보존):
  `hooks config <절대경로> (<USER|PROJECT|LOCAL> layer) is invalid: <파서 메시지 — line/column 포함>`.
  로더에서 붙이므로 핫 리로드의 `failureReason` 도 같이 좋아진다(동작은 그대로).
- **파일 없음은 그대로 정상**이다(`Files.exists` 가 거짓 → 계층 부재).
- **있지만 읽을 수 없는 파일도 로드 실패로 본다(이 설계의 결정, §9 Q1 에서 거부 가능).** 비정규 파일과 열기/읽기 I/O 오류를
  WARN-후-부재에서 `HookConfigParseException("hooks config <경로> (<계층> layer) could not be read: …")` 으로 바꾼다.
  `chmod 000` 한 번으로 그 계층의 가드가 전부 꺼지는 것은 EE-71 과 같은 실패이고, 같은 메서드의 두 줄이다. 핫 리로드에서는
  "그 계층이 조용히 빠진다" 가 "리로드 실패, 이전 설정 유지" 로 바뀐다 — TASK 가 지키라는 규칙에 **더** 맞는다.
- **`bootstrap()` 의 `boolean` 과 `Started.isBootstrapSucceeded()` 는 남긴다**(시그니처 불변). 이제 항상 `true` 이고 Javadoc 에
  그렇게 적는다. 폐기 표시(`@Deprecated`)는 이 변경에서 하지 않는다.
- **적용 단계의 실패**(`applyToManagedLocked` 가 던짐)도 같이 전파된다. 그 경로는 롤백 후 다시 던지므로 레지스트리는 손대지
  않은 상태다.

**탈출구: 설정 스위치를 두지 않는다.**

1. 탈출구는 이미 있다 — 오류 메시지가 가리키는 파일을 고치거나 치우는 것. 파일이 없으면 정상 기동이다.
2. "깨져도 띄운다" 스위치는 EE-71 이 닫는 바로 그 상태(모든 계층의 가드가 꺼진 채 실행)를 설정 한 줄로 되살리고, 한번 켜지면
   남는다.
3. 임베딩 호스트에게는 코드 수준의 명시적 탈출구가 생긴다 — `start()` 를 `try/catch (HookConfigParseException)` 로 감싸는 것.
   기본은 막고, 풀려면 코드를 써야 한다(TASK 의 "기본은 막는 쪽, 명시적으로만" 을 SPI 추가 없이 만족).
4. 필요가 확인되면 나중에 빌더 옵션으로 **더하는** 것은 호환 변경이다. 지금 넣었다 빼는 것은 아니다.

**경로별 동작**

| 경로 | 동작 | 사용자에게 보이는 것 |
|---|---|---|
| `HookHotReloadBootstrap.start()` / `HookRegistryReloader.bootstrap()` (코어) | `HookConfigParseException` 전파. 레지스트리에 파일 훅 없음, 감시자 없음 | 예외 메시지(경로 · 계층 · 원인) |
| CLI (`AgentSetupFactory`) | `setupHookHotReload` 의 예외를 `ConfigurationException`(`at.aimon.cli.exception`)으로 감싸 던진다. **던지기 전에 스택을 닫는다**(전제 7). `AimonCli` 의 기존 `catch (ConfigurationException)` 이 받는다 | `Configuration error: hooks config /…/.aimon/hooks.json (PROJECT layer) is invalid: …` + 기존 설정 오류 종료 코드. REPL 은 뜨지 않는다 |
| `aimon-bootstrap` (`AimonStackBuilder`) | 변경 없음 — `hooks.json` 을 배선하지 않는다(전제 6). 호스트가 부른 `start()` 가 던진다 | 호스트의 기동 실패 |
| `aimon-spring-boot-starter` | 변경 없음 — 배선하지 않는다. 호스트의 `@Bean` 에서 부른 `start()` 가 던지면 `BeanCreationException` 으로 컨텍스트가 뜨지 않는다 | Spring 의 기동 실패 리포트, root cause 에 위 메시지 |

**기각한 대안**

- **실패한 계층만 빼고 나머지를 적용**(백로그 EE-71 이 언급). 여전히 fail-open 이다 — 빠진 계층이 가드를 가진 계층일 수 있다.
- **모든 가드 이벤트를 막는 "닫힌" 상태로 기동**(같은 곳). 뜨긴 뜨지만 아무 도구도 못 쓰는 에이전트가 되어 원인이 기동 오류
  한 줄보다 찾기 어렵고, 합성 가드 훅과 그것을 푸는 리로드 경로라는 새 상태가 생긴다.
- **`bootstrap()` 은 `false` 를 유지하고 `start()` 만 던진다.** 원인 예외가 `start()` 까지 오지 않는다(로그에만 남는다).
  `bootstrap()` 을 직접 부르는 호스트는 계속 fail-open 이다.
- **새 예외 타입(`HookConfigLoadException`)**. 공개 타입이 하나 늘고, 리로드 쪽 `catch (RuntimeException)` 외에는 구분해 쓸
  독자가 없다. I/O 실패에 "Parse" 라는 이름이 덜 맞는 것은 Javadoc 으로 감당한다.
- **`--allow-broken-hooks` 플래그 / 환경 변수.** 위 "탈출구" 의 이유.

## 4. 바뀌는 인터페이스와 데이터

타입 · 메서드 시그니처 · 와이어 형식의 변경은 **없다**. 바뀌는 것은 동작과 Javadoc 이다.

| 표면 | 전 | 후 |
|---|---|---|
| `SubagentExecutor.execute` (`DefaultSubagentExecutor`) | `onStart` block 무시, 루프 진행 | block 이면 `success=false`, `CompletionReason.ERROR`, iteration 0, `errorMessage` = block 문구. `onStop` 미발화 |
| `SkillHookSet.guardEvents()` / `hasGuards()` | `preTool` · `permissionRequest` · `preCompact` | + `onStart` |
| `HookRegistryAccess.activeSkillGuards` (간접) | `onStart` 만 있는 스킬은 빈 목록 | 그 스킬을 포함 → 백그라운드 워크플로 · `ScheduleTask` 거절 |
| `HookRegistryReloader.bootstrap()` | 실패 시 WARN + `false` | 실패 시 예외 전파. 반환은 항상 `true` |
| `HookHotReloadBootstrap.Builder.start()` | 항상 `Started` | 로드/적용 실패 시 `HookConfigParseException`(또는 적용 단계의 `RuntimeException`) |
| `Started.isBootstrapSucceeded()` | 참/거짓 | 항상 참 |
| `HookConfigLoader.load()` | 파싱 실패만 던짐(경로 없음). 비정규 · I/O 오류는 WARN 후 부재 | 파싱 실패 · 비정규 · I/O 오류 모두 `HookConfigParseException`, 메시지에 경로 · 계층 |
| `HookConfigParseException` | — | Javadoc 만: "읽지 못한 경우도 포함" |

## 5. 파일·모듈별 변경

**aimon-core (main)**
- `subagent/execution/DefaultSubagentExecutor.java` — `fireOnStart` 를 block 검사로 바꾸고(이름은 메인에 맞춰
  `checkOnStartHooks`), `runReActLoop` 에 전용 catch, `createBlockedResult`(onStop 없이 종료 표지 스트림 + 실패 결과). 클래스 ·
  메서드 Javadoc.
- `agent/impl/orca/OrcaAgentExecutor.java` — `checkOnStartHooks` Javadoc 의 "Mirrors `DefaultSubagentExecutor#fireOnStart`" 만 고친다.
- `skill/hook/SkillHookSet.java` — `GUARD_EVENTS` 에 `ON_START`, 상수와 `guardEvents()` Javadoc.
- `tools/HookRegistryAccess.java` — `activeSkillGuards` · `view` Javadoc 의 "not `onStart` … EE-70" 삭제.
- `skill/hook/declarative/DeclarativeOnStartHook.java` — Javadoc: 메인은 턴을, 포크는 그 포크를 멈춘다.
- `config/hook/HookRegistryReloader.java` — `bootstrap()` 의 try/catch 제거, Javadoc.
- `config/hook/HookHotReloadBootstrap.java` — `bootstrapOk` 분기와 WARN 제거, `start()` · `Started` Javadoc(`@throws`).
- `config/hook/HookConfigLoader.java` — `loadOptional` 의 경로 부착과 읽기 실패 처리, 클래스 Javadoc 의 "Loading policy".
- `config/hook/HookConfigParseException.java` — Javadoc.

**aimon-cli (main)**
- `factory/AgentSetupFactory.java` — `decorate` 의 `setupHookHotReload` 호출을 감싸 `ConfigurationException` 으로 번역하고,
  `decorate` 가 던지면 스택을 닫는다(`create` 에서 `decorate` 를 try/catch — 빌드 실패 경로의 `closeSuppressing` 관용).

**aimon-session-routing (main)** — `LiveSessionOpener` Javadoc(`:74`)에 "`start()` 는 깨진 파일에서 던진다" 한 줄.

**aimon-bootstrap · aimon-spring-boot-starter (main)** — 변경 없음.

**문서**(번역본이 있으면 같은 커밋에서, `source_commit` 갱신)
- `docs/backlog/execution-environment-open-items.md` — EE-70 · EE-71 닫힘(본문에 결정 · 한 일 · 테스트), 머리 카운트
  (71건 열림 51 · 닫힘 20 → 닫힘 22), 머리말의 EE-70/71 문단, EE-63 본문의 "`onStart` 는 빼고" 구절, EE-51 닫힘 본문의
  "`onStart` 는 포크 안에서 막지 못한다" 문단에 뒤이은 정정 한 줄(이력은 지우지 않는다). `docs/backlog/README.md` 인덱스 행.
- 새 설계 노트 `docs/design/tool/execution-environment-ee70-ee71-fail-closed.md` — 직전 노트 형식(§1 문제 … §10 벗어난 점).
  직전 노트(`…-ee49-ee51-ee58-isolation-boundary.md`)의 머리 인용에 "EE-70 · EE-71 → 이 노트" 포인터만 더한다.
- `docs/features/hook/hook-config-guide.md` + `.en.md` — `:91`/`:97`(시작 시 파싱 실패 → 기동 실패, 메시지 예), 이벤트 표의
  `onStart` 행(메인: 턴 중단 / 포크: 그 포크가 실패로 끝남, `onStop` 없음), PR #207 이 넣은 "포크에서는 advisory" 문구들,
  문제 해결 표. **운영자 안내 한 단락**: `hooks.json` `onStart` 는 이제 모든 포크를 멈출 수 있고, 메인 턴만 겨누려면
  `AIMON_INVOKER_TYPE` 로 가른다.
- `docs/references/aimon-skill-extensions.md:154`, `.claude/rules/hook-development.md:109-110`,
  `docs/features/hook/hook-development-guide.md`(+`.en.md`), `docs/design/hook/hook-system.md` — 같은 정정.
- `CHANGELOG.md` `[Unreleased]` — 새 `### Changed (breaking)` 절을 맨 위에(§8). PR #207 절의 `:39`("not a guard event … EE-70") 와
  `:55`("EE-71") 문장은 같은 미발매 구간이므로 현재 사실로 고쳐 쓴다.

## 6. 실패 모드

| # | 상황 | 처리 |
|---|---|---|
| F1 | 포크 `onStart` 훅이 exit 2 | 포크 미시작, 부모에게 사유가 든 실패 결과. `onStop` 없음, `subagentStop(success=false)` |
| F2 | 포크 `onStart` 셸을 돌리지 못함(환경 사용 불가 · 제공자 실패 · timeout) | EE-51 의 block → F1 과 같다. `failOpen: true` 면 통과 |
| F3 | 여러 `onStart` 훅 중 하나만 block | 정책이 `neverStop` 이라 전부 돌고, 사유는 block 한 것만 모인다(`collectBlockedReasons`). 포크는 멈춘다 |
| F4 | 프로그램 훅이 던지거나 실행기 수준 timeout | 성공으로 읽힘(전제 11) — 메인과 같고 이 변경 밖(EE-64 계열) |
| F5 | 스킬 포크가 block → 스킬 범위 정리 | `SkillTool` 이 범위를 닫는 기존 경로 그대로(결과가 예외가 아니다) |
| F6 | 백그라운드 포크가 block | `FAILED` + 알림. 부모 턴은 계속된다 — 시작을 되돌릴 수는 없고 "그 포크가 일하지 않았다" 가 보장이다 |
| F7 | block 과 부모 취소가 겹침 | block 검사가 먼저면 block 결과, 취소가 훅 실행 중 인터럽트로 오면 기존 `catch (Exception)` + `isCancelledOrInterrupted` 가 interrupted 로 돌린다. 어느 쪽이든 포크는 진행하지 않는다 |
| F8 | 운영자의 기존 `hooks.json` `onStart` 가 사용자 입력을 검사하도록 쓰였는데 포크의 goal 에도 걸림 | **의도한 동작 변경.** CHANGELOG · 가이드에 `AIMON_INVOKER_TYPE` 분기 안내 |
| F9 | 시작 시 깨진 JSON / 모르는 `type` / 0 이하 timeout | `HookConfigParseException`(경로 · 계층 · line/column) → 기동 실패. 다른 계층이 멀쩡해도 뜨지 않는다 |
| F10 | 파일 없음 / `.aimon` 디렉터리 없음 | 정상 기동 |
| F11 | 있지만 읽지 못함 · 비정규 파일 | 기동 실패(§9 Q1). 리로드 중이면 이전 설정 유지 |
| F12 | 리로드 중 파싱 실패(편집 도중 저장 포함) | 그대로 — 이전 설정 유지, `OnConfigReload(successful=false)`, 사유에 이제 경로가 있다 |
| F13 | CLI 기동 실패 시 자원 | 스택 teardown 을 돌린 뒤 던진다(호스트 셸 · GraalJS 엔진 · 메모리 큐가 스택 소유) |
| F14 | 불리언 아닌 `failOpen`, 잘못된 핸들러, 모르는 이벤트 이름 | 그대로 — WARN(전제 10, §9 Q2). 파싱 실패가 아니다 |
| F15 | 감시자 시작 실패 | 그대로 — WARN 후 핫 리로드 없이 기동(가드는 이미 적용됨) |

## 7. 테스트 전략

각 새 테스트는 해당 프로덕션 수정을 잠시 되돌려 실패하는 것을 확인한다(수용 기준 3).

**EE-70**
- `DefaultSubagentExecutorTest`(또는 새 `DefaultSubagentExecutorOnStartBlockTest`): block 을 내는 `OnStartHook` → LLM 호출 0회,
  `isSuccess()==false`, `ERROR`, iteration 0, 메시지에 사유, **`onStop` 미발화**, 종료 표지 스트림. block 없는 advisory 는 전처럼
  reminder 로 붙는다. 훅 둘 중 하나만 block 이어도 멈춘다. 재개 요청도 막힌다.
- `hooks.json` 핸들러: `HookRegistryApplier` 로 `onStart` `command`(exit 2 를 내는 가짜 `ShellActionExecutor`)를 레지스트리에
  적용해 포크를 돌린다 — 스킬 frontmatter 경로(`SkillHookSetParser` → 스킬 뷰)와 **각각**.
- `IsolationBoundaryIntegrationTest`(`OrcaRuntimeItSupport`): (a) `onStart` 가드가 있는 FORK 스킬에서 포크의 환경 제공자가
  실패 → 포크가 돌지 않고 부모의 도구 결과에 원인 · 스킬 · 이벤트가 있다(기존 `skillShellGuardBlocksWhenTheForksEnvironmentProviderFails`
  의 `onStart` 판), (b) 같은 구성에 `failOpen: true` → 포크가 돈다, (c) 런타임 레지스트리의 `onStart` block 이 `Task` 포크를 멈춘다.
- `DefaultSubagentExecutionManager` 수준: block 된 포크에 `subagentStop(success=false, errorMessage=사유)`, 백그라운드는 `FAILED`.
- `SkillHookSetTest`(`:67` 의 단언을 뒤집는다 — `onStart` 만 있어도 `hasGuards()`), `guardEvents()` 가 넷.
  `WorkflowToolBackgroundModeTest` · `ScheduleTaskToolTest` · `aimon-workflow-graaljs` 의 대응 테스트: `onStart` 만 있는 스킬
  아래에서 거절.
- `DeclarativeShellHookBindingTest` 의 테스트 쪽 `GUARD_EVENTS` 는 이미 `onStart` 를 포함한다 — 그대로 통과해야 한다.

**EE-71**
- `HookConfigLoaderTest`: 세 계층 각각에 대해 메시지에 절대 경로 · 계층 이름 · 원인 구절(깨진 JSON 의 line/column,
  `Unknown hook handler type`, `must be a positive number`). 읽지 못하는 파일 · 디렉터리 → 던진다. 파일 없음 → 부재.
- `HookRegistryReloaderTest`: `bootstrap()` 이 던지고 레지스트리가 비어 있다. **리로드**: 정상 부트스트랩 뒤 파일을 깨면
  `false` + 이전 훅 유지 + `OnConfigReload(false)`(기존 테스트 유지, 사유에 경로 단언 추가). 읽지 못하게 된 파일도 같은 결과.
- `HookHotReloadBootstrapTest`: 깨진 파일 셋(JSON · type · timeout)으로 `start()` 가 던진다, 감시자 스레드가 남지 않는다,
  파일 없음은 `Started` + 감시자 활성. 한 계층만 깨져도 던진다(다른 계층의 훅이 등록되지 않았음을 함께 단언).
  `HookConfigHotReloadE2ETest` 는 그대로 통과.
- CLI: `AgentSetupFactoryHookConfigShellTest` 옆에 — 임시 작업 디렉터리의 깨진 `.aimon/hooks.json` 으로 `create(config)` 가
  `ConfigurationException`(메시지에 경로 · 원인)을 던지고 **스택이 닫혔다**(호스트 셸 closed 등 관찰 가능한 것 하나).
  `AimonCliTest` 가 기동 실패의 stderr/종료 코드를 볼 수 있는 구조면 한 건 더한다.
- Spring 스타터: 배선이 없으므로 스타터 자체의 동작은 없다. 가능한 범위의 테스트는 `ApplicationContextRunner` 에 테스트 전용
  `@Bean`(깨진 임시 파일로 `HookHotReloadBootstrap.start()`)을 얹어 컨텍스트가 실패하고 root cause 메시지에 경로가 있음을 보는
  것 — 호스트가 따를 패턴의 회귀 테스트로 넣는다(스타터 테스트 클래스패스가 허락하지 않으면 생략하고 PR 에 적는다).

**게이트**: `./gradlew checkAll`, `scripts/check-backlog-registers.py`, `check-translation-staleness.py`,
`check-translation-structure.py`, `check-doc-links.py`.

## 8. 공개 표면의 변경과 저장소 밖 영향

SPI 시그니처는 그대로지만 **동작 변경 둘이 breaking** 이다. `CHANGELOG.md` `[Unreleased]` 맨 위에:

1. **`onStart` block 이 포크를 멈춘다.** 운영자의 `hooks.json` `onStart` 가 exit 2 를 내거나 명령을 돌리지 못하면 이제 **모든**
   포크(`Task` · 스킬 · 워크플로 서브에이전트)가 시작하지 않는다. 스킬 frontmatter 의 `onStart` 는 다시 가드 이벤트이고,
   `onStart` 만 있는 스킬 아래의 백그라운드 워크플로 · `ScheduleTask` 는 거절된다. 포크를 빼려면 `AIMON_INVOKER_TYPE` 로 가르거나
   `failOpen: true`.
2. **깨진(또는 읽을 수 없는) `hooks.json` 으로는 뜨지 않는다.** `HookHotReloadBootstrap.start()` /
   `HookRegistryReloader.bootstrap()` 이 던진다. 옛 동작이 필요한 호스트는 그 호출을 명시적으로 감싼다.

외부 저장소(EE-1/EE-59 마이그레이션 메모에 한 줄씩): 커스텀 `SubagentExecutor` 는 영향 없음. `start()` 를 부르는 호스트
(aimon-ops 류)는 기동 실패를 받게 된다. `bootstrap()` 의 `false` 를 검사하던 코드는 죽은 분기가 된다.

## 9. 풀지 못한 것 — 열린 질문

각 항목에 이 설계가 **기본으로 택한 답**을 적었다. 메인테이너가 뒤집으면 해당 부분만 빠진다.

- **Q1. "있지만 읽을 수 없는 파일 · 비정규 파일" 도 기동 실패로 볼 것인가.** TASK 는 "파싱 실패" 만 말한다. 기본: **본다**(§3.2).
  뒤집으면 `loadOptional` 의 I/O 분기와 F11 · 해당 테스트만 빠지고, 그 fail-open 은 백로그에 남겨야 한다.
- **Q2. 적용 단계의 WARN-후-건너뛰기(잘못된 핸들러 · 모르는 이벤트 이름 `preTol` 등)를 어떻게 할 것인가.** 가드 하나가 조용히
  빠지는 같은 계열이지만 파싱 실패가 아니고, 미지원 이벤트(`HookEventName.isUnsupported`)처럼 일부러 허용하는 것과 섞여 있어
  경계를 정해야 한다. 기본: **이 변경에서 바꾸지 않고 새 백로그 항목(EE-72)으로 올린다** — 그러면 머리 카운트는
  72건(열림 50 · 닫힘 22)이 된다. 올리지 않기로 하면 71건(열림 49 · 닫힘 22).
- **Q3. 탈출구.** 기본: **설정 스위치 없음**, 호스트의 명시적 `try/catch` 만(§3.2). 컨테이너 이미지에 구운 `hooks.json` 처럼 파일을
  바로 고칠 수 없는 배포가 있다면 빌더 옵션을 나중에 더한다 — 그 요구가 지금 있는지는 TASK 에서 알 수 없다.
- **Q4. 코드 behavior 서브에이전트는 `onStart` 를 아예 발화하지 않는다**(전제 5). 그래서 `hooks.json` `onStart` 가드가 그
  포크에는 닿지 않는다. EE-70 의 문장("`DefaultSubagentExecutor` 를 거치는 모든 포크")의 범위 밖이라 기본: **건드리지 않고**
  닫힘 본문과 훅 가이드에 한계로 적는다. 별도 항목으로 올릴지는 Q2 와 같이 정할 일이다.
- **Q5. `bootstrap()` 의 `boolean` 과 `isBootstrapSucceeded()` 를 `@Deprecated` 로 표시할 것인가.** 기본: **표시하지 않는다**
  (릴리스 전 표면 변화 최소화). 다음 SPI 정리 때 볼 것.
- **Q6. block 된 포크의 `CompletionReason`.** 기본: `ERROR`(§3.1). 부모가 "훅이 막았다" 를 **프로그램으로** 구분해야 하는 독자가
  나오면 그때 값을 더한다 — 지금은 메시지로만 구분된다.

확인하지 못한 것: `ExecutionBlockedByHookException.buildMessage` 의 정확한 문구(테스트는 사유 포함으로 단언),
`ConfigurationException` 의 생성자 모양, `AimonStack` 의 닫기 메서드 이름, Jackson 이 감싼 `IllegalArgumentException` 메시지의
실제 형태(테스트를 쓰며 고정), `aimon-skill-extensions.md` · `hook-system.md` 의 번역본 유무, 외부 저장소가 `start()` /
`bootstrap()` 을 어떻게 부르는지(이 저장소에서 볼 수 없다).

## 10. 구현이 이 설계에서 벗어난 점

본문(§1~§9)은 승인본 그대로다. 구현하면서 다르게 한 것, 본문이 틀렸던 것, 리뷰 지적을 어떻게 했는지를 여기에 적는다.
접근(§3)은 그대로 구현했다 — 포크는 `onStart` block 에서 전용 실패 결과로 끝나고 `onStop` 을 발화하지 않으며, 시작 시 로드
실패는 예외로 전파되고, 타입 · 시그니처 · 와이어 형식은 바뀌지 않았다.

### 10.1 본문과 다르게 한 것

1. **F13 의 전제가 틀렸다 — 메모리 큐는 실패 시점에 스택 소유가 아니다(설계 리뷰).** `setupHookHotReload` 가 던지는 자리는
   `enrollMemorySubsystem` 이 `memoryQueue::stop` 을 스택에 등록하기 **전**이다. 스택만 닫으면 이미 시작된 파생 워커 풀이
   남는다. `create` 의 새 catch 는 스택을 닫고 **큐를 따로 멈춘다**(빌드 실패 경로가 같은 이유로 하는 것과 같은 줄).
   `stop()` 은 멱등이라 `decorate` 가 더 뒤에서 던져 스택이 먼저 멈췄어도 문제가 없다. `graalJsEngines` 와 `hookConfigShell`
   은 그 시점에 등록되어 있어 스택이 닫는다. 테스트는 "스택이 닫혔다" 하나가 아니라 **큐의 `stop()` 호출**까지 단언한다 —
   앞의 것만으로는 이 누수를 알아채지 못한다.
2. **"포크를 빼려면 `AIMON_INVOKER_TYPE` 로 가르거나 `failOpen: true`"(§8, F8)는 과장이다(설계 리뷰).** `failOpen` 은 명령을
   **돌리지 못했을 때만** 통과시킨다. exit 2 를 내는 훅은 `failOpen` 이어도 모든 포크를 막는다. CHANGELOG 와 훅 가이드에는
   `AIMON_INVOKER_TYPE` 분기만 포크를 빼는 방법으로 적었고, `failOpen` 이 그 스위치가 아니라는 것을 명시했다. 리뷰가 물은
   "`http` · `mcp` 의 `onStart` 핸들러는 무엇으로 가르나" 는 해당이 없다 — `hooks.json` 의 `onStart` 는 `command` 핸들러만
   받는다(`HookRegistryApplier` 가 나머지를 WARN 후 건너뛴다).
3. **Spring 의 "root cause 에 위 메시지" 는 사실이 아니다(§3.2 경로 표, §7).** 깨진 JSON 의 root cause 는 Jackson 의
   `JsonParseException` 이다 — 경로를 실은 `HookConfigParseException` 은 원인 사슬의 **중간**에 있다(로더가 원인을 보존해 다시
   던지므로). 스타터 테스트는 root cause 가 아니라 사슬에서 그 예외를 찾아 단언한다.
4. **"없음" 과 "알 수 없음" 을 가른다(§3.2 · Q1, 설계 리뷰).** 본문대로 `Files.exists` 로 시작하면 `.aimon` 디렉터리가 검색
   불가일 때 거짓이 나와 그 계층이 "없음" 으로 읽힌다 — `chmod 000 .aimon` 이 fail-open 으로 남는다. 판정은
   `Files.readAttributes` 의 예외로 한다(§10.6 의 3 — 처음 구현의 `notExists`/`exists` 쌍을 PR 리뷰 뒤 바꿨다):
   `NoSuchFileException` 만 부재이고, 그 밖의 `IOException` 은 읽기 실패로 던진다. 대상 없는 심볼릭 링크는 전처럼 부재다.
5. **CLI 메시지 끝에 한 구절이 붙는다.** `… - fix or remove the file and start again`. 탈출구가 "파일을 고치거나 치운다"
   하나이므로(§3.2) 오류가 그것을 말하게 했다. 번역은 `decorate` 안이 아니라 `setupHookHotReload` 를 감싼
   `startHookHotReload` 에서 한다.
6. **`createBlockedResult` 는 iteration 수와 토큰을 인자로 받는다.** 그 시점에 둘 다 0 이라 결과는 본문의 "iteration 0,
   토큰 0" 과 같다. 같은 메서드의 다른 결과 생성기와 모양을 맞춘 것이다.

### 10.2 본문이 적지 않았지만 따라온 것

- **스킬 frontmatter 의 `onStart` 에 쓴 `failOpen` 이 더 이상 WARN 을 내지 않는다(설계 리뷰).** `SkillHookSetParser.parseFailOpen`
  은 가드 이벤트가 아니면 "the event cannot block" 을 찍는데, `ON_START` 가 가드 이벤트로 돌아오며 그 WARN 이 사라졌다.
  의도한 결과이고(수용 기준이 `failOpen: true` 의 통과를 요구한다) §4 의 표에 없던 동작 변경이다. Javadoc 은 고칠 것이
  없었다 — "막을 수 없는 이벤트에서는 WARN" 이라는 문장이 여전히 참이다.
- **`aimon-core-integration-via-cli-reference.md`(+번역본)** 의 조립 예제에 "깨진 파일이면 여기서 던진다" 한 줄을 더했다.
  §5 의 문서 목록에 없던 파일이다.
- **백로그 EE-64 · EE-1 · EE-59 에 덧붙임.** 전제 11(프로그램 `onStart` 훅이 던지면 성공)은 EE-64 와 같은 계열이라 그 항목에
  적었고, 외부 저장소 영향(§8)은 EE-1 과 EE-59 에 더했다.

### 10.3 테스트가 본문(§7)과 다른 곳

- **매니저 수준 테스트는 런타임 통합 테스트로 했다.** `subagentStop(success=false, 사유)` 와 백그라운드 `FAILED` 는
  `DefaultSubagentExecutionManager` 단위 테스트가 아니라 `IsolationBoundaryIntegrationTest` 에서 조립된 런타임으로 본다 —
  `Task` 도구에서 부모 모델의 관찰까지 한 번에 지나간다.
- **`AimonCli` 수준(stderr · 종료 코드) 테스트는 없다.** `call()` 이 실제 설정 파일을 읽어 이음매가 없다. `create()` 가
  `ConfigurationException` 을 던지는 것까지 테스트하고, 그 예외를 `AimonCli` 가 `Configuration error: …` + 종료 코드 1 로
  바꾸는 것은 기존 코드다(리뷰가 소스로 확인).
- **스킬 frontmatter 경로의 단위 테스트는 스킬 뷰를 조립하지 않는다.** `SkillHookSetParser` 가 만든 훅을 레지스트리에 직접
  등록한다. 뷰를 거치는 경로는 통합 테스트(환경 제공자가 실패한 포크의 스킬 `onStart` 가드)가 본다.
- **막힌 재개 뒤의 재개(설계 리뷰).** 막힌 결과의 스냅샷으로 다시 재개하면 대화가 이어지고 같은 실행 id 를 쓴다는 테스트를
  더했다. PR 리뷰 뒤 그 테스트는 거부된 goal 이 다음 재개의 메시지에 **없다**는 것까지 단언한다(§10.6 의 1).
- **검색 불가 디렉터리 · 읽기 권한 없는 파일 테스트는 권한을 바꿀 수 없는 환경에서는 건너뛴다**(`assumeTrue`). 이 저장소의
  개발 · CI 환경에서는 실행된다.

각 새 테스트는 프로덕션 수정을 되돌려 실패하는 것을 확인했다(수용 기준 3) — 통제군(advisory 피드백, `failOpen` 통과, 파일
없음)은 양쪽에서 통과한다.

### 10.4 열린 질문은 어떻게 되었나

| 질문 | 결과 |
|---|---|
| Q1 — 읽을 수 없는 파일 · 비정규 파일 | 기본대로 **기동 실패**. §10.1 의 4 로 한 걸음 더 갔다(검색 불가 디렉터리). 메인테이너가 뒤집으면 `loadOptional` 의 세 분기와 해당 테스트가 빠진다 |
| Q2 — 적용 단계의 WARN-후-건너뛰기 | 바꾸지 않았다 → **EE-72** |
| Q3 — 탈출구 | 설정 스위치 없음 → **EE-74**(트리거 대기) |
| Q4 — 코드 behavior 서브에이전트 | 건드리지 않았다. 훅 가이드에 한계로 적고 → **EE-73** |
| Q5 — `bootstrap()` 의 `boolean` · `isBootstrapSucceeded()` | `@Deprecated` 없이 남겼다 → **EE-75** |
| Q6 — 막힌 포크의 `CompletionReason` | `ERROR` → **EE-75** |

백로그 머리 카운트는 본문 §5 · Q2 가 적은 72건(열림 50)이 아니라 **75건(열림 53 · 닫힘 22)** 이다 — Q2 하나가 아니라 넷을
올렸다.

"확인하지 못한 것"(§9 끝)은 구현하며 확인했다: `ExecutionBlockedByHookException` 의 문구는
`Execution blocked by OnStart hook [SUBAGENT/<이름>]: <사유; …>`, `ConfigurationException(String, Throwable)` 이 있고,
`AimonStack` 은 `AutoCloseable`(`close()`), Jackson 이 감싼 `IllegalArgumentException` 의 메시지는 원문 구절
(`Unknown hook handler type`, `must be a positive number`)을 그대로 담는다. `aimon-skill-extensions.md` 와 `hook-system.md` 에는
번역본이 없다. **외부 저장소가 `start()` / `bootstrap()` 을 어떻게 부르는지는 여전히 확인하지 못했다** — 이 저장소에서 볼
수 없다(EE-1 의 덧붙임).

### 10.5 사람이 볼 것

- **`aimon-bootstrap` 과 `aimon-spring-boot-starter` 에는 코드 변경이 없다.** 과제는 세 경로의 동작을 물었지만 둘은
  `hooks.json` 을 배선하지 않는다(전제 6). "호스트가 부른 `start()` 가 던진다" 가 그 두 경로의 답 전부다.
- **Q1 은 과제의 문장("파싱 실패")보다 넓다.** 읽을 수 없는 파일과 검색할 수 없는 디렉터리까지 기동 실패다. 과제가 요구한
  것이 아니라 이 설계의 결정이므로 뒤집을 수 있다.
- **운영자의 기존 `hooks.json` `onStart` 가 포크를 막기 시작한다**(F8). 의도한 동작 변경이고 CHANGELOG 맨 위에 있다.

### 10.6 PR 리뷰(#208) 뒤 바꾼 것

결정 원칙은 그대로다 — 판단할 수 없는 가드는 막는다. 다만 판단할 수 있고 해롭지 않은 상황을 기동 실패로 만들지는 않는다.

1. **막힌 포크는 거부된 goal 을 저장하지 않는다.** 처음 구현의 `createBlockedResult` 는 `addUserMessage(goal)` **뒤**의
   스냅샷을 돌려줬고, 백그라운드 포크는 비어 있지 않은 스냅샷을 `Task(resume=…)` 용으로 저장한다. 그래서 막힌 재개의 goal 이
   대화 기록에 남아 다음 재개 때 모델에 그대로 재생됐다. 이제 결과는 goal 을 더하기 **전**의 스냅샷을 싣는다: 재개라면 복원한
   대화 그대로, 새 포크라면 빈 기록이라 저장되지 않고 그 task id 는 재개할 수 없다("iteration 이 돌지 않은 포크" 의 기존 규칙).
   메인 에이전트가 막힌 사용자 메시지를 어떻게 남기는지는 별도 결정이라 건드리지 않았다.
2. **빈 `hooks.json` 은 기동 실패가 아니다.** `parse(String)` 은 공백 · `null` 을 빈 문서로 보지만 `parseFile` 은 스트림을
   Jackson 에 바로 넘겨 0 바이트 파일에서 던졌다 — EE-71 뒤로는 기동 실패다. `parseFile` 은 이제 파일을 UTF-8 문자열로 읽어
   `parse(String)` 으로 보낸다. 0 바이트 · 공백만 · `null` 파일은 hook 없는 계층이다. 덤으로 UTF-8 이 아닌 바이트는 "읽기
   실패" 가 아니라 "invalid" 로 보고되고, 파서 메시지는 원문 인용 없이 줄 · 열만 붙인다(`(line: 2, column: 4)`).
3. **존재 판정의 원인을 남기고, "디렉터리가 아님" 은 부재로 본다.** `notExists`/`exists` 쌍은 원인을 버렸고, 경로 중간이
   일반 파일인 경우(`~/.aimon` 이 파일 — 그 자리에 설정 파일이 있을 수 없다)까지 기동 실패로 만들었다. 이제
   `Files.readAttributes` 를 부르고: `NoSuchFileException` → 부재(DEBUG), 그 밖의 `IOException` 에서 경로의 조상 가운데
   일반 파일이 있으면 → 부재 + 그 파일을 가리키는 WARN, 아니면(검색 불가 홈의 `AccessDeniedException` 등) → 기동 실패이고
   메시지에 원래 예외의 클래스와 메시지가 실린다. "디렉터리가 아님" 을 알리는 예외는 OS 마다 다르고(유닉스는
   이유 문자열이 "Not a directory" 인 `FileSystemException`) 이유 문자열은 지역화될 수 있어서, 예외 대신 조상 검사로 가른다.
4. **자잘한 것.** `AgentSetupFactory.create` 의 `decorate` catch 가 `Error` 도 잡아 스택을 닫고 다시 던진다.
   `LiveSessionOpener` 에 "영속 예약 작업 저장소를 쓰는 호스트는 스케줄링 시작 전에 `hooks.json` 을 올린다" 한 문장을
   더했다 — `AimonStack.start()` 는 런타임 직후 스케줄링을 시작하고, 저장된 루틴은 그때부터 발화할 수 있다. 백로그 EE-72 에
   `HookRegistryApplier` 의 셸 미지원 `command` 건너뛰기를 같은 계열로 더했다.

손대지 않은 것: EE-75(늘 참인 `bootstrapOk`), EE-64(코드 훅 예외 매핑), 워크플로 단계의 막힌 포크 테스트, `AimonCli` stderr
테스트.

### 10.7 그 뒤 닫힌 열린 질문 (2026-10-05)

본문은 그대로 두고, 그 뒤 달라진 사실만 적는다.

- **Q4 (EE-73) — 코드 behavior 서브에이전트도 `onStart` 를 발화한다.** 전제 5 의 "발화하는 곳은 둘뿐" 은 이제 셋이다:
  `SubagentBehaviorRunner` 가 behavior 를 부르기 전에 `SubagentOnStartGate` 로 발화한다. 그 클래스는 발화 · block 판정 ·
  막힌 결과를 `DefaultSubagentExecutor` 와 함께 쓴다. block 이면 behavior 는 실행되지 않고 같은 실패 결과로 끝나며 `onStop`
  은 없다. ReAct 포크와 다른 점은 둘이다 — behavior 포크는 자기 환경을 풀지 않으므로 훅은 **스폰한 실행의 환경**을 받고,
  block 이 아닌 피드백은 붙일 대화가 없어 버린다. 항목이 "읽었고 돌려 보지는 않았다" 고 적은 것은 돌려서 확인했다 — 고치기
  전에는 block 을 낸 훅 아래에서 behavior 가 실행됐다.
- **Q5 (EE-75) — 늘 참인 두 표면은 `@Deprecated` 다.** `HookRegistryReloader.bootstrap()` 은 `void loadInitial()` 로 대체되고
  (옛 메서드는 그것을 부르고 `true` 를 돌려준다), `Started.isBootstrapSucceeded()` 는 상수 `true` 다. 트리 안에서 그 값으로
  갈라지던 곳은 `HookHotReloadBootstrap.start()` 의 `bootstrapOk` 전달 하나였고 없앴다. 메서드 삭제는 하지 않았다.
- **Q6 (EE-75) — 막힌 포크의 완료 사유는 `CompletionReason.BLOCKED` 다.** §3.1 의 표와 §4 의 `CompletionReason.ERROR` 는 이제
  `BLOCKED` 로 읽는다. 기각 사유였던 "그 값으로 분기할 독자가 없다" 는 그대로지만 깨지는 묶음 안에서 더했다. 위험 둘은
  이렇게 확인했다. 옛 노드: 포크의 사유를 노드 사이로 나르는 코덱은 `JsonTaskResultCodec` 하나이고 모르는 이름을 실패
  결과에서는 `ERROR` 로 읽는다 — 옛 노드는 막힌 포크를 전과 같이 본다. `StepOutcomeCodec` 은 `COMPLETED` 단계만 저장하므로
  이 값을 만나지 않는다. 트리 안의 독자: `completionReasonLine`(`Task` · `AgentOutput` · 완료 알림)과 CLI 의
  `SubagentResultDisplayHook` 은 값 전체를 훑으므로 코드 변경 없이 `Completion reason: BLOCKED` 를 찍고,
  `AgentStepResult.isComplete()` 는 거짓이다. 턴의 완료 사유를 나르는 세션 코덱들은 이 값을 만나지 않는다(막힌 턴은 결과가
  아니라 예외다).
