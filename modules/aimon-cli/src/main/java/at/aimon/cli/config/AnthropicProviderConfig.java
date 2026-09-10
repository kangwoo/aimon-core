package at.aimon.cli.config;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import at.aimon.core.llms.anthropic.AnthropicThinkingDisplay;
import at.aimon.core.llms.anthropic.AnthropicThinkingMode;

/**
 * yaml 로 적은 Anthropic 전용 설정 — {@code llm.anthropic} 아래의 네 키.
 *
 * <p>
 * 네 키 모두 <b>이름이 Anthropic 개념을 담고 있어서</b> 공통 {@code llm.*} 이 아니라 벤더 네임스페이스로 내려왔다 —
 * "thinking" 은 이 현상에 대한 Anthropic 의 단어이고(이 저장소의 중립 명사는 {@code ReasoningEffort} ·
 * {@code ReasoningTrace} 다), {@code budget_tokens} 는 Anthropic 요청 본문의 필드 이름 그대로이며,
 * "thinking block" 은 서명이 붙은 {@code thinking} 콘텐츠 블록이라는 와이어 명사다. 네 번째인
 * {@code thinkingDisplay} 는 그 기준에 두 번 걸린다 — "thinking" 이 벤더의 단어인 데다 {@code display} 는
 * 그 {@code thinking} 객체 안의 필드 이름이다. 기준은
 * {@code docs/design/llm/model-capability-config-key.md} §2.7 이고, 그 기준이 처음으로 "쪼갠다" 를 낸 자리다.
 *
 * <p>
 * 필드가 박싱 타입인 것은 <b>적지 않은 것과 값으로 적은 것을 구별하기 위해서</b>다. 적지 않은 필드는 setter 가 아예
 * 호출되지 않으므로 {@link at.aimon.core.llms.anthropic.AnthropicConfig} 의 기본값(모드 {@code OFF}, 예산 없음,
 * replay {@code true})이 그대로 선다 — 즉 이 블록을 쓰지 않는 배포의 요청은 글자 하나 바뀌지 않는다. 원시
 * {@code boolean replayThinkingBlocks = true} 였다면 "적지 않음" 과 "true 로 적음" 이 같아져
 * {@link #isEmpty()} 가 답할 수 없다.
 *
 * <p>
 * {@code thinkingMode} 는 벤더 enum 에 직접 바인딩한다. CLI 는 {@code aimon-llm-anthropic} 을
 * {@code implementation} 으로 들고 있으므로 그 타입이 모든 배포의 런타임에 있다. 스타터는 같은 모듈이
 * {@code compileOnly} 라 이 선택을 할 수 없고 {@code String} 으로 받는다 — 두 표면이 다른 이유는
 * {@code AimonProperties.Llm.Anthropic} 의 javadoc 에 있다.
 *
 * <p>
 * 그 필드만 <b>직접 쓴 디시리얼라이저</b>를 지나는 이유는 하나뿐이고, 네 값 중 하나가 YAML 의 예약어라는
 * 것이다 — {@link ThinkingModeDeserializer} 를 볼 것.
 *
 * <p>
 * 세터를 가진 가변 POJO 인 것은 {@code at.aimon.cli.config} 패키지의 관례다. 이 패키지의 타입은 전부 Jackson 이
 * yaml 에서 채우는 바인딩 대상이고, 도메인 타입이 아니다 — {@code AimonProperties} 의 클래스 javadoc 이 같은
 * 결정을 스타터 쪽에서 길게 서술한다.
 */
public class AnthropicProviderConfig {

    @JsonDeserialize(using = ThinkingModeDeserializer.class)
    private AnthropicThinkingMode thinkingMode;
    private Integer thinkingBudgetTokens;
    private AnthropicThinkingDisplay thinkingDisplay;
    private Boolean replayThinkingBlocks;

    /** AnthropicProviderConfig를 생성한다. */
    public AnthropicProviderConfig() {
    }

    /**
     * 이 요청이 어느 thinking 방언을 말하는가.
     *
     * @return 네 값 중 하나, 또는 적지 않았으면 null ({@code OFF} 가 선다)
     */
    public AnthropicThinkingMode getThinkingMode() {
        return thinkingMode;
    }

    public void setThinkingMode(AnthropicThinkingMode thinkingMode) {
        this.thinkingMode = thinkingMode;
    }

    /**
     * {@code EXTENDED} 방언의 명시적 {@code budget_tokens}. 1024 이상이어야 하고
     * {@code thinkingMode: extended} 아래에서만 쓸 수 있다 — 다른 모드에서는 기동이 실패한다.
     *
     * @return 토큰 수, 또는 적지 않았으면 null (호출의 reasoning effort 에서 파생된다)
     */
    public Integer getThinkingBudgetTokens() {
        return thinkingBudgetTokens;
    }

    public void setThinkingBudgetTokens(Integer thinkingBudgetTokens) {
        this.thinkingBudgetTokens = thinkingBudgetTokens;
    }

    /**
     * 모델의 thinking 텍스트를 사용자에게 흘려보낼 것인가 — 그리고 adaptive 방언에서는 그 텍스트를 애초에 받을 것인가.
     *
     * <p>
     * 한 키가 두 일을 하고, 어느 쪽이 무는지는 방언이 정한다. {@code adaptive} 에서는 요청에 {@code thinking.display}
     * 를 쓰고(그것이 없으면 이 세대의 모델은 thinking 텍스트를 아예 주지 않는다) 동시에 전달 게이트를 연다.
     * {@code extended} 에서는 델타가 이미 오고 있으므로 게이트만 열고 {@code display} 는 보내지 않는다 —
     * 클라이언트가 그 사실을 한 번 경고한다. {@code off} 아래에서는 아무것도 닿지 않으며 역시 한 번 경고한다.
     *
     * <p>
     * 적지 않으면 요청도 이벤트도 이 키가 없던 때와 글자 하나 다르지 않다.
     *
     * @return {@code summarized}, 또는 적지 않았으면 null (thinking 텍스트는 흐르지 않는다)
     */
    public AnthropicThinkingDisplay getThinkingDisplay() {
        return thinkingDisplay;
    }

    public void setThinkingDisplay(AnthropicThinkingDisplay thinkingDisplay) {
        this.thinkingDisplay = thinkingDisplay;
    }

    /**
     * 저장된 thinking 블록을 다음 요청에 되싣는가. 기본은 되싣는 것이다.
     *
     * @return {@code false} 면 되싣지 않는다, 또는 적지 않았으면 null ({@code true} 가 선다)
     */
    public Boolean getReplayThinkingBlocks() {
        return replayThinkingBlocks;
    }

    public void setReplayThinkingBlocks(Boolean replayThinkingBlocks) {
        this.replayThinkingBlocks = replayThinkingBlocks;
    }

    /**
     * 이 블록에 적힌 것이 하나도 없는가.
     *
     * <p>
     * {@code anthropic:} 이라고만 적고 아무 자식도 두지 않은 블록과, 블록 자체가 없는 설정을 같은 것으로 만든다 —
     * 그래야 "읽지 않는 분기가 이 블록을 거절한다" 가 빈 블록에 대해 발화하지 않는다.
     *
     * @return 네 키가 모두 비어 있으면 true
     */
    public boolean isEmpty() {
        return thinkingMode == null && thinkingBudgetTokens == null && thinkingDisplay == null
                && replayThinkingBlocks == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final AnthropicProviderConfig that = (AnthropicProviderConfig) o;
        return thinkingMode == that.thinkingMode && Objects.equals(thinkingBudgetTokens, that.thinkingBudgetTokens)
                && thinkingDisplay == that.thinkingDisplay
                && Objects.equals(replayThinkingBlocks, that.replayThinkingBlocks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(thinkingMode, thinkingBudgetTokens, thinkingDisplay, replayThinkingBlocks);
    }

    @Override
    public String toString() {
        return "AnthropicProviderConfig{" + "thinkingMode=" + thinkingMode + ", thinkingBudgetTokens="
                + thinkingBudgetTokens + ", thinkingDisplay=" + thinkingDisplay + ", replayThinkingBlocks="
                + replayThinkingBlocks + '}';
    }

    /**
     * {@code thinkingMode} 를 <b>파서가 읽은 원문 스칼라</b>에서 접는다. 이 필드에만 붙는 예외이며, 이유는 하나다 —
     * 네 값 중 {@code off} 가 YAML 1.1 의 예약어다.
     *
     * <p>
     * 실측(jackson-dataformat-yaml 2.18 + snakeyaml 2.5): {@code thinkingMode: off} 는 {@code VALUE_FALSE}
     * 토큰이 되고, 기본 enum 디시리얼라이저는 그것을 <em>"Cannot deserialize … from Boolean value"</em> 로
     * 거절한다. 즉 그냥 두면 <b>네 값 중 하나가 문서에 적힌 대로는 동작하지 않고</b>, 운영자는 따옴표가
     * 답이라는 것을 알 수 없는 메시지를 받는다. 같은 토큰에서 {@code getText()} 는 원문 {@code "off"} 를
     * 그대로 돌려주므로, 텍스트로 접으면 그 한 값이 살아난다.
     *
     * <p>
     * 넓히지는 않는다. YAML 이 boolean 으로 읽는 다른 철자({@code no} · {@code false})는 이 enum 의 값이 아니므로
     * 여기서도 거절된다 — 되살리는 것은 <b>우리가 문서에 적은 철자</b>뿐이고, 토큰 종류를 무시하는 것이 아니다.
     * 대소문자를 접는 것은 {@code CliConfigLoader} 의 {@code ACCEPT_CASE_INSENSITIVE_ENUMS} 가 이 필드에는
     * 닿지 않기 때문이며(그 기능은 기본 enum 디시리얼라이저의 것이다), 접는 방식은 스타터의 fold 와 같이
     * {@code values()} 를 돈다 — 두 표면이 서로 다른 철자를 받게 되지 않는다.
     *
     * <p>
     * <b>그 스타터 쪽 fold 의 주소는
     * {@code AimonLlmAutoConfiguration.AnthropicClientConfiguration.thinkingMode(String)} 이다.</b> 두 벌인 것은
     * 피할 수 없다 — 이쪽은 enum 을, 저쪽은 {@code String} 을 바인딩한다 — 대신 <b>둘 다
     * {@code AnthropicThinkingMode.values()} 를 돌기 때문에 다섯 번째 상수가 한쪽에만 조용히 빠질 수는 없다.</b>
     * 안심해도 되는 이 사실을 어느 쪽도 말하지 않아서, 한 벌을 먼저 본 사람이 다른 벌이 있다는 것도, 그 쌍이
     * 구조적으로 안전하다는 것도 알 길이 없었다.
     */
    public static class ThinkingModeDeserializer extends JsonDeserializer<AnthropicThinkingMode> {

        @Override
        public AnthropicThinkingMode deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            final String written = parser.getText() == null ? "" : parser.getText().trim();
            for (AnthropicThinkingMode candidate : AnthropicThinkingMode.values()) {
                if (candidate.name().equalsIgnoreCase(written)) {
                    return candidate;
                }
            }
            final StringBuilder accepted = new StringBuilder();
            for (AnthropicThinkingMode candidate : AnthropicThinkingMode.values()) {
                accepted.append(accepted.length() == 0 ? "" : ", ").append(candidate.name().toLowerCase(Locale.ROOT));
            }
            // weirdStringException rather than a plain IOException: it produces an InvalidFormatException, which is
            // a JsonMappingException, which is what CliConfigLoader turns into "Invalid configuration structure".
            // A bare IOException would land in the loader's I/O branch and tell the operator their file could not
            // be read.
            throw context.weirdStringException(written, AnthropicThinkingMode.class,
                    "not a thinking mode. Accepted values: " + accepted);
        }
    }
}
