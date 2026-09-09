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
     * 내장 prefix 가 답하던 다른 이름들은 그대로 남는다. openai provider 만 읽는다.
     *
     * @return 모델 이름에서 그 capability 로의 맵 (비어 있을 수 있으나 null 은 아니다)
     */
    public Map<String, ModelCapabilityConfig> getModelCapabilities() {
        return modelCapabilities;
    }

    public void setModelCapabilities(Map<String, ModelCapabilityConfig> modelCapabilities) {
        this.modelCapabilities = modelCapabilities == null ? new LinkedHashMap<>() : modelCapabilities;
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
                && Objects.equals(baseUrl, that.baseUrl) && Objects.equals(modelCapabilities, that.modelCapabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, apiKey, model, timeout, baseUrl, modelCapabilities);
    }

    @Override
    public String toString() {
        // apiKey 는 뺀다 (비밀). modelCapabilities 는 싣는다 — 비밀이 아니고, 이 블록이 읽혔는지가 진단의 핵심이다.
        return "LlmProviderConfig{" + "provider='" + provider + '\'' + ", model='" + model + '\'' + ", timeout="
                + timeout + ", baseUrl='" + baseUrl + '\'' + ", modelCapabilities=" + modelCapabilities + '}';
    }
}
