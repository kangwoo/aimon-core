package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.transcript.SessionSnapshot;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.agent.tool.ProgrammaticEngineChoice;
import at.aimon.core.agent.tool.ProgrammaticToolSettings;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.hook.DefaultHookRegistry;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.subagent.behavior.InMemorySubagentBehaviorRegistry;
import at.aimon.core.subagent.execution.SubagentExecutionContext;
import at.aimon.core.subagent.execution.SubagentExecutionResult;
import at.aimon.core.subagent.execution.SubagentExecutor;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The programmatic ceiling: a fork's own {@code programmatic-tools} are narrowed by its caller's settings, and a
 * caller without any is a ceiling of "nothing" (B-DV1).
 */
@DisplayName("DefaultSubagentExecutionManager — caller programmatic ceiling")
class DefaultSubagentExecutionManagerProgrammaticCeilingTest {

    private final SubagentExecutor reactExecutor = mock(SubagentExecutor.class);
    private final ExecutorService bgPool = Executors.newSingleThreadExecutor();

    @BeforeEach
    void setUp() {
        DefaultSubagentExecutionManager.resetProgrammaticWarnings();
    }

    @AfterEach
    void tearDown() {
        bgPool.shutdownNow();
        DefaultSubagentExecutionManager.resetProgrammaticWarnings();
    }

    private static ProgrammaticToolSettings ptc(String... tools) {
        return ProgrammaticToolSettings.builder().tools(List.of(tools)).build();
    }

    private static Subagent subagent(String name, ProgrammaticToolSettings programmatic) {
        return Subagent.builder().name(name).systemPrompt("(inline)").programmatic(programmatic).build();
    }

    @Test
    @DisplayName("a none() ceiling leaves the fork with none()")
    void noneCeilingGivesNone() {
        final Subagent narrowed = DefaultSubagentExecutionManager
                .applyProgrammaticCeiling(ProgrammaticToolSettings.none(), subagent("reader", ptc("Read", "Grep")));

        assertThat(narrowed.getMetadata().getProgrammatic()).isEqualTo(ProgrammaticToolSettings.none());
    }

    @Test
    @DisplayName("the effective settings are the intersection of the fork's and the caller's")
    void intersection() {
        final Subagent narrowed = DefaultSubagentExecutionManager.applyProgrammaticCeiling(ptc("Grep", "Glob"),
                subagent("reader", ptc("Read", "Grep")));

        assertThat(narrowed.getMetadata().getProgrammatic()).isEqualTo(ptc("Grep"));
        assertThat(narrowed.getName()).isEqualTo("reader");
    }

    @Test
    @DisplayName("the same instance comes back when nothing changes")
    void sameInstanceWhenUnchanged() {
        final Subagent wide = subagent("reader", ptc("Read"));
        final Subagent plain = subagent("plain", ProgrammaticToolSettings.none());

        assertThat(DefaultSubagentExecutionManager.applyProgrammaticCeiling(ptc("Read", "Grep"), wide)).isSameAs(wide);
        assertThat(DefaultSubagentExecutionManager.applyProgrammaticCeiling(ProgrammaticToolSettings.none(), plain))
                .isSameAs(plain);
    }

    @Test
    @DisplayName("a fork that opts in and is left with nothing is warned about once per subagent")
    void warnsOncePerSubagent() {
        final List<String> warnings = warnings(() -> {
            DefaultSubagentExecutionManager.applyProgrammaticCeiling(ProgrammaticToolSettings.none(),
                    subagent("reader", ptc("Read")));
            DefaultSubagentExecutionManager.applyProgrammaticCeiling(ptc("Grep"), subagent("reader", ptc("Read")));
            DefaultSubagentExecutionManager.applyProgrammaticCeiling(ProgrammaticToolSettings.none(),
                    subagent("other", ptc("Read")));
            return null;
        });

        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).contains("'reader'").contains("caller allows none");
        assertThat(warnings.get(1)).contains("'other'");
    }

    @Test
    @DisplayName("an engine conflict is warned about under its own key, naming both engines")
    void warnsEngineConflict() {
        final Subagent python = subagent("py", ProgrammaticToolSettings.builder().tools(List.of("Read"))
                .engine(ProgrammaticEngineChoice.PYTHON).build());
        final ProgrammaticToolSettings jsCaller = ProgrammaticToolSettings.builder().tools(List.of("Read"))
                .engine(ProgrammaticEngineChoice.JAVASCRIPT).build();

        final List<String> warnings = warnings(() -> {
            assertThat(DefaultSubagentExecutionManager.applyProgrammaticCeiling(jsCaller, python).getMetadata()
                    .getProgrammatic()).isEqualTo(ProgrammaticToolSettings.none());
            DefaultSubagentExecutionManager.applyProgrammaticCeiling(jsCaller, python);
            // the caller-ceiling key for the same subagent is separate
            DefaultSubagentExecutionManager.applyProgrammaticCeiling(ProgrammaticToolSettings.none(), python);
            return null;
        });

        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).contains("'py'").contains("python").contains("javascript");
        assertThat(warnings.get(1)).contains("'py'").contains("caller allows none");
    }

    @Test
    @DisplayName("no warning for a fork that never opted in")
    void noWarningWithoutOptIn() {
        assertThat(warnings(() -> DefaultSubagentExecutionManager.applyProgrammaticCeiling(ptc("Read"),
                subagent("plain", ProgrammaticToolSettings.none())))).isEmpty();
    }

    @Test
    @DisplayName("through the launch path: a caller with no allow-list and none() still narrows the fork to none()")
    void emptyAllowListCallerStillNarrows() {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult());

        new DefaultSubagentExecutionManager(reactExecutor, bgPool, null, new InMemorySubagentBehaviorRegistry())
                .execute(baseEnv().build(), subagent("reader", ptc("Read", "Grep")), "go");

        assertThat(capturedContext().getSubagent().getMetadata().getProgrammatic())
                .isEqualTo(ProgrammaticToolSettings.none());
    }

    @Test
    @DisplayName("through the launch path: the caller's settings narrow the fork's")
    void launchPathIntersects() {
        when(reactExecutor.execute(any(), any())).thenReturn(reactResult());

        new DefaultSubagentExecutionManager(reactExecutor, bgPool, null, new InMemorySubagentBehaviorRegistry())
                .execute(baseEnv().callerProgrammatic(ptc("Read")).build(), subagent("reader", ptc("Read", "Grep")),
                        "go");

        assertThat(capturedContext().getSubagent().getMetadata().getProgrammatic()).isEqualTo(ptc("Read"));
    }

    private SubagentExecutionContext capturedContext() {
        final ArgumentCaptor<SubagentExecutionContext> captor = ArgumentCaptor.forClass(SubagentExecutionContext.class);
        verify(reactExecutor).execute(captor.capture(), any());
        return captor.getValue();
    }

    private static List<String> warnings(Supplier<?> action) {
        final Logger logger = (Logger) LoggerFactory.getLogger(DefaultSubagentExecutionManager.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.get();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static SubagentLaunchContext.Builder baseEnv() {
        return SubagentLaunchContext.builder().agentRuntimeId(AgentRuntimeId.of("agent:test"))
                .subagentRegistry(new InMemorySubagentRegistry()).toolRegistry(new DefaultToolRegistry())
                .hookRegistry(new DefaultHookRegistry()).defaultModel(LlmModel.builder().name("gpt-4").build());
    }

    private static SubagentExecutionResult reactResult() {
        final Instant now = Instant.now();
        return SubagentExecutionResult.success("ok", SessionSnapshot.of(SessionId.generate()), ExecutionMetadata
                .builder().iterationCount(1).tokenUsage(TokenUsage.empty()).timestamps(now, now).build());
    }
}
