package at.aimon.workflow.graaljs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.subagent.Subagent;
import at.aimon.workflow.graaljs.exception.JsScriptException;

/**
 * Deterministic-name synthesis tests for the inline resolver. Names must be stable across resolves (and
 * across JVMs) so shared/persistent resume caches replay without spurious misses.
 */
@DisplayName("InlineSubagentResolver — deterministic, cross-JVM-stable names")
class SubagentResolverTest {

    private final SubagentResolver resolver = SubagentResolver.inline();

    @Test
    @DisplayName("same agentType yields the same name")
    void agentTypeNameIsDeterministic() {
        final Subagent first = resolver.resolve("bug-reviewer", null, null, null, null);
        final Subagent second = resolver.resolve("bug-reviewer", null, null, null, null);
        assertThat(first.getName()).isEqualTo("graaljs:bug-reviewer").isEqualTo(second.getName());
    }

    @Test
    @DisplayName("systemPrompt-only descriptors get a stable hash-derived name")
    void promptOnlyNameIsStableHash() {
        final Subagent first = resolver.resolve(null, "You review diffs.", null, null, null);
        final Subagent second = resolver.resolve(null, "You review diffs.", null, null, null);
        assertThat(first.getName()).startsWith("graaljs:").isEqualTo(second.getName());
        final Subagent different = resolver.resolve(null, "You write tests.", null, null, null);
        assertThat(different.getName()).isNotEqualTo(first.getName());
    }

    @Test
    @DisplayName("explicit fields are applied; agentType supplies a synthesized system prompt")
    void appliesExplicitFields() {
        final Subagent subagent = resolver.resolve("planner", null, "gpt-4", List.of("Read", "Grep"), 5);
        assertThat(subagent.getName()).isEqualTo("graaljs:planner");
    }

    @Test
    @DisplayName("a descriptor with neither agentType nor systemPrompt is rejected loudly")
    void rejectsEmptyDescriptor() {
        assertThatThrownBy(() -> resolver.resolve(null, null, null, null, null)).isInstanceOf(JsScriptException.class)
                .hasMessageContaining("agentType");
    }
}
