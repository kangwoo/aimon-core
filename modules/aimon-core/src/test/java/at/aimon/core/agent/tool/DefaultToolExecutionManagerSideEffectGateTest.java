package at.aimon.core.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.execution.ToolExecutionResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.llm.ToolUse;

@DisplayName("DefaultToolExecutionManager — side-effect ceiling gate")
class DefaultToolExecutionManagerSideEffectGateTest {

    /** Names of tools that actually reached execute(); the gate must keep entries out of this list. */
    private final List<String> executed = new CopyOnWriteArrayList<>();

    private Tool tool(String name, SideEffectLevel level) {
        return new AbstractTool(name, name + " description", Map.of("type", "object")) {
            @Override
            public ToolResult execute(ToolInput input, ToolContext context) {
                executed.add(name);
                return ToolResult.success("ran " + name);
            }

            @Override
            public SideEffectLevel getSideEffectLevel() {
                return level;
            }
        };
    }

    private ToolRegistry registryWith(Tool... tools) {
        final DefaultToolRegistry registry = new DefaultToolRegistry();
        for (Tool t : tools) {
            registry.register(t);
        }
        return registry;
    }

    private static ToolUse use(String name) {
        return ToolUse.of("id-" + name, name, Map.of());
    }

    @Nested
    @DisplayName("executeAll()")
    class ExecuteAll {

        @Test
        @DisplayName("refuses a MUTATING tool under a READ_ONLY ceiling and reports why")
        void refusesOverPrivileged() {
            final ToolExecutionManager manager = new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY);
            final ToolRegistry registry = registryWith(tool("Writer", SideEffectLevel.MUTATING));

            final List<ToolExecutionResult> results = manager.executeAll(registry, ToolContext.empty(),
                    List.of(use("Writer")), List.of());

            assertThat(executed).isEmpty();
            assertThat(results).hasSize(1);
            assertThat(results.get(0).isError()).isTrue();
            assertThat(results.get(0).getContent()).contains("Writer").contains("MUTATING").contains("READ_ONLY");
        }

        @Test
        @DisplayName("blocking one tool does not abort the rest of the batch")
        void blockedToolDoesNotAbortBatch() {
            final ToolExecutionManager manager = new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY);
            final ToolRegistry registry = registryWith(tool("Writer", SideEffectLevel.MUTATING),
                    tool("Reader", SideEffectLevel.READ_ONLY));

            final List<ToolExecutionResult> results = manager.executeAll(registry, ToolContext.empty(),
                    List.of(use("Writer"), use("Reader")), List.of());

            assertThat(executed).containsExactly("Reader");
            assertThat(results).hasSize(2);
            assertThat(results.get(0).isError()).isTrue();
            assertThat(results.get(1).isSuccess()).isTrue();
            assertThat(results.get(1).getContent()).isEqualTo("ran Reader");
        }

        @Test
        @DisplayName("the default ceiling imposes no restriction, so undeclared tools keep running")
        void defaultCeilingIsUnrestricted() {
            final ToolExecutionManager manager = new DefaultToolExecutionManager();
            final Tool undeclared = new AbstractTool("Legacy", "declares nothing", Map.of("type", "object")) {
                @Override
                public ToolResult execute(ToolInput input, ToolContext context) {
                    executed.add("Legacy");
                    return ToolResult.success("ran Legacy");
                }
            };

            final List<ToolExecutionResult> results = manager.executeAll(registryWith(undeclared), ToolContext.empty(),
                    List.of(use("Legacy")), List.of());

            assertThat(executed).containsExactly("Legacy");
            assertThat(results.get(0).isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("execute()")
    class ExecuteSingle {

        @Test
        @DisplayName("refuses a MUTATING tool under a READ_ONLY ceiling")
        void refusesOverPrivileged() {
            final ToolExecutionManager manager = new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY);
            final ToolRegistry registry = registryWith(tool("Writer", SideEffectLevel.MUTATING));

            final ToolExecutionResult result = manager.execute(use("Writer"), ToolContext.empty(), registry, List.of());

            assertThat(executed).isEmpty();
            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).contains("Writer").contains("MUTATING").contains("READ_ONLY");
        }

        @Test
        @DisplayName("admits a tool at the ceiling")
        void admitsAtCeiling() {
            final ToolExecutionManager manager = new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY);
            final ToolRegistry registry = registryWith(tool("Reader", SideEffectLevel.READ_ONLY));

            final ToolExecutionResult result = manager.execute(use("Reader"), ToolContext.empty(), registry, List.of());

            assertThat(executed).containsExactly("Reader");
            assertThat(result.isSuccess()).isTrue();
        }
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("rejects a null ceiling instead of silently defaulting to unrestricted")
        void nullCeilingRejected() {
            assertThatThrownBy(() -> new DefaultToolExecutionManager((SideEffectLevel) null))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Max side effect level");
        }
    }

    @Nested
    @DisplayName("exposing the ceiling")
    class ExposingTheCeiling {

        @Test
        @DisplayName("reports the ceiling it was built with, so a definition filter can be derived from it")
        void reportsConfiguredCeiling() {
            assertThat(new DefaultToolExecutionManager(SideEffectLevel.READ_ONLY).getMaxSideEffectLevel())
                    .isEqualTo(SideEffectLevel.READ_ONLY);
        }

        @Test
        @DisplayName("reports MUTATING when unrestricted, so deriving a filter from it withholds nothing")
        void reportsUnrestrictedByDefault() {
            assertThat(new DefaultToolExecutionManager().getMaxSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        }

        @Test
        @DisplayName("an implementation that does not override reports the unrestricted ceiling")
        void interfaceDefaultIsUnrestricted() {
            final ToolExecutionManager custom = new ToolExecutionManager() {
                @Override
                public List<ToolExecutionResult> executeAll(ToolRegistry toolRegistry, ToolContext toolContext,
                        List<ToolUse> toolUses, List<AllowedTool> allowedTools) {
                    return List.of();
                }

                @Override
                public ToolExecutionResult execute(ToolUse toolUse, ToolContext toolContext, ToolRegistry toolRegistry,
                        List<AllowedTool> allowedTools) {
                    throw new UnsupportedOperationException();
                }
            };

            assertThat(custom.getMaxSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        }
    }
}
