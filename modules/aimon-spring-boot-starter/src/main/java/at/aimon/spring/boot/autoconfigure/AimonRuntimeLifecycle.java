package at.aimon.spring.boot.autoconfigure;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import at.aimon.bootstrap.AimonStack;

/**
 * Registers the stack's agent runtimes just before the web server starts accepting requests.
 *
 * <p>
 * Bean creation assembles the stack; this makes it reachable. The gap between the two is the point: an
 * {@code AgentRuntimeRegistry} that fills during {@code refresh()} is one that a scheduled task or an inbound
 * session could resolve while other beans are still being created, and "the runtime exists" is exactly the signal
 * that says the application is ready to run a turn.
 *
 * <h2>The phase number</h2>
 *
 * <p>
 * {@code Integer.MAX_VALUE - 4096} is chosen relative to the two numbers Boot occupies at the top of the range,
 * both read out of {@code spring-boot} 3.5's bytecode rather than inferred:
 *
 * <table border="1">
 * <caption>Phases at the top of the range</caption>
 * <tr>
 * <th>Lifecycle</th>
 * <th>Phase</th>
 * </tr>
 * <tr>
 * <td>{@code AimonRuntimeLifecycle} (this)</td>
 * <td>{@code MAX_VALUE - 4096} = 2147479551</td>
 * </tr>
 * <tr>
 * <td>{@code WebServerStartStopLifecycle} (servlet and reactive)</td>
 * <td>{@code MAX_VALUE - 2048} =
 * 2147481599</td>
 * </tr>
 * <tr>
 * <td>{@code WebServerGracefulShutdownLifecycle}</td>
 * <td>{@code MAX_VALUE - 1024} = 2147482623</td>
 * </tr>
 * <tr>
 * <td>{@link AimonSchedulingLifecycle}</td>
 * <td>{@code MAX_VALUE} = {@code SmartLifecycle.DEFAULT_PHASE}</td>
 * </tr>
 * </table>
 *
 * <p>
 * Lower phases start first, so this puts runtime registration <b>before</b> the listening socket opens — the first
 * request cannot arrive to find an empty registry — and, because stop order is the reverse, puts this bean's stop
 * <b>after</b> the socket closes. {@link AimonSchedulingLifecycle} sits above both web phases for the
 * mirror-image reason.
 *
 * <p>
 * The gap is 4096 rather than the 2048 the design first named, because 2048 is not free: it is exactly where
 * {@code WebServerStartStopLifecycle} sits. Beans that share a phase land in one {@code LifecycleGroup} and are
 * ordered within it by nothing more than the bean factory's iteration order, so the tie would have made "runtimes
 * before the socket" a coincidence rather than a guarantee. The design's error was reading
 * {@code MAX_VALUE - 1024} as the web server's phase; that is the graceful-shutdown lifecycle, one step above it.
 *
 * <h2>Why stop does nothing</h2>
 *
 * <p>
 * Because unregistering here would be a second teardown edge for something {@link AimonStack#close()} already
 * owns, and the two would run in the wrong order relative to each other: Spring stops lifecycle beans before it
 * destroys any bean, so this would pull runtimes out of the registry while sessions are still draining inside the
 * stack's own ordered shutdown. Turning off the front door is the web server's job at the phase above; letting go
 * of what is behind it stays with the destroy method.
 */
final class AimonRuntimeLifecycle implements SmartLifecycle {

    /** Below Boot's {@code WebServerStartStopLifecycle} ({@code Integer.MAX_VALUE - 2048}), so runtimes win. */
    static final int PHASE = Integer.MAX_VALUE - 4096;

    private static final Logger log = LoggerFactory.getLogger(AimonRuntimeLifecycle.class);

    private final AimonStack stack;
    private volatile boolean running;

    AimonRuntimeLifecycle(AimonStack stack) {
        this.stack = Objects.requireNonNull(stack, "stack must not be null");
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    @Override
    public void start() {
        stack.startRuntimes();
        running = true;
        if (log.isDebugEnabled()) {
            // Printed once, here, because the order is otherwise invisible without reading TeardownPhase — and
            // the moment anyone wants it is after a shutdown that hung, by which point the process is gone.
            log.debug("AIMON shutdown order:\n  {}", String.join("\n  ", stack.teardownPlan()));
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
