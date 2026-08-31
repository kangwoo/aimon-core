# 도구 검색 (Tool Search)

> Status: **IMPLEMENTED (어셈블리 opt-in)** — 검색 인프라·검색 도구·실행기 통합이 모두 들어가 있다.
> 다만 **인트리 코드는 아무도 이 기능을 켜지 않는다** — 어떤 모듈의 main 소스도 `ToolSearchCatalog` 를
> 만들지 않고, `SearchableTool.deferred(...)` 로 등록되는 도구도 0개다. 켜는 방법은 어셈블리가
> `AgentRuntime` 의 `ToolRegistry` 자리에 `DefaultToolRegistry` 대신 `ToolSearchCatalog` 를 넣는 것이며,
> 실행기는 그 타입을 감지했을 때만 검색 경로로 갈아탄다. 넣지 않으면 동작은 이전과 완전히 동일하다.
>
> 적용 대상: `aimon-core` — `at.aimon.core.agent.tool.search` (카탈로그·전략·활성화 상태),
> `at.aimon.core.agent.tool.ToolLoadingMode`, `at.aimon.core.tools.search.ToolSearchTool`,
> `at.aimon.core.agent.impl.orca.OrcaAgentExecutor` 및 `at.aimon.core.subagent.execution.DefaultSubagentExecutor`
> 의 registry 생성 지점.

---

## 1. 문제

`OrcaAgentExecutor` 는 ReAct 루프에서 `ToolRegistry` 에 등록된 도구 전부의 `ToolDefinition` 을 LLM 에
넘긴다. 도구가 적을 때는 문제가 없지만 규모가 커지면 두 가지가 동시에 나빠진다.

| 증상 | 원인 | 체감 임계 |
|------|------|-----------|
| 컨텍스트 비대화 | 도구 1개당 definition 이 대략 0.5~1K 토큰을 먹는다 | 20개 안팎 |
| 선택 정확도 저하 | 후보가 많아질수록 LLM 이 엉뚱한 도구를 고른다 | 30~50개 안팎 |

해법은 **모든 도구를 항상 보여주지 않는 것**이다. 도구를 두 부류로 나눈다.

| 용어 | 뜻 |
|------|-----|
| **eager** | 처음부터 LLM 컨텍스트에 들어가는 도구. 언제나 호출 가능 |
| **deferred** | 처음에는 보이지 않고, 검색되어 **활성화**된 뒤에만 호출 가능한 도구 |
| **활성화(activation)** | deferred 도구가 검색되어 지금 실행 중인 단위에서 LLM 에 노출되는 상태로 바뀌는 것 |

Anthropic 의 [Tool Search Tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)
과 같은 접근이며, LLM 은 `ToolSearch` 라는 평범한 도구 하나로 나머지를 발견한다.

---

## 2. 세 개의 물체, 두 개의 수명

이 설계의 핵심은 **카탈로그와 활성화 상태를 분리한 것** 하나다. 카탈로그는 오래 살고 활성화 상태는 짧게
산다 — 둘을 한 물체에 두면 A 가 활성화한 도구가 B 에게도 보인다.

| 물체 | 하는 일 | 수명 | 스레드 안전 |
|------|--------|------|------------|
| `ToolSearchCatalog` | 도구 등록, eager/deferred 분류, 검색. `ToolRegistry` 구현 | **agent** — `AgentRuntime` 의 레지스트리 자리에 산다 | 필수 (`ConcurrentHashMap`) |
| `ToolActivationState` | 무엇이 활성화되었는지 (`Set<String>`) | **실행 단위** — 턴 1회 또는 포크 1회 | 불필요 (`HashSet`) |
| `ToolSearchRegistry` | 둘을 합쳐 `ToolRegistry` 로 보여 주는 **읽기 전용 뷰** | **실행 단위** | 자체 상태 없음 |

```
AgentRuntime (agent-scoped)
  └── ToolRegistry 자리 = ToolSearchCatalog
        ├── eager    : Read, Write, Bash, ToolSearch …
        └── deferred : WebFetch, Grep, ScheduleTask …
              ▲
              │ createRegistry()  — 실행 단위마다 새로
       ┌──────┴───────────────────────────┐
       ▼                                  ▼
  턴 1 (사용자 입력 A)              포크 (서브에이전트)
   ToolSearchRegistry                ToolSearchRegistry
   + ToolActivationState             + ToolActivationState
     {WebFetch}                        {Grep, ScheduleTask}
```

### 활성화는 턴을 넘지 못한다

IMPORTANT: `createSessionRegistry(...)` 라는 이름과 "per-session registry" 라는 주석은 **오칭**이다.
호출 지점은 `OrcaAgentExecutor.executeReActLoop(...)` 안이고(`OrcaAgentExecutor.java:1412`), 이 메서드는
**턴 1회**마다 실행된다. `DefaultSubagentExecutor.java:340` 도 마찬가지로 **포크 1회**마다다. 따라서
활성화 상태의 실제 수명은 세션이 아니라 **실행 단위**다.

같은 세션의 다음 턴에서 LLM 은 `ToolSearch` 를 **다시 호출해야 한다**. 이것은 버그가 아니라 현재 동작이며,
세션 단위로 유지하고 싶다면 활성화 상태를 `SessionRecord` 쪽으로 옮기는 별도 변경이 필요하다
(수명 배치 규칙은 [`scope-model.md`](../../overview/scope-model.md) §5.1). 이름·주석·`ToolActivationState`
javadoc 세 군데가 아직 "session" 이라고 말하고 있으므로 읽을 때 주의한다.

포크가 부모의 활성화 상태를 물려받지 않는 것도 같은 이유다 — 포크는 자기 registry 를 새로 만든다.

---

## 3. 검색 계층

### 3.1 쿼리 세 모드

`ToolSearchQueryParser.parse(String)` 가 접두어만 보고 모드를 정한다.

| 모드 | 문법 | 동작 |
|------|------|------|
| `KEYWORD` | `read file` | 키워드 관련도 순 상위 N개 |
| `SELECT` | `select:Read,Edit` | 이름 정확 일치로 즉시 선택 (검색 없음) |
| `REQUIRED` | `+slack send` | 이름에 `slack` 을 **반드시** 포함하는 것만 남기고 `send` 로 정렬 |

`ParsedQuery` 는 모드에 따라 `getKeywords()` / `getToolNames()` / `getRequiredKeyword()` +
`getRankingKeywords()` 중 해당하는 것만 채운다.

### 3.2 전략 인터페이스

```java
public interface ToolSearchStrategy {
    List<SearchableTool> search(String query, List<SearchableTool> candidates, int maxResults);
}
```

교체 가능한 것은 **랭킹**뿐이다 — 후보를 무엇으로 좁힐지(deferred 만)와 활성화 여부는 전략이 아니라
카탈로그와 registry 가 정한다.

기본 구현 `KeywordToolSearchStrategy` 의 점수는 세 필드의 가중합이다.

| 대상 | 가중치 |
|------|--------|
| 도구 이름 | 0.5 |
| 도구 설명 | 0.35 |
| 파라미터 이름·설명 (`inputSchema.properties`) | 0.15 |

`score > 0` 인 것만 내림차순으로 남긴다. 정규화는 소문자화 + `_`/`-` 를 공백과 동일 취급이다.
`inputSchema` 가 없거나 `properties` 가 `Map` 이 아니면 파라미터 점수는 0으로 두고 넘어간다(방어적 파싱).

`RequiredKeywordToolSearchStrategy` 는 이름 부분 문자열로 먼저 거른 뒤 같은 가중치로 정렬한다. 필수
키워드가 비면 전체 deferred 가 후보가 되고, 랭킹 키워드가 비면 이름순으로 돌려준다.

### 3.3 카탈로그의 조회 연산

`search` / `select` / `searchByRequired` 는 **순수 함수**다 — 활성화를 건드리지 않는다. 활성화는
registry 만 한다(§4.2).

| 메서드 | 후보 | 비고 |
|--------|------|------|
| `search(query, maxResults)` | deferred 만 | 기본 전략 |
| `select(names)` | deferred 만 | 이름 정확 일치. **eager 는 걸러진다** — 이미 보이므로 활성화할 것이 없다 |
| `searchByRequired(required, ranking, max)` | deferred 만 | 필터 + 랭킹 |
| `findAll()` | **eager 만** | LLM 에 노출할 목록. 카탈로그를 그대로 `ToolRegistry` 로 써도 안전한 이유 |
| `findByName(name)` | **전부** | 실행 시점 조회 — 활성화된 deferred 도구를 실행하려면 필요하다 |

`findByName` 과 `select` 는 이름을 `normalizeName` 으로 한 번 통과시킨다. 생성자 플래그
`stripFunctionsPrefix` 가 켜져 있으면 `"functions."` 접두어를 떼는데, 일부 LLM 이 도구 이름을
`functions.Read` 형태로 되돌려 주기 때문이다. 기본값은 꺼짐이고, 켜는 것은 2-인자 생성자다.

---

## 4. LLM 이 보는 것

### 4.1 ToolSearchTool

`at.aimon.core.tools.search.ToolSearchTool`, 이름은 `ToolSearch`, 카테고리는 `ToolCategories.SEARCH`.
`AbstractTool` 을 상속한 **평범한 도구**이므로 실행·권한·훅 경로를 그대로 탄다.

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `query` | string | O | 검색어 또는 `select:` / `+` 쿼리 |
| `max_results` | number | — | 기본 5, `[1, 20]` 으로 **clamp** (거부가 아니라 조정) |

스키마에는 `additionalProperties: false` 가 선언되어 있다(내장 도구 규칙).

응답은 검색된 도구의 **전체 function schema** 를 담은 JSON 문자열이다.

```json
{"functions": [{"name": "...", "description": "...", "parameters": { ... }}]}
```

즉 **검색이 곧 로드다(Search-is-Load)**. 별도 `activate` 단계가 없고, 검색 결과를 받은 LLM 은 다음
iteration 에서 그 도구를 바로 호출할 수 있다.

### 4.2 활성화가 일어나는 유일한 지점

```java
final List<SearchableTool> results = registry.searchAndActivate(query, maxResults);
```

`ToolSearchRegistry.searchAndActivate` 가 쿼리를 파싱하고 → 카탈로그의 해당 조회 연산을 부르고 →
결과를 `ToolActivationState.activate` 로 표시한다. 활성화는 멱등이다 — 같은 도구를 두 번 검색해도
`Set` 에 다시 넣을 뿐 상태가 달라지지 않는다.

### 4.3 에러와 빈 결과

"찾지 못함" 은 에러가 아니다.

| 상황 | 결과 |
|------|------|
| 매칭 없음 / `select:` 대상이 전부 없음 | `{"functions": []}` — 정상 |
| `select:` 중 일부만 존재 | 존재하는 것만 반환 — 정상 |
| 이미 활성화된 도구 재검색 | 정상 반환 (멱등) |
| `query` 누락·공백 | `ToolResult.error("Query cannot be empty")` |
| 컨텍스트에 registry 없음 | `ToolResult.error("ToolSearchRegistry not available in tool context")` |

마지막 줄은 **카탈로그를 쓰지 않는 어셈블리에서 `ToolSearchTool` 만 등록했을 때** 나온다. 검색 도구는
카탈로그와 짝이다.

---

## 5. 실행기 통합

세 줄이면 전부다.

**(1) 실행 단위 시작 시 registry 생성** — 카탈로그가 아니면 원본을 그대로 쓴다.

```java
private static ToolRegistry createSessionRegistry(ToolRegistry toolRegistry) {
    if (toolRegistry instanceof ToolSearchCatalog catalog) {
        return catalog.createRegistry();
    }
    return toolRegistry;
}
```

**(2) 도구 컨텍스트에 registry 주입** — `ToolSearchTool` 이 그것을 꺼내 쓴다.

```java
if (sessionRegistry instanceof ToolSearchRegistry searchRegistry) {
    builder.put(ToolContextKeys.TOOL_SEARCH_REGISTRY, searchRegistry);
}
```

키는 `at.aimon.core.tools.ToolContextKeys.TOOL_SEARCH_REGISTRY` (`ToolContextKey<ToolSearchRegistry>`)
이며, 카탈로그가 아닐 때는 아예 넣지 않는다.

**(3) iteration 마다 목록 재조회** — `availableTools` 를 루프 밖이 아니라 루프 **안**에서 만든다
(`OrcaAgentExecutor.java:1551`, `DefaultSubagentExecutor.java:409`). 이번 iteration 에서 활성화된 도구가
다음 iteration 의 LLM 요청에 들어가려면 이것이 필요하다. `findAll()` 은 in-memory 순회이므로 비용은
무시할 수 있다.

`instanceof` 두 번이 곧 하위 호환성이다 — `DefaultToolRegistry` 를 쓰는 기존 어셈블리는 registry 도
컨텍스트 키도 만들어지지 않으므로 동작이 한 톨도 바뀌지 않는다.

---

## 6. 설계 결정

### 6.1 카탈로그(agent)와 활성화 상태(실행 단위)를 분리한다

| 대안 | 결과 |
|------|------|
| 카탈로그 + 실행 단위별 상태 | **채택** — 격리되고, 도구 인스턴스는 공유된다 |
| 활성화 상태를 카탈로그에 둔다 | 기각 — 한 턴이 활성화한 도구가 다른 턴에 새고, 동시 실행 시 경쟁 |
| 실행 단위마다 레지스트리 전체 복사 | 기각 — 도구 인스턴스까지 복제되고, 이후의 등록 변경이 반영되지 않는다 |

### 6.2 `ToolSearchRegistry` 는 `ToolRegistry` 를 구현한다

`OrcaAgentExecutor` · `ToolExecutionManager` 가 이미 `ToolRegistry` 에 의존하므로, 인터페이스를 맞추면
통합 비용이 `instanceof` 한 줄로 끝난다.

`ToolRegistry` 에 `search()` 를 추가하는 안은 기각했다 — `DefaultToolRegistry` 를 포함한 모든 구현체가
쓰지도 않을 메서드를 갖게 되어 ISP 위반이다.

### 6.3 뷰는 쓰기를 거부한다

`register` / `unregister` / `clear` 는 `UnsupportedOperationException` 을 던진다. 실행 단위별 뷰가 공유
카탈로그를 고치게 두면 그 변경이 다른 턴에 새기 때문이다. 등록은 카탈로그로만 한다.

LSP 관점에서 이것은 `Collections.unmodifiableList()` 가 `add()` 에서 던지는 것과 같은 자리다 — no-op 으로
삼키는 안은 호출자가 성공으로 오해하므로 더 나쁘다.

### 6.4 검색 도구는 카탈로그를 생성자로 받지 않는다

`ToolSearchTool` 은 카탈로그와 함께 agent 수명으로 등록되지만 판정 대상인 registry 는 실행 단위마다
다르다. 생성자에 묶으면 격리가 깨지므로 **`ToolContext` 로 매번 받는다**. 이것이 §4.3 의 "registry 없음"
에러가 존재하는 이유이기도 하다.

### 6.5 검색과 동시에 활성화한다 (Search-is-Load)

검색했다는 것 자체가 사용 의도다. 별도 `activate` 호출을 요구하면 LLM 왕복이 한 번 더 늘고 얻는 것은
"검색만 하고 안 쓸 자유" 뿐이다.

### 6.6 카탈로그만 스레드 안전하게 만든다

카탈로그는 agent-scoped 이므로 여러 턴이 동시에 읽는다 → `ConcurrentHashMap`(읽기 lock-free, 순회는
weakly consistent). 반면 `ToolActivationState` 는 실행 단위 안에서만 만져지고 ReAct 루프는 동기적이므로
`HashSet` 으로 충분하다 — javadoc 이 "스레드 안전하지 않다" 고 명시한다.

병렬 도구 실행이 켜져 있어도 이 판단은 유지된다. 활성화를 건드리는 것은 `ToolSearchTool` 하나이고, 그
도구는 `ConcurrencyBehavior` 를 override 하지 않으므로 기본값 `SEQUENTIAL` 이다
([병렬 실행](parallel-execution.md)).

### 6.7 `ToolSearch` 자신은 반드시 eager 다

검색 도구가 deferred 면 그것을 검색할 방법이 없다. Anthropic 문서도 같은 말을 한다 — *"The tool search
tool itself should never have `defer_loading: true`."*

---

## 7. 보안

활성화는 **노출**이지 **권한**이 아니다. 활성화된 도구도 실행 시점에 기존 `AllowedTool` 검증을 그대로
통과해야 하며, 검색으로 얻는 것은 definition 을 볼 수 있는 자격뿐이다. 따라서 권한 모델에 새로 뚫린
구멍은 없다.

| 위협 | 대응 |
|------|------|
| 과도한 검색으로 리소스 소모 | `max_results` 를 `[1, 20]` 으로 clamp |
| 권한 없는 도구 활성화 | 실행 시점 `AllowedTool` 검증이 그대로 적용 |
| 검색 도구 자체의 접근 제어 | `AllowedTool.of("ToolSearch")` 로 통제 |

---

## 8. 남은 것

- **인트리 배선 없음** — 켜려면 어셈블리가 카탈로그를 만들어 `AgentRuntime` 에 주입하고, 어떤 도구를
  deferred 로 등록할지 골라야 한다. 인트리에서 카탈로그를 인식하는 유일한 코드는
  `OrcaKnowledgeToolProvider.java:40` 인데, 그것도 `SearchableTool.eager(...)` 로 **eager 등록**할 뿐이다
- **활성화 상태의 수명** — 지금은 실행 단위다(§2). 세션 단위로 올리려면 `SessionRecord` 쪽에 두고
  `ToolActivationState` 를 인터페이스로 뽑아야 한다
- **이름 정리** — `createSessionRegistry` / "per-session registry" / `ToolActivationState` javadoc 의
  "session" 세 군데는 실제 수명(턴·포크)과 어긋난다
- **랭킹 고도화** — BM25, inverted index, 임베딩 기반 시맨틱 검색은 `ToolSearchStrategy` 교체로 들어갈
  자리가 이미 비어 있다

### 의도적으로 제외

- **자동 비활성화(TTL)** — 활성화 상태가 실행 단위와 함께 사라지므로 만료 개념이 필요 없다
- **provider 전용 tool_reference 프로토콜** — Anthropic 의 `tool_search_tool_result` 같은 것은 LLM
  provider 모듈의 일이다. 코어는 `ToolSearch` 를 평범한 도구로 유지한다

---

## 부록: 참조 파일 지도

| 파일 | 확인할 것 |
|------|----------|
| `agent/tool/ToolLoadingMode.java` | `EAGER` / `DEFERRED` |
| `agent/tool/search/SearchableTool.java` | `eager()` / `deferred()` 팩토리, 불변 래퍼 |
| `agent/tool/search/ToolSearchStrategy.java:34` | `search(query, candidates, maxResults)` |
| `agent/tool/search/KeywordToolSearchStrategy.java` | 가중치 0.5 / 0.35 / 0.15, `score > 0` 필터 |
| `agent/tool/search/RequiredKeywordToolSearchStrategy.java` | 이름 필터 → 랭킹 |
| `agent/tool/search/ToolSearchQueryParser.java` | `select:` / `+` 접두어, `QueryMode` 3값 |
| `agent/tool/search/ToolSearchCatalog.java:121` | `findAll()` 이 eager 만 돌려준다 |
| `agent/tool/search/ToolSearchCatalog.java:223` | `select` 가 deferred 만 남긴다 |
| `agent/tool/search/ToolSearchCatalog.java:251` | `createRegistry()` = catalog + 새 활성화 상태 |
| `agent/tool/search/ToolSearchCatalog.java:255` | `stripFunctionsPrefix` 정규화 |
| `agent/tool/search/ToolActivationState.java:21` | `HashSet` — 스레드 안전하지 않다 |
| `agent/tool/search/ToolSearchRegistry.java:57` | 쓰기 연산이 던진다 |
| `agent/tool/search/ToolSearchRegistry.java:170` | `searchAndActivate` — 활성화의 유일한 지점 |
| `tools/search/ToolSearchTool.java:93` | `max_results` clamp `[1, 20]` |
| `tools/search/ToolSearchTool.java:119` | `{"functions": [...]}` 응답 포맷 |
| `tools/ToolContextKeys.java:195` | `TOOL_SEARCH_REGISTRY` 키 |
| `agent/impl/orca/OrcaAgentExecutor.java:900` | `createSessionRegistry` — 오칭 |
| `agent/impl/orca/OrcaAgentExecutor.java:1412` | 턴마다 registry 생성 |
| `agent/impl/orca/OrcaAgentExecutor.java:1551` | iteration 마다 `availableTools` 재조회 |
| `subagent/execution/DefaultSubagentExecutor.java:340` | 포크마다 registry 생성 |
| `agent/impl/orca/tool/OrcaKnowledgeToolProvider.java:40` | 인트리 유일의 카탈로그 인식 — eager 등록 |

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.
테스트는 `modules/aimon-core/src/test/java/at/aimon/core/agent/tool/search/` 에 10개 파일로 모여 있다
(`ToolSearchConcurrencyTest` 가 카탈로그 동시 접근과 registry 독립 생성을 고정한다).

---

## 관련 문서

- [도구 계약 강화](contract-hardening.md) — 스키마 게이트, `additionalProperties`
- [도구 병렬 실행](parallel-execution.md) — `ConcurrencyBehavior` 와 이 문서 §6.6
- [Tool 개발 가이드](../../features/tool/tool-development-guide.md)
- [스코프 모델](../../overview/scope-model.md) — 활성화 상태를 어디에 둘지의 기준
- [AgentExecutor 인터셉터 체인](../agent-execution/interceptor.md)
- [Anthropic Tool Search Tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)
