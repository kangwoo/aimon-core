package at.aimon.core.skill.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.exception.SkillParseException;
import at.aimon.core.skill.hook.SkillHookSet;

/** Unit tests for {@link MarkdownSkillParser}, focused on the {@code arguments} extension. */
class MarkdownSkillParserTest {

    private final MarkdownSkillParser parser = new MarkdownSkillParser();

    private static String skillSource(String extraFrontmatter) {
        return "---\n" + "name: sample\n" + "description: A test skill\n" + extraFrontmatter + "---\n" + "\n" + "Body";
    }

    @Test
    void parse_NoArgumentsField_ResultsInEmptyArgumentNames() {
        final Skill skill = parser.parse("sample", skillSource(""));

        assertThat(skill.getMetadata().getArgumentNames()).isEmpty();
    }

    @Test
    void parse_ArgumentsAsString_TokenizedIntoNames() {
        final Skill skill = parser.parse("sample", skillSource("arguments: message title\n"));

        assertThat(skill.getMetadata().getArgumentNames()).containsExactly("message", "title");
    }

    @Test
    void parse_ArgumentsAsYamlQuotedString_TokenizedAfterYamlUnquoting() {
        // YAML strips its own surrounding double quotes; the resulting string is then shell-tokenized.
        final Skill skill = parser.parse("sample", skillSource("arguments: \"first second\"\n"));

        assertThat(skill.getMetadata().getArgumentNames()).containsExactly("first", "second");
    }

    @Test
    void parse_ArgumentsWithEmbeddedShellQuoting_HonorsTokenizer() {
        // Use YAML single-quoted scalar so the inner double quotes survive into the shell tokenizer.
        final Skill skill = parser.parse("sample", skillSource("arguments: '\"first second\" third'\n"));

        assertThat(skill.getMetadata().getArgumentNames()).containsExactly("first second", "third");
    }

    @Test
    void parse_ArgumentsAsList_PreservesOrder() {
        final Skill skill = parser.parse("sample", skillSource("arguments:\n  - message\n  - title\n  - body\n"));

        assertThat(skill.getMetadata().getArgumentNames()).containsExactly("message", "title", "body");
    }

    @Test
    void parse_ArgumentsAsEmptyString_ResultsInEmpty() {
        final Skill skill = parser.parse("sample", skillSource("arguments: \"\"\n"));

        assertThat(skill.getMetadata().getArgumentNames()).isEmpty();
    }

    @Test
    void parse_DuplicateNames_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("arguments: foo bar foo\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("Duplicate argument name")
                .hasMessageContaining("foo");
    }

    @Test
    void parse_NonStringListElement_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("arguments:\n  - message\n  - 42\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("arguments");
    }

    @Test
    void parse_ArgumentsAsMap_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("arguments:\n  key: value\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("arguments");
    }

    @Test
    void parse_OtherFieldsStillParsedCorrectly() {
        final Skill skill = parser.parse("sample",
                skillSource("license: MIT\nallowed-tools: Read Grep\narguments: name\n"));

        assertThat(skill.getMetadata().getArgumentNames()).containsExactly("name");
        assertThat(skill.getMetadata().getLicense()).isEqualTo("MIT");
        assertThat(skill.getMetadata().getAllowedTools()).hasSize(2);
    }

    @Test
    void parse_InvokeMissing_DefaultsApplied() {
        final Skill skill = parser.parse("sample", skillSource(""));

        assertThat(skill.getMetadata().getInvokePolicy()).isEqualTo(InvokePolicy.defaults());
    }

    @Test
    void parse_InvokeFullyDeclared_FlagsHonored() {
        final Skill skill = parser.parse("sample", skillSource("invoke:\n  user: true\n  model: false\n"));

        assertThat(skill.getMetadata().getInvokePolicy()).isEqualTo(InvokePolicy.of(true, false));
    }

    @Test
    void parse_InvokePartial_MissingKeyKeepsDefault() {
        final Skill skill = parser.parse("sample", skillSource("invoke:\n  user: true\n"));

        // user overridden, model still defaults to true
        assertThat(skill.getMetadata().getInvokePolicy()).isEqualTo(InvokePolicy.of(true, true));
    }

    @Test
    void parse_InvokePartial_OnlyModelOverridden() {
        final Skill skill = parser.parse("sample", skillSource("invoke:\n  model: false\n"));

        // model overridden, user still defaults to false
        assertThat(skill.getMetadata().getInvokePolicy()).isEqualTo(InvokePolicy.of(false, false));
    }

    @Test
    void parse_InvokeAsScalar_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("invoke: yes\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("invoke");
    }

    @Test
    void parse_InvokeUserNotBoolean_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("invoke:\n  user: \"yes\"\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("invoke.user")
                .hasMessageContaining("boolean");
    }

    @Test
    void parse_InvokeModelNotBoolean_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("invoke:\n  model: 1\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("invoke.model")
                .hasMessageContaining("boolean");
    }

    @Test
    void parse_InvokeUnknownKey_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("invoke:\n  admin: true\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("invoke").hasMessageContaining("admin");
    }

    @Test
    void parse_MaxIterationsMissing_DefaultsApplied() {
        final Skill skill = parser.parse("sample", skillSource(""));

        assertThat(skill.getMetadata().getMaxIterations()).isEqualTo(SkillMetadata.DEFAULT_MAX_ITERATIONS);
    }

    @Test
    void parse_MaxIterationsCustom_HonoredAsInteger() {
        final Skill skill = parser.parse("sample", skillSource("max-iterations: 42\n"));

        assertThat(skill.getMetadata().getMaxIterations()).isEqualTo(42);
    }

    @Test
    void parse_MaxIterationsZero_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("max-iterations: 0\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("max-iterations")
                .hasMessageContaining("positive");
    }

    @Test
    void parse_MaxIterationsNegative_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("max-iterations: -5\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("max-iterations")
                .hasMessageContaining("positive");
    }

    @Test
    void parse_MaxIterationsAsString_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("max-iterations: \"100\"\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("max-iterations");
    }

    @Test
    void parse_MaxIterationsAsBoolean_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("max-iterations: true\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("max-iterations");
    }

    @Test
    void parse_ExecutionMissing_DefaultsToInline() {
        final Skill skill = parser.parse("sample", skillSource(""));

        assertThat(skill.getMetadata().getExecutionMode()).isEqualTo(ExecutionMode.INLINE);
        assertThat(skill.getMetadata().getForkAgentName()).isNull();
    }

    @Test
    void parse_ExecutionInlineExplicit_AcceptedWithNoAgent() {
        final Skill skill = parser.parse("sample", skillSource("execution:\n  mode: inline\n"));

        assertThat(skill.getMetadata().getExecutionMode()).isEqualTo(ExecutionMode.INLINE);
        assertThat(skill.getMetadata().getForkAgentName()).isNull();
    }

    @Test
    void parse_ExecutionFork_RetainsAgent() {
        final Skill skill = parser.parse("sample", skillSource("execution:\n  mode: fork\n  agent: code-reviewer\n"));

        assertThat(skill.getMetadata().getExecutionMode()).isEqualTo(ExecutionMode.FORK);
        assertThat(skill.getMetadata().getForkAgentName()).isEqualTo("code-reviewer");
    }

    @Test
    void parse_ExecutionForkWithoutAgent_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("execution:\n  mode: fork\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("execution.agent")
                .hasMessageContaining("fork");
    }

    @Test
    void parse_ExecutionInlineWithAgent_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("execution:\n  mode: inline\n  agent: foo\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("execution.agent")
                .hasMessageContaining("inline");
    }

    @Test
    void parse_ExecutionAsScalar_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("execution: fork\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("execution");
    }

    @Test
    void parse_ExecutionUnknownMode_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("execution:\n  mode: hybrid\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("hybrid");
    }

    @Test
    void parse_ExecutionUnknownKey_ThrowsSkillParseException() {
        assertThatThrownBy(
                () -> parser.parse("sample", skillSource("execution:\n  mode: fork\n  agent: a\n  extra: nope\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("execution")
                .hasMessageContaining("extra");
    }

    @Test
    void parse_ExecutionAgentNotString_ThrowsSkillParseException() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("execution:\n  mode: fork\n  agent: 42\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("execution.agent")
                .hasMessageContaining("string");
    }

    @Test
    void parse_ExecutionModeUppercase_NormalizedToFork() {
        final Skill skill = parser.parse("sample", skillSource("execution:\n  mode: FORK\n  agent: rev\n"));

        assertThat(skill.getMetadata().getExecutionMode()).isEqualTo(ExecutionMode.FORK);
    }

    @Test
    void parse_HooksMissing_DefaultsToEmptyHookSet() {
        final Skill skill = parser.parse("sample", skillSource(""));

        final SkillHookSet hooks = skill.getMetadata().getHooks();
        assertThat(hooks).isNotNull();
        assertThat(hooks.isEmpty()).isTrue();
    }

    @Test
    void parse_HooksDenyOnPreTool_WiredIntoHookSet() {
        final String body = "hooks:\n" + "  preTool:\n" + "    - matcher: \"Bash\"\n"
                + "      action: { type: deny, reason: \"not allowed\" }\n";

        final Skill skill = parser.parse("sample", skillSource(body));

        final SkillHookSet hooks = skill.getMetadata().getHooks();
        assertThat(hooks.getPreToolHooks()).hasSize(1);
        assertThat(hooks.getPostToolHooks()).isEmpty();
        assertThat(hooks.getOnStartHooks()).isEmpty();
        assertThat(hooks.getOnStopHooks()).isEmpty();
    }

    @Test
    void parse_HooksDenyOnPostTool_ThrowsSkillParseException() {
        final String body = "hooks:\n" + "  postTool:\n" + "    - action: { type: deny, reason: \"x\" }\n";

        assertThatThrownBy(() -> parser.parse("sample", skillSource(body))).isInstanceOf(SkillParseException.class)
                .hasMessageContaining("deny").hasMessageContaining("preTool");
    }

    @Test
    void parse_HooksShellWithoutSupport_ThrowsSkillParseException() {
        // Default MarkdownSkillParser wires NoOpShellActionExecutor — shell must be rejected.
        final String body = "hooks:\n" + "  preTool:\n" + "    - action: { type: shell, command: \"echo\" }\n";

        assertThatThrownBy(() -> parser.parse("sample", skillSource(body))).isInstanceOf(SkillParseException.class)
                .hasMessageContaining("shell").hasMessageContaining("not supported");
    }

    @Test
    void parse_HooksUnknownEvent_ThrowsSkillParseException() {
        final String body = "hooks:\n" + "  preWeird:\n" + "    - action: { type: deny, reason: \"x\" }\n";

        assertThatThrownBy(() -> parser.parse("sample", skillSource(body))).isInstanceOf(SkillParseException.class)
                .hasMessageContaining("preWeird");
    }

    @Test
    void parse_HooksMatcherDefaultsToAnyOnPreTool() {
        // No 'matcher' field — should still build one preTool hook (matcher defaults to "*").
        final String body = "hooks:\n" + "  preTool:\n" + "    - action: { type: deny, reason: \"all blocked\" }\n";

        final Skill skill = parser.parse("sample", skillSource(body));

        assertThat(skill.getMetadata().getHooks().getPreToolHooks()).hasSize(1);
    }

    // --- B-26: a frontmatter key written without a value ------------------------------------------------------
    // Each of these used to surface as SkillParseException("Unexpected error during parsing") wrapping a bare NPE,
    // naming neither the field nor the reason. The contract is that an empty value reads as an absent one.

    @Test
    void parse_EmptyOptionalField_ReadsAsAbsent() {
        final Skill skill = parser.parse("sample", skillSource("license:\n"));

        assertThat(skill.getMetadata().getLicense()).isNull();
    }

    @Test
    void parse_EmptyRequiredField_NamesTheField() {
        final String source = "---\n" + "name: sample\n" + "description:\n" + "---\n" + "\n" + "Body";

        assertThatThrownBy(() -> parser.parse("sample", source)).isInstanceOf(SkillParseException.class)
                .hasMessageContaining("description").hasMessageNotContaining("Unexpected error");
    }

    @Test
    void parse_EmptyStructuralFields_FallBackToTheirDefaults() {
        final Skill skill = parser.parse("sample",
                skillSource("arguments:\n" + "hooks:\n" + "max-iterations:\n" + "allowed-tools:\n" + "metadata:\n"));

        assertThat(skill.getMetadata().getArgumentNames()).isEmpty();
        assertThat(skill.getMetadata().getHooks().getPreToolHooks()).isEmpty();
        // Same as omitting the key: SkillMetadata resolves a null max-iterations to its default.
        assertThat(skill.getMetadata().getMaxIterations()).isEqualTo(SkillMetadata.DEFAULT_MAX_ITERATIONS);
        assertThat(skill.getMetadata().getMetadata()).isEmpty();
    }

    @Test
    void parse_EmptyNestedMetadataValue_NamesTheKey() {
        // Distinct from the cases above: this null lives inside a nested map, so it reached the parser and killed it
        // while building its own error message. Fixing the top-level copy alone would not have covered it.
        assertThatThrownBy(() -> parser.parse("sample", skillSource("metadata:\n  author:\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("author")
                .hasMessageNotContaining("Unexpected error");
    }

    @Test
    void parse_EmptyNestedInvokeAndExecutionValues_NameTheField() {
        assertThatThrownBy(() -> parser.parse("sample", skillSource("invoke:\n  user:\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("invoke.user")
                .hasMessageNotContaining("Unexpected error");

        assertThatThrownBy(() -> parser.parse("sample", skillSource("execution:\n  mode:\n")))
                .isInstanceOf(SkillParseException.class).hasMessageContaining("execution.mode")
                .hasMessageNotContaining("Unexpected error");
    }

    // --- B-28: a SkillParseException raised inside parse() must not be rewrapped ---------------------------------
    // SkillParseException is not an IllegalArgumentException, so it fell through to the blanket catch and every one
    // of these — the three commonest authoring mistakes — reached the author as "Unexpected error during parsing".

    @Test
    void parse_MissingRequiredField_NamesTheField() {
        final String source = "---\n" + "name: sample\n" + "---\n" + "\n" + "Body";

        assertThatThrownBy(() -> parser.parse("sample", source)).isInstanceOf(SkillParseException.class)
                .hasMessageContaining("Missing required field").hasMessageContaining("description")
                .hasMessageNotContaining("Unexpected error");
    }

    @Test
    void parse_NameMismatch_ReportsBothNames() {
        final String source = "---\n" + "name: other\n" + "description: A test skill\n" + "---\n" + "\n" + "Body";

        assertThatThrownBy(() -> parser.parse("sample", source)).isInstanceOf(SkillParseException.class)
                .hasMessageContaining("mismatch").hasMessageContaining("sample").hasMessageContaining("other")
                .hasMessageNotContaining("Unexpected error");
    }

    @Test
    void parse_RequiredFieldOfWrongType_NamesTheTypeItGot() {
        final String source = "---\n" + "name: sample\n" + "description: 42\n" + "---\n" + "\n" + "Body";

        assertThatThrownBy(() -> parser.parse("sample", source)).isInstanceOf(SkillParseException.class)
                .hasMessageContaining("description").hasMessageContaining("Integer")
                .hasMessageNotContaining("Unexpected error");
    }
}
