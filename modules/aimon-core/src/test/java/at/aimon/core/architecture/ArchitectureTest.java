package at.aimon.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import at.aimon.core.agent.AgentRuntime;
import at.aimon.core.agent.session.LiveSession;
import at.aimon.core.agent.tool.AbstractTool;

/**
 * ArchUnit tests for core architectural rules.
 *
 * <p>
 * This test class enforces the following rules:
 * <ul>
 * <li>Tool implementations must follow naming conventions
 * <li>Tool implementations in ext.tools must extend {@link AbstractTool}
 * <li>Session code must not close the agent-scoped {@link AgentRuntime}
 * <li>{@code agent.session.store} must not depend on the node-local {@link LiveSession} handle
 * </ul>
 *
 * <p>
 * The last two encode scope invariants that were previously stated only in prose (see
 * {@code docs/overview/scope-model.md}). Both currently have zero violations; they exist to keep it that way.
 *
 * <p>
 * Note: Package cycle tests are covered by
 * {@code at.aimon.architecture.PackageDependencyArchitectureTest}.
 */
@DisplayName("Core Architecture Tests")
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon.core");
    }

    @Test
    @DisplayName("Classes extending AbstractTool should have names ending with 'Tool'")
    void toolImplementationsShouldBeNamedCorrectly() {
        ArchRule rule = classes().that().areAssignableTo(AbstractTool.class).and()
                .areNotAssignableFrom(AbstractTool.class).should().haveSimpleNameEndingWith("Tool");

        rule.check(classes);
    }

    @Test
    @DisplayName("Tool classes in ext.tools package should extend AbstractTool")
    void toolsInExtToolsPackageShouldExtendAbstractTool() {
        ArchRule rule = classes().that().resideInAPackage("at.aimon.core.tools..").and()
                .haveSimpleNameEndingWith("Tool").should().beAssignableTo(AbstractTool.class);

        rule.check(classes);
    }

    @Test
    @DisplayName("SK-08-F: Legacy CustomCommand types must remain absent from production sources")
    void shouldNotResurrectLegacyCustomCommandTypes() {
        // Belt-and-braces: the classes are deleted; ArchUnit just guards against future regressions. Each name is
        // checked independently so a regression in one place produces a precise failure message.
        ArchRule noCustomCommand = noClasses().should().haveFullyQualifiedName("at.aimon.core.command.CustomCommand")
                .as("CustomCommand was removed in SK-08-F (use SkillBackedCommand)");
        ArchRule noCustomCommandRegistry = noClasses().should()
                .haveFullyQualifiedName("at.aimon.core.command.CustomCommandRegistry")
                .as("CustomCommandRegistry was removed in SK-08-F");
        ArchRule noMarkdownParser = noClasses().should()
                .haveFullyQualifiedName("at.aimon.core.command.parser.MarkdownCommandParser")
                .as("MarkdownCommandParser was removed in SK-08-F");
        ArchRule noLlmCommandExecutor = noClasses().should()
                .haveFullyQualifiedName("at.aimon.core.command.execution.llm.LlmCommandExecutor")
                .as("LlmCommandExecutor was removed in SK-08-F (use LlmSkillExecutor)");
        ArchRule noToolCallFormatter = noClasses().should()
                .haveFullyQualifiedName("at.aimon.core.command.execution.llm.ToolCallFormatter")
                .as("ToolCallFormatter was removed in SK-08-F (use SkillContentRenderer)");
        ArchRule noContextToken = noClasses().should()
                .haveFullyQualifiedName("at.aimon.core.command.execution.llm.ContextToken")
                .as("ContextToken was removed in SK-08-F");
        ArchRule noCommandRepository = noClasses().should()
                .haveFullyQualifiedName("at.aimon.core.command.repository.CommandRepository")
                .as("CommandRepository was removed in SK-08-F");
        ArchRule noVfsCommandRepository = noClasses().should()
                .haveFullyQualifiedName("at.aimon.core.command.repository.VfsCommandRepository")
                .as("VfsCommandRepository was removed in SK-08-F");

        noCustomCommand.check(classes);
        noCustomCommandRegistry.check(classes);
        noMarkdownParser.check(classes);
        noLlmCommandExecutor.check(classes);
        noToolCallFormatter.check(classes);
        noContextToken.check(classes);
        noCommandRepository.check(classes);
        noVfsCommandRepository.check(classes);

        // Defensive: the legacy parser and llm execution sub-packages should hold no classes.
        ArchRule emptyParserPackage = noClasses().should().resideInAPackage("at.aimon.core.command.parser")
                .as("at.aimon.core.command.parser was removed in SK-08-F");
        ArchRule emptyRepositoryPackage = noClasses().should().resideInAPackage("at.aimon.core.command.repository")
                .as("at.aimon.core.command.repository was removed in SK-08-F");

        emptyParserPackage.check(classes);
        emptyRepositoryPackage.check(classes);
    }

    @Test
    @DisplayName("Session code must never close the agent-scoped AgentRuntime")
    void sessionScopedCodeMustNotCloseAgentRuntime() {
        // AgentRuntime itself declares no close() and extends nothing — close() exists only on the concrete
        // OrcaAgentRuntime, which adds AutoCloseable. DefaultLiveSession holds the runtime as that concrete type,
        // so a regression `agentRuntime.close()` compiles fine and emits invokevirtual OrcaAgentRuntime.close().
        // Matching on "target owner is assignable to AgentRuntime" is therefore what catches it; an
        // owner-equality predicate on AgentRuntime would match nothing ever, since AgentRuntime has no close()
        // member at all. The assignableTo clause is also what stops this from degenerating into
        // "no AutoCloseable#close anywhere".
        final DescribedPredicate<JavaMethodCall> closesAnAgentRuntime = DescribedPredicate
                .describe("call close() on an AgentRuntime",
                        call -> "close".equals(call.getTarget().getName())
                                && call.getTarget().getRawParameterTypes().isEmpty()
                                && call.getTargetOwner().isAssignableTo(AgentRuntime.class));

        final ArchRule rule = noClasses().that().resideInAPackage("at.aimon.core.agent.session..").should()
                .callMethodWhere(closesAnAgentRuntime)
                .as("session code must not close the agent-scoped AgentRuntime — other live sessions of the same "
                        + "agent still use it; teardown belongs to OrcaAgentRuntimeManager.destroyRuntime");

        rule.check(classes);
    }

    @Test
    @DisplayName("agent.session.store must not depend on the node-local LiveSession handle")
    void durableSessionStateMustNotDependOnTheLiveHandle() {
        // SessionRecord : LiveSession is 1 : 0..N, and the asymmetry is the whole point: the record survives
        // idle-TTL eviction, process restart and cross-node handoff, while the handle survives none of them. A
        // dependency from the durable side onto the handle is therefore a dependency on something that is usually
        // absent, and it is exactly how "keep the running state on the record" regressions begin. The traffic is
        // one-way by design — the handle reads and flushes the record; the record must not know a handle exists.
        //
        // This replaces the two name-token rules that guarded agent.session vs agent.session before the
        // session-first restructure. Those became vacuous the moment agent.session was emptied — one of them
        // selected zero classes and only ArchUnit's failOnEmptyShould kept it from reporting green forever. A rule
        // that cannot fail is worse than no rule, because it is read as coverage; PackageDependencyArchitectureTest
        // makes the same call for the subagent SPI, swapping an unenforceable package rule for a type allow-list.
        // Design §3.4 rule 2 states the invariant the old pair was reaching for, in dependencies not spelling.
        //
        // Matched two ways because the handle family is open-ended: assignability to the interface catches
        // implementations and sub-interfaces, and the Live* naming convention catches value types like
        // LiveSessionStatus that deliberately implement nothing.
        final DescribedPredicate<JavaClass> aLiveHandleType = DescribedPredicate.describe(
                "LiveSession or an at.aimon Live* type",
                candidate -> candidate.isAssignableTo(LiveSession.class)
                        || candidate.getPackageName().startsWith("at.aimon")
                                && candidate.getSimpleName().startsWith("Live"));

        final ArchRule rule = noClasses().that().resideInAPackage("at.aimon.core.agent.session.store..").should()
                .dependOnClassesThat(aLiveHandleType)
                .as("at.aimon.core.agent.session.store must not depend on LiveSession or any Live* type — the "
                        + "durable side must not know the node-local handle exists");

        rule.check(classes);
    }
}
