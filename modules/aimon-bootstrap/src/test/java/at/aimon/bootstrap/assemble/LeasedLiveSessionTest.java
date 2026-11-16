package at.aimon.bootstrap.assemble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.bootstrap.runtime.AgentRuntimeLease;
import at.aimon.core.agent.interrupt.InterruptReason;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.session.TurnId;

/**
 * Guards the one thing a delegating wrapper gets wrong silently.
 *
 * <p>
 * {@link LiveSession} is mostly {@code default} methods. A wrapper that forgets one still compiles and still
 * runs — it just answers for itself instead of the session it wraps, and the failure shows up as a session that
 * reports itself permanently idle or drops an interrupt. There is no compiler error to wait for, so the check has
 * to be a test.
 */
class LeasedLiveSessionTest {

    @Test
    @DisplayName("overrides every method of LiveSession, defaults included")
    void everyInterfaceMethodIsDelegated() {
        final List<Method> notDelegated = Arrays.stream(LiveSession.class.getMethods())
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !isDeclaredOn(LeasedLiveSession.class, m)).toList();

        assertThat(notDelegated)
                .as("LeasedLiveSession must override these, or they will answer for the wrapper rather than the"
                        + " session it wraps")
                .isEmpty();
    }

    @Test
    @DisplayName("forwards an interrupt's detail to the wrapped session, in both forms")
    void interruptDetailIsForwarded() {
        final LiveSession delegate = mock(LiveSession.class);
        final LeasedLiveSession wrapper = new LeasedLiveSession(delegate, mock(AgentRuntimeLease.class));
        final TurnId turnId = TurnId.of("turn-1");

        wrapper.interrupt(InterruptReason.USER_SIGINT, "wrong directory");
        wrapper.interrupt(turnId, InterruptReason.USER_SIGINT, "deploy freeze");

        // Declaring the override is what the test above checks; this checks the override passes the detail on.
        verify(delegate).interrupt(InterruptReason.USER_SIGINT, "wrong directory");
        verify(delegate).interrupt(turnId, InterruptReason.USER_SIGINT, "deploy freeze");
    }

    private static boolean isDeclaredOn(Class<?> type, Method method) {
        try {
            type.getDeclaredMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
