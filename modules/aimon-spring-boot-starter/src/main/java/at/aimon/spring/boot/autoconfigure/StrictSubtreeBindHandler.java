package at.aimon.spring.boot.autoconfigure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.UnboundElementsSourceFilter;

/**
 * Refuses a property nothing bound, but only underneath the subtrees it was given.
 *
 * <p>
 * {@code @ConfigurationProperties(ignoreUnknownFields = false)} does this for a whole prefix, and for
 * {@code aimon.*} that is the wrong range: it would fail an application that keeps a key of its own under the same
 * prefix. This handler gets the same refusal with a narrower reach — a misspelled
 * {@code aimon.llm.model-capabilities.<model>.supports-sampling-parameter} fails startup, and an unknown
 * {@code aimon.something-else} is ignored exactly as before.
 *
 * <p>
 * <b>How the narrowing works.</b> Boot's {@link NoUnboundElementsBindHandler} decides what "unbound" means, including
 * the collection cases that are easy to get wrong, so one instance per subtree is kept and reused rather than
 * reimplemented. It is fed only the events whose name is the subtree's root or beneath it, and it is asked for its
 * verdict when the binder finishes the root — through a {@link BindContext} that reports depth zero, because that
 * handler checks only at the top of a bind and the root of a subtree is not the top of this one. A bind that never
 * visits a root never triggers a check, which is what leaves every other {@code @ConfigurationProperties} bean in
 * the application alone even though an advisor is applied to all of them.
 *
 * <p>
 * <b>An empty value is not a misspelling.</b> Boot converts {@code key=} to {@code null} for anything but a
 * {@code String} and then reports no success for it, so the stock handler counts a correctly spelled key with an
 * empty value as unbound. Across this property tree an empty value means "leave the default" — the usual result of
 * {@code ${SOME_VAR:}} — so a name the binder actually <em>asked for</em> is taken off the list before anything is
 * thrown. What remains is only what no property of the target answers to.
 *
 * <p>
 * Like the stock handler, properties from the system environment and from JVM system properties are not considered:
 * those sources are shared with everything else on the machine, and an environment variable's name cannot be mapped
 * back to one property path with certainty.
 *
 * <p>
 * Stateful and single-use, like the handler it wraps — one instance serves one bind.
 */
final class StrictSubtreeBindHandler extends AbstractBindHandler {

    private final List<Subtree> subtrees;

    private final Set<ConfigurationPropertyName> attemptedNames = new HashSet<>();

    /**
     * Creates a handler that is strict under the given roots and transparent everywhere else.
     *
     * @param parent
     *            the handler this one decorates
     * @param roots
     *            the property names under which an unbound element fails the bind
     */
    StrictSubtreeBindHandler(BindHandler parent, List<String> roots) {
        super(parent);
        Objects.requireNonNull(roots, "roots must not be null");
        final List<Subtree> built = new ArrayList<>();
        for (String root : roots) {
            built.add(new Subtree(ConfigurationPropertyName.of(root)));
        }
        this.subtrees = List.copyOf(built);
    }

    @Override
    public <T> Bindable<T> onStart(ConfigurationPropertyName name, Bindable<T> target, BindContext context) {
        for (Subtree subtree : subtrees) {
            if (subtree.covers(name)) {
                attemptedNames.add(name);
                subtree.unbound.onStart(name, target, context);
            }
        }
        return super.onStart(name, target, context);
    }

    @Override
    public Object onSuccess(ConfigurationPropertyName name, Bindable<?> target, BindContext context, Object result) {
        for (Subtree subtree : subtrees) {
            if (subtree.covers(name)) {
                subtree.unbound.onSuccess(name, target, context, result);
            }
        }
        return super.onSuccess(name, target, context, result);
    }

    @Override
    public Object onFailure(ConfigurationPropertyName name, Bindable<?> target, BindContext context, Exception error)
            throws Exception {
        if (error instanceof UnboundConfigurationPropertiesException) {
            // Past every parent, as the stock handler does: a decorator that tolerates bind errors must not be
            // able to turn this one back into the silence it replaces.
            throw error;
        }
        return super.onFailure(name, target, context, error);
    }

    @Override
    public void onFinish(ConfigurationPropertyName name, Bindable<?> target, BindContext context, Object result)
            throws Exception {
        super.onFinish(name, target, context, result);
        for (Subtree subtree : subtrees) {
            if (subtree.root.equals(name)) {
                requireNothingUnbound(subtree, target, context, result);
            }
        }
    }

    private void requireNothingUnbound(Subtree subtree, Bindable<?> target, BindContext context, Object result)
            throws Exception {
        try {
            subtree.unbound.onFinish(subtree.root, target, new TopOfSubtree(context), result);
        } catch (UnboundConfigurationPropertiesException e) {
            final Set<ConfigurationProperty> unknown = new TreeSet<>();
            for (ConfigurationProperty property : e.getUnboundProperties()) {
                if (!attemptedNames.contains(property.getName())) {
                    unknown.add(property);
                }
            }
            if (!unknown.isEmpty()) {
                throw new UnboundConfigurationPropertiesException(unknown);
            }
        }
    }

    /** One strict root and the stock handler that keeps its books. */
    private static final class Subtree {

        private final ConfigurationPropertyName root;

        private final NoUnboundElementsBindHandler unbound;

        private Subtree(ConfigurationPropertyName root) {
            this.root = root;
            this.unbound = new NoUnboundElementsBindHandler(BindHandler.DEFAULT, new UnboundElementsSourceFilter());
        }

        private boolean covers(ConfigurationPropertyName name) {
            return root.equals(name) || root.isAncestorOf(name);
        }
    }

    /** The surrounding bind's context, seen from a subtree's root: everything delegated, depth zero. */
    private static final class TopOfSubtree implements BindContext {

        private final BindContext delegate;

        private TopOfSubtree(BindContext delegate) {
            this.delegate = delegate;
        }

        @Override
        public Binder getBinder() {
            return delegate.getBinder();
        }

        @Override
        public int getDepth() {
            return 0;
        }

        @Override
        public Iterable<ConfigurationPropertySource> getSources() {
            return delegate.getSources();
        }

        @Override
        public ConfigurationProperty getConfigurationProperty() {
            return delegate.getConfigurationProperty();
        }
    }
}
