package at.aimon.core.llm.tagging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.LlmCallMetadata;

class LlmCallMetadataHolderTest {

    @AfterEach
    void tearDown() {
        LlmCallMetadataHolder.clear();
    }

    @Test
    @DisplayName("scope 가 없으면 current() 는 empty metadata 를 반환해야 한다")
    void currentIsEmptyWhenNoScope() {
        assertThat(LlmCallMetadataHolder.isPresent()).isFalse();
        assertThat(LlmCallMetadataHolder.current().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("push 한 metadata 는 scope 안에서 current() 로 노출되고 close 후 사라져야 한다")
    void pushExposesCurrentAndPopsOnClose() {
        final LlmCallMetadata meta = LlmCallMetadata.builder().tag("tenant", "acme").build();

        try (LlmCallMetadataHolder.Scope scope = LlmCallMetadataHolder.push(meta)) {
            assertThat(LlmCallMetadataHolder.isPresent()).isTrue();
            assertThat(LlmCallMetadataHolder.current()).isSameAs(meta);
        }

        assertThat(LlmCallMetadataHolder.isPresent()).isFalse();
        assertThat(LlmCallMetadataHolder.current().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("nested push 는 inner 가 outer 를 가리고 close 시 outer 가 복원되어야 한다")
    void nestedPushShadowsAndRestores() {
        final LlmCallMetadata outer = LlmCallMetadata.builder().tag("tenant", "acme").build();
        final LlmCallMetadata inner = LlmCallMetadata.builder().tag("tenant", "globex").build();

        try (LlmCallMetadataHolder.Scope outerScope = LlmCallMetadataHolder.push(outer)) {
            assertThat(LlmCallMetadataHolder.current()).isSameAs(outer);

            try (LlmCallMetadataHolder.Scope innerScope = LlmCallMetadataHolder.push(inner)) {
                assertThat(LlmCallMetadataHolder.current()).isSameAs(inner);
            }

            assertThat(LlmCallMetadataHolder.current()).isSameAs(outer);
        }
    }

    @Test
    @DisplayName("같은 scope 를 두 번 close 해도 idempotent 해야 한다")
    void doubleCloseIsNoOp() {
        final LlmCallMetadataHolder.Scope scope = LlmCallMetadataHolder
                .push(LlmCallMetadata.builder().tag("k", "v").build());

        scope.close();
        scope.close();

        assertThat(LlmCallMetadataHolder.isPresent()).isFalse();
    }

    @Test
    @DisplayName("scope 를 잘못된 순서로 close 하면 IllegalStateException 이 발생해야 한다")
    void outOfOrderCloseFails() {
        final LlmCallMetadataHolder.Scope outer = LlmCallMetadataHolder
                .push(LlmCallMetadata.builder().tag("o", "1").build());
        final LlmCallMetadataHolder.Scope inner = LlmCallMetadataHolder
                .push(LlmCallMetadata.builder().tag("i", "1").build());

        try {
            assertThatThrownBy(outer::close).isInstanceOf(IllegalStateException.class);
        } finally {
            inner.close();
            outer.close();
        }
    }

    @Test
    @DisplayName("push(null) 은 NullPointerException 을 던져야 한다")
    void pushRejectsNull() {
        assertThatThrownBy(() -> LlmCallMetadataHolder.push(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("다른 thread 는 독립적인 stack 을 가져야 한다")
    void perThreadIsolation() throws Exception {
        final LlmCallMetadata mainMeta = LlmCallMetadata.builder().tag("thread", "main").build();

        try (LlmCallMetadataHolder.Scope ignored = LlmCallMetadataHolder.push(mainMeta)) {
            final boolean[] otherThreadSawEmpty = {false};
            final Thread other = new Thread(() -> {
                otherThreadSawEmpty[0] = LlmCallMetadataHolder.current().isEmpty();
            });
            other.start();
            other.join();

            assertThat(otherThreadSawEmpty[0]).isTrue();
            assertThat(LlmCallMetadataHolder.current()).isSameAs(mainMeta);
        }
    }
}
