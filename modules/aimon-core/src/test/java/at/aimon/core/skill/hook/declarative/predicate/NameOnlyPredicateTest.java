package at.aimon.core.skill.hook.declarative.predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.ToolInput;

class NameOnlyPredicateTest {

    private static final ToolInput EMPTY = ToolInput.of();

    @Test
    void of_nullOrBlankOrStar_returnsAnySingleton() {
        assertThat(NameOnlyPredicate.of(null)).isSameAs(NameOnlyPredicate.ANY);
        assertThat(NameOnlyPredicate.of("")).isSameAs(NameOnlyPredicate.ANY);
        assertThat(NameOnlyPredicate.of("   ")).isSameAs(NameOnlyPredicate.ANY);
        assertThat(NameOnlyPredicate.of("*")).isSameAs(NameOnlyPredicate.ANY);
    }

    @Test
    void any_matchesEveryToolName() {
        assertThat(NameOnlyPredicate.ANY.test("Bash", EMPTY)).isTrue();
        assertThat(NameOnlyPredicate.ANY.test("Read", EMPTY)).isTrue();
        assertThat(NameOnlyPredicate.ANY.test("anything-at-all", EMPTY)).isTrue();
    }

    @Test
    void exact_matchesOnlyConfiguredName() {
        NameOnlyPredicate p = NameOnlyPredicate.of("Bash");

        assertThat(p.test("Bash", EMPTY)).isTrue();
        assertThat(p.test("bash", EMPTY)).isFalse();
        assertThat(p.test("Read", EMPTY)).isFalse();
    }

    @Test
    void test_nullToolName_throws() {
        assertThatThrownBy(() -> NameOnlyPredicate.ANY.test(null, EMPTY)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NameOnlyPredicate.of("Bash").test(null, EMPTY))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getPattern_returnsStarForAnyAndLiteralOtherwise() {
        assertThat(NameOnlyPredicate.ANY.getPattern()).isEqualTo("*");
        assertThat(NameOnlyPredicate.of("Read").getPattern()).isEqualTo("Read");
    }

    @Test
    void equalsAndHashCode_basedOnPattern() {
        assertThat(NameOnlyPredicate.of("Bash")).isEqualTo(NameOnlyPredicate.of("Bash"))
                .hasSameHashCodeAs(NameOnlyPredicate.of("Bash"));
        assertThat(NameOnlyPredicate.of("Bash")).isNotEqualTo(NameOnlyPredicate.of("Read"));
        assertThat(NameOnlyPredicate.ANY).isEqualTo(NameOnlyPredicate.of("*"));
    }

    @Test
    void glob_prefixWildcard_matchesSuffix() {
        NameOnlyPredicate p = NameOnlyPredicate.of("*Tool");

        assertThat(p.test("Tool", EMPTY)).isTrue();
        assertThat(p.test("BashTool", EMPTY)).isTrue();
        assertThat(p.test("ReadTool", EMPTY)).isTrue();
        assertThat(p.test("Bash", EMPTY)).isFalse();
        assertThat(p.test("Toolish", EMPTY)).isFalse();
    }

    @Test
    void glob_suffixWildcard_matchesPrefix() {
        NameOnlyPredicate p = NameOnlyPredicate.of("Read*");

        assertThat(p.test("Read", EMPTY)).isTrue();
        assertThat(p.test("ReadTool", EMPTY)).isTrue();
        assertThat(p.test("Readme", EMPTY)).isTrue();
        assertThat(p.test("Bash", EMPTY)).isFalse();
        assertThat(p.test("XRead", EMPTY)).isFalse();
    }

    @Test
    void glob_surroundingWildcards_matchesContains() {
        NameOnlyPredicate p = NameOnlyPredicate.of("*Tool*");

        assertThat(p.test("Tool", EMPTY)).isTrue();
        assertThat(p.test("BashTool", EMPTY)).isTrue();
        assertThat(p.test("ToolX", EMPTY)).isTrue();
        assertThat(p.test("XYZToolABC", EMPTY)).isTrue();
        assertThat(p.test("Bash", EMPTY)).isFalse();
    }

    @Test
    void glob_multipleWildcards_matchesEachSegment() {
        NameOnlyPredicate p = NameOnlyPredicate.of("R*d*Tool");

        assertThat(p.test("ReadTool", EMPTY)).isTrue();
        assertThat(p.test("RedTool", EMPTY)).isTrue();
        assertThat(p.test("RxxxdyyyTool", EMPTY)).isTrue();
        assertThat(p.test("ReadTools", EMPTY)).isFalse();
        assertThat(p.test("ReaTool", EMPTY)).isFalse();
        assertThat(p.test("XReadTool", EMPTY)).isFalse();
    }

    @Test
    void glob_regexMetacharacters_treatedAsLiterals() {
        NameOnlyPredicate p = NameOnlyPredicate.of("Tool.X*");

        assertThat(p.test("Tool.X", EMPTY)).isTrue();
        assertThat(p.test("Tool.Xyz", EMPTY)).isTrue();
        assertThat(p.test("ToolAX", EMPTY)).isFalse();
    }
}
