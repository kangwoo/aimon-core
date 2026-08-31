package at.aimon.core.credential;

import java.util.Optional;
import java.util.Set;

import at.aimon.core.base.ApplicationScoped;

/**
 * Credential storage interface for securely resolving sensitive values by reference.
 *
 * <p>
 * This interface enables Tools to resolve credentials internally so that
 * LLM agents never see actual secret values. Agents reference credentials
 * by profile and field name (e.g., {@code "jira.password"}), and the Tool
 * resolves the actual value from the store at execution time.
 *
 * <p>
 * {@code CredentialStore} is {@link ApplicationScoped}: a single store is shared across all
 * {@code AgentRuntime} instances and lives for the application's lifetime. Implementations must be
 * thread-safe. The default implementation is {@link InMemoryCredentialStore}.
 *
 * <p>
 * Usage example:
 *
 * <pre>
 * {@code
 * CredentialStore store = InMemoryCredentialStore.builder()
 *     .profile("jira", Map.of("username", "admin", "password", "secret"))
 *     .build();
 *
 * Optional<String> password = store.get("jira", "password");
 * }
 * </pre>
 */
public interface CredentialStore extends ApplicationScoped {

    /**
     * Retrieves a credential value by profile and field name.
     *
     * @param profile
     *            the credential profile name (e.g., "jira", "github")
     * @param field
     *            the field name within the profile (e.g., "username", "password")
     * @return the credential value, or empty if profile or field not found
     */
    Optional<String> get(String profile, String field);

    /**
     * Returns the set of all registered profile names.
     *
     * @return unmodifiable set of profile names (never null, may be empty)
     */
    Set<String> getProfiles();

    /**
     * Returns the set of field names for a given profile.
     *
     * @param profile
     *            the credential profile name (e.g., "jira", "github")
     * @return unmodifiable set of field names (never null, empty if profile not found)
     */
    Set<String> getFields(String profile);
}
