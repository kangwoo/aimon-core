package at.aimon.browser.playwright.artifact;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.aimon.browser.playwright.BrowserTool;
import at.aimon.core.agent.artifact.ArtifactCollector;
import at.aimon.core.agent.artifact.ArtifactFileNames;
import at.aimon.core.agent.artifact.FileArtifact;
import at.aimon.core.agent.tool.AbstractTool;
import at.aimon.core.agent.tool.ToolCategories;
import at.aimon.core.agent.tool.ToolContext;
import at.aimon.core.agent.tool.ToolInput;
import at.aimon.core.agent.tool.ToolResult;
import at.aimon.core.agent.tool.permission.CustomToolPermissionAware;
import at.aimon.core.agent.tool.permission.CustomToolPermissionRule;
import at.aimon.core.filesystem.FileMetadata;
import at.aimon.core.filesystem.VirtualFileSystem;
import at.aimon.core.tools.ToolContextKeys;

/**
 * Artifact-aware BrowserTool decorator.
 *
 * <p>
 * Delegates browser automation to the existing {@link BrowserTool} and adds artifact
 * registration via the {@code artifact} parameter. When {@code artifact=true}, the action is {@code screenshot}, a
 * {@code save_path} is provided, and the delegate execution succeeds, a {@link FileArtifact} is registered in the
 * {@link ArtifactCollector} from the tool context.
 *
 * <p>
 * Uses the same tool name ({@code "Browser"}) as the original BrowserTool. Only one of the two should be registered per
 * agent configuration.
 *
 * <p>
 * Example usage:
 *
 * <pre>
 * {
 *     &#64;code
 *     // For environments that need artifact support (Web API)
 *     BrowserTool delegate = new BrowserTool(...);
 *     Tool browserTool = new ArtifactAwareBrowserTool(delegate, vfs);
 *
 *     // For environments without artifact needs (CLI)
 *     Tool browserTool = new BrowserTool(...);
 * }
 * </pre>
 *
 * @see BrowserTool
 * @see ArtifactCollector
 * @see FileArtifact
 */
public class ArtifactAwareBrowserTool extends AbstractTool implements CustomToolPermissionAware {

    public static final String TOOL_NAME = "Browser";

    private static final Logger LOG = LoggerFactory.getLogger(ArtifactAwareBrowserTool.class);

    private static final String DEFAULT_MIME_TYPE = "image/png";

    private static final String ARTIFACT_PARAM = "artifact";

    private final BrowserTool delegate;

    private final VirtualFileSystem fileSystem;

    /**
     * Creates a new artifact-aware BrowserTool.
     *
     * @param delegate
     *            The existing BrowserTool to delegate execution to (must not be null)
     * @param fileSystem
     *            The virtual file system for metadata retrieval (nullable; when null, fallback metadata is used)
     * @throws NullPointerException
     *             if delegate is null
     */
    public ArtifactAwareBrowserTool(BrowserTool delegate, VirtualFileSystem fileSystem) {
        super(TOOL_NAME, requireNonNullDelegate(delegate).getDefinition().getDescription(), ToolCategories.EXECUTION,
                createInputSchema(delegate));
        this.delegate = delegate;
        this.fileSystem = fileSystem;
    }

    private static BrowserTool requireNonNullDelegate(BrowserTool delegate) {
        return Objects.requireNonNull(delegate, "Delegate cannot be null");
    }

    /**
     * Creates input schema by extending the delegate's schema with the {@code artifact} parameter.
     *
     * <p>
     * The rebuilt map keeps only the keys named here, so anything the delegate declares and this method forgets is
     * silently dropped. {@code additionalProperties} is carried over rather than hardcoded for exactly that reason: a
     * call routed through this decorator must be judged by the same strictness as one that reaches the delegate
     * directly, and copying the value keeps the two from drifting apart when either side changes.
     */
    private static Map<String, Object> createInputSchema(BrowserTool delegate) {
        final Map<String, Object> delegateSchema = delegate.getDefinition().getInputSchema();

        @SuppressWarnings("unchecked")
        final Map<String, Object> delegateProperties = (Map<String, Object>) delegateSchema.get("properties");

        final Map<String, Object> properties = new LinkedHashMap<>(delegateProperties);
        properties.put(ARTIFACT_PARAM,
                Map.of("type", "boolean", "description",
                        "Whether the screenshot file is intended for user download "
                                + "(e.g., page captures, visual reports). "
                                + "Defaults to true. Set to false for internal/temporary screenshots."));

        final Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", delegateSchema.getOrDefault("required", List.of()));
        if (delegateSchema.containsKey("additionalProperties")) {
            schema.put("additionalProperties", delegateSchema.get("additionalProperties"));
        }
        return Map.copyOf(schema);
    }

    /**
     * Executes a browser action and optionally registers the screenshot as an artifact.
     *
     * <p>
     * Delegates execution to the original BrowserTool. If the execution succeeds, the action is {@code screenshot}, a
     * {@code save_path} is provided, and {@code artifact=true}, registers a {@link FileArtifact} in the
     * {@link ArtifactCollector}. Artifact registration failure is logged as a warning but does not affect the tool
     * result.
     *
     * @param input
     *            The input parameters containing action, save_path, and optional artifact flag
     * @param context
     *            The execution context containing ArtifactCollector and toolUseId
     * @return The result from the delegate BrowserTool (never null)
     */
    @Override
    public ToolResult execute(ToolInput input, ToolContext context) {
        Objects.requireNonNull(input, "Input cannot be null");
        Objects.requireNonNull(context, "Context cannot be null");

        final boolean isArtifact = input.getBoolean(ARTIFACT_PARAM, true);

        final ToolResult result = delegate.execute(stripArtifactFlag(input), context);

        if (result.isSuccess() && isArtifact) {
            final String action = input.getStringOrNull("action");
            final String savePath = input.getStringOrNull("save_path");
            if ("screenshot".equals(action) && savePath != null) {
                registerArtifact(savePath, context);
            }
        }

        return result;
    }

    /**
     * Removes the decorator-only {@code artifact} key before handing the input to the delegate.
     *
     * <p>
     * {@code artifact} is declared by this decorator alone — the delegate's schema never had it. The delegate binds its
     * input to a record and rejects keys it does not declare, so passing the flag through would fail the whole call on
     * a
     * parameter the delegate was never meant to see.
     */
    private static ToolInput stripArtifactFlag(ToolInput input) {
        if (!input.has(ARTIFACT_PARAM)) {
            return input;
        }
        final Map<String, Object> parameters = new LinkedHashMap<>(input.toMap());
        parameters.remove(ARTIFACT_PARAM);
        return ToolInput.of(parameters);
    }

    /**
     * Registers a screenshot file artifact in the ArtifactCollector.
     *
     * <p>
     * On failure, logs a warning only. The screenshot has already been saved, so artifact metadata retrieval failure
     * must not change the tool result to an error.
     */
    private void registerArtifact(String filePath, ToolContext context) {
        context.get(ToolContextKeys.ARTIFACT_COLLECTOR).ifPresent(collector -> {
            try {
                String mimeType = DEFAULT_MIME_TYPE;
                long size = 0;
                if (fileSystem != null) {
                    try {
                        final FileMetadata metadata = fileSystem.getMetadata(filePath);
                        mimeType = metadata.getMimeType().orElse(DEFAULT_MIME_TYPE);
                        size = metadata.getSize();
                    } catch (Exception e) {
                        LOG.warn("Failed to get metadata for artifact: {}", filePath, e);
                    }
                }

                collector.add(FileArtifact.builder().path(filePath).size(size).mimeType(mimeType)
                        .fileName(ArtifactFileNames.extractFileName(filePath))
                        .toolUseId(context.get(ToolContextKeys.CURRENT_TOOL_USE_ID_KEY).orElse(null)).build());
            } catch (Exception e) {
                LOG.warn("Failed to register artifact: {}", filePath, e);
            }
        });
    }

    @Override
    public CustomToolPermissionRule getCustomPermissionRule() {
        return delegate.getCustomPermissionRule();
    }

}
