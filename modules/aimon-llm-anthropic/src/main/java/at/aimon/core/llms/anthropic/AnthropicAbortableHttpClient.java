package at.aimon.core.llms.anthropic;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpRequestBody;
import com.anthropic.core.http.HttpResponse;

import at.aimon.core.llms.anthropic.exception.AnthropicCallAbortedException;

/**
 * A decorator over the SDK's own OkHttp adapter that makes a call abortable, one attempt at a time.
 *
 * <p>
 * Unlike the OpenAI module, this one keeps the SDK's transport: {@code com.anthropic.client.okhttp.OkHttpClient}
 * already exposes a cross-thread-safe lever for each phase of an attempt. Its {@code executeAsync} future cancels the
 * {@code okhttp3.Call} when the future is cancelled, which covers the header wait; and its response's
 * {@code close()} is documented race-safe — it cancels the call if a read is in flight and closes the body otherwise
 * — which covers the body. This decorator sits directly over the raw adapter (an SDK {@code Interceptor} would sit
 * over a logging wrapper whose derived future does not cancel the call) and does three things on every attempt,
 * including the SDK's own retries, because the SDK's retry loop sits <em>above</em> the client it was built with:
 *
 * <ol>
 * <li>Correlates the attempt with its call through {@link AnthropicCallAbort#HEADER}, which the streaming path put on
 * the SDK params and which is stripped here, before the adapter sees the request, so it never reaches the wire.</li>
 * <li>Refuses the attempt with {@link AnthropicCallAbortedException} — a type the SDK does not retry — if that call
 * has been aborted, so a cancelled request is never answered with a fresh one. An attempt that is cut mid-flight fails
 * the same way rather than with the SDK's retryable {@code AnthropicIoException}.</li>
 * <li>Hands the attempt's two levers to the call's abort object as each phase begins.</li>
 * </ol>
 *
 * <p>
 * For a cancellable call the synchronous path runs the adapter's {@code executeAsync} and joins it, so the header
 * wait goes through OkHttp's {@code Dispatcher} (64 concurrent, per host raised to the same by the adapter) where the
 * adapter's own {@code execute} did not. A slot is held only until the headers arrive, so the cap is on concurrent
 * cancellable header waits per client, not on concurrent streams. A request with no abort registered for it — a
 * non-cancellable blocking call, or the SDK used directly — goes straight to the adapter's synchronous
 * {@code execute} and takes no slot at all.
 *
 * <p>
 * Everything the adapter does for a request — the backend's URL resolution and headers, the credential headers, the
 * {@code X-Stainless-*-Timeout} headers, per-request timeouts — is untouched.
 */
final class AnthropicAbortableHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicAbortableHttpClient.class);

    private final HttpClient delegate;
    private final AnthropicAbortRegistry registry;

    /**
     * @param delegate
     *            the SDK's OkHttp adapter, which this decorator owns and closes
     * @param registry
     *            the abort objects of the calls in flight, shared with the client
     */
    AnthropicAbortableHttpClient(HttpClient delegate, AnthropicAbortRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
        final Optional<AnthropicCallAbort> abort = registry.find(abortKey(request));
        final HttpRequest wire = withoutAbortKey(request);
        if (abort.isEmpty()) {
            // Not a cancellable call (a blocking sendMessage, or the SDK used directly): there is no lever to hand
            // over, so the adapter's own synchronous path runs — no Dispatcher slot, no thread handoff.
            return delegate.execute(wire, requestOptions);
        }
        refuseIfAborted(abort, wire, "refused before it was sent");
        final CompletableFuture<HttpResponse> attempt = delegate.executeAsync(wire, requestOptions);
        // Attached before the join: an abort landing from here on cancels this attempt, not a previous one.
        abort.ifPresent(a -> a.attachAttempt(attempt));
        final HttpResponse response;
        try {
            // Uninterruptible, as the adapter's own synchronous execute() is.
            response = attempt.join();
        } catch (CancellationException e) {
            // Only the abort lever cancels this future.
            throw new AnthropicCallAbortedException("Anthropic attempt cut by the call's abort", e);
        } catch (CompletionException e) {
            if (isAborted(abort)) {
                throw new AnthropicCallAbortedException("Anthropic attempt cut by the call's abort", e);
            }
            throw unwrap(e);
        }
        // Cancelled between the headers arriving and here: the body read would fail anyway. Say why.
        if (isAborted(abort)) {
            closeQuietly(response);
            throw new AnthropicCallAbortedException("Anthropic attempt aborted after its headers arrived");
        }
        abort.ifPresent(a -> a.attachResponse(response));
        if (isAborted(abort)) {
            // The abort landed while the lever changed hands; one side or the other has closed the response.
            closeQuietly(response);
            throw new AnthropicCallAbortedException("Anthropic attempt aborted after its headers arrived");
        }
        return response;
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
        final Optional<AnthropicCallAbort> abort = registry.find(abortKey(request));
        final HttpRequest wire = withoutAbortKey(request);
        if (isAborted(abort)) {
            closeQuietly(wire.body());
            final CompletableFuture<HttpResponse> refused = new CompletableFuture<>();
            refused.completeExceptionally(
                    new AnthropicCallAbortedException("Anthropic attempt refused before it was sent"));
            return refused;
        }
        // The adapter's own future is returned, not a derived one: cancelling a derived future would not reach the
        // adapter's call. The response lever is attached from a callback on it instead.
        final CompletableFuture<HttpResponse> attempt = delegate.executeAsync(wire, requestOptions);
        abort.ifPresent(a -> {
            a.attachAttempt(attempt);
            attempt.whenComplete((response, failure) -> {
                if (response != null) {
                    a.attachResponse(response);
                }
            });
        });
        return attempt;
    }

    @Override
    public void close() {
        delegate.close();
    }

    private static String abortKey(HttpRequest request) {
        return request.headers().values(AnthropicCallAbort.HEADER).stream().findFirst().orElse(null);
    }

    /** The request as the adapter gets it: the correlation header is for this decorator alone. */
    private static HttpRequest withoutAbortKey(HttpRequest request) {
        if (request.headers().values(AnthropicCallAbort.HEADER).isEmpty()) {
            return request;
        }
        return request.toBuilder().removeHeaders(AnthropicCallAbort.HEADER).build();
    }

    private static boolean isAborted(Optional<AnthropicCallAbort> abort) {
        return abort.map(AnthropicCallAbort::isAborted).orElse(false);
    }

    /** Refuses an attempt that was never handed to the adapter, closing its body as the adapter would have. */
    private static void refuseIfAborted(Optional<AnthropicCallAbort> abort, HttpRequest wire, String when) {
        if (isAborted(abort)) {
            closeQuietly(wire.body());
            log.debug("Anthropic attempt {} for call {}", when, abort.get().key());
            throw new AnthropicCallAbortedException("Anthropic attempt " + when + ": the call was aborted");
        }
    }

    /**
     * The adapter's own failure, as its synchronous {@code execute()} would have thrown it, so the SDK's retry
     * classification and the client's exception mapping see exactly what they saw before: an
     * {@code AnthropicIoException} stays one. A cause that is neither unchecked nor an error cannot be rethrown
     * bare; the wrapper goes up as is, which the SDK's retry check unwraps for itself.
     */
    private static RuntimeException unwrap(CompletionException wrapper) {
        final Throwable cause = wrapper.getCause();
        if (cause instanceof RuntimeException unchecked) {
            return unchecked;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return wrapper;
    }

    private static void closeQuietly(HttpRequestBody body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (RuntimeException e) {
            log.debug("Anthropic request body did not close cleanly: {}", e.getMessage());
        }
    }

    private static void closeQuietly(HttpResponse response) {
        try {
            response.close();
        } catch (RuntimeException e) {
            log.debug("Anthropic response did not close cleanly after an abort: {}", e.getMessage());
        }
    }
}
