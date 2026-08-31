package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;

import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.MemoryConfig;
import at.aimon.cli.config.MemoryDreamerConfig;
import at.aimon.cli.config.MemoryDreamerConfig.EmbeddingScorerConfig;
import at.aimon.cli.config.MemoryDreamerConfig.ScorerConfig;
import at.aimon.cli.factory.AgentSetupFactory.DreamerSubsystem;
import at.aimon.cli.factory.AgentSetupFactory.MemoryWiring;
import at.aimon.cli.repl.OutputFormatter;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.InMemoryRepresentationStore;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.scheduling.quartz.dreamer.DreamerJobRegistrar;

@DisplayName("AgentSetupFactory.buildDreamerSubsystem")
class AgentSetupFactoryDreamerTest {

    private static final String MODEL = "gpt-4o";
    private static final String WORKSPACE_ID = "ws-test";

    private AgentSetupFactory factory;
    private LlmClient llmClient;
    private OutputFormatter outputFormatter;
    private ObservationStore observationStore;
    private RepresentationStore representationStore;
    private DreamerSubsystem subsystem;

    @BeforeEach
    void setUp() {
        factory = new AgentSetupFactory();
        llmClient = mock(LlmClient.class);
        outputFormatter = new OutputFormatter(new CliSettings());
        observationStore = new InMemoryObservationStore();
        representationStore = new InMemoryRepresentationStore();
    }

    @AfterEach
    void tearDown() {
        if (subsystem != null) {
            subsystem.close();
            subsystem = null;
        }
    }

    @Test
    @DisplayName("returns null when memory wiring is disabled")
    void disabledWhenWiringMissing() {
        final MemoryConfig memoryConfig = enabledMemoryConfig();
        memoryConfig.setDreamer(enabledLlmDreamerConfig());

        subsystem = factory.buildDreamerSubsystem(MemoryWiring.disabled(), observationStore, representationStore,
                llmClient, MODEL, memoryConfig, outputFormatter);

        assertThat(subsystem).isNull();
    }

    @Test
    @DisplayName("returns null when dreamer block is absent")
    void disabledWhenBlockAbsent() {
        final MemoryConfig memoryConfig = enabledMemoryConfig();
        // dreamer left null

        subsystem = factory.buildDreamerSubsystem(factory.buildMemoryWiring(memoryConfig), observationStore,
                representationStore, llmClient, MODEL, memoryConfig, outputFormatter);

        assertThat(subsystem).isNull();
    }

    @Test
    @DisplayName("returns null when scorer.type=embedding without embedding apiKey")
    void disabledWhenEmbeddingScorerMissingKey() {
        final MemoryConfig memoryConfig = enabledMemoryConfig();
        final MemoryDreamerConfig dreamer = new MemoryDreamerConfig();
        dreamer.setEnabled(true);
        final ScorerConfig scorer = new ScorerConfig();
        scorer.setType("embedding");
        // embedding sub-config absent → not ready
        dreamer.setScorer(scorer);
        memoryConfig.setDreamer(dreamer);

        subsystem = factory.buildDreamerSubsystem(factory.buildMemoryWiring(memoryConfig), observationStore,
                representationStore, llmClient, MODEL, memoryConfig, outputFormatter);

        assertThat(subsystem).isNull();
    }

    @Test
    @DisplayName("default (LLM) scorer schedules dreamer with no extra credentials")
    void llmScorerSchedulesWhenReady() throws Exception {
        final MemoryConfig memoryConfig = enabledMemoryConfig();
        memoryConfig.setDreamer(enabledLlmDreamerConfig());

        subsystem = factory.buildDreamerSubsystem(factory.buildMemoryWiring(memoryConfig), observationStore,
                representationStore, llmClient, MODEL, memoryConfig, outputFormatter);

        assertThat(subsystem).isNotNull();
        // Quartz scheduler is started and a DreamerJob is registered for the configured workspace under the
        // canonical group used by DreamerJobRegistrar.
        final JobKey jobKey = JobKey.jobKey(WORKSPACE_ID, DreamerJobRegistrar.JOB_GROUP);
        assertThat(subsystem.getScheduler().checkExists(jobKey)).isTrue();
    }

    @Test
    @DisplayName("embedding scorer with apiKey schedules dreamer")
    void embeddingScorerSchedulesWhenReady() throws Exception {
        final MemoryConfig memoryConfig = enabledMemoryConfig();
        memoryConfig.setDreamer(enabledEmbeddingDreamerConfig());

        subsystem = factory.buildDreamerSubsystem(factory.buildMemoryWiring(memoryConfig), observationStore,
                representationStore, llmClient, MODEL, memoryConfig, outputFormatter);

        assertThat(subsystem).isNotNull();
        final JobKey jobKey = JobKey.jobKey(WORKSPACE_ID, DreamerJobRegistrar.JOB_GROUP);
        assertThat(subsystem.getScheduler().checkExists(jobKey)).isTrue();
    }

    private static MemoryConfig enabledMemoryConfig() {
        final MemoryConfig config = new MemoryConfig();
        config.setWorkspaceId(WORKSPACE_ID);
        config.setPeerId("peer-test");
        config.setStoragePath(".aimon/test/memory.jsonl");
        return config;
    }

    private static MemoryDreamerConfig enabledLlmDreamerConfig() {
        // Default scorer is LLM, so no scorer block is required — enabling alone makes the dreamer ready.
        final MemoryDreamerConfig dreamer = new MemoryDreamerConfig();
        dreamer.setEnabled(true);
        dreamer.setCron("0 * * * *");
        return dreamer;
    }

    private static MemoryDreamerConfig enabledEmbeddingDreamerConfig() {
        final MemoryDreamerConfig dreamer = new MemoryDreamerConfig();
        dreamer.setEnabled(true);
        dreamer.setCron("0 * * * *");
        final ScorerConfig scorer = new ScorerConfig();
        scorer.setType("embedding");
        // The OpenAI embedding client only validates the key shape at construction; no network call is made unless
        // the dreamer cycle actually fires. The test cron is hourly, so the job is registered but never runs.
        final EmbeddingScorerConfig emb = new EmbeddingScorerConfig();
        emb.setApiKey("sk-test");
        scorer.setEmbedding(emb);
        dreamer.setScorer(scorer);
        return dreamer;
    }
}
