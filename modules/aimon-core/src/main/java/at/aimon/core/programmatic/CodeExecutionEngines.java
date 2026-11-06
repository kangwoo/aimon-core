package at.aimon.core.programmatic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Finds and creates the engines on the class path, for assembly that does not name an engine module.
 */
public final class CodeExecutionEngines {

    private CodeExecutionEngines() {
        throw new AssertionError("This class should not be instantiated");
    }

    /**
     * Returns the engine factories registered on {@code classLoader}, one per language (the first found wins).
     *
     * @param classLoader
     *            the class loader to search (must not be null)
     * @return the factories, in discovery order (never null)
     */
    public static List<CodeExecutionEngineFactory> discover(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader cannot be null");
        final Map<String, CodeExecutionEngineFactory> byLanguage = new LinkedHashMap<>();
        for (CodeExecutionEngineFactory factory : ServiceLoader.load(CodeExecutionEngineFactory.class, classLoader)) {
            byLanguage.putIfAbsent(factory.language(), factory);
        }
        return List.copyOf(byLanguage.values());
    }

    /**
     * Creates one engine from every factory {@link #discover(ClassLoader) discovered}. If one fails, the engines
     * already created are closed before the failure propagates.
     *
     * @param classLoader
     *            the class loader to search (must not be null)
     * @return the engines; the caller owns and closes them (never null)
     */
    public static List<CodeExecutionEngine> createAll(ClassLoader classLoader) {
        final List<CodeExecutionEngine> engines = new ArrayList<>();
        try {
            for (CodeExecutionEngineFactory factory : discover(classLoader)) {
                engines.add(factory.create());
            }
            return List.copyOf(engines);
        } catch (RuntimeException e) {
            for (CodeExecutionEngine engine : engines) {
                try {
                    engine.close();
                } catch (Exception suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            throw e;
        }
    }
}
