package at.aimon.core.base;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Holds {@link Principal}'s equality contract, which had no test until a remote backend tripped over it.
 *
 * <p>
 * That absence is the reason this file exists rather than an afterthought to it. {@code equals} compared every
 * field, including the display name, and nothing anywhere said whether that was a decision or the shape an IDE
 * produces — no comment, no test, and no case in the suite that told two principals apart by their names. It
 * behaved as identity everywhere it was read and as identity-plus-label everywhere it was compared, and the
 * disagreement surfaced only when something had to rebuild a principal from the wire.
 */
@DisplayName("Principal")
class PrincipalTest {

    @Nested
    @DisplayName("equality is identity")
    class Equality {

        @Test
        @DisplayName("a renamed principal is the same principal")
        void displayNameIsNotIdentity() {
            assertThat(Principal.user("alice", "Alice")).isEqualTo(Principal.user("alice", "Alice Smith"));
        }

        @Test
        @DisplayName("and hashes the same, so a rename cannot lose a map entry")
        void displayNameIsNotInTheHash() {
            assertThat(Principal.user("alice", "Alice")).hasSameHashCodeAs(Principal.user("alice", "A."));
        }

        @Test
        @DisplayName("the single-argument factory agrees with the two-argument one")
        void defaultedDisplayNameStillEquals() {
            assertThat(Principal.user("alice")).isEqualTo(Principal.user("alice", "Alice"));
        }

        @Test
        @DisplayName("a different id is a different principal")
        void idIsIdentity() {
            assertThat(Principal.user("alice", "Alice")).isNotEqualTo(Principal.user("bob", "Alice"));
        }

        @Test
        @DisplayName("a different type is a different principal, even with the same id")
        void typeIsIdentity() {
            assertThat(Principal.user("ops", "Ops")).isNotEqualTo(Principal.service("ops", "Ops"));
        }
    }

    @Nested
    @DisplayName("the consequences the old equality had")
    class Consequences {

        /**
         * The shape of {@code ScheduledTaskManager}'s ownership check. Under the old equality a display-name
         * change denied a user their own tasks, which is why this is asserted here rather than left to that
         * class's own tests — the rule belongs to the type, not to the one caller that noticed.
         */
        @Test
        @DisplayName("an ownership check survives the owner being renamed")
        void ownershipCheckSurvivesARename() {
            final Principal owner = Principal.user("alice", "Alice");
            final Principal callerAfterRename = Principal.user("alice", "Alice Smith");

            assertThat(owner.equals(callerAfterRename)).isTrue();
        }

        /**
         * A usage key embeds a principal; metering must not split across a rename.
         *
         * <p>
         * Built with {@code HashSet} rather than {@code Set.of}, which rejects duplicates instead of collapsing
         * them — under this equality the two arguments are duplicates, so the factory throws and the assertion
         * never runs. The throw is itself evidence, but evidence of the wrong shape to leave in a test.
         */
        @Test
        @DisplayName("a set does not hold the same person twice under two names")
        void aSetDeduplicatesAcrossARename() {
            final Set<Principal> people = new HashSet<>(
                    List.of(Principal.user("alice", "Alice"), Principal.user("alice", "Alice Smith")));

            assertThat(people).hasSize(1);
        }

        /**
         * What a backend that stores only an id can rebuild. It cannot restore a display name it never had a
         * column for, and before this it could not produce a principal equal to the one it was given.
         */
        @Test
        @DisplayName("a principal rebuilt from an id alone equals the one it came from")
        void aPrincipalRebuiltFromTheWireEquals() {
            final Principal original = Principal.user("alice", "Alice");
            final Principal fromWire = Principal.user(original.getId());

            assertThat(fromWire).isEqualTo(original);
        }
    }

    @Nested
    @DisplayName("what equality still leaves alone")
    class Untouched {

        @Test
        @DisplayName("the display name is kept, it is only not compared")
        void displayNameIsStillCarried() {
            assertThat(Principal.user("alice", "Alice Smith").getDisplayName()).isEqualTo("Alice Smith");
        }

        /**
         * Two principals that are equal may still carry different names, so a map keyed on them returns
         * whichever value was put first. That is the point — the key is the person — but it means a caller
         * wanting to display a name must read it from the value it has, not from a key it looked up with.
         */
        @Test
        @DisplayName("a map keyed by principal keeps the name of the key it was built with")
        void mapKeepsTheOriginalKeysName() {
            final Map<Principal, String> byPrincipal = Map.of(Principal.user("alice", "Alice"), "tea");

            assertThat(byPrincipal).containsEntry(Principal.user("alice", "Alice Smith"), "tea");
            assertThat(byPrincipal.keySet().iterator().next().getDisplayName()).isEqualTo("Alice");
        }
    }
}
