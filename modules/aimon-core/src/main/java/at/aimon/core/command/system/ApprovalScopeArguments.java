package at.aimon.core.command.system;

import java.util.Locale;

import at.aimon.core.skill.policy.approval.ApprovalScope;

/**
 * Shared parsing for the {@code --agent} flag that {@code /revoke}, {@code /approve} and {@code /deny} accept.
 *
 * <p>
 * All three default to {@link ApprovalScope#SESSION} and widen to {@link ApprovalScope#AGENT} only when the user
 * spells the flag out. Keeping the parse in one place is what makes that default uniform — three hand-rolled
 * {@code contains("--agent")} checks would drift apart the first time one of them gained an argument.
 *
 * <p>
 * The flag may appear anywhere in the argument string, so both {@code /approve abc123 --agent} and
 * {@code /approve --agent abc123} work. {@link #firstOperand(String)} correspondingly skips flag tokens rather than
 * blindly taking the first one, which also preserves the existing tolerance for pasted {@code /pending} output lines.
 */
final class ApprovalScopeArguments {

    /** The literal users type to widen an approval command from the session to the whole agent. */
    static final String AGENT_FLAG = "--agent";

    private ApprovalScopeArguments() {
    }

    /**
     * Returns the scope the arguments select.
     *
     * @param rawArgs
     *            the raw argument string (may be null or blank)
     * @return {@link ApprovalScope#AGENT} when {@code --agent} is present, {@link ApprovalScope#SESSION}
     *         otherwise
     */
    static ApprovalScope scopeOf(String rawArgs) {
        if (rawArgs == null) {
            return ApprovalScope.SESSION;
        }
        for (String token : rawArgs.trim().split("\\s+")) {
            if (AGENT_FLAG.equals(token.toLowerCase(Locale.ROOT))) {
                return ApprovalScope.AGENT;
            }
        }
        return ApprovalScope.SESSION;
    }

    /**
     * Returns the first non-flag token, or {@code null} when the arguments carry none.
     *
     * @param rawArgs
     *            the raw argument string (may be null or blank)
     * @return the first token that does not start with {@code --}, or null
     */
    static String firstOperand(String rawArgs) {
        if (rawArgs == null) {
            return null;
        }
        for (String token : rawArgs.trim().split("\\s+")) {
            if (!token.isEmpty() && !token.startsWith("--")) {
                return token;
            }
        }
        return null;
    }
}
