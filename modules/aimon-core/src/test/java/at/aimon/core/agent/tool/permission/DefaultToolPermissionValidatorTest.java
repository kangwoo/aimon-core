package at.aimon.core.agent.tool.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.Tool;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.exception.ToolPermissionViolationException;

@DisplayName("DefaultToolPermissionValidator Tests")
class DefaultToolPermissionValidatorTest {

    /** Stands in for BashTool: judged on its {@code command} as a COMMAND subject. */
    private final Tool bashTool = new CommandSubjectTool("Bash");

    /** Stands in for ReadTool: judged on its {@code file_path} as a PATH subject. */
    private final Tool readTool = new PathSubjectTool("Read");

    /** Offers nothing — the shape of most tools, judged on its name alone. */
    private final Tool plainTool = new PlainTool("Grep");

    private ToolPermissionValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DefaultToolPermissionValidator();
    }

    private static ToolInput input(String key, Object value) {
        return ToolInput.of(Map.of(key, value));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Name-level decisions
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Should allow all tools when no restrictions")
    void shouldAllowAllToolsWithoutRestrictions() {
        List<AllowedTool> allowed = List.of();

        assertThat(validator.validate(readTool, ToolInput.of(), ToolContext.empty(), allowed).isAllowed()).isTrue();
        assertThat(validator.validate(bashTool, input("command", "rm -rf /"), ToolContext.empty(), allowed).isAllowed())
                .isTrue();
        assertThat(validator.validateByName("Anything", allowed).isAllowed()).isTrue();
        assertThat(validator.hasRestrictions(allowed)).isFalse();
    }

    @Test
    @DisplayName("Should allow tools in allowed list")
    void shouldAllowToolInList() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read"), AllowedTool.parse("Bash(git:*)"));

        assertThat(validator.validate(readTool, ToolInput.of(), ToolContext.empty(), allowed).isAllowed()).isTrue();
    }

    @Test
    @DisplayName("Should reject tools not in allowed list")
    void shouldRejectToolNotInList() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read"));

        assertThat(validator.validate(plainTool, ToolInput.of(), ToolContext.empty(), allowed).isAllowed()).isFalse();
        assertThat(validator.validateByName("Write", allowed).isAllowed()).isFalse();
        assertThat(validator.hasRestrictions(allowed)).isTrue();
    }

    @Test
    @DisplayName("Should allow tools without pattern when name matches")
    void shouldAllowToolWithoutPattern() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read"), AllowedTool.parse("Grep"));

        assertThat(validator.validate(readTool, input("file_path", "/etc/passwd"), ToolContext.empty(), allowed)
                .isAllowed()).isTrue();
        assertThat(validator.validate(plainTool, input("pattern", "test"), ToolContext.empty(), allowed).isAllowed())
                .isTrue();
    }

    /**
     * The blanket grant needs <b>every</b> entry for the name to be unqualified. Listing a narrower entry alongside a
     * bare one does not leave the bare one standing as an escape hatch — the call still has to clear a pattern.
     */
    @Test
    @DisplayName("Should not widen a patterned name by also listing it bare")
    void shouldNotWidenAPatternedNameWithABareOne() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash"), AllowedTool.parse("Bash(git:*)"));

        assertThat(validator.validate(bashTool, input("command", "rm -rf /"), ToolContext.empty(), allowed).isAllowed())
                .isFalse();
        assertThat(
                validator.validate(bashTool, input("command", "git status"), ToolContext.empty(), allowed).isAllowed())
                .isTrue();
    }

    // ------------------------------------------------------------------------------------------------------------
    // COMMAND subjects
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Should validate command patterns with wildcard")
    void shouldValidateCommandWildcardPatterns() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git add:*)"));

        assertThat(
                validator.validate(bashTool, input("command", "git add ."), ToolContext.empty(), allowed).isAllowed())
                .isTrue();
        assertThat(validator.validate(bashTool, input("command", "git add src/"), ToolContext.empty(), allowed)
                .isAllowed()).isTrue();
        assertThat(
                validator.validate(bashTool, input("command", "git commit"), ToolContext.empty(), allowed).isAllowed())
                .isFalse();
    }

    @Test
    @DisplayName("Should validate command patterns with exact match")
    void shouldValidateCommandExactPatterns() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(npm install)"));

        assertThat(
                validator.validate(bashTool, input("command", "npm install"), ToolContext.empty(), allowed).isAllowed())
                .isTrue();
        assertThat(validator.validate(bashTool, input("command", "npm install --save"), ToolContext.empty(), allowed)
                .isAllowed()).isFalse();
    }

    @Test
    @DisplayName("Should validate against multiple command patterns")
    void shouldValidateMultipleCommandPatterns() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git add:*)"),
                AllowedTool.parse("Bash(git commit:*)"), AllowedTool.parse("Bash(git status)"));

        assertThat(
                validator.validate(bashTool, input("command", "git add ."), ToolContext.empty(), allowed).isAllowed())
                .isTrue();
        assertThat(validator.validate(bashTool, input("command", "git commit -m 'msg'"), ToolContext.empty(), allowed)
                .isAllowed()).isTrue();
        assertThat(
                validator.validate(bashTool, input("command", "git status"), ToolContext.empty(), allowed).isAllowed())
                .isTrue();
        assertThat(validator.validate(bashTool, input("command", "git push"), ToolContext.empty(), allowed).isAllowed())
                .isFalse();
    }

    @Test
    @DisplayName("Should deny when the subject cannot be derived")
    void shouldDenyWhenSubjectIsAbsent() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git:*)"));

        // No command at all, and a command of the wrong type: neither can be judged, so both deny rather than throw
        assertThat(validator.validate(bashTool, ToolInput.of(), ToolContext.empty(), allowed).isAllowed()).isFalse();
        assertThat(validator.validate(bashTool, input("command", 123), ToolContext.empty(), allowed).isAllowed())
                .isFalse();
    }

    // ------------------------------------------------------------------------------------------------------------
    // PATH subjects — a different matcher, chosen by the subject's kind
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Should match a PATH subject as a glob, not as a command prefix")
    void shouldMatchPathSubjectAsGlob() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read(/tmp/**)"));

        assertThat(validator.validate(readTool, input("file_path", "/tmp/a.txt"), ToolContext.empty(), allowed)
                .isAllowed()).isTrue();
        assertThat(
                validator.validate(readTool, input("file_path", "/tmp/deep/nested/a.txt"), ToolContext.empty(), allowed)
                        .isAllowed())
                .isTrue();
        assertThat(validator.validate(readTool, input("file_path", "/etc/passwd"), ToolContext.empty(), allowed)
                .isAllowed()).isFalse();
    }

    @Test
    @DisplayName("Should not cross a separator for a single-star path pattern")
    void shouldNotCrossSeparatorForSingleStar() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read(/tmp/*.log)"));

        assertThat(validator.validate(readTool, input("file_path", "/tmp/app.log"), ToolContext.empty(), allowed)
                .isAllowed()).isTrue();
        assertThat(validator.validate(readTool, input("file_path", "/tmp/sub/app.log"), ToolContext.empty(), allowed)
                .isAllowed()).isFalse();
    }

    /**
     * The shell-metacharacter refusal in {@link ToolPattern} is an injection defence for strings headed for a shell. A
     * path never reaches one, so keeping the refusal there would put ordinary filenames out of reach.
     */
    @Test
    @DisplayName("Should not refuse shell metacharacters in a path")
    void shouldNotRefuseShellMetacharactersInPath() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read(/tmp/**)"));

        assertThat(validator.validate(readTool, input("file_path", "/tmp/report (1).txt"), ToolContext.empty(), allowed)
                .isAllowed()).isTrue();
        assertThat(validator.validate(readTool, input("file_path", "/tmp/a&b.txt"), ToolContext.empty(), allowed)
                .isAllowed()).isTrue();
        assertThat(validator.validate(readTool, input("file_path", "/tmp/cost$.txt"), ToolContext.empty(), allowed)
                .isAllowed()).isTrue();
    }

    /**
     * The same characters must still be refused for a COMMAND subject — that is the case the guard exists for.
     */
    @Test
    @DisplayName("Should still refuse shell metacharacters in a command")
    void shouldStillRefuseShellMetacharactersInCommand() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git:*)"));

        assertThat(validator.validate(bashTool, input("command", "git status; rm -rf /"), ToolContext.empty(), allowed)
                .isAllowed()).isFalse();
    }

    @Test
    @DisplayName("Should judge a path pattern only against a PATH subject")
    void shouldNotJudgeCommandSubjectWithPathPattern() {
        // Same spec shape, two tools: the kind comes from the tool, never from how the spec is spelled
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(/tmp/**)"));

        assertThat(validator.validate(bashTool, input("command", "/tmp/script.sh"), ToolContext.empty(), allowed)
                .isAllowed()).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------------
    // The closed fail-open
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A pattern is configured, the tool offers no subject, and it carries no rule — nothing present can interpret the
     * pattern. This used to fall through to allow, which made {@code Grep(/tmp/**)} weaker than a bare {@code Grep}.
     */
    @Test
    @DisplayName("Should deny when a pattern is configured but nothing can interpret it")
    void shouldDenyWhenPatternCannotBeInterpreted() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Grep(/tmp/**)"));

        assertThat(validator.validate(plainTool, input("pattern", "secret"), ToolContext.empty(), allowed).isAllowed())
                .isFalse();
    }

    @Test
    @DisplayName("Should deny a listed name with a pattern when validating by name alone")
    void shouldDenyNameOnlyValidationWithPattern() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git:*)"));

        // No instance means no subject and no rule, so the pattern cannot be judged
        assertThat(validator.validateByName("Bash", allowed).isAllowed()).isFalse();
        assertThat(validator.validateByName("Bash", List.of(AllowedTool.parse("Bash"))).isAllowed()).isTrue();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Custom rules
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Should delegate to a custom rule when the tool offers no subject")
    void shouldDelegateToCustomRule() {
        Tool ruleTool = new RuleTool("Browser");
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Browser(open:*)"));

        assertThat(validator.validate(ruleTool, input("action", "open"), ToolContext.empty(), allowed).isAllowed())
                .isTrue();
        assertThat(validator.validate(ruleTool, input("action", "click"), ToolContext.empty(), allowed).isAllowed())
                .isFalse();
    }

    @Test
    @DisplayName("Should prefer the subject over the rule when a tool offers both")
    void shouldPreferSubjectOverRule() {
        // The rule would allow anything; the subject says otherwise, and the subject is consulted first
        Tool both = new SubjectAndRuleTool("Bash");
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git:*)"));

        assertThat(validator.validate(both, input("command", "rm -rf /"), ToolContext.empty(), allowed).isAllowed())
                .isFalse();
    }

    @Test
    @DisplayName("Should fall back to the rule when the subject is empty")
    void shouldFallBackToRuleWhenSubjectIsEmpty() {
        Tool both = new SubjectAndRuleTool("Bash");
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git:*)"));

        // No command, so no subject — the permissive rule decides
        assertThat(validator.validate(both, ToolInput.of(), ToolContext.empty(), allowed).isAllowed()).isTrue();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Error messages and exceptions
    // ------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Should throw ToolPermissionViolationException when validation fails")
    void shouldThrowExceptionOnValidationFailure() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read"));

        assertThatThrownBy(() -> validator.validateOrThrow(plainTool, ToolInput.of(), ToolContext.empty(), allowed))
                .isInstanceOf(ToolPermissionViolationException.class).hasMessageContaining("Tool 'Grep' not allowed")
                .hasMessageContaining("Allowed tools: Read");
    }

    @Test
    @DisplayName("Should describe the offending subject in the error message")
    void shouldDescribeSubjectInErrorMessage() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git:*)"));

        assertThatThrownBy(
                () -> validator.validateOrThrow(bashTool, input("command", "rm -rf /"), ToolContext.empty(), allowed))
                .isInstanceOf(ToolPermissionViolationException.class).hasMessageContaining("command: rm -rf /")
                .hasMessageContaining("Allowed tools: Bash(git:*)");
    }

    @Test
    @DisplayName("Should describe the offending path in the error message")
    void shouldDescribePathInErrorMessage() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read(/tmp/**)"));

        assertThatThrownBy(() -> validator.validateOrThrow(readTool, input("file_path", "/etc/passwd"),
                ToolContext.empty(), allowed)).isInstanceOf(ToolPermissionViolationException.class)
                .hasMessageContaining("path: /etc/passwd");
    }

    @Test
    @DisplayName("Should not throw when validation succeeds")
    void shouldNotThrowExceptionOnSuccess() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read"));

        assertThatCode(
                () -> validator.validateOrThrow(readTool, input("file_path", "/f"), ToolContext.empty(), allowed))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Should format allowed tools correctly in error messages")
    void shouldFormatAllowedToolsInErrors() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git:*)"), AllowedTool.parse("Read"),
                AllowedTool.parse("Grep"));

        assertThatThrownBy(() -> validator.validateByNameOrThrow("Edit", allowed)).hasMessageContaining("Bash(git:*)")
                .hasMessageContaining("Read").hasMessageContaining("Grep");
    }

    @Test
    @DisplayName("Should carry tool name and input in ToolPermissionViolationException")
    void shouldExtractToolInfoInException() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Read"));

        try {
            validator.validateOrThrow(bashTool, input("command", "rm -rf /"), ToolContext.empty(), allowed);
            throw new AssertionError("Expected ToolPermissionViolationException");
        } catch (ToolPermissionViolationException e) {
            assertThat(e.getToolName()).contains("Bash");
            assertThat(e.getToolInput()).isPresent();
            assertThat(e.getToolInput().get()).containsEntry("command", "rm -rf /");
        }
    }

    @Test
    @DisplayName("Should handle a complex mixed policy")
    void shouldHandleComplexScenarios() {
        List<AllowedTool> allowed = List.of(AllowedTool.parse("Bash(git add:*)"),
                AllowedTool.parse("Bash(git commit:*)"), AllowedTool.parse("Read(/tmp/**)"), AllowedTool.parse("Grep"));

        assertThat(validator.validate(bashTool, input("command", "git add file.txt"), ToolContext.empty(), allowed)
                .isAllowed()).isTrue();
        assertThat(validator.validate(readTool, input("file_path", "/tmp/x"), ToolContext.empty(), allowed).isAllowed())
                .isTrue();
        assertThat(validator.validate(plainTool, ToolInput.of(), ToolContext.empty(), allowed).isAllowed()).isTrue();

        assertThat(validator.validate(bashTool, input("command", "git push"), ToolContext.empty(), allowed).isAllowed())
                .isFalse();
        assertThat(validator.validate(readTool, input("file_path", "/etc/shadow"), ToolContext.empty(), allowed)
                .isAllowed()).isFalse();
        assertThat(validator.validateByName("Write", allowed).isAllowed()).isFalse();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Test doubles
    // ------------------------------------------------------------------------------------------------------------

    /** A tool that does nothing and offers no permission capability. */
    private static class PlainTool extends AbstractTool {

        PlainTool(String name) {
            super(name, name + " test double", Map.of("type", "object"));
        }

        @Override
        public ToolResult execute(ToolInput input, ToolContext context) {
            return ToolResult.success("ok");
        }
    }

    /** Judged on its {@code command}, the way {@code BashTool} is. */
    private static final class CommandSubjectTool extends PlainTool implements ToolPermissionSubjectAware {

        CommandSubjectTool(String name) {
            super(name);
        }

        @Override
        public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
            return input.get("command") instanceof String command
                    ? Optional.of(PermissionSubject.command(command))
                    : Optional.empty();
        }
    }

    /** Judged on its {@code file_path}, the way the file tools are. Paths are given absolute by these tests. */
    private static final class PathSubjectTool extends PlainTool implements ToolPermissionSubjectAware {

        PathSubjectTool(String name) {
            super(name);
        }

        @Override
        public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
            return input.get("file_path") instanceof String path
                    ? Optional.of(PermissionSubject.path(path))
                    : Optional.empty();
        }
    }

    /** Carries a rule instead of a subject, the way {@code BrowserTool} does. */
    private static final class RuleTool extends PlainTool implements CustomToolPermissionAware {

        RuleTool(String name) {
            super(name);
        }

        @Override
        public CustomToolPermissionRule getCustomPermissionRule() {
            return new CustomToolPermissionRule() {
                @Override
                public boolean isAllowed(ToolInput input, ToolContext context, List<AllowedTool> allowedTools) {
                    if (!(input.get("action") instanceof String action)) {
                        return false;
                    }
                    return allowedTools.stream().filter(AllowedTool::hasPattern)
                            .anyMatch(at -> at.getPattern().orElseThrow().matches(action));
                }
            };
        }
    }

    /** Offers both capabilities, with a rule that allows everything — so it is visible which one was consulted. */
    private static final class SubjectAndRuleTool extends PlainTool
            implements
                ToolPermissionSubjectAware,
                CustomToolPermissionAware {

        SubjectAndRuleTool(String name) {
            super(name);
        }

        @Override
        public Optional<PermissionSubject> permissionSubject(ToolInput input, ToolContext context) {
            return input.get("command") instanceof String command
                    ? Optional.of(PermissionSubject.command(command))
                    : Optional.empty();
        }

        @Override
        public CustomToolPermissionRule getCustomPermissionRule() {
            return (input, context, allowedTools) -> true;
        }
    }
}
