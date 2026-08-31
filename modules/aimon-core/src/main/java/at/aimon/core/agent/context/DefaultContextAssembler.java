package at.aimon.core.agent.context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default {@link ContextAssembler}: runs an ordered list of {@link ContextProvider providers} and concatenates their
 * blocks.
 *
 * <p>
 * The assembler is defensive by contract — a provider that throws is logged at {@code WARN} and skipped, so one bad
 * source never breaks prompt assembly. Providers are consulted in registration order and their blocks are appended in
 * that order, so callers control layout by ordering the list.
 *
 * <p>
 * The assembler holds only the immutable provider list; it keeps no per-session state, so a single instance is
 * safe to share across concurrent sessions and across scale-out instances (each provider derives its blocks from
 * the per-request inputs).
 */
public final class DefaultContextAssembler implements ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextAssembler.class);

    private final List<ContextProvider> providers;

    /**
     * Creates an assembler over the given providers.
     *
     * @param providers
     *            the providers to consult, in injection order (must not be null and must not contain null)
     * @throws NullPointerException
     *             if the list or any element is null
     */
    public DefaultContextAssembler(List<ContextProvider> providers) {
        Objects.requireNonNull(providers, "providers must not be null");
        final List<ContextProvider> copy = new ArrayList<>(providers.size());
        for (ContextProvider provider : providers) {
            copy.add(Objects.requireNonNull(provider, "providers must not contain null"));
        }
        this.providers = List.copyOf(copy);
    }

    /**
     * Convenience factory over a varargs list of providers.
     *
     * @param providers
     *            the providers to consult, in injection order (must not contain null)
     * @return a new assembler
     */
    public static DefaultContextAssembler of(ContextProvider... providers) {
        return new DefaultContextAssembler(
                Arrays.asList(Objects.requireNonNull(providers, "providers must not be null")));
    }

    @Override
    public List<ContextBlock> assemble(ContextAssemblyRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        final List<ContextBlock> blocks = new ArrayList<>();
        for (ContextProvider provider : providers) {
            try {
                final List<ContextBlock> contributed = provider.provide(request);
                if (contributed == null) {
                    log.warn("ContextProvider {} returned null; skipping", provider.getClass().getName());
                    continue;
                }
                for (ContextBlock block : contributed) {
                    if (block != null) {
                        blocks.add(block);
                    }
                }
            } catch (RuntimeException e) {
                // Defensive: a failing provider must never break prompt assembly.
                log.warn("ContextProvider {} failed; skipping its blocks: {}", provider.getClass().getName(),
                        e.getMessage());
            }
        }
        return List.copyOf(blocks);
    }
}
