package at.aimon.rewake.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.credential.InMemoryCredentialStore;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.rewake.ExternalEventResolver;
import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeFireListener;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;
import at.aimon.core.hook.rewake.impl.DefaultExternalEventResolver;
import at.aimon.core.hook.rewake.impl.DefaultRewakeService;
import at.aimon.core.hook.rewake.mcp.McpNotificationToRewakeBridge;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Phase 4B WI-4B.4 — exercises the full external-trigger pipeline end-to-end with both transports sharing a
 * single {@link RewakeService}.
 *
 * <p>
 * Wiring under test:
 *
 * <pre>
 *   {webhook HTTP / MCP notification}
 *       ↓
 *   ExternalEventResolver (DefaultExternalEventResolver)
 *       ↓
 *   RewakeService (DefaultRewakeService) — matches RewakeTriggerEvent envelopes
 *       ↓
 *   RewakeFireListener — captured by the test for assertions
 * </pre>
 *
 * <p>
 * The webhook side is exercised via real HTTP through OkHttp into a Javalin server bound on port 0; the MCP bridge
 * is exercised by directly calling {@code onNotification(...)} since the transport-side read loop is intentionally
 * out of scope for this phase (tracked separately).
 */
class RewakeExternalTriggerE2ETest {

    private static final String SECRET = "e2e-shared-secret";
    private static final AgentRuntimeId CTX_ID = AgentRuntimeId.fromName("agent-e2e");
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final HmacSignatureVerifier signer = new HmacSignatureVerifier();
    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build();

    private CopyOnWriteArrayList<RewakeEnvelope> fired;
    private DefaultRewakeService rewakeService;
    private ExternalEventResolver resolver;
    private RewakeWebhookServer webhook;
    private McpNotificationToRewakeBridge mcpBridge;
    private String webhookBaseUrl;
    private CredentialStore credentialStore;

    @BeforeEach
    void setUp() {
        fired = new CopyOnWriteArrayList<>();
        final RewakeFireListener listener = fired::add;
        rewakeService = new DefaultRewakeService(listener);
        resolver = new DefaultExternalEventResolver(rewakeService);
        mcpBridge = new McpNotificationToRewakeBridge(resolver);

        credentialStore = InMemoryCredentialStore.builder().profile("rewake-webhook", Map.of("hmac-secret", SECRET))
                .build();
        webhook = new RewakeWebhookServer(RewakeWebhookConfig.builder().port(0).build(), credentialStore, resolver)
                .start();
        webhookBaseUrl = "http://localhost:" + webhook.getBoundPort();
    }

    @AfterEach
    void tearDown() {
        if (webhook != null) {
            webhook.close();
        }
        if (rewakeService != null) {
            rewakeService.close();
        }
    }

    @Test
    void webhookFiresMatchingEnvelopeWithMergedPayload() throws IOException {
        rewakeService.schedule(envelopeWith("env-webhook", new RewakeTriggerEvent("ticket-update", "ticket-42")));

        final String body = "{\"eventType\":\"ticket-update\",\"eventKey\":\"ticket-42\","
                + "\"payload\":{\"status\":\"approved\"}}";
        final String sig = signer.sign(body.getBytes(), SECRET);
        try (Response response = http.newCall(post(body, sig, null)).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{\"matched\":1,\"idempotentReplay\":false}");
        }

        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).getEnvelopeId()).isEqualTo("env-webhook");
        assertThat(fired.get(0).getPayload()).containsEntry("status", "approved");
        assertThat(rewakeService.listPending()).isEmpty();
    }

    @Test
    void mcpBridgeFiresMatchingEnvelopeViaResourceUpdated() throws Exception {
        rewakeService.schedule(
                envelopeWith("env-mcp", new RewakeTriggerEvent("mcp.resource_updated", "file:///approval.txt")));

        mcpBridge.onNotification("notifications/resources/updated",
                JSON_MAPPER.readTree("{\"uri\":\"file:///approval.txt\",\"reason\":\"approved\"}"));

        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).getEnvelopeId()).isEqualTo("env-mcp");
        assertThat(fired.get(0).getPayload()).containsEntry("reason", "approved");
        assertThat(rewakeService.listPending()).isEmpty();
    }

    @Test
    void nonMatchingEventFiresNothing() throws IOException {
        rewakeService.schedule(envelopeWith("env-x", new RewakeTriggerEvent("ticket-update", "ticket-99")));

        final String body = "{\"eventType\":\"ticket-update\",\"eventKey\":\"ticket-DIFFERENT\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);
        try (Response response = http.newCall(post(body, sig, null)).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{\"matched\":0,\"idempotentReplay\":false}");
        }

        assertThat(fired).isEmpty();
        assertThat(rewakeService.listPending()).hasSize(1);
    }

    @Test
    void idempotentReplayDoesNotDoubleFire() throws IOException {
        rewakeService.schedule(envelopeWith("env-idem", new RewakeTriggerEvent("ticket-update", "ticket-42")));

        final String body = "{\"eventType\":\"ticket-update\",\"eventKey\":\"ticket-42\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);
        final String idempotencyKey = "idem-7";

        try (Response first = http.newCall(post(body, sig, idempotencyKey)).execute()) {
            assertThat(first.code()).isEqualTo(200);
            assertThat(first.body().string()).isEqualTo("{\"matched\":1,\"idempotentReplay\":false}");
        }
        try (Response replay = http.newCall(post(body, sig, idempotencyKey)).execute()) {
            assertThat(replay.code()).isEqualTo(200);
            assertThat(replay.body().string()).isEqualTo("{\"matched\":1,\"idempotentReplay\":true}");
        }

        assertThat(fired).hasSize(1);
    }

    @Test
    void webhookAndMcpShareTheSameRewakeService() throws Exception {
        rewakeService.schedule(envelopeWith("env-webhook", new RewakeTriggerEvent("ticket-update", "ticket-1")));
        rewakeService
                .schedule(envelopeWith("env-mcp", new RewakeTriggerEvent("mcp.resource_updated", "file:///foo.txt")));

        final String body = "{\"eventType\":\"ticket-update\",\"eventKey\":\"ticket-1\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);
        try (Response r = http.newCall(post(body, sig, null)).execute()) {
            assertThat(r.code()).isEqualTo(200);
        }
        mcpBridge.onNotification("notifications/resources/updated",
                JSON_MAPPER.readTree("{\"uri\":\"file:///foo.txt\"}"));

        assertThat(fired).extracting(RewakeEnvelope::getEnvelopeId).containsExactlyInAnyOrder("env-webhook", "env-mcp");
        assertThat(rewakeService.listPending()).isEmpty();
    }

    @Test
    void firedEnvelopeCannotBeRefiredByLaterCall() throws IOException {
        rewakeService.schedule(envelopeWith("env-once", new RewakeTriggerEvent("ticket-update", "ticket-once")));

        final String body = "{\"eventType\":\"ticket-update\",\"eventKey\":\"ticket-once\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);
        try (Response r1 = http.newCall(post(body, sig, null)).execute()) {
            assertThat(r1.code()).isEqualTo(200);
        }
        // Second call (different idempotency posture, i.e. none) — envelope is gone, matched=0.
        try (Response r2 = http.newCall(post(body, sig, null)).execute()) {
            assertThat(r2.code()).isEqualTo(200);
            assertThat(r2.body().string()).isEqualTo("{\"matched\":0,\"idempotentReplay\":false}");
        }
        assertThat(fired).hasSize(1);
    }

    private Request post(String body, String signature, String idempotencyKey) {
        final Request.Builder rb = new Request.Builder().url(webhookBaseUrl + "/rewake/events")
                .post(RequestBody.create(body, JSON));
        if (signature != null) {
            rb.header("X-Rewake-Signature", signature);
        }
        if (idempotencyKey != null) {
            rb.header("X-Rewake-Idempotency-Key", idempotencyKey);
        }
        return rb.build();
    }

    private static RewakeEnvelope envelopeWith(String id, at.aimon.core.hook.rewake.RewakeTrigger trigger) {
        return RewakeEnvelope.builder().envelopeId(id).agentRuntimeId(CTX_ID).trigger(trigger)
                .originalEventType(HookEventType.PRE_TOOL).originatingHookId("approval-hook")
                .firstScheduledAt(Instant.now()).reason("e2e").build();
    }

    @SuppressWarnings("unused")
    private static List<RewakeEnvelope> snapshot(RewakeService s) {
        return s.listPending();
    }
}
