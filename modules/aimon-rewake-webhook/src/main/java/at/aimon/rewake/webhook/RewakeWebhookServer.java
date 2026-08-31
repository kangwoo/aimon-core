package at.aimon.rewake.webhook;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.credential.CredentialStore;
import at.aimon.core.hook.rewake.ExternalEvent;
import at.aimon.core.hook.rewake.ExternalEventResolver;
import io.javalin.Javalin;
import io.javalin.http.Context;

/**
 * Embedded Javalin webhook endpoint that authenticates inbound requests via HMAC, dedups by idempotency key, and
 * dispatches matched events into the rewake pipeline through {@link ExternalEventResolver}.
 *
 * <h2>Wire format</h2>
 *
 * <pre>
 * POST /rewake/events
 * X-Rewake-Signature: sha256=&lt;hex hmac-sha256 of body, hmac-secret&gt;
 * X-Rewake-Idempotency-Key: &lt;optional opaque key&gt;
 * Content-Type: application/json
 *
 * {
 *   "eventType":"webhook",
 *   "eventKey":"ticket-42",
 *   "payload":{"status":"approved"}    // optional
 * }
 * </pre>
 *
 * <h2>Status codes</h2>
 * <ul>
 * <li>{@code 200} — request accepted; body is {@code {"matched":N,"idempotentReplay":bool}}.
 * <li>{@code 400} — malformed JSON.
 * <li>{@code 401} — missing or invalid signature.
 * <li>{@code 422} — JSON parsed but {@code eventType} or {@code eventKey} is missing/blank.
 * <li>{@code 503} — HMAC secret could not be resolved from {@link CredentialStore} (operator misconfiguration).
 * </ul>
 *
 * <p>
 * Idempotency: when the {@code X-Rewake-Idempotency-Key} header is present, the matched-count from the first
 * processing of that key is cached for {@link RewakeWebhookConfig#getIdempotencyWindow() idempotencyWindow}.
 * Redeliveries of the same key are not re-dispatched; the response carries {@code idempotentReplay=true} along
 * with the cached matched-count. Without the header every request is processed.
 *
 * <p>
 * Lifecycle: construct, then call {@link #start()} to bind the port and {@link #close()} to stop.
 * {@link #getBoundPort()} returns the actual port (useful for {@code port=0} test setups).
 *
 * <p>
 * Thread-safe — Javalin handles concurrent requests; this class only reads from injected immutables.
 */
public final class RewakeWebhookServer implements AutoCloseable {

    public static final String SOURCE_TRANSPORT = "webhook";

    private static final Logger log = LoggerFactory.getLogger(RewakeWebhookServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RewakeWebhookConfig config;
    private final CredentialStore credentialStore;
    private final ExternalEventResolver resolver;
    private final HmacSignatureVerifier verifier;
    private final WebhookIdempotencyCache idempotencyCache;
    private final Javalin app;

    public RewakeWebhookServer(RewakeWebhookConfig config, CredentialStore credentialStore,
            ExternalEventResolver resolver) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.credentialStore = Objects.requireNonNull(credentialStore, "credentialStore cannot be null");
        this.resolver = Objects.requireNonNull(resolver, "resolver cannot be null");
        this.verifier = new HmacSignatureVerifier();
        this.idempotencyCache = new WebhookIdempotencyCache(config.getIdempotencyWindow());
        this.app = Javalin.create(c -> c.showJavalinBanner = false).post(config.getPath(), this::handle);
    }

    /**
     * Binds the configured port and starts accepting requests.
     *
     * @return this (for chaining)
     */
    public RewakeWebhookServer start() {
        app.start(config.getPort());
        log.info("rewake.webhook.started port={} path={}", getBoundPort(), config.getPath());
        return this;
    }

    /**
     * @return the actual bound port, or {@code -1} if the server is not started
     */
    public int getBoundPort() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
        log.info("rewake.webhook.stopped");
    }

    private void handle(Context ctx) {
        final byte[] body = ctx.bodyAsBytes();
        final String signatureHeader = ctx.header(config.getSignatureHeader());
        if (signatureHeader == null || signatureHeader.isBlank()) {
            ctx.status(401).result("missing signature");
            return;
        }
        final Optional<String> secret = credentialStore.get(config.getCredentialProfile(), config.getCredentialField());
        if (secret.isEmpty()) {
            log.warn("rewake.webhook.secret_unavailable profile={} field={}", config.getCredentialProfile(),
                    config.getCredentialField());
            ctx.status(503).result("hmac secret unavailable");
            return;
        }
        if (!verifier.verify(signatureHeader, body, secret.get())) {
            log.warn("rewake.webhook.signature_invalid path={}", config.getPath());
            ctx.status(401).result("invalid signature");
            return;
        }

        final String idempotencyKey = ctx.header(config.getIdempotencyHeader());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            final Optional<Integer> prior = idempotencyCache.lookup(idempotencyKey);
            if (prior.isPresent()) {
                log.debug("rewake.webhook.idempotent_replay key={} matched={}", idempotencyKey, prior.get());
                respondMatched(ctx, prior.get(), true);
                return;
            }
        }

        final JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            ctx.status(400).result("malformed json: " + e.getMessage());
            return;
        }
        if (root == null || !root.isObject()) {
            ctx.status(400).result("body must be a JSON object");
            return;
        }
        final String eventType = textOrNull(root.get("eventType"));
        final String eventKey = textOrNull(root.get("eventKey"));
        if (eventType == null || eventType.isBlank()) {
            ctx.status(422).result("eventType is required");
            return;
        }
        if (eventKey == null || eventKey.isBlank()) {
            ctx.status(422).result("eventKey is required");
            return;
        }

        final ExternalEvent.Builder builder = ExternalEvent.builder().eventType(eventType).eventKey(eventKey)
                .sourceTransport(SOURCE_TRANSPORT);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.idempotencyKey(idempotencyKey);
        }
        final JsonNode payloadNode = root.get("payload");
        if (payloadNode != null && payloadNode.isObject()) {
            builder.payload(textPayload(payloadNode));
        }

        final int matched = resolver.resolve(builder.build());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyCache.record(idempotencyKey, matched);
        }
        log.debug("rewake.webhook.dispatched eventType={} eventKey={} idempotencyKey={} matched={}", eventType,
                eventKey, idempotencyKey, matched);
        respondMatched(ctx, matched, false);
    }

    private static void respondMatched(Context ctx, int matched, boolean replay) {
        ctx.status(200).contentType("application/json")
                .result("{\"matched\":" + matched + ",\"idempotentReplay\":" + replay + "}");
    }

    private static String textOrNull(JsonNode n) {
        return n != null && n.isTextual() ? n.asText() : null;
    }

    private static Map<String, String> textPayload(JsonNode obj) {
        final Map<String, String> out = new LinkedHashMap<>();
        final Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
        while (it.hasNext()) {
            final Map.Entry<String, JsonNode> e = it.next();
            if (e.getValue() != null && e.getValue().isTextual()) {
                out.put(e.getKey(), e.getValue().asText());
            }
        }
        return out;
    }
}
