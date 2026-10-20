# Design — split LLM cancellation `abort()` from stream `close()`

> Status: **IMPLEMENTED** (2026-10-20) — `aimon-llm-openai` (`OpenAIAbortableHttpClient`, `OpenAICallAbort`,
> `OpenAIAbortRegistry`, `OpenAICallAbortedException`, `OpenAIStreamHandle`, the two exchanges, both streaming mappers,
> `OpenAILlmClient`), `aimon-llm-anthropic` (`AnthropicAbortableHttpClient`, `AnthropicCallAbort`,
> `AnthropicAbortRegistry`, `AnthropicCallAbortedException`, `AnthropicStreamingMapper`, `AnthropicLlmClient`),
> `aimon-llm-testkit` (`StallingSseServer`) and `aimon-core` (Javadoc only). This is the design that refines the
> committed plan [`cancellation-abort-close.md`](cancellation-abort-close.md); the normative text now lives in
> [`cancellation.md`](cancellation.md) §4.
>
> **[§11](#11-where-the-implementation-departed-from-this-design), appended after the build, is where the
> implementation departs from this document.** Everything between this header and §11 is the body as approved in
> design review round 2 (PASS, no blocking findings, nine non-blocking notes), kept byte-exact rather than corrected —
> the house habit in this directory ([`../README.md`](../README.md#34-승인된-설계를-그대로-커밋한-기록)). Its
> `file:line` citations are at `c2fa8fb8`, as its first paragraph says. The review transcripts (`review-1.md`,
> `review-2.md`) and the run record the body's wording comes from (`TASK.md`) are not in the repository; §11 says
> which review notes were taken.
>
> It is in English, like [`../hook/hook-cancellation-followups-ee93-ee97.md`](../hook/hook-cancellation-followups-ee93-ee97.md):
> `docs/design/` is not a translation target (`docs/project/documentation-guide.md` §5.1). The open questions of §10
> that outlive this change are registered in [`../../backlog/interrupt-open-items.md`](../../backlog/interrupt-open-items.md)
> (items 6–9).

Task: `TASK.md` in this directory. Committed plan this refines: `docs/design/llm/cancellation-abort-close.md`
(commit 044b6b9f). Base: `c2fa8fb8` (main). SDKs on the classpath: openai-java 4.78.1, anthropic-java 2.71.0,
OkHttp 5.5.0 and Okio 3.18.1 (both provider modules; see §2.4).

Revision 2 — after `review-1.md`: §4.1 policy corrected (the review's blocking finding), §2.2 root-cause narrative
corrected, §4.4 wording, `inFlightCalls()` defined, Anthropic dispatcher difference recorded (§4.5), OpenAI hook sketch
closes the response on every failure, provider-neutral core Javadoc, JDK-only testkit with a latch-gated terminator.

This document keeps the plan's scope, contract and test table, and resolves the three things the plan left open:
the transport spike (§3), the two policies (§4), and the regression harness (§8). Where it corrects the plan it says so
(§2.1, §4.4).

---

## 1. Problem

Both LLM clients register `StreamResponse.close()` as the cancellation lever (`OpenAILlmClient` via
`OpenAIStreamHandle::close`, `AnthropicLlmClient` via `streamResponse::close`). That lever runs on the cancelling
thread while the consumer thread is blocked in `BufferedReader.readLine()` on the same stream. In openai-java 4.78.1
`close()` closes the OkHttp response body — which on HTTP/1.1 *reads* the socket to drain it (`ChunkedSource.close()`
→ `discard()` → `skipAll()`), tripping Okio's `AsyncTimeout` guard (`IllegalStateException("Unbalanced enter/exit")`)
— and then takes the `BufferedReader` lock the reader holds, so the cancelling thread blocks until the read unwinds. The
lever is also registered only *after* the stream has opened, so a cancellation during the header wait is not observed
until headers arrive, and because the SDK's `RetryingHttpClient` retries every `IOException`, a transport abort that
did land during an attempt could be answered with a fresh request. The fix is to give each call an `abort()` that cuts
only that call's transport from any thread, keep `close()` on the consumer thread, register the abort before the
request is sent, and make the SDK's retry loop refuse to re-send an aborted call.

---

## 2. Facts verified against the SDK sources (refining plan §2)

### 2.1 `StreamHandler.close()` — confirmed, with one correction

- openai-java 4.78.1 `com.openai.core.handlers.StreamHandler`: `close()` = `sequence.close()` → `closeOnceResponse.close()`
  (→ `body.close()`) → `reader.close()`. The response adapter in `openai-java-client-okhttp`
  (`Response.toHttpResponse()`) is `close() = body!!.close()`. Nothing cancels the `okhttp3.Call`. **Confirmed.**
- anthropic-java 2.71.0 `StreamHandler`: same order (`sequence.close()` → `response.close()` → `reader.close()`), with the
  comment "Close the response first: it unblocks a read in flight on another thread, which holds `reader`'s lock."
  **Correction to plan §2.1:** the Anthropic *OkHttp adapter* (`anthropic-java-client-okhttp` `OkHttpClient.kt`) wraps
  the body in `ResponseBodyInputStream(body, call)` whose Javadoc reads: "A response body that one thread can close
  while another thread reads it. … a close that races a read would throw and leave the read blocked. Such a close
  cancels [call] instead, which fails the read, and the reading thread closes the body once its read unwinds. A close
  with no read in flight closes the body directly." So for Anthropic the body-close race is already fixed in the SDK;
  what remains wrong there is (a) `reader.close()` still takes the reader lock on the cancelling thread (bounded by the
  cancel, but a blocking wait), (b) no lever exists during the header wait, (c) the retry re-send. For OpenAI
  everything in the plan stands.
- openai-java upstream: fetched `openai-java/main` `OkHttpClient.kt` from GitHub and Maven Central's
  `maven-metadata.xml` on 2026-10-20 (latest 4.86.0, released 2026-10-19): `Response.toHttpResponse()` is still
  `close() = body!!.close()`, no `ResponseBodyInputStream`, no CHANGELOG entry about close/read races. **Bumping
  openai-java does not fix the body phase.** (Network check done by the author; the reviewer did not repeat it.)

### 2.2 Why body close from another thread fails — confirmed from OkHttp 5.5.0 / Okio 3.18.1

`Http1ExchangeCodec.ChunkedSource.close()`: `if (hasMoreChunks && !discard(100 ms)) { carrier.noNewExchanges(); … }`.
`discard` → `Source.skipAll()` puts a 100 ms deadline on the *shared* socket `AsyncTimeout` and reads from the source
through `AsyncTimeout.withTimeout`, which calls `enter()` **before** its `try`; `enter()` is
`check(state == STATE_IDLE) { "Unbalanced enter/exit" }` (`AsyncTimeout.kt:63` in 3.18.1, identical in 3.7.0). With the
consumer thread inside `read()` (state `IN_QUEUE`) the closing thread throws `IllegalStateException` out of
`discard` (which catches only `IOException`), out of `ChunkedSource.close()` before `closed = true`, and the
`StreamHandler`'s `finally { reader.close() }` then blocks the cancelling thread on the reader lock. Because `enter()`
failed before the `try`, nothing was scheduled: `insertIntoQueue → setTimeoutAt` runs only on a successful `enter()`, and
`skipAll`'s `finally` restores/clears the deadline. **The consumer's read keeps its original watchdog — the socket read
timeout, 60 s by `OpenAIConfig.DEFAULT_TIMEOUT`.** So on the base commit the race has two arms: `close()` arriving while a
read is in flight hangs the consumer (and the cancelling thread) until the read timeout; `close()` arriving while no
read is in flight runs `discard` for ≤ 100 ms on the cancelling thread, and `CloseableSequence.isClosed` ends the
sequence before the next read. Today's `*StreamAbortTest`s pass by the second arm (or pass late by the first). None of
this touches `RealCall.cancel()`: `exchange?.cancel()` closes the socket, no source is read, and the blocked read fails
with `IOException("Canceled")`. **Only `Call.cancel()` is a sanctioned cross-thread lever.**

### 2.3 Retry — confirmed, with the exact exception sets

- openai `RetryingHttpClient.shouldRetry(Throwable)`: `IOException || OpenAIIoException || OpenAIRetryableException`.
- anthropic: `IOException || AnthropicIoException || AnthropicRetryableException` after unwrapping `CompletionException`.
- Both: default `maxRetries = 2`; the sync path sleeps with the synchronous `Sleeper.sleep()` (`Thread.sleep`); the
  retry loop sits **above** the `HttpClient` the options were built with, so a per-attempt hook placed at that
  `HttpClient` is called for every attempt including retries (anthropic's `ClientOptions.interceptors` Javadoc says the
  same of interceptors).
- Both sync service paths (`…ServiceImpl.WithRawResponseImpl.createStreaming`) call
  `clientOptions.httpClient.execute(request, requestOptions)` on the **caller's thread**; `params._headers()`
  (additional headers) are copied onto the `HttpRequest` by `prepare()` (`replaceAllHeaders(params._headers())`).

### 2.4 Transport levers — what each SDK exposes publicly

| | openai-java 4.78.1 | anthropic-java 2.71.0 |
|---|---|---|
| Inject own `HttpClient` | `ClientOptions.builder().httpClient(…)` (public, "implement your own") + `OpenAIClientImpl(ClientOptions)` (public ctor). `OpenAIOkHttpClient.Builder` has no `httpClient()` | same: `ClientOptions.builder().httpClient(…)` + `AnthropicClientImpl(ClientOptions)`; plus a public `Interceptor` SPI (`addInterceptor`) applied per attempt |
| SDK OkHttp adapter constructible | `OkHttpClient.builder()…build()` public; ctor `internal` | `OkHttpClient.builder()…backend(…).build()` public; ctor `private` |
| `executeAsync` future cancel → `call.cancel()` | yes (`whenComplete { if (e is CancellationException) call.cancel() }`) — header phase only; a completed future cannot be cancelled | same |
| Cross-thread-safe `HttpResponse.close()` | **no** (`body!!.close()`) | **yes** (`ResponseBodyInputStream`: cancels the call if a read is in flight, else closes the body) |
| Async service path | `AsyncStreamResponse` driven on `streamHandlerExecutor`; `RetryingHttpClient.executeAsync` returns a *composed* future — cancelling it does not reach the transport future for repeatable (JSON) bodies | same shape |
| SSE termination the handler sees | `SseHandler` **breaks at `data: [DONE]`** (`markBodyComplete(); close(); break`) — the handler stops reading on its own when the endpoint sends the sentinel; otherwise it reads to EOF | no sentinel: `for (line in lines)` **reads to EOF** after `message_stop` |
| OkHttp / Okio actually on the module runtime classpath | 5.5.0 / 3.18.1 (aimon-core declares `libs.okhttp`; the SDK asks for 4.12.0 and is conflict-resolved up) | 5.5.0 / 3.18.1 (same) |

`ClientOptions.Builder.httpClient()` wraps the given client in `PhantomReachableClosingHttpClient` and "takes
ownership and closes it when closed", so an injected client is closed by `AnthropicLlmClient.close()` today and by
phantom-reachability for OpenAI.

### 2.5 Where the three mappers emit `STREAM_END` — the fact §4.1 turns on

| Mapper | Terminal event recorded | `STREAM_END` emitted |
|---|---|---|
| `AnthropicStreamingMapper` | `streamEnded = true` on `message_stop` | **inside the loop**, at `message_stop` (`onEvent` → `emitStreamEnd()`, lines 178–180 / 352–364). The SDK then keeps reading to EOF, so the consumer re-enters `readLine()` for the chunk terminator *after* the sink has been told the answer is whole |
| `OpenAIStreamingMapper` (Chat) | `lastFinishReason` on the `finish_reason` chunk | **after the loop** (`consume`, lines 90–98). Usage may trail `finish_reason` in its own chunk; the SDK stops at `[DONE]` |
| `OpenAIResponsesStreamingMapper` | `terminalSeen = true` on `response.completed` / `incomplete` / `failed` (line 243) | **after the loop** (lines 123–131) |

---

## 3. Transport spike — decision

### 3.1 Decision

**Inject our own `HttpClient` into each SDK through `ClientOptions.httpClient(…)`, assembling `ClientOptions` +
`*ClientImpl` directly instead of through `OpenAIOkHttpClient` / `AnthropicOkHttpClient` builders. The injected client
is the per-attempt hook: it refuses an attempt once the call is aborted, and it attaches the attempt's transport lever
to a per-call abort object. The two modules differ only in what that lever is, because the SDKs differ (§2.4):**

- **OpenAI — own OkHttp transport.** `OpenAIAbortableHttpClient implements com.openai.core.http.HttpClient` over an
  `okhttp3.OkHttpClient` the module builds itself (replicating what `com.openai.client.okhttp.OkHttpClient.Builder`
  does for the settings AIMON uses). It creates the `okhttp3.Call` in `execute()`, so the per-call abort holds the
  `Call` for the whole attempt — header wait *and* body — and `abort()` is exactly `call.cancel()`. This is forced:
  the OpenAI adapter has no cross-thread-safe body-phase lever and the async future only covers headers (§2.1, §2.4).
- **Anthropic — thin decorator over the SDK's own adapter.** `AnthropicAbortableHttpClient implements
  com.anthropic.core.http.HttpClient` wraps `com.anthropic.client.okhttp.OkHttpClient` built with its public builder.
  Header phase: `delegate.executeAsync(…)` + `future.cancel(true)` → the adapter cancels its `Call`. Body phase: keep
  the adapter's `HttpResponse` and call its `close()` — the adapter's documented cross-thread-safe close (cancel if a
  read is in flight, body close otherwise). No okhttp3 types, no duplicated transport. A cancellable call's header
  phase thereby runs through OkHttp's `Dispatcher` — an accepted difference recorded in §4.5; a request with no abort
  registered goes to the adapter's synchronous `execute` unchanged.

Both modules share the same shape above the transport: a per-call **`*CallAbort`** object (state machine,
`abort()` thread-safe / idempotent / non-blocking / no-throw) looked up by the hook through a correlation **request
header** that the client puts on the SDK params and the hook strips before the wire (§6.3). The client creates the
abort object and registers `cancellation.onCancel(abort::abort)` **before** calling `createStreaming(…)`, so the
header wait and every SDK retry attempt are covered; `OpenAIStreamHandle` gains `abort()` delegating to that object
and keeps `close()` for the consumer thread.

### 3.2 Alternatives rejected

| Alternative | Why rejected |
|---|---|
| (plan a) async service path + `CompletableFuture.cancel(true)` | `RetryingHttpClient.executeAsync` returns a future composed with `handleAsync…thenCompose`; cancelling it does not propagate to the transport future for repeatable (JSON) bodies — the SDK's own code special-cases only multipart ("Nonrepeatable requests previously returned the transport future directly"). Even where it works it covers the header phase only; after headers the future is complete and `cancel()` is a no-op. It would also move stream consumption onto `streamHandlerExecutor` threads, changing the client's threading and the mappers' contract |
| Keep `StreamResponse.close()` as the lever, serialise it with a lock | A lock either blocks the cancelling thread until the read unwinds (the contract violation we are removing) or does nothing for the read already in flight; the body `discard()` race (§2.2) is inside OkHttp and cannot be locked around from outside |
| Keep `StreamResponse.close()` but swallow its exceptions | Leaves the consumer hung until the read timeout in the in-flight-read arm (§2.2), and the reader lock wait remains. Plan §4 forbids both |
| Anthropic: `Interceptor` SPI instead of replacing `httpClient` | An interceptor's `nextClient` is `LoggingHttpClient(raw)`; `LoggingHttpClient.executeAsync` returns a derived future (`future.handle{}`), so `cancel(true)` on it would not reach the adapter's `call.cancel()`. Replacing `httpClient` puts the hook directly over the raw adapter, whose future *does* cancel the call. (The interceptor route would also need the builder bypass for nothing in return) |
| Anthropic: own OkHttp transport, for symmetry with OpenAI | Duplicates an adapter the SDK already made cross-thread safe, plus its `Backend` plumbing (`prepareRequest`/`authorizeRequest`/`prepareResponse`, URL resolution, `pingInterval`). More surface to re-verify on every SDK bump, for a lever the decorator already has |
| OpenAI: construct `com.openai.client.okhttp.OkHttpClient(okhttp3Client)` via its Kotlin-`internal` constructor and capture `chain.call()` with an okhttp3 interceptor | Relies on a non-API constructor (Kotlin `internal` is public only in bytecode); breaks silently on an SDK signature change. Also impossible for Anthropic (private ctor), so no symmetry gained |
| OpenAI: bump openai-java and wait for the Anthropic-style adapter fix | Not in 4.86.0 (2026-10-19); the timing is not ours. Noted as a follow-up (§10): once it lands, the OpenAI transport collapses into the Anthropic decorator shape |
| Reflection into SDK/OkHttp internals (`Dispatcher.runningCalls()`, `RealCall.AsyncCall`) | Internal APIs; rejected on principle |
| Pump thread: read the socket on a helper thread, let the consumer wait on a queue the abort can poison | The abort unblocks the *consumer*, not the socket read; the helper stays blocked until the server speaks, the server keeps generating (billing) tokens, and the body close still races the helper's read. Defeats the point of active abort |
| Per-call `HttpClient` through `service.withOptions(…)` | `ClientOptions.httpClient()` takes ownership and closes the client it is given; a per-call wrapper over the shared raw client would have its phantom-reachable cleanup shut the shared dispatcher down |
| Correlate hook ↔ call with a `ThreadLocal` instead of a header | Works today (sync path runs on the caller thread, §2.3) but couples silently to the SDK's threading; a change would drop to iteration-boundary cancellation with no error. The header is explicit, stripped before the wire, and its absence on the wire is asserted by a test |
| Custom `Sleeper` to cut the SDK backoff sleep short | The sleeper has no request context (which call's abort?); a client-wide early wake would shorten other calls' `Retry-After` waits. Gap accepted and documented (§4.3); gateway retries are already cancellation-aware |
| §4.1: mark the abort `COMPLETED` at the terminal event so a late abort does nothing (reviewer's option b) | Leaves a stalled trailing read (server never sends the chunk terminator after `message_stop`) uncut until the read timeout. Option (a) — cut the transport, then classify the resulting read failure as "after the terminal event" — ends the call immediately *and* returns the complete answer |

---

## 4. Policies decided

### 4.1 Cancellation that arrives around the terminal event — corrected after review-1

**Rule: once the mapper has recorded the stream's terminal event, the answer is complete, and nothing that happens to
the transport afterwards can un-complete it.** Concretely, in every mapper's `consume(stream, cancellation)`:

| Stream end, as the mapper observes it | Terminal event recorded | Result |
|---|---|---|
| normal (loop ends) | yes | complete `LlmResponse`; `STREAM_END` exactly once (unchanged) |
| normal | no, token tripped | `LlmCallCancelledException`, no `STREAM_END` (unchanged) |
| normal | no, token live | provider ended early: existing fallbacks unchanged (Anthropic synthetic `STREAM_END`, OpenAI `STREAM_END`) |
| **SDK transport failure** (`OpenAIIoException` / `AnthropicIoException`) escapes the loop | **yes** | **swallowed by the mapper** — logged at DEBUG when the token is tripped, WARN otherwise; `STREAM_END` emitted if not yet; complete `LlmResponse` returned |
| SDK transport failure escapes the loop | no | propagates to the client's cascade: `LlmCallCancelledException` (cause = the transport failure) if the token is tripped, provider failure otherwise (unchanged) |
| any other exception (`SseException` = provider error payload, mapping bugs) | any | propagates unchanged — the catch is narrowed to the SDK's I/O exception types |

Invariant the row-3 test pins: **the sink received `STREAM_END` ⇔ the call returned an `LlmResponse`; no `STREAM_END` ⇔
the call threw.** This is the lifecycle contract of `streaming.md` ("provider 오류에서는 스트리밍 호출 자체가 던지고
sink 는 `STREAM_END` 를 받지 못한다"; `AssistantTextStreamCompleted` exactly once, `STREAM_END` *or* interrupted, never
both) and it holds in every row above.

Why the first draft's uniform "exception ⇒ cancellation" rule was wrong (review-1): `AnthropicStreamingMapper` emits
`STREAM_END` *inside* the loop at `message_stop`, and the SDK's `sseHandler` keeps reading to EOF, so the consumer
re-enters `readLine()` for the chunk terminator after the sink has been told the answer is whole (§2.5). An abort in that
window cuts the read; `IOExceptionWrappingSequence` wraps it in `AnthropicIoException`, `CloseableSequence.hasNext`
rethrows it because this design never calls `sequence.close()` from the abort thread, and the client's cascade would
have turned a complete, billed, already-announced answer into `LlmCallCancelledException` — a third outcome, a
lifecycle-contract violation, and a behaviour change from the base commit (where the lever's `sequence.close()` set
`isClosed` and the post-close failure was swallowed). The rule above is what the base commit does for its own close,
generalised to any transport failure after the terminal event.

Per-endpoint consequences:

- **Anthropic**: the window exists on every call (EOF-terminated protocol); the rule returns the complete response, as
  base does. A trailing *reset* (not an abort) after `message_stop` also returns the response now — on base it was a
  provider failure the gateway retried, discarding a complete answer. Deliberate improvement; CHANGELOG notes it.
- **OpenAI Responses**: `terminalSeen` is set on `response.completed`; `STREAM_END` is emitted after the loop. Whether or
  not the endpoint sends `[DONE]`, a transport failure after `response.completed` is swallowed by the rule and
  `STREAM_END` is then emitted exactly once by the post-loop `emitStreamEnd()`. On base such a failure became a
  cancellation/provider failure.
- **OpenAI Chat Completions**: the terminal event is the `finish_reason` chunk; usage may trail it in one more chunk
  before `[DONE]`, at which the SDK stops reading. A transport failure between `finish_reason` and `[DONE]` therefore
  returns the complete *text* with `TokenUsage.empty()` when the usage chunk was lost — the fallback `cancellation.md`
  §4.3 already accepts for routed calls ("취소 경로 한정의 미터링 정확도 문제이지 정합성 문제가 아니다"); it is now also
  the behaviour for a trailing reset. Retrying a complete answer to recover its usage would cost a second full response.

Mechanics: the abort object stays active until `consume()` returns, so a late abort still cuts a stalled trailing read
(the alternative — marking it `COMPLETED` at the terminal event — is rejected in §3.2). After `consume()` returns
normally the object is `COMPLETED` and `abort()` is a no-op (`Call.cancel()` on a finished call is a no-op in OkHttp
too). Callers with stricter needs keep enforcing them above the client — `DefaultCompactionEngine.callSummaryModel`
discards any response returned after the signal tripped, unchanged.

### 4.2 `close()` fails after normal completion

**Return the response; log the close failure at WARN with the exception; do not fail the call.** The response is whole
and billed; failing it would make the gateway retry a successful request (double billing, duplicate side effects) — the
exact inversion §5 of `cancellation.md` guards against. This is not "ignoring" the failure in the sense plan §4 forbids:
OkHttp completes a body whose close threw with that error (`Exchange.ResponseBodySource.close()` → `complete(e)`), marks
the connection `noNewExchanges` and does not reuse it, so there is no silent leak; the OpenAI transport additionally calls
`call.cancel()` when a close fails, so the socket is closed deterministically. Today's try-with-resources classifies this
case as a provider failure; the client therefore stops using try-with-resources for the handle and closes explicitly
(§6.5). Close failures after a cancellation or a provider failure are attached with `addSuppressed` to the thrown
exception, as the plan says.

### 4.3 Cancellation during an SDK backoff sleep — accepted gap

If a 5xx/429/`IOException` has already been answered and the SDK is in `Sleeper.sleep()`, the abort is observed when
the next attempt starts (the hook refuses it). No request is sent, but the call returns only after the sleep: ≤ 8 s
for OpenAI (`MAX_RETRY_DELAY`), `Retry-After` as given for Anthropic ("do what it says, however long"). Rare (only
after a failed attempt), already the documented recommendation for prompt cancellation is `maxRetries(0)` at the SDK
with retries at the gateway (`cancellation.md` §7, whose sleep is cancellation-aware). Documented, not fixed (§10 Q2).

### 4.4 `close()` after `abort()` is **not** a no-op — correction to plan §3

Plan §3 says "abort 뒤의 close 는 no-op". It must not be: `Call.cancel()` closes the socket, but it does not release
what the sync call still holds — the `Exchange` and its connection (released in `callDone`), the call's `callTimeout`
`AsyncTimeout` (exited only in `callDone`), and the SDK's `BufferedReader` and `Response`. (For a sync call the
`Dispatcher` is already done with it: `RealCall.execute()` runs `dispatcher.finished(this)` in its `finally`, as soon as
headers return.) Skipping `StreamResponse.close()` would leave those to phantom-reachable cleanup. So `close()` on the
consumer thread always runs `StreamResponse.close()` exactly once, aborted or not — it is safe and fast there (the drain
read fails immediately on a cancelled call). What *is* a no-op is `abort()` after `close()`/completion, and a second
`close()`.

### 4.5 Anthropic header phase runs through OkHttp's `Dispatcher` — accepted difference

Using `delegate.executeAsync(…).join()` for the sync path of a **cancellable** call — one whose request carries an
abort key the registry knows — means that attempt is enqueued through the adapter's `Dispatcher` (`maxRequests` 64,
`maxRequestsPerHost` raised to the same by the adapter), which sync `call.execute()` never was; the decorator cannot
raise the limit (`okHttpClient` is `internal` on the adapter). A slot is held only until `onResponse` completes the
future — time to first byte — so the cap is on *concurrent cancellable header waits per client*, not on concurrent
streams. A request with no abort registered for it — a non-cancellable blocking call (`sendMessage(…, none())`), the
SDK used directly (`messages().create()`), or a key already released — skips this: the hook strips any key and
returns `delegate.execute(…)`, the adapter's own synchronous path, with no `Dispatcher` slot and no thread handoff.
Recorded here and in `cancellation.md` §4.4; not expected to bind at AIMON's concurrency.

---

## 5. Concrete changes

### 5.1 `aimon-llm-openai`

| File | Change |
|---|---|
| `build.gradle.kts` | add `implementation(libs.okhttp)` (compile-time; 5.5.0 is already what the module runs on — no runtime change, no `checkTestClasspathVersions` difference) |
| `OpenAIAbortableHttpClient.java` (new, package-private, `at.aimon.core.llms.openai`) | own `com.openai.core.http.HttpClient` over `okhttp3.OkHttpClient`: `execute`, `executeAsync`, `close`; per-attempt hook (§6.2); replicates the SDK adapter's `retryOnConnectionFailure(false)`, follow-redirects, 4 timeouts from `Timeout`, per-request timeout override via `newBuilder()`, `dispatcher.maxRequestsPerHost = maxRequests`, request/URL/body/header conversion, `X-Stainless-Read-Timeout`/`X-Stainless-Timeout` headers, `IOException → OpenAIIoException("Request failed", e)`, and closes the response on any failure after `call.execute()` (the adapter's `response.use { throw failure }`). Not replicated: proxy/SSL/pool/hostname-verifier options (not on `OpenAIConfig`) and the Azure `apiKeyRedirectInterceptor` (no Azure path in AIMON — §10 Q4). Installs an `okhttp3.EventListener` that counts `callStart` − (`callEnd` + `callFailed`) and exposes it as `int inFlightCalls()` for the resource test (§8.2 row 6) — `callEnd`/`callFailed` fire from `callDone`, i.e. after the body is released, which is what "in flight" must mean here (`Dispatcher.runningCallsCount()` is 0 for a sync call from the moment headers return and asserts nothing) |
| `OpenAICallAbort.java` (new, package-private) | per-call abort object (§6.1) |
| `OpenAIAbortRegistry.java` (new, package-private) | `open()` / `find(key)` / `release(abort)`; `ConcurrentHashMap<String, OpenAICallAbort>`; the client and the transport share one instance |
| `exception/OpenAICallAbortedException.java` (new, public) | `RuntimeException` the hook throws for a refused/aborted attempt; deliberately **not** `IOException`/`OpenAIIoException`/`OpenAIRetryableException` so `RetryingHttpClient` never retries it |
| `OpenAIStreamHandle.java` | add `void abort()`; `close()` doc rewritten (consumer thread, once, never the abort lever); `of(OpenAICallAbort, StreamResponse<T>, Consumer<Stream<T>>)`; `consume()` marks the abort `COMPLETED` on normal return; `close()` guarded by an `AtomicBoolean` |
| `OpenAIEndpointExchange.java` + both exchanges | `openStream(options, sink, aggregator, cancellation, abort)`; each exchange does `params.toBuilder().putAdditionalHeader(OpenAICallAbort.HEADER, abort.key()).build()` before `createStreaming` |
| `OpenAIStreamingMapper.java`, `OpenAIResponsesStreamingMapper.java` | `consume(stream, cancellation)`: catch `OpenAIIoException` around the loop; rethrow unless the terminal event was recorded; otherwise log and fall through to the existing post-loop `emitStreamEnd()` (§4.1, §6.6). Javadoc: a stream closed under its reader ends silently, a cut transport fails loudly; before the terminal event both are classified by the token, after it neither changes the answer |
| `OpenAILlmClient.java` | `createOpenAIClient`: `ClientOptions.builder().httpClient(transport).apiKey(…).baseUrl(…).timeout(…).maxRetries(…)` → `new OpenAIClientImpl(options)` (mirrors `OpenAIOkHttpClient.Builder.build()` for the four settings AIMON passes). Streaming path: open abort → `cancellation.onCancel(abort::abort)` → `openStream` → explicit consume/close with the §4.2 policy → `finally { registry.release(abort) }`. Test ctor `(OpenAIConfig, OpenAIClient)` keeps working with a registry wired to no transport. Class Javadoc and the comments at lines 224 and 279–285 rewritten |
| `OpenAIEmbeddingClient.java` | unchanged (still `OpenAIOkHttpClient.builder()`; no cancellation there) |

### 5.2 `aimon-llm-anthropic`

| File | Change |
|---|---|
| `AnthropicAbortableHttpClient.java` (new, package-private) | decorator over `com.anthropic.client.okhttp.OkHttpClient` (built with `OkHttpClient.builder().timeout(…).backend(backend).build()`): `execute` via `delegate.executeAsync` + `join()` with the hook (§6.2), `executeAsync` delegating with the same attach/refuse, `close()` → delegate |
| `AnthropicCallAbort.java`, `AnthropicAbortRegistry.java` (new, package-private) | same shape as OpenAI; levers are `CompletableFuture<HttpResponse>` (header phase) and `HttpResponse` (body phase) |
| `exception/AnthropicCallAbortedException.java` (new, public) | not `IOException`/`AnthropicIoException`/`AnthropicRetryableException` |
| `AnthropicStreamingMapper.java` | `consume(stream, cancellation)`: catch `AnthropicIoException` around the loop; rethrow unless `streamEnded`; otherwise log and return (`STREAM_END` was already emitted at `message_stop`) (§4.1, §6.6). Javadoc as for OpenAI |
| `AnthropicLlmClient.java` | `createAnthropicClient`: `backend = AnthropicBackend.builder().apiKey(…).baseUrl(…).build()`; `raw = OkHttpClient.builder().timeout(timeout).backend(backend).build()`; `ClientOptions.Builder o = ClientOptions.builder().httpClient(new AnthropicAbortableHttpClient(raw, registry)).timeout(…).maxRetries(…); o.baseUrl(backend.baseUrl()); backend.applyCredentials(raw, o); new AnthropicClientImpl(o.build())` — the body of `AnthropicOkHttpClient.Builder.build()` for the settings AIMON passes. `buildRequest` adds the abort header to `MessageCreateParams` when called from the streaming path. Streaming path restructured like OpenAI (no handle type needed: one stream type; close-once is structural because only the consumer thread closes). Comments at 281 and 342–348 rewritten |

### 5.3 `aimon-llm-testkit`

| File | Change |
|---|---|
| `StallingSseServer.java` (new, `at.aimon.llm.testkit`) | the two provider modules' identical `StallingSseServer` copies move here, generalised (§8.1). **JDK only** — the module's build script rule ("depends on nothing — not aimon-core, and not JUnit"): no AssertJ, JUnit or Awaitility; latches and plain sockets. Both modules already depend on the testkit at `testImplementation`; delete the module copies |
| `CountingHttpServer.java` | unchanged (its single-connection semantics are documented and relied upon) |

### 5.4 `aimon-core` (documentation only — no behaviour change, no API change)

| File | Change |
|---|---|
| `llm/LlmCancellation.java` | class Javadoc "Off-thread firing" bullet and `onCancel` Javadoc, **provider-neutral**: drop `StreamResponse.close()` / `future.cancel(true)` as "typical implementations"; state the principle — *the abort cuts the transport of that one call and must never touch the stream or its reader; cleanup is the consumer thread's `close()`; how the transport is cut is the provider module's business* (`.claude/rules/llm-provider.md`: SDK details stay in the module); keep thread-safe · idempotent · non-blocking · no-throw |
| `agent/interrupt/SignalBackedLlmCancellation.java` | Javadoc lines 21, 40, 93: "abort lever" instead of `StreamResponse.close()` |
| `agent/impl/orca/OrcaAgentExecutor.java` | comments at 1852–1854 ("stale (already-closed) stream") and 3449 ("owns a StreamResponse.close() abort lever") |

### 5.5 Documentation

| File | Change |
|---|---|
| `docs/design/llm/cancellation.md` | §2.2 line 74 (lever list); **§4.1 rewritten**: title "스트리밍 — transport abort", the four rules with abort registered *before* open, the per-attempt hook, the §4.1/§4.2 policies here incl. the `STREAM_END ⇔ LlmResponse` invariant and the Chat usage caveat; new §4.4 "transport hook 와 SDK 재시도" (refuse-after-abort; backoff gap §4.3; Anthropic dispatcher note §4.5); §7 paragraph "중단 레버는 SDK 가 응답을 돌려준 뒤에야 등록된다 … SDK 가 재시도하는 동안에는 취소도 관측되지 않는다" corrected; §8 diagram line 302 (`StreamResponse.close()` → "transport abort"); §9 add the durable rejected rows of §3.2; §10 "스트림 소비를 시작한 뒤에 abort 레버를 등록하지 말 것" → "스트림을 **열기 전에** 등록한다", plus "terminal event 뒤의 전송 실패를 취소나 provider 실패로 분류하지 말 것"; appendix rows for the new files |
| `docs/design/llm/streaming.md` | lifecycle-contract paragraph (lines 132–134): one sentence — a transport failure *after* the terminal event is not a provider error; the call returns normally and the sink has its `STREAM_END` (points to `cancellation.md` §4.1) |
| `docs/design/llm/cancellation-abort-close.md` | status → **IMPLEMENTED**; §2.1 corrected for the Anthropic adapter (§2.1 here); §2.3 → the decision (§3.1); §3 row "close 뒤의 abort, abort 뒤의 close 는 no-op" corrected per §4.4; §4 policies filled in; kept as the decision record (normative text lives in `cancellation.md`) |
| `docs/design/README.md` | add a row for `llm/cancellation-abort-close.md` under "llm — 프로바이더 계약" |
| `docs/design/llm/openai-responses-path.md` | line 84 handle sketch (add `abort()`), line 430 preserved-behaviour row 1 (lever and registration point) |
| `docs/design/agent-execution/interrupt.md` | line 386 wording ("자기 `StreamResponse.close()` 를" → "자기 abort 레버를") |
| `CHANGELOG.md` | `[Unreleased]` → "Fixed" entry for both provider modules (abort/close split, header-wait coverage, no retry after abort) and the §4.1 behaviour note (a transport failure after the terminal event returns the complete answer instead of failing/retrying). Not a release cut |
| Translations | none exist for any of these files (`docs/design/llm/*.en.md`, `docs/design/agent-execution/*.en.md`: no matches) — nothing to sync; say so in the PR. `python3 scripts/check-doc-links.py` after editing |

`docs/backlog/execution-environment-open-items.md:4300` and `hook-cancellation-followups-ee93-ee97.md:492` describe
history and stay as they are.

---

## 6. Shapes

### 6.1 Per-call abort object (same shape in both modules; OpenAI shown)

```java
/** One logical LLM call's abort lever. Created before the SDK call, released after it. */
final class OpenAICallAbort {
    static final String HEADER = "X-Aimon-Abort-Key";

    enum State { OPEN, ABORTED, COMPLETED, RELEASED }

    private final String key;                                   // opaque, unique per call
    private final AtomicReference<State> state = new AtomicReference<>(State.OPEN);
    private final AtomicReference<okhttp3.Call> call = new AtomicReference<>();   // current attempt

    String key();
    boolean isAborted();

    /** Any thread. Thread-safe, idempotent, non-blocking, never throws. Cancels only this call's transport. */
    void abort() {
        if (!state.compareAndSet(State.OPEN, State.ABORTED)) return;   // after COMPLETED/RELEASED: no-op
        final okhttp3.Call c = call.get();
        if (c != null) c.cancel();                                        // microseconds; never touches the stream
    }

    /** Transport hook, per attempt. If already aborted, cancels the call at once (the hook then throws). */
    void attach(okhttp3.Call c) { call.set(c); if (state.get() == State.ABORTED) c.cancel(); }

    /** Consumer thread, after consume() returned normally: a later abort has nothing to do. */
    void complete() { state.compareAndSet(State.OPEN, State.COMPLETED); }

    /** Client finally: unregister; drop the call reference. */
    void release() { state.set(State.RELEASED); call.set(null); }
}
```

Anthropic differs only in the lever fields: `attachAttempt(CompletableFuture<HttpResponse>)` → `abort()` does
`future.cancel(true)`; `attachResponse(HttpResponse)` → `abort()` does `response.close()` (the adapter's race-safe
close). Both attaches cancel/close immediately when the state is already `ABORTED`.

Invariant the tests pin (plan §5 row 4): an abort object only ever holds levers of **its own** call, so a stale
`abort()` (the cancel thread read a previous call's lever just before `clearAbort()`) cannot reach the next call.

### 6.2 The per-attempt hook (`HttpClient.execute`)

```java
// OpenAI — own transport
public HttpResponse execute(HttpRequest request, RequestOptions options) {
    final Optional<OpenAICallAbort> abort = registry.find(firstHeader(request, OpenAICallAbort.HEADER));
    final HttpRequest wire = abort.isPresent() ? request.toBuilder().removeHeaders(OpenAICallAbort.HEADER).build() : request;
    if (abort.map(OpenAICallAbort::isAborted).orElse(false)) {
        throw new OpenAICallAbortedException("attempt refused: the call was aborted");   // RetryingHttpClient: not retryable
    }
    final okhttp3.Call call = newCall(wire, options);          // per-request timeouts via okHttpClient.newBuilder(), as the SDK does
    abort.ifPresent(a -> a.attach(call));                      // aborted meanwhile → cancelled right here
    HttpResponse response = null;
    try (HttpRequestBody body = wire.body()) {                 // the SDK closes the request body after the attempt
        response = toHttpResponse(call.execute());
        if (abort.map(OpenAICallAbort::isAborted).orElse(false)) {       // cancelled between headers and here
            throw new OpenAICallAbortedException("attempt aborted after headers");
        }
        return response;
    } catch (IOException e) {
        if (abort.map(OpenAICallAbort::isAborted).orElse(false)) {
            throw new OpenAICallAbortedException("attempt aborted", e);   // not the SDK's retryable OpenAIIoException
        }
        throw new OpenAIIoException("Request failed", e);                  // the SDK's own classification, unchanged
    } catch (Throwable failure) {
        closeQuietly(response, failure);                                   // the adapter's `response.use { throw failure }`:
        throw failure;                                                     // a body must not leak on any failure after execute()
    }
}
```

(The `IOException` arm has no response yet — `call.execute()` is what threw — so only the generic arm closes one.)

```java
// Anthropic — decorator over the SDK adapter
public HttpResponse execute(HttpRequest request, RequestOptions options) {
    …lookup / strip / refuse as above…
    final CompletableFuture<HttpResponse> attempt = delegate.executeAsync(wire, options);  // adapter cancels its Call when this future is cancelled
    abort.ifPresent(a -> a.attachAttempt(attempt));
    final HttpResponse response;
    try {
        response = attempt.join();                                        // uninterruptible, like the sync call.execute()
    } catch (CancellationException | CompletionException e) {
        if (aborted) throw new AnthropicCallAbortedException("attempt aborted", e);
        throw unwrap(e);   // the adapter's AnthropicIoException etc. rethrown as-is so the SDK's retry/err classification is unchanged
    }
    if (aborted) { response.close(); throw new AnthropicCallAbortedException(…); }
    abort.ifPresent(a -> a.attachResponse(response));                    // body phase lever = adapter's race-safe close()
    return response;
}
```

`executeAsync` in both implementations mirrors this with the same lookup/refuse/attach (AIMON never calls the async
services, but the interface must be whole). `close()` shuts the OkHttp dispatcher executor, evicts the pool and closes
the cache (OpenAI) / delegates (Anthropic).

### 6.3 Correlation header

`X-Aimon-Abort-Key: <key>` is put on `ChatCompletionCreateParams` / `ResponseCreateParams` / `MessageCreateParams` via
`putAdditionalHeader` only on the cancellable streaming path. `prepare()` copies it to the `HttpRequest`;
`RetryingHttpClient`, `LoggingHttpClient` and the auth layers pass it through (Logging may log it at DEBUG — an opaque
id); the hook strips it before building the OkHttp request. The blocking path with `LlmCancellation.none()` carries no
header and passes through the hook untouched — on Anthropic that means the adapter's synchronous `execute`, not the
`executeAsync` + `join()` of a cancellable call, so it takes no `Dispatcher` slot (§4.5). A test asserts the header
never reaches the local server.

### 6.4 `OpenAIStreamHandle`

```java
interface OpenAIStreamHandle extends AutoCloseable {
    void consume();          // consumer thread; drains through the mapper; marks the call COMPLETED on normal return
    void abort();            // any thread → OpenAICallAbort.abort(): transport only; never the stream or its reader
    @Override void close();  // consumer thread only; StreamResponse.close() exactly once; also after abort (§4.4)
    static <T> OpenAIStreamHandle of(OpenAICallAbort abort, StreamResponse<T> stream, Consumer<Stream<T>> consumer);
}
```

### 6.5 Client streaming path (both clients, OpenAI shown)

```java
if (cancellation.isCancelled()) throw new LlmCallCancelledException("… cancelled before start");   // kept: cheapest exit
final OpenAICallAbort abort = aborts.open();
try {
    cancellation.onCancel(abort::abort);          // BEFORE the request: covers header wait and every SDK attempt
    final OpenAIStreamHandle handle = exchange.openStream(requestOptions, sink, aggregator, cancellation, abort);
    Throwable failure = null;
    try {
        handle.consume();                         // the mapper applies §4.1; a failure after the terminal event does not reach here
    } catch (Throwable t) {
        failure = t; throw t;
    } finally {
        closeHandle(handle, failure);             // §4.2: suppressed on failure; WARN + continue on success
    }
    return aggregator.toLlmResponse();
} catch (LlmCallCancelledException e) { throw e; }
  catch (LlmClientException e)        { throw cancellation.isCancelled() ? new LlmCallCancelledException("… aborted by cancellation", e) : e; }
  catch (Exception e)                 { … same as today (cancelled → LlmCallCancelledException(cause e); else mapper) … }
  finally { aborts.release(abort); }
```

`OpenAICallAbortedException` surfaces through the last branch when the hook refused or cut an attempt (the token is
tripped, so it maps to `LlmCallCancelledException` with the aborted exception as cause); should it ever arrive with a
live token (impossible by construction — only `abort()` sets the state) it is mapped as a provider failure.

### 6.6 Mapper rule for a transport failure after the terminal event (§4.1)

```java
// AnthropicStreamingMapper — STREAM_END already emitted at message_stop inside onEvent
void consume(Stream<RawMessageStreamEvent> stream, LlmCancellation cancellation) {
    try {
        stream.forEach(this::onEvent);
    } catch (AnthropicIoException e) {            // the SDK's transport failure type only; SseException etc. propagate
        if (!streamEnded) throw e;                // before message_stop: the client's cascade classifies it
        log.atLevel(cancellation.isCancelled() ? DEBUG : WARN)
           .log("Anthropic stream failed after message_stop; the answer is complete", e);
        return;
    }
    if (!streamEnded) { …unchanged: cancelled → throw; else synthetic STREAM_END… }
}

// OpenAIStreamingMapper / OpenAIResponsesStreamingMapper — STREAM_END emitted after the loop
void consume(Stream<T> stream, LlmCancellation cancellation) {
    try {
        stream.forEach(this::onChunk);
    } catch (OpenAIIoException e) {
        if (!terminalSeen()) throw e;             // Chat: lastFinishReason present; Responses: terminalSeen
        log…("OpenAI stream failed after its terminal event; the answer is complete", e);
    }
    if (!terminalSeen() && cancellation.isCancelled()) throw new LlmCallCancelledException(…);   // unchanged
    emitStreamEnd();                               // exactly once, in every path that returns
}
```

---

## 7. Failure modes

| Situation | What happens | Guarantee |
|---|---|---|
| Cancel during header wait | OpenAI: `call.cancel()` → `call.execute()` throws `IOException` → hook sees `ABORTED` → `OpenAICallAbortedException` (non-retryable) → `LlmCallCancelledException`. Anthropic: `future.cancel(true)` → adapter cancels its call → `join()` throws `CancellationException` → `AnthropicCallAbortedException` | exactly one request on the wire, no retry (plan row 2) |
| Cancel while blocked reading the body, before the terminal event (stalled server) | OpenAI: `call.cancel()` → read fails `IOException("Canceled")` → `OpenAIIoException` out of `stream.forEach` → no terminal → cancelled. Anthropic: `response.close()` with a read in flight → adapter cancels the call → `AnthropicIoException` → no terminal → cancelled | abort returns in µs; no `Unbalanced enter/exit`; no reader lock taken by the cancelling thread; sink gets no `STREAM_END` (plan row 1) |
| Cancel while the consumer is between events (no read in flight) | OpenAI: `call.cancel()`; next read fails. Anthropic: adapter closes the body directly — OkHttp drains ≤ 100 ms (`DISCARD_STREAM_TIMEOUT_MILLIS`) on the abort thread; the consumer's next read throws `IOException("closed")` (the wrapper gates reads behind `closed`, so no concurrent socket read) | bounded (≤ 100 ms) on Anthropic in this window only; immediate on OpenAI |
| Cancel after the terminal event, before EOF / `[DONE]` (Anthropic: every call; Responses: every call; Chat: between `finish_reason` and `[DONE]`) | the abort cuts the trailing read; the mapper sees the terminal event recorded and swallows the SDK I/O failure; `STREAM_END` exactly once; complete `LlmResponse` returned (Chat: `TokenUsage.empty()` if the usage chunk was lost) | `STREAM_END` ⇔ `LlmResponse`; never a partial response; never a double-close error (plan row 3) |
| Cancel after `consume()` returned | `COMPLETED` → `abort()` no-op | complete response |
| Transport reset after the terminal event, token live | same mapper rule → complete response, WARN | a complete answer is not retried (behaviour change vs base, §4.1) |
| SDK retry after an aborted attempt | the hook refuses the next attempt before `newCall` | no re-send; the plan's §2.2 hazard is closed structurally |
| Cancel during SDK backoff sleep | observed at the next attempt (refused) | no re-send; latency ≤ backoff (§4.3) |
| Stale abort from the previous call (`SignalBackedLlmCancellation` race) | `abort()` on a `COMPLETED`/`RELEASED` object → no-op; it holds no lever of the next call | plan row 4 |
| Already cancelled when `onCancel` registers (synchronous firing) | `ABORTED` before the SDK call → first attempt refused → no request | tightens today's fast-path window |
| `close()` throws after cancellation / provider failure | `addSuppressed` on the thrown exception (consumer thread) | primary exception is the cancellation / the mapped failure |
| `close()` throws after a complete response | WARN with exception; response returned; OpenAI also `call.cancel()` so the socket is closed | §4.2 |
| Failure after `call.execute()` returned (OpenAI transport) | the response is closed before the failure propagates (§6.2) | no body leak |
| Hook finds no registry entry (blocking path, or a key after `release()`) | passes the request through unchanged (header stripped if present) | non-cancellable calls unaffected |
| Transport/okhttp3 construction differences vs the SDK adapter | replicated settings listed in §5.1; `OpenAILlmClientSdkRetryTest` (real server, counts attempts) and the timeout tests pin `retryOnConnectionFailure(false)` and per-request timeouts | regression-tested, not promised |
| Anthropic cancellable header waits > 64 concurrent on one client | queued by OkHttp's `Dispatcher` until a slot frees (§4.5); non-cancellable calls take the adapter's sync path and no slot | accepted difference; streams are not capped |
| `OpenAILlmClient.close()` — transport lifetime | `close()` (added after review, §11.6) closes the SDK client and so the transport; without it, `ClientOptions` wraps the transport in `PhantomReachableClosingHttpClient`, as it does the SDK's own adapter | no new leak class |

---

## 8. Test strategy

Real SDK, real OkHttp, local HTTP/1.1 server, no Docker tag — all under plain `test`.

### 8.1 Harness — `at.aimon.llm.testkit.StallingSseServer` (generalised, shared, JDK-only)

Raw `ServerSocket`, one thread per connection (so a stalled connection does not hide a retry — the property row 2 needs,
and the reason `CountingHttpServer` is left alone), `Transfer-Encoding: chunked` written by hand (`hex-len\r\n…\r\n`,
like `com.sun.net.httpserver` does today), request head + `Content-Length` body read like `CountingHttpServer`. No
test-framework dependency: `java.util.concurrent` latches and plain sockets only (the testkit's rule). Scripted per
connection:

- `stallBeforeHeaders()` — read the request, count it, send nothing until released or the client disconnects
- `sseThenStall(events)` — headers + chunked events, then stall (today's behaviour)
- `sseThenHoldTerminator(events, tail)` — headers + all events (through the terminal event: `message_stop`,
  `response.completed`, or the `finish_reason`(+usage) chunk), flushed; then **hold the tail** (`data: [DONE]` if given,
  and the chunk terminator `0\r\n\r\n`) behind a latch until `release()` or client disconnect. This opens the §4.1
  window deterministically
- `sseComplete(events, tail)` — the same with no hold

Observability: `requestCount()`, per-request captured headers (to assert `X-Aimon-Abort-Key` is absent on the wire),
`awaitAnswered(n)` (events flushed), **`awaitClientDisconnected(n)`** (the connection's read returns −1/reset — the
resource check of row 6), `release()`. Both modules' `StallingSseServer` copies are deleted and their tests re-pointed.

Test-side token: a `RecordingCancellation implements LlmCancellation` (like `TrippableCancellation` in
`OpenAILlmClientSdkRetryTest`) that runs the registered levers on `trip()` and **records per lever the elapsed time and
any `Throwable`** — the deterministic signal for the non-blocking/no-throw contract, independent of
`DefaultCancellationSignal.safeRun`, which swallows listener exceptions with a WARN. Test-side sink: a `RecordingSink`
that records chunk kinds and opens latches on the first `TEXT_DELTA` and on `STREAM_END`.

### 8.2 Regression tests (plan §5 rows) and their expected result on the base commit

| Row | Test (OpenAI: both endpoints via `@EnumSource`; Anthropic: one) | Passes after | On base `c2fa8fb8` |
|---|---|---|---|
| 1 | `…CancellationTransportTest.abortWhileReadBlocked`: `sseThenStall` after a text delta; trip from another thread once the delta reached the sink; loop ×30 with a fresh client each time. Assert `LlmCallCancelledException` within 1 s, lever elapsed ≤ 100 ms, lever threw nothing, sink got no `STREAM_END` | yes | **OpenAI fails**, by whichever arm of the race (§2.2) runs: in-flight read → the lever throws `IllegalStateException("Unbalanced enter/exit")`, blocks on the reader lock, and the consumer hangs until the 60 s read timeout (fails the 1 s guard); no read in flight → the lever blocks ≤ 100 ms in `discard` (fails the 100 ms bound only marginally — the recorded exception, not the time, is the deterministic discriminator). **Anthropic**: functional on base thanks to the SDK adapter (§2.1); the lever's `reader.close()` lock wait is sub-ms there, so this row is a regression guard, not a reproduction |
| 2 | `abortDuringHeaderWait`: `stallBeforeHeaders`, `maxRetries(2)`; trip after `awaitAnswered`; assert `LlmCallCancelledException` within 1 s, then after a 500 ms grace `requestCount() == 1`, and `awaitClientDisconnected` | yes | **both fail**: no lever exists before headers; the call hangs until the hang guard |
| 3 | `cancelAfterTerminalEvent`: `sseThenHoldTerminator`. **Anthropic (deterministic)**: wait for `STREAM_END` in the sink (emitted at `message_stop` inside the loop — the consumer is now blocked on the held terminator), trip, `release()`; assert a complete `LlmResponse` with the full text, `STREAM_END` exactly once, lever threw nothing. **OpenAI (both endpoints)**: wait for `awaitAnswered` and the last `TEXT_DELTA`, settle 100 ms (the consumer processes the already-buffered terminal chunk in µs and blocks on the held tail), trip, `release()`; assert the §4.1 invariant — `STREAM_END` in the sink ⇔ `LlmResponse` returned, no `STREAM_END` ⇔ `LlmCallCancelledException` — and no other exception type; ×20. The complete-response outcome is expected in every run; the invariant tolerates the other arm so the test is not timing-flaky. Also `cancelRacesTerminalEvent` with `sseComplete` tripping from the sink on the last text delta ×50, same invariant | yes | **Anthropic passes on base** (its lever's `sequence.close()` swallows the post-close failure — the behaviour §4.1 preserves). **OpenAI**: with a held tail the base lever hangs/throws as in row 1; with `sseComplete` it is racy |
| 4 | `staleAbortDoesNotReachNextCall`: one `RecordingCancellation`; call 1 completes (`sseComplete`), its lever is captured; call 2 starts on `sseThenStall`; run call 1's lever from another thread; assert call 2's connection is **not** disconnected within 500 ms and completes once the server is released | yes | **passes on base** too: call 1's lever is a `StreamResponse` whose `close()` is idempotent, so it does nothing to call 2 either. The row pins the invariant going forward (plan §2.4 said so: invariant + test, not a new mechanism) |
| 5 | `OpenAIStreamAbortTest` / `AnthropicStreamAbortTest` compaction + turn tests — kept, re-pointed to the testkit server; plus `OpenAILlmClientSdkRetryTest.cancelMidStreamIsNotRetried` kept | yes | pass on base (behaviour preserved) |
| 6 | `resourcesReleasedAfterAbort`: after row 1's cancellation, `awaitClientDisconnected(5)`; OpenAI: `transport.inFlightCalls() == 0` (the `EventListener` counter of §5.1) after the client call returned | yes | OpenAI base: the disconnect comes only when `close()` won the race (no read in flight); in the other arm not before the 60 s read timeout. The in-flight counter has no base equivalent |
| header | `abortKeyNeverReachesTheWire`: the captured request headers contain no `X-Aimon-Abort-Key` on streaming and blocking paths | yes | n/a (new header) |
| §4.1 | `trailingResetAfterTerminalEventReturnsTheAnswer` (both providers): `sseThenHoldTerminator`, then the server **resets** the connection instead of releasing (reuse `Step.sseThenReset`'s linger-zero close); token live; assert a complete `LlmResponse`, `STREAM_END` once, one request (no retry) | yes | **fails on base for OpenAI Responses and Anthropic** (provider failure, then a gateway retry would follow); Chat: the SDK stops at `[DONE]`, so the reset must land before it — same assertion |

Unit tests without a server: `OpenAICallAbortTest` / `AnthropicCallAbortTest` (state machine; `abort()` ×N from N
threads; attach-after-abort cancels; complete-then-abort no-op), `OpenAIStreamHandleTest` (close once; abort never calls
`StreamResponse.close()`; close after abort still closes once), `OpenAIAbortRegistryTest`, and mapper tests over a
`Stream` that throws `OpenAIIoException` / `AnthropicIoException` after the terminal event (swallowed, `STREAM_END` once)
and before it (propagates). Existing mock-based `OpenAILlmClientCancellationTest` / `AnthropicLlmClientCancellationTest`
/ `OpenAIResponsesCancellationTest`: the "abort callback is `StreamResponse.close()`" assertions
(`verify(streamResponse, atLeastOnce()).close()`) change to "the registered lever aborts the call object; `close()` is
called exactly once"; the non-streaming routing and already-cancelled tests stay. `OpenAIArchitectureTest`: add
`okhttp3..` and `okio..` to the allowed packages; `AnthropicArchitectureTest` unchanged (no okhttp3 import).
`OpenAILlmClientSdkRetryTest` / `AnthropicLlmClientSdkRetryTest`: unchanged and must still pass — they are the proof that
the replaced transport keeps `retryOnConnectionFailure(false)` and the SDK's retry counts.

Timing bounds: 100 ms for the lever is the plan's figure and has a ~100× margin over `Call.cancel()`; the hang guards
(1 s result, 20 s server waits) are guards, not measurements. On base the deterministic discriminator is the recorded
`IllegalStateException` / the 60 s hang, not the 100 ms bound (§2.2).

### 8.3 Gate

`./gradlew :aimon-llm-openai:test :aimon-llm-anthropic:test :aimon-core:test checkFormat checkStyle`, then
`./gradlew checkAll` (includes `checkJavadocCoverage` — new public type `*CallAbortedException` needs Javadoc; the other
new types are package-private — and `checkTestClasspathVersions`, which sees no new difference), and
`python3 scripts/check-doc-links.py`.

---

## 9. Work order (refines plan §6)

1. Testkit: move/generalise `StallingSseServer` (JDK-only; held-terminator mode); write rows 1–4, 6, the header test and
   the §4.1 trailing-reset test for OpenAI, rows 1/2/4 and the §4.1 tests for Anthropic; run on the base commit and record
   which fail (expected: OpenAI 1, 2, 3 (held tail), §4.1-Responses; Anthropic 2, §4.1).
2. Mappers: the §6.6 rule in all three `consume(stream, cancellation)` methods, with their unit tests.
3. OpenAI: `OpenAICallAbort`, `OpenAIAbortRegistry`, `OpenAICallAbortedException`, `OpenAIAbortableHttpClient` (incl. the
   `EventListener` counter); `OpenAIStreamHandle.abort()`; exchange signature; client wiring (`ClientOptions` +
   `OpenAIClientImpl`), explicit close with §4.2; `build.gradle.kts` + `OpenAIArchitectureTest` allowlist; mock tests
   adjusted.
4. Anthropic: `AnthropicCallAbort`, registry, exception, `AnthropicAbortableHttpClient`; client wiring (`AnthropicBackend`
   + `ClientOptions` + `AnthropicClientImpl`); streaming path; mock tests adjusted.
5. Run the full module suites incl. `*SdkRetryTest`, `*RequestTimeoutTest`, `*StreamAbortTest`.
6. Docs and Javadoc per §5.4–§5.5; `check-doc-links.py`; CHANGELOG `[Unreleased]`.
7. `./gradlew checkAll`.

---

## 10. Open questions (not resolvable from the task statement; stated, not assumed)

1. **Upstreaming.** The Anthropic adapter's `ResponseBodyInputStream` is the fix openai-java lacks. Should an issue/PR
   be filed against openai-java so the OpenAI transport can later be deleted in favour of the decorator shape?
   Outside this repo; recommended, but someone has to own it.
2. **Backoff-sleep gap (§4.3).** Accepted here. If prompt cancellation during a `Retry-After` wait matters for Anthropic
   deployments, the alternative is `maxRetries(0)` at the SDK (already documented) or a wakeable `Sleeper` with a
   correlation design of its own. Backlog item or accepted — the owner's call.
3. **Non-blocking bound.** 100 ms (plan's figure) vs a looser CI-safe bound. The design uses 100 ms and names the
   deterministic fallback discriminator (§8.2); confirm the number if CI is noisy.
4. **Azure parity.** The own OpenAI transport does not replicate the SDK adapter's `apiKeyRedirectInterceptor` (Azure
   cross-origin redirect handling; the interceptor is `internal`). `OpenAIConfig` has no Azure surface, so this is
   believed unreachable — confirm that Azure OpenAI is not a supported deployment, or add the equivalent network
   interceptor.
5. **`cancellation-abort-close.md`: keep or fold.** This design keeps it as the IMPLEMENTED decision record and makes
   `cancellation.md` normative (§5.5), matching `hook-cancellation-followups-ee93-ee97.md`'s precedent. The plan allowed
   either; flag if the preference is to fold.
6. **Header name.** `X-Aimon-Abort-Key` is stripped before the wire and only ever seen by the SDK's in-process layers;
   confirm there is no house convention for internal correlation headers.
7. **Chat Completions usage after a trailing failure (§4.1).** The rule returns the complete text with
   `TokenUsage.empty()` when the usage chunk was lost between `finish_reason` and `[DONE]`. If metering accuracy on that
   (rare) path matters more than avoiding a retry of a complete answer, the Chat mapper could instead treat
   `finish_reason` + missing usage as "not yet terminal" when `stream_options.include_usage` was requested. Decided as
   written; flag if metering wins.

---

## 11. Where the implementation departed from this design

Appended after the build (2026-10-20). The body above is unchanged; what follows is every place the code, the tests or
the documents differ from it, and why. Review-2's nine non-blocking notes are each accounted for — taken, taken in
part, or declined with the reason. The run record (`build/deviations.md`, `build/summary.md`) is not in the repository.

### 11.1 Review-2 notes taken

- **§8.2 row 2 — the retry grace.** The 500 ms grace sat inside the SDKs' first backoff (0.5 s × jitter in
  [0.75, 1.0], so 375–500 ms before a second attempt): a hook that stopped refusing an aborted attempt could have landed
  its retry after the count was read. Both transport tests wait 1.5 s (`RETRY_GRACE`) after the cancelled call returns
  and the client's disconnect was observed, then assert `requestCount() == 1`. The same grace is used by the
  trailing-reset test before asserting the whole answer was not retried.
- **§8.2 row 1 — the lever bound.** The bound is 1 s in both modules (`LEVER_BOUND` in
  `AnthropicCancellationTransportTest` and `OpenAICancellationTransportTest`), not the design's 100 ms. For Anthropic
  the design's own §7 "no read in flight" row says the adapter's race-safe close drains the body for up to OkHttp's
  100 ms discard on the aborting thread, so 100 ms itself was racy on that arm. For OpenAI the lever is a bare
  `Call.cancel()`, which takes microseconds; 100 ms was first kept there, then relaxed after review to the same 1 s,
  because a blocking lever would block for the read timeout (15 s in these tests) and 1 s still separates the two by
  an order of magnitude, while 100 ms put a loaded CI machine's scheduling jitter into the result. The deterministic
  parts of the row — the lever threw nothing, no `STREAM_END`, the result within 1 s — are asserted unchanged.
- **§5.1 / §5.2 — the exception's superclass.** `OpenAICallAbortedException extends OpenAIException` (the module's
  base, itself a `RuntimeException`) and `AnthropicCallAbortedException extends AnthropicException` (the module's
  base, itself an `LlmClientException`) rather than a bare `RuntimeException`. Both land in the clients' `catch
  (Exception)` arm as §6.5 says, and neither is an `IOException`, an `*IoException` or an `*RetryableException`, so
  `RetryingHttpClient.shouldRetry` stays false. The Javadoc of both carries the invariant the review asked for: the
  type must never gain an `IOException` ancestor, because that is the whole no-retry guarantee.
- **§2.4 — "an injected client is closed by `AnthropicLlmClient.close()` today".** Not what the code did:
  `AnthropicClient` declares its own `close()` and is deliberately not `AutoCloseable`, so the client's
  `instanceof AutoCloseable` test never matched and both the adapter and this design's decorator would have been
  released by phantom reachability only. The review's optional fix was taken: `AnthropicLlmClient.close()` now calls
  `AnthropicClient.close()` directly, which closes the options' transport — the decorator and the adapter under it.
  The sentence in §2.4 is therefore true as of this build, by a change outside the design's file table.
- **§2.5 — the Responses row.** `terminalSeen` is set only on `response.completed` and `response.incomplete`;
  `response.failed` and `response.error` are throwing arms. §4.1's per-endpoint text is right; the table cell's
  "`failed`" is wrong and is not corrected in the body.
- **§4.2 — "OkHttp completes a body whose close threw with that error".** True for an `IOException` from the close;
  the `IllegalStateException` arm of §2.2 bypasses that path. After a normal completion there is no concurrent read,
  so that arm cannot occur in the §4.2 case. The sentence should read "an `IOException` from close"; the body is not
  corrected.
- **§3.1 — the OpenAI transport is not a `WebSocketClient`.** Recorded in `OpenAIAbortableHttpClient`'s Javadoc and in
  `cancellation.md` §4.4: the SDK's realtime services would refuse this transport with
  `UnsupportedOperationException`; the module has no realtime use.
- **§6.2 — `unwrap` in the Anthropic sketch.** Defined: a `CompletionException` whose cause is a `RuntimeException`
  is rethrown as that cause (so an `AnthropicIoException` stays one, for both the SDK's retry check and the client's
  mapper); an `Error` is rethrown as itself; any other cause — none exists today — leaves the `CompletionException`
  itself to propagate, which the SDK's `shouldRetry` unwraps for its own classification. A `CancellationException`
  out of the join is always the abort's (nothing else cancels that future) and becomes
  `AnthropicCallAbortedException` without consulting the state.

### 11.2 Review-2 note declined

- **§4.1 Chat Completions / open question 7 — usage after a trailing failure.** The review proposed a narrower rule:
  under a live token, require the usage chunk (or `[DONE]`) before swallowing a transport failure after
  `finish_reason`, so a reset between the two still fails and the gateway retries as on base. Decided as written in
  §4.1 instead: one rule for all three mappers (the terminal event decides, the token does not), and the cost
  asymmetry the body states — a retry of a complete answer is a second full response, billed, against an empty usage
  on a rare path that `cancellation.md` §4.3 already accepts for routed calls. The CHANGELOG entry and
  `cancellation.md` §4.1 both name the consequence. Registered as a backlog item (§11.5) so the decision can be revisited
  if metering wins.

### 11.3 Shapes that differ from the sketches

- **§8.1 — the testkit server's API.** One shape per server rather than a scripted step list, which is all the rows
  need and keeps the class JDK-only and small: `stallingBeforeHeaders()`, `sending(head, tail)` (the head flushed,
  the tail and the chunk terminator held until `release()`, `reset()` or the client hangs up — `sseThenStall` is
  `sending(head, "")`, `sseThenHoldTerminator` is `sending(head, tail)`) and `complete(events)`. Observability as
  designed: `requestCount()`, `requestHeaders()`, `awaitAnswered(n, Duration)`, `awaitClientDisconnected(n,
  Duration)`, plus `reset()` for the §4.1 trailing-reset test. Row 4 therefore uses two servers (one per call) and two
  clients, not one server with two steps. Every response carries `Connection: close` so the client never pools a
  connection the server is about to cut, which would otherwise turn a cut into an SDK retry.
- **§8.2 row 6 — the in-flight counter.** The transport test (`OpenAICancellationTransportTest`) asserts only the
  server-side half of the row (`awaitClientDisconnected`) so that the whole class compiles and runs against the base
  commit; the `inFlightCalls() == 0` half is `OpenAITransportResourceTest`, which exists only on the fixed transport
  and reads the counter through a package-private `OpenAILlmClient.transport()` accessor the design did not list.
- **§8.2 row 1 — where the trip runs.** The token is tripped from a helper thread (`tripper`) rather than the test
  thread, and the test clients use a 15 s timeout. Both are so that the base commit's failure is fast: there the lever
  blocks on the reader's lock until the consumer's read unwinds, which on the test thread would have deadlocked the
  test until the 60 s default read timeout rather than failing the 1 s guard. The base-commit run in `summary.md`
  shows rows 1, 2, 3 (held tail), 6 and the trailing reset failing in about a second each on OpenAI, and row 2 and the
  trailing reset on Anthropic, with the rows the body predicts to pass there passing.
- **§5.2 — where the Anthropic header is added.** On the streaming path, by `request.toBuilder()
  .putAdditionalHeader(...)` after `buildRequest`, rather than inside `buildRequest` with a flag; `buildRequest` is
  unchanged.
- **§6.3 — "only on the cancellable streaming path".** An abort object is opened, and its key put on the params, for
  every streaming call, including one made with `LlmCancellation.none()`; the no-op token discards the lever, the
  header is stripped by the hook like any other, and the blocking path still carries nothing. One path to test rather
  than two.
- **§6.2 — rebuilding the OkHttp client per attempt.** The SDK's adapter rebuilds its `okhttp3.OkHttpClient` through
  `newBuilder()` on every attempt; this module's transport does so only when the `RequestOptions` carry a `Timeout`,
  which in practice is every attempt the SDK makes (it merges the client default in before calling the transport).
  Equivalent; noted because the sketch did not say.
- **§8.2 — the mock tests.** `shouldInvokeAbortCallback_WhenCancellationFiresDuringRegistration` (both clients) and
  `abortCallbackClosesTheStream` (Responses) are replaced by tests that assert the lever is registered <em>before</em>
  `createStreaming` runs and that running it does not close the stream — `close()` is seen exactly once, from the
  consumer path. The request-equality tests strip the abort header from the streaming params before comparing, and
  assert it was there.

### 11.4 Documents

- **§5.5 — `cancellation-abort-close.md`.** The build brief asked for the approved design itself in the repository,
  so this file was added as the design record in the §3.4 habit, and the committed plan was updated as §5.5 says:
  status `IMPLEMENTED`, §2.1 corrected for the Anthropic adapter, §2.3 replaced by the decision, the §3 "no-op" row
  corrected per §4.4, §4's two policies filled in. Both are listed in `docs/design/README.md`. Open question 5
  ("keep or fold") is thereby answered: kept, with this record beside it.
- **Translations.** None of the edited documents has a `*.en.md`; `docs/design/` is not a translation target.

### 11.5 Open questions promoted

§10's questions 1 (upstreaming the body-close fix to openai-java), 2 (the backoff-sleep gap), 4 (Azure parity of the
own transport) and 7 (Chat usage after a trailing failure) have consequences past this change and are registered in
[`../../backlog/interrupt-open-items.md`](../../backlog/interrupt-open-items.md) as items 6–9. Questions 3 (the
100 ms bound: 1 s in both modules — §11.1), 5 (§11.4) and 6 (no house convention for internal correlation headers was
found; `X-Aimon-Abort-Key` stands) are closed here.

### 11.6 Post-review fixes

- **Anthropic's non-cancellable path stays synchronous.** As first built, the decorator's `execute` ran every request
  through `delegate.executeAsync(…).join()`, so `sendMessage(…, none())` and `messages().create()` also took a
  `Dispatcher` slot for the header wait and paid a thread handoff. Now a request with no abort registered goes straight
  to `delegate.execute(…)` (key stripped if present); §4.5 and §6.3 describe the result.
- **`OpenAILlmClient.close()`.** The client now owns its `okhttp3.OkHttpClient`, so it is `AutoCloseable` and
  `close()` closes the SDK client — and with it this module's transport — once; a second call does nothing. With
  `AnthropicLlmClient.close()` (§11.1) both clients' `close()` now really release the SDK client, which is a behaviour
  change for a container that closes a client bean it shares.
- **Refused attempts close their request body**, in both transports' `execute` and `executeAsync`, as the SDK's
  adapters do for an attempt they send.
- **Deterministic gates in `OpenAICancellationTransportTest`.** Row 3 (held tail) and the trailing-reset test no longer
  sleep 100 ms before tripping: the OpenAI mappers emit nothing to the sink at the terminal event, so the fixture puts
  the last text delta <em>after</em> it as a probe (`terminalEventThenProbe()`), and the sink's latch on that delta is
  the gate — events are dispatched in order on the consumer thread. The answer's text and usage are unchanged.
