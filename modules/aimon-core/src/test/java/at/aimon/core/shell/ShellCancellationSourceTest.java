package at.aimon.core.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShellCancellationSource Tests")
class ShellCancellationSourceTest {

    @Test
    @DisplayName("cancel() trips the signal once: the first call runs the listeners, later calls do nothing")
    void cancel_isSingleShot() {
        ShellCancellationSource source = ShellCancellationSource.create();
        AtomicInteger runs = new AtomicInteger();
        source.token().onCancel(runs::incrementAndGet);

        assertThat(source.token().isCancelled()).isFalse();
        assertThat(source.cancel()).isTrue();
        assertThat(source.cancel()).isFalse();

        assertThat(source.token().isCancelled()).isTrue();
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("A listener registered after cancellation runs at once, on the registering thread")
    void onCancel_afterCancellation_runsImmediately() {
        ShellCancellationSource source = ShellCancellationSource.create();
        source.cancel();
        List<Thread> ranOn = new ArrayList<>();

        source.token().onCancel(() -> ranOn.add(Thread.currentThread()));

        assertThat(ranOn).containsExactly(Thread.currentThread());
    }

    @Test
    @DisplayName("A removed listener does not run, and removing twice is harmless")
    void registration_remove_dropsTheListener() {
        ShellCancellationSource source = ShellCancellationSource.create();
        AtomicInteger removed = new AtomicInteger();
        AtomicInteger kept = new AtomicInteger();
        ShellCancellation.Registration registration = source.token().onCancel(removed::incrementAndGet);
        source.token().onCancel(kept::incrementAndGet);

        registration.remove();
        registration.remove();
        source.cancel();

        assertThat(removed).hasValue(0);
        assertThat(kept).hasValue(1);
    }

    @Test
    @DisplayName("A listener that throws does not stop the others, and cancel() does not throw")
    void cancel_survivesAThrowingListener() {
        ShellCancellationSource source = ShellCancellationSource.create();
        AtomicInteger after = new AtomicInteger();
        source.token().onCancel(() -> {
            throw new IllegalStateException("listener failed");
        });
        source.token().onCancel(after::incrementAndGet);

        assertThatCode(source::cancel).doesNotThrowAnyException();

        assertThat(after).hasValue(1);
        // Registering on the already-cancelled signal runs the listener inline; that must not throw either.
        assertThatCode(() -> source.token().onCancel(() -> {
            throw new IllegalStateException("late listener failed");
        })).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("none() is never cancelled and keeps no listener")
    void none_isInert() {
        AtomicInteger runs = new AtomicInteger();

        ShellCancellation.Registration registration = ShellCancellation.none().onCancel(runs::incrementAndGet);
        registration.remove();

        assertThat(ShellCancellation.none().isCancelled()).isFalse();
        assertThat(runs).hasValue(0);
        assertThat(ShellCancellation.none()).isSameAs(ShellCancellation.none());
    }
}
