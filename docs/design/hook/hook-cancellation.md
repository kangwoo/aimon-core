# 훅과 인터럽트 (Hook Cancellation)

> **Status**: IMPLEMENTED — `aimon-core` 의 `at.aimon.core.hook.event` · `hook.execution` · `skill.hook.declarative` · `agent.impl.orca` · `subagent` · `agent.compact` · `agent.context`. 열린 항목은 [등록부](../../backlog/execution-environment-open-items.md)의 EE-93, EE-96, EE-99, EE-100, EE-101 이다.

인터럽트가 훅에 닿았을 때 훅의 명령과 실행이 어떻게 되는가의 정본이다. 가드 이벤트와 보고 이벤트의 분류, 그리고 멈춘 가드가 내는 판정(`Unrun.CANCELLED`)은 [fail-closed](hook-fail-closed.md), 신호와 코디네이터, 세션이 인터럽트를 받는 일, 스레드 플래그 위생은 [중단](../agent-execution/interrupt.md), 요약 호출의 취소는 [컴팩션](../agent-execution/compaction.md)이 소유한다. 사용자 쪽 설명은 [hook-config-guide](../../features/hook/hook-config-guide.md) 에 있다.

## 1. 규칙

**가드 명령은 인터럽트에 언제나 멈추고, 보고 명령은 결코 멈추지 않는다.**

갈리는 기준은 훅이 무엇을 묻는가이고, 설정이 아니라 이벤트가 정한다. 설정 키는 없다. 이 규칙은 선언적 훅의 셸 명령에 걸린다. 신호를 읽는 것은 셸 실행기뿐이고 `http` · `mcp` 액션은 신호에 묶이지 않는다. 코드로 쓴 훅은 [§2.1](#21-신호의-길)의 답을 받아 스스로 정한다.

- **가드 명령**(`onStart`, `preCompact`, `preTool`, `permissionRequest`). 일이 진행되기 전에 묻는다. 인터럽트 뒤에 발화한 명령은 시작하지 않고, 돌던 명령은 멈추고, 훅은 막는다. 판정이 없으면 진행 허가도 없다. 이 경로는 실행이 어차피 끝나는 중일 때만 닿으므로 멀쩡한 실행을 막지 않는다.
- **보고 명령**(`onStop`, `postCompact`, `subagentStart`, `subagentStop`, `postTool`, `permissionDenied`). 이미 일어난 일을 알린다. 인터럽트가 명령보다 먼저 왔든 도는 중에 왔든, 명령은 끝나거나 자기 timeout 이 끝낼 때까지 돈다.

보고 명령을 멈추지 않는 이유는 둘이다.

- **정리가 끝나는지가 경합에 달리면 안 된다.** 신호가 서기 전까지만 묶으면 같은 `onStop` 명령이 인터럽트보다 조금 먼저 시작했을 때는 반쯤 돌다 끊기고 조금 늦게 시작했을 때는 끝까지 돈다.
- **두 실패의 모양이 다르다.** 반쯤 된 정리는 아무 데도 보고되지 않는다. 끝까지 도는 명령이 치르는 것은 timeout 으로 묶이고 기다리는 쪽에 보이는 지연이다. 값은 [§5](#5-치르는-값)에 있다.

## 2. 두 길

인터럽트가 명령에 닿는 길은 둘이고, 보고 명령은 둘 다 막는다. 신호만 떼면 규칙은 그것이 가장 필요한 훅, 로컬 셸의 `subagentStop` 과 포크의 `onStop` 에서 듣지 않는다.

| 길 | 누가 보내는가 | 명령에 닿는 법 |
|---|---|---|
| 신호 | `LiveSession.interrupt`, 선점하는 입력, 백그라운드 포크의 `Task.stop`, 워크플로 정지 | `ShellActionRunner` 가 신호에 건 리스너가 셸의 취소 토큰을 세운다 |
| 스레드 | `Task.stop`(`RunningTaskHandle.requestStop`), 워크플로 정지(`RunControl.requestStop`), `DefaultSessionRouter.close()` · `DefaultSubagentExecutionManager.close()` 의 `shutdownNow()` | `DefaultHookExecutor.awaitHook` 이 `cancel(true)` 로 훅 스레드를 인터럽트하고 그것에 반응하는 셸(`LocalShell`)이 멈춘다 |

`LiveSession.interrupt` 는 턴의 스레드를 인터럽트하지 않는다. 백그라운드 포크와 워크플로의 정지는 두 길을 함께 쓰고 신호를 먼저 세운다.

가드 명령은 두 길 어느 쪽으로도 멈춘다. 신호의 길은 포그라운드 `Bash` 와 같은 길이라 스레드 인터럽트에 반응하지 않는 셸에서도 듣는다. `LocalShell` 이 스레드 인터럽트로 `InterruptedException` 을 내며 끝낸 것도 같은 원인(`Unrun.CANCELLED`)으로 읽으므로 두 종류의 셸에서 결과가 같다.

### 2.1 신호의 길

컨텍스트의 getter `HookContext.getExecutionCancellation()` 은 **코드로 쓴 훅**을 위한 답이다.

| 컨텍스트 | getter 의 답 | 이유 |
|---|---|---|
| 가드 이벤트 | 신호를 언제나 돌려준다 | 멈춰야 한다 |
| 보고 이벤트 | 서기 전까지만 돌려준다(`CancellationSignals.liveOrEmpty`) | 훅이 자기 일을 묶을지 정하되, 이미 취소된 실행에서 시작을 거절당하면 안 된다 |

답을 getter 에 두므로 체인의 훅마다 그 순간의 값을 읽는다. 발화 지점은 쥐고 있는 신호를 넘길 뿐 아무것도 정하지 않는다. 한 컨텍스트 객체의 답이 수명 중에 있음에서 없음으로 바뀔 수 있다.

선언적 보고 훅은 이 답을 쓰지 않는다. 셸 실행기에게 신호 없는 뷰(`SignalDetachedHookContext`)를 넘기므로 러너는 토큰 없는 옵션을 만들고 리스너를 걸지 않는다. 실행기는 이벤트를 몰라도 "신호가 있으면 묶는다" 만으로 규칙을 따른다.

### 2.2 스레드의 길

`awaitHook` 은 기다리다 인터럽트를 받으면 태스크를 취소하고 BLOCKED 로 답한다. 근거는 [중단](../agent-execution/interrupt.md)의 §8.7 이다. 예외는 기다려 달라고 선언한 훅(`ExecutionHook#ignoresInterrupt()`)이다. 태스크를 취소하지 않고 남은 예산만큼 마저 기다려 훅 자신의 결과나 평소의 timeout 처리를 돌려주고, 스레드 플래그를 다시 세워 호출자의 취소가 사라지지 않게 한다.

선언적 셸 훅은 이벤트로 답한다. `SkillHookSet.reportEvents()` 의 여섯에서 `true`, 그 밖에서 `false` 이고, `postTool` 은 셸 액션일 때만 `true` 다.

### 2.3 스레드로만 온 취소

스레드 인터럽트가 신호 없이 오면 그것에 반응하지 않는 셸의 가드 명령은 자기 timeout 까지 돈다. 고치지 않는다. 트리 안에서 실행의 스레드를 인터럽트하는 코드는 신호를 먼저 세우므로 이 경우는 임베더에게서만 오고, 노출은 timeout 으로 묶이며, 훅 실행기는 읽기 전용 신호만 쥐어 세울 수 없다. 코디네이터를 가진 발화 지점에서 올리면 `onStart` · `onStop` 만 덮고 도구 범위 이벤트와 압축은 덮지 못한다. [EE-96](../../backlog/execution-environment-open-items.md)이 추적한다.

## 3. 어느 신호인가

**이벤트마다 신호는 하나이고, 그 훅이 누구의 일을 하는가로 고른다.**

| 이벤트 | 신호 |
|---|---|
| `onStart`, 메인 턴(슬래시 명령 턴 포함) | 턴 자신의 것 |
| `onStart`, ReAct 포크 | 포크 자신의 것 |
| `onStop`, ReAct 턴 · ReAct 포크 | 그 실행의 것 |
| `preTool` · `postTool` · `permissionRequest` · `permissionDenied` | 도구를 호출한 실행의 것 |
| `preCompact` · `postCompact`, AUTO | 압축하는 실행의 것 |
| `subagentStart` · `subagentStop` | 스폰한 실행에서 발화하지만 **포크를 다스리는** 것. 포그라운드는 스폰한 쪽의 것, 백그라운드는 그것에서 번지고 `Task.stop` 이 세우는 태스크의 것 |

압축의 신호는 환경과 같은 [요청 체인](hook-environment.md#15-압축-경로)을 따라 흐른다.

**비어 있는 것은 배관이 빠진 것이 아니라 설 수 있는 신호가 없다는 답이다.**

| 비어 있는 자리 | 이유 |
|---|---|
| 슬래시 명령 턴의 `onStop`, `/compact` 의 `onStop` · `preCompact` · `postCompact` | 명령은 인터럽트할 수 없다([EE-93](../../backlog/execution-environment-open-items.md)) |
| rewake 가 재발화한 `preCompact` | 발화한 실행 밖에서 다시 만든 컨텍스트다 |
| `onSessionStart` · `onSessionEnd` · `onConfigReload` | 어느 실행에도 속하지 않는다 |

스폰한 쪽이 이미 취소되었어도 포크는 시작되어 자기 첫 체크포인트에서 끝나므로, `subagentStart` · `subagentStop` 은 짝을 유지한다. 풀이 거절한 태스크의 `subagentStop` 은 태스크 코디네이터를 닫고 스폰한 쪽 신호와의 연결을 끊기 **전에** 발화한다. 코드로 쓴 훅이 읽는 신호가 아직 설 수 있어야 하기 때문이다.

## 4. 실행 결과

**가드 중에 신호가 서면 실행은 block 이 아니라 `INTERRUPTED` 로 끝난다.** 사용자가 멈춘 것이지 가드가 거절한 것이 아니고, 결과가 그때 돌던 훅의 종류에 달리면 안 된다. 인터럽트된 가드 자신의 답은 여전히 BLOCKED 이고, 실행이 그 답보다 신호를 먼저 읽는다.

### 4.1 메인 턴

턴의 코디네이터는 `onStart` 체인 직전에 만들어져 관찰자에게 공개된다(`runTurn`). 그 앞에서는 끝낼 대상인 `ExecutionScope`, 전사, 이벤트 디스패처가 아직 없다. 그 구간에 온 인터럽트는 세션이 쥐고 있다가 넘긴다([중단](../agent-execution/interrupt.md)의 §6.1).

`checkOnStartHooks` 는 신호를 두 번 읽는다.

- **체인을 내보내기 전.** 이미 섰으면 `onStart` 훅을 하나도 돌리지 않는다. 첫 셸 가드에서야 멈추면 그 앞의 코드 훅과 `http` · `mcp` 훅이 이미 멈춘 턴을 위해 각자의 timeout 까지 돈다.
- **체인이 돌아온 뒤.** 섰으면 block 여부를 보기 전에 끝낸다. 함께 온 block 사유는 debug 로그에만 남는다.

어느 쪽이든 턴은 `CompletionReason.INTERRUPTED` 로 끝나고 `onStop(success=false)` 가 발화하며 LLM 호출은 없다. 인터럽트된 턴은 재시도 표시를 유지하고 막힌 턴은 유지하지 않는다. 슬래시 명령 턴도 같은 길로 끝나고 명령은 돌지 않는다. 그러지 않으면 가드의 답 없이 명령이 돈다.

**스레드로만 온 인터럽트는 다르다.** 턴은 신호만 읽으므로, 플래그가 선 스레드로 들어온 턴의 가드는 실행기에서 BLOCKED 가 되고 턴은 `ExecutionBlockedByHookException` 으로 끝난다([§2.3](#23-스레드로만-온-취소)). 플래그는 호출자에게 되돌아가므로 취소가 사라지지는 않는다.

코디네이터는 턴을 영속하기 **전에** 닫는다. 열어 두면 영속과 메모리 피드 중의 인터럽트가 신호를 세워 백그라운드 포크로 번진다. 슬래시 명령 턴은 `onStart` 뒤에 바로 닫는다. 명령 흐름은 턴의 코디네이터를 읽지 않고, `executeCommand` 는 자기 것을 따로 만들며, `onStop` 에는 명시적으로 신호를 주지 않는다.

### 4.2 포크

`startFork` 는 `onStart` 체인이 돌아오거나 block 을 던진 뒤 그 둘을 보기 전에 루프의 체크포인트 술어(`isCancelledOrInterrupted`)를 한 번 평가한다. 참이면 `INTERRUPTED`, 메시지 "Execution interrupted", `onStop(success=false)` 로 끝나고, 결과는 **goal 을 더하기 전의** 스냅숏을 싣는다.

- **스냅숏.** 포크의 goal 은 부모 모델이 쓴다. 가드의 답 없이 끊긴 goal 이 저장되면 재개 때 이력으로 모델에게 돌아가는데, 재개의 `onStart` 는 새 goal 만 본다. 막힌 포크와 같은 스냅숏을 쓰는 이유다([fail-closed](hook-fail-closed.md#6-포크-onstart-block)).
- **술어.** 맨 신호 읽기가 아니라 체크포인트 술어를 쓰는 이유는 `awaitHook` 이 다시 세운 스레드 플래그를 소비해야 하기 때문이다. 남겨 두면 `onStop` 훅의 대기가 바로 던진다. 그래서 포크는 신호로 왔든 스레드로 왔든 `INTERRUPTED` 다.

코드 behavior 포크는 신호를 받지 않고 루프가 없어 여전히 `BLOCKED` 로 끝난다. [EE-99](../../backlog/execution-environment-open-items.md)가 추적한다.

### 4.3 압축

취소된 AUTO `preCompact` 는 막고, 압축은 건너뛰어지고, 뷰가 차단 한도를 넘었으면 엔진이 `BLOCK` 으로 답한다. 인터럽트가 부른 창 오류는 인터럽트이므로 두 실행기는 `BLOCK` 분기에서 신호를 먼저 읽는다.

| 실행기 | 읽는 법 | 끝 |
|---|---|---|
| 메인 턴 | `cancellationSignal.isCancelled()`. 이 block 을 인터럽트가 불렀는지만 묻고 스레드 플래그는 그것을 소유한 경로에 남긴다 | `handleInterrupted` |
| 포크 | 신호의 `checkpoint()` 가 `CancelledExecutionException` 을 던진다. `applyCompactionGate` 의 반환 모양을 바꾸지 않으려고 예외로 풀린다 | 루프의 catch 가 인터럽트 결과로 만든다 |

MANUAL `preCompact` 의 block 은 경고로 강등되지만 MANUAL 에는 신호가 없어 취소된 가드가 여기에 닿지 않는다. 인터럽트된 턴의 결과가 실패한 압축 시도를 보고하는 일은 [EE-100](../../backlog/execution-environment-open-items.md)이 추적한다.

## 5. 치르는 값

**기다림.** 중단된 실행과 그 뒤에 선 입력이 보고 명령을 기다린다. 두 길을 다 막았으므로 어느 셸에서든 그렇다. 기다림은 **더해진다.** 체인은 훅을 차례로 돌리고, 인터럽트가 왔을 때 시작하지 않은 보고 명령도 시작해서 끝까지 돌며, 실행이 풀리는 동안 이어서 발화하는 체인(`postTool` → `onStop` → `subagentStop`)이 각자의 것을 더한다. 훅 하나의 상한은 바깥 그물인 `min(timeout, 10분) + 5초` 다. 그래서 가이드는 보고 훅의 timeout 을 짧게 잡으라고 적는다.

**정지와 종료.** 정지한 태스크와 워크플로는 보고 명령이 끝난 뒤에 정리된다. 종료도 도는 보고 명령을 기다린다. 라우터가 닫히는 동안 턴의 `onStop` · `postTool` 명령이 돌면 턴의 스레드는 라우터의 짧은 `awaitTermination` 과 세션 캐시 · 이벤트 퍼블리셔의 닫힘을 지나서도 기다린다. 명령이 끝나거나, timeout 이 되거나, `TeardownPhase.HOOK_EXECUTOR` 가 닿을 때까지다. 그 teardown 이 명령을 끝내는 것도 스택이 풀을 소유하고(주입된 풀은 닫지 않는다) 셸이 스레드 인터럽트에 반응할 때뿐이다. 원격 셸의 명령은 토큰이 없으므로 timeout 까지 돈다.

**외부 실행기.** 공개 SPI `ShellActionExecutor.run` 의 시그니처를 유지한 대가로, `context` 인자를 이벤트 컨텍스트 타입으로 다운캐스트하는 외부 실행기는 모든 보고 이벤트에서 실패한다. 보고 이벤트에서 받는 것은 `HookContext` 뷰다.

반대 방향 옵션("인터럽트에 멈춰라")은 요청이 올 때까지 두지 않는다. [EE-101](../../backlog/execution-environment-open-items.md)이 추적한다.

## 6. 공개 표면

| 표면 | 계약 |
|---|---|
| `ExecutionHook#ignoresInterrupt()` | 기본값 `false`. 실행기는 이벤트를 모르므로 코드로 쓴 훅의 선언은 어느 이벤트에서든 듣는다. 가드에서 선언하면 중단된 실행이 그 가드를 예산만큼 기다리고, 얻는 것은 실제 판정이나 timeout 이지 "돌지 않고 성공" 이 아니다 |
| `ShellActionExecutor.run` | 신호를 읽으려면 `context.getExecutionCancellation()` 을 쓴다. 다운캐스트하지 않는다 |
| `OrcaAgentExecutionRequest#getInterruptObserver()` | `onStart` 전에 턴당 한 번 불린다. 슬래시 명령 턴도 부른다 |
| `interruptCoordinatorFactory` | 슬래시 명령 턴에서는 두 번 불린다. 턴의 것과 명령의 도구에 줄 것이다. 인스턴스 하나를 돌려주는 공급자는 명령에 이미 닫힌 코디네이터를 준다 |
| 네 요청 타입의 `executionCancellation` | `ContextRequest`, `CompactionGuardRequest`, `CompactionRequest`, `SummaryRequest`. 자기 하위 요청을 만드는 커스텀 `ContextEngine` · `CompactionEngine` 이 전달하지 않으면 그 압축 훅은 묶이지 않는다. 조용하다 |

## 7. 실패 모드

| 상황 | 처리 |
|---|---|
| 긴 신호에 남은 리스너 | 러너가 `finally` 에서 등록을 지운다. 등록은 명령 하나의 길이만 산다 |
| `NoopCancellationSignal`(에이전트 단위 러너가 스폰한 포크) | 있되 서지 않는다. `onCancel` 은 아무것도 등록하지 않는다. 특별히 다루지 않는다 |
| `ShellFeature.CANCELLATION` 이 없는 셸 | 토큰을 무시한다. 신호로 멈추지 않는다 |
| `onStart` 중에 인터럽트가 오고 다른 훅이 exit 2 로 막음 | 인터럽트가 이긴다. 사유는 로그에 남고, 어느 쪽이든 턴은 나아가지 않는다. 포크도 같고 goal 은 저장되지 않는다 |
| `onStart` 의 advisory 피드백이 모인 뒤 인터럽트 | 버린다. 읽을 쪽이 없다 |
| 끝내지 않는 보고 명령 | timeout 이 끝낸다. 인터럽트가 그것을 늘리지 않는다 |
| 이미 인터럽트된 스레드에서 `ignoresInterrupt` 훅을 기다림 | 끊기지 않고 기다린 뒤 플래그를 다시 세운다. 체인의 다음 훅과 호출자는 그것을 본다 |
| `HookContext` 에 메서드가 늘고 뷰가 위임하지 않음 | 뷰가 모든 메서드를 재정의하는지 테스트가 확인한다 |

## 8. 설계 결정

| 결정 | 이유 | 기각한 대안 |
|---|---|---|
| 가드와 보고로 규칙을 가른다 | 묻는 훅과 알리는 훅의 답이 반대다 | 모든 이벤트에 신호를 언제나 준다: 취소된 실행의 정리 명령이 시작하지 못한다. 보고 명령을 신호가 서기 전까지만 묶는다: [§1](#1-규칙)의 경합. 명령을 떼어 내어 기다리지 않는다: 무엇도 묶거나 보고하지 않고 세션이 그 밑에서 닫힐 수 있다 |
| 설정 키를 두지 않는다 | 이벤트가 정한다. 요청 없는 옵션을 먼저 두지 않는다 | 훅별 `ignoreInterrupt` 키: 기본값이 경합을 그대로 두고 옵션을 켜는 사람만 벗어난다 |
| 코드 훅의 답을 컨텍스트 getter 에 둔다 | 컨텍스트 타입이 곧 이벤트이고 명령마다 읽힌다 | 발화 지점마다 거른다: 체인당 한 번 평가되어 둘째 훅부터 틀리고 여섯 복사본이 어긋난다. 러너에 플래그를 둔다: 이벤트 종류를 실행기 너머로 끼워 넣어야 하고 코드 훅이 다른 답을 본다 |
| 셸 명령은 뷰로 신호를 뗀다 | 공개 SPI 와 실행기를 바꾸지 않는다 | `ShellActionExecutor.run` 에 인자를 더한다: 외부 실행기가 모두 배워야 하고 모르면 조용히 무시한다. `ShellAction` 에 둔다: 액션은 무엇을 돌릴지이다. 보고 컨텍스트가 비게 답한다: 코드 훅에게서 신호를 빼앗고 [EE-101](../../backlog/execution-environment-open-items.md)에 쓸 배관을 없앤다. 컨텍스트마다 신호 없는 복사본을 만든다: 복사 메서드 여섯이 필드마다 따라가야 한다 |
| 두 길을 다 막는다 | 신호만 막으면 로컬 셸의 정지가 명령을 멈춘다 | 신호의 길만 막는다 |
| 턴의 코디네이터를 `onStart` 앞에 만든다 | 묶을 신호가 있어야 가드가 멈춘다. 실행 하나에 신호 하나다 | 메인 `onStart` 를 묶지 않고 적어 둔다: 그 구간의 인터럽트가 버려진다. `onStart` 전용 신호를 따로 둔다: 턴 하나에 신호 둘이고 세션이 현재 신호를 알아야 한다. 슬래시 명령 흐름에도 쓴다: 인라인 스킬의 도구가 선 신호를 보게 된다. 그 자체로 정할 일이다 |
| 인터럽트된 `onStart` 는 `INTERRUPTED` 다 | 원인이 맞고 재시도 표시가 남으며 훅 종류에 달리지 않는다 | "훅이 막음" 으로 끝낸다. 포크에서 block 만 변환한다: goal 을 저장하는 길과 안 하는 길이 갈린다. 포크에 살아 있는 버퍼를 돌려준다: 재개 때 goal 이 재생된다. 판정을 `SubagentOnStartGate` 로 옮긴다: 신호가 없는 코드 behavior 경로와 공유한다 |
| 압축의 신호는 요청 필드다 | 요청 타입이 이미 나르는 길이다 | `ContextCaller` 에 둔다: 그 타입은 정체성이다. `ToolContext` 나 스레드 로컬에서 찾는다: 엔진에 `ToolContext` 가 없고 숨은 결합이다 |

## 9. 하지 말 것

- **선언적 보고 훅의 셸 실행기에 컨텍스트를 그대로 넘기지 말 것.** getter 의 답은 코드 훅의 것이다. 명령이 그것을 따르면 경합이 돌아온다.
- **`ignoresInterrupt()` 를 가드 통과의 길로 쓰지 말 것.** 얻는 것은 시간을 치른 실제 판정이다.
- **실행기에서 `context` 를 다운캐스트하지 말 것.**
- **슬래시 명령 턴의 `onStop` 에 턴의 신호를 넘기지 말 것.** 비어 있는 것이 "설 수 없다" 는 참이다.
- **턴과 포크의 인터럽트를 hook block 으로 보고하지 말 것.**
- **인터럽트된 포크의 결과에 goal 을 넣지 말 것.**

## 10. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 내용 | 타입 | 경로 |
|---|---|---|
| getter 와 답 | `HookContext`, `OnStopContext` 등 여섯, `CancellationSignals` | `hook/execution/`, `hook/event/`, `agent/interrupt/` |
| 신호에 묶기 | `ShellActionRunner`, `AbstractDeclarativeShellHook`, `DeclarativePostToolHook`, `SignalDetachedHookContext` | `skill/hook/declarative/` |
| 스레드의 길 | `ExecutionHook`, `DefaultHookExecutor` | `hook/execution/` |
| 이벤트 분류 | `SkillHookSet` | `skill/hook/` |
| 메인 턴 | `OrcaAgentExecutor`, `OrcaAgentExecutionRequest` | `agent/impl/orca/` |
| 포크 | `DefaultSubagentExecutor`, `DefaultSubagentExecutionManager` | `subagent/execution/`, `subagent/` |
| 압축 | `ContextRequest`, `CompactionGuardRequest`, `CompactionRequest`, `SummaryRequest`, `DefaultCompactionEngine` | `agent/context/`, `agent/compact/` |
