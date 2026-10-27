/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.exception;

import java.io.Serial;
import java.util.Objects;

import at.aimon.core.agent.AgentRuntimeId;

/**
 * Thrown when a task is registered against an agent runtime id the {@code AgentRuntimeRegistry} cannot resolve.
 *
 * <p>
 * Such a task would be stored and scheduled, and then fail at every fire with "No agent runtime registered for
 * binding" — after the caller had been told it was scheduled. Refusing it at registration is the only point where
 * somebody is still there to hear about it.
 */
public class UnresolvableBoundRuntimeException extends SchedulingException {

    /** Serialization version. */
    @Serial
    private static final long serialVersionUID = 1L;

    /** The unresolvable runtime id, kept as text so the exception stays serializable. */
    private final String boundRuntimeId;

    /**
     * UnresolvableBoundRuntimeException을 생성한다.
     *
     * @param boundRuntimeId
     *            the runtime id nobody registered
     */
    public UnresolvableBoundRuntimeException(AgentRuntimeId boundRuntimeId) {
        super("No agent runtime is registered for '" + Objects.requireNonNull(boundRuntimeId, "boundRuntimeId")
                + "'; a task bound to it would never run");
        this.boundRuntimeId = boundRuntimeId.value();
    }

    /**
     * Returns the runtime id that could not be resolved.
     *
     * @return the runtime id, as text
     */
    public String getBoundRuntimeId() {
        return boundRuntimeId;
    }
}
