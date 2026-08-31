package at.aimon.core.agent.tool.search;

import static org.assertj.core.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ToolActivationState Tests")
class ToolActivationStateTest {

    private ToolActivationState state;

    @BeforeEach
    void setUp() {
        state = new ToolActivationState();
    }

    @Nested
    @DisplayName("Activation")
    class Activation {

        @Test
        @DisplayName("Should activate a tool")
        void testActivate() {
            state.activate("WebFetch");

            assertThat(state.isActivated("WebFetch")).isTrue();
        }

        @Test
        @DisplayName("Should activate multiple tools")
        void testActivateAll() {
            state.activateAll(List.of("WebFetch", "WebSearch"));

            assertThat(state.isActivated("WebFetch")).isTrue();
            assertThat(state.isActivated("WebSearch")).isTrue();
        }

        @Test
        @DisplayName("Should be idempotent (duplicate activate is safe)")
        void testIdempotent() {
            state.activate("WebFetch");
            state.activate("WebFetch");

            assertThat(state.getActivatedToolNames()).containsExactly("WebFetch");
        }

        @Test
        @DisplayName("Non-activated tool returns false")
        void testNotActivated() {
            assertThat(state.isActivated("NonExistent")).isFalse();
        }
    }

    @Nested
    @DisplayName("Reset")
    class Reset {

        @Test
        @DisplayName("Should clear all activations")
        void testReset() {
            state.activate("WebFetch");
            state.activate("Grep");
            state.reset();

            assertThat(state.isActivated("WebFetch")).isFalse();
            assertThat(state.isActivated("Grep")).isFalse();
            assertThat(state.getActivatedToolNames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Isolation")
    class Isolation {

        @Test
        @DisplayName("Different instances are independent")
        void testIndependence() {
            final ToolActivationState other = new ToolActivationState();

            state.activate("WebFetch");

            assertThat(other.isActivated("WebFetch")).isFalse();
        }
    }

    @Nested
    @DisplayName("Snapshot")
    class Snapshot {

        @Test
        @DisplayName("getActivatedToolNames returns immutable copy")
        void testImmutableCopy() {
            state.activate("WebFetch");

            assertThatExceptionOfType(UnsupportedOperationException.class)
                    .isThrownBy(() -> state.getActivatedToolNames().add("Hack"));
        }
    }

    @Nested
    @DisplayName("Null Safety")
    class NullSafety {

        @Test
        @DisplayName("activate() throws on null")
        void testActivateNull() {
            assertThatNullPointerException().isThrownBy(() -> state.activate(null));
        }

        @Test
        @DisplayName("activateAll() throws on null")
        void testActivateAllNull() {
            assertThatNullPointerException().isThrownBy(() -> state.activateAll(null));
        }

        @Test
        @DisplayName("isActivated() throws on null")
        void testIsActivatedNull() {
            assertThatNullPointerException().isThrownBy(() -> state.isActivated(null));
        }
    }
}
