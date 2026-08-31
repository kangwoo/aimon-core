package at.aimon.session.routing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Design §3.4 rule 1: no production type may be simple-named exactly {@code Session} or {@code AgentSession}.
 *
 * <p>
 * "Session" on its own names four different lifetimes in this codebase, which is why the word alone was never enough to
 * tell a reader whether a value survives a restart. The restructure forces every symbol to declare its lifetime in its
 * own name — {@code SessionRecord} is durable, {@code LiveSession} is node-local and dies with the process,
 * {@code SessionId} is the join key both of them share, {@code SessionStore} is application-scoped. A bare
 * {@code Session} would re-open the ambiguity in a single commit, and so would {@code AgentSession}, whose only prior
 * job was to be the handle that {@code LiveSession} now is.
 *
 * <p>
 * The payoff is not the class list, it is the prose: with no type called {@code Session}, the phrase "the session" in a
 * comment or design note has no referent to be quietly wrong about, and the writer has to pick the record or the
 * handle.
 *
 * <p>
 * Two deliberate non-goals. This does not forbid {@code Session} as a name *token* — {@code SessionRecord},
 * {@code SessionTotals} and {@code LiveSessionStatus} are all correct, and a substring rule would ban the vocabulary
 * the
 * restructure just adopted. And it does not constrain packages: {@code at.aimon.core.agent.session} is exactly where
 * these types belong.
 *
 * <p>
 * Reach: the importer sees {@code aimon-core} plus {@code aimon-session-routing}, the widest classpath available from
 * any
 * module that can host the rule. Tests are excluded, so a fixture may still be called {@code Session} — the rule is
 * about the vocabulary production code teaches.
 */
@DisplayName("Session lifetime naming architecture")
class SessionNamingArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon");
    }

    @Test
    @DisplayName("no production type is named bare 'Session'")
    void noProductionTypeIsNamedBareSession() {
        final ArchRule rule = noClasses().should().haveSimpleName("Session")
                .as("no production type may be named bare 'Session' — say which lifetime it is: SessionRecord "
                        + "(durable), LiveSession (node-local), SessionId (join key), SessionStore (application).");
        rule.check(classes);
    }

    @Test
    @DisplayName("no production type is named 'AgentSession'")
    void noProductionTypeIsNamedAgentSession() {
        // Checked separately from the bare-Session rule so a regression names the offender precisely. AgentSession
        // was the node-local handle before the restructure; reintroducing the name would silently resurrect the
        // reading that a handle is the session, which is the one thing the 1 : 0..N record-to-handle relation denies.
        final ArchRule rule = noClasses().should().haveSimpleName("AgentSession")
                .as("AgentSession was replaced by LiveSession — the handle is node-local and one session record may "
                        + "be served by zero or many handles over its life.");
        rule.check(classes);
    }
}
