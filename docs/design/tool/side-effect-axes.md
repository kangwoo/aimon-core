# 도구 부작용 축 분리 (Tool Side-Effect Axes)

> Status: **IMPLEMENTED (부분)** — 사다리는 `READ_ONLY < MUTATING` 2단으로 줄었고, 파괴성은
> `DestructiveBehavior` 라는 **별도 형질**로 나갔다. 승인 게이트의 무조건 질문 분기와 MCP 어노테이션
> 체인(`McpToolAnnotations` → `McpToolTraits` → `McpTool`), `McpServerConfig.annotationTrust` 까지 들어가 있다.
> 남은 것은 둘이다 — **인트리 도구의 `NON_DESTRUCTIVE` 감사**(§8.1)와 **멱등성 축**(§8.2).
>
> 적용 대상: `aimon-core` — `at.aimon.core.agent.tool` (`SideEffectLevel`, `DestructiveBehavior`, `Tool`),
> `at.aimon.core.toolinvocation.approval` (`SideEffectApprovalGate`),
> `at.aimon.core.mcp` (`McpToolAnnotations`, `McpToolTraits`, `McpServerConfig.AnnotationTrust`).

---

## 1. 문제 — 사다리가 두 질문을 접고 있었다

`SideEffectLevel` 은 도구가 자기 자신에 대해 선언하는 **순서 있는** 사다리이고, 소비자는 `permits()` 라는
**단 하나의 비교**로 판단한다. 원래는 3단이었다.

```java
READ_ONLY(0) < IDEMPOTENT(1) < MUTATING(2)
```

크기 순서로 읽히지만 실제로는 서로 다른 두 질문의 답이 한 줄에 접혀 있었다.

| 등급 | 질문 1: 쓰는가? | 질문 2: 재실행해도 안전한가? |
|---|---|---|
| `READ_ONLY` | 아니오 | (무의미 — 쓰지 않으니 항상 안전) |
| `IDEMPOTENT` | **예** | 예 |
| `MUTATING` | **예** | 아니오 |

`IDEMPOTENT` 는 `MUTATING` 보다 **부작용이 작지 않다.** 똑같이 쓴다. 다만 재생(replay)이 안전할 뿐이다.
즉 1단과 2단 사이의 간격은 "쓰는가" 라는 실재하는 크기 차이지만, 2단과 3단 사이의 간격은 **크기가 아니라
다른 축의 값**이었다.

이것은 `SideEffectPolicy` → `SideEffectLevel` 개명에서 세운 기준을 그대로 어긴 것이다. 그때 정한 규칙은
**`*Level` 은 순서 있는 형질, `*Behavior` 는 순서 없는 형질**이었고, 멱등성에는 순서가 없다.

그래서 "파괴성을 4번째 등급으로 얹자" 는 발상은 **여기에 세 번째 질문을 더 접는 것**이었다. 결론은 반대
방향이다 — 눈금을 더하는 대신 **접힌 것을 편다**.

---

## 2. 두 축이 직교한다는 증거

"파괴적 = 부작용이 더 큰 것" 이라면 사다리에 한 단 얹으면 된다. 그러나 **파괴성과 멱등성은 서로를
함축하지 않는다.** 이 저장소의 실제 도구만으로 2×2 가 채워진다.

|  | **비파괴 (non-destructive)** | **파괴적 (destructive)** |
|---|---|---|
| **멱등 (idempotent)** | `WikiIngestTool` — 페이지 id 로 색인 upsert | `DeleteSandboxTool`, `CancelScheduledTaskTool` — 두 번 지워도 결과 동일 |
| **비멱등** | `ScheduleTaskTool` — 호출마다 새 작업이 생김 | `BashTool` — 임의 명령 |

> 위 분류는 **선언이 아니라 관찰**이다. 축이 직교한다는 사실을 보이기 위한 예시이지 현재 선언 상태의
> 기술이 아니다 — 네 도구 모두 파괴성을 아직 선언하지 않는다(§7).

핵심은 왼쪽 아래(비멱등·비파괴)와 오른쪽 위(멱등·파괴) 칸이 **둘 다 비어 있지 않다**는 것이다. 사다리는
각 도구에 **위치를 하나만** 주므로 한 축으로는 이 두 칸을 동시에 표현할 수 없다.

MCP 가 `readOnlyHint` / `idempotentHint` / `destructiveHint` 를 **독립 필드**로 둔 것도 같은 이유다.
사다리를 안 만든 게 아니라 만들 수 없어서 안 만든 것이다. **도달한 축 구조는 MCP 와 같고**, 다른 것은 축의
개수가 아니라 그것을 Java 표면에 어떻게 놓는가뿐이다(§4).

---

## 3. 3단 사다리가 열어 두었던 구멍

승인 게이트의 면제선(`exemptAtOrBelow`)은 프롬프트 피로를 줄이는 노브다. 3단 시절 이 노브를 `IDEMPOTENT`
로 두면 면제되는 집합은 **"안전한 것들"** 이 아니라 **"재실행이 안전한 것들"** 이었고, 거기에는 §2 의
오른쪽 위 칸이 통째로 들어 있었다.

```
면제선 = IDEMPOTENT  →  DeleteSandboxTool, CancelScheduledTaskTool 은 묻지 않고 통과
                        BashTool 은 여전히 묻는다
```

`rm -rf` 는 멱등이다. 두 번 실행해도 결과가 같다. **멱등성은 안전성이 아니라 수렴성이다.** 따라서 이
노브는 프롬프트 피로를 줄이려는 운영자에게 **가장 물어봐야 할 부류를 정확히 골라서 면제**했다. 실링
(`DefaultToolExecutionManager`, `OrcaAgentExecutor`)도 같은 `permits()` 를 쓰므로 같은 구멍을 공유했다.

그리고 이 구멍은 **잘못된 선언 때문이 아니라 올바른 선언 때문에** 열렸다. `IDEMPOTENT` 의 javadoc 은
*"upserts keyed by a caller-supplied id, or writes that overwrite rather than append"* 라고 안내하고 있었고,
`WriteTool` 이 정확히 그것이다. 문서를 읽고 `WriteTool` 을 `IDEMPOTENT` 로 선언하면 그것은 **규격대로 맞는
선언**이며, 그 순간 면제선이 파일 덮어쓰기를 조용히 통과시킨다.

구멍이 발견된 시점에 중간 눈금을 선언한 도구는 **0개**였다. 그래서 이 문서는 사고 보고서가 아니라 사고
예보였고, 고쳐야 할 선언이 0건이라 **가장 싼 시점**이었다. `SideEffectLevel` 이 아직 릴리스 전이었으므로
`@Deprecated` 단계도 건너뛰고 상수를 바로 지웠다.

---

## 4. 채택안 — 축과 어휘는 MCP 에서, 형태와 신뢰 경계는 AIMON 에서

### 4.1 사다리 1개 + 형질 N개

축은 MCP 와 1:1 로 맞추고 이름도 MCP 의 단어를 그대로 쓰되, 표면은 boolean 3개가 아니라 **사다리 1개 +
형질 N개**다.

```java
// 상위 축 — 순서가 진짜 있으므로 permits() 는 그대로 의미를 갖는다. 실링이 쓰는 것은 이것뿐.
public enum SideEffectLevel { READ_ONLY(0), MUTATING(1) }

// 형질 — 순서 없음. 기본값은 MCP spec 기본값과 같은 쪽(= 최악).
public enum DestructiveBehavior { NON_DESTRUCTIVE, DESTRUCTIVE }   // default DESTRUCTIVE
```

| MCP annotation | AIMON 축 | 형태 | spec 기본값 ↔ AIMON 기본값 |
|---|---|---|---|
| `readOnlyHint` | `SideEffectLevel` | 사다리 | `false` ↔ `MUTATING` |
| `destructiveHint` | `DestructiveBehavior` | 형질 | `true` ↔ `DESTRUCTIVE` |
| `idempotentHint` | 아직 없음 (§8.2) | 형질 예정 | `false` ↔ (`NON_IDEMPOTENT`) |
| `openWorldHint` | 대응 없음 (§8.3) | — | — |

기본값이 양쪽에서 일치하므로 **어댑터의 "어노테이션 없음" 경로에 특수 처리가 없다** — 없으면 그냥 AIMON
기본값이고, 그 값이 곧 MCP 기본값이다.

### 4.2 상위 축은 타입이 아니라 소비 규칙으로 강제한다

`readOnlyHint` 는 나머지 둘과 직교하지 않는다. 읽기 전용이면 파괴성·멱등성 질문이 무의미해지므로 셋 중
하나는 나머지를 무효화하는 **상위 축**이고, boolean 3개로 두면 `readOnly=true` + `destructive=true` 라는
**모순이 컴파일된다.**

이것을 타입으로 막지 않고 **소비 규칙**으로 막았다. 소비자는 형질을 **`MUTATING` 일 때만 읽는다.**

```java
// SideEffectApprovalGate.denialReason
final SideEffectLevel declared = tool.getSideEffectLevel();
final DestructiveBehavior destructive = declared == SideEffectLevel.MUTATING
        ? tool.getDestructiveBehavior()
        : DestructiveBehavior.NON_DESTRUCTIVE;
```

`READ_ONLY` 면 두 번째 축은 **조회되지 않으므로** 모순 상태가 도달 불가능해진다. 두 선언에 걸치는 유효성
검사가 필요 없고, `Tool.isReadOnly()` 가 `getSideEffectLevel()` 에서 **파생**된다는 기존 불변식도 그대로
산다. `SideEffectApprovalGateTest.readOnlyTool_isExemptDespiteDestructiveDeclaration` 이 이 규칙을 고정한다.

### 4.3 누가 무엇을 읽는가

| 소비자 | `SideEffectLevel` | `DestructiveBehavior` |
|---|---|---|
| `DefaultToolExecutionManager` — 실링 초과 도구의 **실행 거부** | 읽음 (`permits()`) | 읽지 않음 |
| `OrcaAgentExecutor` / `DefaultSubagentExecutor` / `LlmSkillExecutor` — 실링 초과 도구의 **정의를 LLM 에 주지 않음** | 읽음 (`permits()`) | 읽지 않음 |
| `SideEffectApprovalGate` — 사용자에게 **물음** | 읽음 (면제선) | **읽음 — 면제선을 무시하고 묻는다** |

실링 3종은 축 분리에도 **무수정**이다. 사다리에서 눈금 하나가 빠졌을 뿐 비교 방식이 그대로이기 때문이다.
파괴성을 읽는 것은 게이트 하나이며, 게이트에서 **"`DESTRUCTIVE` 면 면제선과 무관하게 묻는다"** 가 §3 의
구멍이 닫히는 지점이다. 면제선의 어떤 값으로도 게이트를 끌 수 없다는 비대칭이 의도한 것이다 — 면제선은
`SideEffectLevel` 이 순서 있는 축이라서 순서가 있고, 파괴성은 그 축 위에 없다.

---

## 5. MCP 신뢰 경계

### 5.1 주장과 판정을 타입으로 가른다

MCP 어노테이션은 **원격 서버가 자기를 서술하는 주장**이고, AIMON 도구의 선언은 **호스트가 컴파일한 사실**
이다. 둘을 같은 이름으로 부르면 진짜로 untrusted 인 그 한 곳이 보이지 않는다. 그래서 체인이 3단이다.

```
서버 JSON  ──DefaultMcpClient.parseToolAnnotations──▶  McpToolAnnotations   (서버의 주장, 원문 그대로)
                                                              │
                                        McpToolTraits.resolve(annotations, trust)   ← 판정은 여기 한 곳
                                                              ▼
                                                       McpToolTraits        (판정 결과)
                                                              │
                                                              ▼
                                                          McpTool           (AIMON 선언)
```

- `McpToolSchema` 는 `annotations` 를 **판정하지 않고** 실어 나른다. 서버가 아무것도 안 보내면
  `McpToolAnnotations.empty()` 이고, 그 안에서 MCP spec 기본값이 적용된다.
- 판정은 `McpClientManager.registerAllTools` 에서 **도구당 1회**다. getter 는 호출마다 불리므로 판정을
  `McpTool` 안에 두면 매번 다시 하게 되고, 무엇보다 "도구는 형질을 **선언**하고 판정은 밖에서 한다" 는
  §4 의 규칙이 MCP 경로에서도 문자 그대로 성립해야 한다.
- 판정을 지난 뒤에는 **아래쪽 누구도 MCP 도구와 로컬 도구를 구분하지 못한다.** 신뢰된 읽기 전용 MCP
  도구는 로컬 읽기 전용 도구와 정확히 같은 의미로 읽기 전용이다.

`McpToolAnnotations` 는 `idempotentHint` 와 `openWorldHint` 도 **파싱해서 보관하되 아무 선언도 만들지
않는다.** 대응 축이 아직 없기 때문이며(§8.2·§8.3), 버리지 않은 것은 §3 과 같은 이유다 — 버려 둔 값은
축이 생길 때 파서까지 되짚어야 한다.

### 5.2 신뢰는 서버 단위 설정이다

```java
public enum AnnotationTrust { IGNORE, TRUST }   // McpServerConfig 안, 기본값 IGNORE
```

**왜 `McpServerConfig` 인가.** 신뢰는 도구의 성질도 프로토콜의 성질도 아니고 **운영자가 특정 서버에 대해
내리는 판단**이다. `McpServerConfig` 는 이미 정확히 그것만 담는다(어느 명령을 띄울지, 어느 URL 에 붙을지,
타임아웃을 얼마로 둘지). 값이 이미 흐르는 경로에 얹히므로 새 배선이 없다 —
`McpServerConfigProvider` → `OrcaMcpToolProvider` → `McpClientManager.createClient(config)`.

**왜 값이 2개인가.** 가능한 중간 값은 "보수적인 선언만 믿는다" 인데, 보수적인 선언(`readOnlyHint: false`,
`destructiveHint: true`)은 **어차피 AIMON 기본값과 같으므로** 믿든 안 믿든 결과가 같다. 실질적 선택지는
"권한을 낮춰 주는 주장을 받아들이는가" 하나뿐이다.

**왜 기본이 `IGNORE` 인가.** 두 가지가 겹친다. 첫째, 어노테이션을 읽기 전의 동작이 그것이므로 회귀가 없다.
둘째, MCP 스펙 자체가 어노테이션을 **신뢰할 수 없는 힌트**로 규정한다 — 서버가 삭제 도구에
`readOnlyHint: true` 를 붙여도 프로토콜은 막지 않는다. 그러므로 "이 서버의 주장을 받아들여 내 방어선을
낮춘다" 는 **명시적 opt-in** 이어야 하고, 그 한 줄이 곧 운영자의 책임 표명이다.

`IGNORE` 의 대가는 **가용성**이다. 서버가 `readOnlyHint: true` 를 보내도 그 도구는 `MUTATING` 이 되어
`READ_ONLY` 실링에서 통째로 숨겨지고 승인 게이트가 매번 묻는다. 방향이 fail-safe 쪽이라 보안 결함은 아니다.

**이름을 `annotationTrust` 로 좁힌 이유.** `trusted` 처럼 넓게 지으면 "이 서버를 믿는다" 가 어노테이션과
무관한 결정까지 매달 수 있는 축이 된다. 무엇을 믿는지를 이름에 박아 두면 그런 일이 생기지 않는다. 신뢰해야
할 다른 것이 생기면 그때 **별도 필드**를 만든다.

**전용 SPI(`McpAnnotationTrustPolicy`)를 만들지 않은 이유**도 같은 종류다 — 판정 규칙이 하나뿐인데 전략
객체를 두면 간접층만 늘어난다. "서버 이름 패턴으로 신뢰를 준다", "도구 이름별로 예외를 둔다" 같은 두 번째
규칙이 실제로 요구될 때가 SPI 의 자리이고, 그때 이 enum 필드는 기본 구현이 읽는 값으로 남는다.

**`McpClientManager` 가 보관하는 것은 config 전체가 아니라 `Map<String, AnnotationTrust>` 하나다.**
나머지 필드는 접속에만 쓰이고 등록 시점에는 읽을 이유가 없으므로, 보관해 두면 "여기서도 config 를 볼 수
있다" 는 잘못된 초대가 된다.

**CLI YAML 의 오타는 시작 시점에 실패시킨다.** 인식하지 못한 `annotationTrust` 값을 `IGNORE` 로 흘리면
"믿지 않는다" 라는 안전한 쪽이긴 해도 **설정한 쪽이 아니며**, 조용히 아무 일도 하지 않는 설정이 더 나쁘다.

### 5.3 정규화 한 가지

`READ_ONLY` 로 판정된 도구는 서버가 `destructiveHint: true` 를 함께 보냈더라도 항상
`NON_DESTRUCTIVE` 다. 소비자는 `MUTATING` 아래에서 두 번째 축을 읽지 않으므로(§4.2) 그 조합은 **저장될 뿐
행동에 반영될 수 없고**, 그대로 두면 `toString()` 과 미래의 독자가 실제 동작과 어긋난 말을 하게 된다.

이 정규화가 모순을 **관대한 쪽**으로 푼다는 점은 의도한 것이다 — `TRUST` 아래에서 자기모순을 보내는
서버는 이미 둘 중 **더 결과가 큰 주장**(읽기 전용)에 대해 믿어진 상태다.

---

## 6. 기각한 대안

### 6.1 4번째 등급 `DESTRUCTIVE` 를 사다리에 추가

```java
READ_ONLY(0) < IDEMPOTENT(1) < MUTATING(2) < DESTRUCTIVE(3)
```

가장 싼 안이다 — enum 상수 1개, `permits()` 무수정, 소비자 전부 무수정. 그러나 §2 의 2×2 를 표현하지
못한다. `DeleteSandboxTool` 은 `DESTRUCTIVE` 로 올라가면서 **멱등하다는 정보를 잃고**, 반대로 멱등성을
지키려고 `IDEMPOTENT` 에 두면 파괴적이라는 사실을 잃는다. 즉 §3 의 구멍을 **막지 못하면서** 선언자에게 두
진실 중 하나를 버리라고 요구한다.

부수 피해도 있다. `MUTATING` 은 "기본값 = 미감사" 와 "무제한 실링" 두 역할을 겸하는데, 위에 등급이 생기면
그 둘이 갈라져 **미감사 도구가 최악값을 뜻하지 않게 된다.** 기본값을 `DESTRUCTIVE` 로 올리면 이번엔 기존
도구 전부가 파괴적이라고 주장하게 된다.

### 6.2 MCP 필드 3개를 Java 표면에 그대로 (boolean 3개)

`SideEffectLevel` 을 없애고 `Tool` 에 `isReadOnly()` / `isIdempotent()` / `isDestructive()` 를 둔다.

**어휘는 채택했다** — "destructive" 는 운영자가 실제로 쓰는 단어이고, `Reversibility` 같은 발명어보다
낫다. MCP 의 기본값 방향(`destructiveHint` 기본 `true`)이 "미선언 = 최악" 이라는 우리 철학과 같다는 점도
그대로 가져왔다. 기각한 것은 **형태**다.

- `readOnlyHint` 가 나머지 둘과 직교하지 않아 **모순이 컴파일된다**(§4.2).
- `Tool.isReadOnly()` 가 **파생값이라 drift 할 수 없다**는 기존 불변식이 깨진다.
- `"Hint"` 는 이름에 신뢰 수준을 박은 단어다. `ReadTool` 은 힌트를 주는 게 아니라 호스트가 컴파일한
  선언을 하고, `DefaultToolExecutionManager` 는 그 선언을 근거로 **실행을 거부**한다. 게이트의 입력을
  "hint" 라 부르면 정확히 반대로 읽힌다. 이름을 갈라 둬야 §5.1 의 신뢰 경계가 존재할 수 있다.
- 실링이 **단일 노브를 잃는다.** 지금 호스트 설정은 enum 값 하나 + 비교 하나인데, boolean 3개면 서로
  모순되는 조합을 포함한 술어 설정이 된다.

### 6.3 합성 값 객체 `ToolEffect`

`Tool#getEffect()` 가 `{ level, destructive, idempotent }` 를 한 번에 돌려주는 안. 축 간 불변식을 한 곳에서
강제할 수 있다는 장점은 진짜다(6.2 는 못 하는 일이다). 그러나 실링 비교가 전순서에서 **격자(부분 순서)**
가 되어 `permits()` 가 사라지고 소비자들이 다차원 비교를 직접 써야 한다. 도구가 선언하는 것이 값 객체가
되면서 `InterruptBehavior` / `ConcurrencyBehavior` 와 모양도 갈라진다.

축이 3개 이상으로 확정되고 축 간 불변식이 실제로 필요해지면 그때가 이 안의 자리다. **축이 2개인 지금
도입할 이유가 없다.**

### 6.4 파괴성을 도구가 아니라 **입력**으로 판정

`BashTool("ls")` 와 `BashTool("rm -rf /")` 는 같은 도구, 다른 파괴성이다. 그러니 호출 인자를 보고 판정하자는
안 — **그 계층은 이미 존재한다.** `BashTool` 이 `ToolPermissionSubjectAware` 로 내놓는 `command` 를
`DefaultToolPermissionValidator` 가 `Bash(git:*)` 같은 패턴에 대조하는 것이 정확히 그 일이다. `SideEffectLevel` 은 의도적으로 **입력과 무관한** 축이며, 그 분리는
`SideEffectApprovalGate` 의 `package-info` 에 명시되어 있다 — 레벨은 도구가 무엇을 하는지를 말하지 지금
여기서 얼마나 위험한지를 말하지 않는다. 스크래치 파일 삭제와 프로덕션 테이블 드롭은 둘 다 같은 레벨이고,
그 둘을 가르는 것은 레벨이 아니라 승인과 권한 규칙이다.

같은 이유로 `DestructiveBehavior` 도 **도구 단위**다. 플래그에 따라 가끔만 덮어쓰는 도구는
`DESTRUCTIVE` 다 — 이 축은 도구에 대한 것이지 한 호출의 인자에 대한 것이 아니다.

### 6.5 `ConcurrencyBehavior` 로부터 파괴성을 유도

`SEQUENTIAL` 인 도구가 대체로 쓰는 도구이니 그것을 신호로 쓰자는 안. `SideEffectLevel` javadoc 이 이미
반례를 두 개 들고 있다 — 레이트 리밋 때문에 `SEQUENTIAL` 인 읽기 전용 도구, `CONCURRENT_SAFE` 인 멱등 쓰기
도구. 두 축은 상관이 있을 뿐 서로에서 유도되지 않는다.

### 6.6 `DestructiveBehavior` 에 3번째 값 `RECOVERABLE`

```java
public enum DestructiveBehavior { NON_DESTRUCTIVE, RECOVERABLE, DESTRUCTIVE }
```

`rm -rf` 와 "휴지통으로 보내기" 를 같은 칸에 두는 것이 거칠다는 지적은 타당하다. 그래도 2값으로 갔다.

**첫째 — 손실은 와이어가 아니라 소비자 쪽에서 난다.** AIMON 은 MCP **클라이언트 전용**이므로(AIMON 도구를
MCP 서버로 내보내는 구현은 없다) "내보낼 때 어느 쪽으로 접는가" 라는 문제는 존재하지 않고, 들어오는 방향도
무손실이다 — boolean 두 값이 양 끝으로 매핑되고 `RECOVERABLE` 은 애초에 도착하지 않는다. 대가는 **소비자마다
세 번째 분기가 생기는 것**이다. 게이트의 규칙이 "`DESTRUCTIVE` 면 면제선과 무관하게 묻는다" 인데
`RECOVERABLE` 은 묻는가 마는가? 답이 자명하지 않고, 그 결정을 게이트·실링·앞으로 생길 소비자마다 따로
내려야 한다. 2값이면 그 분기 자체가 없다.

**둘째 — 복구 가능성은 대개 도구의 성질이 아니라 환경의 성질이다.** 같은 `DeleteSandboxTool` 이 스냅샷 있는
클러스터에서는 복구 가능하고 없는 클러스터에서는 아니다. 파일 삭제가 휴지통 있는 VFS 에서는 복구 가능하고
GridFS 에서는 아니다. **도구는 그것을 알 수 없다.** §6.4 에서 입력 판정을 기각한 것과 같은 종류의 오류이며,
축만 입력이 아니라 환경 쪽으로 옮겨간 것이다.

예외는 **도구가 복구 메커니즘을 스스로 구현한 경우**다 — 자기가 휴지통으로 옮기는 도구라면 그것은 진짜
도구의 성질이고 정직하게 선언할 수 있다. 그런 도구가 실제로 생기는 시점이 3번째 값의 자리다.
`DestructiveBehaviorTest.twoValues()` 가 이 결정을 고정하므로, 위 두 문단을 다시 읽지 않고 값을 늘리는 일은
생기지 않는다.

---

## 7. 지금 선언 현황

| 항목 | 값 |
|---|---|
| `AbstractTool` / `GenericTool` 을 상속하는 구체 도구 (전 모듈 main) | **47** |
| 그중 `getSideEffectLevel()` 을 override 하는 도구 | **13** (전부 `READ_ONLY`) |
| `McpToolTraits` 로 두 축을 위임하는 도구 | **1** (`McpTool`) |
| 기본값(`MUTATING` + `DESTRUCTIVE`)에 머무는 도구 | **33** |
| `getDestructiveBehavior()` 를 override 하는 **인트리** 도구 | **0** |
| `permits()` 외의 `SideEffectLevel` 비교 지점 | **0** |
| 프로덕션 경로에서 `SideEffectApprovalGate` 를 생성하는 곳 | **0** (전부 테스트) |

마지막 두 줄이 §8.1 의 전제다.

---

## 8. 남은 것

### 8.1 인트리 도구의 `NON_DESTRUCTIVE` 감사 — 게이트 배선의 선행 조건

`SideEffectApprovalGate` 는 opt-in 이고(`OrcaAgentExecutorFactory.withApprovalGate` /
`DefaultSubagentExecutor.withApprovalGate`) **프로덕션 경로 중 게이트를 생성하는 곳이 아직 없다.** 그래서
축 분리로 회귀한 것은 하나도 없다. 회귀가 관측되는 것은 **게이트를 배선하는 변경**에서다.

그리고 그때의 회귀는 파괴적 도구에 그치지 않는다. `DestructiveBehavior` 의 기본값이 `DESTRUCTIVE` 이고
`NON_DESTRUCTIVE` 를 선언한 인트리 도구가 아직 하나도 없으므로(§7), 배선 직후에는 **파괴적 도구가 아니라
쓰는 도구 전부**가 무인 경로에서 실패한다. 같은 사실이 `SideEffectApprovalGate` 의 `exemptAtOrBelow`
javadoc 에도 박혀 있다 — 감사가 끝나기 전에는 면제선을 `MUTATING` 으로 올려도 **면제되는 것이 하나도 없다.**

감사가 축 도입과 같은 변경에 들어가지 않은 것은 의도다. 도구 하나하나에 대한 판단이므로 축을 만드는 변경과
섞으면 둘 다 검토하기 어려워진다.

**무인 실행에서 파괴적 도구가 거부되는 것은 버그가 아니다.** "`DESTRUCTIVE` 면 묻는다" 는 운영자가 끌 수
없는 규칙이고, 세션이 없는 실행(스케줄 루틴, rewake 리플레이, 서브에이전트 포크)에는 물을 채널이 없으므로
핸들러 기본값인 deny 로 떨어진다. 무인 실행이 파괴적 도구를 조용히 집행하는 것보다 실패하는 편이 낫다.
따라서 무인 경로에서 파괴적 도구가 필요하면 방법은 **묻지 않고 허용하는 설정을 켜는 것이 아니라**, 그
실행에 응답할 수 있는 승인 핸들러를 붙이거나(사전 승인 목록, 비대화형 정책 핸들러) 도구를 비파괴적으로
다시 설계하는 것이다.

### 8.2 멱등성 축 (`IdempotencyBehavior`)

```java
public enum IdempotencyBehavior { NON_IDEMPOTENT, IDEMPOTENT }   // default NON_IDEMPOTENT
```

멱등성 개념은 §1 에서 **폐기된 것이 아니라 이동 대기 중**이다. 사다리에서 뺀 것은 그것이 순서가 아니기
때문이지 쓸모가 없어서가 아니다. `McpToolAnnotations` 는 `idempotentHint` 를 이미 파싱해 두고 있으므로
축이 생기면 `McpToolTraits.resolve` 에 한 줄이 는다.

만드는 시점은 **첫 소비자가 생길 때**다 — 도구 재시도나 rewake 리플레이가 "이 도구를 다시 실행해도
되는가" 를 실제로 물어볼 때. 소비자 없이 축부터 만들면 선언만 쌓이고 아무도 읽지 않는다. 규모는 새 파일
1개 + `Tool` 의 default 메서드 1개 + `McpToolTraits` 수정 1곳이다.

### 8.3 `openWorldHint`

대응 축이 없다. `McpToolAnnotations` 가 값은 보관하지만 어떤 선언도 만들지 않는다. 통째로 채택하면 4번째
축이 따라오므로, 그것을 읽을 소비자가 나타날 때까지 미룬다.

---

## 부록. 참조 파일 지도

| 관심사 | 파일 |
|---|---|
| 사다리 (2단, `permits()`) | `agent/tool/SideEffectLevel.java` |
| 파괴성 형질 (2값, default `DESTRUCTIVE`) | `agent/tool/DestructiveBehavior.java` |
| 도구의 선언 지점 (default 메서드 2개 + 파생 `isReadOnly()`) | `agent/tool/Tool.java` |
| 실링 — 실행 거부 | `agent/tool/DefaultToolExecutionManager.java` |
| 실링 — 정의 필터 | `agent/impl/orca/OrcaAgentExecutor.java`, `subagent/execution/DefaultSubagentExecutor.java`, `skill/execution/llm/LlmSkillExecutor.java` |
| 승인 게이트 (면제선 + 무조건 질문 분기) | `toolinvocation/approval/SideEffectApprovalGate.java` |
| MCP — 서버의 주장 | `mcp/McpToolAnnotations.java`, `mcp/McpToolSchema.java`, `mcp/DefaultMcpClient.java` |
| MCP — 판정 | `mcp/McpToolTraits.java`, `mcp/McpServerConfig.java` (`AnnotationTrust`) |
| MCP — 판정 지점 (도구당 1회) | `mcp/McpClientManager.java` (`registerAllTools`) |
| MCP — 판정 결과의 소비 | `mcp/McpTool.java` |
| CLI YAML 표면 (오타 fail-fast) | `aimon-cli` — `config/McpServerEntry.java` |
| 결정을 고정하는 테스트 | `SideEffectLevelTest.twoRungsOnly`, `DestructiveBehaviorTest.twoValues`, `SideEffectApprovalGateTest` (면제선 · 무조건 질문 · 읽기 전용 예외), `McpToolTraitsTest` |

## 관련 문서

- [도구 개발 가이드](../../features/tool/tool-development-guide.md) — 도구 작성자가 읽는 문서
- [MCP 도구 통합](../integration/mcp-tool.md) — 어노테이션이 실려 오는 경로 전체
- [도구 병렬 실행](parallel-execution.md) — 같은 `*Behavior` 규약을 따르는 다른 축
- [도구 계약 강화](contract-hardening.md) — 스키마 게이트와 입력 검증
- [Orca 실행기](../agent-execution/orca-executor.md) — 실링이 정의 필터로 걸리는 자리
