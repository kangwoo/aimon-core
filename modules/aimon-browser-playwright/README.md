# aimon-browser-playwright

LLM Agent가 웹 브라우저를 자동화할 수 있는 Browser Tool 모듈.
Playwright Java (Chromium headless) 기반이며, `aimon-core`와 분리된 별도 모듈이다.

## 개요

단일 `Browser` Tool에서 `action` 파라미터로 14가지 브라우저 동작을 수행한다.
각 호출은 하나의 액션을 실행하고, 페이지 상태와 상호작용 가능한 요소 목록을 포함한 구조화된 JSON을 반환한다.

```
User Request -> BrowserTool
                    |
             BrowserActionDispatcher (action 라우팅)
                    |
          BrowserActionHandler (14개 액션 타입)
                    |
         PlaywrightWorkerPool (멀티 Worker 실행)
                    |
      PlaywrightLifecycleManager (Worker별 전용 스레드)
                    |
           Playwright API (Chromium)
```

## 지원 액션

| 액션 | 설명 | 주요 파라미터 |
|------|------|--------------|
| `open` | URL로 이동 (SSRF 사전/사후 검증) | `url`, `wait_until` |
| `click` | 요소 클릭 | `selector`, `text`, `role`, `exact` |
| `type` | 텍스트 입력 | `selector`, `value`/`credential_ref`, `clear` |
| `press` | 키보드 키 입력 | `key` |
| `select` | 드롭다운 선택 | `selector`, `value` |
| `scroll` | 페이지 스크롤 | `direction`, `amount` |
| `wait` | 대기 | `wait_ms`, `selector` |
| `extract` | 콘텐츠 추출 | `mode` (text/html/markdown), `max_chars`, `selector` |
| `screenshot` | 스크린샷 캡처 | `full_page` |
| `back` | 뒤로 이동 | - |
| `forward` | 앞으로 이동 | - |
| `reload` | 페이지 새로고침 | - |
| `close` | 세션 종료 | - |
| `save_auth` | 인증 상태 저장 (cookies + localStorage) | - |

## 사용 예시

```json
// 1. 페이지 열기 (새 세션 자동 생성)
{"action": "open", "url": "https://example.com"}
// -> {"sessionId": "bs_a1b2c3d4", "action": "open", "url": "https://example.com", ...}

// 2. 같은 세션에서 클릭
{"action": "click", "session_id": "bs_a1b2c3d4", "selector": "#login-btn"}

// 3. 텍스트 입력
{"action": "type", "session_id": "bs_a1b2c3d4", "selector": "#username", "value": "admin"}

// 4. 콘텐츠 추출
{"action": "extract", "session_id": "bs_a1b2c3d4", "mode": "markdown"}

// 5. 세션 종료
{"action": "close", "session_id": "bs_a1b2c3d4"}
```

### 인증 정보 참조 (credential_ref)

`credential_ref`를 사용하면 LLM Agent가 실제 비밀번호나 토큰 값을 직접 다루지 않고,
참조 키를 통해 `CredentialStore`에서 실제 값을 조회한다.

```json
// credential_ref 사용 (LLM은 실제 값을 모름)
{"action": "type", "session_id": "bs_a1b2c3d4", "selector": "#username", "credential_ref": "jira.username"}
{"action": "type", "session_id": "bs_a1b2c3d4", "selector": "#password", "credential_ref": "jira.password"}
```

형식은 `"profile.field"` (예: `"jira.password"`, `"github.token"`).
`CredentialStore`가 설정되지 않은 상태에서 `credential_ref`를 사용하면 에러를 반환한다.

### 인증 상태 저장 및 복원

```json
// 1. 로그인 수행
{"action": "open", "url": "https://example.com/login"}
{"action": "type", "session_id": "bs_a1b2c3d4", "selector": "#username", "value": "admin"}
{"action": "type", "session_id": "bs_a1b2c3d4", "selector": "#password", "value": "pass"}
{"action": "click", "session_id": "bs_a1b2c3d4", "selector": "#login-btn"}

// 2. 인증 상태 저장
{"action": "save_auth", "session_id": "bs_a1b2c3d4"}
// -> {"sessionId": "bs_a1b2c3d4", "action": "save_auth", "content": "{\"cookies\":[...],\"origins\":[...]}"}

// 3. 이후 세션에서 인증 상태 복원
{"action": "open", "url": "https://example.com/dashboard", "storage_state": "{\"cookies\":[...],\"origins\":[...]}"}
// -> 로그인 없이 인증된 상태로 시작
```

## 응답 구조

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

`candidates`는 LLM이 다음 액션을 결정할 때 사용할 수 있는 상호작용 가능한 요소 목록이다 (최대 30개).

## 원격 브라우저 연결

로컬 Chromium 실행 외에 원격 브라우저 서버 연결을 지원한다.

### 연결 모드

| 모드 | 연결 방식 | 사용 시나리오 |
|------|----------|-------------|
| `LOCAL` | `pw.chromium().launch()` | 기본값. 로컬 Chromium 프로세스 실행 |
| `REMOTE_WS` | `pw.chromium().connect(wsEndpoint)` | Playwright Server에 WebSocket 연결 |
| `REMOTE_CDP` | `pw.chromium().connectOverCDP(cdpUrl)` | Chrome DevTools Protocol 연결 |

### 사용 예시

```java
// 로컬 실행 (기존과 동일)
PlaywrightWorkerPool pool = new PlaywrightWorkerPool(2, true);

// 원격 Playwright Server 연결
PlaywrightConnectionConfig wsConfig = PlaywrightConnectionConfig.builder()
        .mode(PlaywrightConnectionMode.REMOTE_WS)
        .endpoint("ws://playwright-server:3000")
        .build();
PlaywrightWorkerPool wsPool = new PlaywrightWorkerPool(2, wsConfig);

// 원격 CDP 연결
PlaywrightConnectionConfig cdpConfig = PlaywrightConnectionConfig.builder()
        .mode(PlaywrightConnectionMode.REMOTE_CDP)
        .endpoint("http://chrome:9222")
        .build();
PlaywrightWorkerPool cdpPool = new PlaywrightWorkerPool(1, cdpConfig);
```

### 원격 연결 참고사항

- **headless 옵션**: `LOCAL` 모드에서만 적용된다. 원격 모드에서는 서버 측 설정을 따른다.
- **CDP 제약**: CDP 연결은 단일 브라우저 인스턴스에 연결하므로, Worker 수를 1로 설정하는 것을 권장한다.
- **하위 호환성**: 기존 `PlaywrightWorkerPool(int, boolean)` 생성자는 그대로 유지된다.

## 주요 설계

### 스레드 안전성

Playwright Java API는 스레드 안전하지 않다. 모든 Playwright 호출은 `PlaywrightLifecycleManager`의 전용 단일 스레드에서 실행된다.

- **세션 친화성**: 세션은 생성 시 특정 Worker에 고정되어, 동일 세션의 모든 요청이 같은 스레드에서 실행된다.
- **세션 간 병렬 처리**: 서로 다른 세션은 서로 다른 Worker에서 병렬로 실행될 수 있다.

### 세션 관리 (LRU + TTL)

`InMemoryBrowserSessionStore`는 `LinkedHashMap`(access-order)으로 LRU 동작을 구현한다.

- **LRU eviction**: 최대 세션 수 초과 시 가장 오래 사용되지 않은 세션을 제거한다.
- **TTL 만료**: 비활동 시간이 TTL을 초과한 세션은 조회 시 자동 제거된다.
- **리소스 해제**: 제거된 세션의 Playwright 리소스는 `PlaywrightWorkerPool`을 통해 비동기로 해제된다.

### SSRF 보호

`OpenActionHandler`는 `aimon-core`의 `SsrfGuard`를 사용하여 이중 검증을 수행한다.

1. **사전 검증**: 네비게이션 전 원본 URL 검사
2. **사후 검증**: 리다이렉트 후 최종 URL 검사 (실패 시 `about:blank`으로 복원)

### 권한 시스템

`BrowserToolPermissionRule`은 액션 및 URL 기반 접근 제어를 제공한다.

| 패턴 | 의미 |
|------|------|
| `Browser` | 모든 액션 허용 |
| `Browser(open:*)` | open 액션의 모든 URL 허용 |
| `Browser(open:https://example.com:*)` | 그 URL 접두사로 시작하는 open 만 허용 |
| `Browser(extract:*)` | extract 액션만 허용 |

패턴은 `:*` 로 끝나면 접두사 매칭, 아니면 완전 일치다. `Browser(open:*.example.com)` 같은 호스트 글롭은
지원되지 않는다 — 도메인으로 좁히려면 URL 접두사에 `:*` 를 붙인다.

### Multi-instance Ready

`BrowserSessionStore`는 인터페이스로 분리되어 있다. 기본 구현은 in-memory이지만, 분산 환경에서는 sticky session 라우팅과 외부 저장소 구현으로 교체 가능하다.

> **주의**: `BrowserSession`은 Playwright `BrowserContext`를 포함하므로 직렬화할 수 없다. 분산 환경에서는 `storageState`만 저장하고 context를 재생성하는 전략이 필요하다.

## 패키지 구조

```
at.aimon.browser.playwright/
├── BrowserTool                     # AbstractTool 구현체 (진입점)
├── BrowserActionDispatcher         # Map 기반 액션 라우터
├── BrowserSession                  # 세션 상태 (BrowserContext, Page)
├── BrowserSessionStore             # 세션 저장소 인터페이스
├── InMemoryBrowserSessionStore     # LRU + TTL 구현
├── PlaywrightConnectionMode        # 연결 모드 enum (LOCAL, REMOTE_WS, REMOTE_CDP)
├── PlaywrightConnectionConfig      # 불변 연결 설정 (Builder 패턴)
├── PlaywrightLifecycleManager      # 단일 Worker (Playwright + 전용 스레드)
├── PlaywrightWorkerPool            # N개 Worker 관리 (round-robin)
├── BrowserToolPermissionRule       # 권한 패턴 매칭
│
├── action/
│   ├── BrowserActionHandler        # 핸들러 인터페이스
│   ├── BrowserActionResult         # 불변 결과 객체 + Builder
│   ├── OpenActionHandler           # SSRF 사전/사후 검증
│   ├── ClickActionHandler          # selector/text/role 우선순위
│   ├── TypeActionHandler           # PII 마스킹
│   ├── PressActionHandler          # 키보드 입력
│   ├── SelectActionHandler         # 드롭다운
│   ├── ScrollActionHandler         # 스크롤
│   ├── WaitActionHandler           # 대기
│   ├── ExtractActionHandler        # text/html/markdown
│   ├── ScreenshotActionHandler     # base64 PNG
│   ├── NavigationActionHandler     # back/forward/reload
│   ├── CloseActionHandler          # 세션 종료
│   └── SaveAuthActionHandler       # 인증 상태 저장
│
└── dom/
    ├── DomCandidate                # 상호작용 요소 정보
    ├── CandidateExtractor          # 페이지 요소 수집 (최대 30개)
    └── SelectorGenerator           # CSS selector 생성 (depth 제한)
```

## 빌드 및 테스트

```bash
# 컴파일
./gradlew :aimon-browser-playwright:compileJava

# 단위 테스트 (Playwright 불필요)
./gradlew :aimon-browser-playwright:test

# 통합 테스트 (Playwright + Chromium 필요)
./gradlew :aimon-browser-playwright:playwrightTest

# 포맷팅
./gradlew format

# 품질 검사 (포맷 + 스타일 + 테스트)
./gradlew checkAll
```

통합 테스트는 `@Tag("playwright")`로 분리되어 있어 일반 테스트 실행 시 제외된다.

## 의존성

| 라이브러리 | 용도 |
|-----------|------|
| `aimon-core` | Tool 프레임워크, SsrfGuard, ContentExtractor |
| `com.microsoft.playwright` | Chromium 브라우저 자동화 |
| `com.fasterxml.jackson.databind` | JSON 직렬화 |
| `org.slf4j` | 로깅 |

## 세션 옵션

새 세션 생성 시 다음 옵션을 설정할 수 있다 (`session_id` 생략 시 자동 생성).

| 옵션 | 기본값 | 설명 |
|------|--------|------|
| `locale` | `en-US` | 브라우저 로케일 |
| `user_agent` | Chromium 기본값 | User-Agent 문자열 |
| `viewport_width` | 1280 | 뷰포트 너비 (px) |
| `viewport_height` | 720 | 뷰포트 높이 (px) |
| `resource_policy` | `minimal` | `minimal`: 이미지/폰트/미디어/CSS 차단, `visual`: 전체 로드 |
| `storage_state` | - | `save_auth`로 획득한 인증 상태 JSON (cookies + localStorage) |
| `timeout_ms` | 30000 | 액션 타임아웃 (1000-120000ms) |
