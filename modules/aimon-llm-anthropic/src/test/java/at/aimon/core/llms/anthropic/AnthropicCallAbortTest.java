package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.anthropic.core.http.HttpResponse;

/**
 * The abort object's state machine: which lever {@code abort()} pulls in each phase, what it does in each state, and
 * that it reaches only its own call.
 */
@DisplayName("AnthropicCallAbort")
class AnthropicCallAbortTest {

    @Test
    @DisplayName("starts OPEN, and an abort with nothing attached moves to ABORTED without throwing")
    void abortBeforeAnyAttempt() {
        final AnthropicCallAbort abort = new AnthropicCallAbort("k");

        assertThat(abort.state()).isEqualTo(AnthropicCallAbort.State.OPEN);
        assertThatCode(abort::abort).doesNotThrowAnyException();
        assertThat(abort.isAborted()).isTrue();
    }

    @Test
    @DisplayName("during the header wait, abort cancels the attempt's future")
    void abortCancelsTheAttempt() {
        final AnthropicCallAbort abort = new AnthropicCallAbort("k");
        final CompletableFuture<HttpResponse> attempt = new CompletableFuture<>();
        abort.attachAttempt(attempt);

        abort.abort();

        assertThat(attempt).isCancelled();
    }

    @Test
    @DisplayName("once the headers are in, abort closes the adapter's response (its race-safe close)")
    void abortClosesTheResponse() {
        final AnthropicCallAbort abort = new AnthropicCallAbort("k");
        final HttpResponse response = mock(HttpResponse.class);
        abort.attachAttempt(CompletableFuture.completedFuture(response));
        abort.attachResponse(response);

        abort.abort();

        verify(response, times(1)).close();
    }

    @Test
    @DisplayName("levers attached after the abort are pulled at once")
    void attachAfterAbortPullsAtOnce() {
        final AnthropicCallAbort abort = new AnthropicCallAbort("k");
        abort.abort();
        final CompletableFuture<HttpResponse> attempt = new CompletableFuture<>();
        final HttpResponse response = mock(HttpResponse.class);

        abort.attachAttempt(attempt);
        abort.attachResponse(response);

        assertThat(attempt).isCancelled();
        verify(response, times(1)).close();
    }

    @Test
    @DisplayName("abort from many threads at once closes the response exactly once and nobody throws")
    void concurrentAbortsCloseOnce() throws Exception {
        final AnthropicCallAbort abort = new AnthropicCallAbort("k");
        final HttpResponse response = mock(HttpResponse.class);
        abort.attachResponse(response);
        final int threads = 16;
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        final List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    go.await();
                    abort.abort();
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                } finally {
                    done.countDown();
                }
            }).start();
        }

        go.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        verify(response, times(1)).close();
    }

    @Test
    @DisplayName("a close that throws does not escape the lever")
    void aThrowingCloseIsContained() {
        final AnthropicCallAbort abort = new AnthropicCallAbort("k");
        final HttpResponse response = mock(HttpResponse.class);
        doThrow(new IllegalStateException("boom")).when(response).close();
        abort.attachResponse(response);

        assertThatCode(abort::abort).doesNotThrowAnyException();
        assertThat(abort.isAborted()).isTrue();
    }

    @Test
    @DisplayName("after complete() an abort is a no-op: the answer is whole")
    void abortAfterCompleteIsANoOp() {
        final AnthropicCallAbort abort = new AnthropicCallAbort("k");
        final HttpResponse response = mock(HttpResponse.class);
        abort.attachResponse(response);
        abort.complete();

        abort.abort();

        verify(response, never()).close();
        assertThat(abort.state()).isEqualTo(AnthropicCallAbort.State.COMPLETED);
    }

    @Test
    @DisplayName("after release() an abort is a no-op and the levers are gone")
    void abortAfterReleaseIsANoOp() {
        final AnthropicCallAbort abort = new AnthropicCallAbort("k");
        final CompletableFuture<HttpResponse> attempt = new CompletableFuture<>();
        final HttpResponse response = mock(HttpResponse.class);
        abort.attachAttempt(attempt);
        abort.attachResponse(response);
        abort.release();

        abort.abort();

        assertThat(attempt).isNotCancelled();
        verify(response, never()).close();
        assertThat(abort.state()).isEqualTo(AnthropicCallAbort.State.RELEASED);
    }
}
