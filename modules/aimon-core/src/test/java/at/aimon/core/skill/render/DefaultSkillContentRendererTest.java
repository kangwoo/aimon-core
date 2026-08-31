package at.aimon.core.skill.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import at.aimon.core.base.Principal;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;

/** Unit tests for {@link DefaultSkillContentRenderer}. */
class DefaultSkillContentRendererTest {

    private final DefaultSkillContentRenderer renderer = new DefaultSkillContentRenderer();

    private static Skill skillWithBody(String body) {
        return Skill.builder().name("sample")
                .metadata(SkillMetadata.builder().name("sample").description("desc").build())
                .content(SkillContent.of(body)).build();
    }

    private static Skill skillWithBodyAndArgumentNames(String body, java.util.List<String> argumentNames) {
        return Skill.builder().name("sample")
                .metadata(
                        SkillMetadata.builder().name("sample").description("desc").argumentNames(argumentNames).build())
                .content(SkillContent.of(body)).build();
    }

    @Test
    void render_NoPlaceholder_EmptyArgs_BodyUnchanged() {
        final Skill skill = skillWithBody("Hello world");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Hello world");
    }

    @Test
    void render_NoPlaceholder_NonEmptyArgs_AppendsArgumentsTrailer() {
        final Skill skill = skillWithBody("Hello world");

        final String result = renderer.render(skill, "alpha beta", RenderContext.empty());

        assertThat(result).isEqualTo("Hello world\n\nARGUMENTS: alpha beta\n");
    }

    @Test
    void render_DollarArguments_ReplacedWithRawArgs() {
        final Skill skill = skillWithBody("Run with $ARGUMENTS now");

        final String result = renderer.render(skill, "alpha beta", RenderContext.empty());

        assertThat(result).isEqualTo("Run with alpha beta now");
    }

    @Test
    void render_DollarArguments_FollowedByIdentifierChar_NotReplaced() {
        final Skill skill = skillWithBody("Run with $ARGUMENTSX now");

        final String result = renderer.render(skill, "alpha", RenderContext.empty());

        assertThat(result).isEqualTo("Run with $ARGUMENTSX now\n\nARGUMENTS: alpha\n");
    }

    @Test
    void render_DollarZero_ReplacedWithRawArgs() {
        final Skill skill = skillWithBody("Run with $0 now");

        final String result = renderer.render(skill, "alpha beta", RenderContext.empty());

        assertThat(result).isEqualTo("Run with alpha beta now");
    }

    @Test
    void render_DollarOneAndTwo_ReplacedWithPositionalArgs() {
        final Skill skill = skillWithBody("First=$1 Second=$2");

        final String result = renderer.render(skill, "alpha beta", RenderContext.empty());

        assertThat(result).isEqualTo("First=alpha Second=beta");
    }

    @Test
    void render_PositionalReferenceMissingArg_ReplacedWithEmptyString() {
        final Skill skill = skillWithBody("First=$1 Second=$2 Third=$3");

        final String result = renderer.render(skill, "alpha", RenderContext.empty());

        assertThat(result).isEqualTo("First=alpha Second= Third=");
    }

    @Test
    void render_DollarTen_OutOfRange_ReplacedWithEmptyString() {
        // $10 is captured as a single positional reference (not split as $1 + 0).
        // With only 3 args available the 10th positional is missing, so it resolves to empty.
        final Skill skill = skillWithBody("Code $10 here");

        final String result = renderer.render(skill, "a b c", RenderContext.empty());

        assertThat(result).isEqualTo("Code  here");
    }

    @Test
    void render_DollarTen_OutOfRangeAlongsideValidPositional_StillEmpty() {
        final Skill skill = skillWithBody("First=$1 Tenth=$10");

        final String result = renderer.render(skill, "alpha beta", RenderContext.empty());

        assertThat(result).isEqualTo("First=alpha Tenth=");
    }

    @Test
    void render_DollarTen_WithEnoughPositional_ReplacedAsTenthArgument() {
        final Skill skill = skillWithBody("Tenth=$10");

        final String result = renderer.render(skill, "a b c d e f g h i j k", RenderContext.empty());

        assertThat(result).isEqualTo("Tenth=j");
    }

    @Test
    void render_DollarN_FollowedByNonDigit_StillMatchesDigitsRun() {
        // The non-digit lookahead ensures the entire run of digits is captured,
        // but a trailing letter is allowed — it does not extend the placeholder.
        final Skill skill = skillWithBody("X=$12 Y=$2x");

        final String result = renderer.render(skill, "a b c d e f g h i j k l m", RenderContext.empty());

        assertThat(result).isEqualTo("X=l Y=bx");
    }

    @Test
    void render_PositionalWithQuotedArgs_HonorsShellQuoting() {
        final Skill skill = skillWithBody("Title=$1 Body=$2");

        final String result = renderer.render(skill, "\"hello world\" body", RenderContext.empty());

        assertThat(result).isEqualTo("Title=hello world Body=body");
    }

    @Test
    void render_RegexSpecialCharsInArgs_AreNotInterpreted() {
        final Skill skill = skillWithBody("Echo $ARGUMENTS");

        final String result = renderer.render(skill, "$1 \\n $$ \\\\", RenderContext.empty());

        assertThat(result).isEqualTo("Echo $1 \\n $$ \\\\");
    }

    @Test
    void render_RegexSpecialCharsInPositional_AreNotInterpreted() {
        final Skill skill = skillWithBody("X=$1");

        final String result = renderer.render(skill, "$2", RenderContext.empty());

        assertThat(result).isEqualTo("X=$2");
    }

    @Test
    void render_PlaceholderPresent_DoesNotAppendArgumentsTrailer() {
        final Skill skill = skillWithBody("First=$1");

        final String result = renderer.render(skill, "alpha beta", RenderContext.empty());

        assertThat(result).isEqualTo("First=alpha");
    }

    @Test
    void render_DollarArgumentsPresent_DoesNotAppendArgumentsTrailer() {
        final Skill skill = skillWithBody("Body: $ARGUMENTS");

        final String result = renderer.render(skill, "alpha", RenderContext.empty());

        assertThat(result).isEqualTo("Body: alpha");
    }

    @Test
    void render_EmptyArgs_PlaceholdersResolveToEmptyString() {
        final Skill skill = skillWithBody("[$1]-[$ARGUMENTS]");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("[]-[]");
    }

    @Test
    void render_MultipleOccurrencesOfSamePlaceholder_AllReplaced() {
        final Skill skill = skillWithBody("$1 and $1 again, plus $ARGUMENTS twice: $ARGUMENTS");

        final String result = renderer.render(skill, "x y", RenderContext.empty());

        assertThat(result).isEqualTo("x and x again, plus x y twice: x y");
    }

    @Test
    void render_NullSkill_ThrowsNullPointerException() {
        assertThatThrownBy(() -> renderer.render(null, "", RenderContext.empty()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Skill");
    }

    @Test
    void render_NullArgs_ThrowsNullPointerException() {
        assertThatThrownBy(() -> renderer.render(skillWithBody("body"), null, RenderContext.empty()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Args");
    }

    @Test
    void render_NullContext_ThrowsNullPointerException() {
        assertThatThrownBy(() -> renderer.render(skillWithBody("body"), "", null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Context");
    }

    @Test
    void constructor_NullTokenizer_ThrowsNullPointerException() {
        assertThatThrownBy(() -> new DefaultSkillContentRenderer(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Tokenizer");
    }

    @Test
    void render_AimonSkillDir_SubstitutedFromContext() {
        final Skill skill = skillWithBody("Base=${AIMON_SKILL_DIR}");
        final RenderContext ctx = RenderContext.builder().skillBaseDir("/skills/example").build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("Base=/skills/example");
    }

    @Test
    void render_AimonSkillDir_MissingResolvesToEmptyString() {
        final Skill skill = skillWithBody("Base=${AIMON_SKILL_DIR}");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Base=");
    }

    @Test
    void render_AimonAgentRuntimeId_SubstitutedFromContext() {
        final Skill skill = skillWithBody("Runtime=${AIMON_AGENT_RUNTIME_ID}");
        final RenderContext ctx = RenderContext.builder().agentRuntimeId("agent:ops-bot").build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("Runtime=agent:ops-bot");
    }

    @Test
    void render_AimonAgentRuntimeId_MissingResolvesToEmptyString() {
        final Skill skill = skillWithBody("Runtime=${AIMON_AGENT_RUNTIME_ID}");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Runtime=");
    }

    @Test
    void render_AimonSessionId_SubstitutedFromContext() {
        final Skill skill = skillWithBody("Session=${AIMON_SESSION_ID}");
        final RenderContext ctx = RenderContext.builder().sessionId("sess-42").build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("Session=sess-42");
    }

    @Test
    void render_AimonSessionId_MissingResolvesToEmptyString() {
        final Skill skill = skillWithBody("Session=${AIMON_SESSION_ID}");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Session=");
    }

    /**
     * The two id variables address different lifetimes, so neither may fall back to the other. Before the alias was
     * withdrawn {@code ${AIMON_SESSION_ID}} resolved to the agent runtime id; this pins that it no longer does.
     */
    @Test
    void render_AimonSessionId_DoesNotFallBackToTheAgentRuntimeId() {
        final Skill skill = skillWithBody("Session=${AIMON_SESSION_ID} Runtime=${AIMON_AGENT_RUNTIME_ID}");
        final RenderContext ctx = RenderContext.builder().agentRuntimeId("agent:ops-bot").sessionId("sess-42").build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("Session=sess-42 Runtime=agent:ops-bot");
    }

    @Test
    void render_AimonAgentRuntimeId_DoesNotFallBackToTheSessionId() {
        final Skill skill = skillWithBody("Runtime=${AIMON_AGENT_RUNTIME_ID}");
        final RenderContext ctx = RenderContext.builder().sessionId("sess-42").build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("Runtime=");
    }

    @Test
    void render_AimonExecutionId_SubstitutedFromContext() {
        final Skill skill = skillWithBody("Run=${AIMON_EXECUTION_ID}");
        final RenderContext ctx = RenderContext.builder().executionId("subagent:reviewer:e1").build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("Run=subagent:reviewer:e1");
    }

    @Test
    void render_AimonExecutionId_MissingResolvesToEmptyString() {
        final Skill skill = skillWithBody("Run=${AIMON_EXECUTION_ID}");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Run=");
    }

    /**
     * The session and execution variables are an exclusive pair: a run either is a session's turn or it is not, and the
     * one it is not resolves to empty rather than borrowing the other's value. Collapsing them would leave a body
     * unable to ask the only question the session variable exists to answer — whether a user is on the other end.
     */
    @Test
    void render_AimonSessionId_DoesNotFallBackToTheExecutionId() {
        final Skill skill = skillWithBody("Session=${AIMON_SESSION_ID} Run=${AIMON_EXECUTION_ID}");
        final RenderContext fork = RenderContext.builder().executionId("subagent:reviewer:e1").build();

        final String result = renderer.render(skill, "", fork);

        assertThat(result).isEqualTo("Session= Run=subagent:reviewer:e1");
    }

    @Test
    void render_AimonExecutionId_DoesNotFallBackToTheSessionId() {
        final Skill skill = skillWithBody("Session=${AIMON_SESSION_ID} Run=${AIMON_EXECUTION_ID}");
        final RenderContext sessionTurn = RenderContext.builder().sessionId("sess-42").build();

        final String result = renderer.render(skill, "", sessionTurn);

        assertThat(result).isEqualTo("Session=sess-42 Run=");
    }

    @Test
    void render_AimonUser_SubstitutedFromContextDisplayName() {
        final Skill skill = skillWithBody("User=${AIMON_USER}");
        final RenderContext ctx = RenderContext.builder().principal(Principal.user("u1", "Alice")).build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("User=Alice");
    }

    @Test
    void render_UnknownVariable_LeftAsLiteralPlaceholder() {
        final Skill skill = skillWithBody("X=${UNKNOWN_VAR}");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("X=${UNKNOWN_VAR}");
    }

    @Test
    void render_AdditionalVariable_SubstitutedFromMap() {
        final Skill skill = skillWithBody("Hello $name");
        // $name is not a recognized positional pattern; only named maps via ${VAR} are honored.
        final RenderContext ctx = RenderContext.builder().additionalVariables(Map.of("name", "World")).build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("Hello $name");
    }

    @Test
    void render_BracedAdditionalVariable_Substituted() {
        final Skill skill = skillWithBody("Hello ${name}");
        final RenderContext ctx = RenderContext.builder().additionalVariables(Map.of("name", "World")).build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("Hello World");
    }

    @Test
    void render_AimonVarOverridesAdditionalEntry() {
        final Skill skill = skillWithBody("User=${AIMON_USER}");
        final RenderContext ctx = RenderContext.builder().principal(Principal.user("u1", "Alice"))
                .additionalVariables(Map.of("AIMON_USER", "Override")).build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("User=Alice");
    }

    @Test
    void render_VariableValueContainingDollarOne_NotReinterpreted() {
        final Skill skill = skillWithBody("First=${INJECT} Second=$1");
        final RenderContext ctx = RenderContext.builder().additionalVariables(Map.of("INJECT", "$1")).build();

        final String result = renderer.render(skill, "alpha", ctx);

        assertThat(result).isEqualTo("First=$1 Second=alpha");
    }

    @Test
    void render_VariablePresentButNoPositional_AppendsArgumentsTrailer() {
        final Skill skill = skillWithBody("Base=${AIMON_SKILL_DIR}");
        final RenderContext ctx = RenderContext.builder().skillBaseDir("/skills").build();

        final String result = renderer.render(skill, "alpha", ctx);

        assertThat(result).isEqualTo("Base=/skills\n\nARGUMENTS: alpha\n");
    }

    @Test
    void render_RegexSpecialCharsInVariableValue_AreNotInterpreted() {
        final Skill skill = skillWithBody("X=${INJECT}");
        final RenderContext ctx = RenderContext.builder().additionalVariables(Map.of("INJECT", "$0 \\n $$ \\\\"))
                .build();

        final String result = renderer.render(skill, "", ctx);

        assertThat(result).isEqualTo("X=$0 \\n $$ \\\\");
    }

    @Test
    void render_NamedArgument_SubstitutedFromPositional() {
        final Skill skill = skillWithBodyAndArgumentNames("Hello $name", java.util.List.of("name"));

        final String result = renderer.render(skill, "alice", RenderContext.empty());

        assertThat(result).isEqualTo("Hello alice");
    }

    @Test
    void render_MultipleNamedArguments_HonorOrder() {
        final Skill skill = skillWithBodyAndArgumentNames("To $title: $body", java.util.List.of("title", "body"));

        final String result = renderer.render(skill, "Friend \"hi there\"", RenderContext.empty());

        assertThat(result).isEqualTo("To Friend: hi there");
    }

    @Test
    void render_NamedArgumentMissingPositional_ResolvesToEmpty() {
        final Skill skill = skillWithBodyAndArgumentNames("[$first]-[$second]", java.util.List.of("first", "second"));

        final String result = renderer.render(skill, "only", RenderContext.empty());

        assertThat(result).isEqualTo("[only]-[]");
    }

    @Test
    void render_NamedArgumentNotInDeclaredList_LeftIntact() {
        final Skill skill = skillWithBodyAndArgumentNames("Hello $other", java.util.List.of("name"));

        final String result = renderer.render(skill, "alice", RenderContext.empty());

        assertThat(result).isEqualTo("Hello $other\n\nARGUMENTS: alice\n");
    }

    @Test
    void render_NamedArgumentPresent_DoesNotAppendArgumentsTrailer() {
        final Skill skill = skillWithBodyAndArgumentNames("Hello $name", java.util.List.of("name"));

        final String result = renderer.render(skill, "alice extra", RenderContext.empty());

        assertThat(result).isEqualTo("Hello alice");
    }

    @Test
    void render_NamedArgumentValueWithRegexSpecialChars_NotReinterpreted() {
        final Skill skill = skillWithBodyAndArgumentNames("X=$value", java.util.List.of("value"));

        final String result = renderer.render(skill, "$1", RenderContext.empty());

        assertThat(result).isEqualTo("X=$1");
    }

    @Test
    void render_ArgCount_ReplacedWithPositionalCount() {
        final Skill skill = skillWithBody("Got $ARG_COUNT args");

        final String result = renderer.render(skill, "alpha beta gamma", RenderContext.empty());

        assertThat(result).isEqualTo("Got 3 args");
    }

    @Test
    void render_ArgCount_EmptyArgs_ReplacedWithZero() {
        final Skill skill = skillWithBody("Count=$ARG_COUNT");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Count=0");
    }

    @Test
    void render_ArgCount_FollowedByIdentifierChar_NotReplaced() {
        final Skill skill = skillWithBody("Field=$ARG_COUNTS");

        final String result = renderer.render(skill, "a b", RenderContext.empty());

        assertThat(result).isEqualTo("Field=$ARG_COUNTS\n\nARGUMENTS: a b\n");
    }

    @Test
    void render_ArgCount_HonorsShellQuoting() {
        final Skill skill = skillWithBody("Count=$ARG_COUNT");

        final String result = renderer.render(skill, "\"hello world\" extra", RenderContext.empty());

        assertThat(result).isEqualTo("Count=2");
    }

    @Test
    void render_ArgCountPresent_DoesNotAppendArgumentsTrailer() {
        final Skill skill = skillWithBody("Count=$ARG_COUNT");

        final String result = renderer.render(skill, "alpha", RenderContext.empty());

        assertThat(result).isEqualTo("Count=1");
    }

    @Test
    void render_BashToken_FormattedAsBashToolCall() {
        final Skill skill = skillWithBody("Run !`git status` first");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Run Bash(git status) first");
    }

    @Test
    void render_BashToken_DoesNotSuppressArgumentsTrailer() {
        final Skill skill = skillWithBody("Run !`git status` first");

        final String result = renderer.render(skill, "alpha", RenderContext.empty());

        assertThat(result).isEqualTo("Run Bash(git status) first\n\nARGUMENTS: alpha\n");
    }

    @Test
    void render_FileToken_AtLineStart_FormattedAsReadToolCall() {
        final Skill skill = skillWithBody("@README.md");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Read(README.md)");
    }

    @Test
    void render_FileToken_AfterWhitespace_FormattedAsReadToolCall() {
        final Skill skill = skillWithBody("Read this: @docs/guide.md please");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Read this: Read(docs/guide.md) please");
    }

    @Test
    void render_FileToken_NotPrecededByWhitespace_LeftIntact() {
        // Email-like patterns must not be misinterpreted as file references.
        final Skill skill = skillWithBody("Contact alice@example.com today");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Contact alice@example.com today");
    }

    @Test
    void render_BashAndFileTokens_BothFormatted() {
        final Skill skill = skillWithBody("Look at @docs/x.md after !`build`");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Look at Read(docs/x.md) after Bash(build)");
    }

    @Test
    void render_BashTokenWithRegexSpecialChars_NotInterpreted() {
        final Skill skill = skillWithBody("!`echo $1`");

        final String result = renderer.render(skill, "", RenderContext.empty());

        assertThat(result).isEqualTo("Bash(echo $1)");
    }

    @Test
    void render_DollarArgumentsTakesPrecedenceOverNamedArguments() {
        final Skill skill = skillWithBodyAndArgumentNames("$ARGUMENTS", java.util.List.of("ARGUMENTS"));

        final String result = renderer.render(skill, "alpha beta", RenderContext.empty());

        assertThat(result).isEqualTo("alpha beta");
    }

    @Test
    void render_NamedArgumentCoexistsWithPositionalAndBraced() {
        final Skill skill = skillWithBodyAndArgumentNames("${AIMON_USER}: $name says $1", java.util.List.of("name"));
        final RenderContext ctx = RenderContext.builder().principal(at.aimon.core.base.Principal.user("u1", "Alice"))
                .build();

        final String result = renderer.render(skill, "Bob hi", ctx);

        assertThat(result).isEqualTo("Alice: Bob says Bob");
    }
}
