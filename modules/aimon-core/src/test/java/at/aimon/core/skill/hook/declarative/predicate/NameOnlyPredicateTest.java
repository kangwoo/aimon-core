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

    @Test
    void compileGlob_matchesWhatTheNaivePatternMatched() {
        // The naive translation, `*` to `.*`, is the meaning of a glob here. Every glob and text over a small alphabet,
        // up to a length where each is enumerated, must get the same answer from both.
        final char[] alphabet = {'a', 'b', '*'};
        final java.util.List<String> globs = wordsUpTo(alphabet, 5);
        final java.util.List<String> texts = wordsUpTo(new char[]{'a', 'b'}, 6);
        for (String glob : globs) {
            final java.util.regex.Pattern naive = java.util.regex.Pattern
                    .compile(java.util.Arrays.stream(glob.split("\\*", -1)).map(java.util.regex.Pattern::quote)
                            .collect(java.util.stream.Collectors.joining(".*")), java.util.regex.Pattern.DOTALL);
            final java.util.regex.Pattern compiled = NameOnlyPredicate.compileGlob(glob);
            for (String text : texts) {
                assertThat(compiled.matcher(text).matches()).as("glob '%s' on '%s'", glob, text)
                        .isEqualTo(naive.matcher(text).matches());
            }
        }
    }

    @Test
    void compileGlob_doesNotBacktrackIntoAnEarlierStar() {
        // `.*a.*a.*b` tried every placement of the two a's: 8,000 characters did not finish in a hundred seconds.
        final String text = "a".repeat(65_536);
        final long start = System.nanoTime();

        assertThat(NameOnlyPredicate.compileGlob("*a*a*b").matcher(text).matches()).isFalse();
        assertThat(NameOnlyPredicate.compileGlob("*a*a*a").matcher(text).matches()).isTrue();
        assertThat(NameOnlyPredicate.compileGlob("a*\n*a").matcher("a\n" + text).matches()).isTrue();

        assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(2_000L);
    }

    private static java.util.List<String> wordsUpTo(char[] alphabet, int maxLength) {
        final java.util.List<String> words = new java.util.ArrayList<>(java.util.List.of(""));
        int from = 0;
        for (int length = 1; length <= maxLength; length++) {
            final int to = words.size();
            for (int i = from; i < to; i++) {
                for (char c : alphabet) {
                    words.add(words.get(i) + c);
                }
            }
            from = to;
        }
        return words;
    }
}
