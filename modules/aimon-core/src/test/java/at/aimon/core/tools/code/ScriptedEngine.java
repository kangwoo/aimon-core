package at.aimon.core.tools.code;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import at.aimon.core.agent.tool.DataReach;
import at.aimon.core.agent.tool.DestructiveBehavior;
import at.aimon.core.agent.tool.SideEffectLevel;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.programmatic.CodeExecutionEngine;
import at.aimon.core.programmatic.CodeExecutionOutcome;
import at.aimon.core.programmatic.CodeExecutionRequest;
import at.aimon.core.programmatic.CodeExecutionStatus;
import at.aimon.core.programmatic.EngineAvailability;
import at.aimon.core.programmatic.OfferFacts;
import at.aimon.core.programmatic.ToolCallBridge;
import at.aimon.core.programmatic.ToolGrade;

/**
 * A test engine whose "program" is a Java function over the bridge. Public so tests in other packages (the loop e2e
 * tests under {@code agent.impl.orca}) can assemble an {@code ExecuteCodeTool} over it.
 */
public final class ScriptedEngine implements CodeExecutionEngine {

    static final ToolGrade DELEGATING = ToolGrade.builder().sideEffectLevel(SideEffectLevel.READ_ONLY)
            .destructiveBehavior(DestructiveBehavior.NON_DESTRUCTIVE).dataReach(DataReach.ENVIRONMENT)
            .approvalDelegated(true).build();

    private final String language;
    private final ToolGrade grade;
    private final AtomicReference<BiFunction<CodeExecutionRequest, ToolCallBridge, CodeExecutionOutcome>> program = new AtomicReference<>(
            (request, bridge) -> completed(""));
    private volatile EngineAvailability availability = EngineAvailability.available();
    private volatile CodeExecutionRequest lastRequest;

    ScriptedEngine(String language, ToolGrade grade) {
        this.language = language;
        this.grade = grade;
    }

    public static ScriptedEngine javascript() {
        return new ScriptedEngine(JAVASCRIPT, DELEGATING);
    }

    ScriptedEngine program(BiFunction<CodeExecutionRequest, ToolCallBridge, CodeExecutionOutcome> program) {
        this.program.set(Objects.requireNonNull(program));
        return this;
    }

    ScriptedEngine availability(EngineAvailability availability) {
        this.availability = availability;
        return this;
    }

    CodeExecutionRequest lastRequest() {
        return lastRequest;
    }

    static CodeExecutionOutcome completed(String stdout) {
        return CodeExecutionOutcome.builder().status(CodeExecutionStatus.COMPLETED).stdout(stdout).build();
    }

    @Override
    public String language() {
        return language;
    }

    @Override
    public EngineAvailability staticAvailability(OfferFacts facts) {
        return availability;
    }

    @Override
    public ToolGrade intrinsicGrade() {
        return grade;
    }

    @Override
    public CodeExecutionOutcome execute(CodeExecutionRequest request, ToolCallBridge bridge, ToolContext context) {
        lastRequest = request;
        return program.get().apply(request, bridge);
    }
}
