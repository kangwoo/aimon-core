package at.aimon.spring.boot.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import at.aimon.core.knowledge.KeywordKnowledgeStore;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.knowledge.SimpleDocumentChunker;

/**
 * Decides which {@link KnowledgeStore} the agents search, if any.
 *
 * <p>
 * Off by default. A store present but empty answers every query with silence that reads like a bug, and an agent
 * that was never given documents has nothing to search — so {@code aimon.knowledge.backend} starts at
 * {@code none} and the knowledge tool is not registered at all. That last part is the stack's doing rather than
 * this slice's: a knowledge store is what causes {@code KnowledgeSearch} to be registered with every runtime, so
 * the tool and the store cannot disagree about whether the capability exists.
 *
 * <h2>Ownership</h2>
 *
 * <p>
 * The store this slice builds is a Spring bean with an inferred {@code close()}, unlike almost everything else
 * the starter hands to the spec. That is not an inconsistency — it is what {@code AimonStackSpec.knowledgeStore}
 * asks for. The stack borrows a supplied store and does not enrol it in its teardown, so if Spring did not close
 * it nobody would. The reverse arrangement, {@code destroyMethod = ""}, is for objects the stack created.
 *
 * <p>
 * Under {@code backend=supplied} nothing is created here at all: the application's bean is its own, closed by
 * whatever created it, and this slice backs off entirely through {@code @ConditionalOnMissingBean}.
 *
 * @see KnowledgeBackend
 */
@AutoConfiguration(before = AimonAutoConfiguration.class)
@ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AimonProperties.class)
public class AimonKnowledgeAutoConfiguration {

    /**
     * Builds the in-memory keyword store under {@code aimon.knowledge.backend=keyword}.
     *
     * <p>
     * The chunk settings reach the store through {@link SimpleDocumentChunker}, which validates them itself. They
     * are checked again in {@code AimonProperties.validateKnowledge()} for the one thing the chunker cannot do:
     * say which property produced the number.
     *
     * @param properties
     *            source of {@code aimon.knowledge.chunk-size} and {@code chunk-overlap}
     * @return the store, closed by Spring
     */
    @Bean
    @ConditionalOnMissingBean(KnowledgeStore.class)
    @ConditionalOnProperty(name = AimonProperties.KNOWLEDGE_BACKEND, havingValue = "keyword")
    KnowledgeStore aimonKeywordKnowledgeStore(AimonProperties properties) {
        final AimonProperties.Knowledge knowledge = properties.getKnowledge();
        return new KeywordKnowledgeStore(
                new SimpleDocumentChunker(knowledge.chunkSizeOrDefault(), knowledge.chunkOverlapOrDefault()));
    }

    /**
     * Turns {@code aimon.knowledge.backend} and whatever store bean exists into what the spec is given.
     *
     * <p>
     * A separate bean rather than a lookup inside {@link AimonAutoConfiguration}, for the reason the session and
     * scheduling slices each own their own selector: this is where the property and the bean are both visible,
     * so this is where a contradiction between them can be named. And a contradiction is what it is —
     * {@code backend=none} with a {@link KnowledgeStore} bean defined means either the property is stale or the
     * bean is, and picking one silently makes the deployment's belief about its own configuration wrong in a way
     * nothing reports.
     *
     * @param properties
     *            the bound configuration
     * @param stores
     *            the store, from the branch above or from the application
     * @return the resolved contribution, empty when knowledge is off
     */
    @Bean
    @ConditionalOnMissingBean
    KnowledgeContribution aimonKnowledgeContribution(AimonProperties properties,
            ObjectProvider<KnowledgeStore> stores) {
        final KnowledgeBackend backend = properties.getKnowledge().getBackend();
        final KnowledgeStore store = stores.getIfAvailable();

        if (backend == KnowledgeBackend.NONE) {
            if (store != null) {
                throw new IllegalStateException("A " + KnowledgeStore.class.getName() + " bean is defined but "
                        + AimonProperties.KNOWLEDGE_BACKEND + "=none, so no agent would be given a knowledge tool"
                        + " to reach it with. Set " + AimonProperties.KNOWLEDGE_BACKEND + "=supplied to use the"
                        + " bean, or remove it.");
            }
            return KnowledgeContribution.none();
        }
        if (store == null) {
            // Only reachable under `supplied`: the keyword branch publishes its own store, so a null there
            // would mean the branch was suppressed by an application bean that then also went missing.
            throw new IllegalStateException(AimonProperties.KNOWLEDGE_BACKEND + "=supplied means the application"
                    + " provides the knowledge store, but no " + KnowledgeStore.class.getName() + " bean is"
                    + " defined. Define one, or set " + AimonProperties.KNOWLEDGE_BACKEND + "=keyword to have"
                    + " AIMON build an in-memory one.");
        }
        return KnowledgeContribution.of(store);
    }

    /**
     * The resolved knowledge store, or the explicit absence of one.
     *
     * <p>
     * A value rather than a nullable bean, because {@code null} is not something an {@code ObjectProvider} can
     * carry: "the selector says none" and "the bean has not been defined yet" would arrive at
     * {@link AimonAutoConfiguration} looking identical, and only one of them is a configuration this starter
     * should assemble.
     */
    public static final class KnowledgeContribution {

        private static final KnowledgeContribution NONE = new KnowledgeContribution(null);

        private final KnowledgeStore store;

        private KnowledgeContribution(KnowledgeStore store) {
            this.store = store;
        }

        /**
         * Returns the contribution for a stack with no knowledge store.
         *
         * @return the empty contribution
         */
        public static KnowledgeContribution none() {
            return NONE;
        }

        /**
         * Returns a contribution carrying the resolved store.
         *
         * @param store
         *            the store (must not be null)
         * @return the contribution
         */
        public static KnowledgeContribution of(KnowledgeStore store) {
            return new KnowledgeContribution(java.util.Objects.requireNonNull(store, "store must not be null"));
        }

        /**
         * Returns the store, or null when knowledge is off.
         *
         * @return the store, or null
         */
        public KnowledgeStore getStore() {
            return store;
        }
    }
}
