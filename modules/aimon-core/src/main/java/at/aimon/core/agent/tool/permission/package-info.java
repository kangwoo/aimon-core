/**
 * Tool permission validation — deciding whether a tool call the model asked for is allowed to run.
 *
 * <p>
 * A permission policy is a list of {@link at.aimon.core.agent.tool.permission.AllowedTool} specs, parsed from strings
 * like {@code "Read"}, {@code "Bash(git:*)"} or {@code "Read(/tmp/**)"}. A validator judges one call against that list
 * and answers allowed or denied. An empty list means unrestricted.
 *
 * <h2>Package Overview</h2>
 *
 * <ul>
 * <li><b>Validator</b> — {@link at.aimon.core.agent.tool.permission.ToolPermissionValidator} and its default
 * implementation {@link at.aimon.core.agent.tool.permission.DefaultToolPermissionValidator}
 * <li><b>Subject</b> — {@link at.aimon.core.agent.tool.permission.PermissionSubject}, the one value a call is judged
 * on, offered by tools through {@link at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware}
 * <li><b>Custom rule</b> — {@link at.aimon.core.agent.tool.permission.CustomToolPermissionRule}, offered through
 * {@link at.aimon.core.agent.tool.permission.CustomToolPermissionAware}, for the calls a single subject cannot express
 * <li><b>Patterns</b> — {@link at.aimon.core.agent.tool.permission.ToolPattern} for commands,
 * {@link at.aimon.core.agent.tool.permission.PathPattern} for paths
 * <li><b>Result</b> — {@link at.aimon.core.agent.tool.permission.PermissionValidationResult}
 * </ul>
 *
 * <h2>How a call is judged</h2>
 *
 * <p>
 * {@link at.aimon.core.agent.tool.permission.DefaultToolPermissionValidator} decides in this order, and the first
 * matching line wins:
 *
 * <ol>
 * <li>The allowed list is empty — <b>allow</b>. No policy is configured.
 * <li>The tool's name appears nowhere in the list — <b>deny</b>.
 * <li><b>No</b> entry for this name carries a pattern (e.g. plain {@code "Read"}) — <b>allow</b>. An unqualified name
 * is a blanket grant.
 * <li>Some entry for this name carries a pattern, and the tool offers a
 * {@link at.aimon.core.agent.tool.permission.PermissionSubject} — match the subject against the patterns, choosing the
 * matcher by the subject's {@link at.aimon.core.agent.tool.permission.PermissionSubject.Kind}.
 * <li>No subject, but the tool carries a {@link at.aimon.core.agent.tool.permission.CustomToolPermissionRule} — the
 * rule decides.
 * <li>Neither — <b>deny</b>. A pattern was configured and nothing present can interpret it, so the call cannot be
 * judged.
 * </ol>
 *
 * <p>
 * Line 3 is stricter than it looks: the blanket grant needs <b>every</b> entry for the name to be unqualified. Listing
 * {@code "Bash"} and {@code "Bash(git:*)"} together does not widen the first back to everything — the call still has to
 * clear a pattern. A bare name grants everything only when it is the only entry for that name.
 *
 * <p>
 * The last line used to permit. That made {@code Read(/tmp/**)} weaker than {@code Read}, because the tool offered
 * nothing to match the pattern with and the check fell through to allow — the stricter-looking configuration was the
 * weaker one. It now denies: an unjudgeable call is refused, not waved through.
 *
 * <h2>Two matchers, chosen by kind</h2>
 *
 * <p>
 * A subject carries its {@link at.aimon.core.agent.tool.permission.PermissionSubject.Kind} because one matcher cannot
 * serve both grammars:
 *
 * <table border="1">
 * <caption>Pattern grammar per subject kind</caption>
 * <tr>
 * <th>Kind</th>
 * <th>Spec looks like</th>
 * <th>Matcher</th>
 * </tr>
 * <tr>
 * <td>{@code COMMAND}</td>
 * <td>{@code Bash(git:*)}, {@code Bash(npm install)}</td>
 * <td>{@link at.aimon.core.agent.tool.permission.ToolPattern} — {@code :*} suffix wildcard, and shell metacharacters
 * in the candidate are refused outright</td>
 * </tr>
 * <tr>
 * <td>{@code PATH}</td>
 * <td>{@code Read(/tmp/**)}, {@code Read(/etc/passwd)}</td>
 * <td>{@link at.aimon.core.agent.tool.permission.PathPattern} — a filesystem glob over the whole path, with no
 * metacharacter refusal</td>
 * </tr>
 * </table>
 *
 * <p>
 * The metacharacter refusal is a shell-injection defence and belongs only where a shell is involved. A path never
 * reaches one, and refusing {@code (} or {@code &} there would make ordinary filenames unreachable.
 *
 * <p>
 * The kind is chosen by the tool that offers the subject, never guessed from how the spec is spelled: a spec's kind is
 * not knowable at parse time, since {@code Bash(git:*)} and {@code Read(/tmp/**)} are the same shape to
 * {@link at.aimon.core.agent.tool.permission.AllowedTool#parse(java.lang.String)}.
 *
 * <h2>Which tools offer what</h2>
 *
 * <table border="1">
 * <caption>Permission capability by tool</caption>
 * <tr>
 * <th>Tool</th>
 * <th>Capability</th>
 * <th>Judged on</th>
 * </tr>
 * <tr>
 * <td>{@code Bash}</td>
 * <td>{@link at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware}</td>
 * <td>{@code COMMAND} — the command string as written</td>
 * </tr>
 * <tr>
 * <td>{@code Read} / {@code Edit} / {@code Write}</td>
 * <td>{@link at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware}</td>
 * <td>{@code PATH} — {@code file_path}, made absolute and lexically normalized</td>
 * </tr>
 * <tr>
 * <td>{@code Browser}</td>
 * <td>{@link at.aimon.core.agent.tool.permission.CustomToolPermissionAware}</td>
 * <td>{@code action:url} — its own grammar, which no framework kind describes</td>
 * </tr>
 * <tr>
 * <td>everything else</td>
 * <td>neither</td>
 * <td>name only — a pattern on such a tool denies</td>
 * </tr>
 * </table>
 *
 * <h2>This is not the declarative-hook predicate grammar</h2>
 *
 * <p>
 * {@link at.aimon.core.skill.hook.declarative.predicate.PredicateParser} parses expressions that look similar —
 * {@code Read(**&#47;*.java)}, {@code Bash(git *)} — and it too splits tools into a {@code Bash} branch and a path
 * branch. It is a <b>different grammar for a different purpose</b>: it decides which hooks fire, not which calls are
 * permitted. Do not carry a spec from one side to the other.
 *
 * <ul>
 * <li>Its path-tool set is wider ({@code Read}, {@code Edit}, {@code Write}, {@code MultiEdit}, {@code Glob},
 * {@code Grep}, {@code LS}, {@code NotebookEdit}) than the set of tools that offer a {@code PATH} subject here.
 * <li>It rejects an unknown tool name with an argument pattern; this package accepts the spec at parse time and denies
 * at judgement time.
 * <li>Its {@code Bash} branch matches sub-commands, not the {@code :*} prefix grammar of
 * {@link at.aimon.core.agent.tool.permission.ToolPattern}.
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Example 1 — name-only policy</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     ToolPermissionValidator validator = new DefaultToolPermissionValidator();
 *     List<AllowedTool> allowed = List.of(AllowedTool.parse("Read"), AllowedTool.parse("Grep"));
 *
 *     PermissionValidationResult result = validator.validate(readTool, input, context, allowed);
 *     if (result.isAllowed()) {
 *         // execute
 *     } else {
 *         log.warn("Permission denied: {}", result.getErrorMessage());
 *     }
 * }
 * </pre>
 *
 * <h3>Example 2 — command patterns</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git:*)"));
 *
 *     // Allowed: BashTool offers a COMMAND subject, and "git add ." matches the prefix
 *     validator.validateOrThrow(bashTool, ToolInput.of("command", "git add ."), context, allowed);
 *
 *     // Denied: throws ToolPermissionViolationException
 *     validator.validateOrThrow(bashTool, ToolInput.of("command", "rm -rf /"), context, allowed);
 * }
 * </pre>
 *
 * <h3>Example 3 — path patterns</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     List<AllowedTool> allowed = List.of(AllowedTool.parse("Read(/tmp/**)"));
 *
 *     // Allowed
 *     validator.validateOrThrow(readTool, ToolInput.of("file_path", "/tmp/a/b.txt"), context, allowed);
 *
 *     // Denied — normalized to /secrets before matching, so the /tmp prefix does not save it
 *     validator.validateOrThrow(readTool, ToolInput.of("file_path", "/tmp/../secrets"), context, allowed);
 * }
 * </pre>
 *
 * <h3>Example 4 — a tool with its own grammar</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     // Reach for this only when one value cannot express the decision. Browser judges an action and a URL
 *     // together, which is neither a command nor a path.
 *     public class BrowserToolPermissionRule implements CustomToolPermissionRule {
 *
 *         &#64;Override
 *         public boolean isAllowed(ToolInput input, ToolContext context, List<AllowedTool> allowedTools) {
 *             // Only pattern-carrying entries arrive here; a bare name was already allowed by the validator.
 *             String target = input.get("action") + ":" + input.get("url");
 *             return allowedTools.stream().filter(AllowedTool::hasPattern)
 *                     .anyMatch(at -> at.getPattern().orElseThrow().matches(target));
 *         }
 *     }
 * }
 * </pre>
 *
 * <h3>Example 5 — result-based validation, no exceptions</h3>
 *
 * <pre>
 * {
 *     &#64;code
 *     for (ToolUse toolUse : toolUses) {
 *         Tool tool = registry.findByName(toolUse.getName()).orElse(null);
 *         PermissionValidationResult validation = (tool == null)
 *                 ? validator.validateByName(toolUse.getName(), allowed)
 *                 : validator.validate(tool, ToolInput.of(toolUse.getInput()), context, allowed);
 *
 *         if (validation.isDenied()) {
 *             results.add(ToolExecutionResult.error(toolUse.getId(), validation.getErrorMessage()));
 *             continue;
 *         }
 *         results.add(executeTool(toolUse));
 *     }
 * }
 * </pre>
 *
 * <h2>What this package does not cover</h2>
 *
 * <p>
 * It judges calls that go through {@link at.aimon.core.agent.tool.ToolExecutionManager}. Other paths carry their own
 * checks: {@link at.aimon.core.skill.permission.SkillPermissionManager} matches a skill's declared allowed-tools
 * against a command string, with no {@link at.aimon.core.agent.tool.Tool} instance in hand, and therefore no subject.
 *
 * <p>
 * Nothing here touches the filesystem — a {@code PATH} subject is normalized lexically, without resolving symlinks
 * (the check runs before the tool does, and has no handle to resolve them with). Permission patterns narrow what the
 * agent may ask for; containment is the sandbox's job.
 *
 * <h2>Design Principles</h2>
 *
 * <ul>
 * <li><b>SRP:</b> validators judge, patterns match, tools describe what they are about to do
 * <li><b>OCP:</b> a new tool becomes judgeable by implementing a capability interface, not by editing the validator
 * <li><b>LSP:</b> implementations honour the contracts above, including "empty subject means unjudgeable"
 * <li><b>ISP:</b> {@link at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware} and
 * {@link at.aimon.core.agent.tool.permission.CustomToolPermissionAware} stay off the
 * {@link at.aimon.core.agent.tool.Tool} interface, so tools needing neither carry neither
 * <li><b>DIP:</b> the execution manager depends on
 * {@link at.aimon.core.agent.tool.permission.ToolPermissionValidator}, not on a concrete validator
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>
 * Every class here is stateless or immutable, and safe to share across threads. Validation has no side effects — which
 * matters, because a batch of tool calls may be judged and executed concurrently.
 *
 * <h2>Exception Handling</h2>
 *
 * <ul>
 * <li>{@link at.aimon.core.agent.tool.exception.ToolPermissionViolationException} — thrown by the
 * {@code validateOrThrow} entry points when a call is refused
 * <li>{@link at.aimon.core.agent.tool.exception.InvalidToolSpecException} — thrown by
 * {@link at.aimon.core.agent.tool.permission.AllowedTool#parse(java.lang.String)} for a malformed spec
 * <li>{@link at.aimon.core.agent.tool.permission.PermissionValidationResult} — the non-throwing alternative, for
 * judging a batch without stopping at the first refusal
 * </ul>
 *
 * @see at.aimon.core.agent.tool.permission.ToolPermissionValidator
 * @see at.aimon.core.agent.tool.permission.PermissionSubject
 * @see at.aimon.core.agent.tool.permission.ToolPermissionSubjectAware
 * @see at.aimon.core.agent.tool.permission.CustomToolPermissionAware
 * @see at.aimon.core.agent.tool.permission.CustomToolPermissionRule
 * @see at.aimon.core.agent.tool.permission.AllowedTool
 * @see at.aimon.core.agent.tool.permission.ToolPattern
 * @see at.aimon.core.agent.tool.permission.PathPattern
 * @see at.aimon.core.agent.tool.permission.PermissionValidationResult
 */
package at.aimon.core.agent.tool.permission;
