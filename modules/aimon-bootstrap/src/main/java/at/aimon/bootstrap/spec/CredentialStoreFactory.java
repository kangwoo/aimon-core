package at.aimon.bootstrap.spec;

import at.aimon.core.credential.CredentialStore;

/**
 * Builds the {@link CredentialStore} for one tenant.
 *
 * <p>
 * The parameter is the discriminator alone, not the whole runtime id, and that is the contract rather than an
 * omission: credentials belong to the <b>tenant</b> axis. The same customer's secrets are the same secrets
 * whether the ticketing agent or the reporting agent is asking, and keying them by agent as well would make
 * "agent A can reach the tenant's Jira and agent B cannot" a property of a factory implementation rather than of
 * the credentials. Agents that must not share a tenant's secrets are separated by what the store returns, not by
 * building them a different store.
 *
 * <h2>{@code null} means no tenant</h2>
 *
 * <p>
 * The runtimes created at startup — {@code agent:<ref>}, one per configured agent — have no discriminator, and
 * they execute turns like any other runtime. They are passed {@code null}, and an implementation should answer
 * with whatever the deployment's non-tenant credentials are. Returning a random tenant's store, or throwing,
 * breaks the startup runtimes.
 *
 * <h2>Ownership</h2>
 *
 * <p>
 * {@link CredentialStore} is not {@code AutoCloseable}, so the stack neither closes nor caches what this
 * returns: it is called once per runtime created, which is once per agent at startup and once per tenant on
 * first use. An implementation backed by a connection or a remote secrets client should cache its own instances
 * by discriminator rather than open one per call.
 *
 * <p>
 * Implementations must be thread-safe — tenant runtimes are built on request threads, several at a time.
 */
@FunctionalInterface
public interface CredentialStoreFactory {

    /**
     * Creates the credential store for the given tenant.
     *
     * @param discriminator
     *            the tenant, or {@code null} for a runtime created at startup, which has none
     * @return the store, or null when this tenant has no credentials
     */
    CredentialStore create(String discriminator);
}
