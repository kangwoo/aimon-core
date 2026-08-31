/**
 * Runtime context assembly for the agent loop.
 *
 * <p>
 * This package generalises the previously hard-wired environment block and the one-shot
 * {@link at.aimon.core.agent.prompt.UserContextMessageBuilder} into a refreshable, injectable pipeline. A
 * {@link at.aimon.core.agent.context.ContextAssembler} runs a list of
 * {@link at.aimon.core.agent.context.ContextProvider
 * providers} and returns an ordered list of {@link at.aimon.core.agent.context.ContextBlock blocks}, each tagged with a
 * {@link at.aimon.core.agent.context.ContextBlockKind kind} that tells the executor where to inject it (system prompt,
 * ahead of the first user message, or as a between-turn attachment).
 *
 * <p>
 * The concrete sources live behind {@link at.aimon.core.agent.context.ContextProvider}: the bundled providers
 * ({@link at.aimon.core.agent.context.EnvironmentContextProvider},
 * {@link at.aimon.core.agent.context.GitStatusContextProvider},
 * {@link at.aimon.core.agent.context.DirectorySummaryContextProvider}) derive their blocks from the per-request inputs
 * (environment, {@link at.aimon.core.filesystem.VirtualFileSystem}, agent name), so the assembler holds no
 * per-session state and is safe to share across concurrent sessions and scale-out instances.
 *
 * <p>
 * Everything is opt-in. The framework default {@link at.aimon.core.agent.context.ContextAssembler#NOOP} assembles
 * nothing, so wiring nothing leaves the prompt shape unchanged. Assembly is defensive by contract — a failing provider
 * is skipped, never propagated, so context assembly can never break a turn.
 */
package at.aimon.core.agent.context;
