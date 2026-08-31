package at.aimon.core.tools.web.fetch;

import java.time.Duration;
import java.util.Objects;

import at.aimon.core.tools.web.security.SsrfGuard;
import at.aimon.core.tools.web.security.SsrfGuardConfig;
import at.aimon.core.tools.web.security.SsrfRedirectInterceptor;
import okhttp3.OkHttpClient;

/**
 * Builds the {@link OkHttpClient} used by {@link HttpContentFetcher}, hardened against SSRF.
 *
 * <p>
 * This is the canonical, secure way to construct the web-fetch client: it registers {@link SsrfRedirectInterceptor} as
 * a <b>network</b> interceptor (via {@code addNetworkInterceptor}, not {@code addInterceptor}) so the SSRF policy is
 * re-validated against the actual connected socket address of every request and every redirect hop — the
 * DNS-rebinding mitigation. Building the client here, rather than with a bare {@code new OkHttpClient()}, ensures the
 * rebinding protection is never accidentally omitted.
 *
 * <p>
 * The interceptor delegates every decision to the supplied {@link SsrfGuard}, so a guard built with
 * {@link SsrfGuardConfig#disabled()} or a host allow-list relaxes the checks accordingly.
 *
 * <p>
 * Integration note: this repository does not yet assemble the web-fetch tool stack in production (no component
 * constructs {@link HttpContentFetcher} or this client outside tests). This factory is the intended entry point for
 * whoever wires that stack up — use it instead of a bare {@code new OkHttpClient()} so the SSRF protection is present
 * from the start.
 */
public final class WebHttpClientFactory {

    /** Default connect/read/write/call timeout applied when a timeout is not supplied. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private WebHttpClientFactory() {
    }

    /**
     * Builds a hardened client with {@link #DEFAULT_TIMEOUT}.
     *
     * @param ssrfGuard
     *            the guard the SSRF network interceptor delegates to (must not be null)
     * @return an {@link OkHttpClient} with the SSRF rebinding interceptor registered
     */
    public static OkHttpClient create(SsrfGuard ssrfGuard) {
        return create(ssrfGuard, DEFAULT_TIMEOUT);
    }

    /**
     * Builds a hardened client with the given timeout.
     *
     * @param ssrfGuard
     *            the guard the SSRF network interceptor delegates to (must not be null)
     * @param timeout
     *            the connect/read/write/call timeout (must not be null)
     * @return an {@link OkHttpClient} with the SSRF rebinding interceptor registered
     */
    public static OkHttpClient create(SsrfGuard ssrfGuard, Duration timeout) {
        return newBuilder(ssrfGuard, timeout).build();
    }

    /**
     * Returns a builder pre-loaded with the SSRF network interceptor and timeouts so callers can layer additional
     * configuration (proxy, cache, extra interceptors, ...) before {@code build()}. Do not remove the SSRF interceptor.
     *
     * @param ssrfGuard
     *            the guard the SSRF network interceptor delegates to (must not be null)
     * @param timeout
     *            the connect/read/write/call timeout (must not be null)
     * @return a configured {@link OkHttpClient.Builder}
     */
    public static OkHttpClient.Builder newBuilder(SsrfGuard ssrfGuard, Duration timeout) {
        Objects.requireNonNull(ssrfGuard, "ssrfGuard cannot be null");
        Objects.requireNonNull(timeout, "timeout cannot be null");
        return new OkHttpClient.Builder().addNetworkInterceptor(new SsrfRedirectInterceptor(ssrfGuard))
                .connectTimeout(timeout).readTimeout(timeout).writeTimeout(timeout).callTimeout(timeout);
    }
}
