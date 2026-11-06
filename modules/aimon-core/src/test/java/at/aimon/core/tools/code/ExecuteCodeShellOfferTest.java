package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ShellEngineToolScope;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDescriptor;
import at.aimon.core.agent.tool.ToolOfferContext;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.agent.tool.permission.AllowedTools;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentIsolation;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.RuntimeFileSystem;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.impl.LocalRuntimeFileSystem;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.PreToolHook;
import at.aimon.core.hook.event.ToolScopedHook;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;

/**
 * The offer path of the shell engine (shell doc §4, §12.2, §14): {@code ExecuteCode} is offered with Python only when
 * every static fact holds and the agent's {@code Bash} is unconstrained, graded and approved as {@code Bash}, with its
 * callable set bounded by {@code shell-engine-tool-scope}. Nothing here runs a command.
 */
@DisplayName("ExecuteCode on the shell engine — offer path")
class ExecuteCodeShellOfferTest {

    private final ShellCodeExecutionEngine shellEngine = ShellCodeExecutionEngine.create();
    private final ScriptedEngine jsEngine = ScriptedEngine.javascript();
    private final DefaultToolRegistry registry = new DefaultToolRegistry();

    ExecuteCodeShellOfferTest() {
        registry.register(new TestTool("Bash", SideEffectLevel.MUTATING, DestructiveBehavior.DESTRUCTIVE,
                DataReach.ENVIRONMENT, true));
        registry.register(TestTool.reader("Read"));
        registry.register(new TestTool("MemorySearch", SideEffectLevel.READ_ONLY, DestructiveBehavior.NON_DESTRUCTIVE,
                DataReach.HOST, true));
        registry.register(new TestTool("WebFetch", SideEffectLevel.READ_ONLY, DestructiveBehavior.NON_DESTRUCTIVE,
                DataReach.EXTERNAL, true));
    }

    @AfterEach
    void close() {
        shellEngine.close();
    }

    private static final List<AllowedTool> BASH_AND_READS = List.of(AllowedTool.parse("Bash"),
            AllowedTool.parse("Read"), AllowedTool.parse("MemorySearch"), AllowedTool.parse("WebFetch"),
            AllowedTool.parse("ExecuteCode"));

    private ExecuteCodeTool tool(ShellEngineToolScope scope) {
        return new ExecuteCodeTool(List.of(jsEngine, shellEngine), CodeExecutionLimits.defaults(), scope);
    }

    private ToolOfferContext offer(ProgrammaticToolSettings settings, List<AllowedTool> allowed, HookRegistry hooks,
            ExecutionEnvironment environment) {
        final ToolContext.Builder context = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT,
                environment);
        if (hooks != null) {
            context.put(ToolContextKeys.HOOK_REGISTRY, hooks);
        }
        return CodeOfferKeys.putEnvironmentFacts(ToolOfferContext.builder().registry(registry)
                .admission(AllowedTools.admissionFilter(allowed)).programmatic(settings).agentName("ops"),
                context.build(), allowed).build();
    }

    private static ProgrammaticToolSettings settings(ShellEngineToolScope agentScope) {
        return ProgrammaticToolSettings.builder().tools(List.of("Read", "MemorySearch", "WebFetch"))
                .shellEngineToolScope(agentScope).build();
    }

    @Test
    @DisplayName("auto on a sandbox with every fact: offered as Python, graded and approved as Bash")
    void offeredOnSandbox() {
        final ToolDescriptor descriptor = tool(ShellEngineToolScope.ENVIRONMENT)
                .describe(offer(settings(null), BASH_AND_READS, null, sandbox(true, true)));
        assertThat(descriptor.isOfferable()).isTrue();
        assertThat(descriptor.getDefinition().getDescription()).contains("Python").contains("async def Read");
        assertThat(descriptor.getSideEffectLevel()).isEqualTo(SideEffectLevel.MUTATING);
        assertThat(descriptor.getEffectiveDestructiveBehavior()).isEqualTo(DestructiveBehavior.DESTRUCTIVE);
        assertThat(descriptor.isApprovalDelegated()).isFalse();
        assertThat(descriptor.getApprovalKey()).isEqualTo("Bash");
        assertThat(descriptor.getApprovalVia()).hasValue("ExecuteCode (Python)");
    }

    @Test
    @DisplayName("the default scope keeps only ENVIRONMENT tools; host keeps HOST; unrestricted keeps all")
    void applicationScope() {
        final ToolOfferContext offer = offer(settings(null), BASH_AND_READS, null, sandbox(true, true));
        assertThat(tool(ShellEngineToolScope.ENVIRONMENT).describe(offer).getCodeHostedTools()).containsExactly("Read");
        assertThat(new ExecuteCodeTool(List.of(shellEngine), CodeExecutionLimits.defaults(), ShellEngineToolScope.HOST)
                .describe(offer).getCodeHostedTools()).containsExactlyInAnyOrder("Read", "MemorySearch");
        assertThat(new ExecuteCodeTool(List.of(shellEngine), CodeExecutionLimits.defaults(),
                ShellEngineToolScope.UNRESTRICTED).describe(offer).getCodeHostedTools())
                .containsExactlyInAnyOrder("Read", "MemorySearch", "WebFetch");
    }

    @Test
    @DisplayName("an agent narrows the application's scope; a wider agent value is ignored")
    void agentNarrowsOnly() {
        final ExecuteCodeTool host = new ExecuteCodeTool(List.of(shellEngine), CodeExecutionLimits.defaults(),
                ShellEngineToolScope.HOST);
        assertThat(host
                .describe(offer(settings(ShellEngineToolScope.ENVIRONMENT), BASH_AND_READS, null, sandbox(true, true)))
                .getCodeHostedTools()).containsExactly("Read");
        assertThat(host
                .describe(offer(settings(ShellEngineToolScope.UNRESTRICTED), BASH_AND_READS, null, sandbox(true, true)))
                .getCodeHostedTools()).containsExactlyInAnyOrder("Read", "MemorySearch");
    }

    @Test
    @DisplayName("the scope does not bound the JavaScript engine")
    void scopeIgnoresJavaScript() {
        final ProgrammaticToolSettings js = ProgrammaticToolSettings.builder()
                .tools(List.of("Read", "MemorySearch", "WebFetch")).engine(ProgrammaticEngineChoice.JAVASCRIPT).build();
        assertThat(tool(ShellEngineToolScope.ENVIRONMENT).describe(offer(js, BASH_AND_READS, null, sandbox(true, true)))
                .getCodeHostedTools()).containsExactlyInAnyOrder("Read", "MemorySearch", "WebFetch");
    }

    @Test
    @DisplayName("not offered unless Bash is unconstrained: unrestricted list, patterned Bash, no Bash, Bash hooks")
    void bashMustBeUnconstrained() {
        final ExecuteCodeTool tool = tool(ShellEngineToolScope.ENVIRONMENT);
        assertThat(tool.describe(offer(settings(null), List.of(), null, sandbox(true, true))).isOfferable())
                .as("an empty allow-list is not a decision to give an arbitrary shell").isFalse();
        assertThat(tool.describe(offer(settings(null),
                List.of(AllowedTool.parse("Bash(git *)"), AllowedTool.parse("Read"), AllowedTool.parse("ExecuteCode")),
                null, sandbox(true, true))).isOfferable()).isFalse();
        assertThat(
                tool.describe(offer(
                        settings(null), List.of(AllowedTool.parse("Bash"), AllowedTool.parse("Bash(git *)"),
                                AllowedTool.parse("Read"), AllowedTool.parse("ExecuteCode")),
                        null, sandbox(true, true))).isOfferable())
                .isFalse();
        assertThat(tool.describe(offer(settings(null),
                List.of(AllowedTool.parse("Read"), AllowedTool.parse("ExecuteCode")), null, sandbox(true, true)))
                .isOfferable()).isFalse();

        final HookRegistry anyTool = new DefaultHookRegistry();
        anyTool.register(HookEventType.PRE_TOOL, (PreToolHook) context -> HookResult.success());
        assertThat(tool.describe(offer(settings(null), BASH_AND_READS, anyTool, sandbox(true, true))).isOfferable())
                .as("a hook that does not say which tools it acts on applies to Bash").isFalse();

        final HookRegistry elsewhere = new DefaultHookRegistry();
        elsewhere.register(HookEventType.PRE_TOOL, new ScopedHook("Write"));
        assertThat(tool.describe(offer(settings(null), BASH_AND_READS, elsewhere, sandbox(true, true))).isOfferable())
                .as("a hook scoped to another tool does not narrow Bash").isTrue();

        final HookRegistry onBash = new DefaultHookRegistry();
        onBash.register(HookEventType.PRE_TOOL, new ScopedHook("Bash"));
        assertThat(tool.describe(offer(settings(null), BASH_AND_READS, onBash, sandbox(true, true))).isOfferable())
                .isFalse();
    }

    @Test
    @DisplayName("not offered without python3 declared or without a runtime file system; host gets JavaScript")
    void staticFacts() {
        final ExecuteCodeTool tool = tool(ShellEngineToolScope.ENVIRONMENT);
        assertThat(tool.describe(offer(settings(null), BASH_AND_READS, null, sandbox(false, true))).isOfferable())
                .isFalse();
        assertThat(tool.describe(offer(settings(null), BASH_AND_READS, null, sandbox(true, false))).isOfferable())
                .isFalse();
        final ToolDescriptor onHost = tool.describe(offer(settings(null), BASH_AND_READS, null, host()));
        assertThat(onHost.isOfferable()).isTrue();
        assertThat(onHost.getDefinition().getDescription()).contains("JavaScript");
        final ProgrammaticToolSettings python = ProgrammaticToolSettings.builder().tools(List.of("Read"))
                .engine(ProgrammaticEngineChoice.PYTHON).build();
        assertThat(tool.describe(offer(python, BASH_AND_READS, null, host())).isOfferable())
                .as("explicit python on a host environment: no fallback to JavaScript").isFalse();
    }

    private static final class ScopedHook implements PreToolHook, ToolScopedHook {
        private final String tool;

        ScopedHook(String tool) {
            this.tool = tool;
        }

        @Override
        public HookResult execute(at.aimon.core.hook.event.PreToolContext context) {
            return HookResult.success();
        }

        @Override
        public boolean mayApplyTo(String toolName) {
            return tool.equals(toolName);
        }
    }

    private static ExecutionEnvironment host() {
        return new FactsEnvironment(
                EnvironmentDescriptor.builder().workingDirectory("/w").isolation(EnvironmentIsolation.HOST).build(),
                false);
    }

    private static ExecutionEnvironment sandbox(boolean python, boolean runtimeFiles) {
        final EnvironmentDescriptor.Builder descriptor = EnvironmentDescriptor.builder().workingDirectory("/workspace")
                .isolation(EnvironmentIsolation.SANDBOXED);
        if (python) {
            descriptor.runtime(EnvironmentDescriptor.RUNTIME_PYTHON3);
        }
        return new FactsEnvironment(descriptor.build(), runtimeFiles);
    }

    /** Static facts only: a shell that declares the features, which is never asked to run anything. */
    private static final class FactsEnvironment implements ExecutionEnvironment {
        private final EnvironmentDescriptor descriptor;
        private final boolean runtimeFiles;
        private final VirtualShell shell = new LocalShell();

        FactsEnvironment(EnvironmentDescriptor descriptor, boolean runtimeFiles) {
            this.descriptor = descriptor;
            this.runtimeFiles = runtimeFiles;
        }

        @Override
        public VirtualFileSystem fileSystem() {
            throw new UnsupportedOperationException();
        }

        @Override
        public VirtualShell shell() {
            return shell;
        }

        @Override
        public EnvironmentDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public Optional<RuntimeFileSystem> runtimeFileSystem() {
            return runtimeFiles
                    ? Optional.of(new LocalRuntimeFileSystem(java.nio.file.Path.of("/nonexistent")))
                    : Optional.empty();
        }

        @Override
        public String stage(StagedResource resource) {
            throw new UnsupportedOperationException();
        }
    }
}
