package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.MemoryConfig;
import at.aimon.cli.factory.AgentSetupFactory.MemoryWiring;
import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.agent.impl.orca.OrcaAgentExecutor;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.session.transcript.DefaultTranscriptManager;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;
import at.aimon.core.agent.session.transcript.TranscriptManager;
import at.aimon.core.llm.Message;
import at.aimon.core.memory.MemoryIngestMode;
import at.aimon.core.memory.deriver.DerivationContext;
import at.aimon.core.memory.deriver.DerivationQueueManager;
import at.aimon.core.memory.deriver.DerivationResult;
import at.aimon.core.memory.deriver.Deriver;

@DisplayName("AgentSetupFactory derivation queue wiring")
class AgentSetupFactoryQueueTest {

    private static final String WORKSPACE_ID = "ws-test";
    private static final String PEER_ID = "peer-test";
    private static final SessionId SESSION_ID = SessionId.of("test-conv");

    private AgentSetupFactory factory;
    private OutputFormatter outputFormatter;
    private Deriver deriver;
    private DerivationQueueManager queue;

    @BeforeEach
    void setUp() {
        factory = new AgentSetupFactory();
        outputFormatter = new OutputFormatter(new CliSettings());
        deriver = mock(Deriver.class);
        when(deriver.derive(any(DerivationContext.class))).thenReturn(DerivationResult.empty());
    }

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.stop();
            queue = null;
        }
    }

    @Test
    @DisplayName("buildDerivationQueue returns null when no deriver is wired")
    void noQueueWithoutDeriver() {
        assertThat(factory.buildDerivationQueue(null)).isNull();
    }

    @Test
    @DisplayName("buildDerivationQueue starts the queue ready to accept work")
    void queueStartsIdle() {
        queue = factory.buildDerivationQueue(deriver);

        assertThat(queue).isNotNull();
        assertThat(queue.stats().getQueueSize()).isZero();
        assertThat(queue.stats().getCompletedTasks()).isZero();
    }

    @Test
    @DisplayName("buildMemoryFinalDerivation returns null when wiring is disabled")
    void disabledWhenWiringMissing() {
        queue = factory.buildDerivationQueue(deriver);

        final Runnable runnable = factory.buildMemoryFinalDerivation(MemoryIngestMode.SESSION_END,
                MemoryWiring.disabled(), queue, mock(OrcaAgentExecutor.class), SESSION_ID, outputFormatter);

        assertThat(runnable).isNull();
    }

    @Test
    @DisplayName("buildMemoryFinalDerivation returns null when queue is missing")
    void disabledWhenQueueMissing() {
        final MemoryConfig memoryConfig = enabledMemoryConfig();

        final Runnable runnable = factory.buildMemoryFinalDerivation(MemoryIngestMode.SESSION_END,
                factory.buildMemoryWiring(memoryConfig), null, mock(OrcaAgentExecutor.class), SESSION_ID,
                outputFormatter);

        assertThat(runnable).isNull();
    }

    @Test
    @DisplayName("close-time runnable enqueues a task that is drained when the queue stops")
    void enqueueAndDrainOnClose() {
        // Wire a real session manager with a single user message; the executor mock just exposes it.
        final TranscriptManager transcriptManager = new DefaultTranscriptManager(new InMemorySessionRecordStore());
        final TranscriptBuffer memory = transcriptManager.initialize(SESSION_ID, "you are an aimon agent");
        memory.addMessage(Message.user("Hi there"));
        // The runnable reloads via initialize(...), which goes through the repository — without an explicit save the
        // reload would return an empty memory and the test would never enqueue a task.
        transcriptManager.save(memory);
        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        when(executor.getTranscriptManager()).thenReturn(transcriptManager);

        queue = factory.buildDerivationQueue(deriver);
        final Runnable runnable = factory.buildMemoryFinalDerivation(MemoryIngestMode.SESSION_END,
                factory.buildMemoryWiring(enabledMemoryConfig()), queue, executor, SESSION_ID, outputFormatter);
        assertThat(runnable).isNotNull();

        runnable.run();

        // Stop drains synchronously: workers must have processed the enqueued task before this returns.
        queue.stop();
        queue = null; // tearDown skips a second stop() call

        verify(deriver, times(1)).derive(any(DerivationContext.class));
    }

    @Test
    @DisplayName("runnable on an empty conversation enqueues nothing")
    void emptyConversationIsNoOp() {
        final TranscriptManager transcriptManager = new DefaultTranscriptManager(new InMemorySessionRecordStore());
        // Initialize without adding any user/assistant messages — only a system prompt is present, which
        // TranscriptBuffer#getMessages excludes from its returned message list.
        transcriptManager.initialize(SESSION_ID, "you are an aimon agent");
        final OrcaAgentExecutor executor = mock(OrcaAgentExecutor.class);
        when(executor.getTranscriptManager()).thenReturn(transcriptManager);

        queue = factory.buildDerivationQueue(deriver);
        final Runnable runnable = factory.buildMemoryFinalDerivation(MemoryIngestMode.SESSION_END,
                factory.buildMemoryWiring(enabledMemoryConfig()), queue, executor, SESSION_ID, outputFormatter);

        runnable.run();

        queue.stop();
        queue = null;

        verify(deriver, times(0)).derive(any(DerivationContext.class));
    }

    private static MemoryConfig enabledMemoryConfig() {
        final MemoryConfig config = new MemoryConfig();
        config.setWorkspaceId(WORKSPACE_ID);
        config.setPeerId(PEER_ID);
        config.setStoragePath(".aimon/test/memory.jsonl");
        return config;
    }
}
