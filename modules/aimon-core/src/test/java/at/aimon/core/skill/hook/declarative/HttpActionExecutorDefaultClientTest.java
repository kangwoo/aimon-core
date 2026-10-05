package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.sun.net.httpserver.HttpServer;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.skill.hook.action.HttpAction;

/**
 * What {@link HttpActionExecutor#createDefault()} does on the wire, against loopback servers.
 *
 * <p>
 * The executor is what an assembly turns on to let {@code hooks.json} and skill frontmatter make outbound requests,
 * with headers templated from the process environment. Two defaults of the client it builds were unsafe for that
 * job: it followed redirects, carrying those headers to whichever host the endpoint named, and it read a response
 * of any size into memory.
 */
@DisplayName("HttpActionExecutor.createDefault() on the wire")
class HttpActionExecutorDefaultClientTest {

    private final List<String> tokensSeenByOtherHost = new CopyOnWriteArrayList<>();
    private HttpServer endpoint;
    private HttpServer otherHost;
    private final HttpActionExecutor executor = HttpActionExecutor.createDefault();

    @BeforeEach
    void startServers() throws IOException {
        endpoint = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        otherHost = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        otherHost.createContext("/", exchange -> {
            final String token = exchange.getRequestHeaders().getFirst("X-Auth-Token");
            tokensSeenByOtherHost.add(String.valueOf(token));
            respond(exchange, 200, "{\"decision\":\"allow\"}");
        });
        endpoint.start();
        otherHost.start();
    }

    @AfterEach
    void stopServers() {
        endpoint.stop(0);
        otherHost.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private HttpAction action(String path) {
        return HttpAction.builder().url("http://127.0.0.1:" + endpoint.getAddress().getPort() + path)
                .addHeader("X-Auth-Token", "${env.HOOK_TOKEN}").allowedEnvVars(List.of("HOOK_TOKEN"))
                .timeout(Duration.ofSeconds(5)).build();
    }

    private ActionCallOutcome call(String path) {
        return executor.attempt(action(path), ToolInput.of(), Map.of(), Map.of("HOOK_TOKEN", "s3cret"));
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void redirectIsNotFollowed_soTheTemplatedHeaderNeverReachesAnotherHost_andThereIsNoVerdict() {
        endpoint.createContext("/hook", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + otherHost.getAddress().getPort() + "/collect");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });

        final ActionCallOutcome outcome = call("/hook");

        // The other host answers "allow". Following the redirect would have made that the guard's verdict and handed
        // the token to a host the configuration never named.
        assertThat(tokensSeenByOtherHost).isEmpty();
        assertThat(outcome.getVerdict()).isEmpty();
        assertThat(outcome.getUnrun().orElseThrow().getUnrunCause()).contains(ShellHookOutcome.Unrun.CALL_FAILED);
        assertThat(outcome.getUnrun().orElseThrow().unrunReason()).contains("HTTP 307");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void responseLargerThanTheCap_isNoVerdict_andIsNotReadIntoMemory() {
        final int tooLarge = HttpActionExecutor.MAX_RESPONSE_BYTES + 64 * 1024;
        endpoint.createContext("/hook", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // Chunked: no Content-Length to reject up front, so the cap has to hold while the body streams.
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{\"decision\":\"allow\",\"pad\":\"".getBytes(StandardCharsets.UTF_8));
                final byte[] chunk = new byte[8192];
                java.util.Arrays.fill(chunk, (byte) 'x');
                for (int written = 0; written < tooLarge; written += chunk.length) {
                    out.write(chunk);
                }
                out.write("\"}".getBytes(StandardCharsets.UTF_8));
            } catch (IOException clientWentAway) {
                // Expected once the cap is enforced: the client stops reading.
            }
        });

        final ActionCallOutcome outcome = call("/hook");

        assertThat(outcome.getVerdict()).isEmpty();
        assertThat(outcome.getUnrun().orElseThrow().getUnrunCause()).contains(ShellHookOutcome.Unrun.INVALID_RESPONSE);
        assertThat(outcome.getUnrun().orElseThrow().unrunReason()).contains("larger than");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void responseAtTheCap_isStillRead() {
        final StringBuilder body = new StringBuilder("{\"decision\":\"deny\",\"reason\":\"no\",\"pad\":\"");
        final int padding = HttpActionExecutor.MAX_RESPONSE_BYTES - body.length() - 2;
        body.append("x".repeat(padding)).append("\"}");
        endpoint.createContext("/hook", exchange -> respond(exchange, 200, body.toString()));

        final ActionCallOutcome outcome = call("/hook");

        assertThat(outcome.getVerdict().orElseThrow().isBlocked()).isTrue();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void bodyIsDecodedWithTheCharsetTheResponseDeclares() {
        endpoint.createContext("/hook", exchange -> {
            final byte[] bytes = "{\"decision\":\"deny\",\"reason\":\"금지\"}"
                    .getBytes(java.nio.charset.Charset.forName("EUC-KR"));
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=EUC-KR");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });

        final ActionCallOutcome outcome = call("/hook");

        assertThat(outcome.getVerdict().orElseThrow().getFeedback()).contains("금지");
    }
}
