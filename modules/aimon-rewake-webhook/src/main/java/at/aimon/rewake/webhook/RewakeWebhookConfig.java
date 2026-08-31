package at.aimon.rewake.webhook;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for {@link RewakeWebhookServer}.
 *
 * <p>
 * Defaults mirror the agreed conventions: HMAC secret stored under
 * {@code credential profile="rewake-webhook" field="hmac-secret"}; signature header
 * {@code X-Rewake-Signature}; idempotency header {@code X-Rewake-Idempotency-Key}; route
 * {@code POST /rewake/events}.
 */
public final class RewakeWebhookConfig {

    public static final int DEFAULT_PORT = 0;
    public static final String DEFAULT_PATH = "/rewake/events";
    public static final String DEFAULT_CREDENTIAL_PROFILE = "rewake-webhook";
    public static final String DEFAULT_CREDENTIAL_FIELD = "hmac-secret";
    public static final String DEFAULT_SIGNATURE_HEADER = "X-Rewake-Signature";
    public static final String DEFAULT_IDEMPOTENCY_HEADER = "X-Rewake-Idempotency-Key";
    public static final Duration DEFAULT_IDEMPOTENCY_WINDOW = WebhookIdempotencyCache.DEFAULT_RETENTION;

    private final int port;
    private final String path;
    private final String credentialProfile;
    private final String credentialField;
    private final String signatureHeader;
    private final String idempotencyHeader;
    private final Duration idempotencyWindow;

    private RewakeWebhookConfig(Builder b) {
        this.port = b.port;
        this.path = Objects.requireNonNull(b.path, "path");
        this.credentialProfile = Objects.requireNonNull(b.credentialProfile, "credentialProfile");
        this.credentialField = Objects.requireNonNull(b.credentialField, "credentialField");
        this.signatureHeader = Objects.requireNonNull(b.signatureHeader, "signatureHeader");
        this.idempotencyHeader = Objects.requireNonNull(b.idempotencyHeader, "idempotencyHeader");
        this.idempotencyWindow = Objects.requireNonNull(b.idempotencyWindow, "idempotencyWindow");
        if (port < 0) {
            throw new IllegalArgumentException("port must be >= 0, got: " + port);
        }
        if (path.isBlank() || !path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with '/', got: " + path);
        }
        if (credentialProfile.isBlank()) {
            throw new IllegalArgumentException("credentialProfile cannot be blank");
        }
        if (credentialField.isBlank()) {
            throw new IllegalArgumentException("credentialField cannot be blank");
        }
        if (signatureHeader.isBlank()) {
            throw new IllegalArgumentException("signatureHeader cannot be blank");
        }
        if (idempotencyHeader.isBlank()) {
            throw new IllegalArgumentException("idempotencyHeader cannot be blank");
        }
        if (idempotencyWindow.isNegative() || idempotencyWindow.isZero()) {
            throw new IllegalArgumentException("idempotencyWindow must be positive, got: " + idempotencyWindow);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return TCP port to bind ({@code 0} = OS-assigned, useful for tests) */
    public int getPort() {
        return port;
    }

    /** @return URL path the webhook is mounted at */
    public String getPath() {
        return path;
    }

    /** @return credential-store profile that holds the HMAC secret */
    public String getCredentialProfile() {
        return credentialProfile;
    }

    /** @return credential-store field within the profile */
    public String getCredentialField() {
        return credentialField;
    }

    /** @return HTTP header carrying the request signature */
    public String getSignatureHeader() {
        return signatureHeader;
    }

    /** @return HTTP header carrying the optional idempotency key */
    public String getIdempotencyHeader() {
        return idempotencyHeader;
    }

    /** @return retention window for the idempotency cache */
    public Duration getIdempotencyWindow() {
        return idempotencyWindow;
    }

    public static final class Builder {
        private int port = DEFAULT_PORT;
        private String path = DEFAULT_PATH;
        private String credentialProfile = DEFAULT_CREDENTIAL_PROFILE;
        private String credentialField = DEFAULT_CREDENTIAL_FIELD;
        private String signatureHeader = DEFAULT_SIGNATURE_HEADER;
        private String idempotencyHeader = DEFAULT_IDEMPOTENCY_HEADER;
        private Duration idempotencyWindow = DEFAULT_IDEMPOTENCY_WINDOW;

        private Builder() {
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder credentialProfile(String credentialProfile) {
            this.credentialProfile = credentialProfile;
            return this;
        }

        public Builder credentialField(String credentialField) {
            this.credentialField = credentialField;
            return this;
        }

        public Builder signatureHeader(String signatureHeader) {
            this.signatureHeader = signatureHeader;
            return this;
        }

        public Builder idempotencyHeader(String idempotencyHeader) {
            this.idempotencyHeader = idempotencyHeader;
            return this;
        }

        public Builder idempotencyWindow(Duration idempotencyWindow) {
            this.idempotencyWindow = idempotencyWindow;
            return this;
        }

        public RewakeWebhookConfig build() {
            return new RewakeWebhookConfig(this);
        }
    }
}
