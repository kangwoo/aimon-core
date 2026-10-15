package at.aimon.core.agent.quota.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import at.aimon.core.agent.quota.QuotaResolutionContext;
import at.aimon.core.agent.quota.QuotaScope;
import at.aimon.core.agent.quota.QuotaScopeResolver;

/**
 * The in-tree {@link QuotaScopeResolver}: the session scope when the turn has a session, the principal scope when it
 * has a principal, and a tenant scope only when a tenant function was supplied and names one.
 *
 * <p>
 * The core has no tenant concept, so it does not guess one. In this repository a tenant is a bootstrap convention —
 * the {@link at.aimon.core.agent.AgentRuntimeId} discriminator of {@code agent:ops:acme} — and an application may as
 * well read it off a principal attribute; {@link #withTenant(Function)} takes whichever the application uses:
 *
 * <pre>
 * {
 *     &#64;code
 *     QuotaScopeResolver resolver = DefaultQuotaScopeResolver
 *             .withTenant(ctx -> ctx.getAgentRuntimeId().discriminator());
 * }
 * </pre>
 *
 * <p>
 * Immutable and thread-safe.
 */
public final class DefaultQuotaScopeResolver implements QuotaScopeResolver {

    private static final DefaultQuotaScopeResolver WITHOUT_TENANT = new DefaultQuotaScopeResolver(
            ctx -> Optional.empty());

    private final Function<QuotaResolutionContext, Optional<String>> tenantFunction;

    private DefaultQuotaScopeResolver(Function<QuotaResolutionContext, Optional<String>> tenantFunction) {
        this.tenantFunction = tenantFunction;
    }

    /**
     * Gets the resolver that picks the session and principal scopes and no tenant.
     *
     * @return the resolver (never null)
     */
    public static DefaultQuotaScopeResolver create() {
        return WITHOUT_TENANT;
    }

    /**
     * Creates a resolver that also picks a tenant scope, named by {@code tenantFunction}.
     *
     * @param tenantFunction
     *            reads the tenant id off the context; empty (or a blank id) means the turn has no tenant (must not be
     *            null, must not return null)
     * @return a new resolver (never null)
     */
    public static DefaultQuotaScopeResolver withTenant(
            Function<QuotaResolutionContext, Optional<String>> tenantFunction) {
        return new DefaultQuotaScopeResolver(Objects.requireNonNull(tenantFunction, "Tenant function cannot be null"));
    }

    @Override
    public List<QuotaScope> resolve(QuotaResolutionContext context) {
        Objects.requireNonNull(context, "Context cannot be null");
        final List<QuotaScope> scopes = new ArrayList<>(3);
        context.getSessionId().map(QuotaScope::session).ifPresent(scopes::add);
        context.getPrincipal().map(QuotaScope::principal).ifPresent(scopes::add);
        Objects.requireNonNull(tenantFunction.apply(context), "Tenant function returned null")
                .filter(id -> !id.isBlank()).map(QuotaScope::tenant).ifPresent(scopes::add);
        return List.copyOf(scopes);
    }

    @Override
    public String toString() {
        return this == WITHOUT_TENANT
                ? "DefaultQuotaScopeResolver{session, principal}"
                : "DefaultQuotaScopeResolver{session, principal, tenant}";
    }
}
