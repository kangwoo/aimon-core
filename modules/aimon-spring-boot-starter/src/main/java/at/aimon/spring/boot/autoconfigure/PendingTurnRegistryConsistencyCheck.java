package at.aimon.spring.boot.autoconfigure;

import java.util.Objects;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import at.aimon.bootstrap.AimonStack;
import at.aimon.bootstrap.AimonStackSpec;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;

/**
 * Fails the context when the registry turns suspend into is not the one an application injecting that type gets.
 *
 * <p>
 * {@link PendingTurnRegistry} is the only type this starter both publishes and accepts, and the two halves are
 * resolved by different means — the re-export backs off through {@code @ConditionalOnMissingBean}, which sees
 * beans by type, while the input is read from bean <em>definitions</em> with the re-export dropped by name,
 * because an {@code ObjectProvider} there would close a cycle. Two views that agree in every ordinary
 * configuration can disagree, and the disagreement is silent: the application injects one registry, the stack
 * suspends into another, and {@code /approve} finds nothing to release — the exact failure this seam exists to
 * remove, arriving with nothing to read.
 *
 * <p>
 * Being written against the <b>disagreement</b> rather than against a cause is what lets it catch a second way
 * of arriving there without that way having been predicted first — and there are two, which is why the message
 * does not name one of them as <em>the</em> cause:
 *
 * <ul>
 * <li><b>a bean the application itself names {@code aimonPendingTurnRegistry}</b> — read as the re-export and
 * skipped by the input seam, while the condition withdraws the real re-export by type. The fix is to rename it;
 * <li><b>an {@code AimonStackSpec} or {@code AimonStack} the application supplies itself</b> — both are
 * {@code @ConditionalOnMissingBean}, and it is this starter's spec factory that applies a published registry, so
 * a hand-built spec has to apply it itself. The fix is
 * {@code SkillApprovalSpec.withPendingTurnRegistry(...)}, not a rename.
 * </ul>
 *
 * <p>
 * The two are told apart by asking whether that name is taken, and only then is a cause named. Telling the
 * second shape to rename a bean it does not have would be worse than saying nothing: it is advice that cannot
 * be followed, on a startup failure, in the one place a reader has nothing else to go on.
 *
 * <p>
 * <b>The contract is one sentence: what {@code getBean(PendingTurnRegistry.class)} returns is what the stack
 * suspends into.</b> Asking it that way rather than reimplementing the resolution rules is what keeps it honest
 * — {@code @Primary} and the refusal to guess between two candidates are Spring's answers here, not a second
 * copy of them. Two outcomes are deliberately not failures:
 *
 * <ul>
 * <li><b>no bean of the type at all</b> — nothing is injectable, so nothing can diverge;
 * <li><b>several with no way to choose</b> — the application's own injection point already throws, and louder
 * than this would.
 * </ul>
 *
 * <p>
 * One shape is genuinely outside this, and measurement is what moved it here from the list of things to catch: a
 * registry produced by a {@code FactoryBean} whose object type is knowable only by instantiating it. It is
 * invisible to the definition scan that reads the application's registry — and, for the same reason, to every
 * by-type view in the container, including the application's own {@code @Autowired PendingTurnRegistry}, which
 * resolves to the re-export and therefore to the assembled registry. Nothing diverges because nothing reaches it
 * except a lookup by name, which is a request for that specific bean rather than for the stack's. It is a bean
 * AIMON never sees, not a registry the stack disagrees about.
 *
 * <p>
 * It runs as a {@link SmartInitializingSingleton} rather than inside a factory method because it is the only
 * point where both views exist: the stack has been assembled and every registry bean has been created, so
 * resolving them cannot close the cycle the input seam is shaped around.
 *
 * <p>
 * <b>Its {@code @Bean} carries no {@code @ConditionalOnMissingBean}, and that is the one deliberate exception
 * to this starter's "every bean is replaceable" rule</b> ({@code docs/design/integration/spring-boot-starter.md}
 * §1.4). The rule is about <em>collaborators</em> — a host that knows better supplies its own {@code LlmClient},
 * {@code SessionSpec} or {@code AimonStack}, and the starter withdraws. This is not a collaborator; it asserts
 * an invariant about the beans a host supplied, and an invariant a host can switch off stops being one. What
 * makes that acceptable is that the assertion is never a matter of taste: every configuration it refuses is one
 * where {@code /approve} silently releases nothing, and both shapes above have a fix that takes one line.
 */
final class PendingTurnRegistryConsistencyCheck implements SmartInitializingSingleton {

    private final AimonStack stack;
    private final ConfigurableListableBeanFactory beanFactory;

    PendingTurnRegistryConsistencyCheck(AimonStack stack, ConfigurableListableBeanFactory beanFactory) {
        this.stack = Objects.requireNonNull(stack, "stack must not be null");
        this.beanFactory = Objects.requireNonNull(beanFactory, "beanFactory must not be null");
    }

    @Override
    public void afterSingletonsInstantiated() {
        final PendingTurnRegistry assembled = stack.pendingTurnRegistry();
        final PendingTurnRegistry injected;
        try {
            injected = beanFactory.getBean(PendingTurnRegistry.class);
        } catch (NoSuchBeanDefinitionException absentOrAmbiguous) {
            // Both non-failures above arrive here — NoUniqueBeanDefinitionException is a subtype.
            return;
        }
        if (injected == assembled) {
            return;
        }
        throw new IllegalStateException("The " + PendingTurnRegistry.class.getName()
                + " the assembled stack suspends turns into is not the one an application injecting that type"
                + " receives. An /approve entered against the injected registry would find nothing to release. "
                + cause(assembled));
    }

    /**
     * Names whichever of the two shapes this context is in, and the one-line fix for it.
     *
     * <p>
     * The question that separates them is whether the re-export's name is taken by something other than the
     * re-export. Nothing else has to be inspected: if that name is free, the input seam skipped nothing, so the
     * registry never reached the spec the stack was built from — which only happens when the spec, or the stack,
     * did not come from this starter.
     *
     * @param assembled
     *            the registry the stack suspends into, compared by identity against the named bean
     * @return the cause-specific half of the failure message
     */
    private String cause(PendingTurnRegistry assembled) {
        final String name = AimonAutoConfiguration.EnabledConfiguration.PENDING_TURN_REGISTRY_BEAN;
        final Object named = beanFactory.containsBean(name) ? beanFactory.getBean(name) : null;
        if (named instanceof PendingTurnRegistry && named != assembled) {
            return "The cause is the bean the application named '" + name + "': that is the name this starter"
                    + " re-exports the stack's own registry under, so a bean wearing it is skipped when the"
                    + " application's registry is read, while @ConditionalOnMissingBean has already withdrawn the"
                    + " real re-export by type. Rename it and the seam picks it up.";
        }
        return "No bean is named '" + name + "', so nothing was skipped when the application's registry was read:"
                + " the stack was assembled from an " + AimonStackSpec.class.getName() + " that does not carry"
                + " this registry. That is what an application-supplied " + AimonStackSpec.class.getSimpleName()
                + " or " + AimonStack.class.getSimpleName() + " bean does — both are @ConditionalOnMissingBean,"
                + " and it is this starter's own spec factory that applies a published registry. Apply it on the"
                + " supplied spec with SkillApprovalSpec.withPendingTurnRegistry(...), or let the starter build"
                + " the spec.";
    }
}
