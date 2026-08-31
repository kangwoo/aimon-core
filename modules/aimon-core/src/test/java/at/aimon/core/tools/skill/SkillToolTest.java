package at.aimon.core.tools.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import at.aimon.core.agent.AgentRuntimeIds;
import at.aimon.core.agent.ExecutionId;
import at.aimon.core.agent.session.SessionId;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.AllowedTool;
import at.aimon.core.base.Principal;
import at.aimon.core.skill.ExecutionMode;
import at.aimon.core.skill.InvokePolicy;
import at.aimon.core.skill.Skill;
import at.aimon.core.skill.SkillContent;
import at.aimon.core.skill.SkillMetadata;
import at.aimon.core.skill.SkillRegistry;
import at.aimon.core.skill.fork.NoOpSkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkExecutor;
import at.aimon.core.skill.fork.SkillForkOutcome;
import at.aimon.core.skill.hook.SkillHookActivator;
import at.aimon.core.skill.hook.SkillHookScope;
import at.aimon.core.skill.render.DefaultSkillContentRenderer;
import at.aimon.core.skill.render.NoOpSkillContentRenderer;
import at.aimon.core.skill.render.RenderContext;
import at.aimon.core.skill.render.SkillContentRenderer;
import at.aimon.core.tools.ToolContextKeys;

/** Unit tests for SkillTool. */
class SkillToolTest {

    private MockSkillRegistry mockRegistry;
    private SkillTool skillTool;
    private ToolContext emptyContext;

    @BeforeEach
    void setUp() {
        mockRegistry = new MockSkillRegistry();
        skillTool = new SkillTool(mockRegistry);
        emptyContext = ToolContext.empty();
    }

    @Test
    void testConstructor_NullRegistry_ThrowsException() {
        assertThatThrownBy(() -> new SkillTool(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Skill registry cannot be null");
    }

    @Test
    void testConstructor_TwoArg_NullRegistry_ThrowsException() {
        assertThatThrownBy(() -> new SkillTool(null, new NoOpSkillContentRenderer()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Skill registry cannot be null");
    }

    @Test
    void testConstructor_TwoArg_NullRenderer_ThrowsException() {
        assertThatThrownBy(() -> new SkillTool(mockRegistry, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Renderer cannot be null");
    }

    @Test
    void testConstructor_ThreeArg_NullForkExecutor_ThrowsException() {
        assertThatThrownBy(() -> new SkillTool(mockRegistry, new NoOpSkillContentRenderer(), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Fork executor cannot be null");
    }

    @Test
    void testConstructor_ThreeArg_NullRegistry_ThrowsException() {
        assertThatThrownBy(() -> new SkillTool(null, new NoOpSkillContentRenderer(), new NoOpSkillForkExecutor()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Skill registry cannot be null");
    }

    @Test
    void testConstructor_ThreeArg_NullRenderer_ThrowsException() {
        assertThatThrownBy(() -> new SkillTool(mockRegistry, null, new NoOpSkillForkExecutor()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Renderer cannot be null");
    }

    @Test
    void testExecute_BothConstructors_ProduceIdenticalOutput() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        SkillTool singleArgTool = new SkillTool(mockRegistry);
        SkillTool twoArgTool = new SkillTool(mockRegistry, new NoOpSkillContentRenderer());

        Map<String, Object> input = Map.of("skill", "alert-analysis");

        // Act
        ToolResult singleResult = singleArgTool.execute(ToolInput.of(input), emptyContext);
        ToolResult twoArgResult = twoArgTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(singleResult.isError()).isFalse();
        assertThat(twoArgResult.isError()).isFalse();
        assertThat(twoArgResult.getContent()).isEqualTo(singleResult.getContent());
    }

    @Test
    void testExecute_CustomRenderer_OutputContainsRenderedString() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        SkillContentRenderer fakeRenderer = (s, a, c) -> "<<RENDERED>>";
        SkillTool tool = new SkillTool(mockRegistry, fakeRenderer);

        Map<String, Object> input = Map.of("skill", "alert-analysis");

        // Act
        ToolResult result = tool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Instructions:").contains("<<RENDERED>>")
                .doesNotContain("Test system prompt for alert-analysis");
    }

    @Test
    void testExecute_RendererThrows_ReturnsError() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        SkillContentRenderer failingRenderer = (s, a, c) -> {
            throw new IllegalStateException("boom");
        };
        SkillTool tool = new SkillTool(mockRegistry, failingRenderer);

        Map<String, Object> input = Map.of("skill", "alert-analysis");

        // Act
        ToolResult result = tool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Failed to render skill").contains("boom");
    }

    @Test
    void testExecute_CustomRendererReceivesEmptyArgsAndContext() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        final String[] capturedArgs = new String[1];
        final RenderContext[] capturedContext = new RenderContext[1];
        SkillContentRenderer capturingRenderer = (s, a, c) -> {
            capturedArgs[0] = a;
            capturedContext[0] = c;
            return s.getContent().getInstructions();
        };
        SkillTool tool = new SkillTool(mockRegistry, capturingRenderer);

        // Act
        tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), emptyContext);

        // Assert
        assertThat(capturedArgs[0]).isEqualTo("");
        assertThat(capturedContext[0]).isEqualTo(RenderContext.empty());
    }

    @Test
    void testExecute_RenderContext_PopulatedFromToolContext() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        final RenderContext[] capturedContext = new RenderContext[1];
        SkillContentRenderer capturingRenderer = (s, a, c) -> {
            capturedContext[0] = c;
            return s.getContent().getInstructions();
        };
        SkillTool tool = new SkillTool(mockRegistry, capturingRenderer);

        ToolContext ctx = ToolContext.builder()
                .put(ToolContextKeys.AGENT_RUNTIME_ID, AgentRuntimeIds.testCtx("ops-bot"))
                .put(ToolContextKeys.PRINCIPAL, Principal.user("u1", "Alice")).build();

        // Act
        tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), ctx);

        // Assert
        assertThat(capturedContext[0].getAgentRuntimeId()).contains("agent:ops-bot");
        assertThat(capturedContext[0].getPrincipal()).map(Principal::getDisplayName).contains("Alice");
        assertThat(capturedContext[0].getSkillBaseDir()).isEmpty();
    }

    /**
     * A session's turn fills the session variable and leaves the execution variable empty. The two ids are copied
     * straight across rather than merged, so a body can still tell "a user is on the other end" from "this is some
     * run".
     */
    @Test
    void testExecute_RenderContext_SessionTurnCarriesOnlyTheSessionId() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        final RenderContext[] capturedContext = new RenderContext[1];
        SkillContentRenderer capturingRenderer = (s, a, c) -> {
            capturedContext[0] = c;
            return s.getContent().getInstructions();
        };
        SkillTool tool = new SkillTool(mockRegistry, capturingRenderer);

        SessionId session = SessionId.generate();
        ToolContext ctx = ToolContext.builder().put(ToolContextKeys.SESSION_ID, session).build();

        // Act
        tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), ctx);

        // Assert
        assertThat(capturedContext[0].getSessionId()).contains(session.value());
        assertThat(capturedContext[0].getExecutionId()).isEmpty();
    }

    /**
     * The mirror image, and the shape 6-4 made possible: a skill invoked from inside a subagent fork has no session of
     * its own, so it renders an execution id and nothing for the session. Before 6-4 the fork published a minted
     * session id here, which a skill body could not tell apart from the user's. The invoking session id present in the
     * context must not fill the gap either: it names whose decisions apply, not whose turn this is.
     */
    @Test
    void testExecute_RenderContext_ForkCarriesOnlyTheExecutionId() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        final RenderContext[] capturedContext = new RenderContext[1];
        SkillContentRenderer capturingRenderer = (s, a, c) -> {
            capturedContext[0] = c;
            return s.getContent().getInstructions();
        };
        SkillTool tool = new SkillTool(mockRegistry, capturingRenderer);

        ExecutionId execution = ExecutionId.generate("subagent:reviewer");
        ToolContext ctx = ToolContext.builder().put(ToolContextKeys.EXECUTION_ID, execution)
                .put(ToolContextKeys.INVOKING_SESSION_ID, SessionId.generate()).build();

        // Act
        tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), ctx);

        // Assert
        assertThat(capturedContext[0].getExecutionId()).contains(execution.value());
        assertThat(capturedContext[0].getSessionId()).isEmpty();
    }

    @Test
    void testExecute_RenderContext_SkillBaseDirDerivedFromRootFiles() {
        // Arrange
        Skill skill = Skill.builder().name("alert-analysis")
                .metadata(SkillMetadata.builder().name("alert-analysis").description("Analyzes alerts").build())
                .content(SkillContent.of("Body")).putRootFile("SKILL.md", "/skills/alert-analysis/SKILL.md").build();
        mockRegistry.addSkill(skill);

        final RenderContext[] capturedContext = new RenderContext[1];
        SkillContentRenderer capturingRenderer = (s, a, c) -> {
            capturedContext[0] = c;
            return s.getContent().getInstructions();
        };
        SkillTool tool = new SkillTool(mockRegistry, capturingRenderer);

        // Act
        tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), emptyContext);

        // Assert
        assertThat(capturedContext[0].getSkillBaseDir()).contains("/skills/alert-analysis");
    }

    @Test
    void testExecute_RenderContext_SkillBaseDirDerivedFromScriptsWhenNoRootFiles() {
        // Arrange
        Skill skill = Skill.builder().name("alert-analysis")
                .metadata(SkillMetadata.builder().name("alert-analysis").description("Analyzes alerts").build())
                .content(SkillContent.of("Body")).putScript("run.py", "/skills/alert-analysis/scripts/run.py").build();
        mockRegistry.addSkill(skill);

        final RenderContext[] capturedContext = new RenderContext[1];
        SkillContentRenderer capturingRenderer = (s, a, c) -> {
            capturedContext[0] = c;
            return s.getContent().getInstructions();
        };
        SkillTool tool = new SkillTool(mockRegistry, capturingRenderer);

        // Act
        tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), emptyContext);

        // Assert
        assertThat(capturedContext[0].getSkillBaseDir()).contains("/skills/alert-analysis/scripts");
    }

    @Test
    void testExecute_MissingSkillParameter_ReturnsError() {
        // Arrange
        Map<String, Object> input = Map.of();

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid parameter: Missing required parameter: skill");
    }

    @Test
    void testExecute_InvalidSkillNameFormat_ReturnsError() {
        // Arrange - skill name with invalid characters
        Map<String, Object> input = Map.of("skill", "invalid skill!");

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Invalid skill name format");
        assertThat(result.getContent()).contains("invalid skill!");
    }

    @Test
    void testExecute_SkillNotFound_ReturnsErrorWithAvailableSkills() {
        // Arrange - add some skills to registry
        Skill skill1 = createTestSkill("alert-analysis", "Analyzes alerts");
        Skill skill2 = createTestSkill("code-review", "Reviews code");
        mockRegistry.addSkill(skill1);
        mockRegistry.addSkill(skill2);

        Map<String, Object> input = Map.of("skill", "nonexistent-skill");

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Skill not found: 'nonexistent-skill'");
        assertThat(result.getContent()).contains("Available skills:");
        assertThat(result.getContent()).contains("alert-analysis");
        assertThat(result.getContent()).contains("code-review");
    }

    @Test
    void testExecute_SuccessfulSkillActivation_ReturnsFormattedResult() {
        // Arrange
        Skill skill = Skill.builder().name("alert-analysis")
                .metadata(SkillMetadata.builder().name("alert-analysis")
                        .description("Analyzes alerts from monitoring systems").build())
                .content(SkillContent
                        .of("# Alert Analysis\n\n## Instructions\n\n1. Collect alert information\n2. Query metrics"))
                .build();
        mockRegistry.addSkill(skill);

        Map<String, Object> input = Map.of("skill", "alert-analysis");

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("=== Skill Activated ===");
        assertThat(result.getContent()).contains("Skill: alert-analysis");
        assertThat(result.getContent()).contains("Description: Analyzes alerts from monitoring systems");
        assertThat(result.getContent()).contains("Allowed Tools: No restrictions");
        assertThat(result.getContent()).contains("Instructions:");
        assertThat(result.getContent()).contains("# Alert Analysis");
        assertThat(result.getContent()).contains("1. Collect alert information");
    }

    @Test
    void testExecute_SkillWithToolRestrictions_IncludesAllowedTools() {
        // Arrange
        Skill skill = Skill.builder().name("restricted-skill")
                .metadata(
                        SkillMetadata.builder().name("restricted-skill").description("A skill with tools restrictions")
                                .allowedToolsObjects(List.of(AllowedTool.parse("Read"), AllowedTool.parse("Write"),
                                        AllowedTool.parse("Bash(git:*)")))
                                .build())
                .content(SkillContent.of("Use only allowed tools")).build();
        mockRegistry.addSkill(skill);

        Map<String, Object> input = Map.of("skill", "restricted-skill");

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Allowed Tools:");
        assertThat(result.getContent()).contains("Read");
        assertThat(result.getContent()).contains("Write");
        assertThat(result.getContent()).contains("Bash(git:*)");
        assertThat(result.getContent()).doesNotContain("No restrictions");
    }

    @Test
    void testExecute_NamespacedSkillName_Success() {
        // Arrange - skill with namespaced name
        Skill skill = createTestSkill("ms-office-suite:pdf", "Process PDF documents in MS Office suite");
        mockRegistry.addSkill(skill);

        Map<String, Object> input = Map.of("skill", "ms-office-suite:pdf");

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Skill: ms-office-suite:pdf");
    }

    @Test
    void testExecute_SkillWithFiles_IncludesAvailableFiles() {
        // Arrange
        Skill skill = Skill.builder().name("file-skill")
                .metadata(SkillMetadata.builder().name("file-skill").description("A skill with various files").build())
                .content(SkillContent.of("Use the provided files"))
                .rootFiles(Map.of("config.yaml", "skills/file-skill/config.yaml", "helper.py",
                        "skills/file-skill/helper.py"))
                .scripts(Map.of("analyze.py", "skills/file-skill/scripts/analyze.py"))
                .references(Map.of("api.md", "skills/file-skill/references/api.md"))
                .assets(Map.of("loader.json", "skills/file-skill/assets/loader.json")).build();
        mockRegistry.addSkill(skill);

        Map<String, Object> input = Map.of("skill", "file-skill");

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Available Files:");
        assertThat(result.getContent()).contains("Root:");
        assertThat(result.getContent()).contains("config.yaml → skills/file-skill/config.yaml");
        assertThat(result.getContent()).contains("helper.py → skills/file-skill/helper.py");
        assertThat(result.getContent()).contains("Scripts:");
        assertThat(result.getContent()).contains("analyze.py → skills/file-skill/scripts/analyze.py");
        assertThat(result.getContent()).contains("References:");
        assertThat(result.getContent()).contains("api.md → skills/file-skill/references/api.md");
        assertThat(result.getContent()).contains("Assets:");
        assertThat(result.getContent()).contains("loader.json → skills/file-skill/assets/loader.json");
    }

    @Test
    void testExecute_SkillWithOnlyRootFiles_ShowsOnlyRootSection() {
        // Arrange
        Skill skill = Skill.builder().name("root-files-skill")
                .metadata(SkillMetadata.builder().name("root-files-skill").description("A skill with only root files")
                        .build())
                .content(SkillContent.of("Use root files"))
                .rootFiles(Map.of("config.yaml", "skills/root-files-skill/config.yaml")).build();
        mockRegistry.addSkill(skill);

        Map<String, Object> input = Map.of("skill", "root-files-skill");

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Available Files:");
        assertThat(result.getContent()).contains("Root:");
        assertThat(result.getContent()).contains("config.yaml → skills/root-files-skill/config.yaml");
        assertThat(result.getContent()).doesNotContain("Scripts:");
        assertThat(result.getContent()).doesNotContain("References:");
        assertThat(result.getContent()).doesNotContain("Assets:");
    }

    @Test
    void testGetDefinition_EmptyRegistry_ShowsNoSkills() {
        // Arrange - empty registry
        SkillTool emptySkillTool = new SkillTool(mockRegistry);

        // Act
        String description = emptySkillTool.getDefinition().getDescription();

        // Assert
        assertThat(description).contains("Execute specialized skills");
        assertThat(description).contains("(No skills currently available)");
    }

    @Test
    void testGetDefinition_WithSkills_ListsAllSkills() {
        // Arrange
        Skill skill1 = createTestSkill("alert-analysis", "Analyzes alerts");
        Skill skill2 = createTestSkill("code-review", "Reviews code");
        Skill skill3 = createTestSkill("data-analysis", "Analyzes data");
        mockRegistry.addSkill(skill1);
        mockRegistry.addSkill(skill2);
        mockRegistry.addSkill(skill3);

        // Act
        String description = skillTool.getDefinition().getDescription();

        // Assert
        assertThat(description).contains("Execute specialized skills");
        assertThat(description).contains("<available_skills>");
        assertThat(description).contains("</available_skills>");
        assertThat(description).contains("- alert-analysis: Analyzes alerts");
        assertThat(description).contains("- code-review: Reviews code");
        assertThat(description).contains("- data-analysis: Analyzes data");
    }

    @Test
    void testGetDefinition_InputSchema_ContainsRequiredFields() {
        // Act
        Map<String, Object> schema = skillTool.getDefinition().getInputSchema();

        // Assert
        assertThat(schema).containsKey("type");
        assertThat(schema).containsKey("properties");
        assertThat(schema).containsKey("required");

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey("skill");

        @SuppressWarnings("unchecked")
        Map<String, Object> skillProperty = (Map<String, Object>) properties.get("skill");
        assertThat(skillProperty.get("type")).isEqualTo("string");
        assertThat(skillProperty.get("pattern")).isEqualTo("^[a-zA-Z0-9._:-]+$");

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).contains("skill");
    }

    @Test
    void testGetDefinition_InputSchema_ContainsArgsField() {
        // Act
        Map<String, Object> schema = skillTool.getDefinition().getInputSchema();

        // Assert
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey("args");

        @SuppressWarnings("unchecked")
        Map<String, Object> argsProperty = (Map<String, Object>) properties.get("args");
        assertThat(argsProperty.get("type")).isEqualTo("string");
        assertThat(argsProperty.get("description")).asString().isNotBlank();

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).doesNotContain("args");
    }

    @Test
    void testExecute_ArgsAbsent_RendererReceivesEmptyString() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        final String[] capturedArgs = new String[1];
        SkillContentRenderer capturingRenderer = (s, a, c) -> {
            capturedArgs[0] = a;
            return s.getContent().getInstructions();
        };
        SkillTool tool = new SkillTool(mockRegistry, capturingRenderer);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(capturedArgs[0]).isEqualTo("");
    }

    @Test
    void testExecute_ArgsProvided_ForwardedToRendererVerbatim() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        final String[] capturedArgs = new String[1];
        SkillContentRenderer capturingRenderer = (s, a, c) -> {
            capturedArgs[0] = a;
            return s.getContent().getInstructions();
        };
        SkillTool tool = new SkillTool(mockRegistry, capturingRenderer);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "alert-analysis", "args", "\"foo bar\" baz")),
                emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(capturedArgs[0]).isEqualTo("\"foo bar\" baz");
    }

    @Test
    void testExecute_ArgsAtMaxLength_AcceptedAndForwarded() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        final String[] capturedArgs = new String[1];
        SkillContentRenderer capturingRenderer = (s, a, c) -> {
            capturedArgs[0] = a;
            return s.getContent().getInstructions();
        };
        SkillTool tool = new SkillTool(mockRegistry, capturingRenderer);

        String maxArgs = "a".repeat(SkillTool.MAX_ARGS_LENGTH);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "alert-analysis", "args", maxArgs)),
                emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(capturedArgs[0]).hasSize(SkillTool.MAX_ARGS_LENGTH);
    }

    @Test
    void testExecute_ArgsExceedsMaxLength_ReturnsError() {
        // Arrange
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        String overflowArgs = "a".repeat(SkillTool.MAX_ARGS_LENGTH + 1);

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(Map.of("skill", "alert-analysis", "args", overflowArgs)),
                emptyContext);

        // Assert
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("args too long");
    }

    @Test
    void testGetDefinition_HidesNonModelInvocableSkills() {
        // Arrange — model-invocable skill + user-only skill (model=false)
        Skill modelSkill = createTestSkill("alert-analysis", "Analyzes alerts");
        Skill userOnlySkill = Skill
                .builder().name("rollback").metadata(SkillMetadata.builder().name("rollback")
                        .description("Rollback last deploy").invokePolicy(InvokePolicy.of(true, false)).build())
                .content(SkillContent.of("body")).build();
        mockRegistry.addSkill(modelSkill);
        mockRegistry.addSkill(userOnlySkill);

        // Act
        String description = skillTool.getDefinition().getDescription();

        // Assert
        assertThat(description).contains("- alert-analysis: Analyzes alerts");
        assertThat(description).doesNotContain("rollback").doesNotContain("Rollback last deploy");
    }

    @Test
    void testGetDefinition_AllSkillsHidden_ShowsNoSkills() {
        // Arrange — only user-only skills
        Skill userOnlySkill = Skill
                .builder().name("rollback").metadata(SkillMetadata.builder().name("rollback")
                        .description("Rollback last deploy").invokePolicy(InvokePolicy.of(true, false)).build())
                .content(SkillContent.of("body")).build();
        mockRegistry.addSkill(userOnlySkill);

        // Act
        String description = skillTool.getDefinition().getDescription();

        // Assert — listing block omitted, fallback message shown
        assertThat(description).contains("(No skills currently available)");
        assertThat(description).doesNotContain("<available_skills>");
    }

    @Test
    void testExecute_NonModelInvocableSkill_TreatedAsNotFound() {
        // Arrange — user-only skill; LLM should not learn that this name exists.
        Skill modelSkill = createTestSkill("alert-analysis", "Analyzes alerts");
        Skill userOnlySkill = Skill
                .builder().name("rollback").metadata(SkillMetadata.builder().name("rollback")
                        .description("Rollback last deploy").invokePolicy(InvokePolicy.of(true, false)).build())
                .content(SkillContent.of("body")).build();
        mockRegistry.addSkill(modelSkill);
        mockRegistry.addSkill(userOnlySkill);

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(Map.of("skill", "rollback")), emptyContext);

        // Assert — error message must not reveal that 'rollback' exists.
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Skill not found: 'rollback'");
        assertThat(result.getContent()).contains("alert-analysis"); // visible skill listed
        assertThat(result.getContent()).doesNotContain("rollback,") // not in available skills list
                .doesNotContain(", rollback");
    }

    @Test
    void testExecute_EndToEnd_DefaultRendererSubstitutesPlaceholders() {
        // Arrange
        Skill skill = Skill.builder().name("greet")
                .metadata(SkillMetadata.builder().name("greet").description("Greets someone").build())
                .content(SkillContent.of("Hello $1, welcome to $2!")).build();
        mockRegistry.addSkill(skill);

        SkillTool tool = new SkillTool(mockRegistry, new DefaultSkillContentRenderer());

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "greet", "args", "Alice AIMON")), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Hello Alice, welcome to AIMON!");
        assertThat(result.getContent()).doesNotContain("$1").doesNotContain("$2");
    }

    @Test
    void testExecute_EndToEnd_DefaultRendererFormatsToolCallTokens() {
        // The model path (SkillTool) must surface !`cmd` / @file tokens in the canonical Bash(...) / Read(...) form so
        // that the LLM sees the same body that the user-invocation path would produce.
        Skill skill = Skill.builder().name("inspect")
                .metadata(SkillMetadata.builder().name("inspect").description("Inspect repo").build())
                .content(SkillContent.of("Run !`git status` and inspect @README.md please")).build();
        mockRegistry.addSkill(skill);

        SkillTool tool = new SkillTool(mockRegistry, new DefaultSkillContentRenderer());

        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "inspect")), emptyContext);

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Run Bash(git status) and inspect Read(README.md) please");
        assertThat(result.getContent()).doesNotContain("!`git status`").doesNotContain("@README.md");
    }

    @Test
    void testExecute_EndToEnd_DefaultRendererSubstitutesArgCount() {
        Skill skill = Skill.builder().name("count")
                .metadata(SkillMetadata.builder().name("count").description("Count args").build())
                .content(SkillContent.of("Got $ARG_COUNT args: $ARGUMENTS")).build();
        mockRegistry.addSkill(skill);

        SkillTool tool = new SkillTool(mockRegistry, new DefaultSkillContentRenderer());

        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "count", "args", "alpha beta gamma")),
                emptyContext);

        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Got 3 args: alpha beta gamma");
    }

    @Test
    void testExecute_ForkMode_DelegatesToForkExecutorAndFormatsAnswer() {
        // Arrange — fork-mode skill targeting a known agent name
        Skill skill = Skill.builder().name("review")
                .metadata(SkillMetadata.builder().name("review").description("Review code")
                        .executionMode(ExecutionMode.FORK).forkAgentName("code-reviewer").build())
                .content(SkillContent.of("Please review the changes")).build();
        mockRegistry.addSkill(skill);

        RecordingForkExecutor forkExecutor = new RecordingForkExecutor();
        forkExecutor.outcome = SkillForkOutcome.success("Looks good — ship it.");
        SkillTool tool = new SkillTool(mockRegistry, new DefaultSkillContentRenderer(), forkExecutor);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "review")), emptyContext);

        // Assert — output is the forked-result envelope, fork executor saw the rendered body and the skill itself
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("=== Skill Forked ===").contains("Skill: review")
                .contains("Agent: code-reviewer").contains("Looks good — ship it.")
                .doesNotContain("=== Skill Activated ===");
        assertThat(forkExecutor.calls).hasSize(1);
        assertThat(forkExecutor.calls.get(0).skill().getName()).isEqualTo("review");
        assertThat(forkExecutor.calls.get(0).goal()).isEqualTo("Please review the changes");
    }

    @Test
    void testExecute_ForkMode_ForkExecutorFailure_ReturnsError() {
        // Arrange — fork executor signals a domain failure (e.g., subagent missing)
        Skill skill = Skill.builder().name("review")
                .metadata(SkillMetadata.builder().name("review").description("Review code")
                        .executionMode(ExecutionMode.FORK).forkAgentName("code-reviewer").build())
                .content(SkillContent.of("body")).build();
        mockRegistry.addSkill(skill);

        SkillForkExecutor failing = (s, g, c) -> SkillForkOutcome.failure("subagent 'code-reviewer' not found");
        SkillTool tool = new SkillTool(mockRegistry, new NoOpSkillContentRenderer(), failing);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "review")), emptyContext);

        // Assert — the failure surfaces verbatim through ToolResult.error
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Skill fork failed for 'review'")
                .contains("subagent 'code-reviewer' not found");
    }

    @Test
    void testExecute_InlineMode_DoesNotInvokeForkExecutor() {
        // Arrange — default INLINE skill alongside a fork executor that would explode if called
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        RecordingForkExecutor forkExecutor = new RecordingForkExecutor();
        SkillTool tool = new SkillTool(mockRegistry, new NoOpSkillContentRenderer(), forkExecutor);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), emptyContext);

        // Assert — inline path stays unchanged, fork executor is untouched
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("=== Skill Activated ===").doesNotContain("=== Skill Forked ===");
        assertThat(forkExecutor.calls).isEmpty();
    }

    @Test
    void testExecute_ForkMode_DefaultConstructor_NoOpForkExecutorReturnsError() {
        // Arrange — backward-compatible 1-arg constructor wires NoOpSkillForkExecutor by default
        Skill skill = Skill.builder().name("review")
                .metadata(SkillMetadata.builder().name("review").description("Review code")
                        .executionMode(ExecutionMode.FORK).forkAgentName("code-reviewer").build())
                .content(SkillContent.of("body")).build();
        mockRegistry.addSkill(skill);
        SkillTool tool = new SkillTool(mockRegistry); // 1-arg ctor → NoOp fork executor

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "review")), emptyContext);

        // Assert — fork-mode skill against NoOp fork executor fails clearly
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("fork execution is not configured").contains("NoOpSkillForkExecutor");
    }

    @Test
    void testConstructor_FourArg_NullHookActivator_ThrowsException() {
        assertThatThrownBy(
                () -> new SkillTool(mockRegistry, new NoOpSkillContentRenderer(), new NoOpSkillForkExecutor(), null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("Hook activator cannot be null");
    }

    @Test
    void testExecute_HookActivator_ActivatedOncePerInvocationAndScopeClosed() {
        // Arrange — recording activator counts activate / close pairs
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        RecordingHookActivator activator = new RecordingHookActivator();
        SkillTool tool = new SkillTool(mockRegistry, new NoOpSkillContentRenderer(), new NoOpSkillForkExecutor(),
                activator);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), emptyContext);

        // Assert — successful invocation activates exactly once and closes exactly once with the same skill
        assertThat(result.isError()).isFalse();
        assertThat(activator.activatedSkills).containsExactly("alert-analysis");
        assertThat(activator.closeCount).isEqualTo(1);
    }

    @Test
    void testExecute_HookActivator_ScopeClosedOnRendererFailure() {
        // Arrange — renderer throws, hook scope must still be closed
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);

        SkillContentRenderer failingRenderer = (s, a, c) -> {
            throw new IllegalStateException("boom");
        };
        RecordingHookActivator activator = new RecordingHookActivator();
        SkillTool tool = new SkillTool(mockRegistry, failingRenderer, new NoOpSkillForkExecutor(), activator);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), emptyContext);

        // Assert — error returned, scope still cleaned up
        assertThat(result.isError()).isTrue();
        assertThat(activator.activatedSkills).hasSize(1);
        assertThat(activator.closeCount).isEqualTo(1);
    }

    @Test
    void testExecute_HookActivator_NotInvokedWhenSkillMissing() {
        // Arrange — registry has no skill named "ghost"; activator must not be touched
        RecordingHookActivator activator = new RecordingHookActivator();
        SkillTool tool = new SkillTool(mockRegistry, new NoOpSkillContentRenderer(), new NoOpSkillForkExecutor(),
                activator);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "ghost")), emptyContext);

        // Assert — error returned and activator never reached
        assertThat(result.isError()).isTrue();
        assertThat(activator.activatedSkills).isEmpty();
        assertThat(activator.closeCount).isZero();
    }

    @Test
    void testExecute_DefaultConstructor_UsesNoOpHookActivator() {
        // Arrange — single-arg constructor should wire NoOpSkillHookActivator and remain backward compatible
        Skill skill = createTestSkill("alert-analysis", "Analyzes alerts");
        mockRegistry.addSkill(skill);
        SkillTool tool = new SkillTool(mockRegistry);

        // Act
        ToolResult result = tool.execute(ToolInput.of(Map.of("skill", "alert-analysis")), emptyContext);

        // Assert — execution succeeds; behaviour unchanged from earlier 1-arg ctor contract
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("=== Skill Activated ===").contains("Skill: alert-analysis");
    }

    @Test
    void testExecute_SkillWithFilesInArbitraryDirectories_IncludesOtherFilesSection() {
        // Arrange — skill that has a file under templates/ which is not in any conventional category map
        Skill skill = Skill.builder().name("template-skill")
                .metadata(SkillMetadata.builder().name("template-skill").description("A skill with template files")
                        .build())
                .content(SkillContent.of("Use the template"))
                .putFile("templates/report.md", "/skills/template-skill/templates/report.md").build();
        mockRegistry.addSkill(skill);

        Map<String, Object> input = Map.of("skill", "template-skill");

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Available Files:");
        assertThat(result.getContent()).contains("Other Files:");
        assertThat(result.getContent()).contains("templates/report.md → /skills/template-skill/templates/report.md");
        assertThat(result.getContent()).doesNotContain("Root:");
        assertThat(result.getContent()).doesNotContain("Scripts:");
        assertThat(result.getContent()).doesNotContain("References:");
        assertThat(result.getContent()).doesNotContain("Assets:");
    }

    @Test
    void testExecute_SkillWithFilesAlreadyInCategories_OtherFilesSectionOmitted() {
        // Arrange — the file in getFiles() is already listed in scripts, so it must not appear in "Other Files:"
        Skill skill = Skill.builder().name("no-other-skill")
                .metadata(SkillMetadata.builder().name("no-other-skill").description("No extra files").build())
                .content(SkillContent.of("Use the script")).putScript("run.py", "/skills/no-other-skill/scripts/run.py")
                .putFile("scripts/run.py", "/skills/no-other-skill/scripts/run.py").build();
        mockRegistry.addSkill(skill);

        Map<String, Object> input = Map.of("skill", "no-other-skill");

        // Act
        ToolResult result = skillTool.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("Scripts:");
        assertThat(result.getContent()).doesNotContain("Other Files:");
    }

    @Test
    void testExecute_SkillWithBaseDir_DefaultRendererResolvesSkillDirPlaceholder() {
        // Arrange — skill body references ${AIMON_SKILL_DIR}; baseDir is set explicitly
        Skill skill = Skill.builder().name("dir-skill")
                .metadata(SkillMetadata.builder().name("dir-skill").description("A skill with a base dir").build())
                .content(SkillContent.of("See ${AIMON_SKILL_DIR}/templates/report.md for details"))
                .baseDir("/skills/dir-skill").build();
        mockRegistry.addSkill(skill);

        SkillTool toolWithDefaultRenderer = new SkillTool(mockRegistry, new DefaultSkillContentRenderer());

        Map<String, Object> input = Map.of("skill", "dir-skill");

        // Act
        ToolResult result = toolWithDefaultRenderer.execute(ToolInput.of(input), emptyContext);

        // Assert
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("/skills/dir-skill/templates/report.md");
        assertThat(result.getContent()).doesNotContain("${AIMON_SKILL_DIR}");
    }

    @Test
    void testExecute_SkillWithBaseDirAndDivergentResourcePath_PrefersExplicitBaseDir() {
        // Arrange — explicit baseDir differs from the parent of the only resource path. The explicit baseDir must win
        // over the derived fallback, pinning the getBaseDir().or(deriveSkillBaseDir(...)) precedence so an inversion is
        // caught.
        Skill skill = Skill.builder().name("precedence-skill")
                .metadata(SkillMetadata.builder().name("precedence-skill").description("base dir precedence").build())
                .content(SkillContent.of("Load ${AIMON_SKILL_DIR}/templates/report.md"))
                .baseDir("/skills/precedence-skill").putRootFile("SKILL.md", "/other/loc/SKILL.md").build();
        mockRegistry.addSkill(skill);

        SkillTool toolWithDefaultRenderer = new SkillTool(mockRegistry, new DefaultSkillContentRenderer());

        // Act
        ToolResult result = toolWithDefaultRenderer.execute(ToolInput.of(Map.of("skill", "precedence-skill")),
                emptyContext);

        // Assert — resolves to the explicit baseDir, not the derived parent "/other/loc"
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("/skills/precedence-skill/templates/report.md");
        assertThat(result.getContent()).doesNotContain("/other/loc/templates/report.md");
    }

    /**
     * Helper method to create a test skill.
     *
     * @param name
     *            The skill name
     * @param description
     *            The skill description
     * @return A new test skill
     */
    private Skill createTestSkill(String name, String description) {
        return Skill.builder().name(name).metadata(SkillMetadata.builder().name(name).description(description).build())
                .content(SkillContent.of("Test system prompt for " + name)).build();
    }

    /** Records hook-activator invocations and scope closes for behavioural assertions. */
    private static final class RecordingHookActivator implements SkillHookActivator {
        private final List<String> activatedSkills = new ArrayList<>();
        private int closeCount;

        @Override
        public SkillHookScope activate(Skill skill) {
            Objects.requireNonNull(skill, "Skill cannot be null");
            activatedSkills.add(skill.getName());
            return () -> closeCount++;
        }
    }

    /** Records SkillForkExecutor invocations to verify the inline path never invokes it. */
    private static final class RecordingForkExecutor implements SkillForkExecutor {
        private final List<Call> calls = new ArrayList<>();
        private SkillForkOutcome outcome = SkillForkOutcome.success("");

        @Override
        public SkillForkOutcome fork(Skill skill, String goal, ToolContext toolContext) {
            calls.add(new Call(skill, goal, toolContext));
            return outcome;
        }

        private record Call(Skill skill, String goal, ToolContext toolContext) {
        }
    }

    /** Mock implementation of SkillRegistry for testing. */
    private static class MockSkillRegistry implements SkillRegistry {
        private final Map<String, Skill> skills = new HashMap<>();

        void addSkill(Skill skill) {
            skills.put(skill.getName(), skill);
        }

        @Override
        public Optional<Skill> getSkill(String skillName) {
            return Optional.ofNullable(skills.get(skillName));
        }

        @Override
        public List<Skill> getAllSkills() {
            return new ArrayList<>(skills.values());
        }

        @Override
        public void reloadSkill(String skillName) {
            // No-op for testing
        }

        @Override
        public void reloadAll() {
            // No-op for testing
        }
    }
}
