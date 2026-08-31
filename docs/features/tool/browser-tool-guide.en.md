---
translated_from: docs/features/tool/browser-tool-guide.md
source_commit: 5246ba48
---

# Browser Tool Guide

> The complete guide to configuring and assembling BrowserTool, and to using each of its actions

## Table of contents

1. [Overview](#overview)
2. [Configuration and assembly](#configuration-and-assembly)
3. [Session management](#session-management)
4. [Using each action](#using-each-action)
5. [The response structure](#the-response-structure)
6. [The permission system](#the-permission-system)
7. [Resource policies](#resource-policies)
8. [Error handling](#error-handling)
9. [Shutdown and cleanup](#shutdown-and-cleanup)

---

## Overview

`BrowserTool` is the Tool that lets an LLM agent automate a web browser.
It is built on Playwright Java (Chromium headless) and supports 13 browser actions.

The tool is named `"Browser"`, and the `action` parameter selects the operation.
Each call performs one action and returns the page state plus a list of interactable elements as JSON.

---

## Configuration and assembly

### Gradle dependency

```kotlin
// build.gradle.kts
dependencies {
    implementation(project(":aimon-browser-playwright"))
}
```

### Assembling the components

BrowserTool needs four core dependencies.

```java
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.browser.playwright.*;
import at.aimon.browser.playwright.action.*;
import at.aimon.core.ext.tools.web.fetch.ContentExtractor;
import at.aimon.core.ext.tools.web.security.SsrfGuard;

// 1. the Playwright worker pool (manages the Chromium processes)
//    workerCount: the number of workers (roughly 200-500MB of memory each)
//    headless: true runs without a UI
PlaywrightWorkerPool workerPool = new PlaywrightWorkerPool(2, true);

// 2. the session store (LRU + TTL)
//    maxSessions: the maximum number of concurrent sessions
//    sessionTtl: how long an inactive session lives before it expires
InMemoryBrowserSessionStore sessionStore =
        new InMemoryBrowserSessionStore(10, Duration.ofMinutes(30), workerPool);

// 3. SSRF protection (provided by aimon-core)
SsrfGuard ssrfGuard = new SsrfGuard();

// 4. the content extractor (provided by aimon-core, for markdown mode)
ContentExtractor contentExtractor = new ContentExtractor();

// 5. register the action handlers
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

// 6. create the dispatcher
BrowserActionDispatcher dispatcher = new BrowserActionDispatcher(handlers);

// 7. create the BrowserTool
ObjectMapper objectMapper = new ObjectMapper();
BrowserTool browserTool = new BrowserTool(
        dispatcher, sessionStore, workerPool, objectMapper);
```

### Connecting to a remote browser

You can connect to a remote Playwright Server or to Chrome over CDP.

```java
import at.aimon.browser.playwright.PlaywrightConnectionConfig;
import at.aimon.browser.playwright.PlaywrightConnectionMode;

// a remote Playwright Server (WebSocket)
PlaywrightConnectionConfig wsConfig = PlaywrightConnectionConfig.builder()
        .mode(PlaywrightConnectionMode.REMOTE_WS)
        .endpoint("ws://playwright-server:3000")
        .build();
PlaywrightWorkerPool workerPool = new PlaywrightWorkerPool(2, wsConfig);

// a remote Chrome over CDP
PlaywrightConnectionConfig cdpConfig = PlaywrightConnectionConfig.builder()
        .mode(PlaywrightConnectionMode.REMOTE_CDP)
        .endpoint("http://chrome:9222")
        .build();
PlaywrightWorkerPool workerPool = new PlaywrightWorkerPool(1, cdpConfig);
```

The rest of the assembly is identical to running locally (the session store, the handlers, the dispatcher and so on).

### Registering it as an OrcaToolProvider

To integrate it into aimon-core's agent framework, implement `OrcaToolProvider`.

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

To use it, add it to `defaultToolProviders()`:

```java
List<OrcaToolProvider> providers = List.of(
        // the existing providers...
        new OrcaFileToolProvider(),
        new OrcaBashToolProvider(),
        // add the Browser Tool
        new OrcaBrowserToolProvider(2, 10, Duration.ofMinutes(30))
);
```

---

## Session management

### Creating a session

Omit `session_id` and a new session is created automatically.
The session id is the prefix `"bs_"` plus 8 characters of a UUID (for example `bs_a1b2c3d4`).

```json
{
  "action": "open",
  "url": "https://example.com"
}
```

Reuse the response's `sessionId` value in subsequent calls.

### Session options

The following options can be given when creating a new session.

| Option | Default | Description |
|--------|---------|-------------|
| `locale` | `en-US` | the browser locale (for example `ko-KR`, `ja-JP`) |
| `user_agent` | Chromium's default | the User-Agent string |
| `viewport_width` | `1280` | the viewport width (px) |
| `viewport_height` | `720` | the viewport height (px) |
| `resource_policy` | `minimal` | the resource-loading policy (see [Resource policies](#resource-policies)) |

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

### Reusing a session

To perform successive operations in the same session, pass `session_id`.

```json
{"action": "open", "url": "https://example.com"}
// -> sessionId: "bs_a1b2c3d4"

{"action": "click", "session_id": "bs_a1b2c3d4", "selector": "#login"}
{"action": "type", "session_id": "bs_a1b2c3d4", "selector": "#email", "value": "user@test.com"}
{"action": "close", "session_id": "bs_a1b2c3d4"}
```

### Session lifetime

- **LRU eviction**: once the maximum session count (`maxSessions`) is exceeded, the least recently used session is removed automatically.
- **TTL expiry**: a session inactive for `sessionTtl` or longer expires automatically when it is looked up.
- **Explicit close**: a session can be closed with the `close` action.

---

## Using each action

### open — URL navigation

Navigates the page to a URL. SSRF checks are performed both before and after.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `url` | **O** | - | the URL to navigate to |
| `wait_until` | | `domcontentloaded` | the wait strategy: `domcontentloaded`, `load`, `networkidle` |

```json
{"action": "open", "url": "https://example.com"}
{"action": "open", "url": "https://example.com", "wait_until": "networkidle"}
```

**Response**: `url`, `title`, `candidates`

---

### click — clicking an element

Clicks an element on the page. The locator is decided in this order of priority: `selector` > `role`+`text` > `text`.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `selector` | | - | a CSS selector |
| `text` | | - | find the element by its displayed text |
| `role` | | - | an ARIA role (for example `button`, `link`, `tab`) |
| `exact` | | `false` | true matches the text exactly |

At least one of the three is required.

```json
{"action": "click", "session_id": "bs_xxx", "selector": "#submit-btn"}
{"action": "click", "session_id": "bs_xxx", "text": "Sign in"}
{"action": "click", "session_id": "bs_xxx", "role": "button", "text": "Submit", "exact": true}
```

**Response**: `url`, `title`, `candidates`

---

### type — entering text

Types text into an input field.
For password-related fields the value is masked as `***` in the logs.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `selector` | | - | the target CSS selector |
| `text` | | - | find the element by its displayed text |
| `value` | **O** | - | the text to enter |
| `clear` | | `false` | true clears the existing value before typing |

One of `selector` or `text` is required.

```json
{"action": "type", "session_id": "bs_xxx", "selector": "#email", "value": "user@test.com"}
{"action": "type", "session_id": "bs_xxx", "selector": "#search", "value": "aimon", "clear": true}
```

**Response**: `url`, `title`, `candidates`

---

### press — pressing a keyboard key

Presses a keyboard key.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `key` | **O** | - | the key name (for example `Enter`, `Tab`, `Escape`, `ArrowDown`) |

```json
{"action": "press", "session_id": "bs_xxx", "key": "Enter"}
{"action": "press", "session_id": "bs_xxx", "key": "Tab"}
```

**Response**: `url`, `title`, `candidates`

---

### select — choosing from a dropdown

Selects an option in a `<select>` element.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `selector` | **O** | - | the CSS selector of the `<select>` element |
| `value` | **O** | - | the value of the `<option>` to select |

```json
{"action": "select", "session_id": "bs_xxx", "selector": "#country", "value": "KR"}
```

**Response**: `url`, `title`, `candidates`

---

### scroll — scrolling the page

Scrolls the page up or down.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `direction` | **O** | - | `up` or `down` |
| `amount` | | `500` | how far to scroll (px) |

```json
{"action": "scroll", "session_id": "bs_xxx", "direction": "down"}
{"action": "scroll", "session_id": "bs_xxx", "direction": "up", "amount": 1000}
```

**Response**: `url`, `title`, `candidates`

---

### wait — waiting

Waits for a given time, or until a particular element appears.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `selector` | | - | the CSS selector of the element to wait for (given, it waits for the element) |
| `wait_ms` | | `1000` | how long to wait (ms). Cannot exceed `timeout_ms` |

```json
{"action": "wait", "session_id": "bs_xxx", "wait_ms": 2000}
{"action": "wait", "session_id": "bs_xxx", "selector": "#result-panel"}
```

**Response**: `url`, `title`, `candidates`

---

### extract — extracting content

Extracts the page content as text, HTML or Markdown.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `mode` | | `text` | the extraction mode: `text`, `html`, `markdown` |
| `max_chars` | | `50000` | the maximum number of characters to return |
| `selector` | | - | extract only a particular region (the whole page if omitted) |

```json
{"action": "extract", "session_id": "bs_xxx"}
{"action": "extract", "session_id": "bs_xxx", "mode": "markdown", "max_chars": 10000}
{"action": "extract", "session_id": "bs_xxx", "mode": "html", "selector": "#article-body"}
```

**Response**: `url`, `title`, `content`

---

### screenshot — taking a screenshot

Captures a screenshot of the page and returns it as a base64 PNG.

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `full_page` | | `false` | true captures the whole page including what is scrolled out of view |
| `selector` | | - | capture only a particular element |

```json
{"action": "screenshot", "session_id": "bs_xxx"}
{"action": "screenshot", "session_id": "bs_xxx", "full_page": true}
{"action": "screenshot", "session_id": "bs_xxx", "selector": "#chart"}
```

**Response**: `url`, `title`, `screenshot` (a base64 PNG)

> **Caution**: in a session whose `resource_policy` is `minimal`, images and CSS are blocked, so the screenshot may be inaccurate. In that case a warning is included in the `warnings` field. If you need an accurate screenshot, create the session with `resource_policy=visual`.

---

### back / forward / reload — navigation

Performs the browser's back, forward and reload operations.

```json
{"action": "back", "session_id": "bs_xxx"}
{"action": "forward", "session_id": "bs_xxx"}
{"action": "reload", "session_id": "bs_xxx"}
```

**Response**: `url`, `title`, `candidates`

---

### close — ending the session

Closes the session and releases the Playwright resources.

```json
{"action": "close", "session_id": "bs_xxx"}
```

**Response**: `sessionId`, `action`, `message`

---

## The response structure

Every action's response is in the `BrowserActionResult` JSON format.
Null fields are omitted.

### A success response

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

### An error response

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

An error response can carry `candidates` too, which the LLM can use to pick an alternative element.

### Field reference

| Field | Type | Description |
|-------|------|-------------|
| `sessionId` | string | the session id |
| `action` | string | the action that was performed |
| `url` | string | the current page URL |
| `title` | string | the current page title |
| `error` | string | the error code (null means success) |
| `message` | string | a human-readable message |
| `content` | string | the extracted content (the extract action) |
| `screenshot` | string | a base64-encoded PNG (the screenshot action) |
| `candidates` | array | the list of interactable elements (at most 30) |
| `warnings` | array | warning messages |

### The structure of candidates

`candidates` is the list of elements the LLM uses to decide the target of its next action.

```json
{
  "label": "Submit Form",
  "selector": "#submit-btn",
  "role": "button",
  "text": "Submit"
}
```

| Field | Description |
|-------|-------------|
| `label` | the element's displayed text (at most 80 characters) |
| `selector` | a CSS selector (usable directly in the next action) |
| `role` | the ARIA role or tag kind (`link`, `button`, `textbox` …) |
| `text` | the placeholder or aria-label |

What is collected: `a`, `button`, `[role=button]`, `input`, `select`, `textarea`, `[role=link]`, `[role=tab]`, `[role=menuitem]`

---

## The permission system

BrowserTool implements `CustomToolPermissionAware` and controls access through allow patterns (AllowedTool).

### Allow patterns

| Pattern | Meaning |
|---------|---------|
| `Browser` | allows every action and every URL |
| `Browser(open:*)` | allows the open action for every URL |
| `Browser(open:https://example.com:*)` | allows the open action only for that URL prefix |
| `Browser(extract:*)` | allows only the extract action |
| `Browser(screenshot:*)` | allows only the screenshot action |
| `Browser(click:*)` | allows only the click action |

What is matched is the string `action` or `action:url`, and the pattern follows the `ToolPattern` rules exactly —
**a trailing `:*` is a prefix match, anything else is an exact match**. Host globs are therefore not supported:
`Browser(open:*.example.com)` matches only when the URL is literally `*.example.com`, so in practice it allows
nothing at all. To narrow by domain, use a **URL prefix plus `:*`** as in the table above. That notation includes
the scheme, so `https://example.com` and `http://example.com` are separate entries.

### Configuration examples

Control it through allowedTools in the agent configuration:

```java
// allow every browser operation
AllowedTool.of("Browser")

// allow navigation only to a particular URL prefix, extraction unrestricted
AllowedTool.of("Browser(open:https://example.com:*)")
AllowedTool.of("Browser(extract:*)")

// read-only (allow open + extract only)
AllowedTool.of("Browser(open:*)")
AllowedTool.of("Browser(extract:*)")
AllowedTool.of("Browser(scroll:*)")
AllowedTool.of("Browser(wait:*)")
AllowedTool.of("Browser(close:*)")
```

---

## Resource policies

When creating a session, `resource_policy` sets the browser's resource-loading strategy.

### `minimal` (the default)

Blocks images, fonts, media and CSS so that text can be extracted quickly.

- **Advantage**: fast page loads, low bandwidth use
- **Suitable for**: text extraction, filling in forms, following links
- **Caution**: screenshots may be inaccurate

### `visual`

Loads every resource normally.

- **Advantage**: accurate screenshots, identical to a real user's environment
- **Suitable for**: capturing screenshots, visual verification, interactions that depend on CSS

```json
{"action": "open", "url": "https://example.com", "resource_policy": "visual"}
```

---

## Error handling

### Error codes

| Code | When it occurs |
|------|----------------|
| `SSRF_BLOCKED` | the URL was blocked by the SSRF security policy |
| `NAVIGATION_FAILED` | the page failed to load (a network error, a bad URL …) |
| `ELEMENT_NOT_FOUND` | no element was found for the given selector/text/role |
| `INVALID_PARAMETER` | a required parameter is missing, or a value is wrong |
| `INVALID_MODE` | the extract mode is not text/html/markdown |
| `UNKNOWN_ACTION` | an unsupported action |
| `TIMEOUT` | the wait action timed out |
| `CLICK_FAILED` | the click failed |
| `TYPE_FAILED` | typing the text failed |
| `SCROLL_FAILED` | the scroll failed |
| `EXTRACT_FAILED` | extracting the content failed |
| `SCREENSHOT_FAILED` | capturing the screenshot failed |

### ToolResult-level errors

Besides the BrowserActionResult JSON, there are higher-level errors returned as `ToolResult.error()`.

| Message pattern | Cause |
|-----------------|-------|
| `Invalid parameter: ...` | a required parameter is missing, the session was not found, or the session count was exceeded |
| `Action timed out` | `timeout_ms` was exceeded |
| `Unexpected error: ...` | an unexpected internal error |

### timeout_ms

Every action accepts a `timeout_ms` parameter (default: 30000ms).
The range is clamped to 1000ms ~ 120000ms.

```json
{"action": "open", "url": "https://slow-site.com", "timeout_ms": 60000}
```

---

## Shutdown and cleanup

### Closing a session

An individual session is closed explicitly with the `close` action.

```json
{"action": "close", "session_id": "bs_xxx"}
```

### Releasing every resource

At application shutdown, every resource is cleaned up through `AutoCloseable`.
The cleanup order is the session store, then the worker pool.

```java
// using try-with-resources
try (PlaywrightWorkerPool pool = new PlaywrightWorkerPool(2, true);
     InMemoryBrowserSessionStore store =
             new InMemoryBrowserSessionStore(10, Duration.ofMinutes(30), pool)) {

    // use the BrowserTool...

} // cleaned up automatically in the order store.close() -> pool.close()

// or close explicitly
sessionStore.close();    // removes every session and releases the resources
workerPool.shutdown();   // shuts down every worker
```

---

## Complete workflow examples

### Extracting data after logging in

```
1. open   → https://app.example.com/login
2. type   → #email, "user@example.com"
3. type   → #password, "********"
4. click  → text: "Sign In"
5. wait   → selector: "#dashboard"
6. extract → mode: markdown
7. close
```

### Collecting search results

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

### Capturing a screenshot

```
1. open   → https://dashboard.example.com, resource_policy: visual
2. wait   → wait_ms: 3000
3. screenshot → full_page: true
4. close
```

---

## Related documents

- [Tool development guide](tool-development-guide.en.md)
- [SOLID Principles](../../project/solid-principles.md)
- [aimon-browser-playwright README](../../../modules/aimon-browser-playwright/README.md)
