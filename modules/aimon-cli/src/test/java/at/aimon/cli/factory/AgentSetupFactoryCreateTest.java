package at.aimon.cli.factory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.cli.config.AgentConfig;
import at.aimon.cli.config.CliConfig;
import at.aimon.cli.config.CliSettings;
import at.aimon.cli.config.LlmProviderConfig;
import at.aimon.cli.config.MemoryConfig;
import at.aimon.cli.config.MemoryDreamerConfig;
import at.aimon.cli.config.MemoryDreamerConfig.ScorerConfig;
import at.aimon.cli.factory.AgentSetupFactory.AgentSetup;
import at.aimon.core.llm.LlmClient;

/**
 * {@link AgentSetupFactory#create(CliConfig)} end to end, for two startup behaviours nothing else reaches through
 * {@code create()}: that #92's model check is actually called (#107), and that #105's configuration starts.
 *
 * <p>
 * Side effects, accepted: {@code create()} roots its file system at {@code user.dir} and starts hook hot reload over
 * the real {@code user.home}, so a developer's own {@code ~/.aimon/hooks.json} is read here. The assertions are
 * fragments such files can add to but cannot remove — the main agent always comes from the bundle — and everything the
 * stack started is released by {@link AgentSetup#close()}. The real Anthropic client is built with a placeholder key
 * and sends nothing: no test here runs a turn, and the final derivation on teardown skips an empty transcript.
 */
@DisplayName("AgentSetupFactory.create")
class AgentSetupFactoryCreateTest {

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;
    private final RecordingLlmClientFactory llmClientFactory = new RecordingLlmClientFactory();

    @BeforeEach
    void captureStdout() {
        System.setOut(new PrintStream(stdout));
    }

    @AfterEach
    void restore() throws Exception {
        System.setOut(originalOut);
        llmClientFactory.closeAll();
    }

    private static CliConfig config(String agentName) {
        final LlmProviderConfig llm = new LlmProviderConfig();
        llm.setProvider("anthropic");
        // A placeholder: building the client sends nothing.
        llm.setApiKey("sk-ant-placeholder");
        final AgentConfig agent = new AgentConfig();
        agent.setName(agentName);
        final CliSettings settings = new CliSettings();
        settings.setColorOutput(false);
        final CliConfig config = new CliConfig();
        config.setLlmConfig(llm);
        config.setAgentConfig(agent);
        config.setCliSettings(settings);
        return config;
    }

    @Test
    @DisplayName("#107: the startup model check runs inside create(), and the setup carries the bundle that loaded")
    void startupModelCheckRunsThroughCreate() {
        // #92's reproduction: the llm block switched to Anthropic, agent.name left on the shipped OpenAI bundle.
        final CliConfig config = config("default");
        config.getLlmConfig().setModel("claude-sonnet-4-5");

        try (AgentSetup setup = new AgentSetupFactory(llmClientFactory, null).create(config)) {
            assertThat(stdout.toString()).contains("Agent model:", "model.name: gpt-5.6-terra");
            assertThat(setup.getAgentBundleName()).isEqualTo("default");
        }
    }

    @Test
    @DisplayName("#105: provider anthropic, memory on and no llm.model starts on the client's default and says so")
    void memoryWithoutLlmModelStarts(@TempDir Path tempDir) {
        // The issue's reproduction, plus the reconciler and the LLM-judge dreamer, so every memory component that
        // rejects a null model name is constructed.
        final MemoryConfig memory = new MemoryConfig();
        memory.setWorkspaceId("ws-probe");
        memory.setPeerId("peer-probe");
        memory.setStoragePath(tempDir.resolve("representations.jsonl").toString());
        memory.setBackend("in-memory");
        memory.setReconcilerEnabled(true);
        final ScorerConfig scorer = new ScorerConfig();
        scorer.setType("llm");
        final MemoryDreamerConfig dreamer = new MemoryDreamerConfig();
        dreamer.setEnabled(true);
        dreamer.setScorer(scorer);
        memory.setDreamer(dreamer);
        final CliConfig config = config("default-anthropic");
        config.setMemoryConfig(memory);

        try (AgentSetup setup = new AgentSetupFactory(llmClientFactory, null).create(config)) {
            final String clientDefault = llmClientFactory.created.get(0).getDefaultModelName().orElseThrow();
            // The dreamer swallows its own construction failure into "dreamer disabled", so "dreamer enabled" is what
            // shows it received a name.
            assertThat(stdout.toString()).contains("`llm.model` is not set", "`" + clientDefault + "`",
                    "reconciler enabled", "dreamer enabled").doesNotContain("cannot be null");
            assertThat(setup.getAgentBundleName()).isEqualTo("default-anthropic");
        }
    }

    /**
     * The real factory, keeping each client it builds: the stack is handed the client rather than owning it, so
     * {@link AgentSetup#close()} leaves it open and the test closes it.
     */
    private static final class RecordingLlmClientFactory extends LlmClientFactory {

        private final List<LlmClient> created = new ArrayList<>();

        @Override
        public LlmClient create(LlmProviderConfig config) {
            final LlmClient client = super.create(config);
            created.add(client);
            return client;
        }

        void closeAll() throws Exception {
            for (LlmClient client : created) {
                if (client instanceof AutoCloseable closeable) {
                    closeable.close();
                }
            }
        }
    }
}
