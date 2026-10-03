# 설계 — EE-14: `Environment` 를 없애고 `timeZone` 을 `UserLocale` 로 옮긴다

> Status: **IMPLEMENTED** (2026-10-03). 백로그 항목 EE-14 의 설계이고, 설계 리뷰에서 승인된 그대로다(한 차례, 차단 지적 없이
> 통과, 비차단 지적 아홉 건). 아래 본문(§1~§10)은 승인본에서 한 글자도 바꾸지 않았다 — 파일 수와 줄 번호 인용(`89a8ed4`
> 기준), 열린 질문 번호까지 그대로다(제목 줄만 이 디렉터리의 다른 설계 문서와 같은 형식으로 맞췄다). 그래서 본문에는 **구현 뒤에 사실이 아니게 된 서술**이 남아 있다("저장소 파일은 하나도
> 고치지 않았다", "`Environment` 는 …이다" 같은 현재형). 고친 것은 본문이 아니라 §11 에 적는다.
>
> 더한 것은 §11 하나다 — 구현이 이 설계에서 벗어난 곳과 그 이유, 반영한 리뷰 지적, 열린 질문이 어떻게 되었는지. 특히
> §11.1(§5.4 가 "고치지 않는다" 고 한 `CHANGELOG.md` 338행은 같은 릴리스의 항목이라 고쳤다)과 §11.3(설계가 꼽지 않은
> 소문자 메시지 · `toString` · 다이어그램)을 먼저 볼 것. §10 의 열린 질문 가운데 이 변경 밖으로 결과가 번지는 것은
> [`../../backlog/execution-environment-open-items.md`](../../backlog/execution-environment-open-items.md) 로 옮겼다
> (EE-60 ← §2.3 · Q1, EE-61 ← Q7, EE-1 의 보강 ← Q5). 열림/닫힘의 정본은 그 문서다. 이 설계가 닫는 질문은
> [`execution-environment.md`](execution-environment.md) §14 의 첫 불릿이고, 옛 이름과 새 이름의 대응표는
> [`../../migration/rename-maps.md`](../../migration/rename-maps.md) 에 있다.

- 대상 브랜치: `herdr/ee14-environment-to-user-locale` (HEAD `89a8ed4`, PR #205 위)
- 근거를 확인한 날짜: 2026-10-03. 아래의 파일 수·줄 번호는 모두 이 날짜, 이 브랜치 기준이다.
- 이 문서는 설계만 담는다. 저장소 파일은 하나도 고치지 않았다.

---

## 1. 문제

`at.aimon.core.agent.Environment` 는 원래 호스트(작업 디렉터리·platform·OS 버전)를 묘사하던 타입인데, 그 셋이
실행별 `EnvironmentDescriptor` 로 옮겨 간 뒤 `timeZone` 필드 하나만 남았다. 남은 값은 "명령이 어디서 도는가" 가
아니라 사용자·애플리케이션의 속성인데, 이름은 여전히 "환경" 이고 `HookContext` 에는 `getEnvironment()` ·
`getExecutionEnvironment()` · `getEnvironmentDescriptor()` 가 나란히 있어 서로 다른 세 가지가 한 단어를 나눠 쓴다.
메인테이너는 2026-09-29 에 이 타입을 없애고 `timeZone` 을 새 타입으로 옮기기로 결정했고, 공개 SPI 가 깨지는
변경이므로 EE-1 을 내보내는 코어 릴리스에 함께 싣는다. 이 run 이 할 일은 새 타입의 이름·위치를 확정하고, 저장소
안의 모든 사용처(180개 파일)를 옮기고, 낡은 Javadoc 과 문서를 바로잡고, 백로그 EE-14 를 닫는 것이다.

## 2. 착수 전에 소스로 확인한 사실

설계가 기대는 전제를 먼저 적는다. 과제 서술과 **다른 것이 둘** 있다(2.2, 2.3).

### 2.1 서술대로인 것

- `Environment` 의 필드는 `timeZone`(`ZoneId`) 하나다. 불변 class + builder, `createDefault()` 는
  `ZoneId.systemDefault()` 를 쓴다.
- `HookContext` Javadoc 21행은 아직 `Environment (working directory, platform, OS version)` 라고 적는다.

### 2.2 파급 범위는 서술보다 조금 더 크다

`import at.aimon.core.agent.Environment;` 를 가진 파일 수:

| 모듈 | main | test | 합 |
|---|---:|---:|---:|
| `aimon-core` | 44 | 123 | 167 |
| `aimon-cli` | 2 | 4 | 6 |
| `aimon-workflow-graaljs` | 1 | 3 | 4 |
| `aimon-llm-anthropic` | 0 | 1 | 1 |
| `aimon-llm-openai` | 0 | 1 | 1 |
| `aimon-spring-boot-starter` | 0 | 1 | 1 |
| **합** | **47** | **133** | **180** |

백로그의 46 / 170 은 2026-09-29 수치다. 여기에 import 없이 닿는 파일이 더 있다.

- 같은 패키지라 import 가 없는 것: `AgentEnvironmentSnapshot`, `DefaultAgentEnvironmentSnapshotProvider`(Javadoc),
  `agent/package-info.java`, 테스트 셋(`EnvironmentTest`, `AgentEnvironmentSnapshotTest`,
  `DefaultAgentEnvironmentSnapshotProviderTest`).
- 타입 이름 없이 `getEnvironment()` / `.environment(…)` 만 호출하는 것: `SingleToolInvoker`, `DefaultLiveSession`,
  `HookRegistryReloader`, `DefaultSubagentExecutionManager`, `DefaultRewakeFireListener`, `OrcaSkillToolProvider`,
  `RollingContextEngine`, `SubagentBehavior`(Javadoc).

`aimon-bootstrap` 과 세션·파일시스템·스케줄링 백엔드 모듈에는 사용처가 없다.

### 2.3 `timeZone` 을 읽는 운영 코드가 없다

`getTimeZone()` 호출은 저장소 전체에서 `EnvironmentTest` 의 세 줄뿐이다. main 소스에는 **독자가 0** 이다.

- 프롬프트의 환경 블록은 `EnvironmentBlocks.render(EnvironmentDescriptor)` 가 만들고 시간대를 싣지 않는다.
- 사용자 컨텍스트 메시지의 날짜는 `UserContextMessageBuilder` 가 `AgentEnvironmentSnapshot.getCurrentDate()`
  (`Instant`) 를 `toString()` 한 값(UTC ISO-8601)이다. `Environment` 를 거치지 않는다.
- 값을 만드는 곳은 둘뿐이고 둘 다 `createDefault()` 다 — `OrcaAgentRuntimeFactory.assemble`(914행),
  CLI `AgentSetupFactory`(1044행, 리로드 훅용). 시간대를 설정으로 줄 길은 없다(스타터의 `aimon.environment.*` 는
  **실행 환경** 설정이고 이 타입과 무관하다).

따라서 수용 기준 3 의 "프롬프트/시스템 메시지에 시간대를 싣는 경로" 는 **존재하지 않는다.** 이 타입은 지금
47개 main 파일을 관통해 전달만 되고 어디서도 소비되지 않는다. 이것이 테스트 전략(§8)과 열린 질문 Q1 을 바꾼다.

### 2.4 와이어·영속 포맷에는 나타나지 않는다

- `Environment` 를 필드로 가진 main 타입 가운데 Jackson 을 import 하는 것이 없다.
- 세션 레코드·트랜스크립트(`agent.session..`)와 서브에이전트 태스크 코덱(`subagent.task.codec`)에 참조가 없다.
- 셸 훅의 stdin payload(`ShellHookPayload`)는 `AIMON_*` 환경 맵에서 만들어지고 시간대를 싣지 않는다.
- `modules/` 아래 JSON·YAML·properties·AOT 힌트·`*.imports` 에 `timeZone` 이나 `agent.Environment` 가 없다.
- 문자열로 새는 것은 `toString()` 뿐이다(`AgentEnvironmentSnapshot.toString()` 안의 `Environment{timeZone=…}`).
  로그용이고 파싱하는 곳이 없다.

결론: **이 변경은 데이터 이행이 아니다.** 저장된 세션·태스크는 그대로 읽힌다. `ToolContext` 의 문자열 키
`"environment"` 는 프로세스 안 맵의 키이고 `docs/migration/frozen-names.md` 의 동결 목록에 없다.

### 2.5 외부 저장소 — 로컬 체크아웃으로 확인한 범위

읽기 전용으로 `~/Workspaces/github.com/kangwoo/` 의 체크아웃을 검색했다. **원격의 최신 상태가 아니라 그 체크아웃의
HEAD** 라는 한계가 있다.

| 저장소 | 본 커밋 | `Environment` 사용 |
|---|---|---|
| aimon-sandbox | `bb6c877` (2026-09-30) | 테스트 1개 — `OrcaRuntimeSandboxE2ETest` 22행 import, 108행 `.environment(Environment.createDefault())`. main 에는 없다 |
| aimon-browser | `d590703` (2026-09-16) | 없음 |
| aimon-memory | `784bfeb` (2026-09-21) | 없음 |

(샌드박스의 `options.getEnvironment()` 는 `ExecutionOptions` 의 환경 변수 맵이고 이 타입이 아니다.)

## 3. 접근

### 3.1 선택한 것

1. **새 타입 `at.aimon.core.base.UserLocale`** — 불변 `final class` + builder. 필드는 `timeZone` 하나, 계약은
   `Environment` 와 같다(널 금지, 기본값 `ZoneId.systemDefault()`, `createDefault()`, `equals`/`hashCode`/`toString`).
2. **`Environment` 를 삭제**한다. 유예용 별칭·deprecated 다리를 두지 않는다.
3. **접근자·빌더·키 이름도 함께 바꾼다** — `getEnvironment()` → `getUserLocale()`, `environment(…)` →
   `userLocale(…)`, `ToolContextKeys.ENVIRONMENT_KEY`(`"environment"`) → `ToolContextKeys.USER_LOCALE`(`"userLocale"`).
   필드·매개변수 이름 `environment` 도 `userLocale` 로.
4. **동작은 바꾸지 않는다.** 누가 값을 만들고 어디로 흘러가는지는 그대로다. `timeZone` 을 프롬프트에 싣는 일,
   설정 키를 여는 일은 이 변경에 넣지 않고 새 백로그 항목으로 남긴다(§7.2).
5. 이름 변경은 **컴파일러를 판정자로 삼는 타입 기준 변경**으로 하고, 문자열·Javadoc 은 잔재 검사(§8.4)로 잡는다.

### 3.2 이름 — `UserLocale`

결정문과 설계 §14 가 이미 이 이름을 쓰고 있어, 옛 문서로 찾아오는 사람이 그대로 닿는다. 수용 기준 1 의 두 조건에
비추면:

- **"사용자/애플리케이션 속성" 이 드러나는가** — `User` 가 그 역할을 한다. "환경" 계열 단어(`Environment`,
  `Runtime`, `Host`)를 피하므로 `ExecutionEnvironment` 와 섞이지 않는다.
- **CLAUDE.md 이름 규칙과 충돌하지 않는가** — `Session`/`AgentSession` 과 무관하다. `*Manager`/`*Store` 같은
  컨테이너 접미사가 없어 수명을 암시하지 않는다. 이 타입은 값 객체이고 수명은 **들고 있는 쪽**이 정한다(지금은
  `OrcaAgentRuntime` 이 들고 있으므로 agent-scoped). Javadoc 에 그 사실을 적는다 — "이름은 누구의 속성인지를
  말할 뿐, 사용자마다 따로 만들어진다는 뜻이 아니다. 지금은 런타임 조립 시 한 번 만든다."

알고 고른 약점 둘을 Javadoc 에 명시한다.

- `java.util.Locale` 과 이름이 닮았지만 다르다. `Locale` 은 언어·지역 서식이고 시간대를 포함하지 않는다. 이 타입은
  "사용자가 시간과 글을 읽는 방식" 이라는 넓은 뜻이고, 지금 담는 것은 시간대뿐이다.
- 값의 출처는 지금 JVM 기본 시간대다. 다중 사용자 서버에서는 "사용자의" 시간대가 아니다. 이것은 이름이 아니라
  **공급 경로가 없다**는 문제이고 §7.2 의 새 항목이 다룬다.

### 3.3 패키지 — `at.aimon.core.base`

- 사용자의 속성이라는 점에서 `Principal`(`at.aimon.core.base`) 과 같은 층이다. `ToolContextKeys` 에서도 둘이
  나란히 놓인다(`PRINCIPAL`, `USER_LOCALE`).
- `base` 는 다른 AIMON 패키지에 의존하지 않아야 하고(`coreShouldNotDependOnOtherAimonPackages`), 새 타입은
  `java.time`·`java.util` 만 쓴다 — 통과한다.
- 모든 코어 패키지가 `base` 에 의존할 수 있으므로 **ArchUnit 규칙을 넓힐 일이 없다.** `config.hook` 의 허용
  목록에도 `PKG_CORE` 가 이미 있다. 반대로 `agent` 에 두면, 앞으로 시간대를 읽고 싶은 패키지(`scheduling`,
  `llm`, `filesystem`)가 값 하나 때문에 `agent` 의존을 얻어야 한다.
- import 줄은 어느 패키지를 골라도 180개 파일에서 한 번씩 바뀌므로, `base` 를 고르는 추가 비용은 같은 패키지였던
  `agent` 의 파일 여섯에 import 를 더하는 것뿐이다.

### 3.4 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 타입만 바꾸고 `getEnvironment()` · `environment(…)` · `ENVIRONMENT_KEY` 는 그대로 | 항목의 존재 이유가 "`ExecutionEnvironment` 와 헷갈린다" 인데, 혼동이 실제로 일어나는 자리는 타입 이름보다 `HookContext` 의 `getEnvironment()` / `getExecutionEnvironment()` / `getEnvironmentDescriptor()` 다. 타입은 어차피 사라져 호출부가 전부 깨지므로, 접근자를 남겨도 사용자가 아끼는 것은 없고 혼동만 남는다 |
| `Environment` 를 `@Deprecated` 별칭으로 한 릴리스 유지 | 결정이 "없앤다, 깨지는 변경을 한 번에" 다. 두 타입이 `final` 값 객체라 상속 다리를 놓을 수 없고, 양쪽 오버로드를 35개 타입에 다는 것은 지우려는 이름을 두 배로 늘린다. 저장소 밖 사용처는 확인된 범위에서 테스트 한 줄이다(§2.5) |
| 타입을 만들지 않고 `ZoneId` 를 직접 전달 | 결정(§14 · EE-14)이 "옮긴다" 다. 언어·서식 로케일이 붙을 자리가 사라지고, 그때 다시 47개 시그니처를 건드린다 |
| `timeZone` 째로 삭제(독자가 없으므로, §2.3) | 가장 정직한 정리지만 결정을 뒤집는다. 생성자 인자 수가 바뀌어 기계적 이름 변경이 아니게 되고, 뒤에 시간대를 프롬프트에 싣기로 하면 같은 배관을 다시 깐다. 메인테이너가 정할 일이라 Q1 로 올린다 |
| 패키지를 `at.aimon.core.agent` 에 유지 | ArchUnit 영향이 0 인 것은 `base` 도 같다. "에이전트 개념" 이 아니라 "사용자 속성" 이라는 것이 옮기는 이유인데 위치가 그것을 부정한다 |
| 다른 이름 — `RegionalSettings`, `LocaleSettings`, `AgentTimeZone`, `UserSettings` | 앞의 둘은 "누구의" 가 빠져 수용 기준 1 을 덜 만족한다. `AgentTimeZone` 은 필드 하나에 묶여 확장되지 않고 수명(agent)을 이름에 넣는다. `UserSettings` 는 범위가 없어 권한·모델 선택까지 끌어들인다. 어느 것도 결정문의 어휘를 버릴 만큼 낫지 않다 |
| 키 문자열 `"environment"` 유지(상수 이름만 변경) | `"executionEnvironment"` 옆에 `"environment"` 가 남아 같은 혼동이 문자열에서 재발한다. 문자열로 꺼내던 외부 도구는 타입이 사라져 어차피 고쳐야 하고, 동결 이름도 아니다(§2.4) |
| `SubagentExecutionEnvironment` · `AgentEnvironmentSnapshot` 도 이번에 개명 | 둘은 `Environment` 타입이 아니라 다른 개념(서브에이전트 실행에 필요한 협력자 묶음, 에이전트 단위 스냅샷)이다. 범위를 넓히면 180개 파일 변경에 의미 변경이 섞인다. 전자는 새 백로그 항목으로 남긴다(§7.2) |

## 4. 바뀌는 형태

### 4.1 새 타입

```java
package at.aimon.core.base;

/** 사용자·애플리케이션 쪽 속성 — 지금은 시간대 하나. 불변, thread-safe. */
public final class UserLocale {
    public static UserLocale createDefault();   // timeZone = ZoneId.systemDefault()
    public ZoneId getTimeZone();                // never null
    public static Builder builder();            // Builder.timeZone(ZoneId), build() — null 이면 NPE
    // equals / hashCode / toString("UserLocale{timeZone=…}")
}
```

`createDefault()` 라는 이름을 유지한다. 테스트의 `Environment.createDefault()` 호출이 159곳이고, 이름을 지키면
그 줄들은 타입 이름만 바뀐다.

### 4.2 이름 변경 표

| 옛 것 | 새 것 |
|---|---|
| `at.aimon.core.agent.Environment` | `at.aimon.core.base.UserLocale` |
| `Environment.createDefault()` / `builder()` / `getTimeZone()` | `UserLocale` 의 같은 이름 |
| `X.getEnvironment()` (반환형이 `Environment` / `Optional<Environment>` 인 것) | `X.getUserLocale()` |
| `X.Builder.environment(Environment)` | `X.Builder.userLocale(UserLocale)` |
| `ToolContextKeys.ENVIRONMENT_KEY` — `ToolContextKey.of("environment", Environment.class)` | `ToolContextKeys.USER_LOCALE` — `ToolContextKey.of("userLocale", UserLocale.class)` |
| 생성자·메서드 매개변수 `Environment environment` | `UserLocale userLocale` (위치·순서 그대로) |
| 예외 메시지 `"Environment cannot be null"` | `"UserLocale cannot be null"` |

`X` 에 해당하는 공개 타입(접근자나 빌더, 시그니처가 바뀌는 것):

- 훅: `HookContext`(인터페이스)와 구현 13개 — `PreToolContext`, `PostToolContext`, `PermissionRequestContext`,
  `PermissionDeniedContext`, `OnStartContext`, `OnStopContext`, `OnSessionStartContext`, `OnSessionEndContext`,
  `OnConfigReloadContext`, `SubagentStartContext`, `SubagentStopContext`, `PreCompactContext`, `PostCompactContext`.
  `RewakeCapableRuntime`(인터페이스).
- Orca SPI: `OrcaToolProviderContext`, `OrcaProviderDependencies`, `OrcaCommandProviderContext`, `OrcaAgentRuntime`.
- 컨텍스트·압축: `ContextRequest`(`Optional` 반환), `CompactionRequest`, `CompactionGuardRequest`, `SummaryRequest`,
  `CompactionGuard`(deprecated v1 SPI — `maybeCompact` / `forceCompact` 오버로드의 매개변수 타입), 구현
  `DefaultCompactionGuard` · `NoOpCompactionGuard`.
- 서브에이전트: `SubagentExecutionEnvironment`, `SubagentExecutionContext`, `DefaultSubagentExecutor`.
- 그 밖: `AgentEnvironmentSnapshot`, `ToolInvocationSpec`, `ReloadInvoker`, `TaskTool` · `WorkflowTool` ·
  `GraalJsWorkflowTool` · `SubagentBackedSkillForkExecutor` · `CompactCommand` · `OrcaSkillForkExecutorResolver` 의
  생성자.

### 4.3 이름이 같지만 **건드리지 않는** 것

같은 단어를 쓰지만 다른 개념이다. 일괄 치환이 여기까지 닿으면 안 된다(§6 F1).

- `ExecutionEnvironment`, `EnvironmentDescriptor`, `ExecutionEnvironmentProvider`, `EnvironmentRequest`,
  `ToolContextKeys.EXECUTION_ENVIRONMENT*`, `HookContext.getExecutionEnvironment()` / `getEnvironmentDescriptor()`.
- `ExecutionOptions.getEnvironment()` · `ExecutionOptions.Builder.environment(Map)` — 셸의 환경 변수 맵.
  `ProcessBuilder.environment()`.
- `AimonProperties.getEnvironment()` · `EnvironmentProperties` — 스타터의 실행 환경 설정(`aimon.environment.*`).
  **설정 키는 하나도 바뀌지 않는다.**
- `EnvironmentBlocks`, `EnvironmentContextProvider`(`BLOCK_KEY = "environment"`), `SystemPromptPart` 의
  `kind("environment")` — 프롬프트의 환경 블록.
- `SubagentExecutionEnvironment`, `AgentEnvironmentSnapshot` 의 **타입 이름**(접근자 `getEnvironment()` 만 바뀐다)과
  `AgentEnvironmentSnapshotProvider`(`get(AgentRuntime)` 하나뿐이라 바뀌는 것이 없다).
- Spring 의 `org.springframework.core.env.Environment`.

### 4.4 와이어·영속

바뀌는 것 없음(§2.4). 문서에 그 결정을 적는다 — `rename-maps.md` 의 새 절에 "데이터 이행 아님, 동결 이름 변화 없음".

## 5. 파일·모듈별 변경

### 5.1 `aimon-core` — main

1. **추가** `base/UserLocale.java`.
2. **삭제** `agent/Environment.java`.
3. **타입 기준 이름 변경** — §4.2 의 타입들과 그 호출부. import 를 가진 44개 + §2.2 의 import 없는 파일들.
   지역 변수 이름도 `environment` → `userLocale` 로 맞춘다. 같은 메서드에 `executionEnvironment` 와 함께 나오는
   곳(`DefaultCompactionGuard`, `OrcaAgentExecutor`, `DefaultSubagentExecutor`)에서 특히 읽기 쉬워진다.
4. **낡은 Javadoc 정정** — 이름만 바꾸면 틀린 문장이 새 이름으로 남는 곳들이다.

   | 파일 | 지금 | 고칠 내용 |
   |---|---|---|
   | `hook/execution/HookContext.java` 21행, 51–56행 | "Environment (working directory, platform, OS version)", "Gets the runtime environment" | 사용자 로케일(시간대)임을 적고, 명령이 도는 곳은 `getExecutionEnvironment()` / `getEnvironmentDescriptor()` 임을 가리킨다 |
   | `tools/ToolContextKeys.java` 82–86행 | "provides access to the working directory and environment variables" | 시간대만 담는다고 적고 `EXECUTION_ENVIRONMENT` 를 가리킨다. 클래스 Javadoc 의 예제(43행)·`@see`(50행)도 |
   | `agent/package-info.java` 87행 | "Runtime environment information (working directory, platform, OS)" | 타입이 패키지를 떠났으므로 항목을 지우거나 `base.UserLocale` 링크로 |
   | `agent/impl/orca/tool/package-info.java` 119행, `agent/impl/package-info.java` 20행 | `Environment` 를 코어 추상으로 나열 | 새 타입으로 |
   | `agent/tool/permission/ToolPermissionSubjectAware.java` 36행, 80행 | "`Environment` in the context to resolve [a path] against" | 경로 주체가 실제로 무엇에 대해 풀리는지 구현을 확인하고(`ExecutionEnvironment` 의 작업 디렉터리일 것이다) 그대로 적는다 |
   | `agent/tool/ToolContext.java` 33–38행, 55행, 159–160행 · `agent/tool/package-info.java` 66행, 122–127행 · `agent/tool/ToolExecutionManager.java` 44행 · `agent/tool/execution/package-info.java` 27행 | 예제가 `put("environment", environment)` / `get("environment", Environment.class)` | `ToolContextKeys.USER_LOCALE` 예제로 |
   | `agent/AgentEnvironmentSnapshot.java` 15행, 36행 · `DefaultAgentEnvironmentSnapshotProvider.java` 27행 · `OrcaAgentRuntime.java` 53행 · `HookHotReloadBootstrap.java` 50행 | "the runtime `Environment`", 예제 코드 | 새 이름으로 |
   | `hook/rewake/RewakeCapableRuntime.java` 32–35행 | "the agent's runtime environment … observe the same environment" | 사용자 로케일로 |
   | `subagent/behavior/SubagentBehavior.java` 30행 | `context.getEnvironment()` | `getUserLocale()` |

### 5.2 `aimon-core` — test

- **추가** `base/UserLocaleTest.java` — `EnvironmentTest` 의 네 검증을 그대로 옮긴다(기본값, 빌더, 널 거부, 동등성).
  **삭제** `agent/EnvironmentTest.java`.
- **이름 변경** 123개 import 파일 + 같은 패키지 둘. 대부분 `Environment.createDefault()` 한 줄이다.
- 메시지를 단언하는 곳: `AgentEnvironmentSnapshotTest` 69행 · 82행 · 141행의 `hasMessageContaining("environment")`
  / `contains("environment")` 는 새 필드 이름(`userLocale`)으로.
- `HookContext` · `RewakeCapableRuntime` · `CompactionGuard` 를 구현한 테스트 대역은 컴파일 오류가 찾아 준다.
- `architecture/PackageDependencyArchitectureTest.java` 562행(DisplayName) · 570행(주석)의
  "`Environment` / `InvokerType` value types" 문구를 고친다 — `agent` 에서 받는 것은 이제 `InvokerType` 뿐이고
  `UserLocale` 은 `base` 에서 온다. **규칙 자체는 바꾸지 않는다.**
- 새 테스트는 §8.

### 5.3 다른 모듈

| 모듈 | 파일 | 변경 |
|---|---|---|
| `aimon-cli` main | `factory/AgentSetupFactory.java` 1044행 | `UserLocale.createDefault()` |
| | `tool/GraalJsWorkflowToolProvider.java` 65행 | `context.getUserLocale()` |
| `aimon-cli` test | 4개 | 이름 변경 |
| `aimon-workflow-graaljs` | `GraalJsWorkflowTool.java`(생성자·필드·251행의 `SubagentExecutionEnvironment` 빌더 호출), test 3개 | 이름 변경 |
| `aimon-llm-anthropic`, `aimon-llm-openai`, `aimon-spring-boot-starter` | test 1개씩 | 이름 변경 |
| `aimon-spring-boot-starter` main, `aimon-bootstrap` | — | 변경 없음. 설정 키·AOT 힌트·자동설정에 이 타입이 없다 |

### 5.4 문서

정본만 있는 것(번역본 없음 — 구현 시 `ls <파일>.en.md` 로 다시 확인):

- `docs/backlog/execution-environment-open-items.md` — §7.
- `docs/design/tool/execution-environment.md`
  - §14 머리 인용문: "`Environment` 의 남은 필드" 도 닫혔음을 더한다(EE-12 · EE-13 때와 같은 형식 — 불릿은 설계
    시점의 기록으로 두고, 더는 사실이 아니라는 문장과 지금의 동작이 있는 절을 가리킨다).
  - §6(508행 "`getEnvironment()` 는 … 그 결정(§14)을 따른다")과 §10(650–653행 "`timeZone` 은 … `Environment` 에
    남긴다") — 현재형 서술이므로 결과를 적는다: `UserLocale` 로 옮겨졌고 접근자는 `getUserLocale()` 이다.
- `docs/migration/rename-maps.md` — 새 절 "`Environment` → `UserLocale`" 을 "Related documents" 앞에 더한다.
  §4.2 의 표, §4.3 의 "이름이 같지만 바뀌지 않은 것", "데이터 이행 아님" 한 줄. 이 문서는 영어다.
- `CHANGELOG.md` `[Unreleased]` — "Breaking: `Environment` is gone; its time zone lives in `UserLocale`" 절.
  삭제된 타입, §4.2 의 표, 바뀐 `ToolContext` 키 문자열, 설정 키와 저장 데이터는 영향 없음, 이행은 `rename-maps.md`
  를 보라는 링크. **옛 릴리스 항목(338행, 3532행)은 고치지 않는다** — 그 시점의 기록이다.
- 한 줄씩 이름만 고치는 것: `docs/references/aimon-skill-extensions.md` 94행,
  `docs/design/subagent/code-defined-registration.md` 142 · 185행, `docs/design/hook/async-rewake.md` 82 · 231행,
  `docs/design/knowledge/knowledge-and-rag.md` 482행, `docs/design/agent-execution/context-engine.md` 106행.
- 내용이 낡아 문장을 고쳐야 하는 것: `docs/design/tool/contract-hardening.md` 251행("상대 경로를 `Environment` 의
  작업 디렉터리…"), `docs/design/integration/spring-boot-starter.md` 250행("`Environment.createDefault()` 의
  `user.dir`"). 둘 다 작업 디렉터리가 `Environment` 에 있던 때의 서술이다.
- `docs/design/tool/execution-environment-implementation.md`(13곳) · `execution-environment-ee9-ee12-hook-environment.md`
  (1곳) — 승인된 계획과 그 차이의 **기록**이다. 본문은 두고, 문서 머리에 "여기의 `Environment` 는 EE-14 로
  `UserLocale` 이 되었다" 는 한 줄과 `rename-maps.md` 링크를 단다(Q6).
- **새 문서** `docs/design/tool/execution-environment-ee14-user-locale.md` — 이 설계와 "구현이 설계에서 벗어난 점".
  EE-9/12, EE-13/7 의 선례를 따르며 백로그가 이 문서를 가리킨다.

정본 + 번역본 쌍(같은 커밋에서 함께 고친다):

| 정본 | 번역본 | 줄(정본 / 번역본) | 변경 |
|---|---|---|---|
| `docs/features/hook/hook-development-guide.md` | `.en.md` | 255 / 265 | 표의 행: `getUserLocale()` · `UserLocale` · "사용자 로케일(시간대)" |
| `docs/features/tool/tool-development-guide.md` | `.en.md` | 404, 721 / 406, 708 | 404: 키 표의 행. 721: 낡은 서술("`Environment` 의 작업 디렉터리") — 정본을 고치고 번역본이 따른다 |
| `docs/features/subagent/subagent-development-guide.md` | `.en.md` | 339 / 351 | `getUserLocale()` |
| `docs/features/skill/builtin-agent-skill-guide.md` | `.en.md` | 206 / 211 | 6요소 목록의 이름 |
| `docs/overview/glossary.md` | `.en.md` | 155 / 169 | `AgentEnvironmentSnapshot` 설명 속 이름 |

번역본의 `source_commit` 은 이 수정 **직전**의 정본 커밋으로 올린다. 모든 수정이 행·문장 안에서 끝나므로 구조
검사의 여섯 축(제목·펜스·표 행·리스트·인용·펜스 안 `#`)은 변하지 않는다. 제목을 건드리지 않으므로 앵커도 그대로다.

## 6. 실패 모드와 대응

| # | 실패 | 어떻게 드러나나 | 대응 |
|---|---|---|---|
| F1 | **과잉 치환** — `ExecutionOptions.getEnvironment()`, `AimonProperties.getEnvironment()`, `ProcessBuilder.environment()`, `ExecutionEnvironment` 안의 부분 문자열까지 바뀜 | 대부분 컴파일 오류. 문자열·Javadoc 은 조용히 | 치환 대상을 **§2.2 의 파일 집합으로 한정**하고 단어 경계(`\bEnvironment\b`)를 쓴다. 접근자는 수신자 타입이 §4.2 에 있을 때만 바꾼다. 끝나고 `git diff` 에서 §4.3 의 이름이 한 줄도 바뀌지 않았는지 본다 |
| F2 | **누락** — import 없는 호출부, 테스트 대역 | 컴파일 오류(`Environment` 가 사라졌으므로 `getEnvironment()` 가 남으면 심볼을 못 찾는다) | 타입을 먼저 지우고 컴파일러가 가리키는 곳을 고친다 |
| F3 | **조용한 잔재** — Javadoc 의 `{@link Environment}`, 예제 코드, 예외 메시지, 문서 | 컴파일은 통과. Javadoc 경고 또는 아무 신호 없음 | §8.4 의 잔재 검사. 출력이 허용 목록과 같아야 한다 |
| F4 | **문자열 키로 꺼내는 외부 도구** — `context.get("environment", …)` | 타입이 사라져 컴파일 오류. 타입 없이 `Object` 로 꺼냈다면 조용히 `Optional.empty()` | CHANGELOG 에 키 문자열 변경을 따로 적는다. 저장소 안에는 그런 호출이 없다(§2.4) |
| F5 | **옛 코어로 빌드된 외부 jar** 를 새 코어와 함께 실행 | `NoClassDefFoundError` / `NoSuchMethodError` | 결정대로 EE-1 과 같은 릴리스에 싣는다. 그 릴리스에서 두 외부 저장소는 어차피 다시 빌드해야 한다. aimon-sandbox 의 테스트 한 줄(§2.5)을 EE-1 본문에 적어 둔다 |
| F6 | **import 순서·포맷** — 패키지가 `agent` 에서 `base` 로 바뀌어 정렬 위치가 달라짐 | `checkFormat` 실패 | 기계적 변경 뒤 `./gradlew format`, 그다음 `checkAll` |
| F7 | **ArchUnit** | `base` 가 다른 패키지에 의존하면 실패 | `UserLocale` 은 JDK 타입만 쓴다. `agent` 만 허용하던 규칙에 걸리는 패키지가 없는지는 `:aimon-core:test` 의 architecture 패키지로 확인 |
| F8 | **번역본 어긋남** | `check-translation-structure.py` 실패, `check-translation-staleness.py` 의 낡음 보고 | 쌍을 같은 커밋에서 고치고 세 스크립트를 돌린다. `source_commit` 은 실재하는 커밋이어야 한다(해석 불가는 빌드 실패) |
| F9 | **스택 PR 충돌** — #204 · #205 가 리뷰 중 바뀌면 180개 파일 diff 가 리베이스에서 깨짐 | 리베이스 충돌 | 커밋을 둘로 나눈다 — (a) 기계적 이름 변경만, (b) 손으로 쓴 것(새 타입·테스트·Javadoc·문서). 충돌 시 (a) 는 버리고 새 base 에서 다시 만든다 |
| F10 | **프롬프트 출력이 바뀜** — 이름 변경 중 프롬프트 문자열을 건드림 | 기존 프롬프트 테스트 실패 | 프롬프트 텍스트를 단언하는 기대값은 이 PR 에서 **고치지 않는다**는 규칙(§8.2). 고쳐야 통과한다면 그것이 버그다 |
| F11 | **동작 변화가 섞임** — 리로드 훅과 런타임이 서로 다른 `UserLocale` 인스턴스를 쓰는 것을 "고침" | 리뷰에서만 | 값이 같고(`equals`) 범위 밖이다. 건드리지 않는다 |

런타임의 새 실패 경로는 없다. 널 검사 위치와 예외 종류는 그대로이고 메시지의 단어만 바뀐다.

## 7. 백로그 갱신

### 7.1 EE-14 를 닫는다

- 제목 줄: `· **닫힘** *(2026-10-03)*`.
- 본문에 "닫으면서" 를 더한다 — 무엇으로 닫았는가(§3.1), **착수해 보니 달랐던 점**(README 규칙 둘 · 셋):
  1. 파급은 47 / 180 이었다(결정 시점 46 / 170), import 없는 호출부가 여덟 파일 더 있었다.
  2. **`timeZone` 을 읽는 운영 코드가 없다.** 항목은 "남은 값의 이름이 틀렸다" 로 적었지만, 더 정확히는 "소비자가
     없는 값이 47개 파일을 지난다" 였다. 이름을 고친 것으로 혼동은 사라졌으나 값의 쓸모는 그대로 0 이다(→ EE-60).
  3. 낡은 Javadoc 은 `HookContext` 한 곳이 아니라 §5.1 의 표만큼 있었다.
  4. 와이어·영속 포맷에는 나타나지 않는다(§2.4) — 확인한 방법과 함께.
- 머리말: 제목의 카운트, "결정됨이되 열린 항목은 이제 하나(EE-6)", 이 변경을 설명하는 문장과 새 설계 문서 링크,
  새 항목이 어디서 왔는지.
- 카운트: 지금 `59건 (열림 43 · 닫힘 16)`. EE-14 를 닫고 아래 둘을 등록하면 **`61건 (열림 44 · 닫힘 17)`**.

### 7.2 새 항목 (번호는 EE-60 부터 — 문서 머리의 "59건" 과 마지막 항목 EE-59 로 확인)

- **EE-60 — `UserLocale.timeZone` 은 공급 경로도 소비자도 없다 · 열림.** 값은 언제나 JVM 기본 시간대이고
  (`createDefault()` 두 곳), 읽는 곳이 없다. 사용자 컨텍스트의 `currentDate` 는 UTC `Instant` 문자열이다. 정할 것:
  시간대를 프롬프트에 실을지, 싣는다면 출처가 무엇인지(에이전트 정의 · 스타터 속성 · `Principal` 별), 아니면 값을
  지울지. 다시 볼 때: 날짜·시각을 사용자 시간대로 말해야 한다는 요구가 처음 나올 때, 또는 다음 공개 SPI 정리 때.
- **EE-61 — `SubagentExecutionEnvironment` 는 실행 환경이 아니다 · 열림.** 서브에이전트 실행에 필요한 협력자
  묶음(레지스트리·실행 관리자·부모 `ExecutionEnvironment` …)인데 이름이 `ExecutionEnvironment` 를 포함한다.
  `Environment` 가 사라진 뒤 남는 마지막 동음이의다. 다시 볼 때: 서브에이전트 SPI 를 다음에 깨뜨릴 때.

### 7.3 EE-1 에 더한다

"`Environment` 삭제(EE-14)도 같은 릴리스에 실린다. 로컬 체크아웃에서 확인한 범위에서(§2.5 의 커밋) aimon-sandbox 는
테스트 `OrcaRuntimeSandboxE2ETest` 한 곳에서 `Environment.createDefault()` 를 쓰고, aimon-browser 와 aimon-memory
는 쓰지 않는다. 원격의 최신 상태는 확인하지 못했다." PR 설명에도 같은 문단을 넣는다.

## 8. 테스트 전략

### 8.1 새 타입

`UserLocaleTest` — 기본 시간대, 빌더로 지정, `timeZone(null)` 이면 `build()` 가 NPE, 값 동등성과 `hashCode`,
`toString()` 에 시간대가 들어 있음. (`EnvironmentTest` 의 이식 + `toString`.)

### 8.2 프롬프트 출력이 바뀌지 않음 (수용 기준 3)

§2.3 대로 시간대를 싣는 경로가 없으므로, 증명할 것은 "**이 변경이 프롬프트 바이트를 바꾸지 않았다**" 와
"**프롬프트가 `UserLocale` 에 의존하지 않는다**" 둘이다.

1. **기존 기대값을 고치지 않는다.** `OrcaAgentExecutorSystemPromptTest`, `EnvironmentContextProviderTest`,
   `UserContextMessageBuilderTest`, `ContextEngineExecutionEnvironmentTest` 가 프롬프트 텍스트를 단언한다. 이 PR 의
   diff 에서 이 파일들의 변경은 타입·접근자 이름뿐이어야 하고, 문자열 리터럴 기대값은 한 글자도 바뀌지 않는다.
   리뷰어가 diff 로 확인할 수 있는 조건이다. (구현 시 이 테스트들이 환경 블록과 날짜 줄의 **전문**을 단언하는지
   확인하고, 부분 단언뿐이면 3 의 테스트가 전문을 고정한다.)
2. **시간대 독립성 테스트 (신규).** `UserLocale` 만 다른 두 구성(`UTC` 와 `Asia/Seoul`, `Pacific/Kiritimati` 처럼
   날짜가 갈리는 시간대)에서 같은 `Instant` · 같은 `EnvironmentDescriptor` 로 조립한 (a) 시스템 프롬프트 파트 목록,
   (b) 사용자 컨텍스트 메시지가 **문자 단위로 같다**. 지금의 동작을 고정하는 특성화 테스트이고, EE-60 이 시간대를
   싣기로 하면 이 테스트가 의도적으로 깨지는 자리가 된다 — 테스트 Javadoc 에 그렇게 적는다.
3. **전문 고정 (1 이 부분 단언일 때만).** 고정된 `Instant`(예: `2026-01-01T15:30:00Z`)에 대해 사용자 컨텍스트의
   날짜 값이 정확히 그 ISO 문자열임을, 고정된 서술자에 대해 환경 블록 전문을 단언한다.

### 8.3 배관이 끊기지 않았음

값이 지나는 경로는 이미 테스트가 있다(각 `*ContextTest`, `ToolInvocationSpec`, `OrcaToolProviderContext`,
`SubagentExecutionEnvironment` 의 빌더 테스트). 이름만 바뀐 채 통과하면 된다. 더할 것은 하나다 —
**`ToolContextKeys.USER_LOCALE` 이 메인 실행과 포크 양쪽의 `ToolContext` 에 실린다**
(`OrcaAgentExecutor` 858–860행, `DefaultSubagentExecutor` 738행). 기존에 `ENVIRONMENT_KEY` 로 이를 단언하는
테스트가 있으면 이름만 바꾸고, 없으면 `"userLocale"` 문자열 조회까지 포함해 하나 더한다(키 문자열이 공개 계약이
되었으므로).

### 8.4 잔재 검사 (수동 게이트, PR 설명에 결과를 붙인다)

`modules/` 와 `docs/` 에 대해 다음이 **0건**이어야 한다.

```
at\.aimon\.core\.agent\.Environment\b
\bEnvironment\.(createDefault|builder)\(
\bENVIRONMENT_KEY\b
(Optional|ToolContextKey)<Environment>
"Environment cannot be null"
```

`\bgetEnvironment\(\)` 와 `\bEnvironment\b` 는 0건이 될 수 없으므로, 남은 것이 §4.3 의 허용 목록
(`ExecutionOptions`, `AimonProperties`, Spring `Environment`, 산문의 "Environment variable", 프롬프트의
`**Environment:**`, 역사 기록 문서, CHANGELOG 의 옛 항목)뿐인지 눈으로 확인한다.

### 8.5 게이트

- `./gradlew format` → `./gradlew checkAll` (포맷 · Checkstyle · 전 모듈 단위 테스트 · ArchUnit · `verifyBom`).
- `python3 scripts/check-translation-staleness.py`, `check-translation-structure.py`, `check-doc-links.py`.
- `integrationTest`(Docker) 는 수용 기준에 없다. `@Tag("docker")` 테스트 가운데 이 타입을 쓰는 것이 있으면
  `checkAll` 이 컴파일은 하되 실행하지 않는다 — 컴파일 통과로 충분한 변경이다. 돌리지 않았다면 PR 에 그렇게 적는다.

## 9. 구현 순서 (제안)

1. `UserLocale` + `UserLocaleTest` 추가.
2. `Environment` 삭제 → §2.2 파일 집합에 한정한 이름 변경 → 컴파일러가 가리키는 나머지 수정(core main → core
   test → 다른 모듈). `./gradlew format`. **여기까지를 커밋 (a)** — 기계적 변경만.
3. Javadoc 정정(§5.1 표), ArchUnit 문구, 새 테스트(§8.2 · §8.3). 잔재 검사.
4. 문서: CHANGELOG, `rename-maps.md`, 설계 §6 · §10 · §14, 가이드·용어집 쌍, 백로그(EE-14 닫힘, EE-60 · EE-61 등록,
   EE-1 보강, 카운트), 새 설계 문서. 번역 검사 셋. **커밋 (b)**.
5. `checkAll`. PR 은 `herdr/ee13-ee7-background-bash-lifecycle` 대상, 설명 첫머리에 스택 순서(#204 → #205 → 이 PR)와
   "앞 PR 이 머지되면 base 를 옮길 것", 그리고 §2.5 의 외부 저장소 확인 범위.

## 10. 열린 질문

과제 서술만으로 정할 수 없었던 것. 각각 이 설계가 **가정한 기본값**을 적었고, 답이 달라지면 바뀌는 범위도 적었다.

- **Q1. 독자가 없는 값을 옮기는 것이 맞는가.** `timeZone` 은 main 어디서도 읽히지 않는다(§2.3). 결정문은 "옮긴다"
  이므로 옮기는 것으로 설계했고 EE-60 으로 남겼다. 메인테이너가 "그렇다면 지운다" 를 고르면 §4.2 의 접근자·빌더·
  생성자 인자가 **이름 변경이 아니라 삭제**가 되어 변경의 성격이 달라진다. 수용 기준 3 은 전제가 틀렸으므로
  §8.2 로 다시 읽었다 — 이 해석이 받아들여지는지.
- **Q2. 이름 `UserLocale` 확정.** 과제가 "착수할 때 확정" 이라 했고 이 설계는 후보를 그대로 골랐다(§3.2). 약점은
  `java.util.Locale` 과의 혼동, 그리고 다중 사용자 서버에서 값이 사용자별이 아니라는 점이다.
- **Q3. 패키지 `at.aimon.core.base`.** `at.aimon.core.agent` 에 두는 것과 비용이 같다(§3.3). `base` 에 새 공개
  타입을 더하는 데 대한 메인테이너의 기준을 알 수 없다.
- **Q4. 접근자·키 이름까지 바꾸는 범위.** 수용 기준 2 는 "접근자/빌더/설정 키 이름 변경이 **있으면**" 이라고만
  한다. 이 설계는 `getUserLocale()` · `userLocale(…)` · `USER_LOCALE` / `"userLocale"` 로 전부 바꾼다(§3.4 첫 행).
  타입 이름만 바꾸는 쪽을 원하면 diff 는 줄지만 `HookContext` 의 세 접근자 혼동은 남는다.
- **Q5. 외부 저장소 확인의 한계.** 로컬 체크아웃의 HEAD 만 봤다(§2.5). 원격 main 과 열린 브랜치는 확인하지
  않았다. 그리고 aimon-sandbox 의 그 테스트를 **누가 언제** 고치는지(EE-1 작업에 포함되는지)는 이 저장소에서
  정할 수 없다.
- **Q6. 역사 기록 문서의 처리.** `execution-environment-implementation.md` 와 EE-9/12 설계 문서, CHANGELOG 의 옛
  항목은 본문을 두고 머리에 안내 한 줄을 다는 것으로 했다. 저장소 관례가 "기록도 새 이름으로 고쳐 쓴다" 라면
  14곳을 더 고친다.
- **Q7. 새 백로그 항목 둘을 등록할지.** EE-60 은 이 변경이 드러낸 사실이라 등록이 맞다고 봤다. EE-61
  (`SubagentExecutionEnvironment`)은 이 변경의 결과가 아니라 인접한 관찰이므로, "이 변경 밖으로 결과가 번지는
  것만 올린다" 는 문서 머리의 기준에 맞는지 판단이 필요하다. 빼면 카운트는 `60건 (열림 43 · 닫힘 17)`.
- **Q8. `ToolPermissionSubjectAware` Javadoc 의 올바른 서술.** "경로 주체를 `Environment` 에 대해 푼다" 는 문장이
  낡았다는 것은 확인했지만, 지금 무엇에 대해 푸는지는 구현을 따라가 확인하지 않았다. 같은 문장이
  `tool-development-guide.md` 721행과 `contract-hardening.md` 251행에도 있다. 구현 단계에서 코드로 확인한 뒤
  셋을 같은 내용으로 고친다.
- **Q9. 릴리스 묶음의 강제 수단.** "EE-1 과 같은 코어 릴리스에" 는 결정이지만, 이 PR 이 먼저 머지되고 릴리스가
  EE-1 전에 나가는 것을 막는 장치는 저장소에 없다. CHANGELOG 와 백로그에 적는 것으로 충분한지.

## 11. 구현이 이 설계에서 벗어난 점

2026-10-03 에 구현하면서 적었다. 접근(§3)과 바뀌는 형태(§4)는 설계대로다 — 타입은 `at.aimon.core.base.UserLocale`, 접근자 ·
빌더 · 키까지 바꿨고, 별칭은 없고, 동작은 바꾸지 않았다. 벗어난 것은 범위의 가장자리와 검증 방법이다.

### 11.1 설계 리뷰의 비차단 지적 — 반영한 것

1. **`CHANGELOG.md` 338행은 옛 릴리스 항목이 아니다**(§5.4). `[Unreleased]` 안의 문장이라 이 변경과 같은 릴리스에 실린다.
   그대로 두면 한 릴리스 노트가 "`Environment` 는 `timeZone` 만 남는다" 와 "`Environment` 는 사라졌다" 를 함께 말한다.
   그 문장을 "남았던 `timeZone` 은 `UserLocale` 로 옮겨 갔고 타입은 없어졌다(EE-14)" 로 고쳤다. `[0.2.4]` 의 항목은 설계대로
   두었다.
2. **`tool-development-guide.md` 의 예제 둘이 더 있었다**(§5.4 의 쌍 표) — `context.containsKey("environment")` 와
   `.put("environment", env)`. 번역본과 함께 고쳤다. 뒤의 것은 `ExecutionEnvironment` 변수를 그 키에 넣는, 전부터 틀린
   예제였으므로 `.put(ToolContextKeys.USER_LOCALE, UserLocale.createDefault())` 로 바꿨다.
3. **잔재 검사에 키 문자열 패턴을 더했다**(§8.4) — `(put|get|containsKey)\("environment"`. 그리고 F3 의 "Javadoc 경고로
   드러날 수 있다" 는 틀렸다: `aimon.java-conventions` 가 `Xdoclint:none` 이라 낡은 `{@link}` 에는 **신호가 전혀 없다.**
   잔재 검사가 유일한 방어선이다. **그런데 그 패턴 목록은 한 번 더 짧았다.** 빌드 리뷰가 가이드 두 쌍
   (`workflow-usage-guide`, `opensearch-knowledge-store-guide`)의 예제에서 `.environment(environment)` 를 찾았다 — 타입
   이름도 키 문자열도 없이 빌더 호출만 있는 줄이라 어느 패턴에도 걸리지 않았다. `docs/` 에 대해
   `\.environment\(` 와 `\bgetEnvironment\(\)` 를 더하고, 남은 것이 옛 이름을 일부러 적는 문서뿐인지 다시 확인했다.
4. **Q8 을 코드로 확인했다.** 경로 주체의 상대 경로는 `FilePathSubjects` 가
   `ToolContextKeys.EXECUTION_ENVIRONMENT` 의 `descriptor().workingDirectory()` 에 대해 푼다. `ToolPermissionSubjectAware` 의
   Javadoc 두 곳, `tool-development-guide.md`(+ 번역본), `contract-hardening.md` 를 그 내용으로 맞췄다.
5. **상수 이름 `USER_LOCALE`** 은 `_KEY` 접미사가 없는 쪽(`PRINCIPAL`, `SESSION_ID`)을 따랐고, 그 이유를 상수 Javadoc 에
   한 줄로 적었다.
6. **백로그 머리말은 덧붙였다.** "결정됨이되 열린 항목은 이제 다섯/넷/둘" 같은 앞 문장은 그 시점의 서술이므로 고쳐 쓰지
   않고, 끝에 "이제 하나(EE-6)" 문장을 더했다.
7. **프롬프트 전문 고정(§8.2 의 3)은 새 테스트로 만들지 않았다.** 조건("1 이 부분 단언일 때")이 성립하지 않았다 —
   `OrcaAgentExecutorSystemPromptTest` 와 `UserContextMessageBuilderTest` 가 이미 환경 블록과 날짜 줄의 전문을
   `isEqualTo` 로 단언한다. 이 변경의 diff 에서 그 두 파일은 타입 · 빌더 이름만 바뀐다. 더해서 새 테스트가 모델에게
   보낸 사용자 컨텍스트 메시지 전문을 고정한다.
8. **Q1 과 Q9 는 PR 설명 맨 위에 올릴 질문**으로 구현 요약에 넘겼다. 이 변경은 설계의 기본값대로 했다(옮긴다 / `CHANGELOG`
   와 백로그에 적는다).

### 11.2 테스트 — 설계와 다르게 한 것

- **시간대 독립성 테스트(§8.2 의 2)는 조립 함수가 아니라 실행기를 통해 쓴다.** `buildSystemPromptParts` 와
  `UserContextMessageBuilder.build` 는 애초에 `UserLocale` 을 인자로 받지 않으므로, 그 둘을 직접 부르는 비교는 무엇을
  넣어도 통과한다. `OrcaAgentExecutorUserLocaleTest` 는 `UserLocale` 만 다른 세 런타임(`UTC`, `Asia/Seoul`,
  `Pacific/Kiritimati`)으로 턴을 돌리고 **`LlmClient` 가 실제로 받은** 시스템 프롬프트와 메시지를 비교한다. 스냅숏
  제공자도 런타임의 `UserLocale` 을 스냅숏에 싣게 해서, 값이 프롬프트로 가는 길이 생기면 이 테스트가 깨지게 했다.
- **메인 실행의 키 테스트(§8.3)** 는 같은 클래스에 두었다. `USER_LOCALE` 과 문자열 `"userLocale"` 양쪽으로 **같은
  인스턴스**가 나오고 `"environment"` 키가 없음을 본다. 포크 쪽은 `DefaultSubagentExecutorTest` 의 기존 단언을 넓혀,
  `USER_LOCALE` 과 문자열 `"userLocale"` 양쪽으로 부모 컨텍스트와 **같은 인스턴스**가 나오는지 본다. `"environment"` 키가
  없다는 단언은 포크 쪽에는 두지 않았다.

### 11.3 설계가 꼽지 않았는데 고친 것

- **소문자 메시지.** §4.2 는 `"Environment cannot be null"` 만 꼽았지만, 같은 값의 널 검사에 `"environment cannot be null"` /
  `"environment must not be null"` / `"environment must not be null in context"` 가 열한 곳 있었다(`ReloadInvoker`,
  `CompactionGuardRequest`, `SummaryRequest`, `NoOpCompactionGuard`, `DefaultCompactionGuard`, `CompactCommand`,
  `WorkflowTool`, `GraalJsWorkflowTool`, `OrcaSubagentToolProvider`, `GraalJsWorkflowToolProvider`,
  `AgentEnvironmentSnapshot`). 필드 이름을 말하는 메시지이므로 `userLocale …` 로 맞췄다.
  `AgentEnvironmentSnapshotTest` 의 메시지 단언 셋(§5.2)은 이 때문에 `"userLocale"` 이 되었다.
- **`toString()` 의 `environment=`** 셋(`OrcaAgentRuntime`, `SubagentExecutionEnvironment`, `AgentEnvironmentSnapshot`)과
  `IllegalArgumentException` 메시지 둘(`"… requires an Environment"` → `"… requires a UserLocale"`), `/compact` 등록을
  건너뛸 때의 DEBUG 로그 필드 이름.
- **private 이름.** `DefaultContextEngine.requireEnvironment` → `requireUserLocale`, `DefaultSubagentExecutor` 의 내부
  클래스 `LoopContext` 의 접근자 `environment()` → `userLocale()`.
- **`docs/design/agent-execution/agent-runtime-scope.md` 의 수명 다이어그램**(Agent 행의 `Environment`). §5.4 의 목록에
  없었다. 상자가 아니라 들여쓴 목록이라 폭은 문제가 되지 않았다.
- **색인.** `docs/design/README.md` 에 이 문서의 행을, `docs/backlog/README.md` 의 표에 새 카운트(61 · 44 · 17)와 출처를
  더했다. 명세의 "관련 문서" 에도 이 문서를 더했다.
- **`PackageDependencyArchitectureTest` 의 주석**은 §5.2 대로 `agent` 줄에서 `Environment` 를 빼고, `base` 줄에 `UserLocale`
  을 더했다. 규칙은 바꾸지 않았다.

### 11.4 설계와 다르게 처리한 문서

- **`agent/impl/package-info.java`**(§5.1 표: "새 타입으로") — "Core Package — Defines abstractions (Agent, AgentExecutor,
  Environment, etc.)" 에서 이름을 **뺐다.** 그 줄은 `agent` 패키지의 추상을 나열하는데 `UserLocale` 은 `agent` 에 있지 않다.
- **`spring-boot-starter.md` 250행**(§5.4: "문장을 고쳐야 하는 것") — 고쳐 쓰지 않고 **주석을 달았다.** 그 표는 스타터를
  만들기 전 CLI 조립이 호스트에서 읽던 것의 목록이고, "`Environment.createDefault()` 의 `user.dir`" 은 그때의 사실이다.
  새 이름으로 바꾸면 `UserLocale` 이 `user.dir` 을 읽는다는 틀린 문장이 된다.
- **명세 §1 의 `Environment`**(59 · 67 · 200행 — "지금은 이렇다" 를 적은 문제 서술)는 두었다. 설계 시점의 서술이고 §10 과
  §14 에 더한 문단이 지금의 이름을 말한다.
- **가이드 두 쌍의 예제를 더 고쳤다**(§5.4 의 쌍 표에 없음, 빌드 리뷰 1 의 차단 지적) — `workflow-usage-guide` 의
  `SubagentExecutionEnvironment.builder()….environment(environment)` 와 `opensearch-knowledge-store-guide` 의
  `OrcaAgentRuntime.builder()….environment(environment)`. 같은 예제의 바로 윗줄이 전부터 없는 메서드를 부르고 있어
  (`contextId` → `agentRuntimeId`, `fileSystem` → `controlFileSystem`) 함께 고쳤다. 뒤의 예제는 그래도 완전하지 않다 —
  지금의 런타임은 `executionEnvironmentProvider` 도 필요하고 그것은 이 변경의 범위가 아니다.
- **잔재 검사의 "0건" 은 `modules/` 에서만 성립한다.** `docs/` 에서는 옛 이름을 일부러 적는 문서가 있다 — 대응표
  (`rename-maps.md`), 백로그의 EE-1 · EE-14, 기록 문서 둘, 명세 §1 · §14, 이 문서. `modules/` 에 남은 것은 `UserLocale` 의
  Javadoc 이 옛 FQN 을 한 번 적은 것과 새 테스트의 `containsKey("environment")`(없음을 단언)뿐이다.

### 11.5 하지 않은 것

- **커밋을 둘로 나누지 않았다**(§6 F9 · §9). 이 run 은 커밋하지 않고 작업 트리를 넘긴다. 기계적 변경과 손으로 쓴 변경이
  한 diff 에 섞여 있으므로, 나누려면 커밋하는 쪽이 해야 한다. 기계적 변경은 스크립트로 했다 — §2.2 의 파일 집합에 한정해
  import, 단어 경계의 타입 이름, 키 상수를 바꾸고, **주석과 문자열 밖에서만** `getEnvironment()` · `.environment(` · 식별자
  `environment` 를 바꿨다. 다른 타입이 `environment` 라는 변수를 가진 세 파일(`OrcaAgentExecutor`, `SingleToolInvokerTest`,
  `DefaultSubagentExecutorHookEnvironmentTest`)은 식별자 치환에서 빼고 손으로 고쳤다. 스크립트가 틀린 곳은 셋이었다. 둘은 컴파일러가
  잡았다 — 패키지 경로 `at.aimon.core.environment` 의 세그먼트, 그리고 테스트 셋의 `ExecutionOptions.getEnvironment()`(F1 이
  예상한 그대로다). 하나는 diff 를 읽어서 잡았다 — `OrcaAgentExecutor.buildSystemPromptParts` 의 `@param environment`
  (`EnvironmentDescriptor` 인자)가 `@param userLocale` 로 바뀌어 있었고, 주석이라 컴파일러에는 보이지 않는다.
- **`integrationTest`(Docker)는 돌리지 않았다**(§8.5). `@Tag("docker")` 테스트는 `checkAll` 에서 컴파일만 된다.
- **PR 은 열지 않았다.** §9 의 5 와 §7.3 의 "PR 설명에도 같은 문단" 은 PR 을 여는 쪽의 일이다.

### 11.6 열린 질문(§10)이 어떻게 되었나

| 질문 | 결과 |
|---|---|
| Q1 독자가 없는 값을 옮기는가 | 기본값대로 옮겼다. 백로그 **EE-60** 으로 남겼고 PR 설명에 올릴 질문이다 |
| Q2 이름 | `UserLocale`. 약점 둘은 타입 Javadoc 에 적었다 |
| Q3 패키지 | `at.aimon.core.base`. ArchUnit 규칙을 넓힌 곳이 없다 |
| Q4 접근자 · 키 범위 | 전부 바꿨다 |
| Q5 외부 저장소 확인의 한계 | 백로그 **EE-1** 에 확인한 커밋과 한계를 적었다. 원격은 여전히 확인하지 못했다 |
| Q6 기록 문서 | 본문은 두고 머리에 안내를 달았다(`execution-environment-implementation.md`, EE-9/12 설계). §11.1 의 1 만 예외다 |
| Q7 새 항목 둘 | 둘 다 등록했다(**EE-60**, **EE-61**). EE-61 은 인접한 관찰이라는 설계의 유보를 항목 본문에 적었다("관측 가능한 잘못은 아직 없다") |
| Q8 `ToolPermissionSubjectAware` | 코드로 확인하고 네 곳을 맞췄다(§11.1 의 4) |
| Q9 릴리스 묶음의 강제 수단 | 없다. `CHANGELOG` 와 백로그 EE-14 에 적었고 PR 설명에 올릴 질문이다 |
