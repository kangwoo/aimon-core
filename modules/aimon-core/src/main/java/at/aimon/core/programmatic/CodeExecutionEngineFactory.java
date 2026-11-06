package at.aimon.core.programmatic;

/**
 * The {@code ServiceLoader} entry of an engine module ({@code docs/design/tool/programmatic-tool-calling.md} §5.2,
 * §9): assembly that does not name the module — {@code aimon-bootstrap}, the Spring Boot starter — finds its engines by
 * listing this service, so it depends on this package alone.
 *
 * <p>
 * An implementation is registered in {@code META-INF/services/at.aimon.core.programmatic.CodeExecutionEngineFactory}
 * and has a public no-argument constructor.
 */
public interface CodeExecutionEngineFactory {

    /**
     * Returns the language of the engines this factory creates.
     *
     * @return the language (never null)
     */
    String language();

    /**
     * Creates an engine. The caller owns it and closes it at shutdown. The engine reads its limits from each request.
     *
     * @return a new engine (never null)
     */
    CodeExecutionEngine create();
}
