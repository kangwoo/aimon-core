package at.aimon.bootstrap.spec;

import at.aimon.core.agent.AgentRuntimeRegistry;
import at.aimon.core.knowledge.KnowledgeStore;

/**
 * Builds a {@link KnowledgeStore} over the {@link AgentRuntimeRegistry} the stack created for itself.
 *
 * <p>
 * The same inversion as {@link SkillApprovalChannelFactory}, for the same reason: a knowledge store whose pages
 * live in an agent's own file system has to reach the runtime to find that file system, and the runtime does not
 * exist until the store has already been handed to the factory that creates it. The CLI's wiki store is exactly
 * this shape — it resolves {@code AgentRuntimeId → OrcaAgentRuntime → VirtualFileSystem} on every page read, so
 * the registry it consults must be the one the runtimes register into.
 *
 * <p>
 * A caller-supplied registry would break the cycle syntactically and leave it broken semantically: pages would
 * be written against a registry nothing registers into, and every lookup would miss. That failure is silent —
 * an empty knowledge base reads exactly like one that was never populated — which is why the registry is not an
 * input to {@link at.aimon.bootstrap.AimonStackSpec} at all.
 *
 * <p>
 * Use {@link at.aimon.bootstrap.AimonStackSpec.Builder#knowledgeStore(KnowledgeStore)} instead whenever the
 * store is self-contained (OpenSearch, a database). This interface is only for stores that need the registry.
 *
 * <p>
 * The returned store is not owned by the stack — if it holds resources, the caller closes them. It is invoked
 * exactly once per {@code AimonStackBuilder.build(...)} call, before any runtime is created.
 */
@FunctionalInterface
public interface KnowledgeStoreFactory {

    /**
     * Creates the knowledge store.
     *
     * @param agentRuntimeRegistry
     *            the registry the stack's runtimes register into (never null); empty at call time — resolve
     *            lazily, per lookup, not eagerly in the factory
     * @return the store; must not be null
     */
    KnowledgeStore create(AgentRuntimeRegistry agentRuntimeRegistry);
}
