package at.aimon.cli.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class LlmProviderConfig {
    private String provider;
    private String apiKey;
    private String model;
    private Integer timeout;
    private String baseUrl;
    private Map<String, ModelCapabilityConfig> modelCapabilities = new LinkedHashMap<>();
    private AnthropicProviderConfig anthropic = new AnthropicProviderConfig();

    /** LlmProviderConfig를 생성한다. */
    public LlmProviderConfig() {
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 모델 이름별 capability 선언. 키는 이 배포가 모델을 부르는 이름이며 {@code model} 이 부르는 그 이름이다.
     *
     * <p>
     * 선언은 내장 표를 <b>대체하지 않고 확장</b>한다 — exact 항목으로 등록되므로 같은 이름에 대해서는 사용자 선언이 이기고,
     * 내장 prefix 가 답하던 다른 이름들은 그대로 남는다. <b>두 provider 가 모두 읽는다</b> — 내장 표도 두 벤더를
     * 서술하고, 이름이 벤더 개념을 담고 있지 않으므로 이 키는 공통 네임스페이스에 남는다.
     *
     * @return 모델 이름에서 그 capability 로의 맵 (비어 있을 수 있으나 null 은 아니다)
     */
    public Map<String, ModelCapabilityConfig> getModelCapabilities() {
        return modelCapabilities;
    }

    public void setModelCapabilities(Map<String, ModelCapabilityConfig> modelCapabilities) {
        this.modelCapabilities = modelCapabilities == null ? new LinkedHashMap<>() : modelCapabilities;
    }

    /**
     * Anthropic 전용 설정 — {@code llm.anthropic} 블록. 옆의 {@code modelCapabilities} 와 달리 <b>이 블록은
     * anthropic 분기만 읽는다</b>. 세 키의 이름이 전부 Anthropic 개념이기 때문이며, 그래서 다른 provider 아래에
     * 적힌 이 블록은 조용히 무시되지 않고 기동을 실패시킨다.
     *
     * @return anthropic 블록 (비어 있을 수 있으나 null 은 아니다)
     */
    public AnthropicProviderConfig getAnthropic() {
        return anthropic;
    }

    public void setAnthropic(AnthropicProviderConfig anthropic) {
        this.anthropic = anthropic == null ? new AnthropicProviderConfig() : anthropic;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final LlmProviderConfig that = (LlmProviderConfig) o;
        return Objects.equals(provider, that.provider) && Objects.equals(apiKey, that.apiKey)
                && Objects.equals(model, that.model) && Objects.equals(timeout, that.timeout)
                && Objects.equals(baseUrl, that.baseUrl) && Objects.equals(modelCapabilities, that.modelCapabilities)
                && Objects.equals(anthropic, that.anthropic);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, apiKey, model, timeout, baseUrl, modelCapabilities, anthropic);
    }

    @Override
    public String toString() {
        // apiKey 는 뺀다 (비밀). 나머지 두 블록은 싣는다 — 비밀이 아니고, 그것이 읽혔는지가 진단의 핵심이다.
        return "LlmProviderConfig{" + "provider='" + provider + '\'' + ", model='" + model + '\'' + ", timeout="
                + timeout + ", baseUrl='" + baseUrl + '\'' + ", modelCapabilities=" + modelCapabilities + ", anthropic="
                + anthropic + '}';
    }
}
