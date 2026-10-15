package at.aimon.core.agent.quota.exception;

import java.io.Serial;

import at.aimon.core.base.exception.AimonException;

/**
 * Thrown by a fail-closed {@link at.aimon.core.agent.quota.QuotaGuard} when its
 * {@link at.aimon.core.agent.quota.UsageLedger}
 * failed, so the quota could not be judged or the usage could not be recorded.
 *
 * <p>
 * An execution that sees it ends with {@link at.aimon.core.agent.budget.CompletionReason#ERROR}, not
 * {@code QUOTA_EXCEEDED}: no quota was exceeded, the answer is unknown. A fail-open guard (the default) never throws
 * it; it logs the ledger failure and lets the execution continue.
 */
public class QuotaLedgerUnavailableException extends AimonException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message
     *            what the guard was doing when the ledger failed
     * @param cause
     *            the ledger's failure
     */
    public QuotaLedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
