package at.aimon.sample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import at.aimon.bootstrap.AimonStack;
import at.aimon.core.agent.AgentExecutionResult;
import at.aimon.core.agent.session.LiveSessionOptions;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.scheduling.ScheduledTask;
import at.aimon.core.scheduling.SchedulingEngine;
import at.aimon.spring.boot.AimonSessions;

/**
 * The two observations the live profile needs and the packaging tier does not.
 *
 * <p>
 * Kept out of {@link IntrospectionController} rather than folded into it. That class is the packaging tier's only
 * instrument and its value comes from being small enough to trust; endpoints that exist to exercise a
 * configuration it never runs under would make it answer for two things at once. Profiled to {@code live} for the
 * same reason — under the default profile {@code /aimon/scheduled} would report an empty list forever, since the
 * server default is {@code aimon.scheduling.backend=none}, and an endpoint that can only say "nothing" is worse
 * than no endpoint.
 *
 * <p>
 * Both are deliberately second opinions rather than conveniences. A {@code schedule_task} turn already reports
 * whether the tool succeeded; {@code /aimon/scheduled} reads the application-scoped manager instead, which is
 * what shows the task outlived the turn that registered it. A caller can already run turns one at a time;
 * {@code /aimon/turns} runs them at once, which is what shows two sessions over one agent runtime do not collide.
 */
@RestController
@Profile("live")
public class LiveScenarioController {

    private final AimonStack stack;
    private final AimonSessions sessions;

    /**
     * Creates the controller over the assembled stack.
     *
     * @param stack
     *            the assembled AIMON stack, read for its scheduling engine (must not be null)
     * @param sessions
     *            the session facade used to run turns (must not be null)
     */
    public LiveScenarioController(AimonStack stack, AimonSessions sessions) {
        this.stack = Objects.requireNonNull(stack, "Stack cannot be null");
        this.sessions = Objects.requireNonNull(sessions, "Sessions cannot be null");
    }

    /**
     * Runs the same input under several session ids at once and reports every answer.
     *
     * <p>
     * The sessions share one {@code AgentRuntime} — that is what agent scope means, and it is the part a server
     * assembly is most able to get wrong, because the CLI only ever has one session to be confused about. Running
     * the turns concurrently rather than in a loop is the whole point: sequential turns would pass over a runtime
     * that was not safe to share.
     *
     * @param count
     *            how many sessions to run (defaults to 3)
     * @param input
     *            the input every session receives (defaults to a directive that writes a per-session file)
     * @return one entry per session, each with its answer or its error
     */
    @PostMapping("/aimon/turns")
    public Map<String, Object> turns(@RequestParam(defaultValue = "3") int count,
            @RequestParam(defaultValue = "bash: echo concurrent") String input) {
        final int sessionCount = Math.max(1, Math.min(count, 16));
        final ExecutorService pool = Executors.newFixedThreadPool(sessionCount);
        try {
            final List<CompletableFuture<Map<String, Object>>> pending = new ArrayList<>();
            for (int i = 0; i < sessionCount; i++) {
                final String sessionId = "live-" + i;
                pending.add(CompletableFuture.supplyAsync(() -> runOne(sessionId, input), pool));
            }

            final Map<String, Object> answers = new LinkedHashMap<>();
            for (CompletableFuture<Map<String, Object>> future : pending) {
                final Map<String, Object> answer = future.join();
                answers.put(String.valueOf(answer.get("session")), answer);
            }

            final Map<String, Object> response = new LinkedHashMap<>();
            response.put("sessionCount", sessionCount);
            response.put("answers", answers);
            return response;
        } finally {
            pool.shutdown();
        }
    }

    /**
     * Runs one turn against a named agent rather than the configured default.
     *
     * <p>
     * The approval axis needs this. Its two probe skills live in the {@code approval} bundle and not in
     * {@code sample}, because the packaging tests assert the {@code sample} agent's skill list exactly — and
     * because the two skills already there can never reach the approval chain: the rule policy allows any
     * {@code INLINE} skill with no per-skill hooks before the configured mode is consulted, and both are that.
     * The probes each declare a per-skill hook precisely so the decision falls through to the channel.
     *
     * @param agent
     *            the agent ref to run under (defaults to {@code approval})
     * @param session
     *            the session id to run under (defaults to {@code axis-approval})
     * @param input
     *            the user input for the turn
     * @return the answer, or the error the turn failed with
     */
    @PostMapping("/aimon/turn-as")
    public Map<String, Object> turnAs(@RequestParam(defaultValue = "approval") String agent,
            @RequestParam(defaultValue = "axis-approval") String session,
            @RequestParam(defaultValue = "skill: guarded-open") String input) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("agent", agent);
        answer.put("session", session);
        try {
            final AgentExecutionResult result = sessions.submit(SessionId.of(session), agent, input,
                    LiveSessionOptions.defaults());
            answer.put("success", result.isSuccess());
            answer.put("answer", result.getFinalAnswer());
            answer.put("error", result.getErrorMessage());
        } catch (RuntimeException e) {
            // A refused skill is an outcome to report, not a 500 — the refusal is what the caller came to see.
            answer.put("success", false);
            answer.put("answer", null);
            answer.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return answer;
    }

    /**
     * Reports what the application-scoped task manager holds.
     *
     * @return the registered tasks, or a note that no scheduling backend was selected
     */
    @GetMapping("/aimon/scheduled")
    public Map<String, Object> scheduled() {
        final Map<String, Object> response = new LinkedHashMap<>();
        final SchedulingEngine engine = stack.schedulingEngine().orElse(null);
        response.put("schedulingEnabled", engine != null);
        if (engine == null) {
            response.put("tasks", List.of());
            return response;
        }

        final List<Map<String, Object>> tasks = new ArrayList<>();
        for (ScheduledTask task : engine.getTaskManager().listAll()) {
            final Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", task.getId().value());
            view.put("name", task.getName());
            view.put("cronExpression", task.getCronExpression());
            view.put("timezone", task.getTimezone());
            view.put("enabled", task.isEnabled());
            // The bound runtime id is the reason a cron re-fire still resolves an agent after the session that
            // registered the task is gone. Reporting it is how a caller sees that it is agent-scoped, not a
            // session id that happened to survive.
            view.put("boundRuntimeId", task.getBoundRuntimeId().value());
            view.put("routineSteps", task.getRoutine().size());
            tasks.add(view);
        }
        response.put("tasks", tasks);
        return response;
    }

    private Map<String, Object> runOne(String sessionId, String input) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("session", sessionId);
        try {
            final AgentExecutionResult result = sessions.submit(SessionId.of(sessionId), input);
            answer.put("success", result.isSuccess());
            answer.put("answer", result.getFinalAnswer());
            answer.put("error", result.getErrorMessage());
        } catch (RuntimeException e) {
            // One session failing must not take the other answers with it — the comparison between them is the
            // observation, and a 500 would discard it.
            answer.put("success", false);
            answer.put("answer", null);
            answer.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return answer;
    }
}
