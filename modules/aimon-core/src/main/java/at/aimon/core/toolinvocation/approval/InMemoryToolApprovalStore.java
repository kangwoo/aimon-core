package at.aimon.core.toolinvocation.approval;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ToolApprovalStore}, the default implementation.
 *
 * <p>
 * Answers live in this JVM only. That is the correct default rather than a limitation: an approval is a statement the
 * user made about work they could see in front of them, and re-asking after a restart is the safe direction to fail.
 * A deployment that wants approvals to survive a restart supplies its own implementation — the interface exists so
 * that is a substitution rather than a refactor.
 *
 * <p>
 * Thread-safe. The outer map and each scope's inner map are both concurrent, so a parallel tool batch may read and
 * record answers for different scopes at once.
 */
public final class InMemoryToolApprovalStore implements ToolApprovalStore {

    private final Map<String, Map<String, Boolean>> answersByScope = new ConcurrentHashMap<>();

    @Override
    public Optional<Boolean> lookup(String scopeKey, String toolName) {
        Objects.requireNonNull(scopeKey, "Scope key cannot be null");
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        final Map<String, Boolean> answers = answersByScope.get(scopeKey);
        return answers == null ? Optional.empty() : Optional.ofNullable(answers.get(toolName));
    }

    @Override
    public void remember(String scopeKey, String toolName, boolean allowed) {
        Objects.requireNonNull(scopeKey, "Scope key cannot be null");
        Objects.requireNonNull(toolName, "Tool name cannot be null");
        answersByScope.computeIfAbsent(scopeKey, k -> new ConcurrentHashMap<>()).put(toolName, allowed);
    }

    @Override
    public void revoke(String scopeKey) {
        Objects.requireNonNull(scopeKey, "Scope key cannot be null");
        answersByScope.remove(scopeKey);
    }
}
