package at.aimon.cli.config;

import java.util.Objects;

import at.aimon.core.llms.openai.OpenAiReasoningSummary;

/**
 * yaml 로 적은 OpenAI 전용 설정 — {@code llm.openai} 아래의 한 키.
 *
 * <p>
 * <b>이 라운드가 여는 네임스페이스다.</b> 지금까지 {@code llm.*} 아래 벤더 블록은 {@code anthropic} 하나뿐이었고,
 * {@code docs/backlog/llm-config-surface-open-items.md} 의 {@code L-2} 는 {@code responsesApiEnabled} 가
 * 언젠가 이리로 온다고 적어 두었다. 먼저 도착한 것이 이 키다.
 *
 * <p>
 * 판정 기준은 옆 블록과 같은 {@code docs/design/llm/model-capability-config-key.md} §2.7 이고, 첫 번째 검사가
 * 그대로 걸린다 — {@code reasoning.summary} 는 OpenAI 요청 본문의 경로 그 자체이고 값
 * ({@code auto} · {@code concise} · {@code detailed})도 OpenAI 의 어휘다. 그 배경에는 이 벤더의 세계관이 있다:
 * 이 엔드포인트에서 추론 자체는 {@code encrypted_content}(설계상 암호문)이므로 <b>요약이 유일하게 읽을 수 있는
 * 대리물</b>이다. Anthropic 에는 요약이라는 것이 없고, 모델 자신의 thinking 텍스트가 {@code display} 로 열린다.
 *
 * <p>
 * 적지 않으면 요청도 이벤트도 이 키가 없던 때와 같다. 세터를 가진 가변 POJO 인 것은 이 패키지의 관례이며,
 * 이유는 {@link AnthropicProviderConfig} 의 javadoc 에 있다.
 */
public class OpenAiProviderConfig {

    private OpenAiReasoningSummary reasoningSummary;

    /** OpenAiProviderConfig를 생성한다. */
    public OpenAiProviderConfig() {
    }

    /**
     * 모델의 추론 요약을 요청할 것인가 — 그리고 그 텍스트를 사용자에게 흘려보낼 것인가.
     *
     * <p>
     * 한 키가 두 일을 한다. 요청에 {@code reasoning.summary} 를 쓰고(그것이 없으면 서버는 요약을 만들지 않는다),
     * 동시에 스트림 전달 게이트를 연다. 게이트를 요청과 따로 두는 것은 {@code baseUrl} 뒤의 OpenAI 호환 게이트웨이가
     * 묻지 않은 요약 이벤트를 보낼 수 있기 때문이며, 아무것도 설정하지 않은 배포는 아무것도 달라지지 않아야 한다.
     *
     * <p>
     * <b>Chat Completions 로 가는 요청에는 대응물이 없다.</b> 모델이 추론 트레이스 왕복을 지원하지 않거나
     * Responses API 가 꺼져 있으면 이 키는 아무것도 하지 못하고, 클라이언트가 그 사실을 한 번 경고한다.
     *
     * @return {@code auto} · {@code concise} · {@code detailed} 중 하나, 또는 적지 않았으면 null
     */
    public OpenAiReasoningSummary getReasoningSummary() {
        return reasoningSummary;
    }

    public void setReasoningSummary(OpenAiReasoningSummary reasoningSummary) {
        this.reasoningSummary = reasoningSummary;
    }

    /**
     * 이 블록에 적힌 것이 하나도 없는가.
     *
     * <p>
     * {@link AnthropicProviderConfig#isEmpty()} 와 같은 계약이며 같은 이유로 있다 — {@code openai:} 라고만 적고
     * 자식을 두지 않은 블록이 "읽지 않는 분기의 거절" 을 발화시키면 안 된다.
     *
     * @return 키가 모두 비어 있으면 true
     */
    public boolean isEmpty() {
        return reasoningSummary == null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final OpenAiProviderConfig that = (OpenAiProviderConfig) o;
        return reasoningSummary == that.reasoningSummary;
    }

    @Override
    public int hashCode() {
        return Objects.hash(reasoningSummary);
    }

    @Override
    public String toString() {
        return "OpenAiProviderConfig{" + "reasoningSummary=" + reasoningSummary + '}';
    }
}
