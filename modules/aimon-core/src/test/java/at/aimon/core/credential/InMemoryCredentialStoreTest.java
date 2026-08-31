package at.aimon.core.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class InMemoryCredentialStoreTest {

    @Test
    void shouldRetrieveCredentialByProfileAndField() {
        CredentialStore store = InMemoryCredentialStore.builder()
                .profile("jira", Map.of("username", "admin", "password", "secret123")).build();

        assertThat(store.get("jira", "username")).hasValue("admin");
        assertThat(store.get("jira", "password")).hasValue("secret123");
    }

    @Test
    void shouldReturnEmptyForUnknownProfile() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("username", "admin")).build();

        assertThat(store.get("github", "token")).isEmpty();
    }

    @Test
    void shouldReturnEmptyForUnknownField() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("username", "admin")).build();

        assertThat(store.get("jira", "password")).isEmpty();
    }

    @Test
    void shouldReturnEmptyStoreProfiles() {
        CredentialStore store = InMemoryCredentialStore.builder().build();

        assertThat(store.getProfiles()).isEmpty();
        assertThat(store.get("any", "field")).isEmpty();
    }

    @Test
    void shouldReturnAllProfileNames() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("username", "admin"))
                .profile("github", Map.of("token", "ghp_abc")).build();

        assertThat(store.getProfiles()).containsExactlyInAnyOrder("jira", "github");
    }

    @Test
    void shouldReturnUnmodifiableProfiles() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("username", "admin")).build();

        assertThatThrownBy(() -> store.getProfiles().add("hack")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldThrowOnNullProfile() {
        CredentialStore store = InMemoryCredentialStore.builder().build();

        assertThatThrownBy(() -> store.get(null, "field")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldThrowOnNullField() {
        CredentialStore store = InMemoryCredentialStore.builder().build();

        assertThatThrownBy(() -> store.get("profile", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldSupportMultipleProfiles() {
        CredentialStore store = InMemoryCredentialStore.builder()
                .profile("jira", Map.of("username", "jira-user", "password", "jira-pass"))
                .profile("github", Map.of("token", "ghp_123")).build();

        assertThat(store.get("jira", "username")).hasValue("jira-user");
        assertThat(store.get("github", "token")).hasValue("ghp_123");
    }

    @Test
    void shouldReturnFieldNamesForProfile() {
        CredentialStore store = InMemoryCredentialStore.builder()
                .profile("jira", Map.of("username", "admin", "password", "secret")).build();

        assertThat(store.getFields("jira")).containsExactlyInAnyOrder("username", "password");
    }

    @Test
    void shouldReturnEmptyFieldsForUnknownProfile() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("username", "admin")).build();

        assertThat(store.getFields("github")).isEmpty();
    }

    @Test
    void shouldReturnUnmodifiableFields() {
        CredentialStore store = InMemoryCredentialStore.builder().profile("jira", Map.of("username", "admin")).build();

        assertThatThrownBy(() -> store.getFields("jira").add("hack")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldThrowOnNullProfileForGetFields() {
        CredentialStore store = InMemoryCredentialStore.builder().build();

        assertThatThrownBy(() -> store.getFields(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldOverwriteProfileWhenAddedTwice() {
        CredentialStore store = InMemoryCredentialStore.builder()
                .profile("jira", Map.of("username", "old-user", "password", "old-pass"))
                .profile("jira", Map.of("username", "new-user")).build();

        // 두 번째 호출이 첫 번째를 완전히 대체한다
        assertThat(store.get("jira", "username")).hasValue("new-user");
        assertThat(store.get("jira", "password")).isEmpty();
        assertThat(store.getProfiles()).containsExactly("jira");
    }
}
