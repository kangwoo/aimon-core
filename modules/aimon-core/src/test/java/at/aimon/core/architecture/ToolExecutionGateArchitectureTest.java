package at.aimon.core.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.execution.DefaultToolExecutor;
import at.aimon.core.hook.impl.RecentFilesRestoreHook;
import at.aimon.core.memory.deriver.ReActLlmDeriver;
import at.aimon.core.scheduling.RoutineExecutor;
import at.aimon.core.tools.artifact.ArtifactAwareEditTool;
import at.aimon.core.tools.artifact.ArtifactAwareWriteTool;

/**
 * Keeps {@code Tool#execute} reachable only through the schema-validation gate, or through a call site that was
 * looked at and written down.
 *
 * <p>
 * {@link DefaultToolExecutor} validates a call's arguments against the tool's declared schema immediately before
 * running it. Anything that invokes a tool without going through the executor skips that check — so the value of the
 * gate is exactly the length of this list. The rule's real purpose is not to forbid the five exceptions below but to
 * <b>make the sixth one an argument</b>: adding a name here should send the author back to the design's §12 Q1, where
 * the reason each of these stays out of the gate is recorded (blast radius, not correctness).
 *
 * <h2>Class literals, not package strings</h2>
 *
 * <p>
 * The allowlist is written as {@code .class} references on purpose. An earlier draft of the design carried three
 * hand-written package paths that were simply wrong, and a rule keyed on a string that matches nothing does not fail
 * — it <b>passes silently</b>, which is the worst outcome available. A class literal that no longer resolves stops
 * the compiler instead.
 *
 * <h2>Calls and references, not calls alone</h2>
 *
 * <p>
 * The rule matches access targets rather than call sites, because {@code callMethodWhere} sees only a
 * {@code JavaMethodCall}. A {@code tool::execute} handed to a {@code Stream} or an {@code Executor} compiles to a
 * method <em>reference</em>, invokes the tool exactly the same way, and would have walked past a call-only rule — the
 * silent pass this test exists to avoid.
 *
 * <h2>What this rule cannot see</h2>
 *
 * <p>
 * {@code aimon-browser-playwright}'s {@code ArtifactAwareBrowserTool} delegates to its wrapped tool the same way the
 * two artifact decorators here do, and this rule will never catch it: the core test suite does not have that module
 * on its classpath. That gap is a property of where the test lives, not an exemption.
 */
@DisplayName("Tool Execution Gate Architecture Tests")
class ToolExecutionGateArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon");
    }

    @Test
    @DisplayName("Tool#execute is invoked only by the schema-validation gate and the five recorded exceptions")
    void toolExecuteIsCalledOnlyFromTheGateOrARecordedException() {
        ArchRule rule = noClasses().that()
                .doNotBelongToAnyOf(DefaultToolExecutor.class, ReActLlmDeriver.class, RoutineExecutor.class,
                        RecentFilesRestoreHook.class, ArtifactAwareEditTool.class, ArtifactAwareWriteTool.class)
                .should()
                .accessTargetWhere(describe("Tool#execute, called or referenced",
                        access -> (access instanceof JavaMethodCall || access instanceof JavaMethodReference)
                                && "execute".equals(access.getTarget().getName())
                                && access.getTarget().getOwner().isAssignableTo(Tool.class)))
                .because("a tool invoked outside DefaultToolExecutor skips schema validation; the five exceptions are"
                        + " recorded in the tool-contract-hardening design §12 Q1, and a sixth needs the same argument"
                        + " made in writing");

        rule.check(classes);
    }
}
