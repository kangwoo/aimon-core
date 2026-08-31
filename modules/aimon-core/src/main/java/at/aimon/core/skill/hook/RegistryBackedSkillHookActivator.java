package at.aimon.core.skill.hook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.core.hook.HookEventType;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.execution.ExecutionHook;
import at.aimon.core.skill.Skill;

/**
 * Production {@link SkillHookActivator} that registers a skill's declared hooks against a real {@link HookRegistry} and
 * unregisters them when the returned scope is closed.
 *
 * <p>
 * Registration walks {@link SkillHookSet#supportedEvents()} in order, so every skill-scopable event is covered without
 * this class naming them one by one; deregistration runs in reverse registration order so that any cross-hook
 * dependencies introduced at register time are unwound LIFO. Each scope is single-use and idempotent on close.
 */
public final class RegistryBackedSkillHookActivator implements SkillHookActivator {

    private static final Logger log = LoggerFactory.getLogger(RegistryBackedSkillHookActivator.class);

    private final HookRegistry hookRegistry;

    /**
     * Creates a new RegistryBackedSkillHookActivator.
     *
     * @param hookRegistry
     *            The hook registry (must not be null)
     */
    public RegistryBackedSkillHookActivator(HookRegistry hookRegistry) {
        this.hookRegistry = Objects.requireNonNull(hookRegistry, "Hook registry cannot be null");
    }

    @Override
    public SkillHookScope activate(Skill skill) {
        Objects.requireNonNull(skill, "Skill cannot be null");

        final SkillHookSet hooks = skill.getMetadata().getHooks();
        if (hooks.isEmpty()) {
            return SkillHookScope.EMPTY;
        }

        final List<Runnable> teardowns = new ArrayList<>();
        try {
            for (HookEventType<?> type : SkillHookSet.supportedEvents()) {
                registerAll(hooks, type, teardowns);
            }
        } catch (RuntimeException e) {
            // Partial registration must not leak; tear down what we managed to register before propagating.
            log.warn("Skill '{}' hook registration failed mid-way; rolling back: {}", skill.getName(), e.getMessage());
            unregisterAll(teardowns);
            throw e;
        }

        return new TeardownScope(teardowns);
    }

    /**
     * Registers every hook the skill declared for one event, appending a matching teardown per successful
     * registration.
     *
     * <p>
     * The type parameter is what makes the wildcard loop in {@link #activate(Skill)} type-safe: capture conversion
     * binds {@code H} to the hook type the event keys, so {@link SkillHookSet#get} and
     * {@link HookRegistry#register} line up without a cast.
     */
    private <H extends ExecutionHook<?>> void registerAll(SkillHookSet hooks, HookEventType<H> type,
            List<Runnable> teardowns) {
        for (H hook : hooks.get(type)) {
            hookRegistry.register(type, hook);
            teardowns.add(() -> hookRegistry.unregister(type, hook));
        }
    }

    private static void unregisterAll(List<Runnable> teardowns) {
        // LIFO so that hooks introduced later (which may depend on earlier ones) come off first.
        Collections.reverse(teardowns);
        for (Runnable r : teardowns) {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("Skill hook unregister threw: {}", e.getMessage(), e);
            }
        }
    }

    private static final class TeardownScope implements SkillHookScope {

        private final List<Runnable> teardowns;
        private final AtomicBoolean closed = new AtomicBoolean();

        TeardownScope(List<Runnable> teardowns) {
            this.teardowns = teardowns;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                unregisterAll(teardowns);
            }
        }
    }
}
