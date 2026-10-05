package at.aimon.core.skill.hook.declarative;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

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
 * <b>What a request can reach and carry.</b> The URL is the configured one, never templated. Headers and body are
 * templated, and {@code ${env.X}} reads the process environment for the names the same declaration lists in
 * {@code allowedEnvVars} &mdash; the list keeps a template from reading a variable its author did not name; it is not
 * a limit on what the author of the declaration can send. Template values are substituted without escaping
 * ({@link TemplateRenderer}), so a {@code ${tool_input.X}} inside a JSON body is the model's text placed inside the
 * document. {@code http://} URLs are accepted as well as {@code https://}.
 *
 * <p>
 * <b>What a response can do.</b> At most {@link #MAX_RESPONSE_BYTES} of body are read; a larger one is no verdict.
 * The client {@link #createDefault()} builds does not follow redirects, so a 3xx is a non-2xx like any other: no
 * verdict.
 *
 * <p>
 * <b>Lifetime.</b> The executor holds an {@link HttpClient} and nothing else. On the Java 17 baseline a client has
 * no {@code close()}: its selector thread is a daemon and ends when the client is no longer reachable, so there is
 * nothing for an assembly to enrol on a teardown plan.
 *
 * <p>
 * Thread-safe: the underlying {@code HttpClient} and {@code ObjectMapper} are safe to share across threads.
 */
public final class HttpActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(HttpActionExecutor.class);

    /**
     * The largest response body the executor reads, in bytes (1 MiB).
     *
     * <p>
     * A decision document is a few hundred bytes; the largest legitimate answer carries an {@code updatedInput}. The
     * endpoint is whatever the configuration names, and without a bound one answer could exhaust the host's heap. A
     * larger body is not truncated and guessed at: the call has no verdict ({@code INVALID_RESPONSE}).
     */
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;

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
     * Convenience factory that builds a default {@link HttpClient} (5s connect timeout, redirects not followed) and
     * a fresh {@link ObjectMapper}.
     *
     * <p>
     * <b>Redirects are not followed.</b> The JDK client re-sends the request's headers to the redirect target, and a
     * hook's headers are where {@code ${env.X}} puts its tokens: following a redirect hands them to a host the
     * configuration never named, and makes that host's answer the hook's verdict. A 3xx is therefore read as any other
     * non-2xx &mdash; no verdict &mdash; and the URL in the configuration has to be the final one.
     *
     * <p>
     * <b>Proxy.</b> No proxy is configured here, which for the JDK client means the JVM's default
     * {@link java.net.ProxySelector}: the {@code http.proxyHost} / {@code https.proxyHost} system properties apply,
     * the {@code HTTPS_PROXY} environment variables do not.
     *
     * @return a new executor (never null)
     */
    public static HttpActionExecutor createDefault() {
        return new HttpActionExecutor(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(), new ObjectMapper());
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
            final HttpResponse<String> response = httpClient.send(request, HttpActionExecutor::cappedBody);
            return mapResponse(action, response);
        } catch (HttpTimeoutException e) {
            log.warn("HTTP hook to {} timed out after {}: {}", url, action.getTimeout(), e.getMessage());
            return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.TIMEOUT,
                    "no response within " + action.getTimeout().toMillis() + "ms");
        } catch (IOException e) {
            if (isResponseTooLarge(e)) {
                log.warn("HTTP hook to {} answered with more than {} bytes; the body was not read", url,
                        MAX_RESPONSE_BYTES);
                return ActionCallOutcome.notRun(ShellHookOutcome.Unrun.INVALID_RESPONSE,
                        "response larger than " + MAX_RESPONSE_BYTES + " bytes");
            }
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
                .map(value -> value.toLowerCase(Locale.ROOT).contains("json")).orElse(false);
    }

    /**
     * The body handler: {@code BodyHandlers.ofString()} with {@link #MAX_RESPONSE_BYTES} as an upper bound.
     */
    private static HttpResponse.BodySubscriber<String> cappedBody(HttpResponse.ResponseInfo info) {
        return new CappedStringSubscriber(charsetOf(info));
    }

    /** The charset the response declares in {@code Content-Type}, or UTF-8 when it declares none it can name. */
    private static Charset charsetOf(HttpResponse.ResponseInfo info) {
        final String contentType = info.headers().firstValue("Content-Type").orElse("");
        for (String parameter : contentType.split(";")) {
            final String trimmed = parameter.strip();
            if (trimmed.regionMatches(true, 0, "charset=", 0, "charset=".length())) {
                final String name = trimmed.substring("charset=".length()).replace("\"", "").strip();
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException unknown) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean isResponseTooLarge(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof ResponseTooLargeException) {
                return true;
            }
            if (t.getCause() == t) {
                return false;
            }
        }
        return false;
    }

    /** Raised inside the client when a body passes {@link #MAX_RESPONSE_BYTES}; {@code send} wraps it. */
    private static final class ResponseTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        ResponseTooLargeException() {
            super("response body exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
    }

    /**
     * Collects a response body as a string and gives up, cancelling the exchange, once it passes
     * {@link #MAX_RESPONSE_BYTES}. The JDK only gained a limiting handler in a release newer than the baseline.
     */
    private static final class CappedStringSubscriber implements HttpResponse.BodySubscriber<String> {

        private final Charset charset;
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        CappedStringSubscriber(Charset charset) {
            this.charset = charset;
        }

        @Override
        public CompletionStage<String> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription newSubscription) {
            this.subscription = newSubscription;
            newSubscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    body.completeExceptionally(new ResponseTooLargeException());
                    return;
                }
                final byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toString(charset));
        }
    }
}
