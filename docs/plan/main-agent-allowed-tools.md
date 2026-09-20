# 메인 에이전트 allow-list

> 진행 상태 문서. 작업이 끝나면 **삭제**하고, 남길 근거는 javadoc · 가이드 · `CHANGELOG.md` 로 옮긴다.

## 1. 무엇을 고치는가

`allowed-tools` 는 서브에이전트 · 스킬 · 커맨드 셋이 갖고 있는데 **메인 에이전트만 갖고 있지 않다.**
그래서 오늘 메인 에이전트는 언제나 무제한으로 돈다 — `OrcaAgentExecutor.java:2498` 의
`.allowedTools(List.of())` 가 하드코딩이고, 주석과 `ToolInvocationSpec.java:31` 이 둘 다 그렇게 적어 둔다.

빈자리를 코드가 스스로 지목한다. `toolinvocation/approval/package-info.java` 는 *"Risk that depends on the
argument rather than the tool belongs in the allow-list (`AllowedTool`, e.g. `Bash(git:*)`) or a
`PermissionRequestHook`"* 라고 쓰는데, 메인 경로에는 그 allow-list 를 **선언할 자리가 없다.** 남는 길은
훅을 코드로 짜는 것뿐이고, 그것은 설정이 아니다.

오늘 메인을 좁히는 수단 넷과 각자의 한계:

| 수단 | 좁히는 축 | 한계 |
|------|-----------|------|
| 레지스트리 구성(등록하지 않음) | 도구 유무 | 인자를 못 본다. 조립 코드이지 선언이 아니다 |
| `withMaxSideEffectLevel` | 도구 종류 | "스크래치 파일 삭제 vs 프로덕션 테이블 drop" 을 구분 못 한다(그 package-info 가 직접 그렇게 쓴다) |
| `SideEffectApprovalGate` | 사람에게 묻기 | 세션 없는 실행(스케줄 루틴·rewake)에는 물을 사람이 없어 deny 로 떨어진다 |
| `PermissionRequestHook` | 인자 | 코드다 |

## 2. 결정 사항

**D1. front-matter 키는 `allowed-tools`** (kebab). `agent.md` 의 나머지 키는 camelCase(`maxIterations`)라
파일 안에서는 어긋나 보이지만, 이 키는 **Agent Skills 스펙 이름**이고 스킬·서브에이전트·커맨드 셋이 이미
그 철자를 쓴다. 한 파일 안의 철자 일관성보다 **같은 개념이 네 표면에서 같은 이름**인 편이 크다.
`allowedTools` 로 쓴 것은 **조용히 무시하지 않고 거부**한다 — 파서가 미지 키를 무시하므로 그냥 두면
"설정했는데 안 읽히는" 가장 나쁜 실패가 된다.

**D2. 적용은 두 지점, 근거는 한 값.** 56930f5 가 서브에이전트에 세운 규칙을 그대로 따른다 —
제안 필터(`OrcaAgentExecutor.java:1675`)와 dispatch 거부(`:2498`)가 **같은**
`agent.getMetadata().getAllowedTools()` 를 읽는다. 둘이 어긋날 수 없다.

**D3. 좁히는 것은 정의 목록이지 레지스트리가 아니다.** `SubagentToolScope` javadoc 이 적어 둔 이유 그대로 —
`sessionRegistry` 는 `ToolSearchRegistry` 일 수 있고, 좁힌 복사본은 deferred 도구 활성화 상태를 버린다.

**D4. 천장은 `ToolContext` 로 흐른다.** `SingleToolInvoker` 가 `spec.getAllowedTools()` 를 이미 손에 쥐고
있으므로(`:161-167` 의 enrich 지점), 그것을 새 컨텍스트 키로 publish 하면 **메인이든 서브든 "지금 실행의
유효 allow-list"** 가 자동으로 실린다. 중첩 위임에서도 천장이 이어진다(메인 → 서브 → 그 서브의 Task).

**D5. 교집합은 `DefaultSubagentExecutor.execute()` 진입점 한 곳에서.** 5개 env 생성 지점이 각자 교집합하면
누락이 생긴다. env 는 호출자 목록을 **싣기만** 하고, 강제는 한 곳에서 한다. 공집합이면
`AllowedTools.intersect` 의 `Optional.empty()` 계약대로 **실행을 거부**한다(빈 리스트로 넘기면 "가장 엄격"이
"무제한"으로 뒤집힌다).

**D6. 기본값은 빈 목록 = 무제한.** 메인에 아무것도 안 적으면 오늘과 한 글자도 다르지 않다. 하위 호환.

## 3. Step 1 — 메인 에이전트가 목록을 선언한다

- [x] `AgentMetadata` += `List<AllowedTool> allowedTools` (기본 `List.of()`), `getAllowedTools()`,
      `hasToolRestrictions()`. 빌더에 `tools(List<String>)`(스펙 문자열 파싱) + `allowedTools(List<AllowedTool>)`
      두 setter — `SubagentMetadata.Builder` 와 같은 모양
- [x] `Agent` 인터페이스 += `default getAllowedTools()` / `hasToolRestrictions()` (`getName()` 과 같은 위임)
- [x] `DefaultAgent.Builder` += `allowedTools(...)` 통과 setter
- [x] `AgentDefinition` += 같은 필드, `MarkdownAgentDefinitionParser` 가 `allowed-tools` 파싱 (D1 의 거부 포함)
- [x] `FileSystemAgentBundleLoader.createAgent` / `ClasspathAgentBundleLoader` 가 그 값을 메타데이터로 나른다
- [x] 좁히기를 `AllowedTools.admissionFilter(List<AllowedTool>)` 로 끌어올리고 `SubagentToolScope` 는 위임 (`admissionFilter(List<AllowedTool>)`),
      `Subagent` 오버로드는 위임으로 유지 → 메인·서브가 같은 좁히기를 공유
- [x] `OrcaAgentExecutor:1675` 에 이름 필터 한 칸 추가, `:2498` 의 `List.of()` 를 실제 목록으로
- [x] 빈 제안 경고 — `DefaultSubagentExecutor.availableToolDefinitions` 의 문구를 메인용으로 (1회만)

## 4. Step 2 — 위임 천장

- [x] `ToolContextKeys.CALLER_ALLOWED_TOOLS` + 접근자 `CallerAllowedTools.of(context)`
      (`InvokingSessionAccess.idToPropagate` 와 같은 모양)
- [x] `SingleToolInvoker` 가 enrich 지점에서 `spec.getAllowedTools()` 를 그 키로 publish
- [x] `SubagentExecutionEnvironment` += `callerAllowedTools`
- [x] ~~`DefaultSubagentExecutor.execute()` 진입~~ → **`DefaultSubagentExecutionManager.runResolvedSubagent`**.
      D5 를 고쳤다(아래 §8)
- [x] env 생성 4지점이 컨텍스트에서 읽어 싣는다 — `TaskTool:551` · `WorkflowTool:447` ·
      `SubagentBackedSkillForkExecutor:135` · `GraalJsWorkflowTool:253`
- [x] `OrcaAgentRuntimeFactory:960` 의 agent-scoped `baseEnv` 는 `agent` 가 바로 옆에 있으므로 직접 싣는다

## 5. Step 3 — 문서

- [x] `docs/features/tool/tool-development-guide.md` §권한 시스템 에 "어디에 선언하는가 — 네 표면" 추가
- [x] `.en.md` 를 같은 커밋에서, `source_commit` 맞춰서
- [x] `CHANGELOG.md` `[Unreleased]`

## 6. 하지 않는 것

- **CLI yaml / 스타터 프로퍼티 표면.** `agent.md` front-matter 와 코드 빌더 둘이면 진실 원천이 둘이다.
  세 번째를 지금 열지 않는다
- **개명 없음** → `rename-maps.md` 갱신 대상 아님
- **`SubagentToolScope.scope(ToolRegistry, ...)`** 는 code-behavior 경로 전용이라 그대로 둔다

## 7. 검증

- [x] `./gradlew :aimon-core:test`
- [x] `./gradlew checkAll`
- [x] 새 테스트 — `OrcaAgentExecutorAllowedToolFilterTest`(6) · `DefaultSubagentExecutionManagerCallerCeilingTest`(6) ·
      `MarkdownAgentDefinitionParserTest.AllowedToolsParsing`(6) · `SingleToolInvokerTest`(+2) · `TaskToolTest`(+2)
- [x] `check-doc-links` · `check-translation-structure` · `check-translation-staleness`

---

## 8. 실제로 달라진 것 — D5 정정

계획은 교집합을 `DefaultSubagentExecutor.execute()` 진입점에 두기로 했는데, 실제로는 한 단계 위인
`DefaultSubagentExecutionManager.runResolvedSubagent` 에 두었다. 이유는 조사 중에 드러났다 —
그 메서드가 **resolve 된 `Subagent` 를 실행 컨텍스트로 바꾸는 유일한 지점**이고(트리 전체에
`SubagentExecutionContext.builder()` 호출이 그 한 곳뿐이다), 그 아래에서 ReAct 루프와 **code behavior**
두 갈래가 갈린다. executor 에 두었다면 behavior 경로는 천장을 안 받았을 것이다.

부수 효과로 `SubagentExecutionContext` 에 필드를 더할 필요가 없어졌다 — 좁힌 `Subagent` 자체가 컨텍스트에
들어가므로 두 소비 지점(`availableToolDefinitions`, dispatch spec)이 자동으로 따라온다.

## 9. 범위 밖으로 남긴 것

- **CLI yaml / 스타터 프로퍼티 표면** — §6 대로 열지 않았다. `agent.md` 와 코드 빌더 둘뿐이다
- **`allowedTools` 외의 camelCase 오타** — 이 키 하나만 이름으로 거부한다. 파서의 나머지 미지 키는
  여전히 조용히 무시된다(기존 동작)
- **엔드투엔드 테스트** — 사슬을 고리별로 잠갔다(발행 · 운반 · 강제). 실제 턴에서 세 고리가 이어지는 것을
  한 테스트로 확인하지는 않았다

## 10. 리뷰가 바꾼 것

PR 직전 에이전트 둘이 리뷰했고(규약 · 적대적 correctness), 둘 다 반영했다. 결과가 §3~§5 의 범위를 넓혔다.

| 발견 | 처분 |
|------|------|
| **`AllowedTools.intersect` 가 "이름만 있는 항목" 을 무제한으로 읽는다** — validator 는 `noneMatch(hasPattern)`, intersect 는 `anyMatch(!hasPattern)` 이었다. `intersect([Read, Read(/tmp/**)], [Read(/etc/**)])` → `[Read(/etc/**)]`, 즉 **포크가 호출자보다 넓어진다**. 인자 순서에도 의존했고 천장 호출부가 느슨한 쪽 순서를 쓰고 있었다 | 고쳤다. #172 에서 들어온 기존 버그지만, 이 변경이 그 함수를 **모든** spawn 의 강제 수단으로 승격시키므로 여기서 고치는 것이 맞다. 회귀 테스트 2개 + javadoc 두 문장 정정 |
| **스킬 경로가 두 절반 밖에 있다** — 스킬의 도구는 자기 목록에만 묶였다. 에이전트가 `Read, Grep` 인데 스킬이 `Bash` 를 적으면 `/my-skill` 도, 모델의 `Skill` 호출도 Bash 에 닿았다 | 고쳤다. `LlmSkillExecutor` 가 한 지점에서 교집합 → 제안·dispatch·빈 제안 경고가 한 값에서 나온다. 슬래시 경로는 `commandToolContext` 가 손으로 만들어져 위에 도구 호출이 없으므로 키를 직접 발행한다 |
| "허용목록" 표기 불일치 · 테스트의 `java.util.ArrayList` FQCN | 고쳤다 |
| `builtin-agent-skill-guide.en.md` 의 `source_commit` 변경이 이 변경셋과 무관하다 | 맞다. **별도 커밋**으로 분리했고 PR 본문에 이유를 적었다 — #172 스쿼시가 남긴 고아 SHA 라 `translations` 잡이 main 에서 이미 빨갛다 |

| **code behavior 가 천장에 "묶인다" 는 주장이 과했다** — behavior 는 실행 컨텍스트로 **전체 레지스트리**를 받는다(`SubagentToolScope` javadoc 이 *"exposes the allow-list without enforcing it"* 라고 이미 적어 둔 성질). 테스트 이름도 주장만 하고 검증하지 않았다 | 문구를 고쳤다 — "handed the same narrowed definition" 이 실제로 참인 것이고, 테스트 이름·CHANGELOG·클래스 javadoc 을 거기에 맞췄다. 코드는 그대로 |
| **`SKILL.md` 의 `allowed-tools` 는 공백 구분**이라 `agent.md` 로 옮기면 `Read Grep` 이 도구 이름 하나가 된다 | 도구 **이름**에 공백이 있으면 구분자를 알려주며 거부한다. 이름만 보는 이유는 패턴에는 공백이 정당하게 들어가기 때문(`Bash(npm install)`) |

| **`DefaultAgent.builder().metadata(m).tools(...)` 가 허용목록을 조용히 버린다** — 순서를 뒤집어도 같다. 기존 빌더 모양이지만 이제 버려지는 것이 **제한**이고 fail-open 으로 버려진다 | 두 방식을 섞으면 `build()` 가 거부한다(metadata·content 양쪽). 트리 안에 섞는 호출자는 없었고, `Agent` javadoc 예제가 바로 그 함정을 시연하며 존재하지도 않는 `AgentMetadata.of(int)` 를 쓰고 있어 함께 고쳤다 |
| `SubagentToolScope` javadoc 이 "두 모양 다 `admissionFilter` 로 표현된다" 고 적지만 `scope()` 는 자기 경로를 쓴다 | `scope()` 도 `admissionFilter` 를 쓰게 해서 문장을 참으로 만들었다 |
| 내 테스트 둘이 이름값을 못 한다 — `invoke_unrestrictedCallerPublishesAnEmptyList` 는 발행 줄을 지워도 통과한다(`CallerAllowedTools.of` 가 키 없음도 빈 리스트로 읽으므로) | 키를 직접 단언하도록 바꿨다. 발행 줄을 지우고 돌려 **4건이 실패**하는 것을 확인했다. `TaskToolTest` 쪽은 기본값 회귀 가드임을 주석으로 한정했다 |
| `warnOnEmptyToolOffer` javadoc 의 "`findAll()` only ever grows" 가 성질로 단언되어 있다 — 평범한 레지스트리는 훅·임베더가 비울 수 있다 | 문장을 사실에 맞췄다. 놓치는 것은 로그 한 줄이지 경계가 아니다 |

리뷰가 확인했으나 문제 없던 것: 빌더/불변성과 `equals`/`hashCode`/`toString`, import 순서, 패키지 경계와
ArchUnit, turn/iteration/execution 어휘, javadoc 완전성, 번역 구조 일치, `rename-maps.md` 불필요 판단.
