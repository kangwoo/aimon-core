package at.aimon.core.skill.hook.declarative;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.InvokerType;
import at.aimon.core.environment.DelegatingFileSystem;
import at.aimon.core.environment.EnvironmentDescriptor;
import at.aimon.core.environment.EnvironmentRequest;
import at.aimon.core.environment.ExecutionEnvironment;
import at.aimon.core.environment.StagedResource;
import at.aimon.core.environment.impl.LocalExecutionEnvironmentProvider;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystem;
import at.aimon.core.filesystem.impl.local.LocalFileSystemConfig;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.hook.event.OnStartContext;
import at.aimon.core.hook.event.PostToolContext;
import at.aimon.core.hook.execution.HookContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.hook.execution.HookStatus;
import at.aimon.core.llm.ToolUse;
import at.aimon.core.llm.ToolUseResult;
import at.aimon.core.shell.ExecutionOptions;
import at.aimon.core.shell.ShellCommand;
import at.aimon.core.shell.ShellCommandResult;
import at.aimon.core.shell.ShellFeature;
import at.aimon.core.shell.VirtualShell;
import at.aimon.core.shell.exception.ShellExecutionException;
import at.aimon.core.skill.hook.SkillHookSet;
import at.aimon.core.skill.hook.SkillScopedHookRegistry;
import at.aimon.core.skill.hook.action.ShellAction;
import at.aimon.core.skill.hook.declarative.predicate.NameOnlyPredicate;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * A skill hook's {@code AIMON_SKILL_DIR} under EE-109: a run directory of the skill's bytes where the shell can place
 * one, a staged copy checked on every firing where it cannot.
 */
@DisabledOnOs(OS.WINDOWS)
@DisplayName("AIMON_SKILL_DIR as a run directory (EE-109)")
class SkillHookRunDirectoryTest {

    private static final ShellAction SAY = new ShellAction(
            "printf '%s|' \"$AIMON_SKILL_DIR\"; cat \"$AIMON_SKILL_DIR/scripts/say.txt\"", Duration.ofSeconds(10));

    @TempDir
    Path controlDir;

    @TempDir
    Path workspace;

    private LocalFileSystem control;
    private LocalExecutionEnvironmentProvider provider;
    private ExecutionEnvironment environment;
    private final DefaultShellActionExecutor executor = new DefaultShellActionExecutor();
    private final List<ListAppender<ILoggingEvent>> appenders = new ArrayList<>();
    private final List<Logger> loggers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        HookRunFiles.clearCache();
        DefaultShellActionExecutor.resetFallbackWarnings();
        control = new LocalFileSystem(new LocalFileSystemConfig(controlDir.toString()));
        control.initialize();
        control.write("skills/demo/SKILL.md", "# demo");
        control.write("skills/demo/scripts/say.txt", "loaded");
        provider = LocalExecutionEnvironmentProvider.builder().workspaceRoot(workspace).contentSearch(false).build();
        environment = provider
                .resolve(EnvironmentRequest.builder().agentRuntimeId(AgentRuntimeId.of("agent:t")).build());
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = 0; i < loggers.size(); i++) {
            loggers.get(i).detachAppender(appenders.get(i));
        }
        provider.close();
        control.close();
        HookRunFiles.clearCache();
        DefaultShellActionExecutor.resetFallbackWarnings();
    }

    private ListAppender<ILoggingEvent> capture(Class<?> type) {
        final Logger logger = (Logger) LoggerFactory.getLogger(type);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        loggers.add(logger);
        appenders.add(appender);
        return appender;
    }

    private static long warnings(ListAppender<ILoggingEvent> appender, String containing) {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(containing)).count();
    }

    private static HookContext contextIn(ExecutionEnvironment environment) {
        return OnStartContext.builder().executorType(InvokerType.SUBAGENT).invokerName("worker")
                .hookRegistry(new DefaultHookRegistry()).executionEnvironment(environment).userMessage("go").build();
    }

    private ShellHookOutcome fire(StagedResource resource) {
        return fire(resource, environment);
    }

    private ShellHookOutcome fire(StagedResource resource, ExecutionEnvironment in) {
        return executor.run(SAY, contextIn(in), Map.of(SkillHookEnv.AIMON_SKILL_NAME, "demo"), null,
                Optional.of(resource));
    }

    private static String dirOf(ShellHookOutcome outcome) {
        return outcome.getStdout().substring(0, outcome.getStdout().indexOf('|'));
    }

    private static String contentOf(ShellHookOutcome outcome) {
        return outcome.getStdout().substring(outcome.getStdout().indexOf('|') + 1);
    }

    // --- the run directory -------------------------------------------------------------------------------------

    @Test
    @DisplayName("on a shell with RUN_DIRECTORY the variable is a run directory outside the workspace; nothing is staged")
    void runDirectoryOnTheLocalShell() {
        final ShellHookOutcome outcome = fire(StagedResource.scan(control, "skills/demo", "demo"));

        assertThat(outcome.getExitCode()).isZero();
        assertThat(contentOf(outcome)).isEqualTo("loaded");
        final Path dir = Path.of(dirOf(outcome));
        assertThat(dir.getFileName().toString()).startsWith("aimon-run-");
        assertThat(dir.startsWith(workspace)).isFalse();
        assertThat(dir).doesNotExist();
        assertThat(workspace.resolve(LocalExecutionEnvironmentProvider.DEFAULT_STAGING_ROOT).resolve("demo"))
                .doesNotExist();
    }

    @Test
    @DisplayName("the loaded version is kept in memory: the second firing reads nothing from the source")
    void secondFiringReadsNothing() {
        final List<String> reads = new ArrayList<>();
        final VirtualFileSystem counting = new DelegatingFileSystem(control) {
            @Override
            public InputStream read(String path) {
                reads.add(path);
                return super.read(path);
            }
        };
        final StagedResource resource = StagedResource.scan(counting, "skills/demo", "demo");
        reads.clear();

        fire(resource);
        assertThat(reads).isNotEmpty();
        reads.clear();
        final ShellHookOutcome second = fire(resource);

        assertThat(reads).isEmpty();
        assertThat(contentOf(second)).isEqualTo("loaded");
    }

    @Test
    @DisplayName("an edit after the first firing is not seen until the registry reloads (the EE-3 rule)")
    void editAfterTheFirstFiringIsNotSeen() {
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");
        fire(resource);
        control.write("skills/demo/scripts/say.txt", "edited");

        assertThat(contentOf(fire(resource))).isEqualTo("loaded");
    }

    @Test
    @DisplayName("EE-3: a scanned skill edited before its first firing runs as it is now, with one WARN")
    void editedScannedSkillRunsAsItIsNow() {
        final ListAppender<ILoggingEvent> log = capture(HookRunFiles.class);
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");
        control.write("skills/demo/scripts/say.txt", "edited");

        assertThat(contentOf(fire(resource))).isEqualTo("edited");
        assertThat(contentOf(fire(resource))).isEqualTo("edited");
        assertThat(warnings(log, "changed on disk since it was loaded")).isEqualTo(1);
    }

    @Test
    @DisplayName("EE-3: a skill assembled by hand whose source changed is refused, never re-scanned")
    void editedAssembledSkillIsRefused() throws Exception {
        final StagedResource scanned = StagedResource.scan(control, "skills/demo", "demo");
        // Lists one of the two files on purpose: a rescan would add the other to a directory the agent's uid reads.
        final StagedResource assembled = StagedResource.builder().sourceFileSystem(control).sourceDir("skills/demo")
                .name("demo")
                .contentKey(new StagedResource.ContentKeyBuilder().add("scripts/say.txt", "loaded".getBytes()).build())
                .totalBytes(6).files(List.of("scripts/say.txt")).build();
        assertThat(scanned.isScanned()).isTrue();
        control.write("skills/demo/scripts/say.txt", "edited");

        final ShellHookOutcome outcome = fire(assembled);

        assertThat(outcome.getUnrunCause()).contains(ShellHookOutcome.Unrun.STAGING_FAILED);
        assertThat(outcome.unrunReason()).contains("changed on disk after it was loaded");
    }

    @Test
    @DisplayName("a skill over the run-directory limit falls back to a staged copy")
    void overTheLimitFallsBack() throws Exception {
        control.write("skills/demo/big.bin", new byte[(int) HookRunFiles.MAX_BYTES + 1]);
        final ShellHookOutcome outcome = fire(StagedResource.scan(control, "skills/demo", "demo"));

        assertThat(Path.of(dirOf(outcome)).toRealPath()).startsWith(workspace.toRealPath());
        assertThat(contentOf(outcome)).isEqualTo("loaded");
    }

    // --- the fallback ------------------------------------------------------------------------------------------

    @Test
    @DisplayName("on a shell without RUN_DIRECTORY: the staged path, one WARN for any number of firings")
    void fallbackStagesAndWarnsOnce() throws Exception {
        final ListAppender<ILoggingEvent> log = capture(DefaultShellActionExecutor.class);
        final ExecutionEnvironment older = withoutRunDirectory(environment);
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");

        final ShellHookOutcome first = fire(resource, older);
        final ShellHookOutcome second = fire(resource, older);

        final Path staged = Path.of(dirOf(first));
        assertThat(staged.toRealPath()).startsWith(
                workspace.resolve(LocalExecutionEnvironmentProvider.DEFAULT_STAGING_ROOT).resolve("demo").toRealPath());
        assertThat(dirOf(second)).isEqualTo(dirOf(first));
        assertThat(warnings(log, "run from a staged copy")).isEqualTo(1);
    }

    @Test
    @DisplayName("on a shell without RUN_DIRECTORY, a staged copy rewritten between firings is copied again")
    void fallbackCopyRewrittenIsCopiedAgain() throws Exception {
        final ExecutionEnvironment older = withoutRunDirectory(environment);
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");
        final Path staged = Path.of(dirOf(fire(resource, older)));
        Files.writeString(staged.resolve("scripts/say.txt"), "tampered");

        assertThat(contentOf(fire(resource, older))).isEqualTo("loaded");
    }

    // --- an observer event -------------------------------------------------------------------------------------

    @Test
    @DisplayName("postTool: HOOK_FILES_TAMPERED is logged and the tool result stands, failOpen or not")
    void tamperOnAnObserverEventIsOnlyLogged() {
        final ShellActionExecutor tampered = new ShellActionExecutor() {
            @Override
            public boolean isShellSupported() {
                return true;
            }

            @Override
            public boolean requiresExecutionEnvironment() {
                return true;
            }

            @Override
            public ShellHookOutcome run(ShellAction action, HookContext context, Map<String, String> overrides,
                    String stdinPayload) {
                return ShellHookOutcome.notRun(ShellHookOutcome.Unrun.HOOK_FILES_TAMPERED, "");
            }
        };
        final DeclarativePostToolHook hook = new DeclarativePostToolHook("demo", NameOnlyPredicate.ANY, SAY, tampered);
        final StagedResource resource = StagedResource.scan(control, "skills/demo", "demo");
        final SkillScopedHookRegistry view = new SkillScopedHookRegistry(new DefaultHookRegistry(), "demo",
                SkillHookSet.builder().addPostTool(hook).build(), resource);

        final HookResult result = hook.execute(
                PostToolContext.builder().executorType(InvokerType.SUBAGENT).invokerName("worker").hookRegistry(view)
                        .executionEnvironment(environment).toolUse(ToolUse.of("call-1", "Read", Map.of()))
                        .toolUseResult(ToolUseResult.success("call-1", "ok")).iterationCount(1).build());

        assertThat(result.getStatus()).isEqualTo(HookStatus.SUCCESS);
    }

    /** The environment with a shell that does not declare RUN_DIRECTORY, as a sandbox shell before EE-109. */
    static ExecutionEnvironment withoutRunDirectory(ExecutionEnvironment environment) {
        final VirtualShell shell = environment.shell();
        final VirtualShell older = new VirtualShell() {
            @Override
            public ShellCommandResult execute(ShellCommand command) throws ShellExecutionException {
                return shell.execute(command);
            }

            @Override
            public ShellCommandResult execute(ShellCommand command, ExecutionOptions options)
                    throws ShellExecutionException {
                return shell.execute(command, options);
            }

            @Override
            public String getWorkingDirectory() {
                return shell.getWorkingDirectory();
            }

            @Override
            public boolean supports(ShellFeature feature) {
                return feature != ShellFeature.RUN_DIRECTORY && shell.supports(feature);
            }

            @Override
            public void close() {
            }
        };
        return new ExecutionEnvironment() {
            @Override
            public VirtualFileSystem fileSystem() {
                return environment.fileSystem();
            }

            @Override
            public VirtualShell shell() {
                return older;
            }

            @Override
            public EnvironmentDescriptor descriptor() {
                return environment.descriptor();
            }

            @Override
            public String stage(StagedResource resource) {
                return environment.stage(resource);
            }
        };
    }
}
