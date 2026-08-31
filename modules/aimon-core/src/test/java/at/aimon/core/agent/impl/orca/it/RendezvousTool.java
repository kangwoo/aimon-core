package at.aimon.core.agent.impl.orca.it;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Blocks the calling turn until every party of a shared {@link CyclicBarrier} has also called it.
 *
 * <p>
 * <b>Why this exists.</b> A concurrency test that only starts two turns together proves nothing about overlap: a
 * {@code CountDownLatch} released before {@code executor.execute(...)} gates the <em>submission</em>, and the first
 * turn
 * can still run to completion before the second thread is even scheduled. The test then passes on a purely sequential
 * execution and would keep passing if the runtime serialised every turn behind one lock.
 *
 * <p>
 * Calling this tool in the middle of both scripts closes that hole. Neither turn can pass the barrier until the other
 * has reached it, so both are provably mid-turn — each holding its own transcript buffer, tool context, and iteration
 * state — at the same instant. Whatever isolation the test asserts afterwards was asserted against genuine overlap.
 *
 * <p>
 * <b>It fails loudly, never silently.</b> If one turn never arrives, {@link #TIMEOUT_SECONDS} elapses and the waiting
 * call returns a {@link ToolResult#error error} rather than hanging the suite; the barrier is broken, so the partner
 * fails fast too. An error observation flows into the model's next call, where the test's own assertions see it — a
 * timeout cannot be mistaken for a successful rendezvous.
 */
final class RendezvousTool extends AbstractTool {

    static final String TOOL_NAME = "Rendezvous";

    /** Generous enough for a loaded CI box, short enough that a genuine deadlock does not hang the build. */
    static final int TIMEOUT_SECONDS = 20;

    /** Prefix of the observation a successful rendezvous produces — assert on this to prove both turns overlapped. */
    static final String ARRIVED = "rendezvous complete";

    private final CyclicBarrier barrier;

    RendezvousTool(CyclicBarrier barrier) {
        super(TOOL_NAME, "Integration-test synchronisation point. Blocks until every participating turn has called it. "
                + "Takes no parameters.", Map.of("type", "object", "properties", Map.of()));
        this.barrier = Objects.requireNonNull(barrier, "barrier must not be null");
    }

    /** A provider registering a rendezvous over {@code barrier} — give both nodes the <b>same</b> barrier instance. */
    static OrcaToolProvider provider(CyclicBarrier barrier) {
        return new RendezvousToolProvider(barrier);
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(context, "context must not be null");

        try {
            final int arrivalIndex = barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return ToolResult.success(ARRIVED + " (arrival index " + arrivalIndex + ")");
        } catch (TimeoutException e) {
            return ToolResult.error("rendezvous timed out after " + TIMEOUT_SECONDS
                    + "s — the other turn never arrived, so the two turns did not overlap");
        } catch (BrokenBarrierException e) {
            return ToolResult.error("rendezvous barrier was broken — the other turn failed or timed out first");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("rendezvous was interrupted");
        }
    }

    private static final class RendezvousToolProvider implements OrcaToolProvider {

        private final CyclicBarrier barrier;

        RendezvousToolProvider(CyclicBarrier barrier) {
            this.barrier = Objects.requireNonNull(barrier, "barrier must not be null");
        }

        @Override
        public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
            Objects.requireNonNull(registry, "registry must not be null");
            Objects.requireNonNull(context, "context must not be null");
            registry.register(new RendezvousTool(barrier));
        }
    }
}
