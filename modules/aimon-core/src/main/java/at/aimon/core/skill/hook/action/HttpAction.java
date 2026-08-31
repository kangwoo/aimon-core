package at.aimon.core.skill.hook.action;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Declarative hook action that issues an HTTP request when its enclosing hook fires.
 *
 * <p>
 * The request URL, method, headers and body template are captured at parse time; placeholders inside the body / header
 * values are rendered by {@code HttpActionExecutor} via the shared {@code TemplateRenderer}. Environment variables
 * exposed to {@code ${env.X}} placeholders are restricted to {@link #getAllowedEnvVars()} &mdash; a fail-closed
 * whitelist.
 *
 * <p>
 * The response is mapped to a {@code HookResult} by the executor. The contract for the JSON response body is
 * {@code {"decision":"allow|deny|defer", "reason":"...", "feedback":"...", "updatedInput": {...}}}; see
 * {@code HttpActionExecutor} for details. Non-2xx responses degrade to a non-blocking failure (log + success) by
 * default to keep declarative hooks fail-soft for transport errors.
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class HttpAction implements HookAction {

    /** Default HTTP request timeout when the configuration omits it. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final URI url;
    private final HttpMethod method;
    private final Map<String, String> headers;
    private final String bodyTemplate;
    private final Duration timeout;
    private final Set<String> allowedEnvVars;

    private HttpAction(Builder builder) {
        this.url = Objects.requireNonNull(builder.url, "URL cannot be null");
        if (!"http".equalsIgnoreCase(url.getScheme()) && !"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("URL scheme must be http or https, but was: " + url.getScheme());
        }
        this.method = Objects.requireNonNull(builder.method, "Method cannot be null");
        this.headers = Map.copyOf(builder.headers);
        this.bodyTemplate = builder.bodyTemplate;
        this.timeout = builder.timeout != null ? builder.timeout : DEFAULT_TIMEOUT;
        if (this.timeout.isZero() || this.timeout.isNegative()) {
            throw new IllegalArgumentException("Timeout must be positive, but was: " + this.timeout);
        }
        this.allowedEnvVars = Set.copyOf(builder.allowedEnvVars);
    }

    /**
     * Creates a new builder.
     *
     * @return a fresh builder (never null)
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the absolute target URL.
     *
     * @return the URL (never null; scheme is always {@code http} or {@code https})
     */
    public URI getUrl() {
        return url;
    }

    /**
     * Returns the HTTP method.
     *
     * @return the method (never null)
     */
    public HttpMethod getMethod() {
        return method;
    }

    /**
     * Returns the request headers as captured at parse time.
     *
     * @return immutable map (never null)
     */
    public Map<String, String> getHeaders() {
        return headers;
    }

    /**
     * Returns the body template, or {@code null} when no body was configured.
     *
     * <p>
     * Placeholders supported by the shared {@code TemplateRenderer} (e.g. {@code ${tool_input.X}}) are rendered at
     * execution time.
     *
     * @return the body template (may be null)
     */
    public String getBodyTemplate() {
        return bodyTemplate;
    }

    /**
     * Returns the per-request timeout. Defaults to {@link #DEFAULT_TIMEOUT} when not set explicitly.
     *
     * @return the timeout (never null; always positive)
     */
    public Duration getTimeout() {
        return timeout;
    }

    @Override
    public Optional<Duration> getExecutionBudget() {
        return Optional.of(timeout);
    }

    /**
     * Returns the env-variable whitelist exposed to {@code ${env.X}} placeholders.
     *
     * @return immutable set (never null; possibly empty)
     */
    public Set<String> getAllowedEnvVars() {
        return allowedEnvVars;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HttpAction that)) {
            return false;
        }
        return url.equals(that.url) && method == that.method && headers.equals(that.headers)
                && Objects.equals(bodyTemplate, that.bodyTemplate) && timeout.equals(that.timeout)
                && allowedEnvVars.equals(that.allowedEnvVars);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url, method, headers, bodyTemplate, timeout, allowedEnvVars);
    }

    @Override
    public String toString() {
        return "HttpAction{method=" + method + ", url=" + url + ", timeout=" + timeout + ", headers=" + headers.keySet()
                + ", allowedEnvVars=" + allowedEnvVars + '}';
    }

    /** Builder for {@link HttpAction}. */
    public static final class Builder {
        private URI url;
        private HttpMethod method = HttpMethod.POST;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String bodyTemplate;
        private Duration timeout;
        private final Set<String> allowedEnvVars = new LinkedHashSet<>();

        private Builder() {
        }

        public Builder url(URI url) {
            this.url = url;
            return this;
        }

        public Builder url(String url) {
            this.url = url == null ? null : URI.create(url);
            return this;
        }

        public Builder method(HttpMethod method) {
            this.method = method;
            return this;
        }

        public Builder addHeader(String name, String value) {
            Objects.requireNonNull(name, "Header name cannot be null");
            Objects.requireNonNull(value, "Header value cannot be null");
            this.headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            Objects.requireNonNull(headers, "Headers cannot be null");
            this.headers.clear();
            this.headers.putAll(headers);
            return this;
        }

        public Builder bodyTemplate(String bodyTemplate) {
            this.bodyTemplate = bodyTemplate;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder allowedEnvVars(List<String> names) {
            Objects.requireNonNull(names, "names cannot be null");
            this.allowedEnvVars.clear();
            this.allowedEnvVars.addAll(names);
            return this;
        }

        public Builder allowedEnvVars(Set<String> names) {
            Objects.requireNonNull(names, "names cannot be null");
            this.allowedEnvVars.clear();
            this.allowedEnvVars.addAll(names);
            return this;
        }

        public HttpAction build() {
            return new HttpAction(this);
        }
    }

    /** Returns an immutable empty set. */
    static Set<String> emptyEnv() {
        return Collections.emptySet();
    }
}
