package at.aimon.core.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.tool.DefaultToolRegistry;
import at.aimon.core.command.CommandType;
import at.aimon.core.command.execution.CommandExecutionContext;
import at.aimon.core.command.execution.CommandExecutionResult;
import at.aimon.core.command.execution.direct.DirectCommandExecutionRequest;
import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.rewake.RewakeEnvelope;
import at.aimon.core.hook.rewake.RewakeService;
import at.aimon.core.hook.rewake.RewakeTrigger;
import at.aimon.core.hook.rewake.RewakeTriggerCron;
import at.aimon.core.hook.rewake.RewakeTriggerDelay;
import at.aimon.core.hook.rewake.RewakeTriggerEvent;
import at.aimon.core.llm.LlmModel;

class RewakeListCommandTest {

    @Test
    void constructorRejectsNullService() {
        assertThatThrownBy(() -> new RewakeListCommand(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void hasCorrectMetadata() {
        final RewakeListCommand command = new RewakeListCommand(stubService(List.of()));

        assertThat(command.getName()).isEqualTo("rewakes");
        assertThat(command.getMetadata().getDescription()).hasValue("Display pending async-rewake envelopes");
        assertThat(command.getType()).isEqualTo(CommandType.SYSTEM);
    }

    @Test
    void executeRejectsNullContext() {
        final RewakeListCommand command = new RewakeListCommand(stubService(List.of()));
        assertThatThrownBy(() -> command.execute(null, DirectCommandExecutionRequest.of("")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void executeRejectsNullRequest() {
        final RewakeListCommand command = new RewakeListCommand(stubService(List.of()));
        assertThatThrownBy(() -> command.execute(createContext(), null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rendersEmptyMessageWhenNoneArePending() {
        final RewakeListCommand command = new RewakeListCommand(stubService(List.of()));

        final CommandExecutionResult result = command.execute(createContext(), DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Pending rewakes:");
        assertThat(result.getResponse()).contains("No pending rewakes.");
    }

    @Test
    void rendersDelayEnvelope() {
        final RewakeEnvelope env = envelope("env-delay", "hook-A", new RewakeTriggerDelay(Duration.ofMinutes(5)));

        final CommandExecutionResult result = new RewakeListCommand(stubService(List.of(env))).execute(createContext(),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        final String body = result.getResponse();
        assertThat(body).contains("env-delay");
        assertThat(body).contains("hook:    hook-A");
        assertThat(body).contains("context: agent:agent-x");
        assertThat(body).contains("trigger: delay PT5M");
        assertThat(body).contains("attempt: 1");
        assertThat(body).contains("reason:  waiting-for-rate-limit");
        assertThat(body).contains("Total: 1 pending envelope(s)");
    }

    @Test
    void rendersEventAndCronEnvelopes() {
        final RewakeEnvelope eventEnv = envelope("env-event", "hook-B", new RewakeTriggerEvent("approval", "T-42"));
        final RewakeEnvelope cronEnv = envelope("env-cron", "hook-C",
                new RewakeTriggerCron("0 * * * *", ZoneId.of("UTC")));

        final CommandExecutionResult result = new RewakeListCommand(stubService(List.of(eventEnv, cronEnv)))
                .execute(createContext(), DirectCommandExecutionRequest.of(""));

        final String body = result.getResponse();
        assertThat(body).contains("trigger: event approval:T-42");
        assertThat(body).contains("trigger: cron '0 * * * *' (UTC)");
        assertThat(body).contains("Total: 2 pending envelope(s)");
    }

    @Test
    void renderingSurvivesNullListPending() {
        final RewakeService service = new StubRewakeService(null);

        final CommandExecutionResult result = new RewakeListCommand(service).execute(createContext(),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("No pending rewakes.");
    }

    @Test
    void renderingHandlesServiceException() {
        final RewakeService service = new RewakeService() {
            @Override
            public void schedule(RewakeEnvelope envelope) {
            }

            @Override
            public boolean cancel(String envelopeId) {
                return false;
            }

            @Override
            public int cancelByOriginatingHookId(String originatingHookId) {
                return 0;
            }

            @Override
            public int resolve(String eventType, String eventKey, Map<String, String> payload) {
                return 0;
            }

            @Override
            public List<RewakeEnvelope> listPending() {
                throw new IllegalStateException("backend offline");
            }
        };

        final CommandExecutionResult result = new RewakeListCommand(service).execute(createContext(),
                DirectCommandExecutionRequest.of(""));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getResponse()).contains("Failed to query: backend offline");
    }

    private static RewakeEnvelope envelope(String envelopeId, String hookId, RewakeTrigger trigger) {
        return RewakeEnvelope.builder().envelopeId(envelopeId).agentRuntimeId(AgentRuntimeId.fromName("agent-x"))
                .trigger(trigger).originalEventType(HookEventType.PRE_TOOL).originatingHookId(hookId)
                .firstScheduledAt(Instant.parse("2026-05-09T00:00:00Z")).reason("waiting-for-rate-limit").build();
    }

    private static RewakeService stubService(List<RewakeEnvelope> pending) {
        return new StubRewakeService(pending);
    }

    private CommandExecutionContext createContext() {
        final RewakeListCommand dummy = new RewakeListCommand(stubService(List.of()));
        return CommandExecutionContext.builder().command(dummy).defaultModel(LlmModel.builder().name("test").build())
                .toolRegistry(new DefaultToolRegistry()).build();
    }

    private static final class StubRewakeService implements RewakeService {
        private final List<RewakeEnvelope> pending;

        StubRewakeService(List<RewakeEnvelope> pending) {
            this.pending = pending;
        }

        @Override
        public void schedule(RewakeEnvelope envelope) {
        }

        @Override
        public boolean cancel(String envelopeId) {
            return false;
        }

        @Override
        public int cancelByOriginatingHookId(String originatingHookId) {
            return 0;
        }

        @Override
        public int resolve(String eventType, String eventKey, Map<String, String> payload) {
            return 0;
        }

        @Override
        public List<RewakeEnvelope> listPending() {
            return pending;
        }
    }
}
