package at.aimon.core.agent.context;

import java.util.List;

/**
 * A single source of runtime {@link ContextBlock context blocks} (environment facts, git branch, directory summary,
 * user extensions, between-turn attachments, ...).
 *
 * <p>
 * Providers are stateless strategies injected into a {@link ContextAssembler}. Each is asked, per turn, to contribute
 * whatever blocks it can derive from the {@link ContextAssemblyRequest}. A provider that has nothing to add (e.g. no
 * filesystem is bound, or the {@code .git} directory is absent) returns an empty list rather than a placeholder.
 *
 * <p>
 * A provider <b>should not</b> throw: the {@link ContextAssembler} is defensive and skips a failing provider so one bad
 * source never breaks prompt assembly, but well-behaved providers keep failures internal and simply return
 * {@link List#of() empty}.
 */
@FunctionalInterface
public interface ContextProvider {

    /**
     * Produces the context blocks this provider can derive from the request.
     *
     * @param request
     *            the read-only assembly inputs (must not be null)
     * @return the contributed blocks, in the order they should appear; never null, empty when nothing applies
     */
    List<ContextBlock> provide(ContextAssemblyRequest request);
}
