package at.aimon.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import at.aimon.core.agent.session.store.SessionRecord;
import at.aimon.core.agent.session.transcript.TranscriptBuffer;

/**
 * Baseline architecture rules for {@code aimon-core}. These pin invariants that hold today so any future regression
 * fails the build instead of silently shipping.
 */
@DisplayName("aimon-core architecture rules")
class ArchitectureRulesTest {

    private static final JavaClasses CORE_MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("at.aimon.core");

    @Test
    @DisplayName("aimon-core does not depend on any sibling aimon module")
    void coreHasNoSiblingModuleDependencies() {
        ArchRule rule = noClasses().that().resideInAPackage("at.aimon.core..").should().dependOnClassesThat()
                .resideInAnyPackage("at.aimon.cli..", "at.aimon.llm..", "at.aimon.sandbox..", "at.aimon.session..",
                        "at.aimon.memory..", "at.aimon.filesystem..", "at.aimon.knowledge..", "at.aimon.browser..",
                        "at.aimon.scheduling..", "at.aimon.web..");

        rule.check(CORE_MAIN);
    }

    @Test
    @DisplayName("Tool implementations end with the 'Tool' suffix")
    void toolClassesAreNamedWithToolSuffix() {
        ArchRule rule = classes().that().areAssignableTo("at.aimon.core.agent.tool.AbstractTool").and()
                .doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT).should()
                .haveSimpleNameEndingWith("Tool");

        rule.check(CORE_MAIN);
    }

    /**
     * The message history has two writers with different jobs: {@link TranscriptBuffer} owns the live turn and keeps
     * a mutable list because it is the append hot path, while {@link SessionRecord} is the durable aggregate whose
     * transcript is immutable and shared by reference between copies. Mutating the history through the aggregate is a
     * lost-update hazard against the buffer the turn is actually writing to, so the flush path runs through
     * {@link at.aimon.core.agent.session.transcript.SessionSnapshot} instead.
     *
     * <p>
     * The three append methods this rule used to cover no longer exist — {@link #sessionRecordHasNoAppendMethod()}
     * keeps them gone. What survives on the aggregate is {@code setSystemPrompt}, which no production caller uses
     * today; it stays reachable for record construction inside the owning package and nowhere else.
     *
     * <p>
     * Two limitations worth knowing: this only scans {@code at.aimon.core}, so sibling modules are not covered, and
     * it matches direct calls only, so a caller reaching the method reflectively slips through.
     */
    @Test
    @DisplayName("message history is not written through the SessionRecord aggregate")
    void systemPromptIsNotMutatedOutsideTheStorePackage() {
        ArchRule rule = noClasses().that().resideOutsideOfPackage("at.aimon.core.agent.session.store").should()
                .callMethod(SessionRecord.class, "setSystemPrompt", String.class)
                .because("TranscriptBuffer owns the live history; SessionRecord is flushed to via SessionSnapshot");

        rule.check(CORE_MAIN);
    }

    /**
     * The rule above can only forbid calls to methods that exist. Re-adding an append method to {@link SessionRecord}
     * would therefore reopen the hole silently — the forbidding rule would keep passing while the new method went
     * unguarded. This asserts the absence directly, on the class rather than on its callers, so the removal is a
     * decision the build defends instead of a coincidence of the current call graph.
     *
     * <p>
     * Appending here is also O(n) per message, because the transcript is immutable and every append rebuilds it.
     * Supply the history whole instead: a {@link at.aimon.core.agent.session.transcript.SessionTranscript} or a
     * {@code List<Message>} passed to the constructor.
     */
    @Test
    @DisplayName("SessionRecord exposes no append method")
    void sessionRecordHasNoAppendMethod() {
        assertThat(SessionRecord.class.getMethods()).extracting(Method::getName).doesNotContain("addMessage",
                "addUserMessage", "addAssistantMessage");
    }
}
