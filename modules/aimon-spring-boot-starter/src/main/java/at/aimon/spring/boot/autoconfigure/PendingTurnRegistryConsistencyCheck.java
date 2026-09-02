package at.aimon.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import at.aimon.bootstrap.AimonStack;
import at.aimon.core.skill.policy.pending.PendingTurnRegistry;

/**
 * Fails the context when the registry turns suspend into is not the one the application can reach.
 *
 * <p>
 * {@link PendingTurnRegistry} is the only type this starter both publishes and accepts, and the two halves are
 * resolved by different means — the re-export backs off through {@code @ConditionalOnMissingBean}, which sees
 * beans by type, while the input is read from bean <em>definitions</em> with the re-export dropped by name,
 * because an {@code ObjectProvider} there would close a cycle. Two views that agree in every ordinary
 * configuration can disagree in two, and both disagreements are silent:
 *
 * <ul>
 * <li>an application bean named {@code aimonPendingTurnRegistry} is read as the re-export and ignored, so the
 * condition withdraws the real re-export while the stack keeps its node-local default;
 * <li>a registry produced by a {@code FactoryBean} whose type is only knowable by instantiating it is invisible
 * to the definition scan.
 * </ul>
 *
 * <p>
 * Either way the application injects one registry and the stack suspends into another, so {@code /approve} finds
 * nothing to release — which is the exact failure the seam exists to remove, arriving without a symptom that
 * points anywhere. The check is one identity comparison and turns it into a startup error naming both causes.
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
        final List<String> unreachable = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(PendingTurnRegistry.class)) {
            if (beanFactory.getBean(name, PendingTurnRegistry.class) == assembled) {
                return;
            }
            unreachable.add(name);
        }
        if (unreachable.isEmpty()) {
            // Neither published nor re-exported. Nothing can be injected, so nothing can diverge.
            return;
        }
        throw new IllegalStateException("The " + PendingTurnRegistry.class.getName()
                + " the assembled stack suspends turns into is not any of the beans an application can inject: "
                + String.join(", ", unreachable) + ". An /approve entered against one of those would find nothing"
                + " to release. Two configurations cause this: a bean named '"
                + AimonAutoConfiguration.EnabledConfiguration.PENDING_TURN_REGISTRY_BEAN
                + "', which is the name this starter re-exports the stack's own registry under and is therefore"
                + " skipped when reading the application's — rename it; or a registry produced by a FactoryBean"
                + " whose object type is only knowable by instantiating it, which the definition scan cannot see"
                + " — declare the bean with the registry as its return type instead.");
    }
}
