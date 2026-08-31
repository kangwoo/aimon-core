package at.aimon.core.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.base.Principal;
import at.aimon.core.subagent.task.BackgroundTask;
import at.aimon.core.subagent.task.BackgroundTaskState;
import at.aimon.core.subagent.task.TaskQuery;

/**
 * Unit tests for {@link ScopedSubagentTaskController} — the control-plane decorator that isolates concurrently-running
 * agents that share a {@code BackgroundTaskStore}.
 */
@DisplayName("ScopedSubagentTaskController")
class ScopedSubagentTaskControllerTest {

    private static final AgentRuntimeId CTX_A = AgentRuntimeId.of("agent:alpha");
    private static final AgentRuntimeId CTX_B = AgentRuntimeId.of("agent:bravo");

    private FakeController delegate;
    private ScopedSubagentTaskController scopedToA;

    @BeforeEach
    void setUp() {
        delegate = new FakeController();
        scopedToA = new ScopedSubagentTaskController(delegate, CTX_A);
    }

    private static BackgroundTask task(String id, BackgroundTaskState state, AgentRuntimeId ctx) {
        final BackgroundTask.Builder b = BackgroundTask.builder().taskId(id).subagentName("explore").description("desc")
                .state(state).startTime(Instant.parse("2026-07-08T10:00:00Z"));
        if (ctx != null) {
            b.agentRuntimeId(ctx);
        }
        return b.build();
    }

    @Nested
    @DisplayName("list")
    class ListScoping {

        @Test
        @DisplayName("returns only tasks belonging to the bound context")
        void listReturnsOnlyBoundContext() {
            delegate.add(task("a1", BackgroundTaskState.RUNNING, CTX_A));
            delegate.add(task("b1", BackgroundTaskState.RUNNING, CTX_B));
            delegate.add(task("n1", BackgroundTaskState.RUNNING, null));

            assertThat(scopedToA.list(TaskQuery.all())).extracting(BackgroundTask::getTaskId).containsExactly("a1");
        }

        @Test
        @DisplayName("preserves the caller's state criterion alongside the forced context")
        void listPreservesStateCriterion() {
            delegate.add(task("a-run", BackgroundTaskState.RUNNING, CTX_A));
            delegate.add(task("a-pend", BackgroundTaskState.PENDING, CTX_A));
            delegate.add(task("b-run", BackgroundTaskState.RUNNING, CTX_B));

            assertThat(scopedToA.list(TaskQuery.byState(BackgroundTaskState.RUNNING)))
                    .extracting(BackgroundTask::getTaskId).containsExactly("a-run");
        }

        @Test
        @DisplayName("preserves the caller's owner criterion alongside the forced context")
        void listPreservesOwnerCriterion() {
            final Principal alice = Principal.user("alice");
            delegate.add(task("a-alice", BackgroundTaskState.RUNNING, CTX_A).toBuilder().owner(alice).build());
            delegate.add(
                    task("a-bob", BackgroundTaskState.RUNNING, CTX_A).toBuilder().owner(Principal.user("bob")).build());

            assertThat(scopedToA.list(TaskQuery.builder().owner(alice).build())).extracting(BackgroundTask::getTaskId)
                    .containsExactly("a-alice");
        }

        @Test
        @DisplayName("cannot be widened to another context via the query — the bound context always wins")
        void listOverridesForeignContextCriterion() {
            delegate.add(task("a1", BackgroundTaskState.RUNNING, CTX_A));
            delegate.add(task("b1", BackgroundTaskState.RUNNING, CTX_B));

            // A caller asking for context B still only sees its own context A tasks.
            assertThat(scopedToA.list(TaskQuery.byAgentRuntime(CTX_B))).extracting(BackgroundTask::getTaskId)
                    .containsExactly("a1");
        }
    }

    @Nested
    @DisplayName("status")
    class StatusScoping {

        @Test
        @DisplayName("returns a task in the bound context")
        void statusReturnsOwnTask() {
            delegate.add(task("a1", BackgroundTaskState.RUNNING, CTX_A));

            assertThat(scopedToA.status("a1")).map(BackgroundTask::getTaskId).contains("a1");
        }

        @Test
        @DisplayName("hides a task belonging to another context")
        void statusHidesForeignTask() {
            delegate.add(task("b1", BackgroundTaskState.RUNNING, CTX_B));

            assertThat(scopedToA.status("b1")).isEmpty();
        }

        @Test
        @DisplayName("hides a task with no context (defensive)")
        void statusHidesContextlessTask() {
            delegate.add(task("n1", BackgroundTaskState.RUNNING, null));

            assertThat(scopedToA.status("n1")).isEmpty();
        }
    }

    @Nested
    @DisplayName("stop")
    class StopScoping {

        @Test
        @DisplayName("stops a task in the bound context and delegates the stop")
        void stopOwnTask() {
            delegate.add(task("a1", BackgroundTaskState.RUNNING, CTX_A));

            assertThat(scopedToA.stop("a1")).isTrue();
            assertThat(delegate.stopped).containsExactly("a1");
        }

        @Test
        @DisplayName("denies stopping a task owned by another context and never reaches the delegate")
        void stopForeignTaskDenied() {
            delegate.add(task("b1", BackgroundTaskState.RUNNING, CTX_B));

            assertThat(scopedToA.stop("b1")).isFalse();
            assertThat(delegate.stopped).isEmpty();
        }

        @Test
        @DisplayName("returns false for an unknown task")
        void stopUnknownTask() {
            assertThat(scopedToA.stop("ghost")).isFalse();
            assertThat(delegate.stopped).isEmpty();
        }
    }

    @Nested
    @DisplayName("scopeOrPassThrough")
    class PassThrough {

        @Test
        @DisplayName("returns the raw delegate unchanged when no context id is present")
        void passesThroughWhenAbsent() {
            assertThat(ScopedSubagentTaskController.scopeOrPassThrough(delegate, Optional.empty())).isSameAs(delegate);
        }

        @Test
        @DisplayName("wraps and scopes when a context id is present")
        void wrapsWhenPresent() {
            delegate.add(task("a1", BackgroundTaskState.RUNNING, CTX_A));
            delegate.add(task("b1", BackgroundTaskState.RUNNING, CTX_B));

            final SubagentTaskController scoped = ScopedSubagentTaskController.scopeOrPassThrough(delegate,
                    Optional.of(CTX_A));

            assertThat(scoped).isInstanceOf(ScopedSubagentTaskController.class);
            assertThat(scoped.list(TaskQuery.all())).extracting(BackgroundTask::getTaskId).containsExactly("a1");
        }
    }

    @Test
    @DisplayName("constructor and factory reject null arguments")
    void rejectsNulls() {
        assertThatNullPointerException().isThrownBy(() -> new ScopedSubagentTaskController(null, CTX_A));
        assertThatNullPointerException().isThrownBy(() -> new ScopedSubagentTaskController(delegate, null));
        assertThatNullPointerException()
                .isThrownBy(() -> ScopedSubagentTaskController.scopeOrPassThrough(null, Optional.of(CTX_A)));
        assertThatNullPointerException()
                .isThrownBy(() -> ScopedSubagentTaskController.scopeOrPassThrough(delegate, null));
    }

    /**
     * In-memory {@link SubagentTaskController} that applies {@link TaskQuery#matches} over a fixed task set and records
     * which task ids received a stop, so tests can assert the decorator's authorization decisions.
     */
    private static final class FakeController implements SubagentTaskController {

        private final List<BackgroundTask> tasks = new ArrayList<>();
        private final List<String> stopped = new ArrayList<>();

        void add(BackgroundTask task) {
            tasks.add(task);
        }

        @Override
        public List<BackgroundTask> list(TaskQuery query) {
            final List<BackgroundTask> out = new ArrayList<>();
            for (BackgroundTask t : tasks) {
                if (query.matches(t)) {
                    out.add(t);
                }
            }
            return out;
        }

        @Override
        public Optional<BackgroundTask> status(String taskId) {
            return tasks.stream().filter(t -> t.getTaskId().equals(taskId)).findFirst();
        }

        @Override
        public boolean stop(String taskId) {
            final Optional<BackgroundTask> t = status(taskId);
            if (t.isEmpty() || t.get().getState().isTerminal()) {
                return false;
            }
            stopped.add(taskId);
            return true;
        }
    }
}
