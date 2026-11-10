package at.aimon.core.llms.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigParam;

import at.aimon.core.llm.LlmModel;
import at.aimon.core.llm.LlmResponse;
import at.aimon.core.llm.Message;
import at.aimon.core.llm.ReasoningEffort;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.llm.ToolUseResult;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The live proof that {@code thinking.block_binding.prefix_mismatch_behavior: "drop_block"} keeps the default path
 * working on the current generation when AIMON edits the prompt prefix.
 *
 * <p>
 * Every request this client sends to {@code claude-sonnet-5-5} / {@code claude-haiku-5-5} carries the binding, and
 * the vendor says that setting the field to either value opts any account into the history-editing check — so the
 * result does not depend on the account's age. The cases:
 *
 * <ul>
 * <li><b>a</b> — a tool-calling turn whose thinking block is captured;
 * <li><b>b</b> — that history replayed under a <em>different system prompt</em> (a system prompt re-rendered on a new
 * turn): 200, and the drop is reported;
 * <li><b>c</b> — the head of that history replaced by a summary while the assistant message keeps its traces (the
 * shape compaction's preserved tail sends): 200, and the drop is reported;
 * <li><b>d</b> — the control: the request of case b with the <em>non-dropping</em> behaviour must be a 400 naming the
 * binding, or b and c prove nothing. The other value's spelling is read off the server's own error for an invalid
 * value rather than guessed. A control that cannot be built <strong>fails</strong>; it never skips;
 * <li><b>e</b> — thinking off and on smoke requests on the default model, and a budgeted request on Haiku 5.5.
 * </ul>
 *
 * <p>
 * Billed, a few cents per model. Gated on {@code ANTHROPIC_KEY} like {@link AnthropicThinkingLiveTest}, so a keyless
 * build skips it; run it with {@code --rerun}. Each case prints one {@code [binding-live]} line to standard output,
 * which the test report keeps, so the results can be recorded.
 */
@DisplayName("AnthropicLlmClient - preserved-thinking block binding against the real API")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_KEY", matches = ".+")
class AnthropicThinkingBindingLiveTest {

    private static final String SYSTEM = "You are a weather assistant. Before calling a tool, decide carefully which "
            + "city the user means. Answer in one sentence.";
    private static final String OTHER_SYSTEM = "You are a terse weather assistant. Answer in at most ten words.";
    // A computation before the tool call, so adaptive thinking has something to think about; a plain lookup is often
    // answered without a thinking block, which would leave nothing to bind.
    private static final String ASK_TOOL = "Compute 47 * 53 * 3 in your head, then call get_weather once with city "
            + "set to the word Station followed by that number, for example Station123.";
    private static final String FOLLOW_UP = "Thanks. Is that warm or cold?";

    private Logger clientLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        clientLogger = (Logger) LoggerFactory.getLogger(AnthropicLlmClient.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        clientLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    private static String key() {
        return System.getenv("ANTHROPIC_KEY");
    }

    private static AnthropicConfig.Builder config(String model) {
        return AnthropicConfig.builder().apiKey(key()).model(model).maxTokens(8000).maxRetries(0);
    }

    private static ToolDefinition weatherTool() {
        return ToolDefinition.of("get_weather", "Get the current weather in a city",
                Map.of("type", "object", "additionalProperties", false, "properties",
                        Map.of("city", Map.of("type", "string", "description", "The city")), "required",
                        List.of("city")));
    }

    private List<String> drops() {
        return logAppender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).filter(w -> w.contains("were dropped by the server")).toList();
    }

    private static void record(String model, String testCase, String outcome) {
        System.out.println("[binding-live] model=" + model + " case=" + testCase + " " + outcome);
    }

    private static String usage(LlmResponse response) {
        return "usage=" + response.getTokenUsage();
    }

    /**
     * Case a: a tool loop under the shipped request shape, run until an assistant message carries a captured thinking
     * block — at most two tool iterations per attempt and three attempts.
     *
     * <p>
     * Measured 2026-11-10: {@code claude-sonnet-5-5} at effort {@code high} called the tool on the first iteration
     * without thinking (0 thinking tokens, three times in a row), and thinks between tools instead; so when the first
     * iteration carries no trace, the tool answers with a retry request and the second iteration is the one captured.
     *
     * @return the conversation, ending on the user message (tool results or a follow-up) after the traced assistant
     *         message
     */
    private static List<Message> capturedConversation(AnthropicLlmClient client, String model) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            final List<Message> messages = new ArrayList<>(List.of(Message.user(ASK_TOOL)));
            for (int iteration = 1; iteration <= 2; iteration++) {
                final LlmResponse response = client.sendMessage(SYSTEM, messages, List.of(weatherTool()),
                        LlmModel.builder().reasoningEffort(ReasoningEffort.HIGH).build());
                record(model, "a-attempt" + attempt + "-iteration" + iteration,
                        "tool_uses=" + response.getToolUses().size() + " traces=" + response.getReasoningTraces().size()
                                + " " + usage(response));
                if (response.getToolUses().isEmpty()) {
                    if (response.getReasoningTraces().isEmpty()) {
                        break;
                    }
                    // A final answer that carries a trace (Sonnet 5.5's usual shape here) is replayed as well: the
                    // conversation continues with a follow-up from the user.
                    messages.add(Message.assistant(response.getTextContent(), List.of())
                            .withReasoningTraces(response.getReasoningTraces()));
                    messages.add(Message.user(FOLLOW_UP));
                    record(model, "a", "status=200 traced_iteration=" + iteration + " (final answer)");
                    return messages;
                }
                // Thinking tokens billed with no captured trace is this client's bug, not the model's choice: red.
                if (response.getReasoningTraces().isEmpty()) {
                    assertThat(response.getTokenUsage().getReasoningTokens())
                            .as("a turn that billed thinking tokens must yield a captured trace; %s",
                                    response.getTokenUsage())
                            .isZero();
                }
                messages.add(Message.assistant(response.getTextContent(), response.getToolUses())
                        .withReasoningTraces(response.getReasoningTraces()));
                final String result = response.getReasoningTraces().isEmpty()
                        ? "Unknown station. Recompute the number carefully, add 7, and call get_weather again."
                        : "18C, clear";
                messages.add(Message.toolUseResults(response.getToolUses().stream()
                        .map(use -> ToolUseResult.success(use.getId(), result)).toList()));
                if (!response.getReasoningTraces().isEmpty()) {
                    record(model, "a", "status=200 traced_iteration=" + iteration);
                    return messages;
                }
            }
        }
        assumeTrue(false, "model chose not to think on any tool iteration in three attempts; nothing to bind");
        return List.of();
    }

    /** Compaction's shape: everything before the last (traced) assistant message replaced by one summary. */
    private static List<Message> compacted(List<Message> conversation) {
        final List<Message> messages = new ArrayList<>();
        messages.add(Message.user("Summary of the conversation so far: the user asked for a computed station's "
                + "weather, and the station number was worked out."));
        messages.addAll(conversation.subList(conversation.size() - 2, conversation.size()));
        return messages;
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-5-5", "claude-haiku-5-5"})
    @DisplayName("a-d: a changed system prompt and a compacted head drop the stale block; the control 400s")
    void prefixEditsDropInsteadOfFailing(String model) throws Exception {
        // AUTO at effort high (from the call) makes a thinking block likely; the binding rides every mode alike.
        try (AnthropicLlmClient client = new AnthropicLlmClient(
                config(model).thinkingMode(AnthropicThinkingMode.AUTO).build())) {
            final List<Message> replay = capturedConversation(client, model);

            // b: the system prompt re-rendered on a new turn.
            logAppender.list.clear();
            final LlmResponse changedSystem = client.sendMessage(OTHER_SYSTEM, replay, List.of(weatherTool()),
                    LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());
            final List<String> dropsB = drops();
            record(model, "b", "status=200 drops=" + dropsB + " " + usage(changedSystem));
            assertThat(dropsB).as("case b: the server dropped the block bound to the old system prompt").isNotEmpty();

            // c: compaction's shape -- the head replaced by a summary, the assistant message keeping its traces. A
            // fresh client, because the drop report is recurring (1st, 10th, 100th ...) per client and signature, so
            // the client that reported case b would stay silent on its second drop.
            logAppender.list.clear();
            try (AnthropicLlmClient fresh = new AnthropicLlmClient(
                    config(model).thinkingMode(AnthropicThinkingMode.AUTO).build())) {
                final LlmResponse afterCompaction = fresh.sendMessage(SYSTEM, compacted(replay), List.of(weatherTool()),
                        LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build());
                final List<String> dropsC = drops();
                record(model, "c", "status=200 drops=" + dropsC + " " + usage(afterCompaction));
                assertThat(dropsC).as("case c: the server dropped the block bound to the summarized head").isNotEmpty();
            }

            // d: the control.
            control(model, replay);
        }
    }

    /**
     * Case d. The same request as case b, sent raw so the binding's value can be the non-dropping one. Fails, never
     * skips, when the control cannot be constructed.
     */
    private static void control(String model, List<Message> replay) {
        final AnthropicClient raw = AnthropicOkHttpClient.builder().apiKey(key()).maxRetries(0).build();
        try {
            final String invalid = "not_a_behavior_probe";
            final String enumeration;
            try {
                raw.messages().create(rawRequest(model, replay, invalid));
                fail("control could not be constructed: the invalid probe value '" + invalid + "' was accepted");
                return;
            } catch (AnthropicServiceException e) {
                // Only the enumeration sentence: the body's JSON keys are quoted too. The server says
                // "...prefix_mismatch_behavior: Input should be 'error' or 'drop_block'".
                final String text = String.valueOf(e.getMessage());
                final int from = text.indexOf("Input should be");
                final int to = from < 0 ? -1 : text.indexOf('"', from);
                enumeration = from < 0 ? text : to < 0 ? text.substring(from) : text.substring(from, to);
            }
            final Set<String> values = new LinkedHashSet<>();
            final Matcher matcher = Pattern.compile("'([a-z][a-z_]+)'").matcher(enumeration);
            while (matcher.find()) {
                values.add(matcher.group(1));
            }
            values.removeAll(Set.of("drop_block", invalid));
            if (values.isEmpty()) {
                fail("control could not be constructed: the server's error names no value other than drop_block: "
                        + enumeration);
            }
            final String other = values.iterator().next();
            record(model, "d-probe", "other_value=" + other + " probe_error=" + enumeration);
            try {
                raw.messages().create(rawRequest(model, replay, other));
                fail("control could not be constructed: prefix_mismatch_behavior '" + other
                        + "' with a changed system prompt was accepted (200), so the probe would not see a 400");
            } catch (AnthropicServiceException e) {
                final String text = String.valueOf(e.getMessage());
                record(model, "d", "status=" + e.statusCode() + " error=" + text);
                if (e.statusCode() != 400 || !text.contains("bound to a different conversation")) {
                    fail("control could not be constructed: expected a 400 naming 'bound to a different "
                            + "conversation', got " + e.statusCode() + ": " + text);
                }
            }
        } finally {
            raw.close();
        }
    }

    private static MessageCreateParams rawRequest(String model, List<Message> replay, String behavior) {
        final ThinkingConfigAdaptive adaptive = ThinkingConfigAdaptive.builder()
                .putAdditionalProperty(AnthropicThinkingResolver.BLOCK_BINDING,
                        JsonValue.from(Map.of(AnthropicThinkingResolver.PREFIX_MISMATCH_BEHAVIOR, behavior)))
                .build();
        return MessageCreateParams.builder().model(model).maxTokens(8000L).system(OTHER_SYSTEM)
                .thinking(ThinkingConfigParam.ofAdaptive(adaptive))
                .messages(new AnthropicMessageConverter().convertMessages(replay, "Anthropic", (s, m, a) -> {
                })).tools(new AnthropicMessageConverter().convertTools(List.of(weatherTool())))
                .putAdditionalHeader(AnthropicLlmClient.ANTHROPIC_BETA, AnthropicThinkingResolution.BLOCK_BINDING_BETA)
                .build();
    }

    @Test
    @DisplayName("e: thinking off and on against claude-sonnet-5-5, and a budgeted request on claude-haiku-5-5")
    void thinkingOffAndOnSmoke() throws Exception {
        for (AnthropicThinkingMode mode : new AnthropicThinkingMode[]{AnthropicThinkingMode.OFF,
                AnthropicThinkingMode.AUTO}) {
            try (AnthropicLlmClient client = new AnthropicLlmClient(
                    config("claude-sonnet-5-5").thinkingMode(mode).build())) {
                final LlmModel model = mode == AnthropicThinkingMode.AUTO
                        ? LlmModel.builder().reasoningEffort(ReasoningEffort.LOW).build()
                        : LlmModel.builder().build();
                final LlmResponse response = client.sendMessage(SYSTEM, List.of(Message.user(FOLLOW_UP)), List.of(),
                        model);
                record("claude-sonnet-5-5", "e-" + mode, "status=200 " + usage(response));
                assertThat(response.getTextContent()).isNotBlank();
            }
        }
        try (AnthropicLlmClient client = new AnthropicLlmClient(config("claude-haiku-5-5")
                .thinkingMode(AnthropicThinkingMode.EXTENDED).thinkingBudgetTokens(2048).build())) {
            final LlmResponse response = client.sendMessage(SYSTEM, List.of(Message.user(FOLLOW_UP)), List.of(),
                    LlmModel.builder().build());
            record("claude-haiku-5-5", "e-EXTENDED", "status=200 " + usage(response));
            assertThat(response.getTextContent()).isNotBlank();
        }
    }
}
