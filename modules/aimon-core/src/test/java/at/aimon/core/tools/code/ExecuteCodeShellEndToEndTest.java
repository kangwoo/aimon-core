package at.aimon.core.tools.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.core.agent.InvokerType;
import at.aimon.core.agent.interrupt.DefaultInterruptCoordinator;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DefaultToolExecutionManager;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.agent.tool.ShellEngineToolScope;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolCallDispatcher;
import at.aimon.core.agent.tool.ToolCallOrigin;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolDispatchResult;
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
import at.aimon.core.hook.DefaultHookExecutionManager;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.execution.Decision;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.programmatic.CodeExecutionLimits;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.impl.local.LocalShell;
import at.aimon.core.toolinvocation.DefaultToolCallDispatcher;
import at.aimon.core.toolinvocation.SingleToolInvoker;
import at.aimon.core.toolinvocation.approval.SideEffectApprovalGate;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.code.shell.ShellCodeExecutionEngine;

/**
 * {@code ExecuteCode} on the shell engine over the real gated pipeline: a Python program in a sandbox-shaped local
 * environment calls a tool through the bridge, the approval gate asks under {@code Bash}'s key naming the path
 * (decisions 10, 11), and the observation is the program's standard output.
 */
@DisplayName("ExecuteCode end to end on the shell engine")
class ExecuteCodeShellEndToEndTest {

    @TempDir
    Path tempDir;

    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final TestTool read = TestTool.reader("Read");
    private ShellCodeExecutionEngine engine;
    private DefaultToolRegistry registry;
    private DefaultInterruptCoordinator coordinator;
    private ExecutionEnvironment environment;

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(python3(), "python3 3.8+ is not installed");
        engine = ShellCodeExecutionEngine.create();
        registry = new DefaultToolRegistry();
        registry.register(
                new ExecuteCodeTool(List.of(engine), CodeExecutionLimits.defaults(), ShellEngineToolScope.ENVIRONMENT));
        registry.register(read);
        registry.register(new TestTool("Bash", SideEffectLevel.MUTATING, DestructiveBehavior.DESTRUCTIVE,
                DataReach.ENVIRONMENT, true));
        coordinator = new DefaultInterruptCoordinator();
        environment = new LocalSandbox(tempDir.toRealPath());
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
            engine.close();
        }
    }

    @Test
    @DisplayName("a Python program calls Read; the gate asks once under Bash's key; stdout is the observation")
    void runsThroughTheGates() {
        final List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash"), AllowedTool.parse("Read"),
                AllowedTool.parse("ExecuteCode"));
        final SideEffectApprovalGate gate = new SideEffectApprovalGate(prompt -> {
            prompts.add(prompt);
            return Decision.ALLOW;
        });
        final ToolCallDispatcher dispatcher = DefaultToolCallDispatcher.builder()
                .invoker(new SingleToolInvoker(new DefaultToolExecutionManager(), new DefaultHookExecutionManager(),
                        gate))
                .invokerType(InvokerType.MAIN_AGENT).invokerName("agent").hookRegistry(new DefaultHookRegistry())
                .executionAttributes(Map.of()).toolRegistry(registry).sessionRegistry(registry).allowedTools(allowed)
                .coordinator(coordinator).build();
        final ToolContext base = ToolContext.builder().put(ToolContextKeys.EXECUTION_ENVIRONMENT, environment).build();
        final ToolOfferContext offer = CodeOfferKeys.putEnvironmentFacts(ToolOfferContext.builder().registry(registry)
                .admission(AllowedTools.admissionFilter(allowed))
                .programmatic(ProgrammaticToolSettings.builder().tools(List.of("Read")).build()).agentName("ops"), base,
                allowed).build();
        final ToolContext context = ToolContext.builder().putAll(base.getContext())
                .put(ToolContextKeys.TOOL_CALL_DISPATCHER, dispatcher).put(ToolContextKeys.TOOL_OFFER_CONTEXT, offer)
                .put(ToolContextKeys.SESSION_ID, SessionId.of("s-1")).build();

        final ToolDispatchResult result = dispatcher.dispatch(
                ToolUse.of("tu-1", ExecuteCodeTool.TOOL_NAME,
                        Map.of("code", "r = await Read({'path': '/etc/hosts'})\nprint('got ' + r)")),
                ToolCallOrigin.direct(), context);

        assertThat(result.isError()).as(result.getText()).isFalse();
        assertThat(result.getText()).contains("got Read:/etc/hosts").contains("[ExecuteCode: 1 tool call");
        assertThat(read.calls).singleElement().satisfies(call -> assertThat(call).containsEntry("path", "/etc/hosts"));
        assertThat(prompts).singleElement()
                .satisfies(prompt -> assertThat(prompt).contains("Bash").contains("ExecuteCode (Python)"));
    }

    private static boolean python3() {
        try {
            final Process process = new ProcessBuilder("python3", "-c",
                    "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)").start();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** A local shell and runtime file system over one directory, declared as a sandbox with python3. */
    private static final class LocalSandbox implements ExecutionEnvironment {
        private final VirtualShell shell;
        private final RuntimeFileSystem files;
        private final EnvironmentDescriptor descriptor;

        LocalSandbox(Path root) {
            this.shell = new LocalShell(root);
            this.files = new LocalRuntimeFileSystem(root);
            this.descriptor = EnvironmentDescriptor.builder().workingDirectory(root.toString())
                    .isolation(EnvironmentIsolation.SANDBOXED).runtime(EnvironmentDescriptor.RUNTIME_PYTHON3).build();
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
            return Optional.of(files);
        }

        @Override
        public String stage(StagedResource resource) {
            throw new UnsupportedOperationException();
        }
    }
}
