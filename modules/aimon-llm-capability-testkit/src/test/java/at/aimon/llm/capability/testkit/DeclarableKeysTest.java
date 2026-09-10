package at.aimon.llm.capability.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.ParameterizedType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.llm.capability.ModelCapabilityDeclaration;
import at.aimon.core.llm.capability.ThinkingDialect;

@DisplayName("DeclarableKeys - the key list is read off the builder, never kept by hand")
class DeclarableKeysTest {

    @Nested
    @DisplayName("discovery")
    class Discovery {

        @Test
        @DisplayName("finds the builder's setters, sorted and unique — including both keys #69 added to the surfaces")
        void findsTheBuildersSetters() {
            // Not an exhaustive list, deliberately: that would be a hand-kept key list, which a new key breaks here.
            assertThat(DeclarableKeys.names()).isSorted().doesNotHaveDuplicates().contains("thinkingDialect",
                    "supportsReasoningSummary");
        }

        @Test
        @DisplayName("refuses a builder with no declarable key, rather than handing the contract zero cases")
        void refusesAnEmptyKeyList() {
            assertThatThrownBy(() -> DeclarableKeys.namesOf(NoSetters.class)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(NoSetters.class.getName()).hasMessageContaining("zero keys");
        }

        @Test
        @DisplayName("refuses two setters sharing a name, rather than picking one")
        void refusesOverloadedSetters() {
            assertThatThrownBy(() -> DeclarableKeys.namesOf(Overloaded.class)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("[level]");
        }

        @Test
        @DisplayName("reports a setter's generic parameter type, not its raw class")
        void reportsTheGenericParameterType() {
            assertThat(DeclarableKeys.parameterTypeOf("acceptedReasoningEfforts")).isInstanceOf(ParameterizedType.class)
                    .hasToString("java.util.Set<at.aimon.core.llm.ReasoningEffort>");
        }

        @Test
        @DisplayName("names a key the builder does not declare")
        void namesAnUnknownKey() {
            assertThatThrownBy(() -> DeclarableKeys.setterFor("supportsTelepathy"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("`supportsTelepathy`");
        }
    }

    @Nested
    @DisplayName("the expected declaration")
    class ExpectedDeclaration {

        @Test
        @DisplayName("is the builder with exactly that one setter called")
        void isTheBuilderWithThatOneSetterCalled() {
            assertThat(DeclarableKeys.expectedDeclaration("thinkingDialect", ThinkingDialect.EITHER))
                    .isEqualTo(ModelCapabilityDeclaration.builder().thinkingDialect(ThinkingDialect.EITHER).build());
        }
    }

    /** A builder whose shape changed so that nothing matches the filter. */
    static final class NoSetters {
        public void unrelated(String value) {
        }
    }

    /** A builder with an ambiguous key. */
    static final class Overloaded {
        public Overloaded level(Integer value) {
            return this;
        }

        public Overloaded level(String value) {
            return this;
        }

        public Overloaded other(Boolean value) {
            return this;
        }
    }
}
