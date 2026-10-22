package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.DefaultAgent;
import at.aimon.core.agent.impl.AgentBundle;
import at.aimon.core.agent.impl.orca.OrcaAgentRuntime;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.session.store.InMemorySessionRecordStore;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.base.Principal;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.tools.ToolContextKeys;
import at.aimon.core.tools.monitor.MonitorEventSink;
import at.aimon.core.tools.monitor.MonitorRequest;
import at.aimon.core.tools.monitor.MonitorSource;
import at.aimon.core.tools.monitor.MonitorSourceFactory;
import at.aimon.core.tools.monitor.MonitorTool;
import at.aimon.session.routing.wake.WakePolicy;

@DisplayName("AimonStack — an idle session is woken by its monitor's events")
class AimonStackIdleWakeTest {

    private static final SessionId SESSION = SessionId.of("watched");

    /** A source the test fires by hand. */
    private static final class ManualSource implements MonitorSourceFactory {

        private volatile MonitorEventSink sink;

        @Override
        public String kind() {
            return "manual";
        }

        @Override
        public String describe() {
            return "manual — fired by the test";
        }

        @Override
        public MonitorSource create(MonitorRequest request) {
            return started -> {
                sink = started;
                return () -> {
                };
            };
        }
    }

    @Test
    @DisplayName("with waking on, a monitor event opens a turn whose input is the event, read by the model")
    void monitorEventWakesTheSession(@TempDir Path workspace) {
        final List<List<Message>> calls = new CopyOnWriteArrayList<>();
        final LlmClient llm = new LlmClient() {
            @Override
            public LlmResponse sendMessage(String systemPrompt, List<Message> messages, List<ToolDefinition> tools,
                    LlmModel modelConfig) {
                calls.add(List.copyOf(messages));
                return LlmResponse.text("looking into it");
            }

            @Override
            public String getProviderName() {
                return "recording";
            }
        };
        final ManualSource source = new ManualSource();
        final InMemorySessionRecordStore records = new InMemorySessionRecordStore();
        records.provision(SESSION, "ops", Principal.user("alice"));
        final AimonStackSpec spec = AimonStackSpec.builder().workspaceRoot(workspace.toString()).llm(LlmSpec.of(llm))
                .agent(AgentSpec.of(AgentBundle.builder()
                        .agent(DefaultAgent.builder().name("ops").systemPrompt("You are a test agent.").maxIterations(5)
                                .build())
                        .build()))
                .tools(ToolSpec.builder().monitorEnabled(true).addMonitorSource(source).build())
                .session(SessionSpec.builder().recordStore(records)
                        .wake(WakePolicy.builder().debounce(Duration.ofMillis(50)).build()).build())
                .build();

        try (AimonStack stack = AimonStackBuilder.build(spec)) {
            final OrcaAgentRuntime runtime = stack.agentRuntimeRegistry().get(AgentRuntimeId.fromName("ops"))
                    .map(OrcaAgentRuntime.class::cast).orElseThrow();
            final Tool monitor = runtime.getToolRegistry().findByName(MonitorTool.TOOL_NAME).orElseThrow();
            final ToolResult started = monitor.execute(
                    ToolInput.of(Map.of("description", "deploy errors", "source", "manual")),
                    ToolContext.builder().put(ToolContextKeys.AGENT_RUNTIME_ID, runtime.getId())
                            .put(ToolContextKeys.SESSION_ID, SESSION).build());
            assertThat(started.isSuccess()).as(started.getContent()).isTrue();

            source.sink.emit("ERROR connection refused");

            await(() -> !calls.isEmpty());
            final List<Message> woken = calls.get(0);
            assertThat(woken.get(woken.size() - 1).getContent()).contains("background-wake")
                    .contains("ERROR connection refused");
            await(() -> records.load(SESSION).orElseThrow().getSessionTotals().getTurnCount() == 1);
        }
    }

    private static void await(BooleanSupplier condition) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 15s");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
