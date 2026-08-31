package at.aimon.spring.boot.autoconfigure;

import java.util.List;

import org.springframework.aot.hint.BindingReflectionHintsRegistrar;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import at.aimon.core.tools.todo.Todo;
import at.aimon.core.tools.todo.TodoStatus;

/**
 * Declares the reflection and resource access that a GraalVM native image cannot see by static analysis.
 *
 * <p>
 * Three things in AIMON are reached without a compile-time reference, and every one of them fails <em>quietly</em>
 * in a native image — the agent starts, and then has no skills, or no scheduler, or an empty todo list. Hints are
 * the only way to say so up front.
 *
 * <p>
 * <b>Why the starter and not the application.</b> A consuming application cannot write these hints: it does not
 * know that a Quartz job is instantiated by name, or which class the todo tool binds JSON into. Those are AIMON's
 * internals, so the declaration has to ship with AIMON and be in place <em>before</em> the application's native
 * build runs. That is why this class is wired from {@link AimonAutoConfiguration} — the one entry point that is
 * active whenever the starter is on the class path at all.
 *
 * <p>
 * <b>1. Bundle resources.</b> Agent definitions, subagents and skills are read through
 * {@link ClassLoader#getResourceAsStream(String)} under a base path the starter fixes at {@code agents} (see
 * {@code StackPaths.AGENT_BUNDLE_BASE_PATH}). The fixed shapes are {@code agents/<bundle>/agent.md},
 * {@code agents/<bundle>/agents/index}, {@code agents/<bundle>/agents/<name>.md},
 * {@code agents/<bundle>/skills/index} and {@code agents/<bundle>/skills/<name>/SKILL.md} — but a skill directory
 * also carries payload files whose names no constant knows, because {@code BundledSkillMaterializer} copies
 * whatever is there. A hint listing only the five shapes would therefore be provably incomplete, so the whole
 * subtree is registered instead. In Spring's pattern syntax {@code *} becomes {@code .*} and crosses directory
 * separators (see {@code ResourcePatternHint#toRegex()}), which is also the exact regex written into
 * {@code resource-config.json}, so one pattern covers the tree.
 *
 * <p>
 * <b>What this does not fix.</b> Resource <em>enumeration</em> is not hintable at all: to materialize a skill's
 * payload, {@code ClasspathResourceTreeWalker} has to list a directory, and it does that by switching on the URL
 * protocol — {@code file:} or {@code jar:}. A native image serves resources under neither, so the walker reports
 * {@code unsupported} and logs a warning. That is the honest state of native support: the loader will find every
 * file it asks for by name, and will say out loud that it cannot enumerate the ones it does not know the names of.
 *
 * <p>
 * <b>2. Todo binding.</b> {@code TodoWriteTool} deserializes the model's argument into {@code List<Todo>} with
 * Jackson, which reaches {@link Todo}'s {@code @JsonCreator} constructor and {@link TodoStatus}'s
 * {@code @JsonCreator} / {@code @JsonValue} pair reflectively. Registration is delegated to Spring's own
 * {@link BindingReflectionHintsRegistrar} rather than to a hand-picked list of member categories, so the member
 * set stays whatever Spring considers necessary to bind a type. Both types are passed explicitly; the registrar
 * would also reach {@code TodoStatus} through {@code Todo.getStatus()}, but relying on that would make this
 * declaration depend on Spring's traversal rather than on our own intent.
 *
 * <p>
 * <b>3. Quartz jobs.</b> Quartz instantiates a job class by name at fire time
 * ({@code JobBuilder.newJob(X.class)} stores the class, {@code JobFactory} calls its no-arg constructor), so the
 * three job types AIMON registers are never constructed in code. They are declared by binary name through
 * {@code registerTypeIfPresent} because {@code aimon-scheduling-quartz} is an optional dependency of the starter:
 * on a class path without it, the names resolve to nothing and no hint is written.
 */
final class AimonRuntimeHints implements RuntimeHintsRegistrar {

    /**
     * The one pattern that covers every class path resource an agent bundle is read from.
     *
     * @see #registerHints(RuntimeHints, ClassLoader)
     */
    static final String AGENT_BUNDLE_RESOURCE_PATTERN = "agents/*";

    /** Binary names of the job types Quartz instantiates reflectively; absent unless the scheduler module is. */
    static final List<String> QUARTZ_JOB_CLASS_NAMES = List.of(
            "at.aimon.scheduling.quartz.QuartzTaskScheduler$DelegatingJob",
            "at.aimon.scheduling.quartz.rewake.QuartzRewakeService$RewakeJob",
            "at.aimon.scheduling.quartz.dreamer.DreamerJob");

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern(AGENT_BUNDLE_RESOURCE_PATTERN);

        new BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), Todo.class, TodoStatus.class);

        for (String jobClassName : QUARTZ_JOB_CLASS_NAMES) {
            hints.reflection().registerTypeIfPresent(classLoader, jobClassName,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }
}
