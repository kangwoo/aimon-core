/**
 * Validates a tool call's arguments against the tool's declared input schema before the tool runs.
 *
 * <p>
 * A tool declares a JSON Schema so the model knows how to call it, and until this package existed nothing ever
 * compared a call against that declaration. Each tool re-checked what it cared about inside {@code execute()}, in its
 * own words, and skipped the rest. {@link at.aimon.core.agent.tool.schema.ToolInputSchemaValidator} moves the four
 * checks that are the same for every tool — presence, type, {@code enum}, unknown name — to one gate in
 * {@code DefaultToolExecutor}, where they also cover tools we did not write.
 *
 * <p>
 * Entry points: {@link at.aimon.core.agent.tool.schema.ToolInputSchemaValidator} (the SPI),
 * {@link at.aimon.core.agent.tool.schema.DefaultToolInputSchemaValidator} (what it checks and, more importantly, what
 * it deliberately does not), {@link at.aimon.core.agent.tool.schema.SchemaValidationMode} (how a violation is acted
 * on), {@link at.aimon.core.agent.tool.schema.ViolationMessages} (the sentences the model reads).
 *
 * <h2>Why this is a second validator</h2>
 *
 * <p>
 * {@code at.aimon.core.workflow.impl.StructuredOutputSupport} already contains a small schema checker, and every
 * reader of this package eventually asks why it was not reused. Four reasons, of which the first settles it:
 *
 * <ol>
 * <li><b>Reusing it would fail the build.</b> {@code workflowImplMustNotLeakOutsideWorkflowTree}
 * ({@code PackageDependencyArchitectureTest.java:239-247}) forbids any class outside {@code at.aimon.core.workflow}
 * from depending on {@code at.aimon.core.workflow.impl..}. A reference from this package fails that rule
 * immediately — this is not a preference that can be argued with.
 * <li><b>It is not reachable anyway.</b> The class is package-private ({@code StructuredOutputSupport.java:25}), its
 * constructor is private ({@code :31}), and its validation methods are all {@code private static}.
 * <li><b>It cannot say what this gate has to say.</b> Its {@code validate} returns a single {@code boolean}, so
 * there is no list of violations to hand back — no per-violation sentence, no "did you mean". It has no
 * {@code additionalProperties} branch either, which is exactly the check the built-in schemas now opt into.
 * <li><b>Since TCH-04, the two read {@code null} in opposite directions.</b> Dropping null values from
 * {@code ToolInput} made a JSON {@code null} mean <em>absent</em> on this path, so a required parameter sent as
 * {@code null} is reported as missing. That checker treats a {@code null} as present-but-wrongly-typed. Both are
 * right for their own direction — this one validates input a model sends us, that one validates output a model
 * produced — which is why unifying them would need the policy parameterised rather than picked.
 * </ol>
 *
 * <p>
 * One fact points the other way and is recorded so nobody has to rediscover it: that checker has <b>no production
 * callers at all</b> — only itself and its own test reference it. Promoting it would not have been "reusing proven
 * code"; it would have been putting never-executed code on the tool-call path. And mechanically the extraction is
 * possible, since its core touches no structured-output types; what blocks it is the package boundary in reason 1.
 * If sharing ever becomes worth it, the work is a separate refactor moving that class out of {@code workflow.impl},
 * not a change here.
 *
 * @see at.aimon.core.agent.tool.execution.DefaultToolExecutor
 * @see at.aimon.core.base.NullSafeMaps
 */
package at.aimon.core.agent.tool.schema;
