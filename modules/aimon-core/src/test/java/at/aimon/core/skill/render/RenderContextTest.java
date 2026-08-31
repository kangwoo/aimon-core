package at.aimon.core.skill.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;

/** Unit tests for {@link RenderContext}. */
class RenderContextTest {

    @Test
    void empty_AllFieldsAreEmpty() {
        final RenderContext ctx = RenderContext.empty();

        assertThat(ctx.getAgentRuntimeId()).isEmpty();
        assertThat(ctx.getSessionId()).isEmpty();
        assertThat(ctx.getExecutionId()).isEmpty();
        assertThat(ctx.getPrincipal()).isEmpty();
        assertThat(ctx.getSkillBaseDir()).isEmpty();
        assertThat(ctx.getAdditionalVariables()).isEmpty();
    }

    @Test
    void empty_ReturnsSharedInstance() {
        assertThat(RenderContext.empty()).isSameAs(RenderContext.empty());
    }

    @Test
    void builder_AllFieldsSet_GettersReturnValues() {
        final Principal principal = Principal.user("user-1", "Alice");
        final RenderContext ctx = RenderContext.builder().agentRuntimeId("agent:ops-bot").sessionId("sess-42")
                .executionId("subagent:reviewer:e1").principal(principal).skillBaseDir("/skills/example").build();

        assertThat(ctx.getAgentRuntimeId()).contains("agent:ops-bot");
        assertThat(ctx.getSessionId()).contains("sess-42");
        assertThat(ctx.getExecutionId()).contains("subagent:reviewer:e1");
        assertThat(ctx.getPrincipal()).contains(principal);
        assertThat(ctx.getSkillBaseDir()).contains("/skills/example");
    }

    @Test
    void builder_PartialFieldsSet_UnsetFieldsAreEmpty() {
        final RenderContext ctx = RenderContext.builder().agentRuntimeId("agent:ops-bot").build();

        assertThat(ctx.getAgentRuntimeId()).contains("agent:ops-bot");
        assertThat(ctx.getSessionId()).isEmpty();
        assertThat(ctx.getExecutionId()).isEmpty();
        assertThat(ctx.getPrincipal()).isEmpty();
        assertThat(ctx.getSkillBaseDir()).isEmpty();
    }

    /**
     * {@code sessionId} used to be a deprecated alias writing the {@code agentRuntimeId} field. The alias was withdrawn
     * and the name now carries the session id, so the two accessors must be independent — a context that sets only one
     * of them must not report the other.
     */
    @Test
    void sessionIdAndAgentRuntimeId_AreIndependentFields() {
        final RenderContext onlySession = RenderContext.builder().sessionId("sess-42").build();
        final RenderContext onlyRuntime = RenderContext.builder().agentRuntimeId("agent:ops-bot").build();

        assertThat(onlySession.getSessionId()).contains("sess-42");
        assertThat(onlySession.getAgentRuntimeId()).isEmpty();
        assertThat(onlyRuntime.getAgentRuntimeId()).contains("agent:ops-bot");
        assertThat(onlyRuntime.getSessionId()).isEmpty();
        assertThat(onlySession).isNotEqualTo(onlyRuntime);
    }

    /**
     * {@code sessionId} and {@code executionId} are an exclusive pair, not two spellings of one value: the run
     * rendering the skill either is a session's turn or it is not. Neither accessor may answer for the other, because a
     * skill body reading the session id is asking whether a user is on the other end, and an execution id is not an
     * answer to that question.
     */
    @Test
    void sessionIdAndExecutionId_AreIndependentFields() {
        final RenderContext sessionTurn = RenderContext.builder().sessionId("sess-42").build();
        final RenderContext fork = RenderContext.builder().executionId("subagent:reviewer:e1").build();

        assertThat(sessionTurn.getSessionId()).contains("sess-42");
        assertThat(sessionTurn.getExecutionId()).isEmpty();
        assertThat(fork.getExecutionId()).contains("subagent:reviewer:e1");
        assertThat(fork.getSessionId()).isEmpty();
        assertThat(sessionTurn).isNotEqualTo(fork);
    }

    /**
     * The exclusivity is a property of the callers, not an invariant this type enforces — same as the 6-2 hook
     * contexts. Rejecting one of the shapes here is what would drive a caller to fabricate the other id.
     */
    @Test
    void builder_DoesNotCrossValidateTheIdPair() {
        assertThat(RenderContext.builder().sessionId("sess-42").executionId("subagent:reviewer:e1").build())
                .satisfies(ctx -> {
                    assertThat(ctx.getSessionId()).contains("sess-42");
                    assertThat(ctx.getExecutionId()).contains("subagent:reviewer:e1");
                });
    }

    @Test
    void equalsAndHashCode_SameValues_AreEqual() {
        final RenderContext a = RenderContext.builder().agentRuntimeId("agent:a").skillBaseDir("/d").build();
        final RenderContext b = RenderContext.builder().agentRuntimeId("agent:a").skillBaseDir("/d").build();

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void equalsAndHashCode_DifferentValues_AreNotEqual() {
        final RenderContext a = RenderContext.builder().agentRuntimeId("agent:a").build();
        final RenderContext b = RenderContext.builder().agentRuntimeId("agent:b").build();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void equalsAndHashCode_ConsiderTheExecutionId() {
        final RenderContext a = RenderContext.builder().executionId("subagent:reviewer:e1").build();
        final RenderContext b = RenderContext.builder().executionId("subagent:reviewer:e1").build();
        final RenderContext c = RenderContext.builder().executionId("subagent:reviewer:e2").build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toString_ContainsFieldNames() {
        final RenderContext ctx = RenderContext.builder().agentRuntimeId("agent:a").sessionId("sess-42")
                .executionId("subagent:reviewer:e1").skillBaseDir("/base").build();

        assertThat(ctx.toString()).contains("agentRuntimeId").contains("agent:a").contains("sessionId")
                .contains("sess-42").contains("executionId").contains("subagent:reviewer:e1").contains("skillBaseDir")
                .contains("/base");
    }

    @Test
    void additionalVariables_NullDefaultsToEmptyMap() {
        final RenderContext ctx = RenderContext.builder().additionalVariables(null).build();

        assertThat(ctx.getAdditionalVariables()).isEmpty();
    }

    @Test
    void additionalVariables_AreDefensivelyCopied() {
        final Map<String, String> source = new HashMap<>();
        source.put("FOO", "1");
        final RenderContext ctx = RenderContext.builder().additionalVariables(source).build();

        source.put("BAR", "2");

        assertThat(ctx.getAdditionalVariables()).containsExactlyEntriesOf(Map.of("FOO", "1"));
    }

    @Test
    void additionalVariables_MapIsUnmodifiable() {
        final RenderContext ctx = RenderContext.builder().additionalVariables(Map.of("FOO", "1")).build();

        assertThatThrownBy(() -> ctx.getAdditionalVariables().put("BAR", "2"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void equals_ConsidersAdditionalVariables() {
        final RenderContext a = RenderContext.builder().additionalVariables(Map.of("FOO", "1")).build();
        final RenderContext b = RenderContext.builder().additionalVariables(Map.of("FOO", "1")).build();
        final RenderContext c = RenderContext.builder().additionalVariables(Map.of("FOO", "2")).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
