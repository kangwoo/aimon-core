package at.aimon.core.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultContextAssembler Tests")
class DefaultContextAssemblerTest {

    private static final ContextAssemblyRequest REQUEST = ContextAssemblyRequest.builder().build();

    @Nested
    @DisplayName("Aggregation")
    class Aggregation {

        @Test
        @DisplayName("concatenates provider blocks in registration order")
        void concatenatesInOrder() {
            ContextProvider first = r -> List.of(ContextBlock.system("a", "1"));
            ContextProvider second = r -> List.of(ContextBlock.system("b", "2"), ContextBlock.system("c", "3"));

            List<ContextBlock> blocks = DefaultContextAssembler.of(first, second).assemble(REQUEST);

            assertThat(blocks).extracting(ContextBlock::getKey).containsExactly("a", "b", "c");
        }

        @Test
        @DisplayName("empty when no providers")
        void emptyWhenNoProviders() {
            assertThat(DefaultContextAssembler.of().assemble(REQUEST)).isEmpty();
        }

        @Test
        @DisplayName("returned list is immutable")
        void immutableResult() {
            List<ContextBlock> blocks = DefaultContextAssembler.of(r -> List.of(ContextBlock.system("a", "1")))
                    .assemble(REQUEST);

            assertThatThrownBy(() -> blocks.add(ContextBlock.system("x", "y")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Defensiveness")
    class Defensiveness {

        @Test
        @DisplayName("a throwing provider is skipped, others still contribute")
        void skipsThrowingProvider() {
            ContextProvider bad = r -> {
                throw new IllegalStateException("boom");
            };
            ContextProvider good = r -> List.of(ContextBlock.system("ok", "1"));

            List<ContextBlock> blocks = DefaultContextAssembler.of(bad, good).assemble(REQUEST);

            assertThat(blocks).extracting(ContextBlock::getKey).containsExactly("ok");
        }

        @Test
        @DisplayName("a provider returning null is skipped")
        void skipsNullReturningProvider() {
            ContextProvider nullProvider = r -> null;
            ContextProvider good = r -> List.of(ContextBlock.system("ok", "1"));

            List<ContextBlock> blocks = DefaultContextAssembler.of(nullProvider, good).assemble(REQUEST);

            assertThat(blocks).extracting(ContextBlock::getKey).containsExactly("ok");
        }

        @Test
        @DisplayName("null blocks within a provider's list are dropped")
        void dropsNullBlocks() {
            ContextProvider provider = r -> Arrays.asList(ContextBlock.system("a", "1"), null,
                    ContextBlock.system("b", "2"));

            List<ContextBlock> blocks = DefaultContextAssembler.of(provider).assemble(REQUEST);

            assertThat(blocks).extracting(ContextBlock::getKey).containsExactly("a", "b");
        }
    }

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("null provider list rejected")
        void rejectsNullList() {
            assertThatThrownBy(() -> new DefaultContextAssembler(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("null provider element rejected")
        void rejectsNullElement() {
            assertThatThrownBy(() -> new DefaultContextAssembler(Arrays.asList((ContextProvider) null)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("assemble rejects null request")
        void rejectsNullRequest() {
            assertThatThrownBy(() -> DefaultContextAssembler.of().assemble(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("NOOP assembler")
    class Noop {

        @Test
        @DisplayName("NOOP assembles nothing")
        void noopEmpty() {
            assertThat(ContextAssembler.NOOP.assemble(REQUEST)).isEmpty();
        }
    }
}
