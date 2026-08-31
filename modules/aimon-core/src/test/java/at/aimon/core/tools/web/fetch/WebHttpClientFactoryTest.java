package at.aimon.core.tools.web.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.tools.web.security.SsrfGuard;
import at.aimon.core.tools.web.security.SsrfRedirectInterceptor;
import okhttp3.OkHttpClient;

@DisplayName("WebHttpClientFactory Tests")
class WebHttpClientFactoryTest {

    @Test
    @DisplayName("registers SsrfRedirectInterceptor as a NETWORK interceptor (not application)")
    void registersSsrfNetworkInterceptor() {
        final OkHttpClient client = WebHttpClientFactory.create(new SsrfGuard());

        assertThat(client.networkInterceptors()).anyMatch(i -> i instanceof SsrfRedirectInterceptor);
        // Must be a network interceptor: only those see the actual connected socket address (DNS-rebinding defense).
        assertThat(client.interceptors()).noneMatch(i -> i instanceof SsrfRedirectInterceptor);
    }

    @Test
    @DisplayName("create(guard) applies the default timeout")
    void defaultTimeout() {
        final OkHttpClient client = WebHttpClientFactory.create(new SsrfGuard());
        final int expected = (int) WebHttpClientFactory.DEFAULT_TIMEOUT.toMillis();

        assertThat(client.callTimeoutMillis()).isEqualTo(expected);
        assertThat(client.connectTimeoutMillis()).isEqualTo(expected);
        assertThat(client.readTimeoutMillis()).isEqualTo(expected);
        assertThat(client.writeTimeoutMillis()).isEqualTo(expected);
    }

    @Test
    @DisplayName("create(guard, timeout) applies the given timeout and keeps the interceptor")
    void customTimeout() {
        final Duration timeout = Duration.ofSeconds(7);
        final OkHttpClient client = WebHttpClientFactory.create(new SsrfGuard(), timeout);

        assertThat(client.callTimeoutMillis()).isEqualTo(7_000);
        assertThat(client.connectTimeoutMillis()).isEqualTo(7_000);
        assertThat(client.readTimeoutMillis()).isEqualTo(7_000);
        assertThat(client.writeTimeoutMillis()).isEqualTo(7_000);
        assertThat(client.networkInterceptors()).anyMatch(i -> i instanceof SsrfRedirectInterceptor);
    }

    @Test
    @DisplayName("newBuilder allows further configuration while keeping the interceptor")
    void newBuilderIsCustomizable() {
        final OkHttpClient client = WebHttpClientFactory.newBuilder(new SsrfGuard(), Duration.ofSeconds(5))
                .followRedirects(false).build();

        assertThat(client.followRedirects()).isFalse();
        assertThat(client.networkInterceptors()).anyMatch(i -> i instanceof SsrfRedirectInterceptor);
    }

    @Test
    @DisplayName("rejects null guard / timeout")
    void rejectsNulls() {
        assertThatThrownBy(() -> WebHttpClientFactory.create(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WebHttpClientFactory.newBuilder(new SsrfGuard(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> WebHttpClientFactory.newBuilder(null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
    }
}
