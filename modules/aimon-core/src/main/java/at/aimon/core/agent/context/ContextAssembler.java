package at.aimon.core.agent.context;

import java.util.List;

/**
 * Assembles the runtime context injected into a conversation each turn.
 *
 * <p>
 * This is the seam the executor talks to. It generalises the previously hard-wired environment block and the one-shot
 * {@code UserContextMessageBuilder} into a refreshable, injectable pipeline: the concrete sources live behind
 * {@link ContextProvider} implementations (environment, git status, directory summary, user extensions, ...), keeping
 * the executor agnostic to what context exists and how it is derived (multi-instance friendly — swapping the assembler
 * is a wiring change, never a code change in the loop).
 *
 * <p>
 * The framework default is {@link #NOOP}, which assembles nothing — so context assembly is entirely opt-in and wiring
 * nothing leaves the prompt shape unchanged.
 *
 * @see DefaultContextAssembler
 * @see ContextProvider
 */
@FunctionalInterface
public interface ContextAssembler {

    /**
     * The default assembler: contributes no blocks. Used when no context assembly has been wired, so the surrounding
     * machinery runs but observes nothing.
     */
    ContextAssembler NOOP = request -> List.of();

    /**
     * Assembles the context blocks for the given request.
     *
     * <p>
     * <b>Contract:</b> this method must never throw. A failing {@link ContextProvider} is skipped, not propagated, so
     * context assembly can never break a turn.
     *
     * @param request
     *            the read-only assembly inputs (must not be null)
     * @return the assembled blocks in injection order; never null, empty when nothing applies
     */
    List<ContextBlock> assemble(ContextAssemblyRequest request);
}
