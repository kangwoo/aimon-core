package at.aimon.core.agent.tool.search;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolLoadingMode;

@DisplayName("SearchableTool Tests")
class SearchableToolTest {

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethods {

        @Test
        @DisplayName("eager() creates EAGER SearchableTool")
        void testEager() {
            final SearchableTool st = SearchableTool.eager(MockTools.create("Read", "Read files"));

            assertThat(st.getLoadingMode()).isEqualTo(ToolLoadingMode.EAGER);
            assertThat(st.isEager()).isTrue();
            assertThat(st.isDeferred()).isFalse();
            assertThat(st.getName()).isEqualTo("Read");
        }

        @Test
        @DisplayName("deferred() creates DEFERRED SearchableTool")
        void testDeferred() {
            final SearchableTool st = SearchableTool.deferred(MockTools.create("WebFetch", "Fetch web content"));

            assertThat(st.getLoadingMode()).isEqualTo(ToolLoadingMode.DEFERRED);
            assertThat(st.isDeferred()).isTrue();
            assertThat(st.isEager()).isFalse();
            assertThat(st.getName()).isEqualTo("WebFetch");
        }
    }

    @Nested
    @DisplayName("Null Safety")
    class NullSafety {

        @Test
        @DisplayName("eager() throws on null tool")
        void testEagerNull() {
            assertThatNullPointerException().isThrownBy(() -> SearchableTool.eager(null));
        }

        @Test
        @DisplayName("deferred() throws on null tool")
        void testDeferredNull() {
            assertThatNullPointerException().isThrownBy(() -> SearchableTool.deferred(null));
        }
    }

    @Nested
    @DisplayName("Immutability")
    class Immutability {

        @Test
        @DisplayName("getTool() returns the same instance")
        void testGetTool() {
            final var tool = MockTools.create("Read", "Read files");
            final SearchableTool st = SearchableTool.eager(tool);

            assertThat(st.getTool()).isSameAs(tool);
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        @DisplayName("Same name and mode are equal")
        void testEqual() {
            final SearchableTool a = SearchableTool.eager(MockTools.create("Read", "desc1"));
            final SearchableTool b = SearchableTool.eager(MockTools.create("Read", "desc2"));

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Different modes are not equal")
        void testDifferentMode() {
            final SearchableTool eager = SearchableTool.eager(MockTools.create("Read", "desc"));
            final SearchableTool deferred = SearchableTool.deferred(MockTools.create("Read", "desc"));

            assertThat(eager).isNotEqualTo(deferred);
        }
    }
}
