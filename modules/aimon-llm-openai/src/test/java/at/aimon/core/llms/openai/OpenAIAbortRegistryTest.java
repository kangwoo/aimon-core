package at.aimon.core.llms.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OpenAIAbortRegistry")
class OpenAIAbortRegistryTest {

    @Test
    @DisplayName("open() registers an object the transport can find by its key")
    void openRegisters() {
        final OpenAIAbortRegistry registry = new OpenAIAbortRegistry();

        final OpenAICallAbort abort = registry.open();

        assertThat(registry.find(abort.key())).containsSame(abort);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("every call gets its own key")
    void keysAreDistinct() {
        final OpenAIAbortRegistry registry = new OpenAIAbortRegistry();

        final OpenAICallAbort first = registry.open();
        final OpenAICallAbort second = registry.open();

        assertThat(first.key()).isNotEqualTo(second.key());
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("release() unregisters the object and drops its lever")
    void releaseUnregisters() {
        final OpenAIAbortRegistry registry = new OpenAIAbortRegistry();
        final OpenAICallAbort abort = registry.open();

        registry.release(abort);

        assertThat(registry.find(abort.key())).isEmpty();
        assertThat(abort.state()).isEqualTo(OpenAICallAbort.State.RELEASED);
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("release() twice is harmless")
    void releaseIsIdempotent() {
        final OpenAIAbortRegistry registry = new OpenAIAbortRegistry();
        final OpenAICallAbort abort = registry.open();
        registry.release(abort);

        registry.release(abort);

        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("find() of a null or unknown key is empty: the attempt goes through untouched")
    void unknownKeysAreEmpty() {
        final OpenAIAbortRegistry registry = new OpenAIAbortRegistry();

        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("not-registered")).isEmpty();
    }
}
