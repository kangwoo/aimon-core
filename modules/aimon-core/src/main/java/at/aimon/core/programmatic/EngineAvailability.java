package at.aimon.core.programmatic;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether an engine can run code here, and why not when it cannot
 * ({@code docs/design/tool/programmatic-tool-calling.md}
 * §9). A static unavailability keeps {@code ExecuteCode} out of the offer and reaches the operator as a warning; a
 * probe
 * unavailability fails that one {@code ExecuteCode} call and reaches the model once.
 *
 * <p>
 * Immutable.
 */
public final class EngineAvailability {

    private static final EngineAvailability AVAILABLE = new EngineAvailability(null);

    private final String reason;

    private EngineAvailability(String reason) {
        this.reason = reason;
    }

    /**
     * Returns the available answer.
     *
     * @return the shared available instance (never null)
     */
    public static EngineAvailability available() {
        return AVAILABLE;
    }

    /**
     * Returns an unavailable answer.
     *
     * @param reason
     *            why the engine cannot run here, for an operator or the model (must not be null)
     * @return the answer (never null)
     */
    public static EngineAvailability unavailable(String reason) {
        return new EngineAvailability(Objects.requireNonNull(reason, "reason cannot be null"));
    }

    /**
     * Returns whether the engine can run.
     *
     * @return {@code true} when available
     */
    public boolean isAvailable() {
        return reason == null;
    }

    /**
     * Returns why the engine cannot run.
     *
     * @return the reason, empty when available
     */
    public Optional<String> getReason() {
        return Optional.ofNullable(reason);
    }

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof EngineAvailability other && Objects.equals(reason, other.reason));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(reason);
    }

    @Override
    public String toString() {
        return reason == null ? "EngineAvailability{available}" : "EngineAvailability{unavailable: " + reason + '}';
    }
}
