# Browser Tool 사용 가이드

> BrowserTool 설정, 조립, 액션별 사용법에 대한 완전한 가이드

## 목차

1. [개요](#개요)
2. [설정 및 조립](#설정-및-조립)
3. [세션 관리](#세션-관리)
4. [액션별 사용법](#액션별-사용법)
5. [응답 구조](#응답-구조)
6. [권한 시스템](#권한-시스템)
7. [리소스 정책](#리소스-정책)
8. [에러 처리](#에러-처리)
9. [종료 및 정리](#종료-및-정리)

---

## 개요

`BrowserTool`은 LLM Agent가 웹 브라우저를 자동화할 수 있는 Tool이다.
Playwright Java (Chromium headless) 기반이며, 13가지 브라우저 액션을 지원한다.

Tool 이름은 `"Browser"`이며, `action` 파라미터로 동작을 지정한다.
각 호출은 하나의 액션을 수행하고, 페이지 상태와 상호작용 가능한 요소 목록을 JSON으로 반환한다.

---

## 설정 및 조립

### Gradle 의존성

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":aimon-browser-playwright"))
}
```

### 컴포넌트 조립

BrowserTool은 4개의 핵심 의존성이 필요하다.

```java
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.browser.playwright.*;
import at.aimon.browser.playwright.action.*;
import at.aimon.core.ext.tools.web.fetch.ContentExtractor;
import at.aimon.core.ext.tools.web.security.SsrfGuard;

// 1. Playwright Worker Pool (Chromium 프로세스 관리)
//    workerCount: Worker 수 (Worker당 약 200-500MB 메모리)
//    headless: true면 UI 없이 실행
PlaywrightWorkerPool workerPool = new PlaywrightWorkerPool(2, true);

// 2. 세션 저장소 (LRU + TTL)
//    maxSessions: 최대 동시 세션 수
//    sessionTtl: 비활동 세션 만료 시간
InMemoryBrowserSessionStore sessionStore =
        new InMemoryBrowserSessionStore(10, Duration.ofMinutes(30), workerPool);

// 3. SSRF 보호 (aimon-core 제공)
SsrfGuard ssrfGuard = new SsrfGuard();

// 4. 콘텐츠 추출기 (aimon-core 제공, markdown 모드용)
ContentExtractor contentExtractor = new ContentExtractor();

// 5. 액션 핸들러 등록
List<BrowserActionHandler> handlers = List.of(
        new OpenActionHandler(ssrfGuard),
        new ClickActionHandler(),
        new TypeActionHandler(),
        new PressActionHandler(),
        new SelectActionHandler(),
        new ScrollActionHandler(),
        new WaitActionHandler(),
        new ExtractActionHandler(contentExtractor),
        new ScreenshotActionHandler(),
        new NavigationActionHandler("back"),
        new NavigationActionHandler("forward"),
        new NavigationActionHandler("reload"),
        new CloseActionHandler(sessionStore)
);

// 6. 디스패처 생성
BrowserActionDispatcher dispatcher = new BrowserActionDispatcher(handlers);

// 7. BrowserTool 생성
ObjectMapper objectMapper = new ObjectMapper();
BrowserTool browserTool = new BrowserTool(
        dispatcher, sessionStore, workerPool, objectMapper);
```

### 원격 브라우저 연결

원격 Playwright Server 또는 Chrome CDP에 연결할 수 있다.

```java
import at.aimon.browser.playwright.PlaywrightConnectionConfig;
import at.aimon.browser.playwright.PlaywrightConnectionMode;

// 원격 Playwright Server (WebSocket)
PlaywrightConnectionConfig wsConfig = PlaywrightConnectionConfig.builder()
        .mode(PlaywrightConnectionMode.REMOTE_WS)
        .endpoint("ws://playwright-server:3000")
        .build();
PlaywrightWorkerPool workerPool = new PlaywrightWorkerPool(2, wsConfig);

// 원격 Chrome CDP
PlaywrightConnectionConfig cdpConfig = PlaywrightConnectionConfig.builder()
        .mode(PlaywrightConnectionMode.REMOTE_CDP)
        .endpoint("http://chrome:9222")
        .build();
PlaywrightWorkerPool workerPool = new PlaywrightWorkerPool(1, cdpConfig);
```

이후 조립 과정은 로컬 실행과 동일하다 (세션 저장소, 핸들러, 디스패처 등).

### OrcaToolProvider로 등록

aimon-core의 Agent 프레임워크에 통합하려면 `OrcaToolProvider`를 구현한다.

```java
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.impl.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.impl.orca.tool.OrcaToolProviderContext;

public class OrcaBrowserToolProvider implements OrcaToolProvider {

    private final int workerCount;
    private final int maxSessions;
    private final Duration sessionTtl;

    public OrcaBrowserToolProvider(int workerCount, int maxSessions, Duration sessionTtl) {
        this.workerCount = workerCount;
        this.maxSessions = maxSessions;
        this.sessionTtl = sessionTtl;
    }

    @Override
    public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
        PlaywrightWorkerPool workerPool = new PlaywrightWorkerPool(workerCount, true);
        InMemoryBrowserSessionStore sessionStore =
                new InMemoryBrowserSessionStore(maxSessions, sessionTtl, workerPool);

        SsrfGuard ssrfGuard = new SsrfGuard();
        ContentExtractor contentExtractor = new ContentExtractor();

        List<BrowserActionHandler> handlers = List.of(
                new OpenActionHandler(ssrfGuard),
                new ClickActionHandler(),
                new TypeActionHandler(),
                new PressActionHandler(),
                new SelectActionHandler(),
                new ScrollActionHandler(),
                new WaitActionHandler(),
                new ExtractActionHandler(contentExtractor),
                new ScreenshotActionHandler(),
                new NavigationActionHandler("back"),
                new NavigationActionHandler("forward"),
                new NavigationActionHandler("reload"),
                new CloseActionHandler(sessionStore)
        );

        BrowserActionDispatcher dispatcher = new BrowserActionDispatcher(handlers);
        ObjectMapper objectMapper = new ObjectMapper();
        registry.register(new BrowserTool(dispatcher, sessionStore, workerPool, objectMapper));
    }
}
```

사용 시 `defaultToolProviders()`에 추가한다:

```java
List<OrcaToolProvider> providers = List.of(
        // 기존 프로바이더들...
        new OrcaFileToolProvider(),
        new OrcaBashToolProvider(),
        // Browser Tool 추가
        new OrcaBrowserToolProvider(2, 10, Duration.ofMinutes(30))
);
```

---

## 세션 관리

### 세션 생성

`session_id`를 생략하면 새 세션이 자동 생성된다.
세션 ID는 `"bs_"` 접두사 + UUID 8자 형태로 부여된다 (예: `bs_a1b2c3d4`).

```json
{
  "action": "open",
  "url": "https://example.com"
}
```

응답의 `sessionId` 값을 이후 호출에서 재사용한다.

### 세션 옵션

새 세션 생성 시 다음 옵션을 지정할 수 있다.

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `locale` | `en-US` | 브라우저 로케일 (예: `ko-KR`, `ja-JP`) |
| `user_agent` | Chromium 기본값 | User-Agent 문자열 |
| `viewport_width` | `1280` | 뷰포트 너비 (px) |
| `viewport_height` | `720` | 뷰포트 높이 (px) |
| `resource_policy` | `minimal` | 리소스 로딩 정책 ([리소스 정책](#리소스-정책) 참조) |

```json
{
  "action": "open",
  "url": "https://example.com",
  "locale": "ko-KR",
  "viewport_width": 1920,
  "viewport_height": 1080,
  "resource_policy": "visual"
}
```

### 세션 재사용

동일 세션에서 연속 작업을 수행하려면 `session_id`를 전달한다.

```json
{"action": "open", "url": "https://example.com"}
// -> sessionId: "bs_a1b2c3d4"

{"action": "click", "session_id": "bs_a1b2c3d4", "selector": "#login"}
{"action": "type", "session_id": "bs_a1b2c3d4", "selector": "#email", "value": "user@test.com"}
{"action": "close", "session_id": "bs_a1b2c3d4"}
```

### 세션 수명

- **LRU eviction**: 최대 세션 수(`maxSessions`)를 초과하면 가장 오래 미사용된 세션이 자동 제거된다.
- **TTL 만료**: `sessionTtl` 이상 비활동 상태인 세션은 조회 시 자동 만료된다.
- **명시적 종료**: `close` 액션으로 세션을 닫을 수 있다.

---

## 액션별 사용법

### open — URL 네비게이션

페이지를 URL로 이동한다. SSRF 사전/사후 검증을 수행한다.

| 파라미터 | 필수 | 기본값 | 설명 |
|---------|------|--------|------|
| `url` | **O** | - | 이동할 URL |
| `wait_until` | | `domcontentloaded` | 대기 전략: `domcontentloaded`, `load`, `networkidle` |

```json
{"action": "open", "url": "https://example.com"}
{"action": "open", "url": "https://example.com", "wait_until": "networkidle"}
```

**응답**: `url`, `title`, `candidates`

---

### click — 요소 클릭

페이지의 요소를 클릭한다. Locator 결정 우선순위: `selector` > `role`+`text` > `text`.

| 파라미터 | 필수 | 기본값 | 설명 |
|---------|------|--------|------|
| `selector` | | - | CSS selector |
| `text` | | - | 표시 텍스트로 요소 검색 |
| `role` | | - | ARIA role (예: `button`, `link`, `tab`) |
| `exact` | | `false` | true면 텍스트 정확 일치 |

세 가지 중 최소 하나는 필수이다.

```json
{"action": "click", "session_id": "bs_xxx", "selector": "#submit-btn"}
{"action": "click", "session_id": "bs_xxx", "text": "로그인"}
{"action": "click", "session_id": "bs_xxx", "role": "button", "text": "Submit", "exact": true}
```

**응답**: `url`, `title`, `candidates`

---

### type — 텍스트 입력

입력 필드에 텍스트를 입력한다.
비밀번호 관련 필드는 로그에 값이 `***`로 마스킹된다.

| 파라미터 | 필수 | 기본값 | 설명 |
|---------|------|--------|------|
| `selector` | | - | 대상 CSS selector |
| `text` | | - | 표시 텍스트로 요소 검색 |
| `value` | **O** | - | 입력할 텍스트 |
| `clear` | | `false` | true면 입력 전 기존 값 삭제 |

`selector` 또는 `text` 중 하나 필수.

```json
{"action": "type", "session_id": "bs_xxx", "selector": "#email", "value": "user@test.com"}
{"action": "type", "session_id": "bs_xxx", "selector": "#search", "value": "aimon", "clear": true}
```

**응답**: `url`, `title`, `candidates`

---

### press — 키보드 키 입력

키보드 키를 누른다.

| 파라미터 | 필수 | 기본값 | 설명 |
|---------|------|--------|------|
| `key` | **O** | - | 키 이름 (예: `Enter`, `Tab`, `Escape`, `ArrowDown`) |

```json
{"action": "press", "session_id": "bs_xxx", "key": "Enter"}
{"action": "press", "session_id": "bs_xxx", "key": "Tab"}
```

**응답**: `url`, `title`, `candidates`

---

### select — 드롭다운 선택

`<select>` 요소에서 옵션을 선택한다.

| 파라미터 | 필수 | 기본값 | 설명 |
|---------|------|--------|------|
| `selector` | **O** | - | `<select>` 요소의 CSS selector |
| `value` | **O** | - | 선택할 `<option>`의 value |

```json
{"action": "select", "session_id": "bs_xxx", "selector": "#country", "value": "KR"}
```

**응답**: `url`, `title`, `candidates`

---

### scroll — 페이지 스크롤

페이지를 위아래로 스크롤한다.

| 파라미터 | 필수 | 기본값 | 설명 |
|---------|------|--------|------|
| `direction` | **O** | - | `up` 또는 `down` |
| `amount` | | `500` | 스크롤 양 (px) |

```json
{"action": "scroll", "session_id": "bs_xxx", "direction": "down"}
{"action": "scroll", "session_id": "bs_xxx", "direction": "up", "amount": 1000}
```

**응답**: `url`, `title`, `candidates`

---

### wait — 대기

지정 시간만큼 대기하거나, 특정 요소가 나타날 때까지 대기한다.

| 파라미터 | 필수 | 기본값 | 설명 |
|---------|------|--------|------|
| `selector` | | - | 대기할 요소의 CSS selector (지정 시 요소 대기) |
| `wait_ms` | | `1000` | 대기 시간 (ms). `timeout_ms`를 초과할 수 없음 |

```json
{"action": "wait", "session_id": "bs_xxx", "wait_ms": 2000}
{"action": "wait", "session_id": "bs_xxx", "selector": "#result-panel"}
```

**응답**: `url`, `title`, `candidates`

---

### extract — 콘텐츠 추출

페이지 콘텐츠를 텍스트, HTML, 또는 Markdown 형식으로 추출한다.

| 파라미터 | 필수 | 기본값 | 설명 |
|---------|------|--------|------|
| `mode` | | `text` | 추출 모드: `text`, `html`, `markdown` |
| `max_chars` | | `50000` | 최대 반환 문자 수 |
| `selector` | | - | 특정 영역만 추출 (지정하지 않으면 전체 페이지) |

```json
{"action": "extract", "session_id": "bs_xxx"}
{"action": "extract", "session_id": "bs_xxx", "mode": "markdown", "max_chars": 10000}
{"action": "extract", "session_id": "bs_xxx", "mode": "html", "selector": "#article-body"}
```

**응답**: `url`, `title`, `content`

---

### screenshot — 스크린샷

페이지 스크린샷을 캡처하여 base64 PNG로 반환한다.

| 파라미터 | 필수 | 기본값 | 설명 |
|---------|------|--------|------|
| `full_page` | | `false` | true면 스크롤 포함 전체 페이지 캡처 |
| `selector` | | - | 특정 요소만 캡처 |

```json
{"action": "screenshot", "session_id": "bs_xxx"}
{"action": "screenshot", "session_id": "bs_xxx", "full_page": true}
{"action": "screenshot", "session_id": "bs_xxx", "selector": "#chart"}
```

**응답**: `url`, `title`, `screenshot` (base64 PNG)

> **주의**: `resource_policy`가 `minimal`인 세션에서는 이미지/CSS가 차단되어 스크린샷이 부정확할 수 있다. 이 경우 `warnings` 필드에 경고가 포함된다. 정확한 스크린샷이 필요하면 `resource_policy=visual`로 세션을 생성한다.

---

### back / forward / reload — 네비게이션

브라우저의 뒤로, 앞으로, 새로고침 동작을 수행한다.

```json
{"action": "back", "session_id": "bs_xxx"}
{"action": "forward", "session_id": "bs_xxx"}
{"action": "reload", "session_id": "bs_xxx"}
```

**응답**: `url`, `title`, `candidates`

---

### close — 세션 종료

세션을 닫고 Playwright 리소스를 해제한다.

```json
{"action": "close", "session_id": "bs_xxx"}
```

**응답**: `sessionId`, `action`, `message`

---

## 응답 구조

모든 액션의 응답은 `BrowserActionResult` JSON 형식이다.
null 필드는 생략된다.

### 성공 응답

```json
{
  "sessionId": "bs_a1b2c3d4",
  "action": "open",
  "url": "https://example.com",
  "title": "Example Domain",
  "candidates": [
    {
      "label": "More information...",
      "selector": "a",
      "role": "link",
      "text": null
    }
  ]
}
```

### 에러 응답

```json
{
  "sessionId": "bs_a1b2c3d4",
  "action": "click",
  "error": "ELEMENT_NOT_FOUND",
  "message": "No element found for the given criteria",
  "candidates": [
    {"label": "Login", "selector": "#login-btn", "role": "button"}
  ]
}
```

에러 응답에도 `candidates`가 포함될 수 있어, LLM이 대안 요소를 선택하는 데 활용할 수 있다.

### 필드 설명

| 필드 | 타입 | 설명 |
|------|------|------|
| `sessionId` | string | 세션 ID |
| `action` | string | 수행된 액션 |
| `url` | string | 현재 페이지 URL |
| `title` | string | 현재 페이지 제목 |
| `error` | string | 에러 코드 (null이면 성공) |
| `message` | string | 사람이 읽을 수 있는 메시지 |
| `content` | string | 추출된 콘텐츠 (extract 액션) |
| `screenshot` | string | base64 인코딩 PNG (screenshot 액션) |
| `candidates` | array | 상호작용 가능한 요소 목록 (최대 30개) |
| `warnings` | array | 경고 메시지 |

### candidates 구조

`candidates`는 LLM이 다음 액션의 대상을 결정할 때 사용하는 요소 목록이다.

```json
{
  "label": "Submit Form",
  "selector": "#submit-btn",
  "role": "button",
  "text": "Submit"
}
```

| 필드 | 설명 |
|------|------|
| `label` | 요소의 표시 텍스트 (최대 80자) |
| `selector` | CSS selector (다음 액션에서 바로 사용 가능) |
| `role` | ARIA role 또는 태그 유형 (`link`, `button`, `textbox` 등) |
| `text` | placeholder 또는 aria-label |

수집 대상: `a`, `button`, `[role=button]`, `input`, `select`, `textarea`, `[role=link]`, `[role=tab]`, `[role=menuitem]`

---

## 권한 시스템

BrowserTool은 `CustomToolPermissionAware`를 구현하며, 허용 패턴(AllowedTool)으로 접근을 제어한다.

### 허용 패턴

| 패턴 | 의미 |
|------|------|
| `Browser` | 모든 액션, 모든 URL 허용 |
| `Browser(open:*)` | open 액션의 모든 URL 허용 |
| `Browser(open:https://example.com:*)` | open 액션에서 그 URL 접두사만 허용 |
| `Browser(extract:*)` | extract 액션만 허용 |
| `Browser(screenshot:*)` | screenshot 액션만 허용 |
| `Browser(click:*)` | click 액션만 허용 |

매칭 대상은 `action` 또는 `action:url` 문자열이고, 패턴은 `ToolPattern` 규칙을 그대로 따른다 —
**`:*` 로 끝나면 접두사 매칭, 그렇지 않으면 완전 일치**다. 따라서 호스트 글롭은 지원되지 않는다:
`Browser(open:*.example.com)` 은 URL 이 문자 그대로 `*.example.com` 일 때만 매치되므로 사실상 아무것도
허용하지 않는다. 도메인으로 좁히려면 위 표처럼 **URL 접두사 + `:*`** 를 쓴다. 이 표기는 스킴까지
포함하므로 `https://example.com` 과 `http://example.com` 은 별개 항목이다.

### 설정 예시

Agent 설정에서 allowedTools로 제어한다:

```java
// 모든 브라우저 동작 허용
AllowedTool.of("Browser")

// 특정 URL 접두사만 탐색 허용, 추출은 자유
AllowedTool.of("Browser(open:https://example.com:*)")
AllowedTool.of("Browser(extract:*)")

// 읽기 전용 (open + extract만 허용)
AllowedTool.of("Browser(open:*)")
AllowedTool.of("Browser(extract:*)")
AllowedTool.of("Browser(scroll:*)")
AllowedTool.of("Browser(wait:*)")
AllowedTool.of("Browser(close:*)")
```

---

## 리소스 정책

세션 생성 시 `resource_policy`로 브라우저의 리소스 로딩 전략을 지정한다.

### `minimal` (기본값)

이미지, 폰트, 미디어, CSS를 차단하여 빠르게 텍스트를 추출한다.

- **장점**: 빠른 페이지 로드, 적은 대역폭 사용
- **적합한 작업**: 텍스트 추출, 폼 입력, 링크 탐색
- **주의**: 스크린샷이 부정확할 수 있음

### `visual`

모든 리소스를 정상 로드한다.

- **장점**: 정확한 스크린샷, 실제 사용자 환경과 동일
- **적합한 작업**: 스크린샷 캡처, 시각적 검증, CSS에 의존하는 인터랙션

```json
{"action": "open", "url": "https://example.com", "resource_policy": "visual"}
```

---

## 에러 처리

### 에러 코드

| 코드 | 발생 상황 |
|------|----------|
| `SSRF_BLOCKED` | URL이 SSRF 보안 정책에 의해 차단됨 |
| `NAVIGATION_FAILED` | 페이지 로드 실패 (네트워크 에러, 잘못된 URL 등) |
| `ELEMENT_NOT_FOUND` | 지정한 selector/text/role로 요소를 찾지 못함 |
| `INVALID_PARAMETER` | 필수 파라미터 누락 또는 잘못된 값 |
| `INVALID_MODE` | extract 모드가 text/html/markdown이 아님 |
| `UNKNOWN_ACTION` | 지원하지 않는 액션 |
| `TIMEOUT` | wait 액션 타임아웃 |
| `CLICK_FAILED` | 클릭 실패 |
| `TYPE_FAILED` | 텍스트 입력 실패 |
| `SCROLL_FAILED` | 스크롤 실패 |
| `EXTRACT_FAILED` | 콘텐츠 추출 실패 |
| `SCREENSHOT_FAILED` | 스크린샷 캡처 실패 |

### ToolResult 수준의 에러

BrowserActionResult JSON 외에, `ToolResult.error()`로 반환되는 상위 수준 에러도 있다.

| 메시지 패턴 | 원인 |
|------------|------|
| `Invalid parameter: ...` | 필수 파라미터 누락, 세션 미발견, 세션 수 초과 |
| `Action timed out` | `timeout_ms` 초과 |
| `Unexpected error: ...` | 예상치 못한 내부 에러 |

### timeout_ms

모든 액션에 `timeout_ms` 파라미터를 지정할 수 있다 (기본값: 30000ms).
범위는 1000ms ~ 120000ms로 클램핑된다.

```json
{"action": "open", "url": "https://slow-site.com", "timeout_ms": 60000}
```

---

## 종료 및 정리

### 세션 종료

개별 세션은 `close` 액션으로 명시적으로 닫는다.

```json
{"action": "close", "session_id": "bs_xxx"}
```

### 전체 자원 해제

애플리케이션 종료 시 `AutoCloseable`을 통해 모든 리소스를 정리한다.
정리 순서는 세션 저장소 → Worker Pool 순서이다.

```java
// try-with-resources 사용
try (PlaywrightWorkerPool pool = new PlaywrightWorkerPool(2, true);
     InMemoryBrowserSessionStore store =
             new InMemoryBrowserSessionStore(10, Duration.ofMinutes(30), pool)) {

    // BrowserTool 사용...

} // store.close() -> pool.close() 순서로 자동 정리

// 또는 명시적 종료
sessionStore.close();    // 모든 세션 제거 및 리소스 해제
workerPool.shutdown();   // 모든 Worker 종료
```

---

## 전체 워크플로우 예시

### 로그인 후 데이터 추출

```
1. open   → https://app.example.com/login
2. type   → #email, "user@example.com"
3. type   → #password, "********"
4. click  → text: "Sign In"
5. wait   → selector: "#dashboard"
6. extract → mode: markdown
7. close
```

### 검색 결과 수집

```
1. open   → https://search-engine.com
2. type   → selector: "#search-box", value: "aimon framework", clear: true
3. press  → key: "Enter"
4. wait   → selector: ".results"
5. extract → mode: text, selector: ".results"
6. scroll → direction: down
7. extract → mode: text, selector: ".results"
8. close
```

### 스크린샷 캡처

```
1. open   → https://dashboard.example.com, resource_policy: visual
2. wait   → wait_ms: 3000
3. screenshot → full_page: true
4. close
```

---

## 관련 문서

- [Tool 개발 가이드](tool-development-guide.md)
- [SOLID Principles](../../project/solid-principles.md)
- [aimon-browser-playwright README](../../../modules/aimon-browser-playwright/README.md)
