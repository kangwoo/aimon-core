package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("SideEffectLevel Tests")
class SideEffectLevelTest {

    @Nested
    @DisplayName("permits(): ceiling ordering")
    class Permits {

        @Test
        @DisplayName("READ_ONLY admits only READ_ONLY")
        void readOnlyCeiling() {
            assertThat(SideEffectLevel.READ_ONLY.permits(SideEffectLevel.READ_ONLY)).isTrue();
            assertThat(SideEffectLevel.READ_ONLY.permits(SideEffectLevel.MUTATING)).isFalse();
        }

        @Test
        @DisplayName("the scale has exactly two rungs, so no ceiling can exempt only some writes")
        void twoRungsOnly() {
            assertThat(SideEffectLevel.values()).containsExactly(SideEffectLevel.READ_ONLY, SideEffectLevel.MUTATING);
        }

        @ParameterizedTest
        @EnumSource(SideEffectLevel.class)
        @DisplayName("MUTATING is the unrestricted ceiling — it admits everything")
        void mutatingCeilingAdmitsEverything(SideEffectLevel declared) {
            assertThat(SideEffectLevel.MUTATING.permits(declared)).isTrue();
        }

        @ParameterizedTest
        @EnumSource(SideEffectLevel.class)
        @DisplayName("every ceiling admits its own level")
        void reflexive(SideEffectLevel level) {
            assertThat(level.permits(level)).isTrue();
        }

        @Test
        @DisplayName("rejects a null argument rather than defaulting to permitted")
        void nullRejected() {
            assertThatThrownBy(() -> SideEffectLevel.MUTATING.permits(null)).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("Required level");
        }
    }

    @Nested
    @DisplayName("Tool declaration defaults")
    class ToolDefaults {

        /** A tool that overrides nothing beyond execute(), i.e. the state every existing tool is in. */
        private Tool undeclaredTool() {
            return new AbstractTool("Undeclared", "declares nothing", Map.of("type", "object")) {
                @Override
                public ToolResult execute(ToolInput input, ToolContext context) {
                    return ToolResult.success("ok");
                }
            };
        }

        private Tool declaring(SideEffectLevel level) {
            return new AbstractTool("Declared", "declares " + level, Map.of("type", "object")) {
                @Override
                public ToolResult execute(ToolInput input, ToolContext context) {
                    return ToolResult.success("ok");
                }

                @Override
                public SideEffectLevel getSideEffectLevel() {
                    return level;
                }
            };
        }

        @Test
        @DisplayName("an unaudited tool defaults to MUTATING, the conservative end")
        void defaultIsMutating() {
            assertThat(undeclaredTool().getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        }

        @Test
        @DisplayName("isReadOnly() is derived from the declaration, so the two cannot disagree")
        void isReadOnlyDerives() {
            assertThat(undeclaredTool().isReadOnly()).isFalse();
            assertThat(declaring(SideEffectLevel.READ_ONLY).isReadOnly()).isTrue();
            assertThat(declaring(SideEffectLevel.MUTATING).isReadOnly()).isFalse();
        }
    }
}
