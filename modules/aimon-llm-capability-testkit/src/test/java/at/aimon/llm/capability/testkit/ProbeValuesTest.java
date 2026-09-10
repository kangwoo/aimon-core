package at.aimon.llm.capability.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.capability.ThinkingDialect;

@DisplayName("ProbeValues - two distinct values per key, from the setter's own type")
class ProbeValuesTest {

    private static final ReasoningEffort FIRST_RUNG = ReasoningEffort.values()[0];
    private static final ReasoningEffort SECOND_RUNG = ReasoningEffort.values()[1];

    @Nested
    @DisplayName("the table")
    class Table {

        @Test
        @DisplayName("a Boolean is probed with true, then false")
        void booleans() {
            assertThat(ProbeValues.pairFor("flag", Boolean.class)).containsExactly(true, false);
        }

        @Test
        @DisplayName("an enum is probed with its first two constants")
        void enums() {
            assertThat(ProbeValues.pairFor("dialect", ThinkingDialect.class))
                    .containsExactly(ThinkingDialect.values()[0], ThinkingDialect.values()[1]);
        }

        @Test
        @DisplayName("a Set of an enum is probed with one constant, then two — never an empty ladder")
        void setsOfAnEnum() throws NoSuchFieldException {
            assertThat(ProbeValues.pairFor("ladder", genericTypeOf("setOfRungs"))).containsExactly(Set.of(FIRST_RUNG),
                    Set.of(FIRST_RUNG, SECOND_RUNG));
        }

        @Test
        @DisplayName("a List of an enum is probed with lists, which a set would not equal")
        void listsOfAnEnum() throws NoSuchFieldException {
            assertThat(ProbeValues.pairFor("ladder", genericTypeOf("listOfRungs"))).containsExactly(List.of(FIRST_RUNG),
                    List.of(FIRST_RUNG, SECOND_RUNG));
        }

        @Test
        @DisplayName("a String is probed with two different strings")
        void strings() {
            assertThat(ProbeValues.pairFor("name", String.class)).hasSize(2).doesNotHaveDuplicates()
                    .allSatisfy(value -> assertThat(value).isInstanceOf(String.class));
        }

        @Test
        @DisplayName("a whole number is probed with 1, then 2, in the setter's own width")
        void wholeNumbers() {
            assertThat(ProbeValues.pairFor("count", int.class)).containsExactly(1, 2);
            assertThat(ProbeValues.pairFor("size", Long.class)).containsExactly(1L, 2L);
        }
    }

    @Nested
    @DisplayName("a type the table cannot probe fails, and is never skipped")
    class LoudFailures {

        @Test
        @DisplayName("an unsupported type names the key, the type and where to add a row")
        void anUnsupportedTypeFails() {
            assertThatThrownBy(() -> ProbeValues.pairFor("timeout", Duration.class)).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("`timeout`").hasMessageContaining("java.time.Duration")
                    .hasMessageContaining("ProbeValues.pairFor");
        }

        @Test
        @DisplayName("an enum with one constant fails, saying why two values are needed")
        void anEnumWithOneConstantFails() {
            assertThatThrownBy(() -> ProbeValues.pairFor("lonely", Lonely.class)).isInstanceOf(AssertionError.class)
                    .hasMessageContaining("`lonely`").hasMessageContaining("hard-coded");
        }
    }

    @Nested
    @DisplayName("today's builder")
    class TodaysBuilder {

        @Test
        @DisplayName("every declarable key gets two distinct values of its setter's type")
        void everyDeclarableKeyGetsAPair() {
            for (final String key : DeclarableKeys.names()) {
                final Class<?> parameterType = SurfaceWriter
                        .boxed(DeclarableKeys.setterFor(key).getParameterTypes()[0]);
                assertThat(ProbeValues.distinctPairFor(key)).as(key).hasSize(2).doesNotHaveDuplicates()
                        .allSatisfy(value -> assertThat(value).isInstanceOf(parameterType));
            }
        }
    }

    private static Type genericTypeOf(String field) throws NoSuchFieldException {
        return Shapes.class.getDeclaredField(field).getGenericType();
    }

    /** Generic types no class literal can spell. */
    @SuppressWarnings("unused")
    static final class Shapes {
        private Set<ReasoningEffort> setOfRungs;
        private List<ReasoningEffort> listOfRungs;
    }

    /** An enum with no second value to probe with. */
    enum Lonely {
        ONLY
    }
}
