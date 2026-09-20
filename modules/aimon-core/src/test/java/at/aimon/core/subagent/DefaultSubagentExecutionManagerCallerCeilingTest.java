package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.Environment;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;

/**
 * Covers the delegation ceiling: a spawned subagent is bound by its own {@code allowed-tools} <b>and</b> by the
 * allow-list of the run that spawned it.
 *
 * <p>
 * Without it, a delegation is an escalation — an agent narrowed to {@code Read, Grep} reaches {@code Bash} by
 * launching a subagent that names it, and the narrowing describes only what the agent does with its own hands rather
 * than what it can cause. That is the difference between a tool-offer convenience and a boundary, so it is the
 * property this class pins.
 *
 * <p>
 * The ceiling is applied where the resolved subagent is turned into an execution context, which is the one place both
 * execution branches pass through. The consequence is tested directly here: a registered <b>code behavior</b> is
 * handed the same narrowed definition as the ReAct loop, so the boundary does not depend on which branch runs.
 */
@DisplayName("DefaultSubagentExecutionManager — caller allow-list ceiling")
class DefaultSubagentExecutionManagerCallerCeilingTest {

    private final SubagentExecutor reactExecutor = mock(SubagentExecutor.class);
    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
    }

    @Test
    @DisplayName("the effective allow-list is the intersection of the caller's and the subagent's")
    void theEffectiveListIsTheIntersection() {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("ok"));

        newManager().execute(envAllowing(List.of("Read", "Grep")),
                subagent("explorer", List.of("Read", "Bash")), "go");

        assertThat(effectiveAllowedToolNames()).containsExactly("Read");
    }

    @Test
    @DisplayName("a subagent that declares nothing inherits the caller's list rather than staying unrestricted")
    void anUnrestrictedSubagentInheritsTheCeiling() {
        // The case that makes this a ceiling rather than a merge. An empty allow-list reads as unrestricted
        // everywhere in the permission package, so leaving it alone here would let the loosest possible subagent
        // definition erase the caller's restriction entirely.
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("ok"));

        newManager().execute(envAllowing(List.of("Read")), subagent("explorer", List.of()), "go");

        assertThat(effectiveAllowedToolNames()).containsExactly("Read");
    }

    @Test
    @DisplayName("a caller that declares nothing imposes no ceiling, leaving the subagent exactly as resolved")
    void noCallerListLeavesTheSubagentAlone() {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("ok"));
        final Subagent explorer = subagent("explorer", List.of("Read", "Bash"));

        newManager().execute(env(), explorer, "go");

        // Same instance, not merely an equal one: the no-ceiling path must not rebuild the definition at all.
        assertThat(capturedContext().getSubagent()).isSameAs(explorer);
    }

    @Test
    @DisplayName("a pattern on one side survives when the other side names the tool without one")
    void aPatternOnOneSideGoverns() {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult("ok"));

        newManager().execute(envAllowing(List.of("Bash")), subagent("explorer", List.of("Bash(git:*)")), "go");

        final List<AllowedTool> effective = capturedContext().getSubagent().getAllowedTools();
        assertThat(effective).singleElement().satisfies(entry -> {
            assertThat(entry.getToolName()).isEqualTo("Bash");
            assertThat(entry.hasPattern()).as("the narrower side governs").isTrue();
        });
    }

    @Test
    @DisplayName("two lists with nothing in common refuse the run instead of running it unrestricted")
    void disjointListsRefuseTheRun() {
        // The inversion this guards: handing on an empty intersection as an empty list would turn the strictest
        // possible pairing into no restriction at all. The message names both sides because the mismatch is a
        // configuration error and fixing it means seeing them together.
        final SubagentExecutionResult result = newManager().execute(envAllowing(List.of("Read")),
                subagent("writer", List.of("Bash")), "go");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("writer").contains("nothing in common").contains("Read")
                .contains("Bash");
        verify(reactExecutor, never()).execute(any(), any());
    }

    @Test
    @DisplayName("a registered code behavior is bound by the same ceiling as the ReAct loop")
    void aCodeBehaviorSeesTheSameCeiling() {
        final InMemorySubagentBehaviorRegistry behaviors = new InMemorySubagentBehaviorRegistry();
        final List<List<AllowedTool>> seen = new ArrayList<>();
        behaviors.register("explorer", (ctx, req, support) -> {
            seen.add(ctx.getSubagent().getAllowedTools());
            return support.success("done");
        });

        final SubagentExecutionResult result = newManager(behaviors).execute(envAllowing(List.of("Read", "Grep")),
                subagent("explorer", List.of("Read", "Bash")), "go");

        assertThat(result.isSuccess()).isTrue();
        assertThat(seen).singleElement()
                .satisfies(allowed -> assertThat(allowed).extracting(AllowedTool::getToolName).containsExactly("Read"));
    }

    private List<String> effectiveAllowedToolNames() {
        return capturedContext().getSubagent().getAllowedTools().stream().map(AllowedTool::getToolName).toList();
    }

    private SubagentExecutionContext capturedContext() {
        final ArgumentCaptor<SubagentExecutionContext> captor = ArgumentCaptor.forClass(SubagentExecutionContext.class);
        verify(reactExecutor).execute(captor.capture(), any());
        return captor.getValue();
    }

    private DefaultSubagentExecutionManager newManager() {
        return newManager(new InMemorySubagentBehaviorRegistry());
    }

    private DefaultSubagentExecutionManager newManager(InMemorySubagentBehaviorRegistry behaviorRegistry) {
        return new DefaultSubagentExecutionManager(reactExecutor, bgPool, null, behaviorRegistry);
    }

    private static Subagent subagent(String name, List<String> allowedTools) {
        return Subagent.builder().name(name).systemPrompt("(inline)").tools(allowedTools).build();
    }

    private static SubagentExecutionEnvironment env() {
        return baseEnv().build();
    }

    private static SubagentExecutionEnvironment envAllowing(List<String> callerAllowedTools) {
        return baseEnv().callerAllowedTools(callerAllowedTools.stream().map(AllowedTool::parse).toList()).build();
    }

    private static SubagentExecutionEnvironment.Builder baseEnv() {
        return SubagentExecutionEnvironment.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).environment(Environment.createDefault())
                .defaultModel(LlmModel.builder().name("gpt-4").build());
    }

    private static SubagentExecutionResult reactResult(String answer) {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success(answer, SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }
}
