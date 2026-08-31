package at.aimon.core.tools.web.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable configuration for {@link SsrfGuard}.
 *
 * <p>
 * Controls whether SSRF protection is enforced and which hosts are exempted from the internal-network block.
 *
 * <p>
 * <b>Secure by default.</b> {@link #secure()} (and the no-arg {@link SsrfGuard} constructor) enables protection with an
 * empty allow-list, so existing behaviour is unchanged. The {@link #isEnabled() enabled} flag and
 * {@link #getAllowedHosts()
 * allow-list} only ever <em>weaken</em> protection and are intended for trusted environments or internal-host testing —
 * use them deliberately.
 */
public final class SsrfGuardConfig {

    private static final SsrfGuardConfig SECURE = builder().build();

    private final boolean enabled;
    private final Set<String> allowedHosts;

    private SsrfGuardConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.allowedHosts = Set.copyOf(builder.allowedHosts);
    }

    /**
     * @return the secure default: protection enabled, no host exemptions. Equivalent to {@code new SsrfGuard()}.
     */
    public static SsrfGuardConfig secure() {
        return SECURE;
    }

    /**
     * @return a configuration with SSRF protection turned OFF. Every URL is treated as safe — use only in fully
     *         trusted environments.
     */
    public static SsrfGuardConfig disabled() {
        return builder().enabled(false).build();
    }

    /**
     * @return a new builder; defaults to {@link #secure()} (enabled, empty allow-list).
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return {@code true} if SSRF checks are enforced. Default {@code true}.
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * @return the immutable, lower-cased set of hosts exempted from the internal-network block (never null).
     */
    public Set<String> getAllowedHosts() {
        return allowedHosts;
    }

    /**
     * @param host
     *            the URL host to test (nullable)
     * @return {@code true} if {@code host} is on the allow-list (case-insensitive exact match). Always {@code false}
     *         for a null host or an empty allow-list.
     */
    public boolean isHostAllowed(String host) {
        if (host == null || allowedHosts.isEmpty()) {
            return false;
        }
        return allowedHosts.contains(normalizeHost(host));
    }

    /**
     * Canonicalizes a host for allow-list storage and lookup: trims surrounding whitespace, lower-cases (DNS is
     * case-insensitive), and strips a single RFC 1035 trailing dot so {@code "host."} and {@code "host"} compare equal.
     * Used by both {@link Builder#allowHost(String)} and {@link #isHostAllowed(String)} so the two sides can never
     * diverge (a mismatch there would either silently fail to match or, worse, let a {@code host.} FQDN bypass an
     * allow-list entry stored as {@code host}).
     */
    private static String normalizeHost(String host) {
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "SsrfGuardConfig{enabled=" + enabled + ", allowedHosts=" + allowedHosts + '}';
    }

    public static final class Builder {
        private boolean enabled = true;
        private final Set<String> allowedHosts = new HashSet<>();

        private Builder() {
        }

        /**
         * @param enabled
         *            whether SSRF protection is enforced; {@code false} treats every URL as safe
         * @return this builder
         */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Exempts a single host from the internal-network block (case-insensitive exact match). The scheme and
         * userinfo checks still apply to allow-listed hosts.
         *
         * @param host
         *            the host to allow (must not be null/blank)
         * @return this builder
         */
        public Builder allowHost(String host) {
            Objects.requireNonNull(host, "host cannot be null");
            final String normalized = normalizeHost(host);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("host cannot be blank");
            }
            allowedHosts.add(normalized);
            return this;
        }

        /**
         * Adds every host in the collection to the allow-list (see {@link #allowHost(String)}).
         *
         * @param hosts
         *            the hosts to allow (must not be null; entries must not be null/blank)
         * @return this builder
         */
        public Builder allowedHosts(Collection<String> hosts) {
            Objects.requireNonNull(hosts, "hosts cannot be null");
            hosts.forEach(this::allowHost);
            return this;
        }

        /**
         * @return the immutable configuration
         */
        public SsrfGuardConfig build() {
            return new SsrfGuardConfig(this);
        }
    }
}
