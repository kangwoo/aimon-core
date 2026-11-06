package at.aimon.graaljs.code;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.programmatic.ToolGrade;
import at.aimon.graaljs.GraalJsEngineHolder;
import at.aimon.graaljs.internal.JsProgramRun;

/**
 * The JavaScript engine of {@code ExecuteCode}: a GraalJS isolate in the host JVM
 * ({@code docs/design/tool/programmatic-tool-calling.md} §10).
 *
 * <p>
 * Each program gets its own deny-by-default context on the shared engine — no host access, no I/O, no threads, no
 * processes, no environment, built-in output switched off — whose only ways out are the {@code tools} binding (to the
 * {@link ToolCallBridge}) and the two output buffers behind {@code console}. Calls return JavaScript promises completed
 * by an event loop on the calling thread.
 *
 * <p>
 * Its intrinsic grade is {@code READ_ONLY} / {@code NON_DESTRUCTIVE} / {@code ENVIRONMENT} with approval delegated: the
 * code itself can do nothing but compute and call tools, so the approval gate judges the nested calls, one at a time
 * (decision 10).
 *
 * <p>
 * GraalJS Community Edition has no hard limit on the guest heap or CPU time: the engine stops privilege escalation and
 * host-side copy exhaustion, not a guest that exhausts its own heap. It says so once, as a warning, when built (§10.3).
 *
 * <p>
 * Application-scoped and thread-safe. Shares a {@link GraalJsEngineHolder} with {@code WorkflowJs} when built over one
 * ({@link #GraalJsCodeExecutionEngine(GraalJsEngineHolder)}, the holder's owner closes it), or owns one
 * ({@link #create()}, closed with the engine).
 */
public final class GraalJsCodeExecutionEngine implements CodeExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(GraalJsCodeExecutionEngine.class);

    private static final ToolGrade INTRINSIC_GRADE = ToolGrade.builder().sideEffectLevel(SideEffectLevel.READ_ONLY)
            .destructiveBehavior(DestructiveBehavior.NON_DESTRUCTIVE).dataReach(DataReach.ENVIRONMENT)
            .approvalDelegated(true).build();

    private final GraalJsEngineHolder holder;
    private final boolean ownsHolder;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates an engine over a shared holder. The holder's owner closes it; {@link #close()} leaves it open.
     *
     * @param holder
     *            the application-scoped holder (must not be null)
     */
    public GraalJsCodeExecutionEngine(GraalJsEngineHolder holder) {
        this(holder, false);
    }

    private GraalJsCodeExecutionEngine(GraalJsEngineHolder holder, boolean ownsHolder) {
        this.holder = Objects.requireNonNull(holder, "holder cannot be null");
        this.ownsHolder = ownsHolder;
        log.warn("GraalJS ExecuteCode engine assembled: GraalJS Community Edition has no hard limit on a program's "
                + "guest heap, so a program can exhaust this JVM's memory (programmatic-tool-calling design §10.3)");
    }

    /**
     * Creates an engine that owns a new holder, closed with the engine.
     *
     * @return a new engine (never null)
     */
    public static GraalJsCodeExecutionEngine create() {
        return new GraalJsCodeExecutionEngine(GraalJsEngineHolder.create(), true);
    }

    @Override
    public String language() {
        return JAVASCRIPT;
    }

    /** Assembled is available: the isolate needs nothing from the environment. */
    @Override
    public EngineAvailability staticAvailability(OfferFacts facts) {
        Objects.requireNonNull(facts, "facts cannot be null");
        return closed.get()
                ? EngineAvailability.unavailable("the GraalJS engine is closed")
                : EngineAvailability.available();
    }

    @Override
    public ToolGrade intrinsicGrade() {
        return INTRINSIC_GRADE;
    }

    @Override
    public CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge, ToolContext context) {
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(bridge, "bridge cannot be null");
        Objects.requireNonNull(context, "context cannot be null");
        if (closed.get()) {
            throw new IllegalStateException("the GraalJS engine is closed");
        }
        return new JsProgramRun(holder, request, bridge).run();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true) && ownsHolder) {
            holder.close();
        }
    }

    @Override
    public String toString() {
        return "GraalJsCodeExecutionEngine{ownsHolder=" + ownsHolder + ", closed=" + closed.get() + '}';
    }
}
