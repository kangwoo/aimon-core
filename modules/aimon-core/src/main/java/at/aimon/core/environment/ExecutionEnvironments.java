package at.aimon.core.environment;

import java.util.Map;

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
     * Resolves the environment of an execution whose caller may attach per-run attributes (a main turn or a routine):
     * extracts the {@code environment.*} subset of {@code executionAttributes} into the request (see
     * {@link EnvironmentRequestAttributes}), then resolves as
     * {@link #resolveOrUnavailable(ExecutionEnvironmentProvider,
     * EnvironmentRequest)} does. An extraction failure is an unavailable environment too, and the provider is not
     * called: the caller asked for a placement core cannot express, and resolving without it would silently ignore it.
     *
     * @param provider
     *            the provider, or null if none is configured
     * @param request
     *            the request, filled except for its request attributes (must not be null); any request attributes
     *            already set on it are replaced by the extracted subset
     * @param executionAttributes
     *            the run's execution attributes, or null
     * @return the resolved environment, or an unavailable one carrying the cause (never null)
     */
    public static ExecutionEnvironment resolveOrUnavailable(ExecutionEnvironmentProvider provider,
            EnvironmentRequest.Builder request, Map<String, Object> executionAttributes) {
        final Map<String, String> requestAttributes;
        try {
            requestAttributes = EnvironmentRequestAttributes.extract(executionAttributes);
        } catch (IllegalArgumentException e) {
            // The message names the key and the value's class, never the value; the request is not built yet, so
            // there is nothing else to print.
            log.warn("Execution attributes refused for the environment request: {}", e.getMessage());
            return UnavailableExecutionEnvironment.of(e);
        }
        return resolveOrUnavailable(provider, request.requestAttributes(requestAttributes).build());
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
