package at.aimon.core.tools.web.security;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * OkHttp network interceptor that re-validates SSRF policy on every HTTP request,
 * including redirect hops.
 *
 * <p>
 * Must be registered via {@code addNetworkInterceptor()} (not {@code addInterceptor()})
 * so that it is invoked for each request/response pair during redirect chains.
 *
 * <p>
 * Mitigates DNS rebinding by also validating the actual connected socket address.
 *
 * <p>
 * Delegates all decisions to the supplied {@link SsrfGuard}, so a guard configured with
 * {@link SsrfGuardConfig#disabled()} also disables this DNS-rebinding re-validation (both the URL and connected-address
 * checks pass through). Allow-listed hosts likewise bypass the address check.
 */
public class SsrfRedirectInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SsrfRedirectInterceptor.class);

    private final SsrfGuard ssrfGuard;

    /**
     * Creates a new SsrfRedirectInterceptor.
     *
     * @param ssrfGuard
     *            the SSRF guard to delegate validation to (not null)
     */
    public SsrfRedirectInterceptor(SsrfGuard ssrfGuard) {
        this.ssrfGuard = Objects.requireNonNull(ssrfGuard, "ssrfGuard cannot be null");
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();

        // URL-level SSRF check
        if (!ssrfGuard.isSafe(request.url().toString())) {
            throw new IOException("Redirect blocked by SSRF policy: " + request.url().host());
        }

        Response response = chain.proceed(request);

        // DNS rebinding mitigation: validate actual connected socket address.
        // As a network interceptor, connection() should never be null.
        // If it is, fail closed to prevent DNS rebinding bypass.
        Connection connection = chain.connection();
        if (connection == null || connection.route() == null) {
            log.warn(
                    "Connection or route is null during SSRF check for: {}. "
                            + "Ensure this interceptor is registered via addNetworkInterceptor().",
                    request.url().host());
            response.close();
            throw new IOException("SSRF check failed: connection info unavailable for " + request.url().host());
        }

        // Allow-listed hosts (and a fully disabled guard) are exempt from the connected-address check, matching the
        // URL-level decision in SsrfGuard.isSafe(). isSafeAddress() only sees the IP and cannot consult the host
        // allow-list itself, so the host-aware exemption is checked here.
        InetSocketAddress socketAddress = connection.route().socketAddress();
        if (!ssrfGuard.isAddressCheckExempt(request.url().host())
                && !ssrfGuard.isSafeAddress(socketAddress.getAddress())) {
            response.close();
            throw new IOException("Connection blocked by SSRF policy: resolved to " + socketAddress.getAddress());
        }

        return response;
    }
}
