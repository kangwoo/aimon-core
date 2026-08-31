package at.aimon.core.agent.impl.orca.it;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import at.aimon.core.agent.interrupt.InterruptBehavior;
import at.aimon.core.agent.orca.tool.OrcaToolProvider;
import at.aimon.core.agent.orca.tool.OrcaToolProviderContext;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolRegistry;
import at.aimon.core.agent.tool.ToolResult;

/**
 * Parks the calling turn inside a tool call until the test releases it.
 *
 * <p>
 * <b>Why this exists and {@link RendezvousTool} does not serve.</b> A test that interrupts a running turn needs the
 * turn
 * to be provably <em>mid-flight</em> when the interrupt arrives, and needs to control the moment it resumes. A
 * {@link java.util.concurrent.CyclicBarrier} cannot give the second half: it releases both parties at the same instant,
 * so the test thread and the turn thread run on from the same point and the interrupt races the loop's
 * post-tool interrupt check. The two latches here split that into the two moments a targeting test must distinguish —
 * {@link #awaitArrival()} says <em>the turn is now inside the tool</em>, {@link #release()} says <em>now let it walk
 * into the interrupt check</em>. Between them the test has all the time it needs to read the active {@code TurnId} and
 * aim at it (or at the wrong one).
 *
 * <p>
 * <b>Interrupt behaviour is {@link InterruptBehavior#NON_INTERRUPTIBLE} on purpose</b> — the {@code AbstractTool}
 * default, restated here because it is load-bearing rather than incidental. A {@code THREAD_INTERRUPT} tool would let a
 * cancellation unpark {@link #release() the latch} by interrupting the executing thread, and the test would no longer
 * be the only thing that decides when the turn moves on. Keeping the tool non-interruptible means an interrupt observed
 * afterwards was observed at the loop's own check point, which is the behaviour under test.
 *
 * <p>
 * <b>It fails loudly, never silently.</b> Both waits are bounded by {@link #TIMEOUT_SECONDS}; a release that never
 * comes produces a {@link ToolResult#error error} observation rather than a hung suite, and an arrival that never comes
 * throws out of {@link #awaitArrival()} in the test thread.
 */
final class GateTool extends AbstractTool {

    static final String TOOL_NAME = "Gate";

    /** Generous enough for a loaded CI box, short enough that a genuine deadlock does not hang the build. */
    static final int TIMEOUT_SECONDS = 20;

    /** The observation a released gate produces — assert on it to prove the turn resumed rather than being killed. */
    static final String RELEASED = "gate released";

    /**
     * The current pair of latches, replaced wholesale by {@link #rearm()}.
     *
     * <p>
     * Not {@code final} because a {@link CountDownLatch} cannot be reset, and a test that parks a turn twice — an
     * interrupted turn and then the retry of it — needs a second parking. Each {@link #execute} captures whichever
     * pair is current when it enters, so a re-arm between two turns cannot leave one of them holding half of each.
     */
    private volatile CountDownLatch arrived = new CountDownLatch(1);
    private volatile CountDownLatch released = new CountDownLatch(1);

    GateTool() {
        super(TOOL_NAME, "Integration-test parking point. Blocks until the test releases it. Takes no parameters.",
                Map.of("type", "object", "properties", Map.of()));
    }

    /** A provider registering this gate — one gate instance per node, so each test controls exactly one turn. */
    static OrcaToolProvider provider(GateTool gate) {
        return new GateToolProvider(gate);
    }

    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(context, "context must not be null");

        // Read both once: a re-arm racing this call must not park us on the new latch while the test waits on the old.
        final CountDownLatch thisArrival = arrived;
        final CountDownLatch thisRelease = released;
        thisArrival.countDown();
        try {
            if (!thisRelease.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return ToolResult.error("gate was never released after " + TIMEOUT_SECONDS + "s");
            }
            return ToolResult.success(RELEASED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("gate wait was interrupted");
        }
    }

    /**
     * Restates the {@code AbstractTool} default, because this tool depends on it. See the class javadoc: a tool that
     * registers a thread-interrupt terminator would hand cancellation a second way to unpark the gate, and the test
     * would stop being the only thing that decides when the parked turn proceeds.
     */
    @Override
    public InterruptBehavior getInterruptBehavior() {
        return InterruptBehavior.NON_INTERRUPTIBLE;
    }

    /** Blocks the calling (test) thread until a turn has entered the tool. Throws rather than returning on timeout. */
    void awaitArrival() {
        try {
            if (!arrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "no turn reached the gate within " + TIMEOUT_SECONDS + "s — the script never called it");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a turn to reach the gate", e);
        }
    }

    /** Lets the parked turn proceed. Idempotent, and safe to call when nothing is parked. */
    void release() {
        released.countDown();
    }

    /**
     * Makes the gate ready to park another turn.
     *
     * <p>
     * Call between two parkings, never while one is in flight: the turn already inside holds the previous pair and is
     * unaffected, but a turn that has not yet reached the gate would arrive on the new pair while the test still
     * waits on the old one.
     */
    void rearm() {
        arrived = new CountDownLatch(1);
        released = new CountDownLatch(1);
    }

    private static final class GateToolProvider implements OrcaToolProvider {

        private final GateTool gate;

        GateToolProvider(GateTool gate) {
            this.gate = Objects.requireNonNull(gate, "gate must not be null");
        }

        @Override
        public void registerTools(ToolRegistry registry, OrcaToolProviderContext context) {
            Objects.requireNonNull(registry, "registry must not be null");
            Objects.requireNonNull(context, "context must not be null");
            registry.register(gate);
        }
    }
}
