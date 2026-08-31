# System Reminder Convention

> `<system-reminder>` 태그를 사용하여 합성(synthetic) 사용자-역할 컨텍스트를 주입하는 AIMON 프로젝트의 표준 규약입니다.

## 목적

AIMON은 에이전트 실행 중에 몇 가지 "보조 컨텍스트"를 LLM에 전달해야 합니다. 예를 들어 현재 작업 디렉토리, 오늘 날짜, `CLAUDE.md` 내용, 혹은 ReAct 루프 중간에 삽입되는 상태 리마인더 등이 있습니다. 이러한 메시지는 **LLM에 대한 힌트**이지 **end-user의 실제 입력이 아니므로**, 모델이 둘을 혼동하지 않도록 명시적으로 표시해야 합니다.

이 규약은 참조 구현가 사용하는 패턴을 차용하여 `<system-reminder key="...">...</system-reminder>` 블록으로 합성 컨텍스트를 감쌉니다. 구현은 [`SystemReminderFormatter`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/prompt/SystemReminderFormatter.java) 유틸리티로 제공됩니다.

## 사용 시점

`<system-reminder>`는 **user 역할 메시지** 안에 합성 컨텍스트를 주입할 때 사용합니다.

### 사용해야 하는 경우

- 세션 메타데이터 (작업 디렉토리, 날짜, 호스트명 등).
- 프로젝트 메모리 (`CLAUDE.md` 본문, skill 설명).
- Tool 실행 결과에 덧붙는 ephemeral 상태 (예: "파일이 수정됨" 알림).
- Agent 행동을 교정하기 위한 일회성 reminder.

### 사용하면 안 되는 경우

- **Assistant에게 주는 항시 지시사항** → 이는 **system prompt**에 넣어야 합니다. `<system-reminder>`는 user 메시지 안에 있으므로 영구적인 정책을 표현하기에 적절하지 않습니다.
- **End-user가 실제로 입력한 텍스트** → 있는 그대로 전달합니다. 감싸지 마세요.
- **Tool 반환값 자체** → `ToolResult.success(...)`의 content로 반환하고, LLM에는 tool-result 메시지로 전달합니다.

## 태그 형식

단일 블록:

```
<system-reminder key="<key>">
<body>
</system-reminder>
```

여러 블록은 빈 줄(`\n\n`) 하나로 구분하여 이어 붙입니다.

### `key` 규칙

- 반드시 `[A-Za-z0-9._-]+` 에 부합.
- 비어 있을 수 없음.
- 스코프를 나타내는 dot 표기 권장 (예: `session.cwd`, `memory.project_md`).

### `body` 규칙

- XML 메타문자 이스케이프: `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;` (이 순서).
- 기존에 `<system-reminder` 또는 `</system-reminder>` 부분 문자열을 포함할 수 없음 (중첩/스푸핑 방지 — `IllegalArgumentException` 발생).
- 빈 본문은 허용 (마커 사이에 빈 줄만 남음).

## 예약된 key 목록

후속 PR에서 `<system-reminder>`를 실제로 사용하기 시작하면 아래 표를 채워 주세요. 새로운 key를 도입할 때는 먼저 이 표에 등록하고 PR 리뷰 대상에 포함시킵니다.

| key | purpose | introduced in |
|-----|---------|---------------|
| _(아직 등록된 키 없음)_ | | |

## 예제

### 단일 reminder

```java
String block = SystemReminderFormatter.wrap(
        "session.cwd",
        "/home/kangwoo/projects/aimon-core");
```

결과:

```
<system-reminder key="session.cwd">
/home/kangwoo/projects/aimon-core
</system-reminder>
```

### 여러 reminder (순서 유지)

```java
Map<String, String> parts = new LinkedHashMap<>();
parts.put("session.cwd", "/tmp");
parts.put("session.date", "2026-04-23");

String injected = SystemReminderFormatter.wrapMany(parts);
```

결과:

```
<system-reminder key="session.cwd">
/tmp
</system-reminder>

<system-reminder key="session.date">
2026-04-23
</system-reminder>
```

### XML 메타문자 이스케이프

```java
SystemReminderFormatter.wrap("diff", "a < b && c > d");
// → body: "a &lt; b &amp;&amp; c &gt; d"
```

## 관련 문서

- [`SystemReminderFormatter`](../../../modules/aimon-core/src/main/java/at/aimon/core/agent/prompt/SystemReminderFormatter.java) — 구현체.
