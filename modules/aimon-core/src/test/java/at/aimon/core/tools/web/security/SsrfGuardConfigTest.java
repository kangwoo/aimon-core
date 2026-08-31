package at.aimon.core.tools.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SsrfGuardConfig + configurable SsrfGuard Tests")
class SsrfGuardConfigTest {

    @Nested
    @DisplayName("SsrfGuardConfig")
    class Config {

        @Test
        @DisplayName("secure() is enabled with no allowed hosts")
        void secureDefaults() {
            final SsrfGuardConfig config = SsrfGuardConfig.secure();
            assertThat(config.isEnabled()).isTrue();
            assertThat(config.getAllowedHosts()).isEmpty();
        }

        @Test
        @DisplayName("builder default matches secure()")
        void builderDefault() {
            final SsrfGuardConfig config = SsrfGuardConfig.builder().build();
            assertThat(config.isEnabled()).isTrue();
            assertThat(config.getAllowedHosts()).isEmpty();
        }

        @Test
        @DisplayName("disabled() turns protection off")
        void disabled() {
            assertThat(SsrfGuardConfig.disabled().isEnabled()).isFalse();
        }

        @Test
        @DisplayName("allowHost normalizes to lower-case and trims")
        void allowHostNormalizes() {
            final SsrfGuardConfig config = SsrfGuardConfig.builder().allowHost("  Internal.Host  ").build();
            assertThat(config.getAllowedHosts()).containsExactly("internal.host");
            assertThat(config.isHostAllowed("INTERNAL.HOST")).isTrue();
            assertThat(config.isHostAllowed("internal.host")).isTrue();
            assertThat(config.isHostAllowed("other.host")).isFalse();
        }

        @Test
        @DisplayName("trailing dot (FQDN root) is stripped symmetrically on store and lookup")
        void trailingDotNormalized() {
            final SsrfGuardConfig config = SsrfGuardConfig.builder().allowHost("Internal.Corp.").build();
            assertThat(config.getAllowedHosts()).containsExactly("internal.corp");
            assertThat(config.isHostAllowed("internal.corp")).isTrue();
            assertThat(config.isHostAllowed("internal.corp.")).isTrue();
            // inverse: entry without dot matches a lookup with the trailing dot.
            assertThat(SsrfGuardConfig.builder().allowHost("example.com").build().isHostAllowed("example.com."))
                    .isTrue();
        }

        @Test
        @DisplayName("isHostAllowed normalizes the lookup key (trim + lower-case) symmetrically with allowHost")
        void isHostAllowedNormalizesLookup() {
            final SsrfGuardConfig config = SsrfGuardConfig.builder().allowHost("internal.host").build();
            assertThat(config.isHostAllowed("  INTERNAL.host.  ")).isTrue();
        }

        @Test
        @DisplayName("allowedHosts(collection) adds all")
        void allowedHostsCollection() {
            final SsrfGuardConfig config = SsrfGuardConfig.builder().allowedHosts(List.of("a.test", "B.TEST")).build();
            assertThat(config.getAllowedHosts()).containsExactlyInAnyOrder("a.test", "b.test");
        }

        @Test
        @DisplayName("isHostAllowed is false for null host or empty allow-list")
        void isHostAllowedEdgeCases() {
            assertThat(SsrfGuardConfig.secure().isHostAllowed("anything")).isFalse();
            assertThat(SsrfGuardConfig.builder().allowHost("a.test").build().isHostAllowed(null)).isFalse();
        }

        @Test
        @DisplayName("null/blank host is rejected")
        void rejectsBadHost() {
            assertThatThrownBy(() -> SsrfGuardConfig.builder().allowHost(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> SsrfGuardConfig.builder().allowHost("   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("allowed hosts set is immutable")
        void immutableAllowedHosts() {
            final SsrfGuardConfig config = SsrfGuardConfig.builder().allowHost("a.test").build();
            assertThatThrownBy(() -> config.getAllowedHosts().add("b.test"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("SsrfGuard honours config")
    class GuardBehaviour {

        @Test
        @DisplayName("no-arg guard keeps secure behaviour (blocks loopback/private)")
        void defaultStillSecure() throws UnknownHostException {
            final SsrfGuard guard = new SsrfGuard();
            assertThat(guard.isSafe("http://127.0.0.1/")).isFalse();
            assertThat(guard.isSafeAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
            assertThat(guard.getConfig().isEnabled()).isTrue();
        }

        @Test
        @DisplayName("requires non-null config")
        void requiresConfig() {
            assertThatThrownBy(() -> new SsrfGuard(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("disabled config treats normally-blocked URLs and addresses as safe")
        void disabledBypasses() throws UnknownHostException {
            final SsrfGuard guard = new SsrfGuard(SsrfGuardConfig.disabled());
            assertThat(guard.isSafe("http://127.0.0.1/")).isTrue();
            assertThat(guard.isSafe("http://10.0.0.1/internal")).isTrue();
            assertThat(guard.isSafeAddress(InetAddress.getByName("127.0.0.1"))).isTrue();
            assertThat(guard.isSafeAddress(InetAddress.getByName("::1"))).isTrue();
            // null/blank input is still rejected even when disabled.
            assertThat(guard.isSafe(null)).isFalse();
            assertThat(guard.isSafe("  ")).isFalse();
        }

        @Test
        @DisplayName("allow-listed host bypasses the internal-network block (case/port/trailing-dot insensitive)")
        void allowListedHostBypassesAddressCheck() {
            // localhost resolves to a loopback address that the secure guard blocks; allow-listing it lets it through.
            final SsrfGuard guard = new SsrfGuard(SsrfGuardConfig.builder().allowHost("localhost").build());
            assertThat(guard.isSafe("http://localhost:8080/")).isTrue();
            // case-insensitive match.
            assertThat(guard.isSafe("http://LOCALHOST/path")).isTrue();
            // port-independent + trailing-dot FQDN both still match the allow-list.
            assertThat(guard.isSafe("http://localhost.:9999/")).isTrue();
            // a non-allow-listed internal host is still blocked.
            assertThat(guard.isSafe("http://127.0.0.1/")).isFalse();
        }

        @Test
        @DisplayName("allow-listed host short-circuits BEFORE DNS resolution")
        void allowListShortCircuitsBeforeDns() {
            // The .invalid TLD never resolves (RFC 2606); without the allow-list this would be UnknownHost -> false.
            // Returning true proves the allow-list check runs before (and instead of) DNS resolution + IP validation.
            final SsrfGuard guard = new SsrfGuard(SsrfGuardConfig.builder().allowHost("api.invalid").build());
            assertThat(guard.isSafe("http://api.invalid/data")).isTrue();
        }

        @Test
        @DisplayName("allow-list does NOT relax scheme/userinfo hygiene checks")
        void allowListStillEnforcesSchemeAndUserinfo() {
            final SsrfGuard guard = new SsrfGuard(SsrfGuardConfig.builder().allowHost("localhost").build());
            assertThat(guard.isSafe("ftp://localhost/")).isFalse();
            assertThat(guard.isSafe("http://user:pass@localhost/")).isFalse();
        }
    }
}
