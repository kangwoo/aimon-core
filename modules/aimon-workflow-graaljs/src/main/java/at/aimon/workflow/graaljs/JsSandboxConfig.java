package at.aimon.workflow.graaljs;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable sandbox configuration for a GraalJS workflow run — the knobs {@link JsContextFactory} and
 * {@link GraalJsWorkflowScript} read when building the per-run {@code Context}.
 *
 * <p>
 * Value object: {@code final} fields, builder construction, {@code Objects.requireNonNull} guards. See
 * {@code docs/design/workflow/workflow.md} §7.3 (layered sandbox) for the rationale behind each knob.
 */
public final class JsSandboxConfig {

    /**
     * Determinism posture for the run. Purely a resume/replay control (not a security surface): {@code Object.freeze}
     * is inert against {@code Date}/{@code Math.random}, so the only honest option is fail-closed removal.
     */
    public enum DeterminismMode {

        /** No prelude. {@code Date}/{@code Math.random} remain available; runs are non-deterministic (default). */
        NONE(""),

        /**
         * Fail-closed: {@code Date}, {@code Math.random}, {@code Intl.DateTimeFormat} (which reads the current time
         * when formatting without an argument), and {@code performance.now} are replaced with throwing stubs so any
         * use raises a loud guest error rather than silently perturbing the resume {@code inputHash}.
         */
        STRICT("(function(){var b=function(n){return function(){"
                + "throw new Error(n+' is disabled in deterministic mode');};};"
                + "var d=b('Date');d.now=b('Date.now');globalThis.Date=d;" + "Math.random=b('Math.random');"
                + "if(typeof Intl!=='undefined'){Intl.DateTimeFormat=b('Intl.DateTimeFormat');}"
                + "if(typeof performance!=='undefined'){performance.now=b('performance.now');}})();");

        private final String preludeSource;

        DeterminismMode(String preludeSource) {
            this.preludeSource = preludeSource;
        }

        /** JS bootstrap evaluated before the author source. Empty for {@link #NONE}. */
        public String preludeSource() {
            return preludeSource;
        }
    }

    private static final long DEFAULT_MAX_STATEMENTS = 10_000_000L;
    private static final Duration DEFAULT_WALL_CLOCK = Duration.ofMinutes(30);

    private final long maxStatements;
    private final Duration wallClockTimeout;
    private final DeterminismMode determinismMode;
    private final boolean allowConsole;

    private JsSandboxConfig(Builder builder) {
        this.maxStatements = builder.maxStatements;
        this.wallClockTimeout = Objects.requireNonNull(builder.wallClockTimeout, "wallClockTimeout cannot be null");
        this.determinismMode = Objects.requireNonNull(builder.determinismMode, "determinismMode cannot be null");
        this.allowConsole = builder.allowConsole;
    }

    /** Sensible defaults: 10M statement backstop, 30-minute wall clock, no determinism prelude, no console. */
    public static JsSandboxConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Statement-count backstop for pure-JS runaways ({@code ResourceLimits.statementLimit}). */
    public long maxStatements() {
        return maxStatements;
    }

    /** Wall-clock deadline after which the watchdog force-closes the {@code Context}. */
    public Duration wallClockTimeout() {
        return wallClockTimeout;
    }

    public DeterminismMode determinismMode() {
        return determinismMode;
    }

    /** Whether a minimal {@code console.log}/{@code warn}/{@code error} bridge to {@code ctx.log} is installed. */
    public boolean allowConsole() {
        return allowConsole;
    }

    public static final class Builder {

        private long maxStatements = DEFAULT_MAX_STATEMENTS;
        private Duration wallClockTimeout = DEFAULT_WALL_CLOCK;
        private DeterminismMode determinismMode = DeterminismMode.NONE;
        private boolean allowConsole;

        private Builder() {
        }

        public Builder maxStatements(long maxStatements) {
            if (maxStatements < 1) {
                throw new IllegalArgumentException("maxStatements must be >= 1, got: " + maxStatements);
            }
            this.maxStatements = maxStatements;
            return this;
        }

        /**
         * The wall-clock deadline; must be positive. There is no "no deadline" setting — the watchdog is a mandatory
         * backstop; callers wanting a de-facto unbounded run should pass an explicitly generous duration.
         */
        public Builder wallClockTimeout(Duration wallClockTimeout) {
            Objects.requireNonNull(wallClockTimeout, "wallClockTimeout cannot be null");
            if (wallClockTimeout.isZero() || wallClockTimeout.isNegative()) {
                throw new IllegalArgumentException("wallClockTimeout must be positive, got: " + wallClockTimeout);
            }
            this.wallClockTimeout = wallClockTimeout;
            return this;
        }

        public Builder determinismMode(DeterminismMode determinismMode) {
            this.determinismMode = determinismMode;
            return this;
        }

        public Builder allowConsole(boolean allowConsole) {
            this.allowConsole = allowConsole;
            return this;
        }

        public JsSandboxConfig build() {
            return new JsSandboxConfig(this);
        }
    }
}
