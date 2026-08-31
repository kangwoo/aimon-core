package at.aimon.core.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.budget.ExecutionBudget;

/**
 * Verifies SESSION-02 {@link LiveSessionOptions} builder / defaults / equality semantics.
 */
@DisplayName("LiveSessionOptions (SESSION-02)")
class LiveSessionOptionsTest {

    @Test
    @DisplayName("defaults(): unlimited budget, empty locale, empty sourceAgentId")
    void defaultsAreSane() {
        final LiveSessionOptions opts = LiveSessionOptions.defaults();
        assertThat(opts.getBudget()).isEqualTo(ExecutionBudget.unlimited());
        assertThat(opts.getLocale()).isEmpty();
        assertThat(opts.getSourceAgentId()).isEmpty();
    }

    @Test
    @DisplayName("builder().build() is equivalent to defaults()")
    void builderEmptyEqualsDefaults() {
        assertThat(LiveSessionOptions.builder().build()).isEqualTo(LiveSessionOptions.defaults());
    }

    @Test
    @DisplayName("builder(): all fields round-trip")
    void builderSetsAllFields() {
        final ExecutionBudget budget = ExecutionBudget.builder().maxIterations(7).maxTokens(1000)
                .maxWallClockDuration(Duration.ofSeconds(30)).build();
        final LiveSessionOptions opts = LiveSessionOptions.builder().budget(budget).locale(Locale.ENGLISH)
                .sourceAgentId("cli-repl").build();

        assertThat(opts.getBudget()).isEqualTo(budget);
        assertThat(opts.getLocale()).contains(Locale.ENGLISH);
        assertThat(opts.getSourceAgentId()).contains("cli-repl");
    }

    @Test
    @DisplayName("builder.budget(null) falls back to unlimited at build time")
    void nullBudgetFallsBackToUnlimited() {
        final LiveSessionOptions opts = LiveSessionOptions.builder().budget(null).build();
        assertThat(opts.getBudget()).isEqualTo(ExecutionBudget.unlimited());
    }

    @Test
    @DisplayName("equals / hashCode based on value semantics")
    void equalsAndHashCode() {
        final LiveSessionOptions a = LiveSessionOptions.builder().locale(Locale.ENGLISH).sourceAgentId("id").build();
        final LiveSessionOptions b = LiveSessionOptions.builder().locale(Locale.ENGLISH).sourceAgentId("id").build();
        final LiveSessionOptions c = LiveSessionOptions.builder().locale(Locale.ENGLISH).sourceAgentId("other").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
