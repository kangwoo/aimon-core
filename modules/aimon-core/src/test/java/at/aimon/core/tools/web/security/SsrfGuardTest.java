package at.aimon.core.tools.web.security;

import static org.assertj.core.api.Assertions.*;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SsrfGuard Tests")
class SsrfGuardTest {

    private SsrfGuard ssrfGuard;

    @BeforeEach
    void setUp() {
        ssrfGuard = new SsrfGuard();
    }

    @Nested
    @DisplayName("isSafe - URL validation")
    class IsSafe {

        @Test
        @DisplayName("Should block null URL")
        void testNullUrl() {
            assertThat(ssrfGuard.isSafe(null)).isFalse();
        }

        @Test
        @DisplayName("Should block blank URL")
        void testBlankUrl() {
            assertThat(ssrfGuard.isSafe("")).isFalse();
            assertThat(ssrfGuard.isSafe("   ")).isFalse();
        }

        @Test
        @DisplayName("Should block non-http schemes")
        void testNonHttpScheme() {
            assertThat(ssrfGuard.isSafe("ftp://example.com")).isFalse();
            assertThat(ssrfGuard.isSafe("file:///etc/passwd")).isFalse();
            assertThat(ssrfGuard.isSafe("javascript:alert(1)")).isFalse();
        }

        @Test
        @DisplayName("Should block URLs with userinfo")
        void testUserInfo() {
            assertThat(ssrfGuard.isSafe("http://user:pass@internal.server/")).isFalse();
            assertThat(ssrfGuard.isSafe("https://admin@localhost/")).isFalse();
        }

        @Test
        @DisplayName("Should block loopback addresses")
        void testLoopback() {
            assertThat(ssrfGuard.isSafe("http://127.0.0.1/")).isFalse();
            assertThat(ssrfGuard.isSafe("http://127.0.0.1:8080/admin")).isFalse();
            assertThat(ssrfGuard.isSafe("http://[::1]/")).isFalse();
        }

        @Test
        @DisplayName("Should block private IP addresses")
        void testPrivateAddresses() {
            assertThat(ssrfGuard.isSafe("http://10.0.0.1/")).isFalse();
            assertThat(ssrfGuard.isSafe("http://172.16.0.1/")).isFalse();
            assertThat(ssrfGuard.isSafe("http://192.168.1.1/")).isFalse();
        }

        @Test
        @DisplayName("Should block link-local addresses")
        void testLinkLocal() {
            assertThat(ssrfGuard.isSafe("http://169.254.169.254/")).isFalse();
        }

        @Test
        @DisplayName("Should block localhost hostname")
        void testLocalhost() {
            assertThat(ssrfGuard.isSafe("http://localhost/")).isFalse();
            assertThat(ssrfGuard.isSafe("http://localhost:3000/")).isFalse();
        }

        @Test
        @DisplayName("Should block invalid URLs")
        void testInvalidUrl() {
            assertThat(ssrfGuard.isSafe("not-a-url")).isFalse();
        }

        @Test
        @DisplayName("Should block URLs with no host")
        void testNoHost() {
            assertThat(ssrfGuard.isSafe("http:///path")).isFalse();
        }
    }

    @Nested
    @DisplayName("isSafeAddress - IP validation")
    class IsSafeAddress {

        @Test
        @DisplayName("Should block null address")
        void testNullAddress() {
            assertThat(ssrfGuard.isSafeAddress(null)).isFalse();
        }

        @Test
        @DisplayName("Should block loopback address")
        void testLoopback() throws UnknownHostException {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            assertThat(ssrfGuard.isSafeAddress(loopback)).isFalse();
        }

        @Test
        @DisplayName("Should block IPv6 loopback")
        void testIpv6Loopback() throws UnknownHostException {
            InetAddress loopback = InetAddress.getByName("::1");
            assertThat(ssrfGuard.isSafeAddress(loopback)).isFalse();
        }

        @Test
        @DisplayName("Should block private addresses")
        void testPrivateAddresses() throws UnknownHostException {
            assertThat(ssrfGuard.isSafeAddress(InetAddress.getByName("10.0.0.1"))).isFalse();
            assertThat(ssrfGuard.isSafeAddress(InetAddress.getByName("172.16.0.1"))).isFalse();
            assertThat(ssrfGuard.isSafeAddress(InetAddress.getByName("192.168.1.1"))).isFalse();
        }

        @Test
        @DisplayName("Should block link-local addresses")
        void testLinkLocal() throws UnknownHostException {
            InetAddress linkLocal = InetAddress.getByName("169.254.169.254");
            assertThat(ssrfGuard.isSafeAddress(linkLocal)).isFalse();
        }

        @Test
        @DisplayName("Should block multicast addresses")
        void testMulticast() throws UnknownHostException {
            InetAddress multicast = InetAddress.getByName("224.0.0.1");
            assertThat(ssrfGuard.isSafeAddress(multicast)).isFalse();
        }

        @Test
        @DisplayName("Should allow public addresses")
        void testPublicAddress() throws UnknownHostException {
            // 8.8.8.8 is Google's public DNS
            InetAddress publicAddr = InetAddress.getByName("8.8.8.8");
            assertThat(ssrfGuard.isSafeAddress(publicAddr)).isTrue();
        }

        @Test
        @DisplayName("Should block IPv4-mapped IPv6 loopback")
        void testIpv4MappedIpv6Loopback() throws UnknownHostException {
            // ::ffff:127.0.0.1 is the IPv4-mapped IPv6 form of loopback
            InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
            assertThat(ssrfGuard.isSafeAddress(mapped)).isFalse();
        }

        @Test
        @DisplayName("Should block IPv4-mapped IPv6 private addresses")
        void testIpv4MappedIpv6Private() throws UnknownHostException {
            assertThat(ssrfGuard.isSafeAddress(InetAddress.getByName("::ffff:10.0.0.1"))).isFalse();
            assertThat(ssrfGuard.isSafeAddress(InetAddress.getByName("::ffff:192.168.1.1"))).isFalse();
            assertThat(ssrfGuard.isSafeAddress(InetAddress.getByName("::ffff:172.16.0.1"))).isFalse();
        }

        @Test
        @DisplayName("Should block IPv4-mapped IPv6 link-local")
        void testIpv4MappedIpv6LinkLocal() throws UnknownHostException {
            assertThat(ssrfGuard.isSafeAddress(InetAddress.getByName("::ffff:169.254.169.254"))).isFalse();
        }

        @Test
        @DisplayName("Should allow IPv4-mapped IPv6 public addresses")
        void testIpv4MappedIpv6Public() throws UnknownHostException {
            InetAddress mapped = InetAddress.getByName("::ffff:8.8.8.8");
            assertThat(ssrfGuard.isSafeAddress(mapped)).isTrue();
        }
    }
}
