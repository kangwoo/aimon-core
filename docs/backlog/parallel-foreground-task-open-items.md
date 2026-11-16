# 포그라운드 Task 병렬 실행 — 등록 항목 6건 (열림 4 · 닫힘 2)

출처는 [`../design/subagent/parallel-foreground-task.md`](../design/subagent/parallel-foreground-task.md) 의 구현이다. 그 문서
§8(Open questions)과 §9(구현이 이 설계와 갈라진 자리) 가운데 **이 변경 밖으로 결과가 나가는 것**만 여기로
올라왔다. 이 변경 안에서 닫힌 결정(블로킹 대신 inline fallback, 기본 `maxForkDepth = 1`, 허가가 팩토리
전체에 걸치는 것, 이름 등)은 설계 문서 §8 에 남고 여기 옮겨 적지 않는다.

`README.md` 의 규칙대로 **열림/닫힘의 정본은 이 문서**이고 설계 문서 §8 · §9 는 설계·구현 시점의 기록이다.

줄 번호는 **마지막 확인 날짜와 함께** 적는다. 아래 인용은 등록 시점(2026-10-20, 브랜치 `herdr/parallel-foreground-task` 의
작업 트리, 기준 `c2fa8fb8`) 기준이다. 경로는 따로 적지 않으면 `modules/aimon-core/src/main/java/at/aimon/core/` 아래다.

---

## PF-1 — `DedicatedThreadConfig` 를 `AimonStackBuilder` · Spring 설정으로 노출하지 않았다 · **열림**

**무엇을.** `aimon-bootstrap` 의 `AimonStackBuilder` 와 Spring 스타터의 `AimonProperties` 에서 포그라운드 Task 병렬 실행을 켜고
`maxThreads` · `maxForkDepth` 를 정할 수 있게 한다. 같은 자리에 기존 `ToolConcurrencyConfig` 도 함께 노출한다.

**왜.** 지금은 `OrcaAgentExecutorFactory.withDedicatedThreadConfig` 를 직접 부르는 임베더만 켤 수 있다. 스타터나 부트스트랩으로
조립한 호스트는 기능이 있다는 것을 알아도 켤 길이 없다. 기존 `ToolConcurrencyConfig` 도 같은 처지라(설계 §3.10, grep 0건) 이번에
하나만 노출하면 두 병렬 스위치의 노출 수준이 갈린다 — 그래서 둘을 함께 하는 후속으로 미뤘다(설계 §8 Q4).

**어디** *(2026-10-20 확인)* — `agent/impl/orca/OrcaAgentExecutorFactory.java` 의 `withDedicatedThreadConfig` ·
`withToolConcurrencyConfig`. 노출할 자리는 `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/AimonStackBuilder.java` 의
팩토리 조립부와 스타터의 `AimonProperties`.

**언제 다시 볼까.** 스타터나 부트스트랩 사용자가 둘 중 하나를 요구할 때, 또는 스타터의 설정 표면을 손볼 때.

---

## PF-2 — 백그라운드 `Task` 호출도 전용 허가를 잠깐 쓴다 · **열림**

**무엇을.** `Task(run_in_background=true)` 호출은 전용 스레드 대신 호출 스레드에서 돌게 한다 — 호출별 판정(`ToolUse` 를 보는
선언)이 필요하다.

**왜.** `TaskTool` 의 `DEDICATED_THREAD` 선언은 정적이라, 즉시 반환하는 백그라운드 launch 도 허가 하나를 잠깐 쥔다. 두 가지 결과가
있다. ① 백그라운드 launch 의 `SubagentStart` 가 **입력 순서로 찍힌다는 보장**이 사라진다 — `DefaultSubagentExecutionManager.executeInBackground`
가 Start 를 launch 스레드에서 미리 쏘는 이유가 그 순서였다. ② 한 배치에 백그라운드 launch 가 몰리면 같은 배치의 포그라운드
`Task` 가 허가를 못 얻어 inline lane 으로 밀린다(교착은 없다). 설계 §8 Q2 는 비용이 작다고 보고 정적 선언을 택했고, 가이드 §9
제한사항에 적었다.

**어디** *(2026-10-20 확인)* — `tools/task/TaskTool.java` 의 `getConcurrencyBehavior()`,
`agent/tool/DedicatedThreadDispatcher.java` 의 `isDedicated`.

**언제 다시 볼까.** 백그라운드 launch 의 Start 순서에 기대는 소비자(CLI 외)가 생길 때, 또는 한 배치에 백그라운드 `Task` 를
허가 수보다 많이 띄우는 사용이 관측될 때.

---

## PF-3 — `runForeground` 가 돌아온 뒤의 늦은 interrupt 창 · **닫힘** *(2026-11-16)*

**무엇을.** 도구가 돌아온 뒤 PostTool 훅 전에 실행 스레드의 인터럽트 플래그를 소비하고 신호로 승격할지 정한다
([`../design/agent-execution/interrupt.md`](../design/agent-execution/interrupt.md) §8 의 변경).

**왜.** `DefaultTerminatorRegistrar.fireAll` 은 terminator 를 lock 밖에서 부르고 `unregister` 는 이미 발화했으면 그냥 돌아가므로,
`Task` 가 돌아온 뒤에도 interrupt 가 도착할 수 있다. 전용 스레드에서는 그 interrupt 가 스레드와 함께 사라지지만, 그 전에 같은
스레드에서 도는 것이 둘 있다 — `SingleToolInvoker` 의 PostTool 훅과 디스패처의 `onCompleted`(`emitToolResultReady`). 플래그가 그
사이에 서면 `DefaultHookExecutor` 의 fail-open 이 PostTool 을 건너뛸 수 있다. **이 창은 오늘 순차 경로의 부모 스레드에도 똑같이
있고** 전용 스레드가 넓히지 않았다(설계 §6.7 잔여 창, §8 Q9).

**어디** *(2026-10-20 확인)* — `agent/interrupt/DefaultTerminatorRegistrar.java` 의 `fireAll` · `unregister`,
`toolinvocation/SingleToolInvoker.java` 의 PostTool 발화부, `tools/task/TaskTool.java` 의 `runForeground`.

**언제 다시 볼까.** PostTool 훅이 취소된 `Task` 뒤에 건너뛰어졌다는 보고가 있을 때, 또는 `interrupt.md` §8 을 다시 열 때.


**닫음 (2026-11-16).** 두 가지를 함께 바꿨다. ① `TerminatorRegistrar.close()` 가 **장벽**이 됐다 — 돌아온 시점에는 그
registrar 의 terminator 가 돌고 있지도 않고 이후로 돌지도 않는다. `DefaultTerminatorRegistrar` 는 `fireAll` 전체를 두 번째
모니터 `fireLock` 안에서 돌리고, `close()` 는 `closed` 를 세운 뒤 그 모니터를 한 번 지나간다. terminator 는 계약상 곧바로
돌아오므로 대기는 짧다. ② `SingleToolInvoker` 는 registrar 가 있었던 도구(`THREAD_INTERRUPT` · `EXTERNALLY_TERMINATED`)에
한해, 도구가 돌아온 뒤 PostTool **전에** registrar 를 닫고 스레드 플래그를 소비해 신호에 승격한다(`interrupt.md` §8.1–§8.2 와
같은 규칙, `absorbLateInterrupt`). registrar 가 없던 도구 — 워커 풀에서 도는 것 — 의 플래그는 건드리지 않는다(§8.5). 테스트:
`DefaultTerminatorRegistrarTest.closeWaitsForInFlightTerminator` · `closedRegistrarFiresNothingOnLaterTrip`,
`SingleToolInvokerTest.invoke_lateInterruptAfterToolReturns_isPromotedBeforePostTool`. 이 중 첫째와 셋째는 수정 전 코드에서 실패하는 것을
확인했다(둘째는 이미 성립하던 성질을 고정한다).

- **근거가 달랐다 (규칙 둘).** 항목은 결과를 *"`DefaultHookExecutor` 의 fail-open 이 PostTool 을 건너뛸 수 있다"* 로 적었지만,
  `interrupt.md` §8.7 이후 인터럽트로 끊긴 훅 대기는 정책과 무관하게 **BLOCKED** 다 — 조용한 건너뜀이 아니라 "답을 못 받았다"
  는 정직한 보고다. 남은 결과는 그 훅이 실제로 **돌지 않는다**는 것이고, 출력을 가리는 PostTool 이라면 이미 만들어진 결과에
  가림이 적용되지 않는다. 결과의 종류는 달라졌지만 창은 그대로였다.
- **처방이 모자랐다 (규칙 다섯).** 적힌 처방은 *"PostTool 전에 플래그를 소비하고 승격한다"* 였다. 그것만으로는 창이 닫히지
  않는다 — `fireAll` 은 스냅샷을 lock 안에서 뜨고 terminator 는 lock **밖**에서 부르므로, 스냅샷을 뜬 발화가 소비 **뒤에**
  `Thread::interrupt` 를 떨어뜨릴 수 있다. 이것은 코드를 읽어서 나온 것이고, 장벽이 그 순서를 막는다는 것은
  `closeWaitsForInFlightTerminator` 가 고정한다. 소비만 적용한 상태로 경합을 끝까지 재현하지는 않았다.
- **심각도 (규칙 셋).** 적힌 그대로다. 이미 취소되는 실행에서만 닿고, 전용 스레드가 넓힌 창도 아니다(순차 경로에도 같았다).

---

## PF-4 — PermissionRequest 훅의 `ASK` 는 직렬화되지만 중복 제거되지 않는다 · **열림**

**무엇을.** PermissionRequest 훅이 `ASK` 를 돌려줄 때도 같은 질문을 동시에 한 번만 묻게 할지 정한다.

**왜.** 승인 게이트(`SideEffectApprovalGate`)는 (스코프 × 도구) 단위로 진행 중인 질문을 공유해 같은 질문을 한 번만 묻는다.
PermissionRequest 훅의 `ASK` 는 그 게이트를 지나지 않고 `AskPromptHandler` 로 바로 간다. CLI 핸들러가 콘솔을 한 번에 하나씩
쓰게 되었으므로 프롬프트가 섞이지는 않지만, 병렬 포크 N 개가 같은 `ASK` 를 내면 사용자는 같은 질문에 N 번 답한다. `ASK` 의
답은 승인 게이트처럼 기억되지도 않으므로 "같은 질문" 의 단위부터 정해야 한다(설계 §9.1 의 2).

**어디** *(2026-10-20 확인)* — `hook/DefaultHookExecutionManager.java` 의 `AskPromptHandler` 사용부,
`modules/aimon-cli/src/main/java/at/aimon/cli/hook/InteractiveAskPromptHandler.java`.

**언제 다시 볼까.** `ASK` 를 내는 PermissionRequest 훅을 병렬 포그라운드 `Task` 와 함께 쓰는 배포가 생길 때.

---

## PF-5 — 부모 수준 승인 대기자는 부모 취소에 깨어나지 않는다 · **닫힘** *(2026-11-16)*

**무엇을.** 같은 승인 질문을 기다리는 호출자를 부모 취소가 깨우게 할지 정한다 — 예를 들어 대기를 턴의 취소 신호에도 묶는다.

**왜.** 병렬 `Task` 셋이 같은 세션에서 `Allow 'Task(...)'?` 를 동시에 물으면 하나가 묻고 둘이 그 답을 기다린다. 기다리는 둘의 전용
스레드에는 아직 terminator 가 없다 — 게이트는 도구 실행 **전에** 돌고 `runForeground` 는 실행 중에야 terminator 를 등록한다. 그래서
부모가 취소되어도 둘은 묻던 쪽이 답을 받을 때까지 깨지 않는다. 묻던 쪽은 오늘처럼 `console.readLine()` 에 막혀 있으므로 턴이
더 오래 걸리지는 않지만, 취소가 사람의 답을 기다린다는 사실은 그대로다. 포크 **안의** 대기자는 그 스레드에 포크의 terminator 가
걸려 있어 깨어난다(설계 §9.1 의 4).

**어디** *(2026-10-20 확인)* — `toolinvocation/approval/SideEffectApprovalGate.java` 의 `awaitShared`,
`toolinvocation/SingleToolInvoker.java` 의 게이트 호출부.

**언제 다시 볼까.** 대화형이 아닌 `AskPromptHandler`(사람 대신 원격 승인 채널)가 생겨 묻던 쪽이 취소에 반응할 수 있게 될 때 —
그때는 대기자만 매달리는 것이 실제 지연이 된다.


**닫음 (2026-11-16).** `SideEffectApprovalGate.awaitShared` 가 이제 묻던 쪽의 답 **또는** 대기자 자신의 실행 취소 신호
(`InterruptAccess.signalOf(context)`) 중 먼저 오는 것에 깬다. 신호에 건 리스너는 대기가 끝나면 지운다. 신호가 이미 떠 있으면
리스너가 등록 즉시 불리므로 대기에 들어가지 않는다. 취소된 실행은 답이 같은 순간에 왔더라도 거부되고 아무것도 기억하지 않는다
— 스레드 인터럽트로 깬 대기자와 같은 규칙이다. 테스트: `SideEffectApprovalGateTest.waiterWakesOnCancellationSignal` ·
`alreadyCancelledWaiterDoesNotWait` (둘 다 수정 전 코드에서 실패).

- **재검토 트리거를 기다리지 않았다.** 항목의 트리거(사람 대신 원격 승인 채널)는 아직 오지 않았다. 고칠 자리가 한 메서드이고
  처방이 기존 인터럽트 경로와 같은 결과를 내므로 먼저 닫았다.
- **처방의 낱말 (규칙 다섯).** 항목은 *"대기를 턴의 취소 신호에도 묶는다"* 고 적었지만 묶은 것은 **실행**의 신호다 — 대기자는
  포크나 루틴일 수도 있고, 컨텍스트가 실어 오는 것은 그 실행의 신호다. 부모 스레드에서 도는 `Task` 의 게이트라면 그것이 곧
  부모의 신호다.
- **심각도 (규칙 셋).** 적힌 그대로다. 오늘의 CLI 에서는 묻던 쪽이 `readLine()` 에 막혀 있으므로 턴이 더 늦게 끝나지는
  않는다. 달라진 것은 취소가 더는 사람의 답을 기다리지 않는다는 것이다.

---

## PF-6 — `LlmCallMetadataHolder` 의 ambient 태그는 전용 스레드에서 보이지 않는다 · **열림**

**무엇을.** 턴 스레드의 `ThreadLocal` 에 밀어 넣은 LLM 호출 태그를 전용 스레드 · 풀 워커 · 백그라운드 포크로 옮길지, 아니면 그 API 의
javadoc 에 "호출 스레드에서만 보인다" 를 적을지 정한다.

**왜.** 트레이싱 부모는 값으로 전달되어 스레드와 무관하다(설계 §6.3). 코드베이스에서 유일한 metadata `ThreadLocal` 인
`LlmCallMetadataHolder` 는 두 실행기 어느 쪽도 쓰지 않지만, 임베더가 그것으로 턴 스레드에 태그를 밀어 넣었다면 전용 스레드에서 도는
포크의 LLM 호출은 그 태그를 보지 못한다. 풀 경로와 백그라운드 포크에 **이미 있는 같은 공백**이고 이번 변경이 그 범위를 넓혔다.

**어디** *(2026-10-20 확인)* — `llm/tagging/LlmCallMetadataHolder.java`.

**언제 다시 볼까.** 임베더가 `LlmCallMetadataHolder` 로 태그를 넣는 사용이 확인될 때.
