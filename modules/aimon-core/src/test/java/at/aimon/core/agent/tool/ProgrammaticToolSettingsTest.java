package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ProgrammaticToolSettings")
class ProgrammaticToolSettingsTest {

    private static ProgrammaticToolSettings tools(String... names) {
        return ProgrammaticToolSettings.builder().tools(List.of(names)).build();
    }

    private static ProgrammaticToolSettings withEngine(ProgrammaticEngineChoice engine, String... names) {
        return ProgrammaticToolSettings.builder().tools(List.of(names)).engine(engine).build();
    }

    private static ProgrammaticToolSettings withScope(ShellEngineToolScope scope, String... names) {
        return ProgrammaticToolSettings.builder().tools(List.of(names)).shellEngineToolScope(scope).build();
    }

    @Nested
    @DisplayName("narrowedBy")
    class NarrowedBy {

        @Test
        @DisplayName("tools are the intersection, in the narrowed settings' own order")
        void intersectionKeepsOwnOrder() {
            final ProgrammaticToolSettings own = tools("Grep", "Read", "Glob");
            final ProgrammaticToolSettings ceiling = tools("Read", "Write", "Grep");

            assertThat(own.narrowedBy(ceiling).getTools()).containsExactly("Grep", "Read");
        }

        @Test
        @DisplayName("an empty intersection is none()")
        void emptyIntersectionIsNone() {
            assertThat(tools("Read").narrowedBy(tools("Grep"))).isEqualTo(ProgrammaticToolSettings.none())
                    .isSameAs(ProgrammaticToolSettings.none());
        }

        @Test
        @DisplayName("a none() ceiling leaves nothing")
        void noneCeiling() {
            assertThat(tools("Read", "Grep").narrowedBy(ProgrammaticToolSettings.none()).isEnabled()).isFalse();
        }

        @Test
        @DisplayName("none() own settings stay none() under any ceiling")
        void noneOwn() {
            assertThat(ProgrammaticToolSettings.none().narrowedBy(tools("Read", "Grep")))
                    .isEqualTo(ProgrammaticToolSettings.none());
        }

        @Test
        @DisplayName("codeOnly is the union of both sides, kept to the intersected tools")
        void codeOnlyUnionWithinTools() {
            final ProgrammaticToolSettings own = ProgrammaticToolSettings.builder().tools(List.of("Read", "Grep", "Q"))
                    .codeOnly(List.of("Q")).build();
            final ProgrammaticToolSettings ceiling = ProgrammaticToolSettings.builder()
                    .tools(List.of("Read", "Grep", "Glob")).codeOnly(List.of("Grep", "Glob")).build();

            final ProgrammaticToolSettings narrowed = own.narrowedBy(ceiling);

            assertThat(narrowed.getTools()).containsExactly("Read", "Grep");
            // Q dropped with the tool (not in the ceiling), Glob dropped (not in own), Grep withheld by the ceiling
            assertThat(narrowed.getCodeOnly()).containsExactly("Grep");
        }

        @ParameterizedTest(name = "{0} narrowed by {1} -> {2}")
        @CsvSource({"AUTO,AUTO,AUTO", "AUTO,JAVASCRIPT,JAVASCRIPT", "JAVASCRIPT,AUTO,JAVASCRIPT",
                "JAVASCRIPT,JAVASCRIPT,JAVASCRIPT", "PYTHON,PYTHON,PYTHON", "AUTO,PYTHON,PYTHON"})
        @DisplayName("engine: AUTO defers, equal choices stay")
        void engineTable(ProgrammaticEngineChoice own, ProgrammaticEngineChoice ceiling,
                ProgrammaticEngineChoice expected) {
            final ProgrammaticToolSettings narrowed = withEngine(own, "Read").narrowedBy(withEngine(ceiling, "Read"));

            assertThat(narrowed.isEnabled()).isTrue();
            assertThat(narrowed.getEngine()).isEqualTo(expected);
        }

        @Test
        @DisplayName("engine: conflicting explicit choices are none(), however many tools they share")
        void engineConflictIsNone() {
            assertThat(withEngine(ProgrammaticEngineChoice.JAVASCRIPT, "Read", "Grep")
                    .narrowedBy(withEngine(ProgrammaticEngineChoice.PYTHON, "Read", "Grep")))
                    .isEqualTo(ProgrammaticToolSettings.none());
            assertThat(withEngine(ProgrammaticEngineChoice.PYTHON, "Read")
                    .narrowedBy(withEngine(ProgrammaticEngineChoice.JAVASCRIPT, "Read")))
                    .isEqualTo(ProgrammaticToolSettings.none());
        }

        @Test
        @DisplayName("shell-engine-tool-scope: the present one when only one side sets it")
        void scopeOneSide() {
            assertThat(withScope(ShellEngineToolScope.HOST, "Read").narrowedBy(tools("Read")).getShellEngineToolScope())
                    .hasValue(ShellEngineToolScope.HOST);
            assertThat(tools("Read").narrowedBy(withScope(ShellEngineToolScope.ENVIRONMENT, "Read"))
                    .getShellEngineToolScope()).hasValue(ShellEngineToolScope.ENVIRONMENT);
            assertThat(tools("Read").narrowedBy(tools("Read")).getShellEngineToolScope()).isEmpty();
        }

        @Test
        @DisplayName("shell-engine-tool-scope: the narrower when both sides set it")
        void scopeBothSides() {
            assertThat(withScope(ShellEngineToolScope.UNRESTRICTED, "Read")
                    .narrowedBy(withScope(ShellEngineToolScope.HOST, "Read")).getShellEngineToolScope())
                    .hasValue(ShellEngineToolScope.HOST);
            assertThat(withScope(ShellEngineToolScope.ENVIRONMENT, "Read")
                    .narrowedBy(withScope(ShellEngineToolScope.UNRESTRICTED, "Read")).getShellEngineToolScope())
                    .hasValue(ShellEngineToolScope.ENVIRONMENT);
        }

        @Test
        @DisplayName("is idempotent: narrowing twice by the same ceiling equals narrowing once")
        void idempotent() {
            final ProgrammaticToolSettings own = ProgrammaticToolSettings.builder().tools(List.of("Read", "Grep", "Q"))
                    .codeOnly(List.of("Q")).shellEngineToolScope(ShellEngineToolScope.UNRESTRICTED).build();
            final ProgrammaticToolSettings ceiling = ProgrammaticToolSettings.builder().tools(List.of("Q", "Read"))
                    .codeOnly(List.of("Read")).engine(ProgrammaticEngineChoice.PYTHON)
                    .shellEngineToolScope(ShellEngineToolScope.HOST).build();

            final ProgrammaticToolSettings once = own.narrowedBy(ceiling);

            assertThat(once.narrowedBy(ceiling)).isEqualTo(once);
            assertThat(once.getTools()).containsExactly("Read", "Q");
            assertThat(once.getCodeOnly()).containsExactlyInAnyOrder("Read", "Q");
            assertThat(once.getEngine()).isEqualTo(ProgrammaticEngineChoice.PYTHON);
            assertThat(once.getShellEngineToolScope()).hasValue(ShellEngineToolScope.HOST);
        }

        @Test
        @DisplayName("refuses a null ceiling")
        void nullCeiling() {
            assertThatThrownBy(() -> tools("Read").narrowedBy(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("hasEngineConflict")
    class HasEngineConflict {

        @ParameterizedTest(name = "{0} vs {1} -> {2}")
        @CsvSource({"AUTO,AUTO,false", "AUTO,JAVASCRIPT,false", "PYTHON,AUTO,false", "JAVASCRIPT,JAVASCRIPT,false",
                "JAVASCRIPT,PYTHON,true", "PYTHON,JAVASCRIPT,true"})
        @DisplayName("only two different explicit engines conflict")
        void table(ProgrammaticEngineChoice a, ProgrammaticEngineChoice b, boolean expected) {
            assertThat(withEngine(a, "Read").hasEngineConflict(withEngine(b, "Grep"))).isEqualTo(expected);
        }

        @Test
        @DisplayName("refuses null")
        void nullOther() {
            assertThatThrownBy(() -> tools("Read").hasEngineConflict(null)).isInstanceOf(NullPointerException.class);
        }
    }
}
