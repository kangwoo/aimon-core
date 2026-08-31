package at.aimon.workflow.graaljs;

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

/**
 * Builds a per-run, deny-by-default GraalJS {@code Context} bound to the shared {@link Engine}.
 *
 * <p>
 * The two behaviour-changing non-defaults are {@code allowHostAccess(HostAccess.NONE)} (the {@code allowAllAccess}
 * default is {@code EXPLICIT}, not {@code NONE}) and {@code resourceLimits(statementLimit(...))} — the mandatory
 * runaway backstop for pure-JS loops that never call {@code agent()}. Everything else is defense-in-depth over the
 * {@code allowAllAccess(false)} deny baseline.
 */
final class JsContextFactory {

    private static final Logger log = LoggerFactory.getLogger(JsContextFactory.class);

    private JsContextFactory() {
    }

    /** Creates a locked-down single-thread {@code Context}; the caller owns its lifecycle and must close it. */
    static Context create(Engine engine, JsSandboxConfig config) {
        Objects.requireNonNull(engine, "engine cannot be null");
        Objects.requireNonNull(config, "config cannot be null");

        final ResourceLimits limits = ResourceLimits.newBuilder().statementLimit(config.maxStatements(), source -> true)
                .onLimit(event -> log.warn("GraalJS statement limit exceeded (limit={})", config.maxStatements()))
                .build();

        return Context.newBuilder("js").engine(engine).allowAllAccess(false).allowHostAccess(HostAccess.NONE)
                .allowHostClassLookup(name -> false).allowHostClassLoading(false).allowIO(IOAccess.NONE)
                .allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
                .resourceLimits(limits).build();
    }
}
