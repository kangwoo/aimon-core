package at.aimon.browser.playwright.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.llm.ToolDefinition;
import at.aimon.core.tools.ToolContextKeys;

@DisplayName("ArtifactAwareBrowserTool Tests")
class BrowserToolTest {

    private at.aimon.browser.playwright.BrowserTool delegate;

    private VirtualFileSystem fileSystem;

    private ArtifactAwareBrowserTool browserTool;

    @BeforeEach
    void setUp() {
        delegate = mock(at.aimon.browser.playwright.BrowserTool.class);
        fileSystem = mock(VirtualFileSystem.class);

        // Stub delegate definition with a realistic schema
        final ToolDefinition delegateDefinition = ToolDefinition.of("Browser", "Automates a web browser.",
                Map.of("type", "object", "properties",
                        Map.of("action", Map.of("type", "string", "description", "The browser action to perform."),
                                "save_path", Map.of("type", "string", "description", "File path to save screenshot.")),
                        "required", java.util.List.of("action")));
        when(delegate.getDefinition()).thenReturn(delegateDefinition);

        browserTool = new ArtifactAwareBrowserTool(delegate, fileSystem);
    }

    @Nested
    @DisplayName("Constructor")
    class Constructor {

        @Test
        @DisplayName("Should throw NullPointerException when delegate is null")
        void shouldThrowOnNullDelegate() {
            assertThatThrownBy(() -> new ArtifactAwareBrowserTool(null, fileSystem))
                    .isInstanceOf(NullPointerException.class).hasMessageContaining("Delegate cannot be null");
        }

        @Test
        @DisplayName("Should create tool with valid delegate and fileSystem")
        void shouldCreateWithValidDelegateAndFileSystem() {
            final ArtifactAwareBrowserTool tool = new ArtifactAwareBrowserTool(delegate, fileSystem);
            assertThat(tool).isNotNull();
        }

        @Test
        @DisplayName("Should create tool with null fileSystem (fallback metadata)")
        void shouldCreateWithNullFileSystem() {
            final ArtifactAwareBrowserTool tool = new ArtifactAwareBrowserTool(delegate, null);
            assertThat(tool).isNotNull();
        }

    }

    @Nested
    @DisplayName("Tool Definition")
    class Definition {

        @Test
        @DisplayName("Should have tool name 'Browser'")
        void shouldHaveCorrectName() {
            final ToolDefinition definition = browserTool.getDefinition();
            assertThat(definition.getName()).isEqualTo("Browser");
        }

        @Test
        @DisplayName("Should include artifact parameter in schema")
        void shouldIncludeArtifactParameter() {
            final ToolDefinition definition = browserTool.getDefinition();
            @SuppressWarnings("unchecked")
            final Map<String, Object> properties = (Map<String, Object>) definition.getInputSchema().get("properties");
            assertThat(properties).containsKeys("action", "save_path", "artifact");
        }

        @Test
        @DisplayName("Should require action but not artifact")
        void shouldRequireCorrectParameters() {
            final ToolDefinition definition = browserTool.getDefinition();
            final Object required = definition.getInputSchema().get("required");
            assertThat(required).asList().contains("action");
            assertThat(required).asList().doesNotContain("artifact");
        }

    }

    @Nested
    @DisplayName("Delegation to existing BrowserTool")
    class Delegation {

        @Test
        @DisplayName("Should delegate execution to the original BrowserTool")
        void shouldDelegateExecution() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("delegated result"));

            final ToolInput input = ToolInput.of(Map.of("action", "click", "selector", "#btn"));
            final ToolResult result = browserTool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).isEqualTo("delegated result");
        }

        @Test
        @DisplayName("Should strip the artifact parameter before delegating")
        void shouldStripArtifactParameter() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("delegated result"));

            final ToolInput input = ToolInput
                    .of(Map.of("action", "screenshot", "save_path", "/tmp/page.png", "artifact", false));
            browserTool.execute(input, ToolContext.empty());

            // artifact is this decorator's own parameter — the delegate binds its input to a record and rejects
            // anything it did not declare, so passing the flag through would fail the call outright.
            final ArgumentCaptor<ToolInput> captor = ArgumentCaptor.forClass(ToolInput.class);
            verify(delegate).execute(captor.capture(), any(ToolContext.class));
            assertThat(captor.getValue().keys()).containsExactlyInAnyOrder("action", "save_path");
        }

        @Test
        @DisplayName("Should leave the input untouched when artifact is absent")
        void shouldNotCopyInputWhenArtifactAbsent() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("delegated result"));

            final ToolInput input = ToolInput.of(Map.of("action", "click", "selector", "#btn"));
            browserTool.execute(input, ToolContext.empty());

            final ArgumentCaptor<ToolInput> captor = ArgumentCaptor.forClass(ToolInput.class);
            verify(delegate).execute(captor.capture(), any(ToolContext.class));
            assertThat(captor.getValue()).isSameAs(input);
        }

        @Test
        @DisplayName("Should return delegate error result unchanged")
        void shouldReturnDelegateError() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.error("Action failed"));

            final ToolInput input = ToolInput.of(Map.of("action", "click"));
            final ToolResult result = browserTool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("Action failed");
        }

    }

    @Nested
    @DisplayName("Artifact Registration")
    class ArtifactRegistration {

        @Test
        @DisplayName("Should register artifact when screenshot + save_path + artifact=true")
        void shouldRegisterArtifactOnScreenshotSuccess() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            stubFileMetadata("/screenshots/page.png", 4096, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path", "/screenshots/page.png",
                    "artifact", true));

            final ToolResult result = browserTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).hasSize(1);

            final FileArtifact artifact = collector.getArtifacts().get(0);
            assertThat(artifact.getPath()).isEqualTo("/screenshots/page.png");
            assertThat(artifact.getFileName()).isEqualTo("page.png");
            assertThat(artifact.getSize()).isEqualTo(4096);
            assertThat(artifact.getMimeType()).hasValue("image/png");
        }

        @Test
        @DisplayName("Should not register artifact when artifact=false")
        void shouldNotRegisterWhenArtifactFalse() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path", "/tmp/internal.png",
                    "artifact", false));

            final ToolResult result = browserTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should not register artifact when save_path is not provided")
        void shouldNotRegisterWithoutSavePath() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot base64"));

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "artifact", true));

            final ToolResult result = browserTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should not register artifact when action is not screenshot")
        void shouldNotRegisterForNonScreenshotAction() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("clicked"));

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "click", "selector", "#btn", "artifact", true));

            final ToolResult result = browserTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should not register artifact when delegate returns error")
        void shouldNotRegisterOnFailure() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.error("Screenshot failed"));

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path", "/screenshots/fail.png",
                    "artifact", true));

            final ToolResult result = browserTool.execute(input, context);

            assertThat(result.isError()).isTrue();
            assertThat(collector.getArtifacts()).isEmpty();
        }

        @Test
        @DisplayName("Should register artifact when artifact parameter is omitted (defaults to true)")
        void shouldRegisterWhenArtifactOmitted() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            stubFileMetadata("/screenshots/default.png", 2048, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path",
                    "/screenshots/default.png"));

            final ToolResult result = browserTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("default.png");
        }

        @Test
        @DisplayName("Should succeed even without ArtifactCollector in context")
        void shouldSucceedWithoutCollector() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));

            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path", "/screenshots/no-collector.png",
                    "artifact", true));

            final ToolResult result = browserTool.execute(input, ToolContext.empty());

            assertThat(result.isSuccess()).isTrue();
        }

    }

    @Nested
    @DisplayName("Artifact Metadata")
    class ArtifactMetadata {

        @Test
        @DisplayName("Should include toolUseId from context")
        void shouldIncludeToolUseId() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            stubFileMetadata("/screenshots/with-id.png", 1024, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector)
                    .put(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY, "toolu_abc123").build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path",
                    "/screenshots/with-id.png", "artifact", true));

            browserTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getToolUseId()).hasValue("toolu_abc123");
        }

        @Test
        @DisplayName("Should have empty toolUseId when not in context")
        void shouldHaveEmptyToolUseIdWhenMissing() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            stubFileMetadata("/screenshots/no-id.png", 1024, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path",
                    "/screenshots/no-id.png", "artifact", true));

            browserTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getToolUseId()).isEmpty();
        }

        @Test
        @DisplayName("Should extract fileName from nested path")
        void shouldExtractFileName() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            stubFileMetadata("/reports/2024/capture.png", 3072, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path",
                    "/reports/2024/capture.png", "artifact", true));

            browserTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("capture.png");
        }

        @Test
        @DisplayName("Should use mimeType from file metadata")
        void shouldUseMimeTypeFromMetadata() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            stubFileMetadata("/screenshots/page.png", 4096, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path",
                    "/screenshots/page.png", "artifact", true));

            browserTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getMimeType()).hasValue("image/png");
        }

        @Test
        @DisplayName("Should use fallback metadata when fileSystem is null")
        void shouldUseFallbackWhenFileSystemNull() {
            final ArtifactAwareBrowserTool toolWithoutVfs = new ArtifactAwareBrowserTool(delegate, null);
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector)
                    .build();
            final ToolInput input = ToolInput
                    .of(Map.of("action", "screenshot", "save_path", "/screenshots/fallback.png", "artifact", true));

            toolWithoutVfs.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            final FileArtifact artifact = collector.getArtifacts().get(0);
            assertThat(artifact.getSize()).isEqualTo(0);
            assertThat(artifact.getMimeType()).hasValue("image/png");
            assertThat(artifact.getFileName()).isEqualTo("fallback.png");
        }

        @Test
        @DisplayName("Should use fallback metadata when getMetadata throws exception")
        void shouldUseFallbackOnMetadataException() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            when(fileSystem.getMetadata("/screenshots/error.png"))
                    .thenThrow(new RuntimeException("VFS error"));

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path",
                    "/screenshots/error.png", "artifact", true));

            browserTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            final FileArtifact artifact = collector.getArtifacts().get(0);
            assertThat(artifact.getSize()).isEqualTo(0);
            assertThat(artifact.getMimeType()).hasValue("image/png");
        }

    }

    @Nested
    @DisplayName("File Name Extraction Edge Cases")
    class FileNameExtraction {

        @Test
        @DisplayName("Should extract fileName from root-level path")
        void shouldExtractFromRootPath() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            stubFileMetadata("/page.png", 1024, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput
                    .of(Map.of("action", "screenshot", "save_path", "/page.png", "artifact", true));

            browserTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("page.png");
        }

        @Test
        @DisplayName("Should extract fileName from Windows-style path")
        void shouldExtractFromWindowsPath() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            stubFileMetadata("C:\\screenshots\\page.png", 1024, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(
                    Map.of("action", "screenshot", "save_path", "C:\\screenshots\\page.png", "artifact", true));

            browserTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("page.png");
        }

        @Test
        @DisplayName("Should strip trailing slashes before extracting fileName")
        void shouldStripTrailingSlashes() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("screenshot saved"));
            stubFileMetadata("/screenshots/page.png/", 1024, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(
                    Map.of("action", "screenshot", "save_path", "/screenshots/page.png/", "artifact", true));

            browserTool.execute(input, context);

            assertThat(collector.getArtifacts()).hasSize(1);
            assertThat(collector.getArtifacts().get(0).getFileName()).isEqualTo("page.png");
        }

    }

    @Nested
    @DisplayName("Permission Delegation")
    class PermissionDelegation {

        @Test
        @DisplayName("Should delegate getCustomPermissionRule to original BrowserTool")
        void shouldDelegatePermissionRule() {
            final at.aimon.core.agent.tool.permission.CustomToolPermissionRule mockRule = mock(
                    at.aimon.core.agent.tool.permission.CustomToolPermissionRule.class);
            when(delegate.getCustomPermissionRule()).thenReturn(mockRule);

            assertThat(browserTool.getCustomPermissionRule()).isSameAs(mockRule);
        }

    }

    @Nested
    @DisplayName("Return Value")
    class ReturnValue {

        @Test
        @DisplayName("Should return same success result as delegate BrowserTool")
        void shouldReturnDelegateSuccessResult() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.success("{\"sessionId\":\"bs_test\",\"action\":\"screenshot\"}"));
            stubFileMetadata("/screenshots/result.png", 1024, "image/png");

            final ArtifactCollector collector = new ArtifactCollector();
            final ToolContext context = ToolContext.builder().put(ToolContextKeys.ARTIFACT_COLLECTOR, collector).build();
            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path",
                    "/screenshots/result.png", "artifact", true));

            final ToolResult result = browserTool.execute(input, context);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getContent()).contains("bs_test");
            assertThat(result.getContent()).contains("screenshot");
        }

        @Test
        @DisplayName("Should return delegate error result unchanged even with artifact=true")
        void shouldReturnDelegateErrorResult() {
            when(delegate.execute(any(ToolInput.class), any(ToolContext.class)))
                    .thenReturn(ToolResult.error("Browser error"));

            final ToolInput input = ToolInput.of(Map.of("action", "screenshot", "save_path", "/screenshots/fail.png",
                    "artifact", true));

            final ToolResult result = browserTool.execute(input, ToolContext.empty());

            assertThat(result.isError()).isTrue();
            assertThat(result.getContent()).isEqualTo("Browser error");
        }

    }

    /**
     * Stubs fileSystem.getMetadata() to return a FileMetadata with the given size and mimeType.
     */
    private void stubFileMetadata(String path, long size, String mimeType) {
        final Instant now = Instant.now();
        final FileMetadata metadata = FileMetadata.builder().path(path).size(size).mimeType(mimeType).createdAt(now)
                .modifiedAt(now).build();
        when(fileSystem.getMetadata(path)).thenReturn(metadata);
    }

}
