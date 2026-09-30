package at.aimon.core.environment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helpers for executors that resolve an {@link ExecutionEnvironment}.
 */
public final class ExecutionEnvironments {

    private static final Logger log = LoggerFactory.getLogger(ExecutionEnvironments.class);

    private ExecutionEnvironments() {
    }

    /**
     * Resolves the execution's environment, turning every failure into an {@link UnavailableExecutionEnvironment}
     * instead of failing the execution (design §5.1). A {@code null} provider or a {@code null} answer counts as a
     * failure. Never falls back to a host environment.
     *
     * @param provider
     *            the provider, or null if none is configured
     * @param request
     *            the request (must not be null)
     * @return the resolved environment, or an unavailable one carrying the cause (never null)
     */
    public static ExecutionEnvironment resolveOrUnavailable(ExecutionEnvironmentProvider provider,
            EnvironmentRequest request) {
        if (provider == null) {
            log.warn("No ExecutionEnvironmentProvider for {}; tools needing files or a shell will fail", request);
            return UnavailableExecutionEnvironment.of("no ExecutionEnvironmentProvider is configured");
        }
        try {
            final ExecutionEnvironment environment = provider.resolve(request);
            if (environment == null) {
                log.warn("ExecutionEnvironmentProvider {} returned null for {}", provider.getClass().getName(),
                        request);
                return UnavailableExecutionEnvironment
                        .of(provider.getClass().getName() + " returned no execution environment");
            }
            return environment;
        } catch (RuntimeException e) {
            log.warn("ExecutionEnvironmentProvider {} failed for {}: {}", provider.getClass().getName(), request,
                    e.getMessage(), e);
            return UnavailableExecutionEnvironment.of(e);
        }
    }
}
