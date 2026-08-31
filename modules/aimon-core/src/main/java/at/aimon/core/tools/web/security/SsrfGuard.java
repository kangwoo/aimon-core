package at.aimon.core.tools.web.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSRF (Server-Side Request Forgery) defense utility.
 *
 * <p>
 * Blocks requests to internal networks by validating URLs and resolved IP addresses.
 *
 * <h3>Configuration</h3>
 * <p>
 * Behaviour is controlled by an immutable {@link SsrfGuardConfig}. The no-arg constructor uses
 * {@link SsrfGuardConfig#secure()} (protection on, no exemptions) so existing callers are unchanged. Pass a custom
 * config to disable protection entirely ({@link SsrfGuardConfig#disabled()}) or to exempt specific trusted hosts from
 * the internal-network block (allow-list). Both only weaken protection and are intended for trusted environments.
 *
 * <h3>DNS Rebinding Mitigation</h3>
 * <p>
 * {@code InetAddress.getByName()} based DNS resolution has a Time-of-Check-Time-of-Use
 * (TOCTOU) issue. The DNS response can change between check time and actual request time.
 * To mitigate this, use {@link SsrfRedirectInterceptor} as an OkHttp network interceptor
 * to re-validate the actual connected socket address.
 *
 * <h3>Blocked Targets</h3>
 * <ul>
 * <li>Loopback: 127.0.0.0/8, ::1
 * <li>Private (RFC1918): 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
 * <li>Link-local: 169.254.0.0/16, fe80::/10
 * <li>Multicast: 224.0.0.0/4, ff00::/8
 * <li>URLs with userinfo (@)
 * <li>Non http/https schemes
 * </ul>
 */
public class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    private final SsrfGuardConfig config;

    /**
     * Creates a guard with the secure default configuration ({@link SsrfGuardConfig#secure()}): protection enabled, no
     * host exemptions.
     */
    public SsrfGuard() {
        this(SsrfGuardConfig.secure());
    }

    /**
     * Creates a guard with the given configuration.
     *
     * @param config
     *            the SSRF configuration (must not be null)
     */
    public SsrfGuard(SsrfGuardConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        if (!config.isEnabled()) {
            log.warn("SSRF protection is DISABLED — all URLs are treated as safe. "
                    + "Use only in trusted environments.");
        } else if (!config.getAllowedHosts().isEmpty()) {
            log.warn("SSRF allow-list active; these hosts bypass the internal-network block: {}",
                    config.getAllowedHosts());
        }
    }

    /**
     * @return the configuration backing this guard (never null).
     */
    public SsrfGuardConfig getConfig() {
        return config;
    }

    /**
     * Whether the connected-address SSRF check should be skipped for the given request host.
     *
     * <p>
     * Returns {@code true} when protection is disabled or the host is on the allow-list. The connected-address check
     * ({@link #isSafeAddress(InetAddress)}) only sees an IP and therefore cannot consult the host allow-list on its
     * own; {@link SsrfRedirectInterceptor} calls this with the request host so an allow-listed host is exempted at the
     * connection level too — consistently with {@link #isSafe(String)} at the URL level. Without this, allow-listing a
     * trusted internal host would pass the URL check but still be blocked when it resolves to an internal IP.
     *
     * @param host
     *            the request URL host (nullable)
     * @return {@code true} if the address-level check should be bypassed for this host
     */
    public boolean isAddressCheckExempt(String host) {
        return !config.isEnabled() || config.isHostAllowed(host);
    }

    /**
     * Checks whether a URL is safe to request.
     *
     * <p>
     * Validates the scheme, checks for userinfo, resolves the hostname via DNS,
     * and verifies that the resolved IP is not in a blocked range.
     *
     * @param urlString
     *            the URL to check
     * @return true if safe to request, false if blocked
     */
    public boolean isSafe(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            return false;
        }

        // Protection disabled: treat every URL as safe (trusted-environment opt-out).
        if (!config.isEnabled()) {
            return true;
        }

        try {
            URI uri = URI.create(urlString);

            // Scheme check
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                log.debug("Blocked non-http scheme: {}", scheme);
                return false;
            }

            // Userinfo check (potential URL obfuscation)
            if (uri.getUserInfo() != null) {
                log.debug("Blocked URL with userinfo");
                return false;
            }

            // Host extraction
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }

            // Allow-listed hosts skip the internal-network block (scheme/userinfo checks above still applied).
            if (config.isHostAllowed(host)) {
                log.debug("Allow-listed host bypasses SSRF address check: {}", host);
                return true;
            }

            // DNS resolve and IP check
            InetAddress address = InetAddress.getByName(host);
            return isSafeAddress(address);

        } catch (UnknownHostException e) {
            log.debug("DNS resolution failed for URL: {}", urlString);
            return false;
        } catch (IllegalArgumentException e) {
            log.debug("Invalid URL: {}", urlString);
            return false;
        }
    }

    /**
     * Checks whether an IP address is safe.
     *
     * <p>
     * Used by the network interceptor to validate the actual connected IP address,
     * mitigating DNS rebinding attacks.
     *
     * @param address
     *            the IP address to check
     * @return true if safe, false if the address is loopback, private, link-local, or multicast
     */
    public boolean isSafeAddress(InetAddress address) {
        if (address == null) {
            return false;
        }

        // Protection disabled: accept any resolved address (trusted-environment opt-out).
        if (!config.isEnabled()) {
            return true;
        }

        // IPv4-mapped IPv6 addresses (e.g. ::ffff:127.0.0.1) may bypass standard
        // InetAddress checks. Extract the embedded IPv4 address and re-validate.
        if (address instanceof Inet6Address ipv6) {
            byte[] bytes = ipv6.getAddress();
            if (isIpv4MappedIpv6(bytes)) {
                byte[] ipv4Bytes = new byte[4];
                System.arraycopy(bytes, 12, ipv4Bytes, 0, 4);
                try {
                    InetAddress ipv4 = InetAddress.getByAddress(ipv4Bytes);
                    if (!isSafeAddress(ipv4)) {
                        log.debug("Blocked IPv4-mapped IPv6 address: {}", address);
                        return false;
                    }
                } catch (UnknownHostException e) {
                    log.debug("Failed to parse IPv4-mapped address: {}", address);
                    return false;
                }
            }
        }

        if (address.isLoopbackAddress()) {
            log.debug("Blocked loopback address: {}", address);
            return false;
        }

        if (address.isSiteLocalAddress()) {
            log.debug("Blocked private address: {}", address);
            return false;
        }

        if (address.isLinkLocalAddress()) {
            log.debug("Blocked link-local address: {}", address);
            return false;
        }

        if (address.isMulticastAddress()) {
            log.debug("Blocked multicast address: {}", address);
            return false;
        }

        if (address.isAnyLocalAddress()) {
            log.debug("Blocked any-local address: {}", address);
            return false;
        }

        return true;
    }

    /**
     * Checks if the given 16-byte array represents an IPv4-mapped IPv6 address.
     * Format: 10 bytes of 0, 2 bytes of 0xFF, 4 bytes of IPv4 address.
     */
    private boolean isIpv4MappedIpv6(byte[] bytes) {
        if (bytes.length != 16) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
    }
}
