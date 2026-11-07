package at.aimon.core.toolinvocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.environment.TestExecutionEnvironments;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.tools.CallerProgrammatic;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("ToolCallDispatchers.ungated — the explicit, named way past the gates")
class ToolCallDispatchersTest {

    private static Tool counting(String name, AtomicInteger runs) {
        return new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.of(name, name, Map.of("type", "object"));
            }

            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                runs.incrementAndGet();
                return ToolResult.success("ran " + name);
            }
        };
    }

    @Test
    @DisplayName("runs the tool and hands the environment's notices on beside the text")
    void runsTheToolAndDrainsNotices() {
        final AtomicInteger runs = new AtomicInteger();
        final ToolRegistry registry = new DefaultToolRegistry();
        registry.register(counting("Read", runs));
        final Queue<String> notices = new ConcurrentLinkedQueue<>(List.of("re-created"));
        final ToolContext context = TestExecutionEnvironments.withoutStamps(
                TestExecutionEnvironments.builder().workingDirectory("/workspace").notices(notices).build());

        final ToolDispatchResult result = ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry)
                .dispatch(ToolUse.of("t", "Read", Map.of()), ToolCallOrigin.direct(), context);

        assertThat(runs).hasValue(1);
        assertThat(result.isError()).isFalse();
        assertThat(result.getText()).isEqualTo("ran Read");
        assertThat(result.getNotices()).containsExactly("re-created");
        final ToolUseResult converted = ToolDispatchResults.toToolUseResult(result);
        assertThat(converted.getContent()).isEqualTo("[environment] re-created\nran Read");
    }

    @Test
    @DisplayName("an unknown tool is an error result, not a throw")
    void unknownToolIsAnErrorResult() {
        final ToolDispatchResult result = ToolCallDispatchers
                .ungated(new DefaultToolExecutionManager(), new DefaultToolRegistry())
                .dispatch(ToolUse.of("t", "Nope", Map.of()), ToolCallOrigin.direct(), ToolContext.empty());

        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("narrows like the gated dispatcher: subset registry, intersected allow-list")
    void narrowsLikeTheGatedDispatcher() {
        final AtomicInteger runs = new AtomicInteger();
        final Tool read = counting("Read", runs);
        final Tool write = counting("Write", runs);
        final ToolRegistry registry = new DefaultToolRegistry();
        registry.register(read);
        registry.register(write);
        final ToolCallDispatcher ungated = ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry);

        final ToolRegistry wider = new DefaultToolRegistry();
        wider.register(read);
        wider.register(counting("Bash", runs));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ungated.narrow(wider, List.of(), ProgrammaticToolSettings.none()))
                .withMessageContaining("Bash");

        final ToolDispatchResult refused = ungated
                .narrow(registry, List.of(AllowedTool.parse("Read")), ProgrammaticToolSettings.none())
                .dispatch(ToolUse.of("t", "Write", Map.of()), ToolCallOrigin.skill("s"), ToolContext.empty());
        assertThat(refused.isError()).isTrue();
        assertThat(runs).hasValue(0);
    }

    private static ProgrammaticToolSettings ptc(String... tools) {
        return ProgrammaticToolSettings.builder().tools(List.of(tools)).build();
    }

    private static ToolRegistry probeRegistry(AtomicReference<ProgrammaticToolSettings> seen) {
        final ToolRegistry registry = new DefaultToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition getDefinition() {
                return ToolDefinition.of("Probe", "probe", Map.of("type", "object"));
            }

            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                seen.set(CallerProgrammatic.of(context));
                return ToolResult.success("probed");
            }
        });
        return registry;
    }

    private static ProgrammaticToolSettings publishedBy(ToolCallDispatcher dispatcher,
            AtomicReference<ProgrammaticToolSettings> seen, ToolContext context) {
        seen.set(null);
        final ToolDispatchResult result = dispatcher.dispatch(ToolUse.of("p", "Probe", Map.of()),
                ToolCallOrigin.direct(), context);
        assertThat(result.isError()).as(result.getText()).isFalse();
        return seen.get();
    }

    @Test
    @DisplayName("publishes its programmatic settings to every call, overwriting a pre-seeded value")
    void publishesCallerProgrammatic() {
        final AtomicReference<ProgrammaticToolSettings> seen = new AtomicReference<>();
        final ToolRegistry registry = probeRegistry(seen);
        final ToolCallDispatcher ungated = ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry,
                ptc("Read", "Grep"));
        final ToolContext preSeeded = ToolContext.builder().put(ToolContextKeys.CALLER_PROGRAMMATIC, ptc("Write"))
                .build();

        assertThat(publishedBy(ungated, seen, ToolContext.empty())).isEqualTo(ptc("Read", "Grep"));
        assertThat(publishedBy(ungated, seen, preSeeded)).isEqualTo(ptc("Read", "Grep"));
    }

    @Test
    @DisplayName("the two-argument ungated publishes none(), even over a pre-seeded value")
    void twoArgumentUngatedPublishesNone() {
        final AtomicReference<ProgrammaticToolSettings> seen = new AtomicReference<>();
        final ToolRegistry registry = probeRegistry(seen);
        final ToolContext preSeeded = ToolContext.builder().put(ToolContextKeys.CALLER_PROGRAMMATIC, ptc("Read"))
                .build();

        assertThat(
                publishedBy(ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry), seen, preSeeded))
                .isEqualTo(ProgrammaticToolSettings.none());
    }

    @Test
    @DisplayName("narrow intersects the programmatic settings and cannot widen them")
    void narrowIntersectsAndCannotWidenProgrammatic() {
        final AtomicReference<ProgrammaticToolSettings> seen = new AtomicReference<>();
        final ToolRegistry registry = probeRegistry(seen);
        final ToolCallDispatcher ungated = ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry,
                ptc("Read", "Grep"));

        assertThat(publishedBy(ungated.narrow(registry, List.of(), ptc("Read")), seen, ToolContext.empty()))
                .isEqualTo(ptc("Read"));
        assertThat(publishedBy(ungated.narrow(registry, List.of(), ProgrammaticToolSettings.none()), seen,
                ToolContext.empty())).isEqualTo(ProgrammaticToolSettings.none());
        final ToolCallDispatcher readOnly = ToolCallDispatchers.ungated(new DefaultToolExecutionManager(), registry,
                ptc("Read"));
        assertThat(publishedBy(readOnly.narrow(registry, List.of(), ptc("Read", "Grep", "Write")), seen,
                ToolContext.empty())).isEqualTo(ptc("Read"));
        assertThat(publishedBy(
                ungated.narrow(registry, List.of(), ptc("Grep", "Read")).narrow(registry, List.of(), ptc("Grep")), seen,
                ToolContext.empty())).isEqualTo(ptc("Grep"));
    }
}
