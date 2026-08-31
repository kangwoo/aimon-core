# LLM 멀티모달 콘텐츠

> Status: **IMPLEMENTED** — `at.aimon.core.llm.content` 의 블록 3종, `Message` 의
> `List<ContentBlock>` 단일 표현, `UserInputConverter` 의 입력 경계 변환, 양 프로바이더 컨버터,
> 영속(`JsonSessionSnapshotCodec`)·컴팩션(`MessageStripper`)·토큰 추정 소비자가 모두 들어가 있다.
> 남은 것은 §8 — 오디오 블록이 아직 없고(`AudioInput` 은 텍스트로 폴백), OpenAI 는 바이너리 문서를
> 거부한다.

`Message` 의 콘텐츠가 `String` 하나였을 때, 첨부파일은 LLM 에 도달하기 전에 사라졌다. 이 문서는 그
표현을 `List<ContentBlock>` 으로 넓히면서 **`LlmClient` 인터페이스는 그대로 둔** 설계를 기록한다.

---

## 1. 무엇을 풀었는가

`UserInput` 계층에는 이미 `ImageInput` / `AudioInput` 이 있었고 바이너리 데이터를 들고 있었다. 문제는
그 다음 한 칸이었다 — `Message` 가 `String content` 만 받았으므로 실행기는 `asText()` 로 납작하게
만들 수밖에 없었고, 프로바이더 SDK 가 멀티모달을 지원하는데도 거기까지 갈 데이터가 없었다.

```
ImageInput(byte[])  ──asText()──▶  Message(String)  ──▶  provider API
                       ↑ 여기서 바이너리가 소실
```

넓힐 곳은 `LlmClient` 시그니처가 아니라 `Message` **내부 표현**이다. 첨부파일은 새로운 호출 방식이
아니라 메시지 콘텐츠의 한 종류이기 때문이다.

---

## 2. 단일 진실의 원천 — `List<ContentBlock>`

`Message` 의 콘텐츠 표현은 **하나뿐**이다. `String content` 필드는 남아 있지 않고,
`getContent()` 는 텍스트 블록들을 join 한 **파생값**이다.

```java
private Message(Role role, List<ContentBlock> contentBlocks, ...) {
    this.contentBlocks = List.copyOf(...);
    // 불변 블록에서 파생값을 생성자에서 한 번만 계산한다
    this.textContent = this.contentBlocks.stream()
            .filter(block -> block instanceof TextContentBlock)
            .map(ContentBlock::asText).collect(Collectors.joining());
    this.hasNonTextContent = this.contentBlocks.stream()
            .anyMatch(block -> !(block instanceof TextContentBlock));
}
```

### 기각한 대안 — 이중 필드

| 대안 | 왜 기각했나 |
|------|------------|
| `String content` 와 `List<ContentBlock>` 을 **둘 다** 필드로 둔다 | 두 값이 어긋날 수 있는 순간이 생긴다. 어느 쪽이 진실인지 소비자마다 다르게 판단하게 되고, 그 불일치는 프로바이더 요청에 가서야 드러난다 |
| `content` 를 `Object` 로 두고 런타임 분기 | 타입이 계약을 말해 주지 않는다. 모든 소비자가 `instanceof` 사다리를 각자 쓰게 된다 |
| **파생 접근자** (채택) | 필드는 하나, `getContent()` 는 읽기 전용 뷰. 하위 호환 API 가 새 표현 위에서 그대로 동작한다 |

파생값을 생성자에서 미리 계산하는 것도 결정이다 — 블록이 불변이므로 매 호출 재계산은 낭비이고,
`getContent()` 는 로깅·전사·토큰 추정 경로에서 자주 불린다.

---

## 3. 블록 타입

`ContentBlock` 은 `getType()` 과 `asText()` 둘만 요구하는 인터페이스다. `asText()` 는 텍스트만
다루는 오래된 경로(로그, 전사 요약)가 블록을 만나도 무너지지 않게 하는 **강등 표현**이다.

| 타입 | 팩토리 | 데이터 | `asText()` |
|------|--------|--------|-----------|
| `TextContentBlock` | `of(String)` | `String text` | 원문 |
| `ImageContentBlock` | `ofBase64(byte[], mime)` · `ofUrl(String, mime)` | `Source.BASE64` \| `Source.URL` | 자리표시 문자열 |
| `DocumentContentBlock` | `of(byte[], mime)` · `of(byte[], mime, fileName)` | `byte[]` + 선택적 파일명 | 텍스트 기반이면 원문, 아니면 자리표시 |

### 3.1 MIME 검증은 블록 생성 시점에

두 바이너리 블록 모두 팩토리에서 `SUPPORTED_MIME_TYPES` 를 검사하고 위반이면
`IllegalArgumentException` 을 던진다.

| 블록 | 허용 MIME |
|------|-----------|
| `ImageContentBlock` | `image/png`, `image/jpeg`, `image/gif`, `image/webp` |
| `DocumentContentBlock` | `application/pdf`, `text/plain`, `text/markdown` 외 텍스트 계열 |

**fail-fast 를 고른 이유**: 지원하지 않는 MIME 을 통과시키면 실패 지점이 프로바이더 API 응답으로
밀린다. 그 시점의 에러는 어느 첨부에서 비롯됐는지 말해 주지 않고, 이미 토큰과 왕복 비용을 쓴 뒤다.
블록 생성은 사용자 입력 바로 옆이므로 메시지가 어느 파일 이야기인지 분명하다.

### 3.2 `byte[]` 방어적 복사 — 양쪽에서

`byte[]` 는 불변이 아니므로 생성자와 게터 **둘 다** 복사한다. 한쪽만 하면 불변 계약이 깨진다 —
생성자만 복사하면 게터로 받은 배열을 호출자가 고칠 수 있고, 게터만 복사하면 생성자에 넘긴 배열을
호출자가 계속 쥐고 있다.

### 3.3 `ImageContentBlock` 의 두 소스는 배타적이다

```java
public byte[] getData() {
    if (source != Source.BASE64) {
        throw new IllegalStateException("Data is not available for URL source. Use getUrl() instead.");
    }
    return data.clone();
}
```

`getUrl()` 도 대칭으로 던진다. `null` 을 돌려주는 대신 던지는 쪽을 고른 것은, URL 이미지에
`getData()` 를 부르는 코드는 **분기를 빠뜨린 것**이지 빈 값을 다룰 준비가 된 코드가 아니기 때문이다.
`getSource()` 로 먼저 갈라야 한다.

---

## 4. `Message` API

### 팩토리

| 팩토리 | 만드는 블록 |
|--------|------------|
| `Message.user(String)` | 텍스트 블록 1개 — 기존 호출부는 한 줄도 바뀌지 않았다 |
| `Message.user(List<ContentBlock>)` | 주어진 블록 그대로 (비어 있으면 `IllegalArgumentException`) |
| `Message.userWithAttachments(String, List<ContentBlock>)` | 텍스트가 비어 있지 않으면 텍스트 블록을 **앞에** 두고 첨부를 잇는다 |
| `Message.restore(Role, List<ContentBlock>, ...)` | 영속 복원 전용 — 코덱이 블록 리스트를 그대로 되살린다 |

### 파생 접근자

| 메서드 | 반환 |
|--------|------|
| `getContent()` | 텍스트 블록들의 join — **하위 호환 경로가 쓰는 것** |
| `getContentBlocks()` | 불변 리스트 |
| `hasContentBlocks()` | 블록이 하나라도 있는가 |
| `hasNonTextContentBlocks()` | 텍스트 아닌 블록이 있는가 — **프로바이더 컨버터의 분기점** |

`hasNonTextContentBlocks()` 가 따로 있는 이유는 §6 에 있다: 순수 텍스트 메시지는 SDK 의 단순
문자열 경로로 보내고, 그때만 블록 배열을 만든다.

---

## 5. 입력 경계 — `UserInputConverter`

`UserInput` → `Message` 변환은 `at.aimon.core.agent.impl.orca.UserInputConverter` 한 곳이다.
`MultimodalInput` 은 **재귀적으로 평탄화**되어 단일 블록 리스트가 된다.

| 입력 | 결과 블록 |
|------|----------|
| `TextInput` | `Message.user(String)` — 블록 리스트를 거치지 않는 지름길 |
| `ImageInput` | `ImageContentBlock.ofBase64(data, mime)` |
| `FileInput`, MIME 이 `text/*` 또는 `application/pdf` | `DocumentContentBlock.of(data, mime, fileName)` |
| `FileInput`, MIME 이 `image/*` | `ImageContentBlock.ofBase64` |
| `FileInput`, 그 외 | `TextContentBlock.of(asText())` — 강등 |
| `AudioInput` | `TextContentBlock.of(asText())` — **오디오 블록이 아직 없다**(§8) |
| `MultimodalInput` | 자식들을 재귀 변환해 이어붙인다 |

`FileInput` 이 MIME 으로 갈라지는 것은 파일 확장자가 아니라 **콘텐츠 종류가 블록 타입을 정한다**는
뜻이다. `.png` 를 `FileInput` 으로 넘겨도 이미지 블록이 된다.

---

## 6. 프로바이더 변환

두 컨버터 모두 `hasNonTextContentBlocks()` 가 `false` 면 SDK 의 단순 문자열 메시지를 만들고,
`true` 일 때만 블록 배열로 간다.

### 6.1 Anthropic — 세 종류 모두 네이티브

| 블록 | `ContentBlockParam` |
|------|--------------------|
| `TextContentBlock` | `ofText(TextBlockParam)` |
| `ImageContentBlock` (BASE64) | `ofImage(ImageBlockParam.Source.ofBase64(...))` |
| `ImageContentBlock` (URL) | `ofImage(ImageBlockParam.Source.ofUrl(...))` |
| `DocumentContentBlock` (PDF) | `ofDocument(DocumentBlockParam)` + `Base64PdfSource` |
| `DocumentContentBlock` (텍스트) | `ofDocument(DocumentBlockParam)` + `PlainTextSource` |

### 6.2 OpenAI — 문서는 텍스트 추출 또는 거부

| 블록 | `ChatCompletionContentPart` |
|------|----------------------------|
| `TextContentBlock` | `ofText` |
| `ImageContentBlock` | `ofImageUrl` — BASE64 는 data URL 로 인라인 |
| `DocumentContentBlock` (`isTextBased()`) | `ofText` — `[File: name (mime)]` 헤더 + UTF-8 본문 |
| `DocumentContentBlock` (바이너리, PDF 등) | **`MessageConversionException`** |

PDF 를 조용히 텍스트로 추출하지 않는 것이 이 문서의 가장 논쟁적인 결정이다.

| 대안 | 왜 기각했나 |
|------|------------|
| PDF 텍스트 추출 후 전송 | 추출기 의존성(PDFBox 등)이 LLM 모듈로 들어온다. 게다가 스캔 PDF 는 텍스트가 없어 **빈 메시지**가 되고, 사용자는 모델이 문서를 읽었다고 믿는다 |
| 문서 블록을 조용히 드롭 | 같은 문제 — 실패가 보이지 않는다 |
| **예외** (채택) | "OpenAI 는 문서 블록을 네이티브로 지원하지 않는다. 미리 텍스트를 추출하라"는 메시지가 호출자에게 간다. 손실은 있어도 **조용하지 않다** |

같은 첨부가 Anthropic 에서는 되고 OpenAI 에서는 안 되는 것은 프로바이더 능력의 차이를 그대로
드러낸 것이고, 그 차이를 프레임워크가 임의로 메우지 않는다는 뜻이다.

---

## 7. 블록을 아는 다른 소비자

`Message` 표현을 바꾸면 **콘텐츠를 읽는 모든 경로**가 블록을 알아야 한다. 프로바이더 컨버터만
고치면 되는 변경이 아니었다.

| 소비자 | 블록을 어떻게 다루나 |
|--------|--------------------|
| `JsonSessionSnapshotCodec` (`subagent.task.codec`) | 블록별 JSON 인코딩 — `text` / `image`(+`source`: `base64`\|`url`) / `document`. 바이너리는 표준 base64 문자열로 싣는다. 복원은 `Message.restore(...)` |
| `MessageStripper` (`agent.compact`) | 컴팩션 시 이미지는 `[image]`, 문서는 `[document]` 자리표시로 치환 — 요약 프롬프트에 원본 바이트를 다시 태우지 않는다 |
| `MessageRedactor` (`memory.redaction`) | 각 `TextContentBlock` 의 텍스트만 레닥션 대상 |
| `HeuristicTokenEstimator` · `TikTokenEstimator` | 이미지 **1500**, 문서 **1000** 토큰 고정 상한. 실제 비용은 해상도·페이지 수에 달렸으므로 안전한 상한을 쓴다 |
| `AnthropicStreamingMapper` | 스트림 조각을 블록으로 조립 — [스트리밍](streaming.md) 참조 |

### 왜 블록이 Jackson-ready 가 아닌가

`ContentBlock` 계층은 다형 타입이고 기본 생성자·세터가 없다(프로젝트의 불변 규약). 그래서 Jackson
자동 매핑 대신 **코덱이 손으로 매핑**한다. 그 대가로 저장 문서 형식이 우연한 필드 이름이 아니라
명시적 스키마가 되고, 사람이 읽을 수 있는 base64 로 남는다.

---

## 8. 남은 것

| 항목 | 현재 | 필요한 것 |
|------|------|----------|
| 오디오 | `AudioInput` → `TextContentBlock` 폴백 | `AudioContentBlock` + 양 프로바이더 오디오 파트 매핑 |
| OpenAI 바이너리 문서 | `MessageConversionException` | 어셈블리 계층의 선택적 텍스트 추출 어댑터 (LLM 모듈 밖에서) |
| 크기 상한 | 없음 — MIME 만 검사 | 첨부 바이트 상한. 지금은 프로바이더 413 이 첫 방어선이다 |
| 토큰 추정 정밀도 | 고정 1500/1000 | 이미지 해상도 기반 추정 (프로바이더별 공식이 다름) |

---

## 부록 — 참조 파일 지도

| 파일 | 역할 |
|------|------|
| `llm/content/ContentBlock.java` | `getType()` · `asText()` 두 메서드 인터페이스 |
| `llm/content/TextContentBlock.java` | 텍스트 블록 |
| `llm/content/ImageContentBlock.java` | 이미지 블록, `Source.BASE64`\|`URL` |
| `llm/content/DocumentContentBlock.java` | 문서 블록, `isTextBased()` |
| `llm/Message.java` | `List<ContentBlock>` 단일 표현 + 파생 접근자 + 팩토리 |
| `agent/input/` | `UserInput` 계층 (`TextInput`·`ImageInput`·`FileInput`·`AudioInput`·`MultimodalInput`) |
| `agent/impl/orca/UserInputConverter.java` | 입력 → 블록 변환·평탄화 |
| `llms/anthropic/AnthropicMessageConverter.java` | Anthropic 블록 파라미터 매핑 |
| `llms/openai/OpenAIMessageConverter.java` | OpenAI 콘텐츠 파트 매핑 + 문서 예외 |
| `llms/openai/exception/MessageConversionException.java` | 변환 실패 |
| `subagent/task/codec/JsonSessionSnapshotCodec.java` | 블록 JSON 인코딩·복원 |
| `agent/compact/MessageStripper.java` | 컴팩션 자리표시 치환 |
| `memory/redaction/MessageRedactor.java` | 텍스트 블록만 레닥션 |
| `llm/token/HeuristicTokenEstimator.java` · `llms/openai/token/TikTokenEstimator.java` | 블록별 토큰 추정 |

경로는 모두 `modules/<module>/src/main/java/at/aimon/core/` 이하다.

---

## 관련 문서

- [스트리밍](streaming.md) — 조각을 블록으로 조립하는 경로
- [취소](cancellation.md) — 같은 `LlmClient` 표면의 다른 확장축
- [컴팩션](../agent-execution/compaction.md) — `MessageStripper` 가 블록을 자리표시로 바꾸는 이유
- [아키텍처 개요](../../overview/architecture.md) — LLM 계층의 위치
- [LLM 프로바이더 개발 가이드](../../features/llm/llm-provider-development-guide.md) — 새 프로바이더가 지켜야 할 변환 계약
