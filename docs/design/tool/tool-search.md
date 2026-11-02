# 도구 검색 (Tool Search)

> **Status**: IMPLEMENTED — `aimon-core`. `ToolSearchCatalog` 를 레지스트리로 쓰는 런타임에서만 켜진다. 팩토리와 스택에는 이 자리를 여는 이음매가 없다([TC-9](../../backlog/tool-contract-open-items.md)).

켜는 법과 에이전트가 쓰는 법은 [도구 검색 가이드](../../features/tool/tool-search-guide.md) 에 있다.

---

## 1. 문제

실행기는 ReAct 루프에서 도구 정의를 LLM 에 넘긴다. 도구 수에 비례해 definition 이 커지고, 후보가 많을수록 선택이
흐려진다. 그래서 모든 도구를 항상 보여 주지 않는다.

| 용어 | 뜻 |
|------|-----|
| **eager** | 처음부터 LLM 에 제안되는 도구 |
| **deferred** | 처음에는 제안되지 않고, 검색되어 활성화되면 제안 목록에 드는 도구 |
| **활성화(activation)** | deferred 도구가 검색되어 지금 execution 에서 LLM 에 제안되는 상태로 바뀌는 것 |

LLM 은 `ToolSearch` 라는 평범한 도구 하나로 나머지를 발견한다.

---

## 2. 세 개의 물체, 두 개의 수명

카탈로그는 오래 살고 활성화 상태는 짧게 산다.

| 물체 | 하는 일 | 수명 | 스레드 안전 |
|------|--------|------|------------|
| `ToolSearchCatalog` | 도구 등록, eager/deferred 분류, 검색. `ToolRegistry` 구현 | **agent** — `AgentRuntime` 의 레지스트리 자리에 산다 | 필수 (`ConcurrentHashMap`) |
| `ToolActivationState` | 활성화된 도구 이름 (`Set<String>`) | **execution** — 턴 1회 또는 포크 1회 | 불필요 (`HashSet`) |
| `ToolSearchRegistry` | 둘을 합쳐 `ToolRegistry` 로 보여 주는 읽기 전용 뷰 | **execution** | 자체 상태 없음 |

```text
AgentRuntime (agent-scoped)
  └── ToolRegistry 자리 = ToolSearchCatalog
        ├── eager    : Read, Write, Bash, ToolSearch ...
        └── deferred : WebFetch, Grep, ScheduleTask ...
              ▲
              │ createRegistry()  — execution 마다 새로
       ┌──────┴───────────────────────────┐
       ▼                                  ▼
  턴 1 (사용자 입력 A)              포크 (서브에이전트)
   ToolSearchRegistry                ToolSearchRegistry
   + ToolActivationState             + ToolActivationState
     {WebFetch}                        {Grep, ScheduleTask}
```

### 2.1 활성화는 execution 을 넘지 못한다

레지스트리는 턴 1회, 포크 1회마다 새로 만들어진다. 수명은 execution 이다. 코드의 `createSessionRegistry` 와
"per-session" 주석은 오칭이다([TC-11](../../backlog/tool-contract-open-items.md)).

같은 세션의 다음 턴에서 LLM 은 `ToolSearch` 를 다시 호출해야 한다. 포크는 부모의 활성화 상태를 물려받지 않는다.
세션 단위 유지는 [TC-10](../../backlog/tool-contract-open-items.md) 이고, 값을 어디에 둘지의 기준은
[스코프 모델](../../overview/scope-model.md) 이다.

---

## 3. 검색 계층

### 3.1 쿼리 세 모드

`ToolSearchQueryParser.parse(String)` 가 접두어만 보고 모드를 정한다.

| 모드 | 문법 | 동작 |
|------|------|------|
| `KEYWORD` | `read file` | 키워드 관련도 순 상위 N개 |
| `SELECT` | `select:WebFetch,Grep` | 이름 정확 일치로 선택. 검색하지 않는다 |
| `REQUIRED` | `+slack send` | 이름에 `slack` 을 포함하는 것만 남기고 `send` 로 정렬 |

### 3.2 전략 인터페이스

```java
public interface ToolSearchStrategy {
    List<SearchableTool> search(String query, List<SearchableTool> candidates, int maxResults);
}
```

교체할 수 있는 것은 **랭킹**뿐이다. 후보를 deferred 로 좁히는 일은 카탈로그가, 활성화는 레지스트리가 한다. 기본
구현의 가중치와 정규화는 `KeywordToolSearchStrategy` 에 있고, 더 나은 랭킹은
[TC-12](../../backlog/tool-contract-open-items.md) 다.

### 3.3 카탈로그의 조회 연산

`search` / `select` / `searchByRequired` 는 활성화를 건드리지 않는다.

| 메서드 | 후보 | 비고 |
|--------|------|------|
| `search(query, maxResults)` | deferred 만 | 기본 전략 |
| `select(names)` | deferred 만 | 이름 정확 일치. eager 는 걸러진다. 이미 제안되고 있어 활성화할 것이 없다 |
| `searchByRequired(required, ranking, max)` | deferred 만 | 이름 필터 + 랭킹 |
| `findAll()` | eager 만 | LLM 에 제안할 목록. 카탈로그를 그대로 `ToolRegistry` 로 읽어도 deferred 가 새지 않는다 |
| `findByName(name)` | 전부 | 실행 시점 조회 |

`findByName` 과 `select` 는 이름을 `normalizeName` 으로 통과시킨다. 일부 LLM 이 도구 이름을 `functions.Read`
형태로 되돌려 주므로 `functions.` 접두어를 뗀다. 기본으로 켜져 있고, 2-인자 생성자의 `stripFunctionsPrefix` 로
끈다.

---

## 4. LLM 이 보는 것

### 4.1 ToolSearchTool

이름은 `ToolSearch`, 카테고리는 `ToolCategories.SEARCH`. `AbstractTool` 을 상속한 평범한 도구이므로 실행·권한·훅
경로를 그대로 탄다.

| 파라미터 | 타입 | 필수 | 설명 |
|----------|------|------|------|
| `query` | string | O | 검색어 또는 `select:` / `+` 쿼리 |
| `max_results` | number | — | 기본 5. `[1, 20]` 으로 clamp 한다. 거부하지 않는다 |

스키마는 `additionalProperties: false` 다. 응답은 검색된 도구의 전체 function schema 를 담은 JSON 문자열이다.

```json
{"functions": [{"name": "...", "description": "...", "parameters": { }}]}
```

검색이 곧 로드다(Search-is-Load). 결과를 받은 LLM 은 다음 iteration 에서 그 도구를 호출한다.

### 4.2 활성화가 일어나는 유일한 지점

`ToolSearchRegistry.searchAndActivate(query, maxResults)` 가 쿼리를 파싱하고, 카탈로그의 조회 연산을 부르고, 결과를
`ToolActivationState` 에 표시한다. 활성화는 멱등이다. 같은 도구를 다시 검색해도 상태가 달라지지 않는다.

### 4.3 에러와 빈 결과

"찾지 못함" 은 에러가 아니다.

| 상황 | 결과 |
|------|------|
| 매칭 없음, `select:` 대상이 전부 없음 | `{"functions": []}` |
| `select:` 중 일부만 존재 | 존재하는 것만 반환 |
| 이미 활성화된 도구 재검색 | 정상 반환 |
| `query` 가 공백 | `ToolResult.error("Query cannot be empty")` |
| 컨텍스트에 레지스트리 없음 | `ToolResult.error("ToolSearchRegistry not available in tool context")` |

마지막 줄은 카탈로그를 쓰지 않는 런타임에 `ToolSearchTool` 만 등록했을 때 나온다. 검색 도구는 카탈로그와 짝이다.

---

## 5. 실행기 통합

`OrcaAgentExecutor` 와 `DefaultSubagentExecutor` 가 같은 일을 한다.

1. execution 시작 시 레지스트리가 `ToolSearchCatalog` 이면 `createRegistry()` 로 뷰를 만들고, 아니면 원본을 그대로
   쓴다.
2. 뷰이면 도구 컨텍스트의 `ToolContextKeys.TOOL_SEARCH_REGISTRY` 에 넣는다. 아니면 키를 넣지 않는다.
3. 제안할 도구 정의를 iteration 마다 다시 만든다. 이번 iteration 에 활성화된 도구가 다음 LLM 요청에 들어가야 하기
   때문이다.

실행기는 레지스트리가 `ToolSearchCatalog` 일 때만 검색 경로로 간다. 아니면 활성화 상태도 컨텍스트 키도 만들지 않는다.

---

## 6. 설계 결정 표

| 결정 | 이유 | 기각한 대안 |
|------|------|------------|
| 카탈로그(agent)와 활성화 상태(execution)를 나눈다 | execution 끼리 격리되고 도구 인스턴스는 공유된다 | 활성화 상태를 카탈로그에 둔다: 한 execution 이 활성화한 도구가 다른 execution 에 새고 동시 실행에서 경쟁한다. execution 마다 레지스트리를 복사한다: 도구 인스턴스까지 복제되고 이후의 등록 변경이 반영되지 않는다 |
| `ToolSearchRegistry` 는 `ToolRegistry` 를 구현한다 | 실행기와 `ToolExecutionManager` 가 이미 `ToolRegistry` 에 의존하므로 통합 비용이 `instanceof` 하나다 | `ToolRegistry` 에 `search()` 추가: `DefaultToolRegistry` 를 포함한 모든 구현체가 쓰지 않을 메서드를 갖는다 (ISP 위반) |
| 뷰는 쓰기를 거부한다 (`UnsupportedOperationException`) | execution 단위 뷰가 공유 카탈로그를 고치면 그 변경이 다른 execution 에 샌다. `Collections.unmodifiableList()` 의 `add()` 와 같은 자리다 | no-op 으로 삼킨다: 호출자가 성공으로 오해한다 |
| 검색 도구는 레지스트리를 `ToolContext` 로 받는다 | `ToolSearchTool` 은 agent 수명으로 등록되지만 대상 레지스트리는 execution 마다 다르다. §4.3 의 "레지스트리 없음" 에러가 이 결정의 결과다 | 생성자에 묶는다: 격리가 깨진다 |
| 검색과 동시에 활성화한다 | 검색했다는 것 자체가 사용 의도다 | 별도 `activate` 호출: LLM 왕복이 한 번 늘고 얻는 것은 "검색만 하고 안 쓸 자유" 뿐이다 |
| 카탈로그만 스레드 안전하게 만든다 | 카탈로그는 여러 execution 이 동시에 읽는다. 활성화 상태는 한 execution 안에서만 쓰이고, 그것을 변조하는 `ToolSearchTool` 이 `SEQUENTIAL` 이다. 공유 가변 값의 규칙은 [병렬 실행](parallel-execution.md) 에 있다 | — |
| `ToolSearch` 를 eager 로 등록하는 것은 조립의 책임이다 | deferred 로 두면 그것을 검색할 방법이 없다. 코드는 막지 않는다. 카탈로그에서 거부하는 변경은 [TC-13](../../backlog/tool-contract-open-items.md) 이다 | — |

---

## 7. 보안

활성화는 **노출**이지 **권한**이 아니다. 검색으로 얻는 것은 definition 뿐이다.

제안과 실행은 활성화와 무관하게 판정한다.

- 제안 목록은 활성화된 도구도 부작용 실링과 허용 목록으로 거른다. 검색 결과 자체는 거르지 않으므로, 허용되지 않은
  도구의 definition 도 응답에 실릴 수 있다. 그 도구는 제안되지 않고 실행도 거부된다.
- 실행은 `findByName` 으로 카탈로그 전체에서 찾고 활성화 여부를 보지 않는다. 활성화되지 않은 deferred 도구도 LLM 이
  이름을 부르고 허용 목록과 실링을 통과하면 실행된다. deferred 는 접근 제어가 아니다.

| 위협 | 대응 |
|------|------|
| 과도한 검색으로 리소스 소모 | `max_results` 를 `[1, 20]` 으로 clamp |
| 권한 없는 도구 사용 | 실행 시점 허용 목록과 실링 검사 |
| 검색 도구 자체의 접근 제어 | `AllowedTool.of("ToolSearch")`. `ToolSearch` 도 허용 목록의 적용을 받는다 |

`ToolSearchTool` 은 부작용 선언을 override 하지 않아 `MUTATING` 이다. `READ_ONLY` 실링 아래에서는 제안되지 않으므로
그 실행에서 deferred 도구는 발견되지 않는다.

---

## 8. 의도적으로 제외

- **자동 비활성화(TTL)** — 활성화 상태가 execution 과 함께 사라지므로 만료 개념이 필요 없다.
- **provider 전용 프로토콜** — [Anthropic Tool Search Tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)
  과 접근은 같지만 `tool_search_tool_result` 같은 프로토콜은 LLM provider 모듈의 일이다. 코어는 `ToolSearch` 를
  평범한 도구로 유지한다.

---

## 9. 하지 말 것

- `ToolSearchTool` 을 `SearchableTool.deferred(...)` 로 등록하지 않는다. 통과하지만 deferred 도구에 닿을 길이 사라지고 실패는 조용하다.
- deferred 도구를 쓰는 실행의 허용 목록에서 `ToolSearch` 를 빠뜨리지 않는다.
- 활성화 여부를 권한 판정에 쓰지 않는다. 권한은 허용 목록과 실링이 정한다.
- `ToolSearchRegistry` 로 도구를 등록하지 않는다. 등록은 카탈로그로만 한다.
- `ToolSearchTool` 에 카탈로그나 레지스트리를 생성자로 주지 않는다.
- `ToolSearchStrategy` 에서 후보를 좁히거나 활성화하지 않는다. 전략은 랭킹만 정한다.
- `ToolSearchTool` 을 `CONCURRENT_SAFE` 로 올리지 않는다. 활성화 상태는 `HashSet` 이다.
- 활성화 상태를 세션 수명 값으로 읽지 않는다.

---

## 10. 참조 파일 지도

경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준이다.

| 파일 | 확인할 것 |
|------|----------|
| `agent/tool/ToolLoadingMode.java` | `EAGER` / `DEFERRED` |
| `agent/tool/search/SearchableTool.java` | `eager()` / `deferred()` 팩토리 |
| `agent/tool/search/ToolSearchStrategy.java` | 전략 인터페이스 |
| `agent/tool/search/KeywordToolSearchStrategy.java` | 가중치, 정규화, `score > 0` 필터 |
| `agent/tool/search/RequiredKeywordToolSearchStrategy.java` | 이름 필터 뒤 랭킹 |
| `agent/tool/search/ToolSearchQueryParser.java` | `select:` / `+` 접두어 |
| `agent/tool/search/ToolSearchCatalog.java` | 조회 연산, `createRegistry()`, `stripFunctionsPrefix` |
| `agent/tool/search/ToolActivationState.java` | 활성화 상태 |
| `agent/tool/search/ToolSearchRegistry.java` | 쓰기 거부, `searchAndActivate` |
| `tools/search/ToolSearchTool.java` | 입력, `max_results` clamp, 응답 형식 |
| `tools/ToolContextKeys.java` | `TOOL_SEARCH_REGISTRY` |
| `agent/impl/orca/OrcaAgentExecutor.java` | execution 마다 뷰 생성, iteration 마다 제안 목록, 빈 제안 경고 |
| `subagent/execution/DefaultSubagentExecutor.java` | 포크의 같은 경로 |
| `agent/impl/orca/OrcaAgentRuntimeFactory.java` | 레지스트리를 직접 만드는 지점 |
| `agent/impl/orca/tool/OrcaKnowledgeToolProvider.java` | 카탈로그를 인식하는 provider. eager 로 등록한다 |

---

## 11. 관련 문서

- [도구 검색 가이드](../../features/tool/tool-search-guide.md)
- [도구 계약](tool-contract.md) — 스키마 관문, 권한 판정
- [병렬 실행](parallel-execution.md) — `ConcurrencyBehavior`, 공유 가변 값
- [부작용 축](side-effect-axes.md) — 실링
- [Tool 개발 가이드](../../features/tool/tool-development-guide.md)
- [스코프 모델](../../overview/scope-model.md)
- [AgentExecutor 인터셉터 체인](../agent-execution/interceptor.md)
- [등록부](../../backlog/tool-contract-open-items.md) — TC-9 ~ TC-13
