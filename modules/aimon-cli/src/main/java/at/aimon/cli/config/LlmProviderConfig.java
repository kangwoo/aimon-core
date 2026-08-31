package at.aimon.cli.config;

import java.util.Objects;

public class LlmProviderConfig {
    private String provider;
    private String apiKey;
    private String model;
    private Integer timeout;
    private String baseUrl;

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
                && Objects.equals(baseUrl, that.baseUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, apiKey, model, timeout, baseUrl);
    }

    @Override
    public String toString() {
        return "LlmProviderConfig{" + "provider='" + provider + '\'' + ", model='" + model + '\'' + ", timeout="
                + timeout + ", baseUrl='" + baseUrl + '\'' + '}';
    }
}
