package at.aimon.core.skill.hook.declarative;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.skill.hook.action.HttpAction;
import at.aimon.core.skill.hook.action.HttpMethod;

/**
 * Executes {@link HttpAction} declarative hook actions.
 *
 * <p>
 * Uses Java 17 {@link HttpClient}. Headers and body are rendered via {@link TemplateRenderer} with the env whitelist
 * provided by the action — process env is never read directly. The whitelist is the security boundary; an env name
 * referenced by a placeholder but absent from the whitelist renders to the empty string.
 *
 * <p>
 * Response → {@code HookResult} mapping (parsed from a JSON body when {@code Content-Type} is {@code application/json}
 * or the body parses as an object):
 * <ul>
 * <li>{@code {"decision":"allow"}} → {@code HookResult.success()} (or {@code withFeedback} when {@code feedback} or
 * {@code systemMessage} is present)
 * <li>{@code {"decision":"deny", "reason":"..."}} → {@code HookResult.block(reason)}
 * <li>{@code {"decision":"defer"}} → {@code HookResult.success()} (delegates to the next hook)
 * <li>{@code "updatedInput": {...}} carried alongside any decision → {@code HookResult.builder().updatedInput(...)}
 * </ul>
 *
 * <p>
 * Non-2xx responses, malformed JSON and transport errors are logged at WARN level and degrade to
 * {@link HookResult#success()} — declarative hooks must remain fail-soft for transport problems. A persistent failure
 * is operator-observable through the WARN log.
 *
 * <p>
 * Thread-safe: the underlying {@code HttpClient} and {@code ObjectMapper} are safe to share across threads.
 */
public final class HttpActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpActionExecutor.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new executor.
     *
     * @param httpClient
     *            HTTP client (must not be null)
     * @param objectMapper
     *            JSON mapper used for body templating and response parsing (must not be null)
     */
    public HttpActionExecutor(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient cannot be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper cannot be null");
    }

    /**
     * Convenience factory that builds a default {@link HttpClient} (no proxy, follow normal redirects, 5s connect
     * timeout) and a fresh {@link ObjectMapper}.
     *
     * @return a new executor (never null)
     */
    public static HttpActionExecutor createDefault() {
        return new HttpActionExecutor(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), new ObjectMapper());
    }

    /**
     * Executes the action and returns the resolved {@link HookResult}.
     *
     * @param action
     *            the configured action (must not be null)
     * @param toolInput
     *            tool input source for {@code ${tool_input.X}} placeholders (may be null)
     * @param contextAttributes
     *            context attributes for {@code ${context.X}} placeholders (must not be null)
     * @param processEnv
     *            process env snapshot used to populate the whitelist; only keys present in
     *            {@link HttpAction#getAllowedEnvVars()} are forwarded (must not be null)
     * @return the hook result (never null; success on transport failure)
     */
    public HookResult run(HttpAction action, ToolInput toolInput, Map<String, String> contextAttributes,
            Map<String, String> processEnv) {
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(contextAttributes, "contextAttributes cannot be null");
        Objects.requireNonNull(processEnv, "processEnv cannot be null");

        final TemplateRenderer renderer = TemplateRenderer.builder().toolInput(toolInput)
                .envWhitelist(filterEnv(processEnv, action.getAllowedEnvVars())).context(contextAttributes).build();

        final URI url = action.getUrl();
        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(url).timeout(action.getTimeout());

        for (Map.Entry<String, String> h : action.getHeaders().entrySet()) {
            reqBuilder.header(h.getKey(), renderer.render(h.getValue()));
        }

        final String renderedBody = action.getBodyTemplate() == null ? null : renderer.render(action.getBodyTemplate());
        final HttpRequest.BodyPublisher publisher = renderedBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(renderedBody);

        final HttpRequest request = applyMethod(reqBuilder, action.getMethod(), publisher).build();

        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapResponse(action, response);
        } catch (IOException e) {
            log.warn("HTTP hook to {} failed (transport): {}", url, e.getMessage());
            return HookResult.success();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("HTTP hook to {} interrupted", url);
            return HookResult.success();
        }
    }

    private static Map<String, String> filterEnv(Map<String, String> processEnv, Set<String> whitelist) {
        if (whitelist.isEmpty()) {
            return Map.of();
        }
        final Map<String, String> filtered = new LinkedHashMap<>();
        for (String key : whitelist) {
            final String value = processEnv.get(key);
            filtered.put(key, value == null ? "" : value);
        }
        return filtered;
    }

    private static HttpRequest.Builder applyMethod(HttpRequest.Builder b, HttpMethod method,
            HttpRequest.BodyPublisher publisher) {
        return switch (method) {
            case GET -> b.GET();
            case DELETE -> b.DELETE();
            case POST -> b.POST(publisher);
            case PUT -> b.PUT(publisher);
            case PATCH -> b.method("PATCH", publisher);
        };
    }

    private HookResult mapResponse(HttpAction action, HttpResponse<String> response) {
        final int code = response.statusCode();
        final String body = response.body() == null ? "" : response.body();

        if (code < 200 || code >= 300) {
            log.warn("HTTP hook to {} returned non-2xx status {} (body bytes={})", action.getUrl(), code,
                    body.length());
            return HookResult.success();
        }

        if (body.isBlank()) {
            return HookResult.success();
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            log.warn("HTTP hook to {} returned non-JSON body (status={}): {}", action.getUrl(), code, e.getMessage());
            return HookResult.success();
        }

        if (root == null || !root.isObject()) {
            return HookResult.success();
        }

        final String decision = textOrNull(root, "decision");
        final String reason = textOrNull(root, "reason");
        final String feedback = pickFeedback(root);
        final ToolInput updatedInput = readUpdatedInput(root);

        if ("deny".equalsIgnoreCase(decision)) {
            return HookResult.block(reason != null ? reason : "Denied by HTTP hook");
        }

        // allow / defer / unknown / null → success, optionally with feedback / updatedInput
        if (feedback == null && updatedInput == null) {
            return HookResult.success();
        }

        final HookResult.Builder b = HookResult.builder();
        if (feedback != null) {
            b.feedback(feedback);
        }
        if (updatedInput != null) {
            b.updatedInput(updatedInput);
        }
        return b.build();
    }

    private ToolInput readUpdatedInput(JsonNode root) {
        final JsonNode node = root.get("updatedInput");
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            final Map<String, Object> map = objectMapper.convertValue(node, Map.class);
            return ToolInput.of(map);
        } catch (IllegalArgumentException e) {
            log.warn("HTTP hook returned malformed updatedInput; ignoring: {}", e.getMessage());
            return null;
        }
    }

    private static String pickFeedback(JsonNode root) {
        final String f = textOrNull(root, "feedback");
        if (f != null) {
            return f;
        }
        // Claude Code-compatible alias: systemMessage and hookSpecificOutput.additionalContext
        final String sysMsg = textOrNull(root, "systemMessage");
        if (sysMsg != null) {
            return sysMsg;
        }
        final JsonNode hso = root.get("hookSpecificOutput");
        if (hso != null && hso.isObject()) {
            return textOrNull(hso, "additionalContext");
        }
        return null;
    }

    private static String textOrNull(JsonNode root, String field) {
        final JsonNode n = root.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }
}
