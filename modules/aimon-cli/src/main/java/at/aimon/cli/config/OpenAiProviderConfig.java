package at.aimon.cli.config;

import java.util.Objects;

import at.aimon.core.llms.openai.OpenAiReasoningSummary;

/**
 * yaml 로 적은 OpenAI 전용 설정 — {@code llm.openai} 아래의 키들: {@code reasoningSummary},
 * {@code responsesApiEnabled}, 그리고 샘플링 기본값 넷({@code temperature} · {@code topP} ·
 * {@code presencePenalty} · {@code frequencyPenalty}).
 *
 * <p>
 * <b>이 라운드가 여는 네임스페이스다.</b> 지금까지 {@code llm.*} 아래 벤더 블록은 {@code anthropic} 하나뿐이었고,
 * {@code docs/backlog/llm-config-surface-open-items.md} 의 {@code L-2} 는 {@code responsesApiEnabled} 가
 * 언젠가 이리로 온다고 적어 두었다. 먼저 도착한 것이 {@code reasoningSummary} 이고, {@code responsesApiEnabled} 가
 * 그 뒤를 따라 들어왔다 — 같은 검사에 걸려서다(이름이 OpenAI 의 엔드포인트다).
 *
 * <p>
 * 판정 기준은 옆 블록과 같은 {@code docs/design/llm/configuration-surface.md} §3.1 이고, 첫 번째 검사가
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
    private Boolean responsesApiEnabled;
    private Double temperature;
    private Double topP;
    private Double presencePenalty;
    private Double frequencyPenalty;

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
     * 이 배포가 Responses API({@code /v1/responses}) 경로를 쓰는가 — {@code false} 는 모든 요청을
     * Chat Completions 로 돌린다.
     *
     * <p>
     * <b>이 키가 있는 이유는 설정만으로 만들어지는 404 다.</b> {@code llm.baseUrl} 을
     * {@code /v1/chat/completions} 만 구현한 OpenAI 호환 게이트웨이로 돌리고 실제 {@code gpt-5*} · o-series 이름을
     * 그대로 쓰면, 그 이름은 내장 capability 행에 걸려 {@code /v1/responses} 로 라우팅되고 게이트웨이는 404 를
     * 준다. 그 상황은 yaml 두 줄로 만들어지는데 빠져나오는 길은 자바 빌더뿐이었다
     * ({@code docs/backlog/llm-config-surface-open-items.md} 의 {@code L-2}). {@code false} 가 그 출구다 —
     * 모델에 대해 거짓말을 하지 않고 라우팅만 끄므로 내장 행의 나머지 사실(샘플링 억제, effort 사다리)은
     * 계속 적용된다.
     *
     * <p>
     * <b>박싱된 {@code Boolean} 인 것은 "적지 않음" 을 세 번째 상태로 두기 위해서다.</b> 기본값({@code true})은
     * {@code OpenAIConfig} 한 곳만 알고, 여기서는 적힌 값만 옮긴다. 그래서 {@code true} 를 적은 블록도
     * {@link #isEmpty()} 에게는 "적힌 블록" 이다.
     *
     * <p>
     * 끄면 함께 꺼지는 것이 있다. Chat Completions 에는 reasoning item 왕복이 없고(모델이 매 호출 추론을 다시
     * 세운다) {@code reasoning.summary} 도 없다 — 옆의 {@link #getReasoningSummary()} 는 아무 데도 닿지 않고
     * 클라이언트가 그 사실을 한 번 경고한다. 모델별로 측정된 결과는
     * {@code docs/design/llm/openai-responses-path.md} 의 측정 결론 표에 있다.
     *
     * @return 적힌 값, 또는 적지 않았으면 null
     */
    public Boolean getResponsesApiEnabled() {
        return responsesApiEnabled;
    }

    public void setResponsesApiEnabled(Boolean responsesApiEnabled) {
        this.responsesApiEnabled = responsesApiEnabled;
    }

    /**
     * 이 배포의 기본 {@code temperature} — 에이전트 정의가 자기 값을 적지 않은 요청에만 실린다.
     *
     * <p>
     * <b>우선순위는 셋이고 여기가 가운데다.</b> 에이전트 정의의 {@code model.temperature} 가 있으면 그것이 이기고,
     * 없으면 이 값이 실리며, 둘 다 없으면 <b>아무것도 실리지 않아</b> 서버 기본값이 적용된다 — 클라이언트는 값을
     * 지어내지 않는다({@code docs/design/llm/request-parameters.md} §2). 서브에이전트 요청도 같은 규칙이다: 띄운
     * 에이전트의 값을 물려받고, 그 정의에 값이 없으면 아무것도 싣지 않으므로 이 키가 닿는다(같은 문서 §3.5).
     *
     * <p>
     * <b>벤더 블록에 있는 이유는 뜻이 벤더마다 달라서다.</b> OpenAI 의 범위는 {@code 0.0}–{@code 2.0} 이고
     * Anthropic 의 범위는 {@code 0.0}–{@code 1.0} 이다. 범위는 {@code OpenAIConfig} 한 곳이 알고, 벗어나면
     * 클라이언트를 조립할 때 이 키의 이름으로 기동이 실패한다. 내장 capability 행이 샘플링을 받지 않는다고 적은
     * 모델({@code gpt-5*} · o-series)에서는 값이 실리지 않고 클라이언트가 그 사실을 한 번 경고한다.
     *
     * @return 적힌 값, 또는 적지 않았으면 null
     */
    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    /**
     * 이 배포의 기본 {@code top_p}. 범위 {@code 0.0}–{@code 1.0}. 우선순위와 억제 규칙은
     * {@link #getTemperature()} 와 같다.
     *
     * @return 적힌 값, 또는 적지 않았으면 null
     */
    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    /**
     * 이 배포의 기본 {@code presence_penalty}. 범위 {@code -2.0}–{@code 2.0}. 우선순위와 억제 규칙은
     * {@link #getTemperature()} 와 같다.
     *
     * <p>
     * <b>Responses API 로 가는 요청에는 자리가 없다.</b> 그 엔드포인트에는 penalty 파라미터가 없으므로 값은
     * 실리지 않고 클라이언트가 한 번 경고한다. Chat Completions 로 가는 요청에만 닿는다.
     *
     * @return 적힌 값, 또는 적지 않았으면 null
     */
    public Double getPresencePenalty() {
        return presencePenalty;
    }

    public void setPresencePenalty(Double presencePenalty) {
        this.presencePenalty = presencePenalty;
    }

    /**
     * 이 배포의 기본 {@code frequency_penalty}. 범위 {@code -2.0}–{@code 2.0}. {@link #getPresencePenalty()} 와
     * 같은 규칙이다 — Responses API 로 가는 요청에는 자리가 없다.
     *
     * @return 적힌 값, 또는 적지 않았으면 null
     */
    public Double getFrequencyPenalty() {
        return frequencyPenalty;
    }

    public void setFrequencyPenalty(Double frequencyPenalty) {
        this.frequencyPenalty = frequencyPenalty;
    }

    /**
     * 이 블록에 적힌 것이 하나도 없는가.
     *
     * <p>
     * {@link AnthropicProviderConfig#isEmpty()} 와 같은 계약이며 같은 이유로 있다 — {@code openai:} 라고만 적고
     * 자식을 두지 않은 블록이 "읽지 않는 분기의 거절" 을 발화시키면 안 된다.
     *
     * <p>
     * 묻는 것은 "적혔는가" 이지 "무엇을 바꾸는가" 가 아니다. {@code responsesApiEnabled: true} 는 기본값을 다시
     * 적은 것이지만 anthropic 분기 아래에서는 여전히 아무도 읽지 않는 줄이므로 비어 있지 않다.
     *
     * @return 키가 모두 비어 있으면 true
     */
    public boolean isEmpty() {
        return reasoningSummary == null && responsesApiEnabled == null && temperature == null && topP == null
                && presencePenalty == null && frequencyPenalty == null;
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
        return reasoningSummary == that.reasoningSummary
                && Objects.equals(responsesApiEnabled, that.responsesApiEnabled)
                && Objects.equals(temperature, that.temperature) && Objects.equals(topP, that.topP)
                && Objects.equals(presencePenalty, that.presencePenalty)
                && Objects.equals(frequencyPenalty, that.frequencyPenalty);
    }

    @Override
    public int hashCode() {
        return Objects.hash(reasoningSummary, responsesApiEnabled, temperature, topP, presencePenalty,
                frequencyPenalty);
    }

    @Override
    public String toString() {
        return "OpenAiProviderConfig{" + "reasoningSummary=" + reasoningSummary + ", responsesApiEnabled="
                + responsesApiEnabled + ", temperature=" + temperature + ", topP=" + topP + ", presencePenalty="
                + presencePenalty + ", frequencyPenalty=" + frequencyPenalty + '}';
    }
}
