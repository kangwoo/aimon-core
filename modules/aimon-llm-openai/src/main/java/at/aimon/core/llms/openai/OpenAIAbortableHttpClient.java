package at.aimon.core.llms.openai;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import com.openai.core.http.Headers;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpMethod;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpRequestBody;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIIoException;

import at.aimon.core.llms.openai.exception.OpenAICallAbortedException;
import okhttp3.Cache;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.EventListener;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;

/**
 * This module's own OkHttp transport for the OpenAI SDK, and the per-attempt hook that makes a call abortable.
 *
 * <p>
 * The SDK's bundled adapter cannot be used: its response's {@code close()} closes the OkHttp body directly, which on
 * HTTP/1.1 <em>reads</em> the socket to drain it and so cannot be called while the consumer thread is blocked in a
 * read of the same socket, and nothing in that adapter exposes the {@code okhttp3.Call} that could be cancelled
 * instead. Creating the {@code Call} here, in {@link #execute}, is what lets {@link OpenAICallAbort} hold it for the
 * whole attempt — header wait and body alike — and cut it with the one sanctioned cross-thread lever,
 * {@link Call#cancel()}.
 *
 * <p>
 * Two things happen on every attempt, including the SDK's own retries, because the SDK's retry loop sits
 * <em>above</em> the client it was built with:
 *
 * <ol>
 * <li>The attempt is correlated with its call through {@link OpenAICallAbort#HEADER}, which the streaming path put on
 * the SDK params and which is stripped here, before the request is built, so it never reaches the wire.</li>
 * <li>If that call has been aborted, the attempt is refused with {@link OpenAICallAbortedException} — a type the SDK
 * does not retry — so a cancelled request is never answered with a fresh one. An attempt that is cut mid-flight fails
 * the same way rather than with the SDK's retryable {@code OpenAIIoException}.</li>
 * </ol>
 *
 * <p>
 * Everything else replicates what {@code com.openai.client.okhttp.OkHttpClient} does for the settings this module
 * uses: {@code retryOnConnectionFailure(false)} (retries are the SDK's), redirects followed, the four timeouts from
 * {@link Timeout}, a per-request timeout override through {@code newBuilder()}, the per-host dispatcher limit raised
 * to the overall one, the {@code X-Stainless-*-Timeout} headers, {@code IOException} wrapped as
 * {@code OpenAIIoException("Request failed")}, and a response closed on any failure after it was received. Not
 * replicated, because nothing on {@code OpenAIConfig} reaches them: proxies, SSL, pool sizing, hostname verification,
 * and the Azure cross-origin {@code api-key} redirect interceptor. This class is an {@code HttpClient} only, not a
 * {@code WebSocketClient}: the SDK's realtime services would refuse it, and this module has none.
 */
final class OpenAIAbortableHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAIAbortableHttpClient.class);

    private static final String READ_TIMEOUT_HEADER = "X-Stainless-Read-Timeout";
    private static final String REQUEST_TIMEOUT_HEADER = "X-Stainless-Timeout";

    private final okhttp3.OkHttpClient okHttpClient;
    private final OpenAIAbortRegistry registry;
    private final InFlightCounter inFlight = new InFlightCounter();

    OpenAIAbortableHttpClient(Timeout timeout, OpenAIAbortRegistry registry) {
        Objects.requireNonNull(timeout, "timeout");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.okHttpClient = new okhttp3.OkHttpClient.Builder()
                // The SDK's RetryingHttpClient handles retries if the user enabled them.
                .retryOnConnectionFailure(false).followRedirects(true).followSslRedirects(true)
                .connectTimeout(timeout.connect()).readTimeout(timeout.read()).writeTimeout(timeout.write())
                .callTimeout(timeout.request()).eventListener(inFlight).build();
        // Every request goes to the same host, so the per-host limit is raised to the overall one, as the SDK does.
        okHttpClient.dispatcher().setMaxRequestsPerHost(okHttpClient.dispatcher().getMaxRequests());
    }

    /**
     * Calls started but not yet finished, as OkHttp counts them: {@code callEnd} and {@code callFailed} fire from
     * {@code callDone}, after the body has been released, which is what "in flight" has to mean for a resource check.
     * ({@code Dispatcher.runningCallsCount()} is already 0 for a synchronous call once its headers have arrived.)
     */
    int inFlightCalls() {
        return inFlight.count.get();
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
        final Optional<OpenAICallAbort> abort = registry.find(abortKey(request));
        final HttpRequest wire = withoutAbortKey(request);
        refuseIfAborted(abort, "refused before it was sent");
        final Call call = newCall(wire, requestOptions);
        // Attached before execute(): an abort landing from here on cancels this attempt, not a previous one.
        abort.ifPresent(a -> a.attach(call));
        HttpResponse response = null;
        try (HttpRequestBody body = wire.body()) {
            try {
                response = toHttpResponse(call.execute(), call);
            } catch (IOException e) {
                if (isAborted(abort)) {
                    // Not the SDK's OpenAIIoException: that one is retried, and this attempt must not be.
                    throw new OpenAICallAbortedException("OpenAI attempt cut by the call's abort", e);
                }
                throw new OpenAIIoException("Request failed", e);
            }
            // Cancelled between the headers arriving and here: the body read would fail anyway. Say why.
            refuseIfAborted(abort, "aborted after its headers arrived");
            return response;
        } catch (Throwable failure) {
            // A body must not leak on any failure after execute() returned, which is the adapter's `response.use`.
            closeQuietly(response, failure);
            throw failure;
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
        final Optional<OpenAICallAbort> abort = registry.find(abortKey(request));
        final HttpRequest wire = withoutAbortKey(request);
        final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        if (isAborted(abort)) {
            closeQuietly(wire.body());
            future.completeExceptionally(new OpenAICallAbortedException("OpenAI attempt refused before it was sent"));
            return future;
        }
        final Call call = newCall(wire, requestOptions);
        abort.ifPresent(a -> a.attach(call));
        call.enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) {
                final HttpResponse httpResponse = toHttpResponse(response, call);
                if (isAborted(abort)) {
                    httpResponse.close();
                    future.completeExceptionally(
                            new OpenAICallAbortedException("OpenAI attempt aborted after its headers arrived"));
                } else if (!future.complete(httpResponse)) {
                    httpResponse.close();
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                future.completeExceptionally(isAborted(abort)
                        ? new OpenAICallAbortedException("OpenAI attempt cut by the call's abort", e)
                        : new OpenAIIoException("Request failed", e));
            }
        });
        future.whenComplete((httpResponse, failure) -> {
            if (failure instanceof CancellationException) {
                call.cancel();
            }
            closeQuietly(wire.body());
        });
        return future;
    }

    @Override
    public void close() {
        okHttpClient.dispatcher().executorService().shutdown();
        okHttpClient.connectionPool().evictAll();
        final Cache cache = okHttpClient.cache();
        if (cache != null) {
            try {
                cache.close();
            } catch (IOException e) {
                log.debug("OpenAI transport cache did not close cleanly: {}", e.getMessage());
            }
        }
    }

    private static String abortKey(HttpRequest request) {
        return request.headers().values(OpenAICallAbort.HEADER).stream().findFirst().orElse(null);
    }

    /** The request as it goes on the wire: the correlation header is for the hook alone. */
    private static HttpRequest withoutAbortKey(HttpRequest request) {
        if (request.headers().values(OpenAICallAbort.HEADER).isEmpty()) {
            return request;
        }
        return request.toBuilder().removeHeaders(OpenAICallAbort.HEADER).build();
    }

    private static boolean isAborted(Optional<OpenAICallAbort> abort) {
        return abort.map(OpenAICallAbort::isAborted).orElse(false);
    }

    private static void refuseIfAborted(Optional<OpenAICallAbort> abort, String when) {
        if (isAborted(abort)) {
            log.debug("OpenAI attempt {} for call {}", when, abort.get().key());
            throw new OpenAICallAbortedException("OpenAI attempt " + when + ": the call was aborted");
        }
    }

    private Call newCall(HttpRequest request, RequestOptions requestOptions) {
        okhttp3.OkHttpClient client = okHttpClient;
        final Timeout timeout = requestOptions.getTimeout();
        if (timeout != null) {
            // newBuilder() shares the dispatcher, the pool and the event listener; only the timeouts differ.
            client = okHttpClient.newBuilder().connectTimeout(timeout.connect()).readTimeout(timeout.read())
                    .writeTimeout(timeout.write()).callTimeout(timeout.request()).build();
        }
        return client.newCall(toRequest(request, client));
    }

    private static Request toRequest(HttpRequest request, okhttp3.OkHttpClient client) {
        RequestBody body = request.body() == null ? null : toRequestBody(request.body());
        if (body == null && requiresBody(request.method())) {
            // OkHttp always requires a request body for some methods.
            body = RequestBody.create("", (MediaType) null);
        }
        final Request.Builder builder = new Request.Builder().url(toUrl(request)).method(request.method().name(), body);
        final Headers headers = request.headers();
        for (String name : headers.names()) {
            for (String value : headers.values(name)) {
                builder.addHeader(name, value);
            }
        }
        final Duration readTimeout = Duration.ofMillis(client.readTimeoutMillis());
        final Duration requestTimeout = Duration.ofMillis(client.callTimeoutMillis());
        if (!headers.names().contains(READ_TIMEOUT_HEADER) && !readTimeout.isZero()) {
            builder.addHeader(READ_TIMEOUT_HEADER, Long.toString(readTimeout.getSeconds()));
        }
        if (!headers.names().contains(REQUEST_TIMEOUT_HEADER) && !requestTimeout.isZero()) {
            builder.addHeader(REQUEST_TIMEOUT_HEADER, Long.toString(requestTimeout.getSeconds()));
        }
        return builder.build();
    }

    private static boolean requiresBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH;
    }

    private static HttpUrl toUrl(HttpRequest request) {
        final HttpUrl.Builder url = HttpUrl.get(request.baseUrl()).newBuilder();
        request.pathSegments().forEach(url::addPathSegment);
        for (String key : request.queryParams().keys()) {
            for (String value : request.queryParams().values(key)) {
                url.addQueryParameter(key, value);
            }
        }
        return url.build();
    }

    private static RequestBody toRequestBody(HttpRequestBody body) {
        final MediaType mediaType = body.contentType() == null ? null : MediaType.parse(body.contentType());
        final long length = body.contentLength();
        final boolean oneShot = !body.repeatable();
        return new RequestBody() {
            @Override
            public MediaType contentType() {
                return mediaType;
            }

            @Override
            public long contentLength() {
                return length;
            }

            @Override
            public boolean isOneShot() {
                return oneShot;
            }

            @Override
            public void writeTo(BufferedSink sink) {
                body.writeTo(sink.outputStream());
            }
        };
    }

    /**
     * The SDK's view of an OkHttp response. Its {@code close()} is the consumer thread's, after its read has unwound;
     * should the body's close still fail, the call is cancelled so the socket is closed deterministically rather than
     * left to the pool.
     */
    private static HttpResponse toHttpResponse(Response response, Call call) {
        final Headers.Builder headers = Headers.builder();
        final okhttp3.Headers wireHeaders = response.headers();
        for (int i = 0; i < wireHeaders.size(); i++) {
            headers.put(wireHeaders.name(i), wireHeaders.value(i));
        }
        final Headers built = headers.build();
        final ResponseBody body = response.body();
        return new HttpResponse() {
            @Override
            public int statusCode() {
                return response.code();
            }

            @Override
            public Headers headers() {
                return built;
            }

            @Override
            public InputStream body() {
                return body.byteStream();
            }

            @Override
            public void close() {
                try {
                    body.close();
                } catch (RuntimeException e) {
                    call.cancel();
                    throw e;
                }
            }
        };
    }

    private static void closeQuietly(HttpResponse response, Throwable failure) {
        if (response == null) {
            return;
        }
        try {
            response.close();
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    private static void closeQuietly(HttpRequestBody body) {
        if (body == null) {
            return;
        }
        try {
            body.close();
        } catch (RuntimeException e) {
            log.debug("OpenAI request body did not close cleanly: {}", e.getMessage());
        }
    }

    /** Counts {@code callStart} against {@code callEnd} + {@code callFailed}; see {@link #inFlightCalls()}. */
    private static final class InFlightCounter extends EventListener {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void callStart(Call call) {
            count.incrementAndGet();
        }

        @Override
        public void callEnd(Call call) {
            count.decrementAndGet();
        }

        @Override
        public void callFailed(Call call, IOException ioe) {
            count.decrementAndGet();
        }
    }
}
