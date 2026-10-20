package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AnthropicAbortRegistry")
class AnthropicAbortRegistryTest {

    @Test
    @DisplayName("open() registers an object the decorator can find by its key")
    void openRegisters() {
        final AnthropicAbortRegistry registry = new AnthropicAbortRegistry();

        final AnthropicCallAbort abort = registry.open();

        assertThat(registry.find(abort.key())).containsSame(abort);
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("every call gets its own key")
    void keysAreDistinct() {
        final AnthropicAbortRegistry registry = new AnthropicAbortRegistry();

        assertThat(registry.open().key()).isNotEqualTo(registry.open().key());
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("release() unregisters the object and drops its levers; twice is harmless")
    void releaseUnregisters() {
        final AnthropicAbortRegistry registry = new AnthropicAbortRegistry();
        final AnthropicCallAbort abort = registry.open();

        registry.release(abort);
        registry.release(abort);

        assertThat(registry.find(abort.key())).isEmpty();
        assertThat(abort.state()).isEqualTo(AnthropicCallAbort.State.RELEASED);
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("find() of a null or unknown key is empty: the attempt goes through untouched")
    void unknownKeysAreEmpty() {
        final AnthropicAbortRegistry registry = new AnthropicAbortRegistry();

        assertThat(registry.find(null)).isEmpty();
        assertThat(registry.find("not-registered")).isEmpty();
    }
}
