package at.aimon.core.agent.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Contract of the allow-list conjunction, including the empty-means-unrestricted trap it exists to avoid. */
@DisplayName("AllowedTools.intersect")
class AllowedToolsTest {

    private static List<AllowedTool> list(String... specs) {
        return java.util.Arrays.stream(specs).map(AllowedTool::parse).toList();
    }

    private static List<String> spellingsOf(Optional<List<AllowedTool>> result) {
        return result.orElseThrow().stream().map(AllowedTool::toString).toList();
    }

    @Test
    @DisplayName("an empty side restricts nothing, so the other governs")
    void anEmptySideGovernsNothing() {
        assertThat(spellingsOf(AllowedTools.intersect(List.of(), list("Read", "Grep")))).containsExactly("Read",
                "Grep");
        assertThat(spellingsOf(AllowedTools.intersect(list("Read"), List.of()))).containsExactly("Read");
        assertThat(AllowedTools.intersect(List.of(), List.of())).contains(List.of());
    }

    @Test
    @DisplayName("names only one side mentions are dropped")
    void namesOnlyOneSideMentionsAreDropped() {
        assertThat(spellingsOf(AllowedTools.intersect(list("Read", "Write"), list("Read", "Grep"))))
                .containsExactly("Read");
    }

    @Test
    @DisplayName("a bare name on one side yields the other side's entries, pattern and all")
    void aBareNameYieldsTheOtherSidesEntries() {
        // The skill says "Bash is fine"; the subagent says "only git". The conjunction is "only git", exactly.
        assertThat(spellingsOf(AllowedTools.intersect(list("Bash"), list("Bash(git:*)"))))
                .containsExactly("Bash(git:*)");
        assertThat(spellingsOf(AllowedTools.intersect(list("Bash(git:*)"), list("Bash"))))
                .containsExactly("Bash(git:*)");
    }

    @Test
    @DisplayName("an identical pattern on both sides survives; a merely different one is dropped")
    void identicalPatternsSurviveAndDifferentOnesAreDropped() {
        assertThat(spellingsOf(AllowedTools.intersect(list("Bash(git:*)", "Read"), list("Bash(git:*)", "Read"))))
                .containsExactly("Bash(git:*)", "Read");

        // git:* and npm:* overlap on nothing, and even a pair that did overlap is not computable from two globs —
        // dropping is the only choice that cannot grant what one side refused.
        assertThat(AllowedTools.intersect(list("Bash(git:*)"), list("Bash(npm:*)"))).isEmpty();
    }

    @Test
    @DisplayName("a bare name on one side does not resurrect a name the other never mentions")
    void aBareNameDoesNotWiden() {
        assertThat(spellingsOf(AllowedTools.intersect(list("Read", "Bash"), list("Read")))).containsExactly("Read");
    }

    /**
     * The reason this returns {@link Optional} rather than a list. Every validator in this package treats an empty
     * allow-list as "no restrictions", so handing back an empty list for a conjunction that admits nothing would turn
     * the strictest possible pairing into the loosest.
     */
    @Test
    @DisplayName("no overlap returns empty rather than an empty list, which would read as unrestricted")
    void noOverlapIsNotAnEmptyList() {
        final Optional<List<AllowedTool>> result = AllowedTools.intersect(list("Read"), list("Write"));

        assertThat(result).isEmpty();

        // Guard the inversion in the terms that make it dangerous: had this returned List.of(), the validator would
        // have permitted the very tool neither side allows.
        final ToolPermissionValidator validator = new DefaultToolPermissionValidator();
        assertThat(validator.validateByName("Anything", List.of()).isAllowed())
                .as("an empty allow-list is unrestricted, which is why empty must not be returned as a list").isTrue();
    }

    @Test
    @DisplayName("the result is deduplicated and null arguments are rejected")
    void deduplicatesAndRejectsNulls() {
        assertThat(spellingsOf(AllowedTools.intersect(list("Read", "Read"), list("Read")))).containsExactly("Read");

        assertThatNullPointerException().isThrownBy(() -> AllowedTools.intersect(null, List.of()));
        assertThatNullPointerException().isThrownBy(() -> AllowedTools.intersect(List.of(), null));
    }
}
