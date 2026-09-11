# LLM 멀티모달 콘텐츠 (Multimodal Message Content)

> Status: **IMPLEMENTED** — `at.aimon.core.llm.content` 의 블록 3종, `Message` 의 `List<ContentBlock>` 단일 표현,
> `UserInputConverter` 의 입력 경계 변환, 세 메시지 변환기(Anthropic · OpenAI Chat Completions · OpenAI Responses),
> 영속 · 컴팩션 · 레닥션 · 토큰 추정 소비자가 들어가 있다. 남은 것은 §10.
>
> 적용 대상: `aimon-core` — `at.aimon.core.llm.content`(`ContentBlock`, `TextContentBlock`, `ImageContentBlock`,
> `DocumentContentBlock`) · `at.aimon.core.llm`(`Message`) · `at.aimon.core.agent.input`(`UserInput` 계층) ·
> `at.aimon.core.agent.impl.orca`(`UserInputConverter`) · `aimon-llm-anthropic` / `aimon-llm-openai` — 메시지 변환기.

---

## 1. 문제와 범위 — 첨부는 메시지 콘텐츠의 한 종류다

첨부는 새로운 호출 방식이 아니라 메시지 콘텐츠의 한 종류다. 그래서 `LlmClient` 시그니처는 그대로 두고 `Message` 의
내부 표현을 `List<ContentBlock>` 으로 둔다. `Message` 가 문자열만 담으면 `UserInput` 의 바이너리(`ImageInput`,
`FileInput`)는 provider 에 닿기 전에 `asText()` 로 소실된다.

이 문서가 정하는 것은 세 가지다 — 블록의 모양과 불변식(§2~§4), 사용자 입력이 블록이 되는 경계(§5), 블록이 각
provider 의 네이티브 파트가 되는 규칙(§6). 콘텐츠를 읽는 다른 경로가 블록을 어떻게 다루는지는 §7 이다.

**하지 않는 것**:

- provider 가 네이티브로 받지 못하는 블록을 프레임워크가 임의로 변환해 메우지 않는다 — 문서를 텍스트로 추출하거나
  조용히 버리지 않는다(§6.2)
- 오디오는 블록으로 표현하지 않는다. `AudioInput` 은 텍스트로 강등된다(§5, §10)
- 첨부의 바이트 크기를 제한하지 않는다. 검증은 MIME 뿐이다(§10)

---

## 2. `Message` 콘텐츠 — `List<ContentBlock>` 단일 표현

`Message` 의 콘텐츠 표현은 **하나뿐**이다. `getContent()` 는 텍스트 블록들을 이어 붙인 **파생값**이다.

```java
private Message(Role role, List<ContentBlock> contentBlocks, ...) {
    this.contentBlocks = List.copyOf(contentBlocks);
    // 불변 블록에서 파생값을 생성자에서 한 번만 계산한다
    this.textContent = this.contentBlocks.stream()
            .filter(block -> block instanceof TextContentBlock)
            .map(ContentBlock::asText).collect(Collectors.joining());
    this.hasNonTextContent = this.contentBlocks.stream()
            .anyMatch(block -> !(block instanceof TextContentBlock));
}
```

필드가 하나이므로 두 표현이 어긋나는 순간이 없고, 어느 쪽이 진실인지 소비자마다 판단할 일이 없다. `getContent()`
는 읽기 전용 뷰라서 텍스트만 다루는 경로가 새 표현 위에서 그대로 동작한다.

파생값을 생성자에서 미리 계산하는 것도 결정이다 — 블록이 불변이므로 매 호출 재계산은 낭비이고, `getContent()` 는
로깅 · 전사 · 토큰 추정 경로에서 자주 불린다. `Message.restore(...)` 로 복원한 메시지도 같은 생성자를 지나 파생값을
똑같이 다시 계산한다.

---

## 3. 블록 타입 — `ContentBlock` 3종

`ContentBlock` 은 `getType()` 과 `asText()` 둘만 요구하는 인터페이스다. `asText()` 는 텍스트만 다루는 경로(로그,
전사 요약)가 블록을 만나도 무너지지 않게 하는 **강등 표현**이다.

| 타입 | `getType()` | 팩토리 | 데이터 | `asText()` |
|---|---|---|---|---|
| `TextContentBlock` | `text` | `of(String)` | `String text` | 원문 |
| `ImageContentBlock` | `image` | `ofBase64(byte[], mime)` · `ofUrl(String, mime)` | `Source.BASE64` 의 `byte[]` \| `Source.URL` 의 URL | 자리표시 `[Image: <mime>, <N> bytes]` / `[Image: <mime>, <url>]` |
| `DocumentContentBlock` | `document` | `of(byte[], mime)` · `of(byte[], mime, fileName)` | `byte[]` + 선택적 파일명 | 자리표시 `[Document: <mime>[, <fileName>], <N> bytes]` |

`DocumentContentBlock.isTextBased()` 는 MIME 이 `text/` 로 시작하는지 답한다. provider 변환기가 텍스트 문서와
바이너리 문서를 가르는 기준이다(§6).

### 3.1 MIME 검증은 블록 생성 시점에

두 바이너리 블록 모두 팩토리에서 `SUPPORTED_MIME_TYPES` 를 검사하고 위반이면 `IllegalArgumentException` 을 던진다.

| 블록 | 허용 MIME |
|---|---|
| `ImageContentBlock` | `image/png`, `image/jpeg`, `image/gif`, `image/webp` |
| `DocumentContentBlock` | `application/pdf`, `text/plain`, `text/markdown`, `text/html`, `text/csv` |

**fail-fast 를 고른 이유**: 지원하지 않는 MIME 을 통과시키면 실패 지점이 provider API 응답으로 밀린다. 그 시점의
에러는 어느 첨부에서 비롯됐는지 말해 주지 않고, 이미 토큰과 왕복 비용을 쓴 뒤다. 블록 생성은 사용자 입력 바로
옆이므로 메시지가 어느 파일 이야기인지 분명하다.

### 3.2 `byte[]` 방어적 복사 — 양쪽에서

`byte[]` 는 불변이 아니므로 생성자와 게터 **둘 다** 복사한다. 한쪽만 하면 불변 계약이 깨진다 — 생성자만 복사하면
게터로 받은 배열을 호출자가 고칠 수 있고, 게터만 복사하면 생성자에 넘긴 배열을 호출자가 계속 쥐고 있다.

### 3.3 `ImageContentBlock` 의 두 소스는 배타적이다

```java
public byte[] getData() {
    if (source != Source.BASE64) {
        throw new IllegalStateException("Data is not available for URL source. Use getUrl() instead.");
    }
    return data.clone();
}
```

`getUrl()` 도 BASE64 소스에서 대칭으로 던진다. `null` 을 돌려주는 대신 던지는 이유는, URL 이미지에 `getData()` 를
부르는 코드는 **분기를 빠뜨린 것**이지 빈 값을 다룰 준비가 된 코드가 아니기 때문이다. `getSource()` 로 먼저 가른다.

---

## 4. `Message` API — 팩토리와 파생 접근자

### 4.1 팩토리

| 팩토리 | 만드는 블록 |
|---|---|
| `Message.user(String)` | 텍스트 블록 1개 — 텍스트만 쓰는 호출부는 블록을 몰라도 된다 |
| `Message.user(List<ContentBlock>)` | 주어진 블록 그대로 (비어 있으면 `IllegalArgumentException`) |
| `Message.userWithAttachments(String, List<ContentBlock>)` | 텍스트가 비어 있지 않으면 텍스트 블록을 **앞에** 두고 첨부를 잇는다 (둘 다 비면 `IllegalArgumentException`) |
| `Message.restore(Role, List<ContentBlock>, …)` | 영속 복원 전용. 오버로드 2종(reasoning trace 리스트를 받는 것과 받지 않는 것)이며, 살아 있는 대화가 만들 수 있는 모든 메시지 모양을 손실 없이 되살린다 |

### 4.2 파생 접근자

| 메서드 | 반환 |
|---|---|
| `getContent()` | 텍스트 블록들의 join — 텍스트만 다루는 경로가 쓰는 것 |
| `getContentBlocks()` | 불변 리스트 |
| `hasContentBlocks()` | 블록이 하나라도 있는가 |
| `hasNonTextContentBlocks()` | 텍스트 아닌 블록이 있는가 — **provider 변환기의 분기점**(§6) |

`Message` 는 콘텐츠 블록과 별도로 provider 의 불투명 추론 payload 리스트(`getReasoningTraces()`)도 가진다. 그 슬롯의
모양과 규칙은 [`reasoning-traces.md`](reasoning-traces.md) 가 정본이다.

---

## 5. 입력 경계 — `UserInputConverter`

`UserInput` → `Message` 변환은 `UserInputConverter` 한 곳이다. `MultimodalInput` 은 **재귀적으로 평탄화**되어 단일
블록 리스트가 된다.

| 입력 | 결과 |
|---|---|
| 최상위 `TextInput` | `Message.user(String)` — 블록 리스트를 거치지 않는 지름길 |
| `MultimodalInput` 안의 `TextInput` | `TextContentBlock.of(asText())` |
| `ImageInput` | `ImageContentBlock.ofBase64(data, mime)` |
| `FileInput`, MIME 이 `text/*` 또는 `application/pdf` | `DocumentContentBlock.of(data, mime, fileName)` — 허용 목록(§3.1) 밖의 `text/*` 는 여기서 `IllegalArgumentException` |
| `FileInput`, MIME 이 `image/*` | `ImageContentBlock.ofBase64(data, mime)` |
| `FileInput`, 그 외 | `TextContentBlock.of(asText())` — 강등 |
| `AudioInput` | `TextContentBlock.of(asText())` — 오디오 블록이 없다(§10) |
| `MultimodalInput` | 자식들을 재귀 변환해 이어 붙인다 |

`FileInput` 이 MIME 으로 갈라지는 것은 파일 확장자가 아니라 **콘텐츠 종류가 블록 타입을 정한다**는 뜻이다. 이미지
MIME 을 가진 `FileInput` 은 이미지 블록이 된다.

---

## 6. provider 변환 — 순수 텍스트는 문자열, 블록은 네이티브 파트

모든 변환기는 user 메시지에서 `hasNonTextContentBlocks()` 가 `false` 면 SDK 의 단순 문자열 콘텐츠를 만들고, `true`
일 때만 블록 배열을 만든다. 순수 텍스트 메시지(일반적인 경우)의 요청 모양은 블록 표현의 영향을 받지 않는다.

### 6.1 Anthropic — 세 종류 모두 네이티브

| 블록 | `ContentBlockParam` |
|---|---|
| `TextContentBlock` | `ofText(TextBlockParam)` |
| `ImageContentBlock` (BASE64) | `ofImage(ImageBlockParam)` + `ImageBlockParam.Source.ofBase64(...)` |
| `ImageContentBlock` (URL) | `ofImage(ImageBlockParam)` + `ImageBlockParam.Source.ofUrl(UrlImageSource)` |
| `DocumentContentBlock` (텍스트) | `ofDocument(DocumentBlockParam)` + `PlainTextSource` |
| `DocumentContentBlock` (PDF) | `ofDocument(DocumentBlockParam)` + `Base64PdfSource` |

### 6.2 OpenAI — 텍스트 문서는 텍스트로, 바이너리 문서는 거부

Chat Completions 변환기(`OpenAIMessageConverter`):

| 블록 | `ChatCompletionContentPart` |
|---|---|
| `TextContentBlock` | `ofText` |
| `ImageContentBlock` | `ofImageUrl` — BASE64 는 `data:<mime>;base64,…` URL 로 인라인, URL 소스는 그대로 |
| `DocumentContentBlock` (`isTextBased()`) | `ofText` — 파일명이 있으면 `[File: <name> (<mime>)]` 헤더 + UTF-8 본문 |
| `DocumentContentBlock` (바이너리, PDF 등) | **`MessageConversionException`** — "OpenAI does not support document content blocks natively. Consider extracting text from the document before sending." |

**Responses 경로도 같은 규칙이다.** `OpenAIResponsesMessageConverter` 는 텍스트 문서를 같은 헤더의 `input_text` 로
보내고, 바이너리 문서에는 같은 `MessageConversionException` 을 던진다. 같은 `Message` 가 두 엔드포인트에서 다른 뜻이
되지 않게 하기 위해서다 — 패리티에는 예외도 포함된다. Responses API 의 네이티브 `input_file` 슬롯을 쓰지 않는 이유는
[`openai-responses-path.md`](openai-responses-path.md) 에 있다.

바이너리 문서를 텍스트로 추출하지도, 조용히 버리지도 않고 **예외**를 던진다. 호출자는 "미리 텍스트를 추출하라" 는
메시지를 받는다. 손실은 있어도 **조용하지 않다**(기각 이유는 §8).

같은 첨부가 Anthropic 에서는 되고 OpenAI 에서는 안 되는 것은 provider 능력의 차이를 그대로 드러낸 것이다. 그 차이를
프레임워크가 임의로 메우지 않는다.

---

## 7. 블록을 아는 소비자

`Message` 의 표현은 콘텐츠를 읽는 모든 경로가 공유하므로, provider 변환기뿐 아니라 영속 코덱 · 컴팩션 · 레닥션 ·
토큰 추정도 블록을 안다.

| 소비자 | 블록을 어떻게 다루나 |
|---|---|
| `JsonSessionSnapshotCodec` (`subagent.task.codec`) | 블록별 JSON 인코딩 — `text` / `image`(+`source`: `base64` \| `url`) / `document`. 바이너리는 표준 base64 문자열로 싣는다. 모르는 블록 타입은 `SessionSnapshotCodecException`. 복원은 `Message.restore(...)`. 메시지의 reasoning 배열은 [`reasoning-traces.md`](reasoning-traces.md) |
| `MessageStripper` (`agent.compact`) | 컴팩션 요약 호출 전에 이미지는 `[image]`, 문서는 `[document]` 자리표시로 치환 — 요약 프롬프트에 원본 바이트를 다시 태우지 않는다. 컴팩션 전체는 [`../agent-execution/compaction.md`](../agent-execution/compaction.md) |
| `MessageRedactor` (`memory.redaction`) | 메모리 파생 큐의 레닥션 게이트. `TextContentBlock` 의 텍스트만 레닥션하고 비텍스트 블록은 그대로 둔다 |
| `HeuristicTokenEstimator` · `TikTokenEstimator` | 이미지 **1500**, 문서 **1000** 토큰 고정. 실제 비용은 해상도·페이지 수에 달렸으므로 안전한 상한을 쓴다 |

### 7.1 코덱은 블록을 손으로 매핑한다

`ContentBlock` 계층은 다형 타입이고 기본 생성자·세터가 없다(프로젝트의 불변 규약). 그래서 Jackson 자동 매핑 대신
**코덱이 손으로 매핑**한다. 그 대가로 저장 형식이 우연한 필드 이름이 아니라 명시적 스키마가 되고, 사람이 읽을 수
있는 base64 로 남는다.

---

## 8. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| `LlmClient` 에 첨부를 받는 새 호출 시그니처를 둔다 | 첨부는 호출 방식이 아니라 메시지 콘텐츠의 한 종류다. 대화 이력 안의 첨부를 표현하지 못한다 |
| `String content` 와 `List<ContentBlock>` 을 **둘 다** 필드로 둔다 | 두 값이 어긋날 수 있는 순간이 생긴다. 어느 쪽이 진실인지 소비자마다 다르게 판단하게 되고, 그 불일치는 provider 요청에 가서야 드러난다 |
| `content` 를 `Object` 로 두고 런타임 분기 | 타입이 계약을 말해 주지 않는다. 모든 소비자가 `instanceof` 사다리를 각자 쓰게 된다 |
| MIME 을 검증하지 않고 provider 가 거절하게 둔다 | 실패가 어느 첨부에서 비롯됐는지 모르는 provider 응답으로 밀리고, 토큰과 왕복 비용을 쓴 뒤다 |
| 반대 소스의 게터가 `null` 을 돌려준다 | 분기를 빠뜨린 코드가 빈 값으로 조용히 진행한다 |
| OpenAI 로 가는 PDF 를 텍스트 추출 후 전송 | 추출기 의존성(PDFBox 등)이 LLM 모듈로 들어온다. 스캔 PDF 는 텍스트가 없어 **빈 메시지**가 되고, 사용자는 모델이 문서를 읽었다고 믿는다 |
| OpenAI 로 가는 문서 블록을 조용히 드롭 | 같은 문제 — 실패가 보이지 않는다 |
| 블록 계층을 Jackson 자동 매핑으로 영속 | 불변 규약(기본 생성자·세터 없음)과 맞지 않고, 저장 형식이 필드 이름에 우연히 묶인다 |

---

## 9. 하지 말 것

- **`Message` 에 콘텐츠 표현을 하나 더 두지 말 것.** 텍스트 뷰는 블록에서 파생한다
- **MIME 검증을 provider 요청 시점으로 미루지 말 것.** 블록 팩토리에서 던진다
- **`byte[]` 를 복사 없이 받거나 내주지 말 것.** 생성자와 게터 양쪽에서 복사한다
- **`ImageContentBlock` 의 데이터·URL 을 `getSource()` 로 가르기 전에 읽지 말 것**
- **provider 변환기에서 네이티브로 못 받는 블록을 조용히 드롭하거나 추출해 보내지 말 것.** 예외로 알린다
- **두 OpenAI 변환기의 블록 규칙을 갈라놓지 말 것.** 같은 `Message` 는 두 엔드포인트에서 같은 뜻이어야 한다 — 예외까지
- **새 블록 타입을 더할 때 provider 변환기만 고치지 말 것.** 코덱은 모르는 타입을 복원하지 못하고, 컴팩션 · 레닥션 ·
  토큰 추정도 블록을 안다(§7)

---

## 10. 남은 것

- **오디오 블록** — `AudioInput` 은 `TextContentBlock` 으로 강등된다. `AudioContentBlock` 과 양 provider 의 오디오 파트
  매핑이 없다(백로그 미등록)
- **OpenAI 바이너리 문서** — 두 엔드포인트 모두 `MessageConversionException` 으로 거부한다. 길은 둘이다. 텍스트 추출은
  LLM 모듈 밖, 어셈블리 계층의 선택적 어댑터로 둔다 — 추출기 의존성을 LLM 모듈에 들이지 않기 위해서다. 또는 Responses
  API 의 네이티브 `input_file` 을 쓴다 — 같은 `Message` 가 두 엔드포인트에서 다른 뜻이 되는 문제를 풀고 그 능력을
  라이브로 확인해야 한다([`openai-responses-path.md`](openai-responses-path.md)). 소비자가 없다(백로그 미등록)
- **첨부 크기 상한** — MIME 만 검사하고 바이트 상한은 없다. 지금은 provider 의 413 이 첫 방어선이다(백로그 미등록)
- **토큰 추정 정밀도** — 이미지 1500 · 문서 1000 고정이다. 해상도 기반 추정은 provider 마다 공식이 달라 두지
  않았다(백로그 미등록)

---

## 부록 — 참조 파일 지도

core 경로는 `modules/aimon-core/src/main/java/at/aimon/core/` 기준, provider 는
`modules/aimon-llm-anthropic/src/main/java/at/aimon/core/llms/anthropic/` ·
`modules/aimon-llm-openai/src/main/java/at/aimon/core/llms/openai/` 기준이다.

| 파일 | 무엇을 확인하나 |
|---|---|
| `llm/content/ContentBlock.java` | `getType()` · `asText()` 두 메서드 인터페이스 |
| `llm/content/TextContentBlock.java` | 텍스트 블록 |
| `llm/content/ImageContentBlock.java` | 이미지 블록, `Source.BASE64` \| `URL` 배타성, MIME 허용 목록, 방어적 복사 |
| `llm/content/DocumentContentBlock.java` | 문서 블록, `isTextBased()`, MIME 허용 목록, 방어적 복사 |
| `llm/Message.java` | `List<ContentBlock>` 단일 표현, 파생 접근자, 팩토리, `restore` 오버로드 |
| `agent/input/` | `UserInput` 계층(`TextInput` · `ImageInput` · `FileInput` · `AudioInput` · `MultimodalInput`) |
| `agent/impl/orca/UserInputConverter.java` | 입력 → 블록 변환 · 평탄화 |
| `AnthropicMessageConverter.java` | Anthropic 블록 파라미터 매핑, 순수 텍스트 지름길 |
| `OpenAIMessageConverter.java` | Chat Completions 콘텐츠 파트 매핑 + 문서 예외 |
| `OpenAIResponsesMessageConverter.java` | Responses 입력 콘텐츠 매핑 + 같은 문서 예외 |
| `exception/MessageConversionException.java` (openai) | 변환 실패 |
| `subagent/task/codec/JsonSessionSnapshotCodec.java` | 블록 JSON 인코딩 · 복원 |
| `agent/compact/MessageStripper.java` | 컴팩션 자리표시 치환 |
| `memory/redaction/MessageRedactor.java` | 텍스트 블록만 레닥션 |
| `llm/token/HeuristicTokenEstimator.java`, `token/TikTokenEstimator.java` (openai) | 블록별 토큰 추정 |

---

## 관련 문서

- [`openai-responses-path.md`](openai-responses-path.md) — Responses 경로의 메시지 변환과 `input_file` 을 쓰지 않는 이유
- [`reasoning-traces.md`](reasoning-traces.md) — `Message` 가 함께 가지는 reasoning trace 슬롯
- [`streaming.md`](streaming.md) — 응답 쪽 전송
- [`cancellation.md`](cancellation.md) — 같은 `LlmClient` 표면의 다른 확장
- [`../agent-execution/compaction.md`](../agent-execution/compaction.md) — `MessageStripper` 가 블록을 자리표시로 바꾸는 이유
- [`../../overview/architecture.md`](../../overview/architecture.md) — LLM 계층의 위치
- [`../../features/llm/llm-provider-development-guide.md`](../../features/llm/llm-provider-development-guide.md) — 새 provider 가 지켜야 할 변환 계약
