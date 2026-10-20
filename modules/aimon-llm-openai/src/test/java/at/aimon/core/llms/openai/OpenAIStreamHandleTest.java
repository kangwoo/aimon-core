package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.openai.core.http.StreamResponse;

import okhttp3.Call;

/**
 * The handle's two ways of ending a stream are for two threads and never run the same code: {@code abort()} reaches
 * the transport only, {@code close()} reaches the stream only, once.
 */
@DisplayName("OpenAIStreamHandle")
class OpenAIStreamHandleTest {

    @SuppressWarnings("unchecked")
    private final StreamResponse<String> streamResponse = mock(StreamResponse.class);
    private final Call call = mock(Call.class);
    private final OpenAICallAbort abort = new OpenAICallAbort("k");

    @Test
    @DisplayName("consume() drains through the consumer and marks the call complete")
    void consumeDrainsAndCompletes() {
        when(streamResponse.stream()).thenReturn(Stream.of("a", "b"));
        final List<String> seen = new ArrayList<>();
        final OpenAIStreamHandle handle = OpenAIStreamHandle.of(abort, streamResponse,
                stream -> stream.forEach(seen::add));

        handle.consume();

        assertThat(seen).containsExactly("a", "b");
        assertThat(abort.state()).isEqualTo(OpenAICallAbort.State.COMPLETED);
    }

    @Test
    @DisplayName("a consumer that throws leaves the call open: a late abort can still cut a stalled transport")
    void consumeFailureLeavesTheCallOpen() {
        when(streamResponse.stream()).thenReturn(Stream.of("a"));
        final OpenAIStreamHandle handle = OpenAIStreamHandle.of(abort, streamResponse, stream -> {
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(handle::consume).isInstanceOf(IllegalStateException.class);

        assertThat(abort.state()).isEqualTo(OpenAICallAbort.State.OPEN);
    }

    @Test
    @DisplayName("abort() cancels the call's transport and never touches the stream")
    void abortReachesTheTransportOnly() {
        abort.attach(call);
        final OpenAIStreamHandle handle = OpenAIStreamHandle.of(abort, streamResponse, stream -> {
        });

        handle.abort();
        handle.abort();

        verify(call, times(1)).cancel();
        verify(streamResponse, never()).close();
    }

    @Test
    @DisplayName("close() closes the stream exactly once, however often it is called")
    void closeIsOnce() {
        final OpenAIStreamHandle handle = OpenAIStreamHandle.of(abort, streamResponse, stream -> {
        });

        handle.close();
        handle.close();

        verify(streamResponse, times(1)).close();
    }

    @Test
    @DisplayName("close() after abort() still closes the stream once: a cancelled call holds resources too")
    void closeAfterAbortStillCloses() {
        abort.attach(call);
        final OpenAIStreamHandle handle = OpenAIStreamHandle.of(abort, streamResponse, stream -> {
        });

        handle.abort();
        handle.close();

        verify(call, times(1)).cancel();
        verify(streamResponse, times(1)).close();
    }
}
