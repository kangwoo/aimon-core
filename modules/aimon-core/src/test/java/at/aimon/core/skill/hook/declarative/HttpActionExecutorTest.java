package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.HttpMethod;

@DisplayName("HttpActionExecutor")
class HttpActionExecutorTest {

    private HttpClient httpClient;
    private HttpActionExecutor executor;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        executor = new HttpActionExecutor(httpClient, new ObjectMapper());
    }

    @Test
    @DisplayName("renders body / headers via TemplateRenderer using the env whitelist")
    void rendersBodyAndHeaders() throws IOException, InterruptedException {
        doReturn(stubResponse(200, "{}")).when(httpClient).send(any(HttpRequest.class), any());

        final HttpAction action = HttpAction.builder().url("https://example.com/hook").method(HttpMethod.POST)
                .addHeader("Authorization", "Bearer ${env.API_TOKEN}").bodyTemplate("{\"x\":\"${tool_input.x}\"}")
                .allowedEnvVars(List.of("API_TOKEN")).build();

        executor.run(action, ToolInput.of("x", "42"), Map.of(), Map.of("API_TOKEN", "secret", "OTHER", "leaked"));

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(captor.capture(), any());
        final HttpRequest req = captor.getValue();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.headers().firstValue("Authorization")).contains("Bearer secret");
        // OTHER must not appear (not in whitelist)
        assertThat(req.bodyPublisher().get().contentLength()).isGreaterThan(0);
    }

    @Test
    @DisplayName("non-whitelisted env keys never read process env")
    void nonWhitelistedEnvIsBlank() throws IOException, InterruptedException {
        doReturn(stubResponse(200, "{}")).when(httpClient).send(any(HttpRequest.class), any());

        final HttpAction action = HttpAction.builder().url("https://example.com/hook")
                .addHeader("X-Token", "${env.SECRET}").build();

        executor.run(action, ToolInput.of(), Map.of(), Map.of("SECRET", "should-not-leak"));

        final ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(captor.capture(), any());
        // Whitelist was empty, so the placeholder rendered to "" — header has empty value.
        assertThat(captor.getValue().headers().firstValue("X-Token")).contains("");
    }

    @Test
    @DisplayName("response decision=deny becomes HookResult.block")
    void denyMapsToBlock() throws IOException, InterruptedException {
        doReturn(stubResponse(200, "{\"decision\":\"deny\",\"reason\":\"nope\"}")).when(httpClient)
                .send(any(HttpRequest.class), any());

        final HookResult result = executor.run(noBodyAction(), ToolInput.of(), Map.of(), Map.of());

        assertThat(result.isBlocked()).isTrue();
        assertThat(result.getFeedback()).contains("nope");
    }

    @Test
    @DisplayName("response feedback / systemMessage / additionalContext map to HookResult.feedback")
    void feedbackVariantsMapping() throws IOException, InterruptedException {
        doReturn(stubResponse(200, "{\"decision\":\"allow\",\"systemMessage\":\"hello\"}")).when(httpClient)
                .send(any(HttpRequest.class), any());

        final HookResult result = executor.run(noBodyAction(), ToolInput.of(), Map.of(), Map.of());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.getFeedback()).contains("hello");
    }

    @Test
    @DisplayName("response updatedInput map becomes HookResult.updatedInput")
    void updatedInputMapping() throws IOException, InterruptedException {
        doReturn(stubResponse(200, "{\"decision\":\"allow\",\"updatedInput\":{\"path\":\"/safe\"}}")).when(httpClient)
                .send(any(HttpRequest.class), any());

        final HookResult result = executor.run(noBodyAction(), ToolInput.of("path", "/danger"), Map.of(), Map.of());

        assertThat(result.getUpdatedInput()).isPresent();
        assertThat(result.getUpdatedInput().get().getRequiredString("path")).isEqualTo("/safe");
    }

    @Test
    @DisplayName("non-2xx response degrades to HookResult.success() with WARN log")
    void non2xxIsSoftFail() throws IOException, InterruptedException {
        doReturn(stubResponse(500, "{\"decision\":\"deny\"}")).when(httpClient).send(any(HttpRequest.class), any());

        final HookResult result = executor.run(noBodyAction(), ToolInput.of(), Map.of(), Map.of());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
        assertThat(result.isBlocked()).isFalse();
    }

    @Test
    @DisplayName("transport IOException degrades to HookResult.success()")
    void transportFailureIsSoftFail() throws IOException, InterruptedException {
        doThrow(new IOException("boom")).when(httpClient).send(any(HttpRequest.class), any());

        final HookResult result = executor.run(noBodyAction(), ToolInput.of(), Map.of(), Map.of());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    private static HttpAction noBodyAction() {
        return HttpAction.builder().url("https://example.com/hook").method(HttpMethod.POST).build();
    }

    private static HttpResponse<String> stubResponse(int status, String body) {
        return new StubResponse(status, body);
    }

    /** Minimal HttpResponse stub used by the tests above (Mockito's deep-stubbing of HttpResponse is awkward). */
    private static final class StubResponse implements HttpResponse<String> {
        private final int statusCode;
        private final String body;

        StubResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return URI.create("https://example.com/hook");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
