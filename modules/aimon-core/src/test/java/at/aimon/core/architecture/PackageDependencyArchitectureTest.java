package at.aimon.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import at.aimon.core.agent.AgentCorePackage;
import at.aimon.core.agent.AgentRuntimeId;
import at.aimon.core.agent.budget.CompletionReason;
import at.aimon.core.agent.impl.AgentImplPackage;
import at.aimon.core.base.BasePackage;
import at.aimon.core.command.execution.ExecutionMetadata;
import at.aimon.core.filesystem.FileSystemCorePackage;
import at.aimon.core.hook.HookExecutionManager;
import at.aimon.core.hook.HookFeedback;
import at.aimon.core.hook.HookRegistry;
import at.aimon.core.hook.event.OnSessionEndContext;
import at.aimon.core.hook.event.OnSessionStartContext;
import at.aimon.core.hook.event.PostCompactContext;
import at.aimon.core.hook.event.PreCompactContext;
import at.aimon.core.hook.execution.HookResult;
import at.aimon.core.llm.LlmCorePackage;
import at.aimon.core.llm.TokenUsage;
import at.aimon.core.shell.ShellCorePackage;
import at.aimon.core.subagent.Subagent;
import at.aimon.core.subagent.SubagentExecutionEnvironment;
import at.aimon.core.subagent.SubagentExecutionManager;
import at.aimon.core.subagent.execution.SubagentExecutionResult;

/**
 * ArchUnit tests for package dependency rules.
 *
 * <p>
 * This test class enforces the following layered architecture rules:
 *
 * <ul>
 * <li>at.aimon.core.llm can only depend on at.aimon.core.base and at.aimon.core.agent.prompt (narrow carve-out
 * for the parts-aware {@code sendMessage} overload — these are pure value types and do not create a cycle)
 * <li>at.aimon.core.filesystem can only depend on at.aimon.core.base
 * <li>at.aimon.core.agent can only depend on at.aimon.core.base, at.aimon.core.llm, at.aimon.core.filesystem
 * </ul>
 */
@DisplayName("Package Dependency Architecture Tests")
class PackageDependencyArchitectureTest {

    private static JavaClasses classes;

    /**
     * Mutually-dependent top-level {@code at.aimon.core} package pairs as measured with {@code agent.impl} excluded.
     * Frozen: see {@link #noNewTopLevelCorePackageCycles()} for why this is a baseline and not a prohibition. Shrink
     * it whenever a cycle is broken — the test fails if an entry here has become untrue.
     *
     * <p>
     * Two things are worth reading off the list rather than counting it. Nine of the thirteen involve {@code agent}
     * or {@code command}, which is what "the executor is the hub" looks like from the package graph. And the four
     * {@code ↔ tools} entries are a different shape: a tool has to reach the subsystem it exposes
     * ({@code scheduling}, {@code skill}, {@code subagent}) while that subsystem names the tool that fronts it, so
     * those close through the built-in tool set rather than through the executor.
     */
    private static final Set<String> BASELINE_TOP_LEVEL_CYCLES = Set.of("agent <-> hook", "agent <-> llm",
            "agent <-> scheduling", "agent <-> skill", "agent <-> subagent", "agent <-> workflow", "command <-> hook",
            "command <-> skill", "command <-> subagent", "command <-> tools", "scheduling <-> tools", "skill <-> tools",
            "subagent <-> tools");

    // Package markers for type-safe package references
    private static final String PKG_CORE = BasePackage.class.getPackageName() + "..";
    private static final String PKG_LLM_CORE = LlmCorePackage.class.getPackageName() + "..";
    private static final String PKG_FILESYSTEM_CORE = FileSystemCorePackage.class.getPackageName() + "..";
    private static final String PKG_SHELL_CORE = ShellCorePackage.class.getPackageName() + "..";
    private static final String PKG_AGENT_CORE = AgentCorePackage.class.getPackageName() + "..";
    private static final String PKG_AGENTS = AgentImplPackage.class.getPackageName() + "..";
    // Narrow carve-out: the structured-system-prompt value types live under agent.prompt but are pure data and are
    // intentionally referenced by the LlmClient parts-aware overload (CTX-05). Allowing only this sub-package keeps
    // the agent→llm direction intact (no cycle) while unlocking parts-aware provider APIs.
    private static final String PKG_AGENT_PROMPT = AgentCorePackage.class.getPackageName() + ".prompt..";
    // Narrow carve-out: the L3 conversation-compaction integration (CONV-COMPACT-01) lives under agent.compact and
    // by design integrates with PreCompactHook / PostCompactHook for custom-instruction injection and post-summary
    // restoration. Hook contexts (PreCompactContext, PostCompactContext) in turn reference compaction value types
    // (CompactionTrigger, CompactionMetadata), so the coupling is bidirectional and intentional.
    private static final String PKG_AGENT_COMPACT = AgentCorePackage.class.getPackageName() + ".compact..";
    // Narrow carve-out: at.aimon.core.agent.orca holds the public Orca tool-provider SPIs (OrcaToolProvider,
    // OrcaToolProviderContext, OrcaProviderDependencies). They aggregate dependencies from cross-cutting
    // registries (subagent, skill, scheduling, credential, hook, mcp, ...) by design — this is the SPI surface
    // that external modules implement, not the broader agent.* contract.
    private static final String PKG_AGENT_ORCA = AgentCorePackage.class.getPackageName() + ".orca..";
    // Narrow carve-out: WI-3.3.d wires DefaultLiveSession (in agent.session) to fire OnSessionStart / OnSessionEnd
    // hooks at construction and close. The session package therefore needs to reference HookExecutionManager,
    // HookRegistry, HookResult, OnSessionStartContext, OnSessionEndContext — but nothing else from at.aimon.core.hook.
    private static final String PKG_AGENT_SESSION = AgentCorePackage.class.getPackageName() + ".session..";
    // Narrow carve-out (SPX-0, docs/design/session/spi-extraction.md §4): SessionRecordCodec — the wire
    // encoding every distributed SessionRecordStore backend shares — moves into agent.session.store, and it encodes
    // the transcript half by delegating to SessionSnapshotCodec / JsonSessionSnapshotCodec rather than duplicating
    // that mapping. Those two live under subagent.task.codec because their first consumer was the subagent-resume
    // SessionSnapshotStore; they are pure data codecs with no subagent behaviour attached.
    //
    // Honest caveat: this is NOT a one-way edge. subagent.task.codec already depends on agent.session
    // (SessionSnapshot, SessionId), so allowing agent.session.store -> subagent.task.codec makes the coupling
    // mutual. No existing rule catches it — the slice cycle check in noCorePackageCycles() only slices
    // at.aimon.core.agent.(*).., so classes outside that tree are not slice members and edges through them are
    // invisible to it. It is accepted here because both directions carry only value types, and because the real fix
    // is to relocate the two codecs to agent.session.transcript (their name stopped matching their consumers once
    // workflow, the runtime factory and this SPI joined the subagent one). That relocation is deliberately NOT part
    // of the SPI extraction — see design §4 option B and §7 open question 3.
    private static final String PKG_SUBAGENT_TASK_CODEC = "at.aimon.core.subagent.task.codec..";
    // Narrow carve-out: SK-11 atomic-suspension exposes a SkillTurnSuspendedEvent in agent.stream that carries the
    // PendingTurnId + PendingSkillRequest value types from the skill policy package. These are pure DTOs (no behaviour
    // beyond identity/snapshot) so referencing them keeps the agent->ext direction one-way and acyclic.
    private static final String PKG_SKILL_POLICY_PENDING = "at.aimon.core.skill.policy.pending..";
    // WU-6 (subagent-workflow design §3.3, B4): the workflow subsystem sits on the subagent SPI. The subagent
    // domain does NOT follow the .impl split (Default* impls share the at.aimon.core.subagent[.execution] packages with
    // the SPI types), so a package-level "no subagent..impl" rule would be vacuous. SPI-only is instead enforced as a
    // type allow-list: workflow may reference only the four SPI/value types below, not the Default* impls.
    private static final String PKG_WORKFLOW = "at.aimon.core.workflow..";
    // Phase 3: the background runner derives a per-run environment carrying a run coordinator's cancellation
    // signal (InterruptCoordinator / CancellationSignal / InterruptReason), so stop(runId) reaches the run's subagents.
    private static final String PKG_AGENT_INTERRUPT = "at.aimon.core.agent.interrupt..";
    // Phase 3: the aggregate cost budget reads a subagent result's estimated Money cost (llm.cost).
    private static final String PKG_LLM_COST = "at.aimon.core.llm.cost..";

    // External library packages
    private static final String PKG_JAVA = "java..";
    private static final String PKG_SLF4J = "org.slf4j..";
    private static final String PKG_JACKSON = "com.fasterxml.jackson..";
    private static final String PKG_SNAKEYAML = "org.yaml.snakeyaml..";
    private static final String PKG_MUSTACHE = "com.github.mustachejava..";

    @BeforeAll
    static void setUp() {
        // Import only production code, excluding test classes
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("at.aimon");
    }

    @Test
    @DisplayName("at.aimon.core.llm should only depend on at.aimon.core.base, at.aimon.core.agent.prompt (value"
            + " types for the CTX-05 parts-aware sendMessage overload), and Java standard libraries")
    void llmCoreShouldOnlyDependOnCore() {
        ArchRule rule = classes().that().resideInAPackage(PKG_LLM_CORE).should().onlyDependOnClassesThat()
                .resideInAnyPackage(PKG_LLM_CORE, PKG_CORE, PKG_AGENT_PROMPT, PKG_JAVA, PKG_SLF4J, PKG_JACKSON);

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.filesystem should only depend on at.aimon.core.base and Java standard libraries")
    void filesystemCoreShouldOnlyDependOnCore() {
        ArchRule rule = classes().that().resideInAPackage(PKG_FILESYSTEM_CORE).should().onlyDependOnClassesThat()
                .resideInAnyPackage(PKG_FILESYSTEM_CORE, PKG_CORE, PKG_JAVA, PKG_SLF4J);

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.agent (excluding agent.compact) should only depend on at.aimon.core.base,"
            + " at.aimon.core.llm, at.aimon.core.filesystem, at.aimon.core.shell, at.aimon.core.agent.impl and Java"
            + " standard libraries")
    void agentCoreShouldOnlyDependOnCoreAndLlmAndFilesystem() {
        // The agent.compact sub-package is allowed to reach ext.hook (see PKG_AGENT_COMPACT carve-out comment).
        // The SK-11 SkillTurnSuspendedEvent (in agent.stream) references PendingTurnId / PendingSkillRequest from the
        // ext.skill.policy.pending DTO package — see PKG_EXT_SKILL_PENDING carve-out comment.
        ArchRule rule = classes().that().resideInAPackage(PKG_AGENT_CORE).and()
                .resideOutsideOfPackage(PKG_AGENT_COMPACT).and().resideOutsideOfPackage(PKG_AGENT_ORCA).and()
                .resideOutsideOfPackage(PKG_AGENT_SESSION).and().resideOutsideOfPackage(PKG_AGENTS).should()
                .onlyDependOnClassesThat().resideInAnyPackage(PKG_AGENT_CORE, PKG_CORE, PKG_LLM_CORE,
                        PKG_FILESYSTEM_CORE, PKG_SHELL_CORE, PKG_AGENTS, PKG_SKILL_POLICY_PENDING, PKG_JAVA, PKG_SLF4J,
                        PKG_SNAKEYAML, PKG_JACKSON, PKG_MUSTACHE);

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.agent.compact may depend only on six specific hook types"
            + " (HookRegistry, HookExecutionManager, HookResult, HookFeedback, PreCompactContext, PostCompactContext)"
            + " for the L3 compaction hook integration (CONV-COMPACT-01) — not on the at.aimon.core.hook"
            + " package as a whole")
    void agentCompactMayDependOnExtHook() {
        // Narrow carve-out: agent.compact reaches into at.aimon.core.hook only for the six types listed below.
        // PreCompactContext / PostCompactContext are compaction-specific value types that themselves reference
        // CompactionTrigger / CompactionMetadata / InvokedSkillRecord from agent.compact; the bidirectional
        // coupling is intentional but kept narrow. The other four are general hook plumbing types
        // (registry/manager/result/feedback) that any hook caller needs. HookFeedback is a stateless static
        // helper: the engine calls collectAdvisory(...) so that only ADVISORY notes reach the summarization
        // prompt — a PreCompact deny reason must not be spliced in as a custom instruction.
        ArchRule rule = classes().that().resideInAPackage(PKG_AGENT_COMPACT).should()
                .onlyDependOnClassesThat(JavaClass.Predicates
                        .belongToAnyOf(HookRegistry.class, HookExecutionManager.class, HookResult.class,
                                HookFeedback.class, PreCompactContext.class, PostCompactContext.class)
                        .or(JavaClass.Predicates.resideInAnyPackage(PKG_AGENT_CORE, PKG_CORE, PKG_LLM_CORE,
                                PKG_FILESYSTEM_CORE, PKG_SHELL_CORE, PKG_AGENTS, PKG_JAVA, PKG_SLF4J, PKG_SNAKEYAML,
                                PKG_JACKSON, PKG_MUSTACHE)));

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.agent.session may depend only on five specific hook types"
            + " (HookRegistry, HookExecutionManager, HookResult, OnSessionStartContext, OnSessionEndContext)"
            + " for the WI-3.3.d session-lifecycle hook firing — not on the at.aimon.core.hook package as a whole —"
            + " plus the snapshot codecs in at.aimon.core.subagent.task.codec (SPX-0)")
    void liveSessionMayDependOnHookSessionTypes() {
        // Narrow carve-out: agent.session reaches into at.aimon.core.hook only for the five types listed below.
        // OnSessionStartContext / OnSessionEndContext are session-lifecycle value types fired by DefaultLiveSession
        // at construction and close. The other three are general hook plumbing types (registry/manager/result)
        // that any hook caller needs.
        // Second carve-out: PKG_SUBAGENT_TASK_CODEC — see its declaration for why, and for what it costs.
        ArchRule rule = classes().that().resideInAPackage(PKG_AGENT_SESSION).should()
                .onlyDependOnClassesThat(JavaClass.Predicates
                        .belongToAnyOf(HookRegistry.class, HookExecutionManager.class, HookResult.class,
                                OnSessionStartContext.class, OnSessionEndContext.class)
                        .or(JavaClass.Predicates.resideInAnyPackage(PKG_AGENT_CORE, PKG_CORE, PKG_LLM_CORE,
                                PKG_FILESYSTEM_CORE, PKG_SHELL_CORE, PKG_AGENTS, PKG_SUBAGENT_TASK_CODEC, PKG_JAVA,
                                PKG_SLF4J, PKG_SNAKEYAML, PKG_JACKSON, PKG_MUSTACHE)));

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.workflow may depend only on the subagent SPI types (SubagentExecutionManager,"
            + " SubagentExecutionEnvironment, Subagent, SubagentExecutionResult) — not on the Default* impls that share"
            + " the subagent package (WU-6, subagent-workflow design §3.3 / B4)")
    void workflowMayDependOnlyOnSubagentSpiTypes() {
        // Type allow-list rather than package rule: SPI-only cannot be expressed at package granularity because
        // DefaultSubagentExecutionManager / DefaultSubagentExecutor live in the same packages as the SPI types
        // (the subagent domain has no .impl split). Referencing any other subagent type (e.g. a Default* impl or an
        // internal .execution/.behavior type) fails this rule.
        // Phase 2 additions: CompletionReason (agent.budget) surfaced on AgentStepResult; ExecutionMetadata
        // (command.execution) + TokenUsage (llm) read for the run-scoped aggregate token budget; Jackson for
        // structured-output JSON parsing. Still no subagent Default* impls.
        // Phase 3 addition: AgentRuntimeId (agent) is the run's boundRuntimeId on WorkflowRun /
        // RunQuery — the multi-instance scoping key, mirroring BackgroundTask/TaskQuery. Still a value type, not an
        // impl.
        // Phase 3 addition: VfsStepResultCache depends on the VirtualFileSystem SPI
        // (at.aimon.core.filesystem) for the shared/persistent step cache, mirroring VfsSessionSnapshotStore. The
        // separate filesystemImplMustNotLeak rule still bars workflow from at.aimon.core.filesystem.impl.
        ArchRule rule = classes().that().resideInAPackage(PKG_WORKFLOW).should()
                .onlyDependOnClassesThat(JavaClass.Predicates
                        .belongToAnyOf(SubagentExecutionManager.class, SubagentExecutionEnvironment.class,
                                Subagent.class, SubagentExecutionResult.class, CompletionReason.class,
                                ExecutionMetadata.class, TokenUsage.class, AgentRuntimeId.class)
                        .or(JavaClass.Predicates.resideInAnyPackage(PKG_WORKFLOW, PKG_CORE, PKG_FILESYSTEM_CORE,
                                PKG_AGENT_INTERRUPT, PKG_LLM_COST, PKG_JAVA, PKG_SLF4J, PKG_JACKSON)));

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.workflow.impl must not be referenced from outside the at.aimon.core.workflow.*"
            + " tree — consumers use the WorkflowRunner SPI + WorkflowRunners factory")
    void workflowImplMustNotLeakOutsideWorkflowTree() {
        ArchRule rule = noClasses().that().resideOutsideOfPackage(PKG_WORKFLOW).should().dependOnClassesThat()
                .resideInAPackage("at.aimon.core.workflow.impl..");

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.ext is decommissioned — no production class may reside in it")
    void extPackageIsDecommissioned() {
        ArchRule rule = noClasses().should().resideInAPackage("at.aimon.core.ext..");

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.skill.hook.declarative.predicate concrete public classes must implement"
            + " ToolInputPredicate or be the package-private PredicateParser utility (Phase 1 hook upgrade,"
            + " WI-1.4.x). Concrete predicate types are part of the impl, not the SPI surface.")
    void predicateSubpackageClassesImplementToolInputPredicate() {
        // The predicate subpackage holds the impl classes (NameOnly/BashSubcommand/PathGlob/Composite) that
        // realise the ToolInputPredicate SPI in at.aimon.core.skill.hook.declarative, plus the PredicateParser
        // factory utility. Every concrete top-level class in the subpackage must either implement
        // ToolInputPredicate or be a stateless utility (PredicateParser) — guarantees the package stays focused
        // and prevents unrelated types from sneaking in.
        ArchRule rule = classes().that().resideInAPackage("at.aimon.core.skill.hook.declarative.predicate..").and()
                .doNotHaveModifier(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT).and().areTopLevelClasses()
                .should().implement("at.aimon.core.skill.hook.declarative.ToolInputPredicate").orShould()
                .haveFullyQualifiedName("at.aimon.core.skill.hook.declarative.predicate.PredicateParser");

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.skill.hook.declarative.predicate is impl detail — only the skill parser and the"
            + " hook config bootstrap may import from it. External modules (aimon-cli, aimon-llm-*, ...) and other"
            + " core packages must depend on the ToolInputPredicate SPI in at.aimon.core.skill.hook.declarative"
            + " instead.")
    void predicateSubpackageIsNotReachableFromOutsideAllowedCallers() {
        // Allowed callers: the predicate subpackage itself, at.aimon.core.skill.parser (SkillHookSetParser builds
        // predicates from declarative skill markdown), and at.aimon.core.config.hook (HookRegistryApplier parses
        // Claude Code hooks.json matchers into predicates). Anyone else must depend on the ToolInputPredicate
        // interface. The parent declarative package used to be allowed too, for the sole benefit of the
        // name-only ToolMatcher facade; that facade is gone, so the allowance went with it.
        ArchRule rule = noClasses().that().resideOutsideOfPackage("at.aimon.core.skill.hook.declarative.predicate..")
                .and().resideOutsideOfPackage("at.aimon.core.skill.parser..").and()
                .resideOutsideOfPackage("at.aimon.core.config.hook..").should().dependOnClassesThat()
                .resideInAPackage("at.aimon.core.skill.hook.declarative.predicate..");

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.agent.impl must not be referenced from outside the at.aimon.core.agent.* tree."
            + " External modules implementing Orca tool providers must depend on the neutral SPI package"
            + " at.aimon.core.agent.orca, not on agent.impl directly.")
    void agentImplMustNotLeakOutsideAgentTree() {
        // Scope: scan production classes that live OUTSIDE at.aimon.core.agent.. — i.e. ext packages
        // (mcp.orca, tools.*, scheduling.*, ...) and external impl modules (aimon-sandbox, aimon-llm-*, etc.).
        // Those callers may only see the public Orca SPIs in at.aimon.core.agent.orca.., never the executor
        // internals in at.aimon.core.agent.impl... The session→orca coupling inside core.agent.session is a
        // separate, intentionally-internal concern (the session API uses concrete OrcaAgentExecution* types
        // today) and is allowed by this rule because it lives within the agent.. tree.
        ArchRule rule = noClasses().that().resideOutsideOfPackage(PKG_AGENT_CORE).should().dependOnClassesThat()
                .resideInAPackage(PKG_AGENTS);

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.filesystem.impl must not be referenced from outside the at.aimon.core.filesystem.*"
            + " tree, except by the in-core assembler at at.aimon.core.agent.impl.orca.environment which wires"
            + " concrete LocalFileSystem instances. Other consumers (core.tools.*, external modules) must depend"
            + " on the VirtualFileSystem SPI in at.aimon.core.filesystem.")
    void filesystemImplMustNotLeakOutsideFilesystemTree() {
        // Carve-out: at.aimon.core.agent.impl.orca.environment.LocalExecutionEnvironment is the canonical
        // in-core assembler that constructs LocalFileSystem + LocalShell for the default Orca runtime. The
        // assembler boundary is allowed to import concrete impls; everything else must use the SPI.
        ArchRule rule = noClasses().that().resideOutsideOfPackage(PKG_FILESYSTEM_CORE).and()
                .resideOutsideOfPackage("at.aimon.core.agent.impl.orca.environment..").should().dependOnClassesThat()
                .resideInAPackage("at.aimon.core.filesystem.impl..");

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.shell.impl must not be referenced from outside the at.aimon.core.shell.* tree,"
            + " except by the in-core assembler at at.aimon.core.agent.impl.orca.environment which wires"
            + " concrete LocalShell instances. Other consumers must depend on the VirtualShell SPI in"
            + " at.aimon.core.shell.")
    void shellImplMustNotLeakOutsideShellTree() {
        ArchRule rule = noClasses().that().resideOutsideOfPackage(PKG_SHELL_CORE).and()
                .resideOutsideOfPackage("at.aimon.core.agent.impl.orca.environment..").should().dependOnClassesThat()
                .resideInAPackage("at.aimon.core.shell.impl..");

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.base should not depend on any other aimon packages")
    void coreShouldNotDependOnOtherAimonPackages() {
        ArchRule rule = classes().that().resideInAPackage(PKG_CORE).should().onlyDependOnClassesThat()
                .resideInAnyPackage(PKG_CORE, PKG_JAVA, PKG_SLF4J);

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.config.hook is the bootstrap/reload layer and must NOT be referenced from the runtime"
            + " hook or agent execution packages — only the application assembler (CLI / web entry point) wires it"
            + " up. Phase 3 WI-3.5.x.")
    void configHookMustNotLeakIntoRuntimeLayers() {
        // Rule direction: at.aimon.core.config.hook.. depends on the runtime hook layer (HookRegistry / *Hook) at
        // bootstrap time — never the reverse. Allowing the runtime layer to import config.* would invert the
        // intended layering (runtime calls into config). The CLI / web assembler (outside aimon-core) is the only
        // module that may construct HookConfigWatcher / HookRegistryReloader / HookRegistryApplier.
        ArchRule rule = noClasses().that()
                .resideInAnyPackage("at.aimon.core.hook..", "at.aimon.core.agent..", "at.aimon.core.skill..").should()
                .dependOnClassesThat().resideInAPackage("at.aimon.core.config.hook..");

        rule.check(classes);
    }

    @Test
    @DisplayName("at.aimon.core.config.hook may depend only on a curated allow-list: at.aimon.core.base,"
            + " at.aimon.core.agent (Environment / InvokerType value types), at.aimon.core.hook (registry +"
            + " execution manager + event types), at.aimon.core.skill.hook (declarative hook builders + actions),"
            + " at.aimon.core.scheduling.exception (the cron rejection it translates), plus Java/SLF4J/Jackson."
            + " Phase 3 WI-3.5.x.")
    void configHookOutboundDependenciesAreCurated() {
        // The bootstrap/reload layer materialises hooks.json into Declarative*Hook instances and registers them
        // on the live HookRegistry. Allowed inbound references:
        // - at.aimon.core.base — AimonException base class for HookConfigParseException
        // - at.aimon.core.agent — Environment + InvokerType value types embedded in OnConfigReloadContext
        // - at.aimon.core.hook.. — HookRegistry + HookExecutionManager + at.aimon.core.hook.event.* hook types
        // - at.aimon.core.skill.hook — Declarative*Hook builders and HookAction value types
        // - at.aimon.core.scheduling.exception — InvalidCronExpressionException only. RewakeTriggerCron validates
        // its expression in its own constructor, so a bad asyncRewake cron fails when the file is read rather
        // than when the hook fires; RewakeSpecParser catches that and re-throws it as the single
        // HookConfigParseException this layer promises. The rest of at.aimon.core.scheduling stays out — the
        // config layer translates the rejection, it does not schedule anything.
        // - external libraries — Jackson (config parsing), SLF4J (logging), java.* (NIO + collections)
        ArchRule rule = classes().that().resideInAPackage("at.aimon.core.config.hook..").should()
                .onlyDependOnClassesThat().resideInAnyPackage("at.aimon.core.config.hook..", PKG_CORE, PKG_AGENT_CORE,
                        "at.aimon.core.hook..", "at.aimon.core.skill.hook..", "at.aimon.core.scheduling.exception",
                        PKG_JAVA, PKG_SLF4J, PKG_JACKSON);

        rule.check(classes);
    }

    @Test
    @DisplayName("Core abstraction packages should not have cyclic dependencies")
    void noCorePackageCycles() {
        // Check cycles only between core abstraction packages
        // (excluding at.aimon.agents which is a specific implementation)
        ArchRule coreRule = slices().matching(BasePackage.class.getPackageName() + ".(*)..").should().beFreeOfCycles();

        ArchRule llmRule = slices().matching(LlmCorePackage.class.getPackageName() + ".(*)..").should()
                .beFreeOfCycles();

        ArchRule filesystemRule = slices().matching(FileSystemCorePackage.class.getPackageName() + ".(*)..").should()
                .beFreeOfCycles();

        // The agent.impl slice (formerly at.aimon.core.agents) is intentionally bidirectionally coupled with the rest
        // of agent.* — it implements agent SPIs while depending on agent value types. Run cycle detection against
        // a class set that excludes agent.impl, leaving the remaining agent.* slices to be checked for cycles.
        JavaClasses agentNonImplClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("/at/aimon/core/agent/impl/"))
                .importPackages("at.aimon");
        ArchRule agentCoreRule = slices().matching(AgentCorePackage.class.getPackageName() + ".(*)..").should()
                .beFreeOfCycles();

        coreRule.check(classes);
        llmRule.check(classes);
        filesystemRule.check(classes);
        agentCoreRule.check(agentNonImplClasses);
    }

    /**
     * The four rules above slice <em>inside</em> {@code base}, {@code llm}, {@code filesystem} and {@code agent}. None
     * of them looks <em>between</em> the twenty top-level packages of {@code at.aimon.core}, and that gap is where the
     * coupling actually is: measured on the current tree there are eleven mutually-dependent pairs once
     * {@code agent.impl} is set aside.
     *
     * <p>
     * So this is a baseline, not a prohibition — the same shape as the {@code maxWarnings} figure in
     * {@code modules/aimon-core/build.gradle.kts}, and for the same reason. Untangling eleven pairs means splitting
     * {@code agent}, which is a third rename of the scope model; {@code docs/project/roadmap.md} §3 lists "the scope
     * model survives a release cycle without a rename" as a {@code 1.0} entry condition, so that work is not merely
     * large, it is currently ruled out. What is affordable today is stopping the set from growing, and that is what
     * this asserts.
     *
     * <p>
     * {@code agent.impl} is excluded for a reason worth stating, because it is not "the implementation package is
     * boring". It is where the framework's own assembly happens — {@code OrcaAgentRuntimeFactory} reaches into
     * {@code scheduling}, {@code credential}, {@code knowledge}, {@code mcp} and {@code workflow} to build a runtime,
     * exactly as {@code aimon-bootstrap} does one layer out. An assembly root depends on everything it assembles; that
     * is its job, and holding it to a layering rule would only push the wiring somewhere less honest. Excluding it
     * drops seven of the eighteen pairs, and the exclusion is the same {@link ImportOption} the rule above already
     * uses.
     *
     * <p>
     * The baseline is exact in both directions. A pair that is not listed fails as a new cycle; a listed pair that no
     * longer exists fails too, asking to be deleted. The second half is what keeps a shrinking baseline from rotting
     * into a list nobody can tell is stale.
     */
    @Test
    @DisplayName("no new dependency cycle appears between the top-level core packages")
    void noNewTopLevelCorePackageCycles() {
        final JavaClasses nonAssemblyClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("/at/aimon/core/agent/impl/"))
                .importPackages(BasePackage.class.getPackageName().replace(".base", ""));

        final Set<String> actual = mutuallyDependentTopLevelPackages(nonAssemblyClasses);

        final Set<String> unexpected = new TreeSet<>(actual);
        unexpected.removeAll(BASELINE_TOP_LEVEL_CYCLES);
        assertThat(unexpected).withFailMessage("new dependency cycle(s) between top-level at.aimon.core packages: %s%n"
                + "Each entry is a pair that now depends on itself through the other. Break the cycle, or — if "
                + "it is deliberate — add it to BASELINE_TOP_LEVEL_CYCLES with a note saying why it is "
                + "acceptable. The baseline exists to stop the tangle growing, not to bless additions to it.%n"
                + "Full measured set: %s", unexpected, actual).isEmpty();

        final Set<String> stale = new TreeSet<>(BASELINE_TOP_LEVEL_CYCLES);
        stale.removeAll(actual);
        assertThat(stale).withFailMessage("BASELINE_TOP_LEVEL_CYCLES lists pair(s) that are no longer cyclic: %s%n"
                + "The cycle was broken — delete the entry so the baseline keeps saying something true. A "
                + "baseline nobody shrinks is a baseline nobody reads.", stale).isEmpty();
    }

    /**
     * Every unordered pair of top-level {@code at.aimon.core} packages that depend on each other, as
     * {@code "a <-> b"} with the two names sorted so a pair has exactly one spelling.
     *
     * <p>
     * Built from ArchUnit's resolved dependencies rather than from import statements, so it also counts the coupling
     * an import list does not show — a supertype, a field type, a method parameter reached through a fully qualified
     * name. Self-edges and classes outside {@code at.aimon.core} are ignored.
     */
    private static Set<String> mutuallyDependentTopLevelPackages(JavaClasses javaClasses) {
        final String rootPackage = BasePackage.class.getPackageName().replace(".base", "") + ".";
        final Set<String> edges = new HashSet<>();
        for (final JavaClass javaClass : javaClasses) {
            final String source = topLevelPackageOf(javaClass, rootPackage);
            if (source == null) {
                continue;
            }
            for (final Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                final String target = topLevelPackageOf(dependency.getTargetClass(), rootPackage);
                if (target != null && !target.equals(source)) {
                    edges.add(source + ">" + target);
                }
            }
        }
        final Set<String> pairs = new TreeSet<>();
        for (final String edge : edges) {
            final String[] ends = edge.split(">");
            if (edges.contains(ends[1] + ">" + ends[0])) {
                pairs.add(ends[0].compareTo(ends[1]) < 0 ? ends[0] + " <-> " + ends[1] : ends[1] + " <-> " + ends[0]);
            }
        }
        return pairs;
    }

    /** The segment right under {@code at.aimon.core}, or {@code null} for anything outside that tree. */
    private static String topLevelPackageOf(JavaClass javaClass, String rootPackage) {
        final String packageName = javaClass.getPackageName();
        if (!packageName.startsWith(rootPackage)) {
            return null;
        }
        final String remainder = packageName.substring(rootPackage.length());
        final int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }
}
