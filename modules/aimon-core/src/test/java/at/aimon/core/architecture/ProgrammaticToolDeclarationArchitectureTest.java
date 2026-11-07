package at.aimon.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import at.aimon.core.agent.tool.Tool;
import at.aimon.core.knowledge.wiki.WikiKnowledgeBase;
import at.aimon.core.llm.LlmClient;
import at.aimon.core.llm.invoke.LlmCallGateway;
import at.aimon.core.memory.dialectic.DialecticEngine;
import at.aimon.core.subagent.SubagentExecutionManager;

/**
 * The two built-in tool declarations programmatic tool calling reads are made, and made where they have to be
 * ({@code docs/design/tool/programmatic-tool-calling.md} §17).
 *
 * <ul>
 * <li><b>Decision 15.</b> A tool in {@code at.aimon.core.tools..} whose class graph reaches an LLM client, the LLM call
 * gateway, the dialectic engine, the wiki knowledge base or the subagent execution manager overrides
 * {@link Tool#isCodeCallable()}. Such a tool calls a model or starts a fork; called from a script, that work would grow
 * outside the script's limits and budget. A list of tool names would miss the next such tool; the class graph does
 * not.
 * <li><b>§7.1.</b> Every built-in tool declares {@link Tool#dataReach()} itself rather than inheriting the
 * conservative default — the default exists for tools nobody has audited, and a built-in tool has been.
 * </ul>
 *
 * <p>
 * The class graph is the transitive closure of each tool's dependencies inside {@code at.aimon.core}, through the
 * classes main sources declare, except for a short list of classes the walk does not enter ({@link #WALK_STOPS}, each
 * with its reason). A tool that reaches one of the types through a shared helper is caught as well as one that holds
 * it in a field.
 */
@DisplayName("Programmatic tool calling declaration architecture tests")
class ProgrammaticToolDeclarationArchitectureTest {

    /** The types decision 15 names: reaching one of them means the tool calls a model or starts a fork. */
    private static final Set<String> MODEL_OR_FORK_TYPES = Set.of(LlmClient.class.getName(),
            LlmCallGateway.class.getName(), DialecticEngine.class.getName(), WikiKnowledgeBase.class.getName(),
            SubagentExecutionManager.class.getName());

    /**
     * Classes the walk does not enter, nested classes included, because a class-level graph reads them as reaching a
     * decision-15 type that no tool going through them calls. Each needs the reason a tool reaching it does not call a
     * model or start a fork.
     *
     * <ul>
     * <li>{@code ToolContextKeys} — a registry of typed key constants. Its keys name {@code WikiKnowledgeBase.class}
     * among others; reading one key reaches the type of that key, which the tool then names itself if it uses it.
     * <li>{@code StoreBackedPeerMemory} — the node-local memory facade. It holds a {@code DialecticEngine} for
     * {@code chat()} only; the memory read/write tools build or hold it for its search, recall and observe halves, and
     * the one tool that chats ({@code MemoryChatTool}) names {@code DialecticEngine} directly.
     * </ul>
     */
    private static final Set<String> WALK_STOPS = Set.of("at.aimon.core.tools.ToolContextKeys",
            "at.aimon.core.memory.StoreBackedPeerMemory");

    private static JavaClasses mainClasses;
    private static List<JavaClass> toolsPackageTools;
    private static List<JavaClass> builtInTools;

    @BeforeAll
    static void setUp() {
        mainClasses = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon.core");
        builtInTools = mainClasses.stream().filter(JavaClass::isTopLevelClass)
                .filter(javaClass -> !javaClass.isInterface() && !javaClass.isEnum())
                .filter(javaClass -> !javaClass.getModifiers().contains(JavaModifier.ABSTRACT))
                .filter(javaClass -> javaClass.isAssignableTo(Tool.class))
                .filter(javaClass -> javaClass.getPackageName().startsWith("at.aimon.core.tools.")
                        || javaClass.getPackageName().equals("at.aimon.core.memory.deriver.tool")
                        || javaClass.getPackageName().equals("at.aimon.core.mcp"))
                .sorted(Comparator.comparing(JavaClass::getName)).toList();
        toolsPackageTools = builtInTools.stream()
                .filter(javaClass -> javaClass.getPackageName().startsWith("at.aimon.core.tools.")).toList();
    }

    @Test
    @DisplayName("The sweep found the built-in tools — an empty scan would pass every other assertion")
    void discoversTheBuiltInTools() {
        assertThat(toolsPackageTools).hasSizeGreaterThan(20);
        assertThat(builtInTools).extracting(JavaClass::getSimpleName).contains("McpTool", "DeriverMemorySearchTool");
    }

    @Test
    @DisplayName("Every built-in tool declares dataReach() itself")
    void everyBuiltInToolDeclaresItsDataReach() {
        final List<String> undeclared = builtInTools.stream().map(JavaClass::reflect)
                .filter(type -> declaringClassOf(type, "dataReach") == Tool.class).map(Class::getName).toList();

        assertThat(undeclared).as("built-in tools that inherit Tool#dataReach()'s default").isEmpty();
    }

    @Test
    @DisplayName("A tool whose class graph reaches an LLM, the wiki or a fork overrides isCodeCallable() (decision 15)")
    void toolsThatReachAModelOrAForkAreNotCodeCallable() {
        final Map<String, String> reaching = new TreeMap<>();
        final List<String> undeclared = toolsPackageTools.stream().filter(tool -> {
            final String reached = firstModelOrForkTypeReachedBy(tool);
            if (reached == null) {
                return false;
            }
            reaching.put(tool.getSimpleName(), reached);
            return declaringClassOf(tool.reflect(), "isCodeCallable") == Tool.class;
        }).map(JavaClass::getName).toList();

        // Guards the closure itself: these are the tools the decision names, so a graph walk that stopped finding them
        // (or one that started finding every tool) is broken rather than passing.
        assertThat(reaching).containsKeys("TaskTool", "WorkflowTool", "MemoryChatTool", "WikiSearchTool");
        assertThat(reaching).doesNotContainKeys("ReadTool", "GrepTool", "BashTool", "WebFetchTool");
        assertThat(undeclared).as("tools reaching %s without overriding isCodeCallable()", reaching).isEmpty();
    }

    /**
     * The path from the tool to the first decision-15 type its class graph reaches, as {@code type <- ... <- tool}, or
     * {@code null} when it reaches none. The walk does not enter {@link #WALK_STOPS}.
     */
    private static String firstModelOrForkTypeReachedBy(JavaClass tool) {
        final Map<String, String> reachedFrom = new HashMap<>();
        final Deque<JavaClass> pending = new ArrayDeque<>();
        pending.add(tool);
        reachedFrom.put(tool.getName(), null);
        while (!pending.isEmpty()) {
            final JavaClass current = pending.poll();
            for (Dependency dependency : current.getDirectDependenciesFromSelf()) {
                final String name = dependency.getTargetClass().getBaseComponentType().getName();
                if (MODEL_OR_FORK_TYPES.contains(name)) {
                    final StringBuilder path = new StringBuilder(name);
                    for (String step = current.getName(); step != null; step = reachedFrom.get(step)) {
                        path.append(" <- ").append(step);
                    }
                    return path.toString();
                }
                if (name.startsWith("at.aimon.core.") && !isWalkStop(name) && !reachedFrom.containsKey(name)
                        && mainClasses.contain(name)) {
                    reachedFrom.put(name, current.getName());
                    pending.add(mainClasses.get(name));
                }
            }
        }
        return null;
    }

    /** Whether the walk stops at {@code name} — a {@link #WALK_STOPS} class or one nested in it. */
    private static boolean isWalkStop(String name) {
        final int nested = name.indexOf('$');
        return WALK_STOPS.contains(nested < 0 ? name : name.substring(0, nested));
    }

    private static Class<?> declaringClassOf(Class<?> type, String methodName) {
        try {
            return type.getMethod(methodName).getDeclaringClass();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(type.getName() + " has no " + methodName + "()", e);
        }
    }
}
