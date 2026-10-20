# LLM 취소의 abort 와 close 분리 (Cancellation Abort/Close Split)

> Status: **IMPLEMENTED** (2026-10-20) — 원인 분석과 수정 계획. 구현은 이 계획을 다듬은 승인본 설계
> [`cancellation-abort-close-design.md`](cancellation-abort-close-design.md)(영어, 구현이 벗어난 자리는 그 §11)대로
> 들어갔고, 규범 서술은 [`cancellation.md`](cancellation.md) §4.1 · §4.4 가 갖는다. 이 문서는 결정 기록으로 남는다 —
> §2.1 · §2.3 · §3 · §4 는 설계가 정한 답으로 고쳤다.
>
> 적용 대상: `aimon-llm-openai` — `OpenAIStreamHandle`, `OpenAILlmClient`, 두 `OpenAI*Exchange`, 두 스트리밍 매퍼,
> 새 `OpenAIAbortableHttpClient` · `OpenAICallAbort` · `OpenAIAbortRegistry` · `OpenAICallAbortedException` ·
> `aimon-llm-anthropic` — `AnthropicLlmClient`, `AnthropicStreamingMapper`, 새 `AnthropicAbortableHttpClient` ·
> `AnthropicCallAbort` · `AnthropicAbortRegistry` · `AnthropicCallAbortedException` · `aimon-llm-testkit` —
> `StallingSseServer` · `aimon-core` — `at.aimon.core.llm.LlmCancellation`(문서만)

---

## 1. 문제

취소 콜백이 `StreamResponse.close()` 를 부른다(`OpenAILlmClient` 의 `cancellation.onCancel(handle::close)`,
`AnthropicLlmClient` 의 `cancellation.onCancel(streamResponse::close)`). 그런데 그 close 는 다른 스레드가 같은 스트림을
읽는 중에 부르기에 안전하지 않다. 증상은 OkHttp/Okio 의 `Unbalanced enter/exit` 와, 취소 스레드가 read 가 끝날 때까지
붙잡히는 것이다.

## 2. SDK 소스로 확인한 사실

### 2.1 `StreamResponse.close()` 는 `Call.cancel()` 이 아니다

openai-java 4.78.1 의 `com.openai.core.handlers.StreamHandler` 에서 close 는 `sequence.close()` → 응답 body close →
`reader.close()` 순서다. anthropic-java 2.71.0 도 순서는 같다(response close → reader close).

- 읽는 스레드가 같은 Okio source 를 읽고 있을 때 body 를 닫으면 `AsyncTimeout` 의 enter/exit 짝이 어긋난다.
- `reader.close()` 는 `BufferedReader` 의 lock 을 잡는다. `readLine` 중인 스레드가 그 lock 을 쥐고 있으므로 취소
  스레드는 read 가 끝날 때까지 막힐 수 있다. **non-blocking 계약 위반이다.**

**정정 (설계 §2.1).** Anthropic 은 body 쪽이 이미 고쳐져 있다 — `anthropic-java-client-okhttp` 의 어댑터는 body 를
`ResponseBodyInputStream` 으로 감싸고, 그 `close()` 는 read 가 진행 중이면 body 대신 `Call` 을 취소하고(read 가 풀리면
읽던 스레드가 body 를 닫는다) 아니면 body 를 직접 닫는다. 그래서 Anthropic 에 남는 것은 (a) `reader.close()` 의 lock
대기, (b) 헤더 대기 중에는 레버가 없는 것, (c) §2.2 의 재시도 재전송이다. OpenAI 는 위 서술이 그대로 선다 — 업스트림
최신 4.86.0(2026-10-19)에도 같은 수정은 없다.

`OpenAIStreamHandle#close`, `LlmCancellation`(클래스 문서와 `onCancel`), `OpenAILlmClient` 의 클래스 문서와 주석,
`cancellation.md` §4.1 이 모두 "close 는 OkHttp call 을 취소하며 thread-safe 하다" 고 적고 있다. 틀린 서술이다.

### 2.2 SDK 는 취소된 요청을 재시도한다

`RetryingHttpClient.shouldRetry(Throwable)` 은 `IOException` 과 `OpenAIIoException` 을 재시도 대상으로 본다. 기본값은
2회이고, `OpenAIConfig.maxRetries` 를 비워 두면 그 기본값이 쓰인다. 응답 헤더를 기다리는 동안 `Call.cancel()` 을
해도 OkHttp 의 `IOException("Canceled")` 를 SDK 가 재시도로 읽어 요청을 다시 보낼 수 있다. 재시도 사이의 backoff 는
동기 `sleeper.sleep()` 이라 끊을 수 없다. transport 취소 수단만 확보해서는 부족하고, 같은 호출 안의 재시도도 막아야
한다.

### 2.3 transport 취소 수단

동기 `HttpClient.execute()` 는 `Call` 을 밖으로 내보내지 않는다. `OkHttpClient.executeAsync()` 는 future 를 cancel 하면
`call.cancel()` 을 부른다. 후보는 다음 둘이었다.

- (a) async 서비스 경로 + `CompletableFuture.cancel`
- (b) `ClientOptions.httpClient(...)` 에 끼우는 자체 `HttpClient` 데코레이터

**결정은 (b) 다 (설계 §3).** `RetryingHttpClient.executeAsync` 는 합성 future 를 돌려주어 반복 가능한(JSON) body 에서는
cancel 이 전송 future 에 닿지 않고, 닿아도 헤더 단계뿐이라 (a) 는 기각했다. 두 모듈은 레버만 다르다 — OpenAI 는
어댑터가 `Call` 을 내놓지 않고 body close 도 안전하지 않아 **자체 OkHttp 전송**(`OpenAIAbortableHttpClient`)이 `execute()`
안에서 `Call` 을 만들어 쥐고, Anthropic 은 어댑터의 `executeAsync` future(헤더 대기)와 race-safe 응답 `close()`(body)를
그대로 레버로 쓰는 **얇은 데코레이터**(`AnthropicAbortableHttpClient`)다. 호출과 시도는 요청 헤더 `X-Aimon-Abort-Key` 로
잇고 훅이 와이어 전에 벗긴다. 어느 쪽이든 abort 는 **그 요청의 Call 만** 겨눈다.

### 2.4 이미 맞게 되어 있는 것

- **등록 수명**: `SignalBackedLlmCancellation` 은 signal 에 리스너를 하나만 걸고 호출마다 `currentAbort` 를 덮어쓴다.
  `OrcaAgentExecutor` 와 `DefaultSubagentExecutor` 는 finally 에서 `clearAbort()` 를 부르고, compaction 은
  try-with-resources 로 닫는다. 남은 위험은 하나다. 취소 스레드가 이전 호출의 abort 를 읽은 직후에 다음 호출이
  시작되는 경우인데, abort 가 자기 Call 만 붙잡고 있으면 해가 없다. 그래서 이것은 새 메커니즘이 아니라 **불변식과
  테스트**로 다룬다.
- **compaction**: `DefaultCompactionEngine.callSummaryModel` 은 signal 이 trip 된 뒤에 나온 모든 결과를
  `LlmCallCancelledException` 으로 바꾸고 breaker 카운트에서 뺀다. 수정 대상이 아니라 실제 클라이언트로 끝까지 보는
  회귀 테스트 대상이다.

## 3. 수정 범위

| 대상 | 수정 사항 |
|---|---|
| transport 취소 spike (선행) | 요청별 `Call` 을 정확히 붙잡는 방식을 §2.3 의 (a)·(b) 중에서 정한다. 조건: 취소 뒤 SDK 재시도 없음, abort 는 그 요청의 Call 만 겨눔 |
| `OpenAIStreamHandle` | `abort()` 와 `close()` 를 나눈다. `abort()` 는 상태를 CAS 로 바꾸고 `Call.cancel()` 만 부르며, 스트림·reader 는 건드리지 않는다. `close()` 는 소비 스레드만 부르고 `StreamResponse.close()` 를 한 번만 실행한다. close·완료 뒤의 abort 는 no-op 이다. **abort 뒤의 close 는 no-op 이 아니다** (설계 §4.4 의 정정) — `Call.cancel()` 은 소켓을 닫을 뿐 연결·call timeout·reader·response 는 close 가 풀므로, 취소된 호출도 소비 스레드가 한 번 닫는다 |
| `OpenAILlmClient` | `onCancel(handle::close)` 를 abort 로 바꾼다. abort 는 stream 이 열리기 **전**에 이미 Call 을 알아야 하므로 헤더 대기 구간도 덮는다. 취소 뒤에는 SDK 재시도 루프가 새 요청을 보내지 못하게 한다 |
| `AnthropicLlmClient` | 같은 수정을 적용한다 |
| 문서 | §2.1 에 적은 위치를 고친다. `LlmCancellation` 의 예시에서 `StreamResponse.close()` 를 빼고, "abort 는 transport 만 끊고 정리는 소비 스레드가 한다" 는 원칙을 적는다 |
| 등록 수명 | 새 API 를 만들지 않는다. "abort 는 자기 요청만 겨눈다" 를 Javadoc 과 테스트로 고정한다. `LlmCancellation.onCancel` 이 `Registration` 을 반환하게 하는 것(`ShellCancellation`·`CancellationSignal` 선례)은 공개 SPI 변경이므로 후속으로 둔다 |

## 4. 처리 계약

- abort 콜백은 thread-safe·idempotent·non-blocking 이며 예외를 던지지 않는다. non-blocking 은 **측정한다** — 읽는
  스레드가 막혀 있는 동안 `onCancel` 콜백이 짧은 시간(예: 100ms) 안에 돌아오는지 본다. 구현에서 Anthropic 레버는
  즉시가 아니라 **상한이 있는** 쪽이다 — read 가 없을 때 어댑터의 race-safe body 를 취소 스레드에서 닫으므로
  HTTP/1.1 에서 최대 약 100ms 머문다(`StreamResponse` 나 reader 는 건드리지 않는다). 설계 기록 §11.1 · §11.6.1.
- 취소된 호출은 `LlmCallCancelledException` 으로 끝나고, **SDK 내부 재시도를 포함해** 재전송하지 않는다.
- terminal event 전에 끊긴 응답은 정상 응답으로 반환하지 않는다. terminal event 를 받은 뒤 도착한 취소는 **정상 응답
  반환**으로 정했다(설계 §4.1) — 매퍼가 terminal event 를 기록한 뒤의 전송 실패는 취소든 reset 이든 답을 되돌리지 않고,
  `STREAM_END` ⇔ `LlmResponse` 불변식을 지킨다. [`cancellation.md`](cancellation.md) §4.1.
- 취소 사유가 주 예외다. close·transport 오류는 `addSuppressed` 로 붙인다. 정상 완료 뒤 close 만 실패한 경우는 **응답을
  돌려주고 WARN** 으로 정했다(설계 §4.2) — try-with-resources 를 버리고 명시적으로 닫는다. 성공한 요청을 게이트웨이가
  재시도해 두 번 과금하는 쪽이 더 나쁜 실패이기 때문이다.
- close 예외를 무시하거나 lock 으로 감싸는 방식은 쓰지 않는다. 전자는 연결 누수를, 후자는 취소 스레드의 대기를 남긴다.

## 5. 회귀 테스트

실제 SDK 와 로컬 HTTP/1.1 chunked SSE 서버로 쓴다. `OpenAILlmClientSdkRetryTest` 의 로컬 서버 하네스를 재사용할 수
있다. Docker 가 필요 없으므로 `@Tag("docker")` 없이 일반 `test` 에 둔다.

| 시나리오 | 통과 기준 |
|---|---|
| 첫 chunk 뒤 서버가 멈춘 상태에서 다른 스레드가 취소 | 1초 안에 `LlmCallCancelledException`, 콜백도 즉시 반환, `Unbalanced enter/exit` 없음. 경쟁 조건이므로 N회 반복 |
| 응답 헤더 대기 중 취소, `maxRetries` 2 이상 | handle 생성 전에 중단, **서버가 받은 요청 정확히 1건** |
| 취소와 terminal event 가 동시에 도착 | 결과가 둘 중 하나로 일관됨, 중복 close 오류 없음. 반복 실행 |
| 같은 `SignalBackedLlmCancellation` 으로 연속 호출, 이전 호출이 끝나는 순간 trip | 이전 abort 가 다음 요청의 Call 을 취소하지 않음 |
| 실제 클라이언트로 compaction 요약 중 취소 | transcript 보존, 부분 요약 미설치, breaker 카운트 증가 없음 |
| 취소 후 자원 검사 | 서버 쪽에서 연결 종료 감지, 자체 HttpClient 를 쓴다면 진행 중 Call 목록이 빔 |
| Anthropic 에서 위 1·2·4번 | 같은 기준 |

## 6. 작업 순서

1. 재현 테스트를 쓰고 현재 코드에서 실패하는 것을 확인한다.
2. transport spike — Call 을 붙잡는 방식과 재시도 차단 방식을 정한다. SDK 버전을 올릴지도 재현 테스트 결과로 판단한다.
3. abort/close 분리 — OpenAI, 그다음 Anthropic.
4. 일반 호출과 compaction 통합 검증.
5. 문서 갱신 — `cancellation.md` §4.1·§9·부록과 Javadoc.
6. 릴리스 — 코드가 바뀌는 모듈은 `aimon-llm-openai`·`aimon-llm-anthropic` 이고 `aimon-core` 는 Javadoc 만 바뀐다.

## 7. 범위 밖

heartbeat 가 리뷰를 처음 취소한 이유는 ops 에서 따로 확인한다. 이 문서는 취소가 일어난 **뒤**의 동시성 오류와 자원
누수만 다룬다.

## 관련 문서

- [`cancellation-abort-close-design.md`](cancellation-abort-close-design.md) — 이 계획을 다듬은 승인본 설계와 구현이 벗어난 자리(§11)
- [`cancellation.md`](cancellation.md) — LLM 호출 취소 전체 설계
- [`../agent-execution/interrupt.md`](../agent-execution/interrupt.md) — 실행 취소 신호
