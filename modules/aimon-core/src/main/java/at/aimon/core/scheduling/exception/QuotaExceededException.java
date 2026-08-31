/*
 * Copyright 2025 the original author or authors.
 */

package at.aimon.core.scheduling.exception;

import at.aimon.core.base.Principal;

/**
 * Thrown when a principal exceeds its task quota.
 */
public class QuotaExceededException extends SchedulingException {

    private final Principal principal;
    private final int currentUsage;
    private final int maxQuota;

    /** QuotaExceededException을 생성한다. */
    public QuotaExceededException(Principal principal, int currentUsage, int maxQuota) {
        super("Quota exceeded for " + principal + ": " + currentUsage + '/' + maxQuota);
        this.principal = principal;
        this.currentUsage = currentUsage;
        this.maxQuota = maxQuota;
    }

    public Principal getPrincipal() {
        return principal;
    }

    public int getCurrentUsage() {
        return currentUsage;
    }

    public int getMaxQuota() {
        return maxQuota;
    }
}
