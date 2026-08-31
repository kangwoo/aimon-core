package at.aimon.spring.boot.autoconfigure;

import static at.aimon.spring.boot.autoconfigure.AimonProperties.MEMORY_BACKEND;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.MEMORY_BACKEND_IN_MEMORY;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.MEMORY_PEER_ID;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.MEMORY_PEER_MODE;
import static at.aimon.spring.boot.autoconfigure.AimonProperties.MEMORY_REDACTION;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.core.base.Principal;
import at.aimon.core.memory.InMemoryObservationStore;
import at.aimon.core.memory.InMemoryRepresentationStore;
import at.aimon.core.memory.ObservationStore;
import at.aimon.core.memory.RepresentationStore;
import at.aimon.core.memory.Workspace;
import at.aimon.core.memory.redaction.DefaultRedactionPolicy;
import at.aimon.core.memory.redaction.RedactionPolicy;
import at.aimon.core.memory.redaction.StrictRedactionPolicy;

/**
 * Decides what the agents remember between sessions, and whose memory it is.
 *
 * <p>
 * Off by default, like knowledge, but for a sharper reason. A knowledge store that is wired and empty answers
 * queries with silence; a memory that is wired at the wrong peer answers them with somebody else's history. Both
 * failures are quiet, and only one of them is also a privacy incident — which is why
 * {@code aimon.memory.workspace-id} and, under the default peer mode, {@code aimon.memory.peer-id} have no
 * defaults at all. See {@code AimonProperties.validateMemory()}.
 *
 * <h2>What this slice cannot give you</h2>
 *
 * <p>
 * Two gaps outlive any property here, and both are recorded as runtime degradations by
 * {@code MemoryAssembly} rather than left for the operator to notice:
 *
 * <ul>
 * <li><b>The write path.</b> Nothing the stack builds derives a representation — no deriver, no derivation queue,
 * no dreamer. Observations are written by the {@code Observe} tool; representations must come from something else
 * running against the same store, or the injected memory part stays empty for the life of the process.
 * <li><b>The tools, under {@code peer-mode=caller}.</b> A tool receives its observer through a
 * {@code ToolContextEnricher}, which is handed a session, an execution and a runtime — never a principal. One
 * fixed observer is all that seam can carry, so the caller-following mode injects a memory part and registers no
 * tools. See {@link MemoryPeerMode}.
 * </ul>
 *
 * <h2>Ownership</h2>
 *
 * <p>
 * The two stores are Spring beans with inferred {@code close()}, on the same footing as the knowledge store: the
 * spec borrows them and the stack does not enrol them in its teardown, so if Spring did not close them nobody
 * would. Under {@code backend=supplied} nothing is created here and both branches back off.
 *
 * <p>
 * The {@link RedactionPolicy} is the exception and is not published as a bean under {@code default} or
 * {@code strict}. Both built-in policies are stateless, compiled once and own nothing closeable, so a bean would
 * add an injection point without adding an owner — and it would collide with the {@code supplied} value, which
 * exists precisely to read a policy bean the application declared.
 *
 * @see MemoryBackend
 * @see MemoryRedaction
 */
@AutoConfiguration(before = AimonAutoConfiguration.class)
@ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AimonProperties.class)
public class AimonMemoryAutoConfiguration {

    /**
     * Builds the heap-backed representation store under {@code aimon.memory.backend=in-memory}.
     *
     * @return the store, closed by Spring
     */
    @Bean
    @ConditionalOnMissingBean(RepresentationStore.class)
    @ConditionalOnProperty(name = MEMORY_BACKEND, havingValue = MEMORY_BACKEND_IN_MEMORY)
    RepresentationStore aimonInMemoryRepresentationStore() {
        return new InMemoryRepresentationStore();
    }

    /**
     * Builds the heap-backed observation store under {@code aimon.memory.backend=in-memory}.
     *
     * <p>
     * A second bean rather than one factory returning both, because the two are independently replaceable: an
     * application that declares only a {@code RepresentationStore} keeps this one, which is the deployment where
     * representations arrive from elsewhere and observations are still worth collecting locally.
     *
     * @return the store, closed by Spring
     */
    @Bean
    @ConditionalOnMissingBean(ObservationStore.class)
    @ConditionalOnProperty(name = MEMORY_BACKEND, havingValue = MEMORY_BACKEND_IN_MEMORY)
    ObservationStore aimonInMemoryObservationStore() {
        return new InMemoryObservationStore();
    }

    /**
     * Turns {@code aimon.memory.*} and whatever store beans exist into what the spec is given.
     *
     * <p>
     * Where the knowledge slice resolves one selector against one bean, this one resolves three selectors against
     * three, and every contradiction between them is refused by name. The reason is the same in all three cases
     * and is worth stating once: a store, or a policy, that this starter silently declines to use is
     * indistinguishable at runtime from one it uses and finds empty.
     *
     * @param properties
     *            the bound configuration
     * @param representationStores
     *            the representation store, from the branch above or from the application
     * @param observationStores
     *            the observation store, from the branch above or from the application
     * @param redactionPolicies
     *            the application's policy, consulted only under {@code redaction=supplied}
     * @return the resolved contribution, empty when memory is off
     */
    @Bean
    @ConditionalOnMissingBean
    MemoryContribution aimonMemoryContribution(AimonProperties properties,
            ObjectProvider<RepresentationStore> representationStores,
            ObjectProvider<ObservationStore> observationStores, ObjectProvider<RedactionPolicy> redactionPolicies) {
        final AimonProperties.Memory memory = properties.getMemory();
        final RepresentationStore representations = representationStores.getIfAvailable();
        final ObservationStore observations = observationStores.getIfAvailable();

        if (memory.getBackend() == MemoryBackend.NONE) {
            refuseOrphanedStores(representations, observations);
            return MemoryContribution.none();
        }
        requireSomeStore(representations, observations);

        final Workspace workspace = Workspace.builder().id(memory.getWorkspaceId()).build();
        final MemorySpec.Builder builder = memory.peerModeOrDefault() == MemoryPeerMode.FIXED
                ? MemorySpec.forPeer(workspace, Principal.user(memory.getPeerId()))
                : perCaller(workspace, representations);
        return MemoryContribution.of(builder.representationStore(representations).observationStore(observations)
                .injectionMode(memory.getInjectionMode()).maxTokens(memory.maxTokensOrDefault())
                .redactionPolicy(resolveRedaction(memory, redactionPolicies.getIfAvailable())).build());
    }

    /**
     * Refuses a store bean that {@code backend=none} would leave unreachable.
     *
     * <p>
     * The expensive half of this is the observation store: an application can write into one for months through
     * its own code paths while no agent is ever given a tool that reads it back. Nothing errors, and the model
     * simply behaves as though the history did not exist.
     */
    private static void refuseOrphanedStores(RepresentationStore representations, ObservationStore observations) {
        final List<String> orphaned = new ArrayList<>();
        if (representations != null) {
            orphaned.add(RepresentationStore.class.getName());
        }
        if (observations != null) {
            orphaned.add(ObservationStore.class.getName());
        }
        if (!orphaned.isEmpty()) {
            throw new IllegalStateException("A memory store bean is defined (" + String.join(", ", orphaned) + ") but "
                    + MEMORY_BACKEND + "=none, so nothing would ever read it. Set " + MEMORY_BACKEND
                    + "=supplied to use the bean, or remove it.");
        }
    }

    /** Only reachable under {@code supplied}: the in-memory branches publish their own stores. */
    private static void requireSomeStore(RepresentationStore representations, ObservationStore observations) {
        if (representations == null && observations == null) {
            throw new IllegalStateException(MEMORY_BACKEND + "=supplied means the application"
                    + " provides the memory stores, but neither a " + RepresentationStore.class.getName() + " nor an "
                    + ObservationStore.class.getName() + " bean is defined. Define at least one, or" + " set "
                    + MEMORY_BACKEND + "=" + MEMORY_BACKEND_IN_MEMORY + " to have AIMON build both.");
        }
    }

    /**
     * Starts a caller-following spec, refusing the one combination that would wire nothing at all.
     *
     * <p>
     * {@code MemorySpec} rejects this pair too, and its message is the right one for a programmatic caller. This
     * check exists to say it in property names: under {@code peer-mode=caller} the tools are not registered, so
     * an observation store is the one thing this mode has no use for — and a deployment holding only that has
     * configured memory that does exactly nothing.
     */
    private static MemorySpec.Builder perCaller(Workspace workspace, RepresentationStore representations) {
        if (representations == null) {
            throw new IllegalStateException(MEMORY_PEER_MODE + "=caller injects a memory part and"
                    + " registers no memory tools, so it needs a " + RepresentationStore.class.getName()
                    + " to read that part from. With only an observation store this configuration wires nothing"
                    + " whatsoever. Define one, or set " + MEMORY_PEER_MODE + "=fixed with " + MEMORY_PEER_ID
                    + " if the tools are what you wanted.");
        }
        return MemorySpec.perCaller(workspace);
    }

    /**
     * Resolves {@code aimon.memory.redaction} against whatever policy bean exists.
     *
     * @param memory
     *            the bound memory settings
     * @param supplied
     *            the application's policy bean, or null
     * @return the policy, or null under {@code redaction=none}
     */
    private static RedactionPolicy resolveRedaction(AimonProperties.Memory memory, RedactionPolicy supplied) {
        final MemoryRedaction selected = memory.redactionOrDefault();
        if (selected == MemoryRedaction.SUPPLIED && supplied == null) {
            throw new IllegalStateException(MEMORY_REDACTION + "=supplied means the application"
                    + " provides the policy, but no " + RedactionPolicy.class.getName() + " bean is defined."
                    + " Define one, or set " + MEMORY_REDACTION + "=default.");
        }
        if (selected != MemoryRedaction.SUPPLIED && supplied != null) {
            throw new IllegalStateException("A " + RedactionPolicy.class.getName() + " bean is defined but "
                    + MEMORY_REDACTION + "=" + AimonProperties.asPropertyValue(selected)
                    + ", so observations would be stored without ever passing through it. Set " + MEMORY_REDACTION
                    + "=supplied to use the bean, or remove it.");
        }
        return switch (selected) {
            case DEFAULT -> new DefaultRedactionPolicy();
            case STRICT -> new StrictRedactionPolicy();
            case SUPPLIED -> supplied;
            case NONE -> null;
        };
    }

    /**
     * The resolved memory spec, or the explicit absence of one.
     *
     * <p>
     * A value rather than a nullable {@code MemorySpec} bean, for the reason {@code KnowledgeContribution} is
     * one: "memory is off" and "the spec bean has not been defined yet" would arrive at
     * {@link AimonAutoConfiguration} looking identical. {@code MemorySpec} has no representation of its own for
     * "off" either — it refuses to be built without a store — so the absence has to be carried by something.
     */
    public static final class MemoryContribution {

        private static final MemoryContribution NONE = new MemoryContribution(null);

        private final MemorySpec spec;

        private MemoryContribution(MemorySpec spec) {
            this.spec = spec;
        }

        /**
         * Returns the contribution for a stack with no memory.
         *
         * @return the empty contribution
         */
        public static MemoryContribution none() {
            return NONE;
        }

        /**
         * Returns a contribution carrying the resolved spec.
         *
         * @param spec
         *            the spec (must not be null)
         * @return the contribution
         */
        public static MemoryContribution of(MemorySpec spec) {
            return new MemoryContribution(Objects.requireNonNull(spec, "spec must not be null"));
        }

        /**
         * Returns the spec, or null when memory is off.
         *
         * @return the spec, or null
         */
        public MemorySpec getSpec() {
            return spec;
        }
    }
}
