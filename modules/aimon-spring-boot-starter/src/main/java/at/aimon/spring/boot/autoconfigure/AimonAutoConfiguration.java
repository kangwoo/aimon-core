package at.aimon.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.bootstrap.RuntimeDegradations;
import at.aimon.bootstrap.spec.AgentRuntimeSpec;
import at.aimon.bootstrap.spec.AgentSpec;
import at.aimon.bootstrap.spec.AimonAgentCustomizer;
import at.aimon.bootstrap.spec.CredentialStoreFactory;
import at.aimon.bootstrap.spec.ExecutorSpec;
import at.aimon.bootstrap.spec.FileSystemSpec;
import at.aimon.bootstrap.spec.LlmSpec;
import at.aimon.bootstrap.spec.MemorySpec;
import at.aimon.bootstrap.spec.SchedulingSpec;
import at.aimon.bootstrap.spec.SessionSpec;
import at.aimon.bootstrap.spec.SkillApprovalChannelFactory;
import at.aimon.bootstrap.spec.SkillApprovalSpec;
import at.aimon.bootstrap.spec.ToolSpec;
import at.aimon.core.agent.Agent;
import at.aimon.core.agent.budget.ExecutionBudget;
import at.aimon.core.agent.queue.MessageQueueRepository;
import at.aimon.core.credential.CredentialStore;
import at.aimon.core.credential.InMemoryCredentialStore;
import at.aimon.core.knowledge.KnowledgeStore;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.skill.policy.agent.AgentApprovalStore;
import at.aimon.core.skill.policy.approval.SkillApprovalChannel;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;
import at.aimon.core.skill.policy.session.SessionApprovalStore;
import at.aimon.core.tracing.TracePayloadPolicy;
import at.aimon.core.tracing.Tracer;
import at.aimon.core.tracing.impl.TracingLlmClient;
import at.aimon.spring.boot.AimonAgents;
import at.aimon.spring.boot.AimonSessions;
import at.aimon.spring.boot.DefaultAimonAgents;
import at.aimon.spring.boot.DefaultAimonSessions;
import at.aimon.spring.boot.DisabledAimonAgents;
import at.aimon.spring.boot.DisabledAimonSessions;

/**
 * Assembles the ingredients contributed by the other slices into a running {@link AimonStack}.
 *
 * <p>
 * Ordered {@code after} the LLM, filesystem and session slices. That only orders the <em>definitions</em>, not
 * the instantiation — but it is what makes the {@code @ConditionalOnMissingBean} checks here see those beans if
 * they are going to exist at all, which is the only guarantee those conditions can safely rely on.
 *
 * <p>
 * <b>Two branches, one facade.</b> {@code aimon.enabled=false} has to be usable as a profile-level kill switch,
 * which means the application's own beans must keep starting. Backing this class off entirely would break every
 * host bean that injects {@link AimonSessions}, so the disabled branch publishes a facade that throws instead —
 * see {@code DisabledAimonSessions}. Nothing else is created on that path: no LLM client, no agent runtime, no
 * workspace directory, no scheduler thread.
 *
 * <p>
 * <b>Start-up.</b> Creating the stack bean assembles it; it does not start it. Registering agent runtimes and
 * starting the scheduler happen in two {@link org.springframework.context.SmartLifecycle} beans that sit either
 * side of Boot's web server — see {@link AimonRuntimeLifecycle} and {@link AimonSchedulingLifecycle}. Fail-fast
 * stays at bean creation regardless, because a throw from {@code SmartLifecycle.start()} fails the refresh just
 * as a throw from a factory method does; moving it would buy a worse message, not a softer failure.
 *
 * <p>
 * <b>Teardown.</b> The stack is the only bean here with a {@code destroyMethod}, and its {@code close()} runs an
 * ordered teardown of everything it built. Everything the starter <em>passes into</em> the spec is either owned
 * elsewhere (the LLM client is a bean; a host-supplied filesystem or session store is a bean) or created by the
 * stack itself and never published as a bean (the {@code localAt} filesystem, the fallback in-memory session
 * store). So no resource has two destruction edges. Ordering between the stack and its dependencies is Spring's
 * to enforce: dependents are destroyed before dependencies, and both {@code AimonSessions} and the spec sit on
 * that chain.
 *
 * <p>
 * <b>Native image.</b> {@link AimonRuntimeHints} hangs here rather than off one of the narrower slices because it
 * declares things that are reached from the framework itself, not from a branch a property selects — and because
 * this is the one class in the starter that is active whenever the starter is present at all. Hints are collected
 * at AOT processing time; a hint declared on a slice the application happens not to enable would simply not be
 * written.
 */
@AutoConfiguration(after = {AimonLlmAutoConfiguration.class, AimonFileSystemAutoConfiguration.class,
        AimonSessionAutoConfiguration.class, AimonSchedulingAutoConfiguration.class})
@ConditionalOnClass(Agent.class)
@ImportRuntimeHints(AimonRuntimeHints.class)
public class AimonAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AimonAutoConfiguration.class);

    /** The branch taken unless {@code aimon.enabled=false}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "true", matchIfMissing = true)
    @EnableConfigurationProperties(AimonProperties.class)
    static class EnabledConfiguration {

        /**
         * Name of the bean that re-exports the stack's pending-turn registry.
         *
         * <p>
         * A constant because three places must agree on it: the {@code @Bean} that publishes it,
         * {@link #applicationPendingTurnRegistry(ConfigurableListableBeanFactory)}, which excludes it so that
         * re-exporting an input does not feed the stack back into itself, and
         * {@link PendingTurnRegistryConsistencyCheck}, which reads it to tell the one shape that exclusion
         * cannot see apart from the shapes it can.
         */
        static final String PENDING_TURN_REGISTRY_BEAN = "aimonPendingTurnRegistry";

        /**
         * Name of the bean that gathers the application's optional contributions.
         *
         * <p>
         * Needed by name because every dependency edge this class registers points at it — see
         * {@link ApplicationBeans#registerDestructionEdge(ConfigurableListableBeanFactory, Class, Object, String)}.
         */
        static final String CONTRIBUTIONS_BEAN = "aimonApplicationContributions";

        /**
         * Fails by property name when {@code aimon.llm.provider} selected nothing.
         *
         * <p>
         * {@code aimon.llm.provider} is the one selector that is a {@code String} rather than an enum, because
         * a third-party module has to be able to contribute a value this starter has never heard of. The cost
         * of that openness is that a typo cannot be caught at binding time: {@code provider: anthropci} simply
         * matches no branch. What the user then sees is a missing {@code LlmClient} bean — an accurate report
         * of the symptom that never mentions the property that caused it, or the vendor module that might be
         * missing from the classpath. This bean is what turns that back into a sentence about configuration.
         *
         * <p>
         * It lives here rather than in the LLM slice because it has to be decided <em>after</em> that slice's
         * bean definitions are registered, and {@code @ConditionalOnMissingBean} only reads what is already
         * there. Between two auto-configurations {@code @AutoConfiguration(after = ...)} guarantees that;
         * between two nested classes of one auto-configuration nothing does.
         */
        @Bean
        @ConditionalOnMissingBean(LlmClient.class)
        LlmClient aimonUnresolvedLlmClient(AimonProperties properties) {
            final String provider = properties.getLlm().getProvider();
            if (AimonProperties.PROVIDER_NONE.equals(provider)) {
                throw new IllegalStateException(AimonProperties.LLM_PROVIDER + "=" + AimonProperties.PROVIDER_NONE
                        + " tells this starter to build no LlmClient, but the application defines no LlmClient"
                        + " bean either. Define one, or name a built-in provider (" + AimonProperties.PROVIDER_ANTHROPIC
                        + ", " + AimonProperties.PROVIDER_OPENAI + ").");
            }
            throw new IllegalStateException(AimonProperties.LLM_PROVIDER + "=" + provider + " produced no LlmClient."
                    + " The built-in values are " + AimonProperties.PROVIDER_ANTHROPIC + " (needs aimon-llm-anthropic"
                    + " on the classpath), " + AimonProperties.PROVIDER_OPENAI + " (needs aimon-llm-openai) and "
                    + AimonProperties.PROVIDER_NONE + " (you define the bean). Check the spelling, add the missing"
                    + " vendor module, or define your own LlmClient bean.");
        }

        /**
         * Resolves the beans a host application may define, all of which are optional.
         *
         * <p>
         * Almost every one of these is an {@code ObjectProvider}, because a required parameter for something
         * most applications do not define would make the common configuration fail to start. Resolving them
         * here rather than as parameters of {@link #aimonStackSpec} moves them off the edge Spring destroys
         * along, and that edge is put back by hand — see {@link ApplicationBeans} for why a provider does not
         * record one, and why the reverse-creation order it falls back to is right by accident rather than by
         * construction. Every slice of this starter now does the same thing through that class, so the chain
         * the stack is torn down along is the same one it was built along: stack, spec, this, the contributed
         * bean. Measured rather than assumed; the assertions in {@code AimonApplicationContributionsTest} are
         * what keep it measured.
         *
         * <p>
         * That they are gathered rather than listed one by one is what keeps the spec factory readable as the
         * set of extension points grows; each pair below is also two alternatives rather than two settings,
         * and this is where that pairing is visible. The parameter count is the job rather than a smell —
         * gathering is the whole purpose — so the {@code ParameterNumber} check is suppressed here and nowhere
         * that would hide a method doing several things.
         *
         * <p>
         * The {@link Tracer} is the one entry the starter may itself have filled — the observability slice
         * defines one under {@code aimon.tracing.enabled=true}, and backs off if the application defined its
         * own. It belongs here anyway, because from the spec factory's side that is the same question the other
         * five answer: was there one? It gets an edge like everything else — not because a tracer owns a
         * resource to be closed in sequence with anything, but because "does this one need an edge?" is a
         * question worth not having per entry. The customizers go through
         * {@link ApplicationBeans#resolveAll} for exactly that reason: they were the one entry still answering
         * it, and "a callback holds nothing" is a fact about the implementations that exist rather than about
         * the extension point.
         *
         * <p>
         * The last four are the approval-axis stores and the mid-turn input queue. They are gathered rather than
         * given a slice of their own because there is no property to weigh them against: nothing here selects a
         * backend for them, since none ships. The bean is the whole configuration, and its absence means the
         * node-local default. Three of them resolve like everything above; the fourth cannot use a provider at
         * all, and pays for that separately: see
         * {@link #applicationPendingTurnRegistry(ConfigurableListableBeanFactory)}.
         */
        @Bean(CONTRIBUTIONS_BEAN)
        @SuppressWarnings("checkstyle:ParameterNumber")
        ApplicationContributions aimonApplicationContributions(ObjectProvider<AimonAgentCustomizer> agentCustomizers,
                ObjectProvider<CredentialStore> credentialStores,
                ObjectProvider<CredentialStoreFactory> credentialStoreFactories,
                ObjectProvider<SkillApprovalChannel> approvalChannels,
                ObjectProvider<SkillApprovalChannelFactory> approvalChannelFactories, ObjectProvider<Tracer> tracers,
                ObjectProvider<AgentApprovalStore> agentApprovalStores,
                ObjectProvider<SessionApprovalStore> sessionApprovalStores,
                ObjectProvider<MessageQueueRepository> messageQueueRepositories,
                ConfigurableListableBeanFactory beanFactory) {
            return new ApplicationContributions(
                    ApplicationBeans.resolveAll(agentCustomizers, AimonAgentCustomizer.class, beanFactory,
                            CONTRIBUTIONS_BEAN),
                    resolve(credentialStores, CredentialStore.class, beanFactory),
                    resolve(credentialStoreFactories, CredentialStoreFactory.class, beanFactory),
                    resolve(approvalChannels, SkillApprovalChannel.class, beanFactory),
                    resolve(approvalChannelFactories, SkillApprovalChannelFactory.class, beanFactory),
                    resolve(tracers, Tracer.class, beanFactory),
                    resolve(agentApprovalStores, AgentApprovalStore.class, beanFactory),
                    resolve(sessionApprovalStores, SessionApprovalStore.class, beanFactory),
                    applicationPendingTurnRegistry(beanFactory),
                    resolve(messageQueueRepositories, MessageQueueRepository.class, beanFactory));
        }

        /**
         * Shorthand for {@link ApplicationBeans#resolve} with this class's dependent bean.
         *
         * @param provider
         *            the provider for the contribution's type
         * @param type
         *            the contribution's type
         * @param beanFactory
         *            the context's bean factory
         * @param <T>
         *            the contribution's type
         * @return the contributed bean, or {@code null} when the application defined none
         */
        private static <T> T resolve(ObjectProvider<T> provider, Class<T> type,
                ConfigurableListableBeanFactory beanFactory) {
            return ApplicationBeans.resolve(provider, type, beanFactory, CONTRIBUTIONS_BEAN);
        }

        /**
         * Resolves an application-published {@link PendingTurnRegistry}, ignoring the one this starter
         * re-exports.
         *
         * <p>
         * The registry is the only entry here that the starter also <em>publishes</em>:
         * {@link #aimonPendingTurnRegistry(AimonStack)} hands out the stack's own instance so an application can
         * build an approval endpoint over it. Once the same type is also an input, an {@code ObjectProvider}
         * closes a cycle — resolving it creates the re-export, which needs the stack, which needs the spec, which
         * needs this. Every application using the starter would fail to start with
         * {@code BeanCurrentlyInCreationException}, whether or not it published a registry of its own.
         *
         * <p>
         * So the lookup is by definition rather than by instance: {@code allowEagerInit=false} reads the declared
         * return types without creating anything, and the re-export is dropped by name. What is left is what the
         * application declared, which is the only thing this seam ever meant. The exclusion is by the constant the
         * {@code @Bean} is named with, so the two cannot drift.
         *
         * <p>
         * By name, rather than by asking whether the definition is the one this class declares, because the
         * structural question has no answer in an AOT-processed context — generated bean definitions carry no
         * factory-method metadata, and the check would quietly stop excluding anything, which is the cycle
         * again. The cost is the one shape the name cannot tell apart: an application bean the application
         * itself named {@value #PENDING_TURN_REGISTRY_BEAN} is read as the re-export and ignored, leaving the
         * node-local default. Naming a bean after the starter's own is not a thing to support — but it is a
         * thing to <em>notice</em>, because the stack then suspends into a registry the application cannot
         * reach and nothing says so. {@link PendingTurnRegistryConsistencyCheck} fails the context for it.
         *
         * <p>
         * The edge Spring records for the entries above has to be registered by hand here for the same reason
         * it does there — {@code getBean(...)} does not record who asked either — so the chain ends up
         * identical: stack, spec, this, the registry. Ambiguity is handled by hand for the same reason: this
         * lookup does not inherit the provider's refusal to guess, so it reproduces it — honouring
         * {@code @Primary} and otherwise throwing the exception a provider would have thrown, though not its
         * {@code @Priority} fallback, for the reason in {@link #soleCandidate}.
         *
         * <p>
         * The other bound is narrower than it first reads. A registry contributed by a {@code FactoryBean} whose
         * type is only knowable by instantiating it is not seen here, and the stack keeps its node-local default
         * — but that bean is invisible to <em>every</em> by-type view, not only to this scan, so the
         * application's own {@code PendingTurnRegistry} injection point resolves to the re-export and therefore
         * to the very registry the stack is using. The two views do not disagree; there is simply a bean AIMON
         * never sees, reachable only by asking for it by name. Measured, after this was twice written down as a
         * divergence to catch: see {@link PendingTurnRegistryConsistencyCheck}.
         *
         * @param beanFactory
         *            the context's bean factory
         * @return the application's registry, or {@code null} when it published none
         */
        private static PendingTurnRegistry applicationPendingTurnRegistry(ConfigurableListableBeanFactory beanFactory) {
            final List<String> candidates = new ArrayList<>();
            for (String name : beanFactory.getBeanNamesForType(PendingTurnRegistry.class, true, false)) {
                if (!PENDING_TURN_REGISTRY_BEAN.equals(name)) {
                    candidates.add(name);
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            final String name = candidates.size() == 1 ? candidates.get(0) : soleCandidate(candidates, beanFactory);
            final PendingTurnRegistry registry = beanFactory.getBean(name, PendingTurnRegistry.class);
            beanFactory.registerDependentBean(name, CONTRIBUTIONS_BEAN);
            return registry;
        }

        /**
         * Reduces several {@link PendingTurnRegistry} candidates to the {@code @Primary} one, or refuses.
         *
         * <p>
         * Taking the first would be worse than failing in a way that is easy to miss: with two registries the
         * re-export has already backed off, so the application's own injection point throws while the stack
         * quietly suspends turns into whichever one iteration order surfaced first. Half the deployment would
         * then be looking in the wrong registry for a turn that is genuinely there.
         *
         * <p>
         * {@code @Primary} is the whole of it, which is narrower than an {@code ObjectProvider} would be:
         * autowiring falls back to {@code @Priority} when nothing is primary, and this does not. Reading that
         * annotation means having an <em>instance</em> of every candidate, and not instantiating candidates is
         * the entire reason this lookup reads definitions in the first place — honouring it would reopen the
         * cycle. So the narrower rule is deliberate, and a deployment that wants to choose between two
         * registries marks one {@code @Primary}.
         *
         * @param candidates
         *            the application's registry bean names, at least two
         * @param beanFactory
         *            the context's bean factory
         * @return the sole primary candidate
         * @throws NoUniqueBeanDefinitionException
         *             when none or several are primary
         */
        private static String soleCandidate(List<String> candidates, ConfigurableListableBeanFactory beanFactory) {
            final List<String> primaries = candidates.stream().filter(
                    name -> beanFactory.containsBeanDefinition(name) && beanFactory.getBeanDefinition(name).isPrimary())
                    .toList();
            if (primaries.size() == 1) {
                return primaries.get(0);
            }
            throw new NoUniqueBeanDefinitionException(PendingTurnRegistry.class, candidates);
        }

        /**
         * Gathers what this starter's own slices resolved, so the spec factory reads one value per origin.
         *
         * <p>
         * The two are alike in the way that matters here: each is a decision a slice made by weighing a selector
         * property against a bean that may or may not exist, and each carries an "off" that a nullable bean
         * could not express. Neither is a {@code *Spec} the way the filesystem, session and scheduling slices'
         * contributions are — those types have an off state of their own and can be passed directly.
         *
         * <p>
         * Gathering costs no destruction ordering. The edge Spring destroys along is the parameter chain, and
         * that chain still ends at the stores: stack, spec, this, the contribution, the store bean.
         */
        @Bean
        SliceContributions aimonSliceContributions(AimonKnowledgeAutoConfiguration.KnowledgeContribution knowledge,
                AimonMemoryAutoConfiguration.MemoryContribution memory) {
            return new SliceContributions(knowledge.getStore(), memory.getSpec());
        }

        /**
         * Collects the slices' contributions into the immutable spec the stack is built from.
         *
         * <p>
         * {@link LlmClient} is a plain parameter rather than an {@code ObjectProvider} on purpose. The
         * parameter is what registers the dependency edge that makes Spring destroy the stack before the
         * client's connection pool; resolving it lazily through a provider would leave the ordering to
         * registration order, which happens to be right today and would stay right only by accident.
         * {@code slices} is a plain parameter for the same reason and with more at stake: the knowledge store
         * and both memory stores <em>are</em> Spring beans with a {@code close()} whenever they exist at all, so
         * the stack has to be destroyed before them or a shutting-down agent would search an index — or read a
         * memory — that had already been torn down.
         *
         * <p>
         * The tracer is the exception and arrives inside {@code contributions} instead, because there is nothing
         * to order against — a {@link Tracer} owns no resource — and because under
         * {@code aimon.tracing.enabled=false} the bean does not exist at all.
         *
         * <h4>One tracer, two seams</h4>
         *
         * <p>
         * The tracer is installed twice and the two are not alternatives. {@code ExecutorSpec.tracer(...)}
         * records the per-turn span tree and writes the parent-span tags onto the LLM call metadata;
         * {@link TracingLlmClient} reads those tags back and records the model call as a child. Give the two
         * different tracer instances and the second one's spans name a parent the first one's store has never
         * heard of — which is why both are handed {@code tracer}, the single bean, here rather than each
         * building its own.
         *
         * <p>
         * The payload policy is passed to both for the same reason: it decides whether message content is
         * captured, and a deployment that set {@code payload-capture=none} would otherwise still find response
         * text in its LLM spans.
         */
        @Bean
        @ConditionalOnMissingBean
        AimonStackSpec aimonStackSpec(AimonProperties properties, LlmClient llmClient, FileSystemSpec fileSystemSpec,
                SessionSpec sessionSpec, SchedulingSpec schedulingSpec, ApplicationContributions contributions,
                SliceContributions slices) {
            final Tracer tracer = contributions.getTracer();
            final TracePayloadPolicy payloadPolicy = properties.getTracing().toPayloadPolicy();
            final AimonStackSpec.Builder builder = AimonStackSpec.builder()
                    .llm(LlmSpec
                            .of(tracer == null ? llmClient : new TracingLlmClient(llmClient, tracer, payloadPolicy)))
                    .executor(tracer == null
                            ? ExecutorSpec.defaults()
                            : ExecutorSpec.builder().tracer(tracer).tracePayloadPolicy(payloadPolicy).build())
                    .knowledgeStore(slices.getKnowledgeStore()).memory(slices.getMemory()).fileSystem(fileSystemSpec)
                    .session(sessionSpec).scheduling(schedulingSpec).agents(toAgentSpecs(properties))
                    .agentRuntimes(toAgentRuntimeSpec(properties.getAgentRuntime()))
                    .agentCustomizers(contributions.getAgentCustomizers())
                    .defaultBudget(toBudget(properties.getBudget()))
                    .tools(ToolSpec.builder().bashEnabled(properties.getTools().getBash().isEnabled()).build())
                    .messageQueueRepository(contributions.getMessageQueueRepository()).skillApproval(
                            toSkillApproval(properties.getSkill().getApproval(), contributions.getApprovalChannel(),
                                    contributions.getApprovalChannelFactory(), contributions));
            applyCredentials(builder, toCredentialStore(properties.getCredentials()),
                    contributions.getCredentialStore(), contributions.getCredentialStoreFactory());
            return builder.build();
        }

        /**
         * Turns {@code aimon.skill.approval.*} into the spec that decides how an unapproved skill is answered.
         *
         * <p>
         * The TTL is applied to every mode rather than only to {@code suspend}, and that is deliberate: a mode
         * is a thing a deployment flips while debugging, and a TTL that silently reverted to the framework
         * default on the way past {@code deny} would be a setting that stops meaning what it says. Under the
         * other modes nothing suspends, so it applies to nothing.
         */
        private static SkillApprovalSpec toSkillApproval(AimonProperties.Skill.Approval approval,
                SkillApprovalChannel channel, SkillApprovalChannelFactory channelFactory,
                ApplicationContributions contributions) {
            final SkillApprovalSpec mode = switch (approval.getMode()) {
                case DENY -> SkillApprovalSpec.denyAll();
                case ALLOW_LIST -> SkillApprovalSpec.allowList(approval.getAllow());
                case SUSPEND -> SkillApprovalSpec.suspend();
                case CHANNEL -> toSuppliedChannel(channel, channelFactory);
            };
            final SkillApprovalSpec withTtl = approval.getPendingTurnTtl() == null
                    ? mode
                    : mode.withPendingTurnTtl(approval.getPendingTurnTtl());
            return withApplicationStores(withTtl, contributions);
        }

        /**
         * Applies the approval-axis stores the application published, leaving the rest node-local.
         *
         * <p>
         * Applied under every mode for the same reason the TTL is: a mode is something a deployment flips while
         * debugging, and a store that silently reverted to in-memory on the way past {@code deny} would be a
         * bean that stops meaning what it says. Under {@code deny} nothing is ever approved, so the stores hold
         * nothing — which costs nothing and keeps one less thing conditional.
         *
         * <p>
         * Each is applied only when the bean exists, rather than substituting an explicit in-memory instance for
         * the absent ones. The stack registers its {@code distributed-approvals} degradation precisely on the
         * stores the spec left empty, so filling them in here would announce a distributed deployment as fully
         * shared while every answer stayed on one node.
         *
         * @param spec
         *            the spec built from the approval mode and TTL
         * @param contributions
         *            the gathered application beans
         * @return the spec carrying whichever stores were published
         */
        private static SkillApprovalSpec withApplicationStores(SkillApprovalSpec spec,
                ApplicationContributions contributions) {
            SkillApprovalSpec result = spec;
            if (contributions.getAgentApprovalStore() != null) {
                result = result.withAgentApprovalStore(contributions.getAgentApprovalStore());
            }
            if (contributions.getSessionApprovalStore() != null) {
                result = result.withSessionApprovalStore(contributions.getSessionApprovalStore());
            }
            if (contributions.getPendingTurnRegistry() != null) {
                result = result.withPendingTurnRegistry(contributions.getPendingTurnRegistry());
            }
            return result;
        }

        /**
         * Picks the application's approval channel bean, and refuses both shapes of it.
         *
         * <p>
         * Same reasoning as {@link #applyCredentials}: the spec rejects the pair as well, but only this method
         * knows they arrived as beans and can name the types to remove one of. The two are not layered because
         * they are the same channel expressed twice — the factory exists only so that a channel that must
         * <em>record</em> its answers can be handed the stack's own approval stores, which do not exist until
         * the stack is being assembled.
         *
         * <p>
         * Neither being present is a configuration error rather than a fallback to deny-all. {@code mode=channel}
         * is a statement that the application answers approvals itself, so quietly denying every skill instead
         * would leave a deployment believing its channel was in use.
         */
        private static SkillApprovalSpec toSuppliedChannel(SkillApprovalChannel channel,
                SkillApprovalChannelFactory channelFactory) {
            if (channel != null && channelFactory != null) {
                throw new IllegalStateException("Both a " + SkillApprovalChannel.class.getName() + " bean and a "
                        + SkillApprovalChannelFactory.class.getName() + " bean are defined, and they are"
                        + " alternatives: the factory exists only to build the channel over the stack's own"
                        + " approval stores. Remove whichever one does not need those stores.");
            }
            if (channelFactory != null) {
                return SkillApprovalSpec.channelFactory(channelFactory);
            }
            if (channel != null) {
                return SkillApprovalSpec.channel(channel);
            }
            throw new IllegalStateException(AimonProperties.SKILL_APPROVAL_MODE
                    + "=channel means the application answers skill approvals itself," + " but no "
                    + SkillApprovalChannel.class.getName() + " or " + SkillApprovalChannelFactory.class.getName()
                    + " bean is defined. Define one — the factory"
                    + " variant if the channel has to record its answers in the stack's approval stores — or"
                    + " choose another " + AimonProperties.SKILL_APPROVAL_MODE + ".");
        }

        /**
         * Turns each {@code aimon.agents} entry into the spec the stack builds a startup runtime from.
         *
         * <p>
         * The map key is the ref and the entry's {@code bundle} is only where the definition is read from, so
         * the two are set separately rather than the ref being derived from the bundle's own frontmatter name.
         * A deployment that runs one bundle under two refs needs exactly that separation, and letting the
         * frontmatter win would quietly collapse the two back into one runtime id.
         *
         * <p>
         * The default agent is included even when it is not declared under {@code aimon.agents} — naming it in
         * {@code aimon.agent-defaults.default-agent} alone is the minimal configuration this starter documents,
         * and it means "the bundle of that name, with nothing to say about it".
         */
        private static List<AgentSpec> toAgentSpecs(AimonProperties properties) {
            if (properties.getAgents().isEmpty()) {
                return List.of(AgentSpec.named(properties.resolveDefaultAgentRef()));
            }
            return properties.getAgents().entrySet().stream().map(entry -> {
                final String bundle = entry.getValue().getBundle();
                return AgentSpec.builder().name(entry.getKey())
                        .bundleName(bundle == null || bundle.isBlank() ? null : bundle)
                        .properties(entry.getValue().getProperties()).build();
            }).toList();
        }

        /**
         * Applies the bound tenant-runtime settings, leaving each unset one to the framework default.
         */
        private static AgentRuntimeSpec toAgentRuntimeSpec(AimonProperties.AgentRuntimeProperties runtime) {
            final AgentRuntimeSpec.Builder builder = AgentRuntimeSpec.builder().eviction(runtime.getEviction());
            if (runtime.getIdleTtl() != null) {
                builder.idleTtl(runtime.getIdleTtl());
            }
            if (runtime.getSweepInterval() != null) {
                builder.sweepInterval(runtime.getSweepInterval());
            }
            if (runtime.getMaxEntries() != null) {
                builder.maxEntries(runtime.getMaxEntries());
            }
            return builder.build();
        }

        /**
         * Builds the shared store {@code aimon.credentials.*} describes, or nothing when it describes none.
         *
         * <p>
         * Nothing rather than an empty store, because the two are not the same answer downstream: the stack
         * leaves {@code getCredentialStore()} empty when no store was set, and a tool that asks for a credential
         * then says none is configured rather than that this one is missing. An empty store would turn every
         * such deployment's diagnostics into the second message.
         *
         * @param profiles
         *            the bound tree, profile to field to value
         * @return the store, or {@code null} when the tree is empty
         */
        private static CredentialStore toCredentialStore(Map<String, Map<String, String>> profiles) {
            if (profiles.isEmpty()) {
                return null;
            }
            final InMemoryCredentialStore.Builder store = InMemoryCredentialStore.builder();
            profiles.forEach(store::profile);
            return store.build();
        }

        /**
         * Wires whichever of the three credential sources this deployment used, and refuses any two.
         *
         * <p>
         * {@link AimonStackSpec} rejects the store/factory pair too, but from a builder that can only say the two
         * settings are exclusive. Only this method knows where each arrived from, so only it can name the thing
         * the deployment has to remove — and one of the three is now a property, which the builder cannot see at
         * all.
         *
         * <p>
         * They are alternatives rather than layers. A store is one set of credentials for every runtime, a
         * factory is a set per tenant, and the properties build the shared store; a deployment that supplied two
         * of them would have one win silently, at a point where getting it wrong hands one customer's credentials
         * to another. Letting the bean quietly beat the properties would be the more conventional choice and the
         * worse one here: the operator who wrote a password into configuration and watched it be ignored has no
         * symptom to search for except a tool reporting the credential missing.
         */
        private static void applyCredentials(AimonStackSpec.Builder builder, CredentialStore fromProperties,
                CredentialStore store, CredentialStoreFactory factory) {
            final List<String> sources = new ArrayList<>();
            if (fromProperties != null) {
                sources.add(AimonProperties.CREDENTIALS + ".*");
            }
            if (store != null) {
                sources.add("a " + CredentialStore.class.getName() + " bean");
            }
            if (factory != null) {
                sources.add("a " + CredentialStoreFactory.class.getName() + " bean");
            }
            if (sources.size() > 1) {
                throw new IllegalStateException("Credentials are configured in more than one place — "
                        + String.join(" and ", sources) + " — and these are alternatives: the store is shared by"
                        + " every agent runtime, the factory makes one per tenant, and the properties build the"
                        + " shared store. Keep whichever matches how this deployment scopes credentials and"
                        + " remove the rest.");
            }
            if (fromProperties != null) {
                builder.credentialStore(fromProperties);
            } else if (store != null) {
                builder.credentialStore(store);
            } else if (factory != null) {
                builder.credentialStoreFactory(factory);
            }
        }

        /**
         * Assembles the stack and reports whatever capability it had to give up on the way.
         *
         * <p>
         * Assembles rather than starts. {@code AimonStack.from} would register the runtimes here, in the middle
         * of {@code refresh()}, where a schedule or an inbound session could resolve one while other beans are
         * still being created; {@link AimonRuntimeLifecycle} and {@link AimonSchedulingLifecycle} do it either
         * side of the web server instead. What stays here is everything that has to fail the context rather than
         * a lifecycle callback — which is all of it, because a throw inside {@code SmartLifecycle.start()} fails
         * the refresh just the same, only with a less direct message.
         *
         * <p>
         * The stack is closed before rethrowing under {@code fail-fast}: a bean whose factory method throws
         * never gets a destroy callback registered, so an already-assembled stack would leak its threads and
         * connections into a context that is on its way down.
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        AimonStack aimonStack(AimonStackSpec spec, AimonProperties properties) {
            final AimonStack stack = AimonStack.assembled(spec);
            final RuntimeDegradations degradations = stack.degradations();
            if (degradations.isEmpty()) {
                return stack;
            }
            if (properties.isFailFast()) {
                stack.close();
                throw new IllegalStateException(
                        AimonProperties.FAIL_FAST + "=true and the assembled stack is missing capability:\n"
                                + degradations.describe() + "\nConfigure the missing pieces, or set "
                                + AimonProperties.FAIL_FAST + "=false to start anyway.");
            }
            log.warn("AIMON started with reduced capability:\n{}", degradations.describe());
            return stack;
        }

        /**
         * The two beans that own start-up order; see each class for the phase it sits at and why.
         *
         * <p>
         * Both are plain {@code @Bean}s rather than conditional ones. The scheduling half is inert under
         * {@code aimon.scheduling.backend=none}, and registering it anyway keeps the order a fixed property of
         * the starter instead of something a deployment acquires along with a backend.
         *
         * @param stack
         *            the assembled stack these two start
         * @return the lifecycle bean
         */
        @Bean
        @ConditionalOnMissingBean
        AimonRuntimeLifecycle aimonRuntimeLifecycle(AimonStack stack) {
            return new AimonRuntimeLifecycle(stack);
        }

        /**
         * @param stack
         *            the assembled stack whose scheduling engine this starts and stops
         * @param properties
         *            source of {@code aimon.scheduling.auto-startup}
         * @return the lifecycle bean
         * @see #aimonRuntimeLifecycle(AimonStack)
         */
        @Bean
        @ConditionalOnMissingBean
        AimonSchedulingLifecycle aimonSchedulingLifecycle(AimonStack stack, AimonProperties properties) {
            return new AimonSchedulingLifecycle(stack, properties.getScheduling().startsAutomatically());
        }

        @Bean
        @ConditionalOnMissingBean
        AimonSessions aimonSessions(AimonStack stack, AimonProperties properties) {
            return new DefaultAimonSessions(stack.sessionRouter(), properties.resolveDefaultAgentRef(),
                    stack.spec().getDefaultBudget());
        }

        @Bean
        @ConditionalOnMissingBean
        AimonAgents aimonAgents(AimonStack stack) {
            return new DefaultAimonAgents(stack.agentDescriptors(), stack.agentRuntimes());
        }

        /**
         * Publishes the pending-turn registry so an application can build an approval endpoint over it.
         *
         * <p>
         * Suspending a turn is only half of {@code mode=suspend}: something has to list what is waiting and
         * approve it. That something is the application's, not the starter's — an HTTP endpoint here would have
         * to invent an authentication story for the one decision a deployment is least likely to want invented
         * for it. So the registry is exposed and the endpoint is not.
         *
         * <p>
         * {@code destroyMethod = ""} because the stack owns this object. It is not the starter's to close, and
         * an inferred {@code close()} would run it twice — once as this bean, once inside
         * {@link AimonStack#close()}. The reaper that expires entries is deliberately <em>not</em> a bean for
         * the same reason: it has both a {@code start()} and a {@code close()}, and Spring would attach itself
         * to the second while the stack still owns the first.
         *
         * @param stack
         *            the assembled stack that owns the registry
         * @return the registry
         */
        @Bean(name = PENDING_TURN_REGISTRY_BEAN, destroyMethod = "")
        @ConditionalOnMissingBean
        PendingTurnRegistry aimonPendingTurnRegistry(AimonStack stack) {
            return stack.pendingTurnRegistry();
        }

        /**
         * Refuses a context where the registry turns suspend into is not the one an application can inject.
         *
         * <p>
         * The shape {@link #applicationPendingTurnRegistry(ConfigurableListableBeanFactory)} cannot tell apart —
         * an application bean wearing the re-export's name — is documented there as an accepted cost, and it was,
         * right up to the point of noticing it fails with no symptom at all. This bean turns it into a startup
         * error. It is written against the disagreement rather than against that cause, which is what let the
         * second shape be caught without having been predicted: an application-supplied {@link AimonStackSpec}
         * or {@link AimonStack} bean — both {@code @ConditionalOnMissingBean} — leaves
         * {@link #withApplicationStores} out of the path, so a published registry never reaches the stack. That
         * one is reached with the re-export's name still free, and a message that told it to rename a bean it
         * does not have would be advice it cannot follow. See {@link PendingTurnRegistryConsistencyCheck} for
         * the one-sentence contract, why the check can only run once everything exists, and why this is the one
         * bean here without {@code @ConditionalOnMissingBean}.
         *
         * @param stack
         *            the assembled stack, read for the registry it actually suspends into
         * @param beanFactory
         *            the context's bean factory, asked what a by-type injection would receive
         * @return the check, which runs once at the end of refresh
         */
        @Bean
        PendingTurnRegistryConsistencyCheck aimonPendingTurnRegistryConsistencyCheck(AimonStack stack,
                ConfigurableListableBeanFactory beanFactory) {
            return new PendingTurnRegistryConsistencyCheck(stack, beanFactory);
        }

        /**
         * Turns the bound budget properties into an {@link ExecutionBudget}.
         *
         * <p>
         * Each field is applied only when set. That is not a user-facing way to remove a ceiling — an empty
         * value in a profile restores the default rather than clearing the field — but it keeps this method
         * agreeing with {@code AimonProperties.validateBounds()} about what a {@code null} means, so a value
         * set programmatically cannot arrive here as a zero ceiling that fails every turn immediately.
         */
        private static ExecutionBudget toBudget(AimonProperties.Budget budget) {
            final ExecutionBudget.Builder builder = ExecutionBudget.builder();
            if (budget.getMaxIterations() != null) {
                builder.maxIterations(budget.getMaxIterations());
            }
            if (budget.getMaxTokens() != null) {
                builder.maxTokens(budget.getMaxTokens());
            }
            if (budget.getMaxWallClock() != null) {
                builder.maxWallClockDuration(budget.getMaxWallClock());
            }
            return builder.build();
        }
    }

    /** The branch taken by {@code aimon.enabled=false}. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = AimonProperties.ENABLED, havingValue = "false")
    static class DisabledConfiguration {

        @Bean
        @ConditionalOnMissingBean
        AimonSessions aimonSessions() {
            log.info("AIMON is disabled ({}=false). No agent runtime, LLM client or workspace was created;"
                    + " calls into AimonSessions will throw.", AimonProperties.ENABLED);
            return new DisabledAimonSessions();
        }

        @Bean
        @ConditionalOnMissingBean
        AimonAgents aimonAgents() {
            return new DisabledAimonAgents();
        }
    }

    /**
     * What the host application contributed, resolved once and handed to the spec factory as one value.
     *
     * <p>
     * Nothing here is required, so every field may be null (or, for the customizers, empty) and the absence is
     * the common case. The three groups are deliberately kept as they arrived rather than being reconciled
     * here: whether a missing channel under {@code mode=channel} is an error, and whether a store and a factory
     * together are a contradiction, are questions about configuration that the spec factory answers with the
     * property names in hand. This type only carries the answer to "was it defined?".
     *
     * <p>
     * Which is also why the {@link Tracer} is here rather than being the host's alone: the observability slice
     * defines one only when asked to and backs off when the application defined its own, so by the time the spec
     * is built the origin no longer matters — only whether there is one.
     */
    static final class ApplicationContributions {

        private final List<AimonAgentCustomizer> agentCustomizers;
        private final CredentialStore credentialStore;
        private final CredentialStoreFactory credentialStoreFactory;
        private final SkillApprovalChannel approvalChannel;
        private final SkillApprovalChannelFactory approvalChannelFactory;
        private final Tracer tracer;
        private final AgentApprovalStore agentApprovalStore;
        private final SessionApprovalStore sessionApprovalStore;
        private final PendingTurnRegistry pendingTurnRegistry;
        private final MessageQueueRepository messageQueueRepository;

        @SuppressWarnings("checkstyle:ParameterNumber")
        ApplicationContributions(List<AimonAgentCustomizer> agentCustomizers, CredentialStore credentialStore,
                CredentialStoreFactory credentialStoreFactory, SkillApprovalChannel approvalChannel,
                SkillApprovalChannelFactory approvalChannelFactory, Tracer tracer,
                AgentApprovalStore agentApprovalStore, SessionApprovalStore sessionApprovalStore,
                PendingTurnRegistry pendingTurnRegistry, MessageQueueRepository messageQueueRepository) {
            this.agentCustomizers = List.copyOf(agentCustomizers);
            this.credentialStore = credentialStore;
            this.credentialStoreFactory = credentialStoreFactory;
            this.approvalChannel = approvalChannel;
            this.approvalChannelFactory = approvalChannelFactory;
            this.tracer = tracer;
            this.agentApprovalStore = agentApprovalStore;
            this.sessionApprovalStore = sessionApprovalStore;
            this.pendingTurnRegistry = pendingTurnRegistry;
            this.messageQueueRepository = messageQueueRepository;
        }

        List<AimonAgentCustomizer> getAgentCustomizers() {
            return agentCustomizers;
        }

        CredentialStore getCredentialStore() {
            return credentialStore;
        }

        CredentialStoreFactory getCredentialStoreFactory() {
            return credentialStoreFactory;
        }

        SkillApprovalChannel getApprovalChannel() {
            return approvalChannel;
        }

        SkillApprovalChannelFactory getApprovalChannelFactory() {
            return approvalChannelFactory;
        }

        Tracer getTracer() {
            return tracer;
        }

        AgentApprovalStore getAgentApprovalStore() {
            return agentApprovalStore;
        }

        SessionApprovalStore getSessionApprovalStore() {
            return sessionApprovalStore;
        }

        PendingTurnRegistry getPendingTurnRegistry() {
            return pendingTurnRegistry;
        }

        MessageQueueRepository getMessageQueueRepository() {
            return messageQueueRepository;
        }
    }

    /**
     * What this starter's own slices resolved, as against what the host application contributed.
     *
     * <p>
     * Both fields are null when the corresponding feature is off, and that null has already been checked: each
     * slice refused the configurations where "off" and a bean that exists contradict each other, with the
     * property names in hand. By the time a value reaches here the only remaining question is the one the spec
     * asks — is there one?
     *
     * <p>
     * Why these two and not the filesystem, session and scheduling specs: those types can express their own
     * off state, so a slice hands one over directly. A {@code KnowledgeStore} cannot, and {@code MemorySpec}
     * refuses to exist without a store, so both need something to carry the absence.
     */
    static final class SliceContributions {

        private final KnowledgeStore knowledgeStore;
        private final MemorySpec memory;

        SliceContributions(KnowledgeStore knowledgeStore, MemorySpec memory) {
            this.knowledgeStore = knowledgeStore;
            this.memory = memory;
        }

        KnowledgeStore getKnowledgeStore() {
            return knowledgeStore;
        }

        MemorySpec getMemory() {
            return memory;
        }
    }
}
