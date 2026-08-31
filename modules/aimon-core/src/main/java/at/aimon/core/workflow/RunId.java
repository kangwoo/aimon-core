package at.aimon.core.workflow;

import java.util.Objects;
import java.util.Optional;

/**
 * Value object identifying one workflow run (a single execution of a {@link WorkflowScript}).
 *
 * <p>
 * Like {@link at.aimon.core.agent.AgentRuntimeId}, a {@code RunId} is <b>deterministic</b> — derived from the
 * script name (and an optional {@code discriminator}) rather than a random UUID. This is load-bearing for resume: a run
 * that re-executes the same script under the same id must reproduce the same {@code StepResultCache} step-keys so
 * completed steps replay instead of re-running (design §5.3). Determinism also makes background
 * submission idempotent — re-submitting a {@code RunId} whose prior run is non-terminal returns the existing run
 * rather than dispatching a duplicate.
 *
 * <ul>
 * <li>{@code run:<scriptName>} — basic form. Use {@link #from(String)}.</li>
 * <li>{@code run:<scriptName>:<discriminator>} — when the same script runs per tenant / date / input set. Use
 * {@link #from(String, String)}.</li>
 * </ul>
 *
 * <p>
 * The class stores {@code scriptName} and an optional {@code discriminator} as separate fields; the wire-format string
 * returned by {@link #value()} is derived from them, and {@link #of(String)} is the only path that parses an external
 * string back into an instance. Instances are immutable and safe to use as map keys.
 */
public final class RunId {

    private static final String PREFIX = "run:";
    private static final String SEPARATOR = ":";

    private final String scriptName;
    private final String discriminator;
    private final String value;

    private RunId(String scriptName, String discriminator) {
        this.scriptName = scriptName;
        this.discriminator = discriminator;
        this.value = discriminator == null ? PREFIX + scriptName : PREFIX + scriptName + SEPARATOR + discriminator;
    }

    /**
     * Reconstructs a {@link RunId} from its serialized value of the form {@code run:<scriptName>[:<discriminator>]}.
     *
     * <p>
     * Intended for deserialization / persistence restore. Production code that has the script name should prefer
     * {@link #from(String)} / {@link #from(String, String)} so the contract is enforced at the call site.
     *
     * @param value
     *            the wire-format value (must start with {@code run:}, script-name segment must be non-blank)
     * @return a new id wrapping {@code value}
     * @throws NullPointerException
     *             when {@code value} is null
     * @throws IllegalArgumentException
     *             when {@code value} does not match {@code run:<scriptName>[:<discriminator>]}
     */
    public static RunId of(String value) {
        Objects.requireNonNull(value, "Run ID cannot be null");
        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Run ID must start with '" + PREFIX + "': " + value);
        }
        final String tail = value.substring(PREFIX.length());
        if (tail.isBlank()) {
            throw new IllegalArgumentException("Run ID has empty script-name segment: " + value);
        }
        final int colon = tail.indexOf(SEPARATOR);
        if (colon < 0) {
            return new RunId(tail, null);
        }
        final String name = tail.substring(0, colon);
        final String disc = tail.substring(colon + 1);
        if (name.isBlank()) {
            throw new IllegalArgumentException("Run ID has empty script-name segment: " + value);
        }
        if (disc.isBlank()) {
            throw new IllegalArgumentException("Run ID has empty discriminator segment: " + value);
        }
        if (disc.contains(SEPARATOR)) {
            throw new IllegalArgumentException(
                    "Run ID discriminator must not contain ':' (reserved separator): " + value);
        }
        return new RunId(name, disc);
    }

    /**
     * Derives a run id of the form {@code run:<scriptName>}.
     *
     * @param scriptName
     *            the script name (must be non-blank and must not contain {@code ':'})
     * @return a stable id keyed on the script name
     * @throws IllegalArgumentException
     *             if {@code scriptName} is null, blank, or contains {@code ':'}
     */
    public static RunId from(String scriptName) {
        requireSegment(scriptName, "Script name");
        return new RunId(scriptName, null);
    }

    /**
     * Derives a run id of the form {@code run:<scriptName>:<discriminator>}. Use the discriminator to split repeated
     * runs of the same script (e.g. per tenant, per date, per input set).
     *
     * @param scriptName
     *            the script name (must be non-blank and must not contain {@code ':'})
     * @param discriminator
     *            the discriminator suffix (must be non-blank and must not contain {@code ':'})
     * @return a stable id keyed on the script name and discriminator
     * @throws IllegalArgumentException
     *             if any argument is null, blank, or contains {@code ':'}
     */
    public static RunId from(String scriptName, String discriminator) {
        requireSegment(scriptName, "Script name");
        requireSegment(discriminator, "Discriminator");
        return new RunId(scriptName, discriminator);
    }

    private static void requireSegment(String segment, String label) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException(label + " cannot be null or blank");
        }
        if (segment.contains(SEPARATOR)) {
            throw new IllegalArgumentException(label + " must not contain ':' (reserved separator); got: " + segment);
        }
    }

    /**
     * @return the wire-format identifier value of the form {@code run:<scriptName>[:<discriminator>]} (never blank)
     */
    public String value() {
        return value;
    }

    /**
     * @return the script-name segment (never null or blank)
     */
    public String scriptName() {
        return scriptName;
    }

    /**
     * @return the discriminator segment, or empty when the id is in bare {@code run:<scriptName>} form
     */
    public Optional<String> discriminator() {
        return Optional.ofNullable(discriminator);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final RunId that = (RunId) o;
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
