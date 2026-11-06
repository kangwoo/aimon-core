package at.aimon.graaljs.internal;

import java.io.OutputStream;
import java.util.Objects;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.io.IOAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.graaljs.JsSandboxConfig;

/**
 * Builds a per-run, deny-by-default GraalJS {@code Context} bound to the shared {@link Engine}.
 *
 * <p>
 * The built-in {@code print} and {@code console} are switched off ({@code js.print=false}, {@code js.console=false})
 * and the context's {@code out}/{@code err} go to a discarding sink: left on, both write to the engine's default
 * output — the JVM's {@code System.out} — which in a CLI is the user's terminal. A caller that wants a console installs
 * its own, writing to buffers it owns ({@code docs/design/tool/programmatic-tool-calling.md} §10.1).
 *
 * <p>
 * The two behaviour-changing non-defaults are {@code allowHostAccess(HostAccess.NONE)} (the {@code allowAllAccess}
 * default is {@code EXPLICIT}, not {@code NONE}) and {@code resourceLimits(statementLimit(...))} — the mandatory
 * runaway backstop for pure-JS loops that never call {@code agent()}. Everything else is defense-in-depth over the
 * {@code allowAllAccess(false)} deny baseline.
 */
public final class JsContextFactory {

    private static final Logger log = LoggerFactory.getLogger(JsContextFactory.class);

    private JsContextFactory() {
    }

    /**
     * Creates a locked-down single-thread {@code Context}; the caller owns its lifecycle and must close it.
     *
     * @param engine
     *            the shared engine (must not be null)
     * @param config
     *            the sandbox configuration (must not be null)
     * @return a new context (never null)
     */
    public static Context create(Engine engine, JsSandboxConfig config) {
        Objects.requireNonNull(config, "config cannot be null");
        return create(engine, config.maxStatements());
    }

    /**
     * Creates a locked-down single-thread {@code Context} with an explicit statement limit.
     *
     * @param engine
     *            the shared engine (must not be null)
     * @param maxStatements
     *            the statement limit (must be positive)
     * @return a new context (never null)
     */
    public static Context create(Engine engine, long maxStatements) {
        Objects.requireNonNull(engine, "engine cannot be null");

        final ResourceLimits limits = ResourceLimits.newBuilder().statementLimit(maxStatements, source -> true)
                .onLimit(event -> log.warn("GraalJS statement limit exceeded (limit={})", maxStatements)).build();

        return Context.newBuilder("js").engine(engine).allowAllAccess(false).allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(name -> false).allowHostClassLoading(false).allowIO(IOAccess.NONE)
                .allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
                .option("js.print", "false").option("js.console", "false").out(OutputStream.nullOutputStream())
                .err(OutputStream.nullOutputStream()).resourceLimits(limits).build();
    }
}
