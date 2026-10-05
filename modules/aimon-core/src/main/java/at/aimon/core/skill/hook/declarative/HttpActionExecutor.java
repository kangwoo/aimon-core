package at.aimon.core.skill.hook.declarative;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
 * <b>Verdict or no verdict.</b> {@link #attempt} tells the two apart, because a guard has to (see
 * {@link ActionCallOutcome}):
 * <ul>
 * <li><b>Verdict</b> &mdash; any 2xx response that can be read: a JSON object with {@code decision} absent or one of
 * {@code allow} / {@code deny} / {@code defer}; an empty body; a body that is not declared as JSON and does not parse
 * as a JSON object (a webhook answering {@code ok}). Only {@code deny} blocks.
 * <li><b>No verdict</b> &mdash; a transport failure or an interrupted call ({@code CALL_FAILED}), a request that ran
 * out of {@link HttpAction#getTimeout() time} ({@code TIMEOUT}), a non-2xx status ({@code CALL_FAILED}, whatever its
 * body says &mdash; a refusal is spelled {@code decision: deny} in a 2xx), and a 2xx answer that cannot be read as a
 * decision ({@code INVALID_RESPONSE}): a body declared {@code application/json} that does not parse, a
 * {@code decision} that is not text or not one of the three values, an {@code updatedInput} that is not an object.
 * </ul>
 * {@link #run} is the advisory reading of the same call: it logs a missing verdict at WARN and returns
 * {@link HookResult#success()}, so a {@code postTool} webhook stays fail-soft for transport problems.
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
     * Executes the action and returns the resolved {@link HookResult}, reading a call that produced no verdict as
     * success. For events that cannot block; a guard uses {@link #attempt}.
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
     * @return the hook result (never null; success when the call produced no verdict)
     */
    public HookResult run(HttpAction action, ToolInput toolInput, Map<String, String> contextAttributes,
            Map<String, String> processEnv) {
        return attempt(action, toolInput, contextAttributes, processEnv).orSuccess();
    }

    /**
     * Executes the action and reports whether it produced a verdict. Never throws for a failed call.
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
     * @return the verdict, or why there is none (never null)
     * @throws NullPointerException
     *             if action, contextAttributes or processEnv is null
     */
    public ActionCallOutcome attempt(HttpAction action, ToolInput toolInput, Map<String, String> contextAttributes,
            Map<String, String> processEnv) {
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(contextAttributes, "contextAttributes cannot be null");
        Objects.requireNonNull(processEnv, "processEnv cannot be null");

        final URI url = action.getUrl();
        final HttpRequest request;
        try {
            request = buildRequest(action, toolInput, contextAttributes, processEnv);
        } catch (RuntimeException e) {
            // A header the client rejects, a URL without a scheme: the request never left.
            log.warn("HTTP hook to {} could not be built: {}", url, e.getMessage());
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, ShellActionRunner.failureDetail(e));
        }

        try {
            final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return mapResponse(action, response);
        } catch (HttpTimeoutException e) {
            log.warn("HTTP hook to {} timed out after {}: {}", url, action.getTimeout(), e.getMessage());
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.TIMEOUT,
                    "no response within " + action.getTimeout().toMillis() + "ms");
        } catch (IOException e) {
            // The type only: a transport message routinely names the host and port.
            log.warn("HTTP hook to {} failed (transport): {}", url, e.getMessage());
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, ShellActionRunner.failureDetail(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("HTTP hook to {} interrupted", url);
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CANCELLED, "");
        }
    }

    private static HttpRequest buildRequest(HttpAction action, ToolInput toolInput,
            Map<String, String> contextAttributes, Map<String, String> processEnv) {
        final TemplateRenderer renderer = TemplateRenderer.builder().toolInput(toolInput)
                .envWhitelist(filterEnv(processEnv, action.getAllowedEnvVars())).context(contextAttributes).build();

        final HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(action.getUrl()).timeout(action.getTimeout());

        for (Map.Entry<String, String> h : action.getHeaders().entrySet()) {
            reqBuilder.header(h.getKey(), renderer.render(h.getValue()));
        }

        final String renderedBody = action.getBodyTemplate() == null ? null : renderer.render(action.getBodyTemplate());
        final HttpRequest.BodyPublisher publisher = renderedBody == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(renderedBody);

        return applyMethod(reqBuilder, action.getMethod(), publisher).build();
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

    private ActionCallOutcome mapResponse(HttpAction action, HttpResponse<String> response) {
        final int code = response.statusCode();
        final String body = response.body() == null ? "" : response.body();

        if (code < 200 || code >= 300) {
            // Not a verdict even when the body says "deny": a refusal is a 2xx carrying decision=deny. The body is not
            // read at all, so an error page cannot pass for a decision in either direction.
            log.warn("HTTP hook to {} returned non-2xx status {} (body bytes={})", action.getUrl(), code,
                    body.length());
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.CALL_FAILED, "HTTP " + code);
        }

        if (body.isBlank()) {
            return ActionCallOutcome.verdict(HookResult.success());
        }

        final JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            if (declaresJson(response)) {
                // The endpoint said it was answering in JSON and the answer does not parse (cut short, say).
                log.warn("HTTP hook to {} returned a body declared as JSON that does not parse (status={}): {}",
                        action.getUrl(), code, e.getOriginalMessage());
                return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.INVALID_RESPONSE, "malformed JSON body");
            }
            // A plain-text acknowledgement ("ok"): a side-effect endpoint, no decision intended.
            log.debug("HTTP hook to {} returned a non-JSON body (status={}); no decision carried", action.getUrl(),
                    code);
            return ActionCallOutcome.verdict(HookResult.success());
        }

        if (root == null || !root.isObject()) {
            return ActionCallOutcome.verdict(HookResult.success());
        }
        return DecisionDocument.read(root, objectMapper, "HTTP hook to " + action.getUrl(), "Denied by HTTP hook",
                true);
    }

    private static boolean declaresJson(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type")
                .map(value -> value.toLowerCase(java.util.Locale.ROOT).contains("json")).orElse(false);
    }
}
