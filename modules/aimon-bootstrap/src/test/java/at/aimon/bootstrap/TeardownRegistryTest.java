package at.aimon.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.bootstrap.exception.AimonTeardownException;

/**
 * The teardown contract, asserted rather than described.
 *
 * <p>
 * These are the tests that would have caught the CLI defects this module exists to fix: an entry that throws and
 * silently strands every later entry, and a {@code close()} that runs twice because a container both registered
 * a shutdown hook and called a destroy method.
 */
class TeardownRegistryTest {

    /** Records the order in which resources were closed, and can be told to fail. */
    private static final class RecordingResource implements AutoCloseable {

        private final List<String> log;
        private final String name;
        private final RuntimeException failure;

        RecordingResource(List<String> log, String name) {
            this(log, name, null);
        }

        RecordingResource(List<String> log, String name, RuntimeException failure) {
            this.log = log;
            this.name = name;
            this.failure = failure;
        }

        @Override
        public void close() {
            log.add(name);
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Test
    @DisplayName("closes phases in TeardownPhase declaration order, not registration order")
    void closesInPhaseOrder() {
        final List<String> closed = new ArrayList<>();
        final TeardownRegistry teardown = new TeardownRegistry();

        // Registered in deliberately scrambled phase order: the plan must not follow this.
        teardown.own(TeardownPhase.SKILL_HOOK_SHELL, "shell", new RecordingResource(closed, "shell"));
        teardown.own(TeardownPhase.SESSIONS, "router", new RecordingResource(closed, "router"));
        teardown.own(TeardownPhase.SCHEDULING, "scheduler", new RecordingResource(closed, "scheduler"));
        teardown.own(TeardownPhase.CHECKPOINTS, "checkpoints", new RecordingResource(closed, "checkpoints"));
        teardown.own(TeardownPhase.AGENT_RUNTIMES, "runtime", new RecordingResource(closed, "runtime"));

        teardown.closeAll();

        assertThat(closed).containsExactly("router", "checkpoints", "runtime", "scheduler", "shell");
    }

    @Test
    @DisplayName("closes entries within one phase in reverse registration order")
    void closesReverseWithinPhase() {
        final List<String> closed = new ArrayList<>();
        final TeardownRegistry teardown = new TeardownRegistry();

        teardown.own(TeardownPhase.AGENT_RESOURCES, "first", new RecordingResource(closed, "first"));
        teardown.own(TeardownPhase.AGENT_RESOURCES, "second", new RecordingResource(closed, "second"));
        teardown.own(TeardownPhase.AGENT_RESOURCES, "third", new RecordingResource(closed, "third"));

        teardown.closeAll();

        // Last constructed is first closed — the same reason try-with-resources unwinds backwards: a later
        // resource may have been built on top of an earlier one.
        assertThat(closed).containsExactly("third", "second", "first");
    }

    @Test
    @DisplayName("a failing entry does not prevent later entries from closing")
    void isolatesFailures() {
        final List<String> closed = new ArrayList<>();
        final TeardownRegistry teardown = new TeardownRegistry();

        teardown.own(TeardownPhase.SESSIONS, "router", new RecordingResource(closed, "router"));
        teardown.own(TeardownPhase.SCHEDULING, "scheduler",
                new RecordingResource(closed, "scheduler", new IllegalStateException("scheduler is wedged")));
        teardown.own(TeardownPhase.SKILL_HOOK_SHELL, "shell", new RecordingResource(closed, "shell"));

        assertThatThrownBy(teardown::closeAll).isInstanceOf(AimonTeardownException.class);

        // The point of the whole design: the shell closed even though the scheduler blew up before it.
        assertThat(closed).containsExactly("router", "scheduler", "shell");
    }

    @Test
    @DisplayName("reports every failure, each as a suppressed exception naming its entry")
    void aggregatesFailures() {
        final List<String> closed = new ArrayList<>();
        final TeardownRegistry teardown = new TeardownRegistry();

        teardown.own(TeardownPhase.SESSIONS, "router",
                new RecordingResource(closed, "router", new IllegalStateException("drain timed out")));
        teardown.own(TeardownPhase.REWAKE, "rewake",
                new RecordingResource(closed, "rewake", new IllegalStateException("pool refused to stop")));

        assertThatThrownBy(teardown::closeAll).isInstanceOf(AimonTeardownException.class).satisfies(e -> {
            assertThat(e.getSuppressed()).hasSize(2);
            assertThat(e.getSuppressed()[0]).hasMessageContaining("router");
            assertThat(e.getSuppressed()[1]).hasMessageContaining("rewake");
        });
    }

    @Test
    @DisplayName("closeAll is idempotent — a second call closes nothing again")
    void closeAllIsIdempotent() {
        final List<String> closed = new ArrayList<>();
        final TeardownRegistry teardown = new TeardownRegistry();
        teardown.own(TeardownPhase.SESSIONS, "router", new RecordingResource(closed, "router"));

        teardown.closeAll();
        assertThatCode(teardown::closeAll).doesNotThrowAnyException();

        assertThat(closed).containsExactly("router");
        assertThat(teardown.isClosed()).isTrue();
    }

    @Test
    @DisplayName("registering after closeAll fails loudly rather than leaking")
    void rejectsRegistrationAfterClose() {
        final TeardownRegistry teardown = new TeardownRegistry();
        teardown.closeAll();

        assertThatThrownBy(() -> teardown.own(TeardownPhase.SESSIONS, "late", () -> {
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("already run");
    }

    @Test
    @DisplayName("ownIfPresent skips a null resource without registering anything")
    void ownIfPresentSkipsNull() {
        final TeardownRegistry teardown = new TeardownRegistry();

        final AutoCloseable absent = null;
        assertThat(teardown.ownIfPresent(TeardownPhase.SCHEDULING, "scheduler", absent)).isNull();
        assertThat(teardown.entries()).isEmpty();
    }

    @Test
    @DisplayName("entries() reports the plan without running it")
    void entriesDoesNotClose() {
        final List<String> closed = new ArrayList<>();
        final TeardownRegistry teardown = new TeardownRegistry();
        teardown.own(TeardownPhase.SESSIONS, "router", new RecordingResource(closed, "router"));

        assertThat(teardown.entries()).hasSize(1);
        assertThat(teardown.entries().get(0).getPhase()).isEqualTo(TeardownPhase.SESSIONS);
        assertThat(teardown.entries().get(0).getLabel()).isEqualTo("router");
        assertThat(closed).isEmpty();
        assertThat(teardown.isClosed()).isFalse();
    }
}
