# 도구 검색 가이드

도구가 많은 에이전트에서 일부 도구의 정의를 처음부터 LLM 에 보내지 않고, 에이전트가 `ToolSearch` 로 찾을 때 제안 목록에
넣는 기능의 사용법이다.

왜 이렇게 만들었는지는 설계 문서 [`tool-search.md`](../../design/tool/tool-search.md) 에 있다.

---

## 1. 언제 쓰는가

| 상황 | 쓸 것 |
|------|------|
| 도구가 적고 대부분의 실행에서 쓰인다 | 기본 레지스트리 (이 기능이 필요 없다) |
| 도구 정의가 프롬프트를 크게 차지한다 | 도구 검색 |
| 후보가 많아 모델이 엉뚱한 도구를 고른다 | 도구 검색 |

자주 쓰는 도구는 eager 로 두고, 가끔 쓰는 도구만 deferred 로 둔다. deferred 도구를 쓰려면 모델이 `ToolSearch` 호출과 LLM
왕복을 한 번 더 써야 한다.

---

## 2. 켜기

**런타임을 손으로 조립할 때만 켤 수 있다.** `OrcaAgentRuntimeFactory` 는 레지스트리를 직접 만들고 받는 자리가 없다.
`AimonStack`, CLI, Spring Boot 에도 설정이 없다. 이 이음매는 등록부의
[TC-9](../../backlog/tool-contract-open-items.md) 다.

```java
ToolSearchCatalog catalog = new ToolSearchCatalog(new KeywordToolSearchStrategy());

// ToolSearch 는 반드시 eager 로 등록한다
catalog.register(SearchableTool.eager(new ToolSearchTool()));

// 자주 쓰는 도구
catalog.register(SearchableTool.eager(readTool));

// 찾아야 쓸 수 있는 도구
catalog.register(SearchableTool.deferred(ticketTool));

OrcaAgentRuntime runtime = OrcaAgentRuntime.builder()
        .agent(agent)
        .toolRegistry(catalog)
        // 나머지 필드는 조립이 직접 채운다
        .build();
```

조립이 직접 해야 하는 일이 둘 있다.

- **provider 등록.** 팩토리가 `OrcaToolProvider` 로 하던 도구 등록을 조립이 해야 한다. provider 가 `register(Tool)` 로
  등록한 도구는 eager 가 된다.
- **`ToolSearchTool` 등록.** 카탈로그가 자동으로 넣지 않는다.

`ToolSearchTool` 을 deferred 로 등록해도 코드는 막지 않는다. 그러면 deferred 도구에 닿을 길이 사라지고, 오류 없이 모델이
그 도구들을 모르는 채로 답한다.

실행기는 레지스트리가 `ToolSearchCatalog` 일 때만 검색 경로로 간다. 실행기 쪽에 따로 설정할 것은 없다.

---

## 3. 에이전트가 쓰는 법

`ToolSearch` 의 입력은 둘이다.

| 파라미터 | 필수 | 설명 |
|----------|------|------|
| `query` | O | 검색어 |
| `max_results` | — | 기본 5. 1 ~ 20 으로 잘린다 |

`query` 의 모양이 모드를 정한다.

| 모드 | 예 | 동작 |
|------|-----|------|
| 키워드 | `list directory` | 이름·설명·파라미터의 관련도 순 |
| 직접 선택 | `select:WebFetch,Grep` | 이름 정확 일치 |
| 필수 키워드 | `+slack send` | 이름에 `slack` 이 든 것만 남기고 `send` 로 정렬 |

응답은 찾은 도구의 전체 스키마다.

```json
{"functions": [{"name": "WebFetch", "description": "...", "parameters": { }}]}
```

찾은 도구는 다음 iteration 부터 제안 목록에 들어 있다. 별도의 활성화 호출은 없다. 찾지 못해도 에러가 아니고
`{"functions": []}` 가 돌아온다. eager 도구는 검색 대상이 아니므로 `select:` 에 적어도 결과에 나오지 않는다.

---

## 4. 활성화 수명

활성화는 **턴 1회 또는 포크 1회**만 유지된다.

- 같은 세션의 다음 턴에서 모델은 `ToolSearch` 를 다시 호출해야 한다
- 포크는 부모가 활성화한 도구를 물려받지 않는다

코드의 `createSessionRegistry` 라는 이름과 "per-session" 주석은 실제 수명과 다르다. 이름을 믿지 않는다.

---

## 5. 권한

`ToolSearch` 도 허용 목록의 적용을 받는다. 허용 목록이 있는 에이전트와 서브에이전트에서는 목록에 `ToolSearch` 가 있어야
deferred 도구를 찾을 수 있다.

```text
allowed-tools: Read, ToolSearch, WebFetch
```

활성화는 권한이 아니다.

- 찾은 도구도 허용 목록에 없으면 제안되지 않고 실행이 거부된다. 다만 `ToolSearch` 의 응답에는 그 도구의 스키마가 실릴 수 있다
- 활성화되지 않은 deferred 도구도 모델이 이름을 부르고 허용 목록을 통과하면 실행된다. deferred 를 접근 제어로 쓰지 않는다

`ToolSearchTool` 은 부작용 선언을 override 하지 않아 `MUTATING` + `DESTRUCTIVE` 다.

- `READ_ONLY` 실링 아래에서는 `ToolSearch` 가 제안되지 않으므로 deferred 도구를 찾을 수 없다
- `SideEffectApprovalGate` 를 배선하면 `ToolSearch` 호출마다 승인을 묻는다

---

## 6. 문제 해결

| 증상 | 원인 | 해결 |
|------|------|------|
| `ToolSearchRegistry not available in tool context` | 런타임의 레지스트리가 `ToolSearchCatalog` 가 아닌데 `ToolSearchTool` 을 등록했다 | `toolRegistry(catalog)` 로 조립하거나 `ToolSearchTool` 을 등록하지 않는다 |
| `Query cannot be empty` | `query` 가 공백이다 | 검색어를 준다 |
| 로그에 `is offered no tools` 경고가 있고 에이전트가 도구 없이 답한다 | 허용 목록과 부작용 실링을 거치니 제안할 도구가 없다. 오타, 실링 위의 도구만 적은 목록, deferred 배포에서 `ToolSearch` 를 뺀 목록이 그렇다 | 허용 목록을 고친다. 실행은 성공으로 기록되므로 경고를 본다 |
| 모델이 deferred 도구를 쓰지 않는다 | `ToolSearch` 가 제안되지 않는다. deferred 로 등록했거나, 허용 목록에 없거나, `READ_ONLY` 실링 아래다 | §2, §5 를 확인한다 |
| 다음 턴에 도구가 사라진다 | 활성화 수명이 턴 1회다 | 정상이다. §4 |
| `select:` 결과가 비었다 | 이름이 틀렸거나 eager 도구다 | 정상일 수 있다. §3 |
