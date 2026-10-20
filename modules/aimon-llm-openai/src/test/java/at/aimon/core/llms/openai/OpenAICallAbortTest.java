package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import okhttp3.Call;

/**
 * The abort object's state machine: what {@code abort()} does in each state, and that it reaches only its own call.
 */
@DisplayName("OpenAICallAbort")
class OpenAICallAbortTest {

    @Test
    @DisplayName("starts OPEN, and an abort with no attempt attached moves to ABORTED without throwing")
    void abortBeforeAnyAttempt() {
        final OpenAICallAbort abort = new OpenAICallAbort("k");

        assertThat(abort.state()).isEqualTo(OpenAICallAbort.State.OPEN);
        assertThatCode(abort::abort).doesNotThrowAnyException();
        assertThat(abort.isAborted()).isTrue();
    }

    @Test
    @DisplayName("abort cancels the attached attempt")
    void abortCancelsTheAttachedCall() {
        final OpenAICallAbort abort = new OpenAICallAbort("k");
        final Call call = mock(Call.class);
        abort.attach(call);

        abort.abort();

        verify(call, times(1)).cancel();
    }

    @Test
    @DisplayName("an attempt attached after the abort is cancelled at once")
    void attachAfterAbortCancelsAtOnce() {
        final OpenAICallAbort abort = new OpenAICallAbort("k");
        abort.abort();
        final Call late = mock(Call.class);

        abort.attach(late);

        verify(late, times(1)).cancel();
    }

    @Test
    @DisplayName("a retry's attempt replaces the previous one as the lever")
    void retryReplacesTheLever() {
        final OpenAICallAbort abort = new OpenAICallAbort("k");
        final Call first = mock(Call.class);
        final Call second = mock(Call.class);
        abort.attach(first);
        abort.attach(second);

        abort.abort();

        verify(first, never()).cancel();
        verify(second, times(1)).cancel();
    }

    @Test
    @DisplayName("abort from many threads at once cancels the attempt exactly once and nobody throws")
    void concurrentAbortsCancelOnce() throws Exception {
        final OpenAICallAbort abort = new OpenAICallAbort("k");
        final Call call = mock(Call.class);
        abort.attach(call);
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
        verify(call, times(1)).cancel();
        assertThat(abort.state()).isEqualTo(OpenAICallAbort.State.ABORTED);
    }

    @Test
    @DisplayName("after complete() an abort is a no-op: the answer is whole")
    void abortAfterCompleteIsANoOp() {
        final OpenAICallAbort abort = new OpenAICallAbort("k");
        final Call call = mock(Call.class);
        abort.attach(call);
        abort.complete();

        abort.abort();

        verify(call, never()).cancel();
        assertThat(abort.state()).isEqualTo(OpenAICallAbort.State.COMPLETED);
        assertThat(abort.isAborted()).isFalse();
    }

    @Test
    @DisplayName("after release() an abort is a no-op and the lever is gone")
    void abortAfterReleaseIsANoOp() {
        final OpenAICallAbort abort = new OpenAICallAbort("k");
        final Call call = mock(Call.class);
        abort.attach(call);
        abort.release();

        abort.abort();

        verify(call, never()).cancel();
        assertThat(abort.state()).isEqualTo(OpenAICallAbort.State.RELEASED);
    }

    @Test
    @DisplayName("complete() after an abort does not un-abort the call")
    void completeAfterAbortKeepsAborted() {
        final OpenAICallAbort abort = new OpenAICallAbort("k");
        abort.abort();

        abort.complete();

        assertThat(abort.state()).isEqualTo(OpenAICallAbort.State.ABORTED);
    }
}
