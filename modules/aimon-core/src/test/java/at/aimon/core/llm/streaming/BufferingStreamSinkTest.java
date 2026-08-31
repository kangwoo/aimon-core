package at.aimon.core.llm.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class BufferingStreamSinkTest {

    @Test
    void constructorRejectsNullDownstream() {
        assertThatNullPointerException().isThrownBy(() -> new BufferingStreamSink(null));
    }

    @Test
    void acceptRejectsNullChunk() {
        BufferingStreamSink sink = new BufferingStreamSink(chunk -> {
        });
        assertThatNullPointerException().isThrownBy(() -> sink.accept(null));
    }

    @Test
    void chunksAreNotForwardedUntilFlush() {
        List<LlmStreamChunk> received = new ArrayList<>();
        BufferingStreamSink sink = new BufferingStreamSink(received::add);

        sink.accept(LlmStreamChunk.textDelta(0, "a"));
        sink.accept(LlmStreamChunk.textDelta(1, "b"));

        assertThat(received).isEmpty();
        assertThat(sink.bufferedCount()).isEqualTo(2);
        assertThat(sink.isTerminated()).isFalse();

        sink.flush();

        assertThat(received).hasSize(2);
        assertThat(received).extracting(c -> c.getTextDelta().orElseThrow()).containsExactly("a", "b");
        assertThat(sink.isTerminated()).isTrue();
        assertThat(sink.bufferedCount()).isZero();
    }

    @Test
    void abortDiscardsBufferedChunks() {
        List<LlmStreamChunk> received = new ArrayList<>();
        BufferingStreamSink sink = new BufferingStreamSink(received::add);

        sink.accept(LlmStreamChunk.textDelta(0, "discard me"));
        sink.abort();

        assertThat(received).isEmpty();
        assertThat(sink.isTerminated()).isTrue();
        assertThat(sink.bufferedCount()).isZero();
    }

    @Test
    void acceptAfterFlushThrows() {
        BufferingStreamSink sink = new BufferingStreamSink(c -> {
        });
        sink.flush();
        assertThatIllegalStateException().isThrownBy(() -> sink.accept(LlmStreamChunk.textDelta(0, "x")));
    }

    @Test
    void acceptAfterAbortThrows() {
        BufferingStreamSink sink = new BufferingStreamSink(c -> {
        });
        sink.abort();
        assertThatIllegalStateException().isThrownBy(() -> sink.accept(LlmStreamChunk.textDelta(0, "x")));
    }

    @Test
    void flushAfterFlushThrows() {
        BufferingStreamSink sink = new BufferingStreamSink(c -> {
        });
        sink.flush();
        assertThatIllegalStateException().isThrownBy(sink::flush);
    }

    @Test
    void abortIsIdempotent() {
        BufferingStreamSink sink = new BufferingStreamSink(c -> {
        });
        sink.abort();
        sink.abort();
        assertThat(sink.isTerminated()).isTrue();
    }

    @Test
    void flushPreservesArrivalOrderIncludingStreamEnd() {
        List<LlmStreamChunk> received = new ArrayList<>();
        BufferingStreamSink sink = new BufferingStreamSink(received::add);

        sink.accept(LlmStreamChunk.textDelta(0, "x"));
        sink.accept(LlmStreamChunk.streamEnd(1, null, Optional.empty()));
        sink.flush();

        assertThat(received).extracting(LlmStreamChunk::getKind).containsExactly(LlmStreamChunk.Kind.TEXT_DELTA,
                LlmStreamChunk.Kind.STREAM_END);
    }
}
