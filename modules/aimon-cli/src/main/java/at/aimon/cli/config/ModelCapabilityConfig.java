package at.aimon.cli.config;

import java.util.List;
import java.util.Objects;

import at.aimon.core.llm.ReasoningEffort;

/**
 * yaml 로 적은 한 모델의 capability — {@code llm.modelCapabilities.<model>} 아래의 여섯 키.
 *
 * <p>
 * 게이트웨이나 Azure 배포가 모델을 다른 이름으로 노출하면 내장 capability 표는 그 이름을 모르고, 그 배포는 표가 고쳐 주는 것을
 * 계속 맞는다. 이 블록이 그 이름에 대해 "이 모델의 요청 표면이 무엇을 받는가" 를 적는 자리다.
 *
 * <p>
 * 필드가 박싱 타입인 것은 <b>적지 않은 것과 {@code false} 로 적은 것을 구별하기 위해서</b>다. 적지 않은 필드는
 * {@link at.aimon.core.llm.capability.ModelCapabilities#unknown()} 의 값을 갖는다 — 즉 한 키만 적으면 그 한 가지만 바뀐다.
 * 다 요구하지 않는 이유는 게이트웨이 운영자가 "temperature 가 400 을 낸다" 는 알아도 "이 모델이 reasoning trace 를
 * 되싣는가" 는 모르기 때문이다.
 *
 * <p>
 * 사다리를 적는 키는 <b>둘이고 서로 배타적</b>이다. {@code lowestReasoningEffort} 는 "여기서 시작해서 끝까지" 라는
 * 흔한 경우의 축약이고, {@code acceptedReasoningEfforts} 는 중간에 구멍이 있는 사다리를 위한 일반형이다
 * (실측된 예: {@code gpt-5.6-terra} 는 {@code none} 을 받고 {@code minimal} 을 거절한다). 둘을 같이 적으면
 * 기동이 실패한다 — 판정은 코어의 {@code ModelCapabilityDeclaration} 이 한다.
 *
 * <p>
 * {@code @JsonProperty} 를 붙이지 않는다 — 옆의 {@link LlmProviderConfig} 와 같이 빈 이름(camelCase)을 그대로 쓴다.
 * 필드 이름은 {@link at.aimon.core.llm.capability.ModelCapabilities} 의 자바 필드를 글자 그대로 옮긴 것이며,
 * {@code supports} 접두어를 떼지 않는다 — 같은 사실에 두 번째 어휘를 만들면 아무도 유지하지 않는 번역표가 생긴다.
 */
public class ModelCapabilityConfig {
    private Boolean supportsSamplingParameters;
    private Boolean supportsReasoningEffort;
    private Boolean supportsToolsWithReasoning;
    private Boolean supportsReasoningTraceRoundTrip;
    private ReasoningEffort lowestReasoningEffort;
    private List<ReasoningEffort> acceptedReasoningEfforts;

    /** ModelCapabilityConfig를 생성한다. */
    public ModelCapabilityConfig() {
    }

    public Boolean getSupportsSamplingParameters() {
        return supportsSamplingParameters;
    }

    public void setSupportsSamplingParameters(Boolean supportsSamplingParameters) {
        this.supportsSamplingParameters = supportsSamplingParameters;
    }

    public Boolean getSupportsReasoningEffort() {
        return supportsReasoningEffort;
    }

    public void setSupportsReasoningEffort(Boolean supportsReasoningEffort) {
        this.supportsReasoningEffort = supportsReasoningEffort;
    }

    public Boolean getSupportsToolsWithReasoning() {
        return supportsToolsWithReasoning;
    }

    public void setSupportsToolsWithReasoning(Boolean supportsToolsWithReasoning) {
        this.supportsToolsWithReasoning = supportsToolsWithReasoning;
    }

    public Boolean getSupportsReasoningTraceRoundTrip() {
        return supportsReasoningTraceRoundTrip;
    }

    public void setSupportsReasoningTraceRoundTrip(Boolean supportsReasoningTraceRoundTrip) {
        this.supportsReasoningTraceRoundTrip = supportsReasoningTraceRoundTrip;
    }

    public ReasoningEffort getLowestReasoningEffort() {
        return lowestReasoningEffort;
    }

    public void setLowestReasoningEffort(ReasoningEffort lowestReasoningEffort) {
        this.lowestReasoningEffort = lowestReasoningEffort;
    }

    /**
     * 이 모델이 받는 rung 전부 — 구멍이 있는 사다리를 적는 일반형.
     *
     * <p>
     * {@code List} 로 바인딩하고 코어에서 {@code Set} 으로 옮긴다. yaml 의 시퀀스가 List 이고, 중복된 rung 은
     * 뜻이 하나뿐이므로 조용히 접히는 것이 맞다.
     *
     * @return 적힌 rung 목록 (적지 않았으면 null)
     */
    public List<ReasoningEffort> getAcceptedReasoningEfforts() {
        return acceptedReasoningEfforts;
    }

    public void setAcceptedReasoningEfforts(List<ReasoningEffort> acceptedReasoningEfforts) {
        this.acceptedReasoningEfforts = acceptedReasoningEfforts;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ModelCapabilityConfig that = (ModelCapabilityConfig) o;
        return Objects.equals(supportsSamplingParameters, that.supportsSamplingParameters)
                && Objects.equals(supportsReasoningEffort, that.supportsReasoningEffort)
                && Objects.equals(supportsToolsWithReasoning, that.supportsToolsWithReasoning)
                && Objects.equals(supportsReasoningTraceRoundTrip, that.supportsReasoningTraceRoundTrip)
                && lowestReasoningEffort == that.lowestReasoningEffort
                && Objects.equals(acceptedReasoningEfforts, that.acceptedReasoningEfforts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(supportsSamplingParameters, supportsReasoningEffort, supportsToolsWithReasoning,
                supportsReasoningTraceRoundTrip, lowestReasoningEffort, acceptedReasoningEfforts);
    }

    @Override
    public String toString() {
        return "ModelCapabilityConfig{" + "supportsSamplingParameters=" + supportsSamplingParameters
                + ", supportsReasoningEffort=" + supportsReasoningEffort + ", supportsToolsWithReasoning="
                + supportsToolsWithReasoning + ", supportsReasoningTraceRoundTrip=" + supportsReasoningTraceRoundTrip
                + ", lowestReasoningEffort=" + lowestReasoningEffort + ", acceptedReasoningEfforts="
                + acceptedReasoningEfforts + '}';
    }
}
