package at.aimon.spring.boot.autoconfigure;

import java.util.List;

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
 * was written down as fact in three slices — the approval axis' seam, the Quartz branch's
 * "so the dependency edge that orders destruction is registered", and the session slice's class javadoc, which
 * survived the first two corrections — and the next store resolved through a provider would have inherited it.
 * One helper is what makes "did this get an edge?" answerable by looking at the call rather than at a comment.
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
     * Resolves every contribution of a type in order, giving each one the same edge.
     *
     * <p>
     * The plural counterpart of {@link #resolve}, and it exists so that "does this one need an edge?" has no
     * remaining per-entry answer. A customizer holds no resource today, which is a fact about today's
     * implementations rather than about the extension point.
     *
     * @param provider
     *            the provider for the contribution's type
     * @param type
     *            the contribution's type
     * @param beanFactory
     *            the context's bean factory
     * @param dependent
     *            name of the bean being built, which Spring must destroy before the contributions
     * @param <T>
     *            the contribution's type
     * @return the contributed beans in the order the provider gave them, possibly empty
     */
    static <T> List<T> resolveAll(ObjectProvider<T> provider, Class<T> type,
            ConfigurableListableBeanFactory beanFactory, String dependent) {
        final List<T> beans = provider.orderedStream().toList();
        for (T bean : beans) {
            registerDestructionEdge(beanFactory, type, bean, dependent);
        }
        return beans;
    }

    /**
     * Records that {@code dependent} depends on whichever bean produced {@code instance}.
     *
     * <p>
     * The instance is matched against what each candidate name currently holds rather than resolved by name a
     * second time, because by this point it exists and a fresh {@code getBean} would only re-derive what the
     * caller already holds. Matching has to look in two places, which is the part that was wrong at first: a
     * {@code FactoryBean}'s singleton is the <em>factory</em>, and its product lives in a separate cache. Reading
     * only the first left the borrowed Quartz {@code Scheduler} — which
     * {@code spring-boot-starter-quartz} publishes through a {@code SchedulerFactoryBean} — without the edge the
     * comment at its call site said it was getting.
     *
     * <p>
     * Two shapes are still left without one, and both are correct: a prototype and a non-singleton
     * {@code FactoryBean} product both hand out an instance the container does not destroy, so there is no
     * destruction to order. Neither is asked for its product here, which is also why the loop cannot create
     * anything a caller did not already ask for.
     *
     * <p>
     * {@code allowEagerInit=false} rather than out of caution: a name this loop could match is a name whose
     * definition already declares the type — the caller is holding the instance, so the bean that produced it has
     * been created — and eager init would widen the candidate list only with entries that cannot match.
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
            if (heldBy(beanFactory, name) == instance) {
                beanFactory.registerDependentBean(name, dependent);
                return;
            }
        }
    }

    /**
     * Returns what {@code name} currently hands out, without creating anything.
     *
     * <p>
     * {@code getBean} is reached only for a singleton {@code FactoryBean}, where it reads the product cache the
     * factory filled when the caller resolved it. For everything else the singleton cache is the answer, and a
     * name with nothing in it is not a candidate at all.
     *
     * @param beanFactory
     *            the context's bean factory
     * @param name
     *            the candidate bean name
     * @return the instance that name holds, or {@code null} when it holds none this method may read
     */
    private static Object heldBy(ConfigurableListableBeanFactory beanFactory, String name) {
        if (!beanFactory.containsSingleton(name)) {
            return null;
        }
        if (!beanFactory.isFactoryBean(name)) {
            return beanFactory.getSingleton(name);
        }
        return beanFactory.isSingleton(name) ? beanFactory.getBean(name) : null;
    }
}
