package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.anthropic.core.RequestOptions;
import com.anthropic.core.http.HttpClient;
import com.anthropic.core.http.HttpMethod;
import com.anthropic.core.http.HttpRequest;
import com.anthropic.core.http.HttpResponse;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;

import at.aimon.core.llms.anthropic.exception.AnthropicCallAbortedException;

/**
 * The decorator's per-attempt hook over a scripted adapter: what it strips, what it refuses, which levers it attaches,
 * and that the adapter's own failures come out as the adapter threw them.
 */
@DisplayName("AnthropicAbortableHttpClient - the per-attempt hook")
class AnthropicAbortableHttpClientTest {

    private final HttpClient adapter = mock(HttpClient.class);
    private final AnthropicAbortRegistry registry = new AnthropicAbortRegistry();
    private final AnthropicAbortableHttpClient transport = new AnthropicAbortableHttpClient(adapter, registry);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        worker.shutdownNow();
    }

    private static HttpRequest requestFor(AnthropicCallAbort abort) {
        return HttpRequest.builder().method(HttpMethod.POST).baseUrl("http://127.0.0.1:1").addPathSegment("v1")
                .putHeader(AnthropicCallAbort.HEADER, abort.key()).build();
    }

    @Test
    @DisplayName("an attempt of an aborted call is refused before the adapter sees it, with a type the SDK does not retry")
    void refusesAnAbortedCallsAttempt() {
        final AnthropicCallAbort abort = registry.open();
        abort.abort();

        assertThatThrownBy(() -> transport.execute(requestFor(abort), RequestOptions.none()))
                .isInstanceOf(AnthropicCallAbortedException.class).isNotInstanceOf(AnthropicIoException.class)
                .isNotInstanceOf(AnthropicRetryableException.class).hasMessageContaining("refused before it was sent");
        verify(adapter, never()).executeAsync(any(), any());
    }

    @Test
    @DisplayName("the asynchronous form refuses the same way, as a failed future")
    void refusesAsynchronously() {
        final AnthropicCallAbort abort = registry.open();
        abort.abort();

        final CompletableFuture<HttpResponse> attempt = transport.executeAsync(requestFor(abort),
                RequestOptions.none());

        assertThat(attempt).isCompletedExceptionally();
        assertThatThrownBy(attempt::get).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AnthropicCallAbortedException.class);
        verify(adapter, never()).executeAsync(any(), any());
    }

    @Test
    @DisplayName("the abort key is stripped before the adapter sees the request, and the response lever is attached")
    void stripsTheKeyAndAttachesTheResponse() {
        final AnthropicCallAbort abort = registry.open();
        final HttpResponse response = mock(HttpResponse.class);
        when(adapter.executeAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(response));

        final HttpResponse returned = transport.execute(requestFor(abort), RequestOptions.none());

        assertThat(returned).isSameAs(response);
        final ArgumentCaptor<HttpRequest> wire = ArgumentCaptor.forClass(HttpRequest.class);
        verify(adapter).executeAsync(wire.capture(), any());
        assertThat(wire.getValue().headers().values(AnthropicCallAbort.HEADER)).isEmpty();
        // The body-phase lever is in place: an abort now closes this response.
        abort.abort();
        verify(response, times(1)).close();
    }

    @Test
    @DisplayName("an abort during the header wait cancels the attempt and the join fails as an abort, not an I/O error")
    void abortDuringTheHeaderWait() throws Exception {
        final AnthropicCallAbort abort = registry.open();
        final CompletableFuture<HttpResponse> attempt = new CompletableFuture<>();
        when(adapter.executeAsync(any(), any())).thenReturn(attempt);

        final Future<HttpResponse> joining = worker
                .submit(() -> transport.execute(requestFor(abort), RequestOptions.none()));
        // The decorator is blocked in join(); the lever is the future the adapter handed out.
        Thread.sleep(100);
        abort.abort();

        assertThat(attempt).isCancelled();
        assertThatThrownBy(() -> joining.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(AnthropicCallAbortedException.class);
    }

    @Test
    @DisplayName("an abort that lands between the headers and the return closes the response and fails the attempt")
    void abortAfterHeadersBeforeReturn() {
        final AnthropicCallAbort abort = registry.open();
        final HttpResponse response = mock(HttpResponse.class);
        final CompletableFuture<HttpResponse> attempt = new CompletableFuture<>();
        when(adapter.executeAsync(any(), any())).thenAnswer(invocation -> {
            // Completes, and the abort lands in the same instant the decorator is about to attach the response.
            attempt.complete(response);
            abort.abort();
            return attempt;
        });

        assertThatThrownBy(() -> transport.execute(requestFor(abort), RequestOptions.none()))
                .isInstanceOf(AnthropicCallAbortedException.class).hasMessageContaining("after its headers arrived");
        verify(response, times(1)).close();
    }

    @Test
    @DisplayName("the adapter's own failure comes out as the adapter threw it, so the SDK's retry classification holds")
    void adapterFailuresAreUnwrapped() {
        final AnthropicCallAbort abort = registry.open();
        final AnthropicIoException failure = new AnthropicIoException("Request failed", new IOException("refused"));
        when(adapter.executeAsync(any(), any())).thenReturn(CompletableFuture.failedFuture(failure));

        assertThatThrownBy(() -> transport.execute(requestFor(abort), RequestOptions.none())).isSameAs(failure);
        assertThat(abort.state()).isEqualTo(AnthropicCallAbort.State.OPEN);
    }

    @Test
    @DisplayName("a request without the key goes through untouched")
    void requestsWithoutAKeyPassThrough() {
        final HttpResponse response = mock(HttpResponse.class);
        when(adapter.executeAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(response));
        final HttpRequest plain = HttpRequest.builder().method(HttpMethod.POST).baseUrl("http://127.0.0.1:1")
                .addPathSegment("v1").build();

        assertThat(transport.execute(plain, RequestOptions.none())).isSameAs(response);

        final ArgumentCaptor<HttpRequest> wire = ArgumentCaptor.forClass(HttpRequest.class);
        verify(adapter).executeAsync(wire.capture(), any());
        assertThat(wire.getValue()).isSameAs(plain);
    }

    @Test
    @DisplayName("close() closes the adapter")
    void closeClosesTheAdapter() {
        transport.close();

        verify(adapter, times(1)).close();
    }
}
