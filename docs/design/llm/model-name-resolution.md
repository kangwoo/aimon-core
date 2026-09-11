# 모델 이름 해석 (Model Name Resolution)

> Status: **IMPLEMENTED** — 요청이 싣는 모델 이름의 해석 순서(요청 · 서브에이전트 · memory), 클라이언트 기본 모델과
> 벤더만 말하는 `getProviderName()`, CLI 배너 · 번들 줄 · `Task` 도구 설명, 모델을 적지 않는 번들 `explore`, provider 를
> 바꿨을 때 다른 벤더의 모델 이름을 말하는 기동 검사가 들어가 있다. 남은 것은 §10.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm`(`LlmClient.getProviderName` · `getDefaultModelName`, 관측 데코레이터),
> `at.aimon.core.subagent`(`SubagentLlmDefaults.resolveModel`, `SubagentBehaviorSupport.resolvedModel`),
> `at.aimon.core.tools.task`(`TaskTool` 의 `model` 설명) · `aimon-llm-openai` / `aimon-llm-anthropic` — `OpenAIConfig` ·
> `AnthropicConfig` 의 모델 · `aimon-cli` — `AgentSetupFactory`(`memoryModelName`, `reportAgentModelMismatch`),
> `AgentModelProviderCheck`, `ReplSession` 배너, 출하 번들과 `default-config.yaml` · `aimon-spring-boot-starter` —
> `AimonLlmAutoConfiguration` 의 모델 요구.

---

## 1. 문제와 범위

LLM 클라이언트는 설정의 `llm:` 블록 하나로 만들어지지만, 요청이 싣는 모델 이름은 여러 자리에서 정해진다 — 에이전트
정의의 `model.name`, 서브에이전트의 `model`, `Task` 도구가 넘기는 호출별 override, memory 가 쓰는 `llm.model`, 그리고
어느 것도 없을 때의 클라이언트 기본 모델. 이 자리들이 서로 모르면 두 가지가 어긋난다.

- **보내는 이름.** 설정된 provider 가 제공하지 않는 이름이 나가고, 첫 요청이 404 로 실패한다.
- **보이는 이름.** 사용자가 보는 이름이 실제로 나가는 이름과 다르다.

이 문서는 네 가지를 정한다: 요청의 모델 이름이 어느 순서로 정해지는가, 아무것도 적지 않은 부품은 무엇으로 도는가,
CLI 는 그 이름을 어떻게 보여 주는가, provider 를 바꿨는데 정의가 다른 벤더의 모델을 적고 있으면 기동 시 무엇을
말하는가.

**하지 않는 것.**

- **별칭 해석.** AIMON 은 어디서도 모델 별칭을 풀지 않는다(§2.1).
- **모델이 무엇을 받는가.** 요청 모양을 모델별로 바꾸는 사실과, 그 지식을 모델 이름 스니핑이 아니라 능력 레지스트리로만
  얻는다는 원칙은 [`model-capabilities.md`](model-capabilities.md) 가 정본이다.
- **이름 밖의 요청 값.** 서브에이전트가 받는 temperature · max tokens 기본값은 [`request-parameters.md`](request-parameters.md)
  가 정한다.
- **키의 표기와 바인딩.** `llm.model` · `aimon.llm.model` 이 어느 표면에 어떻게 바인딩되는지는
  [`configuration-surface.md`](configuration-surface.md) 가 정한다.
- **provider 가 이름을 제공하는지 런타임에 판정하는 것.** 기동 검사는 벤더 계열만 본다(§7).
- **`LlmClient` SPI 전체.** 정본은 [LLM Provider 개발 가이드](../../features/llm/llm-provider-development-guide.md) 다.

---

## 2. 원칙 — 모델을 적지 않은 부품은 클라이언트 기본 모델로 돈다

**모델 이름을 적지 않은 부품은 클라이언트가 이름 없는 요청에 보낼 모델, 곧 클라이언트 기본 모델로 돈다. 프레임워크는
이름을 지어내지 않고, 설정 표면이 선택이라고 한 키 때문에 기동을 멈추지 않는다.**

이름 없는 `LlmModel` 은 새로 만든 상태가 아니라 모든 경로가 이미 다루는 상태다. 그래서 이름을 비워 두는 것이 가장 작은
답이다.

| 자리 | 이름이 없을 때 |
|---|---|
| 두 출하 클라이언트 | `modelConfig.getName().orElse(config.getModel())` — 요청 시점에 클라이언트 기본 모델을 채운다 |
| 컴팩션 가드(`DefaultCompactionGuard`) | 기본 한도로 떨어진다 |
| 비용 추정(`TablePricedCostEstimator`) | 0 으로 답하고 모델 이름마다 WARN 을 한 번 남긴다 |
| 관측 데코레이터 셋(`LoggingLlmClient` · `MeteringLlmClient` · `TracingLlmClient`) | `getName().or(delegate::getDefaultModelName)` 로 실효 모델을 풀고, 마지막 폴백은 각자 둔다 |

`model` 블록이 없는 에이전트 정의는 이미 이 방식으로 돈다. 이 위에서 리터럴 폴백은 벤더 추측일 뿐이고, 이름을 해석
시점에 채우면 데코레이터나 라우터가 요청마다 정할 여지도 사라진다.

### 2.1 쓰인 그대로 보낸다 — 별칭은 풀지 않는다

정의의 `model.name`, 서브에이전트의 `model`, `Task` 도구의 호출별 override, `llm.model` 은 모두 **설정된 provider 로
쓰인 그대로** 간다. 코어의 어느 자리도 별칭을 모델 id 로 바꾸지 않는다.

별칭을 풀려면 provider 마다 별칭 표가 코어에 있어야 한다. 능력 레지스트리는 모델 지식이 provider 에 닿는 유일한
이음매이고 일부러 벤더를 싣지 않으므로, 그 표는 둘 자리가 없다. 같은 별칭이 provider 마다 다른 모델을 뜻하게 되는 것도
문제다. 그래서 별칭은 다른 모든 이름처럼 그대로 나가고, 제공되지 않으면 벤더의 오류로 실패한다(§7.1 의 측정).

같은 이유로 호출별 override 는 **"쓰인 그대로 보내는 모델 이름"** 이라 부르고 "별칭" 이라 부르지 않는다. 해석된
서브에이전트 모델의 이름은 비어 있을 수 있다(§3.2).

### 2.2 `getProviderName()` 은 벤더, 모델은 `getDefaultModelName()`

`LlmClient.getProviderName()` 은 **벤더만** 돌려준다(`"OpenAI"`, `"Anthropic"`). 모델은 형제 접근자
`default Optional<String> getDefaultModelName()` 이 말하고, 두 출하 클라이언트는 `Optional.of(config.getModel())` 로
답한다.

**provider 이름에 요청 모델을 반영하지 않는다.** `getProviderName()` 은 인자가 없고, 요청을 쥐지 않은 자리(REPL 배너,
실행기의 `toString()`)에서도 불린다. 요청 모델을 반영하려면 한 메서드 이름에 두 번째 뜻을 주는 오버로드를 모든 구현자에게
강제하거나, thread-safe 로 문서화된 클라이언트에 요청별 상태를 두어야 한다. 클라이언트 기본 모델을 provider 이름에
넣으면, 에이전트별 모델 선택이 override 를 정상 경우로 만든 뒤에는 거의 모든 요청에 틀린 모델을 말한다.

**접근자 모양의 이유.**

- **`default` 메서드다.** 기존 provider · 데코레이터 · 테스트 대역을 고치지 않아도 되고, 기본 모델 개념이 없는
  클라이언트(라우터, 기록된 fixture)는 빈 값으로 올바르게 답한다.
- **데코레이터는 위임에 전달해야 한다.** 전달하지 않으면 그 바깥의 모든 관측 지점이 빈 모델을 본다. 데코레이터 다섯
  (`LoggingLlmClient` · `MeteringLlmClient` · `TracingLlmClient` · `TaggingLlmClient` · `BoundMetadataLlmClient`)이 전달하고,
  전달 테스트가 그것을 붙든다.
- **관측 지점은 요청의 이름을 먼저 본다.** 관측은 provider 와 model 을 별도 필드로 두므로, 요청 이름이 없을 때 클라이언트
  기본 모델로 채우지 않으면 override 하지 않은 모든 요청의 model 필드가 빈다.

사용자에게 보이는 `LLM Provider: <벤더> (<모델>)` 줄은 `ReplSession` 이 두 값을 다시 조합해 만든다(§5.1).

---

## 3. 해석 순서 — 요청 · 서브에이전트 · memory

### 3.1 요청 — 정의의 이름이 `llm.model` 을 이긴다

요청이 싣는 이름은 `LlmModel.getName()` 이 있으면 그것, 없으면 클라이언트 기본 모델이다. CLI 에서 메인 에이전트의
`LlmModel` 은 에이전트 정의의 `model` 블록이므로, **요청의 모델 이름은 `llm:` 블록이 아니라 에이전트 정의가 정한다.**
정의의 `model.name` 은 선택이다 — `model` 블록이 없는 정의는 이름 없는 모델로 파싱된다.

그래서 `llm.model`(클라이언트 기본 모델)이 닿는 곳은 정해져 있다.

- memory 부품(§3.3)
- 위키 페이지 생성 — 이름 없는 `LlmModel` 을 받는다
- `model.name` 이 없는 에이전트 정의, 그리고 그 아래에서 자기 모델을 적지 않은 서브에이전트

자기 `model.name` 을 적은 에이전트와 자기 `model` 을 적은 서브에이전트는 `llm.model` 로 돌지 않는다.

### 3.2 서브에이전트 — override, 서브에이전트, 메인 에이전트, 클라이언트 기본

`SubagentLlmDefaults.resolveModel` 이 서브에이전트의 모델을 푼다. 이름은 다음 순서의 첫 값이다.

1. 호출별 override(`Task` 도구의 `model` 인자) — null 이나 공백이 아닐 때
2. 서브에이전트 정의의 `model`
3. 기본 모델의 이름
4. 없으면 **이름 없음** — 클라이언트가 요청 시점에 자기 기본 모델을 보낸다

기본 모델은 `Task` 도구 · 워크플로 · 스킬 포크가 생성자로 받은 `agent.getMetadata().getModel()` 이므로, **CLI 경로에서는
늘 메인 에이전트의 모델**이다. 따라서 모델을 적지 않은 서브에이전트는 메인 에이전트가 도는 모델로 돌고, 메인 에이전트도
이름이 없으면 클라이언트 기본 모델로 돈다 — CLI 에서는 `llm.model` 이 있으면 그것, anthropic 에서 없으면
`AnthropicConfig` 의 내장 기본 모델이다(§4).

- **temperature 와 max tokens 는 기본 모델에서 합친다.** override 되는 것은 이름뿐이다. 둘 다 없을 때 쓰는 명시값은
  [`request-parameters.md`](request-parameters.md) 가 정한다.
- **해석된 이름은 비어 있을 수 있다.** `SubagentBehaviorSupport.resolvedModel()` 은 null 이 아니지만 이름은 비어 있을 수
  있다. 합치기 전의 기본 모델 접근자(`getDefaultModel()`)는 override 도 서브에이전트의 `model` 도 반영하지 않는다.
- **리터럴 폴백이 없는 이유.** 코어에는 "이 키를 적으라" 고 이름할 설정 키가 없고, `model.name` 없는 정의는 유효하다 —
  그 메인 에이전트는 이미 클라이언트 기본 모델로 돈다. 서브에이전트만 다른 답을 받을 이유가 없다.

용어를 구분한다: **메인 에이전트의 모델**은 해석에 넘기는 기본 `LlmModel` 이고, **클라이언트 기본 모델**은
`getDefaultModelName()` 이다.

### 3.3 memory — `llm.model`, 없으면 클라이언트 기본 모델을 알리고 쓴다

CLI 는 memory 모델 이름을 `AgentSetupFactory.memoryModelName` 에서 **한 번** 푼다.

| 조건 | 결과 |
|---|---|
| memory 가 꺼져 있다 | `null`. 아무것도 읽지 않고 출력도 없다 |
| `llm.model` 이 공백이 아니다 | 그 이름 |
| `llm.model` 이 없거나 공백이고, 클라이언트 기본 모델이 공백이 아니다 | 그 이름. 모델과 키를 이름하는 한 줄을 터미널(`displayInfo`)과 로그 파일(`log.warn`)에 남긴다 |
| 클라이언트 기본 모델도 없다 | `llm.model` 을 이름하는 `ConfigurationException` |

그 이름 하나가 memory 부품 전부로 간다 — dialectic engine, deriver 와 reconciler, dreamer 와 그 LLM judge(judge 는
`memory.dreamer.scorer.llm.model` 이 있으면 그것을 쓴다). 이 이름은 memory 재료를 만들 때 넘어가고, 그 재료가
`MemorySpec` 으로 스택에 들어간다([`../memory/pluggable-memory-backend.md`](../memory/pluggable-memory-backend.md)).

**거절하지 않고 알린다.** anthropic 에서 memory 가 켜질 때 `llm.model` 을 요구하면, 조립 경로가 선택으로 둔 키를 조건부
필수로 좁히게 된다. 위키 생성과 `model.name` 없는 메인 에이전트가 이미 클라이언트 기본 모델로 돌므로 memory 만 예외가 될
이유가 없다. 사용자가 적지 않은 모델로 memory 호출이 나간다는 사실은 기동 줄이 말한다. 서브에이전트(§3.2)와 memory 가
같은 답을 받는 것도 이 때문이다.

---

## 4. 클라이언트 기본 모델 — OpenAI 는 없고, Anthropic 은 측정된 이름이다

| 설정 | 기본 모델 | 조립 경로 |
|---|---|---|
| `OpenAIConfig` | **없다.** `build()` 가 모델 없는 설정을 거절한다 | CLI `LlmClientFactory` 는 yaml `model` 을, 스타터는 `aimon.llm.model` 을 이름하며 먼저 요구한다 |
| `AnthropicConfig` | `claude-sonnet-4-5` | 두 조립 경로 모두 키가 있을 때만 모델을 복사한다 — anthropic 에서 키는 선택이다 |

**`OpenAIConfig` 는 모델을 요구한다.**

- `build()` 는 API 키를 먼저 검사한 뒤 모델을 검사한다. 둘 다 없는 설정이 API 키를 먼저 보고하는 순서에 호출자가 기댄다.
- 조립 경로가 `build()` 보다 먼저 자기 키 이름으로 요구한다. `build()` 의 메시지는 자바 필드를 가리키지만 운영자가 고칠
  것은 설정 키이기 때문이다.

**`AnthropicConfig` 의 내장 기본 모델은 `claude-sonnet-4-5` 다.** 이유는 넷이다.

- **제공이 측정된 이름이다**(아래 결론 표). 제공되는 이름 중 가장 작은 이동이다.
- **내장 능력 표가 이미 기술하는 이름이다.** 기본값이 표의 행으로 해석되므로 요청 모양이 기본값에서도 fail-open 추측이
  아니다([`model-capabilities.md`](model-capabilities.md)). `AnthropicConfigTest` 가 기본값이 기술된 행으로 해석됨을
  단언하므로, 기본값을 다시 바꾸는 변경은 표를 만난다.
- **`default-anthropic` 번들의 메인 모델과 같다.** 제공 여부를 지켜야 할 이름이 하나로 준다.
- **이름 밖의 요청을 바꾸지 않는다.** 날짜 스냅샷 이름은 같은 모델의 두 번째 표기를 만들고, 측정하지 않은 새 계열은
  이름 밖에서 요청 모양(thinking · temperature)을 바꾼다.

| 무엇 | 결과 | 측정 날짜 |
|---|---|---|
| Anthropic 모델 API · Messages API 에 `claude-sonnet-4-20250514` | 둘 다 404 `not_found_error` | 2026-09-11 |
| Anthropic 모델 API 에 `claude-sonnet-4-5` | 200 — 날짜 스냅샷으로 해석되고, 모델 정보가 budgeted thinking 만 지원한다고 답한다 | 2026-09-11 |
| Anthropic Messages API 에 `claude-sonnet-4-5` | 200 — 제공됨 | 2026-09-11 |

**측정 범위.** 제공 여부는 Anthropic Messages API 에서만 쟀다. 모델을 적지 않은 채 `baseUrl` 로 게이트웨이를 가리키는
배포는 그 게이트웨이에 `claude-sonnet-4-5` 를 보내며, 게이트웨이가 그 이름을 받는지는 측정하지 않았다.

**기본 모델이 제공되지 않게 되면** 그 모델로 도는 요청은 요청 시점에 벤더의 오류로 실패한다. memory 가 켜져 있으면 기동
줄이 이미 그 모델과 키를 이름해 두었다.

**두 설정의 비대칭은 의도다.** Anthropic 쪽 기본값을 없애면 배포 모듈에서 `AnthropicConfig.builder().apiKey(k).build()` 가
깨지고, anthropic 에서 `llm.model` · `aimon.llm.model` 이 필수가 되어 §2 의 원칙을 뒤집는다.

---

## 5. CLI 표시 — 보이는 이름이 보내는 이름과 같게

### 5.1 배너의 모델은 메인 에이전트의 요청이 싣는 모델이다

배너의 provider 줄은 `LLM Provider: <getProviderName()> (<모델>)` 이다(`ReplSession.providerLine`). 괄호의 모델은 정의의
`model.name`, 없으면 클라이언트 기본 모델이다 — 두 클라이언트가 요청마다 적용하는 `getName().orElse(...)` 와 같은 식이다.
결과가 비거나 공백이면 괄호를 뺀다. 에이전트가 없는 설정(테스트가 조립한 것)에서는 클라이언트 기본 모델을 보인다.

서브에이전트가 따로 적은 모델은 보여 주지 않는다. 한 줄에 담을 수 없다.

### 5.2 번들 줄과 프롬프트 — 번들 이름은 줄이 말하고, 정의의 이름은 바꾸지 않는다

배너는 `Working Directory:` 와 `LLM Provider:` 사이에 설정의 `agent.name` 을 찍는다(`ReplSession.agentBundleLine`).

```text
Agent bundle: default-anthropic (agent name: default-agent)
```

번들 이름과 정의의 이름이 같으면 괄호를 뺀다. 번들 이름이 없는 `AgentSetup` 에서는 줄이 없다.

**출하 번들 셋(`default` · `default-openai` · `default-anthropic`)은 정의의 `name`(`default-agent`)을 함께 쓰고, 이
이름을 바꾸지 않는다.** 그 이름은 프롬프트와 `AgentRuntimeId`(`agent:default-agent`), 그리고 사용자 hook · 스킬이 읽는
`AIMON_AGENT_RUNTIME_ID` 의 출처인 영속 정체성이기 때문이다. 어느 번들이 떴는지는 배너의 `Agent bundle:` 줄이 말한다.

**REPL 프롬프트는 정의의 이름(`default-agent> `)을 쓴다.**

- 프롬프트가 런타임 id 와 일치한다. 배너 괄호의 `(agent name: …)` 가 그 짝을 설명한다.
- 어느 번들이 떴는지는 테스트가 고정한 배너 줄이 답한다. 설정의 `agent.name` 으로 만든 프롬프트는 같은 답의 두 번째,
  고정되지 않은 사본이다.
- 출하 설정에서는 `default> ` 가 되어 오히려 덜 말한다.
- 다른 프롬프트를 원하는 사용자에게는 `cli.prompt` 가 있다.

대가는 긴 세션에서 배너가 스크롤로 사라지면 화면에 남은 프롬프트가 번들을 이름하지 않는다는 것이다.

### 5.3 번들 `explore` 는 모델을 적지 않는다

`default-anthropic` · `default-openai` · `ops-agent` 의 `explore` 서브에이전트는 `model` 을 적지 않고, 그 자리에 한 줄짜리
YAML 주석으로 이유를 남긴다. 주석은 누군가 별칭을 다시 적지 않게 하려고 둔다. 각 `explore` 는 메인 에이전트의 모델,
곧 그 번들의 provider 가 제공하는 이름을 물려받는다. `default` 번들의 `explore` 는 자기 모델을 적는다 — 그 provider 가
제공하는 이름이다.

- **`agent.name` 이 번들 전체의 스위치가 된다.** provider 를 바꿀 때 번들 하나만 고르면 메인 에이전트와 서브에이전트가
  함께 바뀐다.
- **주석 모양은 복사해도 안전하다.** 파서는 frontmatter 의 그 줄을 주석으로 다루고, 사용자 `.aimon/agents` 파일도 같은
  서브에이전트 파서를 지난다.
- **대가.** `explore` 는 "더 싸고 빠른 모델" 이라는 의도를 잃고 메인 모델로 돈다. 더 싼 `explore` 가 필요한 사용자는
  `.aimon/agents/explore.md` 에 전체 모델 id 를 적고, 기동 검사(§7)가 그 파일을 본다.

### 5.4 `Task` 도구의 `model` 설명은 모델을 권하지 않고 계약을 말한다

`TaskTool` 의 `model` 파라미터 설명(`MODEL_DESCRIPTION`)은 모델 이름을 하나도 적지 않고 다음 사실만 말한다.

- 선택이며, 이번 서브에이전트 실행에만 적용된다
- 설정된 provider 로 쓰인 그대로 가고, 별칭은 풀리지 않는다
- 서브에이전트의 모델을 이긴다
- 특정 모델 id 를 받지 않았으면 비워 둔다 — 그러면 서브에이전트의 모델, 서브에이전트가 적지 않았으면 메인 에이전트의
  모델로 돈다

`TaskTool` 에는 provider 가 없다(`LlmModel` · 레지스트리 · 실행 매니저로 만들어진다). provider 별 모델을 권하려면 코어에
벤더 귀속을 두어야 하고, 매 LLM 호출이 읽는 런타임 문자열 속 모델 목록은 아무도 보지 않는 곳에서 낡는다.

---

## 6. 문서 예시 규칙 — 요청에 닿는 값에 모델 이름을 쓰지 않는다

**복사해 쓰는 예시(가이드 · javadoc · README · 번들)에서 요청에 닿는 값에는 모델 id 도 placeholder 도 쓰지 않는다.** 모델
줄 자리에는 주석을 두어 세 가지를 말한다 — 적지 않으면 메인 에이전트의 모델로 돈다, 다른 모델이 필요할 때만 설정된
provider 가 제공하는 id 를 적는다, 그 id 는 쓰인 그대로 간다.

- **벤더 id 를 쓰지 않는 이유.** provider 중립 예시에 벤더 id 를 쓰면 다른 provider 에서 첫 요청이 실패한다. 그 불일치를
  말하는 기동 검사는 CLI 에만 있어서 애플리케이션 임베딩은 아무 신호도 받지 못한다. id 는 은퇴하면 조용히 낡고, 은퇴를
  404 전에 잡는 문서 검사는 없다.
- **placeholder 를 쓰지 않는 이유.** 복사된 placeholder 는 그대로 보내져 별칭처럼 실패한다. placeholder 는 요청에 절대
  닿지 않는 값(예: skill-creator 의 `benchmark.json` 샘플이 기록하는 `executor_model`)에만 쓴다.
- **기본 설정과 가이드는 벤더 모델 리터럴 대신 "Anthropic 클라이언트의 기본 모델" 이라고 쓴다.** 그 값은
  `aimon-llm-anthropic` 의 `AnthropicConfig` 에 속하고, 다른 곳에 복사하면 기본값이 바뀔 때 조용히 낡는다. memory 가 켜지면
  기동 줄이 실제 이름을 말한다.

규칙의 대상은 **복사되는 설정 · 코드 조각**이다. 설계 문서의 설정 모양 조각도 모델 이름 줄을 뺀다. 측정 결과를 사실로
인용하는 것(§4 · §7.1 의 결론 표, [`model-capabilities.md`](model-capabilities.md) 의 행 근거)은 규칙 대상이 아니다.

아직 이 규칙을 따르지 않는 자리는 백로그 L-27 이 추적한다(§10).

---

## 7. provider 전환 기동 검사 — 말하되 거부하지 않는다

요청이 싣는 모델 이름은 `llm:` 블록이 아니라 에이전트 정의가 정한다(§3.1). 그래서 `llm.provider` 만 바꾸면 정의가 적은
다른 벤더의 모델 이름이 새 provider 로 가서 첫 요청이 실패한다. **CLI 기동 검사는 그 요청 전에 이것을 말한다.**

검사는 `aimon-cli` 에만 있다. `AgentModelProviderCheck` 는 상태가 없는 package-private 클래스이고, 호출 자리는
`AgentSetupFactory.reportAgentModelMismatch` 하나다. 스택 조립이 끝난 뒤, 배너 전에 프로세스 시작마다 한 번 돈다. 검사는
아무것도 소유하지 않는다.

### 7.1 두 관문 — 엔드포인트와 모델 계열

모델 하나가 경고 항목이 되려면 **두 관문을 모두** 통과해야 한다.

**관문 A — 요청이 다른 벤더의 이름을 제공할 수 없는 API 로 간다.** 다음 중 하나일 때 통과한다.

1. `llm.baseUrl` 이 없다 — 두 클라이언트가 읽는 대로 null 이나 빈 값
2. 호스트가 설정된 provider 자신의 공개 API 호스트다 — `openai` 는 `api.openai.com`, `anthropic` 은 `api.anthropic.com`
3. `llm.provider` 가 `anthropic` 인데 호스트가 `api.openai.com` 이다 — 이 조합에는 정당한 해석이 없다. OpenAI 호스트의
   어떤 것도 Anthropic Messages API 를 말하지 않는다. 출하 설정에서 `provider:` 만 고치면 정확히 이 조합이 된다

비교는 대소문자 · scheme · port · path 를 무시한다. 그 밖의 모든 `baseUrl` 에서 검사는 침묵한다 — 게이트웨이나 프록시,
Azure(`*.openai.azure.com`), 파싱되지 않는 값, 그리고 `provider: openai` 에 `api.anthropic.com` 호스트.

**3 의 거울 조합이 침묵하는 이유.** `provider: openai` 가 `api.anthropic.com` 을 가리키는 설정은 동작할 수 있다. Anthropic
이 그 호스트에서 OpenAI SDK 호환 엔드포인트를 제공하기 때문이다. 이것은 트리에 적히지 않은 벤더 지식이고, 그 지식에
기대는 쪽의 선택은 침묵이다. 틀렸다면 비용은 놓친 경고이지 거짓 경고가 아니다.

**호스트 리터럴은 SDK 기본값이다.** 두 벤더 설정 클래스 모두 기본 base URL 상수를 노출하지 않는다.

**관문 B — 모델 이름이 *다른* 벤더의 계열이다.** 계열은 내장 능력 표가 행을 이름하는 벤더 접두어다
([`model-capabilities.md`](model-capabilities.md)).

| 벤더 | 계열 접두어 |
|---|---|
| Anthropic | `claude-` |
| OpenAI | `gpt-`, `o1`, `o3`, `o4` |

어느 계열도 차지하지 않는 이름(맨 별칭, 게이트웨이의 자체 배포 이름, 다른 모델 계열)은 침묵한다. 비교는 레지스트리처럼
대소문자를 접는다.

- **"자기 계열 밖" 이 아니라 "다른 벤더의 계열" 인 이유.** 벤더 자신의 API 가 다른 벤더의 계열을 제공하지 않는다는 것은
  확실하다. 반면 "이 벤더가 제공하는 것" 은 계열이 아니다 — 벤더는 다른 접두어의 모델도 제공하고, 어느 쪽이든 내일 새
  계열을 낼 수 있다.
- **표의 행이 아니라 접두어인 이유.** 레지스트리에는 벤더가 없고, 행 조회는 행이 없는 계열 이름(`gpt-4o`, `gpt-4.1`)을
  놓친다. 설정에 선언한 게이트웨이 이름은 인식된 것처럼 보이게 된다.
- **넓은 접두어가 거짓 경보를 내지 않는 이유.** 관문 A 가 모든 계열 검사를 그 계열을 제공할 수 없는 API 로 제한한다 —
  설정된 벤더 자신의 API(1 · 2)이거나 이 provider 에 아예 답할 수 없는 호스트(3)다. 그래서 거짓 경보는 관문 A 에서만 올
  수 있고, 관문 A 가 보수적인 절반이다.
- **관문 A 가 있는 이유.** 트리는 OpenAI 호환 게이트웨이 뒤에서 `provider: openai` 로 `claude-*` 모델을 쓰는 것을 정당한
  경로로 적는다. 이름만 보는 규칙은 그 배포가 기동할 때마다 경고한다.

| 무엇 | 결과 | 측정 날짜 |
|---|---|---|
| Anthropic Messages API 에 다른 벤더의 모델 이름(`gpt-5.6-terra`) | 404 `not_found_error` | 2026-09-10 |
| 같은 API 에 맨 별칭(`haiku`) | 404 `not_found_error` | 2026-09-10 |

이 측정값은 경고 문구에 넣지 않는다(§8).

**수용한 거짓 음성.** 두 조합은 침묵하고 첫 요청에서 실패한다. 실패 이유가 모델 검사로는 이름할 수 없는 것이기 때문이다.

- `provider: anthropic` + `api.openai.com` + `claude-*` 에이전트
- `provider: openai` + `api.anthropic.com` + OpenAI 계열 에이전트

### 7.2 검사 대상과 파일 출처 — 인스턴스 동일성 하나로 정한다

**항목.**

- **메인 에이전트** — 정의의 `model.name` 이 있고 공백이 아닐 때. 키는 `model.name`. 메인 에이전트는 늘 불러온 번들에서
  오므로 위치는 classpath 파일 `agents/<agent.name>/agent.md` 다.
- **런타임이 띄울 서브에이전트 중 자기 모델을 적은 것** — 런타임 합성 레지스트리가 이름마다 고른 승자 중 `model` 이
  공백이 아닌 것. 키는 `model`.

**서브에이전트 항목의 출처는 인스턴스 동일성 하나로 정한다.**

| 출처 | 조건 | 찍히는 위치(§7.3) |
|---|---|---|
| `BUNDLE` | 번들 레지스트리의 같은 이름 정의가 런타임 승자와 **같은 인스턴스**다 | classpath `agents/<agent.name>/agents/<이름>.md` |
| `OUTSIDE_BUNDLE` | 그 밖의 모든 경우 — 번들에 그 이름이 없거나, 있지만 다른 인스턴스다. **모델이 같아도** 여기다 | `<작업 디렉토리>/.aimon/agents/<이름>.md` |

**동일성만으로 모든 도달 가능한 경우가 갈리는 이유.** 런타임은 서브에이전트 레지스트리를 세 층으로 쌓는다.

- **번들 층** — 번들의 레지스트리 객체 그대로. 번들 승자는 구성상 같은 인스턴스다.
- **사용자 층** — `.aimon/agents` 위의 별도 `DefaultSubagentRegistry` 가 자기 인스턴스를 파싱한다. 그 아래 파일은 번들
  모델을 그대로 둔 사본이라도 번들 인스턴스가 아니다.
- **코드 층** — CLI 에서는 비어 있다.

그래서 같지 않은 승자는 사용자 파일일 수밖에 없고, 찍히는 위치가 그 파일을 이름한다. 모델 문자열로 판정하지 않는
이유는 프롬프트만 바꾸려고 번들 파일을 복사한 사용자 사본을 번들로 표시해, 런타임이 읽지 않는 파일을 고치라고 권하게
되기 때문이다.

**동일성이 견디지 못하는 것과 그것을 지키는 것.** 사본을 돌려주는 레지스트리 클래스, 또는 번들 레지스트리를 감싸거나
다시 파싱한 뒤 층에 넣는 배선은 모든 번들 서브에이전트를 `OUTSIDE_BUNDLE` 로 만들고 존재하지 않는 `.aimon/agents` 경로에
찍는다. 메시지에는 그 경우를 위한 완충이 없다. 실제 스택 조립(`AimonStackBuilder`)을 거쳐 번들 서브에이전트가 `BUNDLE`
로 판정되는지 확인하는 테스트가 사용자보다 먼저 그것을 잡는다. 놓쳐도 비용은 제한된다 — 줄의 모델과 키는 여전히 맞고
기동은 영향받지 않는다.

**서브에이전트도 검사하는 이유.** 번들 서브에이전트가 다른 벤더의 모델을 적으면 같은 클라이언트로 그 이름이 나가고,
실패가 작업 도중에 나타나 원인을 찾기 더 어렵다. **런타임 레지스트리를 보는 이유.** 사용자가 쓴 서브에이전트도 같은
실패를 갖고, 합성 레지스트리가 정확히 `Task` 도구가 보는 것이다.

**일부러 검사하지 않는 것.**

- 모델을 적지 않은 서브에이전트 — 메인 에이전트의 이름을 물려받고 그것은 이미 검사된다(§3.2)
- `model.name` 이 없는 정의 — 클라이언트 기본 모델을 보내며, 이 검사의 결함이 아니다
- `Task` 도구의 호출별 `model` 인자 — 모델이 호출마다 고르므로 기동 시점에 볼 수 없다
- 어느 계열도 차지하지 않는 이름(§7.1)

### 7.3 경고가 나오는 자리와 모양

**두 채널.** CLI 가 기동 조건을 알리는 기존 경로를 쓴다 — 터미널은 `OutputFormatter.displayInfo`, 로그 파일은
`log.warn`. 둘은 짝이어야 한다. CLI 의 `logback.xml` 은 루트 로거를 `WARN` 으로 두고 파일(`~/.aimon/logs/aimon.log`)에만
쓰므로, `log.warn` 만으로는 터미널에 아무것도 닿지 않는다. 경고는 모든 항목을 담은 메시지 하나로 프로세스 시작마다 한 번
나온다. 매 기동 반복은 의도다 — 줄마다 고칠 곳을 이름한다.

**모양.** 머리 한 줄, 항목마다 위치와 바꿀 키 한 줄, 그리고 적용되는 처방만.

```text
Agent model: `llm.provider` is `<provider>`, but these definitions name <Vendor> models, which <Provider>'s API
does not serve:
  - main agent, classpath `agents/<agent.name>/agent.md`: `model.name: <name>`
  - subagent `<sub>`, classpath `agents/<agent.name>/agents/<sub>.md`: `model: <name>`
  - subagent `<sub>`, `<working directory>/.aimon/agents/<sub>.md`: `model: <name>`
Each request carries these names; `llm.model` does not replace them. <remedies> Startup continues.
```

**위치 표기.**

- **번들 파일**(메인 에이전트, `BUNDLE` 서브에이전트)은 ``classpath `agents/<agent.name>/…` `` 로 찍는다. 번들 파일은 jar
  안이나 번들이 빌드되는 곳의 classpath 리소스이지 사용자가 선 자리의 상대 경로가 아니다. classpath 루트는 호출자가 쓰는
  번들 로더의 기준 경로를 인자로 받는다 — 로더가 읽는 루트를 찍고, 검사가 자기를 부르는 클래스로 되짚어 들어가지 않게
  하려는 것이다.
- **`OUTSIDE_BUNDLE` 항목**은 런타임 환경의 작업 디렉토리에 `StackPaths.AGENTS_DIRECTORY` 와 `<이름>.md` 를 붙인 절대
  경로로 찍는다. 작업 디렉토리가 null · 공백이거나 경로가 아니면 상대 경로 `.aimon/agents/<이름>.md` 로 찍는다 — 나쁜 경로
  문자열이 경고를 잃게 하지 않는다.
- **런타임 환경의 작업 디렉토리를 쓰는 이유.** 사용자 층이 그 기준으로 풀리고, 배너가 곧이어 `Working Directory:` 로 찍는
  값도 그것이다. CLI 의 작업 디렉토리는 셸의 현재 디렉토리가 아니라 jar 가 있는 디렉토리, 아니면 `user.dir` 다 — 그래서
  상대 경로를 따라간 jar 사용자는 아무도 읽지 않는 파일을 만든다.
- **이름은 설정의 `agent.name` 에서 온다.** 출하 번들 셋이 정의의 이름 `default-agent` 를 함께 쓰므로 정의의 이름으로는
  번들을 가리킬 수 없다(§5.2).

### 7.4 처방 규칙 — 무언가를 실제로 바꾸는 처방만 말한다

처방 문장은 각자의 조건에서만 나온다. `BUNDLE_FOR` 는 provider 마다 메인 모델이 그 벤더의 것인 출하 번들이다
(`anthropic` → `default-anthropic`, `openai` → `default`).

| 문장 | 조건 | 내용 |
|---|---|---|
| S-switch | 메인 에이전트가 항목이고, 설정된 `agent.name` 이 provider 의 번들과 다르다 | ``Set `agent.name: <번들>`: it replaces the main agent and the subagents in its bundle.`` |
| S-outside | S-switch 가 나오고, `OUTSIDE_BUNDLE` 항목이 있다 | ``Files under `<작업 디렉토리>/.aimon/agents` load with every agent, so `agent.name` does not change them: edit those files.`` |
| S-edit | S-switch 가 나오지 않는다 | `Change the key shown on each line.` |
| + S-built | S-edit 이고, 번들 파일 항목이 있다 | `A classpath file is part of the agent bundle: edit it where that bundle is built.` |
| + S-override | S-edit 이고, `BUNDLE` 서브에이전트 항목이 있다 | ``Or override a bundled subagent without rebuilding: a file of the same name in `<작업 디렉토리>/.aimon/agents` replaces it.`` |
| S-gateway | 관문 A 를 1 이나 2 로 통과했다 | ``Or point `llm.baseUrl` at a gateway that serves these names.`` |
| S-host | 관문 A 를 3 으로 통과했다 | `` `llm.baseUrl` is OpenAI's host, which does not serve Anthropic's API: remove it, or point it at a gateway that serves these names. `` |

**문장들이 지키는 규칙.**

- **런타임이 읽지 않는 파일을 이름하지 않는다.** 번들 항목은 런타임이 푼 바로 그 인스턴스이고(§7.2), 사용자 항목은
  런타임이 그것을 푼 디렉토리를 찍는다.
- **classpath 경로를 사용자 발밑의 파일처럼 보이지 않는다.** `classpath` 로 표시하고, S-built 가 어디서 고치는지, S-override
  가 jar 사용자에게 필요한 재빌드 없는 경로를 말한다.
- **이미 설정된 값을 이름하지 않는다.** S-switch 는 `agent.name` 이 그 번들과 다를 때만 나온다.
- **없는 키를 이름하지 않는다.** 메인 에이전트의 키는 `model.name`, 서브에이전트의 키는 `model` 이다.
- **처방이 바꾸는 것 이상을 약속하지 않는다.** S-switch 는 무엇을 대체하는지 말하고, 그것이 바꾸지 못하는 `.aimon/agents`
  는 S-outside 가 짚는다. "처방을 따르면 경고가 사라진다" 는 주장은 참인 범위로 좁힌다 — 이름된 번들로 바꾸면 S-switch 가
  덮는 항목이 모두 사라진다. `.aimon/agents` 항목은 그 주장 밖이다.
- **S-switch 는 메인 에이전트가 항목일 때만 나온다.** 번들 서브에이전트만 어긋나면 그 번들은 사용자의 선택이고 메인
  에이전트는 provider 에 맞다. 비례하는 처방은 S-edit 이다.
- **S-host 는 필수다.** 3 의 경우 에이전트만 고치면 경고는 사라지지만 모든 요청이 여전히 호스트에서 실패한다.

### 7.5 절대 거부하지 않는다

- **검사는 값을 계산하고, 호출 자리는 찍기만 한다.**
- **seam 은 협력자를 자기 `try` 안에서 읽는다.** 호출 자리는 번들, `Supplier<SubagentRegistry>`, 작업 디렉토리
  `Supplier<String>` 을 넘기고, 번들의 에이전트 · 두 `get()` · 모든 레지스트리 읽기가 `try` 안에서 일어난다. "기동을 멈추지
  않는다" 는 구성으로 성립한다.
- **`RuntimeException` 이면** 로그 파일에만 `Agent model check skipped: …` 를 남기고 터미널에는 아무것도 찍지 않으며 기동을
  계속한다.
- **이상한 입력은 침묵하거나 물러선다.** 파싱되지 않는 `baseUrl` 이나 예상 밖 provider 는 침묵, 경로가 아닌 작업 디렉토리는
  상대 위치다.
- **경고를 끄는 설정 키가 없다.** 검사가 발화하는 모든 설정은 이름을 제공할 수 없는 API 로 보낸다.

### 7.6 provider 를 바꿀 때 함께 바뀌는 키는 다섯이다

`provider`, `apiKey`, `baseUrl`, `model`, `agent.name`.

- `baseUrl` 을 남기면 요청이 이전 벤더의 호스트로 간다. 출하 설정은 OpenAI 호스트를 적는다.
- `model` 을 남기면 memory 처럼 `llm.model` 을 쓰는 부품(§3.1)이 이전 벤더의 모델 이름을 새 provider 로 보낸다. 기동 검사는
  에이전트 정의만 보므로 이것을 말하지 않는다.
- `agent.name` 은 번들을 고른다. 번들 `explore` 가 메인 모델을 물려받으므로(§5.3) provider 의 번들로 바꾸면 메인 에이전트와
  그 서브에이전트가 함께 맞춰진다.

`default-config.yaml` 의 `provider:` 위 주석이 이 다섯을 적고, 전환 절차는 CLI 가이드의
[provider 를 바꿀 때](../../getting-started/aimon-core-integration-via-cli-reference.md#provider-를-바꿀-때--agentname-도-함께-바꾼다)
절이 설명한다.

---

## 8. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 모델을 적지 않은 부품에 리터럴 폴백을 둔다(더 나은 리터럴 포함) | 어떤 리터럴이든 벤더 추측이다. 리터럴 자체가 결함이다 |
| 서브에이전트 해석에 클라이언트 기본 모델을 주입한다(`resolveModel` 에 공급자) | 같은 이름을 먼 길로 얻는다 — 두 호출 지점을 바꾸고, 클라이언트가 아니라 게이트웨이를 쥔 실행기까지 공급자를 꿰어야 한다. 기본값을 해석 시점에 얼려, 데코레이터나 라우터가 요청마다 정할 여지를 없앤다 |
| anthropic 에서 memory 가 켜지면 `llm.model` 을 요구한다 | 선택 키를 조건부 필수로 좁힌다. 위키 생성과 `model.name` 없는 메인 에이전트가 이미 클라이언트 기본 모델로 돌므로 memory 만 예외가 된다. 알리는 것으로 충분하다(§3.3) |
| memory 의 dialectic engine 만 가드한다 | 실패가 deriver · reconciler · dreamer · judge 로 옮겨 갈 뿐이다 |
| memory 가 메인 에이전트의 `model.name` 으로 폴백한다 | memory 를 사용자 모르게 `agent.name` 에 묶는다. memory 의 문서화된 키는 `llm.model` 이고, 메인 에이전트도 이름이 없을 수 있다 |
| 코어가 provider 별로 별칭을 푼다 / 벤더 귀속을 `aimon-core` 에 둔다 | 코어에 벤더 지식과 관리할 별칭 표가 생긴다. 능력 레지스트리는 모델 지식이 provider 에 닿는 유일한 이음매이고 일부러 벤더를 싣지 않는다. 같은 별칭이 provider 마다 다른 뜻이 된다 |
| `getProviderName()` 이 요청의 모델을 반영한다 | 인자가 없고 요청을 쥐지 않은 자리에서도 불린다. 오버로드는 한 이름에 두 번째 뜻을 모든 구현자에게 강제하고, 요청별 상태는 thread-safe 클라이언트와 맞지 않는다(§2.2) |
| Anthropic 기본값으로 측정상 제공되지 않는 이름을 유지하고 측정만 기록한다 | 그 기본값에 기대는 모든 경로가 요청 시점에 계속 실패한다 |
| Anthropic 기본값으로 날짜 스냅샷 이름을 쓴다 | 방금 낡은 모양이고, `default-anthropic` 이 도는 모델의 두 번째 표기다. 날짜 없는 이름의 대가 — 벤더가 같은 계열의 이후 스냅샷을 가리킬 수 있다 — 는 응답의 모델 필드가 실제로 돈 것을 말하고 prefix 행이 여전히 기술하므로 받아들인다 |
| Anthropic 기본값으로 측정하지 않은 새 계열을 쓴다 | 측정되지 않았고, 이름 밖에서 요청 모양(thinking · temperature)을 바꾼다 |
| Anthropic 에 내장 기본 모델을 두지 않고 모델을 요구한다 | 배포 모듈의 `AnthropicConfig.builder().apiKey(k).build()` 가 깨지고, anthropic 에서 두 모델 키를 필수로 만들어 §2 를 뒤집는다 |
| 기본 모델을 런타임에 `GET /v1/models` 로 정한다 | 설정 생성 안의 네트워크 호출이고, 답이 키와 계정에 따라 달라진다 |
| 배너 괄호를 `llm.model` 이라고 표시한다 | 참이지만 무엇이 도는지 볼 수 없게 둔다. memory 가 자기 줄을 찍으므로 `llm.model` 에는 배너 역할이 남지 않는다 |
| 배너 괄호에 두 이름을 함께 찍는다 | 출하 설정에서 둘이 다르므로 "어느 것이 도는가" 라는 질문을 되돌린다 |
| 출하 번들 셋의 정의에 서로 다른 `name` 을 준다 | 프롬프트와 `AgentRuntimeId` 를 바꾸는 영속 정체성 변경이다. 사용자 hook · 스킬이 읽는 `AIMON_AGENT_RUNTIME_ID` 에 무엇이 기대는지는 트리에서 셀 수 없고, 배너 줄이 더 작은 변경으로 같은 질문에 답한다 |
| 기본 프롬프트를 설정의 `agent.name` 으로 만든다 | 모든 사용자가 매 입력에서 보는 줄을 세션 동안 고정된 사실 때문에 바꾸고, 런타임 id 와 어긋나며, 출하 설정에서 덜 말한다(§5.2) |
| 번들 `explore` 마다 벤더 전체 모델 id 를 적는다 | 문서화된 짝에서 설정된 provider 로 가는 것이 알려진 이름은 메인 에이전트의 것뿐이다. 번들별 "빠른" 모델은 측정 없는 제품 선택이고, 관리할 벤더 이름을 늘린다 |
| `Task` 도구 설명에 provider 별 모델을 나열한다 | `TaskTool` 에 provider 가 없어 코어 벤더 귀속이나 새 생성자 인자가 필요하다. 매 LLM 호출이 읽는 문자열 속 목록은 아무도 보지 않는 곳에서 낡는다 |
| `Task` 도구의 `model` 파라미터를 없앤다 | 입력 스키마는 모델과 저장된 전사가 기대는 계약이다 |
| 예시에 전체 모델 id 를 적는다 | provider 중립 예시가 다른 provider 에서 실패하고, 은퇴하면 조용히 낡으며, 그것을 잡는 문서 검사가 없다 |
| 모델 없는 예시와 id 를 적은 예시를 함께 둔다 | id 쪽이 위 문제를 그대로 갖는다. 쓸모 있는 절반 — 언제 id 를 적는가 — 는 주석에 접었다 |
| 예시에 placeholder(`<model-id>`)를 적는다 | 요청에 닿는 값이라 복사되면 쓰인 그대로 보내져 별칭처럼 실패한다 |
| 불일치에 기동을 거부한다 | 사용자 정의 이름과 프록시된 이름은 기동되어야 한다 |
| provider 에 따라 에이전트를 자동으로 고른다 | 범위에서 제외했다. 설계상 이유는 기록되어 있지 않다. 번들 선택은 `agent.name` 하나로 남는다 |
| 이름만 보는 규칙(관문 B 만) | OpenAI 호환 게이트웨이 뒤의 `claude-*` 라는 문서화된 경로에 매 기동 거짓 경보를 낸다 |
| 두 벤더 호스트를 모두 "게이트웨이가 아님" 으로 본다 | 정당한 해석이 없는 절반은 관문 A 의 3 으로 채택했다. 나머지 절반은 Anthropic 의 OpenAI SDK 호환 엔드포인트에 거짓 경보를 낸다 |
| 이름의 벤더와 실효 호스트를 소유한 벤더를 비교한다 | 현실적인 입력에 같은 답을 내면서 상태가 더 많고, 출하 에이전트가 OpenAI 호스트에 남은 경우(3)에 침묵한다 |
| 능력 레지스트리 조회로 이름을 인식한다 | 레지스트리에 벤더가 없고, 행이 없는 계열 이름을 놓치며, 선언한 게이트웨이 이름이 인식된 것처럼 보인다 |
| provider 자기 계열 밖의 이름이면 경고한다 | 벤더는 다른 접두어의 모델도 제공하고, 어느 벤더든 새 계열을 내는 날 거짓 경보가 된다 |
| 별칭(맨 이름)에도 경고한다 | 위 대안의 다른 이름이다. 게이트웨이가 별칭을 정당하게 풀 수 있고, 거짓 경보가 없다는 §7.1 의 논증이 무너진다 |
| 메인 에이전트만 검사한다 | 자기 모델을 적은 번들 서브에이전트를 놓친다 |
| 번들의 서브에이전트 레지스트리만 검사한다 | `.aimon/agents` 서브에이전트를 놓치고, 사용자 파일이 가린 번들 서브에이전트를 보고한다 |
| 모델 문자열 비교로 출처를 정한다 / 모호하면 두 경로를 다 찍는다 | 번들 모델을 유지한 사용자 사본을 번들로 표시하거나 읽히지 않는 파일을 함께 이름한다. 사용자 층이 자기 인스턴스를 파싱하므로 흔히 도달하는 상태다. 사본을 돌려주는 레지스트리에 대한 완충은 실제 스택 조립을 거치는 테스트가 더 싸게 산다 |
| 위치 없이 "불러온 에이전트와 서브에이전트" 라고만 말한다 | 처방은 런타임이 읽는 파일을 이름해야 한다. 가림이 있으면 사용자는 번들 파일을 열고 틀린 모델을 찾게 된다 |
| `.aimon/agents/<이름>.md` 를 상대 경로로 찍는다 | 기준이 셸의 디렉토리가 아니라 jar 디렉토리 또는 `user.dir` 이라, 따라간 jar 사용자는 아무도 읽지 않는 파일을 만든다 |
| `agent.name` 이 provider 의 번들과 다르면 늘 권한다 | 번들 서브에이전트 하나의 키 때문에 올바른 자기 번들을 버리라고 권한다 |
| `log.warn` 만 쓴다 / `displayError` 를 쓴다 / 새 `displayWarning` 을 만든다 | 터미널에 보이지 않는다 / 기동이 계속되는데 실패로 읽힌다 / 새 채널이다 |
| 경고를 끄는 설정 키를 둔다 | 검사가 발화하는 모든 설정은 제공할 수 없는 API 로 이름을 보낸다 |
| 측정된 404 를 경고 문구에 넣는다 | 날짜 없는 런타임 문자열에 들어가면 벤더 응답이 바뀌어도 고칠 계기가 없고, 한 방향만 측정했다. "제공하지 않는다" 는 경고의 주장은 그것 없이 성립한다 |
| 경고가 가이드를 경로로 가리킨다 | CLI 의 어떤 런타임 문자열도 문서를 가리키지 않는다. 전환 자리 옆의 yaml 주석이 가이드를 가리킨다 |

---

## 9. 하지 말 것

- **모델을 적지 않은 부품에 리터럴 모델 이름을 채우지 말 것.** 이름을 비워 두면 클라이언트가 자기 기본 모델을 보낸다.
- **어느 자리에서도 별칭을 풀지 말 것.** 호출별 override 를 "별칭" 이라 부르지도 말 것.
- **`getProviderName()` 에 모델을 넣지 말 것.** 새 `LlmClient` 데코레이터는 `getDefaultModelName()` 을 위임에 전달할 것 —
  빠뜨리면 그 바깥의 관측 지점이 빈 모델을 본다.
- **`resolvedModel().getName()` 이 늘 있다고 가정하지 말 것.** 이름이 어디에도 없으면 비어 있다.
- **복사해 쓰는 예시 · 기본 설정 주석의 요청에 닿는 값에 벤더 모델 id 나 placeholder 를 쓰지 말 것.**
- **`AnthropicConfig` 의 기본 모델을 내장 능력 표가 기술하지 않거나 제공이 측정되지 않은 이름으로 바꾸지 말 것.**
- **출하 번들의 정의 `name` 을 바꾸지 말 것.** 프롬프트 · `AgentRuntimeId` · `AIMON_AGENT_RUNTIME_ID` 가 따라 바뀐다.
- **배너 괄호에 `llm.model` 을 에이전트의 모델처럼 찍지 말 것.**
- **기동 검사가 기동을 멈추게 하지 말 것.** 협력자 읽기를 seam 의 `try` 밖으로 옮기지 말 것.
- **경고를 `log.warn` 하나로만 내지 말 것.** CLI 로그는 WARN 을 파일로만 보낸다.
- **경고 항목의 출처를 모델 문자열로 판정하지 말 것.** 런타임이 읽지 않는 파일을 이름하게 된다.
- **번들 레지스트리를 감싸거나 다시 파싱해 런타임 층에 넣지 말 것.** 인스턴스 동일성이 깨져 모든 번들 서브에이전트가
  존재하지 않는 `.aimon/agents` 경로로 찍힌다.
- **관문 A 를 넓히지 말 것.** 게이트웨이일 수 있는 `baseUrl` 에서 경고하면 거짓 경보 없음이 무너진다.
- **벤더 계열 판정에 레지스트리 행을 쓰지 말 것.**

---

## 10. 남은 것

- **복사해 쓰는 README · javadoc 예시 일부가 서비스되지 않는 모델 이름을 적는다** — §6 의 규칙이 아직 닿지 않은 자리.
  [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) L-27.
- **관문 A 의 3 밖 provider/호스트 불일치는 침묵한다**(§7.1 의 수용한 거짓 음성). 첫 요청에서 실패하고, 모델 검사로는 그
  이유를 이름할 수 없어 이 검사의 범위에 두지 않았다. 백로그에 등록하지 않았다.
- **OpenAI API 가 `claude-*` 이름에 무엇을 답하는지 측정하지 않았다.** 경고 문구는 그 답에 기대지 않는다(Anthropic 방향만
  측정했다).
- **서브에이전트 요청이 404 로 실패한 뒤 `Task` 도구가 메인 에이전트에 무엇을 돌려주는지, 모델이 그다음 무엇을 하는지
  실행해 보지 않았다.**
- **모델이 `Task` 도구의 override 로 제공되지 않는 이름을 넘길 수 있고, 기동 검사는 호출별 인자를 볼 수 없다.** 설명이 더는
  틀린 이름을 권하지 않으므로 처방도 다시 볼 계기도 없어 항목으로 두지 않았다([L-20](../../backlog/llm-config-surface-open-items.md) 닫힘 메모에 기록).
- **위키 저장소의 디스크 배치가 런타임 id 를 싣는지 세지 않았다.** 번들 정의의 이름을 가르는 결정(§5.2)이 다시 올라올 때만
  필요하다([L-21](../../backlog/llm-config-surface-open-items.md) 닫힘 메모에 기록).

---

## 부록 — 참조 파일 지도

| 파일 | 무엇을 확인하나 |
|---|---|
| `modules/aimon-core/src/main/java/at/aimon/core/llm/LlmClient.java` | `getProviderName()` 이 벤더뿐인 이유, `getDefaultModelName()` 의 `default` 와 전달 의무 |
| `modules/aimon-core/src/main/java/at/aimon/core/llm/logging/LoggingLlmClient.java` · `.../llm/usage/MeteringLlmClient.java` · `.../tracing/impl/TracingLlmClient.java` | 요청 이름 → 클라이언트 기본 모델 → 각자의 폴백 |
| `modules/aimon-core/src/main/java/at/aimon/core/llm/tagging/TaggingLlmClient.java` · `BoundMetadataLlmClient.java` | 나머지 두 데코레이터의 전달 |
| `modules/aimon-core/src/test/java/at/aimon/core/llm/LlmClientDefaultModelNameForwardingTest.java` | 데코레이터 전달을 붙드는 테스트 |
| `modules/aimon-core/src/main/java/at/aimon/core/agent/compact/DefaultCompactionGuard.java` · `.../llm/cost/TablePricedCostEstimator.java` | 이름 없는 모델을 다루는 두 자리 |
| `modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/OpenAIConfig.java` · `OpenAILlmClient.java` | 기본 모델 없음과 검사 순서, 요청 이름 해석 |
| `modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/AnthropicConfig.java` · `AnthropicLlmClient.java` | `DEFAULT_MODEL`, 요청 이름 해석 |
| `modules/aimon-llm-anthropic/src/test/java/at/aimon/core/llms/anthropic/AnthropicConfigTest.java` | 기본 모델이 기술된 행으로 해석되는지 |
| `modules/aimon-core/src/main/java/at/aimon/core/subagent/execution/SubagentLlmDefaults.java` | 서브에이전트 이름 해석 순서, 이름 없는 결과 |
| `modules/aimon-core/src/main/java/at/aimon/core/subagent/behavior/SubagentBehaviorSupport.java` | `resolvedModel()` 의 계약 |
| `modules/aimon-core/src/main/java/at/aimon/core/tools/task/TaskTool.java` | `MODEL_DESCRIPTION` |
| `modules/aimon-core/src/main/java/at/aimon/core/agent/impl/orca/OrcaAgentRuntimeFactory.java` | 서브에이전트 레지스트리 세 층 |
| `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentSetupFactory.java` | `memoryModelName`, `reportAgentModelMismatch` 와 호출 위치, 작업 디렉토리(jar 디렉토리 / `user.dir`), `AgentSetup.getAgentBundleName` |
| `modules/aimon-cli/src/main/java/at/aimon/cli/factory/LlmClientFactory.java` | openai 에서 모델 요구, anthropic 에서 선택 |
| `modules/aimon-cli/src/main/java/at/aimon/cli/factory/AgentModelProviderCheck.java` | 두 관문, 계열 접두어, 출처, 위치 표기, 처방 조건, `BUNDLE_FOR` |
| `modules/aimon-cli/src/main/java/at/aimon/cli/repl/ReplSession.java` | `providerLine` · `agentBundleLine` · `displayAgentInfo` |
| `modules/aimon-cli/src/main/java/at/aimon/cli/AimonCli.java` | 프롬프트가 정의의 이름에서 오는 자리 |
| `modules/aimon-cli/src/main/resources/default-config.yaml` | 함께 바뀌는 키 다섯, `llm.model` 이 닿는 곳, 벤더 리터럴 없는 기본 모델 서술 |
| `modules/aimon-cli/src/main/resources/agents/*/agents/explore.md` | 모델 줄 자리의 주석 |
| `modules/aimon-cli/src/main/resources/logback.xml` | WARN 이 파일로만 가는 설정 |
| `modules/aimon-bootstrap/src/main/java/at/aimon/bootstrap/assemble/StackPaths.java` | `AGENTS_DIRECTORY` |
| `modules/aimon-spring-boot-starter/src/main/java/at/aimon/spring/boot/autoconfigure/AimonLlmAutoConfiguration.java` | `requireModel`(openai), anthropic 의 선택 모델 |
| `modules/aimon-cli/src/test/java/at/aimon/cli/factory/AgentModelProviderCheckTest.java` · `AgentSetupFactoryAgentModelCheckTest.java` · `AgentSetupFactoryCreateTest.java` | 관문 · 처방 · 출처, 실제 스택 조립에서의 동일성, `create()` 를 지나는 호출 |
| `modules/aimon-cli/src/test/java/at/aimon/cli/factory/AgentSetupFactoryMemoryModelTest.java` · `BundledSubagentModelTest.java` · `.../repl/ReplSessionBannerTest.java` | memory 이름 해석, 번들 서브에이전트의 해석된 모델, 배너 줄 |

---

## 관련 문서

- [`model-capabilities.md`](model-capabilities.md) — 모델 이름 스니핑 금지의 정본, 계열 접두어가 기대는 내장 표와 기본 모델이
  해석되는 행
- [`request-parameters.md`](request-parameters.md) — 서브에이전트 temperature · max tokens 명시값, 요청 조립 규칙
- [`configuration-surface.md`](configuration-surface.md) — `llm.model` · `aimon.llm.model` 이 사는 설정 표면
- [`../subagent/execution.md`](../subagent/execution.md) — 포크 실행의 정체성 · 예산 · 격리
- [`../memory/pluggable-memory-backend.md`](../memory/pluggable-memory-backend.md) — memory 재료가 `MemorySpec` 으로 들어가는 경로
- [LLM Provider 개발 가이드](../../features/llm/llm-provider-development-guide.md) — `LlmClient` SPI 정본
- [서브에이전트 개발 가이드](../../features/subagent/subagent-development-guide.md) — `resolvedModel()` 과 모델 없는 예시
- [CLI 레퍼런스 › provider 를 바꿀 때](../../getting-started/aimon-core-integration-via-cli-reference.md#provider-를-바꿀-때--agentname-도-함께-바꾼다) — 전환 절차
- [`../../backlog/llm-config-surface-open-items.md`](../../backlog/llm-config-surface-open-items.md) — L-27 과 닫힌 L-17 ~ L-21 · L-24
- [용어집](../../overview/glossary.md) — turn / iteration / execution
