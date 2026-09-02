package at.aimon.spring.boot.autoconfigure;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Resolves an optional application contribution and records the destruction edge the provider does not.
 *
 * <p>
 * Every slice of this starter gathers the beans a host application may define through an {@code ObjectProvider},
 * because a required parameter for something most applications do not define would make the common configuration
 * fail to start. That choice has a cost which is easy to miss: a {@code @Bean} parameter is what records "this
 * bean depends on that one", and {@code getIfAvailable()} records nothing — it resolves without an
 * {@code autowiredBeanNames} sink, so nothing tells the factory who asked.
 *
 * <p>
 * What is left is reverse creation order, which is right in every slice here — the contribution is created while
 * the bean that gathers it is, therefore before the stack — and right only by accident. The accident is not one
 * to keep: a store backed by a connection would be closable before the stack that is still writing to it, and
 * nothing in the failure would point back at the method that resolved it. So the edge is registered by hand and
 * the teardown chain becomes the assembly chain read backwards.
 *
 * <p>
 * This lives in one place rather than in each slice because the belief it corrects is the kind that regrows. It
 * was written down as fact in two slices at once — the approval axis' seam and the Quartz branch's
 * "so the dependency edge that orders destruction is registered" — and the next store resolved through a provider
 * would have inherited it. One helper is what makes "did this get an edge?" answerable by looking at the call
 * rather than at a comment.
 */
final class ApplicationBeans {

    private ApplicationBeans() {
    }

    /**
     * Resolves one optional contribution and records that {@code dependent} now depends on it.
     *
     * <p>
     * Resolution stays with the {@code ObjectProvider}: it is what honours {@code @Primary}, refuses to guess
     * between two candidates, and sees a bean a {@code FactoryBean} only produces. Only the edge is added.
     *
     * @param provider
     *            the provider for the contribution's type
     * @param type
     *            the contribution's type, used to find the resolved instance's bean name
     * @param beanFactory
     *            the context's bean factory
     * @param dependent
     *            name of the bean being built, which Spring must destroy before the contribution
     * @param <T>
     *            the contribution's type
     * @return the contributed bean, or {@code null} when the application defined none
     */
    static <T> T resolve(ObjectProvider<T> provider, Class<T> type, ConfigurableListableBeanFactory beanFactory,
            String dependent) {
        final T bean = provider.getIfAvailable();
        if (bean != null) {
            registerDestructionEdge(beanFactory, type, bean, dependent);
        }
        return bean;
    }

    /**
     * Records that {@code dependent} depends on whichever bean produced {@code instance}.
     *
     * <p>
     * The instance is matched against the singleton cache rather than resolved by name a second time, because by
     * this point it exists and a second {@code getBean} would only re-derive what the caller already holds. Two
     * shapes are deliberately left without an edge, and both are correct: a prototype has no container-managed
     * destruction to order, and a {@code FactoryBean} product is cached under a key this loop does not walk, so it
     * keeps the reverse-creation-order behaviour rather than getting a wrong edge.
     *
     * <p>
     * {@code allowEagerInit=false} for that second reason rather than out of caution: a name this loop could match
     * is a name whose definition already declares the type, so eager init would widen the candidate list only with
     * the entries that cannot be matched anyway.
     *
     * @param beanFactory
     *            the context's bean factory
     * @param type
     *            the contribution's type
     * @param instance
     *            the resolved contribution
     * @param dependent
     *            name of the bean that must be destroyed first
     */
    static void registerDestructionEdge(ConfigurableListableBeanFactory beanFactory, Class<?> type, Object instance,
            String dependent) {
        for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
            if (beanFactory.containsSingleton(name) && beanFactory.getSingleton(name) == instance) {
                beanFactory.registerDependentBean(name, dependent);
                return;
            }
        }
    }
}
