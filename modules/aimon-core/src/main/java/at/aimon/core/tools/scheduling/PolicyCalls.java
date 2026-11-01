/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.tools.scheduling;

import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.scheduling.ScheduledTaskPolicy;
import at.aimon.core.scheduling.ScheduledTaskPolicy.Decision;

/**
 * Asks a {@link ScheduledTaskPolicy} for a decision on behalf of the scheduling tools, failing closed: a policy that
 * throws or answers null refuses. A host policy is the only guard scheduled work has (its steps run without hooks), so
 * a broken one must not wave requests through.
 */
final class PolicyCalls {

    private static final Logger log = LoggerFactory.getLogger(PolicyCalls.class);

    private PolicyCalls() {
    }

    static Decision decide(String what, Supplier<Decision> call) {
        try {
            final Decision decision = call.get();
            if (decision == null) {
                log.warn("Scheduled task policy returned no decision for {}; refusing", what);
                return Decision.deny("the scheduling policy gave no decision");
            }
            return decision;
        } catch (RuntimeException e) {
            log.error("Scheduled task policy failed for {}; refusing: {}", what, e.getMessage(), e);
            return Decision.deny("the scheduling policy failed: " + e.getMessage());
        }
    }
}
