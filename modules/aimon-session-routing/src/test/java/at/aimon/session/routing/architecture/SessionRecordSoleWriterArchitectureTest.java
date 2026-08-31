package at.aimon.session.routing.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import at.aimon.core.agent.session.store.SessionRecord;
import at.aimon.core.agent.session.store.SessionRecordView;
import at.aimon.core.agent.session.store.SessionStore;

/**
 * WS-02-B10 / design §3.4 rule 3 and §3.6 sole-writer invariant: only {@code at.aimon.core.agent.session.store} — the
 * package that holds {@code SessionStore} and its implementations — may depend on the mutable {@link SessionRecord}
 * class. Every other production caller must use {@link SessionRecordView}, so the write path stays behind
 * {@link SessionStore} and its fenced primitives.
 *
 * <p>
 * The allowlist used to have three more entries, and losing all three is the point.
 * {@code at.aimon.core.agent.compact..} was there for the repository-backed compaction failure store, which no longer
 * exists; {@code at.aimon.session.routing.internal.BindingResolver} was there because the session router read and wrote
 * the agent binding itself, one module away from the record; and the transcript package was there for
 * {@code SessionSnapshot.toSessionRecord()}, which has been inverted into {@code SessionRecord.fromSnapshot} so that
 * the store — which already takes a snapshot in {@code mergeFromSnapshot} — owns the direction instead. Now that
 * election, provisioning, binding and snapshot materialisation all settle inside
 * {@code at.aimon.core.agent.session.store}, no class outside it needs the mutable type at all — so the rule states
 * exactly that, with no exceptions to explain.
 *
 * <p>
 * This rule is also why {@link SessionRecordView} itself is not deletable. Its companion was — under the
 * pre-restructure names, {@code MutableConversationView}, which existed solely to expose {@code setAgentRef} across the
 * boundary, and the binding is no longer written from outside. The read-only view, by contrast, is the return type that
 * keeps this rule satisfiable: delete it and every reader of a record depends on the mutable class again.
 *
 * <p>
 * Reach: the importer sees {@code aimon-core} plus {@code aimon-session-routing}, which is the widest classpath
 * available
 * from any module that can host this rule. The concrete transports ({@code aimon-session-mongodb},
 * {@code -postgres}, {@code -redis}) are downstream of both and therefore out of scope here; they hold no reference to
 * the mutable record today, and each one's own test source set is where a regression there would have to be caught.
 */
@DisplayName("SessionRecord sole-writer architecture")
class SessionRecordSoleWriterArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon");
    }

    @Test
    @DisplayName("WS-02-B10: no production caller outside agent.session.store may depend on SessionRecord")
    void onlyTheStorePackageMayDependOnTheMutableSessionRecord() {
        final ArchRule rule = noClasses().that().resideOutsideOfPackages("at.aimon.core.agent.session.store..").should()
                .dependOnClassesThat().haveFullyQualifiedName("at.aimon.core.agent.session.store.SessionRecord")
                .as("Only at.aimon.core.agent.session.store.. may depend on the mutable SessionRecord class"
                        + " — others must use SessionRecordView (design §3.4 rule 3, §3.6 sole-writer invariant).");
        rule.check(classes);
    }
}
