package at.aimon.spring.boot.autoconfigure;

import java.util.Objects;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import at.aimon.bootstrap.AimonStack;
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
 * The known cause is the one the name-based exclusion accepts: a bean the application itself names
 * {@code aimonPendingTurnRegistry} is read as the re-export and skipped, while the condition withdraws the real
 * re-export by type. The check is not written against that cause, though — it is written against the
 * <b>disagreement</b>, so a second way to arrive at it does not need to be predicted first.
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
                + " receives. An /approve entered against the injected registry would find nothing to release."
                + " The configuration that causes this is a bean the application named '"
                + AimonAutoConfiguration.EnabledConfiguration.PENDING_TURN_REGISTRY_BEAN
                + "': that is the name this starter re-exports the stack's own registry under, so a bean wearing"
                + " it is skipped when the application's registry is read, while @ConditionalOnMissingBean has"
                + " already withdrawn the real re-export by type. Rename it and the seam picks it up.");
    }
}
