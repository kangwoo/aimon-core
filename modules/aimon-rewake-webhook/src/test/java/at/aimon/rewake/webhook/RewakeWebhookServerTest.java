package at.aimon.rewake.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.credential.CredentialStore;
import at.aimon.core.credential.InMemoryCredentialStore;
import at.aimon.core.hook.rewake.ExternalEvent;
import at.aimon.core.hook.rewake.ExternalEventResolver;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

class RewakeWebhookServerTest {

    private static final String SECRET = "test-shared-secret";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final HmacSignatureVerifier signer = new HmacSignatureVerifier();
    private final OkHttpClient http = new OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build();

    private CredentialStore credentialStore;
    private ExternalEventResolver resolver;
    private RewakeWebhookServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        credentialStore = InMemoryCredentialStore.builder().profile("rewake-webhook", Map.of("hmac-secret", SECRET))
                .build();
        resolver = mock(ExternalEventResolver.class);
        when(resolver.resolve(any())).thenReturn(1);

        server = new RewakeWebhookServer(RewakeWebhookConfig.builder().port(0).build(), credentialStore, resolver)
                .start();
        baseUrl = "http://localhost:" + server.getBoundPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void validRequestDispatchesAndReturnsMatched() throws IOException {
        final String body = "{\"eventType\":\"webhook\",\"eventKey\":\"ticket-1\","
                + "\"payload\":{\"status\":\"approved\"}}";
        final String sig = signer.sign(body.getBytes(), SECRET);

        try (Response response = http.newCall(post(baseUrl + "/rewake/events", body, sig, null)).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo("{\"matched\":1,\"idempotentReplay\":false}");
        }

        final ArgumentCaptor<ExternalEvent> captor = ArgumentCaptor.forClass(ExternalEvent.class);
        verify(resolver).resolve(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("webhook");
        assertThat(captor.getValue().getEventKey()).isEqualTo("ticket-1");
        assertThat(captor.getValue().getPayload()).containsEntry("status", "approved");
        assertThat(captor.getValue().getSourceTransport()).contains("webhook");
    }

    @Test
    void missingSignatureReturns401() throws IOException {
        try (Response response = http.newCall(post(baseUrl + "/rewake/events", "{}", null, null)).execute()) {
            assertThat(response.code()).isEqualTo(401);
        }
        verify(resolver, never()).resolve(any());
    }

    @Test
    void invalidSignatureReturns401() throws IOException {
        try (Response response = http.newCall(post(baseUrl + "/rewake/events",
                "{\"eventType\":\"x\",\"eventKey\":\"y\"}", "deadbeef".repeat(8), null)).execute()) {
            assertThat(response.code()).isEqualTo(401);
        }
        verify(resolver, never()).resolve(any());
    }

    @Test
    void malformedJsonReturns400() throws IOException {
        final String body = "not-json";
        final String sig = signer.sign(body.getBytes(), SECRET);

        try (Response response = http.newCall(post(baseUrl + "/rewake/events", body, sig, null)).execute()) {
            assertThat(response.code()).isEqualTo(400);
        }
        verify(resolver, never()).resolve(any());
    }

    @Test
    void missingEventTypeReturns422() throws IOException {
        final String body = "{\"eventKey\":\"x\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);

        try (Response response = http.newCall(post(baseUrl + "/rewake/events", body, sig, null)).execute()) {
            assertThat(response.code()).isEqualTo(422);
        }
        verify(resolver, never()).resolve(any());
    }

    @Test
    void missingEventKeyReturns422() throws IOException {
        final String body = "{\"eventType\":\"x\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);

        try (Response response = http.newCall(post(baseUrl + "/rewake/events", body, sig, null)).execute()) {
            assertThat(response.code()).isEqualTo(422);
        }
        verify(resolver, never()).resolve(any());
    }

    @Test
    void unconfiguredSecretReturns503() throws Exception {
        server.close();
        final CredentialStore empty = InMemoryCredentialStore.builder().build();
        server = new RewakeWebhookServer(RewakeWebhookConfig.builder().port(0).build(), empty, resolver).start();
        baseUrl = "http://localhost:" + server.getBoundPort();

        final String body = "{\"eventType\":\"x\",\"eventKey\":\"y\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);

        try (Response response = http.newCall(post(baseUrl + "/rewake/events", body, sig, null)).execute()) {
            assertThat(response.code()).isEqualTo(503);
        }
        verify(resolver, never()).resolve(any());
    }

    @Test
    void idempotentReplayReturnsCachedMatchedAndDoesNotReinvokeResolver() throws IOException {
        final String body = "{\"eventType\":\"webhook\",\"eventKey\":\"ticket-1\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);
        final String idempotencyKey = "idem-42";

        try (Response first = http.newCall(post(baseUrl + "/rewake/events", body, sig, idempotencyKey)).execute()) {
            assertThat(first.code()).isEqualTo(200);
            assertThat(first.body().string()).isEqualTo("{\"matched\":1,\"idempotentReplay\":false}");
        }
        try (Response replay = http.newCall(post(baseUrl + "/rewake/events", body, sig, idempotencyKey)).execute()) {
            assertThat(replay.code()).isEqualTo(200);
            assertThat(replay.body().string()).isEqualTo("{\"matched\":1,\"idempotentReplay\":true}");
        }
        verify(resolver, times(1)).resolve(any());
    }

    @Test
    void differentIdempotencyKeysDoNotCollide() throws IOException {
        final String body = "{\"eventType\":\"webhook\",\"eventKey\":\"ticket-1\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);

        try (Response a = http.newCall(post(baseUrl + "/rewake/events", body, sig, "k1")).execute()) {
            assertThat(a.code()).isEqualTo(200);
        }
        try (Response b = http.newCall(post(baseUrl + "/rewake/events", body, sig, "k2")).execute()) {
            assertThat(b.code()).isEqualTo(200);
        }
        verify(resolver, times(2)).resolve(any());
    }

    @Test
    void requestWithoutIdempotencyHeaderIsAlwaysProcessed() throws IOException {
        final String body = "{\"eventType\":\"webhook\",\"eventKey\":\"ticket-1\"}";
        final String sig = signer.sign(body.getBytes(), SECRET);

        for (int i = 0; i < 3; i++) {
            try (Response r = http.newCall(post(baseUrl + "/rewake/events", body, sig, null)).execute()) {
                assertThat(r.code()).isEqualTo(200);
            }
        }
        verify(resolver, times(3)).resolve(any());
    }

    private Request post(String url, String body, String signature, String idempotencyKey) {
        final Request.Builder rb = new Request.Builder().url(url).post(RequestBody.create(body, JSON));
        if (signature != null) {
            rb.header("X-Rewake-Signature", signature);
        }
        if (idempotencyKey != null) {
            rb.header("X-Rewake-Idempotency-Key", idempotencyKey);
        }
        return rb.build();
    }
}
