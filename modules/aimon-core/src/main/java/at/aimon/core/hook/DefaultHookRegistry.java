package at.aimon.core.hook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;

import at.aimon.core.hook.execution.ExecutionHook;

/**
 * In-memory {@link HookRegistry} backed by a per-event-type {@link CopyOnWriteArrayList}.
 *
 * <p>
 * Storage is a {@link LinkedHashMap} keyed by {@link HookEventType}; each value is a CoW list so concurrent iteration
 * during hook execution is safe even while other threads register / unregister. The map is fully populated at
 * construction with empty lists for every type returned by {@link HookEventType#values()}, so {@link #getHooks} never
 * returns {@code null}.
 */
public class DefaultHookRegistry implements HookRegistry {

    private final Map<HookEventType<?>, CopyOnWriteArrayList<ExecutionHook<?>>> hooksByType;

    /**
     * Creates an empty registry with one CoW list per known event type.
     */
    public DefaultHookRegistry() {
        final Map<HookEventType<?>, CopyOnWriteArrayList<ExecutionHook<?>>> map = new LinkedHashMap<>();
        for (HookEventType<?> type : HookEventType.values()) {
            map.put(type, new CopyOnWriteArrayList<>());
        }
        this.hooksByType = map;
    }

    @Override
    public <H extends ExecutionHook<?>> void register(HookEventType<H> type, H hook) {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(hook, "Hook cannot be null");
        listFor(type).add(hook);
    }

    @Override
    public <H extends ExecutionHook<?>> boolean unregister(HookEventType<H> type, H hook) {
        Objects.requireNonNull(type, "type cannot be null");
        Objects.requireNonNull(hook, "Hook cannot be null");
        return listFor(type).remove(hook);
    }

    @Override
    public <H extends ExecutionHook<?>> List<H> getHooks(HookEventType<H> type) {
        Objects.requireNonNull(type, "type cannot be null");
        final CopyOnWriteArrayList<ExecutionHook<?>> raw = hooksByType.get(type);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        // Safe by construction: register(type, hook) only inserts H instances into this list.
        @SuppressWarnings("unchecked")
        final List<H> typed = (List<H>) List.copyOf(raw);
        return typed;
    }

    @Override
    public boolean isEmpty() {
        for (CopyOnWriteArrayList<ExecutionHook<?>> list : hooksByType.values()) {
            if (!list.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void clearAll() {
        for (CopyOnWriteArrayList<ExecutionHook<?>> list : hooksByType.values()) {
            list.clear();
        }
    }

    private CopyOnWriteArrayList<ExecutionHook<?>> listFor(HookEventType<?> type) {
        final CopyOnWriteArrayList<ExecutionHook<?>> list = hooksByType.get(type);
        if (list == null) {
            throw new IllegalStateException("Unknown HookEventType: " + type
                    + " (registry must be constructed with all HookEventType.values())");
        }
        return list;
    }

    @Override
    public String toString() {
        final StringJoiner sj = new StringJoiner(", ", "HookRegistry{", "}");
        for (Map.Entry<HookEventType<?>, CopyOnWriteArrayList<ExecutionHook<?>>> e : hooksByType.entrySet()) {
            sj.add(e.getKey().name() + "=" + e.getValue().size());
        }
        return sj.toString();
    }
}
